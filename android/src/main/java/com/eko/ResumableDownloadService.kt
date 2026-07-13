package com.eko

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A foreground service that manages resumable downloads in the background.
 * This ensures downloads continue even when the app is in the background or the screen is off.
 */
class ResumableDownloadService : Service() {

  companion object {
    private const val TAG = "ResumableDownloadSvc"
    private const val WAKELOCK_TAG = "ResumableDownloadService::WakeLock"

    // Notification group for grouping all download notifications together
    private const val NOTIFICATION_GROUP_KEY = "com.eko.DOWNLOAD_GROUP"

    // Action constants for Intent
    const val ACTION_START_DOWNLOAD = "com.eko.action.START_DOWNLOAD"
    const val ACTION_PAUSE_DOWNLOAD = "com.eko.action.PAUSE_DOWNLOAD"
    const val ACTION_RESUME_DOWNLOAD = "com.eko.action.RESUME_DOWNLOAD"
    const val ACTION_CANCEL_DOWNLOAD = "com.eko.action.CANCEL_DOWNLOAD"
    const val ACTION_STOP_SERVICE = "com.eko.action.STOP_SERVICE"

    // Extra keys
    const val EXTRA_DOWNLOAD_ID = "download_id"
    const val EXTRA_URL = "url"
    const val EXTRA_DESTINATION = "destination"
    const val EXTRA_HEADERS = "headers"
    const val EXTRA_START_BYTE = "start_byte"
    const val EXTRA_TOTAL_BYTES = "total_bytes"
    const val EXTRA_IS_ALLOWED_OVER_METERED = "is_allowed_over_metered"
  }

  private val binder = LocalBinder()
  private val executorService: ExecutorService = Executors.newFixedThreadPool(DownloadConstants.DOWNLOAD_THREAD_POOL_SIZE)
  private val activeDownloads = ConcurrentHashMap<String, DownloadJob>()
  private var wakeLock: PowerManager.WakeLock? = null
  private var listener: ResumableDownloader.DownloadListener? = null

  // Generation counter per download ID - incremented each time a new download starts
  // This is separate from sessionToken to ensure we can detect stale events even
  // across cancel/restart cycles where the job is removed and re-added
  private val downloadGeneration = ConcurrentHashMap<String, Long>()

  // --- Unmetered-network gate (isAllowedOverMetered=false on Android < 14 / UIDT fallback) ---
  // All gate state below is guarded by gateLock. The lock serializes transitions
  // between "waiting" and "running" against user pause/cancel and network
  // callbacks, so a download can never be started and paused concurrently, and
  // the callback can never be unregistered while a transition is in flight.
  private val gateLock = Any()
  // Downloads waiting for an unmetered network before (re)starting
  private val waitingForUnmetered = mutableSetOf<String>()
  // Gated downloads currently transferring, keyed to the network they are bound to
  private val gatedRunning = mutableMapOf<String, Network>()
  // Unmetered networks currently known to be available
  private val unmeteredNetworks = mutableSetOf<Network>()
  @Volatile
  private var unmeteredCallback: ConnectivityManager.NetworkCallback? = null
  // Gate work runs on a dedicated single thread: network callbacks must return
  // quickly (they share the process-wide ConnectivityThread with every other
  // library), and a single thread preserves onAvailable/onLost ordering
  private val gateExecutor: ExecutorService = Executors.newSingleThreadExecutor()

  private val connectivityManager by lazy {
    getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  }

  // Throttle progress logging to reduce log noise
  private val lastProgressLogTime = ConcurrentHashMap<String, Long>()

  // Shared ResumableDownloader instance
  val resumableDownloader = ResumableDownloader()

  inner class LocalBinder : Binder() {
    fun getService(): ResumableDownloadService = this@ResumableDownloadService
  }

  /**
   * Creates a validating listener wrapper that checks session token and generation
   * before delegating events to the actual listener. This prevents stale events
   * from old download sessions from being processed.
   *
   * On a valid terminal event (complete/error) the service's job entry is always
   * removed - the session/generation check already guarantees only the current
   * session's listener can get here, and a leaked entry would keep the foreground
   * service (and its notification) alive forever via stopServiceIfIdle().
   *
   * @param id The download ID
   * @param sessionToken The session token from when the download was started
   * @param generation The generation counter from when the download was started
   */
  private fun createValidatingListener(
    id: String,
    sessionToken: Long,
    generation: Long
  ): ResumableDownloader.DownloadListener {
    return object : ResumableDownloader.DownloadListener {

      private fun isValid(): Boolean {
        val currentJob = activeDownloads[id]
        val currentGeneration = downloadGeneration[id] ?: 0
        return currentJob != null &&
               currentJob.sessionToken == sessionToken &&
               currentGeneration == generation
      }

      private fun logStale(event: String) {
        val currentJob = activeDownloads[id]
        val currentGeneration = downloadGeneration[id] ?: 0
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Ignoring stale $event for $id (session: my=$sessionToken vs job=${currentJob?.sessionToken}, gen: my=$generation vs current=$currentGeneration)")
      }

      override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) {
        if (isValid()) {
          listener?.onBegin(id, expectedBytes, headers)
          updateNotification()
        } else {
          logStale("onBegin")
        }
      }

      override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) {
        val currentJob = activeDownloads[id]
        val currentGeneration = downloadGeneration[id] ?: 0

        // Throttle progress logging to reduce noise
        val now = System.currentTimeMillis()
        val lastLogTime = lastProgressLogTime[id] ?: 0L
        if (now - lastLogTime >= DownloadConstants.PROGRESS_LOG_INTERVAL_MS) {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "onProgress: id=$id, bytes=$bytesDownloaded, myToken=$sessionToken, jobToken=${currentJob?.sessionToken}, myGen=$generation, currentGen=$currentGeneration")
          lastProgressLogTime[id] = now
        }

        if (isValid()) {
          listener?.onProgress(id, bytesDownloaded, bytesTotal)
        } else {
          logStale("onProgress")
        }
      }

      override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
        if (isValid()) {
          listener?.onComplete(id, location, bytesDownloaded, bytesTotal)
          cleanupTerminalDownload(id)
        } else {
          logStale("onComplete")
        }
      }

      override fun onError(id: String, error: String, errorCode: Int) {
        if (isValid()) {
          // A gated download whose bound network died raises a socket error before
          // the ConnectivityManager onLost callback arrives - reclassify it as
          // "waiting for unmetered network" instead of failing the download
          if (regateAfterNetworkLoss(id, errorCode)) {
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Gated download $id lost its unmetered network mid-transfer, re-waiting (suppressed error: $error)")
            updateNotification()
            return
          }
          // The grace period inside regateAfterNetworkLoss may have raced a
          // cancel or restart - re-check validity before reporting the error
          if (!isValid()) {
            logStale("onError (after network grace check)")
            return
          }
          listener?.onError(id, error, errorCode)
          cleanupTerminalDownload(id)
        } else {
          logStale("onError")
        }
      }
    }
  }

  data class DownloadJob(
    val id: String,
    val url: String,
    val destination: String,
    val headers: Map<String, String>,
    val startByte: Long,
    val totalBytes: Long,
    val sessionToken: Long = System.nanoTime() // Unique token for this download session
  )

  override fun onCreate() {
    super.onCreate()
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Service created")
    createNotificationChannel()
  }

  /**
   * Extract headers from Intent, handling deprecated API for backward compatibility.
   */
  @Suppress("UNCHECKED_CAST", "DEPRECATION")
  private fun getHeadersFromIntent(intent: Intent): HashMap<String, String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      intent.getSerializableExtra(EXTRA_HEADERS, HashMap::class.java) as? HashMap<String, String> ?: HashMap()
    } else {
      @Suppress("DEPRECATION")
      intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String> ?: HashMap()
    }
  }

  override fun onBind(intent: Intent?): IBinder {
    return binder
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "onStartCommand: action=${intent?.action}")

    when (intent?.action) {
      ACTION_START_DOWNLOAD -> {
        val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
        val url = intent.getStringExtra(EXTRA_URL)
        val destination = intent.getStringExtra(EXTRA_DESTINATION)
        val headers = getHeadersFromIntent(intent)
        val startByte = intent.getLongExtra(EXTRA_START_BYTE, 0)
        val totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, -1)
        val isAllowedOverMetered = intent.getBooleanExtra(EXTRA_IS_ALLOWED_OVER_METERED, true)

        if (id != null && url != null && destination != null) {
          startDownloadInternal(id, url, destination, headers, startByte, totalBytes, isAllowedOverMetered)
        }
      }
      ACTION_PAUSE_DOWNLOAD -> {
        val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
        if (id != null) {
          pauseDownload(id)
        }
      }
      ACTION_RESUME_DOWNLOAD -> {
        val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
        if (id != null) {
          resumeDownload(id)
        }
      }
      ACTION_CANCEL_DOWNLOAD -> {
        val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
        if (id != null) {
          cancelDownload(id)
        }
      }
      ACTION_STOP_SERVICE -> {
        stopServiceIfIdle()
      }
      else -> {
        // Downloader starts the service with a blank intent and delivers the actual
        // work over the binder. After Context.startForegroundService() the service
        // MUST call startForeground() promptly or the system kills the app - do it
        // here instead of relying on the bound startDownload call arriving in time.
        startForegroundWithNotification()
      }
    }

    // NOT_STICKY: all download state is in-memory (recovery goes through the
    // module's persisted snapshots on the next app launch), so a sticky restart
    // with a null intent would only produce an idle foreground service whose
    // notification nothing ever clears.
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Service destroyed")
    synchronized(gateLock) {
      waitingForUnmetered.clear()
      gatedRunning.clear()
      maybeUnregisterUnmeteredCallbackLocked()
    }
    gateExecutor.shutdownNow()
    releaseWakeLock()
    executorService.shutdownNow()
    isForeground = false
    super.onDestroy()
  }

  /**
   * Shared terminal cleanup for a download that completed or failed.
   * Must always run on a valid terminal event or the service job entry leaks and
   * keeps the foreground service (and its notification) alive forever.
   */
  private fun cleanupTerminalDownload(id: String) {
    activeDownloads.remove(id)
    lastProgressLogTime.remove(id)
    synchronized(gateLock) {
      clearUnmeteredGateLocked(id)
    }
    stopServiceIfIdle()
  }

  fun setDownloadListener(listener: ResumableDownloader.DownloadListener?) {
    this.listener = listener
  }

  fun startDownload(
    id: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    startByte: Long = 0,
    totalBytes: Long = -1,
    isAllowedOverMetered: Boolean = true
  ) {
    startDownloadInternal(id, url, destination, headers, startByte, totalBytes, isAllowedOverMetered)
  }

  private fun startDownloadInternal(
    id: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    startByte: Long,
    totalBytes: Long,
    isAllowedOverMetered: Boolean = true
  ) {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Starting download: $id from byte $startByte (isAllowedOverMetered=$isAllowedOverMetered)")

    // Start foreground service if not already
    startForegroundWithNotification()

    // Increment generation counter for this download ID
    // This ensures we can detect stale events even if job was removed and re-added
    val generation = (downloadGeneration[id] ?: 0) + 1
    downloadGeneration[id] = generation
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Download $id starting with generation $generation")

    val job = DownloadJob(id, url, destination, headers, startByte, totalBytes)
    activeDownloads[id] = job

    if (!isAllowedOverMetered) {
      // Register the download in a waiting state and let the unmetered-network
      // gate start it: it starts immediately when an unmetered network is already
      // connected, otherwise it waits like DownloadManager's "queued for WiFi"
      // state. No wake lock while waiting - one is acquired when the transfer
      // actually starts.
      val gated = synchronized(gateLock) {
        // Drop any gate bookkeeping left over from a previous download with the
        // same ID so a stale gatedRunning entry can't wrongly pause the new one
        clearUnmeteredGateLocked(id)
        resumableDownloader.prepareWaitingDownload(id, url, destination, headers, startByte, totalBytes)
        if (ensureUnmeteredCallbackLocked()) {
          waitingForUnmetered.add(id)
          // The onAvailable replay only fires when the callback is first
          // registered - drain explicitly for networks we already know about
          unmeteredNetworks.firstOrNull()?.let { startWaitingDownloadsLocked(it) }
          true
        } else {
          false
        }
      }
      if (!gated) {
        // The constraint can't be enforced (network callback registration
        // failed) - fail the download loudly instead of stranding it forever
        RNBackgroundDownloaderModuleImpl.logE(TAG, "Cannot register unmetered network callback, failing download $id")
        activeDownloads.remove(id)
        resumableDownloader.cancel(id)
        listener?.onError(id, "Cannot enforce isAllowedOverMetered=false: network callback registration failed", -1)
        stopServiceIfIdle()
        return
      }
      updateNotification()
      return
    }

    // Clear stale gate bookkeeping from a previous gated download with this ID
    synchronized(gateLock) {
      clearUnmeteredGateLocked(id)
    }

    acquireWakeLock()

    // Create a validating listener wrapper
    val serviceListener = createValidatingListener(id, job.sessionToken, generation)

    // Start the download using ResumableDownloader
    resumableDownloader.startDownload(
      id = id,
      url = url,
      destination = destination,
      headers = headers,
      listener = serviceListener,
      startByte = startByte,
      totalBytes = totalBytes
    )
  }

  fun pauseDownload(id: String): Boolean {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Pausing download: $id")
    // Both steps under the gate lock: a user pause takes the download out of the
    // unmetered gate atomically, so a concurrent onAvailable drain can't claim it
    // and restart the transfer the user just paused
    val result = synchronized(gateLock) {
      clearUnmeteredGateLocked(id)
      resumableDownloader.pause(id)
    }
    if (result) {
      updateNotification()
      // Don't stop service - keep it alive for potential resume
    }
    return result
  }

  fun resumeDownload(id: String): Boolean {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Resuming download: $id")

    if (this.listener == null) return false
    val currentJob = activeDownloads[id] ?: return false
    val sessionToken = currentJob.sessionToken
    val generation = downloadGeneration[id] ?: 0
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Resuming $id with session=$sessionToken, generation=$generation")

    // Make sure service is in foreground
    startForegroundWithNotification()

    // Unmetered-only downloads resume through the network gate so the transfer
    // only starts (and stays) on an unmetered network
    val state = resumableDownloader.getState(id)
    if (state != null && !state.isAllowedOverMetered) {
      val gated = synchronized(gateLock) {
        if (!state.isPaused.get()) {
          // Already transferring (or about to) - nothing to resume, and re-adding
          // a running download to the waiting set would corrupt the gate state
          RNBackgroundDownloaderModuleImpl.logW(TAG, "Download $id is not paused")
          return@synchronized false
        }
        if (!ensureUnmeteredCallbackLocked()) {
          RNBackgroundDownloaderModuleImpl.logE(TAG, "Cannot register unmetered network callback for $id")
          return@synchronized false
        }
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Resume of $id waits for an unmetered network")
        waitingForUnmetered.add(id)
        // The onAvailable replay only fires when the callback is first
        // registered - drain explicitly for networks we already know about
        unmeteredNetworks.firstOrNull()?.let { startWaitingDownloadsLocked(it) }
        true
      }
      if (gated) {
        updateNotification()
      }
      return gated
    }

    acquireWakeLock()

    // Create a validating listener wrapper
    val serviceListener = createValidatingListener(id, sessionToken, generation)

    return resumableDownloader.resume(id, serviceListener)
  }

  fun cancelDownload(id: String): Boolean {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelling download: $id")
    // Under the gate lock so an in-flight onAvailable drain can't restart the
    // download between the bookkeeping removal and the downloader cancel
    val result = synchronized(gateLock) {
      activeDownloads.remove(id)
      clearUnmeteredGateLocked(id)
      resumableDownloader.cancel(id)
    }
    stopServiceIfIdle()
    return result
  }

  // --- Unmetered-network gate ---

  /**
   * Register the shared callback for unmetered networks (idempotent). Caller must
   * hold gateLock. Returns false when registration failed, in which case the gate
   * cannot work and gated downloads must not be parked in the waiting set.
   *
   * NET_CAPABILITY_NOT_VPN is removed (Builder default) so unmetered VPN networks
   * are accepted, consistent with the UIDT job's NetworkRequest.
   * The callback bodies hop to gateExecutor: network callbacks share the
   * process-wide ConnectivityThread, which must never be blocked by socket
   * teardown or notification IPC; a single-threaded executor also preserves the
   * onAvailable/onLost ordering.
   */
  private fun ensureUnmeteredCallbackLocked(): Boolean {
    if (unmeteredCallback != null) return true

    val request = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
      .build()

    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        runOnGateExecutor {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network available: $network")
          synchronized(gateLock) {
            unmeteredNetworks.add(network)
            startWaitingDownloadsLocked(network)
          }
          updateNotification()
        }
      }

      override fun onLost(network: Network) {
        // Also delivered when a network stops satisfying the request
        // (e.g. WiFi becomes metered), not only on disconnect
        runOnGateExecutor {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network lost: $network")
          synchronized(gateLock) {
            unmeteredNetworks.remove(network)
            pauseGatedDownloadsOnLocked(network)
          }
          updateNotification()
        }
      }
    }

    return try {
      connectivityManager.registerNetworkCallback(request, callback)
      unmeteredCallback = callback
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Registered unmetered network callback")
      true
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to register network callback: ${e.message}")
      false
    }
  }

  private fun runOnGateExecutor(block: () -> Unit) {
    try {
      gateExecutor.execute(block)
    } catch (e: java.util.concurrent.RejectedExecutionException) {
      // Service is being destroyed - gate state is going away with it
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Gate executor rejected task (service destroyed)")
    }
  }

  /** Caller must hold gateLock. */
  private fun maybeUnregisterUnmeteredCallbackLocked() {
    if (waitingForUnmetered.isNotEmpty() || gatedRunning.isNotEmpty()) return
    val callback = unmeteredCallback ?: return
    unmeteredCallback = null
    unmeteredNetworks.clear()
    try {
      connectivityManager.unregisterNetworkCallback(callback)
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Unregistered unmetered network callback")
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to unregister network callback: ${e.message}")
    }
  }

  /**
   * Remove a download from the gate bookkeeping (terminal state, user pause or
   * cancel). Caller must hold gateLock.
   */
  private fun clearUnmeteredGateLocked(id: String) {
    waitingForUnmetered.remove(id)
    gatedRunning.remove(id)
    // Drop the network binding so a later non-gated restart of the same ID
    // doesn't inherit a stale network
    resumableDownloader.getState(id)?.network = null
    maybeUnregisterUnmeteredCallbackLocked()
  }

  /**
   * Start every waiting unmetered-only download on the given network.
   * Caller must hold gateLock; callers update the notification afterwards.
   */
  private fun startWaitingDownloadsLocked(network: Network) {
    for (id in waitingForUnmetered.toList()) {
      val job = activeDownloads[id]
      val state = resumableDownloader.getState(id)
      if (job == null || state == null) {
        // Cancelled concurrently (cancel runs under gateLock) - drop the entry
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Gated download $id has no job/state, dropping")
        waitingForUnmetered.remove(id)
        continue
      }

      waitingForUnmetered.remove(id)
      acquireWakeLock()
      state.network = network
      // Record the binding before resuming so an immediate transfer error
      // classifies correctly in regateAfterNetworkLoss
      gatedRunning[id] = network
      val generation = downloadGeneration[id] ?: 0
      val serviceListener = createValidatingListener(id, job.sessionToken, generation)
      if (resumableDownloader.resume(id, serviceListener)) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Started gated download $id on unmetered network $network")
      } else {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to start gated download $id")
        gatedRunning.remove(id)
        state.network = null
      }
    }
  }

  /**
   * If a gated download errored because its bound network died or became metered,
   * move it back into the waiting set (silently - no error is surfaced) and return
   * true. Returns false for genuine errors (HTTP failures, or IO errors while the
   * bound network is verifiably still unmetered) so they propagate normally.
   *
   * Runs on the download thread, which is about to exit - blocking it briefly is
   * fine. The grace re-check is needed because the socket abort from a network
   * loss/metering change usually lands before ConnectivityService updates the
   * network's capabilities and delivers onLost.
   */
  private fun regateAfterNetworkLoss(id: String, errorCode: Int): Boolean {
    // Only IO/exception-type errors (-1) qualify; positive codes are HTTP errors
    if (errorCode != -1) return false
    val state = resumableDownloader.getState(id) ?: return false
    if (state.isAllowedOverMetered) return false

    val boundNetwork = synchronized(gateLock) {
      gatedRunning[id]
        ?: // onLost already moved this download back to the waiting set between the
        // socket error and this classification - suppress the error, it's handled
        return waitingForUnmetered.contains(id)
    }

    var lostUnmetered = !isNetworkUnmetered(boundNetwork)
    if (!lostUnmetered) {
      // Capabilities still look fine - give the connectivity stack a moment to
      // catch up with reality, then trust both the capability query and the
      // callback-maintained set of unmetered networks. Runs unlocked on the
      // exiting download thread.
      try {
        Thread.sleep(DownloadConstants.UNMETERED_RECHECK_DELAY_MS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      lostUnmetered = !isNetworkUnmetered(boundNetwork) ||
        synchronized(gateLock) { !unmeteredNetworks.contains(boundNetwork) }
    }

    synchronized(gateLock) {
      // Re-validate under the lock: the grace period may have raced a cancel, a
      // user pause, or an onLost-driven re-gate that already restarted this
      // download on another network. Mutate only if we still own the binding.
      val currentState = resumableDownloader.getState(id)
        ?: return true // cancelled during the grace period - nothing to report
      if (currentState.isCancelled.get()) return true
      if (waitingForUnmetered.contains(id)) return true // already re-gated by onLost
      val currentNetwork = gatedRunning[id]
        ?: return true // released by a concurrent pause/cancel - error is stale
      if (currentNetwork != boundNetwork) {
        // Restarted on another network during the grace period - the error from
        // the old connection is stale; the new transfer reports for itself
        return true
      }
      if (!lostUnmetered) {
        // The network is genuinely fine - this is a real error (server, disk...)
        return false
      }

      gatedRunning.remove(id)
      currentState.network = null
      // Paused-like state so the gate can restart it via resume()
      currentState.isPaused.set(true)
      waitingForUnmetered.add(id)
      ensureUnmeteredCallbackLocked()
      // If another unmetered network is already up, restart right away
      unmeteredNetworks.firstOrNull()?.let { startWaitingDownloadsLocked(it) }
      return true
    }
  }

  private fun isNetworkUnmetered(network: Network): Boolean {
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
  }

  /**
   * Pause gated downloads bound to a network that is no longer unmetered/available
   * and put them back into the waiting set. If another unmetered network is still
   * available they restart on it immediately. Caller must hold gateLock; callers
   * update the notification afterwards.
   */
  private fun pauseGatedDownloadsOnLocked(network: Network) {
    var movedToWaiting = false
    for ((id, boundNetwork) in gatedRunning.entries.toList()) {
      if (boundNetwork != network) continue
      gatedRunning.remove(id)
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network lost, pausing gated download $id")
      resumableDownloader.pause(id)
      resumableDownloader.getState(id)?.network = null
      // Back to waiting: auto-restarts when an unmetered network is available again
      waitingForUnmetered.add(id)
      movedToWaiting = true
    }

    if (movedToWaiting) {
      // Another unmetered network may still be up (e.g. ethernet next to WiFi) -
      // its onAvailable already fired, so re-drain the waiting set explicitly
      unmeteredNetworks.firstOrNull()?.let { startWaitingDownloadsLocked(it) }
    }
  }

  fun isPaused(id: String): Boolean = resumableDownloader.isPaused(id)

  fun getState(id: String) = resumableDownloader.getState(id)

  @Volatile
  private var isForeground = false

  private fun startForegroundWithNotification() {
    if (isForeground) return
    val notification = createNotification()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(DownloadConstants.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      } else {
        startForeground(DownloadConstants.NOTIFICATION_ID, notification)
      }
      isForeground = true
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to start foreground service: ${e.message}")
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val name = "Background Downloads"
      val descriptionText = "Shows download progress for background downloads"
      val importance = NotificationManager.IMPORTANCE_LOW
      val channel = NotificationChannel(DownloadConstants.NOTIFICATION_CHANNEL_ID, name, importance).apply {
        description = descriptionText
        setShowBadge(false)
      }

      val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun createNotification(): Notification {
    val activeCount = activeDownloads.size
    // Downloads held by the unmetered-network gate are paused-like but shown separately
    val waitingIds = synchronized(gateLock) { waitingForUnmetered.toSet() }
    val waitingCount = activeDownloads.keys.count { waitingIds.contains(it) }
    val pausedCount = activeDownloads.keys.count { resumableDownloader.isPaused(it) && !waitingIds.contains(it) }
    val runningCount = activeCount - pausedCount - waitingCount

    val parts = mutableListOf<String>()
    if (runningCount > 0) parts.add("$runningCount downloading")
    if (waitingCount > 0) parts.add("$waitingCount waiting for unmetered network")
    if (pausedCount > 0) parts.add("$pausedCount paused")
    val contentText = if (parts.isEmpty()) "Download service running" else parts.joinToString(", ")

    // Use download icon when actively downloading, pause icon when all paused
    val icon = if (runningCount > 0) {
      android.R.drawable.stat_sys_download
    } else {
      android.R.drawable.stat_sys_download_done
    }

    return NotificationCompat.Builder(this, DownloadConstants.NOTIFICATION_CHANNEL_ID)
      .setContentTitle("Background Download")
      .setContentText(contentText)
      .setSmallIcon(icon)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setGroup(NOTIFICATION_GROUP_KEY)
      .setGroupSummary(true)
      .build()
  }

  private fun updateNotification() {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(DownloadConstants.NOTIFICATION_ID, createNotification())
  }

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        WAKELOCK_TAG
      ).apply {
        setReferenceCounted(false)
        acquire(DownloadConstants.WAKELOCK_TIMEOUT_MS)
      }
      RNBackgroundDownloaderModuleImpl.logD(TAG, "WakeLock acquired")
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
        RNBackgroundDownloaderModuleImpl.logD(TAG, "WakeLock released")
      }
    }
    wakeLock = null
  }

  private fun stopServiceIfIdle() {
    // Check if there are any active or paused downloads
    val hasActiveDownloads = activeDownloads.isNotEmpty()
    val hasPausedInResumable = activeDownloads.keys.any { resumableDownloader.getState(it) != null }

    if (!hasActiveDownloads && !hasPausedInResumable) {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "No active downloads, stopping service")
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_REMOVE)
      isForeground = false
      stopSelf()
    } else {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Service has active downloads, keeping alive")
      updateNotification()
    }
  }
}

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
  // Downloads waiting for an unmetered network before (re)starting.
  // Value = cleanupOnTerminal for the validating listener created when the download starts.
  private val waitingForUnmetered = ConcurrentHashMap<String, Boolean>()
  // Gated downloads currently transferring, keyed to the network they are bound to
  private val gatedRunning = ConcurrentHashMap<String, Network>()
  // Unmetered networks currently known to be available
  private val unmeteredNetworks = ConcurrentHashMap.newKeySet<Network>()
  @Volatile
  private var unmeteredCallback: ConnectivityManager.NetworkCallback? = null

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
   * @param id The download ID
   * @param sessionToken The session token from when the download was started
   * @param generation The generation counter from when the download was started
   * @param cleanupOnTerminal Whether to clean up state on complete/error (true for start, false for resume since job already exists)
   */
  private fun createValidatingListener(
    id: String,
    sessionToken: Long,
    generation: Long,
    cleanupOnTerminal: Boolean = true
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
          if (cleanupOnTerminal) {
            activeDownloads.remove(id)
            lastProgressLogTime.remove(id)
          }
          clearUnmeteredGate(id)
          stopServiceIfIdle()
        } else {
          logStale("onComplete")
        }
      }

      override fun onError(id: String, error: String, errorCode: Int) {
        if (isValid()) {
          // A gated download whose bound network died raises a socket error before
          // the ConnectivityManager onLost callback arrives - reclassify it as
          // "waiting for unmetered network" instead of failing the download
          if (regateAfterNetworkLoss(id, errorCode, cleanupOnTerminal)) {
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Gated download $id lost its unmetered network mid-transfer, re-waiting (suppressed error: $error)")
            updateNotification()
            return
          }
          listener?.onError(id, error, errorCode)
          if (cleanupOnTerminal) {
            activeDownloads.remove(id)
            lastProgressLogTime.remove(id)
          }
          clearUnmeteredGate(id)
          stopServiceIfIdle()
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
    }

    return START_STICKY
  }

  override fun onDestroy() {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Service destroyed")
    waitingForUnmetered.clear()
    gatedRunning.clear()
    maybeUnregisterUnmeteredCallback()
    releaseWakeLock()
    executorService.shutdownNow()
    super.onDestroy()
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
      // gate start it: the network callback replays onAvailable for networks that
      // already satisfy the request, so if an unmetered network is connected the
      // transfer starts right away; otherwise it waits like DownloadManager's
      // "queued for WiFi" state. No wake lock while waiting - one is acquired
      // when the transfer actually starts.
      resumableDownloader.prepareWaitingDownload(id, url, destination, headers, startByte, totalBytes, isAllowedOverMetered = false)
      waitingForUnmetered[id] = true // cleanupOnTerminal=true (fresh start)
      ensureUnmeteredCallback()
      updateNotification()
      return
    }

    acquireWakeLock()

    // Create a validating listener wrapper
    val serviceListener = createValidatingListener(id, job.sessionToken, generation, cleanupOnTerminal = true)

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
    // A user pause takes the download out of the unmetered gate so it won't
    // auto-restart when an unmetered network appears
    clearUnmeteredGate(id)
    val result = resumableDownloader.pause(id)
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
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Resume of $id waits for an unmetered network")
      waitingForUnmetered[id] = false // cleanupOnTerminal=false (existing job)
      ensureUnmeteredCallback()
      updateNotification()
      return true
    }

    acquireWakeLock()

    // Create a validating listener wrapper (don't cleanup since job already exists)
    val serviceListener = createValidatingListener(id, sessionToken, generation, cleanupOnTerminal = false)

    return resumableDownloader.resume(id, serviceListener)
  }

  fun cancelDownload(id: String): Boolean {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelling download: $id")
    activeDownloads.remove(id)
    clearUnmeteredGate(id)
    val result = resumableDownloader.cancel(id)
    stopServiceIfIdle()
    return result
  }

  // --- Unmetered-network gate ---

  /**
   * Register the shared callback for unmetered networks (idempotent).
   * NET_CAPABILITY_NOT_VPN is removed (Builder default) so unmetered VPN networks
   * are accepted, consistent with the UIDT job's NetworkRequest.
   * registerNetworkCallback immediately replays onAvailable for networks that
   * already satisfy the request, so no synchronous availability check is needed.
   */
  @Synchronized
  private fun ensureUnmeteredCallback() {
    if (unmeteredCallback != null) return

    val request = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
      .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
      .build()

    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network available: $network")
        unmeteredNetworks.add(network)
        startWaitingDownloads(network)
      }

      override fun onLost(network: Network) {
        // Also delivered when a network stops satisfying the request
        // (e.g. WiFi becomes metered), not only on disconnect
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network lost: $network")
        unmeteredNetworks.remove(network)
        pauseGatedDownloadsOn(network)
      }
    }

    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    try {
      connectivityManager.registerNetworkCallback(request, callback)
      unmeteredCallback = callback
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Registered unmetered network callback")
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to register network callback: ${e.message}")
    }
  }

  @Synchronized
  private fun maybeUnregisterUnmeteredCallback() {
    if (waitingForUnmetered.isNotEmpty() || gatedRunning.isNotEmpty()) return
    val callback = unmeteredCallback ?: return
    unmeteredCallback = null
    unmeteredNetworks.clear()
    try {
      val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      connectivityManager.unregisterNetworkCallback(callback)
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Unregistered unmetered network callback")
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to unregister network callback: ${e.message}")
    }
  }

  /**
   * Remove a download from the gate bookkeeping (terminal state, user pause or cancel).
   */
  private fun clearUnmeteredGate(id: String) {
    waitingForUnmetered.remove(id)
    gatedRunning.remove(id)
    maybeUnregisterUnmeteredCallback()
  }

  /**
   * Start every waiting unmetered-only download on the given network.
   * Runs on the ConnectivityManager callback thread.
   */
  private fun startWaitingDownloads(network: Network) {
    for (id in waitingForUnmetered.keys.toList()) {
      // Atomically claim the download so a concurrent user pause/cancel wins or loses cleanly
      val cleanupOnTerminal = waitingForUnmetered.remove(id) ?: continue
      val job = activeDownloads[id]
      val state = resumableDownloader.getState(id)
      if (job == null || state == null) {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Gated download $id has no job/state, skipping")
        continue
      }

      acquireWakeLock()
      state.network = network
      val generation = downloadGeneration[id] ?: 0
      val serviceListener = createValidatingListener(id, job.sessionToken, generation, cleanupOnTerminal)
      if (resumableDownloader.resume(id, serviceListener)) {
        gatedRunning[id] = network
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Started gated download $id on unmetered network $network")
      } else {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to start gated download $id")
      }
    }
    updateNotification()
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
  private fun regateAfterNetworkLoss(id: String, errorCode: Int, cleanupOnTerminal: Boolean): Boolean {
    // Only IO/exception-type errors (-1) qualify; positive codes are HTTP errors
    if (errorCode != -1) return false
    val state = resumableDownloader.getState(id) ?: return false
    if (state.isAllowedOverMetered) return false
    val boundNetwork = gatedRunning[id]
    if (boundNetwork == null) {
      // onLost already moved this download back to the waiting set between the
      // socket error and this classification - suppress the error, it's handled
      return waitingForUnmetered.containsKey(id)
    }

    var lostUnmetered = !isNetworkUnmetered(boundNetwork)
    if (!lostUnmetered) {
      // Capabilities still look fine - give the connectivity stack a moment to
      // catch up with reality, then trust both the capability query and the
      // callback-maintained set of unmetered networks
      try {
        Thread.sleep(DownloadConstants.UNMETERED_RECHECK_DELAY_MS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      lostUnmetered = !isNetworkUnmetered(boundNetwork) || !unmeteredNetworks.contains(boundNetwork)
    }
    if (!lostUnmetered) {
      // The network is genuinely fine - this is a real error (server, disk, etc.)
      return false
    }

    gatedRunning.remove(id)
    state.network = null
    // Paused-like state so the gate can restart it via resume()
    state.isPaused.set(true)
    waitingForUnmetered[id] = cleanupOnTerminal
    ensureUnmeteredCallback()
    // If another unmetered network is already up, restart right away
    unmeteredNetworks.firstOrNull()?.let { startWaitingDownloads(it) }
    return true
  }

  private fun isNetworkUnmetered(network: Network): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
  }

  /**
   * Pause gated downloads bound to a network that is no longer unmetered/available
   * and put them back into the waiting set. If another unmetered network is still
   * available they restart on it immediately.
   */
  private fun pauseGatedDownloadsOn(network: Network) {
    var movedToWaiting = false
    for ((id, boundNetwork) in gatedRunning.entries.toList()) {
      if (boundNetwork != network) continue
      if (gatedRunning.remove(id, boundNetwork)) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Unmetered network lost, pausing gated download $id")
        resumableDownloader.pause(id)
        resumableDownloader.getState(id)?.network = null
        // Back to waiting: auto-restarts when an unmetered network is available again.
        // cleanupOnTerminal=false - the service job entry already exists.
        waitingForUnmetered[id] = false
        movedToWaiting = true
      }
    }

    if (movedToWaiting) {
      // Another unmetered network may still be up (e.g. ethernet next to WiFi) -
      // onAvailable already fired for it, so re-drain the waiting set explicitly
      unmeteredNetworks.firstOrNull()?.let { startWaitingDownloads(it) }
      updateNotification()
    }
  }

  fun isPaused(id: String): Boolean = resumableDownloader.isPaused(id)

  fun getState(id: String) = resumableDownloader.getState(id)

  private fun startForegroundWithNotification() {
    val notification = createNotification()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(DownloadConstants.NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      } else {
        startForeground(DownloadConstants.NOTIFICATION_ID, notification)
      }
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
    val waitingCount = activeDownloads.keys.count { waitingForUnmetered.containsKey(it) }
    val pausedCount = activeDownloads.keys.count { resumableDownloader.isPaused(it) && !waitingForUnmetered.containsKey(it) }
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
      stopSelf()
    } else {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Service has active downloads, keeping alive")
      updateNotification()
    }
  }
}

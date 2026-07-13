package com.eko

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

  // Throttle progress logging to reduce log noise
  private val lastProgressLogTime = ConcurrentHashMap<String, Long>()

  // Shared ResumableDownloader instance
  val resumableDownloader = ResumableDownloader()

  // Enforces isAllowedOverMetered=false on this path (no scheduler to hold the
  // transfer): parks downloads until an unmetered network is available, binds
  // the transfer to it, auto-pauses on loss and auto-resumes
  private val unmeteredGate by lazy {
    UnmeteredNetworkGate(this, resumableDownloader, object : UnmeteredNetworkGate.Host {
      override fun createGateListener(id: String): ResumableDownloader.DownloadListener? {
        val job = activeDownloads[id] ?: return null
        return createValidatingListener(id, job.sessionToken, downloadGeneration[id] ?: 0)
      }

      override fun onGatedTransferStarting() {
        acquireWakeLock()
      }

      override fun onGateStateChanged() {
        updateNotification()
      }
    })
  }

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
          if (unmeteredGate.regateAfterNetworkLoss(id, errorCode)) {
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
    unmeteredGate.shutdown()
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
    unmeteredGate.clear(id)
    stopServiceIfIdle()
  }

  fun setDownloadListener(listener: ResumableDownloader.DownloadListener?) {
    this.listener = listener
  }

  // isAllowedOverMetered is deliberately not defaulted: a call site that forgets
  // it must not compile, or the metered restriction silently reverts to allowed
  fun startDownload(
    id: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    startByte: Long = 0,
    totalBytes: Long = -1,
    isAllowedOverMetered: Boolean
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
    isAllowedOverMetered: Boolean
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
      // Park the download and let the unmetered-network gate start it: it starts
      // immediately when an unmetered network is already connected, otherwise it
      // waits like DownloadManager's "queued for WiFi" state. No wake lock while
      // waiting - one is acquired when the transfer actually starts.
      val gated = unmeteredGate.parkNewDownload(id) {
        resumableDownloader.prepareWaitingDownload(id, url, destination, headers, startByte, totalBytes)
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
    unmeteredGate.clear(id)

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
      totalBytes = totalBytes,
      isAllowedOverMetered = isAllowedOverMetered
    )
  }

  fun pauseDownload(id: String): Boolean {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Pausing download: $id")
    // Atomic with the gate: a user pause takes the download out of the unmetered
    // gate under the gate lock, so a concurrent onAvailable drain can't claim it
    // and restart the transfer the user just paused
    val result = unmeteredGate.withGateCleared(id) {
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
      val gated = unmeteredGate.parkForResume(id)
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
    // Atomic with the gate so an in-flight onAvailable drain can't restart the
    // download between the bookkeeping removal and the downloader cancel
    val result = unmeteredGate.withGateCleared(id) {
      activeDownloads.remove(id)
      resumableDownloader.cancel(id)
    }
    stopServiceIfIdle()
    return result
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
    val waitingIds = unmeteredGate.waitingIds()
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

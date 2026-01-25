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
          stopServiceIfIdle()
        } else {
          logStale("onComplete")
        }
      }

      override fun onError(id: String, error: String, errorCode: Int) {
        if (isValid()) {
          listener?.onError(id, error, errorCode)
          if (cleanupOnTerminal) {
            activeDownloads.remove(id)
            lastProgressLogTime.remove(id)
          }
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

        if (id != null && url != null && destination != null) {
          startDownloadInternal(id, url, destination, headers, startByte, totalBytes)
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
    totalBytes: Long = -1
  ) {
    startDownloadInternal(id, url, destination, headers, startByte, totalBytes)
  }

  private fun startDownloadInternal(
    id: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    startByte: Long,
    totalBytes: Long
  ) {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Starting download: $id from byte $startByte")

    // Start foreground service if not already
    startForegroundWithNotification()
    acquireWakeLock()

    // Increment generation counter for this download ID
    // This ensures we can detect stale events even if job was removed and re-added
    val generation = (downloadGeneration[id] ?: 0) + 1
    downloadGeneration[id] = generation
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Download $id starting with generation $generation")

    val job = DownloadJob(id, url, destination, headers, startByte, totalBytes)
    activeDownloads[id] = job

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

    // Create a validating listener wrapper (don't cleanup since job already exists)
    val serviceListener = createValidatingListener(id, sessionToken, generation, cleanupOnTerminal = false)

    // Make sure service is in foreground
    startForegroundWithNotification()
    acquireWakeLock()

    return resumableDownloader.resume(id, serviceListener)
  }

  fun cancelDownload(id: String): Boolean {
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelling download: $id")
    activeDownloads.remove(id)
    val result = resumableDownloader.cancel(id)
    stopServiceIfIdle()
    return result
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
    val pausedCount = activeDownloads.keys.count { resumableDownloader.isPaused(it) }
    val runningCount = activeCount - pausedCount

    val contentText = when {
      runningCount > 0 && pausedCount > 0 -> "$runningCount downloading, $pausedCount paused"
      runningCount > 0 -> "$runningCount download${if (runningCount > 1) "s" else ""} in progress"
      pausedCount > 0 -> "$pausedCount download${if (pausedCount > 1) "s" else ""} paused"
      else -> "Download service running"
    }

    return NotificationCompat.Builder(this, DownloadConstants.NOTIFICATION_CHANNEL_ID)
      .setContentTitle("Background Download")
      .setContentText(contentText)
      .setSmallIcon(android.R.drawable.stat_sys_download)
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

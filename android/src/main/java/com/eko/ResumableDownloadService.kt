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
import android.util.Log
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
    private const val CHANNEL_ID = "resumable_download_channel"
    private const val NOTIFICATION_ID = 9999
    private const val WAKELOCK_TAG = "ResumableDownloadService::WakeLock"

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
  private val executorService: ExecutorService = Executors.newFixedThreadPool(3)
  private val activeDownloads = ConcurrentHashMap<String, DownloadJob>()
  private var wakeLock: PowerManager.WakeLock? = null
  private var listener: ResumableDownloader.DownloadListener? = null

  // Generation counter per download ID - incremented each time a new download starts
  // This is separate from sessionToken to ensure we can detect stale events even
  // across cancel/restart cycles where the job is removed and re-added
  private val downloadGeneration = ConcurrentHashMap<String, Long>()

  // Throttle progress logging to reduce log noise (log every 500ms per download)
  private val lastProgressLogTime = ConcurrentHashMap<String, Long>()
  private val PROGRESS_LOG_INTERVAL_MS = 500L

  // Shared ResumableDownloader instance
  val resumableDownloader = ResumableDownloader()

  inner class LocalBinder : Binder() {
    fun getService(): ResumableDownloadService = this@ResumableDownloadService
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
    Log.d(TAG, "Service created")
    createNotificationChannel()
  }

  override fun onBind(intent: Intent?): IBinder {
    return binder
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(TAG, "onStartCommand: action=${intent?.action}")

    when (intent?.action) {
      ACTION_START_DOWNLOAD -> {
        val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
        val url = intent.getStringExtra(EXTRA_URL)
        val destination = intent.getStringExtra(EXTRA_DESTINATION)
        @Suppress("UNCHECKED_CAST")
        val headers = intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String> ?: HashMap()
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
    Log.d(TAG, "Service destroyed")
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
    Log.d(TAG, "Starting download: $id from byte $startByte")

    // Start foreground service if not already
    startForegroundWithNotification()
    acquireWakeLock()

    // Increment generation counter for this download ID
    // This ensures we can detect stale events even if job was removed and re-added
    val generation = (downloadGeneration[id] ?: 0) + 1
    downloadGeneration[id] = generation
    Log.d(TAG, "Download $id starting with generation $generation")

    val job = DownloadJob(id, url, destination, headers, startByte, totalBytes)
    val sessionToken = job.sessionToken
    activeDownloads[id] = job

    // Create a wrapper listener that handles service lifecycle
    // Capture both session token and generation to verify events
    val serviceListener = object : ResumableDownloader.DownloadListener {
      override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) {
        // Check both job session token AND generation counter
        val currentJob = activeDownloads[id]
        val currentGeneration = downloadGeneration[id] ?: 0
        if (currentJob != null && currentJob.sessionToken == sessionToken && currentGeneration == generation) {
          listener?.onBegin(id, expectedBytes, headers)
          updateNotification()
        } else {
          Log.d(TAG, "Ignoring stale onBegin for $id (session: my=$sessionToken vs job=${currentJob?.sessionToken}, gen: my=$generation vs current=$currentGeneration)")
        }
      }

      override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) {
        // Check both job session token AND generation counter
        val currentJob = activeDownloads[id]
        val currentGeneration = downloadGeneration[id] ?: 0

        // Throttle progress logging to reduce noise
        val now = System.currentTimeMillis()
        val lastLogTime = lastProgressLogTime[id] ?: 0L
        if (now - lastLogTime >= PROGRESS_LOG_INTERVAL_MS) {
          Log.d(TAG, "onProgress wrapper: id=$id, bytes=$bytesDownloaded, myToken=$sessionToken, jobToken=${currentJob?.sessionToken}, myGen=$generation, currentGen=$currentGeneration")
          lastProgressLogTime[id] = now
        }

        if (currentJob != null && currentJob.sessionToken == sessionToken && currentGeneration == generation) {
          listener?.onProgress(id, bytesDownloaded, bytesTotal)
        } else {
          Log.d(TAG, "Ignoring stale onProgress for $id (session: my=$sessionToken vs job=${currentJob?.sessionToken}, gen: my=$generation vs current=$currentGeneration)")
        }
      }

      override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
        // Check both job session token AND generation counter
        val currentJob = activeDownloads[id]
        val currentGeneration = downloadGeneration[id] ?: 0
        if (currentJob != null && currentJob.sessionToken == sessionToken && currentGeneration == generation) {
          listener?.onComplete(id, location, bytesDownloaded, bytesTotal)
          activeDownloads.remove(id)
          lastProgressLogTime.remove(id)
          stopServiceIfIdle()
        } else {
          Log.d(TAG, "Ignoring stale onComplete for $id (session: my=$sessionToken vs job=${currentJob?.sessionToken}, gen: my=$generation vs current=$currentGeneration)")
        }
      }

      override fun onError(id: String, error: String, errorCode: Int) {
        // Check both job session token AND generation counter
        val currentJob = activeDownloads[id]
        val currentGeneration = downloadGeneration[id] ?: 0
        if (currentJob != null && currentJob.sessionToken == sessionToken && currentGeneration == generation) {
          listener?.onError(id, error, errorCode)
          activeDownloads.remove(id)
          lastProgressLogTime.remove(id)
          stopServiceIfIdle()
        } else {
          Log.d(TAG, "Ignoring stale onError for $id (session: my=$sessionToken vs job=${currentJob?.sessionToken}, gen: my=$generation vs current=$currentGeneration)")
        }
      }
    }

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
    Log.d(TAG, "Pausing download: $id")
    val result = resumableDownloader.pause(id)
    if (result) {
      updateNotification()
      // Don't stop service - keep it alive for potential resume
    }
    return result
  }

  fun resumeDownload(id: String): Boolean {
    Log.d(TAG, "Resuming download: $id")

    val listener = this.listener ?: return false
    val currentJob = activeDownloads[id] ?: return false
    val sessionToken = currentJob.sessionToken
    val generation = downloadGeneration[id] ?: 0
    Log.d(TAG, "Resuming $id with session=$sessionToken, generation=$generation")

    // Create wrapper listener with session token and generation check
    val serviceListener = object : ResumableDownloader.DownloadListener {
      override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) {
        val job = activeDownloads[id]
        val currentGen = downloadGeneration[id] ?: 0
        if (job != null && job.sessionToken == sessionToken && currentGen == generation) {
          listener.onBegin(id, expectedBytes, headers)
          updateNotification()
        } else {
          Log.d(TAG, "Ignoring stale onBegin in resume for $id")
        }
      }

      override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) {
        val job = activeDownloads[id]
        val currentGen = downloadGeneration[id] ?: 0
        if (job != null && job.sessionToken == sessionToken && currentGen == generation) {
          listener.onProgress(id, bytesDownloaded, bytesTotal)
        } else {
          Log.d(TAG, "Ignoring stale onProgress in resume for $id (my gen=$generation, current=$currentGen)")
        }
      }

      override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
        val job = activeDownloads[id]
        val currentGen = downloadGeneration[id] ?: 0
        if (job != null && job.sessionToken == sessionToken && currentGen == generation) {
          listener.onComplete(id, location, bytesDownloaded, bytesTotal)
          activeDownloads.remove(id)
          stopServiceIfIdle()
        } else {
          Log.d(TAG, "Ignoring stale onComplete in resume for $id")
        }
      }

      override fun onError(id: String, error: String, errorCode: Int) {
        val job = activeDownloads[id]
        val currentGen = downloadGeneration[id] ?: 0
        if (job != null && job.sessionToken == sessionToken && currentGen == generation) {
          listener.onError(id, error, errorCode)
          activeDownloads.remove(id)
          stopServiceIfIdle()
        } else {
          Log.d(TAG, "Ignoring stale onError in resume for $id")
        }
      }
    }

    // Make sure service is in foreground
    startForegroundWithNotification()
    acquireWakeLock()

    return resumableDownloader.resume(id, serviceListener)
  }

  fun cancelDownload(id: String): Boolean {
    Log.d(TAG, "Cancelling download: $id")
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
        startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start foreground service: ${e.message}")
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val name = "Background Downloads"
      val descriptionText = "Shows download progress for background downloads"
      val importance = NotificationManager.IMPORTANCE_LOW
      val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
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

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("Background Download")
      .setContentText(contentText)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .build()
  }

  private fun updateNotification() {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.notify(NOTIFICATION_ID, createNotification())
  }

  private fun acquireWakeLock() {
    if (wakeLock == null) {
      val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
      wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        WAKELOCK_TAG
      ).apply {
        setReferenceCounted(false)
        acquire(60 * 60 * 1000L) // 1 hour max
      }
      Log.d(TAG, "WakeLock acquired")
    }
  }

  private fun releaseWakeLock() {
    wakeLock?.let {
      if (it.isHeld) {
        it.release()
        Log.d(TAG, "WakeLock released")
      }
    }
    wakeLock = null
  }

  private fun stopServiceIfIdle() {
    // Check if there are any active or paused downloads
    val hasActiveDownloads = activeDownloads.isNotEmpty()
    val hasPausedInResumable = activeDownloads.keys.any { resumableDownloader.getState(it) != null }

    if (!hasActiveDownloads && !hasPausedInResumable) {
      Log.d(TAG, "No active downloads, stopping service")
      releaseWakeLock()
      stopForeground(STOP_FOREGROUND_REMOVE)
      stopSelf()
    } else {
      Log.d(TAG, "Service has active downloads, keeping alive")
      updateNotification()
    }
  }
}

package com.eko

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Wrapper around Android's DownloadManager for managing file downloads.
 * Provides methods for downloading, canceling, and querying download status.
 *
 * For pause/resume functionality, this class integrates with:
 * - UIDTDownloadJobService (Android 14+): Uses User-Initiated Data Transfer jobs
 *   which are not affected by App Standby Buckets and work properly on Android 16+
 * - ResumableDownloadService (Android < 14): Uses foreground service with dataSync type
 *
 * Both use HTTP Range headers to support true pause/resume, even in the background.
 */
class Downloader(private val context: Context) {

  companion object {
    private const val TAG = "RNBackgroundDownloader"
  }

  val downloadManager: DownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

  // Service for background resumable downloads
  private var downloadService: ResumableDownloadService? = null
  private var serviceBound = false
  private val pendingServiceOperations = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()

  // Track which config IDs are using resumable downloads (paused state)
  private val pausedDownloads = ConcurrentHashMap<String, PausedDownloadInfo>()

  // Track download IDs that are being intentionally cancelled (to ignore broadcast events)
  // The value indicates the cancellation intent: PAUSING or STOPPING
  enum class CancelIntent { PAUSING, STOPPING }
  private val cancellingDownloads = ConcurrentHashMap<Long, CancelIntent>()

  // Service connection
  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val binder = service as ResumableDownloadService.LocalBinder
      downloadService = binder.getService()
      serviceBound = true
      RNBackgroundDownloaderModuleImpl.logD(TAG, "ResumableDownloadService connected")

      // Execute any pending operations
      var operation = pendingServiceOperations.poll()
      while (operation != null) {
        operation.invoke()
        operation = pendingServiceOperations.poll()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      downloadService = null
      serviceBound = false
      RNBackgroundDownloaderModuleImpl.logD(TAG, "ResumableDownloadService disconnected")
    }
  }

  // Expose resumableDownloader for compatibility (delegates to service)
  // Cache a fallback instance to avoid creating new instances on every access when service is null
  private var fallbackResumableDownloader: ResumableDownloader? = null
  val resumableDownloader: ResumableDownloader
    get() = downloadService?.resumableDownloader ?: run {
      if (fallbackResumableDownloader == null) {
        fallbackResumableDownloader = ResumableDownloader()
      }
      fallbackResumableDownloader!!
    }

  data class PausedDownloadInfo(
    val configId: String,
    val url: String,
    val destination: String,
    val headers: Map<String, String>,
    val bytesDownloaded: Long,
    val bytesTotal: Long
  )

  init {
    bindToService()
  }

  private fun bindToService() {
    val intent = Intent(context, ResumableDownloadService::class.java)
    context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
  }

  fun unbindService() {
    if (serviceBound) {
      context.unbindService(serviceConnection)
      serviceBound = false
    }
  }

  /**
   * Set the download listener for resumable downloads.
   */
  fun setResumableDownloadListener(listener: ResumableDownloader.DownloadListener) {
    executeWhenServiceReady {
      downloadService?.setDownloadListener(listener)
    }
  }

  private fun executeWhenServiceReady(operation: () -> Unit) {
    if (serviceBound && downloadService != null) {
      operation()
    } else {
      pendingServiceOperations.offer(operation)
      // Ensure service is started
      bindToService()
    }
  }

  fun download(request: DownloadManager.Request): Long {
    return downloadManager.enqueue(request)
  }

  /**
   * Clean up any stale state for a download ID before starting.
   * This handles cases where a previous download was interrupted without proper cleanup.
   */
  fun cleanupStaleState(configId: String) {
    // Remove any stale paused state from a previous download with the same ID
    val pausedInfo = pausedDownloads.remove(configId)
    if (pausedInfo != null) {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Cleaned up stale paused state for: $configId")
      // Clean up partially downloaded destination file if it exists
      val destFile = File(pausedInfo.destination)
      if (destFile.exists()) {
        if (!destFile.delete()) {
          RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to delete partially downloaded file: ${pausedInfo.destination}")
        } else {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Deleted partially downloaded file: ${pausedInfo.destination}")
        }
      }
    }
  }

  /**
   * Cancel a download and mark it as being stopped to ignore broadcast events.
   */
  fun cancel(downloadId: Long): Int {
    cancellingDownloads[downloadId] = CancelIntent.STOPPING
    return downloadManager.remove(downloadId)
  }

  /**
   * Get the cancellation intent for a download ID, if any.
   * Returns null if the download is not being intentionally cancelled.
   */
  fun getCancelIntent(downloadId: Long): CancelIntent? {
    return cancellingDownloads[downloadId]
  }

  /**
   * Atomically get and clear the cancellation tracking for a download ID.
   * This is thread-safe and avoids race conditions between checking and clearing.
   * Returns the cancel intent if it was set, or null if not.
   */
  fun getAndClearCancelIntent(downloadId: Long): CancelIntent? {
    return cancellingDownloads.remove(downloadId)
  }

  /**
   * Clear the cancellation tracking for a download ID.
   */
  fun clearCancelIntent(downloadId: Long) {
    cancellingDownloads.remove(downloadId)
  }

  /**
   * Pause a download. This cancels the DownloadManager download and saves state
   * so it can be resumed later using HTTP Range headers.
   */
  fun pause(downloadId: Long, configId: String, url: String, destination: String, headers: Map<String, String>): Boolean {
    // Mark this download as being paused to ignore broadcast events
    cancellingDownloads[downloadId] = CancelIntent.PAUSING

    // Query current progress before cancelling
    val status = checkDownloadStatus(downloadId)
    val bytesDownloaded = status.getDouble("bytesDownloaded").toLong()
    val bytesTotal = status.getDouble("bytesTotal").toLong()

    // Cancel the DownloadManager download
    downloadManager.remove(downloadId)

    // Save paused state for later resume
    pausedDownloads[configId] = PausedDownloadInfo(
      configId = configId,
      url = url,
      destination = destination,
      headers = headers,
      bytesDownloaded = bytesDownloaded,
      bytesTotal = bytesTotal
    )

    RNBackgroundDownloaderModuleImpl.logD(TAG, "Paused download $configId at $bytesDownloaded/$bytesTotal bytes")
    return true
  }

  /**
   * Resume a paused download using the background service with HTTP Range headers.
   */
  fun resume(configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
    val pausedInfo = pausedDownloads[configId]
    if (pausedInfo == null) {
      RNBackgroundDownloaderModuleImpl.logW(TAG, "No paused download found for configId: $configId")
      return false
    }

    // Start the foreground service for background download
    startDownloadService(
      configId,
      pausedInfo.url,
      pausedInfo.destination,
      pausedInfo.headers,
      pausedInfo.bytesDownloaded,
      pausedInfo.bytesTotal,
      listener
    )

    RNBackgroundDownloaderModuleImpl.logD(TAG, "Resuming download $configId from ${pausedInfo.bytesDownloaded} bytes via service")
    return true
  }

  /**
   * Start a download via the appropriate background mechanism.
   *
   * On Android 14+ (API 34+), uses User-Initiated Data Transfer (UIDT) jobs which:
   * - Are not affected by App Standby Buckets quotas
   * - Can run for extended periods as system conditions allow
   * - Work properly on Android 16+ where foreground services are restricted
   *
   * On Android < 14, uses the foreground service with dataSync type.
   */
  private fun startDownloadService(
    configId: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    startByte: Long,
    totalBytes: Long,
    listener: ResumableDownloader.DownloadListener
  ) {
    // On Android 14+, use UIDT jobs for better background execution
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      // Set the listener for UIDT job callbacks
      UIDTDownloadJobService.downloadListener = listener

      // Schedule UIDT job
      val scheduled = UIDTDownloadJobService.scheduleDownload(
        context = context,
        configId = configId,
        url = url,
        destination = destination,
        headers = headers,
        startByte = startByte,
        totalBytes = totalBytes
      )

      if (scheduled) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Using UIDT job for download: $configId")
        return
      }

      // Fall through to foreground service if UIDT scheduling fails
      RNBackgroundDownloaderModuleImpl.logW(TAG, "UIDT scheduling failed, falling back to foreground service")
    }

    // On Android < 14 or if UIDT fails, use foreground service
    // First, ensure the service is started as a foreground service
    // Use a no-op action to just wake up the service
    val startIntent = Intent(context, ResumableDownloadService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        context.startForegroundService(startIntent)
      } catch (e: Exception) {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Could not start foreground service: ${e.message}")
      }
    } else {
      context.startService(startIntent)
    }

    // Now use the direct service call path
    executeWhenServiceReady {
      downloadService?.setDownloadListener(listener)
      downloadService?.startDownload(configId, url, destination, headers, startByte, totalBytes)
    }
  }

  /**
   * Start a new download using ResumableDownloader (HTTP-based download).
   * This is used as a fallback when DownloadManager can't handle the external storage path
   * on devices like OnePlus that return invalid paths from getExternalFilesDir().
   */
  fun startResumableDownload(
    configId: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    listener: ResumableDownloader.DownloadListener
  ) {
    startDownloadService(
      configId,
      url,
      destination,
      headers,
      0L,  // Start from beginning
      -1L, // Total bytes unknown
      listener
    )
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Started ResumableDownloader for $configId (DownloadManager fallback)")
  }

  /**
   * Pause a resumable download that's currently in progress.
   */
  fun pauseResumable(configId: String): Boolean {
    // Check UIDT jobs on Android 14+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      if (UIDTDownloadJobService.isActiveJob(configId)) {
        return UIDTDownloadJobService.pauseJob(configId)
      }
    }
    return downloadService?.pauseDownload(configId) ?: false
  }

  /**
   * Resume a paused resumable download.
   */
  fun resumeResumable(configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
    // Check UIDT jobs on Android 14+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      if (UIDTDownloadJobService.isActiveJob(configId)) {
        return UIDTDownloadJobService.resumeJob(configId, listener)
      }
    }
    executeWhenServiceReady {
      downloadService?.setDownloadListener(listener)
    }
    return downloadService?.resumeDownload(configId) ?: false
  }

  /**
   * Cancel a resumable download and clean up partially downloaded files.
   */
  fun cancelResumable(configId: String): Boolean {
    // Cancel UIDT job if on Android 14+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      UIDTDownloadJobService.cancelJob(context, configId)
    }

    // Clean up paused download state and partially downloaded file
    val pausedInfo = pausedDownloads.remove(configId)
    if (pausedInfo != null) {
      val destFile = File(pausedInfo.destination)
      if (destFile.exists()) {
        if (!destFile.delete()) {
          RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to delete partially downloaded file: ${pausedInfo.destination}")
        } else {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Deleted partially downloaded file: ${pausedInfo.destination}")
        }
      }
    }

    return downloadService?.cancelDownload(configId) ?: false
  }

  /**
   * Check if a download is paused.
   */
  fun isPaused(configId: String): Boolean {
    // Check UIDT jobs on Android 14+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      if (UIDTDownloadJobService.isPausedJob(configId)) {
        return true
      }
    }
    return pausedDownloads.containsKey(configId) || (downloadService?.isPaused(configId) ?: false)
  }

  /**
   * Get paused download info if available.
   */
  fun getPausedInfo(configId: String): PausedDownloadInfo? = pausedDownloads[configId]

  /**
   * Check if download is using resumable downloader (either UIDT job or foreground service).
   */
  fun isResumableDownload(configId: String): Boolean {
    // Check UIDT jobs on Android 14+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      if (UIDTDownloadJobService.isActiveJob(configId)) {
        return true
      }
    }
    return downloadService?.getState(configId) != null
  }

  /**
   * Remove paused state for a config ID and clean up partially downloaded files.
   */
  fun removePausedState(configId: String) {
    val pausedInfo = pausedDownloads.remove(configId)
    if (pausedInfo != null) {
      val destFile = File(pausedInfo.destination)
      if (destFile.exists()) {
        if (!destFile.delete()) {
          RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to delete partially downloaded file: ${pausedInfo.destination}")
        } else {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Deleted partially downloaded file: ${pausedInfo.destination}")
        }
      }
    }
  }

  // Manually trigger the receiver from anywhere.
  fun broadcast(downloadId: Long) {
    val intent = Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
    intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId)
    context.sendBroadcast(intent)
  }

  fun checkDownloadStatus(downloadId: Long): WritableMap {
    val query = DownloadManager.Query()
    query.setFilterById(downloadId)

    var result = Arguments.createMap()

    try {
      downloadManager.query(query)?.use { cursor ->
        if (cursor.moveToFirst()) {
          result = getDownloadStatus(cursor)
        } else {
          result.putString("downloadId", downloadId.toString())
          result.putInt("status", DownloadManager.STATUS_FAILED)
          result.putInt("reason", -1)
          result.putString("reasonText", "COULD_NOT_FIND")
        }
      }
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logE(TAG, "Downloader: ${Log.getStackTraceString(e)}")
    }

    return result
  }

  fun getDownloadStatus(cursor: Cursor): WritableMap {
    val downloadId = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
    var localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
    val bytesDownloadedSoFar = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
    val totalSizeBytes = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))

    localUri = localUri?.replace("file://", "")

    val reasonText = if (status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_FAILED) {
      getReasonText(status, reason)
    } else {
      ""
    }

    val result = Arguments.createMap()
    result.putString("downloadId", downloadId)
    result.putInt("status", status)
    result.putInt("reason", reason)
    result.putString("reasonText", reasonText)
    result.putDouble("bytesDownloaded", bytesDownloadedSoFar.toLong().toDouble())
    result.putDouble("bytesTotal", totalSizeBytes.toLong().toDouble())
    result.putString("localUri", localUri)

    return result
  }

  fun getReasonText(status: Int, reason: Int): String {
    return when (status) {
      DownloadManager.STATUS_FAILED -> when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> "ERROR_CANNOT_RESUME"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "ERROR_DEVICE_NOT_FOUND"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "ERROR_FILE_ALREADY_EXISTS"
        DownloadManager.ERROR_FILE_ERROR -> "ERROR_FILE_ERROR"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "ERROR_HTTP_DATA_ERROR"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "ERROR_INSUFFICIENT_SPACE"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "ERROR_TOO_MANY_REDIRECTS"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "ERROR_UNHANDLED_HTTP_CODE"
        else -> "ERROR_UNKNOWN"
      }
      DownloadManager.STATUS_PAUSED -> when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "PAUSED_QUEUED_FOR_WIFI"
        DownloadManager.PAUSED_UNKNOWN -> "PAUSED_UNKNOWN"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "PAUSED_WAITING_FOR_NETWORK"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "PAUSED_WAITING_TO_RETRY"
        else -> "UNKNOWN"
      }
      else -> "UNKNOWN"
    }
  }
}

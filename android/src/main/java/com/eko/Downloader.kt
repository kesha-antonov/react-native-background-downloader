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
 * For pause/resume functionality, this class integrates with ResumableDownloadService
 * which uses HTTP Range headers to support true pause/resume, even in the background.
 */
class Downloader(private val context: Context) {

  companion object {
    private const val TAG = "RNBackgroundDownloader"
  }

  val downloadManager: DownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

  // Service for background resumable downloads
  private var downloadService: ResumableDownloadService? = null
  private var serviceBound = false
  private var pendingServiceOperations = mutableListOf<() -> Unit>()

  // Track which config IDs are using resumable downloads (paused state)
  private val pausedDownloads = ConcurrentHashMap<String, PausedDownloadInfo>()

  // Track download IDs that are being intentionally paused (to ignore broadcast events)
  private val pausingDownloadIds = ConcurrentHashMap.newKeySet<Long>()

  // Service connection
  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val binder = service as ResumableDownloadService.LocalBinder
      downloadService = binder.getService()
      serviceBound = true
      Log.d(TAG, "ResumableDownloadService connected")

      // Execute any pending operations
      synchronized(pendingServiceOperations) {
        pendingServiceOperations.forEach { it.invoke() }
        pendingServiceOperations.clear()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      downloadService = null
      serviceBound = false
      Log.d(TAG, "ResumableDownloadService disconnected")
    }
  }

  // Expose resumableDownloader for compatibility (delegates to service)
  val resumableDownloader: ResumableDownloader
    get() = downloadService?.resumableDownloader ?: ResumableDownloader()

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
      synchronized(pendingServiceOperations) {
        pendingServiceOperations.add(operation)
      }
      // Ensure service is started
      bindToService()
    }
  }

  fun download(request: DownloadManager.Request): Long {
    return downloadManager.enqueue(request)
  }

  fun cancel(downloadId: Long): Int {
    return downloadManager.remove(downloadId)
  }

  /**
   * Pause a download. This cancels the DownloadManager download and saves state
   * so it can be resumed later using HTTP Range headers.
   */
  fun pause(downloadId: Long, configId: String, url: String, destination: String, headers: Map<String, String>): Boolean {
    // Mark this download as being paused to ignore broadcast events
    pausingDownloadIds.add(downloadId)

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

    Log.d(TAG, "Paused download $configId at $bytesDownloaded/$bytesTotal bytes")
    return true
  }

  /**
   * Check if a download ID is being intentionally paused (to ignore broadcast events).
   */
  fun isBeingPaused(downloadId: Long): Boolean {
    return pausingDownloadIds.contains(downloadId)
  }

  /**
   * Clear the pausing state for a download ID after pause is complete.
   */
  fun clearPausingState(downloadId: Long) {
    pausingDownloadIds.remove(downloadId)
  }

  /**
   * Resume a paused download using the background service with HTTP Range headers.
   */
  fun resume(configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
    val pausedInfo = pausedDownloads[configId]
    if (pausedInfo == null) {
      Log.w(TAG, "No paused download found for configId: $configId")
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

    Log.d(TAG, "Resuming download $configId from ${pausedInfo.bytesDownloaded} bytes via service")
    return true
  }

  /**
   * Start a download via the foreground service for background support.
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
    executeWhenServiceReady {
      downloadService?.setDownloadListener(listener)
      downloadService?.startDownload(configId, url, destination, headers, startByte, totalBytes)
    }

    // Also start the service explicitly to ensure it runs in foreground
    val intent = Intent(context, ResumableDownloadService::class.java).apply {
      action = ResumableDownloadService.ACTION_START_DOWNLOAD
      putExtra(ResumableDownloadService.EXTRA_DOWNLOAD_ID, configId)
      putExtra(ResumableDownloadService.EXTRA_URL, url)
      putExtra(ResumableDownloadService.EXTRA_DESTINATION, destination)
      putExtra(ResumableDownloadService.EXTRA_HEADERS, HashMap(headers))
      putExtra(ResumableDownloadService.EXTRA_START_BYTE, startByte)
      putExtra(ResumableDownloadService.EXTRA_TOTAL_BYTES, totalBytes)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.startForegroundService(intent)
    } else {
      context.startService(intent)
    }
  }

  /**
   * Pause a resumable download that's currently in progress.
   */
  fun pauseResumable(configId: String): Boolean {
    return downloadService?.pauseDownload(configId) ?: false
  }

  /**
   * Resume a paused resumable download.
   */
  fun resumeResumable(configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
    executeWhenServiceReady {
      downloadService?.setDownloadListener(listener)
    }
    return downloadService?.resumeDownload(configId) ?: false
  }

  /**
   * Cancel a resumable download and clean up all temp files.
   */
  fun cancelResumable(configId: String): Boolean {
    // Clean up paused download state and temp file
    val pausedInfo = pausedDownloads.remove(configId)
    if (pausedInfo != null) {
      // Delete the temp file for resumable downloads
      try {
        val tempFile = File(pausedInfo.destination + ".tmp")
        if (tempFile.exists()) {
          tempFile.delete()
          Log.d(TAG, "Deleted temp file for paused download: ${tempFile.absolutePath}")
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to delete temp file for paused download: ${e.message}")
      }
    }

    return downloadService?.cancelDownload(configId) ?: false
  }

  /**
   * Check if a download is paused.
   */
  fun isPaused(configId: String): Boolean {
    return pausedDownloads.containsKey(configId) || (downloadService?.isPaused(configId) ?: false)
  }

  /**
   * Get paused download info if available.
   */
  fun getPausedInfo(configId: String): PausedDownloadInfo? = pausedDownloads[configId]

  /**
   * Check if download is using resumable downloader.
   */
  fun isResumableDownload(configId: String): Boolean {
    return downloadService?.getState(configId) != null
  }

  /**
   * Remove paused state for a config ID and clean up temp files.
   */
  fun removePausedState(configId: String) {
    val pausedInfo = pausedDownloads.remove(configId)
    if (pausedInfo != null) {
      // Delete the temp file for resumable downloads
      try {
        val tempFile = File(pausedInfo.destination + ".tmp")
        if (tempFile.exists()) {
          tempFile.delete()
          Log.d(TAG, "Deleted temp file when removing paused state: ${tempFile.absolutePath}")
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to delete temp file when removing paused state: ${e.message}")
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
      Log.e(TAG, "Downloader: ${Log.getStackTraceString(e)}")
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

package com.eko

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Wrapper around Android's DownloadManager for managing file downloads.
 * Provides methods for downloading, canceling, and querying download status.
 *
 * For pause/resume functionality, this class integrates with ResumableDownloader
 * which uses HTTP Range headers to support true pause/resume.
 */
class Downloader(private val context: Context) {

  companion object {
    private const val TAG = "RNBackgroundDownloader"
  }

  val downloadManager: DownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
  val resumableDownloader = ResumableDownloader()

  // Track which config IDs are using resumable downloads (paused state)
  private val pausedDownloads = ConcurrentHashMap<String, PausedDownloadInfo>()

  // Track download IDs that are being intentionally paused (to ignore broadcast events)
  private val pausingDownloadIds = ConcurrentHashMap.newKeySet<Long>()

  data class PausedDownloadInfo(
    val configId: String,
    val url: String,
    val destination: String,
    val headers: Map<String, String>,
    val bytesDownloaded: Long,
    val bytesTotal: Long
  )

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
   * Resume a paused download using HTTP Range headers via ResumableDownloader.
   */
  fun resume(configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
    val pausedInfo = pausedDownloads[configId]
    if (pausedInfo == null) {
      Log.w(TAG, "No paused download found for configId: $configId")
      return false
    }

    // Start resumable download from where we left off
    resumableDownloader.startDownload(
      id = configId,
      url = pausedInfo.url,
      destination = pausedInfo.destination,
      headers = pausedInfo.headers,
      startByte = pausedInfo.bytesDownloaded,
      totalBytes = pausedInfo.bytesTotal,
      listener = listener
    )

    Log.d(TAG, "Resuming download $configId from ${pausedInfo.bytesDownloaded} bytes")
    return true
  }

  /**
   * Pause a resumable download that's currently in progress.
   */
  fun pauseResumable(configId: String): Boolean {
    return resumableDownloader.pause(configId)
  }

  /**
   * Resume a paused resumable download.
   */
  fun resumeResumable(configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
    return resumableDownloader.resume(configId, listener)
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

    return resumableDownloader.cancel(configId)
  }

  /**
   * Check if a download is paused.
   */
  fun isPaused(configId: String): Boolean {
    return pausedDownloads.containsKey(configId) || resumableDownloader.isPaused(configId)
  }

  /**
   * Get paused download info if available.
   */
  fun getPausedInfo(configId: String): PausedDownloadInfo? = pausedDownloads[configId]

  /**
   * Check if download is using resumable downloader.
   */
  fun isResumableDownload(configId: String): Boolean {
    return resumableDownloader.getState(configId) != null
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

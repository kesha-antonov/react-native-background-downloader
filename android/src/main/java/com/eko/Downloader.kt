package com.eko

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap


class Downloader(private val context: Context) {
  var downloadManager: DownloadManager


  init {
    this.downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
  }

  fun download(request: DownloadManager.Request?): Long {
    return downloadManager.enqueue(request)
  }

  fun cancel(downloadId: Long): Int {
    return downloadManager.remove(downloadId)
  }

  fun pause(downloadId: Long) {
    // Android DownloadManager does not provide a public API for pausing downloads.
    // The Downloads.Impl.* constants and private fields like mResolver are not accessible.
    // See: https://android-review.googlesource.com/c/platform/packages/providers/DownloadProvider/+/2089866
    throw UnsupportedOperationException(
      "Pause functionality is not supported by Android DownloadManager. " +
        "Consider using stop() and restart the download if needed."
    )
  }

  fun resume(downloadId: Long) {
    // Android DownloadManager does not provide a public API for resuming paused downloads.
    // The Downloads.Impl.* constants and private fields like mResolver are not accessible.
    // See: https://android-review.googlesource.com/c/platform/packages/providers/DownloadProvider/+/2089866
    throw UnsupportedOperationException(
      "Resume functionality is not supported by Android DownloadManager. " +
        "Downloads that were stopped need to be restarted from the beginning."
    )
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
      downloadManager.query(query).use { cursor ->
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
      Log.e("RNBackgroundDownloader", "Downloader: " + Log.getStackTraceString(e))
    }

    return result
  }

  fun getDownloadStatus(cursor: Cursor): WritableMap {
    val downloadId = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
    var localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
    val bytesDownloadedSoFar =
      cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
    val totalSizeBytes =
      cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
    val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))

    if (localUri != null) {
      localUri = localUri.replace("file://", "")
    }

    var reasonText = ""
    if (status == DownloadManager.STATUS_PAUSED || status == DownloadManager.STATUS_FAILED) {
      reasonText = getReasonText(status, reason)
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
    when (status) {
      DownloadManager.STATUS_FAILED -> when (reason) {
        DownloadManager.ERROR_CANNOT_RESUME -> return "ERROR_CANNOT_RESUME"
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> return "ERROR_DEVICE_NOT_FOUND"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> return "ERROR_FILE_ALREADY_EXISTS"
        DownloadManager.ERROR_FILE_ERROR -> return "ERROR_FILE_ERROR"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> return "ERROR_HTTP_DATA_ERROR"
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> return "ERROR_INSUFFICIENT_SPACE"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> return "ERROR_TOO_MANY_REDIRECTS"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> return "ERROR_UNHANDLED_HTTP_CODE"
        else -> return "ERROR_UNKNOWN"
      }

      DownloadManager.STATUS_PAUSED -> when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> return "PAUSED_QUEUED_FOR_WIFI"
        DownloadManager.PAUSED_UNKNOWN -> return "PAUSED_UNKNOWN"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> return "PAUSED_WAITING_FOR_NETWORK"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> return "PAUSED_WAITING_TO_RETRY"
        else -> return "UNKNOWN"
      }

      else -> return "UNKNOWN"
    }
  }
}

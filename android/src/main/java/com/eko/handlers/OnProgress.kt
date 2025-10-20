package com.eko.handlers

import android.app.DownloadManager
import android.database.Cursor
import android.os.SystemClock
import com.eko.Downloader
import com.eko.RNBGDTaskConfig
import com.eko.interfaces.ProgressCallback
import java.util.concurrent.Callable
import kotlin.concurrent.Volatile

class OnProgress(
  config: RNBGDTaskConfig,
  downloader: Downloader,
  downloadId: Long,
  bytesDownloaded: Long,
  bytesTotal: Long,
  callback: ProgressCallback
) : Callable<OnProgressState?> {
  private val config: RNBGDTaskConfig
  private val downloader: Downloader
  private val downloadId: Long
  private var bytesDownloaded: Long
  private var bytesTotal: Long
  private val callback: ProgressCallback

  @Volatile
  private var isRunning = true

  @Volatile
  private var isCompleted = true

  init {
    this.config = config
    this.downloader = downloader
    this.downloadId = downloadId
    this.bytesDownloaded = bytesDownloaded
    this.bytesTotal = bytesTotal
    this.callback = callback
  }

  @Throws(Exception::class)
  override fun call(): OnProgressState? {
    while (isRunning) {
      var status = -1
      val query = DownloadManager.Query()
      query.setFilterById(downloadId)

      try {
        downloader.downloadManager.query(query).use { cursor ->
          if (!cursor.moveToFirst()) {
            stopLoopWithFail()
            return@use
          }
          // TODO: Maybe we can write some logic in the pause codes here.
          //       For example; PAUSED_WAITING_TO_RETRY attempts count?
          status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
          if (status == DownloadManager.STATUS_SUCCESSFUL) {
            stopLoopWithSuccess()
            return@use
          }
          if (status == DownloadManager.STATUS_FAILED) {
            val reason: Int =
              cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val reasonText: String? = downloader.getReasonText(status, reason)
            throw Exception(reasonText)
          }

          val completed = updateProgress(cursor)
          if (completed) {
            stopLoopWithSuccess()
            return@use
          }
        }
      } catch (e: Exception) {
        stopLoopWithFail()
        // if reached maximum memory while downloading, the downloader broadcast can not receive event normally
        downloader.broadcast(downloadId)
        throw e
      }

      SystemClock.sleep(getSleepDuration(status))
    }

    return if (isCompleted) OnProgressState(config.id, bytesDownloaded, bytesTotal) else null
  }

  private fun stopLoopWithSuccess() {
    this.isRunning = false
    this.isCompleted = true
  }

  private fun stopLoopWithFail() {
    this.isRunning = false
    this.isCompleted = false
  }

  private fun updateProgress(cursor: Cursor): Boolean {
    val byteTotal = getColumnValue(DownloadManager.COLUMN_TOTAL_SIZE_BYTES, cursor)
    bytesTotal = if (byteTotal > 0) byteTotal else bytesTotal

    val byteDownloaded = getColumnValue(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, cursor)
    bytesDownloaded = if (byteDownloaded > 0) byteDownloaded else bytesDownloaded

    // Always call progress callback, even when total bytes are unknown (for realtime streams)
    callback.onProgress(config.id, bytesDownloaded, bytesTotal)

    return bytesTotal > 0 && bytesDownloaded > 0 && bytesDownloaded == bytesTotal
  }

  private fun getColumnValue(column: String?, cursor: Cursor): Long {
    val columnIndex = cursor.getColumnIndex(column)
    return if (columnIndex != -1) cursor.getDouble(columnIndex).toLong() else 0
  }

  private fun getSleepDuration(status: Int): Long {
    when (status) {
      DownloadManager.STATUS_PAUSED -> return 2000
      DownloadManager.STATUS_PENDING -> return 1000
      else -> return 250
    }
  }
}

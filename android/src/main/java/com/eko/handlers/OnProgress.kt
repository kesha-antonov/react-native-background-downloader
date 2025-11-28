package com.eko.handlers

import android.app.DownloadManager
import android.database.Cursor
import android.os.SystemClock
import com.eko.Downloader
import com.eko.RNBGDTaskConfig
import com.eko.interfaces.ProgressCallback
import java.util.concurrent.Callable

/**
 * Callable that monitors download progress and reports updates via callback.
 * Polls DownloadManager at regular intervals until download completes or fails.
 */
class OnProgress(
    private val config: RNBGDTaskConfig,
    private val downloader: Downloader,
    private val downloadId: Long,
    private var bytesDownloaded: Long,
    private var bytesTotal: Long,
    private val callback: ProgressCallback
) : Callable<OnProgressState?> {

    companion object {
        /** Sleep duration when download is paused (milliseconds) */
        private const val SLEEP_PAUSED_MS = 2000L
        /** Sleep duration when download is pending (milliseconds) */
        private const val SLEEP_PENDING_MS = 1000L
        /** Sleep duration during active download (milliseconds) */
        private const val SLEEP_ACTIVE_MS = 250L
    }

    @Volatile
    private var isRunning = true

    @Volatile
    private var isCompleted = true

    override fun call(): OnProgressState? {
        while (isRunning) {
            var status = -1
            val query = DownloadManager.Query()
            query.setFilterById(downloadId)

            try {
                downloader.downloadManager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        stopLoopWithFail()
                        return@use
                    }

                    // TODO: Maybe we can write some logic in the pause codes here.
                    //       For example; PAUSED_WAITING_TO_RETRY attempts count?
                    status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            stopLoopWithSuccess()
                            return@use
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            val reasonText = downloader.getReasonText(status, reason)
                            throw Exception(reasonText)
                        }
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
        isRunning = false
        isCompleted = true
    }

    private fun stopLoopWithFail() {
        isRunning = false
        isCompleted = false
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

    private fun getColumnValue(column: String, cursor: Cursor): Long {
        val columnIndex = cursor.getColumnIndex(column)
        return if (columnIndex != -1) cursor.getDouble(columnIndex).toLong() else 0
    }

    private fun getSleepDuration(status: Int): Long {
        return when (status) {
            DownloadManager.STATUS_PAUSED -> SLEEP_PAUSED_MS
            DownloadManager.STATUS_PENDING -> SLEEP_PENDING_MS
            else -> SLEEP_ACTIVE_MS
        }
    }
}

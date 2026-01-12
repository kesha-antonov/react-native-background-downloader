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
        /** Sleep duration when download is stalled - no progress for a while (milliseconds) */
        private const val SLEEP_STALLED_MS = 500L
        /** Number of polls with no progress before considering download stalled */
        private const val STALL_THRESHOLD = 4
    }

    @Volatile
    private var isRunning = true

    @Volatile
    private var isCompleted = true

    /** Tracks consecutive polls with no progress change */
    private var noProgressCount = 0
    private var lastBytesDownloaded = 0L

    override fun call(): OnProgressState? {
        while (isRunning) {
            var status = -1
            val query = DownloadManager.Query()
            query.setFilterById(downloadId)

            try {
                downloader.downloadManager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        // Check if download was intentionally cancelled (paused or stopped)
                        // Use atomic get-and-clear to avoid race conditions
                        val cancelIntent = downloader.getAndClearCancelIntent(downloadId)
                        if (cancelIntent != null) {
                            // Both PAUSING and STOPPING are intentional - not a failure
                            stopLoopWithSuccess()
                            return@use
                        }
                        stopLoopWithFail()
                        return@use
                    }

                    // Pause states (PAUSED_QUEUED_FOR_WIFI, PAUSED_WAITING_FOR_NETWORK, PAUSED_WAITING_TO_RETRY)
                    // are handled by DownloadManager internally with automatic retry. We just poll less frequently
                    // during pause (see getSleepDuration) and continue reporting progress when resumed.
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
        // DownloadManager returns -1 for unknown size, keep it as -1 to indicate unknown
        // Only update if we get a positive value (actual size known)
        if (byteTotal > 0) {
            bytesTotal = byteTotal
        } else if (byteTotal == -1L && bytesTotal <= 0) {
            // Server doesn't provide content length, keep as -1
            bytesTotal = -1L
        }

        val byteDownloaded = getColumnValue(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, cursor)
        bytesDownloaded = if (byteDownloaded > 0) byteDownloaded else bytesDownloaded

        // Always call progress callback, even when total bytes are unknown (for realtime streams)
        callback.onProgress(config.id, bytesDownloaded, bytesTotal)

        // Download is complete when we have valid total and downloaded equals total
        return bytesTotal > 0 && bytesDownloaded > 0 && bytesDownloaded == bytesTotal
    }

    private fun getColumnValue(column: String, cursor: Cursor): Long {
        val columnIndex = cursor.getColumnIndex(column)
        return if (columnIndex != -1) cursor.getDouble(columnIndex).toLong() else 0
    }

    /**
     * Determines optimal sleep duration based on download status and activity.
     * Uses adaptive polling: polls more frequently during active progress,
     * backs off when download is stalled, paused, or pending.
     */
    private fun getSleepDuration(status: Int): Long {
        // Check if download is making progress
        val isProgressing = bytesDownloaded > lastBytesDownloaded
        lastBytesDownloaded = bytesDownloaded

        if (isProgressing) {
            noProgressCount = 0
        } else {
            noProgressCount++
        }

        return when (status) {
            DownloadManager.STATUS_RUNNING -> {
                // Adaptive polling: back off if no progress is being made
                if (noProgressCount >= STALL_THRESHOLD) {
                    SLEEP_STALLED_MS
                } else {
                    SLEEP_ACTIVE_MS
                }
            }
            DownloadManager.STATUS_PAUSED -> SLEEP_PAUSED_MS
            DownloadManager.STATUS_PENDING -> SLEEP_PENDING_MS
            DownloadManager.STATUS_SUCCESSFUL,
            DownloadManager.STATUS_FAILED -> SLEEP_ACTIVE_MS // Loop will exit anyway
            else -> SLEEP_PENDING_MS // Unknown status, be conservative
        }
    }
}

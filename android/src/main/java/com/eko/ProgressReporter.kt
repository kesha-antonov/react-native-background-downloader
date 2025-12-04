package com.eko

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized progress reporting manager that handles:
 * - Percentage-based threshold filtering
 * - Bytes-based threshold filtering
 * - Time-based batching for efficient JS bridge calls
 * - Log throttling to reduce noise
 *
 * This consolidates progress reporting logic that was previously
 * duplicated across RNBackgroundDownloaderModuleImpl and ResumableDownloadService.
 */
class ProgressReporter(
    private val onEmitProgress: (WritableArray) -> Unit
) {
    companion object {
        private const val TAG = "ProgressReporter"
    }

    // Per-download tracking state
    private val configIdToPercent = ConcurrentHashMap<String, Double>()
    private val configIdToLastBytes = ConcurrentHashMap<String, Long>()
    private val progressReports = ConcurrentHashMap<String, WritableMap>()

    // Batching configuration
    private var progressInterval: Long = 0
    private var progressMinBytes: Long = 0
    private var lastProgressReportedAt = Date()

    /**
     * Configure the progress reporting thresholds.
     * @param interval Minimum milliseconds between batch emissions (0 = no time batching)
     * @param minBytes Minimum bytes change to trigger progress update (0 = use percentage only)
     */
    fun configure(interval: Long, minBytes: Long) {
        progressInterval = interval
        progressMinBytes = minBytes
    }

    /**
     * Get the current progress interval setting.
     */
    fun getProgressInterval(): Long = progressInterval

    /**
     * Get the current minimum bytes setting.
     */
    fun getProgressMinBytes(): Long = progressMinBytes

    /**
     * Report download progress. This method handles:
     * 1. Threshold filtering (percentage and/or bytes based)
     * 2. Batching progress reports
     * 3. Emitting batched reports when interval elapses
     *
     * @param configId The download identifier
     * @param bytesDownloaded Current bytes downloaded
     * @param bytesTotal Total bytes to download (-1 if unknown)
     */
    fun reportProgress(configId: String, bytesDownloaded: Long, bytesTotal: Long) {
        val prevPercent = configIdToPercent[configId] ?: 0.0
        val prevBytes = configIdToLastBytes[configId] ?: 0L

        // For unknown total (-1), use 0 for percent calculation
        val effectiveTotal = if (bytesTotal > 0) bytesTotal else 0L
        val percent = if (effectiveTotal > 0) bytesDownloaded.toDouble() / effectiveTotal else 0.0

        // Check if we should report progress based on percentage OR bytes threshold
        val percentThresholdMet = effectiveTotal > 0 &&
            (percent - prevPercent > DownloadConstants.PROGRESS_REPORT_THRESHOLD)

        // Only check bytes threshold if progressMinBytes > 0
        val bytesThresholdMet = progressMinBytes > 0 &&
            (bytesDownloaded - prevBytes >= progressMinBytes)

        // Report progress if either threshold is met, or if total bytes unknown (for realtime streams)
        // bytesTotal <= 0 means unknown size (-1) or zero
        if (percentThresholdMet || bytesThresholdMet || bytesTotal <= 0) {
            val params = Arguments.createMap()
            params.putString("id", configId)
            params.putDouble("bytesDownloaded", bytesDownloaded.toDouble())
            params.putDouble("bytesTotal", bytesTotal.toDouble())
            progressReports[configId] = params
            configIdToPercent[configId] = percent
            configIdToLastBytes[configId] = bytesDownloaded
        }

        // Check if it's time to emit batched reports
        emitBatchedReportsIfNeeded()
    }

    /**
     * Check if the time interval has passed and emit any pending reports.
     */
    private fun emitBatchedReportsIfNeeded() {
        val now = Date()
        val isReportTimeDifference = now.time - lastProgressReportedAt.time > progressInterval
        val isReportNotEmpty = progressReports.isNotEmpty()

        if (isReportTimeDifference && isReportNotEmpty) {
            emitBatchedReports()
            lastProgressReportedAt = now
        }
    }

    /**
     * Emit all batched progress reports to JS.
     */
    private fun emitBatchedReports() {
        // Create a copy to avoid concurrent modification
        val reportsList = progressReports.values.toList()
        val reportsArray = Arguments.createArray()

        for (report in reportsList) {
            reportsArray.pushMap(report.copy())
        }

        onEmitProgress(reportsArray)
        progressReports.clear()
    }

    /**
     * Clear all tracking state for a specific download.
     * Call this when a download completes, fails, or is cancelled.
     *
     * @param configId The download identifier to clean up
     */
    fun clearDownloadState(configId: String) {
        configIdToPercent.remove(configId)
        configIdToLastBytes.remove(configId)
        progressReports.remove(configId)
    }

    /**
     * Clear any pending progress report for a download without emitting it.
     * Use this when stopping a download to prevent stale data.
     *
     * @param configId The download identifier
     */
    fun clearPendingReport(configId: String) {
        progressReports.remove(configId)
    }

    /**
     * Set the percent tracking for a download (for restoring state).
     */
    fun setPercent(configId: String, percent: Double) {
        configIdToPercent[configId] = percent
    }

    /**
     * Initialize tracking for a new download.
     */
    fun initializeDownload(configId: String) {
        configIdToPercent[configId] = 0.0
        configIdToLastBytes[configId] = 0L
    }
}

/**
 * Extension function to copy a WritableMap.
 * React Native's WritableMap can only be consumed once, so we need to copy it.
 */
fun WritableMap.copy(): WritableMap {
    val copy = Arguments.createMap()
    val iterator = this.keySetIterator()
    while (iterator.hasNextKey()) {
        val key = iterator.nextKey()
        when (this.getType(key)) {
            com.facebook.react.bridge.ReadableType.Null -> copy.putNull(key)
            com.facebook.react.bridge.ReadableType.Boolean -> copy.putBoolean(key, this.getBoolean(key))
            com.facebook.react.bridge.ReadableType.Number -> copy.putDouble(key, this.getDouble(key))
            com.facebook.react.bridge.ReadableType.String -> copy.putString(key, this.getString(key))
            com.facebook.react.bridge.ReadableType.Map -> copy.putMap(key, this.getMap(key))
            com.facebook.react.bridge.ReadableType.Array -> copy.putArray(key, this.getArray(key))
        }
    }
    return copy
}

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
 * @param onEmitProgress Callback to emit batched progress reports
 * @param bytesFieldName The field name for bytes progress (e.g., "bytesDownloaded" for downloads, "bytesUploaded" for uploads)
 */
class ProgressReporter(
    private val onEmitProgress: (WritableArray) -> Unit,
    private val bytesFieldName: String = "bytesDownloaded"
) {
    companion object {
        private const val TAG = "ProgressReporter"
    }

    private val thresholds = ProgressThresholdTracker()
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
        thresholds.minBytes = minBytes
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
        if (thresholds.shouldReport(configId, bytesDownloaded, bytesTotal)) {
            val params = Arguments.createMap()
            params.putString("id", configId)
            params.putDouble(bytesFieldName, bytesDownloaded.toDouble())
            params.putDouble("bytesTotal", bytesTotal.toDouble())
            progressReports[configId] = params
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
        thresholds.clear(configId)
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
        thresholds.setPercent(configId, percent)
    }

    /**
     * Initialize tracking for a new download.
     */
    fun initializeDownload(configId: String) {
        thresholds.initialize(configId)
    }
}

internal class ProgressThresholdTracker(var minBytes: Long = 0) {
    private val percents = ConcurrentHashMap<String, Double>()
    private val lastBytes = ConcurrentHashMap<String, Long>()

    fun shouldReport(id: String, bytes: Long, total: Long): Boolean {
        val percent = if (total > 0) bytes.toDouble() / total else 0.0
        val report = total <= 0 ||
            percent - (percents[id] ?: 0.0) > DownloadConstants.PROGRESS_REPORT_THRESHOLD ||
            minBytes > 0 && bytes - (lastBytes[id] ?: 0L) >= minBytes
        if (report) {
            percents[id] = percent
            lastBytes[id] = bytes
        }
        return report
    }

    fun initialize(id: String) {
        percents[id] = 0.0
        lastBytes[id] = 0L
    }

    fun clear(id: String) {
        percents.remove(id)
        lastBytes.remove(id)
    }

    fun setPercent(id: String, percent: Double) {
        percents[id] = percent
    }
}

/**
 * Extension function to copy a WritableMap.
 * React Native's WritableMap can only be consumed once, so we need to copy it.
 */
fun com.facebook.react.bridge.ReadableMap.copy(): WritableMap {
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

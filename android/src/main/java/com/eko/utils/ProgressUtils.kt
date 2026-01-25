package com.eko.utils

/**
 * Utility functions for calculating download progress.
 */
object ProgressUtils {
    /**
     * Calculate progress percentage from bytes downloaded and total bytes.
     * @param bytesDownloaded Bytes downloaded so far
     * @param bytesTotal Total bytes expected (-1 if unknown)
     * @return Progress percentage (0-100), or 0 if total is unknown or zero
     */
    fun calculateProgress(bytesDownloaded: Long, bytesTotal: Long): Int {
        return if (bytesTotal > 0) {
            ((bytesDownloaded.toDouble() / bytesTotal) * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    /**
     * Check if progress update should be sent based on time and progress thresholds.
     * @param currentProgress Current progress percentage
     * @param lastNotifiedProgress Last notified progress percentage
     * @param currentTime Current timestamp in milliseconds
     * @param lastUpdateTime Last update timestamp in milliseconds
     * @param updateInterval Minimum time between updates in milliseconds
     * @return true if update should be sent
     */
    fun shouldUpdateProgress(
        currentProgress: Int,
        lastNotifiedProgress: Int,
        currentTime: Long,
        lastUpdateTime: Long,
        updateInterval: Long
    ): Boolean {
        val progressChanged = currentProgress != lastNotifiedProgress
        val timeSinceLastUpdate = currentTime - lastUpdateTime
        val isFirstUpdate = lastUpdateTime == 0L
        val isComplete = currentProgress == 100

        return progressChanged && (
            timeSinceLastUpdate >= updateInterval ||
            isFirstUpdate ||
            isComplete
        )
    }
}

/**
 * Extension function to calculate progress percentage.
 */
fun Long.toProgressPercent(total: Long): Int = ProgressUtils.calculateProgress(this, total)

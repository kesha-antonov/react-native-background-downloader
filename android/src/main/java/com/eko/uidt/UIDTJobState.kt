package com.eko.uidt

import android.app.job.JobParameters
import com.eko.ResumableDownloader
import java.util.concurrent.ConcurrentHashMap

/**
 * Data class representing the state of an active UIDT download job.
 */
data class JobState(
    val params: JobParameters,
    val resumableDownloader: ResumableDownloader,
    var notificationId: Int,
    val groupId: String = "",
    val groupName: String = "",
    var lastNotifiedProgress: Int = -1,
    var lastNotificationUpdateTime: Long = 0
)

/**
 * Configuration for notification display.
 */
data class NotificationConfig(
    var groupingEnabled: Boolean = false,
    var showNotificationsEnabled: Boolean = false,
    var updateInterval: Long = 500L,
    val texts: MutableMap<String, String> = mutableMapOf(
        "downloadTitle" to "Download",
        "downloadStarting" to "Starting download...",
        "downloadProgress" to "Downloading... {progress}%",
        "downloadPaused" to "Paused",
        "downloadFinished" to "Download complete",
        "groupTitle" to "Downloads",
        "groupText" to "{count} download(s) in progress"
    )
) {
    fun getText(key: String, vararg replacements: Pair<String, Any>): String {
        var text = texts[key] ?: ""
        for ((placeholder, value) in replacements) {
            text = text.replace("{$placeholder}", value.toString())
        }
        return text
    }

    fun updateTexts(newTexts: Map<String, String>) {
        newTexts.forEach { (key, value) ->
            texts[key] = value
        }
    }
}

/**
 * Constants for UIDT jobs.
 */
object UIDTConstants {
    const val TAG = "UIDTDownloadJobService"

    // Job ID base - we add hash of config ID to make unique job IDs
    const val JOB_ID_BASE = 10000

    // PersistableBundle keys
    const val KEY_DOWNLOAD_ID = "download_id"
    const val KEY_URL = "url"
    const val KEY_DESTINATION = "destination"
    const val KEY_START_BYTE = "start_byte"
    const val KEY_TOTAL_BYTES = "total_bytes"
    const val KEY_METADATA = "metadata"

    // Notification channel for UIDT jobs (visible notifications)
    const val NOTIFICATION_CHANNEL_ID = "uidt_download_channel"

    // Notification channel for UIDT jobs (silent/hidden notifications)
    const val NOTIFICATION_CHANNEL_SILENT_ID = "uidt_download_channel_silent"

    // Notification group for grouping all download notifications together
    const val NOTIFICATION_GROUP_KEY = "com.eko.DOWNLOAD_GROUP"

    // Summary notification ID (used to group all download notifications)
    const val SUMMARY_NOTIFICATION_ID = 19999

    // Base for individual notification IDs
    const val NOTIFICATION_ID_BASE = 20000
}

/**
 * Data class representing the state of a UIDT job for external queries.
 */
data class UIDTJobInfo(
    val id: String,
    val status: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val url: String,
    val destination: String,
    val metadata: String
)

/**
 * Singleton for managing active UIDT jobs state.
 */
object UIDTJobRegistry {
    // Track active jobs for pause/resume
    val activeJobs = ConcurrentHashMap<String, JobState>()

    // Static storage for headers (PersistableBundle can't store complex objects)
    val pendingHeaders = ConcurrentHashMap<String, Map<String, String>>()

    // Static listener reference (set by Downloader)
    @Volatile
    var downloadListener: ResumableDownloader.DownloadListener? = null

    // Store service instance reference for notification updates
    @Volatile
    var serviceInstance: com.eko.UIDTDownloadJobService? = null

    // Notification configuration
    val notificationConfig = NotificationConfig()

    fun isActiveJob(configId: String): Boolean = activeJobs.containsKey(configId)

    fun isPausedJob(configId: String): Boolean {
        val jobState = activeJobs[configId]
        return jobState?.resumableDownloader?.isPaused(configId) ?: false
    }

    fun getJobDownloadState(configId: String): ResumableDownloader.DownloadState? {
        val jobState = activeJobs[configId] ?: return null
        return jobState.resumableDownloader.getState(configId)
    }

    /**
     * Returns a snapshot of all currently active UIDT jobs.
     * Used by the module to populate getExistingDownloads on Android 14+.
     *
     * Status values match DownloadManager constants for JS consistency:
     * - STATUS_RUNNING = 2 (1 << 1)
     * - STATUS_PAUSED = 4 (1 << 2)
     */
    fun getAllActiveJobs(): List<UIDTJobInfo> {
        return activeJobs.map { (configId, jobState) ->
            val state = jobState.resumableDownloader.getState(configId)
            val isPaused = jobState.resumableDownloader.isPaused(configId)

            // Map to DownloadManager constants so JS logic stays consistent
            // STATUS_RUNNING = 2, STATUS_PAUSED = 4
            val status = if (isPaused) 4 else 2

            // Retrieve URL/Dest from the job extras
            val extras = jobState.params.extras
            val url = extras.getString(UIDTConstants.KEY_URL) ?: ""
            val destination = extras.getString(UIDTConstants.KEY_DESTINATION) ?: ""
            val metadata = extras.getString(UIDTConstants.KEY_METADATA) ?: "{}"

            UIDTJobInfo(
                id = configId,
                status = status,
                bytesDownloaded = state?.bytesDownloaded?.get() ?: 0L,
                bytesTotal = state?.bytesTotal ?: -1L,
                url = url,
                destination = destination,
                metadata = metadata
            )
        }
    }
}

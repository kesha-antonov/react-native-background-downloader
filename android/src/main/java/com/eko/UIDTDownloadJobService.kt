package com.eko

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.eko.uidt.JobState
import com.eko.uidt.UIDTConstants
import com.eko.uidt.UIDTJobInfo
import com.eko.uidt.UIDTJobManager
import com.eko.uidt.UIDTJobRegistry
import com.eko.uidt.UIDTNotificationManager
import com.eko.utils.ProgressUtils
import org.json.JSONObject

/**
 * A JobService that uses User-Initiated Data Transfer (UIDT) for background downloads.
 *
 * UIDT was introduced in Android 14 (API 34) and is required for reliable background
 * downloads on Android 16+ where foreground services are more restricted.
 *
 * Key benefits of UIDT:
 * - Not affected by App Standby Buckets quotas
 * - Can run for extended periods as system conditions allow
 * - Shows in Task Manager for user visibility
 * - Properly handles thermal throttling and system health restrictions
 *
 * This class handles only the JobService lifecycle. Job management, notifications,
 * and state are delegated to:
 * - [UIDTJobManager] - Job scheduling, cancellation, pause/resume
 * - [UIDTNotificationManager] - Notification creation and updates
 * - [UIDTJobRegistry] - Active job state tracking
 *
 * @see <a href="https://developer.android.com/develop/background-work/background-tasks/uidt">UIDT Documentation</a>
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UIDTDownloadJobService : JobService() {

    companion object {
        // Delegate static methods to UIDTJobManager and UIDTJobRegistry for backward compatibility

        /**
         * Schedule a UIDT download job.
         */
        fun scheduleDownload(
            context: Context,
            configId: String,
            url: String,
            destination: String,
            headers: Map<String, String>,
            startByte: Long = 0,
            totalBytes: Long = -1,
            metadata: String = "{}"
        ): Boolean = UIDTJobManager.scheduleDownload(context, configId, url, destination, headers, startByte, totalBytes, metadata)

        /**
         * Cancel a scheduled UIDT job.
         */
        fun cancelJob(context: Context, configId: String) = UIDTJobManager.cancelJob(context, configId)

        /**
         * Check if a download is active as a UIDT job.
         */
        fun isActiveJob(configId: String): Boolean = UIDTJobRegistry.isActiveJob(configId)

        /**
         * Check if a download is paused in a UIDT job.
         */
        fun isPausedJob(configId: String): Boolean = UIDTJobRegistry.isPausedJob(configId)

        /**
         * Get the download state for a UIDT job.
         */
        fun getJobDownloadState(configId: String): ResumableDownloader.DownloadState? = UIDTJobRegistry.getJobDownloadState(configId)

        /**
         * Pause an active UIDT download.
         */
        fun pauseJob(context: Context, configId: String): Boolean = UIDTJobManager.pauseJob(context, configId)

        /**
         * Resume a paused UIDT download.
         */
        fun resumeJob(context: Context, configId: String, listener: ResumableDownloader.DownloadListener): Boolean =
            UIDTJobManager.resumeJob(context, configId, listener)

        /**
         * Configure notification grouping and texts.
         */
        fun setNotificationGroupingConfig(enabled: Boolean, showNotificationsEnabled: Boolean, texts: Map<String, String>) =
            UIDTJobManager.setNotificationConfig(enabled, showNotificationsEnabled, texts)

        /**
         * Set notification update interval.
         */
        fun setNotificationUpdateInterval(interval: Long) = UIDTJobManager.setNotificationUpdateInterval(interval)

        /**
         * Check if notifications are enabled globally.
         */
        fun isNotificationsEnabled(): Boolean = UIDTJobManager.isNotificationsEnabled()

        /**
         * Cancel notification for a specific download.
         */
        fun cancelNotification(context: Context, configId: String) = UIDTNotificationManager.cancelNotification(context, configId)

        /**
         * Returns a snapshot of all currently active UIDT jobs.
         * Used by the module to populate getExistingDownloads on Android 14+.
         */
        fun getAllActiveJobs(): List<UIDTJobInfo> = UIDTJobRegistry.getAllActiveJobs()

        /**
         * Set download listener.
         */
        var downloadListener: ResumableDownloader.DownloadListener?
            get() = UIDTJobRegistry.downloadListener
            set(value) { UIDTJobRegistry.downloadListener = value }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        UIDTJobRegistry.serviceInstance = this
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "UIDTDownloadJobService created")
        UIDTNotificationManager.createNotificationChannels(this)
    }

    override fun onDestroy() {
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "UIDTDownloadJobService destroyed")
        UIDTJobRegistry.serviceInstance = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onStartJob(params: JobParameters): Boolean {
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onStartJob called")

        val extras = params.extras
        val configId = extras.getString(UIDTConstants.KEY_DOWNLOAD_ID) ?: run {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "No download ID in job extras")
            return false
        }
        val url = extras.getString(UIDTConstants.KEY_URL) ?: return false
        val destination = extras.getString(UIDTConstants.KEY_DESTINATION) ?: return false
        val startByte = extras.getLong(UIDTConstants.KEY_START_BYTE, 0)
        val totalBytes = extras.getLong(UIDTConstants.KEY_TOTAL_BYTES, -1)

        // Extract group info from metadata (for notification grouping)
        val metadataJson = extras.getString(UIDTConstants.KEY_METADATA) ?: "{}"
        var groupId = ""
        var groupName = ""
        try {
            val json = JSONObject(metadataJson)
            groupId = json.optString("groupId", "")
            groupName = json.optString("groupName", "")
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Failed to parse metadata: ${e.message}")
        }

        // Retrieve headers
        val headers = UIDTJobRegistry.pendingHeaders.remove(configId) ?: emptyMap()

        // Create notification for UIDT job (required)
        val notificationId = UIDTNotificationManager.getNotificationIdForConfig(configId)
        val notification = UIDTNotificationManager.createDownloadNotification(this, configId, groupId, groupName)

        // Set the notification for this job (required for UIDT)
        setNotification(params, notificationId, notification, JOB_END_NOTIFICATION_POLICY_DETACH)

        // Update summary notification if grouping enabled
        UIDTNotificationManager.updateSummaryNotification(this, groupId, groupName)

        // Acquire wake lock
        acquireWakeLock()

        // Create ResumableDownloader for this job
        val resumableDownloader = ResumableDownloader()

        // Store job state
        UIDTJobRegistry.activeJobs[configId] = JobState(params, resumableDownloader, notificationId, groupId, groupName)

        // Create listener that will notify completion
        val jobListener = createJobListener(configId, params, groupId, groupName)

        // Start the download asynchronously
        resumableDownloader.startDownload(
            id = configId,
            url = url,
            destination = destination,
            headers = headers,
            listener = jobListener,
            startByte = startByte,
            totalBytes = totalBytes
        )

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Started UIDT download: $configId from byte $startByte")

        // Return true - work is being done asynchronously
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val extras = params.extras
        val configId = extras.getString(UIDTConstants.KEY_DOWNLOAD_ID)

        val stopReason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.stopReason
        } else {
            -1
        }

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "onStopJob called for $configId, reason: $stopReason")

        if (configId != null) {
            val jobState = UIDTJobRegistry.activeJobs[configId]
            if (jobState != null) {
                // Pause the download - it can be resumed later
                jobState.resumableDownloader.pause(configId)

                // Save state for potential resume
                val state = jobState.resumableDownloader.getState(configId)
                if (state != null) {
                    // Store resume info
                    UIDTJobRegistry.pendingHeaders[configId] = state.headers
                }
            }
            UIDTJobRegistry.activeJobs.remove(configId)
        }

        releaseWakeLock()

        // Return true to reschedule the job if it was stopped by system
        return true
    }

    private fun createJobListener(
        configId: String,
        params: JobParameters,
        groupId: String,
        groupName: String
    ): ResumableDownloader.DownloadListener {
        return object : ResumableDownloader.DownloadListener {
            override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) {
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "UIDT download begin: $id, expectedBytes: $expectedBytes")

                // Update notification with size info
                val jobState = UIDTJobRegistry.activeJobs[id]
                if (jobState != null) {
                    UIDTNotificationManager.updateProgressNotification(this@UIDTDownloadJobService, jobState, 0, expectedBytes)
                }

                // Notify external listener
                UIDTJobRegistry.downloadListener?.onBegin(id, expectedBytes, headers)
            }

            override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) {
                val jobState = UIDTJobRegistry.activeJobs[id]
                if (jobState != null) {
                    // Check if download is paused - don't update notification with progress
                    val isPaused = jobState.resumableDownloader.isPaused(id)
                    if (isPaused) {
                        // Still notify external listener for state tracking, but don't update notification
                        UIDTJobRegistry.downloadListener?.onProgress(id, bytesDownloaded, bytesTotal)
                        return
                    }

                    if (bytesTotal > 0) {
                        val progress = ProgressUtils.calculateProgress(bytesDownloaded, bytesTotal)
                        val currentTime = System.currentTimeMillis()
                        val config = UIDTJobRegistry.notificationConfig

                        val shouldUpdate = ProgressUtils.shouldUpdateProgress(
                            progress,
                            jobState.lastNotifiedProgress,
                            currentTime,
                            jobState.lastNotificationUpdateTime,
                            config.updateInterval
                        )

                        if (shouldUpdate) {
                            jobState.lastNotifiedProgress = progress
                            jobState.lastNotificationUpdateTime = currentTime
                            UIDTNotificationManager.updateProgressNotification(this@UIDTDownloadJobService, jobState, bytesDownloaded, bytesTotal)
                        }
                    }
                }

                // Notify external listener (always - JS handles its own throttling)
                UIDTJobRegistry.downloadListener?.onProgress(id, bytesDownloaded, bytesTotal)
            }

            override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "UIDT download complete: $id")

                val jobState = UIDTJobRegistry.activeJobs[id]
                val notificationId = jobState?.notificationId

                // Cancel individual notification
                if (notificationId != null) {
                    UIDTNotificationManager.cancelNotification(this@UIDTDownloadJobService, notificationId)
                }

                // Clean up
                UIDTJobRegistry.activeJobs.remove(id)
                releaseWakeLock()

                // Update summary notification for this group
                UIDTNotificationManager.updateSummaryNotification(this@UIDTDownloadJobService, groupId, groupName)

                // Signal job completion
                jobFinished(params, false)

                // Notify external listener
                UIDTJobRegistry.downloadListener?.onComplete(id, location, bytesDownloaded, bytesTotal)
            }

            override fun onError(id: String, error: String, errorCode: Int) {
                RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "UIDT download error: $id - $error ($errorCode)")

                val jobState = UIDTJobRegistry.activeJobs[id]
                val notificationId = jobState?.notificationId

                // Cancel individual notification
                if (notificationId != null) {
                    UIDTNotificationManager.cancelNotification(this@UIDTDownloadJobService, notificationId)
                }

                // Clean up
                UIDTJobRegistry.activeJobs.remove(id)
                releaseWakeLock()

                // Update summary notification for this group
                UIDTNotificationManager.updateSummaryNotification(this@UIDTDownloadJobService, groupId, groupName)

                // Signal job completion with no reschedule
                jobFinished(params, false)

                // Notify external listener
                UIDTJobRegistry.downloadListener?.onError(id, error, errorCode)
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UIDTDownloadJobService::WakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(DownloadConstants.WAKELOCK_TIMEOUT_MS)
            }
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}

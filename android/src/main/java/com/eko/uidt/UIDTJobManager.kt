package com.eko.uidt

import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import com.eko.RNBackgroundDownloaderModuleImpl
import com.eko.ResumableDownloader
import com.eko.UIDTDownloadJobService
import com.eko.utils.ProgressUtils

/**
 * Manages UIDT job scheduling, cancellation, pause and resume operations.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object UIDTJobManager {

    private val config: NotificationConfig
        get() = UIDTJobRegistry.notificationConfig

    /**
     * Check if UIDT is available on this device.
     */
    fun isUIDTAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    /**
     * Schedule a UIDT download job.
     *
     * @param context Application context
     * @param configId Unique download identifier
     * @param url Download URL
     * @param destination File destination path
     * @param headers HTTP headers for the request
     * @param startByte Byte position to resume from (0 for new downloads)
     * @param totalBytes Total expected bytes (-1 if unknown)
     * @param metadata JSON metadata with course info for notification grouping
     * @return true if job was scheduled successfully
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
    ): Boolean {
        if (!isUIDTAvailable()) {
            RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "UIDT requires Android 14+, falling back to foreground service")
            return false
        }

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

        // Create unique job ID from config ID
        val jobId = UIDTConstants.JOB_ID_BASE + (configId.hashCode() and 0x7FFFFFFF) % 10000

        // Store headers for later retrieval (PersistableBundle can't store Map<String, String>)
        UIDTJobRegistry.pendingHeaders[configId] = headers

        // Create extras bundle
        val extras = PersistableBundle().apply {
            putString(UIDTConstants.KEY_DOWNLOAD_ID, configId)
            putString(UIDTConstants.KEY_URL, url)
            putString(UIDTConstants.KEY_DESTINATION, destination)
            putLong(UIDTConstants.KEY_START_BYTE, startByte)
            putLong(UIDTConstants.KEY_TOTAL_BYTES, totalBytes)
            putString(UIDTConstants.KEY_METADATA, metadata)
        }

        // Build network request - require internet connectivity
        val networkRequest = NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Build the job with UIDT flag
        val jobInfo = JobInfo.Builder(jobId, ComponentName(context, UIDTDownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetwork(networkRequest)
            .setExtras(extras)
            // Estimate network bytes for better scheduling (use 100MB as default estimate)
            .setEstimatedNetworkBytes(
                if (totalBytes > 0) totalBytes else 100 * 1024 * 1024L,
                0 // Upload bytes
            )
            .build()

        val result = jobScheduler.schedule(jobInfo)
        val success = result == JobScheduler.RESULT_SUCCESS

        if (success) {
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Scheduled UIDT job for $configId (jobId=$jobId)")
        } else {
            RNBackgroundDownloaderModuleImpl.logE(UIDTConstants.TAG, "Failed to schedule UIDT job for $configId")
            UIDTJobRegistry.pendingHeaders.remove(configId)
        }

        return success
    }

    /**
     * Cancel a scheduled UIDT job.
     */
    fun cancelJob(context: Context, configId: String) {
        if (!isUIDTAvailable()) return

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "cancelJob called for $configId")

        // Get job state before removing (for notification cleanup)
        val jobState = UIDTJobRegistry.activeJobs[configId]
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "cancelJob: jobState=$jobState")

        if (jobState != null) {
            // First cancel the download in the ResumableDownloader (stops the actual HTTP download)
            jobState.resumableDownloader.cancel(configId)
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled ResumableDownloader for $configId")

            // Use setNotification with REMOVE policy to properly dismiss the notification
            val service = UIDTJobRegistry.serviceInstance
            if (service != null) {
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Using setNotification with REMOVE policy for $configId")
                val emptyNotification = UIDTNotificationManager.createEmptyNotification(context)
                service.setNotification(
                    jobState.params,
                    jobState.notificationId,
                    emptyNotification,
                    JobService.JOB_END_NOTIFICATION_POLICY_REMOVE
                )
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Calling jobFinished for $configId")
                service.jobFinished(jobState.params, false)
            }

            // Also cancel via NotificationManager as a fallback
            UIDTNotificationManager.cancelNotification(context, jobState.notificationId)
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled notification ${jobState.notificationId} for $configId")
        }

        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobId = UIDTConstants.JOB_ID_BASE + (configId.hashCode() and 0x7FFFFFFF) % 10000

        jobScheduler.cancel(jobId)
        UIDTJobRegistry.pendingHeaders.remove(configId)
        UIDTJobRegistry.activeJobs.remove(configId)

        // Update summary notification if grouping was enabled
        if (jobState != null && config.groupingEnabled && jobState.groupId.isNotEmpty()) {
            UIDTNotificationManager.updateSummaryNotification(context, jobState.groupId, jobState.groupName)
        }

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled UIDT job for $configId")
    }

    /**
     * Pause an active UIDT download.
     * This will pause the download, cancel the UIDT job, but keep a detached notification showing paused state.
     * The download state is persisted and can be resumed later by creating a new UIDT job.
     */
    fun pauseJob(context: Context, configId: String): Boolean {
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "pauseJob called: configId=$configId, showNotificationsEnabled=${config.showNotificationsEnabled}")
        val jobState = UIDTJobRegistry.activeJobs[configId] ?: return false

        // First pause the actual download (this saves state to disk)
        val result = jobState.resumableDownloader.pause(configId)
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "pauseJob: pause result=$result")

        if (result) {
            // Get progress before removing job state
            val downloadState = jobState.resumableDownloader.getState(configId)
            val bytesDownloaded = downloadState?.bytesDownloaded?.get() ?: 0L
            val bytesTotal = downloadState?.bytesTotal ?: -1L
            val progress = ProgressUtils.calculateProgress(bytesDownloaded, bytesTotal)
            val groupName = jobState.groupName
            val notificationId = UIDTNotificationManager.getNotificationIdForConfig(configId)

            // Call jobFinished to properly end the UIDT job
            // Note: jobFinished(params, false) is sufficient - no need for jobScheduler.cancel()
            // since wantsReschedule=false tells the system the job is complete and should not be rescheduled
            val service = UIDTJobRegistry.serviceInstance
            if (service != null) {
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Calling jobFinished for paused job $configId")
                service.jobFinished(jobState.params, false)
            }

            // Remove from activeJobs - will create new job on resume
            UIDTJobRegistry.activeJobs.remove(configId)

            // Show detached paused notification (not tied to UIDT job)
            if (config.showNotificationsEnabled) {
                UIDTNotificationManager.showPausedNotification(context, configId, notificationId, progress, groupName)
            }
        }
        return result
    }

    /**
     * Resume a paused UIDT download.
     */
    fun resumeJob(context: Context, configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
        val jobState = UIDTJobRegistry.activeJobs[configId] ?: return false
        UIDTJobRegistry.downloadListener = listener

        // Reset notification timing to allow immediate updates after resume
        jobState.lastNotificationUpdateTime = 0L

        // Update notification to show resuming/downloading state
        if (config.showNotificationsEnabled) {
            UIDTNotificationManager.updateResumedNotification(context, jobState)
        }

        return jobState.resumableDownloader.resume(configId, listener)
    }

    /**
     * Configure notification settings.
     */
    fun setNotificationConfig(enabled: Boolean, showNotifications: Boolean, mode: String, texts: Map<String, String>) {
        config.groupingEnabled = enabled
        config.showNotificationsEnabled = showNotifications
        config.mode = when (mode) {
            "summaryOnly" -> NotificationGroupingMode.SUMMARY_ONLY
            else -> NotificationGroupingMode.INDIVIDUAL
        }
        config.updateTexts(texts)
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Notification config updated: grouping=$enabled, showNotificationsEnabled=$showNotifications, mode=${config.mode}, texts=$texts")
    }

    /**
     * Set notification update interval (should match progressInterval).
     */
    fun setNotificationUpdateInterval(interval: Long) {
        config.updateInterval = interval
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Notification update interval set to: $interval ms")
    }

    /**
     * Check if notifications are enabled globally.
     */
    fun isNotificationsEnabled(): Boolean = config.showNotificationsEnabled
}

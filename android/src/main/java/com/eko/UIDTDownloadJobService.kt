package com.eko

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
 * @see <a href="https://developer.android.com/develop/background-work/background-tasks/uidt">UIDT Documentation</a>
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class UIDTDownloadJobService : JobService() {

    companion object {
        private const val TAG = "UIDTDownloadJobService"

        // Job ID base - we add hash of config ID to make unique job IDs
        private const val JOB_ID_BASE = 10000

        // PersistableBundle keys
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_URL = "url"
        const val KEY_DESTINATION = "destination"
        const val KEY_START_BYTE = "start_byte"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_METADATA = "metadata"
        // Headers are stored separately due to PersistableBundle limitations

        // Notification channel for UIDT jobs (visible notifications)
        private const val UIDT_NOTIFICATION_CHANNEL_ID = "uidt_download_channel"

        // Notification channel for UIDT jobs (silent/hidden notifications)
        private const val UIDT_NOTIFICATION_CHANNEL_SILENT_ID = "uidt_download_channel_silent"

        // Notification group for grouping all download notifications together
        private const val NOTIFICATION_GROUP_KEY = "com.eko.DOWNLOAD_GROUP"

        // Summary notification ID (used to group all download notifications)
        private const val SUMMARY_NOTIFICATION_ID = 19999

        // Atomic counter for notification IDs
        private val notificationIdCounter = AtomicInteger(20000)

        /**
         * Generate a stable notification ID for a configId.
         * This ensures the same download always uses the same notification ID,
         * even across app restarts.
         */
        private fun getNotificationIdForConfig(configId: String): Int {
            // Use hash of configId to get stable ID, ensure it's positive and above 20000
            return 20000 + (configId.hashCode() and 0x7FFFFFFF) % 100000
        }

        /**
         * Cancel notification for a specific download.
         * Use this to clean up stale notifications from previous app sessions.
         */
        fun cancelNotification(context: Context, configId: String) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationId = getNotificationIdForConfig(configId)
            notificationManager.cancel(notificationId)
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelled notification $notificationId for $configId")
        }

        // Static storage for headers (PersistableBundle can't store complex objects)
        private val pendingHeaders = ConcurrentHashMap<String, Map<String, String>>()

        // Static listener reference (set by Downloader)
        @Volatile
        var downloadListener: ResumableDownloader.DownloadListener? = null

        // Notification grouping configuration (disabled by default)
        @Volatile
        private var groupingEnabled = false

        // Whether to show notifications at all (disabled by default)
        @Volatile
        private var showNotificationsEnabled = false

        // Notification update interval in milliseconds (synced with progressInterval)
        @Volatile
        private var notificationUpdateInterval: Long = 500 // Default 500ms

        // Notification texts configuration
        private val notificationTexts = mutableMapOf(
            "downloadTitle" to "Download",
            "downloadStarting" to "Starting download...",
            "downloadProgress" to "Downloading... {progress}%",
            "downloadPaused" to "Paused",
            "downloadFinished" to "Download complete",
            "groupTitle" to "Downloads",
            "groupText" to "{count} download(s) in progress"
        )

        /**
         * Check if notifications are enabled globally.
         */
        fun isNotificationsEnabled(): Boolean = showNotificationsEnabled

        /**
         * Set notification update interval (should match progressInterval).
         */
        fun setNotificationUpdateInterval(interval: Long) {
            notificationUpdateInterval = interval
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Notification update interval set to: $interval ms")
        }

        /**
         * Configure notification grouping and texts.
         * @param enabled Whether to enable notification grouping
         * @param show Whether to show notifications at all
         * @param texts Map of notification text keys to values
         */
        fun setNotificationGroupingConfig(enabled: Boolean, showNotificationsEnabled: Boolean, texts: Map<String, String>) {
            groupingEnabled = enabled
            this.showNotificationsEnabled = showNotificationsEnabled
            texts.forEach { (key, value) ->
                notificationTexts[key] = value
            }
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Notification config updated: grouping=$enabled, showNotificationsEnabled=$showNotificationsEnabled, texts=$texts")
        }

        private fun getNotificationText(key: String, vararg replacements: Pair<String, Any>): String {
            var text = notificationTexts[key] ?: ""
            for ((placeholder, value) in replacements) {
                text = text.replace("{$placeholder}", value.toString())
            }
            return text
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                RNBackgroundDownloaderModuleImpl.logW(TAG, "UIDT requires Android 14+, falling back to foreground service")
                return false
            }

            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

            // Create unique job ID from config ID
            val jobId = JOB_ID_BASE + (configId.hashCode() and 0x7FFFFFFF) % 10000

            // Store headers for later retrieval (PersistableBundle can't store Map<String, String>)
            pendingHeaders[configId] = headers

            // Create extras bundle
            val extras = PersistableBundle().apply {
                putString(KEY_DOWNLOAD_ID, configId)
                putString(KEY_URL, url)
                putString(KEY_DESTINATION, destination)
                putLong(KEY_START_BYTE, startByte)
                putLong(KEY_TOTAL_BYTES, totalBytes)
                putString(KEY_METADATA, metadata)
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
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Scheduled UIDT job for $configId (jobId=$jobId)")
            } else {
                RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to schedule UIDT job for $configId")
                pendingHeaders.remove(configId)
            }

            return success
        }

        /**
         * Cancel a scheduled UIDT job.
         */
        fun cancelJob(context: Context, configId: String) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

            RNBackgroundDownloaderModuleImpl.logD(TAG, "cancelJob called for $configId")

            // Get job state before removing (for notification cleanup)
            val jobState = activeJobs[configId]
            RNBackgroundDownloaderModuleImpl.logD(TAG, "cancelJob: jobState=$jobState")

            if (jobState != null) {
                // First cancel the download in the ResumableDownloader (stops the actual HTTP download)
                jobState.resumableDownloader.cancel(configId)
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelled ResumableDownloader for $configId")

                // Use setNotification with REMOVE policy to properly dismiss the notification
                val service = serviceInstance
                if (service != null) {
                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Using setNotification with REMOVE policy for $configId")
                    // Create an empty notification and set REMOVE policy to dismiss it
                    val emptyNotification = NotificationCompat.Builder(context, UIDT_NOTIFICATION_CHANNEL_SILENT_ID)
                        .setContentTitle("")
                        .setContentText("")
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .build()
                    service.setNotification(
                        jobState.params,
                        jobState.notificationId,
                        emptyNotification,
                        JobService.JOB_END_NOTIFICATION_POLICY_REMOVE
                    )
                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Calling jobFinished for $configId")
                    service.jobFinished(jobState.params, false)
                }

                // Also cancel via NotificationManager as a fallback
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(jobState.notificationId)
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelled notification ${jobState.notificationId} for $configId")
            }

            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobId = JOB_ID_BASE + (configId.hashCode() and 0x7FFFFFFF) % 10000

            jobScheduler.cancel(jobId)
            pendingHeaders.remove(configId)
            activeJobs.remove(configId)

            // Update summary notification if grouping was enabled
            if (jobState != null && groupingEnabled && jobState.groupId.isNotEmpty()) {
                updateSummaryNotificationStatic(context, jobState.groupId, jobState.groupName)
            }

            RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelled UIDT job for $configId")
        }

        /**
         * Check if a download is active as a UIDT job.
         */
        fun isActiveJob(configId: String): Boolean {
            return activeJobs.containsKey(configId)
        }

        /**
         * Check if a download is paused in a UIDT job.
         */
        fun isPausedJob(configId: String): Boolean {
            val jobState = activeJobs[configId]
            return jobState?.resumableDownloader?.isPaused(configId) ?: false
        }

        /**
         * Get the download state for a UIDT job.
         * Returns the ResumableDownloader.DownloadState if the job is active, null otherwise.
         */
        fun getJobDownloadState(configId: String): ResumableDownloader.DownloadState? {
            val jobState = activeJobs[configId] ?: return null
            return jobState.resumableDownloader.getState(configId)
        }

        /**
         * Pause an active UIDT download.
         * This will pause the download, cancel the UIDT job, but keep a detached notification showing paused state.
         * The download state is persisted and can be resumed later by creating a new UIDT job.
         */
        fun pauseJob(context: Context, configId: String): Boolean {
            RNBackgroundDownloaderModuleImpl.logD(TAG, "pauseJob called: configId=$configId, showNotificationsEnabled=$showNotificationsEnabled")
            val jobState = activeJobs[configId] ?: return false

            // First pause the actual download (this saves state to disk)
            val result = jobState.resumableDownloader.pause(configId)
            RNBackgroundDownloaderModuleImpl.logD(TAG, "pauseJob: pause result=$result")

            if (result) {
                // Get progress before removing job state
                val downloadState = jobState.resumableDownloader.getState(configId)
                val bytesDownloaded = downloadState?.bytesDownloaded?.get() ?: 0L
                val bytesTotal = downloadState?.bytesTotal ?: -1L
                val progress = if (bytesTotal > 0) {
                    ((bytesDownloaded.toDouble() / bytesTotal) * 100).toInt()
                } else {
                    0
                }
                val groupName = jobState.groupName
                val notificationId = getNotificationIdForConfig(configId)

                // Call jobFinished to properly end the UIDT job
                val service = serviceInstance
                if (service != null) {
                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Calling jobFinished for paused job $configId")
                    service.jobFinished(jobState.params, false)
                }

                // Cancel the UIDT job from JobScheduler
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val jobId = JOB_ID_BASE + (configId.hashCode() and 0x7FFFFFFF) % 10000
                jobScheduler.cancel(jobId)
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelled UIDT job $jobId for paused download $configId")

                // Remove from activeJobs - will create new job on resume
                activeJobs.remove(configId)

                // Show detached paused notification (not tied to UIDT job)
                if (showNotificationsEnabled) {
                    showPausedNotification(context, configId, notificationId, progress, groupName)
                }
            }
            return result
        }

        /**
         * Show a detached notification for paused downloads.
         * This notification is not tied to a UIDT job and will persist until cancelled.
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun showPausedNotification(context: Context, configId: String, notificationId: Int, progress: Int, groupName: String) {
            val channelId = UIDT_NOTIFICATION_CHANNEL_ID
            val title = if (groupingEnabled && groupName.isNotEmpty()) {
                groupName
            } else {
                getNotificationText("downloadTitle")
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(getNotificationText("downloadPaused"))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false) // Not ongoing - user can swipe to dismiss
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(100, progress, false)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(notificationId, notification)
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Showed paused notification $notificationId for $configId with progress $progress%")
        }

        /**
         * Update notification to show paused state.
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun updatePausedNotification(context: Context, jobState: JobState) {
            RNBackgroundDownloaderModuleImpl.logD(TAG, "updatePausedNotification called for notificationId=${jobState.notificationId}")
            val channelId = UIDT_NOTIFICATION_CHANNEL_ID
            val title = if (groupingEnabled && jobState.groupName.isNotEmpty()) {
                jobState.groupName
            } else {
                getNotificationText("downloadTitle")
            }

            // Get current progress for the paused state
            val configId = jobState.params.extras.getString(KEY_DOWNLOAD_ID) ?: ""
            val downloadState = jobState.resumableDownloader.getState(configId)
            val bytesDownloaded = downloadState?.bytesDownloaded?.get() ?: 0L
            val bytesTotal = downloadState?.bytesTotal ?: -1L
            val progress = if (bytesTotal > 0) {
                ((bytesDownloaded.toDouble() / bytesTotal) * 100).toInt()
            } else {
                0
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(getNotificationText("downloadPaused"))
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setProgress(100, progress, false) // Show paused progress
                .build()

            // Use setNotification for UIDT jobs instead of notificationManager.notify()
            val service = serviceInstance
            if (service != null) {
                service.setNotification(
                    jobState.params,
                    jobState.notificationId,
                    notification,
                    JobService.JOB_END_NOTIFICATION_POLICY_DETACH
                )
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Notification updated via setNotification")
            } else {
                // Fallback to regular notify if service not available
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(jobState.notificationId, notification)
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Notification updated via notificationManager (fallback)")
            }
        }

        /**
         * Resume a paused UIDT download.
         */
        fun resumeJob(context: Context, configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
            val jobState = activeJobs[configId] ?: return false
            downloadListener = listener

            // Reset notification timing to allow immediate updates after resume
            jobState.lastNotificationUpdateTime = 0L

            // Update notification to show resuming/downloading state
            if (showNotificationsEnabled) {
                updateResumedNotification(context, jobState)
            }

            return jobState.resumableDownloader.resume(configId, listener)
        }

        /**
         * Update notification to show resumed/downloading state.
         */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private fun updateResumedNotification(context: Context, jobState: JobState) {
            RNBackgroundDownloaderModuleImpl.logD(TAG, "updateResumedNotification called for notificationId=${jobState.notificationId}")
            val channelId = UIDT_NOTIFICATION_CHANNEL_ID
            val title = if (groupingEnabled && jobState.groupName.isNotEmpty()) {
                jobState.groupName
            } else {
                getNotificationText("downloadTitle")
            }

            // Get current progress from download state
            val configId = jobState.params.extras.getString(KEY_DOWNLOAD_ID) ?: ""
            val downloadState = jobState.resumableDownloader.getState(configId)
            val bytesDownloaded = downloadState?.bytesDownloaded?.get() ?: 0L
            val bytesTotal = downloadState?.bytesTotal ?: -1L
            val progress = if (bytesTotal > 0) {
                ((bytesDownloaded.toDouble() / bytesTotal) * 100).toInt()
            } else {
                0
            }

            // Update tracking state so next onProgress knows current state
            jobState.lastNotifiedProgress = progress

            val builder = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setContentText(getNotificationText("downloadProgress", "progress" to progress))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true) // Don't alert on every update
                .setShowWhen(false) // Hide timestamp for cleaner UI
                .setProgress(100, progress, bytesTotal <= 0)

            val notification = builder.build()

            val service = serviceInstance
            if (service != null) {
                service.setNotification(
                    jobState.params,
                    jobState.notificationId,
                    notification,
                    JobService.JOB_END_NOTIFICATION_POLICY_DETACH
                )
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Resumed notification updated via setNotification")
            } else {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(jobState.notificationId, notification)
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Resumed notification updated via notificationManager (fallback)")
            }
        }

        // Track active jobs for pause/resume
        private val activeJobs = ConcurrentHashMap<String, JobState>()

        // Store service instance reference for notification updates
        @Volatile
        private var serviceInstance: UIDTDownloadJobService? = null

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
         * Static version of updateSummaryNotification for use in companion object.
         */
        private fun updateSummaryNotificationStatic(context: Context, groupId: String, groupName: String) {
            // Only show summary when grouping is enabled and notifications are shown
            if (!groupingEnabled || groupId.isEmpty() || !showNotificationsEnabled) return

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Count active downloads for this specific group
            val groupDownloads = activeJobs.values.count { it.groupId == groupId }
            val summaryNotificationId = SUMMARY_NOTIFICATION_ID + groupId.hashCode()

            if (groupDownloads == 0) {
                // Remove summary for this group when no active downloads
                notificationManager.cancel(summaryNotificationId)
                return
            }

            val groupKey = "${NOTIFICATION_GROUP_KEY}_$groupId"
            val title = groupName.ifEmpty { getNotificationText("groupTitle") }
            val text = getNotificationText("groupText", "count" to groupDownloads)

            val summaryNotification = NotificationCompat.Builder(context, UIDT_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setGroup(groupKey)
                .setGroupSummary(true)
                .setOngoing(true)
                .build()

            notificationManager.notify(summaryNotificationId, summaryNotification)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
        RNBackgroundDownloaderModuleImpl.logD(TAG, "UIDTDownloadJobService created")
        createNotificationChannel()
    }

    override fun onDestroy() {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "UIDTDownloadJobService destroyed")
        serviceInstance = null
        releaseWakeLock()
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onStartJob(params: JobParameters): Boolean {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "onStartJob called")

        val extras = params.extras
        val configId = extras.getString(KEY_DOWNLOAD_ID) ?: run {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "No download ID in job extras")
            return false
        }
        val url = extras.getString(KEY_URL) ?: return false
        val destination = extras.getString(KEY_DESTINATION) ?: return false
        val startByte = extras.getLong(KEY_START_BYTE, 0)
        val totalBytes = extras.getLong(KEY_TOTAL_BYTES, -1)

        // Extract group info from metadata (for notification grouping)
        val metadataJson = extras.getString(KEY_METADATA) ?: "{}"
        var groupId = ""
        var groupName = ""
        try {
            val json = JSONObject(metadataJson)
            groupId = json.optString("groupId", "")
            groupName = json.optString("groupName", "")
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to parse metadata: ${e.message}")
        }

        // Retrieve headers
        val headers = pendingHeaders.remove(configId) ?: emptyMap()

        // Create notification for UIDT job (required)
        // Use stable notification ID based on configId so it's reused across app restarts
        val notificationId = getNotificationIdForConfig(configId)
        val notification = createDownloadNotification(configId, groupId, groupName)

        // Set the notification for this job (required for UIDT)
        setNotification(
            params,
            notificationId,
            notification,
            JOB_END_NOTIFICATION_POLICY_DETACH
        )

        // Acquire wake lock
        acquireWakeLock()

        // Create ResumableDownloader for this job
        val resumableDownloader = ResumableDownloader()

        // Store job state
        activeJobs[configId] = JobState(params, resumableDownloader, notificationId, groupId, groupName)

        // Create listener that will notify completion
        val jobListener = createJobListener(configId, params)

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

        RNBackgroundDownloaderModuleImpl.logD(TAG, "Started UIDT download: $configId from byte $startByte")

        // Return true - work is being done asynchronously
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val extras = params.extras
        val configId = extras.getString(KEY_DOWNLOAD_ID)

        val stopReason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.stopReason
        } else {
            -1
        }

        RNBackgroundDownloaderModuleImpl.logD(TAG, "onStopJob called for $configId, reason: $stopReason")

        if (configId != null) {
            val jobState = activeJobs[configId]
            if (jobState != null) {
                // Pause the download - it can be resumed later
                jobState.resumableDownloader.pause(configId)

                // Save state for potential resume
                val state = jobState.resumableDownloader.getState(configId)
                if (state != null) {
                    // Store resume info
                    pendingHeaders[configId] = state.headers
                }
            }
            activeJobs.remove(configId)
        }

        releaseWakeLock()

        // Return true to reschedule the job if it was stopped by system
        return true
    }

    @Suppress("UNUSED_PARAMETER")
    private fun createJobListener(configId: String, params: JobParameters): ResumableDownloader.DownloadListener {
        return object : ResumableDownloader.DownloadListener {
            override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) {
                RNBackgroundDownloaderModuleImpl.logD(TAG, "UIDT download begin: $id, expectedBytes: $expectedBytes")

                // Update notification with size info
                updateNotification(id, "Downloading...", 0, expectedBytes)

                // Notify external listener
                downloadListener?.onBegin(id, expectedBytes, headers)
            }

            override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) {
                // Skip notification update if download is paused
                val jobState = activeJobs[id]
                if (jobState != null) {
                    // Check if download is paused - don't update notification with progress
                    val isPaused = jobState.resumableDownloader.isPaused(id)
                    if (isPaused) {
                        // Still notify external listener for state tracking, but don't update notification
                        downloadListener?.onProgress(id, bytesDownloaded, bytesTotal)
                        return
                    }

                    if (bytesTotal > 0) {
                        val progress = ((bytesDownloaded.toDouble() / bytesTotal) * 100).toInt()
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastUpdate = currentTime - jobState.lastNotificationUpdateTime

                        // Update notification when:
                        // 1. Progress changed AND enough time has passed (synced with progressInterval)
                        // 2. OR this is the first update (lastNotificationUpdateTime == 0)
                        // 3. OR download is complete (progress == 100)
                        val shouldUpdate = progress != jobState.lastNotifiedProgress &&
                            (timeSinceLastUpdate >= notificationUpdateInterval ||
                             jobState.lastNotificationUpdateTime == 0L ||
                             progress == 100)

                        if (shouldUpdate) {
                            jobState.lastNotifiedProgress = progress
                            jobState.lastNotificationUpdateTime = currentTime
                            updateNotification(id, "Downloading... $progress%", bytesDownloaded, bytesTotal)
                        }
                    }
                }

                // Notify external listener (always - JS handles its own throttling)
                downloadListener?.onProgress(id, bytesDownloaded, bytesTotal)
            }

            override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
                RNBackgroundDownloaderModuleImpl.logD(TAG, "UIDT download complete: $id")

                // Save group info and notification id before removing from activeJobs
                val jobState = activeJobs[id]
                val groupId = jobState?.groupId ?: ""
                val groupName = jobState?.groupName ?: ""
                val notificationId = jobState?.notificationId

                // Cancel individual notification
                if (notificationId != null) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(notificationId)
                }

                // Clean up
                activeJobs.remove(id)
                releaseWakeLock()

                // Update summary notification for this group (only if grouping enabled)
                if (groupingEnabled && groupId.isNotEmpty()) {
                    updateSummaryNotification(groupId, groupName)
                }

                // Signal job completion
                jobFinished(params, false)

                // Notify external listener
                downloadListener?.onComplete(id, location, bytesDownloaded, bytesTotal)
            }

            override fun onError(id: String, error: String, errorCode: Int) {
                RNBackgroundDownloaderModuleImpl.logE(TAG, "UIDT download error: $id - $error ($errorCode)")

                // Save group info and notification id before removing from activeJobs
                val jobState = activeJobs[id]
                val groupId = jobState?.groupId ?: ""
                val groupName = jobState?.groupName ?: ""
                val notificationId = jobState?.notificationId

                // Cancel individual notification
                if (notificationId != null) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(notificationId)
                }

                // Clean up
                activeJobs.remove(id)
                releaseWakeLock()

                // Update summary notification for this group (only if grouping enabled)
                if (groupingEnabled && groupId.isNotEmpty()) {
                    updateSummaryNotification(groupId, groupName)
                }

                // Signal job completion with no reschedule
                jobFinished(params, false)

                // Notify external listener
                downloadListener?.onError(id, error, errorCode)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Create channel for visible notifications (IMPORTANCE_LOW)
            val visibleChannel = NotificationChannel(
                UIDT_NOTIFICATION_CHANNEL_ID,
                "Background Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for background downloads"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(visibleChannel)

            // Create channel for silent/hidden notifications (IMPORTANCE_MIN)
            val silentChannel = NotificationChannel(
                UIDT_NOTIFICATION_CHANNEL_SILENT_ID,
                "Background Downloads (Silent)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent notifications for background downloads"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(silentChannel)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun createDownloadNotification(configId: String, groupId: String = "", groupName: String = ""): Notification {
        // Use silent channel when notifications are disabled
        val channelId = if (showNotificationsEnabled) UIDT_NOTIFICATION_CHANNEL_ID else UIDT_NOTIFICATION_CHANNEL_SILENT_ID

        // When notifications are disabled, create minimal silent notification
        // (UIDT jobs require a notification, but we can minimize its visibility)
        if (!showNotificationsEnabled) {
            return NotificationCompat.Builder(this, channelId)
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }

        val title = if (groupingEnabled && groupName.isNotEmpty()) {
            groupName
        } else {
            getNotificationText("downloadTitle")
        }
        val startingText = getNotificationText("downloadStarting")

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(startingText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Don't alert on every update
            .setShowWhen(false) // Hide timestamp for cleaner UI
            .setProgress(0, 0, true)

        // Only apply grouping when enabled and groupId is provided
        if (groupingEnabled && groupId.isNotEmpty()) {
            val groupKey = "${NOTIFICATION_GROUP_KEY}_$groupId"
            builder.setGroup(groupKey)
            // Update summary notification for this group
            updateSummaryNotification(groupId, groupName)
        }

        return builder.build()
    }

    private fun updateSummaryNotification(groupId: String, groupName: String) {
        // Only show summary when grouping is enabled and notifications are shown
        if (!groupingEnabled || groupId.isEmpty() || !showNotificationsEnabled) return

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Count active downloads for this specific group
        val groupDownloads = activeJobs.values.count { it.groupId == groupId }
        val summaryNotificationId = SUMMARY_NOTIFICATION_ID + groupId.hashCode()

        if (groupDownloads == 0) {
            // Remove summary for this group when no active downloads
            notificationManager.cancel(summaryNotificationId)
            return
        }

        val groupKey = "${NOTIFICATION_GROUP_KEY}_$groupId"
        val title = groupName.ifEmpty { getNotificationText("groupTitle") }
        val text = getNotificationText("groupText", "count" to groupDownloads)

        val summaryNotification = NotificationCompat.Builder(this, UIDT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Don't alert on every update
            .setShowWhen(false) // Hide timestamp for cleaner UI
            .build()

        notificationManager.notify(summaryNotificationId, summaryNotification)
    }

    private fun updateNotification(configId: String, text: String, bytesDownloaded: Long, bytesTotal: Long) {
        // Skip notification updates when notifications are disabled
        if (!showNotificationsEnabled) return

        val jobState = activeJobs[configId] ?: return

        val progress = if (bytesTotal > 0) {
            ((bytesDownloaded.toDouble() / bytesTotal) * 100).toInt()
        } else {
            0
        }

        val title = if (groupingEnabled && jobState.groupName.isNotEmpty()) {
            jobState.groupName
        } else {
            getNotificationText("downloadTitle")
        }

        val progressText = getNotificationText("downloadProgress", "progress" to progress)

        val builder = NotificationCompat.Builder(this, UIDT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true) // Don't alert on every update
            .setShowWhen(false) // Hide timestamp for cleaner UI
            .setProgress(100, progress, bytesTotal <= 0)

        // Only apply grouping when enabled and groupId is provided
        if (groupingEnabled && jobState.groupId.isNotEmpty()) {
            val groupKey = "${NOTIFICATION_GROUP_KEY}_${jobState.groupId}"
            builder.setGroup(groupKey)
            // Update summary notification for this group
            updateSummaryNotification(jobState.groupId, jobState.groupName)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(jobState.notificationId, builder.build())
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
            RNBackgroundDownloaderModuleImpl.logD(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                RNBackgroundDownloaderModuleImpl.logD(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}

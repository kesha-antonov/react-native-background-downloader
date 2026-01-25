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

        // Notification channel for UIDT jobs
        private const val UIDT_NOTIFICATION_CHANNEL_ID = "uidt_download_channel"

        // Notification group for grouping all download notifications together
        private const val NOTIFICATION_GROUP_KEY = "com.eko.DOWNLOAD_GROUP"

        // Summary notification ID (used to group all download notifications)
        private const val SUMMARY_NOTIFICATION_ID = 19999

        // Atomic counter for notification IDs
        private val notificationIdCounter = AtomicInteger(20000)

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

        // Notification texts configuration
        private val notificationTexts = mutableMapOf(
            "downloadTitle" to "Download",
            "downloadStarting" to "Starting download...",
            "downloadProgress" to "Downloading... {progress}%",
            "downloadFinished" to "Download complete",
            "groupTitle" to "Downloads",
            "groupText" to "{count} download(s) in progress"
        )

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

            // First cancel the download in the ResumableDownloader (stops the actual HTTP download)
            val jobState = activeJobs[configId]
            if (jobState != null) {
                jobState.resumableDownloader.cancel(configId)
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelled ResumableDownloader for $configId")
            }

            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobId = JOB_ID_BASE + (configId.hashCode() and 0x7FFFFFFF) % 10000

            jobScheduler.cancel(jobId)
            pendingHeaders.remove(configId)
            activeJobs.remove(configId)

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
         */
        fun pauseJob(configId: String): Boolean {
            val jobState = activeJobs[configId] ?: return false
            return jobState.resumableDownloader.pause(configId)
        }

        /**
         * Resume a paused UIDT download.
         */
        fun resumeJob(configId: String, listener: ResumableDownloader.DownloadListener): Boolean {
            val jobState = activeJobs[configId] ?: return false
            downloadListener = listener
            return jobState.resumableDownloader.resume(configId, listener)
        }

        // Track active jobs for pause/resume
        private val activeJobs = ConcurrentHashMap<String, JobState>()

        data class JobState(
            val params: JobParameters,
            val resumableDownloader: ResumableDownloader,
            var notificationId: Int,
            val groupId: String = "",
            val groupName: String = ""
        )
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        RNBackgroundDownloaderModuleImpl.logD(TAG, "UIDTDownloadJobService created")
        createNotificationChannel()
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
        val notificationId = notificationIdCounter.incrementAndGet()
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
                // Update notification periodically (throttle to avoid too many updates)
                val jobState = activeJobs[id]
                if (jobState != null && bytesTotal > 0) {
                    val progress = ((bytesDownloaded.toDouble() / bytesTotal) * 100).toInt()
                    if (progress % 5 == 0) { // Update every 5%
                        updateNotification(id, "Downloading... $progress%", bytesDownloaded, bytesTotal)
                    }
                }

                // Notify external listener
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
            val name = "Background Downloads"
            val descriptionText = "Shows progress for background downloads"
            val importance = NotificationManager.IMPORTANCE_MIN
            val channel = NotificationChannel(UIDT_NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun createDownloadNotification(configId: String, groupId: String = "", groupName: String = ""): Notification {
        // When notifications are disabled, create minimal silent notification
        // (UIDT jobs require a notification, but we can minimize its visibility)
        if (!showNotificationsEnabled) {
            return NotificationCompat.Builder(this, UIDT_NOTIFICATION_CHANNEL_ID)
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

        val builder = NotificationCompat.Builder(this, UIDT_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(startingText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
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

    override fun onDestroy() {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "UIDTDownloadJobService destroyed")
        releaseWakeLock()
        super.onDestroy()
    }
}

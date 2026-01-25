package com.eko.uidt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobService
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.eko.RNBackgroundDownloaderModuleImpl
import com.eko.UIDTDownloadJobService
import com.eko.utils.ProgressUtils

/**
 * Manages all notification-related operations for UIDT downloads.
 * Handles creation, updating, and removal of download notifications.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object UIDTNotificationManager {

    private val config: NotificationConfig
        get() = UIDTJobRegistry.notificationConfig

    /**
     * Generate a stable notification ID for a configId.
     * This ensures the same download always uses the same notification ID,
     * even across app restarts.
     */
    fun getNotificationIdForConfig(configId: String): Int {
        return UIDTConstants.NOTIFICATION_ID_BASE + (configId.hashCode() and 0x7FFFFFFF) % 100000
    }

    /**
     * Create notification channels for UIDT jobs.
     * Must be called before showing any notifications (typically in service onCreate).
     */
    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Create channel for visible notifications (IMPORTANCE_LOW)
            val visibleChannel = NotificationChannel(
                UIDTConstants.NOTIFICATION_CHANNEL_ID,
                "Background Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress for background downloads"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(visibleChannel)

            // Create channel for silent/hidden notifications (IMPORTANCE_MIN)
            val silentChannel = NotificationChannel(
                UIDTConstants.NOTIFICATION_CHANNEL_SILENT_ID,
                "Background Downloads (Silent)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Silent notifications for background downloads"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(silentChannel)
        }
    }

    /**
     * Create the initial notification for a download.
     */
    fun createDownloadNotification(
        context: Context,
        configId: String,
        groupId: String = "",
        groupName: String = ""
    ): Notification {
        val channelId = if (config.showNotificationsEnabled) {
            UIDTConstants.NOTIFICATION_CHANNEL_ID
        } else {
            UIDTConstants.NOTIFICATION_CHANNEL_SILENT_ID
        }

        // When notifications are disabled, create minimal silent notification
        // (UIDT jobs require a notification, but we can minimize its visibility)
        if (!config.showNotificationsEnabled) {
            return NotificationCompat.Builder(context, channelId)
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }

        val title = if (config.groupingEnabled && groupName.isNotEmpty()) {
            groupName
        } else {
            config.getText("downloadTitle")
        }
        val startingText = config.getText("downloadStarting")

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(startingText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(0, 0, true)

        // Apply grouping when enabled and groupId is provided
        if (config.groupingEnabled && groupId.isNotEmpty()) {
            val groupKey = "${UIDTConstants.NOTIFICATION_GROUP_KEY}_$groupId"
            builder.setGroup(groupKey)
        }

        return builder.build()
    }

    /**
     * Update notification with download progress.
     */
    fun updateProgressNotification(
        context: Context,
        jobState: JobState,
        bytesDownloaded: Long,
        bytesTotal: Long
    ) {
        if (!config.showNotificationsEnabled) return

        val progress = ProgressUtils.calculateProgress(bytesDownloaded, bytesTotal)

        val title = if (config.groupingEnabled && jobState.groupName.isNotEmpty()) {
            jobState.groupName
        } else {
            config.getText("downloadTitle")
        }

        val progressText = config.getText("downloadProgress", "progress" to progress)

        val builder = NotificationCompat.Builder(context, UIDTConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(progressText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, progress, bytesTotal <= 0)

        // Apply grouping when enabled
        if (config.groupingEnabled && jobState.groupId.isNotEmpty()) {
            val groupKey = "${UIDTConstants.NOTIFICATION_GROUP_KEY}_${jobState.groupId}"
            builder.setGroup(groupKey)
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(jobState.notificationId, builder.build())
    }

    /**
     * Show a detached notification for paused downloads.
     * This notification is not tied to a UIDT job and will persist until cancelled.
     */
    fun showPausedNotification(
        context: Context,
        configId: String,
        notificationId: Int,
        progress: Int,
        groupName: String
    ) {
        val title = if (config.groupingEnabled && groupName.isNotEmpty()) {
            groupName
        } else {
            config.getText("downloadTitle")
        }

        val notification = NotificationCompat.Builder(context, UIDTConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(config.getText("downloadPaused"))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false) // Not ongoing - user can swipe to dismiss
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, progress, false)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Showed paused notification $notificationId for $configId with progress $progress%")
    }

    /**
     * Update notification to show resumed/downloading state.
     */
    fun updateResumedNotification(context: Context, jobState: JobState) {
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "updateResumedNotification called for notificationId=${jobState.notificationId}")

        val configId = jobState.params.extras.getString(UIDTConstants.KEY_DOWNLOAD_ID) ?: ""
        val downloadState = jobState.resumableDownloader.getState(configId)
        val bytesDownloaded = downloadState?.bytesDownloaded?.get() ?: 0L
        val bytesTotal = downloadState?.bytesTotal ?: -1L
        val progress = ProgressUtils.calculateProgress(bytesDownloaded, bytesTotal)

        // Update tracking state so next onProgress knows current state
        jobState.lastNotifiedProgress = progress

        val title = if (config.groupingEnabled && jobState.groupName.isNotEmpty()) {
            jobState.groupName
        } else {
            config.getText("downloadTitle")
        }

        val builder = NotificationCompat.Builder(context, UIDTConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(config.getText("downloadProgress", "progress" to progress))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, progress, bytesTotal <= 0)

        val notification = builder.build()

        val service = UIDTJobRegistry.serviceInstance
        if (service != null) {
            service.setNotification(
                jobState.params,
                jobState.notificationId,
                notification,
                JobService.JOB_END_NOTIFICATION_POLICY_DETACH
            )
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Resumed notification updated via setNotification")
        } else {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(jobState.notificationId, notification)
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Resumed notification updated via notificationManager (fallback)")
        }
    }

    /**
     * Update the summary notification for a download group.
     */
    fun updateSummaryNotification(context: Context, groupId: String, groupName: String) {
        if (!config.groupingEnabled || groupId.isEmpty() || !config.showNotificationsEnabled) return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Count active downloads for this specific group
        val groupDownloads = UIDTJobRegistry.activeJobs.values.count { it.groupId == groupId }
        val summaryNotificationId = UIDTConstants.SUMMARY_NOTIFICATION_ID + groupId.hashCode()

        if (groupDownloads == 0) {
            // Remove summary for this group when no active downloads
            notificationManager.cancel(summaryNotificationId)
            return
        }

        val groupKey = "${UIDTConstants.NOTIFICATION_GROUP_KEY}_$groupId"
        val title = groupName.ifEmpty { config.getText("groupTitle") }
        val text = config.getText("groupText", "count" to groupDownloads)

        val summaryNotification = NotificationCompat.Builder(context, UIDTConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

        notificationManager.notify(summaryNotificationId, summaryNotification)
    }

    /**
     * Cancel notification for a specific download.
     */
    fun cancelNotification(context: Context, configId: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = getNotificationIdForConfig(configId)
        notificationManager.cancel(notificationId)
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled notification $notificationId for $configId")
    }

    /**
     * Cancel notification by ID.
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }

    /**
     * Create an empty notification for removal purposes.
     */
    fun createEmptyNotification(context: Context): Notification {
        return NotificationCompat.Builder(context, UIDTConstants.NOTIFICATION_CHANNEL_SILENT_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .build()
    }
}

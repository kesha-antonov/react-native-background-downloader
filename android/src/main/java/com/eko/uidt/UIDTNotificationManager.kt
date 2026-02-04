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

            // Create ultra-silent channel for summaryOnly mode (IMPORTANCE_MIN, no alert)
            val ultraSilentChannel = NotificationChannel(
                UIDTConstants.NOTIFICATION_CHANNEL_ULTRA_SILENT_ID,
                "Background Downloads (Grouped)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Ultra-silent notifications for grouped background downloads"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(ultraSilentChannel)
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
        val isSummaryOnlyMode = config.mode == NotificationGroupingMode.SUMMARY_ONLY

        // Determine channel based on mode and settings
        val channelId = when {
            !config.showNotificationsEnabled -> UIDTConstants.NOTIFICATION_CHANNEL_SILENT_ID
            isSummaryOnlyMode && config.groupingEnabled && groupId.isNotEmpty() -> UIDTConstants.NOTIFICATION_CHANNEL_ULTRA_SILENT_ID
            else -> UIDTConstants.NOTIFICATION_CHANNEL_ID
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

        // In summaryOnly mode with grouping, create minimal individual notifications
        if (isSummaryOnlyMode && config.groupingEnabled && groupId.isNotEmpty()) {
            val groupKey = "${UIDTConstants.NOTIFICATION_GROUP_KEY}_$groupId"
            return NotificationCompat.Builder(context, channelId)
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setSilent(true)
                .setGroup(groupKey)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
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

        val isSummaryOnlyMode = config.mode == NotificationGroupingMode.SUMMARY_ONLY

        // In summaryOnly mode, skip updating individual notifications - only update summary
        if (isSummaryOnlyMode && config.groupingEnabled && jobState.groupId.isNotEmpty()) {
            // Update group progress tracking
            UIDTJobRegistry.updateGroupProgress(jobState.groupId, "", bytesDownloaded, bytesTotal)
            // Update the summary notification with aggregate progress
            updateSummaryNotificationWithProgress(context, jobState.groupId, jobState.groupName)
            return
        }

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
     * Automatically chooses between regular and progress-based summary
     * depending on the notification mode.
     */
    fun updateSummaryNotificationForGroup(context: Context, groupId: String, groupName: String) {
        if (!config.groupingEnabled || groupId.isEmpty() || !config.showNotificationsEnabled) return
        // Skip if group is finalized (all downloads complete) to prevent race conditions
        if (UIDTJobRegistry.isGroupFinalized(groupId)) {
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Skipping summary update for finalized group: $groupId")
            return
        }

        if (config.mode == NotificationGroupingMode.SUMMARY_ONLY) {
            updateSummaryNotificationWithProgress(context, groupId, groupName)
        } else {
            updateSummaryNotification(context, groupId, groupName)
        }
    }

    /**
     * Update the summary notification for a download group.
     */
    fun updateSummaryNotification(context: Context, groupId: String, groupName: String) {
        if (!config.groupingEnabled || groupId.isEmpty() || !config.showNotificationsEnabled) return
        // Skip if group is finalized (all downloads complete) to prevent race conditions
        if (UIDTJobRegistry.isGroupFinalized(groupId)) {
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Skipping summary update for finalized group: $groupId")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Count active downloads for this specific group
        val groupDownloads = UIDTJobRegistry.activeJobs.values.count { it.groupId == groupId }
        val summaryNotificationId = UIDTConstants.SUMMARY_NOTIFICATION_ID + groupId.hashCode()

        if (groupDownloads == 0) {
            // Remove summary for this group when no active downloads
            notificationManager.cancel(summaryNotificationId)
            UIDTJobRegistry.clearGroupProgress(groupId)
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
     * Update the summary notification with aggregate progress (for summaryOnly mode).
     */
    fun updateSummaryNotificationWithProgress(context: Context, groupId: String, groupName: String) {
        if (!config.groupingEnabled || groupId.isEmpty() || !config.showNotificationsEnabled) return
        if (config.mode != NotificationGroupingMode.SUMMARY_ONLY) return
        // Skip if group is finalized (all downloads complete) to prevent race conditions
        if (UIDTJobRegistry.isGroupFinalized(groupId)) {
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Skipping summary progress update for finalized group: $groupId")
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Count active downloads for this specific group
        val groupDownloads = UIDTJobRegistry.activeJobs.values.count { it.groupId == groupId }
        val summaryNotificationId = UIDTConstants.SUMMARY_NOTIFICATION_ID + groupId.hashCode()

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "updateSummaryNotificationWithProgress: groupId=$groupId, groupDownloads=$groupDownloads, summaryNotificationId=$summaryNotificationId")

        if (groupDownloads == 0) {
            // Remove summary for this group when no active downloads
            RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelling summary notification $summaryNotificationId for empty group $groupId")
            notificationManager.cancel(summaryNotificationId)
            UIDTJobRegistry.clearGroupProgress(groupId)
            return
        }

        val groupProgress = UIDTJobRegistry.groupProgress[groupId]
        val groupKey = "${UIDTConstants.NOTIFICATION_GROUP_KEY}_$groupId"
        val title = groupName.ifEmpty { config.getText("groupTitle") }

        // Build progress text based on available info
        val text = if (groupProgress != null && groupProgress.totalBytes > 0) {
            "${groupProgress.progressPercent}% - ${groupDownloads} file${if (groupDownloads != 1) "s" else ""}"
        } else {
            config.getText("groupText", "count" to groupDownloads)
        }

        val progress = groupProgress?.progressPercent ?: 0
        val indeterminate = groupProgress == null || groupProgress.totalBytes <= 0

        val summaryNotification = NotificationCompat.Builder(context, UIDTConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setProgress(100, progress, indeterminate)
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
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled notification by ID: $notificationId")
    }

    /**
     * Cancel summary notification for a group.
     * Also cancels all notifications in the group to prevent Android from auto-recreating the summary.
     */
    fun cancelSummaryNotification(context: Context, groupId: String) {
        if (groupId.isEmpty()) return
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val summaryNotificationId = UIDTConstants.SUMMARY_NOTIFICATION_ID + groupId.hashCode()

        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "cancelSummaryNotification called for group '$groupId', hashCode=${groupId.hashCode()}, summaryNotificationId=$summaryNotificationId")

        // First, cancel all active status bar notifications to ensure the group is completely removed
        // This prevents Android from auto-recreating a group summary from orphaned child notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val groupKey = "${UIDTConstants.NOTIFICATION_GROUP_KEY}_$groupId"
            try {
                val activeNotifications = notificationManager.activeNotifications
                RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Found ${activeNotifications.size} active notifications")
                for (notification in activeNotifications) {
                    val notifGroup = notification.notification?.group
                    RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Active notification id=${notification.id}, group=$notifGroup, target=$groupKey")
                    if (notifGroup == groupKey) {
                        notificationManager.cancel(notification.id)
                        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled orphaned group notification: ${notification.id}")
                    }
                }
            } catch (e: Exception) {
                RNBackgroundDownloaderModuleImpl.logW(UIDTConstants.TAG, "Failed to cancel orphaned notifications: ${e.message}")
            }
        }

        // Then cancel the summary notification
        notificationManager.cancel(summaryNotificationId)
        UIDTJobRegistry.clearGroupProgress(groupId)
        RNBackgroundDownloaderModuleImpl.logD(UIDTConstants.TAG, "Cancelled summary notification $summaryNotificationId for group $groupId")
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

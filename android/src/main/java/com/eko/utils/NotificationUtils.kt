package com.eko.utils

import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat

object NotificationUtils {
    /**
     * Tapping the notification brings the host app to the front (the launcher
     * intent reuses the running task instead of starting a second one).
     * Does nothing when the app has no launcher activity.
     */
    fun applyOpenAppIntent(builder: NotificationCompat.Builder, context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        builder.setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
    }
}

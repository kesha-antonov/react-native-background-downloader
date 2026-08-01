package com.eko.uidt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.eko.RNBackgroundDownloaderModuleImpl

/**
 * Receives the cancel action fired from the download progress notification.
 * The action carries the download's [EXTRA_CONFIG_ID] as a string extra; on
 * receive we stop the download and dispatch a downloadFailed event so JS can
 * clean up.
 *
 * Cancelling from the shade must leave exactly the same state behind as
 * `task.stop()` from JS, so the work is delegated to the module's stopTask
 * (progress tracking, resumable + DownloadManager cancellation, persisted
 * maps and paused records). Only when the module is not up - a cold start
 * triggered by this very broadcast - do we fall back to cancelling the UIDT
 * job on its own.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CancelDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL_DOWNLOAD) return
        val configId = intent.getStringExtra(EXTRA_CONFIG_ID) ?: return

        RNBackgroundDownloaderModuleImpl.logD(
            UIDTConstants.TAG,
            "CancelDownloadReceiver: cancelling $configId",
        )

        // Snapshot liveness first: stopTask tears the job down, so afterwards we
        // can no longer tell an active download from an already-finished one.
        val wasActive = UIDTJobRegistry.isActiveJob(configId)

        if (!RNBackgroundDownloaderModuleImpl.stopTaskFromNative(configId)) {
            RNBackgroundDownloaderModuleImpl.logD(
                UIDTConstants.TAG,
                "CancelDownloadReceiver: module not initialized, cancelling job directly",
            )
            UIDTJobManager.cancelJob(context, configId)
        }

        // Only notify JS when a live job was actually cancelled. If the download
        // already finished there is nothing to clean up, and dispatching here
        // would emit a spurious downloadFailed for an already-completed task.
        if (wasActive) {
            // Notify JS so the in-memory task map can be cleaned and the
            // .error() handler runs (lib uses CANCELLED errorCode = -1).
            UIDTJobRegistry.downloadListener?.onError(configId, CANCELLED_MESSAGE, CANCELLED_ERROR_CODE)
        }
    }

    companion object {
        const val ACTION_CANCEL_DOWNLOAD = "com.eko.uidt.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_CONFIG_ID = "config_id"

        private const val CANCELLED_MESSAGE = "Download cancelled by user"
        private const val CANCELLED_ERROR_CODE = -1
    }
}

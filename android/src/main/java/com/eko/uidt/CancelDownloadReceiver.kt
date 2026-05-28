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
 * receive we cancel the corresponding UIDT job (which also tears down the
 * notification) and dispatch a downloadFailed event so JS can clean up.
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
        UIDTJobManager.cancelJob(context, configId)

        // Notify JS so the in-memory task map can be cleaned and the
        // .error() handler runs (lib uses CANCELLED errorCode = -1).
        UIDTJobRegistry.downloadListener?.onError(configId, "Download cancelled by user", -1)
    }

    companion object {
        const val ACTION_CANCEL_DOWNLOAD = "com.eko.uidt.ACTION_CANCEL_DOWNLOAD"
        const val EXTRA_CONFIG_ID = "config_id"
    }
}

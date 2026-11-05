package com.eko

import android.app.Application
import android.app.DownloadManager
import androidx.test.core.app.ApplicationProvider
import com.facebook.react.bridge.BridgeReactContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The DOWNLOAD_COMPLETE receiver has to be exported for DownloadManager to reach it,
 * and the action isn't protected on every Android version. Without a sender
 * permission any app could send one and end (and remove) a running download.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadCompleteReceiverTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun registeredDownloadReceivers() = shadowOf(app).registeredReceivers.filter {
        it.intentFilter.hasAction(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
    }

    private fun registerReceiver() {
        val module = RNBackgroundDownloaderModuleImpl(BridgeReactContext(app))
        RNBackgroundDownloaderModuleImpl::class.java.getDeclaredMethod("registerDownloadReceiver").apply {
            isAccessible = true
            invoke(module)
        }
    }

    @Test
    @Config(sdk = [33])
    fun `only DownloadProvider may send it before Android 14`() {
        registerReceiver()

        val receivers = registeredDownloadReceivers()
        assertEquals(1, receivers.size)
        assertEquals("android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS", receivers[0].broadcastPermission)
    }

    @Test
    @Config(sdk = [34])
    fun `only DownloadProvider may send it on Android 14+`() {
        registerReceiver()

        val receivers = registeredDownloadReceivers()
        assertEquals(1, receivers.size)
        assertEquals("android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS", receivers[0].broadcastPermission)
        assertTrue(receivers[0].flags and android.content.Context.RECEIVER_EXPORTED != 0)
    }
}

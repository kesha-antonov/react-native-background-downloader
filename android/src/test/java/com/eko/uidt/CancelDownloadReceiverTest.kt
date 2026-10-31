package com.eko.uidt

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.eko.ResumableDownloader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cancel on the notification has to tell JS about any download it stops. A job the
 * system stopped (e.g. waiting for Wi-Fi) isn't running, but is still scheduled and
 * still shows the notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CancelDownloadReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val jobScheduler: JobScheduler
        get() = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    private val errors = mutableListOf<Pair<String, Int>>()

    @Before
    fun setUp() {
        jobScheduler.cancelAll()
        UIDTJobRegistry.activeJobs.clear()
        UIDTJobRegistry.downloadListener = object : ResumableDownloader.DownloadListener {
            override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = Unit
            override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
            override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
            override fun onError(id: String, error: String, errorCode: Int) {
                errors.add(id to errorCode)
            }
        }
    }

    @After
    fun tearDown() {
        UIDTJobRegistry.downloadListener = null
    }

    private fun cancel(configId: String) {
        CancelDownloadReceiver().onReceive(
            context,
            Intent(CancelDownloadReceiver.ACTION_CANCEL_DOWNLOAD)
                .putExtra(CancelDownloadReceiver.EXTRA_CONFIG_ID, configId)
        )
    }

    @Test
    fun `cancelling a scheduled job that isn't running reports the cancel and removes the job`() {
        assertTrue(
            UIDTJobManager.scheduleDownload(
                context = context,
                configId = "waiting",
                url = "https://example.com/file",
                destination = "/tmp/waiting",
                headers = emptyMap(),
                isAllowedOverMetered = false
            )
        )

        cancel("waiting")

        assertEquals(listOf("waiting" to -1), errors)
        assertTrue(jobScheduler.allPendingJobs.isEmpty())
    }

    @Test
    fun `cancelling a download that already ended reports nothing`() {
        cancel("finished")

        assertTrue(errors.isEmpty())
    }
}

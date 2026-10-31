package com.eko

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eko.uidt.UIDTConstants
import com.eko.uidt.UIDTJobRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A resumed download appends to the partial file, so it has to continue from the
 * file's length. Continuing from a saved count that doesn't match it either keeps
 * only the tail (no file) or duplicates bytes (the count lags the file).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloaderResumeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val jobScheduler: JobScheduler
        get() = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    private val listener = object : ResumableDownloader.DownloadListener {
        override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = Unit
        override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
        override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
        override fun onError(id: String, error: String, errorCode: Int) = Unit
    }

    @Before
    fun clearScheduler() {
        jobScheduler.cancelAll()
        UIDTJobRegistry.activeJobs.clear()
    }

    private fun pauseAndResume(destination: File, bytesDownloaded: Long): Long {
        val downloader = Downloader(context)
        downloader.savePausedDownloadState(
            Downloader.PausedDownloadInfo(
                configId = "paused",
                url = "https://example.com/file",
                destination = destination.absolutePath,
                headers = emptyMap(),
                bytesDownloaded = bytesDownloaded,
                bytesTotal = 1000,
                metadata = "{}",
                isAllowedOverMetered = true
            )
        )
        assertTrue(downloader.resume("paused", listener))
        return jobScheduler.allPendingJobs.single().extras.getLong(UIDTConstants.KEY_START_BYTE)
    }

    @Test
    fun `a paused DownloadManager download whose file is gone starts over`() {
        // Pausing a DownloadManager download removes it, and its partial file with it
        assertEquals(0L, pauseAndResume(File(tempFolder.root, "removed.bin"), bytesDownloaded = 400))
    }

    @Test
    fun `a resume continues from the partial file, not a lagging saved count`() {
        val partial = tempFolder.newFile("partial.bin").apply { writeBytes(ByteArray(600)) }

        assertEquals(600L, pauseAndResume(partial, bytesDownloaded = 400))
    }

    @Test
    fun `a UIDT job continues from the partial file's length`() {
        val partial = tempFolder.newFile("job.bin").apply { writeBytes(ByteArray(700)) }

        assertEquals(700L, UIDTDownloadJobService.resumeStartByte(500, partial.absolutePath))
    }

    @Test
    fun `a UIDT job whose partial file is gone starts over`() {
        assertEquals(0L, UIDTDownloadJobService.resumeStartByte(500, File(tempFolder.root, "gone.bin").absolutePath))
    }

    @Test
    fun `a UIDT job with nothing saved starts from zero even if a file is there`() {
        val stale = tempFolder.newFile("stale.bin").apply { writeBytes(ByteArray(300)) }

        assertEquals(0L, UIDTDownloadJobService.resumeStartByte(0, stale.absolutePath))
    }
}

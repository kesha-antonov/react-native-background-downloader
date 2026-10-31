package com.eko

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.eko.utils.StorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * On launch, downloads that were running when the app died are recovered from their
 * snapshots. One whose file is complete finished without the app (an Android 14+ job
 * records its final size then): it's done, not paused - resuming it would fail with 416.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloaderRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // A store of its own, so tests don't see each other's snapshots
    private val storage = StorageManager(context, "recovery-${UUID.randomUUID()}")

    private fun snapshot(configId: String, fileBytes: Int, bytesTotal: Long) {
        val file = tempFolder.newFile(configId).apply { writeBytes(ByteArray(fileBytes)) }
        storage.saveActiveDownload(
            Downloader.PausedDownloadInfo(
                configId = configId,
                url = "https://example.com/$configId",
                destination = file.absolutePath,
                headers = emptyMap(),
                bytesDownloaded = 0,
                bytesTotal = bytesTotal
            )
        )
    }

    @Test
    fun `a download whose file is complete is recovered as finished, once`() {
        snapshot("complete", fileBytes = 100, bytesTotal = 100)

        val downloader = Downloader(context, storage)

        assertEquals(setOf("complete"), downloader.takeFinishedDownloads().keys)
        assertTrue(downloader.getAllPausedDownloads().isEmpty())
        assertTrue(downloader.takeFinishedDownloads().isEmpty())
        assertTrue(storage.loadActiveDownloads().isEmpty())
    }

    @Test
    fun `a download with part of its file is recovered as paused`() {
        snapshot("partial", fileBytes = 40, bytesTotal = 100)

        val downloader = Downloader(context, storage)

        assertEquals(40L, downloader.getAllPausedDownloads()["partial"]?.bytesDownloaded)
        assertTrue(downloader.takeFinishedDownloads().isEmpty())
    }

    @Test
    fun `a download of unknown size is recovered as paused`() {
        snapshot("unknown-size", fileBytes = 40, bytesTotal = -1)

        val downloader = Downloader(context, storage)

        assertTrue(downloader.getAllPausedDownloads().containsKey("unknown-size"))
        assertTrue(downloader.takeFinishedDownloads().isEmpty())
    }
}

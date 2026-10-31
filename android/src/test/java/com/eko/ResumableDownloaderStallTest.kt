package com.eko

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A server that stops sending mid-transfer makes the read time out. That has to reach the
 * app as an error: a timeout is an InterruptedIOException, the same type a pause or cancel
 * raises, and was once taken for one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableDownloaderStallTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `a stalled transfer fails with the timeout code`() {
        // Headers and a few bytes, then nothing for longer than the read timeout
        server.enqueue(
            MockResponse()
                .setHeader("Content-Length", "1000")
                .setBody("x".repeat(1000))
                .throttleBody(10, 1, TimeUnit.SECONDS)
        )
        val failed = CountDownLatch(1)
        var reportedCode = 0
        val downloader = ResumableDownloader().apply { readTimeoutMs = 300 }

        downloader.startDownload(
            id = "stalled",
            url = server.url("/file").toString(),
            destination = tempFolder.newFile("stalled").absolutePath,
            headers = emptyMap(),
            listener = object : ResumableDownloader.DownloadListener {
                override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = Unit
                override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
                override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
                override fun onError(id: String, error: String, errorCode: Int) {
                    reportedCode = errorCode
                    failed.countDown()
                }
            },
            isAllowedOverMetered = true
        )

        assertTrue("no error was reported", failed.await(10, TimeUnit.SECONDS))
        assertEquals(DownloadConstants.ERR_TIMED_OUT, reportedCode)
    }
}

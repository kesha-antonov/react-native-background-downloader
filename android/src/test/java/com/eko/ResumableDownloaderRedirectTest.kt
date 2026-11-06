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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HttpURLConnection follows same-scheme redirects itself and refuses the others on
 * purpose. The downloader may only add http -> https upgrades on top of that, so a
 * redirect elsewhere is a plain HTTP error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableDownloaderRedirectTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val finished = CountDownLatch(1)
    @Volatile private var errorCode: Int? = null

    private val listener = object : ResumableDownloader.DownloadListener {
        override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = Unit
        override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
        override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
            finished.countDown()
        }
        override fun onError(id: String, error: String, errorCode: Int) {
            this@ResumableDownloaderRedirectTest.errorCode = errorCode
            finished.countDown()
        }
    }

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
    fun `a redirect to a non-http scheme fails with the redirect status`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "ftp://example.com/file"))

        ResumableDownloader().apply {
            networkRetryDelaysMs = longArrayOf(10)
        }.startDownload(
            id = "ftp",
            url = server.url("/file").toString(),
            destination = File(tempFolder.root, "ftp").absolutePath,
            headers = mapOf("Authorization" to "Bearer secret"),
            listener = listener,
            isAllowedOverMetered = true
        )

        assertTrue("the download didn't finish", finished.await(10, TimeUnit.SECONDS))
        assertEquals(302, errorCode)
        assertEquals(1, server.requestCount)
    }
}

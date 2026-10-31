package com.eko

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A transfer the network cuts off (a stall that times out, a dropped connection) continues
 * from the partial file with a Range request, as DownloadManager and iOS do. Only when the
 * retries get nothing through does the app get an error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableDownloaderStallTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val ranges = CopyOnWriteArrayList<String?>()

    private val body = ByteArray(200_000) { (it % 251).toByte() }

    private val finished = CountDownLatch(1)
    @Volatile private var errorCode: Int? = null
    @Volatile private var completed = false

    private val listener = object : ResumableDownloader.DownloadListener {
        override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = Unit
        override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
        override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
            completed = true
            finished.countDown()
        }
        override fun onError(id: String, error: String, errorCode: Int) {
            this@ResumableDownloaderStallTest.errorCode = errorCode
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

    // The first request gets part of the body and then fails as `firstResponse` says;
    // later ones get the rest, from their Range
    private fun serve(firstResponse: MockResponse.() -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                ranges.add(range)
                if (range == null) {
                    return MockResponse()
                        .setHeader("Content-Length", body.size.toString())
                        .setHeader("Accept-Ranges", "bytes")
                        .setBody(okio.Buffer().write(body))
                        .firstResponse()
                }
                val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Length", (body.size - start).toString())
                    .setHeader("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                    .setBody(okio.Buffer().write(body, start, body.size - start))
            }
        }
    }

    private fun download(id: String): File {
        val destination = File(tempFolder.root, id)
        ResumableDownloader().apply {
            readTimeoutMs = 300
            networkRetryDelaysMs = longArrayOf(10, 10, 10)
        }.startDownload(
            id = id,
            url = server.url("/file").toString(),
            destination = destination.absolutePath,
            headers = emptyMap(),
            listener = listener,
            isAllowedOverMetered = true
        )
        assertTrue("the download didn't finish", finished.await(10, TimeUnit.SECONDS))
        return destination
    }

    @Test
    fun `a dropped connection continues from the partial file`() {
        serve { setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) }

        val file = download("dropped")

        assertNull(errorCode)
        assertTrue(completed)
        assertTrue(ranges.size >= 2)
        assertTrue("the retry didn't continue from the partial file", ranges.last()!!.removePrefix("bytes=").removeSuffix("-").toInt() > 0)
        assertTrue(file.readBytes().contentEquals(body))
    }

    @Test
    fun `a stalled transfer continues from the partial file`() {
        // Some of the body, then nothing for longer than the read timeout
        serve { throttleBody(50_000, 1, TimeUnit.SECONDS) }

        val file = download("stalled")

        assertNull(errorCode)
        assertTrue(file.readBytes().contentEquals(body))
    }

    @Test
    fun `a transfer that keeps failing reports the timeout`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        }

        download("unreachable")

        assertEquals(DownloadConstants.ERR_TIMED_OUT, errorCode)
    }
}

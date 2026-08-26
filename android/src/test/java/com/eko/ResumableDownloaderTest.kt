package com.eko

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: ResumableDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ResumableDownloader()
        ResumableDownloader.setMaxConcurrentTransfers(4)
    }

    @After
    fun tearDown() {
        server.shutdown()
        ResumableDownloader.setMaxConcurrentTransfers(DownloadConstants.DEFAULT_MAX_PARALLEL_DOWNLOADS)
    }

    @Test
    fun `successful download writes the destination and releases its state`() {
        server.enqueue(MockResponse().setBody("complete payload"))
        val destination = tempFolder.newFile("success").apply { writeText("previous payload") }.absolutePath
        val terminal = CountDownLatch(1)

        downloader.startDownload("success", server.url("/file").toString(), destination, emptyMap(), listener(terminal))

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertEquals("complete payload", File(destination).readText())
        assertFalse(File("$destination.part").exists())
        assertNull(downloader.getState("success"))
    }

    @Test
    fun `matching SHA-256 promotes the completed download`() {
        server.enqueue(MockResponse().setBody("complete payload"))
        val destination = tempFolder.newFile("integrity-success").apply { writeText("previous payload") }
        val terminal = CountDownLatch(1)

        downloader.startDownload(
            "integrity-success",
            server.url("/file").toString(),
            destination.absolutePath,
            emptyMap(),
            listener(terminal),
            expectedSha256 = "5c9da7276c55d7d713bc1fdcc69072cecd1c7b4fa5cd081581c65c724940d1a9"
        )

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertEquals("complete payload", destination.readText())
        assertFalse(File("${destination.absolutePath}.part").exists())
        assertNull(downloader.getState("integrity-success"))
    }

    @Test
    fun `mismatched SHA-256 preserves the destination and removes the partial file`() {
        server.enqueue(MockResponse().setBody("complete payload"))
        val destination = tempFolder.newFile("integrity-failure").apply { writeText("previous payload") }
        val terminal = CountDownLatch(1)
        var errorCode = 0

        downloader.startDownload(
            "integrity-failure",
            server.url("/file").toString(),
            destination.absolutePath,
            emptyMap(),
            listener(terminal, onError = { errorCode = it }),
            expectedSha256 = "8810ad581e59f2bc3928b261707a71308f7e139eb04820366dc4d5c18d980225"
        )

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertEquals(-1, errorCode)
        assertEquals("previous payload", destination.readText())
        assertFalse(File("${destination.absolutePath}.part").exists())
        awaitUntil { downloader.getState("integrity-failure") == null }
    }

    @Test
    fun `HTTP error reports its status and releases its state`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val terminal = CountDownLatch(1)
        var errorCode = 0
        val listener = listener(terminal, onError = { errorCode = it })
        val destination = tempFolder.newFile("failure").apply { writeText("valid payload") }

        downloader.startDownload("failure", server.url("/file").toString(), destination.absolutePath, emptyMap(), listener)

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertEquals(503, errorCode)
        assertEquals("valid payload", destination.readText())
        assertFalse(File("${destination.absolutePath}.part").exists())
        awaitUntil { downloader.getState("failure") == null }
    }

    @Test
    fun `truncated response preserves the destination and removes its partial file`() {
        server.enqueue(
            MockResponse()
                .setBody("x".repeat(1024))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val destination = tempFolder.newFile("truncated").apply { writeText("valid payload") }
        val terminal = CountDownLatch(1)

        downloader.startDownload(
            "truncated",
            server.url("/file").toString(),
            destination.absolutePath,
            emptyMap(),
            listener(terminal)
        )

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertEquals("valid payload", destination.readText())
        assertFalse(File("${destination.absolutePath}.part").exists())
        awaitUntil { downloader.getState("truncated") == null }
    }

    @Test
    fun `Range resume appends partial content`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 3-5/6")
                .addHeader("ETag", "\"asset-v1\"")
                .setBody("def")
        )
        val destination = tempFolder.newFile("range").apply { writeText("previous") }
        File("${destination.absolutePath}.part").writeText("abc")
        val terminal = CountDownLatch(1)

        downloader.startDownload(
            "range",
            server.url("/file").toString(),
            destination.absolutePath,
            emptyMap(),
            listener(terminal),
            startByte = 3,
            totalBytes = 6,
            resumeValidator = ResumableDownloader.ResumeValidator.ETag("\"asset-v1\"")
        )

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        val request = server.takeRequest()
        assertEquals("bytes=3-", request.getHeader("Range"))
        assertEquals("\"asset-v1\"", request.getHeader("If-Range"))
        assertEquals("abcdef", destination.readText())
    }

    @Test
    fun `resume restarts without an entity validator`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("replacement"))
        val destination = tempFolder.newFile("restart").apply { writeText("previous") }
        File("${destination.absolutePath}.part").writeText("partial")
        val terminal = CountDownLatch(1)

        downloader.startDownload(
            "restart",
            server.url("/file").toString(),
            destination.absolutePath,
            emptyMap(),
            listener(terminal),
            startByte = 7,
            totalBytes = 20
        )

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals("replacement", destination.readText())
    }

    @Test
    fun `resume restarts when the server ignores Range`() {
        server.enqueue(MockResponse().setResponseCode(200).addHeader("ETag", "\"asset-v1\"").setBody("replacement"))
        val destination = tempFolder.newFile("ignored-range").apply { writeText("previous") }
        File("${destination.absolutePath}.part").writeText("partial")
        val terminal = CountDownLatch(1)

        downloader.startDownload(
            "ignored-range",
            server.url("/file").toString(),
            destination.absolutePath,
            emptyMap(),
            listener(terminal),
            startByte = 7,
            totalBytes = 20,
            resumeValidator = ResumableDownloader.ResumeValidator.ETag("\"asset-v1\"")
        )

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        val request = server.takeRequest()
        assertEquals("bytes=7-", request.getHeader("Range"))
        assertEquals("\"asset-v1\"", request.getHeader("If-Range"))
        assertEquals("replacement", destination.readText())
    }

    @Test
    fun `resume restarts when the entity validator changes`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .addHeader("Content-Range", "bytes 3-5/6")
                .addHeader("ETag", "\"asset-v2\"")
                .setBody("xyz")
        )
        server.enqueue(MockResponse().addHeader("ETag", "\"asset-v2\"").setBody("uvwxyz"))
        val destination = tempFolder.newFile("changed-entity").apply { writeText("previous") }
        File("${destination.absolutePath}.part").writeText("abc")
        val terminal = CountDownLatch(1)

        downloader.startDownload(
            "changed-entity",
            server.url("/file").toString(),
            destination.absolutePath,
            emptyMap(),
            listener(terminal),
            startByte = 3,
            totalBytes = 6,
            resumeValidator = ResumableDownloader.ResumeValidator.ETag("\"asset-v1\"")
        )

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        val resumeRequest = server.takeRequest()
        val restartRequest = server.takeRequest()
        assertEquals("bytes=3-", resumeRequest.getHeader("Range"))
        assertEquals("\"asset-v1\"", resumeRequest.getHeader("If-Range"))
        assertNull(restartRequest.getHeader("Range"))
        assertEquals("uvwxyz", destination.readText())
    }

    @Test
    fun `relative redirects complete without retaining task state`() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("redirected"))
        val destination = tempFolder.newFile("redirect").absolutePath
        val terminal = CountDownLatch(1)

        downloader.startDownload("redirect", server.url("/start").toString(), destination, emptyMap(), listener(terminal))

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertEquals("/start", server.takeRequest().path)
        assertEquals("/final", server.takeRequest().path)
        assertEquals("redirected", File(destination).readText())
        assertNull(downloader.getState("redirect"))
    }

    @Test
    fun `cancellation preserves the destination and removes the partial file`() {
        server.enqueue(MockResponse().setBody("x".repeat(1024)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
        val destination = tempFolder.newFile("cancelled").apply { writeText("valid payload") }
        val began = CountDownLatch(1)
        val listener = listener(CountDownLatch(1), onBegin = began::countDown)

        downloader.startDownload("cancelled", server.url("/slow").toString(), destination.absolutePath, emptyMap(), listener)

        assertTrue(began.await(5, TimeUnit.SECONDS))
        assertTrue(downloader.cancel("cancelled"))
        assertNull(downloader.getState("cancelled"))
        assertEquals("valid payload", destination.readText())
        assertFalse(File("${destination.absolutePath}.part").exists())
    }

    @Test
    fun `read timeout reports an error and releases task state`() {
        server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody("late"))
        downloader = ResumableDownloader(readTimeoutMs = 100)
        val terminal = CountDownLatch(1)
        var errorCode = 0
        val destination = tempFolder.newFile("timeout").apply { writeText("valid payload") }

        downloader.startDownload(
            "timeout",
            server.url("/slow-headers").toString(),
            destination.absolutePath,
            emptyMap(),
            listener(terminal, onError = { errorCode = it })
        )

        assertTrue(terminal.await(5, TimeUnit.SECONDS))
        assertEquals(-1, errorCode)
        assertEquals("valid payload", destination.readText())
        assertFalse(File("${destination.absolutePath}.part").exists())
        awaitUntil { downloader.getState("timeout") == null }
    }

    private fun listener(
        terminal: CountDownLatch,
        onBegin: () -> Unit = {},
        onError: (Int) -> Unit = {}
    ) = object : ResumableDownloader.DownloadListener {
        override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = onBegin()
        override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
        override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) = terminal.countDown()
        override fun onError(id: String, error: String, errorCode: Int) {
            onError(errorCode)
            terminal.countDown()
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}

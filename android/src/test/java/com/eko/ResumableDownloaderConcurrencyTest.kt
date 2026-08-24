package com.eko

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The library's own downloader is the one path with no scheduler in front of it:
 * before `maxParallelDownloads` applied here, every download took a thread and a
 * socket of its own the moment it started. These pin the cap that replaced that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableDownloaderConcurrencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: ResumableDownloader

    /** Held closed while a request is being served, so transfers stay in flight. */
    private val holdResponses = CountDownLatch(1)
    private val requested = CopyOnWriteArrayList<String>()

    private val completed = CopyOnWriteArrayList<String>()
    private val failed = ConcurrentHashMap<String, String>()

    private val listener = object : ResumableDownloader.DownloadListener {
        override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) = Unit
        override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) = Unit
        override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
            completed.add(id)
        }
        override fun onError(id: String, error: String, errorCode: Int) {
            failed[id] = error
        }
    }

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested.add(request.path ?: "")
                // Keep the transfer open until the test lets it finish, so a
                // running download really does occupy its slot
                holdResponses.await(20, TimeUnit.SECONDS)
                return MockResponse().setBody("payload")
            }
        }
        server.start()
        downloader = ResumableDownloader()
    }

    @After
    fun stopServer() {
        holdResponses.countDown()
        server.shutdown()
        // Other tests must not inherit this test's cap
        ResumableDownloader.setMaxConcurrentTransfers(DownloadConstants.DEFAULT_MAX_PARALLEL_DOWNLOADS)
    }

    private fun start(id: String) {
        val destination = File(tempFolder.root, id)
        destination.createNewFile()
        downloader.startDownload(
            id = id,
            url = server.url("/$id").toString(),
            destination = destination.absolutePath,
            headers = emptyMap(),
            listener = listener
        )
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun assertStaysAt(count: Int, what: String) {
        // Give anything that should not have started the chance to prove it did
        Thread.sleep(500)
        assertEquals(what, count, requested.size)
    }

    @Test
    fun `only maxConcurrentTransfers downloads transfer at once`() {
        ResumableDownloader.setMaxConcurrentTransfers(2)

        repeat(5) { start("download-$it") }

        awaitUntil("two transfers to reach the server") { requested.size == 2 }
        assertStaysAt(2, "third download must wait for a slot")
    }

    @Test
    fun `a waiting download starts when a running one finishes`() {
        ResumableDownloader.setMaxConcurrentTransfers(2)

        repeat(3) { start("download-$it") }
        awaitUntil("two transfers to reach the server") { requested.size == 2 }

        // Let the running transfers complete - each one frees its slot
        holdResponses.countDown()

        awaitUntil("the queued download to start") { requested.size == 3 }
        awaitUntil("every download to complete") { completed.size == 3 }
    }

    @Test
    fun `a download paused while waiting never starts`() {
        ResumableDownloader.setMaxConcurrentTransfers(1)

        start("running")
        awaitUntil("the first transfer to reach the server") { requested.size == 1 }

        start("waiting")
        assertStaysAt(1, "the second download must wait for a slot")

        // The user pauses it before it ever got a slot
        assertTrue(downloader.pause("waiting"))

        holdResponses.countDown()
        awaitUntil("the running download to complete") { completed.contains("running") }

        // Its slot freed, but a paused download must not be started by the queue
        assertStaysAt(1, "a download paused while waiting must not start")
        assertTrue(downloader.isPaused("waiting"))
    }

    @Test
    fun `a waiting download does not alter its destination`() {
        ResumableDownloader.setMaxConcurrentTransfers(1)

        start("running")
        awaitUntil("the first transfer to reach the server") { requested.size == 1 }

        val destination = tempFolder.newFile("preserved").apply { writeText("valid payload") }
        downloader.startDownload(
            id = "waiting",
            url = server.url("/waiting").toString(),
            destination = destination.absolutePath,
            headers = emptyMap(),
            listener = listener
        )

        assertStaysAt(1, "the second download must wait for a slot")
        assertEquals("valid payload", destination.readText())
        assertTrue(!File("${destination.absolutePath}.part").exists())
    }

    @Test
    fun `a download cancelled while waiting never starts`() {
        ResumableDownloader.setMaxConcurrentTransfers(1)

        start("running")
        awaitUntil("the first transfer to reach the server") { requested.size == 1 }

        start("waiting")
        assertStaysAt(1, "the second download must wait for a slot")

        assertTrue(downloader.cancel("waiting"))

        holdResponses.countDown()
        awaitUntil("the running download to complete") { completed.contains("running") }

        assertStaysAt(1, "a cancelled download must not start")
    }

    @Test
    fun `resuming a paused download goes back through the queue`() {
        ResumableDownloader.setMaxConcurrentTransfers(1)

        start("running")
        awaitUntil("the first transfer to reach the server") { requested.size == 1 }

        start("waiting")
        assertTrue(downloader.pause("waiting"))
        assertTrue(downloader.resume("waiting", listener))

        // The slot is still taken by the running download, so a resume waits too
        assertStaysAt(1, "a resumed download must not jump the queue")

        holdResponses.countDown()
        awaitUntil("the resumed download to start") { requested.size == 2 }
    }

    @Test
    fun `the cap does not lose downloads under load`() {
        ResumableDownloader.setMaxConcurrentTransfers(3)

        repeat(12) { start("download-$it") }
        awaitUntil("the first batch to reach the server") { requested.size == 3 }

        holdResponses.countDown()

        awaitUntil("every download to complete", timeoutMs = 20000) { completed.size == 12 }
        assertEquals("no download ran twice", 12, requested.toSet().size)
        assertTrue("no download failed: $failed", failed.isEmpty())
    }

    @Test
    fun `replacing an active id completes only the replacement`() {
        ResumableDownloader.setMaxConcurrentTransfers(2)

        start("same-id")
        awaitUntil("the original transfer to reach the server") { requested.size == 1 }
        val original = downloader.getState("same-id")!!

        start("same-id")
        val replacement = downloader.getState("same-id")!!
        assertNotSame(original, replacement)
        assertTrue(original.isCancelled.get())
        assertTrue(original.sessionId.get() > 0)
        awaitUntil("the replacement transfer to start") { requested.size == 2 }

        holdResponses.countDown()
        awaitUntil("the replacement transfer to complete") { completed.size == 1 }
        assertTrue("the invalidated transfer must not fail: $failed", failed.isEmpty())
    }
}

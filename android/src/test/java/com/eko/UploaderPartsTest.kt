package com.eko

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S3-style multipart upload: one file sent in byte ranges to one URL per part, with
 * each part's ETag (a response header) reported back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploaderPartsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    // Body each part path received
    private val received = ConcurrentHashMap<String, ByteArray>()
    // Part paths that answer with this status instead of 200
    private val failing = ConcurrentHashMap<String, Int>()

    private val body = ByteArray(25_000) { (it % 251).toByte() }

    private val finished = CountDownLatch(1)
    @Volatile private var error: Pair<String, Int>? = null
    @Volatile private var completion: Completion? = null
    private val partEvents = CopyOnWriteArrayList<UploadPartResult>()
    private val begins = CopyOnWriteArrayList<Long>()

    data class Completion(
        val responseCode: Int,
        val responseHeaders: Map<String, String>,
        val parts: List<UploadPartResult>?,
        val bytesUploaded: Long,
        val bytesTotal: Long
    )

    private val listener = object : Uploader.UploadListener {
        override fun onBegin(id: String, expectedBytes: Long) {
            begins.add(expectedBytes)
        }
        override fun onProgress(id: String, bytesUploaded: Long, bytesTotal: Long) = Unit
        override fun onPartComplete(id: String, part: UploadPartResult) {
            partEvents.add(part)
        }
        override fun onComplete(
            id: String,
            responseCode: Int,
            responseBody: String,
            responseHeaders: Map<String, String>,
            parts: List<UploadPartResult>?,
            bytesUploaded: Long,
            bytesTotal: Long
        ) {
            completion = Completion(responseCode, responseHeaders, parts, bytesUploaded, bytesTotal)
            finished.countDown()
        }
        override fun onError(id: String, error: String, errorCode: Int) {
            this@UploaderPartsTest.error = error to errorCode
            finished.countDown()
        }
    }

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                received[path] = request.body.readByteArray()
                failing[path]?.let { return MockResponse().setResponseCode(it).setBody("<Error>denied</Error>") }
                // S3 answers UploadPart with an empty body and the part's ETag
                return MockResponse().setHeader("ETag", "\"etag-$path\"")
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun source(): File = tempFolder.newFile("video.mp4").apply { writeBytes(body) }

    private fun upload(config: RNBGDUploadTaskConfig) {
        Uploader(ApplicationProvider.getApplicationContext()).startUpload(config, listener)
        assertTrue("the upload didn't finish", finished.await(10, TimeUnit.SECONDS))
    }

    private fun config(file: File, parts: Int, partSize: Long = 0, completed: List<UploadPartResult>? = null) =
        RNBGDUploadTaskConfig(
            id = "upload",
            url = server.url("/p1").toString(),
            source = file.absolutePath,
            method = "PUT",
            partUrls = (1..parts).map { server.url("/p$it").toString() },
            partSize = partSize,
            completedParts = completed
        )

    @Test
    fun `each part gets its byte range and reports its ETag`() {
        upload(config(source(), parts = 3))

        assertNull(error)
        // ceil(25000 / 3) = 8334: two full parts and the remainder
        assertArrayEquals(body.copyOfRange(0, 8334), received["/p1"])
        assertArrayEquals(body.copyOfRange(8334, 16668), received["/p2"])
        assertArrayEquals(body.copyOfRange(16668, 25000), received["/p3"])

        val done = completion!!
        assertEquals(listOf(1, 2, 3), done.parts!!.map { it.partNumber })
        assertEquals(listOf(8334L, 8334L, 8332L), done.parts!!.map { it.bytes })
        // Lowercased header names, as on iOS
        assertEquals("\"etag-/p2\"", done.parts!![1].responseHeaders["etag"])
        assertEquals("\"etag-/p3\"", done.responseHeaders["etag"])
        assertEquals(25_000L, done.bytesUploaded)
        assertEquals(25_000L, done.bytesTotal)
        assertEquals(listOf(25_000L), begins)
        assertEquals(3, partEvents.size)
    }

    @Test
    fun `parts are sent with the given method and no multipart wrapping`() {
        upload(config(source(), parts = 2))

        val first = server.takeRequest()
        assertEquals("PUT", first.method)
        assertEquals("application/octet-stream", first.getHeader("Content-Type"))
        assertEquals("12500", first.getHeader("Content-Length"))
    }

    @Test
    fun `an explicit part size with a smaller last part`() {
        upload(config(source(), parts = 3, partSize = 10_000))

        assertNull(error)
        assertEquals(listOf(10_000L, 10_000L, 5_000L), completion!!.parts!!.map { it.bytes })
        assertArrayEquals(body.copyOfRange(20_000, 25_000), received["/p3"])
    }

    @Test
    fun `a part size that doesn't cover the file fails before sending anything`() {
        upload(config(source(), parts = 2, partSize = 10_000))

        assertTrue(error!!.first.contains("can't be split into 2 parts of 10000 bytes"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `more parts than the file can fill fail`() {
        val file = tempFolder.newFile("tiny").apply { writeBytes(ByteArray(9)) }
        // ceil(9 / 4) = 3 bytes each would leave the last part empty
        upload(config(file, parts = 4))

        assertTrue(error!!.first.contains("can't be split into 4 parts"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a rejected part fails the upload with its status and stops there`() {
        failing["/p2"] = 403

        upload(config(source(), parts = 3))

        assertEquals(403, error!!.second)
        assertTrue(error!!.first.contains("Part 2 failed: HTTP 403 - <Error>denied</Error>"))
        assertEquals(setOf("/p1", "/p2"), received.keys)
        assertNull(completion)
    }

    @Test
    fun `a resumed upload skips the parts the server already accepted`() {
        val accepted = UploadPartResult(1, 200, "", mapOf("etag" to "\"saved\""), 12_500)

        upload(config(source(), parts = 2, completed = listOf(accepted)))

        assertEquals(setOf("/p2"), received.keys)
        val done = completion!!
        assertEquals(listOf("\"saved\"", "\"etag-/p2\""), done.parts!!.map { it.responseHeaders["etag"] })
        assertEquals(25_000L, done.bytesUploaded)
    }

    @Test
    fun `a single upload reports its response headers too`() {
        upload(
            RNBGDUploadTaskConfig(
                id = "upload",
                url = server.url("/single").toString(),
                source = source().absolutePath,
                method = "PUT"
            )
        )

        val done = completion!!
        assertEquals("\"etag-/single\"", done.responseHeaders["etag"])
        assertNull(done.parts)
        assertArrayEquals(body, received["/single"])
    }
}

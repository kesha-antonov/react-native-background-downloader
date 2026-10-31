package com.eko

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The body is sent with a fixed Content-Length, so the length counted up front has to
 * be the number of bytes written. Non-ASCII text is more than one byte per character.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UploaderMultipartTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `non-ASCII field values and file names are sent as UTF-8`() {
        val file = tempFolder.newFile("отчёт.txt").apply { writeText("payload") }
        val done = CountDownLatch(1)
        var failedWith: String? = null
        var completedWith = 0

        Uploader(ApplicationProvider.getApplicationContext()).startUpload(
            RNBGDUploadTaskConfig(
                id = "upload",
                url = server.url("/upload").toString(),
                source = file.absolutePath,
                fieldName = "file",
                parameters = mapOf("title" to "Привет, 世界"),
            ),
            object : Uploader.UploadListener {
                override fun onBegin(id: String, expectedBytes: Long) = Unit
                override fun onProgress(id: String, bytesUploaded: Long, bytesTotal: Long) = Unit
                override fun onComplete(id: String, responseCode: Int, responseBody: String, bytesUploaded: Long, bytesTotal: Long) {
                    completedWith = responseCode
                    done.countDown()
                }
                override fun onError(id: String, error: String, errorCode: Int) {
                    failedWith = error
                    done.countDown()
                }
            }
        )

        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(failedWith)
        assertEquals(200, completedWith)

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        val body = request.body.readUtf8()
        assertEquals(request.getHeader("Content-Length")!!.toLong(), request.bodySize)
        assertTrue(body.contains("Привет, 世界"))
        assertTrue(body.contains("filename=\"отчёт.txt\""))
    }
}

package com.eko

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UploaderReplacementTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val releaseResponses = CountDownLatch(1)
    private val requestReceived = CountDownLatch(1)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestReceived.countDown()
                releaseResponses.await(10, TimeUnit.SECONDS)
                return MockResponse().setBody("ok")
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        releaseResponses.countDown()
        server.shutdown()
    }

    @Test
    fun `replacing an upload invalidates the original session`() {
        val source = tempFolder.newFile("upload").apply { writeText("payload") }
        val uploader = Uploader()
        val originalConfig = config(source.absolutePath)

        uploader.startUpload(originalConfig, listener)
        assertTrue(requestReceived.await(5, TimeUnit.SECONDS))
        val original = uploader.getState(originalConfig.id)!!

        uploader.startUpload(config(source.absolutePath), listener)
        val replacement = uploader.getState(originalConfig.id)!!

        assertNotSame(original, replacement)
        assertTrue(original.isCancelled.get())
        assertTrue(original.sessionId.get() > 0)
        uploader.cancel(originalConfig.id)
    }

    private fun config(source: String) = RNBGDUploadTaskConfig(
        id = "same-id",
        url = server.url("/upload").toString(),
        source = source,
        method = "PUT"
    )

    private val listener = object : Uploader.UploadListener {
        override fun onBegin(id: String, expectedBytes: Long) = Unit
        override fun onProgress(id: String, bytesUploaded: Long, bytesTotal: Long) = Unit
        override fun onComplete(id: String, responseCode: Int, responseBody: String, bytesUploaded: Long, bytesTotal: Long) = Unit
        override fun onError(id: String, error: String, errorCode: Int) = Unit
    }
}

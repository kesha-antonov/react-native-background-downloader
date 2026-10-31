package com.eko.handlers

import com.eko.RNBGDTaskConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The HEAD request that reads the size goes to the same URL as the download, so a
 * URL behind auth only answers it when it carries the download's headers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnBeginTest {

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
    fun `the HEAD request sends the download's headers`() {
        server.enqueue(MockResponse().setHeader("Content-Length", "10"))
        val config = RNBGDTaskConfig(id = "a", url = server.url("/file").toString(), destination = "/tmp/a")

        // The response is turned into a React map, which needs the native React library;
        // only the request matters here
        runCatching {
            OnBegin(config, { _, _, _ -> }, mapOf("Authorization" to "Bearer token")).call()
        }

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("HEAD", request.method)
        assertEquals("Bearer token", request.getHeader("Authorization"))
    }
}

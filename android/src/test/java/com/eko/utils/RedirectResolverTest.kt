package com.eko.utils

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A Location header may be relative. It has to be resolved against the URL that
 * answered with it, keeping that URL's scheme, host, port and directory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedirectResolverTest {

    private lateinit var server: MockWebServer
    private val redirects = mutableMapOf<String, String>()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val location = redirects[request.path]
                    ?: return MockResponse().setResponseCode(200)
                return MockResponse().setResponseCode(302).setHeader("Location", location)
            }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun resolve(path: String) = RedirectResolver.resolve(server.url(path).toString(), 5, null)

    @Test
    fun `a relative location keeps the port and directory`() {
        redirects["/files/start"] = "next/file.zip"

        assertEquals(server.url("/files/next/file.zip").toString(), resolve("/files/start"))
    }

    @Test
    fun `an absolute path keeps the port`() {
        redirects["/files/start"] = "/other/file.zip"

        assertEquals(server.url("/other/file.zip").toString(), resolve("/files/start"))
    }

    @Test
    fun `a chain of relative redirects resolves each step against the previous one`() {
        redirects["/a/start"] = "../b/step"
        redirects["/b/step"] = "final.zip"

        assertEquals(server.url("/b/final.zip").toString(), resolve("/a/start"))
    }
}

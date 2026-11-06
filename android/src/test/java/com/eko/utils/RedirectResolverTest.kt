package com.eko.utils

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import com.facebook.react.bridge.JavaOnlyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URL

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

    // A second server is a different origin (another port)
    private fun otherServer(): MockWebServer = MockWebServer().also {
        it.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
        }
        it.start()
    }

    @Test
    fun `stops before another origin while credentials are attached`() {
        val other = otherServer()
        try {
            redirects["/start"] = other.url("/file.zip").toString()
            val headers = JavaOnlyMap.of("Authorization", "Bearer secret")

            val resolved = RedirectResolver.resolve(server.url("/start").toString(), 5, headers)

            assertEquals(server.url("/start").toString(), resolved)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `follows another origin when no credentials are attached`() {
        val other = otherServer()
        try {
            redirects["/start"] = other.url("/file.zip").toString()
            val headers = JavaOnlyMap.of("Accept", "*/*")

            val resolved = RedirectResolver.resolve(server.url("/start").toString(), 5, headers)

            assertEquals(other.url("/file.zip").toString(), resolved)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `doesn't resolve to a non-http location`() {
        redirects["/start"] = "ftp://example.com/file.zip"

        // DownloadManager.Request throws on an ftp URL, which crashed the app
        assertEquals(server.url("/start").toString(), resolve("/start"))
    }

    @Test
    fun `refuses an https to http downgrade even on the same host`() {
        assertFalse(RedirectResolver.isSafeHop(URL("https://a.example/x"), URL("http://a.example/y"), emptyMap()))
        assertTrue(RedirectResolver.isSafeHop(URL("http://a.example/x"), URL("https://a.example/y"), emptyMap()))
    }

    @Test
    fun `credential headers are matched case-insensitively`() {
        val from = URL("https://a.example/x")
        val to = URL("https://b.example/y")
        assertFalse(RedirectResolver.isSafeHop(from, to, mapOf("cookie" to "s=1")))
        assertFalse(RedirectResolver.isSafeHop(from, to, mapOf("PROXY-AUTHORIZATION" to "x")))
        assertTrue(RedirectResolver.isSafeHop(from, URL("https://a.example:443/y"), mapOf("Authorization" to "x")))
    }
}

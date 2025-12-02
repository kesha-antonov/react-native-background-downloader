package com.eko.handlers

import com.eko.RNBGDTaskConfig
import com.eko.interfaces.BeginCallback
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.Callable

/**
 * Callable that fetches download headers and expected bytes before download begins.
 * Executes a HEAD request to retrieve metadata without downloading content.
 */
class OnBegin(
    private val config: RNBGDTaskConfig,
    private val callback: BeginCallback
) : Callable<OnBeginState> {

    companion object {
        /** Timeout for establishing connection (milliseconds) */
        private const val CONNECT_TIMEOUT_MS = 30000  // 30 seconds
        /** Timeout for reading initial response (milliseconds) */
        private const val READ_TIMEOUT_MS = 60000     // 60 seconds
    }

    override fun call(): OnBeginState {
        var urlConnection: HttpURLConnection? = null
        try {
            urlConnection = getConnection(config.url)
            val urlHeaders = urlConnection.headerFields
            val headers = getHeaders(urlConnection, urlHeaders)
            urlConnection.inputStream.close()

            val bytesExpected = getContentLength(headers)
            callback.onBegin(config.id, headers, bytesExpected)
            return OnBeginState(config.id, headers, bytesExpected)
        } catch (e: Exception) {
            throw Exception(e)
        } finally {
            urlConnection?.disconnect()
        }
    }

    @Throws(Exception::class)
    private fun getConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val urlConnection = url.openConnection() as HttpURLConnection
        // Requests only headers from the server.
        // Prevents memory leaks for invalid connections.
        urlConnection.requestMethod = "HEAD"

        // Set timeout values to prevent downloads from staying in PENDING state
        // when URLs are slow to respond (e.g., taking 2-6 minutes)
        urlConnection.connectTimeout = CONNECT_TIMEOUT_MS
        urlConnection.readTimeout = READ_TIMEOUT_MS

        // 200 and 206 codes are successful http codes.
        val httpStatusCode = urlConnection.responseCode
        if (httpStatusCode != HttpURLConnection.HTTP_OK && httpStatusCode != HttpURLConnection.HTTP_PARTIAL) {
            throw Exception("HTTP response not valid: $httpStatusCode")
        }

        return urlConnection
    }

    private fun getHeaders(urlConnection: URLConnection, urlHeaders: Map<String, List<String>>): WritableMap {
        val headers = Arguments.createMap()

        for (key in urlHeaders.keys) {
            val value = urlConnection.getHeaderField(key)
            headers.putString(key, value)
        }

        return headers
    }

    private fun getContentLength(headersMap: WritableMap): Long {
        val contentLengthString = headersMap.getString("Content-Length")

        return if (contentLengthString != null) {
            try {
                contentLengthString.toLong()
            } catch (e: NumberFormatException) {
                -1L // Unknown size
            }
        } else {
            -1L // Unknown size - server didn't provide Content-Length
        }
    }
}

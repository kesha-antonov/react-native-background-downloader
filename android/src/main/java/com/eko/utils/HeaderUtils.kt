package com.eko.utils

import android.app.DownloadManager
import com.eko.DownloadConstants
import com.facebook.react.bridge.ReadableMap
import java.net.HttpURLConnection

/**
 * Utility functions for handling HTTP headers in download operations.
 */
object HeaderUtils {

    /**
     * Check if the provided headers already contain a User-Agent header (case-insensitive).
     */
    fun hasUserAgent(headers: ReadableMap?): Boolean {
        if (headers == null) return false

        val iterator = headers.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            if (key.equals("user-agent", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * Check if a Map<String, String> contains a User-Agent header (case-insensitive).
     */
    fun hasUserAgent(headers: Map<String, String>?): Boolean {
        if (headers == null) return false
        return headers.keys.any { it.equals("user-agent", ignoreCase = true) }
    }

    /**
     * Convert a ReadableMap of headers to a Map<String, String>.
     */
    fun toMap(headers: ReadableMap?): Map<String, String> {
        if (headers == null) return emptyMap()

        val result = mutableMapOf<String, String>()
        val iterator = headers.keySetIterator()
        while (iterator.hasNextKey()) {
            val key = iterator.nextKey()
            result[key] = headers.getString(key) ?: ""
        }
        return result
    }

    /**
     * Apply headers from a ReadableMap to an HttpURLConnection.
     */
    fun applyToConnection(connection: HttpURLConnection, headers: ReadableMap?) {
        headers?.let {
            val iterator = it.keySetIterator()
            while (iterator.hasNextKey()) {
                val key = iterator.nextKey()
                connection.setRequestProperty(key, it.getString(key))
            }
        }
    }

    /**
     * Apply default headers for improved connection handling.
     * Adds Connection: keep-alive and User-Agent if not already present.
     */
    fun applyDefaultHeaders(connection: HttpURLConnection, existingHeaders: ReadableMap? = null) {
        connection.setRequestProperty("Connection", "keep-alive")
        connection.setRequestProperty("Keep-Alive", DownloadConstants.KEEP_ALIVE_HEADER_VALUE)

        if (!hasUserAgent(existingHeaders)) {
            connection.setRequestProperty("User-Agent", DownloadConstants.USER_AGENT)
        }
    }

    /**
     * Apply default headers to a DownloadManager.Request for improved connection handling.
     * Adds Connection: keep-alive and User-Agent if not already present.
     */
    fun applyDefaultHeaders(request: DownloadManager.Request, existingHeaders: ReadableMap? = null) {
        request.addRequestHeader("Connection", "keep-alive")
        request.addRequestHeader("Keep-Alive", DownloadConstants.KEEP_ALIVE_HEADER_VALUE)

        if (!hasUserAgent(existingHeaders)) {
            request.addRequestHeader("User-Agent", DownloadConstants.USER_AGENT)
        }
    }

    /**
     * Extract response headers from an HttpURLConnection.
     */
    fun extractResponseHeaders(connection: HttpURLConnection): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        for (i in 0 until connection.headerFields.size) {
            val key = connection.getHeaderFieldKey(i)
            val value = connection.getHeaderField(i)
            if (key != null && value != null) {
                headers[key] = value
            }
        }
        return headers
    }
}

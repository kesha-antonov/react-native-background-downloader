package com.eko.utils

import com.facebook.react.bridge.ReadableMap
import java.net.HttpURLConnection

/**
 * Utility functions for handling HTTP headers in download operations.
 */
object HeaderUtils {

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

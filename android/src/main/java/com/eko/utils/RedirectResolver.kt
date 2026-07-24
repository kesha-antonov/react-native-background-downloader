package com.eko.utils

import com.eko.DownloadConstants
import com.eko.RNBackgroundDownloaderModuleImpl
import com.facebook.react.bridge.ReadableMap
import java.net.HttpURLConnection
import java.net.URL

/**
 * Follows HTTP 3xx redirects with HEAD requests to find the final download URL before
 * handing it to DownloadManager (which does not expose the resolved URL). Stateless and
 * self-contained - extracted from the module so the redirect logic is isolated and
 * testable. Never throws: on any failure it returns the original URL unchanged.
 */
object RedirectResolver {

    private const val TAG = "RNBackgroundDownloader"

    /**
     * Resolve up to [maxRedirects] redirects starting from [originalUrl], applying
     * [headers] to each probe. Returns the final URL, or [originalUrl] if resolution
     * is disabled ([maxRedirects] <= 0) or fails.
     */
    fun resolve(originalUrl: String, maxRedirects: Int, headers: ReadableMap?): String {
        if (maxRedirects <= 0) {
            return originalUrl
        }

        try {
            var currentUrl = originalUrl
            var redirectCount = 0

            while (redirectCount < maxRedirects) {
                val url = URL(currentUrl)
                val connection = url.openConnection() as HttpURLConnection

                // Add headers to the redirect resolution request
                HeaderUtils.applyToConnection(connection, headers)

                // Add default headers for consistency with DownloadManager
                HeaderUtils.applyDefaultHeaders(connection, headers)

                connection.instanceFollowRedirects = false
                connection.requestMethod = "HEAD" // Use HEAD to avoid downloading content
                connection.connectTimeout = DownloadConstants.REDIRECT_TIMEOUT_MS
                connection.readTimeout = DownloadConstants.REDIRECT_TIMEOUT_MS

                val responseCode = connection.responseCode

                if (responseCode in 300..399) {
                    // This is a redirect
                    val location = connection.getHeaderField("Location")
                    if (location == null) {
                        RNBackgroundDownloaderModuleImpl.logW(TAG, "Redirect response without Location header at: $currentUrl")
                        break
                    }

                    // Handle relative URLs
                    currentUrl = when {
                        location.startsWith("/") -> {
                            val baseUrl = URL(currentUrl)
                            "${baseUrl.protocol}://${baseUrl.host}$location"
                        }
                        !location.startsWith("http") -> {
                            val baseUrl = URL(currentUrl)
                            "${baseUrl.protocol}://${baseUrl.host}/$location"
                        }
                        else -> location
                    }

                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Redirect ${redirectCount + 1}/$maxRedirects: $currentUrl -> $location")
                    redirectCount++
                } else {
                    // Not a redirect, we've found the final URL
                    break
                }

                connection.disconnect()
            }

            if (redirectCount >= maxRedirects) {
                RNBackgroundDownloaderModuleImpl.logW(
                    TAG,
                    "Reached maximum redirects ($maxRedirects) for URL: $originalUrl. Final URL: $currentUrl"
                )
            } else {
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Resolved URL after $redirectCount redirects: $currentUrl")
            }

            return currentUrl
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to resolve redirects for URL: $originalUrl. Error: ${e.message}")
            // Return original URL if redirect resolution fails
            return originalUrl
        }
    }
}

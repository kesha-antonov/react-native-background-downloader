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

                    // Resolve relative locations against the current URL, keeping its port,
                    // path and scheme (for "//host/path")
                    val previousUrl = currentUrl
                    val nextUrl = URL(URL(currentUrl), location)
                    if (!isSafeHop(URL(currentUrl), nextUrl, headers)) {
                        // Leave this hop to the downloader: the platform's own redirect
                        // handling drops credentials across hosts and refuses downgrades
                        RNBackgroundDownloaderModuleImpl.logW(TAG, "Not following redirect from $previousUrl to $nextUrl")
                        connection.disconnect()
                        break
                    }
                    currentUrl = nextUrl.toString()

                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Redirect ${redirectCount + 1}/$maxRedirects: $previousUrl -> $currentUrl")
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

    /**
     * Whether the resolver may probe [next] with the request's headers. Only http(s),
     * never https -> http, and no other origin while credentials are attached -
     * every probe resends all headers, and the resolved URL is downloaded with them.
     */
    internal fun isSafeHop(current: URL, next: URL, headers: Map<String, String>): Boolean {
        val scheme = next.protocol.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (current.protocol.equals("https", ignoreCase = true) && scheme == "http") return false
        val sameOrigin = current.protocol.equals(next.protocol, ignoreCase = true) &&
            current.host.equals(next.host, ignoreCase = true) &&
            effectivePort(current) == effectivePort(next)
        return sameOrigin || headers.keys.none { HeaderUtils.isCredentialHeader(it) }
    }

    private fun isSafeHop(current: URL, next: URL, headers: ReadableMap?): Boolean =
        isSafeHop(current, next, HeaderUtils.toMap(headers))

    private fun effectivePort(url: URL): Int = if (url.port != -1) url.port else url.defaultPort
}

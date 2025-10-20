package com.eko.handlers

import com.eko.RNBGDTaskConfig
import com.eko.interfaces.BeginCallback
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import com.facebook.react.bridge.WritableMap
import com.facebook.react.bridge.Arguments
import java.util.concurrent.Callable

class OnBegin(
  private val config: RNBGDTaskConfig,
  private val callback: BeginCallback
) : Callable<OnBeginState> {

  @Throws(Exception::class)
  override fun call(): OnBeginState {
    var urlConnection: HttpURLConnection? = null
    try {
      urlConnection = getConnection(config.url!!)
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
    urlConnection.connectTimeout = 30000 // 30 seconds to establish connection
    urlConnection.readTimeout = 60000    // 60 seconds to read initial response

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
      val valStr = urlConnection.getHeaderField(key)
      headers.putString(key, valStr)
    }

    return headers
  }

  private fun getContentLength(headersMap: WritableMap): Long {
    val contentLengthString = headersMap.getString("Content-Length")

    return if (contentLengthString != null) {
      try {
        contentLengthString.toLong()
      } catch (e: NumberFormatException) {
        0L
      }
    } else {
      0L
    }
  }
}

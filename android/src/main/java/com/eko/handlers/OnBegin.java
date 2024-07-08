package com.eko.handlers;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.eko.interfaces.BeginCallback;
import com.eko.RNBGDTaskConfig;

import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

public class OnBegin implements Callable<OnBeginState> {
  private final RNBGDTaskConfig config;
  private final BeginCallback callback;

  public OnBegin(RNBGDTaskConfig config, BeginCallback callback) {
    this.config = config;
    this.callback = callback;
  }

  @Override
  public OnBeginState call() throws Exception {
    HttpURLConnection urlConnection = null;
    try {
      urlConnection = getConnection(config.url);
      Map<String, List<String>> urlHeaders = urlConnection.getHeaderFields();
      WritableMap headers = getHeaders(urlConnection, urlHeaders);
      urlConnection.getInputStream().close();

      long bytesExpected = getContentLength(headers);
      callback.onBegin(config.id, headers, bytesExpected);
      return new OnBeginState(config.id, headers, bytesExpected);
    } catch (Exception e) {
      throw new Exception(e);
    } finally {
      if (urlConnection != null) {
        urlConnection.disconnect();
      }
    }
  }

  private HttpURLConnection getConnection(String urlString) throws Exception {
    URL url = new URL(urlString);
    HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
    // Requests only headers from the server.
    // Prevents memory leaks for invalid connections.
    urlConnection.setRequestMethod("HEAD");

    // 200 and 206 codes are successful http codes.
    int httpStatusCode = urlConnection.getResponseCode();
    if (httpStatusCode != HttpURLConnection.HTTP_OK && httpStatusCode != HttpURLConnection.HTTP_PARTIAL) {
      throw new Exception("HTTP response not valid: " + httpStatusCode);
    }

    return urlConnection;
  }

  private WritableMap getHeaders(URLConnection urlConnection, Map<String, List<String>> urlHeaders) {
    WritableMap headers = Arguments.createMap();

    Set<String> keys = urlHeaders.keySet();
    for (String key : keys) {
      String val = urlConnection.getHeaderField(key);
      headers.putString(key, val);
    }

    return headers;
  }

  private long getContentLength(WritableMap headersMap) {
    String contentLengthString = headersMap.getString("Content-Length");

    if (contentLengthString != null) {
      try {
        return Long.parseLong(contentLengthString);
      } catch (NumberFormatException e) {
        return 0;
      }
    }

    return 0;
  }
}

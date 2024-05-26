package com.eko.handlers;

import android.util.Log;

import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eko.interfaces.BeginCallback;
import com.eko.RNBGDTaskConfig;

import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

public class OnBegin extends Thread {
  private final RNBGDTaskConfig config;
  private final BeginCallback callback;
  private long bytesExpected;

  public OnBegin(RNBGDTaskConfig config, BeginCallback callback) {
    this.config = config;
    this.callback = callback;
    this.bytesExpected = 0;
  }

  public long getBytesExpected() {
    return bytesExpected;
  }

  @Override
  public void run() {
    try {
      URL url = new URL(config.url);
      URLConnection urlConnection = url.openConnection();
      Map<String, List<String>> urlHeaders = urlConnection.getHeaderFields();
      WritableMap headers = getHeaders(urlConnection, urlHeaders);
      urlConnection.getInputStream().close();

      bytesExpected = getContentLength(headers);
      callback.onBegin(config.id, headers, bytesExpected);
    } catch (Exception e) {
      Log.e("RNBackgroundDownloader", "OnBegin: " + Log.getStackTraceString(e));
    }
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

package com.eko.handlers;

import android.util.Log;

import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eko.interfaces.BeginCallback;
import com.eko.RNBGDTaskConfig;

import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

public class OnBegin extends Thread {
  private final RNBGDTaskConfig config;
  private final BeginCallback callback;
  private HashMap params;


  public OnBegin(RNBGDTaskConfig config, BeginCallback callback) {
    this.config = config;
    this.callback = callback;
  }

  public HashMap getParams() {
    return params;
  }

  @Override
  public void run() {
    try {
      URL url = new URL(config.url);
      URLConnection urlConnection = url.openConnection();
      Map<String, List<String>> headers = urlConnection.getHeaderFields();

      String id = config.id;
      WritableMap headersMap = convertHeadersToWritableMap(headers);
      long expectedBytes = getContentLength(headersMap);

      WritableMap map = Arguments.createMap();
      map.putString("id", id);
      map.putMap("headers", headersMap);
      map.putDouble("expectedBytes", expectedBytes);

      params = map.toHashMap();
      callback.onBegin(config.id, headersMap, expectedBytes);
    } catch (Exception e) {
      Log.e("RNBackgroundDownloader", "OnBegin: " + Log.getStackTraceString(e));
    }
  }

  private WritableMap convertHeadersToWritableMap(Map<String, List<String>> headers) {
    WritableMap headersMap = Arguments.createMap();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      String key = entry.getKey();
      List<String> values = entry.getValue();
      if (values != null && !values.isEmpty()) {
        headersMap.putString(key, values.get(0));
      }
    }
    return headersMap;
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

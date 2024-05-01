package com.eko;

import android.util.Log;

import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;

import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;

public class OnBegin extends Thread {
  private final RNBGDTaskConfig config;
  private final DeviceEventManagerModule.RCTDeviceEventEmitter ee;

  public OnBegin(RNBGDTaskConfig config, DeviceEventManagerModule.RCTDeviceEventEmitter ee) {
    this.config = config;
    this.ee = ee;
  }

  @Override
  public void run() {
    try {
      URL url = new URL(config.url);
      URLConnection urlConnection = url.openConnection();
      Map<String, List<String>> headers = urlConnection.getHeaderFields();
      WritableMap headersMap = convertHeadersToWritableMap(headers);
      long contentLength = getContentLength(headersMap);

      WritableMap params = createParams(contentLength, headersMap);
      ee.emit("downloadBegin", params);
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
    if (headersMap != null && headersMap.hasKey("Content-Length")) {
      return Long.valueOf(headersMap.getString("Content-Length"));
    }
    return -1;
  }

  private WritableMap createParams(long contentLength, WritableMap headersMap) {
    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putMap("headers", headersMap);
    params.putDouble("expectedBytes", contentLength);
    return params;
  }
}

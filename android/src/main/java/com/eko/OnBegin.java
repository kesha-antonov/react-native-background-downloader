package com.eko;

import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.eko.RNBGDTaskConfig;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;

public class OnBegin extends Thread {
  private RNBGDTaskConfig config;
  private DeviceEventManagerModule.RCTDeviceEventEmitter ee;

  public OnBegin(RNBGDTaskConfig config,
      DeviceEventManagerModule.RCTDeviceEventEmitter ee) {
    this.config = config;
    this.ee = ee;
  }

  @Override
  public void run() {
    try {
      WritableMap headersMap = Arguments.createMap();

      URL urlC = new URL(config.url);
      URLConnection con = urlC.openConnection();
      Map<String, List<String>> headers = con.getHeaderFields();
      Set<String> keys = headers.keySet();
      for (String key : keys) {
        String val = con.getHeaderField(key);
        headersMap.putString(key, val);
      }
      con.getInputStream().close();

      WritableMap params = Arguments.createMap();
      int contentLength = Integer.valueOf(headersMap.getString("Content-Length"));

      params.putString("id", config.id);
      params.putMap("headers", headersMap);
      params.putInt("expectedBytes", contentLength);

      ee.emit("downloadBegin", params);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}

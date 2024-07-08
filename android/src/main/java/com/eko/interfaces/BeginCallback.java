package com.eko.interfaces;

import com.facebook.react.bridge.WritableMap;

public interface BeginCallback {
    void onBegin(String configId, WritableMap headers, long expectedBytes);
}

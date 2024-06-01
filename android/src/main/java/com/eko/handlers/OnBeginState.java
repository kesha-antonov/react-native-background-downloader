package com.eko.handlers;

import com.facebook.react.bridge.WritableMap;

import java.io.Serializable;

public class OnBeginState implements Serializable {
    public String id;
    public WritableMap headers;
    public long expectedBytes;
    public OnBeginState(String id, WritableMap headers, long expectedBytes) {
        this.id = id;
        this.headers = headers;
        this.expectedBytes = expectedBytes;
    }
}

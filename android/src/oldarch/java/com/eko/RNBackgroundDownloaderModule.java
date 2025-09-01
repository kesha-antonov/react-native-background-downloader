package com.eko;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Old Architecture (Bridge) implementation
 * Explicitly declares all ReactMethod methods to make them visible to React Native's
 * reflection-based method discovery (fixes issue #79)
 */
public class RNBackgroundDownloaderModule extends RNBackgroundDownloaderModuleImpl {

    public RNBackgroundDownloaderModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    @NonNull
    public String getName() {
        return super.getName();
    }

    @Override
    @ReactMethod
    public void checkForExistingDownloads(Promise promise) {
        super.checkForExistingDownloads(promise);
    }

    @Override
    @ReactMethod
    public void completeHandler(String configId) {
        super.completeHandler(configId);
    }

    @Override
    @ReactMethod
    public void download(ReadableMap options) {
        super.download(options);
    }

    @Override
    @ReactMethod
    public void pauseTask(String configId) {
        super.pauseTask(configId);
    }

    @Override
    @ReactMethod
    public void resumeTask(String configId) {
        super.resumeTask(configId);
    }

    @Override
    @ReactMethod
    public void stopTask(String configId) {
        super.stopTask(configId);
    }

    @Override
    @ReactMethod
    public void addListener(String eventName) {
        super.addListener(eventName);
    }

    @Override
    @ReactMethod
    public void removeListeners(Integer count) {
        super.removeListeners(count);
    }

    @Override
    @Nullable
    public Map<String, Object> getConstants() {
        return super.getConstants();
    }

    @Override
    public void initialize() {
        super.initialize();
    }

    @Override
    public void invalidate() {
        super.invalidate();
    }
}
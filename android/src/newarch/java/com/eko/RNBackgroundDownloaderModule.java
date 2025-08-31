package com.eko;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * New Architecture (TurboModule) implementation that delegates to the base module
 */
public class RNBackgroundDownloaderModule extends NativeRNBackgroundDownloaderSpec {

    private final RNBackgroundDownloaderModuleImpl mModuleImpl;

    public RNBackgroundDownloaderModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mModuleImpl = new RNBackgroundDownloaderModuleImpl(reactContext);
    }

    @Override
    @NonNull
    public String getName() {
        return RNBackgroundDownloaderModuleImpl.NAME;
    }

    @Override
    public void checkForExistingDownloads(Promise promise) {
        mModuleImpl.checkForExistingDownloads(promise);
    }

    @Override
    public void completeHandler(String id) {
        mModuleImpl.completeHandler(id);
    }

    @Override
    public void download(ReadableMap options) {
        mModuleImpl.download(options);
    }

    @Override
    public void pauseTask(String configId) {
        mModuleImpl.pauseTask(configId);
    }

    @Override
    public void resumeTask(String configId) {
        mModuleImpl.resumeTask(configId);
    }

    @Override
    public void stopTask(String configId) {
        mModuleImpl.stopTask(configId);
    }

    @Override
    public void addListener(String eventName) {
        mModuleImpl.addListener(eventName);
    }

    @Override
    public void removeListeners(Integer count) {
        mModuleImpl.removeListeners(count);
    }

    @Override
    @Nullable
    public Map<String, Object> getConstants() {
        return mModuleImpl.getConstants();
    }

    @Override
    public void initialize() {
        super.initialize();
        mModuleImpl.initialize();
    }

    @Override
    public void invalidate() {
        mModuleImpl.invalidate();
        super.invalidate();
    }
}
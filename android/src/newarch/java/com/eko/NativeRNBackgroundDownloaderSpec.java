package com.eko;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;

/**
 * Abstract base class for the TurboModule spec
 * This will be extended by the generated spec class
 */
public abstract class NativeRNBackgroundDownloaderSpec extends com.facebook.react.bridge.ReactContextBaseJavaModule {

    public NativeRNBackgroundDownloaderSpec(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @NonNull
    @Override
    public abstract String getName();

    @ReactMethod
    public abstract void checkForExistingDownloads(Promise promise);

    @ReactMethod
    public abstract void completeHandler(String id);

    @ReactMethod
    public abstract void download(ReadableMap options);

    @ReactMethod
    public abstract void pauseTask(String configId);

    @ReactMethod
    public abstract void resumeTask(String configId);

    @ReactMethod
    public abstract void stopTask(String configId);

    @ReactMethod
    public abstract void addListener(String eventName);

    @ReactMethod
    public abstract void removeListeners(Integer count);
}
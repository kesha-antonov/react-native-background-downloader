package com.eko

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap

/**
 * Abstract base class for the TurboModule spec
 * This will be extended by the generated spec class
 */
abstract class NativeRNBackgroundDownloaderSpec(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    abstract override fun getName(): String

    @ReactMethod
    abstract fun checkForExistingDownloads(promise: Promise)

    @ReactMethod
    abstract fun completeHandler(id: String)

    @ReactMethod
    abstract fun download(options: ReadableMap)

    @ReactMethod
    abstract fun pauseTask(configId: String)

    @ReactMethod
    abstract fun resumeTask(configId: String)

    @ReactMethod
    abstract fun stopTask(configId: String)

    @ReactMethod
    abstract fun addListener(eventName: String)

    @ReactMethod
    abstract fun removeListeners(count: Int)
}

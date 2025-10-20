package com.eko

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap

/**
 * Old Architecture (Bridge) implementation
 * Explicitly declares all ReactMethod methods to make them visible to React Native's
 * reflection-based method discovery (fixes issue #79)
 */
class RNBackgroundDownloaderModule(reactContext: ReactApplicationContext) :
    RNBackgroundDownloaderModuleImpl(reactContext) {

    override fun getName(): String = super.getName()

    @ReactMethod
    override fun checkForExistingDownloads(promise: Promise) {
        super.checkForExistingDownloads(promise)
    }

    @ReactMethod
    override fun completeHandler(configId: String) {
        super.completeHandler(configId)
    }

    @ReactMethod
    override fun download(options: ReadableMap) {
        super.download(options)
    }

    @ReactMethod
    override fun pauseTask(configId: String) {
        super.pauseTask(configId)
    }

    @ReactMethod
    override fun resumeTask(configId: String) {
        super.resumeTask(configId)
    }

    @ReactMethod
    override fun stopTask(configId: String) {
        super.stopTask(configId)
    }

    @ReactMethod
    override fun addListener(eventName: String) {
        super.addListener(eventName)
    }

    @ReactMethod
    override fun removeListeners(count: Int) {
        super.removeListeners(count)
    }

    override fun getConstants(): Map<String, Any>? = super.getConstants()

    override fun initialize() {
        super.initialize()
    }

    override fun invalidate() {
        super.invalidate()
    }
}

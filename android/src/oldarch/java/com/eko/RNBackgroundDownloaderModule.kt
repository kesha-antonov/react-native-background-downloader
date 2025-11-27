package com.eko

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = RNBackgroundDownloaderModuleImpl.NAME)
class RNBackgroundDownloaderModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val impl = RNBackgroundDownloaderModuleImpl(reactContext)

    override fun getName(): String = RNBackgroundDownloaderModuleImpl.NAME

    override fun getConstants(): Map<String, Any>? = impl.getConstants()

    override fun initialize() {
        super.initialize()
        impl.initialize()
    }

    override fun invalidate() {
        impl.invalidate()
        super.invalidate()
    }

    @ReactMethod
    fun download(options: ReadableMap) {
        impl.download(options)
    }

    @ReactMethod
    fun pauseTask(id: String) {
        impl.pauseTask(id)
    }

    @ReactMethod
    fun resumeTask(id: String) {
        impl.resumeTask(id)
    }

    @ReactMethod
    fun stopTask(id: String) {
        impl.stopTask(id)
    }

    @ReactMethod
    fun completeHandler(jobId: String, promise: Promise) {
        impl.completeHandler(jobId)
        promise.resolve(null)
    }

    @ReactMethod
    fun getExistingDownloadTasks(promise: Promise) {
        impl.getExistingDownloadTasks(promise)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        impl.addListener(eventName)
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        impl.removeListeners(count)
    }
}

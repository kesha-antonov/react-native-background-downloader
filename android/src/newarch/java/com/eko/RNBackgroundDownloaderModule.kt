package com.eko

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = RNBackgroundDownloaderModuleImpl.NAME)
class RNBackgroundDownloaderModule(reactContext: ReactApplicationContext) :
    NativeRNBackgroundDownloaderSpec(reactContext) {

    private val impl = RNBackgroundDownloaderModuleImpl(reactContext)

    override fun getName(): String = RNBackgroundDownloaderModuleImpl.NAME

    override fun getTypedExportedConstants(): Map<String, Any>? {
        return impl.constants
    }

    override fun initialize() {
        super.initialize()
        impl.initialize()
    }

    override fun invalidate() {
        impl.invalidate()
        super.invalidate()
    }

    override fun download(options: com.facebook.react.bridge.ReadableMap) {
        impl.download(options)
    }

    override fun pauseTask(id: String, promise: com.facebook.react.bridge.Promise) {
        impl.pauseTask(id)
        promise.resolve(null)
    }

    override fun resumeTask(id: String, promise: com.facebook.react.bridge.Promise) {
        impl.resumeTask(id)
        promise.resolve(null)
    }

    override fun stopTask(id: String, promise: com.facebook.react.bridge.Promise) {
        impl.stopTask(id)
        promise.resolve(null)
    }

    override fun completeHandler(jobId: String, promise: com.facebook.react.bridge.Promise) {
        impl.completeHandler(jobId)
        promise.resolve(null)
    }

    override fun getExistingDownloadTasks(promise: com.facebook.react.bridge.Promise) {
        impl.getExistingDownloadTasks(promise)
    }

    override fun addListener(eventName: String) {
        impl.addListener(eventName)
    }

    override fun removeListeners(count: Double) {
        impl.removeListeners(count.toInt())
    }
}

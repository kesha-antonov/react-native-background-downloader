package com.eko

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = RNBackgroundDownloaderModuleImpl.NAME)
class RNBackgroundDownloaderModule(reactContext: ReactApplicationContext) :
    NativeRNBackgroundDownloaderSpec(reactContext) {

    private val impl = RNBackgroundDownloaderModuleImpl(reactContext)

    override fun getName(): String = RNBackgroundDownloaderModuleImpl.NAME

    override fun getTypedExportedConstants(): Map<String, Any>? {
        return impl.getConstants()
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
        try {
            impl.pauseTask(id)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_PAUSE_TASK", e.message, e)
        }
    }

    override fun resumeTask(id: String, promise: com.facebook.react.bridge.Promise) {
        try {
            impl.resumeTask(id)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_RESUME_TASK", e.message, e)
        }
    }

    override fun stopTask(id: String, promise: com.facebook.react.bridge.Promise) {
        try {
            impl.stopTask(id)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_STOP_TASK", e.message, e)
        }
    }

    override fun completeHandler(jobId: String, promise: com.facebook.react.bridge.Promise) {
        try {
            impl.completeHandler(jobId)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_COMPLETE_HANDLER", e.message, e)
        }
    }

    override fun getExistingDownloadTasks(promise: com.facebook.react.bridge.Promise) {
        try {
            impl.getExistingDownloadTasks(promise)
        } catch (e: Exception) {
            promise.reject("ERR_GET_EXISTING_TASKS", e.message, e)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        impl.addListener(eventName)
    }

    @ReactMethod
    fun removeListeners(count: Double) {
        impl.removeListeners(count.toInt())
    }
}

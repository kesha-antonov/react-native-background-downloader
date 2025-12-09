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
    fun pauseTask(id: String, promise: Promise) {
        try {
            impl.pauseTask(id)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_PAUSE_TASK", e.message, e)
        }
    }

    @ReactMethod
    fun resumeTask(id: String, promise: Promise) {
        try {
            impl.resumeTask(id)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_RESUME_TASK", e.message, e)
        }
    }

    @ReactMethod
    fun stopTask(id: String, promise: Promise) {
        try {
            impl.stopTask(id)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_STOP_TASK", e.message, e)
        }
    }

    @ReactMethod
    fun completeHandler(jobId: String, promise: Promise) {
        try {
            impl.completeHandler(jobId)
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("ERR_COMPLETE_HANDLER", e.message, e)
        }
    }

    @ReactMethod
    fun getExistingDownloadTasks(promise: Promise) {
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
    fun removeListeners(count: Int) {
        impl.removeListeners(count)
    }
}

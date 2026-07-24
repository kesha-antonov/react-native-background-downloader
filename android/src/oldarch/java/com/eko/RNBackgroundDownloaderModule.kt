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
    fun pauseTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_PAUSE_TASK") { impl.pauseTask(id) }

    @ReactMethod
    fun resumeTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_RESUME_TASK") { impl.resumeTask(id) }

    @ReactMethod
    fun stopTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_STOP_TASK") { impl.stopTask(id) }

    @ReactMethod
    fun updateTaskHeaders(id: String, headers: ReadableMap, promise: Promise) {
        impl.updateTaskHeaders(id, headers, promise)
    }

    @ReactMethod
    fun completeHandler(jobId: String, promise: Promise) =
        promise.resolveCatching("ERR_COMPLETE_HANDLER") { impl.completeHandler(jobId) }

    @ReactMethod
    fun getExistingDownloadTasks(promise: Promise) =
        promise.rejectOnThrow("ERR_GET_EXISTING_TASKS") { impl.getExistingDownloadTasks(promise) }

    @ReactMethod
    fun setLogsEnabled(enabled: Boolean) {
        impl.setLogsEnabled(enabled)
    }

    @ReactMethod
    fun setMaxParallelDownloads(max: Int) {
        impl.setMaxParallelDownloads(max)
    }

    @ReactMethod
    fun setAllowsCellularAccess(allows: Boolean) {
        impl.setAllowsCellularAccess(allows)
    }

    @ReactMethod
    fun setNotificationGroupingConfig(config: ReadableMap) {
        impl.setNotificationGroupingConfig(config)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        impl.addListener(eventName)
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        impl.removeListeners(count)
    }

    // ============= Upload methods =============

    @ReactMethod
    fun upload(options: ReadableMap) {
        impl.upload(options)
    }

    @ReactMethod
    fun pauseUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_PAUSE_UPLOAD_TASK") { impl.pauseUploadTask(id) }

    @ReactMethod
    fun resumeUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_RESUME_UPLOAD_TASK") { impl.resumeUploadTask(id) }

    @ReactMethod
    fun stopUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_STOP_UPLOAD_TASK") { impl.stopUploadTask(id) }

    @ReactMethod
    fun getExistingUploadTasks(promise: Promise) =
        promise.rejectOnThrow("ERR_GET_EXISTING_UPLOAD_TASKS") { impl.getExistingUploadTasks(promise) }
}

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

    override fun pauseTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_PAUSE_TASK") { impl.pauseTask(id) }

    override fun resumeTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_RESUME_TASK") { impl.resumeTask(id) }

    override fun stopTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_STOP_TASK") { impl.stopTask(id) }

    override fun updateTaskHeaders(id: String, headers: com.facebook.react.bridge.ReadableMap, promise: com.facebook.react.bridge.Promise) {
        impl.updateTaskHeaders(id, headers, promise)
    }

    override fun completeHandler(jobId: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_COMPLETE_HANDLER") { impl.completeHandler(jobId) }

    override fun getExistingDownloadTasks(promise: com.facebook.react.bridge.Promise) =
        promise.rejectOnThrow("ERR_GET_EXISTING_TASKS") { impl.getExistingDownloadTasks(promise) }

    override fun setLogsEnabled(enabled: Boolean) {
        impl.setLogsEnabled(enabled)
    }

    override fun setMaxParallelDownloads(max: Double) {
        impl.setMaxParallelDownloads(max.toInt())
    }

    override fun setAllowsCellularAccess(allows: Boolean) {
        impl.setAllowsCellularAccess(allows)
    }

    override fun setNotificationGroupingConfig(config: com.facebook.react.bridge.ReadableMap?) {
        if (config != null) {
            impl.setNotificationGroupingConfig(config)
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

    // ============= Upload methods =============

    override fun upload(options: com.facebook.react.bridge.ReadableMap) {
        impl.upload(options)
    }

    override fun pauseUploadTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_PAUSE_UPLOAD_TASK") { impl.pauseUploadTask(id) }

    override fun resumeUploadTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_RESUME_UPLOAD_TASK") { impl.resumeUploadTask(id) }

    override fun stopUploadTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_STOP_UPLOAD_TASK") { impl.stopUploadTask(id) }

    override fun getExistingUploadTasks(promise: com.facebook.react.bridge.Promise) =
        promise.rejectOnThrow("ERR_GET_EXISTING_UPLOAD_TASKS") { impl.getExistingUploadTasks(promise) }
}

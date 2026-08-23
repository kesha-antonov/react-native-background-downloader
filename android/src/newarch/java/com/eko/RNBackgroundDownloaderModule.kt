package com.eko

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = RNBackgroundDownloaderModuleImpl.NAME)
class RNBackgroundDownloaderModule(reactContext: ReactApplicationContext) :
    NativeRNBackgroundDownloaderSpec(reactContext) {

    private val impl = RNBackgroundDownloaderModuleImpl.getInstance(reactContext)
    private var bindingToken: Long? = null

    override fun getName(): String = RNBackgroundDownloaderModuleImpl.NAME

    override fun getTypedExportedConstants(): Map<String, Any>? {
        return impl.getConstants()
    }

    override fun initialize() {
        super.initialize()
        bindingToken = impl.initialize(reactApplicationContext)
    }

    override fun invalidate() {
        bindingToken?.let(impl::invalidate)
        bindingToken = null
        super.invalidate()
    }

    private fun isRuntimeActive(): Boolean =
        bindingToken?.let(impl::isBindingTokenCurrent) == true

    override fun setRuntimeReady(promise: com.facebook.react.bridge.Promise) =
        promise.resolveValueCatching("ERR_SET_RUNTIME_READY", ::isRuntimeActive) {
            bindingToken?.let(impl::setRuntimeReady) ?: com.facebook.react.bridge.Arguments.createArray()
        }

    override fun acknowledgeRuntimeEvents(keys: com.facebook.react.bridge.ReadableArray) {
        if (isRuntimeActive()) bindingToken?.let { impl.acknowledgeRuntimeEvents(it, keys) }
    }

    override fun download(options: com.facebook.react.bridge.ReadableMap) {
        if (isRuntimeActive()) impl.download(options)
    }

    override fun pauseTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_PAUSE_TASK", ::isRuntimeActive) { impl.pauseTask(id) }

    override fun resumeTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_RESUME_TASK", ::isRuntimeActive) { impl.resumeTask(id) }

    override fun stopTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_STOP_TASK", ::isRuntimeActive) { impl.stopTask(id) }

    override fun updateTaskHeaders(id: String, headers: com.facebook.react.bridge.ReadableMap, promise: com.facebook.react.bridge.Promise) {
        promise.resolveValueCatching("ERR_UPDATE_HEADERS", ::isRuntimeActive) { impl.updateTaskHeaders(id, headers) }
    }

    override fun getExistingDownloadTasks(promise: com.facebook.react.bridge.Promise) =
        promise.resolveValueCatching("ERR_GET_EXISTING_TASKS", ::isRuntimeActive) { impl.getExistingDownloadTasks() }

    override fun setLogsEnabled(enabled: Boolean) {
        if (isRuntimeActive()) impl.setLogsEnabled(enabled)
    }

    override fun setMaxParallelDownloads(max: Double) {
        if (isRuntimeActive()) impl.setMaxParallelDownloads(max.toInt())
    }

    override fun setAllowsCellularAccess(allows: Boolean) {
        if (isRuntimeActive()) impl.setAllowsCellularAccess(allows)
    }

    override fun setNotificationGroupingConfig(config: com.facebook.react.bridge.ReadableMap?) {
        if (config != null && isRuntimeActive()) {
            impl.setNotificationGroupingConfig(config)
        }
    }

    override fun addListener(eventName: String) {
        if (isRuntimeActive()) impl.addListener(eventName)
    }

    override fun removeListeners(count: Double) {
        if (isRuntimeActive()) impl.removeListeners(count.toInt())
    }

    // ============= Upload methods =============

    override fun upload(options: com.facebook.react.bridge.ReadableMap) {
        if (isRuntimeActive()) impl.upload(options)
    }

    override fun pauseUploadTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_PAUSE_UPLOAD_TASK", ::isRuntimeActive) { impl.pauseUploadTask(id) }

    override fun resumeUploadTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_RESUME_UPLOAD_TASK", ::isRuntimeActive) { impl.resumeUploadTask(id) }

    override fun stopUploadTask(id: String, promise: com.facebook.react.bridge.Promise) =
        promise.resolveCatching("ERR_STOP_UPLOAD_TASK", ::isRuntimeActive) { impl.stopUploadTask(id) }

    override fun getExistingUploadTasks(promise: com.facebook.react.bridge.Promise) =
        promise.resolveValueCatching("ERR_GET_EXISTING_UPLOAD_TASKS", ::isRuntimeActive) { impl.getExistingUploadTasks() }
}

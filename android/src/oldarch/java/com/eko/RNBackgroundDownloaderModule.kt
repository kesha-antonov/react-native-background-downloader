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

    private val impl = RNBackgroundDownloaderModuleImpl.getInstance(reactContext)
    private var bindingToken: Long? = null

    override fun getName(): String = RNBackgroundDownloaderModuleImpl.NAME

    override fun getConstants(): Map<String, Any>? = impl.getConstants()

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

    @ReactMethod
    fun setRuntimeReady(promise: Promise) =
        promise.resolveValueCatching("ERR_SET_RUNTIME_READY", ::isRuntimeActive) {
            bindingToken?.let(impl::setRuntimeReady) ?: com.facebook.react.bridge.Arguments.createArray()
        }

    @ReactMethod
    fun acknowledgeRuntimeEvents(keys: com.facebook.react.bridge.ReadableArray) {
        if (isRuntimeActive()) bindingToken?.let { impl.acknowledgeRuntimeEvents(it, keys) }
    }

    @ReactMethod
    fun download(options: ReadableMap) {
        if (isRuntimeActive()) impl.download(options)
    }

    @ReactMethod
    fun pauseTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_PAUSE_TASK", ::isRuntimeActive) { impl.pauseTask(id) }

    @ReactMethod
    fun resumeTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_RESUME_TASK", ::isRuntimeActive) { impl.resumeTask(id) }

    @ReactMethod
    fun stopTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_STOP_TASK", ::isRuntimeActive) { impl.stopTask(id) }

    @ReactMethod
    fun updateTaskHeaders(id: String, headers: ReadableMap, promise: Promise) {
        promise.resolveValueCatching("ERR_UPDATE_HEADERS", ::isRuntimeActive) { impl.updateTaskHeaders(id, headers) }
    }

    @ReactMethod
    fun getExistingDownloadTasks(promise: Promise) =
        promise.resolveValueCatching("ERR_GET_EXISTING_TASKS", ::isRuntimeActive) { impl.getExistingDownloadTasks() }

    @ReactMethod
    fun setLogsEnabled(enabled: Boolean) {
        if (isRuntimeActive()) impl.setLogsEnabled(enabled)
    }

    @ReactMethod
    fun setMaxParallelDownloads(max: Int) {
        if (isRuntimeActive()) impl.setMaxParallelDownloads(max)
    }

    @ReactMethod
    fun setAllowsCellularAccess(allows: Boolean) {
        if (isRuntimeActive()) impl.setAllowsCellularAccess(allows)
    }

    @ReactMethod
    fun setNotificationGroupingConfig(config: ReadableMap) {
        if (isRuntimeActive()) impl.setNotificationGroupingConfig(config)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        if (isRuntimeActive()) impl.addListener(eventName)
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        if (isRuntimeActive()) impl.removeListeners(count)
    }

    // ============= Upload methods =============

    @ReactMethod
    fun upload(options: ReadableMap) {
        if (isRuntimeActive()) impl.upload(options)
    }

    @ReactMethod
    fun pauseUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_PAUSE_UPLOAD_TASK", ::isRuntimeActive) { impl.pauseUploadTask(id) }

    @ReactMethod
    fun resumeUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_RESUME_UPLOAD_TASK", ::isRuntimeActive) { impl.resumeUploadTask(id) }

    @ReactMethod
    fun stopUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_STOP_UPLOAD_TASK", ::isRuntimeActive) { impl.stopUploadTask(id) }

    @ReactMethod
    fun getExistingUploadTasks(promise: Promise) =
        promise.resolveValueCatching("ERR_GET_EXISTING_UPLOAD_TASKS", ::isRuntimeActive) { impl.getExistingUploadTasks() }
}

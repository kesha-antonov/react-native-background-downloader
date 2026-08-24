package com.eko

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule

@ReactModule(name = RNBackgroundDownloaderModuleImpl.NAME)
class RNBackgroundDownloaderModule(reactContext: ReactApplicationContext) :
    NativeRNBackgroundDownloaderSpec(reactContext) {

    private val impl = RNBackgroundDownloaderModuleImpl.getInstance(reactContext)
    private var bindingToken: Long? = null

    override fun getName(): String = RNBackgroundDownloaderModuleImpl.NAME

    override fun getTypedExportedConstants(): Map<String, Any>? = impl.getConstants()

    override fun initialize() {
        super.initialize()
        bindingToken = impl.initialize(::emitEvent)
    }

    override fun invalidate() {
        bindingToken?.let(impl::invalidate)
        bindingToken = null
        super.invalidate()
    }

    private fun isRuntimeActive(): Boolean =
        bindingToken?.let(impl::isBindingTokenCurrent) == true

    override fun setRuntimeReady(family: String, promise: Promise) =
        promise.resolveValueCatching("ERR_SET_RUNTIME_READY", ::isRuntimeActive) {
            bindingToken?.let { impl.setRuntimeReady(it, family) } ?: Arguments.createArray()
        }

    override fun acknowledgeRuntimeEvents(keys: ReadableArray) {
        if (isRuntimeActive()) bindingToken?.let { impl.acknowledgeRuntimeEvents(it, keys) }
    }

    override fun download(options: ReadableMap) {
        if (isRuntimeActive()) impl.download(options)
    }

    override fun pauseTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_PAUSE_TASK", ::isRuntimeActive) { impl.pauseTask(id) }

    override fun resumeTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_RESUME_TASK", ::isRuntimeActive) { impl.resumeTask(id) }

    override fun stopTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_STOP_TASK", ::isRuntimeActive) { impl.stopTask(id) }

    override fun getExistingDownloadTasks(promise: Promise) =
        promise.resolveValueCatching("ERR_GET_EXISTING_TASKS", ::isRuntimeActive) {
            bindingToken?.let { impl.prepareRuntimeReconciliation(it, "download") }
            impl.getExistingDownloadTasks()
        }

    override fun upload(options: ReadableMap) {
        if (isRuntimeActive()) impl.upload(options)
    }

    override fun pauseUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_PAUSE_UPLOAD_TASK", ::isRuntimeActive) { impl.pauseUploadTask(id) }

    override fun resumeUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_RESUME_UPLOAD_TASK", ::isRuntimeActive) { impl.resumeUploadTask(id) }

    override fun stopUploadTask(id: String, promise: Promise) =
        promise.resolveCatching("ERR_STOP_UPLOAD_TASK", ::isRuntimeActive) { impl.stopUploadTask(id) }

    override fun getExistingUploadTasks(promise: Promise) =
        promise.resolveValueCatching("ERR_GET_EXISTING_UPLOAD_TASKS", ::isRuntimeActive) {
            bindingToken?.let { impl.prepareRuntimeReconciliation(it, "upload") }
            impl.getExistingUploadTasks()
        }

    private fun emitEvent(name: String, payload: Any?) {
        when (name) {
            "downloadBegin" -> emitOnDownloadBegin(payload as ReadableMap)
            "downloadProgress" -> emitOnDownloadProgress(payload as ReadableArray)
            "downloadComplete" -> emitOnDownloadComplete(payload as ReadableMap)
            "downloadFailed" -> emitOnDownloadFailed(payload as ReadableMap)
            "uploadBegin" -> emitOnUploadBegin(payload as ReadableMap)
            "uploadProgress" -> emitOnUploadProgress(payload as ReadableArray)
            "uploadComplete" -> emitOnUploadComplete(payload as ReadableMap)
            "uploadFailed" -> emitOnUploadFailed(payload as ReadableMap)
        }
    }
}

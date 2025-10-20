package com.eko

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap

/**
 * New Architecture (TurboModule) implementation that delegates to the base module
 */
class RNBackgroundDownloaderModule(reactContext: ReactApplicationContext) :
    NativeRNBackgroundDownloaderSpec(reactContext) {

    private val mModuleImpl: RNBackgroundDownloaderModuleImpl = RNBackgroundDownloaderModuleImpl(reactContext)

    override fun getName(): String = RNBackgroundDownloaderModuleImpl.NAME

    override fun checkForExistingDownloads(promise: Promise) {
        mModuleImpl.checkForExistingDownloads(promise)
    }

    override fun completeHandler(id: String) {
        mModuleImpl.completeHandler(id)
    }

    override fun download(options: ReadableMap) {
        mModuleImpl.download(options)
    }

    override fun pauseTask(configId: String) {
        mModuleImpl.pauseTask(configId)
    }

    override fun resumeTask(configId: String) {
        mModuleImpl.resumeTask(configId)
    }

    override fun stopTask(configId: String) {
        mModuleImpl.stopTask(configId)
    }

    override fun addListener(eventName: String) {
        mModuleImpl.addListener(eventName)
    }

    override fun removeListeners(count: Int) {
        mModuleImpl.removeListeners(count)
    }

    override fun getConstants(): Map<String, Any>? = mModuleImpl.getConstants()

    override fun initialize() {
        super.initialize()
        mModuleImpl.initialize()
    }

    override fun invalidate() {
        mModuleImpl.invalidate()
        super.invalidate()
    }
}

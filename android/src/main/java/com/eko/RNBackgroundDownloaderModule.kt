package com.eko

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule


@ReactModule(name = RNBackgroundDownloaderModule.NAME)
class RNBackgroundDownloaderModule(val reactContext: ReactApplicationContext) :
  NativeRNBackgroundDownloaderSpec(reactContext) {

  private val mModuleImpl = RNBackgroundDownloaderModuleImpl(reactContext);

  override fun getName(): String {
    return NAME
  }

  override fun checkForExistingDownloads(promise: Promise) {
    mModuleImpl.checkForExistingDownloads(promise)
  }

  override fun completeHandler(id: String?) {
    mModuleImpl.completeHandler(id)
  }

  override fun download(options: ReadableMap) {
    mModuleImpl.download(options)
  }

  override fun pauseTask(configId: String?) {
    mModuleImpl.pauseTask(configId)
  }

  override fun resumeTask(configId: String?) {
    mModuleImpl.resumeTask(configId)
  }

  override fun stopTask(configId: String?) {
    mModuleImpl.stopTask(configId)
  }

  override fun addListener(eventName: String?) {
    mModuleImpl.addListener(eventName)
  }

  override fun getTypedExportedConstants(): Map<String?, Any?>? {
    return mModuleImpl.constants
  }

  override fun initialize() {
    super.initialize()
    mModuleImpl.initialize()
  }

  override fun invalidate() {
    mModuleImpl.invalidate()
    super.invalidate()
  }

  companion object {
    const val NAME = "RNBackgroundDownloader"
  }
}

package com.eko

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * Centralized event emitter for download events to JavaScript.
 * This consolidates all JS event emission logic to ensure consistent
 * event structure and reduce code duplication.
 */
class DownloadEventEmitter(
    private val getEmitter: () -> DeviceEventManagerModule.RCTDeviceEventEmitter
) {

    companion object {
        private const val TAG = "DownloadEventEmitter"
        // Event names
        const val EVENT_DOWNLOAD_BEGIN = "downloadBegin"
        const val EVENT_DOWNLOAD_PROGRESS = "downloadProgress"
        const val EVENT_DOWNLOAD_COMPLETE = "downloadComplete"
        const val EVENT_DOWNLOAD_FAILED = "downloadFailed"
    }

    /**
     * Safely emit an event, handling cases where the emitter might not be ready.
     */
    private fun safeEmit(eventName: String, params: WritableMap) {
        try {
            getEmitter().emit(eventName, params)
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to emit $eventName event: ${e.message}")
        }
    }

    /**
     * Emit a download begin event.
     */
    fun emitBegin(id: String, headers: WritableMap, expectedBytes: Long) {
        val params = Arguments.createMap()
        params.putString("id", id)
        params.putMap("headers", headers)
        params.putDouble("expectedBytes", expectedBytes.toDouble())
        safeEmit(EVENT_DOWNLOAD_BEGIN, params)
    }

    /**
     * Emit a download complete event.
     */
    fun emitComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
        val params = Arguments.createMap()
        params.putString("id", id)
        params.putString("location", location)
        params.putDouble("bytesDownloaded", bytesDownloaded.toDouble())
        params.putDouble("bytesTotal", bytesTotal.toDouble())
        safeEmit(EVENT_DOWNLOAD_COMPLETE, params)
    }

    /**
     * Emit a download failed event.
     */
    fun emitFailed(id: String, error: String, errorCode: Int) {
        val params = Arguments.createMap()
        params.putString("id", id)
        params.putInt("errorCode", errorCode)
        params.putString("error", error)
        safeEmit(EVENT_DOWNLOAD_FAILED, params)
    }

}

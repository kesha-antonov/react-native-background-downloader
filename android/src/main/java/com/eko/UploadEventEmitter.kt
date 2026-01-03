package com.eko

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

/**
 * Centralized event emitter for upload events to JavaScript.
 * This consolidates all JS event emission logic to ensure consistent
 * event structure and reduce code duplication.
 */
class UploadEventEmitter(
    private val getEmitter: () -> DeviceEventManagerModule.RCTDeviceEventEmitter
) {

    companion object {
        private const val TAG = "UploadEventEmitter"
        // Event names
        const val EVENT_UPLOAD_BEGIN = "uploadBegin"
        const val EVENT_UPLOAD_PROGRESS = "uploadProgress"
        const val EVENT_UPLOAD_COMPLETE = "uploadComplete"
        const val EVENT_UPLOAD_FAILED = "uploadFailed"
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
     * Emit an upload begin event.
     */
    fun emitBegin(id: String, expectedBytes: Long) {
        val params = Arguments.createMap()
        params.putString("id", id)
        params.putDouble("expectedBytes", expectedBytes.toDouble())
        safeEmit(EVENT_UPLOAD_BEGIN, params)
    }

    /**
     * Emit an upload complete event.
     */
    fun emitComplete(id: String, responseCode: Int, responseBody: String, bytesUploaded: Long, bytesTotal: Long) {
        val params = Arguments.createMap()
        params.putString("id", id)
        params.putInt("responseCode", responseCode)
        params.putString("responseBody", responseBody)
        params.putDouble("bytesUploaded", bytesUploaded.toDouble())
        params.putDouble("bytesTotal", bytesTotal.toDouble())
        safeEmit(EVENT_UPLOAD_COMPLETE, params)
    }

    /**
     * Emit an upload failed event.
     */
    fun emitFailed(id: String, error: String, errorCode: Int) {
        val params = Arguments.createMap()
        params.putString("id", id)
        params.putInt("errorCode", errorCode)
        params.putString("error", error)
        safeEmit(EVENT_UPLOAD_FAILED, params)
    }

}

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
        // Event names
        const val EVENT_DOWNLOAD_BEGIN = "downloadBegin"
        const val EVENT_DOWNLOAD_PROGRESS = "downloadProgress"
        const val EVENT_DOWNLOAD_COMPLETE = "downloadComplete"
        const val EVENT_DOWNLOAD_FAILED = "downloadFailed"
    }

    /**
     * Emit a download begin event.
     */
    fun emitBegin(id: String, headers: WritableMap, expectedBytes: Long) {
        val params = Arguments.createMap()
        params.putString("id", id)
        params.putMap("headers", headers)
        params.putDouble("expectedBytes", expectedBytes.toDouble())
        getEmitter().emit(EVENT_DOWNLOAD_BEGIN, params)
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
        getEmitter().emit(EVENT_DOWNLOAD_COMPLETE, params)
    }

    /**
     * Emit a download complete event from a DownloadResult.Success.
     */
    fun emitComplete(result: DownloadResult.Success) {
        emitComplete(result.id, result.location, result.bytesDownloaded, result.bytesTotal)
    }

    /**
     * Emit a download failed event.
     */
    fun emitFailed(id: String, error: String, errorCode: Int) {
        val params = Arguments.createMap()
        params.putString("id", id)
        params.putInt("errorCode", errorCode)
        params.putString("error", error)
        getEmitter().emit(EVENT_DOWNLOAD_FAILED, params)
    }

    /**
     * Emit a download failed event from a DownloadResult.Error.
     */
    fun emitFailed(result: DownloadResult.Error) {
        emitFailed(result.id, result.message, result.errorCode)
    }

    /**
     * Emit a download failed event from an exception.
     */
    fun emitFailed(id: String, exception: Throwable, errorCode: Int = -1) {
        emitFailed(id, exception.message ?: "Unknown error", errorCode)
    }

    /**
     * Emit events based on a DownloadResult.
     * Returns true if an event was emitted (for terminal states).
     */
    fun emitForResult(result: DownloadResult): Boolean {
        return when (result) {
            is DownloadResult.Success -> {
                emitComplete(result)
                true
            }
            is DownloadResult.Error -> {
                emitFailed(result)
                true
            }
            is DownloadResult.Cancelled,
            is DownloadResult.Paused,
            is DownloadResult.SessionInvalidated -> {
                // No event emission for these states
                false
            }
        }
    }
}

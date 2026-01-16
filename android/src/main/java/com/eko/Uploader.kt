package com.eko

import android.content.Context
import android.util.Log
import com.eko.utils.HeaderUtils
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles file uploads with progress tracking and background support.
 * Uses HTTP/HTTPS with multipart form-data support.
 */
class Uploader(private val context: Context) {

    companion object {
        private const val TAG = "Uploader"
        private const val BUFFER_SIZE = 8192
        private const val CONNECT_TIMEOUT_MS = 30000
        private const val READ_TIMEOUT_MS = 30000
    }

    data class UploadState(
        val id: String,
        val config: RNBGDUploadTaskConfig,
        val isPaused: AtomicBoolean = AtomicBoolean(false),
        val isCancelled: AtomicBoolean = AtomicBoolean(false),
        val bytesUploaded: AtomicLong = AtomicLong(0),
        var bytesTotal: Long = 0,
        @Volatile var thread: Thread? = null,
        @Volatile var connection: HttpURLConnection? = null,
        var hasReportedBegin: Boolean = false
    )

    interface UploadListener {
        fun onBegin(id: String, expectedBytes: Long)
        fun onProgress(id: String, bytesUploaded: Long, bytesTotal: Long)
        fun onComplete(id: String, responseCode: Int, responseBody: String, bytesUploaded: Long, bytesTotal: Long)
        fun onError(id: String, error: String, errorCode: Int)
    }

    private val activeUploads = ConcurrentHashMap<String, UploadState>()
    private val executorService: ExecutorService = Executors.newCachedThreadPool()

    /**
     * Start a new upload.
     */
    fun startUpload(config: RNBGDUploadTaskConfig, listener: UploadListener) {
        // Cancel any existing upload with the same ID
        val existingState = activeUploads[config.id]
        if (existingState != null) {
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelling existing upload before starting new one: ${config.id}")
            existingState.isCancelled.set(true)
            try {
                existingState.connection?.disconnect()
            } catch (e: Exception) {
                RNBackgroundDownloaderModuleImpl.logW(TAG, "Error cleaning up existing upload: ${e.message}")
            }
            existingState.thread?.interrupt()
            activeUploads.remove(config.id)
        }

        val file = File(config.source)
        if (!file.exists()) {
            listener.onError(config.id, "Source file not found: ${config.source}", -1)
            return
        }

        val state = UploadState(
            id = config.id,
            config = config,
            bytesTotal = file.length()
        )
        activeUploads[config.id] = state

        val thread = Thread {
            executeUpload(state, listener)
        }
        state.thread = thread
        executorService.submit(thread)
    }

    /**
     * Pause an active upload.
     * Note: HTTP uploads cannot truly be paused/resumed without server support.
     * This cancels the upload but preserves state.
     */
    fun pause(id: String): Boolean {
        val state = activeUploads[id] ?: return false
        state.isPaused.set(true)
        try {
            state.connection?.disconnect()
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Connection disconnected during pause: ${e.message}")
        }
        state.thread?.interrupt()
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Paused upload: $id at ${state.bytesUploaded.get()} bytes")
        return true
    }

    /**
     * Resume a paused upload (restarts from beginning since HTTP upload resume is not standard).
     */
    fun resume(id: String, listener: UploadListener): Boolean {
        val state = activeUploads[id] ?: return false
        if (!state.isPaused.get()) {
            RNBackgroundDownloaderModuleImpl.logW(TAG, "Upload $id is not paused")
            return false
        }

        state.isPaused.set(false)
        state.bytesUploaded.set(0) // Reset since we restart
        state.hasReportedBegin = false

        val thread = Thread {
            executeUpload(state, listener)
        }
        state.thread = thread
        executorService.submit(thread)

        RNBackgroundDownloaderModuleImpl.logD(TAG, "Resuming upload: $id (restarting from beginning)")
        return true
    }

    /**
     * Cancel an upload and clean up.
     */
    fun cancel(id: String): Boolean {
        val state = activeUploads[id] ?: return false
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelling upload: $id")

        state.isCancelled.set(true)
        state.isPaused.set(false)

        try {
            state.connection?.disconnect()
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logW(TAG, "Error disconnecting: ${e.message}")
        }

        state.thread?.interrupt()
        activeUploads.remove(id)
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Upload cancelled and removed: $id")
        return true
    }

    /**
     * Get the state of an upload.
     */
    fun getState(id: String): UploadState? = activeUploads[id]

    /**
     * Check if an upload is paused.
     */
    fun isPaused(id: String): Boolean = activeUploads[id]?.isPaused?.get() ?: false

    /**
     * Check if an upload is active.
     */
    fun isActive(id: String): Boolean = activeUploads.containsKey(id)

    /**
     * Get all active upload IDs.
     */
    fun getActiveUploadIds(): Set<String> = activeUploads.keys.toSet()

    private fun executeUpload(state: UploadState, listener: UploadListener) {
        var connection: HttpURLConnection? = null
        val config = state.config

        try {
            if (state.isCancelled.get()) {
                return
            }

            val file = File(config.source)
            val fileSize = file.length()
            state.bytesTotal = fileSize

            // Determine if we need multipart
            val useMultipart = !config.parameters.isNullOrEmpty() || config.fieldName != null
            val boundary = "----UploadBoundary${System.currentTimeMillis()}"

            val url = URL(config.url)
            connection = url.openConnection() as HttpURLConnection
            state.connection = connection

            connection.requestMethod = config.method
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            // Apply custom headers
            config.headers?.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Set upload header: $key = $value")
            }

            if (useMultipart) {
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            } else {
                connection.setRequestProperty("Content-Type", config.mimeType ?: "application/octet-stream")
            }

            // Calculate total size
            val totalBytes: Long
            if (useMultipart) {
                // For multipart, we need to calculate the full body size
                totalBytes = calculateMultipartSize(config, file, boundary)
            } else {
                totalBytes = fileSize
            }
            state.bytesTotal = totalBytes

            // Report begin
            if (!state.hasReportedBegin) {
                state.hasReportedBegin = true
                listener.onBegin(config.id, totalBytes)
            }

            // Start upload
            if (state.isCancelled.get() || state.isPaused.get()) {
                return
            }

            connection.setFixedLengthStreamingMode(totalBytes)
            connection.connect()

            val outputStream = DataOutputStream(connection.outputStream)

            if (useMultipart) {
                writeMultipartBody(outputStream, config, file, boundary, state, listener)
            } else {
                writeFileBody(outputStream, file, state, listener)
            }

            outputStream.flush()
            outputStream.close()

            if (state.isCancelled.get() || state.isPaused.get()) {
                return
            }

            // Get response
            val responseCode = connection.responseCode
            val responseBody = try {
                val reader = BufferedReader(InputStreamReader(
                    if (responseCode in 200..299) connection.inputStream else connection.errorStream
                ))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                response.toString()
            } catch (e: Exception) {
                ""
            }

            if (responseCode in 200..299) {
                listener.onComplete(config.id, responseCode, responseBody, state.bytesUploaded.get(), state.bytesTotal)
            } else {
                listener.onError(config.id, "HTTP error: $responseCode - $responseBody", responseCode)
            }

            activeUploads.remove(config.id)

        } catch (e: InterruptedException) {
            RNBackgroundDownloaderModuleImpl.logD(TAG, "Upload interrupted: ${state.id}")
            if (!state.isPaused.get() && !state.isCancelled.get()) {
                listener.onError(state.id, "Upload interrupted", -1)
                activeUploads.remove(state.id)
            }
        } catch (e: Exception) {
            if (state.isPaused.get()) {
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Upload stopped (paused): ${state.id}")
            } else if (state.isCancelled.get()) {
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Upload stopped (cancelled): ${state.id}")
            } else {
                RNBackgroundDownloaderModuleImpl.logE(TAG, "Upload error: ${e.message}")
                listener.onError(state.id, e.message ?: "Upload failed", -1)
                activeUploads.remove(state.id)
            }
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun calculateMultipartSize(config: RNBGDUploadTaskConfig, file: File, boundary: String): Long {
        var size = 0L
        val crlf = "\r\n"
        val twoHyphens = "--"
        val fieldName = config.fieldName ?: "file"
        val fileName = file.name
        val mimeType = config.mimeType ?: "application/octet-stream"

        // Parameters
        config.parameters?.forEach { (key, value) ->
            size += "$twoHyphens$boundary$crlf".length
            size += "Content-Disposition: form-data; name=\"$key\"$crlf$crlf".length
            size += "$value$crlf".length
        }

        // File
        size += "$twoHyphens$boundary$crlf".length
        size += "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"$crlf".length
        size += "Content-Type: $mimeType$crlf$crlf".length
        size += file.length()
        size += crlf.length

        // End boundary
        size += "$twoHyphens$boundary$twoHyphens$crlf".length

        return size
    }

    private fun writeMultipartBody(
        outputStream: DataOutputStream,
        config: RNBGDUploadTaskConfig,
        file: File,
        boundary: String,
        state: UploadState,
        listener: UploadListener
    ) {
        val crlf = "\r\n"
        val twoHyphens = "--"
        val fieldName = config.fieldName ?: "file"
        val fileName = file.name
        val mimeType = config.mimeType ?: "application/octet-stream"

        // Write parameters
        config.parameters?.forEach { (key, value) ->
            if (state.isCancelled.get() || state.isPaused.get()) return

            val paramPart = "$twoHyphens$boundary$crlf" +
                "Content-Disposition: form-data; name=\"$key\"$crlf$crlf" +
                "$value$crlf"
            outputStream.writeBytes(paramPart)
            val bytesWritten = state.bytesUploaded.addAndGet(paramPart.length.toLong())
            listener.onProgress(config.id, bytesWritten, state.bytesTotal)
        }

        // Write file header
        val fileHeader = "$twoHyphens$boundary$crlf" +
            "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"$crlf" +
            "Content-Type: $mimeType$crlf$crlf"
        outputStream.writeBytes(fileHeader)
        state.bytesUploaded.addAndGet(fileHeader.length.toLong())

        // Write file content
        FileInputStream(file).use { fileInputStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                if (state.isCancelled.get() || state.isPaused.get()) return

                outputStream.write(buffer, 0, bytesRead)
                val bytesWritten = state.bytesUploaded.addAndGet(bytesRead.toLong())
                listener.onProgress(config.id, bytesWritten, state.bytesTotal)
            }
        }

        // Write file end
        val fileEnd = crlf
        outputStream.writeBytes(fileEnd)
        state.bytesUploaded.addAndGet(fileEnd.length.toLong())

        // Write end boundary
        val endBoundary = "$twoHyphens$boundary$twoHyphens$crlf"
        outputStream.writeBytes(endBoundary)
        val finalBytes = state.bytesUploaded.addAndGet(endBoundary.length.toLong())
        listener.onProgress(config.id, finalBytes, state.bytesTotal)
    }

    private fun writeFileBody(
        outputStream: DataOutputStream,
        file: File,
        state: UploadState,
        listener: UploadListener
    ) {
        FileInputStream(file).use { fileInputStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                if (state.isCancelled.get() || state.isPaused.get()) return

                outputStream.write(buffer, 0, bytesRead)
                val bytesWritten = state.bytesUploaded.addAndGet(bytesRead.toLong())
                listener.onProgress(state.id, bytesWritten, state.bytesTotal)
            }
        }
    }
}

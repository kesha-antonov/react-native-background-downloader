package com.eko

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A resumable downloader that supports pause/resume functionality using HTTP Range headers.
 * This is used as a fallback when the standard DownloadManager doesn't support pause/resume.
 */
class ResumableDownloader {

  companion object {
    private const val TAG = "ResumableDownloader"
    private const val BUFFER_SIZE = 8192
    private const val CONNECT_TIMEOUT_MS = 30000
    private const val READ_TIMEOUT_MS = 30000
  }

  data class DownloadState(
    val id: String,
    val url: String,
    val destination: String,
    val tempFile: String,
    val headers: Map<String, String>,
    val isPaused: AtomicBoolean = AtomicBoolean(false),
    val isCancelled: AtomicBoolean = AtomicBoolean(false),
    val bytesDownloaded: AtomicLong = AtomicLong(0),
    var bytesTotal: Long = -1,
    var thread: Thread? = null,
    var hasReportedBegin: Boolean = false
  )

  private val activeDownloads = ConcurrentHashMap<String, DownloadState>()

  interface DownloadListener {
    fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>)
    fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long)
    fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long)
    fun onError(id: String, error: String, errorCode: Int)
  }

  /**
   * Start a new download or resume from a specific byte position.
   * @param startByte The byte position to start from (for resuming paused DownloadManager downloads)
   * @param totalBytes The total bytes if known (for resuming)
   */
  fun startDownload(
    id: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    listener: DownloadListener,
    startByte: Long = 0,
    totalBytes: Long = -1
  ) {
    val tempFile = "$destination.tmp"
    val state = DownloadState(
      id = id,
      url = url,
      destination = destination,
      tempFile = tempFile,
      headers = headers,
      bytesTotal = totalBytes
    )

    // Set initial bytes downloaded
    if (startByte > 0) {
      state.bytesDownloaded.set(startByte)
      state.hasReportedBegin = true // Don't report begin again for resumed downloads

      // Create temp file with the expected size marker
      val tempFileObj = File(tempFile)
      tempFileObj.parentFile?.mkdirs()
      // We'll append to temp file at the start position
    }

    activeDownloads[id] = state

    val thread = Thread {
      downloadWithResume(state, listener)
    }
    state.thread = thread
    thread.start()
  }

  fun pause(id: String): Boolean {
    val state = activeDownloads[id] ?: return false
    state.isPaused.set(true)
    Log.d(TAG, "Pausing download: $id at ${state.bytesDownloaded.get()} bytes")
    return true
  }

  fun resume(id: String, listener: DownloadListener): Boolean {
    val state = activeDownloads[id] ?: return false

    if (!state.isPaused.get()) {
      Log.w(TAG, "Download $id is not paused")
      return false
    }

    state.isPaused.set(false)

    val thread = Thread {
      downloadWithResume(state, listener)
    }
    state.thread = thread
    thread.start()

    Log.d(TAG, "Resuming download: $id from ${state.bytesDownloaded.get()} bytes")
    return true
  }

  fun cancel(id: String): Boolean {
    val state = activeDownloads[id] ?: return false
    state.isCancelled.set(true)
    state.isPaused.set(false) // Unblock if paused

    // Clean up temp file
    try {
      File(state.tempFile).delete()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to delete temp file: ${e.message}")
    }

    activeDownloads.remove(id)
    return true
  }

  fun getState(id: String): DownloadState? = activeDownloads[id]

  fun isPaused(id: String): Boolean = activeDownloads[id]?.isPaused?.get() ?: false

  fun getBytesDownloaded(id: String): Long = activeDownloads[id]?.bytesDownloaded?.get() ?: 0

  fun getBytesTotal(id: String): Long = activeDownloads[id]?.bytesTotal ?: -1

  private fun downloadWithResume(state: DownloadState, listener: DownloadListener) {
    var connection: HttpURLConnection? = null
    var inputStream: InputStream? = null
    var outputStream: FileOutputStream? = null

    try {
      val url = URL(state.url)
      connection = url.openConnection() as HttpURLConnection
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      connection.requestMethod = "GET"

      // Add custom headers
      for ((key, value) in state.headers) {
        connection.setRequestProperty(key, value)
      }

      // Add Range header for resuming
      val startByte = state.bytesDownloaded.get()
      if (startByte > 0) {
        connection.setRequestProperty("Range", "bytes=$startByte-")
        Log.d(TAG, "Resuming from byte: $startByte")
      }

      val responseCode = connection.responseCode

      // Handle response
      when (responseCode) {
        HttpURLConnection.HTTP_OK -> {
          // Full content - server doesn't support Range or this is a fresh download
          state.bytesTotal = connection.contentLengthLong

          // If we were trying to resume but server sent full content, reset
          if (startByte > 0) {
            Log.w(TAG, "Server doesn't support Range headers, starting from beginning")
            state.bytesDownloaded.set(0)
          }

          // Collect headers
          val responseHeaders = mutableMapOf<String, String>()
          for (i in 0 until connection.headerFields.size) {
            val key = connection.getHeaderFieldKey(i)
            val value = connection.getHeaderField(i)
            if (key != null && value != null) {
              responseHeaders[key] = value
            }
          }

          if (!state.hasReportedBegin) {
            state.hasReportedBegin = true
            listener.onBegin(state.id, state.bytesTotal, responseHeaders)
          }
        }
        HttpURLConnection.HTTP_PARTIAL -> {
          // Partial content - resuming supported
          val contentRange = connection.getHeaderField("Content-Range")
          if (contentRange != null) {
            // Format: bytes start-end/total
            val total = contentRange.substringAfter("/").toLongOrNull()
            if (total != null) {
              state.bytesTotal = total
            }
          }

          if (state.bytesTotal <= 0) {
            state.bytesTotal = startByte + connection.contentLengthLong
          }

          // Only call onBegin if this is a fresh start (not resume)
          if (!state.hasReportedBegin) {
            val responseHeaders = mutableMapOf<String, String>()
            for (i in 0 until connection.headerFields.size) {
              val key = connection.getHeaderFieldKey(i)
              val value = connection.getHeaderField(i)
              if (key != null && value != null) {
                responseHeaders[key] = value
              }
            }
            state.hasReportedBegin = true
            listener.onBegin(state.id, state.bytesTotal, responseHeaders)
          }

          Log.d(TAG, "Server supports Range, continuing from $startByte")
        }
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307, 308 -> {
          // Handle redirect
          val newUrl = connection.getHeaderField("Location")
          if (newUrl != null) {
            connection.disconnect()
            val newState = state.copyWithUrl(newUrl)
            activeDownloads[state.id] = newState
            downloadWithResume(newState, listener)
            return
          }
        }
        416 -> {
          // Range Not Satisfiable - file might be complete or server doesn't support ranges
          Log.w(TAG, "Range not satisfiable for ${state.id}, checking if complete")

          // The download might already be complete
          val tempFile = File(state.tempFile)
          if (tempFile.exists() && state.bytesTotal > 0 && tempFile.length() >= state.bytesTotal) {
            // File is complete, move it
            val destFile = File(state.destination)
            destFile.parentFile?.mkdirs()
            if (tempFile.renameTo(destFile)) {
              activeDownloads.remove(state.id)
              listener.onComplete(state.id, state.destination, state.bytesTotal, state.bytesTotal)
            }
            return
          }

          listener.onError(state.id, "HTTP error: $responseCode - Range not satisfiable", responseCode)
          return
        }
        else -> {
          listener.onError(state.id, "HTTP error: $responseCode", responseCode)
          return
        }
      }

      inputStream = connection.inputStream
      val tempFile = File(state.tempFile)

      // Create parent directories if needed
      tempFile.parentFile?.mkdirs()

      // Open in append mode if resuming
      val shouldAppend = startByte > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
      outputStream = FileOutputStream(tempFile, shouldAppend)

      val buffer = ByteArray(BUFFER_SIZE)
      var bytesRead: Int

      while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        // Check for pause
        if (state.isPaused.get()) {
          Log.d(TAG, "Download paused: ${state.id}")
          outputStream.flush()
          return
        }

        // Check for cancel
        if (state.isCancelled.get()) {
          Log.d(TAG, "Download cancelled: ${state.id}")
          return
        }

        outputStream.write(buffer, 0, bytesRead)
        val newTotal = state.bytesDownloaded.addAndGet(bytesRead.toLong())
        listener.onProgress(state.id, newTotal, state.bytesTotal)
      }

      outputStream.flush()

      // Download complete - move temp file to destination
      val destFile = File(state.destination)
      destFile.parentFile?.mkdirs()

      if (tempFile.renameTo(destFile)) {
        activeDownloads.remove(state.id)
        listener.onComplete(state.id, state.destination, state.bytesDownloaded.get(), state.bytesTotal)
      } else {
        // Fallback: copy and delete
        tempFile.copyTo(destFile, overwrite = true)
        tempFile.delete()
        activeDownloads.remove(state.id)
        listener.onComplete(state.id, state.destination, state.bytesDownloaded.get(), state.bytesTotal)
      }

    } catch (e: Exception) {
      Log.e(TAG, "Download error: ${e.message}", e)
      if (!state.isPaused.get() && !state.isCancelled.get()) {
        listener.onError(state.id, e.message ?: "Unknown error", -1)
      }
    } finally {
      try {
        inputStream?.close()
        outputStream?.close()
        connection?.disconnect()
      } catch (e: Exception) {
        Log.w(TAG, "Error closing streams: ${e.message}")
      }
    }
  }

  private fun DownloadState.copyWithUrl(url: String): DownloadState {
    return DownloadState(
      id = this.id,
      url = url,
      destination = this.destination,
      tempFile = this.tempFile,
      headers = this.headers,
      isPaused = this.isPaused,
      isCancelled = this.isCancelled,
      bytesDownloaded = this.bytesDownloaded,
      bytesTotal = this.bytesTotal,
      thread = this.thread,
      hasReportedBegin = this.hasReportedBegin
    )
  }
}

package com.eko

import com.eko.utils.HeaderUtils
import com.eko.utils.TempFileUtils
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
    @Volatile var thread: Thread? = null,
    @Volatile var connection: HttpURLConnection? = null,
    @Volatile var inputStream: InputStream? = null,
    var hasReportedBegin: Boolean = false,
    // Session counter to detect stale threads after pause/resume
    val sessionId: AtomicLong = AtomicLong(0)
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
    // Cancel any existing download with the same ID first
    val existingState = activeDownloads[id]
    if (existingState != null) {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelling existing download before starting new one: $id")
      existingState.isCancelled.set(true)
      existingState.sessionId.incrementAndGet()
      try {
        existingState.inputStream?.close()
        existingState.connection?.disconnect()
      } catch (e: Exception) {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Error cleaning up existing download: ${e.message}")
      }
      existingState.thread?.interrupt()
      activeDownloads.remove(id)
    }

    val tempFile = TempFileUtils.getTempPath(destination)

    // Clean up any existing temp file if starting fresh (startByte == 0)
    if (startByte == 0L) {
      TempFileUtils.deleteTempFile(destination)
    }

    val state = DownloadState(
      id = id,
      url = url,
      destination = destination,
      tempFile = tempFile,
      headers = headers,
      bytesTotal = totalBytes
    )

    // Set initial bytes downloaded (only for explicit resume with startByte > 0)
    if (startByte > 0) {
      state.bytesDownloaded.set(startByte)
      state.hasReportedBegin = true // Don't report begin again for resumed downloads

      // Create temp file with the expected size marker
      val tempFileObj = File(tempFile)
      tempFileObj.parentFile?.mkdirs()
      // We'll append to temp file at the start position
    }

    activeDownloads[id] = state

    val currentSessionId = state.sessionId.get()
    val thread = Thread {
      downloadWithResume(state, listener, currentSessionId)
    }
    state.thread = thread
    thread.start()
  }

  fun pause(id: String): Boolean {
    val state = activeDownloads[id] ?: return false

    // Increment session ID FIRST to invalidate current thread
    val newSessionId = state.sessionId.incrementAndGet()
    state.isPaused.set(true)

    // Close input stream to force read to fail immediately
    try {
      state.inputStream?.close()
    } catch (e: Exception) {
      // Expected when closing during active read - SSL layer may report "Unbalanced enter/exit"
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Stream closed during pause: ${e.message}")
    }

    // Disconnect current connection to force read to fail
    try {
      state.connection?.disconnect()
    } catch (e: Exception) {
      // Expected when disconnecting during active download
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Connection disconnected during pause: ${e.message}")
    }

    // Interrupt current thread to speed up pause
    state.thread?.interrupt()
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Pausing download: $id at ${state.bytesDownloaded.get()} bytes (invalidated session, new=$newSessionId)")
    return true
  }

  fun resume(id: String, listener: DownloadListener): Boolean {
    val state = activeDownloads[id] ?: return false

    if (!state.isPaused.get()) {
      RNBackgroundDownloaderModuleImpl.logW(TAG, "Download $id is not paused")
      return false
    }

    // Session ID was already incremented in pause(), use current value
    val currentSessionId = state.sessionId.get()
    state.isPaused.set(false)

    val thread = Thread {
      downloadWithResume(state, listener, currentSessionId)
    }
    state.thread = thread
    thread.start()

    RNBackgroundDownloaderModuleImpl.logD(TAG, "Resuming download: $id from ${state.bytesDownloaded.get()} bytes (session $currentSessionId)")
    return true
  }

  fun cancel(id: String): Boolean {
    val state = activeDownloads[id] ?: return false
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelling download: $id")

    // Increment session ID to invalidate any running threads immediately
    state.sessionId.incrementAndGet()

    // Set cancelled flag
    state.isCancelled.set(true)
    state.isPaused.set(false) // Unblock if paused

    // Close input stream to force read to fail immediately
    try {
      state.inputStream?.close()
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logW(TAG, "Error closing input stream: ${e.message}")
    }

    // Disconnect the HTTP connection to force the read to fail immediately
    try {
      state.connection?.disconnect()
    } catch (e: Exception) {
      RNBackgroundDownloaderModuleImpl.logW(TAG, "Error disconnecting: ${e.message}")
    }

    // Interrupt the download thread to stop blocking I/O operations
    state.thread?.interrupt()

    // Clean up temp file
    TempFileUtils.deleteTempFile(state.destination)

    // Remove from active downloads after setting cancelled flag
    activeDownloads.remove(id)
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Download cancelled and removed: $id")
    return true
  }

  fun getState(id: String): DownloadState? = activeDownloads[id]

  fun isPaused(id: String): Boolean = activeDownloads[id]?.isPaused?.get() ?: false

  fun getBytesDownloaded(id: String): Long = activeDownloads[id]?.bytesDownloaded?.get() ?: 0

  fun getBytesTotal(id: String): Long = activeDownloads[id]?.bytesTotal ?: -1

  private fun downloadWithResume(state: DownloadState, listener: DownloadListener, expectedSessionId: Long) {
    val result = executeDownload(state, listener, expectedSessionId)

    // Handle the result by notifying the listener
    when (result) {
      is DownloadResult.Success -> {
        // Already notified in executeDownload
      }
      is DownloadResult.Paused -> {
        // Paused state - no listener callback needed, user can resume later
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Download paused: ${result.id} at ${result.bytesDownloaded} bytes")
      }
      is DownloadResult.Cancelled -> {
        // Cancelled - no callback, cleanup already done
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Download cancelled: ${result.id}")
      }
      is DownloadResult.SessionInvalidated -> {
        // Session invalidated - stale thread, no callback
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Download session invalidated: ${result.id}")
      }
      is DownloadResult.Error -> {
        // Error already reported to listener in executeDownload
      }
    }
  }

  /**
   * Execute the download and return a DownloadResult.
   * This method handles all download logic and returns a type-safe result.
   */
  private fun executeDownload(state: DownloadState, listener: DownloadListener, expectedSessionId: Long): DownloadResult {
    var connection: HttpURLConnection? = null
    var inputStream: InputStream? = null
    var outputStream: FileOutputStream? = null

    try {
      // Check if session is still valid
      if (state.sessionId.get() != expectedSessionId) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Session invalidated at start for ${state.id}, exiting")
        return DownloadResult.SessionInvalidated(state.id)
      }
      if (state.isCancelled.get()) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Already cancelled at start for ${state.id}")
        return DownloadResult.Cancelled(state.id)
      }

      val url = URL(state.url)
      connection = url.openConnection() as HttpURLConnection
      // Store connection reference so it can be disconnected on cancel
      state.connection = connection
      connection.connectTimeout = DownloadConstants.CONNECT_TIMEOUT_MS
      connection.readTimeout = DownloadConstants.READ_TIMEOUT_MS
      connection.requestMethod = "GET"

      // Check again after connection setup
      if (state.sessionId.get() != expectedSessionId) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Session invalidated after connection setup for ${state.id}")
        connection.disconnect()
        return DownloadResult.SessionInvalidated(state.id)
      }
      if (state.isCancelled.get()) {
        connection.disconnect()
        return DownloadResult.Cancelled(state.id)
      }

      // Add custom headers
      for ((key, value) in state.headers) {
        connection.setRequestProperty(key, value)
      }

      // Add Range header for resuming
      val startByte = state.bytesDownloaded.get()
      if (startByte > 0) {
        connection.setRequestProperty("Range", "bytes=$startByte-")
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Resuming from byte: $startByte")
      }

      val responseCode = connection.responseCode

      // Handle response
      when (responseCode) {
        HttpURLConnection.HTTP_OK -> {
          // Full content - server doesn't support Range or this is a fresh download
          state.bytesTotal = connection.contentLengthLong

          // If we were trying to resume but server sent full content, reset
          if (startByte > 0) {
            RNBackgroundDownloaderModuleImpl.logW(TAG, "Server doesn't support Range headers, starting from beginning")
            state.bytesDownloaded.set(0)
          }

          // Collect headers
          val responseHeaders = HeaderUtils.extractResponseHeaders(connection)

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
            val responseHeaders = HeaderUtils.extractResponseHeaders(connection)
            state.hasReportedBegin = true
            listener.onBegin(state.id, state.bytesTotal, responseHeaders)
          }

          RNBackgroundDownloaderModuleImpl.logD(TAG, "Server supports Range, continuing from $startByte")
        }
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        307, 308 -> {
          // Handle redirect
          val newUrl = connection.getHeaderField("Location")
          if (newUrl != null) {
            connection.disconnect()
            // Create new state with updated URL
            // Note: We preserve bytesDownloaded since the redirect should point to the same resource.
            // If the new server doesn't support Range headers, the HTTP_OK case above will reset it.
            val newState = state.copyWithUrl(newUrl)
            activeDownloads[state.id] = newState
            return executeDownload(newState, listener, expectedSessionId)
          }
        }
        416 -> {
          // Range Not Satisfiable - file might be complete or server doesn't support ranges
          RNBackgroundDownloaderModuleImpl.logW(TAG, "Range not satisfiable for ${state.id}, checking if complete")

          // The download might already be complete
          val tempFile = File(state.tempFile)
          if (tempFile.exists() && state.bytesTotal > 0 && tempFile.length() >= state.bytesTotal) {
            // File is complete, move it
            val destFile = File(state.destination)
            destFile.parentFile?.mkdirs()
            if (tempFile.renameTo(destFile)) {
              activeDownloads.remove(state.id)
              listener.onComplete(state.id, state.destination, state.bytesTotal, state.bytesTotal)
              return DownloadResult.Success(state.id, state.destination, state.bytesTotal, state.bytesTotal)
            }
          }

          val error = DownloadResult.httpError(state.id, responseCode, "Range not satisfiable")
          listener.onError(state.id, error.message, error.errorCode)
          return error
        }
        else -> {
          val error = DownloadResult.httpError(state.id, responseCode)
          listener.onError(state.id, error.message, error.errorCode)
          return error
        }
      }

      inputStream = connection.inputStream
      // Store input stream reference so it can be closed on cancel/pause
      state.inputStream = inputStream

      // Check immediately after getting input stream
      if (state.sessionId.get() != expectedSessionId) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Session invalidated after getting input stream for ${state.id}")
        return DownloadResult.SessionInvalidated(state.id)
      }
      if (state.isCancelled.get()) {
        return DownloadResult.Cancelled(state.id)
      }

      val tempFile = File(state.tempFile)

      // Create parent directories if needed
      tempFile.parentFile?.mkdirs()

      // Open in append mode if resuming
      val shouldAppend = startByte > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
      outputStream = FileOutputStream(tempFile, shouldAppend)

      val buffer = ByteArray(DownloadConstants.BUFFER_SIZE)
      var bytesRead: Int

      downloadLoop@ while (true) {
        // Check all termination conditions at the start of each iteration
        if (state.sessionId.get() != expectedSessionId) {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Session invalidated (loop start): ${state.id}")
          return DownloadResult.SessionInvalidated(state.id)
        }
        if (state.isCancelled.get()) {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Download cancelled (loop start): ${state.id}")
          return DownloadResult.Cancelled(state.id)
        }
        if (state.isPaused.get()) {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Download paused: ${state.id}")
          outputStream.flush()
          return DownloadResult.Paused(state.id, state.bytesDownloaded.get(), state.bytesTotal)
        }

        // Read data
        bytesRead = inputStream.read(buffer)
        if (bytesRead == -1) break

        // Check termination conditions again after read (read may block for a while)
        if (state.sessionId.get() != expectedSessionId) {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Session invalidated (after read): ${state.id}")
          return DownloadResult.SessionInvalidated(state.id)
        }
        if (state.isCancelled.get()) {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Download cancelled (after read): ${state.id}")
          return DownloadResult.Cancelled(state.id)
        }

        outputStream.write(buffer, 0, bytesRead)
        val newTotal = state.bytesDownloaded.addAndGet(bytesRead.toLong())

        // Only report progress if session is still valid
        if (state.sessionId.get() == expectedSessionId && !state.isCancelled.get()) {
          listener.onProgress(state.id, newTotal, state.bytesTotal)
        }
      }

      // Check if we exited due to cancellation/stale session
      if (state.sessionId.get() != expectedSessionId) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Session invalidated after loop: ${state.id}")
        return DownloadResult.SessionInvalidated(state.id)
      }
      if (state.isCancelled.get()) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelled after loop: ${state.id}")
        return DownloadResult.Cancelled(state.id)
      }

      outputStream.flush()

      // Download complete - move temp file to destination
      val destFile = File(state.destination)

      val bytesDownloaded = state.bytesDownloaded.get()
      val bytesTotal = state.bytesTotal

      if (TempFileUtils.moveToDestination(tempFile, destFile)) {
        activeDownloads.remove(state.id)
        listener.onComplete(state.id, state.destination, bytesDownloaded, bytesTotal)
        return DownloadResult.Success(state.id, state.destination, bytesDownloaded, bytesTotal)
      } else {
        // Move failed - this is an error
        val error = DownloadResult.Error(state.id, "Failed to move temp file to destination", -1)
        listener.onError(state.id, error.message, error.errorCode)
        return error
      }

    } catch (e: InterruptedException) {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Download interrupted: ${state.id}")
      // Determine result based on state
      return when {
        state.sessionId.get() != expectedSessionId -> DownloadResult.SessionInvalidated(state.id)
        state.isCancelled.get() -> DownloadResult.Cancelled(state.id)
        state.isPaused.get() -> DownloadResult.Paused(state.id, state.bytesDownloaded.get(), state.bytesTotal)
        else -> DownloadResult.SessionInvalidated(state.id) // Treat unexpected interrupt as session invalidation
      }
    } catch (e: java.io.InterruptedIOException) {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Download I/O interrupted: ${state.id}")
      // Determine result based on state
      return when {
        state.sessionId.get() != expectedSessionId -> DownloadResult.SessionInvalidated(state.id)
        state.isCancelled.get() -> DownloadResult.Cancelled(state.id)
        state.isPaused.get() -> DownloadResult.Paused(state.id, state.bytesDownloaded.get(), state.bytesTotal)
        else -> DownloadResult.SessionInvalidated(state.id)
      }
    } catch (e: Exception) {
      // Determine result based on state - expected exceptions vs real errors
      return when {
        state.sessionId.get() != expectedSessionId -> {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Download stopped (session invalidated): ${state.id} - ${e.message}")
          DownloadResult.SessionInvalidated(state.id)
        }
        state.isCancelled.get() -> {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Download stopped (cancelled): ${state.id} - ${e.message}")
          DownloadResult.Cancelled(state.id)
        }
        state.isPaused.get() -> {
          RNBackgroundDownloaderModuleImpl.logD(TAG, "Download stopped (paused): ${state.id} - ${e.message}")
          DownloadResult.Paused(state.id, state.bytesDownloaded.get(), state.bytesTotal)
        }
        else -> {
          // Unexpected error - log with stack trace and report to listener
          RNBackgroundDownloaderModuleImpl.logE(TAG, "Download error: ${e.message}")
          val error = DownloadResult.fromException(state.id, e)
          listener.onError(state.id, error.message, error.errorCode)
          error
        }
      }
    } finally {
      try {
        inputStream?.close()
        outputStream?.close()
        connection?.disconnect()
      } catch (e: Exception) {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Error closing streams: ${e.message}")
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
      connection = this.connection,
      inputStream = this.inputStream,
      hasReportedBegin = this.hasReportedBegin,
      sessionId = this.sessionId
    )
  }
}

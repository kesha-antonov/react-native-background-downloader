package com.eko

import android.system.Os
import com.eko.utils.HeaderUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A process-owned downloader with pause/resume support using HTTP Range headers.
 */
class ResumableDownloader(
  private val connectTimeoutMs: Int = DownloadConstants.CONNECT_TIMEOUT_MS,
  private val readTimeoutMs: Int = DownloadConstants.READ_TIMEOUT_MS
) {

  companion object {
    private const val TAG = "ResumableDownloader"

    @Volatile
    private var maxConcurrentTransfers = DownloadConstants.DEFAULT_MAX_PARALLEL_DOWNLOADS

    /**
     * Cap on transfers running at once, from the JS `maxParallelDownloads`
     * config. Applies to every downloader instance: this path is the one with no
     * scheduler of its own in front of it, so without a cap a batch of downloads
     * takes a thread and a socket each.
     *
     * Raising it lets already waiting downloads start on the next slot release;
     * lowering it never interrupts a transfer that is already running.
     */
    fun setMaxConcurrentTransfers(max: Int) {
      maxConcurrentTransfers = max.coerceAtLeast(1)
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Max concurrent transfers set to $maxConcurrentTransfers")
    }
  }

  data class DownloadState(
    val id: String,
    @Volatile var url: String,
    val destination: String,
    val partialDestination: String,
    val headers: Map<String, String>,
    val expectedSha256: String?,
    val isPaused: AtomicBoolean = AtomicBoolean(false),
    val isCancelled: AtomicBoolean = AtomicBoolean(false),
    val bytesDownloaded: AtomicLong = AtomicLong(0),
    var bytesTotal: Long = -1,
    @Volatile var thread: Thread? = null,
    @Volatile var connection: HttpURLConnection? = null,
    @Volatile var inputStream: InputStream? = null,
    var hasReportedBegin: Boolean = false,
    @Volatile var resumeValidator: ResumeValidator? = null,
    val fileLock: Any = Any(),
    // Session counter to detect stale threads after pause/resume
    val sessionId: AtomicLong = AtomicLong(0),
    // Kept so a download that had to wait for a concurrency slot can be started
    // later without the caller having to hand the listener over again
    @Volatile var listener: DownloadListener? = null
  )

  sealed class ResumeValidator(val value: String) {
    class ETag(value: String) : ResumeValidator(value)
    class LastModified(value: String) : ResumeValidator(value)
  }

  private val activeDownloads = ConcurrentHashMap<String, DownloadState>()

  // Concurrency accounting. `transferring` maps a download to the session ID of
  // the transfer holding its slot, so a restarted download's old thread can't
  // release the slot its replacement took. `waitingForSlot` is FIFO.
  private val transferLock = Any()
  private val transferring = mutableMapOf<String, DownloadState>()
  private val waitingForSlot = ArrayDeque<String>()

  interface DownloadListener {
    fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>)
    fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long)
    fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long)
    fun onError(id: String, error: String, errorCode: Int)
  }

  /**
   * Start a new download or resume from a specific byte position.
   * @param startByte The byte position to start from.
   * @param totalBytes The total bytes if known (for resuming)
   */
  fun startDownload(
    id: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    listener: DownloadListener,
    startByte: Long = 0,
    totalBytes: Long = -1,
    resumeValidator: ResumeValidator? = null,
    expectedSha256: String? = null
  ) {
    val state = registerNewDownload(
      id,
      url,
      destination,
      headers,
      startByte,
      totalBytes,
      resumeValidator,
      expectedSha256
    )
    state.listener = listener

    beginOrQueueTransfer(state)
  }

  /**
   * Start the download's transfer, or leave it waiting when the concurrency cap
   * is already reached. A waiting download starts as soon as a running transfer
   * ends - completed, failed, paused or cancelled, all of which end its thread.
   */
  private fun beginOrQueueTransfer(state: DownloadState) {
    val sessionId = state.sessionId.get()

    synchronized(transferLock) {
      if (transferring.size >= maxConcurrentTransfers || transferring.containsKey(state.id)) {
        if (!waitingForSlot.contains(state.id)) {
          waitingForSlot.addLast(state.id)
        }
        RNBackgroundDownloaderModuleImpl.logD(
          TAG,
          "Queued download ${state.id}: ${transferring.size} transfer(s) running, max $maxConcurrentTransfers"
        )
        return
      }
      transferring[state.id] = state
    }

    startTransferThread(state, sessionId)
  }

  private fun startTransferThread(state: DownloadState, sessionId: Long) {
    val listener = state.listener
    if (listener == null) {
      RNBackgroundDownloaderModuleImpl.logW(TAG, "No listener for ${state.id}, cannot start transfer")
      releaseTransferSlot(state)
      return
    }

    val thread = Thread {
      try {
        downloadWithResume(state, listener, sessionId)
      } finally {
        // The thread ends on every outcome - complete, error, pause, cancel and
        // stale-session exits - so this is the one place a slot is given back
        releaseTransferSlot(state)
      }
    }
    state.thread = thread
    thread.start()
  }

  /**
   * Give back the slot held by this transfer and start the next download waiting
   * for one. Ignores a release from a superseded session: a restarted download
   * must not release the slot its replacement is holding.
   */
  private fun releaseTransferSlot(state: DownloadState) {
    var next: DownloadState? = null
    var nextSessionId = 0L

    synchronized(transferLock) {
      if (transferring[state.id] !== state) return
      transferring.remove(state.id)

      while (next == null && waitingForSlot.isNotEmpty()) {
        val candidateId = waitingForSlot.removeFirst()
        val candidate = activeDownloads[candidateId] ?: continue
        // Skip downloads that stopped waiting while they were in the queue
        if (candidate.isCancelled.get() || candidate.isPaused.get()) continue
        if (transferring.containsKey(candidateId)) continue

        nextSessionId = candidate.sessionId.get()
        transferring[candidateId] = candidate
        next = candidate
      }
    }

    next?.let { startTransferThread(it, nextSessionId) }
  }

  /** Drop a download from the waiting queue (paused, cancelled or restarted). */
  private fun dropFromWaitingQueue(id: String) {
    synchronized(transferLock) { waitingForSlot.remove(id) }
  }

  /**
   * Cancel any existing download with the same ID, then create and register a
   * fresh DownloadState (no thread started).
   */
  private fun registerNewDownload(
    id: String,
    url: String,
    destination: String,
    headers: Map<String, String>,
    startByte: Long,
    totalBytes: Long,
    resumeValidator: ResumeValidator?,
    expectedSha256: String?
  ): DownloadState {
    val partialFile = File("$destination.part")

    val state = DownloadState(
      id = id,
      url = url,
      destination = destination,
      partialDestination = partialFile.absolutePath,
      headers = headers,
      expectedSha256 = expectedSha256,
      bytesTotal = totalBytes,
      resumeValidator = resumeValidator
    )

    // Set initial bytes downloaded (only for explicit resume with startByte > 0)
    if (startByte > 0) {
      state.bytesDownloaded.set(startByte)
      state.hasReportedBegin = true // Don't report begin again for resumed downloads

      val parentDir = partialFile.parentFile
      if (parentDir != null && !parentDir.exists()) {
        if (!parentDir.mkdirs()) {
          RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to create parent directories: ${parentDir.absolutePath}")
        }
      }
    }

    val existingState = synchronized(transferLock) {
      val existing = activeDownloads[id]
      if (existing != null) {
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Cancelling existing download before starting new one: $id")
        existing.isCancelled.set(true)
        existing.sessionId.incrementAndGet()
        waitingForSlot.remove(id)
      }
      activeDownloads[id] = state
      existing
    }

    if (existingState != null) {
      try {
        existingState.inputStream?.close()
        existingState.connection?.disconnect()
      } catch (e: Exception) {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "Error cleaning up existing download: ${e.message}")
      }
      existingState.thread?.interrupt()
    }

    return state
  }

  fun pause(id: String): Boolean {
    val state = activeDownloads[id] ?: return false

    // Increment session ID FIRST to invalidate current thread
    val newSessionId = state.sessionId.incrementAndGet()
    state.isPaused.set(true)

    // A download still waiting for a concurrency slot has no thread to stop, and
    // must not start once one frees up - the user paused it
    dropFromWaitingQueue(id)

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

    state.isPaused.set(false)
    state.listener = listener
    // Session ID was already incremented in pause(); beginOrQueueTransfer reads it
    beginOrQueueTransfer(state)

    RNBackgroundDownloaderModuleImpl.logD(TAG, "Resuming download: $id from ${state.bytesDownloaded.get()} bytes (session ${state.sessionId.get()})")
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

    // Nothing will start it now, but leaving it queued would make the next slot
    // release walk over a dead entry
    dropFromWaitingQueue(id)

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

    deletePartialFile(state)

    // Remove from active downloads after setting cancelled flag
    activeDownloads.remove(id)
    RNBackgroundDownloaderModuleImpl.logD(TAG, "Download cancelled and removed: $id")
    return true
  }

  fun getState(id: String): DownloadState? = activeDownloads[id]

  /**
   * Returns a snapshot of all currently tracked download states.
   */
  fun getActiveDownloads(): Map<String, DownloadState> = activeDownloads.toMap()

  fun isPaused(id: String): Boolean = activeDownloads[id]?.isPaused?.get() ?: false

  fun getBytesDownloaded(id: String): Long = activeDownloads[id]?.bytesDownloaded?.get() ?: 0

  fun getBytesTotal(id: String): Long = activeDownloads[id]?.bytesTotal ?: -1

  private fun downloadWithResume(state: DownloadState, listener: DownloadListener, expectedSessionId: Long) {
    val result = executeDownload(state, listener, expectedSessionId, 0)

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
        deletePartialFile(state)
        activeDownloads.remove(result.id, state)
      }
    }
  }

  /**
   * Execute the download and return a DownloadResult.
   * This method handles all download logic and returns a type-safe result.
   */
  private fun executeDownload(
    state: DownloadState,
    listener: DownloadListener,
    expectedSessionId: Long,
    redirectCount: Int
  ): DownloadResult {
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
      connection.connectTimeout = connectTimeoutMs
      connection.readTimeout = readTimeoutMs
      connection.requestMethod = "GET"
      connection.instanceFollowRedirects = false

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
      connection.setRequestProperty("Connection", "keep-alive")
      connection.setRequestProperty("Keep-Alive", DownloadConstants.KEEP_ALIVE_HEADER_VALUE)
      if (!HeaderUtils.hasUserAgent(state.headers))
        connection.setRequestProperty("User-Agent", DownloadConstants.USER_AGENT)

      var startByte = state.bytesDownloaded.get()
      if (startByte > 0 && state.resumeValidator == null) {
        RNBackgroundDownloaderModuleImpl.logW(TAG, "No entity validator for ${state.id}, restarting from the beginning")
        resetPartialDownload(state)
        startByte = 0
      }

      if (startByte > 0) {
        connection.setRequestProperty("Range", "bytes=$startByte-")
        connection.setRequestProperty("If-Range", state.resumeValidator!!.value)
        RNBackgroundDownloaderModuleImpl.logD(TAG, "Resuming from byte: $startByte")
      }

      val responseCode = connection.responseCode

      // Handle response
      when (responseCode) {
        HttpURLConnection.HTTP_OK -> {
          if (startByte > 0) {
            RNBackgroundDownloaderModuleImpl.logW(TAG, "Server doesn't support Range headers, starting from beginning")
            resetPartialDownload(state)
          }

          state.bytesTotal = connection.contentLengthLong
          state.resumeValidator = responseValidator(connection)

          // Collect headers
          val responseHeaders = HeaderUtils.extractResponseHeaders(connection)

          if (!state.hasReportedBegin) {
            state.hasReportedBegin = true
            listener.onBegin(state.id, state.bytesTotal, responseHeaders)
          }
        }
        HttpURLConnection.HTTP_PARTIAL -> {
          val contentRange = connection.getHeaderField("Content-Range")
          val rangeStart = contentRange
            ?.substringAfter("bytes ", "")
            ?.substringBefore("-")
            ?.toLongOrNull()
          val responseValidator = responseValidator(connection)
          val validResume = rangeStart == startByte &&
            (startByte == 0L || validatorsMatch(state.resumeValidator, responseValidator))

          if (!validResume) {
            if (startByte > 0) {
              RNBackgroundDownloaderModuleImpl.logW(TAG, "Server returned an unsafe resume response for ${state.id}, restarting from the beginning")
              connection.disconnect()
              resetPartialDownload(state)
              return executeDownload(state, listener, expectedSessionId, redirectCount)
            }
            val error = DownloadResult.httpError(state.id, responseCode, "Partial response did not start at byte zero")
            listener.onError(state.id, error.message, error.errorCode)
            return error
          }

          state.resumeValidator = responseValidator
          val total = contentRange?.substringAfter("/")?.toLongOrNull()
          if (total != null) state.bytesTotal = total

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
          if (redirectCount >= 10) {
            val error = DownloadResult.httpError(state.id, responseCode, "Too many redirects")
            listener.onError(state.id, error.message, error.errorCode)
            return error
          }
          val location = connection.getHeaderField("Location")
          if (location.isNullOrBlank()) {
            val error = DownloadResult.httpError(state.id, responseCode, "Redirect response is missing Location")
            listener.onError(state.id, error.message, error.errorCode)
            return error
          }
          connection.disconnect()
          state.url = URL(url, location).toString()
          return executeDownload(state, listener, expectedSessionId, redirectCount + 1)
        }
        416 -> {
          // Range Not Satisfiable - file might be complete or server doesn't support ranges
          RNBackgroundDownloaderModuleImpl.logW(TAG, "Range not satisfiable for ${state.id}, checking if complete")

          // The download might already be complete
          val partialFile = File(state.partialDestination)
          val validatorMatches = validatorsMatch(state.resumeValidator, responseValidator(connection))
          if (partialFile.exists() && state.bytesTotal > 0 && partialFile.length() == state.bytesTotal && validatorMatches) {
            verifyExpectedSha256(state, partialFile)
            if (!commitCompletedDownload(state, expectedSessionId))
              return DownloadResult.SessionInvalidated(state.id)
            listener.onComplete(state.id, state.destination, state.bytesTotal, state.bytesTotal)
            return DownloadResult.Success(state.id, state.destination, state.bytesTotal, state.bytesTotal)
          }

          if (startByte > 0) {
            connection.disconnect()
            resetPartialDownload(state)
            return executeDownload(state, listener, expectedSessionId, redirectCount)
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

      val partialFile = File(state.partialDestination)

      val parentDir = partialFile.parentFile
      if (parentDir != null && !parentDir.exists()) {
        if (!parentDir.mkdirs()) {
          RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to create parent directories: ${parentDir.absolutePath}")
        }
      }

      // Open in append mode if resuming
      val shouldAppend = startByte > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL
      val destinationStream = synchronized(state.fileLock) {
        if (state.sessionId.get() != expectedSessionId)
          return DownloadResult.SessionInvalidated(state.id)
        if (state.isCancelled.get())
          return DownloadResult.Cancelled(state.id)
        FileOutputStream(partialFile, shouldAppend)
      }
      outputStream = destinationStream

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
          destinationStream.flush()
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

        destinationStream.write(buffer, 0, bytesRead)
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

      destinationStream.flush()

      val bytesDownloaded = state.bytesDownloaded.get()
      val bytesTotal = state.bytesTotal
      val partialBytes = partialFile.length()
      if (partialBytes != bytesDownloaded || (bytesTotal >= 0 && bytesDownloaded != bytesTotal)) {
        val error = DownloadResult.Error(
          state.id,
          "Download ended with $partialBytes bytes on disk after receiving $bytesDownloaded of $bytesTotal bytes"
        )
        listener.onError(state.id, error.message, error.errorCode)
        return error
      }

      destinationStream.close()
      outputStream = null
      verifyExpectedSha256(state, partialFile)
      if (!commitCompletedDownload(state, expectedSessionId))
        return DownloadResult.SessionInvalidated(state.id)

      listener.onComplete(state.id, state.destination, bytesDownloaded, bytesTotal)
      return DownloadResult.Success(state.id, state.destination, bytesDownloaded, bytesTotal)

    } catch (e: InterruptedException) {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Download interrupted: ${state.id}")
      // Determine result based on state
      return when {
        state.sessionId.get() != expectedSessionId -> DownloadResult.SessionInvalidated(state.id)
        state.isCancelled.get() -> DownloadResult.Cancelled(state.id)
        state.isPaused.get() -> DownloadResult.Paused(state.id, state.bytesDownloaded.get(), state.bytesTotal)
        else -> DownloadResult.fromException(state.id, e).also {
          listener.onError(state.id, it.message, it.errorCode)
        }
      }
    } catch (e: java.io.InterruptedIOException) {
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Download I/O interrupted: ${state.id}")
      // Determine result based on state
      return when {
        state.sessionId.get() != expectedSessionId -> DownloadResult.SessionInvalidated(state.id)
        state.isCancelled.get() -> DownloadResult.Cancelled(state.id)
        state.isPaused.get() -> DownloadResult.Paused(state.id, state.bytesDownloaded.get(), state.bytesTotal)
        else -> DownloadResult.fromException(state.id, e).also {
          listener.onError(state.id, it.message, it.errorCode)
        }
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

  private fun promotePartialFile(state: DownloadState) = synchronized(state.fileLock) {
    val partialFile = File(state.partialDestination)
    if (!partialFile.exists()) throw IOException("Partial download file is missing")

    val destinationFile = File(state.destination)
    destinationFile.parentFile?.let { parent ->
      if (!parent.exists() && !parent.mkdirs())
        throw IOException("Could not create destination directory: ${parent.absolutePath}")
    }

    try {
      Os.rename(partialFile.absolutePath, destinationFile.absolutePath)
    } catch (error: Exception) {
      throw IOException("Could not replace destination file", error)
    }

    if (partialFile.exists() && !partialFile.renameTo(destinationFile))
      throw IOException("Could not replace destination file")
  }

  private fun commitCompletedDownload(state: DownloadState, expectedSessionId: Long): Boolean =
    synchronized(transferLock) {
      if (state.sessionId.get() != expectedSessionId || state.isCancelled.get() || activeDownloads[state.id] !== state)
        return@synchronized false

      promotePartialFile(state)
      activeDownloads.remove(state.id, state)
      true
    }

  private fun responseValidator(connection: HttpURLConnection): ResumeValidator? {
    val etag = connection.getHeaderField("ETag")?.trim()
    if (!etag.isNullOrEmpty() && !etag.startsWith("W/")) return ResumeValidator.ETag(etag)

    val lastModified = connection.getHeaderField("Last-Modified")?.trim()
    return lastModified?.takeIf(String::isNotEmpty)?.let { ResumeValidator.LastModified(it) }
  }

  private fun verifyExpectedSha256(state: DownloadState, file: File) {
    val expected = state.expectedSha256 ?: return
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    if (actual != expected)
      throw IOException("SHA-256 mismatch: expected $expected, received $actual")
  }

  private fun validatorsMatch(expected: ResumeValidator?, actual: ResumeValidator?): Boolean =
    expected != null && actual != null && expected::class == actual::class && expected.value == actual.value

  private fun resetPartialDownload(state: DownloadState) = synchronized(state.fileLock) {
    state.bytesDownloaded.set(0)
    state.bytesTotal = -1
    state.resumeValidator = null
    val partialFile = File(state.partialDestination)
    if (partialFile.exists() && !partialFile.delete())
      throw IOException("Could not discard unsafe partial download")
  }

  private fun deletePartialFile(state: DownloadState) = synchronized(state.fileLock) {
    val partialFile = File(state.partialDestination)
    if (!partialFile.exists()) return@synchronized
    if (partialFile.delete())
      RNBackgroundDownloaderModuleImpl.logD(TAG, "Deleted partial file: ${state.partialDestination}")
    else
      RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to delete partial file: ${state.partialDestination}")
  }
}

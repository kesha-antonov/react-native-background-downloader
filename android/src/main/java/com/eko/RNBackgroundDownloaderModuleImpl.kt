package com.eko

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import com.eko.handlers.OnBegin
import com.eko.handlers.OnProgress
import com.eko.handlers.OnProgressState
import com.eko.utils.FileUtils
import com.eko.utils.HeaderUtils
import com.eko.utils.StorageManager
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReadableType
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class RNBackgroundDownloaderModuleImpl(private val reactContext: ReactApplicationContext) {

  companion object {
    const val NAME = "RNBackgroundDownloader"

    private val stateMap = mapOf(
      DownloadManager.STATUS_FAILED to DownloadConstants.TASK_CANCELING,
      DownloadManager.STATUS_PAUSED to DownloadConstants.TASK_SUSPENDED,
      DownloadManager.STATUS_PENDING to DownloadConstants.TASK_RUNNING,
      DownloadManager.STATUS_RUNNING to DownloadConstants.TASK_RUNNING,
      DownloadManager.STATUS_SUCCESSFUL to DownloadConstants.TASK_COMPLETED
    )

    private val sharedLock = Any()

    // Controls whether debug logs are enabled
    @Volatile
    private var isLogsEnabled = false

    // Helper functions for conditional logging
    fun logD(tag: String, message: String) {
      if (isLogsEnabled) Log.d(tag, message)
    }

    fun logW(tag: String, message: String) {
      if (isLogsEnabled) Log.w(tag, message)
    }

    fun logE(tag: String, message: String) {
      if (isLogsEnabled) Log.e(tag, message)
    }
  }

  // Storage manager for persistent state
  private val storageManager = StorageManager(reactContext, NAME)

  private val cachedExecutorPool: ExecutorService = Executors.newCachedThreadPool()
  private val fixedExecutorPool: ExecutorService = Executors.newFixedThreadPool(1)
  private val downloader: Downloader
  private var downloadReceiver: BroadcastReceiver? = null
  private var downloadIdToConfig = mutableMapOf<Long, RNBGDTaskConfig>()
  private val configIdToDownloadId = mutableMapOf<String, Long>()
  private val configIdToProgressFuture = mutableMapOf<String, Future<OnProgressState?>>()
  private val configIdToHeaders = mutableMapOf<String, Map<String, String>>()
  private lateinit var ee: DeviceEventManagerModule.RCTDeviceEventEmitter

  // Centralized progress reporting with threshold filtering and batching
  private val progressReporter = ProgressReporter { reportsArray ->
    getEventEmitter()?.emit("downloadProgress", reportsArray)
  }

  // Centralized event emitter for download events
  private val eventEmitter by lazy {
    DownloadEventEmitter { getEventEmitter()!! }
  }

  // Flag to track if module is fully initialized
  @Volatile
  private var isInitialized = false

  // Flag to track if download receiver is registered
  @Volatile
  private var isReceiverRegistered = false

  /**
   * Get the event emitter, ensuring it's initialized.
   * Returns null if the module hasn't been initialized yet.
   */
  private fun getEventEmitter(): DeviceEventManagerModule.RCTDeviceEventEmitter? {
    if (!isInitialized) {
      // Attempt lazy initialization if not yet initialized
      // This handles the case where download() is called before initialize()
      try {
        if (!::ee.isInitialized) {
          ee = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        }
      } catch (e: Exception) {
        logW(NAME, "Event emitter not ready yet: ${e.message}")
        return null
      }
    }
    return if (::ee.isInitialized) ee else null
  }

  /**
   * Ensure event emitter is initialized before starting downloads.
   * This is called before download() to fix issues on first app install
   * where download() might be called before initialize() completes.
   */
  private fun ensureEventEmitterInitialized() {
    if (!::ee.isInitialized) {
      try {
        ee = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        logD(NAME, "Event emitter initialized eagerly before download")
      } catch (e: Exception) {
        logW(NAME, "Could not initialize event emitter: ${e.message}")
      }
    }
  }

  /**
   * Ensure download receiver is registered before starting downloads.
   * This fixes issues on first app install where download() might be called
   * before initialize() completes, causing download completion events to be missed.
   */
  private fun ensureReceiverRegistered() {
    synchronized(sharedLock) {
      if (!isReceiverRegistered) {
        registerDownloadReceiver()
        logD(NAME, "Download receiver registered eagerly before download")
      }
    }
  }

  // Listener for resumable downloads
  private val resumableDownloadListener = object : ResumableDownloader.DownloadListener {
    override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) {
      val headersMap = Arguments.createMap()
      for ((key, value) in headers) {
        headersMap.putString(key, value)
      }
      onBeginDownload(id, headersMap, expectedBytes)
    }

    override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) {
      onProgressDownload(id, bytesDownloaded, bytesTotal)
    }

    override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
      eventEmitter.emitComplete(id, location, bytesDownloaded, bytesTotal)

      // Clean up all download state
      synchronized(sharedLock) {
        cleanupDownloadState(id)
      }
    }

    override fun onError(id: String, error: String, errorCode: Int) {
      eventEmitter.emitFailed(id, error, errorCode)

      // Clean up all download state
      synchronized(sharedLock) {
        cleanupDownloadState(id)
      }
    }
  }

  init {
    loadDownloadIdToConfigMap()
    loadConfigMap()

    downloader = Downloader(reactContext)
  }

  fun getConstants(): Map<String, Any>? {
    val constants = mutableMapOf<String, Any>()

    // Use internal storage (filesDir) for consistency with iOS and to avoid
    // issues with external storage paths on some devices
    constants["documents"] = reactContext.filesDir.absolutePath

    constants["TaskRunning"] = DownloadConstants.TASK_RUNNING
    constants["TaskSuspended"] = DownloadConstants.TASK_SUSPENDED
    constants["TaskCanceling"] = DownloadConstants.TASK_CANCELING
    constants["TaskCompleted"] = DownloadConstants.TASK_COMPLETED

    return constants
  }

  fun setLogsEnabled(enabled: Boolean) {
    isLogsEnabled = enabled
  }

  fun initialize() {
    ee = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
    isInitialized = true
    registerDownloadReceiver()

    // Set the listener for resumable downloads (used by the background service)
    downloader.setResumableDownloadListener(resumableDownloadListener)

    for ((downloadId, config) in downloadIdToConfig) {
      resumeTasks(downloadId, config)
    }
  }

  fun invalidate() {
    unregisterDownloadReceiver()
    downloader.unbindService()
  }

  private fun registerDownloadReceiver() {
    // Prevent double registration
    if (isReceiverRegistered) {
      return
    }

    val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

    downloadReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)

        // Check if this download is being intentionally cancelled (paused or stopped)
        val cancelIntent = downloader.getCancelIntent(downloadId)
        if (cancelIntent != null) {
          downloader.clearCancelIntent(downloadId)
          logD(NAME, "Ignoring broadcast for ${cancelIntent.name.lowercase()} download: $downloadId")
          return
        }

        val config = downloadIdToConfig[downloadId]

        if (config != null) {
          val downloadStatus = downloader.checkDownloadStatus(downloadId)
          val status = downloadStatus.getInt("status")
          val localUri = downloadStatus.getString("localUri")

          stopTaskProgress(config.id)

          synchronized(sharedLock) {
            when (status) {
              DownloadManager.STATUS_SUCCESSFUL -> {
                onSuccessfulDownload(config, downloadStatus)
              }
              DownloadManager.STATUS_FAILED -> {
                onFailedDownload(config, downloadStatus)
              }
            }

            if (localUri != null) {
              // Prevent memory leaks from MediaScanner.
              // Download successful, clean task after media scanning.
              val paths = arrayOf(localUri)
              MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
                stopTask(config.id)
              }
            } else {
              // Download failed, clean task.
              stopTask(config.id)
            }
          }
        }
      }
    }

    compatRegisterReceiver(reactContext, downloadReceiver!!, filter, true)
    isReceiverRegistered = true
  }

  // TAKEN FROM
  // https://github.com/facebook/react-native/pull/38256/files\#diff-d5e21477eeadeb0c536d5870f487a8528f9a16ae928c397fec7b255805cc8ad3
  private fun compatRegisterReceiver(
    context: Context,
    receiver: BroadcastReceiver,
    filter: IntentFilter,
    exported: Boolean
  ) {
    if (Build.VERSION.SDK_INT >= 34 && context.applicationInfo.targetSdkVersion >= 34) {
      context.registerReceiver(
        receiver,
        filter,
        if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
      )
    } else {
      context.registerReceiver(receiver, filter)
    }
  }

  private fun unregisterDownloadReceiver() {
    downloadReceiver?.let {
      try {
        reactContext.unregisterReceiver(it)
      } catch (e: Exception) {
        logW(NAME, "Could not unregister receiver: ${e.message}")
      }
      downloadReceiver = null
      isReceiverRegistered = false
    }
  }

  private fun resumeTasks(downloadId: Long, config: RNBGDTaskConfig) {
    Thread {
      try {
        var bytesDownloaded: Long = 0
        var bytesTotal: Long = 0

        if (!config.reportedBegin) {
          val onBeginCallable = OnBegin(config, this::onBeginDownload)
          val onBeginFuture = cachedExecutorPool.submit(onBeginCallable)
          val onBeginState = onBeginFuture.get()
          bytesTotal = onBeginState.expectedBytes

          config.reportedBegin = true
          downloadIdToConfig[downloadId] = config
          saveDownloadIdToConfigMap()
        }

        val onProgressCallable = OnProgress(
          config,
          downloader,
          downloadId,
          bytesDownloaded,
          bytesTotal,
          this::onProgressDownload
        )
        val onProgressFuture = cachedExecutorPool.submit(onProgressCallable)
        configIdToProgressFuture[config.id] = onProgressFuture
      } catch (e: Exception) {
        logE(NAME, "resumeTasks: ${Log.getStackTraceString(e)}")
      }
    }.start()
  }

  private fun removeTaskFromMap(downloadId: Long) {
    synchronized(sharedLock) {
      val config = downloadIdToConfig[downloadId]

      if (config != null) {
        configIdToDownloadId.remove(config.id)
        progressReporter.clearDownloadState(config.id)
        downloadIdToConfig.remove(downloadId)
        saveDownloadIdToConfigMap()
      }
    }
  }

  /**
   * Cleans up all state associated with a download.
   * This consolidates cleanup logic that was previously duplicated across
   * onComplete, onError, stopTask, and removeTaskFromMap.
   *
   * @param configId The config ID of the download to clean up
   * @param downloadId Optional download ID for DownloadManager cleanup
   * @param removePausedState Whether to remove paused state from downloader
   */
  private fun cleanupDownloadState(
    configId: String,
    downloadId: Long? = null,
    removePausedState: Boolean = true
  ) {
    configIdToDownloadId.remove(configId)
    configIdToHeaders.remove(configId)
    progressReporter.clearDownloadState(configId)

    if (downloadId != null) {
      downloadIdToConfig.remove(downloadId)
      saveDownloadIdToConfigMap()
    }

    if (removePausedState) {
      downloader.removePausedState(configId)
    }
  }

  /**
   * Resolve redirects for a URL up to maxRedirects limit
   * @param originalUrl The original URL to follow
   * @param maxRedirects Maximum number of redirects to follow (0 means no redirect resolution)
   * @param headers Headers to include in redirect resolution requests
   * @return The final resolved URL, or original URL if maxRedirects is 0 or resolution fails
   */
  private fun resolveRedirects(originalUrl: String, maxRedirects: Int, headers: ReadableMap?): String {
    if (maxRedirects <= 0) {
      return originalUrl
    }

    try {
      var currentUrl = originalUrl
      var redirectCount = 0

      while (redirectCount < maxRedirects) {
        val url = URL(currentUrl)
        val connection = url.openConnection() as HttpURLConnection

        // Add headers to the redirect resolution request
        HeaderUtils.applyToConnection(connection, headers)

        // Add default headers for consistency with DownloadManager
        HeaderUtils.applyDefaultHeaders(connection, headers)

        connection.instanceFollowRedirects = false
        connection.requestMethod = "HEAD" // Use HEAD to avoid downloading content
        connection.connectTimeout = DownloadConstants.REDIRECT_TIMEOUT_MS
        connection.readTimeout = DownloadConstants.REDIRECT_TIMEOUT_MS

        val responseCode = connection.responseCode

        if (responseCode in 300..399) {
          // This is a redirect
          val location = connection.getHeaderField("Location")
          if (location == null) {
            logW(NAME, "Redirect response without Location header at: $currentUrl")
            break
          }

          // Handle relative URLs
          currentUrl = when {
            location.startsWith("/") -> {
              val baseUrl = URL(currentUrl)
              "${baseUrl.protocol}://${baseUrl.host}$location"
            }
            !location.startsWith("http") -> {
              val baseUrl = URL(currentUrl)
              "${baseUrl.protocol}://${baseUrl.host}/$location"
            }
            else -> location
          }

          logD(NAME, "Redirect ${redirectCount + 1}/$maxRedirects: $currentUrl -> $location")
          redirectCount++
        } else {
          // Not a redirect, we've found the final URL
          break
        }

        connection.disconnect()
      }

      if (redirectCount >= maxRedirects) {
        logW(
          NAME,
          "Reached maximum redirects ($maxRedirects) for URL: $originalUrl. Final URL: $currentUrl"
        )
      } else {
        logD(NAME, "Resolved URL after $redirectCount redirects: $currentUrl")
      }

      return currentUrl

    } catch (e: Exception) {
      logE(NAME, "Failed to resolve redirects for URL: $originalUrl. Error: ${e.message}")
      // Return original URL if redirect resolution fails
      return originalUrl
    }
  }

  fun download(options: ReadableMap) {
    // Ensure event emitter is initialized before starting download
    // This fixes issues on first app install where download() might be called
    // before initialize() completes
    ensureEventEmitterInitialized()

    // Ensure download receiver is registered before starting download
    // This fixes issues on first app install where download completion events
    // might be missed if download() is called before initialize() completes
    ensureReceiverRegistered()

    val id = options.getString("id")
    var url = options.getString("url")
    val destination = options.getString("destination")
    val headers = options.getMap("headers")
    // Handle metadata - it should be a string, but handle object case defensively
    val metadata = if (options.hasKey("metadata")) {
      when (options.getType("metadata")) {
        ReadableType.String -> options.getString("metadata")
        ReadableType.Map -> {
          // If passed as object, convert to JSON string
          try {
            val map = options.getMap("metadata")
            Arguments.toBundle(map)?.let {
              JSONObject(it.toString()).toString()
            } ?: "{}"
          } catch (e: Exception) {
            logW(NAME, "Failed to convert metadata map to string: ${e.message}")
            "{}"
          }
        }
        else -> null
      }
    } else null
    val notificationTitle = options.getString("notificationTitle")

    val progressIntervalScope = options.getInt("progressInterval")
    val progressMinBytesScope = options.getDouble("progressMinBytes").toLong()
    if (progressIntervalScope > 0 || progressMinBytesScope > 0) {
      val newInterval = if (progressIntervalScope > 0) progressIntervalScope.toLong() else progressReporter.getProgressInterval()
      val newMinBytes = if (progressMinBytesScope > 0) progressMinBytesScope else progressReporter.getProgressMinBytes()
      progressReporter.configure(newInterval, newMinBytes)
      saveConfigMap()
    }

    val isAllowedOverRoaming = options.getBoolean("isAllowedOverRoaming")
    val isAllowedOverMetered = options.getBoolean("isAllowedOverMetered")
    val isNotificationVisible = options.getBoolean("isNotificationVisible")

    // Get maxRedirects parameter
    var maxRedirects = 0
    if (options.hasKey("maxRedirects")) {
      maxRedirects = options.getInt("maxRedirects")
    }

    if (id == null || url == null || destination == null) {
      logE(NAME, "download: id, url and destination must be set.")
      return
    }

    // Resolve redirects if maxRedirects is specified
    if (maxRedirects > 0) {
      logD(NAME, "Resolving redirects for URL: $url (maxRedirects: $maxRedirects)")
      url = resolveRedirects(url, maxRedirects, headers)
      logD(NAME, "Final resolved URL: $url")
    }

    val request = DownloadManager.Request(Uri.parse(url))
    request.setAllowedOverRoaming(isAllowedOverRoaming)
    request.setAllowedOverMetered(isAllowedOverMetered)
    request.setNotificationVisibility(
      if (isNotificationVisible) DownloadManager.Request.VISIBILITY_VISIBLE
      else DownloadManager.Request.VISIBILITY_HIDDEN
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      request.setRequiresCharging(false)
    }

    notificationTitle?.let { request.setTitle(it) }

    // Add default headers to improve connection handling for slow-responding URLs
    HeaderUtils.applyDefaultHeaders(request, headers)

    headers?.let {
      val iterator = it.keySetIterator()
      while (iterator.hasNextKey()) {
        val headerKey = iterator.nextKey()
        request.addRequestHeader(headerKey, it.getString(headerKey))
      }
    }

    // DownloadManager requires external storage for downloads
    // Use app's private external files directory: /storage/emulated/0/Android/data/<package>/files/
    // This keeps files private to the app while being compatible with DownloadManager
    val uuid = (System.currentTimeMillis() and 0xfffffff).toInt()
    val extension = MimeTypeMap.getFileExtensionFromUrl(destination)
    val filename = "$uuid.$extension"

    // Get external files directory and validate it's a proper external storage path
    val externalFilesDir = reactContext.getExternalFilesDir(null)
    val isValidExternalPath = externalFilesDir != null &&
        (externalFilesDir.absolutePath.startsWith("/storage/") ||
         externalFilesDir.absolutePath.startsWith("/sdcard/") ||
         externalFilesDir.absolutePath.startsWith("/mnt/"))

    // Save headers for potential pause/resume functionality
    val headersMap = HeaderUtils.toMap(headers)

    // Helper function to start download with DownloadManager and track state
    fun startDownloadManagerDownload(downloadId: Long) {
      val config = RNBGDTaskConfig(id, url, destination, metadata ?: "{}", notificationTitle)
      synchronized(sharedLock) {
        // Clean up any stale state from previous downloads with the same ID
        downloader.cleanupStaleState(id)
        // Clear any stale progress data and initialize tracking for new download
        progressReporter.clearDownloadState(id)
        progressReporter.initializeDownload(id)
        configIdToDownloadId[id] = downloadId
        configIdToHeaders[id] = headersMap
        downloadIdToConfig[downloadId] = config
        saveDownloadIdToConfigMap()
        resumeTasks(downloadId, config)
      }
    }

    // Helper function to fall back to ResumableDownloader
    fun startWithResumableDownloader() {
      synchronized(sharedLock) {
        // Clean up any stale state from previous downloads with the same ID
        downloader.cleanupStaleState(id)
        // Clear any stale progress data and initialize tracking for new download
        progressReporter.clearDownloadState(id)
        progressReporter.initializeDownload(id)
        configIdToHeaders[id] = headersMap
      }
      downloader.startResumableDownload(id, url, destination, headersMap, resumableDownloadListener)
    }

    if (isValidExternalPath) {
      // Use DownloadManager with valid external storage path
      if (!externalFilesDir!!.exists()) {
        externalFilesDir.mkdirs()
      }
      val tempFile = File(externalFilesDir, filename)
      request.setDestinationUri(Uri.fromFile(tempFile))
      startDownloadManagerDownload(downloader.download(request))
    } else {
      // External files directory path is invalid or null
      // Try setDestinationInExternalFilesDir as fallback, then ResumableDownloader if that fails
      logW(NAME, "External files directory path may be invalid for DownloadManager: ${externalFilesDir?.absolutePath}")

      try {
        // Try standard setDestinationInExternalFilesDir - may work on some devices
        request.setDestinationInExternalFilesDir(reactContext, null, filename)
        startDownloadManagerDownload(downloader.download(request))
        logD(NAME, "Using setDestinationInExternalFilesDir for download: $id")
      } catch (e: Exception) {
        // DownloadManager failed - fall back to ResumableDownloader
        // This handles OnePlus and other devices that return paths like /data/local/tmp/external/
        logW(NAME, "DownloadManager failed with: ${e.message}")
        logD(NAME, "Using ResumableDownloader as fallback for download: $id")
        startWithResumableDownloader()
      }
    }
  }

  // Pause a download. If it's a DownloadManager download, this cancels it and saves state.
  // If it's already using ResumableDownloader, it pauses the HTTP connection.
  fun pauseTask(configId: String) {
    synchronized(sharedLock) {
      // First check if it's an active resumable download
      if (downloader.isResumableDownload(configId)) {
        downloader.pauseResumable(configId)
        logD(NAME, "Paused resumable download: $configId")
        return
      }

      // Otherwise, it's a DownloadManager download - pause by canceling and saving state
      val downloadId = configIdToDownloadId[configId]
      if (downloadId != null) {
        val config = downloadIdToConfig[downloadId]
        if (config != null) {
          val headers = configIdToHeaders[configId] ?: emptyMap()

          // Stop progress tracking
          stopTaskProgress(configId)

          // Pause the download (this cancels DownloadManager and saves state)
          val paused = downloader.pause(downloadId, configId, config.url, config.destination, headers)

          if (paused) {
            // Remove from DownloadManager tracking
            downloadIdToConfig.remove(downloadId)
            configIdToDownloadId.remove(configId)
            saveDownloadIdToConfigMap()
            logD(NAME, "Paused DownloadManager download: $configId")
          } else {
            logD(NAME, "pauseTask: Download not paused (may already be paused): $configId")
          }
        } else {
          logW(NAME, "pauseTask: No config found for downloadId: $downloadId")
        }
      } else {
        logW(NAME, "pauseTask: No download found for configId: $configId")
      }
    }
  }

  // Resume a paused download using HTTP Range headers via ResumableDownloader.
  fun resumeTask(configId: String) {
    synchronized(sharedLock) {
      // First check if it's a paused resumable download
      if (downloader.resumableDownloader.isPaused(configId)) {
        downloader.resumeResumable(configId, resumableDownloadListener)
        logD(NAME, "Resumed paused resumable download: $configId")
        return
      }

      // Check if we have saved paused state from a DownloadManager download
      if (downloader.isPaused(configId)) {
        val resumed = downloader.resume(configId, resumableDownloadListener)
        if (resumed) {
          logD(NAME, "Resumed download via ResumableDownloader: $configId")
        } else {
          logW(NAME, "Failed to resume download: $configId")
        }
        return
      }

      logW(NAME, "resumeTask: No paused download found for configId: $configId")
    }
  }

  fun stopTask(configId: String) {
    synchronized(sharedLock) {
      // Stop progress tracking
      stopTaskProgress(configId)

      // Cancel resumable download if active
      downloader.cancelResumable(configId)

      // Cancel DownloadManager download if active and clean up
      val downloadId = configIdToDownloadId[configId]
      if (downloadId != null) {
        downloader.cancel(downloadId)
      }

      // Clean up all download state
      cleanupDownloadState(configId, downloadId)
    }
  }

  fun completeHandler(configId: String) {
    // Firebase Performance compatibility: Add defensive programming to prevent crashes
    // when Firebase Performance SDK is installed and uses bytecode instrumentation

    logD(NAME, "completeHandler called with configId: $configId")

    // Defensive programming: Validate parameters
    if (configId.isEmpty()) {
      logW(NAME, "completeHandler: Invalid configId provided")
      return
    }

    try {
      // Currently this method doesn't have any implementation on Android
      // as completion handlers are handled differently than iOS.
      // This defensive structure ensures Firebase Performance compatibility.
      logD(NAME, "completeHandler executed successfully for configId: $configId")

    } catch (e: Exception) {
      // Catch any potential exceptions that might be thrown due to Firebase Performance
      // bytecode instrumentation interfering with method dispatch
      logE(NAME, "completeHandler: Exception occurred: ${Log.getStackTraceString(e)}")
    }
  }

  fun getExistingDownloadTasks(promise: Promise) {
    val foundTasks = Arguments.createArray()

    synchronized(sharedLock) {
      val query = DownloadManager.Query()
      try {
        downloader.downloadManager.query(query)?.use { cursor ->
          if (cursor.moveToFirst()) {
            do {
              val downloadStatus = downloader.getDownloadStatus(cursor)
              val downloadId = downloadStatus.getString("downloadId")?.toLong()

              if (downloadId != null && downloadIdToConfig.containsKey(downloadId)) {
                val config = downloadIdToConfig[downloadId]

                if (config != null) {
                  val status = downloadStatus.getInt("status")
                  // Handle completed downloads that weren't processed
                  if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val localUri = downloadStatus.getString("localUri")
                    if (localUri != null) {
                      try {
                        val future = setFileChangesBeforeCompletion(localUri, config.destination)
                        future.get()
                      } catch (e: Exception) {
                        logE(NAME, "Error moving completed download file: ${e.message}")
                        // Continue with normal processing even if file move fails
                      }
                    }
                  }

                  val params = Arguments.createMap()

                  params.putString("id", config.id)
                  params.putString("metadata", config.metadata)
                  val state = stateMap[status] ?: 0
                  params.putInt("state", state)

                  val bytesDownloaded = downloadStatus.getDouble("bytesDownloaded")
                  params.putDouble("bytesDownloaded", bytesDownloaded)
                  val bytesTotal = downloadStatus.getDouble("bytesTotal")
                  params.putDouble("bytesTotal", bytesTotal)
                  val percent = if (bytesTotal > 0) bytesDownloaded / bytesTotal else 0.0

                  foundTasks.pushMap(params)
                  configIdToDownloadId[config.id] = downloadId
                  progressReporter.setPercent(config.id, percent)
                }
              } else if (downloadId != null) {
                downloader.cancel(downloadId)
              }
            } while (cursor.moveToNext())
          }
        }
      } catch (e: Exception) {
        logE(NAME, "getExistingDownloadTasks: ${Log.getStackTraceString(e)}")
      }
    }

    promise.resolve(foundTasks)
  }

  fun addListener(eventName: String) {
  }

  fun removeListeners(count: Int) {
  }

  private fun onBeginDownload(configId: String, headers: WritableMap, expectedBytes: Long) {
    eventEmitter.emitBegin(configId, headers, expectedBytes)
  }

  private fun onProgressDownload(configId: String, bytesDownloaded: Long, bytesTotal: Long) {
    // Delegate all progress handling to ProgressReporter
    // It handles threshold filtering, batching, and emission
    progressReporter.reportProgress(configId, bytesDownloaded, bytesTotal)
  }

  private fun onSuccessfulDownload(config: RNBGDTaskConfig, downloadStatus: WritableMap) {
    val localUri = downloadStatus.getString("localUri")

    // TODO: We need to move it to a more suitable location.
    //     Maybe somewhere in downloadReceiver?
    // Feedback if any error occurs after downloading the file.
    try {
      val future = setFileChangesBeforeCompletion(localUri!!, config.destination)
      future.get()
    } catch (e: Exception) {
      val newDownloadStatus = Arguments.createMap()
      newDownloadStatus.putString("downloadId", downloadStatus.getString("downloadId"))
      newDownloadStatus.putInt("status", DownloadManager.STATUS_FAILED)
      newDownloadStatus.putInt("reason", DownloadManager.ERROR_UNKNOWN)
      newDownloadStatus.putString("reasonText", e.message)
      onFailedDownload(config, newDownloadStatus)
      return
    }

    eventEmitter.emitComplete(
      config.id,
      config.destination,
      downloadStatus.getDouble("bytesDownloaded").toLong(),
      downloadStatus.getDouble("bytesTotal").toLong()
    )
  }

  private fun onFailedDownload(config: RNBGDTaskConfig, downloadStatus: WritableMap) {
    logE(
      NAME, "onFailedDownload: " +
          "${downloadStatus.getInt("status")}:" +
          "${downloadStatus.getInt("reason")}:" +
          downloadStatus.getString("reasonText")
    )

    val reason = downloadStatus.getInt("reason")
    var reasonText = downloadStatus.getString("reasonText")

    // Enhanced handling for ERROR_CANNOT_RESUME (1008)
    if (reason == DownloadManager.ERROR_CANNOT_RESUME) {
      logW(
        NAME, "ERROR_CANNOT_RESUME detected for download: ${config.id}" +
            ". This is a known Android DownloadManager issue with larger files. " +
            "Consider restarting the download or using smaller file segments."
      )

      // Clean up the failed download entry
      removeTaskFromMap(downloadStatus.getString("downloadId")?.toLong() ?: 0L)

      // Provide more helpful error message
      reasonText =
        "ERROR_CANNOT_RESUME - Unable to resume download. This may occur with large files due to Android DownloadManager limitations. Try restarting the download."
    }

    eventEmitter.emitFailed(config.id, reasonText ?: "Unknown error", reason)
  }

  private fun saveDownloadIdToConfigMap() {
    synchronized(sharedLock) {
      storageManager.saveDownloadIdToConfigMap(downloadIdToConfig)
    }
  }

  private fun loadDownloadIdToConfigMap() {
    synchronized(sharedLock) {
      downloadIdToConfig = storageManager.loadDownloadIdToConfigMap()
    }
  }

  private fun saveConfigMap() {
    synchronized(sharedLock) {
      storageManager.saveProgressConfig(
        progressReporter.getProgressInterval(),
        progressReporter.getProgressMinBytes()
      )
    }
  }

  private fun loadConfigMap() {
    synchronized(sharedLock) {
      val (interval, minBytes) = storageManager.loadProgressConfig()
      if (interval > 0 || minBytes > 0) {
        progressReporter.configure(interval, minBytes)
      }
    }
  }

  private fun stopTaskProgress(configId: String) {
    val onProgressFuture = configIdToProgressFuture[configId]
    if (onProgressFuture != null) {
      onProgressFuture.cancel(true)
      configIdToProgressFuture.remove(configId)
    }
    // Clear any batched progress report to prevent stale data from being emitted
    // when a new download starts with the same configId
    progressReporter.clearPendingReport(configId)
  }

  private fun setFileChangesBeforeCompletion(targetSrc: String, destinationSrc: String): Future<Boolean> {
    return fixedExecutorPool.submit<Boolean> {
      val file = File(targetSrc)
      val destination = File(destinationSrc)
      var destinationParent: File? = null
      try {
        if (file.exists()) {
          FileUtils.rm(destination)
          destinationParent = FileUtils.mkdirParent(destination)
          FileUtils.mv(file, destination)
        }
      } catch (e: Exception) {
        FileUtils.rm(file)
        FileUtils.rm(destination)
        FileUtils.rm(destinationParent)
        throw Exception(e)
      }

      true
    }
  }
}

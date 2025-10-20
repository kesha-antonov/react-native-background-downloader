package com.eko

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaScannerConnection
import android.media.MediaScannerConnection.OnScanCompletedListener
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import com.eko.handlers.OnBegin
import com.eko.handlers.OnBeginState
import com.eko.handlers.OnProgress
import com.eko.handlers.OnProgressState
import com.eko.utils.FileUtils
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.tencent.mmkv.MMKV
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class RNBackgroundDownloaderModuleImpl(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private val cachedExecutorPool: ExecutorService = Executors.newCachedThreadPool()
  private val fixedExecutorPool: ExecutorService = Executors.newFixedThreadPool(1)
  private val downloader: Downloader
  private var downloadReceiver: BroadcastReceiver? = null
  private var downloadIdToConfig: MutableMap<Long?, RNBGDTaskConfig?> =
    HashMap<Long?, RNBGDTaskConfig?>()
  private val configIdToDownloadId: MutableMap<String?, Long?> = HashMap<String?, Long?>()
  private val configIdToPercent: MutableMap<String?, Double?> = HashMap<String?, Double?>()
  private val configIdToLastBytes: MutableMap<String?, Long?> = HashMap<String?, Long?>()
  private val configIdToProgressFuture: MutableMap<String?, Future<OnProgressState?>?> =
    HashMap<String?, Future<OnProgressState?>?>()
  private val progressReports: MutableMap<String?, WritableMap?> = HashMap<String?, WritableMap?>()
  private var progressInterval = 0
  private var progressMinBytes = (1024 * 1024 // Default 1MB
    ).toLong()
  private var lastProgressReportedAt = Date()
  private var ee: DeviceEventManagerModule.RCTDeviceEventEmitter? = null

  init {
    // Initialize SharedPreferences as fallback
    sharedPreferences =
      reactContext.getSharedPreferences(getName() + "_prefs", Context.MODE_PRIVATE)


    // Try to initialize MMKV with comprehensive error handling
    try {
      MMKV.initialize(reactContext)
      mmkv = MMKV.mmkvWithID(getName())
      isMMKVAvailable = true
      Log.d(getName(), "MMKV initialized successfully")
    } catch (e: UnsatisfiedLinkError) {
      Log.e(getName(), "Failed to initialize MMKV (libmmkv.so not found): " + e.message)
      Log.w(
        getName(),
        "This may be due to unsupported architecture (x86/ARMv7). Using SharedPreferences fallback."
      )
      Log.w(getName(), "Download persistence across app restarts will use basic storage.")
      mmkv = null
      isMMKVAvailable = false
    } catch (e: NoClassDefFoundError) {
      Log.e(getName(), "MMKV classes not found: " + e.message)
      Log.w(
        getName(),
        "MMKV library not available on this architecture. Using SharedPreferences fallback."
      )
      mmkv = null
      isMMKVAvailable = false
    } catch (e: Exception) {
      Log.e(getName(), "Failed to initialize MMKV: " + e.message)
      Log.w(getName(), "Using SharedPreferences fallback for persistence.")
      mmkv = null
      isMMKVAvailable = false
    }

    loadDownloadIdToConfigMap()
    loadConfigMap()

    downloader = Downloader(reactContext)
  }

  override fun getName(): String {
    return NAME
  }

  override fun getConstants(): MutableMap<String?, Any?> {
    val context: Context = this.getReactApplicationContext()
    val constants: MutableMap<String?, Any?> = HashMap<String?, Any?>()

    val externalDirectory = context.getExternalFilesDir(null)
    if (externalDirectory != null) {
      constants.put("documents", externalDirectory.getAbsolutePath())
    } else {
      constants.put("documents", context.getFilesDir().getAbsolutePath())
    }

    constants.put("TaskRunning", TASK_RUNNING)
    constants.put("TaskSuspended", TASK_SUSPENDED)
    constants.put("TaskCanceling", TASK_CANCELING)
    constants.put("TaskCompleted", TASK_COMPLETED)


    // Expose storage type information for debugging/monitoring
    constants.put("isMMKVAvailable", isMMKVAvailable)
    constants.put("storageType", if (isMMKVAvailable) "MMKV" else "SharedPreferences")

    return constants
  }

  override fun initialize() {
    super.initialize()
    ee = getReactApplicationContext().getJSModule<DeviceEventManagerModule.RCTDeviceEventEmitter?>(
      DeviceEventManagerModule.RCTDeviceEventEmitter::class.java
    )
    registerDownloadReceiver()

    for (entry in downloadIdToConfig.entries.filter { it.value != null }) {
      val downloadId = entry.key
      val config: RNBGDTaskConfig = entry.value!!
      resumeTasks(downloadId, config)
    }
  }

  override fun invalidate() {
    unregisterDownloadReceiver()
  }

  private fun registerDownloadReceiver() {
    val context: Context = getReactApplicationContext()
    val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

    downloadReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent) {
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        val config: RNBGDTaskConfig? = downloadIdToConfig.get(downloadId)

        if (config != null) {
          val downloadStatus: WritableMap = downloader.checkDownloadStatus(downloadId)
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
              val paths: Array<String> = arrayOf(localUri)
              MediaScannerConnection.scanFile(
                context,
                paths,
                null,
                OnScanCompletedListener { path: String?, uri: Uri? -> stopTask(config.id) })
            } else {
              // Download failed, clean task.
              stopTask(config.id)
            }
          }
        }
      }
    }

    compatRegisterReceiver(context, downloadReceiver, filter, true)
  }

  // TAKEN FROM
  // https://github.com/facebook/react-native/pull/38256/files#diff-d5e21477eeadeb0c536d5870f487a8528f9a16ae928c397fec7b255805cc8ad3
  private fun compatRegisterReceiver(
    context: Context, receiver: BroadcastReceiver?, filter: IntentFilter?,
    exported: Boolean
  ) {
    if (Build.VERSION.SDK_INT >= 34 && context.getApplicationInfo().targetSdkVersion >= 34) {
      context.registerReceiver(
        receiver, filter, if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
      )
    } else {
      context.registerReceiver(receiver, filter)
    }
  }

  private fun unregisterDownloadReceiver() {
    if (downloadReceiver != null) {
      getReactApplicationContext().unregisterReceiver(downloadReceiver)
      downloadReceiver = null
    }
  }

  private fun resumeTasks(downloadId: Long?, config: RNBGDTaskConfig) {
    Thread(Runnable {
      try {
        val bytesDownloaded: Long = 0
        var bytesTotal: Long = 0

        if (!config.reportedBegin) {
          val onBeginCallable: OnBegin = OnBegin(
            config,
            { configId: String?, headers: WritableMap?, expectedBytes: Long ->
              this.onBeginDownload(
                configId,
                headers,
                expectedBytes
              )
            })
          val onBeginFuture: Future<OnBeginState> =
            cachedExecutorPool.submit<OnBeginState?>(onBeginCallable)
          val onBeginState: OnBeginState = onBeginFuture.get()
          bytesTotal = onBeginState.expectedBytes

          config.reportedBegin = true
          downloadIdToConfig.put(downloadId, config)
          saveDownloadIdToConfigMap()
        }

        val onProgressCallable: OnProgress = OnProgress(
          config,
          downloader,
          downloadId!!,
          bytesDownloaded,
          bytesTotal,
          { configId: String?, bytesDownloaded: Long, bytesTotal: Long ->
            this.onProgressDownload(
              configId,
              bytesDownloaded,
              bytesTotal
            )
          })
        val onProgressFuture: Future<OnProgressState?> =
          cachedExecutorPool.submit<OnProgressState?>(onProgressCallable)
        configIdToProgressFuture.put(config.id, onProgressFuture)
      } catch (e: Exception) {
        Log.e(getName(), "resumeTasks: " + Log.getStackTraceString(e))
      }
    }).start()
  }

  private fun removeTaskFromMap(downloadId: Long) {
    synchronized(sharedLock) {
      val config: RNBGDTaskConfig? = downloadIdToConfig.get(downloadId)
      if (config != null) {
        configIdToDownloadId.remove(config.id)
        configIdToPercent.remove(config.id)
        configIdToLastBytes.remove(config.id)
        downloadIdToConfig.remove(downloadId)
        saveDownloadIdToConfigMap()
      }
    }
  }

  /**
   * Resolve redirects for a URL up to maxRedirects limit
   * @param originalUrl The original URL to follow
   * @param maxRedirects Maximum number of redirects to follow (0 means no redirect resolution)
   * @param headers Headers to include in redirect resolution requests
   * @return The final resolved URL, or original URL if maxRedirects is 0 or resolution fails
   */
  private fun resolveRedirects(
    originalUrl: String,
    maxRedirects: Int,
    headers: ReadableMap?
  ): String? {
    if (maxRedirects <= 0) {
      return originalUrl
    }

    try {
      var currentUrl: String? = originalUrl
      var redirectCount = 0

      while (redirectCount < maxRedirects) {
        val url = URL(currentUrl)
        val connection = url.openConnection() as HttpURLConnection


        // Add headers to the redirect resolution request
        if (headers != null) {
          val iterator = headers.keySetIterator()
          while (iterator.hasNextKey()) {
            val headerKey = iterator.nextKey()
            connection.setRequestProperty(headerKey, headers.getString(headerKey))
          }
        }


        // Add default headers for consistency with DownloadManager
        connection.setRequestProperty("Connection", "keep-alive")
        connection.setRequestProperty("Keep-Alive", "timeout=600, max=1000")
        if (!hasUserAgentHeader(headers)) {
          connection.setRequestProperty("User-Agent", "ReactNative-BackgroundDownloader/3.2.6")
        }

        connection.setInstanceFollowRedirects(false)
        connection.setRequestMethod("HEAD") // Use HEAD to avoid downloading content
        connection.setConnectTimeout(10000) // 10 second timeout
        connection.setReadTimeout(10000)

        val responseCode = connection.getResponseCode()

        if (responseCode >= 300 && responseCode < 400) {
          // This is a redirect
          var location = connection.getHeaderField("Location")
          if (location == null) {
            Log.w(getName(), "Redirect response without Location header at: " + currentUrl)
            break
          }


          // Handle relative URLs
          if (location.startsWith("/")) {
            val baseUrl = URL(currentUrl)
            location = baseUrl.getProtocol() + "://" + baseUrl.getHost() + location
          } else if (!location.startsWith("http")) {
            val baseUrl = URL(currentUrl)
            location = baseUrl.getProtocol() + "://" + baseUrl.getHost() + "/" + location
          }

          Log.d(
            getName(),
            "Redirect " + (redirectCount + 1) + "/" + maxRedirects + ": " + currentUrl + " -> " + location
          )
          currentUrl = location
          redirectCount++
        } else {
          // Not a redirect, we've found the final URL
          break
        }

        connection.disconnect()
      }

      if (redirectCount >= maxRedirects) {
        Log.w(
          getName(), "Reached maximum redirects (" + maxRedirects + ") for URL: " + originalUrl +
            ". Final URL: " + currentUrl
        )
      } else {
        Log.d(getName(), "Resolved URL after " + redirectCount + " redirects: " + currentUrl)
      }

      return currentUrl
    } catch (e: Exception) {
      Log.e(
        getName(),
        "Failed to resolve redirects for URL: " + originalUrl + ". Error: " + e.message
      )
      // Return original URL if redirect resolution fails
      return originalUrl
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun download(options: ReadableMap) {
    val id = options.getString("id")
    var url = options.getString("url")
    val destination = options.getString("destination")
    val headers = options.getMap("headers")
    val metadata = options.getString("metadata")
    val notificationTitle = options.getString("notificationTitle")
    val progressIntervalScope = options.getInt("progressInterval")
    if (progressIntervalScope > 0) {
      progressInterval = progressIntervalScope
      saveConfigMap()
    }

    val progressMinBytesScope = options.getDouble("progressMinBytes")
    if (progressMinBytesScope > 0) {
      progressMinBytes = progressMinBytesScope.toLong()
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
      Log.e(getName(), "download: id, url and destination must be set.")
      return
    }

    // Resolve redirects if maxRedirects is specified
    if (maxRedirects > 0) {
      Log.d(
        getName(),
        "Resolving redirects for URL: " + url + " (maxRedirects: " + maxRedirects + ")"
      )
      url = resolveRedirects(url, maxRedirects, headers)
      Log.d(getName(), "Final resolved URL: " + url)
    }

    val request = DownloadManager.Request(Uri.parse(url))
    request.setAllowedOverRoaming(isAllowedOverRoaming)
    request.setAllowedOverMetered(isAllowedOverMetered)
    request.setNotificationVisibility(if (isNotificationVisible) DownloadManager.Request.VISIBILITY_VISIBLE else DownloadManager.Request.VISIBILITY_HIDDEN)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      request.setRequiresCharging(false)
    }

    if (notificationTitle != null) {
      request.setTitle(notificationTitle)
    }

    // Add default headers to improve connection handling for slow-responding URLs
    // These headers encourage longer connections and help prevent premature
    // timeouts
    request.addRequestHeader("Connection", "keep-alive")
    request.addRequestHeader("Keep-Alive", "timeout=600, max=1000")

    // Add a proper User-Agent to improve server compatibility
    if (!hasUserAgentHeader(headers)) {
      request.addRequestHeader("User-Agent", "ReactNative-BackgroundDownloader/3.2.6")
    }

    if (headers != null) {
      val iterator = headers.keySetIterator()
      while (iterator.hasNextKey()) {
        val headerKey = iterator.nextKey()
        request.addRequestHeader(headerKey, headers.getString(headerKey))
      }
    }

    val uuid = (System.currentTimeMillis() and 0xfffffffL).toInt()
    val extension = MimeTypeMap.getFileExtensionFromUrl(destination)
    val filename = uuid.toString() + "." + extension
    request.setDestinationInExternalFilesDir(this.getReactApplicationContext(), null, filename)

    val downloadId: Long = downloader.download(request)
    val config: RNBGDTaskConfig = RNBGDTaskConfig(id, url, destination, metadata, notificationTitle)

    synchronized(sharedLock) {
      configIdToDownloadId.put(id, downloadId)
      configIdToPercent.put(id, 0.0)
      downloadIdToConfig.put(downloadId, config)
      saveDownloadIdToConfigMap()
      resumeTasks(downloadId, config)
    }
  }

  // Pause functionality is not supported by Android DownloadManager.
  // This method will throw an UnsupportedOperationException to clearly indicate
  // that pause is not available on Android platform.
  @ReactMethod
  @Suppress("unused")
  fun pauseTask(configId: String?) {
    synchronized(sharedLock) {
      val downloadId = configIdToDownloadId.get(configId)
      if (downloadId != null) {
        try {
          downloader.pause(downloadId)
        } catch (e: UnsupportedOperationException) {
          Log.w("RNBackgroundDownloader", "pauseTask: " + e.message)
          // Note: We don't rethrow the exception to avoid crashing the JS thread.
          // The limitation is already documented and expected.
        }
      }
    }
  }

  // Resume functionality is not supported by Android DownloadManager.
  // This method will throw an UnsupportedOperationException to clearly indicate
  // that resume is not available on Android platform.
  @ReactMethod
  @Suppress("unused")
  fun resumeTask(configId: String?) {
    synchronized(sharedLock) {
      val downloadId = configIdToDownloadId.get(configId)
      if (downloadId != null) {
        try {
          downloader.resume(downloadId)
        } catch (e: UnsupportedOperationException) {
          Log.w("RNBackgroundDownloader", "resumeTask: " + e.message)
          // Note: We don't rethrow the exception to avoid crashing the JS thread.
          // The limitation is already documented and expected.
        }
      }
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun stopTask(configId: String?) {
    synchronized(sharedLock) {
      val downloadId = configIdToDownloadId.get(configId)
      if (downloadId != null) {
        stopTaskProgress(configId)
        removeTaskFromMap(downloadId)
        downloader.cancel(downloadId)
      }
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun completeHandler(configId: String?) {
    // Firebase Performance compatibility: Add defensive programming to prevent crashes
    // when Firebase Performance SDK is installed and uses bytecode instrumentation

    Log.d(getName(), "completeHandler called with configId: " + configId)

    // Defensive programming: Validate parameters
    if (configId == null || configId.isEmpty()) {
      Log.w(getName(), "completeHandler: Invalid configId provided")
      return
    }

    try {
      // Currently this method doesn't have any implementation on Android
      // as completion handlers are handled differently than iOS.
      // This defensive structure ensures Firebase Performance compatibility.
      Log.d(getName(), "completeHandler executed successfully for configId: " + configId)
    } catch (e: Exception) {
      // Catch any potential exceptions that might be thrown due to Firebase Performance
      // bytecode instrumentation interfering with method dispatch
      Log.e(getName(), "completeHandler: Exception occurred: " + Log.getStackTraceString(e))
    }
  }

  @ReactMethod
  @Suppress("unused")
  fun checkForExistingDownloads(promise: Promise) {
    val foundTasks = Arguments.createArray()

    synchronized(sharedLock) {
      val query = DownloadManager.Query()
      try {
        downloader.downloadManager.query(query).use { cursor ->
          if (cursor.moveToFirst()) {
            do {
              val downloadStatus: WritableMap = downloader.getDownloadStatus(cursor)
              val downloadId = downloadStatus.getString("downloadId")!!.toLong()

              if (downloadIdToConfig.containsKey(downloadId)) {
                val config: RNBGDTaskConfig? = downloadIdToConfig.get(downloadId)

                if (config != null) {
                  val status = downloadStatus.getInt("status")
                  // Handle completed downloads that weren't processed
                  if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val localUri = downloadStatus.getString("localUri")
                    if (localUri != null) {
                      try {
                        val future = setFileChangesBeforeCompletion(localUri, config.destination!!)
                        future.get()
                      } catch (e: Exception) {
                        Log.e(getName(), "Error moving completed download file: " + e.message)
                        // Continue with normal processing even if file move fails
                      }
                    }
                  }

                  val params = Arguments.createMap()

                  params.putString("id", config.id)
                  params.putString("metadata", config.metadata)
                  val statusMapping = stateMap.get(status)
                  val state = if (statusMapping != null) statusMapping else 0
                  params.putInt("state", state)
                  params.putInt("savedTaskState", state)

                  val bytesDownloaded = downloadStatus.getDouble("bytesDownloaded")
                  params.putDouble("bytesDownloaded", bytesDownloaded)
                  val bytesTotal = downloadStatus.getDouble("bytesTotal")
                  params.putDouble("bytesTotal", bytesTotal)
                  val percent = if (bytesTotal > 0) bytesDownloaded / bytesTotal else 0.0

                  foundTasks.pushMap(params)
                  configIdToDownloadId.put(config.id, downloadId)
                  configIdToPercent.put(config.id, percent)
                }
              } else {
                downloader.cancel(downloadId)
              }
            } while (cursor.moveToNext())
          }
        }
      } catch (e: Exception) {
        Log.e(getName(), "checkForExistingDownloads: " + Log.getStackTraceString(e))
      }
    }

    promise.resolve(foundTasks)
  }

  @ReactMethod
  @Suppress("unused")
  fun addListener(eventName: String?) {
  }

  @ReactMethod
  @Suppress("unused")
  fun removeListeners(count: Int?) {
  }

  private fun onBeginDownload(configId: String?, headers: WritableMap?, expectedBytes: Long) {
    val params = Arguments.createMap()
    params.putString("id", configId)
    params.putMap("headers", headers)
    params.putDouble("expectedBytes", expectedBytes.toDouble())
    ee!!.emit("downloadBegin", params)
  }

  private fun onProgressDownload(configId: String?, bytesDownloaded: Long, bytesTotal: Long) {
    val existPercent = configIdToPercent.get(configId)
    val existLastBytes = configIdToLastBytes.get(configId)
    val prevPercent = if (existPercent != null) existPercent else 0.0
    val prevBytes = if (existLastBytes != null) existLastBytes else 0
    val percent = if (bytesTotal > 0.0) (bytesDownloaded.toDouble() / bytesTotal) else 0.0

    // Check if we should report progress based on percentage OR bytes threshold
    val percentThresholdMet = percent - prevPercent > 0.01
    val bytesThresholdMet = bytesDownloaded - prevBytes >= progressMinBytes

    // Report progress if either threshold is met, or if total bytes unknown (for realtime streams)
    if (percentThresholdMet || bytesThresholdMet || bytesTotal <= 0) {
      val params = Arguments.createMap()
      params.putString("id", configId)
      params.putDouble("bytesDownloaded", bytesDownloaded.toDouble())
      params.putDouble("bytesTotal", bytesTotal.toDouble())
      progressReports.put(configId, params)
      configIdToPercent.put(configId, percent)
      configIdToLastBytes.put(configId, bytesDownloaded)
    }

    val now = Date()
    val isReportTimeDifference = now.getTime() - lastProgressReportedAt.getTime() > progressInterval
    val isReportNotEmpty = !progressReports.isEmpty()
    if (isReportTimeDifference && isReportNotEmpty) {
      // Extra steps to avoid map always consumed errors.
      val reportsList: MutableList<WritableMap?> = ArrayList<WritableMap?>(progressReports.values)
      val reportsArray = Arguments.createArray()
      for (report in reportsList) {
        if (report != null) {
          reportsArray.pushMap(report.copy())
        }
      }
      ee!!.emit("downloadProgress", reportsArray)
      lastProgressReportedAt = now
      progressReports.clear()
    }
  }

  private fun onSuccessfulDownload(config: RNBGDTaskConfig, downloadStatus: WritableMap) {
    val localUri = downloadStatus.getString("localUri")

    // TODO: We need to move it to a more suitable location.
    //       Maybe somewhere in downloadReceiver?
    // Feedback if any error occurs after downloading the file.
    try {
      val future = setFileChangesBeforeCompletion(localUri!!, config.destination!!)
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

    val params = Arguments.createMap()
    params.putString("id", config.id)
    params.putString("location", config.destination)
    params.putDouble("bytesDownloaded", downloadStatus.getDouble("bytesDownloaded"))
    params.putDouble("bytesTotal", downloadStatus.getDouble("bytesTotal"))
    ee!!.emit("downloadComplete", params)
  }

  private fun onFailedDownload(config: RNBGDTaskConfig, downloadStatus: WritableMap) {
    Log.e(
      getName(), "onFailedDownload: " +
        downloadStatus.getInt("status") + ":" +
        downloadStatus.getInt("reason") + ":" +
        downloadStatus.getString("reasonText")
    )

    val reason = downloadStatus.getInt("reason")
    var reasonText = downloadStatus.getString("reasonText")

    // Enhanced handling for ERROR_CANNOT_RESUME (1008)
    if (reason == DownloadManager.ERROR_CANNOT_RESUME) {
      Log.w(
        getName(), "ERROR_CANNOT_RESUME detected for download: " + config.id +
          ". This is a known Android DownloadManager issue with larger files. " +
          "Consider restarting the download or using smaller file segments."
      )

      // Clean up the failed download entry
      removeTaskFromMap(downloadStatus.getString("downloadId")!!.toLong())

      // Provide more helpful error message
      reasonText =
        "ERROR_CANNOT_RESUME - Unable to resume download. This may occur with large files due to Android DownloadManager limitations. Try restarting the download."
    }

    val params = Arguments.createMap()
    params.putString("id", config.id)
    params.putInt("errorCode", reason)
    params.putString("error", reasonText)
    ee!!.emit("downloadFailed", params)
  }

  private fun saveDownloadIdToConfigMap() {
    synchronized(sharedLock) {
      try {
        val gson: Gson = Gson()
        val str: String? = gson.toJson(downloadIdToConfig)

        if (isMMKVAvailable && mmkv != null) {
          mmkv!!.encode(getName() + "_downloadIdToConfig", str)
          Log.d(getName(), "Saved download config to MMKV")
        } else if (sharedPreferences != null) {
          sharedPreferences!!.edit {
            putString(getName() + "_downloadIdToConfig", str)
          }
          Log.d(getName(), "Saved download config to SharedPreferences fallback")
        } else {
          Log.w(getName(), "No storage available, skipping download config persistence")
        }
      } catch (e: Exception) {
        Log.e(getName(), "Failed to save download config: " + e.message)
      }
    }
  }

  private fun loadDownloadIdToConfigMap() {
    synchronized(sharedLock) {
      downloadIdToConfig = HashMap<Long?, RNBGDTaskConfig?>()
      try {
        var str: String? = null

        if (isMMKVAvailable && mmkv != null) {
          str = mmkv!!.decodeString(getName() + "_downloadIdToConfig")
          if (str != null) {
            Log.d(getName(), "Loaded download config from MMKV")
          }
        } else if (sharedPreferences != null) {
          str = sharedPreferences!!.getString(getName() + "_downloadIdToConfig", null)
          if (str != null) {
            Log.d(getName(), "Loaded download config from SharedPreferences fallback")
          }
        }

        if (str != null) {
          val gson: Gson = Gson()
          val mapType: TypeToken<MutableMap<Long?, RNBGDTaskConfig?>?> =
            object : TypeToken<MutableMap<Long?, RNBGDTaskConfig?>?>() {}
          downloadIdToConfig = gson.fromJson(str, mapType)!!
        } else {
          Log.d(getName(), "No existing download config found, starting with empty map")
        }
      } catch (e: Exception) {
        Log.e(getName(), "Failed to load download config: " + e.message)
        downloadIdToConfig = HashMap<Long?, RNBGDTaskConfig?>()
      }
    }
  }

  private fun saveConfigMap() {
    synchronized(sharedLock) {
      try {
        if (isMMKVAvailable && mmkv != null) {
          mmkv!!.encode(getName() + "_progressInterval", progressInterval)
          mmkv!!.encode(getName() + "_progressMinBytes", progressMinBytes)
          Log.d(getName(), "Saved config to MMKV")
        } else if (sharedPreferences != null) {
          sharedPreferences!!.edit {
            putInt(getName() + "_progressInterval", progressInterval)
              .putLong(getName() + "_progressMinBytes", progressMinBytes)
          }
          Log.d(getName(), "Saved config to SharedPreferences fallback")
        } else {
          Log.w(getName(), "No storage available, skipping config persistence")
        }
      } catch (e: Exception) {
        Log.e(getName(), "Failed to save config: " + e.message)
      }
    }
  }

  private fun loadConfigMap() {
    synchronized(sharedLock) {
      try {
        if (isMMKVAvailable && mmkv != null) {
          val progressIntervalScope: Int = mmkv!!.decodeInt(getName() + "_progressInterval")
          if (progressIntervalScope > 0) {
            progressInterval = progressIntervalScope
          }
          val progressMinBytesScope: Long = mmkv!!.decodeLong(getName() + "_progressMinBytes")
          if (progressMinBytesScope > 0) {
            progressMinBytes = progressMinBytesScope
          }
          Log.d(getName(), "Loaded config from MMKV")
        } else if (sharedPreferences != null) {
          val progressIntervalScope = sharedPreferences!!.getInt(getName() + "_progressInterval", 0)
          if (progressIntervalScope > 0) {
            progressInterval = progressIntervalScope
          }
          val progressMinBytesScope = sharedPreferences!!.getLong(getName() + "_progressMinBytes", 0)
          if (progressMinBytesScope > 0) {
            progressMinBytes = progressMinBytesScope
          }
          Log.d(getName(), "Loaded config from SharedPreferences fallback")
        } else {
          Log.d(getName(), "No storage available, using default config values")
        }
      } catch (e: Exception) {
        Log.e(getName(), "Failed to load config: " + e.message)
      }
    }
  }

  private fun stopTaskProgress(configId: String?) {
    val onProgressFuture: Future<OnProgressState?>? = configIdToProgressFuture.get(configId)
    if (onProgressFuture != null) {
      onProgressFuture.cancel(true)
      configIdToPercent.remove(configId)
      configIdToLastBytes.remove(configId)
      configIdToProgressFuture.remove(configId)
    }
  }

  private fun setFileChangesBeforeCompletion(
    targetSrc: String,
    destinationSrc: String
  ): Future<Boolean?> {
    return fixedExecutorPool.submit<Boolean?>(Callable {
      val file = File(targetSrc)
      val destination = File(destinationSrc)
      var destinationParent: File? = null
      try {
        if (file.exists()) {
          FileUtils.rm(destination)
          destinationParent = FileUtils.mkdirParent(destination)
          FileUtils.mv(file, destination)
        }
      } catch (e: IOException) {
        FileUtils.rm(file)
        FileUtils.rm(destination)
        FileUtils.rm(destinationParent)
        throw Exception(e)
      }
      true
    })
  }

  /**
   * Check if the provided headers already contain a User-Agent header
   * (case-insensitive)
   */
  private fun hasUserAgentHeader(headers: ReadableMap?): Boolean {
    if (headers == null) {
      return false
    }

    val iterator = headers.keySetIterator()
    while (iterator.hasNextKey()) {
      val headerKey = iterator.nextKey()
      if (headerKey != null && headerKey.lowercase(Locale.getDefault()) == "user-agent") {
        return true
      }
    }

    return false
  }

  companion object {
    const val NAME: String = "RNBackgroundDownloader"

    private const val TASK_RUNNING = 0
    private const val TASK_SUSPENDED = 1
    private const val TASK_CANCELING = 2
    private const val TASK_COMPLETED = 3

    private const val ERR_STORAGE_FULL = 0
    private const val ERR_NO_INTERNET = 1
    private const val ERR_NO_WRITE_PERMISSION = 2
    private const val ERR_FILE_NOT_FOUND = 3
    private const val ERR_OTHERS = 100
    private val stateMap: MutableMap<Int?, Int?> = object : HashMap<Int?, Int?>() {
      init {
        put(DownloadManager.STATUS_FAILED, TASK_CANCELING)
        put(DownloadManager.STATUS_PAUSED, TASK_SUSPENDED)
        put(DownloadManager.STATUS_PENDING, TASK_RUNNING)
        put(DownloadManager.STATUS_RUNNING, TASK_RUNNING)
        put(DownloadManager.STATUS_SUCCESSFUL, TASK_COMPLETED)
      }
    }

    private var mmkv: MMKV? = null
    private var sharedPreferences: SharedPreferences? = null
    private var isMMKVAvailable = false
    private val sharedLock = Any()
  }
}

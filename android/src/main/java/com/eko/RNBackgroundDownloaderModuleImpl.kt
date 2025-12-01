package com.eko

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.NonNull
import com.eko.handlers.OnBegin
import com.eko.handlers.OnBeginState
import com.eko.handlers.OnProgress
import com.eko.handlers.OnProgressState
import com.eko.utils.FileUtils
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class RNBackgroundDownloaderModuleImpl(private val reactContext: ReactApplicationContext) {

    companion object {
        const val NAME = "RNBackgroundDownloader"

        // Library version
        private const val VERSION = "3.2.6"
        private const val USER_AGENT = "ReactNative-BackgroundDownloader/$VERSION"

        // Task state constants
        private const val TASK_RUNNING = 0
        private const val TASK_SUSPENDED = 1
        private const val TASK_CANCELING = 2
        private const val TASK_COMPLETED = 3

        // Error code constants
        private const val ERR_STORAGE_FULL = 0
        private const val ERR_NO_INTERNET = 1
        private const val ERR_NO_WRITE_PERMISSION = 2
        private const val ERR_FILE_NOT_FOUND = 3
        private const val ERR_OTHERS = 100

        // Network timeout constants (milliseconds)
        private const val REDIRECT_CONNECT_TIMEOUT_MS = 10000  // 10 seconds
        private const val REDIRECT_READ_TIMEOUT_MS = 10000     // 10 seconds

        // Progress reporting constants
        private const val PROGRESS_REPORT_THRESHOLD = 0.01  // 1% change

        // HTTP header constants
        private const val KEEP_ALIVE_HEADER_VALUE = "timeout=600, max=1000"

        private val stateMap = mapOf(
            DownloadManager.STATUS_FAILED to TASK_CANCELING,
            DownloadManager.STATUS_PAUSED to TASK_SUSPENDED,
            DownloadManager.STATUS_PENDING to TASK_RUNNING,
            DownloadManager.STATUS_RUNNING to TASK_RUNNING,
            DownloadManager.STATUS_SUCCESSFUL to TASK_COMPLETED
        )

        private var mmkv: MMKV? = null
        private lateinit var sharedPreferences: SharedPreferences
        private var isMMKVAvailable = false
        private val sharedLock = Any()
    }

    private val cachedExecutorPool: ExecutorService = Executors.newCachedThreadPool()
    private val fixedExecutorPool: ExecutorService = Executors.newFixedThreadPool(1)
    private val downloader: Downloader
    private var downloadReceiver: BroadcastReceiver? = null
    private var downloadIdToConfig = mutableMapOf<Long, RNBGDTaskConfig>()
    private val configIdToDownloadId = mutableMapOf<String, Long>()
    private val configIdToPercent = mutableMapOf<String, Double>()
    private val configIdToLastBytes = mutableMapOf<String, Long>()
    private val configIdToProgressFuture = mutableMapOf<String, Future<OnProgressState?>>()
    private val progressReports = mutableMapOf<String, WritableMap>()
    private var progressInterval = 0
    private var progressMinBytes: Long = 0
    private var lastProgressReportedAt = Date()
    private lateinit var ee: DeviceEventManagerModule.RCTDeviceEventEmitter

    init {
        // Initialize SharedPreferences as fallback
        sharedPreferences = reactContext.getSharedPreferences(NAME + "_prefs", Context.MODE_PRIVATE)

        // Try to initialize MMKV with comprehensive error handling
        try {
            MMKV.initialize(reactContext)
            mmkv = MMKV.mmkvWithID(NAME)
            isMMKVAvailable = true
            Log.d(NAME, "MMKV initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(NAME, "Failed to initialize MMKV (libmmkv.so not found): ${e.message}")
            Log.w(NAME, "This may be due to unsupported architecture (x86/ARMv7). Using SharedPreferences fallback.")
            Log.w(NAME, "Download persistence across app restarts will use basic storage.")
            mmkv = null
            isMMKVAvailable = false
        } catch (e: NoClassDefFoundError) {
            Log.e(NAME, "MMKV classes not found: ${e.message}")
            Log.w(NAME, "MMKV library not available on this architecture. Using SharedPreferences fallback.")
            mmkv = null
            isMMKVAvailable = false
        } catch (e: Exception) {
            Log.e(NAME, "Failed to initialize MMKV: ${e.message}")
            Log.w(NAME, "Using SharedPreferences fallback for persistence.")
            mmkv = null
            isMMKVAvailable = false
        }

        loadDownloadIdToConfigMap()
        loadConfigMap()

        downloader = Downloader(reactContext)
    }

    fun getConstants(): Map<String, Any>? {
        val constants = mutableMapOf<String, Any>()

        val externalDirectory = reactContext.getExternalFilesDir(null)
        constants["documents"] = externalDirectory?.absolutePath ?: reactContext.filesDir.absolutePath

        constants["TaskRunning"] = TASK_RUNNING
        constants["TaskSuspended"] = TASK_SUSPENDED
        constants["TaskCanceling"] = TASK_CANCELING
        constants["TaskCompleted"] = TASK_COMPLETED

        // Expose storage type information for debugging/monitoring
        constants["isMMKVAvailable"] = isMMKVAvailable
        constants["storageType"] = if (isMMKVAvailable) "MMKV" else "SharedPreferences"

        return constants
    }

    fun initialize() {
        ee = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        registerDownloadReceiver()

        for ((downloadId, config) in downloadIdToConfig) {
            resumeTasks(downloadId, config)
        }
    }

    fun invalidate() {
        unregisterDownloadReceiver()
    }

    private fun registerDownloadReceiver() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
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
            reactContext.unregisterReceiver(it)
            downloadReceiver = null
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
                Log.e(NAME, "resumeTasks: ${Log.getStackTraceString(e)}")
            }
        }.start()
    }

    private fun removeTaskFromMap(downloadId: Long) {
        synchronized(sharedLock) {
            val config = downloadIdToConfig[downloadId]

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
                headers?.let {
                    val iterator = it.keySetIterator()
                    while (iterator.hasNextKey()) {
                        val headerKey = iterator.nextKey()
                        connection.setRequestProperty(headerKey, it.getString(headerKey))
                    }
                }

                // Add default headers for consistency with DownloadManager
                connection.setRequestProperty("Connection", "keep-alive")
                connection.setRequestProperty("Keep-Alive", KEEP_ALIVE_HEADER_VALUE)
                if (!hasUserAgentHeader(headers)) {
                    connection.setRequestProperty("User-Agent", USER_AGENT)
                }

                connection.instanceFollowRedirects = false
                connection.requestMethod = "HEAD" // Use HEAD to avoid downloading content
                connection.connectTimeout = REDIRECT_CONNECT_TIMEOUT_MS
                connection.readTimeout = REDIRECT_READ_TIMEOUT_MS

                val responseCode = connection.responseCode

                if (responseCode in 300..399) {
                    // This is a redirect
                    val location = connection.getHeaderField("Location")
                    if (location == null) {
                        Log.w(NAME, "Redirect response without Location header at: $currentUrl")
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

                    Log.d(NAME, "Redirect ${redirectCount + 1}/$maxRedirects: $currentUrl -> $location")
                    redirectCount++
                } else {
                    // Not a redirect, we've found the final URL
                    break
                }

                connection.disconnect()
            }

            if (redirectCount >= maxRedirects) {
                Log.w(
                    NAME,
                    "Reached maximum redirects ($maxRedirects) for URL: $originalUrl. Final URL: $currentUrl"
                )
            } else {
                Log.d(NAME, "Resolved URL after $redirectCount redirects: $currentUrl")
            }

            return currentUrl

        } catch (e: Exception) {
            Log.e(NAME, "Failed to resolve redirects for URL: $originalUrl. Error: ${e.message}")
            // Return original URL if redirect resolution fails
            return originalUrl
        }
    }

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
            Log.e(NAME, "download: id, url and destination must be set.")
            return
        }

        // Resolve redirects if maxRedirects is specified
        if (maxRedirects > 0) {
            Log.d(NAME, "Resolving redirects for URL: $url (maxRedirects: $maxRedirects)")
            url = resolveRedirects(url, maxRedirects, headers)
            Log.d(NAME, "Final resolved URL: $url")
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
        // These headers encourage longer connections and help prevent premature timeouts
        request.addRequestHeader("Connection", "keep-alive")
        request.addRequestHeader("Keep-Alive", KEEP_ALIVE_HEADER_VALUE)

        // Add a proper User-Agent to improve server compatibility
        if (!hasUserAgentHeader(headers)) {
            request.addRequestHeader("User-Agent", USER_AGENT)
        }

        headers?.let {
            val iterator = it.keySetIterator()
            while (iterator.hasNextKey()) {
                val headerKey = iterator.nextKey()
                request.addRequestHeader(headerKey, it.getString(headerKey))
            }
        }

        val uuid = (System.currentTimeMillis() and 0xfffffff).toInt()
        val extension = MimeTypeMap.getFileExtensionFromUrl(destination)
        val filename = "$uuid.$extension"
        request.setDestinationInExternalFilesDir(reactContext, null, filename)

        val downloadId = downloader.download(request)
        val config = RNBGDTaskConfig(id, url, destination, metadata ?: "{}", notificationTitle)

        synchronized(sharedLock) {
            configIdToDownloadId[id] = downloadId
            configIdToPercent[id] = 0.0
            downloadIdToConfig[downloadId] = config
            saveDownloadIdToConfigMap()
            resumeTasks(downloadId, config)
        }
    }

    // Pause functionality is not supported by Android DownloadManager.
    // This method will throw an UnsupportedOperationException to clearly indicate
    // that pause is not available on Android platform.
    fun pauseTask(configId: String) {
        synchronized(sharedLock) {
            val downloadId = configIdToDownloadId[configId]
            if (downloadId != null) {
                try {
                    downloader.pause(downloadId)
                } catch (e: UnsupportedOperationException) {
                    Log.w("RNBackgroundDownloader", "pauseTask: ${e.message}")
                    // Note: We don't rethrow the exception to avoid crashing the JS thread.
                    // The limitation is already documented and expected.
                }
            }
        }
    }

    // Resume functionality is not supported by Android DownloadManager.
    // This method will throw an UnsupportedOperationException to clearly indicate
    // that resume is not available on Android platform.
    fun resumeTask(configId: String) {
        synchronized(sharedLock) {
            val downloadId = configIdToDownloadId[configId]
            if (downloadId != null) {
                try {
                    downloader.resume(downloadId)
                } catch (e: UnsupportedOperationException) {
                    Log.w("RNBackgroundDownloader", "resumeTask: ${e.message}")
                    // Note: We don't rethrow the exception to avoid crashing the JS thread.
                    // The limitation is already documented and expected.
                }
            }
        }
    }

    fun stopTask(configId: String) {
        synchronized(sharedLock) {
            val downloadId = configIdToDownloadId[configId]
            if (downloadId != null) {
                stopTaskProgress(configId)
                removeTaskFromMap(downloadId)
                downloader.cancel(downloadId)
            }
        }
    }

    fun completeHandler(configId: String) {
        // Firebase Performance compatibility: Add defensive programming to prevent crashes
        // when Firebase Performance SDK is installed and uses bytecode instrumentation

        Log.d(NAME, "completeHandler called with configId: $configId")

        // Defensive programming: Validate parameters
        if (configId.isEmpty()) {
            Log.w(NAME, "completeHandler: Invalid configId provided")
            return
        }

        try {
            // Currently this method doesn't have any implementation on Android
            // as completion handlers are handled differently than iOS.
            // This defensive structure ensures Firebase Performance compatibility.
            Log.d(NAME, "completeHandler executed successfully for configId: $configId")

        } catch (e: Exception) {
            // Catch any potential exceptions that might be thrown due to Firebase Performance
            // bytecode instrumentation interfering with method dispatch
            Log.e(NAME, "completeHandler: Exception occurred: ${Log.getStackTraceString(e)}")
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
                                                Log.e(NAME, "Error moving completed download file: ${e.message}")
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
                                    configIdToPercent[config.id] = percent
                                }
                            } else if (downloadId != null) {
                                downloader.cancel(downloadId)
                            }
                        } while (cursor.moveToNext())
                    }
                }
            } catch (e: Exception) {
                Log.e(NAME, "getExistingDownloadTasks: ${Log.getStackTraceString(e)}")
            }
        }

        promise.resolve(foundTasks)
    }

    fun addListener(eventName: String) {
    }

    fun removeListeners(count: Int) {
    }

    private fun onBeginDownload(configId: String, headers: WritableMap, expectedBytes: Long) {
        val params = Arguments.createMap()
        params.putString("id", configId)
        params.putMap("headers", headers)
        params.putDouble("expectedBytes", expectedBytes.toDouble())
        ee.emit("downloadBegin", params)
    }

    private fun onProgressDownload(configId: String, bytesDownloaded: Long, bytesTotal: Long) {
        val existPercent = configIdToPercent[configId]
        val existLastBytes = configIdToLastBytes[configId]
        val prevPercent = existPercent ?: 0.0
        val prevBytes = existLastBytes ?: 0L
        val percent = if (bytesTotal > 0.0) bytesDownloaded.toDouble() / bytesTotal else 0.0

        // Check if we should report progress based on percentage OR bytes threshold
        val percentThresholdMet = percent - prevPercent > PROGRESS_REPORT_THRESHOLD
        // Only check bytes threshold if progressMinBytes > 0
        val bytesThresholdMet = progressMinBytes > 0 && (bytesDownloaded - prevBytes >= progressMinBytes)

        // Report progress if either threshold is met, or if total bytes unknown (for realtime streams)
        if (percentThresholdMet || bytesThresholdMet || bytesTotal <= 0) {
            val params = Arguments.createMap()
            params.putString("id", configId)
            params.putDouble("bytesDownloaded", bytesDownloaded.toDouble())
            params.putDouble("bytesTotal", bytesTotal.toDouble())
            progressReports[configId] = params
            configIdToPercent[configId] = percent
            configIdToLastBytes[configId] = bytesDownloaded
        }

        val now = Date()
        val isReportTimeDifference = now.time - lastProgressReportedAt.time > progressInterval
        val isReportNotEmpty = progressReports.isNotEmpty()
        if (isReportTimeDifference && isReportNotEmpty) {
            // Extra steps to avoid map always consumed errors.
            val reportsList = progressReports.values.toList()
            val reportsArray = Arguments.createArray()
            for (report in reportsList) {
                reportsArray.pushMap(report.copy())
            }
            ee.emit("downloadProgress", reportsArray)
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

        val params = Arguments.createMap()
        params.putString("id", config.id)
        params.putString("location", config.destination)
        params.putDouble("bytesDownloaded", downloadStatus.getDouble("bytesDownloaded"))
        params.putDouble("bytesTotal", downloadStatus.getDouble("bytesTotal"))
        ee.emit("downloadComplete", params)
    }

    private fun onFailedDownload(config: RNBGDTaskConfig, downloadStatus: WritableMap) {
        Log.e(
            NAME, "onFailedDownload: " +
                    "${downloadStatus.getInt("status")}:" +
                    "${downloadStatus.getInt("reason")}:" +
                    downloadStatus.getString("reasonText")
        )

        val reason = downloadStatus.getInt("reason")
        var reasonText = downloadStatus.getString("reasonText")

        // Enhanced handling for ERROR_CANNOT_RESUME (1008)
        if (reason == DownloadManager.ERROR_CANNOT_RESUME) {
            Log.w(
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

        val params = Arguments.createMap()
        params.putString("id", config.id)
        params.putInt("errorCode", reason)
        params.putString("error", reasonText)
        ee.emit("downloadFailed", params)
    }

    private fun saveDownloadIdToConfigMap() {
        synchronized(sharedLock) {
            try {
                val gson = Gson()
                // Create a defensive copy to prevent ConcurrentModificationException
                // when Gson iterates over the map while another thread modifies it
                val mapCopy = HashMap(downloadIdToConfig)
                val str = gson.toJson(mapCopy)

                if (isMMKVAvailable && mmkv != null) {
                    mmkv!!.encode("${NAME}_downloadIdToConfig", str)
                    Log.d(NAME, "Saved download config to MMKV")
                } else {
                    sharedPreferences.edit()
                        .putString("${NAME}_downloadIdToConfig", str)
                        .apply()
                    Log.d(NAME, "Saved download config to SharedPreferences fallback")
                }
            } catch (e: Exception) {
                Log.e(NAME, "Failed to save download config: ${e.message}")
            }
        }
    }

    private fun loadDownloadIdToConfigMap() {
        synchronized(sharedLock) {
            downloadIdToConfig = mutableMapOf()

            try {
                val str = if (isMMKVAvailable && mmkv != null) {
                    mmkv!!.decodeString("${NAME}_downloadIdToConfig")?.also {
                        Log.d(NAME, "Loaded download config from MMKV")
                    }
                } else {
                    sharedPreferences.getString("${NAME}_downloadIdToConfig", null)?.also {
                        Log.d(NAME, "Loaded download config from SharedPreferences fallback")
                    }
                }

                if (str != null) {
                    val gson = Gson()
                    val mapType = object : TypeToken<Map<Long, RNBGDTaskConfig>>() {}.type
                    downloadIdToConfig = gson.fromJson(str, mapType)
                } else {
                    Log.d(NAME, "No existing download config found, starting with empty map")
                }
            } catch (e: Exception) {
                Log.e(NAME, "Failed to load download config: ${e.message}")
                downloadIdToConfig = mutableMapOf()
            }
        }
    }

    private fun saveConfigMap() {
        synchronized(sharedLock) {
            try {
                if (isMMKVAvailable && mmkv != null) {
                    mmkv!!.encode("${NAME}_progressInterval", progressInterval)
                    mmkv!!.encode("${NAME}_progressMinBytes", progressMinBytes)
                    Log.d(NAME, "Saved config to MMKV")
                } else {
                    sharedPreferences.edit()
                        .putInt("${NAME}_progressInterval", progressInterval)
                        .putLong("${NAME}_progressMinBytes", progressMinBytes)
                        .apply()
                    Log.d(NAME, "Saved config to SharedPreferences fallback")
                }
            } catch (e: Exception) {
                Log.e(NAME, "Failed to save config: ${e.message}")
            }
        }
    }

    private fun loadConfigMap() {
        synchronized(sharedLock) {
            try {
                if (isMMKVAvailable && mmkv != null) {
                    val progressIntervalScope = mmkv!!.decodeInt("${NAME}_progressInterval")
                    if (progressIntervalScope > 0) {
                        progressInterval = progressIntervalScope
                    }
                    val progressMinBytesScope = mmkv!!.decodeLong("${NAME}_progressMinBytes")
                    if (progressMinBytesScope > 0) {
                        progressMinBytes = progressMinBytesScope
                    }
                    Log.d(NAME, "Loaded config from MMKV")
                } else {
                    val progressIntervalScope = sharedPreferences.getInt("${NAME}_progressInterval", 0)
                    if (progressIntervalScope > 0) {
                        progressInterval = progressIntervalScope
                    }
                    val progressMinBytesScope = sharedPreferences.getLong("${NAME}_progressMinBytes", 0)
                    if (progressMinBytesScope > 0) {
                        progressMinBytes = progressMinBytesScope
                    }
                    Log.d(NAME, "Loaded config from SharedPreferences fallback")
                }
            } catch (e: Exception) {
                Log.e(NAME, "Failed to load config: ${e.message}")
            }
        }
    }

    private fun stopTaskProgress(configId: String) {
        val onProgressFuture = configIdToProgressFuture[configId]
        if (onProgressFuture != null) {
            onProgressFuture.cancel(true)
            configIdToPercent.remove(configId)
            configIdToLastBytes.remove(configId)
            configIdToProgressFuture.remove(configId)
        }
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
            if (headerKey.lowercase() == "user-agent") {
                return true
            }
        }

        return false
    }
}

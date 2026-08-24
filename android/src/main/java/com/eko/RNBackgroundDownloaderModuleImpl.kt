package com.eko

import android.content.Context
import android.util.Log
import com.eko.utils.HeaderUtils
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import java.util.concurrent.ConcurrentHashMap

class RNBackgroundDownloaderModuleImpl private constructor(initialContext: Context) {
  companion object {
    const val NAME = "RNBackgroundDownloader"
    private val sharedLock = Any()

    @Volatile
    private var sharedInstance: RNBackgroundDownloaderModuleImpl? = null

    @Volatile
    private var isLogsEnabled = false

    fun getInstance(context: Context): RNBackgroundDownloaderModuleImpl =
      sharedInstance ?: synchronized(sharedLock) {
        sharedInstance ?: RNBackgroundDownloaderModuleImpl(context).also { sharedInstance = it }
      }

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

  private val applicationContext = initialContext.applicationContext
  private val buildConfig = NativeBuildConfig.load(applicationContext)
  private val runtimeBinding = RuntimeBinding<(String, Any?) -> Unit>()
  private val eventLock = Any()
  private val pendingEvents = RuntimeEventBuffer<Any?>()
  private val downloader = ResumableDownloader()
  private val uploader = Uploader()
  private val downloadConfigs = ConcurrentHashMap<String, RNBGDTaskConfig>()
  private val uploadConfigs = ConcurrentHashMap<String, RNBGDUploadTaskConfig>()

  private val readyFamilies = mutableSetOf<String>()

  private val downloadEventEmitter = DownloadEventEmitter(::dispatchTaskEvent)
  private val uploadEventEmitter = UploadEventEmitter(::dispatchTaskEvent)
  private val downloadProgressReporter = ProgressReporter(::dispatchDownloadProgress)
  private val uploadProgressReporter = ProgressReporter(::dispatchUploadProgress, "bytesUploaded")

  private fun removeDownloadConfig(id: String, config: RNBGDTaskConfig): Boolean {
    var removed = false
    downloadConfigs.compute(id) { _, current ->
      if (current === config) {
        removed = true
        null
      } else {
        current
      }
    }
    return removed
  }

  private fun removeUploadConfig(id: String, config: RNBGDUploadTaskConfig): Boolean {
    var removed = false
    uploadConfigs.compute(id) { _, current ->
      if (current === config) {
        removed = true
        null
      } else {
        current
      }
    }
    return removed
  }

  private fun downloadListener(config: RNBGDTaskConfig) = object : ResumableDownloader.DownloadListener {
    override fun onBegin(id: String, expectedBytes: Long, headers: Map<String, String>) {
      if (downloadConfigs[id] !== config) return
      val headerMap = Arguments.createMap()
      headers.forEach(headerMap::putString)
      downloadEventEmitter.emitBegin(id, headerMap, expectedBytes)
    }

    override fun onProgress(id: String, bytesDownloaded: Long, bytesTotal: Long) {
      if (downloadConfigs[id] === config)
        downloadProgressReporter.reportProgress(id, bytesDownloaded, bytesTotal)
    }

    override fun onComplete(id: String, location: String, bytesDownloaded: Long, bytesTotal: Long) {
      if (!removeDownloadConfig(id, config)) return
      downloadProgressReporter.clearDownloadState(id)
      downloadEventEmitter.emitComplete(id, location, bytesDownloaded, bytesTotal, config.metadata)
    }

    override fun onError(id: String, error: String, errorCode: Int) {
      if (!removeDownloadConfig(id, config)) return
      downloadProgressReporter.clearDownloadState(id)
      downloadEventEmitter.emitFailed(id, error, errorCode, config.metadata)
    }
  }

  private fun uploadListener(config: RNBGDUploadTaskConfig) = object : Uploader.UploadListener {
    override fun onBegin(id: String, expectedBytes: Long) {
      if (uploadConfigs[id] === config)
        uploadEventEmitter.emitBegin(id, expectedBytes)
    }

    override fun onProgress(id: String, bytesUploaded: Long, bytesTotal: Long) {
      if (uploadConfigs[id] !== config) return
      uploadProgressReporter.reportProgress(id, bytesUploaded, bytesTotal)
      config.bytesUploaded = bytesUploaded
      config.bytesTotal = bytesTotal
    }

    override fun onComplete(id: String, responseCode: Int, responseBody: String, bytesUploaded: Long, bytesTotal: Long) {
      if (!removeUploadConfig(id, config)) return
      uploadProgressReporter.clearDownloadState(id)
      uploadEventEmitter.emitComplete(id, responseCode, responseBody, bytesUploaded, bytesTotal, config.metadata)
    }

    override fun onError(id: String, error: String, errorCode: Int) {
      if (!removeUploadConfig(id, config)) return
      uploadProgressReporter.clearDownloadState(id)
      uploadEventEmitter.emitFailed(id, error, errorCode, config.metadata)
    }
  }

  init {
    isLogsEnabled = buildConfig.enableLogging
    ResumableDownloader.setMaxConcurrentTransfers(buildConfig.maxParallelDownloads)
    downloadProgressReporter.configure(buildConfig.progressInterval, buildConfig.progressMinBytes)
    uploadProgressReporter.configure(buildConfig.progressInterval, buildConfig.progressMinBytes)
  }

  fun getConstants(): Map<String, Any> = mapOf(
    "documents" to applicationContext.filesDir.absolutePath,
    "TaskRunning" to DownloadConstants.TASK_RUNNING,
    "TaskSuspended" to DownloadConstants.TASK_SUSPENDED,
    "TaskCanceling" to DownloadConstants.TASK_CANCELING,
    "TaskCompleted" to DownloadConstants.TASK_COMPLETED,
    "isLoggingEnabled" to buildConfig.enableLogging
  )

  fun initialize(eventSink: (String, Any?) -> Unit): Long = synchronized(eventLock) {
    val token = runtimeBinding.attach(eventSink)
    readyFamilies.clear()
    token
  }

  fun invalidate(bindingToken: Long) {
    synchronized(eventLock) {
      if (!runtimeBinding.detach(bindingToken)) return
      readyFamilies.clear()
    }
  }

  fun isBindingTokenCurrent(bindingToken: Long): Boolean = runtimeBinding.isCurrent(bindingToken)

  fun setRuntimeReady(bindingToken: Long, family: String): WritableArray = synchronized(eventLock) {
    val result = Arguments.createArray()
    if (!runtimeBinding.isCurrent(bindingToken)) return@synchronized result
    require(family == "download" || family == "upload") { "Unknown task family: $family" }

    readyFamilies.add(family)
    pendingEvents.snapshot().filter { it.key.startsWith(family) }.forEach { event ->
      val record = Arguments.createMap()
      record.putString("name", event.name)
      record.putString("key", event.key)
      record.putMap("payload", (event.payload as ReadableMap).copy())
      result.pushMap(record)
    }
    result
  }

  fun prepareRuntimeReconciliation(bindingToken: Long, family: String) = synchronized(eventLock) {
    if (!runtimeBinding.isCurrent(bindingToken)) return@synchronized
    require(family == "download" || family == "upload") { "Unknown task family: $family" }
    readyFamilies.remove(family)
  }

  fun acknowledgeRuntimeEvents(bindingToken: Long, keys: ReadableArray) {
    synchronized(eventLock) {
      if (!runtimeBinding.isCurrent(bindingToken)) return
      for (index in 0 until keys.size())
        keys.getString(index)?.let(pendingEvents::remove)
    }
  }

  fun download(options: ReadableMap) {
    val id = requireString(options, "id")
    val config = RNBGDTaskConfig(
      id = id,
      url = requireString(options, "url"),
      destination = requireString(options, "destination"),
      metadata = options.optionalString("metadata") ?: "{}",
      headers = HeaderUtils.toMap(options.optionalMap("headers"))
    )
    downloadConfigs[id] = config
    downloadProgressReporter.initializeDownload(id)
    downloader.startDownload(config.id, config.url, config.destination, config.headers, downloadListener(config))
  }

  fun pauseTask(id: String) {
    if (!downloader.pause(id)) throw IllegalArgumentException("Download task not found: $id")
    downloadConfigs[id]?.state = DownloadConstants.TASK_SUSPENDED
  }

  fun resumeTask(id: String) {
    val config = downloadConfigs[id] ?: throw IllegalArgumentException("Download task not found: $id")
    if (!downloader.resume(id, downloadListener(config))) throw IllegalArgumentException("Download task is not paused: $id")
    config.state = DownloadConstants.TASK_RUNNING
  }

  fun stopTask(id: String) {
    downloader.cancel(id)
    downloadProgressReporter.clearDownloadState(id)
    downloadConfigs.remove(id)
    synchronized(eventLock) {
      pendingEvents.remove("download-progress:$id")
      pendingEvents.remove("download:$id")
    }
  }

  fun getExistingDownloadTasks(): WritableArray {
    val result = Arguments.createArray()
    downloadConfigs.values.forEach { config ->
      val state = downloader.getState(config.id) ?: return@forEach
      val task = Arguments.createMap()
      task.putString("id", config.id)
      task.putString("metadata", config.metadata)
      task.putInt("state", if (state.isPaused.get()) DownloadConstants.TASK_SUSPENDED else DownloadConstants.TASK_RUNNING)
      task.putDouble("bytesDownloaded", state.bytesDownloaded.get().toDouble())
      task.putDouble("bytesTotal", state.bytesTotal.toDouble())
      task.putInt("errorCode", config.errorCode)
      task.putString("destination", config.destination)
      result.pushMap(task)
    }
    return result
  }

  fun upload(options: ReadableMap) {
    val id = requireString(options, "id")
    val config = RNBGDUploadTaskConfig(
      id = id,
      url = requireString(options, "url"),
      source = requireString(options, "source"),
      metadata = options.optionalString("metadata") ?: "{}",
      method = options.optionalString("method") ?: "POST",
      headers = HeaderUtils.toMap(options.optionalMap("headers")),
      fieldName = options.optionalString("fieldName"),
      mimeType = options.optionalString("mimeType"),
      parameters = options.optionalMap("parameters")?.let(HeaderUtils::toMap)
    )
    uploadConfigs[id] = config
    uploadProgressReporter.initializeDownload(id)
    uploader.startUpload(config, uploadListener(config))
  }

  fun pauseUploadTask(id: String) {
    if (!uploader.pause(id)) throw IllegalArgumentException("Upload task not found: $id")
    uploadConfigs[id]?.state = DownloadConstants.TASK_SUSPENDED
  }

  fun resumeUploadTask(id: String) {
    val config = uploadConfigs[id] ?: throw IllegalArgumentException("Upload task not found: $id")
    if (!uploader.resume(id, uploadListener(config))) throw IllegalArgumentException("Upload task is not paused: $id")
    config.state = DownloadConstants.TASK_RUNNING
  }

  fun stopUploadTask(id: String) {
    uploader.cancel(id)
    uploadProgressReporter.clearDownloadState(id)
    uploadConfigs.remove(id)
    synchronized(eventLock) {
      pendingEvents.remove("upload-progress:$id")
      pendingEvents.remove("upload:$id")
    }
  }

  fun getExistingUploadTasks(): WritableArray {
    val result = Arguments.createArray()
    uploadConfigs.values.forEach { config ->
      val state = uploader.getState(config.id) ?: return@forEach
      val task = Arguments.createMap()
      task.putString("id", config.id)
      task.putString("metadata", config.metadata)
      task.putInt("state", if (state.isPaused.get()) DownloadConstants.TASK_SUSPENDED else DownloadConstants.TASK_RUNNING)
      task.putDouble("bytesUploaded", state.bytesUploaded.get().toDouble())
      task.putDouble("bytesTotal", state.bytesTotal.toDouble())
      task.putInt("errorCode", config.errorCode)
      result.pushMap(task)
    }
    return result
  }

  private fun dispatchTaskEvent(name: String, payload: WritableMap) {
    synchronized(eventLock) {
      val family = if (name.startsWith("upload")) "upload" else "download"
      val id = payload.getString("id") ?: return
      val terminal = name.endsWith("Complete") || name.endsWith("Failed")
      if (terminal) {
        pendingEvents.remove("$family-progress:$id")
        pendingEvents.put(name, "$family:$id", payload.copy())
      }

      val binding = runtimeBinding.current()
      if (binding != null && readyFamilies.contains(family)) {
        try {
          binding.value.invoke(name, payload)
          return
        } catch (error: Exception) {
          logW(NAME, "Failed to emit $name: ${error.message}")
        }
      }

      if (!terminal)
        pendingEvents.put(name, "$family:$id", payload.copy())
    }
  }

  private fun dispatchDownloadProgress(reports: WritableArray) = dispatchProgress("downloadProgress", "download", reports)

  private fun dispatchUploadProgress(reports: WritableArray) = dispatchProgress("uploadProgress", "upload", reports)

  private fun dispatchProgress(name: String, family: String, reports: WritableArray) {
    synchronized(eventLock) {
      val binding = runtimeBinding.current()
      if (binding != null && readyFamilies.contains(family)) {
        try {
          binding.value.invoke(name, reports)
          return
        } catch (error: Exception) {
          logW(NAME, "Failed to emit $name: ${error.message}")
        }
      }

      for (index in 0 until reports.size()) {
        val report = reports.getMap(index) ?: continue
        val id = report.getString("id") ?: continue
        pendingEvents.put(name, "$family-progress:$id", report.copy())
      }
    }
  }

  private fun requireString(options: ReadableMap, key: String): String =
    options.optionalString(key)?.takeIf(String::isNotBlank)
      ?: throw IllegalArgumentException("$key is required")

  private fun ReadableMap.optionalString(key: String): String? =
    if (hasKey(key) && !isNull(key)) getString(key) else null

  private fun ReadableMap.optionalMap(key: String): ReadableMap? =
    if (hasKey(key) && !isNull(key)) getMap(key) else null
}

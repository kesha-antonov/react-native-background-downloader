package com.eko.utils

import android.content.Context
import android.content.SharedPreferences
import com.eko.Downloader
import com.eko.RNBackgroundDownloaderModuleImpl
import com.eko.RNBGDTaskConfig
import com.eko.RNBGDUploadTaskConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV

/**
 * Manages persistent storage for download and upload configurations.
 * Uses MMKV when available, falls back to SharedPreferences.
 */
class StorageManager(context: Context, private val name: String) {

    companion object {
        private const val TAG = "StorageManager"
        private const val KEY_DOWNLOAD_ID_TO_CONFIG = "_downloadIdToConfig"
        private const val KEY_PAUSED_DOWNLOADS = "_pausedDownloads"
        private const val KEY_ACTIVE_DOWNLOADS = "_activeDownloads"
        private const val KEY_UPLOAD_CONFIGS = "_uploadConfigs"
        private const val KEY_PROGRESS_INTERVAL = "_progressInterval"
        private const val KEY_PROGRESS_MIN_BYTES = "_progressMinBytes"
    }

    private var mmkv: MMKV? = null
    private val sharedPreferences: SharedPreferences
    private val isMMKVAvailable: Boolean

    init {
        // Initialize SharedPreferences as fallback
        sharedPreferences = context.getSharedPreferences("${name}_prefs", Context.MODE_PRIVATE)

        // Try to initialize MMKV with comprehensive error handling
        var mmkvAvailable = false
        try {
            MMKV.initialize(context)
            mmkv = MMKV.mmkvWithID(name)
            mmkvAvailable = true
            RNBackgroundDownloaderModuleImpl.logD(TAG, "MMKV initialized successfully")
        } catch (e: UnsatisfiedLinkError) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to initialize MMKV (libmmkv.so not found): ${e.message}")
            RNBackgroundDownloaderModuleImpl.logW(TAG, "This may be due to unsupported architecture (x86/ARMv7). Using SharedPreferences fallback.")
            mmkv = null
        } catch (e: NoClassDefFoundError) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "MMKV classes not found: ${e.message}")
            RNBackgroundDownloaderModuleImpl.logW(TAG, "MMKV library not available on this architecture. Using SharedPreferences fallback.")
            mmkv = null
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to initialize MMKV: ${e.message}")
            RNBackgroundDownloaderModuleImpl.logW(TAG, "Using SharedPreferences fallback for persistence.")
            mmkv = null
        }
        isMMKVAvailable = mmkvAvailable
    }

    /**
     * Save download ID to config mapping.
     */
    fun saveDownloadIdToConfigMap(downloadIdToConfig: Map<Long, RNBGDTaskConfig>) {
        try {
            val gson = Gson()
            // Create a defensive copy to prevent ConcurrentModificationException
            val mapCopy = HashMap(downloadIdToConfig)
            val str = gson.toJson(mapCopy)

            if (isMMKVAvailable && mmkv != null) {
                mmkv!!.encode("$name$KEY_DOWNLOAD_ID_TO_CONFIG", str)
            } else {
                sharedPreferences.edit()
                    .putString("$name$KEY_DOWNLOAD_ID_TO_CONFIG", str)
                    .commit() // Use commit() for synchronous save
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to save download config: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Load download ID to config mapping.
     */
    fun loadDownloadIdToConfigMap(): MutableMap<Long, RNBGDTaskConfig> {
        try {
            val str = if (isMMKVAvailable && mmkv != null) {
                mmkv!!.decodeString("$name$KEY_DOWNLOAD_ID_TO_CONFIG")
            } else {
                sharedPreferences.getString("$name$KEY_DOWNLOAD_ID_TO_CONFIG", null)
            }

            if (str != null && str.isNotEmpty()) {
                val gson = Gson()
                val mapType = object : TypeToken<Map<Long, RNBGDTaskConfig>>() {}.type
                return gson.fromJson(str, mapType)
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to load download config: ${e.message}")
            e.printStackTrace()
        }
        return mutableMapOf()
    }

    /**
     * Save progress configuration.
     */
    fun saveProgressConfig(progressInterval: Long, progressMinBytes: Long) {
        try {
            if (isMMKVAvailable && mmkv != null) {
                mmkv!!.encode("$name$KEY_PROGRESS_INTERVAL", progressInterval.toInt())
                mmkv!!.encode("$name$KEY_PROGRESS_MIN_BYTES", progressMinBytes)
            } else {
                sharedPreferences.edit()
                    .putInt("$name$KEY_PROGRESS_INTERVAL", progressInterval.toInt())
                    .putLong("$name$KEY_PROGRESS_MIN_BYTES", progressMinBytes)
                    .apply()
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to save config: ${e.message}")
        }
    }

    /**
     * Load progress configuration.
     * @return Pair of (progressInterval, progressMinBytes)
     */
    fun loadProgressConfig(): Pair<Long, Long> {
        try {
            val interval: Long
            val minBytes: Long

            if (isMMKVAvailable && mmkv != null) {
                val progressIntervalScope = mmkv!!.decodeInt("$name$KEY_PROGRESS_INTERVAL")
                interval = if (progressIntervalScope > 0) progressIntervalScope.toLong() else 0L

                val progressMinBytesScope = mmkv!!.decodeLong("$name$KEY_PROGRESS_MIN_BYTES")
                minBytes = if (progressMinBytesScope > 0) progressMinBytesScope else 0L
            } else {
                val progressIntervalScope = sharedPreferences.getInt("$name$KEY_PROGRESS_INTERVAL", 0)
                interval = if (progressIntervalScope > 0) progressIntervalScope.toLong() else 0L

                val progressMinBytesScope = sharedPreferences.getLong("$name$KEY_PROGRESS_MIN_BYTES", 0)
                minBytes = if (progressMinBytesScope > 0) progressMinBytesScope else 0L
            }

            return Pair(interval, minBytes)
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to load config: ${e.message}")
            return Pair(0L, 0L)
        }
    }

    /**
     * Save a boolean value synchronously.
     */
    fun saveBooleanSync(key: String, value: Boolean) {
        try {
            if (isMMKVAvailable && mmkv != null) {
                mmkv!!.encode("$name$key", value)
            } else {
                sharedPreferences.edit()
                    .putBoolean("$name$key", value)
                    .apply()
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to save boolean $key: ${e.message}")
        }
    }

    /**
     * Get a boolean value synchronously.
     */
    fun getBooleanSync(key: String, defaultValue: Boolean): Boolean {
        return try {
            if (isMMKVAvailable && mmkv != null) {
                mmkv!!.decodeBool("$name$key", defaultValue)
            } else {
                sharedPreferences.getBoolean("$name$key", defaultValue)
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to get boolean $key: ${e.message}")
            defaultValue
        }
    }

    /**
     * Serializable data class for storing paused download information.
     * This is needed because Gson requires explicit serialization for complex types.
     */
    data class PausedDownloadInfoData(
        val configId: String,
        val url: String,
        val destination: String,
        val headers: Map<String, String>,
        val bytesDownloaded: Long,
        val bytesTotal: Long,
        val metadata: String = "{}",
        // Nullable so records persisted by older versions (key absent in JSON)
        // load as null and fall back to true instead of Gson's default false.
        val isAllowedOverMetered: Boolean? = null
    )

    /**
     * Save paused downloads map.
     */
    fun savePausedDownloads(pausedDownloads: Map<String, Downloader.PausedDownloadInfo>) {
        try {
            val gson = Gson()
            // Convert to serializable data class
            val mapCopy = pausedDownloads.mapValues { (_, info) ->
                PausedDownloadInfoData(
                    configId = info.configId,
                    url = info.url,
                    destination = info.destination,
                    headers = info.headers,
                    bytesDownloaded = info.bytesDownloaded,
                    bytesTotal = info.bytesTotal,
                    metadata = info.metadata,
                    isAllowedOverMetered = info.isAllowedOverMetered
                )
            }
            val str = gson.toJson(mapCopy)

            if (isMMKVAvailable && mmkv != null) {
                mmkv!!.encode("$name$KEY_PAUSED_DOWNLOADS", str)
            } else {
                sharedPreferences.edit()
                    .putString("$name$KEY_PAUSED_DOWNLOADS", str)
                    .commit() // Use commit() instead of apply() for synchronous save
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to save paused downloads: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Load paused downloads map.
     */
    fun loadPausedDownloads(): MutableMap<String, Downloader.PausedDownloadInfo> {
        try {
            val str = if (isMMKVAvailable && mmkv != null) {
                mmkv!!.decodeString("$name$KEY_PAUSED_DOWNLOADS")
            } else {
                sharedPreferences.getString("$name$KEY_PAUSED_DOWNLOADS", null)
            }

            if (str != null && str.isNotEmpty()) {
                val gson = Gson()
                val mapType = object : TypeToken<Map<String, PausedDownloadInfoData>>() {}.type
                val dataMap: Map<String, PausedDownloadInfoData> = gson.fromJson(str, mapType)
                // Convert back to PausedDownloadInfo
                return dataMap.mapValues { (_, data) ->
                    Downloader.PausedDownloadInfo(
                        configId = data.configId,
                        url = data.url,
                        destination = data.destination,
                        headers = data.headers,
                        bytesDownloaded = data.bytesDownloaded,
                        bytesTotal = data.bytesTotal,
                        metadata = data.metadata,
                        isAllowedOverMetered = data.isAllowedOverMetered ?: true
                    )
                }.toMutableMap()
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to load paused downloads: ${e.message}")
            e.printStackTrace()
        }
        return mutableMapOf()
    }

    /**
     * Remove a paused download by config ID.
     */
    fun removePausedDownload(configId: String) {
        try {
            val paused = loadPausedDownloads()
            if (paused.remove(configId) != null) {
                savePausedDownloads(paused)
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to remove paused download: ${e.message}")
        }
    }

    /**
     * Save the set of in-progress resumable downloads ("recovery snapshots").
     * These let getExistingDownloadTasks recover an active download after the app
     * is force-stopped (which kills the foreground service and loses in-memory
     * state). Stored separately from paused downloads so it never interferes with
     * the live pause/resume bookkeeping.
     */
    fun saveActiveDownloads(activeDownloads: Map<String, Downloader.PausedDownloadInfo>) {
        try {
            val gson = Gson()
            val mapCopy = activeDownloads.mapValues { (_, info) ->
                PausedDownloadInfoData(
                    configId = info.configId,
                    url = info.url,
                    destination = info.destination,
                    headers = info.headers,
                    bytesDownloaded = info.bytesDownloaded,
                    bytesTotal = info.bytesTotal,
                    metadata = info.metadata,
                    isAllowedOverMetered = info.isAllowedOverMetered
                )
            }
            val str = gson.toJson(mapCopy)

            if (isMMKVAvailable && mmkv != null) {
                mmkv!!.encode("$name$KEY_ACTIVE_DOWNLOADS", str)
            } else {
                sharedPreferences.edit()
                    .putString("$name$KEY_ACTIVE_DOWNLOADS", str)
                    .commit()
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to save active downloads: ${e.message}")
        }
    }

    /**
     * Load the in-progress resumable download recovery snapshots.
     */
    fun loadActiveDownloads(): MutableMap<String, Downloader.PausedDownloadInfo> {
        try {
            val str = if (isMMKVAvailable && mmkv != null) {
                mmkv!!.decodeString("$name$KEY_ACTIVE_DOWNLOADS")
            } else {
                sharedPreferences.getString("$name$KEY_ACTIVE_DOWNLOADS", null)
            }

            if (str != null && str.isNotEmpty()) {
                val gson = Gson()
                val mapType = object : TypeToken<Map<String, PausedDownloadInfoData>>() {}.type
                val dataMap: Map<String, PausedDownloadInfoData> = gson.fromJson(str, mapType)
                return dataMap.mapValues { (_, data) ->
                    Downloader.PausedDownloadInfo(
                        configId = data.configId,
                        url = data.url,
                        destination = data.destination,
                        headers = data.headers,
                        bytesDownloaded = data.bytesDownloaded,
                        bytesTotal = data.bytesTotal,
                        metadata = data.metadata,
                        isAllowedOverMetered = data.isAllowedOverMetered ?: true
                    )
                }.toMutableMap()
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to load active downloads: ${e.message}")
        }
        return mutableMapOf()
    }

    /**
     * Upsert a single in-progress resumable download recovery snapshot.
     */
    fun saveActiveDownload(info: Downloader.PausedDownloadInfo) {
        val map = loadActiveDownloads()
        map[info.configId] = info
        saveActiveDownloads(map)
    }

    /**
     * Remove a single recovery snapshot by config ID.
     */
    fun removeActiveDownload(configId: String) {
        val map = loadActiveDownloads()
        if (map.remove(configId) != null) {
            saveActiveDownloads(map)
        }
    }

    /**
     * Clear all recovery snapshots.
     */
    fun clearActiveDownloads() {
        saveActiveDownloads(emptyMap())
    }

    /**
     * Save upload configs map.
     */
    fun saveUploadConfigs(uploadConfigs: Map<String, RNBGDUploadTaskConfig>) {
        try {
            val gson = Gson()
            // Create a defensive copy to prevent ConcurrentModificationException
            val mapCopy = HashMap(uploadConfigs)
            val str = gson.toJson(mapCopy)

            if (isMMKVAvailable && mmkv != null) {
                mmkv!!.encode("$name$KEY_UPLOAD_CONFIGS", str)
            } else {
                sharedPreferences.edit()
                    .putString("$name$KEY_UPLOAD_CONFIGS", str)
                    .apply()
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to save upload configs: ${e.message}")
        }
    }

    /**
     * Load upload configs map.
     */
    fun loadUploadConfigs(): MutableMap<String, RNBGDUploadTaskConfig> {
        try {
            val str = if (isMMKVAvailable && mmkv != null) {
                mmkv!!.decodeString("$name$KEY_UPLOAD_CONFIGS")
            } else {
                sharedPreferences.getString("$name$KEY_UPLOAD_CONFIGS", null)
            }

            if (str != null) {
                val gson = Gson()
                val mapType = object : TypeToken<Map<String, RNBGDUploadTaskConfig>>() {}.type
                return gson.fromJson(str, mapType)
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to load upload configs: ${e.message}")
        }
        return mutableMapOf()
    }

    /**
     * Remove an upload config by ID.
     */
    fun removeUploadConfig(configId: String) {
        try {
            val configs = loadUploadConfigs()
            if (configs.remove(configId) != null) {
                saveUploadConfigs(configs)
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to remove upload config: ${e.message}")
        }
    }
}

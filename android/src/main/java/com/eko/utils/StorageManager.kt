package com.eko.utils

import android.content.Context
import android.content.SharedPreferences
import com.eko.Downloader
import com.eko.RNBackgroundDownloaderModuleImpl
import com.eko.RNBGDTaskConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV

/**
 * Manages persistent storage for download configurations.
 * Uses MMKV when available, falls back to SharedPreferences.
 */
class StorageManager(context: Context, private val name: String) {

    companion object {
        private const val TAG = "StorageManager"
        private const val KEY_DOWNLOAD_ID_TO_CONFIG = "_downloadIdToConfig"
        private const val KEY_PAUSED_DOWNLOADS = "_pausedDownloads"
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
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Saved download config to MMKV")
            } else {
                sharedPreferences.edit()
                    .putString("$name$KEY_DOWNLOAD_ID_TO_CONFIG", str)
                    .apply()
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Saved download config to SharedPreferences fallback")
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to save download config: ${e.message}")
        }
    }

    /**
     * Load download ID to config mapping.
     */
    fun loadDownloadIdToConfigMap(): MutableMap<Long, RNBGDTaskConfig> {
        try {
            val str = if (isMMKVAvailable && mmkv != null) {
                mmkv!!.decodeString("$name$KEY_DOWNLOAD_ID_TO_CONFIG")?.also {
                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Loaded download config from MMKV")
                }
            } else {
                sharedPreferences.getString("$name$KEY_DOWNLOAD_ID_TO_CONFIG", null)?.also {
                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Loaded download config from SharedPreferences fallback")
                }
            }

            if (str != null) {
                val gson = Gson()
                val mapType = object : TypeToken<Map<Long, RNBGDTaskConfig>>() {}.type
                return gson.fromJson(str, mapType)
            } else {
                RNBackgroundDownloaderModuleImpl.logD(TAG, "No existing download config found, starting with empty map")
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to load download config: ${e.message}")
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
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Saved config to MMKV")
            } else {
                sharedPreferences.edit()
                    .putInt("$name$KEY_PROGRESS_INTERVAL", progressInterval.toInt())
                    .putLong("$name$KEY_PROGRESS_MIN_BYTES", progressMinBytes)
                    .apply()
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Saved config to SharedPreferences fallback")
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

                RNBackgroundDownloaderModuleImpl.logD(TAG, "Loaded config from MMKV")
            } else {
                val progressIntervalScope = sharedPreferences.getInt("$name$KEY_PROGRESS_INTERVAL", 0)
                interval = if (progressIntervalScope > 0) progressIntervalScope.toLong() else 0L

                val progressMinBytesScope = sharedPreferences.getLong("$name$KEY_PROGRESS_MIN_BYTES", 0)
                minBytes = if (progressMinBytesScope > 0) progressMinBytesScope else 0L

                RNBackgroundDownloaderModuleImpl.logD(TAG, "Loaded config from SharedPreferences fallback")
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
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Saved boolean $key=$value to MMKV")
            } else {
                sharedPreferences.edit()
                    .putBoolean("$name$key", value)
                    .apply()
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Saved boolean $key=$value to SharedPreferences fallback")
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
        val metadata: String = "{}"
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
                    metadata = info.metadata
                )
            }
            val str = gson.toJson(mapCopy)

            if (isMMKVAvailable && mmkv != null) {
                mmkv!!.encode("$name$KEY_PAUSED_DOWNLOADS", str)
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Saved paused downloads to MMKV: ${pausedDownloads.size} items")
            } else {
                sharedPreferences.edit()
                    .putString("$name$KEY_PAUSED_DOWNLOADS", str)
                    .apply()
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Saved paused downloads to SharedPreferences fallback: ${pausedDownloads.size} items")
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to save paused downloads: ${e.message}")
        }
    }

    /**
     * Load paused downloads map.
     */
    fun loadPausedDownloads(): MutableMap<String, Downloader.PausedDownloadInfo> {
        try {
            val str = if (isMMKVAvailable && mmkv != null) {
                mmkv!!.decodeString("$name$KEY_PAUSED_DOWNLOADS")?.also {
                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Loaded paused downloads from MMKV")
                }
            } else {
                sharedPreferences.getString("$name$KEY_PAUSED_DOWNLOADS", null)?.also {
                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Loaded paused downloads from SharedPreferences fallback")
                }
            }

            if (str != null) {
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
                        metadata = data.metadata
                    )
                }.toMutableMap()
            } else {
                RNBackgroundDownloaderModuleImpl.logD(TAG, "No paused downloads found, starting with empty map")
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to load paused downloads: ${e.message}")
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
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Removed paused download: $configId")
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to remove paused download: ${e.message}")
        }
    }
}

package com.eko.utils

import android.content.Context
import android.content.SharedPreferences
import com.eko.Downloader
import com.eko.RNBGDTaskConfig
import com.eko.RNBGDUploadTaskConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages persistent storage for download and upload configurations.
 * Backed by SharedPreferences.
 */
class StorageManager(context: Context, private val name: String) {

    companion object {
        private const val KEY_DOWNLOAD_ID_TO_CONFIG = "_downloadIdToConfig"
        private const val KEY_PAUSED_DOWNLOADS = "_pausedDownloads"
        private const val KEY_ACTIVE_DOWNLOADS = "_activeDownloads"
        private const val KEY_UPLOAD_CONFIGS = "_uploadConfigs"
        private const val KEY_PROGRESS_INTERVAL = "_progressInterval"
        private const val KEY_PROGRESS_MIN_BYTES = "_progressMinBytes"
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("${name}_prefs", Context.MODE_PRIVATE)

    /**
     * Save download ID to config mapping.
     */
    fun saveDownloadIdToConfigMap(downloadIdToConfig: Map<Long, RNBGDTaskConfig>) {
        val gson = Gson()
        // Create a defensive copy to prevent ConcurrentModificationException
        val mapCopy = HashMap(downloadIdToConfig)
        val str = gson.toJson(mapCopy)

        sharedPreferences.edit()
            .putString("$name$KEY_DOWNLOAD_ID_TO_CONFIG", str)
            .commit() // Use commit() for synchronous save
    }

    /**
     * Load download ID to config mapping.
     */
    fun loadDownloadIdToConfigMap(): MutableMap<Long, RNBGDTaskConfig> {
        val str = sharedPreferences.getString("$name$KEY_DOWNLOAD_ID_TO_CONFIG", null)

        if (str != null && str.isNotEmpty()) {
            val gson = Gson()
            val mapType = object : TypeToken<Map<Long, RNBGDTaskConfig>>() {}.type
            return gson.fromJson(str, mapType)
        }
        return mutableMapOf()
    }

    /**
     * Save progress configuration.
     */
    fun saveProgressConfig(progressInterval: Long, progressMinBytes: Long) {
        sharedPreferences.edit()
            .putInt("$name$KEY_PROGRESS_INTERVAL", progressInterval.toInt())
            .putLong("$name$KEY_PROGRESS_MIN_BYTES", progressMinBytes)
            .apply()
    }

    /**
     * Load progress configuration.
     * @return Pair of (progressInterval, progressMinBytes)
     */
    fun loadProgressConfig(): Pair<Long, Long> {
        val progressIntervalScope = sharedPreferences.getInt("$name$KEY_PROGRESS_INTERVAL", 0)
        val interval = if (progressIntervalScope > 0) progressIntervalScope.toLong() else 0L

        val progressMinBytesScope = sharedPreferences.getLong("$name$KEY_PROGRESS_MIN_BYTES", 0)
        val minBytes = if (progressMinBytesScope > 0) progressMinBytesScope else 0L

        return Pair(interval, minBytes)
    }

    /**
     * Save a boolean value synchronously.
     */
    fun saveBooleanSync(key: String, value: Boolean) {
        sharedPreferences.edit()
            .putBoolean("$name$key", value)
            .apply()
    }

    /**
     * Get a boolean value synchronously.
     */
    fun getBooleanSync(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean("$name$key", defaultValue)
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
        val gson = Gson()
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

        sharedPreferences.edit()
            .putString("$name$KEY_PAUSED_DOWNLOADS", str)
            .commit() // Use commit() instead of apply() for synchronous save
    }

    /**
     * Load paused downloads map.
     */
    fun loadPausedDownloads(): MutableMap<String, Downloader.PausedDownloadInfo> {
        val str = sharedPreferences.getString("$name$KEY_PAUSED_DOWNLOADS", null)

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
                    metadata = data.metadata
                )
            }.toMutableMap()
        }
        return mutableMapOf()
    }

    /**
     * Remove a paused download by config ID.
     */
    fun removePausedDownload(configId: String) {
        val paused = loadPausedDownloads()
        if (paused.remove(configId) != null) {
            savePausedDownloads(paused)
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
        val gson = Gson()
        val mapCopy = activeDownloads.mapValues { (_, info) ->
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

        sharedPreferences.edit()
            .putString("$name$KEY_ACTIVE_DOWNLOADS", str)
            .commit()
    }

    /**
     * Load the in-progress resumable download recovery snapshots.
     */
    fun loadActiveDownloads(): MutableMap<String, Downloader.PausedDownloadInfo> {
        val str = sharedPreferences.getString("$name$KEY_ACTIVE_DOWNLOADS", null)

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
                    metadata = data.metadata
                )
            }.toMutableMap()
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
        val gson = Gson()
        // Create a defensive copy to prevent ConcurrentModificationException
        val mapCopy = HashMap(uploadConfigs)
        val str = gson.toJson(mapCopy)

        sharedPreferences.edit()
            .putString("$name$KEY_UPLOAD_CONFIGS", str)
            .apply()
    }

    /**
     * Load upload configs map.
     */
    fun loadUploadConfigs(): MutableMap<String, RNBGDUploadTaskConfig> {
        val str = sharedPreferences.getString("$name$KEY_UPLOAD_CONFIGS", null)

        if (str != null) {
            val gson = Gson()
            val mapType = object : TypeToken<Map<String, RNBGDUploadTaskConfig>>() {}.type
            return gson.fromJson(str, mapType)
        }
        return mutableMapOf()
    }

    /**
     * Remove an upload config by ID.
     */
    fun removeUploadConfig(configId: String) {
        val configs = loadUploadConfigs()
        if (configs.remove(configId) != null) {
            saveUploadConfigs(configs)
        }
    }
}

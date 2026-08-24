package com.eko

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle

internal data class NativeBuildConfig(
  val maxParallelDownloads: Int,
  val enableLogging: Boolean,
  val progressInterval: Long,
  val progressMinBytes: Long
) {
  companion object {
    const val MAX_PARALLEL_DOWNLOADS_KEY = "com.eko.rnbgd.MAX_PARALLEL_DOWNLOADS"
    const val ENABLE_LOGGING_KEY = "com.eko.rnbgd.ENABLE_LOGGING"
    const val PROGRESS_INTERVAL_KEY = "com.eko.rnbgd.PROGRESS_INTERVAL"
    const val PROGRESS_MIN_BYTES_KEY = "com.eko.rnbgd.PROGRESS_MIN_BYTES"

    fun fromMetadata(metadata: Bundle?): NativeBuildConfig {
      val maximum = metadata?.getInt(MAX_PARALLEL_DOWNLOADS_KEY, 4) ?: 4
      val interval = metadata?.getInt(PROGRESS_INTERVAL_KEY, 1000) ?: 1000
      val minimumBytes = metadata?.getInt(PROGRESS_MIN_BYTES_KEY, 1024 * 1024) ?: 1024 * 1024
      return NativeBuildConfig(
        maxParallelDownloads = maximum.takeIf { it >= 1 } ?: 4,
        enableLogging = metadata?.getBoolean(ENABLE_LOGGING_KEY, false) ?: false,
        progressInterval = interval.takeIf { it >= 250 }?.toLong() ?: 1000L,
        progressMinBytes = minimumBytes.takeIf { it >= 0 }?.toLong() ?: 1024L * 1024L
      )
    }

    fun load(context: Context): NativeBuildConfig {
      val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
      return fromMetadata(info.metaData)
    }
  }
}

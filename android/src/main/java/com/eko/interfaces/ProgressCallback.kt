package com.eko.interfaces

fun interface ProgressCallback {
    fun onProgress(configId: String, bytesDownloaded: Long, bytesTotal: Long)
}

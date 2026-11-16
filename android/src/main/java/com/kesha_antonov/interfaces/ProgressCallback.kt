package com.kesha_antonov.interfaces

fun interface ProgressCallback {
    fun onProgress(configId: String, bytesDownloaded: Long, bytesTotal: Long)
}

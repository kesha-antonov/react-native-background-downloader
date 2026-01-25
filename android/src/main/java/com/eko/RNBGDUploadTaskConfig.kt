package com.eko

/**
 * Configuration class for upload tasks.
 * Stores all necessary info for an upload and its state.
 */
data class RNBGDUploadTaskConfig(
    val id: String,
    val url: String,
    val source: String,
    val metadata: String = "{}",
    val method: String = "POST",
    val headers: Map<String, String>? = null,
    val fieldName: String? = null,
    val mimeType: String? = null,
    val parameters: Map<String, String>? = null,
    var reportedBegin: Boolean = false,
    var bytesUploaded: Long = 0,
    var bytesTotal: Long = 0,
    var state: Int = DownloadConstants.TASK_RUNNING,
    var errorCode: Int = 0
)

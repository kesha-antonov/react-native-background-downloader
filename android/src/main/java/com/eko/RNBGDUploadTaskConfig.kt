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
    // Upload source in parts, one URL per part (S3 multipart upload and similar)
    val partUrls: List<String>? = null,
    // Bytes per part; 0 means ceil(file size / number of parts)
    val partSize: Long = 0,
    var reportedBegin: Boolean = false,
    var bytesUploaded: Long = 0,
    var bytesTotal: Long = 0,
    var state: Int = DownloadConstants.TASK_RUNNING,
    var errorCode: Int = 0,
    // Parts the server accepted, persisted so a resumed upload continues after them.
    // Replaced, never mutated: it's serialized while the upload thread runs
    @Volatile var completedParts: List<UploadPartResult>? = null
)

/** One accepted part of an upload with partUrls. */
data class UploadPartResult(
    // 1-based, as S3's CompleteMultipartUpload numbers parts
    val partNumber: Int,
    val responseCode: Int,
    val responseBody: String,
    // Lowercased header names
    val responseHeaders: Map<String, String>,
    val bytes: Long
)

/**
 * Byte ranges of the parts: every part [partSize] bytes (default ceil(size / count))
 * and the last one what's left. Null when the parts can't cover the file exactly,
 * which includes an empty last part.
 */
internal fun uploadPartRanges(fileSize: Long, partCount: Int, partSize: Long): List<LongRange>? {
    if (partCount <= 0 || fileSize <= 0) return null
    val size = if (partSize > 0) partSize else (fileSize + partCount - 1) / partCount
    val beforeLast = size * (partCount - 1)
    if (beforeLast >= fileSize || fileSize - beforeLast > size) return null
    return (0 until partCount).map { index ->
        val start = size * index
        val end = if (index == partCount - 1) fileSize else start + size
        start until end
    }
}

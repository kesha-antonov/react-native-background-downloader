package com.eko

/**
 * Sealed class representing the possible outcomes of a download operation.
 * This provides type-safe result handling and eliminates the need for
 * mixed exception/callback error handling.
 */
sealed class DownloadResult {

    /**
     * Download completed successfully.
     * @param id The download identifier
     * @param location The final file location
     * @param bytesDownloaded Total bytes downloaded
     * @param bytesTotal Total file size
     */
    data class Success(
        val id: String,
        val location: String,
        val bytesDownloaded: Long,
        val bytesTotal: Long
    ) : DownloadResult()

    /**
     * Download was paused by user request.
     * @param id The download identifier
     * @param bytesDownloaded Bytes downloaded before pause
     * @param bytesTotal Total file size (may be -1 if unknown)
     */
    data class Paused(
        val id: String,
        val bytesDownloaded: Long,
        val bytesTotal: Long
    ) : DownloadResult()

    /**
     * Download was cancelled by user request.
     * @param id The download identifier
     */
    data class Cancelled(
        val id: String
    ) : DownloadResult()

    /**
     * Download failed with an error.
     * @param id The download identifier
     * @param message Error message
     * @param errorCode HTTP error code or -1 for non-HTTP errors
     * @param cause Optional underlying exception
     */
    data class Error(
        val id: String,
        val message: String,
        val errorCode: Int = -1,
        val cause: Throwable? = null
    ) : DownloadResult()

    /**
     * Download session was invalidated (stale thread detected).
     * This happens when a new download starts with the same ID.
     * @param id The download identifier
     */
    data class SessionInvalidated(
        val id: String
    ) : DownloadResult()

    // ========== Utility Methods ==========

    /**
     * Returns true if this result represents a terminal state
     * (success, error, or cancelled - not paused or invalidated).
     */
    fun isTerminal(): Boolean = when (this) {
        is Success, is Error, is Cancelled -> true
        is Paused, is SessionInvalidated -> false
    }

    /**
     * Returns true if this result represents a successful completion.
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * Returns true if this result represents an error.
     */
    fun isError(): Boolean = this is Error

    /**
     * Returns the download ID for any result type.
     */
    val downloadId: String
        get() = when (this) {
            is Success -> id
            is Paused -> id
            is Cancelled -> id
            is Error -> id
            is SessionInvalidated -> id
        }

    companion object {
        /**
         * Create an error result from an exception.
         */
        fun fromException(id: String, e: Throwable, errorCode: Int = -1): Error {
            return Error(
                id = id,
                message = e.message ?: "Unknown error",
                errorCode = errorCode,
                cause = e
            )
        }

        /**
         * Create an HTTP error result.
         */
        fun httpError(id: String, responseCode: Int, message: String? = null): Error {
            return Error(
                id = id,
                message = message ?: "HTTP error: $responseCode",
                errorCode = responseCode
            )
        }
    }
}

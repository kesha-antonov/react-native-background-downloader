package com.eko

/**
 * Centralized constants for the download library.
 * This file consolidates magic numbers and configuration values
 * that were previously scattered across multiple files.
 */
object DownloadConstants {

    // ========== Network Timeouts ==========

    /** Timeout for establishing HTTP connection (milliseconds) */
    const val CONNECT_TIMEOUT_MS = 30_000  // 30 seconds

    /** Timeout for reading HTTP response (milliseconds) */
    const val READ_TIMEOUT_MS = 30_000  // 30 seconds

    // ========== Download Buffer ==========

    /** Size of buffer for reading download data (bytes) */
    const val BUFFER_SIZE = 8192  // 8KB

    // ========== Progress Reporting ==========

    /** Minimum percentage change required to report progress */
    const val PROGRESS_REPORT_THRESHOLD = 0.01  // 1%

    // ========== HTTP Headers ==========

    /** Keep-Alive header value for connection pooling */
    const val KEEP_ALIVE_HEADER_VALUE = "timeout=600, max=1000"

    /** Library version for User-Agent - extracted from package.json via BuildConfig */
    val VERSION: String = BuildConfig.LIBRARY_VERSION

    /** User-Agent string for HTTP requests */
    val USER_AGENT: String = "ReactNative-BackgroundDownloader/$VERSION"

    // ========== Task States ==========

    /** Download is actively running */
    const val TASK_RUNNING = 0

    /** Download is paused/suspended */
    const val TASK_SUSPENDED = 1

    /** Download is being cancelled */
    const val TASK_CANCELING = 2

    /** Download completed successfully */
    const val TASK_COMPLETED = 3

    /**
     * Transfers the library's own downloader runs at once by default, matching
     * the JS `maxParallelDownloads` default. Downloads over the limit wait for a
     * slot instead of each taking a thread and a socket of their own.
     */
    const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 4
}

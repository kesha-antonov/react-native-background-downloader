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

    /** Timeout for HEAD requests when fetching headers (milliseconds) */
    const val HEAD_REQUEST_READ_TIMEOUT_MS = 60_000  // 60 seconds

    /** Timeout for redirect resolution requests (milliseconds) */
    const val REDIRECT_TIMEOUT_MS = 10_000  // 10 seconds

    // ========== Download Buffer ==========

    /** Size of buffer for reading download data (bytes) */
    const val BUFFER_SIZE = 8192  // 8KB

    // ========== Progress Reporting ==========

    /** Minimum percentage change required to report progress */
    const val PROGRESS_REPORT_THRESHOLD = 0.01  // 1%

    /** Interval for throttling progress log messages (milliseconds) */
    const val PROGRESS_LOG_INTERVAL_MS = 500L

    /** Interval for persisting in-progress resumable download recovery snapshots (milliseconds) */
    const val RECOVERY_SNAPSHOT_INTERVAL_MS = 2_000L

    /**
     * Grace period before classifying an IO error on an unmetered-only download (milliseconds).
     * A socket abort caused by network loss/metering usually arrives before ConnectivityService
     * updates the network's capabilities and delivers onLost - re-check after this delay.
     */
    const val UNMETERED_RECHECK_DELAY_MS = 3_000L

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

    // ========== Error Codes ==========

    /** Device storage is full */
    const val ERR_STORAGE_FULL = 0

    /** No internet connection */
    const val ERR_NO_INTERNET = 1

    /** Missing write permission */
    const val ERR_NO_WRITE_PERMISSION = 2

    /** File not found on server */
    const val ERR_FILE_NOT_FOUND = 3

    /** Other/unknown error */
    const val ERR_OTHERS = 100

    // ========== Service Configuration ==========

    /** Notification channel ID for foreground service */
    const val NOTIFICATION_CHANNEL_ID = "resumable_download_channel"

    /** Notification ID for foreground service */
    const val NOTIFICATION_ID = 9999

    /** Wake lock timeout (milliseconds) - 1 hour */
    const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L

    /** Number of concurrent download threads */
    const val DOWNLOAD_THREAD_POOL_SIZE = 3
}

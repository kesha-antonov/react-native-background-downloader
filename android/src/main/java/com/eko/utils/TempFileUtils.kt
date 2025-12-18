package com.eko.utils

import com.eko.RNBackgroundDownloaderModuleImpl
import java.io.File

/**
 * Utility functions for handling temporary files during downloads.
 */
object TempFileUtils {

    private const val TAG = "TempFileUtils"

    /** Standard suffix for temporary download files */
    const val TEMP_SUFFIX = ".tmp"

    /**
     * Get the temp file path for a destination.
     */
    fun getTempPath(destination: String): String = "$destination$TEMP_SUFFIX"

    /**
     * Get the temp File for a destination.
     */
    fun getTempFile(destination: String): File = File(getTempPath(destination))

    /**
     * Delete a temp file for a destination, logging any errors.
     * @return true if file was deleted or didn't exist, false if deletion failed
     */
    fun deleteTempFile(destination: String): Boolean {
        return try {
            val tempFile = getTempFile(destination)
            if (tempFile.exists()) {
                val deleted = tempFile.delete()
                if (deleted) {
                    RNBackgroundDownloaderModuleImpl.logD(TAG, "Deleted temp file: ${tempFile.absolutePath}")
                } else {
                    RNBackgroundDownloaderModuleImpl.logW(TAG, "Failed to delete temp file: ${tempFile.absolutePath}")
                }
                deleted
            } else {
                true // File doesn't exist, considered success
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logW(TAG, "Error deleting temp file for $destination: ${e.message}")
            false
        }
    }

    /**
     * Ensure parent directories exist for a file.
     * @return true if directories exist or were created, false otherwise
     */
    fun ensureParentDirs(file: File): Boolean {
        val parent = file.parentFile ?: return true
        return parent.exists() || parent.mkdirs()
    }

    /**
     * Move a temp file to its final destination.
     * First tries rename, then falls back to copy+delete.
     * @return true if move succeeded, false otherwise
     */
    fun moveToDestination(tempFile: File, destFile: File): Boolean {
        return try {
            ensureParentDirs(destFile)

            if (tempFile.renameTo(destFile)) {
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Renamed temp file to destination: ${destFile.absolutePath}")
                true
            } else {
                // Fallback: copy and delete
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
                RNBackgroundDownloaderModuleImpl.logD(TAG, "Copied temp file to destination: ${destFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            RNBackgroundDownloaderModuleImpl.logE(TAG, "Failed to move temp file to destination: ${e.message}")
            false
        }
    }

}

package com.eko.utils

import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object FileUtils {
    // TODO: We must change the way context is used.
    //       We can check the storage space before starting file downloads.
    //       Moving a source file requires space twice the size of the file.
    fun getAvailableSpace(context: Context): Long {
        val externalDirectory = context.getExternalFilesDir(null)
        val path = externalDirectory?.absolutePath ?: context.filesDir.absolutePath

        val statFs = StatFs(path)
        return statFs.availableBytes
    }

    @Throws(IOException::class)
    fun mv(sourceFile: File, destinationFile: File): Boolean {
        FileInputStream(sourceFile).channel.use { inChannel ->
            FileOutputStream(destinationFile).channel.use { outChannel ->
                var bytesTransferred: Long = 0
                val totalBytes = inChannel.size()
                while (bytesTransferred < totalBytes) {
                    val remainingBytes = totalBytes - bytesTransferred
                    val chunkSize = minOf(remainingBytes, Int.MAX_VALUE.toLong())
                    val transferredBytes = inChannel.transferTo(bytesTransferred, chunkSize, outChannel)
                    bytesTransferred += transferredBytes
                }
            }
        }
        return sourceFile.delete()
    }

    fun mkdirParent(file: File?): File? {
        if (file == null) return null

        val path = file.parent ?: return null

        val parent = File(path)
        if (!parent.exists()) {
            parent.mkdirs()
            return parent
        }

        return null
    }

    fun rm(file: File?): Boolean {
        if (file == null) return false

        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
}

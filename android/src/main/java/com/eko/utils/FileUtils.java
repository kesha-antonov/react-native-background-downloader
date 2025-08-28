package com.eko.utils;

import android.content.Context;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

public class FileUtils {
    // TODO: We must change the way context is used.
    //       We can check the storage space before starting file downloads.
    //       Moving a source file requires space twice the size of the file.
    public static long getAvailableSpace(Context context) {
        // Use internal files directory to be consistent with documents directory
        String path = context.getFilesDir().getAbsolutePath();

        StatFs statFs = new StatFs(path);
        return statFs.getAvailableBytes();
    }

    public static boolean mv(File sourceFile, File destinationFile) throws IOException {
        try (
                FileChannel inChannel = new FileInputStream(sourceFile).getChannel();
                FileChannel outChannel = new FileOutputStream(destinationFile).getChannel()
        ) {
            long bytesTransferred = 0;
            long totalBytes = inChannel.size();
            while (bytesTransferred < totalBytes) {
                long remainingBytes = totalBytes - bytesTransferred;
                long chunkSize = Math.min(remainingBytes, Integer.MAX_VALUE);
                long transferredBytes = inChannel.transferTo(bytesTransferred, chunkSize, outChannel);
                bytesTransferred += transferredBytes;
            }
            return sourceFile.delete();
        }
    }

    public static File mkdirParent(File file) {
        if (file == null) return null;

        String path = file.getParent();
        if (path == null) return null;

        File parent = new File(path);
        if (!parent.exists()) {
            parent.mkdirs();
            return parent;
        }

        return null;
    }

    public static boolean rm(File file) {
        if (file == null) return false;

        if (file.exists()) {
            return file.delete();
        }

        return false;
    }
}

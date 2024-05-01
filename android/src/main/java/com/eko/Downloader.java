package com.eko;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;

import java.util.HashSet;

import android.util.Log;

import static android.content.Context.DOWNLOAD_SERVICE;

public class Downloader {

    public DownloadManager downloadManager;
    private Context context;
    private HashSet<String> alreadySentIntentDownloadIds = new HashSet<>();

    public Downloader(Context ctx) {
        context = ctx;
        downloadManager = (DownloadManager) ctx.getSystemService(DOWNLOAD_SERVICE);
    }

    public long queueDownload(DownloadManager.Request request) {
        return downloadManager.enqueue(request);
    }

    public WritableMap checkDownloadStatus(long downloadId) {
        DownloadManager.Query downloadQuery = new DownloadManager.Query();
        downloadQuery.setFilterById(downloadId);
        Cursor cursor = downloadManager.query(downloadQuery);

        WritableMap result = Arguments.createMap();

        try {
            if (cursor.moveToFirst()) {
                result = getDownloadStatus(cursor);
            } else {
                result.putString("downloadId", String.valueOf(downloadId));
                result.putInt("status", DownloadManager.STATUS_FAILED);
                result.putInt("reason", -1);
                result.putString("reasonText", "COULD_NOT_FIND");
            }
        } catch (Exception e) {
            Log.e("RNBackgroundDownloader", "Downloader: " + Log.getStackTraceString(e));
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return result;
    }


    public int cancelDownload(long downloadId) {
        return downloadManager.remove(downloadId);
    }

    // WAITING FOR THE FIX TO BE MERGED
    // https://android-review.googlesource.com/c/platform/packages/providers/DownloadProvider/+/2089866
    public void pauseDownload(long downloadId) {
        // ContentValues values = new ContentValues();

        // values.put(Downloads.Impl.COLUMN_CONTROL, Downloads.Impl.CONTROL_PAUSED);
        // values.put(Downloads.Impl.COLUMN_STATUS,
        // Downloads.Impl.STATUS_PAUSED_BY_APP);

        // downloadManager.mResolver.update(ContentUris.withAppendedId(mBaseUri,
        // ids[0]), values, null, null)
    }

    public void resumeDownload(long downloadId) {
        // ContentValues values = new ContentValues();

        // values.put(Downloads.Impl.COLUMN_STATUS, Downloads.Impl.STATUS_PENDING);
        // values.put(Downloads.Impl.COLUMN_CONTROL, Downloads.Impl.CONTROL_RUN);
    }

    public String getReasonText(int status, int reason) {
        switch (status) {
            case DownloadManager.STATUS_FAILED:
                switch (reason) {
                    case DownloadManager.ERROR_CANNOT_RESUME:
                        return "ERROR_CANNOT_RESUME";
                    case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                        return "ERROR_DEVICE_NOT_FOUND";
                    case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                        return "ERROR_FILE_ALREADY_EXISTS";
                    case DownloadManager.ERROR_FILE_ERROR:
                        return "ERROR_FILE_ERROR";
                    case DownloadManager.ERROR_HTTP_DATA_ERROR:
                        return "ERROR_HTTP_DATA_ERROR";
                    case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                        return "ERROR_INSUFFICIENT_SPACE";
                    case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                        return "ERROR_TOO_MANY_REDIRECTS";
                    case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                        return "ERROR_UNHANDLED_HTTP_CODE";
                    default:
                        return "ERROR_UNKNOWN";
                }

            case DownloadManager.STATUS_PAUSED:
                switch (reason) {
                    case DownloadManager.PAUSED_QUEUED_FOR_WIFI:
                        return "PAUSED_QUEUED_FOR_WIFI";
                    case DownloadManager.PAUSED_UNKNOWN:
                        return "PAUSED_UNKNOWN";
                    case DownloadManager.PAUSED_WAITING_FOR_NETWORK:
                        return "PAUSED_WAITING_FOR_NETWORK";
                    case DownloadManager.PAUSED_WAITING_TO_RETRY:
                        return "PAUSED_WAITING_TO_RETRY";
                    default:
                        return "UNKNOWN";
                }

            default:
                return "UNKNOWN";
        }
    }


    public WritableMap getDownloadStatus(Cursor cursor) {
        String downloadId = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
        String localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
        String bytesDownloadedSoFar = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
        String totalSizeBytes = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));

        if (localUri != null) {
            localUri = localUri.replace("file://", "");
        }

        String reasonText = "";

        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            if(!alreadySentIntentDownloadIds.contains(downloadId)) {
                alreadySentIntentDownloadIds.add(downloadId);

                // broadcast the download complete to handle the case where the app was closed when the download was done
                Intent intent = new Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

                // You can add extras to the intent if needed
                intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, Long.parseLong(downloadId));
                context.sendBroadcast(intent);
            }
        } else {
            reasonText = getReasonText(status, reason);
        }

        WritableMap result = Arguments.createMap();

        result.putString("downloadId", downloadId);
        result.putInt("status", status);
        result.putInt("reason", reason);
        result.putString("reasonText", reasonText);

        result.putDouble("bytesDownloaded", Long.parseLong(bytesDownloadedSoFar));
        result.putDouble("bytesTotal", Long.parseLong(totalSizeBytes));
        result.putString("localUri", localUri);

        return result;
    }
}

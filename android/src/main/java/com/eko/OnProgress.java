package com.eko;

import android.app.DownloadManager;
import android.os.SystemClock;
import android.util.Log;

import android.database.Cursor;

import java.util.HashMap;

public class OnProgress extends Thread {
  private final RNBGDTaskConfig config;
  private final long downloadId;
  private final HashMap downloadParams;
  private final ProgressCallback callback;
  private long bytesDownloaded;
  private long bytesTotal;
  private final DownloadManager.Query query;
  private final Downloader downloader;
  private Cursor cursor;
  private boolean isRunning = true;

  public OnProgress(RNBGDTaskConfig config, long downloadId, HashMap downloadParams, Downloader downloader, ProgressCallback callback) {
    this.config = config;
    this.downloadId = downloadId;
    this.downloadParams = downloadParams;
    this.downloader = downloader;
    this.callback = callback;
    this.query = new DownloadManager.Query();
    query.setFilterById(this.downloadId);

    Double expectedBytes = (Double) downloadParams.get("expectedBytes");
    this.bytesDownloaded = 0;;
    this.bytesTotal = expectedBytes != null ? expectedBytes.longValue() : 0;
  }

  @Override
  public void run() {
    while (isRunning) {
      try {
        cursor = downloader.downloadManager.query(query);

        if (!cursor.moveToFirst()) {
          isRunning = false;
          break;
        }

        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_SUCCESSFUL) {
          isRunning = false;
          break;
        }

        int byteTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
        long byteTotal = (long) cursor.getDouble(byteTotalIndex);
        bytesTotal = byteTotal > 0 ? byteTotal : bytesTotal;

        int byteDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
        long byteDownloaded = (long) cursor.getDouble(byteDownloadedIndex);
        bytesDownloaded = byteDownloaded > 0 ? byteDownloaded : bytesDownloaded;

        callback.onProgress(config.id, bytesDownloaded, bytesTotal);

        boolean completed = bytesTotal > 0 && bytesDownloaded > 0 && bytesDownloaded == bytesTotal;
        if (completed) {
          isRunning = false;
        }
      } catch (Exception e) {
        isRunning = false;
        Log.e("RNBackgroundDownloader", "OnProgress: " + Log.getStackTraceString(e));
      } finally {
        if (cursor != null) {
          cursor.close();
        }
      }

      SystemClock.sleep(250);
    }
  }
}
package com.eko;

import android.app.DownloadManager;
import android.os.SystemClock;
import android.util.Log;

import android.database.Cursor;

public class OnProgress extends Thread {
  private final long downloadId;
  private final DownloadManager.Query query;
  private final Downloader downloader;
  private Cursor cursor;
  private long bytesDownloaded;
  private long bytesTotal;
  private ProgressCallback callback;
  private RNBGDTaskConfig config;
  private boolean isRunning = true;

  public OnProgress(RNBGDTaskConfig config, long downloadId, Downloader downloader, ProgressCallback callback) {
    this.config = config;
    this.callback = callback;
    this.downloadId = downloadId;
    this.downloader = downloader;
    this.query = new DownloadManager.Query();
    query.setFilterById(this.downloadId);
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

        boolean isTotalByteCalculated = bytesTotal > 0;

        if (!isTotalByteCalculated) {
          int byteTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
          bytesTotal = byteTotalIndex != -1 ? (long) cursor.getDouble(byteTotalIndex) : 0;
        } else {
          int byteDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
          bytesDownloaded = byteDownloadedIndex != -1 ? (long) cursor.getDouble(byteDownloadedIndex) : 0;

          if (bytesDownloaded == bytesTotal) {
            isRunning = false;
          } else {
            bytesDownloaded = bytesTotal;
          }

          callback.onProgress(config.id, bytesDownloaded, bytesTotal);
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
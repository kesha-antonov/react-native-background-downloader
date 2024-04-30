package com.eko;

import android.app.DownloadManager;
import android.util.Log;

import android.database.Cursor;

public class OnProgress extends Thread {
  private final long downloadId;
  private final DownloadManager.Query query;
  private final Downloader downloader;
  private Cursor cursor;
  private long lastBytesDownloaded;
  private long bytesTotal;
  private ProgressCallback callback;

  private RNBGDTaskConfig config;

  public OnProgress(RNBGDTaskConfig config, long downloadId, Downloader downloader, ProgressCallback callback) {
    this.config = config;
    this.callback = callback;
    this.downloadId = downloadId;
    this.downloader = downloader;
    this.query = new DownloadManager.Query();
    query.setFilterById(this.downloadId);
  }

  private void handleInterrupt() {
    try {
      if (cursor != null) {
        cursor.close();
      }
    } catch (Exception e) {
      return;
    }
    this.interrupt();
  }

  @Override
  public void run() {
    while (downloadId > 0) {
      try {
        cursor = downloader.downloadManager.query(query);

        if (!cursor.moveToFirst()) {
          this.handleInterrupt();
        }

        int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
        if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_SUCCESSFUL) {
          this.handleInterrupt();
        }

        if (status == DownloadManager.STATUS_PAUSED) {
          Thread.sleep(5000);
        } else if (status == DownloadManager.STATUS_PENDING) {
          Thread.sleep(1000);
        } else {
          Thread.sleep(250);
        }

        // get total bytes of the file
        if (bytesTotal <= 0) {
          bytesTotal = (long)(cursor.getDouble(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
        }

        long bytesDownloaded = (long)(cursor.getDouble(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));

        if (bytesTotal > 0 && bytesDownloaded == bytesTotal) {
          this.handleInterrupt();
        } else {
          lastBytesDownloaded = bytesDownloaded;
        }

        if (lastBytesDownloaded > 0 && bytesTotal > 0) {
          callback.onProgress(config.id, lastBytesDownloaded, bytesTotal);
        }
      } catch (Exception e) {
        return;
      }

      try {
        if (cursor != null) {
          cursor.close();
        }
      } catch (Exception e) {
        e.printStackTrace();
        Log.e("RNBackgroundDownloader", "RNBD: OnProgress e: " + Log.getStackTraceString(e));
        return;
      }
    }
  }
}

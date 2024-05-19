package com.eko.handlers;

import android.app.DownloadManager;
import android.os.SystemClock;
import android.util.Log;

import android.database.Cursor;

import com.eko.Downloader;
import com.eko.interfaces.ProgressCallback;
import com.eko.RNBGDTaskConfig;

public class OnProgress extends Thread {
  private final RNBGDTaskConfig config;
  private final Downloader downloader;
  private final long downloadId;
  private long bytesDownloaded;
  private long bytesTotal;
  private final ProgressCallback callback;
  private final DownloadManager.Query query;
  private Cursor cursor;
  private boolean isRunning = true;

  public OnProgress(
          RNBGDTaskConfig config,
          Downloader downloader,
          long downloadId,
          long bytesDownloaded,
          long bytesTotal,
          ProgressCallback callback
  ) {
    this.config = config;
    this.downloader = downloader;
    this.downloadId = downloadId;
    this.bytesDownloaded = bytesDownloaded;
    this.bytesTotal = bytesTotal;
    this.callback = callback;
    this.query = new DownloadManager.Query().setFilterById(this.downloadId);
  }

  @Override
  public void interrupt() {
    super.interrupt();
    isRunning = false;
  }

  @Override
  public void run() {
    while (isRunning) {
      int status = -1;

      try {
        cursor = downloader.downloadManager.query(query);

        if (!cursor.moveToFirst()) {
          isRunning = false;
          break;
        }

        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
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

      if (status == DownloadManager.STATUS_PAUSED) {
        SystemClock.sleep(2000);
      } else if (status == DownloadManager.STATUS_PENDING) {
        SystemClock.sleep(1000);
      } else {
        SystemClock.sleep(250);
      }
    }
  }
}

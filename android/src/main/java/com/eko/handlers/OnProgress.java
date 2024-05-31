package com.eko.handlers;

import android.app.DownloadManager;
import android.os.SystemClock;

import android.database.Cursor;
import android.util.Log;

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
  }

  @Override
  public void interrupt() {
    super.interrupt();
    stopLoop();
  }

  @Override
  public void run() {
    while (isRunning) {
      int status = -1;
      DownloadManager.Query query = new DownloadManager.Query();
      query.setFilterById(downloadId);

      try (Cursor cursor = downloader.downloadManager.query(query)) {
        if (!cursor.moveToFirst()) {
          stopLoop();
          return;
        }

        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        switch (status) {
          case DownloadManager.STATUS_SUCCESSFUL:
            stopLoop();
            return;
          case DownloadManager.STATUS_FAILED:
          case DownloadManager.STATUS_PAUSED:
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            String reasonText = downloader.getReasonText(status, reason);
            throw new Exception(reasonText);
        }

        boolean completed = updateProgress(cursor);
        if (completed) {
          stopLoop();
          return;
        }
      } catch (Exception e) {
        stopLoop();
        // Report the error that occurs in the loop to DownloadManager.
        downloader.broadcast(downloadId);
        Log.e("RNBackgroundDownloader", "OnProgress: " + Log.getStackTraceString(e));
      }

      SystemClock.sleep(getSleepDuration(status));
    }
  }

  private void stopLoop() {
    this.isRunning = false;
  }

  private boolean updateProgress(Cursor cursor) {
    long byteTotal = getColumnValue(DownloadManager.COLUMN_TOTAL_SIZE_BYTES, cursor);
    bytesTotal = byteTotal > 0 ? byteTotal : bytesTotal;

    long byteDownloaded = getColumnValue(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, cursor);
    bytesDownloaded = byteDownloaded > 0 ? byteDownloaded : bytesDownloaded;

    if (bytesTotal > 0) {
      callback.onProgress(config.id, bytesDownloaded, bytesTotal);
    }

    return bytesTotal > 0 && bytesDownloaded > 0 && bytesDownloaded == bytesTotal;
  }

  private long getColumnValue(String column, Cursor cursor) {
    int columnIndex = cursor.getColumnIndex(column);
    return columnIndex != -1 ? (long) cursor.getDouble(columnIndex) : 0;
  }

  private long getSleepDuration(int status) {
    switch (status) {
      case DownloadManager.STATUS_PAUSED:
        return 2000;
      case DownloadManager.STATUS_PENDING:
        return 1000;
      default:
        return 250;
    }
  }
}

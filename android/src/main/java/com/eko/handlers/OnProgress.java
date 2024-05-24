package com.eko.handlers;

import android.app.DownloadManager;
import android.os.SystemClock;
import android.util.Log;

import android.database.Cursor;

import com.eko.Downloader;
import com.eko.interfaces.ProgressCallback;
import com.eko.RNBGDTaskConfig;

import java.util.Objects;

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
    isRunning = false;
  }

  @Override
  public void run() {
    while (isRunning) {
      int status = -1;

      DownloadManager.Query query = new DownloadManager.Query();
      query.setFilterById(downloadId);
      Cursor cursor = downloader.downloadManager.query(query);

      try {
        if (!cursor.moveToFirst()) {
          throw new Exception("LoopBreaker");
        }

        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_SUCCESSFUL) {
          throw new Exception("LoopBreaker");
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

        if (!Objects.equals(e.getMessage(), "LoopBreaker")) {
          Log.e("RNBackgroundDownloader", "OnProgress: " + Log.getStackTraceString(e));
        }
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

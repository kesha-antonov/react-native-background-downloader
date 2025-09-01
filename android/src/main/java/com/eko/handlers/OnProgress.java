package com.eko.handlers;

import android.app.DownloadManager;
import android.os.SystemClock;

import android.database.Cursor;

import com.eko.Downloader;
import com.eko.interfaces.ProgressCallback;
import com.eko.RNBGDTaskConfig;

import java.util.concurrent.Callable;

public class OnProgress implements Callable<OnProgressState> {
  private final RNBGDTaskConfig config;
  private final Downloader downloader;
  private final long downloadId;
  private long bytesDownloaded;
  private long bytesTotal;
  private final ProgressCallback callback;
  private volatile boolean isRunning = true;
  private volatile boolean isCompleted = true;

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
  public OnProgressState call() throws Exception {
    while (isRunning) {
      int status = -1;
      DownloadManager.Query query = new DownloadManager.Query();
      query.setFilterById(downloadId);

      try (Cursor cursor = downloader.downloadManager.query(query)) {
        if (!cursor.moveToFirst()) {
          stopLoopWithFail();
          break;
        }

        // TODO: Maybe we can write some logic in the pause codes here.
        //       For example; PAUSED_WAITING_TO_RETRY attempts count?
        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
          stopLoopWithSuccess();
          break;
        }
        if (status == DownloadManager.STATUS_FAILED) {
          int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
          String reasonText = downloader.getReasonText(status, reason);
          throw new Exception(reasonText);
        }

        boolean completed = updateProgress(cursor);
        if (completed) {
          stopLoopWithSuccess();
          break;
        }
      } catch (Exception e) {
        stopLoopWithFail();
        // if reached maximum memory while downloading, the downloader broadcast can not receive event normally
        downloader.broadcast(downloadId);
        throw e;
      }

      SystemClock.sleep(getSleepDuration(status));
    }

    return isCompleted ? new OnProgressState(config.id, bytesDownloaded, bytesTotal) : null;
  }

  private void stopLoopWithSuccess() {
    this.isRunning = false;
    this.isCompleted = true;
  }

  private void stopLoopWithFail() {
    this.isRunning = false;
    this.isCompleted = false;
  }

  private boolean updateProgress(Cursor cursor) {
    long byteTotal = getColumnValue(DownloadManager.COLUMN_TOTAL_SIZE_BYTES, cursor);
    bytesTotal = byteTotal > 0 ? byteTotal : bytesTotal;

    long byteDownloaded = getColumnValue(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR, cursor);
    bytesDownloaded = byteDownloaded > 0 ? byteDownloaded : bytesDownloaded;

    // Always call progress callback, even when total bytes are unknown (for realtime streams)
    callback.onProgress(config.id, bytesDownloaded, bytesTotal);

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

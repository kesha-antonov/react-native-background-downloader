package com.eko;

import android.os.SystemClock;
import android.util.Log;

import com.eko.handlers.OnBegin;
import com.eko.handlers.OnProgress;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import android.app.DownloadManager;
import android.app.DownloadManager.Request;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.net.Uri;
import android.webkit.MimeTypeMap;
import android.database.Cursor;
import android.os.Build;

import androidx.annotation.NonNull;

import com.tencent.mmkv.MMKV;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class RNBackgroundDownloaderModule extends ReactContextBaseJavaModule {

  private static final int TASK_RUNNING = 0;
  private static final int TASK_SUSPENDED = 1;
  private static final int TASK_CANCELING = 2;
  private static final int TASK_COMPLETED = 3;

  private static final int ERR_STORAGE_FULL = 0;
  private static final int ERR_NO_INTERNET = 1;
  private static final int ERR_NO_WRITE_PERMISSION = 2;
  private static final int ERR_FILE_NOT_FOUND = 3;
  private static final int ERR_OTHERS = 100;

  private static Map<Integer, Integer> stateMap = new HashMap<Integer, Integer>() {
    {
      put(DownloadManager.STATUS_FAILED, TASK_CANCELING);
      put(DownloadManager.STATUS_PAUSED, TASK_SUSPENDED);
      put(DownloadManager.STATUS_PENDING, TASK_RUNNING);
      put(DownloadManager.STATUS_RUNNING, TASK_RUNNING);
      put(DownloadManager.STATUS_SUCCESSFUL, TASK_COMPLETED);
    }
  };

  private static MMKV mmkv;
  private Downloader downloader;
  private BroadcastReceiver downloadReceiver;
  private static final Object sharedLock = new Object();
  private Map<Long, RNBGDTaskConfig> downloadIdToConfig = new HashMap<>();
  private Map<String, Long> configIdToDownloadId = new HashMap<>();
  private Map<String, Double> configIdToPercent = new HashMap<>();
  private Map<String, WritableMap> progressReports = new HashMap<>();
  private Date lastProgressReportedAt = new Date();
  private int progressInterval;
  private Map<String, OnProgress> onProgressThreads = new HashMap<>();
  private DeviceEventManagerModule.RCTDeviceEventEmitter ee;

  public RNBackgroundDownloaderModule(ReactApplicationContext reactContext) {
    super(reactContext);
    MMKV.initialize(reactContext);

    mmkv = MMKV.mmkvWithID(getName());

    loadDownloadIdToConfigMap();
    loadConfigMap();

    downloader = new Downloader(reactContext);
  }

  @NonNull
  @Override
  public String getName() {
    return "RNBackgroundDownloader";
  }

  @Nullable
  @Override
  public Map<String, Object> getConstants() {
    Map<String, Object> constants = new HashMap<>();
    File externalDirectory = this.getReactApplicationContext().getExternalFilesDir(null);
    if (externalDirectory != null) {
      constants.put("documents", externalDirectory.getAbsolutePath());
    } else {
      constants.put("documents", this.getReactApplicationContext().getFilesDir().getAbsolutePath());
    }

    constants.put("TaskRunning", TASK_RUNNING);
    constants.put("TaskSuspended", TASK_SUSPENDED);
    constants.put("TaskCanceling", TASK_CANCELING);
    constants.put("TaskCompleted", TASK_COMPLETED);

    return constants;
  }

  @Override
  public void initialize() {
    super.initialize();
    ee = getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
    registerDownloadReceiver();
  }

  @Override
  public void onCatalystInstanceDestroy() {
    super.onCatalystInstanceDestroy();
    unregisterDownloadReceiver();
  }

  private void registerDownloadReceiver() {
    downloadReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        try {
          long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);

          RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);

          if (config != null) {
            WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
            int status = downloadStatus.getInt("status");

            stopTrackingProgress(config.id);

            synchronized (sharedLock) {
              switch (status) {
                case DownloadManager.STATUS_SUCCESSFUL: {
                  onSuccessfulDownload(config, downloadStatus);
                  break;
                }
                case DownloadManager.STATUS_FAILED: {
                  onFailedDownload(config, downloadStatus);
                  break;
                }
              }
            }
          }
        } catch (Exception e) {
          Log.e(getName(), "registerDownloadReceiver: " + Log.getStackTraceString(e));
        }
      }
    };

    Context context = getReactApplicationContext();
    IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
    compatRegisterReceiver(context, downloadReceiver, filter, true);
  }

  // TAKEN FROM
  // https://github.com/facebook/react-native/pull/38256/files#diff-d5e21477eeadeb0c536d5870f487a8528f9a16ae928c397fec7b255805cc8ad3
  private void compatRegisterReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter, boolean exported) {
    if (Build.VERSION.SDK_INT >= 34) {
      context.registerReceiver(receiver, filter, exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED);
    } else {
      context.registerReceiver(receiver, filter);
    }
  }

  private void unregisterDownloadReceiver() {
    if (downloadReceiver != null) {
      getReactApplicationContext().unregisterReceiver(downloadReceiver);
      downloadReceiver = null;
    }
  }

  private void removeTaskFromMap(long downloadId) {
    synchronized (sharedLock) {
      RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);

      if (config != null) {
        configIdToDownloadId.remove(config.id);
        downloadIdToConfig.remove(downloadId);
        configIdToPercent.remove(config.id);

        saveDownloadIdToConfigMap();
      }
    }
  }

  @ReactMethod
  public void download(ReadableMap options) {
    final String id = options.getString("id");
    String url = options.getString("url");
    String destination = options.getString("destination");
    ReadableMap headers = options.getMap("headers");
    String metadata = options.getString("metadata");
    int progressIntervalScope = options.getInt("progressInterval");
    if (progressIntervalScope > 0) {
      progressInterval = progressIntervalScope;
      saveConfigMap();
    }

    boolean isAllowedOverRoaming = options.getBoolean("isAllowedOverRoaming");
    boolean isAllowedOverMetered = options.getBoolean("isAllowedOverMetered");
    boolean isNotificationVisible = options.getBoolean("isNotificationVisible");

    if (id == null || url == null || destination == null) {
      Log.e(getName(),"download: id, url and destination must be set.");
      return;
    }

    RNBGDTaskConfig config = new RNBGDTaskConfig(id, url, destination, metadata);
    final Request request = new Request(Uri.parse(url));
    request.setAllowedOverRoaming(isAllowedOverRoaming);
    request.setAllowedOverMetered(isAllowedOverMetered);
    request.setNotificationVisibility(isNotificationVisible ? Request.VISIBILITY_VISIBLE : Request.VISIBILITY_HIDDEN);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      request.setRequiresCharging(false);
    }

    int uuid = (int) (System.currentTimeMillis() & 0xfffffff);
    String extension = MimeTypeMap.getFileExtensionFromUrl(destination);
    String filename = uuid + "." + extension;
    request.setDestinationInExternalFilesDir(this.getReactApplicationContext(), null, filename);

    if (headers != null) {
      ReadableMapKeySetIterator it = headers.keySetIterator();
      while (it.hasNextKey()) {
        String headerKey = it.nextKey();
        request.addRequestHeader(headerKey, headers.getString(headerKey));
      }
    }

    long downloadId = downloader.queueDownload(request);

    synchronized (sharedLock) {
      configIdToDownloadId.put(id, downloadId);
      downloadIdToConfig.put(downloadId, config);
      configIdToPercent.put(id, 0.0);
      saveDownloadIdToConfigMap();

      if (config.reportedBegin) {
        return;
      }

      startReportingTasks(downloadId, config);
    }
  }

  // TODO: NOT WORKING WITH DownloadManager FOR NOW
  @ReactMethod
  public void pauseTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        downloader.pauseDownload(downloadId);
      }
    }
  }

  // TODO: NOT WORKING WITH DownloadManager FOR NOW
  @ReactMethod
  public void resumeTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        downloader.resumeDownload(downloadId);
      }
    }
  }

  @ReactMethod
  public void stopTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        // DELETES CONFIG HERE SO receiver WILL NOT THROW ERROR DOWNLOAD_FAILED TO THE
        // USER
        removeTaskFromMap(downloadId);
        stopTrackingProgress(configId);

        downloader.cancelDownload(downloadId);
      }
    }
  }

  @ReactMethod
  public void completeHandler(String configId, final Promise promise) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        removeTaskFromMap(downloadId);
        // REMOVES DOWNLOAD FROM DownloadManager SO IT WOULD NOT BE RETURNED IN
        // checkForExistingDownloads
        downloader.cancelDownload(downloadId);
      }
    }
  }

  @ReactMethod
  public void checkForExistingDownloads(final Promise promise) {
    WritableArray foundIds = Arguments.createArray();

    synchronized (sharedLock) {
      Cursor cursor = null;

      try {
        DownloadManager.Query downloadQuery = new DownloadManager.Query();
        cursor = downloader.downloadManager.query(downloadQuery);

        if (cursor.moveToFirst()) {
          do {
            WritableMap downloadStatus = downloader.getDownloadStatus(cursor);
            Long downloadId = Long.parseLong(downloadStatus.getString("downloadId"));

            if (downloadIdToConfig.containsKey(downloadId)) {
              RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);
              WritableMap params = Arguments.createMap();
              params.putString("id", config.id);
              params.putString("metadata", config.metadata);
              params.putInt("state", stateMap.get(downloadStatus.getInt("status")));

              double bytesDownloaded = downloadStatus.getDouble("bytesDownloaded");
              params.putDouble("bytesDownloaded", bytesDownloaded);

              double bytesTotal = downloadStatus.getDouble("bytesTotal");
              params.putDouble("bytesTotal", bytesTotal);

              foundIds.pushMap(params);

              // TODO: MAYBE ADD headers
              configIdToDownloadId.put(config.id, downloadId);
              configIdToPercent.put(config.id, bytesDownloaded / bytesTotal);
            } else {
              downloader.cancelDownload(downloadId);
            }
          } while (cursor.moveToNext());
        }
      } catch (Exception e) {
        Log.e(getName(), "checkForExistingDownloads: " + Log.getStackTraceString(e));
      } finally {
        if (cursor != null) {
          cursor.close();
        }
      }
    }

    promise.resolve(foundIds);
  }

  @ReactMethod
  public void addListener(String eventName) {}

  @ReactMethod
  public void removeListeners(Integer count) {}

  private void startReportingTasks(Long downloadId, RNBGDTaskConfig config) {
    config.reportedBegin = true;
    downloadIdToConfig.put(downloadId, config);
    saveDownloadIdToConfigMap();

    // report begin & progress
    // overlaped with thread to not block main thread
    new Thread(() -> {
      try {
        while (true) {
          WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
          int status = downloadStatus.getInt("status");
          if (status == DownloadManager.STATUS_RUNNING) {
            break;
          } else if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_SUCCESSFUL) {
            Thread.currentThread().interrupt();
            break;
          }

          SystemClock.sleep(500);
        }

        OnBegin onBeginThread = new OnBegin(config, this::onBeginDownload);
        onBeginThread.start();
        onBeginThread.join();

        Double expectedBytes = (Double) onBeginThread.getParams().get("expectedBytes");
        long bytesDownloaded = 0;
        long bytesTotal = expectedBytes != null ? expectedBytes.longValue() : 0;

        OnProgress onProgressThread = new OnProgress(config, downloader, downloadId, bytesDownloaded, bytesTotal, this::onProgressDownload);
        onProgressThreads.put(config.id, onProgressThread);
        onProgressThread.start();
      } catch (Exception e) {
        Log.e(getName(), "startReportingTasks: " + Log.getStackTraceString(e));
      }
    }).start();
  }

  private void onBeginDownload(String configId, WritableMap headers, long expectedBytes) {
    WritableMap params = Arguments.createMap();
    params.putString("id", configId);
    params.putMap("location", headers);
    params.putDouble("expectedBytes", expectedBytes);
    ee.emit("downloadBegin", params);
  }

  private void onProgressDownload(String configId, long bytesDownloaded, long bytesTotal) {
    boolean prevPercentContain = configIdToPercent.containsKey(configId);
    double prevPercent = prevPercentContain ? configIdToPercent.get(configId) : 0;
    double percent = bytesTotal > 0 ? (double) bytesDownloaded / bytesTotal : 0;

    if (!prevPercentContain || percent - prevPercent > 0.01) {
      WritableMap params = Arguments.createMap();
      params.putString("id", configId);
      params.putDouble("bytesDownloaded", bytesDownloaded);
      params.putDouble("bytesTotal", bytesTotal);
      progressReports.put(configId, params);
      configIdToPercent.put(configId, percent);
    }

    Date now = new Date();
    boolean conditionContain = progressReports.containsKey(configId);
    boolean conditionInterval = now.getTime() - lastProgressReportedAt.getTime() > progressInterval;

    if (conditionContain && conditionInterval) {
      WritableArray reportsArray = Arguments.createArray();
      for (Object report : progressReports.values()) {
        reportsArray.pushMap((WritableMap) report);
      }
      ee.emit("downloadProgress", reportsArray);
      lastProgressReportedAt = now;
      progressReports.clear();
    }
  }

  private void onSuccessfulDownload(RNBGDTaskConfig config, WritableMap downloadStatus) {
    String localUri = downloadStatus.getString("localUri");
    File file = new File(localUri);
    File dest = new File(config.destination);

    // only move if source file exists (to handle case if this intent is double called)
    if(file.exists()) {
      // REMOVE DEST FILE IF EXISTS
      if (dest.exists()) {
        dest.delete();
      }

      // CREATE DESTINATION DIR IF NOT EXISTS
      File destDir = new File(dest.getParent());
      if (!destDir.exists()) {
        destDir.mkdirs();
      }

      // MOVE FILE
      moveFile(file, dest);
      file.delete();
    }

    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putString("location", config.destination);
    params.putInt("bytesDownloaded", downloadStatus.getInt("bytesDownloaded"));
    params.putInt("bytesTotal", downloadStatus.getInt("bytesTotal"));
    ee.emit("downloadComplete", params);
  }

  private void onFailedDownload(RNBGDTaskConfig config, WritableMap downloadStatus) {
    Log.e(getName(), "onReceive: " +
            downloadStatus.getInt("status") + ":" +
            downloadStatus.getInt("reason") + ":" +
            downloadStatus.getString("reasonText")
    );

    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putInt("errorCode", downloadStatus.getInt("reason"));
    params.putString("error", downloadStatus.getString("reasonText"));
    ee.emit("downloadFailed", params);
  }

  private void saveDownloadIdToConfigMap() {
    synchronized (sharedLock) {
      Gson gson = new Gson();
      String str = gson.toJson(downloadIdToConfig);
      mmkv.encode(getName() + "_downloadIdToConfig", str);
    }
  }

  private void loadDownloadIdToConfigMap() {
    synchronized (sharedLock) {
      try {
        String str = mmkv.decodeString(getName() + "_downloadIdToConfig");
        if (str != null) {
          Gson gson = new Gson();
          TypeToken<Map<Long, RNBGDTaskConfig>> mapType = new TypeToken<Map<Long, RNBGDTaskConfig>>() {};
          downloadIdToConfig = gson.fromJson(str, mapType);
        }
      } catch (Exception e) {
        Log.e(getName(), "loadDownloadIdToConfigMap: " + Log.getStackTraceString(e));
      }
    }
  }

  private void saveConfigMap() {
    synchronized (sharedLock) {
      mmkv.encode(getName() + "_progressInterval", progressInterval);
    }
  }

  private void loadConfigMap() {
    synchronized (sharedLock) {
      int progressIntervalScope = mmkv.decodeInt(getName() + "_progressInterval");
      if (progressIntervalScope > 0) {
        progressInterval = progressIntervalScope;
      }
    }
  }

  private void stopTrackingProgress(String configId) {
    OnProgress onProgressThread = onProgressThreads.get(configId);
    if (onProgressThread != null) {
      onProgressThread.interrupt();
      onProgressThreads.remove(configId);
      configIdToPercent.remove(configId);
    }
  }

  private static void moveFile(File src, File dst) {
    try (FileChannel inChannel = new FileInputStream(src).getChannel(); FileChannel outChannel = new FileOutputStream(dst).getChannel()) {
      inChannel.transferTo(0, inChannel.size(), outChannel);
      src.delete();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

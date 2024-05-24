package com.eko;

import android.os.Handler;
import android.os.Looper;
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

  private static final Map<Integer, Integer> stateMap = new HashMap<Integer, Integer>() {
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
  private Map<String, OnProgress> configIdToProgressThreads = new HashMap<>();
  private Map<String, WritableMap> progressReports = new HashMap<>();
  private int progressInterval = 0;
  private Date lastProgressReportedAt = new Date();
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
    Context context =  this.getReactApplicationContext();
    Map<String, Object> constants = new HashMap<>();

    File externalDirectory = context.getExternalFilesDir(null);
    if (externalDirectory != null) {
      constants.put("documents", externalDirectory.getAbsolutePath());
    } else {
      constants.put("documents", context.getFilesDir().getAbsolutePath());
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
    Context context = getReactApplicationContext();
    IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

    downloadReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        try {
          long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
          RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);

          if (config != null) {
            WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
            int status = downloadStatus.getInt("status");

            stopTaskProgress(config.id);

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

    if (Build.VERSION.SDK_INT >= 34) {
      context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
    } else {
      context.registerReceiver(downloadReceiver, filter);
    }
  }

  private void unregisterDownloadReceiver() {
    if (downloadReceiver != null) {
      getReactApplicationContext().unregisterReceiver(downloadReceiver);
      downloadReceiver = null;
    }
  }

  private void resumeTasks(Long downloadId, RNBGDTaskConfig config) {
    config.reportedBegin = true;
    downloadIdToConfig.put(downloadId, config);
    saveDownloadIdToConfigMap();

    new Thread(() -> {
      try {
        OnBegin onBeginThread = new OnBegin(config, this::onBeginDownload);
        onBeginThread.start();
        onBeginThread.join();

        long bytesDownloaded = 0;
        long bytesTotal = onBeginThread.getBytesExpected();

        OnProgress onProgressThread = new OnProgress(config, downloader, downloadId, bytesDownloaded, bytesTotal, this::onProgressDownload);
        configIdToProgressThreads.put(config.id, onProgressThread);
        onProgressThread.start();
      } catch (Exception e) {
        Log.e(getName(), "resumeTasks: " + Log.getStackTraceString(e));
      }
    }).start();
  }

  private void removeTaskFromMap(long downloadId) {
    synchronized (sharedLock) {
      RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);

      if (config != null) {
        configIdToDownloadId.remove(config.id);
        configIdToPercent.remove(config.id);
        downloadIdToConfig.remove(downloadId);
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

    final Request request = new Request(Uri.parse(url));
    request.setAllowedOverRoaming(isAllowedOverRoaming);
    request.setAllowedOverMetered(isAllowedOverMetered);
    request.setNotificationVisibility(isNotificationVisible ? Request.VISIBILITY_VISIBLE : Request.VISIBILITY_HIDDEN);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      request.setRequiresCharging(false);
    }

    if (headers != null) {
      ReadableMapKeySetIterator it = headers.keySetIterator();
      while (it.hasNextKey()) {
        String headerKey = it.nextKey();
        request.addRequestHeader(headerKey, headers.getString(headerKey));
      }
    }

    int uuid = (int) (System.currentTimeMillis() & 0xfffffff);
    String extension = MimeTypeMap.getFileExtensionFromUrl(destination);
    String filename = uuid + "." + extension;
    request.setDestinationInExternalFilesDir(this.getReactApplicationContext(), null, filename);

    long downloadId = downloader.download(request);
    RNBGDTaskConfig config = new RNBGDTaskConfig(id, url, destination, metadata);

    synchronized (sharedLock) {
      configIdToDownloadId.put(id, downloadId);
      configIdToPercent.put(id, 0.0);
      downloadIdToConfig.put(downloadId, config);
      saveDownloadIdToConfigMap();

      if (config.reportedBegin) return;

      resumeTasks(downloadId, config);
    }
  }

  // TODO: Not working with DownloadManager for now.
  @ReactMethod
  public void pauseTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        downloader.pause(downloadId);
      }
    }
  }

  // TODO: Not working with DownloadManager for now.
  @ReactMethod
  public void resumeTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        downloader.resume(downloadId);
      }
    }
  }

  @ReactMethod
  public void stopTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        stopTaskProgress(configId);
        removeTaskFromMap(downloadId);
        delay(() -> downloader.cancel(downloadId), 500);
      }
    }
  }

  @ReactMethod
  public void completeHandler(String configId, final Promise promise) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        stopTaskProgress(configId);
        removeTaskFromMap(downloadId);
        delay(() -> downloader.cancel(downloadId), 500);
      }
    }
  }

  @ReactMethod
  public void checkForExistingDownloads(final Promise promise) {
    WritableArray foundTasks = Arguments.createArray();

    synchronized (sharedLock) {
      Cursor cursor = null;

      try {
        DownloadManager.Query query = new DownloadManager.Query();
        cursor = downloader.downloadManager.query(query);

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

              foundTasks.pushMap(params);

              configIdToDownloadId.put(config.id, downloadId);
              configIdToPercent.put(config.id, bytesDownloaded / bytesTotal);
            } else {
              downloader.cancel(downloadId);
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

    promise.resolve(foundTasks);
  }

  @ReactMethod
  public void addListener(String eventName) {}

  @ReactMethod
  public void removeListeners(Integer count) {}

  private void onBeginDownload(String configId, WritableMap headers, long expectedBytes) {
    WritableMap params = Arguments.createMap();
    params.putString("id", configId);
    params.putMap("headers", headers);
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
      for (WritableMap report : progressReports.values()) {
        reportsArray.pushMap(report);
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

    if(file.exists()) {
      if (dest.exists()) dest.delete();
      File destDir = new File(dest.getParent());
      if (!destDir.exists()) destDir.mkdirs();
      moveFile(file, dest);
    }

    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putString("location", config.destination);
    params.putInt("bytesDownloaded", downloadStatus.getInt("bytesDownloaded"));
    params.putInt("bytesTotal", downloadStatus.getInt("bytesTotal"));
    ee.emit("downloadComplete", params);
  }

  private void onFailedDownload(RNBGDTaskConfig config, WritableMap downloadStatus) {
    Log.e(getName(), "onFailedDownload: " +
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

  private void stopTaskProgress(String configId) {
    OnProgress onProgressThread = configIdToProgressThreads.get(configId);
    if (onProgressThread != null) {
      onProgressThread.interrupt();
      configIdToPercent.remove(configId);
      configIdToProgressThreads.remove(configId);
    }
  }

  private void moveFile(File src, File dst) {
    try (FileChannel inChannel = new FileInputStream(src).getChannel(); FileChannel outChannel = new FileOutputStream(dst).getChannel()) {
      inChannel.transferTo(0, inChannel.size(), outChannel);
      src.delete();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void delay(Runnable task, long delay) {
    new Handler(Looper.getMainLooper()).postDelayed(task, delay);
  }
}

package com.eko;

import android.annotation.SuppressLint;
import android.util.Log;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.DownloadManager;
import android.app.DownloadManager.Request;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
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
// TOREMOVE
// import android.os.Environment;

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

  private final ExecutorService executorService = Executors.newSingleThreadExecutor();

  private static Map<Integer, Integer> stateMap = new HashMap<Integer, Integer>() {
    {
      put(DownloadManager.STATUS_FAILED, TASK_CANCELING);
      put(DownloadManager.STATUS_PAUSED, TASK_SUSPENDED);
      put(DownloadManager.STATUS_PENDING, TASK_RUNNING);
      put(DownloadManager.STATUS_RUNNING, TASK_RUNNING);
      put(DownloadManager.STATUS_SUCCESSFUL, TASK_COMPLETED);
    }
  };

  private Downloader downloader;

  private Map<String, Long> configIdToDownloadId = new HashMap<>();
  private Map<Long, RNBGDTaskConfig> downloadIdToConfig = new HashMap<>();
  private Map<String, Double> configIdToPercent = new HashMap<>();
  private Map<String, WritableMap> progressReports = new HashMap<>();
  private Date lastProgressReportedAt = new Date();
  private int progressInterval;
  private DeviceEventManagerModule.RCTDeviceEventEmitter ee;
  private Map<String, OnProgress> onProgressThreads = new HashMap<>();

  private static Object sharedLock = new Object();

  private static void moveFile(String sourcePath, String destinationPath) throws IOException {
    Path source = Paths.get(sourcePath);
    Path destination = Paths.get(destinationPath);

    Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
  }

  private static MMKV mmkv;

  BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
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
                executorService.submit(() -> {
                  try {
                    // MOVES FILE TO DESTINATION
                    String localUri = downloadStatus.getString("localUri");
                    File file = new File(localUri);
                    File dest = new File(config.destination);

                    // only move if source file exists (to handle case if this intent is double called)
                    if(file.exists()) {
                      // CREATE DESTINATION DIR IF NOT EXISTS
                      File destDir = new File(dest.getParent());
                      if (!destDir.exists()) {
                        destDir.mkdirs();
                      }

                      // MOVE FILE (this deletes the source file)
                      moveFile(file.getAbsolutePath(), dest.getAbsolutePath());

                      WritableMap params = Arguments.createMap();
                      params.putString("id", config.id);
                      params.putString("location", config.destination);
                      params.putInt("bytesDownloaded", downloadStatus.getInt("bytesDownloaded"));
                      params.putInt("bytesTotal", downloadStatus.getInt("bytesTotal"));

                      ee.emit("downloadComplete", params);
                    } else {
                      // we emit again, this is to handle the case where the app was closed when the download finished
                      // in this case, we only emit the download complete event so the next time the app opens, the frontend gets it
                      WritableMap params = Arguments.createMap();
                      params.putString("id", config.id);
                      params.putString("location", config.destination);
                      params.putInt("bytesDownloaded", downloadStatus.getInt("bytesDownloaded"));
                      params.putInt("bytesTotal", downloadStatus.getInt("bytesTotal"));

                      ee.emit("downloadComplete", params);
                    }
                  } catch (Exception e) {
                    e.printStackTrace();
                    Log.e(getName(), "Error moving file: " + e.getMessage());
                    // Handle error - Make sure to emit events or log on the UI thread if necessary
                  }
                });
                break;
              }
              case DownloadManager.STATUS_FAILED: {
                Log.e(getName(), "Error in enqueue: " + downloadStatus.getInt("status") + ":"
                    + downloadStatus.getInt("reason") + ":" + downloadStatus.getString("reasonText"));

                WritableMap params = Arguments.createMap();
                params.putString("id", config.id);
                params.putInt("errorCode", downloadStatus.getInt("reason"));
                params.putString("error", downloadStatus.getString("reasonText"));
                ee.emit("downloadFailed", params);
                break;
              }
            }
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
        Log.e(getName(), "downloadReceiver: onReceive. " + Log.getStackTraceString(e));
      }
    }
  };

  public RNBackgroundDownloaderModule(ReactApplicationContext reactContext) {
    super(reactContext);

    MMKV.initialize(reactContext);

    mmkv = MMKV.mmkvWithID(getName());

    loadDownloadIdToConfigMap();
    loadConfigMap();

    downloader = new Downloader(reactContext);

    IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
    compatRegisterReceiver(reactContext, downloadReceiver, filter, true);

    // iterate over downloadIdToConfig
    for (Map.Entry<Long, RNBGDTaskConfig> entry : downloadIdToConfig.entrySet()) {
      Long downloadId = entry.getKey();
      RNBGDTaskConfig config = entry.getValue();

      startReportingTasks(downloadId, config);
    }
  }

  // TAKEN FROM
  // https://github.com/facebook/react-native/pull/38256/files#diff-d5e21477eeadeb0c536d5870f487a8528f9a16ae928c397fec7b255805cc8ad3
  private void compatRegisterReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter,
      boolean exported) {
    if (Build.VERSION.SDK_INT >= 34 && context.getApplicationInfo().targetSdkVersion >= 34) {
      context.registerReceiver(
          receiver, filter, exported ? Context.RECEIVER_EXPORTED : Context.RECEIVER_NOT_EXPORTED);
    } else {
      context.registerReceiver(receiver, filter);
    }
  }

  private void stopTrackingProgress(String configId) {
    OnProgress onProgressTh = onProgressThreads.get(configId);
    if (onProgressTh != null) {
      onProgressTh.interrupt();
      onProgressThreads.remove(configId);
      configIdToPercent.remove(configId);
    }
  }

  @Override
  public void onCatalystInstanceDestroy() {
  }

  @Override
  public String getName() {
    return "RNBackgroundDownloader";
  }

  @Override
  public void initialize() {
    ee = getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
  }

  @ReactMethod
  public void addListener(String eventName) {
    // Keep: Required for RN built in Event Emitter Calls.
  }

  @ReactMethod
  public void removeListeners(Integer count) {
    // Keep: Required for RN built in Event Emitter Calls.
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

  private void removeFromMaps(long downloadId) {
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

          TypeToken<Map<Long, RNBGDTaskConfig>> mapType = new TypeToken<Map<Long, RNBGDTaskConfig>>() {
          };

          downloadIdToConfig = (Map<Long, RNBGDTaskConfig>) gson.fromJson(str, mapType);
        }
      } catch (Exception e) {
        e.printStackTrace();
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
      int _progressInterval = mmkv.decodeInt(getName() + "_progressInterval");
      if (_progressInterval > 0) {
        progressInterval = _progressInterval;
      }
    }
  }

  // JS Methods
  @ReactMethod
  public void download(ReadableMap options) {
    final String id = options.getString("id");
    String url = options.getString("url");
    String destination = options.getString("destination");
    ReadableMap headers = options.getMap("headers");
    String metadata = options.getString("metadata");
    int _progressInterval = options.getInt("progressInterval");
    if (_progressInterval > 0) {
      progressInterval = _progressInterval;
      saveConfigMap();
    }

    boolean isAllowedOverRoaming = options.getBoolean("isAllowedOverRoaming");
    boolean isAllowedOverMetered = options.getBoolean("isAllowedOverMetered");
    boolean isNotificationVisible = options.getBoolean("isNotificationVisible");

    if (id == null || url == null || destination == null) {
      Log.e(getName(), "id, url and destination must be set");
      return;
    }

    RNBGDTaskConfig config = new RNBGDTaskConfig(id, url, destination, metadata);
    final Request request = new Request(Uri.parse(url));
    request.setAllowedOverRoaming(isAllowedOverRoaming);
    request.setAllowedOverMetered(isAllowedOverMetered);
    request.setNotificationVisibility(isNotificationVisible ? Request.VISIBILITY_VISIBLE : Request.VISIBILITY_HIDDEN);
    request.setRequiresCharging(false);

    int uuid = (int) (System.currentTimeMillis() & 0xfffffff);
    // GETS THE FILE EXTENSION FROM PATH
    String extension = MimeTypeMap.getFileExtensionFromUrl(destination);

    String fileName = uuid + "." + extension;
    request.setDestinationInExternalFilesDir(this.getReactApplicationContext(), null, fileName);

    // TOREMOVE
    // request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS.toString(),
    // fileName);
    // request.setDestinationUri(Uri.parse(destination));

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

      WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
      int status = downloadStatus.getInt("status");

      if (config.reportedBegin) {
        return;
      }

      startReportingTasks(downloadId, config);
    }
  }

  private void startReportingTasks(Long downloadId, RNBGDTaskConfig config) {
    config.reportedBegin = true;
    downloadIdToConfig.put(downloadId, config);
    saveDownloadIdToConfigMap();

    // report begin & progress
    //
    // overlaped with thread to not block main thread
    new Thread(new Runnable() {
      public void run() {
        try {
          while (true) {
            WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
            int status = downloadStatus.getInt("status");

            if (status == DownloadManager.STATUS_RUNNING) {
              break;
            }
            if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_SUCCESSFUL) {
              Thread.currentThread().interrupt();
            }

            Thread.sleep(500);
          }

          // EMIT BEGIN
          OnBegin onBeginTh = new OnBegin(config, ee);
          onBeginTh.start();
          // wait for onBeginTh to finish
          onBeginTh.join();

          OnProgress onProgressTh = new OnProgress(
              config,
              downloadId,
              downloader,
              new ProgressCallback() {
                @Override
                public void onProgress(String configId, long bytesDownloaded, long bytesTotal) {
                  double prevPercent = configIdToPercent.getOrDefault(configId, 0.0);
                  double percent = (double) bytesDownloaded / bytesTotal;

                  WritableMap params = Arguments.createMap();
                  params.putString("id", configId);
                  params.putDouble("bytesDownloaded", bytesDownloaded);
                  params.putDouble("bytesTotal", bytesTotal);

                  progressReports.put(configId, params);
                  configIdToPercent.put(configId, percent);

                  Date now = new Date();
                  if (now.getTime() - lastProgressReportedAt.getTime() > progressInterval &&
                      progressReports.size() > 0) {
                    WritableArray reportsArray = Arguments.createArray();
                    for (Object report : progressReports.values()) {
                      reportsArray.pushMap((WritableMap) report);
                    }
                    ee.emit("downloadProgress", reportsArray);
                    lastProgressReportedAt = now;
                    progressReports.clear();
                  }
                }
              });
          onProgressThreads.put(config.id, onProgressTh);
          onProgressTh.start();
        } catch (Exception e) {
          e.printStackTrace();
          Log.e(getName(), "RNBD: Runnable e: " + Log.getStackTraceString(e));
        }
      }
    }).start();
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
        removeFromMaps(downloadId);
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
        removeFromMaps(downloadId);
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
      try {
        DownloadManager.Query downloadQuery = new DownloadManager.Query();
        Cursor cursor = downloader.downloadManager.query(downloadQuery);

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
              configIdToPercent.put(config.id, (double) bytesDownloaded / bytesTotal);

              // TOREMOVE
              // config.reportedBegin = true;
            } else {
              downloader.cancelDownload(downloadId);
            }
          } while (cursor.moveToNext());
        }

        cursor.close();
      } catch (Exception e) {
        e.printStackTrace();
        Log.e(getName(), "CheckForExistingDownloads e: " + Log.getStackTraceString(e));
      }
    }

    promise.resolve(foundIds);
  }
}

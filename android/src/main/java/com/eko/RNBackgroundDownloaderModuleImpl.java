package com.eko;

import android.media.MediaScannerConnection;
import android.util.Log;

import com.eko.handlers.OnBegin;
import com.eko.handlers.OnProgress;
import com.eko.handlers.OnBeginState;
import com.eko.handlers.OnProgressState;
import com.eko.utils.FileUtils;

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
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

public class RNBackgroundDownloaderModuleImpl extends ReactContextBaseJavaModule {

  public static final String NAME = "RNBackgroundDownloader";

  private static final int TASK_RUNNING = 0;
  private static final int TASK_SUSPENDED = 1;
  private static final int TASK_CANCELING = 2;
  private static final int TASK_COMPLETED = 3;

  private static final int ERR_STORAGE_FULL = 0;
  private static final int ERR_NO_INTERNET = 1;
  private static final int ERR_NO_WRITE_PERMISSION = 2;
  private static final int ERR_FILE_NOT_FOUND = 3;
  private static final int ERR_OTHERS = 100;
  private final ExecutorService cachedExecutorPool = Executors.newCachedThreadPool();
  private final ExecutorService fixedExecutorPool = Executors.newFixedThreadPool(1);
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
  private final Downloader downloader;
  private BroadcastReceiver downloadReceiver;
  private static final Object sharedLock = new Object();
  private Map<Long, RNBGDTaskConfig> downloadIdToConfig = new HashMap<>();
  private final Map<String, Long> configIdToDownloadId = new HashMap<>();
  private final Map<String, Double> configIdToPercent = new HashMap<>();
  private final Map<String, Long> configIdToLastBytes = new HashMap<>();
  private final Map<String, Future<OnProgressState>> configIdToProgressFuture = new HashMap<>();
  private final Map<String, WritableMap> progressReports = new HashMap<>();
  private int progressInterval = 0;
  private long progressMinBytes = 1024 * 1024; // Default 1MB
  private Date lastProgressReportedAt = new Date();
  private DeviceEventManagerModule.RCTDeviceEventEmitter ee;

  public RNBackgroundDownloaderModuleImpl(ReactApplicationContext reactContext) {
    super(reactContext);
    
    // Initialize MMKV with error handling for Android 12 compatibility
    try {
      MMKV.initialize(reactContext);
      mmkv = MMKV.mmkvWithID(getName());
      Log.d(getName(), "MMKV initialized successfully");
    } catch (UnsatisfiedLinkError e) {
      Log.e(getName(), "Failed to initialize MMKV (libmmkv.so not found): " + e.getMessage());
      Log.w(getName(), "Continuing without persistent storage. Downloads will not persist across app restarts.");
      mmkv = null;
    } catch (Exception e) {
      Log.e(getName(), "Failed to initialize MMKV: " + e.getMessage());
      Log.w(getName(), "Continuing without persistent storage. Downloads will not persist across app restarts.");
      mmkv = null;
    }

    loadDownloadIdToConfigMap();
    loadConfigMap();

    downloader = new Downloader(reactContext);
  }

  @NonNull
  @Override
  public String getName() {
    return NAME;
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

    for (Map.Entry<Long, RNBGDTaskConfig> entry : downloadIdToConfig.entrySet()) {
      Long downloadId = entry.getKey();
      RNBGDTaskConfig config = entry.getValue();
      resumeTasks(downloadId, config);
    }
  }

  @Override
  public void invalidate() {
    unregisterDownloadReceiver();
  }

  private void registerDownloadReceiver() {
    Context context = getReactApplicationContext();
    IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

    downloadReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);

        if (config != null) {
          WritableMap downloadStatus = downloader.checkDownloadStatus(downloadId);
          int status = downloadStatus.getInt("status");
          String localUri = downloadStatus.getString("localUri");

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

            if (localUri != null) {
              // Prevent memory leaks from MediaScanner.
              // Download successful, clean task after media scanning.
              String[] paths = new String[]{localUri};
              MediaScannerConnection.scanFile(context, paths, null, (path, uri) -> stopTask(config.id));
            } else {
              // Download failed, clean task.
              stopTask(config.id);
            }
          }
        }
      }
    };

    compatRegisterReceiver(context, downloadReceiver, filter, true);
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

  private void unregisterDownloadReceiver() {
    if (downloadReceiver != null) {
      getReactApplicationContext().unregisterReceiver(downloadReceiver);
      downloadReceiver = null;
    }
  }

  private void resumeTasks(Long downloadId, RNBGDTaskConfig config) {
    new Thread(() -> {
      try {
        long bytesDownloaded = 0;
        long bytesTotal = 0;

        if (!config.reportedBegin) {
          OnBegin onBeginCallable = new OnBegin(config, this::onBeginDownload);
          Future<OnBeginState> onBeginFuture = cachedExecutorPool.submit(onBeginCallable);
          OnBeginState onBeginState = onBeginFuture.get();
          bytesTotal = onBeginState.expectedBytes;

          config.reportedBegin = true;
          downloadIdToConfig.put(downloadId, config);
          saveDownloadIdToConfigMap();
        }

        OnProgress onProgressCallable = new OnProgress(config, downloader, downloadId, bytesDownloaded, bytesTotal, this::onProgressDownload);
        Future<OnProgressState> onProgressFuture = cachedExecutorPool.submit(onProgressCallable);
        configIdToProgressFuture.put(config.id, onProgressFuture);
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
        configIdToLastBytes.remove(config.id);
        downloadIdToConfig.remove(downloadId);
        saveDownloadIdToConfigMap();
      }
    }
  }

  /**
   * Resolve redirects for a URL up to maxRedirects limit
   * @param originalUrl The original URL to follow
   * @param maxRedirects Maximum number of redirects to follow (0 means no redirect resolution)
   * @param headers Headers to include in redirect resolution requests
   * @return The final resolved URL, or original URL if maxRedirects is 0 or resolution fails
   */
  private String resolveRedirects(String originalUrl, int maxRedirects, ReadableMap headers) {
    if (maxRedirects <= 0) {
      return originalUrl;
    }

    try {
      String currentUrl = originalUrl;
      int redirectCount = 0;
      
      while (redirectCount < maxRedirects) {
        java.net.URL url = new java.net.URL(currentUrl);
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
        
        // Add headers to the redirect resolution request
        if (headers != null) {
          ReadableMapKeySetIterator iterator = headers.keySetIterator();
          while (iterator.hasNextKey()) {
            String headerKey = iterator.nextKey();
            connection.setRequestProperty(headerKey, headers.getString(headerKey));
          }
        }
        
        // Add default headers for consistency with DownloadManager
        connection.setRequestProperty("Connection", "keep-alive");
        connection.setRequestProperty("Keep-Alive", "timeout=600, max=1000");
        if (!hasUserAgentHeader(headers)) {
          connection.setRequestProperty("User-Agent", "ReactNative-BackgroundDownloader/3.2.6");
        }
        
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("HEAD"); // Use HEAD to avoid downloading content
        connection.setConnectTimeout(10000); // 10 second timeout
        connection.setReadTimeout(10000);
        
        int responseCode = connection.getResponseCode();
        
        if (responseCode >= 300 && responseCode < 400) {
          // This is a redirect
          String location = connection.getHeaderField("Location");
          if (location == null) {
            Log.w(getName(), "Redirect response without Location header at: " + currentUrl);
            break;
          }
          
          // Handle relative URLs
          if (location.startsWith("/")) {
            java.net.URL baseUrl = new java.net.URL(currentUrl);
            location = baseUrl.getProtocol() + "://" + baseUrl.getHost() + location;
          } else if (!location.startsWith("http")) {
            java.net.URL baseUrl = new java.net.URL(currentUrl);
            location = baseUrl.getProtocol() + "://" + baseUrl.getHost() + "/" + location;
          }
          
          Log.d(getName(), "Redirect " + (redirectCount + 1) + "/" + maxRedirects + ": " + currentUrl + " -> " + location);
          currentUrl = location;
          redirectCount++;
        } else {
          // Not a redirect, we've found the final URL
          break;
        }
        
        connection.disconnect();
      }
      
      if (redirectCount >= maxRedirects) {
        Log.w(getName(), "Reached maximum redirects (" + maxRedirects + ") for URL: " + originalUrl + 
              ". Final URL: " + currentUrl);
      } else {
        Log.d(getName(), "Resolved URL after " + redirectCount + " redirects: " + currentUrl);
      }
      
      return currentUrl;
      
    } catch (Exception e) {
      Log.e(getName(), "Failed to resolve redirects for URL: " + originalUrl + ". Error: " + e.getMessage());
      // Return original URL if redirect resolution fails
      return originalUrl;
    }
  }

  @ReactMethod
  @SuppressWarnings("unused")
  public void download(ReadableMap options) {
    final String id = options.getString("id");
    String url = options.getString("url");
    String destination = options.getString("destination");
    ReadableMap headers = options.getMap("headers");
    String metadata = options.getString("metadata");
    String notificationTitle = options.getString("notificationTitle");
    int progressIntervalScope = options.getInt("progressInterval");
    if (progressIntervalScope > 0) {
      progressInterval = progressIntervalScope;
      saveConfigMap();
    }

    double progressMinBytesScope = options.getDouble("progressMinBytes");
    if (progressMinBytesScope > 0) {
      progressMinBytes = (long) progressMinBytesScope;
      saveConfigMap();
    }

    boolean isAllowedOverRoaming = options.getBoolean("isAllowedOverRoaming");
    boolean isAllowedOverMetered = options.getBoolean("isAllowedOverMetered");
    boolean isNotificationVisible = options.getBoolean("isNotificationVisible");

    // Get maxRedirects parameter
    int maxRedirects = 0;
    if (options.hasKey("maxRedirects")) {
      maxRedirects = options.getInt("maxRedirects");
    }

    if (id == null || url == null || destination == null) {
      Log.e(getName(),"download: id, url and destination must be set.");
      return;
    }

    // Resolve redirects if maxRedirects is specified
    if (maxRedirects > 0) {
      Log.d(getName(), "Resolving redirects for URL: " + url + " (maxRedirects: " + maxRedirects + ")");
      url = resolveRedirects(url, maxRedirects, headers);
      Log.d(getName(), "Final resolved URL: " + url);
    }

    final Request request = new Request(Uri.parse(url));
    request.setAllowedOverRoaming(isAllowedOverRoaming);
    request.setAllowedOverMetered(isAllowedOverMetered);
    request.setNotificationVisibility(isNotificationVisible ? Request.VISIBILITY_VISIBLE : Request.VISIBILITY_HIDDEN);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      request.setRequiresCharging(false);
    }

    if (notificationTitle != null) {
      request.setTitle(notificationTitle);
    }

    // Add default headers to improve connection handling for slow-responding URLs
    // These headers encourage longer connections and help prevent premature
    // timeouts
    request.addRequestHeader("Connection", "keep-alive");
    request.addRequestHeader("Keep-Alive", "timeout=600, max=1000");

    // Add a proper User-Agent to improve server compatibility
    if (!hasUserAgentHeader(headers)) {
      request.addRequestHeader("User-Agent", "ReactNative-BackgroundDownloader/3.2.6");
    }

    if (headers != null) {
      ReadableMapKeySetIterator iterator = headers.keySetIterator();
      while (iterator.hasNextKey()) {
        String headerKey = iterator.nextKey();
        request.addRequestHeader(headerKey, headers.getString(headerKey));
      }
    }

    int uuid = (int) (System.currentTimeMillis() & 0xfffffff);
    String extension = MimeTypeMap.getFileExtensionFromUrl(destination);
    String filename = uuid + "." + extension;
    request.setDestinationInExternalFilesDir(this.getReactApplicationContext(), null, filename);

    long downloadId = downloader.download(request);
    RNBGDTaskConfig config = new RNBGDTaskConfig(id, url, destination, metadata, notificationTitle);

    synchronized (sharedLock) {
      configIdToDownloadId.put(id, downloadId);
      configIdToPercent.put(id, 0.0);
      downloadIdToConfig.put(downloadId, config);
      saveDownloadIdToConfigMap();
      resumeTasks(downloadId, config);
    }
  }

  // Pause functionality is not supported by Android DownloadManager.
  // This method will throw an UnsupportedOperationException to clearly indicate
  // that pause is not available on Android platform.
  @ReactMethod
  @SuppressWarnings("unused")
  public void pauseTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        try {
          downloader.pause(downloadId);
        } catch (UnsupportedOperationException e) {
          Log.w("RNBackgroundDownloader", "pauseTask: " + e.getMessage());
          // Note: We don't rethrow the exception to avoid crashing the JS thread.
          // The limitation is already documented and expected.
        }
      }
    }
  }

  // Resume functionality is not supported by Android DownloadManager.
  // This method will throw an UnsupportedOperationException to clearly indicate
  // that resume is not available on Android platform.
  @ReactMethod
  @SuppressWarnings("unused")
  public void resumeTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        try {
          downloader.resume(downloadId);
        } catch (UnsupportedOperationException e) {
          Log.w("RNBackgroundDownloader", "resumeTask: " + e.getMessage());
          // Note: We don't rethrow the exception to avoid crashing the JS thread.
          // The limitation is already documented and expected.
        }
      }
    }
  }

  @ReactMethod
  @SuppressWarnings("unused")
  public void stopTask(String configId) {
    synchronized (sharedLock) {
      Long downloadId = configIdToDownloadId.get(configId);
      if (downloadId != null) {
        stopTaskProgress(configId);
        removeTaskFromMap(downloadId);
        downloader.cancel(downloadId);
      }
    }
  }

  @ReactMethod
  @SuppressWarnings("unused")
  public void completeHandler(String configId) {
    // Firebase Performance compatibility: Add defensive programming to prevent crashes
    // when Firebase Performance SDK is installed and uses bytecode instrumentation

    Log.d(getName(), "completeHandler called with configId: " + configId);

    // Defensive programming: Validate parameters
    if (configId == null || configId.isEmpty()) {
      Log.w(getName(), "completeHandler: Invalid configId provided");
      return;
    }

    try {
      // Currently this method doesn't have any implementation on Android
      // as completion handlers are handled differently than iOS.
      // This defensive structure ensures Firebase Performance compatibility.
      Log.d(getName(), "completeHandler executed successfully for configId: " + configId);

    } catch (Exception e) {
      // Catch any potential exceptions that might be thrown due to Firebase Performance
      // bytecode instrumentation interfering with method dispatch
      Log.e(getName(), "completeHandler: Exception occurred: " + Log.getStackTraceString(e));
    }
  }

  @ReactMethod
  @SuppressWarnings("unused")
  public void checkForExistingDownloads(final Promise promise) {
    WritableArray foundTasks = Arguments.createArray();

    synchronized (sharedLock) {
      DownloadManager.Query query = new DownloadManager.Query();
      try (Cursor cursor = downloader.downloadManager.query(query)) {
        if (cursor.moveToFirst()) {
          do {
            WritableMap downloadStatus = downloader.getDownloadStatus(cursor);
            Long downloadId = Long.parseLong(downloadStatus.getString("downloadId"));

            if (downloadIdToConfig.containsKey(downloadId)) {
              RNBGDTaskConfig config = downloadIdToConfig.get(downloadId);

              if (config != null) {
                int status = downloadStatus.getInt("status");
                // Handle completed downloads that weren't processed
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                  String localUri = downloadStatus.getString("localUri");
                  if (localUri != null) {
                    try {
                      Future<Boolean> future = setFileChangesBeforeCompletion(localUri, config.destination);
                      future.get();
                    } catch (Exception e) {
                      Log.e(getName(), "Error moving completed download file: " + e.getMessage());
                      // Continue with normal processing even if file move fails
                    }
                  }
                }

                WritableMap params = Arguments.createMap();

                params.putString("id", config.id);
                params.putString("metadata", config.metadata);
                Integer statusMapping = stateMap.get(status);
                int state = statusMapping != null ? statusMapping : 0;
                params.putInt("state", state);

                double bytesDownloaded = downloadStatus.getDouble("bytesDownloaded");
                params.putDouble("bytesDownloaded", bytesDownloaded);
                double bytesTotal = downloadStatus.getDouble("bytesTotal");
                params.putDouble("bytesTotal", bytesTotal);
                double percent = bytesTotal > 0 ? bytesDownloaded / bytesTotal : 0;

                foundTasks.pushMap(params);
                configIdToDownloadId.put(config.id, downloadId);
                configIdToPercent.put(config.id, percent);
              }
            } else {
              downloader.cancel(downloadId);
            }
          } while (cursor.moveToNext());
        }
      } catch (Exception e) {
        Log.e(getName(), "checkForExistingDownloads: " + Log.getStackTraceString(e));
      }
    }

    promise.resolve(foundTasks);
  }

  @ReactMethod
  @SuppressWarnings("unused")
  public void addListener(String eventName) {}

  @ReactMethod
  @SuppressWarnings("unused")
  public void removeListeners(Integer count) {}

  private void onBeginDownload(String configId, WritableMap headers, long expectedBytes) {
    WritableMap params = Arguments.createMap();
    params.putString("id", configId);
    params.putMap("headers", headers);
    params.putDouble("expectedBytes", expectedBytes);
    ee.emit("downloadBegin", params);
  }

  private void onProgressDownload(String configId, long bytesDownloaded, long bytesTotal) {
    Double existPercent = configIdToPercent.get(configId);
    Long existLastBytes = configIdToLastBytes.get(configId);
    double prevPercent = existPercent != null ? existPercent : 0.0;
    long prevBytes = existLastBytes != null ? existLastBytes : 0;
    double percent = bytesTotal > 0.0 ?  ((double) bytesDownloaded / bytesTotal) : 0.0;

    // Check if we should report progress based on percentage OR bytes threshold
    boolean percentThresholdMet = percent - prevPercent > 0.01;
    boolean bytesThresholdMet = bytesDownloaded - prevBytes >= progressMinBytes;

    if (percentThresholdMet || bytesThresholdMet) {
      WritableMap params = Arguments.createMap();
      params.putString("id", configId);
      params.putDouble("bytesDownloaded", bytesDownloaded);
      params.putDouble("bytesTotal", bytesTotal);
      progressReports.put(configId, params);
      configIdToPercent.put(configId, percent);
      configIdToLastBytes.put(configId, bytesDownloaded);
    }

    Date now = new Date();
    boolean isReportTimeDifference = now.getTime() - lastProgressReportedAt.getTime() > progressInterval;
    boolean isReportNotEmpty =!progressReports.isEmpty();
    if (isReportTimeDifference && isReportNotEmpty) {
      // Extra steps to avoid map always consumed errors.
      List<WritableMap> reportsList = new ArrayList<>(progressReports.values());
      WritableArray reportsArray = Arguments.createArray();
      for (WritableMap report : reportsList) {
        if (report != null) {
          reportsArray.pushMap(report.copy());
        }
      }
      ee.emit("downloadProgress", reportsArray);
      lastProgressReportedAt = now;
      progressReports.clear();
    }
  }

  private void onSuccessfulDownload(RNBGDTaskConfig config, WritableMap downloadStatus) {
    String localUri = downloadStatus.getString("localUri");

    // TODO: We need to move it to a more suitable location.
    //       Maybe somewhere in downloadReceiver?
    // Feedback if any error occurs after downloading the file.
    try {
      Future<Boolean> future = setFileChangesBeforeCompletion(localUri, config.destination);
      future.get();
    } catch (Exception e) {
      WritableMap newDownloadStatus = Arguments.createMap();
      newDownloadStatus.putString("downloadId", downloadStatus.getString("downloadId"));
      newDownloadStatus.putInt("status", DownloadManager.STATUS_FAILED);
      newDownloadStatus.putInt("reason", DownloadManager.ERROR_UNKNOWN);
      newDownloadStatus.putString("reasonText", e.getMessage());
      onFailedDownload(config, newDownloadStatus);
      return;
    }

    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putString("location", config.destination);
    params.putDouble("bytesDownloaded", downloadStatus.getDouble("bytesDownloaded"));
    params.putDouble("bytesTotal", downloadStatus.getDouble("bytesTotal"));
    ee.emit("downloadComplete", params);
  }

  private void onFailedDownload(RNBGDTaskConfig config, WritableMap downloadStatus) {
    Log.e(getName(), "onFailedDownload: " +
            downloadStatus.getInt("status") + ":" +
            downloadStatus.getInt("reason") + ":" +
            downloadStatus.getString("reasonText")
    );

    int reason = downloadStatus.getInt("reason");
    String reasonText = downloadStatus.getString("reasonText");

    // Enhanced handling for ERROR_CANNOT_RESUME (1008)
    if (reason == DownloadManager.ERROR_CANNOT_RESUME) {
      Log.w(getName(), "ERROR_CANNOT_RESUME detected for download: " + config.id +
            ". This is a known Android DownloadManager issue with larger files. " +
            "Consider restarting the download or using smaller file segments.");

      // Clean up the failed download entry
      removeTaskFromMap(Long.parseLong(downloadStatus.getString("downloadId")));

      // Provide more helpful error message
      reasonText = "ERROR_CANNOT_RESUME - Unable to resume download. This may occur with large files due to Android DownloadManager limitations. Try restarting the download.";
    }

    WritableMap params = Arguments.createMap();
    params.putString("id", config.id);
    params.putInt("errorCode", reason);
    params.putString("error", reasonText);
    ee.emit("downloadFailed", params);
  }

  private void saveDownloadIdToConfigMap() {
    synchronized (sharedLock) {
      if (mmkv == null) {
        Log.d(getName(), "MMKV not available, skipping download config persistence");
        return;
      }
      try {
        Gson gson = new Gson();
        String str = gson.toJson(downloadIdToConfig);
        mmkv.encode(getName() + "_downloadIdToConfig", str);
      } catch (Exception e) {
        Log.e(getName(), "Failed to save download config to MMKV: " + e.getMessage());
      }
    }
  }

  private void loadDownloadIdToConfigMap() {
    synchronized (sharedLock) {
      if (mmkv == null) {
        Log.d(getName(), "MMKV not available, starting with empty download config");
        downloadIdToConfig = new HashMap<>();
        return;
      }
      try {
        String str = mmkv.decodeString(getName() + "_downloadIdToConfig");
        if (str != null) {
          Gson gson = new Gson();

          TypeToken<Map<Long, RNBGDTaskConfig>> mapType = new TypeToken<Map<Long, RNBGDTaskConfig>>() {
          };

          downloadIdToConfig = (Map<Long, RNBGDTaskConfig>) gson.fromJson(str, mapType);
        }
      } catch (Exception e) {
        Log.e(getName(), "loadDownloadIdToConfigMap: " + Log.getStackTraceString(e));
        downloadIdToConfig = new HashMap<>();
      }
    }
  }

  private void saveConfigMap() {
    synchronized (sharedLock) {
      if (mmkv == null) {
        Log.d(getName(), "MMKV not available, skipping config persistence");
        return;
      }
      try {
        mmkv.encode(getName() + "_progressInterval", progressInterval);
        mmkv.encode(getName() + "_progressMinBytes", progressMinBytes);
      } catch (Exception e) {
        Log.e(getName(), "Failed to save config to MMKV: " + e.getMessage());
      }
    }
  }

  private void loadConfigMap() {
    synchronized (sharedLock) {
      if (mmkv == null) {
        Log.d(getName(), "MMKV not available, using default config values");
        return;
      }
      try {
        int progressIntervalScope = mmkv.decodeInt(getName() + "_progressInterval");
        if (progressIntervalScope > 0) {
          progressInterval = progressIntervalScope;
        }
        long progressMinBytesScope = mmkv.decodeLong(getName() + "_progressMinBytes");
        if (progressMinBytesScope > 0) {
          progressMinBytes = progressMinBytesScope;
        }
      } catch (Exception e) {
        Log.e(getName(), "Failed to load config from MMKV: " + e.getMessage());
      }
    }
  }

  private void stopTaskProgress(String configId) {
    Future<OnProgressState> onProgressFuture = configIdToProgressFuture.get(configId);
    if (onProgressFuture != null) {
      onProgressFuture.cancel(true);
      configIdToPercent.remove(configId);
      configIdToLastBytes.remove(configId);
      configIdToProgressFuture.remove(configId);
    }
  }

  private Future<Boolean> setFileChangesBeforeCompletion(String targetSrc, String destinationSrc) {
    return fixedExecutorPool.submit(() -> {
      File file = new File(targetSrc);
      File destination = new File(destinationSrc);
      File destinationParent = null;
      try {
        if(file.exists()) {
          FileUtils.rm(destination);
          destinationParent = FileUtils.mkdirParent(destination);
          FileUtils.mv(file, destination);
        }
      } catch (IOException e) {
        FileUtils.rm(file);
        FileUtils.rm(destination);
        FileUtils.rm(destinationParent);
        throw new Exception(e);
      }

      return true;
    });
  }

  /**
   * Check if the provided headers already contain a User-Agent header
   * (case-insensitive)
   */
  private boolean hasUserAgentHeader(@Nullable ReadableMap headers) {
    if (headers == null) {
      return false;
    }

    ReadableMapKeySetIterator iterator = headers.keySetIterator();
    while (iterator.hasNextKey()) {
      String headerKey = iterator.nextKey();
      if (headerKey != null && headerKey.toLowerCase().equals("user-agent")) {
        return true;
      }
    }

    return false;
  }
}

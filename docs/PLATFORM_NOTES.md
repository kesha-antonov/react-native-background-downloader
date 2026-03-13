# Platform Notes

Platform-specific information, requirements, and troubleshooting for `@kesha-antonov/react-native-background-downloader`.

## Table of Contents

- [iOS Notes](#ios-notes)
- [Android Notes](#android-notes)
- [Google Play Console Declaration](#google-play-console-declaration)
- [Proguard Rules](#proguard-rules)

---

## iOS Notes

### Background Session Handling

iOS uses `NSURLSession` with background configuration. Downloads continue even after your app is terminated **by the OS** (e.g. due to memory pressure). When your app is relaunched, you can reconnect to these downloads using `getExistingDownloadTasks()`.

### Force-Kill Limitation

> **Important:** If the user explicitly **force-kills** the app via the iOS App Switcher (swipe up to dismiss), iOS immediately cancels all `NSURLSession` background tasks. This is an [intentional iOS system behaviour](https://developer.apple.com/documentation/foundation/url_loading_system/downloading_files_in_the_background) and cannot be overridden by any third-party library.
>
> In short:
> - App sent to background (home button / swipe home) → downloads **continue** ✅
> - App terminated by the OS (memory pressure, system reboot) → downloads **continue** ✅
> - App **force-killed** by the user via App Switcher → downloads **stop** ❌
>
> If continuous background downloading is critical regardless of force-kill, consider scheduling a silent push notification to wake the app after it is relaunched, then reconnect to the task with `getExistingDownloadTasks()` to capture any progress that occurred before the kill.

### AppDelegate Setup Required

You must implement `handleEventsForBackgroundURLSession` in your AppDelegate for background downloads to work properly. See the [Installation guide](../README.md#ios---extra-mandatory-step) for details.

### Max Parallel Downloads

You can configure the maximum number of simultaneous connections per host using `setConfig({ maxParallelDownloads: 8 })`. Default is 4.

### Updating Headers on Paused Downloads

Use `task.setDownloadParams()` to update headers while a task is paused. This is useful when auth tokens expire while a download is paused - you can refresh the token and update headers before resuming without restarting the download from scratch.

See the [Updating headers on paused downloads](../README.md#-usage) section in the README for usage examples.

---

## Android Notes

### Pause/Resume Implementation

Pause/resume on Android uses HTTP Range headers. The server must support range requests for resume to work correctly. If the server doesn't support it, the download will restart from the beginning.

### Android 16+ Support

Downloads are automatically marked as user-initiated data transfers on Android 16+ (API 36) to prevent being killed due to thermal throttling.

### Foreground Service

The library uses a Foreground Service for pause/resume functionality. This requires:
- `FOREGROUND_SERVICE` permission (automatically added)
- Notification displayed during downloads

### MMKV Dependency

Android uses MMKV for persistent state storage. This is required for:
- Tracking download progress across app restarts
- Storing download metadata
- Managing pause/resume state

If you're using [react-native-mmkv](https://github.com/mrousavy/react-native-mmkv) in your project, you don't need to add MMKV separately.

### Handling Redirects

Android's DownloadManager has a built-in redirect limit. If you're downloading from URLs with many redirects (common with podcast URLs, tracking services, CDNs), use the `maxRedirects` option:

```javascript
const task = createDownloadTask({
  id: 'file123',
  url: 'https://example.com/file.mp3',
  destination: `${directories.documents}/file.mp3`,
  maxRedirects: 10, // Follow up to 10 redirects
})
```

### Slow-Responding URLs

The library automatically includes connection timeout improvements for slow-responding URLs. By default, the following headers are added to all download requests on Android:

- `Connection: keep-alive` - Keeps the connection open for better handling
- `Keep-Alive: timeout=600, max=1000` - Sets a 10-minute keep-alive timeout
- `User-Agent: ReactNative-BackgroundDownloader/3.2.6` - Proper user agent for better server compatibility

You can override these headers by providing your own in the `headers` option.

---

## Google Play Console Declaration

The library uses Foreground Service permissions. When publishing to Google Play:

1. Go to **App content** → **Foreground Service** in the Play Console
2. Select **Yes** for Foreground Service usage
3. Choose **Data sync** as the type
4. Select **Network processing** as the task

---

## Proguard Rules

If you encounter `TypeToken` errors in release builds, add the following to your `android/app/proguard-rules.pro`:

```proguard
-keep class com.eko.RNBGDTaskConfig { *; }
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.tencent.mmkv.** { *; }
```

---

## Troubleshooting

### Download stuck in "pending" state (Android)

This can happen with slow-responding servers. Try:
- Enable debug logs to see what's happening: `setConfig({ isLogsEnabled: true })`
- Check if the server supports the download URL
- Increase timeout by setting custom headers

### Duplicate class errors with react-native-mmkv (Android)

If you're using `react-native-mmkv`, you don't need to add the MMKV dependency manually - it's already included. The library uses `compileOnly` to avoid conflicts.

### EXC_BAD_ACCESS crash on iOS with react-native-mmkv

This was fixed in v4.4.0. Update to the latest version. If you're not using `react-native-mmkv`, add `pod 'MMKV', '>= 1.0.0'` to your Podfile.

### Downloads not resuming after app restart

Make sure to call `getExistingDownloadTasks()` at app startup and re-attach your callbacks. The task IDs you provide are used to identify downloads across restarts.

### Google Play Console asking about Foreground Service

See the [Google Play Console Declaration](#google-play-console-declaration) section above.

### TypeToken errors in release builds (Android)

Add the Proguard rules mentioned in the [Proguard Rules](#proguard-rules) section above.

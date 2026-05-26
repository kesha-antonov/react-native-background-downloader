# Platform Notes

Platform-specific information, requirements, and troubleshooting for `@kesha-antonov/react-native-background-downloader`.

## Table of Contents

- [iOS Notes](#ios-notes)
- [Android Notes](#android-notes)
- [Image Compression (compressValue)](#image-compression-compressvalue)
- [Notification Image (notificationImageUrl)](#notification-image-notificationimageurl)
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

### SIGSEGV crash on iOS when calling setConfig({ allowsCellularAccess }) with New Architecture

**Affected versions:** 4.5.x, RN 0.83+, New Architecture (TurboModules) enabled  
**Root cause:** `setAllowsCellularAccess:` is a void method that can throw `NSException` (e.g. when recreating the `URLSession`). The TurboModule bridge catches it and tries to convert it to a JS error via `convertNSExceptionToJSError`, which accesses Hermes VM from the library's background dispatch queue (`com.eko.backgrounddownloader`). Hermes is not thread-safe → SIGSEGV crash.

**Fix:** Updated in this fork — both `_setAllowsCellularAccessInternal:` and `_setMaxParallelDownloadsInternal:` are now wrapped in `@try/@catch`. Exceptions are caught and logged without propagating to the TurboModule bridge.

**Workaround (older versions):** Instead of `setConfig({ allowsCellularAccess: true })`, pass `isAllowedOverMetered: true` per-task in `createDownloadTask()` options — this does not trigger a separate native void method call.

---

### EXC_BAD_ACCESS crash on iOS with react-native-mmkv

This was fixed in v4.4.0. Update to the latest version. If you're not using `react-native-mmkv`, add `pod 'MMKV', '>= 1.0.0'` to your Podfile.

### getExistingDownloadTasks() returns [] on Android 11 with internal destination paths

**Affected versions:** 4.5.x  
**Root cause:** When `destination` points to an internal app path (e.g. from `RNFS.DocumentDirectoryPath`), Android's `DownloadManager` may reject it and the library silently falls back to `ResumableDownloader`. Prior to the fix in this fork, active `ResumableDownloader` tasks were invisible to `getExistingDownloadTasks()`.

**Fix:** Updated in this fork — active `ResumableDownloader` tasks are now included in Phase 4 of `getExistingDownloadTasks()`. No workaround needed after updating.

**Workaround (older versions):** Pause the download before force-stopping the app, or use `directories.documents` which maps to external app storage on Android (compatible with `DownloadManager`).

---

### getExistingDownloadTasks() returns [] after force-stop for active (non-paused) downloads

**Affected versions:** 4.5.x (Android 13+)  
**Root cause:** After force-stop, Android's `DownloadManager` may reassign new download IDs. The persisted `downloadId → config` mapping in MMKV becomes stale.

**Fix:** Updated in this fork — Phase 1 now falls back to matching by destination file path when the download ID is not found, restoring the mapping automatically.

**Limitation:** This fix only works for `DownloadManager`-backed downloads. If `ResumableDownloader` was used (Android 16+, or internal-path fallback), active state is in-memory only and lost on force-stop. Paused downloads survive because state is persisted to disk on pause.

---

### Downloads not resuming after app restart

Make sure to call `getExistingDownloadTasks()` at app startup and re-attach your callbacks. The task IDs you provide are used to identify downloads across restarts.

### Google Play Console asking about Foreground Service

See the [Google Play Console Declaration](#google-play-console-declaration) section above.

### TypeToken errors in release builds (Android)

Add the Proguard rules mentioned in the [Proguard Rules](#proguard-rules) section above.

---

## Image Compression (`compressValue`)

Pass `compressValue` (0–1) to `createDownloadTask` to re-encode the downloaded file as JPEG after the download completes.

```javascript
createDownloadTask({
  id: 'my-image',
  url: 'https://example.com/photo.jpg',
  destination: `${directories.documents}/photo.jpg`,
  compressValue: 0.7,  // 70% JPEG quality
})
```

**Behavior:**
- Runs after the file is fully written to disk, on a background thread.
- Safe when the app is minimized — on Android this is done inside the download service/job; on iOS inside the `URLSession` completion handler.
- **iOS:** Always re-encodes as JPEG via `UIImageJPEGRepresentation`, regardless of original format (PNG/WebP → JPEG).
- **Android:** Uses `Bitmap.compress(JPEG)` — same JPEG-only output.
- Non-image files (e.g. ZIP, MP4) are skipped silently — `BitmapFactory.decodeFile` / `UIImage` will return `nil`/`null` and the file is left untouched.
- `compressValue = 1.0` is treated as "no compression" (pass-through). Use values in the range `0.01–0.99`.

**Note:** The file at `destination` is overwritten in-place. Keep a copy elsewhere if the original lossless file is needed.

---

## Notification Image (`notificationImageUrl`)

**Android only.** Pass `notificationImageUrl` (absolute local file path) to show a large icon in the download notification.

```javascript
createDownloadTask({
  id: 'my-video',
  url: 'https://example.com/video.mp4',
  destination: `${directories.documents}/video.mp4`,
  notificationImageUrl: '/data/user/0/com.myapp/cache/thumbnail.jpg',
})
```

**Requirements:**
- `showNotificationsEnabled: true` must be set via `setConfig` — the image has no effect when notifications are disabled.
- Android 14+ (UIDT jobs) only. On Android 13 and below (foreground service), the parameter is parsed but not used.
- The path must be readable by the app process at the time the download notification is created. Images in the app's `cache` or `documents` directory work reliably.
- The image is loaded once when the download starts and cached in the `JobState` for the lifetime of the download. If the file is deleted before the download finishes, later notification updates will simply not show the image.

**Sizing:** The image is scaled down to ~256px before being passed to `setLargeIcon()`. Very large bitmaps will be automatically sub-sampled to avoid OOM errors in the system process.

# Platform Notes

Platform-specific information, requirements, and troubleshooting for `@kesha-antonov/react-native-background-downloader`.

## Table of Contents

- [iOS Notes](#ios-notes)
- [Android Notes](#android-notes)
- [Google Play Console Declaration](#google-play-console-declaration)
- [Proguard Rules](#proguard-rules)
- [Troubleshooting](#troubleshooting)

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

### Which mechanism runs a download

Android has three of them, and the library picks per download:

| Android version | Mechanism | Notes |
| --- | --- | --- |
| 16+ (API 36) | UIDT job | `DownloadManager` rejects app-specific external paths there |
| 14-15 (API 34-35) | UIDT job, falling back to `DownloadManager` | The job can only be scheduled while the app is visible, so a download started from the background runs through `DownloadManager` instead |
| 13 and below | `DownloadManager`, falling back to the foreground service | The service also takes over when `DownloadManager` refuses the destination path |

A UIDT job is not subject to App Standby quotas, keeps running when the app is backgrounded, and is the only path where the library manages the download's notification - so notification grouping, the completion notification, the Cancel action and per-download titles apply to downloads that run as jobs. A download that resumes after a pause always resumes as a UIDT job on Android 14+, whichever mechanism started it.

### Max Parallel Downloads

`setConfig({ maxParallelDownloads: N })` (default 4) caps how many downloads the library's own downloader transfers at once - the mechanism used on Android 16+, and whenever `DownloadManager` or a UIDT job can't take the download. Downloads over the limit wait for a slot instead of taking a thread and a socket each, and the next one starts as soon as a running transfer completes, fails, is paused or is stopped. Downloads that run through `DownloadManager` or the JobScheduler are queued by those schedulers instead, so the setting does not apply to them.

### Many concurrent downloads (Android 14+)

Each download on Android 14+ is scheduled as its own `JobScheduler` job, and Android allows an app at most 150 pending jobs across the whole process - a quota shared with WorkManager and any other library that schedules jobs. When a batch of downloads would exhaust it, the library keeps headroom for the rest of the app and routes the remaining downloads through the foreground service instead, which has no such limit. Those downloads still run; they just don't get a user-initiated data transfer job's scheduling privileges. If you regularly start hundreds of downloads at once, queue them in your app rather than starting them all at the same time.

### Foreground Service

The library uses a Foreground Service for pause/resume functionality. This requires:
- `FOREGROUND_SERVICE` permission (automatically added)
- Notification displayed during downloads

### Download Notifications (Android 14+)

On Android 14+ (API 34) downloads run as user-initiated data transfers (UIDT) and the library can manage a richer notification flow. All of it requires `showNotificationsEnabled: true`, and the two notification extras are opt-in on top of that so upgrading does not change what your users see:

- **Cancel action** (`showCancelAction`, default `false`) - the in-progress notification shows a **Cancel** button. Tapping it stops the download exactly like `task.stop()` does, removes the notification, and fires the task's `.error()` handler with `errorCode = -1` (`CANCELLED`). Enable it only if your app handles that error. The button label comes from the `downloadCancel` text.
- **Completion notification** (`showCompletionNotification`, default `false`) - when a download finishes, a persistent "download complete" notification is posted on its own `IMPORTANCE_DEFAULT` channel, so unlike the silent progress channel it actually alerts. Title is the download's `notificationTitle` if it has one, otherwise the `downloadFinished` text; the body is the file name. Nothing is posted in `summaryOnly` grouping mode, which exists precisely so a batch of downloads produces a single notification.
- **Per-download title** - pass `metadata.notificationTitle` when creating a task to override the notification title for that download. Precedence: `notificationTitle` -> `groupName` (when grouping is enabled) -> the default `downloadTitle` text.

```javascript
setConfig({
  showNotificationsEnabled: true,
  showCompletionNotification: true,
  showCancelAction: true,
})

const task = createDownloadTask({
  id: 'file123',
  url: 'https://example.com/file.mp3',
  destination: `${directories.documents}/file.mp3`,
  // fileName is optional and only used as the completion notification's body;
  // it falls back to the destination's file name
  metadata: { notificationTitle: 'My Custom Title', fileName: 'file.mp3' },
})
```

These extras are Android 14+ only. On Android 13 and below downloads run through the foreground service, which keeps its existing single progress notification - `notificationTitle`, the Cancel button and the completion notification have no effect there.

**Tap-to-open (FileProvider):** Tapping the completion notification opens the saved file via a `FileProvider` content URI and the system chooser. This works out of the box with **no configuration** — the library ships its own `FileProvider` (`RNBGDFileProvider`, authority `${applicationId}.rnbackgrounddownloader.fileprovider`) declared in its manifest, covering the app-scoped directories downloads are written to. The unique subclass and authority avoid manifest-merger collisions with any `FileProvider` your app already registers.

If a download is saved to a directory the library's provider does not cover, the library falls back to auto-detecting a host-app `FileProvider` authority from the merged manifest. If neither can serve the file, the notification is still posted, but **without** a tap action (the failure is logged, not thrown).

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

This was fixed in v4.4.0. Update to the latest version. The podspec declares the MMKV dependency itself, so you don't need to add anything to your Podfile. If you do pin it manually, exclude the broken 2.4.1 release (see the entry below): `pod 'MMKV', '>= 1.0.0', '!= 2.4.1'`.

### iOS build fails with "use of undeclared identifier 'memset_s'" (MMKVCore)

**MMKVCore 2.4.1** does not compile on Apple platforms - an upstream bug ([Tencent/MMKV#1675](https://github.com/Tencent/MMKV/issues/1675)). The podspec excludes exactly that release (`MMKV (!= 2.4.1)` **and** `MMKVCore (!= 2.4.1)` - the second one is required because `MMKV 2.4.0` itself depends on `MMKVCore (~> 2.4.0)`), so `pod install` resolves `MMKVCore 2.4.0`.

If your `Podfile.lock` still pins the broken version, run `pod update MMKV MMKVCore`. See the [README troubleshooting entry](../README.md#-troubleshooting) for the `__STDC_WANT_LIB_EXT1__=1` `post_install` workaround if another pod forces `MMKVCore 2.4.1`.

### Downloads not resuming after app restart

Make sure to call `getExistingDownloadTasks()` at app startup and re-attach your callbacks. The task IDs you provide are used to identify downloads across restarts.

### Google Play Console asking about Foreground Service

See the [Google Play Console Declaration](#google-play-console-declaration) section above.

### TypeToken errors in release builds (Android)

Add the Proguard rules mentioned in the [Proguard Rules](#proguard-rules) section above.

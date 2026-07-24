# Changelog

## v4.5.9

### 🐛 Bug Fixes

- **iOS: Crash creating an upload task on an invalidated `URLSession` / missing source file (fix [#170](https://github.com/kesha-antonov/react-native-background-downloader/issues/170)):** `upload()` called `uploadTaskWithRequest:fromFile:` directly, so the same `NSInvalidArgumentException` that #157 fixed for downloads (session invalidated after a hot reload or a prior `invalidateAndCancel`) terminated the app when it happened for an upload - the method is a void TurboModule call, so the uncaught exception was rethrown by React and the process died. Upload task creation now goes through the same recovery path as downloads: catch the exception, recreate the background session in place, and retry once. The multipart path also no longer crashes when the source file disappears between the size check and the body build (`appendData:nil`), and a `file://`-prefixed `source` is normalized to a plain path before file access. On top of the crash fixes, every setup failure path (missing id/url/source, invalid URL, unreadable file, failed temp-file write, task creation failure) now emits `uploadFailed` instead of silently returning, so the JS task errors out instead of hanging forever.
- **iOS: `getExistingDownloadTasks()` / `getExistingUploadTasks()` could crash instead of rejecting when background-session creation threw:** both methods created the `NSURLSession` in an unguarded prologue, so the same `NSInvalidArgumentException` behind [#170](https://github.com/kesha-antonov/react-native-background-downloader/issues/170) / [#161](https://github.com/kesha-antonov/react-native-background-downloader/issues/161) (session invalidated after a hot reload or a prior `invalidateAndCancel`) could escape a background-queue method and crash Hermes off the JS thread. Session creation is now funneled through a single guarded choke point that leaves the session unset on failure, so every entry point degrades gracefully instead: `download()` queues the request, and the `getExisting*` methods reject with `ERR_SESSION_NIL`.

### 🏗️ Architecture Changes

- **Cross-platform failure-contract tests:** added a JS-layer test suite that pins the behavior both platforms must share - a native failure event reaches the task's error handler (the [#170](https://github.com/kesha-antonov/react-native-background-downloader/issues/170) regression guard, so a failed upload can never hang), and a completion event reaches its done handler, symmetric across downloads and uploads.
- **Internal refactors (no API or behavior change):** split the JS `config` module into focused `logger` and `notifications` modules; deduplicated the old- and new-architecture Android bridge wrappers into shared `Promise.resolveCatching` / `rejectOnThrow` helpers; and extracted the stateless `ReadableMap` -> JSON conversion and HTTP redirect resolution out of the Android module into `com.eko.utils`. Documented the persistence-migration invariant so new persisted fields on the Android paused-download record stay nullable with an explicit legacy fallback and can't corrupt in-flight downloads across an app update.

## v4.5.8

### 🐛 Bug Fixes

- **Android: `isAllowedOverMetered` ignored on the UIDT/JobScheduler path (fix [#169](https://github.com/kesha-antonov/react-native-background-downloader/issues/169)):** The flag was only applied via `DownloadManager.Request.setAllowedOverMetered()`, but downloads routed through UIDT jobs (the DownloadManager fallback - always used on Android 16 / API 36 - and every pause/resume on Android 14+) built their `NetworkRequest` with `NET_CAPABILITY_INTERNET` only, so `isAllowedOverMetered: false` still transferred over cellular. The UIDT job's required network now includes `NET_CAPABILITY_NOT_METERED` when metered networks are disallowed, so the JobScheduler holds the transfer until an unmetered network is available and releases it automatically - matching the DownloadManager semantics. The restriction is stored in the job's extras and in the persisted pause/recovery state, so it survives pause/resume, process death and JobScheduler reschedules. On top of the constraint, the transfer's sockets are now bound to the network that satisfied it (`JobParameters.getNetwork()`) - previously they used the device's default network, which can be metered cellular even while the satisfying unmetered network is connected. Thanks to [@lakshgk](https://github.com/lakshgk) for the detailed report and root-cause analysis.
- **Android < 14: `isAllowedOverMetered` now enforced on the foreground-service path too ([#169](https://github.com/kesha-antonov/react-native-background-downloader/issues/169)):** Downloads running through `ResumableDownloadService` (Android < 14, and the fallback when UIDT scheduling fails - including any download to internal storage, where `DownloadManager` rejects the path) ignored the flag entirely, since there is no scheduler to hold the transfer. The service now gates unmetered-only downloads on a `ConnectivityManager` network callback: the transfer waits until an unmetered network is available (mirroring DownloadManager's "queued for WiFi" state), the HTTP connection is bound to that specific `Network` so bytes can't leak onto a metered default network, and if the network disappears or becomes metered mid-transfer the download auto-pauses back into the waiting state - a socket abort racing ahead of the connectivity callback is reclassified after a short grace period instead of surfacing as `downloadFailed` - and auto-resumes from its byte offset when an unmetered network returns. All gate transitions are serialized under one lock (a user pause can't be undone by a concurrent network callback) and callback work runs on a dedicated thread instead of the process-wide ConnectivityThread. If the network callback can't be registered at all, the download fails loudly instead of waiting forever. The service notification shows how many downloads are waiting for an unmetered network.
- **Android: pausing a UIDT job that hasn't started yet was silently ignored ([#169](https://github.com/kesha-antonov/react-native-background-downloader/issues/169)):** A job scheduled but still held by its constraints (e.g. waiting for an unmetered network) has no runtime state, so `pause()` found nothing to act on - and the download would start anyway once the constraint was satisfied, despite the user's pause. Pausing now cancels the pending job and persists it as a regular paused download that `resumeTask` can pick up later.
- **Android: resuming with a stale byte offset could corrupt the partial file:** Resuming an in-service paused download never removed its persisted paused record, so after the transfer progressed further and the app was force-stopped, the next launch recovered the download with a byte count lower than the partial file's real size - and resuming from that offset appends mid-stream data at the end of the file. The paused record is now dropped as soon as the download is transferring again, and `resume` recomputes the offset from the on-disk file length whenever the record disagrees with it (the file only ever grows by sequential appends, so its length is the authoritative resume position).
- **Android: foreground service and its notification leaked after pause -> resume -> complete:** A download that finished (or failed) after being resumed never removed its service-side job entry, so the service considered itself busy forever - the "Background Download" notification stayed up and the service never stopped. Terminal cleanup now always runs. The service also returns `START_NOT_STICKY`: all download state is in-memory (recovery goes through persisted snapshots on the next app launch), so the sticky null-intent restart after process death only produced a useless idle service.
- **Android: resumable downloads force-stopped before their first byte were lost:** Recovery snapshots were only written from progress callbacks, so a download that hadn't received any data yet (slow server, or parked waiting for an unmetered network) vanished without a trace if the app was force-stopped - `getExistingDownloadTasks()` never returned it. An initial snapshot is now persisted at start, and zero-byte snapshots are recovered as paused downloads that restart from the beginning.

### 🏗️ Architecture Changes

- **Android: metered-enforcement internals consolidated (no behavior change):** The unmetered-network gate moved out of `ResumableDownloadService` into a dedicated `UnmeteredNetworkGate` component, and both enforcement paths (the UIDT JobScheduler constraint and the gate) build their network requirements from one shared `NetworkRequestUtils`, so the two can't drift apart. `Downloader.PausedDownloadInfo` became the canonical per-download record passed through start, pause/resume and recovery-snapshot paths instead of parallel parameter lists, and internal `isAllowedOverMetered` parameters no longer have defaults - a call site that forgets the flag fails to compile instead of silently reverting to metered-allowed.

## v4.5.6

### 🐛 Bug Fixes

- **iOS: `upload` without `fieldName` forced multipart even for raw PUT uploads (fix [#167](https://github.com/kesha-antonov/react-native-background-downloader/issues/167)):** iOS defaulted `fieldName` to `@"file"` during option parsing, so `fieldName` was never `nil` and `useMultipart` was always `true` - every upload was wrapped in `multipart/form-data`, even a `PUT` with an explicit `Content-Type` header that intended a raw body upload. iOS now keeps `fieldName` `nil` when the option is omitted (matching Android), only defaulting to `"file"` when actually building the multipart body. A raw file upload now sends the file as-is with the caller's `Content-Type`.

## v4.5.5

### 🐛 Bug Fixes

- **iOS: SIGSEGV when a native method throws on the background queue (fix [#161](https://github.com/kesha-antonov/react-native-background-downloader/issues/161)):** The module's `methodQueue` is a custom serial background queue. On the New Architecture, when a void TurboModule method threw an `NSException`, the bridge converted it to a JS error via Hermes JSI on that background thread — and Hermes is not thread-safe, so it crashed with `EXC_BAD_ACCESS`. Reproducible via `setConfig({ allowsCellularAccess: true })` and also affecting `download()` on failure. Wrapped `setAllowsCellularAccess`, `setMaxParallelDownloads`, and `download` so no exception can escape onto the background queue.
- **iOS: Crash creating a task on an invalidated `URLSession` (fix [#157](https://github.com/kesha-antonov/react-native-background-downloader/issues/157)):** `downloadTaskWithRequest:` could raise "attempted to create a NSURLSessionDownloadTask in a session that has been invalidated" (e.g. after a hot reload or a prior `invalidateAndCancel`). The library now catches that, recreates the background session in place, and retries task creation once instead of letting the download fail. Thanks to [@leogaletti](https://github.com/leogaletti) for the diagnosis ([#158](https://github.com/kesha-antonov/react-native-background-downloader/pull/158)).
- **iOS: CocoaPods conflict with `react-native-mmkv` (fix [#162](https://github.com/kesha-antonov/react-native-background-downloader/issues/162)):** The unpinned `MMKV` pod could resolve to a version whose `MMKVCore` is older than what `react-native-mmkv` pins, causing a `pod install` conflict. The podspec now requires only the library's genuine minimum, `MMKV >= 1.2.0` (open lower bound), so CocoaPods can resolve a compatible version for whatever `react-native-mmkv` requires without being locked to one range.
- **Android: `getExistingDownloadTasks()` missed active resumable downloads (fix [#164](https://github.com/kesha-antonov/react-native-background-downloader/issues/164)):** When `DownloadManager` can't be used (e.g. an internal destination path, or device-specific path restrictions on some Android 11 devices) the library falls back to `ResumableDownloader`, whose downloads were invisible to `getExistingDownloadTasks()` while in progress — they only appeared once paused. Added a phase that returns in-progress `ResumableDownloader` downloads with their current progress and RUNNING/PAUSED state.
- **Android: Active downloads lost after force-stop (fix [#159](https://github.com/kesha-antonov/react-native-background-downloader/issues/159)):** A resumable download that was actively running when the app was force-stopped returned `[]` from `getExistingDownloadTasks()` on relaunch (it only worked if the user had paused first). The library now periodically persists a recovery snapshot for in-progress resumable downloads (both the foreground-service path on Android < 14 and the UIDT path on Android 14+), and on the next launch surfaces each download whose partial file still exists as a resumable task. The resume offset is recomputed from the partial file's on-disk length so a resumed download can't be corrupted by a snapshot that lagged the real byte count.

### ✨ New Features

- **iOS: Reliable background downloads while the device is locked (fix [#101](https://github.com/kesha-antonov/react-native-background-downloader/issues/101)):** Downloaded files are now saved with `NSFileProtectionCompleteUntilFirstUserAuthentication` by default, so a background download can write its file while the device is locked (after the first unlock since boot) instead of failing the save under Data Protection. If the move to the destination still can't happen because the device is locked, the bytes are staged and the save (and `downloadComplete` event) is completed automatically when the device is next unlocked. Added an `iosDataProtection` option — global via `setConfig` and per task via `createDownloadTask` (`'completeUntilFirstUserAuthentication'` | `'complete'` | `'completeUnlessOpen'` | `'none'`) — ignored on Android.

### 🏗️ Architecture Changes

- **Compiled JS entry point for Node 24 (fix [#113](https://github.com/kesha-antonov/react-native-background-downloader/issues/113)):** `package.json` `main`/`types` previously pointed at `src/index.ts`. Node 24's experimental type stripping refuses to strip types under `node_modules`, breaking `require(...)` / `npx expo prebuild`. The package now compiles `src` to `lib/` (CommonJS + `.d.ts`) and points `main`/`types` there. The `react-native` field still points at `src/index.ts`, so Metro keeps bundling from source and codegen is unchanged.

### 📚 Documentation

- **iOS background downloads & device lock ([#101](https://github.com/kesha-antonov/react-native-background-downloader/issues/101)):** Added a Troubleshooting section explaining what actually happens when the screen is locked (transfers continue via `nsurlsessiond`; force-quit halts them; JS doesn't run while suspended so events are deferred; `handleEventsForBackgroundURLSession` + `completeHandler` are required; Simulator behavior is unreliable), plus documentation for the new `iosDataProtection` option.

---

## v4.5.4

### 🐛 Bug Fixes

- **iOS: SIGABRT Crash on New Architecture (TurboModules):** Fixed a crash where `NSURLSession` delegate callbacks fired before the JS side had registered event listeners (e.g. background session delivering completions from a prior app session on launch). Added a `safeEmitEvent:` helper that queues events when the emitter callback is not yet set and flushes the queue once `setEventEmitterCallback:` is called. All 8 emit call sites (download + upload) are updated. (PR [#153](https://github.com/kesha-antonov/react-native-background-downloader/pull/153) by [@isaacrowntree](https://github.com/isaacrowntree))
- **Android: UIDT Downloads Not Starting on VPN Networks (fix [#154](https://github.com/kesha-antonov/react-native-background-downloader/issues/154)):** Removed `NET_CAPABILITY_NOT_VPN` from UIDT `JobScheduler` network requirements so that VPN networks (e.g. Proton VPN, full-tunnel VPNs) are accepted. Previously the job never started when a kill-switch VPN was active because `JobScheduler` only considered non-VPN interfaces.
- **Android: UIDT Downloads Not Resuming After App Restart (fix [#156](https://github.com/kesha-antonov/react-native-background-downloader/issues/156)):** Fixed headers and start-byte resolution for UIDT jobs after a cross-process restart. In-memory `pendingHeaders` are used when available (same-process); the disk-persisted resume state is used as a fallback for a fresh process. Also reconnects UIDT event forwarding to JS on Android 14+ when the app is reopened while downloads are already in progress.
- **Cross-Platform: Stale Progress Event After Download Completes/Fails:** Fixed a race condition where a buffered progress event could arrive in JS after `downloadComplete` or `downloadFailed`.
  - **iOS:** Clears `progressReports` entry for the task before dispatching `sendDownloadCompletionEvent`.
  - **Android (ResumableDownloader/UIDT):** Calls `clearPendingReport` before `emitComplete` / `emitFailed`.
  - **Android (DownloadManager):** Calls `clearPendingReport` inside the synchronized block before `onSuccessfulDownload` / `onFailedDownload` to close the race with the polling thread.

### 📚 Documentation

- **iOS Force-Kill Limitation (fix [#155](https://github.com/kesha-antonov/react-native-background-downloader/issues/155)):** Clarified that user-initiated force-kills via the iOS App Switcher cancel all `NSURLSession` background tasks — this is an intentional iOS system behaviour that cannot be overridden. Added a dedicated "Force-Kill Limitation" section to `PLATFORM_NOTES.md` with a summary table and a workaround suggestion (silent push + `getExistingDownloadTasks()`).
- **MMKV Version Downgraded to 1.3.16 (fix [#150](https://github.com/kesha-antonov/react-native-background-downloader/issues/150)):** Changed the default MMKV version from `2.2.4` to `1.3.16` (LTS) to restore `armeabi-v7a` (32-bit ARM) support that was dropped in MMKV 2.x. Added an MMKV version comparison table to the README covering official 1.x/2.x and the Margelo fork used by `react-native-mmkv` v4.x.

---

## v4.5.3

### ✨ New Features

- **Android: Notification Grouping Mode (`summaryOnly`):** Added `mode` option to `NotificationsGroupingConfig`. Set `mode: 'summaryOnly'` to show only the summary notification for a group while individual download notifications are minimized (ultra-silent, no alert). Useful for keeping the notification shade clean during large batch downloads.
  - `'individual'` (default) — all notifications shown, current behavior unchanged
  - `'summaryOnly'` — only the group summary notification is shown with aggregate progress; individual notifications are invisible/silent
- **Android: Progress-Based Summary Notification:** In `summaryOnly` mode, the group summary notification now displays aggregate progress (total bytes downloaded / total bytes) across all downloads in the group.
- **Android: Auto-Remove Completed Downloads from Group:** Completed downloads are now automatically removed from notification groups, keeping the summary accurate.

### 🏗️ Architecture Changes

- **JS: `NotificationGroupingMode` Type:** New exported type `'individual' | 'summaryOnly'` for the `mode` field in `NotificationsGroupingConfig`.
- **Android: Ultra-Silent Notification Channel:** Added `NOTIFICATION_CHANNEL_ULTRA_SILENT_ID` (`IMPORTANCE_MIN`) channel used for individual notifications in `summaryOnly` mode.
- **Android: `updateSummaryNotificationForGroup()`:** New method that dispatches to the correct summary update strategy based on grouping mode.

---

## v4.5.2

### ✨ New Features

- **Update Headers on Paused Downloads:** Added ability to update headers (e.g., refresh auth tokens) on paused download tasks before resuming. Use `task.setDownloadParams()` to update headers while paused, then `task.resume()` to continue the download with new headers.
  - **Use case:** User pauses a large download, returns hours/days later when auth token has expired. Now you can refresh the token and resume without restarting the download.
  - **iOS:** Creates a fresh request with HTTP Range header and updated headers on resume
  - **Android:** Updates both in-memory headers and persisted paused download state

### 🏗️ Architecture Changes

- **JS: `setDownloadParams()` Now Async:** The `DownloadTask.setDownloadParams()` method is now async and returns `Promise<boolean>` indicating whether native headers were updated (true when task is paused).
- **Native: Added `updateTaskHeaders` Method:** New native method on iOS and Android to update headers for paused tasks.

### 📚 Documentation

- Added "Updating headers on paused downloads" section to README
- Added `setDownloadParams()` method documentation to API.md
- Added iOS "Updating Headers on Paused Downloads" section to PLATFORM_NOTES.md

---

## v4.5.1

### 🏗️ Architecture Changes

- **Android: UIDT Code Refactoring:** Extracted 980-line monolithic `UIDTDownloadJobService.kt` into modular components:
  - `uidt/UIDTJobState.kt` - Data classes, constants, job registry
  - `uidt/UIDTNotificationManager.kt` - All notification logic
  - `uidt/UIDTJobManager.kt` - Job scheduling, cancel, pause, resume
  - `utils/ProgressUtils.kt` - Progress calculation utilities
  - Backward compatibility maintained via companion object delegates
- **Android: Removed Redundant jobScheduler.cancel():** In `pauseJob()`, removed unnecessary `jobScheduler.cancel()` after `jobFinished(params, false)` since `wantsReschedule=false` already tells the system the job is complete.

---

## v4.5.0

### ✨ New Features

- **Android: Global Notification Configuration:** Added `showNotificationsEnabled` and `notificationsGrouping` config options for controlling UIDT notifications globally via `setConfig()`.
- **Android: Customizable Paused Notification Text:** Added `downloadPaused` to `NotificationTexts` interface for customizing the "Paused" notification text.
- **Android: Notification Update Throttling:** Notification updates are now synced with `progressInterval` for consistent UI/notification progress display.

### 🐛 Bug Fixes

- **Android: Paused Downloads Continuing in Background:** Fixed paused UIDT downloads continuing to download in background after app restart. Now UIDT job is fully cancelled on pause, with a detached "Paused" notification shown.
- **Android: Duplicate Notifications After Resume:** Fixed duplicate notifications appearing when resuming downloads after app restart. Now uses stable notification IDs based on `configId.hashCode()`.
- **Android: Notification Not Updating After Resume:** Fixed notification stuck on old progress after resuming. Now resets notification timing on resume and shows correct progress immediately.
- **Android: Notification Updating While Paused:** Fixed notification progress updating even when download is paused.
- **Android: Stale Notifications on App Close:** All download notifications are now cancelled when the app closes via `invalidate()`.

### 💥 Breaking Changes

- **Removed Per-Task Notification Options:** `isNotificationVisible` and `notificationTitle` removed from `DownloadParams` and `UploadParams`. Use global `setConfig({ showNotificationsEnabled, notificationsGrouping })` instead.

### 🏗️ Architecture Changes

- **Android: Pause Behavior on Android 14+:** Complete redesign of pause/resume for User-Initiated Data Transfer (UIDT) jobs:
  - **Problem:** UIDT jobs continue running in background even after app closes, causing "paused" downloads to secretly continue downloading.
  - **Solution:** On pause, the UIDT job is properly terminated via `jobFinished(params, false)`. Download state is persisted to disk for resumption via HTTP Range headers.
  - **UX:** A detached "Paused" notification (using `JOB_END_NOTIFICATION_POLICY_DETACH`) remains visible after job termination. On resume, a new UIDT job is created.
  - **Follows Google's UIDT best practices:** State saved even without `onStopJob`, `jobFinished()` called on completion, notifications updated periodically with throttling.
- **Android: Separate Notification Channels:** Added separate channels for visible (`IMPORTANCE_LOW`) and silent (`IMPORTANCE_MIN`) notifications.

### ✨ Improvements

- **Android: Cleaner Notifications:** Added `setOnlyAlertOnce(true)` and `setShowWhen(false)` to all notifications for less intrusive updates.
- **Example App: Persistent Notification Settings:** Show Notifications and Notification Grouping toggles are now persisted with MMKV.
- **Example App: Android 13+ Permission Request:** Added POST_NOTIFICATIONS permission request when enabling notifications.

### 📚 Documentation

- Updated README with notification behavior during pause/resume
- Updated API.md with new notification configuration options
- Documented that notifications are removed when app closes

---

## v4.4.5

### 🐛 Bug Fixes

- **Android: Stop Task Not Working on Android 14+:** Fixed `stopTask()` not actually stopping UIDT downloads on Android 14+. The JobScheduler job was cancelled but the underlying HTTP download continued. Now properly calls `resumableDownloader.cancel()` before removing from active jobs.
- **Android: Paused Tasks Not Persisting Across App Restarts:** Fixed paused UIDT downloads losing their state when the app was restarted. Added `getJobDownloadState()` to retrieve download state from active UIDT jobs and `savePausedDownloadState()` to properly persist pause state.
- **Android: ACCESS_NETWORK_STATE Permission:** Added missing permission required for JobScheduler network connectivity constraints on Android 14+.
- **Android: Downloaded Files List Showing Incomplete Files:** The "Downloaded Files" section in the example app now correctly filters out files that have active (non-DONE) download tasks, preventing incomplete files from appearing in the list.

### 🧹 Code Cleanup

- **Removed Verbose Debug Logs:** Cleaned up extensive debug logging in `StorageManager`, `Downloader`, and `RNBackgroundDownloaderModuleImpl` that was cluttering production logs. Removed serialization/deserialization logs, verification reads, and per-item iteration logs while keeping error logging.
- **Simplified Kotlin Code:** Removed unnecessary `else` blocks containing only debug/warning logs from `pauseTask()` and `resumeTask()` methods for cleaner code.

### ✨ Improvements

- **TypeScript: Added `destination` to Task Info:** The `destination` field is now returned from `getExistingDownloadTasks()` for paused downloads, allowing the app to know where the file will be saved.

### 📚 Documentation

- Added `skipMmkvDependency` option documentation to README for Expo plugin

---

## v4.4.4

### 🐛 Bug Fixes

- **Expo Plugin: Fixed TypeScript Types:** Corrected TypeScript type definitions in the Expo config plugin.

---

## v4.4.3

### ✨ New Features

- **Expo Plugin: Auto-detect react-native-mmkv:** The Expo config plugin now automatically detects if `react-native-mmkv` is installed and skips adding the MMKV dependency to avoid duplicate class errors. Use `skipMmkvDependency: true` option to manually skip if needed.
- **Android: Version from package.json:** Android native code now reads the library version from `package.json` instead of hardcoding it.

---

## v4.4.2

### 🐛 Bug Fixes

- **Kotlin 2.0 Compatibility:** Fixed compilation error with Kotlin 2.0 (React Native 0.77+) by updating `progressReporter` to use named parameter syntax. This ensures compatibility with both Kotlin 1.9 (RN 0.76) and Kotlin 2.x (RN 0.77+).

---

## v4.4.1

### 🐛 Bug Fixes

- **Android: Paused Tasks Persistence:** Fixed paused downloads not being restored after app restart on Android. Added persistent storage for paused download state using MMKV/SharedPreferences.
- **iOS: Improved Pause/Resume Handling:** Better handling of pause/resume operations on app restarts for iOS.
- **Upload Task App Restart Recovery:** Fixed upload tasks not being recoverable after app restart ([#143](https://github.com/kesha-antonov/react-native-background-downloader/issues/143)). Added persistent storage for upload task configurations.

### ✨ Improvements

- **Example App:** Added task list display with animations and improved UI for managing downloads.

### 📚 Documentation

- Updated README with clearer MMKV dependency instructions
- Added information about resuming tasks after app restarts
- Updated authors section

---

## v4.4.0

### ✨ New Features

- **Android 16 UIDT Support:** Downloads are now automatically marked as User-Initiated Data Transfers on Android 16+ (API 36) to prevent thermal throttling and job quota restrictions. Downloads will continue reliably even under moderate thermal conditions (~40°C).

### 🐛 Bug Fixes

- **iOS MMKV Conflict Fix:** Removed hard MMKV dependency from iOS podspec to prevent symbol conflicts with `react-native-mmkv`. Apps using `react-native-mmkv` no longer experience crashes (EXC_BAD_ACCESS) on iOS.

### 📦 Dependencies & Infrastructure

- **New Android Permission:** Added `RUN_USER_INITIATED_JOBS` permission for Android 16+ UIDT support
- **iOS MMKV Dependency:** MMKV is no longer a hard dependency in the podspec. Apps not using `react-native-mmkv` must add `pod 'MMKV', '>= 1.0.0'` to their Podfile.

### 📚 Documentation

- Added documentation about Android 16+ UIDT support in README
- Added iOS MMKV dependency section in README (similar to Android section)
- Added migration guide for iOS MMKV dependency change

---

## v4.2.0

> 📖 **Upgrading from v4.1.x?** See the [Migration Guide](./MIGRATION.md) for details on the new Android pause/resume functionality.

### ✨ New Features

- **Android Pause/Resume Support:** Android now fully supports `task.pause()` and `task.resume()` methods using HTTP Range headers. Downloads can be paused and resumed just like on iOS.
- **Background Download Service:** Added `ResumableDownloadService` - a foreground service that ensures downloads continue even when the app is in the background or the screen is off.
- **WakeLock Support:** Downloads maintain a partial wake lock to prevent the device from sleeping during active downloads.
- **`bytesTotal` Unknown Size Handling:** When the server doesn't provide a `Content-Length` header, `bytesTotal` now returns `-1` instead of `0` to distinguish "unknown size" from "zero bytes".

### 🐛 Bug Fixes

- **Android Pause Error:** Fixed `COULD_NOT_FIND` error when pausing downloads on Android by properly tracking pausing state
- **Temp File Cleanup:** Fixed temp files (`.tmp`) not being deleted when stopping or deleting paused downloads
- **Content-Length Handling:** Fixed progress percentage calculation when server doesn't provide Content-Length header

### 📦 Dependencies & Infrastructure

- **New Android Permissions:** Added `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and `WAKE_LOCK` permissions for background download support
- **New Service:** Added `ResumableDownloadService` with `dataSync` foreground service type

### 📚 Documentation

- Updated README to reflect Android pause/resume support
- Removed "iOS only" notes from pause/resume documentation
- Added documentation about `bytesTotal` returning `-1` for unknown sizes
- Updated Android DownloadManager Limitations section with new pause/resume implementation details

---

## v4.1.0

> 📖 **Upgrading from v4.0.x?** See the [Migration Guide](./MIGRATION.md) for the MMKV dependency change.

### ⚠️ Breaking Changes

- **MMKV Dependency Changed to `compileOnly`:** The MMKV dependency is now `compileOnly` instead of `implementation` to avoid duplicate class errors when the app also uses `react-native-mmkv`. Apps not using `react-native-mmkv` must now explicitly add the MMKV dependency.

### ✨ New Features

- **Expo Plugin Android Support:** The Expo config plugin now automatically adds the MMKV dependency on Android. Use `addMmkvDependency: false` option if you're already using `react-native-mmkv`.

### 🐛 Bug Fixes

- **Duplicate Class Errors:** Fixed potential duplicate class errors when app uses both this library and `react-native-mmkv` by changing MMKV to `compileOnly` dependency

### 📚 Documentation

- Added documentation for MMKV dependency requirements in README
- Updated Platform-Specific Limitations section with MMKV setup instructions
- Added Expo plugin options documentation

---

## v4.0.0

> 📖 **Upgrading from v3.x?** See the [Migration Guide](./MIGRATION.md) for detailed instructions.

### ⚠️ Breaking Changes

- **API Renamed:** `checkForExistingDownloads()` → `getExistingDownloadTasks()` - Now returns a Promise with better naming
- **API Renamed:** `download()` → `createDownloadTask()` - Downloads now require explicit `.start()` call
- **Download Tasks Start Explicitly:** Tasks created with `createDownloadTask()` are now in `PENDING` state and must call `.start()` to begin downloading
- **New Config Option:** Added `progressMinBytes` to `setConfig()` - controls minimum bytes change before progress callback fires (default: 1MB)
- **Source Structure Changed:** Code moved from `lib/` to `src/` directory with proper TypeScript types

### ✨ New Features

- **React Native New Architecture Support:** Full TurboModules support for both iOS and Android
- **Expo Config Plugin:** Added automatic iOS native code integration for Expo projects via `app.plugin.js`
- **Android Kotlin Migration:** All Java code converted to Kotlin
- **`maxRedirects` Option:** Configure maximum redirects for Android downloads (resolves [#15](https://github.com/kesha-antonov/react-native-background-downloader/issues/15))
- **`progressMinBytes` Option:** Hybrid progress reporting - callbacks fire based on time interval OR bytes downloaded
- **Android 15+ Support:** Added support for 16KB memory page sizes
- **Architecture Fallback:** Comprehensive x86/ARMv7 support with SharedPreferences fallback

### 🐛 Bug Fixes

- **iOS Pause/Resume:** Fixed pause and resume functionality on iOS
- **RN 0.78+ Compatibility:** Fixed bridge checks with safe emitter checks
- **New Architecture Events:** Fixed `downloadBegin` and `downloadProgress` events emission
- **Android Background Downloads:** Fixed completed files not moving to destination
- **Progress Callback Unknown Total:** Fixed progress callback not firing when total bytes unknown
- **Android 12 MMKV Crash:** Added robust error handling
- **`checkForExistingDownloads` TypeError:** Fixed TypeError on Android with architecture fallback
- **Firebase Performance Compatibility:** Fixed `completeHandler` method compatibility on Android
- **Slow Connection Handling:** Better handling of slow-responding URLs with timeouts
- **Android OldArch Export:** Fixed module method export issue ([#79](https://github.com/kesha-antonov/react-native-background-downloader/issues/79))
- **MMKV Compatibility:** Support for react-native-mmkv 4+ with mmkv-shared dependency

### 📦 Dependencies & Infrastructure

- **React Native:** Updated example app to RN 0.81.4
- **TypeScript:** Full TypeScript types in `src/types.ts`
- **iOS Native:** Converted from `.m` to `.mm` (Objective-C++)
- **Package Manager:** Switched to yarn as preferred package manager

### 📚 Documentation

- Added documentation for `progressMinBytes` option
- Updated README for React Native 0.77+ instructions
- Improved Expo config plugin examples

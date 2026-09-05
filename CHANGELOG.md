# Changelog

## v4.6.2

### 🐛 Bug Fixes

- **Android: the library didn't build under AGP 9 (`Cannot add extension with name 'kotlin'`) ([#178](https://github.com/kesha-antonov/react-native-background-downloader/pull/178)):** AGP 9 enables built-in Kotlin support by default and registers the `kotlin` extension itself, so applying `kotlin-android` on top of it failed configuration before anything compiled. It is now applied only when that extension is absent: AGP 8 behaves exactly as before, and AGP 10, where the `android.builtInKotlin` opt-out is gone, needs no special case. Thanks to [@gabrieldonadel](https://github.com/gabrieldonadel)

## v4.6.1

### 🐛 Bug Fixes

- **iOS: build failed with `use of undeclared identifier 'memset_s'` once CocoaPods resolved MMKVCore 2.4.1 (fix [#175](https://github.com/kesha-antonov/react-native-background-downloader/issues/175)):** MMKVCore 2.4.1 doesn't compile on Apple platforms ([Tencent/MMKV#1675](https://github.com/Tencent/MMKV/issues/1675)), and the podspec had no upper bound, so any project without another MMKV constraint picked it up and failed to build. Both `MMKV` and `MMKVCore` are now declared with `!= 2.4.1` - excluding `MMKV` alone isn't enough, since `MMKV 2.4.0` pulls in `MMKVCore (~> 2.4.0)`. If your `Podfile.lock` already pins 2.4.1, run `cd ios && pod update MMKV MMKVCore`. Thanks to [@raginsky](https://github.com/raginsky) for the root-cause analysis

## v4.6.0

### 🐛 Bug Fixes

- **Android 14+: two concurrent downloads could share one notification:** notification IDs were `NOTIFICATION_ID_BASE + hash(configId) % 100000`, so downloads that hashed to the same slot overwrote each other's progress, and whichever finished first took the notification down for the other. IDs are now assigned per live download, still starting the search at the hash so a download keeps the ID it has always had unless another live download holds it
- **Android: a download the service could never start hung forever with no event ([#174](https://github.com/kesha-antonov/react-native-background-downloader/pull/174)):** when a download fell back to the foreground service and the system refused to start it - a background start on Android 12+, say - it waited on a service connection that never came. No `begin`, no `error`. A download that hasn't reached the service within 15 seconds now fails with an error the app can handle
- **Android 14+: a download whose job hadn't started yet was invisible, and could end up tracked twice ([#173](https://github.com/kesha-antonov/react-native-background-downloader/pull/173)):** `getExistingDownloadTasks()` and force-stop recovery both read an in-memory registry that is only filled in `onStartJob`, so a job still waiting on its network constraint was missing - and after a process restart was recovered as *paused* while it downloaded on its own. Both now ask the JobScheduler, which holds the job whether it is running or waiting
- **Android 14+: concurrent downloads could silently kill each other through colliding JobScheduler IDs ([#172](https://github.com/kesha-antonov/react-native-background-downloader/pull/172)):** job IDs were `JOB_ID_BASE + hash(configId) % 10000`, and `schedule()` on an existing ID *replaces* the job holding it - across the 150 concurrent downloads the quota allows, the birthday bound puts a collision at roughly two chances in three. A download's job is now found by the config ID the job carries in its extras, and a download needing a new job takes the first ID no live job holds
- **Android 14+: starting a large batch of downloads could crash the app on the JobScheduler quota ([#171](https://github.com/kesha-antonov/react-native-background-downloader/pull/171)):** `JobScheduler` holds at most 150 distinct jobs per app and throws `IllegalStateException` from `schedule()` past that, and the call was unguarded. Scheduling now checks the pending-job count first, keeps headroom for the rest of the app, and degrades to the foreground-service path instead of terminating the process

### ✨ Features

- **Android 14/15: fresh downloads now run as user-initiated data transfer jobs:** only Android 16+ used them for a new download; on 14 and 15 a download started through `DownloadManager` and became a UIDT job only after a pause/resume, so notification grouping, the completion notification, the Cancel action and per-download titles did nothing until then. `DownloadManager` stays as the fallback for when a job can't be scheduled, most importantly a `download()` call from the background
- **`maxParallelDownloads` now applies on Android ([#174](https://github.com/kesha-antonov/react-native-background-downloader/pull/174)):** the config was documented as iOS-only and was a no-op on Android, where the downloader started a thread and a socket for every download at once. It now caps concurrent transfers (default 4, matching iOS), with the rest waiting for a slot; downloads running through `DownloadManager` or the JobScheduler are queued by those schedulers instead, so they keep transferring in the background as before
- **Android 14+: completion notification, notification Cancel action, and per-download notification titles ([#165](https://github.com/kesha-antonov/react-native-background-downloader/pull/165)):** UIDT downloads can now post a persistent "download complete" notification that opens the saved file through a library-owned `FileProvider`, and show a **Cancel** button that stops the download and fires `.error()` with `errorCode = -1`. A task can override its title via `metadata.notificationTitle`. Both extras are opt-in through `showCompletionNotification` and `showCancelAction` (default `false`). Thanks to [@HuuNguyen312](https://github.com/HuuNguyen312) for the implementation

### 🏗️ Architecture Changes

- **Android unit tests:** the Android side had no test infrastructure at all, so the scheduling work above was verified by reading. There is now a Robolectric + MockWebServer harness and 38 tests over the parts that are easy to get subtly wrong: job-ID assignment, the quota guard, re-scheduling an existing download, pausing a job that hasn't started, notification IDs, and the transfer concurrency cap. Run them with `yarn test:android`; CI runs the JS and Android suites on push and pull request

## v4.5.9

### 🐛 Bug Fixes

- **iOS: crash creating an upload task on an invalidated `URLSession` / missing source file (fix [#170](https://github.com/kesha-antonov/react-native-background-downloader/issues/170)):** `upload()` called `uploadTaskWithRequest:fromFile:` directly, so the same `NSInvalidArgumentException` that #157 fixed for downloads still killed the app for uploads. Upload task creation now goes through the same catch-recreate-retry path, the multipart path no longer crashes when the source file disappears mid-build, and every setup failure emits `uploadFailed` instead of hanging the JS task forever
- **iOS: `getExistingDownloadTasks()` / `getExistingUploadTasks()` could crash instead of rejecting:** both created the `NSURLSession` in an unguarded prologue, so a throw could escape a background-queue method and crash Hermes off the JS thread. Session creation now goes through one guarded choke point: `download()` queues the request, and the `getExisting*` methods reject with `ERR_SESSION_NIL`

### 🏗️ Architecture Changes

- **Cross-platform failure-contract tests:** a JS-layer suite pinning what both platforms must share - a native failure event reaches the task's error handler (the [#170](https://github.com/kesha-antonov/react-native-background-downloader/issues/170) regression guard, so a failed upload can never hang) and a completion event reaches its done handler, symmetric across downloads and uploads
- **Internal refactors (no API or behavior change):** split the JS `config` module into `logger` and `notifications`; deduplicated the old- and new-architecture Android bridge wrappers into shared `Promise.resolveCatching` / `rejectOnThrow` helpers; moved `ReadableMap` -> JSON conversion and redirect resolution into `com.eko.utils`

## v4.5.8

### 🐛 Bug Fixes

- **Android: `isAllowedOverMetered` ignored on the UIDT/JobScheduler path (fix [#169](https://github.com/kesha-antonov/react-native-background-downloader/issues/169)):** the flag was only applied through `DownloadManager.Request.setAllowedOverMetered()`, so downloads routed via UIDT jobs still transferred over cellular. The job's required network now includes `NET_CAPABILITY_NOT_METERED`, the restriction survives pause/resume and process death through the job extras, and the transfer's sockets are bound to the network that satisfied the constraint rather than the device default. Thanks to [@lakshgk](https://github.com/lakshgk) for the report and analysis
- **Android < 14: `isAllowedOverMetered` now enforced on the foreground-service path too ([#169](https://github.com/kesha-antonov/react-native-background-downloader/issues/169)):** `ResumableDownloadService` ignored the flag entirely, since there is no scheduler to hold the transfer. The download now waits on a `ConnectivityManager` callback until an unmetered network is available, binds the connection to that `Network`, auto-pauses if it disappears mid-transfer and auto-resumes from its byte offset. If the callback can't be registered at all, the download fails loudly instead of waiting forever
- **Android: pausing a UIDT job that hadn't started yet was silently ignored ([#169](https://github.com/kesha-antonov/react-native-background-downloader/issues/169)):** a job still held by its constraints has no runtime state, so `pause()` found nothing to act on - and the download started anyway once the constraint was satisfied. Pausing now cancels the pending job and persists it as a regular paused download that `resumeTask` can pick up
- **Android: resuming with a stale byte offset could corrupt the partial file:** resuming an in-service paused download never removed its persisted paused record, so a later force-stop recovered a byte count lower than the file's real size, and resuming from it appended mid-stream data at the end. The record is now dropped as soon as the download is transferring again, and `resume` recomputes the offset from the on-disk file length
- **Android: foreground service and its notification leaked after pause -> resume -> complete:** a download that finished or failed after being resumed never removed its service-side job entry, so the service considered itself busy forever and its notification stayed up. Terminal cleanup now always runs, and the service returns `START_NOT_STICKY`
- **Android: resumable downloads force-stopped before their first byte were lost:** recovery snapshots were only written from progress callbacks, so a download that hadn't received any data yet vanished without a trace. An initial snapshot is now persisted at start, and zero-byte snapshots are recovered as paused downloads that restart from the beginning

### 🏗️ Architecture Changes

- **Android: metered-enforcement internals consolidated (no behavior change):** the unmetered gate moved out of `ResumableDownloadService` into a dedicated `UnmeteredNetworkGate`, both enforcement paths build their requirements from one shared `NetworkRequestUtils` so they can't drift, and `Downloader.PausedDownloadInfo` became the canonical per-download record. Internal `isAllowedOverMetered` parameters lost their defaults, so a call site that forgets the flag fails to compile

## v4.5.6

### 🐛 Bug Fixes

- **iOS: `upload` without `fieldName` forced multipart even for raw PUT uploads (fix [#167](https://github.com/kesha-antonov/react-native-background-downloader/issues/167)):** iOS defaulted `fieldName` to `@"file"` during option parsing, so `useMultipart` was always `true` - even for a `PUT` with an explicit `Content-Type` meant as a raw body upload. `fieldName` now stays `nil` when omitted (matching Android), defaulting to `"file"` only when actually building the multipart body

## v4.5.5

### 🐛 Bug Fixes

- **iOS: SIGSEGV when a native method throws on the background queue (fix [#161](https://github.com/kesha-antonov/react-native-background-downloader/issues/161)):** on the New Architecture, a void TurboModule method that threw an `NSException` had it converted to a JS error via Hermes JSI on the module's serial background queue - and Hermes is not thread-safe. `setAllowsCellularAccess`, `setMaxParallelDownloads` and `download` are now wrapped so no exception escapes onto that queue
- **iOS: crash creating a task on an invalidated `URLSession` (fix [#157](https://github.com/kesha-antonov/react-native-background-downloader/issues/157)):** `downloadTaskWithRequest:` could raise "attempted to create a NSURLSessionDownloadTask in a session that has been invalidated" after a hot reload or a prior `invalidateAndCancel`. The library now catches that, recreates the background session in place and retries once. Thanks to [@leogaletti](https://github.com/leogaletti) for the diagnosis ([#158](https://github.com/kesha-antonov/react-native-background-downloader/pull/158))
- **iOS: CocoaPods conflict with `react-native-mmkv` (fix [#162](https://github.com/kesha-antonov/react-native-background-downloader/issues/162)):** the unpinned `MMKV` pod could resolve to a version whose `MMKVCore` is older than what `react-native-mmkv` pins, causing a `pod install` conflict. The podspec now requires only the library's genuine minimum, `MMKV >= 1.2.0`, so CocoaPods can resolve whatever the other pod needs
- **Android: `getExistingDownloadTasks()` missed active resumable downloads (fix [#164](https://github.com/kesha-antonov/react-native-background-downloader/issues/164)):** downloads that fall back to `ResumableDownloader` - an internal destination path, or path restrictions on some Android 11 devices - were invisible while in progress and only appeared once paused. They are now returned with their current progress and RUNNING/PAUSED state
- **Android: active downloads lost after force-stop (fix [#159](https://github.com/kesha-antonov/react-native-background-downloader/issues/159)):** a resumable download running when the app was force-stopped returned `[]` from `getExistingDownloadTasks()` on relaunch, unless the user had paused it first. The library now periodically persists a recovery snapshot and surfaces each download whose partial file still exists, recomputing the resume offset from the file's on-disk length so a lagging snapshot can't corrupt it

### ✨ New Features

- **iOS: reliable background downloads while the device is locked (fix [#101](https://github.com/kesha-antonov/react-native-background-downloader/issues/101)):** files are now saved with `NSFileProtectionCompleteUntilFirstUserAuthentication`, so a background download can write while the device is locked instead of failing the save under Data Protection; if the move still can't happen, the bytes are staged and the save (and `downloadComplete`) completes on the next unlock. Added an `iosDataProtection` option, global via `setConfig` and per task via `createDownloadTask`, ignored on Android

### 🏗️ Architecture Changes

- **Compiled JS entry point for Node 24 (fix [#113](https://github.com/kesha-antonov/react-native-background-downloader/issues/113)):** `main`/`types` pointed at `src/index.ts`, and Node 24's type stripping refuses to strip types under `node_modules`, breaking `require(...)` / `npx expo prebuild`. The package now compiles `src` to `lib/` (CommonJS + `.d.ts`) and points `main`/`types` there. The `react-native` field still points at source, so Metro and codegen are unchanged

### 📚 Documentation

- **iOS background downloads & device lock ([#101](https://github.com/kesha-antonov/react-native-background-downloader/issues/101)):** added a Troubleshooting section on what actually happens when the screen is locked - transfers continue via `nsurlsessiond`, force-quit halts them, events are deferred while JS is suspended, `handleEventsForBackgroundURLSession` + `completeHandler` are required, and Simulator behavior is unreliable - plus docs for the new `iosDataProtection` option

---

## v4.5.4

### 🐛 Bug Fixes

- **iOS: SIGABRT crash on the New Architecture (TurboModules):** `NSURLSession` delegate callbacks could fire before JS had registered event listeners, e.g. a background session delivering completions from a prior app session on launch. A `safeEmitEvent:` helper now queues events until `setEventEmitterCallback:` is called, then flushes them (PR [#153](https://github.com/kesha-antonov/react-native-background-downloader/pull/153) by [@isaacrowntree](https://github.com/isaacrowntree))
- **Android: UIDT downloads not starting on VPN networks (fix [#154](https://github.com/kesha-antonov/react-native-background-downloader/issues/154)):** removed `NET_CAPABILITY_NOT_VPN` from the UIDT job's network requirements, which `JobScheduler` read as non-VPN interfaces only - so a kill-switch VPN kept the job from ever starting
- **Android: UIDT downloads not resuming after app restart (fix [#156](https://github.com/kesha-antonov/react-native-background-downloader/issues/156)):** fixed headers and start-byte resolution for UIDT jobs after a cross-process restart - in-memory `pendingHeaders` when available, disk-persisted resume state as the fallback. Also reconnects UIDT event forwarding to JS on Android 14+ when the app is reopened mid-download
- **Cross-platform: stale progress event after a download completed or failed:** a buffered progress event could arrive in JS after `downloadComplete` / `downloadFailed`. Both platforms now clear the pending report before emitting the terminal event, with the `DownloadManager` path doing it inside the synchronized block to close the race with the polling thread

### 📚 Documentation

- **iOS force-kill limitation (fix [#155](https://github.com/kesha-antonov/react-native-background-downloader/issues/155)):** clarified that a force-kill from the App Switcher cancels all `NSURLSession` background tasks and cannot be overridden. Added a "Force-Kill Limitation" section to `PLATFORM_NOTES.md` with a summary table and a silent-push workaround
- **MMKV version downgraded to 1.3.16 (fix [#150](https://github.com/kesha-antonov/react-native-background-downloader/issues/150)):** changed the default from `2.2.4` to `1.3.16` (LTS) to restore `armeabi-v7a` (32-bit ARM) support dropped in MMKV 2.x. Added an MMKV version comparison table to the README
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

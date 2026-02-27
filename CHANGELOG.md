# Changelog

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
- **Upload Task App Restart Recovery:** Fixed upload tasks not being recoverable after app restart (#143). Added persistent storage for upload task configurations.

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
- **`maxRedirects` Option:** Configure maximum redirects for Android downloads (resolves #15)
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
- **Android OldArch Export:** Fixed module method export issue (#79)
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

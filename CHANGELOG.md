# Changelog

## v4.7.0

### ✨ Features

- iOS: per-task `isAllowedOverMetered` for downloads and uploads, kept across resume, retry and relaunch ([#182](https://github.com/kesha-antonov/react-native-background-downloader/pull/182)). Thanks [@elliotdickison](https://github.com/elliotdickison)
- Downloads cut off by the network continue where they stopped instead of failing. Android retries with a `Range` request (after 1, 2, 4, 8, 15 s); iOS continues from resume data and restarts foreground stalls after 20 s
- Types work with React Native 0.88's strict TypeScript API

### ⚠️ Behavior Changes

- A task without `isAllowedOverMetered` follows `setConfig({ allowsCellularAccess })`; before, it always allowed cellular
- iOS: cellular is enforced per request, so `isAllowedOverMetered: false` now waits for Wi-Fi; `setConfig({ allowsCellularAccess })` no longer recreates the session
- iOS: the background completion handler waits until every finished task has been passed to `completeHandler`, or 25 s at most. Call `completeHandler(jobId)` in both `done` and `error`
- `begin` fires once per task; Android sent it again on each new transfer
- No `begin` or `progress` after `pause()`, `stop()`, `done` or `error`
- `task.errorCode` is set when `error` fires
- iOS: `done`'s `location` is the saved file's path, also after an app update
- Android: a stalled transfer fails after 20 s without data (was 30), with `errorCode` -1001 on timeout
- Android 14+: a download that finished while the app wasn't running is listed as done, not paused

### 🐛 Bug Fixes

- Expo: `handleEventsForBackgroundURLSession` was added to the wrong class on the SDK 53+ template ([#181](https://github.com/kesha-antonov/react-native-background-downloader/pull/181)). Thanks [@elliotdickison](https://github.com/elliotdickison)
- Expo: the config plugin did nothing for projects with `AppDelegate.mm`
- iOS: a JS reload cancelled every download and upload
- iOS: `setConfig({ maxParallelDownloads })` cancelled running transfers
- iOS: resuming after changing headers saved a truncated file
- iOS: resuming a download paused without resume data did nothing
- iOS: download setup failures left the task waiting forever; they now emit `downloadFailed`
- iOS: restoring a suspended download replaced it with a task that never ran
- iOS: resume data from a system cancel was lost on the next launch
- iOS: multipart upload temp files were never deleted
- iOS: starting tasks often delayed or stopped progress events
- iOS: `getExistingDownloadTasks()` blocked events for 100 ms on every call
- iOS old architecture: events sent before the bridge was ready could arrive out of order
- iOS: build warning about the missing `setNotificationGroupingConfig`
- Android: a `DownloadManager` download was deleted right after `downloadComplete`
- Android: resuming a paused `DownloadManager` download saved only the remaining bytes
- Android: a failed `DownloadManager` download could send `downloadFailed` twice
- Android: a download whose connection closed early was reported as done
- Android: no `begin` or progress when the server rejects the HEAD request
- Android: completed downloads reported as failed on devices with an invalid external files directory
- Android: a paused download could be set back to downloading by a late size request
- Android: a download finished in the background was listed on every launch
- Android: restored uploads couldn't be resumed
- Android: `progressMinBytes` couldn't be set back to 0
- Android: multipart uploads garbled non-ASCII values, relative redirects lost the port and path, the wake lock wasn't renewed
- Android 14+: a download restarted after process death lost its headers and could append duplicate bytes
- Android 14+: Cancel on a download waiting for the network sent no event
- Android: a download recovered after a force-stop in its first seconds had no total size
- Android: fewer, cheaper progress reports on fast connections (64 KB reads)
- `setConfig` reset options that weren't passed
- `cleanup()` left iOS new-architecture listeners attached, doubling events
- `groupId`, `groupName` and `notificationTitle` passed to `createDownloadTask` were ignored
- Restoring a canceled task could raise an unhandled promise rejection
- Progress after a resume that restarts from zero was missing until it passed the old value
- Progress buffered for an inactive task went out with another task's batch

## v4.6.4

- A stopped task was never released from memory ([#180](https://github.com/kesha-antonov/react-native-background-downloader/pull/180)). Thanks [@rostwolke](https://github.com/rostwolke)

## v4.6.3

- No runtime changes. Releases publish from CI with npm provenance

## v4.6.2

- Android: build failed under AGP 9 with `Cannot add extension with name 'kotlin'` ([#178](https://github.com/kesha-antonov/react-native-background-downloader/pull/178)). Thanks [@gabrieldonadel](https://github.com/gabrieldonadel)

## v4.6.1

- iOS: build failed with `memset_s` on MMKVCore 2.4.1; the podspec now excludes 2.4.1 ([#175](https://github.com/kesha-antonov/react-native-background-downloader/issues/175)). If your `Podfile.lock` pins it, run `cd ios && pod update MMKV MMKVCore`. Thanks [@raginsky](https://github.com/raginsky)

## v4.6.0

### ✨ Features

- Android 14/15: new downloads run as user-initiated data transfer jobs, not only on 16+
- `maxParallelDownloads` applies on Android (default 4) ([#174](https://github.com/kesha-antonov/react-native-background-downloader/pull/174))
- Android 14+: opt-in completion notification, Cancel action and per-download titles via `metadata.notificationTitle` ([#165](https://github.com/kesha-antonov/react-native-background-downloader/pull/165)). Thanks [@HuuNguyen312](https://github.com/HuuNguyen312)
- Android unit tests (`yarn test:android`), run in CI

### 🐛 Bug Fixes

- Android 14+: concurrent downloads could share one notification
- Android 14+: colliding job IDs could kill concurrent downloads ([#172](https://github.com/kesha-antonov/react-native-background-downloader/pull/172))
- Android 14+: a large batch could crash on the JobScheduler quota; it now falls back to the foreground service ([#171](https://github.com/kesha-antonov/react-native-background-downloader/pull/171))
- Android 14+: a job that hadn't started was invisible and could be tracked twice ([#173](https://github.com/kesha-antonov/react-native-background-downloader/pull/173))
- Android: a download the service never started hung with no event; it fails after 15 s ([#174](https://github.com/kesha-antonov/react-native-background-downloader/pull/174))

## v4.5.9

- iOS: crash creating an upload on an invalidated session or a missing file; setup failures emit `uploadFailed` ([#170](https://github.com/kesha-antonov/react-native-background-downloader/issues/170))
- iOS: `getExistingDownloadTasks()` / `getExistingUploadTasks()` could crash instead of rejecting

## v4.5.8

- Android: `isAllowedOverMetered` was ignored for job and foreground-service downloads ([#169](https://github.com/kesha-antonov/react-native-background-downloader/issues/169)). Thanks [@lakshgk](https://github.com/lakshgk)
- Android: pausing a job that hadn't started was ignored
- Android: resuming with a stale byte offset could corrupt the file
- Android: the foreground service and its notification leaked after pause, resume and complete
- Android: downloads force-stopped before their first byte were lost

## v4.5.6

- iOS: `upload` without `fieldName` forced multipart for raw uploads ([#167](https://github.com/kesha-antonov/react-native-background-downloader/issues/167))

## v4.5.5

### ✨ Features

- iOS: downloads save while the device is locked; new `iosDataProtection` option ([#101](https://github.com/kesha-antonov/react-native-background-downloader/issues/101))
- Compiled `lib/` entry point, so Node 24 can `require` the package ([#113](https://github.com/kesha-antonov/react-native-background-downloader/issues/113))

### 🐛 Bug Fixes

- iOS: crash when a native method threw on the background queue ([#161](https://github.com/kesha-antonov/react-native-background-downloader/issues/161))
- iOS: crash creating a task on an invalidated session ([#157](https://github.com/kesha-antonov/react-native-background-downloader/issues/157), [#158](https://github.com/kesha-antonov/react-native-background-downloader/pull/158)). Thanks [@leogaletti](https://github.com/leogaletti)
- iOS: CocoaPods conflict with `react-native-mmkv` ([#162](https://github.com/kesha-antonov/react-native-background-downloader/issues/162))
- Android: `getExistingDownloadTasks()` missed active resumable downloads ([#164](https://github.com/kesha-antonov/react-native-background-downloader/issues/164))
- Android: active downloads were lost after a force-stop ([#159](https://github.com/kesha-antonov/react-native-background-downloader/issues/159))

## v4.5.4

- iOS: crash on the New Architecture when events fired before JS listened ([#153](https://github.com/kesha-antonov/react-native-background-downloader/pull/153)). Thanks [@isaacrowntree](https://github.com/isaacrowntree)
- Android: job downloads didn't start on VPN networks ([#154](https://github.com/kesha-antonov/react-native-background-downloader/issues/154))
- Android: job downloads didn't resume after an app restart ([#156](https://github.com/kesha-antonov/react-native-background-downloader/issues/156))
- A progress event could arrive after `done` or `error`
- Default MMKV is 1.3.16 again, for 32-bit ARM ([#150](https://github.com/kesha-antonov/react-native-background-downloader/issues/150))
- Docs: iOS force-kill limitation ([#155](https://github.com/kesha-antonov/react-native-background-downloader/issues/155))

## v4.5.3

- Android: notification grouping `mode: 'summaryOnly'`, with aggregate progress in the summary
- Android: completed downloads leave their notification group

## v4.5.2

- Update headers on a paused download with `task.setDownloadParams()`, now async, then `resume()`

## v4.5.1

- Android: internal refactor of the job service, no behavior change

## v4.5.0

### 💥 Breaking Changes

- Removed per-task `isNotificationVisible` and `notificationTitle`; use `setConfig({ showNotificationsEnabled, notificationsGrouping })`

### ✨ Features

- Android: global notification config, customizable "Paused" text, notification updates synced with `progressInterval`

### 🐛 Bug Fixes

- Android 14+: paused downloads kept downloading in the background after a restart
- Android: duplicate, stale or stuck notifications around pause and resume; notifications are removed when the app closes

## v4.4.5

- Android 14+: `stopTask()` didn't stop the download
- Android 14+: paused tasks were lost on restart
- Android: added the missing `ACCESS_NETWORK_STATE` permission
- `destination` is returned for paused downloads

## v4.4.4

- Expo plugin: fixed TypeScript types

## v4.4.3

- Expo plugin skips the MMKV dependency when `react-native-mmkv` is installed; `skipMmkvDependency` option

## v4.4.2

- Kotlin 2.0 (React Native 0.77+) build error

## v4.4.1

- Android: paused downloads are restored after a restart
- iOS: better pause and resume across restarts
- Upload tasks are recoverable after a restart ([#143](https://github.com/kesha-antonov/react-native-background-downloader/issues/143))

## v4.4.0

- Android 16+: downloads run as user-initiated data transfers (adds `RUN_USER_INITIATED_JOBS`)
- iOS: MMKV is no longer a hard pod dependency. Without `react-native-mmkv`, add `pod 'MMKV', '>= 1.0.0'`

## v4.2.0

> See the [Migration Guide](./MIGRATION.md).

- Android: `pause()` and `resume()` via `Range` requests, with a foreground service and wake lock
- `bytesTotal` is `-1` when the size is unknown
- Android: `COULD_NOT_FIND` on pause, leftover `.tmp` files, progress without `Content-Length`

## v4.1.0

> See the [Migration Guide](./MIGRATION.md).

- ⚠️ Android: MMKV is `compileOnly`. Without `react-native-mmkv`, add the MMKV dependency; the Expo plugin does it for you

## v4.0.0

> See the [Migration Guide](./MIGRATION.md).

### ⚠️ Breaking Changes

- `checkForExistingDownloads()` is `getExistingDownloadTasks()`
- `download()` is `createDownloadTask()`, and tasks start with `.start()`
- Source moved to `src/` with TypeScript types

### ✨ Features

- New Architecture (TurboModules) on iOS and Android
- Expo config plugin
- Android rewritten in Kotlin, with 16 KB page size support
- `maxRedirects` ([#15](https://github.com/kesha-antonov/react-native-background-downloader/issues/15)) and `progressMinBytes` options

### 🐛 Bug Fixes

- iOS pause and resume, React Native 0.78+ events, Android background downloads, progress with an unknown total, old-architecture Android export ([#79](https://github.com/kesha-antonov/react-native-background-downloader/issues/79)), and `react-native-mmkv` 4 support

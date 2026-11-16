# Migration Guide

This guide helps you upgrade between major versions of `@kesha-antonov/react-native-background-downloader`.

## Table of Contents

- [Migration Guide](#migration-guide)
  - [Table of Contents](#table-of-contents)
- [Migration Guide: v4.8.x to v5.0.0](#migration-guide-v48x-to-v500)
  - [Android Package Renamed](#android-package-renamed)
    - [Who Needs to Do Something](#who-needs-to-do-something)
    - [Migration Helper](#migration-helper)
    - [Manual Steps](#manual-steps)
    - [Downloads Running During the Update](#downloads-running-during-the-update)
- [Migration Guide: v4.1.x → v4.2.0](#migration-guide-v41x--v420)
  - [Android Pause/Resume Now Supported](#android-pauseresume-now-supported)
    - [Before (v4.1.x)](#before-v41x)
    - [After (v4.2.0)](#after-v420)
    - [How It Works on Android](#how-it-works-on-android)
    - [New Android Permissions](#new-android-permissions)
    - [Google Play Console Declaration Required](#google-play-console-declaration-required)
  - [bytesTotal Returns -1 for Unknown Sizes](#bytestotal-returns--1-for-unknown-sizes)
    - [Before (v4.1.x)](#before-v41x-1)
    - [After (v4.2.0)](#after-v420-1)
    - [Why This Change?](#why-this-change)
- [Migration Guide: v4.3.x → v4.4.0](#migration-guide-v43x--v440)
  - [iOS MMKV Dependency Change](#ios-mmkv-dependency-change)
    - [If you're using `react-native-mmkv`](#if-youre-using-react-native-mmkv)
    - [If you're NOT using `react-native-mmkv`](#if-youre-not-using-react-native-mmkv)
    - [Why This Change?](#why-this-change-1)
- [Migration Guide: v4.0.x → v4.1.0](#migration-guide-v40x--v410)
  - [MMKV Dependency Change](#mmkv-dependency-change)
    - [If you're using `react-native-mmkv`](#if-youre-using-react-native-mmkv-1)
    - [If you're NOT using `react-native-mmkv`](#if-youre-not-using-react-native-mmkv-1)
    - [Why This Change?](#why-this-change-2)
- [Migration Guide: v3.2.6 → v4.0.0](#migration-guide-v326--v400)
  - [Breaking Changes Overview](#breaking-changes-overview)
  - [Step-by-Step Migration](#step-by-step-migration)
    - [1. Update Import Path](#1-update-import-path)
    - [2. Rename API Methods](#2-rename-api-methods)
      - [`checkForExistingDownloads()` → `getExistingDownloadTasks()`](#checkforexistingdownloads--getexistingdownloadtasks)
    - [3. Update Download Creation](#3-update-download-creation)
      - [`download()` → `createDownloadTask()` + `.start()`](#download--createdownloadtask--start)
      - [Why This Change?](#why-this-change-3)
    - [4. Update Configuration](#4-update-configuration)
      - [New `progressMinBytes` Option](#new-progressminbytes-option)
  - [New Features](#new-features)
    - [`maxRedirects` Option (Android)](#maxredirects-option-android)
    - [Task States](#task-states)
    - [TypeScript Support](#typescript-support)
  - [Expo Projects](#expo-projects)
    - [Setup](#setup)
    - [Rebuild Required](#rebuild-required)
  - [Quick Migration Checklist](#quick-migration-checklist)
  - [Need Help?](#need-help)

---

# Migration Guide: v4.8.x to v5.0.0

## Android Package Renamed

The Android code moved from the `com.eko` package to `com.kesha_antonov`. JavaScript, TypeScript and iOS APIs don't change.

| | Before (v4.x) | After (v5.0.0) |
|---|---|---|
| React package | `com.eko.RNBackgroundDownloaderPackage` | `com.kesha_antonov.RNBackgroundDownloaderPackage` |
| Other classes | `com.eko.*` | `com.kesha_antonov.*` |
| Gradle namespace | `com.eko` | `com.kesha_antonov` |
| ProGuard rules | added by you | shipped with the library |

### Who Needs to Do Something

Most apps don't need any change. Autolinking (React Native CLI and Expo) picks up the new package by itself; rebuild the app after upgrading:

```bash
# Expo
npx expo prebuild --clean
# React Native CLI
cd android && ./gradlew clean
```

Change your code only if it names a class of the library:

- `MainApplication` registers `com.eko.RNBackgroundDownloaderPackage` by hand (autolinking turned off for this library).
- `android/app/proguard-rules.pro`, or `extraProguardRules` of `expo-build-properties` in the Expo app config, has `-keep class com.eko...` rules. Those rules no longer match anything; delete them, since the library now ships its own rules.
- Native code of your app imports anything from `com.eko`.

### Migration Helper

The package has a command that finds these references and changes them. Run it from your app's root folder:

```bash
# Lists what would change
npx react-native-background-downloader migrate-package

# Changes the files
npx react-native-background-downloader migrate-package --write
```

It scans Java, Kotlin, Gradle, ProGuard and manifest files and the Expo app config (`app.json`, `app.config.*`), skipping `node_modules`, `ios` and build folders. Only names of this library are changed (for example `com.eko.RNBGDTaskConfig` or `com.eko.uidt.*`). Other lines that mention `com.eko`, such as a `-keep class com.eko.** { *; }` wildcard or your app's own `com.eko` package, are listed for you to check by hand.

Review the result with `git diff`, then delete the library's keep rules from your ProGuard file: they are no longer needed.

### Manual Steps

Without the helper, replace `com.eko.` with `com.kesha_antonov.` wherever it names this library:

```diff
- import com.eko.RNBackgroundDownloaderPackage;
+ import com.kesha_antonov.RNBackgroundDownloaderPackage;
```

and remove the library's rules from `proguard-rules.pro`:

```diff
- -keep class com.eko.RNBGDTaskConfig { *; }
- -keep class com.eko.RNBGDUploadTaskConfig { *; }
- -keep class com.eko.UploadPartResult { *; }
```

### Downloads Running During the Update

Downloads, their progress and their saved state carry over to v5.0.0. This was checked by updating a v4.8.0 app to v5.0.0 in the middle of downloads, on an Android 16 phone and an iOS simulator:

- **iOS:** running downloads keep going in the background session, and paused ones come back paused with their progress. Their saved state moves from the old storage keys to the new ones on the first launch.
- **Android, paused downloads:** come back paused and resume from where they stopped.
- **Android 14 and later, running downloads:** the download job keeps running after the update. The library keeps a service under the old `com.eko` class name for jobs scheduled by v4.x. Android usually holds a download job that an app update interrupted until the user opens the app again.
- **Android 14 and later, one-time restart:** a download that v4.x started and that was running at the moment of the update starts again from the beginning, because v4.x didn't record its progress while downloading. From v5.0.0 on, progress is saved every few seconds, so later updates continue from where they were.

Call `getExistingDownloadTasks()` on startup as before to re-attach your handlers.

---

# Migration Guide: v4.1.x → v4.2.0

## Android Pause/Resume Now Supported

In v4.2.0, Android now fully supports `task.pause()` and `task.resume()` methods! You no longer need platform-specific code for pause/resume functionality.

### Before (v4.1.x)

```javascript
import { Platform } from 'react-native'

async function pauseDownload() {
  if (Platform.OS === 'ios') {
    await task.pause()
  } else {
    // Android didn't support pause - had to stop and restart
    console.log('Pause not supported on Android')
  }
}
```

### After (v4.2.0)

```javascript
// Works on both iOS and Android!
async function pauseDownload() {
  await task.pause()
}

async function resumeDownload() {
  await task.resume()
}
```

### How It Works on Android

Android's `DownloadManager` doesn't natively support pause/resume. v4.2.0 implements this using:

1. **HTTP Range Headers:** When resuming, the library uses the `Range` header to request only the remaining bytes
2. **Foreground Service:** A `ResumableDownloadService` runs as a foreground service to ensure downloads continue in the background
3. **Temp Files:** Progress is saved to a `.tmp` file which is renamed upon completion

**Server Requirement:** The server must support HTTP Range requests for resume to work correctly. If the server doesn't support range requests, the download will restart from the beginning.

### New Android Permissions

The library now requires additional permissions that are automatically added:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

These are declared in the library's `AndroidManifest.xml` and will be merged automatically.

### Google Play Console Declaration Required

Because the library uses Foreground Service permissions, Google Play requires you to declare this in the Play Console when publishing your app. Without this declaration, you will see the following error:

> "You must let us know whether your app uses any Foreground Service permissions."

**To resolve this, complete these steps in the Google Play Console:**

1. Go to your app in the Google Play Console
2. Navigate to **App content** → **Foreground Service**
3. Select **Yes** when asked if your app uses Foreground Service permissions
4. Choose **Data sync** as the Foreground Service type
5. Select **Network processing** as the task
6. Provide a justification such as:

   > "This app downloads files in the background using a foreground service with a user-visible notification. The foreground service ensures downloads continue reliably when the app is in the background or when the device is under memory pressure. Users initiate downloads and can see download progress via the notification."

**Note:** This is a Play Console compliance step only—no additional code changes are required.

## bytesTotal Returns -1 for Unknown Sizes

When a server doesn't provide a `Content-Length` header, `bytesTotal` now returns `-1` instead of `0`.

### Before (v4.1.x)

```javascript
.progress(({ bytesDownloaded, bytesTotal }) => {
  // bytesTotal was 0 when unknown, same as "zero bytes"
  const percent = bytesTotal > 0 ? (bytesDownloaded / bytesTotal) * 100 : 0
})
```

### After (v4.2.0)

```javascript
.progress(({ bytesDownloaded, bytesTotal }) => {
  if (bytesTotal === -1) {
    // Server didn't provide Content-Length - show indeterminate progress
    console.log(`Downloaded ${bytesDownloaded} bytes (total unknown)`)
  } else if (bytesTotal > 0) {
    // Normal case - show percentage
    const percent = (bytesDownloaded / bytesTotal) * 100
    console.log(`Downloaded ${percent.toFixed(1)}%`)
  }
})
```

### Why This Change?

- `-1` clearly indicates "unknown" vs `0` which could mean "zero bytes"
- Allows apps to show indeterminate progress indicators when appropriate
- Consistent with common conventions in download APIs

---

# Migration Guide: v4.3.x → v4.4.0

## iOS MMKV Dependency Change

In v4.4.0, the MMKV dependency on iOS was removed from the podspec to prevent symbol conflicts when apps also use `react-native-mmkv`.

### If you're using `react-native-mmkv`

**No action required!** The `react-native-mmkv` package already provides the required MMKV dependency (via MMKVCore pod), so everything will work automatically.

### If you're NOT using `react-native-mmkv`

You must explicitly add the MMKV dependency to your `ios/Podfile`:

```ruby
pod 'MMKV', '>= 1.0.0', '!= 2.4.1'
```

Then run `cd ios && pod install`.

> **Note:** this manual step only applies to v4.4.0-v4.5.4. The podspec declares MMKV again since v4.5.5, so on current releases you can drop this line entirely.
>
> The `!= 2.4.1` exclusion matters either way: MMKVCore 2.4.1 does not compile on Apple platforms (`use of undeclared identifier 'memset_s'`, [Tencent/MMKV#1675](https://github.com/Tencent/MMKV/issues/1675)). If you pin MMKV yourself, also pin `pod 'MMKVCore', '!= 2.4.1'` - `MMKV 2.4.0` depends on `MMKVCore (~> 2.4.0)`, which would otherwise still resolve to the broken 2.4.1.

### Why This Change?

Previously, both this library and `react-native-mmkv` would include their own MMKV dependencies, causing symbol conflicts and crashes (EXC_BAD_ACCESS) on iOS. By removing the hard dependency, this library now relies on the app to provide the MMKV dependency, which:

1. Eliminates symbol conflicts with `react-native-mmkv`
2. Allows the app to control the MMKV version
3. Fixes crashes on iOS when both libraries are used together

---

# Migration Guide: v4.0.x → v4.1.0

## MMKV Dependency Change

In v4.1.0, the MMKV dependency was changed from `implementation` to `compileOnly` to prevent duplicate class errors when apps also use `react-native-mmkv`.

### If you're using `react-native-mmkv`

**No action required!** The `react-native-mmkv` package already provides the MMKV dependency, so everything will work automatically.

### If you're NOT using `react-native-mmkv`

You must explicitly add the MMKV dependency to your app's `android/app/build.gradle`:

```gradle
dependencies {
    // ... other dependencies
    implementation 'com.tencent:mmkv-shared:2.2.4'  // or newer
}
```

**Note:** MMKV 2.0.0+ is required for Android 15+ support (16KB memory page sizes).

### Why This Change?

Previously, both this library and `react-native-mmkv` would each bundle their own copy of MMKV, causing Gradle to fail with "duplicate class" errors during build. By using `compileOnly`, this library now relies on the app to provide the MMKV dependency, which:

1. Eliminates duplicate class conflicts
2. Allows the app to control the MMKV version
3. Reduces APK size when `react-native-mmkv` is already included

---

# Migration Guide: v3.2.6 → v4.0.0

This section helps you upgrade from v3.2.6 to v4.0.0.

## Breaking Changes Overview

| v3.2.6 | v4.0.0 | Notes |
|--------|--------|-------|
| `checkForExistingDownloads()` | `getExistingDownloadTasks()` | Renamed for clarity |
| `download(options)` | `createDownloadTask(options)` | Returns task in PENDING state |
| Downloads start immediately | Must call `.start()` | Explicit control over when downloads begin |
| No `progressMinBytes` | `progressMinBytes` in config | New option for hybrid progress reporting |

---

## Step-by-Step Migration

### 1. Update Import Path

The internal source structure changed from `lib/` to `src/`, but the public API imports remain the same:

```javascript
// No changes needed - imports work the same way
import RNBackgroundDownloader from '@kesha-antonov/react-native-background-downloader'

// Or named imports
import {
  setConfig,
  createDownloadTask,
  getExistingDownloadTasks,
  directories
} from '@kesha-antonov/react-native-background-downloader'
```

### 2. Rename API Methods

#### `checkForExistingDownloads()` → `getExistingDownloadTasks()`

**Before (v3.2.6):**
```javascript
const existingTasks = await RNBackgroundDownloader.checkForExistingDownloads()
```

**After (v4.0.0):**
```javascript
const existingTasks = await RNBackgroundDownloader.getExistingDownloadTasks()
```

### 3. Update Download Creation

This is the most significant change. Downloads no longer start immediately.

#### `download()` → `createDownloadTask()` + `.start()`

**Before (v3.2.6):**
```javascript
// Download started immediately upon calling download()
const task = RNBackgroundDownloader.download({
  id: 'my-download',
  url: 'https://example.com/file.zip',
  destination: `${RNBackgroundDownloader.directories.documents}/file.zip`,
})
  .begin(({ expectedBytes }) => {
    console.log(`Starting download, expected ${expectedBytes} bytes`)
  })
  .progress(({ bytesDownloaded, bytesTotal }) => {
    console.log(`Progress: ${bytesDownloaded}/${bytesTotal}`)
  })
  .done(() => {
    console.log('Download complete!')
  })
  .error((error) => {
    console.log('Download error:', error)
  })
```

**After (v4.0.0):**
```javascript
// Create the task (in PENDING state)
const task = createDownloadTask({
  id: 'my-download',
  url: 'https://example.com/file.zip',
  destination: `${RNBackgroundDownloader.directories.documents}/file.zip`,
})
  .begin(({ expectedBytes }) => {
    console.log(`Starting download, expected ${expectedBytes} bytes`)
  })
  .progress(({ bytesDownloaded, bytesTotal }) => {
    console.log(`Progress: ${bytesDownloaded}/${bytesTotal}`)
  })
  .done(() => {
    console.log('Download complete!')
  })
  .error((error) => {
    console.log('Download error:', error)
  })

// Explicitly start the download when ready
task.start()
```

#### Why This Change?

The explicit `.start()` pattern gives you more control:

```javascript
// Set up multiple downloads first
const tasks = urls.map((url, index) =>
  createDownloadTask({
    id: `download-${index}`,
    url,
    destination: `${directories.documents}/file-${index}`,
  })
    .progress(({ bytesDownloaded, bytesTotal }) => {
      updateProgress(index, bytesDownloaded, bytesTotal)
    })
    .done(() => handleComplete(index))
    .error((err) => handleError(index, err))
)

// Start them all at once, or conditionally
tasks.forEach(task => task.start())

// Or start based on some condition
if (isWifiConnected) {
  tasks.forEach(task => task.start())
}
```

### 4. Update Configuration

#### New `progressMinBytes` Option

**Before (v3.2.6):**
```javascript
RNBackgroundDownloader.setConfig({
  headers: { 'Authorization': 'Bearer token' },
  progressInterval: 1000,
  isLogsEnabled: true,
})
```

**After (v4.0.0):**
```javascript
RNBackgroundDownloader.setConfig({
  headers: { 'Authorization': 'Bearer token' },
  progressInterval: 1000,        // Time-based interval (ms)
  progressMinBytes: 1024 * 1024, // NEW: Byte-based threshold (default: 1MB)
  isLogsEnabled: true,
})
```

The `progressMinBytes` option creates hybrid progress reporting - callbacks fire when EITHER:
- The time interval has passed, OR
- The minimum bytes have been downloaded

Set `progressMinBytes: 0` to disable byte-based throttling and use time-only (like v3.2.6 behavior).

---

## New Features

### `maxRedirects` Option (Android)

Configure maximum HTTP redirects for Android downloads:

```javascript
const task = createDownloadTask({
  id: 'my-download',
  url: 'https://example.com/file.zip',
  destination: `${directories.documents}/file.zip`,
  maxRedirects: 5, // NEW: Limit redirects (Android only)
})
```

### Task States

Tasks now have a clearer state machine:

```javascript
const task = createDownloadTask({ ... })

console.log(task.state) // 'PENDING' - before start()

task.start()
// task.state will transition: 'PENDING' → 'DOWNLOADING' → 'DONE'

// Other possible states:
// - 'PAUSED' - after task.pause()
// - 'FAILED' - on error
// - 'STOPPED' - after task.stop()
```

### TypeScript Support

v4.0.0 includes full TypeScript definitions:

```typescript
import RNBackgroundDownloader, {
  DownloadTask,
  Config,
  BeginHandlerParams,
  ProgressHandlerParams,
  DoneHandlerParams,
  ErrorHandlerParams,
} from '@kesha-antonov/react-native-background-downloader'

const task: DownloadTask = createDownloadTask({
  id: 'typed-download',
  url: 'https://example.com/file.zip',
  destination: '/path/to/file.zip',
})
  .begin(({ expectedBytes, headers }: BeginHandlerParams) => {
    console.log(`Expected: ${expectedBytes}`)
  })
  .progress(({ bytesDownloaded, bytesTotal }: ProgressHandlerParams) => {
    const percent = (bytesDownloaded / bytesTotal) * 100
  })
  .done(({ bytesDownloaded, bytesTotal }: DoneHandlerParams) => {
    console.log('Complete!')
  })
  .error(({ error, errorCode }: ErrorHandlerParams) => {
    console.error(`Error ${errorCode}: ${error}`)
  })

task.start()
```

---

## Expo Projects

v4.0.0 includes an Expo config plugin for automatic iOS setup.

### Setup

**app.json / app.config.js:**
```json
{
  "expo": {
    "plugins": [
      "@kesha-antonov/react-native-background-downloader"
    ]
  }
}
```

The plugin automatically:
- Adds the required `handleEventsForBackgroundURLSession` method to AppDelegate
- Sets up the bridging header for Swift projects (RN 0.77+)

### Rebuild Required

After adding the plugin, rebuild your app:

```bash
npx expo prebuild --clean
npx expo run:ios
npx expo run:android
```

---

## Quick Migration Checklist

- [ ] Update package to v4.0.0
- [ ] Replace `checkForExistingDownloads()` with `getExistingDownloadTasks()`
- [ ] Replace `download()` with `createDownloadTask()`
- [ ] Add `.start()` call after setting up task handlers
- [ ] (Optional) Configure `progressMinBytes` in `setConfig()`
- [ ] (Optional) Use `maxRedirects` for Android if needed
- [ ] (Expo) Add the config plugin to app.json
- [ ] Rebuild your app (pod install for iOS, rebuild for Android)

---

## Need Help?

If you encounter issues during migration, please:
1. Check the [README](./README.md) for updated documentation
2. Search [existing issues](https://github.com/kesha-antonov/react-native-background-downloader/issues)
3. Open a new issue with details about your setup

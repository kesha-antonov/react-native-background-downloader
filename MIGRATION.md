# Migration Guide

This guide helps you upgrade between major versions of `@fivecar/react-native-background-downloader`.

## Table of Contents

- [Migration Guide](#migration-guide)
  - [Table of Contents](#table-of-contents)
- [Migration Guide: v4.5.x → v5.0.0](#migration-guide-v45x--v500)
  - [MMKV Removed](#mmkv-removed)
    - [Before (v4.5.x)](#before-v45x)
    - [After (v5.0.0)](#after-v500)
    - [Why This Change?](#why-this-change-4)
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

# Migration Guide: v4.5.x → v5.0.0

## MMKV Removed

Starting in v5.0.0, the library no longer depends on MMKV on either platform.

### Before (v4.5.x)

- **Android:** required a `com.tencent:mmkv-shared` (or `react-native-mmkv`-provided) Gradle dependency; fell back to `SharedPreferences` only if MMKV failed to initialize.
- **iOS:** required the `MMKV` CocoaPod; no fallback existed.
- The Expo config plugin injected/skipped the Android MMKV Gradle dependency automatically.

### After (v5.0.0)

- **Android:** uses `SharedPreferences` exclusively. No MMKV Gradle dependency, no `mmkvVersion`/`skipMmkvDependency` plugin options.
- **iOS:** uses `NSUserDefaults` exclusively. No `MMKV` CocoaPod dependency.
- **No action required** for the storage change itself — just update to v5.0.0 and rebuild (`pod install` on iOS; no Gradle changes needed on Android).
- **Important:** this is a clean cutover, not a migration. Any downloads that were paused, active, or queued under the old MMKV-backed storage will **not** carry over — after upgrading, call `getExistingDownloadTasks()` / `getExistingUploadTasks()` as usual, but be aware it will return an empty list for any transfers that were only recorded in the old storage. Re-initiate those downloads/uploads as needed.
- If you were passing `mmkvVersion` or `skipMmkvDependency` to the Expo config plugin, remove them — they are no longer recognized options.
- If you had manually added `implementation 'com.tencent:mmkv-shared:...'` (Android) or `pod 'MMKV', '>= 1.0.0'` (iOS) solely for this library, you may remove it (unless another dependency, like `react-native-mmkv`, still needs it).

### Why This Change?

MMKV caused real integration friction: duplicate-class errors with `react-native-mmkv`, the 32-bit ARM (`armeabi-v7a`) support drop in MMKV 2.x, and past `EXC_BAD_ACCESS` crashes from symbol conflicts on iOS. Both `SharedPreferences` and `NSUserDefaults` are built into their platforms, need no version coordination with other dependencies, and remove an entire class of setup problems for consumers.

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
pod 'MMKV', '>= 1.0.0'
```

Then run `cd ios && pod install`.

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
import RNBackgroundDownloader from '@fivecar/react-native-background-downloader'

// Or named imports
import {
  setConfig,
  createDownloadTask,
  getExistingDownloadTasks,
  directories
} from '@fivecar/react-native-background-downloader'
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
} from '@fivecar/react-native-background-downloader'

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
      "@fivecar/react-native-background-downloader"
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
2. Search [existing issues](https://github.com/fivecar/react-native-background-downloader/issues)
3. Open a new issue with details about your setup

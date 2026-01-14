# Migration Guide

This guide helps you upgrade between major versions of `@kesha-antonov/react-native-background-downloader`.

## Table of Contents

- [Migration Guide](#migration-guide)
  - [Table of Contents](#table-of-contents)
- [Migration Guide: v4.1.x → v4.2.0](#migration-guide-v41x--v420)
  - [Android Pause/Resume Now Supported](#android-pauseresume-now-supported)
  - [bytesTotal Returns -1 for Unknown Sizes](#bytestotal-returns--1-for-unknown-sizes)
- [Migration Guide: v4.0.x → v4.1.0](#migration-guide-v40x--v410)
  - [MMKV Dependency Change](#mmkv-dependency-change)
    - [If you're using `react-native-mmkv`](#if-youre-using-react-native-mmkv)
    - [If you're NOT using `react-native-mmkv`](#if-youre-not-using-react-native-mmkv)
    - [Why This Change?](#why-this-change)
- [Migration Guide: v3.2.6 → v4.0.0](#migration-guide-v326--v400)
  - [Breaking Changes Overview](#breaking-changes-overview)
  - [Step-by-Step Migration](#step-by-step-migration)
    - [1. Update Import Path](#1-update-import-path)
    - [2. Rename API Methods](#2-rename-api-methods)
      - [`checkForExistingDownloads()` → `getExistingDownloadTasks()`](#checkforexistingdownloads--getexistingdownloadtasks)
    - [3. Update Download Creation](#3-update-download-creation)
      - [`download()` → `createDownloadTask()` + `.start()`](#download--createdownloadtask--start)
      - [Why This Change?](#why-this-change-1)
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

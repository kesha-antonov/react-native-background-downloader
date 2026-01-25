<p align="center">
  <img width="300" src="https://github.com/user-attachments/assets/25e89808-9eb7-42b2-8031-b48d8c24796c" />
</p>

<p align="center">
  <a href="https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader"><img src="https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader.svg" alt="npm version"></a>
  <a href="https://www.npmjs.com/package/@kesha-antonov/react-native-background-downloader"><img src="https://img.shields.io/npm/dm/@kesha-antonov/react-native-background-downloader.svg" alt="npm downloads"></a>
  <a href="https://github.com/kesha-antonov/react-native-background-downloader/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="license"></a>
  <img src="https://img.shields.io/badge/platforms-iOS%20%7C%20Android-lightgrey.svg" alt="platforms">
  <img src="https://img.shields.io/badge/TypeScript-supported-blue.svg" alt="TypeScript">
  <img src="https://img.shields.io/badge/Expo-compatible-000020.svg" alt="Expo compatible">
  <img src="https://img.shields.io/badge/New%20Architecture-supported-green.svg" alt="New Architecture">
</p>

<h1 align="center">React Native Background Downloader</h1>

<p align="center">
  Download and upload large files on iOS & Android — even when your app is in the background or terminated.
</p>

---

## ✨ Features

- 📥 **Background Downloads** - Downloads continue even when app is in background or terminated
- 📤 **Background Uploads** - Upload files reliably in the background
- ⏸️ **Pause/Resume** - Full pause and resume support on both iOS and Android
- 🔄 **Re-attach to Downloads** - Reconnect to ongoing downloads after app restart
- 📊 **Progress Tracking** - Real-time progress updates with customizable intervals
- 🔒 **Custom Headers** - Support for authentication and custom request headers
- 📱 **Expo Support** - Config plugin for easy Expo integration
- ⚡ **New Architecture** - Full TurboModules support for React Native
- 📝 **TypeScript** - Complete TypeScript definitions included

## 💡 Why?

**The Problem:** Standard network requests in React Native are tied to your app's lifecycle. When the user switches to another app or the OS terminates your app to free memory, your downloads stop. For small files this is fine, but for large files (videos, podcasts, documents) this creates a frustrating user experience.

**The Solution:** Both iOS and Android provide system-level APIs for background file transfers:
- **iOS:** [`NSURLSession`](https://developer.apple.com/documentation/foundation/url_loading_system/downloading_files_in_the_background) - handles downloads in a separate process, continuing even after your app is terminated
- **Android:** A combination of [`DownloadManager`](https://developer.android.com/reference/android/app/DownloadManager) for system-managed downloads, [Foreground Services](https://developer.android.com/develop/background-work/services/foreground-services) for pause/resume support, and [MMKV](https://github.com/Tencent/MMKV) for persistent state storage

**The Challenge:** These APIs are powerful but complex. Downloads run in a separate process, so your app might restart from scratch while downloads are still in progress. Keeping your UI in sync with background downloads requires careful state management.

**This Library:** `@kesha-antonov/react-native-background-downloader` wraps these native APIs in a simple, unified JavaScript interface. Start a download, close your app, reopen it hours later, and seamlessly reconnect to your ongoing downloads with a single function call.

## 📖 Table of Contents

- [✨ Features](#-features)
- [💡 Why?](#-why)
- [📖 Table of Contents](#-table-of-contents)
- [📋 Requirements](#-requirements)
- [📦 Installation](#-installation)
  - [Expo Projects](#expo-projects)
  - [Bare React Native Projects](#bare-react-native-projects)
- [🚀 Usage](#-usage)
  - [Downloading a file](#downloading-a-file)
  - [Re-Attaching to background tasks](#re-attaching-to-background-tasks)
- [⚙️ Advanced Configuration](#️-advanced-configuration)
    - [Max Parallel Downloads (iOS only)](#max-parallel-downloads-ios-only)
    - [Cellular/WiFi Restrictions](#cellularwifi-restrictions)
- [📚 API](#-api)
  - [Quick Reference](#quick-reference)
- [📱 Platform Notes](#-platform-notes)
- [❓ Troubleshooting](#-troubleshooting)
- [🧪 Example App](#-example-app)
- [💡 Use Cases](#-use-cases)
- [🔄 Migration Guide](#-migration-guide)
- [🤝 Contributing](#-contributing)
  - [Development Setup](#development-setup)
- [👥 Authors](#-authors)
- [📄 License](#-license)

## 📋 Requirements

| Requirement | Version |
|-------------|--------|
| React Native | >= 0.70.0 |
| iOS | >= 15.1 |
| Android | API 24+ (Android 7.0) |
| Expo | SDK 50+ (with config plugin) |

> **Note:** For older React Native versions (0.57.0 - 0.69.x), use version 2.x of this library.

## 📦 Installation

### Expo Projects

**Step 1:** Install the package

```bash
npx expo install @kesha-antonov/react-native-background-downloader
```

**Step 2:** Add the config plugin to your `app.json` or `app.config.js`:

```json
{
  "expo": {
    "plugins": [
      "@kesha-antonov/react-native-background-downloader"
    ]
  }
}
```

<details>
<summary><strong>Plugin Options (optional)</strong></summary>

```js
// app.config.js
export default {
  expo: {
    plugins: [
      ["@kesha-antonov/react-native-background-downloader", {
        mmkvVersion: "2.2.4",  // Customize MMKV version on Android
        skipMmkvDependency: true  // Skip if you want to add MMKV manually
      }]
    ]
  }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mmkvVersion` | string | `'2.2.4'` | The version of [MMKV](https://github.com/Tencent/MMKV/releases) to use on Android. |
| `skipMmkvDependency` | boolean | `false` | Skip adding MMKV dependency. Set to `true` if you're using [react-native-mmkv](https://github.com/mrousavy/react-native-mmkv) to avoid duplicate class errors. The plugin auto-detects `react-native-mmkv` but you can use this option to explicitly skip. |

</details>

**Step 3:** Rebuild your app

```bash
npx expo prebuild --clean
npx expo run:ios   # or npx expo run:android
```

The plugin automatically handles:
- **iOS:** Adding the required `handleEventsForBackgroundURLSession` method to AppDelegate
- **Android:** Adding the required MMKV dependency

---

### Bare React Native Projects

**Step 1:** Install the package

**yarn:**
```bash
yarn add @kesha-antonov/react-native-background-downloader
```

**npm:**
```bash
npm install @kesha-antonov/react-native-background-downloader
```

**Step 2:** Install iOS pods

**yarn:**
```bash
cd ios && pod install && cd ..
```

**npm:**
```bash
cd ios && pod install && cd ..
```

**Step 3:** Configure iOS AppDelegate

<details>
<summary><strong>React Native 0.77+ (Swift)</strong></summary>

In your project bridging header file (e.g. `ios/{projectName}-Bridging-Header.h`):

```objc
#import <RNBackgroundDownloader.h>
```

In your `AppDelegate.swift`:

```swift
func application(
  _ application: UIApplication,
  handleEventsForBackgroundURLSession identifier: String,
  completionHandler: @escaping () -> Void
) {
  RNBackgroundDownloader.setCompletionHandlerWithIdentifier(identifier, completionHandler: completionHandler)
}
```

</details>

<details>
<summary><strong>React Native < 0.77 (Objective-C)</strong></summary>

In your `AppDelegate.m`:

```objc
#import <RNBackgroundDownloader.h>

- (void)application:(UIApplication *)application handleEventsForBackgroundURLSession:(NSString *)identifier completionHandler:(void (^)(void))completionHandler
{
  [RNBackgroundDownloader setCompletionHandlerWithIdentifier:identifier completionHandler:completionHandler];
}
```

</details>

**Step 4:** Configure Android MMKV dependency

Add MMKV to your `android/app/build.gradle`:

```gradle
dependencies {
    implementation 'com.tencent:mmkv-shared:2.2.4'
}
```

> **Note:** If you're already using [react-native-mmkv](https://github.com/mrousavy/react-native-mmkv) in your project, skip this step — it already includes MMKV.

## 🚀 Usage

### Downloading a file

```javascript
import { Platform } from 'react-native'
import { createDownloadTask, completeHandler, directories } from '@kesha-antonov/react-native-background-downloader'

const jobId = 'file123'

let task = createDownloadTask({
  id: jobId,
  url: 'https://link-to-very.large/file.zip',
  destination: `${directories.documents}/file.zip`,
  metadata: {}
}).begin(({ expectedBytes, headers }) => {
  console.log(`Going to download ${expectedBytes} bytes!`)
}).progress(({ bytesDownloaded, bytesTotal }) => {
  console.log(`Downloaded: ${bytesDownloaded / bytesTotal * 100}%`)
}).done(({ bytesDownloaded, bytesTotal }) => {
  console.log('Download is done!', { bytesDownloaded, bytesTotal })

  // PROCESS YOUR STUFF

  // FINISH DOWNLOAD JOB
  completeHandler(jobId)
}).error(({ error, errorCode }) => {
  console.log('Download canceled due to error: ', { error, errorCode });
})

// starts download
task.start()

// ...later

// Pause the task
await task.pause()

// Resume after pause
await task.resume()

// Cancel the task
await task.stop()
```

### Re-Attaching to background tasks

The killer feature of this library: **reconnect to downloads and uploads that continued running while your app was closed**, or **resume paused tasks from a previous session**.

When the OS terminates your app to free memory, background transfers keep running. When your app restarts, call `getExistingDownloadTasks()` or `getExistingUploadTasks()` to get back in sync. Paused tasks are also preserved and can be resumed with `task.resume()`.

> **💡 Tip:** Use meaningful task IDs (not random UUIDs) so you can match tasks to your UI components after restart.

**Downloads:**

```javascript
import { getExistingDownloadTasks } from '@kesha-antonov/react-native-background-downloader'

const lostTasks = await getExistingDownloadTasks()

for (const task of lostTasks) {
  console.log(`Found download: ${task.id}`)

  task.progress(({ bytesDownloaded, bytesTotal }) => {
    console.log(`Downloaded: ${bytesDownloaded / bytesTotal * 100}%`)
  }).done(({ location, bytesDownloaded, bytesTotal }) => {
    console.log('Download complete!', { location, bytesDownloaded, bytesTotal })
  }).error(({ error, errorCode }) => {
    console.log('Download failed:', { error, errorCode })
  })
}
```

**Uploads:**

```javascript
import { getExistingUploadTasks } from '@kesha-antonov/react-native-background-downloader'

const lostUploads = await getExistingUploadTasks()

for (const task of lostUploads) {
  console.log(`Found upload: ${task.id}`)

  task.progress(({ bytesUploaded, bytesTotal }) => {
    console.log(`Uploaded: ${bytesUploaded / bytesTotal * 100}%`)
  }).done(({ responseCode, responseBody }) => {
    console.log('Upload complete!', { responseCode, responseBody })
  }).error(({ error, errorCode }) => {
    console.log('Upload failed:', { error, errorCode })
  })
}
```

<details>
<summary><strong>Uploading a file</strong></summary>

```javascript
import { Platform } from 'react-native'
import { createUploadTask, completeHandler, directories } from '@kesha-antonov/react-native-background-downloader'

const jobId = 'upload123'

let task = createUploadTask({
  id: jobId,
  url: 'https://your-server.com/upload',
  source: `${directories.documents}/photo.jpg`,
  method: 'POST', // or 'PUT', 'PATCH'
  fieldName: 'file', // multipart form field name
  mimeType: 'image/jpeg',
  parameters: {
    userId: '123',
    description: 'My photo'
  },
  metadata: {}
}).begin(({ expectedBytes }) => {
  console.log(`Going to upload ${expectedBytes} bytes!`)
}).progress(({ bytesUploaded, bytesTotal }) => {
  console.log(`Uploaded: ${bytesUploaded / bytesTotal * 100}%`)
}).done(({ responseCode, responseBody, bytesUploaded, bytesTotal }) => {
  console.log('Upload is done!', { responseCode, responseBody })

  // PROCESS YOUR STUFF

  // FINISH UPLOAD JOB
  completeHandler(jobId)
}).error(({ error, errorCode }) => {
  console.log('Upload canceled due to error: ', { error, errorCode })
})

// starts upload
task.start()

// ...later

// Pause the task (platform support may vary)
await task.pause()

// Resume after pause
await task.resume()

// Cancel the task
await task.stop()
```

</details>

## ⚙️ Advanced Configuration

<details>
<summary><strong>Using custom headers</strong></summary>
If you need to send custom headers with your download request, you can do in it 2 ways:

1) Globally using `setConfig()`:
```javascript
import { setConfig } from '@kesha-antonov/react-native-background-downloader'

setConfig({
  headers: {
    Authorization: 'Bearer 2we$@$@Ddd223',
  }
})
```
This way, all downloads with have the given headers.

2) Per download by passing a headers object in the options of `createDownloadTask()`:
```javascript
import { createDownloadTask, directories } from '@kesha-antonov/react-native-background-downloader'

const task = createDownloadTask({
  id: 'file123',
  url: 'https://link-to-very.large/file.zip'
  destination: `${directories.documents}/file.zip`,
  headers: {
    Authorization: 'Bearer 2we$@$@Ddd223'
  }
}).begin(({ expectedBytes, headers }) => {
  console.log(`Going to download ${expectedBytes} bytes!`)
}).progress(({ bytesDownloaded, bytesTotal }) => {
  console.log(`Downloaded: ${bytesDownloaded / bytesTotal * 100}%`)
}).done(({ location, bytesDownloaded, bytesTotal }) => {
  console.log('Download is done!', { location, bytesDownloaded, bytesTotal })
}).error(({ error, errorCode }) => {
  console.log('Download canceled due to error: ', { error, errorCode })
})

task.start()
```
Headers given in `createDownloadTask()` are **merged** with the ones given in `setConfig({ headers: { ... } })`.

</details>

<details>
<summary><strong>Configuring parallel downloads and network types</strong></summary>

You can configure global settings for download behavior using `setConfig()`:

#### Max Parallel Downloads (iOS only)

Control how many simultaneous downloads can occur per host. This is useful for managing bandwidth and server load.

```javascript
import { setConfig } from '@kesha-antonov/react-native-background-downloader'

// Set maximum parallel downloads to 8 (default is 4)
setConfig({
  maxParallelDownloads: 8
})
```

**Note:** This setting only affects iOS. Android's `DownloadManager` manages parallel downloads automatically and does not expose this configuration.

#### Cellular/WiFi Restrictions

Control whether downloads are allowed over cellular (metered) networks:

```javascript
import { setConfig } from '@kesha-antonov/react-native-background-downloader'

// Only allow downloads over WiFi (disable cellular data)
setConfig({
  allowsCellularAccess: false
})

// Allow downloads over both WiFi and cellular (default)
setConfig({
  allowsCellularAccess: true
})
```

This is a cross-platform setting that works on both iOS and Android:
- **iOS**: Sets the `allowsCellularAccess` property on the NSURLSession configuration
- **Android**: Sets the `isAllowedOverMetered` flag on DownloadManager requests

**Per-download override (Android only):** On Android, you can override the global cellular setting for individual downloads using the `isAllowedOverMetered` option in `createDownloadTask()`:

```javascript
const task = createDownloadTask({
  id: 'file123',
  url: 'https://link-to-very.large/file.zip',
  destination: `${directories.documents}/file.zip`,
  isAllowedOverMetered: true  // This download can use cellular even if global setting is false
})
```

</details>

<details>
<summary><strong>Enabling debug logs</strong></summary>

The library includes verbose debug logging that can help diagnose download issues. Logging is disabled by default but can be enabled at runtime using `setConfig()`. **Logging works in both debug and production/release builds.**

```javascript
import { setConfig } from '@kesha-antonov/react-native-background-downloader'

// Option 1: Enable native console logging (logs appear in Xcode/Android Studio console)
setConfig({
  isLogsEnabled: true
})

// Option 2: Enable logging with a JavaScript callback to capture logs in your app
setConfig({
  isLogsEnabled: true,
  logCallback: (log) => {
    // log.message - The debug message
    // log.taskId - Optional task ID associated with the log (iOS only)
    console.log('[BackgroundDownloader]', log.message)

    // You can also send logs to your analytics/crash reporting service
    // crashlytics.log(log.message)
  }
})

// Disable logging
setConfig({
  isLogsEnabled: false
})
```

**Notes:**
- When `isLogsEnabled` is `true`, native debug logs (NSLog on iOS, Log.d/w/e on Android) are printed
- The `logCallback` function is called for each native debug log (iOS only sends logs to callback currently)
- Logs include detailed information about download lifecycle, session management, and errors
- In production builds, logs are only printed when explicitly enabled via `isLogsEnabled`

</details>

<details>
<summary><strong>Handling slow-responding URLs</strong></summary>

This library automatically includes connection timeout improvements for slow-responding URLs. By default, the following headers are added to all download requests on Android:

- `Connection: keep-alive` - Keeps the connection open for better handling
- `Keep-Alive: timeout=600, max=1000` - Sets a 10-minute keep-alive timeout
- `User-Agent: ReactNative-BackgroundDownloader/3.2.6` - Proper user agent for better server compatibility

These headers help prevent downloads from getting stuck in "pending" state when servers take several minutes to respond initially. You can override these headers by providing your own in the `headers` option.

</details>

<details>
<summary><strong>Handling URLs with many redirects (Android)</strong></summary>

Android's DownloadManager has a built-in redirect limit that can cause `ERROR_TOO_MANY_REDIRECTS` for URLs with multiple redirects (common with podcast URLs, tracking services, CDNs, etc.).

To handle this, you can use the `maxRedirects` option to pre-resolve redirects before passing the final URL to DownloadManager:

```javascript
import { Platform } from 'react-native'
import { createDownloadTask, directories } from '@kesha-antonov/react-native-background-downloader'

// Example: Podcast URL with multiple redirects
const task = createDownloadTask({
  id: 'podcast-episode',
  url: 'https://pdst.fm/e/chrt.fm/track/479722/arttrk.com/p/example.mp3',
  destination: `${directories.documents}/episode.mp3`,
  maxRedirects: 10, // Follow up to 10 redirects before downloading
}).begin(({ expectedBytes }) => {
  console.log(`Going to download ${expectedBytes} bytes!`)
}).progress(({ bytesDownloaded, bytesTotal }) => {
  console.log(`Downloaded: ${bytesDownloaded / bytesTotal * 100}%`)
}).done(({ location, bytesDownloaded, bytesTotal }) => {
  console.log('Download is done!', { location, bytesDownloaded, bytesTotal })
}).error(({ error, errorCode }) => {
  console.log('Download canceled due to error: ', { error, errorCode })

  if (errorCode === 1005) { // ERROR_TOO_MANY_REDIRECTS
    console.log('Consider increasing maxRedirects or using a different URL')
  }
})

task.start()
```

**Notes on maxRedirects:**
- Only available on Android (iOS handles redirects automatically)
- If not specified or set to 0, no redirect resolution is performed
- Uses HEAD requests to resolve redirects efficiently
- Falls back to original URL if redirect resolution fails
- Respects the same headers and timeouts as the main download

</details>

<details>
<summary><strong>Notification Configuration (Android)</strong></summary>

On Android 14+ (API 34), downloads use User-Initiated Data Transfer (UIDT) jobs which **require** notifications. Due to Android system requirements, notifications cannot be completely disabled when using UIDT jobs. However, you can control their visibility:

- When `showNotificationsEnabled: true` - Full notifications with progress, title, and custom texts
- When `showNotificationsEnabled: false` (default) - Minimal silent notifications with lowest priority that are barely noticeable

**Basic configuration:**

```javascript
import { setConfig } from '@kesha-antonov/react-native-background-downloader'

// Enable notifications and notification grouping with custom texts
setConfig({
  showNotificationsEnabled: true, // Show full notifications (default: false - minimal silent notifications)
  notificationsGrouping: {
    enabled: true,           // Enable grouping (default: false)
    texts: {
      downloadTitle: 'Download',
      downloadStarting: 'Starting download...',
      downloadProgress: 'Downloading... {progress}%',
      downloadPaused: 'Paused',
      downloadFinished: 'Download complete',
      groupTitle: 'Downloads',
      groupText: '{count} downloads in progress',
    },
  },
})

// Use minimal silent notifications (default behavior)
setConfig({
  showNotificationsEnabled: false, // Minimal silent notifications (required by UIDT but barely visible)
})
```

**Grouping downloads by category:**

When notification grouping is enabled, you can group related downloads (e.g., by album, playlist, podcast) by passing `groupId` and `groupName` in the task metadata:

```javascript
import { createDownloadTask, directories } from '@kesha-antonov/react-native-background-downloader'

// Download album songs - they will be grouped under one notification
const task = createDownloadTask({
  id: 'song-1',
  url: 'https://example.com/albums/summer-hits/track01.mp3',
  destination: `${directories.documents}/track01.mp3`,
  metadata: {
    groupId: 'album-summer-hits',  // Unique identifier for the group
    groupName: 'Summer Hits 2024', // Display name in notification
  },
})

task.start()
```

**Notification behavior during pause/resume:**

When a download is paused on Android 14+:
- The background UIDT job is cancelled (to prevent downloads continuing in background)
- A detached "Paused" notification remains visible showing current progress
- When resumed, a new UIDT job is created and the notification switches to "Downloading" state
- When stopped, the notification is removed
- **When app is closed, all download notifications are automatically removed**

This ensures users always see the download status without unexpected background activity.

**Configuration options:**

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `showNotificationsEnabled` | boolean | `false` | Show full download notifications. When `false`, creates minimal silent notifications (UIDT jobs require a notification, but it will be barely visible). This is a top-level config option. |
| `notificationsGrouping.enabled` | boolean | `false` | Enable notification grouping |
| `notificationsGrouping.texts` | object | See below | Customizable notification texts |

**Notification texts (`notificationsGrouping.texts`):**

| Key | Default | Placeholders | Description |
|-----|---------|--------------|-------------|
| `downloadTitle` | `'Download'` | — | Title for individual download notifications |
| `downloadStarting` | `'Starting download...'` | — | Text when download is starting |
| `downloadProgress` | `'Downloading... {progress}%'` | `{progress}` | Progress text with current percentage (0-100) |
| `downloadPaused` | `'Paused'` | — | Text when download is paused |
| `downloadFinished` | `'Download complete'` | — | Text when download is finished |
| `groupTitle` | `'Downloads'` | — | Title for group summary notification |
| `groupText` | `'{count} download(s) in progress'` | `{count}` | Group summary text with active downloads count |

**Notes:**
- Notifications cannot be completely disabled on Android 14+ due to UIDT requirements
- When `showNotificationsEnabled: false`, notifications are created with minimal visibility (lowest priority, empty content)
- Paused downloads show a non-ongoing notification (can be swiped away by user)
- Active downloads show an ongoing notification (cannot be swiped away)
- This feature only affects Android 14+ (API 34) where UIDT jobs are used
- On older Android versions, the standard DownloadManager notifications are shown
- iOS uses system download notifications and doesn't support custom grouping

</details>

## 📚 API

For complete API documentation, see the **[API Reference](./docs/API.md)**.

### Quick Reference

```typescript
import {
  setConfig,
  createDownloadTask,
  createUploadTask,
  getExistingDownloadTasks,
  getExistingUploadTasks,
  completeHandler,
  directories
} from '@kesha-antonov/react-native-background-downloader'
```

| Function | Description |
|----------|-------------|
| `createDownloadTask(options)` | Create a new download task |
| `createUploadTask(options)` | Create a new upload task |
| `getExistingDownloadTasks()` | Get downloads running in background |
| `getExistingUploadTasks()` | Get uploads running in background |
| `setConfig(config)` | Set global configuration |
| `completeHandler(jobId)` | Signal download completion to OS |
| `directories.documents` | Path to app's documents directory |

## 📱 Platform Notes

For detailed platform-specific information, see **[Platform Notes](./docs/PLATFORM_NOTES.md)**.

Key points:
- **iOS**: Uses `NSURLSession` for true background downloads
- **Android**: Uses `DownloadManager` + Foreground Services + MMKV
- **Pause/Resume**: Works on both platforms (Android requires server Range header support)

## ❓ Troubleshooting

<details>
<summary><strong>Download stuck in "pending" state (Android)</strong></summary>

This can happen with slow-responding servers. The library automatically adds keep-alive headers, but you can also try:
- Increase timeout by setting custom headers
- Check if the server supports the download URL
- Enable debug logs to see what's happening: `setConfig({ isLogsEnabled: true })`
</details>

<details>
<summary><strong>Duplicate class errors with react-native-mmkv (Android)</strong></summary>

If you're using `react-native-mmkv`, you don't need to add the MMKV dependency manually - it's already included. The library uses `compileOnly` to avoid conflicts.
</details>

<details>
<summary><strong>EXC_BAD_ACCESS crash on iOS with react-native-mmkv</strong></summary>

This was fixed in v4.4.0. Update to the latest version. If you're not using `react-native-mmkv`, add `pod 'MMKV', '>= 1.0.0'` to your Podfile.
</details>

<details>
<summary><strong>Downloads not resuming after app restart</strong></summary>

Make sure to call `getExistingDownloadTasks()` at app startup and re-attach your callbacks. The task IDs you provide are used to identify downloads across restarts.
</details>

<details>
<summary><strong>Google Play Console asking about Foreground Service</strong></summary>

See the [Google Play Console Declaration](#google-play-console-declaration) section for the required steps.
</details>

<details>
<summary><strong>TypeToken errors in release builds (Android)</strong></summary>

Add the Proguard rules mentioned in the [Proguard Rules](#proguard-rules) section.
</details>

## 🧪 Example App

The repository includes a full example app demonstrating all features:

```bash
cd example
yarn install

# iOS
cd ios && pod install && cd ..
yarn ios

# Android
yarn android
```

The example app shows:
- Starting multiple downloads
- Pause/resume functionality
- Progress tracking with animations
- Re-attaching to background tasks
- File management

## 💡 Use Cases

This library is perfect for apps that need reliable file transfers:

- 🎵 **Music/Podcast Apps** - Download episodes for offline listening
- 📚 **E-book Readers** - Download books in the background
- 🎬 **Video Streaming** - Offline video downloads
- 📁 **File Managers** - Large file transfers
- 🎮 **Games** - Download game assets and updates
- 📱 **Enterprise Apps** - Sync large documents and media

## 🔄 Migration Guide

Upgrading from an older version? Check the [Migration Guide](./MIGRATION.md) for detailed instructions:

- [v4.3.x → v4.4.0](./MIGRATION.md#migration-guide-v43x--v440) - iOS MMKV dependency change
- [v4.1.x → v4.2.0](./MIGRATION.md#migration-guide-v41x--v420) - Android pause/resume support
- [v4.0.x → v4.1.0](./MIGRATION.md#migration-guide-v40x--v410) - MMKV dependency change
- [v3.2.6 → v4.0.0](./MIGRATION.md#migration-guide-v326--v400) - Major API changes

See the [Changelog](./CHANGELOG.md) for a complete list of changes in each version.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Install dependencies (`yarn install`)
4. Make your changes
5. Run tests (`yarn test`)
6. Run linting (`yarn lint`)
7. Commit your changes (`git commit -m 'Add amazing feature'`)
8. Push to the branch (`git push origin feature/amazing-feature`)
9. Open a Pull Request

### Development Setup

```bash
# Install dependencies
yarn install

# Run tests
yarn test

# Run linting
yarn lint

# Build the Expo plugin
yarn build-plugin

# Run the example app
cd example && yarn install
yarn ios  # or yarn android
```

## 👥 Authors

Maintained by [Kesha Antonov](https://github.com/kesha-antonov)

Based on [react-native-background-downloader](https://github.com/ekolabs/react-native-background-downloader) by [Elad Gil](https://github.com/ptelad) (unmaintained since 2019)

> Please note that this project is maintained in free time. If you find it helpful, please consider [becoming a sponsor](https://github.com/sponsors/kesha-antonov).

## 📄 License

[Apache 2.0](./LICENSE)

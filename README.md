<p align="center">
  <img width="300" src="https://github.com/user-attachments/assets/25e89808-9eb7-42b2-8031-b48d8c24796c" />
</p>

<p align="center">
  <a href="https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader"><img src="https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader.svg" alt="npm version"></a>
  <a href="https://github.com/kesha-antonov/react-native-background-downloader/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue.svg" alt="license"></a>
  <img src="https://img.shields.io/badge/platforms-iOS%20%7C%20Android-lightgrey.svg" alt="platforms">
  <img src="https://img.shields.io/badge/TypeScript-supported-blue.svg" alt="TypeScript">
  <img src="https://img.shields.io/badge/Expo-compatible-000020.svg" alt="Expo compatible">
  <img src="https://img.shields.io/badge/New%20Architecture-supported-green.svg" alt="New Architecture">
</p>

# @kesha-antonov/react-native-background-downloader

A library for React-Native to help you download and upload large files on iOS and Android both in the foreground and most importantly in the background.

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

### Why?

**The Problem:** Standard network requests in React Native are tied to your app's lifecycle. When the user switches to another app or the OS terminates your app to free memory, your downloads stop. For small files this is fine, but for large files (videos, podcasts, documents) this creates a frustrating user experience.

**The Solution:** Both iOS and Android provide system-level APIs for background file transfers:
- **iOS:** [`NSURLSession`](https://developer.apple.com/documentation/foundation/url_loading_system/downloading_files_in_the_background) - handles downloads in a separate process, continuing even after your app is terminated
- **Android:** [`DownloadManager`](https://developer.android.com/reference/android/app/DownloadManager) - a system service that manages long-running downloads independently of your app

**The Challenge:** These APIs are powerful but complex. Downloads run in a separate process, so your app might restart from scratch while downloads are still in progress. Keeping your UI in sync with background downloads requires careful state management.

**This Library:** `@kesha-antonov/react-native-background-downloader` wraps these native APIs in a simple, unified JavaScript interface. Start a download, close your app, reopen it hours later, and seamlessly reconnect to your ongoing downloads with a single function call.

## ToC

- [Features](#-features)
- [Requirements](#requirements)
- [Getting started](#getting-started)
- [Usage](#usage)
  - [Downloading](#downloading-a-file)
  - [Uploading](#uploading-a-file)
  - [Custom Headers](#using-custom-headers)
  - [Configuration](#configuring-parallel-downloads-and-network-types)
  - [Debug Logs](#enabling-debug-logs)
  - [Handling Redirects](#handling-urls-with-many-redirects-android)
- [API](#api)
- [Constants](#constants)
- [Platform Notes](#platform-notes)
- [Troubleshooting](#troubleshooting)
- [Example App](#example-app)
- [Migration Guide](#migration-guide)
- [Contributing](#contributing)
- [License](#license)

## Requirements

| Requirement | Version |
|-------------|--------|
| React Native | >= 0.57.0 |
| iOS | >= 12.0 |
| Android | API 21+ (Android 5.0) |
| Expo | SDK 50+ (with config plugin) |

## Getting started

### Installation

```bash
yarn add @kesha-antonov/react-native-background-downloader
```

or
```bash
npm i @kesha-antonov/react-native-background-downloader
```

Then:

```bash
cd ios && pod install
```

### iOS - Extra Mandatory Step

#### Option 1: Using Expo Config Plugin (Recommended)

If you're using Expo, add the plugin to your config:

**In your `app.json`:**
```json
{
  "expo": {
    "plugins": [
      "@kesha-antonov/react-native-background-downloader"
    ]
  }
}
```

**Plugin Options:**

```js
// app.config.js
export default {
  expo: {
    plugins: [
      ["@kesha-antonov/react-native-background-downloader", {
        // Customize the MMKV version on Android (default: '2.2.4')
        mmkvVersion: "2.2.4"
      }]
    ]
  }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mmkvVersion` | string | `'2.2.4'` | The version of [MMKV](https://github.com/Tencent/MMKV/releases) to use on Android. |

The plugin will automatically:
- **iOS:** Add the required `handleEventsForBackgroundURLSession` method to your AppDelegate
- **Android:** Add the required MMKV dependency

After adding the plugin, run:
```bash
npx expo prebuild --clean
```

#### Option 2: Manual Setup

<details>
  <summary>Manual setup for React Native 0.77+ (Click to expand)</summary>

  In your project bridging header file (e.g. `ios/{projectName}-Bridging-Header.h`)
  add an import for RNBackgroundDownloader:

  ```objc
  #import <RNBackgroundDownloader.h>
  ```

  Then in your `AppDelegate.swift` add the following method inside of your `AppDelegate` class:

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
  <summary>Manual setup for React Native < 0.77 (Click to expand)</summary>

  In your `AppDelegate.m` add the following code:
  ```objc
  #import <RNBackgroundDownloader.h>

  - (void)application:(UIApplication *)application handleEventsForBackgroundURLSession:(NSString *)identifier completionHandler:(void (^)(void))completionHandler
  {
    [RNBackgroundDownloader setCompletionHandlerWithIdentifier:identifier completionHandler:completionHandler];
  }
  ```
</details>

### Android - Extra Step (Non-Expo only)

If you're **not** using Expo, add the MMKV dependency to your `android/app/build.gradle`:

```gradle
dependencies {
    implementation 'com.tencent:mmkv-shared:2.2.4'
}
```

> **Note:** If you're already using [react-native-mmkv](https://github.com/mrousavy/react-native-mmkv) in your project, you can skip this step as it already includes MMKV as a dependency.

## Usage

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

### Re-Attaching to background downloads

This is the main selling point of this library (but it's free!).

What happens to your downloads after the OS stopped your app? Well, they are still running, we just need to re-attach to them.

Add this code to app's init stage, and you'll never lose a download again!

```javascript
import { getExistingDownloadTasks } from '@kesha-antonov/react-native-background-downloader'

let lostTasks = await getExistingDownloadTasks()
for (let task of lostTasks) {
  console.log(`Task ${task.id} was found!`)
  task.progress(({ bytesDownloaded, bytesTotal }) => {
    console.log(`Downloaded: ${bytesDownloaded / bytesTotal * 100}%`)
  }).done(({ location, bytesDownloaded, bytesTotal }) => {
    console.log('Download is done!', { location, bytesDownloaded, bytesTotal })
  }).error(({ error, errorCode }) => {
    console.log('Download canceled due to error: ', { error, errorCode })
  })
}
```

`task.id` is very important for re-attaching the download task with any UI component representing that task. This is why you need to make sure to give sensible IDs that you know what to do with, try to avoid using random IDs.

### Uploading a file

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

### Re-Attaching to background uploads

Similar to downloads, you can re-attach to uploads that were running when your app was terminated:

```javascript
import { getExistingUploadTasks } from '@kesha-antonov/react-native-background-downloader'

let lostUploads = await getExistingUploadTasks()
for (let task of lostUploads) {
  console.log(`Upload task ${task.id} was found!`)
  task.progress(({ bytesUploaded, bytesTotal }) => {
    console.log(`Uploaded: ${bytesUploaded / bytesTotal * 100}%`)
  }).done(({ responseCode, responseBody }) => {
    console.log('Upload is done!', { responseCode, responseBody })
  }).error(({ error, errorCode }) => {
    console.log('Upload canceled due to error: ', { error, errorCode })
  })
}
```

### Using custom headers
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

### Configuring Parallel Downloads and Network Types

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

### Enabling Debug Logs

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

### Handling Slow-Responding URLs

This library automatically includes connection timeout improvements for slow-responding URLs. By default, the following headers are added to all download requests on Android:

- `Connection: keep-alive` - Keeps the connection open for better handling
- `Keep-Alive: timeout=600, max=1000` - Sets a 10-minute keep-alive timeout
- `User-Agent: ReactNative-BackgroundDownloader/3.2.6` - Proper user agent for better server compatibility

These headers help prevent downloads from getting stuck in "pending" state when servers take several minutes to respond initially. You can override these headers by providing your own in the `headers` option.

### Handling URLs with Many Redirects (Android)

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

## API

### Named Exports

The library exports the following functions and objects:

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

### `createDownloadTask(options)`

Download a file to destination

**options**

An object containing options properties

| Property      | Type                                             | Required | Platforms | Info                                                                                                                                                                                                                                 |
| ------------- | ------------------------------------------------ | :------: | :-------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `id`          | String |    ✅     |    All    | A unique ID to provide for this download. This ID will help to identify the download task when the app re-launches |
| `url`         | String |    ✅     |    All    | URL to file you want to download |
| `destination` | String |    ✅     |    All    | Where to copy the file to once the download is done. The 'file://' prefix will be automatically removed if present |
| `metadata`    | Record<string, unknown> |           |    All    | Custom data to be preserved across app restarts. Will be serialized to JSON |
| `headers`     | Record<string, string \| null> |           |    All    | Custom headers to add to the download request. These are merged with the headers given in `setConfig({ headers: { ... } })`. Headers with null values will be removed |
| `maxRedirects` | Number |          |  Android  | Maximum number of redirects to follow before passing URL to DownloadManager. If not specified or 0, no redirect resolution is performed. Helps avoid ERROR_TOO_MANY_REDIRECTS for URLs with many redirects (e.g., podcast URLs) |
| `isAllowedOverRoaming` | Boolean   |          |  Android  | whether this download may proceed over a roaming connection. By default, roaming is allowed |
| `isAllowedOverMetered` | Boolean   |          |  Android  | Whether this download may proceed over a metered network connection. By default, metered networks are allowed |
| `isNotificationVisible`     | Boolean   |          |  Android  | Whether to show a download notification or not |
| `notificationTitle`     | String   |          |  Android  | Title of the download notification |

**returns**

`DownloadTask` - The download task to control and monitor this download. Call `task.start()` to begin the download.

### `getExistingDownloadTasks()`

Checks for downloads that ran in background while your app was terminated.

Recommended to run at the init stage of the app.

**returns**

`Promise<DownloadTask[]>` - A promise that resolves to an array of tasks that were running in the background so you can re-attach callbacks to them

### `createUploadTask(options)`

Upload a file to a server

**options**

An object containing options properties

| Property      | Type                                             | Required | Platforms | Info                                                                                                                                                                                                                                 |
| ------------- | ------------------------------------------------ | :------: | :-------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `id`          | String |    ✅     |    All    | A unique ID to provide for this upload. This ID will help to identify the upload task when the app re-launches |
| `url`         | String |    ✅     |    All    | URL to upload the file to |
| `source`      | String |    ✅     |    All    | Path to the local file to upload. The 'file://' prefix will be automatically removed if present |
| `method`      | 'POST' \| 'PUT' \| 'PATCH' |          |    All    | HTTP method for upload. Default is 'POST' |
| `metadata`    | Record<string, unknown> |           |    All    | Custom data to be preserved across app restarts. Will be serialized to JSON |
| `headers`     | Record<string, string \| null> |           |    All    | Custom headers to add to the upload request. These are merged with the headers given in `setConfig({ headers: { ... } })`. Headers with null values will be removed |
| `fieldName`   | String |          |    All    | Name of the multipart form field for the file. Default is 'file' |
| `mimeType`    | String |          |    All    | MIME type of the file being uploaded. Default is inferred from file extension |
| `parameters`  | Record<string, string> |          |    All    | Additional form parameters to send with the upload |
| `isAllowedOverRoaming` | Boolean   |          |  Android  | Whether this upload may proceed over a roaming connection. By default, roaming is allowed |
| `isAllowedOverMetered` | Boolean   |          |  Android  | Whether this upload may proceed over a metered network connection. By default, metered networks are allowed |
| `isNotificationVisible`     | Boolean   |          |  Android  | Whether to show an upload notification or not |
| `notificationTitle`     | String   |          |  Android  | Title of the upload notification |

**returns**

`UploadTask` - The upload task to control and monitor this upload. Call `task.start()` to begin the upload.

### `getExistingUploadTasks()`

Checks for uploads that ran in background while your app was terminated.

Recommended to run at the init stage of the app.

**returns**

`Promise<UploadTask[]>` - A promise that resolves to an array of upload tasks that were running in the background so you can re-attach callbacks to them

### `setConfig(config)`

Sets global configuration for the downloader.

**config**

An object containing configuration properties

| Name           | Type   | Info                                                                                                 |
| -------------- | ------ | ---------------------------------------------------------------------------------------------------- |
| `headers`     | Record<string, string \| null> | Optional headers to use in all future downloads. Headers with null values will be removed |
| `progressInterval` | Number | Interval in milliseconds for download progress updates. Must be >= 250. Default is 1000 |
| `progressMinBytes` | Number | Minimum number of bytes that must be downloaded before a progress event is emitted. When set to 0, only the percentage threshold (1% change) triggers progress updates. Default is 1048576 (1MB) |
| `isLogsEnabled`   | Boolean | Enables/disables verbose debug logs in native code (iOS and Android). Works in both debug and release builds. Default is false |
| `logCallback`   | (log: { message: string, taskId?: string }) => void | Optional callback function to receive native debug logs in JavaScript. Only called when `isLogsEnabled` is true |
| `maxParallelDownloads` | Number | **iOS only**. Sets the maximum number of simultaneous connections per host for the download session. Must be >= 1. Default is 4. Note: Android's DownloadManager does not support this configuration |
| `allowsCellularAccess` | Boolean | Controls whether downloads are allowed over cellular (metered) connections. When set to `false`, downloads will only occur over WiFi. Default is `true`. This is a cross-platform abstraction - on iOS it sets `allowsCellularAccess`, on Android it sets `isAllowedOverMetered` |

**Example:**

```javascript
import { setConfig } from '@kesha-antonov/react-native-background-downloader'

// Configure parallel downloads (iOS only) and cellular access
setConfig({
  maxParallelDownloads: 8,  // iOS only - max simultaneous connections per host
  allowsCellularAccess: false,  // Only download over WiFi
})

// Enable verbose logging with callback
setConfig({
  isLogsEnabled: true,
  logCallback: (log) => {
    console.log('[BackgroundDownloader]', log.message, log.taskId ? `(${log.taskId})` : '')
  }
})

// Or just enable native console logging without JS callback
setConfig({
  isLogsEnabled: true
})
```

### DownloadTask

A class representing a download task created by `createDownloadTask()`. Note: You must call `task.start()` to begin the download after setting up event handlers.

### UploadTask

A class representing an upload task created by `createUploadTask()`. Note: You must call `task.start()` to begin the upload after setting up event handlers.

**Members** (same structure as DownloadTask with upload-specific properties)

| Name           | Type   | Info                                                                                                 |
| -------------- | ------ | ---------------------------------------------------------------------------------------------------- |
| `id`           | String | The id you gave the task when calling `createUploadTask`                              |
| `metadata`     | Record<string, unknown> | The metadata you gave the task when calling `createUploadTask`                        |
| `state`        | 'PENDING' \| 'UPLOADING' \| 'PAUSED' \| 'DONE' \| 'FAILED' \| 'STOPPED' | Current state of the upload task |
| `bytesUploaded` | Number | The number of bytes currently uploaded by the task                                                    |
| `bytesTotal`   | Number | The total number bytes to be uploaded by this task |
| `uploadParams` | UploadParams | The upload parameters set for this task |

**Callback Methods**

| Function   | Callback Arguments                | Info|
| ---------- | --------------------------------- | ---- |
| `begin`    | `{ expectedBytes: number }` | Called when upload starts |
| `progress` | `{ bytesUploaded: number, bytesTotal: number }` | Called based on progressInterval (default: every 1000ms) so you can update your progress bar accordingly |
| `done`     | `{ responseCode: number, responseBody: string, bytesUploaded: number, bytesTotal: number }` | Called when the upload is done. Includes server response code and body |
| `error`    | `{ error: string, errorCode: number }` | Called when the upload stops due to an error |

**Methods**

- `pause(): Promise<void>` - Pauses the upload (platform support may vary)
- `resume(): Promise<void>` - Resumes a paused upload
- `stop(): Promise<void>` - Stops the upload and removes temporary data
- `start(): void` - Starts the upload

### `Members`
| Name           | Type   | Info                                                                                                 |
| -------------- | ------ | ---------------------------------------------------------------------------------------------------- |
| `id`           | String | The id you gave the task when calling `createDownloadTask`                              |
| `metadata`     | Record<string, unknown> | The metadata you gave the task when calling `createDownloadTask`                        |
| `state`        | 'PENDING' \| 'DOWNLOADING' \| 'PAUSED' \| 'DONE' \| 'FAILED' \| 'STOPPED' | Current state of the download task |
| `bytesDownloaded` | Number | The number of bytes currently written by the task                                                    |
| `bytesTotal`   | Number | The number bytes expected to be written by this task or more plainly, the file size being downloaded. **Note:** This value will be `-1` if the server does not provide a `Content-Length` header |
| `downloadParams` | DownloadParams | The download parameters set for this task |

### `completeHandler(jobId: string)`

Finishes download job and informs OS that app can be closed in background if needed.
After finishing download in background you have some time to process your JS logic and finish the job.

**Parameters:**
- `jobId` (String) - The ID of the download task to complete

**Note:** This should be called after processing your download in the `done` callback to properly signal completion to the OS.

### `Callback Methods`
Use these methods to stay updated on what's happening with the task.

All callback methods return the current instance of the `DownloadTask` for chaining.

| Function   | Callback Arguments                | Info|
| ---------- | --------------------------------- | ---- |
| `begin`    | `{ expectedBytes: number, headers: Record<string, string \| null> }` | Called when the first byte is received. 💡: this is good place to check if the device has enough storage space for this download |
| `progress` | `{ bytesDownloaded: number, bytesTotal: number }` | Called based on progressInterval (default: every 1000ms) so you can update your progress bar accordingly. **Note:** `bytesTotal` will be `-1` if the server does not provide a `Content-Length` header |
| `done`     | `{ location: string, bytesDownloaded: number, bytesTotal: number }` | Called when the download is done, the file is at the destination you've set. `location` is the final file path. **Note:** `bytesTotal` will be `-1` if the server did not provide a `Content-Length` header |
| `error`    | `{ error: string, errorCode: number }` | Called when the download stops due to an error |

### `pause(): Promise<void>`
Pauses the download. Returns a promise that resolves when the pause operation is complete.

**Note:** On Android, pause/resume is implemented using HTTP Range headers, which requires server support. The download progress is saved and resumed from where it left off.

### `resume(): Promise<void>`
Resumes a paused download. Returns a promise that resolves when the resume operation is complete.

**Note:** On Android, this uses HTTP Range headers to resume from the last downloaded byte position. If the server doesn't support range requests, the download will restart from the beginning.

### `stop(): Promise<void>`
Stops the download for good and removes the file that was written so far. Returns a promise that resolves when the stop operation is complete.

## Constants

### directories

### `documents`

An absolute path to the app's documents directory. It is recommended that you use this path as the target of downloaded files.

## Platform Notes

### Android Pause/Resume

Pause/resume on Android uses HTTP Range headers. The server must support range requests for resume to work correctly. If the server doesn't support it, the download will restart from the beginning.

### Android 16+ Support

Downloads are automatically marked as user-initiated data transfers on Android 16+ (API 36) to prevent being killed due to thermal throttling.

### Google Play Console Declaration

The library uses Foreground Service permissions. When publishing to Google Play:

1. Go to **App content** → **Foreground Service** in the Play Console
2. Select **Yes** for Foreground Service usage
3. Choose **Data sync** as the type
4. Select **Network processing** as the task

### Proguard Rules

If you encounter `TypeToken` errors in release builds, add to `proguard-rules.pro`:

```
-keep class com.eko.RNBGDTaskConfig { *; }
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.tencent.mmkv.** { *; }
```

## Troubleshooting

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

## Example App

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
- Re-attaching to background downloads
- File management

## Use Cases

This library is perfect for apps that need reliable file transfers:

- 🎵 **Music/Podcast Apps** - Download episodes for offline listening
- 📚 **E-book Readers** - Download books in the background
- 🎬 **Video Streaming** - Offline video downloads
- 📁 **File Managers** - Large file transfers
- 🎮 **Games** - Download game assets and updates
- 📱 **Enterprise Apps** - Sync large documents and media

## Migration Guide

Upgrading from an older version? Check the [Migration Guide](./MIGRATION.md) for detailed instructions:

- [v4.3.x → v4.4.0](./MIGRATION.md#migration-guide-v43x--v440) - iOS MMKV dependency change
- [v4.1.x → v4.2.0](./MIGRATION.md#migration-guide-v41x--v420) - Android pause/resume support
- [v4.0.x → v4.1.0](./MIGRATION.md#migration-guide-v40x--v410) - MMKV dependency change
- [v3.2.6 → v4.0.0](./MIGRATION.md#migration-guide-v326--v400) - Major API changes

See the [Changelog](./CHANGELOG.md) for a complete list of changes in each version.

## Contributing

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

## Authors

Maintained by [Kesha Antonov](https://github.com/kesha-antonov)

Originally developed by [Elad Gil](https://github.com/ptelad) of [Eko](https://github.com/ekolabs/react-native-background-downloader)

## License

[Apache 2.0](./LICENSE)

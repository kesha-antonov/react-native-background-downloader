<p align="center">
  <img width="300" src="https://github.com/user-attachments/assets/25e89808-9eb7-42b2-8031-b48d8c24796c" />
</p>

[![npm version](https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader.svg)](https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader)

# @kesha-antonov/react-native-background-downloader

A library for React-Native to help you download and upload large files on iOS and Android both in the foreground and most importantly in the background.

### Why?

On iOS, if you want to download big files no matter the state of your app, wether it's in the background or terminated by the OS, you have to use a system API called `NSURLSession`.

This API handles your downloads separately from your app and only keeps it informed using delegates (Read: [Downloading Files in the Background](https://developer.apple.com/documentation/foundation/url_loading_system/downloading_files_in_the_background)).

On Android we are using similar process with [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)

The real challenge of using this method is making sure the app's UI is always up-to-date with the downloads that are happening in another process because your app might startup from scratch while the downloads are still running.

`@kesha-antonov/react-native-background-downloader` gives you an easy API to both downloading large files and re-attaching to those downloads once your app launches again.

## ToC

- [Getting started](#getting-started)
- [Usage](#usage)
  - [Downloading](#downloading-a-file)
  - [Uploading](#uploading-a-file)
- [API](#api)
- [Constants](#constants)

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
        // Customize the MMKV version on Android (default: '4.1.1')
        mmkvVersion: "4.1.1"
      }]
    ]
  }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mmkvVersion` | string | `'4.1.1'` | The version of MMKV to use on Android. |

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

## Authors

Maintained by [Kesha Antonov](https://github.com/kesha-antonov)

Originally developed by [Elad Gil](https://github.com/ptelad) of [Eko](https://github.com/ekolabs/react-native-background-downloader)

## License
Apache 2

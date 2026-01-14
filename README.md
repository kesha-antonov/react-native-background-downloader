
<p align="center">
  <img width="300" src="https://github.com/user-attachments/assets/25e89808-9eb7-42b2-8031-b48d8c24796c" />
</p>

[![npm version](https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader.svg)](https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader)

## 🎉 Version 4.0.0 Released!

**v4.0.0** is now available with full **React Native New Architecture (TurboModules)** support!

### What's New
- ✅ Full TurboModules support for iOS and Android
- ✅ Expo Config Plugin for automatic iOS setup
- ✅ Android code converted to Kotlin
- ✅ Full TypeScript support
- ✅ New `progressMinBytes` option for hybrid progress reporting
- ✅ `maxRedirects` option for Android

### Upgrading from v3.x?
📖 See the [Migration Guide](./MIGRATION.md) for detailed upgrade instructions and breaking changes.

📋 See the [Changelog](./CHANGELOG.md) for the full list of changes.

### Looking for v3.2.6?
If you need the previous stable version: [3.2.6 readme](https://github.com/kesha-antonov/react-native-background-downloader/blob/8f4b8a844a2d7f00d1558f6ea65bac94c8dd6fc9/README.md)

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

```Terminal
yarn add @kesha-antonov/react-native-background-downloader
```

or
```Terminal
npm i @kesha-antonov/react-native-background-downloader
```

Then:

```Terminal
cd ios && pod install
```

<details>
<summary>Manual Setup (Advanced)</summary>

If you need to manually configure the package for New Architecture:

**iOS**: The library automatically detects New Architecture via compile-time flags.

**Android**: For New Architecture, you can optionally use `RNBackgroundDownloaderTurboPackage` instead of the default package:
```java
import com.eko.RNBackgroundDownloaderTurboPackage;

// In your MainApplication.java
@Override
protected List<ReactPackage> getPackages() {
  return Arrays.<ReactPackage>asList(
    // ... other packages
    new RNBackgroundDownloaderTurboPackage() // For New Architecture
  );
}
```
</details>

### Mostly automatic installation
Any React Native version **`>= 0.60`** supports autolinking so nothing should be done.

For anything **`< 0.60`** run the following link command

`$ react-native link @kesha-antonov/react-native-background-downloader`

### Manual installation

<details>

#### iOS

1. In XCode, in the project navigator, right click `Libraries` ➜ `Add Files to [your project's name]`
2. Go to `node_modules` ➜ `@kesha-antonov/react-native-background-downloader` and add `RNBackgroundDownloader.xcodeproj`
3. In XCode, in the project navigator, select your project. Add `libRNBackgroundDownloader.a` to your project's `Build Phases` ➜ `Link Binary With Libraries`
4. Run your project (`Cmd+R`)

#### Android

1. Open up `android/app/src/main/java/[...]/MainActivity.java`
  - Add `import com.eko.RNBackgroundDownloaderPackage;` to the imports at the top of the file
  - Add `new RNBackgroundDownloaderPackage()` to the list returned by the `getPackages()` method
2. Append the following lines to `android/settings.gradle`:
    ```
    include ':react-native-background-downloader'
    project(':react-native-background-downloader').projectDir = new File(rootProject.projectDir,   '../node_modules/@kesha-antonov/react-native-background-downloader/android')
    ```
3. Insert the following lines inside the dependencies block in `android/app/build.gradle`:
    ```
      compile project(':react-native-background-downloader')
    ```
</details>

### iOS - Extra Mandatory Step

#### Option 1: Using Expo Config Plugin (Recommended for Expo/EAS users)

If you're using Expo or EAS Build, you can use the included Expo config plugin to automatically configure the native code:

**In your `app.json`:**
```json
{
  "expo": {
    "name": "Your App",
    "plugins": [
      "@kesha-antonov/react-native-background-downloader"
    ]
  }
}
```

**Or in your `app.config.js`:**
```js
export default {
  expo: {
    name: "Your App",
    plugins: [
      "@kesha-antonov/react-native-background-downloader"
    ]
  }
}
```

**Plugin Options:**

You can customize the plugin behavior with options:

```js
// app.config.js
export default {
  expo: {
    name: "Your App",
    plugins: [
      ["@kesha-antonov/react-native-background-downloader", {
        // Set to false if you're already using react-native-mmkv
        addMmkvDependency: true,
        // Customize the MMKV version (default: '2.2.4')
        mmkvVersion: "2.2.4"
      }]
    ]
  }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `addMmkvDependency` | boolean | `true` | Whether to automatically add MMKV dependency on Android. Set to `false` if you're using `react-native-mmkv`. |
| `mmkvVersion` | string | `'2.2.4'` | The version of MMKV to use on Android. |

The plugin will automatically:
- **iOS:** Add the required import to your AppDelegate (Objective-C) or Bridging Header (Swift)
- **iOS:** Add the `handleEventsForBackgroundURLSession` method to your AppDelegate
- **iOS:** Handle both React Native < 0.77 (Objective-C) and >= 0.77 (Swift) projects
- **Android:** Add the required MMKV dependency (unless `addMmkvDependency: false`)

After adding the plugin, run:
```bash
expo prebuild --clean
```

#### Option 2: Manual Setup

<details>
  <summary>Manual setup for React Native 0.77+ (Click to expand)</summary>

  In your project bridging header file (e.g. `ios/{projectName}-Bridging-Header.h`)
  add an import for RNBackgroundDownloader:

  ```objc
  ...
  #import <RNBackgroundDownloader.h>
  ```

  Then in your `AppDelegate.swift` add the following method inside of your `AppDelegate` class:

  ```swift
  ...

  @main
  class AppDelegate: UIResponder, UIApplicationDelegate
    ...

    func application(
      _ application: UIApplication,
      handleEventsForBackgroundURLSession identifier: String,
      completionHandler: @escaping () -> Void
    ) {
      RNBackgroundDownloader.setCompletionHandlerWithIdentifier(identifier, completionHandler: completionHandler)
    }
  }
  ...
  ```
  Failing to add this code will result in canceled background downloads. If Xcode complains that RNBackgroundDownloader.h is missing, you might have forgotten to `pod install` first.
</details>

<details>
  <summary>Manual setup for React Native < 0.77 (Click to expand)</summary>

  In your `AppDelegate.m` add the following code:
  ```objc
  ...
  #import <RNBackgroundDownloader.h>

  ...

  - (void)application:(UIApplication *)application handleEventsForBackgroundURLSession:(NSString *)identifier completionHandler:(void (^)(void))completionHandler
  {
    [RNBackgroundDownloader setCompletionHandlerWithIdentifier:identifier completionHandler:completionHandler];
  }

  ...
  ```
  Failing to add this code will result in canceled background downloads. If Xcode complains that RNBackgroundDownloader.h is missing, you might have forgotten to `pod install` first.
</details>

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

## Platform-Specific Limitations

### iOS Background Download Behavior

This library uses iOS's `NSURLSession` with background session configuration, which is Apple's recommended approach for background downloads. However, iOS background downloads have specific behaviors that are important to understand:

#### How iOS Background Downloads Work

1. **System-Managed Downloads**: When your app goes to the background, iOS takes over the download and manages it in a separate process. Your app may be suspended or terminated, but the download continues.

2. **App Wake-Up on Completion**: When a background download completes, iOS wakes up your app (if terminated) or calls the AppDelegate method `handleEventsForBackgroundURLSession`. This is why the mandatory AppDelegate setup is crucial.

3. **Discretionary Behavior**: iOS may delay background downloads based on network conditions, battery level, and other factors to optimize device performance and battery life.

#### Common Issues and Solutions

**Downloads pause when screen is locked:**

iOS background downloads should continue when the screen is locked. If downloads are stopping, verify:

1. **AppDelegate Setup**: Ensure you've added the required `handleEventsForBackgroundURLSession` method as described in the [iOS - Extra Mandatory Step](#ios---extra-mandatory-step) section. Without this, iOS may cancel background downloads.

2. **Download Initiation Context**: If you're starting downloads from JavaScript that runs in a view context (e.g., in response to a user action), the download should still work. However, for more reliable background operation when used with other libraries like audio players, consider starting downloads from a background context.

3. **Using with Audio Players (react-native-track-player, etc.)**: When using this library alongside audio players, start downloads from the audio player's background service rather than from the main JavaScript context:

   ```javascript
   // In your track-player service (runs in background)
   // This service is registered with TrackPlayer and runs even when the app is backgrounded
   import TrackPlayer, { Event } from 'react-native-track-player'
   import { createDownloadTask, directories, completeHandler } from '@kesha-antonov/react-native-background-downloader'

   // Your app's function to get the next track info - implement based on your needs
   // Returns: { id: string, url: string, filename: string }
   async function getNextTrackInfo() {
     // Example: fetch from your playlist/queue
     return { id: 'track-123', url: 'https://example.com/track.mp3', filename: 'track.mp3' }
   }

   export async function PlaybackService() {
     TrackPlayer.addEventListener(Event.RemoteNext, async () => {
       // Start downloading next track from background service
       const nextTrack = await getNextTrackInfo()

       const task = createDownloadTask({
         id: nextTrack.id,
         url: nextTrack.url,
         destination: `${directories.documents}/${nextTrack.filename}`,
       })
       .done(({ bytesDownloaded, bytesTotal }) => {
         console.log('Download complete!')
         completeHandler(nextTrack.id)
       })
       .error(({ error }) => {
         console.error('Download failed:', error)
       })

       task.start()
     })
   }
   ```

4. **Using react-native-background-actions**: For scenarios where you need guaranteed background execution (not just background downloads), consider using [react-native-background-actions](https://github.com/Rapsssito/react-native-background-actions) alongside this library:

   ```javascript
   import BackgroundService from 'react-native-background-actions'
   import { createDownloadTask, directories, completeHandler } from '@kesha-antonov/react-native-background-downloader'

   /**
    * Background task function that downloads a file
    * @param {Object} taskData - Task data passed to BackgroundService.start()
    * @param {string} taskData.id - Unique download ID
    * @param {string} taskData.url - URL to download from
    * @param {string} taskData.filename - Destination filename
    */
   const downloadTask = async (taskData) => {
     const { url, filename, id } = taskData

     return new Promise((resolve, reject) => {
       const task = createDownloadTask({
         id,
         url,
         destination: `${directories.documents}/${filename}`,
       })
       .done(({ bytesDownloaded }) => {
         completeHandler(id)
         resolve(bytesDownloaded)
       })
       .error(({ error }) => {
         reject(new Error(error))
       })

       task.start()
     })
   }

   // Start the background task
   const options = {
     taskName: 'Download',
     taskTitle: 'Downloading file',
     taskDesc: 'Downloading in background',
     taskIcon: { name: 'ic_launcher', type: 'mipmap' },
     progressBar: { max: 100, value: 0, indeterminate: true },
   }

   // Call with your download parameters
   await BackgroundService.start(downloadTask, options)
   ```

#### Best Practices for Reliable iOS Background Downloads

1. **Always implement the AppDelegate method** - This is the most common cause of background download issues.

2. **Call `completeHandler(jobId)`** - After processing a completed download, call this method to inform iOS that your app has finished handling the background event.

3. **Use `getExistingDownloadTasks()`** - On app startup, always check for and re-attach to any downloads that completed while your app was suspended.

4. **Handle app termination gracefully** - iOS may terminate your app at any time. The download will continue, but your app needs to be ready to re-attach when relaunched.

5. **Test in production conditions** - Debug builds may behave differently than release builds. Test background downloads with the app installed from TestFlight or the App Store.

For more details on iOS background downloads, see Apple's documentation: [Downloading Files in the Background](https://developer.apple.com/documentation/foundation/url_loading_system/downloading_files_in_the_background).

### Android MMKV Dependency

This library uses MMKV for persistent storage of download state on Android. The MMKV dependency is declared as `compileOnly`, meaning your app must provide it.

**If you're using `react-native-mmkv`:** No additional setup needed - `react-native-mmkv` already provides the required MMKV dependency.

**If you're NOT using `react-native-mmkv`:** Add the MMKV dependency to your app's `android/app/build.gradle`:

```gradle
dependencies {
    // ... other dependencies
    implementation 'com.tencent:mmkv-shared:2.2.4'  // or newer
}
```

**Note:** MMKV 2.0.0+ is required for Android 15+ support (16KB memory page sizes).

### Android DownloadManager Limitations

The Android implementation uses the system's `DownloadManager` service for downloads, with custom pause/resume support:

#### Android 16+ User-Initiated Data Transfer (UIDT) Support
- **Android 16 Compatibility**: Downloads are automatically marked as user-initiated data transfers on Android 16+ (API 36)
- **What this fixes**: Prevents background downloads from being killed due to thermal throttling or job quota restrictions
- **Requirements**: The library automatically includes the `RUN_USER_INITIATED_JOBS` permission and marks downloads as user-initiated when running on Android 16+
- **No action needed**: This is handled automatically by the library - your downloads will continue reliably even under moderate thermal conditions (~40°C) on Android 16+

#### Pause/Resume Support
- **Implementation**: Pause/resume on Android is implemented using HTTP Range headers
- **How it works**: When you pause a download, the current progress is saved. When resumed, a new download starts from where it left off using the `Range` header
- **Server requirement**: The server must support HTTP Range requests for resume to work correctly. If the server doesn't support range requests, the download will restart from the beginning
- **Temp files**: During pause/resume, progress is stored in a `.tmp` file which is renamed to the final destination upon completion

### Google Play Console Declaration

The library uses Foreground Service permissions (`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`) to enable reliable background downloads. **Google Play requires you to declare foreground service usage in the Play Console** when publishing your app.

If you see this error when submitting to Google Play:

> "You must let us know whether your app uses any Foreground Service permissions."

Complete these steps in the Google Play Console:

1. Go to your app in the [Google Play Console](https://play.google.com/console)
2. Navigate to **App content** → **Foreground Service**
3. Select **Yes** when asked if your app uses Foreground Service permissions
4. Choose **Data sync** as the Foreground Service type
5. Select **Network processing** as the task
6. Provide a justification explaining that your app downloads files in the background with a user-visible notification

Example justification:
> "This app downloads files in the background using a foreground service with a user-visible notification. The foreground service ensures downloads continue reliably when the app is in the background or when the device is under memory pressure. Users initiate downloads and can see download progress via the notification."

This is a Play Console compliance step only—no additional code changes are required.

## Rules for proguard-rules.pro

If you encounter `java.lang.IllegalStateException: TypeToken must be created with a type argument: new TypeToken<...>()` in Android release builds, add these rules to your `proguard-rules.pro`:

```
# react-native-background-downloader - Keep config class used by Gson
-keep class com.eko.RNBGDTaskConfig { *; }

# Gson TypeToken support
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# MMKV
-keep class com.tencent.mmkv.** { *; }
-dontwarn com.tencent.mmkv.**
```

## Known Issues with New Architecture
When using larger files with the New Architecture, you may encounter `ERROR_CANNOT_RESUME` (error code 1008). This is a known limitation of Android's DownloadManager, not specific to this library or the New Architecture. The error includes enhanced messaging to help diagnose the issue.

**Workaround:** If you encounter this error frequently with large files, consider:
1. Breaking large downloads into smaller chunks
2. Implementing retry logic in your app
3. Using alternative download strategies for very large files

The library now provides enhanced error handling for this specific case with detailed logging and cleanup.

## TODO

- [ ] Write better API for downloads - current kinda boilerplate

## Authors

Re-written & maintained by [Kesha Antonov](https://github.com/kesha-antonov)

Originally developed by [Elad Gil](https://github.com/ptelad) of [Eko](https://github.com/ekolabs/react-native-background-downloader)

## License
Apache 2

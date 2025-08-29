![react-native-background-downloader banner](https://d1w2zhnqcy4l8f.cloudfront.net/content/falcon/production/projects/V5EEOX_fast/RNBD-190702083358.png)

[![npm version](https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader.svg)](https://badge.fury.io/js/@kesha-antonov%2Freact-native-background-downloader)

# @kesha-antonov/react-native-background-downloader

A library for React-Native to help you download and upload large files on iOS and Android both in the foreground and most importantly in the background.

### Why?

On iOS, if you want to download big files no matter the state of your app, wether it's in the background or terminated by the OS, you have to use a system API called `NSURLSession`.

This API handles your downloads separately from your app and only keeps it informed using delegates (Read: [Downloading Files in the Background](https://developer.apple.com/documentation/foundation/url_loading_system/downloading_files_in_the_background)).

On Android we are using similar process with [DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)

The real challenge of using this method is making sure the app's UI is always up-to-date with the downloads that are happening in another process because your app might startup from scratch while the downloads are still running.

`@kesha-antonov/react-native-background-downloader` gives you an easy API to both downloading and uploading large files and re-attaching to those transfers once your app launches again.

## ToC

- [Usage](#usage)
- [API](#api)
- [Constants](#constants)

## Getting started

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

### New Architecture Support

This library supports React Native's New Architecture (Fabric + TurboModules) starting from React Native 0.70+.

#### Automatic Detection
The library automatically detects whether the New Architecture is enabled in your app and uses the appropriate implementation:
- **New Architecture**: Uses TurboModules for optimal performance
- **Legacy Architecture**: Uses the traditional bridge implementation

#### Manual Setup (Advanced)
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

#### Known Issues with New Architecture
When using larger files with the New Architecture, you may encounter `ERROR_CANNOT_RESUME` (error code 1008). This is a known limitation of Android's DownloadManager, not specific to this library or the New Architecture. The error includes enhanced messaging to help diagnose the issue.

**Workaround:** If you encounter this error frequently with large files, consider:
1. Breaking large downloads into smaller chunks
2. Implementing retry logic in your app
3. Using alternative download strategies for very large files

The library now provides enhanced error handling for this specific case with detailed logging and cleanup.

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

#### (react-native 0.77+)

<details>
  <summary>Click to expand</summary>

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

#### (react-native < 0.77)

<details>
  <summary>Click to expand</summary>

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

## Compatibility

### MMKV Storage Libraries

This library uses MMKV for efficient storage and is compatible with popular React Native MMKV libraries:

- **react-native-mmkv**: ✅ Compatible (v3.x and later)
- **react-native-mmkv-storage**: ✅ Compatible

The library uses flexible MMKV version ranges (iOS: `>= 2.1.0, < 3.0`, Android: `[2.1.0,3.0)`) to avoid version conflicts when used alongside other MMKV-based libraries. No additional configuration is required.

**Note**: If you encounter build issues related to MMKV when using multiple MMKV-based libraries, ensure all libraries are up to date and consider cleaning your build cache (`cd ios && pod cache clean --all && pod install`).

## Usage

### Downloading a file

```javascript
import { Platform } from 'react-native'
import { download, completeHandler, directories } from '@kesha-antonov/react-native-background-downloader'

const jobId = 'file123'

let task = download({
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

// Pause the task (iOS only)
// Note: On Android, pause/resume is not supported by DownloadManager
task.pause()

// Resume after pause (iOS only)
// Note: On Android, pause/resume is not supported by DownloadManager
task.resume()

// Cancel the task
task.stop()
```

### Platform-Aware Pause/Resume

```javascript
import { Platform } from 'react-native'
import { download, directories } from '@kesha-antonov/react-native-background-downloader'

let task = download({
  id: 'file123',
  url: 'https://link-to-very.large/file.zip',
  destination: `${directories.documents}/file.zip`,
  metadata: {}
}).begin(({ expectedBytes, headers }) => {
  console.log(`Going to download ${expectedBytes} bytes!`)
}).progress(({ bytesDownloaded, bytesTotal }) => {
  console.log(`Downloaded: ${bytesDownloaded / bytesTotal * 100}%`)
}).done(({ bytesDownloaded, bytesTotal }) => {
  console.log('Download is done!', { bytesDownloaded, bytesTotal })
}).error(({ error, errorCode }) => {
  console.log('Download canceled due to error: ', { error, errorCode });
})

// Platform-aware pause/resume handling
function pauseDownload() {
  if (Platform.OS === 'ios') {
    task.pause()
    console.log('Download paused')
  } else {
    console.log('Pause not supported on Android. Consider using stop() instead.')
  }
}

function resumeDownload() {
  if (Platform.OS === 'ios') {
    task.resume()
    console.log('Download resumed')
  } else {
    console.log('Resume not supported on Android. You may need to restart the download.')
  }
}
```

### Re-Attaching to background downloads

This is the main selling point of this library (but it's free!).

What happens to your downloads after the OS stopped your app? Well, they are still running, we just need to re-attach to them.

Add this code to app's init stage, and you'll never lose a download again!

```javascript
import RNBackgroundDownloader from '@kesha-antonov/react-native-background-downloader'

let lostTasks = await RNBackgroundDownloader.checkForExistingDownloads()
for (let task of lostTasks) {
  console.log(`Task ${task.id} was found!`)
  task.progress(({ bytesDownloaded, bytesTotal }) => {
    console.log(`Downloaded: ${bytesDownloaded / bytesTotal * 100}%`)
  }).done(({ bytesDownloaded, bytesTotal }) => {
    console.log('Download is done!', { bytesDownloaded, bytesTotal })
  }).error(({ error, errorCode }) => {
    console.log('Download canceled due to error: ', { error, errorCode })
  })
}
```

`task.id` is very important for re-attaching the download task with any UI component representing that task. This is why you need to make sure to give sensible IDs that you know what to do with, try to avoid using random IDs.

### Using custom headers
If you need to send custom headers with your download request, you can do in it 2 ways:

1) Globally using `RNBackgroundDownloader.setConfig()`:
```javascript
RNBackgroundDownloader.setConfig({
  headers: {
    Authorization: 'Bearer 2we$@$@Ddd223',
  }
})
```
This way, all downloads with have the given headers.

2) Per download by passing a headers object in the options of `RNBackgroundDownloader.download()`:
```javascript
let task = RNBackgroundDownloader.download({
  id: 'file123',
  url: 'https://link-to-very.large/file.zip'
  destination: `${RNBackgroundDownloader.directories.documents}/file.zip`,
  headers: {
    Authorization: 'Bearer 2we$@$@Ddd223'
  }
}).begin(({ expectedBytes, headers }) => {
  console.log(`Going to download ${expectedBytes} bytes!`)
}).progress(({ bytesDownloaded, bytesTotal }) => {
  console.log(`Downloaded: ${bytesDownloaded / bytesTotal * 100}%`)
}).done(({ bytesDownloaded, bytesTotal }) => {
  console.log('Download is done!', { bytesDownloaded, bytesTotal })
}).error(({ error, errorCode }) => {
  console.log('Download canceled due to error: ', { error, errorCode })
})
```
Headers given in the `download` function are **merged** with the ones given in `setConfig({ headers: { ... } })`.

### Handling Slow-Responding URLs

This library automatically includes connection timeout improvements for slow-responding URLs. By default, the following headers are added to all download requests on Android:

- `Connection: keep-alive` - Keeps the connection open for better handling
- `Keep-Alive: timeout=600, max=1000` - Sets a 10-minute keep-alive timeout
- `User-Agent: ReactNative-BackgroundDownloader/3.2.6` - Proper user agent for better server compatibility

These headers help prevent downloads from getting stuck in "pending" state when servers take several minutes to respond initially. You can override these headers by providing your own in the `headers` option.

## API

### RNBackgroundDownloader

### `download(options)`

Download a file to destination

**options**

An object containing options properties

| Property      | Type                                             | Required | Platforms | Info                                                                                                                                                                                                                                 |
| ------------- | ------------------------------------------------ | :------: | :-------: | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `id`          | String |    ✅     |    All    | A Unique ID to provide for this download. This ID will help to identify the download task when the app re-launches |
| `url`         | String |    ✅     |    All    | URL to file you want to download |
| `destination` | String |    ✅     |    All    | Where to copy the file to once the download is done |
| `metadata`    | Object |           |    All    | Data to be preserved on reboot. |
| `headers`     | Object |           |    All    | Costume headers to add to the download request. These are merged with the headers given in the `setConfig({ headers: { ... } })` function |
| `isAllowedOverRoaming` | Boolean   |          |  Android  | whether this download may proceed over a roaming connection. By default, roaming is allowed |
| `isAllowedOverMetered` | Boolean   |          |  Android  | Whether this download may proceed over a metered network connection. By default, metered networks are allowed |
| `isNotificationVisible`     | Boolean   |          |  Android  | Whether to show a download notification or not |
| `notificationTitle`     | String   |          |  Android  | Title of the download notification |

**returns**

`DownloadTask` - The download task to control and monitor this download

### `upload(options)` (Experimental)

Upload a file to a server endpoint

**options**

| Name           | Type   | Required | Info                                                                                                     |
| -------------- | ------ | -------- | -------------------------------------------------------------------------------------------------------- |
| `id`           | String | ✓        | Unique identifier for this upload task                                                                   |
| `url`          | String | ✓        | The server endpoint URL to upload to                                                                     |
| `source`       | String | ✓        | Absolute path to the file to upload                                                                      |
| `headers`      | Object |          | HTTP headers to send with the upload request                                                             |
| `method`       | String |          | HTTP method (`POST`, `PUT`, `PATCH`). Default: `POST`                                                   |
| `fieldName`    | String |          | The form field name for the file. Default: `file`                                                       |
| `mimeType`     | String |          | MIME type of the file being uploaded. Auto-detected if not provided                                     |
| `metadata`     | Object |          | Any data you wish to store with this task                                                               |

**Note:** Upload functionality is currently in experimental stage with stub implementations. iOS implementation is planned using NSURLSessionUploadTask, Android implementation would require WorkManager or similar background processing solution.

**returns**

`UploadTask` - The upload task to control and monitor this upload

### `checkForExistingUploads()` (Experimental)

Checks for uploads that ran in background while you app was terminated.

**returns**

`UploadTask[]` - Array of upload tasks that were running in the background

**Note:** Currently returns empty array as full implementation is pending.

### `checkForExistingDownloads()`

Checks for downloads that ran in background while you app was terminated. And also forces them to resume downloads.

Recommended to run at the init stage of the app.

**returns**

`DownloadTask[]` - Array of tasks that were running in the background so you can re-attach callbacks to them

### `setConfig({})`

| Name           | Type   | Info                                                                                                 |
| -------------- | ------ | ---------------------------------------------------------------------------------------------------- |
| `headers`     | Object | optional headers to use in all future downloads |
| `progressInterval` | Number | Interval in which download progress sent from downloader. Number should be >= 250. It's in ms |
| `progressMinBytes` | Number | Minimum number of bytes that must be downloaded before triggering progress callbacks. Used for hybrid progress reporting (triggers on either percentage >1% OR bytes threshold). Default is 1048576 (1MB). Number should be >= 0 |
| `isLogsEnabled`   | Boolean | Enables/disables logs in library |

### DownloadTask

A class representing a download task created by `RNBackgroundDownloader.download`

### `Members`
| Name           | Type   | Info                                                                                                 |
| -------------- | ------ | ---------------------------------------------------------------------------------------------------- |
| `id`           | String | The id you gave the task when calling `RNBackgroundDownloader.download`                              |
| `metadata`     | Object | The metadata you gave the task when calling `RNBackgroundDownloader.download`                        |
| `bytesDownloaded` | Number | The number of bytes currently written by the task                                                    |
| `bytesTotal`   | Number | The number bytes expected to be written by this task or more plainly, the file size being downloaded |

### UploadTask (Experimental)

A class representing an upload task created by `RNBackgroundDownloader.upload`

### `Members`
| Name           | Type   | Info                                                                                                 |
| -------------- | ------ | ---------------------------------------------------------------------------------------------------- |
| `id`           | String | The id you gave the task when calling `RNBackgroundDownloader.upload`                              |
| `metadata`     | Object | The metadata you gave the task when calling `RNBackgroundDownloader.upload`                        |
| `bytesUploaded` | Number | The number of bytes currently uploaded by the task                                                 |
| `bytesTotal`   | Number | The number bytes expected to be uploaded by this task or more plainly, the file size being uploaded |

### `Callback Methods`

UploadTask has the same callback methods as DownloadTask:

- `begin((expectedBytes, headers) => {})` - Called when the upload begins
- `progress(({ bytesDownloaded, bytesTotal }) => {})` - Called on upload progress (note: uses same field names as download for consistency)  
- `done(({ bytesDownloaded, bytesTotal }) => {})` - Called when upload completes successfully
- `error(({ error, errorCode }) => {})` - Called when upload fails

**Control Methods:**
- `pause()` - Pauses the upload (iOS only when implemented)
- `resume()` - Resumes a paused upload (iOS only when implemented)
- `stop()` - Stops and cancels the upload

### `completeHandler(jobId)`

Finishes download job and informs OS that app can be closed in background if needed.
After finishing download in background you have some time to process your JS logic and finish the job.

### `ensureDownloadsAreRunning` (iOS only)

Pauses and resumes all downloads - this is fix for stuck downloads. Use it when your app loaded and is ready for handling downloads (all your logic loaded and ready to handle download callbacks).

### `ensureUploadsAreRunning` (iOS only) (Experimental)

Similar to `ensureDownloadsAreRunning` but for upload tasks. When implemented, will pause and resume all uploads to fix stuck upload tasks.

Here's example of how you can use it:

1. When your app just loaded

Either stop all tasks:

```javascript
const tasks = await checkForExistingDownloads()
for (const task of tasks)
  task.stop()
```

Or re-attach them:

```javascript
const tasks = await checkForExistingDownloads()
for (const task of tasks) {
  task.pause()
  //
  // YOUR LOGIC OF RE-ATTACHING DOWLOADS TO YOUR STUFF
  // ...
  //
}
```

2. Prepare your app to handle downloads... (load your state etc.)

3. Add listener to handle when your app goes foreground (be sure to do it only after you stopped all tasks or re-attached them!)

```javascript

function handleAppStateChange (appState) {
  if (appState !== 'active')
    return

  ensureDownloadsAreRunning()
}

const appStateChangeListener = AppState.addEventListener('change', handleAppStateChange)
```

4. Call `ensureDownloadsAreRunning()` after all was setup.

### `Callback Methods`
Use these methods to stay updated on what's happening with the task.

All callback methods return the current instance of the `DownloadTask` for chaining.

| Function   | Callback Arguments                | Info|
| ---------- | --------------------------------- | ---- |
| `begin`    | { expectedBytes, headers } | Called when the first byte is received. 💡: this is good place to check if the device has enough storage space for this download |
| `progress` | { bytesDownloaded, bytesTotal } | Called at max every 1.5s so you can update your progress bar accordingly |
| `done`     | { bytesDownloaded, bytesTotal } | Called when the download is done, the file is at the destination you've set |
| `error`    | { error, errorCode } | Called when the download stops due to an error |

### `pause()`  (iOS only)
Pauses the download

**Note:** This functionality is not supported on Android due to limitations in the DownloadManager API. On Android, calling this method will log a warning but will not crash the application.

### `resume()`  (iOS only)
Resumes a paused download

**Note:** This functionality is not supported on Android due to limitations in the DownloadManager API. On Android, calling this method will log a warning but will not crash the application.

### `stop()`
Stops the download for good and removes the file that was written so far

## Constants

### directories

### `documents`

An absolute path to the app's documents directory. It is recommended that you use this path as the target of downloaded files.

## Platform-Specific Limitations

### Android DownloadManager Limitations

The Android implementation uses the system's `DownloadManager` service, which has some limitations compared to iOS:

#### Pause/Resume Not Supported
- **Issue**: Android's DownloadManager does not provide a public API for pausing and resuming downloads
- **Impact**: Calling `task.pause()` or `task.resume()` on Android will log a warning but not perform any action
- **Workaround**: If you need to stop a download, use `task.stop()` and restart it later with a new download request
- **Technical Details**: The private APIs needed for pause/resume functionality are not accessible to third-party applications

#### Alternative Approaches for Android
If pause/resume functionality is critical for your application, consider:
1. Using `task.stop()` and tracking progress to restart downloads from where they left off (if the server supports range requests)
2. Implementing a custom download solution for Android that doesn't use DownloadManager
3. Designing your app flow to minimize the need for pause/resume functionality

## Rules for proguard-rules.pro

In case of error `java.lang.IllegalStateException: TypeToken must be created with a type argument: new TypeToken<...>()` in Android release add this to `proguard-rules.pro`:

```
# Application classes that will be serialized/deserialized over Gson
-keep class com.yourapplicationname.model.api.** { *; }

# Gson uses generic type information stored in a class file when working with
# fields. Proguard removes such information by default, keep it.
-keepattributes Signature

# This is also needed for R8 in compat mode since multiple
# optimizations will remove the generic signature such as class
# merging and argument removal. See:
# https://r8.googlesource.com/r8/+/refs/heads/main/compatibility-faq.md#troubleshooting-gson-gson
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Optional. For using GSON @Expose annotation
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations
```

## TODO

- [ ] Write better examples - current kinda old and shallow
- [ ] Write better API for downloads - current kinda boilerplate
- [ ] Add more tests

## Authors

Re-written & maintained by [Kesha Antonov](https://github.com/kesha-antonov)

Originally developed by [Elad Gil](https://github.com/ptelad) of [Eko](https://github.com/ekolabs/react-native-background-downloader)

## License
Apache 2

# API Reference

Complete API documentation for `@kesha-antonov/react-native-background-downloader`.

## Table of Contents

- [API Reference](#api-reference)
  - [Table of Contents](#table-of-contents)
  - [Named Exports](#named-exports)
  - [`createDownloadTask(options)`](#createdownloadtaskoptions)
    - [Options](#options)
    - [Returns](#returns)
  - [`getExistingDownloadTasks()`](#getexistingdownloadtasks)
    - [Returns](#returns-1)
  - [`createUploadTask(options)`](#createuploadtaskoptions)
    - [Options](#options-1)
    - [Returns](#returns-2)
  - [`getExistingUploadTasks()`](#getexistinguploadtasks)
    - [Returns](#returns-3)
  - [`setConfig(config)`](#setconfigconfig)
    - [Config Options](#config-options)
    - [Example](#example)
  - [`completeHandler(jobId: string)`](#completehandlerjobid-string)
    - [Parameters](#parameters)
  - [DownloadTask](#downloadtask)
    - [Members](#members)
    - [Callback Methods](#callback-methods)
    - [Methods](#methods)
      - [`pause(): Promise<void>`](#pause-promisevoid)
      - [`resume(): Promise<void>`](#resume-promisevoid)
      - [`stop(): Promise<void>`](#stop-promisevoid)
      - [`start(): void`](#start-void)
  - [UploadTask](#uploadtask)
    - [Members](#members-1)
    - [Callback Methods](#callback-methods-1)
    - [Methods](#methods-1)
  - [Constants](#constants)
    - [`directories`](#directories)
      - [`documents`](#documents)

## Named Exports

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

---

## `createDownloadTask(options)`

Download a file to destination.

### Options

| Property      | Type                                             | Required | Platforms | Description |
| ------------- | ------------------------------------------------ | :------: | :-------: | ----------- |
| `id`          | String |    ✅     |    All    | A unique ID to provide for this download. This ID will help to identify the download task when the app re-launches |
| `url`         | String |    ✅     |    All    | URL to file you want to download |
| `destination` | String |    ✅     |    All    | Where to copy the file to once the download is done. The 'file://' prefix will be automatically removed if present |
| `metadata`    | Record<string, unknown> |           |    All    | Custom data to be preserved across app restarts. Will be serialized to JSON |
| `headers`     | Record<string, string \| null> |           |    All    | Custom headers to add to the download request. These are merged with the headers given in `setConfig({ headers: { ... } })`. Headers with null values will be removed |
| `maxRedirects` | Number |          |  Android  | Maximum number of redirects to follow before passing URL to DownloadManager. If not specified or 0, no redirect resolution is performed. Helps avoid ERROR_TOO_MANY_REDIRECTS for URLs with many redirects (e.g., podcast URLs) |
| `isAllowedOverRoaming` | Boolean   |          |  Android  | Whether this download may proceed over a roaming connection. By default, roaming is allowed |
| `isAllowedOverMetered` | Boolean   |          |  Android  | Whether this download may proceed over a metered network connection. By default, metered networks are allowed |

### Returns

`DownloadTask` - The download task to control and monitor this download. Call `task.start()` to begin the download.

---

## `getExistingDownloadTasks()`

Checks for downloads that ran in background while your app was terminated.

Recommended to run at the init stage of the app.

### Returns

`Promise<DownloadTask[]>` - A promise that resolves to an array of tasks that were running in the background so you can re-attach callbacks to them.

---

## `createUploadTask(options)`

Upload a file to a server.

### Options

| Property      | Type                                             | Required | Platforms | Description |
| ------------- | ------------------------------------------------ | :------: | :-------: | ----------- |
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

### Returns

`UploadTask` - The upload task to control and monitor this upload. Call `task.start()` to begin the upload.

---

## `getExistingUploadTasks()`

Checks for uploads that ran in background while your app was terminated.

Recommended to run at the init stage of the app.

### Returns

`Promise<UploadTask[]>` - A promise that resolves to an array of upload tasks that were running in the background so you can re-attach callbacks to them.

---

## `setConfig(config)`

Sets global configuration for the downloader.

### Config Options

| Name           | Type   | Description |
| -------------- | ------ | ----------- |
| `headers`     | Record<string, string \| null> | Optional headers to use in all future downloads. Headers with null values will be removed |
| `progressInterval` | Number | Interval in milliseconds for download progress updates. Must be >= 250. Default is 1000 |
| `progressMinBytes` | Number | Minimum number of bytes that must be downloaded before a progress event is emitted. When set to 0, only the percentage threshold (1% change) triggers progress updates. Default is 1048576 (1MB) |
| `isLogsEnabled`   | Boolean | Enables/disables verbose debug logs in native code (iOS and Android). Works in both debug and release builds. Default is false |
| `logCallback`   | (log: { message: string, taskId?: string }) => void | Optional callback function to receive native debug logs in JavaScript. Only called when `isLogsEnabled` is true |
| `maxParallelDownloads` | Number | **iOS only**. Sets the maximum number of simultaneous connections per host for the download session. Must be >= 1. Default is 4. Note: Android's DownloadManager does not support this configuration |
| `allowsCellularAccess` | Boolean | Controls whether downloads are allowed over cellular (metered) connections. When set to `false`, downloads will only occur over WiFi. Default is `true`. This is a cross-platform abstraction - on iOS it sets `allowsCellularAccess`, on Android it sets `isAllowedOverMetered` |
| `showNotificationsEnabled` | Boolean | **Android only**. Show full download notifications. When `false`, creates minimal silent notifications (UIDT jobs on Android 14+ require a notification, but it will be barely visible). Default is `false` |
| `notificationsGrouping` | Object | **Android only**. Configuration for notification grouping. See below for details |

**notificationsGrouping Options (Android only):**

| Name | Type | Description |
| ---- | ---- | ----------- |
| `enabled` | Boolean | Enable notification grouping. Default is `false` |
| `texts` | Object | Customizable notification texts. All fields are optional - defaults are used for any omitted fields |

**notificationsGrouping.texts — Customizable Notification Strings:**

| Key | Default | Placeholders | Description |
| --- | ------- | ------------ | ----------- |
| `downloadTitle` | `'Download'` | — | Title shown in individual download notifications |
| `downloadStarting` | `'Starting download...'` | — | Notification text when a download begins |
| `downloadProgress` | `'Downloading... {progress}%'` | `{progress}` — current percentage (0-100) | Text shown while download is in progress |
| `downloadPaused` | `'Paused'` | — | Text shown when download is paused |
| `downloadFinished` | `'Download complete'` | — | Text shown when download completes |
| `groupTitle` | `'Downloads'` | — | Title for the group summary notification (when multiple downloads are active) |
| `groupText` | `'{count} download(s) in progress'` | `{count}` — number of active downloads | Summary text showing how many downloads are running |

### Example

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

// Android: Enable notifications with custom texts
setConfig({
  showNotificationsEnabled: true,
  notificationsGrouping: {
    enabled: true,
    texts: {
      downloadTitle: 'My App Download',
      downloadStarting: 'Preparing...',
      downloadProgress: '{progress}% complete',
      downloadPaused: 'Download paused',
      downloadFinished: 'Done!',
      groupTitle: 'Downloads',
      groupText: '{count} files downloading',
    },
  },
})
```

---

## `completeHandler(jobId: string)`

Finishes download job and informs OS that app can be closed in background if needed.
After finishing download in background you have some time to process your JS logic and finish the job.

### Parameters

- `jobId` (String) - The ID of the download task to complete

> **Note:** This should be called after processing your download in the `done` callback to properly signal completion to the OS.

---

## DownloadTask

A class representing a download task created by `createDownloadTask()`.

> **Note:** You must call `task.start()` to begin the download after setting up event handlers.

### Members

| Name           | Type   | Description |
| -------------- | ------ | ----------- |
| `id`           | String | The id you gave the task when calling `createDownloadTask` |
| `metadata`     | Record<string, unknown> | The metadata you gave the task when calling `createDownloadTask` |
| `state`        | 'PENDING' \| 'DOWNLOADING' \| 'PAUSED' \| 'DONE' \| 'FAILED' \| 'STOPPED' | Current state of the download task |
| `bytesDownloaded` | Number | The number of bytes currently written by the task |
| `bytesTotal`   | Number | The number bytes expected to be written by this task or more plainly, the file size being downloaded. **Note:** This value will be `-1` if the server does not provide a `Content-Length` header |
| `downloadParams` | DownloadParams | The download parameters set for this task |

### Callback Methods

Use these methods to stay updated on what's happening with the task. All callback methods return the current instance of the `DownloadTask` for chaining.

| Function   | Callback Arguments                | Description |
| ---------- | --------------------------------- | ----------- |
| `begin`    | `{ expectedBytes: number, headers: Record<string, string \| null> }` | Called when the first byte is received. 💡 This is a good place to check if the device has enough storage space for this download |
| `progress` | `{ bytesDownloaded: number, bytesTotal: number }` | Called based on progressInterval (default: every 1000ms) so you can update your progress bar accordingly. **Note:** `bytesTotal` will be `-1` if the server does not provide a `Content-Length` header |
| `done`     | `{ location: string, bytesDownloaded: number, bytesTotal: number }` | Called when the download is done, the file is at the destination you've set. `location` is the final file path. **Note:** `bytesTotal` will be `-1` if the server did not provide a `Content-Length` header |
| `error`    | `{ error: string, errorCode: number }` | Called when the download stops due to an error |

### Methods

#### `setDownloadParams(params): Promise<boolean>`

Updates the download parameters. If the task is paused, this will also update headers in the native layer, allowing you to refresh authentication tokens before resuming.

**Parameters:**
- `params` (DownloadParams) - The new download parameters. Same structure as options passed to `createDownloadTask()`

**Returns:** `Promise<boolean>` - Resolves to `true` if native headers were updated (task was paused), `false` otherwise.

**Example:**
```javascript
// Update auth token on a paused download before resuming
if (task.state === 'PAUSED') {
  const updated = await task.setDownloadParams({
    ...task.downloadParams,
    headers: {
      ...task.downloadParams?.headers,
      Authorization: 'Bearer new-refreshed-token'
    }
  })
  console.log('Headers updated:', updated) // true
  await task.resume()
}
```

> **Note:** When headers are updated on a paused task:
> - **iOS:** The download resumes using a fresh request with HTTP Range header and the new headers
> - **Android:** Both in-memory headers and persisted paused download state are updated

#### `pause(): Promise<void>`

Pauses the download. Returns a promise that resolves when the pause operation is complete.

> **Note:** On Android, pause/resume is implemented using HTTP Range headers, which requires server support. The download progress is saved and resumed from where it left off.

#### `resume(): Promise<void>`

Resumes a paused download. Returns a promise that resolves when the resume operation is complete.

> **Note:** On Android, this uses HTTP Range headers to resume from the last downloaded byte position. If the server doesn't support range requests, the download will restart from the beginning.

#### `stop(): Promise<void>`

Stops the download for good and removes the file that was written so far. Returns a promise that resolves when the stop operation is complete.

#### `start(): void`

Starts the download.

---

## UploadTask

A class representing an upload task created by `createUploadTask()`.

> **Note:** You must call `task.start()` to begin the upload after setting up event handlers.

### Members

| Name           | Type   | Description |
| -------------- | ------ | ----------- |
| `id`           | String | The id you gave the task when calling `createUploadTask` |
| `metadata`     | Record<string, unknown> | The metadata you gave the task when calling `createUploadTask` |
| `state`        | 'PENDING' \| 'UPLOADING' \| 'PAUSED' \| 'DONE' \| 'FAILED' \| 'STOPPED' | Current state of the upload task |
| `bytesUploaded` | Number | The number of bytes currently uploaded by the task |
| `bytesTotal`   | Number | The total number bytes to be uploaded by this task |
| `uploadParams` | UploadParams | The upload parameters set for this task |

### Callback Methods

| Function   | Callback Arguments                | Description |
| ---------- | --------------------------------- | ----------- |
| `begin`    | `{ expectedBytes: number }` | Called when upload starts |
| `progress` | `{ bytesUploaded: number, bytesTotal: number }` | Called based on progressInterval (default: every 1000ms) so you can update your progress bar accordingly |
| `done`     | `{ responseCode: number, responseBody: string, bytesUploaded: number, bytesTotal: number }` | Called when the upload is done. Includes server response code and body |
| `error`    | `{ error: string, errorCode: number }` | Called when the upload stops due to an error |

### Methods

- `pause(): Promise<void>` - Pauses the upload (platform support may vary)
- `resume(): Promise<void>` - Resumes a paused upload
- `stop(): Promise<void>` - Stops the upload and removes temporary data
- `start(): void` - Starts the upload

---

## Constants

### `directories`

#### `documents`

An absolute path to the app's documents directory. It is recommended that you use this path as the target of downloaded files.

```javascript
import { directories } from '@kesha-antonov/react-native-background-downloader'

const destination = `${directories.documents}/my-file.zip`
```

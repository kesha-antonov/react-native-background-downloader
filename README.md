# @anorak-games/react-native-background-downloader

Reliable, process-owned native file downloads and uploads for React Native and Expo.

This fork uses direct native HTTP transfers to avoid downloads stalling, while deliberately avoiding background-execution APIs. It adds no Android permissions, services, jobs, receivers, providers, notifications, or wake locks, and it does not use an iOS background `URLSession`.

Transfers may continue while the application process remains runnable. The operating system may suspend or terminate them after the app backgrounds. Tasks are not recoverable after process death.

## Installation

This package requires React Native 0.76 or newer with the New Architecture enabled. Android and iOS builds fail with a clear error when it is disabled; there is no legacy bridge implementation.

```sh
npm install @anorak-games/react-native-background-downloader
```

Expo projects should include the plugin:

```json
{
  "expo": {
    "plugins": [
      [
        "@anorak-games/react-native-background-downloader",
        {
          "maxParallelDownloads": 4,
          "enableLogging": false,
          "progressInterval": 1000,
          "progressMinBytes": 1048576
        }
      ]
    ]
  }
}
```

The plugin writes only private Android application metadata and iOS `Info.plist` values. It does not modify the AppDelegate, bridging header, Gradle dependencies, permissions, or background modes.

| Option | Default | Validation |
| --- | ---: | --- |
| `maxParallelDownloads` | `4` | Positive integer |
| `enableLogging` | `false` | Boolean |
| `progressInterval` | `1000` ms | Integer, at least `250` |
| `progressMinBytes` | `1048576` bytes | Non-negative integer |

Configuration is read once when the native process coordinator starts. Rebuild the native app after changing plugin options.

## Download

```ts
import {
  createDownloadTask,
  directories,
} from '@anorak-games/react-native-background-downloader'

const task = createDownloadTask({
  id: 'archive',
  url: 'https://example.com/archive.zip',
  destination: `${directories.documents}/archive.zip`,
  headers: { Authorization: 'Bearer token' },
  metadata: { kind: 'archive' },
})

task
  .begin(({ expectedBytes }) => console.log('size', expectedBytes))
  .progress(({ bytesDownloaded, bytesTotal }) => console.log(bytesDownloaded, bytesTotal))
  .done(({ location }) => console.log('saved', location))
  .error(({ error, errorCode }) => console.error(errorCode, error))

task.start()
```

Downloads support `pause()`, `resume()`, and `stop()`. Android resumes with HTTP Range requests only when a strong `ETag` or `Last-Modified` validator proves the resource is unchanged; otherwise it restarts cleanly from byte zero. iOS suspends and resumes the current process-owned `URLSessionTask`.

Each active download must have its own destination path. Running multiple downloads against the same destination is unsupported. On iOS, replacing an existing destination is not transactional; download to a unique path and perform the final replacement in application code when the previous file must be preserved.

## Upload

```ts
import { createUploadTask } from '@anorak-games/react-native-background-downloader'

const task = createUploadTask({
  id: 'upload',
  url: 'https://example.com/upload',
  source: '/absolute/path/file.bin',
  method: 'PUT',
  headers: { Authorization: 'Bearer token' },
})

task
  .progress(({ bytesUploaded, bytesTotal }) => console.log(bytesUploaded, bytesTotal))
  .done(({ responseCode, responseBody }) => console.log(responseCode, responseBody))
  .error(({ error, errorCode }) => console.error(errorCode, error))

task.start()
```

Multipart uploads can also specify `fieldName`, `mimeType`, and string `parameters`.

## Runtime reloads and task reconciliation

The native coordinator is process-scoped and independent of a particular React Native runtime. During an Expo OTA or React runtime reload it keeps current transfers alive, buffers native events, and rejects stale-runtime delivery.

Call `getExistingDownloadTasks()` and `getExistingUploadTasks()` after a new runtime starts to reconcile tasks from the current process. These functions never restore work after the operating system kills or relaunches the process.

The native runtime-event buffer holds at most 256 coalesced entries. If more entries accumulate before JavaScript reconciles and acknowledges them, the oldest entries are discarded.

## Timeouts

Android uses 30-second connection and read inactivity timeouts. iOS uses a 30-second request inactivity timeout and a 24-hour total resource timeout for large transfers. A stalled socket therefore fails instead of remaining active indefinitely.

See [API](docs/API.md) and [platform notes](docs/PLATFORM_NOTES.md) for the complete contract.

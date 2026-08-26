# @anorak-games/react-native-background-downloader

A small, opinionated React Native downloader focused on:

- Running downloads outside the React Native runtime using `URLSession` on iOS and `HttpURLConnection` on Android.
- Surviving Expo OTA updates and other JavaScript runtime reloads.
- Reliably pausing, resuming, stopping, and recovering downloads owned by the current native process.

It adds no Android permissions or background components. Downloads survive JavaScript replacement, not process death. The operating system may suspend or terminate them after the app is backgrounded.

This is a fork of [react-native-background-downloader](https://github.com/kesha-antonov/react-native-background-downloader). If you want durable background downloads, app-termination recovery, notifications, or the broader original feature set, use upstream.

## Installation

React Native 0.76 or newer with the New Architecture enabled is required.

```sh
npm install @anorak-games/react-native-background-downloader
```

Expo projects should also add the config plugin:

```json
{
  "expo": {
    "plugins": ["@anorak-games/react-native-background-downloader"]
  }
}
```

## Download

```ts
import { createDownloadTask, directories } from '@anorak-games/react-native-background-downloader'

const task = createDownloadTask({
  id: 'archive',
  url: 'https://example.com/archive.zip',
  destination: `${directories.documents}/archive.zip`,
  metadata: { kind: 'archive' },
})

task
  .progress(({ bytesDownloaded, bytesTotal }) => console.log(bytesDownloaded, bytesTotal))
  .done(({ location }) => console.log('saved', location))
  .error(({ error, errorCode }) => console.error(errorCode, error))

task.start()
```

Set `expectedSha256` to a lowercase SHA-256 hex digest to verify the completed temporary file
before it replaces the destination. A mismatch calls the error handler and leaves the destination
unchanged.

`pause()`, `resume()`, and `stop()` return promises.

## Recover after an OTA update

When a new JavaScript runtime starts, reconcile it with the native process before creating replacement tasks:

```ts
import { getExistingDownloadTasks } from '@anorak-games/react-native-background-downloader'

const tasks = await getExistingDownloadTasks()

for (const task of tasks) {
  task
    .progress(({ bytesDownloaded, bytesTotal }) => console.log(task.id, bytesDownloaded, bytesTotal))
    .done(({ location }) => console.log(task.id, 'saved', location))
    .error(({ error, errorCode }) => console.error(task.id, errorCode, error))
}
```

Completion and failure events that occur while JavaScript is being replaced are buffered by the native coordinator and delivered during reconciliation.

See the [API reference](docs/API.md) and [platform notes](docs/PLATFORM_NOTES.md) for configuration, uploads, and the complete contract.

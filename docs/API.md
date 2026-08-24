# API

## `createDownloadTask(options)`

Required options:

| Option | Type |
| --- | --- |
| `id` | `string` |
| `url` | `string` |
| `destination` | absolute path `string` |

Optional options are `headers: Record<string, string | null>` and `metadata: object`.

The returned task supports chained `begin`, `progress`, `done`, and `error` handlers plus `start()`, `pause()`, `resume()`, and `stop()`.

Download parameters are fixed when the task is created.

Each active download must use a distinct destination path. Running multiple downloads against the same destination is unsupported.

## `createUploadTask(options)`

Required options are `id`, `url`, and absolute `source` path. Optional options are `method` (`POST`, `PUT`, or `PATCH`), `headers`, `metadata`, `fieldName`, `mimeType`, and string `parameters`.

The returned task supports chained `begin`, `progress`, `done`, and `error` handlers plus `start()`, `pause()`, `resume()`, and `stop()`.

## Reconciliation

- `getExistingDownloadTasks(): Promise<DownloadTask[]>`
- `getExistingUploadTasks(): Promise<UploadTask[]>`

These return tasks owned by the current native process. They also activate delivery of events buffered during a React Native runtime reload. They do not restore tasks after process death.

Completion and failure events remain buffered until JavaScript acknowledges them while they remain within the native buffer. The buffer holds at most 256 coalesced entries and discards the oldest entry on overflow. If a task finishes during a runtime reload and its terminal event remains buffered, the reconstructed task retains the original metadata.

## Directories

`directories.documents` is the application documents/files directory appropriate to the current platform.

## Build-time configuration

Global configuration belongs in the Expo plugin options:

```json
[
  "@anorak-games/react-native-background-downloader",
  {
    "maxParallelDownloads": 4,
    "enableLogging": false,
    "progressInterval": 1000,
    "progressMinBytes": 1048576
  }
]
```

There is no runtime global configuration API. Request headers are configured per task.

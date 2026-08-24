# Platform notes

## Execution model

Transfers belong to a process-scoped native coordinator, not to a React Native bridge instance. React runtime invalidation detaches only the event sink; it does not cancel active transfers. A replacement runtime can reconcile the current process's tasks and receive buffered events.

The package requires React Native 0.76 or newer and supports only the New Architecture. Events use generated Codegen emitters on both platforms, with no legacy bridge fallback.

The library does not request durable background execution. Transfers may continue opportunistically after the app backgrounds, but the operating system may suspend or terminate the process. Process death ends every task and clears all native task state.

## Android

All supported Android versions use the library's direct `HttpURLConnection` downloader. It has 30-second connection/read inactivity timeouts, a concurrency queue, redirects, HTTP Range resume, and a clean restart when a server ignores Range.

The library manifest declares no permissions or application components. In particular, the package contributes no service, scheduled job, receiver, provider, notification, wake lock, or connectivity restriction. This direct path also avoids Android 16 destination restrictions associated with the platform download manager.

Paused downloads resume with `Range` and `If-Range` only when the original response supplied a strong `ETag` or `Last-Modified` validator. The returned `Content-Range` and validator must match before bytes are appended; otherwise the partial file is discarded and the download restarts from byte zero.

Each active download must use a distinct destination path. Android derives its partial-file path from the destination, so concurrent downloads targeting the same destination are unsupported.

The minimum Android SDK remains 24.

## iOS

iOS uses one process-owned default `URLSession` for downloads and uploads. The request inactivity timeout is 30 seconds and the total resource timeout is 24 hours.

There is no background session identifier, launch-event integration, AppDelegate hook, deferred locked-device move, background mode, or persistent recovery. Downloaded files are moved directly from the session temporary location to the requested destination. Replacing an existing destination is not transactional; applications that must preserve the existing file should download to a unique path and perform their own replacement after completion.

The minimum iOS version remains 15.1.

## Build settings

The Expo plugin writes the four package settings as private Android `<meta-data>` entries and private iOS `Info.plist` keys. They are not platform permissions or store capability declarations.

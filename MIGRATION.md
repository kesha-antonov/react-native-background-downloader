# Migration to process-owned transfers

This release is a direct cutover from background execution infrastructure to process-owned native transfers.

## New Architecture

This package now requires React Native 0.76 or newer with the New Architecture enabled. Enable it before installing the native package. Android and iOS builds reject legacy-architecture configurations rather than compiling a second bridge implementation.

## Configuration

Remove every `setConfig()` call. Configuration is now build-time only through the Expo plugin:

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

All four options are optional. Rebuild the native app after changing them; an OTA update cannot change native build settings.

The following runtime options no longer exist:

- Notification and notification-grouping configuration
- Cellular, metered, and roaming controls
- Redirect limits
- iOS data-protection selection
- Global runtime headers

Task-specific `headers` remain supported.
Download parameters and headers cannot be changed after task creation.

## Native declarations

Remove any declarations that were added only for this package:

- Android foreground-service, data-sync, user-initiated-job, wake-lock, and network-state permissions
- Android services, receivers, providers, or notification resources copied from older integration instructions
- iOS background modes and background URL-session completion handling
- Expo plugin options related to native persistence, notifications, AppDelegate patching, or dependency injection

The package's Android manifest is empty and its iOS integration does not add a background mode.

## Task lifetime

Transfers remain native and survive React Native runtime or Expo OTA reloads while the operating-system process remains alive. Reconcile them with `getExistingDownloadTasks()` and `getExistingUploadTasks()` after the new runtime starts.

The native runtime-event buffer holds at most 256 coalesced entries and discards the oldest entry on overflow. Reconcile promptly after a runtime reload when using large transfer batches.

Transfers do not survive process death. After a process kill or device restart, both reconciliation methods return no tasks from the previous process. Application-level cleanup and retry policy belongs to the consuming app.

Backgrounding does not automatically pause a transfer. It may continue opportunistically for as long as the process remains runnable, but the package requests no extended execution time.

## Pause and resume

Downloads retain manual pause, Range-based resume, and stop controls. Android appends a partial response only when a strong `ETag` or `Last-Modified` validator still matches; missing or changed validators and ignored Range requests restart from byte zero.

Each active download must use a distinct destination path. On iOS, applications that must preserve an existing destination should download to a unique path and perform the final replacement after completion.

Uploads retain pause, resume, and stop. Android restarts a paused HTTP upload from the beginning; iOS suspends and resumes its current `URLSessionTask`.

## Platform requirements

React Native 0.76 or newer with the New Architecture enabled is required. The Android library minimum remains API 24. The iOS deployment target remains 15.1.

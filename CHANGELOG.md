# Changelog

## v4.3.9 (Unreleased)

### ✨ New Features

- **Android 16 UIDT Support:** Downloads are now automatically marked as User-Initiated Data Transfers on Android 16+ (API 36) to prevent thermal throttling and job quota restrictions. Downloads will continue reliably even under moderate thermal conditions (~40°C).

### 🐛 Bug Fixes

- **iOS MMKV Conflict Fix:** Removed hard MMKV dependency from iOS podspec to prevent symbol conflicts with `react-native-mmkv`. Apps using `react-native-mmkv` no longer experience crashes (EXC_BAD_ACCESS) on iOS.

### 📦 Dependencies & Infrastructure

- **New Android Permission:** Added `RUN_USER_INITIATED_JOBS` permission for Android 16+ UIDT support
- **iOS MMKV Dependency:** MMKV is no longer a hard dependency in the podspec. Apps not using `react-native-mmkv` must add `pod 'MMKV', '>= 1.0.0'` to their Podfile.

### 📚 Documentation

- Added documentation about Android 16+ UIDT support in README
- Added iOS MMKV dependency section in README (similar to Android section)
- Added migration guide for iOS MMKV dependency change

---

## v4.2.0

> 📖 **Upgrading from v4.1.x?** See the [Migration Guide](./MIGRATION.md) for details on the new Android pause/resume functionality.

### ✨ New Features

- **Android Pause/Resume Support:** Android now fully supports `task.pause()` and `task.resume()` methods using HTTP Range headers. Downloads can be paused and resumed just like on iOS.
- **Background Download Service:** Added `ResumableDownloadService` - a foreground service that ensures downloads continue even when the app is in the background or the screen is off.
- **WakeLock Support:** Downloads maintain a partial wake lock to prevent the device from sleeping during active downloads.
- **`bytesTotal` Unknown Size Handling:** When the server doesn't provide a `Content-Length` header, `bytesTotal` now returns `-1` instead of `0` to distinguish "unknown size" from "zero bytes".

### 🐛 Bug Fixes

- **Android Pause Error:** Fixed `COULD_NOT_FIND` error when pausing downloads on Android by properly tracking pausing state
- **Temp File Cleanup:** Fixed temp files (`.tmp`) not being deleted when stopping or deleting paused downloads
- **Content-Length Handling:** Fixed progress percentage calculation when server doesn't provide Content-Length header

### 📦 Dependencies & Infrastructure

- **New Android Permissions:** Added `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and `WAKE_LOCK` permissions for background download support
- **New Service:** Added `ResumableDownloadService` with `dataSync` foreground service type

### 📚 Documentation

- Updated README to reflect Android pause/resume support
- Removed "iOS only" notes from pause/resume documentation
- Added documentation about `bytesTotal` returning `-1` for unknown sizes
- Updated Android DownloadManager Limitations section with new pause/resume implementation details

---

## v4.1.0

> 📖 **Upgrading from v4.0.x?** See the [Migration Guide](./MIGRATION.md) for the MMKV dependency change.

### ⚠️ Breaking Changes

- **MMKV Dependency Changed to `compileOnly`:** The MMKV dependency is now `compileOnly` instead of `implementation` to avoid duplicate class errors when the app also uses `react-native-mmkv`. Apps not using `react-native-mmkv` must now explicitly add the MMKV dependency.

### ✨ New Features

- **Expo Plugin Android Support:** The Expo config plugin now automatically adds the MMKV dependency on Android. Use `addMmkvDependency: false` option if you're already using `react-native-mmkv`.

### 🐛 Bug Fixes

- **Duplicate Class Errors:** Fixed potential duplicate class errors when app uses both this library and `react-native-mmkv` by changing MMKV to `compileOnly` dependency

### 📚 Documentation

- Added documentation for MMKV dependency requirements in README
- Updated Platform-Specific Limitations section with MMKV setup instructions
- Added Expo plugin options documentation

---

## v4.0.0

> 📖 **Upgrading from v3.x?** See the [Migration Guide](./MIGRATION.md) for detailed instructions.

### ⚠️ Breaking Changes

- **API Renamed:** `checkForExistingDownloads()` → `getExistingDownloadTasks()` - Now returns a Promise with better naming
- **API Renamed:** `download()` → `createDownloadTask()` - Downloads now require explicit `.start()` call
- **Download Tasks Start Explicitly:** Tasks created with `createDownloadTask()` are now in `PENDING` state and must call `.start()` to begin downloading
- **New Config Option:** Added `progressMinBytes` to `setConfig()` - controls minimum bytes change before progress callback fires (default: 1MB)
- **Source Structure Changed:** Code moved from `lib/` to `src/` directory with proper TypeScript types

### ✨ New Features

- **React Native New Architecture Support:** Full TurboModules support for both iOS and Android
- **Expo Config Plugin:** Added automatic iOS native code integration for Expo projects via `app.plugin.js`
- **Android Kotlin Migration:** All Java code converted to Kotlin
- **`maxRedirects` Option:** Configure maximum redirects for Android downloads (resolves #15)
- **`progressMinBytes` Option:** Hybrid progress reporting - callbacks fire based on time interval OR bytes downloaded
- **Android 15+ Support:** Added support for 16KB memory page sizes
- **Architecture Fallback:** Comprehensive x86/ARMv7 support with SharedPreferences fallback

### 🐛 Bug Fixes

- **iOS Pause/Resume:** Fixed pause and resume functionality on iOS
- **RN 0.78+ Compatibility:** Fixed bridge checks with safe emitter checks
- **New Architecture Events:** Fixed `downloadBegin` and `downloadProgress` events emission
- **Android Background Downloads:** Fixed completed files not moving to destination
- **Progress Callback Unknown Total:** Fixed progress callback not firing when total bytes unknown
- **Android 12 MMKV Crash:** Added robust error handling
- **`checkForExistingDownloads` TypeError:** Fixed TypeError on Android with architecture fallback
- **Firebase Performance Compatibility:** Fixed `completeHandler` method compatibility on Android
- **Slow Connection Handling:** Better handling of slow-responding URLs with timeouts
- **Android OldArch Export:** Fixed module method export issue (#79)
- **MMKV Compatibility:** Support for react-native-mmkv 4+ with mmkv-shared dependency

### 📦 Dependencies & Infrastructure

- **React Native:** Updated example app to RN 0.81.4
- **TypeScript:** Full TypeScript types in `src/types.ts`
- **iOS Native:** Converted from `.m` to `.mm` (Objective-C++)
- **Package Manager:** Switched to yarn as preferred package manager

### 📚 Documentation

- Added documentation for `progressMinBytes` option
- Updated README for React Native 0.77+ instructions
- Improved Expo config plugin examples

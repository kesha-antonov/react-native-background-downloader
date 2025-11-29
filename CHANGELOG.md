# Changelog

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

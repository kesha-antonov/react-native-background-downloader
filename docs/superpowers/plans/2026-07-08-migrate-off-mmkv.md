# Migrate off MMKV + Rescope Package Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the MMKV native dependency from Android, iOS, the Expo config plugin, and the example app (replacing it with each platform's built-in key/value store), delete the stale MMKV-specific test files and docs, and rescope the npm package from `@kesha-antonov/react-native-background-downloader` to `@fivecar/react-native-background-downloader`.

**Architecture:** Android already has a full `SharedPreferences` fallback in `StorageManager.kt` — delete the MMKV branch and keep only that path. iOS gets a new `RNBGDStorage` wrapper class backed by `NSUserDefaults`, exposing the same method shape MMKV had (`getDataForKey:`/`setData:forKey:`, `getFloatForKey:`/`setFloat:forKey:`, `getInt64ForKey:`/`setInt64:forKey:`) so `RNBackgroundDownloader.mm`'s ~20 call sites become a mechanical variable rename. The Expo plugin drops its Android MMKV-dependency-injection logic entirely. The example app swaps `react-native-mmkv` for `expo-sqlite/kv-store`'s synchronous `Storage` API (same sync call shape, no component restructuring). The package rename is a single global find/replace pass across every file referencing the old scoped name, run **last**, after all functional and doc content has settled.

**Tech Stack:** Kotlin (Android), Objective-C++ (iOS `.mm`), TypeScript (Expo config plugin, example app), Jest (JS tests).

## Global Constraints

- Data migration: **clean cutover**. No code migrates old MMKV-backed data to the new storage on either platform. New storage starts empty; in-flight paused/active downloads recorded under old MMKV keys are lost across the upgrade.
- New npm scope: `@fivecar`. New GitHub repo URL: `https://github.com/fivecar/react-native-background-downloader`.
- Do **not** touch `CHANGELOG.md` (historical record under the old name).
- Do **not** touch the README "Maintained by Kesha Antonov" / sponsor-link attribution line (`README.md:1014` and `:1018`), or `.github/FUNDING.yml` — these credit the original author and are explicitly out of scope for the rename.
- This repo has no native (Android/iOS) automated test harness. For native-code tasks, verification is: (a) `grep` to confirm no stray references remain, (b) a manual note that a real Gradle/Xcode build should be run before release — do not claim a native build "passes" without actually running one.
- The four MMKV-specific JS test files being deleted (`__tests__/mmkvErrorHandling.test.js`, `__tests__/mmkv4Compatibility.test.js`, `__tests__/16kbPageSizeSupport.test.js`, `__tests__/architectureCompatibility.test.js`) are shallow smoke tests with no real native interaction — deleting them is not a test-coverage regression.

---

### Task 1: Android — remove MMKV, keep SharedPreferences-only storage

**Files:**
- Modify: `android/src/main/java/com/eko/utils/StorageManager.kt`
- Modify: `android/build.gradle:38-43,76-87`

**Interfaces:**
- Produces: `StorageManager` keeps its exact existing public method signatures (`saveDownloadIdToConfigMap`, `loadDownloadIdToConfigMap`, `saveProgressConfig`, `loadProgressConfig`, `saveBooleanSync`, `getBooleanSync`, `savePausedDownloads`, `loadPausedDownloads`, `removePausedDownload`, `saveActiveDownloads`, `loadActiveDownloads`, `saveActiveDownload`, `removeActiveDownload`, `clearActiveDownloads`, `saveUploadConfigs`, `loadUploadConfigs`, `removeUploadConfig`) — callers elsewhere in the Android module are unaffected.

- [ ] **Step 1: Rewrite `StorageManager.kt` to drop MMKV entirely**

Replace the whole file with:

```kotlin
package com.eko.utils

import android.content.Context
import android.content.SharedPreferences
import com.eko.Downloader
import com.eko.RNBGDTaskConfig
import com.eko.RNBGDUploadTaskConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages persistent storage for download and upload configurations.
 * Backed by SharedPreferences.
 */
class StorageManager(context: Context, private val name: String) {

    companion object {
        private const val KEY_DOWNLOAD_ID_TO_CONFIG = "_downloadIdToConfig"
        private const val KEY_PAUSED_DOWNLOADS = "_pausedDownloads"
        private const val KEY_ACTIVE_DOWNLOADS = "_activeDownloads"
        private const val KEY_UPLOAD_CONFIGS = "_uploadConfigs"
        private const val KEY_PROGRESS_INTERVAL = "_progressInterval"
        private const val KEY_PROGRESS_MIN_BYTES = "_progressMinBytes"
    }

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("${name}_prefs", Context.MODE_PRIVATE)

    /**
     * Save download ID to config mapping.
     */
    fun saveDownloadIdToConfigMap(downloadIdToConfig: Map<Long, RNBGDTaskConfig>) {
        val gson = Gson()
        // Create a defensive copy to prevent ConcurrentModificationException
        val mapCopy = HashMap(downloadIdToConfig)
        val str = gson.toJson(mapCopy)

        sharedPreferences.edit()
            .putString("$name$KEY_DOWNLOAD_ID_TO_CONFIG", str)
            .commit() // Use commit() for synchronous save
    }

    /**
     * Load download ID to config mapping.
     */
    fun loadDownloadIdToConfigMap(): MutableMap<Long, RNBGDTaskConfig> {
        val str = sharedPreferences.getString("$name$KEY_DOWNLOAD_ID_TO_CONFIG", null)

        if (str != null && str.isNotEmpty()) {
            val gson = Gson()
            val mapType = object : TypeToken<Map<Long, RNBGDTaskConfig>>() {}.type
            return gson.fromJson(str, mapType)
        }
        return mutableMapOf()
    }

    /**
     * Save progress configuration.
     */
    fun saveProgressConfig(progressInterval: Long, progressMinBytes: Long) {
        sharedPreferences.edit()
            .putInt("$name$KEY_PROGRESS_INTERVAL", progressInterval.toInt())
            .putLong("$name$KEY_PROGRESS_MIN_BYTES", progressMinBytes)
            .apply()
    }

    /**
     * Load progress configuration.
     * @return Pair of (progressInterval, progressMinBytes)
     */
    fun loadProgressConfig(): Pair<Long, Long> {
        val progressIntervalScope = sharedPreferences.getInt("$name$KEY_PROGRESS_INTERVAL", 0)
        val interval = if (progressIntervalScope > 0) progressIntervalScope.toLong() else 0L

        val progressMinBytesScope = sharedPreferences.getLong("$name$KEY_PROGRESS_MIN_BYTES", 0)
        val minBytes = if (progressMinBytesScope > 0) progressMinBytesScope else 0L

        return Pair(interval, minBytes)
    }

    /**
     * Save a boolean value synchronously.
     */
    fun saveBooleanSync(key: String, value: Boolean) {
        sharedPreferences.edit()
            .putBoolean("$name$key", value)
            .apply()
    }

    /**
     * Get a boolean value synchronously.
     */
    fun getBooleanSync(key: String, defaultValue: Boolean): Boolean {
        return sharedPreferences.getBoolean("$name$key", defaultValue)
    }

    /**
     * Serializable data class for storing paused download information.
     * This is needed because Gson requires explicit serialization for complex types.
     */
    data class PausedDownloadInfoData(
        val configId: String,
        val url: String,
        val destination: String,
        val headers: Map<String, String>,
        val bytesDownloaded: Long,
        val bytesTotal: Long,
        val metadata: String = "{}"
    )

    /**
     * Save paused downloads map.
     */
    fun savePausedDownloads(pausedDownloads: Map<String, Downloader.PausedDownloadInfo>) {
        val gson = Gson()
        val mapCopy = pausedDownloads.mapValues { (_, info) ->
            PausedDownloadInfoData(
                configId = info.configId,
                url = info.url,
                destination = info.destination,
                headers = info.headers,
                bytesDownloaded = info.bytesDownloaded,
                bytesTotal = info.bytesTotal,
                metadata = info.metadata
            )
        }
        val str = gson.toJson(mapCopy)

        sharedPreferences.edit()
            .putString("$name$KEY_PAUSED_DOWNLOADS", str)
            .commit() // Use commit() instead of apply() for synchronous save
    }

    /**
     * Load paused downloads map.
     */
    fun loadPausedDownloads(): MutableMap<String, Downloader.PausedDownloadInfo> {
        val str = sharedPreferences.getString("$name$KEY_PAUSED_DOWNLOADS", null)

        if (str != null && str.isNotEmpty()) {
            val gson = Gson()
            val mapType = object : TypeToken<Map<String, PausedDownloadInfoData>>() {}.type
            val dataMap: Map<String, PausedDownloadInfoData> = gson.fromJson(str, mapType)
            return dataMap.mapValues { (_, data) ->
                Downloader.PausedDownloadInfo(
                    configId = data.configId,
                    url = data.url,
                    destination = data.destination,
                    headers = data.headers,
                    bytesDownloaded = data.bytesDownloaded,
                    bytesTotal = data.bytesTotal,
                    metadata = data.metadata
                )
            }.toMutableMap()
        }
        return mutableMapOf()
    }

    /**
     * Remove a paused download by config ID.
     */
    fun removePausedDownload(configId: String) {
        val paused = loadPausedDownloads()
        if (paused.remove(configId) != null) {
            savePausedDownloads(paused)
        }
    }

    /**
     * Save the set of in-progress resumable downloads ("recovery snapshots").
     * These let getExistingDownloadTasks recover an active download after the app
     * is force-stopped (which kills the foreground service and loses in-memory
     * state). Stored separately from paused downloads so it never interferes with
     * the live pause/resume bookkeeping.
     */
    fun saveActiveDownloads(activeDownloads: Map<String, Downloader.PausedDownloadInfo>) {
        val gson = Gson()
        val mapCopy = activeDownloads.mapValues { (_, info) ->
            PausedDownloadInfoData(
                configId = info.configId,
                url = info.url,
                destination = info.destination,
                headers = info.headers,
                bytesDownloaded = info.bytesDownloaded,
                bytesTotal = info.bytesTotal,
                metadata = info.metadata
            )
        }
        val str = gson.toJson(mapCopy)

        sharedPreferences.edit()
            .putString("$name$KEY_ACTIVE_DOWNLOADS", str)
            .commit()
    }

    /**
     * Load the in-progress resumable download recovery snapshots.
     */
    fun loadActiveDownloads(): MutableMap<String, Downloader.PausedDownloadInfo> {
        val str = sharedPreferences.getString("$name$KEY_ACTIVE_DOWNLOADS", null)

        if (str != null && str.isNotEmpty()) {
            val gson = Gson()
            val mapType = object : TypeToken<Map<String, PausedDownloadInfoData>>() {}.type
            val dataMap: Map<String, PausedDownloadInfoData> = gson.fromJson(str, mapType)
            return dataMap.mapValues { (_, data) ->
                Downloader.PausedDownloadInfo(
                    configId = data.configId,
                    url = data.url,
                    destination = data.destination,
                    headers = data.headers,
                    bytesDownloaded = data.bytesDownloaded,
                    bytesTotal = data.bytesTotal,
                    metadata = data.metadata
                )
            }.toMutableMap()
        }
        return mutableMapOf()
    }

    /**
     * Upsert a single in-progress resumable download recovery snapshot.
     */
    fun saveActiveDownload(info: Downloader.PausedDownloadInfo) {
        val map = loadActiveDownloads()
        map[info.configId] = info
        saveActiveDownloads(map)
    }

    /**
     * Remove a single recovery snapshot by config ID.
     */
    fun removeActiveDownload(configId: String) {
        val map = loadActiveDownloads()
        if (map.remove(configId) != null) {
            saveActiveDownloads(map)
        }
    }

    /**
     * Clear all recovery snapshots.
     */
    fun clearActiveDownloads() {
        saveActiveDownloads(emptyMap())
    }

    /**
     * Save upload configs map.
     */
    fun saveUploadConfigs(uploadConfigs: Map<String, RNBGDUploadTaskConfig>) {
        val gson = Gson()
        // Create a defensive copy to prevent ConcurrentModificationException
        val mapCopy = HashMap(uploadConfigs)
        val str = gson.toJson(mapCopy)

        sharedPreferences.edit()
            .putString("$name$KEY_UPLOAD_CONFIGS", str)
            .apply()
    }

    /**
     * Load upload configs map.
     */
    fun loadUploadConfigs(): MutableMap<String, RNBGDUploadTaskConfig> {
        val str = sharedPreferences.getString("$name$KEY_UPLOAD_CONFIGS", null)

        if (str != null) {
            val gson = Gson()
            val mapType = object : TypeToken<Map<String, RNBGDUploadTaskConfig>>() {}.type
            return gson.fromJson(str, mapType)
        }
        return mutableMapOf()
    }

    /**
     * Remove an upload config by ID.
     */
    fun removeUploadConfig(configId: String) {
        val configs = loadUploadConfigs()
        if (configs.remove(configId) != null) {
            saveUploadConfigs(configs)
        }
    }
}
```

Note: all `try`/`catch` wrapping and error logging around storage calls is removed along with the MMKV branch — the original catch blocks only existed to swallow MMKV-specific native init/link errors (`UnsatisfiedLinkError`, `NoClassDefFoundError`) that can't occur with `SharedPreferences`. `RNBackgroundDownloaderModuleImpl.logD/logE/logW` and the `TAG` constant are no longer referenced by this file, so they're dropped too (they aren't used elsewhere in this file).

- [ ] **Step 2: Remove the MMKV Gradle dependency**

In `android/build.gradle`, delete these lines (currently 80-84):

```gradle
    // MMKV dependency for persistent download state storage
    // Uses compileOnly to avoid duplicate class errors when app also uses react-native-mmkv
    // The app must provide MMKV dependency (either directly or via react-native-mmkv)
    // MMKV 1.3.14+ supports both armeabi-v7a (32-bit) and 16KB page sizes (Android 15+)
    compileOnly 'com.tencent:mmkv-shared:1.3.16'

```

leaving:

```gradle
dependencies {
    //noinspection GradleDynamicVersion
    implementation 'com.facebook.react:react-native:+'

    implementation 'com.google.code.gson:gson:2.12.1'
}
```

Also update the comment above `abiFilters` (currently lines 38-40):

```gradle
        // Support for 16KB memory page sizes (Android 15+)
        // Note: MMKV 2.x dropped armeabi-v7a support. If your app targets armeabi-v7a,
        // downgrade to MMKV 1.x in your app's build.gradle (see README for details).
```

to just:

```gradle
        // Support for 16KB memory page sizes (Android 15+)
```

- [ ] **Step 3: Verify no MMKV references remain in the Android module**

Run: `grep -rni mmkv android/`
Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add android/src/main/java/com/eko/utils/StorageManager.kt android/build.gradle
git commit -m "feat(android): remove MMKV, use SharedPreferences-only storage

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: iOS — replace MMKV with an NSUserDefaults-backed `RNBGDStorage`

**Files:**
- Create: `ios/RNBGDStorage.h`
- Create: `ios/RNBGDStorage.mm`
- Modify: `ios/RNBackgroundDownloader.mm`
- Modify: `react-native-background-downloader.podspec:22-30`

**Interfaces:**
- Produces: `RNBGDStorage` with `+ (instancetype)storageWithID:(NSString *)storageID`, `- (nullable NSData *)getDataForKey:(NSString *)key`, `- (void)setData:(NSData *)data forKey:(NSString *)key`, `- (float)getFloatForKey:(NSString *)key` (returns `NAN` if the key was never set, matching MMKV's prior behavior so the existing `isnan(...)` default-handling in `RNBackgroundDownloader.mm:243` keeps working unchanged), `- (void)setFloat:(float)value forKey:(NSString *)key`, `- (int64_t)getInt64ForKey:(NSString *)key`, `- (void)setInt64:(int64_t)value forKey:(NSString *)key`.

- [ ] **Step 1: Create `ios/RNBGDStorage.h`**

```objc
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

// Persistent key/value storage backed by NSUserDefaults. Exposes the same
// method shape the library previously got from MMKV so callers needed only
// a variable rename when MMKV was removed.
@interface RNBGDStorage : NSObject

+ (instancetype)storageWithID:(NSString *)storageID;

- (nullable NSData *)getDataForKey:(NSString *)key;
- (void)setData:(NSData *)data forKey:(NSString *)key;

// Returns NAN if the key has never been set (matches MMKV's prior behavior).
- (float)getFloatForKey:(NSString *)key;
- (void)setFloat:(float)value forKey:(NSString *)key;

- (int64_t)getInt64ForKey:(NSString *)key;
- (void)setInt64:(int64_t)value forKey:(NSString *)key;

@end

NS_ASSUME_NONNULL_END
```

- [ ] **Step 2: Create `ios/RNBGDStorage.mm`**

```objc
#import "RNBGDStorage.h"
#import <math.h>

@implementation RNBGDStorage {
    NSUserDefaults *_defaults;
}

+ (instancetype)storageWithID:(NSString *)storageID {
    return [[self alloc] initWithID:storageID];
}

- (instancetype)initWithID:(NSString *)storageID {
    self = [super init];
    if (self) {
        _defaults = [[NSUserDefaults alloc] initWithSuiteName:storageID];
        if (_defaults == nil) {
            // Suite creation failed (should not happen in practice) - fall back
            // to the standard defaults domain rather than crash.
            _defaults = [NSUserDefaults standardUserDefaults];
        }
    }
    return self;
}

- (nullable NSData *)getDataForKey:(NSString *)key {
    return [_defaults dataForKey:key];
}

- (void)setData:(NSData *)data forKey:(NSString *)key {
    [_defaults setObject:data forKey:key];
}

- (float)getFloatForKey:(NSString *)key {
    if ([_defaults objectForKey:key] == nil) {
        return NAN;
    }
    return [_defaults floatForKey:key];
}

- (void)setFloat:(float)value forKey:(NSString *)key {
    [_defaults setFloat:value forKey:key];
}

- (int64_t)getInt64ForKey:(NSString *)key {
    return (int64_t)[_defaults integerForKey:key];
}

- (void)setInt64:(int64_t)value forKey:(NSString *)key {
    [_defaults setInteger:(NSInteger)value forKey:key];
}

@end
```

- [ ] **Step 3: Swap the MMKV import and ivar in `RNBackgroundDownloader.mm`**

At line 4, replace:

```objc
#import <MMKV/MMKV.h>
```

with:

```objc
#import "RNBGDStorage.h"
```

At line 46, replace:

```objc
    MMKV *mmkv;
```

with:

```objc
    RNBGDStorage *storage;
```

- [ ] **Step 4: Swap the MMKV init call**

At lines 214-215, replace:

```objc
        [MMKV initializeMMKV:nil];
        mmkv = [MMKV mmkvWithID:@"RNBackgroundDownloader"];
```

with:

```objc
        storage = [RNBGDStorage storageWithID:@"RNBackgroundDownloader"];
```

- [ ] **Step 5: Rename every remaining `mmkv` call-site reference to `storage`**

Run (from repo root):

```bash
sed -i '' 's/\bmmkv\b/storage/g' ios/RNBackgroundDownloader.mm
```

This renames the identifier at every remaining call site (`getDataForKey:`, `setData:forKey:`, `getFloatForKey:`, `setFloat:forKey:`, `getInt64ForKey:`, `setInt64:forKey:`, and the `self->mmkv` reference), since none of them reference the `MMKV` class name directly (all class-level references were already replaced in Steps 3-4).

- [ ] **Step 6: Update the stale comment referencing MMKV**

Find (originally around line 696, now shifted since Steps 3-4 removed 2 lines):

```objc
                                // Save resume data to file instead of storing in MMKV
```

Replace with:

```objc
                                // Save resume data to file instead of storing in persistent storage
```

- [ ] **Step 7: Verify no MMKV references remain in the iOS module**

Run: `grep -rni mmkv ios/`
Expected: no output.

- [ ] **Step 8: Remove the MMKV podspec dependency**

In `react-native-background-downloader.podspec`, delete lines 22-30:

```ruby
  # MMKV is used for persistent download state storage on iOS
  # Using MMKV (Objective-C wrapper) which depends on MMKVCore.
  # We only use MMKV's basic key/value APIs (initializeMMKV:, mmkvWithID:,
  # getDataForKey:/setData:forKey:, typed getters/setters) which have been stable
  # since MMKV 1.x, so we require only that minimum. CocoaPods is still free to
  # resolve a newer MMKV when another pod (e.g. react-native-mmkv, which pins an
  # exact MMKVCore) requires one - we just don't impose our own higher floor.
  # See https://github.com/kesha-antonov/react-native-background-downloader/issues/162
  s.dependency 'MMKV', '>= 1.2.0'

```

so the file reads, after `install_modules_dependencies(s)`:

```ruby
  s.source_files = 'ios/**/*.{h,m,mm,swift}'
  # React Native Core dependency
  install_modules_dependencies(s)

  # Enable codegen for new architecture
  if ENV['RCT_NEW_ARCH_ENABLED'] == '1'
```

- [ ] **Step 9: Commit**

```bash
git add ios/RNBGDStorage.h ios/RNBGDStorage.mm ios/RNBackgroundDownloader.mm react-native-background-downloader.podspec
git commit -m "feat(ios): replace MMKV with NSUserDefaults-backed RNBGDStorage

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Expo config plugin — remove MMKV dependency injection

**Files:**
- Modify: `plugin/src/index.ts`

**Interfaces:**
- Consumes: none from other tasks.
- Produces: `withRNBackgroundDownloader: ConfigPlugin<void>` (no longer accepts `PluginOptions` since there are no options left) — this is the plugin's default export, used by `app.plugin.js` (unchanged, not touched by this task).

- [ ] **Step 1: Remove `PluginOptions`, MMKV auto-detection, and the Android Gradle mod**

Replace the top of the file (everything from the `PluginOptions` interface through the end of `withRNBackgroundDownloader`, currently lines 6-61):

```typescript
const withRNBackgroundDownloader: ConfigPlugin = (config) => {
  // Handle iOS AppDelegate modifications
  config = withAppDelegate(config, (config) => {
    if (config.modResults.language === 'objc') {
      // For Objective-C AppDelegate.m (React Native < 0.77)
      config.modResults.contents = addObjCSupport(config.modResults.contents)
    } else {
      // For Swift AppDelegate.swift (React Native >= 0.77)
      config.modResults.contents = addSwiftSupport(config.modResults.contents)

      // For Swift projects, we need to ensure the bridging header includes our import
      const projectRoot = config.modRequest.projectRoot
      const iosProjectRoot = IOSConfig.Paths.getSourceRoot(projectRoot)
      addToBridgingHeader(iosProjectRoot, config.modRequest.projectName || 'App')
    }
    return config
  })

  return config
}
```

- [ ] **Step 2: Remove the now-unused `checkForReactNativeMmkv` and `addMmkvDependencyAndroid` functions**

Delete these two functions entirely (originally lines 63-116):

```typescript
/**
 * Check if react-native-mmkv is present in the project dependencies.
 * react-native-mmkv uses io.github.zhongwuzw:mmkv which conflicts with com.tencent:mmkv-shared.
 */
function checkForReactNativeMmkv (config: ExpoConfig): boolean {
  ...
}

function addMmkvDependencyAndroid (buildGradleContents: string, mmkvVersion: string): string {
  ...
}
```

- [ ] **Step 3: Remove now-unused imports**

`withAppBuildGradle` and `ExpoConfig` are no longer referenced. Change line 1-2 from:

```typescript
import { ConfigPlugin, withAppDelegate, withAppBuildGradle, IOSConfig } from '@expo/config-plugins'
import type { ExpoConfig } from '@expo/config-types'
```

to:

```typescript
import { ConfigPlugin, withAppDelegate, IOSConfig } from '@expo/config-plugins'
```

- [ ] **Step 4: Verify no MMKV references remain in the plugin, and that it still compiles**

Run: `grep -ni mmkv plugin/src/index.ts`
Expected: no output.

Run: `npm run build-plugin`
Expected: exits 0, no TypeScript errors.

- [ ] **Step 5: Commit**

```bash
git add plugin/src/index.ts
git commit -m "feat(plugin): remove MMKV Android dependency injection

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Delete stale MMKV-specific test files

**Files:**
- Delete: `__tests__/mmkvErrorHandling.test.js`
- Delete: `__tests__/mmkv4Compatibility.test.js`
- Delete: `__tests__/16kbPageSizeSupport.test.js`
- Delete: `__tests__/architectureCompatibility.test.js`

**Interfaces:** none — these files have no consumers.

- [ ] **Step 1: Delete the four files**

```bash
git rm __tests__/mmkvErrorHandling.test.js __tests__/mmkv4Compatibility.test.js __tests__/16kbPageSizeSupport.test.js __tests__/architectureCompatibility.test.js
```

- [ ] **Step 2: Run the remaining test suite**

Run: `npm test`
Expected: all remaining tests pass (these four files only ever asserted generic JS-API smoke behavior unrelated to their filenames — no other test depended on them).

- [ ] **Step 3: Commit**

```bash
git commit -m "test: remove stale MMKV-specific smoke tests

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Example app — replace `react-native-mmkv` with `expo-sqlite/kv-store`

**Files:**
- Modify: `example/src/screens/BasicExample/index.tsx:17,22,31,40,60,435,438,441,447,469,546`
- Modify: `example/package.json` (dependencies)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `TaskIdStorage` keeps its existing shape (`load`, `save`, `getOrCreate`, `clear`, `clearAll`) — no other file in the example imports it, so no downstream signature concerns.

- [ ] **Step 1: Swap the `react-native-mmkv` import and storage instance**

In `example/src/screens/BasicExample/index.tsx`, replace line 17:

```typescript
import { createMMKV } from 'react-native-mmkv'
```

with:

```typescript
import { Storage } from 'expo-sqlite/kv-store'
```

Replace line 22:

```typescript
const storage = createMMKV({ id: 'download-example-storage' })
```

with:

```typescript
const storage = Storage
```

- [ ] **Step 2: Update `TaskIdStorage` to use the string-based `Storage` API**

`expo-sqlite/kv-store`'s `Storage` is string-based (`getItemSync`/`setItemSync`/`removeItemSync`), unlike MMKV's typed `getString`/`set`/`remove`. Replace lines 28-62:

```typescript
const TaskIdStorage = {
  load: (): Record<string, string> => {
    try {
      const json = storage.getItemSync(TASK_IDS_KEY)
      return json ? JSON.parse(json) : {}
    } catch (e) {
      console.warn('Failed to load persisted task IDs:', e)
      return {}
    }
  },

  save: (mapping: Record<string, string>) => {
    storage.setItemSync(TASK_IDS_KEY, JSON.stringify(mapping))
  },

  getOrCreate: (url: string): string => {
    const mapping = TaskIdStorage.load()
    if (mapping[url]) return mapping[url]

    const newId = uuid()
    mapping[url] = newId
    TaskIdStorage.save(mapping)
    return newId
  },

  clear: (url: string) => {
    const mapping = TaskIdStorage.load()
    delete mapping[url]
    TaskIdStorage.save(mapping)
  },

  clearAll: () => {
    storage.removeItemSync(TASK_IDS_KEY)
  },
}
```

(Only `getItemSync`/`setItemSync`/`removeItemSync` replace MMKV's `getString`/`set`/`remove` — the rest of the object is unchanged.)

- [ ] **Step 3: Update the boolean setting reads/writes to the string-based API**

Replace lines 434-442:

```typescript
  const [notificationGroupingEnabled, setNotificationGroupingEnabled] = useState(() => {
    return storage.getItemSync(NOTIFICATION_GROUPING_KEY) === 'true'
  })
  const [showNotificationsEnabled, setShowNotificationsEnabled] = useState(() => {
    return storage.getItemSync(SHOW_NOTIFICATIONS_KEY) === 'true'
  })
  const [summaryOnlyMode, setSummaryOnlyMode] = useState(() => {
    return storage.getItemSync(SUMMARY_ONLY_MODE_KEY) === 'true'
  })
```

Replace line 447 (`storage.set(NOTIFICATION_GROUPING_KEY, enabled)`) with:

```typescript
    storage.setItemSync(NOTIFICATION_GROUPING_KEY, String(enabled))
```

Replace line 469 (`storage.set(SUMMARY_ONLY_MODE_KEY, enabled)`) with:

```typescript
    storage.setItemSync(SUMMARY_ONLY_MODE_KEY, String(enabled))
```

Replace line 546 (`storage.set(SHOW_NOTIFICATIONS_KEY, show)`) with:

```typescript
    storage.setItemSync(SHOW_NOTIFICATIONS_KEY, String(show))
```

- [ ] **Step 4: Update the comment referencing MMKV**

Replace line 885 (`// Initialize URL list with persisted IDs after mount (when MMKV is ready)`) with:

```typescript
    // Initialize URL list with persisted IDs after mount
```

- [ ] **Step 5: Update example dependencies**

In `example/package.json`, remove (lines 33-34):

```json
    "react-native-mmkv": "^4.1.1",
    "react-native-nitro-modules": "^0.33.1",
```

(`react-native-nitro-modules` is only there as `react-native-mmkv`'s peer dependency — nothing else in the example uses it.)

- [ ] **Step 6: Add `expo-sqlite` via `expo install` and reinstall**

Run (from `example/`): `npx expo install expo-sqlite`
Expected: exits 0; adds an `expo-sqlite` entry to `dependencies` in `example/package.json` at the version matched to this project's Expo SDK (`~54.0.25`) — let the Expo CLI pick the exact version rather than hand-pinning one.

Run: `yarn install`
Expected: exits 0; `react-native-mmkv`/`react-native-nitro-modules` are removed from `node_modules` and `yarn.lock`.

Run: `grep -rni mmkv example/src example/package.json`
Expected: no output.

- [ ] **Step 7: Commit**

```bash
git add example/src/screens/BasicExample/index.tsx example/package.json example/yarn.lock
git commit -m "feat(example): replace react-native-mmkv with expo-sqlite/kv-store

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 6: Docs — remove MMKV-specific content from README and Platform Notes

**Files:**
- Modify: `README.md`
- Modify: `docs/PLATFORM_NOTES.md`

**Interfaces:** none — documentation only.

- [ ] **Step 1: Remove the MMKV architecture mention (`README.md:43`)**

Replace:

```markdown
- **Android:** A combination of [`DownloadManager`](https://developer.android.com/reference/android/app/DownloadManager) for system-managed downloads, [Foreground Services](https://developer.android.com/develop/background-work/services/foreground-services) for pause/resume support, and [MMKV](https://github.com/Tencent/MMKV) for persistent state storage
```

with:

```markdown
- **Android:** A combination of [`DownloadManager`](https://developer.android.com/reference/android/app/DownloadManager) for system-managed downloads, [Foreground Services](https://developer.android.com/develop/background-work/services/foreground-services) for pause/resume support, and `SharedPreferences` for persistent state storage
```

- [ ] **Step 2: Remove the "MMKV version comparison" Table of Contents entry (`README.md:58`)**

Delete the line:

```markdown
    - [MMKV version comparison](#mmkv-version-comparison)
```

- [ ] **Step 3: Remove the plugin options doc block (`README.md:110-132`)**

Delete the whole `<details><summary><strong>Plugin Options (optional)</strong></summary>...</details>` block:

```markdown
<details>
<summary><strong>Plugin Options (optional)</strong></summary>

```js
// app.config.js
export default {
  expo: {
    plugins: [
      ["@kesha-antonov/react-native-background-downloader", {
        mmkvVersion: "1.3.16",  // Customize MMKV version on Android
        skipMmkvDependency: true  // Skip if you want to add MMKV manually
      }]
    ]
  }
}
```

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `mmkvVersion` | string | `'1.3.16'` | The version of [MMKV](https://github.com/Tencent/MMKV/releases) to use on Android. See [MMKV version comparison](#mmkv-version-comparison) for details. |
| `skipMmkvDependency` | boolean | `false` | Skip adding MMKV dependency. Set to `true` if you're using [react-native-mmkv](https://github.com/mrousavy/react-native-mmkv) to avoid duplicate class errors. The plugin auto-detects `react-native-mmkv` but you can use this option to explicitly skip. See [MMKV version comparison](#mmkv-version-comparison). |

</details>
```

The plugin no longer accepts any options (Task 3), so there is nothing to document here.

- [ ] **Step 4: Update "The plugin automatically handles" bullet (`README.md:141-143`)**

Replace:

```markdown
The plugin automatically handles:
- **iOS:** Adding the required `handleEventsForBackgroundURLSession` method to AppDelegate
- **Android:** Adding the required MMKV dependency
```

with:

```markdown
The plugin automatically handles:
- **iOS:** Adding the required `handleEventsForBackgroundURLSession` method to AppDelegate
```

- [ ] **Step 5: Remove Step 4 ("Configure Android MMKV dependency") and the MMKV version comparison section (`README.md:214-242`)**

Delete everything from `**Step 4:** Configure Android MMKV dependency` through the `**TL;DR:**` line, i.e.:

```markdown
**Step 4:** Configure Android MMKV dependency

Add MMKV to your `android/app/build.gradle`:

```gradle
dependencies {
    implementation 'com.tencent:mmkv-shared:1.3.16'
}
```

> **Note:** If you're already using [react-native-mmkv](https://github.com/mrousavy/react-native-mmkv) in your project, skip this step — it already includes MMKV. Note that `react-native-mmkv` v4.x uses [Margelo's fork of MMKV](https://github.com/margelo/MMKV) (`io.github.zhongwuzw:mmkv`) which re-adds armeabi-v7a (32-bit ARM) support that was dropped in the official MMKV 2.x release.

> **⚠️ armeabi-v7a (32-bit ARM) users:** MMKV 2.x dropped 32-bit ABI support (since v2.0.0). If you need armeabi-v7a support and get a CMake error like `No compatible library found for //mmkv/mmkv`, use the MMKV 1.3.x LTS series instead — it supports both armeabi-v7a **and** 16KB page sizes (since v1.3.14):
> ```gradle
> dependencies {
>     implementation 'com.tencent:mmkv-shared:1.3.16'
> }
> ```

#### MMKV version comparison

| Dependency | armeabi-v7a (32-bit) | arm64-v8a | 16KB page size | Recommended for |
|---|:---:|:---:|:---:|---|
| `com.tencent:mmkv-shared:1.3.16` (**default**) | ✅ | ✅ | ✅ (since 1.3.14) | Most apps — broadest device coverage |
| `com.tencent:mmkv-shared:2.x` | ❌ | ✅ | ✅ | 64-bit only apps (no legacy devices) |
| `io.github.zhongwuzw:mmkv:2.3.0` ([Margelo fork](https://github.com/margelo/MMKV)) | ✅ | ✅ | ✅ | Used automatically by `react-native-mmkv` v4.x — skip manual dependency |
| `react-native-mmkv` (already in project) | ✅ | ✅ | ✅ | If you already use `react-native-mmkv` — skip Step 4 entirely |

**TL;DR:** Use the default `1.3.16`. If you already have `react-native-mmkv` in your project, skip Step 4.
```

so that `**Step 3:** Configure iOS AppDelegate`'s closing `</details>` is followed directly by `## 🚀 Usage`.

- [ ] **Step 6: Update the Platform Notes summary bullet (`README.md:875`)**

Replace:

```markdown
- **Android**: Uses `DownloadManager` + Foreground Services + MMKV
```

with:

```markdown
- **Android**: Uses `DownloadManager` + Foreground Services + SharedPreferences
```

- [ ] **Step 7: Remove the two MMKV troubleshooting entries (`README.md:889-901`)**

Delete:

```markdown
<details>
<summary><strong>Duplicate class errors with react-native-mmkv (Android)</strong></summary>

If you're using `react-native-mmkv`, you don't need to add the MMKV dependency manually - it's already included. The library uses `compileOnly` to avoid conflicts.

`react-native-mmkv` v4.x uses [Margelo's fork of MMKV](https://github.com/margelo/MMKV) (`io.github.zhongwuzw:mmkv`) which re-adds armeabi-v7a (32-bit ARM) support, so you have full ABI coverage including 32-bit devices when using `react-native-mmkv`.
</details>

<details>
<summary><strong>EXC_BAD_ACCESS crash on iOS with react-native-mmkv</strong></summary>

This was fixed in v4.4.0. Update to the latest version. If you're not using `react-native-mmkv`, add `pod 'MMKV', '>= 1.0.0'` to your Podfile.
</details>

```

so the "Download stuck in pending state" `</details>` is followed directly by the "Downloads don't work when the iOS screen is locked" block.

- [ ] **Step 8: Update `docs/PLATFORM_NOTES.md` — remove the "MMKV Dependency" section (lines 63-71)**

Delete:

```markdown
### MMKV Dependency

Android uses MMKV for persistent state storage. This is required for:
- Tracking download progress across app restarts
- Storing download metadata
- Managing pause/resume state

If you're using [react-native-mmkv](https://github.com/mrousavy/react-native-mmkv) in your project, you don't need to add MMKV separately.

```

so "### Foreground Service" is followed directly by "### Handling Redirects".

- [ ] **Step 9: Remove the MMKV Proguard rule (`docs/PLATFORM_NOTES.md:117`)**

Replace:

```proguard
-keep class com.eko.RNBGDTaskConfig { *; }
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.tencent.mmkv.** { *; }
```

with:

```proguard
-keep class com.eko.RNBGDTaskConfig { *; }
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
```

- [ ] **Step 10: Remove the two MMKV troubleshooting entries in Platform Notes (lines 131-137)**

Delete:

```markdown
### Duplicate class errors with react-native-mmkv (Android)

If you're using `react-native-mmkv`, you don't need to add the MMKV dependency manually - it's already included. The library uses `compileOnly` to avoid conflicts.

### EXC_BAD_ACCESS crash on iOS with react-native-mmkv

This was fixed in v4.4.0. Update to the latest version. If you're not using `react-native-mmkv`, add `pod 'MMKV', '>= 1.0.0'` to your Podfile.

```

so "### Download stuck in..." is followed directly by "### Downloads not resuming after app restart".

- [ ] **Step 11: Verify no stray MMKV mentions remain**

Run: `grep -ni mmkv README.md docs/PLATFORM_NOTES.md`
Expected: no output.

- [ ] **Step 12: Commit**

```bash
git add README.md docs/PLATFORM_NOTES.md
git commit -m "docs: remove MMKV-specific content from README and Platform Notes

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: MIGRATION.md — document the MMKV removal

**Files:**
- Modify: `MIGRATION.md`

**Interfaces:** none — documentation only.

- [ ] **Step 1: Add a new top-of-file migration section**

Insert immediately after the Table of Contents intro (after `MIGRATION.md:4`, before the existing `## Table of Contents` entries list, i.e. add both a TOC entry and the section body):

Add this TOC entry as the new first bullet under `## Table of Contents` (before `- [Migration Guide: v4.1.x → v4.2.0]...`):

```markdown
- [Migration Guide: v4.5.x → v5.0.0](#migration-guide-v45x--v500)
  - [MMKV Removed](#mmkv-removed)
    - [Before (v4.5.x)](#before-v45x)
    - [After (v5.0.0)](#after-v500)
    - [Why This Change?](#why-this-change-4)
```

Add this new section as the first section, before `# Migration Guide: v4.1.x → v4.2.0` (find the section boundary by searching for that heading):

```markdown
# Migration Guide: v4.5.x → v5.0.0

## MMKV Removed

Starting in v5.0.0, the library no longer depends on MMKV on either platform.

### Before (v4.5.x)

- **Android:** required a `com.tencent:mmkv-shared` (or `react-native-mmkv`-provided) Gradle dependency; fell back to `SharedPreferences` only if MMKV failed to initialize.
- **iOS:** required the `MMKV` CocoaPod; no fallback existed.
- The Expo config plugin injected/skipped the Android MMKV Gradle dependency automatically.

### After (v5.0.0)

- **Android:** uses `SharedPreferences` exclusively. No MMKV Gradle dependency, no `mmkvVersion`/`skipMmkvDependency` plugin options.
- **iOS:** uses `NSUserDefaults` exclusively. No `MMKV` CocoaPod dependency.
- **No action required** for the storage change itself — just update to v5.0.0 and rebuild (`pod install` on iOS; no Gradle changes needed on Android).
- **Important:** this is a clean cutover, not a migration. Any downloads that were paused, active, or queued under the old MMKV-backed storage will **not** carry over — after upgrading, call `getExistingDownloadTasks()` / `getExistingUploadTasks()` as usual, but be aware it will return an empty list for any transfers that were only recorded in the old storage. Re-initiate those downloads/uploads as needed.
- If you were passing `mmkvVersion` or `skipMmkvDependency` to the Expo config plugin, remove them — they are no longer recognized options.
- If you had manually added `implementation 'com.tencent:mmkv-shared:...'` (Android) or `pod 'MMKV', '>= 1.0.0'` (iOS) solely for this library, you may remove it (unless another dependency, like `react-native-mmkv`, still needs it).

### Why This Change?

MMKV caused real integration friction: duplicate-class errors with `react-native-mmkv`, the 32-bit ARM (`armeabi-v7a`) support drop in MMKV 2.x, and past `EXC_BAD_ACCESS` crashes from symbol conflicts on iOS. Both `SharedPreferences` and `NSUserDefaults` are built into their platforms, need no version coordination with other dependencies, and remove an entire class of setup problems for consumers.

```

- [ ] **Step 2: Verify the new section renders as valid Markdown headings**

Run: `grep -n "^# \|^## " MIGRATION.md | head -5`
Expected: first two lines are `# Migration Guide` and `## Table of Contents`, followed shortly by `# Migration Guide: v4.5.x → v5.0.0` and `## MMKV Removed`.

- [ ] **Step 3: Commit**

```bash
git add MIGRATION.md
git commit -m "docs: add v5.0.0 migration guide entry for MMKV removal

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 8: Rescope the package to `@fivecar/react-native-background-downloader`

**Files:**
- Modify: `package.json`
- Modify: `plugin/package.json`
- Modify: `react-native-background-downloader.podspec:16`
- Modify: `src/index.ts:55`
- Modify: `example/scripts/postinstall.js:27`
- Modify: `example/package.json:23`
- Modify: `example/app.json:41`
- Modify: `example/tsconfig.json:10`
- Modify: `example/metro.config.js:20`
- Modify: `example/configuration-example.js:8`
- Modify: `example/src/screens/UploadExample/index.tsx:10-11`
- Modify: `README.md`
- Modify: `MIGRATION.md`
- Modify: `docs/API.md`
- Modify: `docs/PLATFORM_NOTES.md`

**Interfaces:** none — this is a mechanical rename of the published package identifier; no function signatures change.

**Note:** run this task **after** Tasks 1-7 so the rename sees final file contents (no point renaming text about to be deleted).

- [ ] **Step 1: Update `package.json`**

Change:

```json
  "name": "@kesha-antonov/react-native-background-downloader",
```

to:

```json
  "name": "@fivecar/react-native-background-downloader",
```

Change:

```json
  "repository": {
    "type": "git",
    "url": "git+https://github.com/kesha-antonov/react-native-background-downloader.git"
  },
  "homepage": "https://github.com/kesha-antonov/react-native-background-downloader",
```

to:

```json
  "repository": {
    "type": "git",
    "url": "git+https://github.com/fivecar/react-native-background-downloader.git"
  },
  "homepage": "https://github.com/fivecar/react-native-background-downloader",
  "bugs": {
    "url": "https://github.com/fivecar/react-native-background-downloader/issues"
  },
```

Leave `author` (`{ "name": "Philip Su", "email": "39933441+fivecar@users.noreply.github.com" }`) and `contributors` (crediting Kesha Antonov / Elad Gil) unchanged.

- [ ] **Step 2: Update `plugin/package.json`**

Change:

```json
  "name": "@kesha-antonov/react-native-background-downloader-expo-plugin",
```

to:

```json
  "name": "@fivecar/react-native-background-downloader-expo-plugin",
```

- [ ] **Step 3: Update the podspec source URL**

In `react-native-background-downloader.podspec`, change:

```ruby
  s.source       = { git: 'https://github.com/kesha-antonov/react-native-background-downloader.git', tag: 'main' }
```

to:

```ruby
  s.source       = { git: 'https://github.com/fivecar/react-native-background-downloader.git', tag: 'main' }
```

(`s.name`, `s.homepage`, and `s.author` are all derived from `package.json` at build time via `package['name']`/`package['repository']['url']`/`package['author']`, so Step 1 already propagates those — no further podspec edit needed.)

- [ ] **Step 4: Update the linking-error message in `src/index.ts`**

At line 55, change:

```typescript
      'The package \'@kesha-antonov/react-native-background-downloader\' doesn\'t seem to be linked. Make sure: \n\n' +
```

to:

```typescript
      'The package \'@fivecar/react-native-background-downloader\' doesn\'t seem to be linked. Make sure: \n\n' +
```

- [ ] **Step 5: Update the example's local-link postinstall script**

At `example/scripts/postinstall.js:27`, change:

```javascript
const PKG = '@kesha-antonov/react-native-background-downloader'
```

to:

```javascript
const PKG = '@fivecar/react-native-background-downloader'
```

- [ ] **Step 6: Update example config/dependency references**

In `example/package.json:23`, change:

```json
    "@kesha-antonov/react-native-background-downloader": "link:..",
```

to:

```json
    "@fivecar/react-native-background-downloader": "link:..",
```

In `example/app.json:41`, change:

```json
      "@kesha-antonov/react-native-background-downloader"
```

to:

```json
      "@fivecar/react-native-background-downloader"
```

In `example/tsconfig.json:10`, change:

```json
      "@kesha-antonov/react-native-background-downloader": ["../src/index"]
```

to:

```json
      "@fivecar/react-native-background-downloader": ["../src/index"]
```

In `example/metro.config.js:20`, change:

```javascript
  '@kesha-antonov/react-native-background-downloader': libraryRoot,
```

to:

```javascript
  '@fivecar/react-native-background-downloader': libraryRoot,
```

In `example/configuration-example.js:8`, change:

```javascript
import { setConfig, createDownloadTask, directories } from '@kesha-antonov/react-native-background-downloader'
```

to:

```javascript
import { setConfig, createDownloadTask, directories } from '@fivecar/react-native-background-downloader'
```

In `example/src/screens/UploadExample/index.tsx:10-11`, change:

```typescript
} from '@kesha-antonov/react-native-background-downloader'
import type { UploadTask } from '@kesha-antonov/react-native-background-downloader'
```

to:

```typescript
} from '@fivecar/react-native-background-downloader'
import type { UploadTask } from '@fivecar/react-native-background-downloader'
```

- [ ] **Step 7: Bulk-replace every remaining occurrence in README/docs/MIGRATION**

Every remaining occurrence of the literal string `@kesha-antonov/react-native-background-downloader` in `README.md`, `MIGRATION.md`, `docs/API.md`, and `docs/PLATFORM_NOTES.md` (import statements, `npm install`/`yarn add`/`npx expo install` commands, Expo plugin config entries, prose) should become `@fivecar/react-native-background-downloader`. Also update the two `kesha-antonov`-only URLs that aren't part of the package-name string:

- `README.md`: the npm-stat badge URL (`%40kesha-antonov%2Freact-native-background-downloader` → `%40fivecar%2Freact-native-background-downloader`) and the LICENSE blob link (`github.com/kesha-antonov/react-native-background-downloader/blob/main/LICENSE` → `github.com/fivecar/react-native-background-downloader/blob/main/LICENSE`).
- `MIGRATION.md`: the "existing issues" link (`github.com/kesha-antonov/react-native-background-downloader/issues` → `github.com/fivecar/react-native-background-downloader/issues`).

Do **not** touch `README.md`'s "Maintained by Kesha Antonov" / sponsor-link lines (around line 1014/1018) — those are out of scope per the Global Constraints.

Run this from the repo root:

```bash
sed -i '' \
  -e 's#@kesha-antonov/react-native-background-downloader#@fivecar/react-native-background-downloader#g' \
  -e 's#%40kesha-antonov%2Freact-native-background-downloader#%40fivecar%2Freact-native-background-downloader#g' \
  -e 's#github\.com/kesha-antonov/react-native-background-downloader#github.com/fivecar/react-native-background-downloader#g' \
  README.md MIGRATION.md docs/API.md docs/PLATFORM_NOTES.md
```

Then confirm the attribution lines were untouched:

Run: `grep -n "kesha-antonov" README.md`
Expected: only the two attribution lines remain (`Maintained by [Kesha Antonov](https://github.com/kesha-antonov)` and the `sponsors/kesha-antonov` link).

- [ ] **Step 8: Reinstall to regenerate `package-lock`/`yarn.lock` under the new name**

Run: `npm install`
Expected: exits 0; root lockfile reflects the renamed package.

Run: `cd example && yarn install`
Expected: exits 0; `example/yarn.lock` and the `node_modules/@fivecar/...` symlink (created by `postinstall.js`, now using the updated `PKG` constant) reflect the renamed package. `node_modules/@kesha-antonov` should no longer exist.

- [ ] **Step 9: Verify no remaining `kesha-antonov` references outside the intentionally-kept attribution lines**

Run: `grep -rl kesha-antonov . --exclude-dir=node_modules --exclude-dir=.git --exclude=CHANGELOG.md --exclude-dir=docs/superpowers`
Expected output: only `README.md` and `.github/FUNDING.yml` (both intentionally kept per Global Constraints).

- [ ] **Step 10: Run the full check suite**

Run: `npm run typecheck && npm test && npm run lint`
Expected: all pass.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "chore: rescope package to @fivecar/react-native-background-downloader

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage:** Android (Task 1), iOS (Task 2), Expo plugin (Task 3), stale tests (Task 4), example app (Task 5), README/Platform Notes MMKV content (Task 6), MIGRATION.md entry (Task 7), and the package rename (Task 8) all map directly to spec sections. The spec's "Not touched" list (`CHANGELOG.md`, README attribution line, `.github/FUNDING.yml`) is carried into Global Constraints and re-checked in Task 8 Step 9.
- **Placeholder scan:** no TBD/TODO; the MIGRATION.md version number (`v5.0.0`) is a concrete decision (this is a breaking, non-backward-compatible change — package rename plus dropped data continuity — so a major bump is the correct semver call), not a placeholder.
- **Type/interface consistency:** `RNBGDStorage`'s method names (`getDataForKey:`/`setData:forKey:`/`getFloatForKey:`/`setFloat:forKey:`/`getInt64ForKey:`/`setInt64:forKey:`) match exactly what Task 2 Step 5's `sed` rename expects to find already in `RNBackgroundDownloader.mm`. `StorageManager`'s public method list in Task 1 matches the original file's public surface exactly (verified against the pre-existing file contents).

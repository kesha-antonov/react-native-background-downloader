# Migrate off MMKV

## Motivation

The library uses Tencent MMKV as a native dependency on Android and iOS to persist
download/upload state (queued configs, paused/active download snapshots, progress
settings). This causes real integration problems for consumers:

- Duplicate-class errors when the host app also uses `react-native-mmkv`
- MMKV 2.x dropped 32-bit ARM (`armeabi-v7a`) support
- The Expo config plugin has to conditionally inject/skip a native Android
  dependency depending on what else is in the app
- iOS previously had `EXC_BAD_ACCESS` crashes from symbol conflicts with
  `react-native-mmkv`'s own MMKV copy

Android already has a full `SharedPreferences` fallback implemented in
`StorageManager.kt` for when MMKV fails to initialize (e.g. missing native lib on
unsupported ABIs). iOS has no fallback today — MMKV is the only persistence
mechanism.

## Goal

Remove the MMKV native dependency entirely from both platforms and the Expo
config plugin, replacing it with each platform's built-in key/value store.

## Design

### Android (`android/src/main/java/com/eko/utils/StorageManager.kt`)

Delete the MMKV branch entirely. Keep only the existing `SharedPreferences` code
path — it already implements every method (`saveDownloadIdToConfigMap`,
`loadDownloadIdToConfigMap`, `saveProgressConfig`, `loadProgressConfig`,
`saveBooleanSync`, `getBooleanSync`, `savePausedDownloads`, `loadPausedDownloads`,
`removePausedDownload`, `saveActiveDownloads`/`loadActiveDownloads`/
`saveActiveDownload`/`removeActiveDownload`/`clearActiveDownloads`,
`saveUploadConfigs`/`loadUploadConfigs`/`removeUploadConfig`). Remove the
`isMMKVAvailable`/`mmkv` fields, the MMKV init `try`/`catch` block, and the
`com.tencent.mmkv.MMKV` import.

Remove the `compileOnly 'com.tencent:mmkv-shared:1.3.16'` dependency (and its
surrounding comments) from `android/build.gradle`.

### iOS (`ios/RNBackgroundDownloader.mm`)

MMKV is used at ~20 call sites via `getDataForKey:`/`setData:forKey:`,
`getFloatForKey:`/`setFloat:forKey:`, `getInt64ForKey:`/`setInt64:forKey:`. Add a
small internal wrapper class (e.g. `RNBGDStorage`) backed by `NSUserDefaults`
that exposes the same method shape, so call sites become one-line swaps:

```objc
@interface RNBGDStorage : NSObject
+ (instancetype)storageWithID:(NSString *)storageID;
- (NSData *)getDataForKey:(NSString *)key;
- (void)setData:(NSData *)data forKey:(NSString *)key;
- (float)getFloatForKey:(NSString *)key;
- (void)setFloat:(float)value forKey:(NSString *)key;
- (int64_t)getInt64ForKey:(NSString *)key;
- (void)setInt64:(int64_t)value forKey:(NSString *)key;
@end
```

Backed by a dedicated `NSUserDefaults` suite (via `-initWithSuiteName:`) keyed
by the same storage ID MMKV used (`RNBackgroundDownloader`), to avoid colliding
with unrelated `NSUserDefaults` keys the host app may set.

Replace `[MMKV initializeMMKV:nil]; mmkv = [MMKV mmkvWithID:@"RNBackgroundDownloader"];`
with `storage = [RNBGDStorage storageWithID:@"RNBackgroundDownloader"];`, rename
the `mmkv` ivar/variable to `storage` throughout, and update all call sites.

Remove the `s.dependency 'MMKV', '>= 1.2.0'` line (and its comment block) from
the podspec.

### Expo config plugin (`plugin/src/index.ts`)

Remove the `mmkvVersion`/`skipMmkvDependency` options, `checkForReactNativeMmkv`,
and `addMmkvDependencyAndroid` — the plugin no longer needs to touch
`android/app/build.gradle` for this at all. If the plugin's Android mod becomes a
no-op as a result, remove that mod registration too.

### Example app (`example/src/screens/BasicExample/index.tsx`)

Replace `react-native-mmkv`'s `createMMKV` with `expo-sqlite/kv-store`'s
synchronous `Storage` API (`getItemSync`/`setItemSync`/`removeItemSync`), since
it matches MMKV's synchronous call shape used today in
`useState(() => storage.getBoolean(...))` initializers and the module-level
`TaskIdStorage` helper — no restructuring of component logic needed. Values are
stored as JSON-stringified strings/booleans since the kv-store API is
string-based (unlike MMKV which has typed getters).

Add `expo-sqlite` to `example/package.json`, remove `react-native-mmkv`.

### Tests

Delete `__tests__/mmkvErrorHandling.test.js`, `__tests__/mmkv4Compatibility.test.js`,
`__tests__/16kbPageSizeSupport.test.js`, and `__tests__/architectureCompatibility.test.js`.
All four are shallow JS-level smoke tests (call `createDownloadTask`/`setConfig`
and assert no throw) with no actual native MMKV interaction — the scenarios they
claim to cover (MMKV init failure, MMKV 4+ compatibility, 16KB page size support,
ABI compatibility) no longer apply once MMKV is removed.

### Docs

- `README.md`: remove the MMKV architecture bullet, the `mmkvVersion`/
  `skipMmkvDependency` plugin option docs, the "MMKV version comparison" table
  and section, Step 4 ("Configure Android MMKV dependency"), and the
  MMKV-related troubleshooting entries (duplicate class errors,
  `EXC_BAD_ACCESS` crash).
- `MIGRATION.md`: add a new entry documenting that MMKV has been removed as a
  dependency, no native storage setup is required anymore on either platform,
  and — per the clean-cutover decision below — in-flight paused/active
  downloads recorded under old MMKV-backed keys will not carry over across the
  update (users would need to re-initiate any in-progress downloads after
  upgrading).

## Data migration

Clean cutover: no migration code from old MMKV-backed storage to the new
storage. New storage starts empty on both platforms.

## Out of scope

None — this design covers the library (Android + iOS + Expo plugin), its tests,
its docs, and the example app.

## Package rename (bundled into this branch)

Alongside the MMKV removal, rescope the npm package from
`@kesha-antonov/react-native-background-downloader` to
`@fivecar/react-native-background-downloader`, since this fork is now
maintained independently.

- **`package.json`**: `name` → `@fivecar/react-native-background-downloader`;
  `repository.url` → `git+https://github.com/fivecar/react-native-background-downloader.git`;
  `homepage` → `https://github.com/fivecar/react-native-background-downloader`;
  add `bugs.url` → `https://github.com/fivecar/react-native-background-downloader/issues`.
  `author` is already `{ "name": "Philip Su", "email": "39933441+fivecar@users.noreply.github.com" }`
  — leave as is. Keep the existing `contributors` entry crediting
  Kesha Antonov / Elad Gil for the original work.
- **`plugin/package.json`**: `name` → `@fivecar/react-native-background-downloader-expo-plugin`.
- **`react-native-background-downloader.podspec`**: `s.source` git URL →
  `https://github.com/fivecar/react-native-background-downloader.git`; update
  the issue-tracker URL comment similarly.
- **All package-name references** across `README.md`, `MIGRATION.md`,
  `docs/API.md`, `docs/PLATFORM_NOTES.md`, `example/app.json`,
  `example/package.json`, `example/tsconfig.json`: replace every
  `@kesha-antonov/react-native-background-downloader` occurrence (import
  statements, `npm install`/`yarn add`/`npx expo install` commands, Expo plugin
  config array entries, prose) with `@fivecar/react-native-background-downloader`.
  Includes the README npm badges (`badge.fury.io`, `npmjs.com`, `npm-stat.com`
  URLs), which will read zero/no-data until this scope is published — expected
  for a freshly re-scoped package.
- **Not touched**: `CHANGELOG.md` (historical record of past releases under the
  old name — do not rewrite history), and the README "Maintained by Kesha
  Antonov" / sponsor-link attribution line (original-author credit, orthogonal
  to the package-name/repo-metadata rename).

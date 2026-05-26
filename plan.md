# Fork Fix Plan

> Working directory: `/Users/cocktail/WebstormProjects/react-native-background-downloader`
> App consuming it: `/Users/cocktail/WebstormProjects/app/fsd/features/download/`

---

## Bugs Analyzed

### #164 — Android 11: `getExistingDownloadTasks()` returns `[]` with internal destination path
**Root cause (3 parts):**
1. When `destination` is an internal app path (e.g. `RNFS.DocumentDirectoryPath`), Android's `DownloadManager.setDestinationUri()` throws `SecurityException`.
2. Library catches it → falls back to `ResumableDownloader` (downloads work, progress events work).
3. `getExistingDownloadTasks()` Phase 4 was a no-op — active `ResumableDownloader` tasks were never included in results.

### #159 — Android: `getExistingDownloadTasks()` returns `[]` after force-stop if download is active
**Root cause:** After force-stop, Android `DownloadManager` may reassign new download IDs. The persisted `downloadId → config` map in MMKV becomes stale. Phase 1 found the download row in `DownloadManager` but couldn't match it to any config → incorrectly cancelled it.

### #161 — iOS: `setConfig({ allowsCellularAccess })` crashes app (SIGSEGV)
**Root cause:** `_setAllowsCellularAccessInternal:` is a void method that calls `[urlSession invalidateAndCancel]` which can throw `NSException`. The TurboModule bridge catches unhandled exceptions from void methods on background queues and calls `convertNSExceptionToJSError`, which accesses Hermes VM from `com.eko.backgrounddownloader` queue — Hermes is not thread-safe → SIGSEGV. Same pattern affects any void method that throws on this queue.

---

## Fixes Applied

### ✅ Fix 1 — Android Phase 4: active ResumableDownloader tasks in `getExistingDownloadTasks()`
**Files:** `android/src/main/java/com/eko/ResumableDownloader.kt`, `RNBackgroundDownloaderModuleImpl.kt`

- Added `getActiveDownloads(): Map<String, DownloadState>` to `ResumableDownloader`
- Phase 4 now iterates active downloads (non-cancelled, non-paused), builds task info using `configIdToMetadata[configId]` for metadata

**Fixes:** #164 (app still running, download active), partially #159 (Android 16+ path where ResumableDownloader is always used)

**Limitation:** Does NOT fix force-stop with ResumableDownloader — state is in-memory only and lost after process kill. Paused downloads survive force-stop (state is written to disk on pause).

### ✅ Fix 2 — Android Phase 1: destination path matching fallback
**File:** `android/src/main/java/com/eko/RNBackgroundDownloaderModuleImpl.kt`

- In Phase 1, when `downloadId` not found in `downloadIdToConfig` map, try to match by normalized local URI vs `config.destination`
- On match: restore both `downloadIdToConfig[downloadId]` and `configIdToDownloadId[configId]`, persist via `saveDownloadIdToConfigMap()`
- On no match: cancel orphan download (unchanged behavior)

**Fixes:** #159 (active DownloadManager download, force-stop, new download ID assigned)

### ✅ Fix 3 — iOS: wrap void config methods in `@try/@catch`
**File:** `ios/RNBackgroundDownloader.mm`

- Wrapped `_setAllowsCellularAccessInternal:` and `_setMaxParallelDownloadsInternal:` in `@try/@catch`
- Exceptions caught and logged via `DLog` — not propagated to TurboModule bridge → no Hermes thread-safety violation

**Fixes:** #161 (SIGSEGV on `setConfig({ allowsCellularAccess })` with New Architecture)

---

## Remaining / Not Fixed

### 🔶 #159 — ResumableDownloader force-stop (active → lost)
After force-stop, active `ResumableDownloader` downloads (Android 16+, or internal-path fallback) lose all in-memory state. `getExistingDownloadTasks()` cannot recover them.

**Options to fix:**
1. **Persist active ResumableDownloader state to MMKV on progress tick** (e.g. every N bytes). On restart, load persisted state and surface as `SUSPENDED` in `getExistingDownloadTasks()`. User would need to call `resumeTask()`.
2. **Restore temp-file-then-move pattern for DownloadManager** (matches 4.3.2 behavior). Always download to `getExternalFilesDir()` temp file, move to final destination on completion. Avoids ResumableDownloader fallback for internal-path cases on Android < 16.

Option 2 is safer (matches old working behavior), Option 1 is more complete.

### 🔶 #161 — Other void methods potentially affected
`download:` (the main download start method) and `setNotificationGroupingConfig:` also run on the background queue and could theoretically crash if they throw. Lower risk but worth wrapping.

---

## Docs Updated

- `docs/PLATFORM_NOTES.md` — added Troubleshooting entries for #164, #159, #161 with root cause + fix + workaround for older versions

---

## App Integration Notes (`app/fsd/features/download/`)

The app uses `KeshaTaskFactory` (`model/infrastructure/kesha-task-factory.ts`) and calls `getExistingTasks()` via `restoreActiveTasks()` in `DownloadManager`. With Fix 1 applied, active ResumableDownloader downloads (Android 11 fallback, Android 16+) will now appear in restore.

**Potential behavior change:** `restoreActiveTasks()` will now see previously invisible active tasks. Ensure session restore handles `state = TASK_RUNNING` tasks correctly (not just `TASK_SUSPENDED`). Review `TitleDownloadSession.restore()` to confirm it doesn't assume restored tasks are always paused.

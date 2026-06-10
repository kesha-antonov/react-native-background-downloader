# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Use `yarn` (not npm) — project is configured with `yarn.lock` and `packageManager` field.

```sh
yarn install                    # install main library deps (~40s, never cancel)
cd example && yarn install      # install example app deps (~20s, never cancel)

yarn test                       # run Jest tests
yarn lint                       # run ESLint
yarn typecheck                  # tsc type-check src/*.ts
yarn run prepublishOnly         # build-plugin + typecheck + test + lint (full validation before commit)
yarn run build-plugin           # compile plugin/src/index.ts → plugin/build/

cd example && yarn start        # start Metro bundler (validates compilation, ~15s to start)
```

Run a single test file:
```sh
yarn test __tests__/mainTest.js
```

Run `yarn run prepublishOnly` before every commit — it's the canonical validation gate.

## Architecture

This is a React Native native module library — no compilation step for `src/`; Metro bundler handles TypeScript directly. The Expo config plugin in `plugin/` is the exception and must be compiled with `build-plugin`.

### JS Layer (`src/`)

**`index.ts`** — module entry point. Owns:
- Lazy native module initialization (`ensureNativeModuleInitialized`) — never touches the native bridge at import time
- Dual-architecture event wiring: iOS New Arch uses TurboModule `EventEmitter` callbacks directly; Old Arch + Android uses `NativeEventEmitter`
- Three in-memory registries: `tasksMap` (`DownloadTask`), `uploadTasksMap` (`UploadTask`), `groupsMap` (`GroupTask`)
- Public API: `createDownloadTask`, `getExistingDownloadTasks`, `createUploadTask`, `getExistingUploadTasks`, `groupingApi`, `setConfig`, `completeHandler`, `cleanup`

**`DownloadTask.ts`** — task lifecycle class. Calls native methods via `getNativeModule()` (lazy `require('./index')` to avoid circular dependency). Holds `_groupObserver` hook that `GroupTask` injects to observe events without overriding user-set handlers. `maxAge` timer auto-stops the task after a deadline.

**`GroupTask.ts`** — aggregates multiple `DownloadTask` instances under one `groupId`. Recalculates combined `bytesDownloaded/bytesTotal` and fires `done` when all tasks reach DONE or FAILED. Mirrors Android's native notification grouping on the JS side (works on iOS too).

**`NativeRNBackgroundDownloader.ts`** — TurboModule `Spec` interface for codegen. Defines all native method signatures. Upload methods are optional (`?`) for backward compatibility.

**`config.ts`** — mutable singleton holding runtime config (headers, progressInterval, progressMinBytes, etc.) shared across the module.

**`types.ts`** — all exported TypeScript types. Source of truth for the public API surface.

### Native Layer

**iOS (`ios/`)** — Objective-C, uses `NSURLSession` for background downloads. `RNBackgroundDownloader.mm` is the main implementation. Paused tasks enter `TaskCanceling` state with `errorCode -999` (resume-data pattern).

**Android (`android/src/`)** — Java, uses Android `DownloadManager`. Has `newarch/` and `oldarch/` subdirectories for New Architecture (TurboModules) vs Old Architecture builds. `build.gradle` selects the right source set.

### Expo Plugin (`plugin/`)

`plugin/src/index.ts` — compiled to `plugin/build/` (CommonJS, ES2018 target). Registered via `app.plugin.js` at repo root. Must run `yarn run build-plugin` after any changes before the compiled output is valid.

### Tests (`__tests__/`)

Jest with `react-native` preset. Mocks in `__mocks__/`: `RNBackgroundDownloader.js` mocks the native module, `NativeEventEmitter.js` mocks the event emitter. Tests import from `../src/index`.

## Key Invariants

- Native module is initialized lazily — never call `ensureNativeModuleInitialized()` at module load time.
- `DownloadTask` uses a lazy `require('./index')` for `getNativeModule` to avoid circular imports.
- `GroupTask._groupObserver` is an internal hook; user-facing handlers (`beginHandler`, `progressHandler`, etc.) are separate and must not be overwritten by group logic.
- `metadata` is always stored as a JSON string in the native layer and parsed back to an object in JS.
- `destination`/`source` paths have `file://` prefix stripped before passing to native.
- iOS `TaskCanceling` + `errorCode === -999` means paused (not cancelled) — handle this case in `getExistingDownloadTasks`.

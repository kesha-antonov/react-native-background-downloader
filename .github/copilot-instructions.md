# React Native Background Downloader

React Native Background Downloader is a library that enables downloading large files on iOS and Android both in the foreground and background. It provides native iOS (NSURLSession) and Android (DownloadManager) implementations with a unified JavaScript API.

Always reference these instructions first and fallback to search or bash commands only when you encounter unexpected information that does not match the info here.

## Working Effectively

### Bootstrap and Install Dependencies
- Install main library dependencies:
  - `yarn install` -- takes 40 seconds. NEVER CANCEL. Set timeout to 90+ seconds.
  - NOTE: Use yarn as the preferred package manager. The project is configured with yarn.lock and packageManager field.
- Install example app dependencies:
  - `cd example && yarn install` -- takes 20 seconds. NEVER CANCEL. Set timeout to 60+ seconds.

### Build and Test Commands
- Run library tests: `yarn test` -- takes 2 seconds. Set timeout to 30+ seconds.
- Run library linting: `yarn lint` -- takes 2 seconds. Set timeout to 30+ seconds. 
- Run full validation: `yarn run prepublishOnly` -- runs jest && yarn run lint, takes 4 seconds total. Set timeout to 60+ seconds.
- Run example app linting: `cd example && yarn lint` -- takes 2 seconds. Set timeout to 30+ seconds.

### Running the Example App
- Start Metro bundler: `cd example && npx react-native start` -- takes 15 seconds to start. Set timeout to 60+ seconds.
- ALWAYS run the Metro bundler to validate any changes to library functionality.
- The example app demonstrates real background downloading with test files (20MB, 50MB, 100MB).
- You cannot run the full iOS/Android simulators in this environment, but the Metro bundler validates the code compiles correctly.

## Validation

### Mandatory Validation Steps
- ALWAYS run `yarn run prepublishOnly` before committing changes - this runs the full test suite and linting.
- ALWAYS test example app Metro bundler startup after making library changes: `cd example && npx react-native start`.
- ALWAYS test the download functionality scenarios described below if modifying core download logic.

### Testing Download Functionality
After making changes to download logic, validate using the example app scenarios:
1. Start Metro bundler: `cd example && npx react-native start`
2. The example app (`/example/src/screens/BasicExample/index.tsx`) provides complete test scenarios:
   - Start multiple downloads (20MB, 50MB, 100MB test files)
   - Pause/resume downloads (iOS only)
   - Stop downloads
   - Resume existing downloads after app restart
   - Clear downloaded files
   - List downloaded files

### Code Quality Validation
- TypeScript compilation: The main library uses TypeScript but doesn't have a separate compilation step - it's handled by Metro bundler.
- The example app has TypeScript config issues but Metro bundler works correctly for development validation.

## Key Projects and Navigation

### Main Library (`/src`)
- **Entry point**: `/src/index.ts` - Main API exports
- **Core logic**: `/src/DownloadTask.ts` - Download task implementation
- **Native interface**: `/src/NativeRNBackgroundDownloader.ts` - Native module interface
- **Type definitions**: `/src/index.d.ts` - TypeScript type definitions

### Tests (`/__tests__`)
- **Main test file**: `__tests__/mainTest.js` - Jest tests for core functionality
- Tests cover: download, pause, resume, stop, error handling, progress tracking, existing downloads

### Example App (`/example`)
- **Main app**: `/example/src/App.tsx` - React Native app entry point
- **Download demo**: `/example/src/screens/BasicExample/index.tsx` - Complete download scenarios
- **Package.json**: `/example/package.json` - React Native 0.73.6, example app dependencies

### Native Implementations
- **Android**: `/android` - Java/Kotlin native implementation using DownloadManager
- **iOS**: `/ios` - Objective-C/Swift native implementation using NSURLSession
- **Config**: `/react-native.config.js` - React Native autolinking configuration

## Common Tasks

### Repository Root Structure
```
.
..
.eslintrc.js          # ESLint configuration
.github/              # GitHub templates and this instruction file
.gitignore
README.md             # Main documentation with API details
__mocks__/            # Jest mocks for React Native modules
__tests__/            # Jest test files
android/              # Android native implementation
babel.config.js       # Babel configuration for Metro bundler
example/              # Full React Native example app
ios/                  # iOS native implementation  
metro.config.js       # Metro bundler configuration
package.json          # Main library package.json
react-native.config.js # React Native autolinking config
src/                  # Main TypeScript library source
yarn.lock             # Lock file (yarn is preferred)
```

### Package.json Scripts
Main library (`package.json`):
- `yarn test` - Run Jest tests
- `yarn lint` - Run ESLint
- `yarn run prepublishOnly` - Run tests && lint (full validation)

Example app (`example/package.json`):
- `yarn run android` - Run on Android (requires Android SDK)
- `yarn run ios` - Run on iOS simulator (requires Xcode)
- `yarn start` - Start Metro bundler
- `yarn lint` - ESLint for example app

### Important Files to Check After Changes
- ALWAYS check `/src/index.ts` after modifying the main API
- ALWAYS check `/src/DownloadTask.ts` after modifying download behavior
- ALWAYS check `__tests__/mainTest.js` after API changes to ensure tests are updated
- ALWAYS check `/example/src/screens/BasicExample/index.tsx` to understand usage patterns

### Known Issues and Workarounds
- **Dependency installation**: Use yarn as the preferred package manager (configured with packageManager field)
- **Package managers**: Both npm and yarn work, but yarn is preferred and configured in package.json
- **TypeScript**: No separate tsc compilation step for main library - Metro bundler handles it
- **Test imports**: Fixed import paths from '../index' to '../src/index' and '../lib/downloadTask' to '../src/DownloadTask'

### React Native Environment Requirements
- Node.js (version in package.json engines field)
- React Native CLI: `yarn global add react-native-cli`
- For iOS development: Xcode and CocoaPods (`cd ios && pod install`)
- For Android development: Android Studio and SDK
- Metro bundler (included with React Native)

## Timeout Settings for Commands

CRITICAL: Set appropriate timeouts for all commands to prevent premature cancellation:

- `yarn install`: 90+ seconds (takes ~40 seconds)
- `cd example && yarn install`: 60+ seconds (takes ~20 seconds)
- `yarn run prepublishOnly`: 60+ seconds (takes ~4 seconds)
- `cd example && npx react-native start`: 60+ seconds (takes ~15 seconds to start)
- All other yarn commands: 30+ seconds

NEVER CANCEL any yarn install or build commands. Package installation can take significant time depending on network conditions.
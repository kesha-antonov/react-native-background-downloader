# Expo Config Plugin for React Native Background Downloader

This Expo config plugin automatically configures your iOS project to support background downloads when using `@kesha-antonov/react-native-background-downloader` in an Expo managed workflow.

## What it does

The plugin automatically adds the required iOS code to handle background URL sessions, which prevents downloads from being canceled when your app goes to the background.

### For React Native < 0.77 (Objective-C projects):
- Adds `#import <RNBackgroundDownloader.h>` to your `AppDelegate.m`
- Adds the `handleEventsForBackgroundURLSession` method to your `AppDelegate.m`

### For React Native 0.77+ (Swift projects):
- Adds the `handleEventsForBackgroundURLSession` method to your `AppDelegate.swift`
- **Note**: You still need to manually add `#import <RNBackgroundDownloader.h>` to your bridging header file

## Installation

1. Install the package:
```bash
npm install @kesha-antonov/react-native-background-downloader
```

2. Add the plugin to your `app.config.js` or `expo.json`:

```javascript
// app.config.js
export default {
  expo: {
    name: "My App",
    plugins: [
      "@kesha-antonov/react-native-background-downloader"
    ]
  }
};
```

Or in `expo.json`:
```json
{
  "expo": {
    "name": "My App",
    "plugins": [
      "@kesha-antonov/react-native-background-downloader"
    ]
  }
}
```

## Manual Steps for Swift Projects (React Native 0.77+)

For Swift projects, you still need to manually add the import to your bridging header:

1. Open your project's bridging header file (usually `ios/{YourProjectName}/{YourProjectName}-Bridging-Header.h`)
2. Add this import:
```objc
#import <RNBackgroundDownloader.h>
```

## Usage

After adding the plugin and running `expo prebuild` (or building your project), you can use the background downloader library as documented:

```javascript
import RNBackgroundDownloader from '@kesha-antonov/react-native-background-downloader';

const task = RNBackgroundDownloader.download({
  id: 'file1',
  url: 'https://example.com/file.zip',
  destination: `${RNBackgroundDownloader.directories.documents}/file.zip`
});

task.begin(() => {
  console.log('Download started');
}).done(() => {
  console.log('Download completed');
}).error((error) => {
  console.error('Download failed:', error);
});
```

## Plugin Options

Currently, no configuration options are supported. The plugin automatically detects whether your project uses Objective-C or Swift and applies the appropriate modifications.

## Troubleshooting

### Build errors about missing RNBackgroundDownloader.h
Make sure you've run `pod install` in your iOS directory after adding the plugin and running `expo prebuild`.

### Downloads still get canceled in background
Verify that the plugin has correctly added the `handleEventsForBackgroundURLSession` method to your AppDelegate. For Swift projects, also ensure you've added the import to your bridging header.

### Plugin not working
1. Make sure you've added the plugin to your `app.config.js` or `expo.json`
2. Run `expo prebuild --clean` to regenerate your native code
3. Run `pod install` in your iOS directory

## Manual Alternative

If you can't use the plugin or prefer manual setup, follow the instructions in the main library documentation for manual AppDelegate modifications.
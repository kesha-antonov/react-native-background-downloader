Android 11: getExistingDownloadTasks() returns [] when DownloadManager falls back because of internal destination path
#164

Description


On Android 11, getExistingDownloadTasks() returns an empty array while downloads are still running and progress events are being emitted normally.

This only happens on one Android 11 physical test device. The same code works on newer Android emulator/system versions. Downgrading to 4.3.2 also fixes the issue.

After comparing 4.3.2 with 4.5.4, the issue appears related to the Android download destination path change introduced after 4.3.x.

In 4.3.2, Android DownloadManager downloads to an app external temp file first:

val tempFile = File(externalFilesDir, filename)
request.setDestinationUri(Uri.fromFile(tempFile))
Then the completed file is moved to the JS-provided destination.

In 4.5.4, the code downloads directly to the JS-provided destination:

val destFile = File(destination)
request.setDestinationUri(Uri.fromFile(destFile))
In my app, destination points to React Native's document/internal files directory, e.g. a path built from RNFS.DocumentDirectoryPath.

On this Android 11 device, the direct destination path seems to make DownloadManager fail or become unsuitable, so the library falls back to ResumableDownloader. Downloads still work, and progress events are emitted, but getExistingDownloadTasks() returns [].

This looks similar in shape to #147: active downloads exist, but getExistingDownloadTasks() does not see them when the actual download path is not standard DownloadManager.

Environment
Package: @kesha-antonov/react-native-background-downloader
Broken version: 4.5.4
Working version: 4.3.2
React Native: 0.83.1
Android device: Android 11 physical device
Newer Android emulator/system: works correctly
Destination path: internal app document path via RNFS.DocumentDirectoryPath
Reproduction
Use @kesha-antonov/react-native-background-downloader@4.5.4.
Start a download on Android 11 with a destination under the app internal documents directory.
Observe that download progress events are emitted normally.
Call getExistingDownloadTasks() while the download is still active.
It returns [].
Expected:

getExistingDownloadTasks() should return the active download task.

Actual:

It returns an empty array, even though the download is active and progress events continue.

Debugging Notes
The problem seems to be caused by a combination of:

Directly passing the app internal destination to DownloadManager.Request.setDestinationUri().
Falling back to ResumableDownloader on some Android 11 devices.
getExistingDownloadTasks() not returning active ResumableDownloader tasks.
Android's DownloadManager.Request.setDestinationUri() expects a file URI on external storage or another path accepted by DownloadManager. App internal files paths are not reliable for this API across Android versions/devices.

Local Patch That Fixes It
I patched RNBackgroundDownloaderModuleImpl.kt to restore the older behavior:

For normal Android DownloadManager downloads, download to reactContext.getExternalFilesDir(null) first.
On completion, move the file to the requested config.destination.
Keep the existing fallback behavior only if DownloadManager enqueue fails.
Patch summary:

// Instead of downloading directly to destination:
val destFile = File(destination)
request.setDestinationUri(Uri.fromFile(destFile))

// Download to external temp file:
if (!externalFilesDir!!.exists()) {
externalFilesDir.mkdirs()
}
val tempFile = File(externalFilesDir, filename)
request.setDestinationUri(Uri.fromFile(tempFile))
Then on successful completion:

private fun moveDownloadedFile(localUri: String, destination: String) {
val sourceFile = File(localUri)
val destinationFile = File(destination)

if (destinationFile.exists()) {
return
}

FileUtils.mkdirParent(destinationFile)
if (!sourceFile.exists() || !FileUtils.mv(sourceFile, destinationFile)) {
throw Exception("Downloaded file could not be moved to destination")
}
}
And onSuccessfulDownload() calls moveDownloadedFile(localUri, config.destination) before emitting completion.

After this patch, Android 11 works again with 4.5.4: downloads work, progress events work, and getExistingDownloadTasks() returns the active task.

Possible Fixes
I think either of these would fix the issue:

Restore the old temp-file-then-move behavior for DownloadManager downloads, at least when destination is not under getExternalFilesDir() or another DownloadManager-safe path.

Fully support active ResumableDownloader tasks in getExistingDownloadTasks(), similar to the UIDT fix from getExistingDownloadTasks() does not return pending tasks when fallback DownloadManager is used #147.

Option 1 is probably the safer compatibility fix because it matches the working 4.3.x behavior and avoids device-specific DownloadManager path restrictions.

Thanks for maintaining this library.


[iOS] allowsCellularAccess causes app crash #161
Is this a bug report, a feature request, or a question?
Bug report

Have you followed the required steps before opening a bug report?

I have reviewed the documentation in its entirety, including the dedicated documentations 📚.

I have searched for existing issues and made sure that the problem hasn't already been reported.

I am using the latest plugin version.
Is the bug specific to iOS or Android? Or can it be reproduced on both platforms?
iOS only. Reproduced on:

iPhone 13 Pro Max (iOS 26.3.1)
iOS Simulator, iphone 17 (iOS 26.3.1)
Is the bug related to the native implementation? (NSURLSession on iOS and Fetch on Android)
Yes. The crash occurs in the native setAllowsCellularAccess: method (RNBackgroundDownloader.mm). The method is dispatched on com.eko.backgrounddownloader (a custom serial queue), and when it throws an NSException, React Native's TurboModule bridge attempts to convert the exception to a JS error using Hermes JSI on that background thread. Since Hermes is not thread-safe, this causes a SIGSEGV crash.

Environment
Environment:
React: 19.2.0
React Native: 0.83 (react-native-tvos@0.83-stable)
Expo: 55.0.7
@kesha-antonov/react-native-background-downloader: 4.5.4
React Native MKKV: 4.1.2
New Architecture: enabled

Target Platform:
iOS (26.3.1)
Expected Behavior
Calling setConfig({ allowsCellularAccess: true }) should enable downloads over cellular networks without crashing.

Actual Behavior
The app crashes immediately with EXC_BAD_ACCESS (SIGSEGV) on the com.eko.backgrounddownloader dispatch queue. The crash stack shows:

Thread 1 Crashed:: Dispatch queue: com.eko.backgrounddownloader
0  hermesvm   hermes::vm::GCScope::_newChunkAndPHV(hermes::vm::HermesValue)
...
14 hermesvm   facebook::hermes::HermesRuntimeImpl::setValueAtIndexImpl(...)
15 React      convertNSArrayToJSIArray(...)
16 React      convertNSExceptionToJSError(...)
17 React      ObjCTurboModule::performVoidMethodInvocation(...)
The native setAllowsCellularAccess: void method throws an NSException. The TurboModule bridge catches it and tries to convert it to a JS error via convertNSExceptionToJSError, but this involves creating JSI objects (Hermes VM operations) on the library's background dispatch queue (com.eko.backgrounddownloader), which is not the JS thread. Hermes is not thread-safe, so this crashes.

Root cause: The library's methodQueue returns a custom background queue (dispatch_queue_create("com.eko.backgrounddownloader", DISPATCH_QUEUE_SERIAL)). With the new architecture (TurboModules), any void native method that throws an NSException on this background queue will crash, because the error conversion path accesses the Hermes runtime from the wrong thread.

Note: This same crash pattern affects any native method that throws on the library's background queue -- not just setAllowsCellularAccess. We observed it with download: as well when the download fails (e.g., ATS rejection on HTTP URLs). The library works fine when no exceptions are thrown.

Workaround: Avoid calling setConfig({ allowsCellularAccess: true }). Instead, pass isAllowedOverMetered: true per-task in createDownloadTask() options, which doesn't trigger a separate native method call.

Steps to Reproduce
Create a React Native app with Expo 55, RN 0.83, new architecture enabled
Install @kesha-antonov/react-native-background-downloader@4.5.4
In a React component's useEffect, call:
setConfig({ allowsCellularAccess: true });
Trigger a download with createDownloadTask() + task.start()
App crashes with SIGSEGV on the com.eko.backgrounddownloader thread
Without allowsCellularAccess in setConfig, downloads work fine (but won't start on cellular networks).

With isAllowedOverMetered: true passed per-task in createDownloadTask(), downloads work on cellular without crashing.
Android: getExistingDownloadTasks returns empty array after force stop if download is active (works only when paused) #159
s this a bug report, a feature request, or a question?
bug report

Have you followed the required steps before opening a bug report?
(Check the step you've followed - put an x character between the square brackets ([]).)


I have reviewed the documentation in its entirety, including the dedicated documentations 📚.

I have searched for existing issues and made sure that the problem hasn't already been reported.

I am using the latest plugin version.
Is the bug specific to iOS or Android? Or can it be reproduced on both platforms?
I have only tested the brhaviour on android 13

Is the bug related to the native implementation? (NSURLSession on iOS and Fetch on Android)
No, i dont think so

Environment
Environment:
React: 19.1.0
React native: 0.81.5
react-native-background-downloader: 4.5.4

Target Platform:
Android (13)

Expected Behavior
calling getExistingDownloadTasks should return the list of formerly ongoing downloads on app restart

Actual Behavior
calling getExistingDownloadTasks after pausing the download works correctly even after app force stop but if the download is going then the app gets force stopped it would return an empty array on the next call

Android Bundled 239ms node_modules\expo-router\entry.js (1 module)
WARN  `new NativeEventEmitter()` was called with a non-null argument without the required `addListener` method.
WARN  `new NativeEventEmitter()` was called with a non-null argument without the required `removeListeners` method.
LOG  Existing Tasks [{"bytesDownloaded": 3571712, "bytesTotal": 391005611, "destination": "/data/user/0/com.thingfoil.movieland/files/downloads/movie_1290821_Shelter.mp4", "errorCode": 0, "id": "movie_1290821", "metadata": {"description": "Downloading video...", "title": "Shelter"}, "state": "PAUSED"}]
Android Bundled 209ms node_modules\expo-router\entry.js (1 module)
WARN  `new NativeEventEmitter()` was called with a non-null argument without the required `addListener` method.
WARN  `new NativeEventEmitter()` was called with a non-null argument without the required `removeListeners` method.
LOG  Existing Tasks []
Steps to Reproduce
pause download then force stop app (it works correctly)
don't pause download then force stop app (calling getExistingDownloadTasks now returns an empty array)
Rant
I installed this package about early january at V4.4.0(i also thought the package was unmaintained),and i tried using the getExistingDownloadTasks function but it didn't work after i restart the app,
so i frankensteined the app and had to use react-native blob utils and react-native-fs to find the parts this package already downloaded then read the file size of the already downloaded part to find the offset we'll use to download the remaining part, then after that we merge it with the old partial one and yeah the approach worked but is very unreliable and fragile since i had 3 possible spots of error, and i thought i'd check the package again a couple days ago
and saw that there are new releases and the one right after v4.4.0 had this Commit which basically said the getExistingDownloadTasks wasn't working before and unlucky me didn't see this till 2 months later and even funnier is i think i was the only one with that issue since there's only one download for v4.4.0 on npm, anyways so iinstalled the latest version v4.5.4 which is why i made this issue because it still returns empty array if i dont pause download first

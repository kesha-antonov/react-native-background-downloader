# Background Upload Implementation Guide

This document provides a detailed guide for implementing the native iOS and Android upload functionality.

## Overview

The JavaScript/TypeScript API layer is complete and tested. Native implementations are needed for both iOS and Android to enable actual file uploads.

## Architecture

The upload feature mirrors the download architecture:
- **JavaScript Layer**: Complete (UploadTask.ts, types, event handlers)
- **iOS Layer**: NSURLSessionUploadTask implementation needed
- **Android Layer**: Custom HTTP upload or WorkManager implementation needed

## API Design

### JavaScript API (✅ Complete)

```typescript
// Create an upload task
const task = createUploadTask({
  id: 'upload-123',
  url: 'https://api.example.com/upload',
  source: `${directories.documents}/photo.jpg`,
  method: 'POST', // or 'PUT', 'PATCH'
  headers: {
    'Authorization': 'Bearer token',
  },
  fieldName: 'file', // multipart form field name
  mimeType: 'image/jpeg',
  parameters: {
    userId: '123',
    action: 'upload',
  },
  metadata: { customData: 'value' },
})

// Set up event handlers
task
  .begin(({ expectedBytes }) => {
    console.log(`Uploading ${expectedBytes} bytes`)
  })
  .progress(({ bytesUploaded, bytesTotal }) => {
    console.log(`${bytesUploaded}/${bytesTotal}`)
  })
  .done(({ responseCode, responseBody, bytesUploaded, bytesTotal }) => {
    console.log('Upload complete:', responseCode, responseBody)
  })
  .error(({ error, errorCode }) => {
    console.error('Upload failed:', error)
  })

// Start the upload
task.start()

// Later: pause, resume, or stop
await task.pause()
await task.resume()
await task.stop()

// Re-attach to uploads after app restart
const existingUploads = await getExistingUploadTasks()
for (const upload of existingUploads) {
  upload.progress(/* ... */).done(/* ... */)
}
```

## iOS Implementation (TODO)

### Files to Create/Modify

1. **ios/RNBackgroundDownloader.mm** - Add upload methods
2. **ios/RNBGDTaskConfig.h/mm** - Add upload configuration

### Required Implementation

#### 1. NSURLSessionUploadTask Setup

```objc
// In RNBackgroundDownloader.mm

- (void)upload:(JS::NativeRNBackgroundDownloader::UploadOptions &)options {
  NSString *taskId = options.id();
  NSURL *url = [NSURL URLWithString:options.url()];
  NSString *source = options.source();
  NSString *method = options.method();
  
  // Create upload task configuration
  NSURLRequest *request = [self createUploadRequest:url method:method headers:headers];
  NSURL *fileURL = [NSURL fileURLWithPath:source];
  
  // Create upload task
  NSURLSessionUploadTask *uploadTask = [self.session uploadTaskWithRequest:request fromFile:fileURL];
  
  // Store task info for progress tracking
  [self storeUploadTask:uploadTask withId:taskId config:config];
  
  // Start upload
  [uploadTask resume];
}
```

#### 2. Progress Tracking

Use `URLSession:task:didSendBodyData:totalBytesSent:totalBytesExpectedToSend:` delegate:

```objc
- (void)URLSession:(NSURLSession *)session 
              task:(NSURLSessionTask *)task 
   didSendBodyData:(int64_t)bytesSent 
    totalBytesSent:(int64_t)totalBytesSent 
totalBytesExpectedToSend:(int64_t)totalBytesExpectedToSend {
  
  NSString *taskId = [self taskIdForTask:task];
  
  // Emit progress event
  [self sendEventWithName:@"uploadProgress" body:@{
    @"id": taskId,
    @"bytesUploaded": @(totalBytesSent),
    @"bytesTotal": @(totalBytesExpectedToSend)
  }];
}
```

#### 3. Completion Handling

```objc
- (void)URLSession:(NSURLSession *)session 
              task:(NSURLSessionTask *)task 
didCompleteWithError:(NSError *)error {
  
  NSString *taskId = [self taskIdForTask:task];
  
  if (error) {
    // Upload failed
    [self sendEventWithName:@"uploadFailed" body:@{
      @"id": taskId,
      @"error": error.localizedDescription,
      @"errorCode": @(error.code)
    }];
  } else if ([task isKindOfClass:[NSURLSessionUploadTask class]]) {
    NSHTTPURLResponse *response = (NSHTTPURLResponse *)task.response;
    
    // Upload completed
    [self sendEventWithName:@"uploadComplete" body:@{
      @"id": taskId,
      @"responseCode": @(response.statusCode),
      @"responseBody": [self readResponseBody:task],
      @"bytesUploaded": @(task.countOfBytesSent),
      @"bytesTotal": @(task.countOfBytesExpectedToSend)
    }];
  }
}
```

#### 4. Pause/Resume Support

```objc
RCT_EXPORT_METHOD(pauseUploadTask:(NSString *)taskId
                          resolver:(RCTPromiseResolveBlock)resolve
                          rejecter:(RCTPromiseRejectBlock)reject) {
  NSURLSessionUploadTask *task = [self uploadTaskForId:taskId];
  if (task) {
    [task suspend];
    resolve(nil);
  } else {
    reject(@"task_not_found", @"Upload task not found", nil);
  }
}

RCT_EXPORT_METHOD(resumeUploadTask:(NSString *)taskId
                           resolver:(RCTPromiseResolveBlock)resolve
                           rejecter:(RCTPromiseRejectBlock)reject) {
  NSURLSessionUploadTask *task = [self uploadTaskForId:taskId];
  if (task) {
    [task resume];
    resolve(nil);
  } else {
    reject(@"task_not_found", @"Upload task not found", nil);
  }
}
```

#### 5. State Persistence

Use the same MMKV storage pattern as downloads:

```objc
- (void)storeUploadTask:(NSURLSessionUploadTask *)task
                 withId:(NSString *)taskId
                 config:(RNBGDTaskConfig *)config {
  // Store task info for recovery after app restart
  NSDictionary *taskInfo = @{
    @"id": taskId,
    @"url": task.originalRequest.URL.absoluteString,
    @"source": config.source,
    @"metadata": config.metadata,
    // ... other config
  };
  
  [self.storage setObject:taskInfo forKey:[@"upload_" stringByAppendingString:taskId]];
}
```

### Testing

1. Test basic upload with small file
2. Test upload with large file (>10MB)
3. Test pause/resume
4. Test app restart during upload
5. Test network interruption recovery
6. Test background uploads when app is terminated

## Android Implementation (TODO)

### Files to Create/Modify

1. **android/src/main/java/com/eko/Uploader.kt** - New file for upload logic
2. **android/src/main/java/com/eko/RNBackgroundDownloaderModuleImpl.kt** - Add upload methods
3. **android/src/main/java/com/eko/UploadProgressReporter.kt** - Progress tracking

### Implementation Approaches

#### Option 1: OkHttp with MultipartBody (Recommended)

Pros:
- Robust HTTP client
- Built-in multipart support
- Good progress tracking
- Handles redirects and retries

```kotlin
class Uploader(
    private val context: Context,
    private val eventEmitter: UploadEventEmitter
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun upload(config: UploadTaskConfig) {
        val file = File(config.source)
        
        // Build multipart body
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                config.fieldName ?: "file",
                file.name,
                file.asRequestBody(config.mimeType?.toMediaType())
            )
        
        // Add additional parameters
        config.parameters?.forEach { (key, value) ->
            requestBody.addFormDataPart(key, value)
        }
        
        // Build request
        val request = Request.Builder()
            .url(config.url)
            .method(config.method, requestBody.build())
            .apply {
                config.headers?.forEach { (key, value) ->
                    addHeader(key, value)
                }
            }
            .build()
        
        // Execute with progress tracking
        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                eventEmitter.emitComplete(
                    config.id,
                    response.code,
                    response.body?.string() ?: "",
                    file.length(),
                    file.length()
                )
            }
            
            override fun onFailure(call: Call, e: IOException) {
                eventEmitter.emitError(config.id, e.message ?: "Upload failed", -1)
            }
        })
    }
}
```

#### Option 2: WorkManager for Background Uploads

Pros:
- Survives app termination
- System-managed scheduling
- Battery-friendly

Cons:
- More complex setup
- Limited real-time progress

```kotlin
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result {
        val taskId = inputData.getString("taskId") ?: return Result.failure()
        val url = inputData.getString("url") ?: return Result.failure()
        val source = inputData.getString("source") ?: return Result.failure()
        
        return try {
            // Perform upload
            performUpload(url, source, taskId)
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
    
    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            NOTIFICATION_ID,
            createNotification()
        )
    }
}
```

### Progress Tracking

Implement custom RequestBody to track progress:

```kotlin
class CountingRequestBody(
    private val delegate: RequestBody,
    private val listener: (bytesWritten: Long, totalBytes: Long) -> Unit
) : RequestBody() {
    
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    
    override fun writeTo(sink: BufferedSink) {
        val countingSink = CountingSink(sink)
        val bufferedSink = countingSink.buffer()
        
        delegate.writeTo(bufferedSink)
        bufferedSink.flush()
    }
    
    private inner class CountingSink(delegate: Sink) : ForwardingSink(delegate) {
        private var bytesWritten = 0L
        
        override fun write(source: Buffer, byteCount: Long) {
            super.write(source, byteCount)
            bytesWritten += byteCount
            listener(bytesWritten, contentLength())
        }
    }
}
```

### State Persistence

Use same MMKV pattern as downloads:

```kotlin
class UploadStateManager(private val storage: MMKV) {
    
    fun saveUploadState(taskId: String, config: UploadTaskConfig, state: Int) {
        val json = Gson().toJson(mapOf(
            "id" to taskId,
            "url" to config.url,
            "source" to config.source,
            "state" to state,
            "metadata" to config.metadata
        ))
        storage.encode("upload_$taskId", json)
    }
    
    fun getUploadState(taskId: String): UploadTaskState? {
        val json = storage.decodeString("upload_$taskId") ?: return null
        return Gson().fromJson(json, UploadTaskState::class.java)
    }
    
    fun getAllUploadStates(): List<UploadTaskState> {
        return storage.allKeys()
            ?.filter { it.startsWith("upload_") }
            ?.mapNotNull { getUploadState(it.removePrefix("upload_")) }
            ?: emptyList()
    }
}
```

### Testing

1. Test basic upload (small file)
2. Test large file upload (>50MB)
3. Test multipart with parameters
4. Test different HTTP methods (POST, PUT, PATCH)
5. Test pause/resume (if supported)
6. Test network disconnection
7. Test app termination and recovery
8. Test Android doze mode
9. Test different Android versions

## Integration Checklist

### Phase 1: iOS Basic Upload
- [ ] Implement NSURLSessionUploadTask
- [ ] Add progress tracking
- [ ] Add completion/error handling
- [ ] Test basic upload

### Phase 2: iOS Advanced Features
- [ ] Implement pause/resume
- [ ] Add state persistence
- [ ] Handle background sessions
- [ ] Add getExistingUploadTasks
- [ ] Test app restart scenarios

### Phase 3: Android Basic Upload
- [ ] Choose implementation approach
- [ ] Implement basic upload
- [ ] Add progress tracking
- [ ] Add completion/error handling
- [ ] Test basic upload

### Phase 4: Android Advanced Features
- [ ] Implement pause/resume (if possible)
- [ ] Add state persistence
- [ ] Handle background execution
- [ ] Add getExistingUploadTasks
- [ ] Test app restart scenarios

### Phase 5: Cross-Platform Testing
- [ ] Test iOS and Android parity
- [ ] Test with example app
- [ ] Performance testing
- [ ] Memory leak testing
- [ ] Battery impact testing

### Phase 6: Documentation
- [ ] Update README with upload API
- [ ] Add example code
- [ ] Document limitations
- [ ] Update CHANGELOG

## Known Limitations

### iOS
- Background uploads must complete within the system's background time limit
- Large files (>100MB) may not upload reliably in background if app is terminated
- iOS may throttle uploads in low power mode

### Android
- Pause/resume may not be possible with all implementations
- WorkManager uploads may be delayed by the system
- Android doze mode can affect background uploads
- Some devices may kill foreground services aggressively

## API Compatibility Notes

The upload API is designed to be optional and backward compatible:
- Old native implementations without upload support will continue to work
- Upload methods are marked optional in the TurboModule spec
- Calling upload functions without native support logs a warning but doesn't crash
- This allows gradual rollout of the feature

## References

- [NSURLSession Upload Tasks](https://developer.apple.com/documentation/foundation/nsurlsessionuploadtask)
- [Android WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
- [OkHttp Documentation](https://square.github.io/okhttp/)
- [Multipart Upload Best Practices](https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods/POST)

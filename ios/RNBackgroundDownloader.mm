#import "RNBackgroundDownloader.h"
#import "RNBGDTaskConfig.h"
#import "RNBGDUploadTaskConfig.h"
#import <MMKV/MMKV.h>
#import <React/RCTBridge.h>

#ifdef RCT_NEW_ARCH_ENABLED
#import <RNBackgroundDownloaderSpec/RNBackgroundDownloaderSpec.h>
#import <ReactCommon/TurboModule.h>
#endif

#define ID_TO_CONFIG_MAP_KEY @"com.eko.bgdownloadidmap"
#define ID_TO_UPLOAD_CONFIG_MAP_KEY @"com.eko.bguploadidmap"
#define PROGRESS_INTERVAL_KEY @"progressInterval"
#define PROGRESS_MIN_BYTES_KEY @"progressMinBytes"

// Session configuration constants
static const NSInteger kMaxConnectionsPerHost = 4;
static const NSTimeInterval kRequestTimeoutSeconds = 60 * 60;        // 1 hour - max time to get new data
static const NSTimeInterval kResourceTimeoutSeconds = 60 * 60 * 24;  // 1 day - max time to download resource

// Progress reporting constants
static const NSTimeInterval kTaskReconciliationDelay = 0.1;  // Delay to allow session tasks to stabilize
static const float kProgressReportThreshold = 0.01f;         // Report progress every 1% change
static const NSTimeInterval kCompletionHandlerTimeout = 30.0; // Timeout for completion handler (30 seconds)

// DLog accepts taskId as first parameter to help debugging
// Only logs if isLogsEnabled is true (works in both DEBUG and RELEASE builds)
// Note: Cannot be used in class methods (+) since it references self->isLogsEnabled
#define DLog( taskId, s, ... ) do { if (self->isLogsEnabled) { NSLog( @"<%p %@:(%d)> %@ %@", self, [[NSString stringWithUTF8String:__FILE__] lastPathComponent], __LINE__, [NSString stringWithFormat:(s), ##__VA_ARGS__], ((id)(taskId) ? [NSString stringWithFormat:@"taskId:%@", (id)(taskId)] : @"taskId:NULL") ); } } while(0)

// Static log macro for class methods - always logs when DEBUG is enabled
#ifdef DEBUG
#define DLogStatic( taskId, s, ... ) do { NSLog( @"<%@ %@:(%d)> %@ %@", @"RNBackgroundDownloader", [[NSString stringWithUTF8String:__FILE__] lastPathComponent], __LINE__, [NSString stringWithFormat:(s), ##__VA_ARGS__], ((id)(taskId) ? [NSString stringWithFormat:@"taskId:%@", (id)(taskId)] : @"taskId:NULL") ); } while(0)
#else
#define DLogStatic( taskId, s, ... ) do { } while(0)
#endif

static CompletionHandler storedCompletionHandler;

// Maximum retry attempts for queued events when bridge isn't ready
static const int kMaxEventRetries = 50;  // 50 retries * 100ms = 5 seconds max wait

@implementation RNBackgroundDownloader {
    MMKV *mmkv;
    NSURLSession *urlSession;
    NSURLSessionConfiguration *sessionConfig;
    NSNumber *sharedLock;
    NSMutableDictionary<NSNumber *, RNBGDTaskConfig *> *taskToConfigMap;
    NSMutableDictionary<NSString *, NSURLSessionDownloadTask *> *idToTaskMap;
    NSMutableDictionary<NSString *, NSData *> *idToResumeDataMap;
    NSMutableDictionary<NSString *, NSNumber *> *idToPercentMap;
    NSMutableDictionary<NSString *, NSDictionary *> *progressReports;
    NSMutableDictionary<NSString *, NSNumber *> *idToLastBytesMap;
    NSMutableSet<NSString *> *idsToPauseSet;
    // Tracks tasks that have already been retried once after a decode error (-1015)
    NSMutableSet<NSString *> *decodeErrorRetriedIds;
    float progressInterval;
    int64_t progressMinBytes;
    NSDate *lastProgressReportedAt;
    BOOL isBridgeListenerInited;
    BOOL hasListeners;
    // Tracks whether the session has been fully activated (warmed up)
    BOOL isSessionActivated;
    // Queue of download operations waiting for session activation
    NSMutableArray<dispatch_block_t> *pendingDownloads;
    // Controls whether debug logs are sent to JS
    BOOL isLogsEnabled;

    // Upload-specific instance variables
    NSMutableDictionary<NSNumber *, RNBGDUploadTaskConfig *> *uploadTaskToConfigMap;
    NSMutableDictionary<NSString *, NSURLSessionUploadTask *> *idToUploadTaskMap;
    NSMutableDictionary<NSString *, NSNumber *> *idToUploadPercentMap;
    NSMutableDictionary<NSString *, NSDictionary *> *uploadProgressReports;
    NSMutableDictionary<NSString *, NSNumber *> *idToUploadLastBytesMap;
    NSMutableSet<NSString *> *idsToUploadPauseSet;
    NSDate *lastUploadProgressReportedAt;
}

RCT_EXPORT_MODULE();

// Enable interop layer so NativeModules.RNBackgroundDownloader is available
// This is required for NativeEventEmitter to work with TurboModules
+ (BOOL)requiresMainQueueSetup {
    return NO;
}

#pragma mark - Helper methods

- (RNBGDTaskConfig *)configForTask:(NSURLSessionTask *)task {
    return taskToConfigMap[@(task.taskIdentifier)];
}

- (RNBGDUploadTaskConfig *)uploadConfigForTask:(NSURLSessionTask *)task {
    return uploadTaskToConfigMap[@(task.taskIdentifier)];
}

- (dispatch_queue_t)methodQueue
{
    return dispatch_queue_create("com.eko.backgrounddownloader", DISPATCH_QUEUE_SERIAL);
}

#ifndef RCT_NEW_ARCH_ENABLED
- (NSArray<NSString *> *)supportedEvents {
    return @[
        @"downloadBegin",
        @"downloadProgress",
        @"downloadComplete",
        @"downloadFailed",
        @"uploadBegin",
        @"uploadProgress",
        @"uploadComplete",
        @"uploadFailed",
        @"nativeDebugLog"
    ];
}
#endif

// Helper method to send debug logs to JS
- (void)sendDebugLog:(NSString *)message taskId:(NSString *)taskId {
    // Only send logs if logging is enabled
    if (!isLogsEnabled) {
        return;
    }

    NSDictionary *body = @{
        @"message": message ?: @"",
        @"taskId": taskId ?: @"",
        @"timestamp": @([[NSDate date] timeIntervalSince1970] * 1000)
    };
#ifdef RCT_NEW_ARCH_ENABLED
    // For new architecture, we'd need to add this to the spec
    // For now, just log to NSLog
    NSLog(@"[RNBD-Native] %@ taskId:%@", message, taskId);
#else
    [self sendEventWithName:@"nativeDebugLog" body:body];
#endif
}

#ifndef RCT_NEW_ARCH_ENABLED
// Old architecture override to ensure events are sent
// Check if bridge is valid and loaded before sending events
- (void)sendEventWithName:(NSString *)eventName body:(id)body {
    [self sendEventWithName:eventName body:body retryCount:0];
}

- (void)sendEventWithName:(NSString *)eventName body:(id)body retryCount:(int)retryCount {
    // Check if bridge is available and loaded
    // This prevents crashes on first app install when events fire before JS is ready
    if (self.bridge == nil || !self.bridge.isValid) {
        if (retryCount < kMaxEventRetries) {
            DLog(nil, @"[RNBackgroundDownloader] - Bridge not ready (attempt %d), queueing event: %@", retryCount + 1, eventName);
            // Queue the event to be sent when bridge becomes available
            dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.1 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
                [self sendEventWithName:eventName body:body retryCount:retryCount + 1];
            });
        } else {
            DLog(nil, @"[RNBackgroundDownloader] - Bridge not ready after %d attempts, dropping event: %@", kMaxEventRetries, eventName);
        }
        return;
    }
    [super sendEventWithName:eventName body:body];
}
#endif

- (NSDictionary *)constantsToExport {
    NSArray *paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
    return @{
        @"documents": [paths firstObject],
        @"TaskRunning": @(NSURLSessionTaskStateRunning), // 0
        @"TaskSuspended": @(NSURLSessionTaskStateSuspended), // 1
        @"TaskCanceling": @(NSURLSessionTaskStateCanceling), // 2
        @"TaskCompleted": @(NSURLSessionTaskStateCompleted) // 3
    };
}

#ifdef RCT_NEW_ARCH_ENABLED
- (NSDictionary *)getConstants {
    return [self constantsToExport];
}
#endif

- (id)init {
    DLog(nil, @"[RNBackgroundDownloader] - [init]");
#ifdef RCT_NEW_ARCH_ENABLED
    // New architecture uses generated base class
    self = [super init];
#else
    // Use initWithDisabledObservation to bypass listener count check
    // This ensures events are always sent even when JS uses DeviceEventEmitter
    // instead of NativeEventEmitter (which would call addListener)
    self = [super initWithDisabledObservation];
#endif
    if (self) {
        [MMKV initializeMMKV:nil];
        mmkv = [MMKV mmkvWithID:@"RNBackgroundDownloader"];

        NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
        NSString *sessionIdentifier = [bundleIdentifier stringByAppendingString:@".backgrounddownloadtask"];
        sessionConfig = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:sessionIdentifier];
        sessionConfig.HTTPMaximumConnectionsPerHost = kMaxConnectionsPerHost;
        sessionConfig.timeoutIntervalForRequest = kRequestTimeoutSeconds;
        sessionConfig.timeoutIntervalForResource = kResourceTimeoutSeconds;
        sessionConfig.discretionary = NO;
        sessionConfig.sessionSendsLaunchEvents = YES;
        // These APIs are available since our minimum iOS version (15.1)
        sessionConfig.shouldUseExtendedBackgroundIdleMode = YES;
        sessionConfig.allowsExpensiveNetworkAccess = YES;

        sharedLock = [NSNumber numberWithInt:1];

        NSData *taskToConfigMapData = [mmkv getDataForKey:ID_TO_CONFIG_MAP_KEY];
        NSMutableDictionary *taskToConfigMapDataDefault = [[NSMutableDictionary alloc] init];
        NSMutableDictionary *taskToConfigMapDataDecoded = taskToConfigMapData != nil ? [self deserialize:taskToConfigMapData] : nil;
        taskToConfigMap = taskToConfigMapDataDecoded != nil ? taskToConfigMapDataDecoded : taskToConfigMapDataDefault;
        idToTaskMap = [[NSMutableDictionary alloc] init];
        idToResumeDataMap = [[NSMutableDictionary alloc] init];
        idToPercentMap = [[NSMutableDictionary alloc] init];
        idsToPauseSet = [[NSMutableSet alloc] init];

        progressReports = [[NSMutableDictionary alloc] init];
        idToLastBytesMap = [[NSMutableDictionary alloc] init];
        float progressIntervalScope = [mmkv getFloatForKey:PROGRESS_INTERVAL_KEY];
        progressInterval = isnan(progressIntervalScope) ? 1.0 : progressIntervalScope;
        int64_t progressMinBytesScope = [mmkv getInt64ForKey:PROGRESS_MIN_BYTES_KEY];
        progressMinBytes = progressMinBytesScope > 0 ? progressMinBytesScope : 0;
        lastProgressReportedAt = [[NSDate alloc] init];
        decodeErrorRetriedIds = [[NSMutableSet alloc] init];
        isSessionActivated = NO;
        pendingDownloads = [[NSMutableArray alloc] init];

        // Initialize upload-specific data structures
        NSData *uploadTaskToConfigMapData = [mmkv getDataForKey:ID_TO_UPLOAD_CONFIG_MAP_KEY];
        NSMutableDictionary *uploadTaskToConfigMapDataDefault = [[NSMutableDictionary alloc] init];
        NSMutableDictionary *uploadTaskToConfigMapDataDecoded = uploadTaskToConfigMapData != nil ? [self deserializeUploadConfig:uploadTaskToConfigMapData] : nil;
        uploadTaskToConfigMap = uploadTaskToConfigMapDataDecoded != nil ? uploadTaskToConfigMapDataDecoded : uploadTaskToConfigMapDataDefault;
        idToUploadTaskMap = [[NSMutableDictionary alloc] init];
        idToUploadPercentMap = [[NSMutableDictionary alloc] init];
        uploadProgressReports = [[NSMutableDictionary alloc] init];
        idToUploadLastBytesMap = [[NSMutableDictionary alloc] init];
        idsToUploadPauseSet = [[NSMutableSet alloc] init];
        lastUploadProgressReportedAt = [[NSDate alloc] init];

        [self registerBridgeListener];

        // Initialize session early to receive background events on app relaunch
        [self lazyRegisterSession];
    }

    return self;
}

- (void)dealloc {
    DLog(nil, @"[RNBackgroundDownloader] - [dealloc]");
    [self unregisterSession];
    [self unregisterBridgeListener];
}

- (void)handleBridgeHotReload:(NSNotification *) note {
    DLog(nil, @"[RNBackgroundDownloader] - [handleBridgeHotReload]");
    [self unregisterSession];
    [self unregisterBridgeListener];
}

- (void)lazyRegisterSession {
    DLog(nil, @"[RNBackgroundDownloader] - [lazyRegisterSession]");
    [self sendDebugLog:@"lazyRegisterSession called" taskId:nil];
    @synchronized (sharedLock) {
        if (urlSession == nil) {
            [self sendDebugLog:@"lazyRegisterSession: creating new session" taskId:nil];
            urlSession = [NSURLSession sessionWithConfiguration:sessionConfig delegate:self delegateQueue:nil];
            // Activate the session by calling getTasksWithCompletionHandler
            // This forces iOS to fully initialize the background session
            // On fresh installs, the session may not be ready to process tasks immediately
            [self activateSession];
        } else {
            [self sendDebugLog:@"lazyRegisterSession: session already exists" taskId:nil];
        }
    }
}

// Activates the background session by warming it up with getTasksWithCompletionHandler
// This is crucial for fresh app installs where the session needs to be fully initialized
// before downloads can start reliably
- (void)activateSession {
    DLog(nil, @"[RNBackgroundDownloader] - [activateSession]");
    [self sendDebugLog:@"activateSession: calling getTasksWithCompletionHandler" taskId:nil];
    [urlSession getTasksWithCompletionHandler:^(NSArray<NSURLSessionDataTask *> * _Nonnull dataTasks, NSArray<NSURLSessionUploadTask *> * _Nonnull uploadTasks, NSArray<NSURLSessionDownloadTask *> * _Nonnull downloadTasks) {
        @synchronized (self->sharedLock) {
            NSString *logMsg = [NSString stringWithFormat:@"activateSession: session activated, pendingDownloads=%lu, existingTasks=%lu",
                (unsigned long)self->pendingDownloads.count, (unsigned long)downloadTasks.count];
            DLog(nil, @"[RNBackgroundDownloader] - %@", logMsg);
            [self sendDebugLog:logMsg taskId:nil];
            self->isSessionActivated = YES;

            // Process any pending downloads that were queued while waiting for activation
            for (dispatch_block_t downloadBlock in self->pendingDownloads) {
                downloadBlock();
            }
            [self->pendingDownloads removeAllObjects];
        }
    }];
}

- (void)unregisterSession {
    DLog(nil, @"[RNBackgroundDownloader] - [unregisterSession]");
    if (urlSession) {
        [urlSession invalidateAndCancel];
        urlSession = nil;
    }
    isSessionActivated = NO;
    [pendingDownloads removeAllObjects];
}

- (void)registerBridgeListener {
    DLog(nil, @"[RNBackgroundDownloader] - [registerBridgeListener]");
    @synchronized (sharedLock) {
        if (isBridgeListenerInited != YES) {
            isBridgeListenerInited = YES;
            [[NSNotificationCenter defaultCenter] addObserver:self
                                                  selector:@selector(handleBridgeAppEnterForeground:)
                                                  name:UIApplicationWillEnterForegroundNotification
                                                  object:nil];

            [[NSNotificationCenter defaultCenter] addObserver:self
                                                  selector:@selector(handleBridgeHotReload:)
                                                  name:RCTJavaScriptWillStartLoadingNotification
                                                  object:nil];
        }
    }
}

- (void)unregisterBridgeListener {
    DLog(nil, @"[RNBackgroundDownloader] - [unregisterBridgeListener]");
    if (isBridgeListenerInited == YES) {
        [[NSNotificationCenter defaultCenter] removeObserver:self];
        isBridgeListenerInited = NO;
    }
}

- (void)handleBridgeAppEnterForeground:(NSNotification *) note {
    DLog(nil, @"[RNBackgroundDownloader] - [handleBridgeAppEnterForeground]");
    [self resumeTasks];
}

- (void)resumeTasks {
    @synchronized (sharedLock) {
        DLog(nil, @"[RNBackgroundDownloader] - [resumeTasks]");
        [urlSession getTasksWithCompletionHandler:^(NSArray<NSURLSessionDataTask *> * _Nonnull dataTasks, NSArray<NSURLSessionUploadTask *> * _Nonnull uploadTasks, NSArray<NSURLSessionDownloadTask *> * _Nonnull downloadTasks) {
            for (NSURLSessionDownloadTask *task in downloadTasks) {
                // running - 0
                // suspended - 1
                // canceling - 2
                // completed - 3
                if (task.state == NSURLSessionTaskStateRunning) {
                    [task suspend];
                    [task resume];
                }
            }
        }];
    }
}

- (void)removeTaskFromMap: (NSURLSessionTask *)task {
    @synchronized (sharedLock) {
        NSNumber *taskId = @(task.taskIdentifier);
        RNBGDTaskConfig *taskConfig = taskToConfigMap[taskId];
        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [removeTaskFromMap]");

        [taskToConfigMap removeObjectForKey:taskId];
        [mmkv setData:[self serialize: taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];

        if (taskConfig) {
            [self -> idToTaskMap removeObjectForKey:taskConfig.id];
            [idToPercentMap removeObjectForKey:taskConfig.id];
            [idToLastBytesMap removeObjectForKey:taskConfig.id];
            // Clean up decode error retry tracking to prevent memory leak
            [decodeErrorRetriedIds removeObject:taskConfig.id];
        }
    }
}

#pragma mark - JS exported methods

// Method to enable/disable native debug logging
- (void)_setLogsEnabledInternal:(BOOL)enabled {
    isLogsEnabled = enabled;
}

#ifdef RCT_NEW_ARCH_ENABLED
- (void)setLogsEnabled:(BOOL)enabled {
    [self _setLogsEnabledInternal:enabled];
}
#else
RCT_EXPORT_METHOD(setLogsEnabled:(BOOL)enabled) {
    [self _setLogsEnabledInternal:enabled];
}
#endif

- (void)_setMaxParallelDownloadsInternal:(NSInteger)max {
    DLog(nil, @"[RNBackgroundDownloader] - [setMaxParallelDownloads:%ld]", (long)max);
    @synchronized (sharedLock) {
        if (max >= 1) {
            sessionConfig.HTTPMaximumConnectionsPerHost = max;
            // Recreate session with new config if it's already initialized
            if (urlSession != nil) {
                [self unregisterSession];
                [self lazyRegisterSession];
            }
        }
    }
}

#ifdef RCT_NEW_ARCH_ENABLED
- (void)setMaxParallelDownloads:(double)max {
    [self _setMaxParallelDownloadsInternal:(NSInteger)max];
}
#else
RCT_EXPORT_METHOD(setMaxParallelDownloads:(NSInteger)max) {
    [self _setMaxParallelDownloadsInternal:max];
}
#endif

- (void)_setAllowsCellularAccessInternal:(BOOL)allows {
    DLog(nil, @"[RNBackgroundDownloader] - [setAllowsCellularAccess:%@]", allows ? @"YES" : @"NO");
    @synchronized (sharedLock) {
        sessionConfig.allowsCellularAccess = allows;
        // Recreate session with new config if it's already initialized
        if (urlSession != nil) {
            [self unregisterSession];
            [self lazyRegisterSession];
        }
    }
}

#ifdef RCT_NEW_ARCH_ENABLED
- (void)setAllowsCellularAccess:(BOOL)allows {
    [self _setAllowsCellularAccessInternal:allows];
}
#else
RCT_EXPORT_METHOD(setAllowsCellularAccess:(BOOL)allows) {
    [self _setAllowsCellularAccessInternal:allows];
}
#endif

#ifdef RCT_NEW_ARCH_ENABLED
- (void)download:(JS::NativeRNBackgroundDownloader::SpecDownloadOptions &)options {
    NSString *identifier = options.id_();
    DLog(identifier, @"[RNBackgroundDownloader] - [download]");
    NSString *url = options.url();
    NSString *destination = options.destination();
    NSString *metadata = options.metadata() ? options.metadata() : @"";
    NSDictionary *headers = options.headers() ? (NSDictionary *)options.headers() : nil;

    if (options.progressInterval().has_value()) {
        progressInterval = options.progressInterval().value() / 1000.0;
        [mmkv setFloat:progressInterval forKey:PROGRESS_INTERVAL_KEY];
    }

    if (options.progressMinBytes().has_value()) {
        progressMinBytes = (int64_t)options.progressMinBytes().value();
        [mmkv setInt64:progressMinBytes forKey:PROGRESS_MIN_BYTES_KEY];
    }

    NSString *destinationRelative = [self getRelativeFilePathFromPath:destination];
#else
RCT_EXPORT_METHOD(download: (NSDictionary *) options) {
    NSString *identifier = options[@"id"];
    DLog(identifier, @"[RNBackgroundDownloader] - [download]");
    NSString *url = options[@"url"];
    NSString *destination = options[@"destination"];
    NSString *metadata = options[@"metadata"] ?: @"";
    NSDictionary *headers = options[@"headers"];

    NSNumber *progressIntervalScope = options[@"progressInterval"];
    if (progressIntervalScope) {
        progressInterval = [progressIntervalScope intValue] / 1000.0;
        [mmkv setFloat:progressInterval forKey:PROGRESS_INTERVAL_KEY];
    }

    NSNumber *progressMinBytesScope = options[@"progressMinBytes"];
    if (progressMinBytesScope) {
        progressMinBytes = [progressMinBytesScope longLongValue];
        [mmkv setInt64:progressMinBytes forKey:PROGRESS_MIN_BYTES_KEY];
    }

    NSString *destinationRelative = [self getRelativeFilePathFromPath:destination];
#endif

    DLog(identifier, @"[RNBackgroundDownloader] - [download] url %@ destination %@ progressInterval %f", url, destination, progressInterval);
    if (identifier == nil || url == nil || destination == nil) {
        DLog(identifier, @"[RNBackgroundDownloader] - [Error] id, url and destination must be set");
        return;
    }

    if (destinationRelative == nil) {
        DLog(identifier, @"[RNBackgroundDownloader] - [Error] destination is not valid");
        return;
    }

    NSMutableURLRequest *request = [[NSMutableURLRequest alloc] initWithURL:[NSURL URLWithString:url]];
    // Query in the getExistingDownloadTasks function.
    [request setValue:identifier forHTTPHeaderField:@"configId"];
    if (headers != nil) {
        for (NSString *headerKey in headers) {
            [request setValue:[headers valueForKey:headerKey] forHTTPHeaderField:headerKey];
        }
    }

    @synchronized (sharedLock) {
        [self sendDebugLog:@"download: calling lazyRegisterSession" taskId:identifier];
        [self lazyRegisterSession];

        // If session is not yet activated, queue the download to be executed after activation
        // This fixes the issue where downloads don't start on fresh app installs
        if (!isSessionActivated) {
            DLog(identifier, @"[RNBackgroundDownloader] - [download] session not activated, queueing download");
            [self sendDebugLog:@"download: session not activated, queueing download" taskId:identifier];
            __weak RNBackgroundDownloader *weakSelf = self;
            dispatch_block_t downloadBlock = ^{
                RNBackgroundDownloader *strongSelf = weakSelf;
                if (strongSelf) {
                    [strongSelf executeDownloadWithRequest:request identifier:identifier url:url destination:destination metadata:metadata];
                }
            };
            [pendingDownloads addObject:downloadBlock];
            return;
        }

        [self sendDebugLog:@"download: session activated, executing download" taskId:identifier];
        [self executeDownloadWithRequest:request identifier:identifier url:url destination:destination metadata:metadata];
    }
}

// Internal method to execute the download after session is activated
- (void)executeDownloadWithRequest:(NSMutableURLRequest *)request identifier:(NSString *)identifier url:(NSString *)url destination:(NSString *)destination metadata:(NSString *)metadata {
    @synchronized (sharedLock) {
        DLog(identifier, @"[RNBackgroundDownloader] - [executeDownloadWithRequest]");
        [self sendDebugLog:@"executeDownloadWithRequest: creating download task" taskId:identifier];

        NSURLSessionDownloadTask __strong *task = [urlSession downloadTaskWithRequest:request];
        if (task == nil) {
            DLog(identifier, @"[RNBackgroundDownloader] - [Error] failed to create download task");
            [self sendDebugLog:@"executeDownloadWithRequest: ERROR - failed to create download task" taskId:identifier];
            return;
        }

        [self sendDebugLog:[NSString stringWithFormat:@"executeDownloadWithRequest: task created with taskIdentifier=%lu", (unsigned long)task.taskIdentifier] taskId:identifier];

        RNBGDTaskConfig *taskConfig = [[RNBGDTaskConfig alloc] initWithDictionary: @{
            @"id": identifier,
            @"url": url,
            @"destination": destination,
            @"metadata": metadata
        }];

        taskToConfigMap[@(task.taskIdentifier)] = taskConfig;
        [mmkv setData:[self serialize: taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];

        self->idToTaskMap[identifier] = task;
        idToPercentMap[identifier] = @0.0;
        idToLastBytesMap[identifier] = @0;

        [task resume];
        [self sendDebugLog:@"executeDownloadWithRequest: task.resume() called" taskId:identifier];
        lastProgressReportedAt = [[NSDate alloc] init];
    }
}

- (void)pauseTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(identifier, @"[RNBackgroundDownloader] - [pauseTask]");
    @try {
        @synchronized (sharedLock) {
            NSURLSessionDownloadTask *task = self->idToTaskMap[identifier];
            if (task != nil) {
                // Mark task as pausing IMMEDIATELY before cancellation to prevent race condition
                [idsToPauseSet addObject:identifier];
                DLog(identifier, @"[RNBackgroundDownloader] - [pauseTask] marking task as pausing: %@", identifier);

                [task cancelByProducingResumeData:^(NSData * _Nullable resumeData) {
                    @synchronized (self->sharedLock) {
                        if (resumeData != nil) {
                            self->idToResumeDataMap[identifier] = resumeData;
                            DLog(identifier, @"[RNBackgroundDownloader] - [pauseTask] stored resume data for %@", identifier);

                            // Save resume data to file and update task config with hasResumeData flag
                            RNBGDTaskConfig *taskConfig = self->taskToConfigMap[@(task.taskIdentifier)];
                            if (taskConfig) {
                                // Save resume data to file instead of storing in MMKV
                                [self saveResumeData:resumeData forTaskId:identifier];
                                taskConfig.hasResumeData = YES;
                                taskConfig.state = NSURLSessionTaskStateCanceling;
                                taskConfig.errorCode = -999;
                                [self->mmkv setData:[self serialize:self->taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];
                                DLog(identifier, @"[RNBackgroundDownloader] - [pauseTask] persisted resume data to file for %@", identifier);
                            }
                        } else {
                            DLog(identifier, @"[RNBackgroundDownloader] - [pauseTask] no resume data available for %@", identifier);
                        }
                        // Keep the identifier in idsToPauseSet until resume or stop is called
                    }
                    resolve(nil);
                }];
            } else {
                resolve(nil);
            }
        }
    } @catch (NSException *exception) {
        reject(@"ERR_PAUSE_TASK", exception.reason, nil);
    }
}

- (void)pauseTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    [self pauseTaskInternal:identifier resolve:resolve reject:reject];
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(pauseTask:(NSString *)id resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self pauseTaskInternal:id resolve:resolve reject:reject];
}
#endif

- (void)resumeTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(identifier, @"[RNBackgroundDownloader] - [resumeTask]");
    @try {
        @synchronized (sharedLock) {
            [self lazyRegisterSession];

            // Remove from pause set when resuming
            [idsToPauseSet removeObject:identifier];

            NSData *resumeData = self->idToResumeDataMap[identifier];
            NSURLSessionDownloadTask *task = self->idToTaskMap[identifier];

            // If no resume data in memory, try to load from file
            if (resumeData == nil) {
                RNBGDTaskConfig *taskConfig = [self findConfigById:identifier];
                if (taskConfig != nil && taskConfig.hasResumeData) {
                    resumeData = [self loadResumeDataForTaskId:identifier];
                    DLog(identifier, @"[RNBackgroundDownloader] - [resumeTask] loaded resume data from file for %@", identifier);
                }
            }

            if (resumeData != nil) {
                // Task was paused with resume data, create new task from resume data
                DLog(identifier, @"[RNBackgroundDownloader] - [resumeTask] resuming with resume data for %@", identifier);

                NSURLSessionDownloadTask *newTask = [urlSession downloadTaskWithResumeData:resumeData];
                if (newTask != nil) {
                    // Get the task config from the old task or find by id
                    RNBGDTaskConfig *taskConfig = nil;
                    if (task != nil) {
                        taskConfig = taskToConfigMap[@(task.taskIdentifier)];
                        [taskToConfigMap removeObjectForKey:@(task.taskIdentifier)];
                    } else {
                        // Task not in idToTaskMap, find config by id and remove old mapping
                        taskConfig = [self findConfigById:identifier];
                        if (taskConfig != nil) {
                            NSNumber *oldKey = [self findTaskKeyById:identifier];
                            if (oldKey != nil) {
                                [taskToConfigMap removeObjectForKey:oldKey];
                            }
                        }
                    }

                    // Update mappings with new task
                    if (taskConfig != nil) {
                        taskConfig.state = NSURLSessionTaskStateRunning;
                        taskConfig.errorCode = 0;
                        taskConfig.hasResumeData = NO; // Clear flag after resuming
                        taskToConfigMap[@(newTask.taskIdentifier)] = taskConfig;
                        [mmkv setData:[self serialize: taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];
                    }

                    self->idToTaskMap[identifier] = newTask;
                    [self->idToResumeDataMap removeObjectForKey:identifier];
                    // Delete the resume data file after successful resume
                    [self deleteResumeDataForTaskId:identifier];
                    [newTask resume];
                }
            } else if (task != nil && task.state == NSURLSessionTaskStateSuspended) {
                // Task was suspended normally, just resume it
                DLog(identifier, @"[RNBackgroundDownloader] - [resumeTask] resuming suspended task for %@", identifier);
                RNBGDTaskConfig *taskConfig = taskToConfigMap[@(task.taskIdentifier)];
                if (taskConfig != nil) {
                    taskConfig.state = NSURLSessionTaskStateRunning;
                    taskConfig.errorCode = 0;
                }
                [task resume];
            } else {
                DLog(identifier, @"[RNBackgroundDownloader] - [resumeTask] no task or resume data found for %@", identifier);
            }
        }
        resolve(nil);
    } @catch (NSException *exception) {
        reject(@"ERR_RESUME_TASK", exception.reason, nil);
    }
}

- (void)resumeTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    [self resumeTaskInternal:identifier resolve:resolve reject:reject];
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(resumeTask:(NSString *)id resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self resumeTaskInternal:id resolve:resolve reject:reject];
}
#endif

- (void)stopTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(identifier, @"[RNBackgroundDownloader] - [stopTask]");
    @try {
        @synchronized (sharedLock) {
            NSURLSessionDownloadTask *task = self->idToTaskMap[identifier];
            if (task != nil) {
                [task cancel];
                [self removeTaskFromMap:task];
            } else {
                // Task not in idToTaskMap, but may have persisted resume data
                // Find and remove from taskToConfigMap using helper method
                RNBGDTaskConfig *config = [self findConfigById:identifier];
                if (config != nil) {
                    config.hasResumeData = NO;
                    NSNumber *key = [self findTaskKeyById:identifier];
                    if (key != nil) {
                        [taskToConfigMap removeObjectForKey:key];
                        [mmkv setData:[self serialize:taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];
                    }
                }
            }
            [self->idToResumeDataMap removeObjectForKey:identifier];
            // Delete resume data file when stopping
            [self deleteResumeDataForTaskId:identifier];
            [idsToPauseSet removeObject:identifier];
        }
        resolve(nil);
    } @catch (NSException *exception) {
        reject(@"ERR_STOP_TASK", exception.reason, nil);
    }
}

- (void)stopTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    [self stopTaskInternal:identifier resolve:resolve reject:reject];
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(stopTask:(NSString *)id resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self stopTaskInternal:id resolve:resolve reject:reject];
}
#endif

- (void)completeHandler:(NSString *)jobId resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(nil, @"[RNBackgroundDownloader] - [completeHandlerIOS]");
    @try {
        [[NSOperationQueue mainQueue] addOperationWithBlock:^{
            if (storedCompletionHandler) {
                storedCompletionHandler();
                storedCompletionHandler = nil;
            }
        }];

        resolve(nil);
    } @catch (NSException *exception) {
        reject(@"ERR_COMPLETE_HANDLER", exception.reason, nil);
    }
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(completeHandler:(nonnull NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self completeHandler:jobId resolve:resolve reject:reject];
}
#endif

- (void)getExistingDownloadTasks:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(nil, @"[RNBackgroundDownloader] - [getExistingDownloadTasks]");
    [self sendDebugLog:@"getExistingDownloadTasks: starting" taskId:nil];
    [self lazyRegisterSession];

    // Defensive check: if session is nil, reject instead of hanging
    if (urlSession == nil) {
        [self sendDebugLog:@"getExistingDownloadTasks: ERROR - urlSession is nil" taskId:nil];
        reject(@"ERR_SESSION_NIL", @"URL session could not be initialized", nil);
        return;
    }

    [self sendDebugLog:@"getExistingDownloadTasks: calling getTasksWithCompletionHandler" taskId:nil];
    [urlSession getTasksWithCompletionHandler:^(NSArray<NSURLSessionDataTask *> * _Nonnull dataTasks, NSArray<NSURLSessionUploadTask *> * _Nonnull uploadTasks, NSArray<NSURLSessionDownloadTask *> * _Nonnull downloadTasks) {
        @synchronized (self->sharedLock) {
            @try {
                NSString *logMsg = [NSString stringWithFormat:@"getExistingDownloadTasks: found %lu download tasks", (unsigned long)downloadTasks.count];
                [self sendDebugLog:logMsg taskId:nil];

                // Wait for session tasks to stabilize after app restart
                [NSThread sleepForTimeInterval:kTaskReconciliationDelay];

                NSMutableArray *foundTasks = [[NSMutableArray alloc] init];
                NSMutableSet *processedIds = [[NSMutableSet alloc] init];

                // Phase 1: Process active download tasks from session
                for (NSURLSessionDownloadTask *task in downloadTasks) {
                    RNBGDTaskConfig *taskConfig = [self findAndReconcileTaskConfig:task];

                    if (taskConfig) {
                        NSURLSessionDownloadTask *mutableTask = task;
                        [self restoreTaskIfNeeded:&mutableTask];
                        [self updateTaskMappings:mutableTask config:taskConfig];
                        [foundTasks addObject:[self createTaskInfo:mutableTask config:taskConfig]];
                        [processedIds addObject:taskConfig.id];
                    } else {
                        [task cancel];
                    }
                }

                // Phase 2: Add paused tasks with resume data (not in active session)
                [self addPausedTasksToResults:foundTasks processedIds:processedIds];

                NSString *resultMsg = [NSString stringWithFormat:@"getExistingDownloadTasks: returning %lu tasks", (unsigned long)foundTasks.count];
                [self sendDebugLog:resultMsg taskId:nil];
                resolve(foundTasks);
            } @catch (NSException *exception) {
                [self sendDebugLog:[NSString stringWithFormat:@"getExistingDownloadTasks: ERROR - %@", exception.reason] taskId:nil];
                reject(@"ERR_GET_EXISTING_TASKS", exception.reason, nil);
            }
        }
    }];
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(getExistingDownloadTasks: (RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self getExistingDownloadTasks:resolve reject:reject];
}
#endif

// RCTEventEmitter lifecycle methods - called when JS adds/removes listeners
- (void)startObserving {
    hasListeners = YES;
}

- (void)stopObserving {
    // Keep hasListeners = YES since downloads may still need to send events
    // and new listeners might be added later
}

// Event emitter methods required by TurboModule spec
- (void)addListener:(NSString *)eventName {
    // For TurboModules - also set hasListeners here
    hasListeners = YES;
}

- (void)removeListeners:(double)count {
    // Note: count represents how many listeners were removed
    // We keep hasListeners = YES since we don't track individual listener counts
    // and downloads may still need to send events
}

- (RNBGDTaskConfig *)findAndReconcileTaskConfig:(NSURLSessionDownloadTask *)task {
    // The task.taskIdentifier may change after app restart, so we use configId from headers
    NSString *configId = task.currentRequest.allHTTPHeaderFields[@"configId"];

    for (NSNumber *key in taskToConfigMap) {
        RNBGDTaskConfig *config = taskToConfigMap[key];
        if ([config.id isEqualToString:configId]) {
            return config;
        }
    }

    return nil;
}

- (void)restoreTaskIfNeeded:(NSURLSessionDownloadTask *__strong *)taskPtr {
    NSURLSessionDownloadTask *task = *taskPtr;

    BOOL isCompletedOrSuspended = (task.state == NSURLSessionTaskStateCompleted ||
                                   task.state == NSURLSessionTaskStateSuspended);
    BOOL needsMoreData = task.countOfBytesReceived < task.countOfBytesExpectedToReceive;

    if (isCompletedOrSuspended && needsMoreData) {
        NSData *resumeData = task.error.userInfo[NSURLSessionDownloadTaskResumeData];

        if (task.error.code == -999 && resumeData) {
            *taskPtr = [urlSession downloadTaskWithResumeData:resumeData];
        } else {
            *taskPtr = [urlSession downloadTaskWithURL:task.currentRequest.URL];
        }
    }
}

- (void)updateTaskMappings:(NSURLSessionDownloadTask *)task config:(RNBGDTaskConfig *)taskConfig {
    NSNumber *percent = task.countOfBytesExpectedToReceive > 0
        ? @((float)task.countOfBytesReceived / (float)task.countOfBytesExpectedToReceive)
        : @0.0;

    taskConfig.reportedBegin = YES;
    taskConfig.bytesDownloaded = task.countOfBytesReceived;
    taskConfig.bytesTotal = task.countOfBytesExpectedToReceive;
    taskConfig.state = task.state;
    taskConfig.errorCode = task.error ? task.error.code : 0;

    taskToConfigMap[@(task.taskIdentifier)] = taskConfig;
    idToTaskMap[taskConfig.id] = task;
    idToPercentMap[taskConfig.id] = percent;
    idToLastBytesMap[taskConfig.id] = @(task.countOfBytesReceived);
}

- (NSDictionary *)createTaskInfo:(NSURLSessionDownloadTask *)task config:(RNBGDTaskConfig *)taskConfig {
    return [self createTaskInfoFromConfig:taskConfig];
}

- (NSDictionary *)createTaskInfoFromConfig:(RNBGDTaskConfig *)taskConfig {
    return @{
        @"id": taskConfig.id,
        @"metadata": taskConfig.metadata,
        @"state": @(taskConfig.state),
        @"bytesDownloaded": @(taskConfig.bytesDownloaded),
        @"bytesTotal": @(taskConfig.bytesTotal),
        @"errorCode": taskConfig.errorCode ? @(taskConfig.errorCode) : [NSNull null]
    };
}

- (void)addPausedTasksToResults:(NSMutableArray *)foundTasks processedIds:(NSMutableSet *)processedIds {
    // First, add tasks with resume data in memory
    for (NSString *taskId in idToResumeDataMap) {
        if ([processedIds containsObject:taskId]) {
            continue;
        }

        RNBGDTaskConfig *taskConfig = [self findConfigById:taskId];
        if (taskConfig) {
            // Override state and errorCode for paused tasks
            taskConfig.state = NSURLSessionTaskStateCanceling;
            if (taskConfig.errorCode == 0) {
                taskConfig.errorCode = -999;
            }

            [foundTasks addObject:[self createTaskInfoFromConfig:taskConfig]];
            [processedIds addObject:taskId];
        }
    }

    // Also check for tasks with persisted resume data in taskToConfigMap (for app restarts)
    for (RNBGDTaskConfig *taskConfig in [taskToConfigMap allValues]) {
        if ([processedIds containsObject:taskConfig.id]) {
            continue;
        }

        // Check if this config has persisted resume data (paused before app restart)
        // hasResumeData flag indicates there's a file on disk
        if (taskConfig.hasResumeData) {
            // Load resume data from file to in-memory map for resumeTask to use
            NSData *resumeData = [self loadResumeDataForTaskId:taskConfig.id];
            if (resumeData != nil) {
                idToResumeDataMap[taskConfig.id] = resumeData;

                // Ensure state and errorCode are set correctly
                if (taskConfig.state != NSURLSessionTaskStateCanceling) {
                    taskConfig.state = NSURLSessionTaskStateCanceling;
                }
                if (taskConfig.errorCode == 0) {
                    taskConfig.errorCode = -999;
                }

                DLog(taskConfig.id, @"[RNBackgroundDownloader] - [addPausedTasksToResults] restored persisted paused task: %@", taskConfig.id);
                [foundTasks addObject:[self createTaskInfoFromConfig:taskConfig]];
                [processedIds addObject:taskConfig.id];
            } else {
                // Resume data file doesn't exist, clear the flag
                taskConfig.hasResumeData = NO;
                [mmkv setData:[self serialize:taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];
                DLog(taskConfig.id, @"[RNBackgroundDownloader] - [addPausedTasksToResults] resume data file not found for task: %@", taskConfig.id);
            }
        }
    }
}

- (RNBGDTaskConfig *)findConfigById:(NSString *)taskId {
    for (RNBGDTaskConfig *config in [taskToConfigMap allValues]) {
        if ([config.id isEqualToString:taskId]) {
            return config;
        }
    }
    return nil;
}

- (NSNumber *)findTaskKeyById:(NSString *)taskId {
    for (NSNumber *key in [taskToConfigMap allKeys]) {
        RNBGDTaskConfig *config = taskToConfigMap[key];
        if ([config.id isEqualToString:taskId]) {
            return key;
        }
    }
    return nil;
}

#pragma mark - Resume data file storage helpers

// Get the directory for storing resume data files
- (NSURL *)resumeDataDirectory {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSURL *cachesDir = [[fileManager URLsForDirectory:NSCachesDirectory inDomains:NSUserDomainMask] firstObject];
    NSURL *resumeDataDir = [cachesDir URLByAppendingPathComponent:@"RNBGDResumeData" isDirectory:YES];

    // Create directory if it doesn't exist
    if (![fileManager fileExistsAtPath:resumeDataDir.path]) {
        [fileManager createDirectoryAtURL:resumeDataDir withIntermediateDirectories:YES attributes:nil error:nil];
    }

    return resumeDataDir;
}

// Get the file URL for a task's resume data
- (NSURL *)resumeDataFileURLForTaskId:(NSString *)taskId {
    return [[self resumeDataDirectory] URLByAppendingPathComponent:[NSString stringWithFormat:@"%@.resumedata", taskId]];
}

// Save resume data to file
- (BOOL)saveResumeData:(NSData *)resumeData forTaskId:(NSString *)taskId {
    if (resumeData == nil || taskId == nil) {
        return NO;
    }

    NSURL *fileURL = [self resumeDataFileURLForTaskId:taskId];
    NSError *error = nil;
    BOOL success = [resumeData writeToURL:fileURL options:NSDataWritingAtomic error:&error];

    if (!success) {
        DLog(taskId, @"[RNBackgroundDownloader] - [saveResumeData] failed to save resume data: %@", error.localizedDescription);
    } else {
        DLog(taskId, @"[RNBackgroundDownloader] - [saveResumeData] saved resume data to file (%lu bytes)", (unsigned long)resumeData.length);
    }

    return success;
}

// Load resume data from file
- (NSData *)loadResumeDataForTaskId:(NSString *)taskId {
    if (taskId == nil) {
        return nil;
    }

    NSURL *fileURL = [self resumeDataFileURLForTaskId:taskId];
    NSFileManager *fileManager = [NSFileManager defaultManager];

    if (![fileManager fileExistsAtPath:fileURL.path]) {
        DLog(taskId, @"[RNBackgroundDownloader] - [loadResumeData] no resume data file found");
        return nil;
    }

    NSError *error = nil;
    NSData *resumeData = [NSData dataWithContentsOfURL:fileURL options:0 error:&error];

    if (error) {
        DLog(taskId, @"[RNBackgroundDownloader] - [loadResumeData] failed to load resume data: %@", error.localizedDescription);
        return nil;
    }

    DLog(taskId, @"[RNBackgroundDownloader] - [loadResumeData] loaded resume data from file (%lu bytes)", (unsigned long)resumeData.length);
    return resumeData;
}

// Delete resume data file
- (void)deleteResumeDataForTaskId:(NSString *)taskId {
    if (taskId == nil) {
        return;
    }

    NSURL *fileURL = [self resumeDataFileURLForTaskId:taskId];
    NSFileManager *fileManager = [NSFileManager defaultManager];

    if ([fileManager fileExistsAtPath:fileURL.path]) {
        NSError *error = nil;
        [fileManager removeItemAtURL:fileURL error:&error];
        if (error) {
            DLog(taskId, @"[RNBackgroundDownloader] - [deleteResumeData] failed to delete resume data: %@", error.localizedDescription);
        } else {
            DLog(taskId, @"[RNBackgroundDownloader] - [deleteResumeData] deleted resume data file");
        }
    }
}

#pragma mark - NSURLSessionDownloadDelegate methods
- (void)URLSession:(nonnull NSURLSession *)session downloadTask:(nonnull NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(nonnull NSURL *)location {
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskConfig = [self configForTask:downloadTask];
        if (!taskConfig) {
            [self sendDebugLog:@"didFinishDownloadingToURL: no taskConfig found" taskId:nil];
            return;
        }

        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didFinishDownloadingToURL]");
        [self sendDebugLog:@"didFinishDownloadingToURL: download finished" taskId:taskConfig.id];

        NSError *error = [self getServerError:downloadTask];
        if (!error) {
            [self saveFile:taskConfig downloadURL:location error:&error];
        }

        if (error) {
            [self sendDebugLog:[NSString stringWithFormat:@"didFinishDownloadingToURL: error - %@", error.localizedDescription] taskId:taskConfig.id];
        }

        [self sendDownloadCompletionEvent:taskConfig task:downloadTask error:error];

        [self removeTaskFromMap:downloadTask];
    }
}

- (void)sendDownloadCompletionEvent:(RNBGDTaskConfig *)taskConfig task:(NSURLSessionDownloadTask *)task error:(NSError *)error {
    if (error) {
#ifdef RCT_NEW_ARCH_ENABLED
        [self emitOnDownloadFailed:@{
            @"id": taskConfig.id,
            @"error": [error localizedDescription],
            @"errorCode": @(error.code)
        }];
#else
        [self sendEventWithName:@"downloadFailed" body:@{
            @"id": taskConfig.id,
            @"error": [error localizedDescription],
            @"errorCode": @(error.code)
        }];
#endif
    } else {
#ifdef RCT_NEW_ARCH_ENABLED
        [self emitOnDownloadComplete:@{
            @"id": taskConfig.id,
            @"location": taskConfig.destination,
            @"bytesDownloaded": @(task.countOfBytesReceived),
            @"bytesTotal": @(task.countOfBytesExpectedToReceive)
        }];
#else
        [self sendEventWithName:@"downloadComplete" body:@{
            @"id": taskConfig.id,
            @"location": taskConfig.destination,
            @"bytesDownloaded": @(task.countOfBytesReceived),
            @"bytesTotal": @(task.countOfBytesExpectedToReceive)
        }];
#endif
    }
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didResumeAtOffset:(int64_t)fileOffset expectedbytesTotal:(int64_t)expectedbytesTotal {
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskConfig = [self configForTask:downloadTask];
        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didResumeAtOffset]");
    }
}

- (void)URLSession:(NSURLSession *)session
    downloadTask:(NSURLSessionDownloadTask *)downloadTask
    didWriteData:(int64_t)bytesDownloaded
    totalBytesWritten:(int64_t)bytesTotalWritten
    totalBytesExpectedToWrite:(int64_t)bytesTotalExpectedToWrite
{
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskConfig = [self configForTask:downloadTask];
        if (!taskConfig) {
            return;
        }

        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didWriteData]");

        [self reportBeginIfNeeded:taskConfig downloadTask:downloadTask expectedBytes:bytesTotalExpectedToWrite];
        [self updateProgressIfNeeded:taskConfig bytesWritten:bytesTotalWritten bytesTotal:bytesTotalExpectedToWrite];
        [self flushProgressReportsIfNeeded];
    }
}

- (void)reportBeginIfNeeded:(RNBGDTaskConfig *)taskConfig downloadTask:(NSURLSessionDownloadTask *)task expectedBytes:(int64_t)expectedBytes {
    if (taskConfig.reportedBegin) {
        return;
    }

    // Safely extract response headers - may be nil if response is not an HTTP response
    NSDictionary *responseHeaders = nil;
    if ([task.response isKindOfClass:[NSHTTPURLResponse class]]) {
        responseHeaders = ((NSHTTPURLResponse *)task.response).allHeaderFields;
    }
    // Use empty dictionary if headers are nil to prevent NSDictionary nil insertion crash
    if (responseHeaders == nil) {
        responseHeaders = @{};
    }
#ifdef RCT_NEW_ARCH_ENABLED
    [self emitOnDownloadBegin:@{
        @"id": taskConfig.id,
        @"expectedBytes": @(expectedBytes),
        @"headers": responseHeaders
    }];
#else
    [self sendEventWithName:@"downloadBegin" body:@{
        @"id": taskConfig.id,
        @"expectedBytes": @(expectedBytes),
        @"headers": responseHeaders
    }];
#endif
    taskConfig.reportedBegin = YES;
}

- (void)updateProgressIfNeeded:(RNBGDTaskConfig *)taskConfig bytesWritten:(int64_t)bytesWritten bytesTotal:(int64_t)bytesTotal {
    NSNumber *prevPercent = idToPercentMap[taskConfig.id] ?: @0.0;
    NSNumber *prevBytes = idToLastBytesMap[taskConfig.id] ?: @0;
    float currentPercent = bytesTotal > 0 ? (float)bytesWritten / (float)bytesTotal : 0.0;

    // Check if we should report progress based on percentage OR bytes threshold
    BOOL percentThresholdMet = currentPercent - [prevPercent floatValue] > kProgressReportThreshold;
    // Only check bytes threshold if progressMinBytes > 0
    BOOL bytesThresholdMet = progressMinBytes > 0 && (bytesWritten - [prevBytes longLongValue] >= progressMinBytes);

    // Report progress if either threshold is met, or if total bytes unknown (for realtime streams)
    if (percentThresholdMet || bytesThresholdMet || bytesTotal <= 0) {
        progressReports[taskConfig.id] = @{
            @"id": taskConfig.id,
            @"bytesDownloaded": @(bytesWritten),
            @"bytesTotal": @(bytesTotal)
        };
        idToPercentMap[taskConfig.id] = @(currentPercent);
        idToLastBytesMap[taskConfig.id] = @(bytesWritten);
        taskConfig.bytesDownloaded = bytesWritten;
        taskConfig.bytesTotal = bytesTotal;
    }
}

- (void)flushProgressReportsIfNeeded {
    if (progressReports.count == 0) {
        return;
    }

    NSDate *now = [NSDate date];
    if ([now timeIntervalSinceDate:lastProgressReportedAt] > progressInterval) {
#ifdef RCT_NEW_ARCH_ENABLED
        [self emitOnDownloadProgress:[progressReports allValues]];
#else
        [self sendEventWithName:@"downloadProgress" body:[progressReports allValues]];
#endif
        lastProgressReportedAt = now;
        [progressReports removeAllObjects];
    }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    @synchronized (sharedLock) {
        // Check if this is an upload task first
        RNBGDUploadTaskConfig *uploadTaskConfig = [self uploadConfigForTask:task];
        if (uploadTaskConfig) {
            [self handleUploadCompletion:task error:error];
            return;
        }

        RNBGDTaskConfig *taskConfig = [self configForTask:task];

        if (!error || !taskConfig) {
            if (taskConfig) {
                [self sendDebugLog:@"didCompleteWithError: completed without error" taskId:taskConfig.id];
            }
            return;
        }

        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didCompleteWithError] error: %@", error);
        [self sendDebugLog:[NSString stringWithFormat:@"didCompleteWithError: error code=%ld, %@", (long)error.code, error.localizedDescription] taskId:taskConfig.id];

        // NSURLErrorCancelled (-999) is used for paused or cancelled tasks
        // Extract resume data first before checking isPausedTask
        NSData *resumeData = error.userInfo[NSURLSessionDownloadTaskResumeData];

        // Check if this task was intentionally paused (marked in idsToPauseSet)
        BOOL wasIntentionallyPaused = [idsToPauseSet containsObject:taskConfig.id];
        BOOL hasResumeData = resumeData != nil;
        BOOL isPausedTask = (error.code == NSURLErrorCancelled && (wasIntentionallyPaused || hasResumeData));

        if (isPausedTask) {
            taskConfig.errorCode = error.code;

            // Store resume data so we can resume the download later
            if (taskConfig.id && resumeData) {
                idToResumeDataMap[taskConfig.id] = resumeData;
            }
            DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didCompleteWithError] task was paused, ignoring error for %@", taskConfig.id);
            return;
        }

        // Not a pause - remove from pause set if it was there (cleanup on failure)
        [idsToPauseSet removeObject:taskConfig.id];

        // Fallback: certain servers return -1015 (NSURLErrorCannotDecodeRawData) on resumed tasks.
        // Instead of failing permanently, attempt ONE fresh retry without resume data.
        if (error.code == NSURLErrorCannotDecodeRawData && ![decodeErrorRetriedIds containsObject:taskConfig.id]) {
            [decodeErrorRetriedIds addObject:taskConfig.id];
            DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didCompleteWithError] attempting fresh retry after decode error");

            // Build a fresh request replicating original headers
            NSMutableURLRequest *request = [[NSMutableURLRequest alloc] initWithURL:[NSURL URLWithString:taskConfig.url]];
            // Reapply original request headers if available (including our internal identifier header)
            NSDictionary *originalHeaders = task.originalRequest.allHTTPHeaderFields;
            if (originalHeaders != nil) {
                for (NSString *headerKey in originalHeaders) {
                    [request setValue:[originalHeaders valueForKey:headerKey] forHTTPHeaderField:headerKey];
                }
            }
            [request setValue:taskConfig.id forHTTPHeaderField:@"configId"]; // ensure config id header

            // Remove old mapping keyed by previous task identifier
            [taskToConfigMap removeObjectForKey:@(task.taskIdentifier)];

            NSURLSessionDownloadTask *newTask = [urlSession downloadTaskWithRequest:request];
            if (newTask != nil) {
                // Reset task state for fresh attempt
                taskConfig.state = NSURLSessionTaskStateRunning;
                taskConfig.errorCode = 0;
                taskConfig.bytesDownloaded = 0;
                taskConfig.bytesTotal = 0;
                taskToConfigMap[@(newTask.taskIdentifier)] = taskConfig;
                [mmkv setData:[self serialize: taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];
                idToTaskMap[taskConfig.id] = newTask;
                idToPercentMap[taskConfig.id] = @0.0;
                idToLastBytesMap[taskConfig.id] = @0;
                [newTask resume];
                DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didCompleteWithError] fresh retry started");
                return; // Do not emit failure yet
            } else {
                DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didCompleteWithError] fresh retry creation failed");
            }
        }

        // Handle failure
#ifdef RCT_NEW_ARCH_ENABLED
        [self emitOnDownloadFailed:@{
            @"id": taskConfig.id,
            @"error": [error localizedDescription],
            @"errorCode": @(error.code)
        }];
#else
        [self sendEventWithName:@"downloadFailed" body:@{
            @"id": taskConfig.id,
            @"error": [error localizedDescription],
            @"errorCode": @(error.code)
        }];
#endif
        [self removeTaskFromMap:task];
    }
}

- (void)URLSessionDidFinishEventsForBackgroundURLSession:(NSURLSession *)session {
    DLog(nil, @"[RNBackgroundDownloader] - [URLSessionDidFinishEventsForBackgroundURLSession]");
}

#pragma mark - Upload methods

- (void)removeUploadTaskFromMap:(NSURLSessionTask *)task {
    @synchronized (sharedLock) {
        NSNumber *taskId = @(task.taskIdentifier);
        RNBGDUploadTaskConfig *taskConfig = uploadTaskToConfigMap[taskId];
        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [removeUploadTaskFromMap]");

        [uploadTaskToConfigMap removeObjectForKey:taskId];
        [mmkv setData:[self serializeUploadConfig:uploadTaskToConfigMap] forKey:ID_TO_UPLOAD_CONFIG_MAP_KEY];

        if (taskConfig) {
            [idToUploadTaskMap removeObjectForKey:taskConfig.id];
            [idToUploadPercentMap removeObjectForKey:taskConfig.id];
            [idToUploadLastBytesMap removeObjectForKey:taskConfig.id];
        }
    }
}

#ifdef RCT_NEW_ARCH_ENABLED
- (void)upload:(JS::NativeRNBackgroundDownloader::SpecUploadOptions &)options {
    NSString *identifier = options.id_();
    DLog(identifier, @"[RNBackgroundDownloader] - [upload]");
    NSString *url = options.url();
    NSString *source = options.source();
    NSString *method = options.method() ? options.method() : @"POST";
    NSString *metadata = options.metadata() ? options.metadata() : @"";
    NSDictionary *headers = options.headers() ? (NSDictionary *)options.headers() : nil;
    NSString *fieldName = options.fieldName() ? options.fieldName() : @"file";
    NSString *mimeType = options.mimeType() ? options.mimeType() : nil;
    NSDictionary *parameters = options.parameters() ? (NSDictionary *)options.parameters() : nil;

    if (options.progressInterval().has_value()) {
        progressInterval = options.progressInterval().value() / 1000.0;
        [mmkv setFloat:progressInterval forKey:PROGRESS_INTERVAL_KEY];
    }

    if (options.progressMinBytes().has_value()) {
        progressMinBytes = (int64_t)options.progressMinBytes().value();
        [mmkv setInt64:progressMinBytes forKey:PROGRESS_MIN_BYTES_KEY];
    }
#else
RCT_EXPORT_METHOD(upload:(NSDictionary *)options) {
    NSString *identifier = options[@"id"];
    DLog(identifier, @"[RNBackgroundDownloader] - [upload]");
    NSString *url = options[@"url"];
    NSString *source = options[@"source"];
    NSString *method = options[@"method"] ?: @"POST";
    NSString *metadata = options[@"metadata"] ?: @"";
    NSDictionary *headers = options[@"headers"];
    NSString *fieldName = options[@"fieldName"] ?: @"file";
    NSString *mimeType = options[@"mimeType"];
    NSDictionary *parameters = options[@"parameters"];

    NSNumber *progressIntervalScope = options[@"progressInterval"];
    if (progressIntervalScope) {
        progressInterval = [progressIntervalScope intValue] / 1000.0;
        [mmkv setFloat:progressInterval forKey:PROGRESS_INTERVAL_KEY];
    }

    NSNumber *progressMinBytesScope = options[@"progressMinBytes"];
    if (progressMinBytesScope) {
        progressMinBytes = [progressMinBytesScope longLongValue];
        [mmkv setInt64:progressMinBytes forKey:PROGRESS_MIN_BYTES_KEY];
    }
#endif

    DLog(identifier, @"[RNBackgroundDownloader] - [upload] url %@ source %@ method %@", url, source, method);
    if (identifier == nil || url == nil || source == nil) {
        DLog(identifier, @"[RNBackgroundDownloader] - [Error] id, url and source must be set");
        return;
    }

    @synchronized (sharedLock) {
        [self sendDebugLog:@"upload: calling lazyRegisterSession" taskId:identifier];
        [self lazyRegisterSession];

        // Get file info
        NSURL *fileURL = [NSURL fileURLWithPath:source];
        NSError *fileError;
        NSDictionary *fileAttrs = [[NSFileManager defaultManager] attributesOfItemAtPath:source error:&fileError];
        if (fileError) {
            DLog(identifier, @"[RNBackgroundDownloader] - [Error] Could not read file: %@", fileError.localizedDescription);
            return;
        }
        unsigned long long fileSize = [fileAttrs fileSize];

        // Create request
        NSMutableURLRequest *request = [[NSMutableURLRequest alloc] initWithURL:[NSURL URLWithString:url]];
        request.HTTPMethod = method;
        [request setValue:identifier forHTTPHeaderField:@"uploadConfigId"];

        // Add custom headers
        if (headers != nil) {
            for (NSString *headerKey in headers) {
                [request setValue:[headers valueForKey:headerKey] forHTTPHeaderField:headerKey];
            }
        }

        // Determine if we need multipart
        BOOL useMultipart = (parameters != nil && parameters.count > 0) || fieldName != nil;

        RNBGDUploadTaskConfig *taskConfig = [[RNBGDUploadTaskConfig alloc] initWithDictionary:@{
            @"id": identifier,
            @"url": url,
            @"source": source,
            @"method": method,
            @"metadata": metadata,
            @"fieldName": fieldName ?: [NSNull null],
            @"mimeType": mimeType ?: [NSNull null],
            @"parameters": parameters ?: [NSNull null]
        }];
        taskConfig.bytesTotal = fileSize;

        NSURLSessionUploadTask *uploadTask;

        if (useMultipart) {
            // Create multipart form data
            NSString *boundary = [[NSUUID UUID] UUIDString];
            [request setValue:[NSString stringWithFormat:@"multipart/form-data; boundary=%@", boundary] forHTTPHeaderField:@"Content-Type"];

            // Build multipart body
            NSMutableData *body = [NSMutableData data];

            // Add parameters
            if (parameters != nil) {
                for (NSString *key in parameters) {
                    [body appendData:[[NSString stringWithFormat:@"--%@\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];
                    [body appendData:[[NSString stringWithFormat:@"Content-Disposition: form-data; name=\"%@\"\r\n\r\n", key] dataUsingEncoding:NSUTF8StringEncoding]];
                    [body appendData:[[NSString stringWithFormat:@"%@\r\n", parameters[key]] dataUsingEncoding:NSUTF8StringEncoding]];
                }
            }

            // Add file
            NSString *filename = [source lastPathComponent];
            NSString *contentType = mimeType ?: @"application/octet-stream";

            [body appendData:[[NSString stringWithFormat:@"--%@\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];
            [body appendData:[[NSString stringWithFormat:@"Content-Disposition: form-data; name=\"%@\"; filename=\"%@\"\r\n", fieldName, filename] dataUsingEncoding:NSUTF8StringEncoding]];
            [body appendData:[[NSString stringWithFormat:@"Content-Type: %@\r\n\r\n", contentType] dataUsingEncoding:NSUTF8StringEncoding]];

            NSData *fileData = [NSData dataWithContentsOfFile:source];
            [body appendData:fileData];
            [body appendData:[@"\r\n" dataUsingEncoding:NSUTF8StringEncoding]];

            // End boundary
            [body appendData:[[NSString stringWithFormat:@"--%@--\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];

            // Write body to temp file for background upload
            NSString *tempPath = [NSTemporaryDirectory() stringByAppendingPathComponent:[[NSUUID UUID] UUIDString]];
            [body writeToFile:tempPath atomically:YES];
            NSURL *tempFileURL = [NSURL fileURLWithPath:tempPath];

            taskConfig.bytesTotal = body.length;
            uploadTask = [urlSession uploadTaskWithRequest:request fromFile:tempFileURL];
        } else {
            // Simple file upload
            if (mimeType) {
                [request setValue:mimeType forHTTPHeaderField:@"Content-Type"];
            }
            uploadTask = [urlSession uploadTaskWithRequest:request fromFile:fileURL];
        }

        if (uploadTask == nil) {
            DLog(identifier, @"[RNBackgroundDownloader] - [Error] failed to create upload task");
            return;
        }

        uploadTaskToConfigMap[@(uploadTask.taskIdentifier)] = taskConfig;
        [mmkv setData:[self serializeUploadConfig:uploadTaskToConfigMap] forKey:ID_TO_UPLOAD_CONFIG_MAP_KEY];

        idToUploadTaskMap[identifier] = uploadTask;
        idToUploadPercentMap[identifier] = @0.0;
        idToUploadLastBytesMap[identifier] = @0;

        [uploadTask resume];
        lastUploadProgressReportedAt = [[NSDate alloc] init];
    }
}

- (void)pauseUploadTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(identifier, @"[RNBackgroundDownloader] - [pauseUploadTask]");
    @try {
        @synchronized (sharedLock) {
            NSURLSessionUploadTask *task = idToUploadTaskMap[identifier];
            if (task != nil) {
                [idsToUploadPauseSet addObject:identifier];
                [task suspend];
                DLog(identifier, @"[RNBackgroundDownloader] - [pauseUploadTask] suspended upload task: %@", identifier);
            }
        }
        resolve(nil);
    } @catch (NSException *exception) {
        reject(@"ERR_PAUSE_UPLOAD_TASK", exception.reason, nil);
    }
}

- (void)pauseUploadTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    [self pauseUploadTaskInternal:identifier resolve:resolve reject:reject];
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(pauseUploadTask:(NSString *)id resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self pauseUploadTaskInternal:id resolve:resolve reject:reject];
}
#endif

- (void)resumeUploadTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(identifier, @"[RNBackgroundDownloader] - [resumeUploadTask]");
    @try {
        @synchronized (sharedLock) {
            [self lazyRegisterSession];
            [idsToUploadPauseSet removeObject:identifier];

            NSURLSessionUploadTask *task = idToUploadTaskMap[identifier];
            if (task != nil && task.state == NSURLSessionTaskStateSuspended) {
                [task resume];
                DLog(identifier, @"[RNBackgroundDownloader] - [resumeUploadTask] resumed upload task: %@", identifier);
            } else {
                DLog(identifier, @"[RNBackgroundDownloader] - [resumeUploadTask] no suspended upload task found for %@", identifier);
            }
        }
        resolve(nil);
    } @catch (NSException *exception) {
        reject(@"ERR_RESUME_UPLOAD_TASK", exception.reason, nil);
    }
}

- (void)resumeUploadTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    [self resumeUploadTaskInternal:identifier resolve:resolve reject:reject];
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(resumeUploadTask:(NSString *)id resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self resumeUploadTaskInternal:id resolve:resolve reject:reject];
}
#endif

- (void)stopUploadTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(identifier, @"[RNBackgroundDownloader] - [stopUploadTask]");
    @try {
        @synchronized (sharedLock) {
            NSURLSessionUploadTask *task = idToUploadTaskMap[identifier];
            if (task != nil) {
                [task cancel];
                [self removeUploadTaskFromMap:task];
            }
            [idsToUploadPauseSet removeObject:identifier];
        }
        resolve(nil);
    } @catch (NSException *exception) {
        reject(@"ERR_STOP_UPLOAD_TASK", exception.reason, nil);
    }
}

- (void)stopUploadTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    [self stopUploadTaskInternal:identifier resolve:resolve reject:reject];
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(stopUploadTask:(NSString *)id resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self stopUploadTaskInternal:id resolve:resolve reject:reject];
}
#endif

- (void)getExistingUploadTasks:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
    DLog(nil, @"[RNBackgroundDownloader] - [getExistingUploadTasks]");
    [self lazyRegisterSession];

    if (urlSession == nil) {
        reject(@"ERR_SESSION_NIL", @"URL session could not be initialized", nil);
        return;
    }

    [urlSession getTasksWithCompletionHandler:^(NSArray<NSURLSessionDataTask *> * _Nonnull dataTasks, NSArray<NSURLSessionUploadTask *> * _Nonnull uploadTasks, NSArray<NSURLSessionDownloadTask *> * _Nonnull downloadTasks) {
        @synchronized (self->sharedLock) {
            @try {
                NSMutableArray *foundTasks = [[NSMutableArray alloc] init];
                NSMutableSet *processedIds = [[NSMutableSet alloc] init];

                // Phase 1: Process active upload tasks from session
                for (NSURLSessionUploadTask *task in uploadTasks) {
                    RNBGDUploadTaskConfig *taskConfig = [self uploadConfigForTask:task];

                    if (taskConfig) {
                        taskConfig.state = task.state;
                        taskConfig.bytesUploaded = task.countOfBytesSent;
                        taskConfig.bytesTotal = task.countOfBytesExpectedToSend;

                        NSDictionary *taskInfo = @{
                            @"id": taskConfig.id,
                            @"metadata": taskConfig.metadata,
                            @"state": @(taskConfig.state),
                            @"bytesUploaded": @(taskConfig.bytesUploaded),
                            @"bytesTotal": @(taskConfig.bytesTotal),
                            @"errorCode": taskConfig.errorCode ? @(taskConfig.errorCode) : [NSNull null]
                        };
                        [foundTasks addObject:taskInfo];
                        [processedIds addObject:taskConfig.id];
                        self->idToUploadTaskMap[taskConfig.id] = task;
                    } else {
                        [task cancel];
                    }
                }

                // Phase 2: Add persisted upload configs that are not active (for app restart recovery)
                // Uploads cannot truly resume like downloads, but we preserve their metadata
                // so the user can restart them
                for (RNBGDUploadTaskConfig *taskConfig in [self->uploadTaskToConfigMap allValues]) {
                    if ([processedIds containsObject:taskConfig.id]) {
                        continue;
                    }

                    // Mark as suspended since the upload task doesn't exist in session anymore
                    taskConfig.state = NSURLSessionTaskStateSuspended;

                    NSDictionary *taskInfo = @{
                        @"id": taskConfig.id,
                        @"metadata": taskConfig.metadata,
                        @"state": @(taskConfig.state),
                        @"bytesUploaded": @(taskConfig.bytesUploaded),
                        @"bytesTotal": @(taskConfig.bytesTotal),
                        @"errorCode": taskConfig.errorCode ? @(taskConfig.errorCode) : [NSNull null]
                    };
                    [foundTasks addObject:taskInfo];
                    [processedIds addObject:taskConfig.id];
                    DLog(taskConfig.id, @"[RNBackgroundDownloader] - [getExistingUploadTasks] restored persisted upload task: %@", taskConfig.id);
                }

                resolve(foundTasks);
            } @catch (NSException *exception) {
                reject(@"ERR_GET_EXISTING_UPLOAD_TASKS", exception.reason, nil);
            }
        }
    }];
}

#ifndef RCT_NEW_ARCH_ENABLED
RCT_EXPORT_METHOD(getExistingUploadTasks:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    [self getExistingUploadTasks:resolve reject:reject];
}
#endif

#pragma mark - NSURLSessionTaskDelegate for uploads

// Progress tracking for uploads
- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didSendBodyData:(int64_t)bytesSent totalBytesSent:(int64_t)totalBytesSent totalBytesExpectedToSend:(int64_t)totalBytesExpectedToSend {
    @synchronized (sharedLock) {
        RNBGDUploadTaskConfig *taskConfig = [self uploadConfigForTask:task];
        if (!taskConfig) {
            return;
        }

        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didSendBodyData] %lld/%lld", totalBytesSent, totalBytesExpectedToSend);

        // Report begin if needed
        if (!taskConfig.reportedBegin) {
            taskConfig.reportedBegin = YES;
#ifdef RCT_NEW_ARCH_ENABLED
            [self emitOnUploadBegin:@{
                @"id": taskConfig.id,
                @"expectedBytes": @(totalBytesExpectedToSend)
            }];
#else
            [self sendEventWithName:@"uploadBegin" body:@{
                @"id": taskConfig.id,
                @"expectedBytes": @(totalBytesExpectedToSend)
            }];
#endif
        }

        // Update progress
        NSNumber *prevPercent = idToUploadPercentMap[taskConfig.id] ?: @0.0;
        NSNumber *prevBytes = idToUploadLastBytesMap[taskConfig.id] ?: @0;
        float currentPercent = totalBytesExpectedToSend > 0 ? (float)totalBytesSent / (float)totalBytesExpectedToSend : 0.0;

        BOOL percentThresholdMet = currentPercent - [prevPercent floatValue] > kProgressReportThreshold;
        BOOL bytesThresholdMet = progressMinBytes > 0 && (totalBytesSent - [prevBytes longLongValue] >= progressMinBytes);

        if (percentThresholdMet || bytesThresholdMet || totalBytesExpectedToSend <= 0) {
            uploadProgressReports[taskConfig.id] = @{
                @"id": taskConfig.id,
                @"bytesUploaded": @(totalBytesSent),
                @"bytesTotal": @(totalBytesExpectedToSend)
            };
            idToUploadPercentMap[taskConfig.id] = @(currentPercent);
            idToUploadLastBytesMap[taskConfig.id] = @(totalBytesSent);
            taskConfig.bytesUploaded = totalBytesSent;
            taskConfig.bytesTotal = totalBytesExpectedToSend;
        }

        // Flush progress reports if needed
        if (uploadProgressReports.count > 0) {
            NSDate *now = [NSDate date];
            if ([now timeIntervalSinceDate:lastUploadProgressReportedAt] > progressInterval) {
#ifdef RCT_NEW_ARCH_ENABLED
                [self emitOnUploadProgress:[uploadProgressReports allValues]];
#else
                [self sendEventWithName:@"uploadProgress" body:[uploadProgressReports allValues]];
#endif
                lastUploadProgressReportedAt = now;
                [uploadProgressReports removeAllObjects];
            }
        }
    }
}

#pragma mark - NSURLSessionDataDelegate for upload response

// Handle response data for uploads
- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask didReceiveData:(NSData *)data {
    @synchronized (sharedLock) {
        // Upload tasks are also data tasks when receiving response
        RNBGDUploadTaskConfig *taskConfig = [self uploadConfigForTask:dataTask];
        if (!taskConfig) {
            return;
        }

        if (taskConfig.responseData == nil) {
            taskConfig.responseData = [[NSMutableData alloc] init];
        }
        [taskConfig.responseData appendData:data];
    }
}

// Handle upload completion (override the existing method to also handle uploads)
- (void)handleUploadCompletion:(NSURLSessionTask *)task error:(NSError *)error {
    @synchronized (sharedLock) {
        RNBGDUploadTaskConfig *taskConfig = [self uploadConfigForTask:task];
        if (!taskConfig) {
            return;
        }

        if (error) {
            // Check if intentionally paused
            if (error.code == NSURLErrorCancelled && [idsToUploadPauseSet containsObject:taskConfig.id]) {
                DLog(taskConfig.id, @"[RNBackgroundDownloader] - upload was paused, ignoring error");
                return;
            }

            DLog(taskConfig.id, @"[RNBackgroundDownloader] - [handleUploadCompletion] error: %@", error);
#ifdef RCT_NEW_ARCH_ENABLED
            [self emitOnUploadFailed:@{
                @"id": taskConfig.id,
                @"error": [error localizedDescription],
                @"errorCode": @(error.code)
            }];
#else
            [self sendEventWithName:@"uploadFailed" body:@{
                @"id": taskConfig.id,
                @"error": [error localizedDescription],
                @"errorCode": @(error.code)
            }];
#endif
        } else {
            // Upload succeeded
            NSInteger responseCode = 0;
            if ([task.response isKindOfClass:[NSHTTPURLResponse class]]) {
                responseCode = ((NSHTTPURLResponse *)task.response).statusCode;
            }

            NSString *responseBody = @"";
            if (taskConfig.responseData) {
                responseBody = [[NSString alloc] initWithData:taskConfig.responseData encoding:NSUTF8StringEncoding] ?: @"";
            }

            DLog(taskConfig.id, @"[RNBackgroundDownloader] - [handleUploadCompletion] success, responseCode: %ld", (long)responseCode);
#ifdef RCT_NEW_ARCH_ENABLED
            [self emitOnUploadComplete:@{
                @"id": taskConfig.id,
                @"responseCode": @(responseCode),
                @"responseBody": responseBody,
                @"bytesUploaded": @(task.countOfBytesSent),
                @"bytesTotal": @(task.countOfBytesExpectedToSend)
            }];
#else
            [self sendEventWithName:@"uploadComplete" body:@{
                @"id": taskConfig.id,
                @"responseCode": @(responseCode),
                @"responseBody": responseBody,
                @"bytesUploaded": @(task.countOfBytesSent),
                @"bytesTotal": @(task.countOfBytesExpectedToSend)
            }];
#endif
        }

        [self removeUploadTaskFromMap:task];
    }
}

+ (void)setCompletionHandlerWithIdentifier:(NSString *)identifier completionHandler: (CompletionHandler)completionHandler {
  DLogStatic(nil, @"[RNBackgroundDownloader] - [setCompletionHandlerWithIdentifier]");
  NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
  NSString *sessionIdentifier =
      [bundleIdentifier stringByAppendingString:@".backgrounddownloadtask"];
  if ([sessionIdentifier isEqualToString:identifier]) {
    storedCompletionHandler = completionHandler;

    // Set a timeout to prevent memory leak if JS never calls completeHandler
    // iOS requires the completion handler to be called within 30 seconds
    // Copy the handler to a local variable to avoid retain cycle with static variable
    __block CompletionHandler handlerToCall = completionHandler;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(kCompletionHandlerTimeout * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
      // Check if this is still the same handler (not already called or replaced)
      if (storedCompletionHandler && storedCompletionHandler == handlerToCall) {
        DLogStatic(nil, @"[RNBackgroundDownloader] - [setCompletionHandlerWithIdentifier] timeout - calling completion handler automatically");
        storedCompletionHandler();
        storedCompletionHandler = nil;
      }
      handlerToCall = nil;  // Release the block reference
    });
  }
}

- (NSError *)getServerError:(NSURLSessionDownloadTask *)downloadTask {
    // Safely check if response is an HTTP response
    if (![downloadTask.response isKindOfClass:[NSHTTPURLResponse class]]) {
        return nil; // No HTTP response, can't determine server error
    }

    NSInteger statusCode = ((NSHTTPURLResponse *)downloadTask.response).statusCode;

    // 200: OK, 206: Partial Content (for resumed downloads)
    if (statusCode == 200 || statusCode == 206) {
        return nil;
    }

    return [NSError errorWithDomain:NSURLErrorDomain
                               code:statusCode
                           userInfo:@{NSLocalizedDescriptionKey: [NSHTTPURLResponse localizedStringForStatusCode:statusCode]}];
}

- (BOOL)saveFile: (nonnull RNBGDTaskConfig *) taskConfig downloadURL:(nonnull NSURL *)location error:(NSError **)saveError {
    DLog(taskConfig.id, @"[RNBackgroundDownloader] - [saveFile]");
    // taskConfig.destination is absolute path.
    // The absolute path may change when the application is restarted.
    // But the relative path remains the same.
    // Relative paths are used to recreate the Absolute path.
    NSString *rootPath = [self getRootPathFromPath:taskConfig.destination];
    NSString *fileRelativePath = [self getRelativeFilePathFromPath:taskConfig.destination];

    // Check for nil paths to prevent crash
    if (rootPath == nil || fileRelativePath == nil) {
        if (saveError) {
            *saveError = [NSError errorWithDomain:NSURLErrorDomain
                                             code:NSURLErrorFileDoesNotExist
                                         userInfo:@{NSLocalizedDescriptionKey: @"Invalid destination path"}];
        }
        return NO;
    }

    NSString *fileAbsolutePath = [rootPath stringByAppendingPathComponent:fileRelativePath];
    NSURL *destinationURL = [NSURL fileURLWithPath:fileAbsolutePath];

    NSFileManager *fileManager = [NSFileManager defaultManager];
    [fileManager createDirectoryAtURL:[destinationURL URLByDeletingLastPathComponent] withIntermediateDirectories:YES attributes:nil error:nil];
    [fileManager removeItemAtURL:destinationURL error:nil];

    return [fileManager moveItemAtURL:location toURL:destinationURL error:saveError];
}

#pragma mark - serialization
- (NSData *)serialize:(NSMutableDictionary<NSNumber *, RNBGDTaskConfig *> *)taskMap {
    NSError *error = nil;
    NSData *taskMapRaw = [NSKeyedArchiver archivedDataWithRootObject:taskMap requiringSecureCoding:YES error:&error];

    if (error) {
        DLog(nil, @"[RNBackgroundDownloader] Serialization error: %@", error);
        return nil;
    }

    return taskMapRaw;
}

- (NSMutableDictionary<NSNumber *, RNBGDTaskConfig *> *)deserialize:(NSData *)taskMapRaw {
    NSError *error = nil;
    // Creates a list of classes that can be stored.
    NSSet *classes = [NSSet setWithObjects:[RNBGDTaskConfig class], [NSMutableDictionary class], [NSNumber class], [NSString class], [NSData class], nil];
    NSMutableDictionary<NSNumber *, RNBGDTaskConfig *> *taskMap = [NSKeyedUnarchiver unarchivedObjectOfClasses:classes fromData:taskMapRaw error:&error];

    if (error) {
        DLog(nil, @"[RNBackgroundDownloader] Deserialization error: %@", error);
        return nil;
    }

    return taskMap;
}

#pragma mark - Upload serialization

- (NSData *)serializeUploadConfig:(NSMutableDictionary<NSNumber *, RNBGDUploadTaskConfig *> *)taskMap {
    NSError *error = nil;
    NSData *taskMapRaw = [NSKeyedArchiver archivedDataWithRootObject:taskMap requiringSecureCoding:YES error:&error];

    if (error) {
        DLog(nil, @"[RNBackgroundDownloader] Upload serialization error: %@", error);
        return nil;
    }

    return taskMapRaw;
}

- (NSMutableDictionary<NSNumber *, RNBGDUploadTaskConfig *> *)deserializeUploadConfig:(NSData *)taskMapRaw {
    NSError *error = nil;
    // Creates a list of classes that can be stored.
    NSSet *classes = [NSSet setWithObjects:[RNBGDUploadTaskConfig class], [NSMutableDictionary class], [NSNumber class], [NSString class], [NSDictionary class], nil];
    NSMutableDictionary<NSNumber *, RNBGDUploadTaskConfig *> *taskMap = [NSKeyedUnarchiver unarchivedObjectOfClasses:classes fromData:taskMapRaw error:&error];

    if (error) {
        DLog(nil, @"[RNBackgroundDownloader] Upload deserialization error: %@", error);
        return nil;
    }

    return taskMap;
}

- (NSString *)getRootPathFromPath:(NSString *)path {
    // [...]/data/Containers/Bundle/Application/[UUID]
    NSString *bundlePath = [[[NSBundle mainBundle] bundlePath] stringByDeletingLastPathComponent];
    NSString *bundlePathWithoutUuid = [self getPathWithoutSuffixUuid:bundlePath];
    // [...]/data/Containers/Data/Application/[UUID]
    NSString *dataPath = [[NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject] stringByDeletingLastPathComponent];
    NSString *dataPathWithoutUuid = [self getPathWithoutSuffixUuid:dataPath];

    if ([path hasPrefix:bundlePathWithoutUuid]) {
        return bundlePath;
    }

    if ([path hasPrefix:dataPathWithoutUuid]) {
        return dataPath;
    }

    return nil;
}

- (NSString *)getRelativeFilePathFromPath:(NSString *)path {
    // [...]/data/Containers/Bundle/Application/[UUID]
    NSString *bundlePath = [[[NSBundle mainBundle] bundlePath] stringByDeletingLastPathComponent];
    NSString *bundlePathWithoutUuid = [self getPathWithoutSuffixUuid:bundlePath];

    // [...]/data/Containers/Data/Application/[UUID]
    NSString *dataPath = [[NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject] stringByDeletingLastPathComponent];
    NSString *dataPathWithoutUuid = [self getPathWithoutSuffixUuid:dataPath];

    if ([path hasPrefix:bundlePathWithoutUuid]) {
        return [path substringFromIndex:[bundlePath length]];
    }

    if ([path hasPrefix:dataPathWithoutUuid]) {
        return [path substringFromIndex:[dataPath length]];
    }

    return nil;
}

- (NSString *)getPathWithoutSuffixUuid:(NSString *)path {
    NSString *pattern = @"^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$";
    NSRegularExpression *regex = [NSRegularExpression regularExpressionWithPattern:pattern options:NSRegularExpressionCaseInsensitive error:nil];

    NSString *pathSuffix = [path lastPathComponent];
    NSTextCheckingResult *pathSuffixIsUuid = [regex firstMatchInString:pathSuffix options:0 range:NSMakeRange(0, [pathSuffix length])];
    if (pathSuffixIsUuid) {
        return [path stringByDeletingLastPathComponent];
    }

    return path;
}

#ifdef RCT_NEW_ARCH_ENABLED
- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeRNBackgroundDownloaderSpecJSI>(params);
}
#endif

@end

#import "RNBackgroundDownloader.h"
#import "RNBGDTaskConfig.h"
#import <MMKV/MMKV.h>

#define ID_TO_CONFIG_MAP_KEY @"com.eko.bgdownloadidmap"
#define PROGRESS_INTERVAL_KEY @"progressInterval"

// DISABLES LOGS IN RELEASE MODE. NSLOG IS SLOW: https://stackoverflow.com/a/17738695/3452513
// DLog accepts taskId as first parameter to help debugging
#ifdef DEBUG
#define DLog( taskId, s, ... ) NSLog( @"<%p %@:(%d)> %@ %@", self, [[NSString stringWithUTF8String:__FILE__] lastPathComponent], __LINE__, [NSString stringWithFormat:(s), ##__VA_ARGS__], (taskId ? [NSString stringWithFormat:@"taskId:%@", taskId] : @"taskId:NULL") )
#else
#define DLog( taskId, s, ... )
#endif

static CompletionHandler storedCompletionHandler;

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
    NSMutableSet<NSString *> *idsToPauseSet;
    float progressInterval;
    NSDate *lastProgressReportedAt;
    BOOL isBridgeListenerInited;
    BOOL isJavascriptLoaded;
}

RCT_EXPORT_MODULE();

- (dispatch_queue_t)methodQueue
{
    return dispatch_queue_create("com.eko.backgrounddownloader", DISPATCH_QUEUE_SERIAL);
}

+ (BOOL)requiresMainQueueSetup {
    return YES;
}

- (NSArray<NSString *> *)supportedEvents {
    return @[
        @"downloadBegin",
        @"downloadProgress",
        @"downloadComplete",
        @"downloadFailed"
    ];
}

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

- (id)init {
    DLog(nil, @"[RNBackgroundDownloader] - [init]");
    self = [super init];
    if (self) {
        [MMKV initializeMMKV:nil];
        mmkv = [MMKV mmkvWithID:@"RNBackgroundDownloader"];

        NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
        NSString *sessionIdentifier = [bundleIdentifier stringByAppendingString:@".backgrounddownloadtask"];
        sessionConfig = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:sessionIdentifier];
        sessionConfig.HTTPMaximumConnectionsPerHost = 4;
        sessionConfig.timeoutIntervalForRequest = 60 * 60; // MAX TIME TO GET NEW DATA IN REQUEST - 1 HOUR
        sessionConfig.timeoutIntervalForResource = 60 * 60 * 24; // MAX TIME TO DOWNLOAD RESOURCE - 1 DAY
        sessionConfig.discretionary = NO;
        sessionConfig.sessionSendsLaunchEvents = YES;
        if (@available(iOS 9.0, *)) {
            sessionConfig.shouldUseExtendedBackgroundIdleMode = YES;
        }
        if (@available(iOS 13.0, *)) {
            sessionConfig.allowsExpensiveNetworkAccess = YES;
        }

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
        float progressIntervalScope = [mmkv getFloatForKey:PROGRESS_INTERVAL_KEY];
        progressInterval = isnan(progressIntervalScope) ? 1.0 : progressIntervalScope;
        lastProgressReportedAt = [[NSDate alloc] init];

        [self registerBridgeListener];
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
    @synchronized (sharedLock) {
        if (urlSession == nil) {
            urlSession = [NSURLSession sessionWithConfiguration:sessionConfig delegate:self delegateQueue:nil];
        }
    }
}

- (void)unregisterSession {
    DLog(nil, @"[RNBackgroundDownloader] - [unregisterSession]");
    if (urlSession) {
        [urlSession invalidateAndCancel];
        urlSession = nil;
    }
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

            [[NSNotificationCenter defaultCenter] addObserver:self
                                                  selector:@selector(handleBridgeJavascriptLoad:)
                                                  name:RCTJavaScriptDidLoadNotification
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

- (void)handleBridgeJavascriptLoad:(NSNotification *) note {
    DLog(nil, @"[RNBackgroundDownloader] - [handleBridgeJavascriptLoad]");
    isJavascriptLoaded = YES;
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
        }
    }
}

#pragma mark - JS exported methods
RCT_EXPORT_METHOD(download: (NSDictionary *) options) {
    NSString *identifier = options[@"id"];
    DLog(identifier, @"[RNBackgroundDownloader] - [download]");
    NSString *url = options[@"url"];
    NSString *destination = options[@"destination"];
    NSString *metadata = options[@"metadata"];
    NSDictionary *headers = options[@"headers"];

    NSNumber *progressIntervalScope = options[@"progressInterval"];
    if (progressIntervalScope) {
        progressInterval = [progressIntervalScope intValue] / 1000;
        [mmkv setFloat:progressInterval forKey:PROGRESS_INTERVAL_KEY];
    }

    NSString *destinationRelative = [self getRelativeFilePathFromPath:destination];

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
        [self lazyRegisterSession];

        NSURLSessionDownloadTask __strong *task = [urlSession downloadTaskWithRequest:request];
        if (task == nil) {
            DLog(identifier, @"[RNBackgroundDownloader] - [Error] failed to create download task");
            return;
        }

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

        [task resume];
        lastProgressReportedAt = [[NSDate alloc] init];
    }
}

RCT_EXPORT_METHOD(pauseTask: (NSString *)identifier) {
    DLog(identifier, @"[RNBackgroundDownloader] - [pauseTask]");
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
                    } else {
                        DLog(identifier, @"[RNBackgroundDownloader] - [pauseTask] no resume data available for %@", identifier);
                    }
                    // Keep the identifier in idsToPauseSet until resume or stop is called
                }
            }];
        }
    }
}

RCT_EXPORT_METHOD(resumeTask: (NSString *)identifier) {
    DLog(identifier, @"[RNBackgroundDownloader] - [resumeTask]");
    @synchronized (sharedLock) {
        [self lazyRegisterSession];

        // Remove from pause set when resuming
        [idsToPauseSet removeObject:identifier];

        NSData *resumeData = self->idToResumeDataMap[identifier];
        NSURLSessionDownloadTask *task = self->idToTaskMap[identifier];

        if (resumeData != nil) {
            // Task was paused with resume data, create new task from resume data
            DLog(identifier, @"[RNBackgroundDownloader] - [resumeTask] resuming with resume data for %@", identifier);

            NSURLSessionDownloadTask *newTask = [urlSession downloadTaskWithResumeData:resumeData];
            if (newTask != nil) {
                // Get the task config from the old task
                RNBGDTaskConfig *taskConfig = nil;
                if (task != nil) {
                    taskConfig = taskToConfigMap[@(task.taskIdentifier)];
                    [taskToConfigMap removeObjectForKey:@(task.taskIdentifier)];
                }

                // Update mappings with new task
                if (taskConfig != nil) {
                    taskConfig.state = NSURLSessionTaskStateRunning;
                    taskConfig.errorCode = 0;
                    taskToConfigMap[@(newTask.taskIdentifier)] = taskConfig;
                    [mmkv setData:[self serialize: taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];
                }

                self->idToTaskMap[identifier] = newTask;
                [self->idToResumeDataMap removeObjectForKey:identifier];
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
}

RCT_EXPORT_METHOD(stopTask: (NSString *)identifier) {
    DLog(identifier, @"[RNBackgroundDownloader] - [stopTask]");
    @synchronized (sharedLock) {
        NSURLSessionDownloadTask *task = self->idToTaskMap[identifier];
        if (task != nil) {
            [task cancel];
            [self removeTaskFromMap:task];
        }
        [self->idToResumeDataMap removeObjectForKey:identifier];
        [idsToPauseSet removeObject:identifier];
    }
}

RCT_EXPORT_METHOD(completeHandler:(nonnull NSString *)jobId resolver:(RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    DLog(nil, @"[RNBackgroundDownloader] - [completeHandlerIOS]");
    [[NSOperationQueue mainQueue] addOperationWithBlock:^{
        if (storedCompletionHandler) {
            storedCompletionHandler();
            storedCompletionHandler = nil;
        }
    }];

    resolve(nil);
}

RCT_EXPORT_METHOD(getExistingDownloadTasks: (RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    DLog(nil, @"[RNBackgroundDownloader] - [getExistingDownloadTasks]");
    [self lazyRegisterSession];
    [urlSession getTasksWithCompletionHandler:^(NSArray<NSURLSessionDataTask *> * _Nonnull dataTasks, NSArray<NSURLSessionUploadTask *> * _Nonnull uploadTasks, NSArray<NSURLSessionDownloadTask *> * _Nonnull downloadTasks) {
        @synchronized (self->sharedLock) {
            // Wait for the Thread to be ready to prevent first task from failing
            [NSThread sleepForTimeInterval:0.1f];

            NSMutableArray *foundTasks = [[NSMutableArray alloc] init];
            NSMutableSet *processedIds = [[NSMutableSet alloc] init];

            // Phase 1: Process active download tasks from session
            for (NSURLSessionDownloadTask *task in downloadTasks) {
                RNBGDTaskConfig *taskConfig = [self findAndReconcileTaskConfig:task];

                if (taskConfig) {
                    [self restoreTaskIfNeeded:&task];
                    [self updateTaskMappings:task config:taskConfig];
                    [foundTasks addObject:[self createTaskInfo:task config:taskConfig]];
                    [processedIds addObject:taskConfig.id];
                } else {
                    [task cancel];
                }
            }

            // Phase 2: Add paused tasks with resume data (not in active session)
            [self addPausedTasksToResults:foundTasks processedIds:processedIds];

            resolve(foundTasks);
        }
    }];
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
}

- (RNBGDTaskConfig *)findConfigById:(NSString *)taskId {
    for (RNBGDTaskConfig *config in [taskToConfigMap allValues]) {
        if ([config.id isEqualToString:taskId]) {
            return config;
        }
    }
    return nil;
}

#pragma mark - NSURLSessionDownloadDelegate methods
- (void)URLSession:(nonnull NSURLSession *)session downloadTask:(nonnull NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(nonnull NSURL *)location {
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskConfig = taskToConfigMap[@(downloadTask.taskIdentifier)];
        if (!taskConfig) {
            return;
        }

        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didFinishDownloadingToURL]");

        NSError *error = [self getServerError:downloadTask];
        if (!error) {
            [self saveFile:taskConfig downloadURL:location error:&error];
        }

        if (self.bridge && isJavascriptLoaded) {
            [self sendDownloadCompletionEvent:taskConfig task:downloadTask error:error];
        }

        [self removeTaskFromMap:downloadTask];
    }
}

- (void)sendDownloadCompletionEvent:(RNBGDTaskConfig *)taskConfig task:(NSURLSessionDownloadTask *)task error:(NSError *)error {
    if (error) {
        [self sendEventWithName:@"downloadFailed" body:@{
            @"id": taskConfig.id,
            @"error": [error localizedDescription],
            @"errorCode": @(error.code)
        }];
    } else {
        NSDictionary *responseHeaders = ((NSHTTPURLResponse *)task.response).allHeaderFields;
        [self sendEventWithName:@"downloadComplete" body:@{
            @"id": taskConfig.id,
            @"headers": responseHeaders,
            @"location": taskConfig.destination,
            @"bytesDownloaded": @(task.countOfBytesReceived),
            @"bytesTotal": @(task.countOfBytesExpectedToReceive)
        }];
    }
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didResumeAtOffset:(int64_t)fileOffset expectedbytesTotal:(int64_t)expectedbytesTotal {
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskConfig = taskToConfigMap[@(downloadTask.taskIdentifier)];
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
        RNBGDTaskConfig *taskConfig = taskToConfigMap[@(downloadTask.taskIdentifier)];
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

    if (self.bridge && isJavascriptLoaded) {
        NSDictionary *responseHeaders = ((NSHTTPURLResponse *)task.response).allHeaderFields;
        [self sendEventWithName:@"downloadBegin" body:@{
            @"id": taskConfig.id,
            @"expectedBytes": @(expectedBytes),
            @"headers": responseHeaders
        }];
    }
    taskConfig.reportedBegin = YES;
}

- (void)updateProgressIfNeeded:(RNBGDTaskConfig *)taskConfig bytesWritten:(int64_t)bytesWritten bytesTotal:(int64_t)bytesTotal {
    NSNumber *prevPercent = idToPercentMap[taskConfig.id] ?: @0.0;
    float currentPercent = bytesTotal > 0 ? (float)bytesWritten / (float)bytesTotal : 0.0;

    if (currentPercent - [prevPercent floatValue] > 0.01f) {
        progressReports[taskConfig.id] = @{
            @"id": taskConfig.id,
            @"bytesDownloaded": @(bytesWritten),
            @"bytesTotal": @(bytesTotal)
        };
        idToPercentMap[taskConfig.id] = @(currentPercent);
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
        if (self.bridge && isJavascriptLoaded) {
            [self sendEventWithName:@"downloadProgress" body:[progressReports allValues]];
        }
        lastProgressReportedAt = now;
        [progressReports removeAllObjects];
    }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskConfig = taskToConfigMap[@(task.taskIdentifier)];

        if (!error || !taskConfig) {
            return;
        }

        DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didCompleteWithError] error: %@", error);

        // NSURLErrorCancelled (-999) is used for paused or cancelled tasks
        NSData *resumeData = task.error.userInfo[NSURLSessionDownloadTaskResumeData];
        BOOL isPausedTask = (error.code == NSURLErrorCancelled && resumeData != nil);

        if (isPausedTask) {
            taskConfig.errorCode = error.code;
            DLog(taskConfig.id, @"[RNBackgroundDownloader] - [didCompleteWithError] task was paused, ignoring error for %@", taskConfig.id);
            return;
        }

        // Handle failure
        if (self.bridge && isJavascriptLoaded) {
            [self sendEventWithName:@"downloadFailed" body:@{
                @"id": taskConfig.id,
                @"error": [error localizedDescription],
                @"errorCode": @(error.code)
            }];
        }
        [self removeTaskFromMap:task];
    }
}

- (void)URLSessionDidFinishEventsForBackgroundURLSession:(NSURLSession *)session {
    DLog(nil, @"[RNBackgroundDownloader] - [URLSessionDidFinishEventsForBackgroundURLSession]");
}

+ (void)setCompletionHandlerWithIdentifier:(NSString *)identifier completionHandler: (CompletionHandler)completionHandler {
  DLog(nil, @"[RNBackgroundDownloader] - [setCompletionHandlerWithIdentifier]");
  NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
  NSString *sessionIdentifier =
      [bundleIdentifier stringByAppendingString:@".backgrounddownloadtask"];
  if ([sessionIdentifier isEqualToString:identifier]) {
    storedCompletionHandler = completionHandler;
  }
}

- (NSError *)getServerError:(NSURLSessionDownloadTask *)downloadTask {
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
    NSSet *classes = [NSSet setWithObjects:[RNBGDTaskConfig class], [NSMutableDictionary class], [NSNumber class], [NSString class], nil];
    NSMutableDictionary<NSNumber *, RNBGDTaskConfig *> *taskMap = [NSKeyedUnarchiver unarchivedObjectOfClasses:classes fromData:taskMapRaw error:&error];

    if (error) {
        DLog(nil, @"[RNBackgroundDownloader] Deserialization error: %@", error);
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

@end

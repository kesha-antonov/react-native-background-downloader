#import "RNBackgroundDownloader.h"
#import "RNBGDTaskConfig.h"
#import <MMKV/MMKV.h>

#define ID_TO_CONFIG_MAP_KEY @"com.eko.bgdownloadidmap"
#define PROGRESS_INTERVAL_KEY @"progressInterval"

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
        @"TaskRunning": @(NSURLSessionTaskStateRunning),
        @"TaskSuspended": @(NSURLSessionTaskStateSuspended),
        @"TaskCanceling": @(NSURLSessionTaskStateCanceling),
        @"TaskCompleted": @(NSURLSessionTaskStateCompleted)
    };
}

- (id)init {
    NSLog(@"[RNBackgroundDownloader] - [init]");
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
        taskToConfigMap = [self deserialize:taskToConfigMapData] ?: [[NSMutableDictionary alloc] init];
        self->idToTaskMap = [[NSMutableDictionary alloc] init];
        idToResumeDataMap = [[NSMutableDictionary alloc] init];
        idToPercentMap = [[NSMutableDictionary alloc] init];

        progressReports = [[NSMutableDictionary alloc] init];
        float progressIntervalScope = [mmkv getFloatForKey:PROGRESS_INTERVAL_KEY];
        progressInterval = isnan(progressIntervalScope) ? 1.0 : progressIntervalScope;
        lastProgressReportedAt = [[NSDate alloc] init];

        [self registerSession];
        [self registerBridgeListener];
    }
    return self;
}

- (void)dealloc {
    NSLog(@"[RNBackgroundDownloader] - [dealloc]");
    [self unregisterSession];
    [self unregisterBridgeListener];
}

- (void)handleBridgeHotReload:(NSNotification *) note {
    NSLog(@"[RNBackgroundDownloader] - [handleBridgeHotReload]");
    [self unregisterSession];
    [self unregisterBridgeListener];
}

- (void)registerSession {
    NSLog(@"[RNBackgroundDownloader] - [registerSession]");
    @synchronized (sharedLock) {
        if (urlSession == nil) {
            urlSession = [NSURLSession sessionWithConfiguration:sessionConfig delegate:self delegateQueue:nil];
        }
    }
}

- (void)unregisterSession {
    NSLog(@"[RNBackgroundDownloader] - [unregisterSession]");
    if (urlSession) {
        [urlSession invalidateAndCancel];
        urlSession = nil;
    }
}

- (void)registerBridgeListener {
    NSLog(@"[RNBackgroundDownloader] - [registerBridgeListener]");
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
    NSLog(@"[RNBackgroundDownloader] - [unregisterBridgeListener]");
    if (isBridgeListenerInited == YES) {
        [[NSNotificationCenter defaultCenter] removeObserver:self];
        isBridgeListenerInited = NO;
    }
}

- (void)handleBridgeJavascriptLoad:(NSNotification *) note {
    NSLog(@"[RNBackgroundDownloader] - [handleBridgeJavascriptLoad]");
    isJavascriptLoaded = YES;
}

- (void)handleBridgeAppEnterForeground:(NSNotification *) note {
    NSLog(@"[RNBackgroundDownloader] - [handleBridgeAppEnterForeground]");
    [self resumeTasks];
}

- (void)resumeTasks {
    @synchronized (sharedLock) {
        NSLog(@"[RNBackgroundDownloader] - [resumeTasks]");
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
    NSLog(@"[RNBackgroundDownloader] - [removeTaskFromMap]");
    @synchronized (sharedLock) {
        NSNumber *taskId = @(task.taskIdentifier);
        RNBGDTaskConfig *taskConfig = taskToConfigMap[taskId];

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
    NSLog(@"[RNBackgroundDownloader] - [download] - 1");
    NSString *identifier = options[@"id"];
    NSString *url = options[@"url"];
    NSString *destination = options[@"destination"];
    NSString *metadata = options[@"metadata"];
    NSDictionary *headers = options[@"headers"];

    NSNumber *progressIntervalScope = options[@"progressInterval"];
    if (progressIntervalScope) {
        progressInterval = [progressIntervalScope intValue] / 1000;
        [mmkv setFloat:progressInterval forKey:PROGRESS_INTERVAL_KEY];
    }

    NSLog(@"[RNBackgroundDownloader] - [download] - 1 url %@ destination %@ progressInterval %f", url, destination, progressInterval);
    if (identifier == nil || url == nil || destination == nil) {
        NSLog(@"[RNBackgroundDownloader] - [Error] id, url and destination must be set");
        return;
    }

    NSMutableURLRequest *request = [[NSMutableURLRequest alloc] initWithURL:[NSURL URLWithString:url]];
    if (headers != nil) {
        for (NSString *headerKey in headers) {
            [request setValue:[headers valueForKey:headerKey] forHTTPHeaderField:headerKey];
        }
    }

    @synchronized (sharedLock) {
        NSURLSessionDownloadTask __strong *task = [urlSession downloadTaskWithRequest:request];
        if (task == nil) {
            NSLog(@"[RNBackgroundDownloader] - [Error] failed to create download task");
            return;
        }

        RNBGDTaskConfig *taskConfig = [[RNBGDTaskConfig alloc] initWithDictionary: @{@"id": identifier, @"destination": destination, @"metadata": metadata}];

        taskToConfigMap[@(task.taskIdentifier)] = taskConfig;
        [mmkv setData:[self serialize: taskToConfigMap] forKey:ID_TO_CONFIG_MAP_KEY];

        self->idToTaskMap[identifier] = task;
        idToPercentMap[identifier] = @0.0;

        [task resume];
        lastProgressReportedAt = [[NSDate alloc] init];
    }
}

RCT_EXPORT_METHOD(pauseTask: (NSString *)identifier) {
    NSLog(@"[RNBackgroundDownloader] - [pauseTask]");
    @synchronized (sharedLock) {
        NSURLSessionDownloadTask *task = self->idToTaskMap[identifier];
        if (task != nil && task.state == NSURLSessionTaskStateRunning) {
            [task suspend];
        }
    }
}

RCT_EXPORT_METHOD(resumeTask: (NSString *)identifier) {
    NSLog(@"[RNBackgroundDownloader] - [resumeTask]");
    @synchronized (sharedLock) {
        NSURLSessionDownloadTask *task = self->idToTaskMap[identifier];
        if (task != nil && task.state == NSURLSessionTaskStateSuspended) {
            [task resume];
        }
    }
}

RCT_EXPORT_METHOD(stopTask: (NSString *)identifier) {
    NSLog(@"[RNBackgroundDownloader] - [stopTask]");
    @synchronized (sharedLock) {
        NSURLSessionDownloadTask *task = self->idToTaskMap[identifier];
        if (task != nil) {
            [task cancel];
            [self removeTaskFromMap:task];
        }
    }
}

RCT_EXPORT_METHOD(completeHandler:(nonnull NSString *)jobId
                  resolver:(RCTPromiseResolveBlock)resolve
                  rejecter:(RCTPromiseRejectBlock)reject)
{
    NSLog(@"[RNBackgroundDownloader] - [completeHandlerIOS]");
    [[NSOperationQueue mainQueue] addOperationWithBlock:^{
        if (storedCompletionHandler) {
            storedCompletionHandler();
            storedCompletionHandler = nil;
        }
    }];
    resolve(nil);
}

RCT_EXPORT_METHOD(checkForExistingDownloads: (RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    NSLog(@"[RNBackgroundDownloader] - [checkForExistingDownloads]");
    [urlSession getTasksWithCompletionHandler:^(NSArray<NSURLSessionDataTask *> * _Nonnull dataTasks, NSArray<NSURLSessionUploadTask *> * _Nonnull uploadTasks, NSArray<NSURLSessionDownloadTask *> * _Nonnull downloadTasks) {
        NSMutableArray *idsFound = [[NSMutableArray alloc] init];
        @synchronized (self->sharedLock) {
            for (NSURLSessionDownloadTask *foundTask in downloadTasks) {
                NSURLSessionDownloadTask __strong *task = foundTask;
                RNBGDTaskConfig *taskConfig = self->taskToConfigMap[@(task.taskIdentifier)];
                if (taskConfig) {
                    if ((task.state == NSURLSessionTaskStateCompleted || task.state == NSURLSessionTaskStateSuspended) && task.countOfBytesReceived < task.countOfBytesExpectedToReceive) {
                        if (task.error && task.error.userInfo[NSURLSessionDownloadTaskResumeData] != nil) {
                            task = [self->urlSession downloadTaskWithResumeData:task.error.userInfo[NSURLSessionDownloadTaskResumeData]];
                        } else {
                            task = [self->urlSession downloadTaskWithURL:task.currentRequest.URL];
                        }
                        [task resume];
                    }

                    [idsFound addObject:@{
                        @"id": taskConfig.id,
                        @"metadata": taskConfig.metadata,
                        @"state": [NSNumber numberWithInt: task.state],
                        @"bytesDownloaded": [NSNumber numberWithLongLong:task.countOfBytesReceived],
                        @"bytesTotal": [NSNumber numberWithLongLong:task.countOfBytesExpectedToReceive]
                    }];
                    taskConfig.reportedBegin = YES;
                    self->taskToConfigMap[@(task.taskIdentifier)] = taskConfig;
                    self->idToTaskMap[taskConfig.id] = task;

                    NSNumber *percent = task.countOfBytesExpectedToReceive > 0 ? [NSNumber numberWithFloat:(float)task.countOfBytesReceived/(float)task.countOfBytesExpectedToReceive] : @0.0;
                    self->idToPercentMap[taskConfig.id] = percent;
                } else {
                    [task cancel];
                }
            }
            resolve(idsFound);
        }
    }];
}

#pragma mark - NSURLSessionDownloadDelegate methods
- (void)URLSession:(nonnull NSURLSession *)session downloadTask:(nonnull NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(nonnull NSURL *)location {
    NSLog(@"[RNBackgroundDownloader] - [didFinishDownloadingToURL]");
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskConfig = taskToConfigMap[@(downloadTask.taskIdentifier)];
        if (taskConfig != nil) {
            NSError *error = [self getServerError:downloadTask];
            if (error == nil) {
                [self saveFile:taskConfig downloadURL:location error:&error];
            }
            if (self.bridge && isJavascriptLoaded) {
                if (error == nil) {
                    NSDictionary *responseHeaders = ((NSHTTPURLResponse *)downloadTask.response).allHeaderFields;
                    [self sendEventWithName:@"downloadComplete" body:@{
                        @"id": taskConfig.id,
                        @"headers": responseHeaders,
                        @"location": taskConfig.destination,
                        @"bytesDownloaded": [NSNumber numberWithLongLong:downloadTask.countOfBytesReceived],
                        @"bytesTotal": [NSNumber numberWithLongLong:downloadTask.countOfBytesExpectedToReceive]
                    }];
                } else {
                    [self sendEventWithName:@"downloadFailed" body:@{
                        @"id": taskConfig.id,
                        @"error": [error localizedDescription],
                        // TODO
                        @"errorCode": @-1
                    }];
                }
            }
            [self removeTaskFromMap:downloadTask];
        }
    }
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didResumeAtOffset:(int64_t)fileOffset expectedbytesTotal:(int64_t)expectedbytesTotal {
    NSLog(@"[RNBackgroundDownloader] - [didResumeAtOffset]");
}

- (void)URLSession:(NSURLSession *)session
    downloadTask:(NSURLSessionDownloadTask *)downloadTask
    didWriteData:(int64_t)bytesDownloaded
    totalBytesWritten:(int64_t)bytesTotalWritten
    totalBytesExpectedToWrite:(int64_t)bytesTotalExpectedToWrite
{
    NSLog(@"[RNBackgroundDownloader] - [didWriteData]");
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskCofig = taskToConfigMap[@(downloadTask.taskIdentifier)];
        if (taskCofig != nil) {
            // NSLog(@"[RNBackgroundDownloader] - [didWriteData] destination - %@", taskCofig.destination);
            if (!taskCofig.reportedBegin) {
                NSDictionary *responseHeaders = ((NSHTTPURLResponse *)downloadTask.response).allHeaderFields;
                if (self.bridge && isJavascriptLoaded) {
                    [self sendEventWithName:@"downloadBegin" body:@{
                        @"id": taskCofig.id,
                        @"expectedBytes": [NSNumber numberWithLongLong: bytesTotalExpectedToWrite],
                        @"headers": responseHeaders
                    }];
                }
                taskCofig.reportedBegin = YES;
            }

            NSNumber *prevPercent = idToPercentMap[taskCofig.id];
            NSNumber *percent = [NSNumber numberWithFloat:(float)bytesTotalWritten/(float)bytesTotalExpectedToWrite];
            if ([percent floatValue] - [prevPercent floatValue] > 0.01f) {
                progressReports[taskCofig.id] = @{
                    @"id": taskCofig.id,
                    @"bytesDownloaded": [NSNumber numberWithLongLong: bytesTotalWritten],
                    @"bytesTotal": [NSNumber numberWithLongLong: bytesTotalExpectedToWrite]
                };
                idToPercentMap[taskCofig.id] = percent;
            }


            NSDate *now = [[NSDate alloc] init];
            if ([now timeIntervalSinceDate:lastProgressReportedAt] > progressInterval && progressReports.count > 0) {
                if (self.bridge && isJavascriptLoaded) {
                    [self sendEventWithName:@"downloadProgress" body:[progressReports allValues]];
                }
                lastProgressReportedAt = now;
                [progressReports removeAllObjects];
            }
        }
    }
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    NSLog(@"[RNBackgroundDownloader] - [didCompleteWithError]");
    @synchronized (sharedLock) {
        if (error == nil)
            return;

        RNBGDTaskConfig *taskCofig = taskToConfigMap[@(task.taskIdentifier)];
        if (taskCofig == nil)
            return;

        if (self.bridge && isJavascriptLoaded) {
            [self sendEventWithName:@"downloadFailed" body:@{
                @"id": taskCofig.id,
                @"error": [error localizedDescription],
                // TODO
                @"errorCode": @-1
            }];
        }
        if (error.userInfo[NSURLSessionDownloadTaskResumeData] == nil) {
            [self removeTaskFromMap:task];
        }
    }
}

- (void)URLSessionDidFinishEventsForBackgroundURLSession:(NSURLSession *)session {
    NSLog(@"[RNBackgroundDownloader] - [URLSessionDidFinishEventsForBackgroundURLSession]");
}

+ (void)setCompletionHandlerWithIdentifier: (NSString *)identifier completionHandler: (CompletionHandler)completionHandler {
    NSLog(@"[RNBackgroundDownloader] - [setCompletionHandlerWithIdentifier]");
    NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
    NSString *sessionIdentifier = [bundleIdentifier stringByAppendingString:@".backgrounddownloadtask"];
    if ([sessionIdentifier isEqualToString:identifier]) {
        storedCompletionHandler = completionHandler;
    }
}

- (NSError *)getServerError: (nonnull NSURLSessionDownloadTask *)downloadTask {
  NSLog(@"[RNBackgroundDownloader] - [getServerError]");
  NSError *serverError;
  NSInteger httpStatusCode = [((NSHTTPURLResponse *)downloadTask.response) statusCode];
  if(httpStatusCode != 200) {
      serverError = [NSError errorWithDomain:NSURLErrorDomain
                                        code:httpStatusCode
                                    userInfo:@{NSLocalizedDescriptionKey: [NSHTTPURLResponse localizedStringForStatusCode: httpStatusCode]}];
  }
  return serverError;
}

- (BOOL)saveFile: (nonnull RNBGDTaskConfig *) taskConfig downloadURL:(nonnull NSURL *)location error:(NSError **)saveError {
  NSLog(@"[RNBackgroundDownloader] - [saveFile]");
  NSFileManager *fileManager = [NSFileManager defaultManager];
  NSURL *destURL = [NSURL fileURLWithPath:taskConfig.destination];
  [fileManager createDirectoryAtURL:[destURL URLByDeletingLastPathComponent] withIntermediateDirectories:YES attributes:nil error:nil];
  [fileManager removeItemAtURL:destURL error:nil];

  return [fileManager moveItemAtURL:location toURL:destURL error:saveError];
}

#pragma mark - serialization
- (NSData *)serialize: (id)obj {
    NSError *error;
    NSData *data = [NSKeyedArchiver archivedDataWithRootObject:obj requiringSecureCoding:NO error:&error];

    if (error) {
        NSLog(@"[RNBackgroundDownloader] Serialization error: %@", error);
    }

    return data;
}

- (id)deserialize: (NSData *)data {
    NSError *error;
    id obj = [NSKeyedUnarchiver unarchivedObjectOfClass:[NSObject class] fromData:data error:&error];

    if (error) {
        NSLog(@"[RNBackgroundDownloader] Deserialization error: %@", error);
    }

    return obj;
}

@end

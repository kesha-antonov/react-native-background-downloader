//
//  RNFileBackgroundDownload.m
//  EkoApp
//
//  Created by Elad Gil on 20/11/2017.
//  Copyright © 2017 Eko. All rights reserved.
//
//
#import "RNBackgroundDownloader.h"
#import "RNBGDTaskConfig.h"
#import <MMKV/MMKV.h>

#define ID_TO_CONFIG_MAP_KEY @"com.eko.bgdownloadidmap"
#define CONFIG_MAP_KEY @"com.eko.config_map"

static CompletionHandler storedCompletionHandler;

@implementation RNBackgroundDownloader {
    NSURLSession *urlSession;
    NSURLSessionConfiguration *sessionConfig;
    NSMutableDictionary<NSNumber *, RNBGDTaskConfig *> *taskToConfigMap;
    NSMutableDictionary<NSString *, NSURLSessionDownloadTask *> *idToTaskMap;
    NSMutableDictionary<NSString *, NSData *> *idToResumeDataMap;
    NSMutableDictionary<NSString *, NSNumber *> *idToPercentMap;
    NSMutableDictionary<NSString *, NSDictionary *> *progressReports;
    NSDate *lastProgressReportedAt;
    NSNumber *sharedLock;
    float progressInterval; // IN SECONDS
    BOOL isNotificationCenterInited;

    MMKV *mmkv;
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

- (id) init {
    NSLog(@"[RNBackgroundDownloader] - [init]");
    self = [super init];
    if (self) {
        [MMKV initializeMMKV:nil];

        mmkv = [MMKV defaultMMKV];

        taskToConfigMap = [self deserialize:[mmkv getDataForKey:ID_TO_CONFIG_MAP_KEY]];
        if (taskToConfigMap == nil) {
            taskToConfigMap = [[NSMutableDictionary alloc] init];
        }

        NSDictionary *configMap = [self deserialize:[mmkv getDataForKey:CONFIG_MAP_KEY]];
        if (configMap != nil) {
            for (NSString *key in configMap) {
                if ([key isEqual: @"progressInterval"]) {
                    progressInterval = [configMap[key] intValue];
                }
            }
        }
        if (isnan(progressInterval)) {
            progressInterval = 1.0;
        }

        self->idToTaskMap = [[NSMutableDictionary alloc] init];
        idToResumeDataMap = [[NSMutableDictionary alloc] init];
        idToPercentMap = [[NSMutableDictionary alloc] init];
        NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
        NSString *sessonIdentifier = [bundleIdentifier stringByAppendingString:@".backgrounddownloadtask"];
        sessionConfig = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:sessonIdentifier];
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

        progressReports = [[NSMutableDictionary alloc] init];
        lastProgressReportedAt = [[NSDate alloc] init];
        sharedLock = [NSNumber numberWithInt:1];
    }
    return self;
}

- (void)lazyInitSession {
    NSLog(@"[RNBackgroundDownloader] - [lazyInitSession]");
    @synchronized (sharedLock) {
        if (urlSession == nil) {
            urlSession = [NSURLSession sessionWithConfiguration:sessionConfig delegate:self delegateQueue:nil];
        }
        if (isNotificationCenterInited != YES) {
            isNotificationCenterInited = YES;
            [[NSNotificationCenter defaultCenter] addObserver:self
                                                selector:@selector(resumeTasks:)
                                                name:UIApplicationWillEnterForegroundNotification
                                                object:nil];
        }
    }
}

- (void) dealloc {
    NSLog(@"[RNBackgroundDownloader] - [dealloc]");
    [urlSession invalidateAndCancel];
    urlSession = nil;
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

// NOTE: FIXES HANGING DOWNLOADS WHEN GOING TO BG
- (void) resumeTasks:(NSNotification *) note {
    NSLog(@"[RNBackgroundDownloader] - [resumeTasks]");
    @synchronized (sharedLock) {
        [urlSession getTasksWithCompletionHandler:^(NSArray<NSURLSessionDataTask *> * _Nonnull dataTasks, NSArray<NSURLSessionUploadTask *> * _Nonnull uploadTasks, NSArray<NSURLSessionDownloadTask *> * _Nonnull downloadTasks) {
            for (NSURLSessionDownloadTask *task in downloadTasks) {
                // running - 0
                // suspended - 1
                // canceling - 2
                // completed - 3

                if (task.state == NSURLSessionTaskStateRunning) {
                    [task suspend]; // PAUSE
                    [task resume];
                }
            }
//            TODO: MAYBE ADD FOR OTHER TASKS TYPES
//            for (NSURLSessionDataTask *task in dataTasks) {
//                NSLog(@"[RNBackgroundDownloader] - [resumeTasks] 5");
//                [task resume];
//            }
//            for (NSURLSessionUploadTask *task in dataTasks) {
//                NSLog(@"[RNBackgroundDownloader] - [resumeTasks] 6");
//                [task resume];
//            }
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
            [self->idToTaskMap removeObjectForKey:taskConfig.id];
            [idToPercentMap removeObjectForKey:taskConfig.id];
        }
        // TOREMOVE - GIVES ERROR IN JS ON HOT RELOAD
        // if (taskToConfigMap.count == 0) {
        //     [urlSession invalidateAndCancel];
        //     urlSession = nil;
        // }
    }
}

+ (void)setCompletionHandlerWithIdentifier: (NSString *)identifier completionHandler: (CompletionHandler)completionHandler {
    NSLog(@"[RNBackgroundDownloader] - [setCompletionHandlerWithIdentifier]");
    NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
    NSString *sessonIdentifier = [bundleIdentifier stringByAppendingString:@".backgrounddownloadtask"];
    if ([sessonIdentifier isEqualToString:identifier]) {
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

- (BOOL)saveDownloadedFile: (nonnull RNBGDTaskConfig *) taskConfig downloadURL:(nonnull NSURL *)location error:(NSError **)saveError {
  NSLog(@"[RNBackgroundDownloader] - [saveDownloadedFile]");
  NSFileManager *fileManager = [NSFileManager defaultManager];
  NSURL *destURL = [NSURL fileURLWithPath:taskConfig.destination];
  [fileManager createDirectoryAtURL:[destURL URLByDeletingLastPathComponent] withIntermediateDirectories:YES attributes:nil error:nil];
  [fileManager removeItemAtURL:destURL error:nil];

  return [fileManager moveItemAtURL:location toURL:destURL error:saveError];
}

#pragma mark - JS exported methods
RCT_EXPORT_METHOD(download: (NSDictionary *) options) {
    NSLog(@"[RNBackgroundDownloader] - [download] - 1");
    NSString *identifier = options[@"id"];
    NSString *url = options[@"url"];
    NSString *destination = options[@"destination"];
    NSString *metadata = options[@"metadata"];
    NSDictionary *headers = options[@"headers"];


    NSNumber *_progressInterval = options[@"progressInterval"];
    if (_progressInterval) {
        progressInterval = [_progressInterval intValue] / 1000; // progressInterval IN options SUPPLIED IN MILLISECONDS

        NSDictionary *configMap = @{@"progressInterval": [NSNumber numberWithFloat:progressInterval]};
        [mmkv setData:[self serialize: configMap] forKey:CONFIG_MAP_KEY];
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
        [self lazyInitSession];
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

RCT_EXPORT_METHOD(checkForExistingDownloads: (RCTPromiseResolveBlock)resolve rejecter:(RCTPromiseRejectBlock)reject) {
    NSLog(@"[RNBackgroundDownloader] - [checkForExistingDownloads]");
    [self lazyInitSession];
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


#pragma mark - NSURLSessionDownloadDelegate methods
- (void)URLSession:(nonnull NSURLSession *)session downloadTask:(nonnull NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(nonnull NSURL *)location {
    NSLog(@"[RNBackgroundDownloader] - [didFinishDownloadingToURL]");
    @synchronized (sharedLock) {
        RNBGDTaskConfig *taskConfig = taskToConfigMap[@(downloadTask.taskIdentifier)];
        if (taskConfig != nil) {
            NSError *error = [self getServerError:downloadTask];
            if (error == nil) {
                [self saveDownloadedFile:taskConfig downloadURL:location error:&error];
            }
            if (self.bridge) {
                if (error == nil) {
                    NSDictionary *responseHeaders = ((NSHTTPURLResponse *)downloadTask.response).allHeaderFields;
                    // TODO: SEND bytesDownloaded AND bytesTotal
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
                if (self.bridge) {
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
                if (self.bridge) {
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

        if (self.bridge) {
            [self sendEventWithName:@"downloadFailed" body:@{
                @"id": taskCofig.id,
                @"error": [error localizedDescription],
                // TODO
                @"errorCode": @-1
            }];
        }
        // IF WE CAN'T RESUME TO DOWNLOAD LATER
        if (error.userInfo[NSURLSessionDownloadTaskResumeData] == nil) {
            [self removeTaskFromMap:task];
        }
    }
}

- (void)URLSessionDidFinishEventsForBackgroundURLSession:(NSURLSession *)session {
    NSLog(@"[RNBackgroundDownloader] - [URLSessionDidFinishEventsForBackgroundURLSession]");
   // USE completionHandler FROM JS INSTEAD OF THIS
   // TOREMOVE
   // if (storedCompletionHandler) {
   //     [[NSOperationQueue mainQueue] addOperationWithBlock:^{
   //         storedCompletionHandler();
   //         storedCompletionHandler = nil;
   //     }];
   // }
}

#pragma mark - serialization
- (NSData *)serialize: (id)obj {
    NSError *error;
    NSData *data = [NSKeyedArchiver archivedDataWithRootObject:obj requiringSecureCoding:NO error:&error];

    if (error) {
        // Handle the error
        NSLog(@"[RNBackgroundDownloader] Serialization error: %@", error);
    }

    return data;
}

- (id)deserialize: (NSData *)data {
    NSError *error;
    id obj = [NSKeyedUnarchiver unarchivedObjectOfClass:[NSObject class] fromData:data error:&error];

    if (error) {
        // Handle the error
        NSLog(@"[RNBackgroundDownloader] Deserialization error: %@", error);
    }

    return obj;
}

@end

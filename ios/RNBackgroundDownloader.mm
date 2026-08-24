#import "RNBackgroundDownloader.h"
#import "RNBGDTaskConfig.h"
#import "RNBGDUploadTaskConfig.h"
#import <React/RCTBridge.h>

#import <RNBackgroundDownloaderSpec/RNBackgroundDownloaderSpec.h>
#import <ReactCommon/TurboModule.h>

static NSString *const IOSMaxParallelDownloadsKey = @"RNBackgroundDownloaderMaxParallelDownloads";
static NSString *const IOSEnableLoggingKey = @"RNBackgroundDownloaderEnableLogging";
static NSString *const IOSProgressIntervalKey = @"RNBackgroundDownloaderProgressInterval";
static NSString *const IOSProgressMinBytesKey = @"RNBackgroundDownloaderProgressMinBytes";
static const NSTimeInterval RequestTimeoutSeconds = 30;
static const NSTimeInterval ResourceTimeoutSeconds = 60 * 60 * 24;
static const float ProgressReportThreshold = 0.01f;

@interface RNBackgroundDownloader ()
+ (RNBackgroundDownloader *)sharedCoordinator;
- (instancetype)initCoordinator;
@end

@implementation RNBackgroundDownloader {
    BOOL isCoordinator;
    RNBackgroundDownloader *coordinator;
    __weak RNBackgroundDownloader *eventSink;
    NSUUID *bindingToken;
    NSUUID *currentBindingToken;
    BOOL downloadEventSinkReady;
    BOOL uploadEventSinkReady;
    BOOL loggingEnabled;
    NSNumber *sharedLock;
    NSURLSession *urlSession;
    NSMutableArray<NSDictionary *> *pendingEmitEvents;

    NSMutableDictionary<NSNumber *, RNBGDTaskConfig *> *downloadConfigsByTask;
    NSMutableDictionary<NSString *, RNBGDTaskConfig *> *downloadConfigsById;
    NSMutableDictionary<NSString *, NSURLSessionDownloadTask *> *downloadTasksById;
    NSMutableDictionary<NSString *, NSNumber *> *downloadPercents;
    NSMutableDictionary<NSString *, NSNumber *> *downloadLastBytes;
    NSMutableDictionary<NSString *, NSDictionary *> *downloadProgressReports;
    NSDate *lastDownloadProgressAt;

    NSMutableDictionary<NSNumber *, RNBGDUploadTaskConfig *> *uploadConfigsByTask;
    NSMutableDictionary<NSString *, RNBGDUploadTaskConfig *> *uploadConfigsById;
    NSMutableDictionary<NSString *, NSURLSessionUploadTask *> *uploadTasksById;
    NSMutableDictionary<NSString *, NSNumber *> *uploadPercents;
    NSMutableDictionary<NSString *, NSNumber *> *uploadLastBytes;
    NSMutableDictionary<NSString *, NSDictionary *> *uploadProgressReports;
    NSDate *lastUploadProgressAt;

    NSTimeInterval progressInterval;
    int64_t progressMinBytes;
}

RCT_EXPORT_MODULE();

+ (BOOL)requiresMainQueueSetup
{
    return NO;
}

- (dispatch_queue_t)methodQueue
{
    return dispatch_queue_create("com.eko.rnbackgrounddownloader", DISPATCH_QUEUE_SERIAL);
}

- (instancetype)init
{
    self = [super init];
    if (self) {
        isCoordinator = NO;
        coordinator = [RNBackgroundDownloader sharedCoordinator];
        bindingToken = [NSUUID UUID];
        pendingEmitEvents = [[NSMutableArray alloc] init];
        [coordinator attachEventSink:self token:bindingToken];
    }
    return self;
}

+ (RNBackgroundDownloader *)sharedCoordinator
{
    static RNBackgroundDownloader *instance;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[RNBackgroundDownloader alloc] initCoordinator];
    });
    return instance;
}

- (instancetype)initCoordinator
{
    self = [super init];
    if (self) {
        isCoordinator = YES;
        sharedLock = @1;
        pendingEmitEvents = [[NSMutableArray alloc] init];

        NSDictionary *info = [NSBundle mainBundle].infoDictionary;
        NSInteger maximum = [info[IOSMaxParallelDownloadsKey] integerValue];
        maximum = maximum >= 1 ? maximum : 4;
        loggingEnabled = [info[IOSEnableLoggingKey] boolValue];
        NSInteger intervalMilliseconds = [info[IOSProgressIntervalKey] integerValue];
        intervalMilliseconds = intervalMilliseconds >= 250 ? intervalMilliseconds : 1000;
        NSInteger minimumBytes = [info[IOSProgressMinBytesKey] integerValue];
        progressMinBytes = minimumBytes >= 0 && info[IOSProgressMinBytesKey] != nil ? minimumBytes : 1024 * 1024;
        progressInterval = intervalMilliseconds / 1000.0;

        NSURLSessionConfiguration *configuration = [NSURLSessionConfiguration defaultSessionConfiguration];
        configuration.HTTPMaximumConnectionsPerHost = maximum;
        configuration.timeoutIntervalForRequest = RequestTimeoutSeconds;
        configuration.timeoutIntervalForResource = ResourceTimeoutSeconds;
        NSOperationQueue *delegateQueue = [[NSOperationQueue alloc] init];
        delegateQueue.maxConcurrentOperationCount = 1;
        urlSession = [NSURLSession sessionWithConfiguration:configuration delegate:self delegateQueue:delegateQueue];

        downloadConfigsByTask = [[NSMutableDictionary alloc] init];
        downloadConfigsById = [[NSMutableDictionary alloc] init];
        downloadTasksById = [[NSMutableDictionary alloc] init];
        downloadPercents = [[NSMutableDictionary alloc] init];
        downloadLastBytes = [[NSMutableDictionary alloc] init];
        downloadProgressReports = [[NSMutableDictionary alloc] init];
        lastDownloadProgressAt = [NSDate date];

        uploadConfigsByTask = [[NSMutableDictionary alloc] init];
        uploadConfigsById = [[NSMutableDictionary alloc] init];
        uploadTasksById = [[NSMutableDictionary alloc] init];
        uploadPercents = [[NSMutableDictionary alloc] init];
        uploadLastBytes = [[NSMutableDictionary alloc] init];
        uploadProgressReports = [[NSMutableDictionary alloc] init];
        lastUploadProgressAt = [NSDate date];
    }
    return self;
}

- (void)dealloc
{
    if (!isCoordinator)
        [coordinator detachEventSinkWithToken:bindingToken];
}

- (void)invalidate
{
    if (isCoordinator) return;
    [coordinator detachEventSinkWithToken:bindingToken];
}

- (NSDictionary *)constantsToExport
{
    if (!isCoordinator) return [coordinator constantsToExport];
    return @{
        @"documents": [NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject],
        @"TaskRunning": @(NSURLSessionTaskStateRunning),
        @"TaskSuspended": @(NSURLSessionTaskStateSuspended),
        @"TaskCanceling": @(NSURLSessionTaskStateCanceling),
        @"TaskCompleted": @(NSURLSessionTaskStateCompleted),
        @"isLoggingEnabled": @(loggingEnabled)
    };
}

- (NSDictionary *)getConstants
{
    return [self constantsToExport];
}

- (void)attachEventSink:(RNBackgroundDownloader *)sink token:(NSUUID *)token
{
    @synchronized (pendingEmitEvents) {
        eventSink = sink;
        currentBindingToken = token;
        downloadEventSinkReady = NO;
        uploadEventSinkReady = NO;
    }
}

- (void)detachEventSinkWithToken:(NSUUID *)token
{
    @synchronized (pendingEmitEvents) {
        if (![currentBindingToken isEqual:token]) return;
        eventSink = nil;
        currentBindingToken = nil;
        downloadEventSinkReady = NO;
        uploadEventSinkReady = NO;
    }
}

- (BOOL)isBindingTokenCurrent:(NSUUID *)token
{
    @synchronized (pendingEmitEvents) {
        return [currentBindingToken isEqual:token];
    }
}

- (NSArray<NSDictionary *> *)markEventSinkReadyWithToken:(NSUUID *)token family:(NSString *)family
{
    @synchronized (pendingEmitEvents) {
        if (![currentBindingToken isEqual:token]) return @[];
        if ([family isEqualToString:@"download"])
            downloadEventSinkReady = YES;
        else if ([family isEqualToString:@"upload"])
            uploadEventSinkReady = YES;
        else
            return @[];
        NSMutableArray *events = [[NSMutableArray alloc] initWithCapacity:pendingEmitEvents.count];
        for (NSDictionary *event in pendingEmitEvents) {
            if (![event[@"key"] hasPrefix:family]) continue;
            [events addObject:@{
                @"name": event[@"name"],
                @"key": event[@"key"],
                @"payload": event[@"value"] ?: @{}
            }];
        }
        return events;
    }
}

- (void)markEventSinkReconcilingWithToken:(NSUUID *)token family:(NSString *)family
{
    @synchronized (pendingEmitEvents) {
        if (![currentBindingToken isEqual:token]) return;
        if ([family isEqualToString:@"download"])
            downloadEventSinkReady = NO;
        else if ([family isEqualToString:@"upload"])
            uploadEventSinkReady = NO;
    }
}

- (void)acknowledgeEventsWithKeys:(NSArray<NSString *> *)keys token:(NSUUID *)token
{
    @synchronized (pendingEmitEvents) {
        if (![currentBindingToken isEqual:token]) return;
        NSSet *acknowledged = [NSSet setWithArray:keys];
        NSIndexSet *indexes = [pendingEmitEvents indexesOfObjectsPassingTest:^BOOL(NSDictionary *event, NSUInteger index, BOOL *stop) {
            return [acknowledged containsObject:event[@"key"]];
        }];
        [pendingEmitEvents removeObjectsAtIndexes:indexes];
    }
}

- (RCTPromiseResolveBlock)runtimeResolve:(RCTPromiseResolveBlock)resolve
{
    __weak RNBackgroundDownloader *weakSelf = self;
    NSUUID *token = bindingToken;
    return ^(id result) {
        RNBackgroundDownloader *strongSelf = weakSelf;
        if (strongSelf && [strongSelf->coordinator isBindingTokenCurrent:token]) resolve(result);
    };
}

- (RCTPromiseRejectBlock)runtimeReject:(RCTPromiseRejectBlock)reject
{
    __weak RNBackgroundDownloader *weakSelf = self;
    NSUUID *token = bindingToken;
    return ^(NSString *code, NSString *message, NSError *error) {
        RNBackgroundDownloader *strongSelf = weakSelf;
        if (strongSelf && [strongSelf->coordinator isBindingTokenCurrent:token]) reject(code, message, error);
    };
}

- (void)setRuntimeReady:(NSString *)family resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (![coordinator isBindingTokenCurrent:bindingToken]) return;
    RCTPromiseResolveBlock guardedResolve = [self runtimeResolve:resolve];
    guardedResolve([coordinator markEventSinkReadyWithToken:bindingToken family:family]);
}

- (void)acknowledgeRuntimeEvents:(NSArray<NSString *> *)keys
{
    [coordinator acknowledgeEventsWithKeys:keys token:bindingToken];
}

- (void)dispatchCoordinatorEvent:(NSString *)eventName value:(id)value
{
    @synchronized (pendingEmitEvents) {
        RNBackgroundDownloader *sink = eventSink;
        BOOL upload = [eventName containsString:@"Upload"] || [eventName hasPrefix:@"upload"];
        BOOL ready = upload ? uploadEventSinkReady : downloadEventSinkReady;
        BOOL progress = [eventName containsString:@"Progress"];
        BOOL terminal = [eventName containsString:@"Complete"] || [eventName containsString:@"Failed"];
        NSString *family = upload ? @"upload" : @"download";
        if (!sink || !ready || terminal) {
            NSArray *values = progress && [value isKindOfClass:[NSArray class]] ? value : @[value ?: @{}];
            for (NSDictionary *item in values) {
                NSString *taskId = item[@"id"];
                NSString *key = taskId
                    ? [NSString stringWithFormat:@"%@%@:%@", family, progress ? @"-progress" : @"", taskId]
                    : [NSUUID UUID].UUIDString;
                NSIndexSet *existing = [pendingEmitEvents indexesOfObjectsPassingTest:^BOOL(NSDictionary *event, NSUInteger index, BOOL *stop) {
                    return [event[@"key"] isEqualToString:key];
                }];
                [pendingEmitEvents removeObjectsAtIndexes:existing];
                if (terminal) {
                    NSString *progressKey = [NSString stringWithFormat:@"%@-progress:%@", family, taskId];
                    NSIndexSet *progressEvents = [pendingEmitEvents indexesOfObjectsPassingTest:^BOOL(NSDictionary *event, NSUInteger index, BOOL *stop) {
                        return [event[@"key"] isEqualToString:progressKey];
                    }];
                    [pendingEmitEvents removeObjectsAtIndexes:progressEvents];
                }
                if (pendingEmitEvents.count >= 256) [pendingEmitEvents removeObjectAtIndex:0];
                [pendingEmitEvents addObject:@{ @"name": eventName, @"key": key, @"value": item }];
            }
        }

        if (sink && ready) [sink emitEventToRuntime:eventName value:value];
    }
}

- (void)emitEventToRuntime:(NSString *)eventName value:(id)value
{
    [self safeEmitEvent:eventName value:value];
}

- (void)emitEvent:(NSString *)event value:(id)value
{
    [self dispatchCoordinatorEvent:[@"on" stringByAppendingString:[event stringByReplacingCharactersInRange:NSMakeRange(0, 1) withString:[[event substringToIndex:1] uppercaseString]]] value:value];
}

- (void)log:(NSString *)message taskId:(nullable NSString *)taskId
{
    if (loggingEnabled) NSLog(@"[RNBackgroundDownloader] %@%@", message, taskId ? [NSString stringWithFormat:@" taskId:%@", taskId] : @"");
}

- (void)safeEmitEvent:(NSString *)eventName value:(id)value
{
    if (isCoordinator) {
        [self dispatchCoordinatorEvent:eventName value:value];
        return;
    }
    if (![coordinator isBindingTokenCurrent:bindingToken]) return;
    @synchronized (pendingEmitEvents) {
        if (_eventEmitterCallback)
            _eventEmitterCallback(std::string([eventName UTF8String]), value);
        else
            [pendingEmitEvents addObject:@{ @"name": eventName, @"value": value }];
    }
}

- (void)setEventEmitterCallback:(EventEmitterCallbackWrapper *)wrapper
{
    @synchronized (pendingEmitEvents) {
        [super setEventEmitterCallback:wrapper];
        if (![coordinator isBindingTokenCurrent:bindingToken]) {
            [pendingEmitEvents removeAllObjects];
            return;
        }
        for (NSDictionary *event in pendingEmitEvents)
            _eventEmitterCallback(std::string([event[@"name"] UTF8String]), event[@"value"]);
        [pendingEmitEvents removeAllObjects];
    }
}

- (void)emitDownloadFailure:(NSString *)identifier error:(NSString *)error code:(NSInteger)code metadata:(nullable NSString *)metadata
{
    if (!identifier) return;
    [self emitEvent:@"downloadFailed" value:@{ @"id": identifier, @"error": error ?: @"Download failed", @"errorCode": @(code), @"metadata": metadata ?: @"{}" }];
}

- (void)emitUploadFailure:(NSString *)identifier error:(NSString *)error code:(NSInteger)code metadata:(nullable NSString *)metadata
{
    if (!identifier) return;
    [self emitEvent:@"uploadFailed" value:@{ @"id": identifier, @"error": error ?: @"Upload failed", @"errorCode": @(code), @"metadata": metadata ?: @"{}" }];
}

- (void)startDownloadWithId:(NSString *)identifier
                         url:(NSString *)url
                 destination:(NSString *)destination
                    metadata:(NSString *)metadata
                     headers:(NSDictionary *)headers
{
    if (!identifier || !url || !destination) {
        [self emitDownloadFailure:identifier error:@"id, url and destination are required" code:NSURLErrorBadURL metadata:metadata];
        return;
    }
    NSURL *requestURL = [NSURL URLWithString:url];
    if (!requestURL) {
        [self emitDownloadFailure:identifier error:@"Invalid download URL" code:NSURLErrorBadURL metadata:metadata];
        return;
    }

    @synchronized (sharedLock) {
        NSURLSessionDownloadTask *existing = downloadTasksById[identifier];
        if (existing) {
            [self removeDownloadTask:existing];
            [existing cancel];
        }

        NSMutableURLRequest *request = [[NSMutableURLRequest alloc] initWithURL:requestURL];
        request.timeoutInterval = RequestTimeoutSeconds;
        for (NSString *key in headers ?: @{}) [request setValue:headers[key] forHTTPHeaderField:key];

        NSURLSessionDownloadTask *task = [urlSession downloadTaskWithRequest:request];
        RNBGDTaskConfig *config = [[RNBGDTaskConfig alloc] initWithDictionary:@{
            @"id": identifier,
            @"url": url,
            @"destination": destination,
            @"metadata": metadata ?: @"{}",
            @"headers": headers ?: @{}
        }];
        downloadConfigsByTask[@(task.taskIdentifier)] = config;
        downloadConfigsById[identifier] = config;
        downloadTasksById[identifier] = task;
        downloadPercents[identifier] = @0;
        downloadLastBytes[identifier] = @0;
        [task resume];
        lastDownloadProgressAt = [NSDate date];
        [self log:@"Started download" taskId:identifier];
    }
}

- (void)download:(JS::NativeRNBackgroundDownloader::SpecDownloadOptions &)options
{
    if (![coordinator isBindingTokenCurrent:bindingToken]) return;
    [coordinator startDownloadWithId:options.id_()
                                url:options.url()
                        destination:options.destination()
                           metadata:options.metadata() ? options.metadata() : @"{}"
                            headers:options.headers() ? (NSDictionary *)options.headers() : @{}];
}

- (void)pauseTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (!isCoordinator) {
        if (![coordinator isBindingTokenCurrent:bindingToken]) return;
        [coordinator pauseTask:identifier resolve:[self runtimeResolve:resolve] reject:[self runtimeReject:reject]];
        return;
    }
    @synchronized (sharedLock) {
        NSURLSessionDownloadTask *task = downloadTasksById[identifier];
        RNBGDTaskConfig *config = downloadConfigsById[identifier];
        if (!task || !config) {
            reject(@"ERR_PAUSE_TASK", @"Download task not found", nil);
            return;
        }
        [task suspend];
        config.state = NSURLSessionTaskStateSuspended;
    }
    resolve(nil);
}

- (void)resumeTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (!isCoordinator) {
        if (![coordinator isBindingTokenCurrent:bindingToken]) return;
        [coordinator resumeTask:identifier resolve:[self runtimeResolve:resolve] reject:[self runtimeReject:reject]];
        return;
    }
    @synchronized (sharedLock) {
        NSURLSessionDownloadTask *task = downloadTasksById[identifier];
        RNBGDTaskConfig *config = downloadConfigsById[identifier];
        if (!task || !config || task.state != NSURLSessionTaskStateSuspended) {
            reject(@"ERR_RESUME_TASK", @"Download task is not paused", nil);
            return;
        }
        [task resume];
        config.state = NSURLSessionTaskStateRunning;
    }
    resolve(nil);
}

- (void)stopTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (!isCoordinator) {
        if (![coordinator isBindingTokenCurrent:bindingToken]) return;
        [coordinator stopTask:identifier resolve:[self runtimeResolve:resolve] reject:[self runtimeReject:reject]];
        return;
    }
    @synchronized (sharedLock) {
        NSURLSessionDownloadTask *task = downloadTasksById[identifier];
        if (task) {
            [self removeDownloadTask:task];
            [task cancel];
        }
    }
    resolve(nil);
}

- (void)getExistingDownloadTasks:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (!isCoordinator) {
        if (![coordinator isBindingTokenCurrent:bindingToken]) return;
        [coordinator markEventSinkReconcilingWithToken:bindingToken family:@"download"];
        [coordinator getExistingDownloadTasks:[self runtimeResolve:resolve] reject:[self runtimeReject:reject]];
        return;
    }
    NSMutableArray *result = [[NSMutableArray alloc] init];
    @synchronized (sharedLock) {
        for (NSString *identifier in downloadConfigsById) {
            RNBGDTaskConfig *config = downloadConfigsById[identifier];
            NSURLSessionDownloadTask *task = downloadTasksById[identifier];
            if (!task) continue;
            config.state = task.state;
            config.bytesDownloaded = task.countOfBytesReceived;
            config.bytesTotal = task.countOfBytesExpectedToReceive;
            [result addObject:@{
                @"id": config.id,
                @"metadata": config.metadata,
                @"state": @(config.state),
                @"bytesDownloaded": @(config.bytesDownloaded),
                @"bytesTotal": @(config.bytesTotal),
                @"errorCode": @(config.errorCode),
                @"destination": config.destination
            }];
        }
    }
    resolve(result);
}

- (void)removeDownloadTask:(NSURLSessionTask *)task
{
    RNBGDTaskConfig *config = downloadConfigsByTask[@(task.taskIdentifier)];
    [downloadConfigsByTask removeObjectForKey:@(task.taskIdentifier)];
    if (!config) return;
    if (downloadTasksById[config.id] == task) [downloadTasksById removeObjectForKey:config.id];
    [downloadConfigsById removeObjectForKey:config.id];
    [downloadPercents removeObjectForKey:config.id];
    [downloadLastBytes removeObjectForKey:config.id];
    [downloadProgressReports removeObjectForKey:config.id];
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)task didWriteData:(int64_t)bytesDownloaded totalBytesWritten:(int64_t)bytesWritten totalBytesExpectedToWrite:(int64_t)bytesTotal
{
    @synchronized (sharedLock) {
        RNBGDTaskConfig *config = downloadConfigsByTask[@(task.taskIdentifier)];
        if (!config) return;
        if (!config.reportedBegin) {
            NSDictionary *responseHeaders = [task.response isKindOfClass:[NSHTTPURLResponse class]]
                ? ((NSHTTPURLResponse *)task.response).allHeaderFields
                : @{};
            [self emitEvent:@"downloadBegin" value:@{ @"id": config.id, @"expectedBytes": @(bytesTotal), @"headers": responseHeaders ?: @{} }];
            config.reportedBegin = YES;
        }
        [self recordProgressForId:config.id bytes:bytesWritten total:bytesTotal percents:downloadPercents lastBytes:downloadLastBytes reports:downloadProgressReports bytesKey:@"bytesDownloaded"];
        config.bytesDownloaded = bytesWritten;
        config.bytesTotal = bytesTotal;
        lastDownloadProgressAt = [self flushProgressReports:downloadProgressReports event:@"downloadProgress" lastDate:lastDownloadProgressAt];
    }
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)task didFinishDownloadingToURL:(NSURL *)location
{
    @synchronized (sharedLock) {
        RNBGDTaskConfig *config = downloadConfigsByTask[@(task.taskIdentifier)];
        if (!config) return;

        NSError *error = nil;
        if ([task.response isKindOfClass:[NSHTTPURLResponse class]]) {
            NSInteger status = ((NSHTTPURLResponse *)task.response).statusCode;
            if (status < 200 || status >= 300)
                error = [NSError errorWithDomain:NSURLErrorDomain code:status userInfo:@{ NSLocalizedDescriptionKey: [NSHTTPURLResponse localizedStringForStatusCode:status] }];
        }

        if (!error) {
            NSURL *destination = [NSURL fileURLWithPath:config.destination];
            NSFileManager *files = [NSFileManager defaultManager];
            [files createDirectoryAtURL:destination.URLByDeletingLastPathComponent withIntermediateDirectories:YES attributes:nil error:&error];
            if (!error) {
                [files removeItemAtURL:destination error:nil];
                [files moveItemAtURL:location toURL:destination error:&error];
            }
        }

        [downloadProgressReports removeObjectForKey:config.id];
        if (error) {
            [self emitDownloadFailure:config.id error:error.localizedDescription code:error.code metadata:config.metadata];
        } else {
            if (!config.reportedBegin) {
                NSDictionary *headers = [task.response isKindOfClass:[NSHTTPURLResponse class]] ? ((NSHTTPURLResponse *)task.response).allHeaderFields : @{};
                [self emitEvent:@"downloadBegin" value:@{ @"id": config.id, @"expectedBytes": @(task.countOfBytesExpectedToReceive), @"headers": headers ?: @{} }];
            }
            [self emitEvent:@"downloadComplete" value:@{
                @"id": config.id,
                @"location": config.destination,
                @"bytesDownloaded": @(task.countOfBytesReceived),
                @"bytesTotal": @(task.countOfBytesExpectedToReceive),
                @"metadata": config.metadata
            }];
        }
        [self removeDownloadTask:task];
    }
}

- (void)recordProgressForId:(NSString *)identifier
                      bytes:(int64_t)bytes
                      total:(int64_t)total
                   percents:(NSMutableDictionary *)percents
                  lastBytes:(NSMutableDictionary *)lastBytes
                    reports:(NSMutableDictionary *)reports
                   bytesKey:(NSString *)bytesKey
{
    double previousPercent = [percents[identifier] doubleValue];
    int64_t previousBytes = [lastBytes[identifier] longLongValue];
    double percent = total > 0 ? (double)bytes / total : 0;
    BOOL percentMet = total > 0 && percent - previousPercent > ProgressReportThreshold;
    BOOL bytesMet = progressMinBytes > 0 && bytes - previousBytes >= progressMinBytes;
    if (percentMet || bytesMet || total <= 0) {
        reports[identifier] = @{ @"id": identifier, bytesKey: @(bytes), @"bytesTotal": @(total) };
        percents[identifier] = @(percent);
        lastBytes[identifier] = @(bytes);
    }
}

- (NSDate *)flushProgressReports:(NSMutableDictionary *)reports event:(NSString *)event lastDate:(NSDate *)lastDate
{
    NSDate *now = [NSDate date];
    if (reports.count > 0 && [now timeIntervalSinceDate:lastDate] >= progressInterval) {
        [self emitEvent:event value:reports.allValues];
        [reports removeAllObjects];
        return now;
    }
    return lastDate;
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error
{
    @synchronized (sharedLock) {
        RNBGDUploadTaskConfig *upload = uploadConfigsByTask[@(task.taskIdentifier)];
        if (upload) {
            [self completeUploadTask:task config:upload error:error];
            return;
        }
        RNBGDTaskConfig *download = downloadConfigsByTask[@(task.taskIdentifier)];
        if (!download || !error) return;
        [downloadProgressReports removeObjectForKey:download.id];
        [self emitDownloadFailure:download.id error:error.localizedDescription code:error.code metadata:download.metadata];
        [self removeDownloadTask:task];
    }
}

- (void)startUploadWithId:(NSString *)identifier
                       url:(NSString *)url
                    source:(NSString *)source
                    method:(NSString *)method
                  metadata:(NSString *)metadata
                   headers:(NSDictionary *)headers
                 fieldName:(nullable NSString *)fieldName
                  mimeType:(nullable NSString *)mimeType
                parameters:(nullable NSDictionary *)parameters
{
    if (!identifier || !url || !source) {
        [self emitUploadFailure:identifier error:@"id, url and source are required" code:NSURLErrorBadURL metadata:metadata];
        return;
    }
    NSURL *requestURL = [NSURL URLWithString:url];
    if (!requestURL) {
        [self emitUploadFailure:identifier error:@"Invalid upload URL" code:NSURLErrorBadURL metadata:metadata];
        return;
    }
    if (![[NSFileManager defaultManager] fileExistsAtPath:source]) {
        [self emitUploadFailure:identifier error:@"Upload source file does not exist" code:NSURLErrorFileDoesNotExist metadata:metadata];
        return;
    }

    @synchronized (sharedLock) {
        NSURLSessionUploadTask *existing = uploadTasksById[identifier];
        if (existing) {
            [self removeUploadTask:existing];
            [existing cancel];
        }

        NSMutableURLRequest *request = [[NSMutableURLRequest alloc] initWithURL:requestURL];
        request.HTTPMethod = method ?: @"POST";
        request.timeoutInterval = RequestTimeoutSeconds;
        for (NSString *key in headers ?: @{}) [request setValue:headers[key] forHTTPHeaderField:key];

        RNBGDUploadTaskConfig *config = [[RNBGDUploadTaskConfig alloc] initWithDictionary:@{
            @"id": identifier,
            @"url": url,
            @"source": source,
            @"method": method ?: @"POST",
            @"metadata": metadata ?: @"{}",
            @"headers": headers ?: @{},
            @"fieldName": fieldName ?: [NSNull null],
            @"mimeType": mimeType ?: [NSNull null],
            @"parameters": parameters ?: [NSNull null]
        }];

        NSURL *bodyURL = [NSURL fileURLWithPath:source];
        if ((parameters && parameters.count > 0) || fieldName) {
            NSString *boundary = [NSUUID UUID].UUIDString;
            [request setValue:[NSString stringWithFormat:@"multipart/form-data; boundary=%@", boundary] forHTTPHeaderField:@"Content-Type"];
            NSMutableData *body = [[NSMutableData alloc] init];
            for (NSString *key in parameters ?: @{}) {
                [body appendData:[[NSString stringWithFormat:@"--%@\r\nContent-Disposition: form-data; name=\"%@\"\r\n\r\n%@\r\n", boundary, key, parameters[key]] dataUsingEncoding:NSUTF8StringEncoding]];
            }
            NSString *name = fieldName ?: @"file";
            NSString *type = mimeType ?: @"application/octet-stream";
            [body appendData:[[NSString stringWithFormat:@"--%@\r\nContent-Disposition: form-data; name=\"%@\"; filename=\"%@\"\r\nContent-Type: %@\r\n\r\n", boundary, name, source.lastPathComponent, type] dataUsingEncoding:NSUTF8StringEncoding]];
            NSData *fileData = [NSData dataWithContentsOfFile:source];
            if (!fileData) {
                [self emitUploadFailure:identifier error:@"Could not read upload source" code:NSURLErrorCannotOpenFile metadata:metadata];
                return;
            }
            [body appendData:fileData];
            [body appendData:[[NSString stringWithFormat:@"\r\n--%@--\r\n", boundary] dataUsingEncoding:NSUTF8StringEncoding]];
            NSString *temporaryPath = [NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID UUID].UUIDString];
            if (![body writeToFile:temporaryPath atomically:YES]) {
                [self emitUploadFailure:identifier error:@"Could not create multipart upload body" code:NSURLErrorCannotWriteToFile metadata:metadata];
                return;
            }
            config.temporaryBodyPath = temporaryPath;
            config.bytesTotal = body.length;
            bodyURL = [NSURL fileURLWithPath:temporaryPath];
        } else {
            if (mimeType) [request setValue:mimeType forHTTPHeaderField:@"Content-Type"];
            config.bytesTotal = [[[NSFileManager defaultManager] attributesOfItemAtPath:source error:nil] fileSize];
        }

        NSURLSessionUploadTask *task = [urlSession uploadTaskWithRequest:request fromFile:bodyURL];
        uploadConfigsByTask[@(task.taskIdentifier)] = config;
        uploadConfigsById[identifier] = config;
        uploadTasksById[identifier] = task;
        uploadPercents[identifier] = @0;
        uploadLastBytes[identifier] = @0;
        [task resume];
        lastUploadProgressAt = [NSDate date];
    }
}

- (void)upload:(JS::NativeRNBackgroundDownloader::SpecUploadOptions &)options
{
    if (![coordinator isBindingTokenCurrent:bindingToken]) return;
    [coordinator startUploadWithId:options.id_()
                              url:options.url()
                           source:options.source()
                           method:options.method() ? options.method() : @"POST"
                         metadata:options.metadata() ? options.metadata() : @"{}"
                          headers:options.headers() ? (NSDictionary *)options.headers() : @{}
                        fieldName:options.fieldName() ? options.fieldName() : nil
                         mimeType:options.mimeType() ? options.mimeType() : nil
                       parameters:options.parameters() ? (NSDictionary *)options.parameters() : nil];
}

- (void)pauseUploadTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (!isCoordinator) {
        if (![coordinator isBindingTokenCurrent:bindingToken]) return;
        [coordinator pauseUploadTask:identifier resolve:[self runtimeResolve:resolve] reject:[self runtimeReject:reject]];
        return;
    }
    @synchronized (sharedLock) {
        NSURLSessionUploadTask *task = uploadTasksById[identifier];
        RNBGDUploadTaskConfig *config = uploadConfigsById[identifier];
        if (!task || !config) {
            reject(@"ERR_PAUSE_UPLOAD_TASK", @"Upload task not found", nil);
            return;
        }
        [task suspend];
        config.state = NSURLSessionTaskStateSuspended;
    }
    resolve(nil);
}

- (void)resumeUploadTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (!isCoordinator) {
        if (![coordinator isBindingTokenCurrent:bindingToken]) return;
        [coordinator resumeUploadTask:identifier resolve:[self runtimeResolve:resolve] reject:[self runtimeReject:reject]];
        return;
    }
    @synchronized (sharedLock) {
        NSURLSessionUploadTask *task = uploadTasksById[identifier];
        RNBGDUploadTaskConfig *config = uploadConfigsById[identifier];
        if (!task || !config || task.state != NSURLSessionTaskStateSuspended) {
            reject(@"ERR_RESUME_UPLOAD_TASK", @"Upload task is not paused", nil);
            return;
        }
        [task resume];
        config.state = NSURLSessionTaskStateRunning;
    }
    resolve(nil);
}

- (void)stopUploadTask:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (!isCoordinator) {
        if (![coordinator isBindingTokenCurrent:bindingToken]) return;
        [coordinator stopUploadTask:identifier resolve:[self runtimeResolve:resolve] reject:[self runtimeReject:reject]];
        return;
    }
    @synchronized (sharedLock) {
        NSURLSessionUploadTask *task = uploadTasksById[identifier];
        if (task) {
            [self removeUploadTask:task];
            [task cancel];
        }
    }
    resolve(nil);
}

- (void)getExistingUploadTasks:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject
{
    if (!isCoordinator) {
        if (![coordinator isBindingTokenCurrent:bindingToken]) return;
        [coordinator markEventSinkReconcilingWithToken:bindingToken family:@"upload"];
        [coordinator getExistingUploadTasks:[self runtimeResolve:resolve] reject:[self runtimeReject:reject]];
        return;
    }
    NSMutableArray *result = [[NSMutableArray alloc] init];
    @synchronized (sharedLock) {
        for (NSString *identifier in uploadConfigsById) {
            RNBGDUploadTaskConfig *config = uploadConfigsById[identifier];
            NSURLSessionUploadTask *task = uploadTasksById[identifier];
            if (!task) continue;
            config.state = task.state;
            config.bytesUploaded = task.countOfBytesSent;
            config.bytesTotal = task.countOfBytesExpectedToSend;
            [result addObject:@{
                @"id": config.id,
                @"metadata": config.metadata,
                @"state": @(config.state),
                @"bytesUploaded": @(config.bytesUploaded),
                @"bytesTotal": @(config.bytesTotal),
                @"errorCode": @(config.errorCode)
            }];
        }
    }
    resolve(result);
}

- (void)removeUploadTask:(NSURLSessionTask *)task
{
    RNBGDUploadTaskConfig *config = uploadConfigsByTask[@(task.taskIdentifier)];
    [uploadConfigsByTask removeObjectForKey:@(task.taskIdentifier)];
    if (!config) return;
    if (uploadTasksById[config.id] == task) [uploadTasksById removeObjectForKey:config.id];
    [uploadConfigsById removeObjectForKey:config.id];
    [uploadPercents removeObjectForKey:config.id];
    [uploadLastBytes removeObjectForKey:config.id];
    [uploadProgressReports removeObjectForKey:config.id];
    if (config.temporaryBodyPath) [[NSFileManager defaultManager] removeItemAtPath:config.temporaryBodyPath error:nil];
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didSendBodyData:(int64_t)bytesSent totalBytesSent:(int64_t)bytesUploaded totalBytesExpectedToSend:(int64_t)bytesTotal
{
    @synchronized (sharedLock) {
        RNBGDUploadTaskConfig *config = uploadConfigsByTask[@(task.taskIdentifier)];
        if (!config) return;
        if (!config.reportedBegin) {
            [self emitEvent:@"uploadBegin" value:@{ @"id": config.id, @"expectedBytes": @(bytesTotal) }];
            config.reportedBegin = YES;
        }
        [self recordProgressForId:config.id bytes:bytesUploaded total:bytesTotal percents:uploadPercents lastBytes:uploadLastBytes reports:uploadProgressReports bytesKey:@"bytesUploaded"];
        config.bytesUploaded = bytesUploaded;
        config.bytesTotal = bytesTotal;
        lastUploadProgressAt = [self flushProgressReports:uploadProgressReports event:@"uploadProgress" lastDate:lastUploadProgressAt];
    }
}

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)task didReceiveData:(NSData *)data
{
    @synchronized (sharedLock) {
        RNBGDUploadTaskConfig *config = uploadConfigsByTask[@(task.taskIdentifier)];
        if (config) [config.responseData appendData:data];
    }
}

- (void)completeUploadTask:(NSURLSessionTask *)task config:(RNBGDUploadTaskConfig *)config error:(NSError *)error
{
    [uploadProgressReports removeObjectForKey:config.id];
    NSInteger status = [task.response isKindOfClass:[NSHTTPURLResponse class]] ? ((NSHTTPURLResponse *)task.response).statusCode : 0;
    if (error) {
        [self emitUploadFailure:config.id error:error.localizedDescription code:error.code metadata:config.metadata];
    } else if (status < 200 || status >= 300) {
        [self emitUploadFailure:config.id error:[NSHTTPURLResponse localizedStringForStatusCode:status] code:status metadata:config.metadata];
    } else {
        NSString *body = [[NSString alloc] initWithData:config.responseData encoding:NSUTF8StringEncoding] ?: @"";
        [self emitEvent:@"uploadComplete" value:@{
            @"id": config.id,
            @"responseCode": @(status),
            @"responseBody": body,
            @"bytesUploaded": @(task.countOfBytesSent),
            @"bytesTotal": @(task.countOfBytesExpectedToSend),
            @"metadata": config.metadata
        }];
    }
    [self removeUploadTask:task];
}

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:(const facebook::react::ObjCTurboModule::InitParams &)params
{
    return std::make_shared<facebook::react::NativeRNBackgroundDownloaderSpecJSI>(params);
}

@end

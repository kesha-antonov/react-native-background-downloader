#import <XCTest/XCTest.h>
#import <MMKV/MMKV.h>
#import "RNBackgroundDownloader.h"
#import "RNBGDTaskConfig.h"
#import "RNBGDUploadTaskConfig.h"

// The module's internals the tests drive directly
@interface RNBackgroundDownloader (Testing)
- (void)emitDownloadFailed:(NSString *)identifier error:(NSString *)error errorCode:(NSInteger)errorCode;
- (void)_setMaxParallelDownloadsInternal:(NSInteger)max;
- (void)_setAllowsCellularAccessInternal:(BOOL)allows;
- (void)resumeTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject;
- (void)stopTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject;
- (void)updateTaskHeadersInternal:(NSString *)identifier headers:(NSDictionary *)headers resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject;
- (void)stopUploadTaskInternal:(NSString *)identifier resolve:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject;
- (void)restoreTaskIfNeeded:(NSURLSessionDownloadTask *__strong *)taskPtr;
- (NSString *)multipartTempPathForUploadId:(NSString *)identifier;
- (NSData *)loadResumeDataForTaskId:(NSString *)taskId;
- (void)getExistingDownloadTasks:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject;
#ifndef RCT_NEW_ARCH_ENABLED
- (void)download:(NSDictionary *)options;
#endif
@end

// Records download failures instead of sending them to JS
@interface RNBGDRecordingDownloader : RNBackgroundDownloader
@property (nonatomic, strong) NSMutableArray<NSDictionary *> *failures;
@end

@implementation RNBGDRecordingDownloader

- (void)emitDownloadFailed:(NSString *)identifier error:(NSString *)error errorCode:(NSInteger)errorCode
{
    @synchronized (self.failures) {
        [self.failures addObject:@{@"id": identifier ?: [NSNull null], @"error": error ?: @""}];
    }
}

@end

// A task in whatever state a test needs; real tasks only reach most states over the network
@interface RNBGDFakeTask : NSURLSessionDownloadTask
@property (nonatomic, assign) NSUInteger fakeIdentifier;
@property (nonatomic, assign) NSURLSessionTaskState fakeState;
@property (nonatomic, assign) int64_t fakeReceived;
@property (nonatomic, assign) int64_t fakeExpected;
@property (nonatomic, strong) NSURLRequest *fakeRequest;
@property (nonatomic, strong) NSError *fakeError;
@end

@implementation RNBGDFakeTask

- (NSUInteger)taskIdentifier { return self.fakeIdentifier; }
- (NSURLSessionTaskState)state { return self.fakeState; }
- (int64_t)countOfBytesReceived { return self.fakeReceived; }
- (int64_t)countOfBytesExpectedToReceive { return self.fakeExpected; }
- (NSURLRequest *)originalRequest { return self.fakeRequest; }
- (NSURLRequest *)currentRequest { return self.fakeRequest; }
- (NSError *)error { return self.fakeError; }

@end

static NSString *const kUnreachableURL = @"http://127.0.0.1:9/file.zip";

@interface RNBackgroundDownloaderTests : XCTestCase
@end

@implementation RNBackgroundDownloaderTests {
    NSMutableArray<NSString *> *downloadIds;
    NSMutableArray<NSString *> *uploadIds;
}

// One module for the whole run: a second background session with the same identifier
// in the same process doesn't get events
+ (RNBGDRecordingDownloader *)module
{
    static RNBGDRecordingDownloader *module;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        module = [RNBGDRecordingDownloader new];
        module.failures = [NSMutableArray new];
    });
    return module;
}

- (RNBGDRecordingDownloader *)module
{
    return [[self class] module];
}

- (void)setUp
{
    downloadIds = [NSMutableArray new];
    uploadIds = [NSMutableArray new];
    @synchronized (self.module.failures) {
        [self.module.failures removeAllObjects];
    }
}

- (void)tearDown
{
    for (NSString *identifier in downloadIds) {
        [self.module stopTaskInternal:identifier resolve:^(id result) {} reject:^(NSString *code, NSString *message, NSError *error) {}];
    }
    for (NSString *identifier in uploadIds) {
        [self.module stopUploadTaskInternal:identifier resolve:^(id result) {} reject:^(NSString *code, NSString *message, NSError *error) {}];
    }
}

#pragma mark - Helpers

- (NSString *)uniqueId:(NSString *)prefix
{
    NSString *identifier = [NSString stringWithFormat:@"%@-%@", prefix, [NSUUID UUID].UUIDString];
    [downloadIds addObject:identifier];
    return identifier;
}

- (id)ivar:(NSString *)name
{
    return [self.module valueForKey:name];
}

- (NSURLSession *)session
{
    return [self ivar:@"urlSession"];
}

- (id)lock
{
    return [self ivar:@"sharedLock"];
}

- (NSMutableDictionary<NSNumber *, RNBGDTaskConfig *> *)taskToConfigMap
{
    return [self ivar:@"taskToConfigMap"];
}

- (NSURLSessionDownloadTask *)taskForId:(NSString *)identifier
{
    @synchronized ([self lock]) {
        return ((NSDictionary *)[self ivar:@"idToTaskMap"])[identifier];
    }
}

- (void)waitFor:(BOOL (^)(void))condition description:(NSString *)description
{
    NSPredicate *predicate = [NSPredicate predicateWithBlock:^BOOL(id object, NSDictionary *bindings) {
        return condition();
    }];
    XCTNSPredicateExpectation *expectation = [[XCTNSPredicateExpectation alloc] initWithPredicate:predicate object:nil];
    expectation.expectationDescription = description;
    [self waitForExpectations:@[expectation] timeout:10];
}

// Waits for the session's queue to get past whatever the module just asked it for
- (void)drainSession:(NSURLSession *)session
{
    for (int i = 0; i < 2; i++) {
        XCTestExpectation *expectation = [self expectationWithDescription:@"getTasks"];
        [session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {
            [expectation fulfill];
        }];
        [self waitForExpectations:@[expectation] timeout:10];
    }
}

// A paused or restored download the module knows only by its saved config
- (RNBGDTaskConfig *)addConfigWithId:(NSString *)identifier key:(NSUInteger)key
{
    RNBGDTaskConfig *config = [[RNBGDTaskConfig alloc] initWithDictionary:@{
        @"id": identifier,
        @"url": kUnreachableURL,
        @"destination": [NSTemporaryDirectory() stringByAppendingPathComponent:identifier],
        @"metadata": @"{}",
    }];
    @synchronized ([self lock]) {
        self.taskToConfigMap[@(key)] = config;
    }
    return config;
}

- (void)download:(NSDictionary *)options
{
#ifdef RCT_NEW_ARCH_ENABLED
    JS::NativeRNBackgroundDownloader::SpecDownloadOptions specOptions(options);
    [self.module download:specOptions];
#else
    [self.module download:options];
#endif
}

- (NSString *)documentsPath:(NSString *)name
{
    return [[NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES) firstObject] stringByAppendingPathComponent:name];
}

- (NSArray<NSDictionary *> *)failures
{
    @synchronized (self.module.failures) {
        return [self.module.failures copy];
    }
}

#pragma mark - Cellular access

- (void)testDownloadKeepsItsCellularSettingOnTheRequestAndConfig
{
    NSString *identifier = [self uniqueId:@"cellular"];

    [self download:@{
        @"id": identifier,
        @"url": kUnreachableURL,
        @"destination": [self documentsPath:identifier],
        @"metadata": @"{}",
        @"isAllowedOverRoaming": @NO,
        @"isAllowedOverMetered": @NO,
    }];
    [self waitFor:^BOOL { return [self taskForId:identifier] != nil; } description:@"download task created"];

    NSURLSessionDownloadTask *task = [self taskForId:identifier];
    XCTAssertFalse(task.originalRequest.allowsCellularAccess);
    @synchronized ([self lock]) {
        XCTAssertFalse(self.taskToConfigMap[@(task.taskIdentifier)].allowsCellularAccess);
    }
    // The session stays open to cellular, so a task can allow it too
    XCTAssertTrue(self.session.configuration.allowsCellularAccess);
}

- (void)testDisallowingCellularGloballyKeepsTheSession
{
    NSURLSession *session = self.session;

    [self.module _setAllowsCellularAccessInternal:NO];
    [self drainSession:session];

    XCTAssertEqual(self.session, session);
    XCTAssertTrue(self.session.configuration.allowsCellularAccess);
}

#pragma mark - setMaxParallelDownloads

- (void)testMaxParallelDownloadsKeepsASessionWithTransfersInIt
{
    NSURLSession *session = self.session;
    NSURLSessionDownloadTask *task = [session downloadTaskWithURL:[NSURL URLWithString:kUnreachableURL]];
    NSInteger max = session.configuration.HTTPMaximumConnectionsPerHost + 1;

    [self.module _setMaxParallelDownloadsInternal:max];
    [self drainSession:session];

    XCTAssertEqual(self.session, session);
    XCTAssertEqual(task.state, NSURLSessionTaskStateSuspended);
    // Saved, so the session is created with it on the next launch
    XCTAssertEqual([[MMKV mmkvWithID:@"RNBackgroundDownloader"] getInt64ForKey:@"maxParallelDownloads"], max);
    [task cancel];
}

- (NSArray<NSURLSessionTask *> *)tasksInSession:(NSURLSession *)session
{
    __block NSArray<NSURLSessionTask *> *tasks = @[];
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [session getAllTasksWithCompletionHandler:^(NSArray<NSURLSessionTask *> *allTasks) {
        tasks = allTasks;
        dispatch_semaphore_signal(done);
    }];
    dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC));
    return tasks;
}

- (void)testMaxParallelDownloadsAppliesToAnIdleSession
{
    NSURLSession *session = self.session;
    // Tasks other tests left behind, or earlier runs: the session persists across launches
    for (NSURLSessionTask *task in [self tasksInSession:session]) {
        [task cancel];
    }
    [self waitFor:^BOOL {
        @synchronized ([self lock]) {
            if (((NSDictionary *)[self ivar:@"idToTaskMap"]).count > 0) {
                return NO;
            }
        }
        return [self tasksInSession:session].count == 0;
    } description:@"session idle"];
    NSInteger max = session.configuration.HTTPMaximumConnectionsPerHost + 2;

    [self.module _setMaxParallelDownloadsInternal:max];

    [self waitFor:^BOOL {
        NSURLSession *current = self.session;
        return current != nil && current != session && current.configuration.HTTPMaximumConnectionsPerHost == max;
    } description:@"session recreated with the new limit"];
    // A session created before the old one is gone throws on every task
    NSURLSessionDownloadTask *task = [self.session downloadTaskWithURL:[NSURL URLWithString:kUnreachableURL]];
    XCTAssertNotNil(task);
    [task cancel];
}

- (void)testExistingDownloadsCanBeListedRightAfterTheSessionIsReplaced
{
    NSURLSession *session = self.session;
    for (NSURLSessionTask *task in [self tasksInSession:session]) {
        [task cancel];
    }
    [self waitFor:^BOOL { return [self tasksInSession:session].count == 0; } description:@"session idle"];
    [self.module _setMaxParallelDownloadsInternal:session.configuration.HTTPMaximumConnectionsPerHost + 1];
    [self waitFor:^BOOL { return self.session != session; } description:@"old session released"];

    // As an app does on launch: setConfig, then getExistingDownloadTasks
    XCTestExpectation *listed = [self expectationWithDescription:@"getExistingDownloadTasks resolved"];
    [self.module getExistingDownloadTasks:^(id result) {
        [listed fulfill];
    } reject:^(NSString *code, NSString *message, NSError *error) {
        XCTFail(@"rejected: %@ %@", code, message);
        [listed fulfill];
    }];
    [self waitForExpectations:@[listed] timeout:10];
}

#pragma mark - Download setup failures

- (void)testDownloadWithAnInvalidDestinationFails
{
    NSString *identifier = [self uniqueId:@"bad-destination"];

    [self download:@{
        @"id": identifier,
        @"url": kUnreachableURL,
        @"destination": @"/nonexistent/file.zip",
        @"metadata": @"{}",
        @"isAllowedOverRoaming": @NO,
        @"isAllowedOverMetered": @YES,
    }];

    XCTAssertEqualObjects(self.failures.firstObject[@"id"], identifier);
}

- (void)testDownloadWithoutAUrlFails
{
    NSString *identifier = [self uniqueId:@"no-url"];

    [self download:@{
        @"id": identifier,
        @"destination": [self documentsPath:identifier],
        @"metadata": @"{}",
        @"isAllowedOverRoaming": @NO,
        @"isAllowedOverMetered": @YES,
    }];

    XCTAssertEqualObjects(self.failures.firstObject[@"id"], identifier);
}

#pragma mark - Resume

- (void)testResumeWithUpdatedHeadersDownloadsTheWholeFileAgain
{
    NSString *identifier = [self uniqueId:@"updated-headers"];
    RNBGDTaskConfig *config = [self addConfigWithId:identifier key:900001];
    config.bytesDownloaded = 100;
    config.allowsCellularAccess = NO;
    @synchronized ([self lock]) {
        ((NSMutableDictionary *)[self ivar:@"idToResumeDataMap"])[identifier] = [@"resume" dataUsingEncoding:NSUTF8StringEncoding];
    }

    [self.module updateTaskHeadersInternal:identifier
                                   headers:@{@"Authorization": @"Bearer new", @"Range": @"bytes=100-"}
                                   resolve:^(id result) {}
                                    reject:^(NSString *code, NSString *message, NSError *error) {}];
    [self.module resumeTaskInternal:identifier resolve:^(id result) {} reject:^(NSString *code, NSString *message, NSError *error) {}];

    NSURLRequest *request = [self taskForId:identifier].originalRequest;
    XCTAssertNotNil(request);
    // A Range on a fresh task would save only the rest of the file, as if it were all of it
    XCTAssertNil([request valueForHTTPHeaderField:@"Range"]);
    XCTAssertEqualObjects([request valueForHTTPHeaderField:@"Authorization"], @"Bearer new");
    XCTAssertEqualObjects([request valueForHTTPHeaderField:@"configId"], identifier);
    XCTAssertFalse(request.allowsCellularAccess);
    XCTAssertEqual(config.bytesDownloaded, 0);
    XCTAssertEqual(self.failures.count, 0u);
}

- (void)testResumeWithoutResumeDataDownloadsTheFileAgain
{
    NSString *identifier = [self uniqueId:@"no-resume-data"];
    [self addConfigWithId:identifier key:900002];

    [self.module resumeTaskInternal:identifier resolve:^(id result) {} reject:^(NSString *code, NSString *message, NSError *error) {}];

    NSURLSessionDownloadTask *task = [self taskForId:identifier];
    XCTAssertNotNil(task);
    XCTAssertEqualObjects(task.originalRequest.URL.absoluteString, kUnreachableURL);
    XCTAssertEqualObjects([task.originalRequest valueForHTTPHeaderField:@"configId"], identifier);
    @synchronized ([self lock]) {
        XCTAssertNil(self.taskToConfigMap[@900002]);
        XCTAssertEqualObjects(self.taskToConfigMap[@(task.taskIdentifier)].id, identifier);
    }
}

#pragma mark - Cancel by the system

- (void)testResumeDataFromACancelTheAppDidNotRequestIsPersisted
{
    NSString *identifier = [self uniqueId:@"system-cancel"];
    RNBGDTaskConfig *config = [self addConfigWithId:identifier key:900003];
    NSData *resumeData = [@"resume" dataUsingEncoding:NSUTF8StringEncoding];
    RNBGDFakeTask *task = [RNBGDFakeTask new];
    task.fakeIdentifier = 900003;
    NSError *error = [NSError errorWithDomain:NSURLErrorDomain code:NSURLErrorCancelled
                                     userInfo:@{NSURLSessionDownloadTaskResumeData: resumeData}];

    [self.module URLSession:self.session task:task didCompleteWithError:error];

    XCTAssertTrue(config.hasResumeData);
    XCTAssertEqual(config.state, NSURLSessionTaskStateCanceling);
    XCTAssertEqualObjects([self.module loadResumeDataForTaskId:identifier], resumeData);
}

#pragma mark - Restore

- (void)testRestoreKeepsASuspendedTask
{
    RNBGDFakeTask *task = [RNBGDFakeTask new];
    task.fakeState = NSURLSessionTaskStateSuspended;
    task.fakeReceived = 50;
    task.fakeExpected = 100;
    NSURLSessionDownloadTask *restored = task;

    [self.module restoreTaskIfNeeded:&restored];

    XCTAssertEqual(restored, task);
}

- (void)testRestoreReplacesAnUnfinishedTaskFromItsOriginalRequest
{
    NSMutableURLRequest *request = [NSMutableURLRequest requestWithURL:[NSURL URLWithString:kUnreachableURL]];
    [request setValue:@"Bearer token" forHTTPHeaderField:@"Authorization"];
    [request setValue:@"restored" forHTTPHeaderField:@"configId"];
    request.allowsCellularAccess = NO;
    RNBGDFakeTask *task = [RNBGDFakeTask new];
    task.fakeState = NSURLSessionTaskStateCompleted;
    task.fakeReceived = 50;
    task.fakeExpected = 100;
    task.fakeRequest = request;
    task.fakeError = [NSError errorWithDomain:NSURLErrorDomain code:NSURLErrorNetworkConnectionLost userInfo:nil];
    NSURLSessionDownloadTask *restored = task;

    [self.module restoreTaskIfNeeded:&restored];

    XCTAssertNotEqual(restored, task);
    XCTAssertEqualObjects([restored.originalRequest valueForHTTPHeaderField:@"Authorization"], @"Bearer token");
    XCTAssertEqualObjects([restored.originalRequest valueForHTTPHeaderField:@"configId"], @"restored");
    XCTAssertFalse(restored.originalRequest.allowsCellularAccess);
    [restored cancel];
}

#pragma mark - Uploads

- (RNBGDUploadTaskConfig *)addUploadConfigWithId:(NSString *)identifier key:(NSUInteger)key
{
    [uploadIds addObject:identifier];
    RNBGDUploadTaskConfig *config = [[RNBGDUploadTaskConfig alloc] initWithDictionary:@{
        @"id": identifier,
        @"url": kUnreachableURL,
        @"source": [NSTemporaryDirectory() stringByAppendingPathComponent:identifier],
    }];
    @synchronized ([self lock]) {
        ((NSMutableDictionary *)[self ivar:@"uploadTaskToConfigMap"])[@(key)] = config;
    }
    return config;
}

- (void)testAFinishedUploadDeletesItsMultipartBody
{
    NSString *identifier = [NSString stringWithFormat:@"multipart-%@", [NSUUID UUID].UUIDString];
    [self addUploadConfigWithId:identifier key:900004];
    NSString *bodyPath = [self.module multipartTempPathForUploadId:identifier];
    [[NSFileManager defaultManager] createFileAtPath:bodyPath contents:[NSData dataWithBytes:"body" length:4] attributes:nil];
    RNBGDFakeTask *task = [RNBGDFakeTask new];
    task.fakeIdentifier = 900004;

    [self.module URLSession:self.session task:task didCompleteWithError:[NSError errorWithDomain:NSURLErrorDomain code:NSURLErrorTimedOut userInfo:nil]];

    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:bodyPath]);
}

- (void)testStoppingAnUploadWithoutALiveTaskRemovesItsConfig
{
    NSString *identifier = [NSString stringWithFormat:@"restored-upload-%@", [NSUUID UUID].UUIDString];
    [self addUploadConfigWithId:identifier key:900005];

    [self.module stopUploadTaskInternal:identifier resolve:^(id result) {} reject:^(NSString *code, NSString *message, NSError *error) {}];

    @synchronized ([self lock]) {
        XCTAssertNil(((NSDictionary *)[self ivar:@"uploadTaskToConfigMap"])[@900005]);
    }
}

@end

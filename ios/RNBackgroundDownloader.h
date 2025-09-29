#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#ifdef RCT_NEW_ARCH_ENABLED
#import <RNBackgroundDownloaderSpec/RNBackgroundDownloaderSpec.h>
#endif

typedef void (^CompletionHandler)();

@interface RNBackgroundDownloader : RCTEventEmitter
#ifdef RCT_NEW_ARCH_ENABLED
   <NativeRNBackgroundDownloaderSpec, NSURLSessionDelegate, NSURLSessionDownloadDelegate>
#else
    <RCTBridgeModule, NSURLSessionDelegate, NSURLSessionDownloadDelegate>
#endif

+ (void)setCompletionHandlerWithIdentifier:(NSString *)identifier completionHandler:(CompletionHandler)completionHandler;
- (void)completeHandler:(NSString *)jobId;

@end

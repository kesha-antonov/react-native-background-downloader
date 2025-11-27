#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

#ifdef RCT_NEW_ARCH_ENABLED
#import <RNBackgroundDownloaderSpec/RNBackgroundDownloaderSpec.h>
#endif

typedef void (^CompletionHandler)();

#ifdef RCT_NEW_ARCH_ENABLED
@interface RNBackgroundDownloader : RCTEventEmitter <NativeRNBackgroundDownloaderSpec, NSURLSessionDelegate, NSURLSessionDownloadDelegate>
#else
@interface RNBackgroundDownloader : RCTEventEmitter <RCTBridgeModule, NSURLSessionDelegate, NSURLSessionDownloadDelegate>
#endif

+ (void)setCompletionHandlerWithIdentifier:(NSString *)identifier completionHandler:(CompletionHandler)completionHandler;

@end

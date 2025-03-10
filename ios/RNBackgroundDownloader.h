#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>
#ifdef RCT_NEW_ARCH_ENABLED
#import <RNBackgroundDownloaderSpec/RNBackgroundDownloaderSpec.h>
#endif

typedef void (^CompletionHandler)();

@interface RNBackgroundDownloader : RCTEventEmitter <RCTBridgeModule, NSURLSessionDelegate, NSURLSessionDownloadDelegate>

+ (void)setCompletionHandlerWithIdentifier:(NSString *)identifier completionHandler:(CompletionHandler)completionHandler;

@end

#ifdef RCT_NEW_ARCH_ENABLED
@interface RNBackgroundDownloader () <RNBackgroundDownloaderSpec>

@end
#endif

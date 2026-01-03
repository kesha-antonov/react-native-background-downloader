#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

#ifdef RCT_NEW_ARCH_ENABLED
#import <RNBackgroundDownloaderSpec/RNBackgroundDownloaderSpec.h>
#endif

NS_ASSUME_NONNULL_BEGIN

typedef void (^CompletionHandler)(void);

#ifdef RCT_NEW_ARCH_ENABLED
// Use the generated base class for new architecture which provides emit* methods
// NSURLSessionDataDelegate is used for upload progress tracking
@interface RNBackgroundDownloader : NativeRNBackgroundDownloaderSpecBase <NativeRNBackgroundDownloaderSpec, NSURLSessionDelegate, NSURLSessionDownloadDelegate, NSURLSessionDataDelegate, NSURLSessionTaskDelegate>
#else
// NSURLSessionDataDelegate is used for upload progress tracking
@interface RNBackgroundDownloader : RCTEventEmitter <RCTBridgeModule, NSURLSessionDelegate, NSURLSessionDownloadDelegate, NSURLSessionDataDelegate, NSURLSessionTaskDelegate>
#endif

+ (void)setCompletionHandlerWithIdentifier:(NSString *)identifier completionHandler:(nullable CompletionHandler)completionHandler;

@end

NS_ASSUME_NONNULL_END

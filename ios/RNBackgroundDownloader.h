#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

typedef void (^CompletionHandler)();

@interface RNBackgroundDownloader : RCTEventEmitter <RCTBridgeModule, NSURLSessionDelegate, NSURLSessionDownloadDelegate>

+ (void)setCompletionHandlerWithIdentifier:(NSString *)identifier completionHandler:(CompletionHandler)completionHandler;

@end

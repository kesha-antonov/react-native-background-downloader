#import <React/RCTInvalidating.h>
#import <RNBackgroundDownloaderSpec/RNBackgroundDownloaderSpec.h>

NS_ASSUME_NONNULL_BEGIN

@interface RNBackgroundDownloader : NativeRNBackgroundDownloaderSpecBase <NativeRNBackgroundDownloaderSpec, RCTInvalidating, NSURLSessionDownloadDelegate, NSURLSessionDataDelegate, NSURLSessionTaskDelegate>

@end

NS_ASSUME_NONNULL_END

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface RNBGDTaskConfig : NSObject

@property (nonatomic, copy) NSString *id;
@property (nonatomic, copy) NSString *url;
@property (nonatomic, copy) NSString *destination;
@property (nonatomic, copy) NSString *metadata;
@property (nonatomic, copy) NSDictionary<NSString *, NSString *> *headers;
@property (nonatomic, assign) BOOL reportedBegin;
@property (nonatomic, assign) int64_t bytesDownloaded;
@property (nonatomic, assign) int64_t bytesTotal;
@property (nonatomic, assign) NSInteger state;
@property (nonatomic, assign) NSInteger errorCode;

- (instancetype)initWithDictionary:(NSDictionary *)dictionary;

@end

NS_ASSUME_NONNULL_END

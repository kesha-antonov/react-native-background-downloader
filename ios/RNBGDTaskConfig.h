#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface RNBGDTaskConfig : NSObject <NSCoding, NSSecureCoding>

@property (nonatomic, copy) NSString *id;
@property (nonatomic, copy) NSString *url;
@property (nonatomic, copy) NSString *destination;
@property (nonatomic, copy) NSString *metadata;
@property (nonatomic, assign) BOOL reportedBegin;
@property (nonatomic, assign) long long bytesDownloaded;
@property (nonatomic, assign) long long bytesTotal;
@property (nonatomic, assign) NSInteger state;
@property (nonatomic, assign) NSInteger errorCode;

- (instancetype)initWithDictionary:(NSDictionary *)dict;

@end

NS_ASSUME_NONNULL_END

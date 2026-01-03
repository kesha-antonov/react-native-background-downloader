#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface RNBGDUploadTaskConfig : NSObject <NSCoding, NSSecureCoding>

@property (nonatomic, copy) NSString *id;
@property (nonatomic, copy) NSString *url;
@property (nonatomic, copy) NSString *source;
@property (nonatomic, copy) NSString *method;
@property (nonatomic, copy) NSString *metadata;
@property (nonatomic, copy, nullable) NSString *fieldName;
@property (nonatomic, copy, nullable) NSString *mimeType;
@property (nonatomic, copy, nullable) NSDictionary<NSString *, NSString *> *parameters;
@property (nonatomic, assign) BOOL reportedBegin;
@property (nonatomic, assign) long long bytesUploaded;
@property (nonatomic, assign) long long bytesTotal;
@property (nonatomic, assign) NSInteger state;
@property (nonatomic, assign) NSInteger errorCode;
// Stores the response body when upload completes
@property (nonatomic, strong, nullable) NSMutableData *responseData;

- (instancetype)initWithDictionary:(NSDictionary *)dict;

@end

NS_ASSUME_NONNULL_END

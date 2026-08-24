#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface RNBGDUploadTaskConfig : NSObject

@property (nonatomic, copy) NSString *id;
@property (nonatomic, copy) NSString *url;
@property (nonatomic, copy) NSString *source;
@property (nonatomic, copy) NSString *method;
@property (nonatomic, copy) NSString *metadata;
@property (nonatomic, copy) NSDictionary<NSString *, NSString *> *headers;
@property (nonatomic, copy, nullable) NSString *fieldName;
@property (nonatomic, copy, nullable) NSString *mimeType;
@property (nonatomic, copy, nullable) NSDictionary<NSString *, NSString *> *parameters;
@property (nonatomic, copy, nullable) NSString *temporaryBodyPath;
@property (nonatomic, assign) BOOL reportedBegin;
@property (nonatomic, assign) int64_t bytesUploaded;
@property (nonatomic, assign) int64_t bytesTotal;
@property (nonatomic, assign) NSInteger state;
@property (nonatomic, assign) NSInteger errorCode;
@property (nonatomic, strong) NSMutableData *responseData;

- (instancetype)initWithDictionary:(NSDictionary *)dictionary;

@end

NS_ASSUME_NONNULL_END

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

// Upload source in parts, one URL per part (S3 multipart upload and similar)
@property (nonatomic, copy, nullable) NSArray<NSString *> *partUrls;
// Bytes per part; 0 means ceil(file size / number of parts)
@property (nonatomic, assign) long long partSize;
// Session task identifier -> 0-based part index, for the part tasks still running
@property (nonatomic, copy, nullable) NSDictionary<NSNumber *, NSNumber *> *partIndexByTask;
// 0-based part index -> result (partNumber, responseCode, responseBody, responseHeaders, bytes)
@property (nonatomic, copy, nullable) NSDictionary<NSNumber *, NSDictionary *> *partResults;

// The response of a finished upload, kept until JS has it
@property (nonatomic, assign) NSInteger responseCode;
@property (nonatomic, copy, nullable) NSString *responseBody;
@property (nonatomic, copy, nullable) NSDictionary<NSString *, NSString *> *responseHeaders;

- (instancetype)initWithDictionary:(NSDictionary *)dict;

/**
 * Byte ranges of the parts as [offset, length] pairs: every part `partSize` bytes
 * (default ceil(fileSize / partCount)) and the last one what's left. Nil when the parts
 * can't cover the file exactly, which includes an empty last part.
 */
+ (nullable NSArray<NSArray<NSNumber *> *> *)partRangesForFileSize:(long long)fileSize
                                                         partCount:(NSUInteger)partCount
                                                          partSize:(long long)partSize;

// Results of the accepted parts in part order, as sent to JS
- (NSArray<NSDictionary *> *)orderedPartResults;

@end

NS_ASSUME_NONNULL_END

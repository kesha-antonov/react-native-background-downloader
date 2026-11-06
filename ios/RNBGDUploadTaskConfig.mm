#import "RNBGDUploadTaskConfig.h"

@implementation RNBGDUploadTaskConfig

+ (BOOL)supportsSecureCoding
{
    return YES;
}

static NSString *_Nullable RNBGDStringOrNil(id value)
{
    if ([value isKindOfClass:[NSString class]]) return value;
    if (value == nil || value == [NSNull null]) return nil;
    return [value description];
}

static NSDictionary<NSString *, NSString *> *_Nullable RNBGDStringParameters(id value)
{
    if (![value isKindOfClass:[NSDictionary class]]) return nil;
    NSMutableDictionary<NSString *, NSString *> *parameters = [NSMutableDictionary dictionary];
    [(NSDictionary *)value enumerateKeysAndObjectsUsingBlock:^(id key, id obj, BOOL *stop) {
        NSString *string = RNBGDStringOrNil(obj);
        if ([key isKindOfClass:[NSString class]] && string != nil) parameters[key] = string;
    }];
    return parameters;
}

- (instancetype)initWithDictionary:(NSDictionary *)dict
{
    self = [super init];
    if (self)
    {
        self.id = dict[@"id"];
        self.url = dict[@"url"];
        self.source = dict[@"source"];
        self.method = dict[@"method"] ?: @"POST";
        self.metadata = dict[@"metadata"] ?: @"{}";
        // NSNull (a missing option) or a number would be archived as is, and then the
        // whole saved upload map fails to decode with the NSString/NSDictionary allowlist
        self.fieldName = RNBGDStringOrNil(dict[@"fieldName"]);
        self.mimeType = RNBGDStringOrNil(dict[@"mimeType"]);
        self.parameters = RNBGDStringParameters(dict[@"parameters"]);
        id partUrls = dict[@"partUrls"];
        self.partUrls = [partUrls isKindOfClass:[NSArray class]] ? partUrls : nil;
        self.partSize = [dict[@"partSize"] isKindOfClass:[NSNumber class]] ? [dict[@"partSize"] longLongValue] : 0;
        self.reportedBegin = NO;
        self.bytesUploaded = 0;
        self.bytesTotal = 0;
        self.state = NSURLSessionTaskStateRunning;
        self.errorCode = 0;
        self.responseData = nil;
    }

    return self;
}

- (void)encodeWithCoder:(nonnull NSCoder *)aCoder
{
    [aCoder encodeObject:self.id forKey:@"id"];
    [aCoder encodeObject:self.url forKey:@"url"];
    [aCoder encodeObject:self.source forKey:@"source"];
    [aCoder encodeObject:self.method forKey:@"method"];
    [aCoder encodeObject:self.metadata forKey:@"metadata"];
    [aCoder encodeObject:self.fieldName forKey:@"fieldName"];
    [aCoder encodeObject:self.mimeType forKey:@"mimeType"];
    [aCoder encodeObject:self.parameters forKey:@"parameters"];
    [aCoder encodeBool:self.reportedBegin forKey:@"reportedBegin"];
    [aCoder encodeInt64:self.bytesUploaded forKey:@"bytesUploaded"];
    [aCoder encodeInt64:self.bytesTotal forKey:@"bytesTotal"];
    [aCoder encodeInteger:self.state forKey:@"state"];
    [aCoder encodeInteger:self.errorCode forKey:@"errorCode"];
    [aCoder encodeObject:self.partUrls forKey:@"partUrls"];
    [aCoder encodeInt64:self.partSize forKey:@"partSize"];
    [aCoder encodeObject:self.partIndexByTask forKey:@"partIndexByTask"];
    [aCoder encodeObject:self.partResults forKey:@"partResults"];
    [aCoder encodeInteger:self.responseCode forKey:@"responseCode"];
    [aCoder encodeObject:self.responseBody forKey:@"responseBody"];
    [aCoder encodeObject:self.responseHeaders forKey:@"responseHeaders"];
    // Note: responseData is not persisted as it's only needed during active upload
}

- (nullable instancetype)initWithCoder:(nonnull NSCoder *)aDecoder
{
    self = [super init];
    if (self)
    {
        // Use type-safe decoding (available since iOS 6, required for secure coding)
        self.id = [aDecoder decodeObjectOfClass:[NSString class] forKey:@"id"];
        self.url = [aDecoder decodeObjectOfClass:[NSString class] forKey:@"url"];
        self.source = [aDecoder decodeObjectOfClass:[NSString class] forKey:@"source"];
        NSString *method = [aDecoder decodeObjectOfClass:[NSString class] forKey:@"method"];
        self.method = method != nil ? method : @"POST";
        NSString *metadata = [aDecoder decodeObjectOfClass:[NSString class] forKey:@"metadata"];
        self.metadata = metadata != nil ? metadata : @"{}";
        self.fieldName = [aDecoder decodeObjectOfClass:[NSString class] forKey:@"fieldName"];
        self.mimeType = [aDecoder decodeObjectOfClass:[NSString class] forKey:@"mimeType"];
        // Use set of allowed classes for NSDictionary
        NSSet *dictClasses = [NSSet setWithObjects:[NSDictionary class], [NSString class], nil];
        self.parameters = [aDecoder decodeObjectOfClasses:dictClasses forKey:@"parameters"];
        self.reportedBegin = [aDecoder decodeBoolForKey:@"reportedBegin"];
        self.bytesUploaded = [aDecoder decodeInt64ForKey:@"bytesUploaded"];
        self.bytesTotal = [aDecoder decodeInt64ForKey:@"bytesTotal"];
        self.state = [aDecoder decodeIntegerForKey:@"state"];
        self.errorCode = [aDecoder decodeIntegerForKey:@"errorCode"];
        self.responseData = nil;
        self.partUrls = [aDecoder decodeObjectOfClasses:[NSSet setWithObjects:[NSArray class], [NSString class], nil] forKey:@"partUrls"];
        self.partSize = [aDecoder decodeInt64ForKey:@"partSize"];
        self.partIndexByTask = [aDecoder decodeObjectOfClasses:[NSSet setWithObjects:[NSDictionary class], [NSNumber class], nil] forKey:@"partIndexByTask"];
        self.partResults = [aDecoder decodeObjectOfClasses:[NSSet setWithObjects:[NSDictionary class], [NSNumber class], [NSString class], nil] forKey:@"partResults"];
        self.responseCode = [aDecoder decodeIntegerForKey:@"responseCode"];
        self.responseBody = [aDecoder decodeObjectOfClass:[NSString class] forKey:@"responseBody"];
        self.responseHeaders = [aDecoder decodeObjectOfClasses:[NSSet setWithObjects:[NSDictionary class], [NSString class], nil] forKey:@"responseHeaders"];
    }

    return self;
}

+ (NSArray<NSArray<NSNumber *> *> *)partRangesForFileSize:(long long)fileSize
                                                partCount:(NSUInteger)partCount
                                                 partSize:(long long)partSize
{
    if (partCount == 0 || fileSize <= 0) return nil;
    long long count = (long long)partCount;
    long long size = partSize > 0 ? partSize : (fileSize + count - 1) / count;
    long long beforeLast = size * (count - 1);
    if (beforeLast >= fileSize || fileSize - beforeLast > size) return nil;

    NSMutableArray<NSArray<NSNumber *> *> *ranges = [NSMutableArray arrayWithCapacity:partCount];
    for (long long index = 0; index < count; index++) {
        long long offset = size * index;
        long long length = index == count - 1 ? fileSize - offset : size;
        [ranges addObject:@[@(offset), @(length)]];
    }
    return ranges;
}

- (NSArray<NSDictionary *> *)orderedPartResults
{
    NSArray<NSNumber *> *indexes = [self.partResults.allKeys sortedArrayUsingSelector:@selector(compare:)];
    NSMutableArray<NSDictionary *> *results = [NSMutableArray arrayWithCapacity:indexes.count];
    for (NSNumber *index in indexes) {
        [results addObject:self.partResults[index]];
    }
    return results;
}

@end

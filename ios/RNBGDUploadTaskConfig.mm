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
    }

    return self;
}

@end

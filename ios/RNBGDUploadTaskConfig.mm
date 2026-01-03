#import "RNBGDUploadTaskConfig.h"

@implementation RNBGDUploadTaskConfig

+ (BOOL)supportsSecureCoding
{
    return YES;
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
        self.fieldName = dict[@"fieldName"];
        self.mimeType = dict[@"mimeType"];
        self.parameters = dict[@"parameters"];
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
        self.id = [aDecoder decodeObjectForKey:@"id"];
        self.url = [aDecoder decodeObjectForKey:@"url"];
        self.source = [aDecoder decodeObjectForKey:@"source"];
        NSString *method = [aDecoder decodeObjectForKey:@"method"];
        self.method = method != nil ? method : @"POST";
        NSString *metadata = [aDecoder decodeObjectForKey:@"metadata"];
        self.metadata = metadata != nil ? metadata : @"{}";
        self.fieldName = [aDecoder decodeObjectForKey:@"fieldName"];
        self.mimeType = [aDecoder decodeObjectForKey:@"mimeType"];
        self.parameters = [aDecoder decodeObjectForKey:@"parameters"];
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

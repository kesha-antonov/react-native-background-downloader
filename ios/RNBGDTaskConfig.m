#import "RNBGDTaskConfig.h"

@implementation RNBGDTaskConfig

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
        self.destination = dict[@"destination"];
        self.metadata = dict[@"metadata"];
        self.reportedBegin = NO;
        self.bytesDownloaded = 0;
        self.bytesTotal = 0;
        self.state = NSURLSessionTaskStateRunning;
        self.errorCode = 0;
    }

    return self;
}

- (void)encodeWithCoder:(nonnull NSCoder *)aCoder
{
    [aCoder encodeObject:self.id forKey:@"id"];
    [aCoder encodeObject:self.url forKey:@"url"];
    [aCoder encodeObject:self.destination forKey:@"destination"];
    [aCoder encodeObject:self.metadata forKey:@"metadata"];
    [aCoder encodeBool:self.reportedBegin forKey:@"reportedBegin"];
    [aCoder encodeInt64:self.bytesDownloaded forKey:@"bytesDownloaded"];
    [aCoder encodeInt64:self.bytesTotal forKey:@"bytesTotal"];
    [aCoder encodeInteger:self.state forKey:@"state"];
    [aCoder encodeInteger:self.errorCode forKey:@"errorCode"];
}

- (nullable instancetype)initWithCoder:(nonnull NSCoder *)aDecoder
{
    self = [super init];
    if (self)
    {
        self.id = [aDecoder decodeObjectForKey:@"id"];
        self.url = [aDecoder decodeObjectForKey:@"url"];
        self.destination = [aDecoder decodeObjectForKey:@"destination"];
        NSString *metadata = [aDecoder decodeObjectForKey:@"metadata"];
        self.metadata = metadata != nil ? metadata : @"{}";
        self.reportedBegin = [aDecoder decodeBoolForKey:@"reportedBegin"];
        self.bytesDownloaded = [aDecoder decodeInt64ForKey:@"bytesDownloaded"];
        self.bytesTotal = [aDecoder decodeInt64ForKey:@"bytesTotal"];
        self.state = [aDecoder decodeIntegerForKey:@"state"];
        self.errorCode = [aDecoder decodeIntegerForKey:@"errorCode"];
    }

    return self;
}

@end

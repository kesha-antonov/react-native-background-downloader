#import "RNBGDTaskConfig.h"

@implementation RNBGDTaskConfig

- (instancetype)initWithDictionary:(NSDictionary *)dictionary
{
    self = [super init];
    if (self) {
        self.id = dictionary[@"id"];
        self.url = dictionary[@"url"];
        self.destination = dictionary[@"destination"];
        self.metadata = dictionary[@"metadata"] ?: @"{}";
        self.headers = dictionary[@"headers"] ?: @{};
        self.expectedSha256 = dictionary[@"expectedSha256"];
        self.state = NSURLSessionTaskStateRunning;
        self.bytesTotal = -1;
    }
    return self;
}

@end

#import "RNBGDUploadTaskConfig.h"

@implementation RNBGDUploadTaskConfig

- (instancetype)initWithDictionary:(NSDictionary *)dictionary
{
    self = [super init];
    if (self) {
        self.id = dictionary[@"id"];
        self.url = dictionary[@"url"];
        self.source = dictionary[@"source"];
        self.method = dictionary[@"method"] ?: @"POST";
        self.metadata = dictionary[@"metadata"] ?: @"{}";
        self.headers = dictionary[@"headers"] ?: @{};
        self.fieldName = [dictionary[@"fieldName"] isKindOfClass:[NSString class]] ? dictionary[@"fieldName"] : nil;
        self.mimeType = [dictionary[@"mimeType"] isKindOfClass:[NSString class]] ? dictionary[@"mimeType"] : nil;
        self.parameters = [dictionary[@"parameters"] isKindOfClass:[NSDictionary class]] ? dictionary[@"parameters"] : nil;
        self.state = NSURLSessionTaskStateRunning;
        self.responseData = [[NSMutableData alloc] init];
    }
    return self;
}

@end

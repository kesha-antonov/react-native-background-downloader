#import <Foundation/Foundation.h>

@interface RNBGDTaskConfig : NSObject <NSCoding, NSSecureCoding>

@property NSString *_Nonnull id;
@property NSString *_Nonnull url;
@property NSString *_Nonnull destination;
@property NSString *_Nonnull metadata;
@property BOOL reportedBegin;

- (id _Nullable)initWithDictionary:(NSDictionary *_Nonnull)dict;

@end

@implementation RNBGDTaskConfig

+ (BOOL)supportsSecureCoding
{
    return YES;
}

- (id _Nullable)initWithDictionary:(NSDictionary *_Nonnull)dict
{
    self = [super init];
    if (self)
    {
        self.id = dict[@"id"];
        self.url = dict[@"url"];
        self.destination = dict[@"destination"];
        self.metadata = dict[@"metadata"];
        self.reportedBegin = NO;
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
    }

    return self;
}

@end

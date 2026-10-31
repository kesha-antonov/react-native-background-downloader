#import <XCTest/XCTest.h>
#import "RNBGDTaskConfig.h"

// Encodes the fields a config had before allowsCellularAccess was added
@interface RNBGDLegacyTaskConfig : NSObject <NSSecureCoding>
@end

@implementation RNBGDLegacyTaskConfig

+ (BOOL)supportsSecureCoding
{
    return YES;
}

- (void)encodeWithCoder:(NSCoder *)coder
{
    [coder encodeObject:@"legacy" forKey:@"id"];
    [coder encodeObject:@"https://example.com/file" forKey:@"url"];
    [coder encodeObject:@"/tmp/file" forKey:@"destination"];
    [coder encodeObject:@"{}" forKey:@"metadata"];
    [coder encodeBool:YES forKey:@"hasResumeData"];
}

- (instancetype)initWithCoder:(NSCoder *)coder
{
    return [super init];
}

@end

@interface RNBGDTaskConfigTests : XCTestCase
@end

@implementation RNBGDTaskConfigTests

- (RNBGDTaskConfig *)unarchive:(NSData *)data
{
    NSError *error = nil;
    RNBGDTaskConfig *config = [NSKeyedUnarchiver unarchivedObjectOfClass:[RNBGDTaskConfig class] fromData:data error:&error];
    XCTAssertNil(error);
    return config;
}

- (void)testCellularAccessDefaultsToAllowed
{
    XCTAssertTrue([[RNBGDTaskConfig alloc] initWithDictionary:@{@"id": @"a"}].allowsCellularAccess);
}

- (void)testDisallowedCellularAccessSurvivesArchiving
{
    RNBGDTaskConfig *config = [[RNBGDTaskConfig alloc] initWithDictionary:@{@"id": @"a", @"url": @"https://example.com", @"destination": @"/tmp/a"}];
    config.allowsCellularAccess = NO;

    NSData *data = [NSKeyedArchiver archivedDataWithRootObject:config requiringSecureCoding:YES error:nil];

    XCTAssertFalse([self unarchive:data].allowsCellularAccess);
}

- (void)testConfigSavedByAnOlderVersionAllowsCellular
{
    NSKeyedArchiver *archiver = [[NSKeyedArchiver alloc] initRequiringSecureCoding:YES];
    [archiver setClassName:@"RNBGDTaskConfig" forClass:[RNBGDLegacyTaskConfig class]];
    [archiver encodeObject:[RNBGDLegacyTaskConfig new] forKey:NSKeyedArchiveRootObjectKey];
    [archiver finishEncoding];

    RNBGDTaskConfig *config = [self unarchive:archiver.encodedData];

    XCTAssertEqualObjects(config.id, @"legacy");
    XCTAssertTrue(config.hasResumeData);
    XCTAssertTrue(config.allowsCellularAccess);
}

@end

#import <XCTest/XCTest.h>
#import "RNBGDUploadTaskConfig.h"

// The saved upload map has to decode with the classes the module allows. A config
// that archived NSNull (a missing option) or a number made the whole map unreadable,
// so after a relaunch every running background upload was cancelled.
@interface RNBGDUploadTaskConfigTests : XCTestCase
@end

@implementation RNBGDUploadTaskConfigTests

- (RNBGDUploadTaskConfig *)roundTrip:(NSDictionary *)options
{
    RNBGDUploadTaskConfig *config = [[RNBGDUploadTaskConfig alloc] initWithDictionary:options];
    NSMutableDictionary *map = [@{@1: config} mutableCopy];

    NSError *error = nil;
    NSData *data = [NSKeyedArchiver archivedDataWithRootObject:map requiringSecureCoding:YES error:&error];
    XCTAssertNil(error);

    // The same allowlist as deserializeUploadConfig:
    NSSet *classes = [NSSet setWithObjects:[RNBGDUploadTaskConfig class], [NSMutableDictionary class],
                      [NSNumber class], [NSString class], [NSDictionary class], nil];
    NSDictionary *decoded = [NSKeyedUnarchiver unarchivedObjectOfClasses:classes fromData:data error:&error];
    XCTAssertNil(error);
    return decoded[@1];
}

- (NSDictionary *)optionsWith:(NSDictionary *)extra
{
    NSMutableDictionary *options = [@{
        @"id": @"upload",
        @"url": @"https://example.com/upload",
        @"source": @"/tmp/file",
        @"method": @"POST",
        @"metadata": @"{}",
    } mutableCopy];
    [options addEntriesFromDictionary:extra];
    return options;
}

- (void)testMissingOptionsDecode
{
    RNBGDUploadTaskConfig *config = [self roundTrip:[self optionsWith:@{
        @"fieldName": [NSNull null],
        @"mimeType": [NSNull null],
        @"parameters": [NSNull null],
    }]];

    XCTAssertEqualObjects(config.id, @"upload");
    XCTAssertNil(config.fieldName);
    XCTAssertNil(config.mimeType);
    XCTAssertNil(config.parameters);
}

- (void)testNumericParametersDecodeAsStrings
{
    RNBGDUploadTaskConfig *config = [self roundTrip:[self optionsWith:@{
        @"fieldName": @"file",
        @"parameters": @{@"count": @3, @"name": @"photo"},
    }]];

    XCTAssertEqualObjects(config.parameters, (@{@"count": @"3", @"name": @"photo"}));
}

@end

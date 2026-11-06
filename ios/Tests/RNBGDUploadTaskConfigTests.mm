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

- (void)testPartRangesDefaultToTheCeilingTheLastPartTakingTheRest
{
    XCTAssertEqualObjects([RNBGDUploadTaskConfig partRangesForFileSize:10 partCount:3 partSize:0],
                          (@[@[@0, @4], @[@4, @4], @[@8, @2]]));
    XCTAssertEqualObjects([RNBGDUploadTaskConfig partRangesForFileSize:10 partCount:1 partSize:0], (@[@[@0, @10]]));
}

- (void)testPartRangesMustCoverTheFileExactly
{
    XCTAssertEqualObjects([RNBGDUploadTaskConfig partRangesForFileSize:7 partCount:2 partSize:5], (@[@[@0, @5], @[@5, @2]]));
    // The last part would be bigger than the part size
    XCTAssertNil([RNBGDUploadTaskConfig partRangesForFileSize:11 partCount:2 partSize:5]);
    // The last part would be empty
    XCTAssertNil([RNBGDUploadTaskConfig partRangesForFileSize:5 partCount:2 partSize:5]);
    XCTAssertNil([RNBGDUploadTaskConfig partRangesForFileSize:9 partCount:4 partSize:0]);
    XCTAssertNil([RNBGDUploadTaskConfig partRangesForFileSize:0 partCount:2 partSize:0]);
}

- (void)testPartsAndTheResponseSurviveArchiving
{
    RNBGDUploadTaskConfig *config = [[RNBGDUploadTaskConfig alloc] initWithDictionary:[self optionsWith:@{
        @"partUrls": @[@"https://example.com/1", @"https://example.com/2"],
        @"partSize": @5,
    }]];
    config.partIndexByTask = @{@7: @1};
    config.partResults = @{@0: @{@"partNumber": @1, @"responseCode": @200, @"responseBody": @"",
                                 @"responseHeaders": @{@"etag": @"\"a\""}, @"bytes": @5}};
    config.responseCode = 200;
    config.responseHeaders = @{@"etag": @"\"a\""};

    NSError *error = nil;
    NSData *data = [NSKeyedArchiver archivedDataWithRootObject:[@{@7: config} mutableCopy] requiringSecureCoding:YES error:&error];
    NSSet *classes = [NSSet setWithObjects:[RNBGDUploadTaskConfig class], [NSMutableDictionary class],
                      [NSNumber class], [NSString class], [NSDictionary class], [NSArray class], nil];
    RNBGDUploadTaskConfig *decoded = [NSKeyedUnarchiver unarchivedObjectOfClasses:classes fromData:data error:&error][@7];

    XCTAssertNil(error);
    XCTAssertEqualObjects(decoded.partUrls, config.partUrls);
    XCTAssertEqual(decoded.partSize, 5);
    XCTAssertEqualObjects(decoded.partIndexByTask, config.partIndexByTask);
    XCTAssertEqualObjects([decoded orderedPartResults].firstObject[@"responseHeaders"][@"etag"], @"\"a\"");
    XCTAssertEqualObjects(decoded.responseHeaders, config.responseHeaders);
    XCTAssertEqual(decoded.responseCode, 200);
}

@end

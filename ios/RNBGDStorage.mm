#import "RNBGDStorage.h"
#import <math.h>

@implementation RNBGDStorage {
    NSUserDefaults *_defaults;
}

+ (instancetype)storageWithID:(NSString *)storageID {
    return [[self alloc] initWithID:storageID];
}

- (instancetype)initWithID:(NSString *)storageID {
    self = [super init];
    if (self) {
        _defaults = [[NSUserDefaults alloc] initWithSuiteName:storageID];
        if (_defaults == nil) {
            // Suite creation failed (should not happen in practice) - fall back
            // to the standard defaults domain rather than crash.
            _defaults = [NSUserDefaults standardUserDefaults];
        }
    }
    return self;
}

- (nullable NSData *)getDataForKey:(NSString *)key {
    return [_defaults dataForKey:key];
}

- (void)setData:(NSData *)data forKey:(NSString *)key {
    [_defaults setObject:data forKey:key];
}

- (float)getFloatForKey:(NSString *)key {
    if ([_defaults objectForKey:key] == nil) {
        return NAN;
    }
    return [_defaults floatForKey:key];
}

- (void)setFloat:(float)value forKey:(NSString *)key {
    [_defaults setFloat:value forKey:key];
}

- (int64_t)getInt64ForKey:(NSString *)key {
    return (int64_t)[_defaults integerForKey:key];
}

- (void)setInt64:(int64_t)value forKey:(NSString *)key {
    [_defaults setInteger:(NSInteger)value forKey:key];
}

@end

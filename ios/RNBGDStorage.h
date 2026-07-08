#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

// Persistent key/value storage backed by NSUserDefaults. Exposes the same
// method shape the library previously got from MMKV so callers needed only
// a variable rename when MMKV was removed.
@interface RNBGDStorage : NSObject

+ (instancetype)storageWithID:(NSString *)storageID;

- (nullable NSData *)getDataForKey:(NSString *)key;
- (void)setData:(NSData *)data forKey:(NSString *)key;

// Returns NAN if the key has never been set (matches MMKV's prior behavior).
- (float)getFloatForKey:(NSString *)key;
- (void)setFloat:(float)value forKey:(NSString *)key;

- (int64_t)getInt64ForKey:(NSString *)key;
- (void)setInt64:(int64_t)value forKey:(NSString *)key;

@end

NS_ASSUME_NONNULL_END

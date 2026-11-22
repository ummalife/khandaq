// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <CommonCrypto/CommonDigest.h>

#import "OCTGroupMessageTombstones.h"
#import "OCTLogging.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageText.h"
#import "OCTMessageFile.h"
#import "OCTRealmManager.h"

static const NSTimeInterval kOCTGroupMessageTombstoneRetentionSec = 7.0 * 24.0 * 60.0 * 60.0;
static const NSUInteger kOCTGroupMessageTombstoneCapacity = 512;
// The window of the content rule of files (#170 in OCTRealmManager): the same size and base name within a day.
static const NSTimeInterval kOCTGroupMessageTombstoneFileWindowSec = 24.0 * 60.0 * 60.0;
// The unscoped window of the text rule (#42 in OCTSubmanagerGroupsImpl): the same text within 3 s.
static const NSTimeInterval kOCTGroupMessageTombstoneAnyAuthorWindowSec = 3.0;
static NSString *const kOCTGroupMessageTombstoneKeyPrefix = @"kqdeleted:";

// The fields of a stored tombstone.
static NSString *const kKind = @"k";
static NSString *const kKindText = @"t";
static NSString *const kKindFile = @"f";
static NSString *const kMessageId = @"i";
static NSString *const kDigest = @"d";
static NSString *const kAuthorKey = @"a";
static NSString *const kNameDigest = @"n";
static NSString *const kTransferId = @"h";
static NSString *const kFileSize = @"s";
static NSString *const kTime = @"t";
static NSString *const kRecordedAt = @"r";

@interface OCTGroupMessageTombstones ()

@property (nonatomic, copy, readonly) OCTGroupMessageTombstonesGetValueBlock getValueBlock;
@property (nonatomic, copy, readonly) OCTGroupMessageTombstonesSetValueBlock setValueBlock;
/// The tombstones of each chat read so far, by store key, oldest first.
@property (nonatomic, strong, readonly) NSMutableDictionary<NSString *, NSMutableArray<NSDictionary *> *> *entriesByStoreKey;

@end

@implementation OCTGroupMessageTombstones

+ (NSTimeInterval)retention
{
    return kOCTGroupMessageTombstoneRetentionSec;
}

+ (NSUInteger)capacityPerChat
{
    return kOCTGroupMessageTombstoneCapacity;
}

+ (NSString *)storeKeyForChatIdHex:(NSString *)chatIdHex chatUniqueIdentifier:(NSString *)chatUniqueIdentifier
{
    return chatIdHex.length == 64 ? chatIdHex.lowercaseString : (chatUniqueIdentifier ?: @"");
}

- (instancetype)initWithGetValueBlock:(OCTGroupMessageTombstonesGetValueBlock)getValueBlock
                        setValueBlock:(OCTGroupMessageTombstonesSetValueBlock)setValueBlock
{
    NSParameterAssert(getValueBlock);
    NSParameterAssert(setValueBlock);

    self = [super init];

    if (! self) {
        return nil;
    }

    _getValueBlock = [getValueBlock copy];
    _setValueBlock = [setValueBlock copy];
    _entriesByStoreKey = [NSMutableDictionary dictionary];

    return self;
}

#pragma mark - Public

- (void)recordMessages:(NSArray<OCTMessageAbstract *> *)messages storeKey:(NSString *)storeKey now:(NSTimeInterval)now
{
    if (storeKey.length == 0 || messages.count == 0) {
        return;
    }

    NSMutableArray<NSDictionary *> *added = [NSMutableArray array];

    for (OCTMessageAbstract *message in messages) {
        NSDictionary *entry = [[self class] entryForMessage:message now:now];

        if (entry) {
            [added addObject:entry];
        }
    }

    if (added.count == 0) {
        return;
    }

    @synchronized(self) {
        NSMutableArray<NSDictionary *> *entries = [self entriesForStoreKey:storeKey now:now];
        [entries addObjectsFromArray:added];

        if (entries.count > kOCTGroupMessageTombstoneCapacity) {
            [entries removeObjectsInRange:NSMakeRange(0, entries.count - kOCTGroupMessageTombstoneCapacity)];
        }

        [self storeEntries:entries forStoreKey:storeKey];
    }
}

- (BOOL)isDeletedTextInStoreKey:(NSString *)storeKey
                      messageId:(uint32_t)messageId
                           text:(NSString *)text
                      authorKey:(NSString *)authorKey
                     authorName:(NSString *)authorName
                   dateInterval:(NSTimeInterval)dateInterval
                  windowSeconds:(NSTimeInterval)windowSeconds
                            now:(NSTimeInterval)now
{
    if (storeKey.length == 0 || text.length == 0) {
        return NO;
    }

    NSString *digest = [[self class] digestOfString:text];
    NSString *nameDigest = authorName.length > 0 ? [[self class] digestOfString:authorName] : nil;

    @synchronized(self) {
        for (NSDictionary *entry in [self entriesForStoreKey:storeKey now:now]) {
            if (! [entry[kKind] isEqualToString:kKindText]) {
                continue;
            }

            const BOOL sameText = [entry[kDigest] isEqualToString:digest];
            NSString *entryAuthor = entry[kAuthorKey];
            const BOOL sameAuthor = authorKey.length > 0 && entryAuthor.length > 0
                && [entryAuthor caseInsensitiveCompare:authorKey] == NSOrderedSame;

            // The message id goes with its text, as the dedup of stored rows takes it (#114); with its author too:
            // a copy of a text the author has edited since keeps the id and the author.
            if (messageId != 0 && [entry[kMessageId] unsignedIntValue] == messageId && (sameText || sameAuthor)) {
                return YES;
            }

            if (! sameText) {
                continue;
            }

            const NSTimeInterval entryTime = [entry[kTime] doubleValue];

            if (dateInterval <= 0 || entryTime <= 0) {
                continue;
            }

            const NSTimeInterval distance = fabs(dateInterval - entryTime);
            const BOOL sameName = nameDigest.length > 0 && [entry[kNameDigest] isEqualToString:nameDigest];

            if (distance <= kOCTGroupMessageTombstoneAnyAuthorWindowSec || (distance <= windowSeconds && (sameAuthor || sameName))) {
                return YES;
            }
        }
    }

    return NO;
}

- (BOOL)isDeletedFileInStoreKey:(NSString *)storeKey msgIdHashHex:(NSString *)msgIdHashHex now:(NSTimeInterval)now
{
    if (storeKey.length == 0 || msgIdHashHex.length != 64) {
        return NO;
    }

    NSString *transferId = msgIdHashHex.lowercaseString;

    @synchronized(self) {
        for (NSDictionary *entry in [self entriesForStoreKey:storeKey now:now]) {
            if ([entry[kKind] isEqualToString:kKindFile] && [entry[kTransferId] isEqualToString:transferId]) {
                return YES;
            }
        }
    }

    return NO;
}

- (BOOL)isDeletedFileInStoreKey:(NSString *)storeKey
                       baseName:(NSString *)baseName
                       fileSize:(uint64_t)fileSize
                   dateInterval:(NSTimeInterval)dateInterval
                            now:(NSTimeInterval)now
{
    if (storeKey.length == 0 || baseName.length == 0 || fileSize == 0 || dateInterval <= 0) {
        return NO;
    }

    NSString *digest = [[self class] digestOfString:baseName.lowercaseString];

    @synchronized(self) {
        for (NSDictionary *entry in [self entriesForStoreKey:storeKey now:now]) {
            if ([entry[kKind] isEqualToString:kKindFile]
                && [entry[kFileSize] unsignedLongLongValue] == fileSize
                && [entry[kDigest] isEqualToString:digest]
                && fabs(dateInterval - [entry[kTime] doubleValue]) <= kOCTGroupMessageTombstoneFileWindowSec) {
                return YES;
            }
        }
    }

    return NO;
}

- (NSUInteger)countInStoreKey:(NSString *)storeKey now:(NSTimeInterval)now
{
    if (storeKey.length == 0) {
        return 0;
    }

    @synchronized(self) {
        return [self entriesForStoreKey:storeKey now:now].count;
    }
}

#pragma mark - Private

/// What a tombstone keeps of the row, or nil for a row nothing could come again for.
+ (nullable NSDictionary *)entryForMessage:(OCTMessageAbstract *)message now:(NSTimeInterval)now
{
    if (! message || message.isInvalidated || message.groupSystemMessage || message.groupPrivateMessage) {
        return nil;
    }

    NSMutableDictionary *entry = [NSMutableDictionary dictionary];
    // As AirGroupTranscript.authorKey(of:) reads it: the key a relayed row claims, else the key a live row froze. Our
    // own rows keep none (their groupPrivatePeerPubkey names a recipient, never us).
    NSString *authorKey = message.messageText.groupSenderPubkey.length > 0
        ? message.messageText.groupSenderPubkey
        : message.messageFile.groupSenderPubkey;

    if (authorKey.length == 0 && ! message.isOutgoing) {
        authorKey = message.groupPrivatePeerPubkey;
    }

    if (authorKey.length > 0) {
        entry[kAuthorKey] = authorKey.uppercaseString;
    }

    const NSTimeInterval time = message.groupSyncDedupTimestamp > 0 ? message.groupSyncDedupTimestamp : message.dateInterval;
    entry[kTime] = @(time);
    entry[kRecordedAt] = @(now);

    if (message.messageText) {
        NSString *text = message.messageText.text;

        if (text.length == 0) {
            return nil;
        }

        entry[kKind] = kKindText;
        entry[kMessageId] = @((uint32_t)message.messageText.messageId);
        entry[kDigest] = [self digestOfString:text];

        if (message.messageText.groupPeerName.length > 0) {
            entry[kNameDigest] = [self digestOfString:message.messageText.groupPeerName];
        }

        return entry;
    }

    if (message.messageFile) {
        NSString *transferId = message.messageFile.groupMsgIdHashHex;
        NSString *baseName = OCTGroupFileBaseName(message.messageFile.fileName);

        if (transferId.length != 64 && baseName.length == 0) {
            return nil;
        }

        entry[kKind] = kKindFile;

        if (transferId.length == 64) {
            entry[kTransferId] = transferId.lowercaseString;
        }

        if (baseName.length > 0) {
            entry[kDigest] = [self digestOfString:baseName.lowercaseString];
            entry[kFileSize] = @((unsigned long long)message.messageFile.fileSize);
        }

        return entry;
    }

    return nil;
}

/// The first 16 bytes of the SHA-256 of the UTF-8 string, in hex: enough to tell texts apart, and the text is not kept.
+ (NSString *)digestOfString:(NSString *)string
{
    NSData *data = [string dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
    uint8_t digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(data.bytes, (CC_LONG)data.length, digest);

    NSMutableString *hex = [NSMutableString stringWithCapacity:32];

    for (int i = 0; i < 16; i++) {
        [hex appendFormat:@"%02x", digest[i]];
    }

    return hex;
}

/// The tombstones of the chat, read from the profile the first time, without the ones past their week. Under the lock.
- (NSMutableArray<NSDictionary *> *)entriesForStoreKey:(NSString *)storeKey now:(NSTimeInterval)now
{
    NSMutableArray<NSDictionary *> *entries = self.entriesByStoreKey[storeKey];

    if (! entries) {
        entries = [[[self class] entriesFromSerialized:self.getValueBlock([kOCTGroupMessageTombstoneKeyPrefix stringByAppendingString:storeKey])] mutableCopy];
        self.entriesByStoreKey[storeKey] = entries;
    }

    NSIndexSet *expired = [entries indexesOfObjectsPassingTest:^BOOL (NSDictionary *entry, NSUInteger index, BOOL *stop) {
        return [entry[kRecordedAt] doubleValue] < now - kOCTGroupMessageTombstoneRetentionSec;
    }];

    if (expired.count > 0) {
        [entries removeObjectsAtIndexes:expired];
    }

    return entries;
}

- (void)storeEntries:(NSArray<NSDictionary *> *)entries forStoreKey:(NSString *)storeKey
{
    NSData *data = [NSJSONSerialization dataWithJSONObject:entries options:0 error:nil];
    NSString *serialized = data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : nil;

    if (! serialized || ! self.setValueBlock([kOCTGroupMessageTombstoneKeyPrefix stringByAppendingString:storeKey], serialized)) {
        // The tombstones still hold until the app closes.
        OCTLogWarn(@"the deleted messages of a group chat were not stored");
    }
}

/// What the profile keeps, read back: each field of the type it is written with, or the tombstone is dropped.
+ (NSArray<NSDictionary *> *)entriesFromSerialized:(nullable NSString *)serialized
{
    NSData *data = [serialized dataUsingEncoding:NSUTF8StringEncoding];
    id parsed = data.length > 0 ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;

    if (! [parsed isKindOfClass:[NSArray class]]) {
        return @[];
    }

    NSMutableArray<NSDictionary *> *entries = [NSMutableArray array];

    for (id candidate in parsed) {
        NSDictionary *entry = [self entryFromStored:candidate];

        if (entry) {
            [entries addObject:entry];
        }
    }

    return entries;
}

+ (nullable NSDictionary *)entryFromStored:(id)stored
{
    if (! [stored isKindOfClass:[NSDictionary class]]) {
        return nil;
    }

    NSString *kind = stored[kKind];

    if (! [kind isKindOfClass:[NSString class]] || ! ([kind isEqualToString:kKindText] || [kind isEqualToString:kKindFile])
        || ! [stored[kRecordedAt] isKindOfClass:[NSNumber class]]) {
        return nil;
    }

    NSMutableDictionary *entry = [NSMutableDictionary dictionary];

    for (NSString *key in @[kKind, kDigest, kAuthorKey, kNameDigest, kTransferId]) {
        if ([stored[key] isKindOfClass:[NSString class]]) {
            entry[key] = stored[key];
        }
    }

    for (NSString *key in @[kMessageId, kFileSize, kTime, kRecordedAt]) {
        if ([stored[key] isKindOfClass:[NSNumber class]]) {
            entry[key] = stored[key];
        }
    }

    return entry;
}

@end

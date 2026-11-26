// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@class OCTMessageAbstract;

typedef NSString *_Nullable (^OCTGroupMessageTombstonesGetValueBlock)(NSString *key);
typedef BOOL (^OCTGroupMessageTombstonesSetValueBlock)(NSString *key, NSString *value);

/**
 * KHANDAQ (tester 21.11, 8256/8261): the group messages deleted on this device, kept so that a copy of one that comes
 * again is not stored again. A removed row left nothing behind, and every member who holds a message serves it in the
 * group's history sync for 130 minutes, at each join and reconnect: a deleted message came back as "Relayed from
 * history", most often right after a restart.
 *
 * A tombstone keeps what the receive paths tell copies of one message apart by: for a text its message id, a digest
 * of the text (never the text), the author's key, a digest of the author's name frozen on the row and its time; for
 * a file the id of its transfer, a digest of its base name, its size and its time. They are kept per chat in the
 * profile's key-value rows, for a week and at most the 512 newest of a chat; the history sync takes no record older
 * than 200 minutes.
 *
 * No clock of its own: the caller says what time it is. Thread-safe.
 */
@interface OCTGroupMessageTombstones : NSObject

/// How long a tombstone is kept (a week).
+ (NSTimeInterval)retention;

/// How many tombstones a chat keeps at most, the newest.
+ (NSUInteger)capacityPerChat;

/// The chat a store key names: its group id (64 hex digits) once known, which a chat made again by a rejoin keeps,
/// else its unique identifier.
+ (NSString *)storeKeyForChatIdHex:(nullable NSString *)chatIdHex chatUniqueIdentifier:(NSString *)chatUniqueIdentifier;

- (instancetype)initWithGetValueBlock:(OCTGroupMessageTombstonesGetValueBlock)getValueBlock
                        setValueBlock:(OCTGroupMessageTombstonesSetValueBlock)setValueBlock NS_DESIGNATED_INITIALIZER;

- (instancetype)init NS_UNAVAILABLE;

/// Records the messages of the chat that are about to be removed, in one write. To be called while the rows are still
/// there. System lines, rows of private threads and rows with nothing to recognise a copy by are left out.
- (void)recordMessages:(NSArray<OCTMessageAbstract *> *)messages storeKey:(NSString *)storeKey now:(NSTimeInterval)now;

/**
 * YES when the text is a copy of a text deleted in the chat: the same message id with the same text or from the same
 * author, or the same text from the same author (key or name) within `windowSeconds` of the deleted one's time, or
 * the same text within 3 s of it (the same rules the receive paths dedup stored rows by).
 */
- (BOOL)isDeletedTextInStoreKey:(NSString *)storeKey
                      messageId:(uint32_t)messageId
                           text:(NSString *)text
                      authorKey:(nullable NSString *)authorKey
                     authorName:(nullable NSString *)authorName
                   dateInterval:(NSTimeInterval)dateInterval
                  windowSeconds:(NSTimeInterval)windowSeconds
                            now:(NSTimeInterval)now;

/// YES when a file with this transfer id (64 hex digits, any case) was deleted in the chat.
- (BOOL)isDeletedFileInStoreKey:(NSString *)storeKey msgIdHashHex:(nullable NSString *)msgIdHashHex now:(NSTimeInterval)now;

/// YES when a file of this base name (as OCTGroupFileBaseName gives it) and size was deleted in the chat within a day
/// of `dateInterval`: a copy sent again comes with a transfer id of its own.
- (BOOL)isDeletedFileInStoreKey:(NSString *)storeKey
                       baseName:(nullable NSString *)baseName
                       fileSize:(uint64_t)fileSize
                   dateInterval:(NSTimeInterval)dateInterval
                            now:(NSTimeInterval)now;

/// How many tombstones the chat keeps at `now`.
- (NSUInteger)countInStoreKey:(NSString *)storeKey now:(NSTimeInterval)now;

@end

NS_ASSUME_NONNULL_END

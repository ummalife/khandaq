// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTToxConstants.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * KHANDAQ (QA live, F11): a group the user left whose tox_group_leave waits until toxcore has resent the leave
 * notice. Immutable.
 */
@interface OCTGroupPendingLeave : NSObject

@property (nonatomic, assign, readonly) OCTToxGroupNumber groupNumber;
/// The group's chat id, 64 hex digits, upper case.
@property (nonatomic, copy, readonly) NSString *chatIdHex;
@property (nonatomic, copy, readonly, nullable) NSString *partMessage;
/// When the group is left, on the clock the caller records with.
@property (nonatomic, assign, readonly) NSTimeInterval dueAt;

- (instancetype)initWithGroupNumber:(OCTToxGroupNumber)groupNumber
                          chatIdHex:(NSString *)chatIdHex
                        partMessage:(nullable NSString *)partMessage
                              dueAt:(NSTimeInterval)dueAt NS_DESIGNATED_INITIALIZER;

- (instancetype)init NS_UNAVAILABLE;

@end

/**
 * KHANDAQ (QA live, F11): the bookkeeping of the leaves that wait. toxcore deletes a group, with its connections and
 * its send queue, on the iterate after tox_group_leave, so the leave notice sent right before it had a single try,
 * and over a TCP relay it was sometimes lost. A leave whose notice was queued waits for the notice's resends instead.
 *
 * Only records, no toxcore and no clock of its own: the caller says what time it is. Thread-safe.
 */
@interface OCTGroupPendingLeaves : NSObject

/// How long a leave waits after its notice was queued (12 s; the reason is at its definition).
+ (NSTimeInterval)delay;

/// Whether a leave waits: only for a notice that toxcore queued to another member. toxcore counts the user among the
/// group's confirmed peers (tox_group_peer_count), and reports a lossless packet as sent when no other peer is
/// confirmed (send_gc_lossless_packet_all_peers); then it queued the notice to no one and resends nothing, and the
/// group is left at once.
+ (BOOL)leaveWaitsForNoticeSent:(BOOL)noticeSent confirmedPeerCount:(uint32_t)confirmedPeerCount;

/// The chat ids read back from -serializedChatIds: anything that is not 64 hex digits is dropped, the rest is upper
/// case, each once, in the stored order.
+ (NSArray<NSString *> *)chatIdHexesFromSerialized:(nullable NSString *)serialized;

@property (nonatomic, assign, readonly) NSUInteger count;

/// Records the leave of this group at `now`, due `+delay` later. A leave already pending for the group number is
/// replaced. Nil when the chat id is not 64 hex digits.
- (nullable OCTGroupPendingLeave *)recordLeaveOfGroupNumber:(OCTToxGroupNumber)groupNumber
                                                  chatIdHex:(NSString *)chatIdHex
                                                partMessage:(nullable NSString *)partMessage
                                                         at:(NSTimeInterval)now;

/// Drops this leave, if it is still the one recorded (a newer record of its group is kept). YES when it was.
- (BOOL)removeEntry:(OCTGroupPendingLeave *)entry;

- (BOOL)containsGroupNumber:(OCTToxGroupNumber)groupNumber;

/// The leaves pending for this chat id, in any case, in the order recorded.
- (NSArray<OCTGroupPendingLeave *> *)entriesForChatIdHex:(nullable NSString *)chatIdHex;

/// The leaves due at `now`, earliest first. They stay recorded until removed.
- (NSArray<OCTGroupPendingLeave *> *)dueEntriesAt:(NSTimeInterval)now;

/// Every pending leave, in the order recorded.
- (NSArray<OCTGroupPendingLeave *> *)allEntries;

/// When the earliest pending leave is due; 0 when none is pending.
- (NSTimeInterval)nextDueAt;

/// What the profile keeps: the chat ids, upper case, comma separated, each once, in the order recorded; empty when
/// none is pending.
- (NSString *)serializedChatIds;

@end

NS_ASSUME_NONNULL_END

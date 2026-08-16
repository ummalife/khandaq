// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef BOOL (^OCTNgcHskAnnounceSendBroadcastBlock)(uint32_t groupNumber, NSData *packet);
typedef NSString *_Nullable (^OCTNgcHskAnnounceSelfGroupPubBlock)(uint32_t groupNumber);
typedef NSString *_Nullable (^OCTNgcHskAnnouncePeerGroupPubBlock)(uint32_t groupNumber, uint32_t peerId);
typedef NSString *_Nullable (^OCTNgcHskAnnounceGroupIdBlock)(uint32_t groupNumber);
typedef NSString *_Nullable (^OCTNgcHskAnnounceSelfToxPubBlock)(void);
typedef BOOL (^OCTNgcHskAnnouncePeerConnectedBlock)(uint32_t groupNumber, uint32_t peerId);
typedef NSString *_Nullable (^OCTNgcHskAnnounceGetValueBlock)(NSString *key);
typedef BOOL (^OCTNgcHskAnnounceSetValueBlock)(NSString *key, NSString *value);

/**
 * KHANDAQ (external audit #2, finding 1, step 3) — announcing our history-signing key to a group,
 * and learning other members' keys from theirs.
 *
 * Packet: magic(6) | 0x02 | 0x50 | hsk_pub(32) | valid_from_ts(8 BE) | sig(64), self-signed over
 * "KQ-HSK-ANNOUNCE-1" || sender_group_pub(32) || hsk_pub(32) || valid_from_ts(8).
 *
 * Deliberately inert for every shipped client: version 0x02 is dropped by every parser in the field
 * on all three platforms, so peers that do not understand it ignore it rather than misread it.
 *
 * WHEN IT IS SENT is as load-bearing as the crypto. Announcing only when WE connect misses every
 * peer that arrives later — the packet goes out while the peer list is still empty, and on Android
 * two clients started together learned nothing for the full ten-minute interval. A time window
 * cannot fix that: any window wide enough to collapse a join burst also swallows the join that
 * matters. So this tracks which peers have actually heard us, and a peer that has not triggers one
 * broadcast that marks everyone currently present.
 */
@interface OCTNgcHskAnnounce : NSObject

/** Not more often than this per group on the periodic path. */
+ (NSTimeInterval)minIntervalSeconds;

- (instancetype)initWithSendBroadcastBlock:(OCTNgcHskAnnounceSendBroadcastBlock)sendBroadcastBlock
                          selfGroupPubBlock:(OCTNgcHskAnnounceSelfGroupPubBlock)selfGroupPubBlock
                          peerGroupPubBlock:(OCTNgcHskAnnouncePeerGroupPubBlock)peerGroupPubBlock
                               groupIdBlock:(OCTNgcHskAnnounceGroupIdBlock)groupIdBlock
                             selfToxPubBlock:(OCTNgcHskAnnounceSelfToxPubBlock)selfToxPubBlock
                        peerConnectedBlock:(OCTNgcHskAnnouncePeerConnectedBlock)peerConnectedBlock
                             getValueBlock:(OCTNgcHskAnnounceGetValueBlock)getValueBlock
                             setValueBlock:(OCTNgcHskAnnounceSetValueBlock)setValueBlock;

/**
 * Builds our announcement for one group. Exposed for tests: it is the whole cryptographic path
 * except the socket.
 *
 * @return the packet, or nil when there is no usable identity or key, or signing failed. Never a
 *         partial packet.
 */
- (nullable NSData *)buildAnnouncementForGroupId:(nullable NSString *)groupId
                                    selfGroupPub:(nullable NSString *)selfGroupPubHex
                                     validFromTs:(uint64_t)validFromTs;

/** Sends into a group, honouring the periodic interval unless forced. */
- (BOOL)announceToGroupNumber:(uint32_t)groupNumber force:(BOOL)force;

/** Announces because a peer arrived, unless that peer has already heard us. */
- (BOOL)announceForNewPeerWithGroupNumber:(uint32_t)groupNumber peerId:(uint32_t)peerId;

/**
 * Handles a packet that arrived on the group's live broadcast channel.
 *
 * @return YES if the packet was ours to consume, so the caller stops dispatching it further.
 */
- (BOOL)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                                       data:(nullable NSData *)data;

/** Forgets rate limit and heard-peers for a group — used when our key changes. */
- (void)resetGroupId:(nullable NSString *)groupId;

@end

NS_ASSUME_NONNULL_END

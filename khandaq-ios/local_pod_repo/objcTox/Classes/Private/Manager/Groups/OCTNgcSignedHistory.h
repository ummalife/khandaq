// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef BOOL (^OCTNgcSignedHistorySendPrivateBlock)(uint32_t groupNumber, uint32_t peerId, NSData *packet);
typedef NSString *_Nullable (^OCTNgcSignedHistoryGroupIdBlock)(uint32_t groupNumber);
typedef NSString *_Nullable (^OCTNgcSignedHistorySelfGroupPubBlock)(uint32_t groupNumber);
typedef NSString *_Nullable (^OCTNgcSignedHistorySelfToxPubBlock)(void);
typedef NSString *_Nullable (^OCTNgcSignedHistoryGetValueBlock)(NSString *key);
typedef BOOL (^OCTNgcSignedHistorySetValueBlock)(NSString *key, NSString *value);

/**
 * KHANDAQ (external audit #2, finding 1, step 3) — history-sync text signed by its author.
 *
 * WHO CAN SIGN shapes everything here. The signature covers
 * "KQ-HISTSYNC-1" || group_id || author_pub || msg_id || ts || sha256(text) and is made with the
 * AUTHOR's key. A peer relaying someone else's message cannot produce it — that is exactly the
 * property being bought, since today's relayed history carries an author field nobody signed. So a
 * client can only sign rows it wrote itself; rows written by others keep travelling as they do now
 * until their authors also run a signing build.
 *
 * WHY BOTH PACKETS GO OUT during the transition: version 0x02 is dropped by every shipped parser,
 * so sending only the signed form would make our history vanish for every client in the field —
 * a far worse failure than the one being fixed. The unsigned packet is sent exactly as before and
 * the signed one goes beside it. The extra packet is the cost of not breaking history for old
 * clients and can be dropped once the transition ends (§8).
 *
 * The timestamp is signed FULL WIDTH. The unsigned format transmits only its low four bytes — a
 * latent 2038-class truncation — and signing the truncated value would produce signatures nobody
 * could reproduce.
 */
@interface OCTNgcSignedHistory : NSObject

- (instancetype)initWithSendPrivateBlock:(OCTNgcSignedHistorySendPrivateBlock)sendPrivateBlock
                            groupIdBlock:(OCTNgcSignedHistoryGroupIdBlock)groupIdBlock
                       selfGroupPubBlock:(OCTNgcSignedHistorySelfGroupPubBlock)selfGroupPubBlock
                         selfToxPubBlock:(OCTNgcSignedHistorySelfToxPubBlock)selfToxPubBlock
                           getValueBlock:(OCTNgcSignedHistoryGetValueBlock)getValueBlock
                           setValueBlock:(OCTNgcSignedHistorySetValueBlock)setValueBlock;

/**
 * Builds the signed packet for one of OUR OWN messages.
 *
 * @param authorPubHex the row's author. Anything that is not our own key in this group returns nil:
 *                     a signature is a claim about authorship and we can only make it about
 *                     ourselves.
 * @return the packet, or nil. Never partial — a malformed signed packet is worse than none, because
 *         a receiver counts it as a failed verification rather than as an old client.
 */
- (nullable NSData *)buildSignedTextForGroupNumber:(uint32_t)groupNumber
                                      authorPubHex:(nullable NSString *)authorPubHex
                                         messageId:(uint32_t)messageId
                                         timestamp:(uint64_t)timestampSeconds
                                          peerName:(nullable NSString *)peerName
                                              text:(nullable NSString *)text;

/** Sends the signed copy to one peer, in addition to the unsigned packet the caller already sent. */
- (BOOL)sendSignedTextToGroupNumber:(uint32_t)groupNumber
                             peerId:(uint32_t)peerId
                       authorPubHex:(nullable NSString *)authorPubHex
                          messageId:(uint32_t)messageId
                          timestamp:(uint64_t)timestampSeconds
                           peerName:(nullable NSString *)peerName
                               text:(nullable NSString *)text;

/**
 * Handles an incoming signed record. Inserts NOTHING: during the transition the unsigned packet
 * travels beside this one and does the inserting exactly as before, so this decides only whether
 * the authorship claim on that row is backed by a signature, and records the verdict. Keeping it
 * out of the insert path means the change cannot alter WHICH messages appear, only how confidently
 * they are attributed.
 *
 * A bad signature is dropped without a verdict. It cannot be an old client — old clients never send
 * 0x02 — so the honest outcome is the row staying unproved rather than being marked bad.
 *
 * @return YES if the packet was ours to consume.
 */
- (BOOL)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                                       data:(nullable NSData *)data;

/** @return YES when a signature has proved this row's author. A missing row means "not proved". */
- (BOOL)isAuthorVerifiedForGroupId:(nullable NSString *)groupId
                         messageId:(uint32_t)messageId
                      authorPubHex:(nullable NSString *)authorPubHex;

@end

NS_ASSUME_NONNULL_END

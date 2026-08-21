// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTNgcHistoryDowngradePolicy.h"

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
/**
 * Posted on the main queue right after a verdict is stored, with the group's chat-id hex as `object`.
 *
 * The row a verdict proves was inserted and drawn milliseconds earlier by the unsigned copy that
 * travels beside the signed one, and it still carries the "sender not verified" marker. The verdict
 * lives in its own table, so no message-level Realm notification fires for it — without this, the
 * marker would sit there, wrong, until the chat was reopened. (Android does the same thing through
 * schedule_group_verified_refresh.)
 */
extern NSString *const kOCTNgcSignedHistoryVerdictStoredNotification;

@interface OCTNgcSignedHistory : NSObject

- (instancetype)initWithSendPrivateBlock:(OCTNgcSignedHistorySendPrivateBlock)sendPrivateBlock
                            groupIdBlock:(OCTNgcSignedHistoryGroupIdBlock)groupIdBlock
                       selfGroupPubBlock:(OCTNgcSignedHistorySelfGroupPubBlock)selfGroupPubBlock
                         selfToxPubBlock:(OCTNgcSignedHistorySelfToxPubBlock)selfToxPubBlock
                           getValueBlock:(OCTNgcSignedHistoryGetValueBlock)getValueBlock
                           setValueBlock:(OCTNgcSignedHistorySetValueBlock)setValueBlock;

/**
 * Resolves (and mints on first use) this group's signing key, caching it in memory.
 *
 * MUST be called before any buildSignedText… for that group, and MUST be called from outside the
 * profile database's own queue. The store reaches the database through a serial queue, while the
 * packets are built from inside an enumeration ALREADY running on that queue — looking the key up
 * there is a dispatch_sync onto a queue the current thread owns, which libdispatch turns into an
 * immediate crash and which would take the shipped unsigned history path down with it.
 *
 * @return YES if a key is available; when NO, buildSignedText… simply produces nothing.
 */
- (BOOL)prepareKeyForGroupNumber:(uint32_t)groupNumber;

/**
 * Builds the signed packet for one of OUR OWN messages.
 *
 * Uses only the key cached by -prepareKeyForGroupNumber: and never touches the database, so it is
 * safe to call from inside a database enumeration.
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

/**
 * @return YES when a signature has proved THIS row — its author, its timestamp and its exact text.
 *         A missing row simply means "not proved", which is every message today.
 *
 * @param timestampSeconds the row's send time in SECONDS, exactly as the signed packet carried it.
 * @param text             the body being rendered. Passed in and re-hashed on every read, because a
 *                         verdict found under (group, msg_id, author) proves nothing on its own:
 *                         msg_id is four bytes the sender chooses.
 */
- (BOOL)isAuthorVerifiedForGroupId:(nullable NSString *)groupId
                         messageId:(uint32_t)messageId
                      authorPubHex:(nullable NSString *)authorPubHex
                         timestamp:(uint64_t)timestampSeconds
                              text:(nullable NSString *)text;

/**
 * KHANDAQ (external audit 2026-08-21, K-01) — whether an UNSIGNED history row claiming `authorPubHex`
 * may be stored, or is a downgrade attempt against an author we know can sign.
 *
 * Everything the rule needs already lived here — the HSK directory, its row key, its decoder and the
 * verdict store — and nobody ever asked the question on the unsigned path. This is that question,
 * asked once, so the caller stays one line and the rule itself stays a pure function shared with
 * Android (see OCTNgcHistoryDowngradePolicy.h).
 *
 * Called on the packet-dispatch stack; it performs the same single blocking key-value read the 0x02
 * handler already performs there.
 */
- (OCTNgcDowngradeDecision)downgradeDecisionForGroupNumber:(uint32_t)groupNumber
                                              authorPubHex:(nullable NSString *)authorPubHex
                                                 messageId:(uint32_t)messageId
                                                 timestamp:(uint64_t)timestampSeconds
                                                      text:(nullable NSString *)text;

/**
 * KHANDAQ (audit round 3, F-03) — the same question for a record that CANNOT be signed.
 *
 * The file history packet (0x03) has no signed form: the parser knows only PKT_SIGNED_TEXT, so a
 * file record can never carry a verdict and the method above would be asked to look one up by a text
 * it does not have. Answering "is this author known to sign?" is the whole question there, and the
 * answer for an author with a live announced key is Reject — refusing exactly the downgrade that the
 * text path already refuses.
 *
 * Kept as its own method rather than passing nil text to the one above, so that a lookup MISS can
 * never be mistaken for a deliberate "unverifiable by construction".
 */
- (OCTNgcDowngradeDecision)downgradeDecisionForUnsignableRecordInGroupNumber:(uint32_t)groupNumber
                                                               authorPubHex:(nullable NSString *)authorPubHex;

@end

NS_ASSUME_NONNULL_END

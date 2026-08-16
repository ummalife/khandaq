// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * KHANDAQ (external audit #2, finding 1, step 3) — which history-signing key we believe belongs to
 * a peer, and when we are willing to change our mind.
 *
 * This is the security boundary of the whole design. History-sync packets are relayed, so their
 * claimed author is unauthenticated; the fix verifies an Ed25519 signature against the author's HSK.
 * That only helps if learning the HSK is itself hard to spoof — and the announcement carrying it is
 * self-signed, which proves possession of the announced key and nothing about who owns it. The
 * binding comes from the live transport: toxcore authenticates the sender of a group packet, so an
 * announcement received live is attributable and a relayed one is not. That identity is the peer's
 * public key IN THIS GROUP, the only one NGC exposes and the same one a group message records as
 * its author, which is what makes the two halves line up.
 *
 * Hence the rule from §4.2: FIRST ANNOUNCEMENT WINS. A later announcement naming a different key
 * for the same peer is refused, because silently accepting replacements hands the impersonation
 * door back to whoever gets a packet in first after a peer drops. Replacement is allowed only when
 * the peer is connected RIGHT NOW and the key being replaced has not been seen for a grace period.
 *
 * Kept as a pure decision so every branch is testable without a database or a network.
 */
typedef NS_ENUM(NSUInteger, OCTNgcHskDecision) {
    /** Nothing known yet — learn it. */
    OCTNgcHskDecisionLearn,
    /** The key we already trust — bump last-seen. */
    OCTNgcHskDecisionRefresh,
    /** A different key we are not willing to switch to. Keep trusting the old one. */
    OCTNgcHskDecisionRecordOnly,
    /** A different key, and every condition for switching is met. */
    OCTNgcHskDecisionReplace,
    /** Malformed input; change nothing. */
    OCTNgcHskDecisionIgnore,
};

/** What we currently believe about one (group, peer) pair. */
@interface OCTNgcHskRecord : NSObject
@property (nonatomic, copy, readonly) NSData *hskPub;
@property (nonatomic, assign, readonly) uint64_t firstSeenMs;
@property (nonatomic, assign, readonly) uint64_t lastSeenMs;

- (instancetype)initWithHskPub:(NSData *)hskPub
                   firstSeenMs:(uint64_t)firstSeenMs
                    lastSeenMs:(uint64_t)lastSeenMs;
@end

@interface OCTNgcHskDirectory : NSObject

/** How long a known key must go unseen before a different one may replace it. */
+ (uint64_t)replaceGraceMs;

/**
 * @param existing what we already believe, or nil if this pair is new.
 * @param peerConnectedNow whether toxcore has a live connection to this peer — the only thing that
 *                         makes the sender attributable at all.
 *
 * A clock moved backwards yields a negative age, which fails the grace comparison and lands on
 * RecordOnly. That is the safe direction: a backwards clock can never hurry a replacement through.
 */
+ (OCTNgcHskDecision)decideWithExisting:(nullable OCTNgcHskRecord *)existing
                            incomingPub:(nullable NSData *)incomingHskPub
                       peerConnectedNow:(BOOL)peerConnectedNow
                                  nowMs:(uint64_t)nowMs;

/** Row key for one pair, lower-cased so two spellings cannot become two rows. */
+ (nullable NSString *)rowKeyWithGroupId:(nullable NSString *)groupId
                          peerGroupPubHex:(nullable NSString *)peerGroupPubHex;

/** hskPubHex:firstSeen:lastSeen — plain and greppable in a support dump. */
+ (nullable NSString *)encodeRecord:(nullable OCTNgcHskRecord *)record;

/** @return the record, or nil if the row is missing or malformed. Never a partial record. */
+ (nullable OCTNgcHskRecord *)decodeRecord:(nullable NSString *)stored;

@end

NS_ASSUME_NONNULL_END

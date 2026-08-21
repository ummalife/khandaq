// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTNgcHskDirectory.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * KHANDAQ (external audit 2026-08-21, finding K-01) — do not accept unsigned history about an author
 * we know can sign.
 *
 * The exact counterpart of Android's NgcHistoryDowngradePolicy, kept as a pure function for the same
 * reason the HSK decision is: every branch is testable without a database, and the two platforms can
 * be read side by side. Any change here must be mirrored there — a rule enforced on one client and
 * not the other is not a rule, it is a preference.
 *
 * THE DEFECT. A history-sync packet carries the alleged author as 32 bytes nobody signed; the
 * transport authenticates the SYNCING peer only. Signed history (0x02) closes that for records that
 * carry a signature, but while the unsigned form is accepted unconditionally an attacker simply does
 * not send the signed one. The downgrade costs nothing.
 *
 * THE RULE. Once this author's signing key has been learned from a live, transport-authenticated
 * announcement in this group, an unsigned record claiming them is refused outright.
 *
 * WHY IT IS NOT A ONE-LINE CHECK. OCTNgcSignedHistory deliberately inserts nothing — the unsigned
 * packet creates the row and the signed one only records a verdict about it. So "refuse unsigned
 * records from a signing author" would delete every message from exactly the authors doing the right
 * thing. The rule therefore asks whether the signed twin has ALREADY ARRIVED for this specific row,
 * which required sending the signed copy FIRST (OCTSubmanagerGroupsImpl) instead of last.
 *
 * ANTI-LOCKOUT. The staleness window is `+[OCTNgcHskDirectory replaceGraceMs]`, the same constant
 * that governs when the directory will accept a REPLACEMENT key, so the two can never disagree: the
 * moment the directory is willing to believe a peer has a new key, this policy stops rejecting on the
 * strength of the old one.
 */
typedef NS_ENUM(NSUInteger, OCTNgcDowngradeDecision) {
    /** The signed twin arrived and its signature covers exactly this row. */
    OCTNgcDowngradeDecisionAcceptVerified,
    /** This author has never announced a signing key here — no downgrade to detect. */
    OCTNgcDowngradeDecisionAcceptLegacy,
    /** The key is old enough that the directory would now accept a replacement; assume key loss. */
    OCTNgcDowngradeDecisionAcceptKeyStale,
    /** The author can sign, said so recently, and this record is not signed. Drop it. */
    OCTNgcDowngradeDecisionReject,
};

/** Same value as +[OCTNgcHskDirectory replaceGraceMs]; see the header comment for why. */
extern uint64_t OCTNgcHistoryDowngradeKeyStaleMs(void);

/**
 * @param authorHsk             what the HSK directory holds for (this group, this claimed author).
 * @param verdictMatchesThisRow whether a verdict exists covering THIS row — message id, timestamp
 *                              and text hash, not merely the author.
 * @param nowMs                 wall clock, milliseconds.
 */
extern OCTNgcDowngradeDecision OCTNgcHistoryDowngradeDecide(OCTNgcHskRecord *_Nullable authorHsk,
                                                           BOOL verdictMatchesThisRow,
                                                           uint64_t nowMs);

NS_ASSUME_NONNULL_END

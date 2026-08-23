// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcHistoryDowngradePolicy.h"

uint64_t OCTNgcHistoryDowngradeKeyStaleMs(void)
{
    return [OCTNgcHskDirectory replaceGraceMs];
}

OCTNgcDowngradeDecision OCTNgcHistoryDowngradeDecide(OCTNgcHskRecord *_Nullable authorHsk,
                                                    BOOL verdictMatchesThisRow,
                                                    uint64_t nowMs)
{
    if (verdictMatchesThisRow) {
        return OCTNgcDowngradeDecisionAcceptVerified;
    }

    if (authorHsk == nil || authorHsk.hskPub.length == 0) {
        return OCTNgcDowngradeDecisionAcceptLegacy;
    }

    // The backwards-clock case is written as a comparison rather than as Android's `unseen < 0`,
    // because these are uint64_t. With ordinary timestamps the underflow happens to land on "stale"
    // anyway, so the guard reads as belt-and-braces — but it is not. On a device whose clock has not
    // been set yet (nowMs near the epoch) and a stored lastSeenMs near UINT64_MAX, the same underflow
    // produces a SMALL number, which without this line reads as "seen moments ago" and REJECTS that
    // peer's history outright. scripts/check-ios-downgrade-policy.py pins exactly that case; removing
    // this line turns it red and no other case notices.
    if (authorHsk.lastSeenMs > nowMs) {
        return OCTNgcDowngradeDecisionAcceptKeyStale;
    }

    if ((nowMs - authorHsk.lastSeenMs) >= OCTNgcHistoryDowngradeKeyStaleMs()) {
        return OCTNgcDowngradeDecisionAcceptKeyStale;
    }

    return OCTNgcDowngradeDecisionReject;
}

BOOL OCTNgcHistoryDowngradeRendersAsClaimedAuthor(OCTNgcDowngradeDecision decision,
                                                  BOOL syncerIsAuthor)
{
    // KHANDAQ (internal audit 2026-08-22): Reject joins AcceptKeyStale — see the Java twin.
    //
    // A file record has no signed form, so every author with a fresh key produced Reject and the
    // record was dropped: the rule refused the feature rather than an attack. The file path now keeps
    // the row and asks this, and answering NO for a rejected row is what makes keeping it safe.
    if (!syncerIsAuthor && (decision == OCTNgcDowngradeDecisionAcceptKeyStale ||
                            decision == OCTNgcDowngradeDecisionReject)) {
        return NO;
    }
    return YES;
}

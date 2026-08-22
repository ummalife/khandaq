package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (re-review 2026-08-22, KQ-03) — the three-member scenario the review asks for by name.
 *
 * <p>A announced a history-signing key in this group and then went quiet for longer than the stale
 * window. B relays an unsigned record claiming A. C receives it.
 *
 * <p>What was already true and must stay true: C does not get a notification with A's name on it.
 * What the review found missing: the row was nevertheless STORED AND DISPLAYED as a message from A.
 * A "sender not verified" marker sat beside it, and a marker beside a familiar name is easy to miss.
 *
 * <p>What must now hold: the row is kept — discarding it would lose real history whenever a peer
 * loses its key, which is the whole reason ACCEPT_KEY_STALE exists — but it is not rendered as A.
 *
 * <p>Pure policy, so the scenario is reproducible without three devices. The rendering half is
 * pinned in {@link UnverifiedSenderMarkerTest}.
 */
public class StaleAuthorAttributionTest
{
    private static final long HOUR = 3600L * 1000L;
    private static final long NOW = 1_800_000_000_000L;

    private static NgcHskDirectory.Record announced(final long lastSeenMs)
    {
        return new NgcHskDirectory.Record(new byte[32], lastSeenMs, lastSeenMs);
    }

    /** A signed key seen an hour ago: an unsigned claim about A is an attack, and is refused. */
    @Test
    public void aRecentSigningAuthorCannotBeImpersonatedAtAll()
    {
        final NgcHistoryDowngradePolicy.Decision d =
                NgcHistoryDowngradePolicy.decide(announced(NOW - HOUR), false, NOW);
        assertEquals(NgcHistoryDowngradePolicy.Decision.REJECT_DOWNGRADE, d);
    }

    /** Past the stale window the row is accepted — that is deliberate, and it is the risky case. */
    @Test
    public void aStaleSigningAuthorsRowIsStillAccepted()
    {
        final NgcHistoryDowngradePolicy.Decision d =
                NgcHistoryDowngradePolicy.decide(announced(NOW - 25 * HOUR), false, NOW);
        assertEquals(NgcHistoryDowngradePolicy.Decision.ACCEPT_KEY_STALE, d);
    }

    /** ...and B, who is not A, must not be able to put A's name on it. */
    @Test
    public void butItMayNotBeShownAsComingFromTheStaleAuthor()
    {
        final NgcHistoryDowngradePolicy.Decision d =
                NgcHistoryDowngradePolicy.decide(announced(NOW - 25 * HOUR), false, NOW);
        assertFalse("a third party's unsigned claim about an absent author must not render as them",
                    NgcHistoryDowngradePolicy.rendersAsClaimedAuthor(d, /* syncerIsAuthor */ false));
    }

    /**
     * A relaying its own history is a different thing entirely: the transport authenticated the peer,
     * so the claim is as good as a live message's. Locking this out would punish the honest case.
     */
    @Test
    public void theAuthorRelayingItsOwnHistoryKeepsItsName()
    {
        final NgcHistoryDowngradePolicy.Decision d =
                NgcHistoryDowngradePolicy.decide(announced(NOW - 25 * HOUR), false, NOW);
        assertTrue(NgcHistoryDowngradePolicy.rendersAsClaimedAuthor(d, /* syncerIsAuthor */ true));
    }

    /** A signature over this exact row is the strongest evidence there is; it always attributes. */
    @Test
    public void aVerifiedRowAlwaysAttributes()
    {
        final NgcHistoryDowngradePolicy.Decision d =
                NgcHistoryDowngradePolicy.decide(announced(NOW - 25 * HOUR), true, NOW);
        assertEquals(NgcHistoryDowngradePolicy.Decision.ACCEPT_VERIFIED, d);
        assertTrue(NgcHistoryDowngradePolicy.rendersAsClaimedAuthor(d, false));
    }

    /**
     * An author who never announced a key is not part of this rule at all. Most of the fleet is in
     * this state during the transition, and stripping their names would be a far worse regression
     * than the one being fixed.
     */
    @Test
    public void anAuthorThatNeverSignedIsUnaffected()
    {
        final NgcHistoryDowngradePolicy.Decision d =
                NgcHistoryDowngradePolicy.decide(null, false, NOW);
        assertEquals(NgcHistoryDowngradePolicy.Decision.ACCEPT_LEGACY, d);
        assertTrue("stripping names from every pre-signing author would break ordinary history",
                   NgcHistoryDowngradePolicy.rendersAsClaimedAuthor(d, false));
    }

    /** Notification suppression is unchanged, and is what stops the forgery from interrupting C. */
    @Test
    public void theForgedRowStillCannotNotify()
    {
        assertFalse(NgcHistoryDowngradePolicy.allowsNotification(
                /* authorVerified */ false, /* syncerIsAuthor */ false));
    }

    /**
     * Key rotation and reinstall must not be locked out permanently — the reason the review's
     * "require signed history forever" option was not the one taken. A rejoin mints a new NGC group
     * key, which is a different directory row, so it lands on ACCEPT_LEGACY and keeps its name.
     */
    @Test
    public void aRejoinedPeerIsNotPunishedForever()
    {
        final NgcHistoryDowngradePolicy.Decision d =
                NgcHistoryDowngradePolicy.decide(null, false, NOW + 400L * 24 * HOUR);
        assertEquals(NgcHistoryDowngradePolicy.Decision.ACCEPT_LEGACY, d);
        assertTrue(NgcHistoryDowngradePolicy.rendersAsClaimedAuthor(d, false));
    }

    /** The stored row must carry the decision, so the list never re-derives it while scrolling. */
    @Test
    public void theRowRecordsThatItMayNotBeAttributed()
    {
        final GroupMessage unattributed = new GroupMessage();
        unattributed.was_synced = true;
        unattributed.TRIFA_SYNC_TYPE =
                TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS_UNATTRIBUTED.value;
        assertTrue(GroupMessageListHolder_text_incoming_not_read
                           .should_hide_claimed_author(false, unattributed));

        final GroupMessage ordinary = new GroupMessage();
        ordinary.was_synced = true;
        ordinary.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value;
        assertFalse(GroupMessageListHolder_text_incoming_not_read
                            .should_hide_claimed_author(false, ordinary));
    }

    /** A system message names nobody, so it is outside the rule in both directions. */
    @Test
    public void systemMessagesAreNotAffected()
    {
        final GroupMessage m = new GroupMessage();
        m.was_synced = true;
        m.TRIFA_SYNC_TYPE =
                TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS_UNATTRIBUTED.value;
        assertFalse(GroupMessageListHolder_text_incoming_not_read
                            .should_hide_claimed_author(true, m));
    }
}

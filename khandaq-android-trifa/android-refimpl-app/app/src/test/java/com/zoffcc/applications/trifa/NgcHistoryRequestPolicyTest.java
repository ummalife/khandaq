package com.zoffcc.applications.trifa;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (audit 2026-08-20) — the NGC history-request cooldown.
 *
 * <p>The finding it closes: an 8-byte packet made this client spawn an OS thread, scan the group's
 * whole message table out of the encrypted database and re-send it, with no limit on how often. The
 * tests below pin the two properties that make the fix worth having — a loop from one peer collapses
 * to one rebuild per window, and an honest peer is never refused because of what a different peer
 * did — plus the edges that would quietly disable it.
 */
public class NgcHistoryRequestPolicyTest
{
    private static final String GROUP = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF001122334455";
    private static final String OTHER_GROUP = "1122334455667788990011223344556677889900112233445566778899";
    private static final String PEER_A = "11223344556677889900AABBCCDDEEFF11223344556677889900AABB";
    private static final String PEER_B = "FFEEDDCCBBAA00998877665544332211FFEEDDCCBBAA009988776655";
    private static final long T0 = 1_700_000_000_000L;

    @Before
    public void reset()
    {
        NgcHistoryRequestPolicy.resetAllForTest();
    }

    @Test
    public void theFirstRequestIsAlwaysServed()
    {
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0));
    }

    @Test
    public void aLoopFromOnePeerCollapsesToOneRebuildPerWindow()
    {
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0));

        // The attack: the same packet at line rate.
        for (int i = 1; i <= 1000; i++)
        {
            assertFalse("request " + i + " must be dropped",
                        NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0 + i));
        }

        assertFalse(NgcHistoryRequestPolicy.allowRequest(
                GROUP, PEER_A, T0 + NgcHistoryRequestPolicy.REQUEST_COOLDOWN_MS - 1));
        assertTrue("and the window does reopen",
                   NgcHistoryRequestPolicy.allowRequest(
                           GROUP, PEER_A, T0 + NgcHistoryRequestPolicy.REQUEST_COOLDOWN_MS));
    }

    @Test
    public void oneFloodingPeerDoesNotStarveAnother()
    {
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0));
        assertFalse(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0 + 1));

        assertTrue("an honest peer must still be served",
                   NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_B, T0 + 1));
    }

    @Test
    public void theSamePeerInADifferentGroupIsADifferentBudget()
    {
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0));
        assertTrue(NgcHistoryRequestPolicy.allowRequest(OTHER_GROUP, PEER_A, T0));
    }

    @Test
    public void keysAreCaseInsensitive()
    {
        // The pubkey reaches this code from several helpers, some upper-cased and some not; a
        // case-sensitive key would hand the attacker a free reset by flipping case.
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP.toUpperCase(), PEER_A.toUpperCase(), T0));
        assertFalse(NgcHistoryRequestPolicy.allowRequest(GROUP.toLowerCase(), PEER_A.toLowerCase(), T0 + 1));
    }

    @Test
    public void anUnresolvablePeerOrGroupIsRefused()
    {
        assertFalse(NgcHistoryRequestPolicy.allowRequest(null, PEER_A, T0));
        assertFalse(NgcHistoryRequestPolicy.allowRequest(GROUP, null, T0));
        assertFalse(NgcHistoryRequestPolicy.allowRequest(null, null, T0));
    }

    @Test
    public void aBackwardsClockDoesNotLockAPeerOut()
    {
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0));
        // Device time corrected backwards: without the guard `nowMs - last` is negative, which is
        // "less than the cooldown", and this peer would be refused until the clock caught up.
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0 - 86_400_000L));
    }

    @Test
    public void theTrackingMapIsBounded()
    {
        for (int i = 0; i < NgcHistoryRequestPolicy.MAX_TRACKED_REQUESTERS + 50; i++)
        {
            assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, "peer" + i, T0));
        }
        // Whatever the eviction did, the policy must still work afterwards.
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, "fresh-peer", T0));
        assertFalse(NgcHistoryRequestPolicy.allowRequest(GROUP, "fresh-peer", T0 + 1));
    }

    @Test
    public void pruningRemovesOnlyExpiredEntries()
    {
        NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0);
        NgcHistoryRequestPolicy.pruneExpired(T0 + 1);
        assertFalse("not expired, so still in cooldown",
                    NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0 + 2));

        NgcHistoryRequestPolicy.pruneExpired(T0 + NgcHistoryRequestPolicy.REQUEST_COOLDOWN_MS);
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_A, T0 + 3));
    }
}

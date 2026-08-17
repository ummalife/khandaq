package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (audit#2 finding 1) — the per-client spread on the re-announcement interval.
 *
 * The point of the spread is what a group does after an outage: everyone reconnects within the same
 * second, and without an offset everyone re-announces within the same second too. The properties
 * that make it work are all testable without a network: it must be stable for a given client (or a
 * client drifts earlier on every launch until it is back in lockstep), it must differ between
 * members of one group, and it must be bounded — an offset that can grow without limit is a key
 * that stops being announced.
 */
public class NgcHskAnnounceJitterTest
{
    private static final String GROUP_A = "AA".repeat(32);
    private static final String GROUP_B = "BB".repeat(32);
    private static final String CLIENT_1 = "11".repeat(32);
    private static final String CLIENT_2 = "22".repeat(32);

    @Test
    public void isStableForTheSameGroupAndClient()
    {
        assertEquals(NgcHskAnnounce.announceJitterMs(GROUP_A, CLIENT_1),
                     NgcHskAnnounce.announceJitterMs(GROUP_A, CLIENT_1));
    }

    /** Case is not identity: the group id is compared lower-cased everywhere else too. */
    @Test
    public void ignoresGroupIdCase()
    {
        assertEquals(NgcHskAnnounce.announceJitterMs(GROUP_A, CLIENT_1),
                     NgcHskAnnounce.announceJitterMs(GROUP_A.toLowerCase(), CLIENT_1));
    }

    @Test
    public void differsBetweenTwoMembersOfTheSameGroup()
    {
        assertNotEquals(NgcHskAnnounce.announceJitterMs(GROUP_A, CLIENT_1),
                        NgcHskAnnounce.announceJitterMs(GROUP_A, CLIENT_2));
    }

    @Test
    public void differsBetweenTwoGroupsOfTheSameClient()
    {
        assertNotEquals(NgcHskAnnounce.announceJitterMs(GROUP_A, CLIENT_1),
                        NgcHskAnnounce.announceJitterMs(GROUP_B, CLIENT_1));
    }

    @Test
    public void staysUnderTwoMinutes()
    {
        for (int i = 0; i < 500; i++)
        {
            final long jitter = NgcHskAnnounce.announceJitterMs(GROUP_A, "%02x".formatted(i).repeat(32));
            assertTrue("jitter out of range: " + jitter, jitter >= 0L && jitter < 120_000L);
        }
    }

    /** A missing identity must not throw and must not push the interval anywhere odd. */
    @Test
    public void survivesNulls()
    {
        assertTrue(NgcHskAnnounce.announceJitterMs(null, null) >= 0L);
        assertTrue(NgcHskAnnounce.announceJitterMs(GROUP_A, null) < 120_000L);
        assertTrue(NgcHskAnnounce.announceJitterMs(null, CLIENT_1) < 120_000L);
    }
}

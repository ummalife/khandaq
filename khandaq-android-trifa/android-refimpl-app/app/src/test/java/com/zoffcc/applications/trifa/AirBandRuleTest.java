package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.AirBandRule.Outcome.BAND;
import static com.zoffcc.applications.trifa.AirBandRule.Outcome.QUIET;
import static com.zoffcc.applications.trifa.AirBandRule.Outcome.SYSTEM;
import static com.zoffcc.applications.trifa.AirBandRule.Screen.CALL;
import static com.zoffcc.applications.trifa.AirBandRule.Screen.CONTENT;
import static com.zoffcc.applications.trifa.AirBandRule.Screen.GATE;
import static com.zoffcc.applications.trifa.AirBandRule.Screen.NONE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AirBandRuleTest
{
    private static final long NOW = 1_800_000_000_000L;
    /** In front for a minute: well past the grace of coming back. */
    private static final long LONG_AGO = NOW - 60_000L;

    @Test
    public void aMessageFromAnotherChatComesAsTheBand()
    {
        assertEquals(BAND, AirBandRule.forMessage(CONTENT, false, false, LONG_AGO, NOW - 2_000L, NOW));
        // A live group message or a file carries no send time: it arrived just now.
        assertEquals(BAND, AirBandRule.forMessage(CONTENT, false, false, LONG_AGO, 0L, NOW));
    }

    @Test
    public void theChatOnScreenGetsNoBand()
    {
        assertEquals(QUIET, AirBandRule.forMessage(CONTENT, false, true, LONG_AGO, NOW, NOW));
    }

    @Test
    public void theBacklogOfComingBackGetsNoBand()
    {
        assertEquals(QUIET, AirBandRule.forMessage(CONTENT, false, false, NOW - 1_000L, NOW, NOW));
        assertEquals(QUIET, AirBandRule.forMessage(CONTENT, false, false, NOW - 2_999L, NOW, NOW));
        assertEquals(BAND, AirBandRule.forMessage(CONTENT, false, false, NOW - 3_000L, NOW, NOW));
    }

    @Test
    public void aLateDeliveryGetsNoBand()
    {
        assertEquals(QUIET, AirBandRule.forMessage(CONTENT, false, false, LONG_AGO, NOW - 45_001L, NOW));
        assertEquals(BAND, AirBandRule.forMessage(CONTENT, false, false, LONG_AGO, NOW - 45_000L, NOW));
        // A sender's clock a little ahead of ours is not late.
        assertEquals(BAND, AirBandRule.forMessage(CONTENT, false, false, LONG_AGO, NOW + 5_000L, NOW));
    }

    @Test
    public void awayLockedOrOnAGateOrACallItIsASystemNotification()
    {
        assertEquals(SYSTEM, AirBandRule.forMessage(NONE, false, false, LONG_AGO, NOW, NOW));
        assertEquals(SYSTEM, AirBandRule.forMessage(GATE, false, false, LONG_AGO, NOW, NOW));
        assertEquals(SYSTEM, AirBandRule.forMessage(CALL, false, false, LONG_AGO, NOW, NOW));
        assertEquals(SYSTEM, AirBandRule.forMessage(CONTENT, true, false, LONG_AGO, NOW, NOW));
        // Even for the chat that was on screen last: nobody sees it now.
        assertEquals(SYSTEM, AirBandRule.forMessage(CONTENT, true, true, LONG_AGO, NOW, NOW));
    }

    @Test
    public void requestsAndInvitationsFollowTheGraceOnly()
    {
        assertEquals(BAND, AirBandRule.forRequest(CONTENT, false, LONG_AGO, NOW));
        assertEquals(QUIET, AirBandRule.forRequest(CONTENT, false, NOW - 500L, NOW));
        assertEquals(SYSTEM, AirBandRule.forRequest(NONE, false, LONG_AGO, NOW));
        assertEquals(SYSTEM, AirBandRule.forRequest(GATE, false, LONG_AGO, NOW));
        assertEquals(SYSTEM, AirBandRule.forRequest(CALL, false, LONG_AGO, NOW));
        assertEquals(SYSTEM, AirBandRule.forRequest(CONTENT, true, LONG_AGO, NOW));
    }

    @Test
    public void freshness()
    {
        assertTrue(AirBandRule.arrivedJustNow(0L, LONG_AGO, NOW));
        assertFalse(AirBandRule.arrivedJustNow(0L, NOW, NOW));
        assertFalse(AirBandRule.arrivedJustNow(NOW - 100_000L, LONG_AGO, NOW));
        assertTrue(AirBandRule.isPastEntryGrace(NOW - AirBandRule.ENTRY_GRACE_MS, NOW));
    }

    @Test
    public void theBandStaysAtLeastItsOwnTime()
    {
        assertEquals(4_500L, AirBandRule.stayMs(0L));
        assertEquals(4_500L, AirBandRule.stayMs(3_000L));
        assertEquals(20_000L, AirBandRule.stayMs(20_000L));
    }
}

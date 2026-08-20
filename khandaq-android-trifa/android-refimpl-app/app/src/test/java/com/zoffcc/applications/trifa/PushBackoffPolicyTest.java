package com.zoffcc.applications.trifa;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (audit 2026-08-20) — the push 429 backoff.
 *
 * <p>The property that matters is the one the old flat two-hour rule got wrong: a SINGLE transient
 * refusal must cost a minute, not two hours, because a relay-side blip is not evidence that this
 * contact is unreachable for the rest of the afternoon. The ceiling still exists — it is just where
 * repeated refusals end up, not where the first one starts.
 */
public class PushBackoffPolicyTest
{
    private static final String URL = "https://push.khandaq.org/toxfcm/fcm.php?id=abcdefghij";
    private static final String OTHER = "https://push.khandaq.org/toxfcm/fcm.php?id=zyxwvutsrq";
    private static final long T0 = 1_700_000_000_000L;

    @Before
    public void reset()
    {
        PushBackoffPolicy.resetAllForTest();
    }

    // ------------------------------------------------------------------ the regression being fixed

    @Test
    public void aSingleRefusalCostsOneMinuteNotTwoHours()
    {
        PushBackoffPolicy.recordTooManyRequests(URL, T0);

        assertTrue("blocked immediately after the 429", PushBackoffPolicy.shouldSkip(URL, T0));
        assertTrue("still blocked at 59s", PushBackoffPolicy.shouldSkip(URL, T0 + 59_000L));
        assertFalse("free again at 60s", PushBackoffPolicy.shouldSkip(URL, T0 + 60_000L));
        assertFalse("and certainly free long before the old two hours",
                    PushBackoffPolicy.shouldSkip(URL, T0 + 10 * 60_000L));
    }

    @Test
    public void anUntouchedUrlIsNeverBlocked()
    {
        assertFalse(PushBackoffPolicy.shouldSkip(URL, T0));
        assertFalse(PushBackoffPolicy.shouldSkip(null, T0));
    }

    // ------------------------------------------------------------------------------- the escalation

    @Test
    public void consecutiveRefusalsDoubleTheBackoff()
    {
        assertEquals(60_000L, PushBackoffPolicy.backoffMillisForStrikes(1));
        assertEquals(120_000L, PushBackoffPolicy.backoffMillisForStrikes(2));
        assertEquals(240_000L, PushBackoffPolicy.backoffMillisForStrikes(3));
        assertEquals(480_000L, PushBackoffPolicy.backoffMillisForStrikes(4));
    }

    @Test
    public void theEscalationStopsAtTheOldTwoHourCeiling()
    {
        assertEquals(PushBackoffPolicy.MAX_BACKOFF_MS, PushBackoffPolicy.backoffMillisForStrikes(8));
        assertEquals(PushBackoffPolicy.MAX_BACKOFF_MS, PushBackoffPolicy.backoffMillisForStrikes(9));
        assertEquals(PushBackoffPolicy.MAX_BACKOFF_MS, PushBackoffPolicy.backoffMillisForStrikes(50));
    }

    @Test
    public void aVeryLongStreakNeverOverflowsIntoNoBackoffAtAll()
    {
        // A shift past 63 bits wraps and can come out negative, which shouldSkip() would read as
        // "not blocked" — i.e. an attacker who can hold the relay at 429 would REMOVE the backoff.
        for (int strikes : new int[] {60, 63, 64, 100, Integer.MAX_VALUE})
        {
            final long backoff = PushBackoffPolicy.backoffMillisForStrikes(strikes);
            assertTrue("strikes=" + strikes + " gave " + backoff, backoff > 0L);
            assertEquals(PushBackoffPolicy.MAX_BACKOFF_MS, backoff);
        }
    }

    @Test
    public void repeatedRefusalsActuallyExtendTheWindow()
    {
        PushBackoffPolicy.recordTooManyRequests(URL, T0);
        PushBackoffPolicy.recordTooManyRequests(URL, T0 + 60_000L);   // second strike

        assertEquals(2, PushBackoffPolicy.strikesForTest(URL));
        assertTrue("second strike buys 2 minutes from ITS own time",
                   PushBackoffPolicy.shouldSkip(URL, T0 + 60_000L + 119_000L));
        assertFalse(PushBackoffPolicy.shouldSkip(URL, T0 + 60_000L + 120_000L));
    }

    // ---------------------------------------------------------------------------- success resets it

    @Test
    public void aSuccessfulCallClearsTheStreak()
    {
        PushBackoffPolicy.recordTooManyRequests(URL, T0);
        PushBackoffPolicy.recordTooManyRequests(URL, T0);
        PushBackoffPolicy.recordTooManyRequests(URL, T0);
        assertEquals(3, PushBackoffPolicy.strikesForTest(URL));

        PushBackoffPolicy.recordSuccess(URL);

        assertEquals(0, PushBackoffPolicy.strikesForTest(URL));
        assertFalse(PushBackoffPolicy.shouldSkip(URL, T0));
        // The next refusal starts from one minute again, not from where the old streak left off.
        PushBackoffPolicy.recordTooManyRequests(URL, T0);
        assertFalse(PushBackoffPolicy.shouldSkip(URL, T0 + 60_000L));
    }

    // ------------------------------------------------------------------------------------ isolation

    @Test
    public void oneBlockedUrlDoesNotBlockAnother()
    {
        PushBackoffPolicy.recordTooManyRequests(URL, T0);

        assertTrue(PushBackoffPolicy.shouldSkip(URL, T0));
        assertFalse("a different contact's push URL is unaffected",
                    PushBackoffPolicy.shouldSkip(OTHER, T0));
    }

    // ------------------------------------------------------------------------------------ the clock

    @Test
    public void aBackwardsClockDoesNotStrandAUrlForever()
    {
        PushBackoffPolicy.recordTooManyRequests(URL, T0);
        // Device time corrected backwards by a day: without a guard the window would sit in the
        // future for the whole day and this contact would never be woken again.
        assertFalse(PushBackoffPolicy.shouldSkip(URL, T0 - 86_400_000L));
        assertEquals(0, PushBackoffPolicy.strikesForTest(URL));
    }

    @Test
    public void expiredEntriesArePruned()
    {
        PushBackoffPolicy.recordTooManyRequests(URL, T0);
        PushBackoffPolicy.recordTooManyRequests(OTHER, T0);

        PushBackoffPolicy.pruneExpired(T0 + 30_000L);
        assertEquals("not expired yet", 1, PushBackoffPolicy.strikesForTest(URL));

        PushBackoffPolicy.pruneExpired(T0 + 60_000L);
        assertEquals(0, PushBackoffPolicy.strikesForTest(URL));
        assertEquals(0, PushBackoffPolicy.strikesForTest(OTHER));
    }
}

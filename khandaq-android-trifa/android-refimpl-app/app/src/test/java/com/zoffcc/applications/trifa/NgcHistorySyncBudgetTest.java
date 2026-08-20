package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — per-group bounds on NGC history sync (audit2 finding 1, the non-protocol half).
 *
 * The reviewer asked for per-group row/byte limits and called out notification flooding. These cover the
 * decision logic for all three budgets, including the cases that would silently disable a budget: an
 * unseeded row counter, a failed DB count, and a clock that moves backwards.
 */
public class NgcHistorySyncBudgetTest
{
    private static NgcHistorySyncBudget.State seeded(final int rowsAlreadyStored)
    {
        final NgcHistorySyncBudget.State s = new NgcHistorySyncBudget.State();
        NgcHistorySyncBudget.seed(s, rowsAlreadyStored);
        return s;
    }

    // -------------------------------------------------------------------------------- row budget

    @Test
    public void acceptsAnOrdinarySyncedMessage()
    {
        assertTrue(NgcHistorySyncBudget.fits(seeded(0), 100));
        assertTrue(NgcHistorySyncBudget.fits(seeded(10), 100));
    }

    @Test
    public void refusesOnceTheGroupIsAtItsRowLimit()
    {
        assertTrue(NgcHistorySyncBudget.fits(seeded(NgcHistorySyncBudget.MAX_SYNCED_ROWS_PER_GROUP - 1), 1));
        assertFalse(NgcHistorySyncBudget.fits(seeded(NgcHistorySyncBudget.MAX_SYNCED_ROWS_PER_GROUP), 1));
        assertFalse(NgcHistorySyncBudget.fits(seeded(NgcHistorySyncBudget.MAX_SYNCED_ROWS_PER_GROUP + 999), 1));
    }

    @Test
    public void countsAcceptedRowsTowardsTheLimit()
    {
        final NgcHistorySyncBudget.State s = seeded(NgcHistorySyncBudget.MAX_SYNCED_ROWS_PER_GROUP - 2);

        assertTrue(NgcHistorySyncBudget.fits(s, 10));
        NgcHistorySyncBudget.recordAccepted(s, 10);
        assertTrue(NgcHistorySyncBudget.fits(s, 10));
        NgcHistorySyncBudget.recordAccepted(s, 10);
        assertFalse("now at the limit", NgcHistorySyncBudget.fits(s, 10));
    }

    /**
     * A failed DB count must be seeded as 0, never skipped. An unseeded state would mean "no row budget
     * at all", which is exactly the hole the finding is about.
     */
    @Test
    public void seedingWithAFailedCountStillEnforcesTheBudgetForThisRun()
    {
        final NgcHistorySyncBudget.State s = seeded(0);
        assertTrue(NgcHistorySyncBudget.isSeeded(s));

        for (int i = 0; i < NgcHistorySyncBudget.MAX_SYNCED_ROWS_PER_GROUP; i++)
        {
            assertTrue("row " + i, NgcHistorySyncBudget.fits(s, 1));
            NgcHistorySyncBudget.recordAccepted(s, 1);
        }
        assertFalse("budget must still close after MAX rows in this run", NgcHistorySyncBudget.fits(s, 1));
    }

    @Test
    public void seedingIsIdempotentSoALaterCountCannotRaiseTheBudget()
    {
        final NgcHistorySyncBudget.State s = seeded(NgcHistorySyncBudget.MAX_SYNCED_ROWS_PER_GROUP);
        assertFalse(NgcHistorySyncBudget.fits(s, 1));

        NgcHistorySyncBudget.seed(s, 0);
        assertFalse("a second seed must not reset the budget", NgcHistorySyncBudget.fits(s, 1));
    }

    @Test
    public void aNegativeSeedIsClampedToZero()
    {
        final NgcHistorySyncBudget.State s = seeded(-100);
        assertTrue(NgcHistorySyncBudget.fits(s, 1));
    }

    // ------------------------------------------------------------------------------ byte budget

    @Test
    public void refusesOnceTheSessionByteBudgetIsSpent()
    {
        final NgcHistorySyncBudget.State s = seeded(0);
        final int chunk = 64 * 1024;
        long spent = 0;
        while (spent + chunk <= NgcHistorySyncBudget.MAX_SYNCED_BYTES_PER_GROUP_PER_SESSION)
        {
            assertTrue(NgcHistorySyncBudget.fits(s, chunk));
            NgcHistorySyncBudget.recordAccepted(s, chunk);
            spent += chunk;
        }
        assertFalse("byte budget must close", NgcHistorySyncBudget.fits(s, chunk));
    }

    @Test
    public void acceptsExactlyUpToTheByteBudgetAndRefusesOneMore()
    {
        final NgcHistorySyncBudget.State s = seeded(0);
        final int big = (int) NgcHistorySyncBudget.MAX_SYNCED_BYTES_PER_GROUP_PER_SESSION;

        assertTrue(NgcHistorySyncBudget.fits(s, big));
        NgcHistorySyncBudget.recordAccepted(s, big);
        assertFalse(NgcHistorySyncBudget.fits(s, 1));
    }

    @Test
    public void refusesANegativeTextSize()
    {
        assertFalse(NgcHistorySyncBudget.fits(seeded(0), -1));
        assertFalse(NgcHistorySyncBudget.fits(seeded(0), Integer.MIN_VALUE));
    }

    // ---------------------------------------------------------------------- notification budget

    @Test
    public void allowsTheFirstFewNotificationsThenGoesSilent()
    {
        final NgcHistorySyncBudget.State s = seeded(0);
        final long t0 = 1_000_000L;

        for (int i = 0; i < NgcHistorySyncBudget.MAX_NOTIFICATIONS_PER_GROUP_PER_WINDOW; i++)
        {
            assertTrue("notification " + i, NgcHistorySyncBudget.allowNotification(s, t0 + i));
        }
        assertFalse("one past the window limit", NgcHistorySyncBudget.allowNotification(s, t0 + 10));
        assertFalse(NgcHistorySyncBudget.allowNotification(s, t0 + 11));
    }

    @Test
    public void allowsNotificationsAgainInTheNextWindow()
    {
        final NgcHistorySyncBudget.State s = seeded(0);
        final long t0 = 1_000_000L;

        for (int i = 0; i < NgcHistorySyncBudget.MAX_NOTIFICATIONS_PER_GROUP_PER_WINDOW; i++)
        {
            NgcHistorySyncBudget.allowNotification(s, t0);
        }
        assertFalse(NgcHistorySyncBudget.allowNotification(s, t0));

        final long next = t0 + NgcHistorySyncBudget.NOTIFICATION_WINDOW_MS;
        assertTrue("a fresh window reopens the budget", NgcHistorySyncBudget.allowNotification(s, next));
    }

    /**
     * A device whose clock is corrected backwards must not end up in a window that never expires — that
     * would silence the group's notifications permanently.
     */
    @Test
    public void aBackwardsClockOpensAFreshWindowInsteadOfSilencingForever()
    {
        final NgcHistorySyncBudget.State s = seeded(0);
        final long late = 10_000_000L;

        for (int i = 0; i < NgcHistorySyncBudget.MAX_NOTIFICATIONS_PER_GROUP_PER_WINDOW; i++)
        {
            NgcHistorySyncBudget.allowNotification(s, late);
        }
        assertFalse(NgcHistorySyncBudget.allowNotification(s, late));

        assertTrue("clock moved backwards", NgcHistorySyncBudget.allowNotification(s, late - 5_000_000L));
    }

    // ------------------------------------------------------------------------------- state keying

    @Test
    public void keepsOneBudgetPerGroupAndIsCaseInsensitive()
    {
        NgcHistorySyncBudget.resetAllForTest();

        final NgcHistorySyncBudget.State a = NgcHistorySyncBudget.stateFor("AABBCC");
        final NgcHistorySyncBudget.State b = NgcHistorySyncBudget.stateFor("aabbcc");
        final NgcHistorySyncBudget.State c = NgcHistorySyncBudget.stateFor("ddeeff");

        assertSame("group ids differing only in case are the same group", a, b);
        assertNotSame("different groups must not share a budget", a, c);
    }

    @Test
    public void oneGroupExhaustingItsBudgetDoesNotAffectAnother()
    {
        NgcHistorySyncBudget.resetAllForTest();

        final NgcHistorySyncBudget.State flooded = NgcHistorySyncBudget.stateFor("flooded");
        NgcHistorySyncBudget.seed(flooded, NgcHistorySyncBudget.MAX_SYNCED_ROWS_PER_GROUP);
        final NgcHistorySyncBudget.State quiet = NgcHistorySyncBudget.stateFor("quiet");
        NgcHistorySyncBudget.seed(quiet, 0);

        assertFalse(NgcHistorySyncBudget.fits(flooded, 1));
        assertTrue(NgcHistorySyncBudget.fits(quiet, 1));
    }

    @Test
    public void aNullGroupIdDoesNotThrow()
    {
        NgcHistorySyncBudget.resetAllForTest();
        assertEquals(NgcHistorySyncBudget.stateFor(null), NgcHistorySyncBudget.stateFor(null));
    }

    // ------------------------------------------------------------------------- verdict budget (0x02)

    @Test
    public void acceptsOrdinaryVerdicts()
    {
        final NgcHistorySyncBudget.State s = new NgcHistorySyncBudget.State();
        assertTrue(NgcHistorySyncBudget.claimVerdict(s));
        assertTrue(NgcHistorySyncBudget.claimVerdict(s));
    }

    @Test
    public void refusesVerdictsOnceTheGroupIsAtItsSessionCap()
    {
        final NgcHistorySyncBudget.State s = new NgcHistorySyncBudget.State();
        for (int i = 0; i < NgcHistorySyncBudget.MAX_VERDICTS_PER_GROUP_PER_SESSION; i++)
        {
            assertTrue("claim " + i + " must succeed", NgcHistorySyncBudget.claimVerdict(s));
        }
        assertFalse(NgcHistorySyncBudget.claimVerdict(s));
        assertFalse("and it must stay refused", NgcHistorySyncBudget.claimVerdict(s));
    }

    @Test
    public void aRefusedVerdictDoesNotKeepCountingUp()
    {
        // Guards against an overflow-by-flooding: a refused claim must not increment, or a long
        // enough flood would wrap the counter back to a value that passes again.
        final NgcHistorySyncBudget.State s = new NgcHistorySyncBudget.State();
        s.verdictsThisSession = NgcHistorySyncBudget.MAX_VERDICTS_PER_GROUP_PER_SESSION;
        for (int i = 0; i < 1000; i++)
        {
            assertFalse(NgcHistorySyncBudget.claimVerdict(s));
        }
        assertEquals(NgcHistorySyncBudget.MAX_VERDICTS_PER_GROUP_PER_SESSION, s.verdictsThisSession);
    }

    @Test
    public void oneGroupFloodingVerdictsDoesNotSilenceAnother()
    {
        NgcHistorySyncBudget.resetAllForTest();

        final NgcHistorySyncBudget.State flooded = NgcHistorySyncBudget.stateFor("flooded");
        flooded.verdictsThisSession = NgcHistorySyncBudget.MAX_VERDICTS_PER_GROUP_PER_SESSION;
        final NgcHistorySyncBudget.State quiet = NgcHistorySyncBudget.stateFor("quiet");

        assertFalse(NgcHistorySyncBudget.claimVerdict(flooded));
        assertTrue(NgcHistorySyncBudget.claimVerdict(quiet));
    }

    @Test
    public void theVerdictBudgetIsSeparateFromTheRowBudget()
    {
        // A group at its row cap has still verified nothing, and vice versa: spending one budget
        // must not spend the other, or a busy group would stop showing verified authorship.
        final NgcHistorySyncBudget.State s = seeded(NgcHistorySyncBudget.MAX_SYNCED_ROWS_PER_GROUP);
        assertFalse(NgcHistorySyncBudget.fits(s, 1));
        assertTrue(NgcHistorySyncBudget.claimVerdict(s));

        final NgcHistorySyncBudget.State t = seeded(0);
        t.verdictsThisSession = NgcHistorySyncBudget.MAX_VERDICTS_PER_GROUP_PER_SESSION;
        assertFalse(NgcHistorySyncBudget.claimVerdict(t));
        assertTrue(NgcHistorySyncBudget.fits(t, 1));
    }
}

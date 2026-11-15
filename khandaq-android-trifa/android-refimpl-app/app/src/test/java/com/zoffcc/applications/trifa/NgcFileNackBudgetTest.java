package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.NgcFileNackBudget.MAX_REQUESTS;
import static com.zoffcc.applications.trifa.NgcFileNackBudget.MIN_GAP_MS;
import static com.zoffcc.applications.trifa.NgcFileNackBudget.SILENCE_LIMIT_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56, QA live): how often an incoming group file asks its sender for missing chunks
 * (NgcFileNackBudget), the rule of iOS (PR #133): 24 requests at most, and a file silent for a minute in a row is
 * given up.
 *
 * QA: Android asked for one file every 3.5 to 7 s for thirty minutes, from a sender that never answered.
 */
public class NgcFileNackBudgetTest
{
    private static final String FILE = "groupid:msgid";
    private static final String OTHER = "groupid:other";
    private static final long T0 = 5_000_000L;
    /** The receive watchdog's round, HelperGroup.NGC_NACK_RETRY_MS. */
    private static final long ROUND_MS = 3_500L;

    /**
     * The receive watchdog as HelperGroup.arm_ngc_incoming_file_timeout runs it for a file with its sender online:
     * a silent round, then a request if the file still asks. Returns the requests that went.
     */
    private static int watchdogRounds(final NgcFileNackBudget budget, final long fromMs, final long untilMs)
    {
        int sent = 0;
        for (long now = fromMs; now < untilMs; now += ROUND_MS)
        {
            if (budget.silentRound(FILE, now))
            {
                break;
            }
            if (budget.takeRequest(FILE, now))
            {
                sent++;
            }
        }
        return sent;
    }

    @Test
    public void a_sender_that_never_answers_is_asked_a_few_times_then_the_file_is_given_up()
    {
        // The QA case: thirty minutes of rounds with the sender online and silent.
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        budget.restart(FILE);
        final int sent = watchdogRounds(budget, T0, T0 + 30L * 60L * 1000L);

        assertTrue(budget.gaveUp(FILE));
        // One request every other round (they are MIN_GAP_MS apart) over the minute of silence.
        final int most = (int) (SILENCE_LIMIT_MS / MIN_GAP_MS) + 1;
        assertTrue("sent " + sent, sent >= 2 && sent <= most);
        // Given up, the file asks no more, however long the watchdog goes on.
        assertFalse(budget.takeRequest(FILE, T0 + 31L * 60L * 1000L));
        assertTrue(budget.silentRound(FILE, T0 + 31L * 60L * 1000L));
    }

    @Test
    public void a_file_that_keeps_coming_slowly_is_kept_and_asks_24_times_at_most()
    {
        // iOS's testAGroupFileThatKeepsComingSlowlyIsNotDropped: a chunk, then two silent rounds, forty times.
        // A sender that paces its chunks for a big group (the desktop waits up to 40 s between two) is not silent.
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        budget.restart(FILE);
        long now = T0;
        int sent = 0;
        for (int chunk = 0; chunk < 40; chunk++)
        {
            budget.heard(FILE);
            for (int round = 0; round < 3; round++)
            {
                now += MIN_GAP_MS;
                assertFalse("chunk " + chunk + " round " + round, budget.silentRound(FILE, now));
                if (budget.takeRequest(FILE, now))
                {
                    sent++;
                }
            }
        }
        assertFalse(budget.gaveUp(FILE));
        assertEquals(MAX_REQUESTS, sent);
        assertEquals(MAX_REQUESTS, budget.requestsTaken(FILE));
    }

    @Test
    public void a_chunk_every_40_seconds_is_not_silence()
    {
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        budget.restart(FILE);
        long now = T0;
        for (int chunk = 0; chunk < 30; chunk++)
        {
            for (long gap = 0L; gap < 40_000L; gap += ROUND_MS)
            {
                assertFalse("chunk " + chunk, budget.silentRound(FILE, now + gap));
            }
            now += 40_000L;
            budget.heard(FILE);
        }
        assertFalse(budget.gaveUp(FILE));
    }

    @Test
    public void requests_are_four_seconds_apart_at_the_least()
    {
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        assertTrue(budget.takeRequest(FILE, T0));
        assertFalse(budget.takeRequest(FILE, T0 + 1L));
        assertFalse(budget.takeRequest(FILE, T0 + MIN_GAP_MS - 1L));
        assertTrue(budget.takeRequest(FILE, T0 + MIN_GAP_MS));
        // Another file has its own requests.
        assertTrue(budget.takeRequest(OTHER, T0 + MIN_GAP_MS));
        assertEquals(2, budget.requestsTaken(FILE));
        assertEquals(1, budget.requestsTaken(OTHER));
    }

    @Test
    public void a_begin_or_a_chunk_ends_the_silence_but_gives_no_request_back()
    {
        // An answer to each request starts with a BEGIN (sendGroupFileSelectiveOnWorker): it must not make the
        // requests endless, as they were.
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        budget.restart(FILE);
        long now = T0;
        int sent = 0;
        for (int i = 0; i < 200; i++)
        {
            now += MIN_GAP_MS;
            if (budget.takeRequest(FILE, now))
            {
                sent++;
            }
            budget.heard(FILE);
        }
        assertEquals(MAX_REQUESTS, sent);
        assertFalse(budget.gaveUp(FILE));
    }

    @Test
    public void the_user_asking_again_gives_all_requests_back()
    {
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        budget.restart(FILE);
        watchdogRounds(budget, T0, T0 + 10L * 60L * 1000L);
        assertTrue(budget.gaveUp(FILE));

        // "Download again" (HelperGroup.mark_ngc_manual_download_requested), or the first BEGIN of the file.
        budget.restart(FILE);
        assertFalse(budget.gaveUp(FILE));
        assertEquals(0, budget.requestsTaken(FILE));
        assertTrue(budget.takeRequest(FILE, T0 + 11L * 60L * 1000L));
    }

    @Test
    public void a_chunk_after_the_file_was_given_up_brings_it_back()
    {
        // Its sender sends it again (a send of ours that waited for a member goes again from its BEGIN).
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        budget.restart(FILE);
        watchdogRounds(budget, T0, T0 + 5L * 60L * 1000L);
        assertTrue(budget.gaveUp(FILE));
        final int taken = budget.requestsTaken(FILE);

        budget.heard(FILE);
        assertFalse(budget.gaveUp(FILE));
        assertFalse(budget.silentRound(FILE, T0 + 6L * 60L * 1000L));
        assertEquals(taken, budget.requestsTaken(FILE));
    }

    @Test
    public void a_sender_away_is_not_the_files_silence()
    {
        // The sender went offline mid-file and comes back ten minutes later: the file asks again, and is given up
        // only after a minute of silence of a sender that is there.
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        budget.restart(FILE);
        assertFalse(budget.silentRound(FILE, T0));
        assertTrue(budget.takeRequest(FILE, T0));
        for (long now = T0 + ROUND_MS; now < T0 + 10L * 60L * 1000L; now += ROUND_MS)
        {
            budget.senderAway(FILE);
        }
        final long back = T0 + 10L * 60L * 1000L;
        assertFalse(budget.silentRound(FILE, back));
        assertTrue(budget.takeRequest(FILE, back));
        assertFalse(budget.silentRound(FILE, back + SILENCE_LIMIT_MS - 1L));
        assertTrue(budget.silentRound(FILE, back + SILENCE_LIMIT_MS));
    }

    @Test
    public void a_file_that_came_whole_is_forgotten()
    {
        final NgcFileNackBudget budget = new NgcFileNackBudget();
        assertTrue(budget.takeRequest(FILE, T0));
        budget.forget(FILE);
        assertEquals(0, budget.requestsTaken(FILE));
        assertFalse(budget.gaveUp(FILE));
        // A file the watchdog never saw is not given up by a round of another one, nor by its sender's absence.
        budget.senderAway(OTHER);
        assertFalse(budget.gaveUp(OTHER));
    }
}

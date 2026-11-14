package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): when an undelivered message wakes its friend again.
 *
 * <p>Push was off from 0.2.7 on, so when it came back every old undelivered message had all ten of its slots
 * overdue, and each 5-second pass used one of them per message: all ten wakes within a minute, thirty calls a pass
 * for a friend with thirty such messages. A message now goes for the last slot it has reached and never replays
 * the ones it missed, and a friend gets one wake a pass for all of their due messages.
 */
public class PushWakeSlotsTest
{
    private static final long T0 = 1_700_000_000_000L;
    private static final long SECOND = 1_000L;
    private static final long MINUTE = 60L * SECOND;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long PASS_MS = 5L * SECOND;
    private static final int SLOTS = PushBackoffPolicy.WAKE_SLOTS_MS.length;

    private static final String ALICE = "A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1";
    private static final String BOB = "B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2";

    // ------------------------------------------------------------------ the schedule of a fresh message

    @Test
    public void theScheduleIsTheOneTheServiceUses()
    {
        assertArrayEquals(new long[]{10 * SECOND, 45 * SECOND, 2 * MINUTE, 5 * MINUTE, 12 * MINUTE, 30 * MINUTE,
                                     HOUR, 2 * HOUR, 4 * HOUR, 6 * HOUR}, PushBackoffPolicy.WAKE_SLOTS_MS);
        assertSame(PushBackoffPolicy.WAKE_SLOTS_MS, TrifaToxService.PUSH_WAKE_BACKOFF_MS);
        assertEquals(SLOTS, TrifaToxService.PUSH_WAKE_MAX_ATTEMPTS);
    }

    @Test
    public void aFreshMessageIsDueForEachSlotExactlyAtItsTime()
    {
        for (int n = 0; n < SLOTS; n++)
        {
            final long slotTime = PushBackoffPolicy.WAKE_SLOTS_MS[n];
            assertEquals("slot " + n + " a millisecond early", -1,
                         PushBackoffPolicy.slotToSend(T0, T0 + slotTime - 1, n));
            assertEquals("slot " + n + " on time", n, PushBackoffPolicy.slotToSend(T0, T0 + slotTime, n));
            assertEquals("slot " + n + " after its wake", -1, PushBackoffPolicy.slotToSend(T0, T0 + slotTime, n + 1));
        }
    }

    @Test
    public void fiveSecondPassesKeepTheFreshScheduleAsItWas()
    {
        // The service checks every 5 seconds, from whenever it happens to run.
        final List<Integer> slots = new ArrayList<>();
        int sentPush = 0;
        for (long now = T0 + 1_234L; now <= T0 + 7 * HOUR; now += PASS_MS)
        {
            final int slot = PushBackoffPolicy.slotToSend(T0, now, sentPush);
            if (slot >= 0)
            {
                final long late = (now - T0) - PushBackoffPolicy.WAKE_SLOTS_MS[slot];
                assertTrue("slot " + slot + " went out " + late + " ms late", (late >= 0) && (late < PASS_MS));
                slots.add(slot);
                sentPush = slot + 1;
            }
        }
        assertEquals(Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), slots);
    }

    // ------------------------------------------------------------------ the burst after the update

    @Test
    public void anHourOldMessageIsWokenForOnceAndGoesOnAtTheTwoHourSlot()
    {
        final long now = T0 + HOUR + 5 * MINUTE;
        final int slot = PushBackoffPolicy.slotToSend(T0, now, 0);
        assertEquals("the 1 h slot, not the 10 s one", 6, slot);

        final int sentPush = slot + 1;
        assertEquals("nothing on the next pass", -1, PushBackoffPolicy.slotToSend(T0, now + PASS_MS, sentPush));
        assertEquals("nor just before 2 h", -1, PushBackoffPolicy.slotToSend(T0, T0 + 2 * HOUR - 1, sentPush));
        assertEquals("the 2 h slot", 7, PushBackoffPolicy.slotToSend(T0, T0 + 2 * HOUR, sentPush));
    }

    @Test
    public void aMessageOlderThanTheWholeScheduleIsWokenForOnceAndIsDone()
    {
        final long now = T0 + 3 * DAY;
        final int slot = PushBackoffPolicy.slotToSend(T0, now, 0);
        assertEquals(SLOTS - 1, slot);

        assertEquals(-1, PushBackoffPolicy.slotToSend(T0, now + PASS_MS, slot + 1));
        assertEquals(-1, PushBackoffPolicy.slotToSend(T0, now + 30 * DAY, slot + 1));
    }

    @Test
    public void missedSlotsAreSkippedNotReplayed()
    {
        // Two wakes went out, then the app was closed for a while: the 2 m and 5 m slots are gone.
        assertEquals(4, PushBackoffPolicy.slotToSend(T0, T0 + 13 * MINUTE, 2));
        assertEquals(-1, PushBackoffPolicy.slotToSend(T0, T0 + 13 * MINUTE + PASS_MS, 5));
    }

    @Test
    public void nothingIsDueOnceEverySlotIsUsed()
    {
        assertEquals(-1, PushBackoffPolicy.slotToSend(T0, T0 + 3 * DAY, SLOTS));
        assertEquals(-1, PushBackoffPolicy.slotToSend(T0, T0 + 3 * DAY, SLOTS + 1));
        assertEquals(-1, PushBackoffPolicy.slotToSend(T0, T0 + 3 * DAY, Integer.MAX_VALUE));
    }

    @Test
    public void aNegativeCountReadsAsNoSlotUsedAndAClockBehindTheMessageWakesNobody()
    {
        assertEquals(0, PushBackoffPolicy.slotToSend(T0, T0 + 10 * SECOND, -1));
        assertEquals(-1, PushBackoffPolicy.slotToSend(T0, T0 - HOUR, 0));
        assertEquals(-1, PushBackoffPolicy.slotToSend(T0, T0, 0));
    }

    @Test
    public void onlyAWakeThatGotThroughOrARefusalThatCountsUsesUpTheSlot()
    {
        for (final int code : new int[]{200, 201, 204, 299, 300, 401, 404, 410, 500, 502, 503, 599})
        {
            assertTrue("HTTP " + code, PushBackoffPolicy.wakeAttemptUsed(code));
        }
        // 429 has its own backoff; -1 is "nothing went out" or "no answer".
        for (final int code : new int[]{-1, 0, 100, 199, 429, 600})
        {
            assertFalse("HTTP " + code, PushBackoffPolicy.wakeAttemptUsed(code));
        }
    }

    // ------------------------------------------------------------------ one wake per friend per pass

    @Test
    public void aFriendGetsOneWakeAPassForAllTheirDueMessages()
    {
        final long now = T0 + 2 * DAY;
        final PushBackoffPolicy.DueWakes due = new PushBackoffPolicy.DueWakes();
        due.add(ALICE, 1, T0, 0, now);
        due.add(BOB, 2, T0 + MINUTE, 0, now);
        due.add(ALICE, 3, T0 + 2 * MINUTE, 0, now);
        due.add(ALICE, 4, now - 5 * SECOND, 0, now); // too fresh for its first wake
        due.add(BOB, 5, T0 + 3 * MINUTE, SLOTS, now); // every slot used
        due.add(null, 6, T0, 0, now);
        due.add("", 7, T0, 0, now);

        final Map<String, List<PushBackoffPolicy.DueWake>> byFriend = due.byFriend();
        assertEquals("friends in the order of their oldest due message", Arrays.asList(ALICE, BOB),
                     new ArrayList<>(byFriend.keySet()));
        assertEquals(Arrays.asList(1L, 3L), ids(byFriend.get(ALICE)));
        assertEquals(Arrays.asList(2L), ids(byFriend.get(BOB)));
    }

    @Test
    public void thirtyOldMessagesOfOneFriendMeanOneWakeAndThenNothingIsDue()
    {
        final long now = T0 + 3 * DAY;
        final long[] sentAt = new long[30];
        final int[] sentPush = new int[30];

        PushBackoffPolicy.DueWakes due = new PushBackoffPolicy.DueWakes();
        for (int i = 0; i < 30; i++)
        {
            sentAt[i] = T0 + i * HOUR;
            due.add(ALICE, i, sentAt[i], sentPush[i], now);
        }
        assertEquals("one call for the friend", 1, due.byFriend().size());
        final List<PushBackoffPolicy.DueWake> wakes = due.byFriend().get(ALICE);
        assertEquals(30, wakes.size());
        for (final PushBackoffPolicy.DueWake d : wakes)
        {
            assertEquals("message " + d.messageId, SLOTS - 1, d.slot);
            sentPush[(int) d.messageId] = d.slot + 1; // the answer counted
        }

        due = new PushBackoffPolicy.DueWakes();
        for (int i = 0; i < 30; i++)
        {
            due.add(ALICE, i, sentAt[i], sentPush[i], now + PASS_MS);
        }
        assertTrue("nothing left to wake for", due.byFriend().isEmpty());
    }

    @Test
    public void oneWakeMovesEachOfTheFriendsMessagesToItsOwnNextSlot()
    {
        final long now = T0 + 2 * HOUR;
        final long[] sentAt = {now - 65 * MINUTE, now - 50 * SECOND, now - 12 * SECOND};
        final int[] sentPush = {3, 0, 0};

        PushBackoffPolicy.DueWakes due = new PushBackoffPolicy.DueWakes();
        for (int i = 0; i < sentAt.length; i++)
        {
            due.add(ALICE, i, sentAt[i], sentPush[i], now);
        }
        final List<PushBackoffPolicy.DueWake> wakes = due.byFriend().get(ALICE);
        assertEquals(1, due.byFriend().size());
        assertEquals(Arrays.asList(6, 1, 0), slots(wakes));
        for (final PushBackoffPolicy.DueWake d : wakes)
        {
            sentPush[(int) d.messageId] = d.slot + 1;
        }

        due = new PushBackoffPolicy.DueWakes();
        for (int i = 0; i < sentAt.length; i++)
        {
            due.add(ALICE, i, sentAt[i], sentPush[i], now + PASS_MS);
        }
        assertTrue("each waits for its own next slot", due.byFriend().isEmpty());
    }

    // ------------------------------------------------------------------ the service uses all of the above

    @Test
    public void theServiceWakesEachFriendOnceAPassAndNotEachMessage() throws IOException
    {
        final String service = read("src/main/java/com/zoffcc/applications/trifa/TrifaToxService.java",
                                    "app/src/main/java/com/zoffcc/applications/trifa/TrifaToxService.java");
        final int start = service.indexOf("static void resend_push_for_v3_messages()");
        assertTrue("resend_push_for_v3_messages is in TrifaToxService", start >= 0);
        final String body = service.substring(start, service.indexOf("static void resend_v3_messages(", start));

        assertTrue(body.contains("new PushBackoffPolicy.DueWakes()"));
        assertTrue(body.contains("friend_call_push_url_for_due_messages("));
        assertFalse("no wake per message", body.contains("friend_call_push_url("));
    }

    private static List<Long> ids(final List<PushBackoffPolicy.DueWake> wakes)
    {
        final List<Long> ids = new ArrayList<>();
        for (final PushBackoffPolicy.DueWake d : wakes)
        {
            ids.add(d.messageId);
        }
        return ids;
    }

    private static List<Integer> slots(final List<PushBackoffPolicy.DueWake> wakes)
    {
        final List<Integer> slots = new ArrayList<>();
        for (final PushBackoffPolicy.DueWake d : wakes)
        {
            slots.add(d.slot);
        }
        return slots;
    }

    private static String read(final String... candidates) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String candidate : candidates)
        {
            final File file = new File(candidate);
            if (file.isFile())
            {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(candidates[0] + " not found from " + new File("").getAbsolutePath());
    }
}

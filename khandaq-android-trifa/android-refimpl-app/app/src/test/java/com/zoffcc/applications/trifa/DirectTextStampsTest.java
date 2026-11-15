package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): the parts of a long text to one person showed out of order on the iPhone. It sorts a text by
 * the whole seconds of its msgV3 stamp (the smaller of the stamp and the arrival), every send and resend takes
 * that stamp from the row's sent time, and the parts were a tenth of a second apart, so up to ten had one stamp.
 * Now each part is a second after the one before and a text goes after the last one sent to that person, by the
 * rule iOS stamps its texts with (+textStampAfterStamp:now:), while a wake push still finds its own row.
 */
public class DirectTextStampsTest
{
    private static final String FRIEND = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90";
    private static final String OTHER = "0F1E2D3C4B5A69788796A5B4C3D2E1F00F1E2D3C4B5A69788796A5B4C3D2E1F0";
    private static final long NOW = 1_731_600_000_250L;

    @Test
    public void a_stamp_is_the_clock_or_a_second_after_the_stamp_before_it()
    {
        // The cases of testAStampIsTheClockOrASecondAfterTheStampBeforeIt (OCTTextPartsAndGroupSendTests, iOS).
        // The first text to a person, and one written once the clock has passed the text before it: the clock.
        assertEquals(1000L, stamp(0L, 1000_700L));
        assertEquals(1000_700L, DirectTextStamps.sentTimestampAfter(0L, 1000_700L));
        assertEquals(1000L, stamp(999_000L, 1000_000L));
        assertEquals(1005L, stamp(1000_000L, 1005_200L));
        assertEquals(1005_200L, DirectTextStamps.sentTimestampAfter(1000_000L, 1005_200L));
        // One written within the second of the text before it, or while the texts run ahead of the clock: a
        // second after it, so the two cannot tie at the receiver.
        assertEquals(1001L, stamp(1000_000L, 1000_900L));
        assertEquals(1041L, stamp(1040_000L, 1000_000L));
        // A clock set back by an hour or more starts the stamps from it again.
        assertEquals(4600L, stamp((1000L + 3599L) * 1000L, 1000_000L));
        assertEquals(1000L, stamp((1000L + 3600L) * 1000L, 1000_000L));
    }

    @Test
    public void its_whole_seconds_are_those_of_the_ios_rule_and_two_texts_are_far_enough_apart_for_a_wake()
    {
        final Random random = new Random(20261115L);
        for (int i = 0; i < 200_000; i++)
        {
            final long now = 1_000_000_000_000L + random.nextInt(10_000_000);
            final long last = Math.max(1L, now + random.nextInt(8_000_000) - 4_000_000);
            final long sent = DirectTextStamps.sentTimestampAfter(last, now);
            assertEquals("last=" + last + " now=" + now, iosStampAfterStamp(last / 1000L, now / 1000L), sent / 1000L);
            assertTrue("before the clock: last=" + last + " now=" + now, sent >= now);
            if (last - now < DirectTextStamps.MAX_LEAD_SEC * 1000L - 1000L)
            {
                assertTrue("too close: last=" + last + " now=" + now, sent - last >= DirectTextStamps.MIN_GAP_MS);
            }
        }
    }

    @Test
    public void the_parts_of_a_text_and_the_text_after_them_have_stamps_a_second_apart()
    {
        final DirectTextStamps stamps = new DirectTextStamps();
        final long first = stamps.firstSentTimestamp(FRIEND, 5, NOW);
        assertEquals("the first text to the person goes at the clock", NOW, first);
        for (int i = 1; i < 5; i++)
        {
            assertEquals(first / 1000L + i, MessageSplitter.partSentTimestamp(first, i) / 1000L);
        }
        final long lastPart = MessageSplitter.partSentTimestamp(first, 4);

        // Sent a moment later, while the parts still run ahead of the clock: after the last part, a second on.
        final long next = stamps.firstSentTimestamp(FRIEND, 1, NOW + 200L);
        assertEquals(lastPart / 1000L + 1L, next / 1000L);
        assertTrue("sorts among the parts here", next > lastPart);

        // A long text right after it goes on from there.
        final long longNext = stamps.firstSentTimestamp(FRIEND, 3, NOW + 400L);
        assertEquals(next / 1000L + 1L, longNext / 1000L);

        // Once the clock has passed them: the clock.
        assertEquals(NOW + 60_000L, stamps.firstSentTimestamp(FRIEND, 1, NOW + 60_000L));

        // The texts to one person do not hold back those to another, whichever way a key is written.
        assertEquals(NOW + 300L, stamps.firstSentTimestamp(OTHER, 1, NOW + 300L));
        assertEquals((NOW + 60_000L) / 1000L + 1L,
                     stamps.firstSentTimestamp(FRIEND.toLowerCase(java.util.Locale.ROOT), 1, NOW + 60_100L) / 1000L);
    }

    @Test
    public void a_text_goes_after_the_parts_of_a_waiting_text_split_ahead_of_the_clock()
    {
        final DirectTextStamps stamps = new DirectTextStamps();
        stamps.firstSentTimestamp(FRIEND, 1, NOW);
        // resend_unsent_text_messages split it into four parts, the last three seconds after it.
        stamps.sentAt(FRIEND, MessageSplitter.partSentTimestamp(NOW, 3));
        assertEquals(NOW / 1000L + 4L, stamps.firstSentTimestamp(FRIEND, 1, NOW + 500L) / 1000L);

        // An old row split later does not move the next text back or ahead.
        stamps.sentAt(FRIEND, NOW - 86_400_000L);
        assertEquals(NOW + 120_000L, stamps.firstSentTimestamp(FRIEND, 1, NOW + 120_000L));
    }

    @Test
    public void each_text_and_each_part_finds_its_own_row_for_a_wake()
    {
        final DirectTextStamps stamps = new DirectTextStamps();
        final List<Long> rows = new ArrayList<>();
        addText(rows, stamps, 3, NOW);
        // Right after it, while its parts run ahead of the clock.
        addText(rows, stamps, 1, NOW + 30L);
        addText(rows, stamps, 2, NOW + 2_950L);
        // A text at .999 of a second once the clock has passed the texts before it, and one 51 ms later, in the
        // next second: the clock would put the two 51 ms apart.
        final long edge = (rows.get(rows.size() - 1) / 1000L + 2L) * 1000L + 999L;
        addText(rows, stamps, 1, edge);
        assertEquals(edge, (long) rows.get(rows.size() - 1));
        addText(rows, stamps, 1, edge + 51L);
        assertEquals(edge + DirectTextStamps.MIN_GAP_MS, (long) rows.get(rows.size() - 1));
        addText(rows, stamps, 4, NOW + 600_000L);

        // Texts in bursts, long ones among them, now and then a pause that lets the clock pass them.
        final Random random = new Random(156L);
        long now = NOW + 700_000L;
        for (int i = 0; i < 1_000; i++)
        {
            now += random.nextInt(4) == 0 ? 2_000 + random.nextInt(60_000) : random.nextInt(120);
            addText(rows, stamps, 1 + random.nextInt(random.nextInt(10) == 0 ? 40 : 3), now);
        }

        for (int i = 1; i < rows.size(); i++)
        {
            assertTrue("rows out of order at " + i, rows.get(i) > rows.get(i - 1));
            assertTrue("two stamps in one second at " + i, rows.get(i) / 1000L > rows.get(i - 1) / 1000L);
        }
        for (final long row : rows)
        {
            assertEquals("the wake for " + row + " counts on another row", row, wakeRow(rows, row));
        }
    }

    /** The whole seconds DirectTextStamps.sentTimestampAfter gives. */
    private static long stamp(final long lastSentMs, final long nowMs)
    {
        return DirectTextStamps.sentTimestampAfter(lastSentMs, nowMs) / 1000L;
    }

    /** OCTSubmanagerChatsImpl +textStampAfterStamp:now: (khandaq-ios), in whole seconds. */
    private static long iosStampAfterStamp(final long lastStamp, final long clock)
    {
        if ((lastStamp < clock) || (lastStamp - clock >= 3600L))
        {
            return clock;
        }
        return lastStamp + 1L;
    }

    /** The rows a text of parts parts written at nowMs adds, as the send loop of MessageListActivity writes them. */
    private static void addText(final List<Long> rows, final DirectTextStamps stamps, final int parts, final long nowMs)
    {
        final long first = stamps.firstSentTimestamp(FRIEND, parts, nowMs);
        assertTrue(first >= nowMs);
        for (int i = 0; i < parts; i++)
        {
            rows.add(MessageSplitter.partSentTimestamp(first, i));
        }
    }

    /**
     * The row HelperMessage.update_message_in_db_sent_push_set counts a wake for sent time ts on: the earliest of
     * the person's sent rows in the window around ts. sent_timestampBetween leaves out both ends; this takes them
     * in, which is stricter.
     */
    private static long wakeRow(final List<Long> rows, final long ts)
    {
        long found = -1L;
        for (final long row : rows)
        {
            if ((row >= ts - TRIFAGlobals.PUSH_URL_TRIGGER_GET_MESSAGE_FOR_delta_ms_prev)
                && (row <= ts + TRIFAGlobals.PUSH_URL_TRIGGER_GET_MESSAGE_FOR_delta_ms_after)
                && ((found < 0L) || (row < found)))
            {
                found = row;
            }
        }
        return found;
    }
}

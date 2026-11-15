package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.NgcUploadPace.BURST_BYTES;
import static com.zoffcc.applications.trifa.NgcUploadPace.BYTES_PER_SECOND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56, QA live): the pace of our group file packets (NgcUploadPace), the desktop's Air::UploadPace.
 *
 * QA: a resent group file of 11 MB was handed to toxcore chunk after chunk; its send array (8192 entries, 28 per
 * chunk) filled after some 290 chunks, every send was refused (-99), and the file was said not sent 20 s later. The
 * member got the BEGIN and nothing else, and toxcore dropped it ("Send array stuck; timing out peer").
 */
public class NgcUploadPaceTest
{
    /** A chunk packet as it goes on the wire: the largest payload and its header. */
    private static final long CHUNK_PACKET = NgcGroupFileTransfer.CHUNK_PAYLOAD_MAX + NgcGroupFileTransfer.FILE_CHUNK_HEADER;
    private static final long T0 = 1_000_000L;

    /** Sends chunks as fast as the pace lets them, from T0 for durationMs; the bytes it let go. */
    private static long bytesLetGo(final NgcUploadPace pace, final int members, final long durationMs)
    {
        long now = T0;
        long bytes = 0L;
        while (now < T0 + durationMs)
        {
            if (pace.ready(now))
            {
                final long cost = NgcUploadPace.cost(CHUNK_PACKET, members);
                pace.spend(cost);
                bytes += cost;
            }
            else
            {
                now += Math.max(1L, pace.msUntilReady(now));
            }
        }
        return bytes;
    }

    @Test
    public void chunks_go_at_the_rate_however_fast_they_are_handed_over()
    {
        // Ten seconds of a sender that never waits: about ten seconds of the rate, not the whole file at once.
        final long bytes = bytesLetGo(new NgcUploadPace(), 1, 10_000L);
        final long expected = BYTES_PER_SECOND * 10L;
        assertTrue("let go " + bytes + " of " + expected, bytes >= expected - CHUNK_PACKET);
        assertTrue("let go " + bytes + " of " + expected, bytes <= expected + BURST_BYTES + 2L * CHUNK_PACKET);
    }

    @Test
    public void a_packet_to_more_members_costs_more()
    {
        // The rate is what this device uploads: one chunk to eight members takes eight times the room.
        assertEquals(8L * CHUNK_PACKET, NgcUploadPace.cost(CHUNK_PACKET, 8));
        assertEquals(CHUNK_PACKET, NgcUploadPace.cost(CHUNK_PACKET, 1));
        // Nobody is still one packet: what goes out is paid for.
        assertEquals(CHUNK_PACKET, NgcUploadPace.cost(CHUNK_PACKET, 0));

        final long toOne = bytesLetGo(new NgcUploadPace(), 1, 20_000L) / CHUNK_PACKET;
        final long toEight = bytesLetGo(new NgcUploadPace(), 8, 20_000L) / (8L * CHUNK_PACKET);
        assertTrue("one " + toOne + " eight " + toEight, toEight * 8L <= toOne + 8L);
        assertTrue("one " + toOne + " eight " + toEight, toEight * 8L >= toOne - 16L);
    }

    @Test
    public void the_first_packet_goes_at_once_and_a_pause_gives_one_burst_at_most()
    {
        final NgcUploadPace pace = new NgcUploadPace();
        assertTrue(pace.ready(T0));
        assertEquals(0L, pace.msUntilReady(T0));

        // Spend the burst; an hour of silence gives back one burst, not an hour of the rate.
        pace.spend(BURST_BYTES);
        assertFalse(pace.ready(T0));
        final long later = T0 + 60L * 60L * 1000L;
        assertTrue(pace.ready(later));
        pace.spend(BURST_BYTES);
        assertFalse(pace.ready(later));
    }

    /** A pace that let its first packet go at T0, one that cost this much. */
    private static NgcUploadPace afterFirstPacket(final long cost)
    {
        final NgcUploadPace pace = new NgcUploadPace();
        assertTrue(pace.ready(T0));
        pace.spend(cost);
        return pace;
    }

    @Test
    public void the_wait_it_names_is_the_wait_it_keeps()
    {
        // Debts from none to more than twelve chunks to a member each (the first packet may cost more than the burst).
        for (long debt = 0L; debt <= 12L * CHUNK_PACKET; debt += 997L)
        {
            final long cost = BURST_BYTES + debt;
            final long wait = afterFirstPacket(cost).msUntilReady(T0);
            assertTrue("debt=" + debt, wait > 0L);
            // Ready when it says, and not a millisecond earlier: the sender sleeps that long, not longer.
            assertTrue("debt=" + debt + " wait=" + wait, afterFirstPacket(cost).ready(T0 + wait));
            assertFalse("debt=" + debt + " wait=" + wait, afterFirstPacket(cost).ready(T0 + wait - 1L));
        }
        // A pace with credit left names no wait.
        assertEquals(0L, afterFirstPacket(1L).msUntilReady(T0));
    }

    @Test
    public void a_clock_that_goes_back_gives_nothing()
    {
        final NgcUploadPace pace = new NgcUploadPace();
        assertTrue(pace.ready(T0));
        pace.spend(BURST_BYTES + CHUNK_PACKET);
        final long wait = pace.msUntilReady(T0);
        assertTrue(wait > 0L);
        assertFalse(pace.ready(T0 - 10_000L));
        assertFalse(pace.ready(T0));
        assertTrue(pace.ready(T0 + wait));
    }
}

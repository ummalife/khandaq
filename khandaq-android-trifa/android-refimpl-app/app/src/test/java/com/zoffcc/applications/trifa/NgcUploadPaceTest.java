package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.NgcUploadPace.BURST_BYTES;
import static com.zoffcc.applications.trifa.NgcUploadPace.BURST_MS;
import static com.zoffcc.applications.trifa.NgcUploadPace.BYTES_PER_SECOND;
import static com.zoffcc.applications.trifa.NgcUploadPace.RELAYED_BYTES_PER_SECOND;
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

    // ---- KHANDAQ (0.2.57): the pace of the relays ----
    //
    // QA 2026-11-16: over a TCP relay a member lost a group file sent at 256 and at 128 KiB/s and got it whole at 64
    // and at 32; an iPhone over a relay got 27 of the 86 chunks Android sent it at 512 KiB/s, and the file was dropped.

    private static final int NONE = ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value;
    private static final int TCP = ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP.value;
    private static final int UDP = ToxVars.TOX_CONNECTION.TOX_CONNECTION_UDP.value;
    /** Our own peer in the group. */
    private static final long SELF = 0L;
    private static final long RELAY_BURST = RELAYED_BYTES_PER_SECOND * BURST_MS / 1000L;

    /** How toxcore reaches each peer, by peer id: statuses[0] is our own peer. */
    private static NgcUploadPace.PeerConnection connections(final int... statuses)
    {
        return peerId -> statuses[(int) peerId];
    }

    /**
     * Sends chunks to this many members as fast as both paces let them, from {@code fromMs} for durationMs, as
     * NgcGroupFileTransfer does (awaitPace, spendPace); the bytes each member got.
     */
    private static long bytesToEachMember(final NgcUploadPace all, final NgcUploadPace relays, final int members,
                                          final boolean overRelay, final long fromMs, final long durationMs)
    {
        long now = fromMs;
        long bytes = 0L;
        while (now < fromMs + durationMs)
        {
            final long waitMs = NgcUploadPace.msUntilReady(all, relays, overRelay, now);
            if (waitMs == 0L)
            {
                NgcUploadPace.spend(all, relays, CHUNK_PACKET, members, overRelay);
                bytes += CHUNK_PACKET;
            }
            else
            {
                now += waitMs;
            }
        }
        return bytes;
    }

    @Test
    public void a_group_with_a_member_over_a_relay_goes_at_the_pace_of_the_relays()
    {
        assertTrue(RELAYED_BYTES_PER_SECOND < BYTES_PER_SECOND);
        assertEquals(64L * 1024L, RELAYED_BYTES_PER_SECOND);
        // Three members, the second over a relay: every packet waits for the relays too.
        final long[] members = {1L, 2L, 3L};
        assertTrue(NgcUploadPace.overRelay(members, SELF, connections(UDP, UDP, TCP, UDP)));

        final long bytes = bytesToEachMember(new NgcUploadPace(), new NgcUploadPace(RELAYED_BYTES_PER_SECOND),
                                             members.length, true, T0, 10_000L);
        // Ten seconds of 64 KiB/s to each member, give or take the relays' burst and a chunk; not 512/3 KiB/s.
        final long expected = RELAYED_BYTES_PER_SECOND * 10L;
        assertTrue("each got " + bytes + " of " + expected, bytes >= expected - CHUNK_PACKET);
        assertTrue("each got " + bytes + " of " + expected, bytes <= expected + RELAY_BURST + CHUNK_PACKET);
    }

    @Test
    public void a_group_whose_members_are_all_direct_keeps_the_whole_pace()
    {
        // Our own peer reads TCP while this device has no UDP of its own; it is not a member over a relay.
        final long[] members = {1L, 2L, 3L};
        final NgcUploadPace.PeerConnection connection = connections(TCP, UDP, UDP, UDP);
        assertFalse(NgcUploadPace.overRelay(members, SELF, connection));
        assertFalse(NgcUploadPace.overRelay(new long[]{SELF, 1L, 2L, 3L}, SELF, connection));

        final NgcUploadPace relays = new NgcUploadPace(RELAYED_BYTES_PER_SECOND);
        assertTrue(relays.ready(T0));
        final long bytes = bytesToEachMember(new NgcUploadPace(), relays, members.length, false, T0, 10_000L);
        // 512 KiB/s of upload, a third of it to each member: as before 0.2.57.
        final long expected = BYTES_PER_SECOND * 10L;
        final long uploaded = bytes * members.length;
        assertTrue("uploaded " + uploaded + " of " + expected, uploaded >= expected - 3L * CHUNK_PACKET);
        assertTrue("uploaded " + uploaded + " of " + expected, uploaded <= expected + BURST_BYTES + 3L * CHUNK_PACKET);
        assertTrue(bytes > 2L * RELAYED_BYTES_PER_SECOND * 10L);
        // Nothing of it was counted at the pace of the relays: its burst of T0 is all there.
        assertEquals(0L, relays.msUntilReady(T0));
    }

    @Test
    public void a_private_resend_to_a_direct_member_is_not_held_by_the_relays()
    {
        final NgcUploadPace all = new NgcUploadPace();
        final NgcUploadPace relays = new NgcUploadPace(RELAYED_BYTES_PER_SECOND);
        // Our own peer, member 1 direct, member 2 over a relay. A chunk goes to both, and the relays owe for it.
        final NgcUploadPace.PeerConnection connection = connections(UDP, UDP, TCP);
        assertTrue(NgcUploadPace.overRelay(new long[]{1L, 2L}, SELF, connection));
        assertEquals(0L, NgcUploadPace.msUntilReady(all, relays, true, T0));
        NgcUploadPace.spend(all, relays, CHUNK_PACKET, 2, true);
        final long relaysWait = relays.msUntilReady(T0);
        assertTrue(relaysWait > 0L);

        // Member 2 asks for a chunk again: a private packet looks at its member, over a relay, so it waits.
        assertTrue(NgcUploadPace.overRelay(new long[]{2L}, SELF, connection));
        assertEquals(relaysWait, NgcUploadPace.msUntilReady(all, relays, true, T0));

        // Member 1 asks: its resend goes now, and on at the whole pace while the relays still owe.
        assertFalse(NgcUploadPace.overRelay(new long[]{1L}, SELF, connection));
        assertEquals(0L, NgcUploadPace.msUntilReady(all, relays, false, T0));
        final long bytes = bytesToEachMember(all, relays, 1, false, T0, 10_000L);
        final long expected = BYTES_PER_SECOND * 10L;
        assertTrue("got " + bytes + " of " + expected, bytes >= expected - 3L * CHUNK_PACKET);
        assertTrue("got " + bytes + " of " + expected, bytes <= expected + BURST_BYTES + CHUNK_PACKET);
        // It paid nothing at the pace of the relays.
        assertEquals(relaysWait, relays.msUntilReady(T0));
    }

    @Test
    public void a_packet_to_members_over_relays_counts_once_at_the_pace_of_the_relays()
    {
        final NgcUploadPace all = new NgcUploadPace();
        final NgcUploadPace relays = new NgcUploadPace(RELAYED_BYTES_PER_SECOND);
        // Four members, three of them over relays.
        assertTrue(NgcUploadPace.overRelay(new long[]{1L, 2L, 3L, 4L}, SELF, connections(UDP, TCP, TCP, TCP, UDP)));
        assertEquals(0L, NgcUploadPace.msUntilReady(all, relays, true, T0));
        NgcUploadPace.spend(all, relays, CHUNK_PACKET, 4, true);

        // The relays owe for one chunk, whatever members it went to; the upload for four, more than its burst.
        final NgcUploadPace oneChunk = new NgcUploadPace(RELAYED_BYTES_PER_SECOND);
        assertTrue(oneChunk.ready(T0));
        oneChunk.spend(CHUNK_PACKET);
        assertEquals(oneChunk.msUntilReady(T0), relays.msUntilReady(T0));
        final NgcUploadPace fourChunks = new NgcUploadPace();
        assertTrue(fourChunks.ready(T0));
        fourChunks.spend(4L * CHUNK_PACKET);
        assertTrue(fourChunks.msUntilReady(T0) > 0L);
        assertEquals(fourChunks.msUntilReady(T0), all.msUntilReady(T0));

        // The next packet over a relay waits for the longer of the two; one to direct members for the upload alone.
        final long relayed = NgcUploadPace.msUntilReady(all, relays, true, T0);
        assertEquals(Math.max(all.msUntilReady(T0), relays.msUntilReady(T0)), relayed);
        final long direct = NgcUploadPace.msUntilReady(all, relays, false, T0);
        assertEquals(all.msUntilReady(T0), direct);
        assertTrue("direct " + direct + " relayed " + relayed, direct < relayed);
    }

    @Test
    public void only_a_member_toxcore_reaches_through_a_relay_counts()
    {
        // Our own peer, then a member with each status the JNI returns: none, TCP, UDP, no tox (-99), an error.
        final NgcUploadPace.PeerConnection connection = connections(TCP, NONE, TCP, UDP, -99, -2);
        assertTrue(NgcUploadPace.overRelay(new long[]{2L}, SELF, connection));
        assertFalse(NgcUploadPace.overRelay(new long[]{SELF}, SELF, connection));
        assertFalse(NgcUploadPace.overRelay(new long[]{1L, 3L, 4L, 5L}, SELF, connection));
        assertTrue(NgcUploadPace.overRelay(new long[]{SELF, 1L, 3L, 4L, 5L, 2L}, SELF, connection));
        assertFalse(NgcUploadPace.overRelay(new long[0], SELF, connection));
    }

    @Test
    public void the_pace_of_the_relays_has_its_own_burst_and_a_pace_of_nothing_still_lets_a_packet_go()
    {
        // A quarter of a second of 64 KiB/s: a chunk goes at once, the next once the rate paid for it.
        final NgcUploadPace relays = new NgcUploadPace(RELAYED_BYTES_PER_SECOND);
        assertEquals(16L * 1024L, RELAY_BURST);
        assertTrue(relays.ready(T0));
        relays.spend(CHUNK_PACKET);
        assertFalse(relays.ready(T0));
        final long owedMs = (CHUNK_PACKET - RELAY_BURST) * 1000L / RELAYED_BYTES_PER_SECOND;
        final long waitMs = relays.msUntilReady(T0);
        assertTrue("wait " + waitMs + " owed " + owedMs, waitMs >= owedMs && waitMs <= owedMs + 1L);
        assertTrue(relays.ready(T0 + waitMs));

        // The pace without a rate is the whole upload's.
        final NgcUploadPace upload = new NgcUploadPace();
        assertTrue(upload.ready(T0));
        upload.spend(BURST_BYTES - 1L);
        assertTrue(upload.ready(T0));

        // A pace of no bytes is one of a byte a second, not a division by zero.
        final NgcUploadPace nothing = new NgcUploadPace(0L);
        assertTrue(nothing.ready(0L));
        nothing.spend(1L);
        assertFalse(nothing.ready(0L));
        assertEquals(1000L, nothing.msUntilReady(0L));
        assertTrue(nothing.ready(1000L));
    }
}

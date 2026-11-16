package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (0.2.56): the pace of the group file packets this device sends; the desktop's Air::UploadPace
 * (airgroupfilequeue.h), with its numbers.
 *
 * <p>toxcore tells nothing of how full a member's send array is (GCC_BUFFER_SIZE, 8192 entries; a chunk takes 28 of
 * them). It keeps each part of a packet until the member acknowledges it, sends again all the parts of the same
 * second at once, and the member drops every part after a lost one until that one comes again. Android handed the
 * chunks of a file to toxcore back to back: some 290 chunks (11 MB) filled the array, every private send was then
 * refused (-99), and after 20 s of refusals the file was said not sent. The receiver of a resend got its BEGIN and
 * nothing else, and a member whose oldest packet waited 58 s for its acknowledgement was dropped by toxcore ("Send
 * array stuck; timing out peer"). Two nodes on one Mac got a file of 14 MB whole at this rate; at 1 and 2 MiB/s the
 * array filled.
 *
 * <p>The rate counts what this device uploads: a packet once for every member it went to. One instance is shared by
 * the send threads of all group files, so its methods are synchronized; a caller that wants {@link #ready} and
 * {@link #msUntilReady} as one answer holds the instance's lock around both.
 *
 * <p>KHANDAQ (0.2.57): what goes to a member that toxcore reaches only through a TCP relay goes at most at
 * {@link #RELAYED_BYTES_PER_SECOND}, a packet's bytes counted once whatever members it goes to; the desktop's
 * UploadPace::relayedBytesPerSecond. A relay passes a packet on only while its connection has room for it and drops it
 * otherwise (write_packet_TCP_secure_connection, which TCP_server.c forwards with), so the parts of a second that
 * toxcore sends again at once (above) are lost there first, and the member waits for each lost part with all that came
 * after it. QA 2026-11-16, a file of 3 MiB from a desktop into a group of three on one Mac, both receivers over relays:
 * Android on an emulator lost it at 256 and at 128 KiB/s each and got it whole, asking for nothing, at 64 KiB/s twice
 * and at 32 once. An iPhone over a relay got 27 of the 86 chunks Android sent it at {@link #BYTES_PER_SECOND}; then
 * toxcore refused the chunks ("send:backpressure refusals=65"), and after 60 s the file was dropped. A member with a
 * direct path got it whole at 128 and at 64 KiB/s; a packet to members that all have one keeps
 * {@link #BYTES_PER_SECOND}.
 */
final class NgcUploadPace
{
    /** About 4 Mbit/s, shared by the members a packet goes to. */
    static final long BYTES_PER_SECOND = 512L * 1024L;
    /** KHANDAQ (0.2.57): the most that goes to members over a relay, a packet's bytes once ({@link #overRelay}). */
    static final long RELAYED_BYTES_PER_SECOND = 64L * 1024L;
    /** After a pause, at most this much of the rate goes at once. */
    static final long BURST_MS = 250L;
    static final long BURST_BYTES = BYTES_PER_SECOND * BURST_MS / 1000L;
    /** Longer pauses fill the credit all the same; the bound keeps the product in range. */
    private static final long LONGEST_PAUSE_MS = 60L * 60L * 1000L;

    private final long rate;
    private final long burst;
    private long credit = 0L;
    private long lastMs = -1L;

    /** The pace of all our group file packets: {@link #BYTES_PER_SECOND}. */
    NgcUploadPace()
    {
        this(BYTES_PER_SECOND);
    }

    /** KHANDAQ (0.2.57): a pace of {@code perSecond} bytes a second (at least 1); {@link #BURST_MS} of it at once. */
    NgcUploadPace(final long perSecond)
    {
        rate = Math.max(1L, perSecond);
        burst = Math.max(1L, rate * BURST_MS / 1000L);
    }

    /** What a packet of this size costs when it goes to this many members: at least one. */
    static long cost(final long packetBytes, final int members)
    {
        return packetBytes * Math.max(1, members);
    }

    /**
     * Whether a packet may go at {@code nowMs} (a monotonic clock). One may go whenever the credit is positive,
     * however much it costs: a costly one is paid for by the wait after it.
     */
    synchronized boolean ready(final long nowMs)
    {
        if (lastMs < 0L)
        {
            credit = burst;
        }
        else if (nowMs > lastMs)
        {
            final long pauseMs = Math.min(nowMs - lastMs, LONGEST_PAUSE_MS);
            credit = Math.min(burst, credit + pauseMs * rate / 1000L);
        }
        lastMs = Math.max(lastMs, nowMs);
        return credit > 0L;
    }

    /** A packet went that cost this much ({@link #cost}). */
    synchronized void spend(final long bytes)
    {
        credit -= bytes;
    }

    /** How long after {@code nowMs} {@link #ready} first says a packet may go: 0 when it may at {@code nowMs}. */
    synchronized long msUntilReady(final long nowMs)
    {
        if (lastMs < 0L || credit > 0L)
        {
            return 0L;
        }
        // The shortest pause whose share of the rate, rounded down as ready() rounds it, makes the credit positive.
        final long pauseMs = ((1L - credit) * 1000L + rate - 1L) / rate;
        return Math.max(0L, lastMs + pauseMs - nowMs);
    }

    /** KHANDAQ (0.2.57): how a member is reached, as tox_group_peer_get_connection_status says (TOX_CONNECTION). */
    interface PeerConnection
    {
        int status(long peerId);
    }

    /**
     * KHANDAQ (0.2.57): whether a packet to these members goes to one that toxcore reaches only through a TCP relay
     * (TOX_CONNECTION_TCP), and so waits for the pace of the relays too. A private packet asks about its member alone.
     * Our own peer is skipped: toxcore lists it, as TCP while this device has no UDP of its own.
     */
    static boolean overRelay(final long[] peerIds, final long selfPeerId, final PeerConnection connection)
    {
        for (final long peerId : peerIds)
        {
            if (peerId != selfPeerId
                && connection.status(peerId) == ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP.value)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * KHANDAQ (0.2.57): how long after {@code nowMs} a packet may go: by the pace of all our packets and, for one
     * {@link #overRelay}, by the pace of the relays as well. 0 when it may at {@code nowMs}. A caller that shares the
     * paces with other threads holds the lock of {@code all}, then that of {@code relays}, around this.
     */
    static long msUntilReady(final NgcUploadPace all, final NgcUploadPace relays, final boolean overRelay,
                             final long nowMs)
    {
        final boolean allReady = all.ready(nowMs);
        final boolean relaysReady = !overRelay || relays.ready(nowMs);
        if (allReady && relaysReady)
        {
            return 0L;
        }
        final long allWait = allReady ? 0L : all.msUntilReady(nowMs);
        final long relaysWait = relaysReady ? 0L : relays.msUntilReady(nowMs);
        return Math.max(1L, Math.max(allWait, relaysWait));
    }

    /**
     * KHANDAQ (0.2.57): a packet of this many bytes went to this many members: the pace of all our packets pays its
     * {@link #cost}, and the pace of the relays its bytes once when it went {@link #overRelay}.
     */
    static void spend(final NgcUploadPace all, final NgcUploadPace relays, final long packetBytes, final int members,
                      final boolean overRelay)
    {
        all.spend(cost(packetBytes, members));
        if (overRelay)
        {
            relays.spend(packetBytes);
        }
    }
}

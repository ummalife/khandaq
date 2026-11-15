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
 */
final class NgcUploadPace
{
    /** About 4 Mbit/s, shared by the members a packet goes to. */
    static final long BYTES_PER_SECOND = 512L * 1024L;
    /** After a pause, at most this much of the rate goes at once. */
    static final long BURST_MS = 250L;
    static final long BURST_BYTES = BYTES_PER_SECOND * BURST_MS / 1000L;
    /** Longer pauses fill the credit all the same; the bound keeps the product in range. */
    private static final long LONGEST_PAUSE_MS = 60L * 60L * 1000L;

    private long credit = 0L;
    private long lastMs = -1L;

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
            credit = BURST_BYTES;
        }
        else if (nowMs > lastMs)
        {
            final long pauseMs = Math.min(nowMs - lastMs, LONGEST_PAUSE_MS);
            credit = Math.min(BURST_BYTES, credit + pauseMs * BYTES_PER_SECOND / 1000L);
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
        final long pauseMs = ((1L - credit) * 1000L + BYTES_PER_SECOND - 1L) / BYTES_PER_SECOND;
        return Math.max(0L, lastMs + pauseMs - nowMs);
    }
}

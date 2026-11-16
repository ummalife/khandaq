package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (0.2.57, SPEC-network part 2): when the "no network" band shows and what it says, free of Android so
 * that the unit tests hold the timing as the phone runs it. AirNoNetworkBand feeds it the network the system
 * reports and the state of our node, asks it again at {@link #nextAt()}, and draws its {@link Phase}.
 * <ul>
 * <li>No usable network for {@link #SHOW_AFTER_MS} on end: "No network", for as long as there is none. A quick
 * handover (Wi-Fi to mobile data) shows nothing.</li>
 * <li>The network back: "Connecting" until our node is online, then "Back online" for {@link #BACK_ONLINE_MS},
 * then the band goes. Still not online {@link #CONNECTING_CAP_MS} after the network came back: it goes anyway,
 * and the line under your name keeps the state.</li>
 * <li>Our node connecting while there is a network is not this notice: that line says it.</li>
 * </ul>
 * Times are of one monotonic clock, in milliseconds.
 */
final class NoNetworkNotice
{
    static final long SHOW_AFTER_MS = 3_000L;
    static final long BACK_ONLINE_MS = 1_500L;
    static final long CONNECTING_CAP_MS = 30_000L;

    enum Phase
    {
        HIDDEN,
        NO_NETWORK,
        CONNECTING,
        BACK_ONLINE
    }

    private boolean network = true;
    private boolean node = false;
    /** Since when there has been no network, while there is none. */
    private long lostAt = -1L;
    /** When the network came back after "No network". */
    private long backAt = -1L;
    /** When the band started to say "Back online". */
    private long backOnlineAt = -1L;
    private Phase phase = Phase.HIDDEN;

    Phase phase()
    {
        return phase;
    }

    /** Whether the system reports a usable network now. */
    Phase network(final boolean usable, final long now)
    {
        if (usable != network)
        {
            network = usable;
            if (!usable)
            {
                lostAt = now;
            }
            else
            {
                lostAt = -1L;
                if (phase == Phase.NO_NETWORK)
                {
                    backAt = now;
                    phase = Phase.CONNECTING;
                }
            }
        }
        return advance(now);
    }

    /** Whether our node is online now. */
    Phase node(final boolean online, final long now)
    {
        node = online;
        return advance(now);
    }

    /** The phase at {@code now}, with the times that ran out by then. */
    Phase advance(final long now)
    {
        if (!network && (lostAt >= 0) && (now - lostAt >= SHOW_AFTER_MS))
        {
            phase = Phase.NO_NETWORK;
            backAt = -1L;
            backOnlineAt = -1L;
            return phase;
        }
        if (phase == Phase.CONNECTING)
        {
            if (node)
            {
                phase = Phase.BACK_ONLINE;
                backOnlineAt = now;
            }
            else if (now - backAt >= CONNECTING_CAP_MS)
            {
                phase = Phase.HIDDEN;
            }
        }
        if ((phase == Phase.BACK_ONLINE) && (now - backOnlineAt >= BACK_ONLINE_MS))
        {
            phase = Phase.HIDDEN;
        }
        return phase;
    }

    /** When {@link #advance} may change the phase by itself next, or -1 for nothing to wait for. */
    long nextAt()
    {
        long next = -1L;
        if (!network && (lostAt >= 0) && (phase != Phase.NO_NETWORK))
        {
            next = earlier(next, lostAt + SHOW_AFTER_MS);
        }
        if (phase == Phase.CONNECTING)
        {
            next = earlier(next, backAt + CONNECTING_CAP_MS);
        }
        if (phase == Phase.BACK_ONLINE)
        {
            next = earlier(next, backOnlineAt + BACK_ONLINE_MS);
        }
        return next;
    }

    private static long earlier(final long next, final long at)
    {
        return (next < 0) ? at : Math.min(next, at);
    }
}

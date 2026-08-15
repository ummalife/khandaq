package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (#246) — when a CONNECTED NGC group that sees nobody may be hard-reset.
 *
 * Observed on two devices in the same group: the Redmi listed the Samsung as online while the
 * Samsung listed only itself ("2 участников · 1 онлайн"). Membership was right on both — toxcore on
 * the Samsung knew the other member existed, it just had no live connection to it. Nothing recovered
 * it except restarting the app, which works because it rebuilds the group handshake from scratch —
 * exactly what tox_group_reconnect does.
 *
 * HelperGroup.kickstart_group_connection deliberately reconnects ONLY when the group is in the ERROR
 * state. That guard is correct and stays: hard-resetting a group that is still CONNECTING aborts the
 * slow (30-90s) public-group DHT handshake, and doing that regressed public joins for everyone. It
 * simply leaves this case uncovered, because a stuck group here is CONNECTED, not ERROR — so the
 * escalation fired every 180s and did nothing but re-bootstrap.
 *
 * The extra reset is therefore gated on a state that cannot be a join in progress:
 *
 *   - CONNECTED — so no handshake is being aborted (CONNECTING never qualifies; ERROR is already
 *     handled by the existing branch)
 *   - at most ourselves online, WHILE toxcore's own offline-member count says other members exist.
 *     Both numbers come from toxcore's synchronised group state rather than our local seen-peer DB,
 *     so a genuinely empty group never qualifies and cannot churn
 *   - stuck that way far longer than any legitimate discovery window
 *   - rate-limited well above the escalation period, so even a permanently unreachable group costs
 *     at most one reconnect per cooldown
 *
 * Kept as a standalone class with no Android imports purely so the decision is unit-testable: the
 * device repro needs two phones in one group and a peer that vanishes one-way, which is not
 * something a test can stage.
 */
final class NgcStuckGroupPolicy
{
    /**
     * How long a CONNECTED group must see nobody before it is considered stuck rather than slow.
     *
     * Public-group DHT discovery legitimately takes 30-90s, and the caller only starts this clock
     * after its own 180s alone-timer has already elapsed, so this is deliberately far past anything
     * a normal join can explain.
     */
    static final long RESET_AFTER_MS = 300_000L;

    /** Minimum gap between hard resets of the same group. Above the 180s escalation period. */
    static final long RESET_COOLDOWN_MS = 600_000L;

    private NgcStuckGroupPolicy()
    {
    }

    /**
     * @param isConnected   group connection status is CONNECTED (not CONNECTING, not ERROR)
     * @param onlinePeers   tox_group_peer_count — confirmed peers online, INCLUDING ourselves
     * @param offlinePeers  tox_group_offline_peer_count — saved members currently disconnected
     * @param stuckSinceMs  when we first saw this state, or null if we are not tracking it yet
     * @param lastResetMs   when this group was last hard-reset, or null if never
     * @return true only when every gate above holds.
     *
     * Both elapsed comparisons are written so that a clock moved BACKWARDS yields a negative
     * interval, which fails every threshold and returns false. A backwards clock therefore
     * suppresses the reset rather than triggering one; the caller reseats its timestamps.
     */
    static boolean shouldHardReset(final boolean isConnected, final long onlinePeers, final long offlinePeers,
                                   final Long stuckSinceMs, final Long lastResetMs, final long nowMs)
    {
        if (!isConnected)
        {
            return false;
        }
        // > 1 means someone besides us is actually online, so the group is fine. offlinePeers <= 0
        // means toxcore knows of nobody to reach, so a reset has nothing to recover.
        if (onlinePeers > 1L || offlinePeers <= 0L)
        {
            return false;
        }
        if (stuckSinceMs == null)
        {
            return false;
        }
        if ((nowMs - stuckSinceMs) < RESET_AFTER_MS)
        {
            return false;
        }
        return lastResetMs == null || (nowMs - lastResetMs) >= RESET_COOLDOWN_MS;
    }
}

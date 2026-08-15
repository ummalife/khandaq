package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.NgcStuckGroupPolicy.RESET_AFTER_MS;
import static com.zoffcc.applications.trifa.NgcStuckGroupPolicy.RESET_COOLDOWN_MS;
import static com.zoffcc.applications.trifa.NgcStuckGroupPolicy.shouldHardReset;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (#246) — when a CONNECTED NGC group that sees nobody may be hard-reset.
 *
 * The device repro needs two phones in one group and a peer that disappears in ONE direction only,
 * which no test can stage — so the decision itself is pinned here. The gates matter in both
 * directions: too loose and we re-break public joins by resetting groups that are merely slow (the
 * regression the ERROR-only guard in kickstart_group_connection was added to fix), too tight and the
 * stuck group stays stuck until the user restarts the app, which is the bug.
 */
public class NgcStuckGroupPolicyTest
{
    private static final long NOW = 1_000_000_000L;
    /** Long enough to pass RESET_AFTER_MS, in the state QA actually observed. */
    private static final long STUCK_SINCE = NOW - RESET_AFTER_MS;

    // ---------------------------------------------------------------- the case this exists for

    @Test
    public void resets_connected_group_alone_with_known_offline_members()
    {
        // Exactly the QA state: header read "2 участников · 1 онлайн" - we are the 1, and toxcore
        // still lists the peer we cannot reach as an offline member.
        assertTrue(shouldHardReset(true, 1L, 1L, STUCK_SINCE, null, NOW));
    }

    @Test
    public void resets_when_even_we_are_not_counted_online()
    {
        // tox_group_peer_count includes self, but do not depend on that: 0 is just as alone as 1.
        assertTrue(shouldHardReset(true, 0L, 3L, STUCK_SINCE, null, NOW));
    }

    // ---------------------------------------------------------------- states that must NOT reset

    @Test
    public void never_resets_a_group_that_is_not_connected()
    {
        // CONNECTING lands here too, and hard-resetting it aborts the slow public-group DHT
        // handshake. That is the exact regression the caller's ERROR-only guard was added to fix,
        // so it must survive every other gate being satisfied.
        assertFalse(shouldHardReset(false, 1L, 1L, STUCK_SINCE, null, NOW));
    }

    @Test
    public void never_resets_while_someone_else_is_online()
    {
        assertFalse(shouldHardReset(true, 2L, 5L, STUCK_SINCE, null, NOW));
    }

    @Test
    public void never_resets_a_group_with_nobody_to_reach()
    {
        // No offline members means a reset has nothing to recover - a group whose only member is us
        // would otherwise reconnect forever.
        assertFalse(shouldHardReset(true, 1L, 0L, STUCK_SINCE, null, NOW));
        assertFalse(shouldHardReset(true, 1L, -1L, STUCK_SINCE, null, NOW));
    }

    @Test
    public void never_resets_before_the_stuck_window_elapses()
    {
        assertFalse(shouldHardReset(true, 1L, 1L, NOW - (RESET_AFTER_MS - 1L), null, NOW));
        assertTrue(shouldHardReset(true, 1L, 1L, NOW - RESET_AFTER_MS, null, NOW));
    }

    @Test
    public void never_resets_when_the_stuck_clock_was_never_started()
    {
        assertFalse(shouldHardReset(true, 1L, 1L, null, null, NOW));
    }

    // ---------------------------------------------------------------- rate limiting

    @Test
    public void honours_the_cooldown_between_resets()
    {
        final long stuck_since = NOW - (RESET_COOLDOWN_MS * 2L);
        assertFalse(shouldHardReset(true, 1L, 1L, stuck_since, NOW - (RESET_COOLDOWN_MS - 1L), NOW));
        assertTrue(shouldHardReset(true, 1L, 1L, stuck_since, NOW - RESET_COOLDOWN_MS, NOW));
    }

    @Test
    public void cooldown_is_longer_than_the_stuck_window()
    {
        // Otherwise a permanently unreachable group would reset on every escalation pass.
        assertTrue(RESET_COOLDOWN_MS > RESET_AFTER_MS);
    }

    // ---------------------------------------------------------------- clock moved backwards

    @Test
    public void a_backwards_clock_suppresses_the_reset_rather_than_forcing_one()
    {
        // Timestamps in the future (device clock pulled back, or a timezone/NTP correction) yield a
        // negative interval. That must read as "not stuck long enough", never as "long overdue".
        assertFalse(shouldHardReset(true, 1L, 1L, NOW + 60_000L, null, NOW));
        assertFalse(shouldHardReset(true, 1L, 1L, STUCK_SINCE, NOW + 60_000L, NOW));
    }
}

package com.zoffcc.applications.trifa;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KHANDAQ (audit 2026-08-20) — how often one peer may make us rebuild and re-send our group history.
 *
 * <p>An 8-byte packet (`66 77 88 11 34 35 01 01`) used to be enough to make this client, on the
 * toxcore callback thread: create a brand new OS thread, scan every message row of the group out of
 * the encrypted database, and send each matching row back with a 50-100 ms sleep and — for our own
 * rows — an Ed25519 signature each. There was no cooldown, no deduplication and no executor, so a
 * group member could send that packet in a loop and get one live thread per packet. At roughly
 * 75 ms of work per stored row, a few hundred rows keeps each thread alive for many seconds, and a
 * few dozen requests per second reaches `OutOfMemoryError: pthread_create failed` on modest devices.
 * Long before that the client is unusable, because every packet also costs two encrypted-database
 * queries on the tox thread itself.
 *
 * <p>iOS already fixed exactly this — {@code kOCTNgcHistRequestCooldown = 20.0} in
 * OCTNgcGroupHistSync.m, keyed on the peer's stable public key, with the comment "each request makes
 * us rebuild and re-send the WHOLE history to that peer, and nothing stopped one peer from asking in
 * a loop". This is the Android side of the same rule, with the same window, so the two clients do
 * not disagree about how often an honest peer may ask.
 *
 * <p>Keyed on the peer's PUBLIC KEY rather than its peer id: NGC peer ids churn on reconnect, so an
 * id-keyed cooldown would reset every time the attacker's peer entry was renumbered, which is
 * something the attacker can cause.
 *
 * <p>Pure static logic over an explicit state map, so it is unit-testable without a Tox instance or
 * an Android runtime — same reason as {@link NgcHistorySyncBudget}.
 */
final class NgcHistoryRequestPolicy
{
    /**
     * Minimum gap between two history rebuilds for the same peer in the same group.
     *
     * <p>Matches iOS. An honest client asks once when it joins or reconnects, so 20s never refuses a
     * genuine request; it only collapses a loop.
     */
    static final long REQUEST_COOLDOWN_MS = 20_000L;

    /** Bound on the tracking map itself, so it cannot become the leak the policy was added to close. */
    static final int MAX_TRACKED_REQUESTERS = 2_000;

    private static final Map<String, Long> LAST_SERVED_MS = new ConcurrentHashMap<>();

    private NgcHistoryRequestPolicy()
    {
    }

    static String key(final String groupIdentifier, final String peerPubkey)
    {
        if (groupIdentifier == null || peerPubkey == null)
        {
            return null;
        }
        return groupIdentifier.toLowerCase(Locale.ROOT) + "|" + peerPubkey.toLowerCase(Locale.ROOT);
    }

    /**
     * Claims the right to serve one history request.
     *
     * <p>Claim-on-use: the caller starts the rebuild immediately after, so a check that did not
     * consume would bound nothing. Returns false when this peer asked too recently — the request is
     * then simply dropped, which is what an honest peer's own retry timer already expects.
     */
    static boolean allowRequest(final String groupIdentifier, final String peerPubkey, final long nowMs)
    {
        final String k = key(groupIdentifier, peerPubkey);
        if (k == null)
        {
            // Unresolvable peer or group: nothing to rate limit against, and nothing sensible to
            // serve either. Refuse — failing closed here costs an honest peer nothing, because a
            // peer we cannot name is a peer we cannot send history to.
            return false;
        }

        final Long last = LAST_SERVED_MS.get(k);
        if (last != null)
        {
            final long since = nowMs - last;
            // A clock corrected backwards must not lock a peer out until it catches up.
            if (since >= 0L && since < REQUEST_COOLDOWN_MS)
            {
                return false;
            }
        }

        if (LAST_SERVED_MS.size() >= MAX_TRACKED_REQUESTERS && !LAST_SERVED_MS.containsKey(k))
        {
            LAST_SERVED_MS.clear();
        }
        LAST_SERVED_MS.put(k, nowMs);
        return true;
    }

    /** Drops entries whose cooldown has long expired. */
    static void pruneExpired(final long nowMs)
    {
        final Iterator<Map.Entry<String, Long>> it = LAST_SERVED_MS.entrySet().iterator();
        while (it.hasNext())
        {
            final Long v = it.next().getValue();
            if (v == null || (nowMs - v) >= REQUEST_COOLDOWN_MS)
            {
                it.remove();
            }
        }
    }

    /** Test seam. */
    static void resetAllForTest()
    {
        LAST_SERVED_MS.clear();
    }
}

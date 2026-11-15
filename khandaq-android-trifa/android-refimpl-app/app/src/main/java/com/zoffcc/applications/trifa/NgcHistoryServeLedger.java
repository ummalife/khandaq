package com.zoffcc.applications.trifa;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * KHANDAQ (0.2.56) — which history rows one serve sends to one peer.
 *
 * <p>QA live 2026-11-15: after a 64-part group text, two Android phones kept serving each other group
 * history at 200-370 packets a minute for more than an hour, and the collector ran every 3-4 s. Nothing
 * looped by itself. Every serve re-sent the whole 130-minute window, whatever the peer already held,
 * and every peer that timed out or joined made us serve every peer again: the join callback, the
 * transport-loss handler, the mesh recovery, the resync after a send and every history request all
 * serve. The long text tripled the window, a member that timed out every minute kept the serves
 * coming, and the 20 s request cooldown only spaced them.
 *
 * <p>This ledger keeps, per group and peer public key, what that peer is known to hold, and a serve
 * leaves those rows out:
 * <ul>
 *   <li>a row the peer wrote itself: every client drops a record naming it as the author;</li>
 *   <li>our row the peer confirmed, by its delivery receipt or in the confirmations kept on the row;</li>
 *   <li>a row the peer served to us: it holds what it serves;</li>
 *   <li>a row already queued to the peer, unless the peer dropped out or joined again within
 *       {@link #DELIVERY_GRACE_MS} of it: toxcore queues a lossless packet and loses it when the peer
 *       times out, 58 s after its last ping by toxcore's monotonic clock;</li>
 *   <li>a row queued to the peer {@link #MAX_SENDS_PER_FULL_INTERVAL} times since the last full serve,
 *       so a peer whose link carries nothing is not fed the same rows at every rejoin.</li>
 * </ul>
 * The first serve to a peer in a run of the app, the first one {@link #FULL_SERVE_INTERVAL_MS} after
 * the last full serve, the first one after the peer was away for {@link #LONG_ABSENCE_MS}, and the first
 * one after the peer asked for history {@link #REQUESTED_FULL_SERVE_INTERVAL_MS} after the last full
 * serve are full: every row except the peer's own, as before. They cover a peer that lost its database
 * and a signed copy the peer could not use the first time.
 *
 * <p>Nothing changes on the wire: iOS and the desktop get the same packets, only fewer of them.
 *
 * <p>Pure static logic over an explicit state map, like {@link NgcHistoryRequestPolicy}, so it is
 * unit-tested without a Tox instance or a database: see {@code NgcHistoryServeLedgerTest}. The callers
 * pass both clocks in: the wall clock ({@code nowMs}) for the full serves and the absences, the
 * monotonic one ({@code nowUptimeMs}, SystemClock.uptimeMillis()) for the delivery grace.
 */
final class NgcHistoryServeLedger
{
    /** A full serve at least this often per peer. Well inside the 130-minute history window. */
    static final long FULL_SERVE_INTERVAL_MS = 30L * 60L * 1000L;
    /**
     * The serve after the peer asked for history is full when the last full serve to it is this old. A member
     * that kept its key and lost its rows (a restored profile, a database reset) and is back within
     * {@link #LONG_ABSENCE_MS} is still known here, and was served only the rows it never confirmed until the
     * next full serve, by when the oldest had left the history window. Its ask is the only sign. Asks within
     * this time are served as any serve: a member asks at every join, and the churn must stay quiet.
     */
    static final long REQUESTED_FULL_SERVE_INTERVAL_MS = 5L * 60L * 1000L;
    /**
     * A row queued this long before the peer's exit or join may have been lost with its connection. On the
     * monotonic clock (SystemClock.uptimeMillis(), CLOCK_MONOTONIC), the one toxcore times a peer out by: it
     * stops in deep sleep, and the Tox service holds no wake lock. On the wall clock, a phone that slept for
     * more than half a minute after a serve saw the exit past the grace, and the rows lost with the
     * connection waited for the next full serve.
     */
    static final long DELIVERY_GRACE_MS = 90_000L;
    /** A peer away this long may come back with a new database: its next serve is full. */
    static final long LONG_ABSENCE_MS = 10L * 60L * 1000L;
    /** Sends of one row to one peer between two full serves. */
    static final int MAX_SENDS_PER_FULL_INTERVAL = 3;
    /** Bounds on the ledger itself. Past them it forgets, and the peers are served as before. */
    static final int MAX_ENTRIES_PER_PEER = 6_000;
    static final int MAX_TRACKED_ENTRIES = 60_000;
    static final int MAX_TRACKED_PEERS = 2_000;

    private static final long NEVER = Long.MIN_VALUE;
    /** HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX: our row that has no tox id yet. */
    private static final String PENDING_MESSAGE_ID = "00000000";

    private static final Map<String, PeerState> STATES = new HashMap<>();
    private static int trackedEntries = 0;

    private NgcHistoryServeLedger()
    {
    }

    /** Package-private, so the ledger builds it without a synthetic accessor (lint SyntheticAccessor). */
    static final class PeerState
    {
        /** Rows queued to this peer that are still expected to arrive: message id -> when, on the monotonic clock. */
        final Map<String, Long> queuedAtUptimeMs = new HashMap<>();
        /** How often each row was queued to this peer since the last full serve. */
        final Map<String, Integer> sends = new HashMap<>();
        /** Rows this peer showed it holds. */
        final Set<String> held = new HashSet<>();
        long lastFullServeMs = NEVER;
        long lastExitMs = NEVER;
        /** The peer asked for history {@link #REQUESTED_FULL_SERVE_INTERVAL_MS} after its last full serve. */
        boolean fullServeRequested;

        int entries()
        {
            return queuedAtUptimeMs.size() + sends.size() + held.size();
        }
    }

    /** One serve to one peer: its mode and what it did, for the log line after it. */
    static final class Serve
    {
        final String key;
        final String peerPubkey;
        final boolean full;
        int rows;
        int send;
        int skipOwn;
        int skipHeld;
        int skipQueued;
        int skipCapped;

        Serve(final String key, final String peerPubkey, final boolean full)
        {
            this.key = key;
            this.peerPubkey = peerPubkey;
            this.full = full;
        }

        String summary()
        {
            return "full=" + full + " rows=" + rows + " send=" + send + " skip_own=" + skipOwn + " skip_held="
                    + skipHeld + " skip_queued=" + skipQueued + " skip_capped=" + skipCapped;
        }
    }

    static String key(final String groupIdentifier, final String peerPubkey)
    {
        if (groupIdentifier == null || peerPubkey == null || groupIdentifier.isEmpty() || peerPubkey.isEmpty())
        {
            return null;
        }
        return groupIdentifier.toLowerCase(Locale.ROOT) + "|" + peerPubkey.toLowerCase(Locale.ROOT);
    }

    /** A tox message id as 8 hex digits, or null for a row the ledger cannot track. */
    static String messageId(final String messageIdTox)
    {
        if (messageIdTox == null || messageIdTox.length() != 8)
        {
            return null;
        }
        for (int i = 0; i < 8; i++)
        {
            if (Character.digit(messageIdTox.charAt(i), 16) < 0)
            {
                return null;
            }
        }
        final String id = messageIdTox.toLowerCase(Locale.ROOT);
        return PENDING_MESSAGE_ID.equals(id) ? null : id;
    }

    /** Starts a serve to one peer and decides whether it is full. */
    static synchronized Serve beginServe(final String groupIdentifier, final String peerPubkey, final long nowMs)
    {
        final String k = key(groupIdentifier, peerPubkey);
        if (k == null)
        {
            return new Serve(null, peerPubkey, true);
        }
        final PeerState st = stateFor(k);
        final boolean full = st.fullServeRequested || isFullServeDue(st, nowMs, FULL_SERVE_INTERVAL_MS);
        if (full)
        {
            final int before = st.entries();
            st.lastFullServeMs = nowMs;
            st.fullServeRequested = false;
            st.sends.clear();
            afterChange(k, st, before);
        }
        return new Serve(k, peerPubkey, full);
    }

    /**
     * The peer asked for history: its next serve is full, unless its last full serve is less than
     * {@link #REQUESTED_FULL_SERVE_INTERVAL_MS} old. The next serve, not only the one this ask makes: a member
     * asks 1-7 s after it sees us join, on Android and on iOS, when its join has just made us serve it, and
     * NgcHistoryRequestPolicy's cooldown drops the serve of the ask.
     */
    static synchronized void onHistoryRequested(final String groupIdentifier, final String peerPubkey, final long nowMs)
    {
        final String k = key(groupIdentifier, peerPubkey);
        final PeerState st = k == null ? null : STATES.get(k);
        // A peer the ledger does not know gets a full serve anyway.
        if (st != null && isFullServeDue(st, nowMs, REQUESTED_FULL_SERVE_INTERVAL_MS))
        {
            st.fullServeRequested = true;
        }
    }

    /** The last full serve is intervalMs old, or there was none, or it is "in the future": the clock was set back. */
    private static boolean isFullServeDue(final PeerState st, final long nowMs, final long intervalMs)
    {
        return st.lastFullServeMs == NEVER || nowMs < st.lastFullServeMs || (nowMs - st.lastFullServeMs) >= intervalMs;
    }

    /**
     * Whether this serve sends this row.
     *
     * @param messageIdTox the row's tox message id; null for a row the ledger does not track (a file),
     *                     which is sent unless the peer wrote it
     * @param knownHolders peers the row itself records as holding it (the confirmations on our row)
     */
    static synchronized boolean shouldSend(final Serve serve, final String messageIdTox, final String authorPubkey,
                                           final String... knownHolders)
    {
        serve.rows++;
        if (serve.peerPubkey != null && serve.peerPubkey.equalsIgnoreCase(authorPubkey))
        {
            serve.skipOwn++;
            return false;
        }
        final String id = messageId(messageIdTox);
        final PeerState st = serve.key == null ? null : STATES.get(serve.key);
        if (serve.full || id == null || st == null)
        {
            serve.send++;
            return true;
        }
        if (st.held.contains(id) || isKnownHolder(serve.peerPubkey, knownHolders))
        {
            serve.skipHeld++;
            return false;
        }
        if (st.queuedAtUptimeMs.containsKey(id))
        {
            serve.skipQueued++;
            return false;
        }
        final Integer sends = st.sends.get(id);
        if (sends != null && sends >= MAX_SENDS_PER_FULL_INTERVAL)
        {
            serve.skipCapped++;
            return false;
        }
        serve.send++;
        return true;
    }

    /**
     * The row went into toxcore's queue for this peer.
     *
     * @param nowUptimeMs SystemClock.uptimeMillis(), the clock of {@link #DELIVERY_GRACE_MS}
     */
    static synchronized void markQueued(final Serve serve, final String messageIdTox, final long nowUptimeMs)
    {
        final String id = messageId(messageIdTox);
        final PeerState st = serve.key == null ? null : STATES.get(serve.key);
        if (id == null || st == null)
        {
            return;
        }
        final int before = st.entries();
        st.queuedAtUptimeMs.put(id, nowUptimeMs);
        final Integer sends = st.sends.get(id);
        st.sends.put(id, sends == null ? 1 : sends + 1);
        afterChange(serve.key, st, before);
    }

    /** The peer showed it holds this row: its delivery receipt for it, or the row in its own serve. */
    static synchronized void markHeld(final String groupIdentifier, final String holderPubkey,
                                      final String messageIdTox)
    {
        final String k = key(groupIdentifier, holderPubkey);
        final String id = messageId(messageIdTox);
        if (k == null || id == null)
        {
            return;
        }
        final PeerState st = stateFor(k);
        final int before = st.entries();
        st.held.add(id);
        afterChange(k, st, before);
    }

    /**
     * The peer left or timed out: what was queued to it shortly before may be lost.
     *
     * @param nowMs       System.currentTimeMillis(), the clock of {@link #LONG_ABSENCE_MS}
     * @param nowUptimeMs SystemClock.uptimeMillis(), the clock of {@link #DELIVERY_GRACE_MS}
     */
    static synchronized void onPeerExited(final String groupIdentifier, final String peerPubkey, final long nowMs,
                                          final long nowUptimeMs)
    {
        final String k = key(groupIdentifier, peerPubkey);
        final PeerState st = k == null ? null : STATES.get(k);
        if (st == null)
        {
            return;
        }
        final int before = st.entries();
        st.lastExitMs = nowMs;
        dropRecentlyQueued(st, nowUptimeMs);
        afterChange(k, st, before);
    }

    /**
     * The peer joined (again). Queued rows go the way of an exit, in case its exit was never reported; a
     * peer back from a long absence is a stranger again. The clocks as for {@link #onPeerExited}.
     */
    static synchronized void onPeerJoined(final String groupIdentifier, final String peerPubkey, final long nowMs,
                                          final long nowUptimeMs)
    {
        final String k = key(groupIdentifier, peerPubkey);
        final PeerState st = k == null ? null : STATES.get(k);
        if (st == null)
        {
            return;
        }
        if (st.lastExitMs != NEVER && (nowMs - st.lastExitMs) >= LONG_ABSENCE_MS)
        {
            trackedEntries -= st.entries();
            STATES.remove(k);
            return;
        }
        final int before = st.entries();
        dropRecentlyQueued(st, nowUptimeMs);
        afterChange(k, st, before);
    }

    private static boolean isKnownHolder(final String peerPubkey, final String... knownHolders)
    {
        if (peerPubkey == null || knownHolders == null)
        {
            return false;
        }
        for (final String holder : knownHolders)
        {
            if (peerPubkey.equalsIgnoreCase(holder))
            {
                return true;
            }
        }
        return false;
    }

    private static void dropRecentlyQueued(final PeerState st, final long nowUptimeMs)
    {
        final Iterator<Map.Entry<String, Long>> it = st.queuedAtUptimeMs.entrySet().iterator();
        while (it.hasNext())
        {
            final long queuedAt = it.next().getValue();
            // Recent on the monotonic clock, which a clock set back does not move.
            if (queuedAt > nowUptimeMs - DELIVERY_GRACE_MS)
            {
                it.remove();
            }
        }
    }

    private static PeerState stateFor(final String k)
    {
        PeerState st = STATES.get(k);
        if (st == null)
        {
            if (STATES.size() >= MAX_TRACKED_PEERS)
            {
                STATES.clear();
                trackedEntries = 0;
            }
            st = new PeerState();
            STATES.put(k, st);
        }
        return st;
    }

    private static void afterChange(final String k, final PeerState st, final int entriesBefore)
    {
        trackedEntries += st.entries() - entriesBefore;
        if (st.entries() > MAX_ENTRIES_PER_PEER)
        {
            // This peer is served as before (a full serve next) until it shows what it holds again.
            trackedEntries -= st.entries();
            STATES.remove(k);
        }
        if (trackedEntries > MAX_TRACKED_ENTRIES)
        {
            STATES.clear();
            trackedEntries = 0;
        }
    }

    /**
     * A new Tox session. What the last one queued went with its toxcore instance, so nothing is taken for
     * queued, and the first serve to each peer is full again. TrifaToxService calls it when Tox starts: the
     * static ledger outlives a restart of Tox in the same process (a profile switch, the service started
     * again).
     */
    static synchronized void reset()
    {
        STATES.clear();
        trackedEntries = 0;
    }

    /** Test seam. */
    static synchronized void resetAllForTest()
    {
        reset();
    }

    /** Test seam. */
    static synchronized int trackedEntriesForTest()
    {
        return trackedEntries;
    }
}

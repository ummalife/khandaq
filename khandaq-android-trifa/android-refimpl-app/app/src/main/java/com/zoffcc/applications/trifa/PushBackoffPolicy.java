package com.zoffcc.applications.trifa;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KHANDAQ (audit 2026-08-20) — how long to stop calling a friend's push URL after it answers 429.
 *
 * <p>The old rule was a flat two hours after a single 429, and that turned a transient relay
 * condition into a long local outage: the relay's per-IP rate limiter had collapsed into one global
 * bucket (see infra/push/relay/app.py, fixed in the same pass), so ONE flooding host could make the
 * relay answer 429 to everybody for a minute — and every Android client that happened to send in
 * that minute then refused to wake that contact for the next two hours. The recipient simply stopped
 * getting notifications until they opened the app by hand, long after the flood was over.
 *
 * <p>Two hours is also the wrong shape even without an attacker: 429 is by definition a "try again
 * later" answer, and the sane response to it is to try again later — soon at first, then less often
 * if it keeps happening. So: start at one minute, double per consecutive 429, cap at the old two
 * hours. A single blip costs a minute instead of two hours; a genuinely overloaded relay still ends
 * up at the same ceiling, reached in about seven refusals rather than immediately.
 *
 * <p>Any successful call clears the streak, which is the part that makes the escalation safe — a
 * client that gets through is not held at a long backoff by something that happened an hour ago.
 *
 * <p>Pure static logic over an explicit state map, so it is unit-testable without an Android runtime
 * or a network — same reason as {@link NgcHistorySyncBudget}. The map is concurrent because the old
 * plain HashMap was reached from more than one thread.
 */
final class PushBackoffPolicy
{
    /** First refusal costs this much. */
    static final long INITIAL_BACKOFF_MS = 60_000L;
    /** Ceiling: the old flat penalty, now reached only after repeated refusals. */
    static final long MAX_BACKOFF_MS = 2L * 3600L * 1000L;
    /** Entries kept before the map is cleared wholesale, as the previous implementation did. */
    static final int MAX_TRACKED_URLS = 20_000;

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private PushBackoffPolicy()
    {
    }

    static final class State
    {
        /** Consecutive 429s with no success in between. */
        int strikes;
        /** When the current backoff expires. */
        long blockedUntilMs;
    }

    /**
     * @return the backoff for the n-th consecutive refusal, doubling from
     *         {@link #INITIAL_BACKOFF_MS} and clamped to {@link #MAX_BACKOFF_MS}.
     */
    static long backoffMillisForStrikes(final int strikes)
    {
        if (strikes <= 1)
        {
            return INITIAL_BACKOFF_MS;
        }
        // Shift rather than multiply, and stop shifting well before the sign bit: 2h is reached at
        // 8 strikes, so anything past that is already clamped and must not be allowed to overflow
        // into a negative (which would read as "no backoff at all").
        final int shift = Math.min(strikes - 1, 20);
        final long scaled = INITIAL_BACKOFF_MS << shift;
        return (scaled <= 0L || scaled > MAX_BACKOFF_MS) ? MAX_BACKOFF_MS : scaled;
    }

    /** @return true when this URL is still inside its backoff window and must not be called. */
    static boolean shouldSkip(final String pushUrl, final long nowMs)
    {
        if (pushUrl == null)
        {
            return false;
        }
        final State s = STATES.get(pushUrl);
        if (s == null)
        {
            return false;
        }
        // A clock corrected backwards must not strand a URL in a window that never expires.
        if (nowMs < s.blockedUntilMs - MAX_BACKOFF_MS)
        {
            s.blockedUntilMs = 0L;
            s.strikes = 0;
            return false;
        }
        return nowMs < s.blockedUntilMs;
    }

    /** Records a 429 and opens (or extends) the backoff window. */
    static void recordTooManyRequests(final String pushUrl, final long nowMs)
    {
        if (pushUrl == null)
        {
            return;
        }
        if (STATES.size() >= MAX_TRACKED_URLS && !STATES.containsKey(pushUrl))
        {
            // Same crude bound the previous implementation used. Nobody has 20k contacts, so this
            // is a runaway guard, not a working eviction policy.
            STATES.clear();
        }
        final State s = STATES.computeIfAbsent(pushUrl, k -> new State());
        synchronized (s)
        {
            if (s.strikes < Integer.MAX_VALUE)
            {
                s.strikes++;
            }
            s.blockedUntilMs = nowMs + backoffMillisForStrikes(s.strikes);
        }
    }

    /**
     * Records a call that got through. Clears the streak so the next refusal starts at one minute
     * again — without this the backoff would only ever grow.
     */
    static void recordSuccess(final String pushUrl)
    {
        if (pushUrl != null)
        {
            STATES.remove(pushUrl);
        }
    }

    /** Test seam. */
    static void resetAllForTest()
    {
        STATES.clear();
    }

    /** Test seam: current strike count, 0 when the URL is not tracked. */
    static int strikesForTest(final String pushUrl)
    {
        final State s = (pushUrl == null) ? null : STATES.get(pushUrl);
        return (s == null) ? 0 : s.strikes;
    }

    /** Drops entries whose window has expired, so a long-lived process does not accumulate them. */
    static void pruneExpired(final long nowMs)
    {
        final Iterator<Map.Entry<String, State>> it = STATES.entrySet().iterator();
        while (it.hasNext())
        {
            final Map.Entry<String, State> e = it.next();
            if (nowMs >= e.getValue().blockedUntilMs)
            {
                it.remove();
            }
        }
    }
}

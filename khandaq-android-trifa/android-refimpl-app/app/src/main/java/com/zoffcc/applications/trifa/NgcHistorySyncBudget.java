package com.zoffcc.applications.trifa;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KHANDAQ (audit2 finding 1, the half that is not a protocol change) — bounds on what group history
 * sync is allowed to cost us.
 *
 * The reviewer's finding has two parts. The first, attributing a synced message to an author nobody
 * signed for, needs an original-author signature and therefore a protocol version bump. The second is
 * this one, quoted: "The same path explicitly lacks rate/storage bounds, enabling database and
 * notification flooding... impose per-group row/byte limits." Nothing about that needs the wire format
 * to change, so it is done here.
 *
 * Three separate budgets, because they fail in different ways:
 * <ul>
 *   <li><b>rows per group</b> — a permanent cost. Counted against SYNCED rows only, so a group's own
 *       live traffic is never rejected no matter how busy the group is.</li>
 *   <li><b>bytes per group per session</b> — a burst cost. Deliberately NOT persisted: it bounds how
 *       much one run of the app can be made to ingest, which is the flooding case, without inventing a
 *       lifetime quota that would eventually refuse a legitimately long-lived group.</li>
 *   <li><b>notifications per group per window</b> — an attention cost. Rate-limited rather than
 *       suppressed: a message that genuinely arrived while we were offline still deserves to notify,
 *       it just cannot notify a hundred times. Rows over the limit are still stored and still update
 *       the unread badge; only the notification is dropped.</li>
 * </ul>
 *
 * All decision logic lives in pure static methods over {@link State} so it can be unit tested without a
 * database, a Tox instance or an Android runtime — see {@code NgcHistorySyncBudgetTest}.
 */
final class NgcHistorySyncBudget
{
    /**
     * Synced rows one group may accumulate. History sync only ever offers the last
     * TOX_NGC_HISTORY_SYNC_MAX_SECONDS_BACK of a group, so an honest peer cannot approach this; it is
     * an upper bound on a hostile one, not a product limit.
     */
    static final int MAX_SYNCED_ROWS_PER_GROUP = 5000;
    /** Synced text bytes one group may ingest per app run. 8 MiB is ~200x the largest honest backfill. */
    static final long MAX_SYNCED_BYTES_PER_GROUP_PER_SESSION = 8L * 1024L * 1024L;
    /** Notifications one group may raise from SYNCED history inside {@link #NOTIFICATION_WINDOW_MS}. */
    static final int MAX_NOTIFICATIONS_PER_GROUP_PER_WINDOW = 3;
    static final long NOTIFICATION_WINDOW_MS = 60_000L;
    /**
     * Signature verdicts one group may have recorded per app run.
     *
     * <p>The fourth budget, and it exists because version 0x02 arrived after the other three. A
     * verified signed-history packet writes a permanent {@code kqhistver_*} row into the profile
     * database, and NOTHING bounded that: the row budget above governs the unsigned 0x01 packet that
     * inserts the message, while the signed packet beside it took a completely separate path. A group
     * member who has announced a history-signing key can sign as many synthetic records as they like
     * — the signature is over their OWN key, so every one of them verifies — and each distinct
     * message id (four attacker-chosen bytes) becomes another permanent row. That is the same
     * database-flooding shape the row budget was written for, through a door that was left open.
     *
     * <p>Deliberately per session and not persisted, matching the byte budget: a lifetime quota would
     * eventually refuse a legitimately long-lived group, whereas this bounds what one run can be made
     * to ingest. Set to the row cap because a verdict can only ever be useful for a row that fits it,
     * so an honest peer cannot reach it.
     */
    static final int MAX_VERDICTS_PER_GROUP_PER_SESSION = MAX_SYNCED_ROWS_PER_GROUP;

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private NgcHistorySyncBudget()
    {
    }

    static final class State
    {
        /** Synced rows already in the DB for this group, once {@link #seeded} is true. */
        int syncedRows;
        /** True once the row count has been taken from the database at least once this run. */
        boolean seeded;
        long bytesThisSession;
        long notifyWindowStartMs;
        int notifyCountInWindow;
        /** Signature verdicts written for this group during this app run. */
        int verdictsThisSession;
    }

    static State stateFor(final String groupIdentifier)
    {
        final String key = groupIdentifier == null ? "" : groupIdentifier.toLowerCase(Locale.ROOT);
        return STATES.computeIfAbsent(key, k -> new State());
    }

    /**
     * Records the row count read from the database. Call once per group per app run.
     *
     * <p>A failed count must be passed as 0, not skipped: the budget then bounds rows added during THIS
     * run, which still closes the flood. Never leaving the state unseeded is the point — an unseeded
     * state would mean "no row budget at all".
     */
    static void seed(final State state, final int rowsAlreadyStored)
    {
        if (state.seeded)
        {
            return;
        }
        state.syncedRows = Math.max(0, rowsAlreadyStored);
        state.seeded = true;
    }

    static boolean isSeeded(final State state)
    {
        return state.seeded;
    }

    /**
     * @param textBytes UTF-8 size of the message text about to be stored
     * @return true if one more synced row of this size fits inside the group's budget
     */
    static boolean fits(final State state, final int textBytes)
    {
        if (textBytes < 0)
        {
            return false;
        }
        if (state.syncedRows >= MAX_SYNCED_ROWS_PER_GROUP)
        {
            return false;
        }
        return state.bytesThisSession + (long) textBytes <= MAX_SYNCED_BYTES_PER_GROUP_PER_SESSION;
    }

    /** Books an accepted row against the budget. Only call after the row was actually stored. */
    static void recordAccepted(final State state, final int textBytes)
    {
        if (state.syncedRows < Integer.MAX_VALUE)
        {
            state.syncedRows++;
        }
        state.bytesThisSession += Math.max(0, textBytes);
    }

    /**
     * Sliding-window notification limiter.
     *
     * @return true if this synced message may raise a notification; false means store it silently
     */
    static boolean allowNotification(final State state, final long nowMs)
    {
        if (state.notifyWindowStartMs == 0L || (nowMs - state.notifyWindowStartMs) >= NOTIFICATION_WINDOW_MS
            || nowMs < state.notifyWindowStartMs)
        {
            // The clock going BACKWARDS also opens a fresh window. Otherwise a device whose time is
            // corrected backwards would sit in a window that never expires and stay silent for good.
            state.notifyWindowStartMs = nowMs;
            state.notifyCountInWindow = 0;
        }
        if (state.notifyCountInWindow >= MAX_NOTIFICATIONS_PER_GROUP_PER_WINDOW)
        {
            return false;
        }
        state.notifyCountInWindow++;
        return true;
    }

    /**
     * Claims one signature-verdict write for this group.
     *
     * <p>Claim-on-use rather than check-then-record: the caller writes the row immediately after, and
     * a check that did not consume would bound nothing. Returns false once the group is at its cap,
     * and the caller then simply does not record the verdict — the message stays marked unverified,
     * which is the honest outcome and exactly what it already shows before any verdict arrives.
     */
    static boolean claimVerdict(final State state)
    {
        if (state.verdictsThisSession >= MAX_VERDICTS_PER_GROUP_PER_SESSION)
        {
            return false;
        }
        state.verdictsThisSession++;
        return true;
    }

    /** Test seam: drops all per-group state. */
    static void resetAllForTest()
    {
        STATES.clear();
    }
}

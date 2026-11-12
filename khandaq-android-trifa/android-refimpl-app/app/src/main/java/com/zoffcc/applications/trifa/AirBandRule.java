package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (redesign, E22): where something new is told, as on iOS (NotificationFreshness and
 * NotificationCoordinator): as the in-app band while a screen of the app is in front, as a system
 * notification while the app is away, or not at all. Plain Java, so the rules are unit tested.
 *
 * <p>No band for the chat on screen, nor for what was notified while away: the backlog the first
 * {@link #ENTRY_GRACE_MS} after coming back, and a message delivered late (sent more than
 * {@link #LATE_DELIVERY_MS} before) come without one. A screen that keeps the content hidden (the PIN
 * lock, the password, the first launch), a call screen and a locked phone get the system notification,
 * as they did before the band.
 */
final class AirBandRule
{
    /** The backlog right after coming back: what toxcore and the senders' queues hand over at once. */
    static final long ENTRY_GRACE_MS = 3000L;
    /** A message sent longer ago than this waited in a queue somewhere; 45 s absorb clock skew. */
    static final long LATE_DELIVERY_MS = 45_000L;
    /** How long a band stays when nobody touches it. */
    static final long BAND_MS = 4500L;

    /** The screen of the app in front, as far as the band is concerned. */
    enum Screen
    {
        /** No screen of the app is in front: it is away. */
        NONE,
        /** A screen with content, where a band may come down. */
        CONTENT,
        /** A screen that keeps the content hidden: the PIN lock, the password, the first launch. */
        GATE,
        /** A call: its top belongs to the person in the call. */
        CALL,
    }

    enum Outcome
    {
        /** The band, on the screen in front; no system notification. */
        BAND,
        /** A system notification, as while away. */
        SYSTEM,
        /** Nothing: the chat is on screen, or it was notified already. */
        QUIET,
    }

    private AirBandRule()
    {
    }

    /**
     * A message of a chat. {@code locked} is the app lock owed or the phone locked; {@code sentAtMs} is
     * the sender's send time, 0 when unknown (a live group message, a file), which counts as just now.
     */
    static Outcome forMessage(final Screen screen, final boolean locked, final boolean chatOnScreen,
                              final long foregroundSinceMs, final long sentAtMs, final long nowMs)
    {
        if (screen != Screen.CONTENT || locked)
        {
            return Outcome.SYSTEM;
        }
        if (chatOnScreen)
        {
            return Outcome.QUIET;
        }
        return arrivedJustNow(sentAtMs, foregroundSinceMs, nowMs) ? Outcome.BAND : Outcome.QUIET;
    }

    /** A contact request or an invitation to a group: no chat it could be on screen in, no send time. */
    static Outcome forRequest(final Screen screen, final boolean locked, final long foregroundSinceMs,
                              final long nowMs)
    {
        if (screen != Screen.CONTENT || locked)
        {
            return Outcome.SYSTEM;
        }
        return isPastEntryGrace(foregroundSinceMs, nowMs) ? Outcome.BAND : Outcome.QUIET;
    }

    static boolean isPastEntryGrace(final long foregroundSinceMs, final long nowMs)
    {
        return nowMs - foregroundSinceMs >= ENTRY_GRACE_MS;
    }

    /** Arrived just now: not delivered late, and not part of the backlog of coming back. */
    static boolean arrivedJustNow(final long sentAtMs, final long foregroundSinceMs, final long nowMs)
    {
        if (sentAtMs > 0L && nowMs - sentAtMs > LATE_DELIVERY_MS)
        {
            return false;
        }
        return isPastEntryGrace(foregroundSinceMs, nowMs);
    }

    /** How long the band stays: the system may ask for longer, for someone who needs the time. */
    static long stayMs(final long recommendedMs)
    {
        return Math.max(BAND_MS, recommendedMs);
    }
}

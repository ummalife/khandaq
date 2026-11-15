package com.zoffcc.applications.trifa;

import java.util.HashMap;
import java.util.Map;

/**
 * KHANDAQ (0.2.56): how often an incoming group file asks its sender for the chunks it misses (FILE_REQUEST, packet
 * 0x14); the rule iOS has (kOCTNgcNackMaxRounds in OCTNgcGroupFileTransfer.m). The packet itself is unchanged.
 *
 * <p>Android asked every 3.5 to 7 s for as long as the sender was online, with no end: QA saw one file asked for
 * during thirty minutes of a sender that never answered (the desktop does not answer such requests at all). Now one
 * file asks at most {@link #MAX_REQUESTS} times, {@link #MIN_GAP_MS} apart at the least, and a file that has been
 * silent for {@link #SILENCE_LIMIT_MS} in a row while its sender was online (no new chunk, no BEGIN) is given up: its
 * row says it did not come and offers to download it again. A chunk or a BEGIN ends the silence, not the count, so a
 * sender that paces its chunks for a big group (the desktop waits up to 40 s between two) keeps its file going, and a
 * sender that answers every request with a BEGIN alone does not get asked forever. Only the start of the file (a new
 * assembly) and a tap on "Download again" give its requests back.
 *
 * <p>Silence is counted in rounds of the receive watchdog (HelperGroup.arm_ngc_incoming_file_timeout), which runs
 * once the file got nothing for a few seconds. A round while the sender is away counts nothing, and the silence starts
 * again at the first round the sender is back: the file was not silent, its sender was gone.
 *
 * <p>Keys are the caller's (one per file); thread-safe.
 */
final class NgcFileNackBudget
{
    /** The most requests one file sends; as many as iOS sends. */
    static final int MAX_REQUESTS = 24;
    /** The least time between two requests of one file. */
    static final long MIN_GAP_MS = 4_000L;
    /** A file silent this long in a row is given up: 24 rounds of 2.5 s on iOS, its kOCTNgcAssemblyStaleTimeout. */
    static final long SILENCE_LIMIT_MS = 60_000L;

    static final class Entry
    {
        int requests;
        boolean asked;
        long lastRequestMs;
        /** The first silent round of the file's silence now, or -1 while it is heard. */
        long silentSinceMs = -1L;
        boolean gaveUp;
    }

    private final Map<String, Entry> entries = new HashMap<>();

    private Entry entry(final String key)
    {
        Entry e = entries.get(key);
        if (e == null)
        {
            e = new Entry();
            entries.put(key, e);
        }
        return e;
    }

    /** A new chunk, or a BEGIN, of the file came: it is not silent, and a file given up goes on. */
    synchronized void heard(final String key)
    {
        final Entry e = entry(key);
        e.silentSinceMs = -1L;
        e.gaveUp = false;
    }

    /** The file starts over, with all its requests: its first BEGIN came, or the user asked for it again. */
    synchronized void restart(final String key)
    {
        final Entry e = entry(key);
        e.requests = 0;
        e.asked = false;
        e.silentSinceMs = -1L;
        e.gaveUp = false;
    }

    /**
     * Takes one request when the file may ask at {@code nowMs}: false once it asked {@link #MAX_REQUESTS} times,
     * asked less than {@link #MIN_GAP_MS} ago, or was given up.
     */
    synchronized boolean takeRequest(final String key, final long nowMs)
    {
        final Entry e = entry(key);
        if (e.gaveUp || e.requests >= MAX_REQUESTS || (e.asked && (nowMs - e.lastRequestMs) < MIN_GAP_MS))
        {
            return false;
        }
        e.requests++;
        e.asked = true;
        e.lastRequestMs = nowMs;
        return true;
    }

    /**
     * A round of the watchdog found the file silent at {@code nowMs}, its sender online. True, and the file is given
     * up from now on ({@link #gaveUp}), once its silence has lasted {@link #SILENCE_LIMIT_MS}.
     */
    synchronized boolean silentRound(final String key, final long nowMs)
    {
        final Entry e = entry(key);
        if (e.gaveUp)
        {
            return true;
        }
        if (e.silentSinceMs < 0L)
        {
            e.silentSinceMs = nowMs;
        }
        else if ((nowMs - e.silentSinceMs) >= SILENCE_LIMIT_MS)
        {
            e.gaveUp = true;
        }
        return e.gaveUp;
    }

    /** A round of the watchdog found the file's sender away: that is not the file's silence. */
    synchronized void senderAway(final String key)
    {
        final Entry e = entries.get(key);
        if (e != null)
        {
            e.silentSinceMs = -1L;
        }
    }

    /** Whether the file was given up and nothing brought it back since. */
    synchronized boolean gaveUp(final String key)
    {
        final Entry e = entries.get(key);
        return (e != null) && e.gaveUp;
    }

    /** The requests the file has taken since it started. */
    synchronized int requestsTaken(final String key)
    {
        final Entry e = entries.get(key);
        return (e != null) ? e.requests : 0;
    }

    /** The file came whole: nothing is kept of it. */
    synchronized void forget(final String key)
    {
        entries.remove(key);
    }
}

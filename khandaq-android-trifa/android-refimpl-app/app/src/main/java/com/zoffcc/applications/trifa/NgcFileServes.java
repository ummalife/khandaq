package com.zoffcc.applications.trifa;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * KHANDAQ (0.2.57): the resends we run for the members' requests of chunks of our group files (FILE_REQUEST, packet
 * 0x14), one per file and member (NgcGroupFileTransfer.handleIncomingFileRequest). A request merges into the resend of
 * its file and member while that one is queued or running, so nothing it asks for is dropped: a chunk the resend has
 * not reached yet goes with it, and one it has passed is left to the member's next request, since it may still be on
 * its way. Every member has a resend of its own.
 *
 * <p>In 0.2.56 a file had one resend at a time, for whichever member asked, and a request less than 2.5 s after the
 * last one of the file was dropped. That dropped the packets of a request after its first (a request lists at most
 * NgcGroupFileTransfer.FILE_REQUEST_MAX_LISTED_CHUNKS chunks a packet, on iOS and now on Android), an iPhone's repeat
 * that came less than 2.5 s after the one before (it asks every 2.5 s, with 0.5 s of timer leeway), and every request
 * of a member while the resend of another member ran.
 *
 * <p>Keys are the caller's (one per file and member); thread-safe.
 */
final class NgcFileServes
{
    /** The chunks asked for that the resend of each key has not sent yet. */
    private final Map<String, boolean[]> asked = new HashMap<>();

    /**
     * Adds the chunks a member asked for ({@code wanted[i]}: chunk i) to its resend of the file. True when it had none:
     * the caller starts one, and ends it with {@link #end}. False when they merged into the one queued or running.
     */
    synchronized boolean ask(final String key, final boolean[] wanted)
    {
        final boolean[] chunks = asked.get(key);
        if (chunks == null)
        {
            asked.put(key, wanted.clone());
            return true;
        }
        final boolean[] merged = (wanted.length > chunks.length) ? Arrays.copyOf(chunks, wanted.length) : chunks;
        for (int i = 0; i < wanted.length; i++)
        {
            if (wanted[i])
            {
                merged[i] = true;
            }
        }
        asked.put(key, merged);
        return false;
    }

    /**
     * The next chunk the resend sends, the first one asked for from {@code fromIndex} on, which then counts as sent;
     * -1 when none is left there.
     */
    synchronized int takeNext(final String key, final int fromIndex)
    {
        final boolean[] chunks = asked.get(key);
        if (chunks == null)
        {
            return -1;
        }
        for (int i = Math.max(0, fromIndex); i < chunks.length; i++)
        {
            if (chunks[i])
            {
                chunks[i] = false;
                return i;
            }
        }
        return -1;
    }

    /** The resend ended (sent, failed or stopped): what it did not send is dropped, and the next request starts one. */
    synchronized void end(final String key)
    {
        asked.remove(key);
    }
}

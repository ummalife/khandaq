package com.zoffcc.applications.trifa;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * KHANDAQ (0.2.56): the sent times of the texts to one person, and with them their msgV3 stamps. Every send and
 * resend of a text stamps it with the whole seconds of its row's sent time (sent_timestamp / 1000: the send loop
 * of MessageListActivity, resend_unsent_text_messages, tox_friend_resend_msgv3_wrapper, the delivery watchdog), and
 * a receiver sorts by them: iOS by the smaller of the stamp and the arrival, an Android phone by the stamp. The
 * parts of a long text were a tenth of a second apart, so up to ten of them had one stamp and showed in any order
 * on the iPhone; a text sent right after them, at the clock, could sort among them, here too.
 *
 * <p>Now the parts are a second apart (MessageSplitter.PART_GAP_MS), and a text goes after the last text sent to
 * that person: at the clock once the clock is in a later second, else at the start of the second after it. That
 * is the rule iOS stamps its texts by (OCTSubmanagerChatsImpl +textStampAfterStamp:now:), kept here in the sent
 * time of the row, so a text that waits, or goes again, carries the stamp it got when it was written. Two texts
 * are also at least MIN_GAP_MS apart, so a wake push still finds its own row by sent time. A last text more than
 * MAX_LEAD_SEC ahead of the clock (a clock set back) is let go, as on iOS. In memory, as on iOS: after a restart
 * the first text goes at the clock. Pure Java.
 */
final class DirectTextStamps
{
    /** How far ahead of the clock the texts to one person may run: kOCTTextStampMaxLeadSec on iOS. */
    static final long MAX_LEAD_SEC = 3600L;

    /**
     * The least time between two texts to one person. A wake push finds its message by sent time, in a window that
     * reaches PUSH_URL_TRIGGER_GET_MESSAGE_FOR_delta_ms_prev back, and takes the earliest row in it
     * (update_message_in_db_sent_push_set).
     */
    static final long MIN_GAP_MS = TRIFAGlobals.PUSH_URL_TRIGGER_GET_MESSAGE_FOR_delta_ms_prev + 1L;

    /** The texts this phone sends to the people it talks to. */
    static final DirectTextStamps OUTGOING = new DirectTextStamps();

    /** Friend public key, upper case, to the sent time of the last text written to them. */
    private final Map<String, Long> lastSentMs = new HashMap<>();

    /**
     * The sent time of a text written at nowMs after a text to the same person sent at lastSentMs (0 for none).
     * Its whole seconds are what +textStampAfterStamp:now: gives on iOS for the whole seconds of the two.
     */
    static long sentTimestampAfter(final long lastSentMs, final long nowMs)
    {
        if (lastSentMs <= 0L)
        {
            return nowMs;
        }
        final long lastSec = lastSentMs / 1000L;
        final long nowSec = nowMs / 1000L;
        if (lastSec - nowSec >= MAX_LEAD_SEC)
        {
            return nowMs;
        }
        if (lastSec < nowSec)
        {
            return Math.max(nowMs, lastSentMs + MIN_GAP_MS);
        }
        return Math.max((lastSec + 1L) * 1000L, lastSentMs + MIN_GAP_MS);
    }

    /**
     * The sent time of the first of the parts of a text to friendPubkey written at nowMs; part i goes at
     * MessageSplitter.partSentTimestamp(first, i). The next text to that person goes after the last part.
     */
    synchronized long firstSentTimestamp(final String friendPubkey, final int parts, final long nowMs)
    {
        final String key = key(friendPubkey);
        final Long last = lastSentMs.get(key);
        final long first = sentTimestampAfter(last == null ? 0L : last, nowMs);
        lastSentMs.put(key, MessageSplitter.partSentTimestamp(first, Math.max(parts, 1) - 1));
        return first;
    }

    /**
     * A text to friendPubkey was written at sentMs another way (the parts of a waiting text that one message cannot
     * carry): the next text to that person goes after it.
     */
    synchronized void sentAt(final String friendPubkey, final long sentMs)
    {
        final String key = key(friendPubkey);
        final Long last = lastSentMs.get(key);
        if ((last == null) || (sentMs > last))
        {
            lastSentMs.put(key, sentMs);
        }
    }

    private static String key(final String friendPubkey)
    {
        return friendPubkey == null ? "" : friendPubkey.toUpperCase(Locale.ROOT);
    }
}

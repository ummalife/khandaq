package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * KHANDAQ (QA live, F11): the groups this device is leaving, kept in toxcore a little longer than in the app.
 *
 * <p>QA: over a TCP relay the leave notice (GroupPeerKeys.leaveNoticePacket) was lost 1 time in 3, and the member
 * stayed listed until toxcore timed it out, about a minute later. toxcore 0.2.19 deletes a group on the next
 * tox_iterate after tox_group_leave (group_chats.c, do_gc -> group_delete), so the notice and the QUIT had one send
 * attempt each: toxcore resends an unconfirmed lossless packet only later (group_connection.c, gcc_resend_packets).
 * The app now sends the notice, drops the group from its list and database at once, and leaves it in toxcore
 * {@link #LEAVE_DELAY_MS} later.
 *
 * <p>A group is held here in two stages, and the app treats it as gone in both: it ignores the group's callbacks,
 * enumerations of the tox groups skip it, and a lookup by its chat id finds nothing.
 * <ul>
 * <li>waiting: the notice is out, tox_group_leave is not called yet;</li>
 * <li>left: tox_group_leave is called. toxcore deletes the group in its next tox_iterate and saves it with the
 * profile until then (gc_group_is_valid ignores flag_exit), so the persisted list keeps it as well.</li>
 * </ul>
 *
 * <p>Pure static logic over an explicit clock, so it is unit-testable without a Tox instance or an Android
 * runtime — same reason as {@link GroupInviteAcceptPolicy}.
 */
final class GroupPendingLeaves
{
    /**
     * How long toxcore keeps a group after its leave notice. toxcore resends an unconfirmed lossless packet about 3,
     * 5 and 9 s after its first send, and next only at about 17 s (gcc_resend_packets: an age of 2, 4, 8, 16 s,
     * seen on the following second). 12 s covers the three, with time for the last one to arrive.
     */
    static final long LEAVE_DELAY_MS = 12_000L;

    private static final int CHAT_ID_HEX_LENGTH = ToxVars.TOX_GROUP_CHAT_ID_SIZE * 2;

    /** One group being left. */
    static final class Leave
    {
        final long groupNumber;
        /** hex, lower case */
        final String chatId;
        final String partMessage;
        long dueMs;
        /** A join of the same group brought the leave forward ({@link #cancel}). */
        boolean cancelled;

        Leave(final long groupNumber, final String chatId, final String partMessage, final long dueMs)
        {
            this.groupNumber = groupNumber;
            this.chatId = chatId;
            this.partMessage = partMessage;
            this.dueMs = dueMs;
        }
    }

    /** What leaving a group takes from toxcore, on the thread that owns tox (HelperGroup); a fake in the tests. */
    interface Tox
    {
        /** The chat id the group number holds now (hex), or null when it holds none. */
        String chatIdOf(long groupNumber);

        /** tox_group_leave */
        void leave(long groupNumber, String partMessage);
    }

    private static final Object LOCK = new Object();
    // chat id (hex, lower case) -> the leave that waits, in the order recorded
    private static final Map<String, Leave> WAITING = new LinkedHashMap<>();
    // chat id (hex, lower case) -> group number; tox_group_leave was called, toxcore deletes it in its next iterate
    private static final Map<String, Long> LEFT = new LinkedHashMap<>();
    // WAITING and LEFT together, read without the lock by the checks that run on every group callback
    private static volatile int held = 0;

    private GroupPendingLeaves()
    {
    }

    static boolean isChatId(final String chatId)
    {
        if (chatId == null || chatId.length() != CHAT_ID_HEX_LENGTH)
        {
            return false;
        }
        for (int i = 0; i < chatId.length(); i++)
        {
            if (Character.digit(chatId.charAt(i), 16) < 0)
            {
                return false;
            }
        }
        return true;
    }

    private static String key(final String chatId)
    {
        return isChatId(chatId) ? chatId.toLowerCase(Locale.ROOT) : null;
    }

    private static void updateHeld()
    {
        held = WAITING.size() + LEFT.size();
    }

    private static boolean holdsGroupNumberLocked(final long groupNumber)
    {
        for (final Leave leave : WAITING.values())
        {
            if (leave.groupNumber == groupNumber)
            {
                return true;
            }
        }
        return LEFT.containsValue(groupNumber);
    }

    /**
     * Whether a leave waits for its notice: toxcore queued it for at least one other member. {@code sendResult} is
     * what the JNI tox_group_send_custom_packet returned (1 when sent). toxcore also reports a lossless send to a
     * group without confirmed peers as sent (group_chats.c, send_gc_lossless_packet_all_peers), and nothing is
     * resent then. {@code peerCount} is tox_group_peer_count, which counts this device too.
     */
    static boolean noticeWasQueued(final int sendResult, final long peerCount)
    {
        return sendResult == 1 && peerCount > 1;
    }

    /** The notice is out: the group waits {@link #LEAVE_DELAY_MS} for its leave. False if it cannot be held. */
    static boolean record(final long groupNumber, final String chatId, final String partMessage, final long nowMs)
    {
        final String k = key(chatId);
        if (groupNumber < 0 || k == null)
        {
            return false;
        }
        synchronized (LOCK)
        {
            if (WAITING.containsKey(k) || LEFT.containsKey(k) || holdsGroupNumberLocked(groupNumber))
            {
                return false;
            }
            WAITING.put(k, new Leave(groupNumber, k, partMessage, nowMs + LEAVE_DELAY_MS));
            updateHeld();
            return true;
        }
    }

    /** True while the group number is being left: its callbacks are not for the app any more. */
    static boolean holdsGroupNumber(final long groupNumber)
    {
        if (held == 0)
        {
            return false;
        }
        synchronized (LOCK)
        {
            return holdsGroupNumberLocked(groupNumber);
        }
    }

    /** True while the group with this chat id (hex, any case) is being left. */
    static boolean holdsChatId(final String chatId)
    {
        if (held == 0)
        {
            return false;
        }
        final String k = key(chatId);
        if (k == null)
        {
            return false;
        }
        synchronized (LOCK)
        {
            return WAITING.containsKey(k) || LEFT.containsKey(k);
        }
    }

    /**
     * A join of a group that is being left: its wait ends, so that the leave never runs against the instance the
     * join creates. The old instance is left on the next pass ({@link #takeDue}), and the join runs once it is
     * gone. Null when the group does not wait (it is not held, or it is left already).
     */
    static Leave cancel(final String chatId, final long nowMs)
    {
        final String k = key(chatId);
        if (k == null)
        {
            return null;
        }
        synchronized (LOCK)
        {
            final Leave leave = WAITING.get(k);
            if (leave == null)
            {
                return null;
            }
            leave.dueMs = Math.min(leave.dueMs, nowMs);
            leave.cancelled = true;
            return leave;
        }
    }

    /** The leaves due at {@code nowMs}, taken out of the waiting ones. Each goes to {@link #leaveNow}. */
    static List<Leave> takeDue(final long nowMs)
    {
        synchronized (LOCK)
        {
            if (WAITING.isEmpty())
            {
                return Collections.emptyList();
            }
            final List<Leave> due = new ArrayList<>();
            for (final Iterator<Leave> it = WAITING.values().iterator(); it.hasNext(); )
            {
                final Leave leave = it.next();
                if (leave.dueMs <= nowMs)
                {
                    due.add(leave);
                    it.remove();
                }
            }
            updateHeld();
            return due;
        }
    }

    /** Every leave that waits, due now: the app quits, logs out or switches the profile. */
    static List<Leave> takeAll()
    {
        synchronized (LOCK)
        {
            final List<Leave> all = new ArrayList<>(WAITING.values());
            WAITING.clear();
            updateHeld();
            return all;
        }
    }

    /**
     * Leaves the group if its number still holds the leave's chat id, and holds it until the next tox_iterate has
     * deleted it. Never looks for the chat id under another number: that may be a new instance the user just
     * joined. False when the number holds another group or none, and nothing was left.
     */
    static boolean leaveNow(final Leave leave, final Tox tox)
    {
        if (!leave.chatId.equalsIgnoreCase(tox.chatIdOf(leave.groupNumber)))
        {
            return false;
        }
        tox.leave(leave.groupNumber, leave.partMessage);
        noteLeft(leave.groupNumber, leave.chatId);
        return true;
    }

    /** tox_group_leave was called for the group: held until the next tox_iterate has deleted it. */
    static void noteLeft(final long groupNumber, final String chatId)
    {
        final String k = key(chatId);
        if (groupNumber < 0 || k == null)
        {
            return;
        }
        synchronized (LOCK)
        {
            WAITING.remove(k);
            LEFT.put(k, groupNumber);
            updateHeld();
        }
    }

    /** After a tox_iterate: toxcore has deleted the groups left before it. True if there were any. */
    static boolean dropLeft()
    {
        synchronized (LOCK)
        {
            if (LEFT.isEmpty())
            {
                return false;
            }
            LEFT.clear();
            updateHeld();
            return true;
        }
    }

    /** The chat ids to persist: every group being left, which toxcore may still save with the profile. */
    static Set<String> chatIds()
    {
        synchronized (LOCK)
        {
            final Set<String> ids = new TreeSet<>(WAITING.keySet());
            ids.addAll(LEFT.keySet());
            return ids;
        }
    }

    static boolean isEmpty()
    {
        return held == 0;
    }

    /** A new tox instance: group numbers of the old one mean nothing any more. Also for the tests. */
    static void clear()
    {
        synchronized (LOCK)
        {
            WAITING.clear();
            LEFT.clear();
            updateHeld();
        }
    }

    /** The persisted list: chat ids, lower case, sorted, comma separated. */
    static String encode(final Collection<String> chatIds)
    {
        final Set<String> sorted = new TreeSet<>();
        for (final String chatId : chatIds)
        {
            final String k = key(chatId);
            if (k != null)
            {
                sorted.add(k);
            }
        }
        final StringBuilder stored = new StringBuilder();
        for (final String k : sorted)
        {
            if (stored.length() > 0)
            {
                stored.append(',');
            }
            stored.append(k);
        }
        return stored.toString();
    }

    /** The chat ids of a persisted list, lower case; what is not a chat id is dropped. */
    static Set<String> decode(final String stored)
    {
        final Set<String> chatIds = new TreeSet<>();
        if (stored == null)
        {
            return chatIds;
        }
        for (final String part : stored.split(","))
        {
            final String k = key(part.trim());
            if (k != null)
            {
                chatIds.add(k);
            }
        }
        return chatIds;
    }
}

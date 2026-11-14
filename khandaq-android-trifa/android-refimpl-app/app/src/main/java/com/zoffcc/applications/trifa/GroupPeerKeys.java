package com.zoffcc.applications.trifa;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KHANDAQ (QA live, F11): a member who leaves a group leaves this device's member list and count.
 *
 * <p>QA: QA iPhone left a group with "Leave group"; the desktop counted 2 members at once, while Android
 * kept "3 members" and listed QA iPhone as a member last seen 5 minutes ago, for good. Two things kept it:
 * <ul>
 * <li>toxcore 0.2.19 drops the peer before it calls peer_exit (group_chats.c, peer_delete), and the JNI
 * callback carries only the group number, the peer id and the exit type. Asked for that peer id's key, toxcore
 * has nothing, so the member's row was never deleted. The key is now remembered while the peer is there.</li>
 * <li>toxcore keeps the member's key in its saved peers until the slot is wanted for another peer, and the
 * import of those saved peers wrote the row straight back; the offline count is taken from the same saved
 * peers. A key that left is now marked, and neither the import nor the count takes it any more.</li>
 * </ul>
 *
 * <p>Pure static logic, so it is unit-testable without a Tox instance or an Android runtime. The map holds one
 * key per peer id that is in a group: toxcore gives a peer the lowest free id, a join overwrites what the id
 * held before and an exit takes it out.
 */
final class GroupPeerKeys
{
    private static final int KEY_HEX_LENGTH = ToxVars.TOX_PUBLIC_KEY_SIZE * 2;

    private static final Map<String, String> KEY_BY_PEER_ID = new ConcurrentHashMap<>();

    private GroupPeerKeys()
    {
    }

    private static String peerSlot(final String groupIdentifier, final long peerId)
    {
        return groupIdentifier.toLowerCase(Locale.ROOT) + "|" + peerId;
    }

    static boolean isKey(final String publicKey)
    {
        if (publicKey == null || publicKey.length() != KEY_HEX_LENGTH)
        {
            return false;
        }
        for (int i = 0; i < publicKey.length(); i++)
        {
            if (Character.digit(publicKey.charAt(i), 16) < 0)
            {
                return false;
            }
        }
        return true;
    }

    /** While the peer is in the group (its join, a rename, a read of the peer list): this id has this key. */
    static void remember(final String groupIdentifier, final long peerId, final String publicKey)
    {
        if (groupIdentifier == null || groupIdentifier.isEmpty() || peerId < 0 || !isKey(publicKey))
        {
            return;
        }
        KEY_BY_PEER_ID.put(peerSlot(groupIdentifier, peerId), publicKey);
    }

    /** At the peer's exit: the key it had, or null. The id is free again for the next peer that joins. */
    static String take(final String groupIdentifier, final long peerId)
    {
        if (groupIdentifier == null || groupIdentifier.isEmpty() || peerId < 0)
        {
            return null;
        }
        return KEY_BY_PEER_ID.remove(peerSlot(groupIdentifier, peerId));
    }

    /**
     * The member is gone from the group: it was kicked, or it said it was leaving (a leave notice) shortly before
     * this exit. Anything else keeps it a member, offline.
     *
     * <p>KHANDAQ (QA live, F11): toxcore sends the same QUIT when a member leaves and when its app simply closes
     * (tox_kill sends it for every group, with no part message), so QUIT alone ended the membership of everyone
     * who closed the app: Windows closed its window and Android dropped QA Windows from the group. The member
     * that leaves now says so first ({@link #isLeaveNotice}); a QUIT without that is an app that closed. The
     * notice counts for any exit that follows it, since the QUIT itself can be lost and end as a timeout.
     */
    static boolean leftForGood(final int exitType, final boolean saidItWasLeaving)
    {
        return saidItWasLeaving || exitType == ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_KICK.value;
    }

    // KHANDAQ (QA live, F11): the leave notice. A Khandaq client sends it to the whole group, lossless, just
    // before tox_group_leave when the user leaves or deletes a group, and never when the app closes, rejoins a
    // group or disconnects from it. The standard NGC header and the next free Khandaq id (0x41-0x43 are the
    // edits, deletes and reactions): magic(6) | 0x01 | 0x44, nothing after it. The same packet on the desktop
    // (src/model/air/airgroupleave.h) and on iOS (OCTGroupLeaveNotice).
    static final byte NGC_PKT_LEAVE_NOTICE = 0x44;
    private static final byte[] NGC_MAGIC = {0x66, 0x77, (byte) 0x88, 0x11, 0x34, 0x35};
    private static final byte NGC_VERSION = 0x01;
    static final int LEAVE_NOTICE_LENGTH = NGC_MAGIC.length + 2;

    /** How long a notice waits for its exit: the QUIT follows at once, a timeout after a lost QUIT within a minute. */
    static final long LEAVE_NOTICE_WINDOW_MS = 5L * 60L * 1000L;

    private static final Map<String, Long> LEAVE_NOTICE_AT_MS = new ConcurrentHashMap<>();

    static byte[] leaveNoticePacket()
    {
        final byte[] packet = new byte[LEAVE_NOTICE_LENGTH];
        System.arraycopy(NGC_MAGIC, 0, packet, 0, NGC_MAGIC.length);
        packet[NGC_MAGIC.length] = NGC_VERSION;
        packet[NGC_MAGIC.length + 1] = NGC_PKT_LEAVE_NOTICE;
        return packet;
    }

    /** A later version may add bytes after the header; they are not read. */
    static boolean isLeaveNotice(final byte[] data, final long length)
    {
        if (data == null || length < LEAVE_NOTICE_LENGTH || data.length < LEAVE_NOTICE_LENGTH)
        {
            return false;
        }
        for (int i = 0; i < NGC_MAGIC.length; i++)
        {
            if (data[i] != NGC_MAGIC[i])
            {
                return false;
            }
        }
        return data[NGC_MAGIC.length] == NGC_VERSION && data[NGC_MAGIC.length + 1] == NGC_PKT_LEAVE_NOTICE;
    }

    private static String memberSlot(final String groupIdentifier, final String publicKey)
    {
        return groupIdentifier.toLowerCase(Locale.ROOT) + "|" + publicKey.toLowerCase(Locale.ROOT);
    }

    private static boolean fresh(final Long noticedMs, final long nowMs)
    {
        return noticedMs != null && nowMs >= noticedMs && nowMs - noticedMs <= LEAVE_NOTICE_WINDOW_MS;
    }

    /**
     * The member with this key said it is leaving the group. The key is taken when the notice comes in.
     *
     * <p>KHANDAQ (QA live, F11): from now on the member is out of the group (see {@link #isLeaving}). Its exit
     * follows at once after a QUIT, but on a relay the QUIT can be lost and the exit then comes as a timeout about
     * a minute later: the others went on listing the member, online, for that minute.
     *
     * @return true for the notice that puts the member out; false for a repeat while that one waits for its exit
     *         (one leave may send it from more than one place), and for no key or no group
     */
    static boolean noteLeaveNotice(final String groupIdentifier, final String publicKey, final long nowMs)
    {
        if (groupIdentifier == null || groupIdentifier.isEmpty() || !isKey(publicKey))
        {
            return false;
        }
        final boolean[] first = {false};
        LEAVE_NOTICE_AT_MS.compute(memberSlot(groupIdentifier, publicKey), (slot, earlier) -> {
            if (fresh(earlier, nowMs))
            {
                return earlier;
            }
            first[0] = true;
            return nowMs;
        });
        return first[0];
    }

    /**
     * Whether the member with this key said it is leaving and its exit has not come yet. toxcore lists it, online,
     * until that exit; this device has it out of the member list and the count already.
     */
    static boolean isLeaving(final String groupIdentifier, final String publicKey, final long nowMs)
    {
        if (groupIdentifier == null || groupIdentifier.isEmpty() || !isKey(publicKey))
        {
            return false;
        }
        return fresh(LEAVE_NOTICE_AT_MS.get(memberSlot(groupIdentifier, publicKey)), nowMs);
    }

    /** The keys of the group's members that are leaving ({@link #isLeaving}), lower case; empty when none. */
    static Set<String> leavingKeys(final String groupIdentifier, final long nowMs)
    {
        final Set<String> leaving = new HashSet<>();
        if (groupIdentifier == null || groupIdentifier.isEmpty() || LEAVE_NOTICE_AT_MS.isEmpty())
        {
            return leaving;
        }
        final String prefix = groupIdentifier.toLowerCase(Locale.ROOT) + "|";
        for (final Map.Entry<String, Long> notice : LEAVE_NOTICE_AT_MS.entrySet())
        {
            if (notice.getKey().startsWith(prefix) && fresh(notice.getValue(), nowMs))
            {
                leaving.add(notice.getKey().substring(prefix.length()));
            }
        }
        return leaving;
    }

    /**
     * At the member's exit: whether it said it was leaving within {@link #LEAVE_NOTICE_WINDOW_MS}. The notice is
     * used up either way, so a later exit of the same key (it came back and closed the app) is not a leave.
     */
    static boolean takeLeaveNotice(final String groupIdentifier, final String publicKey, final long nowMs)
    {
        if (groupIdentifier == null || groupIdentifier.isEmpty() || !isKey(publicKey))
        {
            return false;
        }
        return fresh(LEAVE_NOTICE_AT_MS.remove(memberSlot(groupIdentifier, publicKey)), nowMs);
    }

    /**
     * The notice of {@code noticedMs} had no exit within {@link #LEAVE_NOTICE_WINDOW_MS}: it is dropped, and the
     * member, if toxcore still has it, is one again. False when its exit or a join used it up, or a later notice
     * replaced it.
     */
    static boolean dropLapsedLeaveNotice(final String groupIdentifier, final String publicKey, final long noticedMs,
                                         final long nowMs)
    {
        if (groupIdentifier == null || groupIdentifier.isEmpty() || !isKey(publicKey))
        {
            return false;
        }
        final Long at = LEAVE_NOTICE_AT_MS.get(memberSlot(groupIdentifier, publicKey));
        if (at == null || at != noticedMs || fresh(at, nowMs))
        {
            return false;
        }
        return LEAVE_NOTICE_AT_MS.remove(memberSlot(groupIdentifier, publicKey), at);
    }

    /**
     * Whether the member's exit tells the group it left. A member that said it was leaving was told about at its
     * notice, so the exit that follows adds nothing; a kick is told at the exit; any other exit is an app that
     * closed or a lost connection.
     */
    static boolean exitTellsTheLeave(final int exitType, final boolean saidItWasLeaving)
    {
        return !saidItWasLeaving && exitType == ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_KICK.value;
    }

    /**
     * toxcore's online count without the members that are leaving: toxcore counts them until their exit.
     * {@code live} is toxcore's peer list now, lower case.
     */
    static long onlineWithoutLeaving(final long toxOnline, final Set<String> live, final Set<String> leaving)
    {
        final long online = Math.max(0L, toxOnline);
        if (live == null || leaving == null || leaving.isEmpty())
        {
            return online;
        }
        long stillListed = 0L;
        for (final String key : leaving)
        {
            if (live.contains(key))
            {
                stillListed++;
            }
        }
        return Math.max(0L, online - stillListed);
    }

    /** A join of the key drops a notice its exit never used: it is in the group again. */
    static void forgetLeaveNotice(final String groupIdentifier, final String publicKey)
    {
        if (groupIdentifier == null || groupIdentifier.isEmpty() || !isKey(publicKey))
        {
            return;
        }
        LEAVE_NOTICE_AT_MS.remove(memberSlot(groupIdentifier, publicKey));
    }

    /** The marked keys of one group as they are stored: lower case, comma separated. Anything else is dropped. */
    static Set<String> decodeDepartures(final String stored)
    {
        final Set<String> departed = ConcurrentHashMap.newKeySet();
        if (stored == null)
        {
            return departed;
        }
        for (final String part : stored.split(","))
        {
            final String key = part.trim();
            if (isKey(key))
            {
                departed.add(key.toLowerCase(Locale.ROOT));
            }
        }
        return departed;
    }

    static String encodeDepartures(final Set<String> departed)
    {
        final StringBuilder stored = new StringBuilder();
        for (final String key : new TreeSet<>(departed))
        {
            if (stored.length() > 0)
            {
                stored.append(',');
            }
            stored.append(key);
        }
        return stored.toString();
    }

    /**
     * toxcore's offline count without the members that left. toxcore counts every key in its saved peers that is
     * not in the group now; a key that left is taken out as long as the saved peers (lower case) still hold it.
     * Without the saved peers at hand (null) every marked key is taken out: the marks of keys toxcore no longer
     * keeps are dropped whenever the saved peers are read.
     */
    static long offlineWithoutDeparted(final long toxOffline, final Set<String> departed, final Set<String> saved)
    {
        return offlineWithoutDeparted(toxOffline, departed, saved, null);
    }

    /**
     * As above, while some members are leaving ({@link #leavingKeys}): they are marked already, but toxcore still
     * lists them online, so its offline count does not have them to take out.
     */
    static long offlineWithoutDeparted(final long toxOffline, final Set<String> departed, final Set<String> saved,
                                       final Set<String> leaving)
    {
        final long offline = Math.max(0L, toxOffline);
        if (departed == null || departed.isEmpty())
        {
            return offline;
        }
        long counted = 0L;
        for (final String key : departed)
        {
            if ((leaving == null || !leaving.contains(key)) && (saved == null || saved.contains(key)))
            {
                counted++;
            }
        }
        return Math.max(0L, offline - counted);
    }
}

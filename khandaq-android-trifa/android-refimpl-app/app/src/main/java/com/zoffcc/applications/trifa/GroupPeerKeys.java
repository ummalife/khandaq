package com.zoffcc.applications.trifa;

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
     * The member is gone from the group: it left (QUIT) or was kicked. A timeout or a lost connection keeps it
     * a member, offline.
     */
    static boolean leftForGood(final int exitType)
    {
        return exitType == ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_QUIT.value
               || exitType == ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_KICK.value;
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
        final long offline = Math.max(0L, toxOffline);
        if (departed == null || departed.isEmpty())
        {
            return offline;
        }
        if (saved == null)
        {
            return Math.max(0L, offline - departed.size());
        }
        long stillSaved = 0L;
        for (final String key : departed)
        {
            if (saved.contains(key))
            {
                stillSaved++;
            }
        }
        return Math.max(0L, offline - stillSaved);
    }
}

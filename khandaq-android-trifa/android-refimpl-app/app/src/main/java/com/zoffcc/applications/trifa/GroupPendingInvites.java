package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * KHANDAQ (0.2.60): group invitations kept for contacts who are offline, across restarts of the app.
 *
 * <p>tox_group_invite_friend goes over the link with the contact: to a contact who is offline it does not go
 * (TOX_ERR_GROUP_INVITE_FRIEND_INVITE_FAIL). HelperGroup kept such an invitation in memory and sent it once the contact
 * came online, so a restart of the app lost it, and an invitation to someone offline for longer never went. The list
 * is kept with the profile's settings now, as the desktop keeps its outgoing invitations (Air::LocalStore): one entry
 * per contact and group, with the time it was asked for. An entry goes when the invitation went, when toxcore refuses
 * it for another reason than the link (no such group or contact), when the group is left or deleted, when the contact
 * is removed, and {@link #MAX_AGE_MS} after it was asked for.
 *
 * <p>Pure static logic over an explicit clock, so it is unit-testable without a Tox instance or an Android runtime —
 * same reason as {@link GroupPendingLeaves}.
 */
final class GroupPendingInvites
{
    /** How long an invitation waits for its contact, as on the desktop. */
    static final long MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    /** At most this many wait, as on the desktop; over it the oldest goes. */
    static final int MAX_ENTRIES = 256;

    /** What tox_group_invite_friend's answer means for a kept invitation. */
    enum Outcome
    {
        /** It went: the entry goes. */
        SENT,
        /** The link to the contact is down, or toxcore is not ready: it waits. */
        LATER,
        /** It cannot go (no such group or contact in toxcore): the entry goes. */
        REFUSED
    }

    private static final int KEY_HEX_LENGTH = ToxVars.TOX_PUBLIC_KEY_SIZE * 2;
    private static final int CHAT_ID_HEX_LENGTH = ToxVars.TOX_GROUP_CHAT_ID_SIZE * 2;
    /** What the JNI tox_group_invite_friend returns without a tox instance. */
    private static final int NO_TOX = -99;

    private static final Object LOCK = new Object();
    // friend key (hex, lower case) -> group chat id (hex, lower case) -> when the invitation was asked for (ms)
    private static final Map<String, Map<String, Long>> BY_FRIEND = new TreeMap<>();
    // The persisted list was read for the running profile (load): only then is it written back.
    private static boolean loaded = false;

    private GroupPendingInvites()
    {
    }

    private static String hex(final String text, final int length)
    {
        if (text == null)
        {
            return null;
        }
        final String trimmed = text.trim();
        if (trimmed.length() != length)
        {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++)
        {
            if (Character.digit(trimmed.charAt(i), 16) < 0)
            {
                return null;
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String friendKey(final String publicKey)
    {
        return hex(publicKey, KEY_HEX_LENGTH);
    }

    private static String chatId(final String groupId)
    {
        return hex(groupId, CHAT_ID_HEX_LENGTH);
    }

    /**
     * What the JNI tox_group_invite_friend returned ({@code res}: 1 when sent, minus the TOX_ERR_GROUP_INVITE_FRIEND
     * code otherwise, -99 without toxcore) means for a kept invitation. As the desktop's Core::inviteToGroup: a failure
     * of the link to the contact waits, a group or a contact toxcore does not have is refused.
     */
    static Outcome outcomeOf(final int res)
    {
        if (res == 1)
        {
            return Outcome.SENT;
        }
        if (res >= 0 || res == NO_TOX)
        {
            return Outcome.LATER;
        }
        final int code = -res;
        if (code == ToxVars.TOX_ERR_GROUP_INVITE_FRIEND.TOX_ERR_GROUP_INVITE_FRIEND_INVITE_FAIL.ordinal()
            || code == ToxVars.TOX_ERR_GROUP_INVITE_FRIEND.TOX_ERR_GROUP_INVITE_FRIEND_FAIL_SEND.ordinal()
            || code == ToxVars.TOX_ERR_GROUP_INVITE_FRIEND.TOX_ERR_GROUP_INVITE_FRIEND_DISCONNECTED.ordinal())
        {
            return Outcome.LATER;
        }
        return Outcome.REFUSED;
    }

    /**
     * The persisted list ({@code stored}, null for none), read when a tox instance starts: it replaces what was held,
     * which may be another profile's. An entry past its age, one that cannot be read and those over the bound are left
     * out. True when the list differs from {@code stored}, so the caller writes it again.
     */
    static boolean load(final String stored, final long nowMs)
    {
        synchronized (LOCK)
        {
            BY_FRIEND.clear();
            if (stored != null)
            {
                for (final String entry : stored.split(","))
                {
                    final String[] parts = entry.trim().split(":");
                    if (parts.length != 3)
                    {
                        continue;
                    }
                    final String friend = friendKey(parts[0]);
                    final String group = chatId(parts[1]);
                    final long since;
                    try
                    {
                        since = Long.parseLong(parts[2]);
                    }
                    catch (NumberFormatException e)
                    {
                        continue;
                    }
                    if (friend == null || group == null || since < 0)
                    {
                        continue;
                    }
                    // A clock set back later must not keep an entry for years.
                    putLocked(friend, group, Math.min(since, nowMs));
                }
            }
            pruneLocked(nowMs);
            loaded = true;
            return !encodeLocked().equals(stored == null ? "" : stored);
        }
    }

    /** Whether the persisted list was read for the running profile ({@link #load}). */
    static boolean isLoaded()
    {
        synchronized (LOCK)
        {
            return loaded;
        }
    }

    /** Nothing held and nothing read: a stand-in profile (#244), and the tests. */
    static void clear()
    {
        synchronized (LOCK)
        {
            BY_FRIEND.clear();
            loaded = false;
        }
    }

    /** An invitation that did not go waits for its contact, from {@code nowMs}. False when a key is not one. */
    static boolean add(final String friendPublicKey, final String groupId, final long nowMs)
    {
        final String friend = friendKey(friendPublicKey);
        final String group = chatId(groupId);
        if (friend == null || group == null)
        {
            return false;
        }
        synchronized (LOCK)
        {
            putLocked(friend, group, nowMs);
            return true;
        }
    }

    /** The groups an invitation waits in for this contact, lower case, sorted. */
    static List<String> groupsFor(final String friendPublicKey)
    {
        final String friend = friendKey(friendPublicKey);
        synchronized (LOCK)
        {
            final Map<String, Long> groups = friend == null ? null : BY_FRIEND.get(friend);
            return groups == null ? new ArrayList<String>() : new ArrayList<>(groups.keySet());
        }
    }

    static boolean hasFor(final String friendPublicKey)
    {
        final String friend = friendKey(friendPublicKey);
        synchronized (LOCK)
        {
            return friend != null && BY_FRIEND.containsKey(friend);
        }
    }

    /** The invitation went, or cannot go. True when it was kept. */
    static boolean remove(final String friendPublicKey, final String groupId)
    {
        final String friend = friendKey(friendPublicKey);
        final String group = chatId(groupId);
        synchronized (LOCK)
        {
            final Map<String, Long> groups = friend == null ? null : BY_FRIEND.get(friend);
            if (groups == null || group == null || groups.remove(group) == null)
            {
                return false;
            }
            if (groups.isEmpty())
            {
                BY_FRIEND.remove(friend);
            }
            return true;
        }
    }

    /** The contact was removed: every invitation kept for them goes. True when there was one. */
    static boolean forgetFriend(final String friendPublicKey)
    {
        final String friend = friendKey(friendPublicKey);
        synchronized (LOCK)
        {
            return friend != null && BY_FRIEND.remove(friend) != null;
        }
    }

    /** The group was left or deleted: every invitation kept for it goes. True when there was one. */
    static boolean forgetGroup(final String groupId)
    {
        final String group = chatId(groupId);
        if (group == null)
        {
            return false;
        }
        synchronized (LOCK)
        {
            boolean removed = false;
            for (final Iterator<Map<String, Long>> it = BY_FRIEND.values().iterator(); it.hasNext(); )
            {
                final Map<String, Long> groups = it.next();
                removed |= groups.remove(group) != null;
                if (groups.isEmpty())
                {
                    it.remove();
                }
            }
            return removed;
        }
    }

    /** Invitations asked for more than {@link #MAX_AGE_MS} before {@code nowMs} go. True when one went. */
    static boolean prune(final long nowMs)
    {
        synchronized (LOCK)
        {
            return pruneLocked(nowMs);
        }
    }

    static int size()
    {
        synchronized (LOCK)
        {
            return sizeLocked();
        }
    }

    /** The list to persist: "friend:group:since" entries, comma separated, sorted; "" for none. */
    static String encode()
    {
        synchronized (LOCK)
        {
            return encodeLocked();
        }
    }

    private static void putLocked(final String friend, final String group, final long sinceMs)
    {
        Map<String, Long> groups = BY_FRIEND.get(friend);
        if (groups == null)
        {
            groups = new TreeMap<>();
            BY_FRIEND.put(friend, groups);
        }
        groups.put(group, sinceMs);
        while (sizeLocked() > MAX_ENTRIES)
        {
            dropOldestLocked();
        }
    }

    private static int sizeLocked()
    {
        int n = 0;
        for (final Map<String, Long> groups : BY_FRIEND.values())
        {
            n += groups.size();
        }
        return n;
    }

    private static void dropOldestLocked()
    {
        String oldestFriend = null;
        String oldestGroup = null;
        long oldest = Long.MAX_VALUE;
        for (final Map.Entry<String, Map<String, Long>> friend : BY_FRIEND.entrySet())
        {
            for (final Map.Entry<String, Long> group : friend.getValue().entrySet())
            {
                if (group.getValue() < oldest)
                {
                    oldest = group.getValue();
                    oldestFriend = friend.getKey();
                    oldestGroup = group.getKey();
                }
            }
        }
        if (oldestFriend != null)
        {
            final Map<String, Long> groups = BY_FRIEND.get(oldestFriend);
            groups.remove(oldestGroup);
            if (groups.isEmpty())
            {
                BY_FRIEND.remove(oldestFriend);
            }
        }
    }

    private static boolean pruneLocked(final long nowMs)
    {
        boolean removed = false;
        for (final Iterator<Map<String, Long>> friends = BY_FRIEND.values().iterator(); friends.hasNext(); )
        {
            final Map<String, Long> groups = friends.next();
            for (final Iterator<Long> since = groups.values().iterator(); since.hasNext(); )
            {
                if (nowMs - since.next() > MAX_AGE_MS)
                {
                    since.remove();
                    removed = true;
                }
            }
            if (groups.isEmpty())
            {
                friends.remove();
            }
        }
        return removed;
    }

    private static String encodeLocked()
    {
        final StringBuilder stored = new StringBuilder();
        for (final Map.Entry<String, Map<String, Long>> friend : BY_FRIEND.entrySet())
        {
            for (final Map.Entry<String, Long> group : friend.getValue().entrySet())
            {
                if (stored.length() > 0)
                {
                    stored.append(',');
                }
                stored.append(friend.getKey()).append(':').append(group.getKey()).append(':').append(group.getValue());
            }
        }
        return stored.toString();
    }
}

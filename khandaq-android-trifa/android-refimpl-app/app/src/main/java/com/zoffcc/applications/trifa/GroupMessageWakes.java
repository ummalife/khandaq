package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * KHANDAQ (0.2.69, group message wakes): the rules of docs/PUSH_PREVIEWS.md, "Group message wakes": which of our
 * groups each friend may be woken for (the 190/03 set it gets from us), and whom a message of ours to a group wakes.
 * Pure logic on plain values. PushPreviewPeers and PushPreviewSender read toxcore, the database and the stored sets,
 * so that the unit tests hold every rule without them.
 */
final class GroupMessageWakes
{
    private GroupMessageWakes()
    {
    }

    /** One group this device is in, as the 190/03 sets need it. */
    static final class OwnGroup
    {
        /** The group's chat id, 64 hex. */
        final String chatId;
        /** Our own key in the group, 64 hex. */
        final String ownPeerKey;
        /** The group is muted on this device (GroupDB.notification_silent). */
        final boolean muted;
        /** The keys of the members muted on this device in this group (GroupPeerDB.notification_silent). */
        final Set<String> mutedMembers;

        OwnGroup(final String chatId, final String ownPeerKey, final boolean muted,
                 final Collection<String> mutedMembers)
        {
            this.chatId = chatId;
            this.ownPeerKey = ownPeerKey;
            this.muted = muted;
            this.mutedMembers = (mutedMembers == null) ? Collections.<String>emptySet()
                                                       : Collections.unmodifiableSet(new HashSet<>(mutedMembers));
        }
    }

    /**
     * The 190/03 set for one friend: the presence hash of each of our groups with a known own key, except a group
     * muted here and a group in which we muted the friend's own member. Only the friend's 190/02 set
     * ({@code friendPresence}) tells which member is the friend's: the presence hash of a member muted here is in it.
     * The first 64, none twice.
     */
    static List<byte[]> wakeSet(final List<OwnGroup> groups, final Collection<String> friendPresence)
    {
        final List<byte[]> set = new ArrayList<>();
        if (groups == null)
        {
            return set;
        }
        final Set<String> presence = lowerCase(friendPresence);
        final Set<String> listed = new HashSet<>();
        for (final OwnGroup group : groups)
        {
            if (set.size() == PushPreviewCrypto.MAX_PRESENCE_HASHES)
            {
                break;
            }
            if ((group == null) || group.muted)
            {
                continue;
            }
            final byte[] chatId = PushPreviewCrypto.unhex(group.chatId, PushPreviewCrypto.CHAT_ID_BYTES);
            final byte[] own = presenceHash(chatId, group.ownPeerKey);
            if ((own == null) || mutesMemberOf(chatId, group.mutedMembers, presence))
            {
                continue;
            }
            if (listed.add(PushPreviewCrypto.hex(own)))
            {
                set.add(own);
            }
        }
        return set;
    }

    /**
     * Whom a message of ours to the group with this chat id wakes: contact (64 uppercase hex) -> the key of its member
     * in the group (64 uppercase hex), in the order of {@code memberKeys}, each contact once.
     *
     * <p>A member counts when it is not our own key, not a key that has left ({@code leftKeys}) and not connected in
     * the group now ({@code connectedKeys}): one that is gets the message over Tox. Its contact is the one whose
     * 190/03 set ({@code wakeSets}) holds the member's presence hash, when no other contact's set holds it too: of
     * two that claim one member, one replayed the other's set, and neither is woken. Whether the contact is offline,
     * and whether its K and push address are known, is the caller's to ask.
     */
    static Map<String, String> contactsToWake(final String chatId, final String ownPeerKey,
                                              final Collection<String> memberKeys, final Collection<String> leftKeys,
                                              final Collection<String> connectedKeys,
                                              final Map<String, Set<String>> wakeSets)
    {
        final Map<String, String> wake = new LinkedHashMap<>();
        final byte[] chat = PushPreviewCrypto.unhex(chatId, PushPreviewCrypto.CHAT_ID_BYTES);
        if ((chat == null) || (memberKeys == null) || (wakeSets == null) || wakeSets.isEmpty())
        {
            return wake;
        }
        final Map<String, Set<String>> claimants = claimantsByHash(wakeSets);
        final Set<String> left = upperCase(leftKeys);
        final Set<String> connected = upperCase(connectedKeys);
        final String own = (ownPeerKey == null) ? null : ownPeerKey.toUpperCase(Locale.ROOT);
        for (final String member : upperCase(memberKeys))
        {
            if (member.equals(own) || left.contains(member) || connected.contains(member))
            {
                continue;
            }
            final byte[] hash = presenceHash(chat, member);
            final Set<String> claimed = (hash == null) ? null : claimants.get(PushPreviewCrypto.hex(hash));
            if ((claimed == null) || (claimed.size() != 1))
            {
                continue;
            }
            final String contact = claimed.iterator().next();
            if (!wake.containsKey(contact))
            {
                wake.put(contact, member);
            }
        }
        return wake;
    }

    // ---- inside ----

    private static byte[] presenceHash(final byte[] chatId, final String peerKey)
    {
        return PushPreviewCrypto.presenceHash(chatId,
                                              PushPreviewCrypto.unhex(peerKey, PushPreviewCrypto.PUBLIC_KEY_BYTES));
    }

    /** A member muted here in the group is the friend's own: its presence hash is in the friend's 190/02 set. */
    private static boolean mutesMemberOf(final byte[] chatId, final Set<String> mutedMembers,
                                         final Set<String> friendPresence)
    {
        if (friendPresence.isEmpty())
        {
            return false;
        }
        for (final String member : mutedMembers)
        {
            final byte[] hash = presenceHash(chatId, member);
            if ((hash != null) && friendPresence.contains(PushPreviewCrypto.hex(hash)))
            {
                return true;
            }
        }
        return false;
    }

    /** Presence hash (32 lowercase hex) -> the contacts whose 190/03 set holds it. */
    private static Map<String, Set<String>> claimantsByHash(final Map<String, Set<String>> wakeSets)
    {
        final Map<String, Set<String>> byHash = new HashMap<>();
        for (final Map.Entry<String, Set<String>> entry : wakeSets.entrySet())
        {
            if ((entry.getKey() == null) || (entry.getValue() == null))
            {
                continue;
            }
            final String contact = entry.getKey().toUpperCase(Locale.ROOT);
            for (final String hash : entry.getValue())
            {
                if (hash != null)
                {
                    byHash.computeIfAbsent(hash.toLowerCase(Locale.ROOT), h -> new HashSet<>(2)).add(contact);
                }
            }
        }
        return byHash;
    }

    private static Set<String> upperCase(final Collection<String> keys)
    {
        final Set<String> out = new LinkedHashSet<>();
        if (keys != null)
        {
            for (final String key : keys)
            {
                if (key != null)
                {
                    out.add(key.toUpperCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static Set<String> lowerCase(final Collection<String> hashes)
    {
        final Set<String> out = new HashSet<>();
        if (hashes != null)
        {
            for (final String hash : hashes)
            {
                if (hash != null)
                {
                    out.add(hash.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }
}

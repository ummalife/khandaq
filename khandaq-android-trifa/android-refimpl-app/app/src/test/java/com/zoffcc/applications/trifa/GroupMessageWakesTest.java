package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.CHAT_ID;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.PEER_PK;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.PRESENCE_HASH;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.WAKE_PACKET;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.bytes;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.repeat;
import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.upper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.69, group message wakes): the rules of docs/PUSH_PREVIEWS.md, "Group message wakes": the 190/03 set
 * each friend gets, and whom a message of ours to a group wakes.
 */
public class GroupMessageWakesTest
{
    private static final String OTHER_GROUP = repeat("2B", 32);
    private static final String OWN = repeat("0A", 32);
    private static final String OWN_IN_OTHER = repeat("0B", 32);
    private static final String MEMBER_1 = repeat("C1", 32);
    private static final String MEMBER_2 = repeat("C2", 32);
    private static final String CONTACT_1 = repeat("F1", 32);
    private static final String CONTACT_2 = repeat("F2", 32);
    private static final String CONTACT_3 = repeat("F3", 32);

    // ---- the 190/03 set for a friend ----

    @Test
    public void the_wake_set_of_the_vector_is_the_vector()
    {
        final List<byte[]> set = GroupMessageWakes.wakeSet(
                Collections.singletonList(group(CHAT_ID, PEER_PK, false)), Collections.<String>emptySet());
        assertEquals(Collections.singletonList(PRESENCE_HASH), hex(set));
        assertEquals(WAKE_PACKET, upper(PushPreviewCrypto.wakePacket(set)));
    }

    @Test
    public void the_wake_set_lists_each_group_with_a_known_own_key()
    {
        final List<GroupMessageWakes.OwnGroup> groups = Arrays.asList(
                group(CHAT_ID, OWN, false),
                group(OTHER_GROUP.toLowerCase(Locale.ROOT), OWN_IN_OTHER.toLowerCase(Locale.ROOT), false),
                // No own key, a malformed chat id, nothing: no hash.
                group(repeat("3C", 32), null, false),
                group("not a chat id", OWN, false),
                null);
        assertEquals(Arrays.asList(hash(CHAT_ID, OWN), hash(OTHER_GROUP, OWN_IN_OTHER)),
                     hex(GroupMessageWakes.wakeSet(groups, null)));
        assertTrue(GroupMessageWakes.wakeSet(null, null).isEmpty());
        assertTrue(GroupMessageWakes.wakeSet(Collections.<GroupMessageWakes.OwnGroup>emptyList(), null).isEmpty());
    }

    @Test
    public void a_group_muted_here_is_in_no_friends_wake_set()
    {
        final List<GroupMessageWakes.OwnGroup> groups = Arrays.asList(group(CHAT_ID, OWN, true),
                                                                      group(OTHER_GROUP, OWN_IN_OTHER, false));
        assertEquals(Collections.singletonList(hash(OTHER_GROUP, OWN_IN_OTHER)),
                     hex(GroupMessageWakes.wakeSet(groups, Collections.<String>emptySet())));
    }

    @Test
    public void a_group_in_which_the_friends_member_is_muted_is_left_out_for_that_friend_only()
    {
        // Muted here: MEMBER_1 in CHAT_ID. The friend's 190/02 set says MEMBER_1 is the friend's own member.
        final List<GroupMessageWakes.OwnGroup> groups = Arrays.asList(
                new GroupMessageWakes.OwnGroup(CHAT_ID, OWN, false, Collections.singletonList(
                        MEMBER_1.toLowerCase(Locale.ROOT))),
                group(OTHER_GROUP, OWN_IN_OTHER, false));
        final Set<String> friendOfMember1 = new HashSet<>(Arrays.asList(
                hash(CHAT_ID, MEMBER_1).toUpperCase(Locale.ROOT), hash(OTHER_GROUP, MEMBER_2)));
        assertEquals(Collections.singletonList(hash(OTHER_GROUP, OWN_IN_OTHER)),
                     hex(GroupMessageWakes.wakeSet(groups, friendOfMember1)));

        // Another friend, whose member is not muted: both groups. One whose presence we do not know yet: both too,
        // and the set goes again when its 190/02 comes.
        final Set<String> friendOfMember2 = Collections.singleton(hash(CHAT_ID, MEMBER_2));
        final List<String> both = Arrays.asList(hash(CHAT_ID, OWN), hash(OTHER_GROUP, OWN_IN_OTHER));
        assertEquals(both, hex(GroupMessageWakes.wakeSet(groups, friendOfMember2)));
        assertEquals(both, hex(GroupMessageWakes.wakeSet(groups, null)));

        // The same member muted in the other group says nothing about this one.
        final List<GroupMessageWakes.OwnGroup> mutedElsewhere = Arrays.asList(
                group(CHAT_ID, OWN, false),
                new GroupMessageWakes.OwnGroup(OTHER_GROUP, OWN_IN_OTHER, false, Collections.singletonList(MEMBER_1)));
        assertEquals(both, hex(GroupMessageWakes.wakeSet(mutedElsewhere,
                                                         Collections.singleton(hash(CHAT_ID, MEMBER_1)))));
    }

    @Test
    public void the_wake_set_keeps_the_first_64_groups_once()
    {
        final List<GroupMessageWakes.OwnGroup> groups = new ArrayList<>();
        groups.add(group(CHAT_ID, OWN, false));
        groups.add(group(CHAT_ID.toLowerCase(Locale.ROOT), OWN, false));
        for (int i = 0; i < 70; i++)
        {
            groups.add(group(String.format(Locale.ROOT, "%064X", i + 1), OWN, false));
        }
        final List<String> set = hex(GroupMessageWakes.wakeSet(groups, null));
        assertEquals(PushPreviewCrypto.MAX_PRESENCE_HASHES, set.size());
        assertEquals(hash(CHAT_ID, OWN), set.get(0));
        assertEquals(hash(String.format(Locale.ROOT, "%064X", 1), OWN), set.get(1));
        assertEquals(hash(String.format(Locale.ROOT, "%064X", 63), OWN), set.get(63));
        assertEquals(set.size(), new HashSet<>(set).size());
    }

    // ---- whom a message of ours wakes ----

    @Test
    public void a_member_away_wakes_the_contact_whose_wake_set_holds_it()
    {
        final Map<String, Set<String>> sets = new HashMap<>();
        sets.put(CONTACT_1.toLowerCase(Locale.ROOT), Collections.singleton(PRESENCE_HASH.toUpperCase(Locale.ROOT)));
        sets.put(CONTACT_2, Collections.singleton(hash(OTHER_GROUP, MEMBER_2)));
        assertEquals(Collections.singletonMap(CONTACT_1, PEER_PK),
                     GroupMessageWakes.contactsToWake(CHAT_ID.toLowerCase(Locale.ROOT), OWN,
                                                      Arrays.asList(PEER_PK.toLowerCase(Locale.ROOT), MEMBER_2),
                                                      null, null, sets));
        // The hash is of the group the message went to: the same member in another group wakes nobody.
        assertTrue(GroupMessageWakes.contactsToWake(OTHER_GROUP, OWN, Collections.singletonList(PEER_PK), null, null,
                                                    sets).isEmpty());
    }

    @Test
    public void a_member_nobody_listed_wakes_nobody()
    {
        final Map<String, Set<String>> sets = Collections.singletonMap(CONTACT_1,
                                                                       Collections.singleton(hash(CHAT_ID, MEMBER_1)));
        assertTrue(GroupMessageWakes.contactsToWake(CHAT_ID, OWN, Collections.singletonList(MEMBER_2), null, null,
                                                    sets).isEmpty());
        // A contact that never sent 190/03 is in no set: a muted group could not be told from another.
        assertTrue(GroupMessageWakes.contactsToWake(CHAT_ID, OWN, Collections.singletonList(MEMBER_1), null, null,
                                                    Collections.<String, Set<String>>emptyMap()).isEmpty());
        assertTrue(GroupMessageWakes.contactsToWake(CHAT_ID, OWN, Collections.singletonList(MEMBER_1), null, null,
                                                    null).isEmpty());
        assertTrue(GroupMessageWakes.contactsToWake("not a chat id", OWN, Collections.singletonList(MEMBER_1), null,
                                                    null, sets).isEmpty());
        assertTrue(GroupMessageWakes.contactsToWake(CHAT_ID, OWN, null, null, null, sets).isEmpty());
        assertTrue(GroupMessageWakes.contactsToWake(CHAT_ID, OWN, Arrays.asList(null, "short"), null, null,
                                                    sets).isEmpty());
    }

    @Test
    public void a_member_muted_or_a_group_muted_on_its_side_wakes_nobody()
    {
        // The other side leaves the group out of the 190/03 set it sends us (above); with that set, nobody wakes.
        final List<String> setOfContact = hex(GroupMessageWakes.wakeSet(
                Arrays.asList(group(CHAT_ID, MEMBER_1, true), group(OTHER_GROUP, MEMBER_2, false)), null));
        final Map<String, Set<String>> sets = Collections.singletonMap(CONTACT_1, new HashSet<>(setOfContact));
        assertTrue(GroupMessageWakes.contactsToWake(CHAT_ID, OWN, Collections.singletonList(MEMBER_1), null, null,
                                                    sets).isEmpty());
        assertEquals(Collections.singletonMap(CONTACT_1, MEMBER_2),
                     GroupMessageWakes.contactsToWake(OTHER_GROUP, OWN_IN_OTHER, Collections.singletonList(MEMBER_2),
                                                      null, null, sets));

        // The contact muted our member there: its set for us leaves the group out too.
        final Set<String> ourPresence = Collections.singleton(hash(CHAT_ID, OWN));
        final List<String> mutedUs = hex(GroupMessageWakes.wakeSet(Collections.singletonList(
                new GroupMessageWakes.OwnGroup(CHAT_ID, MEMBER_1, false, Collections.singletonList(OWN))),
                                                                   ourPresence));
        assertTrue(mutedUs.isEmpty());
    }

    @Test
    public void two_contacts_claiming_one_member_wake_neither()
    {
        final Map<String, Set<String>> sets = new LinkedHashMap<>();
        sets.put(CONTACT_1, Collections.singleton(hash(CHAT_ID, MEMBER_1)));
        // One replayed the other's set.
        sets.put(CONTACT_2, new HashSet<>(Arrays.asList(hash(CHAT_ID, MEMBER_1), hash(OTHER_GROUP, MEMBER_2))));
        sets.put(CONTACT_3, Collections.singleton(hash(CHAT_ID, MEMBER_2)));
        assertEquals(Collections.singletonMap(CONTACT_3, MEMBER_2),
                     GroupMessageWakes.contactsToWake(CHAT_ID, OWN, Arrays.asList(MEMBER_1, MEMBER_2), null, null,
                                                      sets));
    }

    @Test
    public void a_member_connected_in_the_group_gets_it_over_tox_and_wakes_nobody()
    {
        final Map<String, Set<String>> sets = new HashMap<>();
        sets.put(CONTACT_1, Collections.singleton(hash(CHAT_ID, MEMBER_1)));
        sets.put(CONTACT_2, Collections.singleton(hash(CHAT_ID, MEMBER_2)));
        assertEquals(Collections.singletonMap(CONTACT_2, MEMBER_2),
                     GroupMessageWakes.contactsToWake(CHAT_ID, OWN, Arrays.asList(MEMBER_1, MEMBER_2), null,
                                                      Collections.singleton(MEMBER_1.toLowerCase(Locale.ROOT)), sets));
    }

    @Test
    public void our_own_key_and_a_key_that_left_wake_nobody()
    {
        final Map<String, Set<String>> sets = new HashMap<>();
        // A contact that replayed our own hash, and one whose member left the group.
        sets.put(CONTACT_1, Collections.singleton(hash(CHAT_ID, OWN)));
        sets.put(CONTACT_2, Collections.singleton(hash(CHAT_ID, MEMBER_1)));
        sets.put(CONTACT_3, Collections.singleton(hash(CHAT_ID, MEMBER_2)));
        assertEquals(Collections.singletonMap(CONTACT_3, MEMBER_2),
                     GroupMessageWakes.contactsToWake(CHAT_ID, OWN.toLowerCase(Locale.ROOT),
                                                      Arrays.asList(OWN, MEMBER_1, MEMBER_2),
                                                      Collections.singleton(MEMBER_1.toLowerCase(Locale.ROOT)), null,
                                                      sets));
    }

    @Test
    public void a_contact_is_woken_once_whatever_the_number_of_its_members()
    {
        final Map<String, Set<String>> sets = Collections.singletonMap(
                CONTACT_1, new HashSet<>(Arrays.asList(hash(CHAT_ID, MEMBER_1), hash(CHAT_ID, MEMBER_2))));
        final Map<String, String> wake = GroupMessageWakes.contactsToWake(
                CHAT_ID, OWN, Arrays.asList(MEMBER_2, MEMBER_1, MEMBER_2.toLowerCase(Locale.ROOT)), null, null, sets);
        assertEquals(Collections.singletonMap(CONTACT_1, MEMBER_2), wake);
    }

    // ---- helpers ----

    private static GroupMessageWakes.OwnGroup group(final String chatId, final String ownKey, final boolean muted)
    {
        return new GroupMessageWakes.OwnGroup(chatId, ownKey, muted, null);
    }

    private static String hash(final String chatId, final String peerKey)
    {
        return PushPreviewCrypto.hex(PushPreviewCrypto.presenceHash(bytes(chatId, 32), bytes(peerKey, 32)));
    }

    private static List<String> hex(final Collection<byte[]> hashes)
    {
        final List<String> out = new ArrayList<>();
        for (final byte[] h : hashes)
        {
            out.add(PushPreviewCrypto.hex(h));
        }
        return out;
    }
}

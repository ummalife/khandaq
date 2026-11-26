package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.repeat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** KHANDAQ (0.2.64): what push previews keep in SharedPreferences, on memory. */
public class PushPreviewStoreTest
{
    private static final String FRIEND = repeat("A1", 32);
    private static final String OTHER = repeat("B2", 32);
    private static final String HASH_1 = repeat("1f", 16);
    private static final String HASH_2 = repeat("2e", 16);

    @Test
    public void the_master_key_is_made_once_and_kept()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final byte[] first = PushPreviewStore.masterKey(prefs, true);
        assertNotNull(first);
        assertEquals(32, first.length);
        assertArrayEquals(first, PushPreviewStore.masterKey(prefs, true));
        assertArrayEquals(first, PushPreviewStore.masterKey(prefs, false));
        assertEquals(1, prefs.commits);
        final String stored = (String) prefs.values.get(PushPreviewStore.KEY_MASTER);
        assertEquals(PushPreviewCrypto.hex(first), stored);
        assertTrue(stored.matches("[0-9a-f]{64}"));
    }

    @Test
    public void the_push_handler_never_makes_a_master_key()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        assertNull(PushPreviewStore.masterKey(prefs, false));
        assertTrue(prefs.values.isEmpty());
        // A damaged one opens nothing; the app makes a new one, and the friends get its keys when they connect.
        prefs.values.put(PushPreviewStore.KEY_MASTER, "not hex");
        assertNull(PushPreviewStore.masterKey(prefs, false));
        assertNotNull(PushPreviewStore.masterKey(prefs, true));
    }

    @Test
    public void a_friend_is_kept_under_its_public_key_in_upper_case()
    {
        assertEquals(FRIEND, PushPreviewStore.friendKey(FRIEND.toLowerCase(java.util.Locale.ROOT)));
        // A Tox ID: its first 32 bytes are the public key.
        assertEquals(FRIEND, PushPreviewStore.friendKey(FRIEND + "0123456789AB"));
        assertNull(PushPreviewStore.friendKey(FRIEND.substring(2)));
        assertNull(PushPreviewStore.friendKey(repeat("ZZ", 32)));
        assertNull(PushPreviewStore.friendKey(null));
    }

    @Test
    public void the_key_a_friend_handed_us_is_kept_and_read()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final byte[] key = PushPreviewCrypto.unhex(PushPreviewCryptoTest.K, 32);
        PushPreviewStore.storePeerKey(prefs, FRIEND.toLowerCase(java.util.Locale.ROOT), key);
        assertEquals(PushPreviewCryptoTest.K, prefs.values.get(FRIEND));
        assertArrayEquals(key, PushPreviewStore.peerKey(prefs, FRIEND));
        assertNull(PushPreviewStore.peerKey(prefs, OTHER));
        // The same key again, as each time the friend connects: nothing is written.
        final int commits = prefs.commits;
        PushPreviewStore.storePeerKey(prefs, FRIEND, key);
        assertEquals(commits, prefs.commits);
        // Nothing for a malformed key or friend.
        PushPreviewStore.storePeerKey(prefs, OTHER, new byte[31]);
        PushPreviewStore.storePeerKey(prefs, "nobody", key);
        assertEquals(1, prefs.values.size());
    }

    @Test
    public void each_presence_packet_replaces_what_the_friend_announced()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        PushPreviewStore.storePresence(prefs, FRIEND, Arrays.asList(HASH_1, HASH_2.toUpperCase(java.util.Locale.ROOT),
                                                                    "short", null));
        assertEquals(Collections.singletonList(FRIEND), PushPreviewStore.friendsWithPresence(prefs, HASH_1));
        assertEquals(Collections.singletonList(FRIEND), PushPreviewStore.friendsWithPresence(prefs, HASH_2));
        assertEquals(Collections.singletonList(FRIEND),
                     PushPreviewStore.friendsWithPresence(prefs, HASH_1.toUpperCase(java.util.Locale.ROOT)));
        assertTrue(PushPreviewStore.friendsWithPresence(prefs, "short").isEmpty());

        PushPreviewStore.storePresence(prefs, FRIEND, Collections.singletonList(HASH_2));
        assertTrue(PushPreviewStore.friendsWithPresence(prefs, HASH_1).isEmpty());

        // Two friends claiming one member: both are found (the sender then wakes neither).
        PushPreviewStore.storePresence(prefs, OTHER, Collections.singletonList(HASH_2));
        assertEquals(2, PushPreviewStore.friendsWithPresence(prefs, HASH_2).size());

        // An empty announcement: the friend is in no group any more.
        PushPreviewStore.storePresence(prefs, FRIEND, Collections.<String>emptyList());
        assertFalse(prefs.values.containsKey(FRIEND));
        assertEquals(Collections.singletonList(OTHER), PushPreviewStore.friendsWithPresence(prefs, HASH_2));
    }

    @Test
    public void a_deleted_friend_takes_its_key_and_presence_along()
    {
        final MemoryPreferences peers = new MemoryPreferences();
        final MemoryPreferences presence = new MemoryPreferences();
        final MemoryPreferences wake = new MemoryPreferences();
        final MemoryPreferences allowed = new MemoryPreferences();
        PushPreviewStore.storePeerKey(peers, FRIEND, new byte[32]);
        PushPreviewStore.storePeerKey(peers, OTHER, new byte[32]);
        PushPreviewStore.storePresence(presence, FRIEND, Collections.singletonList(HASH_1));
        // KHANDAQ (0.2.69, group message wakes): and its 190/03 set; the other friend's stays.
        PushPreviewStore.storeWakeSet(wake, FRIEND, Collections.singletonList(HASH_1));
        PushPreviewStore.storeWakeSet(wake, OTHER, Collections.singletonList(HASH_2));
        assertTrue(PushPreviewStore.allowSender(allowed, FRIEND));
        assertTrue(PushPreviewStore.allowSender(allowed, OTHER));
        PushPreviewStore.forgetFriend(peers, presence, wake, allowed, FRIEND.toLowerCase(java.util.Locale.ROOT));
        assertNull(PushPreviewStore.peerKey(peers, FRIEND));
        assertNotNull(PushPreviewStore.peerKey(peers, OTHER));
        assertTrue(PushPreviewStore.friendsWithPresence(presence, HASH_1).isEmpty());
        assertFalse(wake.values.containsKey(FRIEND));
        assertEquals(Collections.singletonMap(OTHER, Collections.singleton(HASH_2)), PushPreviewStore.wakeSets(wake));
        // Deleted or blocked: whatever they still send is not shown; the others' is.
        assertFalse(PushPreviewStore.isAllowedSender(allowed, FRIEND));
        assertTrue(PushPreviewStore.isAllowedSender(allowed, OTHER));
    }

    @Test
    public void each_wake_set_replaces_what_the_friend_sent_and_is_read_for_every_friend()
    {
        // KHANDAQ (0.2.69, group message wakes): 190/03 is kept as 190/02 is, in a file of its own.
        final MemoryPreferences prefs = new MemoryPreferences();
        assertTrue(PushPreviewStore.wakeSets(prefs).isEmpty());
        PushPreviewStore.storeWakeSet(prefs, FRIEND.toLowerCase(java.util.Locale.ROOT),
                                      Arrays.asList(HASH_1, HASH_2.toUpperCase(java.util.Locale.ROOT), "short", null));
        assertEquals(HASH_1 + "," + HASH_2, prefs.values.get(FRIEND));
        PushPreviewStore.storeWakeSet(prefs, OTHER, Collections.singletonList(HASH_2));
        final Map<String, Set<String>> sets = PushPreviewStore.wakeSets(prefs);
        assertEquals(2, sets.size());
        assertEquals(new HashSet<>(Arrays.asList(HASH_1, HASH_2)), sets.get(FRIEND));
        assertEquals(Collections.singleton(HASH_2), sets.get(OTHER));

        // The next set replaces it; an empty one: the friend may be woken for none of our groups.
        PushPreviewStore.storeWakeSet(prefs, FRIEND, Collections.singletonList(HASH_1));
        assertEquals(Collections.singleton(HASH_1), PushPreviewStore.wakeSets(prefs).get(FRIEND));
        PushPreviewStore.storeWakeSet(prefs, FRIEND, Collections.<String>emptyList());
        assertFalse(prefs.values.containsKey(FRIEND));
        assertEquals(Collections.singleton(OTHER), PushPreviewStore.wakeSets(prefs).keySet());

        // Nothing for a malformed friend, and nothing malformed is read back.
        PushPreviewStore.storeWakeSet(prefs, "nobody", Collections.singletonList(HASH_1));
        prefs.values.put(repeat("C3", 32), "nothex,short");
        prefs.values.put("not a key", HASH_1);
        assertEquals(Collections.singleton(OTHER), PushPreviewStore.wakeSets(prefs).keySet());
    }

    @Test
    public void a_friends_presence_is_read_back_as_it_was_kept()
    {
        // KHANDAQ (0.2.69, group message wakes): the 190/03 set for a friend leaves out a group whose muted member is
        // in that friend's 190/02 set, so that set is read per friend.
        final MemoryPreferences prefs = new MemoryPreferences();
        assertTrue(PushPreviewStore.presenceOf(prefs, FRIEND).isEmpty());
        PushPreviewStore.storePresence(prefs, FRIEND, Arrays.asList(HASH_1.toUpperCase(java.util.Locale.ROOT), HASH_2));
        assertEquals(new HashSet<>(Arrays.asList(HASH_1, HASH_2)),
                     PushPreviewStore.presenceOf(prefs, FRIEND.toLowerCase(java.util.Locale.ROOT)));
        assertTrue(PushPreviewStore.presenceOf(prefs, OTHER).isEmpty());
        assertTrue(PushPreviewStore.presenceOf(prefs, null).isEmpty());
    }

    @Test
    public void only_a_contact_handed_the_key_is_an_allowed_sender()
    {
        final MemoryPreferences allowed = new MemoryPreferences();
        assertFalse(PushPreviewStore.isAllowedSender(allowed, FRIEND));
        assertTrue(PushPreviewStore.allowSender(allowed, FRIEND.toLowerCase(java.util.Locale.ROOT)));
        assertTrue(PushPreviewStore.isAllowedSender(allowed, FRIEND));
        assertTrue(PushPreviewStore.isAllowedSender(allowed, FRIEND.toLowerCase(java.util.Locale.ROOT)));
        assertFalse(PushPreviewStore.isAllowedSender(allowed, OTHER));
        final int commits = allowed.commits;
        assertTrue(PushPreviewStore.allowSender(allowed, FRIEND));
        assertEquals("a friend already on the list is not written again", commits, allowed.commits);
        // Nothing that is not a public key gets on the list or passes.
        assertFalse(PushPreviewStore.allowSender(allowed, "not a key"));
        assertFalse(PushPreviewStore.allowSender(allowed, null));
        assertFalse(PushPreviewStore.isAllowedSender(allowed, null));
        assertFalse(PushPreviewStore.isAllowedSender(allowed, repeat("zz", 32)));
    }

    @Test
    public void the_copy_of_the_muted_chats_answers_in_either_case()
    {
        final MemoryPreferences prefs = new MemoryPreferences();
        final String group = repeat("0f", 32);
        PushPreviewStore.storeMutedChats(prefs, Arrays.asList(FRIEND, group, "", null));
        assertTrue(PushPreviewStore.isMuted(prefs, FRIEND.toLowerCase(java.util.Locale.ROOT)));
        assertTrue(PushPreviewStore.isMuted(prefs, group));
        assertTrue(PushPreviewStore.isMuted(prefs, group.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(PushPreviewStore.isMuted(prefs, OTHER));
        assertFalse(PushPreviewStore.isMuted(prefs, null));
        final int commits = prefs.commits;
        PushPreviewStore.storeMutedChats(prefs, Arrays.asList(group, FRIEND));
        assertEquals("the same set is not written again", commits, prefs.commits);
        PushPreviewStore.storeMutedChats(prefs, Collections.<String>emptyList());
        assertFalse(PushPreviewStore.isMuted(prefs, FRIEND));
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static com.zoffcc.applications.trifa.GroupPeerKeys.decodeDepartures;
import static com.zoffcc.applications.trifa.GroupPeerKeys.encodeDepartures;
import static com.zoffcc.applications.trifa.GroupPeerKeys.leftForGood;
import static com.zoffcc.applications.trifa.GroupPeerKeys.offlineWithoutDeparted;
import static com.zoffcc.applications.trifa.GroupPeerKeys.remember;
import static com.zoffcc.applications.trifa.GroupPeerKeys.take;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA live, F11): a member who leaves a group leaves this device's member list and count.
 *
 * QA: QA iPhone left a group of three; the desktop counted 2 members at once, Android kept "3 members" and listed
 * QA iPhone for good. toxcore drops a peer before it reports the exit, so its key is remembered while it is there;
 * and toxcore keeps the key in its saved peers, which the offline count is taken from.
 *
 * Each test uses a group of its own: the remembered keys are kept for the whole process.
 */
public class GroupPeerKeysTest
{
    private static final String IPHONE = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90";
    private static final String MAC = "0F1E2D3C4B5A69788796A5B4C3D2E1F00F1E2D3C4B5A69788796A5B4C3D2E1F0";
    private static final String NEWCOMER = "1111222233334444555566667777888899990000AAAABBBBCCCCDDDDEEEEFFFF";

    private static String lower(final String key)
    {
        return key.toLowerCase(java.util.Locale.ROOT);
    }

    @Test
    public void the_key_remembered_at_the_join_is_there_at_the_exit_once()
    {
        final String group = "926c33466257eda8837c55d11580435a0a8b4482a451d5a427032c39d8947dc9";
        remember(group, 2, IPHONE);

        assertEquals(IPHONE, take(group, 2));
        // The id is free again: a second exit on it finds nothing.
        assertNull(take(group, 2));
    }

    @Test
    public void the_next_peer_on_a_freed_id_gets_its_own_key()
    {
        final String group = "group-freed-id";
        remember(group, 1, IPHONE);
        // No exit reported for the first peer (a rename or a read of the peer list said the same), then toxcore
        // gives the lowest free id to the next peer and its join overwrites.
        remember(group, 1, NEWCOMER);

        assertEquals(NEWCOMER, take(group, 1));
    }

    @Test
    public void groups_are_apart_and_a_group_id_in_either_case_is_the_same_group()
    {
        remember("group-a", 0, IPHONE);
        remember("group-b", 0, MAC);

        assertEquals(MAC, take("GROUP-B", 0));
        assertEquals(IPHONE, take("group-a", 0));
    }

    @Test
    public void nothing_is_remembered_without_a_group_an_id_or_a_key()
    {
        final String group = "group-bad-input";
        remember(group, 3, null);
        remember(group, 4, "-1");
        remember(group, 5, IPHONE.substring(1));
        remember(group, 6, IPHONE.replace('A', 'G'));
        remember(group, -1, IPHONE);
        remember(null, 7, IPHONE);
        remember("", 8, IPHONE);

        assertNull(take(group, 3));
        assertNull(take(group, 4));
        assertNull(take(group, 5));
        assertNull(take(group, 6));
        assertNull(take(group, -1));
        assertNull(take(null, 7));
        assertNull(take("", 8));
        assertNull(take(group, 99));
    }

    @Test
    public void only_leaving_and_a_kick_end_the_membership()
    {
        assertTrue(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_QUIT.value));
        assertTrue(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_KICK.value));
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_TIMEOUT.value));
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_DISCONNECTED.value));
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_SELF_DISCONNECTED.value));
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_SYNC_ERROR.value));
    }

    @Test
    public void the_marks_are_stored_in_lower_case_and_read_back()
    {
        final Set<String> departed = decodeDepartures(null);
        assertTrue(departed.isEmpty());
        departed.add(lower(MAC));
        departed.add(lower(IPHONE));

        final String stored = encodeDepartures(departed);
        assertEquals(lower(MAC) + "," + lower(IPHONE), stored);
        assertEquals(departed, decodeDepartures(stored));
        // Keys in upper case are read in lower case; anything that is not a key is dropped.
        assertEquals(new HashSet<>(Collections.singletonList(lower(IPHONE))),
                     decodeDepartures(" " + IPHONE + " ,,-1,xyz," + IPHONE.substring(2)));
        assertTrue(decodeDepartures("").isEmpty());
        assertEquals("", encodeDepartures(decodeDepartures("")));
    }

    @Test
    public void a_member_who_left_is_not_counted_while_the_saved_peers_still_hold_them()
    {
        final Set<String> departed = new HashSet<>(Collections.singletonList(lower(IPHONE)));

        // The QA case: QA Mac and this device online, QA iPhone left and still in toxcore's saved peers.
        assertEquals(0L, offlineWithoutDeparted(1L, departed, new HashSet<>(Collections.singletonList(lower(IPHONE)))));
        // Another offline member still counts.
        assertEquals(1L, offlineWithoutDeparted(2L, departed,
                                                new HashSet<>(Arrays.asList(lower(IPHONE), lower(MAC)))));
        // Once toxcore has given the slot to another peer, its count no longer has the key either.
        assertEquals(1L, offlineWithoutDeparted(1L, departed, new HashSet<>(Collections.singletonList(lower(MAC)))));
        // The saved peers not read yet: every marked key is taken out.
        assertEquals(0L, offlineWithoutDeparted(1L, departed, null));
        // Nothing marked: the count as toxcore gives it.
        assertEquals(2L, offlineWithoutDeparted(2L, new HashSet<String>(), null));
        assertEquals(2L, offlineWithoutDeparted(2L, null, null));
        // Never below zero, also when toxcore reports an error.
        assertEquals(0L, offlineWithoutDeparted(0L, departed, null));
        assertEquals(0L, offlineWithoutDeparted(-1L, null, null));
    }
}

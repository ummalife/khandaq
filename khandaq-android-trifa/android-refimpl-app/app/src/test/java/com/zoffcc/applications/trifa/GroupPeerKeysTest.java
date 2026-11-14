package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static com.zoffcc.applications.trifa.GroupPeerKeys.decodeDepartures;
import static com.zoffcc.applications.trifa.GroupPeerKeys.encodeDepartures;
import static com.zoffcc.applications.trifa.GroupPeerKeys.LEAVE_NOTICE_WINDOW_MS;
import static com.zoffcc.applications.trifa.GroupPeerKeys.forgetLeaveNotice;
import static com.zoffcc.applications.trifa.GroupPeerKeys.isLeaveNotice;
import static com.zoffcc.applications.trifa.GroupPeerKeys.leaveNoticePacket;
import static com.zoffcc.applications.trifa.GroupPeerKeys.leftForGood;
import static com.zoffcc.applications.trifa.GroupPeerKeys.noteLeaveNotice;
import static com.zoffcc.applications.trifa.GroupPeerKeys.takeLeaveNotice;
import static com.zoffcc.applications.trifa.GroupPeerKeys.offlineWithoutDeparted;
import static com.zoffcc.applications.trifa.GroupPeerKeys.remember;
import static com.zoffcc.applications.trifa.GroupPeerKeys.take;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA live, F11): a member who leaves a group leaves this device's member list and count, and a member
 * whose app closes stays in it. toxcore sends the same QUIT in both cases, so the member that leaves says so first.
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
    public void a_quit_alone_is_an_app_that_closed_and_keeps_the_member()
    {
        // QA live F11: Windows closed its window (WM_CLOSE, toxcore's QUIT) and Android dropped QA Windows.
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_QUIT.value, false));
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_TIMEOUT.value, false));
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_DISCONNECTED.value, false));
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_SELF_DISCONNECTED.value, false));
        assertFalse(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_SYNC_ERROR.value, false));
    }

    @Test
    public void a_kick_or_an_exit_after_a_leave_notice_ends_the_membership()
    {
        assertTrue(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_KICK.value, false));
        assertTrue(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_QUIT.value, true));
        // The QUIT itself can be lost; the member then times out, and it still left.
        assertTrue(leftForGood(ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_TIMEOUT.value, true));
    }

    @Test
    public void the_leave_notice_is_the_ngc_header_with_id_0x44()
    {
        final byte[] packet = leaveNoticePacket();
        assertEquals(8, packet.length);
        assertEquals(Arrays.toString(new byte[]{0x66, 0x77, (byte) 0x88, 0x11, 0x34, 0x35, 0x01, 0x44}),
                     Arrays.toString(packet));
        assertTrue(isLeaveNotice(packet, packet.length));
        // A later version may add bytes after the header.
        assertTrue(isLeaveNotice(Arrays.copyOf(packet, 12), 12));
    }

    @Test
    public void other_ngc_packets_are_not_a_leave_notice()
    {
        final byte[] packet = leaveNoticePacket();
        assertFalse(isLeaveNotice(null, 0));
        assertFalse(isLeaveNotice(packet, 7));
        assertFalse(isLeaveNotice(Arrays.copyOf(packet, 7), 8));
        for (final byte id : new byte[]{0x01, 0x11, 0x21, 0x31, 0x41, 0x42, 0x43, 0x45, 0x50})
        {
            final byte[] other = leaveNoticePacket();
            other[7] = id;
            assertFalse("id " + id, isLeaveNotice(other, other.length));
        }
        final byte[] version2 = leaveNoticePacket();
        version2[6] = 0x02;
        assertFalse(isLeaveNotice(version2, version2.length));
        final byte[] wrongMagic = leaveNoticePacket();
        wrongMagic[0] = 0x67;
        assertFalse(isLeaveNotice(wrongMagic, wrongMagic.length));
    }

    @Test
    public void a_leave_notice_counts_once_for_the_exit_that_follows_it()
    {
        final String group = "group-leave-notice";
        noteLeaveNotice(group, IPHONE, 1_000L);
        // The key comes back in another case at the exit (toxcore's hex is upper case, the stored one may not be).
        assertTrue(takeLeaveNotice(group, lower(IPHONE), 1_200L));
        // Used up: the same member closing the app later is not a leave.
        assertFalse(takeLeaveNotice(group, IPHONE, 1_300L));
        // Another member, or the same key in another group, never said anything.
        noteLeaveNotice(group, IPHONE, 2_000L);
        assertFalse(takeLeaveNotice(group, MAC, 2_100L));
        assertFalse(takeLeaveNotice("another-group", IPHONE, 2_100L));
        assertTrue(takeLeaveNotice(group, IPHONE, 2_100L));
    }

    @Test
    public void an_old_leave_notice_or_one_dropped_by_a_join_does_not_count()
    {
        final String group = "group-leave-notice-window";
        noteLeaveNotice(group, MAC, 10_000L);
        assertFalse(takeLeaveNotice(group, MAC, 10_000L + LEAVE_NOTICE_WINDOW_MS + 1));

        noteLeaveNotice(group, MAC, 20_000L);
        // It joined again before any exit: it stayed.
        forgetLeaveNotice(group, MAC);
        assertFalse(takeLeaveNotice(group, MAC, 20_100L));

        // Not a key, or no group: nothing is kept.
        noteLeaveNotice(group, "not-a-key", 30_000L);
        assertFalse(takeLeaveNotice(group, "not-a-key", 30_001L));
        noteLeaveNotice(null, MAC, 30_000L);
        assertFalse(takeLeaveNotice(null, MAC, 30_001L));
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

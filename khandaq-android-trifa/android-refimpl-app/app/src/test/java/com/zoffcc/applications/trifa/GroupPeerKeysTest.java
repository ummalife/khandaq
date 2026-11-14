package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static com.zoffcc.applications.trifa.GroupPeerKeys.decodeDepartures;
import static com.zoffcc.applications.trifa.GroupPeerKeys.encodeDepartures;
import static com.zoffcc.applications.trifa.GroupPeerKeys.LEAVE_NOTICE_WINDOW_MS;
import static com.zoffcc.applications.trifa.GroupPeerKeys.dropLapsedLeaveNotice;
import static com.zoffcc.applications.trifa.GroupPeerKeys.exitTellsTheLeave;
import static com.zoffcc.applications.trifa.GroupPeerKeys.forgetLeaveNotice;
import static com.zoffcc.applications.trifa.GroupPeerKeys.isLeaveNotice;
import static com.zoffcc.applications.trifa.GroupPeerKeys.isLeaving;
import static com.zoffcc.applications.trifa.GroupPeerKeys.leaveNoticePacket;
import static com.zoffcc.applications.trifa.GroupPeerKeys.leavingKeys;
import static com.zoffcc.applications.trifa.GroupPeerKeys.leftForGood;
import static com.zoffcc.applications.trifa.GroupPeerKeys.noteLeaveNotice;
import static com.zoffcc.applications.trifa.GroupPeerKeys.onlineWithoutLeaving;
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

    private static final int QUIT = ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_QUIT.value;
    private static final int TIMEOUT = ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_TIMEOUT.value;
    private static final int DISCONNECTED = ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_DISCONNECTED.value;
    private static final int KICK = ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_KICK.value;

    /**
     * QA live 2026-11-14: QA Mac left a group over a relay. Android had the notice at once, but toxcore's QUIT was
     * lost and its exit came as a timeout 54 s later; until then Android listed and counted QA Mac. The member is
     * out at its notice now.
     */
    @Test
    public void a_member_that_said_it_is_leaving_is_out_until_its_exit()
    {
        final String group = "group-leaving-at-once";
        assertTrue(leavingKeys(group, 1_000L).isEmpty());
        assertTrue(noteLeaveNotice(group, IPHONE, 1_000L));
        assertTrue(isLeaving(group, IPHONE, 1_000L));
        // toxcore's key in either case; the keys listed are lower case, as the peer lists are compared.
        assertTrue(isLeaving(group, lower(IPHONE), 55_000L));
        assertEquals(new HashSet<>(Collections.singletonList(lower(IPHONE))), leavingKeys(group, 55_000L));
        // Another member, or the same key in another group, is not leaving.
        assertFalse(isLeaving(group, MAC, 1_000L));
        assertFalse(isLeaving("another-group", IPHONE, 1_000L));
        assertTrue(leavingKeys("another-group", 1_000L).isEmpty());
        // One leave may send the notice twice: the repeat puts no one out again, and the window is the first one's.
        assertFalse(noteLeaveNotice(group, IPHONE, 2_000L));
        assertFalse(isLeaving(group, IPHONE, 1_000L + LEAVE_NOTICE_WINDOW_MS + 1));
        // The exit, a timeout after the QUIT was lost on a relay, uses the notice up: a leave, and nothing more.
        assertTrue(takeLeaveNotice(group, IPHONE, 55_000L));
        assertFalse(isLeaving(group, IPHONE, 55_001L));
        assertTrue(leavingKeys(group, 55_001L).isEmpty());
    }

    @Test
    public void the_exit_after_a_notice_tells_the_group_nothing_more()
    {
        // Told at the notice: whatever exit follows it adds no "left the group".
        assertFalse(exitTellsTheLeave(QUIT, true));
        assertFalse(exitTellsTheLeave(TIMEOUT, true));
        assertFalse(exitTellsTheLeave(KICK, true));
        // A kick with no notice is told at the exit.
        assertTrue(exitTellsTheLeave(KICK, false));
        // An app that closed, a timeout, a lost connection: no one left.
        assertFalse(exitTellsTheLeave(QUIT, false));
        assertFalse(exitTellsTheLeave(TIMEOUT, false));
        assertFalse(exitTellsTheLeave(DISCONNECTED, false));
        // The member that said it was leaving is gone for good at that exit all the same.
        assertTrue(leftForGood(TIMEOUT, true));
        assertFalse(leftForGood(QUIT, false));
    }

    @Test
    public void members_that_are_leaving_are_out_of_the_online_count()
    {
        final Set<String> live = new HashSet<>(Arrays.asList(lower(IPHONE), lower(MAC), lower(NEWCOMER)));
        final Set<String> leaving = new HashSet<>(Collections.singletonList(lower(IPHONE)));
        // Three online with this device; QA iPhone said it is leaving and toxcore still counts it.
        assertEquals(2L, onlineWithoutLeaving(3L, live, leaving));
        // Its exit came: toxcore neither lists nor counts it, nothing more to take out.
        assertEquals(2L, onlineWithoutLeaving(2L, new HashSet<>(Arrays.asList(lower(MAC), lower(NEWCOMER))),
                                              leaving));
        // No one leaving, or no peer list: the count as toxcore gives it; never below zero.
        assertEquals(3L, onlineWithoutLeaving(3L, live, new HashSet<String>()));
        assertEquals(3L, onlineWithoutLeaving(3L, null, leaving));
        assertEquals(0L, onlineWithoutLeaving(0L, live, leaving));
        assertEquals(0L, onlineWithoutLeaving(-1L, live, leaving));
    }

    @Test
    public void a_member_that_is_leaving_is_marked_but_not_in_the_offline_count_to_take_out()
    {
        // QA Mac left earlier and is still in toxcore's saved peers; QA iPhone is leaving, marked at its notice and
        // still online in toxcore, so toxcore's offline count (2: QA Mac and another) does not have it.
        final Set<String> departed = new HashSet<>(Arrays.asList(lower(IPHONE), lower(MAC)));
        final Set<String> leaving = new HashSet<>(Collections.singletonList(lower(IPHONE)));
        assertEquals(1L, offlineWithoutDeparted(2L, departed, new HashSet<>(Collections.singletonList(lower(MAC))),
                                                leaving));
        // The saved peers not read yet: the marks of the members not leaving are taken out.
        assertEquals(1L, offlineWithoutDeparted(2L, departed, null, leaving));
        // No one leaving: as before.
        assertEquals(0L, offlineWithoutDeparted(2L, departed, null, null));
        assertEquals(offlineWithoutDeparted(2L, departed, null),
                     offlineWithoutDeparted(2L, departed, null, new HashSet<String>()));
    }

    @Test
    public void a_join_ends_the_leave()
    {
        final String group = "group-leaving-join";
        assertTrue(noteLeaveNotice(group, MAC, 5_000L));
        // It joined again before any exit: in the group, listed and counted.
        forgetLeaveNotice(group, MAC);
        assertFalse(isLeaving(group, MAC, 5_100L));
        assertTrue(leavingKeys(group, 5_100L).isEmpty());
        assertFalse(takeLeaveNotice(group, MAC, 5_200L));
        // A notice after the join puts it out again.
        assertTrue(noteLeaveNotice(group, MAC, 6_000L));
        assertTrue(isLeaving(group, MAC, 6_000L));
    }

    @Test
    public void a_notice_no_exit_follows_lapses_and_the_member_is_one_again()
    {
        final String group = "group-leaving-lapse";
        final long noticed = 100_000L;
        assertTrue(noteLeaveNotice(group, NEWCOMER, noticed));
        // Within the window it is not dropped.
        assertFalse(dropLapsedLeaveNotice(group, NEWCOMER, noticed, noticed + LEAVE_NOTICE_WINDOW_MS));
        assertTrue(isLeaving(group, NEWCOMER, noticed + LEAVE_NOTICE_WINDOW_MS));
        // After it the member is listed and counted again, and the notice is dropped, once.
        final long later = noticed + LEAVE_NOTICE_WINDOW_MS + 1_000L;
        assertFalse(isLeaving(group, NEWCOMER, later));
        assertTrue(leavingKeys(group, later).isEmpty());
        assertTrue(dropLapsedLeaveNotice(group, NEWCOMER, noticed, later));
        assertFalse(dropLapsedLeaveNotice(group, NEWCOMER, noticed, later));
        // Its exit, long after, is an app that closed.
        assertFalse(takeLeaveNotice(group, NEWCOMER, later + 1));

        // A notice its exit used, or one a later notice replaced, is not dropped.
        assertTrue(noteLeaveNotice(group, NEWCOMER, 200_000L));
        assertTrue(takeLeaveNotice(group, NEWCOMER, 254_000L));
        assertFalse(dropLapsedLeaveNotice(group, NEWCOMER, 200_000L, 200_000L + LEAVE_NOTICE_WINDOW_MS + 1_000L));
        assertTrue(noteLeaveNotice(group, NEWCOMER, 300_000L));
        assertFalse(dropLapsedLeaveNotice(group, NEWCOMER, 200_000L, 300_000L + LEAVE_NOTICE_WINDOW_MS + 1_000L));
        assertTrue(dropLapsedLeaveNotice(group, NEWCOMER, 300_000L, 300_000L + LEAVE_NOTICE_WINDOW_MS + 1_000L));

        // A lapsed notice not dropped yet does not hold back the next one.
        assertTrue(noteLeaveNotice(group, MAC, noticed));
        assertTrue(noteLeaveNotice(group, MAC, later));
        assertTrue(isLeaving(group, MAC, later));
        // No key or no group: no one is put out.
        assertFalse(noteLeaveNotice(group, "not-a-key", noticed));
        assertFalse(noteLeaveNotice(null, MAC, noticed));
        assertFalse(isLeaving(null, MAC, noticed));
        assertTrue(leavingKeys(null, noticed).isEmpty());
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

package com.zoffcc.applications.trifa;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.zoffcc.applications.trifa.GroupPendingLeaves.LEAVE_DELAY_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA live, F11): a group this device leaves stays in toxcore for a few seconds after its leave notice,
 * so that toxcore can resend the notice, and the app treats it as gone meanwhile.
 *
 * QA: over a TCP relay the notice was lost 1 time in 3 (Android -> Mac), and the member stayed listed for about
 * a minute. toxcore deleted the group on the tox_iterate after tox_group_leave, before it could resend anything.
 */
public class GroupPendingLeavesTest
{
    private static final String GROUP = "926c33466257eda8837c55d11580435a0a8b4482a451d5a427032c39d8947dc9";
    private static final String OTHER_GROUP = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899";
    private static final long T0 = 1_700_000_000_000L;

    /** toxcore as the pending leaves see it: which chat id a group number holds, and the leaves asked for. */
    private static final class FakeTox implements GroupPendingLeaves.Tox
    {
        final Map<Long, String> chatIdByNumber = new HashMap<>();
        final List<String> leaves = new ArrayList<>();

        @Override
        public String chatIdOf(final long groupNumber)
        {
            return chatIdByNumber.get(groupNumber);
        }

        @Override
        public void leave(final long groupNumber, final String partMessage)
        {
            leaves.add(groupNumber + ":" + partMessage);
        }
    }

    private static String lower(final String chatId)
    {
        return chatId.toLowerCase(Locale.ROOT);
    }

    @Before
    public void reset()
    {
        GroupPendingLeaves.clear();
    }

    @Test
    public void nothing_is_held_before_a_leave_is_recorded()
    {
        assertTrue(GroupPendingLeaves.isEmpty());
        assertFalse(GroupPendingLeaves.holdsGroupNumber(3));
        assertFalse(GroupPendingLeaves.holdsChatId(GROUP));
        assertTrue(GroupPendingLeaves.takeDue(T0 + LEAVE_DELAY_MS).isEmpty());
        assertTrue(GroupPendingLeaves.chatIds().isEmpty());
    }

    @Test
    public void a_recorded_leave_holds_the_group_by_number_and_by_chat_id_in_any_case()
    {
        assertTrue(GroupPendingLeaves.record(3, GROUP, "left", T0));

        assertFalse(GroupPendingLeaves.isEmpty());
        assertTrue(GroupPendingLeaves.holdsGroupNumber(3));
        assertTrue(GroupPendingLeaves.holdsChatId(GROUP));
        assertTrue(GroupPendingLeaves.holdsChatId(GROUP.toUpperCase(Locale.ROOT)));
        // Another group, under another number or with another chat id, goes on as before.
        assertFalse(GroupPendingLeaves.holdsGroupNumber(4));
        assertFalse(GroupPendingLeaves.holdsChatId(OTHER_GROUP));
    }

    @Test
    public void a_leave_without_a_group_number_or_a_chat_id_is_not_recorded()
    {
        assertFalse(GroupPendingLeaves.record(-1, GROUP, "left", T0));
        assertFalse(GroupPendingLeaves.record(3, null, "left", T0));
        assertFalse(GroupPendingLeaves.record(3, GROUP.substring(2), "left", T0));
        assertFalse(GroupPendingLeaves.record(3, GROUP.substring(2) + "zz", "left", T0));

        assertTrue(GroupPendingLeaves.isEmpty());
        assertFalse(GroupPendingLeaves.holdsChatId(null));
    }

    @Test
    public void a_group_is_recorded_once()
    {
        assertTrue(GroupPendingLeaves.record(3, GROUP, "left", T0));

        assertFalse(GroupPendingLeaves.record(3, GROUP, "bye", T0 + 1_000L));
        assertFalse(GroupPendingLeaves.record(5, GROUP.toUpperCase(Locale.ROOT), "bye", T0 + 1_000L));
        assertFalse(GroupPendingLeaves.record(3, OTHER_GROUP, "bye", T0 + 1_000L));

        // The first leave is the one that counts, with its due time.
        final List<GroupPendingLeaves.Leave> due = GroupPendingLeaves.takeDue(T0 + LEAVE_DELAY_MS);
        assertEquals(1, due.size());
        assertEquals("left", due.get(0).partMessage);
    }

    @Test
    public void the_leave_is_due_twelve_seconds_after_the_notice()
    {
        assertEquals(12_000L, LEAVE_DELAY_MS);
        GroupPendingLeaves.record(3, GROUP.toUpperCase(Locale.ROOT), "left", T0);

        assertTrue(GroupPendingLeaves.takeDue(T0 + LEAVE_DELAY_MS - 1).isEmpty());
        assertTrue(GroupPendingLeaves.holdsGroupNumber(3));

        final List<GroupPendingLeaves.Leave> due = GroupPendingLeaves.takeDue(T0 + LEAVE_DELAY_MS);
        assertEquals(1, due.size());
        final GroupPendingLeaves.Leave leave = due.get(0);
        assertEquals(3, leave.groupNumber);
        assertEquals(lower(GROUP), leave.chatId);
        assertEquals("left", leave.partMessage);
        assertFalse(leave.cancelled);
        // Taken once.
        assertTrue(GroupPendingLeaves.takeDue(T0 + LEAVE_DELAY_MS + 1_000L).isEmpty());
    }

    @Test
    public void a_due_leave_is_held_until_the_next_iterate_has_deleted_the_group()
    {
        final FakeTox tox = new FakeTox();
        tox.chatIdByNumber.put(3L, GROUP.toUpperCase(Locale.ROOT));
        GroupPendingLeaves.record(3, GROUP, "left", T0);

        final GroupPendingLeaves.Leave leave = GroupPendingLeaves.takeDue(T0 + LEAVE_DELAY_MS).get(0);
        assertTrue(GroupPendingLeaves.leaveNow(leave, tox));

        assertEquals(Arrays.asList("3:left"), tox.leaves);
        // toxcore deletes the group only in its next tox_iterate, and saves it with the profile until then.
        assertTrue(GroupPendingLeaves.holdsGroupNumber(3));
        assertTrue(GroupPendingLeaves.holdsChatId(GROUP));
        assertEquals(new TreeSet<>(Arrays.asList(lower(GROUP))), GroupPendingLeaves.chatIds());

        assertTrue(GroupPendingLeaves.dropLeft());
        assertTrue(GroupPendingLeaves.isEmpty());
        assertFalse(GroupPendingLeaves.holdsGroupNumber(3));
        assertFalse(GroupPendingLeaves.holdsChatId(GROUP));
        assertTrue(GroupPendingLeaves.chatIds().isEmpty());
        assertFalse(GroupPendingLeaves.dropLeft());
    }

    @Test
    public void a_due_leave_never_leaves_another_group_under_the_number()
    {
        final FakeTox tox = new FakeTox();
        tox.chatIdByNumber.put(3L, OTHER_GROUP);
        GroupPendingLeaves.record(3, GROUP, "left", T0);
        GroupPendingLeaves.record(4, OTHER_GROUP, "left", T0);

        final List<GroupPendingLeaves.Leave> due = GroupPendingLeaves.takeDue(T0 + LEAVE_DELAY_MS);
        assertEquals(2, due.size());
        // Number 3 holds another group now; number 4 holds none.
        assertFalse(GroupPendingLeaves.leaveNow(due.get(0), tox));
        assertFalse(GroupPendingLeaves.leaveNow(due.get(1), tox));

        assertTrue(tox.leaves.isEmpty());
        assertTrue(GroupPendingLeaves.isEmpty());
        assertFalse(GroupPendingLeaves.dropLeft());
    }

    @Test
    public void a_join_ends_the_wait_and_the_old_instance_is_left_on_the_next_pass()
    {
        final FakeTox tox = new FakeTox();
        tox.chatIdByNumber.put(3L, GROUP);
        GroupPendingLeaves.record(3, GROUP, "left", T0);

        final GroupPendingLeaves.Leave cancelled = GroupPendingLeaves.cancel(GROUP.toUpperCase(Locale.ROOT),
                                                                              T0 + 2_000L);
        assertTrue(cancelled.cancelled);
        assertEquals(3, cancelled.groupNumber);
        // The join waits for the old instance to go, so the group stays held.
        assertTrue(GroupPendingLeaves.holdsChatId(GROUP));

        final List<GroupPendingLeaves.Leave> due = GroupPendingLeaves.takeDue(T0 + 2_000L);
        assertEquals(1, due.size());
        assertSame(cancelled, due.get(0));
        assertTrue(GroupPendingLeaves.leaveNow(due.get(0), tox));
        assertEquals(Arrays.asList("3:left"), tox.leaves);
        assertTrue(GroupPendingLeaves.holdsChatId(GROUP));
        // Left already: nothing waits that a second join could end.
        assertNull(GroupPendingLeaves.cancel(GROUP, T0 + 2_100L));

        assertTrue(GroupPendingLeaves.dropLeft());
        assertFalse(GroupPendingLeaves.holdsChatId(GROUP));
    }

    @Test
    public void a_join_of_a_group_that_is_not_left_ends_nothing()
    {
        GroupPendingLeaves.record(3, GROUP, "left", T0);

        assertNull(GroupPendingLeaves.cancel(OTHER_GROUP, T0 + 1_000L));
        assertNull(GroupPendingLeaves.cancel(null, T0 + 1_000L));
        assertTrue(GroupPendingLeaves.takeDue(T0 + 1_000L).isEmpty());
    }

    @Test
    public void quitting_takes_every_waiting_leave_at_once()
    {
        final FakeTox tox = new FakeTox();
        tox.chatIdByNumber.put(3L, GROUP);
        tox.chatIdByNumber.put(4L, OTHER_GROUP);
        tox.chatIdByNumber.put(5L, "1122334455667788990011223344556677889900112233445566778899001122");
        GroupPendingLeaves.record(3, GROUP, "left", T0);
        GroupPendingLeaves.record(4, OTHER_GROUP, "bye", T0 + 5_000L);
        // One that was left on the last pass and waits for its tox_iterate.
        GroupPendingLeaves.noteLeft(5, "1122334455667788990011223344556677889900112233445566778899001122");

        final List<GroupPendingLeaves.Leave> all = GroupPendingLeaves.takeAll();
        assertEquals(2, all.size());
        assertEquals(3, all.get(0).groupNumber);
        assertEquals(4, all.get(1).groupNumber);
        for (final GroupPendingLeaves.Leave leave : all)
        {
            assertTrue(GroupPendingLeaves.leaveNow(leave, tox));
        }
        assertEquals(Arrays.asList("3:left", "4:bye"), tox.leaves);
        assertEquals(3, GroupPendingLeaves.chatIds().size());

        assertTrue(GroupPendingLeaves.dropLeft());
        assertTrue(GroupPendingLeaves.isEmpty());
        assertTrue(GroupPendingLeaves.takeAll().isEmpty());
    }

    @Test
    public void the_persisted_list_is_every_group_toxcore_may_still_save()
    {
        GroupPendingLeaves.record(3, OTHER_GROUP, "left", T0);
        GroupPendingLeaves.noteLeft(4, GROUP.toUpperCase(Locale.ROOT));

        final Set<String> ids = GroupPendingLeaves.chatIds();
        assertEquals(Arrays.asList(lower(GROUP), lower(OTHER_GROUP)), new ArrayList<>(ids));
        assertEquals(lower(GROUP) + "," + lower(OTHER_GROUP), GroupPendingLeaves.encode(ids));
    }

    @Test
    public void the_persisted_list_comes_back_as_it_was_written()
    {
        final Set<String> ids = new TreeSet<>(Arrays.asList(lower(GROUP), lower(OTHER_GROUP)));

        assertEquals(ids, GroupPendingLeaves.decode(GroupPendingLeaves.encode(ids)));
        assertEquals("", GroupPendingLeaves.encode(new TreeSet<String>()));
        assertTrue(GroupPendingLeaves.decode(null).isEmpty());
        assertTrue(GroupPendingLeaves.decode("").isEmpty());
    }

    @Test
    public void what_is_not_a_chat_id_is_dropped_from_the_persisted_list()
    {
        final Set<String> ids = GroupPendingLeaves.decode(
                " " + OTHER_GROUP + " ,,not-a-chat-id," + GROUP.substring(1) + "," + GROUP + "," + GROUP);

        assertEquals(new TreeSet<>(Arrays.asList(lower(GROUP), lower(OTHER_GROUP))), ids);
        assertEquals(lower(OTHER_GROUP), GroupPendingLeaves.encode(Arrays.asList(OTHER_GROUP, "x", null)));
    }

    @Test
    public void a_leave_found_at_startup_is_held_until_the_first_iterate()
    {
        GroupPendingLeaves.noteLeft(7, GROUP);

        assertTrue(GroupPendingLeaves.holdsGroupNumber(7));
        assertTrue(GroupPendingLeaves.holdsChatId(GROUP));
        // It is left already: neither due nor taken when the app quits.
        assertTrue(GroupPendingLeaves.takeDue(Long.MAX_VALUE).isEmpty());
        assertTrue(GroupPendingLeaves.takeAll().isEmpty());

        assertTrue(GroupPendingLeaves.dropLeft());
        assertTrue(GroupPendingLeaves.isEmpty());
    }

    @Test
    public void a_new_tox_instance_forgets_the_old_group_numbers()
    {
        GroupPendingLeaves.record(3, GROUP, "left", T0);
        GroupPendingLeaves.noteLeft(4, OTHER_GROUP);

        GroupPendingLeaves.clear();

        assertTrue(GroupPendingLeaves.isEmpty());
        assertFalse(GroupPendingLeaves.holdsGroupNumber(3));
        assertFalse(GroupPendingLeaves.holdsGroupNumber(4));
        assertTrue(GroupPendingLeaves.chatIds().isEmpty());
    }

    @Test
    public void a_leave_waits_only_for_a_notice_toxcore_queued_for_another_member()
    {
        // The JNI send returns 1 when toxcore took the packet; tox_group_peer_count counts this device too.
        assertTrue(GroupPendingLeaves.noticeWasQueued(1, 2));
        assertTrue(GroupPendingLeaves.noticeWasQueued(1, 7));
        // Sent to nobody: toxcore reports that as sent, and there is nothing to resend.
        assertFalse(GroupPendingLeaves.noticeWasQueued(1, 1));
        assertFalse(GroupPendingLeaves.noticeWasQueued(1, 0));
        // tox_group_peer_count failed.
        assertFalse(GroupPendingLeaves.noticeWasQueued(1, -98));
        // Not sent: the group is not connected, or we may not send to it.
        assertFalse(GroupPendingLeaves.noticeWasQueued(0, 5));
        assertFalse(GroupPendingLeaves.noticeWasQueued(-104, 5));
        assertFalse(GroupPendingLeaves.noticeWasQueued(-101, 5));
    }
}

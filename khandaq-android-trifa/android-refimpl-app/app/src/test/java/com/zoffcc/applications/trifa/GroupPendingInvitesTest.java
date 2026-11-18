package com.zoffcc.applications.trifa;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static com.zoffcc.applications.trifa.GroupPendingInvites.MAX_AGE_MS;
import static com.zoffcc.applications.trifa.GroupPendingInvites.MAX_ENTRIES;
import static com.zoffcc.applications.trifa.GroupPendingInvites.Outcome.LATER;
import static com.zoffcc.applications.trifa.GroupPendingInvites.Outcome.REFUSED;
import static com.zoffcc.applications.trifa.GroupPendingInvites.Outcome.SENT;
import static com.zoffcc.applications.trifa.GroupPendingInvites.outcomeOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.60): a group invitation to a contact who is offline waits for them across restarts of the app, up to
 * 30 days, as on the desktop. Owner's feedback: a person can be added to a group even when they are not online.
 */
public class GroupPendingInvitesTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String FRIEND = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90";
    private static final String OTHER_FRIEND = friend(2);
    private static final String GROUP = "926c33466257eda8837c55d11580435a0a8b4482a451d5a427032c39d8947dc9";
    private static final String OTHER_GROUP = group(2);
    private static final long T0 = 1_700_000_000_000L;

    private static String friend(final int n)
    {
        return String.format(Locale.ROOT, "f%063d", n);
    }

    private static String group(final int n)
    {
        return String.format(Locale.ROOT, "c%063d", n);
    }

    @Before
    public void setUp()
    {
        GroupPendingInvites.clear();
    }

    @After
    public void tearDown()
    {
        GroupPendingInvites.clear();
    }

    @Test
    public void only_a_failure_of_the_link_waits()
    {
        assertEquals(SENT, outcomeOf(1));
        // INVITE_FAIL (the contact is offline), FAIL_SEND, DISCONNECTED, and no toxcore yet: it waits.
        for (final int res : new int[]{-3, -4, -5, -99, 0})
        {
            assertEquals(String.valueOf(res), LATER, outcomeOf(res));
        }
        // No such group or contact in toxcore, or an answer toxcore does not have: it goes.
        for (final int res : new int[]{-1, -2, -6})
        {
            assertEquals(String.valueOf(res), REFUSED, outcomeOf(res));
        }
    }

    @Test
    public void an_invitation_waits_for_its_contact_across_a_restart()
    {
        assertFalse(GroupPendingInvites.load(null, T0));
        assertTrue(GroupPendingInvites.add(FRIEND, GROUP, T0));
        assertTrue(GroupPendingInvites.add(FRIEND, OTHER_GROUP, T0 + 1));
        assertTrue(GroupPendingInvites.add(OTHER_FRIEND, GROUP, T0 + 2));
        final String stored = GroupPendingInvites.encode();
        assertTrue(stored, stored.contains(FRIEND.toLowerCase(Locale.ROOT) + ":" + GROUP + ":" + T0));

        // The app stops: what was held goes with it; the next start reads the list back.
        GroupPendingInvites.clear();
        assertFalse(GroupPendingInvites.hasFor(FRIEND));
        assertFalse(GroupPendingInvites.load(stored, T0 + 3));
        assertTrue(GroupPendingInvites.isLoaded());
        assertEquals(Arrays.asList(GROUP, OTHER_GROUP), GroupPendingInvites.groupsFor(FRIEND));
        assertEquals(Collections.singletonList(GROUP), GroupPendingInvites.groupsFor(OTHER_FRIEND));
        assertEquals(stored, GroupPendingInvites.encode());
    }

    @Test
    public void only_keys_are_kept_and_one_invitation_counts_once()
    {
        assertFalse(GroupPendingInvites.add(null, GROUP, T0));
        assertFalse(GroupPendingInvites.add(FRIEND, null, T0));
        assertFalse(GroupPendingInvites.add(FRIEND.substring(1), GROUP, T0));
        assertFalse(GroupPendingInvites.add(FRIEND, GROUP.replace('9', 'g'), T0));
        assertEquals(0, GroupPendingInvites.size());
        // The same invitation asked for again is one entry, from the later time.
        assertTrue(GroupPendingInvites.add(FRIEND, GROUP.toUpperCase(Locale.ROOT), T0));
        assertTrue(GroupPendingInvites.add(FRIEND.toLowerCase(Locale.ROOT), GROUP, T0 + 5));
        assertEquals(1, GroupPendingInvites.size());
        assertEquals(FRIEND.toLowerCase(Locale.ROOT) + ":" + GROUP + ":" + (T0 + 5), GroupPendingInvites.encode());
    }

    @Test
    public void an_invitation_waits_thirty_days()
    {
        assertEquals(30L * 24L * 60L * 60L * 1000L, MAX_AGE_MS);
        GroupPendingInvites.add(FRIEND, GROUP, T0);
        GroupPendingInvites.add(FRIEND, OTHER_GROUP, T0 + 1000);
        assertFalse(GroupPendingInvites.prune(T0 + MAX_AGE_MS));
        assertTrue(GroupPendingInvites.prune(T0 + MAX_AGE_MS + 1));
        assertEquals(Collections.singletonList(OTHER_GROUP), GroupPendingInvites.groupsFor(FRIEND));
        assertTrue(GroupPendingInvites.prune(T0 + 1000 + MAX_AGE_MS + 1));
        assertFalse(GroupPendingInvites.hasFor(FRIEND));
        assertEquals("", GroupPendingInvites.encode());
    }

    @Test
    public void reading_back_leaves_out_what_is_past_its_age_or_unreadable()
    {
        final String fresh = friend(1) + ":" + GROUP + ":" + T0;
        final String stored = fresh + ",garbage," + friend(2) + ":" + GROUP + ":" + (T0 - MAX_AGE_MS - 1) + ","
                              + friend(3) + ":" + GROUP + ":later," + friend(4) + ":" + GROUP;
        assertTrue(GroupPendingInvites.load(stored, T0));
        assertEquals(fresh, GroupPendingInvites.encode());
        // A list read back as it was written needs no writing.
        assertFalse(GroupPendingInvites.load(fresh, T0 + 1));
    }

    @Test
    public void a_time_ahead_of_the_clock_counts_from_now()
    {
        assertTrue(GroupPendingInvites.load(friend(1) + ":" + GROUP + ":" + (T0 + 10 * MAX_AGE_MS), T0));
        assertTrue(GroupPendingInvites.hasFor(friend(1)));
        assertTrue(GroupPendingInvites.prune(T0 + MAX_AGE_MS + 1));
        assertFalse(GroupPendingInvites.hasFor(friend(1)));
    }

    @Test
    public void a_removed_contact_or_a_left_group_takes_its_invitations_along()
    {
        GroupPendingInvites.add(FRIEND, GROUP, T0);
        GroupPendingInvites.add(FRIEND, OTHER_GROUP, T0);
        GroupPendingInvites.add(OTHER_FRIEND, GROUP, T0);
        assertTrue(GroupPendingInvites.forgetGroup(GROUP.toUpperCase(Locale.ROOT)));
        assertEquals(Collections.singletonList(OTHER_GROUP), GroupPendingInvites.groupsFor(FRIEND));
        assertFalse(GroupPendingInvites.hasFor(OTHER_FRIEND));
        assertFalse(GroupPendingInvites.forgetGroup(GROUP));
        assertTrue(GroupPendingInvites.forgetFriend(FRIEND));
        assertFalse(GroupPendingInvites.forgetFriend(FRIEND));
        assertEquals(0, GroupPendingInvites.size());
        // One that went, or was refused, goes by itself.
        GroupPendingInvites.add(FRIEND, GROUP, T0);
        assertTrue(GroupPendingInvites.remove(FRIEND, GROUP));
        assertFalse(GroupPendingInvites.remove(FRIEND, GROUP));
        assertFalse(GroupPendingInvites.hasFor(FRIEND));
    }

    @Test
    public void at_most_256_wait_and_the_oldest_goes_first()
    {
        assertEquals(256, MAX_ENTRIES);
        for (int i = 0; i <= MAX_ENTRIES; i++)
        {
            assertTrue(GroupPendingInvites.add(friend(i), GROUP, T0 + i));
        }
        assertEquals(MAX_ENTRIES, GroupPendingInvites.size());
        assertFalse(GroupPendingInvites.hasFor(friend(0)));
        assertTrue(GroupPendingInvites.hasFor(friend(1)));
        assertTrue(GroupPendingInvites.hasFor(friend(MAX_ENTRIES)));
    }

    @Test
    public void nothing_is_written_before_the_list_was_read_for_the_profile()
    {
        assertFalse(GroupPendingInvites.isLoaded());
        assertFalse(GroupPendingInvites.load("", T0));
        assertTrue(GroupPendingInvites.isLoaded());
        GroupPendingInvites.clear();
        assertFalse(GroupPendingInvites.isLoaded());

        final String helper = read(JAVA + "HelperGroup.java");
        final String save = method(helper, "private static void save_pending_manual_group_invites()");
        final int guard = save.indexOf("if (!GroupPendingInvites.isLoaded())");
        assertTrue(save, guard >= 0 && guard < save.indexOf("set_g_opts(") && guard < save.indexOf("del_g_opts("));
        final String load = method(helper, "static void load_pending_manual_group_invites()");
        // A stand-in profile (#244) reads nothing, so nothing is written over the person's list.
        final int blocked = load.indexOf("MainActivity.identity_load_blocked");
        assertTrue(load, blocked >= 0 && blocked < load.indexOf("GroupPendingInvites.clear();")
                         && load.indexOf("GroupPendingInvites.clear();") < load.indexOf("GroupPendingInvites.load("));
    }

    @Test
    public void the_list_is_read_at_start_and_follows_contacts_and_groups()
    {
        final String service = read(JAVA + "TrifaToxService.java");
        final int leaves = service.indexOf("HelperGroup.leave_groups_pending_at_last_stop();");
        final int load = service.indexOf("HelperGroup.load_pending_manual_group_invites();");
        assertTrue(leaves >= 0 && load > leaves);

        final String helper = read(JAVA + "HelperGroup.java");
        assertFalse("the in-memory queue is gone", helper.contains("pending_manual_group_invites ="));
        assertTrue(method(helper, "static void set_group_group_we_left(")
                           .contains("forget_manual_group_invites_for_group(group_identifier);"));
        assertTrue(method(helper, "static void delete_group(final String group_identifier)")
                           .contains("forget_manual_group_invites_for_group(group_identifier);"));
        assertTrue(method(read(JAVA + "HelperFriend.java"), "static void delete_friend(final String friend_pubkey)")
                           .contains("HelperGroup.forget_manual_group_invites_for_friend(friend_pubkey);"));
        assertTrue(method(helper, "private static void queue_manual_group_invite(")
                           .contains("save_pending_manual_group_invites();"));

        final String retry = method(helper, "static void retry_pending_manual_group_invites(");
        assertTrue(retry, retry.contains("GroupPendingInvites.prune("));
        // A group the person left is never joined again for an invitation: it is checked before toxcore is asked.
        final int left = retry.indexOf("is_group_we_left(group_identifier)");
        assertTrue(retry, left >= 0 && left < retry.indexOf("ensure_group_in_tox(group_identifier)"));
        assertTrue(retry, retry.contains("GroupPendingInvites.outcomeOf(res)"));
        assertTrue(retry, retry.contains("if (outcome != GroupPendingInvites.Outcome.LATER)"));
        assertTrue(retry, retry.contains("save_pending_manual_group_invites();"));
    }

    /** The text of the method that starts with signature, up to its closing brace at class level. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

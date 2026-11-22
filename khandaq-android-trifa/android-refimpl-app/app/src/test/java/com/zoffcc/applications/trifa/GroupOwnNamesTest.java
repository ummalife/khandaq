package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (tester 21.11, 8257): a name set in a group's settings ("AZ iOS1") was the profile name ("AZ iOS") again
 * later. Every save of the profile sent the profile name into every group, and every join the app makes of a group
 * again took the profile name. The rules of the name a group keeps are pure and tested here; the wiring is Android's
 * and toxcore's, so it is read from the sources (comments left out), as GroupAuthorTapTest does.
 */
public class GroupOwnNamesTest
{
    private static final String GROUP = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";

    @Test
    public void a_group_has_one_row_by_its_id_in_lower_case()
    {
        assertEquals(GroupOwnNames.ROW_KEY_PREFIX + GROUP, GroupOwnNames.rowKey(GROUP));
        assertEquals(GroupOwnNames.ROW_KEY_PREFIX + GROUP, GroupOwnNames.rowKey(GROUP.toUpperCase(java.util.Locale.ROOT)));
        assertEquals(GroupOwnNames.ROW_KEY_PREFIX + GROUP, GroupOwnNames.rowKey(" " + GROUP + "\n"));
    }

    @Test
    public void what_is_not_a_group_id_has_no_row()
    {
        assertNull(GroupOwnNames.rowKey(null));
        assertNull(GroupOwnNames.rowKey(""));
        assertNull(GroupOwnNames.rowKey(GROUP.substring(1)));
        assertNull(GroupOwnNames.rowKey(GROUP + "00"));
        assertNull(GroupOwnNames.rowKey(GROUP.substring(1) + "g"));
    }

    @Test
    public void a_name_other_than_the_profiles_is_kept()
    {
        assertEquals("AZ iOS1", GroupOwnNames.nameToKeep("AZ iOS1", "AZ iOS"));
        assertEquals("AZ iOS1", GroupOwnNames.nameToKeep("AZ iOS1", null));
        // Letters of any script, and a case of its own, are a name of its own.
        assertEquals("az ios", GroupOwnNames.nameToKeep("az ios", "AZ iOS"));
        assertEquals("عزيز", GroupOwnNames.nameToKeep("عزيز", "AZ iOS"));
    }

    @Test
    public void the_profile_name_or_none_makes_the_group_follow_the_profile_again()
    {
        assertNull(GroupOwnNames.nameToKeep("AZ iOS", "AZ iOS"));
        assertNull(GroupOwnNames.nameToKeep(" AZ iOS ", "AZ iOS"));
        assertNull(GroupOwnNames.nameToKeep(null, "AZ iOS"));
        assertNull(GroupOwnNames.nameToKeep("", "AZ iOS"));
        assertNull(GroupOwnNames.nameToKeep("   ", "AZ iOS"));
    }

    @Test
    public void a_join_takes_the_groups_own_name_else_the_profiles()
    {
        assertEquals("AZ iOS1", GroupOwnNames.joinName("AZ iOS1", "AZ iOS"));
        assertEquals("AZ iOS", GroupOwnNames.joinName(null, "AZ iOS"));
        assertEquals("AZ iOS", GroupOwnNames.joinName("", "AZ iOS"));
        assertEquals("AZ iOS", GroupOwnNames.joinName(" ", "AZ iOS"));
    }

    @Test
    public void a_stored_row_reads_back_as_the_name_or_none()
    {
        assertEquals("AZ iOS1", GroupOwnNames.decode("AZ iOS1"));
        assertNull(GroupOwnNames.decode(null));
        assertNull(GroupOwnNames.decode(""));
        assertNull(GroupOwnNames.decode(" \n"));
    }

    @Test
    public void the_group_settings_keep_the_name_once_toxcore_took_it()
    {
        final String write = bare(body(code("GroupInfoActivity.java"),
                                       "private static Outcome writeSelfName(final String groupId, final String name)"));
        // The stored part of call() runs only after toxcore said yes, and the save of the profile follows it.
        assertTrue(write.contains(bare("call(groupId, null, \"self name\", num -> MainActivity.tox_group_self_set_name(num, name),"
                                       + " () -> HelperGroup.set_group_own_name(groupId, name));")));
    }

    @Test
    public void a_save_of_the_profile_leaves_a_group_with_a_name_of_its_own_alone()
    {
        final String push = bare(body(code("HelperGroup.java"),
                                      "static void sync_self_display_name_to_all_groups(@Nullable final String name)"));
        final int skip = push.indexOf(bare("if (get_group_own_name(tox_group_by_groupnum__wrapper(group_num)) != null) {"
                                           + " continue; }"));
        assertTrue("the push skips a group with its own name", skip > 0);
        assertTrue(skip < push.indexOf("tox_group_self_set_name(group_num,sanitized);"));
    }

    @Test
    public void every_join_of_a_group_again_takes_its_own_name()
    {
        final String helper = code("HelperGroup.java");
        assertTrue(bare(body(helper, "static long ensure_group_in_tox(@NonNull String group_identifier)")).contains(
                "get_group_peer_join_name(group_identifier),null);"));
        assertTrue(bare(body(helper, "@Nullable final java.util.function.BooleanSupplier still_wanted)")).contains(
                "get_group_peer_join_name(normalized_group_id),password);"));
        assertTrue(bare(body(code("MainActivity.java"), "static String join_group_invite(long friend_number,")).contains(
                "HelperGroup.get_group_peer_join_name(invited_group_identifier),null);"));
        // A group made here is new: it has no name of its own yet.
        final String made = bare(helper);
        final int profileName = made.indexOf("finalStringpeer_name=sanitize_group_peer_name(get_group_peer_join_name());");
        assertTrue(profileName > 0);
        assertTrue(made.indexOf("tox_group_new(privacy_state,group_name,peer_name);", profileName) > profileName);
        // And when toxcore says we are in, the group's own name is set again.
        final String apply = bare(body(helper, "static void apply_group_self_display_name(final long group_num)"));
        assertTrue(apply.contains(bare("final String own = get_group_own_name(tox_group_by_groupnum__wrapper(group_num));"
                                       + " final String nick = (own != null) ? own : resolve_group_self_raw_name(group_num);")));
    }

    @Test
    public void the_names_are_read_again_for_each_session_and_go_with_a_deleted_group()
    {
        final String start = code("TrifaToxService.java");
        final int forget = start.indexOf("HelperGroup.forget_group_own_names_read();");
        assertTrue(forget > 0);
        assertTrue("before the session counts as started", forget < start.indexOf("is_tox_started = true;", forget));
        assertTrue(bare(body(code("HelperGroup.java"), "static void delete_group(final String group_identifier)")).contains(
                "set_group_own_name(group_identifier,null);"));
    }

    // ---- sources ----

    private static String code(final String name)
    {
        return AntispamOnRequestTest.code(read(JAVA + name));
    }

    private static String body(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        final int open = text.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (c == '{')
            {
                depth++;
            }
            else if (c == '}')
            {
                depth--;
                if (depth == 0)
                {
                    return text.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError(head + " has no end");
    }

    private static String bare(final String source)
    {
        return source.replaceAll("\\s+", "");
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

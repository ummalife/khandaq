package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #5, 27.11): a group opens at once, pinned on the sources with their comments left out. Its screen
 * stood still for half a second to a second each time it opened, in a big group, while the main thread read the
 * members of the side panel, and the name and the picture of the header filled in a moment after the screen. These
 * are the lines a merge resolved the easy way would drop without any other test failing.
 */
public class GroupOpensAtOnceWiringTest
{
    @Test
    public void the_members_of_the_side_panel_are_read_off_the_main_thread() throws Exception
    {
        final String activity = code("GroupMessageListActivity.java");
        final String schedule = bare(body(activity, "void set_peer_names_and_avatars()"));
        final int queued = schedule.indexOf("if(!member_read_queued.compareAndSet(false,true)){return;}");
        final int reader = schedule.indexOf("GROUP_MEMBER_READER.execute(");
        assertTrue(queued >= 0);
        assertTrue(reader > queued);
        assertTrue(schedule.indexOf("read_group_drawer_peers(read_group_id);", reader) > reader);
        assertTrue(schedule.contains("if(read_group_id.equalsIgnoreCase(group_id)){sync_group_drawer_peers(all_peers);}"));
        // The caller asks neither the database nor toxcore: the reader does.
        assertFalse(schedule.contains("collect_group_members_for_display("));
        assertFalse(schedule.contains("lookup_group_peer_by_pubkey("));
        assertFalse(schedule.contains("tox_group_"));

        final String read = bare(body(activity, "private static List<group_list_peer> read_group_drawer_peers("));
        assertTrue(read.contains("collect_group_members_for_display(read_group_id);"));
        assertTrue(read.contains("lookup_group_peer_by_pubkey(read_group_id,member.pubkey);"));
        assertTrue(read.endsWith("returnall_peers;"));
    }

    @Test
    public void a_row_of_the_side_panel_does_not_look_for_its_picture_as_it_is_built() throws Exception
    {
        final String item = body(code("GroupMessageListActivity.java"),
                                 "private ConferenceCustomDrawerPeerItem create_group_drawer_peer_item(");
        assertFalse(item.contains("friendlist_has_avatar_for_pubkey("));
        assertTrue(bare(item).contains("newConferenceCustomDrawerPeerItem(false,peer.peer_pubkey,group_id,peer.peer_name)"));
    }

    @Test
    public void the_header_shows_the_group_before_its_first_read_comes_back() throws Exception
    {
        assertTrue(bare(code("GroupMessageListActivity.java")).contains("airPanel.start();airPanel.showFirst(group_id);"));

        final String panel = code("AirGroupPanel.java");
        final String first = bare(body(panel, "void showFirst(final String groupId)"));
        final int remembered = first.indexOf(
                "finalSnapshotlast=LAST_SHOWN.get(groupId.toLowerCase(Locale.ROOT));if(last!=null){show(last);return;}");
        assertTrue(remembered >= 0);
        assertTrue(first.indexOf("setText(name,title);bindTile(groupId,title);", remembered) > remembered);
        assertTrue(bare(body(panel, "void show(final Snapshot s)")).contains(
                "shown=s;LAST_SHOWN.put(s.groupId.toLowerCase(Locale.ROOT),s);"));
    }

    private static String code(final String name) throws java.io.IOException
    {
        return AntispamOnRequestTest.code(source(name));
    }

    private static String bare(final String text)
    {
        return text.replaceAll("\\s+", "");
    }

    private static String body(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        final int open = text.indexOf('{', at);
        assertTrue("no opening brace", open >= 0);
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
        throw new AssertionError("unbalanced braces");
    }

    private static String source(final String name) throws java.io.IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final java.io.File file = new java.io.File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                  java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new java.io.File("").getAbsolutePath());
    }
}

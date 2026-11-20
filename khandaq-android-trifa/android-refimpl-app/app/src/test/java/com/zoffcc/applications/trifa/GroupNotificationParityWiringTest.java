package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (2026-11-20, notification parity): a message of a group is told as a message of a contact is. Three things
 * differed:
 * <ul>
 * <li>the system notification of a group named the group only, not the member who wrote;</li>
 * <li>a file of another member was told when its last chunk was in, and in Khandaq Community, where it waits for a
 * tap, never;</li>
 * <li>with a group's chat on screen a message came in silence, where a contact's chat on screen plays the short
 * message sound.</li>
 * </ul>
 * The callbacks call toxcore and the database, so these read the sources (comments left out) that decide.
 */
public class GroupNotificationParityWiringTest
{
    @Test
    public void the_system_notification_of_a_group_names_the_member_who_wrote() throws Exception
    {
        final String notifications = code("HelperMsgNotification.java");
        assertTrue(notifications.contains(
                "member_name = HelperGroup.tox_group_peer_get_name__wrapper(nf_key, group_peer_key);"));
        // The text of a message and the label of a file, both.
        assertEquals(2, count(notifications, "with_group_member(context_s, group_member, "));
        assertTrue(body(notifications, "static String with_group_member(").contains(
                "R.string.air_band_sender_text_format"));

        // Every notification of a group message hands over the member it came from.
        final String group = code("HelperGroup.java");
        assertTrue(body(group, "static void android_tox_callback_group_message_cb_method_wrapper(").contains(
                "m.sent_timestamp, peer_pubkey);"));
        assertTrue(body(group, "private static void notify_incoming_group_file(").contains(
                "m.sent_timestamp, m.tox_group_peer_pubkey);"));
    }

    @Test
    public void a_group_file_is_told_when_its_row_appears_and_not_again_at_its_end() throws Exception
    {
        final String group = code("HelperGroup.java");
        final String begin = body(group, "static void handle_incoming_group_file_begin(");
        final int insert = begin.indexOf("insert_into_group_message_db(m, viewing);");
        assertTrue(insert > 0);
        assertTrue(begin.indexOf("notify_incoming_group_file(groupId, m, displayName);") > insert);

        final String notify = body(group, "private static void notify_incoming_group_file(");
        assertTrue(notify.contains("should_notify_incoming_group_file(groupId, m.tox_group_peer_pubkey, groupDb)"));

        // At its end only a file that had no row is told: its row could not be written when it came.
        final String complete = body(group, "static void handle_incoming_group_file_complete(");
        assertEquals(1, count(complete, "change_msg_notification("));
        final String hadRow = block(complete, complete.indexOf('{', complete.indexOf("if (existing != null)")));
        assertFalse(hadRow.contains("change_msg_notification("));

        // The mutes decide. The chat on screen is for show_msg_notification to judge, as for every message.
        final String rule = body(group, "private static boolean should_notify_incoming_group_file(");
        assertTrue(rule.contains("groupDb.notification_silent"));
        assertTrue(rule.contains("group_notification_silent_peer_get(groupId, peerPubkey)"));
        assertFalse(rule.contains("group_message_list_activity"));
    }

    @Test
    public void a_group_chat_on_screen_gets_the_short_sound_and_no_badge() throws Exception
    {
        final String group = code("HelperGroup.java");
        for (final String head : new String[]{"static void android_tox_callback_group_message_cb_method_wrapper(",
                                              "static void handle_incoming_group_file(long group_number,"})
        {
            final String callback = body(group, head);
            final int open = callback.indexOf("get_current_group_id().equalsIgnoreCase(group_id))");
            assertTrue(head, open > 0);
            final String onScreen = block(callback, callback.indexOf('{', open));
            assertTrue(head, onScreen.contains("do_badge_update = false;"));
            assertFalse(head, onScreen.contains("do_notification = false;"));
        }

        // show_msg_notification takes a group's chat on screen for the chat on screen, as a contact's: the short
        // sound, nothing posted, no band.
        final String notifications = code("HelperMsgNotification.java");
        assertTrue(body(notifications, "static boolean is_viewing_chat(").contains(
                "is_viewing_friend_chat(key) || is_viewing_group_chat(key)"));
        assertTrue(body(notifications, "static boolean is_viewing_group_chat(").contains(
                "MainActivity.group_message_list_activity.get_current_group_id()"));
        assertEquals(0, count(notifications, "is_viewing_friend_chat(nf_key)"));
        assertEquals(2, count(notifications, "is_viewing_chat(nf_key)"));
    }

    private static String code(final String name) throws java.io.IOException
    {
        return AntispamOnRequestTest.code(source(name));
    }

    private static int count(final String text, final String what)
    {
        int n = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length()))
        {
            n++;
        }
        return n;
    }

    private static String body(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        return block(text, text.indexOf('{', at));
    }

    private static String block(final String text, final int open)
    {
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

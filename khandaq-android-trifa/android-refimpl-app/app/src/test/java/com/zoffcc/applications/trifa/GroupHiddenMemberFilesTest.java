package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57, review of the desktop's Community code): a member whose messages are hidden in a group sends no
 * file there either: no Accept entry, no unread count, no place in the hold.
 * <p>
 * On Android the one state that hides what a member writes in a group is the observer role, set by its founder or a
 * moderator (is_group_muted_or_kicked_peer). "Mute in this group" only silences the member's notifications: their
 * messages show and count as before, and so do their files. Each path a group file comes in by drops an observer's
 * before anything of the file exists: its offer, its chunks, its row.
 */
public class GroupHiddenMemberFilesTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String HIDDEN_SENDER = "if (is_group_muted_or_kicked_peer(group_number, peer_id))";
    private static final String HIDDEN_AUTHOR =
            "if (is_group_muted_or_kicked_peer(group_identifier, original_sender_peerpubkey))";

    @Test
    public void the_hidden_state_is_the_observer_role()
    {
        final String helper = read(JAVA + "HelperGroup.java");
        final String rule = method(helper,
                                   "static boolean is_group_muted_or_kicked_peer(final String group_identifier,");
        assertTrue(rule.contains("return peer_role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;"));
        // Mute in this group silences, and hides nothing.
        final String mute = method(read(JAVA + "GroupMemberActions.java"), "static void setMemberMuted(");
        assertTrue(mute.contains("group_notification_silent_peer_set(group_id, peer_pubkey, muted);"));
        assertFalse(mute.contains("is_group_muted_or_kicked_peer"));
    }

    @Test
    public void a_file_a_hidden_member_sends_is_dropped_before_its_offer_or_its_chunks()
    {
        final String main = read(JAVA + "MainActivity.java");
        final String toAll = method(main, "static void android_tox_callback_group_custom_packet_cb_method(");
        for (final String file : new String[]{"routeNgcChunkedFileTransferPacket(", "handle_incoming_group_file(",
                                              "NgcGroupFileTransfer.handleIncomingBegin(",
                                              "NgcGroupFileTransfer.handleIncomingChunk("})
        {
            assertBefore(toAll, HIDDEN_SENDER, file);
        }
        final String toUs = method(main, "static void android_tox_callback_group_custom_private_packet_cb_method(");
        for (final String file : new String[]{"routeNgcChunkedFileTransferPacket(", "handle_incoming_sync_group_file(",
                                              "handle_incoming_sync_group_message("})
        {
            assertBefore(toUs, HIDDEN_SENDER, file);
        }
    }

    @Test
    public void a_file_from_history_whose_author_is_hidden_is_dropped_before_its_row()
    {
        // History names the author; the peer that relays it may be anyone, so the check is on the author it names.
        final String helper = read(JAVA + "HelperGroup.java");
        assertBefore(method(helper, "static void handle_incoming_sync_group_file("), HIDDEN_AUTHOR,
                     "group_file_add_from_sync(");
        assertBefore(method(helper, "static void handle_incoming_sync_group_message("), HIDDEN_AUTHOR,
                     "group_message_add_from_sync(");
    }

    private static void assertBefore(final String source, final String first, final String then)
    {
        final int at = source.indexOf(first);
        assertTrue(first, at >= 0);
        assertTrue(then, source.indexOf(then) > at);
    }

    /** A method of a class at the usual indent: from its signature to the brace that closes it. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        return source.substring(start, source.indexOf("\n    }\n", start));
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

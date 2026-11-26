package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (26.11.2026): a private message of a group reaches only its two members
 * (DESIGN-group-contacts-private-mentions.md): the history this phone serves to a member never holds one,
 * and Reply on one answers privately instead of quoting it to the group.
 */
public class GroupPrivateMessagesTest
{
    @Test
    public void the_history_served_to_a_member_never_holds_a_private_message() throws Exception
    {
        final String helper = read("HelperGroup.java");
        final int serve = helper.indexOf("final Runnable serve_history = new Runnable()");
        assertTrue(serve >= 0);
        final int query = helper.indexOf("orma.selectFromGroupMessage()", serve);
        final int rows = helper.indexOf(".toList()", query);
        assertTrue(query > serve && rows > query);
        assertTrue(helper.substring(query, rows).contains(".private_messageEq(0)"));
        // That query is the only one that serves history: one sender of synced texts and one of files.
        assertTrue(helper.indexOf("send_ngch_syncmsg(group_identifier, peer_pubkey, gm)")
                   == helper.lastIndexOf("send_ngch_syncmsg(group_identifier, peer_pubkey, gm)"));
        assertTrue(helper.indexOf("send_ngch_syncfile(group_identifier, peer_pubkey, gm)")
                   == helper.lastIndexOf("send_ngch_syncfile(group_identifier, peer_pubkey, gm)"));
    }

    @Test
    public void reply_on_a_private_message_answers_privately_and_quotes_nothing_into_the_group() throws Exception
    {
        final String reply = read("ChatReplyPreviewController.java");
        final int start = reply.indexOf("static void startReplyToGroupMessage(");
        final int guard = reply.indexOf("if (message.private_message != 0)", start);
        final int quote = reply.indexOf("show(context, meta);", start);
        assertTrue(start >= 0 && guard > start && quote > guard);
        final String branch = reply.substring(guard, reply.indexOf('}', guard));
        assertTrue(branch.contains("answerPrivately(context, message);"));
        assertTrue(branch.contains("return;"));
        assertTrue(reply.contains("GroupMemberActions.showPrivateMessageDialog("));
    }

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + "src/main/java/com/zoffcc/applications/trifa/" + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

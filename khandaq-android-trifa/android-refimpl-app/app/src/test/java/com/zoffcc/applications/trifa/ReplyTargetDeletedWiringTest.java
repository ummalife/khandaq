package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #10, 27.11): every delete of a message remembers it for the replies to it, before the list lets the
 * row go, and every reply that is shown asks whether its message was deleted (ReplyTargetTombstones). Pinned on the
 * sources with their comments left out.
 */
public class ReplyTargetDeletedWiringTest
{
    private static final String JAVA = "com/zoffcc/applications/trifa/";
    /** A message row deleted by its id. */
    private static final Pattern DELETES_A_ROW = Pattern.compile(
            "\\.deleteFrom(Group)?Message\\(\\)\\s*\\.idEq\\(");

    @Test
    public void every_reply_shown_asks_whether_its_message_was_deleted()
    {
        final String[][] holders = {
                {"MessageListHolder_text_incoming_not_read.java", "parsedMessage", "1"},
                {"MessageListHolder_text_outgoing_not_read.java", "parsedMessage", "1"},
                {"MessageListHolder_text_outgoing_read.java", "parsedMessage", "1"},
                {"GroupMessageListHolder_text_incoming_not_read.java", "parsedGroup", "2"},
                {"GroupMessageListHolder_text_outgoing_read.java", "parsedGroup", "2"},
        };
        for (final String[] holder : holders)
        {
            final String code = bare(code(holder[0]));
            final int parses = count(code, "final" + (holder[1].equals("parsedGroup")
                                                       ? "GroupMentionHelper.ParsedGroupTextparsedGroup="
                                                       : "MessageReplyHelper.ParsedMessageparsedMessage="));
            assertEquals(holder[0], Integer.parseInt(holder[2]), parses);
            assertEquals(holder[0], parses, count(code, "HelperReply.markDeletedTarget(" + holder[1] + ".reply,m);"));
        }
    }

    @Test
    public void the_quote_of_a_deleted_message_says_so_and_leads_nowhere()
    {
        final String bind = bare(body(code("ChatBubbleUiHelper.java"), "static void bind_reply_quote("));
        assertTrue(bind.contains("replyMeta.targetDeleted?context.getString(R.string.chat_reply_deleted)"));
        final int deleted = bind.indexOf("if(replyMeta.targetDeleted){quoteBlock.setOnClickListener(null);");
        assertTrue(deleted >= 0);
        assertTrue(bind.indexOf("elseif(clickListener!=null&&!replyMeta.legacyQuote)") > deleted);
        assertTrue(bare(body(code("AirTranscript.java"), "static String spokenQuote("))
                           .contains("reply.targetDeleted?context.getString(R.string.chat_reply_deleted)"));
    }

    @Test
    public void a_delete_of_a_selection_remembers_the_row_before_the_list_lets_it_go()
    {
        final String main = bare(code("MainActivity.java"));
        for (final String[] task : new String[][]{
                {"classdelete_selected_messages_asynchtask", "finalMessagem_to_delete=(Message)",
                        "message_list_fragment.adapter.remove_item(m_to_delete);",
                        "orma.deleteFromMessage().idEq(message_id_to_delete).execute();"},
                {"classdelete_selected_group_messages_asynchtask", "finalGroupMessagem_to_delete=(GroupMessage)",
                        "group_message_list_fragment.adapter.remove_item(m_to_delete);",
                        "orma.deleteFromGroupMessage().idEq(message_id_to_delete).execute();"}})
        {
            final int start = main.indexOf(task[0]);
            assertTrue(task[0], start >= 0);
            final int read = main.indexOf(task[1], start);
            final int remember = main.indexOf("HelperReply.rememberDeletedReplyTarget(m_to_delete);", start);
            assertTrue(task[0], (read > start) && (remember > read));
            assertTrue(task[0], main.indexOf(task[2], start) > remember);
            assertTrue(task[0], main.indexOf(task[3], start) > remember);
        }
    }

    @Test
    public void a_delete_from_the_other_side_or_of_a_text_that_never_left_is_remembered_first()
    {
        assertBefore("HelperMessageDelete.java", "private static void deleteLocalFriendMessage(final Message m)",
                     "HelperReply.rememberDeletedReplyTarget(m);", "orma.deleteFromMessage().idEq(m.id)");
        assertBefore("HelperMessageDelete.java", "static void handleIncomingGroupDelete(",
                     "HelperReply.rememberDeletedReplyTarget(gm);", "orma.deleteFromGroupMessage().idEq(gm.id)");
        assertBefore("AirChatPanel.java", "HelperMessageDelete.sendOwnFriendDelete(m);",
                     "HelperReply.rememberDeletedReplyTarget(m);", "orma.deleteFromMessage().idEq(id)");
        assertBefore("HelperFiletransfer.java", "static void drop_outgoing_file_rows(final Message m)",
                     "HelperReply.rememberDeletedReplyTarget(m);", "orma.deleteFromMessage().idEq(m.id)");
    }

    /**
     * Where a message row is deleted by its id. A new place would leave the replies to what it deletes quoting it:
     * it remembers the row first (HelperReply.rememberDeletedReplyTarget), and is counted here. The split of a waiting
     * text takes back only the parts it has just added, which nothing has answered.
     */
    @Test
    public void message_rows_are_deleted_one_by_one_only_where_the_replies_are_told() throws IOException
    {
        final Map<String, Integer> found = new TreeMap<>();
        final File root = root();
        collect(root, new File(root, JAVA), found);
        final Map<String, Integer> expected = new TreeMap<>();
        expected.put("AirChatPanel.java", 1);
        expected.put("HelperFiletransfer.java", 1);
        expected.put("HelperMessageDelete.java", 2);
        expected.put("MainActivity.java", 2);
        expected.put("TrifaToxService.java", 1);
        assertEquals(expected, found);
    }

    @Test
    public void the_lists_redraw_their_replies_when_a_row_goes()
    {
        for (final String adapter : new String[]{"MessagelistAdapter.java", "GroupMessagelistAdapter.java"})
        {
            final String code = code(adapter);
            final String remove = bare(body(code, "synchronized public void remove_item("));
            final int removed = remove.indexOf("this.notifyItemRemoved(pos);");
            assertTrue(adapter, removed >= 0);
            assertTrue(adapter, remove.indexOf("notify_reply_rows();") > removed);
            final String redraw = bare(body(code, "private void notify_reply_rows()"));
            assertTrue(adapter, redraw.contains("row.text.startsWith(MessageReplyHelper.HEADER_PREFIX)"));
            assertTrue(adapter, redraw.contains("this.notifyItemChanged(i);"));
        }
    }

    @Test
    public void another_profile_and_a_chat_that_goes_take_their_deletes_with_them()
    {
        final String service = bare(code("TrifaToxService.java"));
        final int groups = service.indexOf("HelperMessageDelete.forgetGroupDeletesRead();");
        assertTrue(groups >= 0);
        assertEquals(groups + "HelperMessageDelete.forgetGroupDeletesRead();".length(),
                     service.indexOf("HelperReply.REPLY_TARGETS.forgetRead();"));
        assertTrue(bare(code("HelperGroup.java")).contains(
                "HelperReply.forgetDeletedReplyTargets(ReplyTargetTombstones.group(group_identifier));"));
        assertBefore("HelperFriend.java", "static void delete_friend_all_messages(final String friend_pubkey)",
                     "orma.deleteFromMessage().tox_friendpubkeyEq(friend_pubkey).execute();",
                     "HelperReply.forgetDeletedReplyTargets(ReplyTargetTombstones.direct(friend_pubkey));");
        // The flag of a reply stays on this device: encode writes the header and the quote only.
        assertFalse(bare(body(code("MessageReplyHelper.java"), "static String encode(")).contains("targetDeleted"));
    }

    private static void assertBefore(final String file, final String after, final String first, final String then)
    {
        final String code = bare(code(file));
        final int start = code.indexOf(bare(after));
        assertTrue(file + ": " + after, start >= 0);
        final int at = code.indexOf(bare(first), start);
        assertTrue(file + ": " + first, at > start);
        assertTrue(file + ": " + then, code.indexOf(bare(then), at) > at);
    }

    private static void collect(final File root, final File dir, final Map<String, Integer> found) throws IOException
    {
        final File[] files = dir.listFiles();
        if (files == null)
        {
            return;
        }
        for (final File file : files)
        {
            if (file.isDirectory())
            {
                collect(root, file, found);
                continue;
            }
            if (!file.getName().endsWith(".java"))
            {
                continue;
            }
            final Matcher matcher = DELETES_A_ROW.matcher(
                    AntispamOnRequestTest.code(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)));
            int n = 0;
            while (matcher.find())
            {
                n++;
            }
            if (n > 0)
            {
                found.put(file.getName(), n);
            }
        }
    }

    private static int count(final String text, final String part)
    {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
        {
            n++;
        }
        return n;
    }

    private static String code(final String name)
    {
        try
        {
            return AntispamOnRequestTest.code(new String(Files.readAllBytes(new File(root(), JAVA + name).toPath()),
                                                         StandardCharsets.UTF_8));
        }
        catch (final IOException e)
        {
            throw new AssertionError(name, e);
        }
    }

    private static File root()
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final File dir = new File(root);
            if (new File(dir, JAVA + "HelperReply.java").isFile())
            {
                return dir;
            }
        }
        throw new AssertionError("src/main/java not found from " + new File("").getAbsolutePath());
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
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.58, QA live 18.11): a reply to your own message carried "You" as the author in the header
 * that travels with it, so every receiver showed "You" over a message that was not theirs (iOS showed
 * "You / a1-from-android" over a reply from the Android user to their own message). As iOS since 0.2.56,
 * the header names you as the others in the chat know you, and only the bar above your own field says
 * "You". The naming is pure and tested here; the start of a reply needs a Context and toxcore, so its
 * call sites are read from the source (comments left out).
 */
public class ChatReplyOwnAuthorTest
{
    private static final String YOU = "You";
    private static final String UNKNOWN = "Unknown";

    private static MessageReplyHelper.ReplyMeta meta()
    {
        final MessageReplyHelper.ReplyMeta meta = new MessageReplyHelper.ReplyMeta();
        meta.localMessageId = 7L;
        meta.senderPubkeyTail = "ABCDEF12";
        meta.sortTimestampMs = 1731370000000L;
        meta.previewText = "a1-from-android";
        return meta;
    }

    private static MessageReplyHelper.ReplyMeta own(final String... knownAs)
    {
        final MessageReplyHelper.ReplyMeta meta = meta();
        ChatReplyPreviewController.nameOwnAuthor(meta, YOU, UNKNOWN, knownAs);
        return meta;
    }

    @Test
    public void a_reply_to_our_own_message_carries_our_name_on_the_wire()
    {
        final MessageReplyHelper.ReplyMeta meta = own("QA Fresh 057", "Profile name");
        assertEquals("QA Fresh 057", meta.senderName);

        final String wire = MessageReplyHelper.encode(meta, "the answer");
        assertTrue(wire, wire.startsWith("[KQ|7|ABCDEF12|1731370000000|QA Fresh 057]\na1-from-android\n[KQ/end]\n"));
        assertFalse(wire, wire.contains(YOU));

        // What a receiver reads: our name, and nothing of the bar's word.
        final MessageReplyHelper.ParsedMessage parsed = MessageReplyHelper.parse(wire);
        assertNotNull(parsed.reply);
        assertEquals("QA Fresh 057", parsed.reply.senderName);
        assertNull(parsed.reply.localSenderName);
        assertEquals("the answer", parsed.bodyText);
    }

    @Test
    public void only_the_bar_above_the_field_says_you()
    {
        final MessageReplyHelper.ReplyMeta meta = own("QA Fresh 057");
        assertEquals(YOU, meta.localSenderName);
        assertEquals(YOU, ChatReplyPreviewController.barName(meta));
    }

    @Test
    public void the_group_name_comes_first_then_the_profile_name()
    {
        // In a group: the name we have in it, else our profile name; trimmed, as the header keeps it.
        assertEquals("Group name", own("  Group name ", "Profile name").senderName);
        assertEquals("Profile name", own(null, "Profile name").senderName);
        assertEquals("Profile name", own("", "Profile name").senderName);
        assertEquals("Profile name", own("   ", "Profile name").senderName);
        // "-1" is what the old name lookups give for none.
        assertEquals("Profile name", own("-1", "Profile name").senderName);
    }

    @Test
    public void with_no_name_at_all_it_is_the_unknown_sender_never_you()
    {
        for (final MessageReplyHelper.ReplyMeta meta : new MessageReplyHelper.ReplyMeta[]{
                own(), own((String) null), own("", "  "), own(null, "-1"), own((String[]) null)})
        {
            assertEquals(UNKNOWN, meta.senderName);
            assertEquals(YOU, ChatReplyPreviewController.barName(meta));
            assertFalse(MessageReplyHelper.encode(meta, "x").contains(YOU));
        }
    }

    @Test
    public void the_bar_over_our_own_message_says_in_reply_to_yourself()
    {
        // KHANDAQ (0.2.59): "In reply to" + "You" put "You" in the wrong grammatical case in Russian; the bar has
        // its own words for a message of ours (chat_reply_header_self).
        assertTrue(ChatReplyPreviewController.barRepliesToSelf(own("QA Fresh 057")));
        assertTrue(ChatReplyPreviewController.barRepliesToSelf(own()));
        final MessageReplyHelper.ReplyMeta other = meta();
        other.senderName = "Amina";
        assertFalse(ChatReplyPreviewController.barRepliesToSelf(other));
        other.localSenderName = "";
        assertFalse(ChatReplyPreviewController.barRepliesToSelf(other));
    }

    @Test
    public void a_reply_to_someone_else_is_as_before()
    {
        final MessageReplyHelper.ReplyMeta meta = meta();
        meta.senderName = "Amina";
        assertNull(meta.localSenderName);
        assertEquals("Amina", ChatReplyPreviewController.barName(meta));
        assertTrue(MessageReplyHelper.encode(meta, "x").startsWith("[KQ|7|ABCDEF12|1731370000000|Amina]\n"));
    }

    @Test
    public void the_replies_name_us_by_name_and_the_bar_says_you()
    {
        final String controller = AntispamOnRequestTest.code(read("ChatReplyPreviewController.java"));
        assertFalse(bare(controller).contains(bare("meta.senderName = context.getString(R.string.chat_reply_self_name)")));

        final String direct = bare(body(controller, "static void startReplyToMessage("));
        assertTrue(direct.contains(bare("nameOwnAuthor(meta, context.getString(R.string.chat_reply_self_name),"
                                        + " context.getString(R.string.chat_reply_unknown_sender), TRIFAGlobals.global_my_name);")));

        final String group = bare(body(controller, "static void startReplyToGroupMessage("));
        assertTrue(group.contains(bare("nameOwnAuthor(meta, context.getString(R.string.chat_reply_self_name),"
                                       + " context.getString(R.string.chat_reply_unknown_sender),"
                                       + " groupSelfName(message.group_identifier), TRIFAGlobals.global_my_name);")));
        // The sender pubkey tail is the row's, as before.
        assertTrue(group.contains(bare("meta.senderPubkeyTail = MessageReplyHelper.pubkeyTail(message.tox_group_peer_pubkey);")));
        assertTrue(direct.contains(bare("meta.senderPubkeyTail = MessageReplyHelper.pubkeyTail(message.tox_friendpubkey);")));

        final String show = bare(body(controller, "private static void show("));
        assertTrue(show.contains(bare("context.getString(R.string.chat_reply_header, barName(meta))")));
        assertTrue(show.contains(bare("barRepliesToSelf(meta) ? context.getString(R.string.chat_reply_header_self)")));

        // The bar's word never reaches the wire.
        final String encode = body(AntispamOnRequestTest.code(read("MessageReplyHelper.java")), "static String encode(");
        assertFalse(encode.contains("localSenderName"));
    }

    // ---- sources ----

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
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final File f = new File(root, "com/zoffcc/applications/trifa/" + name);
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

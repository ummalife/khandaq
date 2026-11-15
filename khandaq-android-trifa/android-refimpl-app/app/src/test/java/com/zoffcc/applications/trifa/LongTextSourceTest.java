package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): a long text is never cut or refused for its length on its way out; it goes in parts.
 * The send paths need toxcore and the database, so these pin them in the source, as AirSheetKeyboardTest
 * does; the cutting itself is tested in GroupTextPartsTest and MessageSplitterTest.
 */
public class LongTextSourceTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";

    @Test
    public void a_long_group_text_goes_in_parts_on_one_sender_thread() throws Exception
    {
        final String activity = read("GroupMessageListActivity.java");
        final String send = method(activity, "synchronized public void send_message_onclick(View view)");
        assertFalse(send.contains("bytesOver"));
        assertFalse(send.contains("air_compose_bytes_over_group"));
        assertTrue(send.contains("AirGroupModel.textParts(msg, normalized)"));
        assertTrue(send.contains("GROUP_TEXT_SENDER.execute("));
        assertTrue(activity.contains("GROUP_TEXT_SENDER = Executors.newSingleThreadExecutor("));
        // Waiting for the group, a part takes the parts after it along, so they still go in order.
        final String finish = method(activity, "private void finish_group_text_send(");
        assertTrue(finish.contains("for (int i = index; i < parts.size(); i++)"));
        assertFalse(read("AirGroupPanel.java").contains("air_compose_bytes_over_group"));
    }

    @Test
    public void a_group_caption_goes_in_parts_not_cut() throws Exception
    {
        final String caption = method(read("MediaSendPreviewHelper.java"), "static void sendGroupCaption(");
        assertTrue(caption.contains("AirGroupModel.textParts(trimmed, trimmed)"));
        assertFalse(caption.contains("Math.min(MainActivity.tox_max_message_length()"));
        assertFalse(caption.contains("trimmed.substring(0,"));
    }

    @Test
    public void text_shared_into_a_group_fills_its_field() throws Exception
    {
        final String activity = read("GroupMessageListActivity.java");
        final int shared = activity.indexOf("intent.getStringExtra(\"fillouttext\")");
        final int draft = activity.indexOf("ChatDraftHelper.load_group_draft(group_id)");
        assertTrue(shared > 0 && draft > shared);
        assertTrue(activity.indexOf("if (!restored_shared_text && (group_id != null)") > shared);
    }

    @Test
    public void nothing_cuts_a_direct_text_to_one_message() throws Exception
    {
        final String wrapper = method(read("HelperGeneric.java"),
                                      "public static MainActivity.send_message_result tox_friend_send_message_wrapper(");
        assertFalse(wrapper.contains("trim_to_utf8_length_bytes"));
        assertTrue(wrapper.contains("if (MessageChunker.needsChunks(message))"));
        // Saved Messages stay on the device: no Tox limit.
        assertFalse(method(read("FavoritesChatHelper.java"), "static long sendTextMessage(")
                            .contains("trim_to_utf8_length_bytes"));
        // A waiting text that cannot go whole is split before the queue is sent.
        final String resend = method(read("TrifaToxService.java"), "static void resend_unsent_text_messages()");
        final int split = resend.indexOf("split_waiting_texts_that_cannot_go_whole(unsent)");
        assertTrue(split > 0 && resend.indexOf("tox_friend_send_message_wrapper(") > split);
        // A text over what chunks carry goes in parts when it is new too.
        assertTrue(read("MessageListActivity.java").contains("&& MessageChunker.canCarry(fullText))"));
    }

    @Test
    public void a_long_direct_text_wakes_a_friend_who_is_offline_once() throws Exception
    {
        // Each part asked the relay twice and again at every resend step; past ~60 parts the relay said 429
        // and the phone held back its wakes to that friend, a call's too (MessageSplitter.partWakes).
        // The whole activity, not one method: the send loop may move (the media caption sends through it too).
        final String send = flat(read("MessageListActivity.java"));
        assertTrue(send.contains("final boolean wakes = MessageSplitter.partWakes(i, parts.size());"));
        assertTrue(send.contains("m.sent_push = wakes ? 0 : TrifaToxService.PUSH_WAKE_MAX_ATTEMPTS;"));
        assertTrue(send.contains("(m.sent_timestamp / 1000), wakes);"));
        assertTrue(send.contains("if (wakes && !HelperFriend.is_dm_message_send_success(res))"));
        // The wrapper wakes only when it is asked to; a message of its own still asks.
        final String helper = read("HelperGeneric.java");
        assertTrue(method(helper, "public static MainActivity.send_message_result tox_friend_send_message_wrapper(")
                           .contains("need_call_push_url = may_wake;"));
        assertTrue(flat(helper).contains("return tox_friend_send_message_wrapper(friend_pubkey, a_TOX_MESSAGE_TYPE, message,"
                                         + " timestamp_unixtime_seconds, true);"));
        // Rows that wait in the queue (parts written before Tox ran, a text split there) wake a friend once
        // a round.
        final String resend = flat(method(read("TrifaToxService.java"), "static void resend_unsent_text_messages()"));
        assertTrue(resend.contains("if (woken_this_round.add(m_unsent.tox_friendpubkey)) {"
                                   + " friend_call_push_url(m_unsent.tox_friendpubkey, m_unsent.sent_timestamp); }"));
        // ... and nothing else in that loop wakes anyone.
        assertEquals(1, resend.split("friend_call_push_url\\(", -1).length - 1);
    }

    /** The source with each run of white space as one space, so a check does not hang on line breaks. */
    private static String flat(final String source)
    {
        return source.replaceAll("\\s+", " ");
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

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + JAVA + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

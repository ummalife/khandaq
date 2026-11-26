package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.junit.Test;
import org.khandaq.messenger.R;

import static com.zoffcc.applications.trifa.HelperMessageEdit.EDIT_WINDOW_MS;
import static com.zoffcc.applications.trifa.HelperMessageEdit.directEditBlockReason;
import static com.zoffcc.applications.trifa.HelperMessageEdit.groupEditBlockReason;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA 02.10, "not everyone can edit messages", Galaxy A55) — why a given own text cannot be
 * edited. The Edit action used to vanish silently in each of these cases, which read as the feature being
 * absent on that phone; it is now always offered for an own text and the tap names the reason.
 */
public class ChatEditBlockReasonTest
{
    private static final long NOW = 1_790_000_000_000L;
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static Message own(final String text, final String hash, final boolean read, final long sentAgoMs)
    {
        final Message m = new Message();
        m.direction = 1;
        m.tox_friendpubkey = "A1B2";
        m.text = text;
        m.msg_idv3_hash = hash;
        m.read = read;
        m.sent_timestamp = NOW - sentAgoMs;
        return m;
    }

    private static GroupMessage ownGroup(final String messageIdTox, final long sentAgoMs)
    {
        final GroupMessage gm = new GroupMessage();
        gm.direction = 1;
        gm.text = "hello group";
        gm.message_id_tox = messageIdTox;
        gm.sent_timestamp = NOW - sentAgoMs;
        return gm;
    }

    @Test
    public void a_delivered_msgv3_text_inside_the_window_is_editable()
    {
        assertEquals(0, directEditBlockReason(own("hello", HASH, true, 60_000), NOW));
    }

    @Test
    public void a_text_still_queued_for_an_offline_contact_waits_for_delivery()
    {
        // tox_friend_send_message_wrapper hands out the msgV3 hash only when it actually sends
        assertEquals(R.string.chat_edit_blocked_not_delivered, directEditBlockReason(own("hello", "", false, 60_000), NOW));
    }

    @Test
    public void a_text_delivered_as_a_plain_tox_message_cannot_be_addressed()
    {
        assertEquals(R.string.chat_edit_blocked_unsupported, directEditBlockReason(own("hello", "", true, 60_000), NOW));
    }

    @Test
    public void the_48_hour_window_is_enforced()
    {
        assertEquals(R.string.chat_edit_blocked_too_old,
                     directEditBlockReason(own("hello", HASH, true, EDIT_WINDOW_MS), NOW));
        assertEquals(0, directEditBlockReason(own("hello", HASH, true, EDIT_WINDOW_MS - 1), NOW));
    }

    @Test
    public void an_old_message_still_in_the_queue_is_too_old_not_waiting()
    {
        // "after delivery" would be a promise that never comes true once the window has passed
        assertEquals(R.string.chat_edit_blocked_too_old, directEditBlockReason(own("hello", "", false, EDIT_WINDOW_MS), NOW));
        assertEquals(R.string.chat_edit_blocked_too_old, groupEditBlockReason(ownGroup("00000000", EDIT_WINDOW_MS), NOW));
    }

    @Test
    public void call_log_lines_are_not_messages()
    {
        // HelperCall.logCallEvent writes "Missed call" and friends as our own text rows; Edit is not
        // offered on them at all (isOwnSingleTextSelection), instead of blaming the contact's app.
        final Message log = own("Missed call", "", true, 60_000);
        log.message_id = -1;
        log.resend_count = HelperCall.LOCAL_CALL_LOG_RESEND_COUNT;
        assertEquals(true, HelperMessageEdit.isLocalCallLogRow(log));

        final Message queued = own("hello", "", false, 60_000);
        queued.message_id = -1;
        queued.resend_count = 0;
        assertEquals(false, HelperMessageEdit.isLocalCallLogRow(queued));
    }

    @Test
    public void a_text_that_only_chunks_can_carry_is_too_long_to_edit_even_with_its_hash()
    {
        // chunked texts now carry a msgV3 hash; an edit still carries at most MAX_EDIT_TEXT_BYTES
        final StringBuilder longText = new StringBuilder();
        while (longText.length() <= ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH)
        {
            longText.append("long text ");
        }
        assertEquals(R.string.chat_edit_too_long, directEditBlockReason(own(longText.toString(), HASH, true, 60_000), NOW));
    }

    @Test
    public void a_reply_is_not_editable_whatever_else_holds()
    {
        final String reply = "[KQ|17|C397E117|1790954870517|Khandaq Demo]quoted text[KQ/end]my answer";
        assertEquals(R.string.chat_edit_blocked_reply, directEditBlockReason(own(reply, HASH, true, 60_000), NOW));
    }

    @Test
    public void favorites_is_a_local_edit_and_always_allowed()
    {
        final Message m = own("note", "", false, EDIT_WINDOW_MS * 10);
        m.tox_friendpubkey = FavoritesChatHelper.CHAT_ID;
        assertEquals(0, directEditBlockReason(m, NOW));
    }

    @Test
    public void a_long_correction_of_a_note_is_saved_a_long_correction_to_a_contact_is_refused()
    {
        // KHANDAQ (Khandaq Testers 8253): a note stays on this device and takes a text of any length, as a new
        // note does; a correction to a contact goes out in one packet of at most MAX_EDIT_TEXT_BYTES.
        final String limit = repeat("a", HelperMessageEdit.MAX_EDIT_TEXT_BYTES);
        final String over = limit + "a";
        final String longNote = repeat("a long note \u2713 ", 2_000); // 16 bytes each in UTF-8: 32,000 bytes
        assertFalse(HelperMessageEdit.isDirectEditTooLong(FavoritesChatHelper.CHAT_ID, over));
        assertFalse(HelperMessageEdit.isDirectEditTooLong(FavoritesChatHelper.CHAT_ID, longNote));
        assertFalse(HelperMessageEdit.isDirectEditTooLong("A1B2", limit));
        assertTrue(HelperMessageEdit.isDirectEditTooLong("A1B2", over));
        assertTrue(HelperMessageEdit.isDirectEditTooLong("A1B2", longNote));
        // The limit counts bytes, not characters: 650 two-byte letters fit, one more does not.
        final int twoByteLetters = HelperMessageEdit.MAX_EDIT_TEXT_BYTES / 2;
        assertFalse(HelperMessageEdit.isDirectEditTooLong("A1B2", repeat("\u00fc", twoByteLetters)));
        assertTrue(HelperMessageEdit.isDirectEditTooLong("A1B2", repeat("\u00fc", twoByteLetters + 1)));
        assertFalse(HelperMessageEdit.isDirectEditTooLong("A1B2", null));
    }

    @Test
    public void the_chat_screen_asks_the_chat_before_it_refuses_a_long_correction() throws Exception
    {
        // The send path of a 1:1 chat and Saved Messages holds the edit to the limit only through the rule above.
        final String activity = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(
                "src/main/java/com/zoffcc/applications/trifa/MessageListActivity.java")),
                                           java.nio.charset.StandardCharsets.UTF_8);
        final int from = activity.indexOf("public void send_text_message(final String friend_pubkey, "
                                          + "final String message, final boolean rawStandalone)");
        final int to = activity.indexOf("ChatReplyPreviewController.consumeEditDirect();", from);
        assertTrue((from > 0) && (to > from));
        final String editPath = activity.substring(from, to);
        assertTrue(editPath.contains("HelperMessageEdit.isDirectEditTooLong(friend_pubkey, newBody)"));
        assertFalse(editPath.contains("MAX_EDIT_TEXT_BYTES"));
    }

    private static String repeat(final String s, final int times)
    {
        final StringBuilder b = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++)
        {
            b.append(s);
        }
        return b.toString();
    }

    @Test
    public void group_texts_wait_for_their_tox_message_id()
    {
        assertEquals(R.string.chat_edit_blocked_not_delivered, groupEditBlockReason(ownGroup("00000000", 60_000), NOW));
        assertEquals(R.string.chat_edit_blocked_not_delivered, groupEditBlockReason(ownGroup(null, 60_000), NOW));
        assertEquals(0, groupEditBlockReason(ownGroup("1a2b3c4d", 60_000), NOW));
        assertEquals(R.string.chat_edit_blocked_too_old, groupEditBlockReason(ownGroup("1a2b3c4d", EDIT_WINDOW_MS), NOW));
    }
}

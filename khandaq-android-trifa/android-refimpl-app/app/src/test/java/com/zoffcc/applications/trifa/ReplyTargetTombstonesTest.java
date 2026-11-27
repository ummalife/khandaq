package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #10, 27.11): a deleted message stayed readable in the quote of every reply to it, in a group and in
 * Saved Messages: a message was answered, then deleted, and the reply still showed its words. A
 * reply carries the words it quotes; what was deleted here is now remembered by the marks a reply header names its
 * message by, the row id and the date, and the quote of such a reply says the message was deleted.
 */
public class ReplyTargetTombstonesTest
{
    private static final String CHAT = "g:" + "ab12".repeat(16);
    private static final long T = 1_764_244_800_000L;

    /** The profile's rows: a store made again over the same map is the app after a restart. */
    private static final class ProfileRows implements GroupMessageTombstones.Rows
    {
        final Map<String, String> rows = new HashMap<>();
        boolean open = true;
        int writes = 0;

        @Override
        public boolean ready()
        {
            return open;
        }

        @Override
        public String get(final String key)
        {
            return rows.get(key);
        }

        @Override
        public void put(final String key, final String value)
        {
            writes++;
            rows.put(key, value);
        }

        @Override
        public void remove(final String key)
        {
            rows.remove(key);
        }
    }

    private static MessageReplyHelper.ReplyMeta reply(final long rowId, final long dateMs)
    {
        final MessageReplyHelper.ReplyMeta meta = new MessageReplyHelper.ReplyMeta();
        meta.localMessageId = rowId;
        meta.sortTimestampMs = dateMs;
        meta.senderPubkeyTail = "AB12AB12";
        meta.senderName = "Tester";
        meta.previewText = "Lunch at one?";
        return meta;
    }

    private static List<ReplyTargetTombstones.Entry> book(final long... rowAndDate)
    {
        final List<ReplyTargetTombstones.Entry> book = new ArrayList<>();
        for (int i = 0; i < rowAndDate.length; i += 2)
        {
            book.add(new ReplyTargetTombstones.Entry(rowAndDate[i], rowAndDate[i + 1]));
        }
        return book;
    }

    /** The messages still in the chat, {row id, date} each. */
    private static ReplyTargetTombstones.Live live(final long[]... rows)
    {
        return date -> new ArrayList<>(Arrays.asList(rows));
    }

    private static final ReplyTargetTombstones.Live NOT_ASKED = date ->
    {
        throw new AssertionError("the messages of the chat are read only when a delete is near the date");
    };

    @Test
    public void a_reply_written_here_says_its_message_was_deleted()
    {
        // The message was row 41, the reply to it is row 42 a second later.
        final List<ReplyTargetTombstones.Entry> deleted = book(41, T);
        assertTrue(ReplyTargetTombstones.isDeleted(reply(41, T), deleted, 42, live(new long[]{42, T + 1000})));
        // By its row id, even when another message has the very same date.
        assertTrue(ReplyTargetTombstones.isDeleted(reply(41, T), deleted, 42, live(new long[]{40, T})));
    }

    @Test
    public void a_message_still_here_with_the_row_id_is_the_one_quoted()
    {
        assertFalse(ReplyTargetTombstones.isDeleted(reply(40, T), book(41, T), 42, live(new long[]{40, T + 900})));
    }

    @Test
    public void a_reply_from_another_device_names_its_message_by_the_date_and_the_nearest_wins()
    {
        // Another device's row ids mean nothing here: 7 is no row of ours. Its date of a message is ours give or take.
        final MessageReplyHelper.ReplyMeta theirs = reply(7, T + 300);
        // Deleted at T, another message 2 s later: the reply answers the deleted one.
        assertTrue(ReplyTargetTombstones.isDeleted(theirs, book(41, T), 50, live(new long[]{43, T + 2000})));
        // Deleted at T, the reply's date is next to a message still here: it answers that one.
        final MessageReplyHelper.ReplyMeta nextOne = reply(7, T + 2100);
        assertFalse(ReplyTargetTombstones.isDeleted(nextOne, book(41, T), 50, live(new long[]{43, T + 2000})));
        // As far from both: the quote stays as it came.
        assertFalse(ReplyTargetTombstones.isDeleted(reply(7, T + 1000), book(41, T), 50,
                                                    live(new long[]{43, T + 2000})));
    }

    @Test
    public void the_reply_itself_and_a_row_being_deleted_are_no_candidates()
    {
        // A reply a moment after its message lies nearer the date than the deleted message seen from elsewhere.
        assertTrue(ReplyTargetTombstones.isDeleted(reply(7, T + 1500), book(41, T), 42,
                                                   live(new long[]{42, T + 1600})));
        // The list redraws the replies before the delete takes the row away.
        assertTrue(ReplyTargetTombstones.isDeleted(reply(41, T), book(41, T), 42, live(new long[]{41, T})));
    }

    @Test
    public void with_the_chat_unknown_only_the_row_id_tells()
    {
        final ReplyTargetTombstones.Live unknown = date -> null;
        assertTrue(ReplyTargetTombstones.isDeleted(reply(41, T), book(41, T), 42, unknown));
        assertFalse(ReplyTargetTombstones.isDeleted(reply(7, T), book(41, T), 42, unknown));
    }

    @Test
    public void a_quote_without_a_date_or_far_from_every_delete_stays()
    {
        assertFalse(ReplyTargetTombstones.isDeleted(reply(41, 0L), book(41, T), 42, NOT_ASKED));
        final MessageReplyHelper.ReplyMeta legacy = reply(41, T);
        legacy.legacyQuote = true;
        assertFalse(ReplyTargetTombstones.isDeleted(legacy, book(41, T), 42, NOT_ASKED));
        assertFalse(ReplyTargetTombstones.isDeleted(reply(41, T + ReplyTargetTombstones.WINDOW_MS + 1), book(41, T), 42,
                                                    NOT_ASKED));
        assertFalse(ReplyTargetTombstones.isDeleted(null, book(41, T), 42, NOT_ASKED));
        assertFalse(ReplyTargetTombstones.isDeleted(reply(41, T), Collections.emptyList(), 42, NOT_ASKED));
    }

    @Test
    public void a_delete_is_kept_in_the_profiles_rows_and_read_again_after_a_restart()
    {
        final ProfileRows rows = new ProfileRows();
        final ReplyTargetTombstones.Store store = new ReplyTargetTombstones.Store(rows);
        assertTrue(store.record(CHAT, 41, T));
        assertEquals(1, rows.writes);
        // The same delete again writes nothing.
        assertTrue(store.record(CHAT, 41, T));
        assertEquals(1, rows.writes);

        final ReplyTargetTombstones.Store again = new ReplyTargetTombstones.Store(rows);
        assertTrue(again.isDeleted(CHAT, reply(41, T), 42, live()));
        // Another chat has deletes of its own.
        assertFalse(again.isDeleted("g:" + "cd34".repeat(16), reply(41, T), 42, NOT_ASKED));
    }

    @Test
    public void a_store_asked_before_the_profile_is_open_keeps_nothing_and_says_nothing()
    {
        final ProfileRows rows = new ProfileRows();
        rows.open = false;
        final ReplyTargetTombstones.Store store = new ReplyTargetTombstones.Store(rows);
        assertFalse(store.record(CHAT, 41, T));
        assertFalse(store.isDeleted(CHAT, reply(41, T), 42, NOT_ASKED));
        assertEquals(0, rows.writes);
        // Once it is open, the profile's rows are read: nothing was cached from before.
        rows.open = true;
        assertTrue(store.record(CHAT, 41, T));
        assertTrue(store.isDeleted(CHAT, reply(41, T), 42, live()));
    }

    @Test
    public void a_chat_keeps_its_newest_deletes()
    {
        final ProfileRows rows = new ProfileRows();
        final ReplyTargetTombstones.Store store = new ReplyTargetTombstones.Store(rows);
        for (int i = 1; i <= ReplyTargetTombstones.MAX_PER_CHAT + 3; i++)
        {
            store.record(CHAT, i, T + i * 60_000L);
        }
        final List<ReplyTargetTombstones.Entry> kept = ReplyTargetTombstones.decode(
                rows.get(ReplyTargetTombstones.rowKey(CHAT)));
        assertEquals(ReplyTargetTombstones.MAX_PER_CHAT, kept.size());
        assertEquals(4, kept.get(0).rowId);
        assertEquals(ReplyTargetTombstones.MAX_PER_CHAT + 3, kept.get(kept.size() - 1).rowId);
        assertFalse(store.isDeleted(CHAT, reply(1, T + 60_000L), 9999, live()));
        assertTrue(store.isDeleted(CHAT, reply(4, T + 4 * 60_000L), 9999, live()));
    }

    @Test
    public void a_chat_that_goes_takes_its_deletes_and_another_profile_reads_its_own()
    {
        final ProfileRows rows = new ProfileRows();
        final ReplyTargetTombstones.Store store = new ReplyTargetTombstones.Store(rows);
        store.record(CHAT, 41, T);
        store.forget(CHAT);
        assertNull(rows.get(ReplyTargetTombstones.rowKey(CHAT)));
        assertFalse(store.isDeleted(CHAT, reply(41, T), 42, NOT_ASKED));

        store.record(CHAT, 41, T);
        rows.rows.clear(); // the next profile's rows
        store.forgetRead();
        assertFalse(store.isDeleted(CHAT, reply(41, T), 42, NOT_ASKED));
    }

    @Test
    public void the_row_reads_back_what_was_written_and_nothing_it_cannot_read()
    {
        final List<ReplyTargetTombstones.Entry> book = book(41, T, 42, T + 5);
        final List<ReplyTargetTombstones.Entry> read = ReplyTargetTombstones.decode(ReplyTargetTombstones.encode(book));
        assertEquals(2, read.size());
        assertEquals(42, read.get(1).rowId);
        assertEquals(T + 5, read.get(1).dateMs);

        assertEquals(1, ReplyTargetTombstones.decode("kq-reply-deleted-1\n41 " + T + "\nx y\n0 5\n7\n").size());
        assertTrue(ReplyTargetTombstones.decode("kq-reply-deleted-9\n41 " + T).isEmpty());
        assertTrue(ReplyTargetTombstones.decode(null).isEmpty());
        assertTrue(ReplyTargetTombstones.decode("").isEmpty());
    }

    @Test
    public void a_chat_is_named_one_way_whatever_case_its_key_comes_in()
    {
        assertEquals(ReplyTargetTombstones.direct("ab12cd34"), ReplyTargetTombstones.direct(" AB12CD34 "));
        assertEquals(ReplyTargetTombstones.group("AB12"), ReplyTargetTombstones.group("ab12"));
        assertFalse(ReplyTargetTombstones.direct("ab12").equals(ReplyTargetTombstones.group("ab12")));
        assertNull(ReplyTargetTombstones.direct(null));
        assertNull(ReplyTargetTombstones.group("  "));
        assertFalse(new ReplyTargetTombstones.Store(new ProfileRows()).record(null, 41, T));
    }
}

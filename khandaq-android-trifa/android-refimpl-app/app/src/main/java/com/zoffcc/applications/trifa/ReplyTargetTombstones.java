package com.zoffcc.applications.trifa;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * KHANDAQ (inbox #10, 27.11): the messages deleted on this device that a reply may quote. A reply carries the words it
 * quotes in its own text (MessageReplyHelper), so a deleted message stayed readable in every reply to it, in a group
 * and in Saved Messages alike. Each chat keeps the row ids and dates of its deleted messages, the marks a reply header
 * names the message it answers by, and a reply that answers one of them says that the message was deleted. Kept in
 * the profile's g_opts rows, one per chat (HelperReply).
 */
final class ReplyTargetTombstones
{
    /** How far apart two devices may date one message: the slack findPositionForReply allows. */
    static final long WINDOW_MS = 5000L;
    /** The newest deletes a chat keeps; a reply to an older one shows its quote again. */
    static final int MAX_PER_CHAT = 500;
    private static final String ROW_PREFIX = "kq_reply_deleted:";
    private static final String FORMAT_LINE = "kq-reply-deleted-1";

    private ReplyTargetTombstones()
    {
    }

    /** A message deleted here: its row id and its date, as a reply written here names it. */
    static final class Entry
    {
        final long rowId;
        final long dateMs;

        Entry(final long rowId, final long dateMs)
        {
            this.rowId = rowId;
            this.dateMs = dateMs;
        }
    }

    /** The messages of a chat that are still here. */
    interface Live
    {
        /** The chat's messages dated within WINDOW_MS of dateMs, each as {row id, date in ms}; null when unknown. */
        @Nullable
        List<long[]> near(long dateMs);
    }

    /** The chat with one person, or Saved Messages, a message of which is kept with this key. */
    @Nullable
    static String direct(@Nullable final String friendPubkey)
    {
        final String key = (friendPubkey == null) ? "" : friendPubkey.trim();
        return key.isEmpty() ? null : "f:" + key.toUpperCase(Locale.ROOT);
    }

    @Nullable
    static String group(@Nullable final String groupIdentifier)
    {
        final String id = (groupIdentifier == null) ? "" : groupIdentifier.trim();
        return id.isEmpty() ? null : "g:" + id.toLowerCase(Locale.ROOT);
    }

    static String rowKey(final String chatKey)
    {
        return ROW_PREFIX + chatKey;
    }

    static String encode(final List<Entry> book)
    {
        final StringBuilder out = new StringBuilder(FORMAT_LINE);
        for (final Entry entry : book)
        {
            out.append('\n').append(entry.rowId).append(' ').append(entry.dateMs);
        }
        return out.toString();
    }

    /** The book a row holds; empty for none, or for a row in a form this build does not read. */
    static List<Entry> decode(@Nullable final String stored)
    {
        final List<Entry> book = new ArrayList<>();
        if ((stored == null) || stored.isEmpty())
        {
            return book;
        }
        final String[] lines = stored.split("\n");
        if (!FORMAT_LINE.equals(lines[0].trim()))
        {
            return book;
        }
        for (int i = 1; i < lines.length; i++)
        {
            final String[] field = lines[i].trim().split(" ");
            if (field.length != 2)
            {
                continue;
            }
            try
            {
                final long rowId = Long.parseLong(field[0]);
                final long dateMs = Long.parseLong(field[1]);
                if ((rowId > 0L) && (dateMs > 0L))
                {
                    book.add(new Entry(rowId, dateMs));
                }
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        while (book.size() > MAX_PER_CHAT)
        {
            book.remove(0);
        }
        return book;
    }

    /** A delete added to a chat's book, the oldest going past MAX_PER_CHAT. Whether the book changed. */
    static boolean add(final List<Entry> book, final long rowId, final long dateMs)
    {
        if ((rowId <= 0L) || (dateMs <= 0L))
        {
            return false;
        }
        for (final Entry entry : book)
        {
            if (entry.rowId == rowId)
            {
                return false;
            }
        }
        book.add(new Entry(rowId, dateMs));
        while (book.size() > MAX_PER_CHAT)
        {
            book.remove(0);
        }
        return true;
    }

    /** Whether a delete of the book lies within WINDOW_MS of the date: when none does, no reply to it is answered. */
    static boolean anyNear(final List<Entry> book, final long dateMs)
    {
        for (final Entry entry : book)
        {
            if (Math.abs(entry.dateMs - dateMs) <= WINDOW_MS)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the message a reply quotes was deleted here. The header names it by a row id, ours only when the reply
     * was written here, and by its date, which the devices of a chat set a little apart. So the quote is of a deleted
     * message when a delete here bears the reply's row id, or lies nearer the date than every message still here; a
     * message still here that bears the id is the one quoted. The reply's own row is no candidate, nor a row a delete
     * of the book is taking away right now. With the live messages unknown, only the row id tells.
     */
    static boolean isDeleted(@Nullable final MessageReplyHelper.ReplyMeta reply, @Nullable final List<Entry> book,
                             final long replyRowId, @Nullable final Live live)
    {
        if ((reply == null) || reply.legacyQuote || (reply.sortTimestampMs <= 0L) || (book == null) || book.isEmpty())
        {
            return false;
        }
        final long date = reply.sortTimestampMs;
        final boolean ours = reply.localMessageId > 0L;
        final Set<Long> deletedRows = new HashSet<>();
        boolean byId = false;
        long nearestDeleted = Long.MAX_VALUE;
        for (final Entry entry : book)
        {
            deletedRows.add(entry.rowId);
            final long apart = Math.abs(entry.dateMs - date);
            if (apart > WINDOW_MS)
            {
                continue;
            }
            byId |= ours && (entry.rowId == reply.localMessageId);
            nearestDeleted = Math.min(nearestDeleted, apart);
        }
        if (nearestDeleted == Long.MAX_VALUE)
        {
            return false;
        }
        final List<long[]> rows = (live == null) ? null : live.near(date);
        if (rows == null)
        {
            return byId;
        }
        long nearestLive = Long.MAX_VALUE;
        for (final long[] row : rows)
        {
            if ((row == null) || (row.length < 2) || (row[0] == replyRowId) || deletedRows.contains(row[0]))
            {
                continue;
            }
            final long apart = Math.abs(row[1] - date);
            if (apart > WINDOW_MS)
            {
                continue;
            }
            if (ours && (row[0] == reply.localMessageId))
            {
                return false;
            }
            nearestLive = Math.min(nearestLive, apart);
        }
        return byId || (nearestDeleted < nearestLive);
    }

    /**
     * The deletes of the profile's chats, each chat's read from its row the first time it is asked for. Thread-safe;
     * the rows are written under its lock, the live messages are read outside it.
     */
    static final class Store
    {
        private final GroupMessageTombstones.Rows rows;
        private final Object lock = new Object();
        private final Map<String, List<Entry>> books = new HashMap<>();

        Store(final GroupMessageTombstones.Rows rows)
        {
            this.rows = rows;
        }

        /** Remembers a message of the chat deleted here, while its row is still there. Whether it is kept now. */
        boolean record(@Nullable final String chatKey, final long rowId, final long dateMs)
        {
            if ((chatKey == null) || (rowId <= 0L) || (dateMs <= 0L))
            {
                return false;
            }
            synchronized (lock)
            {
                final List<Entry> book = book(chatKey);
                if (book == null)
                {
                    return false;
                }
                if (add(book, rowId, dateMs))
                {
                    rows.put(rowKey(chatKey), encode(book));
                }
                return true;
            }
        }

        boolean isDeleted(@Nullable final String chatKey, @Nullable final MessageReplyHelper.ReplyMeta reply,
                          final long replyRowId, @Nullable final Live live)
        {
            if ((chatKey == null) || (reply == null) || (reply.sortTimestampMs <= 0L))
            {
                return false;
            }
            final List<Entry> copy;
            synchronized (lock)
            {
                final List<Entry> book = book(chatKey);
                if ((book == null) || !anyNear(book, reply.sortTimestampMs))
                {
                    return false;
                }
                copy = new ArrayList<>(book);
            }
            return ReplyTargetTombstones.isDeleted(reply, copy, replyRowId, live);
        }

        /** The chat is gone, and every reply in it. */
        void forget(@Nullable final String chatKey)
        {
            if (chatKey == null)
            {
                return;
            }
            synchronized (lock)
            {
                books.remove(chatKey);
                if (rows.ready())
                {
                    rows.remove(rowKey(chatKey));
                }
            }
        }

        /** Another profile is open: its chats are read from its own rows. */
        void forgetRead()
        {
            synchronized (lock)
            {
                books.clear();
            }
        }

        @Nullable
        private List<Entry> book(final String chatKey)
        {
            List<Entry> book = books.get(chatKey);
            if (book == null)
            {
                if (!rows.ready())
                {
                    return null;
                }
                book = decode(rows.get(rowKey(chatKey)));
                books.put(chatKey, book);
            }
            return book;
        }
    }
}

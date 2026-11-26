package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import androidx.annotation.Nullable;

/**
 * KHANDAQ (tester 21.11, 8256/8261): the group messages deleted on this device, kept so that a copy of one is not
 * stored again.
 *
 * <p>A location message deleted three times came back each time, "after a name change, not always". A delete left
 * nothing behind: the row went, while every member that still held the message served it in the history sync of a
 * public group (its last 130 minutes, at every join, the rejoin after a rename too; this side takes records up to
 * 200 minutes old), and the dedups, which look for a stored row, found none and stored it again as relayed history.
 * A delete for everyone reached only the members online at that moment; the others kept the message and served it.
 *
 * <p>A tombstone keeps what tells the copies of one message apart, never the text: for a text the author's key in
 * the group, the message id (four random bytes toxcore gives a message at its sender, the same in every copy, relay
 * and history record of it) and a digest of the text; for a file the author's key and the transfer id
 * (msg_id_hash). What made it decides what it stops:
 * <ul>
 *   <li>{@link Origin#LOCAL} (a member's message deleted here) and {@link Origin#OWN} (ours, deleted for everyone):
 *   a text with that id from the same author, or with that id and the same text from anyone, as the live path's
 *   dedup (#89) matches a relay that names another key; a file by its transfer id, from anyone;</li>
 *   <li>{@link Origin#REMOTE} (deleted for everyone by its author) and {@link Origin#UNSEEN} (the same, for a
 *   message not here yet): only that author's copies. The author is the peer toxcore authenticated as the sender of
 *   the delete, so a member can only ever stop messages of their own.</li>
 * </ul>
 * A message sent again is a new message with an id of its own (a location shared twice from one place has the same
 * text) and is never taken for a copy.
 *
 * <p>Kept per group in one g_opts row (no schema change, see NgcHskStore) for {@link #RETENTION_MS}, the newest
 * {@link #MAX_PER_GROUP}. The deletes for messages not here are capped apart and replaced slowly, so that a member
 * sending them by the thousand can neither push out what was deleted here nor cost more than a few writes; a delete
 * that changes nothing is not written at all.
 *
 * <p>Our own deletes for everyone go again, as the packets they first went as, when a member joins that may lack
 * one. Who holds them all is known for this session only, so after a start the first join in a group gets them
 * again; and a member counts as holding one only when it had been in the group for {@link #JOIN_SETTLE_MS} when it
 * went, since a member that has just joined drops a broadcast from a peer it has no info for yet (as the flush of
 * the waiting messages allows for).
 *
 * <p>Pure logic over a {@link Rows} store, unit-tested without a database (GroupMessageTombstonesTest);
 * HelperMessageDelete keeps the app's {@link Store}, asks it on every receive path and sends the packets.
 */
final class GroupMessageTombstones
{
    /** How long a tombstone is kept: a week, far past the 200 minutes of history the sync takes. */
    static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;
    /** The tombstones a group keeps, the newest. */
    static final int MAX_PER_GROUP = 512;
    /** Our own deletes for everyone sent again at once, the newest. */
    static final int MAX_RESEND = 32;
    /** Of a group's tombstones, the deletes for messages not here: per author (as many as an author sends again). */
    static final int MAX_UNSEEN_PER_AUTHOR = MAX_RESEND;
    /** And per group. */
    static final int MAX_UNSEEN_PER_GROUP = 128;
    /** Past those caps a new one takes the place of the oldest only once that one is this old. */
    static final long UNSEEN_HOLD_MS = 10L * 60L * 1000L;
    /** The members a group remembers as holding all our deletes; past it nobody is, and they get them again. */
    static final int MAX_KNOWN_PEERS = 4096;
    /** How long a member that joined may still drop a broadcast of ours, as for the waiting messages' flush. */
    static final long JOIN_SETTLE_MS = HelperGroup.GROUP_PEER_JOIN_FLUSH_DELAY_MS;
    /** A cleared history remembers the rows a history sync could still bring back (200 minutes), with a margin. */
    static final long CLEARED_WINDOW_MS = 210L * 60L * 1000L;

    /** The g_opts row of a group: this prefix, then the group's ID in lower case. */
    static final String ROW_KEY_PREFIX = "kqngcdeleted_";

    private static final String FORMAT_LINE = "v1";
    private static final int GROUP_ID_HEX_LENGTH = ToxVars.TOX_GROUP_CHAT_ID_SIZE * 2;
    private static final int KEY_HEX_LENGTH = 64;
    private static final int TRANSFER_ID_HEX_LENGTH = 64;
    private static final int MESSAGE_ID_HEX_LENGTH = 8;
    private static final int DIGEST_BYTES = 16;

    private GroupMessageTombstones()
    {
    }

    enum Kind
    {
        TEXT('t'), FILE('f');

        final char code;

        Kind(final char code)
        {
            this.code = code;
        }

        @Nullable
        static Kind of(final char code)
        {
            for (final Kind kind : values())
            {
                if (kind.code == code)
                {
                    return kind;
                }
            }
            return null;
        }
    }

    enum Origin
    {
        /** A member's message, deleted here. */
        LOCAL('l', true, 2),
        /** Our own, deleted here for everyone: sent again to members that may lack the delete. */
        OWN('o', true, 2),
        /** A member's message, deleted for everyone by its author while it was here. */
        REMOTE('r', false, 1),
        /** A member's message, deleted for everyone by its author before it reached us. */
        UNSEEN('u', false, 0);

        final char code;
        /** Whether it also stops a copy with the same id and text that names another author. */
        final boolean anyAuthor;
        /** Of two tombstones of one message, the stronger is kept. */
        final int strength;

        Origin(final char code, final boolean anyAuthor, final int strength)
        {
            this.code = code;
            this.anyAuthor = anyAuthor;
            this.strength = strength;
        }

        @Nullable
        static Origin of(final char code)
        {
            for (final Origin origin : values())
            {
                if (origin.code == code)
                {
                    return origin;
                }
            }
            return null;
        }
    }

    /** What adding a tombstone did. */
    enum Added
    {
        /** Not kept: the caps for messages not here are full of recent ones. */
        REFUSED,
        /** The message had one already, as strong: nothing changed. */
        ALREADY,
        /** Kept, the book changed. */
        KEPT
    }

    /** One deleted message. */
    static final class Entry
    {
        final Kind kind;
        final Origin origin;
        /** The author's key in the group, upper case; empty when it is not known. */
        final String author;
        /** A text's message id (8 hexadecimal digits, lower case), a file's transfer id (64, upper case). */
        final String anchor;
        /** The digest of a text (32 hexadecimal digits); empty for a file, and for a text not seen here. */
        final String digest;
        final long recordedMs;

        Entry(final Kind kind, final Origin origin, final String author, final String anchor, final String digest,
              final long recordedMs)
        {
            this.kind = kind;
            this.origin = origin;
            this.author = author;
            this.anchor = anchor;
            this.digest = digest;
            this.recordedMs = recordedMs;
        }

        private boolean sameMessage(final Entry other)
        {
            return (kind == other.kind) && anchor.equals(other.anchor) && author.equals(other.author);
        }
    }

    // ---- what is kept of a message ----

    /** The row of a group, or null for an ID that is not a group's (64 hexadecimal digits). */
    @Nullable
    static String rowKey(@Nullable final String groupIdentifier)
    {
        final String id = hexOf(groupIdentifier, GROUP_ID_HEX_LENGTH);
        return (id == null) ? null : ROW_KEY_PREFIX + id.toLowerCase(Locale.ROOT);
    }

    /**
     * The tombstone of a row about to be removed, or null for a row nothing could bring back: a line of the app, a
     * private message, a text without an id (ours not sent yet), a file without a transfer id.
     */
    @Nullable
    static Entry ofRow(@Nullable final GroupMessage row, final Origin origin, final long nowMs)
    {
        if ((row == null) || (row.private_message == 1)
            || TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(row.tox_group_peer_pubkey))
        {
            return null;
        }
        final String author = keyOf(row.tox_group_peer_pubkey);
        if (row.TRIFA_MESSAGE_TYPE == TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value)
        {
            final String transfer = transferIdOf(row.msg_id_hash);
            return (transfer == null) ? null : new Entry(Kind.FILE, origin, author, transfer, "", nowMs);
        }
        final String id = messageIdOf(row.message_id_tox);
        if (id == null)
        {
            return null;
        }
        final String digest = ((row.text == null) || row.text.isEmpty()) ? "" : digest(row.text);
        return new Entry(Kind.TEXT, origin, author, id, digest, nowMs);
    }

    /** A delete for everyone from the author of a message that is not here, or null when it names none. */
    @Nullable
    static Entry ofUnseen(final Kind kind, @Nullable final String authorKey, @Nullable final String anchor,
                          final long nowMs)
    {
        final String author = keyOf(authorKey);
        final String id = (kind == Kind.FILE) ? transferIdOf(anchor) : messageIdOf(anchor);
        if (author.isEmpty() || (id == null))
        {
            return null;
        }
        return new Entry(kind, Origin.UNSEEN, author, id, "", nowMs);
    }

    /** The first 16 bytes of the SHA-256 of the text in UTF-8, in hex: enough to tell texts apart, not the text. */
    static String digest(final String text)
    {
        try
        {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(DIGEST_BYTES * 2);
            for (int i = 0; i < DIGEST_BYTES; i++)
            {
                hex.append(String.format(Locale.ROOT, "%02x", hash[i] & 0xff));
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            // Every Java runtime has SHA-256; without it the id and the author still match.
            return "";
        }
    }

    // ---- the tombstones of one group, oldest first ----

    /** Drops what is past its week (or recorded a week ahead of a clock set back). Whether anything went. */
    static boolean prune(final List<Entry> book, final long nowMs)
    {
        boolean changed = false;
        for (final Iterator<Entry> it = book.iterator(); it.hasNext(); )
        {
            if (Math.abs(nowMs - it.next().recordedMs) > RETENTION_MS)
            {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Adds a tombstone. One the message has already stays where it is, made stronger if this one is; a new one goes
     * last, within the caps.
     */
    static Added add(final List<Entry> book, final Entry entry, final long nowMs)
    {
        for (int i = 0; i < book.size(); i++)
        {
            final Entry earlier = book.get(i);
            if (earlier.sameMessage(entry))
            {
                final Entry merged = merge(earlier, entry);
                if (merged == earlier)
                {
                    return Added.ALREADY;
                }
                book.set(i, merged);
                return Added.KEPT;
            }
        }
        if ((entry.origin == Origin.UNSEEN) && !roomForUnseen(book, entry.author, nowMs))
        {
            return Added.REFUSED;
        }
        book.add(entry);
        while (book.size() > MAX_PER_GROUP)
        {
            book.remove(0);
        }
        return Added.KEPT;
    }

    /** Whether a member's text is a copy of a message deleted here. */
    static boolean isDeletedText(final List<Entry> book, @Nullable final String authorKey,
                                 @Nullable final String messageId, @Nullable final String text)
    {
        final String id = messageIdOf(messageId);
        if (id == null)
        {
            return false;
        }
        final String author = keyOf(authorKey);
        String digest = null;
        for (final Entry entry : book)
        {
            if ((entry.kind != Kind.TEXT) || !entry.anchor.equals(id))
            {
                continue;
            }
            if (!author.isEmpty() && author.equals(entry.author))
            {
                return true;
            }
            if (entry.origin.anyAuthor && !entry.digest.isEmpty() && (text != null) && !text.isEmpty())
            {
                if (digest == null)
                {
                    digest = digest(text);
                }
                if (digest.equals(entry.digest))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a member's file is a copy of a file deleted here. */
    static boolean isDeletedFile(final List<Entry> book, @Nullable final String authorKey,
                                 @Nullable final String msgIdHash)
    {
        final String transfer = transferIdOf(msgIdHash);
        if (transfer == null)
        {
            return false;
        }
        final String author = keyOf(authorKey);
        for (final Entry entry : book)
        {
            if ((entry.kind == Kind.FILE) && entry.anchor.equals(transfer)
                && (entry.origin.anyAuthor || (!author.isEmpty() && author.equals(entry.author))))
            {
                return true;
            }
        }
        return false;
    }

    /** Whether a row about to be stored is a copy of a message deleted here. Only a member's message is asked. */
    static boolean isDeletedRow(final List<Entry> book, @Nullable final GroupMessage row)
    {
        if ((row == null) || (row.direction != 0) || (row.private_message == 1)
            || TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(row.tox_group_peer_pubkey))
        {
            return false;
        }
        if (row.TRIFA_MESSAGE_TYPE == TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value)
        {
            return isDeletedFile(book, row.tox_group_peer_pubkey, row.msg_id_hash);
        }
        return isDeletedText(book, row.tox_group_peer_pubkey, row.message_id_tox, row.text);
    }

    /** Our own deletes for everyone to send again, the newest first. */
    static List<Entry> ownToResend(final List<Entry> book)
    {
        final List<Entry> own = new ArrayList<>();
        for (int i = book.size() - 1; (i >= 0) && (own.size() < MAX_RESEND); i--)
        {
            if (book.get(i).origin == Origin.OWN)
            {
                own.add(book.get(i));
            }
        }
        return own;
    }

    // ---- the row ----

    static String encode(final List<Entry> book)
    {
        final StringBuilder out = new StringBuilder(FORMAT_LINE);
        for (final Entry entry : book)
        {
            out.append('\n').append(entry.kind.code).append(',').append(entry.origin.code).append(',')
               .append(entry.author).append(',').append(entry.anchor).append(',').append(entry.digest).append(',')
               .append(entry.recordedMs);
        }
        return out.toString();
    }

    /** A stored row read back; a damaged line is left out, a row of another format reads as none. */
    static List<Entry> decode(@Nullable final String stored)
    {
        final List<Entry> book = new ArrayList<>();
        if (stored == null)
        {
            return book;
        }
        final String[] lines = stored.split("\n");
        if ((lines.length == 0) || !FORMAT_LINE.equals(lines[0].trim()))
        {
            return book;
        }
        for (int i = 1; i < lines.length; i++)
        {
            final Entry entry = parse(lines[i].trim());
            if (entry != null)
            {
                book.add(entry);
            }
        }
        while (book.size() > MAX_PER_GROUP)
        {
            book.remove(0);
        }
        return book;
    }

    @Nullable
    private static Entry parse(final String line)
    {
        final String[] field = line.split(",", -1);
        if ((field.length != 6) || (field[0].length() != 1) || (field[1].length() != 1))
        {
            return null;
        }
        final Kind kind = Kind.of(field[0].charAt(0));
        final Origin origin = Origin.of(field[1].charAt(0));
        if ((kind == null) || (origin == null))
        {
            return null;
        }
        final String author = keyOf(field[2]);
        if ((!field[2].isEmpty() && author.isEmpty()) || ((origin == Origin.UNSEEN) && author.isEmpty()))
        {
            return null;
        }
        final String anchor = (kind == Kind.FILE) ? transferIdOf(field[3]) : messageIdOf(field[3]);
        final String digest = field[4].toLowerCase(Locale.ROOT);
        if ((anchor == null)
            || (!digest.isEmpty() && ((kind == Kind.FILE) || (hexOf(digest, DIGEST_BYTES * 2) == null))))
        {
            return null;
        }
        final long recordedMs;
        try
        {
            recordedMs = Long.parseLong(field[5]);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
        return (recordedMs > 0) ? new Entry(kind, origin, author, anchor, digest, recordedMs) : null;
    }

    // ---- helpers ----

    /** The earlier tombstone itself when the later one adds nothing; it keeps its time and place. */
    private static Entry merge(final Entry earlier, final Entry later)
    {
        final Origin origin = (later.origin.strength > earlier.origin.strength) ? later.origin : earlier.origin;
        final String digest = earlier.digest.isEmpty() ? later.digest : earlier.digest;
        if ((origin == earlier.origin) && digest.equals(earlier.digest))
        {
            return earlier;
        }
        return new Entry(earlier.kind, origin, earlier.author, earlier.anchor, digest, earlier.recordedMs);
    }

    /**
     * Room for a delete of a message not here: under the author's cap and the group's, or in place of the oldest
     * such delete (the author's at their cap, else the group's) once that one has been kept {@link #UNSEEN_HOLD_MS}.
     */
    private static boolean roomForUnseen(final List<Entry> book, final String author, final long nowMs)
    {
        int ofAuthor = 0;
        int ofGroup = 0;
        int oldestOfAuthor = -1;
        int oldestOfGroup = -1;
        for (int i = 0; i < book.size(); i++)
        {
            final Entry entry = book.get(i);
            if (entry.origin != Origin.UNSEEN)
            {
                continue;
            }
            if (oldestOfGroup < 0)
            {
                oldestOfGroup = i;
            }
            ofGroup++;
            if (entry.author.equals(author))
            {
                if (oldestOfAuthor < 0)
                {
                    oldestOfAuthor = i;
                }
                ofAuthor++;
            }
        }
        final int replace;
        if (ofAuthor >= MAX_UNSEEN_PER_AUTHOR)
        {
            replace = oldestOfAuthor;
        }
        else if (ofGroup >= MAX_UNSEEN_PER_GROUP)
        {
            replace = oldestOfGroup;
        }
        else
        {
            return true;
        }
        if ((nowMs - book.get(replace).recordedMs) < UNSEEN_HOLD_MS)
        {
            return false;
        }
        book.remove(replace);
        return true;
    }

    private static boolean hasOwn(final List<Entry> book)
    {
        for (final Entry entry : book)
        {
            if (entry.origin == Origin.OWN)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean sameMessages(final List<Entry> a, final List<Entry> b)
    {
        if (a.size() != b.size())
        {
            return false;
        }
        for (int i = 0; i < a.size(); i++)
        {
            if (!a.get(i).sameMessage(b.get(i)))
            {
                return false;
            }
        }
        return true;
    }

    /** A member's key in a group, upper case; empty for anything else (the "-1" of a failed lookup too). */
    static String keyOf(@Nullable final String key)
    {
        final String hex = hexOf(key, KEY_HEX_LENGTH);
        return (hex == null) ? "" : hex.toUpperCase(Locale.ROOT);
    }

    @Nullable
    private static String messageIdOf(@Nullable final String hex)
    {
        final String id = hexOf(hex, MESSAGE_ID_HEX_LENGTH);
        if (id == null)
        {
            return null;
        }
        final String lower = id.toLowerCase(Locale.ROOT);
        return HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX.equals(lower) ? null : lower;
    }

    @Nullable
    private static String transferIdOf(@Nullable final String hex)
    {
        final String id = hexOf(hex, TRANSFER_ID_HEX_LENGTH);
        return (id == null) ? null : id.toUpperCase(Locale.ROOT);
    }

    @Nullable
    private static String hexOf(@Nullable final String value, final int length)
    {
        if (value == null)
        {
            return null;
        }
        final String hex = value.trim();
        if (hex.length() != length)
        {
            return null;
        }
        for (int i = 0; i < hex.length(); i++)
        {
            if (Character.digit(hex.charAt(i), 16) < 0)
            {
                return null;
            }
        }
        return hex;
    }

    private static Set<String> keysOf(@Nullable final Collection<String> peerKeys)
    {
        final Set<String> keys = new HashSet<>();
        if (peerKeys != null)
        {
            for (final String peerKey : peerKeys)
            {
                final String key = keyOf(peerKey);
                if (!key.isEmpty())
                {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    // ---- the store ----

    /** Where the tombstones are kept: the profile's g_opts rows in the app (HelperMessageDelete), a map in tests. */
    interface Rows
    {
        /** Whether rows can be read now: the profile's database is open. */
        boolean ready();

        @Nullable
        String get(String key);

        void put(String key, String value);

        void remove(String key);
    }

    /**
     * The tombstones of the profile's groups, each group's read from its row the first time it is asked for, and
     * who holds all our deletes for everyone. Thread-safe; the rows are written under its lock, the packets are sent
     * by the caller outside it.
     */
    static final class Store
    {
        /** A delete of ours recorded and not sent yet. */
        private static final class Unsent
        {
            final Entry entry;
            /** The members that held every earlier delete of ours; null when there was none. */
            @Nullable
            final Set<String> heldBefore;

            Unsent(final Entry entry, @Nullable final Set<String> heldBefore)
            {
                this.entry = entry;
                this.heldBefore = heldBefore;
            }
        }

        private final Rows rows;
        private final Object lock = new Object();
        private final Map<String, List<Entry>> books = new HashMap<>();
        /** Per group: the members known to hold every delete of ours kept. Absent: nobody is known to. */
        private final Map<String, Set<String>> covered = new HashMap<>();
        private final Map<String, Unsent> unsent = new HashMap<>();
        /** Per group: the members that joined less than JOIN_SETTLE_MS ago, and when. */
        private final Map<String, Map<String, Long>> joined = new HashMap<>();
        /** Per group: when our deletes last went again. */
        private final Map<String, Long> lastResend = new HashMap<>();

        Store(final Rows rows)
        {
            this.rows = rows;
        }

        /** Remembers a row removed here, while it is still there. Whether the message has a tombstone now. */
        boolean record(@Nullable final GroupMessage row, final Origin origin, final long nowMs)
        {
            final Entry entry = ofRow(row, origin, nowMs);
            return (entry != null) && (add(row.group_identifier, Collections.singletonList(entry), nowMs) > 0);
        }

        /** Remembers a delete for everyone from the author of a message that is not here. */
        boolean recordUnseen(@Nullable final String groupId, final Kind kind, @Nullable final String authorKey,
                             @Nullable final String anchor, final long nowMs)
        {
            final Entry entry = ofUnseen(kind, authorKey, anchor, nowMs);
            return (entry != null) && (add(groupId, Collections.singletonList(entry), nowMs) > 0);
        }

        /**
         * Remembers the rows a clear of the group's history removes, those a history sync could still bring back,
         * in one write. How many have a tombstone now.
         */
        int recordCleared(@Nullable final String groupId, @Nullable final List<GroupMessage> cleared, final long nowMs)
        {
            if (cleared == null)
            {
                return 0;
            }
            final List<GroupMessage> recent = new ArrayList<>();
            for (final GroupMessage row : cleared)
            {
                if ((row != null) && ((nowMs - row.sent_timestamp) <= CLEARED_WINDOW_MS))
                {
                    recent.add(row);
                }
            }
            Collections.sort(recent, (a, b) -> Long.compare(a.sent_timestamp, b.sent_timestamp));
            List<Entry> entries = new ArrayList<>();
            for (final GroupMessage row : recent)
            {
                final Entry entry = ofRow(row, Origin.LOCAL, nowMs);
                if (entry != null)
                {
                    entries.add(entry);
                }
            }
            if (entries.size() > MAX_PER_GROUP)
            {
                entries = entries.subList(entries.size() - MAX_PER_GROUP, entries.size());
            }
            return entries.isEmpty() ? 0 : add(groupId, entries, nowMs);
        }

        boolean isDeletedText(@Nullable final String groupId, @Nullable final String authorKey,
                              @Nullable final String messageId, @Nullable final String text, final long nowMs)
        {
            synchronized (lock)
            {
                final List<Entry> book = book(rowKey(groupId), nowMs);
                return (book != null) && GroupMessageTombstones.isDeletedText(book, authorKey, messageId, text);
            }
        }

        boolean isDeletedFile(@Nullable final String groupId, @Nullable final String authorKey,
                              @Nullable final String msgIdHash, final long nowMs)
        {
            synchronized (lock)
            {
                final List<Entry> book = book(rowKey(groupId), nowMs);
                return (book != null) && GroupMessageTombstones.isDeletedFile(book, authorKey, msgIdHash);
            }
        }

        boolean isDeletedRow(@Nullable final GroupMessage row, final long nowMs)
        {
            if (row == null)
            {
                return false;
            }
            synchronized (lock)
            {
                final List<Entry> book = book(rowKey(row.group_identifier), nowMs);
                return (book != null) && GroupMessageTombstones.isDeletedRow(book, row);
            }
        }

        /** Our own deletes for everyone in the group to send again, the newest first. */
        List<Entry> ownToResend(@Nullable final String groupId, final long nowMs)
        {
            synchronized (lock)
            {
                final List<Entry> book = book(rowKey(groupId), nowMs);
                return (book == null) ? Collections.<Entry>emptyList() : GroupMessageTombstones.ownToResend(book);
            }
        }

        /** A member joined the group now: for a moment it may drop what we broadcast. */
        void notePeerJoined(@Nullable final String groupId, @Nullable final String peerKey, final long nowMs)
        {
            final String row = rowKey(groupId);
            final String key = keyOf(peerKey);
            if ((row == null) || key.isEmpty())
            {
                return;
            }
            synchronized (lock)
            {
                Map<String, Long> recent = joined.get(row);
                if (recent == null)
                {
                    recent = new HashMap<>();
                    joined.put(row, recent);
                }
                for (final Iterator<Long> it = recent.values().iterator(); it.hasNext(); )
                {
                    if (!joinedJustNow(it.next(), nowMs))
                    {
                        it.remove();
                    }
                }
                if (recent.size() < MAX_KNOWN_PEERS)
                {
                    recent.put(key, nowMs);
                }
            }
        }

        /** Our delete of the row went to the members connected now: those that were settled hold them all. */
        void noteOwnDeleteSent(@Nullable final GroupMessage row, @Nullable final Collection<String> onlineKeys,
                               final long nowMs)
        {
            final Entry entry = ofRow(row, Origin.OWN, nowMs);
            final String group = (row == null) ? null : rowKey(row.group_identifier);
            if ((entry == null) || (group == null))
            {
                return;
            }
            synchronized (lock)
            {
                final Unsent sent = unsent.get(group);
                if ((sent == null) || !sent.entry.sameMessage(entry))
                {
                    return;
                }
                unsent.remove(group);
                final Set<String> got = settled(group, onlineKeys, nowMs);
                if (sent.heldBefore == null)
                {
                    covered.put(group, capped(got));
                }
                else
                {
                    sent.heldBefore.retainAll(got);
                    covered.put(group, sent.heldBefore);
                }
            }
        }

        /**
         * How long to wait before our deletes go again in the group: 0 for now, -1 when none of the members connected
         * now lacks one. A member that joined just now is waited for, and they go again at most every JOIN_SETTLE_MS.
         */
        long resendWaitMs(@Nullable final String groupId, @Nullable final Collection<String> onlineKeys,
                          final long nowMs)
        {
            final String row = rowKey(groupId);
            synchronized (lock)
            {
                final List<Entry> book = book(row, nowMs);
                if ((book == null) || !hasOwn(book))
                {
                    return -1;
                }
                final Set<String> held = covered.get(row);
                final Map<String, Long> recent = joined.get(row);
                long due = Long.MAX_VALUE;
                for (final String key : keysOf(onlineKeys))
                {
                    if ((held != null) && held.contains(key))
                    {
                        continue;
                    }
                    final Long at = (recent == null) ? null : recent.get(key);
                    due = Math.min(due, ((at == null) || !joinedJustNow(at, nowMs)) ? nowMs : at + JOIN_SETTLE_MS);
                }
                if (due == Long.MAX_VALUE)
                {
                    return -1;
                }
                final Long last = lastResend.get(row);
                if ((last != null) && (last <= nowMs))
                {
                    due = Math.max(due, last + JOIN_SETTLE_MS);
                }
                return Math.max(0L, due - nowMs);
            }
        }

        /**
         * Our deletes went again to the members connected now: those that were settled hold them all, provided what
         * went is what would go now (a delete of ours recorded meanwhile went to fewer).
         */
        void noteOwnDeletesResent(@Nullable final String groupId, @Nullable final Collection<String> onlineKeys,
                                  final List<Entry> sent, final long nowMs)
        {
            final String row = rowKey(groupId);
            if (row == null)
            {
                return;
            }
            synchronized (lock)
            {
                lastResend.put(row, nowMs);
                final List<Entry> book = book(row, nowMs);
                if ((book == null) || !sameMessages(GroupMessageTombstones.ownToResend(book), sent))
                {
                    return;
                }
                unsent.remove(row);
                final Set<String> got = settled(row, onlineKeys, nowMs);
                final Set<String> held = covered.get(row);
                if (held == null)
                {
                    covered.put(row, capped(got));
                    return;
                }
                held.addAll(got);
                if (held.size() > MAX_KNOWN_PEERS)
                {
                    covered.put(row, capped(got));
                }
            }
        }

        /** How many tombstones the group keeps now. */
        int count(@Nullable final String groupId, final long nowMs)
        {
            synchronized (lock)
            {
                final List<Entry> book = book(rowKey(groupId), nowMs);
                return (book == null) ? 0 : book.size();
            }
        }

        /** A group deleted here: its tombstones go with it. */
        void forget(@Nullable final String groupId)
        {
            final String row = rowKey(groupId);
            if (row == null)
            {
                return;
            }
            synchronized (lock)
            {
                books.remove(row);
                covered.remove(row);
                unsent.remove(row);
                joined.remove(row);
                lastResend.remove(row);
                if (rows.ready())
                {
                    rows.remove(row);
                }
            }
        }

        /** A new tox session, maybe of another profile: the rows are read again, and nobody is known to hold any. */
        void forgetRead()
        {
            synchronized (lock)
            {
                books.clear();
                covered.clear();
                unsent.clear();
                joined.clear();
                lastResend.clear();
            }
        }

        /** Adds tombstones to a group's book, written once. How many of them the book has now. */
        private int add(@Nullable final String groupId, final List<Entry> entries, final long nowMs)
        {
            final String row = rowKey(groupId);
            synchronized (lock)
            {
                final List<Entry> book = book(row, nowMs);
                if (book == null)
                {
                    return 0;
                }
                int kept = 0;
                boolean changed = false;
                for (final Entry entry : entries)
                {
                    final boolean ownBefore = (entry.origin == Origin.OWN) && hasOwn(book);
                    final Added added = GroupMessageTombstones.add(book, entry, nowMs);
                    if (added == Added.REFUSED)
                    {
                        continue;
                    }
                    kept++;
                    if (added == Added.KEPT)
                    {
                        changed = true;
                        if (entry.origin == Origin.OWN)
                        {
                            ownRecorded(row, entry, ownBefore);
                        }
                    }
                }
                if (changed)
                {
                    store(row, book);
                }
                return kept;
            }
        }

        /**
         * A delete of ours recorded: until it is sent nobody is known to hold them all; once it is, those that held
         * the earlier ones and get it do. One recorded before and never sent leaves nobody holding the earlier ones.
         * Locked.
         */
        private void ownRecorded(final String row, final Entry entry, final boolean ownBefore)
        {
            final Set<String> held = covered.remove(row);
            unsent.put(row, new Unsent(entry, ownBefore ? ((held == null) ? new HashSet<String>() : held) : null));
        }

        /** The members connected now that had been in the group for JOIN_SETTLE_MS. Locked. */
        private Set<String> settled(final String row, @Nullable final Collection<String> onlineKeys, final long nowMs)
        {
            final Set<String> keys = keysOf(onlineKeys);
            final Map<String, Long> recent = joined.get(row);
            if (recent != null)
            {
                for (final Iterator<String> it = keys.iterator(); it.hasNext(); )
                {
                    final Long at = recent.get(it.next());
                    if ((at != null) && joinedJustNow(at, nowMs))
                    {
                        it.remove();
                    }
                }
            }
            return keys;
        }

        /** The group's tombstones, read the first time; null for no group or before the database is open. Locked. */
        @Nullable
        private List<Entry> book(@Nullable final String row, final long nowMs)
        {
            if (row == null)
            {
                return null;
            }
            List<Entry> book = books.get(row);
            if (book == null)
            {
                if (!rows.ready())
                {
                    return null;
                }
                book = decode(rows.get(row));
                books.put(row, book);
            }
            if (prune(book, nowMs))
            {
                store(row, book);
            }
            return book;
        }

        private void store(final String row, final List<Entry> book)
        {
            if (book.isEmpty())
            {
                rows.remove(row);
            }
            else
            {
                rows.put(row, encode(book));
            }
        }

        private static boolean joinedJustNow(final long joinedMs, final long nowMs)
        {
            return (nowMs >= joinedMs) && ((nowMs - joinedMs) < JOIN_SETTLE_MS);
        }

        private static Set<String> capped(final Set<String> keys)
        {
            return (keys.size() > MAX_KNOWN_PEERS) ? new HashSet<String>() : keys;
        }
    }
}

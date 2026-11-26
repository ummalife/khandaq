package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (tester 21.11, 8256/8261): a location message deleted three times came back each time. A delete left
 * nothing behind, and the members that still held the message served it in the history sync, or its sender's device
 * brought it again. The tombstones are tested here over a store of their own, a map that outlives one start as the
 * profile's g_opts rows do; the receive paths are Android's and toxcore's, so their wiring is read from the sources
 * (comments left out), as GroupOwnNamesTest does.
 */
public class GroupMessageTombstonesTest
{
    private static final String GROUP = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_GROUP = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String AMINA = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855";
    private static final String YUSUF = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71";
    private static final String OURS = "0F2652A3B4C5D6E7F8091A2B3C4D5E6F708192A3B4C5D6E7F8091A2B3C4D5E6F";
    private static final String LOCATION = "khandaq-location:41.00824,28.97836";
    private static final String TRANSFER = "9C1F0E2D3B4A59687766554433221100FFEEDDCCBBAA99887766554433221100";
    private static final long NOW = 1_763_800_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long SETTLE = GroupMessageTombstones.JOIN_SETTLE_MS;
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";

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
            writes++;
            rows.remove(key);
        }
    }

    private static GroupMessage text(final String author, final String messageId, final String text)
    {
        final GroupMessage m = new GroupMessage();
        m.direction = 0;
        m.group_identifier = GROUP;
        m.tox_group_peer_pubkey = author;
        m.message_id_tox = messageId;
        m.text = text;
        m.private_message = 0;
        m.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
        m.sent_timestamp = NOW;
        return m;
    }

    private static GroupMessage ours(final String messageId, final String text)
    {
        final GroupMessage m = text(OURS, messageId, text);
        m.direction = 1;
        return m;
    }

    private static GroupMessage file(final String author, final String transfer)
    {
        final GroupMessage m = text(author, "", "photo.jpg");
        m.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value;
        m.msg_id_hash = transfer;
        return m;
    }

    /** The copy a member serves in the history sync: the same author, id and text, stored as relayed. */
    private static GroupMessage fromHistory(final GroupMessage original)
    {
        final GroupMessage m = text(original.tox_group_peer_pubkey, original.message_id_tox, original.text);
        m.was_synced = true;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value;
        m.sent_timestamp = original.sent_timestamp;
        return m;
    }

    private static String id(final int n)
    {
        return String.format(Locale.ROOT, "%08x", n);
    }

    // ---- record and match ----

    @Test
    public void the_deleted_location_does_not_come_back_from_history()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        final GroupMessage location = text(AMINA, "a1b2c3d4", LOCATION);
        assertFalse(store.isDeletedRow(fromHistory(location), NOW));

        assertTrue(store.record(location, GroupMessageTombstones.Origin.LOCAL, NOW));

        // The history sync of the next join, 20 minutes later, and the live path once more.
        assertTrue(store.isDeletedRow(fromHistory(location), NOW + 20 * MINUTE));
        assertTrue(store.isDeletedText(GROUP, AMINA, "A1B2C3D4", LOCATION, NOW + 20 * MINUTE));
        assertTrue(store.isDeletedText(GROUP.toUpperCase(Locale.ROOT), AMINA.toLowerCase(Locale.ROOT), "a1b2c3d4",
                                       LOCATION, NOW + 20 * MINUTE));
        // Another group has its own.
        assertFalse(store.isDeletedText(OTHER_GROUP, AMINA, "a1b2c3d4", LOCATION, NOW));
    }

    @Test
    public void the_location_shared_again_is_a_new_message()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        store.record(text(AMINA, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.LOCAL, NOW);
        // Shared again from the same place: the same text, an id of its own.
        assertFalse(store.isDeletedRow(text(AMINA, "0e0f1011", LOCATION), NOW + MINUTE));
    }

    @Test
    public void a_relay_under_another_key_is_a_copy_by_its_id_and_text()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        store.record(text(AMINA, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.LOCAL, NOW);
        // As the dedup of the live path collapses it (#89): the id and the text, whoever it names.
        assertTrue(store.isDeletedText(GROUP, YUSUF, "a1b2c3d4", LOCATION, NOW));
        assertTrue(store.isDeletedText(GROUP, null, "a1b2c3d4", LOCATION, NOW));
        // A copy from the author keeps the id, an edited one too.
        assertTrue(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", "edited", NOW));
        // Another member's message under the same id is not it.
        assertFalse(store.isDeletedText(GROUP, YUSUF, "a1b2c3d4", "hello", NOW));
        // Nor is anything without an id.
        assertFalse(store.isDeletedText(GROUP, AMINA, "", LOCATION, NOW));
        assertFalse(store.isDeletedText(GROUP, AMINA, "00000000", LOCATION, NOW));
    }

    @Test
    public void a_delete_for_everyone_stops_only_the_authors_copies()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        store.record(text(AMINA, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.REMOTE, NOW);
        assertTrue(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION, NOW));
        // Amina deleted hers; a message of Yusuf's is his, even with the same id and text.
        assertFalse(store.isDeletedText(GROUP, YUSUF, "a1b2c3d4", LOCATION, NOW));
    }

    @Test
    public void a_delete_that_came_before_its_message_stops_it_when_it_comes()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        assertTrue(store.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, AMINA, "a1b2c3d4", NOW));
        assertTrue(store.isDeletedRow(fromHistory(text(AMINA, "a1b2c3d4", LOCATION)), NOW + MINUTE));
        assertFalse(store.isDeletedText(GROUP, YUSUF, "a1b2c3d4", LOCATION, NOW));
        assertTrue(store.recordUnseen(GROUP, GroupMessageTombstones.Kind.FILE, AMINA, TRANSFER, NOW));
        assertTrue(store.isDeletedFile(GROUP, AMINA, TRANSFER.toLowerCase(Locale.ROOT), NOW));
        assertFalse(store.isDeletedFile(GROUP, YUSUF, TRANSFER, NOW));
        // A delete that names nobody, or nothing, is not kept.
        assertFalse(store.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, "-1", "a1b2c3d4", NOW));
        assertFalse(store.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, AMINA, "00000000", NOW));
        assertFalse(store.recordUnseen(GROUP, GroupMessageTombstones.Kind.FILE, AMINA, "a1b2c3d4", NOW));
        assertFalse(store.recordUnseen("-1", GroupMessageTombstones.Kind.TEXT, AMINA, "a1b2c3d4", NOW));
    }

    @Test
    public void a_file_deleted_here_stays_deleted_whoever_serves_it()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        store.record(file(AMINA, TRANSFER), GroupMessageTombstones.Origin.LOCAL, NOW);
        assertTrue(store.isDeletedFile(GROUP, AMINA, TRANSFER, NOW));
        assertTrue(store.isDeletedFile(GROUP, YUSUF, TRANSFER, NOW));
        assertTrue(store.isDeletedFile(GROUP, null, TRANSFER, NOW));
        assertTrue(store.isDeletedRow(file(AMINA, TRANSFER), NOW));
        assertFalse(store.isDeletedFile(GROUP, AMINA, TRANSFER.replace('9', '8'), NOW));
        // A file is not a text with that anchor.
        assertFalse(store.isDeletedText(GROUP, AMINA, TRANSFER.substring(0, 8), "photo.jpg", NOW));
    }

    @Test
    public void what_nothing_brings_back_is_not_kept()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        final GroupMessage system = text(TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY, "", "Amina joined");
        assertFalse(store.record(system, GroupMessageTombstones.Origin.LOCAL, NOW));
        final GroupMessage whisper = text(AMINA, "a1b2c3d4", "only you");
        whisper.private_message = 1;
        assertFalse(store.record(whisper, GroupMessageTombstones.Origin.LOCAL, NOW));
        assertFalse(store.record(ours("00000000", "not sent yet"), GroupMessageTombstones.Origin.OWN, NOW));
        assertFalse(store.record(file(AMINA, ""), GroupMessageTombstones.Origin.LOCAL, NOW));
        assertFalse(store.record(null, GroupMessageTombstones.Origin.LOCAL, NOW));
        assertEquals(0, store.count(GROUP, NOW));
    }

    @Test
    public void the_insert_refuses_only_a_members_copy()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        final GroupMessage location = text(AMINA, "a1b2c3d4", LOCATION);
        store.record(location, GroupMessageTombstones.Origin.LOCAL, NOW);
        assertTrue(store.isDeletedRow(fromHistory(location), NOW));
        // Our own rows and the app's lines are never asked about.
        final GroupMessage own = fromHistory(location);
        own.direction = 1;
        assertFalse(store.isDeletedRow(own, NOW));
        final GroupMessage line = fromHistory(location);
        line.tox_group_peer_pubkey = TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
        assertFalse(store.isDeletedRow(line, NOW));
        final GroupMessage whisper = fromHistory(location);
        whisper.private_message = 1;
        assertFalse(store.isDeletedRow(whisper, NOW));
        assertFalse(store.isDeletedRow(null, NOW));
    }

    // ---- prune, caps, restart ----

    @Test
    public void a_tombstone_lasts_a_week_and_then_goes_with_its_row()
    {
        final ProfileRows rows = new ProfileRows();
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(rows);
        store.record(text(AMINA, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.LOCAL, NOW);
        final String row = GroupMessageTombstones.rowKey(GROUP);
        assertTrue(rows.rows.containsKey(row));

        assertTrue(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION, NOW + GroupMessageTombstones.RETENTION_MS));
        assertFalse(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION,
                                        NOW + GroupMessageTombstones.RETENTION_MS + 1));
        assertFalse("the empty row is removed", rows.rows.containsKey(row));
    }

    @Test
    public void a_group_keeps_its_newest_tombstones()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        final int over = GroupMessageTombstones.MAX_PER_GROUP + 40;
        for (int i = 1; i <= over; i++)
        {
            store.record(text(AMINA, id(i), "text " + i), GroupMessageTombstones.Origin.LOCAL, NOW + i);
        }
        assertEquals(GroupMessageTombstones.MAX_PER_GROUP, store.count(GROUP, NOW + over));
        assertFalse(store.isDeletedText(GROUP, AMINA, id(1), "text 1", NOW + over));
        assertTrue(store.isDeletedText(GROUP, AMINA, id(over), "text " + over, NOW + over));
    }

    @Test
    public void a_flood_of_deletes_for_messages_not_here_costs_a_few_writes_and_pushes_out_nothing()
    {
        final ProfileRows rows = new ProfileRows();
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(rows);
        store.record(text(YUSUF, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.LOCAL, NOW);
        final int writesBefore = rows.writes;

        // One member sends a thousand deletes for messages nobody has, within a second.
        for (int i = 1; i <= 1000; i++)
        {
            store.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, AMINA, id(i), NOW + i);
        }
        assertEquals(1 + GroupMessageTombstones.MAX_UNSEEN_PER_AUTHOR, store.count(GROUP, NOW + 1000));
        assertEquals(GroupMessageTombstones.MAX_UNSEEN_PER_AUTHOR, rows.writes - writesBefore);
        // The first ones hold, the rest were refused.
        assertTrue(store.isDeletedText(GROUP, AMINA, id(1), "x", NOW + 1000));
        assertFalse(store.isDeletedText(GROUP, AMINA, id(1000), "x", NOW + 1000));

        // Once the oldest has been kept a while, a new one takes its place.
        final long later = NOW + 1 + GroupMessageTombstones.UNSEEN_HOLD_MS;
        assertTrue(store.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, AMINA, "0badf00d", later));
        assertFalse(store.isDeletedText(GROUP, AMINA, id(1), "x", later));
        assertTrue(store.isDeletedText(GROUP, AMINA, "0badf00d", "x", later));

        // Many members, the same: the group's cap.
        for (int i = 1; i <= 1000; i++)
        {
            store.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, String.format(Locale.ROOT, "%064X", i), id(i),
                               later + i);
        }
        assertEquals(1 + GroupMessageTombstones.MAX_UNSEEN_PER_GROUP, store.count(GROUP, later + 1000));
        // What was deleted here stays.
        assertTrue(store.isDeletedText(GROUP, YUSUF, "a1b2c3d4", LOCATION, later + 1000));
        assertTrue(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION, later + 1000));
    }

    @Test
    public void a_delete_that_changes_nothing_writes_nothing()
    {
        final ProfileRows rows = new ProfileRows();
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(rows);
        store.record(text(AMINA, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.LOCAL, NOW);
        final int writes = rows.writes;
        // Amina deletes it for everyone after we did, and sends the delete again at every join.
        assertTrue(store.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, AMINA, "a1b2c3d4", NOW + MINUTE));
        assertTrue(store.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, AMINA, "a1b2c3d4", NOW + 2 * MINUTE));
        assertEquals(writes, rows.writes);
        assertEquals(1, store.count(GROUP, NOW + 2 * MINUTE));
        // Still the one of a message deleted here: a relay under another key is stopped by the text.
        assertTrue(store.isDeletedText(GROUP, YUSUF, "a1b2c3d4", LOCATION, NOW + 2 * MINUTE));
    }

    @Test
    public void the_tombstones_outlive_a_restart()
    {
        final ProfileRows rows = new ProfileRows();
        final GroupMessageTombstones.Store before = new GroupMessageTombstones.Store(rows);
        before.record(text(AMINA, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.LOCAL, NOW);
        before.record(file(YUSUF, TRANSFER), GroupMessageTombstones.Origin.REMOTE, NOW);
        before.recordUnseen(GROUP, GroupMessageTombstones.Kind.TEXT, YUSUF, "0badf00d", NOW);

        final GroupMessageTombstones.Store after = new GroupMessageTombstones.Store(rows);
        assertTrue(after.isDeletedText(GROUP, YUSUF, "a1b2c3d4", LOCATION, NOW + MINUTE));
        assertTrue(after.isDeletedFile(GROUP, YUSUF, TRANSFER, NOW + MINUTE));
        assertFalse(after.isDeletedFile(GROUP, AMINA, TRANSFER, NOW + MINUTE));
        assertTrue(after.isDeletedText(GROUP, YUSUF, "0badf00d", "anything", NOW + MINUTE));

        // A session of another profile reads its own rows.
        rows.rows.clear();
        after.forgetRead();
        assertFalse(after.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION, NOW + MINUTE));
    }

    @Test
    public void nothing_is_cached_before_the_database_is_open()
    {
        final ProfileRows rows = new ProfileRows();
        new GroupMessageTombstones.Store(rows).record(text(AMINA, "a1b2c3d4", LOCATION),
                                                      GroupMessageTombstones.Origin.LOCAL, NOW);
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(rows);
        rows.open = false;
        assertFalse(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION, NOW));
        assertFalse(store.record(text(AMINA, "0e0f1011", "hi"), GroupMessageTombstones.Origin.LOCAL, NOW));
        rows.open = true;
        assertTrue(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION, NOW));
    }

    @Test
    public void a_deleted_group_takes_its_tombstones_along()
    {
        final ProfileRows rows = new ProfileRows();
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(rows);
        store.record(text(AMINA, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.LOCAL, NOW);
        store.forget(GROUP);
        assertTrue(rows.rows.isEmpty());
        assertFalse(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION, NOW));
    }

    @Test
    public void a_damaged_row_reads_as_what_is_left_of_it()
    {
        final List<GroupMessageTombstones.Entry> book = new ArrayList<>();
        GroupMessageTombstones.add(book, GroupMessageTombstones.ofRow(text(AMINA, "a1b2c3d4", LOCATION),
                                                                     GroupMessageTombstones.Origin.LOCAL, NOW), NOW);
        final String stored = GroupMessageTombstones.encode(book);
        assertEquals(1, GroupMessageTombstones.decode(stored).size());
        assertEquals(1, GroupMessageTombstones.decode(stored + "\nt,l,nothex,a1b2c3d4,,1\nx,l,,a1b2c3d4,,1\n"
                                                      + "t,u,,a1b2c3d4,,1\nt,l," + AMINA + ",a1b2c3d4,,-5\n"
                                                      + "f,r," + AMINA + "," + TRANSFER + ",abc,1\n"
                                                      + "t,l," + AMINA + ",a1b2c3d4,,not-a-time\n,,,,\n").size());
        assertTrue(GroupMessageTombstones.decode(null).isEmpty());
        assertTrue(GroupMessageTombstones.decode("").isEmpty());
        assertTrue(GroupMessageTombstones.decode("v9\n" + stored.substring(3)).isEmpty());
        assertTrue(GroupMessageTombstones.decode("{\"json\":true}").isEmpty());
        // The text itself is never kept.
        assertFalse(stored.contains("khandaq-location"));
        assertFalse(stored.contains("41.00824"));
    }

    @Test
    public void a_group_has_one_row_by_its_id()
    {
        assertEquals(GroupMessageTombstones.ROW_KEY_PREFIX + GROUP,
                     GroupMessageTombstones.rowKey(GROUP.toUpperCase(Locale.ROOT)));
        assertNull(GroupMessageTombstones.rowKey("-1"));
        assertNull(GroupMessageTombstones.rowKey(null));
        assertNull(GroupMessageTombstones.rowKey(GROUP.substring(2)));
    }

    // ---- a cleared history ----

    @Test
    public void a_cleared_history_remembers_what_a_history_sync_could_bring_back()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        final GroupMessage fresh = text(AMINA, "a1b2c3d4", LOCATION);
        fresh.sent_timestamp = NOW - 20 * MINUTE;
        final GroupMessage old = text(AMINA, "0e0f1011", "from this morning");
        old.sent_timestamp = NOW - 5 * 60 * MINUTE;
        final GroupMessage own = ours("1a2b3c4d", "ours");
        own.sent_timestamp = NOW - MINUTE;
        final GroupMessage line = text(TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY, "", "Amina joined");

        assertEquals(2, store.recordCleared(GROUP, Arrays.asList(fresh, old, own, line, null), NOW));
        assertTrue(store.isDeletedText(GROUP, AMINA, "a1b2c3d4", LOCATION, NOW));
        assertTrue(store.isDeletedText(GROUP, YUSUF, "1a2b3c4d", "ours", NOW));
        // Older than any history sync takes: nothing could bring it back.
        assertFalse(store.isDeletedText(GROUP, AMINA, "0e0f1011", "from this morning", NOW));
        // A clear sends nothing: none of it is a delete of ours for everyone.
        assertTrue(store.ownToResend(GROUP, NOW).isEmpty());
        assertEquals(0, store.recordCleared(GROUP, Collections.<GroupMessage>emptyList(), NOW));
    }

    // ---- our own deletes for everyone ----

    @Test
    public void our_delete_goes_again_to_a_member_that_missed_it()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        final GroupMessage first = ours("1a2b3c4d", LOCATION);
        assertEquals(-1, store.resendWaitMs(GROUP, Collections.singletonList(AMINA), NOW));

        assertTrue(store.record(first, GroupMessageTombstones.Origin.OWN, NOW));
        // Until it went, nobody holds it.
        assertEquals(0, store.resendWaitMs(GROUP, Collections.singletonList(AMINA), NOW));
        // It went while Amina was connected, and Yusuf was not.
        store.noteOwnDeleteSent(first, Collections.singletonList(AMINA), NOW);
        assertEquals(-1, store.resendWaitMs(GROUP, Collections.singletonList(AMINA), NOW + MINUTE));

        // Yusuf joins: it goes again once he can take a broadcast of ours.
        store.notePeerJoined(GROUP, YUSUF, NOW + MINUTE);
        assertEquals(SETTLE, store.resendWaitMs(GROUP, Collections.singletonList(YUSUF), NOW + MINUTE));
        final long ready = NOW + MINUTE + SETTLE;
        assertEquals(0, store.resendWaitMs(GROUP, Arrays.asList(AMINA, YUSUF), ready));
        final List<GroupMessageTombstones.Entry> again = store.ownToResend(GROUP, ready);
        assertEquals(1, again.size());
        assertEquals("1a2b3c4d", again.get(0).anchor);
        store.noteOwnDeletesResent(GROUP, Arrays.asList(AMINA, YUSUF), again, ready);
        assertEquals(-1, store.resendWaitMs(GROUP, Arrays.asList(AMINA, YUSUF), NOW + 2 * MINUTE));

        // The next delete reaches only Yusuf: Amina lacks it now.
        final GroupMessage second = ours("5e6f7a8b", "second");
        store.record(second, GroupMessageTombstones.Origin.OWN, NOW + 3 * MINUTE);
        store.noteOwnDeleteSent(second, Collections.singletonList(YUSUF), NOW + 3 * MINUTE);
        assertEquals(0, store.resendWaitMs(GROUP, Collections.singletonList(AMINA), NOW + 3 * MINUTE));
        assertEquals(-1, store.resendWaitMs(GROUP, Collections.singletonList(YUSUF), NOW + 3 * MINUTE));

        // A restart knows nobody: the first join gets them again.
        store.forgetRead();
        assertEquals(0, store.resendWaitMs(GROUP, Collections.singletonList(YUSUF), NOW + 4 * MINUTE));
        // A key that is no member's is nobody to send to.
        assertEquals(-1, store.resendWaitMs(GROUP, Collections.singletonList("-1"), NOW + 4 * MINUTE));
        // And once the week is over, nothing goes again.
        assertEquals(-1, store.resendWaitMs(GROUP, Collections.singletonList(AMINA),
                                            NOW + GroupMessageTombstones.RETENTION_MS + 4 * MINUTE));
    }

    @Test
    public void a_member_that_had_just_joined_is_not_taken_to_hold_our_delete()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        // Yusuf joined half a second before the delete went: he may have dropped it.
        store.notePeerJoined(GROUP, YUSUF, NOW);
        final GroupMessage own = ours("1a2b3c4d", LOCATION);
        store.record(own, GroupMessageTombstones.Origin.OWN, NOW + 500);
        store.noteOwnDeleteSent(own, Arrays.asList(AMINA, YUSUF), NOW + 500);
        assertEquals(-1, store.resendWaitMs(GROUP, Collections.singletonList(AMINA), NOW + 500));
        assertEquals(SETTLE - 500, store.resendWaitMs(GROUP, Arrays.asList(AMINA, YUSUF), NOW + 500));
        // Nor by a pass that went before he was ready.
        store.noteOwnDeletesResent(GROUP, Arrays.asList(AMINA, YUSUF), store.ownToResend(GROUP, NOW + 600),
                                   NOW + 600);
        assertEquals(SETTLE, store.resendWaitMs(GROUP, Collections.singletonList(YUSUF), NOW + 600));
        // A pass goes at most every JOIN_SETTLE_MS; the next one reaches him.
        final long next = NOW + 600 + SETTLE;
        assertEquals(0, store.resendWaitMs(GROUP, Collections.singletonList(YUSUF), next));
        store.noteOwnDeletesResent(GROUP, Arrays.asList(AMINA, YUSUF), store.ownToResend(GROUP, next), next);
        assertEquals(-1, store.resendWaitMs(GROUP, Arrays.asList(AMINA, YUSUF), next));
    }

    @Test
    public void a_delete_of_ours_recorded_meanwhile_is_not_vouched_for_by_a_pass_without_it()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        final GroupMessage first = ours("1a2b3c4d", LOCATION);
        store.record(first, GroupMessageTombstones.Origin.OWN, NOW);
        store.noteOwnDeleteSent(first, Collections.singletonList(AMINA), NOW);
        final List<GroupMessageTombstones.Entry> pass = store.ownToResend(GROUP, NOW + MINUTE);

        final GroupMessage second = ours("5e6f7a8b", "second");
        store.record(second, GroupMessageTombstones.Origin.OWN, NOW + MINUTE);
        store.noteOwnDeletesResent(GROUP, Arrays.asList(AMINA, YUSUF), pass, NOW + MINUTE);
        // Neither holds the second; the next pass goes after the interval.
        assertEquals(SETTLE, store.resendWaitMs(GROUP, Collections.singletonList(AMINA), NOW + MINUTE));
        assertEquals(SETTLE, store.resendWaitMs(GROUP, Collections.singletonList(YUSUF), NOW + MINUTE));
        // A send of another message does not vouch for it either.
        store.noteOwnDeleteSent(ours("0badf00d", "never kept"), Arrays.asList(AMINA, YUSUF), NOW + 2 * MINUTE);
        assertEquals(0, store.resendWaitMs(GROUP, Collections.singletonList(AMINA), NOW + 2 * MINUTE));
    }

    @Test
    public void a_delete_sent_while_the_group_was_unreachable_goes_at_the_first_join()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        final GroupMessage own = file(OURS, TRANSFER);
        own.direction = 1;
        store.record(own, GroupMessageTombstones.Origin.OWN, NOW);
        // Not sent: the group was not in toxcore.
        assertEquals(0, store.resendWaitMs(GROUP, Collections.singletonList(AMINA), NOW + MINUTE));
        final GroupMessageTombstones.Entry entry = store.ownToResend(GROUP, NOW + MINUTE).get(0);
        assertEquals(GroupMessageTombstones.Kind.FILE, entry.kind);
        assertEquals(TRANSFER, entry.anchor);
        // It goes again as the packet of the first time, with the time it was deleted.
        assertEquals(45, HelperMessageDelete.buildGroupDeleteFilePacket(entry.anchor, entry.recordedMs / 1000).length);
        assertEquals(NOW, entry.recordedMs);
    }

    @Test
    public void the_newest_of_our_deletes_go_again()
    {
        final GroupMessageTombstones.Store store = new GroupMessageTombstones.Store(new ProfileRows());
        for (int i = 1; i <= GroupMessageTombstones.MAX_RESEND + 8; i++)
        {
            store.record(ours(id(i), "ours " + i), GroupMessageTombstones.Origin.OWN, NOW + i);
        }
        store.record(text(AMINA, "a1b2c3d4", LOCATION), GroupMessageTombstones.Origin.LOCAL, NOW + 100);
        final List<GroupMessageTombstones.Entry> again = store.ownToResend(GROUP, NOW + 100);
        assertEquals(GroupMessageTombstones.MAX_RESEND, again.size());
        assertEquals(id(GroupMessageTombstones.MAX_RESEND + 8), again.get(0).anchor);
        for (final GroupMessageTombstones.Entry entry : again)
        {
            assertEquals(GroupMessageTombstones.Origin.OWN, entry.origin);
            assertEquals(16, HelperMessageDelete.buildGroupDeletePacket(entry.anchor, entry.recordedMs / 1000).length);
        }
    }

    // ---- the wiring ----

    @Test
    public void every_receive_path_asks_before_it_stores()
    {
        final String helper = code("HelperGroup.java");
        final String insert = bare(body(helper, "static long insert_into_group_message_db(final GroupMessage m,"));
        assertBefore(insert, "if(HelperMessageDelete.isDeletedGroupRow(m)){", "orma.insertIntoGroupMessage(m);");

        final String live = bare(body(helper, "static void android_tox_callback_group_message_cb_method_wrapper("));
        assertBefore(live, "HelperMessageDelete.isDeletedGroupText(m.group_identifier,m.tox_group_peer_pubkey,"
                           + "m.message_id_tox,m.text)", "get_last_group_message_in_this_group_within_n_seconds");
        assertBefore(live, "HelperMessageDelete.isDeletedGroupText(", "change_msg_notification(");

        // The history of the group's members and the relay's both come through here.
        final String history = bare(body(helper, "static void group_message_add_from_sync(final String group_identifier,"));
        assertBefore(history, "HelperMessageDelete.isDeletedGroupText(group_identifier,peer_pubkey,message_id,message)",
                     "find_group_message_by_msgid_and_text(");
        assertTrue(bare(code("MainActivity.java")).contains("group_message_add_from_sync(real_conference_id,"));

        final String historyFile = bare(body(helper, "private static void group_file_add_from_sync("));
        assertBefore(historyFile, "HelperMessageDelete.isDeletedGroupFile(group_identifier,original_sender_peerpubkey,"
                                  + "message_id_hash)", "ngc_incoming_download_allowed(");

        final String single = bare(body(helper, "static void handle_incoming_group_file(long group_number,"));
        assertBefore(single, "HelperMessageDelete.isDeletedGroupFile(group_id,peer_pubkey,m.msg_id_hash)",
                     "save_group_incoming_file(");

        final String begin = bare(body(helper, "static void handle_incoming_group_file_begin("));
        assertBefore(begin, "HelperMessageDelete.isDeletedGroupFile(groupId,asm.senderPubkey,msgIdHex)",
                     "notify_incoming_group_file(");
        final String complete = bare(body(helper, "static void handle_incoming_group_file_complete("));
        assertBefore(complete, "HelperMessageDelete.isDeletedGroupFile(group_id,peer_pubkey,msgIdHex)",
                     "insert_into_group_message_db(m,true);");
        assertBefore(complete, "HelperMessageDelete.isDeletedGroupFile(group_id,peer_pubkey,msgIdHex)",
                     "delete_received_group_file_copy(asm.outPath);");

        final String chunked = bare(body(code("NgcGroupFileTransfer.java"), "static void handleIncomingBegin("));
        assertBefore(chunked, "HelperMessageDelete.isDeletedGroupFile(groupId,beginFromPubkey,msgIdHex)",
                     "createAssembly(");
    }

    @Test
    public void every_delete_leaves_a_tombstone()
    {
        final String task = bare(body(code("MainActivity.java"),
                                      "static class delete_selected_group_messages_asynchtask"));
        assertBefore(task, "HelperMessageDelete.rememberDeletedGroupMessage(m_to_delete);",
                     "orma.deleteFromGroupMessage().idEq(message_id_to_delete).execute();");
        assertBefore(task, "HelperMessageDelete.sendOwnGroupDelete(m_to_delete);",
                     "orma.deleteFromGroupMessage().idEq(message_id_to_delete).execute();");

        final String deletes = code("HelperMessageDelete.java");
        final String own = bare(body(deletes, "static void sendOwnGroupDelete(final GroupMessage gm)"));
        assertBefore(own, "GROUP_TOMBSTONES.record(gm,GroupMessageTombstones.Origin.OWN,",
                     "finalList<String>online=onlineGroupPeerKeys(group_number);");
        assertBefore(own, "finalList<String>online=onlineGroupPeerKeys(group_number);",
                     "MainActivity.tox_group_send_custom_packet(group_number,1,pkt,pkt.length)");
        assertBefore(own, "MainActivity.tox_group_send_custom_packet(group_number,1,pkt,pkt.length)",
                     "GROUP_TOMBSTONES.noteOwnDeleteSent(gm,online,");

        final String incoming = bare(body(deletes, "static void handleIncomingGroupDelete(final long group_number,"));
        assertTrue(incoming.contains("GROUP_TOMBSTONES.recordUnseen(groupIdentifier,"));
        assertBefore(incoming, "GROUP_TOMBSTONES.record(gm,GroupMessageTombstones.Origin.REMOTE,",
                     "orma.deleteFromGroupMessage().idEq(gm.id).execute();");

        final String clear = bare(body(code("AirGroupDossierActivity.java"),
                                       "private static void clearHistoryHere(final String groupId)"));
        assertBefore(clear, "HelperMessageDelete.rememberClearedGroupHistory(id);",
                     "TrifaToxService.orma.deleteFromGroupMessage().group_identifierEq(id).execute();");
    }

    @Test
    public void a_join_sends_our_deletes_again_as_the_same_broadcast()
    {
        final String join = bare(body(code("MainActivity.java"),
                                      "static void android_tox_callback_group_peer_join_cb_method(long group_number,"));
        assertTrue(join.contains("HelperMessageDelete.onGroupPeerJoined(temp_group_identifier,group_peer_pubkey);"));

        final String deletes = code("HelperMessageDelete.java");
        final String joined = bare(body(deletes, "static void onGroupPeerJoined(final String groupId,"));
        assertBefore(joined, "GROUP_TOMBSTONES.notePeerJoined(groupId,peerPubkey,", "scheduleGroupDeleteResend(");

        final String again = bare(body(deletes, "private static void resendOwnGroupDeletes(final String groupId)"));
        // The packets of the first time, broadcast lossless: the receivers take a delete only from the group's
        // broadcast callback.
        assertTrue(again.contains("buildGroupDeleteFilePacket(entry.anchor,entry.recordedMs/1000)"));
        assertTrue(again.contains("buildGroupDeletePacket(entry.anchor,entry.recordedMs/1000)"));
        assertTrue(again.contains("MainActivity.tox_group_send_custom_packet(group_number,1,pkt,pkt.length)"));
        assertFalse(again.contains("custom_private_packet"));
        // Only a pass that sent all of them vouches for anyone.
        assertBefore(again, "if(sent<own.size()){return;}", "GROUP_TOMBSTONES.noteOwnDeletesResent(groupId,online,own,");
    }

    @Test
    public void a_session_reads_them_again_and_a_deleted_group_forgets_them()
    {
        final String start = code("TrifaToxService.java");
        final int forget = start.indexOf("HelperMessageDelete.forgetGroupDeletesRead();");
        assertTrue(forget > 0);
        assertTrue(forget < start.indexOf("is_tox_started = true;", forget));
        assertTrue(bare(body(code("HelperGroup.java"), "static void delete_group(final String group_identifier)"))
                           .contains("HelperMessageDelete.forgetGroupDeletes(group_identifier);"));
    }

    // ---- sources ----

    private static void assertBefore(final String code, final String first, final String then)
    {
        final int at = code.indexOf(bare(first));
        assertTrue(first + " not found", at >= 0);
        final int later = code.indexOf(bare(then), at);
        assertTrue(then + " does not follow " + first, later > at);
    }

    private static String code(final String name)
    {
        return AntispamOnRequestTest.code(read(JAVA + name));
    }

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

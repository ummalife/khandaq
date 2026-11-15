package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57): read receipts, protocol v1 (scratchpad SPEC "Khandaq read receipts"), the same on iOS and
 * the desktop. The packet byte for byte on the SPEC's two vectors; the watermark that only moves forward and
 * survives a restart; what turns seen and what never does; the reader that sends only for the conversation on
 * screen, keeps an anchor for an offline friend, and coalesces. The parts that need toxcore, the database or a
 * screen are pinned in the source, as LongTextSourceTest does.
 */
public class HelperMessageSeenTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";

    private static final String FRIEND = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90";
    private static final String OTHER = "0F1E2D3C4B5A69788796A5B4C3D2E1F00F1E2D3C4B5A69788796A5B4C3D2E1F0";
    private static final String ID_A = "00112233445566778899AABBCCDDEEFF0123456789ABCDEFFEDCBA9876543210";
    private static final String ID_B = "B0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0C1C2C3C4C5C6C7C8C9CACBCCCDCECF";
    private static final String ID_C = "C0C1C2C3C4C5C6C7C8C9CACBCCCDCECFD0D1D2D3D4D5D6D7D8D9DADBDCDDDEDF";
    private static final long NOW_SEC = 1700000000L;

    private MapStore store;
    private FakePort port;
    private boolean onlineBefore;

    @Before
    public void setUp()
    {
        store = new MapStore();
        port = new FakePort();
        HelperMessageSeen.resetForTest(store, port);
        onlineBefore = AirTranscript.personOnline;
    }

    @After
    public void tearDown()
    {
        HelperMessageSeen.resetForTest(new MapStore(), new FakePort());
        AirTranscript.personOnline = onlineBefore;
    }

    // ---- the packet ----

    @Test
    public void the_seen_packet_is_189_in_the_custom_range_and_41_bytes()
    {
        assertEquals(189, HelperMessageSeen.PKT_MSG_SEEN);
        assertTrue(HelperMessageSeen.PKT_MSG_SEEN >= 160 && HelperMessageSeen.PKT_MSG_SEEN <= 191);
        assertNotEquals(HelperMessageEdit.PKT_MSG_EDIT, HelperMessageSeen.PKT_MSG_SEEN);
        assertNotEquals(HelperMessageDelete.PKT_MSG_DELETE, HelperMessageSeen.PKT_MSG_SEEN);
        assertNotEquals(HelperMessageReaction.PKT_MSG_REACTION, HelperMessageSeen.PKT_MSG_SEEN);
        assertEquals(41, HelperMessageSeen.PKT_LEN);
        assertEquals(1, HelperMessageSeen.ANCHOR_TEXT);
        assertEquals(2, HelperMessageSeen.ANCHOR_FILE);
    }

    @Test
    public void the_text_vector_of_the_spec_byte_for_byte()
    {
        final byte[] expected = hex("BD 4B 51 01 01 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14 15 "
                                    + "16 17 18 19 1A 1B 1C 1D 1E 1F 65 53 F1 00");
        final String id = "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F";
        assertArrayEquals(expected, HelperMessageSeen.buildPacket(HelperMessageSeen.ANCHOR_TEXT, id, 0x6553F100L));
        // The case of the id does not matter on the way out.
        assertArrayEquals(expected, HelperMessageSeen.buildPacket(1, id.toLowerCase(Locale.ROOT), 0x6553F100L));

        final HelperMessageSeen.Anchor anchor = HelperMessageSeen.parsePacket(expected, expected.length);
        assertNotNull(anchor);
        assertEquals(HelperMessageSeen.ANCHOR_TEXT, anchor.type);
        assertEquals(id, anchor.idHex);
        assertEquals(0x6553F100L, anchor.seenSec);
        assertNull(HelperMessageSeen.dropReason(expected, expected.length));
    }

    @Test
    public void the_file_vector_of_the_spec_byte_for_byte()
    {
        final byte[] expected = hex("BD 4B 51 01 02 FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF "
                                    + "FF FF FF FF FF FF FF FF FF FF 00 00 00 00");
        final String id = repeat("FF", 32);
        assertArrayEquals(expected, HelperMessageSeen.buildPacket(HelperMessageSeen.ANCHOR_FILE, id, 0L));

        final HelperMessageSeen.Anchor anchor = HelperMessageSeen.parsePacket(expected, expected.length);
        assertNotNull(anchor);
        assertEquals(HelperMessageSeen.ANCHOR_FILE, anchor.type);
        assertEquals(id, anchor.idHex);
        assertEquals(0L, anchor.seenSec);
    }

    @Test
    public void the_seen_time_is_an_unsigned_big_endian_u32()
    {
        final byte[] packet = HelperMessageSeen.buildPacket(1, ID_A, 0xFFFFFFFFL);
        assertNotNull(packet);
        assertArrayEquals(hex("FF FF FF FF"), Arrays.copyOfRange(packet, 37, 41));
        assertEquals(0xFFFFFFFFL, HelperMessageSeen.parsePacket(packet, 41).seenSec);
        assertArrayEquals(hex("65 53 F1 00"), Arrays.copyOfRange(HelperMessageSeen.buildPacket(2, ID_A, NOW_SEC), 37, 41));
    }

    @Test
    public void the_reader_drops_any_other_length_id_magic_version_or_anchor_type()
    {
        final byte[] good = HelperMessageSeen.buildPacket(1, ID_A, NOW_SEC);
        assertNotNull(HelperMessageSeen.parsePacket(good, 41));

        assertDropped(Arrays.copyOf(good, 40), 40);
        assertDropped(Arrays.copyOf(good, 42), 42);
        assertDropped(good, 40);
        assertDropped(Arrays.copyOf(good, 40), 41);
        assertDropped(null, 41);
        assertDropped(new byte[0], 0);

        assertDropped(with(good, 0, 188), 41);
        assertDropped(with(good, 1, 'k'), 41);
        assertDropped(with(good, 2, 'R'), 41);
        assertDropped(with(good, 3, 0), 41);
        assertDropped(with(good, 3, 2), 41);
        assertDropped(with(good, 4, 0), 41);
        assertDropped(with(good, 4, 3), 41);
        assertDropped(with(good, 4, 255), 41);
    }

    @Test
    public void nothing_but_a_text_or_file_anchor_of_32_bytes_is_built()
    {
        assertNull(HelperMessageSeen.buildPacket(0, ID_A, NOW_SEC));
        assertNull(HelperMessageSeen.buildPacket(3, ID_A, NOW_SEC));
        assertNull(HelperMessageSeen.buildPacket(1, ID_A.substring(2), NOW_SEC));
        assertNull(HelperMessageSeen.buildPacket(1, ID_A + "00", NOW_SEC));
        assertNull(HelperMessageSeen.buildPacket(1, "zz" + ID_A.substring(2), NOW_SEC));
        assertNull(HelperMessageSeen.buildPacket(1, ID_A.substring(1), NOW_SEC));
        assertNull(HelperMessageSeen.buildPacket(1, "", NOW_SEC));
        assertNull(HelperMessageSeen.buildPacket(2, null, NOW_SEC));
    }

    // ---- the watermark ----

    @Test
    public void the_watermark_only_moves_forward_and_survives_a_restart()
    {
        final HelperMessageSeen.Watermarks marks = new HelperMessageSeen.Watermarks(store);
        assertEquals(0L, marks.get(FRIEND));
        assertTrue(marks.advance(FRIEND, 2000L));
        assertEquals(2000L, marks.get(FRIEND));
        assertFalse(marks.advance(FRIEND, 1000L));
        assertFalse(marks.advance(FRIEND, 2000L));
        assertEquals(2000L, marks.get(FRIEND));
        assertTrue(marks.advance(FRIEND.toLowerCase(Locale.ROOT), 3000L));
        assertEquals(3000L, marks.get(FRIEND));
        assertEquals(0L, marks.get(OTHER));
        assertEquals("3000", store.values.get(HelperMessageSeen.WATERMARK_KEY_PREFIX + FRIEND));

        // A new process reads it back.
        final HelperMessageSeen.Watermarks restarted = new HelperMessageSeen.Watermarks(store);
        assertEquals(3000L, restarted.get(FRIEND));
        assertFalse(restarted.advance(FRIEND, 2500L));
        assertEquals(3000L, restarted.get(FRIEND));
    }

    @Test
    public void the_watermark_waits_for_the_database_and_is_forgotten_with_the_contact()
    {
        store.values.put(HelperMessageSeen.WATERMARK_KEY_PREFIX + FRIEND, "5000");
        store.ready = false;
        final HelperMessageSeen.Watermarks marks = new HelperMessageSeen.Watermarks(store);
        assertEquals(0L, marks.get(FRIEND));
        assertFalse(marks.advance(FRIEND, 9000L));
        assertEquals("5000", store.values.get(HelperMessageSeen.WATERMARK_KEY_PREFIX + FRIEND));

        store.ready = true;
        assertEquals(5000L, marks.get(FRIEND));
        marks.forget(FRIEND);
        assertEquals(0L, marks.get(FRIEND));
        assertFalse(store.values.containsKey(HelperMessageSeen.WATERMARK_KEY_PREFIX + FRIEND));

        assertEquals(0L, marks.get("-1"));
        assertEquals(0L, marks.get(FavoritesChatHelper.CHAT_ID));
        assertFalse(marks.advance(FavoritesChatHelper.CHAT_ID, 1000L));
        store.values.put(HelperMessageSeen.WATERMARK_KEY_PREFIX + OTHER, "not a number");
        assertEquals(0L, marks.get(OTHER));
    }

    @Test
    public void the_watermark_covers_what_was_sent_at_or_before_it()
    {
        assertFalse(HelperMessageSeen.isCovered(1000L, 0L));
        assertFalse(HelperMessageSeen.isCovered(0L, 1000L));
        assertTrue(HelperMessageSeen.isCovered(999L, 1000L));
        assertTrue(HelperMessageSeen.isCovered(1000L, 1000L));
        assertFalse(HelperMessageSeen.isCovered(1001L, 1000L));
    }

    @Test
    public void the_anchor_resolves_to_our_own_item_only()
    {
        final HelperMessageSeen.Anchor text = new HelperMessageSeen.Anchor(1, ID_A, NOW_SEC);
        final HelperMessageSeen.Anchor file = new HelperMessageSeen.Anchor(2, ID_A, NOW_SEC);

        final Message theirs = text(0, FRIEND, ID_A, 1000L, 1L);
        final Message toSomeoneElse = text(1, OTHER, ID_A, 1000L, 2L);
        final Message ourFile = file(1, FRIEND, ID_A, 1500L, 3L);
        assertNull(HelperMessageSeen.ownItem(Arrays.asList(theirs, toSomeoneElse, ourFile), FRIEND, text));

        final Message ours = text(1, FRIEND, ID_A.toLowerCase(Locale.ROOT), 1200L, 4L);
        final Message oursLater = text(1, FRIEND, ID_A, 1300L, 9L);
        assertSame(ours, HelperMessageSeen.ownItem(Arrays.asList(theirs, ours), FRIEND, text));
        assertSame(oursLater, HelperMessageSeen.ownItem(Arrays.asList(ours, oursLater, theirs), FRIEND, text));
        assertSame(ourFile, HelperMessageSeen.ownItem(Arrays.asList(ours, ourFile), FRIEND.toLowerCase(Locale.ROOT),
                                                      file));
        assertNull(HelperMessageSeen.ownItem(Arrays.asList(ours, oursLater),
                                             FRIEND, new HelperMessageSeen.Anchor(1, ID_B, NOW_SEC)));
        assertNull(HelperMessageSeen.ownItem(null, FRIEND, text));
        assertNull(HelperMessageSeen.ownItem(Arrays.asList(ours), null, text));
        assertNull(HelperMessageSeen.ownItem(Arrays.asList(ours), FRIEND, null));
    }

    // ---- what turns seen ----

    @Test
    public void only_sent_and_delivered_turn_seen()
    {
        for (final AirConversationModel.EntryStatus status : AirConversationModel.EntryStatus.values())
        {
            assertSame(status, status.seenIf(false));
        }
        assertSame(AirConversationModel.EntryStatus.SEEN, AirConversationModel.EntryStatus.SENT.seenIf(true));
        assertSame(AirConversationModel.EntryStatus.SEEN, AirConversationModel.EntryStatus.DELIVERED.seenIf(true));
        assertSame(AirConversationModel.EntryStatus.SEEN, AirConversationModel.EntryStatus.SEEN.seenIf(true));
        assertSame(AirConversationModel.EntryStatus.TRANSIT, AirConversationModel.EntryStatus.TRANSIT.seenIf(true));
        assertSame(AirConversationModel.EntryStatus.SENDING, AirConversationModel.EntryStatus.SENDING.seenIf(true));
        assertSame(AirConversationModel.EntryStatus.FAILED, AirConversationModel.EntryStatus.FAILED.seenIf(true));
        assertSame(AirConversationModel.EntryStatus.NONE, AirConversationModel.EntryStatus.NONE.seenIf(true));
        assertEquals("check-double", AirConversationModel.EntryStatus.SEEN.iconName());

        for (final AirModel.Delivery delivery : AirModel.Delivery.values())
        {
            assertSame(delivery, delivery.seenIf(false));
        }
        assertSame(AirModel.Delivery.SEEN, AirModel.Delivery.SENT.seenIf(true));
        assertSame(AirModel.Delivery.SEEN, AirModel.Delivery.DELIVERED.seenIf(true));
        assertSame(AirModel.Delivery.TRANSIT, AirModel.Delivery.TRANSIT.seenIf(true));
        // The list's rule is the conversation's.
        assertSame(AirModel.Delivery.DELIVERED, AirModel.Delivery.ofDirectText(true, false, 7L, true));
        assertSame(AirModel.Delivery.TRANSIT, AirModel.Delivery.ofDirectText(false, false, 7L, false));
    }

    @Test
    public void the_conversation_shows_seen_up_to_the_watermark_and_never_for_what_waits_or_failed()
    {
        store.values.put(HelperMessageSeen.WATERMARK_KEY_PREFIX + FRIEND, "2000");
        HelperMessageSeen.resetForTest(store, port);
        AirTranscript.personOnline = true;

        final Message delivered = text(1, FRIEND, ID_A, 1500L, 1L);
        delivered.read = true;
        delivered.message_id = 11L;
        assertSame(AirConversationModel.EntryStatus.SEEN, AirTranscript.statusOf(delivered));

        final Message sentNoReceipt = text(1, FRIEND, ID_B, 2000L, 2L);
        sentNoReceipt.message_id = 12L;
        assertSame(AirConversationModel.EntryStatus.SEEN, AirTranscript.statusOf(sentNoReceipt));

        final Message newer = text(1, FRIEND, ID_C, 2001L, 3L);
        newer.read = true;
        newer.message_id = 13L;
        assertSame(AirConversationModel.EntryStatus.DELIVERED, AirTranscript.statusOf(newer));

        final Message leaving = text(1, FRIEND, "", 1000L, 4L);
        leaving.message_id = -1L;
        assertSame(AirConversationModel.EntryStatus.SENDING, AirTranscript.statusOf(leaving));

        final Message failed = text(1, FRIEND, ID_A, 1000L, 5L);
        failed.message_id = 14L;
        failed.send_retries = MessageDeliveryWatchdog.MAX_SEND_RETRIES;
        assertSame(AirConversationModel.EntryStatus.FAILED, AirTranscript.statusOf(failed));

        AirTranscript.personOnline = false;
        assertSame(AirConversationModel.EntryStatus.TRANSIT, AirTranscript.statusOf(sentNoReceipt));
        assertSame(AirConversationModel.EntryStatus.SEEN, AirTranscript.statusOf(delivered));

        // Theirs, Saved and the system's rows carry no mark at all.
        assertSame(AirConversationModel.EntryStatus.NONE, AirTranscript.statusOf(text(0, FRIEND, ID_A, 1000L, 6L)));
        final Message saved = text(1, FavoritesChatHelper.CHAT_ID, "", 1000L, 7L);
        saved.read = true;
        assertSame(AirConversationModel.EntryStatus.NONE, AirTranscript.statusOf(saved));
        assertFalse(HelperMessageSeen.coveredBySeen(saved));
        assertSame(AirConversationModel.EntryStatus.NONE, AirTranscript.statusOf(text(1, "-1", "", 1000L, 8L)));
        // Another person's watermark covers nothing of ours to this one.
        final Message toOther = text(1, OTHER, ID_A, 1000L, 9L);
        toOther.read = true;
        assertSame(AirConversationModel.EntryStatus.DELIVERED, AirTranscript.statusOf(toOther));

        final Message ourFile = file(1, FRIEND, ID_A, 1800L, 10L);
        ourFile.state = TOX_FILE_CONTROL_CANCEL.value;
        ourFile.filedb_id = 3L;
        assertTrue(HelperMessageSeen.coveredBySeen(ourFile));
        assertFalse(HelperMessageSeen.coveredBySeen(file(0, FRIEND, ID_A, 1800L, 11L)));
    }

    @Test
    public void a_group_message_never_shows_seen()
    {
        final GroupMessage waiting = group(null, 0);
        final GroupMessage sent = group("0A0B0C0D", 0);
        final GroupMessage confirmed = group("0A0B0C0D", 2);
        for (final GroupMessage m : Arrays.asList(waiting, sent, confirmed))
        {
            for (final boolean connected : new boolean[]{true, false})
            {
                assertNotEquals(AirConversationModel.EntryStatus.SEEN, AirTranscript.groupStatus(m, connected));
            }
        }
        assertSame(AirConversationModel.EntryStatus.DELIVERED, AirTranscript.groupStatus(confirmed, true));
        for (final ChatTransferProgressHelper.Phase phase : ChatTransferProgressHelper.Phase.values())
        {
            assertNotEquals(AirModel.Delivery.SEEN, AirModel.Delivery.ofGroupFile(phase));
        }
    }

    // ---- the reader ----

    @Test
    public void the_newest_anchored_item_goes_once_and_an_older_one_never()
    {
        final HelperMessageSeen.Reader reader = new HelperMessageSeen.Reader(store, port);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_A, 1000L, 1L)), NOW_SEC);
        assertEquals(1, port.sent.size());
        assertArrayEquals(HelperMessageSeen.buildPacket(1, ID_A, NOW_SEC), port.sent.get(0));

        port.advanceTo(port.clock + 5000L);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_A, 1000L, 1L)), NOW_SEC + 5);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_B, 900L, 2L)), NOW_SEC + 5);
        assertEquals(1, port.sent.size());

        reader.offer(FRIEND, candidate(file(0, FRIEND, ID_C, 1000L, 3L)), NOW_SEC + 6);
        assertEquals(2, port.sent.size());
        assertArrayEquals(HelperMessageSeen.buildPacket(2, ID_C, NOW_SEC + 6), port.sent.get(1));
        assertTrue(store.values.isEmpty());
    }

    @Test
    public void an_offline_friend_keeps_the_anchor_which_goes_on_connect_even_after_a_restart()
    {
        port.online = false;
        final HelperMessageSeen.Reader reader = new HelperMessageSeen.Reader(store, port);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_A, 1000L, 1L)), NOW_SEC);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_B, 2000L, 2L)), NOW_SEC + 1);
        assertTrue(port.sent.isEmpty());
        assertEquals(0, port.attempts);
        assertEquals("1:" + ID_B + ":" + (NOW_SEC + 1), store.values.get(HelperMessageSeen.PENDING_KEY_PREFIX + FRIEND));

        // The app starts again; the friend connects.
        final FakePort later = new FakePort();
        final HelperMessageSeen.Reader restarted = new HelperMessageSeen.Reader(store, later);
        restarted.onFriendOnline(OTHER);
        assertTrue(later.sent.isEmpty());
        restarted.onFriendOnline(FRIEND);
        assertEquals(1, later.sent.size());
        assertArrayEquals(HelperMessageSeen.buildPacket(1, ID_B, NOW_SEC + 1), later.sent.get(0));
        assertFalse(store.values.containsKey(HelperMessageSeen.PENDING_KEY_PREFIX + FRIEND));

        // Once it went, a second connect sends nothing.
        later.advanceTo(later.clock + 5000L);
        restarted.onFriendOnline(FRIEND);
        assertEquals(1, later.sent.size());
    }

    @Test
    public void one_packet_a_second_and_a_later_anchor_replaces_the_one_that_waits()
    {
        final HelperMessageSeen.Reader reader = new HelperMessageSeen.Reader(store, port);
        final long start = port.clock;
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_A, 1000L, 1L)), NOW_SEC);
        assertEquals(1, port.sent.size());

        port.advanceTo(start + 100L);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_B, 2000L, 2L)), NOW_SEC);
        port.advanceTo(start + 300L);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_C, 3000L, 3L)), NOW_SEC + 1);
        assertEquals(1, port.sent.size());
        assertEquals(1, port.timers.size());

        port.advanceTo(start + 999L);
        assertEquals(1, port.sent.size());
        port.advanceTo(start + 1000L);
        assertEquals(2, port.sent.size());
        assertArrayEquals(HelperMessageSeen.buildPacket(1, ID_C, NOW_SEC + 1), port.sent.get(1));
        assertEquals(start + 1000L, port.sentAt.get(1).longValue());
        assertTrue(port.timers.isEmpty());

        // Other friends have their own second.
        reader.offer(OTHER, candidate(text(0, OTHER, ID_A, 1000L, 4L)), NOW_SEC + 1);
        assertEquals(3, port.sent.size());
    }

    @Test
    public void a_refused_send_is_tried_again_a_few_times_then_waits_for_the_friend()
    {
        port.accept = false;
        final HelperMessageSeen.Reader reader = new HelperMessageSeen.Reader(store, port);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_A, 1000L, 1L)), NOW_SEC);
        assertEquals(1, port.attempts);
        assertNotNull(store.values.get(HelperMessageSeen.PENDING_KEY_PREFIX + FRIEND));

        port.advanceTo(port.clock + 60_000L);
        assertEquals(1 + HelperMessageSeen.MAX_RETRIES, port.attempts);
        assertTrue(port.timers.isEmpty());
        assertTrue(port.sent.isEmpty());

        port.accept = true;
        reader.onFriendOnline(FRIEND);
        assertEquals(1, port.sent.size());
        assertArrayEquals(HelperMessageSeen.buildPacket(1, ID_A, NOW_SEC), port.sent.get(0));
        assertFalse(store.values.containsKey(HelperMessageSeen.PENDING_KEY_PREFIX + FRIEND));
    }

    @Test
    public void a_removed_contact_takes_what_waited_for_it_along()
    {
        final HelperMessageSeen.Reader reader = new HelperMessageSeen.Reader(store, port);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_A, 1000L, 1L)), NOW_SEC);
        reader.offer(FRIEND, candidate(text(0, FRIEND, ID_B, 2000L, 2L)), NOW_SEC);
        assertEquals(1, port.timers.size());
        reader.forget(FRIEND);
        port.advanceTo(port.clock + 5000L);
        assertEquals(1, port.sent.size());

        port.online = false;
        reader.offer(OTHER, candidate(text(0, OTHER, ID_C, 1000L, 3L)), NOW_SEC);
        assertTrue(store.values.containsKey(HelperMessageSeen.PENDING_KEY_PREFIX + OTHER));
        reader.forget(OTHER);
        assertFalse(store.values.containsKey(HelperMessageSeen.PENDING_KEY_PREFIX + OTHER));
        port.online = true;
        reader.onFriendOnline(OTHER);
        assertEquals(1, port.sent.size());
    }

    @Test
    public void nothing_goes_for_saved_the_system_or_an_item_without_an_anchor()
    {
        final HelperMessageSeen.Reader reader = new HelperMessageSeen.Reader(store, port);
        final HelperMessageSeen.Candidate c = candidate(text(0, FRIEND, ID_A, 1000L, 1L));
        reader.offer(FavoritesChatHelper.CHAT_ID, c, NOW_SEC);
        reader.offer("-1", c, NOW_SEC);
        reader.offer(null, c, NOW_SEC);
        reader.offer(FRIEND, null, NOW_SEC);
        assertEquals(0, port.attempts);

        // A text of a client without msgV3 ids, a call note and our own items have no anchor to send.
        final Message noId = text(0, FRIEND, "", 3000L, 2L);
        final Message callNote = text(0, FRIEND, ID_B, 2500L, 3L);
        callNote.resend_count = HelperCall.LOCAL_CALL_LOG_RESEND_COUNT;
        callNote.message_id = -1L;
        final Message ours = text(1, FRIEND, ID_C, 2400L, 4L);
        final Message file = file(0, FRIEND, ID_C.toLowerCase(Locale.ROOT), 2000L, 5L);
        final Message older = text(0, FRIEND, ID_A, 1000L, 6L);
        assertNull(HelperMessageSeen.Candidate.of(noId));
        assertNull(HelperMessageSeen.Candidate.of(callNote));
        assertNull(HelperMessageSeen.Candidate.of(ours));
        assertNull(HelperMessageSeen.Candidate.of(null));

        final HelperMessageSeen.Candidate newest =
                HelperMessageSeen.newestCandidate(Arrays.asList(noId, callNote, ours, file, older));
        assertNotNull(newest);
        assertEquals(HelperMessageSeen.ANCHOR_FILE, newest.type);
        assertEquals(ID_C, newest.idHex);
        assertNull(HelperMessageSeen.newestCandidate(Arrays.asList(noId, callNote, ours)));
        assertNull(HelperMessageSeen.newestCandidate(null));
    }

    @Test
    public void newer_follows_the_order_of_the_conversation()
    {
        final HelperMessageSeen.Candidate base = candidate(text(0, FRIEND, ID_A, 1000L, 5L));
        final Message sameTimeLaterMs = text(0, FRIEND, ID_B, 1000L, 1L);
        sameTimeLaterMs.sent_timestamp_ms = 3L;
        assertTrue(candidate(text(0, FRIEND, ID_B, 1001L, 1L)).isAfter(base));
        assertTrue(candidate(sameTimeLaterMs).isAfter(base));
        assertTrue(candidate(text(0, FRIEND, ID_B, 1000L, 6L)).isAfter(base));
        assertFalse(candidate(text(0, FRIEND, ID_B, 1000L, 5L)).isAfter(base));
        assertFalse(candidate(text(0, FRIEND, ID_B, 999L, 9L)).isAfter(base));
    }

    // ---- when the reader sends ----

    @Test
    public void seen_needs_the_conversation_resumed_on_a_lit_unlocked_screen()
    {
        assertTrue(HelperMessageSeen.maySee(true, true, false, false));
        for (int bits = 0; bits < 16; bits++)
        {
            final boolean shown = (bits & 1) != 0;
            final boolean interactive = (bits & 2) != 0;
            final boolean keyguardLocked = (bits & 4) != 0;
            final boolean appLockUp = (bits & 8) != 0;
            assertEquals(shown && interactive && !keyguardLocked && !appLockUp,
                         HelperMessageSeen.maySee(shown, interactive, keyguardLocked, appLockUp));
        }
    }

    @Test
    public void the_conversation_on_screen_is_known_by_its_key_and_saved_is_never_one()
    {
        assertFalse(HelperMessageSeen.isShown(FRIEND));
        assertEquals(FRIEND, HelperMessageSeen.markShown(FRIEND.toLowerCase(Locale.ROOT)));
        assertTrue(HelperMessageSeen.isShown(FRIEND));
        assertFalse(HelperMessageSeen.isShown(OTHER));

        HelperMessageSeen.onChatHidden(OTHER);
        assertTrue(HelperMessageSeen.isShown(FRIEND));
        HelperMessageSeen.onChatHidden(FRIEND);
        assertFalse(HelperMessageSeen.isShown(FRIEND));

        assertNull(HelperMessageSeen.markShown(FavoritesChatHelper.CHAT_ID));
        assertNull(HelperMessageSeen.markShown(null));
        assertFalse(HelperMessageSeen.isShown(FavoritesChatHelper.CHAT_ID));
    }

    // ---- the wiring, in the source ----

    @Test
    public void packet_189_is_registered_dispatched_and_flushed_when_the_friend_connects() throws Exception
    {
        final String main = read(JAVA + "MainActivity.java");
        final String register = method(main, "private void register_khandaq_lossless_pktids()");
        assertTrue(register.contains("for (int pktid = 184; pktid <= HelperMessageSeen.PKT_MSG_SEEN; pktid++)"));
        assertTrue(register.contains("tox_callback_friend_lossless_packet_per_pktid(pktid)"));

        final String dispatch = method(main, "static void android_tox_callback_friend_lossless_packet_cb_method(");
        assertTrue(bare(dispatch).contains(bare(
                "else if (data[0] == (byte) HelperMessageSeen.PKT_MSG_SEEN) { // KHANDAQ (0.2.57): the person has seen "
                + "our 1:1 items up to the anchored one\n"
                + "HelperMessageSeen.handleIncomingFriendSeen(friend_number, data, (int) length); }")));

        final String online = method(main, "static void android_tox_callback_friend_connection_status_cb_method(");
        final int reactions = online.indexOf("HelperMessageReaction.flushPendingReactionsForFriend(friend_number);");
        final int seen = online.indexOf("HelperMessageSeen.onFriendOnline(friend_number);");
        assertTrue(reactions > 0);
        assertTrue(seen > reactions);
        assertTrue(online.lastIndexOf("// ******** friend just came online ********", seen) >= 0);
    }

    @Test
    public void only_the_resumed_conversation_of_one_person_looks_for_what_is_seen() throws Exception
    {
        final String panel = read(JAVA + "AirChatPanel.java");
        assertTrue(bare(method(panel, "    void onResume()")).contains(
                bare("if (!saved) { HelperMessageSeen.onChatShown(activity, publicKey); }")));
        assertTrue(bare(method(panel, "    void onWindowFocused()")).contains(
                bare("if (resumed && !saved) { HelperMessageSeen.onChatShown(activity, publicKey); }")));
        assertTrue(method(panel, "    void onPause()").contains("HelperMessageSeen.onChatHidden(publicKey);"));

        final String activity = read(JAVA + "MessageListActivity.java");
        assertTrue(bare(method(activity, "public void onWindowFocusChanged(final boolean hasFocus)")).contains(
                bare("if (hasFocus && airPanel != null) { airPanel.onWindowFocused(); }")));

        final String insert = method(read(JAVA + "HelperMessage.java"),
                                     "static long insert_into_message_db(final Message m, final boolean");
        final int stored = insert.indexOf("row_id = orma.insertIntoMessage(m);");
        final int look = insert.indexOf("HelperMessageSeen.onIncomingItem(m);");
        assertTrue(stored > 0);
        assertTrue(look > stored);

        final String seen = read(JAVA + "HelperMessageSeen.java");
        final String lookNow = method(seen, "private static void lookNow(final String key)");
        assertTrue(lookNow.contains("power.isInteractive()"));
        assertTrue(lookNow.contains("keyguard.isKeyguardLocked()"));
        assertTrue(lookNow.contains("AppLockHelper.isGateUp(context)"));
        assertTrue(lookNow.contains("key.equals(shownKey)"));
        // A contact only: never a request, the relay or our own key.
        assertTrue(lookNow.contains("HelperFriend.main_get_friend(key)"));
        assertTrue(lookNow.contains("HelperRelay.is_any_relay(key)"));
        assertTrue(lookNow.contains("HelperFriend.is_own_public_key(key)"));
        assertTrue(lookNow.contains(".directionEq(0)"));
    }

    @Test
    public void groups_neither_send_nor_show_seen() throws Exception
    {
        for (final String name : new String[]{"GroupMessageListActivity.java", "GroupMessageListFragment.java",
                                              "GroupMessagelistAdapter.java", "AirGroupPanel.java", "HelperGroup.java",
                                              "GroupMessageListHolder_text_outgoing_read.java",
                                              "GroupMessageListHolder_file_outgoing_state_cancel.java",
                                              "NgcGroupFileTransfer.java", "AirGroupModel.java"})
        {
            final String source = read(JAVA + name);
            assertFalse(name, source.contains("HelperMessageSeen"));
            assertFalse(name, source.contains("seenIf("));
        }
        final String transcript = read(JAVA + "AirTranscript.java");
        assertTrue(transcript.contains("showMark(views.mark, outgoingStatus(m, entry), m.id, false);"));
        assertFalse(method(transcript, "static AirConversationModel.EntryStatus groupStatus(").contains("seenIf"));
        final String files = read(JAVA + "AirFileEntry.java");
        assertFalse(method(files, "private static AirConversationModel.EntryStatus groupStatus(").contains("SEEN"));
        final String repository = read(JAVA + "AirRepository.java");
        assertFalse(method(repository, "static AirModel.Delivery groupDelivery(").contains("seenIf"));
    }

    @Test
    public void a_removed_contact_forgets_its_watermark_and_waiting_anchor() throws Exception
    {
        final String remove = method(read(JAVA + "FriendListHolder.java"), "static void delete_friend_now(final String");
        assertTrue(remove.contains("HelperMessageSeen.forgetFriend(pubkey);"));
    }

    @Test
    public void seen_is_brand_teal_and_delivered_takes_the_muted_ink_of_the_time() throws Exception
    {
        final String transcript = read(JAVA + "AirTranscript.java");
        final String marks = method(transcript, "    static void setMarkIcon(final ImageView mark, final "
                                                + "AirConversationModel.EntryStatus status,\n");
        assertTrue(bare(marks).contains(bare("case SEEN: icon = R.drawable.kq_ic_check_double; "
                                             + "tint = R.color.khandaq_teal;")));
        assertTrue(bare(marks).contains(bare("case DELIVERED: icon = R.drawable.kq_ic_check_double; "
                                             + "tint = direct ? R.color.kq_ink_3 : R.color.kq_ink;")));
        assertTrue(bare(marks).contains(bare("case SENT: icon = R.drawable.kq_ic_check; "
                                             + "tint = direct ? R.color.kq_ink_3 : R.color.kq_ink;")));
        assertTrue(transcript.contains("showMark(views.mark, status, m.id, true);"));
        assertTrue(read(JAVA + "AirFileEntry.java").contains("AirTranscript.setMarkIcon(v.mark, e.status, e.direct);"));
        // The time beside the mark is in the muted ink, in the conversation and in the list.
        assertEquals("@color/kq_ink_3", colorOf(read(RES + "layout/air_entry_text.xml"), "@+id/date_time"));
        assertEquals("@color/kq_ink_3", colorOf(read(RES + "layout/message_list_ft_outgoing.xml"), "@+id/date_time"));
        assertEquals("@color/kq_ink_3",
                     colorOf(read(RES + "layout/message_list_ft_outgoing_compact.xml"), "@+id/date_time"));
        assertEquals("@color/kq_ink_3", colorOf(read(RES + "layout/air_row.xml"), "@+id/air_row_time"));

        final String adapter = read(JAVA + "AirAdapter.java");
        assertTrue(bare(adapter).contains(bare("case SEEN: icon = R.drawable.kq_ic_check_double; "
                                               + "tint = R.color.khandaq_teal;")));
        assertTrue(bare(adapter).contains(bare(
                "tint = next.kind == AirModel.EntryKind.GROUP ? R.color.kq_ink : R.color.kq_ink_3;")));

        // The hex all three clients match: #029B7D light, #10B98F dark.
        assertTrue(read(RES + "values/colors.xml").contains("<color name=\"khandaq_teal\">#029B7D</color>"));
        assertTrue(read(RES + "values-night/colors.xml").contains("<color name=\"khandaq_teal\">#10B98F</color>"));
    }

    @Test
    public void seen_is_said_in_every_language_the_delivery_words_are() throws Exception
    {
        final Map<String, String> words = new HashMap<>();
        words.put("values", "seen");
        words.put("values-ru", "прочитано");
        words.put("values-ar", "قُرئت");
        words.put("values-zh-rCN", "已读");
        for (final Map.Entry<String, String> word : words.entrySet())
        {
            final String strings = read(RES + word.getKey() + "/strings_air.xml");
            assertTrue(word.getKey(), strings.contains("<string name=\"air_delivery_delivered\">"));
            assertTrue(word.getKey(), strings.contains(
                    "<string name=\"air_delivery_seen\">" + word.getValue() + "</string>"));
        }
        assertTrue(read(JAVA + "AirTranscript.java").contains("context.getString(R.string.air_delivery_seen)"));
        assertTrue(read(JAVA + "AirUi.java").contains("context.getString(R.string.air_delivery_seen)"));
    }

    // ---- helpers ----

    private static void assertDropped(final byte[] data, final int length)
    {
        assertNull(HelperMessageSeen.parsePacket(data, length));
        assertNotNull(HelperMessageSeen.dropReason(data, length));
    }

    private static byte[] with(final byte[] packet, final int index, final int value)
    {
        final byte[] copy = packet.clone();
        copy[index] = (byte) value;
        return copy;
    }

    private static HelperMessageSeen.Candidate candidate(final Message m)
    {
        final HelperMessageSeen.Candidate c = HelperMessageSeen.Candidate.of(m);
        assertNotNull(c);
        return c;
    }

    private static Message text(final int direction, final String friend, final String id, final long sent,
                                final long rowId)
    {
        final Message m = new Message();
        m.id = rowId;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.direction = direction;
        m.tox_friendpubkey = friend;
        m.msg_idv3_hash = id;
        m.sent_timestamp = sent;
        m.text = "hi";
        return m;
    }

    private static Message file(final int direction, final String friend, final String id, final long sent,
                                final long rowId)
    {
        final Message m = new Message();
        m.id = rowId;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
        m.direction = direction;
        m.tox_friendpubkey = friend;
        m.ft_id_anchor_hex = id;
        m.msg_idv3_hash = "";
        m.sent_timestamp = sent;
        m.filedb_id = -1L;
        return m;
    }

    private static GroupMessage group(final String messageIdTox, final int confirmations)
    {
        final GroupMessage m = new GroupMessage();
        m.direction = 1;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.tox_group_peer_pubkey = OTHER;
        m.message_id_tox = messageIdTox;
        m.sync_confirmations = confirmations;
        m.sent_timestamp = 1000L;
        return m;
    }

    private static byte[] hex(final String spaced)
    {
        final String digits = spaced.replace(" ", "");
        final byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String repeat(final String part, final int times)
    {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++)
        {
            out.append(part);
        }
        return out.toString();
    }

    /** The text colour of the view with {@code id} in a layout. */
    private static String colorOf(final String layout, final String id)
    {
        final int view = layout.indexOf("android:id=\"" + id + "\"");
        assertTrue(id, view >= 0);
        final int end = layout.indexOf("/>", view);
        final int color = layout.indexOf("android:textColor=\"", view);
        assertTrue(id, color > view && color < end);
        final int start = color + "android:textColor=\"".length();
        return layout.substring(start, layout.indexOf('"', start));
    }

    private static String bare(final String source)
    {
        return source.replaceAll("\\s+", "");
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
            final File f = new File(root + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }

    /** g_opts, as a map. */
    private static final class MapStore implements HelperMessageSeen.Store
    {
        final Map<String, String> values = new HashMap<>();
        boolean ready = true;

        @Override
        public boolean ready()
        {
            return ready;
        }

        @Override
        public String get(final String key)
        {
            return values.get(key);
        }

        @Override
        public void set(final String key, final String value)
        {
            values.put(key, value);
        }

        @Override
        public void delete(final String key)
        {
            values.remove(key);
        }
    }

    /** toxcore and the clock: what went, when, and the timers, run by hand in their order. */
    private static final class FakePort implements HelperMessageSeen.Port
    {
        boolean online = true;
        boolean accept = true;
        long clock = 50_000L;
        int attempts = 0;
        final List<byte[]> sent = new ArrayList<>();
        final List<Long> sentAt = new ArrayList<>();
        final List<Timer> timers = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        private static final class Timer
        {
            final long at;
            final Runnable work;

            Timer(final long at, final Runnable work)
            {
                this.at = at;
                this.work = work;
            }
        }

        @Override
        public boolean connected(final String publicKey)
        {
            return online;
        }

        @Override
        public boolean send(final String publicKey, final byte[] packet)
        {
            attempts++;
            assertEquals(HelperMessageSeen.PKT_LEN, packet.length);
            if (!accept)
            {
                return false;
            }
            sent.add(packet);
            sentAt.add(clock);
            return true;
        }

        @Override
        public long now()
        {
            return clock;
        }

        @Override
        public void schedule(final Runnable work, final long delayMs)
        {
            timers.add(new Timer(clock + delayMs, work));
        }

        @Override
        public void log(final String line)
        {
            lines.add(line);
        }

        /** Moves the clock to {@code to}, running every timer due by then at its own time. */
        void advanceTo(final long to)
        {
            while (true)
            {
                Timer next = null;
                for (final Timer t : timers)
                {
                    if (t.at <= to && (next == null || t.at < next.at))
                    {
                        next = t;
                    }
                }
                if (next == null)
                {
                    break;
                }
                timers.remove(next);
                clock = Math.max(clock, next.at);
                next.work.run();
            }
            clock = Math.max(clock, to);
        }
    }
}

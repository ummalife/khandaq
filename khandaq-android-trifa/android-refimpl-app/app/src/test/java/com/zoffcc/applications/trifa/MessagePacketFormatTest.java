package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The wire formats of edits, deletes and reactions (redesign stage 3, item 10): friend lossless packets
 * 186, 187 and 188 and the NGC custom packets 0x41, 0x42 and 0x43, byte for byte as the headers of
 * HelperMessageEdit, HelperMessageDelete and HelperMessageReaction describe them. iOS and the desktop
 * read these bytes, so a change here is a protocol change and has to fail loudly.
 */
public class MessagePacketFormatTest
{
    private static final String HASH =
            "00112233445566778899AABBCCDDEEFF0123456789ABCDEFFEDCBA9876543210";
    private static final String AUTHOR =
            "A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0";
    private static final String MESSAGE_ID = "0A0B0C0D";
    /** 1700000000 seconds, 0x6553F100. */
    private static final long TS = 1700000000L;
    private static final byte[] TS_BYTES = {0x65, 0x53, (byte) 0xF1, 0x00};
    private static final byte[] NGC_MAGIC = {0x66, 0x77, (byte) 0x88, 0x11, 0x34, 0x35};
    private static final byte[] KQ = {'K', 'Q', 1};
    /** The largest custom packet toxcore sends, to a friend (lossless) and to a group alike. */
    private static final int TOX_MAX_CUSTOM_PACKET = 1373;

    @Test
    public void the_packet_ids_stay_where_the_other_clients_look_for_them()
    {
        assertEquals(186, HelperMessageEdit.PKT_MSG_EDIT);
        assertEquals(187, HelperMessageDelete.PKT_MSG_DELETE);
        assertEquals(188, HelperMessageReaction.PKT_MSG_REACTION);
        assertEquals(0x41, HelperMessageEdit.NGC_PKT_MSG_EDIT);
        assertEquals(0x42, HelperMessageDelete.NGC_PKT_MSG_DELETE);
        assertEquals(0x43, HelperMessageReaction.NGC_PKT_MSG_REACTION);
    }

    @Test
    public void friend_edit_186_is_id_magic_version_hash_time_and_the_new_text()
    {
        final String text = "Hi é";
        final byte[] packet = HelperMessageEdit.buildFriendEditPacket(HASH, TS, text);
        assertArrayEquals(concat(new byte[]{(byte) 186}, KQ, hex(HASH), TS_BYTES,
                                 text.getBytes(StandardCharsets.UTF_8)), packet);
    }

    @Test
    public void friend_edit_takes_one_to_1300_bytes_of_text_and_a_32_byte_hash_only()
    {
        final String longest = repeat('a', HelperMessageEdit.MAX_EDIT_TEXT_BYTES);
        final byte[] packet = HelperMessageEdit.buildFriendEditPacket(HASH, TS, longest);
        assertNotNull(packet);
        assertEquals(40 + 1300, packet.length);
        assertBelowTheToxLimit(packet);

        assertNull(HelperMessageEdit.buildFriendEditPacket(HASH, TS, ""));
        assertNull(HelperMessageEdit.buildFriendEditPacket(HASH, TS, longest + "a"));
        // Two bytes a letter: 651 of them are 1302 bytes.
        assertNull(HelperMessageEdit.buildFriendEditPacket(HASH, TS, repeat('ж', 651)));
        assertNull(HelperMessageEdit.buildFriendEditPacket(HASH.substring(2), TS, "x"));
        assertNull(HelperMessageEdit.buildFriendEditPacket(null, TS, "x"));
        assertNull(HelperMessageEdit.buildFriendEditPacket("zz" + HASH.substring(2), TS, "x"));
    }

    @Test
    public void group_edit_0x41_is_the_ngc_header_message_id_time_and_the_new_text()
    {
        final byte[] packet = HelperMessageEdit.buildGroupEditPacket(MESSAGE_ID, TS, "ok");
        assertArrayEquals(concat(NGC_MAGIC, new byte[]{1, 0x41}, hex(MESSAGE_ID), TS_BYTES,
                                 "ok".getBytes(StandardCharsets.UTF_8)), packet);

        final byte[] longest = HelperMessageEdit.buildGroupEditPacket(
                MESSAGE_ID, TS, repeat('a', HelperMessageEdit.MAX_EDIT_TEXT_BYTES));
        assertNotNull(longest);
        assertEquals(16 + 1300, longest.length);
        assertBelowTheToxLimit(longest);
        assertNull(HelperMessageEdit.buildGroupEditPacket("0A0B0C", TS, "ok"));
        assertNull(HelperMessageEdit.buildGroupEditPacket(MESSAGE_ID, TS, ""));
    }

    @Test
    public void friend_delete_187_is_forty_bytes_for_a_text_and_forty_one_for_a_file()
    {
        assertArrayEquals(concat(new byte[]{(byte) 187}, KQ, hex(HASH), TS_BYTES),
                          HelperMessageDelete.buildFriendDeletePacket(HASH, TS));
        // A file is anchored on its Tox file id, after the anchor type 2.
        assertArrayEquals(concat(new byte[]{(byte) 187}, KQ, new byte[]{2}, hex(HASH), TS_BYTES),
                          HelperMessageDelete.buildFriendDeleteFilePacket(HASH, TS));

        assertNull(HelperMessageDelete.buildFriendDeletePacket(HASH.substring(2), TS));
        assertNull(HelperMessageDelete.buildFriendDeleteFilePacket(HASH + "00", TS));
    }

    @Test
    public void a_delete_with_an_anchor_that_is_not_hex_is_not_built()
    {
        // It used to become a packet naming some other message.
        final String notHex = "zz" + HASH.substring(2);
        assertNull(HelperMessageDelete.buildFriendDeletePacket(notHex, TS));
        assertNull(HelperMessageDelete.buildFriendDeleteFilePacket(notHex, TS));
        assertNull(HelperMessageDelete.buildGroupDeletePacket("0A0B0Czz", TS));
        assertNull(HelperMessageDelete.buildGroupDeleteFilePacket(notHex, TS));
    }

    @Test
    public void group_delete_0x42_is_sixteen_bytes_for_a_text_and_forty_five_for_a_file()
    {
        assertArrayEquals(concat(NGC_MAGIC, new byte[]{1, 0x42}, hex(MESSAGE_ID), TS_BYTES),
                          HelperMessageDelete.buildGroupDeletePacket(MESSAGE_ID, TS));
        assertArrayEquals(concat(NGC_MAGIC, new byte[]{1, 0x42, 2}, hex(HASH), TS_BYTES),
                          HelperMessageDelete.buildGroupDeleteFilePacket(HASH, TS));

        assertNull(HelperMessageDelete.buildGroupDeletePacket(HASH, TS));
        assertNull(HelperMessageDelete.buildGroupDeleteFilePacket(MESSAGE_ID, TS));
    }

    @Test
    public void friend_reaction_188_carries_anchor_time_action_and_the_emoji_with_its_length()
    {
        final String heart = "❤️";
        final byte[] emoji = heart.getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(concat(new byte[]{(byte) 188}, KQ, new byte[]{1}, hex(HASH), TS_BYTES,
                                 new byte[]{1, (byte) emoji.length}, emoji),
                          HelperMessageReaction.buildFriendReactionPacket(HASH, (byte) 1, TS, heart, true));
        // Removing one's own reaction: action 0, the emoji only informs.
        final byte[] removal = HelperMessageReaction.buildFriendReactionPacket(HASH, (byte) 1, TS, "*", false);
        assertNotNull(removal);
        assertEquals(44, removal.length);
        assertEquals(0, removal[41]);
        assertEquals(1, removal[42]);
        assertEquals('*', removal[43]);
    }

    @Test
    public void a_reaction_holds_one_to_sixteen_bytes_of_emoji()
    {
        final String sixteen = repeat('a', HelperMessageReaction.MAX_EMOJI_BYTES);
        final byte[] longest = HelperMessageReaction.buildFriendReactionPacket(HASH, (byte) 1, TS, sixteen, true);
        assertNotNull(longest);
        assertEquals(43 + 16, longest.length);
        assertNull(HelperMessageReaction.buildFriendReactionPacket(HASH, (byte) 1, TS, sixteen + "a", true));
        assertNull(HelperMessageReaction.buildFriendReactionPacket(HASH, (byte) 1, TS, "", true));
        assertNull(HelperMessageReaction.buildGroupReactionPacket((byte) 1, MESSAGE_ID, AUTHOR, TS,
                                                                  sixteen + "a", true));
    }

    @Test
    public void group_reaction_0x43_pads_a_message_id_anchor_to_32_bytes_and_names_the_author()
    {
        final byte[] emoji = "👍".getBytes(StandardCharsets.UTF_8);
        final byte[] padded = Arrays.copyOf(hex(MESSAGE_ID), 32);
        assertArrayEquals(concat(NGC_MAGIC, new byte[]{1, 0x43, 1}, padded, hex(AUTHOR), TS_BYTES,
                                 new byte[]{1, (byte) emoji.length}, emoji),
                          HelperMessageReaction.buildGroupReactionPacket((byte) 1, MESSAGE_ID, AUTHOR, TS,
                                                                         "👍", true));
        // A file is anchored on its whole 32-byte hash.
        final byte[] file = HelperMessageReaction.buildGroupReactionPacket((byte) 2, HASH, AUTHOR, TS, "*", false);
        assertNotNull(file);
        assertEquals(80, file.length);
        assertArrayEquals(hex(HASH), Arrays.copyOfRange(file, 9, 41));
        assertEquals(0, file[77]);

        assertNull(HelperMessageReaction.buildGroupReactionPacket((byte) 1, "0A0B0C", AUTHOR, TS, "*", true));
        assertNull(HelperMessageReaction.buildGroupReactionPacket((byte) 2, HASH + "00", AUTHOR, TS, "*", true));
        assertNull(HelperMessageReaction.buildGroupReactionPacket((byte) 1, MESSAGE_ID, AUTHOR.substring(2), TS,
                                                                  "*", true));
    }

    @Test
    public void the_time_is_four_bytes_big_endian()
    {
        final byte[] packet = HelperMessageDelete.buildFriendDeletePacket(HASH, 0x01020304L);
        assertNotNull(packet);
        assertArrayEquals(new byte[]{1, 2, 3, 4}, Arrays.copyOfRange(packet, 36, 40));
    }

    private static void assertBelowTheToxLimit(final byte[] packet)
    {
        if (packet.length > TOX_MAX_CUSTOM_PACKET)
        {
            throw new AssertionError(packet.length + " bytes do not fit a Tox custom packet");
        }
    }

    private static byte[] hex(final String text)
    {
        final byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] concat(final byte[]... parts)
    {
        int length = 0;
        for (final byte[] part : parts)
        {
            length += part.length;
        }
        final byte[] out = new byte[length];
        int at = 0;
        for (final byte[] part : parts)
        {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static String repeat(final char c, final int count)
    {
        final char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}

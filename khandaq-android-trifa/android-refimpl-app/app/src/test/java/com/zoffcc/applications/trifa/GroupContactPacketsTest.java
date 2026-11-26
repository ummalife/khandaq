package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;

import static com.zoffcc.applications.trifa.GroupContactPackets.PKT_ACCEPT;
import static com.zoffcc.applications.trifa.GroupContactPackets.PKT_OFFER;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The offer and the accept a member of a group sends to become a contact, byte by byte
 * (DESIGN-group-contacts-private-mentions.md): iOS and the desktop read these same bytes.
 */
public class GroupContactPacketsTest
{
    /** A whole ID with a correct check (the one AirNewContactModelTest and the iOS tests use). */
    private static final String ADDRESS = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5";
    private static final byte[] NONCE = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, (byte) 0xAB};

    private static byte[] address()
    {
        final byte[] address = GroupContactPackets.fromHex(ADDRESS, GroupContactPackets.ADDRESS_BYTES);
        assertNotNull(address);
        return address;
    }

    private static byte[] offer()
    {
        final byte[] packet = GroupContactPackets.build(PKT_OFFER, address(), NONCE);
        assertNotNull(packet);
        return packet;
    }

    @Test
    public void an_offer_is_the_magic_the_version_the_kind_the_id_and_the_nonce()
    {
        final byte[] packet = offer();
        assertEquals(54, packet.length);
        assertEquals(GroupContactPackets.LENGTH, packet.length);
        assertArrayEquals(new byte[]{0x66, 0x77, (byte) 0x88, 0x11, 0x34, 0x35, 0x01, 0x61}, Arrays.copyOf(packet, 8));
        assertArrayEquals(address(), Arrays.copyOfRange(packet, 8, 46));
        assertArrayEquals(NONCE, Arrays.copyOfRange(packet, 46, 54));
        final byte[] accept = GroupContactPackets.build(PKT_ACCEPT, address(), NONCE);
        assertNotNull(accept);
        assertEquals(0x62, accept[7]);
    }

    @Test
    public void what_is_built_reads_back()
    {
        for (final byte kind : new byte[]{PKT_OFFER, PKT_ACCEPT})
        {
            final byte[] packet = GroupContactPackets.build(kind, address(), NONCE);
            assertNotNull(packet);
            assertTrue(GroupContactPackets.isContactPacket(packet, packet.length));
            final GroupContactPackets.Packet parsed = GroupContactPackets.parse(packet, packet.length);
            assertNotNull(parsed);
            assertEquals(kind == PKT_OFFER, parsed.isOffer());
            assertEquals(ADDRESS, parsed.addressHex());
            assertEquals(ADDRESS.substring(0, 64), parsed.publicKeyHex());
            assertEquals("01020304050607ab", parsed.nonceHex());
        }
    }

    @Test
    public void any_other_packet_is_dropped()
    {
        final byte[] good = offer();
        assertNull(GroupContactPackets.parse(good, 53));
        assertNull(GroupContactPackets.parse(Arrays.copyOf(good, 55), 55));
        assertNull(GroupContactPackets.parse(Arrays.copyOf(good, 40), 54));
        assertNull(GroupContactPackets.parse(null, 54));

        byte[] bad = good.clone();
        bad[0] = 0x67;
        assertNull(GroupContactPackets.parse(bad, bad.length));
        assertFalse(GroupContactPackets.isContactPacket(bad, bad.length));

        bad = good.clone();
        bad[6] = 0x02;
        assertNull(GroupContactPackets.parse(bad, bad.length));
        assertFalse(GroupContactPackets.isContactPacket(bad, bad.length));

        // 0x41 is an edit: the same magic and version, another packet.
        bad = good.clone();
        bad[7] = 0x41;
        assertNull(GroupContactPackets.parse(bad, bad.length));
        assertFalse(GroupContactPackets.isContactPacket(bad, bad.length));

        // Ours by its header, but the ID inside does not hold its check: dropped.
        bad = good.clone();
        bad[45] ^= 0x01;
        assertTrue(GroupContactPackets.isContactPacket(bad, bad.length));
        assertNull(GroupContactPackets.parse(bad, bad.length));

        assertFalse(GroupContactPackets.isContactPacket(Arrays.copyOf(good, 7), 7));
        assertFalse(GroupContactPackets.isContactPacket(null, 54));
    }

    @Test
    public void nothing_is_built_from_a_wrong_id_nonce_or_kind()
    {
        final byte[] broken = address();
        broken[37] ^= 0x01;
        assertFalse(GroupContactPackets.checksumMatches(broken));
        assertTrue(GroupContactPackets.checksumMatches(address()));
        assertNull(GroupContactPackets.build(PKT_OFFER, broken, NONCE));
        assertNull(GroupContactPackets.build((byte) 0x41, address(), NONCE));
        assertNull(GroupContactPackets.build(PKT_OFFER, Arrays.copyOf(address(), 32), NONCE));
        assertNull(GroupContactPackets.build(PKT_OFFER, address(), new byte[7]));
        assertNull(GroupContactPackets.build(PKT_OFFER, null, NONCE));
        assertNull(GroupContactPackets.build(PKT_OFFER, address(), null));
    }

    @Test
    public void the_kinds_are_not_those_of_the_other_group_packets()
    {
        // History 01-04, files 11-14, video 21, audio 31, edit 41, delete 42, reaction 43, leave notice 44, HSK 50.
        for (final int used : new int[]{0x01, 0x02, 0x03, 0x04, 0x11, 0x12, 0x13, 0x14, 0x21, 0x31, 0x41, 0x42, 0x43,
                                        0x44, 0x50})
        {
            assertNotEquals(used, PKT_OFFER);
            assertNotEquals(used, PKT_ACCEPT);
        }
    }

    @Test
    public void the_contact_request_starts_with_the_nonce_of_the_offer()
    {
        assertEquals("[KQ|c|01020304050607ab]", GroupContactPackets.marker("01020304050607AB"));
        final String message = GroupContactPackets.requestMessage("01020304050607ab", "  I accepted your request  ");
        assertEquals("[KQ|c|01020304050607ab]\nI accepted your request", message);
        assertEquals("[KQ|c|01020304050607ab]", GroupContactPackets.requestMessage("01020304050607ab", null));
        assertEquals("01020304050607ab", GroupContactPackets.nonceOfRequest(message));
        assertEquals("01020304050607ab", GroupContactPackets.nonceOfRequest("[KQ|c|01020304050607AB]"));

        assertNull(GroupContactPackets.nonceOfRequest(null));
        assertNull(GroupContactPackets.nonceOfRequest("Hello"));
        assertNull(GroupContactPackets.nonceOfRequest("[KQ|c|01020304050607a]"));
        assertNull(GroupContactPackets.nonceOfRequest("[KQ|c|01020304050607zz]"));
        assertNull(GroupContactPackets.nonceOfRequest("[KQ|c|01020304050607ab"));
        assertNull(GroupContactPackets.nonceOfRequest(" [KQ|c|01020304050607ab]"));
    }

    @Test
    public void the_words_of_a_request_are_shown_without_the_marker()
    {
        assertEquals("I accepted", GroupContactPackets.withoutMarker("[KQ|c|01020304050607ab]\nI accepted"));
        assertEquals("I accepted", GroupContactPackets.withoutMarker("[KQ|c|01020304050607ab]\r\nI accepted"));
        assertEquals("", GroupContactPackets.withoutMarker("[KQ|c|01020304050607ab]"));
        assertEquals("Hello", GroupContactPackets.withoutMarker("Hello"));
        assertEquals("[KQ|c|nothex]\nHello", GroupContactPackets.withoutMarker("[KQ|c|nothex]\nHello"));
        assertEquals("", GroupContactPackets.withoutMarker(null));
    }

    @Test
    public void hex_reads_exactly_the_bytes_asked_for()
    {
        assertArrayEquals(NONCE, GroupContactPackets.fromHex("01020304050607ab", 8));
        assertArrayEquals(NONCE, GroupContactPackets.fromHex("01020304050607AB", 8));
        assertNull(GroupContactPackets.fromHex("01020304050607a", 8));
        assertNull(GroupContactPackets.fromHex("01020304050607ag", 8));
        assertNull(GroupContactPackets.fromHex(null, 8));
        assertEquals("01020304050607AB", GroupContactPackets.toHex(NONCE, 0, 8));
    }

    /**
     * KHANDAQ (26.11.2026): the same offer, accept and contact request are in the tests of iOS
     * (GroupContactsMentionsTests) and desktop (airgroupcontact_test.cpp), so what one client sends the others read.
     */
    static final String SHARED_ADDRESS =
            "3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C0A0B0C0D0606";
    static final String SHARED_OFFER = "6677881134350161" + SHARED_ADDRESS + "0123456789ABCDEF";
    static final String SHARED_ACCEPT = "6677881134350162" + SHARED_ADDRESS + "0123456789ABCDEF";
    static final String SHARED_WORDS = "I accepted your request from the group \u00abQA 2611\u00bb";
    static final String SHARED_REQUEST = "[KQ|c|0123456789abcdef]\n" + SHARED_WORDS;

    @Test
    public void every_client_builds_and_reads_the_same_bytes()
    {
        final byte[] address = GroupContactPackets.fromHex(SHARED_ADDRESS, GroupContactPackets.ADDRESS_BYTES);
        final byte[] nonce = GroupContactPackets.fromHex("0123456789abcdef", 8);
        assertTrue(GroupContactPackets.checksumMatches(address));
        assertArrayEquals(GroupContactPackets.fromHex(SHARED_OFFER, 54), GroupContactPackets.build(PKT_OFFER, address, nonce));
        assertArrayEquals(GroupContactPackets.fromHex(SHARED_ACCEPT, 54),
                          GroupContactPackets.build(PKT_ACCEPT, address, nonce));

        final GroupContactPackets.Packet offer =
                GroupContactPackets.parse(GroupContactPackets.fromHex(SHARED_OFFER, 54), 54);
        assertNotNull(offer);
        assertEquals(PKT_OFFER, offer.kind);
        assertEquals(SHARED_ADDRESS, offer.addressHex());
        assertEquals(SHARED_ADDRESS.substring(0, 64), offer.publicKeyHex());
        assertEquals("0123456789abcdef", offer.nonceHex());
        final GroupContactPackets.Packet accept =
                GroupContactPackets.parse(GroupContactPackets.fromHex(SHARED_ACCEPT, 54), 54);
        assertNotNull(accept);
        assertEquals(PKT_ACCEPT, accept.kind);
        assertEquals("0123456789abcdef", accept.nonceHex());

        assertEquals(SHARED_REQUEST, GroupContactPackets.requestMessage("0123456789ABCDEF", SHARED_WORDS));
        assertEquals("0123456789abcdef", GroupContactPackets.nonceOfRequest(SHARED_REQUEST));
        assertEquals(SHARED_WORDS, GroupContactPackets.withoutMarker(SHARED_REQUEST));
    }
}

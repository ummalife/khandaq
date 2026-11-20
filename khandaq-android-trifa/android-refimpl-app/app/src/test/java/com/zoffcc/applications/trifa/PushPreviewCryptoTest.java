package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.64): the cryptography and the packets of push previews against the test vectors of
 * docs/PUSH_PREVIEWS.md. iOS, the desktop and the relay hold the same vectors, so a byte that moves here breaks
 * previews between the platforms, not only on Android.
 */
public class PushPreviewCryptoTest
{
    static final String M = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    static final String P = "7E5A6B1C2D3E4F50617283940A1B2C3D4E5F60718293A4B5C6D7E8F901122334";
    static final String K = "76437c9f9a1e86d7731ab94a0b7329c55129122bd40e4f05c71c22a7b3d88e75";
    static final String KEY_PACKET = "BE010176437C9F9A1E86D7731AB94A0B7329C55129122BD40E4F05C71C22A7B3D88E75";
    static final String NONCE = "000102030405060708090a0b";
    static final String H = "t:" + repeat("AB", 32);
    /** "Privet" in Cyrillic, a space and the waving hand, as the spec has it. */
    static final String VECTOR_TEXT = "\u041F\u0440\u0438\u0432\u0435\u0442 \uD83D\uDC4B";
    static final String PLAINTEXT = "{\"v\":1,\"k\":\"text\",\"b\":\"" + VECTOR_TEXT + "\",\"n\":\"Isa\",\"ts\":1763571600,"
                                    + "\"h\":\"" + H + "\"}";
    static final String PREVIEW = "AQABAgMEBQYHCAkKC0cuZhE-761wu62F31CwYSNfERS9di10rfHn1Jnd2SQOJlEMElxEyACoX6lIA3nRn3Sl9GcJ"
                                  + "wRsv9OPSe7vzhOealnmCAJTD3uSj8rkONYeGSckX8WD6qi-WbLXRv9rUwHr2UexIOPXc-ua7_kVhtnjMbOHSMf4h"
                                  + "1pSNZa27AUhSAv9ipJnIDtdsoVdBinsLSmml2n6AJdWU5TGF3hQ";
    static final String PID = "f7c7d2c8db7e2f187cad5b14889dfd07";
    static final String CHAT_ID = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20";
    static final String PEER_PK = "A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0";
    static final String PRESENCE_HASH = "9f033743ff83e9574563245fe61fac74";
    static final String PRESENCE_PACKET = "BE0201019F033743FF83E9574563245FE61FAC74";
    static final String WAKE_PACKET = "BE0301019F033743FF83E9574563245FE61FAC74";

    // ---- K and packet 190/01 ----

    @Test
    public void the_key_of_a_friend_is_the_vector()
    {
        assertEquals(K, PushPreviewCrypto.hex(PushPreviewCrypto.friendKey(bytes(M, 32), bytes(P, 32))));
        // The friend's key in either case is the same 32 bytes.
        assertEquals(K, PushPreviewCrypto.hex(PushPreviewCrypto.friendKey(bytes(M, 32),
                                                                         bytes(P.toLowerCase(Locale.ROOT), 32))));
    }

    @Test
    public void a_malformed_master_or_friend_key_gives_no_key()
    {
        assertNull(PushPreviewCrypto.friendKey(null, bytes(P, 32)));
        assertNull(PushPreviewCrypto.friendKey(new byte[31], bytes(P, 32)));
        assertNull(PushPreviewCrypto.friendKey(bytes(M, 32), new byte[33]));
        assertNull(PushPreviewCrypto.friendKey(bytes(M, 32), null));
    }

    @Test
    public void packet_190_01_is_the_vector_and_reads_back()
    {
        final byte[] packet = PushPreviewCrypto.keyPacket(bytes(K, 32));
        assertEquals(KEY_PACKET, upper(packet));
        assertEquals(PushPreviewCrypto.KEY_PACKET_BYTES, packet.length);
        assertEquals(K, PushPreviewCrypto.hex(PushPreviewCrypto.keyFromPacket(packet, packet.length)));
    }

    @Test
    public void anything_but_a_well_formed_190_01_carries_no_key()
    {
        final byte[] packet = bytes(KEY_PACKET, 35);
        assertNull(PushPreviewCrypto.keyFromPacket(packet, 34));
        assertNull(PushPreviewCrypto.keyFromPacket(Arrays.copyOf(packet, 36), 36));
        assertNull(PushPreviewCrypto.keyFromPacket(Arrays.copyOf(packet, 20), 35));
        assertNull(PushPreviewCrypto.keyFromPacket(null, 35));
        assertNull(PushPreviewCrypto.keyFromPacket(changed(packet, 0, (byte) 0xBD), 35));
        assertNull(PushPreviewCrypto.keyFromPacket(changed(packet, 1, (byte) 0x02), 35));
        assertNull(PushPreviewCrypto.keyFromPacket(changed(packet, 2, (byte) 0x02), 35));
        assertNull(PushPreviewCrypto.keyPacket(new byte[31]));
        assertNull(PushPreviewCrypto.keyPacket(null));
    }

    // ---- the sealed preview ----

    @Test
    public void sealing_the_vector_with_its_nonce_gives_the_vector()
    {
        assertEquals(PREVIEW, PushPreviewCrypto.seal(bytes(K, 32), PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                                                     bytes(NONCE, 12)));
    }

    @Test
    public void opening_the_vector_gives_its_plaintext()
    {
        final byte[] plaintext = PushPreviewCrypto.open(bytes(K, 32), PREVIEW);
        assertNotNull(plaintext);
        assertEquals(PLAINTEXT, new String(plaintext, StandardCharsets.UTF_8));
    }

    @Test
    public void a_preview_opens_only_with_its_key_and_untouched()
    {
        final byte[] key = bytes(K, 32);
        final byte[] other = key.clone();
        other[0] ^= 1;
        assertNull(PushPreviewCrypto.open(other, PREVIEW));

        // Any character changed, inside the nonce, the ciphertext or the tag.
        for (final int at : new int[]{3, 20, 100, PREVIEW.length() - 1})
        {
            final char[] chars = PREVIEW.toCharArray();
            chars[at] = (chars[at] == 'A') ? 'B' : 'A';
            assertNull("changed at " + at, PushPreviewCrypto.open(key, new String(chars)));
        }
        assertNull(PushPreviewCrypto.open(key, PREVIEW.substring(0, PREVIEW.length() - 4)));
        assertNull(PushPreviewCrypto.open(key, PREVIEW + "AAAA"));
        assertNull(PushPreviewCrypto.open(key, PREVIEW.replace('-', '+')));
        assertNull(PushPreviewCrypto.open(key, PREVIEW + "="));
        assertNull(PushPreviewCrypto.open(key, ""));
        assertNull(PushPreviewCrypto.open(key, null));
        assertNull(PushPreviewCrypto.open(key, repeat("A", PushPreviewCrypto.MAX_PREVIEW_CHARS + 1)));
        assertNull(PushPreviewCrypto.open(null, PREVIEW));
        // A version byte other than 0x01: "AQ" is 0x01, "Ag" 0x02.
        assertNull(PushPreviewCrypto.open(key, "Ag" + PREVIEW.substring(2)));
    }

    @Test
    public void each_preview_has_a_fresh_nonce()
    {
        final byte[] key = bytes(K, 32);
        final byte[] plaintext = PLAINTEXT.getBytes(StandardCharsets.UTF_8);
        final String first = PushPreviewCrypto.seal(key, plaintext);
        final String second = PushPreviewCrypto.seal(key, plaintext);
        assertNotNull(first);
        assertNotEquals(first, second);
        assertArrayEquals(plaintext, PushPreviewCrypto.open(key, first));
        assertArrayEquals(plaintext, PushPreviewCrypto.open(key, second));
        assertTrue(PushPreviewCrypto.cipherAvailable());
    }

    @Test
    public void a_preview_too_long_for_the_relay_is_not_made()
    {
        // 1 + 12 + n + 16 bytes in base64url without padding: 1400 characters hold 1050 bytes.
        final byte[] key = bytes(K, 32);
        assertNotNull(PushPreviewCrypto.seal(key, new byte[1050 - 29]));
        assertEquals(PushPreviewCrypto.MAX_PREVIEW_CHARS, PushPreviewCrypto.seal(key, new byte[1050 - 29]).length());
        assertNull(PushPreviewCrypto.seal(key, new byte[1050 - 28]));
        assertNull(PushPreviewCrypto.seal(key, PLAINTEXT.getBytes(StandardCharsets.UTF_8), new byte[11]));
        assertNull(PushPreviewCrypto.seal(new byte[16], PLAINTEXT.getBytes(StandardCharsets.UTF_8)));
    }

    // ---- the relay's event id ----

    @Test
    public void the_event_id_is_the_vector()
    {
        assertEquals(PID, PushPreviewCrypto.eventId(bytes(K, 32), H));
        // The same event, the same id: every retry of it.
        assertEquals(PID, PushPreviewCrypto.eventId(bytes(K, 32), H));
        assertNotEquals(PID, PushPreviewCrypto.eventId(bytes(K, 32), "t:" + repeat("AC", 32)));
        assertEquals(PushPreviewCrypto.EVENT_ID_CHARS, PID.length());
        assertNull(PushPreviewCrypto.eventId(bytes(K, 32), ""));
        assertNull(PushPreviewCrypto.eventId(null, H));
    }

    // ---- group presence, packet 190/02 ----

    @Test
    public void the_presence_hash_and_packet_are_the_vectors()
    {
        final byte[] hash = PushPreviewCrypto.presenceHash(bytes(CHAT_ID, 32), bytes(PEER_PK, 32));
        assertEquals(PRESENCE_HASH, PushPreviewCrypto.hex(hash));
        final byte[] packet = PushPreviewCrypto.presencePacket(Collections.singletonList(hash));
        assertEquals(PRESENCE_PACKET, upper(packet));
        assertEquals(Collections.singletonList(PRESENCE_HASH),
                     PushPreviewCrypto.presenceFromPacket(packet, packet.length));
        assertNull(PushPreviewCrypto.presenceHash(new byte[31], bytes(PEER_PK, 32)));
        assertNull(PushPreviewCrypto.presenceHash(bytes(CHAT_ID, 32), null));
    }

    @Test
    public void a_presence_packet_carries_the_first_64_well_formed_hashes()
    {
        final List<byte[]> hashes = new ArrayList<>();
        hashes.add(new byte[15]);
        hashes.add(null);
        for (int i = 0; i < 70; i++)
        {
            final byte[] hash = new byte[16];
            hash[0] = (byte) i;
            hashes.add(hash);
        }
        final byte[] packet = PushPreviewCrypto.presencePacket(hashes);
        assertEquals(4 + 64 * 16, packet.length);
        assertEquals(64, packet[3] & 0xFF);
        final List<String> read = PushPreviewCrypto.presenceFromPacket(packet, packet.length);
        assertEquals(64, read.size());
        assertEquals("00" + repeat("0", 30), read.get(0));
        assertEquals("3f" + repeat("0", 30), read.get(63));

        // None: the friend is in no group, and what was kept for it goes.
        final byte[] none = PushPreviewCrypto.presencePacket(Collections.<byte[]>emptyList());
        assertEquals("BE020100", upper(none));
        assertEquals(Collections.<String>emptyList(), PushPreviewCrypto.presenceFromPacket(none, none.length));
    }

    @Test
    public void a_malformed_presence_packet_says_nothing()
    {
        final byte[] packet = bytes(PRESENCE_PACKET, 20);
        assertNull(PushPreviewCrypto.presenceFromPacket(packet, 19));
        assertNull(PushPreviewCrypto.presenceFromPacket(Arrays.copyOf(packet, 21), 21));
        assertNull(PushPreviewCrypto.presenceFromPacket(changed(packet, 1, (byte) 0x01), 20));
        assertNull(PushPreviewCrypto.presenceFromPacket(changed(packet, 2, (byte) 0x00), 20));
        assertNull(PushPreviewCrypto.presenceFromPacket(changed(packet, 3, (byte) 0x02), 20));
        assertNull(PushPreviewCrypto.presenceFromPacket(new byte[]{(byte) 0xBE, 0x02, 0x01}, 3));
        final byte[] tooMany = new byte[4 + 65 * 16];
        tooMany[0] = (byte) 0xBE;
        tooMany[1] = 0x02;
        tooMany[2] = 0x01;
        tooMany[3] = 65;
        assertNull(PushPreviewCrypto.presenceFromPacket(tooMany, tooMany.length));
    }

    @Test
    public void a_hash_announced_twice_is_kept_once()
    {
        final byte[] hash = bytes(PRESENCE_HASH, 16);
        final byte[] packet = PushPreviewCrypto.presencePacket(Arrays.asList(hash, hash));
        assertEquals(Collections.singletonList(PRESENCE_HASH), PushPreviewCrypto.presenceFromPacket(packet, 36));
    }

    // ---- group message wakes, packet 190/03 ----

    @Test
    public void the_wake_packet_is_the_vector_and_reads_back()
    {
        // KHANDAQ (0.2.69, group message wakes): the presence hash of the vector, under subtype 0x03.
        final byte[] hash = PushPreviewCrypto.presenceHash(bytes(CHAT_ID, 32), bytes(PEER_PK, 32));
        final byte[] packet = PushPreviewCrypto.wakePacket(Collections.singletonList(hash));
        assertEquals(WAKE_PACKET, upper(packet));
        assertEquals(Collections.singletonList(PRESENCE_HASH), PushPreviewCrypto.wakeFromPacket(packet, packet.length));
        // The same layout as 190/02, only the subtype differs; neither reads as the other.
        assertNull(PushPreviewCrypto.presenceFromPacket(packet, packet.length));
        assertNull(PushPreviewCrypto.wakeFromPacket(bytes(PRESENCE_PACKET, 20), 20));

        // None: no group of ours may wake the friend, and what it kept goes.
        final byte[] none = PushPreviewCrypto.wakePacket(Collections.<byte[]>emptyList());
        assertEquals("BE030100", upper(none));
        assertEquals(Collections.<String>emptyList(), PushPreviewCrypto.wakeFromPacket(none, none.length));
        assertEquals("BE030100", upper(PushPreviewCrypto.wakePacket(null)));
    }

    @Test
    public void a_wake_packet_carries_the_first_64_well_formed_hashes_once()
    {
        final List<byte[]> hashes = new ArrayList<>();
        hashes.add(new byte[17]);
        hashes.add(null);
        for (int i = 0; i < 70; i++)
        {
            final byte[] hash = new byte[16];
            hash[15] = (byte) i;
            hashes.add(hash);
        }
        final byte[] packet = PushPreviewCrypto.wakePacket(hashes);
        assertEquals(4 + 64 * 16, packet.length);
        assertEquals(64, packet[3] & 0xFF);
        final List<String> read = PushPreviewCrypto.wakeFromPacket(packet, packet.length);
        assertEquals(64, read.size());
        assertEquals(repeat("0", 30) + "00", read.get(0));
        assertEquals(repeat("0", 30) + "3f", read.get(63));

        final byte[] hash = bytes(PRESENCE_HASH, 16);
        final byte[] twice = PushPreviewCrypto.wakePacket(Arrays.asList(hash, hash));
        assertEquals(Collections.singletonList(PRESENCE_HASH), PushPreviewCrypto.wakeFromPacket(twice, twice.length));
    }

    @Test
    public void a_malformed_wake_packet_says_nothing()
    {
        final byte[] packet = bytes(WAKE_PACKET, 20);
        assertNull(PushPreviewCrypto.wakeFromPacket(null, 20));
        assertNull(PushPreviewCrypto.wakeFromPacket(packet, 19));
        assertNull(PushPreviewCrypto.wakeFromPacket(packet, 21));
        assertNull(PushPreviewCrypto.wakeFromPacket(Arrays.copyOf(packet, 21), 21));
        assertNull(PushPreviewCrypto.wakeFromPacket(changed(packet, 0, (byte) 0xBD), 20));
        assertNull(PushPreviewCrypto.wakeFromPacket(changed(packet, 1, (byte) 0x04), 20));
        assertNull(PushPreviewCrypto.wakeFromPacket(changed(packet, 2, (byte) 0x02), 20));
        assertNull(PushPreviewCrypto.wakeFromPacket(changed(packet, 3, (byte) 0x00), 20));
        assertNull(PushPreviewCrypto.wakeFromPacket(changed(packet, 3, (byte) 0x02), 20));
        assertNull(PushPreviewCrypto.wakeFromPacket(new byte[]{(byte) 0xBE, 0x03, 0x01}, 3));
        final byte[] tooMany = new byte[4 + 65 * 16];
        tooMany[0] = (byte) 0xBE;
        tooMany[1] = 0x03;
        tooMany[2] = 0x01;
        tooMany[3] = 65;
        assertNull(PushPreviewCrypto.wakeFromPacket(tooMany, tooMany.length));
    }

    @Test
    public void hex_reads_either_case_and_nothing_else()
    {
        assertArrayEquals(new byte[]{(byte) 0xAB, 0x01}, PushPreviewCrypto.unhex("Ab01", 2));
        assertNull(PushPreviewCrypto.unhex("ab0", 2));
        assertNull(PushPreviewCrypto.unhex("ab0g", 2));
        assertNull(PushPreviewCrypto.unhex(null, 2));
        assertEquals("ab01", PushPreviewCrypto.hex(new byte[]{(byte) 0xAB, 0x01}));
    }

    // ---- helpers ----

    static byte[] bytes(final String hex, final int count)
    {
        final byte[] out = PushPreviewCrypto.unhex(hex, count);
        assertNotNull(hex, out);
        return out;
    }

    static String upper(final byte[] data)
    {
        return PushPreviewCrypto.hex(data).toUpperCase(Locale.ROOT);
    }

    static String repeat(final String part, final int times)
    {
        final StringBuilder out = new StringBuilder(part.length() * times);
        for (int i = 0; i < times; i++)
        {
            out.append(part);
        }
        return out.toString();
    }

    private static byte[] changed(final byte[] data, final int at, final byte value)
    {
        final byte[] copy = data.clone();
        copy[at] = value;
        return copy;
    }
}

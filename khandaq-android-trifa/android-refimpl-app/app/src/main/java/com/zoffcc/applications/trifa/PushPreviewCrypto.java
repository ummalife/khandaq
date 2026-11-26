package com.zoffcc.applications.trifa;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * KHANDAQ (0.2.64): the cryptography and the packets of push previews (docs/PUSH_PREVIEWS.md): the per-contact key
 * K, lossless packet 190 with its subtypes, the sealed preview and the relay's event id. Pure Java, so that the
 * unit tests hold every byte of it against the vectors of the spec.
 */
final class PushPreviewCrypto
{
    /** Lossless custom packet 190 (0xBE). */
    static final int PKT_PUSH_PREVIEW = 190;
    static final byte SUBTYPE_KEY = 0x01;
    static final byte SUBTYPE_GROUP_PRESENCE = 0x02;
    /** KHANDAQ (0.2.69, group message wakes): the groups whose messages may wake the device that sends it. */
    static final byte SUBTYPE_GROUP_WAKE = 0x03;
    static final byte PACKET_VERSION = 0x01;

    static final int KEY_BYTES = 32;
    static final int PUBLIC_KEY_BYTES = 32;
    static final int CHAT_ID_BYTES = 32;
    static final int KEY_PACKET_BYTES = 3 + KEY_BYTES;
    static final int PRESENCE_HASH_BYTES = 16;
    static final int MAX_PRESENCE_HASHES = 64;
    static final int NONCE_BYTES = 12;
    static final int TAG_BYTES = 16;
    static final int MAX_PREVIEW_CHARS = 1400;
    static final int EVENT_ID_CHARS = 32;

    private static final byte SEALED_VERSION = 0x01;
    private static final int MIN_SEALED_BYTES = 1 + NONCE_BYTES + TAG_BYTES;

    private static final byte[] KEY_LABEL = ascii("khandaq push preview key v1");
    private static final byte[] AAD = ascii("khandaq push preview v1");
    private static final byte[] EVENT_LABEL = ascii("khandaq push event v1");
    private static final byte[] PRESENCE_LABEL = ascii("khandaq group presence v1");

    /**
     * Conscrypt (Android 9 and later) knows the IETF ChaCha20-Poly1305 by the first name, the JDK that runs the
     * unit tests by the second.
     */
    private static final String[] CIPHER_NAMES = {"ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305"};

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PushPreviewCrypto()
    {
    }

    // ---- keys ----

    /** K = HMAC-SHA256(M, "khandaq push preview key v1" || P), or null for a malformed input. */
    static byte[] friendKey(final byte[] master, final byte[] friendPublicKey)
    {
        if (master == null || master.length != KEY_BYTES || friendPublicKey == null
            || friendPublicKey.length != PUBLIC_KEY_BYTES)
        {
            return null;
        }
        return hmac(master, KEY_LABEL, friendPublicKey);
    }

    /** Packet 190/01: BE 01 01 K (35 bytes), or null without a well-formed key. */
    static byte[] keyPacket(final byte[] key)
    {
        if (key == null || key.length != KEY_BYTES)
        {
            return null;
        }
        final byte[] packet = new byte[KEY_PACKET_BYTES];
        packet[0] = (byte) PKT_PUSH_PREVIEW;
        packet[1] = SUBTYPE_KEY;
        packet[2] = PACKET_VERSION;
        System.arraycopy(key, 0, packet, 3, KEY_BYTES);
        return packet;
    }

    /** The K of packet 190/01, or null for any other length, subtype or version. */
    static byte[] keyFromPacket(final byte[] data, final int length)
    {
        if (data == null || length != KEY_PACKET_BYTES || data.length < length
            || data[0] != (byte) PKT_PUSH_PREVIEW || data[1] != SUBTYPE_KEY || data[2] != PACKET_VERSION)
        {
            return null;
        }
        final byte[] key = new byte[KEY_BYTES];
        System.arraycopy(data, 3, key, 0, KEY_BYTES);
        return key;
    }

    // ---- group presence ----

    /** SHA-256("khandaq group presence v1" || chat_id || own_peer_pk)[0:16], or null for a malformed input. */
    static byte[] presenceHash(final byte[] chatId, final byte[] peerPublicKey)
    {
        if (chatId == null || chatId.length != CHAT_ID_BYTES || peerPublicKey == null
            || peerPublicKey.length != PUBLIC_KEY_BYTES)
        {
            return null;
        }
        final byte[] digest = sha256(PRESENCE_LABEL, chatId, peerPublicKey);
        final byte[] hash = new byte[PRESENCE_HASH_BYTES];
        System.arraycopy(digest, 0, hash, 0, PRESENCE_HASH_BYTES);
        return hash;
    }

    /** Packet 190/02: BE 02 01 n hash*n, with the first 64 well-formed hashes. */
    static byte[] presencePacket(final List<byte[]> hashes)
    {
        return hashSetPacket(SUBTYPE_GROUP_PRESENCE, hashes);
    }

    /** The hashes of packet 190/02 as 32 lowercase hex each (possibly none), or null for a malformed packet. */
    static List<String> presenceFromPacket(final byte[] data, final int length)
    {
        return hashSetFromPacket(SUBTYPE_GROUP_PRESENCE, data, length);
    }

    // ---- group message wakes ----

    /**
     * Packet 190/03: BE 03 01 n hash*n, with the first 64 well-formed hashes. KHANDAQ (0.2.69, group message wakes):
     * the layout of 190/02, so that the two can never drift apart; only the subtype tells them apart.
     */
    static byte[] wakePacket(final List<byte[]> hashes)
    {
        return hashSetPacket(SUBTYPE_GROUP_WAKE, hashes);
    }

    /** The hashes of packet 190/03 as 32 lowercase hex each (possibly none), or null for a malformed packet. */
    static List<String> wakeFromPacket(final byte[] data, final int length)
    {
        return hashSetFromPacket(SUBTYPE_GROUP_WAKE, data, length);
    }

    private static byte[] hashSetPacket(final byte subtype, final List<byte[]> hashes)
    {
        final List<byte[]> kept = new ArrayList<>();
        if (hashes != null)
        {
            for (final byte[] hash : hashes)
            {
                if (kept.size() == MAX_PRESENCE_HASHES)
                {
                    break;
                }
                if (hash != null && hash.length == PRESENCE_HASH_BYTES)
                {
                    kept.add(hash);
                }
            }
        }
        final byte[] packet = new byte[4 + kept.size() * PRESENCE_HASH_BYTES];
        packet[0] = (byte) PKT_PUSH_PREVIEW;
        packet[1] = subtype;
        packet[2] = PACKET_VERSION;
        packet[3] = (byte) kept.size();
        for (int i = 0; i < kept.size(); i++)
        {
            System.arraycopy(kept.get(i), 0, packet, 4 + i * PRESENCE_HASH_BYTES, PRESENCE_HASH_BYTES);
        }
        return packet;
    }

    private static List<String> hashSetFromPacket(final byte subtype, final byte[] data, final int length)
    {
        if (data == null || length < 4 || data.length < length
            || data[0] != (byte) PKT_PUSH_PREVIEW || data[1] != subtype || data[2] != PACKET_VERSION)
        {
            return null;
        }
        final int count = data[3] & 0xFF;
        if (count > MAX_PRESENCE_HASHES || length != 4 + count * PRESENCE_HASH_BYTES)
        {
            return null;
        }
        final List<String> hashes = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            final String hash = hex(data, 4 + i * PRESENCE_HASH_BYTES, PRESENCE_HASH_BYTES);
            if (!hashes.contains(hash))
            {
                hashes.add(hash);
            }
        }
        return Collections.unmodifiableList(hashes);
    }

    // ---- the sealed preview ----

    /** base64url(0x01 || nonce || ciphertext || tag) with a fresh random nonce, or null when it cannot be made. */
    static String seal(final byte[] key, final byte[] plaintext)
    {
        return seal(key, plaintext, randomBytes(NONCE_BYTES));
    }

    /** As {@link #seal(byte[], byte[])} with the nonce given, which only the test vectors do. */
    static String seal(final byte[] key, final byte[] plaintext, final byte[] nonce)
    {
        if (key == null || key.length != KEY_BYTES || plaintext == null || nonce == null || nonce.length != NONCE_BYTES)
        {
            return null;
        }
        try
        {
            final Cipher cipher = chacha20Poly1305();
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"), new IvParameterSpec(nonce));
            cipher.updateAAD(AAD);
            final byte[] sealed = cipher.doFinal(plaintext);
            final byte[] out = new byte[1 + NONCE_BYTES + sealed.length];
            out[0] = SEALED_VERSION;
            System.arraycopy(nonce, 0, out, 1, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, 1 + NONCE_BYTES, sealed.length);
            final String preview = Base64.getUrlEncoder().withoutPadding().encodeToString(out);
            return preview.length() <= MAX_PREVIEW_CHARS ? preview : null;
        }
        catch (GeneralSecurityException | RuntimeException e)
        {
            return null;
        }
    }

    /** The plaintext of a preview sealed with this key, or null for anything that does not authenticate. */
    static byte[] open(final byte[] key, final String preview)
    {
        if (key == null || key.length != KEY_BYTES || !isBase64Url(preview, MAX_PREVIEW_CHARS))
        {
            return null;
        }
        try
        {
            final byte[] in = Base64.getUrlDecoder().decode(preview);
            if (in.length < MIN_SEALED_BYTES || in[0] != SEALED_VERSION)
            {
                return null;
            }
            final Cipher cipher = chacha20Poly1305();
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                        new IvParameterSpec(in, 1, NONCE_BYTES));
            cipher.updateAAD(AAD);
            return cipher.doFinal(in, 1 + NONCE_BYTES, in.length - 1 - NONCE_BYTES);
        }
        catch (GeneralSecurityException | RuntimeException e)
        {
            return null;
        }
    }

    /** True when this platform has the cipher previews need. */
    static boolean cipherAvailable()
    {
        try
        {
            chacha20Poly1305();
            return true;
        }
        catch (GeneralSecurityException e)
        {
            return false;
        }
    }

    // ---- the relay's event id ----

    /** pid = lowercase_hex(HMAC-SHA256(K, "khandaq push event v1" || eventKey))[0:32], or null without a key. */
    static String eventId(final byte[] key, final String eventKey)
    {
        if (key == null || key.length != KEY_BYTES || eventKey == null || eventKey.isEmpty())
        {
            return null;
        }
        final byte[] mac = hmac(key, EVENT_LABEL, eventKey.getBytes(StandardCharsets.UTF_8));
        return mac == null ? null : hex(mac, 0, EVENT_ID_CHARS / 2);
    }

    // ---- helpers ----

    static byte[] randomBytes(final int count)
    {
        final byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static String hex(final byte[] bytes)
    {
        return bytes == null ? null : hex(bytes, 0, bytes.length);
    }

    static String hex(final byte[] bytes, final int offset, final int length)
    {
        final char[] out = new char[length * 2];
        for (int i = 0; i < length; i++)
        {
            final int v = bytes[offset + i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** The bytes of a hex string of exactly {@code byteCount} bytes, in either case, or null. */
    static byte[] unhex(final String hex, final int byteCount)
    {
        if (hex == null || hex.length() != byteCount * 2)
        {
            return null;
        }
        final byte[] out = new byte[byteCount];
        for (int i = 0; i < byteCount; i++)
        {
            final int high = Character.digit(hex.charAt(i * 2), 16);
            final int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0)
            {
                return null;
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /** SHA-256 over the parts, one after another. */
    static byte[] sha256(final byte[]... parts)
    {
        try
        {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final byte[] part : parts)
            {
                digest.update(part);
            }
            return digest.digest();
        }
        catch (GeneralSecurityException e)
        {
            // Every Java platform has SHA-256 (it is required of them): this cannot happen.
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(final byte[] key, final byte[] label, final byte[] message)
    {
        try
        {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(label);
            mac.update(message);
            return mac.doFinal();
        }
        catch (GeneralSecurityException e)
        {
            return null;
        }
    }

    private static Cipher chacha20Poly1305() throws GeneralSecurityException
    {
        GeneralSecurityException last = null;
        for (final String name : CIPHER_NAMES)
        {
            try
            {
                return Cipher.getInstance(name);
            }
            catch (GeneralSecurityException e)
            {
                last = e;
            }
        }
        throw last;
    }

    private static boolean isBase64Url(final String text, final int maxChars)
    {
        if (text == null || text.isEmpty() || text.length() > maxChars)
        {
            return false;
        }
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'))
            {
                return false;
            }
        }
        return true;
    }

    private static byte[] ascii(final String text)
    {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}

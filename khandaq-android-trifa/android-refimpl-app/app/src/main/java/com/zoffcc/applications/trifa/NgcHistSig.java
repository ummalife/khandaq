package com.zoffcc.applications.trifa;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * KHANDAQ (external audit #2, finding 1) — the bytes an NGC history-sync signature covers.
 *
 * A history-sync packet carries the alleged original author as raw bytes nobody signed: the
 * transport authenticates only the SYNCING peer, so any group member can manufacture a message that
 * displays as another member's. The fix is an original-author signature — design, decisions and
 * rollout in DESIGN-ngc-signed-history-sync.md.
 *
 * This class is ONLY the pre-image. The signature itself is Ed25519 and will not diverge between
 * platforms; the byte string being signed absolutely can — field order, integer width, endianness,
 * and above all whether the text is hashed as UTF-8 or as the platform's native string encoding.
 * Java strings are UTF-16, which makes this the single most likely place for Android to disagree
 * with the desktop and iOS, and a disagreement here fails open in production while every local test
 * stays green.
 *
 * So it is checked against frozen reference vectors: {@code ngc_histsync_vectors.py} at the
 * repository root, mirrored by {@code NgcHistSigTest}, and by the desktop's
 * {@code test/core/ngchistsig_test.cpp}. All three must produce identical digests.
 *
 * Signing and verification are deliberately absent: {@code java.security.Signature} only offers
 * Ed25519 from API 33 while this app ships minSdk 21, so those go through the native toxcore JNI
 * (libsodium) in a later step. Nothing calls this class yet.
 */
final class NgcHistSig
{
    /** Widths the wire format fixes. Anything else is a malformed packet, not a variant. */
    static final int GROUP_ID_SIZE = 32;
    static final int PUBKEY_SIZE = 32;
    static final int MSG_ID_SIZE = 4;
    static final int SHA256_SIZE = 32;

    /** Domain separators — they stop a history signature validating as an announcement one. */
    static final byte[] HISTSYNC_DOMAIN = "KQ-HISTSYNC-1".getBytes(StandardCharsets.US_ASCII);
    static final byte[] ANNOUNCE_DOMAIN = "KQ-HSK-ANNOUNCE-1".getBytes(StandardCharsets.US_ASCII);

    /** Fixed regardless of message length, because the body is hashed rather than embedded. */
    static final int HISTSYNC_PREIMAGE_SIZE =
            HISTSYNC_DOMAIN.length + GROUP_ID_SIZE + PUBKEY_SIZE + MSG_ID_SIZE + 8 + SHA256_SIZE;
    static final int ANNOUNCE_PREIMAGE_SIZE = ANNOUNCE_DOMAIN.length + PUBKEY_SIZE + PUBKEY_SIZE + 8;

    private NgcHistSig()
    {
    }

    /**
     * "KQ-HISTSYNC-1" || groupId(32) || authorPub(32) || msgId(4) || ts(8 BE) || sha256(textUtf8)
     *
     * @param timestamp the FULL 64-bit value. Today's wire format transmits only its low 4 bytes;
     *                  a client that signs the truncated value produces signatures nobody accepts.
     *                  Java has no unsigned long, so this is a signed long carrying unsigned bits —
     *                  the shift-and-mask below is correct for the whole range including negatives.
     * @param textUtf8  the body ALREADY encoded as UTF-8. Taking bytes rather than a String is
     *                  deliberate: it removes the chance of a caller handing over UTF-16.
     * @return the pre-image, or null if any fixed-width argument has the wrong size — never a short
     *         buffer, so a malformed input can never be silently signed.
     */
    static byte[] histSyncPreimage(final byte[] groupId, final byte[] authorPub, final byte[] msgId,
                                   final long timestamp, final byte[] textUtf8)
    {
        if (groupId == null || groupId.length != GROUP_ID_SIZE
            || authorPub == null || authorPub.length != PUBKEY_SIZE
            || msgId == null || msgId.length != MSG_ID_SIZE
            || textUtf8 == null)
        {
            return null;
        }

        final byte[] textHash = sha256(textUtf8);
        if (textHash == null)
        {
            return null;
        }

        final byte[] out = new byte[HISTSYNC_PREIMAGE_SIZE];
        int pos = 0;
        pos = append(out, pos, HISTSYNC_DOMAIN);
        pos = append(out, pos, groupId);
        pos = append(out, pos, authorPub);
        pos = append(out, pos, msgId);
        pos = appendBigEndian64(out, pos, timestamp);
        pos = append(out, pos, textHash);
        return pos == out.length ? out : null;
    }

    /**
     * "KQ-HSK-ANNOUNCE-1" || senderGroupPub(32) || hskPub(32) || validFromTs(8 BE)
     *
     * senderGroupPub is the sender's public key IN THIS GROUP — what
     * tox_group_peer_get_public_key reports to the receiver, and what a group message records as its
     * author. It is deliberately not the profile's Tox ID key: NGC gives a member a different key
     * per group and never tells anyone their Tox ID, so a signature over the Tox ID key would be
     * one no receiver could reconstruct. (It was written that way first, and every announcement
     * failed verification on the wire while both sides looked correct in isolation.)
     */
    static byte[] announcePreimage(final byte[] senderGroupPub, final byte[] hskPub, final long validFromTs)
    {
        if (senderGroupPub == null || senderGroupPub.length != PUBKEY_SIZE
            || hskPub == null || hskPub.length != PUBKEY_SIZE)
        {
            return null;
        }

        final byte[] out = new byte[ANNOUNCE_PREIMAGE_SIZE];
        int pos = 0;
        pos = append(out, pos, ANNOUNCE_DOMAIN);
        pos = append(out, pos, senderGroupPub);
        pos = append(out, pos, hskPub);
        pos = appendBigEndian64(out, pos, validFromTs);
        return pos == out.length ? out : null;
    }

    private static int append(final byte[] out, final int pos, final byte[] src)
    {
        System.arraycopy(src, 0, out, pos, src.length);
        return pos + src.length;
    }

    /**
     * Big-endian on the wire regardless of host order. Written out explicitly rather than via
     * ByteBuffer so the byte order is visible at the call site instead of depending on a default.
     */
    private static int appendBigEndian64(final byte[] out, final int pos, final long value)
    {
        for (int i = 0; i < 8; i++)
        {
            out[pos + i] = (byte) ((value >>> (56 - (i * 8))) & 0xffL);
        }
        return pos + 8;
    }

    /** The same digest the pre-image uses, so a verdict cannot be checked against a different one. */
    static byte[] sha256Public(final byte[] data)
    {
        return sha256(data);
    }

    private static byte[] sha256(final byte[] data)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(data);
        }
        catch (Exception e)
        {
            // SHA-256 is mandatory on every Android version we ship to; if it is genuinely missing
            // the honest answer is "no pre-image", not a weaker digest.
            return null;
        }
    }
}

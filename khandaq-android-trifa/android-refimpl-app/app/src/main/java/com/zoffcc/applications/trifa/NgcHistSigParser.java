package com.zoffcc.applications.trifa;

import java.util.Arrays;

/**
 * KHANDAQ (external audit #2, finding 1) — parsers for the version-0x02 NGC packets.
 *
 * Parsing is where both external reviews and our own internal one actually found defects: an
 * attacker-chosen length that was trusted, a count that bought an allocation, an index that was
 * never bounded. So the new packets get their parsers written and bounded BEFORE anything emits
 * them, and every field is treated as hostile.
 *
 * Layouts (see DESIGN-ngc-signed-history-sync.md §4):
 *
 *   announcement  magic(6) | 0x02 | 0x50 | hskPub(32) | validFromTs(8 BE) | sig(64)      = 112
 *   signed text   magic(6) | 0x02 | 0x02 | msgId(4) | authorPub(32) | ts(8 BE)
 *                 | name(25) | textLen(4 BE) | text | sig(64)
 *
 * Nothing calls this yet. Version 0x02 is not emitted by any shipped client, and every shipped
 * parser drops it (verified on all three platforms), so this code is unreachable until the rollout
 * turns it on.
 */
final class NgcHistSigParser
{
    static final byte[] MAGIC = {0x66, 0x77, (byte) 0x88, 0x11, 0x34, 0x35};
    static final byte VERSION_SIGNED = 0x02;
    static final byte PKT_HSK_ANNOUNCE = 0x50;
    static final byte PKT_SIGNED_TEXT = 0x02;

    static final int HEADER_SIZE = 8; // magic + version + pktid
    static final int PEERNAME_SIZE = 25;
    static final int SIGNATURE_SIZE = 64;

    static final int ANNOUNCE_PACKET_SIZE =
            HEADER_SIZE + NgcHistSig.PUBKEY_SIZE + 8 + SIGNATURE_SIZE;

    /** Everything a signed-text packet carries besides the text itself: 145 bytes. */
    static final int SIGNED_TEXT_OVERHEAD =
            HEADER_SIZE + NgcHistSig.MSG_ID_SIZE + NgcHistSig.PUBKEY_SIZE + 8 + PEERNAME_SIZE + 4
            + SIGNATURE_SIZE;

    /**
     * Largest text a signed history packet may carry.
     *
     * The ceiling that matters is on the PACKET, not on the text: group_custom_private_packet_cb
     * drops anything longer than TOX_MAX_NGC_FILE_AND_HEADER_SIZE before it ever looks at the
     * version byte (MainActivity.java), so a text of exactly 37000 — the figure the unsigned path
     * uses, and what this constant used to be — produces a 37145-byte packet that the receiver
     * discards without a word. The signed copy would simply go missing for the largest messages,
     * and nothing in the logs would say why. Derive the text limit from the packet limit instead,
     * so the two can never drift apart again.
     */
    static final int MAX_TEXT_BYTES =
            ToxVars.TOX_MAX_NGC_FILE_AND_HEADER_SIZE - SIGNED_TEXT_OVERHEAD;

    private NgcHistSigParser()
    {
    }

    /** A parsed announcement. Immutable; only produced when every bound held. */
    static final class Announcement
    {
        final byte[] hskPub;
        final long validFromTs;
        final byte[] signature;

        Announcement(final byte[] hskPub, final long validFromTs, final byte[] signature)
        {
            this.hskPub = hskPub;
            this.validFromTs = validFromTs;
            this.signature = signature;
        }
    }

    /** A parsed signed-history text record. Immutable; only produced when every bound held. */
    static final class SignedText
    {
        final byte[] msgId;
        final byte[] authorPub;
        final long timestamp;
        final byte[] peerNameRaw;
        final byte[] textUtf8;
        final byte[] signature;

        SignedText(final byte[] msgId, final byte[] authorPub, final long timestamp,
                   final byte[] peerNameRaw, final byte[] textUtf8, final byte[] signature)
        {
            this.msgId = msgId;
            this.authorPub = authorPub;
            this.timestamp = timestamp;
            this.peerNameRaw = peerNameRaw;
            this.textUtf8 = textUtf8;
            this.signature = signature;
        }
    }

    /** True only for a packet that is ours, of the signed version, and of the given kind. */
    static boolean isSignedPacket(final byte[] data, final int length, final byte pktId)
    {
        if (data == null || length < HEADER_SIZE || length > data.length)
        {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++)
        {
            if (data[i] != MAGIC[i])
            {
                return false;
            }
        }
        return data[6] == VERSION_SIGNED && data[7] == pktId;
    }

    /**
     * @param length bytes actually received. Passed separately because the caller's buffer may be
     *               larger than the datagram; trusting {@code data.length} would read stale bytes.
     * @return the announcement, or null if anything about the packet is wrong.
     */
    static Announcement parseAnnouncement(final byte[] data, final int length)
    {
        // Exact length, not "at least": this packet has no variable part, so a longer one is either
        // a different packet or an attempt to hide something after the signature.
        if (!isSignedPacket(data, length, PKT_HSK_ANNOUNCE) || length != ANNOUNCE_PACKET_SIZE)
        {
            return null;
        }

        int pos = HEADER_SIZE;
        final byte[] hskPub = Arrays.copyOfRange(data, pos, pos + NgcHistSig.PUBKEY_SIZE);
        pos += NgcHistSig.PUBKEY_SIZE;
        final long ts = readBigEndian64(data, pos);
        pos += 8;
        final byte[] sig = Arrays.copyOfRange(data, pos, pos + SIGNATURE_SIZE);
        return new Announcement(hskPub, ts, sig);
    }

    /**
     * @return the record, or null if anything about the packet is wrong. Notably null when the
     *         declared text length disagrees with the bytes actually present — the class of bug that
     *         both reviews found in the chunk paths.
     */
    static SignedText parseSignedText(final byte[] data, final int length)
    {
        final int fixedBefore = SIGNED_TEXT_OVERHEAD - SIGNATURE_SIZE;
        if (!isSignedPacket(data, length, PKT_SIGNED_TEXT) || length < fixedBefore + SIGNATURE_SIZE)
        {
            return null;
        }

        int pos = HEADER_SIZE;
        final byte[] msgId = Arrays.copyOfRange(data, pos, pos + NgcHistSig.MSG_ID_SIZE);
        pos += NgcHistSig.MSG_ID_SIZE;
        final byte[] authorPub = Arrays.copyOfRange(data, pos, pos + NgcHistSig.PUBKEY_SIZE);
        pos += NgcHistSig.PUBKEY_SIZE;
        final long ts = readBigEndian64(data, pos);
        pos += 8;
        final byte[] peerName = Arrays.copyOfRange(data, pos, pos + PEERNAME_SIZE);
        pos += PEERNAME_SIZE;

        // The declared length is attacker-chosen, and it is guarded THREE times over on purpose:
        // read unsigned (a 32-bit field with the top bit set would otherwise arrive negative), then
        // range-checked, then required to match the packet exactly. Mutation testing showed these
        // overlap - flipping the read to signed alone changes no test outcome, because the range
        // check catches the same values. That redundancy is deliberate, not an accident: this is the
        // exact field shape that produced defects in the chunk paths in two separate reviews.
        final long declaredTextLen = readBigEndian32Unsigned(data, pos);
        pos += 4;

        if (declaredTextLen < 0 || declaredTextLen > MAX_TEXT_BYTES)
        {
            return null;
        }
        // The bytes must actually be present, and the signature must follow them EXACTLY - no
        // trailing slack in which to hide anything the signature does not cover.
        if ((long) pos + declaredTextLen + SIGNATURE_SIZE != (long) length)
        {
            return null;
        }

        final byte[] text = Arrays.copyOfRange(data, pos, pos + (int) declaredTextLen);
        pos += (int) declaredTextLen;
        final byte[] sig = Arrays.copyOfRange(data, pos, pos + SIGNATURE_SIZE);
        return new SignedText(msgId, authorPub, ts, peerName, text, sig);
    }

    // ---------------------------------------------------------------- builders
    //
    // Building is not sending. These produce the bytes; wiring them into an actual emit is the step
    // that changes outgoing traffic and is deliberately not done here. Keeping them next to the
    // parsers is the point: the round-trip test below is what stops the two drifting apart, which is
    // the failure nobody notices until two clients disagree in the field.

    /**
     * @return the announcement packet, or null if a field has the wrong size — never a short buffer.
     */
    static byte[] buildAnnouncement(final byte[] hskPub, final long validFromTs, final byte[] signature)
    {
        if (hskPub == null || hskPub.length != NgcHistSig.PUBKEY_SIZE
            || signature == null || signature.length != SIGNATURE_SIZE)
        {
            return null;
        }

        final byte[] out = new byte[ANNOUNCE_PACKET_SIZE];
        int pos = writeHeader(out, PKT_HSK_ANNOUNCE);
        pos = put(out, pos, hskPub);
        pos = putBigEndian64(out, pos, validFromTs);
        pos = put(out, pos, signature);
        return pos == out.length ? out : null;
    }

    /**
     * @return the signed-text packet, or null if a field has the wrong size or the body exceeds
     *         {@link #MAX_TEXT_BYTES} — the same ceiling the parser enforces, checked on the way out
     *         as well so this client can never emit something its own peers must reject.
     */
    static byte[] buildSignedText(final byte[] msgId, final byte[] authorPub, final long timestamp,
                                  final byte[] peerNameRaw, final byte[] textUtf8,
                                  final byte[] signature)
    {
        if (msgId == null || msgId.length != NgcHistSig.MSG_ID_SIZE
            || authorPub == null || authorPub.length != NgcHistSig.PUBKEY_SIZE
            || peerNameRaw == null || peerNameRaw.length != PEERNAME_SIZE
            || textUtf8 == null || textUtf8.length > MAX_TEXT_BYTES
            || signature == null || signature.length != SIGNATURE_SIZE)
        {
            return null;
        }

        final int total = SIGNED_TEXT_OVERHEAD + textUtf8.length;
        final byte[] out = new byte[total];
        int pos = writeHeader(out, PKT_SIGNED_TEXT);
        pos = put(out, pos, msgId);
        pos = put(out, pos, authorPub);
        pos = putBigEndian64(out, pos, timestamp);
        pos = put(out, pos, peerNameRaw);
        pos = putBigEndian32(out, pos, textUtf8.length);
        pos = put(out, pos, textUtf8);
        pos = put(out, pos, signature);
        return pos == out.length ? out : null;
    }

    private static int writeHeader(final byte[] out, final byte pktId)
    {
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[6] = VERSION_SIGNED;
        out[7] = pktId;
        return HEADER_SIZE;
    }

    private static int put(final byte[] out, final int pos, final byte[] src)
    {
        System.arraycopy(src, 0, out, pos, src.length);
        return pos + src.length;
    }

    private static int putBigEndian64(final byte[] out, final int pos, final long value)
    {
        for (int i = 0; i < 8; i++)
        {
            out[pos + i] = (byte) ((value >>> (56 - (i * 8))) & 0xffL);
        }
        return pos + 8;
    }

    private static int putBigEndian32(final byte[] out, final int pos, final long value)
    {
        for (int i = 0; i < 4; i++)
        {
            out[pos + i] = (byte) ((value >>> (24 - (i * 8))) & 0xffL);
        }
        return pos + 4;
    }

    private static long readBigEndian64(final byte[] data, final int pos)
    {
        long v = 0;
        for (int i = 0; i < 8; i++)
        {
            v = (v << 8) | (data[pos + i] & 0xffL);
        }
        return v;
    }

    /** Returns 0..4294967295 rather than a sign-extended int. */
    private static long readBigEndian32Unsigned(final byte[] data, final int pos)
    {
        long v = 0;
        for (int i = 0; i < 4; i++)
        {
            v = (v << 8) | (data[pos + i] & 0xffL);
        }
        return v;
    }
}

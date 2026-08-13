package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * KHANDAQ — bounds on the version-0x02 packet parsers (audit2 finding 1).
 *
 * Every external review of this codebase, and our own internal one, found its real defects in
 * parsing: an attacker-chosen length that was trusted, a count that bought an allocation up front,
 * an index that was never bounded. So these parsers get their tests before anything emits the
 * packets they read, and every field is treated as hostile.
 */
public class NgcHistSigParserTest
{
    private static final byte[] HSK = filled(0x11, 32);
    private static final byte[] AUTHOR = filled(0xAA, 32);
    private static final byte[] MSG_ID = {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef};
    private static final byte[] SIG = filled(0x5A, 64);
    private static final long TS = 1754870400L;

    private static byte[] filled(final int v, final int n)
    {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) { b[i] = (byte) v; }
        return b;
    }

    private static void be64(final ByteArrayOutputStream o, final long v)
    {
        for (int i = 0; i < 8; i++) { o.write((int) ((v >>> (56 - (i * 8))) & 0xffL)); }
    }

    private static void be32(final ByteArrayOutputStream o, final long v)
    {
        for (int i = 0; i < 4; i++) { o.write((int) ((v >>> (24 - (i * 8))) & 0xffL)); }
    }

    private static void header(final ByteArrayOutputStream o, final byte version, final byte pkt)
    {
        o.write(NgcHistSigParser.MAGIC, 0, NgcHistSigParser.MAGIC.length);
        o.write(version);
        o.write(pkt);
    }

    private static byte[] announcement(final byte version, final byte pkt)
    {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        header(o, version, pkt);
        o.write(HSK, 0, HSK.length);
        be64(o, TS);
        o.write(SIG, 0, SIG.length);
        return o.toByteArray();
    }

    /** @param declaredLen written into the length field; may deliberately disagree with the body. */
    private static byte[] signedText(final byte[] body, final long declaredLen)
    {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();
        header(o, NgcHistSigParser.VERSION_SIGNED, NgcHistSigParser.PKT_SIGNED_TEXT);
        o.write(MSG_ID, 0, MSG_ID.length);
        o.write(AUTHOR, 0, AUTHOR.length);
        be64(o, TS);
        o.write(new byte[NgcHistSigParser.PEERNAME_SIZE], 0, NgcHistSigParser.PEERNAME_SIZE);
        be32(o, declaredLen);
        o.write(body, 0, body.length);
        o.write(SIG, 0, SIG.length);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ announcement

    @Test
    public void parsesAValidAnnouncement()
    {
        final byte[] p = announcement(NgcHistSigParser.VERSION_SIGNED, NgcHistSigParser.PKT_HSK_ANNOUNCE);
        assertEquals(NgcHistSigParser.ANNOUNCE_PACKET_SIZE, p.length);

        final NgcHistSigParser.Announcement a = NgcHistSigParser.parseAnnouncement(p, p.length);
        assertNotNull(a);
        assertArrayEquals(HSK, a.hskPub);
        assertEquals(TS, a.validFromTs);
        assertArrayEquals(SIG, a.signature);
    }

    /** Version 0x01 is the old protocol; it must never reach the signed parser. */
    @Test
    public void rejectsTheOldProtocolVersion()
    {
        final byte[] p = announcement((byte) 0x01, NgcHistSigParser.PKT_HSK_ANNOUNCE);
        assertNull(NgcHistSigParser.parseAnnouncement(p, p.length));
    }

    @Test
    public void rejectsAWrongPacketIdAndWrongMagic()
    {
        assertNull(NgcHistSigParser.parseAnnouncement(
                announcement(NgcHistSigParser.VERSION_SIGNED, (byte) 0x51), NgcHistSigParser.ANNOUNCE_PACKET_SIZE));

        final byte[] badMagic = announcement(NgcHistSigParser.VERSION_SIGNED, NgcHistSigParser.PKT_HSK_ANNOUNCE);
        badMagic[3] = 0x12;
        assertNull(NgcHistSigParser.parseAnnouncement(badMagic, badMagic.length));
    }

    /**
     * The announcement has no variable part, so a longer packet is either something else or an
     * attempt to hide bytes after the signature. Exact length, not "at least".
     */
    @Test
    public void rejectsAnAnnouncementWithTrailingBytes()
    {
        final byte[] p = announcement(NgcHistSigParser.VERSION_SIGNED, NgcHistSigParser.PKT_HSK_ANNOUNCE);
        final byte[] longer = new byte[p.length + 1];
        System.arraycopy(p, 0, longer, 0, p.length);
        assertNull(NgcHistSigParser.parseAnnouncement(longer, longer.length));
    }

    @Test
    public void rejectsATruncatedAnnouncement()
    {
        final byte[] p = announcement(NgcHistSigParser.VERSION_SIGNED, NgcHistSigParser.PKT_HSK_ANNOUNCE);
        assertNull(NgcHistSigParser.parseAnnouncement(p, p.length - 1));
        assertNull(NgcHistSigParser.parseAnnouncement(p, 4));
        assertNull(NgcHistSigParser.parseAnnouncement(p, 0));
    }

    /** A declared length beyond the buffer must be refused, never read. */
    @Test
    public void rejectsALengthLongerThanTheBuffer()
    {
        final byte[] p = announcement(NgcHistSigParser.VERSION_SIGNED, NgcHistSigParser.PKT_HSK_ANNOUNCE);
        assertNull(NgcHistSigParser.parseAnnouncement(p, p.length + 100));
        assertNull(NgcHistSigParser.parseAnnouncement(null, 120));
    }

    // ------------------------------------------------------------------ signed text

    @Test
    public void parsesAValidSignedText()
    {
        final byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        final byte[] p = signedText(body, body.length);

        final NgcHistSigParser.SignedText t = NgcHistSigParser.parseSignedText(p, p.length);
        assertNotNull(t);
        assertArrayEquals(MSG_ID, t.msgId);
        assertArrayEquals(AUTHOR, t.authorPub);
        assertEquals(TS, t.timestamp);
        assertArrayEquals(body, t.textUtf8);
        assertArrayEquals(SIG, t.signature);
    }

    @Test
    public void parsesAnEmptyBody()
    {
        final byte[] p = signedText(new byte[0], 0);
        final NgcHistSigParser.SignedText t = NgcHistSigParser.parseSignedText(p, p.length);
        assertNotNull(t);
        assertEquals(0, t.textUtf8.length);
    }

    /**
     * THE one that matters: a 32-bit length with the top bit set arrives as a negative int, and a
     * signed comparison would wave it straight through into an allocation.
     */
    @Test
    public void rejectsALengthWithTheHighBitSet()
    {
        final byte[] p = signedText("x".getBytes(StandardCharsets.UTF_8), 0xFFFFFFFFL);
        assertNull(NgcHistSigParser.parseSignedText(p, p.length));

        final byte[] p2 = signedText("x".getBytes(StandardCharsets.UTF_8), 0x80000000L);
        assertNull(NgcHistSigParser.parseSignedText(p2, p2.length));
    }

    @Test
    public void rejectsALengthAboveTheCeiling()
    {
        final byte[] p = signedText("x".getBytes(StandardCharsets.UTF_8),
                                    NgcHistSigParser.MAX_TEXT_BYTES + 1);
        assertNull(NgcHistSigParser.parseSignedText(p, p.length));
    }

    /** Declared shorter than the bytes present: the slack could hide data the signature misses. */
    @Test
    public void rejectsADeclaredLengthShorterThanTheBody()
    {
        final byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
        final byte[] p = signedText(body, body.length - 1);
        assertNull(NgcHistSigParser.parseSignedText(p, p.length));
    }

    /** Declared longer than the bytes present: the classic over-read. */
    @Test
    public void rejectsADeclaredLengthLongerThanTheBody()
    {
        final byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        final byte[] p = signedText(body, body.length + 1);
        assertNull(NgcHistSigParser.parseSignedText(p, p.length));
    }

    @Test
    public void rejectsAPacketTooShortToHoldItsFixedFields()
    {
        final byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        final byte[] p = signedText(body, body.length);
        for (int cut = 1; cut <= 70; cut++)
        {
            assertNull("truncated by " + cut, NgcHistSigParser.parseSignedText(p, p.length - cut));
        }
    }

    @Test
    public void rejectsTheOldVersionAndForeignPackets()
    {
        final byte[] body = "x".getBytes(StandardCharsets.UTF_8);
        final byte[] p = signedText(body, body.length);
        p[6] = 0x01;
        assertNull(NgcHistSigParser.parseSignedText(p, p.length));
        p[6] = NgcHistSigParser.VERSION_SIGNED;
        p[7] = 0x13; // a chunked-file packet id
        assertNull(NgcHistSigParser.parseSignedText(p, p.length));
    }

    // ------------------------------------------------------------------ builder round-trip
    //
    // The builder and the parser are the two halves that must never drift. A layout change in one
    // and not the other produces packets our own peers reject - and it would show up only in the
    // field, between two clients, which is the hardest place to notice anything.

    @Test
    public void announcementSurvivesABuildParseRoundTrip()
    {
        final byte[] p = NgcHistSigParser.buildAnnouncement(HSK, TS, SIG);
        assertNotNull(p);
        assertEquals(NgcHistSigParser.ANNOUNCE_PACKET_SIZE, p.length);

        final NgcHistSigParser.Announcement a = NgcHistSigParser.parseAnnouncement(p, p.length);
        assertNotNull(a);
        assertArrayEquals(HSK, a.hskPub);
        assertEquals(TS, a.validFromTs);
        assertArrayEquals(SIG, a.signature);
    }

    @Test
    public void signedTextSurvivesABuildParseRoundTrip()
    {
        final byte[] name = new byte[NgcHistSigParser.PEERNAME_SIZE];
        name[0] = 'a';
        final byte[] body = "Привет, мир 👋".getBytes(StandardCharsets.UTF_8);

        final byte[] p = NgcHistSigParser.buildSignedText(MSG_ID, AUTHOR, TS, name, body, SIG);
        assertNotNull(p);

        final NgcHistSigParser.SignedText t = NgcHistSigParser.parseSignedText(p, p.length);
        assertNotNull(t);
        assertArrayEquals(MSG_ID, t.msgId);
        assertArrayEquals(AUTHOR, t.authorPub);
        assertEquals(TS, t.timestamp);
        assertArrayEquals(name, t.peerNameRaw);
        assertArrayEquals(body, t.textUtf8);
        assertArrayEquals(SIG, t.signature);
    }

    /** Edge bodies round-trip too: empty, one byte, and exactly the ceiling. */
    @Test
    public void roundTripHoldsAtTheBodySizeEdges()
    {
        final byte[] name = new byte[NgcHistSigParser.PEERNAME_SIZE];
        for (final int size : new int[]{0, 1, NgcHistSigParser.MAX_TEXT_BYTES})
        {
            final byte[] body = new byte[size];
            final byte[] p = NgcHistSigParser.buildSignedText(MSG_ID, AUTHOR, TS, name, body, SIG);
            assertNotNull("build failed at size " + size, p);
            final NgcHistSigParser.SignedText t = NgcHistSigParser.parseSignedText(p, p.length);
            assertNotNull("parse failed at size " + size, t);
            assertEquals(size, t.textUtf8.length);
        }
    }

    /** The full unsigned 64-bit timestamp range must survive the round trip, including -1L. */
    @Test
    public void roundTripPreservesTheFullTimestampRange()
    {
        final byte[] name = new byte[NgcHistSigParser.PEERNAME_SIZE];
        for (final long ts : new long[]{0L, 1L, 0x0000000100000001L, Long.MAX_VALUE, -1L})
        {
            final byte[] p = NgcHistSigParser.buildSignedText(MSG_ID, AUTHOR, ts, name,
                                                              new byte[]{'x'}, SIG);
            final NgcHistSigParser.SignedText t = NgcHistSigParser.parseSignedText(p, p.length);
            assertNotNull(t);
            assertEquals("timestamp " + ts, ts, t.timestamp);
        }
    }

    /** The builder refuses what the parser would refuse, so we never emit a packet peers must drop. */
    @Test
    public void builderRefusesWhatTheParserWouldReject()
    {
        final byte[] name = new byte[NgcHistSigParser.PEERNAME_SIZE];
        assertNull(NgcHistSigParser.buildSignedText(MSG_ID, AUTHOR, TS, name,
                                                    new byte[NgcHistSigParser.MAX_TEXT_BYTES + 1], SIG));
        assertNull(NgcHistSigParser.buildSignedText(new byte[3], AUTHOR, TS, name, new byte[0], SIG));
        assertNull(NgcHistSigParser.buildSignedText(MSG_ID, new byte[31], TS, name, new byte[0], SIG));
        assertNull(NgcHistSigParser.buildSignedText(MSG_ID, AUTHOR, TS, new byte[24], new byte[0], SIG));
        assertNull(NgcHistSigParser.buildSignedText(MSG_ID, AUTHOR, TS, name, new byte[0], new byte[63]));
        assertNull(NgcHistSigParser.buildSignedText(MSG_ID, AUTHOR, TS, name, null, SIG));
        assertNull(NgcHistSigParser.buildAnnouncement(new byte[31], TS, SIG));
        assertNull(NgcHistSigParser.buildAnnouncement(HSK, TS, new byte[65]));
        assertNull(NgcHistSigParser.buildAnnouncement(null, TS, SIG));
    }

    /** A body at exactly the ceiling is legitimate and must survive. */
    @Test
    public void acceptsABodyAtExactlyTheCeiling()
    {
        final byte[] body = new byte[NgcHistSigParser.MAX_TEXT_BYTES];
        final byte[] p = signedText(body, body.length);
        final NgcHistSigParser.SignedText t = NgcHistSigParser.parseSignedText(p, p.length);
        assertNotNull(t);
        assertEquals(NgcHistSigParser.MAX_TEXT_BYTES, t.textUtf8.length);
    }
}

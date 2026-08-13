package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — proves Android builds the SAME signature pre-image as everyone else
 * (audit2 finding 1, step 3 of DESIGN-ngc-signed-history-sync.md).
 *
 * The digests below are copied from the frozen reference `ngc_histsync_vectors.py` at the repository
 * root, and the desktop's `test/core/ngchistsig_test.cpp` asserts the same values. If any of the
 * three drifts, signatures stop verifying across clients while that client's own tests stay green —
 * which is exactly why the vectors exist rather than each platform testing itself against itself.
 */
public class NgcHistSigTest
{
    private static byte[] counting32()
    {
        final byte[] b = new byte[32];
        for (int i = 0; i < 32; i++) { b[i] = (byte) i; }
        return b;
    }

    private static byte[] repeated(final int value, final int n)
    {
        final byte[] b = new byte[n];
        for (int i = 0; i < n; i++) { b[i] = (byte) value; }
        return b;
    }

    private static byte[] alternating1122()
    {
        final byte[] b = new byte[32];
        for (int i = 0; i < 16; i++) { b[i * 2] = 0x11; b[(i * 2) + 1] = 0x22; }
        return b;
    }

    private static String sha256Hex(final byte[] data) throws Exception
    {
        final byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        final StringBuilder sb = new StringBuilder(d.length * 2);
        for (final byte x : d) { sb.append(String.format("%02x", x)); }
        return sb.toString();
    }

    private static final byte[] GROUP_A = counting32();
    private static final byte[] GROUP_B = repeated(0xff, 32);
    private static final byte[] AUTHOR_A = repeated(0xAA, 32);
    private static final byte[] AUTHOR_B = repeated(0x00, 32);
    private static final byte[] MSG_ID = new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef};
    private static final long TS_BASE = 1754870400L;

    private static byte[] utf8(final String s)
    {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------- frozen reference vectors

    @Test
    public void asciiBasic() throws Exception
    {
        assertEquals("33599061b75b2c487120a845450367ee880c931d6c00095960f8c3828f3457ed",
                     sha256Hex(NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, TS_BASE, utf8("hello"))));
    }

    /** Skipping the hash of an empty body, or substituting null, diverges here. */
    @Test
    public void emptyText() throws Exception
    {
        assertEquals("d008aea0521ebdc8ac4488263746b0c63c7819b37aa966edcdf89ff7121b711f",
                     sha256Hex(NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, TS_BASE, new byte[0])));
    }

    /**
     * The one Android is most likely to get wrong: Java strings are UTF-16, so a caller that hands
     * over native string bytes instead of UTF-8 fails only on multi-byte input.
     */
    @Test
    public void utf8Multibyte() throws Exception
    {
        assertEquals("752e856237a501d9bb3c278d97b445b2d428375f54edf0692ffbb80818dafd49",
                     sha256Hex(NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, TS_BASE,
                                                           utf8("Привет, мир 👋"))));
    }

    /**
     * The wire format transmits only the LOW 4 bytes of the timestamp while the pre-image signs all
     * 8. An implementation that truncates to 32 bits passes every other vector and fails this one.
     */
    @Test
    public void timestampAbove32Bit() throws Exception
    {
        assertEquals("b16b06ed10ce4bec25661e486350f2b8b4018014b9ddcb8cbcc35ca51779507d",
                     sha256Hex(NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID,
                                                           0x0000000100000001L, utf8("x"))));
    }

    /**
     * Java has no unsigned long, so the maximum u64 arrives here as -1. A shift that is arithmetic
     * rather than logical, or a comparison that treats this as negative, breaks exactly here.
     */
    @Test
    public void timestampMaxU64() throws Exception
    {
        assertEquals("e867afdab7e3f201da8b48259530733400dce9caf5d1306e22af0989e69db055",
                     sha256Hex(NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, -1L, utf8("x"))));
    }

    /** An all-zero pubkey is a normal value, not an absent field. */
    @Test
    public void zeroAuthorKey() throws Exception
    {
        assertEquals("0c0a9029409cf4a617254a0f5bc2fa78119ca3f4766ce77f377ade1f38defa99",
                     sha256Hex(NgcHistSig.histSyncPreimage(GROUP_B, AUTHOR_B, new byte[4], 0L, new byte[0])));
    }

    @Test
    public void announceBasic() throws Exception
    {
        assertEquals("70b75055b020a79fbc70fe27fb7d48adebf585dcf6fd79f9a1f58d195d11a88b",
                     sha256Hex(NgcHistSig.announcePreimage(AUTHOR_A, alternating1122(), TS_BASE)));
    }

    /** validFromTs == 0 must not read as "field absent". */
    @Test
    public void announceZeroTs() throws Exception
    {
        assertEquals("b25c730609d8e16646630ff6f8dff11046dfaa4d8f617bda18c948fa843de278",
                     sha256Hex(NgcHistSig.announcePreimage(AUTHOR_B, alternating1122(), 0L)));
    }

    // ------------------------------------------------------- structural guarantees

    /** The body is hashed, not embedded, so signature handling has no size-dependent path. */
    @Test
    public void preimageLengthIsIndependentOfMessageSize()
    {
        final byte[] small = NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, TS_BASE, utf8("x"));
        final byte[] large = NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, TS_BASE,
                                                         new byte[1024 * 1024]);
        assertEquals(121, small.length);
        assertEquals(121, large.length);
        assertEquals(121, NgcHistSig.HISTSYNC_PREIMAGE_SIZE);
        assertEquals(89, NgcHistSig.ANNOUNCE_PREIMAGE_SIZE);
        assertEquals(89, NgcHistSig.announcePreimage(AUTHOR_A, AUTHOR_B, TS_BASE).length);
        assertNotEquals(sha256HexQuiet(small), sha256HexQuiet(large));
    }

    /** A history signature must never validate as an announcement signature, or vice versa. */
    @Test
    public void domainsAreDistinctAndNeitherPrefixesTheOther()
    {
        final String hist = new String(NgcHistSig.HISTSYNC_DOMAIN, StandardCharsets.US_ASCII);
        final String ann = new String(NgcHistSig.ANNOUNCE_DOMAIN, StandardCharsets.US_ASCII);
        assertNotEquals(hist, ann);
        assertTrue(!ann.startsWith(hist) && !hist.startsWith(ann));
    }

    /** Every field must actually land where the layout says it does. */
    @Test
    public void fieldsAreLaidOutInTheDeclaredOrder()
    {
        final byte[] pre = NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, TS_BASE, new byte[0]);
        int pos = NgcHistSig.HISTSYNC_DOMAIN.length;
        assertArrayEquals(GROUP_A, slice(pre, pos, 32)); pos += 32;
        assertArrayEquals(AUTHOR_A, slice(pre, pos, 32)); pos += 32;
        assertArrayEquals(MSG_ID, slice(pre, pos, 4)); pos += 4;
        // 1754870400 == 0x68993280, big-endian, zero-padded to 8 bytes: most significant first.
        // (The first draft of this assertion had the low half wrong - the implementation was right
        //  and the hand-written expectation was not, which is why it is spelled out here.)
        assertArrayEquals(new byte[]{0, 0, 0, 0, (byte) 0x68, (byte) 0x99, (byte) 0x32, (byte) 0x80},
                          slice(pre, pos, 8));
    }

    // ------------------------------------------------------- fail-closed behaviour

    /**
     * A wrong-sized fixed field yields null, never a short buffer — so a malformed input can never
     * be silently signed or compared.
     */
    @Test
    public void wrongSizedFieldsYieldNull()
    {
        assertNull(NgcHistSig.histSyncPreimage(new byte[31], AUTHOR_A, MSG_ID, TS_BASE, new byte[0]));
        assertNull(NgcHistSig.histSyncPreimage(GROUP_A, new byte[33], MSG_ID, TS_BASE, new byte[0]));
        assertNull(NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, new byte[3], TS_BASE, new byte[0]));
        assertNull(NgcHistSig.histSyncPreimage(null, AUTHOR_A, MSG_ID, TS_BASE, new byte[0]));
        assertNull(NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, TS_BASE, null));
        assertNull(NgcHistSig.announcePreimage(new byte[31], AUTHOR_A, TS_BASE));
        assertNull(NgcHistSig.announcePreimage(AUTHOR_A, new byte[31], TS_BASE));
        assertNull(NgcHistSig.announcePreimage(null, AUTHOR_A, TS_BASE));
    }

    private static byte[] slice(final byte[] src, final int from, final int len)
    {
        final byte[] out = new byte[len];
        System.arraycopy(src, from, out, 0, len);
        return out;
    }

    private static String sha256HexQuiet(final byte[] data)
    {
        try { return sha256Hex(data); } catch (Exception e) { return "err"; }
    }
}

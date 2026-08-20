package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * KHANDAQ (external audit, §11 "Fuzzing and sanitizer program") — mutational fuzzing of the
 * version-0x02 NGC parsers.
 *
 * <p>Why this parser first: it is the top entry on the audit's own target list, it consumes bytes
 * an attacker fully controls, and the two previous reviews both found their defects in exactly this
 * shape of code — a declared length that was trusted, a count that bought an allocation, an index
 * that was never bounded. The hand-written boundary tests next door cover the cases somebody thought
 * of; this covers the ones nobody did.
 *
 * <p>How it works, and why it is not flaky: a corpus of structurally valid packets is mutated —
 * bit flips, byte splices, truncation, extension, length-field tampering — and every result is
 * checked against invariants rather than against an expected value. The seed is FIXED, so a failure
 * is reproducible from the printed case and CI never goes red for a different reason than it went
 * green. Raise {@link #ITERATIONS} locally to search harder; a crasher found that way should be
 * minimised and added to {@code NgcHistSigParserTest} as a named regression, per §11.2.
 *
 * <p>The invariants, all of which are properties the caller in
 * {@code MainActivity.android_tox_callback_group_custom_private_packet_cb_method} relies on:
 * <ol>
 *   <li>No input throws. A parser that throws on hostile input is a remote crash.</li>
 *   <li>A parse either fails cleanly or returns a fully-formed record — never a partial one with a
 *       short buffer or a null field.</li>
 *   <li>Whatever the parser accepts, it round-trips: rebuilding from the parsed fields reproduces
 *       the input byte for byte. That is what stops a packet being interpreted one way here and
 *       another way by the peer that built it.</li>
 *   <li>{@code length} is authoritative over {@code data.length}. The receive buffer may be longer
 *       than the datagram, so a parser that trusted the array would read stale bytes.</li>
 * </ol>
 */
public class NgcHistSigParserFuzzTest
{
    /** Fixed, so a failure is reproducible. */
    private static final long SEED = 0x4b68616e64617100L;
    /** Kept CI-cheap (runs in about a second). Raise it locally to search harder. */
    private static final int ITERATIONS = 200_000;

    private static final int SIGNED_TEXT_FIXED_BEFORE =
            NgcHistSigParser.SIGNED_TEXT_OVERHEAD - NgcHistSigParser.SIGNATURE_SIZE;

    // ------------------------------------------------------------------------------- corpus seeds

    private static byte[] validAnnouncement(final Random rnd)
    {
        final byte[] pub = new byte[NgcHistSig.PUBKEY_SIZE];
        final byte[] sig = new byte[NgcHistSigParser.SIGNATURE_SIZE];
        rnd.nextBytes(pub);
        rnd.nextBytes(sig);
        return NgcHistSigParser.buildAnnouncement(pub, rnd.nextLong(), sig);
    }

    private static byte[] validSignedText(final Random rnd, final int textLen)
    {
        final byte[] msgId = new byte[NgcHistSig.MSG_ID_SIZE];
        final byte[] author = new byte[NgcHistSig.PUBKEY_SIZE];
        final byte[] name = new byte[NgcHistSigParser.PEERNAME_SIZE];
        final byte[] text = new byte[textLen];
        final byte[] sig = new byte[NgcHistSigParser.SIGNATURE_SIZE];
        rnd.nextBytes(msgId);
        rnd.nextBytes(author);
        rnd.nextBytes(name);
        rnd.nextBytes(text);
        rnd.nextBytes(sig);
        return NgcHistSigParser.buildSignedText(msgId, author, rnd.nextLong(), name, text, sig);
    }

    /** Text lengths that sit on a boundary, plus ordinary ones. */
    private static int seedTextLength(final Random rnd)
    {
        switch (rnd.nextInt(8))
        {
            case 0: return 0;
            case 1: return 1;
            case 2: return NgcHistSigParser.MAX_TEXT_BYTES;
            case 3: return NgcHistSigParser.MAX_TEXT_BYTES - 1;
            case 4: return 255;
            case 5: return 256;
            default: return rnd.nextInt(2048);
        }
    }

    // ---------------------------------------------------------------------------------- mutations

    private static byte[] mutate(final Random rnd, final byte[] seed)
    {
        byte[] out = seed.clone();
        final int rounds = 1 + rnd.nextInt(3);
        for (int r = 0; r < rounds; r++)
        {
            if (out.length == 0)
            {
                return out;
            }
            switch (rnd.nextInt(7))
            {
                case 0: // single bit flip
                    out[rnd.nextInt(out.length)] ^= (byte) (1 << rnd.nextInt(8));
                    break;
                case 1: // whole byte replaced
                    out[rnd.nextInt(out.length)] = (byte) rnd.nextInt(256);
                    break;
                case 2: // interesting byte, the values that break sign handling
                {
                    final byte[] interesting = {0x00, 0x01, 0x7f, (byte) 0x80, (byte) 0xff};
                    out[rnd.nextInt(out.length)] = interesting[rnd.nextInt(interesting.length)];
                    break;
                }
                case 3: // truncate
                {
                    final int keep = rnd.nextInt(out.length);
                    final byte[] shorter = new byte[keep];
                    System.arraycopy(out, 0, shorter, 0, keep);
                    out = shorter;
                    break;
                }
                case 4: // extend with noise — the "hide something after the signature" case
                {
                    final byte[] longer = new byte[out.length + 1 + rnd.nextInt(64)];
                    System.arraycopy(out, 0, longer, 0, out.length);
                    for (int i = out.length; i < longer.length; i++)
                    {
                        longer[i] = (byte) rnd.nextInt(256);
                    }
                    out = longer;
                    break;
                }
                case 5: // tamper with the declared text length specifically
                    if (out.length >= SIGNED_TEXT_FIXED_BEFORE)
                    {
                        final int at = SIGNED_TEXT_FIXED_BEFORE - 4;
                        for (int i = 0; i < 4; i++)
                        {
                            out[at + i] = (byte) rnd.nextInt(256);
                        }
                    }
                    break;
                default: // restore a valid header, so mutants keep reaching the deep parse paths
                    if (out.length >= NgcHistSigParser.HEADER_SIZE)
                    {
                        System.arraycopy(NgcHistSigParser.MAGIC, 0, out, 0, NgcHistSigParser.MAGIC.length);
                        out[6] = NgcHistSigParser.VERSION_SIGNED;
                    }
                    break;
            }
        }
        return out;
    }

    // --------------------------------------------------------------------------------- invariants

    private static void checkSignedText(final byte[] data, final int length)
    {
        final NgcHistSigParser.SignedText st = NgcHistSigParser.parseSignedText(data, length);
        if (st == null)
        {
            return;
        }
        assertTrue("accepted a packet isSignedPacket() rejects",
                   NgcHistSigParser.isSignedPacket(data, length, NgcHistSigParser.PKT_SIGNED_TEXT));
        assertNotNull(st.msgId);
        assertNotNull(st.authorPub);
        assertNotNull(st.peerNameRaw);
        assertNotNull(st.textUtf8);
        assertNotNull(st.signature);
        assertEquals(NgcHistSig.MSG_ID_SIZE, st.msgId.length);
        assertEquals(NgcHistSig.PUBKEY_SIZE, st.authorPub.length);
        assertEquals(NgcHistSigParser.PEERNAME_SIZE, st.peerNameRaw.length);
        assertEquals(NgcHistSigParser.SIGNATURE_SIZE, st.signature.length);
        assertTrue("text over the ceiling was accepted",
                   st.textUtf8.length <= NgcHistSigParser.MAX_TEXT_BYTES);
        assertEquals("the packet must be exactly its parts, with no slack to hide bytes in",
                     length, SIGNED_TEXT_FIXED_BEFORE + st.textUtf8.length + NgcHistSigParser.SIGNATURE_SIZE);

        // Round trip: what was accepted must rebuild to the same bytes.
        final byte[] rebuilt = NgcHistSigParser.buildSignedText(
                st.msgId, st.authorPub, st.timestamp, st.peerNameRaw, st.textUtf8, st.signature);
        assertNotNull("a packet the parser accepted could not be rebuilt", rebuilt);
        assertEquals(length, rebuilt.length);
        for (int i = 0; i < length; i++)
        {
            assertEquals("round-trip differs at byte " + i, data[i], rebuilt[i]);
        }
    }

    private static void checkAnnouncement(final byte[] data, final int length)
    {
        final NgcHistSigParser.Announcement a = NgcHistSigParser.parseAnnouncement(data, length);
        if (a == null)
        {
            return;
        }
        assertEquals("the announcement has no variable part; its length is exact",
                     NgcHistSigParser.ANNOUNCE_PACKET_SIZE, length);
        assertEquals(NgcHistSig.PUBKEY_SIZE, a.hskPub.length);
        assertEquals(NgcHistSigParser.SIGNATURE_SIZE, a.signature.length);

        final byte[] rebuilt = NgcHistSigParser.buildAnnouncement(a.hskPub, a.validFromTs, a.signature);
        assertNotNull(rebuilt);
        assertEquals(length, rebuilt.length);
        for (int i = 0; i < length; i++)
        {
            assertEquals("round-trip differs at byte " + i, data[i], rebuilt[i]);
        }
    }

    // -------------------------------------------------------------------------------------- tests

    @Test
    public void mutatedPacketsNeverCrashAndNeverParsePartially()
    {
        final Random rnd = new Random(SEED);
        for (int i = 0; i < ITERATIONS; i++)
        {
            final byte[] seed = rnd.nextBoolean()
                                ? validAnnouncement(rnd)
                                : validSignedText(rnd, seedTextLength(rnd));
            final byte[] data = mutate(rnd, seed);
            // `length` is what the transport says arrived, and it is deliberately allowed to
            // disagree with the buffer — that disagreement is the bug class being probed.
            final int length = rnd.nextInt(10) == 0
                               ? rnd.nextInt(data.length + 64)
                               : data.length;
            try
            {
                checkSignedText(data, length);
                checkAnnouncement(data, length);
            }
            catch (AssertionError e)
            {
                throw new AssertionError("iteration " + i + " (seed " + SEED + "): " + e.getMessage(), e);
            }
            catch (RuntimeException e)
            {
                fail("iteration " + i + " (seed " + SEED + ") threw " + e);
            }
        }
    }

    @Test
    public void aLengthLongerThanTheBufferIsNeverParsed()
    {
        // The receive buffer can outlive its datagram. A parser that trusted data.length here would
        // read whatever the previous packet left behind and sign-verify against stale memory.
        final Random rnd = new Random(SEED + 1);
        for (int i = 0; i < 20_000; i++)
        {
            final byte[] packet = rnd.nextBoolean()
                                  ? validAnnouncement(rnd)
                                  : validSignedText(rnd, rnd.nextInt(512));
            final int overstated = packet.length + 1 + rnd.nextInt(4096);
            assertNull(NgcHistSigParser.parseAnnouncement(packet, overstated));
            assertNull(NgcHistSigParser.parseSignedText(packet, overstated));
        }
    }

    @Test
    public void nullAndDegenerateInputsAreRefusedQuietly()
    {
        for (int len : new int[] {Integer.MIN_VALUE, -1, 0, 1, 7, 8, 111, 112, 113, Integer.MAX_VALUE})
        {
            assertNull(NgcHistSigParser.parseAnnouncement(null, len));
            assertNull(NgcHistSigParser.parseSignedText(null, len));
            assertNull(NgcHistSigParser.parseAnnouncement(new byte[0], len));
            assertNull(NgcHistSigParser.parseSignedText(new byte[0], len));
        }
    }

    @Test
    public void everyValidSeedStillParses()
    {
        // A fuzzer that only ever produced garbage would pass while testing nothing. This proves the
        // corpus is actually reaching the accept path, boundary lengths included.
        final Random rnd = new Random(SEED + 2);
        for (int i = 0; i < 500; i++)
        {
            final byte[] ann = validAnnouncement(rnd);
            assertNotNull(NgcHistSigParser.parseAnnouncement(ann, ann.length));

            final byte[] txt = validSignedText(rnd, seedTextLength(rnd));
            assertNotNull(NgcHistSigParser.parseSignedText(txt, txt.length));
        }
    }
}

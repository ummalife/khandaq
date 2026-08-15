package com.zoffcc.applications.trifa;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * KHANDAQ (external audit #2, finding 1) — the Ed25519 JNI bridges, exercised for real.
 *
 * The JVM tests cover the pre-image bytes; they cannot cover the three native functions, which only
 * exist inside libjni-c-toxcore.so. That library is produced by a 4-ABI CI job, so until it has been
 * rebuilt and dropped into app/nativelibs the symbols are simply absent and every call throws
 * UnsatisfiedLinkError. "The symbol appears in the .so" is also not the same claim as "calling it
 * from Java works and produces the right bytes" — that is what this file establishes, on an emulator
 * or a device.
 *
 * What is actually proven here:
 *   - the three symbols resolve from Java (correct JNI names, correct signatures);
 *   - sign and verify agree with each other AND with the frozen cross-platform vectors, so this
 *     platform signs the same bytes the desktop and iOS do — a disagreement there fails open in
 *     production while every local test still passes, which is the failure this whole design guards
 *     against;
 *   - the malformed-input contract holds: every bridge fails CLOSED, and a tampered signature,
 *     message or key is rejected rather than silently accepted.
 */
@RunWith(AndroidJUnit4.class)
public class NgcHistSigNativeTest
{
    // The same fixtures the JVM vector test uses, so a digest computed here is directly comparable.
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

    private static final byte[] GROUP_A = counting32();
    private static final byte[] AUTHOR_A = repeated(0xAA, 32);
    private static final byte[] MSG_ID = new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef};
    private static final long TS_BASE = 1754870400L;

    /** The "ascii-basic" vector from ngc_histsync_vectors.py, mirrored by the desktop and iOS. */
    private static final String ASCII_BASIC_DIGEST =
            "33599061b75b2c487120a845450367ee880c931d6c00095960f8c3828f3457ed";

    private static final int PUBKEY_SIZE = 32;
    private static final int SECRETKEY_SIZE = 64;
    private static final int SIGNATURE_SIZE = 64;

    @BeforeClass
    public static void loadNativeLibrary()
    {
        // Fails loudly rather than as a confusing UnsatisfiedLinkError inside the first test.
        System.loadLibrary("jni-c-toxcore");
    }

    private static String hex(final byte[] data) throws Exception
    {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (final byte x : data) { sb.append(String.format("%02x", x)); }
        return sb.toString();
    }

    private static String sha256Hex(final byte[] data) throws Exception
    {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] preimage()
    {
        return NgcHistSig.histSyncPreimage(GROUP_A, AUTHOR_A, MSG_ID, TS_BASE,
                                           "hello".getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- the bytes being signed

    @Test
    public void the_preimage_this_device_signs_matches_the_frozen_vector() throws Exception
    {
        // If this fails, everything below is signing the wrong bytes and would still "pass".
        assertEquals(ASCII_BASIC_DIGEST, sha256Hex(preimage()));
    }

    // ---------------------------------------------------------------- keypair

    @Test
    public void keypair_fills_both_halves_and_they_are_not_constant()
    {
        final byte[] pub1 = new byte[PUBKEY_SIZE];
        final byte[] sec1 = new byte[SECRETKEY_SIZE];
        final byte[] pub2 = new byte[PUBKEY_SIZE];
        final byte[] sec2 = new byte[SECRETKEY_SIZE];

        assertEquals(0, MainActivity.khandaq_ed25519_keypair(pub1, sec1));
        assertEquals(0, MainActivity.khandaq_ed25519_keypair(pub2, sec2));

        // A bridge that forgot to copy back would leave these all-zero, which is the failure mode
        // that would silently give every install the same "identity".
        assertFalse(Arrays.equals(new byte[PUBKEY_SIZE], pub1));
        assertFalse(Arrays.equals(new byte[SECRETKEY_SIZE], sec1));
        assertFalse(Arrays.equals(pub1, pub2));

        // libsodium stores the public key in the tail of the secret key; if that does not hold, the
        // two halves did not come from one generation.
        assertArrayEquals(pub1, Arrays.copyOfRange(sec1, SECRETKEY_SIZE - PUBKEY_SIZE, SECRETKEY_SIZE));
    }

    @Test
    public void keypair_rejects_wrong_sized_buffers()
    {
        assertEquals(-1, MainActivity.khandaq_ed25519_keypair(new byte[PUBKEY_SIZE - 1], new byte[SECRETKEY_SIZE]));
        assertEquals(-1, MainActivity.khandaq_ed25519_keypair(new byte[PUBKEY_SIZE], new byte[SECRETKEY_SIZE + 1]));
        assertEquals(-1, MainActivity.khandaq_ed25519_keypair(null, new byte[SECRETKEY_SIZE]));
        assertEquals(-1, MainActivity.khandaq_ed25519_keypair(new byte[PUBKEY_SIZE], null));
    }

    // ---------------------------------------------------------------- sign + verify round trip

    @Test
    public void a_signature_this_device_produces_verifies_on_this_device()
    {
        final byte[] pub = new byte[PUBKEY_SIZE];
        final byte[] sec = new byte[SECRETKEY_SIZE];
        assertEquals(0, MainActivity.khandaq_ed25519_keypair(pub, sec));

        final byte[] msg = preimage();
        final byte[] sig = new byte[SIGNATURE_SIZE];
        assertEquals(0, MainActivity.khandaq_ed25519_sign(msg, msg.length, sec, sig));
        assertFalse(Arrays.equals(new byte[SIGNATURE_SIZE], sig));

        assertEquals(0, MainActivity.khandaq_ed25519_verify(msg, msg.length, sig, pub));
    }

    @Test
    public void signing_is_deterministic_for_the_same_key_and_message()
    {
        // Ed25519 is deterministic; two different signatures here would mean the bridge is feeding
        // the primitive something that varies - a stale buffer, or the wrong length.
        final byte[] pub = new byte[PUBKEY_SIZE];
        final byte[] sec = new byte[SECRETKEY_SIZE];
        assertEquals(0, MainActivity.khandaq_ed25519_keypair(pub, sec));

        final byte[] msg = preimage();
        final byte[] sigA = new byte[SIGNATURE_SIZE];
        final byte[] sigB = new byte[SIGNATURE_SIZE];
        assertEquals(0, MainActivity.khandaq_ed25519_sign(msg, msg.length, sec, sigA));
        assertEquals(0, MainActivity.khandaq_ed25519_sign(msg, msg.length, sec, sigB));
        assertArrayEquals(sigA, sigB);
    }

    // ---------------------------------------------------------------- rejection contract

    @Test
    public void verify_rejects_a_tampered_signature_message_or_key()
    {
        final byte[] pub = new byte[PUBKEY_SIZE];
        final byte[] sec = new byte[SECRETKEY_SIZE];
        assertEquals(0, MainActivity.khandaq_ed25519_keypair(pub, sec));

        final byte[] msg = preimage();
        final byte[] sig = new byte[SIGNATURE_SIZE];
        assertEquals(0, MainActivity.khandaq_ed25519_sign(msg, msg.length, sec, sig));

        final byte[] badSig = sig.clone();
        badSig[0] ^= 0x01;
        assertEquals(-1, MainActivity.khandaq_ed25519_verify(msg, msg.length, badSig, pub));

        final byte[] badMsg = msg.clone();
        badMsg[badMsg.length - 1] ^= 0x01;
        assertEquals(-1, MainActivity.khandaq_ed25519_verify(badMsg, badMsg.length, sig, pub));

        // A different key must not validate — this is the whole point of the finding: attribution.
        final byte[] otherPub = new byte[PUBKEY_SIZE];
        final byte[] otherSec = new byte[SECRETKEY_SIZE];
        assertEquals(0, MainActivity.khandaq_ed25519_keypair(otherPub, otherSec));
        assertNotEquals(0, MainActivity.khandaq_ed25519_verify(msg, msg.length, sig, otherPub));
    }

    @Test
    public void verify_rejects_a_length_that_overruns_the_array()
    {
        // The declared length arrives from Java and must never be trusted to describe the array.
        final byte[] msg = preimage();
        final byte[] sig = new byte[SIGNATURE_SIZE];
        final byte[] pub = new byte[PUBKEY_SIZE];
        assertEquals(-1, MainActivity.khandaq_ed25519_verify(msg, msg.length + 1, sig, pub));
        assertEquals(-1, MainActivity.khandaq_ed25519_verify(msg, 0, sig, pub));
        assertEquals(-1, MainActivity.khandaq_ed25519_verify(null, 1, sig, pub));
    }

    @Test
    public void sign_rejects_malformed_arguments_and_leaves_no_partial_signature()
    {
        final byte[] pub = new byte[PUBKEY_SIZE];
        final byte[] sec = new byte[SECRETKEY_SIZE];
        assertEquals(0, MainActivity.khandaq_ed25519_keypair(pub, sec));

        final byte[] msg = preimage();
        final byte[] sig = new byte[SIGNATURE_SIZE];

        assertEquals(-1, MainActivity.khandaq_ed25519_sign(msg, msg.length + 1, sec, sig));
        assertEquals(-1, MainActivity.khandaq_ed25519_sign(msg, msg.length, new byte[SECRETKEY_SIZE - 1], sig));
        assertEquals(-1, MainActivity.khandaq_ed25519_sign(msg, msg.length, sec, new byte[SIGNATURE_SIZE - 1]));
        assertEquals(-1, MainActivity.khandaq_ed25519_sign(null, 1, sec, sig));

        // Nothing above may have written into the signature buffer.
        assertArrayEquals(new byte[SIGNATURE_SIZE], sig);
    }
}

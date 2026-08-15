package com.zoffcc.applications.trifa;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeTrue;

/**
 * KHANDAQ (audit #2 finding 1, step 2) — the HSK actually surviving a write and a read.
 *
 * The unit tests cover the decision (when a stored key may be reused); they cannot cover the part
 * that has burned this project before — whether the bytes reach the encrypted profile database and
 * come back. set_g_opts swallows its own failures, which is exactly how the audit3 #1 cancel
 * tombstone silently stopped persisting, so "it returned without throwing" proves nothing.
 *
 * Requires an open profile: g_opts lives in the orma/SQLCipher database, and without it these are
 * skipped rather than passed, so a green run cannot be mistaken for coverage that did not happen.
 */
@RunWith(AndroidJUnit4.class)
public class NgcHskStoreDeviceTest
{
    private static final String OWNER_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OWNER_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @BeforeClass
    public static void loadNativeLibrary()
    {
        System.loadLibrary("jni-c-toxcore");
        // The test runs INSIDE the app's own process, so starting the launcher brings the profile
        // database up the same way a normal launch does. Without this the instrumentation process
        // has no open profile and every case below would skip.
        startAppAndWaitForProfile();
    }

    private static void startAppAndWaitForProfile()
    {
        try
        {
            final android.content.Context ctx =
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
            final android.content.Intent launch =
                    ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (launch != null)
            {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
            }
        }
        catch (Throwable ignored)
        {
        }

        // Opening the profile involves disk and SQLCipher; poll rather than guess a sleep.
        for (int i = 0; i < 60; i++)
        {
            if (profileIsOpen())
            {
                return;
            }
            try
            {
                Thread.sleep(1000L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** True when g_opts is usable; everything here is meaningless otherwise. */
    private static boolean profileIsOpen()
    {
        try
        {
            HelperGeneric.set_g_opts("kqhsk_probe", "1");
            return "1".equals(HelperGeneric.get_g_opts("kqhsk_probe"));
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    private static void clearRows()
    {
        HelperGeneric.set_g_opts(NgcHskStore.G_OPTS_KEY_OWNER, "");
        HelperGeneric.set_g_opts(NgcHskStore.G_OPTS_KEY_PUB, "");
        HelperGeneric.set_g_opts(NgcHskStore.G_OPTS_KEY_SEC, "");
    }

    @Test
    public void the_key_is_generated_once_and_reloaded_verbatim()
    {
        assumeTrue("no open profile — g_opts unavailable", profileIsOpen());
        clearRows();

        final NgcHskStore.Hsk first = NgcHskStore.ensureKeypair(OWNER_A);
        assertNotNull("generation or persistence failed", first);
        assertEquals(NgcHskStore.PUBKEY_SIZE, first.pub.length);
        assertEquals(NgcHskStore.SECRETKEY_SIZE, first.sec.length);
        assertFalse(Arrays.equals(new byte[NgcHskStore.PUBKEY_SIZE], first.pub));

        // Second call must LOAD, not mint: a key that changes under the same identity would
        // invalidate every announcement already made with the first one.
        final NgcHskStore.Hsk second = NgcHskStore.ensureKeypair(OWNER_A);
        assertNotNull(second);
        assertArrayEquals(first.pub, second.pub);
        assertArrayEquals(first.sec, second.sec);
    }

    @Test
    public void the_reloaded_key_still_signs_and_verifies()
    {
        assumeTrue("no open profile — g_opts unavailable", profileIsOpen());
        clearRows();

        final NgcHskStore.Hsk stored = NgcHskStore.ensureKeypair(OWNER_A);
        assertNotNull(stored);

        // Round-trip through the database, then use what came back: this is what catches bytes
        // mangled by hex encoding or truncated by the row, which a length check alone would miss.
        final NgcHskStore.Hsk reloaded = NgcHskStore.ensureKeypair(OWNER_A);
        assertNotNull(reloaded);

        final byte[] msg = NgcHistSig.histSyncPreimage(new byte[32], new byte[32], new byte[4], 1L,
                                                        "hsk".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final byte[] sig = new byte[64];
        assertEquals(0, MainActivity.khandaq_ed25519_sign(msg, msg.length, reloaded.sec, sig));
        assertEquals(0, MainActivity.khandaq_ed25519_verify(msg, msg.length, sig, reloaded.pub));
    }

    @Test
    public void changing_the_tox_identity_replaces_the_key()
    {
        // #244 made this real: the Tox identity can change under a live profile. A key still bound
        // to the old identity would sign as someone this profile no longer is.
        assumeTrue("no open profile — g_opts unavailable", profileIsOpen());
        clearRows();

        final NgcHskStore.Hsk forA = NgcHskStore.ensureKeypair(OWNER_A);
        assertNotNull(forA);
        final NgcHskStore.Hsk forB = NgcHskStore.ensureKeypair(OWNER_B);
        assertNotNull(forB);
        assertFalse("the key must not be reused across identities", Arrays.equals(forA.pub, forB.pub));

        // ...and the new one must now be the stored one.
        assertArrayEquals(forB.pub, NgcHskStore.ensureKeypair(OWNER_B).pub);
    }

    @Test
    public void the_announcement_we_emit_parses_and_verifies_as_a_peer_would_check_it()
    {
        // The whole cryptographic path of step 3 except the socket: build the packet the way the
        // group emitter does, then take it apart and check it exactly as the receiving side does.
        // A peer that cannot verify our announcement can never verify our history either, and the
        // failure would be silent — it looks like an unsigned old client.
        assumeTrue("no open profile — g_opts unavailable", profileIsOpen());
        clearRows();

        final long validFrom = 1_755_000_000_000L;
        final byte[] packet = NgcHskAnnounce.buildSelfAnnouncement(OWNER_A, validFrom);
        assertNotNull("announcement could not be built", packet);
        assertEquals(NgcHistSigParser.ANNOUNCE_PACKET_SIZE, packet.length);

        final NgcHistSigParser.Announcement ann =
                NgcHistSigParser.parseAnnouncement(packet, packet.length);
        assertNotNull("our own packet did not survive our own parser", ann);
        assertEquals(validFrom, ann.validFromTs);

        // The receiver rebuilds the pre-image from the sender's TOX key (which it takes from the
        // transport, not the payload) and the announced key, then verifies the self-signature.
        final byte[] toxPub = NgcHskStore.fromHex(OWNER_A);
        final byte[] preimage = NgcHistSig.announcePreimage(toxPub, ann.hskPub, ann.validFromTs);
        assertNotNull(preimage);
        assertEquals(0, MainActivity.khandaq_ed25519_verify(
                preimage, preimage.length, ann.signature, ann.hskPub));

        // And it must fail when the claimed Tox identity is not the one the signature covers —
        // otherwise anyone could replay someone else's announcement under their own name.
        final byte[] wrongPreimage =
                NgcHistSig.announcePreimage(NgcHskStore.fromHex(OWNER_B), ann.hskPub, ann.validFromTs);
        assertNotEquals(0, MainActivity.khandaq_ed25519_verify(
                wrongPreimage, wrongPreimage.length, ann.signature, ann.hskPub));
    }

    @Test
    public void without_a_tox_identity_no_key_is_minted()
    {
        assumeTrue("no open profile — g_opts unavailable", profileIsOpen());
        clearRows();

        assertNull(NgcHskStore.ensureKeypair(null));
        assertNull(NgcHskStore.ensureKeypair(""));
        assertNull(NgcHskStore.ensureKeypair("not-hex"));
        // Nothing was written, so a later call with a real identity still generates cleanly.
        assertNull(HelperGeneric.get_g_opts(NgcHskStore.G_OPTS_KEY_OWNER) == null
                   ? null : (HelperGeneric.get_g_opts(NgcHskStore.G_OPTS_KEY_OWNER).isEmpty() ? null : "written"));
    }
}

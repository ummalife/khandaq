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

    /** Two groups, because the key is per group and the tests must be able to tell them apart. */
    private static final String GROUP_1 =
            "1111111111111111111111111111111111111111111111111111111111111111";
    private static final String GROUP_2 =
            "2222222222222222222222222222222222222222222222222222222222222222";

    /** Our public key IN a group — the identity an announcement's signature actually binds to. */
    private static final String SELF_GROUP_PUB =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @BeforeClass
    public static void loadNativeLibrary()
    {
        System.loadLibrary("jni-c-toxcore");
        // KHANDAQ (2026-08-21): this used to call a local startAppAndWaitForProfile() that merely
        // launched the app and waited. It never worked: a fresh install lands on OnboardingActivity
        // and waits for a human, so no profile ever opened and all six cases below SKIPPED on every
        // run — a green tick over nothing. KhandaqFirstRun walks the actual first-run flow.
        KhandaqFirstRun.ensureProfile();
    }

    /** True when g_opts is usable; everything here is meaningless otherwise. */
    private static boolean profileIsOpen()
    {
        return KhandaqFirstRun.profileIsOpen();
    }

    private static void clearRows(final String group)
    {
        HelperGeneric.set_g_opts(NgcHskStore.rowKey(NgcHskStore.G_OPTS_KEY_OWNER, group), "");
        HelperGeneric.set_g_opts(NgcHskStore.rowKey(NgcHskStore.G_OPTS_KEY_PUB, group), "");
        HelperGeneric.set_g_opts(NgcHskStore.rowKey(NgcHskStore.G_OPTS_KEY_SEC, group), "");
    }

    private static void clearRows()
    {
        clearRows(GROUP_1);
        clearRows(GROUP_2);
    }

    @Test
    public void the_key_is_generated_once_and_reloaded_verbatim()
    {
        assumeTrue("no open profile — g_opts unavailable", profileIsOpen());
        clearRows();

        final NgcHskStore.Hsk first = NgcHskStore.ensureKeypair(OWNER_A, GROUP_1);
        assertNotNull("generation or persistence failed", first);
        assertEquals(NgcHskStore.PUBKEY_SIZE, first.pub.length);
        assertEquals(NgcHskStore.SECRETKEY_SIZE, first.sec.length);
        assertFalse(Arrays.equals(new byte[NgcHskStore.PUBKEY_SIZE], first.pub));

        // Second call must LOAD, not mint: a key that changes under the same identity would
        // invalidate every announcement already made with the first one.
        final NgcHskStore.Hsk second = NgcHskStore.ensureKeypair(OWNER_A, GROUP_1);
        assertNotNull(second);
        assertArrayEquals(first.pub, second.pub);
        assertArrayEquals(first.sec, second.sec);
    }

    @Test
    public void each_group_gets_its_own_key()
    {
        // NGC gives a member a different public key per group on purpose. One HSK shared across
        // groups would re-link the same person wherever two of their groups share an observer —
        // a correlator this feature must not add while fixing authorship.
        assumeTrue("no open profile — g_opts unavailable", profileIsOpen());
        clearRows();

        final NgcHskStore.Hsk one = NgcHskStore.ensureKeypair(OWNER_A, GROUP_1);
        final NgcHskStore.Hsk two = NgcHskStore.ensureKeypair(OWNER_A, GROUP_2);
        assertNotNull(one);
        assertNotNull(two);
        assertFalse("the same key must not serve two groups", Arrays.equals(one.pub, two.pub));

        // And each stays put: re-reading one group must not disturb the other.
        assertArrayEquals(one.pub, NgcHskStore.ensureKeypair(OWNER_A, GROUP_1).pub);
        assertArrayEquals(two.pub, NgcHskStore.ensureKeypair(OWNER_A, GROUP_2).pub);
    }

    @Test
    public void the_reloaded_key_still_signs_and_verifies()
    {
        assumeTrue("no open profile — g_opts unavailable", profileIsOpen());
        clearRows();

        final NgcHskStore.Hsk stored = NgcHskStore.ensureKeypair(OWNER_A, GROUP_1);
        assertNotNull(stored);

        // Round-trip through the database, then use what came back: this is what catches bytes
        // mangled by hex encoding or truncated by the row, which a length check alone would miss.
        final NgcHskStore.Hsk reloaded = NgcHskStore.ensureKeypair(OWNER_A, GROUP_1);
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

        final NgcHskStore.Hsk forA = NgcHskStore.ensureKeypair(OWNER_A, GROUP_1);
        assertNotNull(forA);
        final NgcHskStore.Hsk forB = NgcHskStore.ensureKeypair(OWNER_B, GROUP_1);
        assertNotNull(forB);
        assertFalse("the key must not be reused across identities", Arrays.equals(forA.pub, forB.pub));

        // ...and the new one must now be the stored one.
        assertArrayEquals(forB.pub, NgcHskStore.ensureKeypair(OWNER_B, GROUP_1).pub);
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
        final byte[] packet =
                NgcHskAnnounce.buildSelfAnnouncement(OWNER_A, GROUP_1, SELF_GROUP_PUB, validFrom);
        assertNotNull("announcement could not be built", packet);
        assertEquals(NgcHistSigParser.ANNOUNCE_PACKET_SIZE, packet.length);

        final NgcHistSigParser.Announcement ann =
                NgcHistSigParser.parseAnnouncement(packet, packet.length);
        assertNotNull("our own packet did not survive our own parser", ann);
        assertEquals(validFrom, ann.validFromTs);

        // The receiver rebuilds the pre-image from the sender's key IN THIS GROUP — which it reads
        // off the transport, not out of the payload — and the announced key, then verifies.
        final byte[] groupPub = NgcHskStore.fromHex(SELF_GROUP_PUB);
        final byte[] preimage = NgcHistSig.announcePreimage(groupPub, ann.hskPub, ann.validFromTs);
        assertNotNull(preimage);
        assertEquals("a peer could not verify our own announcement", 0,
                     MainActivity.khandaq_ed25519_verify(
                             preimage, preimage.length, ann.signature, ann.hskPub));

        // The regression this test exists for: signing over the profile's TOX key instead of the
        // group key produced packets that verified nowhere. Both sides looked right in isolation —
        // the sender signed one identity, the receiver could only ever reconstruct the other — and
        // it took two devices on a wire to see it. Pinned here so it cannot come back quietly.
        final byte[] toxKeyPreimage =
                NgcHistSig.announcePreimage(NgcHskStore.fromHex(OWNER_A), ann.hskPub, ann.validFromTs);
        assertNotEquals("the signature must not be over the profile's Tox key", 0,
                        MainActivity.khandaq_ed25519_verify(
                                toxKeyPreimage, toxKeyPreimage.length, ann.signature, ann.hskPub));

        // And it must fail when the claimed group identity is not the one the signature covers —
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

        assertNull(NgcHskStore.ensureKeypair(null, GROUP_1));
        assertNull(NgcHskStore.ensureKeypair("", GROUP_1));
        assertNull(NgcHskStore.ensureKeypair("not-hex", GROUP_1));
        // No group means no row to write to, so nothing may be minted then either.
        assertNull(NgcHskStore.ensureKeypair(OWNER_A, null));
        assertNull(NgcHskStore.ensureKeypair(OWNER_A, ""));

        // Nothing was written, so a later call with a real identity still generates cleanly.
        final String ownerRow = NgcHskStore.rowKey(NgcHskStore.G_OPTS_KEY_OWNER, GROUP_1);
        final String written = HelperGeneric.get_g_opts(ownerRow);
        assertNull(written == null || written.isEmpty() ? null : "written");
    }
}

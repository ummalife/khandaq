package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;

import static com.zoffcc.applications.trifa.NgcHskStore.PUBKEY_SIZE;
import static com.zoffcc.applications.trifa.NgcHskStore.SECRETKEY_SIZE;
import static com.zoffcc.applications.trifa.NgcHskStore.fromHex;
import static com.zoffcc.applications.trifa.NgcHskStore.needsFreshKey;
import static com.zoffcc.applications.trifa.NgcHskStore.toHex;
import static com.zoffcc.applications.trifa.NgcHskStore.toxPubFromToxId;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (audit #2 finding 1, step 2) — when the stored history-signing key may be trusted.
 *
 * The decision matters in both directions. Reuse a key that does not belong to this Tox identity and
 * every signature is attributed to someone we are not; regenerate one that was fine and every peer
 * has to relearn the announcement. The torn-write case is the subtle one: the rows are three separate
 * g_opts writes, so a process death in the middle is a real state, not a hypothetical.
 */
public class NgcHskStoreTest
{
    private static final String OWNER_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OWNER_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    /** A consistent pair: libsodium keeps the public key in the tail of the secret key. */
    private static byte[] secretFor(final byte[] pub)
    {
        final byte[] sec = new byte[SECRETKEY_SIZE];
        for (int i = 0; i < SECRETKEY_SIZE - PUBKEY_SIZE; i++) { sec[i] = (byte) (i + 1); }
        System.arraycopy(pub, 0, sec, SECRETKEY_SIZE - PUBKEY_SIZE, PUBKEY_SIZE);
        return sec;
    }

    private static byte[] pubBytes(final int fill)
    {
        final byte[] p = new byte[PUBKEY_SIZE];
        Arrays.fill(p, (byte) fill);
        return p;
    }

    // ---------------------------------------------------------------- the key may be reused

    @Test
    public void a_complete_consistent_key_for_this_identity_is_reused()
    {
        final byte[] pub = pubBytes(0x11);
        assertFalse(needsFreshKey(OWNER_A, toHex(pub), toHex(secretFor(pub)), OWNER_A));
    }

    @Test
    public void owner_comparison_ignores_hex_case()
    {
        // Tox IDs are shown upper-case in the UI and stored lower-case here; a case mismatch must
        // not look like an identity change and silently re-mint the key.
        final byte[] pub = pubBytes(0x11);
        assertFalse(needsFreshKey(OWNER_A.toUpperCase(), toHex(pub), toHex(secretFor(pub)), OWNER_A));
    }

    // ---------------------------------------------------------------- the key must be replaced

    @Test
    public void a_key_belonging_to_another_identity_is_replaced()
    {
        // #244 made this concrete: the Tox identity really can change under a live profile.
        final byte[] pub = pubBytes(0x11);
        assertTrue(needsFreshKey(OWNER_B, toHex(pub), toHex(secretFor(pub)), OWNER_A));
    }

    @Test
    public void any_missing_row_counts_as_no_key()
    {
        final byte[] pub = pubBytes(0x11);
        final String sec = toHex(secretFor(pub));
        assertTrue(needsFreshKey(null, toHex(pub), sec, OWNER_A));
        assertTrue(needsFreshKey(OWNER_A, null, sec, OWNER_A));
        assertTrue(needsFreshKey(OWNER_A, toHex(pub), null, OWNER_A));
    }

    @Test
    public void a_torn_write_is_detected_by_the_public_key_in_the_secret_tail()
    {
        // Secret from one generation, public from another: the rows are written separately, so this
        // is what a crash between two set_g_opts calls actually leaves behind.
        final byte[] pubOld = pubBytes(0x11);
        final byte[] pubNew = pubBytes(0x22);
        assertTrue(needsFreshKey(OWNER_A, toHex(pubNew), toHex(secretFor(pubOld)), OWNER_A));
    }

    @Test
    public void malformed_or_wrong_sized_material_is_replaced()
    {
        final byte[] pub = pubBytes(0x11);
        final String sec = toHex(secretFor(pub));
        assertTrue(needsFreshKey(OWNER_A, "zzzz", sec, OWNER_A));
        assertTrue(needsFreshKey(OWNER_A, toHex(pub), "abc", OWNER_A));
        assertTrue(needsFreshKey(OWNER_A, toHex(new byte[PUBKEY_SIZE - 1]), sec, OWNER_A));
    }

    // ---------------------------------------------------------------- no identity yet

    @Test
    public void without_a_usable_tox_identity_nothing_is_regenerated()
    {
        // Answering "yes, regenerate" here would mint a key bound to nothing, on every call, before
        // the profile is even up. ensureKeypair refuses separately; this pins the decision too.
        assertFalse(needsFreshKey(null, null, null, null));
        assertFalse(needsFreshKey(null, null, null, ""));
        assertFalse(needsFreshKey(null, null, null, "not-hex"));
        assertFalse(needsFreshKey(null, null, null, OWNER_A.substring(0, 62)));
    }

    // ---------------------------------------------------------------- helpers

    @Test
    public void hex_round_trips_and_rejects_junk()
    {
        final byte[] data = {0x00, 0x0f, (byte) 0xf0, (byte) 0xff, 0x7f, (byte) 0x80};
        assertEquals("000ff0ff7f80", toHex(data));
        assertArrayEquals(data, fromHex("000ff0ff7f80"));
        assertArrayEquals(data, fromHex("000FF0FF7F80"));
        assertNull(fromHex("abc"));      // odd length
        assertNull(fromHex("zz"));       // not hex
        assertNull(fromHex(""));
        assertNull(fromHex(null));
    }

    @Test
    public void the_tox_public_key_is_the_head_of_the_tox_id()
    {
        // A Tox ID is pubkey + nospam + checksum; signing must bind to the pubkey alone, since
        // nospam changes without the identity changing.
        final String toxId = OWNER_A + "1a2b3c4d" + "e5f6";
        assertEquals(OWNER_A, toxPubFromToxId(toxId));
        assertEquals(OWNER_A, toxPubFromToxId(OWNER_A.toUpperCase() + "1a2b3c4de5f6"));
        assertNull(toxPubFromToxId(OWNER_A.substring(0, 60)));
        assertNull(toxPubFromToxId(null));
    }
}

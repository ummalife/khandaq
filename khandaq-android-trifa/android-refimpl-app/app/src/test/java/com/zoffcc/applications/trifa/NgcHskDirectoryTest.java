package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;

import static com.zoffcc.applications.trifa.NgcHskDirectory.Decision;
import static com.zoffcc.applications.trifa.NgcHskDirectory.REPLACE_GRACE_MS;
import static com.zoffcc.applications.trifa.NgcHskDirectory.Record;
import static com.zoffcc.applications.trifa.NgcHskDirectory.decide;
import static com.zoffcc.applications.trifa.NgcHskDirectory.decode;
import static com.zoffcc.applications.trifa.NgcHskDirectory.encode;
import static com.zoffcc.applications.trifa.NgcHskDirectory.rowKey;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * KHANDAQ (audit #2 finding 1, step 3) — when we change our mind about a peer's signing key.
 *
 * This is where the design either holds or quietly fails. The announcement is self-signed, so a
 * valid signature proves only that the sender holds the key it is announcing — anyone can mint a
 * keypair and announce it as anyone. What makes an announcement attributable is the live transport,
 * which toxcore authenticates. So every case where we would adopt a NEW key for a peer we already
 * know is an impersonation opportunity, and each is pinned here.
 */
public class NgcHskDirectoryTest
{
    private static final long NOW = 1_700_000_000_000L;

    private static byte[] key(final int fill)
    {
        final byte[] k = new byte[NgcHskStore.PUBKEY_SIZE];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    private static Record known(final byte[] k, final long lastSeenMs)
    {
        return new Record(k, lastSeenMs - 1000L, lastSeenMs);
    }

    // ---------------------------------------------------------------- the ordinary cases

    @Test
    public void an_unknown_peer_is_learned()
    {
        assertEquals(Decision.LEARN, decide(null, key(0x11), true, NOW));
        // Learning does not require a live connection: the first key is all we have, and refusing it
        // would leave the peer permanently unverifiable.
        assertEquals(Decision.LEARN, decide(null, key(0x11), false, NOW));
    }

    @Test
    public void the_same_key_only_refreshes()
    {
        assertEquals(Decision.REFRESH, decide(known(key(0x11), NOW), key(0x11), true, NOW));
        assertEquals(Decision.REFRESH, decide(known(key(0x11), NOW), key(0x11), false, NOW));
    }

    // ---------------------------------------------------------------- the impersonation guard

    @Test
    public void a_different_key_from_a_peer_that_is_not_live_never_takes_over()
    {
        // Not connected means the sender is not attributable — precisely what the self-signature
        // cannot establish. Even after the grace period, this must not replace.
        final Record r = known(key(0x11), NOW - (REPLACE_GRACE_MS * 3));
        assertEquals(Decision.RECORD_ONLY, decide(r, key(0x22), false, NOW));
    }

    @Test
    public void a_different_key_does_not_take_over_while_the_old_one_is_in_use()
    {
        // A second key appearing next to a key still being seen is the shape of an attack, not of a
        // rotation, however live the sender is.
        final Record r = known(key(0x11), NOW - 1000L);
        assertEquals(Decision.RECORD_ONLY, decide(r, key(0x22), true, NOW));
    }

    @Test
    public void replacement_needs_both_a_live_peer_and_an_expired_key()
    {
        final byte[] old = key(0x11);
        // one second short of the grace period: still refused
        assertEquals(Decision.RECORD_ONLY,
                     decide(known(old, NOW - (REPLACE_GRACE_MS - 1000L)), key(0x22), true, NOW));
        // exactly at the grace period, live peer: allowed
        assertEquals(Decision.REPLACE,
                     decide(known(old, NOW - REPLACE_GRACE_MS), key(0x22), true, NOW));
    }

    @Test
    public void a_backwards_clock_cannot_hurry_a_replacement()
    {
        // last_seen in the future yields a negative age, which must read as "recently seen", not as
        // "long expired".
        final Record r = known(key(0x11), NOW + (REPLACE_GRACE_MS * 2));
        assertEquals(Decision.RECORD_ONLY, decide(r, key(0x22), true, NOW));
    }

    // ---------------------------------------------------------------- malformed input

    @Test
    public void malformed_keys_change_nothing()
    {
        assertEquals(Decision.IGNORE, decide(known(key(0x11), NOW), null, true, NOW));
        assertEquals(Decision.IGNORE, decide(known(key(0x11), NOW), new byte[31], true, NOW));
        assertEquals(Decision.IGNORE, decide(null, new byte[33], true, NOW));
    }

    @Test
    public void a_corrupt_stored_record_is_treated_as_unknown_rather_than_trusted()
    {
        // Otherwise a damaged row would keep a peer unverifiable forever with no way back.
        assertEquals(Decision.LEARN, decide(new Record(null, NOW, NOW), key(0x22), false, NOW));
        assertEquals(Decision.LEARN, decide(new Record(new byte[10], NOW, NOW), key(0x22), false, NOW));
    }

    // ---------------------------------------------------------------- storage encoding

    @Test
    public void a_record_round_trips_through_its_row()
    {
        final Record r = new Record(key(0xAB), 111L, 222L);
        final Record back = decode(encode(r));
        assertArrayEquals(r.hskPub, back.hskPub);
        assertEquals(111L, back.firstSeenMs);
        assertEquals(222L, back.lastSeenMs);
    }

    @Test
    public void a_damaged_row_decodes_to_null_rather_than_half_a_record()
    {
        assertNull(decode(null));
        assertNull(decode(""));
        assertNull(decode("nothex:1:2"));
        assertNull(decode(NgcHskStore.toHex(key(0x11)) + ":1"));
        assertNull(decode(NgcHskStore.toHex(key(0x11)) + ":1:notanumber"));
        assertNull(decode(NgcHskStore.toHex(new byte[16]) + ":1:2"));
    }

    @Test
    public void row_keys_are_case_stable()
    {
        // A group id or pubkey spelled in two cases must not become two rows, or a peer would get a
        // second, competing key for free.
        assertEquals(rowKey("AABB", "CCDD"), rowKey("aabb", "ccdd"));
        assertNull(rowKey(null, "cc"));
        assertNull(rowKey("aa", null));
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.Decision;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.KEY_STALE_MS;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.allowsNotification;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.decide;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (audit 2026-08-21, K-01) — when an unsigned history record about a signing author is a
 * downgrade attempt rather than an old client.
 *
 * <p>Both directions are load-bearing and both are easy to get wrong. Reject too eagerly and a peer
 * that reinstalled loses its history permanently and silently; reject too little and the entire
 * signed-history mechanism is bypassed by simply not sending the signature.
 */
public class NgcHistoryDowngradePolicyTest
{
    private static final long NOW = 1_700_000_000_000L;

    private static NgcHskDirectory.Record knownKey(final long lastSeenMs)
    {
        final byte[] pub = new byte[NgcHskStore.PUBKEY_SIZE];
        for (int i = 0; i < pub.length; i++)
        {
            pub[i] = (byte) i;
        }
        return new NgcHskDirectory.Record(pub, lastSeenMs - 1000L, lastSeenMs);
    }

    /** The whole point: a signed record about a signing author is accepted, obviously. */
    @Test
    public void aVerifiedRowIsAccepted()
    {
        assertEquals(Decision.ACCEPT_VERIFIED, decide(knownKey(NOW), true, NOW));
    }

    /** The attack: the author can sign, said so minutes ago, and this record carries no signature. */
    @Test
    public void anUnsignedRowFromAnAuthorWeKnowCanSignIsRefused()
    {
        assertEquals(Decision.REJECT_DOWNGRADE, decide(knownKey(NOW - 60_000L), false, NOW));
    }

    /**
     * The transition case, and by far the most common one today: nobody has heard this author
     * announce a key, so there is no downgrade to detect and refusing would just delete history.
     */
    @Test
    public void anAuthorWhoNeverAnnouncedAKeyIsUnaffected()
    {
        assertEquals(Decision.ACCEPT_LEGACY, decide(null, false, NOW));
    }

    /** A directory row with no key in it is the same situation as no row at all. */
    @Test
    public void aDirectoryRecordWithoutAKeyIsTreatedAsUnknown()
    {
        assertEquals(Decision.ACCEPT_LEGACY,
                     decide(new NgcHskDirectory.Record(null, NOW, NOW), false, NOW));
    }

    /**
     * Anti-lockout. Past the grace period the directory would accept a REPLACEMENT key, so a peer
     * whose key we have not seen in that long may genuinely have lost it — and must not be locked out
     * of relayed history on the strength of a key it no longer has.
     */
    @Test
    public void anAuthorWhoseKeyIsStaleIsGivenTheBenefitOfTheDoubt()
    {
        assertEquals(Decision.ACCEPT_KEY_STALE, decide(knownKey(NOW - KEY_STALE_MS), false, NOW));
        assertEquals(Decision.ACCEPT_KEY_STALE, decide(knownKey(NOW - KEY_STALE_MS - 1L), false, NOW));
    }

    /** One millisecond inside the window is still inside it. */
    @Test
    public void theBoundaryIsExactlyTheDirectoryReplacementGrace()
    {
        assertEquals(Decision.REJECT_DOWNGRADE,
                     decide(knownKey(NOW - KEY_STALE_MS + 1L), false, NOW));
        assertEquals(KEY_STALE_MS, NgcHskDirectory.REPLACE_GRACE_MS);
    }

    /**
     * A clock that moved backwards must not silently start dropping a peer's history. Same choice
     * NgcHskDirectory already makes for the same reason.
     */
    @Test
    public void aBackwardsClockAcceptsRatherThanRejects()
    {
        assertEquals(Decision.ACCEPT_KEY_STALE, decide(knownKey(NOW + 60_000L), false, NOW));
    }

    /**
     * The case that separates a real guard from an accidental one, and the reason the iOS twin needs
     * an explicit comparison rather than a subtraction: a device whose clock has not been set yet,
     * plus a stored last-seen near the top of the range. Java's signed arithmetic gives a negative
     * difference here; C's unsigned arithmetic gives a small positive one, which would read as "seen
     * moments ago" and refuse the peer's history. Both must accept.
     * scripts/check-ios-downgrade-policy.py asserts the same case against the Objective-C source.
     */
    @Test
    public void anUnsetClockWithACorruptLastSeenDoesNotReject()
    {
        assertEquals(Decision.ACCEPT_KEY_STALE, decide(knownKey(Long.MAX_VALUE), false, 1000L));
    }

    /** A verdict outranks staleness — if the signature covers the row, nothing else matters. */
    @Test
    public void aVerdictWinsOverEveryOtherCondition()
    {
        assertEquals(Decision.ACCEPT_VERIFIED, decide(knownKey(NOW - KEY_STALE_MS * 10L), true, NOW));
        assertEquals(Decision.ACCEPT_VERIFIED, decide(null, true, NOW));
    }

    // ------------------------------------------------------------------ notifications (DESIGN §4.5)

    @Test
    public void aVerifiedRowMayNotify()
    {
        assertTrue(allowsNotification(true, false));
    }

    /**
     * The peer that sent us the row IS the claimed author, so toxcore authenticated the attribution —
     * the same guarantee a live message carries.
     */
    @Test
    public void aRowSentByItsOwnAuthorMayNotify()
    {
        assertTrue(allowsNotification(false, true));
    }

    /**
     * The forgery: peer X relays a record claiming author Y, unsigned. It is still stored and still
     * rendered with the "sender not verified" marker — but it does not get to raise a banner with
     * Y's name on it.
     */
    @Test
    public void anUnverifiedRowRelayedByAThirdPartyDoesNotNotify()
    {
        assertFalse(allowsNotification(false, false));
    }
}

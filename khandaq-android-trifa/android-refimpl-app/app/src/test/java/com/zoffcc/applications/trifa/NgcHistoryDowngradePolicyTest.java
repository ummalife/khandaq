package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.Decision;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.KEY_STALE_MS;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.allowsNotification;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.claimsOwnKey;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.decide;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.isRecentEnoughToNotify;
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

    // ------------------------------------------------------------------ the age of a row (0.2.65)

    /**
     * The tester's report: rows of the backlog that every reconnect hands over notified one by one, an hour after
     * they were written. They are stored without a notification now.
     */
    @Test
    public void theBacklogOfAReconnectDoesNotNotify()
    {
        assertFalse(isRecentEnoughToNotify(NOW - 2L * 60L * 60L * 1000L, NOW));
        assertFalse(isRecentEnoughToNotify(NOW - 5L * 60L * 1000L, NOW));
        assertFalse(isRecentEnoughToNotify(NOW - AirBandRule.LATE_DELIVERY_MS - 1L, NOW));
    }

    /** History that only caught up with a message being sent right now notifies, like a late live message. */
    @Test
    public void aRowAsFreshAsALateLiveMessageNotifies()
    {
        assertTrue(isRecentEnoughToNotify(NOW, NOW));
        assertTrue(isRecentEnoughToNotify(NOW - 2_000L, NOW));
        assertTrue(isRecentEnoughToNotify(NOW - AirBandRule.LATE_DELIVERY_MS, NOW));
    }

    /** The sender's clock may run ahead of ours; the receive window already bounds by how much. */
    @Test
    public void aSendTimeAheadOfOurClockCountsAsFresh()
    {
        assertTrue(isRecentEnoughToNotify(NOW + 60_000L, NOW));
    }

    /** Without a send time the age is unknown, and an unknown age does not interrupt anyone. */
    @Test
    public void aRowWithoutASendTimeDoesNotNotify()
    {
        assertFalse(isRecentEnoughToNotify(0L, NOW));
        assertFalse(isRecentEnoughToNotify(-1L, NOW));
    }

    /**
     * Both paths from history to a notification, text and file, ask the age first: before the rate limit, so the
     * backlog does not spend the budget of what is fresh, and before the notification itself. A live message does
     * not ask: it comes as it is sent.
     */
    @Test
    public void bothHistoryPathsAskTheAgeBeforeTheBudgetAndTheNotification()
    {
        final String helper = AntispamOnRequestTest.code(read(JAVA + "HelperGroup.java"));
        final String ask = "NgcHistoryDowngradePolicy.isRecentEnoughToNotify(m.sent_timestamp, System.currentTimeMillis())";
        for (final String path : new String[]{"static void group_message_add_from_sync(",
                                              "private static void group_file_add_from_sync("})
        {
            final String body = method(helper, path);
            assertBefore(body, ask, "NgcHistorySyncBudget.allowNotification(");
            assertBefore(body, ask, "change_msg_notification(");
            assertTrue(path, body.indexOf(ask) > body.indexOf("m.sent_timestamp = "));
        }
        assertFalse(method(helper, "static void android_tox_callback_group_message_cb_method_wrapper(")
                            .contains("isRecentEnoughToNotify("));
        assertFalse(method(helper, "static void handle_incoming_group_file(").contains("isRecentEnoughToNotify("));
    }

    private static final String OWN = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789";
    private static final String STRANGER = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";

    /**
     * KHANDAQ (0.2.57, review of the desktop's Community code): a history record that names our own key in the
     * group as its author is dropped. Our own rows are here already; this one came from someone else, signed by no
     * one, and would show as a stranger's message or, made our own, skip the Community hold.
     */
    @Test
    public void aRecordNamingOurOwnKeyIsDroppedWhateverItsCase()
    {
        assertTrue(claimsOwnKey(OWN, OWN));
        assertTrue(claimsOwnKey(OWN.toLowerCase(Locale.ROOT), OWN));
        assertTrue(claimsOwnKey(OWN, OWN.toLowerCase(Locale.ROOT)));
        assertFalse(claimsOwnKey(OWN, STRANGER));
        assertFalse(claimsOwnKey(OWN, null));
        assertFalse(claimsOwnKey(OWN, ""));
    }

    /** Not knowing our own key, a record cannot be told apart from ours: it is dropped rather than shown. */
    @Test
    public void withoutOurOwnKeyARecordIsDropped()
    {
        assertTrue(claimsOwnKey(null, STRANGER));
        assertTrue(claimsOwnKey(null, null));
    }

    /**
     * A failed read of our own key is no key: the JNI read gives "-1" when toxcore has none for us in the group, and
     * anything but 64 hexadecimal characters is not a key either. A record is dropped then, as without a key.
     */
    @Test
    public void aFailedReadOfOurOwnKeyIsNoKeyAndTheRecordIsDropped()
    {
        for (final String notAKey : new String[]{"-1", "", " ", OWN.substring(1), OWN + "0",
                                                 "ZZ" + OWN.substring(2), OWN.substring(2) + "-1"})
        {
            assertTrue(notAKey, claimsOwnKey(notAKey, STRANGER));
            assertTrue(notAKey, claimsOwnKey(notAKey, null));
            assertTrue(notAKey, claimsOwnKey(notAKey, "-1"));
        }
        // A key read well still tells a stranger's record from ours.
        assertFalse(claimsOwnKey(OWN.toLowerCase(Locale.ROOT), STRANGER));
        assertTrue(claimsOwnKey(OWN.toLowerCase(Locale.ROOT), OWN));
        // Why: the JNI read of our key gives "-1" when toxcore has none for us in the group.
        final String jni = read("../../jni-c-toxcore/jni-c-toxcore.c");
        final int start = jni.indexOf(
                "Java_com_zoffcc_applications_trifa_MainActivity_tox_1group_1self_1get_1public_1key(");
        assertTrue(start > 0);
        final String body = jni.substring(start, jni.indexOf("\n}\n", start));
        assertTrue(body.contains("result = (*env)->NewStringUTF(env, \"-1\");"));
    }

    /**
     * Every path that makes a row of a history record asks before the row exists: the group's own history, its
     * text and its files, and the relay's (ToxProxy), whose text goes through the same insert. A file from history
     * is always a row that came in, never one of ours that the Community hold would let through.
     */
    @Test
    public void everyPathFromHistoryToARowDropsARecordNamingOurOwnKeyFirst()
    {
        final String helper = read(JAVA + "HelperGroup.java");
        final String ask = "if (NgcHistoryDowngradePolicy.claimsOwnKey(tox_group_self_get_public_key(group_number),";
        assertBefore(method(helper, "static void handle_incoming_sync_group_message("), ask,
                     "group_message_add_from_sync(");
        assertBefore(method(helper, "static void handle_incoming_sync_group_file("), ask, "group_file_add_from_sync(");
        assertBefore(method(helper, "static void group_message_add_from_sync("),
                     "if (NgcHistoryDowngradePolicy.claimsOwnKey(", "insert_into_group_message_db(");
        final String fileRow = method(helper, "private static void group_file_add_from_sync(");
        assertTrue(fileRow.contains("m.direction = 0;"));
        assertFalse(fileRow.contains("m.direction = 1;"));
        // The relay's record takes that same insert.
        assertTrue(read(JAVA + "MainActivity.java").contains(
                "group_message_add_from_sync(real_conference_id, syncer_pubkey, sender_peer_num, real_sender_peer_pubkey,"));
    }

    private static void assertBefore(final String source, final String first, final String then)
    {
        final int at = source.indexOf(first);
        assertTrue(first, at >= 0);
        assertTrue(then, source.indexOf(then) > at);
    }

    /** A method of a class at the usual indent: from its signature to the brace that closes it. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        return source.substring(start, source.indexOf("\n    }\n", start));
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

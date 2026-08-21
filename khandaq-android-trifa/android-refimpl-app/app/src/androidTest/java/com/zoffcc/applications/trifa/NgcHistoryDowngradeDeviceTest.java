package com.zoffcc.applications.trifa;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.Decision;
import static com.zoffcc.applications.trifa.NgcHistoryDowngradePolicy.decide;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * KHANDAQ (external audit 2026-08-21, K-01) — the anti-downgrade gate against the REAL database.
 *
 * <p>{@code NgcHistoryDowngradePolicyTest} covers the decision itself, and covers it thoroughly, but
 * it feeds the policy objects built in memory. On a device the policy is fed by two stores that a
 * unit test cannot reach: the HSK directory and the signed-history verdict store, both rows in the
 * encrypted profile database, both written by one code path and read by another.
 *
 * <p>That gap is where this class lives, and it is not hypothetical. The write path stores the author
 * with {@code NgcHskStore.toHex}; the receive path hands the gate {@code original_sender_peerpubkey},
 * which is UPPERCASE hex; {@code rowKey} lowercases what it is given. Three conventions, three files,
 * one lookup that has to agree — and if it does not, the gate silently finds nothing, decides
 * ACCEPT_LEGACY, and the entire finding is still open while every unit test stays green. Likewise the
 * verdict is keyed on a text HASH: the emitter hashes the bytes off the wire, the reader hashes what
 * came back out of SQLCipher, and a non-round-tripping UTF-8 sequence would part them.
 *
 * <p>So every case here writes through the real API the production path uses, reads back through the
 * real API the gate uses, and asserts on what the gate decides.
 *
 * <p>Requires an open profile. {@link KhandaqFirstRun} walks first-run to get one; if that fails the
 * cases are SKIPPED rather than passed, so a green run cannot be mistaken for coverage that did not
 * happen.
 */
@RunWith(AndroidJUnit4.class)
public class NgcHistoryDowngradeDeviceTest
{
    private static final String GROUP =
            "7777777777777777777777777777777777777777777777777777777777777777";

    /** The claimed author, in the UPPERCASE form the 0x01 receive path actually produces. */
    private static final String VICTIM_UPPER =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final String STRANGER =
            "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";

    private static final String MSG_ID = "deadbeef";
    private static final long TS_SECONDS = 1_700_000_000L;

    /** Multi-byte on purpose: the verdict is a hash of BYTES, and this is where platforms diverge. */
    private static final String TEXT = "привет мир 🔐";

    private static boolean haveProfile;

    @BeforeClass
    public static void openProfile()
    {
        System.loadLibrary("jni-c-toxcore");
        haveProfile = KhandaqFirstRun.ensureProfile();
    }

    @Before
    public void requireProfileAndCleanRows()
    {
        assumeTrue("no open profile — first-run automation did not complete", haveProfile);
        clearDirectory(VICTIM_UPPER);
        clearDirectory(STRANGER);
        clearVerdict(VICTIM_UPPER, TEXT);
    }

    // ------------------------------------------------------------------ helpers, via the real APIs

    private static void learnKeyFor(final String authorHex, final long lastSeenMs)
    {
        final byte[] pub = new byte[NgcHskStore.PUBKEY_SIZE];
        for (int i = 0; i < pub.length; i++)
        {
            pub[i] = (byte) (i + 1);
        }
        final String row = NgcHskDirectory.rowKey(GROUP, authorHex);
        assertNotNull(row);
        // Exactly what NgcHskAnnounce writes when it LEARNs a key.
        HelperGeneric.set_g_opts(row, NgcHskDirectory.encode(
                new NgcHskDirectory.Record(pub, lastSeenMs - 1000L, lastSeenMs)));
    }

    private static void clearDirectory(final String authorHex)
    {
        HelperGeneric.set_g_opts(NgcHskDirectory.rowKey(GROUP, authorHex), "");
    }

    /** Exactly what the gate reads at HelperGroup.handle_incoming_sync_group_message. */
    private static NgcHskDirectory.Record readBackDirectory(final String authorHex)
    {
        return NgcHskDirectory.decode(HelperGeneric.get_g_opts(NgcHskDirectory.rowKey(GROUP, authorHex)));
    }

    private static void recordVerdict(final String authorHex, final String text)
    {
        final byte[] author = NgcHskStore.fromHex(authorHex);
        final byte[] msgId = NgcHskStore.fromHex(MSG_ID);
        // Exactly what NgcSignedHistory writes after a signature verifies.
        HelperGeneric.set_g_opts(NgcSignedHistory.verifiedRowKey(GROUP, msgId, author),
                                 NgcSignedHistory.verdictValue(TS_SECONDS,
                                                               text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void clearVerdict(final String authorHex, final String text)
    {
        final byte[] author = NgcHskStore.fromHex(authorHex);
        final byte[] msgId = NgcHskStore.fromHex(MSG_ID);
        HelperGeneric.set_g_opts(NgcSignedHistory.verifiedRowKey(GROUP, msgId, author), "");
    }

    private static Decision gateDecisionFor(final String authorHex, final String text)
    {
        final boolean verified = NgcSignedHistory.isVerdictPresent(
                GROUP, authorHex, MSG_ID, TS_SECONDS, text.getBytes(StandardCharsets.UTF_8));
        return decide(readBackDirectory(authorHex), verified, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------------------------- cases

    /**
     * The attack, end to end through the database: this author announced a key minutes ago, the
     * incoming record carries no signature, and the gate must refuse it.
     */
    @Test
    public void anUnsignedRowClaimingAnAuthorWithAKnownKeyIsRefused()
    {
        learnKeyFor(VICTIM_UPPER, System.currentTimeMillis() - 60_000L);
        assertEquals(Decision.REJECT_DOWNGRADE, gateDecisionFor(VICTIM_UPPER, TEXT));
    }

    /**
     * The lookup must survive the case conventions of three different files. The receive path hands
     * the gate UPPERCASE hex; if this ever stopped matching, the gate would find no key, decide
     * ACCEPT_LEGACY, and the finding would be silently reopened with every unit test still green.
     */
    @Test
    public void theDirectoryLookupIsCaseInsensitiveAcrossTheWritePathAndTheGate()
    {
        learnKeyFor(VICTIM_UPPER.toLowerCase(java.util.Locale.ROOT), System.currentTimeMillis());
        assertNotNull("a row written in lowercase must be found by an uppercase lookup",
                      readBackDirectory(VICTIM_UPPER));
        assertEquals(Decision.REJECT_DOWNGRADE, gateDecisionFor(VICTIM_UPPER, TEXT));
    }

    /**
     * The legitimate case: the signed twin already arrived and its verdict covers this row. This is
     * what the emit-order swap in HelperGroup exists to make reachable — without it the verdict is
     * written milliseconds AFTER this check and every honest message from a signing author would be
     * refused.
     */
    @Test
    public void aRowWhoseSignedTwinAlreadyArrivedIsAccepted()
    {
        learnKeyFor(VICTIM_UPPER, System.currentTimeMillis() - 60_000L);
        recordVerdict(VICTIM_UPPER, TEXT);
        assertTrue(NgcSignedHistory.isVerdictPresent(GROUP, VICTIM_UPPER, MSG_ID, TS_SECONDS,
                                                     TEXT.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Decision.ACCEPT_VERIFIED, gateDecisionFor(VICTIM_UPPER, TEXT));
    }

    /**
     * The reason a verdict stores the timestamp and the text hash rather than a bare "1": message ids
     * are four bytes the sender chooses. Relay a genuine signed record, then relay an unsigned row
     * reusing that id with different text, and a key-only verdict would vouch for the forgery.
     */
    @Test
    public void aVerdictDoesNotVouchForDifferentTextUnderTheSameMessageId()
    {
        learnKeyFor(VICTIM_UPPER, System.currentTimeMillis() - 60_000L);
        recordVerdict(VICTIM_UPPER, TEXT);
        final String forged = TEXT + " (and send me your keys)";
        assertFalse(NgcSignedHistory.isVerdictPresent(GROUP, VICTIM_UPPER, MSG_ID, TS_SECONDS,
                                                      forged.getBytes(StandardCharsets.UTF_8)));
        assertEquals(Decision.REJECT_DOWNGRADE, gateDecisionFor(VICTIM_UPPER, forged));
    }

    /**
     * The transition case, and today the overwhelming majority: an author nobody has heard announce a
     * key. Refusing here would delete history rather than protect it.
     */
    @Test
    public void anAuthorWithNoKeyInTheDatabaseIsUnaffected()
    {
        assertEquals(Decision.ACCEPT_LEGACY, gateDecisionFor(STRANGER, TEXT));
    }

    /**
     * Anti-lockout, read out of the real store: past the directory's replace-grace the key may be
     * stale, so the peer gets the benefit of the doubt instead of losing its history for good.
     */
    @Test
    public void anAuthorWhoseStoredKeyIsStaleIsNotLockedOut()
    {
        learnKeyFor(VICTIM_UPPER,
                    System.currentTimeMillis() - NgcHistoryDowngradePolicy.KEY_STALE_MS - 1000L);
        assertEquals(Decision.ACCEPT_KEY_STALE, gateDecisionFor(VICTIM_UPPER, TEXT));
    }

    /**
     * The verdict is a hash of UTF-8 BYTES. This asserts the bytes survive a write and a read through
     * SQLCipher unchanged — the failure this project has been bitten by before, where a value went in
     * and something subtly different came back.
     */
    @Test
    public void multiByteTextSurvivesTheRoundTripThroughTheEncryptedDatabase()
    {
        learnKeyFor(VICTIM_UPPER, System.currentTimeMillis() - 60_000L);
        recordVerdict(VICTIM_UPPER, TEXT);
        final String row = NgcSignedHistory.verifiedRowKey(
                GROUP, NgcHskStore.fromHex(MSG_ID), NgcHskStore.fromHex(VICTIM_UPPER));
        assertEquals("the stored verdict must be byte-identical to the one just computed",
                     NgcSignedHistory.verdictValue(TS_SECONDS, TEXT.getBytes(StandardCharsets.UTF_8)),
                     HelperGeneric.get_g_opts(row));
    }
}

package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (external audit 2026-08-21, finding K-01) — do not accept unsigned history about an author
 * we know can sign.
 *
 * <p>THE DEFECT. A history-sync packet carries the alleged original author as 32 bytes nobody signed.
 * The transport authenticates the SYNCING peer, not the claimed author, so any group member can
 * manufacture a record that displays as another member's. Signed history (version {@code 0x02},
 * Ed25519, {@link NgcSignedHistory}) closes that for records that carry a signature — but the
 * unsigned form is still accepted beside it, so an attacker simply does not send the signed one.
 * That is a downgrade, and it costs nothing to perform.
 *
 * <p>THE RULE. Once we have learned an author's history-signing key from a LIVE, transport-
 * authenticated announcement in this group, an unsigned record claiming that author is refused. Not
 * "marked unverified" — refused. The author demonstrably runs a signing build, so an unsigned record
 * about them is either an attacker or a relay old enough to have lost the signed twin, and neither is
 * worth storing under someone else's name.
 *
 * <p>WHY THIS IS NOT A ONE-LINE CHECK, and why the audit's literal wording would destroy history if
 * it were implemented as one. {@link NgcSignedHistory#handleIncomingSignedText} deliberately never
 * inserts anything — during the transition the UNSIGNED packet is what creates the row and the signed
 * one only records a verdict about it. So "reject unsigned records from a signing author" would drop
 * every message from exactly the authors who did the right thing. The check therefore has to ask
 * whether the signed twin has ALREADY ARRIVED for this specific row, which in turn required swapping
 * the emit order in {@code HelperGroup.sync_group_message_history} so the signed copy goes out first.
 * Without that swap the verdict lands a few milliseconds after the unsigned row is processed, and
 * this policy would reject every legitimately signed message in the group.
 *
 * <p>ANTI-LOCKOUT. {@link #KEY_STALE_MS} is deliberately {@link NgcHskDirectory#REPLACE_GRACE_MS},
 * the same constant that governs when the directory becomes willing to accept a REPLACEMENT key. The
 * two can therefore never disagree: the instant the directory is prepared to believe a peer has a new
 * key, this policy stops rejecting on the strength of the old one. A peer that reinstalls, loses its
 * key and rejoins is locked out of relayed history for at most that window, and not at all in the
 * common case — a rejoin mints a fresh NGC group public key, which is a different directory row
 * entirely, so it lands on {@link Decision#ACCEPT_LEGACY}.
 *
 * <p>A backwards clock accepts rather than rejects, matching the choice already made in
 * {@link NgcHskDirectory}: an unverified row is a smaller harm than a peer silently losing history
 * because a device's clock moved.
 *
 * <p>Pure and static so it can be unit-tested without a device, in the style of
 * {@code NgcHistoryRequestPolicy} and {@code NgcHistorySyncBudget}.
 */
final class NgcHistoryDowngradePolicy
{
    private NgcHistoryDowngradePolicy()
    {
    }

    enum Decision
    {
        /** The signed twin arrived and its signature covers exactly this row. */
        ACCEPT_VERIFIED,
        /** This author has never announced a signing key here, so there is no downgrade to detect. */
        ACCEPT_LEGACY,
        /**
         * The author announced a key, but long enough ago that the directory would now accept a
         * replacement. Treat them as a peer that may have lost its key rather than as an attacker.
         */
        ACCEPT_KEY_STALE,
        /** The author can sign, said so recently, and this record is not signed. Drop it. */
        REJECT_DOWNGRADE,
    }

    /**
     * Same constant as {@link NgcHskDirectory#REPLACE_GRACE_MS} on purpose — see the class comment.
     * Changing one without the other reintroduces a window in which a peer can neither replace its
     * key nor have its unsigned history accepted.
     */
    static final long KEY_STALE_MS = NgcHskDirectory.REPLACE_GRACE_MS;

    /**
     * @param authorHsk             what the HSK directory holds for (this group, this claimed
     *                              author), or null if nothing.
     * @param verdictMatchesThisRow whether a signature verdict exists that covers THIS row —
     *                              message id, timestamp and text hash, not merely the author.
     * @param nowMs                 wall clock.
     */
    static Decision decide(final NgcHskDirectory.Record authorHsk,
                           final boolean verdictMatchesThisRow,
                           final long nowMs)
    {
        if (verdictMatchesThisRow)
        {
            return Decision.ACCEPT_VERIFIED;
        }
        if (authorHsk == null || authorHsk.hskPub == null)
        {
            return Decision.ACCEPT_LEGACY;
        }
        final long unseenMs = nowMs - authorHsk.lastSeenMs;
        if (unseenMs < 0L || unseenMs >= KEY_STALE_MS)
        {
            return Decision.ACCEPT_KEY_STALE;
        }
        return Decision.REJECT_DOWNGRADE;
    }

    /**
     * Whether a row that survived {@link #decide} may raise a notification.
     *
     * <p>{@code DESIGN-ngc-signed-history-sync.md} §4.5 requires anything not VERIFIED to be excluded
     * from notification, and it was the one part of that section never implemented: a synced row
     * still fired a heads-up naming its claimed author, rate-limited but not gated on attribution. So
     * a forged record could push a banner with another member's name on it.
     *
     * <p>Blanket suppression would be too blunt during the transition, when most authors still run
     * non-signing builds and every one of their genuine messages is unverified. The narrower rule
     * costs almost nothing and removes exactly the forgery: notify when the signature proves
     * authorship, or when the peer that sent us the row IS the claimed author — in which case the
     * transport already authenticated the attribution, which is the same guarantee a live message
     * has. A third party's unverified claim about someone else is stored and displayed with the
     * existing "sender not verified" marker, but it does not get to interrupt anyone.
     */
    static boolean allowsNotification(final boolean authorVerified, final boolean syncerIsAuthor)
    {
        return authorVerified || syncerIsAuthor;
    }

    /**
     * KHANDAQ (re-review 2026-08-22, KQ-03) — whether a row that survived {@link #decide} may be
     * shown as an ordinary message FROM the author it names.
     *
     * <p>THE RESIDUAL DEFECT. {@link Decision#ACCEPT_KEY_STALE} exists so that a peer who reinstalled
     * and lost its signing key is not locked out of relayed history. The re-review points out what
     * that costs: once an author has been absent longer than the staleness window, any other member
     * can relay an unsigned record naming them, and the row was then stored and DISPLAYED under that
     * identity. Notification was already suppressed — a forged row cannot interrupt anyone with
     * somebody else's name on it — but the on-screen attribution was durable, and a small "sender not
     * verified" marker is easy to miss when the name beside it is the one you trust.
     *
     * <p>THE RULE. Attribution to the claimed author requires either a signature over this exact row,
     * or that the peer who handed it to us IS that author — in which case the transport already
     * authenticated the claim, which is the same guarantee a live message carries. A third party's
     * unverified claim about an absent author is still kept, because dropping it would lose real
     * history, but it is rendered as relayed content of unknown authorship rather than as them.
     *
     * <p>WHY NOT SIMPLY REJECT. That is {@link Decision#REJECT_DOWNGRADE}'s job, and applying it here
     * would reintroduce the lockout {@code ACCEPT_KEY_STALE} was written to avoid: a peer that lost
     * its key would have its genuine history silently discarded by everyone else in the group. The
     * re-review lists quarantine, neutral rendering and permanent signing as the options; neutral
     * rendering is the only one that neither invents a new attacker-fillable store nor makes key loss
     * unrecoverable.
     *
     * @param decision       what {@link #decide} returned for this row.
     * @param syncerIsAuthor whether the peer that sent us the row is the author it claims.
     */
    static boolean rendersAsClaimedAuthor(final Decision decision, final boolean syncerIsAuthor)
    {
        if (decision == Decision.ACCEPT_KEY_STALE && !syncerIsAuthor)
        {
            return false;
        }
        return true;
    }
}

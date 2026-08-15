package com.zoffcc.applications.trifa;

import java.util.Arrays;

/**
 * KHANDAQ (external audit #2, finding 1, step 3) — which history-signing key we believe belongs to
 * a peer, and when we are willing to change our mind.
 *
 * This is the security boundary of the whole design. History-sync packets are relayed, so their
 * claimed author is unauthenticated; the fix is to verify an Ed25519 signature against the author's
 * HSK. That only helps if learning the HSK is itself hard to spoof — and the announcement carrying
 * it is self-signed, which proves possession of the announced key but says nothing about WHO owns
 * it. The binding to a Tox identity comes from the live transport: toxcore authenticates the sender
 * of a group packet, so an announcement received live is attributable, while a relayed one is not.
 *
 * Hence the rule from §4.2, and hence the care here: FIRST ANNOUNCEMENT WINS. A later announcement
 * naming a different key for the same Tox public key is recorded but does NOT take over, because
 * silently accepting replacements hands the impersonation door back to whoever manages to get a
 * packet in first after a peer drops. Replacement is allowed only when the peer is connected RIGHT
 * NOW (so the transport vouches for the sender) and the key being replaced has not been seen for
 * longer than a grace period (so a live peer cannot be talked over).
 *
 * The decision is a pure function so it can be tested exhaustively; the storage half lives in
 * g_opts, the same encrypted profile rows the HSK itself uses, for the same reason — a new table
 * means a schema migration, and migrations here have destroyed profiles (#244).
 */
final class NgcHskDirectory
{
    /** How long a known key must go unseen before a different one may replace it. */
    static final long REPLACE_GRACE_MS = 24L * 60L * 60L * 1000L;

    /** g_opts row prefix: kqhskdir_<group_id>|<peer_tox_pubkey> -> hskPubHex:firstSeen:lastSeen */
    static final String G_OPTS_PREFIX = "kqhskdir_";

    private NgcHskDirectory()
    {
    }

    /** What to do with an announcement that has already been verified as self-signed. */
    enum Decision
    {
        /** Nothing known yet — learn it. */
        LEARN,
        /** Same key we already trust — just bump last_seen. */
        REFRESH,
        /** A different key, but we are not willing to switch. Record it, keep trusting the old one. */
        RECORD_ONLY,
        /** A different key, and every condition for switching is met. */
        REPLACE,
        /** Malformed input; change nothing. */
        IGNORE
    }

    /** What we currently believe about one (group, peer) pair. */
    static final class Record
    {
        final byte[] hskPub;
        final long firstSeenMs;
        final long lastSeenMs;

        Record(final byte[] hskPub, final long firstSeenMs, final long lastSeenMs)
        {
            this.hskPub = hskPub;
            this.firstSeenMs = firstSeenMs;
            this.lastSeenMs = lastSeenMs;
        }
    }

    /**
     * @param existing what we already believe, or null if this pair is new
     * @param incomingHskPub the announced key, 32 bytes
     * @param peerConnectedNow whether toxcore currently has a live connection to this peer — the
     *                         only thing that makes the sender attributable at all
     * @param nowMs current time
     *
     * A clock moved backwards yields a negative age, which fails the grace comparison and lands on
     * RECORD_ONLY. That is the safe direction: a backwards clock can never be used to hurry a
     * replacement through.
     */
    static Decision decide(final Record existing, final byte[] incomingHskPub,
                           final boolean peerConnectedNow, final long nowMs)
    {
        if (incomingHskPub == null || incomingHskPub.length != NgcHskStore.PUBKEY_SIZE)
        {
            return Decision.IGNORE;
        }
        if (existing == null || existing.hskPub == null
            || existing.hskPub.length != NgcHskStore.PUBKEY_SIZE)
        {
            return Decision.LEARN;
        }
        if (Arrays.equals(existing.hskPub, incomingHskPub))
        {
            return Decision.REFRESH;
        }

        // A different key for a peer we already know. Everything below is the impersonation guard.
        if (!peerConnectedNow)
        {
            // The announcement reached us relayed or from a peer that is not live: unattributable,
            // which is exactly the property the signature cannot supply.
            return Decision.RECORD_ONLY;
        }
        if ((nowMs - existing.lastSeenMs) < REPLACE_GRACE_MS)
        {
            // The key we trust is still in active use. A second key appearing beside it is the
            // shape of an attack, not of a rotation.
            return Decision.RECORD_ONLY;
        }
        return Decision.REPLACE;
    }

    /** g_opts row key for one pair. Lower-cased so two spellings cannot become two rows. */
    static String rowKey(final String groupId, final String peerToxPubHex)
    {
        if (groupId == null || peerToxPubHex == null)
        {
            return null;
        }
        return G_OPTS_PREFIX + groupId.toLowerCase(java.util.Locale.ROOT)
               + "|" + peerToxPubHex.toLowerCase(java.util.Locale.ROOT);
    }

    /** hskPubHex:firstSeen:lastSeen — plain and greppable in a support dump. */
    static String encode(final Record r)
    {
        if (r == null || r.hskPub == null || r.hskPub.length != NgcHskStore.PUBKEY_SIZE)
        {
            return null;
        }
        return NgcHskStore.toHex(r.hskPub) + ":" + r.firstSeenMs + ":" + r.lastSeenMs;
    }

    /** @return the record, or null if the row is missing or malformed. Never a partial record. */
    static Record decode(final String stored)
    {
        if (stored == null)
        {
            return null;
        }
        final String[] parts = stored.split(":");
        if (parts.length != 3)
        {
            return null;
        }
        final byte[] pub = NgcHskStore.fromHex(parts[0]);
        if (pub == null || pub.length != NgcHskStore.PUBKEY_SIZE)
        {
            return null;
        }
        try
        {
            return new Record(pub, Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}

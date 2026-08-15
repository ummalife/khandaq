package com.zoffcc.applications.trifa;

import java.util.Arrays;

/**
 * KHANDAQ (external audit #2, finding 1, step 2) — the history-signing key (HSK).
 *
 * A history-sync packet names its original author in bytes nobody signed, so the fix needs a key
 * this profile signs with. That key is Ed25519 and cannot be the Tox identity key, which is
 * Curve25519 — see DESIGN-ngc-signed-history-sync.md §2 for why deriving one from the other is not
 * the hard part; publishing it credibly is, and that is what the announcement packet does.
 *
 * WHERE IT IS STORED, and why not in a table of its own: the profile database is orma/SQLCipher and
 * adding a table means a schema migration. Migrations in this project have destroyed user profiles
 * (#244 re-minted the Tox identity and every friend had to be re-added), so the key goes into the
 * existing g_opts key/value rows instead. Same encrypted database, no migration, nothing to get
 * wrong on upgrade.
 *
 * WHAT IT IS BOUND TO: the Tox public key of the profile that generated it. §4.1 requires the HSK to
 * be regenerated when the Tox identity changes, and after #244 that is not a theoretical event. The
 * owner row doubles as the commit marker — it is written LAST, so a process death midway through
 * leaves the key looking absent and it is simply regenerated, rather than leaving half a key that
 * something later treats as an identity.
 *
 * The secret key is never logged, never returned by any toString, and never written anywhere but the
 * encrypted profile row.
 *
 * Nothing calls this yet: it produces and stores a key, and emitting anything signed with it is a
 * later step that changes outgoing traffic.
 */
final class NgcHskStore
{
    /** g_opts row keys. Prefixed like the other KHANDAQ rows so they are recognisable in a dump. */
    static final String G_OPTS_KEY_SEC = "kqhsksec";
    static final String G_OPTS_KEY_PUB = "kqhskpub";
    /** Written last; its presence means the other two are complete. */
    static final String G_OPTS_KEY_OWNER = "kqhskowner";

    static final int PUBKEY_SIZE = 32;
    static final int SECRETKEY_SIZE = 64;
    /** A Tox public key as hex — the first 64 chars of a Tox ID. */
    static final int TOX_PUBKEY_HEX_LEN = 64;

    private NgcHskStore()
    {
    }

    /** A loaded key. The secret is deliberately not exposed through toString or equals. */
    static final class Hsk
    {
        final byte[] pub;
        final byte[] sec;

        Hsk(final byte[] pub, final byte[] sec)
        {
            this.pub = pub;
            this.sec = sec;
        }

        @Override
        public String toString()
        {
            // Never let a stray log line print key material.
            return "Hsk{pub=" + (pub == null ? "null" : toHex(pub)) + ", sec=<redacted>}";
        }
    }

    // ---------------------------------------------------------------- pure decisions (unit-tested)

    /**
     * Should a fresh keypair be generated rather than the stored one used?
     *
     * True when anything is missing, malformed, mutually inconsistent, or belongs to a different Tox
     * identity. Deliberately fails towards regenerating: a key we cannot fully trust is worthless —
     * signatures made with it would be rejected by everyone anyway — whereas regenerating costs one
     * announcement.
     *
     * @param storedOwnerHex the Tox public key that owned the stored key, or null
     * @param currentToxPubHex the Tox public key of the profile right now
     */
    static boolean needsFreshKey(final String storedOwnerHex, final String storedPubHex,
                                 final String storedSecHex, final String currentToxPubHex)
    {
        if (!isToxPubHex(currentToxPubHex))
        {
            // Without knowing who we are, a key cannot be bound to anything. The caller must not
            // generate one either — see ensureKeypair, which refuses rather than minting an orphan.
            return false;
        }
        if (storedOwnerHex == null || storedPubHex == null || storedSecHex == null)
        {
            return true;
        }
        if (!storedOwnerHex.equalsIgnoreCase(currentToxPubHex))
        {
            return true;
        }

        final byte[] pub = fromHex(storedPubHex);
        final byte[] sec = fromHex(storedSecHex);
        if (pub == null || sec == null || pub.length != PUBKEY_SIZE || sec.length != SECRETKEY_SIZE)
        {
            return true;
        }
        // libsodium keeps the public key in the tail of the secret key. If those disagree the rows
        // came from different generations — a torn write, or a half-restored backup.
        return !Arrays.equals(pub, Arrays.copyOfRange(sec, SECRETKEY_SIZE - PUBKEY_SIZE, SECRETKEY_SIZE));
    }

    /** True for exactly 64 hex characters. */
    static boolean isToxPubHex(final String s)
    {
        return s != null && s.length() == TOX_PUBKEY_HEX_LEN && fromHex(s) != null;
    }

    /** The public-key half of a Tox ID (which also carries nospam and a checksum). */
    static String toxPubFromToxId(final String toxId)
    {
        if (toxId == null || toxId.length() < TOX_PUBKEY_HEX_LEN)
        {
            return null;
        }
        final String head = toxId.substring(0, TOX_PUBKEY_HEX_LEN);
        return isToxPubHex(head) ? head.toLowerCase(java.util.Locale.ROOT) : null;
    }

    static String toHex(final byte[] data)
    {
        if (data == null)
        {
            return null;
        }
        final char[] digits = "0123456789abcdef".toCharArray();
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (final byte b : data)
        {
            sb.append(digits[(b >> 4) & 0x0f]).append(digits[b & 0x0f]);
        }
        return sb.toString();
    }

    /** @return the bytes, or null if the string is not clean, even-length hex. Never a partial array. */
    static byte[] fromHex(final String s)
    {
        if (s == null || s.length() == 0 || (s.length() % 2) != 0)
        {
            return null;
        }
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++)
        {
            final int hi = Character.digit(s.charAt(i * 2), 16);
            final int lo = Character.digit(s.charAt((i * 2) + 1), 16);
            if (hi < 0 || lo < 0)
            {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    // ---------------------------------------------------------------- storage (device-tested)

    /**
     * Returns this profile's HSK, generating and persisting one on first use or after the Tox
     * identity changed.
     *
     * @return the key, or null when there is no usable Tox identity yet (the caller must not sign
     *         anything in that case) or when generation or persistence failed. Never a partial key.
     */
    static synchronized Hsk ensureKeypair(final String currentToxPubHex)
    {
        if (!isToxPubHex(currentToxPubHex))
        {
            return null;
        }

        final String owner = HelperGeneric.get_g_opts(G_OPTS_KEY_OWNER);
        final String pubHex = HelperGeneric.get_g_opts(G_OPTS_KEY_PUB);
        final String secHex = HelperGeneric.get_g_opts(G_OPTS_KEY_SEC);

        if (!needsFreshKey(owner, pubHex, secHex, currentToxPubHex))
        {
            return new Hsk(fromHex(pubHex), fromHex(secHex));
        }

        final byte[] pub = new byte[PUBKEY_SIZE];
        final byte[] sec = new byte[SECRETKEY_SIZE];
        if (MainActivity.khandaq_ed25519_keypair(pub, sec) != 0)
        {
            return null;
        }

        // Owner last: it is the commit marker, so a crash between these writes leaves the key
        // looking absent and the next call regenerates instead of loading half of one.
        HelperGeneric.set_g_opts(G_OPTS_KEY_SEC, toHex(sec));
        HelperGeneric.set_g_opts(G_OPTS_KEY_PUB, toHex(pub));
        HelperGeneric.set_g_opts(G_OPTS_KEY_OWNER, currentToxPubHex.toLowerCase(java.util.Locale.ROOT));

        // set_g_opts swallows its own failures (see the audit3 #1 note on the cancel tombstone), so
        // confirm by reading back rather than trusting the write.
        if (needsFreshKey(HelperGeneric.get_g_opts(G_OPTS_KEY_OWNER),
                          HelperGeneric.get_g_opts(G_OPTS_KEY_PUB),
                          HelperGeneric.get_g_opts(G_OPTS_KEY_SEC), currentToxPubHex))
        {
            return null;
        }
        return new Hsk(pub, sec);
    }
}

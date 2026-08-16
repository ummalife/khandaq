package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * KHANDAQ (external audit #2, finding 1, step 3) — history-sync text, signed by its author.
 *
 * WHO CAN SIGN, and why that shapes everything here: the signature covers
 * `"KQ-HISTSYNC-1" || group_id || author_pub || msg_id || ts || sha256(text)` and must be made with
 * the AUTHOR's history-signing key. A peer relaying someone else's message cannot produce it — that
 * is precisely the property being bought, since today's relayed history carries an author field
 * nobody signed. So a client can only sign the rows it wrote itself, and only those become
 * verifiable. Rows written by others keep travelling exactly as they do today until their authors
 * are also running a signing build.
 *
 * WHY BOTH PACKETS GO OUT during the transition: version 0x02 is dropped by every shipped parser.
 * Sending only the signed form would make our history vanish for every client in the field, which
 * is a far worse failure than the one being fixed. So the unsigned 0x01 packet is sent exactly as
 * before and the signed one is sent in addition; upgraded receivers recognise both and deduplicate
 * on message id, everyone else never sees the second packet. The extra packet is the cost of not
 * breaking history for old clients, and it can be dropped once the transition ends (§8).
 *
 * The timestamp is written FULL WIDTH here. The unsigned format transmits only the low four bytes
 * of it — a latent 2038-class truncation — and the signature covers all eight, so a client that
 * signed the truncated value would produce signatures nobody could reproduce.
 */
final class NgcSignedHistory
{
    private static final String TAG = "trifa.SignedHist";

    private NgcSignedHistory()
    {
    }

    /**
     * Builds the signed history packet for one of OUR OWN messages.
     *
     * @return the packet, or null when the row is not ours to sign, when any field is the wrong
     *         size, or when signing fails. Never a partial packet — a malformed signed packet is
     *         worse than none, because a receiver would count it as a failed verification rather
     *         than as an old client.
     */
    static byte[] buildSignedText(final String groupIdentifier, final GroupMessage m,
                                  final String selfToxPubHex)
    {
        try
        {
            if (groupIdentifier == null || m == null || m.text == null)
            {
                return null;
            }
            // Only our own rows. direction==1 is what the rest of the file uses for "we wrote it",
            // and it is the only claim we can actually back with a signature.
            if (m.direction != 1)
            {
                return null;
            }

            final byte[] groupId = NgcHskStore.fromHex(groupIdentifier);
            final byte[] authorPub = NgcHskStore.fromHex(m.tox_group_peer_pubkey);
            if (groupId == null || groupId.length != NgcHistSig.GROUP_ID_SIZE
                || authorPub == null || authorPub.length != NgcHistSig.PUBKEY_SIZE)
            {
                return null;
            }

            final byte[] msgId = msgIdBytes(m.message_id_tox);
            final byte[] textUtf8 = m.text.getBytes(StandardCharsets.UTF_8);
            if (textUtf8.length > NgcHistSigParser.MAX_TEXT_BYTES)
            {
                return null;
            }

            // Seconds, matching what the unsigned packet carries, but all eight bytes of it.
            final long ts = m.sent_timestamp / 1000L;

            final byte[] preimage = NgcHistSig.histSyncPreimage(groupId, authorPub, msgId, ts, textUtf8);
            if (preimage == null)
            {
                return null;
            }

            final NgcHskStore.Hsk hsk = NgcHskStore.ensureKeypair(selfToxPubHex);
            if (hsk == null)
            {
                return null;
            }
            final byte[] sig = new byte[NgcHistSigParser.SIGNATURE_SIZE];
            if (MainActivity.khandaq_ed25519_sign(preimage, preimage.length, hsk.sec, sig) != 0)
            {
                return null;
            }

            return NgcHistSigParser.buildSignedText(msgId, authorPub, ts,
                                                    peerNameField(groupIdentifier, m.tox_group_peer_pubkey),
                                                    textUtf8, sig);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "buildSignedText:EE:" + e.getMessage());
            return null;
        }
    }

    /** Four bytes, zero-filled when the id is missing or malformed — the same fallback 0x01 uses. */
    private static byte[] msgIdBytes(final String messageIdToxHex)
    {
        final byte[] out = new byte[NgcHistSig.MSG_ID_SIZE];
        try
        {
            final byte[] raw = HelperGeneric.hex_to_bytes(messageIdToxHex);
            if (raw != null)
            {
                System.arraycopy(raw, 0, out, 0, Math.min(raw.length, out.length));
            }
        }
        catch (Exception ignored)
        {
        }
        return out;
    }

    /** Exactly 25 bytes: the display name truncated or null-padded, as the wire format fixes it. */
    private static byte[] peerNameField(final String groupIdentifier, final String authorPubHex)
    {
        final byte[] out = new byte[NgcHistSigParser.PEERNAME_SIZE];
        try
        {
            final String name = HelperGroup.tox_group_peer_get_name__wrapper(groupIdentifier, authorPubHex);
            if (name != null)
            {
                final byte[] raw = name.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(raw, 0, out, 0, Math.min(raw.length, out.length));
            }
        }
        catch (Exception ignored)
        {
        }
        return out;
    }

    /**
     * Sends the signed copy of one of our messages to one peer, in addition to the unsigned packet
     * the caller already sent.
     *
     * @return true when a packet was handed to toxcore.
     */
    static boolean sendSignedTextTo(final String groupIdentifier, final String peerPubkey,
                                    final GroupMessage m)
    {
        try
        {
            final String selfToxPub = NgcHskStore.toxPubFromToxId(MainActivity.get_my_toxid());
            if (selfToxPub == null)
            {
                return false;
            }
            final byte[] packet = buildSignedText(groupIdentifier, m, selfToxPub);
            if (packet == null)
            {
                return false;
            }

            final long groupNum = HelperGroup.tox_group_by_groupid__wrapper(groupIdentifier);
            final long peerNum = HelperGroup.get_group_peernum_from_peer_pubkey(groupIdentifier, peerPubkey);
            if (groupNum < 0 || peerNum < 0)
            {
                return false;
            }

            final int res = MainActivity.tox_group_send_custom_private_packet(
                    groupNum, peerNum, 1, packet, packet.length);
            HelperGeneric.logI(TAG, "sendSignedTextTo:res=" + res + " bytes=" + packet.length);
            return HelperGroup.is_ngc_custom_packet_send_ok(res);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "sendSignedTextTo:EE:" + e.getMessage());
            return false;
        }
    }

    // ---------------------------------------------------------------- receiving

    /** g_opts row: kqhistver_<group>|<msgid>|<author> -> "1" once the signature has checked out. */
    static final String G_OPTS_VERIFIED_PREFIX = "kqhistver_";

    static String verifiedRowKey(final String groupIdentifier, final byte[] msgId, final byte[] authorPub)
    {
        if (groupIdentifier == null || msgId == null || authorPub == null)
        {
            return null;
        }
        return G_OPTS_VERIFIED_PREFIX + groupIdentifier.toLowerCase(java.util.Locale.ROOT)
               + "|" + NgcHskStore.toHex(msgId) + "|" + NgcHskStore.toHex(authorPub);
    }

    /**
     * Handles an incoming signed history record.
     *
     * It deliberately does NOT insert anything. During the transition the unsigned packet travels
     * beside this one and does the inserting exactly as before, so this path has one job: decide
     * whether the authorship claim on that row is backed by a signature, and record the verdict.
     * Keeping it out of the insert path means the change cannot alter which messages appear, only
     * how confidently they are attributed.
     *
     * A bad signature is dropped without a verdict. It cannot be an old client — old clients never
     * send version 0x02 — so it is either corruption or an attempt, and in both cases the honest
     * outcome is the row staying marked unverified rather than being marked bad.
     */
    static void handleIncomingSignedText(final long groupNum, final long peerId,
                                         final byte[] data, final int length)
    {
        try
        {
            final NgcHistSigParser.SignedText st = NgcHistSigParser.parseSignedText(data, length);
            if (st == null)
            {
                return;
            }

            final String groupIdentifier = HelperGroup.tox_group_by_groupnum__wrapper(groupNum);
            if (groupIdentifier == null)
            {
                return;
            }
            final byte[] groupId = NgcHskStore.fromHex(groupIdentifier);
            if (groupId == null || groupId.length != NgcHistSig.GROUP_ID_SIZE)
            {
                return;
            }

            // The author is a claim in the packet — that is the defect being fixed — so it is only
            // worth anything once the signature over it checks out against the key we learned for
            // that author from a LIVE announcement.
            final String authorHex = NgcHskStore.toHex(st.authorPub);
            final String dirRow = NgcHskDirectory.rowKey(groupIdentifier, authorHex);
            if (dirRow == null)
            {
                return;
            }
            final NgcHskDirectory.Record known = NgcHskDirectory.decode(HelperGeneric.get_g_opts(dirRow));
            if (known == null || known.hskPub == null)
            {
                // We have never heard this author announce a key, so there is nothing to check the
                // signature against. Not a failure — just an author we cannot vouch for yet.
                return;
            }

            final byte[] preimage = NgcHistSig.histSyncPreimage(groupId, st.authorPub, st.msgId,
                                                                st.timestamp, st.textUtf8);
            if (preimage == null)
            {
                return;
            }
            if (MainActivity.khandaq_ed25519_verify(preimage, preimage.length,
                                                    st.signature, known.hskPub) != 0)
            {
                HelperGeneric.logI(TAG, "handleIncomingSignedText:BAD SIGNATURE gn=" + groupNum
                        + " pid=" + peerId);
                return;
            }

            final String row = verifiedRowKey(groupIdentifier, st.msgId, st.authorPub);
            if (row != null)
            {
                HelperGeneric.set_g_opts(row, "1");
                HelperGeneric.logI(TAG, "handleIncomingSignedText:verified gn=" + groupNum);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "handleIncomingSignedText:EE:" + e.getMessage());
        }
    }

    /**
     * @return true when a signature has proved this row's author. Reads the memo written by
     *         {@link #handleIncomingSignedText}; a missing row simply means "not proved", which is
     *         every message today.
     */
    static boolean isAuthorVerified(final GroupMessage m)
    {
        try
        {
            if (m == null || m.group_identifier == null || m.tox_group_peer_pubkey == null)
            {
                return false;
            }
            final byte[] authorPub = NgcHskStore.fromHex(m.tox_group_peer_pubkey);
            if (authorPub == null || authorPub.length != NgcHistSig.PUBKEY_SIZE)
            {
                return false;
            }
            final String row = verifiedRowKey(m.group_identifier, msgIdBytes(m.message_id_tox), authorPub);
            return row != null && "1".equals(HelperGeneric.get_g_opts(row));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** True when two byte arrays are equal — kept local so callers need no extra import. */
    static boolean sameBytes(final byte[] a, final byte[] b)
    {
        return Arrays.equals(a, b);
    }
}

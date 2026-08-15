package com.zoffcc.applications.trifa;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_packet;

/**
 * KHANDAQ (external audit #2, finding 1, step 3) — announcing our history-signing key to a group.
 *
 * The packet is `magic(6) | 0x02 | 0x50 | hsk_pub(32) | valid_from_ts(8 BE) | sig(64)`, and the
 * signature is made with the announced key itself over
 * `"KQ-HSK-ANNOUNCE-1" || tox_pub(32) || hsk_pub(32) || valid_from_ts(8)`.
 *
 * The self-signature proves possession and nothing else — anyone can mint a keypair and announce it
 * under any name. What makes an announcement attributable is that toxcore authenticates the sender
 * of a live group packet, which is why this is sent on the LOSSLESS group channel and why the
 * receiving side (NgcHskDirectory) refuses to swap a known key for one that did not arrive from a
 * live peer.
 *
 * This is the first step of the design that changes outgoing traffic. It is deliberately inert for
 * every shipped client: version 0x02 is dropped by every parser in the field (verified on all three
 * platforms), so peers that do not understand it ignore it rather than misread it.
 */
final class NgcHskAnnounce
{
    private static final String TAG = "trifa.HskAnnounce";

    /**
     * Not more often than this per group. The announcement only has to reach peers that joined
     * after us, so it is a slow background fact, not a heartbeat.
     */
    static final long MIN_INTERVAL_MS = 10L * 60L * 1000L;

    private static final ConcurrentHashMap<String, Long> last_sent_ms = new ConcurrentHashMap<>();

    private NgcHskAnnounce()
    {
    }

    /**
     * Builds the signed announcement for this profile.
     *
     * @param validFromTs the timestamp the signature covers. The receiving side records its own
     *                    first_seen/last_seen and does not currently rely on this value; if it ever
     *                    needs to be stable across announcements, the key's creation time has to be
     *                    persisted alongside the key, which it is not today.
     * @return the packet bytes, or null when there is no usable identity or key, when signing
     *         fails, or when any field is the wrong size. Never a partial packet.
     */
    static byte[] buildSelfAnnouncement(final String toxPubHex, final long validFromTs)
    {
        final NgcHskStore.Hsk hsk = NgcHskStore.ensureKeypair(toxPubHex);
        if (hsk == null)
        {
            return null;
        }
        final byte[] toxPub = NgcHskStore.fromHex(toxPubHex);
        if (toxPub == null || toxPub.length != NgcHistSig.PUBKEY_SIZE)
        {
            return null;
        }

        final byte[] preimage = NgcHistSig.announcePreimage(toxPub, hsk.pub, validFromTs);
        if (preimage == null)
        {
            return null;
        }

        final byte[] sig = new byte[NgcHistSigParser.SIGNATURE_SIZE];
        if (MainActivity.khandaq_ed25519_sign(preimage, preimage.length, hsk.sec, sig) != 0)
        {
            return null;
        }

        return NgcHistSigParser.buildAnnouncement(hsk.pub, validFromTs, sig);
    }

    /**
     * Sends our announcement into one group, at most once per {@link #MIN_INTERVAL_MS}.
     *
     * @return true if a packet was actually handed to toxcore.
     */
    static boolean announceToGroup(final long groupNum, final String groupIdentifier,
                                   final String toxPubHex, final boolean force)
    {
        if (groupNum < 0 || groupIdentifier == null)
        {
            return false;
        }

        final String key = groupIdentifier.toLowerCase(Locale.ROOT);
        final long now = System.currentTimeMillis();
        if (!force)
        {
            final Long last = last_sent_ms.get(key);
            // A clock moved backwards yields a negative interval, which fails this test and simply
            // announces again — harmless, and better than going silent until the clock catches up.
            if (last != null && (now - last) < MIN_INTERVAL_MS && (now - last) >= 0)
            {
                return false;
            }
        }

        final byte[] packet = buildSelfAnnouncement(toxPubHex, now);
        if (packet == null)
        {
            return false;
        }

        // Claim the slot before sending: a failed send should not free the rate limit for an
        // immediate retry storm on a group that is simply not reachable.
        last_sent_ms.put(key, now);

        final int res = tox_group_send_custom_packet(groupNum, 1, packet, packet.length);
        if (!HelperGroup.is_ngc_custom_packet_send_ok(res))
        {
            HelperGeneric.logI(TAG, "announceToGroup:send failed res=" + res
                    + " (" + HelperGroup.describe_ngc_custom_packet_error(res) + ")");
            return false;
        }
        HelperGeneric.logI(TAG, "announceToGroup:sent gn=" + groupNum + " bytes=" + packet.length);
        return true;
    }

    /**
     * Handles an incoming announcement that arrived on the group's live channel.
     *
     * Three things have to hold before we believe anything: the packet parses exactly (the parser
     * rejects any length but 120, so nothing can hide after the signature), the self-signature
     * verifies against the announced key, and — the part no signature can supply — the sender is
     * identified by toxcore rather than by the packet. The Tox public key therefore comes from
     * tox_group_peer_get_public_key, never from the payload.
     *
     * What happens to a key that is new for a peer we already know is NgcHskDirectory's decision,
     * not this method's.
     */
    static void handleIncoming(final long groupNum, final long peerId, final byte[] data, final int length)
    {
        try
        {
            final NgcHistSigParser.Announcement ann = NgcHistSigParser.parseAnnouncement(data, length);
            if (ann == null)
            {
                return;
            }

            final String groupId = HelperGroup.tox_group_by_groupnum__wrapper(groupNum);
            final String senderToxPubHex = HelperGroup.tox_group_peer_get_public_key__wrapper(groupNum, peerId);
            if (groupId == null || senderToxPubHex == null)
            {
                return;
            }
            final byte[] senderToxPub = NgcHskStore.fromHex(senderToxPubHex);
            if (senderToxPub == null || senderToxPub.length != NgcHistSig.PUBKEY_SIZE)
            {
                return;
            }

            // Ignore our own announcement echoed back: adopting it would be harmless but it would
            // also mask a real peer's key behind ours if the pubkeys ever collided in the store.
            final String selfToxPub = NgcHskStore.toxPubFromToxId(MainActivity.get_my_toxid());
            if (selfToxPub != null && selfToxPub.equalsIgnoreCase(senderToxPubHex))
            {
                return;
            }

            final byte[] preimage = NgcHistSig.announcePreimage(senderToxPub, ann.hskPub, ann.validFromTs);
            if (preimage == null)
            {
                return;
            }
            // Self-signed: verified against the key being announced. This proves possession only —
            // the binding to senderToxPubHex comes from the transport above.
            if (MainActivity.khandaq_ed25519_verify(preimage, preimage.length, ann.signature, ann.hskPub) != 0)
            {
                HelperGeneric.logI(TAG, "handleIncoming:bad signature gn=" + groupNum + " pid=" + peerId);
                return;
            }

            final String row = NgcHskDirectory.rowKey(groupId, senderToxPubHex);
            if (row == null)
            {
                return;
            }
            final NgcHskDirectory.Record existing = NgcHskDirectory.decode(HelperGeneric.get_g_opts(row));
            final long now = System.currentTimeMillis();
            // The packet reached us over the live group channel, so the sender is connected by
            // construction; the status check only guards against acting on a peer that dropped
            // between delivery and this point.
            final boolean connected = HelperGroup.is_group_peer_online(
                    MainActivity.tox_group_peer_get_connection_status(groupNum, peerId));

            final NgcHskDirectory.Decision decision =
                    NgcHskDirectory.decide(existing, ann.hskPub, connected, now);

            switch (decision)
            {
                case LEARN:
                case REPLACE:
                    HelperGeneric.set_g_opts(row, NgcHskDirectory.encode(
                            new NgcHskDirectory.Record(ann.hskPub, now, now)));
                    HelperGeneric.logI(TAG, "handleIncoming:" + decision + " gn=" + groupNum);
                    break;
                case REFRESH:
                    HelperGeneric.set_g_opts(row, NgcHskDirectory.encode(
                            new NgcHskDirectory.Record(existing.hskPub, existing.firstSeenMs, now)));
                    break;
                case RECORD_ONLY:
                    // Deliberately not stored anywhere that verification reads. A rejected key is
                    // only interesting as a diagnostic, and writing it beside the trusted one is how
                    // a "recorded" key quietly becomes a usable one later.
                    HelperGeneric.logI(TAG, "handleIncoming:RECORD_ONLY (kept existing key) gn=" + groupNum);
                    break;
                default:
                    break;
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "handleIncoming:EE:" + e.getMessage());
        }
    }

    /** Lets a key change re-announce immediately instead of waiting out the interval. */
    static void forgetRateLimit(final String groupIdentifier)
    {
        if (groupIdentifier != null)
        {
            last_sent_ms.remove(groupIdentifier.toLowerCase(Locale.ROOT));
        }
    }
}

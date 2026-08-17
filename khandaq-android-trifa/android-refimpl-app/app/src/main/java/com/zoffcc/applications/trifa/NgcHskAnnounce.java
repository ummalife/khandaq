package com.zoffcc.applications.trifa;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_packet;

/**
 * KHANDAQ (external audit #2, finding 1, step 3) — announcing our history-signing key to a group.
 *
 * The packet is `magic(6) | 0x02 | 0x50 | hsk_pub(32) | valid_from_ts(8 BE) | sig(64)`, and the
 * signature is made with the announced key itself over
 * `"KQ-HSK-ANNOUNCE-1" || sender_group_pub(32) || hsk_pub(32) || valid_from_ts(8)`.
 *
 * The identity in that pre-image is the sender's public key IN THIS GROUP, because that is the only
 * identity the receiver can independently obtain (tox_group_peer_get_public_key) and the same one a
 * group message records as its author. Binding to the profile's Tox ID key instead makes every
 * signature unverifiable — the receiver has no way to learn a group peer's Tox ID, so it rebuilds a
 * different pre-image and rejects everything.
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
    private static final ConcurrentHashMap<String, Long> last_fail_log_ms = new ConcurrentHashMap<>();

    /**
     * Per group, the peers that were in the group the last time we announced — so they have heard
     * our key. Not a rate limit: a time window is the wrong tool here, because the join we care
     * about lands seconds after our own connect-time announcement and any window wide enough to
     * collapse a burst also swallows exactly that join. (Measured: with a 30-second window, two
     * clients that started together did not learn each other's key until the ten-minute periodic.)
     */
    private static final ConcurrentHashMap<String, java.util.Set<String>> announced_to =
            new ConcurrentHashMap<>();

    /**
     * Logs a failure at most once per group per {@link #MIN_INTERVAL_MS}. The maintenance loop calls
     * the announcer every few seconds, so an unthrottled line would bury the log — but silence is
     * worse: a profile whose key never goes out is indistinguishable from one running an old
     * client, which is exactly how this path stayed broken while looking fine.
     */
    private static void logFailureThrottled(final String key, final String message)
    {
        final long now = System.currentTimeMillis();
        final Long last = last_fail_log_ms.get(key);
        if (last != null && (now - last) < MIN_INTERVAL_MS && (now - last) >= 0)
        {
            return;
        }
        last_fail_log_ms.put(key, now);
        HelperGeneric.logI(TAG, message);
    }

    private NgcHskAnnounce()
    {
    }

    /**
     * Builds the signed announcement for this profile in one group.
     *
     * @param ownerToxPubHex the profile's Tox public key. Used ONLY to decide which stored key is
     *                       ours (§4.1: a changed Tox identity must re-mint), never signed over.
     * @param groupIdentifier the group whose key is announced — keys are per group.
     * @param senderGroupPubHex our public key in that group, which is what the signature binds to.
     * @param validFromTs the timestamp the signature covers. The receiving side records its own
     *                    first_seen/last_seen and does not currently rely on this value; if it ever
     *                    needs to be stable across announcements, the key's creation time has to be
     *                    persisted alongside the key, which it is not today.
     * @return the packet bytes, or null when there is no usable identity or key, when signing
     *         fails, or when any field is the wrong size. Never a partial packet.
     */
    static byte[] buildSelfAnnouncement(final String ownerToxPubHex, final String groupIdentifier,
                                        final String senderGroupPubHex, final long validFromTs)
    {
        final NgcHskStore.Hsk hsk = NgcHskStore.ensureKeypair(ownerToxPubHex, groupIdentifier);
        if (hsk == null)
        {
            return null;
        }
        final byte[] senderGroupPub = NgcHskStore.fromHex(senderGroupPubHex);
        if (senderGroupPub == null || senderGroupPub.length != NgcHistSig.PUBKEY_SIZE)
        {
            return null;
        }

        final byte[] preimage = NgcHistSig.announcePreimage(senderGroupPub, hsk.pub, validFromTs);
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
        return announceToGroup(groupNum, groupIdentifier, toxPubHex, force,
                               MIN_INTERVAL_MS + announceJitterMs(groupIdentifier, toxPubHex));
    }

    /**
     * Per-client spread on top of the interval, 0..119 s.
     *
     * DESIGN-ngc-signed-history-sync.md §7.1 asks for it in as many words: without a spread, every
     * member of a group that came back together after an outage re-announces in the same second —
     * the one moment the network can least afford it. Derived from the group and our own identity
     * rather than drawn at random, so a client does not drift earlier and earlier across launches,
     * and two members of the same group land on different offsets.
     */
    static long announceJitterMs(final String groupIdentifier, final String toxPubHex)
    {
        try
        {
            final String seed = (groupIdentifier == null ? "" : groupIdentifier.toLowerCase(Locale.ROOT))
                                + "|" + (toxPubHex == null ? "" : toxPubHex);
            final byte[] digest = NgcHistSig.sha256Public(seed.getBytes(StandardCharsets.UTF_8));
            if (digest == null || digest.length < 2)
            {
                return 0L;
            }
            final int value = ((digest[0] & 0xff) << 8) | (digest[1] & 0xff);
            return (value % 120) * 1000L;
        }
        catch (Exception e)
        {
            return 0L;
        }
    }

    /**
     * @param minIntervalMs the rate limit to apply. The periodic path uses the full interval; a peer
     *                      joining uses a short one, because a peer that arrives after our last
     *                      announcement can verify nothing we wrote until it hears one, and waiting
     *                      out ten minutes for that is the difference between "verified" and "looks
     *                      like an old client" for everything it receives in between.
     */
    static boolean announceToGroup(final long groupNum, final String groupIdentifier,
                                   final String toxPubHex, final boolean force,
                                   final long minIntervalMs)
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
            if (last != null && (now - last) < minIntervalMs && (now - last) >= 0)
            {
                return false;
            }
        }

        // Our identity in THIS group, which is what the signature binds to and what the receiver
        // will independently read off the transport.
        final String selfGroupPub = HelperGroup.tox_group_self_get_public_key__wrapper(groupNum);
        final byte[] packet = buildSelfAnnouncement(toxPubHex, groupIdentifier, selfGroupPub, now);
        if (packet == null)
        {
            // Silence here means nobody can ever verify our history and nothing says why, so this
            // one failure is worth a (throttled) line even though it is not fatal to anything else.
            logFailureThrottled(key, "announceToGroup:not built gn=" + groupNum
                    + " have_group_pub=" + (selfGroupPub != null));
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
            final String senderGroupPubHex = HelperGroup.tox_group_peer_get_public_key__wrapper(groupNum, peerId);
            if (groupId == null || senderGroupPubHex == null)
            {
                return;
            }
            final byte[] senderGroupPub = NgcHskStore.fromHex(senderGroupPubHex);
            if (senderGroupPub == null || senderGroupPub.length != NgcHistSig.PUBKEY_SIZE)
            {
                return;
            }

            // Ignore our own announcement echoed back: adopting it would be harmless but it would
            // also mask a real peer's key behind ours if the pubkeys ever collided in the store.
            // Compared in group-key space, the only space both sides of this packet speak.
            final String selfGroupPub = HelperGroup.tox_group_self_get_public_key__wrapper(groupNum);
            if (selfGroupPub != null && selfGroupPub.equalsIgnoreCase(senderGroupPubHex))
            {
                return;
            }

            final byte[] preimage = NgcHistSig.announcePreimage(senderGroupPub, ann.hskPub, ann.validFromTs);
            if (preimage == null)
            {
                return;
            }
            // Self-signed: verified against the key being announced. This proves possession only —
            // the binding to senderGroupPubHex comes from the transport above.
            if (MainActivity.khandaq_ed25519_verify(preimage, preimage.length, ann.signature, ann.hskPub) != 0)
            {
                HelperGeneric.logI(TAG, "handleIncoming:bad signature gn=" + groupNum + " pid=" + peerId);
                return;
            }

            final String row = NgcHskDirectory.rowKey(groupId, senderGroupPubHex);
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
            final String key = groupIdentifier.toLowerCase(Locale.ROOT);
            last_sent_ms.remove(key);
            // Everyone has to hear the new key, including peers that already heard the old one.
            announced_to.remove(key);
        }
    }

    /**
     * Announces to a group because a peer arrived, unless that peer has already heard us.
     *
     * The peer that just joined can verify nothing we wrote until it has our key, so this bypasses
     * the periodic interval — but only once per peer. On a successful send every peer currently in
     * the group is marked as having heard it, which is what keeps a group of N waking up at once to
     * a single broadcast rather than N of them.
     *
     * @return true if a packet was sent.
     */
    static boolean announceForNewPeer(final long groupNum, final String groupIdentifier,
                                      final String toxPubHex, final String joiningPeerPubkey)
    {
        if (groupNum < 0 || groupIdentifier == null || joiningPeerPubkey == null)
        {
            return false;
        }

        final String key = groupIdentifier.toLowerCase(Locale.ROOT);
        final String peer = joiningPeerPubkey.toLowerCase(Locale.ROOT);
        java.util.Set<String> heard = announced_to.get(key);
        if (heard != null && heard.contains(peer))
        {
            return false;
        }

        if (!announceToGroup(groupNum, groupIdentifier, toxPubHex, true))
        {
            // Nothing was sent, so nobody may be marked as having heard it — otherwise a failed
            // send would permanently convince us this peer knows a key it never received.
            return false;
        }

        heard = announced_to.get(key);
        if (heard == null)
        {
            heard = java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
            final java.util.Set<String> raced = announced_to.putIfAbsent(key, heard);
            if (raced != null)
            {
                heard = raced;
            }
        }
        heard.add(peer);
        markCurrentPeersAsHeard(groupNum, heard);
        return true;
    }

    /** The broadcast reached everyone who is in the group right now, so record all of them. */
    private static void markCurrentPeersAsHeard(final long groupNum, final java.util.Set<String> heard)
    {
        try
        {
            final long[] peers = MainActivity.tox_group_get_peerlist(groupNum);
            if (peers == null)
            {
                return;
            }
            for (final long peerId : peers)
            {
                if (peerId < 0)
                {
                    continue;
                }
                final String pub = HelperGroup.tox_group_peer_get_public_key__wrapper(groupNum, peerId);
                if (pub != null)
                {
                    heard.add(pub.toLowerCase(Locale.ROOT));
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }
}

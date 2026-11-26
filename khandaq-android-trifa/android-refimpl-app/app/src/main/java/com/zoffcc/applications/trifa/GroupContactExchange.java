package com.zoffcc.applications.trifa;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupPeerDB;

import org.khandaq.messenger.R;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.annotation.Nullable;

import static com.zoffcc.applications.trifa.HelperGeneric.del_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.get_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.set_g_opts;
import static com.zoffcc.applications.trifa.HelperGroup.get_group_peernum_from_peer_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.is_ngc_custom_packet_send_ok;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupnum__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_public_key__wrapper;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UINT32_MAX_JAVA;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (26.11.2026): a member of a group becomes a contact (DESIGN-group-contacts-private-mentions.md).
 * <p>
 * "Add to contacts" sends the member an offer with our Tox ID over the group's private channel and keeps
 * it as a pending offer. The member sees it as a contact request "from the group"; accepting it sends us
 * a Tox contact request marked with the offer's nonce, and an accept packet with their ID over the group.
 * Whichever of the two reaches us first adds them without asking again: we asked first. Declining sends
 * nothing, as with any request.
 * <p>
 * Both sides keep which contact stands behind the member, so the member's sheet offers the conversation
 * and copies their real ID. Everything is kept in the encrypted database (g_opts).
 */
final class GroupContactExchange
{
    private static final String TAG = "trifa.GroupContacts";

    /** An offer nobody answered is forgotten after thirty days. */
    static final long OFFER_LIFETIME_MS = 30L * 24L * 60L * 60L * 1000L;
    /** A second offer from the same member within a minute is dropped. */
    static final long REPEAT_OFFER_MS = 60L * 1000L;

    private static final String OFFER_KEY = "kq_group_contact_offer:";
    private static final String OFFER_TO_MEMBER_KEY = "kq_group_contact_offer_to:";
    private static final String MEMBER_KEY = "kq_group_contact_member:";

    private static final String FIELD_GROUP = "group";
    private static final String FIELD_MEMBER = "member";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_AT = "at";
    private static final String FIELD_ADDRESS = "address";
    private static final String FIELD_KEY = "key";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Long> LAST_OFFER_FROM = new ConcurrentHashMap<>();
    /**
     * toxcore's callbacks hand us the packets and the requests; adding a contact or answering goes on here,
     * after the callback returned, as add_friend_to_system does for the same reason.
     */
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "kq-group-contacts"));

    private GroupContactExchange()
    {
    }

    /** Where the result of "Add to contacts" is said: the sheet's toast, the member screen's note. */
    interface Say
    {
        void say(CharSequence text, boolean failed);
    }

    // ---- the one who asks ----

    /** "Add to contacts" on a member: the offer goes to them now, or the words why it cannot. True when sent. */
    static boolean offer(final Context context, final String groupId, final String memberKey,
                         final String memberName, final Say say)
    {
        final String gid = groupId.toLowerCase(Locale.ROOT);
        final String member = memberKey.toUpperCase(Locale.ROOT);
        final String name = TextUtils.isEmpty(memberName) ? context.getString(R.string.group_peer_unknown) : memberName;
        if (!HelperFriend.is_tox_profile_ready())
        {
            say.say(context.getString(R.string.add_friend_failed_not_ready), true);
            return false;
        }
        final byte[] address = GroupContactPackets.fromHex(ownAddress(), GroupContactPackets.ADDRESS_BYTES);
        final long groupNum = tox_group_by_groupid__wrapper(gid);
        final long peerId = groupNum < 0 ? -1 : get_group_peernum_from_peer_pubkey(gid, member);
        if (address == null || groupNum < 0)
        {
            say.say(context.getString(R.string.air_group_contact_failed), true);
            return false;
        }
        if (peerId < 0 || MainActivity.tox_group_peer_get_connection_status(groupNum, peerId)
                          == ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value)
        {
            say.say(context.getString(R.string.air_group_contact_offline_format, name), false);
            return false;
        }
        final byte[] nonce = new byte[GroupContactPackets.NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        final byte[] packet = GroupContactPackets.build(GroupContactPackets.PKT_OFFER, address, nonce);
        final int result = packet == null ? -1
                           : MainActivity.tox_group_send_custom_private_packet(groupNum, peerId, 1, packet, packet.length);
        if (packet == null || !is_ngc_custom_packet_send_ok(result))
        {
            Log.i(TAG, "offer: not sent, result=" + result);
            say.say(context.getString(R.string.air_group_contact_failed), true);
            return false;
        }
        keepOffer(gid, member, name, GroupContactPackets.toHex(nonce, 0, nonce.length).toLowerCase(Locale.ROOT),
                  System.currentTimeMillis());
        say.say(context.getString(R.string.air_group_contact_sent_format, name), false);
        return true;
    }

    /**
     * A Tox contact request: one that answers an offer of ours is accepted here, without a card, and
     * true comes back. Any other request is the caller's, with the marker of an unknown offer taken off.
     */
    static boolean onFriendRequest(final String publicKey, final String message)
    {
        final String nonce = GroupContactPackets.nonceOfRequest(message);
        if (nonce == null)
        {
            return false;
        }
        final JsonObject offer = takeOffer(nonce);
        if (offer == null)
        {
            return false;
        }
        final String gid = string(offer, FIELD_GROUP);
        final String member = string(offer, FIELD_MEMBER);
        final String name = string(offer, FIELD_NAME);
        final String key = publicKey.toUpperCase(Locale.ROOT);
        Log.i(TAG, "friend request answers our offer: adding");
        WORK.execute(() -> {
            addAsked(key, name);
            if (!gid.isEmpty() && !member.isEmpty())
            {
                rememberMember(gid, member, key, null);
            }
        });
        return true;
    }

    // ---- packets from the group ----

    /** An offer or an accept from a member, from the group's private packet callback. */
    static void onPacket(final long groupNum, final long peerId, final byte[] data, final long length)
    {
        final GroupContactPackets.Packet packet = GroupContactPackets.parse(data, length);
        if (packet == null)
        {
            Log.i(TAG, "packet dropped: length or checksum, len=" + length);
            return;
        }
        final String gid = tox_group_by_groupnum__wrapper(groupNum);
        final String member = tox_group_peer_get_public_key__wrapper(groupNum, peerId);
        if (gid == null || member == null || member.length() != GroupContactPackets.PUBLIC_KEY_BYTES * 2)
        {
            return;
        }
        final String publicKey = packet.publicKeyHex();
        final String own = ownAddress();
        if (own.length() >= publicKey.length() && own.substring(0, publicKey.length()).equalsIgnoreCase(publicKey))
        {
            return;
        }
        final String groupId = gid.toLowerCase(Locale.ROOT);
        final String memberKey = member.toUpperCase(Locale.ROOT);
        WORK.execute(() -> {
            if (packet.isOffer())
            {
                onOffer(groupId, memberKey, packet);
            }
            else
            {
                onAccept(groupId, memberKey, packet);
            }
        });
    }

    private static void onOffer(final String gid, final String member, final GroupContactPackets.Packet packet)
    {
        final long now = System.currentTimeMillis();
        final String from = gid + ":" + member;
        final Long last = LAST_OFFER_FROM.get(from);
        if (last != null && now - last < REPEAT_OFFER_MS)
        {
            Log.i(TAG, "offer: a repeat within a minute, dropped");
            return;
        }
        LAST_OFFER_FROM.put(from, now);

        final String publicKey = packet.publicKeyHex();
        if (AirBlockList.isBlocked(publicKey))
        {
            Log.i(TAG, "offer: dropped, the key is blocked on this device");
            return;
        }
        final String name = memberName(gid, member);
        if (HelperFriend.lookup_friendlist_by_pubkey(publicKey) != null)
        {
            // Already a contact: answered at once, nothing to ask.
            rememberMember(gid, member, publicKey, packet.addressHex());
            sendAccept(gid, member, packet.nonceHex());
            return;
        }
        final String groupName = groupName(gid);
        final AirRequestStore.Pending pending = AirRequestStore.Pending.fromGroup(
                publicKey, new AirRequestStore.GroupOffer(packet.addressHex(), gid, member, packet.nonceHex(), name,
                                                          groupName), now);
        if (pending == null)
        {
            return;
        }
        final boolean toldBefore = HelperMsgNotification.is_request_waiting(publicKey);
        AirRequestStore.add(pending);
        final Context context = MainActivity.context_s;
        if (!toldBefore && context != null)
        {
            HelperMsgNotification.notify_incoming_request(
                    publicKey, groupName.isEmpty()
                               ? context.getString(R.string.air_group_contact_band_unnamed_format, name)
                               : context.getString(R.string.air_group_contact_band_format, name, groupName));
        }
    }

    private static void onAccept(final String gid, final String member, final GroupContactPackets.Packet packet)
    {
        final JsonObject offer = loadOffer(packet.nonceHex());
        if (offer == null || !gid.equalsIgnoreCase(string(offer, FIELD_GROUP))
            || !member.equalsIgnoreCase(string(offer, FIELD_MEMBER)))
        {
            Log.i(TAG, "accept: no offer of ours to this member with that nonce");
            return;
        }
        takeOffer(packet.nonceHex());
        Log.i(TAG, "accept: adding the member as a contact");
        addAsked(packet.publicKeyHex(), string(offer, FIELD_NAME));
        rememberMember(gid, member, packet.publicKeyHex(), packet.addressHex());
    }

    // ---- the one who is asked ----

    /**
     * Accept on the card of an offer: our contact request goes to their ID with the offer's marker, and
     * the accept to them over the group while they are there. True when the request went.
     */
    static boolean accept(final Context context, final AirRequestStore.Pending pending,
                          @Nullable final AirComposerNotice.Field refusals)
    {
        final AirRequestStore.GroupOffer offer = pending == null ? null : pending.group;
        if (offer == null)
        {
            return false;
        }
        final String words = offer.groupName.isEmpty()
                             ? context.getString(R.string.air_group_contact_request_words)
                             : context.getString(R.string.air_group_contact_request_words_format, offer.groupName);
        final boolean asked = HelperFriend.add_friend_real(offer.address,
                                                           GroupContactPackets.requestMessage(offer.nonce, words),
                                                           refusals);
        if (!asked && HelperFriend.lookup_friendlist_by_pubkey(pending.publicKey) == null)
        {
            return false;
        }
        nameContact(pending.publicKey, offer.memberName);
        rememberMember(offer.groupId, offer.memberKey, pending.publicKey, offer.address);
        sendAccept(offer.groupId, offer.memberKey, offer.nonce);
        return true;
    }

    /** The accept, to the member while they are in the group; the marked request answers otherwise. */
    private static void sendAccept(final String gid, final String member, final String nonceHex)
    {
        final long groupNum = tox_group_by_groupid__wrapper(gid);
        final long peerId = groupNum < 0 ? -1 : get_group_peernum_from_peer_pubkey(gid, member);
        if (peerId < 0)
        {
            Log.i(TAG, "accept: the member is not in the group now, the request answers alone");
            return;
        }
        final byte[] address = GroupContactPackets.fromHex(ownAddress(), GroupContactPackets.ADDRESS_BYTES);
        final byte[] nonce = GroupContactPackets.fromHex(nonceHex, GroupContactPackets.NONCE_BYTES);
        final byte[] packet = GroupContactPackets.build(GroupContactPackets.PKT_ACCEPT, address, nonce);
        if (packet == null)
        {
            return;
        }
        final int result = MainActivity.tox_group_send_custom_private_packet(groupNum, peerId, 1, packet, packet.length);
        Log.i(TAG, "accept sent: ok=" + is_ngc_custom_packet_send_ok(result));
    }

    // ---- the member behind a contact ----

    /** The contact's public key behind this member, when the two of them traded IDs; else null. */
    @Nullable
    static String contactOfMember(@Nullable final String groupId, @Nullable final String memberKey)
    {
        final JsonObject known = loadMember(groupId, memberKey);
        final String key = known == null ? "" : string(known, FIELD_KEY);
        return key.length() == GroupContactPackets.PUBLIC_KEY_BYTES * 2
               && HelperFriend.lookup_friendlist_by_pubkey(key) != null ? key : null;
    }

    /** The member's whole Tox ID, when they are a contact we traded IDs with; else null. */
    @Nullable
    static String addressOfMember(@Nullable final String groupId, @Nullable final String memberKey)
    {
        if (contactOfMember(groupId, memberKey) == null)
        {
            return null;
        }
        final JsonObject known = loadMember(groupId, memberKey);
        final String address = known == null ? "" : string(known, FIELD_ADDRESS);
        return address.length() == GroupContactPackets.ADDRESS_BYTES * 2 ? address : null;
    }

    /**
     * The member of one of our groups whose key there this is, ourselves left out; null for any other key.
     * Reads the database: off the main thread where it can be.
     */
    @Nullable
    static AirNewContactModel.GroupMember findMember(@Nullable final String hex)
    {
        if (hex == null || hex.length() != GroupContactPackets.PUBLIC_KEY_BYTES * 2 || orma == null)
        {
            return null;
        }
        final String key = hex.toUpperCase(Locale.ROOT);
        try
        {
            for (final String variant : new String[]{key, key.toLowerCase(Locale.ROOT)})
            {
                final List<GroupPeerDB> peers = orma.selectFromGroupPeerDB().tox_group_peer_pubkeyEq(variant).toList();
                if (peers == null)
                {
                    continue;
                }
                for (final GroupPeerDB peer : peers)
                {
                    final String gid = peer.group_identifier == null ? "" : peer.group_identifier.toLowerCase(Locale.ROOT);
                    final long groupNum = gid.isEmpty() ? -1 : tox_group_by_groupid__wrapper(gid);
                    if (groupNum < 0 || key.equalsIgnoreCase(MainActivity.tox_group_self_get_public_key(groupNum)))
                    {
                        continue;
                    }
                    return new AirNewContactModel.GroupMember(gid, key, memberName(gid, key), groupName(gid));
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "findMember:EE:" + e.getMessage());
        }
        return null;
    }

    // ---- storage ----

    private static void keepOffer(final String gid, final String member, final String name, final String nonce,
                                  final long at)
    {
        final String toMember = OFFER_TO_MEMBER_KEY + gid + ":" + member;
        final String previous = get_g_opts(toMember);
        if (previous != null && !previous.equals(nonce))
        {
            del_g_opts(OFFER_KEY + previous);
        }
        final JsonObject offer = new JsonObject();
        offer.addProperty(FIELD_GROUP, gid);
        offer.addProperty(FIELD_MEMBER, member);
        offer.addProperty(FIELD_NAME, name);
        offer.addProperty(FIELD_AT, at);
        set_g_opts(OFFER_KEY + nonce, offer.toString());
        set_g_opts(toMember, nonce);
    }

    /** The pending offer with this nonce, still in its thirty days; an older one is forgotten. */
    @Nullable
    private static JsonObject loadOffer(final String nonce)
    {
        final JsonObject offer = parse(get_g_opts(OFFER_KEY + nonce));
        if (offer == null)
        {
            return null;
        }
        final JsonElement at = offer.get(FIELD_AT);
        final long sent = at != null && at.isJsonPrimitive() && at.getAsJsonPrimitive().isNumber() ? at.getAsLong() : 0L;
        if (System.currentTimeMillis() - sent > OFFER_LIFETIME_MS)
        {
            takeOffer(nonce);
            return null;
        }
        return offer;
    }

    /** The offer, answered or expired: it no longer waits. */
    @Nullable
    private static JsonObject takeOffer(final String nonce)
    {
        final JsonObject offer = parse(get_g_opts(OFFER_KEY + nonce));
        del_g_opts(OFFER_KEY + nonce);
        if (offer != null)
        {
            final String toMember = OFFER_TO_MEMBER_KEY + string(offer, FIELD_GROUP) + ":" + string(offer, FIELD_MEMBER);
            if (nonce.equals(get_g_opts(toMember)))
            {
                del_g_opts(toMember);
            }
        }
        return offer;
    }

    private static void rememberMember(final String gid, final String member, final String publicKey,
                                       @Nullable final String address)
    {
        final String slot = MEMBER_KEY + gid.toLowerCase(Locale.ROOT) + ":" + member.toUpperCase(Locale.ROOT);
        final JsonObject known = parse(get_g_opts(slot));
        final JsonObject next = new JsonObject();
        next.addProperty(FIELD_KEY, publicKey.toUpperCase(Locale.ROOT));
        String keep = address;
        if (keep == null && known != null && publicKey.equalsIgnoreCase(string(known, FIELD_KEY)))
        {
            keep = string(known, FIELD_ADDRESS);
        }
        next.addProperty(FIELD_ADDRESS, keep == null ? "" : keep.toUpperCase(Locale.ROOT));
        set_g_opts(slot, next.toString());
    }

    @Nullable
    private static JsonObject loadMember(@Nullable final String gid, @Nullable final String member)
    {
        if (gid == null || member == null)
        {
            return null;
        }
        return parse(get_g_opts(MEMBER_KEY + gid.toLowerCase(Locale.ROOT) + ":" + member.toUpperCase(Locale.ROOT)));
    }

    // ---- contacts ----

    /** The member who accepted our offer becomes a contact without a request back: we asked first. */
    private static void addAsked(final String publicKey, final String name)
    {
        if (HelperFriend.lookup_friendlist_by_pubkey(publicKey) != null)
        {
            AirRequestStore.remove(publicKey);
            return;
        }
        // Marked first, as Accept on a card does, so the new contact never reads as our own pending request.
        AirRequestStore.markAccepted(publicKey);
        final long friendnum = MainActivity.tox_friend_add_norequest(publicKey);
        if (friendnum < 0 || friendnum == UINT32_MAX_JAVA)
        {
            Log.i(TAG, "addAsked: toxcore refused, " + friendnum);
            if (!HelperFriend.sync_tox_friend_to_contacts(publicKey))
            {
                return;
            }
        }
        else
        {
            HelperGeneric.update_savedata_file_wrapper_async();
            AirBlockList.unblock(publicKey);
            if (!HelperFriend.persist_new_friend_contact(publicKey, friendnum))
            {
                return;
            }
        }
        AirRequestStore.remove(publicKey);
        nameContact(publicKey, name);
        AirRepository.requestRefresh();
        if (MainActivity.context_s != null && !TextUtils.isEmpty(name))
        {
            HelperGeneric.display_toast(MainActivity.context_s.getString(R.string.air_group_contact_added_format, name),
                                        false, 300);
        }
    }

    /** A new contact is called by their name in the group until their own name arrives. */
    private static void nameContact(final String publicKey, @Nullable final String name)
    {
        if (TextUtils.isEmpty(name))
        {
            return;
        }
        try
        {
            final FriendList friend = HelperFriend.lookup_friendlist_by_pubkey(publicKey);
            if (friend == null || !(TextUtils.isEmpty(friend.name)
                                    || HelperFriend.is_placeholder_friend_name(friend.name, publicKey)))
            {
                return;
            }
            friend.name = name;
            HelperFriend.update_friend_in_db_name(friend);
            HelperFriend.update_single_friend_in_friendlist_view(friend);
        }
        catch (Exception e)
        {
            Log.i(TAG, "nameContact:EE:" + e.getMessage());
        }
    }

    // ---- names ----

    /** The name a member has in the group, else the short id of their key. */
    static String memberName(final String gid, final String member)
    {
        String name = null;
        try
        {
            name = HelperGroup.tox_group_peer_get_name__wrapper(gid, member);
        }
        catch (Exception ignored)
        {
        }
        if (TextUtils.isEmpty(name) || "-1".equals(name))
        {
            final GroupPeerDB peer = HelperGroup.lookup_group_peer_by_pubkey(gid, member);
            name = peer == null ? null : peer.peer_name;
        }
        return TextUtils.isEmpty(name) || "-1".equals(name) ? HelperFriend.peer_pubkey_short_id(member) : name;
    }

    static String groupName(final String gid)
    {
        String raw = "";
        try
        {
            if (orma != null)
            {
                final List<GroupDB> rows = orma.selectFromGroupDB().group_identifierEq(gid.toLowerCase(Locale.ROOT))
                                               .toList();
                if (rows != null && !rows.isEmpty() && rows.get(0).name != null)
                {
                    raw = rows.get(0).name;
                }
            }
        }
        catch (Exception ignored)
        {
        }
        final String shown = HelperGroup.get_group_display_name(gid, raw);
        return shown == null ? "" : shown.trim();
    }

    private static String ownAddress()
    {
        final String own = TRIFAGlobals.global_my_toxid;
        return own == null ? "" : own.trim().toUpperCase(Locale.ROOT);
    }

    @Nullable
    private static JsonObject parse(@Nullable final String json)
    {
        if (json == null || json.isEmpty())
        {
            return null;
        }
        try
        {
            final JsonElement root = JsonParser.parseString(json);
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static String string(final JsonObject object, final String name)
    {
        final JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
               ? value.getAsString() : "";
    }
}

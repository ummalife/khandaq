package com.zoffcc.applications.trifa;

import android.util.Log;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (#179): delete-for-both on top of Tox (which has no native delete). Rides the same
 * KQ custom-packet family as message edits (HelperMessageEdit); OLD clients silently drop
 * unknown packet ids, so this degrades to local-only deletion against them.
 *
 *   1:1 friend lossless packet (pkt id 187, "KQ" magic):
 *     [0]=0xBB  [1..2]='K','Q'  [3]=version(1)  [4..35]=msgv3 hash of the message (32 bytes)
 *     [36..39]=delete unix-seconds u32 BE          — fixed 40 bytes, no payload
 *
 *   NGC custom broadcast packet (standard NGC header, pkt id 0x42):
 *     [0..5]=0x667788113435  [6]=version(1)  [7]=0x42
 *     [8..11]=message_id of the message (as in message_id_tox hex)  [12..15]=delete ts u32 BE
 *
 * Rules mirror edits: only OWN TEXT messages propagate (files carry no shared msgv3 anchor);
 * the receiver authenticates by packet source and deletes only a message RECEIVED from that
 * sender (direction==0) — you can only retract what you sent me. A delete that arrives before
 * its original is stashed as a tombstone and applied on insert. Offline friends get the delete
 * on reconnect via a per-friend pending list in g_opts.
 */
public class HelperMessageDelete
{
    private static final String TAG = "trifa.HelperMsgDel";

    // 1:1 friend lossless packet id (160..254; 186 = edits, 187 was free)
    static final int PKT_MSG_DELETE = 187;
    private static final byte MAGIC_1 = 'K';
    private static final byte MAGIC_2 = 'Q';
    private static final byte VERSION = 1;
    private static final int FRIEND_PKT_LEN = 1 + 2 + 1 + 32 + 4; // 40

    // NGC custom packet id inside the standard NGC header (0x41 = edits)
    static final byte NGC_PKT_MSG_DELETE = 0x42;
    private static final int NGC_PKT_LEN = 6 + 1 + 1 + 4 + 4; // 16

    /** g_opts key prefix: pending delete hashes for an offline friend (CSV of msgv3 hashes). */
    private static final String PENDING_KEY_PREFIX = "kqdel_f_";

    // tombstones: deletes that arrived before their original (sender_pubkey + ":" + hash)
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> pending_tombstones =
            new java.util.concurrent.ConcurrentHashMap<>();

    // =============================================================================================
    // 1:1
    // =============================================================================================

    static byte[] buildFriendDeletePacket(final String msgV3HashHex, final long delTsSec)
    {
        try
        {
            final byte[] hash = hexToBytes(msgV3HashHex);
            if (hash == null || hash.length != 32)
            {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(FRIEND_PKT_LEN);
            out.write(PKT_MSG_DELETE);
            out.write(MAGIC_1);
            out.write(MAGIC_2);
            out.write(VERSION);
            out.write(hash, 0, 32);
            writeU32BE(out, delTsSec);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Propagate deletion of an OWN 1:1 TEXT message to the peer. Call BEFORE the local row is
     * removed (needs msg_idv3_hash). Local deletion itself stays with the existing delete flow.
     */
    static void sendOwnFriendDelete(final Message m)
    {
        try
        {
            if (m == null || m.direction != 1
                || m.TRIFA_MESSAGE_TYPE != TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value
                || m.msg_idv3_hash == null || m.msg_idv3_hash.length() != 64
                || m.tox_friendpubkey == null
                || FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey))
            {
                return;
            }

            final long friend_number = HelperFriend.tox_friend_by_public_key__wrapper(m.tox_friendpubkey);
            final byte[] pkt = buildFriendDeletePacket(m.msg_idv3_hash, System.currentTimeMillis() / 1000);
            if (pkt == null)
            {
                return;
            }

            int res = -1;
            if (friend_number >= 0)
            {
                res = MainActivity.tox_friend_send_lossless_packet(friend_number, pkt, pkt.length);
            }
            if (res != 0)
            {
                // offline / temporary failure — queue for the reconnect flush
                stashPendingDelete(m.tox_friendpubkey, m.msg_idv3_hash);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "sendOwnFriendDelete:EE:" + e.getMessage());
        }
    }

    /** Incoming 1:1 delete — called from the friend lossless packet dispatcher. */
    static void handleIncomingFriendDelete(final long friend_number, final byte[] data, final int length)
    {
        try
        {
            if (length < FRIEND_PKT_LEN || data[1] != MAGIC_1 || data[2] != MAGIC_2 || data[3] != VERSION)
            {
                return;
            }
            final String hashHex = HelperGeneric.bytesToHex(data, 4, 32).toUpperCase(Locale.ENGLISH);
            final String senderPubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            if (senderPubkey == null)
            {
                return;
            }

            Message m = null;
            try
            {
                // direction==0 = anti-spoofing: only a message this friend SENT me can be retracted
                final List<Message> list = orma.selectFromMessage().
                        msg_idv3_hashEq(hashHex).
                        tox_friendpubkeyEq(senderPubkey).
                        directionEq(0).
                        orderByIdDesc().
                        toList();
                if (list != null && !list.isEmpty())
                {
                    m = list.get(0);
                }
            }
            catch (Exception ignored)
            {
            }

            if (m == null)
            {
                // original not here yet (offline flood race) — tombstone it
                if (pending_tombstones.size() > 200)
                {
                    pending_tombstones.clear();
                }
                pending_tombstones.put(senderPubkey + ":" + hashHex, System.currentTimeMillis());
                return;
            }

            deleteLocalFriendMessage(m);
        }
        catch (Exception e)
        {
            Log.i(TAG, "handleIncomingFriendDelete:EE:" + e.getMessage());
        }
    }

    /** Called from insert_into_message_db for a fresh INCOMING message: drop it if tombstoned. */
    static boolean consume_pending_delete(final Message m)
    {
        if (m == null || m.direction != 0 || m.msg_idv3_hash == null || m.msg_idv3_hash.isEmpty()
            || m.tox_friendpubkey == null)
        {
            return false;
        }
        return pending_tombstones.remove(m.tox_friendpubkey + ":" + m.msg_idv3_hash) != null;
    }

    private static void deleteLocalFriendMessage(final Message m)
    {
        try
        {
            orma.deleteFromMessage().idEq(m.id).execute();
        }
        catch (Exception e)
        {
            Log.i(TAG, "deleteLocalFriendMessage:EE:" + e.getMessage());
            return;
        }
        try
        {
            // tox callback thread — refresh the open chat via the async path only
            if (MainActivity.message_list_activity != null
                && MainActivity.message_list_activity.get_friend_pubkey() != null
                && MainActivity.message_list_activity.get_friend_pubkey().
                    equalsIgnoreCase(m.tox_friendpubkey)
                && MainActivity.message_list_fragment != null)
            {
                MainActivity.message_list_fragment.update_all_messages_async(false);
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            HelperFriend.update_single_friend_in_friendlist_view(
                    HelperFriend.main_get_friend(m.tox_friendpubkey));
        }
        catch (Exception ignored)
        {
        }
    }

    // ---------------------------------- offline pending queue ----------------------------------

    private static void stashPendingDelete(final String friendPubkey, final String hashHex)
    {
        try
        {
            final String key = PENDING_KEY_PREFIX + friendPubkey.toUpperCase(Locale.ENGLISH);
            final String cur = HelperGeneric.get_g_opts(key);
            if (cur == null || cur.isEmpty())
            {
                HelperGeneric.set_g_opts(key, hashHex);
            }
            else if (!cur.contains(hashHex))
            {
                // cap the list — a delete flood should not grow g_opts unbounded
                final String joined = cur + "," + hashHex;
                HelperGeneric.set_g_opts(key, joined.length() > 65 * 100 ? hashHex : joined);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "stashPendingDelete:EE:" + e.getMessage());
        }
    }

    /** Deliver every pending delete for this friend — call when the friend comes online. */
    static void flushPendingDeletesForFriend(final long friend_number)
    {
        try
        {
            final String pubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            if (pubkey == null)
            {
                return;
            }
            final String key = PENDING_KEY_PREFIX + pubkey.toUpperCase(Locale.ENGLISH);
            final String cur = HelperGeneric.get_g_opts(key);
            if (cur == null || cur.isEmpty())
            {
                return;
            }
            final List<String> remaining = new ArrayList<>();
            for (final String hash : cur.split(","))
            {
                if (hash.length() != 64)
                {
                    continue;
                }
                final byte[] pkt = buildFriendDeletePacket(hash, System.currentTimeMillis() / 1000);
                if (pkt == null)
                {
                    continue;
                }
                if (MainActivity.tox_friend_send_lossless_packet(friend_number, pkt, pkt.length) != 0)
                {
                    remaining.add(hash);
                }
            }
            if (remaining.isEmpty())
            {
                HelperGeneric.del_g_opts(key);
            }
            else
            {
                HelperGeneric.set_g_opts(key, String.join(",", remaining));
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "flushPendingDeletesForFriend:EE:" + e.getMessage());
        }
    }

    // =============================================================================================
    // NGC groups
    // =============================================================================================

    static byte[] buildGroupDeletePacket(final String messageIdToxHex, final long delTsSec)
    {
        try
        {
            final byte[] msgId = hexToBytes(messageIdToxHex);
            if (msgId == null || msgId.length != 4)
            {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(NGC_PKT_LEN);
            out.write(0x66);
            out.write(0x77);
            out.write(0x88);
            out.write(0x11);
            out.write(0x34);
            out.write(0x35);
            out.write(VERSION);
            out.write(NGC_PKT_MSG_DELETE);
            out.write(msgId, 0, 4);
            writeU32BE(out, delTsSec);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Broadcast deletion of an OWN group TEXT message (best effort — like group edits). */
    static void sendOwnGroupDelete(final GroupMessage gm)
    {
        try
        {
            if (gm == null || gm.direction != 1
                || gm.TRIFA_MESSAGE_TYPE != TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value
                || gm.message_id_tox == null || gm.message_id_tox.length() != 8
                || HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX.equals(gm.message_id_tox))
            {
                return;
            }
            final long group_number =
                    HelperGroup.tox_group_by_groupid__wrapper(gm.group_identifier);
            if (group_number < 0)
            {
                return;
            }
            final byte[] pkt = buildGroupDeletePacket(gm.message_id_tox, System.currentTimeMillis() / 1000);
            if (pkt == null)
            {
                return;
            }
            MainActivity.tox_group_send_custom_packet(group_number, 1, pkt, pkt.length);
        }
        catch (Exception e)
        {
            Log.i(TAG, "sendOwnGroupDelete:EE:" + e.getMessage());
        }
    }

    /** Incoming NGC delete — called from the group custom packet dispatcher AFTER the magic check. */
    static void handleIncomingGroupDelete(final long group_number, final long peer_id, final byte[] data,
                                          final int length)
    {
        try
        {
            if (length < NGC_PKT_LEN)
            {
                return;
            }
            final String messageIdHex =
                    HelperGeneric.bytesToHex(data, 8, 4).toLowerCase(Locale.ENGLISH);
            final String senderPubkey =
                    HelperGroup.tox_group_peer_get_public_key__wrapper(group_number, peer_id);
            final String groupIdentifier = HelperGroup.tox_group_by_groupnum__wrapper(group_number);
            if (senderPubkey == null || groupIdentifier == null
                || HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX.equals(messageIdHex))
            {
                return;
            }

            GroupMessage gm = null;
            try
            {
                final List<GroupMessage> list = orma.selectFromGroupMessage().
                        group_identifierEq(groupIdentifier).
                        tox_group_peer_pubkeyEq(senderPubkey.toUpperCase(Locale.ENGLISH)).
                        message_id_toxEq(messageIdHex).
                        orderByIdDesc().
                        toList();
                if (list != null && !list.isEmpty())
                {
                    gm = list.get(0);
                }
            }
            catch (Exception ignored)
            {
            }
            if (gm == null || gm.direction != 0)
            {
                // unknown original (or our own echo) — silent drop, deletes never create state
                return;
            }

            try
            {
                orma.deleteFromGroupMessage().idEq(gm.id).execute();
            }
            catch (Exception e)
            {
                return;
            }
            try
            {
                // tox callback thread — refresh the open group chat via the async path only
                if (MainActivity.group_message_list_activity != null
                    && MainActivity.group_message_list_activity.get_current_group_id() != null
                    && MainActivity.group_message_list_activity.get_current_group_id().
                        equalsIgnoreCase(groupIdentifier)
                    && MainActivity.group_message_list_fragment != null)
                {
                    MainActivity.group_message_list_fragment.update_all_messages_async();
                }
            }
            catch (Exception ignored)
            {
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "handleIncomingGroupDelete:EE:" + e.getMessage());
        }
    }

    // ---------------------------------- utils ----------------------------------

    private static byte[] hexToBytes(final String s)
    {
        if (s == null)
        {
            return null;
        }
        final int len = s.length();
        if (len % 2 != 0)
        {
            return null;
        }
        final byte[] out = new byte[len / 2];
        try
        {
            for (int i = 0; i < len; i += 2)
            {
                out[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                     + Character.digit(s.charAt(i + 1), 16));
            }
        }
        catch (Exception e)
        {
            return null;
        }
        return out;
    }

    private static void writeU32BE(final ByteArrayOutputStream out, final long v)
    {
        out.write((int) ((v >> 24) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }
}

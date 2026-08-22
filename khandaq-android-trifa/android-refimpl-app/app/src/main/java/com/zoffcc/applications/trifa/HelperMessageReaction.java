package com.zoffcc.applications.trifa;

import android.util.Log;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.json.JSONArray;
import org.json.JSONObject;
import org.khandaq.messenger.R;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_ADD;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (#192): Telegram-style message reactions on top of Tox. Same KQ custom-packet family as
 * edits (186) and deletes (187) — OLD clients silently drop unknown packet ids, so reactions
 * degrade to local-only against them.
 *
 *   1:1 friend lossless packet (pkt id 188, "KQ" magic):
 *     [0]=0xBC  [1..2]='K','Q'  [3]=version(1)  [4]=anchor_type(1=msgv3 hash)
 *     [5..36]=anchor (32 bytes)  [37..40]=ts u32 BE  [41]=action(1=add,0=remove own)
 *     [42]=emoji byte length (1..16)  [43..]=emoji UTF-8    — 44..59 bytes
 *
 *   NGC custom broadcast packet (standard NGC header, pkt id 0x43):
 *     [0..5]=0x667788113435  [6]=version(1)  [7]=0x43
 *     [8]=anchor_type(1=message_id_tox in first 4 bytes, 2=msg_id_hash)  [9..40]=anchor (32 bytes,
 *     zero-padded for type 1)  [41..72]=target author pubkey (32 bytes)  [73..76]=ts u32 BE
 *     [77]=action  [78]=emoji byte length  [79..]=emoji UTF-8            — 80..95 bytes
 *
 * Rules: ONE reaction per user per message (an add replaces the actor's previous reaction, remove
 * clears it — the emoji on remove is informational only, "*" placeholder). The actor is always the
 * packet source (authenticated by the transport), the payload never carries the actor. Storage is
 * a JSON array [{"e":"<emoji>","p":["-OWN-","<PUBKEY>",...]},...] on the message row; own
 * reactions are stored as the "-OWN-" marker. Offline 1:1 delivery: reactions_pending flag + flush
 * on reconnect rebuilt from the CURRENT own state (toggles collapse, last-write-wins). NGC is
 * best-effort broadcast like edits/deletes — offline group members miss it.
 */
public class HelperMessageReaction
{
    private static final String TAG = "trifa.HelperMsgReact";

    // 1:1 friend lossless packet id (160..191 range; 182-187 taken, 188 was free)
    static final int PKT_MSG_REACTION = 188;
    private static final byte MAGIC_1 = 'K';
    private static final byte MAGIC_2 = 'Q';
    private static final byte VERSION = 1;
    private static final int FRIEND_HEADER_LEN = 1 + 2 + 1 + 1 + 32 + 4 + 1 + 1; // 43

    // NGC custom packet id inside the standard NGC header (0x41 = edits, 0x42 = deletes)
    static final byte NGC_PKT_MSG_REACTION = 0x43;
    private static final int NGC_HEADER_LEN = 6 + 1 + 1 + 1 + 32 + 32 + 4 + 1 + 1; // 79

    private static final byte ANCHOR_MSGV3_HASH = 1;
    private static final byte ANCHOR_MSG_ID_HASH = 2;

    static final int MAX_EMOJI_BYTES = 16;
    private static final String REMOVE_PLACEHOLDER = "*";

    /** Marker for the local user inside the stored JSON (kept DB-stable across identities). */
    static final String OWN_MARKER = "-OWN-";

    // sane caps so a malicious peer cannot balloon the row
    private static final int MAX_DISTINCT_EMOJI = 12;
    private static final int MAX_ACTORS_PER_EMOJI = 128;

    /** Telegram-style quick reaction bar. */
    static final String[] QUICK_REACTIONS = {"❤️", "👍", "👎", "😂", "😮", "😢", "🔥"};

    // toggles come from UI taps — keep DB + wire off the main thread, in tap order
    private static final ExecutorService reaction_executor = Executors.newSingleThreadExecutor();

    // =============================================================================================
    // reactions JSON model
    // =============================================================================================

    /** @return emoji the actor currently has on this message, or null. */
    static String actorEmoji(final String reactionsJson, final String actor)
    {
        try
        {
            if (reactionsJson == null || reactionsJson.isEmpty())
            {
                return null;
            }
            final JSONArray arr = new JSONArray(reactionsJson);
            for (int i = 0; i < arr.length(); i++)
            {
                final JSONObject entry = arr.getJSONObject(i);
                final JSONArray actors = entry.getJSONArray("p");
                for (int j = 0; j < actors.length(); j++)
                {
                    if (actor.equals(actors.getString(j)))
                    {
                        return entry.getString("e");
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return null;
    }

    /**
     * Apply one reaction event: remove the actor everywhere, then (add) append to the emoji entry.
     * Returns the new JSON string, or null when the result has no reactions left.
     */
    static String applyActor(final String reactionsJson, final String actor, final String emoji,
                             final boolean add)
    {
        try
        {
            JSONArray arr;
            try
            {
                arr = (reactionsJson == null || reactionsJson.isEmpty())
                      ? new JSONArray() : new JSONArray(reactionsJson);
            }
            catch (Exception e)
            {
                arr = new JSONArray();
            }
            final JSONArray out = new JSONArray();
            JSONObject target = null;
            for (int i = 0; i < arr.length(); i++)
            {
                final JSONObject entry = arr.getJSONObject(i);
                final JSONArray actors = entry.getJSONArray("p");
                final JSONArray kept = new JSONArray();
                for (int j = 0; j < actors.length(); j++)
                {
                    final String a = actors.getString(j);
                    if (!actor.equals(a))
                    {
                        kept.put(a);
                    }
                }
                if (kept.length() > 0)
                {
                    final JSONObject ne = new JSONObject();
                    ne.put("e", entry.getString("e"));
                    ne.put("p", kept);
                    out.put(ne);
                    if (add && emoji.equals(entry.getString("e")))
                    {
                        target = ne;
                    }
                }
            }
            if (add)
            {
                if (target == null)
                {
                    if (out.length() >= MAX_DISTINCT_EMOJI)
                    {
                        return out.length() == 0 ? null : out.toString();
                    }
                    target = new JSONObject();
                    target.put("e", emoji);
                    target.put("p", new JSONArray());
                    out.put(target);
                }
                final JSONArray actors = target.getJSONArray("p");
                if (actors.length() < MAX_ACTORS_PER_EMOJI)
                {
                    actors.put(actor);
                }
            }
            return out.length() == 0 ? null : out.toString();
        }
        catch (Exception e)
        {
            Log.i(TAG, "applyActor:EE:" + e.getMessage());
            return reactionsJson;
        }
    }

    private static boolean validEmoji(final String emoji)
    {
        if (emoji == null || emoji.isEmpty())
        {
            return false;
        }
        return emoji.getBytes(StandardCharsets.UTF_8).length <= MAX_EMOJI_BYTES;
    }

    // =============================================================================================
    // 1:1
    // =============================================================================================

    static byte[] buildFriendReactionPacket(final String anchorHex, final byte anchorType, final long tsSec,
                                            final String emoji, final boolean add)
    {
        try
        {
            final byte[] hash = hexToBytes(anchorHex);
            final byte[] em = emoji.getBytes(StandardCharsets.UTF_8);
            if (hash == null || hash.length != 32 || em.length < 1 || em.length > MAX_EMOJI_BYTES)
            {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(FRIEND_HEADER_LEN + em.length);
            out.write(PKT_MSG_REACTION);
            out.write(MAGIC_1);
            out.write(MAGIC_2);
            out.write(VERSION);
            out.write(anchorType);
            out.write(hash, 0, 32);
            writeU32BE(out, tsSec);
            out.write(add ? 1 : 0);
            out.write(em.length);
            out.write(em, 0, em.length);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Can this row carry a NETWORK reaction (shared anchor exists)? Favorites are local-only. */
    static boolean friendRowAddressable(final Message m)
    {
        if (m == null)
        {
            return false;
        }
        // text: anchored on the msgV3 hash; file/media/voice: on the symmetric tox file_id.
        if (m.TRIFA_MESSAGE_TYPE == TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value)
        {
            return m.msg_idv3_hash != null && m.msg_idv3_hash.length() == 64;
        }
        return friendFileRow(m);
    }

    /** A 1:1 file/media/voice row with a usable symmetric anchor. */
    private static boolean friendFileRow(final Message m)
    {
        return m != null
               && m.TRIFA_MESSAGE_TYPE == TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value
               && m.ft_id_anchor_hex != null && m.ft_id_anchor_hex.length() == 64;
    }

    /**
     * Toggle the OWN reaction on a 1:1 message (tap on a chip or the quick bar): same emoji
     * removes, a different one replaces. DB first, then wire (or reactions_pending for the flush).
     */
    static void toggleOwnFriendReaction(final Message m, final String emoji)
    {
        if (m == null || !validEmoji(emoji))
        {
            return;
        }
        reaction_executor.execute(() -> {
            try
            {
                final boolean local_only = FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey)
                                           || !friendRowAddressable(m);
                final String cur = actorEmoji(m.reactions, OWN_MARKER);
                final boolean add = !emoji.equals(cur);
                final String newJson = applyActor(m.reactions, OWN_MARKER, emoji, add);
                final boolean pending = !local_only;
                orma.updateMessage().idEq(m.id).
                        reactions(newJson).
                        reactions_pending(pending).
                        execute();
                m.reactions = newJson;
                m.reactions_pending = pending;
                HelperMessage.update_single_message(m, true);

                if (!local_only)
                {
                    final long friend_number =
                            HelperFriend.tox_friend_by_public_key__wrapper(m.tox_friendpubkey);
                    if (friend_number >= 0)
                    {
                        deliverFriendReaction(friend_number, m);
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "toggleOwnFriendReaction:EE:" + e.getMessage());
            }
        });
    }

    /** Send the CURRENT own-reaction state of this row (add of the current emoji, or a remove). */
    private static void deliverFriendReaction(final long friend_number, final Message m)
    {
        try
        {
            final boolean isFile = friendFileRow(m);
            final String anchorHex = isFile ? m.ft_id_anchor_hex : m.msg_idv3_hash;
            final byte anchorType = isFile ? ANCHOR_MSG_ID_HASH : ANCHOR_MSGV3_HASH;
            final String own = actorEmoji(m.reactions, OWN_MARKER);
            final byte[] pkt = buildFriendReactionPacket(anchorHex,
                                                         anchorType,
                                                         System.currentTimeMillis() / 1000,
                                                         own != null ? own : REMOVE_PLACEHOLDER,
                                                         own != null);
            if (pkt == null)
            {
                orma.updateMessage().idEq(m.id).reactions_pending(false).execute();
                m.reactions_pending = false;
                return;
            }
            final int res = MainActivity.tox_friend_send_lossless_packet(friend_number, pkt, pkt.length);
            if (res == 0)
            {
                orma.updateMessage().idEq(m.id).reactions_pending(false).execute();
                m.reactions_pending = false;
            }
            // res != 0 (offline / temporary): reactions_pending stays set → reconnect flush
        }
        catch (Exception e)
        {
            Log.i(TAG, "deliverFriendReaction:EE:" + e.getMessage());
        }
    }

    /** Deliver every pending reaction state for this friend — call when the friend comes online. */
    static void flushPendingReactionsForFriend(final long friend_number)
    {
        try
        {
            final String pubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            if (pubkey == null)
            {
                return;
            }
            final List<Message> pending = orma.selectFromMessage().
                    tox_friendpubkeyEq(pubkey).
                    reactions_pendingEq(true).
                    toList();
            if (pending == null)
            {
                return;
            }
            for (final Message m : pending)
            {
                deliverFriendReaction(friend_number, m);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "flushPendingReactionsForFriend:EE:" + e.getMessage());
        }
    }

    /** Incoming 1:1 reaction — called from the friend lossless packet dispatcher. */
    static void handleIncomingFriendReaction(final long friend_number, final byte[] data, final int length)
    {
        try
        {
            final byte anchorType = data[4];
            if (length < (FRIEND_HEADER_LEN + 1) || data[1] != MAGIC_1 || data[2] != MAGIC_2
                || data[3] != VERSION
                || (anchorType != ANCHOR_MSGV3_HASH && anchorType != ANCHOR_MSG_ID_HASH))
            {
                return;
            }
            final boolean add = (data[41] == 1);
            final int emLen = data[42] & 0xFF;
            if (emLen < 1 || emLen > MAX_EMOJI_BYTES || length < (FRIEND_HEADER_LEN + emLen))
            {
                return;
            }
            final String emoji = new String(data, FRIEND_HEADER_LEN, emLen, StandardCharsets.UTF_8);
            final String hashHex = HelperGeneric.bytesToHex(data, 5, 32).toUpperCase(Locale.ENGLISH);
            final String senderPubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            if (senderPubkey == null || (add && !validEmoji(emoji)))
            {
                return;
            }

            Message m = null;
            try
            {
                // the friend may react to messages of EITHER direction in our shared chat.
                // text anchors on msg_idv3_hash; file/media/voice on the symmetric tox file_id.
                final List<Message> list = (anchorType == ANCHOR_MSG_ID_HASH)
                        ? orma.selectFromMessage().
                                ft_id_anchor_hexEq(hashHex).
                                tox_friendpubkeyEq(senderPubkey).
                                orderByIdDesc().
                                toList()
                        : orma.selectFromMessage().
                                msg_idv3_hashEq(hashHex).
                                tox_friendpubkeyEq(senderPubkey).
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
                // reactions never create state for unknown originals — silent drop
                return;
            }

            final String actor = senderPubkey.toUpperCase(Locale.ENGLISH);
            final String oldJson = m.reactions;
            final String newJson = applyActor(oldJson, actor, emoji, add);
            // KHANDAQ (#F6): a reaction packet can be re-delivered (offline re-flush on reconnect,
            // sender's reactions_pending re-send). applyActor is idempotent (one reaction per actor),
            // so a duplicate yields the SAME json — do NOT re-write the DB or re-fire the
            // notification, otherwise the "liked your voice message" notice keeps re-arriving.
            final boolean changed = (newJson == null) ? (oldJson != null) : !newJson.equals(oldJson);
            if (changed)
            {
                orma.updateMessage().idEq(m.id).reactions(newJson).execute();
                m.reactions = newJson;
                HelperMessage.update_single_message(m, true);
            }

            if (add && changed)
            {
                notifyFriendReaction(m, senderPubkey, emoji);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "handleIncomingFriendReaction:EE:" + e.getMessage());
        }
    }

    private static void notifyFriendReaction(final Message m, final String senderPubkey, final String emoji)
    {
        try
        {
            String friendname = null;
            try
            {
                final FriendList f = (FriendList) orma.selectFromFriendList().
                        tox_public_key_stringEq(senderPubkey).toList().get(0);
                if (f.notification_silent)
                {
                    return;
                }
                friendname = (f.alias_name != null && f.alias_name.length() > 0) ? f.alias_name : f.name;
            }
            catch (Exception ignored)
            {
            }
            final String body = String.format(
                    MainActivity.context_s.getString(R.string.reaction_notification_body), emoji);
            HelperMsgNotification.change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value,
                                                          m.tox_friendpubkey, friendname, body);
        }
        catch (Exception e)
        {
            Log.i(TAG, "notifyFriendReaction:EE:" + e.getMessage());
        }
    }

    // =============================================================================================
    // NGC groups
    // =============================================================================================

    static byte[] buildGroupReactionPacket(final byte anchorType, final String anchorHex,
                                           final String authorPubkeyHex, final long tsSec,
                                           final String emoji, final boolean add)
    {
        try
        {
            final byte[] anchor = hexToBytes(anchorHex);
            final byte[] author = hexToBytes(authorPubkeyHex);
            final byte[] em = emoji.getBytes(StandardCharsets.UTF_8);
            if (anchor == null || anchor.length < 4 || anchor.length > 32
                || author == null || author.length != 32
                || em.length < 1 || em.length > MAX_EMOJI_BYTES)
            {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(NGC_HEADER_LEN + em.length);
            out.write(0x66);
            out.write(0x77);
            out.write(0x88);
            out.write(0x11);
            out.write(0x34);
            out.write(0x35);
            out.write(VERSION);
            out.write(NGC_PKT_MSG_REACTION);
            out.write(anchorType);
            out.write(anchor, 0, anchor.length);
            for (int i = anchor.length; i < 32; i++)
            {
                out.write(0); // zero-pad the 4-byte message_id anchor to the fixed 32-byte field
            }
            out.write(author, 0, 32);
            writeU32BE(out, tsSec);
            out.write(add ? 1 : 0);
            out.write(em.length);
            out.write(em, 0, em.length);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Shared anchor for a group row: text → message_id_tox (type 1), file → msg_id_hash (type 2). */
    private static byte groupAnchorType(final GroupMessage gm)
    {
        if (gm.message_id_tox != null && gm.message_id_tox.length() == 8
            && !HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX.equals(gm.message_id_tox))
        {
            return ANCHOR_MSGV3_HASH; // = 1: 4-byte message_id in the first anchor bytes
        }
        if (gm.msg_id_hash != null && gm.msg_id_hash.length() == 64)
        {
            return ANCHOR_MSG_ID_HASH;
        }
        return 0;
    }

    static boolean groupRowAddressable(final GroupMessage gm)
    {
        return gm != null && gm.private_message == 0 && groupAnchorType(gm) != 0
               && gm.tox_group_peer_pubkey != null && gm.tox_group_peer_pubkey.length() == 64;
    }

    /** Toggle the OWN reaction on a group message and broadcast it (best effort, like edits). */
    static void toggleOwnGroupReaction(final GroupMessage gm, final String emoji)
    {
        if (gm == null || !validEmoji(emoji))
        {
            return;
        }
        reaction_executor.execute(() -> {
            try
            {
                final String cur = actorEmoji(gm.reactions, OWN_MARKER);
                final boolean add = !emoji.equals(cur);
                final String newJson = applyActor(gm.reactions, OWN_MARKER, emoji, add);
                final boolean addressable = groupRowAddressable(gm);
                orma.updateGroupMessage().idEq(gm.id).
                        reactions(newJson).
                        reactions_pending(addressable).
                        execute();
                gm.reactions = newJson;
                gm.reactions_pending = addressable;
                updateGroupMessageInUi(gm);

                if (!addressable)
                {
                    return;
                }
                final long group_number = HelperGroup.tox_group_by_groupid__wrapper(gm.group_identifier);
                if (group_number < 0)
                {
                    return;
                }
                final byte anchorType = groupAnchorType(gm);
                final String anchorHex = (anchorType == ANCHOR_MSG_ID_HASH)
                                         ? gm.msg_id_hash : gm.message_id_tox;
                final String own = actorEmoji(gm.reactions, OWN_MARKER);
                final byte[] pkt = buildGroupReactionPacket(anchorType, anchorHex,
                                                            gm.tox_group_peer_pubkey,
                                                            System.currentTimeMillis() / 1000,
                                                            own != null ? own : REMOVE_PLACEHOLDER,
                                                            own != null);
                if (pkt == null)
                {
                    return;
                }
                final int res = MainActivity.tox_group_send_custom_packet(group_number, 1, pkt, pkt.length);
                if (HelperGroup.is_ngc_custom_packet_send_ok(res))
                {
                    orma.updateGroupMessage().idEq(gm.id).reactions_pending(false).execute();
                    gm.reactions_pending = false;
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "toggleOwnGroupReaction:EE:" + e.getMessage());
            }
        });
    }

    /** Incoming NGC reaction — called from the group custom packet dispatcher AFTER the magic check. */
    static void handleIncomingGroupReaction(final long group_number, final long peer_id, final byte[] data,
                                            final int length)
    {
        try
        {
            if (length < (NGC_HEADER_LEN + 1))
            {
                return;
            }
            final byte anchorType = data[8];
            final boolean add = (data[77] == 1);
            final int emLen = data[78] & 0xFF;
            if (emLen < 1 || emLen > MAX_EMOJI_BYTES || length < (NGC_HEADER_LEN + emLen))
            {
                return;
            }
            final String emoji = new String(data, NGC_HEADER_LEN, emLen, StandardCharsets.UTF_8);
            final String senderPubkey =
                    HelperGroup.tox_group_peer_get_public_key__wrapper(group_number, peer_id);
            final String groupIdentifier = HelperGroup.tox_group_by_groupnum__wrapper(group_number);
            if (senderPubkey == null || groupIdentifier == null || (add && !validEmoji(emoji)))
            {
                return;
            }
            // drop own echoes (history relays may bounce our own packet back)
            try
            {
                final String self = MainActivity.tox_group_self_get_public_key(group_number);
                if (self != null && self.equalsIgnoreCase(senderPubkey))
                {
                    return;
                }
            }
            catch (Exception ignored)
            {
            }
            final String authorPubkey =
                    HelperGeneric.bytesToHex(data, 41, 32).toUpperCase(Locale.ENGLISH);

            GroupMessage gm = null;
            try
            {
                if (anchorType == ANCHOR_MSGV3_HASH)
                {
                    final String messageIdHex =
                            HelperGeneric.bytesToHex(data, 9, 4).toLowerCase(Locale.ENGLISH);
                    if (HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX.equals(messageIdHex))
                    {
                        return;
                    }
                    final List<GroupMessage> list = orma.selectFromGroupMessage().
                            group_identifierEq(groupIdentifier).
                            tox_group_peer_pubkeyEq(authorPubkey).
                            message_id_toxEq(messageIdHex).
                            orderByIdDesc().
                            toList();
                    if (list != null && !list.isEmpty())
                    {
                        gm = list.get(0);
                    }
                }
                else if (anchorType == ANCHOR_MSG_ID_HASH)
                {
                    final String msgIdHashHex =
                            HelperGeneric.bytesToHex(data, 9, 32).toUpperCase(Locale.ENGLISH);
                    final List<GroupMessage> list = orma.selectFromGroupMessage().
                            group_identifierEq(groupIdentifier).
                            tox_group_peer_pubkeyEq(authorPubkey).
                            msg_id_hashEq(msgIdHashHex).
                            orderByIdDesc().
                            toList();
                    if (list != null && !list.isEmpty())
                    {
                        gm = list.get(0);
                    }
                }
            }
            catch (Exception ignored)
            {
            }
            if (gm == null)
            {
                // reactions never create state for unknown originals — silent drop
                return;
            }

            final String actor = senderPubkey.toUpperCase(Locale.ENGLISH);
            final String oldJson = gm.reactions;
            final String newJson = applyActor(oldJson, actor, emoji, add);
            // KHANDAQ (audit #17): NGC reaction packets are best-effort broadcast AND re-broadcast/history-
            // relayed, so the same packet arrives more than once. applyActor is idempotent, so a duplicate
            // yields the SAME json — mirror the 1:1 path (#F6) and skip the redundant DB/UI write and,
            // crucially, the repeated group notification.
            final boolean changed = (newJson == null) ? (oldJson != null) : !newJson.equals(oldJson);
            if (changed)
            {
                orma.updateGroupMessage().idEq(gm.id).reactions(newJson).execute();
                gm.reactions = newJson;
                updateGroupMessageInUi(gm);
            }

            if (add && changed)
            {
                notifyGroupReaction(groupIdentifier, senderPubkey, emoji);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "handleIncomingGroupReaction:EE:" + e.getMessage());
        }
    }

    private static void notifyGroupReaction(final String groupIdentifier, final String senderPubkey,
                                            final String emoji)
    {
        try
        {
            // an open chat of the same group suppresses the banner (mirror of the text callsites)
            try
            {
                if (MainActivity.group_message_list_activity != null
                    && MainActivity.group_message_list_activity.get_current_group_id() != null
                    && MainActivity.group_message_list_activity.get_current_group_id().
                        equalsIgnoreCase(groupIdentifier))
                {
                    return;
                }
            }
            catch (Exception ignored)
            {
            }
            String groupname = null;
            try
            {
                final GroupDB group_temp = (GroupDB) orma.selectFromGroupDB().
                        group_identifierEq(groupIdentifier.toLowerCase(Locale.ENGLISH)).toList().get(0);
                if (group_temp.notification_silent)
                {
                    return;
                }
                groupname = group_temp.name;
            }
            catch (Exception ignored)
            {
            }
            if (HelperGroup.group_notification_silent_peer_get(groupIdentifier, senderPubkey))
            {
                return;
            }
            String peername = null;
            try
            {
                peername = HelperGroup.tox_group_peer_get_name__wrapper(groupIdentifier, senderPubkey);
            }
            catch (Exception ignored)
            {
            }
            String body = String.format(
                    MainActivity.context_s.getString(R.string.reaction_notification_body), emoji);
            if (peername != null && peername.length() > 0)
            {
                body = peername + " " + body;
            }
            HelperMsgNotification.change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value,
                                                          groupIdentifier, groupname, body);
        }
        catch (Exception e)
        {
            Log.i(TAG, "notifyGroupReaction:EE:" + e.getMessage());
        }
    }

    private static void updateGroupMessageInUi(final GroupMessage gm)
    {
        try
        {
            if ((MainActivity.group_message_list_fragment == null)
                || (!gm.group_identifier.equals(MainActivity.group_message_list_fragment.current_group_id)))
            {
                return;
            }
            final GroupMessage gm2 = gm;
            final Runnable myRunnable = () -> {
                try
                {
                    MainActivity.group_message_list_fragment.modify_message(gm2);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            };
            if (MainActivity.main_handler_s != null)
            {
                MainActivity.main_handler_s.post(myRunnable);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    // =============================================================================================
    // selection-toolbar entry points (mirror HelperMessageEdit)
    // =============================================================================================

    /** Gate for the "React" menu item: exactly one message that can hold a reaction. */
    static boolean canReactToCurrentSelection()
    {
        try
        {
            if (MainActivity.selected_messages.size() == 1)
            {
                final long id = MainActivity.selected_messages.iterator().next();
                final List<Message> rows = orma.selectFromMessage().idEq(id).toList();
                if (rows == null || rows.isEmpty())
                {
                    return false;
                }
                final Message m = rows.get(0);
                // Reactable in the UI when: Saved Messages (local-only), any text row with a shared
                // anchor, OR any file/media/voice row (iOS parity — reaction is offered always;
                // toggleOwnFriendReaction stores it locally and only puts it on the wire when the
                // file has a shared anchor, so a voice note is always reactable at least locally).
                return FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey)
                       || friendRowAddressable(m)
                       || m.TRIFA_MESSAGE_TYPE == TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value;
            }
            if (MainActivity.selected_group_messages.size() == 1)
            {
                final long id = MainActivity.selected_group_messages.iterator().next();
                final List<GroupMessage> rows = orma.selectFromGroupMessage().idEq(id).toList();
                if (rows == null || rows.isEmpty())
                {
                    return false;
                }
                return groupRowAddressable(rows.get(0));
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    static void reactSelectedDirectMessage(final android.content.Context context)
    {
        try
        {
            if (MainActivity.selected_messages.size() != 1)
            {
                return;
            }
            final long id = MainActivity.selected_messages.iterator().next();
            final List<Message> rows = orma.selectFromMessage().idEq(id).toList();
            if (rows != null && !rows.isEmpty())
            {
                final Message m = rows.get(0);
                showQuickReactionDialog(context, actorEmoji(m.reactions, OWN_MARKER),
                                        emoji -> toggleOwnFriendReaction(m, emoji));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void reactSelectedGroupMessage(final android.content.Context context)
    {
        try
        {
            if (MainActivity.selected_group_messages.size() != 1)
            {
                return;
            }
            final long id = MainActivity.selected_group_messages.iterator().next();
            final List<GroupMessage> rows = orma.selectFromGroupMessage().idEq(id).toList();
            if (rows != null && !rows.isEmpty())
            {
                final GroupMessage gm = rows.get(0);
                showQuickReactionDialog(context, actorEmoji(gm.reactions, OWN_MARKER),
                                        emoji -> toggleOwnGroupReaction(gm, emoji));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    interface OnReactionPicked
    {
        void onPicked(String emoji);
    }

    /** Horizontal quick-bar dialog; the actor's current emoji is highlighted (tap = remove). */
    static void showQuickReactionDialog(final android.content.Context context, final String currentEmoji,
                                        final OnReactionPicked cb)
    {
        try
        {
            final float d = context.getResources().getDisplayMetrics().density;
            final android.widget.LinearLayout row = new android.widget.LinearLayout(context);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            final int pad = (int) (10 * d);
            row.setPadding(pad, pad, pad, pad);

            final android.widget.HorizontalScrollView scroll =
                    new android.widget.HorizontalScrollView(context);
            // KHANDAQ (release 0.2.40, found by QA on real phones): the row must be allowed to be
            // WIDER than the viewport, or there is nothing to scroll and the last emoji is simply
            // clipped off.
            //
            // HorizontalScrollView extends FrameLayout, whose generateDefaultLayoutParams() returns
            // MATCH_PARENT. addView(row) therefore pinned the row to the dialog's width: the first
            // six emoji took their natural size and the seventh got whatever was left — 30px of 96
            // on a 720px screen, 27px of 147 on a 1080px one. One of the seven offered reactions was
            // unpickable on every device, and the bar could not be scrolled to reach it because the
            // content never exceeded the viewport.
            scroll.addView(row, new android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            // Nothing to fill: the row sizes itself to its content and scrolls when it overflows.
            scroll.setFillViewport(false);
            scroll.setHorizontalScrollBarEnabled(false);

            final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(context).
                    setView(scroll).create();

            for (final String emoji : QUICK_REACTIONS)
            {
                final android.widget.TextView tv = new android.widget.TextView(context);
                tv.setText(emoji);
                tv.setTextSize(30);
                final int p = (int) (8 * d);
                tv.setPadding(p, p / 2, p, p / 2);
                if (emoji.equals(currentEmoji))
                {
                    tv.setBackgroundColor(0x336C9DE0);
                }
                tv.setOnClickListener(v -> {
                    try
                    {
                        dialog.dismiss();
                    }
                    catch (Exception ignored)
                    {
                    }
                    cb.onPicked(emoji);
                });
                row.addView(tv);
            }
            dialog.show();
        }
        catch (Exception e)
        {
            Log.i(TAG, "showQuickReactionDialog:EE:" + e.getMessage());
        }
    }

    // =============================================================================================
    // helpers
    // =============================================================================================

    private static void writeU32BE(final ByteArrayOutputStream out, final long v)
    {
        out.write((int) ((v >> 24) & 0xff));
        out.write((int) ((v >> 16) & 0xff));
        out.write((int) ((v >> 8) & 0xff));
        out.write((int) (v & 0xff));
    }

    private static byte[] hexToBytes(final String hex)
    {
        if (hex == null || (hex.length() % 2) != 0)
        {
            return null;
        }
        try
        {
            final int len = hex.length() / 2;
            final byte[] out = new byte[len];
            for (int i = 0; i < len; i++)
            {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }
        catch (Exception e)
        {
            return null;
        }
    }
}

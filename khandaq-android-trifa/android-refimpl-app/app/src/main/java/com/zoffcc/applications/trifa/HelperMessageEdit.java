package com.zoffcc.applications.trifa;

import android.util.Log;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (#9): app-level message editing on top of Tox. Tox has no native edit, so edits travel
 * as custom lossless packets that OLD clients silently drop (verified: unknown pkt ids fall through
 * both dispatchers with no UI side effects):
 *
 *   1:1 friend lossless packet (pkt id 186, "KQ" magic):
 *     [0]=0xBA  [1..2]='K','Q'  [3]=version(1)  [4..35]=msgv3 hash of the original (32 bytes)
 *     [36..39]=edit unix-seconds u32 BE  [40..]=new text UTF-8 (1..MAX_EDIT_TEXT_BYTES)
 *
 *   NGC custom broadcast packet (standard NGC header, pkt id 0x41):
 *     [0..5]=0x667788113435  [6]=version(1)  [7]=0x41
 *     [8..11]=message_id of the original (4 bytes, as in message_id_tox hex)  [12..15]=edit ts u32 BE
 *     [16..]=new text UTF-8
 *
 * Rules: only own text messages are editable; last-write-wins by edit_ts (idempotent against dup
 * delivery); the receiver authenticates by the packet source (friend_number / peer pubkey), payload
 * carries no author field. An edit that arrives before its original is stashed and applied when the
 * original row is inserted (same pattern as the msgv3 read-ACK stash).
 */
public class HelperMessageEdit
{
    private static final String TAG = "trifa.HelperMsgEdit";

    // 1:1 friend lossless packet id (160..254 range; 175-179/181-186 are taken by other features,
    // 186 was free — see TRIFAGlobals/MessageChunker/ConnectionHealthMonitor/HelperGroup ids)
    static final int PKT_MSG_EDIT = 186;
    private static final byte MAGIC_1 = 'K';
    private static final byte MAGIC_2 = 'Q';
    private static final byte VERSION = 1;
    private static final int FRIEND_HEADER_LEN = 1 + 2 + 1 + 32 + 4; // 40

    // NGC custom packet id inside the standard NGC header (0x01-0x04/0x11-0x14/0x21/0x31 taken)
    static final byte NGC_PKT_MSG_EDIT = 0x41;
    private static final int NGC_HEADER_LEN = 6 + 1 + 1 + 4 + 4; // 16

    /** Единый безопасный лимит нового текста (ниже обоих транспортных потолков). */
    static final int MAX_EDIT_TEXT_BYTES = 1300;

    /** Окно редактирования на СТОРОНЕ ОТПРАВИТЕЛЯ (приёмник окно не проверяет). */
    static final long EDIT_WINDOW_MS = 48L * 60 * 60 * 1000;

    // ---- stash: edits that arrived before their original message ------------------------------
    private static class PendingEdit
    {
        final long ts;
        final String text;

        PendingEdit(long ts, String text)
        {
            this.ts = ts;
            this.text = text;
        }
    }

    private static final ConcurrentHashMap<String, PendingEdit> pending_friend_edits =
            new ConcurrentHashMap<>(); // key: sender_pubkey + ":" + msgv3 hash (uppercase)

    // =============================================================================================
    // 1:1
    // =============================================================================================

    static byte[] buildFriendEditPacket(final String msgV3HashHex, final long editTsSec, final String newText)
    {
        try
        {
            final byte[] hash = hexToBytes(msgV3HashHex);
            final byte[] text = newText.getBytes(StandardCharsets.UTF_8);
            if (hash == null || hash.length != 32 || text.length < 1 || text.length > MAX_EDIT_TEXT_BYTES)
            {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(FRIEND_HEADER_LEN + text.length);
            out.write(PKT_MSG_EDIT);
            out.write(MAGIC_1);
            out.write(MAGIC_2);
            out.write(VERSION);
            out.write(hash, 0, 32);
            writeU32BE(out, editTsSec);
            out.write(text, 0, text.length);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** Incoming 1:1 edit — called from the friend lossless packet dispatcher. */
    static void handleIncomingFriendEdit(final long friend_number, final byte[] data, final int length)
    {
        try
        {
            if (length < (FRIEND_HEADER_LEN + 1) || data[1] != MAGIC_1 || data[2] != MAGIC_2
                || data[3] != VERSION)
            {
                return;
            }
            final String hashHex = HelperGeneric.bytesToHex(data, 4, 32).toUpperCase(Locale.ENGLISH);
            final long editTs = readU32BE(data, 36);
            final int textLen = length - FRIEND_HEADER_LEN;
            if (textLen > MAX_EDIT_TEXT_BYTES)
            {
                return;
            }
            final String newText = new String(data, FRIEND_HEADER_LEN, textLen, StandardCharsets.UTF_8);
            final String senderPubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            if (senderPubkey == null || newText.isEmpty())
            {
                return;
            }

            // register first (race with the original's insert), then look up — like the ACK stash
            final String stashKey = senderPubkey + ":" + hashHex;
            if (pending_friend_edits.size() > 200)
            {
                pending_friend_edits.clear();
            }
            pending_friend_edits.put(stashKey, new PendingEdit(editTs, newText));

            Message m = null;
            try
            {
                // direction==0: on the receiver the original is an INCOMING message from this friend —
                // that constraint is the anti-spoofing check (you can only edit what YOU sent me).
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
                // original not here yet — stays stashed, applied on insert
                return;
            }

            pending_friend_edits.remove(stashKey);
            applyFriendEdit(m, editTs, newText);
        }
        catch (Exception e)
        {
            Log.i(TAG, "handleIncomingFriendEdit:EE:" + e.getMessage());
        }
    }

    /** Called from insert_into_message_db for a fresh INCOMING message: apply a stashed edit. */
    static boolean consume_pending_edit(final Message m)
    {
        if (m == null || m.direction != 0 || m.msg_idv3_hash == null || m.msg_idv3_hash.isEmpty()
            || m.tox_friendpubkey == null)
        {
            return false;
        }
        final PendingEdit pe = pending_friend_edits.remove(m.tox_friendpubkey + ":" + m.msg_idv3_hash);
        if (pe == null)
        {
            return false;
        }
        m.text = pe.text;
        m.edited = true;
        m.edited_timestamp = pe.ts;
        return true;
    }

    private static void applyFriendEdit(final Message m, final long editTs, final String newText)
    {
        // last-write-wins + idempotency against duplicate delivery
        if (m.edited && editTs < m.edited_timestamp)
        {
            return;
        }
        if (m.edited && editTs == m.edited_timestamp && newText.equals(m.text))
        {
            return;
        }
        try
        {
            orma.updateMessage().idEq(m.id).
                    text(newText).
                    edited(true).
                    edited_timestamp(editTs).
                    execute();
            m.text = newText;
            m.edited = true;
            m.edited_timestamp = editTs;
            HelperMessage.update_single_message(m, true);
        }
        catch (Exception e)
        {
            Log.i(TAG, "applyFriendEdit:EE:" + e.getMessage());
        }
    }

    /**
     * Send an edit of OWN 1:1 message: update the row locally, then deliver (or mark edit_pending
     * for the reconnect flush). The DB row is the source of truth — the flush rebuilds the packet
     * from the CURRENT text, so repeated edits collapse into one delivery (last-write-wins).
     */
    static boolean sendOwnFriendEdit(final long friend_number, final Message m, final String newText)
    {
        try
        {
            final long editTs = System.currentTimeMillis() / 1000;
            orma.updateMessage().idEq(m.id).
                    text(newText).
                    edited(true).
                    edited_timestamp(editTs).
                    edit_pending(true).
                    execute();
            m.text = newText;
            m.edited = true;
            m.edited_timestamp = editTs;
            m.edit_pending = true;
            HelperMessage.update_single_message(m, true);

            deliverFriendEdit(friend_number, m);
            return true;
        }
        catch (Exception e)
        {
            Log.i(TAG, "sendOwnFriendEdit:EE:" + e.getMessage());
            return false;
        }
    }

    private static void deliverFriendEdit(final long friend_number, final Message m)
    {
        try
        {
            final byte[] pkt = buildFriendEditPacket(m.msg_idv3_hash, m.edited_timestamp, m.text);
            if (pkt == null)
            {
                return;
            }
            final int res = MainActivity.tox_friend_send_lossless_packet(friend_number, pkt, pkt.length);
            if (res == 0)
            {
                orma.updateMessage().idEq(m.id).edit_pending(false).execute();
                m.edit_pending = false;
            }
            // res != 0 (offline / temporary failure): edit_pending stays set → reconnect flush
        }
        catch (Exception e)
        {
            Log.i(TAG, "deliverFriendEdit:EE:" + e.getMessage());
        }
    }

    /** Deliver every pending edit for this friend — call when the friend comes online. */
    static void flushPendingEditsForFriend(final long friend_number)
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
                    directionEq(1).
                    edit_pendingEq(true).
                    toList();
            if (pending == null)
            {
                return;
            }
            for (final Message m : pending)
            {
                deliverFriendEdit(friend_number, m);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "flushPendingEditsForFriend:EE:" + e.getMessage());
        }
    }

    // =============================================================================================
    // NGC groups
    // =============================================================================================

    static byte[] buildGroupEditPacket(final String messageIdToxHex, final long editTsSec, final String newText)
    {
        try
        {
            final byte[] msgId = hexToBytes(messageIdToxHex);
            final byte[] text = newText.getBytes(StandardCharsets.UTF_8);
            if (msgId == null || msgId.length != 4 || text.length < 1 || text.length > MAX_EDIT_TEXT_BYTES)
            {
                return null;
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(NGC_HEADER_LEN + text.length);
            out.write(0x66);
            out.write(0x77);
            out.write(0x88);
            out.write(0x11);
            out.write(0x34);
            out.write(0x35);
            out.write(VERSION);
            out.write(NGC_PKT_MSG_EDIT);
            out.write(msgId, 0, 4);
            writeU32BE(out, editTsSec);
            out.write(text, 0, text.length);
            return out.toByteArray();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Incoming NGC edit — called from the group custom packet dispatcher AFTER the magic check.
     * The target row is addressed by (group, sender pubkey, message_id): only the author's own
     * messages can be touched, peer_id volatility is avoided by resolving the pubkey immediately.
     */
    static void handleIncomingGroupEdit(final long group_number, final long peer_id, final byte[] data,
                                        final int length)
    {
        try
        {
            if (length < (NGC_HEADER_LEN + 1))
            {
                return;
            }
            final int textLen = length - NGC_HEADER_LEN;
            if (textLen > MAX_EDIT_TEXT_BYTES)
            {
                return;
            }
            final String messageIdHex =
                    HelperGeneric.bytesToHex(data, 8, 4).toLowerCase(Locale.ENGLISH);
            final long editTs = readU32BE(data, 12);
            final String newText = new String(data, NGC_HEADER_LEN, textLen, StandardCharsets.UTF_8);
            final String senderPubkey =
                    HelperGroup.tox_group_peer_get_public_key__wrapper(group_number, peer_id);
            final String groupIdentifier = HelperGroup.tox_group_by_groupnum__wrapper(group_number);
            if (senderPubkey == null || groupIdentifier == null || newText.isEmpty()
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
                // unknown original (or our own echo) — silent drop, never create rows from edits
                return;
            }

            if (gm.edited && editTs < gm.edited_timestamp)
            {
                return;
            }
            if (gm.edited && editTs == gm.edited_timestamp && newText.equals(gm.text))
            {
                return;
            }

            orma.updateGroupMessage().idEq(gm.id).
                    text(newText).
                    edited(true).
                    edited_timestamp(editTs).
                    execute();
            gm.text = newText;
            gm.edited = true;
            gm.edited_timestamp = editTs;
            updateGroupMessageInUi(gm);
        }
        catch (Exception e)
        {
            Log.i(TAG, "handleIncomingGroupEdit:EE:" + e.getMessage());
        }
    }

    /** Edit OWN group message: update the row, broadcast the edit to the group (best effort). */
    static boolean sendOwnGroupEdit(final long group_number, final GroupMessage gm, final String newText)
    {
        try
        {
            final long editTs = System.currentTimeMillis() / 1000;
            orma.updateGroupMessage().idEq(gm.id).
                    text(newText).
                    edited(true).
                    edited_timestamp(editTs).
                    edit_pending(true).
                    execute();
            gm.text = newText;
            gm.edited = true;
            gm.edited_timestamp = editTs;
            gm.edit_pending = true;
            updateGroupMessageInUi(gm);

            final byte[] pkt = buildGroupEditPacket(gm.message_id_tox, editTs, newText);
            if (pkt != null)
            {
                final int res = MainActivity.tox_group_send_custom_packet(group_number, 1, pkt, pkt.length);
                if (res == 0)
                {
                    orma.updateGroupMessage().idEq(gm.id).edit_pending(false).execute();
                    gm.edit_pending = false;
                }
            }
            return true;
        }
        catch (Exception e)
        {
            Log.i(TAG, "sendOwnGroupEdit:EE:" + e.getMessage());
            return false;
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
    // selection-toolbar entry points (mirror HelperReply)
    // =============================================================================================

    /** Gate for the "Изменить" menu item: exactly one OWN, addressable text message in the window. */
    static boolean canEditCurrentSelection()
    {
        try
        {
            if (MainActivity.selected_messages.size() == 1
                && MainActivity.selected_messages_text_only.size() == 1)
            {
                final long id = MainActivity.selected_messages.iterator().next();
                final List<Message> rows = orma.selectFromMessage().idEq(id).toList();
                if (rows == null || rows.isEmpty())
                {
                    return false;
                }
                final Message m = rows.get(0);
                if (m.direction != 1)
                {
                    return false;
                }
                // Saved Messages: purely local edit, always allowed
                if (FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey))
                {
                    return true;
                }
                // v1: replies are not editable — the edit would destroy the [KQ|...] quote header
                if (m.text != null && !m.text.equals(MessageReplyHelper.parse(m.text).bodyText))
                {
                    return false;
                }
                // network edit needs the msgv3 hash to address the original + the 48h window
                if (m.msg_idv3_hash == null || m.msg_idv3_hash.length() < 64)
                {
                    return false;
                }
                return (System.currentTimeMillis() - m.sent_timestamp) < EDIT_WINDOW_MS;
            }

            if (MainActivity.selected_group_messages.size() == 1
                && MainActivity.selected_group_messages_text_only.size() == 1)
            {
                final long id = MainActivity.selected_group_messages.iterator().next();
                final List<GroupMessage> rows = orma.selectFromGroupMessage().idEq(id).toList();
                if (rows == null || rows.isEmpty())
                {
                    return false;
                }
                final GroupMessage gm = rows.get(0);
                if (gm.direction != 1 || gm.private_message != 0)
                {
                    return false;
                }
                // v1: replies/mentions-encoded messages are not editable (would destroy the header)
                if (gm.text != null && !gm.text.equals(MessageReplyHelper.parse(gm.text).bodyText))
                {
                    return false;
                }
                if (gm.message_id_tox == null || gm.message_id_tox.length() != 8
                    || HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX.equals(gm.message_id_tox))
                {
                    return false;
                }
                return (System.currentTimeMillis() - gm.sent_timestamp) < EDIT_WINDOW_MS;
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    static void editSelectedDirectMessage(final android.content.Context context)
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
                ChatReplyPreviewController.startEditDirectMessage(context, rows.get(0));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void editSelectedGroupMessage(final android.content.Context context)
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
                ChatReplyPreviewController.startEditGroupMessage(context, rows.get(0));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    /** Local-only edit for Saved Messages (no network). */
    static boolean editLocalDirectMessage(final Message m, final String newText)
    {
        try
        {
            final long editTs = System.currentTimeMillis() / 1000;
            orma.updateMessage().idEq(m.id).
                    text(newText).
                    edited(true).
                    edited_timestamp(editTs).
                    execute();
            m.text = newText;
            m.edited = true;
            m.edited_timestamp = editTs;
            HelperMessage.update_single_message(m, true);
            return true;
        }
        catch (Exception e)
        {
            return false;
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

    private static long readU32BE(final byte[] data, final int off)
    {
        return ((long) (data[off] & 0xff) << 24)
               | ((long) (data[off + 1] & 0xff) << 16)
               | ((long) (data[off + 2] & 0xff) << 8)
               | ((long) (data[off + 3] & 0xff));
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

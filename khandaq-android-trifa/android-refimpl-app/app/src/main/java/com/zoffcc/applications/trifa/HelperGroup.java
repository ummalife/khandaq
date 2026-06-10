/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2022 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.util.Log;

import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.GroupPeerDB;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import id.zelory.compressor.Compressor;

import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_CONFERENCE;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_GROUP;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.NGC_VIDEO_ICON_STATE_ACTIVE;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.NGC_VIDEO_ICON_STATE_INCOMING;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.lookup_ngc_incoming_video_peer_list;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.ml_video_icon;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.ngc_camera_info_text;
import static com.zoffcc.applications.trifa.HelperFiletransfer.get_incoming_filetransfer_local_filename;
import static com.zoffcc.applications.trifa.HelperFiletransfer.save_group_incoming_file;
import static com.zoffcc.applications.trifa.HelperGeneric.bytebuffer_to_hexstring;
import static com.zoffcc.applications.trifa.HelperGeneric.bytes_to_hex;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.fourbytes_of_long_to_hex;
import static com.zoffcc.applications.trifa.HelperGeneric.io_file_copy;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.utf8_string_from_bytes_with_padding;
import static com.zoffcc.applications.trifa.HelperMsgNotification.change_msg_notification;
import static com.zoffcc.applications.trifa.MainActivity.PREF__conference_show_system_messages;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.group_message_list_activity;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages_incoming_file;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages_text_only;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_by_chat_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_chat_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_privacy_state;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_new;
import static com.zoffcc.applications.trifa.HelperRelay.have_own_relay;
import static com.zoffcc.applications.trifa.HelperRelay.invite_to_group_own_relay;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_peer_limit;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_topic;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_set_topic;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_invite_friend;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_is_connected;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_mod_set_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_count;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_peerlist;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_peer_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_packet;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_private_packet;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GROUP_ID_LENGTH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.KHANDAQ_COMMUNITY_DISPLAY_NAME;
import static com.zoffcc.applications.trifa.TRIFAGlobals.KHANDAQ_COMMUNITY_GROUPID;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_toxid;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_GROUP_HISTORY_SYNC_DOUBLE_INTERVAL_SECS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_ADD;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_NGC_HISTORY_SYNC_MAX_FILENAME_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.KHANDAQ_COMMUNITY_HISTORY_SYNC_MAX_SECONDS_BACK;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_NGC_HISTORY_SYNC_MAX_SECONDS_BACK;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_INCOMING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UINT32_MAX_JAVA;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_PREFIX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_for_battery_savings_ts;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ERR_GROUP_INVITE_FRIEND;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ERR_GROUP_NEW;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_CHAT_ID_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_MAX_GROUP_NAME_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_MAX_PART_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_PEER_PUBLIC_KEY_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_HASH_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_FILENAME_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_FILESIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_FILE_AND_HEADER_SIZE;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class HelperGroup
{
    private static final String TAG = "trifa.Hlp.Group";
    static final int DEFAULT_GROUP_PEER_LIMIT = 100;

    static void add_group_wrapper(final long friend_number, long group_num, String group_identifier_in, final int a_TOX_GROUP_PRIVACY_STATE)
    {
        if (group_num < 0)
        {
            Log.d(TAG, "add_group_wrapper:ERR:group number less than zero:" + group_num);
            return;
        }

        String group_identifier = group_identifier_in;


        if (group_num >= 0)
        {
            new_or_updated_group(group_num, HelperFriend.tox_friend_get_public_key__wrapper(friend_number),
                                 group_identifier_in, a_TOX_GROUP_PRIVACY_STATE);
        }
        else
        {
            //Log.i(TAG, "add_conference_wrapper:error=" + conference_num + " joining conference");
        }

        try
        {
            if (group_message_list_activity != null)
            {
                if (group_message_list_activity.get_current_group_id().equals(group_identifier))
                {
                    group_message_list_activity.set_group_connection_status_icon();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        // save tox savedate file
        HelperGeneric.update_savedata_file_wrapper();
    }

    static void new_or_updated_group(long group_num, String who_invited_public_key, String group_identifier, int privacy_state)
    {
        try
        {
            // Log.i(TAG, "new_or_updated_group:" + "group_num=" + group_identifier);
            final GroupDB conf2 = (GroupDB) orma.selectFromGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList().get(0);
            // group already exists -> update and connect
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    privacy_state(privacy_state).
                    tox_group_number(group_num).execute();

            try
            {
                Log.i(TAG, "new_or_updated_group:*update*");
                final GroupDB conf3 = (GroupDB) orma.selectFromGroupDB().
                        group_identifierEq(group_identifier.toLowerCase()).toList().get(0);
                // update or add to "friendlist"
                CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
                cc.is_friend = COMBINED_IS_GROUP;
                cc.group_item = (GroupDB) GroupDB.deep_copy(conf3);
                MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
            }
            catch (Exception e3)
            {
                Log.i(TAG, "new_or_updated_group:EE3:" + e3.getMessage());
                // e3.printStackTrace();
            }

            return;
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            Log.i(TAG, "new_or_updated_group:EE1:" + e.getMessage());

            // conference is new -> add
            try
            {
                String group_topic = "";
                try
                {
                    group_topic = tox_group_get_name(group_num);
                    Log.i(TAG, "new_or_updated_group:group_topic=" + group_topic);
                    if (group_topic == null)
                    {
                        group_topic = "";
                    }
                }
                catch (Exception e6)
                {
                    e6.printStackTrace();
                    Log.i(TAG, "new_or_updated_group:EE6:" + e6.getMessage());
                }

                GroupDB conf_new = new GroupDB();
                conf_new.group_identifier = group_identifier;
                conf_new.who_invited__tox_public_key_string = who_invited_public_key;
                conf_new.peer_count = -1;
                conf_new.own_peer_number = -1;
                conf_new.privacy_state = privacy_state;
                conf_new.group_active = false;
                conf_new.tox_group_number = group_num;
                conf_new.name = get_group_display_name(group_identifier, group_topic);
                //
                orma.insertIntoGroupDB(conf_new);
                Log.i(TAG, "new_or_updated_group:+ADD+");

                try
                {
                    CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
                    cc.is_friend = COMBINED_IS_GROUP;
                    cc.group_item = (GroupDB) GroupDB.deep_copy(conf_new);
                    Log.i(TAG, "new_or_updated_group:EE4__:" + MainActivity.friend_list_fragment + " " + cc);
                    MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
                    //!! if we are coming from another activity the friend_list_fragment might not be initialized yet!!
                }
                catch (Exception e4)
                {
                    e4.printStackTrace();
                    // Log.i(TAG, "new_or_updated_group:EE4:" + e4.getMessage());
                }

                return;
            }
            catch (Exception e1)
            {
                e1.printStackTrace();
                Log.i(TAG, "new_or_updated_group:EE2:" + e1.getMessage());
            }
        }
    }

    static void update_group_in_friendlist(long group_num)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_num);
            Log.i(TAG, "new_or_updated_group:*update*");
            final GroupDB conf3 = (GroupDB) orma.selectFromGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList().get(0);
            // update in "friendlist"
            CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
            cc.is_friend = COMBINED_IS_GROUP;
            cc.group_item = (GroupDB) GroupDB.deep_copy(conf3);
            MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
        }
        catch (Exception e3)
        {
            Log.i(TAG, "update_group_in_friendlist:EE3:" + e3.getMessage());
        }
    }

    public static long tox_group_by_groupid__wrapper(@NonNull String group_id_string)
    {
        ByteBuffer group_id_buffer = ByteBuffer.allocateDirect(GROUP_ID_LENGTH);
        byte[] data = HelperGeneric.hex_to_bytes(group_id_string.toUpperCase());
        group_id_buffer.put(data);
        group_id_buffer.rewind();

        long res = tox_group_by_chat_id(group_id_buffer);
        if (res == UINT32_MAX_JAVA)
        {
            return -1;
        }
        else if (res < 0)
        {
            return -1;
        }
        else
        {
            return res;
        }
    }

    public static String tox_group_by_groupnum__wrapper(long groupnum)
    {
        try
        {
            ByteBuffer groupid_buf = ByteBuffer.allocateDirect(GROUP_ID_LENGTH * 2);
            if (tox_group_get_chat_id(groupnum, groupid_buf) == 0)
            {
                byte[] groupid_buffer = new byte[GROUP_ID_LENGTH];
                groupid_buf.get(groupid_buffer, 0, GROUP_ID_LENGTH);
                return bytes_to_hex(groupid_buffer);
            }
            else
            {
                return null;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    static long insert_into_group_message_db(final GroupMessage m, final boolean update_group_view_flag)
    {
        long row_id = orma.insertIntoGroupMessage(m);

        try
        {
            if ((row_id != -1) && (update_group_view_flag))
            {
                if ((PREF__conference_show_system_messages == false) &&
                    (m.tox_group_peer_pubkey.equals(TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY)))
                {
                    // HINT: dont show system message because of user PREF
                }
                else
                {
                    add_single_group_message_from_messge_id(row_id, true);
                }
            }

            return row_id;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return row_id;
        }
    }

    public static void add_single_group_message_from_messge_id(final long message_id, final boolean force)
    {
        try
        {
            Thread t = new Thread()
            {
                @Override
                public void run()
                {
                    if (message_id != -1)
                    {
                        try
                        {
                            GroupMessage m = (GroupMessage) orma.selectFromGroupMessage().idEq(message_id).orderByIdDesc().get(0);

                            if (m.id != -1)
                            {
                                if ((force) || (MainActivity.update_all_messages_global_timestamp +
                                                MainActivity.UPDATE_MESSAGES_NORMAL_MILLIS <
                                                System.currentTimeMillis()))
                                {
                                    if (MainActivity.group_message_list_fragment == null)
                                    {
                                        long loop = 0;
                                        while (loop < 40)
                                        {
                                            loop++;
                                            try
                                            {
                                                Thread.sleep(200);
                                            }
                                            catch (InterruptedException e)
                                            {
                                                e.printStackTrace();
                                            }

                                            if (MainActivity.group_message_list_fragment != null)
                                            {
                                                break;
                                            }
                                        }
                                    }

                                    if (MainActivity.group_message_list_fragment != null)
                                    {
                                        if (MainActivity.group_message_list_fragment.current_group_id.toLowerCase().equals(
                                                m.group_identifier.toLowerCase()))
                                        {
                                            MainActivity.update_all_messages_global_timestamp = System.currentTimeMillis();
                                            MainActivity.group_message_list_fragment.add_message(m);
                                        }
                                    }
                                }
                            }
                        }
                        catch (Exception e2)
                        {
                        }
                    }
                }
            };
            t.start();
        }
        catch (Exception e)
        {
            // e.printStackTrace();
        }
    }

    public static String tox_group_peer_get_name__wrapper(String group_identifier, String group_peer_pubkey)
    {
        String default_name = "";

        if ((group_peer_pubkey == null) || (group_peer_pubkey.trim().isEmpty()))
        {
            return HelperFriend.resolve_name_for_pubkey(group_peer_pubkey, default_name);
        }

        try
        {
            final String res = tox_group_peer_get_name(tox_group_by_groupid__wrapper(group_identifier),
                    get_group_peernum_from_peer_pubkey(group_identifier, group_peer_pubkey));
            if ((res != null) && (res.length() > 0))
            {
                default_name = res;
            }
        }
        catch (Exception ignored)
        {
        }

        if (default_name.isEmpty())
        {
            final GroupPeerDB peer_from_db = lookup_group_peer_by_pubkey(group_identifier, group_peer_pubkey);
            if ((peer_from_db != null) && (peer_from_db.peer_name != null) && (peer_from_db.peer_name.length() > 0))
            {
                default_name = peer_from_db.peer_name;
            }
        }

        return HelperFriend.resolve_name_for_pubkey(group_peer_pubkey, default_name);
    }

    static GroupPeerDB lookup_group_peer_by_pubkey(final String group_identifier, final String peer_pubkey)
    {
        if ((group_identifier == null) || (peer_pubkey == null) || (peer_pubkey.trim().isEmpty()) || (orma == null))
        {
            return null;
        }

        final String trimmed = peer_pubkey.trim();
        final String[] variants = new String[]{
                trimmed,
                trimmed.toUpperCase(java.util.Locale.ROOT),
                trimmed.toLowerCase(java.util.Locale.ROOT),
        };

        for (final String variant : variants)
        {
            try
            {
                final List<GroupPeerDB> peers = orma.selectFromGroupPeerDB().
                        group_identifierEq(group_identifier).
                        tox_group_peer_pubkeyEq(variant).toList();
                if ((peers != null) && (!peers.isEmpty()))
                {
                    return peers.get(0);
                }
            }
            catch (Exception ignored)
            {
            }
        }

        return null;
    }

    /*
   this is a bit costly, asking for pubkeys of all group peers
   */
    static long get_group_peernum_from_peer_pubkey(final String group_identifier, final String peer_pubkey)
    {
        try
        {
            long group_num = tox_group_by_groupid__wrapper(group_identifier);
            long num_peers = MainActivity.tox_group_peer_count(group_num);

            if (num_peers > 0)
            {
                long[] peers = tox_group_get_peerlist(group_num);
                if (peers != null)
                {
                    long i = 0;
                    for (i = 0; i < num_peers; i++)
                    {
                        try
                        {
                            String pubkey_try = tox_group_peer_get_public_key(group_num, peers[(int) i]);
                            if ((pubkey_try != null) && (pubkey_try.equalsIgnoreCase(peer_pubkey)))
                            {
                                // we found the peer number
                                return peers[(int) i];
                            }
                        }
                        catch (Exception e)
                        {
                        }
                    }
                }
            }
            return -2;
        }
        catch (Exception e)
        {
            return -2;
        }
    }


    public static String tox_group_peer_get_public_key__wrapper(long group_num, long peer_number)
    {
        String result = null;
        try
        {
            result = MainActivity.tox_group_peer_get_public_key(group_num, peer_number);
        }
        catch (Exception ignored)
        {
        }
        return result;
    }

    static boolean is_group_we_left(String group_identifier)
    {
        try
        {
            return (orma.selectFromGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    toList().get(0).group_we_left);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return false;
        }
    }

    static void set_group_group_we_left(String group_identifier)
    {
        try
        {
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    group_we_left(true).
                    execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "set_group_group_we_left:EE:" + e.getMessage());
        }
    }

    static void clear_group_group_we_left(String group_identifier)
    {
        try
        {
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    group_we_left(false).
                    execute();
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            // Log.i(TAG, "clear_group_group_we_left:EE:" + e.getMessage());
        }
    }

    static boolean is_group_active(String group_identifier)
    {
        try
        {
            return (orma.selectFromGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    toList().get(0).group_active);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return false;
        }
    }

    static void set_group_active(String group_identifier)
    {
        try
        {
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    group_active(true).
                    execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "set_group_active:EE:" + e.getMessage());
        }
    }

    static void set_group_inactive(String group_identifier)
    {
        try
        {
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    group_active(false).
                    execute();
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            // Log.i(TAG, "set_group_inactive:EE:" + e.getMessage());
        }
    }

    static int get_display_peer_limit(final long group_num)
    {
        if (group_num < 0)
        {
            return DEFAULT_GROUP_PEER_LIMIT;
        }
        try
        {
            final int limit = tox_group_get_peer_limit(group_num);
            if (limit < 1)
            {
                return DEFAULT_GROUP_PEER_LIMIT;
            }
            return limit;
        }
        catch (Exception e)
        {
            return DEFAULT_GROUP_PEER_LIMIT;
        }
    }

    static long ensure_group_in_tox(@NonNull String group_identifier)
    {
        long group_num = tox_group_by_groupid__wrapper(group_identifier);
        if (group_num >= 0)
        {
            return group_num;
        }

        if (is_group_we_left(group_identifier) || !is_tox_started)
        {
            return -1;
        }

        try
        {
            ByteBuffer join_chat_id_buffer = ByteBuffer.allocateDirect(TOX_GROUP_CHAT_ID_SIZE);
            byte[] data_join = HelperGeneric.hex_to_bytes(group_identifier.toUpperCase());
            if (data_join == null || data_join.length != TOX_GROUP_CHAT_ID_SIZE)
            {
                return -1;
            }
            join_chat_id_buffer.put(data_join);
            join_chat_id_buffer.rewind();

            long new_group_num = MainActivity.tox_group_join(join_chat_id_buffer, TOX_GROUP_CHAT_ID_SIZE,
                                                             get_group_peer_join_name(), null);
            Log.i(TAG, "ensure_group_in_tox:join=" + new_group_num + " id=" + group_identifier);

            if (new_group_num >= 0 && new_group_num < UINT32_MAX_JAVA)
            {
                try
                {
                    final GroupDB g = (GroupDB) orma.selectFromGroupDB().
                            group_identifierEq(group_identifier.toLowerCase()).get(0);
                    new_or_updated_group(new_group_num, g.who_invited__tox_public_key_string, group_identifier,
                                         g.privacy_state);
                }
                catch (Exception e)
                {
                    new_or_updated_group(new_group_num, "", group_identifier,
                                         ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value);
                }
                set_group_active(group_identifier);
                update_savedata_file_wrapper();
                return new_group_num;
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "ensure_group_in_tox:EE:" + e.getMessage());
        }
        return -1;
    }

    @Nullable
    static String group_send_precheck_failure_reason(@NonNull String group_identifier)
    {
        if (is_group_we_left(group_identifier))
        {
            return context_s.getString(R.string.group_send_left_group);
        }

        final long group_num = ensure_group_in_tox(group_identifier);
        if (group_num < 0)
        {
            return context_s.getString(R.string.group_send_group_not_found);
        }

        if (tox_group_is_connected(group_num) !=
            TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            return context_s.getString(R.string.group_send_not_connected);
        }

        int role = tox_group_self_get_role(group_num);
        if (role < 0)
        {
            role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
        }

        if (role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value
                && !is_khandaq_community_group(group_identifier))
        {
            return context_s.getString(R.string.group_send_observer_role);
        }

        return null;
    }

    static String group_invite_failure_message(final int res)
    {
        if (res >= 0)
        {
            return context_s.getString(R.string.group_invite_friend_failed);
        }

        if (res == -99)
        {
            return context_s.getString(R.string.add_group_failed_not_ready);
        }

        final int errCode = -res;

        if (errCode == TOX_ERR_GROUP_INVITE_FRIEND.TOX_ERR_GROUP_INVITE_FRIEND_GROUP_NOT_FOUND.ordinal())
        {
            return context_s.getString(R.string.group_send_group_not_found);
        }
        if (errCode == TOX_ERR_GROUP_INVITE_FRIEND.TOX_ERR_GROUP_INVITE_FRIEND_FRIEND_NOT_FOUND.ordinal())
        {
            return context_s.getString(R.string.group_invite_friend_not_found);
        }
        if (errCode == TOX_ERR_GROUP_INVITE_FRIEND.TOX_ERR_GROUP_INVITE_FRIEND_DISCONNECTED.ordinal())
        {
            return context_s.getString(R.string.group_invite_friend_disconnected);
        }
        if (errCode == TOX_ERR_GROUP_INVITE_FRIEND.TOX_ERR_GROUP_INVITE_FRIEND_FAIL_SEND.ordinal())
        {
            return context_s.getString(R.string.group_invite_friend_fail_send);
        }
        if (errCode == TOX_ERR_GROUP_INVITE_FRIEND.TOX_ERR_GROUP_INVITE_FRIEND_INVITE_FAIL.ordinal())
        {
            return context_s.getString(R.string.group_invite_friend_invite_fail);
        }

        Log.i(TAG, "group_invite_failure_message:errCode=" + errCode);
        return context_s.getString(R.string.group_invite_friend_failed);
    }

    static boolean invite_friend_to_group(@NonNull String group_identifier, @NonNull String friend_public_key)
    {
        final String precheck = group_send_precheck_failure_reason(group_identifier);
        if (precheck != null)
        {
            display_toast(precheck, false, 300);
            return false;
        }

        final long friend_num = tox_friend_by_public_key__wrapper(friend_public_key);
        if ((friend_num < 0) || (friend_num >= UINT32_MAX_JAVA))
        {
            display_toast(context_s.getString(R.string.group_invite_friend_not_found), false, 300);
            return false;
        }

        final long group_num = ensure_group_in_tox(group_identifier);
        if (group_num < 0)
        {
            display_toast(context_s.getString(R.string.group_send_group_not_found), false, 300);
            return false;
        }

        final int res = tox_group_invite_friend(group_num, friend_num);
        Log.i(TAG, "invite_friend_to_group:group=" + group_identifier + " friend=" + friend_public_key + " res=" + res);

        if (res == 1)
        {
            update_savedata_file_wrapper();
            display_toast(context_s.getString(R.string.group_invite_friend_success), false, 300);
            return true;
        }

        display_toast(group_invite_failure_message(res), false, 300);
        return false;
    }

    static String group_send_failure_reason(final long message_id)
    {
        if (message_id == -1)
        {
            return context_s.getString(R.string.group_send_group_not_found);
        }
        else if (message_id == -2)
        {
            return context_s.getString(R.string.group_send_too_long);
        }
        else if (message_id == -3)
        {
            return context_s.getString(R.string.group_send_empty);
        }
        else if (message_id == -4)
        {
            return context_s.getString(R.string.group_send_bad_type);
        }

        return context_s.getString(R.string.group_send_failed);
    }

    static String group_identifier_short(String group_identifier, boolean uppercase_result)
    {
        try
        {
            if (uppercase_result)
            {
                return (group_identifier.substring(group_identifier.length() - 6,
                                                   group_identifier.length())).toUpperCase(Locale.ENGLISH);
            }
            else
            {
                return group_identifier.substring(group_identifier.length() - 6, group_identifier.length());
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return group_identifier;
        }
    }

    static boolean is_khandaq_community_group(final String group_identifier)
    {
        if (group_identifier == null)
        {
            return false;
        }
        return KHANDAQ_COMMUNITY_GROUPID.equalsIgnoreCase(group_identifier.trim());
    }

    static boolean is_legacy_trifa_community_display_name(final String name)
    {
        if (name == null)
        {
            return false;
        }
        final String normalized = name.trim();
        return "TRIfA Community".equalsIgnoreCase(normalized)
                || "TRIFA Community".equalsIgnoreCase(normalized)
                || "TriFA Community".equalsIgnoreCase(normalized);
    }

    static String get_group_display_name(final String group_identifier, final String raw_name)
    {
        if (is_khandaq_community_group(group_identifier) || is_legacy_trifa_community_display_name(raw_name))
        {
            return KHANDAQ_COMMUNITY_DISPLAY_NAME;
        }
        if (raw_name == null)
        {
            return "";
        }
        return raw_name;
    }

    static boolean is_valid_group_title_string(final String value)
    {
        return (value != null) && (value.length() > 0) && (!value.equals("-1"));
    }

    static String sanitize_group_title(final String raw_title)
    {
        if (raw_title == null)
        {
            return "";
        }

        String cleaned = raw_title.trim().replace("\r", "").replace("\n", "");
        return HelperGeneric.truncate_utf8_to_max_bytes(cleaned, TOX_GROUP_MAX_GROUP_NAME_LENGTH);
    }

    static String sanitize_group_peer_name(final String raw_name)
    {
        if ((raw_name == null) || (raw_name.trim().isEmpty()))
        {
            return "User";
        }

        String cleaned = raw_name.trim().replace("\r", "").replace("\n", "");
        return HelperGeneric.truncate_utf8_to_max_bytes(cleaned, TOX_GROUP_MAX_PART_LENGTH);
    }

    static String group_create_failure_message(final long group_num)
    {
        if (group_num == -99)
        {
            return context_s.getString(R.string.add_group_failed_not_ready);
        }

        if (group_num >= 0)
        {
            return context_s.getString(R.string.add_private_group_failed);
        }

        final int errCode = (int) (-group_num);
        if (errCode == TOX_ERR_GROUP_NEW.TOX_ERR_GROUP_NEW_NAME_TOO_LONG.ordinal())
        {
            return context_s.getString(R.string.add_group_failed_name_too_long);
        }
        if (errCode == TOX_ERR_GROUP_NEW.TOX_ERR_GROUP_NEW_PEER_NAME_TOO_LONG.ordinal())
        {
            return context_s.getString(R.string.add_group_failed_peer_name_too_long);
        }
        if (errCode == TOX_ERR_GROUP_NEW.TOX_ERR_GROUP_NEW_TOO_MANY.ordinal())
        {
            return context_s.getString(R.string.add_group_failed_too_many);
        }
        if (errCode == TOX_ERR_GROUP_NEW.TOX_ERR_GROUP_NEW_INIT.ordinal())
        {
            return context_s.getString(R.string.add_group_failed_not_ready);
        }

        Log.i(TAG, "group_create_failure_message:errCode=" + errCode);
        return context_s.getString(R.string.add_private_group_failed);
    }

    static boolean create_new_group(final int privacy_state, final String raw_group_name,
                                    final int success_string_id, final int failure_string_id,
                                    final String system_message)
    {
        if (!HelperFriend.is_tox_profile_ready())
        {
            display_toast(context_s.getString(R.string.add_group_failed_not_ready), false, 300);
            return false;
        }

        final String group_name = sanitize_group_title(raw_group_name);
        if (group_name.isEmpty())
        {
            display_toast(context_s.getString(R.string.add_group_failed_empty_name), false, 300);
            return false;
        }

        final String peer_name = sanitize_group_peer_name(get_group_peer_join_name());
        Log.i(TAG, "create_new_group:name=" + group_name + " peer=" + peer_name + " privacy=" + privacy_state);

        final long new_group_num = tox_group_new(privacy_state, group_name, peer_name);
        Log.i(TAG, "create_new_group:groupnum=" + new_group_num);

        if ((new_group_num < 0) || (new_group_num >= UINT32_MAX_JAVA))
        {
            display_toast(group_create_failure_message(new_group_num), false, 300);
            return false;
        }

        update_savedata_file_wrapper();

        final ByteBuffer groupid_buf = ByteBuffer.allocateDirect(GROUP_ID_LENGTH * 2);
        if (tox_group_get_chat_id(new_group_num, groupid_buf) != 0)
        {
            Log.i(TAG, "create_new_group:get_chat_id failed groupnum=" + new_group_num);
            display_toast(context_s.getString(failure_string_id), false, 300);
            return false;
        }

        final byte[] groupid_buffer = new byte[GROUP_ID_LENGTH];
        groupid_buf.get(groupid_buffer, 0, GROUP_ID_LENGTH);
        final String group_identifier = bytes_to_hex(groupid_buffer);
        final int resolved_privacy_state = tox_group_get_privacy_state(new_group_num);

        Log.i(TAG, "create_new_group:ok num=" + new_group_num + " id=" + group_identifier);

        add_group_wrapper(0, new_group_num, group_identifier, resolved_privacy_state);
        display_toast(context_s.getString(success_string_id), false, 300);
        add_system_message_to_group_chat(group_identifier, system_message);
        set_group_active(group_identifier);

        if (have_own_relay())
        {
            invite_to_group_own_relay(new_group_num);
        }

        try
        {
            final GroupDB conf3 = (GroupDB) orma.selectFromGroupDB().group_identifierEq(
                    group_identifier.toLowerCase()).toList().get(0);
            CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
            cc.is_friend = COMBINED_IS_CONFERENCE;
            cc.group_item = (GroupDB) GroupDB.deep_copy(conf3);
            MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
        }
        catch (Exception e)
        {
            Log.i(TAG, "create_new_group:friendlist:EE:" + e.getMessage());
        }

        return true;
    }

    static String get_effective_group_title(final long group_num, final String group_identifier)
    {
        if (group_num >= 0)
        {
            try
            {
                String topic = tox_group_get_topic(group_num);
                if (is_valid_group_title_string(topic))
                {
                    return get_group_display_name(group_identifier, topic);
                }

                String name = tox_group_get_name(group_num);
                if (is_valid_group_title_string(name))
                {
                    return get_group_display_name(group_identifier, name);
                }
            }
            catch (Exception ignored)
            {
            }
        }

        try
        {
            GroupDB g = (GroupDB) orma.selectFromGroupDB().group_identifierEq(group_identifier.toLowerCase()).get(0);
            return get_group_display_name(group_identifier, g.name);
        }
        catch (Exception ignored)
        {
        }

        return get_group_display_name(group_identifier, "");
    }

    static boolean save_group_title_if_changed(final String group_identifier, final String raw_title)
    {
        final String new_title = sanitize_group_title(raw_title);
        if (new_title.length() < 1)
        {
            return false;
        }

        final long group_num = tox_group_by_groupid__wrapper(group_identifier);
        if (group_num < 0)
        {
            return false;
        }

        final String current_title = get_effective_group_title(group_num, group_identifier);
        if (new_title.equals(current_title))
        {
            return true;
        }

        final int res = tox_group_set_topic(group_num, new_title);
        if (res == 1)
        {
            update_group_in_db_name(group_identifier, new_title);
            update_group_in_db_topic(group_identifier, new_title);
            update_group_in_friendlist(group_identifier);
            update_group_in_groupmessagelist(group_identifier);
            update_savedata_file_wrapper();
            return true;
        }

        Log.i(TAG, "save_group_title_if_changed:failed res=" + res + " group=" + group_identifier);
        return false;
    }

    static void khandaq_community_auto_promote_peer(final long group_number, final long peer_id)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (!is_khandaq_community_group(group_identifier))
            {
                return;
            }

            final int self_role = tox_group_self_get_role(group_number);
            if (self_role < 0
                    || self_role > ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value)
            {
                return;
            }

            final int peer_role = tox_group_peer_get_role(group_number, peer_id);
            if (peer_role != ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value)
            {
                return;
            }

            final int result = tox_group_mod_set_role(group_number, peer_id,
                    ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value);
            Log.i(TAG, "khandaq_community_auto_promote_peer:peer=" + peer_id + " result=" + result);

            if (result == 1)
            {
                final String group_peer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);
                update_group_peer_in_db(group_number, group_identifier, peer_id, group_peer_pubkey,
                        ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value);
                update_group_messages_peer_role(group_identifier, group_peer_pubkey,
                        ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value);
            }

            update_savedata_file_wrapper();
        }
        catch (Exception e)
        {
            Log.i(TAG, "khandaq_community_auto_promote_peer:EE:" + e.getMessage());
        }
    }

    static void khandaq_community_promote_all_observers_if_moderator(final long group_number)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (!is_khandaq_community_group(group_identifier))
            {
                return;
            }

            final int self_role = tox_group_self_get_role(group_number);
            if (self_role < 0
                    || self_role > ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value)
            {
                return;
            }

            final long num_peers = tox_group_peer_count(group_number);
            if (num_peers <= 0)
            {
                return;
            }

            final long[] peers = tox_group_get_peerlist(group_number);
            if (peers == null)
            {
                return;
            }

            for (int i = 0; i < num_peers; i++)
            {
                khandaq_community_auto_promote_peer(group_number, peers[i]);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "khandaq_community_promote_all_observers_if_moderator:EE:" + e.getMessage());
        }
    }

    static void khandaq_community_on_connected(final long group_number)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (!is_khandaq_community_group(group_identifier))
            {
                return;
            }

            khandaq_community_promote_all_observers_if_moderator(group_number);
            request_khandaq_community_history_from_peers(group_identifier);
        }
        catch (Exception e)
        {
            Log.i(TAG, "khandaq_community_on_connected:EE:" + e.getMessage());
        }
    }

    static void request_khandaq_community_history_from_peers(final String group_identifier)
    {
        if (!is_khandaq_community_group(group_identifier))
        {
            return;
        }

        final Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    final long group_num = tox_group_by_groupid__wrapper(group_identifier);
                    if (group_num < 0)
                    {
                        return;
                    }

                    if (tox_group_is_connected(group_num) !=
                        TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
                    {
                        return;
                    }

                    final long num_peers = tox_group_peer_count(group_num);
                    if (num_peers <= 0)
                    {
                        return;
                    }

                    final long[] peers = tox_group_get_peerlist(group_num);
                    if (peers == null)
                    {
                        return;
                    }

                    final long self_peer_id = tox_group_self_get_peer_id(group_num);
                    for (int i = 0; i < num_peers; i++)
                    {
                        if (peers[i] == self_peer_id)
                        {
                            continue;
                        }

                        final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_num, peers[i]);
                        if (peer_pubkey == null)
                        {
                            continue;
                        }

                        send_ngch_request(group_identifier, peer_pubkey);
                    }
                }
                catch (Exception e)
                {
                    Log.i(TAG, "request_khandaq_community_history_from_peers:EE:" + e.getMessage());
                }
            }
        };
        t.start();
    }

    static void migrate_khandaq_community_display_names()
    {
        try
        {
            orma.updateGroupDB().
                    group_identifierEq(KHANDAQ_COMMUNITY_GROUPID.toLowerCase()).
                    name(KHANDAQ_COMMUNITY_DISPLAY_NAME).
                    execute();
        }
        catch (Exception ignored)
        {
        }

        try
        {
            final List<GroupDB> groups = orma.selectFromGroupDB().toList();
            for (GroupDB g : groups)
            {
                if (is_khandaq_community_group(g.group_identifier)
                        || is_legacy_trifa_community_display_name(g.name))
                {
                    orma.updateGroupDB().
                            group_identifierEq(g.group_identifier.toLowerCase()).
                            name(KHANDAQ_COMMUNITY_DISPLAY_NAME).
                            execute();
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void update_group_in_db_name(final String group_identifier, final String name)
    {
        try
        {
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    name(get_group_display_name(group_identifier, name)).
                    execute();
        }
        catch (Exception ignored)
        {
        }
    }

    static void update_group_in_db_topic(final String group_identifier, final String topic)
    {
        try
        {
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    topic(topic).
                    execute();
        }
        catch (Exception ignored)
        {
        }
    }

    static void update_group_in_db_privacy_state(final String group_identifier, final int a_TOX_GROUP_PRIVACY_STATE)
    {
        try
        {
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    privacy_state(a_TOX_GROUP_PRIVACY_STATE).
                    execute();
        }
        catch (Exception ignored)
        {
        }
    }

    static void delete_group_all_files(final String group_identifier)
    {
        try
        {
            Iterator<com.zoffcc.applications.sorm.GroupMessage> i1 = orma.selectFromGroupMessage().group_identifierEq(group_identifier.toLowerCase()).
                    directionEq(TRIFA_FT_DIRECTION_INCOMING.value).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    toList().iterator();
            selected_group_messages.clear();
            selected_group_messages_text_only.clear();
            selected_group_messages_incoming_file.clear();

            while (i1.hasNext())
            {
                try
                {
                    MainActivity.selected_group_messages.add(i1.next().id);
                    MainActivity.selected_group_messages_incoming_file.add(i1.next().id);
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                }
            }

            HelperConference.delete_selected_group_messages(MainActivity.main_activity_s, false, "deleting Messages ...");
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void delete_group_all_messages(final String group_identifier)
    {
        Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Log.i(TAG, "group_conference_all_messages:del");
                    orma.deleteFromGroupMessage().group_identifierEq(group_identifier.toLowerCase()).execute();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    Log.i(TAG, "group_conference_all_messages:EE:" + e.getMessage());
                }
            }
        };
        t.start();
    }

    static void delete_group(final String group_identifier)
    {
        try
        {
            Log.i(TAG, "delete_group:del");
            orma.deleteFromGroupDB().group_identifierEq(group_identifier.toLowerCase()).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "delete_group:EE:" + e.getMessage());
        }
    }

    static void update_group_in_friendlist(final String group_identifier)
    {
        try
        {
            final GroupDB conf3 = (GroupDB) orma.selectFromGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList().get(0);

            CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
            cc.is_friend = COMBINED_IS_GROUP;
            cc.group_item = (GroupDB) GroupDB.deep_copy(conf3);
            // TODO: sometimes friend_list_fragment == NULL here!
            //       because its not yet resumed yet
            MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
        }
        catch (Exception e1)
        {
            // Log.i(TAG, "update_group_in_friendlist:EE1:" + e1.getMessage());
            // e1.printStackTrace();
        }
    }

    static void update_group_peer_in_db(final long group_number, final String group_identifier,
                                        final long peerid, final String group_peer_pubkey,
                                        int aTox_Group_Role)
    {
        try
        {
            if (group_identifier != null)
            {
                final String peer_name = tox_group_peer_get_name__wrapper(group_identifier, group_peer_pubkey);
                orma.updateGroupPeerDB().group_identifierEq(group_identifier).tox_group_peer_pubkeyEq(group_peer_pubkey).
                        peer_name(peer_name).
                        Tox_Group_Role(aTox_Group_Role).
                        last_update_timestamp(System.currentTimeMillis()).
                        execute();
            }

            // Log.i(TAG, "add_group_peer_to_db:" + orma.selectFromGroupPeerDB().count());
        }
        catch (Exception ignored)
        {
        }
    }

    static void group_notification_silent_peer_set(final String group_identifier, final String group_peer_pubkey, boolean silent)
    {
        try
        {
            if ((group_identifier == null) || (group_peer_pubkey == null))
            {
                return;
            }

            final long now = System.currentTimeMillis();
            final GroupPeerDB existing = lookup_group_peer_by_pubkey(group_identifier, group_peer_pubkey);

            if (existing != null)
            {
                orma.updateGroupPeerDB().group_identifierEq(group_identifier).
                        tox_group_peer_pubkeyEq(existing.tox_group_peer_pubkey).
                        notification_silent(silent).
                        last_update_timestamp(now).
                        execute();
                return;
            }

            final long group_num = tox_group_by_groupid__wrapper(group_identifier);
            int role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value;
            try
            {
                final long peer_num = get_group_peernum_from_peer_pubkey(group_identifier, group_peer_pubkey);
                if ((group_num >= 0) && (peer_num >= 0))
                {
                    role = tox_group_peer_get_role(group_num, peer_num);
                }
            }
            catch (Exception ignored)
            {
            }

            GroupPeerDB p = new GroupPeerDB();
            p.group_identifier = group_identifier;
            p.tox_group_peer_pubkey = group_peer_pubkey;
            p.peer_name = tox_group_peer_get_name__wrapper(group_identifier, group_peer_pubkey);
            p.last_update_timestamp = now;
            p.first_join_timestamp = now;
            p.Tox_Group_Role = role;
            p.notification_silent = silent;

            try
            {
                orma.insertIntoGroupPeerDB(p);
            }
            catch (Exception insert_error)
            {
                orma.updateGroupPeerDB().group_identifierEq(group_identifier).
                        tox_group_peer_pubkeyEq(group_peer_pubkey).
                        notification_silent(silent).
                        last_update_timestamp(now).
                        execute();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static boolean group_notification_silent_peer_get(final String group_identifier, final String group_peer_pubkey)
    {
        final GroupPeerDB peer = lookup_group_peer_by_pubkey(group_identifier, group_peer_pubkey);
        return (peer != null) && peer.notification_silent;
    }

    static void update_group_messages_peer_role(String group_identifier, String group_peer_pubkey, int aTox_Group_Role)
    {
        try
        {
            if ((group_identifier != null) && (group_peer_pubkey != null))
            {
                orma.updateGroupMessage()
                        .group_identifierEq(group_identifier)
                        .tox_group_peer_pubkeyEq(group_peer_pubkey)
                        .tox_group_peer_roleEq(-1)
                        .tox_group_peer_role(aTox_Group_Role);
            }
        }
        catch (Exception e)
        {
        }
    }

    static void add_group_peer_to_db(final long group_number, final String group_identifier,
                                     final long peerid, final String group_peer_pubkey, int aTox_Group_Role)
    {
        try
        {
            if (group_identifier != null)
            {
                GroupPeerDB p = new GroupPeerDB();
                p.group_identifier = group_identifier;
                p.tox_group_peer_pubkey = group_peer_pubkey;
                p.peer_name = tox_group_peer_get_name__wrapper(group_identifier, group_peer_pubkey);
                p.last_update_timestamp = System.currentTimeMillis();
                p.first_join_timestamp = System.currentTimeMillis();
                p.Tox_Group_Role = aTox_Group_Role;
                orma.insertIntoGroupPeerDB(p);
            }
            // Log.i(TAG, "add_group_peer_to_db:" + orma.selectFromGroupPeerDB().count());
        }
        catch (Exception e)
        {
            if (group_identifier != null)
            {
                update_group_peer_in_db(group_number, group_identifier, peerid, group_peer_pubkey, aTox_Group_Role);
            }
        }
    }

    static boolean is_group_muted_or_kicked_peer(final long group_number, final long peerid)
    {
        try
        {
            String group_id = "-1";
            GroupDB group_temp = null;
            try
            {
                group_id = tox_group_by_groupnum__wrapper(group_number);
                group_temp = (GroupDB) orma.selectFromGroupDB().group_identifierEq(group_id.toLowerCase()).toList().get(0);
            }
            catch(Exception ignored)
            {
            }
            if (group_temp == null)
            {
                return true;
            }

            final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peerid);
            if (peer_pubkey == null)
            {
                return true;
            }

            return is_group_muted_or_kicked_peer(group_id, peer_pubkey);
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static boolean is_group_muted_or_kicked_peer(final String group_identifier, final String group_peer_pubkey)
    {
        if (is_khandaq_community_group(group_identifier))
        {
            return false;
        }

        try
        {
            final int peer_role = orma.selectFromGroupPeerDB().group_identifierEq(group_identifier.toLowerCase()).
                    tox_group_peer_pubkeyEq(group_peer_pubkey).toList().get(0).Tox_Group_Role;
            return peer_role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
        }
        catch(Exception e)
        {
            return false;
        }
    }

    static void update_group_in_groupmessagelist(final String group_identifier)
    {
        try
        {
            if (group_message_list_activity != null)
            {
                if (group_identifier != null)
                {
                    if (group_message_list_activity.get_current_group_id().toLowerCase().equals(
                            group_identifier.toLowerCase()))
                    {
                        group_message_list_activity.update_group_all_users();
                    }
                }
            }
        }
        catch (Exception e1)
        {
            Log.i(TAG, "update_group_in_groupmessagelist:EE1:" + e1.getMessage());
            e1.printStackTrace();
        }
    }

    static void add_system_message_to_group_chat(final String group_identifier, final String system_message)
    {
        if (PREF__conference_show_system_messages == false)
        {
            return;
        }

        GroupMessage m = new GroupMessage();
        m.is_new = false;
        m.tox_group_peer_pubkey = TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
        m.direction = 0; // msg received
        m.TOX_MESSAGE_TYPE = 0;
        m.read = false;
        m.tox_group_peername = "System";
        m.private_message = 0;
        m.group_identifier = group_identifier.toLowerCase();
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.rcvd_timestamp = System.currentTimeMillis();
        m.sent_timestamp = System.currentTimeMillis();
        m.text = system_message;
        m.message_id_tox = "";
        m.was_synced = false;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;

        if (group_message_list_activity != null)
        {
            if (group_message_list_activity.get_current_group_id().equals(group_identifier.toLowerCase()))
            {
                HelperGroup.insert_into_group_message_db(m, true);
            }
            else
            {
                HelperGroup.insert_into_group_message_db(m, false);
            }
        }
        else
        {
            long new_msg_id = HelperGroup.insert_into_group_message_db(m, false);
        }
    }

    static void android_tox_callback_group_message_cb_method_wrapper(long group_number, long peer_id, int a_TOX_MESSAGE_TYPE, String message_orig, long length, long message_id, boolean is_private_message)
    {
        // Log.i(TAG, "android_tox_callback_group_message_cb_method_wrapper:gn=" + group_number + " peerid=" + peer_id +
        //           " message=" + message_orig + " is_private_message=" + is_private_message);

        long res = tox_group_self_get_peer_id(group_number);
        if (res == peer_id)
        {
            // HINT: do not add our own messages, they are already in the DB!
            Log.i(TAG, "group_message_cb:gn=" + group_number + " peerid=" + peer_id + " ignoring own message");
            return;
        }

        // TODO: add message ID later --------
        String message_ = "";
        String message_id_ = "";
        message_ = message_orig;
        message_id_ = "";
        // TODO: add message ID later --------

        if (!is_private_message)
        {
            message_id_ = fourbytes_of_long_to_hex(message_id);
        }

        boolean do_notification = true;
        boolean do_badge_update = true;
        String group_id = "-1";
        GroupDB group_temp = null;

        try
        {
            group_id = tox_group_by_groupnum__wrapper(group_number);
            group_temp = (GroupDB) orma.selectFromGroupDB().
                    group_identifierEq(group_id.toLowerCase()).
                    toList().get(0);
        }
        catch (Exception e)
        {
        }

        if (group_id.compareTo("-1") == 0)
        {
            display_toast("ERROR 001 with incoming Group Message!", true, 0);
            return;
        }

        if (group_temp.group_identifier.toLowerCase().compareTo(group_id.toLowerCase()) != 0)
        {
            display_toast("ERROR 002 with incoming Group Message!", true, 0);
            return;
        }

        final String peer_pubkey = HelperGroup.tox_group_peer_get_public_key__wrapper(group_number, peer_id);

        String groupname = null;
        try
        {
            if (group_temp.notification_silent)
            {
                do_notification = false;
            }
            if (group_notification_silent_peer_get(group_id, peer_pubkey))
            {
                do_notification = false;
            }
            groupname = group_temp.name;
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            do_notification = false;
        }




        if (group_message_list_activity != null)
        {
            //Log.i(TAG,
            //      "noti_and_badge:002group:" + group_message_list_activity.get_current_group_id() + ":" + group_id);
            if (group_message_list_activity.get_current_group_id().equals(group_id))
            {
                // no notifcation and no badge update
                do_notification = false;
                do_badge_update = false;
            }
        }

        GroupMessage m = new GroupMessage();
        m.is_new = do_badge_update;
        // m.tox_friendnum = friend_number;
        m.tox_group_peer_pubkey = peer_pubkey;
        m.direction = 0; // msg received
        m.TOX_MESSAGE_TYPE = 0;
        m.read = false;
        m.tox_group_peername = null;
        if (is_private_message)
        {
            m.private_message = 1;
        }
        else
        {
            m.private_message = 0;
        }
        m.group_identifier = group_id.toLowerCase();
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.rcvd_timestamp = System.currentTimeMillis();
        m.sent_timestamp = System.currentTimeMillis();
        m.text = message_;
        m.message_id_tox = message_id_;
        m.was_synced = false;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
        // Log.i(TAG, "message_id_tox=" + message_id_ + " message_id=" + message_id);

        m.tox_group_peer_role = -1;
        try
        {
            int peer_role_get = tox_group_peer_get_role(group_number, peer_id);
            if (peer_role_get >= 0)
            {
                m.tox_group_peer_role = peer_role_get;
            }

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            m.tox_group_peername = HelperGroup.tox_group_peer_get_name__wrapper(m.group_identifier,
                                                                                m.tox_group_peer_pubkey);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (group_message_list_activity != null)
        {
            if (group_message_list_activity.get_current_group_id().equals(group_id.toLowerCase()))
            {
                HelperGroup.insert_into_group_message_db(m, true);
            }
            else
            {
                HelperGroup.insert_into_group_message_db(m, false);
            }
        }
        else
        {
            long new_msg_id = HelperGroup.insert_into_group_message_db(m, false);
            Log.i(TAG, "group_message_cb:new_msg_id=" + new_msg_id);
        }

        HelperFriend.add_all_friends_clear_wrapper(0);

        if (do_notification)
        {
            change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.group_identifier, groupname, m.text);
        }
    }

    static GroupMessage get_last_group_message_in_this_group_within_n_seconds_from_sender_pubkey(
            String group_identifier, String sender_pubkey, long sent_timestamp, String message_id_tox,
            long time_delta_ms, final String message_text)
    {
        try
        {
            if ((message_id_tox == null) || (message_id_tox.length() < 8))
            {
                return null;
            }

            GroupMessage gm = (GroupMessage) orma.selectFromGroupMessage().
                    group_identifierEq(group_identifier.toLowerCase()).
                    tox_group_peer_pubkeyEq(sender_pubkey.toUpperCase()).
                    message_id_toxEq(message_id_tox.toLowerCase()).
                    sent_timestampGt(sent_timestamp - (time_delta_ms * 1000)).
                    textEq(message_text).
                    limit(1).
                    toList().
                    get(0);

            return gm;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static void group_message_add_from_sync(final String group_identifier, final String syncer_pubkey,
                                            long peer_number2, String peer_pubkey, int a_TOX_MESSAGE_TYPE,
                                            String message, long length, long sent_timestamp_in_ms,
                                            String message_id, int sync_type, final String peer_name)
    {
        // Log.i(TAG,
        //       "group_message_add_from_sync:cf_num=" + group_identifier + " pnum=" + peer_number2 + " msg=" + message);

        long group_num_ = tox_group_by_groupid__wrapper(group_identifier);
        int res = -1;
        if (peer_number2 == -1)
        {
            res = -1;
        }
        else
        {
            final long my_peer_num = tox_group_self_get_peer_id(group_num_);
            if (my_peer_num == peer_number2)
            {
                res = 1;
            }
            else
            {
                res = 0;
            }
        }

        if (res == 1)
        {
            // HINT: do not add our own messages, they are already in the DB!
            // Log.i(TAG, "conference_message_add_from_sync:own peer");
            return;
        }

        boolean do_notification = true;
        boolean do_badge_update = true;
        GroupDB group_temp = null;

        try
        {
            // TODO: cache me!!
            group_temp = (GroupDB) orma.selectFromGroupDB().
                    group_identifierEq(group_identifier).get(0);
        }
        catch (Exception e)
        {
        }

        if (group_temp == null)
        {
            Log.i(TAG, "group_message_add_from_sync:cf_num=" + group_identifier + " pnum=" + peer_number2 + " msg=" +
                       message + " we dont have the group anymore????");
            return;
        }

        String groupname = null;
        try
        {
            if (group_temp.notification_silent)
            {
                do_notification = false;
            }
            if (group_notification_silent_peer_get(group_identifier, peer_pubkey))
            {
                do_notification = false;
            }
            groupname = group_temp.name;
        }
        catch (Exception e)
        {
        }

        if (group_message_list_activity != null)
        {
            // Log.i(TAG, "conference_message_add_from_sync:noti_and_badge:002conf:" +
            //            conference_message_list_activity.get_current_conf_id() + ":" + conf_id);

            if (group_message_list_activity.get_current_group_id().equals(group_identifier))
            {
                // Log.i(TAG, "noti_and_badge:003:");
                // no notifcation and no badge update
                do_notification = false;
                do_badge_update = false;
            }
        }

        GroupMessage m = new GroupMessage();
        m.is_new = do_badge_update;
        m.tox_group_peer_pubkey = peer_pubkey;
        m.direction = 0; // msg received
        m.TOX_MESSAGE_TYPE = 0;
        m.read = false;
        m.tox_group_peername = peer_name;
        m.group_identifier = group_identifier;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.sent_timestamp = sent_timestamp_in_ms;
        m.rcvd_timestamp = System.currentTimeMillis();
        m.text = message;
        m.message_id_tox = message_id;
        m.was_synced = true;
        m.TRIFA_SYNC_TYPE = sync_type;
        Log.i(TAG, "add TRIFA_SYNC_TYPE=" + sync_type + " syncer_pubkey_01:" + syncer_pubkey);
        m.tox_group_peer_pubkey_syncer_01 = syncer_pubkey;
        m.tox_group_peer_pubkey_syncer_01_sent_timestamp = sent_timestamp_in_ms;

        m.tox_group_peer_role = -1;
        try
        {
            int peer_role_get = tox_group_peer_get_role(tox_group_by_groupid__wrapper(group_identifier),
                                                        get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey));
            if (peer_role_get >= 0)
            {
                m.tox_group_peer_role = peer_role_get;
            }

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }


        if (m.tox_group_peername == null)
        {
            try
            {
                m.tox_group_peername = tox_group_peer_get_name__wrapper(m.group_identifier, m.tox_group_peer_pubkey);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        if (group_message_list_activity != null)
        {
            if (group_message_list_activity.get_current_group_id().equals(group_identifier))
            {
                insert_into_group_message_db(m, true);
            }
            else
            {
                insert_into_group_message_db(m, false);
            }
        }
        else
        {
            long new_msg_id = insert_into_group_message_db(m, false);
            // Log.i(TAG, "conference_message_add_from_sync:new_msg_id=" + new_msg_id);
        }

        HelperFriend.add_all_friends_clear_wrapper(0);

        if (do_notification)
        {
            change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.group_identifier, groupname, m.text);
        }
    }

    static void send_group_image(final GroupMessage g)
    {
        // @formatter:off
        /*
           40000 max bytes length for custom lossless NGC packets.
           37000 max bytes length for file and header, to leave some space for offline message syncing.

        | what      | Length in bytes| Contents                                           |
        |------     |--------        |------------------                                  |
        | magic     |       6        |  0x667788113435                                    |
        | version   |       1        |  0x01                                              |
        | pkt id    |       1        |  0x11                                              |
        | msg id    |      32        | *uint8_t  to uniquely identify the message         |
        | create ts |       4        |  uint32_t unixtimestamp in UTC of local wall clock |
        | filename  |     255        |  len TOX_MAX_FILENAME_LENGTH                       |
        |           |                |      data first, then pad with NULL bytes          |
        | data      |[1, 36701]      |  bytes of file data, zero length files not allowed!|


        header size: 299 bytes
        data   size: 1 - 36701 bytes
         */
        // @formatter:on

        final long header = 6 + 1 + 1 + 32 + 4 + 255;
        long data_length = header + g.filesize;

        if ((data_length > TOX_MAX_NGC_FILE_AND_HEADER_SIZE) || (data_length < (header + 1)))
        {
            Log.i(TAG, "send_group_image: data length has wrong size: " + data_length);
            display_toast("Group file send failed", true, 100);
            return;
        }

        ByteBuffer data_buf = ByteBuffer.allocateDirect((int)data_length);

        data_buf.rewind();
        //
        data_buf.put((byte)0x66);
        data_buf.put((byte)0x77);
        data_buf.put((byte)0x88);
        data_buf.put((byte)0x11);
        data_buf.put((byte)0x34);
        data_buf.put((byte)0x35);
        //
        data_buf.put((byte)0x01);
        //
        data_buf.put((byte)0x11);
        //
        try
        {
            data_buf.put(HelperGeneric.hex_to_bytes(g.msg_id_hash), 0, 32);
        }
        catch(Exception e)
        {
            for(int jj=0;jj<32;jj++)
            {
                data_buf.put((byte)0x0);
            }
        }
        //
        // TODO: write actual timestamp into buffer
        data_buf.put((byte)0x0);
        data_buf.put((byte)0x0);
        data_buf.put((byte)0x0);
        data_buf.put((byte)0x0);
        //
        byte[] fn = "image.jpg".getBytes(StandardCharsets.UTF_8);
        try
        {
            if (g.file_name.getBytes(StandardCharsets.UTF_8).length <= 255)
            {
                fn = g.file_name.getBytes(StandardCharsets.UTF_8);
            }
        }
        catch(Exception ignored)
        {
        }
        data_buf.put(fn);
        for (int k=0;k<(255 - fn.length);k++)
        {
            // fill with null bytes up to 255 for the filename
            data_buf.put((byte) 0x0);
        }




        // -- now fill the data from file --
        java.io.File img_file = new java.io.File(g.filename_fullpath);




        long length_sum = 0;
        java.io.FileInputStream is = null;
        try
        {
            is = new java.io.FileInputStream(img_file);
            byte[] buffer = new byte[1024];
            int length;

            while ((length = is.read(buffer)) > 0)
            {
                data_buf.put(buffer, 0, length);
                length_sum = length_sum + length;
                Log.i(TAG,"put " + length + " bytes into buffer");
            }
        }
        catch(Exception e)
        {
        }
        finally
        {
            try
            {
                is.close();
            }
            catch(Exception e2)
            {
            }
        }
        Log.i(TAG,"put " + length_sum + " bytes TOTAL into buffer, and should match " + g.filesize);
        // -- now fill the data from file --

        byte[] data = new byte[(int)data_length];
        data_buf.rewind();
        data_buf.get(data);
        tox_group_send_custom_packet(tox_group_by_groupid__wrapper(g.group_identifier),
                                     1,
                                     data,
                                     (int)data_length);
    }

    static boolean is_probable_video_file(final String filename, final String mime)
    {
        if ((mime != null) && (mime.startsWith("video/")))
        {
            return true;
        }

        if (filename == null)
        {
            return false;
        }

        final String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".webm") ||
               lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".m4v") ||
               lower.endsWith(".avi");
    }

    static boolean shrink_group_outgoing_file(final Context c, final Uri uri, final String filename,
                                              final MessageListActivity.outgoing_file_wrapped ofw)
    {
        String mime = null;
        try
        {
            if ((c != null) && (uri != null))
            {
                mime = c.getContentResolver().getType(uri);
            }
        }
        catch (Exception ignored)
        {
        }

        if (is_probable_video_file(filename, mime))
        {
            return shrink_video_file_for_ngc(ofw);
        }

        shrink_image_file(c, ofw);
        try
        {
            final java.io.File ff1 = new java.io.File(ofw.filepath_wrapped + "/" + ofw.filename_wrapped);
            return ff1.length() <= TOX_MAX_NGC_FILESIZE;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private static int find_track_index(final MediaExtractor extractor, final String prefix)
    {
        for (int i = 0; i < extractor.getTrackCount(); i++)
        {
            final MediaFormat format = extractor.getTrackFormat(i);
            final String mime = format.getString(MediaFormat.KEY_MIME);
            if ((mime != null) && (mime.startsWith(prefix)))
            {
                return i;
            }
        }
        return -1;
    }

    private static void copy_track_samples(final MediaExtractor extractor, final MediaMuxer muxer,
                                           final int muxer_track_index, final long max_pts_us)
    {
        final ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
        final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        while (true)
        {
            final int sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0)
            {
                break;
            }

            final long sampleTime = extractor.getSampleTime();
            if (sampleTime > max_pts_us)
            {
                break;
            }

            bufferInfo.offset = 0;
            bufferInfo.size = sampleSize;
            bufferInfo.presentationTimeUs = sampleTime;
            bufferInfo.flags = extractor.getSampleFlags();
            muxer.writeSampleData(muxer_track_index, buffer, bufferInfo);
            extractor.advance();
        }
    }

    private static boolean trim_video_to_duration(final File src, final File dst, final long max_duration_ms,
                                                  final boolean include_audio)
    {
        if (dst.exists())
        {
            dst.delete();
        }

        MediaExtractor extractor = null;
        MediaMuxer muxer = null;
        try
        {
            extractor = new MediaExtractor();
            extractor.setDataSource(src.getAbsolutePath());

            final int video_track = find_track_index(extractor, "video/");
            if (video_track < 0)
            {
                return false;
            }

            int audio_track = -1;
            if (include_audio)
            {
                audio_track = find_track_index(extractor, "audio/");
            }

            muxer = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            extractor.selectTrack(video_track);
            final int out_video = muxer.addTrack(extractor.getTrackFormat(video_track));
            extractor.unselectTrack(video_track);

            int out_audio = -1;
            if (audio_track >= 0)
            {
                extractor.selectTrack(audio_track);
                out_audio = muxer.addTrack(extractor.getTrackFormat(audio_track));
                extractor.unselectTrack(audio_track);
            }

            muxer.start();
            final long max_pts_us = max_duration_ms * 1000L;

            extractor.selectTrack(video_track);
            copy_track_samples(extractor, muxer, out_video, max_pts_us);
            extractor.unselectTrack(video_track);

            if (out_audio >= 0)
            {
                extractor.selectTrack(audio_track);
                copy_track_samples(extractor, muxer, out_audio, max_pts_us);
                extractor.unselectTrack(audio_track);
            }

            muxer.stop();
            return dst.exists() && (dst.length() > 0);
        }
        catch (Exception e)
        {
            Log.i(TAG, "trim_video_to_duration:EE:" + e.getMessage());
            return false;
        }
        finally
        {
            if (muxer != null)
            {
                try
                {
                    muxer.release();
                }
                catch (Exception ignored)
                {
                }
            }
            if (extractor != null)
            {
                extractor.release();
            }
            if (dst.exists() && (dst.length() < 1))
            {
                dst.delete();
            }
        }
    }

    static boolean shrink_video_file_for_ngc(final MessageListActivity.outgoing_file_wrapped ofw)
    {
        try
        {
            final java.io.File src = new java.io.File(ofw.filepath_wrapped + "/" + ofw.filename_wrapped);
            Log.i(TAG, "shrink_video_file_for_ngc:fsize_before=" + src.length());

            if (src.length() <= TOX_MAX_NGC_FILESIZE)
            {
                return true;
            }

            final long[] durations_ms = new long[]{5000, 3000, 2000, 1000, 700, 500, 300, 200, 100};
            final java.io.File tmp = new java.io.File(ofw.filepath_wrapped,
                    "ngc_vid_" + System.currentTimeMillis() + ".mp4");

            for (final long duration_ms : durations_ms)
            {
                if (trim_video_to_duration(src, tmp, duration_ms, false))
                {
                    if ((tmp.length() > 0) && (tmp.length() <= TOX_MAX_NGC_FILESIZE))
                    {
                        io_file_copy(tmp, src);
                        tmp.delete();
                        Log.i(TAG, "shrink_video_file_for_ngc:fsize_after=" + src.length());
                        return true;
                    }
                }
                if (tmp.exists())
                {
                    tmp.delete();
                }
            }

            Log.i(TAG, "shrink_video_file_for_ngc:failed");
            return false;
        }
        catch (Exception e)
        {
            Log.i(TAG, "shrink_video_file_for_ngc:EE:" + e.getMessage());
            return false;
        }
    }

    static void shrink_image_file(Context c, MessageListActivity.outgoing_file_wrapped ofw)
    {
        try
        {
            java.io.File ff1 = new java.io.File(ofw.filepath_wrapped + "/" + ofw.filename_wrapped);
            Log.i(TAG, "shrink_image_file:fsize_before=" + ff1.length());

            long new_len = ff1.length();
            int max_width = 800;
            java.io.File ff2 = null;

            final int[] qualityies = new int[]{70, 50, 30, 10, 4, 2, 1, 0};
            int count = 0;
            int quality = qualityies[count];

            while (new_len > TOX_MAX_NGC_FILESIZE)
            {
                if (quality == 0)
                {
                    // @formatter:off
                    ff2 = new Compressor(c).
                            setMaxWidth(max_width).
                            setMaxHeight(max_width).
                            setQuality(quality).
                            setCompressFormat(Bitmap.CompressFormat.PNG).
                            compressToFile(ff1);
                    // @formatter:on
                }
                else
                {
                    // @formatter:off
                    ff2 = new Compressor(c).
                            setMaxWidth(max_width).
                            setQuality(quality).
                            setCompressFormat(Bitmap.CompressFormat.WEBP).
                            compressToFile(ff1);
                    // @formatter:on
                }
                new_len = ff2.length();
                Log.i(TAG, "shrink_image_file:fsize_after=" +
                           new_len + " " + quality + " " + max_width + " " + ff2.getAbsolutePath());
                count++;
                if (count < qualityies.length)
                {
                    quality = qualityies[count];
                    Log.i(TAG, "shrink_image_file:A:count=" + count + " qualityies.length=" + qualityies.length + " quality=" + quality);
                }
                else
                {
                    Log.i(TAG, "shrink_image_file:B:count=" + count + " qualityies.length=" + qualityies.length + " quality=" + quality);
                }

                if (quality > 0)
                {
                    max_width = max_width - 20;
                }
                else
                {
                    max_width = max_width / 2;
                    if (max_width < 30)
                    {
                        max_width = 30;
                    }
                }

                if (max_width <= 30)
                {
                    try
                    {
                        io_file_copy(ff2, ff1);
                        Log.i(TAG, "shrink_image_file:file copied:BREAK");
                    }
                    catch (Exception e)
                    {
                        Log.i(TAG, "shrink_image_file:file copy error:EE003:BREAK" + e.getMessage());
                    }
                    try
                    {
                        ff2.delete();
                        Log.i(TAG, "shrink_image_file:temp file deleted:001:BREAK");
                    }
                    catch (Exception ignored)
                    {
                    }
                    break;
                }

                if (new_len <= TOX_MAX_NGC_FILESIZE)
                {
                    try
                    {
                        io_file_copy(ff2, ff1);
                        Log.i(TAG, "shrink_image_file:file copied");
                    }
                    catch (Exception e)
                    {
                        Log.i(TAG, "shrink_image_file:file copy error:EE003" + e.getMessage());
                    }
                    try
                    {
                        ff2.delete();
                        Log.i(TAG, "shrink_image_file:temp file deleted:001");
                    }
                    catch (Exception ignored)
                    {
                    }
                    break;
                }
                else
                {
                    try
                    {
                        ff2.delete();
                        Log.i(TAG, "shrink_image_file:temp file deleted:002");
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }

            Log.i(TAG, "shrink_image_file:fsize_after:END=" + ff1.length() + " " + ff1.getAbsolutePath());

            if (ff1.length() > TOX_MAX_NGC_FILESIZE)
            {
                display_toast("Group Image shrinking failed", true, 50);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "shrink_image_file:compressToFile:EE003:" + e.getMessage());
            e.printStackTrace();
        }
    }

    static String get_group_peer_join_name()
    {
        String peer_name = "User";

        if ((global_my_name != null) && (global_my_name.trim().length() > 0))
        {
            peer_name = global_my_name.trim();
        }
        else if ((global_my_toxid != null) && (global_my_toxid.length() >= 6))
        {
            peer_name = global_my_toxid.substring(global_my_toxid.length() - 6).toUpperCase(Locale.ENGLISH);
        }

        return sanitize_group_peer_name(peer_name);
    }

    static void do_join_public_group(Intent data)
    {
        try
        {
            String group_id = data.getStringExtra("group_id");
            Log.i(TAG, "join_group:group_id:>" + group_id + "<");

            ByteBuffer join_chat_id_buffer = ByteBuffer.allocateDirect(TOX_GROUP_CHAT_ID_SIZE);
            byte[] data_join = HelperGeneric.hex_to_bytes(group_id.toUpperCase());
            join_chat_id_buffer.put(data_join);
            join_chat_id_buffer.rewind();

            long new_group_num = MainActivity.tox_group_join(join_chat_id_buffer, TOX_GROUP_CHAT_ID_SIZE,
                                                             get_group_peer_join_name(), null);

            Log.i(TAG, "join_group:new groupnum:=" + new_group_num);
            update_savedata_file_wrapper();
            if ((new_group_num >= 0) && (new_group_num < UINT32_MAX_JAVA))
            {
                ByteBuffer groupid_buf = ByteBuffer.allocateDirect(GROUP_ID_LENGTH * 2);
                if (tox_group_get_chat_id(new_group_num, groupid_buf) == 0)
                {
                    byte[] groupid_buffer = new byte[GROUP_ID_LENGTH];
                    groupid_buf.get(groupid_buffer, 0, GROUP_ID_LENGTH);
                    String group_identifier = bytes_to_hex(groupid_buffer);

                    int privacy_state = MainActivity.tox_group_get_privacy_state(new_group_num);

                    Log.i(TAG, "join_group:group num=" + new_group_num + " privacy_state=" + privacy_state +
                                            " group_id=" + group_identifier + " offset=" + groupid_buf.arrayOffset());

                    add_group_wrapper(0, new_group_num, group_identifier, privacy_state);

                    display_toast(MainActivity.context_s.getString(R.string.join_public_group_joined), false, 300);
                    set_group_active(group_identifier);
                    try
                    {
                        final GroupDB conf3 = (GroupDB) orma.selectFromGroupDB().group_identifierEq(
                                group_identifier.toLowerCase()).toList().get(0);
                        CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
                        cc.is_friend = COMBINED_IS_CONFERENCE;
                        cc.group_item = (GroupDB) GroupDB.deep_copy(conf3);
                        MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
                    }
                    catch (Exception e3)
                    {
                        // e3.printStackTrace();
                    }
                }
            }
            else
            {
                display_toast(MainActivity.context_s.getString(R.string.join_public_group_failed), false, 300);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "join_group:EE01:" + e.getMessage());
        }
    }

    static void handle_incoming_group_file(long group_number, long peer_id, byte[] data, long length, long header)
    {
        // HINT: ok we have a group file
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();

        try
        {
            long res = tox_group_self_get_peer_id(group_number);
            if (res == peer_id)
            {
                // HINT: do not add our own messages, they are already in the DB!
                Log.i(TAG, "group_custom_packet_cb:gn=" + group_number + " peerid=" + peer_id + " ignoring own file");
                return;
            }

            boolean do_notification = true;
            boolean do_badge_update = true;
            String group_id = "-1";
            GroupDB group_temp = null;

            try
            {
                group_id = tox_group_by_groupnum__wrapper(group_number);
                group_temp = (GroupDB) orma.selectFromGroupDB().
                        group_identifierEq(group_id.toLowerCase()).
                        toList().get(0);
            }
            catch (Exception ignored)
            {
            }

            if (group_id.compareTo("-1") == 0)
            {
                display_toast("group_custom_packet_cb:ERROR 001 with incoming Group File!", true, 0);
                return;
            }

            if (group_temp.group_identifier.toLowerCase().compareTo(group_id.toLowerCase()) != 0)
            {
                display_toast("group_custom_packet_cb:ERROR 002 with incoming Group File!", true, 0);
                return;
            }

            final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);

            String groupname = null;
            try
            {
                if (group_temp.notification_silent)
                {
                    do_notification = false;
                }
                if (group_notification_silent_peer_get(group_id, peer_pubkey))
                {
                    do_notification = false;
                }
                groupname = group_temp.name;
            }
            catch (Exception e)
            {
                // e.printStackTrace();
                do_notification = false;
            }

            if (group_message_list_activity != null)
            {
                //Log.i(TAG,
                //      "group_custom_packet_cb:noti_and_badge:002group:" + group_message_list_activity.get_current_group_id() + ":" + group_id);
                if (group_message_list_activity.get_current_group_id().equals(group_id))
                {
                    // no notifcation and no badge update
                    do_notification = false;
                    do_badge_update = false;
                }
            }

            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
            hash_bytes.put(data, 8, 32);
            //Log.i(TAG, "group_custom_packet_cb:filename:"+hash_bytes.arrayOffset()+" "
            //           +hash_bytes.limit()+" "+hash_bytes.array().length);
            //Log.i(TAG, "group_custom_packet_cb:hash_bytes hex="
            //           + HelperGeneric.bytesToHex(hash_bytes.array(),hash_bytes.arrayOffset(),hash_bytes.limit()));

            // TODO: fix me!
            long timestamp = ((byte)data[8+32]<<3) + ((byte)data[8+32+1]<<2) + ((byte)data[8+32+2]<<1) + (byte)data[8+32+3];

            String filename = "image.jpg";
            try
            {
                ByteBuffer filename_bytes = ByteBuffer.allocateDirect(TOX_MAX_FILENAME_LENGTH);
                filename_bytes.put(data, 8 + 32 + 4, TOX_MAX_FILENAME_LENGTH);
                filename = utf8_string_from_bytes_with_padding(filename_bytes,
                                                               TOX_MAX_FILENAME_LENGTH,
                                                               "image.jpg");
                Log.i(TAG,"group_custom_packet_cb:filename str=" + filename);

                //Log.i(TAG, "group_custom_packet_cb:filename:"+filename_bytes.arrayOffset()+" "
                //+filename_bytes.limit()+" "+filename_bytes.array().length);
                //Log.i(TAG, "group_custom_packet_cb:filename hex="
                //           + HelperGeneric.bytesToHex(filename_bytes.array(),filename_bytes.arrayOffset(),filename_bytes.limit()));
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }

            long file_size = length - header;
            if (file_size < 1)
            {
                Log.i(TAG, "group_custom_packet_cb: file size less than 1 byte");
                return;
            }

            String filename_corrected = get_incoming_filetransfer_local_filename(filename, group_id.toLowerCase());

            // Log.i(TAG, "group_custom_packet_cb:filename=" + filename + " filename_corrected=" + filename_corrected);

            GroupMessage m = new GroupMessage();
            m.is_new = do_badge_update;
            m.tox_group_peer_pubkey = peer_pubkey;
            m.direction = 0; // msg received
            m.TOX_MESSAGE_TYPE = 0;
            m.read = false;
            m.tox_group_peername = null;
            m.private_message = 0;
            m.group_identifier = group_id.toLowerCase();
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.rcvd_timestamp = System.currentTimeMillis();
            m.sent_timestamp = System.currentTimeMillis();
            m.text = filename_corrected + "\n" + file_size + " bytes";
            m.message_id_tox = "";
            m.was_synced = false;
            m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
            m.path_name = VFS_PREFIX + VFS_FILE_DIR + "/" + m.group_identifier + "/";
            m.file_name = filename_corrected;
            m.filename_fullpath = m.path_name + m.file_name;
            m.storage_frame_work = false;
            m.msg_id_hash = bytebuffer_to_hexstring(hash_bytes, true);
            m.filesize = file_size;

            try
            {
                m.tox_group_peername = tox_group_peer_get_name__wrapper(m.group_identifier,
                                                                        m.tox_group_peer_pubkey);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                    m.path_name + "/" + m.file_name);
            info.guardianproject.iocipher.File f2 = new info.guardianproject.iocipher.File(f1.getParent());
            f2.mkdirs();

            save_group_incoming_file(m.path_name, m.file_name, data, header, file_size);

            if (group_message_list_activity != null)
            {
                if (group_message_list_activity.get_current_group_id().equals(group_id.toLowerCase()))
                {
                    insert_into_group_message_db(m, true);
                }
                else
                {
                    insert_into_group_message_db(m, false);
                }
            }
            else
            {
                long new_msg_id = insert_into_group_message_db(m, false);
                Log.i(TAG, "group_custom_packet_cb:new_msg_id=" + new_msg_id);
            }

            HelperFriend.add_all_friends_clear_wrapper(0);

            if (do_notification)
            {
                change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.group_identifier, groupname, m.text);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void send_ngch_request(final String group_identifier, final String peer_pubkey)
    {
        try
        {
            long res = tox_group_self_get_peer_id(tox_group_by_groupid__wrapper(group_identifier));
            if (res == get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey))
            {
                // HINT: ignore own packets
                Log.i(TAG, "send_ngch_request:dont send to self");
                return;
            }
        }
        catch(Exception e)
        {
        }

        final Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    // HINT: sleep "5 + random(0 .. 6)" seconds
                    Random rand = new Random();
                    int rndi = rand.nextInt(7);
                    int n = 5 + rndi;
                    // Log.i(TAG,"send_ngch_request: sleep for " + n + " seconds");
                    Thread.sleep(1000 * n);
                    //
                    final int data_length = 6 + 1 + 1;
                    ByteBuffer data_buf = ByteBuffer.allocateDirect(data_length);

                    data_buf.rewind();
                    //
                    data_buf.put((byte) 0x66);
                    data_buf.put((byte) 0x77);
                    data_buf.put((byte) 0x88);
                    data_buf.put((byte) 0x11);
                    data_buf.put((byte) 0x34);
                    data_buf.put((byte) 0x35);
                    //
                    data_buf.put((byte) 0x1);
                    //
                    data_buf.put((byte) 0x1);

                    byte[] data = new byte[data_length];
                    data_buf.rewind();
                    data_buf.get(data);
                    int result = tox_group_send_custom_private_packet(
                            tox_group_by_groupid__wrapper(group_identifier),
                            get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey),
                            1,
                            data,
                            data_length);
                    // Log.i(TAG,"send_ngch_request: sending request:result=" + result);
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        t.start();
    }

    static void sync_group_message_history(final long group_number, final long peer_id)
    {
        final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);
        final String group_identifier = tox_group_by_groupnum__wrapper(group_number);

        try
        {
            long res = tox_group_self_get_peer_id(tox_group_by_groupid__wrapper(group_identifier));
            if (res == get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey))
            {
                // HINT: ignore self
                Log.i(TAG, "sync_group_message_history:dont send to self");
                return;
            }
        }
        catch(Exception ignored)
        {
        }

        final Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    final int history_window_seconds = is_khandaq_community_group(group_identifier)
                            ? KHANDAQ_COMMUNITY_HISTORY_SYNC_MAX_SECONDS_BACK
                            : TOX_NGC_HISTORY_SYNC_MAX_SECONDS_BACK;
                    final long sync_from_ts = history_window_seconds <= 0
                            ? 0
                            : System.currentTimeMillis() - (history_window_seconds * 1000L);

                    // Log.i(TAG, "sync_group_message_history:sync_from_ts:" + sync_from_ts);

                    Iterator<com.zoffcc.applications.sorm.GroupMessage> i1 =  orma.selectFromGroupMessage()
                            .group_identifierEq(group_identifier)
                            // .TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value)
                            .private_messageEq(0)
                            .tox_group_peer_pubkeyNotEq("-1")
                            .sent_timestampGt(sync_from_ts)
                            .orderByRcvd_timestampAsc()
                            .toList().iterator();

                    // Log.i(TAG, "sync_group_message_history:i1:" + i1);

                    while (i1.hasNext())
                    {
                        try
                        {
                            GroupMessage gm = (GroupMessage) i1.next();
                            if (!gm.tox_group_peer_pubkey.equalsIgnoreCase("-1"))
                            {
                                //Log.i(TAG, "sync_group_message_history:sync:sent_ts="
                                //           + gm.sent_timestamp + " syncts=" + sync_from_ts + " "
                                //           + gm.tox_group_peer_pubkey + " " +
                                //           gm.message_id_tox + " " + gm.msg_id_hash);
                                if (gm.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                                {
                                    send_ngch_syncfile(group_identifier, peer_pubkey, gm);
                                }
                                else
                                {
                                    send_ngch_syncmsg(group_identifier, peer_pubkey, gm);
                                }
                            }
                            else
                            {
                                // Log.i(TAG, "sync_group_message_history:sync:ignoring system message");
                            }
                        }
                        catch (Exception e2)
                        {
                            e2.printStackTrace();
                        }
                    }

                    // Log.i(TAG, "sync_group_message_history:END");
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        t.start();
    }

    private static void send_ngch_syncmsg(final String group_identifier, final String peer_pubkey, final GroupMessage m)
    {
        try
        {
            Random rand = new Random();
            int rndi = rand.nextInt(301);
            int n = 300 + rndi;
            // Log.i(TAG, "send_ngch_syncmsg: sleep for " + n + " ms");
            Thread.sleep(n);
            //
            final int header_length = 6 + 1 + 1 + 4 + 32 + 4 + 25;
            final int data_length = header_length + m.text.getBytes(StandardCharsets.UTF_8).length;

            if (data_length < (header_length + 1) || (data_length > 40000))
            {
                Log.i(TAG, "send_ngch_syncmsg: some error in calculating data length");
                return;
            }

            ByteBuffer data_buf = ByteBuffer.allocateDirect(data_length);

            data_buf.rewind();
            //
            data_buf.put((byte) 0x66);
            data_buf.put((byte) 0x77);
            data_buf.put((byte) 0x88);
            data_buf.put((byte) 0x11);
            data_buf.put((byte) 0x34);
            data_buf.put((byte) 0x35);
            //
            data_buf.put((byte) 0x1);
            //
            data_buf.put((byte) 0x2);
            // should be 4 bytes
            try
            {
                data_buf.put(HelperGeneric.hex_to_bytes(m.message_id_tox), 0,4);
            }
            catch (Exception e)
            {
                data_buf.put((byte) 0x0);
                data_buf.put((byte) 0x0);
                data_buf.put((byte) 0x0);
                data_buf.put((byte) 0x0);
            }
            // should be 32 bytes
            try
            {
                data_buf.put(HelperGeneric.hex_to_bytes(m.tox_group_peer_pubkey), 0, 32);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                for(int jj=0;jj<32;jj++)
                {
                    data_buf.put((byte) 0x0);
                }
            }
            //
            // unix timestamp
            long timestamp_tmp = (m.sent_timestamp / 1000);
            // Log.i(TAG,"send_ngch_syncmsg:outgoing_timestamp=" + timestamp_tmp);
            ByteBuffer temp_buffer = ByteBuffer.allocate(8);
            temp_buffer.putLong(timestamp_tmp).order(ByteOrder.BIG_ENDIAN);
            temp_buffer.position(4);
            data_buf.put(temp_buffer);
            //Log.i(TAG,"send_ngch_syncmsg:send_ts_bytes:" +
            //         HelperGeneric.bytesToHex(temp_buffer.array(), temp_buffer.arrayOffset(), temp_buffer.limit()));
            /*
            data_buf.put((byte)((timestamp_tmp >> 32) & 0xFF));
            data_buf.put((byte)((timestamp_tmp >> 16) & 0xFF));
            data_buf.put((byte)((timestamp_tmp >> 8) & 0xFF));
            data_buf.put((byte)(timestamp_tmp & 0xFF));
            */
            //
            byte[] fn = "peer".getBytes(StandardCharsets.UTF_8);
            try
            {
                final String peer_name = tox_group_peer_get_name__wrapper(m.group_identifier, m.tox_group_peer_pubkey);
                if (peer_name.getBytes(StandardCharsets.UTF_8).length > TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES)
                {
                    fn = Arrays.copyOfRange(peer_name.getBytes(StandardCharsets.UTF_8),0,TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                }
                else
                {
                    fn = peer_name.getBytes(StandardCharsets.UTF_8);
                }
            }
            catch(Exception e)
            {
            }
            data_buf.put(fn);
            for (int k=0;k<(TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES - fn.length);k++)
            {
                // fill with null bytes up to TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES for the peername
                data_buf.put((byte) 0x0);
            }
            // -- now fill the message text --
            fn = m.text.getBytes(StandardCharsets.UTF_8);
            data_buf.put(fn);
            //
            //
            //
            byte[] data = new byte[data_length];
            data_buf.rewind();
            data_buf.get(data);
            //Log.i(TAG,"send_ngch_syncmsg:send_ts_bytes_to_network:" +
            //          HelperGeneric.bytesToHex(data, 6 + 1 + 1 + 4 + 32 , 4));
            int result = tox_group_send_custom_private_packet(tox_group_by_groupid__wrapper(group_identifier),
                                                              get_group_peernum_from_peer_pubkey(group_identifier,
                                                                                                 peer_pubkey), 1, data,
                                                              data_length);
            // Log.i(TAG, "send_ngch_syncmsg: sending request:result=" + result);
        }
        catch(Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "send_ngch_syncmsg:EE:" + e.getMessage());
        }
    }

    private static void send_ngch_syncfile(final String group_identifier, final String peer_pubkey, final GroupMessage m)
    {
        try
        {
            Random rand = new Random();
            int rndi = rand.nextInt(301);
            int n = 300 + rndi;
            Log.i(TAG, "send_ngch_syncfile: sleep for " + n + " ms");
            Thread.sleep(n);
            //
            final int header_length = 6 + 1 + 1 + 32 + 32 + 4 + 25 + 255;
            final info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                m.path_name + "/" + m.file_name);
            final java.io.File f2 = new java.io.File(m.path_name + "/" + m.file_name);

            long data_length_ = header_length;
            long f_length = 0;
            if (m.direction == 1)
            {
                // outgoing file
                data_length_ = data_length_ + f2.length();
                f_length = f2.length();
            }
            else
            {
                // incoming file
                data_length_ = data_length_ + f1.length();
                f_length = f1.length();
            }

            Log.i(TAG, "send_ngch_syncfile: file=" + m.path_name + "__/__" + m.file_name + " " + m.filename_fullpath);
            Log.i(TAG, "send_ngch_syncfile: data_length=" + data_length_ + " header_length=" +
                       header_length + " filesize=" + f_length);

            if (data_length_ < (header_length + 1) || (data_length_ > 40000))
            {
                Log.i(TAG, "send_ngch_syncfile: some error in calculating data length");
                return;
            }

            final int data_length = (int)data_length_;
            ByteBuffer data_buf = ByteBuffer.allocateDirect(data_length);

            data_buf.rewind();
            //
            data_buf.put((byte) 0x66);
            data_buf.put((byte) 0x77);
            data_buf.put((byte) 0x88);
            data_buf.put((byte) 0x11);
            data_buf.put((byte) 0x34);
            data_buf.put((byte) 0x35);
            //
            data_buf.put((byte) 0x1);
            //
            data_buf.put((byte) 0x3);
            // should be 32 bytes
            try
            {
                data_buf.put(HelperGeneric.hex_to_bytes(m.msg_id_hash), 0,32);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                for(int jj=0;jj<32;jj++)
                {
                    data_buf.put((byte) 0x0);
                }
            }
            // should be 32 bytes
            try
            {
                data_buf.put(HelperGeneric.hex_to_bytes(m.tox_group_peer_pubkey), 0, 32);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                for(int jj=0;jj<32;jj++)
                {
                    data_buf.put((byte) 0x0);
                }
            }
            //
            // unix timestamp
            long timestamp_tmp = (m.sent_timestamp / 1000);
            Log.i(TAG,"send_ngch_syncfile:outgoing_timestamp=" + timestamp_tmp);
            ByteBuffer temp_buffer = ByteBuffer.allocate(8);
            temp_buffer.putLong(timestamp_tmp).order(ByteOrder.BIG_ENDIAN);
            temp_buffer.position(4);
            data_buf.put(temp_buffer);
            //Log.i(TAG,"send_ngch_syncmsg:send_ts_bytes:" +
            //          HelperGeneric.bytesToHex(temp_buffer.array(), temp_buffer.arrayOffset(), temp_buffer.limit()));
            //
            byte[] fn = "peer".getBytes(StandardCharsets.UTF_8);
            try
            {
                final String peer_name = tox_group_peer_get_name__wrapper(m.group_identifier, m.tox_group_peer_pubkey);
                if (peer_name.getBytes(StandardCharsets.UTF_8).length > TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES)
                {
                    fn = Arrays.copyOfRange(peer_name.getBytes(StandardCharsets.UTF_8),0,TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                }
                else
                {
                    fn = peer_name.getBytes(StandardCharsets.UTF_8);
                }
            }
            catch(Exception e)
            {
            }
            data_buf.put(fn);
            for (int k=0;k<(TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES - fn.length);k++)
            {
                // fill with null bytes up to TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES for the peername
                data_buf.put((byte) 0x0);
            }
            //
            //
            //
            byte[] filename_bytes = "image.jpg".getBytes(StandardCharsets.UTF_8);
            try
            {
                if (m.file_name.getBytes(StandardCharsets.UTF_8).length > TOX_MAX_FILENAME_LENGTH)
                {
                    filename_bytes = Arrays.copyOfRange(m.file_name.getBytes(StandardCharsets.UTF_8),0,TOX_MAX_FILENAME_LENGTH);
                }
                else
                {
                    filename_bytes = m.file_name.getBytes(StandardCharsets.UTF_8);
                }
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }

            try
            {
                Log.i(TAG,"send_ngch_syncmsg:send_ts_bytes:filename_bytes=" +
                      HelperGeneric.bytesToHex(filename_bytes, 0, filename_bytes.length));
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }

            data_buf.put(filename_bytes);
            for (int k=0;k<(TOX_MAX_FILENAME_LENGTH - filename_bytes.length);k++)
            {
                // fill with null bytes up to TOX_MAX_FILENAME_LENGTH for the peername
                data_buf.put((byte) 0x0);
            }
            //
            //
            // -- now fill the file data --

            byte[] file_raw_data = null;
            if (m.direction == 1)
            {
                // outgoing file
                java.io.FileInputStream inputStream = new java.io.FileInputStream(f2);
                file_raw_data = new byte[(int)f2.length()];
                inputStream.read(file_raw_data);
                inputStream.close();
            }
            else
            {
                // incoming file
                info.guardianproject.iocipher.FileInputStream inputStream = new info.guardianproject.iocipher.FileInputStream(f1);
                file_raw_data = new byte[(int)f1.length()];
                inputStream.read(file_raw_data);
                inputStream.close();
            }

            data_buf.put(file_raw_data);
            //
            //
            //
            byte[] data = new byte[data_length];
            data_buf.rewind();
            data_buf.get(data);
            int result = tox_group_send_custom_private_packet(tox_group_by_groupid__wrapper(group_identifier),
                                                              get_group_peernum_from_peer_pubkey(group_identifier,
                                                                                                 peer_pubkey), 1, data,
                                                              data_length);
            // Log.i(TAG, "send_ngch_syncfile: sending request:result=" + result);
        }
        catch(Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "send_ngch_syncfile:EE:" + e.getMessage());
        }
    }

    static void handle_incoming_sync_group_message(final long group_number, final long peer_id, final byte[] data, final long length)
    {
        try
        {
            long res = tox_group_self_get_peer_id(group_number);
            if (res == peer_id)
            {
                // HINT: do not add our own messages, they are already in the DB!
                // Log.i(TAG, "handle_incoming_sync_group_message:gn=" + group_number + " peerid=" + peer_id + " ignoring self");
                return;
            }

            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            final String syncer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);

            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_GROUP_PEER_PUBLIC_KEY_SIZE);
            hash_bytes.put(data, 8 + 4, 32);
            final String original_sender_peerpubkey = HelperGeneric.bytesToHex(hash_bytes.array(),hash_bytes.arrayOffset(),hash_bytes.limit()).toUpperCase();
            // Log.i(TAG, "handle_incoming_sync_group_message:peerpubkey hex=" + original_sender_peerpubkey);

            // check for muted or kicked peers
            if (is_group_muted_or_kicked_peer(group_identifier, original_sender_peerpubkey))
            {
                return;
            }
            // check for muted or kicked peers

            if (tox_group_self_get_public_key(group_number).toUpperCase().equalsIgnoreCase(original_sender_peerpubkey))
            {
                // HINT: do not add our own messages, they are already in the DB!
                // Log.i(TAG, "handle_incoming_sync_group_message:gn=" + group_number + " peerid=" + peer_id + " ignoring myself as original sender");
                return;
            }
            //
            //
            // HINT: putting 4 bytes unsigned int in big endian format into a java "long" is more complex than i thought
            ByteBuffer timestamp_byte_buffer = ByteBuffer.allocateDirect(8);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put(data, 8+4+32, 4);
            timestamp_byte_buffer.order(ByteOrder.BIG_ENDIAN);
            timestamp_byte_buffer.rewind();
            long timestamp = timestamp_byte_buffer.getLong();
            //Log.i(TAG,"handle_incoming_sync_group_message:got_ts_bytes:" +
            //          HelperGeneric.bytesToHex(data, 8+4+32, 4));
            timestamp_byte_buffer.rewind();
            //Log.i(TAG,"handle_incoming_sync_group_message:got_ts_bytes:bytebuffer:" +
            //          HelperGeneric.bytesToHex(timestamp_byte_buffer.array(),
            //                                   timestamp_byte_buffer.arrayOffset(),
             //                                  timestamp_byte_buffer.limit()));

            // Log.i(TAG, "handle_incoming_sync_group_message:timestamp=" + timestamp);

            if (timestamp > ((System.currentTimeMillis() / 1000) + (60 * 5)))
            {
                long delta_t = timestamp - (System.currentTimeMillis() / 1000);
                // Log.i(TAG, "handle_incoming_sync_group_message:delta t=" + delta_t + " do NOT sync messages from the future");
                return;
            }
            else if (timestamp < ((System.currentTimeMillis() / 1000) - (60 * 200)))
            {
                long delta_t = (System.currentTimeMillis() / 1000) - timestamp;
                // Log.i(TAG, "handle_incoming_sync_group_message:delta t=" + (-delta_t) + " do NOT sync messages that are too old");
                return;
            }

            //
            //
            //
            ByteBuffer hash_msg_id_bytes = ByteBuffer.allocateDirect(4);
            hash_msg_id_bytes.put(data, 8, 4);
            final String message_id_tox = HelperGeneric.bytesToHex(hash_msg_id_bytes.array(),hash_msg_id_bytes.arrayOffset(),hash_msg_id_bytes.limit()).toLowerCase();
            // Log.i(TAG, "handle_incoming_sync_group_message:message_id_tox hex=" + message_id_tox);
            //
            //

            try
            {
                ByteBuffer name_buffer = ByteBuffer.allocateDirect(TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                name_buffer.put(data, 8 + 4 + 32 + 4, TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                String peer_name = utf8_string_from_bytes_with_padding(name_buffer,
                                                                             TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES,
                                                                             "peer");
                // Log.i(TAG,"handle_incoming_sync_group_message:peer_name str=" + peer_name);


                //
                final int header = 6+1+1+4+32+4+25; // 73 bytes
                long text_size = length - header;
                if ((text_size < 1) || (text_size > 37000))
                {
                    Log.i(TAG, "handle_incoming_sync_group_message: text size less than 1 byte or larger than 37000 bytes");
                    return;
                }

                byte[] text_byte_buf = Arrays.copyOfRange(data, header, (int)length);
                String message_str = new String(text_byte_buf, StandardCharsets.UTF_8);
                // Log.i(TAG,"handle_incoming_sync_group_message:message str=" + message_str);

                long sender_peer_num = HelperGroup.get_group_peernum_from_peer_pubkey(group_identifier,
                                                                                      original_sender_peerpubkey);

                GroupMessage gm = get_last_group_message_in_this_group_within_n_seconds_from_sender_pubkey(
                        group_identifier, original_sender_peerpubkey, (timestamp * 1000),
                        message_id_tox, MESSAGE_GROUP_HISTORY_SYNC_DOUBLE_INTERVAL_SECS, message_str);

                try
                {
                    if ((message_id_tox != null) && (message_id_tox.length()>1))
                    {
                        GroupMessage gmsg = (GroupMessage) orma.selectFromGroupMessage().group_identifierEq(group_identifier).
                                tox_group_peer_pubkeyEq(original_sender_peerpubkey).
                                message_id_toxEq(message_id_tox).
                                textEq(message_str).toList().get(0);
                        if (gmsg != null)
                        {
                            if (gmsg.was_synced)
                            {
                                //Log.i(TAG,"handle_incoming_sync_group_message:syn_conf: message_id_tox="
                                //          +message_id_tox+ ", syncer=" + syncer_pubkey);
                                if (gmsg.sync_confirmations == 0)
                                {
                                    if (!syncer_pubkey.equals(gmsg.tox_group_peer_pubkey_syncer_01))
                                    {
                                        // its a new syncer
                                        orma.updateGroupMessage().group_identifierEq(group_identifier).tox_group_peer_pubkeyEq(
                                                original_sender_peerpubkey).message_id_toxEq(message_id_tox).textEq(
                                                message_str).sync_confirmations(gmsg.sync_confirmations + 1).
                                                tox_group_peer_pubkey_syncer_02(syncer_pubkey).
                                                tox_group_peer_pubkey_syncer_02_sent_timestamp(timestamp*1000).
                                                execute();
                                        // Log.i(TAG,"handle_incoming_sync_group_message:syn_conf=1, syncer=" + syncer_pubkey);
                                        gmsg.sync_confirmations++;
                                        gmsg.tox_group_peer_pubkey_syncer_02 = syncer_pubkey;
                                        update_group_message_in_list(gmsg);
                                    }
                                }
                                else if (gmsg.sync_confirmations == 1)
                                {
                                    if ((!syncer_pubkey.equals(gmsg.tox_group_peer_pubkey_syncer_01)) &&
                                        (!syncer_pubkey.equals(gmsg.tox_group_peer_pubkey_syncer_02)))
                                    {
                                        // its a new syncer
                                        orma.updateGroupMessage().group_identifierEq(group_identifier).tox_group_peer_pubkeyEq(
                                                original_sender_peerpubkey).message_id_toxEq(message_id_tox).textEq(
                                                message_str).sync_confirmations(gmsg.sync_confirmations + 1).
                                                tox_group_peer_pubkey_syncer_03(syncer_pubkey).
                                                tox_group_peer_pubkey_syncer_03_sent_timestamp(timestamp*1000).
                                                execute();
                                        // Log.i(TAG,"handle_incoming_sync_group_message:syn_conf=2, syncer=" + syncer_pubkey);
                                        gmsg.sync_confirmations++;
                                        gmsg.tox_group_peer_pubkey_syncer_03 = syncer_pubkey;
                                        update_group_message_in_list(gmsg);
                                    }
                                }
                                else if (gmsg.sync_confirmations == 2)
                                {
                                    if ((!syncer_pubkey.equals(gmsg.tox_group_peer_pubkey_syncer_01)) &&
                                        (!syncer_pubkey.equals(gmsg.tox_group_peer_pubkey_syncer_02)) &&
                                        (!syncer_pubkey.equals(gmsg.tox_group_peer_pubkey_syncer_03)))
                                    {
                                        // its a new syncer
                                        orma.updateGroupMessage().group_identifierEq(group_identifier).tox_group_peer_pubkeyEq(
                                                original_sender_peerpubkey).message_id_toxEq(message_id_tox).textEq(
                                                message_str).sync_confirmations(gmsg.sync_confirmations + 1).execute();
                                        // Log.i(TAG,"handle_incoming_sync_group_message:syn_conf=3, syncer=" + syncer_pubkey);
                                        gmsg.sync_confirmations++;
                                        update_group_message_in_list(gmsg);
                                    }
                                }
                            }
                        }
                    }
                }
                catch(Exception e)
                {
                    Log.i(TAG,"handle_incoming_sync_group_message:EE003:" + e.getMessage());
                }

                if (gm != null)
                {
                    // Log.i(TAG,"handle_incoming_sync_group_message:potential double message:" + message_str);
                    return;
                }

                final String peer_name_saved = tox_group_peer_get_name__wrapper(group_identifier, original_sender_peerpubkey);
                if (peer_name_saved != null)
                {
                    // HINT: use saved name instead of name from sync message
                    peer_name = peer_name_saved;
                }

                group_message_add_from_sync(group_identifier, syncer_pubkey, sender_peer_num, original_sender_peerpubkey,
                                            TRIFA_MSG_TYPE_TEXT.value, message_str, message_str.length(),
                                            (timestamp * 1000), message_id_tox,
                                            TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value,
                                            peer_name);
            }
            catch(Exception e)
            {
                e.printStackTrace();
                Log.i(TAG,"handle_incoming_sync_group_message:EE002:" + e.getMessage());
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "handle_incoming_sync_group_message:EE001:" + e.getMessage());
        }
    }

    static void handle_incoming_sync_group_file(final long group_number, final long peer_id, final byte[] data, final long length)
    {
        try
        {
            long res = tox_group_self_get_peer_id(group_number);
            if (res == peer_id)
            {
                // HINT: do not add our own messages, they are already in the DB!
                // Log.i(TAG, "handle_incoming_sync_group_file:gn=" + group_number + " peerid=" + peer_id + " ignoring self");
                return;
            }

            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            final String syncer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);

            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_GROUP_PEER_PUBLIC_KEY_SIZE);
            hash_bytes.put(data, 8 + 32, 32);
            final String original_sender_peerpubkey = HelperGeneric.bytesToHex(hash_bytes.array(),hash_bytes.arrayOffset(),hash_bytes.limit()).toUpperCase();
            // Log.i(TAG, "handle_incoming_sync_group_file:peerpubkey hex=" + original_sender_peerpubkey);

            // check for muted or kicked peers
            if (is_group_muted_or_kicked_peer(group_identifier, original_sender_peerpubkey))
            {
                return;
            }
            // check for muted or kicked peers

            if (tox_group_self_get_public_key(group_number).toUpperCase().equalsIgnoreCase(original_sender_peerpubkey))
            {
                // HINT: do not add our own files, they are already in the DB!
                // Log.i(TAG, "handle_incoming_sync_group_file:gn=" + group_number + " peerid=" + peer_id + " ignoring myself as original sender");
                return;
            }
            //
            //
            // HINT: putting 4 bytes unsigned int in big endian format into a java "long" is more complex than i thought
            ByteBuffer timestamp_byte_buffer = ByteBuffer.allocateDirect(8);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put((byte)0x0);
            timestamp_byte_buffer.put(data, 8+32+32, 4);
            timestamp_byte_buffer.order(ByteOrder.BIG_ENDIAN);
            timestamp_byte_buffer.rewind();
            long timestamp = timestamp_byte_buffer.getLong();
            //Log.i(TAG,"handle_incoming_sync_group_file:got_ts_bytes:" +
            //          HelperGeneric.bytesToHex(data, 8+32+32, 4));
            timestamp_byte_buffer.rewind();
            //Log.i(TAG,"handle_incoming_sync_group_file:got_ts_bytes:bytebuffer:" +
            //          HelperGeneric.bytesToHex(timestamp_byte_buffer.array(),
            //                                   timestamp_byte_buffer.arrayOffset(),
            //                                   timestamp_byte_buffer.limit()));

            //Log.i(TAG, "handle_incoming_sync_group_file:timestamp=" + timestamp);

            if (timestamp > ((System.currentTimeMillis() / 1000) + (60 * 5)))
            {
                long delta_t = timestamp - (System.currentTimeMillis() / 1000);
                // Log.i(TAG, "handle_incoming_sync_group_file:delta t=" + delta_t + " do NOT sync files from the future");
                return;
            }
            else if (timestamp < ((System.currentTimeMillis() / 1000) - (60 * 200)))
            {
                long delta_t = (System.currentTimeMillis() / 1000) - timestamp;
                // Log.i(TAG, "handle_incoming_sync_group_file:delta t=" + (-delta_t) + " do NOT sync files that are too old");
                return;
            }

            //
            //
            //
            ByteBuffer hash_msg_id_bytes = ByteBuffer.allocateDirect(32);
            hash_msg_id_bytes.put(data, 8, 32);
            final String message_id_hash = HelperGeneric.bytesToHex(hash_msg_id_bytes.array(),hash_msg_id_bytes.arrayOffset(),hash_msg_id_bytes.limit()).toUpperCase();
            // Log.i(TAG, "handle_incoming_sync_group_file:message_id_hash hex=" + message_id_hash);
            //
            //
            try
            {
                ByteBuffer name_buffer = ByteBuffer.allocateDirect(TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                name_buffer.put(data, 8 + 32 + 32 + 4, TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                final String peer_name = utf8_string_from_bytes_with_padding(name_buffer,
                                                                             TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES,
                                                                             "peer");
                Log.i(TAG,"handle_incoming_sync_group_file:peer_name str=" + peer_name);
                //
                //
                //
                ByteBuffer filename_buffer = ByteBuffer.allocateDirect(TOX_NGC_HISTORY_SYNC_MAX_FILENAME_BYTES);
                filename_buffer.put(data, 6 + 1 + 1 + 32 + 32 + 4 + 25, TOX_NGC_HISTORY_SYNC_MAX_FILENAME_BYTES);
                final String filename = utf8_string_from_bytes_with_padding(filename_buffer,
                                                                            TOX_NGC_HISTORY_SYNC_MAX_FILENAME_BYTES,
                                                                            "image.jpg");
                Log.i(TAG, "handle_incoming_sync_group_file:filename=" + filename);
                //
                //
                //
                final int header = 6+1+1+32+32+4+25+255;
                long filedata_size = length - header;
                if ((filedata_size < 1) || (filedata_size > 37000))
                {
                    Log.i(TAG, "handle_incoming_sync_group_file: file size less than 1 byte or larger than 37000 bytes");
                    return;
                }

                byte[] file_byte_buf = Arrays.copyOfRange(data, header, (int)length);

                long sender_peer_num = HelperGroup.get_group_peernum_from_peer_pubkey(group_identifier,
                                                                                      original_sender_peerpubkey);

                try
                {
                    if (group_identifier!=null)
                    {
                        GroupMessage gm = (GroupMessage) orma.selectFromGroupMessage().group_identifierEq(group_identifier).tox_group_peer_pubkeyEq(original_sender_peerpubkey).msg_id_hashEq(
                                message_id_hash).toList().get(0);

                        if (gm != null)
                        {
                            // Log.i(TAG, "handle_incoming_sync_group_file:potential double file, message_id_hash:" + message_id_hash);
                            return;
                        }
                    }
                }
                catch(Exception e)
                {
                }

                group_file_add_from_sync(group_identifier, syncer_pubkey, sender_peer_num, original_sender_peerpubkey,
                                            file_byte_buf, filename, peer_name,
                                            (timestamp * 1000), message_id_hash,
                                            TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value);
            }
            catch(Exception e)
            {
                e.printStackTrace();
                Log.i(TAG,"handle_incoming_sync_group_file:EE002:" + e.getMessage());
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "handle_incoming_sync_group_file:EE001:" + e.getMessage());
        }
    }

    private static void group_file_add_from_sync(final String group_identifier, final String syncer_pubkey,
                                                 final long sender_peer_num,
                                                 final String original_sender_peerpubkey,
                                                 final byte[] file_byte_buf, final String filename,
                                                 final String peer_name,
                                                 final long timestamp, final String message_id_hash,
                                                 final int aTRIFA_SYNC_TYPE)
    {
        boolean do_notification = true;
        boolean do_badge_update = true;
        String group_id = group_identifier;
        GroupDB group_temp = null;

        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();

        try
        {
            try
            {
                group_temp = (GroupDB) orma.selectFromGroupDB().
                        group_identifierEq(group_id.toLowerCase()).
                        toList().get(0);
            }
            catch (Exception ignored)
            {
            }

            if (group_id.compareTo("-1") == 0)
            {
                display_toast("group_file_add_from_sync:ERROR 001 with incoming Group File!", true, 0);
                return;
            }

            if (group_temp.group_identifier.toLowerCase().compareTo(group_id.toLowerCase()) != 0)
            {
                display_toast("group_file_add_from_sync:ERROR 002 with incoming Group File!", true, 0);
                return;
            }

            String groupname = null;
            try
            {
                if (group_temp.notification_silent)
                {
                    do_notification = false;
                }
                if (group_notification_silent_peer_get(group_identifier, original_sender_peerpubkey))
                {
                    do_notification = false;
                }
                groupname = group_temp.name;
            }
            catch (Exception e)
            {
                // e.printStackTrace();
                do_notification = false;
            }

            if (group_message_list_activity != null)
            {
                if (group_message_list_activity.get_current_group_id().equals(group_id))
                {
                    // no notifcation and no badge update
                    do_notification = false;
                    do_badge_update = false;
                }
            }

            String filename_corrected = get_incoming_filetransfer_local_filename(filename, group_id.toLowerCase());

            GroupMessage m = new GroupMessage();
            m.is_new = do_badge_update;
            m.tox_group_peer_pubkey = original_sender_peerpubkey;
            m.direction = 0; // msg received
            m.TOX_MESSAGE_TYPE = 0;
            m.read = false;
            m.tox_group_peername = peer_name;
            m.private_message = 0;
            m.group_identifier = group_id.toLowerCase();
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.rcvd_timestamp = System.currentTimeMillis();
            m.sent_timestamp = timestamp;
            m.text = filename_corrected + "\n" + file_byte_buf.length + " bytes";
            m.message_id_tox = "";
            m.was_synced = true;
            m.TRIFA_SYNC_TYPE = aTRIFA_SYNC_TYPE;
            m.path_name = VFS_PREFIX + VFS_FILE_DIR + "/" + m.group_identifier + "/";
            m.file_name = filename_corrected;
            m.filename_fullpath = m.path_name + m.file_name;
            m.storage_frame_work = false;
            m.msg_id_hash = message_id_hash;
            m.filesize = file_byte_buf.length;
            m.tox_group_peer_pubkey_syncer_01 = syncer_pubkey;

            m.tox_group_peer_role = -1;
            try
            {
                int peer_role_get = tox_group_peer_get_role(tox_group_by_groupid__wrapper(group_identifier),
                                                            get_group_peernum_from_peer_pubkey(group_identifier, original_sender_peerpubkey));
                if (peer_role_get >= 0)
                {
                    m.tox_group_peer_role = peer_role_get;
                }

            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                    m.path_name + "/" + m.file_name);
            info.guardianproject.iocipher.File f2 = new info.guardianproject.iocipher.File(f1.getParent());
            f2.mkdirs();

            save_group_incoming_file(m.path_name, m.file_name, file_byte_buf, 0, file_byte_buf.length);

            if (group_message_list_activity != null)
            {
                if (group_message_list_activity.get_current_group_id().equals(group_id.toLowerCase()))
                {
                    insert_into_group_message_db(m, true);
                }
                else
                {
                    insert_into_group_message_db(m, false);
                }
            }
            else
            {
                long new_msg_id = insert_into_group_message_db(m, false);
                Log.i(TAG, "group_file_add_from_sync:new_msg_id=" + new_msg_id);
            }

            HelperFriend.add_all_friends_clear_wrapper(0);

            if (do_notification)
            {
                change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.group_identifier, groupname, m.text);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static void update_group_message_in_list(final GroupMessage gmsg)
    {
        try
        {
            if ((MainActivity.group_message_list_fragment != null)
                && (gmsg.group_identifier.equals(MainActivity.group_message_list_fragment.current_group_id)))
                {
                    //
                }
            else
            {
                // we are not showing that ngc group now
                return;
            }

            Runnable myRunnable = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        MainActivity.group_message_list_fragment.modify_message(gmsg);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            };
            if (main_handler_s != null)
            {
                main_handler_s.post(myRunnable);
            }
        }
        catch(Exception e)
        {
        }
    }

    static void ngc_update_video_incoming_peer_list(final String peer_pubkey)
    {
        lookup_ngc_incoming_video_peer_list.put(peer_pubkey, System.currentTimeMillis());
        ngc_update_video_incoming_peer_list_ts();
        // Log.i(TAG, "ngc_update_video_incoming_peer_list entries=" + lookup_ngc_incoming_video_peer_list.size());
    }

    static void ngc_update_video_incoming_peer_list_ts()
    {
        if (lookup_ngc_incoming_video_peer_list.isEmpty())
        {
            return;
        }
        // remove all peers that have not sent video in the last 5 seconds
        Iterator<Long> iterator = lookup_ngc_incoming_video_peer_list.values().iterator();
        while (iterator.hasNext())
        {
            if (iterator.next() < (System.currentTimeMillis() - (5 * 1000)))
            {
                iterator.remove();
            }
        }
    }

    static void ngc_purge_video_incoming_peer_list()
    {
        lookup_ngc_incoming_video_peer_list.clear();
    }

    static void ngc_set_video_call_icon(final int state)
    {
        try
        {
            if (state == NGC_VIDEO_ICON_STATE_ACTIVE)
            {
                final Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            final Drawable d3 = new IconicsDrawable(context_s).
                                    icon(FontAwesome.Icon.faw_video).color(context_s.getResources().
                                    getColor(R.color.md_light_green_A700)).sizeDp(80);
                            ml_video_icon.setImageDrawable(d3);
                            ml_video_icon.setPadding((int) dp2px(0), (int) dp2px(0), (int) dp2px(0), (int) dp2px(0));
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                };
                if (main_handler_s != null)
                {
                    main_handler_s.post(myRunnable);
                }
            }
            else if (state == NGC_VIDEO_ICON_STATE_INCOMING)
            {
                final Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            final Drawable d3 = new IconicsDrawable(context_s).
                                    icon(FontAwesome.Icon.faw_video).color(context_s.getResources().
                                    getColor(R.color.md_amber_800)).sizeDp(80);
                            ml_video_icon.setPadding((int) dp2px(7), (int) dp2px(7), (int) dp2px(7), (int) dp2px(7));
                            ml_video_icon.setImageDrawable(d3);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                };
                if (main_handler_s != null)
                {
                    main_handler_s.post(myRunnable);
                }
            }
            else // state == NGC_VIDEO_ICON_STATE_INACTIVE
            {
                final Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            final Drawable d3 = new IconicsDrawable(context_s).
                                    icon(FontAwesome.Icon.faw_video).color(context_s.getResources().
                                    getColor(R.color.icon_colors)).sizeDp(80);
                            ml_video_icon.setPadding((int) dp2px(7), (int) dp2px(7), (int) dp2px(7), (int) dp2px(7));
                            ml_video_icon.setImageDrawable(d3);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                };
                if (main_handler_s != null)
                {
                    main_handler_s.post(myRunnable);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void ngc_set_video_info_text(final String text)
    {
        final Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    ngc_camera_info_text.setText(text);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    static String ngc_get_index_video_incoming_peer_list(final int wanted_index)
    {
        if (lookup_ngc_incoming_video_peer_list.isEmpty())
        {
            return "-1";
        }

        if (wanted_index < 0)
        {
            return "-1";
        }

        // remove all peers that have not sent video in the last 5 seconds
        if (lookup_ngc_incoming_video_peer_list.size() == 1)
        {
            try
            {
                final String peer_pubkey = (String) lookup_ngc_incoming_video_peer_list.keySet().toArray()[0];
                try
                {
                    Log.i(TAG, "ngc_get_index_video_incoming_peer_list:peer_pubkey=" + peer_pubkey.substring(0, 6));
                }
                catch(Exception e)
                {
                }
                return peer_pubkey;
            }
            catch(Exception e)
            {
                return "-1";
            }
        }

        if (wanted_index >= lookup_ngc_incoming_video_peer_list.size())
        {
            try
            {
                final String peer_pubkey = (String) lookup_ngc_incoming_video_peer_list.keySet().
                        toArray()[wanted_index % lookup_ngc_incoming_video_peer_list.size()];
                Log.i(TAG, "ngc_get_index_video_incoming_peer_list:peer_pubkey=" + peer_pubkey);
                return peer_pubkey;
            }
            catch(Exception e)
            {
                return "-1";
            }
        }
        else
        {
            try
            {
                final String peer_pubkey = (String) lookup_ngc_incoming_video_peer_list.keySet().toArray()[wanted_index];
                Log.i(TAG, "ngc_get_index_video_incoming_peer_list:peer_pubkey=" + peer_pubkey);
                return peer_pubkey;
            }
            catch(Exception e)
            {
                return "-1";
            }
        }
    }
}
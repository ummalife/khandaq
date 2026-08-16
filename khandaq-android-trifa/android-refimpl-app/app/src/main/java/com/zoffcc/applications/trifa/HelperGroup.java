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
import android.text.TextUtils;
import android.util.Log;

import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.GroupPeerDB;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

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
import static com.zoffcc.applications.trifa.HelperGeneric.is_valid_tox_public_key;
import static com.zoffcc.applications.trifa.HelperGeneric.fourbytes_of_long_to_hex;
import static com.zoffcc.applications.trifa.HelperGeneric.io_file_copy;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.utf8_string_from_bytes_with_padding;
import static com.zoffcc.applications.trifa.HelperMsgNotification.change_msg_notification;
import static com.zoffcc.applications.trifa.MainActivity.PREF__conference_show_system_messages;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.main_activity_s;
import static com.zoffcc.applications.trifa.MainActivity.group_message_list_activity;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages_incoming_file;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages_text_only;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_by_chat_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_chat_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_privacy_state;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_topic_lock;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_founder_set_password;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_founder_set_privacy_state;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_founder_set_topic_lock;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_new;
import static com.zoffcc.applications.trifa.HelperRelay.have_own_relay;
import static com.zoffcc.applications.trifa.HelperRelay.invite_to_group_own_relay;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_peer_limit;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_topic;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_set_topic;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_invite_friend;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_is_connected;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_mod_set_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_disconnect;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_leave;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_reconnect;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_offline_peer_count;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_count;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_savedpeer_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_peerlist;
import static com.zoffcc.applications.trifa.HelperFriend.format_group_peer_list_display_name;
import static com.zoffcc.applications.trifa.HelperFriend.lookup_friendlist_by_pubkey;
import static com.zoffcc.applications.trifa.MainActivity.PREF__messageview_paging;
import static com.zoffcc.applications.trifa.ToxVars.GC_MAX_SAVED_PEERS;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_connection_status;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_grouplist;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_name_safe;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_peer_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_set_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_name_size;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_packet;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_private_packet;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_message;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GROUP_ID_LENGTH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_toxid;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_GROUP_HISTORY_SYNC_DOUBLE_INTERVAL_SECS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_ADD;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_NGC_HISTORY_SYNC_MAX_FILENAME_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_NGC_HISTORY_SYNC_MAX_SECONDS_BACK;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_INCOMING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UINT32_MAX_JAVA;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_PREFIX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_for_battery_savings_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_showing_anygroupview;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ERR_GROUP_INVITE_FRIEND;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ERR_GROUP_NEW;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_CHAT_ID_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_MAX_GROUP_NAME_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_MAX_TOPIC_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_MAX_PART_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_PEER_PUBLIC_KEY_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_HASH_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_FILENAME_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_FILESIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_FILE_AND_HEADER_SIZE;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.TrifaToxService.perform_khandaq_bootstrap_burst;

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
            //HelperGeneric.logI(TAG, "add_conference_wrapper:error=" + conference_num + " joining conference");
        }

        try
        {
            if (group_message_list_activity != null)
            {
                if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_identifier))
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
            // HelperGeneric.logI(TAG, "new_or_updated_group:" + "group_num=" + group_identifier);
            final GroupDB conf2 = (GroupDB) orma.selectFromGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList().get(0);
            // group already exists -> update and connect
            orma.updateGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    privacy_state(privacy_state).
                    tox_group_number(group_num).execute();

            try
            {
                HelperGeneric.logI(TAG, "new_or_updated_group:*update*");
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
                HelperGeneric.logI(TAG, "new_or_updated_group:EE3:" + e3.getMessage());
                // e3.printStackTrace();
            }

            if (is_group_active(group_identifier) && !is_group_we_left(group_identifier))
            {
                reconnect_group_if_disconnected(group_num, group_identifier);
            }
            sync_group_peers_from_tox_to_db(group_num);

            return;
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            HelperGeneric.logI(TAG, "new_or_updated_group:EE1:" + e.getMessage());

            // conference is new -> add
            try
            {
                String group_topic = "";
                try
                {
                    group_topic = tox_group_get_name(group_num);
                    HelperGeneric.logI(TAG, "new_or_updated_group:group_topic=" + group_topic);
                    if (group_topic == null)
                    {
                        group_topic = "";
                    }
                }
                catch (Exception e6)
                {
                    e6.printStackTrace();
                    HelperGeneric.logI(TAG, "new_or_updated_group:EE6:" + e6.getMessage());
                }

                GroupDB conf_new = new GroupDB();
                conf_new.group_identifier = group_identifier;
                conf_new.who_invited__tox_public_key_string = who_invited_public_key;
                conf_new.peer_count = -1;
                conf_new.own_peer_number = -1;
                conf_new.privacy_state = privacy_state;
                conf_new.group_active = true;
                conf_new.tox_group_number = group_num;
                conf_new.name = get_group_display_name(group_identifier, group_topic);
                //
                orma.insertIntoGroupDB(conf_new);
                HelperGeneric.logI(TAG, "new_or_updated_group:+ADD+");

                try
                {
                    CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
                    cc.is_friend = COMBINED_IS_GROUP;
                    cc.group_item = (GroupDB) GroupDB.deep_copy(conf_new);
                    HelperGeneric.logI(TAG, "new_or_updated_group:EE4__:" + MainActivity.friend_list_fragment + " " + cc);
                    if (MainActivity.friend_list_fragment != null)
                    {
                        MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
                    }
                }
                catch (Exception e4)
                {
                    e4.printStackTrace();
                    // HelperGeneric.logI(TAG, "new_or_updated_group:EE4:" + e4.getMessage());
                }

                return;
            }
            catch (Exception e1)
            {
                e1.printStackTrace();
                HelperGeneric.logI(TAG, "new_or_updated_group:EE2:" + e1.getMessage());
            }
        }
    }

    static void update_group_in_friendlist(long group_num)
    {
        try
        {
            if (MainActivity.friend_list_fragment == null)
            {
                return;
            }

            final String group_identifier = tox_group_by_groupnum__wrapper(group_num);
            HelperGeneric.logI(TAG, "new_or_updated_group:*update*");
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
            HelperGeneric.logI(TAG, "update_group_in_friendlist:EE3:" + e3.getMessage());
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

    private static Boolean tox_group_extended_state_jni_supported = null;

    public static boolean is_tox_group_extended_state_jni_supported()
    {
        if (tox_group_extended_state_jni_supported != null)
        {
            return tox_group_extended_state_jni_supported;
        }

        try
        {
            tox_group_get_topic_lock(-1);
            tox_group_extended_state_jni_supported = Boolean.TRUE;
        }
        catch (UnsatisfiedLinkError e)
        {
            tox_group_extended_state_jni_supported = Boolean.FALSE;
            Log.w(TAG, "group topic/privacy/password JNI unavailable in native lib");
        }

        return tox_group_extended_state_jni_supported;
    }

    public static int tox_group_get_topic_lock__wrapper(final long group_num)
    {
        if (!is_tox_group_extended_state_jni_supported())
        {
            return -99;
        }

        try
        {
            return tox_group_get_topic_lock(group_num);
        }
        catch (UnsatisfiedLinkError e)
        {
            tox_group_extended_state_jni_supported = Boolean.FALSE;
            Log.w(TAG, "tox_group_get_topic_lock unavailable in native lib");
            return -99;
        }
    }

    public static int tox_group_founder_set_topic_lock__wrapper(final long group_num, final int topic_lock)
    {
        if (!is_tox_group_extended_state_jni_supported())
        {
            return -99;
        }

        try
        {
            return tox_group_founder_set_topic_lock(group_num, topic_lock);
        }
        catch (UnsatisfiedLinkError e)
        {
            tox_group_extended_state_jni_supported = Boolean.FALSE;
            Log.w(TAG, "tox_group_founder_set_topic_lock unavailable in native lib");
            return -99;
        }
    }

    public static int tox_group_founder_set_privacy_state__wrapper(final long group_num, final int privacy_state)
    {
        if (!is_tox_group_extended_state_jni_supported())
        {
            return -99;
        }

        try
        {
            return tox_group_founder_set_privacy_state(group_num, privacy_state);
        }
        catch (UnsatisfiedLinkError e)
        {
            tox_group_extended_state_jni_supported = Boolean.FALSE;
            Log.w(TAG, "tox_group_founder_set_privacy_state unavailable in native lib");
            return -99;
        }
    }

    public static int tox_group_founder_set_password__wrapper(final long group_num, @Nullable final String password)
    {
        if (!is_tox_group_extended_state_jni_supported())
        {
            return -99;
        }

        try
        {
            return tox_group_founder_set_password(group_num, password);
        }
        catch (UnsatisfiedLinkError e)
        {
            tox_group_extended_state_jni_supported = Boolean.FALSE;
            Log.w(TAG, "tox_group_founder_set_password unavailable in native lib");
            return -99;
        }
    }

    static boolean is_viewing_group_message_list(final String group_identifier)
    {
        if ((group_identifier == null) || (group_message_list_activity == null))
        {
            return false;
        }
        final String current = group_message_list_activity.get_current_group_id();
        if (current == null)
        {
            return false;
        }
        return current.equalsIgnoreCase(group_identifier);
    }

    static boolean shouldRefreshGroupMessageList(final GroupMessage m, final boolean update_group_view_flag)
    {
        return update_group_view_flag || is_viewing_group_message_list(m.group_identifier);
    }

    static long insert_into_group_message_db(final GroupMessage m, final boolean update_group_view_flag)
    {
        if (!GroupMessageLayoutHelper.isRenderableMessageForDb(m))
        {
            HelperGeneric.logI(TAG, "insert_into_group_message_db:skip empty message");
            return -1;
        }

        long row_id = orma.insertIntoGroupMessage(m);

        try
        {
            if ((row_id != -1) && (shouldRefreshGroupMessageList(m, update_group_view_flag)))
            {
                if ((!should_show_group_system_messages(m.group_identifier)) &&
                    (m.tox_group_peer_pubkey.equals(TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY)))
                {
                    // HINT: dont show system message because of user PREF / community policy
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
        return tox_group_peer_get_name__wrapper(group_identifier, group_peer_pubkey, -1L);
    }

    static boolean is_short_hex_peer_id(final String name, final String peer_pubkey)
    {
        if (name == null || peer_pubkey == null || name.isEmpty())
        {
            return false;
        }
        return name.equalsIgnoreCase(HelperFriend.peer_pubkey_short_id(peer_pubkey));
    }

    private static String pick_non_hex_group_peer_name(final String candidate, final String peer_pubkey)
    {
        if (candidate == null || candidate.isEmpty() || "-1".equals(candidate))
        {
            return null;
        }
        final String sanitized = sanitize_group_peer_name(candidate);
        if (TextUtils.isEmpty(sanitized) || is_short_hex_peer_id(sanitized, peer_pubkey))
        {
            return null;
        }
        return sanitized;
    }

    public static String tox_group_peer_get_name__wrapper(final String group_identifier, final String group_peer_pubkey,
                                                          final long peer_id_hint)
    {
        String default_name = "";

        if ((group_peer_pubkey == null) || (group_peer_pubkey.trim().isEmpty()))
        {
            return HelperFriend.resolve_name_for_pubkey(group_peer_pubkey, default_name);
        }

        try
        {
            long group_num = tox_group_by_groupid__wrapper(group_identifier);
            long peer_num = peer_id_hint;
            if (peer_num < 0)
            {
                peer_num = get_group_peernum_from_peer_pubkey(group_identifier, group_peer_pubkey);
            }
            if (group_num >= 0 && peer_num >= 0)
            {
                final String res = tox_group_peer_get_name(group_num, peer_num);
                final String picked = pick_non_hex_group_peer_name(res, group_peer_pubkey);
                if (picked != null)
                {
                    return picked;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        final GroupPeerDB peer_from_db = lookup_group_peer_by_pubkey(group_identifier, group_peer_pubkey);
        if (peer_from_db != null)
        {
            final String picked = pick_non_hex_group_peer_name(peer_from_db.peer_name, group_peer_pubkey);
            if (picked != null)
            {
                default_name = picked;
            }
        }

        if (!default_name.isEmpty())
        {
            return default_name;
        }

        final FriendList friend = HelperFriend.lookup_friendlist_by_pubkey(group_peer_pubkey);
        if (friend != null)
        {
            final String friendName = HelperFriend.display_name_from_friendlist(friend, group_peer_pubkey);
            final String picked = pick_non_hex_group_peer_name(friendName, group_peer_pubkey);
            if (picked != null)
            {
                return picked;
            }
        }

        return HelperFriend.peer_pubkey_short_id(group_peer_pubkey);
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

    /**
     * Our own public key in one group, lowercased so it compares cleanly against the peer keys that
     * come back from the transport (which arrive uppercase from some call sites and lowercase from
     * others).
     */
    public static String tox_group_self_get_public_key__wrapper(long group_num)
    {
        try
        {
            final String result = MainActivity.tox_group_self_get_public_key(group_num);
            return (result == null) ? null : result.toLowerCase(java.util.Locale.ROOT);
        }
        catch (Exception ignored)
        {
            return null;
        }
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
            HelperGeneric.logI(TAG, "set_group_group_we_left:EE:" + e.getMessage());
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
            // HelperGeneric.logI(TAG, "clear_group_group_we_left:EE:" + e.getMessage());
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
            HelperGeneric.logI(TAG, "set_group_active:EE:" + e.getMessage());
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
            // HelperGeneric.logI(TAG, "set_group_inactive:EE:" + e.getMessage());
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
            reconnect_group_if_disconnected(group_num, group_identifier);
            sync_group_peers_from_tox_to_db(group_num);
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
            HelperGeneric.logI(TAG, "ensure_group_in_tox:join=" + new_group_num + " id=" + group_identifier);

            if (new_group_num < 0)
            {
                new_group_num = tox_group_by_groupid__wrapper(group_identifier);
                HelperGeneric.logI(TAG, "ensure_group_in_tox:resolve_existing=" + new_group_num);
            }

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
                reconnect_group_if_disconnected(new_group_num, group_identifier);
                sync_group_peers_from_tox_to_db(new_group_num);
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

        long group_num = tox_group_by_groupid__wrapper(group_identifier);
        if (group_num < 0)
        {
            group_num = ensure_group_in_tox(group_identifier);
        }
        if (group_num < 0)
        {
            return context_s.getString(R.string.group_send_group_not_found);
        }

        final int conn = tox_group_is_connected(group_num);
        if (conn != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            schedule_group_auto_reconnect(group_num, group_identifier);
            TrifaToxService.wakeup_tox_thread();
            if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING.value)
            {
                return null;
            }
        }

        int role = tox_group_self_get_role(group_num);
        if (role < 0)
        {
            role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
        }

        if (role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value)
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

        HelperGeneric.logI(TAG, "group_invite_failure_message:errCode=" + errCode);
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
        HelperGeneric.logI(TAG, "invite_friend_to_group:group=" + group_identifier + " friend=" + friend_public_key + " res=" + res);

        if (res == 1)
        {
            update_savedata_file_wrapper();
            display_toast(context_s.getString(R.string.group_invite_friend_success), false, 300);
            return true;
        }

        // KHANDAQ: friend and group both existed (checked above), so this is a transient send failure —
        // typically the friend flapped offline for an instant (INVITE_FAIL). Queue the invite and retry
        // on friend-online + timers instead of dropping it, mirroring how text messages get delivered
        // on reconnect.
        queue_manual_group_invite(group_identifier, friend_public_key);
        display_toast(context_s.getString(R.string.group_invite_friend_queued), false, 300);
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

        if (message_id == GROUP_SEND_QUEUE_WHEN_UNCONNECTED)
        {
            return context_s.getString(R.string.group_send_not_connected);
        }

        if (message_id == -99)
        {
            return context_s.getString(R.string.group_send_not_connected);
        }

        return context_s.getString(R.string.group_send_failed);
    }

    /** Return from send_group_text_message_resilient: queue in DB, flush when group connects. */
    public static final long GROUP_SEND_QUEUE_WHEN_UNCONNECTED = -98L;
    public static final String PENDING_GROUP_MESSAGE_ID_TOX = "00000000";

    private static final int GROUP_SEND_MAX_ATTEMPTS = 3;
    private static final long GROUP_SEND_PEER_RETRY_MS = 150L;
    private static final int GROUP_CONNECT_MAX_ATTEMPTS = 5;
    private static final ConcurrentHashMap<String, Integer> group_connect_attempt_count = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> group_connect_started_ms = new ConcurrentHashMap<>();

    static void begin_group_connect_attempt(@NonNull final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return;
        }
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        group_connect_started_ms.put(key, System.currentTimeMillis());
        group_connect_attempt_count.put(key, 1);
    }

    static void bump_group_connect_attempt(@NonNull final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return;
        }
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        group_connect_attempt_count.merge(key, 1, (a, b) -> Math.min(GROUP_CONNECT_MAX_ATTEMPTS, a + b));
    }

    static int get_group_connect_attempt(@NonNull final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return 0;
        }
        return group_connect_attempt_count.getOrDefault(group_identifier.toLowerCase(Locale.ENGLISH), 0);
    }

    static void clear_group_connect_progress(@NonNull final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return;
        }
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        group_connect_attempt_count.remove(key);
        group_connect_started_ms.remove(key);
        group_post_join_discovery_until_ms.remove(key);
    }

    static long group_connect_elapsed_ms(@NonNull final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return 0L;
        }
        final Long started = group_connect_started_ms.get(group_identifier.toLowerCase(Locale.ENGLISH));
        if (started == null)
        {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - started);
    }

    static long group_connect_reconnect_backoff_ms(@NonNull final String group_identifier)
    {
        final int attempt = Math.max(1, get_group_connect_attempt(group_identifier));
        // KHANDAQ #25: retry a stalled announce sooner (first retry at 6s instead of 15s) so
        // joining/reconnecting feels faster, still backing off to 96s to spare the DHT.
        return Math.min(120_000L, 6_000L * (1L << Math.min(attempt - 1, 4)));
    }

    static long count_group_sync_target_peers(final long group_num)
    {
        if (group_num < 0)
        {
            return 0L;
        }
        try
        {
            final long self_peer_id = tox_group_self_get_peer_id(group_num);
            final long[] peers = tox_group_get_peerlist(group_num);
            if (peers == null)
            {
                return 0L;
            }
            long sync_targets = 0L;
            for (long peer_id : peers)
            {
                if (peer_id >= 0 && peer_id != self_peer_id)
                {
                    sync_targets++;
                }
            }
            return sync_targets;
        }
        catch (Exception ignored)
        {
            return 0L;
        }
    }

    static long count_group_db_members(final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return 0L;
        }
        try
        {
            long count = 0L;
            final List<GroupPeerDB> db_peers = orma.selectFromGroupPeerDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList();
            for (GroupPeerDB db_peer : db_peers)
            {
                if (db_peer == null || db_peer.tox_group_peer_pubkey == null)
                {
                    continue;
                }
                if (should_hide_peer_from_member_lists(db_peer.peer_name, db_peer.tox_group_peer_pubkey))
                {
                    continue;
                }
                count++;
            }
            return count;
        }
        catch (Exception ignored)
        {
            return 0L;
        }
    }

    static String humanize_tox_connection(final int connection_status)
    {
        if (connection_status == ToxVars.TOX_CONNECTION.TOX_CONNECTION_UDP.value)
        {
            return "direct";
        }
        if (connection_status == ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP.value)
        {
            return "relay";
        }
        return "none";
    }

    /** Fan-out reachability snapshot for one group (transport paths to other peerlist entries). */
    static final class GroupMeshSnapshot
    {
        int listed;
        int direct;
        int relay;
        int none;
        int fanout;
        int groupConn;

        boolean isFullyUnreachable()
        {
            return groupConn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value
                   && listed > 0 && fanout == 0;
        }

        boolean isPartiallyDegraded()
        {
            return groupConn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value
                   && listed > 0 && fanout > 0 && fanout < listed;
        }

        boolean isDeliveryDegraded()
        {
            return isFullyUnreachable() || isPartiallyDegraded();
        }
    }

    @NonNull
    static GroupMeshSnapshot compute_group_mesh_snapshot(final long group_num)
    {
        final GroupMeshSnapshot snap = new GroupMeshSnapshot();
        if (group_num < 0)
        {
            return snap;
        }
        try
        {
            snap.groupConn = tox_group_is_connected(group_num);
            final long self_peer_id = tox_group_self_get_peer_id(group_num);
            final long[] peers = tox_group_get_peerlist(group_num);
            if (peers == null)
            {
                return snap;
            }
            for (final long peer_id : peers)
            {
                if (peer_id < 0 || peer_id == self_peer_id)
                {
                    continue;
                }
                ++snap.listed;
                final int conn = tox_group_peer_get_connection_status(group_num, peer_id);
                if (conn == ToxVars.TOX_CONNECTION.TOX_CONNECTION_UDP.value)
                {
                    ++snap.direct;
                }
                else if (conn == ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP.value)
                {
                    ++snap.relay;
                }
                else
                {
                    ++snap.none;
                }
            }
            snap.fanout = snap.direct + snap.relay;
        }
        catch (Exception ignored)
        {
        }
        return snap;
    }

    static boolean is_group_mesh_degraded(final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return false;
        }
        final GroupMeshSnapshot snap = group_mesh_snapshot_cache.get(group_identifier.toLowerCase(Locale.ENGLISH));
        return snap != null && snap.isDeliveryDegraded();
    }

    static boolean is_group_mesh_fully_unreachable(final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return false;
        }
        final GroupMeshSnapshot snap = group_mesh_snapshot_cache.get(group_identifier.toLowerCase(Locale.ENGLISH));
        return snap != null && snap.isFullyUnreachable();
    }

    /** Fan-out reachability: how many peerlist entries have direct/relay vs none (NGC sends to connected peers). */
    static void log_group_peer_mesh_diagnostics(final long group_num, final String group_identifier,
                                                final String reason)
    {
        if (group_num < 0 || TextUtils.isEmpty(group_identifier))
        {
            return;
        }
        try
        {
            final GroupMeshSnapshot snap = compute_group_mesh_snapshot(group_num);
            group_mesh_snapshot_cache.put(group_identifier.toLowerCase(Locale.ENGLISH), snap);
            final long self_peer_id = tox_group_self_get_peer_id(group_num);
            final long[] peers = tox_group_get_peerlist(group_num);
            if (peers != null)
            {
                for (final long peer_id : peers)
                {
                    if (peer_id < 0 || peer_id == self_peer_id)
                    {
                        continue;
                    }
                    final int conn = tox_group_peer_get_connection_status(group_num, peer_id);
                    HelperGeneric.logI(TAG, "group_peer_mesh:peer gn=" + group_num + " pid=" + peer_id
                            + " conn=" + humanize_tox_connection(conn) + " reason=" + reason);
                }
            }
            HelperGeneric.logI(TAG, "group_peer_mesh:summary gn=" + group_num + " id="
                    + group_identifier_short(group_identifier, false) + " reason=" + reason
                    + " group_conn=" + snap.groupConn
                    + " tox_peers=" + tox_group_peer_count(group_num) + " listed=" + snap.listed
                    + " fanout=" + snap.fanout + " direct=" + snap.direct + " relay=" + snap.relay
                    + " none=" + snap.none
                    + (snap.isDeliveryDegraded() ? " DEGRADED" : ""));
        }
        catch (Exception e)
        {
            Log.w(TAG, "log_group_peer_mesh_diagnostics:EE:" + e.getMessage());
        }
    }

    /** DEBUG QA: dump mesh for one group (hex id) or all tox groups if id is null. */
    static void qa_dump_group_mesh(@Nullable final String group_id_hex)
    {
        if (!is_tox_started)
        {
            return;
        }
        if (group_id_hex != null && !group_id_hex.isEmpty())
        {
            final long gn = tox_group_by_groupid__wrapper(group_id_hex);
            if (gn >= 0)
            {
                log_group_peer_mesh_diagnostics(gn, group_id_hex, "qa_dump");
            }
            return;
        }
        final long[] groups = MainActivity.tox_group_get_grouplist();
        if (groups == null)
        {
            return;
        }
        for (final long gn : groups)
        {
            if (gn < 0)
            {
                continue;
            }
            final String id = tox_group_by_groupnum__wrapper(gn);
            if (id != null)
            {
                log_group_peer_mesh_diagnostics(gn, id, "qa_dump");
            }
        }
    }

    /** DEBUG QA: reactivate inactive DB rows and kickstart alone/stuck groups. */
    static void qa_revive_all_stuck_groups()
    {
        if (!is_tox_started)
        {
            return;
        }
        final long[] groups = MainActivity.tox_group_get_grouplist();
        if (groups == null)
        {
            return;
        }
        for (final long gn : groups)
        {
            if (gn < 0)
            {
                continue;
            }
            final String id = tox_group_by_groupnum__wrapper(gn);
            if (id == null || is_group_we_left(id))
            {
                continue;
            }
            set_group_active(id);
            final int conn = tox_group_is_connected(gn);
            final long peers = Math.max(0L, tox_group_peer_count(gn));
            if (peers <= 1L
                || conn != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                send_group_invite_request_to_friends(id);
                kickstart_group_connection(gn, id);
            }
            log_group_peer_mesh_diagnostics(gn, id, "qa_revive");
        }
        TrifaToxService.wakeup_tox_thread();
    }

    static void log_group_send_diagnostics(final long group_num, final String group_identifier, final int attempt)
    {
        final long tox_peers = Math.max(0L, tox_group_peer_count(group_num));
        final long sync_targets = count_group_sync_target_peers(group_num);
        final long db_members = count_group_db_members(group_identifier);
        HelperGeneric.logI(TAG, "group_send_peers:gn=" + group_num + " id=" + group_identifier_short(group_identifier, false)
                + " attempt=" + attempt + " conn=" + tox_group_is_connected(group_num)
                + " tox_peers=" + tox_peers + " sync_targets=" + sync_targets + " db_members=" + db_members);
        log_group_peer_mesh_diagnostics(group_num, group_identifier, "send_attempt=" + attempt);
    }

    static long send_group_text_message_resilient(@NonNull final String group_identifier, final long group_number,
                                                @NonNull final String message)
    {
        long group_num = group_number;
        for (int attempt = 0; attempt < GROUP_SEND_MAX_ATTEMPTS; attempt++)
        {
            if (group_num < 0)
            {
                group_num = ensure_group_in_tox(group_identifier);
            }

            if (group_num < 0)
            {
                continue;
            }

            log_group_send_diagnostics(group_num, group_identifier, attempt);

            final int conn = tox_group_is_connected(group_num);
            if (conn != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                schedule_group_auto_reconnect(group_num, group_identifier);
                nudge_public_group_dht(group_num, group_identifier);
                TrifaToxService.wakeup_tox_thread();
                if (attempt + 1 >= GROUP_SEND_MAX_ATTEMPTS)
                {
                    return GROUP_SEND_QUEUE_WHEN_UNCONNECTED;
                }
                try
                {
                    Thread.sleep(GROUP_SEND_PEER_RETRY_MS);
                }
                catch (InterruptedException ignored)
                {
                    break;
                }
                continue;
            }

            final long message_id = tox_group_send_message(group_num, 0, message);
            if (HelperFriend.is_group_text_send_success(message_id))
            {
                global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
                clear_group_connect_progress(group_identifier);
                log_group_send_diagnostics(group_num, group_identifier, attempt);
                HelperGeneric.logI(TAG, "group_msg_send:ts=" + System.currentTimeMillis() + " gn=" + group_num
                        + " gid=" + group_identifier_short(group_identifier, false) + " msg_id=" + message_id
                        + " peers=" + tox_group_peer_count(group_num) + " conn=" + tox_group_is_connected(group_num)
                        + (message.length() <= 64 ? " text=" + message : ""));
                HelperGeneric.logI(TAG, "send_group_text_message_resilient:ok gn=" + group_num + " id=" + group_identifier
                        + " peers=" + tox_group_peer_count(group_num) + " attempt=" + attempt
                        + " conn=" + tox_group_is_connected(group_num) + " msg_id=" + message_id);
                schedule_group_message_resync(group_num);
                return message_id;
            }

            if (message_id == -2 || message_id == -3 || message_id == -4)
            {
                return message_id;
            }

            TrifaToxService.wakeup_tox_thread();
            if (attempt + 1 >= GROUP_SEND_MAX_ATTEMPTS)
            {
                return GROUP_SEND_QUEUE_WHEN_UNCONNECTED;
            }
            try
            {
                Thread.sleep(GROUP_SEND_PEER_RETRY_MS);
            }
            catch (InterruptedException ignored)
            {
                break;
            }
        }

        return GROUP_SEND_QUEUE_WHEN_UNCONNECTED;
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

    /** Former auto-joined public group; stripped from the app in v0.2.7. */
    private static final String REMOVED_LEGACY_PUBLIC_COMMUNITY_GROUPID =
            "154b3973bd0e66304fd6179a8a54759073649e09e6e368f0334fc6ed666ab762";

    static boolean is_removed_legacy_public_community_group(final String group_identifier)
    {
        if (group_identifier == null)
        {
            return false;
        }
        return REMOVED_LEGACY_PUBLIC_COMMUNITY_GROUPID.equalsIgnoreCase(group_identifier.trim());
    }

    /** Leave tox group, mark left in DB, hide from chat list. */
    static void remove_legacy_public_community_group()
    {
        if (!is_tox_started || orma == null)
        {
            return;
        }

        final String group_identifier = REMOVED_LEGACY_PUBLIC_COMMUNITY_GROUPID;
        try
        {
            set_group_group_we_left(group_identifier);
            final long group_num = tox_group_by_groupid__wrapper(group_identifier);
            if (group_num >= 0)
            {
                tox_group_leave(group_num, "removed");
                update_savedata_file_wrapper();
            }
            HelperGeneric.logI(TAG, "remove_legacy_public_community_group:done gn=" + group_num);
        }
        catch (Exception e)
        {
            Log.w(TAG, "remove_legacy_public_community_group:EE:" + e.getMessage());
        }
    }

    /** Drop tox groups the user left or that are inactive in DB — stale savedata poisons DHT/mesh. */
    static void prune_stale_tox_groups()
    {
        if (!is_tox_started || orma == null)
        {
            return;
        }
        try
        {
            final long num_groups = MainActivity.tox_group_get_number_groups();
            if (num_groups <= 0)
            {
                return;
            }
            final long[] group_numbers = MainActivity.tox_group_get_grouplist();
            if (group_numbers == null)
            {
                return;
            }

            for (long group_num : group_numbers)
            {
                if (group_num < 0)
                {
                    continue;
                }
                final String group_identifier = tox_group_by_groupnum__wrapper(group_num);
                if (group_identifier == null || is_removed_legacy_public_community_group(group_identifier))
                {
                    continue;
                }

                boolean should_leave = false;
                try
                {
                    final GroupDB row = orma.selectFromGroupDB().
                            group_identifierEq(group_identifier.toLowerCase()).get(0);
                    should_leave = row.group_we_left || !row.group_active;
                }
                catch (Exception not_in_db)
                {
                    // KHANDAQ #202: a group present in tox but ABSENT from GroupDB is the NORMAL state right
                    // after an import (REPLACE wipes GroupDB; FIRST_LAUNCH starts empty). Leaving it here
                    // silently dropped every imported group — the "контакты группы пропадают" report. Keep it.
                    should_leave = false;
                }

                if (should_leave)
                {
                    HelperGeneric.logI(TAG, "prune_stale_tox_groups:leave gn=" + group_num + " id=" + group_identifier);
                    tox_group_leave(group_num, "pruned");
                    try
                    {
                        set_group_group_we_left(group_identifier);
                        set_group_inactive(group_identifier);
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
            update_savedata_file_wrapper();
        }
        catch (Exception e)
        {
            Log.w(TAG, "prune_stale_tox_groups:EE:" + e.getMessage());
        }
    }

    static String get_group_display_name(final String group_identifier, final String raw_name)
    {
        if (raw_name == null)
        {
            return "";
        }
        return raw_name;
    }

    static boolean should_show_group_system_messages(final String group_identifier)
    {
        return PREF__conference_show_system_messages;
    }

    static String format_group_member_count_subtitle(final Context context, final long online_count,
                                                     final long offline_count)
    {
        final long total = Math.max(0L, online_count) + Math.max(0L, offline_count);
        return context.getString(R.string.group_header_member_count, total, Math.max(0L, online_count));
    }

    static String format_group_list_status_subtitle(final Context context, final String group_identifier)
    {
        final String connecting = format_group_connecting_status_subtitle(context, group_identifier);
        if (connecting != null && !connecting.isEmpty())
        {
            return connecting;
        }
        if (is_group_mesh_degraded(group_identifier))
        {
            return context.getString(R.string.group_header_reconnecting_peers);
        }
        final long[] counts = count_authoritative_group_members(group_identifier);
        return format_group_member_count_subtitle(context, counts[0], counts[1]);
    }

    /**
     * KHANDAQ (#9): member counts taken straight from toxcore's own NGC group state —
     * tox_group_peer_count() = confirmed peers currently online (includes self) and
     * tox_group_offline_peer_count() = saved members that are currently disconnected. Both are part
     * of the group state that NGC synchronises between peers, so every client converges on the SAME
     * "N members · M online" instead of each device deriving a different total from its own local
     * seen-peer DB (which is what made the counts disagree across clients). Cheaper than walking the
     * full peer list too. Falls back to the DB-derived roster when toxcore has no live handle yet.
     */
    // KHANDAQ (#22): this runs in the group-list row bind (UI thread, under the tox lock), so on the
    // chat/group list it was called for every visible row on every rebind/scroll — 3 JNI calls each,
    // which froze older phones. Cache the result per group for a short window: a list count does not
    // need sub-second freshness, and a scroll/tab-switch burst now reuses one computation per group.
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> ngc_member_count_cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> ngc_member_count_cache_ts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long NGC_MEMBER_COUNT_CACHE_TTL_MS = 1500L;

    static long[] count_authoritative_group_members(final String group_identifier)
    {
        if (group_identifier == null)
        {
            return new long[]{0L, 0L};
        }

        final String key = group_identifier.toLowerCase(Locale.ROOT);
        final long now = System.currentTimeMillis();
        final Long ts = ngc_member_count_cache_ts.get(key);
        if (ts != null && (now - ts) < NGC_MEMBER_COUNT_CACHE_TTL_MS)
        {
            final long[] cached = ngc_member_count_cache.get(key);
            if (cached != null)
            {
                return cached;
            }
        }

        long[] result = null;
        try
        {
            final long group_num = tox_group_by_groupid__wrapper(group_identifier);
            if (group_num >= 0)
            {
                final long online = Math.max(0L, tox_group_peer_count(group_num));
                final long offline = Math.max(0L, tox_group_offline_peer_count(group_num));
                if (online > 0 || offline > 0)
                {
                    result = new long[]{online, offline};
                }
            }
        }
        catch (Throwable ignored)
        {
        }

        if (result == null)
        {
            result = count_visible_group_members(group_identifier);
        }

        ngc_member_count_cache.put(key, result);
        ngc_member_count_cache_ts.put(key, now);
        return result;
    }

    static String format_group_connecting_status_subtitle(final Context context, final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier) || context == null)
        {
            return null;
        }
        if (!is_group_join_still_in_progress(group_identifier))
        {
            return null;
        }
        return context.getString(R.string.group_header_reconnecting_peers);
    }

    static boolean is_group_join_still_in_progress(final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return false;
        }
        if (is_group_peer_discovery_in_progress(group_identifier))
        {
            return true;
        }
        try
        {
            final long group_num = tox_group_by_groupid__wrapper(group_identifier);
            if (group_num < 0)
            {
                return get_group_connect_attempt(group_identifier) > 0;
            }
            final int conn = tox_group_is_connected(group_num);
            // CONNECTED (even alone as founder) is not "still connecting" — show member count instead.
            if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                clear_group_connect_progress(group_identifier);
                return false;
            }
            if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING.value)
            {
                return is_group_peer_discovery_in_progress(group_identifier);
            }
            if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_ERROR.value)
            {
                return get_group_connect_attempt(group_identifier) > 0
                        && group_connect_elapsed_ms(group_identifier) < 90_000L;
            }
            return false;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static boolean should_refresh_group_connect_header(final String group_identifier)
    {
        return is_group_join_still_in_progress(group_identifier)
               || is_group_mesh_degraded(group_identifier);
    }

    static String humanize_group_connection_status(final Context context, final int connection_status)
    {
        return humanize_group_connection_status(context, connection_status, null);
    }

    static String humanize_group_connection_status(final Context context, final int connection_status,
                                                   @Nullable final String group_identifier)
    {
        if (connection_status == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            if (group_identifier != null && is_group_mesh_degraded(group_identifier))
            {
                return context.getString(R.string.group_header_reconnecting_peers);
            }
            return context.getString(R.string.group_info_connection_connected);
        }
        if (connection_status == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING.value)
        {
            return context.getString(R.string.group_info_connection_connecting);
        }
        return context.getString(R.string.group_info_connection_disconnected);
    }

    static String humanize_group_role(final Context context, final int role)
    {
        if (role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value)
        {
            return context.getString(R.string.group_info_role_founder);
        }
        if (role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value)
        {
            return context.getString(R.string.group_info_role_moderator);
        }
        if (role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value)
        {
            return context.getString(R.string.group_info_role_observer);
        }
        return context.getString(R.string.group_info_role_user);
    }

    static String humanize_group_privacy(final Context context, final int privacy_state)
    {
        if (privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            return context.getString(R.string.group_info_privacy_public);
        }
        if (privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value)
        {
            return context.getString(R.string.group_info_privacy_private);
        }
        return context.getString(R.string.group_info_privacy_unknown);
    }

    static final class GroupMemberDisplay
    {
        String pubkey;
        String name;
        boolean online;
        boolean self;
        boolean probable_bot;
        int connection_status;
        long last_seen_ms;
    }

    private static final ConcurrentHashMap<String, Long> group_peer_last_online_ms = new ConcurrentHashMap<>();

    // KHANDAQ (#50): group-peer presence is LEVEL-triggered (polled every render, no persistence),
    // unlike the EDGE-triggered + persisted 1:1 status, so a peer flickers offline during the brief
    // peer_id-rejoin / relay-flap gaps NGC churn produces. Treat a peer online if toxcore reports
    // UDP/TCP now OR it was seen live within this grace window — bounded, driven only by real sightings.
    private static final long GROUP_PEER_ONLINE_GRACE_MS = 25_000L;

    static String group_peer_seen_key(final String group_id, final String pubkey)
    {
        return group_id.toLowerCase(Locale.ENGLISH) + "|" + pubkey.toLowerCase(Locale.ENGLISH);
    }

    static void touch_group_peer_online(final String group_id, final String pubkey)
    {
        if ((group_id == null) || (pubkey == null))
        {
            return;
        }
        group_peer_last_online_ms.put(group_peer_seen_key(group_id, pubkey), System.currentTimeMillis());
        retry_pending_incoming_group_files_for_peer(group_id, pubkey);
        // KHANDAQ (#247): outgoing needed the same trigger and did not have it. A peer coming back
        // only ever revived INCOMING transfers; the outgoing sweep hung off on_group_connected, so a
        // file queued while the group was already connected but the peer absent was never
        // reconsidered — that callback had long since fired and does not fire again. The row then sat
        // at "connecting" forever. retry_pending_outgoing_group_files' own comment already claims it
        // runs on "group reconnect / peer online", so the intent was there; only the wiring was not.
        if (should_run_group_maintenance(group_id, group_last_outgoing_file_sweep_ms,
                                         GROUP_OUTGOING_FILE_SWEEP_MIN_INTERVAL_MS))
        {
            retry_pending_outgoing_group_files(group_id);
        }
    }

    // A peer sighting is a frequent event - NGC presence flickers constantly (see #84) - and the
    // outgoing sweep costs a DB select plus a blocking tombstone lookup per row, so the new trigger
    // is throttled per group rather than run on every sighting. 15s matches the per-message
    // auto-retry cooldown the sweep feeds into, so a tighter interval could not act any sooner.
    private static final long GROUP_OUTGOING_FILE_SWEEP_MIN_INTERVAL_MS = 15_000L;
    private static final ConcurrentHashMap<String, Long> group_last_outgoing_file_sweep_ms =
            new ConcurrentHashMap<>();

    static void record_group_peer_last_seen_on_exit(final long group_number, final long peer_id)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            final String pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);
            if (TextUtils.isEmpty(group_identifier) || TextUtils.isEmpty(pubkey))
            {
                return;
            }
            final long now = System.currentTimeMillis();
            touch_group_peer_online(group_identifier, pubkey);
            orma.updateGroupPeerDB().group_identifierEq(group_identifier.toLowerCase()).
                    tox_group_peer_pubkeyEq(pubkey).
                    last_update_timestamp(now).
                    execute();
        }
        catch (Exception ignored)
        {
        }
    }

    static long resolve_group_peer_last_seen_ms(final String group_id, final String pubkey, final GroupPeerDB db)
    {
        Long from_memory = group_peer_last_online_ms.get(group_peer_seen_key(group_id, pubkey));
        final long from_db = db != null ? db.last_update_timestamp : 0L;
        if (from_memory != null && from_memory > from_db)
        {
            return from_memory;
        }
        if (from_db > 0L)
        {
            return from_db;
        }
        return from_memory != null ? from_memory : 0L;
    }

    static boolean is_group_peer_online(final int connection_status)
    {
        return connection_status == ToxVars.TOX_CONNECTION.TOX_CONNECTION_UDP.value
               || connection_status == ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP.value;
    }

    static boolean is_group_message_sender_online(final GroupMessage message)
    {
        if ((message == null) || (message.group_identifier == null) || (message.tox_group_peer_pubkey == null))
        {
            return false;
        }

        final long group_num = tox_group_by_groupid__wrapper(message.group_identifier);
        if (group_num < 0)
        {
            return false;
        }

        final long peer_id = get_group_peernum_from_peer_pubkey(message.group_identifier,
                message.tox_group_peer_pubkey);
        if (peer_id < 0)
        {
            return tox_group_is_connected(group_num) ==
                   TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value;
        }

        final int conn = tox_group_peer_get_connection_status(group_num, peer_id);
        if (is_group_peer_online(conn))
        {
            return true;
        }
        // KHANDAQ (#50): grace window so the message-sender dot matches the member-list presence.
        final long last_seen = resolve_group_peer_last_seen_ms(message.group_identifier,
                message.tox_group_peer_pubkey, null);
        return last_seen > 0 && (System.currentTimeMillis() - last_seen) <= GROUP_PEER_ONLINE_GRACE_MS;
    }

    /**
     * All other online peers in the group — chunked FT uses private fan-out to every one
     * (ghost/stale peer ids are common; picking a single lowest id often misses the real peer).
     */
    // KHANDAQ (#126): when per-peer NGC status reads NONE for everyone, unicast to all peers only if the group
    // is this small — protects large groups from a blind lossless fan-out to dozens of members.
    private static final int MAX_UNICAST_FANOUT_WHEN_STATUS_UNKNOWN = 8;

    static long[] pickChunkedFileUnicastPeerIds(final long groupNum)
    {
        if (groupNum < 0)
        {
            return new long[0];
        }
        try
        {
            final long selfPeerId = tox_group_self_get_peer_id(groupNum);
            final long[] peers = tox_group_get_peerlist(groupNum);
            if (peers == null)
            {
                return new long[0];
            }
            final java.util.ArrayList<Long> online = new java.util.ArrayList<>();
            final java.util.ArrayList<Long> reachable = new java.util.ArrayList<>();
            for (final long peerId : peers)
            {
                if (peerId < 0 || peerId == selfPeerId)
                {
                    continue;
                }
                reachable.add(peerId);
                if (!is_group_peer_online(tox_group_peer_get_connection_status(groupNum, peerId)))
                {
                    continue;
                }
                online.add(peerId);
            }
            // KHANDAQ (#126): NGC per-peer connection status frequently reads NONE even for reachable peers
            // (packets route via the group mesh), which leaves 'online' empty -> the sender latches onto a
            // ghost id / empty target and the receiver gets ~0 chunks on the first pass, limping along on slow
            // NACK recovery. When the GROUP itself is connected and SMALL, unicast to every real peer regardless
            // of cached per-peer status (a private send self-verifies via its return code). Bounded to small
            // groups so large groups keep the status-gated selection (no blind lossless fan-out to dozens).
            if (online.isEmpty() && !reachable.isEmpty()
                    && reachable.size() <= MAX_UNICAST_FANOUT_WHEN_STATUS_UNKNOWN
                    && tox_group_is_connected(groupNum)
                            == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                java.util.Collections.sort(reachable);
                final long[] out = new long[reachable.size()];
                for (int i = 0; i < reachable.size(); i++)
                {
                    out[i] = reachable.get(i);
                }
                HelperGeneric.logI(TAG, "pickChunkedFileUnicastPeerIds:status-blind fallback peers=" + reachable.size());
                return out;
            }
            java.util.Collections.sort(online);
            final long[] out = new long[online.size()];
            for (int i = 0; i < online.size(); i++)
            {
                out[i] = online.get(i);
            }
            return out;
        }
        catch (Exception ignored)
        {
            return new long[0];
        }
    }

    /**
     * @return first online peer, or {@code -1} if none
     */
    static long pickChunkedFileUnicastPeerId(final long groupNum)
    {
        final long[] ids = pickChunkedFileUnicastPeerIds(groupNum);
        return ids.length > 0 ? ids[0] : -1L;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> group_file_auto_retry_ts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final long GROUP_FILE_AUTO_RETRY_COOLDOWN_MS = 15_000L;

    static void schedule_auto_retry_group_file_download(final GroupMessage message)
    {
        schedule_auto_retry_group_file(message, false);
    }

    static void schedule_auto_retry_group_file_send(final GroupMessage message)
    {
        schedule_auto_retry_group_file(message, true);
    }

    private static void schedule_auto_retry_group_file(final GroupMessage message, final boolean outgoing)
    {
        if ((message == null) || (message.group_identifier == null) || (message.msg_id_hash == null))
        {
            return;
        }
        if (HelperFiletransfer.isGroupMessageMediaReady(message, null))
        {
            return;
        }
        if (!outgoing && !is_group_message_sender_online(message))
        {
            return;
        }

        final String key = ngc_file_progress_key(message.group_identifier, message.msg_id_hash);
        final long now = System.currentTimeMillis();
        final Long last = group_file_auto_retry_ts.get(key);
        if ((last != null) && ((now - last) < GROUP_FILE_AUTO_RETRY_COOLDOWN_MS))
        {
            return;
        }
        group_file_auto_retry_ts.put(key, now);

        if (main_handler_s != null)
        {
            main_handler_s.post(() ->
            {
                if (outgoing)
                {
                    // KHANDAQ (audit2 #5): automatic path — must NOT lift a user cancel.
                    resume_group_file_send(message);
                }
                else
                {
                    retry_group_incoming_file_download(message);
                }
            });
        }
    }

    static void retry_pending_incoming_group_files_for_peer(final String groupId, final String peerPubkey)
    {
        if ((groupId == null) || (peerPubkey == null) || (orma == null))
        {
            return;
        }

        try
        {
            final List<GroupMessage> pending = orma.selectFromGroupMessage().
                    group_identifierEq(groupId.toLowerCase(Locale.ROOT)).
                    tox_group_peer_pubkeyEq(peerPubkey).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    directionEq(0).
                    toList();

            for (GroupMessage gm : pending)
            {
                if (!HelperFiletransfer.isGroupMessageMediaReady(gm, null))
                {
                    // KHANDAQ (#84): route through the 15s-cooldown'd scheduler instead of calling
                    // retry directly. NGC peer presence flickers constantly, and an unguarded retry on
                    // every sighting re-requested the in-flight download over and over. The scheduler
                    // also re-checks sender-online before firing.
                    schedule_auto_retry_group_file_download(gm);
                }
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "retry_pending_incoming_group_files_for_peer:EE:" + e.getMessage());
        }
    }

    static boolean is_ngc_transfer_started(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        return ngc_transfer_start_ts.containsKey(ngc_file_progress_key(groupId, msgIdHash));
    }

    static void retry_pending_outgoing_group_files(final String groupId)
    {
        if ((groupId == null) || (orma == null))
        {
            return;
        }

        try
        {
            final List<GroupMessage> outgoing = orma.selectFromGroupMessage().
                    group_identifierEq(groupId.toLowerCase(Locale.ROOT)).
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    toList();

            if (outgoing == null)
            {
                return;
            }

            for (GroupMessage gm : outgoing)
            {
                if (HelperFiletransfer.isGroupMessageMediaReady(gm, null))
                {
                    continue;
                }
                // KHANDAQ (audit2 #5): this is the auto-resume after a crash/restart, and it is the
                // reason the tombstone is read lazily from the DB rather than kept in memory only.
                // KHANDAQ (audit3 #2): describing the guard as "skips user cancels" was wrong — the
                // call below is is_ngc_send_cancelled(), which skips a row for THREE reasons:
                //   1. the user cancelled it -> persisted tombstone, must stay cancelled forever;
                //   2. it was superseded in this session by a newer send for the same group
                //      (cancelStaleOutgoingChunkedSends -> ngc_cancelled_sends). That map is memory
                //      only, so after a restart such a row is not skipped and does auto-resume here;
                //   3. the tombstone row could not be read at all right now (audit3 #1 fails closed).
                //      Nothing is cached in that case, so the row is simply reconsidered the next time
                //      this runs (group reconnect / peer online) instead of being sent blind.
                // An honest interrupted upload is in none of those states and still reaches the
                // scheduler below, which is what makes crash-then-resume work.
                if (is_ngc_send_cancelled(gm.group_identifier, gm.msg_id_hash))
                {
                    continue;
                }
                clear_ngc_file_transfer_failed(gm.group_identifier, gm.msg_id_hash);
                schedule_auto_retry_group_file_send(gm);
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "retry_pending_outgoing_group_files:EE:" + e.getMessage());
        }
    }

    static void retry_group_incoming_file_download(final GroupMessage message)
    {
        if ((message == null) || (message.group_identifier == null) || (message.msg_id_hash == null))
        {
            return;
        }
        if (HelperFiletransfer.isGroupMessageMediaReady(message, null))
        {
            return;
        }
        // KHANDAQ (#120): respect the attachment-download policy for the auto-retry callers (peer-online
        // resume, stall watchdog, scheduler). The manual onRetry path sets the bypass marker before
        // calling, so a user-initiated download is never blocked here.
        if (!ngc_incoming_download_allowed(message.group_identifier, message.msg_id_hash))
        {
            return;
        }

        clear_ngc_file_transfer_failed(message.group_identifier, message.msg_id_hash);

        if (NgcGroupFileTransfer.shouldUseChunkedTransfer(message.filesize))
        {
            // Resumable: keep partial progress (prepareRetryReceive is non-destructive); never reset UI to 0.
            NgcGroupFileTransfer.prepareRetryReceive(message);
            // KHANDAQ (#84): do NOT call record_ngc_transfer_started here — it removes
            // ngc_file_transfer_bytes_done (the map that drives the % bar), so every retry tick snapped
            // a healthy 90% download back to 0% and re-streamed from chunk 0. On RESUME we only bump the
            // last-progress timestamp (so the stall watchdog backs off) and re-issue the partial resend
            // request; the assembled chunks and byte count are preserved.
            touch_ngc_transfer_progress(message.group_identifier, message.msg_id_hash);
            NgcGroupFileTransfer.sendFileResendRequest(message);
            arm_ngc_incoming_file_timeout(message.group_identifier, message.msg_id_hash);
            post_group_file_progress_refresh(message.group_identifier, message.msg_id_hash);
            TrifaToxService.wakeup_tox_thread();
            return;
        }

        final long group_num = tox_group_by_groupid__wrapper(message.group_identifier);
        if (group_num < 0)
        {
            return;
        }

        final long sender_peer = get_group_peernum_from_peer_pubkey(message.group_identifier,
                message.tox_group_peer_pubkey);
        if (sender_peer >= 0
                && is_group_peer_online(tox_group_peer_get_connection_status(group_num, sender_peer)))
        {
            sync_group_message_history(group_num, sender_peer);
        }
        else
        {
            request_history_sync_from_transport_peers(group_num, message.group_identifier);
        }

        arm_ngc_incoming_file_timeout(message.group_identifier, message.msg_id_hash);
        post_group_file_progress_refresh(message.group_identifier, message.msg_id_hash);
        TrifaToxService.wakeup_tox_thread();
    }

    static boolean is_likely_automated_test_peer(final String name, final String pubkey)
    {
        if (pubkey != null && lookup_friendlist_by_pubkey(pubkey.trim()) != null)
        {
            return false;
        }

        if (name != null)
        {
            final String n = name.trim();
            if (n.matches("(?i)^(tt2|uh2)\\s+\\d+$"))
            {
                return true;
            }
            if (n.contains("TrifaMaterial") || n.contains("envsh") || n.startsWith("zzzzzoffline"))
            {
                return true;
            }
            if (n.matches("(?i)^0seid0[-_].*"))
            {
                return true;
            }
            if (n.matches("(?i)^venom-ngc.*"))
            {
                return true;
            }
        }

        return false;
    }

    static boolean should_hide_peer_from_member_lists(final String name, final String pubkey)
    {
        return is_likely_automated_test_peer(name, pubkey);
    }

    static boolean is_valid_group_peer_pubkey(@Nullable final String pubkey)
    {
        return is_valid_tox_public_key(pubkey);
    }

    @Nullable
    static String resolve_group_self_pubkey(final long group_num)
    {
        if (group_num < 0)
        {
            return null;
        }
        try
        {
            final String pubkey = tox_group_self_get_public_key(group_num);
            if (!is_valid_group_peer_pubkey(pubkey))
            {
                return null;
            }
            return pubkey.trim();
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    static String resolve_group_self_raw_name(final long group_num)
    {
        if (group_num >= 0)
        {
            try
            {
                final String group_name = tox_group_self_get_name_safe(group_num);
                if (!TextUtils.isEmpty(group_name) && !"-1".equals(group_name))
                {
                    return sanitize_group_peer_name(group_name);
                }
            }
            catch (Throwable ignored)
            {
            }
        }

        try
        {
            if (tox_self_get_name_size() > 0)
            {
                final String global_name = tox_self_get_name();
                if (!TextUtils.isEmpty(global_name) && !"-1".equals(global_name))
                {
                    return sanitize_group_peer_name(global_name);
                }
            }
        }
        catch (Throwable ignored)
        {
        }

        return get_group_peer_join_name();
    }

    /** Push profile nick into every active NGC group so member lists and messages stay in sync. */
    static void sync_self_display_name_to_all_groups(@Nullable final String name)
    {
        if (!is_tox_started || TextUtils.isEmpty(name))
        {
            return;
        }

        try
        {
            final String sanitized = sanitize_group_peer_name(name);
            if (TextUtils.isEmpty(sanitized))
            {
                return;
            }

            final long[] groups = tox_group_get_grouplist();
            if (groups == null)
            {
                return;
            }

            for (long group_num : groups)
            {
                try
                {
                    tox_group_self_set_name(group_num, sanitized);
                }
                catch (Exception ignored)
                {
                }
            }
            TrifaToxService.wakeup_tox_thread();
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "sync_self_display_name_to_all_groups:EE:" + e.getMessage());
        }
    }

    static void apply_group_self_display_name(final long group_num)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        try
        {
            final String nick = resolve_group_self_raw_name(group_num);
            if (!TextUtils.isEmpty(nick))
            {
                tox_group_self_set_name(group_num, nick);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static int compare_group_members(final boolean self1, final boolean online1, final String name1,
                                     final long last_seen1, final boolean self2, final boolean online2,
                                     final String name2, final long last_seen2)
    {
        if (self1 != self2)
        {
            return self1 ? -1 : 1;
        }
        if (online1 != online2)
        {
            return online1 ? -1 : 1;
        }
        if (!online1 && !online2 && last_seen1 != last_seen2)
        {
            return Long.compare(last_seen2, last_seen1);
        }
        final String n1 = name1 == null ? "" : name1;
        final String n2 = name2 == null ? "" : name2;
        return n1.compareToIgnoreCase(n2);
    }

    static String format_group_member_list_label(final Context context, final String pubkey, final String raw_name,
                                                 final boolean online, final boolean is_self)
    {
        String label = format_group_peer_list_display_name(pubkey, raw_name);
        if (is_self && context != null)
        {
            label = label + " (" + context.getString(R.string.add_self_contact_suffix) + ")";
        }
        return label;
    }

    static String format_group_member_last_seen_relative(final Context context, final long last_seen_ms)
    {
        if (last_seen_ms <= 0L)
        {
            return context.getString(R.string.group_member_last_seen_long_ago);
        }
        final long delta_ms = Math.max(0L, System.currentTimeMillis() - last_seen_ms);
        if (delta_ms < 60_000L)
        {
            return context.getString(R.string.group_member_last_seen_just_now);
        }
        final long minutes = delta_ms / 60_000L;
        if (minutes < 60L)
        {
            return context.getResources().getQuantityString(R.plurals.group_member_last_seen_minutes,
                                                              (int) minutes, (int) minutes);
        }
        final long hours = minutes / 60L;
        if (hours < 48L)
        {
            return context.getResources().getQuantityString(R.plurals.group_member_last_seen_hours,
                                                              (int) hours, (int) hours);
        }
        return context.getString(R.string.group_member_last_seen_long_ago);
    }

    static String format_group_member_status_line(final Context context, final boolean online, final long last_seen_ms)
    {
        if (online)
        {
            return context.getString(R.string.group_info_member_online);
        }
        if (last_seen_ms > 0L)
        {
            return format_group_member_last_seen_relative(context, last_seen_ms);
        }
        return context.getString(R.string.group_info_member_offline);
    }

    static long[] count_visible_group_members(final String group_identifier)
    {
        long online = 0L;
        long offline = 0L;
        try
        {
            final List<GroupMemberDisplay> members =
                    dedupe_group_members_for_display(collect_group_members_for_display(group_identifier));
            for (GroupMemberDisplay member : members)
            {
                if (member.online)
                {
                    online++;
                }
                else
                {
                    offline++;
                }
            }
        }
        catch (Throwable ignored)
        {
        }
        return new long[]{online, offline};
    }

    /** Unique pubkeys currently in toxcore's live group peer list. */
    static java.util.HashSet<String> collect_unique_live_group_pubkeys(final long group_num)
    {
        final java.util.HashSet<String> live_pubkeys = new java.util.HashSet<>();
        if (group_num < 0)
        {
            return live_pubkeys;
        }
        try
        {
            final long[] peers = tox_group_get_peerlist(group_num);
            if (peers == null)
            {
                return live_pubkeys;
            }
            for (long peer : peers)
            {
                try
                {
                    final String pubkey = tox_group_peer_get_public_key(group_num, peer);
                    if (!TextUtils.isEmpty(pubkey))
                    {
                        live_pubkeys.add(pubkey.toLowerCase(Locale.ENGLISH));
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return live_pubkeys;
    }

    static long count_unique_group_members(final String group_identifier)
    {
        return count_display_group_members(group_identifier);
    }

    static List<GroupMemberDisplay> collect_group_members_for_display(final String group_identifier)
    {
        final List<GroupMemberDisplay> members = new ArrayList<>();
        try
        {
            long group_num = tox_group_by_groupid__wrapper(group_identifier);
            if (group_num < 0 && is_tox_started && !is_group_we_left(group_identifier))
            {
                group_num = ensure_group_in_tox(group_identifier);
            }

            final java.util.HashSet<String> seen_pubkeys = new java.util.HashSet<>();
            if (group_num < 0)
            {
                append_group_peers_from_db(group_identifier, -1, seen_pubkeys, members);
                return dedupe_group_members_for_display(members);
            }

            final long self_peer_id = tox_group_self_get_peer_id(group_num);
            final String self_pubkey = resolve_group_self_pubkey(group_num);
            final long num_peers = tox_group_peer_count(group_num);
            if (num_peers > 0)
            {
                final long[] peers = tox_group_get_peerlist(group_num);
                if (peers != null)
                {
                    for (long peer : peers)
                    {
                        try
                        {
                            final String pubkey = tox_group_peer_get_public_key(group_num, peer);
                            if (!is_valid_group_peer_pubkey(pubkey))
                            {
                                continue;
                            }
                            final String key = pubkey.toLowerCase(Locale.ENGLISH);
                            if (seen_pubkeys.contains(key))
                            {
                                continue;
                            }
                            seen_pubkeys.add(key);
                            final boolean is_self = (self_pubkey != null && self_pubkey.equalsIgnoreCase(pubkey))
                                    || (self_peer_id >= 0 && peer == self_peer_id);
                            String raw_name = is_self
                                    ? resolve_group_self_raw_name(group_num)
                                    : tox_group_peer_get_name(group_num, peer);
                            if (!is_self && should_hide_peer_from_member_lists(raw_name, pubkey))
                            {
                                continue;
                            }
                            GroupPeerDB peer_from_db = lookup_group_peer_by_pubkey(group_identifier, pubkey);
                            final int conn = tox_group_peer_get_connection_status(group_num, peer);
                            final boolean live_online = is_group_peer_online(conn);
                            final GroupMemberDisplay item = new GroupMemberDisplay();
                            item.pubkey = pubkey;
                            item.probable_bot = false;
                            item.connection_status = conn;
                            item.self = is_self;
                            item.last_seen_ms = resolve_group_peer_last_seen_ms(group_identifier, pubkey, peer_from_db);
                            // KHANDAQ (#50): online = live UDP/TCP OR seen live within the grace window.
                            final boolean within_grace = item.last_seen_ms > 0
                                    && (System.currentTimeMillis() - item.last_seen_ms) <= GROUP_PEER_ONLINE_GRACE_MS;
                            item.online = live_online || within_grace;
                            item.name = format_group_member_list_label(context_s, pubkey, raw_name, item.online, is_self);
                            // CRITICAL: refresh the seen-cache ONLY on a real live sighting, never on the
                            // grace-derived value, or item.online would latch on permanently.
                            if (live_online)
                            {
                                touch_group_peer_online(group_identifier, pubkey);
                            }
                            ensure_group_peer_recorded(group_identifier, group_num, pubkey, raw_name);
                            members.add(item);
                        }
                        catch (Throwable ignored)
                        {
                        }
                    }
                }
            }

            append_group_peers_from_db(group_identifier, group_num, seen_pubkeys, members);
            ensure_self_in_group_member_list(group_identifier, group_num, members, seen_pubkeys);

            try
            {
                Collections.sort(members, new Comparator<GroupMemberDisplay>()
                {
                    @Override
                    public int compare(GroupMemberDisplay a, GroupMemberDisplay b)
                    {
                        return compare_group_members(a.self, a.online, a.name, a.last_seen_ms,
                                                     b.self, b.online, b.name, b.last_seen_ms);
                    }
                });
            }
            catch (Exception ignored)
            {
            }
            return dedupe_group_members_for_display(members);
        }
        catch (Throwable e)
        {
            HelperGeneric.logI(TAG, "collect_group_members_for_display:EE:" + e.getMessage());
        }
        return members;
    }

    private static boolean prefer_group_member_for_display(final GroupMemberDisplay candidate,
                                                           final GroupMemberDisplay existing)
    {
        if (candidate.self != existing.self)
        {
            return candidate.self;
        }
        if (candidate.online != existing.online)
        {
            return candidate.online;
        }
        return candidate.last_seen_ms >= existing.last_seen_ms;
    }

    static List<GroupMemberDisplay> dedupe_group_members_for_display(final List<GroupMemberDisplay> members)
    {
        if (members == null || members.isEmpty())
        {
            return members != null ? members : new ArrayList<>();
        }

        final List<GroupMemberDisplay> self_members = new ArrayList<>();
        final List<GroupMemberDisplay> other_members = new ArrayList<>();
        for (GroupMemberDisplay member : members)
        {
            if (member == null || !is_valid_group_peer_pubkey(member.pubkey))
            {
                continue;
            }
            if (member.self)
            {
                self_members.add(member);
            }
            else
            {
                other_members.add(member);
            }
        }

        final List<GroupMemberDisplay> result = new ArrayList<>();
        final java.util.LinkedHashSet<String> seen_pubkeys = new java.util.LinkedHashSet<>();

        for (GroupMemberDisplay member : self_members)
        {
            final String key = member.pubkey.toLowerCase(Locale.ENGLISH);
            if (seen_pubkeys.add(key))
            {
                result.add(member);
            }
        }

        final List<GroupMemberDisplay> deduped_others = dedupe_non_self_group_members(other_members);
        for (GroupMemberDisplay member : deduped_others)
        {
            if (member.pubkey == null)
            {
                continue;
            }
            final String key = member.pubkey.toLowerCase(Locale.ENGLISH);
            if (seen_pubkeys.add(key))
            {
                result.add(member);
            }
        }
        return result;
    }

    private static List<GroupMemberDisplay> dedupe_non_self_group_members(final List<GroupMemberDisplay> members)
    {
        final List<GroupMemberDisplay> result = new ArrayList<>();
        final java.util.LinkedHashMap<String, GroupMemberDisplay> by_pubkey = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<String, GroupMemberDisplay> by_name = new java.util.LinkedHashMap<>();

        for (GroupMemberDisplay member : members)
        {
            if (member == null || !is_valid_group_peer_pubkey(member.pubkey))
            {
                continue;
            }

            final String pubkey_key = member.pubkey.toLowerCase(Locale.ENGLISH);
            final GroupMemberDisplay existing_pk = by_pubkey.get(pubkey_key);
            if (existing_pk == null || prefer_group_member_for_display(member, existing_pk))
            {
                by_pubkey.put(pubkey_key, member);
            }

            final String name_key = normalize_group_peer_name_key(member.name);
            if (!TextUtils.isEmpty(name_key))
            {
                final GroupMemberDisplay existing_name = by_name.get(name_key);
                if (existing_name == null || prefer_group_member_for_display(member, existing_name))
                {
                    by_name.put(name_key, member);
                }
            }
        }

        final java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (GroupMemberDisplay member : by_name.values())
        {
            if (member.pubkey == null)
            {
                continue;
            }
            final String key = member.pubkey.toLowerCase(Locale.ENGLISH);
            if (seen.add(key))
            {
                result.add(member);
            }
        }
        for (GroupMemberDisplay member : by_pubkey.values())
        {
            if (member.pubkey == null)
            {
                continue;
            }
            final String key = member.pubkey.toLowerCase(Locale.ENGLISH);
            if (seen.add(key))
            {
                result.add(member);
            }
        }
        return result;
    }

    static long count_display_group_members(final String group_identifier)
    {
        try
        {
            return dedupe_group_members_for_display(collect_group_members_for_display(group_identifier)).size();
        }
        catch (Throwable ignored)
        {
            return 0L;
        }
    }

    private static void ensure_self_in_group_member_list(final String group_identifier, final long group_num,
                                                         final List<GroupMemberDisplay> members,
                                                         final java.util.HashSet<String> seen_pubkeys)
    {
        if (group_num < 0)
        {
            return;
        }

        try
        {
            final String self_pubkey = resolve_group_self_pubkey(group_num);
            if (self_pubkey == null)
            {
                return;
            }

            final String raw_name = resolve_group_self_raw_name(group_num);
            for (GroupMemberDisplay member : members)
            {
                if (member.pubkey != null && member.pubkey.equalsIgnoreCase(self_pubkey))
                {
                    member.self = true;
                    member.name = format_group_member_list_label(context_s, self_pubkey, raw_name, member.online, true);
                    return;
                }
            }

            long self_peer_id = -1L;
            try
            {
                self_peer_id = tox_group_self_get_peer_id(group_num);
            }
            catch (Exception ignored)
            {
            }

            int conn = ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value;
            if (self_peer_id >= 0)
            {
                try
                {
                    conn = tox_group_peer_get_connection_status(group_num, self_peer_id);
                }
                catch (Exception ignored)
                {
                }
            }

            final GroupMemberDisplay item = new GroupMemberDisplay();
            item.pubkey = self_pubkey;
            item.probable_bot = false;
            item.connection_status = conn;
            item.online = is_group_peer_online(conn);
            item.self = true;
            item.last_seen_ms = System.currentTimeMillis();
            item.name = format_group_member_list_label(context_s, self_pubkey, raw_name, item.online, true);
            members.add(item);
            seen_pubkeys.add(self_pubkey.toLowerCase(Locale.ENGLISH));
        }
        catch (Throwable e)
        {
            HelperGeneric.logI(TAG, "ensure_self_in_group_member_list:EE:" + e.getMessage());
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.HashSet<String>>
            saved_peer_roster_cache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long>
            saved_peer_roster_cache_ts = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> saved_peer_roster_inflight =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
    private static final long SAVED_PEER_ROSTER_TTL_MS = 8_000L;

    /**
     * toxcore's bounded saved-peer roster for a loaded group. Read from a short-TTL cache that is
     * (re)populated ONLY on a background thread, never inline. Querying GC_MAX_SAVED_PEERS slots is
     * one tox JNI call each (each contends the tox lock); doing that inline on the UI thread for
     * every group-list row rebind froze the app (ANR). Returns null when the group is not loaded or
     * before the first population completes, so callers transiently keep the full DB list (the count
     * self-corrects within ~1s once the cache is warm). The returned set is read-only.
     */
    private static java.util.HashSet<String> get_saved_peer_roster_cached(final String group_identifier,
                                                                          final long group_num)
    {
        if (group_num < 0 || TextUtils.isEmpty(group_identifier))
        {
            return null;
        }
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        final long now = System.currentTimeMillis();
        final Long ts = saved_peer_roster_cache_ts.get(key);
        final boolean fresh = ts != null && (now - ts) < SAVED_PEER_ROSTER_TTL_MS;
        if (!fresh && saved_peer_roster_inflight.add(key))
        {
            final Thread t = new Thread(() ->
            {
                try
                {
                    // Re-resolve the group number here (it can change) so we never query a stale/wrong group.
                    final long gn = tox_group_by_groupid__wrapper(key);
                    if (gn >= 0)
                    {
                        final java.util.HashSet<String> roster = new java.util.HashSet<>();
                        for (long slot = 0; slot < GC_MAX_SAVED_PEERS; slot++)
                        {
                            try
                            {
                                final String saved_pk = tox_group_savedpeer_get_public_key(gn, slot);
                                if (!TextUtils.isEmpty(saved_pk) && !"-1".equalsIgnoreCase(saved_pk))
                                {
                                    roster.add(saved_pk.toLowerCase(Locale.ENGLISH));
                                }
                            }
                            catch (Exception ignored)
                            {
                            }
                        }
                        saved_peer_roster_cache.put(key, roster);
                        saved_peer_roster_cache_ts.put(key, System.currentTimeMillis());
                    }
                }
                catch (Throwable ignored)
                {
                }
                finally
                {
                    saved_peer_roster_inflight.remove(key);
                }
            }, "roster-cache");
            t.setDaemon(true);
            t.start();
        }
        return saved_peer_roster_cache.get(key);
    }

    private static void append_group_peers_from_db(final String group_identifier, final long group_num,
                                                   final java.util.HashSet<String> seen_pubkeys,
                                                   final List<GroupMemberDisplay> members)
    {
        try
        {
            String self_pubkey = null;
            if (group_num >= 0)
            {
                self_pubkey = resolve_group_self_pubkey(group_num);
            }

            // When the group is loaded in toxcore, restrict offline/historical members to the pubkeys
            // toxcore still keeps in its bounded, mesh-synced saved-peer roster. The local GroupPeerDB
            // is never pruned and accumulates stale ephemeral NGC pubkeys (a fresh key is assigned on
            // every rejoin), which made the SAME group show different member totals on different
            // devices. When the group is not loaded (group_num < 0) we keep the full DB so something
            // still shows offline.
            // Querying all GC_MAX_SAVED_PEERS slots is one tox JNI call per slot (each contends the
            // tox lock). This runs via the group-list subtitle on the UI thread for every row rebind,
            // so doing it uncached froze the app (ANR). Use a short-TTL cache instead.
            final java.util.HashSet<String> tox_known_pubkeys =
                    get_saved_peer_roster_cached(group_identifier, group_num);

            final List<GroupPeerDB> db_peers = orma.selectFromGroupPeerDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    orderByLast_update_timestampDesc().toList();
            for (GroupPeerDB db_peer : db_peers)
            {
                if (db_peer == null || db_peer.tox_group_peer_pubkey == null)
                {
                    continue;
                }
                final String pubkey = db_peer.tox_group_peer_pubkey.trim();
                if (pubkey.isEmpty())
                {
                    continue;
                }
                final String key = pubkey.toLowerCase(Locale.ENGLISH);
                if (seen_pubkeys.contains(key))
                {
                    continue;
                }
                // Skip stale DB rows that toxcore no longer tracks (keeps counts consistent across
                // devices). Only applied when we actually have toxcore's roster (group loaded).
                if (tox_known_pubkeys != null && !tox_known_pubkeys.contains(key))
                {
                    continue;
                }
                seen_pubkeys.add(key);

                final String peer_name = db_peer.peer_name;
                if (should_hide_peer_from_member_lists(peer_name, pubkey))
                {
                    continue;
                }

                final GroupMemberDisplay item = new GroupMemberDisplay();
                item.pubkey = pubkey;
                item.probable_bot = false;
                item.connection_status = ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value;
                item.online = false;
                item.self = self_pubkey != null && self_pubkey.equalsIgnoreCase(pubkey);
                item.last_seen_ms = resolve_group_peer_last_seen_ms(group_identifier, pubkey, db_peer);
                item.name = format_group_member_list_label(context_s, pubkey, peer_name, false, item.self);
                members.add(item);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "append_group_peers_from_db:EE:" + e.getMessage());
        }
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

    static String sanitize_group_topic(final String raw_title)
    {
        if (raw_title == null)
        {
            return "";
        }

        String cleaned = raw_title.trim().replace("\r", "").replace("\n", "");
        return HelperGeneric.truncate_utf8_to_max_bytes(cleaned, TOX_GROUP_MAX_TOPIC_LENGTH);
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

        HelperGeneric.logI(TAG, "group_create_failure_message:errCode=" + errCode);
        return context_s.getString(R.string.add_private_group_failed);
    }

    static boolean create_new_group(final int privacy_state, final String raw_group_name,
                                    final int success_string_id, final int failure_string_id,
                                    final String system_message)
    {
        return create_new_group(privacy_state, raw_group_name, success_string_id, failure_string_id,
                system_message, null);
    }

    static boolean create_new_group(final int privacy_state, final String raw_group_name,
                                    final int success_string_id, final int failure_string_id,
                                    final String system_message, @Nullable final String founder_password)
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
        HelperGeneric.logI(TAG, "create_new_group:name=" + group_name + " peer=" + peer_name + " privacy=" + privacy_state);

        final long new_group_num = tox_group_new(privacy_state, group_name, peer_name);
        HelperGeneric.logI(TAG, "create_new_group:groupnum=" + new_group_num);

        if ((new_group_num < 0) || (new_group_num >= UINT32_MAX_JAVA))
        {
            display_toast(group_create_failure_message(new_group_num), false, 300);
            return false;
        }

        update_savedata_file_wrapper();

        final ByteBuffer groupid_buf = ByteBuffer.allocateDirect(GROUP_ID_LENGTH * 2);
        if (tox_group_get_chat_id(new_group_num, groupid_buf) != 0)
        {
            HelperGeneric.logI(TAG, "create_new_group:get_chat_id failed groupnum=" + new_group_num);
            display_toast(context_s.getString(failure_string_id), false, 300);
            return false;
        }

        final byte[] groupid_buffer = new byte[GROUP_ID_LENGTH];
        groupid_buf.get(groupid_buffer, 0, GROUP_ID_LENGTH);
        final String group_identifier = bytes_to_hex(groupid_buffer);
        final int resolved_privacy_state = tox_group_get_privacy_state(new_group_num);

        HelperGeneric.logI(TAG, "create_new_group:ok num=" + new_group_num + " id=" + group_identifier);

        if (founder_password != null && !founder_password.isEmpty())
        {
            final int pwd_res = tox_group_founder_set_password__wrapper(new_group_num, founder_password);
            if (pwd_res < 0)
            {
                Log.w(TAG, "create_new_group:set_password failed res=" + pwd_res);
            }
            else
            {
                update_savedata_file_wrapper();
            }
        }

        add_group_wrapper(0, new_group_num, group_identifier, resolved_privacy_state);
        display_toast(context_s.getString(success_string_id), false, 300);
        add_system_message_to_group_chat(group_identifier, system_message);
        set_group_active(group_identifier);
        if (resolved_privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            // tox_group_new may already leave the group CONNECTED — reconnect would drop to CONNECTING.
            if (tox_group_is_connected(new_group_num) !=
                TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                kickstart_public_group_dht(new_group_num, group_identifier);
            }
            else
            {
                clear_group_connect_progress(group_identifier);
            }
        }
        else if (have_own_relay())
        {
            invite_to_group_own_relay(new_group_num);
        }
        if (tox_group_is_connected(new_group_num) ==
            TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            clear_group_connect_progress(group_identifier);
        }

        try
        {
            final GroupDB conf3 = (GroupDB) orma.selectFromGroupDB().group_identifierEq(
                    group_identifier.toLowerCase()).toList().get(0);
            CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
            cc.is_friend = COMBINED_IS_GROUP;
            cc.group_item = (GroupDB) GroupDB.deep_copy(conf3);
            if (MainActivity.friend_list_fragment != null)
            {
                MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "create_new_group:friendlist:EE:" + e.getMessage());
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
        final String new_title = sanitize_group_topic(raw_title);
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

        TrifaToxService.wakeup_tox_thread();
        final int res;
        try
        {
            res = tox_group_set_topic(group_num, new_title);
        }
        catch (LinkageError linkErr)
        {
            HelperGeneric.logI(TAG, "save_group_title_if_changed:no JNI tox_group_set_topic: " + linkErr.getMessage());
            return false;
        }
        if (res == 1)
        {
            update_group_in_db_name(group_identifier, new_title);
            update_group_in_db_topic(group_identifier, new_title);
            update_group_in_friendlist(group_identifier);
            update_group_in_groupmessagelist(group_identifier);
            update_savedata_file_wrapper();
            return true;
        }

        HelperGeneric.logI(TAG, "save_group_title_if_changed:failed res=" + res + " group=" + group_identifier);
        return false;
    }

    static void ensure_group_peer_recorded(final String group_identifier, final long group_number,
                                           final String peer_pubkey, final String peer_name_hint)
    {
        if (group_identifier == null || peer_pubkey == null || peer_pubkey.isEmpty()
                || TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(peer_pubkey)
                || "-1".equalsIgnoreCase(peer_pubkey))
        {
            return;
        }

        try
        {
            String resolved_name = null;
            if (!TextUtils.isEmpty(peer_name_hint))
            {
                resolved_name = sanitize_group_peer_name(peer_name_hint);
            }
            if (TextUtils.isEmpty(resolved_name))
            {
                resolved_name = tox_group_peer_get_name__wrapper(group_identifier, peer_pubkey);
            }

            final GroupPeerDB existing = lookup_group_peer_by_pubkey(group_identifier, peer_pubkey);
            if (existing != null)
            {
                if (!TextUtils.isEmpty(resolved_name) && TextUtils.isEmpty(existing.peer_name))
                {
                    orma.updateGroupPeerDB().group_identifierEq(group_identifier.toLowerCase()).
                            tox_group_peer_pubkeyEq(existing.tox_group_peer_pubkey).
                            peer_name(resolved_name).
                            last_update_timestamp(System.currentTimeMillis()).
                            execute();
                }
                return;
            }

            int role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value;
            long peer_num = -1L;
            if (group_number >= 0)
            {
                try
                {
                    peer_num = get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey);
                    if (peer_num >= 0)
                    {
                        final int peer_role = tox_group_peer_get_role(group_number, peer_num);
                        if (peer_role >= 0)
                        {
                            role = peer_role;
                        }
                    }
                }
                catch (Exception ignored)
                {
                }
            }

            final GroupPeerDB p = new GroupPeerDB();
            p.group_identifier = group_identifier;
            p.tox_group_peer_pubkey = peer_pubkey;
            p.peer_name = resolved_name;
            p.last_update_timestamp = System.currentTimeMillis();
            p.first_join_timestamp = System.currentTimeMillis();
            p.Tox_Group_Role = role;
            orma.insertIntoGroupPeerDB(p);
        }
        catch (Exception e)
        {
            try
            {
                int role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value;
                long peer_num = -1L;
                if (group_number >= 0)
                {
                    peer_num = get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey);
                    if (peer_num >= 0)
                    {
                        final int peer_role = tox_group_peer_get_role(group_number, peer_num);
                        if (peer_role >= 0)
                        {
                            role = peer_role;
                        }
                    }
                }
                update_group_peer_in_db(group_number, group_identifier, peer_num, peer_pubkey, role);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    /** Copy live + saved tox peers into GroupPeerDB (peer_join is not fired for existing peers). */
    static void sync_group_peers_from_tox_to_db(final long group_number)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (group_identifier == null)
            {
                return;
            }

            final java.util.HashSet<String> live_pubkeys = new java.util.HashSet<>();
            final long num_peers = tox_group_peer_count(group_number);
            if (num_peers > 0)
            {
                final long[] peers = tox_group_get_peerlist(group_number);
                if (peers != null)
                {
                    for (long peer : peers)
                    {
                        try
                        {
                            final String pubkey = tox_group_peer_get_public_key(group_number, peer);
                            if (TextUtils.isEmpty(pubkey))
                            {
                                continue;
                            }
                            live_pubkeys.add(pubkey.toLowerCase(Locale.ENGLISH));
                            touch_group_peer_online(group_identifier, pubkey);
                            int role = tox_group_peer_get_role(group_number, peer);
                            if (role < 0)
                            {
                                role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
                            }
                            ensure_group_peer_recorded(group_identifier, group_number, pubkey, null);
                            update_group_peer_in_db(group_number, group_identifier, peer, pubkey, role);
                        }
                        catch (Throwable ignored)
                        {
                        }
                    }
                }
            }

            // Keep offline members in GroupPeerDB; only dedupe same display name with a new NGC session key.
            sync_saved_group_peers_to_db(group_number, group_identifier, live_pubkeys);
            dedupe_group_peer_db_by_display_name(group_identifier, group_number);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "sync_group_peers_from_tox_to_db:EE:" + e.getMessage());
        }
    }

    /**
     * Import toxcore's saved peer roster into GroupPeerDB so offline/historical members stay visible.
     */
    private static void sync_saved_group_peers_to_db(final long group_number,
                                                     @NonNull final String group_identifier,
                                                     @NonNull final java.util.HashSet<String> live_pubkeys)
    {
        if (group_number < 0)
        {
            return;
        }
        try
        {
            for (long slot = 0; slot < GC_MAX_SAVED_PEERS; slot++)
            {
                try
                {
                    final String pubkey = tox_group_savedpeer_get_public_key(group_number, slot);
                    if (TextUtils.isEmpty(pubkey) || "-1".equalsIgnoreCase(pubkey))
                    {
                        continue;
                    }
                    final String key = pubkey.toLowerCase(Locale.ENGLISH);
                    if (live_pubkeys.contains(key))
                    {
                        continue;
                    }
                    ensure_group_peer_recorded(group_identifier, group_number, pubkey, null);
                }
                catch (Exception ignored)
                {
                }
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "sync_saved_group_peers_to_db:EE:" + e.getMessage());
        }
    }

    /**
     * Remove DB rows not in the live tox peer list. NGC rejoin creates a new
     * ephemeral pubkey per session; keeping saved_peers entries inflated counts.
     */
    private static void prune_stale_group_peers_from_db(@NonNull final String group_identifier,
                                                        @NonNull final java.util.HashSet<String> live_pubkeys)
    {
        try
        {
            final List<GroupPeerDB> db_peers = orma.selectFromGroupPeerDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList();
            for (GroupPeerDB db_peer : db_peers)
            {
                if (db_peer == null || db_peer.tox_group_peer_pubkey == null)
                {
                    continue;
                }
                final String key = db_peer.tox_group_peer_pubkey.toLowerCase(Locale.ENGLISH);
                if (live_pubkeys.contains(key))
                {
                    continue;
                }
                orma.deleteFromGroupPeerDB().
                        group_identifierEq(group_identifier.toLowerCase()).
                        tox_group_peer_pubkeyEq(db_peer.tox_group_peer_pubkey).
                        execute();
                group_peer_last_online_ms.remove(group_peer_seen_key(group_identifier, db_peer.tox_group_peer_pubkey));
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "prune_stale_group_peers_from_db:EE:" + e.getMessage());
        }
    }

    /**
     * NGC assigns a fresh ephemeral pubkey on each rejoin. When the same display
     * name appears with a new key, drop stale DB rows for the old key.
     */
    static void dedupe_group_peer_db_by_display_name(@NonNull final String group_identifier, final long group_num)
    {
        try
        {
            final java.util.HashMap<String, String> best_pubkey_by_name = new java.util.HashMap<>();
            final java.util.HashMap<String, Long> best_peer_id_by_name = new java.util.HashMap<>();
            final long[] peers = group_num >= 0 ? tox_group_get_peerlist(group_num) : null;
            if (peers != null)
            {
                for (long peer : peers)
                {
                    try
                    {
                        final String pubkey = tox_group_peer_get_public_key(group_num, peer);
                        if (TextUtils.isEmpty(pubkey))
                        {
                            continue;
                        }
                        final String raw_name = tox_group_peer_get_name(group_num, peer);
                        final String name_key = normalize_group_peer_name_key(raw_name);
                        if (TextUtils.isEmpty(name_key))
                        {
                            continue;
                        }
                        final Long prev_peer = best_peer_id_by_name.get(name_key);
                        if (prev_peer == null || peer > prev_peer)
                        {
                            best_peer_id_by_name.put(name_key, peer);
                            best_pubkey_by_name.put(name_key, pubkey.toLowerCase(Locale.ENGLISH));
                        }
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }

            final List<GroupPeerDB> db_peers = orma.selectFromGroupPeerDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList();
            for (GroupPeerDB db_peer : db_peers)
            {
                if (db_peer == null || db_peer.tox_group_peer_pubkey == null)
                {
                    continue;
                }
                final String name_key = normalize_group_peer_name_key(db_peer.peer_name);
                if (TextUtils.isEmpty(name_key))
                {
                    continue;
                }
                final String keep_pubkey = best_pubkey_by_name.get(name_key);
                if (keep_pubkey == null)
                {
                    continue;
                }
                if (keep_pubkey.equals(db_peer.tox_group_peer_pubkey.toLowerCase(Locale.ENGLISH)))
                {
                    continue;
                }
                orma.deleteFromGroupPeerDB().
                        group_identifierEq(group_identifier.toLowerCase()).
                        tox_group_peer_pubkeyEq(db_peer.tox_group_peer_pubkey).
                        execute();
                group_peer_last_online_ms.remove(group_peer_seen_key(group_identifier, db_peer.tox_group_peer_pubkey));
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "dedupe_group_peer_db_by_display_name:EE:" + e.getMessage());
        }
    }

    @Nullable
    static String normalize_group_peer_name_key(@Nullable final String raw_name)
    {
        final String sanitized = sanitize_group_peer_name(raw_name);
        if (TextUtils.isEmpty(sanitized))
        {
            return null;
        }
        return sanitized.toLowerCase(Locale.ENGLISH);
    }

    static void record_group_peer_on_join(final long group_number, final String group_identifier,
                                          final long peer_id, final String group_peer_pubkey, final int peer_role)
    {
        if (TextUtils.isEmpty(group_identifier) || TextUtils.isEmpty(group_peer_pubkey))
        {
            return;
        }
        dedupe_stale_pubkey_for_same_display_name(group_identifier, group_number, peer_id, group_peer_pubkey);
        ensure_group_peer_recorded(group_identifier, group_number, group_peer_pubkey, null);
        update_group_peer_in_db(group_number, group_identifier, peer_id, group_peer_pubkey, peer_role);
        sync_group_peers_from_tox_to_db(group_number);
        log_group_peer_mesh_diagnostics(group_number, group_identifier, "peer_join pid=" + peer_id);
        clear_group_connect_progress(group_identifier);

        // KHANDAQ (audit #2 finding 1, step 3): the arriving peer needs our signing key before it can
        // verify anything we wrote. Announcing only when WE connect misses it entirely — the packet
        // goes out while the peer list is still empty and nothing follows for ten minutes, which is
        // exactly how the first version of this looked correct and delivered nothing.
        announce_hsk_to_new_peer(group_number, group_identifier, group_peer_pubkey);
    }

    private static void dedupe_stale_pubkey_for_same_display_name(@NonNull final String group_identifier,
                                                                final long group_number, final long peer_id,
                                                                @NonNull final String new_pubkey)
    {
        try
        {
            // KHANDAQ: resolve by pubkey first (stable); raw peer_id only as a last resort.
            String new_name = tox_group_peer_get_name__wrapper(group_identifier, new_pubkey, peer_id);
            if (TextUtils.isEmpty(new_name))
            {
                new_name = tox_group_peer_get_name(group_number, peer_id);
            }
            final String name_key = normalize_group_peer_name_key(new_name);
            if (TextUtils.isEmpty(name_key))
            {
                return;
            }
            final List<GroupPeerDB> db_peers = orma.selectFromGroupPeerDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList();
            for (GroupPeerDB db_peer : db_peers)
            {
                if (db_peer == null || db_peer.tox_group_peer_pubkey == null)
                {
                    continue;
                }
                if (db_peer.tox_group_peer_pubkey.equalsIgnoreCase(new_pubkey))
                {
                    continue;
                }
                if (!name_key.equals(normalize_group_peer_name_key(db_peer.peer_name)))
                {
                    continue;
                }
                orma.deleteFromGroupPeerDB().
                        group_identifierEq(group_identifier.toLowerCase()).
                        tox_group_peer_pubkeyEq(db_peer.tox_group_peer_pubkey).
                        execute();
                group_peer_last_online_ms.remove(group_peer_seen_key(group_identifier, db_peer.tox_group_peer_pubkey));
                HelperGeneric.logI(TAG, "dedupe_stale_pubkey_for_same_display_name:name=" + name_key
                        + " removed=" + db_peer.tox_group_peer_pubkey.substring(0, 6));
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "dedupe_stale_pubkey_for_same_display_name:EE:" + e.getMessage());
        }
    }

    /**
     * Drop a peer from the members DB only when they intentionally left or were kicked.
     * Disconnect/timeout peers stay in the roster as offline members.
     */
    static void remove_group_peer_from_db_on_exit(final long group_number, final long peer_id,
                                                  final int exit_type)
    {
        try
        {
            final boolean permanent_leave =
                    exit_type == ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_QUIT.value
                    || exit_type == ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_KICK.value;
            if (!permanent_leave)
            {
                record_group_peer_last_seen_on_exit(group_number, peer_id);
                return;
            }

            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            final String pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);
            if (TextUtils.isEmpty(group_identifier) || TextUtils.isEmpty(pubkey))
            {
                return;
            }
            orma.deleteFromGroupPeerDB().
                    group_identifierEq(group_identifier.toLowerCase()).
                    tox_group_peer_pubkeyEq(pubkey).
                    execute();
            group_peer_last_online_ms.remove(group_peer_seen_key(group_identifier, pubkey));
            HelperGeneric.logI(TAG, "remove_group_peer_from_db_on_exit:id="
                    + group_identifier_short(group_identifier, false)
                    + " exit_type=" + exit_type + " pubkey=" + pubkey.substring(0, 6));
            sync_group_peers_from_tox_to_db(group_number);
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
                    HelperGeneric.logI(TAG, "group_conference_all_messages:del");
                    orma.deleteFromGroupMessage().group_identifierEq(group_identifier.toLowerCase()).execute();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "group_conference_all_messages:EE:" + e.getMessage());
                }
            }
        };
        t.start();
    }

    static void delete_group(final String group_identifier)
    {
        try
        {
            HelperGeneric.logI(TAG, "delete_group:del");
            orma.deleteFromGroupDB().group_identifierEq(group_identifier.toLowerCase()).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "delete_group:EE:" + e.getMessage());
        }
    }

    static void update_group_in_friendlist(final String group_identifier)
    {
        try
        {
            if (group_identifier == null || MainActivity.friend_list_fragment == null)
            {
                return;
            }

            final GroupDB conf3 = (GroupDB) orma.selectFromGroupDB().
                    group_identifierEq(group_identifier.toLowerCase()).toList().get(0);

            CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
            cc.is_friend = COMBINED_IS_GROUP;
            cc.group_item = (GroupDB) GroupDB.deep_copy(conf3);
            MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
        }
        catch (Exception e1)
        {
            // HelperGeneric.logI(TAG, "update_group_in_friendlist:EE1:" + e1.getMessage());
            // e1.printStackTrace();
        }
    }

    static void update_group_peer_in_db(final long group_number, final String group_identifier,
                                        final long peerid, final String group_peer_pubkey,
                                        int aTox_Group_Role)
    {
        try
        {
            if (group_identifier == null || group_peer_pubkey == null)
            {
                return;
            }

            String peer_name = null;
            // KHANDAQ: resolve the name by PUBKEY (the wrapper maps it to the peer's CURRENT peer_id
            // internally). NEVER resolve by the raw peer_id here — NGC reuses peer_ids on rejoin, so a
            // stale id would attach another peer's name to this pubkey and relabel their messages.
            try
            {
                peer_name = pick_non_hex_group_peer_name(
                        tox_group_peer_get_name__wrapper(group_identifier, group_peer_pubkey, peerid),
                        group_peer_pubkey);
            }
            catch (Exception ignored)
            {
            }

            if (peer_name == null)
            {
                final GroupPeerDB existing = lookup_group_peer_by_pubkey(group_identifier, group_peer_pubkey);
                if (existing != null)
                {
                    peer_name = pick_non_hex_group_peer_name(existing.peer_name, group_peer_pubkey);
                }
            }

            if (peer_name == null)
            {
                final FriendList friend = HelperFriend.lookup_friendlist_by_pubkey(group_peer_pubkey);
                if (friend != null)
                {
                    peer_name = pick_non_hex_group_peer_name(
                            HelperFriend.display_name_from_friendlist(friend, group_peer_pubkey),
                            group_peer_pubkey);
                }
            }

            if (peer_name == null)
            {
                peer_name = HelperFriend.peer_pubkey_short_id(group_peer_pubkey);
            }

            orma.updateGroupPeerDB().group_identifierEq(group_identifier).tox_group_peer_pubkeyEq(group_peer_pubkey).
                    peer_name(peer_name).
                    Tox_Group_Role(aTox_Group_Role).
                    last_update_timestamp(System.currentTimeMillis()).
                    execute();
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
        record_group_peer_on_join(group_number, group_identifier, peerid, group_peer_pubkey, aTox_Group_Role);
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
                // Do not drop custom packets (file chunks) when GroupDB is not yet synced.
                return false;
            }

            final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peerid);
            if (peer_pubkey == null)
            {
                return false;
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
        update_group_in_groupmessagelist(group_identifier, false);
    }

    static void update_group_in_groupmessagelist(final String group_identifier, final boolean force_refresh)
    {
        if (group_identifier == null || group_message_list_activity == null)
        {
            return;
        }

        final String group_id_lower = group_identifier.toLowerCase();
        final Runnable refresh_runnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (group_message_list_activity == null)
                    {
                        return;
                    }

                    final String current_group_id = group_message_list_activity.get_current_group_id();
                    if (current_group_id == null
                            || !current_group_id.toLowerCase().equals(group_id_lower))
                    {
                        return;
                    }

                    if (force_refresh)
                    {
                        group_message_list_activity.update_group_all_users_real();
                    }
                    else
                    {
                        group_message_list_activity.update_group_all_users();
                    }
                }
                catch (Exception e1)
                {
                    HelperGeneric.logI(TAG, "update_group_in_groupmessagelist:EE1:" + e1.getMessage());
                    e1.printStackTrace();
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(refresh_runnable);
        }
        else
        {
            refresh_runnable.run();
        }
    }

    private static final long GROUP_RECONNECT_SUPPRESS_MS = 5L * 60L * 1000L;
    private static final java.util.Map<String, Long> group_peer_reconnect_until = new java.util.concurrent.ConcurrentHashMap<>();

    static void notify_group_peer_joined(final long group_number, final long peer_id)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (TextUtils.isEmpty(group_identifier))
            {
                return;
            }
            if (!should_show_group_system_messages(group_identifier))
            {
                return;
            }
            if (shouldSuppressReconnectJoin(group_identifier, peer_id))
            {
                return;
            }
            add_system_message_to_group_chat(group_identifier,
                    formatGroupPeerJoinedMessage(group_number, peer_id));
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "notify_group_peer_joined:EE:" + e.getMessage());
        }
    }

    static void notify_group_peer_exit(final long group_number, final long peer_id, final int exit_type)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (TextUtils.isEmpty(group_identifier))
            {
                return;
            }

            if (exit_type == ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_TIMEOUT.value
                    || exit_type == ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_DISCONNECTED.value)
            {
                markGroupPeerReconnectNoise(group_identifier, peer_id);
                return;
            }

            if (!should_show_group_system_messages(group_identifier))
            {
                return;
            }

            add_system_message_to_group_chat(group_identifier,
                    formatGroupPeerLeftMessage(group_number, peer_id, exit_type));
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "notify_group_peer_exit:EE:" + e.getMessage());
        }
    }

    static void notify_group_peer_name_changed(final long group_number, final long peer_id)
    {
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (TextUtils.isEmpty(group_identifier))
            {
                return;
            }

            final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);
            String peer_name = null;
            try
            {
                // KHANDAQ: resolve by pubkey (stable), not the volatile peer_id.
                peer_name = pick_non_hex_group_peer_name(
                        tox_group_peer_get_name__wrapper(group_identifier, peer_pubkey, peer_id), peer_pubkey);
            }
            catch (Exception ignored)
            {
            }

            if (peer_name != null)
            {
                refresh_group_messages_peer_name(group_identifier, peer_pubkey, peer_name);
            }

            if (isGroupPeerReconnectSuppressed(group_identifier, peer_id))
            {
                return;
            }
            if (!should_show_group_system_messages(group_identifier))
            {
                return;
            }
            add_system_message_to_group_chat(group_identifier,
                    formatGroupPeerRenamedMessage(group_number, peer_id));
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "notify_group_peer_name_changed:EE:" + e.getMessage());
        }
    }

    static void refresh_group_messages_peer_name(final String group_identifier, final String peer_pubkey,
                                                 final String peer_name)
    {
        if (TextUtils.isEmpty(group_identifier) || TextUtils.isEmpty(peer_pubkey) || TextUtils.isEmpty(peer_name))
        {
            return;
        }

        final String resolved = pick_non_hex_group_peer_name(peer_name, peer_pubkey);
        if (resolved == null)
        {
            return;
        }

        try
        {
            orma.updateGroupMessage().
                    group_identifierEq(group_identifier.toLowerCase(Locale.ROOT)).
                    tox_group_peer_pubkeyEq(peer_pubkey.toUpperCase(Locale.ROOT)).
                    tox_group_peername(resolved).
                    execute();
        }
        catch (Exception ignored)
        {
        }

        try
        {
            if (MainActivity.group_message_list_fragment != null
                    && group_identifier.equalsIgnoreCase(MainActivity.group_message_list_fragment.current_group_id))
            {
                // KHANDAQ (audit): the old code posted the SYNCHRONOUS update_all_messages (full DB
                // read+sort) to the UI thread. The async variant reads off the UI thread and posts only
                // the adapter update.
                try
                {
                    MainActivity.group_message_list_fragment.update_all_messages_async();
                }
                catch (Exception ignored)
                {
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static boolean isReconnectNoiseSystemMessage(final String text)
    {
        if (text == null || text.isEmpty())
        {
            return false;
        }

        return text.contains("TOX_GROUP_EXIT_TYPE_TIMEOUT")
                || text.contains("TOX_GROUP_EXIT_TYPE_DISCONNECTED");
    }

    static String formatSystemMessageForDisplay(final Context context, final String raw)
    {
        if (context == null || raw == null)
        {
            return raw == null ? "" : raw;
        }

        if (isReconnectNoiseSystemMessage(raw))
        {
            return "";
        }

        if (raw.startsWith("You joined the group"))
        {
            return context.getString(R.string.group_sys_you_joined);
        }

        if (raw.startsWith("privacy state changed to:"))
        {
            return raw.replace("privacy state changed to:", context.getString(R.string.group_sys_privacy_changed));
        }

        try
        {
            if (raw.contains(" joined the group"))
            {
                final String peerLabel = raw.replace("peer ", "").replace(" joined the group", "").trim();
                return context.getString(R.string.group_sys_peer_joined, peerLabel);
            }
            if (raw.contains(" left the group: "))
            {
                final int split = raw.indexOf(" left the group: ");
                final String peerLabel = raw.substring(raw.indexOf("peer ") + 5, split).trim();
                final String reason = raw.substring(split + " left the group: ".length()).trim();
                return context.getString(R.string.group_sys_peer_left, peerLabel, humanizeExitReason(context, reason));
            }
            if (raw.contains(" changed name"))
            {
                final String peerLabel = raw.replace("peer ", "").replace(" changed name", "").trim();
                return context.getString(R.string.group_sys_peer_renamed, peerLabel);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "formatSystemMessageForDisplay:EE:" + e.getMessage());
        }

        return raw;
    }

    private static String humanizeExitReason(final Context context, final String reason)
    {
        if (reason == null)
        {
            return "";
        }

        if (reason.contains("TOX_GROUP_EXIT_TYPE_QUIT"))
        {
            return context.getString(R.string.group_sys_exit_quit);
        }
        if (reason.contains("TOX_GROUP_EXIT_TYPE_KICK"))
        {
            return context.getString(R.string.group_sys_exit_kick);
        }
        if (reason.contains("TOX_GROUP_EXIT_TYPE_SYNC_ERROR"))
        {
            return context.getString(R.string.group_sys_exit_sync_error);
        }
        if (reason.contains("TOX_GROUP_EXIT_TYPE_SELF_DISCONNECTED"))
        {
            return context.getString(R.string.group_sys_exit_self_disconnected);
        }

        return reason;
    }

    private static String formatGroupPeerJoinedMessage(final long group_number, final long peer_id)
    {
        final Context ctx = groupNotifyContext();
        return ctx.getString(R.string.group_sys_peer_joined, resolveGroupPeerLabel(group_number, peer_id));
    }

    private static String formatGroupPeerLeftMessage(final long group_number, final long peer_id, final int exit_type)
    {
        final Context ctx = groupNotifyContext();
        return ctx.getString(R.string.group_sys_peer_left,
                resolveGroupPeerLabel(group_number, peer_id),
                humanizeExitReason(ctx, ToxVars.Tox_Group_Exit_Type.value_str(exit_type)));
    }

    private static String formatGroupPeerRenamedMessage(final long group_number, final long peer_id)
    {
        final Context ctx = groupNotifyContext();
        return ctx.getString(R.string.group_sys_peer_renamed, resolveGroupPeerLabel(group_number, peer_id));
    }

    private static String resolveGroupPeerLabel(final long group_number, final long peer_id)
    {
        // KHANDAQ: resolve via PUBKEY (captured from the still-valid peer_id at the callback instant),
        // so a later peer_id reuse can't put the wrong name in a "joined/left/renamed" notice.
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);
            if (group_identifier != null && peer_pubkey != null && !peer_pubkey.isEmpty())
            {
                final String by_pubkey = tox_group_peer_get_name__wrapper(group_identifier, peer_pubkey, peer_id);
                if (by_pubkey != null && !by_pubkey.isEmpty() && !by_pubkey.equals("-1"))
                {
                    return by_pubkey;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            final String peer_name = tox_group_peer_get_name(group_number, peer_id);
            if (peer_name != null && !peer_name.isEmpty() && !peer_name.equals("-1"))
            {
                return peer_name;
            }
        }
        catch (Exception ignored)
        {
        }

        return groupNotifyContext().getString(R.string.group_sys_peer_number, peer_id);
    }

    private static Context groupNotifyContext()
    {
        if (context_s != null)
        {
            return context_s;
        }
        if (main_activity_s != null)
        {
            return main_activity_s;
        }
        throw new IllegalStateException("group notify context unavailable");
    }

    private static String groupPeerEventKey(final String group_identifier, final long peer_id)
    {
        if (group_identifier == null)
        {
            return ":" + peer_id;
        }
        return group_identifier.toLowerCase() + ":" + peer_id;
    }

    private static void markGroupPeerReconnectNoise(final String group_identifier, final long peer_id)
    {
        group_peer_reconnect_until.put(groupPeerEventKey(group_identifier, peer_id),
                System.currentTimeMillis() + GROUP_RECONNECT_SUPPRESS_MS);
    }

    private static boolean isGroupPeerReconnectSuppressed(final String group_identifier, final long peer_id)
    {
        final Long until = group_peer_reconnect_until.get(groupPeerEventKey(group_identifier, peer_id));
        if (until == null)
        {
            return false;
        }

        if (System.currentTimeMillis() > until)
        {
            group_peer_reconnect_until.remove(groupPeerEventKey(group_identifier, peer_id));
            return false;
        }

        return true;
    }

    private static boolean shouldSuppressReconnectJoin(final String group_identifier, final long peer_id)
    {
        return isGroupPeerReconnectSuppressed(group_identifier, peer_id);
    }

    static void add_system_message_to_group_chat(final String group_identifier, final String system_message)
    {
        add_system_message_to_group_chat(group_identifier, system_message, false);
    }

    /**
     * @param force when true, insert the system message even if the user disabled group system
     *              messages. Used for important, actionable notices (e.g. the UDP-blocked hint).
     */
    static void add_system_message_to_group_chat(final String group_identifier, final String system_message,
                                                 final boolean force)
    {
        // KHANDAQ #202: self-join can fire before the group chat_id is materialized (null identifier),
        // notably during the post-import reconnect burst. group_identifier.toLowerCase() below would NPE
        // in an unwrapped callback and crash the app — guard it.
        if (group_identifier == null)
        {
            return;
        }
        if (!force && !should_show_group_system_messages(group_identifier))
        {
            return;
        }

        if (system_message == null || system_message.isEmpty())
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
            if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_identifier.toLowerCase()))
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

    /** True if at least one online, non-relay friend exists that we could ask to invite us. */
    private static boolean has_invitable_online_friend()
    {
        try
        {
            final long[] friends = MainActivity.tox_self_get_friend_list();
            if (friends == null)
            {
                return false;
            }
            for (final long friend_number : friends)
            {
                if (MainActivity.tox_friend_get_connection_status(friend_number) == TOX_CONNECTION_NONE.value)
                {
                    continue;
                }
                final String pubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
                if (pubkey != null && HelperRelay.is_any_relay(pubkey))
                {
                    continue;
                }
                return true;
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    /**
     * When a PUBLIC group has been stuck CONNECTING with no reachable peers for a long time and we
     * have no online friend to pull us in via the friend-assisted invite path, the local network is
     * almost certainly blocking UDP peer discovery. Insert a one-shot, actionable hint into the group
     * chat so the user knows the workaround (have a member add them as a contact and invite them),
     * instead of staring at "connecting…" forever. Re-arms once the group recovers (peer_count > 1).
     */
    private static void maybe_hint_udp_blocked(final long group_num, @NonNull final String group_identifier)
    {
        if (group_udp_hint_shown.contains(group_identifier))
        {
            return; // already hinted for this group until it recovers
        }
        if (global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            return; // device is fully offline -> not a group-specific discovery problem; don't mislead
        }
        final Long alone_since = group_alone_since_ms.get(group_identifier);
        final long alone_ms = (alone_since == null) ? -1L : (System.currentTimeMillis() - alone_since);
        if (alone_ms < GROUP_UDP_BLOCKED_HINT_AFTER_MS)
        {
            return;
        }
        if (has_invitable_online_friend())
        {
            // friend-assisted invite can still rescue the join; don't cry wolf. Log once so this
            // decision is observable when debugging "why didn't the hint show".
            if (group_udp_hint_suppress_logged.add(group_identifier))
            {
                HelperGeneric.logI(TAG, "maybe_hint_udp_blocked:suppressed-has-friend id="
                        + group_identifier_short(group_identifier, false) + " alone_ms=" + alone_ms);
            }
            return;
        }
        group_udp_hint_shown.add(group_identifier);
        String hint;
        try
        {
            hint = context_s.getString(R.string.group_udp_blocked_hint);
        }
        catch (Exception e)
        {
            hint = "Can't connect to this group. Your network may be blocking peer discovery " +
                   "(common on mobile data or in some regions). Ask a member to add you as a contact " +
                   "and invite you into the group.";
        }
        add_system_message_to_group_chat(group_identifier, hint, true);
        HelperGeneric.logI(TAG, "maybe_hint_udp_blocked:shown id="
                + group_identifier_short(group_identifier, false) + " gn=" + group_num);
    }

    static void android_tox_callback_group_message_cb_method_wrapper(long group_number, long peer_id, int a_TOX_MESSAGE_TYPE, String message_orig, long length, long message_id, boolean is_private_message)
    {
        // HelperGeneric.logI(TAG, "android_tox_callback_group_message_cb_method_wrapper:gn=" + group_number + " peerid=" + peer_id +
        //           " message=" + message_orig + " is_private_message=" + is_private_message);

        long res = tox_group_self_get_peer_id(group_number);
        if (res == peer_id)
        {
            // HINT: do not add our own messages, they are already in the DB!
            HelperGeneric.logI(TAG, "group_message_cb:gn=" + group_number + " peerid=" + peer_id + " ignoring own message");
            return;
        }

        // KHANDAQ (#59): the volatile self-peer-id check above misses our OWN message when peer ids
        // have churned (a rejoin reassigns ids) — it then lands as an INCOMING message attributed to
        // "another" peer. Also drop it by the STABLE self pubkey (mirrors the iOS #51 fix).
        final String self_pubkey_for_echo = resolve_group_self_pubkey(group_number);
        final String sender_pubkey_for_echo = tox_group_peer_get_public_key__wrapper(group_number, peer_id);
        if ((self_pubkey_for_echo != null) && (sender_pubkey_for_echo != null) &&
            self_pubkey_for_echo.equalsIgnoreCase(sender_pubkey_for_echo))
        {
            HelperGeneric.logI(TAG, "group_message_cb:gn=" + group_number + " ignoring own message by pubkey");
            return;
        }

        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        TrifaToxService.wakeup_tox_thread();

        // TODO: add message ID later --------
        String message_ = "";
        String message_id_ = "";
        message_ = message_orig;
        message_id_ = "";
        // TODO: add message ID later --------

        if (!GroupMessageLayoutHelper.hasNonBlankText(message_))
        {
            HelperGeneric.logI(TAG, "group_message_cb: ignoring empty message from peer " + peer_id);
            return;
        }

        if (!is_private_message)
        {
            message_id_ = fourbytes_of_long_to_hex(message_id);
            check_group_message_sequence_gap(group_number, message_id);
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
            display_toast(context_s.getString(R.string.group_incoming_message_error), true, 0);
            return;
        }

        if (group_temp.group_identifier.toLowerCase().compareTo(group_id.toLowerCase()) != 0)
        {
            display_toast(context_s.getString(R.string.group_incoming_message_error), true, 0);
            return;
        }

        final String peer_pubkey = HelperGroup.tox_group_peer_get_public_key__wrapper(group_number, peer_id);
        on_group_message_received_from_peer(group_id, group_number, peer_pubkey);

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
            if (GroupMentionHelper.isSelfMentioned(message_, group_id))
            {
                do_notification = true;
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
            //HelperGeneric.logI(TAG,
            //      "noti_and_badge:002group:" + group_message_list_activity.get_current_group_id() + ":" + group_id);
            if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_id))
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
        // HelperGeneric.logI(TAG, "message_id_tox=" + message_id_ + " message_id=" + message_id);

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

        // KHANDAQ (#68): drop duplicate incoming group messages BEFORE insert. NGC re-delivers a message
        // (mesh relay + lossless retransmit), firing this LIVE callback many times for the SAME message;
        // only the history-sync path had dedup, so the live path produced ~Nx copies. Match by the stable
        // (group + sender pubkey + tox message id + text) within a recent window.
        if (!is_private_message)
        {
            GroupMessage existing_dup = get_last_group_message_in_this_group_within_n_seconds_from_sender_pubkey(
                    m.group_identifier, m.tox_group_peer_pubkey, m.sent_timestamp, m.message_id_tox, 60, m.text);
            if (existing_dup != null)
            {
                HelperGeneric.logI(TAG, "group_message_cb: skip duplicate incoming group message msg_id=" + m.message_id_tox);
                return;
            }

            // KHANDAQ (#89): also collapse a copy already stored under a DIFFERENT (mis-attributed) author —
            // e.g. a sync/relay peer delivered this same message first under a wrong pubkey. This LIVE
            // callback carries the authoritative real-time sender, so correct the existing row's attribution
            // and do not insert a second bubble.
            GroupMessage cross_author = find_group_message_by_msgid_and_text(m.group_identifier, m.message_id_tox, m.text);
            if (cross_author != null)
            {
                if ((cross_author.tox_group_peer_pubkey == null)
                        || !cross_author.tox_group_peer_pubkey.equalsIgnoreCase(m.tox_group_peer_pubkey))
                {
                    try
                    {
                        orma.updateGroupMessage().idEq(cross_author.id).
                                tox_group_peer_pubkey(m.tox_group_peer_pubkey).
                                tox_group_peername(m.tox_group_peername).
                                execute();
                        HelperFriend.refresh_chat_list_group_row_wrapper(group_id);
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                HelperGeneric.logI(TAG, "group_message_cb: collapse cross-author duplicate msg_id=" + m.message_id_tox);
                return;
            }
        }

        if (group_message_list_activity != null)
        {
            if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_id.toLowerCase()))
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
            HelperGeneric.logI(TAG, "group_msg_recv:ts=" + System.currentTimeMillis() + " gn=" + group_number + " peer=" + peer_id
                    + " gid=" + group_identifier_short(group_id, false) + " tox_msg_id=" + message_id
                    + " text_len=" + (message_ != null ? message_.length() : 0)
                    + (message_ != null && message_.length() <= 64 ? " text=" + message_ : ""));
            HelperGeneric.logI(TAG, "group_message_cb:recv gn=" + group_number + " peer=" + peer_id + " gid="
                    + group_identifier_short(group_id, false) + " new_msg_id=" + new_msg_id
                    + " text_len=" + (message_ != null ? message_.length() : 0));
            log_group_peer_mesh_diagnostics(group_number, group_id, "msg_recv");
        }

        ensure_group_peer_recorded(group_id, group_number, peer_pubkey, m.tox_group_peername);

        if (!is_private_message && message_id_ != null && message_id_.length() >= 4)
        {
            send_ngch_delivery_receipt(group_number, peer_id, message_id_);
        }

        HelperFriend.refresh_chat_list_group_row_wrapper(group_id);
        HelperFriend.add_all_friends_clear_wrapper(0);

        if (do_notification)
        {
            String notificationText = GroupMentionHelper.notificationPreviewText(m.text);
            if (GroupMentionHelper.isSelfMentioned(m.text, group_id))
            {
                notificationText = context_s.getString(R.string.group_mention_notification_prefix) + notificationText;
            }
            change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.group_identifier, groupname, notificationText);
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

    // KHANDAQ (#89): find ANY peer's copy of this exact message, regardless of which peer it is attributed
    // to, keyed on the STABLE per-message id (a sender-generated random_u32 that is identical across every
    // copy/relay/sync of one logical message) + exact text. The sender-keyed dedup above cannot collapse a
    // copy whose author pubkey differs — which is exactly what happens when a relaying/sync peer re-broadcast
    // someone's message under a stale/wrong pubkey (NGC peer-id churn), producing a second bubble shown as a
    // DIFFERENT participant. message_id + exact text avoids merging two distinct people sending the same
    // short text (their ids differ); a 32-bit-id AND identical-text collision between distinct messages is
    // negligible.
    static GroupMessage find_group_message_by_msgid_and_text(
            String group_identifier, String message_id_tox, final String message_text)
    {
        try
        {
            if ((message_id_tox == null) || (message_id_tox.length() < 8) || (group_identifier == null))
            {
                return null;
            }

            return (GroupMessage) orma.selectFromGroupMessage().
                    group_identifierEq(group_identifier.toLowerCase()).
                    message_id_toxEq(message_id_tox.toLowerCase()).
                    textEq(message_text).
                    limit(1).
                    toList().
                    get(0);
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
        // HelperGeneric.logI(TAG,
        //       "group_message_add_from_sync:cf_num=" + group_identifier + " pnum=" + peer_number2 + " msg=" + message);

        if (!GroupMessageLayoutHelper.hasNonBlankText(message))
        {
            HelperGeneric.logI(TAG, "group_message_add_from_sync: ignoring empty message");
            return;
        }

        // KHANDAQ (#89): if we already have this exact message (stable msg id + text) from ANY peer — e.g.
        // the live broadcast already delivered it from the real author — do NOT insert a synced copy. A
        // relaying peer can embed a wrong/stale author pubkey (NGC peer-id churn), which the sender-keyed
        // dedup below cannot collapse, producing a second bubble attributed to a DIFFERENT participant.
        if (find_group_message_by_msgid_and_text(group_identifier, message_id, message) != null)
        {
            HelperGeneric.logI(TAG, "group_message_add_from_sync: skip cross-peer duplicate msg_id=" + message_id);
            return;
        }

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
            // HelperGeneric.logI(TAG, "conference_message_add_from_sync:own peer");
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
            HelperGeneric.logI(TAG, "group_message_add_from_sync:cf_num=" + group_identifier + " pnum=" + peer_number2 + " msg=" +
                       message + " we dont have the group anymore????");
            return;
        }

        // KHANDAQ (audit2 #1, storage half): history sync had no bound of its own — a peer could keep
        // offering "history" indefinitely and every accepted record became a permanent DB row plus a
        // notification. Budget it per group. Live traffic never passes through here, so a busy group is
        // unaffected; only what arrives claiming to be OLD is capped.
        final NgcHistorySyncBudget.State sync_budget = NgcHistorySyncBudget.stateFor(group_identifier);
        if (!NgcHistorySyncBudget.isSeeded(sync_budget))
        {
            int already_stored = 0;
            try
            {
                already_stored = orma.selectFromGroupMessage().
                        group_identifierEq(group_identifier.toLowerCase(Locale.ROOT)).
                        was_syncedEq(true).count();
            }
            catch (Exception e)
            {
                // Seed with 0 rather than leaving it unseeded: the budget then bounds what THIS run can
                // ingest, which is the flood we care about. Leaving it unseeded would mean no row budget.
                HelperGeneric.logI(TAG, "group_message_add_from_sync:sync budget seed failed:" + e.getMessage());
            }
            NgcHistorySyncBudget.seed(sync_budget, already_stored);
        }
        final int synced_text_bytes = message.getBytes(StandardCharsets.UTF_8).length;
        if (!NgcHistorySyncBudget.fits(sync_budget, synced_text_bytes))
        {
            HelperGeneric.logI(TAG, "group_message_add_from_sync: history budget exhausted for group, dropping synced msg");
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
            // HelperGeneric.logI(TAG, "conference_message_add_from_sync:noti_and_badge:002conf:" +
            //            conference_message_list_activity.get_current_conf_id() + ":" + conf_id);

            if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_identifier))
            {
                // HelperGeneric.logI(TAG, "noti_and_badge:003:");
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
        m.group_identifier = group_identifier.toLowerCase(Locale.ROOT);
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.sent_timestamp = sent_timestamp_in_ms;
        m.rcvd_timestamp = System.currentTimeMillis();
        m.text = message;
        m.message_id_tox = message_id;
        m.was_synced = true;
        m.TRIFA_SYNC_TYPE = sync_type;
        HelperGeneric.logI(TAG, "add TRIFA_SYNC_TYPE=" + sync_type + " syncer_pubkey_01:" + syncer_pubkey);
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
            if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_identifier))
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
            // HelperGeneric.logI(TAG, "conference_message_add_from_sync:new_msg_id=" + new_msg_id);
        }

        // KHANDAQ (audit F-6): nothing in a sync packet is signed, so the author pubkey and the peer name
        // in it are whatever the syncing peer chose to put there. They may drive THIS row's display value
        // (above), but they must not create rows in the persistent peer table — that would let one peer
        // invent group members, and (for a row whose peer_name is still empty) put a name of its choosing
        // on one. So only record a peer that toxcore currently lists in the group, and let
        // ensure_group_peer_recorded resolve the name itself instead of taking the one off the wire.
        // Peers not in the peer list stay unrecorded: the message row keeps its own tox_group_peername,
        // which the list holders already fall back to when name resolution only yields a short hex id.
        if (peer_number2 >= 0)
        {
            ensure_group_peer_recorded(group_identifier, group_num_, peer_pubkey, null);
        }

        // KHANDAQ (audit2 #1): the row is stored, so book it against the per-group budget.
        NgcHistorySyncBudget.recordAccepted(sync_budget, synced_text_bytes);

        HelperFriend.refresh_chat_list_group_row_wrapper(group_identifier);
        HelperFriend.add_all_friends_clear_wrapper(0);

        // KHANDAQ (audit2 #1, notification half): rate-limited rather than suppressed. A message that
        // genuinely arrived while we were offline still deserves to notify; it just must not be able to
        // notify a hundred times. Rows past the limit are still stored and still update the unread badge
        // (do_badge_update above is untouched) — only the notification is dropped.
        if (do_notification && !NgcHistorySyncBudget.allowNotification(sync_budget, System.currentTimeMillis()))
        {
            HelperGeneric.logI(TAG, "group_message_add_from_sync: notification rate limit hit, storing silently");
            do_notification = false;
        }

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
            HelperGeneric.logI(TAG, "send_group_image: data length has wrong size: " + data_length);
            display_toast(context_s.getString(R.string.group_file_send_failed), true, 100);
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
                HelperGeneric.logI(TAG,"put " + length + " bytes into buffer");
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
        HelperGeneric.logI(TAG,"put " + length_sum + " bytes TOTAL into buffer, and should match " + g.filesize);
        // -- now fill the data from file --

        if (length_sum < 1 || length_sum != g.filesize)
        {
            HelperGeneric.logI(TAG, "send_group_image: file read mismatch len=" + length_sum + " expected=" + g.filesize);
            mark_ngc_file_transfer_failed(g.group_identifier, g.msg_id_hash);
            display_toast(context_s.getString(R.string.group_file_send_failed), true, 100);
            return;
        }

        final long groupNum = tox_group_by_groupid__wrapper(g.group_identifier);
        if (groupNum < 0)
        {
            HelperGeneric.logI(TAG, "send_group_image: group not found");
            mark_ngc_file_transfer_failed(g.group_identifier, g.msg_id_hash);
            display_toast(context_s.getString(R.string.group_file_send_failed), true, 100);
            return;
        }

        TrifaToxService.wakeup_tox_thread();
        FileTransferDebug.logGroupConnection(groupNum);
        if (!NgcGroupFileTransfer.waitForGroupConnected(groupNum, g, 45_000L))
        {
            HelperGeneric.logI(TAG, "send_group_image: group not connected — queue retry");
            schedule_auto_retry_group_file_send(g);
            return;
        }

        record_ngc_transfer_started(g.group_identifier, g.msg_id_hash);
        notify_group_file_send_progress_bytes(g.group_identifier, g.msg_id_hash, 0L, length_sum);

        byte[] data = new byte[(int)data_length];
        data_buf.rewind();
        data_buf.get(data);
        FileTransferDebug.logGroupCustomPacketSend(groupNum, (int) data_length);
        final int sendRes = tox_group_send_custom_packet(groupNum,
                                     1,
                                     data,
                                     (int)data_length);
        FileTransferDebug.logGroupCustomPacketResult(sendRes);
        if (!is_ngc_custom_packet_send_ok(sendRes))
        {
            HelperGeneric.logI(TAG, "send_group_image: packet failed res=" + sendRes
                    + " (" + HelperGroup.describe_ngc_custom_packet_error(sendRes) + ")");
            mark_ngc_file_transfer_failed(g.group_identifier, g.msg_id_hash);
            display_toast(context_s.getString(R.string.group_file_send_failed), true, 100);
            return;
        }

        notify_group_file_send_progress_bytes(g.group_identifier, g.msg_id_hash, length_sum, length_sum);
        HelperGeneric.logI(TAG, "send_group_image:done bytes=" + length_sum + " res=" + sendRes);
    }

    /** JNI returns {@code 1} on success, {@code 0} or negative on failure — never treat {@code 0} as ok. */
    static boolean is_ngc_custom_packet_send_ok(final int result)
    {
        return result > 0;
    }

    static String describe_ngc_custom_packet_error(final int result)
    {
        if (result >= 0)
        {
            return "ok";
        }
        if (result == -100)
        {
            return "no_ngc";
        }
        if (result == -101)
        {
            return "no_tox";
        }
        if (result <= -100)
        {
            final int err = -(result + 100);
            switch (err)
            {
                case 1:
                    return "group_not_found";
                case 2:
                    return "too_long";
                case 3:
                    return "empty";
                case 4:
                    return "permissions";
                case 5:
                    return "disconnected";
                default:
                    return "tox_err_" + err;
            }
        }
        return "err_" + result;
    }

    static void cancelStaleOutgoingChunkedSends(final String groupId, final String activeMsgIdHash)
    {
        if (groupId == null || activeMsgIdHash == null)
        {
            return;
        }
        try
        {
            final List<GroupMessage> pending = orma.selectFromGroupMessage().
                    group_identifierEq(groupId.toLowerCase(Locale.ROOT)).
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    toList();
            for (final GroupMessage gm : pending)
            {
                if (gm == null || gm.msg_id_hash == null
                        || activeMsgIdHash.equalsIgnoreCase(gm.msg_id_hash))
                {
                    continue;
                }
                if (!NgcGroupFileTransfer.shouldUseChunkedTransfer(gm.filesize))
                {
                    continue;
                }
                if (is_ngc_outgoing_send_complete(groupId, gm.msg_id_hash)
                        || HelperFiletransfer.isGroupMessageMediaReady(gm, null))
                {
                    continue;
                }
                cancel_ngc_file_send(groupId, gm.msg_id_hash);
                HelperGeneric.logI(TAG, "cancelStaleOutgoingChunkedSends:msg="
                        + gm.msg_id_hash.substring(0, 8));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void send_group_file(final GroupMessage g)
    {
        if (g == null)
        {
            return;
        }

        if (NgcGroupFileTransfer.shouldUseChunkedTransfer(g.filesize))
        {
            cancelStaleOutgoingChunkedSends(g.group_identifier, g.msg_id_hash);
            NgcGroupFileTransfer.sendGroupFileChunked(g);
        }
        else
        {
            NgcGroupFileTransfer.sendGroupFileSmall(g);
        }
    }

    static void start_group_file_send_by_hash(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        final GroupMessage m = find_group_message_by_msg_id_hash(groupId, msgIdHash);
        if (m != null)
        {
            send_group_file(m);
        }
    }

    private static final ConcurrentHashMap<String, Integer> ngc_file_transfer_progress = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ngc_file_transfer_bytes_done = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> ngc_outgoing_send_complete = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ngc_transfer_speed_last_ts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ngc_transfer_speed_last_bytes = new ConcurrentHashMap<>();
    // Session-only cancels ("a newer send superseded this one"). Values are always TRUE; an entry is
    // dropped again by any resume/retry, so a superseded send still resumes on its own.
    private static final ConcurrentHashMap<String, Boolean> ngc_cancelled_sends = new ConcurrentHashMap<>();
    // KHANDAQ (audit2 #5): in-memory mirror of the persisted USER cancels. This set is one-way:
    // cancel_ngc_file_send_by_user() adds, and only retry_group_file_send() (the user "retry" button)
    // removes. Nothing on the automatic-retry path may touch it.
    private static final java.util.Set<String> ngc_user_cancelled_sends =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Keys whose tombstone row was looked up SUCCESSFULLY and was absent. Only memoises the negative
    // answer; a positive one lands in ngc_user_cancelled_sends instead, and a failed read (audit3 #1)
    // lands in neither, so it is retried. Keeps the per-chunk gate IO-free after the first look.
    private static final java.util.Set<String> ngc_cancel_lookup_clean =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Serialises the authoritative mutations (user cancel / user retry) against the lazy tombstone
    // lookup, which is the only place that writes the cache from a non-user thread. Without it the
    // lookup's SQL read is a wide window in which a send thread can write its stale "not cancelled"
    // answer over a cancel the user just tapped. On the READ side it is taken at most once per key,
    // so the per-chunk gate stays lock-free after the first look. It IS taken on the UI thread, but
    // only by the two user actions (cancel / retry), which also do a short read-back of the tombstone
    // row while holding it — a tap already blocks, and correctness of the cancel wins over that.
    private static final Object ngc_cancel_state_lock = new Object();
    // KHANDAQ (audit2 #5): tombstone key prefix in the existing g_opts key/value table
    // (TRIFADatabaseGlobalsNew) — persisting there needs no schema change / no migration.
    private static final String NGC_CANCEL_TOMBSTONE_PREFIX = "kqngccancel_";
    // KHANDAQ (audit3 #1): tri-state result of looking at the tombstone row. "row is not there" and
    // "we could not find out" are NOT the same answer and must not be collapsed: the first means the
    // send is live, the second means we do not know and therefore must not emit data.
    private static final int NGC_TOMBSTONE_ABSENT = 0;
    private static final int NGC_TOMBSTONE_PRESENT = 1;
    private static final int NGC_TOMBSTONE_UNREADABLE = -1;
    // KHANDAQ (audit3 #3): keys whose tombstone lookup was kicked off by the (non-blocking) UI path and
    // has not come back yet. Purely a de-dup guard so a fast-scrolling list cannot queue the same read
    // dozens of times; entries are removed when the lookup finishes, failure included.
    private static final java.util.Set<String> ngc_cancel_lookup_inflight =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    // KHANDAQ (audit3 #3): the UI must never do the tombstone SQL itself — row binding runs on the main
    // thread and the lookup takes ngc_cancel_state_lock, which a chunk/tox thread can be holding.
    private static final java.util.concurrent.ExecutorService ngc_cancel_lookup_exec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "ngc-cancel-lookup");
                t.setDaemon(true);
                return t;
            });
    private static final ConcurrentHashMap<String, Boolean> ngc_file_transfer_failed = new ConcurrentHashMap<>();
    /** Last successfully sent chunk index for resume after disconnect (-1 = none). */
    private static final ConcurrentHashMap<String, Integer> ngc_outgoing_last_chunk_sent = new ConcurrentHashMap<>();

    static int ngc_outgoing_last_chunk_index(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return -1;
        }
        return ngc_outgoing_last_chunk_sent.getOrDefault(ngc_file_progress_key(groupId, msgIdHash), -1);
    }

    static void record_ngc_outgoing_chunk_sent(final String groupId, final String msgIdHash, final int chunkIndex)
    {
        if (groupId == null || msgIdHash == null || chunkIndex < 0)
        {
            return;
        }
        ngc_outgoing_last_chunk_sent.put(ngc_file_progress_key(groupId, msgIdHash), chunkIndex);
    }

    static void clear_ngc_outgoing_send_resume_state(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_outgoing_last_chunk_sent.remove(ngc_file_progress_key(groupId, msgIdHash));
    }

    /** True when chunks were sent recently — suppress duplicate FILE_REQUEST restarts. */
    static boolean is_ngc_outgoing_send_recently_active(final String groupId, final String msgIdHash,
                                                        final long withinMs)
    {
        if (groupId == null || msgIdHash == null || withinMs < 1L)
        {
            return false;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        if (ngc_outgoing_last_chunk_index(groupId, msgIdHash) < 0)
        {
            return false;
        }
        final Long last = ngc_transfer_last_progress_ts.get(key);
        return last != null && (System.currentTimeMillis() - last) < withinMs;
    }

    static boolean is_ngc_outgoing_send_complete(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        final Boolean complete = ngc_outgoing_send_complete.get(ngc_file_progress_key(groupId, msgIdHash));
        return complete != null && complete;
    }

    static void mark_ngc_outgoing_send_complete(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_outgoing_send_complete.put(ngc_file_progress_key(groupId, msgIdHash), true);
        post_group_file_progress_refresh(groupId, msgIdHash);
    }

    static void clear_ngc_outgoing_send_complete(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_outgoing_send_complete.remove(ngc_file_progress_key(groupId, msgIdHash));
    }

    static long ngc_file_transfer_bytes_done_count(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return -1L;
        }
        final Long value = ngc_file_transfer_bytes_done.get(ngc_file_progress_key(groupId, msgIdHash));
        return value != null ? value : -1L;
    }

    private static final ConcurrentHashMap<String, Long> ngc_transfer_start_ts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> ngc_transfer_last_progress_ts = new ConcurrentHashMap<>();
    // KHANDAQ (#120): incoming group files the user EXPLICITLY tapped to download. Lets the per-file
    // download bypass the global attachment-download policy (Никогда / Wi-Fi-only) for that file only.
    private static final java.util.Set<String> ngc_manual_download_requested =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Runnable> ngc_outgoing_file_timeout_tasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Runnable> ngc_incoming_file_timeout_tasks = new ConcurrentHashMap<>();

    private static final long NGC_INCOMING_FILE_TIMEOUT_MS = 30_000L;
    private static final long NGC_TRANSFER_STALL_MS = 30_000L;
    // KHANDAQ: how often the receiver re-checks an in-flight chunked transfer and (if it has stalled)
    // re-asks the sender for the still-missing chunks via selective NACK. iOS NACKs every ~2.5s;
    // Android used to only re-ask once per 30s timeout, so a single dropped chunk left the file stuck
    // until the user manually tapped "retry". Tick fast, but only actually NACK when no new chunk has
    // arrived for NGC_NACK_STALL_MS — a normally-progressing transfer is never disturbed.
    private static final long NGC_NACK_RETRY_MS = 3_500L;
    private static final long NGC_NACK_STALL_MS = 2_000L; // KHANDAQ (#126): first NACK at ~3.5s, not ~7s

    static float ngc_file_transfer_speed_bps(final String groupId, final String msgIdHash, final long bytesDone)
    {
        if (groupId == null || msgIdHash == null)
        {
            return 0f;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final long now = System.currentTimeMillis();
        final Long lastTs = ngc_transfer_speed_last_ts.get(key);
        final Long lastBytes = ngc_transfer_speed_last_bytes.get(key);
        ngc_transfer_speed_last_ts.put(key, now);
        ngc_transfer_speed_last_bytes.put(key, bytesDone);
        if (lastTs == null || lastBytes == null || bytesDone <= lastBytes)
        {
            return 0f;
        }
        final long deltaMs = Math.max(1L, now - lastTs);
        return ((float) (bytesDone - lastBytes) / (float) deltaMs) * 1000f;
    }

    /**
     * Session-only cancel: stops the send that is running right now. Used for internal
     * "superseded by a newer send" cancels, which MUST still auto-resume after a restart.
     * For a cancel the user asked for use {@link #cancel_ngc_file_send_by_user}.
     */
    static void cancel_ngc_file_send(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_cancelled_sends.put(ngc_file_progress_key(groupId, msgIdHash), true);
        clear_ngc_outgoing_send_complete(groupId, msgIdHash);
        clear_ngc_file_transfer_progress(groupId, msgIdHash);
        post_group_file_progress_refresh(groupId, msgIdHash);
    }

    /**
     * KHANDAQ (audit2 #5): user-initiated cancel of an outgoing group file. The cancel used to live
     * only in the static map, so after an app restart the remote FILE_REQUEST handler happily served
     * the still-persisted outgoing row again — the user believed the file was retracted. Persist a
     * tombstone so the cancel survives the process.
     * <p>
     * The tombstone is one-way: nothing but {@link #retry_group_file_send} (the user's own "retry")
     * lifts it again, so neither an automatic retry nor a slow cache reader can un-cancel it.
     */
    static void cancel_ngc_file_send_by_user(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        synchronized (ngc_cancel_state_lock)
        {
            try
            {
                // In-memory first: even if the DB write below fails, the cancel holds for this session.
                ngc_user_cancelled_sends.add(key);
                ngc_cancel_lookup_clean.remove(key);
                HelperGeneric.set_g_opts(ngc_cancel_tombstone_key(groupId, msgIdHash), "1");
                // KHANDAQ (audit3 #1): set_g_opts() swallows its own failures, and a cancel that did
                // not reach the DB is a cancel that dies at the next app start — exactly the bug the
                // tombstone exists to fix. Confirm the row and write once more if it is missing.
                if (read_persisted_ngc_cancel(groupId, msgIdHash) != NGC_TOMBSTONE_PRESENT)
                {
                    HelperGeneric.set_g_opts(ngc_cancel_tombstone_key(groupId, msgIdHash), "1");
                    if (read_persisted_ngc_cancel(groupId, msgIdHash) != NGC_TOMBSTONE_PRESENT)
                    {
                        Log.i(TAG, "cancel_ngc_file_send_by_user:tombstone not persisted key=" + key);
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "cancel_ngc_file_send_by_user:EE:" + e.getMessage());
            }
        }
        cancel_ngc_file_send(groupId, msgIdHash);
    }

    /**
     * The gate every emitting path uses: true for a persisted user cancel, for a session-only
     * "superseded" cancel, and (audit3 #1) while the tombstone row cannot be read at all. Blocking on
     * the first look per key — background/tox threads only.
     */
    static boolean is_ngc_send_cancelled(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        if (is_ngc_send_cancelled_by_user(groupId, msgIdHash))
        {
            return true;
        }
        return ngc_cancelled_sends.containsKey(ngc_file_progress_key(groupId, msgIdHash));
    }

    /**
     * KHANDAQ (audit2 #5): true when the USER cancelled this send (as opposed to it being superseded
     * in-session). Answers from memory once the tombstone row has been looked at; the first lookup per
     * key runs under {@link #ngc_cancel_state_lock}, so it can never write its stale answer over a
     * cancel (or a user retry) that landed while it was inside SQL.
     * <p>
     * KHANDAQ (audit3 #1): this is the AUTHORITATIVE (blocking) answer — call it from sending paths
     * only. It fails CLOSED: if the tombstone row cannot be read at all, we report "cancelled" and
     * cache nothing, so the next call retries the read. The old code turned every read failure into
     * "not cancelled" and then memoised it for the whole process lifetime, i.e. one transient sqlite
     * error permanently un-cancelled a cancelled upload. Not sending a file we might have been allowed
     * to send is recoverable (the honest send resumes as soon as the DB answers again); sending a file
     * the user retracted is not.
     * <p>
     * For the UI use {@link #is_ngc_send_cancelled_by_user_cached}.
     */
    static boolean is_ngc_send_cancelled_by_user(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        if (ngc_user_cancelled_sends.contains(key))
        {
            return true;
        }
        if (ngc_cancel_lookup_clean.contains(key))
        {
            return false;
        }
        synchronized (ngc_cancel_state_lock)
        {
            // Re-check: a cancel may have landed while we were waiting for the lock.
            if (ngc_user_cancelled_sends.contains(key))
            {
                return true;
            }
            if (ngc_cancel_lookup_clean.contains(key))
            {
                return false;
            }
            final int tombstone = read_persisted_ngc_cancel(groupId, msgIdHash);
            if (tombstone == NGC_TOMBSTONE_PRESENT)
            {
                ngc_user_cancelled_sends.add(key);
                return true;
            }
            if (tombstone == NGC_TOMBSTONE_ABSENT)
            {
                ngc_cancel_lookup_clean.add(key);
                return false;
            }
            // NGC_TOMBSTONE_UNREADABLE: do NOT memoise either way, and hold the send back for now.
            return true;
        }
    }

    /**
     * KHANDAQ (audit3 #3): non-blocking view for row binding on the main thread. It answers from the
     * two in-memory sets only — no SQL, and it never takes {@link #ngc_cancel_state_lock}, which a
     * chunk/tox thread may be holding. On a cache miss it returns "not cancelled" for this frame and
     * kicks the authoritative lookup onto a background thread; when that lookup does find a tombstone
     * it forces a refresh of exactly that row, so a cancelled send still lands on the FAILED phase
     * (the phase carrying the retry button) shortly after the list first shows it.
     * <p>
     * This is deliberately weaker than {@link #is_ngc_send_cancelled_by_user}: the UI may briefly lie,
     * the sending paths may not, so nothing that emits bytes may use this variant.
     */
    static boolean is_ngc_send_cancelled_by_user_cached(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final Boolean cached = ngc_user_cancel_from_cache(key);
        if (cached != null)
        {
            return cached;
        }
        schedule_ngc_cancel_lookup(groupId, msgIdHash, key);
        return false;
    }

    /**
     * The memoised tombstone answer, or null when this key has never been looked up (or the last look
     * failed). Reads the two lock-free sets only — never SQL, never {@link #ngc_cancel_state_lock}.
     */
    private static Boolean ngc_user_cancel_from_cache(final String key)
    {
        if (ngc_user_cancelled_sends.contains(key))
        {
            return Boolean.TRUE;
        }
        if (ngc_cancel_lookup_clean.contains(key))
        {
            return Boolean.FALSE;
        }
        return null;
    }

    private static void schedule_ngc_cancel_lookup(final String groupId, final String msgIdHash,
                                                   final String key)
    {
        if (!ngc_cancel_lookup_inflight.add(key))
        {
            return;
        }
        try
        {
            ngc_cancel_lookup_exec.execute(() ->
            {
                try
                {
                    // Ask the store directly rather than is_ngc_send_cancelled_by_user(): that one
                    // answers "cancelled" for an UNREADABLE tombstone too (fail closed, correct for
                    // the sending paths). Using it here would repaint the row, the rebind would miss
                    // the cache again — nothing was cached — and schedule another lookup, spinning
                    // this executor for as long as the database stays unreadable. Only a tombstone we
                    // actually read changes what the row shows.
                    //
                    // KHANDAQ (#247, found by device QA): the result must be MEMOISED here, which it
                    // was not. Reading the tombstone and only posting a refresh left the caches empty,
                    // so the rebind asked again, got "not cancelled" again, and scheduled yet another
                    // lookup — a cancelled row sat at "Ожидание отправителя" forever after an app
                    // restart while this executor re-queried the DB on every bind. That is exactly the
                    // state audit2 #5 introduced the tombstone to prevent ("a user cancel now outlives
                    // the process, so it also needs a UI state that outlives it"); the non-blocking
                    // variant added later quietly stopped delivering it.
                    //
                    // Memoised under ngc_cancel_state_lock, the same lock the blocking lookup and
                    // clear_ngc_user_cancel take, so a retry that lifts the cancel while we are reading
                    // either lands entirely before us (we then read ABSENT) or entirely after us (it
                    // removes what we just wrote). UNREADABLE still memoises nothing, so a row is
                    // reconsidered once the database can be read.
                    final int state;
                    synchronized (ngc_cancel_state_lock)
                    {
                        state = read_persisted_ngc_cancel(groupId, msgIdHash);
                        if (state == NGC_TOMBSTONE_PRESENT)
                        {
                            ngc_user_cancelled_sends.add(key);
                        }
                        else if (state == NGC_TOMBSTONE_ABSENT)
                        {
                            ngc_cancel_lookup_clean.add(key);
                        }
                    }

                    if (state == NGC_TOMBSTONE_PRESENT || ngc_user_cancel_from_cache(key))
                    {
                        // The throttle is reset first so this one-shot state change cannot be eaten
                        // by the per-chunk refresh rate limit.
                        ngc_progress_refresh_ts.remove(key);
                        post_group_file_progress_refresh(groupId, msgIdHash);
                    }
                }
                catch (Exception e)
                {
                    Log.i(TAG, "schedule_ngc_cancel_lookup:EE:" + e.getMessage());
                }
                finally
                {
                    // Removed on failure too: an unreadable tombstone caches nothing, so the next bind
                    // of this row is allowed to try again.
                    ngc_cancel_lookup_inflight.remove(key);
                }
            });
        }
        catch (Exception e)
        {
            ngc_cancel_lookup_inflight.remove(key);
        }
    }

    private static String ngc_cancel_tombstone_key(final String groupId, final String msgIdHash)
    {
        return NGC_CANCEL_TOMBSTONE_PREFIX + ngc_file_progress_key(groupId, msgIdHash);
    }

    /**
     * KHANDAQ (audit3 #1): reads the tombstone row and reports ABSENT / PRESENT / UNREADABLE.
     * <p>
     * It queries orma directly instead of going through HelperGeneric.get_g_opts(): that helper
     * swallows every exception and returns null, which makes "no such row" and "the read blew up"
     * indistinguishable — exactly the confusion this method exists to remove.
     */
    private static int read_persisted_ngc_cancel(final String groupId, final String msgIdHash)
    {
        try
        {
            if (orma == null)
            {
                // DB not open (yet): unknown, not "no cancel".
                return NGC_TOMBSTONE_UNREADABLE;
            }
            final long rows = orma.selectFromTRIFADatabaseGlobalsNew().
                    keyEq(ngc_cancel_tombstone_key(groupId, msgIdHash)).count();
            return (rows > 0) ? NGC_TOMBSTONE_PRESENT : NGC_TOMBSTONE_ABSENT;
        }
        catch (Exception e)
        {
            Log.i(TAG, "read_persisted_ngc_cancel:EE:" + e.getMessage());
            return NGC_TOMBSTONE_UNREADABLE;
        }
    }

    /**
     * Lifts a user cancel. The DB row and both in-memory sets are updated under the same lock the lazy
     * lookup takes, so a send thread that is mid-lookup cannot re-memoise "cancelled" afterwards.
     */
    private static void clear_ngc_user_cancel(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        synchronized (ngc_cancel_state_lock)
        {
            try
            {
                HelperGeneric.del_g_opts(ngc_cancel_tombstone_key(groupId, msgIdHash));
                // KHANDAQ (audit3 #1): del_g_opts() swallows its own failures, so confirm the row is
                // really gone and try once more if it is not — otherwise the tombstone would come back
                // from the DB on the next launch and silently undo the retry the user just asked for.
                if (read_persisted_ngc_cancel(groupId, msgIdHash) == NGC_TOMBSTONE_PRESENT)
                {
                    HelperGeneric.del_g_opts(ngc_cancel_tombstone_key(groupId, msgIdHash));
                    if (read_persisted_ngc_cancel(groupId, msgIdHash) == NGC_TOMBSTONE_PRESENT)
                    {
                        Log.i(TAG, "clear_ngc_user_cancel:tombstone survived delete key=" + key);
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "clear_ngc_user_cancel:EE:" + e.getMessage());
            }
            // In-memory always wins for this session: the user asked for the retry, so it runs now even
            // if the row could not be removed.
            ngc_user_cancelled_sends.remove(key);
            ngc_cancel_lookup_clean.add(key);
        }
    }

    /**
     * USER "retry" on an outgoing group file — the ONLY path that may lift a user cancel. Do not call
     * it from watchdogs / reconnect handlers: use {@link #resume_group_file_send} there.
     */
    static void retry_group_file_send(final GroupMessage message)
    {
        if (message == null)
        {
            return;
        }
        // KHANDAQ (audit2 #5): lift the user cancel first (DB row + both sets, atomically), then fall
        // through to the ordinary resume — which now sees an uncancelled file.
        clear_ngc_user_cancel(message.group_identifier, message.msg_id_hash);
        resume_group_file_send(message);
    }

    /**
     * KHANDAQ (audit2 #5): automatic resume of an interrupted outgoing send (stall watchdog, group
     * reconnect, retry_pending_outgoing_group_files after a crash). It clears only the session-level
     * state, never the user's cancel — an automatic retry used to run the very same body as the user's
     * "retry" button and so silently un-cancelled a cancelled file.
     */
    static void resume_group_file_send(final GroupMessage message)
    {
        if (message == null)
        {
            return;
        }
        final String groupId = message.group_identifier;
        final String msgIdHash = message.msg_id_hash;
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final Boolean cached = ngc_user_cancel_from_cache(key);
        if (cached == null)
        {
            // KHANDAQ (audit3 #3): callers reach this from the main thread (the auto-retry post and
            // the retry button), with one exception — the re-entry below, which already runs on the
            // lookup thread and takes the cached branch. So on a cache miss the read must not be done
            // here on the main thread
            // (schedule_auto_retry_group_file posts here, onRetry is a tap), and the authoritative
            // tombstone check does SQL under a lock a chunk/tox thread can be holding. On a cold cache
            // do that read on the lookup thread and come back to the main thread for the send itself,
            // so the thread the transfer starts on does not change. The tombstone keeps full authority:
            // a cancelled — or unreadable, which we treat as cancelled — file simply never comes back.
            try
            {
                ngc_cancel_lookup_exec.execute(() ->
                {
                    if (is_ngc_send_cancelled_by_user(groupId, msgIdHash))
                    {
                        return;
                    }
                    if (main_handler_s != null)
                    {
                        main_handler_s.post(() -> resume_group_file_send(message));
                    }
                    else
                    {
                        // No looper (service-only context): the answer is memoised now, so re-entering
                        // here takes the synchronous path below and cannot recurse again.
                        resume_group_file_send(message);
                    }
                });
            }
            catch (Exception e)
            {
                Log.i(TAG, "resume_group_file_send:EE:" + e.getMessage());
            }
            return;
        }
        if (cached)
        {
            return;
        }
        ngc_cancelled_sends.remove(key);
        clear_ngc_outgoing_send_complete(groupId, msgIdHash);
        clear_ngc_file_transfer_failed(groupId, msgIdHash);
        send_group_file(message);
    }

    static void retry_group_file_receive(final GroupMessage message)
    {
        retry_group_incoming_file_download(message);
    }

    static boolean is_ngc_file_transfer_failed(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        final Boolean failed = ngc_file_transfer_failed.get(ngc_file_progress_key(groupId, msgIdHash));
        return failed != null && failed;
    }

    static void mark_ngc_file_transfer_failed(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_file_transfer_failed.put(ngc_file_progress_key(groupId, msgIdHash), true);
        clear_ngc_file_transfer_progress(groupId, msgIdHash);
        post_group_file_progress_refresh(groupId, msgIdHash);
    }

    static void clear_ngc_file_transfer_failed(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_file_transfer_failed.remove(ngc_file_progress_key(groupId, msgIdHash));
    }

    static void arm_ngc_incoming_file_timeout(final String groupId, final String msgIdHash)
    {
        if (main_handler_s == null || groupId == null || msgIdHash == null)
        {
            return;
        }

        disarm_ngc_incoming_file_timeout(groupId, msgIdHash);

        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final Runnable timeoutTask = () ->
        {
            ngc_incoming_file_timeout_tasks.remove(key);
            if (NgcGroupFileTransfer.isReceiveComplete(groupId, msgIdHash))
            {
                return;
            }
            // Snapshot partial progress to disk so it survives an app restart (resume, not re-download).
            NgcGroupFileTransfer.persistIncomingProgress(groupId, msgIdHash);

            // Resumable chunked transfer + sender still reachable → keep recovering via selective NACK
            // instead of dead-ending at "Download failed" (which also blocks the auto-retry path).
            final GroupMessage m = find_group_message_by_msg_id_hash(groupId, msgIdHash);
            if (m != null && m.direction != 1
                    && NgcGroupFileTransfer.shouldUseChunkedTransfer(m.filesize)
                    && is_group_message_sender_online(m))
            {
                clear_ngc_file_transfer_failed(groupId, msgIdHash);
                // Only re-ask when the transfer has actually stalled (no new chunk for a few seconds),
                // so an in-progress download isn't flooded with redundant requests. Re-arm fast so a
                // dropped chunk recovers in seconds (parity with iOS) instead of one NACK per 30s.
                if (ngc_progress_idle_for(groupId, msgIdHash, NGC_NACK_STALL_MS))
                {
                    NgcGroupFileTransfer.sendFileResendRequest(m);
                    TrifaToxService.wakeup_tox_thread();
                }
                arm_ngc_incoming_file_timeout(groupId, msgIdHash);
                return;
            }

            // Sender offline / not a chunked transfer: keep polling cheaply and only give up (show the
            // manual "retry" affordance) once there's been no progress for the full stall window.
            if (is_ngc_transfer_stalled(groupId, msgIdHash))
            {
                mark_ngc_file_transfer_failed(groupId, msgIdHash);
            }
            else
            {
                arm_ngc_incoming_file_timeout(groupId, msgIdHash);
            }
        };

        ngc_incoming_file_timeout_tasks.put(key, timeoutTask);
        main_handler_s.postDelayed(timeoutTask, NGC_NACK_RETRY_MS);
    }

    // True when no chunk has advanced this transfer's progress for at least windowMs.
    private static boolean ngc_progress_idle_for(final String groupId, final String msgIdHash, final long windowMs)
    {
        if (groupId == null || msgIdHash == null)
        {
            return true;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final Long last = ngc_transfer_last_progress_ts.get(key);
        final Long started = ngc_transfer_start_ts.get(key);
        final long ref = last != null ? last : (started != null ? started : System.currentTimeMillis());
        return (System.currentTimeMillis() - ref) >= windowMs;
    }

    static void disarm_ngc_incoming_file_timeout(final String groupId, final String msgIdHash)
    {
        if (main_handler_s == null || groupId == null || msgIdHash == null)
        {
            return;
        }

        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final Runnable timeoutTask = ngc_incoming_file_timeout_tasks.remove(key);
        if (timeoutTask != null)
        {
            main_handler_s.removeCallbacks(timeoutTask);
        }
    }

    private static String ngc_file_progress_key(final String groupId, final String msgIdHash)
    {
        return groupId.toLowerCase(Locale.ROOT) + "|" + msgIdHash.toLowerCase(Locale.ROOT);
    }

    static int ngc_file_transfer_progress_percent(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return -1;
        }
        final Integer value = ngc_file_transfer_progress.get(ngc_file_progress_key(groupId, msgIdHash));
        return value != null ? value : -1;
    }

    private static void set_ngc_file_transfer_progress(final String groupId, final String msgIdHash, final int percent)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        touch_ngc_transfer_progress(groupId, msgIdHash);
        ngc_file_transfer_progress.put(ngc_file_progress_key(groupId, msgIdHash), percent);
        post_group_file_progress_refresh(groupId, msgIdHash);
    }

    static void record_ngc_transfer_started(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        final long now = System.currentTimeMillis();
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        ngc_transfer_start_ts.put(key, now);
        ngc_transfer_last_progress_ts.put(key, now);
        clear_ngc_outgoing_send_complete(groupId, msgIdHash);
        ngc_file_transfer_bytes_done.remove(key);
        clear_ngc_file_transfer_failed(groupId, msgIdHash);
    }

    // KHANDAQ (#120): mark that the user explicitly asked to download this incoming group file
    // (tap on the download/retry affordance), so the attachment-download policy is bypassed for it.
    static void mark_ngc_manual_download_requested(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_manual_download_requested.add(ngc_file_progress_key(groupId, msgIdHash));
    }

    static void clear_ngc_manual_download_requested(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_manual_download_requested.remove(ngc_file_progress_key(groupId, msgIdHash));
    }

    // KHANDAQ (#120): the single chokepoint deciding whether we may pull/save an incoming group file
    // right now. True when the global policy permits auto-download (Всегда, or Wi-Fi-only on Wi-Fi),
    // OR the user explicitly tapped download for this specific file.
    static boolean ngc_incoming_download_allowed(final String groupId, final String msgIdHash)
    {
        return HelperFiletransfer.attachment_auto_download_allowed()
                || is_ngc_manual_download_requested(groupId, msgIdHash);
    }

    static boolean is_ngc_manual_download_requested(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        return ngc_manual_download_requested.contains(ngc_file_progress_key(groupId, msgIdHash));
    }

    static void touch_ngc_transfer_progress(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        ngc_transfer_last_progress_ts.put(ngc_file_progress_key(groupId, msgIdHash), System.currentTimeMillis());
    }

    static boolean is_ngc_transfer_stalled(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        if (is_ngc_file_transfer_failed(groupId, msgIdHash))
        {
            return true;
        }

        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final Long started = ngc_transfer_start_ts.get(key);
        if (started == null)
        {
            return false;
        }

        final Long last = ngc_transfer_last_progress_ts.get(key);
        final long ref = last != null ? last : started;
        return (System.currentTimeMillis() - ref) >= NGC_TRANSFER_STALL_MS;
    }

    static void kickstart_incoming_chunked_file_transfer(final GroupMessage message)
    {
        if (message == null || message.group_identifier == null || message.msg_id_hash == null)
        {
            return;
        }
        if (message.direction == 1)
        {
            return;
        }
        if (!NgcGroupFileTransfer.shouldUseChunkedTransfer(message.filesize))
        {
            return;
        }
        if (HelperFiletransfer.isGroupMessageMediaReady(message, null))
        {
            return;
        }
        if (is_ngc_file_transfer_failed(message.group_identifier, message.msg_id_hash))
        {
            return;
        }
        // KHANDAQ (#120): never auto-pull when the attachment-download policy forbids it; a manual tap
        // sets the bypass marker first, so explicit downloads still proceed.
        if (!ngc_incoming_download_allowed(message.group_identifier, message.msg_id_hash))
        {
            return;
        }

        record_ngc_transfer_started(message.group_identifier, message.msg_id_hash);
        NgcGroupFileTransfer.prepareRetryReceive(message);
        NgcGroupFileTransfer.sendFileResendRequest(message);
        arm_ngc_incoming_file_timeout(message.group_identifier, message.msg_id_hash);
    }

    static boolean is_incoming_chunked_file_stalled(final GroupMessage message)
    {
        if (message == null || message.group_identifier == null || message.msg_id_hash == null)
        {
            return false;
        }
        if (message.direction == 1 || !NgcGroupFileTransfer.shouldUseChunkedTransfer(message.filesize))
        {
            return false;
        }
        if (HelperFiletransfer.isGroupMessageMediaReady(message, null))
        {
            return false;
        }
        if (is_ngc_file_transfer_failed(message.group_identifier, message.msg_id_hash)
                || is_ngc_transfer_stalled(message.group_identifier, message.msg_id_hash))
        {
            return true;
        }

        final int pct = ngc_file_transfer_progress_percent(message.group_identifier, message.msg_id_hash);
        if (pct >= 0)
        {
            return false;
        }

        final long ageMs = System.currentTimeMillis() - Math.max(message.rcvd_timestamp, message.sent_timestamp);
        return ageMs >= NGC_TRANSFER_STALL_MS;
    }

    static void arm_ngc_outgoing_file_timeout(final String groupId, final String msgIdHash)
    {
        if (main_handler_s == null || groupId == null || msgIdHash == null)
        {
            return;
        }

        disarm_ngc_outgoing_file_timeout(groupId, msgIdHash);

        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final Runnable timeoutTask = () ->
        {
            ngc_outgoing_file_timeout_tasks.remove(key);
            if (NgcGroupFileTransfer.isReceiveComplete(groupId, msgIdHash))
            {
                return;
            }
            final int pct = ngc_file_transfer_progress_percent(groupId, msgIdHash);
            if (pct >= 100)
            {
                return;
            }
            mark_ngc_file_transfer_failed(groupId, msgIdHash);
        };

        ngc_outgoing_file_timeout_tasks.put(key, timeoutTask);
        main_handler_s.postDelayed(timeoutTask, NGC_TRANSFER_STALL_MS);
    }

    static void disarm_ngc_outgoing_file_timeout(final String groupId, final String msgIdHash)
    {
        if (main_handler_s == null || groupId == null || msgIdHash == null)
        {
            return;
        }

        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final Runnable timeoutTask = ngc_outgoing_file_timeout_tasks.remove(key);
        if (timeoutTask != null)
        {
            main_handler_s.removeCallbacks(timeoutTask);
        }
    }

    static void clear_ngc_file_transfer_progress(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        ngc_file_transfer_progress.remove(key);
        ngc_file_transfer_bytes_done.remove(key);
    }

    private static final ConcurrentHashMap<String, Long> ngc_progress_refresh_ts = new ConcurrentHashMap<>();
    private static final long PROGRESS_REFRESH_MIN_INTERVAL_MS = 300L;

    private static void post_group_file_progress_refresh(final String groupId, final String msgIdHash)
    {
        if (main_handler_s == null)
        {
            return;
        }
        // Throttle per-chunk UI refreshes — rebinding the row on every chunk reloads the thumbnail and flickers.
        final String refreshKey = ngc_file_progress_key(groupId, msgIdHash);
        final long nowRefresh = System.currentTimeMillis();
        final Long lastRefresh = ngc_progress_refresh_ts.get(refreshKey);
        if (lastRefresh != null && (nowRefresh - lastRefresh) < PROGRESS_REFRESH_MIN_INTERVAL_MS)
        {
            return;
        }
        ngc_progress_refresh_ts.put(refreshKey, nowRefresh);
        main_handler_s.post(() ->
        {
            try
            {
                if (MainActivity.group_message_list_fragment == null)
                {
                    return;
                }
                if (!groupId.equalsIgnoreCase(MainActivity.group_message_list_fragment.current_group_id))
                {
                    return;
                }
                MainActivity.group_message_list_fragment.refresh_file_progress_by_hash(msgIdHash);
            }
            catch (Exception ignored)
            {
            }
        });
    }

    static void notify_group_file_send_progress(final String groupId, final String msgIdHash,
                                                final int chunksDone, final int chunksTotal)
    {
        if (groupId == null || msgIdHash == null || chunksTotal <= 0)
        {
            return;
        }
        final GroupMessage message = find_group_message_by_msg_id_hash(groupId, msgIdHash);
        final long bytesTotal = (message != null && message.filesize > 0L)
                ? message.filesize
                : (long) chunksTotal * NgcGroupFileTransfer.CHUNK_PAYLOAD_MAX;
        final long bytesDone = Math.min(bytesTotal,
                (long) chunksDone * NgcGroupFileTransfer.CHUNK_PAYLOAD_MAX);
        notify_group_file_send_progress_bytes(groupId, msgIdHash, bytesDone, bytesTotal);
    }

    static void notify_group_file_recv_progress(final String groupId, final String msgIdHash,
                                                final int chunksDone, final int chunksTotal)
    {
        if (groupId == null || msgIdHash == null || chunksTotal <= 0)
        {
            return;
        }
        final GroupMessage message = find_group_message_by_msg_id_hash(groupId, msgIdHash);
        final long bytesTotal = (message != null && message.filesize > 0L)
                ? message.filesize
                : (long) chunksTotal * NgcGroupFileTransfer.CHUNK_PAYLOAD_MAX;
        final long bytesDone = Math.min(bytesTotal,
                (long) chunksDone * NgcGroupFileTransfer.CHUNK_PAYLOAD_MAX);
        notify_group_file_recv_progress_bytes(groupId, msgIdHash, bytesDone, bytesTotal);
    }

    static void notify_group_file_send_progress_bytes(final String groupId, final String msgIdHash,
                                                      final long bytesDone, final long bytesTotal)
    {
        if (groupId == null || msgIdHash == null || bytesTotal <= 0L)
        {
            return;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final long clampedDone = Math.max(0L, Math.min(bytesDone, bytesTotal));
        ngc_file_transfer_bytes_done.put(key, clampedDone);
        final int percent = (int) Math.min(100L, (100L * clampedDone) / bytesTotal);
        set_ngc_file_transfer_progress(groupId, msgIdHash, percent);
        touch_ngc_transfer_progress(groupId, msgIdHash);
        if (percent >= 100)
        {
            mark_ngc_outgoing_send_complete(groupId, msgIdHash);
            clear_ngc_file_transfer_progress(groupId, msgIdHash);
        }
    }

    static void notify_group_file_recv_progress_bytes(final String groupId, final String msgIdHash,
                                                      final long bytesDone, final long bytesTotal)
    {
        if (groupId == null || msgIdHash == null || bytesTotal <= 0L)
        {
            return;
        }
        final String key = ngc_file_progress_key(groupId, msgIdHash);
        final long clampedDone = Math.max(0L, Math.min(bytesDone, bytesTotal));
        ngc_file_transfer_bytes_done.put(key, clampedDone);
        final int percent = (int) Math.min(100L, (100L * clampedDone) / bytesTotal);
        set_ngc_file_transfer_progress(groupId, msgIdHash, percent);
        touch_ngc_transfer_progress(groupId, msgIdHash);
        if (percent >= 100)
        {
            clear_ngc_file_transfer_progress(groupId, msgIdHash);
        }
        post_group_file_progress_refresh(groupId, msgIdHash);
    }

    static boolean group_incoming_file_exists(final String groupId, final String msgIdHash)
    {
        return find_group_message_by_msg_id_hash(groupId, msgIdHash) != null;
    }

    static GroupMessage find_group_message_by_msg_id_hash(final String groupId, final String msgIdHash)
    {
        if ((groupId == null) || (msgIdHash == null) || msgIdHash.isEmpty())
        {
            return null;
        }

        try
        {
            final List<GroupMessage> list = orma.selectFromGroupMessage().
                    group_identifierEq(groupId.toLowerCase(Locale.ROOT)).
                    toList();
            if (list == null)
            {
                return null;
            }

            for (GroupMessage gm : list)
            {
                if ((gm.msg_id_hash != null) && msgIdHash.equalsIgnoreCase(gm.msg_id_hash))
                {
                    return gm;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return null;
    }

    private static boolean should_notify_incoming_group_file(final String groupId, final String peerPubkey,
                                                             final GroupDB groupDb)
    {
        if ((group_message_list_activity != null)
            && group_message_list_activity.get_current_group_id().equalsIgnoreCase(groupId))
        {
            return false;
        }

        try
        {
            if ((groupDb != null) && groupDb.notification_silent)
            {
                return false;
            }
            if (group_notification_silent_peer_get(groupId, peerPubkey))
            {
                return false;
            }
        }
        catch (Exception ignored)
        {
            return false;
        }

        return true;
    }

    static void update_incoming_group_file_metadata(final GroupMessage existing,
                                                  final NgcGroupFileTransfer.IncomingAssembly asm)
    {
        if (existing == null || asm == null)
        {
            return;
        }

        final String displayName = (asm.displayFilename != null && !asm.displayFilename.isEmpty())
                ? asm.displayFilename : asm.filename;
        existing.file_name = displayName;
        existing.text = HelperFiletransfer.buildOutgoingFileMessageText(context_s, displayName, asm.totalSize);
        existing.filesize = asm.totalSize;
        if (asm.outPath != null)
        {
            existing.filename_fullpath = asm.outPath;
        }

        try
        {
            orma.updateGroupMessage().idEq(existing.id).
                    file_name(displayName).
                    text(existing.text).
                    filesize(existing.filesize).
                    filename_fullpath(existing.filename_fullpath).
                    execute();
        }
        catch (Exception ignored)
        {
        }

        post_group_file_progress_refresh(existing.group_identifier, existing.msg_id_hash);
    }

    static void handle_incoming_group_file_begin(final long groupNumber, final long peerId,
                                                 final String groupId, final byte[] msgId,
                                                 final NgcGroupFileTransfer.IncomingAssembly asm)
    {
        final String msgIdHex = bytebuffer_to_hexstring(ByteBuffer.wrap(msgId), true);
        set_ngc_file_transfer_progress(groupId, msgIdHex, 0);
        clear_ngc_file_transfer_failed(groupId, msgIdHex);

        if (group_incoming_file_exists(groupId, msgIdHex))
        {
            return;
        }

        final String displayName = (asm.displayFilename != null && !asm.displayFilename.isEmpty())
                ? asm.displayFilename : asm.filename;

        try
        {
            if (tox_group_self_get_peer_id(groupNumber) == peerId)
            {
                return;
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            // KHANDAQ: prefer the sender pubkey captured at transfer start. peerId is volatile and may
            // have been reassigned to another peer (or self) by completion time → wrong attribution.
            final String peer_pubkey = (asm.senderPubkey != null && !asm.senderPubkey.isEmpty())
                    ? asm.senderPubkey
                    : tox_group_peer_get_public_key__wrapper(groupNumber, peerId);
            final GroupMessage m = new GroupMessage();
            m.is_new = true;
            m.tox_group_peer_pubkey = peer_pubkey;
            m.direction = 0;
            m.TOX_MESSAGE_TYPE = 0;
            m.read = false;
            m.private_message = 0;
            m.group_identifier = groupId.toLowerCase(Locale.ROOT);
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.rcvd_timestamp = System.currentTimeMillis();
            m.sent_timestamp = m.rcvd_timestamp;
            m.text = HelperFiletransfer.buildOutgoingFileMessageText(context_s, displayName, asm.totalSize);
            m.was_synced = false;
            m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
            m.path_name = VFS_PREFIX + VFS_FILE_DIR + "/" + m.group_identifier + "/";
            m.file_name = displayName;
            m.filename_fullpath = asm.outPath;
            m.storage_frame_work = false;
            m.filesize = asm.totalSize;
            m.msg_id_hash = msgIdHex;
            m.message_id_tox = "";
            try
            {
                ensure_group_peer_recorded(groupId, groupNumber, peer_pubkey, null);
                m.tox_group_peername = tox_group_peer_get_name__wrapper(m.group_identifier, peer_pubkey, peerId);
            }
            catch (Exception ignored)
            {
            }

            final boolean viewing = (group_message_list_activity != null)
                    && group_message_list_activity.get_current_group_id().equalsIgnoreCase(groupId);
            insert_into_group_message_db(m, viewing);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "handle_incoming_group_file_begin:EE:" + e.getMessage());
        }
    }

    static void handle_incoming_group_file_complete(final long groupNumber, final long peerId,
                                                    final NgcGroupFileTransfer.IncomingAssembly asm)
    {
        try
        {
            final String group_id = HelperGroup.tox_group_by_groupnum__wrapper(groupNumber);
            if (group_id == null)
            {
                return;
            }

            final String msgIdHex = bytebuffer_to_hexstring(ByteBuffer.wrap(asm.msgId), true);
            clear_ngc_file_transfer_progress(asm.groupId, msgIdHex);
            disarm_ngc_incoming_file_timeout(asm.groupId, msgIdHex);
            clear_ngc_file_transfer_failed(asm.groupId, msgIdHex);
            clear_ngc_manual_download_requested(asm.groupId, msgIdHex); // KHANDAQ #120: one-shot bypass consumed

            final String displayName = (asm.displayFilename != null && !asm.displayFilename.isEmpty())
                    ? asm.displayFilename : asm.filename;

            final GroupMessage existing = find_group_message_by_msg_id_hash(asm.groupId, msgIdHex);
            if (existing != null)
            {
                existing.file_name = displayName;
                existing.text = HelperFiletransfer.buildOutgoingFileMessageText(context_s, displayName, asm.totalSize);
                existing.filesize = asm.totalSize;
                if (asm.outPath != null && !asm.outPath.isEmpty())
                {
                    existing.filename_fullpath = asm.outPath;
                }
                try
                {
                    ensure_group_peer_recorded(group_id, groupNumber, existing.tox_group_peer_pubkey, null);
                    final String livePeerName = tox_group_peer_get_name__wrapper(existing.group_identifier,
                            existing.tox_group_peer_pubkey);
                    final String shortPeerId = HelperFriend.peer_pubkey_short_id(existing.tox_group_peer_pubkey);
                    if (!TextUtils.isEmpty(livePeerName) && !shortPeerId.equalsIgnoreCase(livePeerName))
                    {
                        existing.tox_group_peername = livePeerName;
                    }
                }
                catch (Exception ignored)
                {
                }
                try
                {
                    orma.updateGroupMessage().idEq(existing.id).
                            file_name(displayName).
                            text(existing.text).
                            filesize(existing.filesize).
                            filename_fullpath(existing.filename_fullpath).
                            tox_group_peername(existing.tox_group_peername).
                            execute();
                }
                catch (Exception ignored)
                {
                }

                if ((group_message_list_activity != null)
                    && group_message_list_activity.get_current_group_id().equalsIgnoreCase(existing.group_identifier)
                    && (MainActivity.group_message_list_fragment != null)
                    && (MainActivity.group_message_list_fragment.adapter != null)
                    && main_handler_s != null)
                {
                    // KHANDAQ (audit): this runs on the tox iterate/callback thread — the RecyclerView
                    // adapter must only be touched on the UI thread. Post it (re-check on arrival).
                    final String hex = msgIdHex;
                    main_handler_s.post(() -> {
                        try
                        {
                            if (MainActivity.group_message_list_fragment != null
                                && MainActivity.group_message_list_fragment.adapter != null)
                            {
                                MainActivity.group_message_list_fragment.adapter.remove_placeholder_by_msg_id_hash(hex);
                                MainActivity.group_message_list_fragment.refresh_file_progress_by_hash(hex);
                            }
                        }
                        catch (Exception ignored)
                        {
                        }
                    });
                }

                GroupDB groupDb = null;
                try
                {
                    groupDb = (GroupDB) orma.selectFromGroupDB().
                            group_identifierEq(group_id.toLowerCase()).
                            toList().get(0);
                }
                catch (Exception ignored)
                {
                }

                final String peer_pubkey = existing.tox_group_peer_pubkey;
                if (should_notify_incoming_group_file(group_id, peer_pubkey, groupDb))
                {
                    String groupname = null;
                    try
                    {
                        if (groupDb != null)
                        {
                            groupname = groupDb.name;
                        }
                    }
                    catch (Exception ignored)
                    {
                    }
                    change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, existing.group_identifier,
                                            groupname, existing.text);
                }

                HelperFriend.refresh_chat_list_group_row_wrapper(group_id);
                HelperFriend.add_all_friends_clear_wrapper(0);
                return;
            }

            // KHANDAQ: prefer the sender pubkey captured at transfer start. peerId is volatile and may
            // have been reassigned to another peer (or self) by completion time → wrong attribution.
            final String peer_pubkey = (asm.senderPubkey != null && !asm.senderPubkey.isEmpty())
                    ? asm.senderPubkey
                    : tox_group_peer_get_public_key__wrapper(groupNumber, peerId);
            final GroupMessage m = new GroupMessage();
            m.is_new = true;
            m.tox_group_peer_pubkey = peer_pubkey;
            m.direction = 0;
            m.TOX_MESSAGE_TYPE = 0;
            m.read = false;
            m.private_message = 0;
            m.group_identifier = group_id.toLowerCase(Locale.ROOT);
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.rcvd_timestamp = System.currentTimeMillis();
            m.sent_timestamp = m.rcvd_timestamp;
            m.text = HelperFiletransfer.buildOutgoingFileMessageText(context_s, displayName, asm.totalSize);
            m.was_synced = false;
            m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
            m.path_name = VFS_PREFIX + VFS_FILE_DIR + "/" + m.group_identifier + "/";
            m.file_name = displayName;
            m.filename_fullpath = asm.outPath;
            m.storage_frame_work = false;
            m.filesize = asm.totalSize;
            m.msg_id_hash = msgIdHex;
            m.message_id_tox = "";

            if (group_message_list_activity != null
                    && group_message_list_activity.get_current_group_id().equalsIgnoreCase(m.group_identifier)
                    && MainActivity.group_message_list_fragment != null
                    && MainActivity.group_message_list_fragment.adapter != null
                    && main_handler_s != null)
            {
                // KHANDAQ (audit): off the tox thread — hop the adapter mutation to the UI thread.
                final String hex2 = m.msg_id_hash;
                main_handler_s.post(() -> {
                    try
                    {
                        if (MainActivity.group_message_list_fragment != null
                            && MainActivity.group_message_list_fragment.adapter != null)
                        {
                            MainActivity.group_message_list_fragment.adapter.remove_placeholder_by_msg_id_hash(hex2);
                        }
                    }
                    catch (Exception ignored)
                    {
                    }
                });
            }

            try
            {
                ensure_group_peer_recorded(group_id, groupNumber, peer_pubkey, null);
                m.tox_group_peername = tox_group_peer_get_name__wrapper(m.group_identifier, peer_pubkey);
            }
            catch (Exception ignored)
            {
            }

            GroupDB groupDb = null;
            try
            {
                groupDb = (GroupDB) orma.selectFromGroupDB().
                        group_identifierEq(group_id.toLowerCase()).
                        toList().get(0);
            }
            catch (Exception ignored)
            {
            }

            final boolean viewing = (group_message_list_activity != null)
                    && group_message_list_activity.get_current_group_id().equalsIgnoreCase(m.group_identifier);

            if (viewing)
            {
                insert_into_group_message_db(m, true);
            }
            else
            {
                insert_into_group_message_db(m, false);
            }

            if (should_notify_incoming_group_file(group_id, peer_pubkey, groupDb))
            {
                String groupname = null;
                try
                {
                    if (groupDb != null)
                    {
                        groupname = groupDb.name;
                    }
                }
                catch (Exception ignored)
                {
                }
                change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.group_identifier, groupname, m.text);
            }

            HelperFriend.refresh_chat_list_group_row_wrapper(group_id);
            HelperFriend.add_all_friends_clear_wrapper(0);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "handle_incoming_group_file_complete:EE:" + e.getMessage());
        }
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
            HelperGeneric.logI(TAG, "trim_video_to_duration:EE:" + e.getMessage());
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
            HelperGeneric.logI(TAG, "shrink_video_file_for_ngc:fsize_before=" + src.length());

            if (src.length() <= TOX_MAX_NGC_FILESIZE)
            {
                return true;
            }

            final long[] durations_ms = new long[]{5000, 3000, 2000, 1000, 700, 500, 300, 200, 100};
            final java.io.File tmp = new java.io.File(ofw.filepath_wrapped,
                    "ngc_vid_" + System.currentTimeMillis() + ".mp4");

            for (final long duration_ms : durations_ms)
            {
                // KHANDAQ (#F3): keep the AUDIO track when shrinking a group video to fit the NGC
                // size cap. It used to strip audio (include_audio=false) → recipients got a silent
                // clip ("can't hear the video joke"). Audio is cheap; the duration ladder below just
                // trims to a shorter length that still fits WITH sound.
                if (trim_video_to_duration(src, tmp, duration_ms, true))
                {
                    if ((tmp.length() > 0) && (tmp.length() <= TOX_MAX_NGC_FILESIZE))
                    {
                        io_file_copy(tmp, src);
                        tmp.delete();
                        HelperGeneric.logI(TAG, "shrink_video_file_for_ngc:fsize_after=" + src.length());
                        return true;
                    }
                }
                if (tmp.exists())
                {
                    tmp.delete();
                }
            }

            HelperGeneric.logI(TAG, "shrink_video_file_for_ngc:failed");
            return false;
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "shrink_video_file_for_ngc:EE:" + e.getMessage());
            return false;
        }
    }

    static void shrink_image_file(Context c, MessageListActivity.outgoing_file_wrapped ofw)
    {
        try
        {
            java.io.File ff1 = new java.io.File(ofw.filepath_wrapped + "/" + ofw.filename_wrapped);
            HelperGeneric.logI(TAG, "shrink_image_file:fsize_before=" + ff1.length());

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
                HelperGeneric.logI(TAG, "shrink_image_file:fsize_after=" +
                           new_len + " " + quality + " " + max_width + " " + ff2.getAbsolutePath());
                count++;
                if (count < qualityies.length)
                {
                    quality = qualityies[count];
                    HelperGeneric.logI(TAG, "shrink_image_file:A:count=" + count + " qualityies.length=" + qualityies.length + " quality=" + quality);
                }
                else
                {
                    HelperGeneric.logI(TAG, "shrink_image_file:B:count=" + count + " qualityies.length=" + qualityies.length + " quality=" + quality);
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
                        HelperGeneric.logI(TAG, "shrink_image_file:file copied:BREAK");
                    }
                    catch (Exception e)
                    {
                        HelperGeneric.logI(TAG, "shrink_image_file:file copy error:EE003:BREAK" + e.getMessage());
                    }
                    try
                    {
                        ff2.delete();
                        HelperGeneric.logI(TAG, "shrink_image_file:temp file deleted:001:BREAK");
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
                        HelperGeneric.logI(TAG, "shrink_image_file:file copied");
                    }
                    catch (Exception e)
                    {
                        HelperGeneric.logI(TAG, "shrink_image_file:file copy error:EE003" + e.getMessage());
                    }
                    try
                    {
                        ff2.delete();
                        HelperGeneric.logI(TAG, "shrink_image_file:temp file deleted:001");
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
                        HelperGeneric.logI(TAG, "shrink_image_file:temp file deleted:002");
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }

            HelperGeneric.logI(TAG, "shrink_image_file:fsize_after:END=" + ff1.length() + " " + ff1.getAbsolutePath());

            if (ff1.length() > TOX_MAX_NGC_FILESIZE)
            {
                display_toast(context_s.getString(R.string.group_image_shrink_failed), true, 50);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "shrink_image_file:compressToFile:EE003:" + e.getMessage());
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

    private static int group_maintain_tick = 0;
    private static final ConcurrentHashMap<String, Long> group_last_peer_sync_ms = new ConcurrentHashMap<>();
    private static final long GROUP_PEER_SYNC_INTERVAL_MS = 30_000L;
    private static final long GROUP_KICKSTART_MIN_INTERVAL_MS = 20_000L;
    private static final ConcurrentHashMap<String, Long> group_last_kickstart_ms = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> group_last_reconnect_ms = new ConcurrentHashMap<>();
    // KHANDAQ #25: when the user opens a chat we force one reconnect bypassing the backoff,
    // rate-limited by this so rapid open/close cannot hammer the DHT announce.
    private static final long GROUP_USER_RECONNECT_MIN_INTERVAL_MS = 5_000L;
    private static final ConcurrentHashMap<String, Long> group_last_user_reconnect_ms = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> group_last_connecting_resync_ms = new ConcurrentHashMap<>();
    // groups stuck alone: toxcore onion announce search backs off exponentially (up to 1h),
    // tox_group_reconnect resets it to fast 3s lookups — escalate when alone too long
    private static final ConcurrentHashMap<String, Long> group_alone_since_ms = new ConcurrentHashMap<>();
    // separate rate-limit map: nudges update group_last_kickstart_ms every 60s, which would
    // otherwise block the 180s escalation gate from ever firing
    private static final ConcurrentHashMap<String, Long> group_last_escalate_ms = new ConcurrentHashMap<>();
    private static final long GROUP_ALONE_ESCALATE_MS = 180_000L;
    private static final ConcurrentHashMap<Long, Boolean> group_message_resync_running = new ConcurrentHashMap<>();
    /** Last seen tox group message id per group_num (for gap detection). */
    private static final ConcurrentHashMap<Long, Long> last_group_tox_message_id = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> group_join_fail_retry_running = new ConcurrentHashMap<>();
    private static final long[] GROUP_MESSAGE_RESYNC_DELAYS_MS = {500L, 1_500L, 4_000L, 10_000L, 30_000L};
    private static final long GROUP_POST_JOIN_DISCOVERY_MS = 15_000L;
    private static final long GROUP_POST_JOIN_NUDGE_MS = 2_000L;
    private static final long GROUP_FRIEND_FALLBACK_AFTER_MS = 10_000L;
    // If a PUBLIC group stays CONNECTING with no reachable peers this long AND we have no online
    // friend to pull us in via the friend-assisted invite path, the local network is almost
    // certainly blocking UDP peer discovery (common on mobile carriers / restrictive regions, where
    // the DHT/onion announce-lookup that a chat-id join depends on cannot complete). NGC public-group
    // discovery legitimately takes 30-90s, so this threshold is deliberately well past that to avoid
    // false alarms. When it trips we surface a one-shot, actionable hint in the group chat.
    private static final long GROUP_UDP_BLOCKED_HINT_AFTER_MS = 120_000L;
    private static final java.util.Set<String> group_udp_hint_shown = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> group_udp_hint_suppress_logged = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> group_post_join_discovery_until_ms = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> group_post_join_discovery_running = new ConcurrentHashMap<>();
    private static final long GROUP_POST_JOIN_DISCOVERY_COOLDOWN_MS = 60_000L;
    private static final ConcurrentHashMap<String, Long> group_last_post_join_discovery_ms = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> group_last_mesh_log_ms = new ConcurrentHashMap<>();
    private static final long GROUP_MESH_LOG_INTERVAL_MS = 15_000L;
    /** First recovery attempt after broken peer transport detected. */
    private static final long GROUP_MESH_DEGRADED_ESCALATE_MS = 2_000L;
    /** Minimum gap between mesh recovery bursts. */
    private static final long GROUP_MESH_RECOVERY_COOLDOWN_MS = 5_000L;
    /** After this long degraded, force tox_group_reconnect to reset DHT announce backoff. */
    private static final long GROUP_MESH_KICKSTART_AFTER_MS = 12_000L;
    private static final long GROUP_MESH_KICKSTART_COOLDOWN_MS = 30_000L;
    /** Proactive keepalive to peers with conn=NONE (stimulates toxcore reconnect). */
    private static final long GROUP_MESH_KEEPALIVE_INTERVAL_MS = 8_000L;
    private static final ConcurrentHashMap<String, Long> group_mesh_degraded_since_ms = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> group_last_mesh_fanout = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> group_last_mesh_recovery_ms = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> group_last_mesh_kickstart_ms = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> group_last_mesh_keepalive_ms = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, GroupMeshSnapshot> group_mesh_snapshot_cache =
            new ConcurrentHashMap<>();

    static boolean should_run_group_maintenance(final String group_identifier, final ConcurrentHashMap<String, Long> map,
                                                final long min_interval_ms)
    {
        if (group_identifier == null)
        {
            return false;
        }
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        final long now = System.currentTimeMillis();
        final long last = map.getOrDefault(key, 0L);
        if (now - last < min_interval_ms)
        {
            return false;
        }
        map.put(key, now);
        return true;
    }

    static void schedule_group_auto_reconnect(final long group_num, @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started || group_identifier == null)
        {
            return;
        }
        if (is_group_we_left(group_identifier) || !is_group_active(group_identifier))
        {
            return;
        }
        if (global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            return;
        }
        final int conn = tox_group_is_connected(group_num);
        if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING.value)
        {
            TrifaToxService.wakeup_tox_thread();
            return;
        }
        final long backoff_ms = group_connect_reconnect_backoff_ms(group_identifier);
        if (!should_run_group_maintenance(group_identifier, group_last_reconnect_ms, backoff_ms))
        {
            return;
        }
        if (reconnect_group_if_disconnected(group_num, group_identifier))
        {
            TrifaToxService.wakeup_tox_thread();
        }
        sync_group_peers_from_tox_to_db(group_num);
        HelperGeneric.logI(TAG, "schedule_group_auto_reconnect:id=" + group_identifier_short(group_identifier, false)
                + " gn=" + group_num + " conn=" + tox_group_is_connected(group_num)
                + " peers=" + tox_group_peer_count(group_num));
    }

    /**
     * KHANDAQ #25: the user is actively looking at the chat and it is not connected — force one
     * reconnect now, bypassing the exponential backoff. Rate-limited (5s) so rapid open/close can't
     * hammer the DHT, and still ERROR-only inside reconnect_group_if_disconnected so we never abort
     * an in-progress CONNECTING announce.
     */
    static void force_group_reconnect_user_initiated(final long group_num, @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started || group_identifier == null)
        {
            return;
        }
        if (global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            return;
        }
        if (is_group_we_left(group_identifier) || !is_group_active(group_identifier))
        {
            return;
        }
        if (tox_group_is_connected(group_num) ==
            TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            return;
        }
        if (!should_run_group_maintenance(group_identifier, group_last_user_reconnect_ms,
                GROUP_USER_RECONNECT_MIN_INTERVAL_MS))
        {
            return;
        }
        // keep the periodic backoff clock in sync so it doesn't immediately re-fire on top of us
        group_last_reconnect_ms.put(group_identifier.toLowerCase(Locale.ENGLISH), System.currentTimeMillis());
        if (reconnect_group_if_disconnected(group_num, group_identifier))
        {
            TrifaToxService.wakeup_tox_thread();
            HelperGeneric.logI(TAG, "force_group_reconnect_user:id="
                    + group_identifier_short(group_identifier, false) + " gn=" + group_num
                    + " conn=" + tox_group_is_connected(group_num));
        }
    }

    /** After self_join — start friend-assisted discovery for groups still alone. */
    static void on_group_self_joined(final long group_num, @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        apply_group_self_display_name(group_num);
        TrifaToxService.wakeup_tox_thread();
        sync_group_peers_from_tox_to_db(group_num);
        update_group_in_friendlist(group_identifier);

        final int conn = tox_group_is_connected(group_num);
        final long peer_count = Math.max(0L, tox_group_peer_count(group_num));
        if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            clear_group_connect_progress(group_identifier);
            schedule_group_message_resync(group_num);
            flush_pending_group_messages(group_identifier);
            if (peer_count <= 1L)
            {
                send_group_invite_request_to_friends(group_identifier);
            }
            return;
        }

        begin_group_connect_attempt(group_identifier);
        if (peer_count <= 1L)
        {
            send_group_invite_request_to_friends(group_identifier);
        }
        if (MainActivity.tox_group_get_privacy_state(group_num) ==
            ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            schedule_post_join_peer_discovery(group_num, group_identifier);
            if (should_run_group_maintenance(group_identifier, group_last_kickstart_ms, 90_000L))
            {
                nudge_public_group_dht(group_num, group_identifier);
            }
        }
    }

    static void on_group_connected(final long group_num)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        final String group_identifier = tox_group_by_groupnum__wrapper(group_num);
        if (group_identifier == null || is_group_we_left(group_identifier) || !is_group_active(group_identifier))
        {
            return;
        }
        clear_group_connect_progress(group_identifier);
        // KHANDAQ (audit #2 finding 1, step 3): announce our history-signing key. Sent here because
        // a peer can only learn it from a live packet, and rate-limited inside announceToGroup, so
        // a flapping group cannot turn this into traffic. Every shipped client drops version 0x02,
        // so this is inert until signing clients are rolled out.
        announce_hsk_to_group(group_num, group_identifier, false);
        flush_pending_group_messages(group_identifier);
        retry_pending_outgoing_group_files(group_identifier);
        sync_group_peers_from_tox_to_db(group_num);
        update_group_in_friendlist(group_identifier);
        schedule_group_message_resync(group_num);

        final long peer_count = Math.max(0L, tox_group_peer_count(group_num));
        if (peer_count > 1L)
        {
            return;
        }
        send_group_invite_request_to_friends(group_identifier);
        if (MainActivity.tox_group_get_privacy_state(group_num) ==
            ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            if (should_run_group_maintenance(group_identifier, group_last_kickstart_ms, 60_000L))
            {
                nudge_public_group_dht(group_num, group_identifier);
            }
        }
    }

    /** @deprecated use {@link #on_group_connected} */
    static void on_public_group_connected(final long group_num)
    {
        on_group_connected(group_num);
    }

    static void schedule_group_message_resync(final long group_num)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        if (group_message_resync_running.putIfAbsent(group_num, Boolean.TRUE) != null)
        {
            return;
        }

        final Thread t = new Thread(() ->
        {
            try
            {
                for (long delay_ms : GROUP_MESSAGE_RESYNC_DELAYS_MS)
                {
                    Thread.sleep(delay_ms);
                    if (!is_tox_started || group_num < 0)
                    {
                        return;
                    }
                    HelperGeneric.logI(TAG, "schedule_group_message_resync:gn=" + group_num + " delay_ms=" + delay_ms
                            + " peers=" + tox_group_peer_count(group_num));
                    sync_group_message_history_to_all_peers(group_num);
                }
            }
            catch (InterruptedException ignored)
            {
            }
            finally
            {
                group_message_resync_running.remove(group_num);
            }
        }, "grp-msg-resync-" + group_num);
        t.setDaemon(true);
        t.start();
    }

    /** Detect missing tox group message ids and trigger history resync. */
    static void check_group_message_sequence_gap(final long group_number, final long tox_message_id)
    {
        if (group_number < 0 || tox_message_id <= 0)
        {
            return;
        }

        final Long last = last_group_tox_message_id.get(group_number);
        if (last != null && tox_message_id > last + 1)
        {
            HelperGeneric.logI(TAG, "group_msg_seq_gap:gn=" + group_number + " last=" + last + " got="
                    + tox_message_id + " missing=" + (tox_message_id - last - 1));
            schedule_group_message_resync(group_number);
        }

        last_group_tox_message_id.merge(group_number, tox_message_id, Math::max);
    }

    static void mark_group_peer_discovery_started(@NonNull final String group_identifier)
    {
        begin_group_connect_attempt(group_identifier);
        group_post_join_discovery_until_ms.put(group_identifier.toLowerCase(Locale.ENGLISH),
                System.currentTimeMillis() + GROUP_POST_JOIN_DISCOVERY_MS);
    }

    static boolean is_group_peer_discovery_in_progress(final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return false;
        }
        final Long until = group_post_join_discovery_until_ms.get(group_identifier.toLowerCase(Locale.ENGLISH));
        if (until == null || System.currentTimeMillis() > until)
        {
            group_post_join_discovery_until_ms.remove(group_identifier.toLowerCase(Locale.ENGLISH));
            return false;
        }
        try
        {
            final long group_num = tox_group_by_groupid__wrapper(group_identifier);
            if (group_num < 0)
            {
                return true;
            }
            final int conn = tox_group_is_connected(group_num);
            if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                group_post_join_discovery_until_ms.remove(group_identifier.toLowerCase(Locale.ENGLISH));
                return false;
            }
            if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING.value)
            {
                return true;
            }
            final long peers = Math.max(0L, tox_group_peer_count(group_num));
            if (peers <= 1L)
            {
                return true;
            }
        }
        catch (Exception ignored)
        {
            return true;
        }
        group_post_join_discovery_until_ms.remove(group_identifier.toLowerCase(Locale.ENGLISH));
        return false;
    }

    static void schedule_post_join_peer_discovery(final long group_num, @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started || is_group_we_left(group_identifier))
        {
            return;
        }
        if (MainActivity.tox_group_get_privacy_state(group_num) !=
            ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            return;
        }
        if (!should_run_group_maintenance(group_identifier, group_last_post_join_discovery_ms,
                GROUP_POST_JOIN_DISCOVERY_COOLDOWN_MS))
        {
            return;
        }

        mark_group_peer_discovery_started(group_identifier);
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        if (group_post_join_discovery_running.putIfAbsent(key, Boolean.TRUE) != null)
        {
            return;
        }

        final Thread t = new Thread(() ->
        {
            try
            {
                final long deadline = System.currentTimeMillis() + GROUP_POST_JOIN_DISCOVERY_MS;
                int round = 0;
                while (System.currentTimeMillis() < deadline && is_tox_started && !is_group_we_left(group_identifier))
                {
                    final long peers = Math.max(0L, tox_group_peer_count(group_num));
                    final int conn = tox_group_is_connected(group_num);
                    HelperGeneric.logI(TAG, "post_join_peer_discovery:gn=" + group_num + " id="
                            + group_identifier_short(group_identifier, false) + " round=" + round
                            + " peers=" + peers + " conn=" + conn + " attempt="
                            + get_group_connect_attempt(group_identifier));
                    if (peers > 1L && conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
                    {
                        clear_group_connect_progress(group_identifier);
                        sync_group_peers_from_tox_to_db(group_num);
                        update_group_in_friendlist(group_identifier);
                        update_group_in_groupmessagelist(group_identifier);
                        schedule_group_message_resync(group_num);
                        flush_pending_group_messages(group_identifier);
                        break;
                    }
                    if (group_connect_elapsed_ms(group_identifier) >= GROUP_FRIEND_FALLBACK_AFTER_MS)
                    {
                        send_group_invite_request_to_known_member_friends(group_identifier);
                        send_group_invite_request_to_friends(group_identifier);
                    }
                    nudge_public_group_dht(group_num, group_identifier);
                    TrifaToxService.wakeup_tox_thread();
                    sync_group_peers_from_tox_to_db(group_num);
                    update_group_in_friendlist(group_identifier);
                    update_group_in_groupmessagelist(group_identifier);
                    Thread.sleep(GROUP_POST_JOIN_NUDGE_MS);
                    round++;
                }
            }
            catch (InterruptedException ignored)
            {
            }
            finally
            {
                group_post_join_discovery_running.remove(key);
            }
        }, "grp-post-join-" + group_num);
        t.setDaemon(true);
        t.start();
    }

    private static final ConcurrentHashMap<String, Boolean> group_pending_flush_running = new ConcurrentHashMap<>();

    static void schedule_pending_group_message_flush(@NonNull final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return;
        }
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        if (group_pending_flush_running.putIfAbsent(key, Boolean.TRUE) != null)
        {
            return;
        }
        final Thread t = new Thread(() ->
        {
            try
            {
                flush_pending_group_messages(group_identifier);
            }
            finally
            {
                group_pending_flush_running.remove(key);
            }
        }, "grp-pending-flush-" + group_identifier_short(group_identifier, false));
        t.setDaemon(true);
        t.start();
    }

    static void flush_pending_group_messages(@NonNull final String group_identifier)
    {
        if (!is_tox_started || orma == null || TextUtils.isEmpty(group_identifier))
        {
            return;
        }
        final String id_lower = group_identifier.toLowerCase(Locale.ENGLISH);
        try
        {
            final java.util.List<GroupMessage> pending = orma.selectFromGroupMessage().
                    group_identifierEq(id_lower).
                    directionEq(1).
                    message_id_toxEq(PENDING_GROUP_MESSAGE_ID_TOX).
                    orderBySent_timestampAsc().
                    toList();
            if (pending.isEmpty())
            {
                return;
            }
            long group_num = tox_group_by_groupid__wrapper(group_identifier);
            for (final GroupMessage pending_msg : pending)
            {
                if (pending_msg.text == null || pending_msg.text.isEmpty())
                {
                    continue;
                }
                final long message_id = send_group_text_message_resilient(group_identifier, group_num, pending_msg.text);
                if (HelperFriend.is_group_text_send_success(message_id))
                {
                    orma.updateGroupMessage().idEq(pending_msg.id).
                            message_id_tox(fourbytes_of_long_to_hex(message_id)).
                            execute();
                    update_group_in_groupmessagelist(group_identifier);
                }
                else if (message_id == GROUP_SEND_QUEUE_WHEN_UNCONNECTED)
                {
                    break;
                }
                else
                {
                    Log.w(TAG, "flush_pending_group_messages:failed id=" + pending_msg.id + " res=" + message_id);
                    break;
                }
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "flush_pending_group_messages:EE:" + e.getMessage());
        }
    }

    /** Faster tox_iterate while groups are connecting or peer discovery is active. */
    static boolean needs_fast_group_iterate()
    {
        if (!is_tox_started)
        {
            return false;
        }
        if (global_showing_anygroupview)
        {
            return true;
        }
        try
        {
            if (!group_post_join_discovery_until_ms.isEmpty())
            {
                return true;
            }
            final long[] group_numbers = MainActivity.tox_group_get_grouplist();
            if (group_numbers == null)
            {
                return false;
            }
            for (long group_num : group_numbers)
            {
                if (group_num < 0)
                {
                    continue;
                }
                final String group_identifier = tox_group_by_groupnum__wrapper(group_num);
                if (group_identifier == null || is_group_we_left(group_identifier) || !is_group_active(group_identifier))
                {
                    continue;
                }
                if (MainActivity.tox_group_get_privacy_state(group_num) !=
                    ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
                {
                    if (Math.max(0L, tox_group_peer_count(group_num)) > 1L)
                    {
                        return true;
                    }
                    continue;
                }
                final int conn = tox_group_is_connected(group_num);
                if (conn != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
                {
                    return true;
                }
                if (Math.max(0L, tox_group_peer_count(group_num)) <= 1L)
                {
                    return true;
                }
                if (is_group_mesh_degraded(group_identifier))
                {
                    return true;
                }
                // Populated groups: iterate at 20ms to speed up mesh reconnect.
                return true;
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    static void on_group_chat_foreground(@NonNull final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier) || !is_tox_started)
        {
            return;
        }
        TrifaToxService.wakeup_tox_thread();
        long group_num = tox_group_by_groupid__wrapper(group_identifier);
        if (group_num < 0)
        {
            group_num = ensure_group_in_tox(group_identifier);
        }
        if (group_num < 0)
        {
            return;
        }
        schedule_group_auto_reconnect(group_num, group_identifier);
        // KHANDAQ #25: opening the chat is a strong user-intent signal — don't wait out the backoff,
        // force a disconnected group to re-announce immediately (rate-limited, CONNECTING-safe).
        force_group_reconnect_user_initiated(group_num, group_identifier);
        sync_group_peers_from_tox_to_db(group_num);
        final long peers = Math.max(0L, tox_group_peer_count(group_num));
        final int conn = tox_group_is_connected(group_num);
        final boolean connected = conn ==
                TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value;

        if (connected)
        {
            clear_group_connect_progress(group_identifier);
            flush_pending_group_messages(group_identifier);
        }
        else if (get_group_connect_attempt(group_identifier) == 0 && peers <= 1L)
        {
            begin_group_connect_attempt(group_identifier);
        }

        if (peers <= 1L && !connected)
        {
            if (group_connect_elapsed_ms(group_identifier) >= GROUP_FRIEND_FALLBACK_AFTER_MS)
            {
                send_group_invite_request_to_known_member_friends(group_identifier);
                send_group_invite_request_to_friends(group_identifier);
            }
            if (MainActivity.tox_group_get_privacy_state(group_num) ==
                ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
            {
                nudge_public_group_dht(group_num, group_identifier);
                schedule_post_join_peer_discovery(group_num, group_identifier);
            }
            else
            {
                send_group_invite_request_to_friends(group_identifier);
            }
        }
        else if (peers <= 1L)
        {
            send_group_invite_request_to_friends(group_identifier);
        }

        schedule_group_message_resync(group_num);
        if (connected && peers > 1L)
        {
            request_history_sync_from_transport_peers(group_num, group_identifier);
        }
        update_group_in_friendlist(group_identifier);
    }

    /**
     * If a public group stayed alone (peers<=1) longer than GROUP_ALONE_ESCALATE_MS, run a full
     * kickstart (tox_group_reconnect) in background: this resets toxcore's exponential onion
     * announce-search backoff and forces a fresh self-announce. Returns true if escalation fired.
     */
    private static boolean escalate_if_alone_too_long(final long group_num, @NonNull final String group_identifier)
    {
        final Long alone_since = group_alone_since_ms.get(group_identifier);
        final long escalate_after_ms = is_group_peer_discovery_in_progress(group_identifier)
                ? 10_000L : GROUP_ALONE_ESCALATE_MS;
        if (alone_since == null || (System.currentTimeMillis() - alone_since) < escalate_after_ms)
        {
            return false;
        }
        if (!should_run_group_maintenance(group_identifier, group_last_escalate_ms, GROUP_ALONE_ESCALATE_MS))
        {
            return false;
        }
        // restart the alone-timer so the next escalation is a full period away
        group_alone_since_ms.put(group_identifier, System.currentTimeMillis());
        // Log.i, not logI: logI compiles out unless BuildConfig.DEBUG, and this is the one line that
        // describes a group nobody can reach. #246 came from the field and could not be
        // characterised for days precisely because every group diagnostic is debug-only — the QA
        // header ("N участников · M онлайн") shows peer COUNTS, which say nothing about whether the
        // group is CONNECTED or CONNECTING, and that distinction is what decides whether the
        // stuck-group recovery may act. Rare by construction: this point is reached at most once per
        // escalation period per group. Names no user — group id (shortened), state, counts.
        Log.i(TAG, "escalate_if_alone_too_long:id=" + group_identifier_short(group_identifier, false)
                + " gn=" + group_num + " conn=" + tox_group_is_connected(group_num)
                + " online=" + tox_group_peer_count(group_num)
                + " offline=" + tox_group_offline_peer_count(group_num)
                + " -> kickstart (reset announce backoff)");
        send_group_invite_request_to_friends(group_identifier);
        final Thread t = new Thread(() -> kickstart_group_connection(group_num, group_identifier),
                "grp-kick-" + group_num);
        t.setDaemon(true);
        t.start();
        return true;
    }

    static void kickstart_group_connection(final long group_num, @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        if (global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            return;
        }

        perform_khandaq_bootstrap_burst();
        TrifaToxService.bootstrap_me(true);
        TrifaToxService.wakeup_tox_thread();
        try
        {
            Thread.sleep(2000L);
        }
        catch (InterruptedException ignored)
        {
        }

        clear_group_group_we_left(group_identifier);
        set_group_active(group_identifier);
        // tox_group_reconnect() drops ALL peers and restarts the group handshake from scratch.
        // NGC public-group DHT peer discovery takes 30-90s; resetting a group that is already
        // CONNECTED or still CONNECTING aborts that slow handshake, so it never settles and peers
        // never appear (this regressed public-group joins for everyone). Only hard-reset groups
        // that are genuinely in the ERROR state; for CONNECTED/CONNECTING groups just refresh the
        // DHT/bootstrap above and let toxcore finish connecting on its own (last-known-good behavior).
        final int conn_now = tox_group_is_connected(group_num);
        // KHANDAQ (#246): a stuck-group hard reset used to hang off this branch too. It was written
        // against a misdiagnosis — the one-way peer split turned out to be a stale header, with the
        // transport working the whole time (toxcore reported the peer present while the screen did
        // not), so reconnecting the group addressed a layer that was never broken. Removed rather
        // than left dormant: an unvalidated path that drops every peer and restarts the handshake is
        // not something to carry on a hunch.
        if (conn_now == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_ERROR.value)
        {
            final int res = tox_group_reconnect(group_num);
            HelperGeneric.logI(TAG, "kickstart_group_connection:reconnect id=" + group_identifier + " gn=" + group_num
                    + " res=" + res + " conn=" + tox_group_is_connected(group_num) + " peers=" + tox_group_peer_count(group_num));
            update_savedata_file_wrapper();
            TrifaToxService.wakeup_tox_thread();
        }
        else
        {
            HelperGeneric.logI(TAG, "kickstart_group_connection:skip-reconnect conn=" + conn_now + " id="
                    + group_identifier + " gn=" + group_num + " peers=" + tox_group_peer_count(group_num));
        }
        sync_group_peers_from_tox_to_db(group_num);
        update_group_in_friendlist(group_identifier);
    }

    static void kickstart_public_group_dht(final long group_num, @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        if (MainActivity.tox_group_get_privacy_state(group_num) !=
            ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            return;
        }
        // KHANDAQ: kickstart_group_connection does a BLOCKING bootstrap (getaddrinfo) + Thread.sleep(2000)
        // — this is reached from create_new_group on the UI thread (AddPublicGroupActivity result), so it
        // froze the app for >2s and ANR'd right after creating a public group. Run it off the UI thread,
        // mirroring the already-threaded escalate_if_alone_too_long path.
        final Thread t = new Thread(() -> kickstart_group_connection(group_num, group_identifier),
                "grp-kick-pub-" + group_num);
        t.setDaemon(true);
        t.start();
    }

    // ---------------- friend-assisted group join ----------------
    // DHT announce lookups are slow (30-90+ s, with exponential backoff), but a
    // friend who is already inside the target group can invite us instantly over
    // the existing friend connection. On join-by-id we broadcast a small
    // "invite me to this group" lossless packet to online friends; a member
    // replies with a normal NGC invite which we auto-accept.
    public static final int LOSSLESS_PKT_GROUP_INVITE_REQUEST = 184; // custom lossless range 160..191
    private static final int GROUP_INVITE_REQUEST_VERSION = 1;
    private static final long GROUP_INVITE_REQUEST_TTL_MS = 10 * 60 * 1000L;
    private static final long GROUP_INVITE_REQUEST_RESEND_MS = 30 * 1000L;
    private static final long GROUP_INVITE_REPLY_MIN_INTERVAL_MS = 60 * 1000L;
    // chat ids (hex, lowercase) we asked friends to invite us to -> request ts
    private static final ConcurrentHashMap<String, Long> pending_friend_assisted_joins = new ConcurrentHashMap<>();
    // "<friendnum>:<id>" -> last time we sent that friend the request
    private static final ConcurrentHashMap<String, Long> last_invite_request_ms = new ConcurrentHashMap<>();
    // per-group gate for the periodic invite-request resend in maintain_all_groups
    private static final ConcurrentHashMap<String, Long> group_last_invite_request_ms = new ConcurrentHashMap<>();
    // "<friendnum>:<groupnum>" -> last time we replied to that friend with an invite
    private static final ConcurrentHashMap<String, Long> last_invite_reply_ms = new ConcurrentHashMap<>();

    /** Ask all online friends to invite us into the given public group. */
    static void send_group_invite_request_to_friends(@NonNull final String group_identifier)
    {
        send_group_invite_request_to_friends(group_identifier, null);
    }

    /** Ask online friends who are known members of this group (GroupPeerDB) to invite us. */
    static void send_group_invite_request_to_known_member_friends(@NonNull final String group_identifier)
    {
        final java.util.HashSet<String> known_pubkeys = collect_known_group_member_pubkeys(group_identifier);
        if (known_pubkeys.isEmpty())
        {
            return;
        }
        send_group_invite_request_to_friends(group_identifier, known_pubkeys);
    }

    @NonNull
    private static java.util.HashSet<String> collect_known_group_member_pubkeys(@NonNull final String group_identifier)
    {
        final java.util.HashSet<String> known_pubkeys = new java.util.HashSet<>();
        if (orma == null)
        {
            return known_pubkeys;
        }
        try
        {
            final String id_lower = group_identifier.toLowerCase(Locale.ENGLISH);
            final java.util.List<GroupPeerDB> peers = orma.selectFromGroupPeerDB().
                    group_identifierEq(id_lower).toList();
            for (final GroupPeerDB peer : peers)
            {
                if (peer.tox_group_peer_pubkey != null && !peer.tox_group_peer_pubkey.isEmpty())
                {
                    known_pubkeys.add(peer.tox_group_peer_pubkey.toLowerCase(Locale.ENGLISH));
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return known_pubkeys;
    }

    private static void send_group_invite_request_to_friends(@NonNull final String group_identifier,
                                                             @Nullable final java.util.Set<String> pubkey_filter)
    {
        send_group_invite_request_to_friends(group_identifier, pubkey_filter, false);
    }

    /**
     * @param force when true, ignore the per-friend 30s resend rate-limit. Used right after a friend
     *              comes online: that is a strong, legitimate trigger to (re)send immediately, and we
     *              must not be blocked by a rate-limit set by an earlier attempt that raced the
     *              connection transition (and got sent=0 because the friend wasn't connected yet).
     */
    private static void send_group_invite_request_to_friends(@NonNull final String group_identifier,
                                                             @Nullable final java.util.Set<String> pubkey_filter,
                                                             final boolean force)
    {
        if (!is_tox_started || global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            return;
        }
        final String id_lower = group_identifier.toLowerCase(Locale.ENGLISH);
        if (id_lower.length() != TOX_GROUP_CHAT_ID_SIZE * 2)
        {
            return;
        }
        pending_friend_assisted_joins.put(id_lower, System.currentTimeMillis());

        final byte[] chat_id_bytes = HelperGeneric.hex_to_bytes(id_lower);
        final byte[] packet = new byte[2 + TOX_GROUP_CHAT_ID_SIZE];
        packet[0] = (byte) LOSSLESS_PKT_GROUP_INVITE_REQUEST;
        packet[1] = (byte) GROUP_INVITE_REQUEST_VERSION;
        System.arraycopy(chat_id_bytes, 0, packet, 2, TOX_GROUP_CHAT_ID_SIZE);

        final long[] friends = MainActivity.tox_self_get_friend_list();
        if (friends == null)
        {
            return;
        }
        final long now = System.currentTimeMillis();
        int sent = 0;
        for (final long friend_number : friends)
        {
            try
            {
                if (MainActivity.tox_friend_get_connection_status(friend_number) == TOX_CONNECTION_NONE.value)
                {
                    continue;
                }
                final String pubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
                if (pubkey != null && HelperRelay.is_any_relay(pubkey))
                {
                    continue;
                }
                if (pubkey_filter != null)
                {
                    if (pubkey == null || !pubkey_filter.contains(pubkey.toLowerCase(Locale.ENGLISH)))
                    {
                        continue;
                    }
                }
                final String rate_key = friend_number + ":" + id_lower;
                final Long last = last_invite_request_ms.get(rate_key);
                if (!force && last != null && (now - last) < GROUP_INVITE_REQUEST_RESEND_MS)
                {
                    continue;
                }
                last_invite_request_ms.put(rate_key, now);
                final int res = MainActivity.tox_friend_send_lossless_packet(friend_number, packet, packet.length);
                if (res == 0)
                {
                    sent++;
                }
            }
            catch (Exception ignored)
            {
            }
        }
        HelperGeneric.logI(TAG, "send_group_invite_request_to_friends:id=" + group_identifier_short(id_lower, false)
                + " sent=" + sent);
    }

    /** A friend asked us to invite them into a group (lossless packet 184). */
    static void handle_group_invite_request(final long friend_number, @NonNull final byte[] data, final int length)
    {
        if (!is_tox_started || length != (2 + TOX_GROUP_CHAT_ID_SIZE) ||
            data[1] != (byte) GROUP_INVITE_REQUEST_VERSION)
        {
            return;
        }
        final String chat_id_hex =
                bytes_to_hex(Arrays.copyOfRange(data, 2, 2 + TOX_GROUP_CHAT_ID_SIZE)).toLowerCase(Locale.ENGLISH);
        final long group_num = tox_group_by_groupid__wrapper(chat_id_hex);
        if (group_num < 0)
        {
            return; // not a member of that group — silently ignore
        }
        final long now = System.currentTimeMillis();
        final String rate_key = friend_number + ":" + group_num;
        final Long last = last_invite_reply_ms.get(rate_key);
        if (last != null && (now - last) < GROUP_INVITE_REPLY_MIN_INTERVAL_MS)
        {
            return;
        }
        last_invite_reply_ms.put(rate_key, now);
        final int res = MainActivity.tox_group_invite_friend(group_num, friend_number);
        HelperGeneric.logI(TAG, "handle_group_invite_request:fn=" + friend_number
                + " id=" + group_identifier_short(chat_id_hex, false) + " invite_res=" + res);
    }

    /** Forget a pending friend-assisted join (e.g. after the invite was accepted). */
    static void clear_pending_friend_assisted_join(@Nullable final String group_identifier)
    {
        if (group_identifier != null)
        {
            pending_friend_assisted_joins.remove(group_identifier.toLowerCase(Locale.ENGLISH));
        }
    }

    /** Friend just came online: re-send still-pending invite requests. */
    static void resend_pending_group_invite_requests()
    {
        resend_pending_group_invite_requests(false);
    }

    static void resend_pending_group_invite_requests(final boolean force)
    {
        if (pending_friend_assisted_joins.isEmpty())
        {
            return;
        }
        final long now = System.currentTimeMillis();
        for (final Map.Entry<String, Long> entry : pending_friend_assisted_joins.entrySet())
        {
            final String id_lower = entry.getKey();
            if ((now - entry.getValue()) > GROUP_INVITE_REQUEST_TTL_MS)
            {
                pending_friend_assisted_joins.remove(id_lower);
                continue;
            }
            final long group_num = tox_group_by_groupid__wrapper(id_lower);
            if (group_num >= 0 && tox_group_peer_count(group_num) > 1L &&
                tox_group_is_connected(group_num) ==
                TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                // already joined with peers — done
                pending_friend_assisted_joins.remove(id_lower);
                continue;
            }
            send_group_invite_request_to_friends(id_lower, null, force);
        }
    }

    private static final java.util.concurrent.ScheduledExecutorService group_invite_resend_exec =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "ngc-invite-resend");
                t.setDaemon(true);
                return t;
            });

    /**
     * A friend just came online. The friend-assisted join used to rely on a single resend at the
     * connection-status callback, but at that exact instant tox_friend_get_connection_status() can
     * still report NONE (the lossless channel isn't ready yet) -> the invite-request gets sent=0 and,
     * if the maintenance loop is slow/starved, the join can stall forever even though the friend is
     * connected. So we force-resend (bypassing the 30s rate-limit) immediately AND a couple of times
     * over the next few seconds, off the UI thread. No-op when there is no pending friend-assisted
     * join, so this is free in steady state.
     */
    static void schedule_friend_online_invite_resends()
    {
        if (pending_friend_assisted_joins.isEmpty())
        {
            return;
        }
        resend_pending_group_invite_requests(true); // immediate (caller is the tox callback thread)
        for (final long delay_ms : new long[]{3_000L, 10_000L})
        {
            try
            {
                group_invite_resend_exec.schedule(() -> {
                    try
                    {
                        resend_pending_group_invite_requests(true);
                        TrifaToxService.wakeup_tox_thread();
                    }
                    catch (Throwable t)
                    {
                        Log.w(TAG, "schedule_friend_online_invite_resends:EE:" + t.getMessage());
                    }
                }, delay_ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    // KHANDAQ: pending queue for the MANUAL "invite friend to group" action. tox_group_invite_friend
    // is a one-shot synchronous send that fails (INVITE_FAIL) whenever the friend is not FRIEND_ONLINE
    // at that exact instant. On UDP-restricted networks the friend connection flaps, so a single attempt
    // often races a momentary disconnect and the invite is silently lost — unlike text messages, which
    // are queued and retried on reconnect. We mirror that: queue (friend_pubkey -> set of group ids) and
    // re-fire on friend-online + a few timed retries until tox_group_invite_friend returns success.
    private static final ConcurrentHashMap<String, java.util.Set<String>> pending_manual_group_invites =
            new ConcurrentHashMap<>();

    private static void queue_manual_group_invite(final String group_identifier, final String friend_public_key)
    {
        final String fk = friend_public_key.toLowerCase(Locale.ENGLISH);
        pending_manual_group_invites
                .computeIfAbsent(fk, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>()))
                .add(group_identifier);
        HelperGeneric.logI(TAG, "queue_manual_group_invite:group=" + group_identifier + " friend=" + friend_public_key);
        // re-fire a few times in case the friend flaps back without a fresh connection-status callback
        for (final long delay_ms : new long[]{3_000L, 10_000L, 30_000L})
        {
            try
            {
                group_invite_resend_exec.schedule(() -> retry_pending_manual_group_invites(friend_public_key),
                        delay_ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    /** Re-attempt any queued manual group invites for this friend; called on friend-online + timers. */
    static void retry_pending_manual_group_invites(final String friend_public_key)
    {
        if (friend_public_key == null)
        {
            return;
        }
        final String fk = friend_public_key.toLowerCase(Locale.ENGLISH);
        final java.util.Set<String> groups = pending_manual_group_invites.get(fk);
        if (groups == null || groups.isEmpty())
        {
            return;
        }
        final long friend_num = tox_friend_by_public_key__wrapper(friend_public_key);
        if ((friend_num < 0) || (friend_num >= UINT32_MAX_JAVA))
        {
            return; // friend not in tox right now — keep queued, try again later
        }
        for (final String group_identifier : new java.util.ArrayList<>(groups))
        {
            final long group_num = ensure_group_in_tox(group_identifier);
            if (group_num < 0)
            {
                groups.remove(group_identifier); // group is gone — stop trying
                continue;
            }
            final int res = tox_group_invite_friend(group_num, friend_num);
            HelperGeneric.logI(TAG, "retry_pending_manual_group_invites:group=" + group_identifier +
                                    " friend=" + friend_public_key + " res=" + res);
            if (res == 1)
            {
                groups.remove(group_identifier);
                update_savedata_file_wrapper();
            }
        }
        if (groups.isEmpty())
        {
            pending_manual_group_invites.remove(fk);
        }
    }

    /**
     * A friend just came online — re-fire queued manual invites for them. We try immediately AND a
     * couple of times over the next seconds because at the exact connection-status instant the crypto
     * channel may not be ready yet (write_cryptpacket can still fail), so a single attempt can race the
     * transition. No-op when nothing is queued for this friend.
     */
    static void schedule_manual_invite_resends(final String friend_public_key)
    {
        if (friend_public_key == null)
        {
            return;
        }
        final java.util.Set<String> groups =
                pending_manual_group_invites.get(friend_public_key.toLowerCase(Locale.ENGLISH));
        if (groups == null || groups.isEmpty())
        {
            return;
        }
        retry_pending_manual_group_invites(friend_public_key); // immediate (caller is the tox callback thread)
        for (final long delay_ms : new long[]{3_000L, 10_000L})
        {
            try
            {
                group_invite_resend_exec.schedule(() -> {
                    try
                    {
                        retry_pending_manual_group_invites(friend_public_key);
                        TrifaToxService.wakeup_tox_thread();
                    }
                    catch (Throwable ignored)
                    {
                    }
                }, delay_ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    /** Bootstrap + wakeup without tox_group_reconnect (safe while waiting for DHT peers). */
    /** Private groups: reconnect on ERROR + friend-assisted join when alone (desktop parity). */
    /**
     * KHANDAQ (audit #2 finding 1, step 3): announces this profile's history-signing key into one
     * group. Safe to call often — announceToGroup keeps its own per-group interval, and without a
     * usable Tox identity or key it simply does nothing.
     */
    static void announce_hsk_to_group(final long group_num, final String group_identifier,
                                      final boolean force)
    {
        try
        {
            final String self_tox_pub = self_tox_pub_for_hsk(group_num, group_identifier);
            if (self_tox_pub == null)
            {
                return;
            }
            NgcHskAnnounce.announceToGroup(group_num, group_identifier, self_tox_pub, force);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "announce_hsk_to_group:EE:" + e.getMessage());
        }
    }

    /**
     * The profile identity the HSK is filed under. It is used ONLY to decide whose stored key this
     * is (§4.1 re-mints on identity change); what a signature binds to is our per-group key, which
     * NgcHskAnnounce reads from the transport.
     */
    private static String self_tox_pub_for_hsk(final long group_num, final String group_identifier)
    {
        if (group_num < 0 || group_identifier == null)
        {
            return null;
        }
        return NgcHskStore.toxPubFromToxId(MainActivity.get_my_toxid());
    }

    /** Groups with a verified-authorship refresh already queued, so a burst costs one rebind. */
    private static final java.util.Set<String> pending_verified_refresh =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * KHANDAQ (audit #2 finding 1, step 3): rebinds an open group chat after signatures have proved
     * some of its rows.
     *
     * A verdict lands a few milliseconds AFTER the unsigned copy inserted and drew the row (measured
     * on device: 14:12:13.676 insert, 14:12:13.680 verified), so without this the "sender not
     * verified" marker stays on screen until something else rebinds the list — the one case where
     * the marker is both wrong and visible.
     *
     * Coalesced rather than throttled: a history sync verifies many rows at once, and a throttle
     * that drops the LAST event in a burst would leave exactly the stale marker it is meant to
     * clear. One queued refresh absorbs the whole burst; a verdict arriving later queues another.
     */
    static void schedule_group_verified_refresh(final String group_identifier)
    {
        if (group_identifier == null || main_handler_s == null)
        {
            return;
        }
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        if (!pending_verified_refresh.add(key))
        {
            return;
        }
        main_handler_s.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                pending_verified_refresh.remove(key);
                update_group_in_groupmessagelist(key, true);
            }
        }, 900L);
    }

    /** Announces to a peer that just joined, at most once per peer. See NgcHskAnnounce. */
    static void announce_hsk_to_new_peer(final long group_num, final String group_identifier,
                                         final String peer_pubkey)
    {
        try
        {
            final String self_tox_pub = self_tox_pub_for_hsk(group_num, group_identifier);
            if (self_tox_pub == null)
            {
                return;
            }
            NgcHskAnnounce.announceForNewPeer(group_num, group_identifier, self_tox_pub, peer_pubkey);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "announce_hsk_to_new_peer:EE:" + e.getMessage());
        }
    }

    static void maintain_private_group(final long group_num, @NonNull final String group_identifier,
                                       final int conn, final long peer_count)
    {
        if (peer_count > 1L)
        {
            group_alone_since_ms.remove(group_identifier);
            if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                clear_group_connect_progress(group_identifier);
            }
            return;
        }

        group_alone_since_ms.putIfAbsent(group_identifier, System.currentTimeMillis());
        if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            clear_group_connect_progress(group_identifier);
        }
        else if (get_group_connect_attempt(group_identifier) == 0)
        {
            begin_group_connect_attempt(group_identifier);
        }

        if (group_connect_elapsed_ms(group_identifier) >= GROUP_FRIEND_FALLBACK_AFTER_MS)
        {
            if (should_run_group_maintenance(group_identifier, group_last_invite_request_ms, 15_000L))
            {
                send_group_invite_request_to_known_member_friends(group_identifier);
                send_group_invite_request_to_friends(group_identifier);
            }
        }

        if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_ERROR.value)
        {
            schedule_group_auto_reconnect(group_num, group_identifier);
        }
        else if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING.value)
        {
            TrifaToxService.wakeup_tox_thread();
            if (peer_count <= 1L)
            {
                escalate_if_alone_too_long(group_num, group_identifier);
            }
        }
        else if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value
                 && peer_count <= 1L)
        {
            // KHANDAQ (#246, found by a two-emulator repro): this branch did not exist. A private
            // group that had FINISHED connecting and then lost its peers was handled by nothing —
            // ERROR reconnects, CONNECTING escalates, and CONNECTED fell off the end. So the alone
            // escalation never ran in the one state it is for, and neither did the stuck-group reset
            // that hangs off it, which is why the recovery looked dead on a real device while its
            // unit tests passed: they cover the policy, not the path that reaches it.
            //
            // Public groups already do exactly this (see maintain_all_groups, the CONNECTED branch),
            // so this restores parity rather than inventing behaviour. escalate_if_alone_too_long is
            // itself rate-limited to one pass per period, and the hard reset it can lead to is gated
            // itself rate-limited to one pass per period.
            escalate_if_alone_too_long(group_num, group_identifier);
        }
    }

    static void maybe_log_group_mesh_periodic(final long group_num, @NonNull final String group_identifier)
    {
        if (should_run_group_maintenance(group_identifier, group_last_mesh_log_ms, GROUP_MESH_LOG_INTERVAL_MS))
        {
            log_group_peer_mesh_diagnostics(group_num, group_identifier, "maintain");
        }
    }

    /** Pull history from every peer we currently have transport to. */
    static void request_history_sync_from_transport_peers(final long group_num,
                                                          @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        try
        {
            final long self_peer_id = tox_group_self_get_peer_id(group_num);
            final long[] peers = tox_group_get_peerlist(group_num);
            if (peers == null)
            {
                return;
            }
            for (final long peer_id : peers)
            {
                if (peer_id < 0 || peer_id == self_peer_id)
                {
                    continue;
                }
                final int conn = tox_group_peer_get_connection_status(group_num, peer_id);
                if (!is_group_peer_online(conn))
                {
                    continue;
                }
                final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_num, peer_id);
                if (TextUtils.isEmpty(peer_pubkey))
                {
                    continue;
                }
                send_ngch_request(group_identifier, peer_pubkey, true);
                sync_group_message_history(group_num, peer_id);
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "request_history_sync_from_transport_peers:EE:" + e.getMessage());
        }
    }

    /** Ping peers listed in the group but with no direct/relay transport — wakes toxcore reconnect. */
    static void ping_unreachable_group_peers(final long group_num, @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        try
        {
            final long self_peer_id = tox_group_self_get_peer_id(group_num);
            final long[] peers = tox_group_get_peerlist(group_num);
            if (peers == null)
            {
                return;
            }
            int pinged = 0;
            for (final long peer_id : peers)
            {
                if (peer_id < 0 || peer_id == self_peer_id)
                {
                    continue;
                }
                final int conn = tox_group_peer_get_connection_status(group_num, peer_id);
                if (is_group_peer_online(conn))
                {
                    continue;
                }
                if (send_ngch_keepalive_packet(group_num, peer_id))
                {
                    pinged++;
                }
            }
            if (pinged > 0)
            {
                HelperGeneric.logI(TAG, "mesh_keepalive:gn=" + group_num + " id="
                        + group_identifier_short(group_identifier, false) + " pinged=" + pinged);
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "ping_unreachable_group_peers:EE:" + e.getMessage());
        }
    }

    /** Proactive mesh keepalive for multi-peer groups (detect broken links within seconds). */
    static void maintain_group_mesh_keepalive(final long group_num, @NonNull final String group_identifier,
                                              final int conn, final long peer_count)
    {
        if (group_num < 0 || peer_count <= 1L
            || conn != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            return;
        }
        final GroupMeshSnapshot snap = compute_group_mesh_snapshot(group_num);
        if (snap.listed <= 0)
        {
            return;
        }
        if (!should_run_group_maintenance(group_identifier, group_last_mesh_keepalive_ms,
                GROUP_MESH_KEEPALIVE_INTERVAL_MS))
        {
            return;
        }
        if (snap.none > 0 || snap.isPartiallyDegraded())
        {
            ping_unreachable_group_peers(group_num, group_identifier);
            if (MainActivity.tox_group_get_privacy_state(group_num) ==
                ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
            {
                nudge_public_group_dht(group_num, group_identifier);
            }
            else
            {
                send_group_invite_request_to_known_member_friends(group_identifier);
            }
            TrifaToxService.wakeup_tox_thread();
        }
    }

    static void on_group_mesh_fanout_recovered(final long group_num, @NonNull final String group_identifier)
    {
        HelperGeneric.logI(TAG, "mesh_fanout_recovered:gn=" + group_num + " id="
                + group_identifier_short(group_identifier, false));
        schedule_group_message_resync(group_num);
        sync_group_message_history_to_all_peers(group_num);
        flush_pending_group_messages(group_identifier);
        update_group_in_friendlist(group_identifier);
        update_group_in_groupmessagelist(group_identifier, true);
        TrifaToxService.wakeup_tox_thread();
    }

    static void escalate_group_mesh_recovery(final long group_num, @NonNull final String group_identifier,
                                             @NonNull final GroupMeshSnapshot snap)
    {
        HelperGeneric.logI(TAG, "mesh_recovery:gn=" + group_num + " id="
                + group_identifier_short(group_identifier, false)
                + " fanout=" + snap.fanout + "/" + snap.listed
                + " direct=" + snap.direct + " relay=" + snap.relay + " none=" + snap.none);
        TrifaToxService.wakeup_tox_thread();
        ping_unreachable_group_peers(group_num, group_identifier);
        request_history_sync_from_transport_peers(group_num, group_identifier);
        schedule_group_message_resync(group_num);
        sync_group_peers_from_tox_to_db(group_num);

        final boolean is_public = MainActivity.tox_group_get_privacy_state(group_num) ==
                ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value;
        if (is_public)
        {
            nudge_public_group_dht(group_num, group_identifier);
        }
        else
        {
            send_group_invite_request_to_known_member_friends(group_identifier);
            send_group_invite_request_to_friends(group_identifier);
        }

        update_group_in_friendlist(group_identifier);
        update_group_in_groupmessagelist(group_identifier);
    }

    /**
     * Detect CONNECTED groups where peer transport paths are missing (frozen chat) and recover.
     * Safe while CONNECTING — never calls tox_group_reconnect here.
     */
    static void maintain_group_mesh_health(final long group_num, @NonNull final String group_identifier,
                                           final int conn, final long peer_count)
    {
        if (group_num < 0 || TextUtils.isEmpty(group_identifier))
        {
            return;
        }

        final GroupMeshSnapshot snap = compute_group_mesh_snapshot(group_num);
        snap.groupConn = conn;
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        group_mesh_snapshot_cache.put(key, snap);

        final int prevFanout = group_last_mesh_fanout.containsKey(key)
                ? group_last_mesh_fanout.get(key) : -1;
        group_last_mesh_fanout.put(key, snap.fanout);

        if (conn != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value
            || peer_count <= 1L || snap.listed <= 0)
        {
            group_mesh_degraded_since_ms.remove(key);
            if (prevFanout == 0 && snap.fanout > 0)
            {
                on_group_mesh_fanout_recovered(group_num, group_identifier);
            }
            return;
        }

        if (!snap.isDeliveryDegraded())
        {
            if (prevFanout == 0 && snap.fanout > 0)
            {
                on_group_mesh_fanout_recovered(group_num, group_identifier);
            }
            group_mesh_degraded_since_ms.remove(key);
            return;
        }

        group_mesh_degraded_since_ms.putIfAbsent(key, System.currentTimeMillis());
        final Long degradedSince = group_mesh_degraded_since_ms.get(key);
        if (degradedSince == null
            || System.currentTimeMillis() - degradedSince < GROUP_MESH_DEGRADED_ESCALATE_MS)
        {
            return;
        }

        if (!should_run_group_maintenance(group_identifier, group_last_mesh_recovery_ms,
                GROUP_MESH_RECOVERY_COOLDOWN_MS))
        {
            return;
        }

        escalate_group_mesh_recovery(group_num, group_identifier, snap);

        if (degradedSince != null
            && System.currentTimeMillis() - degradedSince >= GROUP_MESH_KICKSTART_AFTER_MS
            && should_run_group_maintenance(group_identifier, group_last_mesh_kickstart_ms,
                    GROUP_MESH_KICKSTART_COOLDOWN_MS))
        {
            HelperGeneric.logI(TAG, "mesh_kickstart:gn=" + group_num + " id="
                    + group_identifier_short(group_identifier, false)
                    + " degraded_ms=" + (System.currentTimeMillis() - degradedSince));
            final Thread t = new Thread(() -> kickstart_group_connection(group_num, group_identifier),
                    "mesh-kickstart-" + group_num);
            t.setDaemon(true);
            t.start();
        }
    }

    /** Peer lost transport within the group (timeout/disconnect) — resync from remaining peers. */
    static void handle_group_peer_transport_lost(final long group_number, final long peer_id, final int exit_type)
    {
        if (exit_type != ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_TIMEOUT.value
            && exit_type != ToxVars.Tox_Group_Exit_Type.TOX_GROUP_EXIT_TYPE_DISCONNECTED.value)
        {
            return;
        }
        if (group_number < 0 || !is_tox_started)
        {
            return;
        }
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (TextUtils.isEmpty(group_identifier) || is_group_we_left(group_identifier))
            {
                return;
            }
            markGroupPeerReconnectNoise(group_identifier, peer_id);
            HelperGeneric.logI(TAG, "peer_transport_lost:gn=" + group_number + " pid=" + peer_id
                    + " id=" + group_identifier_short(group_identifier, false)
                    + " exit_type=" + exit_type);
            TrifaToxService.wakeup_tox_thread();
            log_group_peer_mesh_diagnostics(group_number, group_identifier, "peer_exit pid=" + peer_id);
            ping_unreachable_group_peers(group_number, group_identifier);
            schedule_group_message_resync(group_number);
            request_history_sync_from_transport_peers(group_number, group_identifier);
            group_mesh_degraded_since_ms.putIfAbsent(group_identifier.toLowerCase(Locale.ENGLISH),
                    System.currentTimeMillis());
            update_group_in_friendlist(group_identifier);
            update_group_in_groupmessagelist(group_identifier, true);
        }
        catch (Exception e)
        {
            Log.w(TAG, "handle_group_peer_transport_lost:EE:" + e.getMessage());
        }
    }

    static void on_group_message_received_from_peer(@NonNull final String group_identifier, final long group_number,
                                                    @Nullable final String peer_pubkey)
    {
        if (!TextUtils.isEmpty(peer_pubkey))
        {
            touch_group_peer_online(group_identifier, peer_pubkey);
        }
        if (group_number >= 0)
        {
            final GroupMeshSnapshot snap = compute_group_mesh_snapshot(group_number);
            snap.groupConn = tox_group_is_connected(group_number);
            group_mesh_snapshot_cache.put(group_identifier.toLowerCase(Locale.ENGLISH), snap);
            if (!snap.isDeliveryDegraded())
            {
                group_mesh_degraded_since_ms.remove(group_identifier.toLowerCase(Locale.ENGLISH));
            }
        }
    }

    private static volatile long last_nudge_bootstrap_ms = 0L;

    static void nudge_public_group_dht(final long group_num, @NonNull final String group_identifier)
    {
        if (group_num < 0 || !is_tox_started)
        {
            return;
        }
        if (MainActivity.tox_group_get_privacy_state(group_num) !=
            ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            return;
        }
        if (global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            return;
        }
        // The post-join discovery loop calls this every ~2s. Re-bootstrapping the whole DHT that
        // often churns the routing table and keeps interrupting the in-flight NGC announce lookup
        // (which needs 30-90s undisturbed), so the group never settles and peers never appear.
        // Cap the expensive re-bootstrap to once per 20s; the cheap wakeup still runs every call.
        final long now = System.currentTimeMillis();
        if (now - last_nudge_bootstrap_ms >= 20_000L)
        {
            last_nudge_bootstrap_ms = now;
            // KHANDAQ: the burst resolves bootstrap-node hostnames with BLOCKING getaddrinfo calls —
            // never run it on the caller's thread (several call sites are the UI thread → ANR).
            new Thread(() -> {
                try
                {
                    perform_khandaq_bootstrap_burst();
                    TrifaToxService.bootstrap_me(false);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }, "group-dht-nudge").start();
        }
        TrifaToxService.wakeup_tox_thread();
        HelperGeneric.logI(TAG, "nudge_public_group_dht:id=" + group_identifier + " gn=" + group_num
                + " peers=" + tox_group_peer_count(group_num) + " conn=" + tox_group_is_connected(group_num));
    }

    static void maintain_all_groups()
    {
        if (!is_tox_started)
        {
            return;
        }
        try
        {
            final long num_groups = MainActivity.tox_group_get_number_groups();
            if (num_groups <= 0)
            {
                return;
            }
            final long[] group_numbers = MainActivity.tox_group_get_grouplist();
            if (group_numbers == null)
            {
                return;
            }

            for (long group_num : group_numbers)
            {
                if (group_num < 0)
                {
                    continue;
                }
                final String group_identifier = tox_group_by_groupnum__wrapper(group_num);
                if (group_identifier == null || is_group_we_left(group_identifier))
                {
                    continue;
                }
                if (!is_group_active(group_identifier))
                {
                    if (is_group_we_left(group_identifier))
                    {
                        continue;
                    }
                    set_group_active(group_identifier);
                    HelperGeneric.logI(TAG, "maintain_all_groups:reactivated_inactive id="
                            + group_identifier_short(group_identifier, false) + " gn=" + group_num);
                }

                final int conn = tox_group_is_connected(group_num);
                final long peer_count = Math.max(0L, tox_group_peer_count(group_num));
                final boolean is_public = MainActivity.tox_group_get_privacy_state(group_num) ==
                        ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value;

                // KHANDAQ (audit #2 finding 1, step 3): periodic re-announcement, before the
                // public/private split so both kinds get it. A peer that joins after us can only
                // learn our signing key from a live packet, and on_group_connected fires only for
                // us — so without this, anyone arriving later could never verify our history.
                // announceToGroup holds a 10-minute per-group interval, so this costs one 120-byte
                // packet per group per ten minutes at most.
                announce_hsk_to_group(group_num, group_identifier, false);

                if (!is_public)
                {
                    maintain_private_group(group_num, group_identifier, conn, peer_count);
                    maintain_group_mesh_health(group_num, group_identifier, conn, peer_count);
                    maintain_group_mesh_keepalive(group_num, group_identifier, conn, peer_count);
                    if (should_run_group_maintenance(group_identifier, group_last_peer_sync_ms, GROUP_PEER_SYNC_INTERVAL_MS))
                    {
                        sync_group_peers_from_tox_to_db(group_num);
                        dedupe_group_peer_db_by_display_name(group_identifier, group_num);
                        update_group_in_friendlist(group_identifier);
                    }
                    maybe_log_group_mesh_periodic(group_num, group_identifier);
                    continue;
                }

                if (peer_count > 1L)
                {
                    group_alone_since_ms.remove(group_identifier);
                    group_udp_hint_shown.remove(group_identifier); // recovered -> re-arm the hint
                    group_udp_hint_suppress_logged.remove(group_identifier);
                }
                else
                {
                    group_alone_since_ms.putIfAbsent(group_identifier, System.currentTimeMillis());
                    if (group_connect_elapsed_ms(group_identifier) >= GROUP_FRIEND_FALLBACK_AFTER_MS)
                    {
                        if (should_run_group_maintenance(group_identifier, group_last_invite_request_ms, 15_000L))
                        {
                            send_group_invite_request_to_known_member_friends(group_identifier);
                            send_group_invite_request_to_friends(group_identifier);
                        }
                    }
                }

                if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
                {
                    final long kickstart_interval = is_group_peer_discovery_in_progress(group_identifier)
                            ? 15_000L : 60_000L;
                    if (peer_count <= 1L && !escalate_if_alone_too_long(group_num, group_identifier)
                        && should_run_group_maintenance(group_identifier, group_last_kickstart_ms, kickstart_interval))
                    {
                        nudge_public_group_dht(group_num, group_identifier);
                    }
                }
                else if (conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING.value)
                {
                    TrifaToxService.wakeup_tox_thread();
                    final long kickstart_interval = is_group_peer_discovery_in_progress(group_identifier)
                            ? 15_000L : 60_000L;
                    if (peer_count <= 1L && !escalate_if_alone_too_long(group_num, group_identifier)
                        && should_run_group_maintenance(group_identifier, group_last_kickstart_ms, kickstart_interval))
                    {
                        nudge_public_group_dht(group_num, group_identifier);
                    }
                    else if (peer_count > 1L
                             && should_run_group_maintenance(group_identifier, group_last_connecting_resync_ms,
                                     30_000L))
                    {
                        schedule_group_message_resync(group_num);
                    }
                    // Stuck CONNECTING with no peers for too long and no friend to fall back on:
                    // tell the user their network is likely blocking UDP discovery (one-shot).
                    if (peer_count <= 1L)
                    {
                        maybe_hint_udp_blocked(group_num, group_identifier);
                    }
                }
                else
                {
                    schedule_group_auto_reconnect(group_num, group_identifier);
                }

                maintain_group_mesh_health(group_num, group_identifier, conn, peer_count);
                maintain_group_mesh_keepalive(group_num, group_identifier, conn, peer_count);

                if (should_run_group_maintenance(group_identifier, group_last_peer_sync_ms, GROUP_PEER_SYNC_INTERVAL_MS))
                {
                    sync_group_peers_from_tox_to_db(group_num);
                    dedupe_group_peer_db_by_display_name(group_identifier, group_num);
                    update_group_in_friendlist(group_identifier);
                }
                maybe_log_group_mesh_periodic(group_num, group_identifier);
            }

            if (++group_maintain_tick >= 40)
            {
                group_maintain_tick = 0;
                for (long group_num : group_numbers)
                {
                    if (group_num < 0)
                    {
                        continue;
                    }
                    final String group_identifier = tox_group_by_groupnum__wrapper(group_num);
                    if (group_identifier == null || is_group_we_left(group_identifier))
                    {
                        continue;
                    }
                    if (!is_group_active(group_identifier))
                    {
                        continue;
                    }
                    if (MainActivity.tox_group_get_privacy_state(group_num) !=
                        ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
                    {
                        continue;
                    }
                    sync_group_peers_from_tox_to_db(group_num);
                    update_group_in_friendlist(group_identifier);
                }
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "maintain_all_groups:EE:" + e.getMessage());
        }
    }

    static boolean reconnect_group_if_disconnected(final long group_num, final String group_identifier)
    {
        return reconnect_group_if_disconnected(group_num, group_identifier, false);
    }

    static boolean reconnect_group_if_disconnected(final long group_num, final String group_identifier,
                                                final boolean hard_reset)
    {
        if (group_num < 0 || !is_tox_started || group_identifier == null)
        {
            return false;
        }
        if (global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            return false;
        }

        try
        {
            if (!hard_reset)
            {
                // -1 = disconnected, 0 = CONNECTING, 1 = CONNECTED.
                // Only revive a genuinely disconnected group: reconnecting a
                // CONNECTING one aborts the announce lookup already in progress.
                final int conn_status = tox_group_is_connected(group_num);
                if (conn_status != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_ERROR.value)
                {
                    return false;
                }
            }

            clear_group_group_we_left(group_identifier);
            set_group_active(group_identifier);
            if (hard_reset)
            {
                // Public groups: reconnect alone sets update_self_announces in toxcore.
                // disconnect first only breaks DHT announce when no TCP relay is ready yet.
                if (MainActivity.tox_group_get_privacy_state(group_num) ==
                    ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
                {
                    // fall through to reconnect only
                }
                else
                {
                    tox_group_disconnect(group_num);
                    update_savedata_file_wrapper();
                    try
                    {
                        Thread.sleep(300L);
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                }
            }
            final int res = tox_group_reconnect(group_num);
            HelperGeneric.logI(TAG, "reconnect_group_if_disconnected:id=" + group_identifier + " gn=" + group_num
                    + " hard=" + hard_reset + " res=" + res);
            update_savedata_file_wrapper();
            TrifaToxService.wakeup_tox_thread();
            return true;
        }
        catch (Exception e)
        {
            Log.w(TAG, "reconnect_group_if_disconnected:EE:" + e.getMessage());
            return false;
        }
    }

    @Nullable
    static String normalize_public_group_chat_id(@Nullable final String group_id)
    {
        if (group_id == null)
        {
            return null;
        }
        final String cleaned = group_id.replace(" ", "").
                replace("\r", "").
                replace("\n", "").
                replaceAll("[^a-fA-F0-9]", "");
        if (cleaned.length() != TOX_GROUP_CHAT_ID_SIZE * 2)
        {
            return null;
        }
        return cleaned.toUpperCase(Locale.ENGLISH);
    }

    static boolean wait_for_tox_ready(final long timeout_ms)
    {
        final long deadline = System.currentTimeMillis() + timeout_ms;
        while (System.currentTimeMillis() < deadline)
        {
            if (is_tox_started)
            {
                return true;
            }
            TrifaToxService.wakeup_tox_thread();
            try
            {
                Thread.sleep(100L);
            }
            catch (InterruptedException ignored)
            {
            }
        }
        return is_tox_started;
    }

    static String join_public_group_failure_message(final long join_result)
    {
        if (join_result == -1L)
        {
            return MainActivity.context_s.getString(R.string.join_public_group_failed_not_ready);
        }
        if (join_result == -2L)
        {
            return MainActivity.context_s.getString(R.string.join_public_group_failed_bad_id);
        }
        if (join_result == -5L)
        {
            return MainActivity.context_s.getString(R.string.join_public_group_failed_password);
        }
        if (join_result == -6L)
        {
            return MainActivity.context_s.getString(R.string.join_public_group_failed_network);
        }
        return MainActivity.context_s.getString(R.string.join_public_group_failed);
    }

    static boolean finish_public_group_join(final long new_group_num, @NonNull final String group_identifier,
                                            final boolean show_toast)
    {
        if (new_group_num < 0 || new_group_num >= UINT32_MAX_JAVA)
        {
            return false;
        }

        String resolved_group_identifier = group_identifier;
        ByteBuffer groupid_buf = ByteBuffer.allocateDirect(GROUP_ID_LENGTH * 2);
        if (tox_group_get_chat_id(new_group_num, groupid_buf) == 0)
        {
            byte[] groupid_buffer = new byte[GROUP_ID_LENGTH];
            groupid_buf.get(groupid_buffer, 0, GROUP_ID_LENGTH);
            resolved_group_identifier = bytes_to_hex(groupid_buffer);
        }

        final int privacy_state = MainActivity.tox_group_get_privacy_state(new_group_num);
        HelperGeneric.logI(TAG, "finish_public_group_join:gn=" + new_group_num + " privacy=" + privacy_state +
                " id=" + resolved_group_identifier);

        add_group_wrapper(0, new_group_num, resolved_group_identifier, privacy_state);
        clear_group_group_we_left(resolved_group_identifier);

        if (show_toast)
        {
            display_toast(MainActivity.context_s.getString(R.string.join_public_group_joined), false, 300);
        }
        set_group_active(resolved_group_identifier);
        final int join_conn_status = tox_group_is_connected(new_group_num);
        if (privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            // Fresh tox_group_join leaves the group CONNECTING(0) with the announce
            // lookup already running — a reconnect here would restart it from zero.
            // Kick only a disconnected group or a connected-but-peerless one.
            final long peers = tox_group_peer_count(new_group_num);
            if (join_conn_status == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_ERROR.value
                || (join_conn_status == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value
                    && peers <= 1L))
            {
                kickstart_public_group_dht(new_group_num, resolved_group_identifier);
            }
            if (peers <= 1L)
            {
                // in parallel with the DHT lookup, ask friends in the group to invite us
                send_group_invite_request_to_friends(resolved_group_identifier);
                schedule_post_join_peer_discovery(new_group_num, resolved_group_identifier);
            }
        }
        else
        {
            begin_group_connect_attempt(resolved_group_identifier);
            if (tox_group_peer_count(new_group_num) <= 1L)
            {
                send_group_invite_request_to_friends(resolved_group_identifier);
            }
            if (join_conn_status == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_ERROR.value)
            {
                reconnect_group_if_disconnected(new_group_num, resolved_group_identifier, true);
            }
            else if (join_conn_status ==
                     TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
            {
                clear_group_connect_progress(resolved_group_identifier);
            }
        }
        sync_group_peers_from_tox_to_db(new_group_num);
        update_savedata_file_wrapper();
        TrifaToxService.wakeup_tox_thread();

        try
        {
            final GroupDB conf3 = (GroupDB) orma.selectFromGroupDB().group_identifierEq(
                    resolved_group_identifier.toLowerCase()).toList().get(0);
            CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
            cc.is_friend = COMBINED_IS_GROUP;
            cc.group_item = (GroupDB) GroupDB.deep_copy(conf3);
            if (MainActivity.friend_list_fragment != null)
            {
                MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
            }
        }
        catch (Exception e3)
        {
            HelperGeneric.logI(TAG, "finish_public_group_join:friendlist:EE:" + e3.getMessage());
        }
        return true;
    }

    static void handle_group_join_fail_async(final long group_number, final int fail_type)
    {
        Log.w(TAG, "handle_group_join_fail_async:gn=" + group_number + " fail=" + fail_type);
        final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
        if (group_identifier == null)
        {
            return;
        }
        reconnect_group_if_disconnected(group_number, group_identifier, true);
        sync_group_peers_from_tox_to_db(group_number);
        update_group_in_friendlist(group_identifier);
        TrifaToxService.wakeup_tox_thread();
        schedule_group_join_fail_retry(group_identifier);
    }

    private static void schedule_group_join_fail_retry(@NonNull final String group_identifier)
    {
        final String key = group_identifier.toLowerCase(Locale.ENGLISH);
        if (is_group_we_left(group_identifier))
        {
            return;
        }
        if (group_join_fail_retry_running.putIfAbsent(key, Boolean.TRUE) != null)
        {
            return;
        }

        final Thread t = new Thread(() ->
        {
            try
            {
                for (int attempt = 1; attempt <= 4; attempt++)
                {
                    Thread.sleep(2500L * attempt);
                    if (!is_tox_started || is_group_we_left(group_identifier))
                    {
                        return;
                    }
                    final long existing = tox_group_by_groupid__wrapper(group_identifier);
                    if (existing >= 0 && tox_group_is_connected(existing) ==
                        TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
                    {
                        finish_public_group_join(existing, group_identifier, false);
                        HelperGeneric.logI(TAG, "group_join_fail_retry:connected attempt=" + attempt);
                        return;
                    }
                    HelperGeneric.logI(TAG, "group_join_fail_retry:attempt=" + attempt + " id="
                            + group_identifier_short(group_identifier, false));
                    join_public_group_by_id(group_identifier, false);
                }
            }
            catch (InterruptedException ignored)
            {
            }
            finally
            {
                group_join_fail_retry_running.remove(key);
            }
        }, "grp-join-retry-" + group_identifier_short(group_identifier, false));
        t.setDaemon(true);
        t.start();
    }

    static boolean join_public_group_by_id(final String group_id, final boolean show_toast)
    {
        return join_public_group_by_id(group_id, null, show_toast);
    }

    static boolean join_public_group_by_id(final String group_id, @Nullable final String password, final boolean show_toast)
    {
        try
        {
            final String normalized_group_id = normalize_public_group_chat_id(group_id);
            if (normalized_group_id == null)
            {
                if (show_toast)
                {
                    display_toast(join_public_group_failure_message(-2L), false, 300);
                }
                return false;
            }

            HelperGeneric.logI(TAG, "join_group:group_id:>" + normalized_group_id + "<");

            if (!wait_for_tox_ready(12000L))
            {
                if (show_toast)
                {
                    display_toast(join_public_group_failure_message(-1L), false, 300);
                }
                return false;
            }

            long existing_group_num = tox_group_by_groupid__wrapper(normalized_group_id);
            if (existing_group_num >= 0)
            {
                HelperGeneric.logI(TAG, "join_group:already_in_tox gn=" + existing_group_num);
                return finish_public_group_join(existing_group_num, normalized_group_id, show_toast);
            }

            perform_khandaq_bootstrap_burst();
            TrifaToxService.wakeup_tox_thread();

            ByteBuffer join_chat_id_buffer = ByteBuffer.allocateDirect(TOX_GROUP_CHAT_ID_SIZE);
            byte[] data_join = HelperGeneric.hex_to_bytes(normalized_group_id);
            if (data_join == null || data_join.length != TOX_GROUP_CHAT_ID_SIZE)
            {
                return false;
            }
            join_chat_id_buffer.put(data_join);
            join_chat_id_buffer.rewind();

            long new_group_num = -1L;
            long last_join_result = -99L;
            for (int attempt = 0; attempt < 8; attempt++)
            {
                if (attempt > 0)
                {
                    TrifaToxService.wakeup_tox_thread();
                    try
                    {
                        Thread.sleep(800L * attempt);
                    }
                    catch (InterruptedException ignored)
                    {
                    }
                    existing_group_num = tox_group_by_groupid__wrapper(normalized_group_id);
                    if (existing_group_num >= 0)
                    {
                        HelperGeneric.logI(TAG, "join_group:appeared_in_tox attempt=" + attempt + " gn=" + existing_group_num);
                        return finish_public_group_join(existing_group_num, normalized_group_id, show_toast);
                    }
                }

                join_chat_id_buffer.rewind();
                new_group_num = MainActivity.tox_group_join(join_chat_id_buffer, TOX_GROUP_CHAT_ID_SIZE,
                                                            get_group_peer_join_name(), password);
                last_join_result = new_group_num;
                HelperGeneric.logI(TAG, "join_group:attempt=" + attempt + " groupnum=" + new_group_num);
                if ((new_group_num >= 0) && (new_group_num < UINT32_MAX_JAVA))
                {
                    break;
                }

                existing_group_num = tox_group_by_groupid__wrapper(normalized_group_id);
                if (existing_group_num >= 0)
                {
                    HelperGeneric.logI(TAG, "join_group:already_exists_after_fail gn=" + existing_group_num +
                            " err=" + new_group_num);
                    return finish_public_group_join(existing_group_num, normalized_group_id, show_toast);
                }
            }

            update_savedata_file_wrapper();
            if ((new_group_num >= 0) && (new_group_num < UINT32_MAX_JAVA))
            {
                HelperGeneric.logI(TAG, "join_group:new groupnum:=" + new_group_num);
                return finish_public_group_join(new_group_num, normalized_group_id, show_toast);
            }

            if (show_toast)
            {
                display_toast(join_public_group_failure_message(last_join_result), false, 300);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "join_group:EE01:" + e.getMessage());
        }
        return false;
    }

    static void do_join_public_group(Intent data)
    {
        try
        {
            final String group_id = data.getStringExtra("group_id");
            join_public_group_by_id(group_id, true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "join_group:EE01:" + e.getMessage());
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
                HelperGeneric.logI(TAG, "group_custom_packet_cb:gn=" + group_number + " peerid=" + peer_id + " ignoring own file");
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
                display_toast(context_s.getString(R.string.group_incoming_file_error), true, 0);
                return;
            }

            if ((group_temp != null)
                && (group_temp.group_identifier.toLowerCase().compareTo(group_id.toLowerCase()) != 0))
            {
                display_toast(context_s.getString(R.string.group_incoming_file_error), true, 0);
                return;
            }

            final String peer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);

            String groupname = null;
            try
            {
                if ((group_temp != null) && group_temp.notification_silent)
                {
                    do_notification = false;
                }
                if (group_notification_silent_peer_get(group_id, peer_pubkey))
                {
                    do_notification = false;
                }
                if (group_temp != null)
                {
                    groupname = group_temp.name;
                }
            }
            catch (Exception e)
            {
                // e.printStackTrace();
                do_notification = false;
            }

            if (group_message_list_activity != null)
            {
                //HelperGeneric.logI(TAG,
                //      "group_custom_packet_cb:noti_and_badge:002group:" + group_message_list_activity.get_current_group_id() + ":" + group_id);
                if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_id))
                {
                    // no notifcation and no badge update
                    do_notification = false;
                    do_badge_update = false;
                }
            }

            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
            hash_bytes.put(data, 8, 32);
            //HelperGeneric.logI(TAG, "group_custom_packet_cb:filename:"+hash_bytes.arrayOffset()+" "
            //           +hash_bytes.limit()+" "+hash_bytes.array().length);
            //HelperGeneric.logI(TAG, "group_custom_packet_cb:hash_bytes hex="
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
                HelperGeneric.logI(TAG,"group_custom_packet_cb:filename str=" + filename);

                //HelperGeneric.logI(TAG, "group_custom_packet_cb:filename:"+filename_bytes.arrayOffset()+" "
                //+filename_bytes.limit()+" "+filename_bytes.array().length);
                //HelperGeneric.logI(TAG, "group_custom_packet_cb:filename hex="
                //           + HelperGeneric.bytesToHex(filename_bytes.array(),filename_bytes.arrayOffset(),filename_bytes.limit()));
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }

            long file_size = length - header;
            if (file_size < 1)
            {
                HelperGeneric.logI(TAG, "group_custom_packet_cb: file size less than 1 byte");
                return;
            }

            String filename_corrected = get_incoming_filetransfer_local_filename(filename, group_id.toLowerCase());

            // HelperGeneric.logI(TAG, "group_custom_packet_cb:filename=" + filename + " filename_corrected=" + filename_corrected);

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
            m.message_id_tox = "";
            m.was_synced = false;
            m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
            m.path_name = VFS_PREFIX + VFS_FILE_DIR + "/" + m.group_identifier + "/";
            m.file_name = filename_corrected;
            m.filename_fullpath = m.path_name + m.file_name;
            m.storage_frame_work = false;
            m.msg_id_hash = bytebuffer_to_hexstring(hash_bytes, true);
            m.filesize = file_size;

            if (group_incoming_file_exists(group_id, m.msg_id_hash))
            {
                HelperGeneric.logI(TAG, "group_custom_packet_cb:duplicate file msg_id_hash=" + m.msg_id_hash);
                return;
            }

            try
            {
                m.tox_group_peername = tox_group_peer_get_name__wrapper(m.group_identifier,
                                                                        m.tox_group_peer_pubkey);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            m.text = HelperFiletransfer.buildOutgoingFileMessageText(context_s, filename_corrected, file_size);

            info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                    m.path_name + "/" + m.file_name);
            info.guardianproject.iocipher.File f2 = new info.guardianproject.iocipher.File(f1.getParent());
            f2.mkdirs();

            // KHANDAQ (#120 / Figma "Загрузка вложений"): only auto-save the group file when policy allows
            // (Всегда, or Только Wi-Fi on Wi-Fi) OR the user explicitly tapped download for it. On Никогда
            // the message is still inserted (shown as not-downloaded) so a later manual tap re-fetches it.
            if (ngc_incoming_download_allowed(group_id, m.msg_id_hash))
            {
                save_group_incoming_file(m.path_name, m.file_name, data, header, file_size);
            }

            if (group_message_list_activity != null)
            {
                if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_id.toLowerCase()))
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
                HelperGeneric.logI(TAG, "group_custom_packet_cb:new_msg_id=" + new_msg_id);
            }

            HelperFriend.refresh_chat_list_group_row_wrapper(group_id);
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
        send_ngch_request(group_identifier, peer_pubkey, false);
    }

    static void send_ngch_request(final String group_identifier, final String peer_pubkey, final boolean immediate)
    {
        try
        {
            long res = tox_group_self_get_peer_id(tox_group_by_groupid__wrapper(group_identifier));
            if (res == get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey))
            {
                return;
            }
        }
        catch (Exception ignored)
        {
        }

        final Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    if (!immediate)
                    {
                        final Random rand = new Random();
                        Thread.sleep(1000L * (1 + rand.nextInt(7)));
                    }
                    send_ngch_keepalive_packet(
                            tox_group_by_groupid__wrapper(group_identifier),
                            get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey));
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** Lightweight private packet to unreachable group peers (keepalive + history-sync request). */
    static boolean send_ngch_keepalive_packet(final long group_num, final long peer_id)
    {
        if (group_num < 0 || peer_id < 0 || !is_tox_started)
        {
            return false;
        }
        try
        {
            if (peer_id == tox_group_self_get_peer_id(group_num))
            {
                return false;
            }
            final int data_length = 6 + 1 + 1;
            final ByteBuffer data_buf = ByteBuffer.allocateDirect(data_length);
            data_buf.put((byte) 0x66);
            data_buf.put((byte) 0x77);
            data_buf.put((byte) 0x88);
            data_buf.put((byte) 0x11);
            data_buf.put((byte) 0x34);
            data_buf.put((byte) 0x35);
            data_buf.put((byte) 0x1);
            data_buf.put((byte) 0x1);
            final byte[] data = new byte[data_length];
            data_buf.rewind();
            data_buf.get(data);
            final int result = tox_group_send_custom_private_packet(group_num, peer_id, 1, data, data_length);
            return result == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static void send_ngch_delivery_receipt(final long group_number, final long peer_id, final String message_id_tox)
    {
        if (group_number < 0 || peer_id < 0 || message_id_tox == null || message_id_tox.length() < 4)
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
                    Thread.sleep(200);
                    final int data_length = 6 + 1 + 1 + 4;
                    final ByteBuffer data_buf = ByteBuffer.allocateDirect(data_length);
                    data_buf.put((byte) 0x66);
                    data_buf.put((byte) 0x77);
                    data_buf.put((byte) 0x88);
                    data_buf.put((byte) 0x11);
                    data_buf.put((byte) 0x34);
                    data_buf.put((byte) 0x35);
                    data_buf.put((byte) 0x1);
                    data_buf.put((byte) 0x4);
                    try
                    {
                        data_buf.put(HelperGeneric.hex_to_bytes(message_id_tox), 0, 4);
                    }
                    catch (Exception e)
                    {
                        return;
                    }
                    final byte[] data = new byte[data_length];
                    data_buf.rewind();
                    data_buf.get(data);
                    final int result = tox_group_send_custom_private_packet(group_number, peer_id, 1, data, data_length);
                    HelperGeneric.logI(TAG, "send_ngch_delivery_receipt:gn=" + group_number + " peer=" + peer_id
                            + " msg_id=" + message_id_tox + " result=" + result);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        t.start();
    }

    static void handle_incoming_group_delivery_receipt(final long group_number, final long peer_id,
                                                      final byte[] data, final int length)
    {
        if (length < (6 + 1 + 1 + 4))
        {
            return;
        }

        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (group_identifier == null)
            {
                return;
            }

            final String message_id_tox = HelperGeneric.bytesToHex(data, 8, 4).toLowerCase(Locale.ENGLISH);
            final String acker_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);
            bump_outgoing_group_delivery_confirmation(group_identifier, message_id_tox, acker_pubkey);
            HelperGeneric.logI(TAG, "group_delivery_ack:recv gn=" + group_number + " peer=" + peer_id + " msg_id=" + message_id_tox);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "handle_incoming_group_delivery_receipt:EE:" + e.getMessage());
        }
    }

    private static void bump_outgoing_group_delivery_confirmation(final String group_identifier,
                                                                  final String message_id_tox,
                                                                  final String acker_pubkey)
    {
        if (acker_pubkey == null || acker_pubkey.length() < 10)
        {
            return;
        }

        try
        {
            final List<GroupMessage> list = orma.selectFromGroupMessage().group_identifierEq(
                    group_identifier.toLowerCase()).directionEq(1).message_id_toxEq(message_id_tox).toList();
            if (list.isEmpty())
            {
                return;
            }

            final GroupMessage gmsg = list.get(0);
            if (acker_pubkey.equalsIgnoreCase(gmsg.tox_group_peer_pubkey))
            {
                return;
            }

            if (acker_pubkey.equalsIgnoreCase(gmsg.tox_group_peer_pubkey_syncer_01)
                || acker_pubkey.equalsIgnoreCase(gmsg.tox_group_peer_pubkey_syncer_02)
                || acker_pubkey.equalsIgnoreCase(gmsg.tox_group_peer_pubkey_syncer_03))
            {
                return;
            }

            final long now = System.currentTimeMillis();
            if (gmsg.sync_confirmations == 0)
            {
                orma.updateGroupMessage().group_identifierEq(group_identifier.toLowerCase()).directionEq(1).
                        message_id_toxEq(message_id_tox).sync_confirmations(1).
                        tox_group_peer_pubkey_syncer_01(acker_pubkey).
                        tox_group_peer_pubkey_syncer_01_sent_timestamp(now).execute();
                gmsg.sync_confirmations = 1;
                gmsg.tox_group_peer_pubkey_syncer_01 = acker_pubkey;
            }
            else if (gmsg.sync_confirmations == 1)
            {
                orma.updateGroupMessage().group_identifierEq(group_identifier.toLowerCase()).directionEq(1).
                        message_id_toxEq(message_id_tox).sync_confirmations(2).
                        tox_group_peer_pubkey_syncer_02(acker_pubkey).
                        tox_group_peer_pubkey_syncer_02_sent_timestamp(now).execute();
                gmsg.sync_confirmations = 2;
                gmsg.tox_group_peer_pubkey_syncer_02 = acker_pubkey;
            }
            else if (gmsg.sync_confirmations == 2)
            {
                orma.updateGroupMessage().group_identifierEq(group_identifier.toLowerCase()).directionEq(1).
                        message_id_toxEq(message_id_tox).sync_confirmations(3).
                        tox_group_peer_pubkey_syncer_03(acker_pubkey).
                        tox_group_peer_pubkey_syncer_03_sent_timestamp(now).execute();
                gmsg.sync_confirmations = 3;
                gmsg.tox_group_peer_pubkey_syncer_03 = acker_pubkey;
            }
            else
            {
                orma.updateGroupMessage().group_identifierEq(group_identifier.toLowerCase()).directionEq(1).
                        message_id_toxEq(message_id_tox).sync_confirmations(gmsg.sync_confirmations + 1).execute();
                gmsg.sync_confirmations++;
            }

            update_group_message_in_list(gmsg);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "bump_outgoing_group_delivery_confirmation:EE:" + e.getMessage());
        }
    }

    /** Push recent local messages to every online peer (fallback when tox mesh delivery is delayed). */
    static void sync_group_message_history_to_all_peers(final long group_number)
    {
        if (group_number < 0 || !is_tox_started)
        {
            return;
        }
        try
        {
            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            if (group_identifier == null)
            {
                return;
            }
            final long self_peer_id = tox_group_self_get_peer_id(group_number);
            final long[] peers = tox_group_get_peerlist(group_number);
            int sync_targets = 0;
            if (peers != null)
            {
                for (long peer_id : peers)
                {
                    if (peer_id >= 0 && peer_id != self_peer_id)
                    {
                        sync_group_message_history(group_number, peer_id);
                        sync_targets++;
                    }
                }
            }
            HelperGeneric.logI(TAG, "sync_group_message_history_to_all_peers:gn=" + group_number
                    + " gid=" + group_identifier_short(group_identifier, false)
                    + " tox_peers=" + Math.max(0L, tox_group_peer_count(group_number))
                    + " sync_targets=" + sync_targets);
        }
        catch (Exception e)
        {
            Log.w(TAG, "sync_group_message_history_to_all_peers:EE:" + e.getMessage());
        }
    }

    static void request_group_media_resync(final GroupMessage message)
    {
        if (message == null || message.group_identifier == null)
        {
            return;
        }

        try
        {
            final long group_num = tox_group_by_groupid__wrapper(message.group_identifier);
            if (group_num >= 0)
            {
                sync_group_message_history_to_all_peers(group_num);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "request_group_media_resync:EE:" + e.getMessage());
        }
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
                HelperGeneric.logI(TAG, "sync_group_message_history:dont send to self");
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
                    final int history_window_seconds = TOX_NGC_HISTORY_SYNC_MAX_SECONDS_BACK;
                    final long sync_from_ts = history_window_seconds <= 0
                            ? 0
                            : System.currentTimeMillis() - (history_window_seconds * 1000L);

                    // HelperGeneric.logI(TAG, "sync_group_message_history:sync_from_ts:" + sync_from_ts);

                    Iterator<com.zoffcc.applications.sorm.GroupMessage> i1 =  orma.selectFromGroupMessage()
                            .group_identifierEq(group_identifier)
                            // .TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value)
                            .private_messageEq(0)
                            .tox_group_peer_pubkeyNotEq("-1")
                            .sent_timestampGt(sync_from_ts)
                            .orderByRcvd_timestampAsc()
                            .toList().iterator();

                    // HelperGeneric.logI(TAG, "sync_group_message_history:i1:" + i1);

                    while (i1.hasNext())
                    {
                        try
                        {
                            GroupMessage gm = (GroupMessage) i1.next();
                            // KHANDAQ (audit F-6, round 5): NEVER re-serve a second-hand row. A was_synced
                            // row reached us through history sync, and the author pubkey on it is an
                            // UNSIGNED claim by whoever synced it — we have no way to check it. Passing it
                            // on makes us corroborate that claim to the rest of the group and bumps the
                            // row's sync_confirmations on every receiver, which is exactly what turns a
                            // one-hop injection into history the whole group agrees on. Round 4 still let
                            // such a row through while its claimed author was in the live peer list; that
                            // is not a check on the CLAIM (an attacker names a member who is present), so
                            // it is gone. We only ever hand out history we received FIRST-HAND: our own
                            // rows and rows delivered live are was_synced=false and are unaffected.
                            // Cost: a device that holds a message only second-hand no longer heals other
                            // peers with it, so history reaches a joiner one hop at a time (see attestation).
                            if (gm.was_synced)
                            {
                                continue;
                            }
                            if (!gm.tox_group_peer_pubkey.equalsIgnoreCase("-1"))
                            {
                                //HelperGeneric.logI(TAG, "sync_group_message_history:sync:sent_ts="
                                //           + gm.sent_timestamp + " syncts=" + sync_from_ts + " "
                                //           + gm.tox_group_peer_pubkey + " " +
                                //           gm.message_id_tox + " " + gm.msg_id_hash);
                                if (gm.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                                {
                                    final long syncFileSize = gm.filesize > 0L ? gm.filesize : 0L;
                                    if (NgcGroupFileTransfer.shouldUseChunkedTransfer(syncFileSize))
                                    {
                                        continue;
                                    }
                                    send_ngch_syncfile(group_identifier, peer_pubkey, gm);
                                }
                                else
                                {
                                    send_ngch_syncmsg(group_identifier, peer_pubkey, gm);
                                    // KHANDAQ (audit #2 finding 1, step 3): additionally send the
                                    // signed copy of rows we wrote ourselves. Additionally, not
                                    // instead: version 0x02 is dropped by every shipped parser, so
                                    // replacing the packet above would make our history vanish for
                                    // everyone in the field. Only our own rows can be signed at all
                                    // — the signature is the AUTHOR's, and a relayer cannot make it,
                                    // which is the whole point of the finding.
                                    NgcSignedHistory.sendSignedTextTo(group_identifier, peer_pubkey, gm);
                                }
                            }
                            else
                            {
                                // HelperGeneric.logI(TAG, "sync_group_message_history:sync:ignoring system message");
                            }
                        }
                        catch (Exception e2)
                        {
                            e2.printStackTrace();
                        }
                    }

                    // HelperGeneric.logI(TAG, "sync_group_message_history:END");
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
            int rndi = rand.nextInt(51);
            int n = 50 + rndi;
            // HelperGeneric.logI(TAG, "send_ngch_syncmsg: sleep for " + n + " ms");
            Thread.sleep(n);
            //
            final int header_length = 6 + 1 + 1 + 4 + 32 + 4 + 25;
            final int data_length = header_length + m.text.getBytes(StandardCharsets.UTF_8).length;

            if (data_length < (header_length + 1) || (data_length > 40000))
            {
                HelperGeneric.logI(TAG, "send_ngch_syncmsg: some error in calculating data length");
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
            // HelperGeneric.logI(TAG,"send_ngch_syncmsg:outgoing_timestamp=" + timestamp_tmp);
            ByteBuffer temp_buffer = ByteBuffer.allocate(8);
            temp_buffer.putLong(timestamp_tmp).order(ByteOrder.BIG_ENDIAN);
            temp_buffer.position(4);
            data_buf.put(temp_buffer);
            //HelperGeneric.logI(TAG,"send_ngch_syncmsg:send_ts_bytes:" +
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
            //HelperGeneric.logI(TAG,"send_ngch_syncmsg:send_ts_bytes_to_network:" +
            //          HelperGeneric.bytesToHex(data, 6 + 1 + 1 + 4 + 32 , 4));
            final long group_num = tox_group_by_groupid__wrapper(group_identifier);
            final long peer_num = get_group_peernum_from_peer_pubkey(group_identifier, peer_pubkey);
            int result = tox_group_send_custom_private_packet(group_num, peer_num, 1, data, data_length);
            HelperGeneric.logI(TAG, "send_ngch_syncmsg:result=" + result + " gn=" + group_num + " peer_num=" + peer_num
                    + " gid=" + group_identifier_short(group_identifier, false));
        }
        catch(Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "send_ngch_syncmsg:EE:" + e.getMessage());
        }
    }

    private static void send_ngch_syncfile(final String group_identifier, final String peer_pubkey, final GroupMessage m)
    {
        try
        {
            if (m == null)
            {
                return;
            }

            final info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                m.path_name + "/" + m.file_name);
            final java.io.File f2 = new java.io.File(m.path_name + "/" + m.file_name);

            long f_length = 0;
            if (m.direction == 1)
            {
                f_length = f2.length();
            }
            else
            {
                f_length = f1.length();
            }

            if (f_length < 1L || NgcGroupFileTransfer.shouldUseChunkedTransfer(f_length))
            {
                HelperGeneric.logI(TAG, "send_ngch_syncfile: skip chunked/large file size=" + f_length);
                return;
            }

            Random rand = new Random();
            int rndi = rand.nextInt(51);
            int n = 50 + rndi;
            HelperGeneric.logI(TAG, "send_ngch_syncfile: sleep for " + n + " ms");
            Thread.sleep(n);
            //
            final int header_length = 6 + 1 + 1 + 32 + 32 + 4 + 25 + 255;

            long data_length_ = header_length + f_length;

            HelperGeneric.logI(TAG, "send_ngch_syncfile: file=" + m.path_name + "__/__" + m.file_name + " " + m.filename_fullpath);
            HelperGeneric.logI(TAG, "send_ngch_syncfile: data_length=" + data_length_ + " header_length=" +
                       header_length + " filesize=" + f_length);

            if (data_length_ < (header_length + 1) || (data_length_ > 40000))
            {
                HelperGeneric.logI(TAG, "send_ngch_syncfile: some error in calculating data length");
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
            HelperGeneric.logI(TAG,"send_ngch_syncfile:outgoing_timestamp=" + timestamp_tmp);
            ByteBuffer temp_buffer = ByteBuffer.allocate(8);
            temp_buffer.putLong(timestamp_tmp).order(ByteOrder.BIG_ENDIAN);
            temp_buffer.position(4);
            data_buf.put(temp_buffer);
            //HelperGeneric.logI(TAG,"send_ngch_syncmsg:send_ts_bytes:" +
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
                HelperGeneric.logI(TAG,"send_ngch_syncmsg:send_ts_bytes:filename_bytes=" +
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
            // HelperGeneric.logI(TAG, "send_ngch_syncfile: sending request:result=" + result);
        }
        catch(Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "send_ngch_syncfile:EE:" + e.getMessage());
        }
    }

    /**
     * Display name for a peer whose message reached us through history sync. The name in the packet is
     * unsigned and attacker-chosen, so a name we know locally (live from toxcore, else GroupPeerDB) wins;
     * the synced one is only a fallback for when we have no real name and would show a hex id instead.
     * Callers with no name on the wire at all (toxproxy relay sync) pass null for name_from_packet.
     */
    static String resolve_synced_peer_name(final String group_identifier, final String sender_pubkey,
                                           final long sender_peer_num, final String name_from_packet)
    {
        // the wrapper never returns null for a real pubkey: when it knows nothing it hands back the short
        // hex id, so that (not a null check) is the test for "we actually have a name for this pubkey"
        final String local_name = tox_group_peer_get_name__wrapper(group_identifier, sender_pubkey, sender_peer_num);
        if (!TextUtils.isEmpty(local_name) && !is_short_hex_peer_id(local_name, sender_pubkey))
        {
            return local_name;
        }

        final String from_packet = pick_non_hex_group_peer_name(name_from_packet, sender_pubkey);
        return (from_packet != null) ? from_packet : name_from_packet;
    }

    // KHANDAQ (audit F-6): a per-syncer rate limit on incoming history sync was tried here and removed
    // again on review. It could not bind the attack it targeted — the counter is keyed by the syncer's
    // NGC pubkey, which is minted fresh on every rejoin, so a flooder just rotates identity, and any
    // bound on the tracking table is either unbounded memory or fillable with ephemeral keys. Meanwhile
    // the only threshold that is safe for an honest peer sits far above what already hurts us (one
    // honest syncer legitimately bursts ~10k-30k packets/min right after a public-group join), so the
    // limiter bought no real protection while risking silently dropped history. Storage/CPU abuse of
    // this path has to be bounded where the cost is (per-group history caps), not by counting packets.

    static void handle_incoming_sync_group_message(final long group_number, final long peer_id, final byte[] data, final long length)
    {
        try
        {
            long res = tox_group_self_get_peer_id(group_number);
            if (res == peer_id)
            {
                // HINT: do not add our own messages, they are already in the DB!
                // HelperGeneric.logI(TAG, "handle_incoming_sync_group_message:gn=" + group_number + " peerid=" + peer_id + " ignoring self");
                return;
            }

            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            final String syncer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);

            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_GROUP_PEER_PUBLIC_KEY_SIZE);
            hash_bytes.put(data, 8 + 4, 32);
            final String original_sender_peerpubkey = HelperGeneric.bytesToHex(hash_bytes.array(),hash_bytes.arrayOffset(),hash_bytes.limit()).toUpperCase();
            // HelperGeneric.logI(TAG, "handle_incoming_sync_group_message:peerpubkey hex=" + original_sender_peerpubkey);

            // check for muted or kicked peers
            if (is_group_muted_or_kicked_peer(group_identifier, original_sender_peerpubkey))
            {
                return;
            }
            // check for muted or kicked peers

            if (tox_group_self_get_public_key(group_number).toUpperCase().equalsIgnoreCase(original_sender_peerpubkey))
            {
                // HINT: do not add our own messages, they are already in the DB!
                // HelperGeneric.logI(TAG, "handle_incoming_sync_group_message:gn=" + group_number + " peerid=" + peer_id + " ignoring myself as original sender");
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
            //HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:got_ts_bytes:" +
            //          HelperGeneric.bytesToHex(data, 8+4+32, 4));
            timestamp_byte_buffer.rewind();
            //HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:got_ts_bytes:bytebuffer:" +
            //          HelperGeneric.bytesToHex(timestamp_byte_buffer.array(),
            //                                   timestamp_byte_buffer.arrayOffset(),
             //                                  timestamp_byte_buffer.limit()));

            // HelperGeneric.logI(TAG, "handle_incoming_sync_group_message:timestamp=" + timestamp);

            if (timestamp > ((System.currentTimeMillis() / 1000) + (60 * 5)))
            {
                long delta_t = timestamp - (System.currentTimeMillis() / 1000);
                // HelperGeneric.logI(TAG, "handle_incoming_sync_group_message:delta t=" + delta_t + " do NOT sync messages from the future");
                return;
            }
            else if (timestamp < ((System.currentTimeMillis() / 1000) - (60 * 200)))
            {
                long delta_t = (System.currentTimeMillis() / 1000) - timestamp;
                // HelperGeneric.logI(TAG, "handle_incoming_sync_group_message:delta t=" + (-delta_t) + " do NOT sync messages that are too old");
                return;
            }

            //
            //
            //
            ByteBuffer hash_msg_id_bytes = ByteBuffer.allocateDirect(4);
            hash_msg_id_bytes.put(data, 8, 4);
            final String message_id_tox = HelperGeneric.bytesToHex(hash_msg_id_bytes.array(),hash_msg_id_bytes.arrayOffset(),hash_msg_id_bytes.limit()).toLowerCase();
            hash_msg_id_bytes.rewind();
            check_group_message_sequence_gap(group_number, hash_msg_id_bytes.getInt() & 0xffffffffL);
            // HelperGeneric.logI(TAG, "handle_incoming_sync_group_message:message_id_tox hex=" + message_id_tox);
            //
            //

            try
            {
                ByteBuffer name_buffer = ByteBuffer.allocateDirect(TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                name_buffer.put(data, 8 + 4 + 32 + 4, TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                String peer_name = utf8_string_from_bytes_with_padding(name_buffer,
                                                                             TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES,
                                                                             "peer");
                // HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:peer_name str=" + peer_name);


                //
                final int header = 6+1+1+4+32+4+25; // 73 bytes
                long text_size = length - header;
                if ((text_size < 1) || (text_size > 37000))
                {
                    HelperGeneric.logI(TAG, "handle_incoming_sync_group_message: text size less than 1 byte or larger than 37000 bytes");
                    return;
                }

                byte[] text_byte_buf = Arrays.copyOfRange(data, header, (int)length);
                String message_str = new String(text_byte_buf, StandardCharsets.UTF_8);
                // HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:message str=" + message_str);

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
                                //HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:syn_conf: message_id_tox="
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
                                        // HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:syn_conf=1, syncer=" + syncer_pubkey);
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
                                        // HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:syn_conf=2, syncer=" + syncer_pubkey);
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
                                        // HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:syn_conf=3, syncer=" + syncer_pubkey);
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
                    HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:EE003:" + e.getMessage());
                }

                if (gm != null)
                {
                    // HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:potential double message:" + message_str);
                    return;
                }

                // KHANDAQ (audit F-6): a locally known name beats the unsigned one carried in the packet.
                peer_name = resolve_synced_peer_name(group_identifier, original_sender_peerpubkey, sender_peer_num,
                                                     peer_name);

                group_message_add_from_sync(group_identifier, syncer_pubkey, sender_peer_num, original_sender_peerpubkey,
                                            TRIFA_MSG_TYPE_TEXT.value, message_str, message_str.length(),
                                            (timestamp * 1000), message_id_tox,
                                            TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value,
                                            peer_name);

                if (message_id_tox != null && message_id_tox.length() >= 4)
                {
                    send_ngch_delivery_receipt(group_number, sender_peer_num, message_id_tox);
                }
            }
            catch(Exception e)
            {
                e.printStackTrace();
                HelperGeneric.logI(TAG,"handle_incoming_sync_group_message:EE002:" + e.getMessage());
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "handle_incoming_sync_group_message:EE001:" + e.getMessage());
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
                // HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:gn=" + group_number + " peerid=" + peer_id + " ignoring self");
                return;
            }

            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            final String syncer_pubkey = tox_group_peer_get_public_key__wrapper(group_number, peer_id);

            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_GROUP_PEER_PUBLIC_KEY_SIZE);
            hash_bytes.put(data, 8 + 32, 32);
            final String original_sender_peerpubkey = HelperGeneric.bytesToHex(hash_bytes.array(),hash_bytes.arrayOffset(),hash_bytes.limit()).toUpperCase();
            // HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:peerpubkey hex=" + original_sender_peerpubkey);

            // check for muted or kicked peers
            if (is_group_muted_or_kicked_peer(group_identifier, original_sender_peerpubkey))
            {
                return;
            }
            // check for muted or kicked peers

            if (tox_group_self_get_public_key(group_number).toUpperCase().equalsIgnoreCase(original_sender_peerpubkey))
            {
                // HINT: do not add our own files, they are already in the DB!
                // HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:gn=" + group_number + " peerid=" + peer_id + " ignoring myself as original sender");
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
            //HelperGeneric.logI(TAG,"handle_incoming_sync_group_file:got_ts_bytes:" +
            //          HelperGeneric.bytesToHex(data, 8+32+32, 4));
            timestamp_byte_buffer.rewind();
            //HelperGeneric.logI(TAG,"handle_incoming_sync_group_file:got_ts_bytes:bytebuffer:" +
            //          HelperGeneric.bytesToHex(timestamp_byte_buffer.array(),
            //                                   timestamp_byte_buffer.arrayOffset(),
            //                                   timestamp_byte_buffer.limit()));

            //HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:timestamp=" + timestamp);

            if (timestamp > ((System.currentTimeMillis() / 1000) + (60 * 5)))
            {
                long delta_t = timestamp - (System.currentTimeMillis() / 1000);
                // HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:delta t=" + delta_t + " do NOT sync files from the future");
                return;
            }
            else if (timestamp < ((System.currentTimeMillis() / 1000) - (60 * 200)))
            {
                long delta_t = (System.currentTimeMillis() / 1000) - timestamp;
                // HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:delta t=" + (-delta_t) + " do NOT sync files that are too old");
                return;
            }

            //
            //
            //
            ByteBuffer hash_msg_id_bytes = ByteBuffer.allocateDirect(32);
            hash_msg_id_bytes.put(data, 8, 32);
            final String message_id_hash = HelperGeneric.bytesToHex(hash_msg_id_bytes.array(),hash_msg_id_bytes.arrayOffset(),hash_msg_id_bytes.limit()).toUpperCase();
            // HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:message_id_hash hex=" + message_id_hash);
            //
            //
            try
            {
                ByteBuffer name_buffer = ByteBuffer.allocateDirect(TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                name_buffer.put(data, 8 + 32 + 32 + 4, TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES);
                String peer_name = utf8_string_from_bytes_with_padding(name_buffer,
                                                                             TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES,
                                                                             "peer");
                HelperGeneric.logI(TAG,"handle_incoming_sync_group_file:peer_name str=" + peer_name);
                //
                //
                //
                ByteBuffer filename_buffer = ByteBuffer.allocateDirect(TOX_NGC_HISTORY_SYNC_MAX_FILENAME_BYTES);
                filename_buffer.put(data, 6 + 1 + 1 + 32 + 32 + 4 + 25, TOX_NGC_HISTORY_SYNC_MAX_FILENAME_BYTES);
                final String filename = utf8_string_from_bytes_with_padding(filename_buffer,
                                                                            TOX_NGC_HISTORY_SYNC_MAX_FILENAME_BYTES,
                                                                            "image.jpg");
                HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:filename=" + filename);
                //
                //
                //
                final int header = 6+1+1+32+32+4+25+255;
                long filedata_size = length - header;
                if ((filedata_size < 1) || (filedata_size > 37000))
                {
                    HelperGeneric.logI(TAG, "handle_incoming_sync_group_file: file size less than 1 byte or larger than 37000 bytes");
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
                            // HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:potential double file, message_id_hash:" + message_id_hash);
                            return;
                        }
                    }
                }
                catch(Exception e)
                {
                }

                // KHANDAQ (audit F-6): same as for synced text — a locally known name beats the packet one.
                // Resolved only after the duplicate check, so re-synced files cost no extra name lookup.
                peer_name = resolve_synced_peer_name(group_identifier, original_sender_peerpubkey, sender_peer_num,
                                                     peer_name);

                group_file_add_from_sync(group_identifier, syncer_pubkey, sender_peer_num, original_sender_peerpubkey,
                                            file_byte_buf, filename, peer_name,
                                            (timestamp * 1000), message_id_hash,
                                            TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value);
            }
            catch(Exception e)
            {
                e.printStackTrace();
                HelperGeneric.logI(TAG,"handle_incoming_sync_group_file:EE002:" + e.getMessage());
            }
        }
        catch(Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "handle_incoming_sync_group_file:EE001:" + e.getMessage());
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
                display_toast(context_s.getString(R.string.group_incoming_file_error), true, 0);
                return;
            }

            if (group_temp.group_identifier.toLowerCase().compareTo(group_id.toLowerCase()) != 0)
            {
                display_toast(context_s.getString(R.string.group_incoming_file_error), true, 0);
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
                if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_id))
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
                if (group_message_list_activity.get_current_group_id().equalsIgnoreCase(group_id.toLowerCase()))
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
                HelperGeneric.logI(TAG, "group_file_add_from_sync:new_msg_id=" + new_msg_id);
            }

            HelperFriend.refresh_chat_list_group_row_wrapper(group_id);
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
        // HelperGeneric.logI(TAG, "ngc_update_video_incoming_peer_list entries=" + lookup_ngc_incoming_video_peer_list.size());
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
                    HelperGeneric.logI(TAG, "ngc_get_index_video_incoming_peer_list:peer_pubkey=" + peer_pubkey.substring(0, 6));
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
                HelperGeneric.logI(TAG, "ngc_get_index_video_incoming_peer_list:peer_pubkey=" + peer_pubkey);
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
                HelperGeneric.logI(TAG, "ngc_get_index_video_incoming_peer_list:peer_pubkey=" + peer_pubkey);
                return peer_pubkey;
            }
            catch(Exception e)
            {
                return "-1";
            }
        }
    }
}
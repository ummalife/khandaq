/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2020 - 2025 Zoff <zoff@zoff.cc>
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
import android.util.Log;

import com.zoffcc.applications.sorm.FileDB;
import com.zoffcc.applications.sorm.FriendList;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import okhttp3.CacheControl;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FRIEND;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperMessage.get_message_in_db_sent_push_is_read;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_sent_push_set;
import static com.zoffcc.applications.trifa.HelperRelay.get_pushurl_for_friend;
import static com.zoffcc.applications.trifa.HelperRelay.is_valid_pushurl_for_friend_with_whitelist;
import static com.zoffcc.applications.trifa.HelperRelay.own_push_token_load;
import static com.zoffcc.applications.trifa.HelperRelay.push_token_to_push_url;
import static com.zoffcc.applications.trifa.MainActivity.PREF__orbot_enabled;
import static com.zoffcc.applications.trifa.MainActivity.PREF__use_push_service;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.HelperGeneric.del_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.get_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.set_g_opts;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.tox_friend_delete;
import static com.zoffcc.applications.trifa.MainActivity.tox_friend_send_lossless_packet;
import static com.zoffcc.applications.trifa.TRIFAGlobals.ECHOBOT_LEGACY_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONTROL_PROXY_MESSAGE_TYPE.CONTROL_PROXY_MESSAGE_TYPE_PUSH_URL_FOR_FRIEND;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GENERIC_TOR_USERAGENT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GENERIC_UNIFIED_WEBPUSH_CONTENT_ENCODING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GENERIC_UNIFIED_WEBPUSH_TTL_SECONDS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.ORBOT_PROXY_HOST;
import static com.zoffcc.applications.trifa.TRIFAGlobals.ORBOT_PROXY_PORT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PUSH_URL_TRIGGER_AGAIN_MAX_COUNT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PUSH_URL_TRIGGER_AGAIN_SECONDS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_INCOMING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UINT32_MAX_JAVA;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_toxid;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ERR_FRIEND_ADD;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class HelperFriend
{
    private static final String TAG = "trifa.Hlp.Friend";

    static HashMap<String, Long> ping_push_blocker_cache = new HashMap<>();
    final static long TWO_HOURS_IN_MILLIS = (2 * 3600 * 1000); // 2 hours in millis

    static FriendList main_get_friend(long friendnum)
    {
        FriendList f = null;

        if (friendnum < 0)
        {
            return null;
        }

        try
        {
            String pubkey_temp = tox_friend_get_public_key__wrapper(friendnum);
            // Log.i(TAG, "main_get_friend:pubkey=" + pubkey_temp + " fnum=" + friendnum);
            List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().
                    tox_public_key_stringEq(tox_friend_get_public_key__wrapper(friendnum)).
                    toList();

            // Log.i(TAG, "main_get_friend:fl=" + fl + " size=" + fl.size());

            if (fl.size() > 0)
            {
                f = (FriendList) fl.get(0);
                // Log.i(TAG, "main_get_friend:f=" + f);
            }
            else
            {
                f = null;
            }
        }
        catch (Exception e)
        {
            f = null;
        }

        return f;
    }

    static FriendList main_get_friend(String friend_pubkey)
    {
        FriendList f = null;

        try
        {
            List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().
                    tox_public_key_stringEq(friend_pubkey).
                    toList();

            if (fl.size() > 0)
            {
                f = (FriendList) fl.get(0);
            }
            else
            {
                f = null;
            }
        }
        catch (Exception e)
        {
            f = null;
        }

        return f;
    }

    static int is_friend_online(long friendnum)
    {
        try
        {
            return (orma.selectFromFriendList().
                    tox_public_key_stringEq(tox_friend_get_public_key__wrapper(friendnum)).
                    toList().get(0).TOX_CONNECTION);
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            return 0;
        }
    }

    static int is_friend_online_real_and_has_msgv3(long friendnum)
    {
        try
        {
            final FriendList f = (FriendList) orma.selectFromFriendList().
                    tox_public_key_stringEq(tox_friend_get_public_key__wrapper(friendnum)).
                    toList().get(0);
            if ((f.TOX_CONNECTION_real != 0) && (f.msgv3_capability == 1))
            {
                return 1;
            }
            else
            {
                return 0;
            }
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            return 0;
        }
    }

    static int is_friend_online_real_and_hasnot_msgv3(long friendnum)
    {
        try
        {
            final FriendList f = (FriendList) orma.selectFromFriendList().
                    tox_public_key_stringEq(tox_friend_get_public_key__wrapper(friendnum)).
                    toList().get(0);
            if ((f.TOX_CONNECTION_real != 0) && (f.msgv3_capability != 1))
            {
                return 1;
            }
            else
            {
                return 0;
            }
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            return 0;
        }
    }

    static int is_friend_online_real(long friendnum)
    {
        try
        {
            return (orma.selectFromFriendList().
                    tox_public_key_stringEq(tox_friend_get_public_key__wrapper(friendnum)).
                    toList().get(0).TOX_CONNECTION_real);
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            return 0;
        }
    }

    synchronized static void set_all_friends_offline()
    {
        Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    orma.updateFriendList().
                            TOX_CONNECTION(0).
                            execute();
                }
                catch (Exception e)
                {
                }

                try
                {
                    orma.updateFriendList().
                            TOX_CONNECTION_real(0).
                            execute();
                }
                catch (Exception e)
                {
                }

                try
                {
                    orma.updateFriendList().
                            TOX_CONNECTION_on_off(0).
                            execute();
                }
                catch (Exception e)
                {
                }

                try
                {
                    orma.updateFriendList().
                            TOX_CONNECTION_on_off_real(0).
                            execute();
                }
                catch (Exception e)
                {
                }

                try
                {
                    orma.updateFriendList().
                            last_online_timestampEq(LAST_ONLINE_TIMSTAMP_ONLINE_NOW).
                            last_online_timestamp(System.currentTimeMillis()).
                            execute();
                }
                catch (Exception e)
                {
                }

                try
                {
                    orma.updateFriendList().
                            last_online_timestamp_realEq(LAST_ONLINE_TIMSTAMP_ONLINE_NOW).
                            last_online_timestamp_real(System.currentTimeMillis()).
                            execute();
                }
                catch (Exception e)
                {
                }

                // ------ DEBUG ------
                // ------ set all friends to "never" seen online ------
                // ------ DEBUG ------
                // try
                // {
                //     orma.updateFriendList().
                //             last_online_timestamp(LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE).
                //             execute();
                // }
                // catch (Exception e)
                // {
                // }
                // ------ DEBUG ------
                // ------ set all friends to "never" seen online ------
                // ------ DEBUG ------

                try
                {
                    MainActivity.friend_list_fragment.set_all_friends_to_offline();
                }
                catch (Exception e)
                {
                }
            }
        };
        t.start();
    }

    synchronized static void update_friend_in_db(FriendList f)
    {
        orma.updateFriendList().
                tox_public_key_string(f.tox_public_key_string).
                name(f.name).
                status_message(f.status_message).
                TOX_CONNECTION(f.TOX_CONNECTION).
                TOX_CONNECTION_on_off(f.TOX_CONNECTION_on_off).
                TOX_USER_STATUS(f.TOX_USER_STATUS).
                execute();
    }

    synchronized static void update_friend_in_db_status_message(FriendList f)
    {
        orma.updateFriendList().
                tox_public_key_stringEq(f.tox_public_key_string).
                status_message(f.status_message).
                execute();
    }

    synchronized static void update_friend_in_db_status(FriendList f)
    {
        // Log.i(TAG, "update_friend_in_db_status:f=" + f);
        orma.updateFriendList().
                tox_public_key_stringEq(f.tox_public_key_string).
                TOX_USER_STATUS(f.TOX_USER_STATUS).
                execute();
        // Log.i(TAG, "update_friend_in_db_status:numrows=" + numrows);
    }

    synchronized static void update_friend_in_db_capabilities(FriendList f)
    {
        orma.updateFriendList().
                tox_public_key_stringEq(f.tox_public_key_string).
                capabilities(f.capabilities).
                execute();
    }

    synchronized static void update_friend_in_db_connection_status(FriendList f)
    {
        try
        {
            orma.updateFriendList().
                    tox_public_key_stringEq(f.tox_public_key_string).
                    TOX_CONNECTION(f.TOX_CONNECTION).
                    TOX_CONNECTION_on_off(f.TOX_CONNECTION_on_off).
                    execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void update_friend_in_db_ip_addr_str(FriendList f)
    {
        try
        {
            orma.updateFriendList().
                    tox_public_key_stringEq(f.tox_public_key_string).
                    ip_addr_str(f.ip_addr_str).
                    execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    synchronized static void update_friend_in_db_connection_status_real(FriendList f)
    {
        try
        {
            orma.updateFriendList().
                    tox_public_key_stringEq(f.tox_public_key_string).
                    TOX_CONNECTION_real(f.TOX_CONNECTION_real).
                    TOX_CONNECTION_on_off_real(f.TOX_CONNECTION_on_off_real).
                    execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    synchronized static void update_friend_in_db_last_online_timestamp(FriendList f)
    {
        // Log.i(TAG, "update_friend_in_db_last_online_timestamp");
        try
        {
            orma.updateFriendList().
                    tox_public_key_stringEq(f.tox_public_key_string).
                    last_online_timestamp(f.last_online_timestamp).
                    execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    synchronized static void update_friend_in_db_last_online_timestamp_real(FriendList f)
    {
        try
        {
            orma.updateFriendList().
                    tox_public_key_stringEq(f.tox_public_key_string).
                    last_online_timestamp_real(f.last_online_timestamp_real).
                    execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    synchronized static void update_friend_in_db_name(FriendList f)
    {
        orma.updateFriendList().
                tox_public_key_stringEq(f.tox_public_key_string).
                name(f.name).
                execute();
    }

    static boolean peer_supports_msgv2(@NonNull FriendList f)
    {
        return (f.capabilities & ToxVars.TOX_CAPABILITY_MSGV2) != 0;
    }

    static boolean peer_supports_msgv3(@NonNull FriendList f)
    {
        return f.msgv3_capability == 1 || (f.capabilities & ToxVars.TOX_CAPABILITY_MSGV3) != 0;
    }

    static long get_friend_msgv3_capability(@NonNull String friend_public_key_string)
    {
        long ret = 0;
        try
        {
            FriendList f = (FriendList) orma.selectFromFriendList().
                    tox_public_key_stringEq(friend_public_key_string).
                    get(0);
            if (f != null)
            {
                ret = f.msgv3_capability;
            }

            return ret;
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    static long get_friend_msgv3_capability(long friend_number)
    {
        long ret = 0;
        try
        {
            FriendList f = (FriendList) orma.selectFromFriendList().
                    tox_public_key_stringEq(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).
                    get(0);
            if (f != null)
            {
                // Log.i(TAG, "get_friend_msgv3_capability:f=" +
                //           get_friend_name_from_pubkey(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)) +
                //           " f=" + f.msgv3_capability);
                ret = f.msgv3_capability;
            }

            return ret;
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    static void update_friend_msgv3_capability(long friend_number, int new_value)
    {
        try
        {
            if ((new_value == 0) || (new_value == 1))
            {
                FriendList f = (FriendList) orma.selectFromFriendList().
                        tox_public_key_stringEq(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).
                        get(0);
                if (f != null)
                {
                    if (f.msgv3_capability != new_value)
                    {
                        Log.i(TAG,
                              "update_friend_msgv3_capability f=" + get_friend_name_from_num(friend_number) + " new=" +
                              new_value + " old=" + f.msgv3_capability);
                        orma.updateFriendList().
                                tox_public_key_stringEq(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).
                                msgv3_capability(new_value).
                                execute();
                    }
                }
            }
        }
        catch (Exception e)
        {
        }
    }

    public static long tox_friend_by_public_key__wrapper(@NonNull String friend_public_key_string)
    {
        if (MainActivity.cache_pubkey_fnum.containsKey(friend_public_key_string))
        {
            // Log.i(TAG, "cache hit:1");
            return MainActivity.cache_pubkey_fnum.get(friend_public_key_string);
        }
        else
        {
            if (MainActivity.cache_pubkey_fnum.size() >= 180)
            {
                // TODO: bad!
                MainActivity.cache_pubkey_fnum.clear();
            }

            long result = MainActivity.tox_friend_by_public_key(friend_public_key_string);
            MainActivity.cache_pubkey_fnum.put(friend_public_key_string, result);
            return result;
        }
    }

    public static String tox_friend_get_public_key__wrapper(long friend_number)
    {
        if (friend_number < 0)
        {
            return null;
        }

        if (MainActivity.cache_fnum_pubkey.containsKey(friend_number))
        {
            // Log.i(TAG, "cache hit:2");
            return MainActivity.cache_fnum_pubkey.get(friend_number);
        }
        else
        {
            if (MainActivity.cache_fnum_pubkey.size() >= 180)
            {
                // TODO: bad!
                MainActivity.cache_fnum_pubkey.clear();
            }

            String result = MainActivity.tox_friend_get_public_key(friend_number);
            MainActivity.cache_fnum_pubkey.put(friend_number, result);
            return result;
        }
    }

    static void del_friend_avatar(String friend_pubkey, String avatar_path_name, String avatar_file_name)
    {
        try
        {
            boolean avatar_filesize_non_zero = false;
            info.guardianproject.iocipher.File f1 = null;

            try
            {
                f1 = new info.guardianproject.iocipher.File(avatar_path_name + "/" + avatar_file_name);

                if (f1.length() > 0)
                {
                    avatar_filesize_non_zero = true;
                }

                f1.delete();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            orma.updateFriendList().tox_public_key_stringEq(friend_pubkey).
                    avatar_pathname(null).
                    avatar_filename(null).
                    avatar_ftid_hex(null).
                    avatar_update(false).
                    avatar_update_timestamp(System.currentTimeMillis()).
                    execute();

            HelperGeneric.update_display_friend_avatar(friend_pubkey, avatar_path_name, avatar_file_name);
        }
        catch (Exception e)
        {
            Log.i(TAG, "set_friend_avatar:EE:" + e.getMessage());
            e.printStackTrace();
        }
    }

    static void set_friend_avatar(String friend_pubkey, String avatar_path_name, String avatar_file_name, String avatar_ftid_hex)
    {
        try
        {
            boolean avatar_filesize_non_zero = false;
            info.guardianproject.iocipher.File f1 = null;

            String avatar_ftid_hex_wrap = avatar_ftid_hex;
            if (avatar_ftid_hex_wrap!= null)
            {
                avatar_ftid_hex_wrap = avatar_ftid_hex_wrap.toUpperCase();
            }

            try
            {
                f1 = new info.guardianproject.iocipher.File(avatar_path_name + "/" + avatar_file_name);

                if (f1.length() > 0)
                {
                    avatar_filesize_non_zero = true;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "set_friend_avatar:EE01:" + e.getMessage());
            }

            // Log.i(TAG, "set_friend_avatar:update:pubkey=" + friend_pubkey.substring(0,4) + " path=" + avatar_path_name + " file=" +
            // avatar_file_name);

            if (avatar_filesize_non_zero)
            {
                orma.updateFriendList().tox_public_key_stringEq(friend_pubkey).
                        avatar_pathname(avatar_path_name).
                        avatar_filename(avatar_file_name).
                        avatar_ftid_hex(avatar_ftid_hex_wrap).
                        avatar_update(false).
                        avatar_update_timestamp(System.currentTimeMillis()).
                        execute();
            }
            else
            {
                orma.updateFriendList().tox_public_key_stringEq(friend_pubkey).
                        avatar_pathname(null).
                        avatar_filename(null).
                        avatar_ftid_hex(null).
                        avatar_update(false).
                        avatar_update_timestamp(System.currentTimeMillis()).
                        execute();
            }

            HelperGeneric.update_display_friend_avatar(friend_pubkey, avatar_path_name, avatar_file_name);
        }
        catch (Exception e)
        {
            Log.i(TAG, "set_friend_avatar:EE02:" + e.getMessage());
            e.printStackTrace();
        }
    }

    static void set_friend_avatar_update(String friend_pubkey, boolean avatar_update_value)
    {
        try
        {
            orma.updateFriendList().tox_public_key_stringEq(friend_pubkey).
                    avatar_update(avatar_update_value).
                    avatar_update_timestamp(System.currentTimeMillis()).
                    execute();
        }
        catch (Exception e)
        {
            Log.i(TAG, "set_friend_avatar_update:EE:" + e.getMessage());
            e.printStackTrace();
        }
    }

    static String get_friend_avatar_saved_hash_hex(String friend_pubkey)
    {
        String ret = null;
        try
        {
            ret = orma.selectFromFriendList().tox_public_key_stringEq(friend_pubkey).
                    get(0).avatar_ftid_hex.toUpperCase();
        }
        catch (Exception e)
        {
        }
        return ret;
    }

    static void add_friend_to_system(final String friend_public_key, final boolean as_friends_relay, final String owner_public_key)
    {
        Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    // toxcore needs this!!
                    Thread.sleep(10);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                // ---- auto add all friends ----
                // ---- auto add all friends ----
                // ---- auto add all friends ----
                long friendnum = MainActivity.tox_friend_add_norequest(friend_public_key); // add friend
                Log.d(TAG, "add_friend_to_system:fnum add=" + friendnum);

                if (friendnum == UINT32_MAX_JAVA) // 0xffffffff == UINT32_MAX
                {
                    // Log.d(TAG, "add_friend_to_system:fnum add res=0xffffffff as_friends_relay=" + as_friends_relay);
                    // adding friend failed
                    // if its a relay still try to update it in our DB
                    if (as_friends_relay)
                    {
                        // add relay for friend to DB
                        // Log.d(TAG, "add_friend_to_system:add_or_update_friend_relay");
                        HelperRelay.add_or_update_friend_relay(friend_public_key, owner_public_key);
                        add_all_friends_clear_wrapper(10);
                    }

                    return;
                }

                try
                {
                    Thread.sleep(20);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                HelperGeneric.update_savedata_file_wrapper(); // save toxcore datafile (new friend added)
                final FriendList f = new FriendList();
                f.tox_public_key_string = friend_public_key;
                f.TOX_USER_STATUS = 0;
                f.TOX_CONNECTION = 0;
                f.TOX_CONNECTION_on_off = HelperGeneric.get_toxconnection_wrapper(f.TOX_CONNECTION);
                f.name = initial_friend_display_name(friend_public_key, friendnum);
                f.avatar_pathname = null;
                f.avatar_filename = null;
                f.capabilities = 0;

                try
                {
                    // Log.i(TAG, "friend_request:insert:001:f=" + f);
                    f.added_timestamp = System.currentTimeMillis();
                    long res = orma.insertIntoFriendList(f);
                    Log.i(TAG, "friend_request:insert:002:res=" + res);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    Log.i(TAG, "friend_request:insert:EE2:" + e.getMessage());
                    return;
                }

                if (as_friends_relay)
                {
                    // add relay for friend to DB
                    // Log.d(TAG, "add_friend_to_system:add_or_update_friend_relay");
                    HelperRelay.add_or_update_friend_relay(friend_public_key, owner_public_key);
                    // update friendlist on screen
                    add_all_friends_clear_wrapper(10);
                }
                else
                {
                    update_single_friend_in_friendlist_view(f);
                }

                // ---- auto add all friends ----
                // ---- auto add all friends ----
                // ---- auto add all friends ----

                try
                {
                    Thread.sleep(100);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                if (MainActivity.PREF__U_keep_nospam == false)
                {
                    // ---- set new random nospam value after each added friend ----
                    // ---- set new random nospam value after each added friend ----
                    // ---- set new random nospam value after each added friend ----
                    HelperGeneric.set_new_random_nospam_value();
                    // ---- set new random nospam value after each added friend ----
                    // ---- set new random nospam value after each added friend ----
                    // ---- set new random nospam value after each added friend ----
                }
            }
        };
        t.start();
    }

    static void add_pushurl_for_friend(final String friend_push_url, final String friend_pubkey)
    {
        try
        {
            orma.updateFriendList().tox_public_key_stringEq(friend_pubkey).push_url(friend_push_url).execute();
        }
        catch (Exception e)
        {
            Log.i(TAG, "add_pushurl_for_friend:EE:" + e.getMessage());
        }
    }

    static void remove_pushurl_for_friend(final String friend_pubkey)
    {
        try
        {
            orma.updateFriendList().tox_public_key_stringEq(friend_pubkey).push_url(null).execute();
        }
        catch (Exception e)
        {
            Log.i(TAG, "remove_pushurl_for_friend:EE:" + e.getMessage());
        }
    }

    synchronized static void insert_into_friendlist_db(final FriendList f)
    {
        try
        {
            if (orma.selectFromFriendList().tox_public_key_stringEq(f.tox_public_key_string).count() == 0)
            {
                f.added_timestamp = System.currentTimeMillis();
                f.push_url = null;
                orma.insertIntoFriendList(f);
                // Log.i(TAG, "friend added to DB: " + f.tox_public_key_string);
            }
            else
            {
                // friend already in DB
                // Log.i(TAG, "friend already in DB: " + f.tox_public_key_string);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "friend added to DB:EE:" + e.getMessage());
        }
    }

    static void delete_friend_all_files(final String friend_pubkey)
    {
        try
        {
            Iterator<FileDB> i1 = orma.selectFromFileDB().tox_public_key_stringEq(friend_pubkey).
                    directionEq(TRIFA_FT_DIRECTION_INCOMING.value).
                    is_in_VFSEq(true).
                    toList().iterator();
            MainActivity.selected_messages.clear();
            MainActivity.selected_messages_text_only.clear();
            MainActivity.selected_messages_incoming_file.clear();

            while (i1.hasNext())
            {
                try
                {
                    long file_id = i1.next().id;
                    long msg_id = orma.selectFromMessage().filedb_idEq(file_id).directionEq(0).
                            tox_friendpubkeyEq(friend_pubkey).get(0).id;
                    MainActivity.selected_messages.add(msg_id);
                    MainActivity.selected_messages_incoming_file.add(msg_id);
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                }
            }

            HelperMessage.delete_selected_messages(MainActivity.main_activity_s, false, false, "deleting Messages ...");
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            orma.deleteFromFileDB().tox_public_key_stringEq(friend_pubkey).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void delete_friend_all_filetransfers(final String friend_pubkey)
    {
        try
        {
            Log.i(TAG, "delete_ft:ALL for friend=" + friend_pubkey);
            orma.deleteFromFiletransfer().tox_public_key_stringEq(friend_pubkey).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void delete_friend_all_messages(final String friend_pubkey)
    {
        Thread t = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    orma.deleteFromMessage().tox_friendpubkeyEq(friend_pubkey).execute();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };
        t.start();
    }

    static void delete_friend(final String friend_pubkey)
    {
        //Thread t = new Thread()
        //{
        //    @Override
        //    public void run()
        //    {
                try
                {
                    orma.deleteFromFriendList().
                        tox_public_key_stringEq(friend_pubkey).
                        execute();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
        //    }
        //};
        //t.start();
    }

    private static final String ECHOBOT_LEGACY_REMOVED_DB_KEY = "ECHOBOT_LEGACY_REMOVED_v1";

    static void remove_legacy_echobot_contact_if_present()
    {
        try
        {
            if ("true".equals(get_g_opts(ECHOBOT_LEGACY_REMOVED_DB_KEY)))
            {
                return;
            }

            List<FriendList> existing = orma.selectFromFriendList().tox_public_key_stringEq(ECHOBOT_LEGACY_PUBKEY).toList();
            if ((existing == null) || existing.isEmpty())
            {
                set_g_opts(ECHOBOT_LEGACY_REMOVED_DB_KEY, "true");
                del_g_opts("ADD_BOTS_ON_STARTUP_done");
                return;
            }

            Log.i(TAG, "remove_legacy_echobot_contact_if_present:start");

            delete_friend_all_files(ECHOBOT_LEGACY_PUBKEY);
            delete_friend_all_filetransfers(ECHOBOT_LEGACY_PUBKEY);
            delete_friend_all_messages(ECHOBOT_LEGACY_PUBKEY);
            delete_friend(ECHOBOT_LEGACY_PUBKEY);

            final long friend_num = tox_friend_by_public_key__wrapper(ECHOBOT_LEGACY_PUBKEY);
            if (friend_num > -1)
            {
                tox_friend_delete(friend_num);
                HelperGeneric.update_savedata_file_wrapper();
            }

            set_g_opts(ECHOBOT_LEGACY_REMOVED_DB_KEY, "true");
            del_g_opts("ADD_BOTS_ON_STARTUP_done");
            add_all_friends_clear_wrapper(0);

            Log.i(TAG, "remove_legacy_echobot_contact_if_present:done");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "remove_legacy_echobot_contact_if_present:EE:" + e.getMessage());
        }
    }

    static void add_friend_real_norequest(String friend_tox_id)
    {
        // Log.i(TAG, "add_friend_real:add friend ID:" + friend_tox_id);
        // add friend ---------------
        if (friend_tox_id == null)
        {
            Log.i(TAG, "add_friend_real_norequest:add friend ID = NULL");
            return;
        }

        friend_tox_id = HelperGeneric.normalize_tox_address(friend_tox_id);
        if (friend_tox_id == null)
        {
            Log.i(TAG, "add_friend_real_norequest:invalid friend ID");
            return;
        }

        final String friend_public_key = friend_tox_id.substring(0, (TOX_PUBLIC_KEY_SIZE * 2)).toUpperCase(Locale.ROOT);

        Log.i(TAG, "add_friend_real_norequest:add friend ID len:" + friend_tox_id.length());
        long friendnum = MainActivity.tox_friend_add_norequest(friend_public_key); // add friend
        Log.i(TAG, "add_friend_real_norequest:add friend  #:" + friendnum);
        HelperGeneric.update_savedata_file_wrapper(); // save toxcore datafile (new friend added)

        if (friendnum == UINT32_MAX_JAVA)
        {
            display_toast(context_s.getString(R.string.add_friend_failed), false, 300);
        }
        else if (friendnum > -1)
        {
            // Log.i(TAG, "add_friend_real_norequest:add friend PK:" + friend_public_key);
            FriendList f = new FriendList();
            f.tox_public_key_string = friend_public_key;

            f.name = initial_friend_display_name(friend_public_key, friendnum);

            f.TOX_USER_STATUS = 0;
            f.TOX_CONNECTION = 0;
            f.TOX_CONNECTION_on_off = HelperGeneric.get_toxconnection_wrapper(f.TOX_CONNECTION);
            f.avatar_filename = null;
            f.avatar_pathname = null;

            display_toast(context_s.getString(R.string.add_friend_success), false, 300);

            try
            {
                insert_into_friendlist_db(f);
            }
            catch (Exception e)
            {
                // e.printStackTrace();
            }

            update_single_friend_in_friendlist_view(f);
            refresh_friend_name_after_add(friendnum, f);
        }
        else
        {
            display_toast(context_s.getString(R.string.add_friend_failed), false, 300);
        }

        if (friendnum == -1)
        {
            Log.i(TAG, "add_friend_real_norequest:friend already added, or request already sent");

            /*
            // still add the friend to the DB
            String friend_public_key = friend_tox_id.substring(0, friend_tox_id.length() - 12);
            add_friend_to_system(friend_public_key, false, null);
            */
        }
        else if (friendnum == UINT32_MAX_JAVA)
        {
            Log.i(TAG, "add_friend_real_norequest:some other error occured");
        }
        else if (friendnum < -1)
        {
            Log.i(TAG, "add_friend_real_norequest:some error occured");
        }

        // add friend ---------------
    }

    static boolean is_own_public_key(final String friend_pubkey)
    {
        if ((friend_pubkey == null) || (friend_pubkey.trim().isEmpty()) || (global_my_toxid == null) ||
            (global_my_toxid.length() < (TOX_PUBLIC_KEY_SIZE * 2)))
        {
            return false;
        }

        final String own_pubkey = global_my_toxid.substring(0, (TOX_PUBLIC_KEY_SIZE * 2));
        return friend_pubkey.trim().equalsIgnoreCase(own_pubkey);
    }

    static String self_contact_display_name()
    {
        if ((global_my_name != null) && (!global_my_name.trim().isEmpty()))
        {
            return global_my_name.trim() + " (" + context_s.getString(R.string.add_self_contact_suffix) + ")";
        }

        return context_s.getString(R.string.add_self_contact_name);
    }

    static boolean add_self_as_friend()
    {
        final String my_toxid = MainActivity.get_my_toxid();
        if ((my_toxid == null) || (my_toxid.length() < ((TOX_PUBLIC_KEY_SIZE * 2) + 12)))
        {
            display_toast(context_s.getString(R.string.add_friend_failed), false, 300);
            return false;
        }

        final String my_pubkey = my_toxid.substring(0, (TOX_PUBLIC_KEY_SIZE * 2)).toUpperCase(Locale.ROOT);

        if (lookup_friendlist_by_pubkey(my_pubkey) != null)
        {
            display_toast(context_s.getString(R.string.add_self_already_in_contacts), false, 300);
            return true;
        }

        long friendnum = MainActivity.tox_friend_add_norequest(my_pubkey);
        if ((friendnum != UINT32_MAX_JAVA) && (friendnum >= 0))
        {
            HelperGeneric.update_savedata_file_wrapper();
            final FriendList f = new FriendList();
            f.tox_public_key_string = my_pubkey;
            f.name = self_contact_display_name();
            f.TOX_USER_STATUS = 0;
            f.TOX_CONNECTION = 0;
            f.TOX_CONNECTION_on_off = HelperGeneric.get_toxconnection_wrapper(f.TOX_CONNECTION);
            f.avatar_filename = null;
            f.avatar_pathname = null;
            f.capabilities = 0;
            f.added_timestamp = System.currentTimeMillis();

            try
            {
                insert_into_friendlist_db(f);
            }
            catch (Exception e)
            {
                display_toast(context_s.getString(R.string.add_friend_failed), false, 300);
                return false;
            }

            update_single_friend_in_friendlist_view(f);
            display_toast(context_s.getString(R.string.add_self_success), false, 300);
            return true;
        }

        final FriendList f = new FriendList();
        f.tox_public_key_string = my_pubkey;
        f.name = self_contact_display_name();
        f.TOX_USER_STATUS = 0;
        f.TOX_CONNECTION = 0;
        f.TOX_CONNECTION_on_off = HelperGeneric.get_toxconnection_wrapper(f.TOX_CONNECTION);
        f.avatar_filename = null;
        f.avatar_pathname = null;
        f.capabilities = 0;
        f.added_timestamp = System.currentTimeMillis();

        try
        {
            insert_into_friendlist_db(f);
        }
        catch (Exception e)
        {
            display_toast(context_s.getString(R.string.add_friend_failed), false, 300);
            return false;
        }

        update_single_friend_in_friendlist_view(f);
        display_toast(context_s.getString(R.string.add_self_success), false, 300);
        return true;
    }

    static boolean is_tox_profile_ready()
    {
        final String my_toxid = MainActivity.get_my_toxid();
        return (my_toxid != null) && (my_toxid.length() >= ((TOX_PUBLIC_KEY_SIZE * 2) + 12));
    }

    static FriendList build_friendlist_entry(final String friend_public_key, final long friendnum)
    {
        final FriendList f = new FriendList();
        f.tox_public_key_string = friend_public_key;
        f.name = initial_friend_display_name(friend_public_key, friendnum);
        f.TOX_USER_STATUS = 0;
        f.TOX_CONNECTION = 0;
        f.TOX_CONNECTION_on_off = HelperGeneric.get_toxconnection_wrapper(f.TOX_CONNECTION);
        f.avatar_filename = null;
        f.avatar_pathname = null;
        f.added_timestamp = System.currentTimeMillis();
        return f;
    }

    static boolean persist_new_friend_contact(final String friend_public_key, final long friendnum)
    {
        final FriendList f = build_friendlist_entry(friend_public_key, friendnum);

        try
        {
            insert_into_friendlist_db(f);
        }
        catch (Exception e)
        {
            Log.i(TAG, "persist_new_friend_contact:db:EE:" + e.getMessage());
            return false;
        }

        update_single_friend_in_friendlist_view(f);
        refresh_friend_name_after_add(friendnum, f);
        return true;
    }

    static boolean sync_tox_friend_to_contacts(final String friend_public_key)
    {
        if (lookup_friendlist_by_pubkey(friend_public_key) != null)
        {
            return true;
        }

        final long friendnum = ensure_friend_in_tox_core(friend_public_key);
        if ((friendnum == UINT32_MAX_JAVA) || (friendnum < 0))
        {
            return false;
        }

        return persist_new_friend_contact(friend_public_key, friendnum);
    }

    static void invalidate_friendnum_cache_for_pubkey(final String friend_public_key)
    {
        if (friend_public_key == null)
        {
            return;
        }

        final String trimmed = friend_public_key.trim();
        MainActivity.cache_pubkey_fnum.remove(trimmed);
        MainActivity.cache_pubkey_fnum.remove(trimmed.toUpperCase(Locale.ROOT));
        MainActivity.cache_pubkey_fnum.remove(trimmed.toLowerCase(Locale.ROOT));
    }

    /** Re-resolve friendnum from toxcore; re-add DB contacts missing from tox savedata. */
    static long ensure_friend_in_tox_core(final String friend_public_key)
    {
        if ((friend_public_key == null) ||
            (friend_public_key.trim().length() != (TOX_PUBLIC_KEY_SIZE * 2)))
        {
            return -1;
        }

        if (!is_tox_profile_ready())
        {
            return -1;
        }

        final String pk = friend_public_key.trim().toUpperCase(Locale.ROOT);
        long fn = tox_friend_by_public_key__wrapper(pk);
        if (fn >= 0)
        {
            return fn;
        }

        invalidate_friendnum_cache_for_pubkey(pk);
        fn = MainActivity.tox_friend_by_public_key(pk);
        if (fn >= 0)
        {
            MainActivity.cache_pubkey_fnum.put(pk, fn);
            return fn;
        }

        if ((lookup_friendlist_by_pubkey(pk) == null) && !has_dm_history_for_pubkey(pk))
        {
            return -1;
        }

        fn = MainActivity.tox_friend_add_norequest(pk);
        Log.i(TAG, "ensure_friend_in_tox_core:re-add fn=" + fn + " pk=" + pk.substring(0, 8));
        if ((fn >= 0) && (fn != UINT32_MAX_JAVA))
        {
            invalidate_friendnum_cache_for_pubkey(pk);
            MainActivity.cache_pubkey_fnum.put(pk, fn);
            MainActivity.cache_fnum_pubkey.put(fn, pk);
            HelperGeneric.update_savedata_file_wrapper();
            final FriendList fl = lookup_friendlist_by_pubkey(pk);
            if (fl != null)
            {
                refresh_friend_name_after_add(fn, fl);
            }
            return fn;
        }

        return -1;
    }

    static boolean has_dm_history_for_pubkey(final String friend_public_key)
    {
        if ((orma == null) || (friend_public_key == null) ||
            (friend_public_key.length() != (TOX_PUBLIC_KEY_SIZE * 2)))
        {
            return false;
        }

        try
        {
            return orma.selectFromMessage().tox_friendpubkeyEq(friend_public_key).count() > 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static void ensure_contact_record_for_pubkey(final String friend_public_key, final long friendnum_hint)
    {
        if ((friend_public_key == null) ||
            (friend_public_key.length() != (TOX_PUBLIC_KEY_SIZE * 2)))
        {
            return;
        }

        final String pk = friend_public_key.trim().toUpperCase(Locale.ROOT);
        if (lookup_friendlist_by_pubkey(pk) != null)
        {
            return;
        }

        long fn = friendnum_hint;
        if (fn < 0)
        {
            fn = tox_friend_by_public_key__wrapper(pk);
        }

        if (fn >= 0)
        {
            persist_new_friend_contact(pk, fn);
        }
    }

    static void sync_db_contacts_to_tox_core()
    {
        if (orma == null)
        {
            return;
        }

        try
        {
            final List<FriendList> all = orma.selectFromFriendList().toList();
            if (all == null)
            {
                return;
            }

            for (final FriendList fl : all)
            {
                if ((fl.tox_public_key_string != null) &&
                    (fl.tox_public_key_string.length() == (TOX_PUBLIC_KEY_SIZE * 2)))
                {
                    ensure_friend_in_tox_core(fl.tox_public_key_string);
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "sync_db_contacts_to_tox_core:EE:" + e.getMessage());
        }
    }

    static void repair_corrupt_friend_display_names()
    {
        if (orma == null)
        {
            return;
        }

        try
        {
            final List<FriendList> all = orma.selectFromFriendList().toList();
            if (all == null)
            {
                return;
            }

            for (final FriendList fl : all)
            {
                if ((fl.tox_public_key_string == null) ||
                    (fl.tox_public_key_string.length() != (TOX_PUBLIC_KEY_SIZE * 2)))
                {
                    continue;
                }

                if (!is_placeholder_friend_name(fl.name, fl.tox_public_key_string))
                {
                    continue;
                }

                final long fn = tox_friend_by_public_key__wrapper(fl.tox_public_key_string);
                fl.name = initial_friend_display_name(fl.tox_public_key_string, fn);
                update_friend_in_db_name(fl);
                update_single_friend_in_friendlist_view(fl);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "repair_corrupt_friend_display_names:EE:" + e.getMessage());
        }
    }

    static void show_friend_add_error(final long friendnum)
    {
        if (friendnum >= 0)
        {
            return;
        }

        final int errCode = (int) (-friendnum);
        String message = context_s.getString(R.string.add_friend_failed);

        if (errCode == TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_ALREADY_SENT.ordinal())
        {
            message = context_s.getString(R.string.add_friend_already_in_contacts);
        }
        else if (errCode == TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_BAD_CHECKSUM.ordinal())
        {
            message = context_s.getString(R.string.add_friend_failed_bad_checksum);
        }
        else if (errCode == TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_SET_NEW_NOSPAM.ordinal())
        {
            message = context_s.getString(R.string.add_friend_failed_use_full_id);
        }
        else if (errCode == TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_OWN_KEY.ordinal())
        {
            message = context_s.getString(R.string.add_friend_failed_own_key);
        }
        else if (errCode == TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_NULL.ordinal())
        {
            message = context_s.getString(R.string.add_friend_failed_not_ready);
        }
        else if (errCode == TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_MALLOC.ordinal())
        {
            message = context_s.getString(R.string.add_friend_failed_memory);
        }

        Log.i(TAG, "show_friend_add_error:friendnum=" + friendnum + " errCode=" + errCode);
        display_toast(message, false, 300);
    }

    static void add_friend_real(String friend_tox_id)
    {
        if (friend_tox_id == null)
        {
            Log.i(TAG, "add_friend_real:add friend ID = NULL");
            return;
        }

        if (!is_tox_profile_ready())
        {
            display_toast(context_s.getString(R.string.add_friend_failed_not_ready), false, 300);
            return;
        }

        friend_tox_id = HelperGeneric.normalize_tox_address(friend_tox_id);
        if (friend_tox_id == null)
        {
            Log.i(TAG, "add_friend_real:invalid friend ID");
            display_toast(context_s.getString(R.string.add_friend_failed_invalid_id), false, 300);
            return;
        }

        final String friend_public_key = friend_tox_id.substring(0, (TOX_PUBLIC_KEY_SIZE * 2));

        if (is_own_public_key(friend_public_key))
        {
            add_self_as_friend();
            return;
        }

        if (lookup_friendlist_by_pubkey(friend_public_key) != null)
        {
            display_toast(context_s.getString(R.string.add_friend_already_in_contacts), false, 300);
            return;
        }

        if (sync_tox_friend_to_contacts(friend_public_key))
        {
            display_toast(context_s.getString(R.string.add_friend_already_in_contacts), false, 300);
            return;
        }

        Log.i(TAG, "add_friend_real:add friend ID len:" + friend_tox_id.length());
        long friendnum = MainActivity.tox_friend_add(friend_tox_id, "Hi");
        Log.i(TAG, "add_friend_real:add friend #:" + friendnum);

        if (friendnum >= 0)
        {
            HelperGeneric.update_savedata_file_wrapper();
            if (persist_new_friend_contact(friend_public_key, friendnum))
            {
                display_toast(context_s.getString(R.string.add_friend_success), false, 300);
            }
            else
            {
                display_toast(context_s.getString(R.string.add_friend_failed), false, 300);
            }
            return;
        }

        if ((-friendnum) == TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_ALREADY_SENT.ordinal())
        {
            if (sync_tox_friend_to_contacts(friend_public_key))
            {
                display_toast(context_s.getString(R.string.add_friend_already_in_contacts), false, 300);
            }
            else
            {
                show_friend_add_error(friendnum);
            }
            return;
        }

        show_friend_add_error(friendnum);
    }

    static boolean is_placeholder_friend_name(String name, String friend_pubkey)
    {
        if ((name == null) || (friend_pubkey == null))
        {
            return true;
        }

        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals("Unknown") || trimmed.equals("-1"))
        {
            return true;
        }

        if (trimmed.equalsIgnoreCase("Contact"))
        {
            return true;
        }

        if (trimmed.equalsIgnoreCase("Khandaq"))
        {
            return true;
        }

        if (trimmed.regionMatches(true, 0, "TRIfA", 0, 5))
        {
            return true;
        }

        try
        {
            if (trimmed.equals(friend_display_name_fallback()))
            {
                return true;
            }
        }
        catch (Exception ignored)
        {
        }

        if (friend_pubkey.length() >= 5)
        {
            String suffix = friend_pubkey.substring(friend_pubkey.length() - 5);
            if (trimmed.equalsIgnoreCase(suffix))
            {
                return true;
            }
        }

        return false;
    }

    static String friend_display_name_fallback()
    {
        try
        {
            return context_s.getString(R.string.friend_display_name_fallback);
        }
        catch (Exception e)
        {
            return "Contact";
        }
    }

    static String get_tox_friend_name_live(long friendnum)
    {
        try
        {
            if (friendnum < 0)
            {
                return null;
            }

            String live_name = MainActivity.tox_friend_get_name(friendnum);
            if ((live_name != null) && (live_name.length() > 0) && (!live_name.equals("-1")))
            {
                return live_name.trim();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return null;
    }

    static String initial_friend_display_name(String friend_pubkey, long friendnum)
    {
        String live_name = get_tox_friend_name_live(friendnum);
        if ((live_name != null) && (!is_placeholder_friend_name(live_name, friend_pubkey)))
        {
            return live_name;
        }

        return peer_pubkey_short_id(friend_pubkey);
    }

    static void refresh_friend_name_after_add(long friendnum, FriendList f)
    {
        if ((f == null) || (friendnum < 0))
        {
            return;
        }

        sync_friend_name_from_tox(friendnum, f);
        update_single_friend_in_friendlist_view(f);
    }

    static void sync_friend_name_from_tox(long friendnum, FriendList f)
    {
        if (f == null)
        {
            return;
        }

        String live_name = get_tox_friend_name_live(friendnum);
        if ((live_name == null) || is_placeholder_friend_name(live_name, f.tox_public_key_string))
        {
            return;
        }

        if (live_name.equals(f.name))
        {
            return;
        }

        f.name = live_name;
        update_friend_in_db_name(f);
    }

    static String resolve_friend_display_name(long friendnum)
    {
        if (friendnum < 0)
        {
            return friend_display_name_fallback();
        }

        String friend_pubkey = null;

        try
        {
            friend_pubkey = tox_friend_get_public_key__wrapper(friendnum);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if ((friend_pubkey != null) && (friend_pubkey.length() != (TOX_PUBLIC_KEY_SIZE * 2)))
        {
            friend_pubkey = null;
        }

        String result = friend_pubkey != null ? peer_pubkey_short_id(friend_pubkey) : friend_display_name_fallback();

        try
        {
            if (orma != null)
            {
                if (friend_pubkey != null)
                {
                    FriendList fl = lookup_friendlist_by_pubkey(friend_pubkey);

                    if (fl != null)
                    {
                        final String from_friendlist = display_name_from_friendlist(fl, friend_pubkey);
                        if ((from_friendlist != null) && (from_friendlist.length() > 0))
                        {
                            return from_friendlist;
                        }

                        sync_friend_name_from_tox(friendnum, fl);
                        final String after_sync = display_name_from_friendlist(fl, friend_pubkey);
                        if ((after_sync != null) && (after_sync.length() > 0))
                        {
                            return after_sync;
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        String live_name = get_tox_friend_name_live(friendnum);
        if ((live_name != null) && (friend_pubkey != null) &&
            (!is_placeholder_friend_name(live_name, friend_pubkey)))
        {
            return live_name;
        }

        return result;
    }

    static FriendList lookup_friendlist_by_pubkey(String pub_key)
    {
        if ((pub_key == null) || (pub_key.trim().isEmpty()) || (orma == null))
        {
            return null;
        }

        final String trimmed = pub_key.trim();
        final String[] variants = new String[]{
                trimmed,
                trimmed.toUpperCase(Locale.ROOT),
                trimmed.toLowerCase(Locale.ROOT),
        };

        for (final String variant : variants)
        {
            try
            {
                final List<FriendList> fl = orma.selectFromFriendList().
                        tox_public_key_stringEq(variant).toList();
                if ((fl != null) && (!fl.isEmpty()))
                {
                    return fl.get(0);
                }
            }
            catch (Exception ignored)
            {
            }
        }

        return null;
    }

    static String display_name_from_friendlist(final FriendList fl, final String friend_pubkey)
    {
        if (fl == null)
        {
            return null;
        }

        if ((fl.alias_name != null) && (fl.alias_name.length() > 0))
        {
            return fl.alias_name;
        }

        if ((fl.name != null) && (fl.name.length() > 0) &&
            (!is_placeholder_friend_name(fl.name, friend_pubkey)))
        {
            return fl.name;
        }

        return null;
    }

    static String get_friend_name_from_pubkey(String friend_pubkey)
    {
        if ((friend_pubkey == null) || (friend_pubkey.trim().isEmpty()))
        {
            return "Unknown";
        }

        final String normalized = friend_pubkey.trim();
        final String from_friendlist = display_name_from_friendlist(
                lookup_friendlist_by_pubkey(normalized), normalized);

        if ((from_friendlist != null) && (from_friendlist.length() > 0))
        {
            return from_friendlist;
        }

        return peer_pubkey_short_id(normalized);
    }

    static long get_friend_capabilities_from_pubkey(String friend_pubkey)
    {
        long friend_capabilities = 0;

        try
        {
            friend_capabilities = orma.selectFromFriendList().
                    tox_public_key_stringEq(friend_pubkey).
                    toList().get(0).capabilities;
        }
        catch (Exception e)
        {
            friend_capabilities = 0;
            e.printStackTrace();
        }

        return friend_capabilities;
    }

    static String get_friend_name_from_num(long friendnum)
    {
        return resolve_friend_display_name(friendnum);
    }

    static String peer_pubkey_short_id(final String peer_pubkey)
    {
        if ((peer_pubkey == null) || (peer_pubkey.length() < 6))
        {
            return peer_pubkey != null ? peer_pubkey : "";
        }
        return peer_pubkey.substring(peer_pubkey.length() - 6).toUpperCase(java.util.Locale.ENGLISH);
    }

    static String format_group_peer_list_display_name(final String peer_pubkey, final String raw_name)
    {
        String name = raw_name;
        if (name != null)
        {
            name = name.trim();
            if (name.startsWith("_NEW_ "))
            {
                name = name.substring(6).trim();
            }
            if (name.startsWith("zzzzzoffline"))
            {
                name = "";
            }
        }

        name = resolve_name_for_pubkey(peer_pubkey, name);

        if ((name != null) && (name.length() > 0) && (!is_placeholder_friend_name(name, peer_pubkey)) &&
            (!name.equals(friend_display_name_fallback())))
        {
            return name;
        }

        return peer_pubkey_short_id(peer_pubkey);
    }

    static boolean friendlist_has_avatar_for_pubkey(final String peer_pubkey)
    {
        if ((peer_pubkey == null) || (peer_pubkey.trim().isEmpty()))
        {
            return false;
        }

        try
        {
            final FriendList fl_temp = lookup_friendlist_by_pubkey(peer_pubkey.trim());
            if (fl_temp == null)
            {
                return false;
            }

            if ((fl_temp.avatar_filename == null) || (fl_temp.avatar_pathname == null))
            {
                return false;
            }

            if (!ChatBubbleUiHelper.shouldLoadVfsAvatar(fl_temp))
            {
                return false;
            }

            if (VFS_ENCRYPT)
            {
                final info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                        fl_temp.avatar_pathname + "/" + fl_temp.avatar_filename);
                return f1.length() > 0;
            }
            else
            {
                final java.io.File f1 = new java.io.File(fl_temp.avatar_pathname + "/" + fl_temp.avatar_filename);
                return f1.length() > 0;
            }
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static String resolve_name_for_pubkey(String pub_key, String default_name)
    {
        if ((pub_key == null) || (pub_key.trim().isEmpty()))
        {
            return default_name;
        }

        final String normalized = pub_key.trim();

        try
        {
            if (normalized.equalsIgnoreCase(global_my_toxid.substring(0, (TOX_PUBLIC_KEY_SIZE * 2))))
            {
                return global_my_name;
            }
        }
        catch (Exception e1)
        {
            e1.printStackTrace();
        }

        final String from_friendlist = display_name_from_friendlist(
                lookup_friendlist_by_pubkey(normalized), normalized);
        if ((from_friendlist != null) && (from_friendlist.length() > 0))
        {
            return from_friendlist;
        }

        if ((default_name != null) && (default_name.length() > 0) && (!default_name.equals("-1")) &&
            (!is_placeholder_friend_name(default_name, normalized)))
        {
            return default_name;
        }

        return get_friend_name_from_pubkey(normalized);
    }

    static void send_friend_msg_receipt_v2_wrapper(final long friend_number, final int msg_type, final ByteBuffer msg_id_buffer, long t_sec_receipt)
    {
        if (!MainActivity.PREF__send_read_receipts)
        {
            return;
        }

        // (msg_type == 1) msgV2 direct message
        // (msg_type == 2) msgV2 relay message
        // (msg_type == 3) msgV2 "conference" and "group" confirm msg received message
        // (msg_type == 4) msgV2 confirm unknown received message
        if (msg_type == 1)
        {
            // send message receipt v2
            MainActivity.tox_util_friend_send_msg_receipt_v2(friend_number, t_sec_receipt, msg_id_buffer);

            try
            {
                String relay_for_friend = HelperRelay.get_relay_for_friend(
                        tox_friend_get_public_key__wrapper(friend_number));

                if (relay_for_friend != null)
                {
                    // if friend has a relay, send the "msg receipt" also to the relay. just to be sure.
                    MainActivity.tox_util_friend_send_msg_receipt_v2(
                            tox_friend_by_public_key__wrapper(relay_for_friend), t_sec_receipt, msg_id_buffer);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        else if (msg_type == 2)
        {
            // send message receipt v2
            final Thread t = new Thread()
            {
                @Override
                public void run()
                {

                    // send msg receipt on main thread
                    final Runnable myRunnable = new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                String msg_id_as_hex_string = HelperGeneric.bytesToHex(msg_id_buffer.array(),
                                                                                       msg_id_buffer.arrayOffset(),
                                                                                       msg_id_buffer.limit());
                                // Log.i(TAG, "send_friend_msg_receipt_v2_wrapper:send delayed -> now msgid=" +
                                //           msg_id_as_hex_string);

                                try
                                {
                                    int res = MainActivity.tox_util_friend_send_msg_receipt_v2(friend_number,
                                                                                               t_sec_receipt,
                                                                                               msg_id_buffer);

                                    // Log.i(TAG, "send_friend_msg_receipt_v2_wrapper:ACK:1:res=" + res + " f=" +
                                    //           get_friend_name_from_num(friend_number));

                                    try
                                    {
                                        String relay_for_friend = HelperRelay.get_relay_for_friend(
                                                tox_friend_get_public_key__wrapper(friend_number));

                                        if (relay_for_friend != null)
                                        {
                                            // if friend has a relay, send the "msg receipt" also to the relay. just to be sure.
                                            int res_relay = MainActivity.tox_util_friend_send_msg_receipt_v2(
                                                    tox_friend_by_public_key__wrapper(relay_for_friend), t_sec_receipt,
                                                    msg_id_buffer);

                                            // Log.i(TAG,
                                            //      "send_friend_msg_receipt_v2_wrapper:ACK:2:res_relay=" + res_relay +
                                            //      " f=" + get_friend_name_from_num(
                                            //              tox_friend_by_public_key__wrapper(relay_for_friend)));

                                        }
                                    }
                                    catch (Exception e)
                                    {
                                        e.printStackTrace();
                                    }
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    };

                    if (MainActivity.main_handler_s != null)
                    {
                        MainActivity.main_handler_s.post(myRunnable);
                    }
                }
            };
            t.start();
        }
        else if (msg_type == 3)
        {
            // send message receipt v2
            /*
            String msg_id_as_hex_string_wrapped = HelperGeneric.bytesToHex(msg_id_buffer.array(),
                                                                           msg_id_buffer.arrayOffset(),
                                                                           msg_id_buffer.limit());

            Log.i(TAG, "send_friend_msg_receipt_v2_wrapper:(msg_type == 3):" + get_friend_name_from_num(friend_number) +
                       " buffer=" + msg_id_as_hex_string_wrapped);
             */
            MainActivity.tox_util_friend_send_msg_receipt_v2(friend_number, t_sec_receipt, msg_id_buffer);
        }
        else if (msg_type == 4)
        {
            // send message receipt v2 for unknown message
            MainActivity.tox_util_friend_send_msg_receipt_v2(friend_number, t_sec_receipt, msg_id_buffer);
        }
    }

    static void add_all_friends_clear_wrapper(int delay)
    {
        try
        {
            if (MainActivity.friend_list_fragment != null)
            {
                MainActivity.friend_list_fragment.add_all_friends_clear(delay);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void update_single_friend_in_friendlist_view(final FriendList f)
    {
        try
        {
            if (MainActivity.friend_list_fragment != null)
            {
                CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
                cc.is_friend = COMBINED_IS_FRIEND;
                cc.friend_item = f;
                MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /*
     * return true if we should stop triggering push notifications
     *        false otherwise
     */
    static boolean friend_do_actual_weburl_call(final String friend_pubkey, final String pushurl_for_friend, final long message_timestamp_circa, final boolean update_message_flag)
    {
        OkHttpClient client = null;

        if (!update_message_flag)
        {
            if (get_message_in_db_sent_push_is_read(friend_pubkey, message_timestamp_circa))
            {
                // message is "read" (received) so stop triggering push notifications
                return true;
            }
        }

        long check_for_http_too_many_request_timeout = 0L;
        try
        {
            //noinspection DataFlowIssue
            check_for_http_too_many_request_timeout = ping_push_blocker_cache.getOrDefault(pushurl_for_friend, 0L);
        }
        catch(Exception ignored)
        {
        }

        if (check_for_http_too_many_request_timeout + TWO_HOURS_IN_MILLIS > System.currentTimeMillis())
        {
            Log.i(TAG, "friend_call_push_url:HTTP 429: too many requests -> timeout");
            return false;
        }

        if (PREF__orbot_enabled)
        {
            InetSocketAddress proxyAddr = new InetSocketAddress(ORBOT_PROXY_HOST, (int) ORBOT_PROXY_PORT);
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, proxyAddr);

            client = new OkHttpClient.Builder().
                    proxy(proxy).
                    readTimeout(5, TimeUnit.SECONDS).
                    callTimeout(6, TimeUnit.SECONDS).
                    connectTimeout(8, TimeUnit.SECONDS).
                    writeTimeout(5, TimeUnit.SECONDS).
                    build();
        }
        else
        {
            client = new OkHttpClient.Builder().
                    readTimeout(5, TimeUnit.SECONDS).
                    callTimeout(6, TimeUnit.SECONDS).
                    connectTimeout(8, TimeUnit.SECONDS).
                    writeTimeout(5, TimeUnit.SECONDS).
                    build();
        }

        RequestBody formBody = new FormBody.Builder().
                add("ping", "1").
                build();

        String pushurl_to_call = pushurl_for_friend;
        try
        {
            if ((global_my_toxid != null) && (global_my_toxid.length() >= (TOX_PUBLIC_KEY_SIZE * 2)))
            {
                final String sender_pubkey = global_my_toxid.substring(0, (TOX_PUBLIC_KEY_SIZE * 2));
                pushurl_to_call = org.khandaq.messenger.KhandaqPush.withWakeParams(pushurl_for_friend, sender_pubkey);
            }
        }
        catch (Exception ignored)
        {
        }

        Request request = new Request.
                Builder().
                cacheControl(new CacheControl.Builder().noCache().build()).
                url(pushurl_to_call).
                header("User-Agent", GENERIC_TOR_USERAGENT).
                header("TTL", "" + GENERIC_UNIFIED_WEBPUSH_TTL_SECONDS).
                header("Content-Encoding", GENERIC_UNIFIED_WEBPUSH_CONTENT_ENCODING).
                post(formBody).
                build();

        try (Response response = client.newCall(request).execute())
        {
            Log.i(TAG, "friend_call_push_url"); // :url=" + pushurl_for_friend + " RES=" + response.code());
            if (response.code() == 429)
            {
                // HINT: set timestamp of last 429 HTTP code (Error Too Many Requests)
                ping_push_blocker_cache.put(pushurl_for_friend, System.currentTimeMillis());
                if (ping_push_blocker_cache.size() >= 20000)
                {
                    // HINT: too many entries. just clear the hasmap.
                    // but probably nobody will have 20k friends in this app in sum?
                    ping_push_blocker_cache.clear();
                }
            }
            else if ((response.code() < 300) && (response.code() > 199))
            {
                    if (update_message_flag)
                    {
                        update_message_in_db_sent_push_set(friend_pubkey, message_timestamp_circa);
                    }
            }
        }
        catch (Exception e1)
        {
            Log.i(TAG, "friend_call_push_url:EE1:" + e1.getMessage());
            e1.printStackTrace();
        }

        return false;
    }

    static void friend_call_push_url(final String friend_pubkey, final long message_timestamp_circa)
    {
        try
        {
            if (!PREF__use_push_service)
            {
                return;
            }

            final String pushurl_for_friend = get_pushurl_for_friend(friend_pubkey);

            if (pushurl_for_friend != null)
            {
                if (pushurl_for_friend.length() > "https://".length())
                {
                    if (is_valid_pushurl_for_friend_with_whitelist(pushurl_for_friend))
                    {

                        Thread t = new Thread()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    friend_do_actual_weburl_call(friend_pubkey, pushurl_for_friend,
                                                                 message_timestamp_circa, true);
                                }
                                catch (Exception e)
                                {
                                    Log.i(TAG, "friend_call_push_url:EE2:" + e.getMessage());
                                }
                            }
                        };
                        t.start();
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void send_pushurl_to_friend(final String friend_pubkey)
    {
        own_push_token_load();

        if (TRIFAGlobals.global_notification_token != null)
        {
            final String notification_push_url = push_token_to_push_url(TRIFAGlobals.global_notification_token);
            if (notification_push_url != null)
            {
                String temp_string = "A" + notification_push_url; //  "A" is a placeholder to put the pkgID later
                // Log.i(TAG, "send_pushurl_to_friend:" +
                //           get_friend_name_from_num(friend_number) + ":send push url:" + temp_string);
                byte[] data_bin = temp_string.getBytes(); // TODO: use specific characterset
                int data_bin_len = data_bin.length;
                data_bin[0] = (byte) CONTROL_PROXY_MESSAGE_TYPE_PUSH_URL_FOR_FRIEND.value; // replace "A" with pkgID
                final int res = tox_friend_send_lossless_packet(tox_friend_by_public_key__wrapper(friend_pubkey),
                                                                data_bin, data_bin_len);
                // Log.i(TAG, "send_pushurl_to_friend:" +
                //           get_friend_name_from_pubkey(friend_pubkey) + ":send push url:RES=" + res);
            }
        }
    }

    static void send_pushurl_to_all_friends()
    {
        own_push_token_load();

        if (TRIFAGlobals.global_notification_token == null)
        {
            return;
        }

        try
        {
            List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().
                    is_relayNotEq(true).
                    toList();

            if (fl != null)
            {
                if (fl.size() > 0)
                {
                    int i = 0;
                    for (i = 0; i < fl.size(); i++)
                    {
                        FriendList n = (FriendList) fl.get(i);
                        send_pushurl_to_friend(n.tox_public_key_string);
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static boolean is_friend_presence_online(final FriendList f)
    {
        if (f == null)
        {
            return false;
        }
        return f.last_online_timestamp == LAST_ONLINE_TIMSTAMP_ONLINE_NOW || f.TOX_CONNECTION != 0;
    }

    static String format_chat_presence_line(final Context context, final FriendList f)
    {
        if (context == null || f == null)
        {
            return "";
        }
        if (is_friend_presence_online(f))
        {
            switch (f.TOX_USER_STATUS)
            {
                case 1:
                    return context.getString(R.string.chat_presence_away);
                case 2:
                    return context.getString(R.string.chat_presence_busy);
                default:
                    return context.getString(R.string.chat_presence_online);
            }
        }
        return format_chat_last_seen_line(context, f.last_online_timestamp, f.last_online_timestamp_real);
    }

    static String format_chat_last_seen_line(final Context context, long last_seen_combined, final long last_seen_real)
    {
        long ts = last_seen_combined;
        if (ts == LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
        {
            return context.getString(R.string.chat_presence_online);
        }
        if (ts <= LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE || ts == 0L)
        {
            if (last_seen_real > 0L && last_seen_real != LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE
                    && last_seen_real != LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
            {
                ts = last_seen_real;
            }
            else
            {
                return context.getString(R.string.chat_presence_last_seen_unknown);
            }
        }
        return format_chat_last_seen_from_timestamp(context, ts);
    }

    static String format_chat_last_seen_from_timestamp(final Context context, final long timestamp_ms)
    {
        final long delta_ms = Math.max(0L, System.currentTimeMillis() - timestamp_ms);
        if (delta_ms < 60_000L)
        {
            return context.getString(R.string.chat_presence_last_seen_just_now);
        }
        final long minutes = delta_ms / 60_000L;
        if (minutes < 60L)
        {
            return context.getResources().getQuantityString(R.plurals.chat_presence_last_seen_minutes,
                    (int) minutes, (int) minutes);
        }

        final Calendar now = Calendar.getInstance();
        final Calendar seen = Calendar.getInstance();
        seen.setTimeInMillis(timestamp_ms);
        if (is_same_calendar_day(now, seen))
        {
            final Date seen_date = seen.getTime();
            return context.getString(R.string.chat_presence_last_seen_at_time,
                    MainActivity.df_time_only.format(seen_date));
        }
        final Calendar yesterday = (Calendar) now.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (is_same_calendar_day(yesterday, seen))
        {
            return context.getString(R.string.chat_presence_last_seen_yesterday);
        }
        return context.getString(R.string.chat_presence_last_seen_on_date,
                MainActivity.df_date_only.format(seen.getTime()));
    }

    private static boolean is_same_calendar_day(final Calendar a, final Calendar b)
    {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}

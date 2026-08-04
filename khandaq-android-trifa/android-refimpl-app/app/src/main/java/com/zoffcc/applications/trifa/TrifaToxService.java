/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 - 2019 Zoff <zoff@zoff.cc>
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

import org.khandaq.messenger.BuildConfig;
import org.khandaq.messenger.R;


import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.zoffcc.applications.nativeaudio.NativeAudio;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.Message;
import com.zoffcc.applications.sorm.OrmaDatabase;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.core.app.ServiceCompat;
import info.guardianproject.iocipher.VirtualFileSystem;

import static com.zoffcc.applications.nativeaudio.NativeAudio.set_aec_active;
import static com.zoffcc.applications.trifa.BootstrapNodeEntryDB.get_tcprelay_nodelist_from_db;
import static com.zoffcc.applications.trifa.BootstrapNodeEntryDB.get_udp_nodelist_from_db;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FRIEND;
import static com.zoffcc.applications.trifa.HelperConference.new_or_updated_conference;
import static com.zoffcc.applications.trifa.HelperConference.set_all_conferences_inactive;
import static com.zoffcc.applications.trifa.HelperFiletransfer.set_all_filetransfers_inactive;
import static com.zoffcc.applications.trifa.HelperFiletransfer.start_outgoing_ft;
import static com.zoffcc.applications.trifa.HelperFriend.add_friend_real_norequest;
import static com.zoffcc.applications.trifa.HelperFriend.remove_legacy_echobot_contact_if_present;
import static com.zoffcc.applications.trifa.HelperFriend.friend_call_push_url;
import static com.zoffcc.applications.trifa.HelperFriend.get_friend_msgv3_capability;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.HelperFriend.main_get_friend;
import static com.zoffcc.applications.trifa.HelperFriend.set_all_friends_offline;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_get_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperFriend.update_friend_in_db_connection_status;
import static com.zoffcc.applications.trifa.HelperGeneric.IPisValid;
import static com.zoffcc.applications.trifa.HelperGeneric.append_logger_msg;
import static com.zoffcc.applications.trifa.HelperGeneric.battery_saving_can_sleep;
import static com.zoffcc.applications.trifa.HelperGeneric.bootstrap_single_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.bytebuffer_to_hexstring;
import static com.zoffcc.applications.trifa.HelperGeneric.bytes_to_hex;
import static com.zoffcc.applications.trifa.HelperGeneric.del_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.get_combined_connection_status;
import static com.zoffcc.applications.trifa.HelperGeneric.get_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.get_toxconnection_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.hex_to_bytes;
import static com.zoffcc.applications.trifa.HelperGeneric.isIPPortValid;
import static com.zoffcc.applications.trifa.HelperGeneric.is_valid_tox_public_key;
import static com.zoffcc.applications.trifa.HelperGeneric.long_date_time_format;
import static com.zoffcc.applications.trifa.HelperGeneric.set_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.tox_friend_resend_msgv3_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.tox_friend_send_message_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.trigger_proper_wakeup_from_tox_service_thread;
import static com.zoffcc.applications.trifa.HelperGeneric.trigger_proper_wakeup_outside_tox_service_thread;
import static com.zoffcc.applications.trifa.HelperGeneric.vfs__unmount;
import static com.zoffcc.applications.trifa.HelperGroup.is_valid_group_title_string;
import static com.zoffcc.applications.trifa.HelperGroup.prune_stale_tox_groups;
import static com.zoffcc.applications.trifa.HelperGroup.remove_legacy_public_community_group;
import static com.zoffcc.applications.trifa.HelperGroup.new_or_updated_group;
import static com.zoffcc.applications.trifa.HelperGroup.needs_fast_group_iterate;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_db_name;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_db_privacy_state;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_db_topic;
import static com.zoffcc.applications.trifa.HelperMessage.set_message_queueing_from_id;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_messageid;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_msg_idv3_hash;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_no_read_recvedts;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_resend_count;
import static com.zoffcc.applications.trifa.HelperRelay.get_relay_for_friend;
import static com.zoffcc.applications.trifa.HelperRelay.is_any_relay;
import static com.zoffcc.applications.trifa.HelperToxNotification.tox_notification_cancel;
import static com.zoffcc.applications.trifa.HelperToxNotification.tox_notification_change;
import static com.zoffcc.applications.trifa.HelperToxNotification.tox_notification_change_wrapper;
import static com.zoffcc.applications.trifa.HelperToxNotification.tox_notification_setup;
import static com.zoffcc.applications.trifa.MainActivity.DEBUG_BATTERY_OPTIMIZATION_LOGGING;
import static com.zoffcc.applications.trifa.MainActivity.DEBUG_USE_LOGFRIEND;
import static com.zoffcc.applications.trifa.MainActivity.PREF__X_battery_saving_mode;
import static com.zoffcc.applications.trifa.MainActivity.PREF__X_battery_saving_timeout;
import static com.zoffcc.applications.trifa.MainActivity.PREF__force_udp_only;
import static com.zoffcc.applications.trifa.MainActivity.PREF__use_push_service;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_FILES_DEBUG_DIR;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.MainActivity.cache_confid_confnum;
import static com.zoffcc.applications.trifa.MainActivity.cache_fnum_pubkey;
import static com.zoffcc.applications.trifa.MainActivity.cache_pubkey_fnum;
import static com.zoffcc.applications.trifa.MainActivity.conference_audio_activity;
import static com.zoffcc.applications.trifa.MainActivity.conference_message_list_activity;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.get_my_toxid;
import static com.zoffcc.applications.trifa.MainActivity.group_message_list_activity;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.receiver1;
import static com.zoffcc.applications.trifa.MainActivity.receiver2;
import static com.zoffcc.applications.trifa.MainActivity.receiver3;
import static com.zoffcc.applications.trifa.MainActivity.receiver4;
import static com.zoffcc.applications.trifa.MainActivity.tox_conference_get_chatlist;
import static com.zoffcc.applications.trifa.MainActivity.tox_conference_get_chatlist_size;
import static com.zoffcc.applications.trifa.MainActivity.tox_conference_get_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_conference_get_type;
import static com.zoffcc.applications.trifa.MainActivity.tox_friend_get_connection_status;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_chat_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_grouplist;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_topic;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_number_groups;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_privacy_state;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_is_connected;
import static com.zoffcc.applications.trifa.MainActivity.tox_iterate;
import static com.zoffcc.applications.trifa.MainActivity.tox_iteration_interval;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_capabilites;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_capabilities;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_connection_status;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_name_size;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_status_message;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_status_message_size;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_set_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_set_status_message;
import static com.zoffcc.applications.trifa.MainActivity.tox_service_fg;
import static com.zoffcc.applications.trifa.MainActivity.tox_util_friend_resend_message_v2;
import static com.zoffcc.applications.trifa.TRIFAGlobals.BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONFERENCE_ID_LENGTH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GROUP_ID_LENGTH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.HAVE_INTERNET_CONNECTIVITY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOGFRIEND_ON_STARTUP_DONE_DB_KEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOGFRIEND_TOXID_DB_KEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOG_FRIEND_INIT_NAME;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOG_FRIEND_INIT_STATUSMSG;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOG_FRIEND_TOXID;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MAX_TEXTMSG_RESEND_COUNT_OLDMSG_VERSION;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_TCP_IP;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_TCP_KEYHEX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_TCP_PORT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_UDP_IP;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_UDP_KEYHEX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_UDP_PORT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_BOOTSTRAP_AGAIN_AFTER_OFFLINE_MILLIS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_BOOTSTRAP_MIN_INTERVAL_SECS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_MIN_NORMAL_ITERATE_DELTA_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.USE_MAX_NUMBER_OF_BOOTSTRAP_NODES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.USE_MAX_NUMBER_OF_BOOTSTRAP_TCP_RELAYS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.bootstrapListsLock;
import static com.zoffcc.applications.trifa.TRIFAGlobals.bootstrap_node_list;
import static com.zoffcc.applications.trifa.TRIFAGlobals.bootstrapping;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_for_battery_savings_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_incoming_ft_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_outgoung_ft_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_bootstrap_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_status_message;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_toxid;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_last_went_offline_timestamp;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_showing_anygroupview;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_showing_messageview;
import static com.zoffcc.applications.trifa.TRIFAGlobals.tcprelay_node_list;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_HASH_LENGTH;

public class TrifaToxService extends Service
{
    static final String TAG = "trifa.ToxService";
    Notification notification2 = null;
    NotificationManager nmn2 = null;
    static Thread ToxServiceThread = null;
    // static EchoCanceller canceller = null;
    static boolean stop_me = false;
    static OrmaDatabase orma = null;
    static VirtualFileSystem vfs = null;
    static boolean is_tox_started = false;
    static boolean manually_logged_out = false;
    static boolean global_toxid_text_set = false;
    static boolean TOX_SERVICE_STARTED = false;
    static Thread trifa_service_thread = null;
    static long last_resend_pending_messages0_ms = -1;
    static long last_resend_pending_messages1_ms = -1;
    static long last_resend_pending_messages2_ms = -1;
    static long last_resend_pending_messages3_ms = -1;
    static long last_resend_pending_messages4_ms = -1;
    static long last_start_queued_fts_ms = -1;
    static long last_resend_unsent_text_messages_ms = -1;
    static long last_delivery_watchdog_ms = -1;
    static boolean need_wakeup_now = false;
    static int tox_thread_starting_up = 0;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        HelperGeneric.logI(TAG, "onStartCommand");
        // this gets called all the time!
        tox_service_fg = this;
        // KHANDAQ (logout→login): re-login issues startService() but not onCreate() (Service stays
        // alive across logout), so mark the service started here. This is race-free now because
        // performLogout() only re-arms TOX_SERVICE_STARTED=false + navigates AFTER tox is fully dead,
        // so the onCreate tox bring-up can no longer collide with the in-flight tox_kill.
        TOX_SERVICE_STARTED = true;
        return START_NOT_STICKY; // START_STICKY;
    }

    @Override
    public void onCreate()
    {
        HelperGeneric.logI(TAG, "onCreate");
        // serivce is created ---
        super.onCreate();

        TOX_SERVICE_STARTED = true;
        start_me();
    }

    /*
     *
     * ------ this really stops the whole thing ------
     *
     */
    void stop_me(boolean exit_app)
    {
        HelperGeneric.logI(TAG, "stop_me:001:tox_thread_starting_up=" + tox_thread_starting_up);
        stopForeground(true);

        HelperGeneric.logI(TAG, "stop_me:002:tox_thread_starting_up=" + tox_thread_starting_up);
        tox_notification_cancel(this);
        HelperGeneric.logI(TAG, "stop_me:003");

        final Context static_context = this;

        if (exit_app)
        {
            try
            {
                HelperGeneric.logI(TAG, "stop_me:004:tox_thread_starting_up=" + tox_thread_starting_up);
                Thread t = new Thread()
                {
                    @Override
                    public void run()
                    {
                        HelperGeneric.logI(TAG, "stop_me:005:tox_thread_starting_up=" + tox_thread_starting_up);
                        set_aec_active(0);
                        long i = 0;
                        while (is_tox_started)
                        {
                            i++;
                            if (i > 40)
                            {
                                break;
                            }

                            HelperGeneric.logI(TAG, "stop_me:006:tox_thread_starting_up=" + tox_thread_starting_up);

                            try
                            {
                                Thread.sleep(150);
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                        HelperGeneric.logI(TAG, "stop_me:006a:tox_thread_starting_up=" + tox_thread_starting_up);

                        HelperGeneric.logI(TAG, "stop_me:unmount sql database");
                        OrmaDatabase.shutdown();

                        if (VFS_ENCRYPT)
                        {
                            HelperGeneric.logI(TAG, "stop_me:006b");
                            try
                            {
                                HelperGeneric.logI(TAG, "stop_me:006c");
                                if (vfs.isMounted())
                                {
                                    HelperGeneric.logI(TAG, "stop_me:006d");
                                    HelperGeneric.logI(TAG, "VFS:detach:start:vfs.isMounted()=" + vfs.isMounted() + " " +
                                               Thread.currentThread().getId() + ":" + Thread.currentThread().getName());
                                    // vfs__detach();
                                    HelperGeneric.logI(TAG, "stop_me:006e");
                                    Thread.sleep(1);
                                    HelperGeneric.logI(TAG, "VFS:unmount:start:vfs.isMounted()=" + vfs.isMounted() + " " +
                                               Thread.currentThread().getId() + ":" + Thread.currentThread().getName());
                                    vfs__unmount();
                                    HelperGeneric.logI(TAG, "stop_me:006f");
                                }
                                else
                                {
                                    HelperGeneric.logI(TAG, "stop_me:006g");
                                    HelperGeneric.logI(TAG, "VFS:unmount:NOT MOUNTED");
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                                HelperGeneric.logI(TAG, "VFS:unmount:EE:" + e.getMessage());
                                HelperGeneric.logI(TAG, "stop_me:006h");
                            }
                        }

                        HelperGeneric.logI(TAG, "stop_me:007");

                        try
                        {
                            unregisterReceiver(receiver1);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        try
                        {
                            unregisterReceiver(receiver2);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        try
                        {
                            unregisterReceiver(receiver3);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        try
                        {
                            unregisterReceiver(receiver4);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        HelperGeneric.logI(TAG, "stop_me:008");
                        tox_notification_cancel(static_context);
                        HelperGeneric.logI(TAG, "stop_me:009");

                        HelperGeneric.logI(TAG, "stop_me:010");
                        stopSelf();
                        HelperGeneric.logI(TAG, "stop_me:011");

                        try
                        {
                            HelperGeneric.logI(TAG, "stop_me:012");
                            Thread.sleep(300);
                            HelperGeneric.logI(TAG, "stop_me:013");
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        HelperGeneric.logI(TAG, "stop_me:014");
                        tox_notification_cancel(static_context);
                        HelperGeneric.logI(TAG, "stop_me:015");

                        // MainActivity.exit();
                        try
                        {
                            System.exit(0);
                        }
                        catch (Exception ignored)
                        {
                        }

                        HelperGeneric.logI(TAG, "stop_me:089");
                    }
                };
                t.start();
                HelperGeneric.logI(TAG, "stop_me:099");
            }
            catch (Exception e)
            {
                e.printStackTrace();
                try
                {
                    stopSelf();
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                }

                try
                {
                    System.exit(0);
                }
                catch (Exception ignored)
                {
                }
            }
        }
    }

    static boolean stop_tox_fg_done = false;

    void stop_tox_fg(final boolean want_exit)
    {
        stop_tox_fg_done = false;
        HelperGeneric.logI(TAG, "stop_tox_fg:001");
        Thread t = new Thread()
        {
            @Override
            public void run()
            {
                HelperGeneric.logI(TAG, "stop_tox_fg:002:a");
                HelperGeneric.update_savedata_file_wrapper(); // save on tox shutdown
                HelperGeneric.logI(TAG, "stop_tox_fg:002:b");
                stop_me = true;

                try
                {
                    ToxServiceThread.interrupt();
                }
                catch (Exception e)
                {

                }

                HelperGeneric.logI(TAG, "stop_tox_fg:003");
                try
                {
                    HelperGeneric.logI(TAG, "stop_tox_fg:004");
                    ToxServiceThread.join();
                    HelperGeneric.logI(TAG, "stop_tox_fg:005");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "stop_tox_fg:006:EE:" + e.getMessage());
                    e.printStackTrace();
                }

                stop_me = false; // reset flag again!
                HelperGeneric.logI(TAG, "stop_tox_fg:007");
                tox_notification_change_wrapper(0, ""); // set to offline
                HelperGeneric.logI(TAG, "stop_tox_fg:008");
                set_all_friends_offline();
                HelperGeneric.logI(TAG, "set_all_conferences_inactive:003");
                set_all_conferences_inactive();

                // so that the app knows we went offline
                global_self_connection_status = TOX_CONNECTION_NONE.value;

                HelperGeneric.logI(TAG, "stop_tox_fg:009");

                if (want_exit)
                {
                    tox_notification_cancel(context_s);
                    HelperGeneric.logI(TAG, "stop_tox_fg:clear_tox_notification");
                }

                try
                {
                    HelperGeneric.logI(TAG, "stop_tox_fg:010a");
                    Thread.sleep(500);
                    HelperGeneric.logI(TAG, "stop_tox_fg:010b");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                stop_tox_fg_done = true;
                is_tox_started = false;

                HelperGeneric.logI(TAG, "stop_tox_fg:thread:done");
            }
        };

        HelperGeneric.logI(TAG, "stop_tox_fg:HH:001");
        t.start();
        HelperGeneric.logI(TAG, "stop_tox_fg:HH:004");
        HelperGeneric.logI(TAG, "stop_tox_fg:099");
    }

    void load_and_add_all_friends()
    {

        // --- load and update all friends ---
        long[] friends = MainActivity.tox_self_get_friend_list();
        HelperGeneric.logI(TAG, "loading_friend:number_of_friends=" + friends.length);

        int fc = 0;
        boolean exists_in_db = false;

        for (fc = 0; fc < friends.length; fc++)
        {
            // HelperGeneric.logI(TAG, "loading_friend:" + fc + " friendnum=" + MainActivity.friends[fc]);
            // HelperGeneric.logI(TAG, "loading_friend:" + fc + " pubkey=" + tox_friend_get_public_key__wrapper(MainActivity.friends[fc]));

            FriendList f;
            List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().tox_public_key_stringEq(
                    tox_friend_get_public_key__wrapper(friends[fc])).toList();

            // HelperGeneric.logI(TAG, "loading_friend:" + fc + " db entry size=" + fl);

            if (fl.size() > 0)
            {
                f = (FriendList) fl.get(0);
                // HelperGeneric.logI(TAG, "loading_friend:" + fc + " db entry=" + f);
            }
            else
            {
                f = null;
            }

            if (f == null)
            {
                HelperGeneric.logI(TAG, "loading_friend:c is null");

                f = new FriendList();
                f.tox_public_key_string = "" + (long) ((Math.random() * 10000000d));
                try
                {
                    f.tox_public_key_string = tox_friend_get_public_key__wrapper(friends[fc]);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                // KHANDAQ (#24): this friend is in toxcore but not yet in our DB (typically right after
                // importing a .tox profile). Use the name toxcore restored from the savedata instead of
                // a "friend #N" placeholder, so imported contacts keep their real names immediately —
                // previously the placeholder stuck until each friend happened to reconnect and re-send
                // their name.
                String live_name = null;
                try
                {
                    live_name = MainActivity.tox_friend_get_name(friends[fc]);
                }
                catch (Exception ignored)
                {
                }
                if ((live_name != null) &&
                    !HelperFriend.is_placeholder_friend_name(live_name, f.tox_public_key_string))
                {
                    f.name = live_name;
                }
                else
                {
                    f.name = "friend #" + fc;
                }
                exists_in_db = false;
                // HelperGeneric.logI(TAG, "loading_friend:c is null fnew=" + f);
            }
            else
            {
                // HelperGeneric.logI(TAG, "loading_friend:found friend in DB " + f.tox_public_key_string + " f=" + f);
                exists_in_db = true;
            }

            try
            {
                // get the real "live" connection status of this friend
                // the value in the database may be old (and wrong)
                int status_new = tox_friend_get_connection_status(friends[fc]);
                int combined_connection_status_ = get_combined_connection_status(f.tox_public_key_string, status_new);

                f.TOX_CONNECTION = combined_connection_status_;
                f.TOX_CONNECTION_on_off = get_toxconnection_wrapper(f.TOX_CONNECTION);
                f.TOX_CONNECTION_real = status_new;
                f.TOX_CONNECTION_on_off_real = get_toxconnection_wrapper(f.TOX_CONNECTION_real);

                f.added_timestamp = System.currentTimeMillis();

                if ((status_new != 0) && (combined_connection_status_ != 0))
                {
                    // HelperGeneric.logI(TAG, "non_relay_status:ALL:" + friends[fc] + " pk=" +
                    //           get_friend_name_from_pubkey(f.tox_public_key_string) + " status=" + status_new +
                    //           " combined_connection_status_=" + combined_connection_status_);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            if (exists_in_db == false)
            {
                // HelperGeneric.logI(TAG, "loading_friend:1:insertIntoFriendList:" + " f=" + f);
                orma.insertIntoFriendList(f);
                // HelperGeneric.logI(TAG, "loading_friend:2:insertIntoFriendList:" + " f=" + f);
            }
            else
            {
                // HelperGeneric.logI(TAG, "loading_friend:1:updateFriendList:" + " f=" + f);

                // @formatter:off
                orma.updateFriendList().
                        tox_public_key_stringEq(tox_friend_get_public_key__wrapper(friends[fc])).
                        name(f.name).
                        status_message(f.status_message).
                        TOX_CONNECTION(f.TOX_CONNECTION).
                        TOX_CONNECTION_on_off(get_toxconnection_wrapper(f.TOX_CONNECTION)).
                        TOX_CONNECTION_real(f.TOX_CONNECTION_real).
                        TOX_CONNECTION_on_off_real(get_toxconnection_wrapper(f.TOX_CONNECTION_real)).
                        TOX_USER_STATUS(f.TOX_USER_STATUS).
                        execute();
                // @formatter:on
                // HelperGeneric.logI(TAG, "loading_friend:1:updateFriendList:" + " f=" + f);
            }

            try_update_friend_in_friendlist(friends[fc]);
        }
        // --- load and update all friends ---

        // now run thru the list again to account for relays ----------------

        for (fc = 0; fc < friends.length; fc++)
        {
            try
            {
                List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().tox_public_key_stringEq(
                        tox_friend_get_public_key__wrapper(friends[fc])).toList();

                if (fl.size() > 0)
                {
                    final FriendList f = (FriendList) fl.get(0);
                    if (!is_any_relay(f.tox_public_key_string))
                    {
                        final int status_new = tox_friend_get_connection_status(friends[fc]);
                        final String friends_relay = HelperRelay.get_relay_for_friend(f.tox_public_key_string);
                        if (friends_relay != null)
                        {
                            int combined_connection_status_ = get_combined_connection_status(f.tox_public_key_string,
                                                                                             status_new);

                            // HelperGeneric.logI(TAG, "non_relay_status:FWR:" + friends[fc] + " pk=" +
                            //           get_friend_name_from_pubkey(f.tox_public_key_string) + " status=" + status_new +
                            //           " combined_connection_status_=" + combined_connection_status_);

                            f.TOX_CONNECTION = combined_connection_status_;
                            f.TOX_CONNECTION_on_off = get_toxconnection_wrapper(f.TOX_CONNECTION);
                            update_friend_in_db_connection_status(f);
                            try_update_friend_in_friendlist(friends[fc]);
                        }
                    }
                }
            }
            catch (Exception e)
            {
            }
        }
        // now run thru the list again to account for relays ----------------


    }

    static void try_update_friend_in_friendlist(long friendnum)
    {
        FriendList f_check;
        List<com.zoffcc.applications.sorm.FriendList> fl_check = orma.selectFromFriendList().tox_public_key_stringEq(
                tox_friend_get_public_key__wrapper(friendnum)).toList();
        // HelperGeneric.logI(TAG, "loading_friend:check:" + " db entry=" + fl_check);
        try
        {
            // HelperGeneric.logI(TAG, "loading_friend:check:" + " db entry=" + fl_check.get(0));

            try
            {
                CombinedFriendsAndConferences cc = null;
                if (MainActivity.friend_list_fragment != null)
                {
                    // reload friend in friendlist
                    cc = new CombinedFriendsAndConferences();
                    cc.is_friend = COMBINED_IS_FRIEND;
                    cc.friend_item = (FriendList) fl_check.get(0);
                    MainActivity.friend_list_fragment.modify_friend(cc, cc.is_friend);
                }
                if (MainActivity.contacts_list_fragment != null)
                {
                    if (cc == null)
                    {
                        cc = new CombinedFriendsAndConferences();
                        cc.is_friend = COMBINED_IS_FRIEND;
                        cc.friend_item = (FriendList) fl_check.get(0);
                    }
                    MainActivity.contacts_list_fragment.modify_friend(cc, cc.is_friend);
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
            HelperGeneric.logI(TAG, "loading_friend:check:EE:" + e.getMessage());
        }
    }

    void load_and_add_all_conferences()
    {
        long num_conferences = tox_conference_get_chatlist_size();
        HelperGeneric.logI(TAG, "load conferences at startup: num=" + num_conferences);

        long[] conference_numbers = tox_conference_get_chatlist();
        ByteBuffer cookie_buf3 = ByteBuffer.allocateDirect(CONFERENCE_ID_LENGTH * 2);

        int conf_ = 0;
        for (conf_ = 0; conf_ < num_conferences; conf_++)
        {
            cookie_buf3.clear();
            if (tox_conference_get_id(conference_numbers[conf_], cookie_buf3) == 0)
            {
                byte[] cookie_buffer = new byte[CONFERENCE_ID_LENGTH];
                cookie_buf3.get(cookie_buffer, 0, CONFERENCE_ID_LENGTH);
                String conference_identifier = bytes_to_hex(cookie_buffer);
                // HelperGeneric.logI(TAG, "load conference num=" + conference_numbers[conf_] + " cookie=" + conference_identifier +
                //           " offset=" + cookie_buf3.arrayOffset());

                // final ConferenceDB conf2 = orma.selectFromConferenceDB().toList().get(0);
                //HelperGeneric.logI(TAG,
                //      "conference 0 in db:" + conf2.conference_identifier + " " + conf2.tox_conference_number + " " +
                //      conf2.name);

                new_or_updated_conference(conference_numbers[conf_], tox_friend_get_public_key__wrapper(0),
                                          conference_identifier, tox_conference_get_type(
                                conference_numbers[conf_])); // rejoin a saved conference

                //if (tox_conference_get_type(conference_numbers[conf_]) == TOX_CONFERENCE_TYPE_AV.value)
                //{
                //    // TODO: this returns error. check it
                //    long result = toxav_groupchat_disable_av(conference_numbers[conf_]);
                //    HelperGeneric.logI(TAG, "load conference num=" + conference_numbers[conf_] + " toxav_groupchat_disable_av res=" +
                //               result);
                //}

                try
                {
                    if (conference_message_list_activity != null)
                    {
                        if (conference_message_list_activity.get_current_conf_id().equals(conference_identifier))
                        {
                            conference_message_list_activity.set_conference_connection_status_icon();
                        }
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    if (conference_audio_activity != null)
                    {
                        if (conference_audio_activity.get_current_conf_id().equals(conference_identifier))
                        {
                            conference_audio_activity.set_conference_connection_status_icon();
                        }
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

            }
        }
    }

    void load_and_add_all_groups()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        remove_legacy_public_community_group();
        prune_stale_tox_groups();

        long num_groups = tox_group_get_number_groups();
        HelperGeneric.logI(TAG, "load groups at startup: num=" + num_groups);

        long[] group_numbers = tox_group_get_grouplist();
        ByteBuffer groupid_buf3 = ByteBuffer.allocateDirect(GROUP_ID_LENGTH * 2);

        // KHANDAQ #202: bound the loop by the ACTUAL grouplist length — prune/leave can make grouplist
        // shorter than num_groups, and a null list right after import would otherwise NPE / AIOOBE.
        final long safe_num_groups = (group_numbers == null) ? 0L : Math.min(num_groups, group_numbers.length);
        int conf_ = 0;
        for (conf_ = 0; conf_ < safe_num_groups; conf_++)
        {
            groupid_buf3.clear();

            if (tox_group_get_chat_id(group_numbers[conf_], groupid_buf3) == 0)
            {
                byte[] groupid_buffer = new byte[GROUP_ID_LENGTH];
                groupid_buf3.get(groupid_buffer, 0, GROUP_ID_LENGTH);
                String group_identifier = bytes_to_hex(groupid_buffer);
                int is_connected = tox_group_is_connected(group_numbers[conf_]);
                HelperGeneric.logI(TAG, "load group num=" + group_numbers[conf_] + " connected=" + is_connected);

                new_or_updated_group(group_numbers[conf_], tox_friend_get_public_key__wrapper(0), group_identifier,
                                     tox_group_get_privacy_state(group_numbers[conf_]));

                String group_name = tox_group_get_name(group_numbers[conf_]);
                if (group_name == null)
                {
                    group_name = "";
                }

                String group_topic = tox_group_get_topic(group_numbers[conf_]);
                if (is_valid_group_title_string(group_topic))
                {
                    update_group_in_db_name(group_identifier, group_topic);
                    update_group_in_db_topic(group_identifier, group_topic);
                }
                else
                {
                    update_group_in_db_name(group_identifier, group_name);
                }

                final int new_privacy_state = tox_group_get_privacy_state(group_numbers[conf_]);
                update_group_in_db_privacy_state(group_identifier, new_privacy_state);

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
            }
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    static void write_debug_file(String filename)
    {
        if (DEBUG_BATTERY_OPTIMIZATION_LOGGING)
        {
            try
            {
                Log.d("BATTOPTDEBUG", "" + filename);

                File dir = new File(SD_CARD_FILES_DEBUG_DIR);
                dir.mkdirs();
                String filename2 = long_date_time_format(System.currentTimeMillis()) + "_" + filename;
                File file = new File(dir, filename2);

                FileOutputStream f = new FileOutputStream(file);
                f.write(1);
                f.close();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    void tox_thread_start_fg()
    {
        HelperGeneric.logI(TAG, "tox_thread_start_fg");

        ToxServiceThread = new Thread()
        {
            @Override
            public void run()
            {

                tox_thread_starting_up = 0;

                try
                {
                    this.setName("tox_iterate()");
                }
                catch (Exception e)
                {
                }

                // KHANDAQ #153: the native init() refuses to create a Tox instance when
                // savedata.tox is corrupt or password-encrypted (e.g. an encrypted .tox was
                // imported). Every native call below would then SIGSEGV on the NULL tox handle,
                // crash-looping the app forever. Park the broken file and restart clean instead.
                if (get_my_toxid() == null)
                {
                    HelperGeneric.logI(TAG, "tox_thread_start_fg:tox init FAILED (NULL tox), parking broken savedata");
                    try
                    {
                        final File broken_savedata = new File(MainActivity.app_files_directory, "savedata.tox");
                        if (broken_savedata.exists())
                        {
                            broken_savedata.renameTo(
                                    new File(MainActivity.app_files_directory, "savedata.tox.broken"));
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    if (main_handler_s != null)
                    {
                        main_handler_s.post(() ->
                        {
                            try
                            {
                                Toast.makeText(context_s, R.string.tox_profile_load_failed,
                                               Toast.LENGTH_LONG).show();
                            }
                            catch (Exception ignored)
                            {
                            }
                        });
                    }

                    try
                    {
                        Thread.sleep(4000); // let the user read the toast
                    }
                    catch (Exception ignored)
                    {
                    }

                    System.exit(0);
                    return;
                }

                // ------ correct startup order ------
                boolean old_is_tox_started = is_tox_started;
                HelperGeneric.logI(TAG, "is_tox_started:==============================");
                HelperGeneric.logI(TAG, "is_tox_started=" + is_tox_started);
                HelperGeneric.logI(TAG, "is_tox_started:==============================");

                is_tox_started = true;
                // KHANDAQ (logout crash fix): tox is up again, so we are no longer "logged out". Clearing
                // here (cold start AND same-process re-login) re-enables the reloadProfileFromTox guard
                // that manually_log_out() armed, without leaving it stuck true forever.
                manually_logged_out = false;

                Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (!global_toxid_text_set)
                        {
                            global_toxid_text_set = true;
                            // MainActivity.mt.setText(MainActivity.mt.getText() + "\n" + "my_ToxId=" + get_my_toxid());
                        }
                    }
                };

                if (main_handler_s != null)
                {
                    main_handler_s.post(myRunnable);
                }

                if (!old_is_tox_started)
                {
                    HelperGeneric.logI(TAG, "set_all_conferences_inactive:004");
                    set_all_friends_offline();
                    set_all_conferences_inactive();
                    set_all_filetransfers_inactive();
                    MainActivity.init_tox_callbacks();
                    HelperGeneric.update_savedata_file_wrapper();
                }
                // ------ correct startup order ------

                cache_pubkey_fnum.clear();
                cache_fnum_pubkey.clear();
                cache_confid_confnum.clear();

                try
                {
                    orma.updateFriendList().ip_addr_str("").execute();
                }
                catch(Exception e)
                {
                }

                tox_self_capabilites = tox_self_get_capabilities();
                //HelperGeneric.logI(TAG, "tox_self_capabilites:" + tox_self_capabilites + " decoded:" +
                //           TOX_CAPABILITY_DECODE_TO_STRING(TOX_CAPABILITY_DECODE(tox_self_capabilites)) + " " +
                //           (1L << 63L));

                // ----- convert old conference messages which did not contain a sent timestamp -----
                try
                {
                    boolean need_migrate_old_conf_msg_date = true;

                    if (get_g_opts("MIGRATE_OLD_CONF_MSG_DATE_done") != null)
                    {
                        if (get_g_opts("MIGRATE_OLD_CONF_MSG_DATE_done").equals("true"))
                        {
                            need_migrate_old_conf_msg_date = false;
                        }
                    }

                    if (need_migrate_old_conf_msg_date == true)
                    {
                        try
                        {
                            orma.run_multi_sql(
                                    "update ConferenceMessage set sent_timestamp=rcvd_timestamp" + " where " +
                                    " sent_timestamp='0'");
                            HelperGeneric.logI(TAG, "onCreate:migrate_old_conf_msg_date");
                        }
                        catch (Exception e)
                        {
                            HelperGeneric.logI(TAG, "onCreate:migrate_old_conf_msg_date:EE01");
                        }
                        // now remember that we did that, and don't do it again
                        set_g_opts("MIGRATE_OLD_CONF_MSG_DATE_done", "true");
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "onCreate:migrate_old_conf_msg_date:EE:" + e.getMessage());
                }
                // ----- convert old conference messages which did not contain a sent timestamp -----

                // ----- convert old NULL's into false -----
                try
                {
                    orma.run_multi_sql(
                            "update ConferenceMessage set was_synced=false" + " where " + " was_synced is NULL");
                    HelperGeneric.logI(TAG, "onCreate:migrate_was_synced");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "onCreate:migrate_was_synced:EE01");
                }
                // ----- convert old NULL's into false -----

                // ----- convert old NULL's into false -----
                try
                {
                    orma.run_multi_sql(
                            "update GroupDB set group_we_left=false" + " where " + " group_we_left is NULL");
                    HelperGeneric.logI(TAG, "onCreate:migrate_group_we_left");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "onCreate:migrate_group_we_left:EE01");
                }
                // ----- convert old NULL's into false -----


                // ----- convert old NULL's into 0 -----
                try
                {
                    orma.run_multi_sql("update Message set sent_push='0' where sent_push is NULL");
                    HelperGeneric.logI(TAG, "onCreate:sent_push");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "onCreate:sent_push:EE01");
                }
                // ----- convert old NULL's into 0 -----

                // ----- convert old NULL's into 0 -----
                try
                {
                    orma.run_multi_sql(
                            "update FriendList set msgv3_capability='0' where msgv3_capability is NULL");
                    HelperGeneric.logI(TAG, "onCreate:msgv3_capability");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "onCreate:msgv3_capability:EE01");
                }
                // ----- convert old NULL's into 0 -----

                // ----- convert old NULL's into 0 -----
                try
                {
                    orma.run_multi_sql(
                            "update Message set filetransfer_kind='0' where filetransfer_kind is NULL");
                    HelperGeneric.logI(TAG, "onCreate:filetransfer_kind");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "onCreate:filetransfer_kind:EE01");
                }
                // ----- convert old NULL's into 0 -----

                // ----- convert old NULL's into 0 -----
                try
                {
                    orma.run_multi_sql(
                            "update GroupMessage set TRIFA_SYNC_TYPE='0' where TRIFA_SYNC_TYPE is NULL");
                    HelperGeneric.logI(TAG, "onCreate:TRIFA_SYNC_TYPE");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "onCreate:TRIFA_SYNC_TYPE:EE01");
                }
                // ----- convert old NULL's into 0 -----

                // ----- convert old NULL's into 0 -----
                try
                {
                    orma.run_multi_sql("update GroupMessage set tox_group_peer_pubkey_syncer_01_sent_timestamp='0' where tox_group_peer_pubkey_syncer_01_sent_timestamp is NULL");
                    HelperGeneric.logI(TAG, "onCreate:tox_group_peer_pubkey_syncer_01_sent_timestamp");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "onCreate:tox_group_peer_pubkey_syncer_01_sent_timestamp:EE01");
                }
                try
                {
                    orma.run_multi_sql("update GroupMessage set tox_group_peer_pubkey_syncer_02_sent_timestamp='0' where tox_group_peer_pubkey_syncer_02_sent_timestamp is NULL");
                    HelperGeneric.logI(TAG, "onCreate:tox_group_peer_pubkey_syncer_02_sent_timestamp");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "onCreate:tox_group_peer_pubkey_syncer_02_sent_timestamp:EE01");
                }
                try
                {
                    orma.run_multi_sql("update GroupMessage set tox_group_peer_pubkey_syncer_03_sent_timestamp='0' where tox_group_peer_pubkey_syncer_03_sent_timestamp is NULL");
                    HelperGeneric.logI(TAG, "onCreate:tox_group_peer_pubkey_syncer_03_sent_timestamp");
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "onCreate:tox_group_peer_pubkey_syncer_03_sent_timestamp:EE01");
                }
                // ----- convert old NULL's into 0 -----


                // TODO --------
                String my_tox_id_local = get_my_toxid();
                global_my_toxid = my_tox_id_local;
                if (tox_self_get_name_size() > 0)
                {
                    String tmp_name = tox_self_get_name();
                    if (tmp_name != null)
                    {
                        if (tmp_name.length() > 0)
                        {
                            android.content.SharedPreferences profile_prefs =
                                    android.preference.PreferenceManager.getDefaultSharedPreferences(context_s);
                            if (is_legacy_trifa_profile_name(tmp_name) &&
                                !profile_prefs.getBoolean("legacy_trifa_profile_name_migrated", false))
                            {
                                tmp_name = default_self_display_name(my_tox_id_local);
                                tox_self_set_name(tmp_name);
                                profile_prefs.edit().putBoolean("legacy_trifa_profile_name_migrated", true).apply();
                            }
                            global_my_name = tmp_name;
                            // HelperGeneric.logI(TAG, "AAA:003:" + global_my_name + " size=" + tox_self_get_name_size());
                        }
                    }
                }
                else
                {
                    // KHANDAQ: new profile — use the name typed on the "Создание профиля" onboarding step
                    // (one-shot pref), else fall back to the auto display name.
                    final android.content.SharedPreferences onb_prefs =
                            android.preference.PreferenceManager.getDefaultSharedPreferences(context_s);
                    final String onboarding_name = onb_prefs.getString(
                            CreateProfileActivity.PREF_ONBOARDING_DISPLAY_NAME, "");
                    final String new_name = (onboarding_name != null && onboarding_name.trim().length() > 0)
                            ? onboarding_name.trim()
                            : default_self_display_name(my_tox_id_local);
                    tox_self_set_name(new_name);
                    global_my_name = new_name;
                    onb_prefs.edit().remove(CreateProfileActivity.PREF_ONBOARDING_DISPLAY_NAME).apply();
                    HelperGeneric.logI(TAG, "AAA:005");
                }

                if (tox_self_get_status_message_size() > 0)
                {
                    String tmp_status = tox_self_get_status_message();
                    if (tmp_status != null)
                    {
                        if (tmp_status.length() > 0)
                        {
                            if (is_legacy_trifa_profile_status(tmp_status))
                            {
                                tmp_status = "";
                                tox_self_set_status_message(tmp_status);
                            }
                            global_my_status_message = tmp_status;
                            // HelperGeneric.logI(TAG, "AAA:008:" + global_my_status_message + " size=" + tox_self_get_status_message_size());
                        }
                    }
                }
                else
                {
                    tox_self_set_status_message("");
                    global_my_status_message = "";
                    HelperGeneric.logI(TAG, "AAA:010");
                }
                HelperGeneric.logI(TAG, "AAA:011");

                HelperGeneric.update_savedata_file_wrapper();

                load_and_add_all_friends();
                HelperFriend.sync_db_contacts_to_tox_core();
                HelperFriend.repair_corrupt_friend_display_names();
                remove_legacy_echobot_contact_if_present();
                DbSecretKeyStorage.persistLastWorkingDbSecretKey(context_s, MainActivity.PREF__DB_secrect_key);

                // --------------- bootstrap ---------------
                // --------------- bootstrap ---------------
                // --------------- bootstrap ---------------
                if (!old_is_tox_started)
                {
                    bootstrapping = true;
                    global_self_last_went_offline_timestamp = System.currentTimeMillis();
                    HelperGeneric.logI(TAG, "global_self_last_went_offline_timestamp[1]=" + global_self_last_went_offline_timestamp +
                               " HAVE_INTERNET_CONNECTIVITY=" + HAVE_INTERNET_CONNECTIVITY);
                    HelperGeneric.logI(TAG, "bootrapping:set to true[1]");
                    try
                    {
                        tox_notification_change(context_s, nmn2, 0, ""); // set notification to "bootstrapping"
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    try
                    {
                        TrifaToxService.write_debug_file("STARTUP__start__bootstrapping");
                        bootstrap_me(true);
                        TrifaToxService.write_debug_file("STARTUP__finish__bootstrapping");
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        HelperGeneric.logI(TAG, "bootstrap_me:001:EE:" + e.getMessage());
                    }

                    check_if_still_bootstrapping();
                }

                // --------------- bootstrap ---------------
                // --------------- bootstrap ---------------
                // --------------- bootstrap ---------------

                long tox_iteration_interval_ms = tox_iteration_interval();
                HelperGeneric.logI(TAG, "tox_iteration_interval_ms=" + tox_iteration_interval_ms);

                MainActivity.tox_iterate();

                // -------- add log friend --------
                // -------- add log friend --------
                // -------- add log friend --------
                if (DEBUG_USE_LOGFRIEND)
                {
                    boolean need_add_log_pseudo_friend = true;
                    try
                    {
                        if (get_g_opts(LOGFRIEND_ON_STARTUP_DONE_DB_KEY) != null)
                        {
                            if (get_g_opts(LOGFRIEND_ON_STARTUP_DONE_DB_KEY).equals("true"))
                            {
                                if (get_g_opts(LOGFRIEND_TOXID_DB_KEY) != null)
                                {
                                    if (get_g_opts(LOGFRIEND_TOXID_DB_KEY).length() > 2)
                                    {
                                        LOG_FRIEND_TOXID = get_g_opts(LOGFRIEND_TOXID_DB_KEY);
                                        need_add_log_pseudo_friend = false;
                                        HelperGeneric.logI(TAG, "need_add_log_pseudo_friend=false");
                                    }
                                }
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    if (need_add_log_pseudo_friend)
                    {
                        HelperGeneric.logI(TAG, "need_add_log_pseudo_friend:start");

                        ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
                        MainActivity.tox_messagev3_get_new_message_id(hash_bytes);
                        LOG_FRIEND_TOXID = bytebuffer_to_hexstring(hash_bytes, true);
                        if (LOG_FRIEND_TOXID == null)
                        {
                            del_g_opts(LOGFRIEND_ON_STARTUP_DONE_DB_KEY);
                            del_g_opts(LOGFRIEND_TOXID_DB_KEY);
                            HelperGeneric.logI(TAG, "need_add_log_pseudo_friend:some error generating the ID for log_pseudo_friend");
                        }
                        else
                        {
                            HelperGeneric.logI(TAG, "need_add_log_pseudo_friend:LOG_FRIEND_TOXID=" + LOG_FRIEND_TOXID + " len=" +
                                       LOG_FRIEND_TOXID.length());
                            add_friend_real_norequest(LOG_FRIEND_TOXID);
                            set_g_opts(LOGFRIEND_ON_STARTUP_DONE_DB_KEY, "true");
                            set_g_opts(LOGFRIEND_TOXID_DB_KEY, LOG_FRIEND_TOXID);
                            // HelperGeneric.logI(TAG, "need_add_log_pseudo_friend:get:" + LOG_FRIEND_TOXID + " :: " + (LOG_FRIEND_TOXID.substring(0, 32 * 2).toUpperCase()));
                            FriendList f_log_friend = main_get_friend(
                                    LOG_FRIEND_TOXID.substring(0, 32 * 2).toUpperCase());
                            if (f_log_friend != null)
                            {
                                f_log_friend.status_message = LOG_FRIEND_INIT_STATUSMSG;
                                f_log_friend.name = LOG_FRIEND_INIT_NAME;
                                HelperFriend.update_friend_in_db_name(f_log_friend);
                                HelperFriend.update_friend_in_db_status_message(f_log_friend);
                                HelperFriend.update_single_friend_in_friendlist_view(f_log_friend);
                                HelperGeneric.logI(TAG, "need_add_log_pseudo_friend=update meta data");
                            }
                            HelperGeneric.logI(TAG, "need_add_log_pseudo_friend=true (INSERT)");
                            append_logger_msg("need_add_log_pseudo_friend=true (INSERT)");
                        }
                    }
                    else
                    {
                        if ((LOG_FRIEND_TOXID != null) && (LOG_FRIEND_TOXID.length() > 2))
                        {
                            add_friend_real_norequest(LOG_FRIEND_TOXID);
                            FriendList f_log_friend = main_get_friend(
                                    LOG_FRIEND_TOXID.substring(0, 32 * 2).toUpperCase());
                            if (f_log_friend != null)
                            {
                                f_log_friend.status_message = LOG_FRIEND_INIT_STATUSMSG;
                                f_log_friend.name = LOG_FRIEND_INIT_NAME;
                                HelperFriend.update_friend_in_db_name(f_log_friend);
                                HelperFriend.update_friend_in_db_status_message(f_log_friend);
                                HelperFriend.update_single_friend_in_friendlist_view(f_log_friend);
                                HelperGeneric.logI(TAG, "need_add_log_pseudo_friend=update meta data");
                            }
                            HelperGeneric.logI(TAG, "need_add_log_pseudo_friend=true (refresh)");
                        }
                    }
                }
                // -------- add log friend --------
                // -------- add log friend --------
                // -------- add log friend --------

                try
                {
                    load_and_add_all_conferences();
                }
                catch (Throwable e)
                {
                    e.printStackTrace();
                }

                try
                {
                    load_and_add_all_groups();
                }
                catch (Throwable e) // KHANDAQ #202: also catch Error (OOM/UnsatisfiedLinkError) so import survives
                {
                    e.printStackTrace();
                }

                global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
                global_self_last_went_offline_timestamp = System.currentTimeMillis();
                HelperGeneric.logI(TAG, "global_self_last_went_offline_timestamp[2]=" + global_self_last_went_offline_timestamp +
                           " HAVE_INTERNET_CONNECTIVITY=" + HAVE_INTERNET_CONNECTIVITY);


                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                append_logger_msg(TAG + "::" + "tox main loop START");
                tox_thread_starting_up = 1;
                while (!stop_me)
                {
                    try
                    {
                        if (tox_iteration_interval_ms < 1)
                        {
                            Thread.sleep(1);
                        }
                        else
                        {
                            if ((PREF__X_battery_saving_mode) && (battery_saving_can_sleep()))
                            {
                                need_wakeup_now = false;
                                trifa_service_thread = Thread.currentThread();

                                BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS = PREF__X_battery_saving_timeout * 1000 * 60;
                                append_logger_msg(TAG + "::" + "entering BATTERY SAVINGS MODE ... BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS=" + BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS);

                                append_logger_msg(TAG + "::" + "setting alarm ...");
                                set_alarm_for_battery_saving_sleep();

                                tox_notification_change_wrapper(TOX_CONNECTION_NONE.value, "");
                                set_all_friends_offline();
                                set_all_conferences_inactive();
                                global_self_last_went_offline_timestamp = System.currentTimeMillis();
                                global_self_connection_status = TOX_CONNECTION_NONE.value;

                                long sleep_in_sec = BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS;
                                // add some random value, so that the sleep is not always exactly the same
                                sleep_in_sec = sleep_in_sec + (int) (Math.random() * 15000d) + 5000;
                                sleep_in_sec = sleep_in_sec / 1000;
                                sleep_in_sec = sleep_in_sec / 10; // now in 10s of seconds!!
                                append_logger_msg(TAG + "::" + "entering BATTERY SAVINGS MODE ... sleep for " + (10 * sleep_in_sec) + "s");

                                for (int ii = 0; ii < sleep_in_sec; ii++)
                                {
                                    if ((global_showing_messageview) || (global_showing_anygroupview))
                                    {
                                        // if the user opens the message view, or any group view -> go online, to be able to send messages
                                        trigger_proper_wakeup_from_tox_service_thread();
                                        append_logger_msg(TAG + "::finish BATTERY SAVINGS MODE (Message view opened)");
                                        break;
                                    }

                                    if (need_wakeup_now)
                                    {
                                        trigger_proper_wakeup_from_tox_service_thread();
                                        append_logger_msg(TAG + "::" + "need_wakeup_now trigger 001");
                                        break;
                                    }

                                    try
                                    {
                                        // android OS will freeze the app (CPU cycles) here
                                        // android OS will freeze the app (CPU cycles) here
                                        Thread.sleep(10 * 1000); // sleep very long!!
                                        // android OS will freeze the app (CPU cycles) here
                                        // android OS will freeze the app (CPU cycles) here
                                    }
                                    catch (Exception es)
                                    {
                                        append_logger_msg(TAG + "::" + "BATTERY_SAVINGS_MODE__finish__interrupted");
                                        break;
                                    }
                                }
                                append_logger_msg(TAG + "::" + "finish BATTERY SAVINGS MODE, connecting again");

                                update_friends_and_groups();

                                need_wakeup_now = false;
                                trifa_service_thread = null;

                                int TOX_CONNECTION_a = tox_self_get_connection_status();
                                global_self_connection_status = TOX_CONNECTION_a;

                                bootstrapping = true;
                                global_self_last_went_offline_timestamp = System.currentTimeMillis();
                                tox_notification_change_wrapper(TOX_CONNECTION_a,"");
                                bootstrap_me(true);
                                tox_iterate();
                                check_if_still_bootstrapping();
                            }
                            else
                            {
                                Thread.sleep(tox_iteration_interval_ms);
                            }
                        }

                        // ----------
                        check_if_need_bootstrap_again();
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    // KHANDAQ #153/#202: the group-callback burst right after an import (all imported NGC
                    // groups reconnect and their members join at once) can leave an uncaught Throwable/Error
                    // here — which the global uncaught-exception handler turns into the "Khandaq crashed"
                    // screen. Catch it so the import survives and the real cause is logged, not fatal.
                    try
                    {
                        MainActivity.tox_iterate();
                        HelperGroup.maintain_all_groups();
                    }
                    catch (Throwable t)
                    {
                        t.printStackTrace();
                    }
                    ConnectionHealthMonitor.tick();

                    if ((Callstate.state != 0) || (Callstate.audio_group_active) || (Callstate.audio_ngc_group_active))
                    {
                        if ((Callstate.audio_group_active) || (Callstate.audio_ngc_group_active))
                        {
                            tox_iteration_interval_ms = 5; // if we are in a group audio call iterate more often
                        }
                        else
                        {
                            tox_iteration_interval_ms = 10; // if we are in a video/audio call iterate more often
                        }
                    }
                    else
                    {
                        if (needs_fast_group_iterate())
                        {
                            tox_iteration_interval_ms = 20;
                        }
                        else if (global_last_activity_outgoung_ft_ts > -1)
                        {
                            if ((global_last_activity_outgoung_ft_ts + 200) > System.currentTimeMillis())
                            {
                                // iterate faster if outgoing filetransfers are active
                                tox_iteration_interval_ms = 5;
                            }
                            else
                            {
                                tox_iteration_interval_ms = Math.max(TOX_MIN_NORMAL_ITERATE_DELTA_MS, MainActivity.tox_iteration_interval());
                            }
                        }
                        else if (global_last_activity_incoming_ft_ts > -1)
                        {
                            if ((global_last_activity_incoming_ft_ts + 200) > System.currentTimeMillis())
                            {
                                // iterate faster if incoming filetransfers are active
                                tox_iteration_interval_ms = 5;
                            }
                            else
                            {
                                tox_iteration_interval_ms = Math.max(TOX_MIN_NORMAL_ITERATE_DELTA_MS, MainActivity.tox_iteration_interval());
                            }
                        }
                        else
                        {
                            tox_iteration_interval_ms = Math.max(TOX_MIN_NORMAL_ITERATE_DELTA_MS, MainActivity.tox_iteration_interval());
                        }
                    }

                    if (global_self_connection_status != TOX_CONNECTION_NONE.value)
                    {
                        send_or_resend_pending_messages();
                    }

                    if (global_self_connection_status != TOX_CONNECTION_NONE.value)
                    {
                        start_queued_filetransfers();
                    }
                }
                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                // ------- MAIN TOX LOOP ---------------------------------------------------------------
                // ------- MAIN TOX LOOP ---------------------------------------------------------------

                append_logger_msg(TAG + "::" + "tox main loop stop");

                tox_thread_starting_up = 2;

                try
                {
                    Thread.sleep(100); // wait a bit, for "something" to finish up in the native code
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    NativeAudio.shutdownEngine();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    // this stops Tox
                    MainActivity.tox_kill();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    Thread.sleep(100); // wait a bit, for "something" to finish up in the native code
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                //HelperGeneric.logI(TAG, "VFS:detachThread:(TrifaToxService):" + Thread.currentThread().getId() + ":" +
                //           Thread.currentThread().getName());
                //vfs.detachThread();
                //HelperGeneric.logI(TAG, "VFS:detachThread:(TrifaToxService):OK");
            }
        };

        ToxServiceThread.start();
    }

    private void update_friends_and_groups()
    {
        // update all friends again
        try
        {
            load_and_add_all_friends();
        }
        catch (Exception e)
        {
        }
        // load conferences again
        try
        {
            load_and_add_all_conferences();
        }
        catch (Exception e)
        {
        }
        // load groups again
        try
        {
            load_and_add_all_groups();
        }
        catch (Exception e)
        {
        }
    }

    private void set_alarm_for_battery_saving_sleep()
    {
        // ---------------------------------------------------------
        Intent intent_wakeup = new Intent(getApplicationContext(), WakeupAlarmReceiver.class);
        // intentWakeFullBroacastReceiver.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        PendingIntent alarmIntent = PendingIntent.getBroadcast(getApplicationContext(), 1001,
                                                               intent_wakeup,
                                                               PendingIntent.FLAG_CANCEL_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        getApplicationContext();
        AlarmManager alarmManager = (AlarmManager) getApplicationContext().getSystemService(
                ALARM_SERVICE);


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            //alarmManager.setExactAndAllowWhileIdle(
            //        AlarmManager.ELAPSED_REALTIME_WAKEUP,
            //        SystemClock.elapsedRealtime() +
            //        BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS +
            //        (int) (Math.random() * 15000d) + 5000, alarmIntent);

            HelperGeneric.logI(TAG, "get BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS:" +
                       BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS);

            try
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                {
                    if (alarmManager.canScheduleExactAlarms())
                    {
                        HelperGeneric.logI(TAG, "canScheduleExactAlarms:true");
                    }
                    else
                    {
                        HelperGeneric.logI(TAG, "canScheduleExactAlarms:**FALSE**");
                    }
                }
            }
            catch(Exception e)
            {
            }
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                                                   System.currentTimeMillis() +
                                                   BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS +
                                                   (int) (Math.random() * 15000d) + 5000,
                                                   alarmIntent);
        }
        else
        {
            //alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            //                      SystemClock.elapsedRealtime() +
            //                      BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS +
            //                      (int) (Math.random() * 15000d) + 5000,
            //                      alarmIntent);

            alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() +
                                                           BATTERY_OPTIMIZATION_SLEEP_IN_MILLIS +
                                                           (int) (Math.random() * 15000d) +
                                                           5000, alarmIntent);
        }
    }

    private void check_if_still_bootstrapping()
    {
        try
        {
            if (tox_self_get_connection_status() != TOX_CONNECTION_NONE.value)
            {
                bootstrapping = false;
                append_logger_msg(TAG + "::check_if_still_bootstrapping:false");
                ReconnectBackoffCoordinator.get().onConnectionRestored();
                ConnectionQualityMonitor.get().onBootstrapFinished(true);
            }
            else if (bootstrapping)
            {
                ConnectionQualityMonitor.get().onBootstrapFinished(false);
            }
        }
        catch(Exception e)
        {
            append_logger_msg(TAG + "::check_if_still_bootstrapping:EE:001");
        }

        try
        {
            tox_notification_change_wrapper(tox_self_get_connection_status(),"");
            append_logger_msg(TAG + "::tox_notification_change:" + tox_self_get_connection_status());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            append_logger_msg(TAG + "::check_if_still_bootstrapping:EE:002");
        }
    }

    private void send_or_resend_pending_messages()
    {
        if ((last_resend_pending_messages4_ms + (5 * 1000)) < System.currentTimeMillis())
        {
            last_resend_pending_messages4_ms = System.currentTimeMillis();
            resend_push_for_v3_messages();
        }

        if ((last_resend_pending_messages0_ms + (30 * 1000)) < System.currentTimeMillis())
        {
            last_resend_pending_messages0_ms = System.currentTimeMillis();
            resend_old_messages(null);
        }

        if ((last_resend_pending_messages1_ms + (30 * 1000)) < System.currentTimeMillis())
        {
            last_resend_pending_messages1_ms = System.currentTimeMillis();
            resend_v3_messages(null);
        }

        if ((last_resend_pending_messages2_ms + (30 * 1000)) < System.currentTimeMillis())
        {
            last_resend_pending_messages2_ms = System.currentTimeMillis();
            resend_v2_messages(false);
        }

        if ((last_resend_pending_messages3_ms + (120 * 1000)) < System.currentTimeMillis())
        {
            last_resend_pending_messages3_ms = System.currentTimeMillis();
            resend_v2_messages(true);
        }

        if ((last_resend_unsent_text_messages_ms + (15 * 1000)) < System.currentTimeMillis())
        {
            last_resend_unsent_text_messages_ms = System.currentTimeMillis();
            resend_unsent_text_messages();
        }

        if ((last_delivery_watchdog_ms + MessageDeliveryWatchdog.WATCH_INTERVAL_MS) < System.currentTimeMillis())
        {
            last_delivery_watchdog_ms = System.currentTimeMillis();
            MessageDeliveryWatchdog.tick();
        }
    }

    private void start_queued_filetransfers()
    {
        if ((last_start_queued_fts_ms + (4 * 1000)) < System.currentTimeMillis())
        {
            last_start_queued_fts_ms = System.currentTimeMillis();
            // HelperGeneric.logI(TAG, "start_queued_outgoing_FTs ============================================");

            try
            {
                List<com.zoffcc.applications.sorm.Message> pending_ft = orma.selectFromMessage().
                        directionEq(1).
                        TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                        ft_outgoing_startedEq(false).
                        ft_outgoing_queuedEq(false).
                        stateEq(TOX_FILE_CONTROL_PAUSE.value).
                        sent_timestampLt(System.currentTimeMillis() - 1500).
                        orderBySent_timestampAsc().
                        toList();

                if ((pending_ft != null) && (pending_ft.size() > 0))
                {
                    Iterator<com.zoffcc.applications.sorm.Message> pending_it = pending_ft.iterator();
                    while (pending_it.hasNext())
                    {
                        Message m_pending = (Message) pending_it.next();
                        set_message_queueing_from_id(m_pending.id, true);
                    }
                }

                List<com.zoffcc.applications.sorm.Message> m_v1 = orma.selectFromMessage().
                        directionEq(1).
                        TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                        ft_outgoing_queuedEq(true).
                        stateNotEq(TOX_FILE_CONTROL_CANCEL.value).
                        orderBySent_timestampAsc().
                        toList();

                // HelperGeneric.logI(TAG, "start_queued_outgoing_FTs:000:" + m_v1);

                if ((m_v1 != null) && (m_v1.size() > 0))
                {
                    // HelperGeneric.logI(TAG, "start_queued_outgoing_FTs:001:" + m_v1.size());

                    final java.util.HashSet<String> started_pubkeys = new java.util.HashSet<>();
                    Iterator<com.zoffcc.applications.sorm.Message> ii = m_v1.iterator();
                    while (ii.hasNext())
                    {
                        Message m_resend_ft = (Message) ii.next();

                        if (started_pubkeys.contains(m_resend_ft.tox_friendpubkey))
                        {
                            continue;
                        }

                        if (HelperFiletransfer.has_active_outgoing_filetransfer_for_pubkey(m_resend_ft.tox_friendpubkey))
                        {
                            continue;
                        }

                        started_pubkeys.add(m_resend_ft.tox_friendpubkey);

                        if (m_resend_ft.sent_push < 1)
                        {
                            friend_call_push_url(m_resend_ft.tox_friendpubkey, m_resend_ft.sent_timestamp);
                        }
                        HelperGeneric.logI(TAG, "start_ft:sent ping to push url");
                        if (is_friend_online_real(tox_friend_by_public_key__wrapper(m_resend_ft.tox_friendpubkey)) != 0)
                        {
                            start_outgoing_ft(m_resend_ft);
                        }
                    }
                }
            }
            catch (Exception ignored)
            {
            }
        }
    }

    private void check_if_need_bootstrap_again()
    {
        if (global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            if (HAVE_INTERNET_CONNECTIVITY)
            {
                if (global_self_last_went_offline_timestamp != -1)
                {
                    if (global_self_last_went_offline_timestamp + TOX_BOOTSTRAP_AGAIN_AFTER_OFFLINE_MILLIS <
                        System.currentTimeMillis())
                    {
                        HelperGeneric.logI(TAG, "offline and we have internet connectivity --> bootstrap again ...");
                        global_self_last_went_offline_timestamp = System.currentTimeMillis();

                        bootstrapping = true;
                        HelperGeneric.logI(TAG, "bootrapping:set to true[2]");
                        try
                        {
                            tox_notification_change(context_s, nmn2, TOX_CONNECTION_NONE.value, "");
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        ReconnectBackoffCoordinator.get().scheduleReconnect(
                                ReconnectBackoffCoordinator.Reason.OFFLINE_PERIODIC, false);

                        check_if_still_bootstrapping();
                    }
                }
            }
        }
    }

    void start_me()
    {
        HelperGeneric.logI(TAG, "start_me");
        notification2 = tox_notification_setup(this, nmn2);
        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        }
        ServiceCompat.startForeground(this,
                                      HelperToxNotification.ONGOING_NOTIFICATION_ID,
                                      notification2,
                                      type);
    }

    static void bootstap_from_custom_nodes()
    {
        try
        {
            final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context_s);
            final String bs_udp_ip = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_UDP_IP, "");
            final String bs_udp_port = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_UDP_PORT, "");
            final String bs_udp_keyhex = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_UDP_KEYHEX, "");
            final String bs_tcp_ip = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_TCP_IP, "");
            final String bs_tcp_port = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_TCP_PORT, "");
            final String bs_tcp_keyhex = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_TCP_KEYHEX, "");

            if ((bs_udp_ip.length() > 0) && (bs_udp_port.length() > 0) && (IPisValid(bs_udp_ip)) &&
                (isIPPortValid(bs_udp_port)) && (is_valid_tox_public_key(bs_udp_keyhex)))
            {
                HelperGeneric.logI(TAG, "bootstap_from_custom_nodes:bootstrap_single:ip=" + bs_udp_ip + " port=" +
                           Integer.parseInt(bs_udp_port) + " key=" + bs_udp_keyhex.toUpperCase());
                int bootstrap_result = bootstrap_single_wrapper(bs_udp_ip, Integer.parseInt(bs_udp_port),
                                                                bs_udp_keyhex.toUpperCase());
                HelperGeneric.logI(TAG, "bootstap_from_custom_nodes:bootstrap_single:res=" + bootstrap_result);
            }

            if ((bs_tcp_ip.length() > 0) && (bs_tcp_port.length() > 0) && (IPisValid(bs_tcp_ip)) &&
                (isIPPortValid(bs_tcp_port)) && (is_valid_tox_public_key(bs_tcp_keyhex)))
            {
                HelperGeneric.logI(TAG, "bootstap_from_custom_nodes:add_tcp_relay_single:ip=" + bs_tcp_ip + " port=" +
                           Integer.parseInt(bs_tcp_port) + " key=" + bs_tcp_keyhex.toUpperCase());
                int bootstrap_result = HelperGeneric.add_tcp_relay_single_wrapper(bs_tcp_ip,
                                                                                  Integer.parseInt(bs_tcp_port),
                                                                                  bs_tcp_keyhex.toUpperCase());
                HelperGeneric.logI(TAG, "bootstap_from_custom_nodes:add_tcp_relay_single:res=" + bootstrap_result);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "bootstap_from_custom_nodes:EE01:" + e.getMessage());
        }
    }

    static void on_network_changed()
    {
        ReconnectBackoffCoordinator.get().scheduleReconnect(ReconnectBackoffCoordinator.Reason.NETWORK_CHANGE, true);
    }

    /** User-initiated or UI-triggered reconnect (bootstrap burst + pending flush). */
    static void requestManualReconnect()
    {
        NetworkDiagnosticsLog.log("reconnect_manual", "requested");
        ReconnectBackoffCoordinator.get().scheduleReconnect(ReconnectBackoffCoordinator.Reason.MANUAL, true);
    }

    static void on_network_reconnect_attempt(final ReconnectBackoffCoordinator.Reason reason, final int attempt)
    {
        if (orma == null || !is_tox_started)
        {
            NetworkDiagnosticsLog.log("reconnect_skip", "tox not ready reason=" + reason);
            return;
        }
        append_logger_msg(TAG + "::on_network_reconnect_attempt reason=" + reason + " attempt=" + attempt);
        NetworkDiagnosticsLog.log("reconnect_attempt", reason + " attempt=" + attempt);
        ConnectionQualityMonitor.get().onBootstrapStarted();
        global_self_last_went_offline_timestamp =
                System.currentTimeMillis() - TOX_BOOTSTRAP_AGAIN_AFTER_OFFLINE_MILLIS;
        bootstrap_me(reason == ReconnectBackoffCoordinator.Reason.NETWORK_CHANGE || attempt == 0);
        wakeup_tox_thread();
    }

    // KHANDAQ: known-good public Tox anchors, verified reachable. Tried on EVERY bootstrap so a
    // cold start is never at the mercy of the random subset (see below). Keys mirror the seed list.
    private static final String[][] KHANDAQ_BURST_UDP = {
        {"tox.initramfs.io",   "33445", "3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25"},
        {"172.104.215.182",    "33445", "DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239"},
        {"188.245.84.166",     "33445", "96B66D300BA2B59B98FC42DB1325E7092388F0379593E680ABDBEA03B9C9CE03"},
    };
    // TCP relays: port 443 first — it punches through firewalls / UDP-blocked mobile & Wi-Fi networks.
    private static final String[][] KHANDAQ_BURST_TCP = {
        {"172.104.215.182",    "443",   "DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239"},
        {"188.245.84.166",     "443",   "96B66D300BA2B59B98FC42DB1325E7092388F0379593E680ABDBEA03B9C9CE03"},
        {"tox.hidemybits.com", "443",   "5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E"},
        {"tox.initramfs.io",   "33445", "3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25"},
    };

    static void perform_khandaq_bootstrap_burst()
    {
        // Our own bootstrap*.khandaq.org nodes are gone, so the app depends entirely on the public
        // Tox DHT. bootstrap_me__real_locked() shuffles the seed list and only tries a random subset
        // (USE_MAX_NUMBER_OF_BOOTSTRAP_NODES), so a cold start can miss the few reliable,
        // firewall-friendly relays and stay stuck "connecting" — worst on UDP-throttled networks.
        // This burst ADDITIVELY pins a small set of verified public anchors that are tried on every
        // bootstrap, on top of the broad random seeding (so DHT presence is not narrowed).
        for (final String[] n : KHANDAQ_BURST_UDP)
        {
            try
            {
                bootstrap_single_wrapper(n[0], Integer.parseInt(n[1]), n[2]);
            }
            catch (Exception e)
            {
                HelperGeneric.logI(TAG, "khandaq_burst:udp:EE:" + e.getMessage());
            }
        }

        if (!PREF__force_udp_only)
        {
            for (final String[] n : KHANDAQ_BURST_TCP)
            {
                try
                {
                    HelperGeneric.add_tcp_relay_single_wrapper(n[0], Integer.parseInt(n[1]), n[2]);
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "khandaq_burst:tcp:EE:" + e.getMessage());
                }
            }
        }
    }

    // KHANDAQ: bootstrap resolves node hostnames with BLOCKING getaddrinfo and runs the native
    // bootstrap — it must NEVER run on the UI thread. Several callers reach here from a main-thread
    // Handler (ReconnectBackoffCoordinator, group-foreground nudge, network-change), which froze the
    // app >5s and ANR'd it, especially on flaky networks where reconnect fires often. Centralize the
    // guard here so EVERY caller is protected: on the main thread, run the work on a dedicated
    // single-thread executor; off the main thread (tox-service startup), keep the synchronous path.
    private static final java.util.concurrent.ExecutorService bootstrap_executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    static void bootstrap_me(boolean force)
    {
        if (Looper.myLooper() == Looper.getMainLooper())
        {
            bootstrap_executor.execute(() -> {
                try
                {
                    bootstrap_me__run(force);
                }
                catch (Throwable t)
                {
                    HelperGeneric.logI(TAG, "bootstrap_me:bg:EE:" + t.getMessage());
                }
            });
            return;
        }
        bootstrap_me__run(force);
    }

    private static void bootstrap_me__run(boolean force)
    {
        append_logger_msg(TAG + "::" + "calling bootstrap_me()");
        if (force)
        {
            global_last_bootstrap_ts = System.currentTimeMillis();
            append_logger_msg(TAG + "::" + "calling bootstrap_me__real() [force]");
            bootstrap_me__real();
            return;
        }

        if (global_last_bootstrap_ts > -1)
        {
            if ((global_last_bootstrap_ts + (TOX_BOOTSTRAP_MIN_INTERVAL_SECS * 1000)) <= System.currentTimeMillis())
            {
                final long dt = System.currentTimeMillis() - global_last_bootstrap_ts;
                append_logger_msg(TAG + "::" + "calling bootstrap_me__real() [delta time s: " + (dt / 1000) + " (min s: " + TOX_BOOTSTRAP_MIN_INTERVAL_SECS + " )]");
                global_last_bootstrap_ts = System.currentTimeMillis();
                bootstrap_me__real();
            }
        }
        else
        {
            global_last_bootstrap_ts = System.currentTimeMillis();
            append_logger_msg(TAG + "::" + "calling bootstrap_me__real() [startup]");
            bootstrap_me__real();
        }
    }

    static void bootstrap_me__real()
    {
        synchronized (bootstrapListsLock)
        {
            bootstrap_me__real_locked();
        }
    }

    static void bootstrap_me__real_locked()
    {
        HelperGeneric.logI(TAG, "bootstrap_me");

        bootstap_from_custom_nodes();
        perform_khandaq_bootstrap_burst();

        final ArrayList<com.zoffcc.applications.sorm.BootstrapNodeEntryDB> nodesSnapshot;
        synchronized (bootstrap_node_list)
        {
            get_udp_nodelist_from_db();
            nodesSnapshot = new ArrayList<>(bootstrap_node_list);
        }

        HelperGeneric.logI(TAG, "bootstrap_node_list[sort]=" + nodesSnapshot.size());
        try
        {
            Collections.shuffle(nodesSnapshot);
            Collections.shuffle(nodesSnapshot);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        HelperGeneric.logI(TAG, "bootstrap_node_list[rand]=" + nodesSnapshot.size());

        bootstrap_udp_nodes_parallel(nodesSnapshot);

        // ----- TCP ------
        final ArrayList<com.zoffcc.applications.sorm.BootstrapNodeEntryDB> tcpSnapshot;
        synchronized (tcprelay_node_list)
        {
            get_tcprelay_nodelist_from_db();
            tcpSnapshot = new ArrayList<>(tcprelay_node_list);
        }
        HelperGeneric.logI(TAG, "tcprelay_node_list[sort]=" + tcpSnapshot.size());
        try
        {
            Collections.shuffle(tcpSnapshot);
            Collections.shuffle(tcpSnapshot);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        HelperGeneric.logI(TAG, "tcprelay_node_list[rand]=" + tcpSnapshot.size());

        try
        {
            if (USE_MAX_NUMBER_OF_BOOTSTRAP_TCP_RELAYS > 0)
            {
                if (!PREF__force_udp_only)
                {
                    bootstrap_tcp_relays_parallel(tcpSnapshot);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        // ----- TCP ------

        // ----- TCP mobile ------
        // HelperGeneric.logI(TAG, "add_tcp_relay_single:res=" + MainActivity.add_tcp_relay_single_wrapper("127.0.0.1", 33447, "252E6D7F8168682363BC473C3951357FB2E28BC9A7B7E1F4CB3B302DC331BDAA".substring(0, (TOX_PUBLIC_KEY_SIZE * 2) - 0)));
        // ----- TCP mobile ------
    }

    private static final int BOOTSTRAP_PARALLEL_WORKERS = 4;

    private static void bootstrap_udp_nodes_parallel(final List<com.zoffcc.applications.sorm.BootstrapNodeEntryDB> nodes)
    {
        if (nodes == null || nodes.isEmpty())
        {
            return;
        }

        final int limit = Math.min(nodes.size(), USE_MAX_NUMBER_OF_BOOTSTRAP_NODES);
        final AtomicInteger successCount = new AtomicInteger(0);
        final ExecutorService pool = Executors.newFixedThreadPool(BOOTSTRAP_PARALLEL_WORKERS);
        final CountDownLatch latch = new CountDownLatch(limit);

        for (int i = 0; i < limit; i++)
        {
            final com.zoffcc.applications.sorm.BootstrapNodeEntryDB ee = nodes.get(i);
            pool.execute(() ->
            {
                try
                {
                    final int res = bootstrap_single_wrapper(ee.ip, ee.port, ee.key_hex);
                    if (res == 0)
                    {
                        successCount.incrementAndGet();
                    }
                }
                catch (Exception ignored)
                {
                }
                finally
                {
                    latch.countDown();
                }
            });
        }

        try
        {
            latch.await(8, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        pool.shutdownNow();
        HelperGeneric.logI(TAG, "bootstrap_udp_parallel:ok=" + successCount.get() + " tried=" + limit);
    }

    private static void bootstrap_tcp_relays_parallel(final List<com.zoffcc.applications.sorm.BootstrapNodeEntryDB> nodes)
    {
        if (nodes == null || nodes.isEmpty())
        {
            return;
        }

        final int limit = Math.min(nodes.size(), USE_MAX_NUMBER_OF_BOOTSTRAP_TCP_RELAYS);
        final AtomicInteger successCount = new AtomicInteger(0);
        final ExecutorService pool = Executors.newFixedThreadPool(BOOTSTRAP_PARALLEL_WORKERS);
        final CountDownLatch latch = new CountDownLatch(limit);

        for (int i = 0; i < limit; i++)
        {
            final com.zoffcc.applications.sorm.BootstrapNodeEntryDB ee = nodes.get(i);
            pool.execute(() ->
            {
                try
                {
                    if (ee.ip != null && ee.key_hex != null)
                    {
                        final int res = HelperGeneric.add_tcp_relay_single_wrapper(ee.ip, ee.port, ee.key_hex);
                        if (res == 0)
                        {
                            successCount.incrementAndGet();
                        }
                    }
                }
                catch (Exception ignored)
                {
                }
                finally
                {
                    latch.countDown();
                }
            });
        }

        try
        {
            latch.await(8, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        pool.shutdownNow();
        HelperGeneric.logI(TAG, "bootstrap_tcp_parallel:ok=" + successCount.get() + " tried=" + limit);
    }

    // KHANDAQ: re-push undelivered wake pings with backoff instead of ONCE. Previously a message got a
    // single push ~10s after sending; if that wake was missed (peer app killed by the OEM, FCM delayed,
    // no network for a moment) the message stayed stuck until the peer manually opened the app. Now the
    // `sent_push` int doubles as an attempt counter and we re-wake on a backoff schedule, capped, and only
    // while the message is still undelivered (read==false → we stop the moment a read/delivery receipt
    // arrives). The push relay coalesces duplicates, and a duplicate wake is harmless (it only wakes the
    // peer, it does not resend the message), so this is safe to retry.
    // KHANDAQ: attempt N is due at sent_timestamp + PUSH_WAKE_BACKOFF_MS[N]. Extended past the old 12-min
    // cliff with an hourly tail up to ~6h, so an unread message to a peer that stays offline for a long
    // time keeps getting occasional FCM wakes instead of giving up after 12 min. Stops immediately on the
    // read/delivery receipt (readEq(false) filter). Duplicate wakes are harmless (relay coalesces; a wake
    // only wakes, never resends the message).
    static final long[] PUSH_WAKE_BACKOFF_MS = {
            10_000L, 45_000L, 120_000L, 300_000L, 720_000L,        // 10s, 45s, 2m, 5m, 12m
            1_800_000L, 3_600_000L, 7_200_000L, 14_400_000L, 21_600_000L // 30m, 1h, 2h, 4h, 6h
    };
    static final int PUSH_WAKE_MAX_ATTEMPTS = PUSH_WAKE_BACKOFF_MS.length; // 10

    static void resend_push_for_v3_messages()
    {
        try
        {
            if (!PREF__use_push_service)
            {
                return;
            }

            final long now = System.currentTimeMillis();

            List<com.zoffcc.applications.sorm.Message> m_push = orma.selectFromMessage().
                    directionEq(1).
                    msg_versionEq(0).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                    sent_pushLt(PUSH_WAKE_MAX_ATTEMPTS).
                    readEq(false).
                    orderBySent_timestampAsc().
                    sent_timestampLt(now - PUSH_WAKE_BACKOFF_MS[0]).
                    toList();

            if ((m_push != null) && (m_push.size() > 0))
            {
                Iterator<com.zoffcc.applications.sorm.Message> ii = m_push.iterator();
                while (ii.hasNext())
                {
                    Message m_resend_push = (Message) ii.next();
                    if ((m_resend_push.msg_idv3_hash != null) && (m_resend_push.msg_idv3_hash.length() > 3))
                    {
                        // attempt N is due only once (sent_time + backoff[N]) has passed; sent_push is
                        // incremented on each successful push (update_message_in_db_sent_push_set).
                        final int attempt = Math.max(0, Math.min(m_resend_push.sent_push, PUSH_WAKE_BACKOFF_MS.length - 1));
                        if (now >= (m_resend_push.sent_timestamp + PUSH_WAKE_BACKOFF_MS[attempt]))
                        {
                            friend_call_push_url(m_resend_push.tox_friendpubkey, m_resend_push.sent_timestamp);
                        }
                    }
                }
            }

        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "resend_push_for_v3_messages:EE:" + e.getMessage());
        }
    }

    static void resend_v3_messages(final String friend_pubkey)
    {
        // loop through "old msg version" msgV3 1-on-1 text messages that have "resend_count < MAX_TEXTMSG_RESEND_COUNT_OLDMSG_VERSION" --------------
        try
        {
            int max_resend_count_per_iteration = 20;

            if (friend_pubkey != null)
            {
                max_resend_count_per_iteration = 20;
            }

            int cur_resend_count_per_iteration = 0;

            List<com.zoffcc.applications.sorm.Message> m_v1 = null;
            if (friend_pubkey != null)
            {
                m_v1 = orma.selectFromMessage().
                        directionEq(1).
                        msg_versionEq(0).
                        tox_friendpubkeyEq(friend_pubkey).
                        TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                        resend_countLt(MAX_TEXTMSG_RESEND_COUNT_OLDMSG_VERSION).
                        readEq(false).
                        orderBySent_timestampAsc().
                        toList();
            }
            else
            {
                m_v1 = orma.selectFromMessage().
                        directionEq(1).
                        msg_versionEq(0).
                        TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                        resend_countLt(MAX_TEXTMSG_RESEND_COUNT_OLDMSG_VERSION).
                        readEq(false).
                        orderBySent_timestampAsc().
                        toList();
            }

            if ((m_v1 != null) && (m_v1.size() > 0))
            {
                Iterator<Message> ii = m_v1.iterator();
                while (ii.hasNext())
                {
                    Message m_resend_v1 = ii.next();
                    if (friend_pubkey == null)
                    {
                        if (is_friend_online_real(tox_friend_by_public_key__wrapper(m_resend_v1.tox_friendpubkey)) == 0)
                        {
                            continue;
                        }
                    }

                    if (get_friend_msgv3_capability(m_resend_v1.tox_friendpubkey) != 1)
                    {
                        continue;
                    }

                    tox_friend_resend_msgv3_wrapper(m_resend_v1);
                    cur_resend_count_per_iteration++;
                    if (cur_resend_count_per_iteration >= max_resend_count_per_iteration)
                    {
                        break;
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "resend_v3_messages:EE:" + e.getMessage());
        }
        // loop through all pending outgoing 1-on-1 text messages --------------
    }

    static void resend_old_messages(final String friend_pubkey)
    {
        try
        {
            int max_resend_count_per_iteration = 10;

            if (friend_pubkey != null)
            {
                max_resend_count_per_iteration = 20;
            }

            int cur_resend_count_per_iteration = 0;

            // HINT: cutoff time "now" minus 25 seconds
            final long cutoff_sent_time = System.currentTimeMillis() - (25 * 1000);
            List<com.zoffcc.applications.sorm.Message> m_v0 = null;

            if (friend_pubkey != null)
            {
                // HINT: this is the generic resend for all friends, that happens in regular intervals
                //       only resend if the original sent timestamp is at least 25 seconds in the past
                //       to try to avoid resending when the read receipt is very late.
                m_v0 = orma.selectFromMessage().
                        directionEq(1).
                        TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                        msg_versionEq(0).
                        tox_friendpubkeyEq(friend_pubkey).
                        readEq(false).
                        resend_countLt(2).
                        orderBySent_timestampAsc().
                        sent_timestampLt(cutoff_sent_time).
                        toList();
            }
            else
            {
                // HINT: this is the specific resend for 1 friend only, when that friend comes online
                m_v0 = orma.selectFromMessage().
                        directionEq(1).
                        TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                        msg_versionEq(0).
                        readEq(false).
                        resend_countLt(2).
                        orderBySent_timestampAsc().
                        toList();
            }

            if ((m_v0 != null) && (m_v0.size() > 0))
            {
                Iterator<com.zoffcc.applications.sorm.Message> ii = m_v0.iterator();
                while (ii.hasNext())
                {
                    Message m_resend_v0 = (Message) ii.next();

                    if (friend_pubkey == null)
                    {
                        if (is_friend_online_real(tox_friend_by_public_key__wrapper(m_resend_v0.tox_friendpubkey)) == 0)
                        {
                            // HelperGeneric.logI(TAG, "resend_old_messages:RET:01:" +
                            //            get_friend_name_from_pubkey(m_resend_v0.tox_friendpubkey));
                            continue;
                        }
                    }

                    if (get_friend_msgv3_capability(m_resend_v0.tox_friendpubkey) == 1)
                    {
                        // HelperGeneric.logI(TAG, "resend_old_messages:RET:02:" +
                        //            get_friend_name_from_pubkey(m_resend_v0.tox_friendpubkey));
                        continue;
                    }

                    // HelperGeneric.logI(TAG, "resend_old_messages:tox_friend_resend_msgv3_wrapper:" + m_resend_v0.text + " : m=" +
                    //            m_resend_v0 + " : " + get_friend_name_from_pubkey(m_resend_v0.tox_friendpubkey));
                    tox_friend_resend_msgv3_wrapper(m_resend_v0);

                    cur_resend_count_per_iteration++;

                    if (cur_resend_count_per_iteration >= max_resend_count_per_iteration)
                    {
                        break;
                    }

                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void resend_v2_messages(boolean at_relay)
    {
        // loop through all pending outgoing 1-on-1 text messages V2 (resend) --------------
        try
        {
            final int max_resend_count_per_iteration = 10;
            int cur_resend_count_per_iteration = 0;

            List<com.zoffcc.applications.sorm.Message> m_v1 = orma.selectFromMessage().
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                    msg_versionEq(1).
                    readEq(false).
                    msg_at_relayEq(at_relay).
                    orderBySent_timestampAsc().
                    toList();


            if ((m_v1 != null) && (m_v1.size() > 0))
            {
                Iterator<com.zoffcc.applications.sorm.Message> ii = m_v1.iterator();
                while (ii.hasNext())
                {
                    Message m_resend_v2 = (Message) ii.next();

                    if (is_friend_online(tox_friend_by_public_key__wrapper(m_resend_v2.tox_friendpubkey)) == 0)
                    {
                        continue;
                    }

                    if ((m_resend_v2.msg_id_hash == null) ||
                        (m_resend_v2.msg_id_hash.equalsIgnoreCase(""))) // resend msgV2 WITHOUT hash
                    {
                        // HelperGeneric.logI(TAG, "resend_msgV2_WITHOUT_hash:f=" +
                        //           get_friend_name_from_pubkey(m_resend_v2.tox_friendpubkey) + " m=" + m_resend_v2);
                        MainActivity.send_message_result result = tox_friend_send_message_wrapper(
                                m_resend_v2.tox_friendpubkey, 0, m_resend_v2.text, (m_resend_v2.sent_timestamp / 1000));

                        if (result != null)
                        {
                            long res = result.msg_num;

                            if (HelperFriend.is_dm_message_send_success(res))
                            {
                                m_resend_v2.resend_count = 1; // we sent the message successfully
                                m_resend_v2.message_id = res;
                            }
                            else
                            {
                                m_resend_v2.resend_count = 0; // sending was NOT successfull
                                m_resend_v2.message_id = -1;
                            }

                            if (result.msg_v2)
                            {
                                m_resend_v2.msg_version = 1;
                            }
                            else
                            {
                                m_resend_v2.msg_version = 0;
                            }

                            if ((result.msg_hash_hex != null) && (!result.msg_hash_hex.equalsIgnoreCase("")))
                            {
                                // msgV2 message -----------
                                m_resend_v2.msg_id_hash = result.msg_hash_hex;
                                // msgV2 message -----------
                            }

                            if ((result.msg_hash_v3_hex != null) && (!result.msg_hash_v3_hex.equalsIgnoreCase("")))
                            {
                                // msgV3 message -----------
                                m_resend_v2.msg_idv3_hash = result.msg_hash_v3_hex;
                                // msgV3 message -----------
                            }

                            if ((result.raw_message_buf_hex != null) &&
                                (!result.raw_message_buf_hex.equalsIgnoreCase("")))
                            {
                                // save raw message bytes of this v2 msg into the database
                                // we need it if we want to resend it later
                                m_resend_v2.raw_msgv2_bytes = result.raw_message_buf_hex;
                            }

                            update_message_in_db_messageid(m_resend_v2);
                            // KHANDAQ (#193): persist the freshly minted msgv3 hash (delete/edit/
                            // reaction anchor) — it was memory-only, so those features silently
                            // skipped sending for resent messages.
                            update_message_in_db_msg_idv3_hash(m_resend_v2);
                            update_message_in_db_resend_count(m_resend_v2);
                            update_message_in_db_no_read_recvedts(m_resend_v2);
                        }
                    }
                    else // resend msgV2 with hash
                    {
                        final int raw_data_length = (m_resend_v2.raw_msgv2_bytes.length() / 2);
                        byte[] raw_msg_resend_data = hex_to_bytes(m_resend_v2.raw_msgv2_bytes);

                        ByteBuffer msg_text_buffer_resend_v2 = ByteBuffer.allocateDirect(raw_data_length);
                        msg_text_buffer_resend_v2.put(raw_msg_resend_data, 0, raw_data_length);

                        int res = tox_util_friend_resend_message_v2(
                                tox_friend_by_public_key__wrapper(m_resend_v2.tox_friendpubkey),
                                msg_text_buffer_resend_v2, raw_data_length);


                        String relay = get_relay_for_friend(m_resend_v2.tox_friendpubkey);
                        if (relay != null)
                        {
                            int res_relay = tox_util_friend_resend_message_v2(tox_friend_by_public_key__wrapper(relay),
                                                                              msg_text_buffer_resend_v2,
                                                                              raw_data_length);

                        }
                    }

                    cur_resend_count_per_iteration++;

                    if (cur_resend_count_per_iteration >= max_resend_count_per_iteration)
                    {
                        break;
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        // loop through all pending outgoing 1-on-1 text messages V2 (resend the resend) --------------
    }

    static void resend_unsent_text_messages()
    {
        try
        {
            List<com.zoffcc.applications.sorm.Message> unsent = orma.selectFromMessage().
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                    message_idEq(-1).
                    readEq(false).
                    orderBySent_timestampAsc().
                    toList();

            if ((unsent == null) || (unsent.isEmpty()))
            {
                return;
            }

            int sent_this_round = 0;
            final int max_per_round = 10;
            Iterator<com.zoffcc.applications.sorm.Message> ii = unsent.iterator();

            while (ii.hasNext())
            {
                Message m_unsent = (Message) ii.next();

                if (is_friend_online_real(tox_friend_by_public_key__wrapper(m_unsent.tox_friendpubkey)) == 0)
                {
                    if (m_unsent.sent_push < 1)
                    {
                        friend_call_push_url(m_unsent.tox_friendpubkey, m_unsent.sent_timestamp);
                        orma.updateMessage().idEq(m_unsent.id).sent_push(1).execute();
                    }
                    continue;
                }

                MainActivity.send_message_result result = tox_friend_send_message_wrapper(
                        m_unsent.tox_friendpubkey, 0, m_unsent.text, (m_unsent.sent_timestamp / 1000));

                if (result == null)
                {
                    continue;
                }

                long res = result.msg_num;
                if (HelperFriend.is_dm_message_send_success(res))
                {
                    m_unsent.resend_count = 1;
                    m_unsent.message_id = res;
                }
                else
                {
                    m_unsent.resend_count = 0;
                    m_unsent.message_id = -1;
                }

                if (result.msg_v2)
                {
                    m_unsent.msg_version = 1;
                }

                if ((result.msg_hash_hex != null) && (!result.msg_hash_hex.isEmpty()))
                {
                    m_unsent.msg_id_hash = result.msg_hash_hex;
                }

                if ((result.msg_hash_v3_hex != null) && (!result.msg_hash_v3_hex.isEmpty()))
                {
                    m_unsent.msg_idv3_hash = result.msg_hash_v3_hex;
                }

                if ((result.raw_message_buf_hex != null) && (!result.raw_message_buf_hex.isEmpty()))
                {
                    m_unsent.raw_msgv2_bytes = result.raw_message_buf_hex;
                }

                update_message_in_db_messageid(m_unsent);
                // KHANDAQ (#193): PERSIST the msgv3 hash the resend just minted. It was only kept
                // in memory, so a later delete-for-both / edit / reaction re-read the row from the
                // DB, found an empty anchor and silently skipped sending the wire packet — the
                // peer never saw the delete. (The recipient stored THIS hash, so it must match.)
                update_message_in_db_msg_idv3_hash(m_unsent);
                update_message_in_db_resend_count(m_unsent);
                update_message_in_db_no_read_recvedts(m_unsent);

                sent_this_round++;
                if (sent_this_round >= max_per_round)
                {
                    break;
                }
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "resend_unsent_text_messages:EE:" + e.getMessage());
        }
    }

    private static volatile long last_wakeup_ms = 0L;

    static void wakeup_tox_thread()
    {
        // Coalesce rapid wakeups. Group maintenance / resend paths call this many times per second
        // (one per group per pass), which floods logs, burns CPU and churns the tox iterate thread.
        // A wakeup only needs to nudge the thread to iterate sooner, so collapsing bursts to at most
        // one per 150ms is safe and keeps iteration at a healthy, steady cadence.
        final long now = System.currentTimeMillis();
        if (now - last_wakeup_ms < 150L)
        {
            return;
        }
        last_wakeup_ms = now;
        // This will wakeup the tox_iterate() thread and go online as quick as possible
        // only useful if in Batterysavings-Mode
        try
        {
            if (trifa_service_thread != null)
            {
                trigger_proper_wakeup_outside_tox_service_thread();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onUnbind(Intent intent)
    {
        HelperGeneric.logI(TAG, "onUnbind");
        return super.onUnbind(intent);
    }

    @Override
    public void unbindService(ServiceConnection conn)
    {
        HelperGeneric.logI(TAG, "unbindService");
        super.unbindService(conn);
    }

    @Override
    public void onTaskRemoved(Intent rootIntent)
    {
        // KHANDAQ (#2 fix): the app was swiped away from recents. Stop global voice playback so a
        // voice note does not keep playing after the app is fully closed (this foreground service
        // otherwise keeps the process — and the static MediaPlayer — alive).
        try
        {
            ChatVoicePlaybackManager.releaseAll();
        }
        catch (Throwable e)
        {
            e.printStackTrace();
        }
        HelperGeneric.logI(TAG, "onTaskRemoved: released voice playback");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy()
    {
        HelperGeneric.logI(TAG, "onDestroy");
        try
        {
            ChatVoicePlaybackManager.releaseAll();
        }
        catch (Throwable ignored)
        {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        HelperGeneric.logI(TAG, "onBind");
        return null;
    }

    // ------------------------------


    static String default_self_display_name(String tox_id)
    {
        if (tox_id == null)
        {
            return "Contact";
        }

        String hex = tox_id.replace(" ", "").replace("tox:", "").trim();
        if (hex.length() >= 6)
        {
            return hex.substring(hex.length() - 6).toUpperCase(java.util.Locale.ENGLISH);
        }

        if (!hex.isEmpty())
        {
            return hex.toUpperCase(java.util.Locale.ENGLISH);
        }

        return "Contact";
    }

    static boolean is_legacy_trifa_profile_name(String name)
    {
        if (name == null)
        {
            return false;
        }

        String trimmed = name.trim();
        if (trimmed.isEmpty())
        {
            return false;
        }

        return trimmed.regionMatches(true, 0, "TRIfA", 0, 5);
    }

    static boolean is_legacy_trifa_profile_status(String status)
    {
        if (status == null)
        {
            return false;
        }

        String trimmed = status.trim();
        if (trimmed.isEmpty())
        {
            return false;
        }

        return trimmed.equalsIgnoreCase("this is TRIfA") || trimmed.regionMatches(true, 0, "TRIfA", 0, 5);
    }

    // --------------- JNI ---------------
    // --------------- JNI ---------------
    // --------------- JNI ---------------
    static void logger(int level, String text)
    {
        HelperGeneric.logI(TAG, text);
    }

    /*
     * This is called by native methods to check/fix broken UTF-8 Strings
     */
    static String safe_string(byte[] in)
    {
        // HelperGeneric.logI(TAG, "safe_string:in=" + in);
        String out = "";

        try
        {
            out = new String(in, StandardCharsets.UTF_8);  // Best way to decode using "UTF-8"
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "safe_string:EE:" + e.getMessage());
            try
            {
                out = new String(in);
            }
            catch (Exception e2)
            {
                e2.printStackTrace();
                HelperGeneric.logI(TAG, "safe_string:EE2:" + e2.getMessage());
            }
        }

        // HelperGeneric.logI(TAG, "safe_string:out=" + out);
        return out;
    }
    // --------------- JNI ---------------
    // --------------- JNI ---------------
    // --------------- JNI ---------------
}

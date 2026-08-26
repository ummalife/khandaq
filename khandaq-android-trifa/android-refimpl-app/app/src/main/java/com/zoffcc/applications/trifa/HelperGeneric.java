/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2020 Zoff <zoff@zoff.cc>
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

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Notification;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.NotificationTarget;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.nativeaudio.NativeAudio;
import com.zoffcc.applications.sorm.ConferenceDB;
import com.zoffcc.applications.sorm.ConferenceMessage;
import com.zoffcc.applications.sorm.Filetransfer;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.Message;
import com.zoffcc.applications.sorm.TRIFADatabaseGlobalsNew;

import org.secuso.privacyfriendlynetmonitor.ConnectionAnalysis.Collector;
import org.secuso.privacyfriendlynetmonitor.ConnectionAnalysis.Detector;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URLConnection;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.appcompat.app.AlertDialog;
import androidx.exifinterface.media.ExifInterface;
import de.hdodenhof.circleimageview.CircleImageView;

import static android.graphics.Color.blue;
import static android.graphics.Color.green;
import static android.graphics.Color.red;
import static com.zoffcc.applications.nativeaudio.NativeAudio.set_rec_preset;
import static com.zoffcc.applications.trifa.CallingActivity.feed_h264_encoder;
import static com.zoffcc.applications.trifa.CallingActivity.fetch_from_h264_encoder;
import static com.zoffcc.applications.trifa.CallingActivity.global_sps_pps_nal_unit_bytes;
import static com.zoffcc.applications.trifa.CallingActivity.send_sps_pps_every_x_frames;
import static com.zoffcc.applications.trifa.CallingActivity.send_sps_pps_every_x_frames_current;
import static com.zoffcc.applications.trifa.CallingActivity.set_vdelay_every_x_frames;
import static com.zoffcc.applications.trifa.CallingActivity.set_vdelay_every_x_frames_current;
import static com.zoffcc.applications.trifa.Callstate.java_video_encoder_first_frame_in;
import static com.zoffcc.applications.trifa.HelperFriend.friend_call_push_url;
import static com.zoffcc.applications.trifa.HelperFriend.get_friend_msgv3_capability;
import static com.zoffcc.applications.trifa.HelperFriend.main_get_friend;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_get_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperFriend.update_friend_msgv3_capability;
import static com.zoffcc.applications.trifa.HelperMessage.process_msgv3_high_level_ack;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_messageid;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_resend_count;
import static com.zoffcc.applications.trifa.HelperMessage.update_single_message;
import static com.zoffcc.applications.trifa.HelperMsgNotification.change_msg_notification;
import static com.zoffcc.applications.trifa.MainActivity.DEBUG_USE_LOGFRIEND;
import static com.zoffcc.applications.trifa.MainActivity.PREF__DB_secrect_key;
import static com.zoffcc.applications.trifa.MainActivity.PREF__messageview_paging;
import static com.zoffcc.applications.trifa.MainActivity.PREF__video_call_quality;
import static com.zoffcc.applications.trifa.MainActivity.PREF__video_cam_resolution;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_MIN_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_BITRATE_3G_CEILING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_BITRATE_CELLULAR_CEILING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_BITRATE_WIFI_CEILING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_ENCODER_MAX_BITRATE_HIGH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_ENCODER_MAX_BITRATE_LOW;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_ENCODER_MAX_BITRATE_MED;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_ENCODER_MIN_BITRATE_HIGH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_ENCODER_MIN_BITRATE_LOW;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_ENCODER_MIN_BITRATE_MED;
import static com.zoffcc.applications.trifa.MainActivity.PREF__X_battery_saving_mode;
import static com.zoffcc.applications.trifa.MainActivity.PREF__compact_chatlist;
import static com.zoffcc.applications.trifa.MainActivity.PREF__global_font_size;
import static com.zoffcc.applications.trifa.MainActivity.VFS_CUSTOM_WRITE_CACHE;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.MainActivity.audio_buffer_2;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.toxav_option_set;
import static com.zoffcc.applications.trifa.ProfileContentFragment.update_toxid_display_s;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FAB_SCROLL_TO_BOTTOM_FADEIN_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FAB_SCROLL_TO_BOTTOM_FADEOUT_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.HAVE_INTERNET_CONNECTIVITY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOGFRIEND_TOXID_DB_KEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOG_FRIEND_TOXID;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_AVATAR_HEIGHT_COMPACT_LAYOUT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_AVATAR_HEIGHT_NORMAL_LAYOUT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_V2_MSG_SENT_OK;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_ADD;
import static com.zoffcc.applications.trifa.TRIFAGlobals.SECONDS_TO_STAY_ONLINE_IN_BATTERY_SAVINGS_MODE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_NGC_HISTORY_SYNC_MAX_PEERNAME_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_OUTGOING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_OWN_AVATAR_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_OWN_AVATAR_DIR_FILENAME_WITH_EXTENSION;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_PREFIX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_TMP_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_CODEC_H264;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_FRAME_RATE_INCOMING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_FRAME_RATE_OUTGOING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.cache_ft_fis_saf;
import static com.zoffcc.applications.trifa.TRIFAGlobals.cache_ft_fos;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_for_battery_savings_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_last_went_online_timestamp;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_showing_anygroupview;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_showing_messageview;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_OPTIONS_OPTION.TOXAV_CLIENT_VIDEO_CAPTURE_DELAY_MS;
import static com.zoffcc.applications.trifa.HelperFriend.peer_supports_msgv2;
import static com.zoffcc.applications.trifa.HelperFriend.peer_supports_msgv3;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_RESUME;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_AVATAR;
import static com.zoffcc.applications.trifa.ToxVars.TOX_HASH_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_FILETRANSFER_SIZE_MSGV2;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MESSAGE_TYPE.TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ADDRESS_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_NOSPAM_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.TrifaToxService.stop_tox_fg_done;
import static com.zoffcc.applications.trifa.TrifaToxService.trifa_service_thread;
import static com.zoffcc.applications.trifa.TrifaToxService.vfs;

public class HelperGeneric
{
    private static final String TAG = "trifa.Hlp.Generic";

    static void logI(final String tag, final String msg)
    {
        if (BuildConfig.DEBUG)
        {
            Log.i(tag, msg);
        }
    }

    /*
     all stuff here should be moved somewhere else at some point
     */
    static final Object audio_system_start_stop_lock = new Object();

    /** stop_audio_system() and stop_ngc_audio_system() are both reached from the main thread -
     *  ConferenceAudioActivity.onPause(), CallingActivity.stop_active_call() and
     *  ConfGroupAudioService.ButtonReceiver.onReceive(). An unbounded join() there waits on a thread
     *  that may never exit, which is an ANR rather than a delay; and two of them in a row must still
     *  finish well inside the 5s the system allows. A thread that does exit does so within one
     *  500ms poll of its own loop, so this is generous for the honest case. */
    static final long AUDIO_THREAD_JOIN_TIMEOUT_MS = 1200L;

    private static final Pattern PATTERN_IPV4 = Pattern.compile(
            "^(([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\.){3}([01]?\\d\\d?|2[0-4]\\d|25[0-5])$");
    private static final int INTERVAL_UPDATE_SAVE_FILE_WRAPPER_THROTTLED_MS = 200;

    static long video_frame_age_mean = 0;
    static int video_frame_age_values_cur_index = 0;
    final static int video_frame_age_values_cur_index_count = 10;
    static long[] video_frame_age_values = new long[video_frame_age_values_cur_index_count];
    static byte[] buf_video_send_frame = null;
    static long last_log_battery_savings_criteria_ts = -1;
    static long update_savedata_file_wrapper_throttled_last_trigger_ts = 0;
    static long update_savedata_file_wrapper_last_ts = 0;

    @Nullable
    static String collect_shared_text_from_intent(@Nullable final Intent intent)
    {
        if (intent == null)
        {
            return null;
        }

        final StringBuilder sb = new StringBuilder();
        try
        {
            final String extraText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (extraText != null && extraText.length() > 0)
            {
                sb.append(extraText);
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            final ArrayList<CharSequence> textList = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT);
            if (textList != null)
            {
                for (CharSequence part : textList)
                {
                    if (part == null || part.length() == 0)
                    {
                        continue;
                    }
                    if (sb.length() > 0)
                    {
                        sb.append('\n');
                    }
                    sb.append(part);
                }
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            final ClipData clipData = intent.getClipData();
            if (clipData != null)
            {
                for (int i = 0; i < clipData.getItemCount(); i++)
                {
                    final ClipData.Item item = clipData.getItemAt(i);
                    if (item == null)
                    {
                        continue;
                    }
                    CharSequence part = item.getText();
                    if (part == null && item.getUri() != null)
                    {
                        part = item.getUri().toString();
                    }
                    if (part == null || part.length() == 0)
                    {
                        continue;
                    }
                    if (sb.length() > 0)
                    {
                        sb.append('\n');
                    }
                    sb.append(part);
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    public static void clearCache_s()
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    clearCache(context_s);
                }
                catch (Exception e)
                {
                }
            }
        };

        try
        {
            if (MainActivity.main_handler_s != null)
            {
                MainActivity.main_handler_s.post(myRunnable);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void clearCache(final Context c)
    {
        Log.i(TAG, "clearCache");

        try
        {
            Glide.get(c).clearMemory();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "clearCache:EE2:" + e.getMessage());
        }

        // ------clear Glide image cache------
        final Thread t_glide_clean_cache = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Log.i(TAG, "clearCache:bg:start");
                    File cacheDir = Glide.getPhotoCacheDir(c);

                    if (cacheDir.isDirectory())
                    {
                        for (File child : cacheDir.listFiles())
                        {
                            if (!child.delete())
                            {
                            }
                            else
                            {
                                // Log.i(TAG, "clearCache:" + child.getAbsolutePath());
                            }
                        }
                    }

                    Glide.get(c).clearDiskCache();
                    Log.i(TAG, "clearCache:bg:end");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    Log.i(TAG, "clearCache:EE1:" + e.getMessage());
                }
            }
        };
        t_glide_clean_cache.start();
        // ------clear Glide image cache------
    }

    public static void cleanup_temp_dirs()
    {
        Thread t = new Thread()
        {
            @Override
            public void run()
            {
                Log.i(TAG, "cleanup_temp_dirs:---START---");

                try
                {
                    Thread.sleep(400);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                Log.i(TAG, "M:STARTUP:cleanup_temp_dirs PART 1 starting ...");

                try
                {
                    if (MainActivity.VFS_ENCRYPT)
                    {
                        Log.i(TAG, "cleanup_temp_dirs:001");
                        vfs_deleteFilesAndFilesSubDirectories_vfs(VFS_PREFIX + VFS_TMP_FILE_DIR + "/");
                        Log.i(TAG, "cleanup_temp_dirs:002");
                    }
                    else
                    {
                        Log.i(TAG, "cleanup_temp_dirs:003");
                        vfs_deleteFilesAndFilesSubDirectories_real(VFS_PREFIX + VFS_TMP_FILE_DIR + "/");
                        Log.i(TAG, "cleanup_temp_dirs:004");
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                Log.i(TAG, "M:STARTUP:cleanup_temp_dirs PART 2 starting ...");

                try
                {
                    vfs_deleteFilesAndFilesSubDirectories_real(MainActivity.SD_CARD_TMP_DIR + "/");
                }
                catch (Exception e)
                {
                    e.getMessage();
                }

                Log.i(TAG, "M:STARTUP:cleanup_temp_dirs ---READY---");

                Log.i(TAG, "cleanup_temp_dirs:---READY---");
            }
        };
        t.start();
    }

    public static void vfs_deleteFilesAndFilesSubDirectories_real(String directoryName)
    {
        File directory1 = new File(directoryName);
        File[] fList1 = directory1.listFiles();

        for (File file : fList1)
        {
            if (file.isFile())
            {
                // Log.i(TAG, "VFS:REAL:rm:" + file);
                file.delete();
            }
            else if (file.isDirectory())
            {
                // Log.i(TAG, "VFS:REAL:rm:D:" + file);
                vfs_deleteFilesAndFilesSubDirectories_real(file.getAbsolutePath());
                file.delete();
            }
        }
    }

    public static void vfs_deleteFilesAndFilesSubDirectories_vfs(String directoryName)
    {
        if (MainActivity.VFS_ENCRYPT)
        {
            Log.i(TAG, "cleanup_temp_dirs:00a");
            info.guardianproject.iocipher.File directory1 = new info.guardianproject.iocipher.File(directoryName);
            info.guardianproject.iocipher.File[] fList1 = directory1.listFiles();

            for (info.guardianproject.iocipher.File file : fList1)
            {
                if (file.isFile())
                {
                    // Log.i(TAG, "VFS:VFS:rm:" + file);
                    file.delete();
                }
                else if (file.isDirectory())
                {
                    // Log.i(TAG, "VFS:VFS:rm:D:" + file);
                    vfs_deleteFilesAndFilesSubDirectories_vfs(file.getAbsolutePath());
                    file.delete();
                }
            }

            Log.i(TAG, "cleanup_temp_dirs:00b");
        }
    }

    static void conference_message_add_from_sync(long conference_number, long peer_number2, String peer_pubkey, int a_TOX_MESSAGE_TYPE, String message, long length, long sent_timestamp_in_ms, String message_id)
    {
        // Log.i(TAG, "conference_message_add_from_sync:cf_num=" + conference_number + " pnum=" + peer_number2 + " msg=" +
        //            message);

        int res = -1;
        if (peer_number2 == -1)
        {
            res = -1;
        }
        else
        {
            res = MainActivity.tox_conference_peer_number_is_ours(conference_number, peer_number2);
        }

        if (res == 1)
        {
            // HINT: do not add our own messages, they are already in the DB!
            // Log.i(TAG, "conference_message_add_from_sync:own peer");
            return;
        }

        boolean do_notification = true;
        boolean do_badge_update = true;
        String conf_id = "-1";
        ConferenceDB conf_temp = null;
        String conference_name = null;

        try
        {
            // TODO: cache me!!
            conf_temp = (ConferenceDB) orma.selectFromConferenceDB().tox_conference_numberEq(
                    conference_number).conference_activeEq(true).toList().get(0);
            conf_id = conf_temp.conference_identifier;
            // Log.i(TAG, "conference_message_add_from_sync:conf_id=" + conf_id);
            conference_name = conf_temp.name;
        }
        catch (Exception e)
        {
            // e.printStackTrace();
        }

        try
        {
            if (conf_temp.notification_silent)
            {
                do_notification = false;
            }
        }
        catch (Exception e)
        {
            // e.printStackTrace();
        }

        if (MainActivity.conference_message_list_activity != null)
        {
            // Log.i(TAG, "conference_message_add_from_sync:noti_and_badge:002conf:" +
            //            conference_message_list_activity.get_current_conf_id() + ":" + conf_id);

            if (MainActivity.conference_message_list_activity.get_current_conf_id().equals(conf_id))
            {
                // Log.i(TAG, "noti_and_badge:003:");
                // no notifcation and no badge update
                do_notification = false;
                do_badge_update = false;
            }
        }

        ConferenceMessage m = new ConferenceMessage();
        m.is_new = do_badge_update;
        // m.tox_friendnum = friend_number;
        m.tox_peerpubkey = peer_pubkey;
        m.direction = 0; // msg received
        m.TOX_MESSAGE_TYPE = 0;
        m.read = false;
        m.tox_peername = null;
        m.conference_identifier = conf_id;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.sent_timestamp = sent_timestamp_in_ms;
        m.rcvd_timestamp = System.currentTimeMillis();
        m.text = message;
        m.message_id_tox = message_id;
        m.was_synced = true;

        try
        {
            m.tox_peername = HelperConference.tox_conference_peer_get_name__wrapper(m.conference_identifier,
                                                                                    m.tox_peerpubkey);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (MainActivity.conference_message_list_activity != null)
        {
            if (MainActivity.conference_message_list_activity.get_current_conf_id().equals(conf_id))
            {
                HelperConference.insert_into_conference_message_db(m, true);
            }
            else
            {
                HelperConference.insert_into_conference_message_db(m, false);
            }
        }
        else
        {
            long new_msg_id = HelperConference.insert_into_conference_message_db(m, false);
            // Log.i(TAG, "conference_message_add_from_sync:new_msg_id=" + new_msg_id);
        }

        // HelperConference.update_single_conference_in_friendlist_view(conf_temp);
        HelperFriend.add_all_friends_clear_wrapper(0);

        if (do_notification)
        {
            change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.conference_identifier, conference_name, m.text);
        }
    }

    public static void update_friend_connection_status_helper(int a_TOX_CONNECTION, FriendList f, boolean from_relay)
    {
        // Log.i(TAG, "android_tox_callback_friend_connection_status_cb_method:ENTER");

        final long friend_number_ = tox_friend_by_public_key__wrapper(f.tox_public_key_string);
        boolean went_online = false;

        if (f.TOX_CONNECTION != a_TOX_CONNECTION)
        {
            if ((!from_relay) && (!HelperRelay.is_any_relay(f.tox_public_key_string)))
            {
                if (f.TOX_CONNECTION == TOX_CONNECTION_NONE.value)
                {
                    send_avatar_to_friend(tox_friend_by_public_key__wrapper(f.tox_public_key_string));
                }
            }

            if (a_TOX_CONNECTION == TOX_CONNECTION_NONE.value)
            {
                // ******** friend going offline ********
                // Log.i(TAG, "friend_connection_status:friend going offline:" + System.currentTimeMillis());
            }
            else
            {
                went_online = true;
                // ******** friend coming online ********
                // Log.i(TAG, "friend_connection_status:friend coming online:" + LAST_ONLINE_TIMSTAMP_ONLINE_NOW);
            }
        }

        if (!from_relay)
        {
            if (went_online)
            {
                f.last_online_timestamp_real = LAST_ONLINE_TIMSTAMP_ONLINE_NOW;
                HelperFriend.update_friend_in_db_last_online_timestamp_real(f);
            }
        }

        if (went_online)
        {
            // Log.i(TAG, "friend_connection_status:friend status seems: ONLINE");
            f.last_online_timestamp = LAST_ONLINE_TIMSTAMP_ONLINE_NOW;
            HelperFriend.update_friend_in_db_last_online_timestamp(f);
            f.TOX_CONNECTION = a_TOX_CONNECTION;
            f.TOX_CONNECTION_on_off = get_toxconnection_wrapper(f.TOX_CONNECTION);
            HelperFriend.update_friend_in_db_connection_status(f);

            try
            {
                if (MainActivity.message_list_activity != null)
                {
                    if (MainActivity.message_list_activity.get_current_friendnum() == friend_number_)
                    {
                        MainActivity.message_list_activity.set_friend_connection_status_icon();
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            HelperFriend.add_all_friends_clear_wrapper(0);
            HelperFiletransfer.on_friend_connection_available(f.tox_public_key_string);
        }
        else // went offline -------------------
        {
            // check for combined online status of (friend + possible relay)
            int status_new = a_TOX_CONNECTION;
            int combined_connection_status_ = get_combined_connection_status(f.tox_public_key_string, status_new);
            // Log.i(TAG, "friend_connection_status:friend status combined con status:" + combined_connection_status_);

            if (get_toxconnection_wrapper(combined_connection_status_) == TOX_CONNECTION_NONE.value)
            {
                // Log.i(TAG, "friend_connection_status:friend status combined: OFFLINE");
                final long offline_ts = System.currentTimeMillis();
                f.last_online_timestamp = offline_ts;
                f.last_online_timestamp_real = offline_ts;
                HelperFriend.update_friend_in_db_last_online_timestamp(f);
                HelperFriend.update_friend_in_db_last_online_timestamp_real(f);
                f.TOX_CONNECTION = combined_connection_status_;
                f.TOX_CONNECTION_on_off = get_toxconnection_wrapper(f.TOX_CONNECTION);
                HelperFriend.update_friend_in_db_connection_status(f);

                try
                {
                    if (MainActivity.message_list_activity != null)
                    {
                        if (MainActivity.message_list_activity.get_current_friendnum() == friend_number_)
                        {
                            MainActivity.message_list_activity.set_friend_connection_status_icon();
                        }
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                HelperFriend.add_all_friends_clear_wrapper(0);
            }
            else
            {
                // Log.i(TAG, "friend or relay offline, combined still ONLINE");
                HelperFriend.update_single_friend_in_friendlist_view(f);
            }
        }
    }

    public static void send_avatar_to_friend(final long friend_number_)
    {
        final Thread new_thread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    if (MainActivity.VFS_ENCRYPT)
                    {
                        String fname = get_vfs_image_filename_own_avatar();

                        // Log.i(TAG, "send_avatar_to_friend:own_avatar_filename:" + fname);

                        if (fname != null)
                        {
                            ByteBuffer avatar_bytes = file_to_bytebuffer(fname, true);

                            // Log.i(TAG, "send_avatar_to_friend:avatar_bytes:" + avatar_bytes);

                            if (avatar_bytes != null)
                            {
                                // Log.i(TAG, "android_tox_callback_friend_connection_status_cb_method:avatar_bytes=" + bytes_to_hex(avatar_bytes));
                                ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
                                int res = MainActivity.tox_hash(hash_bytes, avatar_bytes, avatar_bytes.capacity());

                                // Log.i(TAG, "send_avatar_to_friend:tox_hash:res=" + res);


                                if (res == 0)
                                {
                                    // Log.i(TAG,
                                    //       "android_tox_callback_friend_connection_status_cb_method:hash(1)=" +
                                    //       bytes_to_hex(hash_bytes));
                                    // send avatar to friend -------


                                    String avatar_filename_for_remote =
                                            "avatar" + get_g_opts("VFS_OWN_AVATAR_FILE_EXTENSION");

                                    long filenum = MainActivity.tox_file_send(friend_number_,
                                                                              TOX_FILE_KIND_AVATAR.value,
                                                                              avatar_bytes.capacity(), hash_bytes,
                                                                              avatar_filename_for_remote,
                                                                              avatar_filename_for_remote.length());

                                    if (filenum < 0)
                                    {
                                        Log.i(TAG, "Send_own_Avatar: error " + filenum);
                                        return;
                                    }

                                    //Log.i(TAG, "send_avatar_to_friend:filenum=" + filenum + " fname=" +
                                    //           avatar_filename_for_remote);
                                    // save FT to db ---------------
                                    Filetransfer ft_avatar_outgoing = new Filetransfer();
                                    ft_avatar_outgoing.tox_public_key_string = HelperFriend.tox_friend_get_public_key__wrapper(
                                            friend_number_);
                                    ft_avatar_outgoing.path_name = VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b852";
                                    ft_avatar_outgoing.file_name = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b851";
                                    ft_avatar_outgoing.direction = TRIFA_FT_DIRECTION_OUTGOING.value;
                                    ft_avatar_outgoing.file_number = filenum;
                                    ft_avatar_outgoing.kind = TOX_FILE_KIND_AVATAR.value;
                                    ft_avatar_outgoing.filesize = avatar_bytes.capacity();
                                    long rowid = HelperFiletransfer.insert_into_filetransfer_db(ft_avatar_outgoing);
                                    ft_avatar_outgoing.id = rowid;
                                }
                                else
                                {
                                    // Log.i(TAG, "send_avatar_to_friend:tox_hash res=" + res);
                                }
                            }
                        }
                    }
                    else
                    {
                        // TODO: write code
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    Log.i(TAG, "send_avatar_to_friend:EE01:" + e.getMessage());
                }
            }
        };
        new_thread.start();
    }

    public static boolean need_rotate_image_to_exif(Bitmap bitmap, String filename_with_path)
    {
        try
        {
            ExifInterface exifInterface = new ExifInterface(filename_with_path);
            int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                                            ExifInterface.ORIENTATION_UNDEFINED);
            switch (orientation)
            {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return true;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return true;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return true;
                default:
                    return false;
            }
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public static Bitmap rotate_image_to_exif(Bitmap bitmap, String filename_with_path)
    {
        try
        {
            ExifInterface exifInterface = new ExifInterface(filename_with_path);
            int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                                            ExifInterface.ORIENTATION_UNDEFINED);
            Matrix matrix = new Matrix();
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();

            // Log.i(TAG, "rotate_image_to_exif:orig w x h=" + w + " " + h);

            switch (orientation)
            {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270);
                    break;
                default:
                    return bitmap;
            }
            Bitmap out = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true);
            // Log.i(TAG, "rotate_image_to_exif:new w x h=" + out.getWidth() + " " + out.getHeight());
            return out;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return bitmap;
        }
    }

    public static Bitmap scale_bitmap_keep_aspect(Bitmap originalImage, int width, int height)
    {
        Bitmap background = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        float originalWidth = originalImage.getWidth();
        float originalHeight = originalImage.getHeight();

        // Log.i(TAG, "scale_bitmap_keep_aspect:orig w x h=" + originalWidth + " " + originalHeight);

        Canvas canvas = new Canvas(background);

        float scale = width / originalWidth;

        float xTranslation = 0.0f;
        float yTranslation = (height - originalHeight * scale) / 2.0f;

        Matrix transformation = new Matrix();
        transformation.postTranslate(xTranslation, yTranslation);
        transformation.preScale(scale, scale);

        Paint paint = new Paint();
        paint.setFilterBitmap(true);

        canvas.drawBitmap(originalImage, transformation, paint);

        // Log.i(TAG, "scale_bitmap_keep_aspect:new w x h=" + background.getWidth() + " " + background.getHeight());

        return background;
    }

    public static void send_avatar_to_all_friends()
    {
        try
        {
            List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().toList();

            if (fl != null)
            {
                if (fl.size() > 0)
                {
                    int i = 0;
                    for (i = 0; i < fl.size(); i++)
                    {
                        FriendList n = (FriendList) fl.get(i);
                        // iterate over all online friends, and send them our new avatar
                        if (n.TOX_CONNECTION != TOX_CONNECTION_NONE.value)
                        {
                            // Log.i(TAG, "select_avatar:send_avatar_to_friend:online:i=" + i);
                            send_avatar_to_friend(
                                    HelperFriend.tox_friend_by_public_key__wrapper(n.tox_public_key_string));
                        }
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    public static void del_own_avatar()
    {
        delete_vfs_file(VFS_PREFIX + VFS_OWN_AVATAR_DIR + "/", VFS_OWN_AVATAR_DIR_FILENAME_WITH_EXTENSION);
    }

    public static void set_message_accepted_from_id(long message_id)
    {
        try
        {
            orma.updateMessage().idEq(message_id).ft_accepted(true).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void delete_vfs_file(String vfs_path_name, String vfs_file_name)
    {
        info.guardianproject.iocipher.File f1 = null;

        try
        {
            f1 = new info.guardianproject.iocipher.File(vfs_path_name + "/" + vfs_file_name);

            if (f1.length() > 0)
            {
                f1.delete();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void update_display_friend_avatar(String friend_pubkey, String avatar_path_name, String avatar_file_name)
    {
        // TODO: update entry in main friendlist (if visible)
        //       or in chat view (if visible)
        HelperFriend.update_single_friend_in_friendlist_view(
                main_get_friend(tox_friend_by_public_key__wrapper(friend_pubkey)));
    }

    static void move_tmp_file_to_real_file(String src_path_name, String src_file_name, String dst_path_name, String dst_file_name)
    {
        // Log.i(TAG, "move_tmp_file_to_real_file:" + src_path_name + "/" + src_file_name + " -> " + dst_path_name + "/" +
        //           dst_file_name);
        try
        {
            if (MainActivity.VFS_ENCRYPT)
            {
                info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                        src_path_name + "/" + src_file_name);
                info.guardianproject.iocipher.File f2 = new info.guardianproject.iocipher.File(
                        dst_path_name + "/" + dst_file_name);
                info.guardianproject.iocipher.File dst_dir = new info.guardianproject.iocipher.File(
                        dst_path_name + "/");
                dst_dir.mkdirs();
                // Log.i(TAG, "move_tmp_file_to_real_file:ft.len=" + f1.length());
                f1.renameTo(f2);
            }
            else
            {
                File f1 = new File(src_path_name + "/" + src_file_name);
                File f2 = new File(dst_path_name + "/" + dst_file_name);
                File dst_dir = new File(dst_path_name + "/");
                dst_dir.mkdirs();
                f1.renameTo(f2);
            }

            // Log.i(TAG, "move_tmp_file_to_real_file:OK");
        }
        catch (Exception e)
        {
            Log.i(TAG, "move_tmp_file_to_real_file:EE:" + e.getMessage());
            // e.printStackTrace();
        }
    }

    static String get_uniq_tmp_filename(String filename_with_path, long filesize)
    {
        String ret = null;

        try
        {
            java.security.MessageDigest md5_ = java.security.MessageDigest.getInstance("MD5");
            byte[] md5_digest = md5_.digest((filesize + ":" + filename_with_path).getBytes());
            BigInteger bigInt = new BigInteger(1, md5_digest);
            StringBuilder hashtext = new StringBuilder(bigInt.toString(16));

            // Now we need to zero pad it if you actually want the full 32 chars.
            while (hashtext.length() < 32)
            {
                hashtext.insert(0, "0");
            }

            ret = hashtext.toString();
            // Log.i(TAG, "get_uniq_tmp_filename:ret=" + ret);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "get_uniq_tmp_filename:EE:" + e.getMessage());
            ret = "temp__" + System.currentTimeMillis() + (int) (Math.random() * 10000d);
        }

        return ret;
    }

    static void copy_real_file_to_vfs_file(String src_path_name, String src_file_name, String dst_path_name, String dst_file_name)
    {
        Log.i(TAG, "copy_real_file_to_vfs_file:" + src_path_name + "/" + src_file_name + " -> " + dst_path_name + "/" +
                   dst_file_name);

        try
        {
            if (MainActivity.VFS_ENCRYPT)
            {
                File f_real = new File(src_path_name + "/" + src_file_name);
                String uniq_temp_filename = get_uniq_tmp_filename(f_real.getAbsolutePath(), f_real.length());
                Log.i(TAG, "copy_real_file_to_vfs_file:uniq_temp_filename=" + uniq_temp_filename);
                info.guardianproject.iocipher.File f2 = new info.guardianproject.iocipher.File(
                        VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + uniq_temp_filename);
                info.guardianproject.iocipher.File dst_dir = new info.guardianproject.iocipher.File(
                        VFS_PREFIX + VFS_TMP_FILE_DIR + "/");
                dst_dir.mkdirs();
                java.io.FileInputStream is = null;
                info.guardianproject.iocipher.FileOutputStream os = null;

                try
                {
                    is = new java.io.FileInputStream(f_real);
                    os = new info.guardianproject.iocipher.FileOutputStream(f2);
                    byte[] buffer = new byte[1024];
                    int length;

                    while ((length = is.read(buffer)) > 0)
                    {
                        os.write(buffer, 0, length);
                    }
                }
                finally
                {
                    is.close();
                    os.close();
                }

                move_tmp_file_to_real_file(VFS_PREFIX + VFS_TMP_FILE_DIR, uniq_temp_filename, dst_path_name,
                                           dst_file_name);
            }
            else
            {
                File f_real = new File(src_path_name + "/" + src_file_name);
                String uniq_temp_filename = get_uniq_tmp_filename(f_real.getAbsolutePath(), f_real.length());
                Log.i(TAG, "copy_real_file_to_vfs_file:uniq_temp_filename=" + uniq_temp_filename);
                File f2 = new File(VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + uniq_temp_filename);
                File dst_dir = new File(VFS_PREFIX + VFS_TMP_FILE_DIR + "/");
                dst_dir.mkdirs();
                java.io.FileInputStream is = null;
                java.io.FileOutputStream os = null;

                try
                {
                    is = new java.io.FileInputStream(f_real);
                    os = new java.io.FileOutputStream(f2);
                    byte[] buffer = new byte[1024];
                    int length;

                    while ((length = is.read(buffer)) > 0)
                    {
                        os.write(buffer, 0, length);
                    }
                }
                finally
                {
                    is.close();
                    os.close();
                }

                move_tmp_file_to_real_file(VFS_PREFIX + VFS_TMP_FILE_DIR, uniq_temp_filename, dst_path_name,
                                           dst_file_name);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "copy_real_file_to_vfs_file:EE:" + e.getMessage());
            e.printStackTrace();
        }
    }

    static String make_some_static_dummy_file(Context context)
    {
        String ret = null;

        try
        {
            File dst_dir = new File(MainActivity.SD_CARD_STATIC_DIR + "/");
            dst_dir.mkdirs();
            File fout = new File(MainActivity.SD_CARD_STATIC_DIR + "/" + "__dummy__dummy_.jpg");
            java.io.FileOutputStream os = new java.io.FileOutputStream(fout);
            //            int len = 2 + 2 + 2 + 5 + 2 + 1 + 2 + 2;
            //            byte[] buffer = new byte[len];
            //
            //            int a = 0;
            //            buffer[a] = (byte) 0xff;
            //            a++;
            //            buffer[a] = (byte) 0xd8;
            //            a++;
            //
            //            buffer[a] = (byte) 0xff;
            //            a++;
            //            buffer[a] = (byte) 0xe0;
            //            a++;
            //
            //            buffer[a] = (byte) 0x0;
            //            a++;
            //            buffer[a] = (byte) 0x10;
            //            a++;
            //
            //            buffer[a] = (byte) 0x4a;
            //            a++;
            //            buffer[a] = (byte) 0x46;
            //            a++;
            //            buffer[a] = (byte) 0x49;
            //            a++;
            //            a++;
            //            buffer[a] = (byte) 0x46;
            //            a++;
            //            buffer[a] = (byte) 0x0;
            //            a++;
            //
            //            buffer[a] = (byte) 0x01;
            //            a++;
            //            buffer[a] = (byte) 0x02;
            //            a++;
            //
            //            buffer[a] = (byte) 0x0;
            //            a++;
            //
            //            buffer[a] = (byte) 0x0;
            //            a++;
            //            buffer[a] = (byte) 0x0a;
            //            a++;
            //
            //            buffer[a] = (byte) 0x0;
            //            a++;
            //            buffer[a] = (byte) 0x0a;
            //            a++;
            //
            //            os.write(buffer, 0, len);
            //            os.close();
            java.io.InputStream ins = context.getResources().openRawResource(
                    context.getResources().getIdentifier("ic_plus_sign", "drawable", context.getPackageName()));
            byte[] buffer = new byte[1024];
            int length;

            while ((length = ins.read(buffer)) > 0)
            {
                os.write(buffer, 0, length);
            }

            ins.close();
            os.close();
            ret = fout.getAbsolutePath();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return ret;
    }

    static String copy_vfs_file_to_real_file(String src_path_name, String src_file_name, String dst_path_name, String appl)
    {
        String uniq_temp_filename = null;

        try
        {
            if (MainActivity.VFS_ENCRYPT)
            {
                info.guardianproject.iocipher.File f_real = new info.guardianproject.iocipher.File(
                        src_path_name + "/" + src_file_name);
                uniq_temp_filename = get_uniq_tmp_filename(f_real.getAbsolutePath(), f_real.length()) + appl;
                //Log.i(TAG,
                //      "copy_vfs_file_to_real_file:" + src_path_name + "/" + src_file_name + " -> " + dst_path_name +
                //      "/" + uniq_temp_filename);
                File f2 = new File(dst_path_name + "/" + uniq_temp_filename);
                File dst_dir = new File(dst_path_name + "/");
                dst_dir.mkdirs();
                info.guardianproject.iocipher.FileInputStream is = null;
                java.io.FileOutputStream os = null;

                if (!f_real.exists())
                {
                    // Log.i(TAG,
                    //      "copy_vfs_file_to_real_file:" + src_path_name + "/" + src_file_name + " : does not exist");
                    return null;
                }

                try
                {
                    is = new info.guardianproject.iocipher.FileInputStream(f_real);
                    os = new java.io.FileOutputStream(f2);
                    byte[] buffer = new byte[8192];
                    int length;

                    while ((length = is.read(buffer)) > 0)
                    {
                        os.write(buffer, 0, length);
                    }
                }
                finally
                {
                    is.close();
                    os.close();
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "copy_vfs_file_to_real_file:EE:"); // + e.getMessage());
            // e.printStackTrace();
        }

        return uniq_temp_filename;
    }

    static void export_vfs_file_to_real_file(String src_path_name, String src_file_name, String dst_path_name, String dst_file_name)
    {
        // KHANDAQ (security): both the source read and the destination write are built from a stored
        // filename, and a row written by an older version may still carry a peer-supplied name. A name
        // containing ../ read a file outside the chat directory and wrote it outside the export one.
        if (HelperFiletransfer.path_inside_dir_or_null(src_path_name, src_file_name) == null)
        {
            logI(TAG, "export: refusing a source name that leaves its directory");
            return;
        }
        try
        {
            if (MainActivity.VFS_ENCRYPT)
            {
                info.guardianproject.iocipher.File f_real = vfs_file_at(src_path_name, src_file_name);
                File f2 = new File(dst_path_name + "/" + dst_file_name);
                File dst_dir = new File(dst_path_name + "/");
                dst_dir.mkdirs();
                info.guardianproject.iocipher.FileInputStream is = null;
                java.io.FileOutputStream os = null;

                if (!f_real.exists())
                {
                    return;
                }

                try
                {
                    is = new info.guardianproject.iocipher.FileInputStream(f_real);
                    os = new java.io.FileOutputStream(f2);
                    byte[] buffer = new byte[8192];
                    int length;

                    while ((length = is.read(buffer)) > 0)
                    {
                        os.write(buffer, 0, length);
                    }
                }
                finally
                {
                    if (is != null)
                    {
                        is.close();
                    }
                    if (os != null)
                    {
                        os.close();
                    }
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "export_vfs_file_to_real_file:EE:" + e.getMessage());
            e.printStackTrace();
        }
    }

    private static info.guardianproject.iocipher.File vfs_file_at(String src_path_name, String src_file_name)
    {
        String path = src_path_name;
        if ((path == null) || (path.isEmpty()))
        {
            path = "/";
        }
        if (!path.endsWith("/"))
        {
            path = path + "/";
        }
        return new info.guardianproject.iocipher.File(path + src_file_name);
    }

    private static void copy_vfs_file_to_output_stream(info.guardianproject.iocipher.File vfs_file, OutputStream os)
            throws IOException
    {
        info.guardianproject.iocipher.FileInputStream is = null;
        try
        {
            is = new info.guardianproject.iocipher.FileInputStream(vfs_file);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = is.read(buffer)) > 0)
            {
                os.write(buffer, 0, length);
            }
            os.flush();
        }
        finally
        {
            if (is != null)
            {
                is.close();
            }
        }
    }

    private static String guess_mime_type_for_export(String file_name)
    {
        if ((file_name == null) || (file_name.isEmpty()))
        {
            return "application/octet-stream";
        }

        try
        {
            final String guessed = URLConnection.guessContentTypeFromName(file_name.toLowerCase());
            if ((guessed != null) && (!guessed.isEmpty()))
            {
                return guessed;
            }
        }
        catch (Exception ignored)
        {
        }

        final String ext = MimeTypeMap.getFileExtensionFromUrl(file_name);
        if ((ext != null) && (!ext.isEmpty()))
        {
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if ((mime != null) && (!mime.isEmpty()))
            {
                return mime;
            }
        }

        return "application/octet-stream";
    }

    private static String unique_export_display_name(String file_name)
    {
        if ((file_name == null) || (file_name.trim().isEmpty()))
        {
            return "khandaq_export_" + System.currentTimeMillis();
        }

        String safe = file_name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.isEmpty())
        {
            safe = "khandaq_export_" + System.currentTimeMillis();
        }
        return safe;
    }

    private static String export_display_name_from_message_text(String message_text, String fallback_file_name)
    {
        if (message_text != null)
        {
            final int newline = message_text.indexOf('\n');
            final String first_line = ((newline >= 0) ? message_text.substring(0, newline) : message_text).trim();
            if (!first_line.isEmpty())
            {
                return unique_export_display_name(first_line);
            }
        }
        return unique_export_display_name(fallback_file_name);
    }

    private static String copy_export_extension_from_filename(String display_name, String fallback_file_name)
    {
        if ((display_name == null) || (display_name.isEmpty()))
        {
            return display_name;
        }

        final String display_ext = MimeTypeMap.getFileExtensionFromUrl(display_name);
        if ((display_ext != null) && (!display_ext.isEmpty()))
        {
            return display_name;
        }

        if ((fallback_file_name == null) || (fallback_file_name.isEmpty()))
        {
            return display_name;
        }

        final int dot = fallback_file_name.lastIndexOf('.');
        if ((dot <= 0) || (dot >= fallback_file_name.length() - 1))
        {
            return display_name;
        }

        return display_name + fallback_file_name.substring(dot);
    }

    private static String ensure_export_extension(String display_name, String mime_type)
    {
        if ((display_name == null) || (display_name.isEmpty()) || (mime_type == null))
        {
            return display_name;
        }

        final String ext = MimeTypeMap.getFileExtensionFromUrl(display_name);
        if ((ext != null) && (!ext.isEmpty()))
        {
            return display_name;
        }

        if (mime_type.startsWith("video/"))
        {
            if ("video/quicktime".equals(mime_type))
            {
                return display_name + ".mov";
            }
            return display_name + ".mp4";
        }
        if (mime_type.startsWith("audio/"))
        {
            return display_name + ".m4a";
        }
        if (mime_type.startsWith("image/"))
        {
            if ("image/png".equals(mime_type))
            {
                return display_name + ".png";
            }
            return display_name + ".jpg";
        }
        return display_name;
    }

    /**
     * Exports a VFS file to public storage visible in Gallery / Downloads.
     * @return human-readable location, e.g. "Pictures/Khandaq/photo.jpg", or null on failure
     */
    static String export_vfs_file_to_public_storage(Context context, String src_path_name, String src_file_name)
    {
        return export_vfs_file_to_public_storage(context, src_path_name, src_file_name, null);
    }

    /**
     * Exports a VFS file to public storage using an optional human-readable display name.
     */
    static String export_vfs_file_to_public_storage(Context context, String src_path_name, String src_file_name,
                                                      String preferred_display_name)
    {
        // KHANDAQ (security): both the source read and the destination write are built from a stored
        // filename, and a row written by an older version may still carry a peer-supplied name. A name
        // containing ../ read a file outside the chat directory and wrote it outside the export one.
        if (HelperFiletransfer.path_inside_dir_or_null(src_path_name, src_file_name) == null)
        {
            logI(TAG, "export: refusing a source name that leaves its directory");
            return null;
        }
        if ((context == null) || (!VFS_ENCRYPT) || (src_file_name == null) || (src_file_name.trim().isEmpty()))
        {
            return null;
        }

        try
        {
            final info.guardianproject.iocipher.File vfs_file = vfs_file_at(src_path_name, src_file_name);
            if ((!vfs_file.exists()) || (vfs_file.length() <= 0))
            {
                Log.i(TAG, "export_vfs_file_to_public_storage:missing:" + vfs_file.getAbsolutePath());
                return null;
            }

            String display_name;
            if ((preferred_display_name != null) && (!preferred_display_name.trim().isEmpty()))
            {
                display_name = export_display_name_from_message_text(preferred_display_name, src_file_name);
            }
            else
            {
                display_name = unique_export_display_name(src_file_name);
            }
            display_name = copy_export_extension_from_filename(display_name, src_file_name);
            String mime_type = guess_mime_type_for_export(display_name);
            display_name = ensure_export_extension(display_name, mime_type);
            mime_type = guess_mime_type_for_export(display_name);
            final boolean is_image = mime_type.startsWith("image/");
            final boolean is_video = mime_type.startsWith("video/");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            {
                return export_vfs_file_via_mediastore_q(context, vfs_file, display_name, mime_type, is_image, is_video);
            }
            else
            {
                return export_vfs_file_via_legacy_public_dir(context, vfs_file, display_name, mime_type, is_image,
                        is_video);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "export_vfs_file_to_public_storage:EE:" + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    static String export_display_name_for_message(String message_text, String fallback_file_name)
    {
        return export_display_name_from_message_text(message_text, fallback_file_name);
    }

    private static String export_vfs_file_via_mediastore_q(Context context,
                                                           info.guardianproject.iocipher.File vfs_file,
                                                           String display_name,
                                                           String mime_type,
                                                           boolean is_image,
                                                           boolean is_video) throws IOException
    {
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, display_name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime_type);

        final Uri collection;
        final String relative_path;
        if (is_image)
        {
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            relative_path = Environment.DIRECTORY_PICTURES + "/Khandaq";
        }
        else if (is_video)
        {
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            relative_path = Environment.DIRECTORY_MOVIES + "/Khandaq";
        }
        else
        {
            collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            relative_path = Environment.DIRECTORY_DOWNLOADS + "/Khandaq";
        }

        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relative_path);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        final Uri item_uri = context.getContentResolver().insert(collection, values);
        if (item_uri == null)
        {
            return null;
        }

        OutputStream os = null;
        try
        {
            os = context.getContentResolver().openOutputStream(item_uri);
            if (os == null)
            {
                context.getContentResolver().delete(item_uri, null, null);
                return null;
            }
            copy_vfs_file_to_output_stream(vfs_file, os);
        }
        catch (Exception e)
        {
            context.getContentResolver().delete(item_uri, null, null);
            throw e;
        }
        finally
        {
            if (os != null)
            {
                os.close();
            }
        }

        final ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(item_uri, done, null, null);

        return relative_path + "/" + display_name;
    }

    private static String export_vfs_file_via_legacy_public_dir(Context context,
                                                                info.guardianproject.iocipher.File vfs_file,
                                                                String display_name,
                                                                String mime_type,
                                                                boolean is_image,
                                                                boolean is_video) throws IOException
    {
        final String public_dir_name;
        if (is_image)
        {
            public_dir_name = Environment.DIRECTORY_PICTURES;
        }
        else if (is_video)
        {
            public_dir_name = Environment.DIRECTORY_MOVIES;
        }
        else
        {
            public_dir_name = Environment.DIRECTORY_DOWNLOADS;
        }
        final File export_dir = new File(
                Environment.getExternalStoragePublicDirectory(public_dir_name), "Khandaq");
        if ((!export_dir.exists()) && (!export_dir.mkdirs()))
        {
            return null;
        }

        File dest = new File(export_dir, display_name);
        int suffix = 1;
        while (dest.exists())
        {
            final int dot = display_name.lastIndexOf('.');
            final String base = (dot > 0) ? display_name.substring(0, dot) : display_name;
            final String ext = (dot > 0) ? display_name.substring(dot) : "";
            dest = new File(export_dir, base + "_" + suffix + ext);
            suffix++;
        }

        FileOutputStream os = null;
        try
        {
            os = new FileOutputStream(dest);
            copy_vfs_file_to_output_stream(vfs_file, os);
        }
        finally
        {
            if (os != null)
            {
                os.close();
            }
        }

        MediaScannerConnection.scanFile(context,
                new String[]{dest.getAbsolutePath()},
                new String[]{mime_type},
                null);

        return public_dir_name + "/Khandaq/" + dest.getName();
    }

    static String get_vfs_image_filename_own_avatar()
    {
        return get_g_opts("VFS_OWN_AVATAR_FNAME");
    }

    static String get_vfs_image_filename_friend_avatar(String friend_pubkey)
    {
        try
        {
            FriendList f = (FriendList) orma.selectFromFriendList().tox_public_key_stringEq(friend_pubkey).toList().get(0);
            return f.avatar_pathname + "/" + f.avatar_filename;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static String get_vfs_image_filename_friend_avatar(long friendnum)
    {
        try
        {
            FriendList f = (FriendList) orma.selectFromFriendList().tox_public_key_stringEq(
                    HelperFriend.tox_friend_get_public_key__wrapper(friendnum)).toList().get(0);

            if (f.avatar_pathname == null)
            {
                return null;
            }

            if (f.avatar_filename == null)
            {
                return null;
            }

            return f.avatar_pathname + "/" + f.avatar_filename;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static Drawable get_drawable_from_vfs_image(String vfs_image_filename)
    {
        try
        {
            if (MainActivity.VFS_ENCRYPT)
            {
                info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(vfs_image_filename);
                info.guardianproject.iocipher.FileInputStream fis = new info.guardianproject.iocipher.FileInputStream(
                        f1);
                byte[] byteArray = new byte[(int) f1.length()];
                fis.read(byteArray, 0, (int) f1.length());
                return new BitmapDrawable(BitmapFactory.decodeByteArray(byteArray, 0, (int) f1.length()));
            }
            else
            {
                File f1 = new File(vfs_image_filename);
                java.io.FileInputStream fis = new java.io.FileInputStream(f1);
                byte[] byteArray = new byte[(int) f1.length()];
                fis.read(byteArray, 0, (int) f1.length());
                return new BitmapDrawable(BitmapFactory.decodeByteArray(byteArray, 0, (int) f1.length()));
            }
        }
        catch (Exception e)
        {
            return null;
        }
    }

    private static Bitmap bitmap_from_vfs_image(String vfs_image_filename)
    {
        try
        {
            final byte[] byteArray;
            if (MainActivity.VFS_ENCRYPT)
            {
                info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(vfs_image_filename);
                if (f1.length() <= 0)
                {
                    return null;
                }
                info.guardianproject.iocipher.FileInputStream fis = new info.guardianproject.iocipher.FileInputStream(f1);
                byteArray = new byte[(int) f1.length()];
                fis.read(byteArray, 0, (int) f1.length());
                fis.close();
            }
            else
            {
                File f1 = new File(vfs_image_filename);
                if (!f1.isFile() || f1.length() <= 0)
                {
                    return null;
                }
                java.io.FileInputStream fis = new java.io.FileInputStream(f1);
                byteArray = new byte[(int) f1.length()];
                fis.read(byteArray, 0, (int) f1.length());
                fis.close();
            }
            return decode_avatar_bitmap_safe(byteArray);
        }
        catch (Throwable e)
        {
            // catch Throwable: a decode-bomb avatar throws OutOfMemoryError (an Error, not Exception),
            // which would otherwise crash the notification path on every incoming message.
            return null;
        }
    }

    /**
     * KHANDAQ (security, D-1): decode an avatar safely. A friend can send a tiny-on-disk but
     * huge-in-pixels avatar; a naive decodeByteArray then allocates gigabytes → OutOfMemoryError on
     * every notification. We read the bounds first, reject absurd dimensions, and downsample so the
     * result never blows up memory (the caller scales it to 48dp anyway).
     */
    private static Bitmap decode_avatar_bitmap_safe(final byte[] byteArray)
    {
        if (byteArray == null || byteArray.length == 0)
        {
            return null;
        }
        try
        {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true; // measure only — no allocation
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length, bounds);
            final int w = bounds.outWidth;
            final int h = bounds.outHeight;
            if (w <= 0 || h <= 0)
            {
                return null;
            }
            // reject obvious decompression bombs outright
            if (w > 8192 || h > 8192 || ((long) w * (long) h) > 16_000_000L)
            {
                return null;
            }
            final int target = 512; // generous for a 48dp notification icon
            int sample = 1;
            while ((w / sample) > target || (h / sample) > target)
            {
                sample *= 2;
            }
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.length, opts);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    private static Bitmap drawable_to_bitmap(Drawable drawable, int sizePx)
    {
        if (drawable == null)
        {
            return null;
        }
        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, sizePx, sizePx);
        drawable.draw(canvas);
        return bitmap;
    }

    static Bitmap get_friend_notification_large_icon(Context context, String friend_pubkey)
    {
        final int sizePx = (int) dp2px(48);
        if (friend_pubkey != null && HelperFriend.friendlist_has_avatar_for_pubkey(friend_pubkey))
        {
            final String vfsPath = get_vfs_image_filename_friend_avatar(friend_pubkey);
            final Bitmap avatar = bitmap_from_vfs_image(vfsPath);
            if (avatar != null)
            {
                return Bitmap.createScaledBitmap(avatar, sizePx, sizePx, true);
            }
        }
        return get_default_friend_notification_large_icon(context, friend_pubkey, sizePx);
    }

    private static Bitmap get_default_friend_notification_large_icon(Context context, String friend_pubkey, int sizePx)
    {
        int peerColorBg = context.getResources().getColor(R.color.colorPrimaryDark);
        if (friend_pubkey != null && !friend_pubkey.isEmpty())
        {
            peerColorBg = ChatColors.get_shade(
                    ChatColors.PeerAvatarColors[hash_to_bucket(friend_pubkey, ChatColors.get_size())],
                    friend_pubkey);
        }

        Bitmap bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(peerColorBg);
        circle.setBounds(0, 0, sizePx, sizePx);
        circle.draw(canvas);

        final int iconSize = (int) (sizePx * 0.72f);
        final int inset = (sizePx - iconSize) / 2;
        final Drawable icon = new IconicsDrawable(context)
                .icon(GoogleMaterial.Icon.gmd_account_circle)
                .backgroundColor(Color.TRANSPARENT)
                .color(context.getResources().getColor(R.color.colorPrimaryDark))
                .sizePx(iconSize);
        icon.setBounds(inset, inset, inset + iconSize, inset + iconSize);
        icon.draw(canvas);
        return bitmap;
    }

    static void put_vfs_image_on_imageview_real(Context c, ImageView v, Drawable placholder, String vfs_image_filename, boolean force_update, boolean is_friend_avatar, FriendList fl)
    {
        try
        {
            if (is_friend_avatar && fl != null && !ChatBubbleUiHelper.shouldLoadVfsAvatar(fl))
            {
                return;
            }

            // Log.i(TAG, "put_vfs_image_on_imageview:" + vfs_image_filename);
            if (MainActivity.VFS_ENCRYPT)
            {
                info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(vfs_image_filename);
                // KHANDAQ: the cache key must change when an image is replaced IN PLACE (same VFS path,
                // new content) — e.g. the own profile avatar. Without this, Glide's RESOURCE disk cache
                // is keyed only by the (constant) path and serves the stale image, so the profile photo
                // never updates after the user changes it. length+mtime busts the cache on every change.
                final String vfs_content_sig = vfs_image_filename + "_" + f1.length() + "_" + f1.lastModified();
                // info.guardianproject.iocipher.FileInputStream fis = new info.guardianproject.iocipher.FileInputStream(f1);

                //byte[] byteArray = new byte[(int) f1.length()];
                // fis.read(byteArray, 0, (int) f1.length());

                if (placholder == null)
                {
                    if (is_friend_avatar)
                    {
                        GlideApp.with(c).load(f1).placeholder(R.drawable.round_loading_animation).diskCacheStrategy(
                                DiskCacheStrategy.RESOURCE).signature(new com.bumptech.glide.signature.StringSignatureZ(
                                "_avatar_" + fl.avatar_pathname + "/" + fl.avatar_filename + "_" +
                                fl.avatar_update_timestamp)).skipMemoryCache(false).into(v);
                    }
                    else
                    {
                        GlideApp.with(c).load(f1).placeholder(R.drawable.round_loading_animation).diskCacheStrategy(
                                DiskCacheStrategy.RESOURCE).signature(
                                new com.bumptech.glide.signature.StringSignatureZ(vfs_content_sig)).skipMemoryCache(
                                force_update).into(v);
                    }
                }
                else
                {
                    if (is_friend_avatar)
                    {
                        GlideApp.with(c).load(f1).placeholder(placholder).diskCacheStrategy(
                                DiskCacheStrategy.RESOURCE).signature(new com.bumptech.glide.signature.StringSignatureZ(
                                "_avatar_" + fl.avatar_pathname + "/" + fl.avatar_filename + "_" +
                                fl.avatar_update_timestamp)).skipMemoryCache(false).into(v);
                    }
                    else
                    {
                        GlideApp.with(c).load(f1).placeholder(placholder).diskCacheStrategy(
                                DiskCacheStrategy.RESOURCE).signature(
                                new com.bumptech.glide.signature.StringSignatureZ(vfs_content_sig)).skipMemoryCache(
                                force_update).into(v);
                    }
                }
            }
            else
            {
                File f1 = new File(vfs_image_filename);
                java.io.FileInputStream fis = new java.io.FileInputStream(f1);
                byte[] byteArray = new byte[(int) f1.length()];
                fis.read(byteArray, 0, (int) f1.length());
                GlideApp.with(c).load(byteArray).placeholder(placholder).diskCacheStrategy(
                        DiskCacheStrategy.RESOURCE).skipMemoryCache(force_update).into(v);
            }
        }
        // KHANDAQ: Throwable, not Exception — right after a process restart the IOCipher native lib
        // may not be loaded yet and f1.length() throws UnsatisfiedLinkError (an Error), which used
        // to crash the app while merely rendering an avatar. Showing the placeholder is fine.
        catch (Throwable e)
        {
            e.printStackTrace();
            Log.i(TAG, "put_vfs_image_on_imageview:EE1:" + e.getMessage());
        }
    }

    static void put_vfs_image_on_imageview_real_remoteviews(Context c, Notification notification, int notification_id, int viewid, RemoteViews rv, Drawable placholder, String vfs_image_filename, boolean force_update, boolean is_friend_avatar, FriendList fl)
    {
        try
        {
            NotificationTarget notificationTarget = new NotificationTarget(c, viewid, rv, notification,
                                                                           notification_id);

            info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(vfs_image_filename);
            if (placholder == null)
            {
                if (is_friend_avatar)
                {
                    GlideApp.with(c).asBitmap().load(f1).placeholder(
                            R.drawable.round_loading_animation).diskCacheStrategy(DiskCacheStrategy.NONE).signature(
                            new com.bumptech.glide.signature.StringSignatureZ(
                                    "_avatar_" + fl.avatar_pathname + "/" + fl.avatar_filename + "_" +
                                    fl.avatar_update_timestamp)).skipMemoryCache(false).into(notificationTarget);
                }
                else
                {
                    GlideApp.with(c).asBitmap().load(f1).placeholder(
                            R.drawable.round_loading_animation).diskCacheStrategy(
                            DiskCacheStrategy.NONE).skipMemoryCache(force_update).into(notificationTarget);
                }
            }
            else
            {
                if (is_friend_avatar)
                {
                    GlideApp.with(c).asBitmap().load(f1).placeholder(placholder).diskCacheStrategy(
                            DiskCacheStrategy.NONE).signature(new com.bumptech.glide.signature.StringSignatureZ(
                            "_avatar_" + fl.avatar_pathname + "/" + fl.avatar_filename + "_" +
                            fl.avatar_update_timestamp)).skipMemoryCache(false).into(notificationTarget);
                }
                else
                {
                    GlideApp.with(c).asBitmap().load(f1).placeholder(placholder).diskCacheStrategy(
                            DiskCacheStrategy.NONE).skipMemoryCache(force_update).into(notificationTarget);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "put_vfs_image_on_imageview_real_remoteviews:EE1:" + e.getMessage());
        }
    }

    static String get_g_opts(String key)
    {
        if (orma == null)
        {
            // Called from onResume before the database is opened (push token load, pending FCM ack).
            // The callers already treat null as "not set" and are re-run later; throwing here only
            // produced a stack trace per resume.
            return null;
        }
        try
        {
            if (orma.selectFromTRIFADatabaseGlobalsNew().keyEq(key).count() == 1)
            {
                TRIFADatabaseGlobalsNew g_opts = (TRIFADatabaseGlobalsNew) orma.selectFromTRIFADatabaseGlobalsNew().keyEq(key).get(0);
                // Log.i(TAG, "get_g_opts:(SELECT):key=" + key);
                return g_opts.value;
            }
            else
            {
                return null;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "get_g_opts:EE1:" + e.getMessage());
            return null;
        }
    }

    static void set_g_opts(String key, String value)
    {
        try
        {
            TRIFADatabaseGlobalsNew g_opts = new TRIFADatabaseGlobalsNew();
            g_opts.key = key;
            g_opts.value = value;

            try
            {
                orma.insertIntoTRIFADatabaseGlobalsNew(g_opts);
                Log.i(TAG, "set_g_opts:(INSERT):key=" + key + " value=" + "xxxxxxxxxxxxx");
            }
            catch (Exception e)
            {
                // e.printStackTrace();
                try
                {
                    orma.updateTRIFADatabaseGlobalsNew().keyEq(key).value(value).execute();
                    Log.i(TAG, "set_g_opts:(UPDATE):key=" + key + " value=" + "xxxxxxxxxxxxxxx");
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                    Log.i(TAG, "set_g_opts:EE1:" + e2.getMessage());
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "set_g_opts:EE2:" + e.getMessage());
        }
    }

    static void del_g_opts(String key)
    {
        try
        {
            orma.deleteFromTRIFADatabaseGlobalsNew().keyEq(key).execute();
            Log.i(TAG, "del_g_opts:(DELETE):key=" + key);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "del_g_opts:EE2:" + e.getMessage());
        }
    }

    static int add_tcp_relay_single_wrapper(String ip, long port, String key_hex)
    {
        return MainActivity.add_tcp_relay_single(ip, key_hex, port);
    }

    static int bootstrap_single_wrapper(String ip, long port, String key_hex)
    {
        return MainActivity.bootstrap_single(ip, key_hex, port);
    }

    /**
     * This method converts dp unit to equivalent pixels, depending on device density.
     *
     * @param dp A value in dp (density independent pixels) unit. Which we need to convert into pixels
     * @return A float value to represent px equivalent to dp depending on device density
     */
    public static void apply_chat_input_typography(android.widget.TextView textView)
    {
        if (textView == null)
        {
            return;
        }
        try
        {
            Typeface typeface = ResourcesCompat.getFont(textView.getContext(), R.font.khandaq_chat);
            if (typeface != null)
            {
                textView.setTypeface(typeface);
            }
            else
            {
                textView.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
            }
        }
        catch (Exception ignored)
        {
            textView.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL);
        }
        textView.setLineSpacing(0, TRIFAGlobals.MESSAGE_LINE_SPACING_MULTIPLIER);
    }

    /**
     * Style the chat compose field without overriding its typeface.
     * Custom fonts on {@link com.vanniktech.emoji.EmojiEditText} break keyboard text after emoji insertion.
     */
    public static void apply_chat_input_field_style(android.widget.TextView textView)
    {
        if (textView == null)
        {
            return;
        }
        textView.setLineSpacing(0, TRIFAGlobals.MESSAGE_LINE_SPACING_MULTIPLIER);
        ChatBubbleUiHelper.apply_chat_input_field_theme(textView);
    }

    /** Strip zero-width chars that EmojiEditText can leave after emoji-only sends. */
    public static String normalize_chat_input_text(final String raw)
    {
        if (raw == null)
        {
            return "";
        }
        // KHANDAQ (#emoji): strip the stray zero-width residue EmojiEditText leaves, but do NOT blanket
        // remove U+200D (ZERO WIDTH JOINER). An INTERIOR ZWJ is what fuses multi-part emoji (family/
        // profession/flag/handshake ZWJ sequences); removing it silently split them into a different or
        // broken emoji (tester: "wrong smiley"). So strip ZWSP (U+200B) / ZWNJ (U+200C) / BOM (U+FEFF)
        // everywhere, and only trim ZWJ (with whitespace) at the string EDGES where the residue actually
        // sits -- an interior ZWJ that glues an emoji sequence is preserved.
        final String s = raw.replace("\u200B", "").replace("\u200C", "").replace("\uFEFF", "");
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '\u200D' || Character.isWhitespace(s.charAt(start))))
        {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '\u200D' || Character.isWhitespace(s.charAt(end - 1))))
        {
            end--;
        }
        return s.substring(start, end);
    }

    public static boolean has_sendable_chat_text(final CharSequence raw)
    {
        if (raw == null)
        {
            return false;
        }
        return !normalize_chat_input_text(raw.toString()).isEmpty();
    }

    public static void clear_chat_input_field(final android.widget.EditText field)
    {
        if (field == null)
        {
            return;
        }
        field.setText("");
        try
        {
            field.getEditableText().clear();
        }
        catch (Exception ignored)
        {
        }
        field.clearComposingText();
    }

    public static String dm_send_failure_reason(final long msg_num)
    {
        return HelperFriend.dm_send_failure_reason(msg_num, null);
    }

    public static String dm_send_failure_reason(final long msg_num, @Nullable final String friend_pubkey)
    {
        return HelperFriend.dm_send_failure_reason(msg_num, friend_pubkey);
    }

    public static float dp2px(float dp)
    {
        try
        {
            float px = dp * ((float) MainActivity.metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT);
            return px;
        }
        catch (Exception e)
        {
            // if there is an error, just return the input value!!
            e.printStackTrace();
            return dp;
        }
    }

    /**
     * This method converts device specific pixels to density independent pixels.
     *
     * @param px A value in px (pixels) unit. Which we need to convert into db
     * @return A float value to represent dp equivalent to px value
     */
    public static float px2dp(float px)
    {
        float dp = px / ((float) MainActivity.metrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT);
        return dp;
    }

    public static void update_fps()
    {
        if ((MainActivity.last_updated_fps + MainActivity.update_fps_every_ms) < System.currentTimeMillis())
        {
            MainActivity.last_updated_fps = System.currentTimeMillis();

            // these were updated: VIDEO_FRAME_RATE_INCOMING, VIDEO_FRAME_RATE_OUTGOING
            try
            {
                if (CallingActivity.ca != null)
                {
                    if (CallingActivity.ca.callactivity_handler != null)
                    {
                        final Runnable myRunnable = new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    CallingActivity.ca.right_top_text_3.setText(
                                            "IN   " + VIDEO_FRAME_RATE_INCOMING + " fps");
                                    CallingActivity.ca.right_top_text_4.setText(
                                            "Out " + VIDEO_FRAME_RATE_OUTGOING + " fps");
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }
                            }
                        };

                        if (CallingActivity.ca.callactivity_handler != null)
                        {
                            CallingActivity.ca.callactivity_handler.post(myRunnable);
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public static int getVideoMaxBitrateForQuality(int quality)
    {
        if (quality == 2)
        {
            return VIDEO_ENCODER_MAX_BITRATE_HIGH;
        }
        if (quality == 1)
        {
            return VIDEO_ENCODER_MAX_BITRATE_MED;
        }
        return VIDEO_ENCODER_MAX_BITRATE_LOW;
    }

    public static int getVideoMinBitrateForQuality(int quality)
    {
        if (quality == 2)
        {
            return VIDEO_ENCODER_MIN_BITRATE_HIGH;
        }
        if (quality == 1)
        {
            return VIDEO_ENCODER_MIN_BITRATE_MED;
        }
        return VIDEO_ENCODER_MIN_BITRATE_LOW;
    }

    public static int applyNetworkVideoBitrateCap(Context ctx, int bitrate)
    {
        try
        {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null)
            {
                return Math.min(bitrate, VIDEO_BITRATE_CELLULAR_CEILING);
            }
            Network network = cm.getActiveNetwork();
            if (network == null)
            {
                return Math.min(bitrate, VIDEO_BITRATE_CELLULAR_CEILING);
            }
            NetworkCapabilities nc = cm.getNetworkCapabilities(network);
            if (nc == null)
            {
                return Math.min(bitrate, VIDEO_BITRATE_CELLULAR_CEILING);
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            {
                return Math.min(bitrate, VIDEO_BITRATE_WIFI_CEILING);
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            {
                int downKbps = nc.getLinkDownstreamBandwidthKbps();
                if (downKbps > 0 && downKbps < 1500)
                {
                    return Math.min(bitrate, VIDEO_BITRATE_3G_CEILING);
                }
                return Math.min(bitrate, VIDEO_BITRATE_CELLULAR_CEILING);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return Math.min(bitrate, VIDEO_BITRATE_CELLULAR_CEILING);
    }

    public static int computeVideoBitrateForSettings(int quality, int camResolution, Context ctx)
    {
        int bitrate = getVideoMaxBitrateForQuality(quality);
        if (camResolution == 2 && quality < 2)
        {
            bitrate = Math.min((bitrate * 3) / 2, VIDEO_ENCODER_MAX_BITRATE_HIGH);
        }
        else if (camResolution == 1 && quality == 0)
        {
            bitrate = Math.min((bitrate * 4) / 3, VIDEO_ENCODER_MAX_BITRATE_MED);
        }
        if (ctx != null)
        {
            bitrate = applyNetworkVideoBitrateCap(ctx, bitrate);
        }
        return Math.max(bitrate, GLOBAL_MIN_VIDEO_BITRATE);
    }

    public static void applyGlobalVideoBitrateFromPrefs(Context ctx)
    {
        GLOBAL_VIDEO_BITRATE = computeVideoBitrateForSettings(PREF__video_call_quality, PREF__video_cam_resolution, ctx);
        if (Callstate.state != 0)
        {
            Callstate.video_bitrate = GLOBAL_VIDEO_BITRATE;
        }
        Log.i(TAG, "applyGlobalVideoBitrateFromPrefs:quality=" + PREF__video_call_quality + " cam=" +
                   PREF__video_cam_resolution + " bitrate=" + GLOBAL_VIDEO_BITRATE);
    }

    public static void applyActiveCallVideoBitrate()
    {
        if (Callstate.state == 0)
        {
            return;
        }
        try
        {
            applyGlobalVideoBitrateFromPrefs(context_s);
            long fn = tox_friend_by_public_key__wrapper(Callstate.friend_pubkey);
            if (fn >= 0)
            {
                MainActivity.toxav_bit_rate_set(fn, Callstate.audio_bitrate, Callstate.video_bitrate);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void adaptVideoBitrateFromSuggestion(long suggestedVideoBitrate)
    {
        if (Callstate.state == 0 || suggestedVideoBitrate <= 0)
        {
            return;
        }
        int qualityMax = computeVideoBitrateForSettings(PREF__video_call_quality, PREF__video_cam_resolution,
                                                          context_s);
        int qualityMin = getVideoMinBitrateForQuality(PREF__video_call_quality);
        long target = suggestedVideoBitrate;
        target = Math.min(target, qualityMax);
        target = Math.max(target, qualityMin);
        target = Math.max(target, GLOBAL_MIN_VIDEO_BITRATE);

        long current = Callstate.video_bitrate;
        if (target < current)
        {
            target = Math.max(target, (long) (current * 0.8));
        }
        else if (target > current)
        {
            target = Math.min(target, (long) (current * 1.2));
        }

        if (Math.abs(target - current) < 50)
        {
            return;
        }

        Callstate.video_bitrate = target;
        try
        {
            long fn = tox_friend_by_public_key__wrapper(Callstate.friend_pubkey);
            if (fn >= 0)
            {
                MainActivity.toxav_bit_rate_set(fn, Callstate.audio_bitrate, Callstate.video_bitrate);
                Log.i(TAG, "adaptVideoBitrateFromSuggestion:" + current + " -> " + target + " (suggested=" +
                           suggestedVideoBitrate + ")");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void update_bitrates()
    {
        // these were updated: Callstate.audio_bitrate, Callstate.video_bitrate
        try
        {
            if (CallingActivity.ca != null)
            {
                if (CallingActivity.ca.callactivity_handler != null)
                {
                    final Runnable myRunnable = new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                CallingActivity.ca.right_top_text_1.setText(
                                        "O:" + Callstate.codec_to_str(Callstate.video_out_codec) + ":" +
                                        Callstate.video_bitrate);
                                CallingActivity.ca.right_top_text_1b.setText(
                                        "I:" + Callstate.codec_to_str(Callstate.video_in_codec) + ":" +
                                        Callstate.video_in_bitrate);
                                CallingActivity.ca.right_top_text_2.setText(
                                        "AO:" + Callstate.audio_bitrate + " " + Callstate.play_delay);
                            }
                            catch (Exception e)
                            {
                                // e.printStackTrace();
                            }

                            try
                            {
                                // KHANDAQ (modern call screen): show ONLY the caller name here. This was the
                                // real source of the "Isa 300/9900/0" the user reported — update_bitrates
                                // overwrote top_text_line every tick with name + round_trip_time/play_delay/
                                // play_buffer_entries (network debug), bypassing update_top_text_line().
                                CallingActivity.top_text_line.setText(
                                        ("-1".equals(Callstate.friend_alias_name) || Callstate.friend_alias_name == null)
                                                ? "" : Callstate.friend_alias_name);
                            }
                            catch (Exception e)
                            {
                                // e.printStackTrace();
                            }

                        }
                    };

                    if (CallingActivity.ca.callactivity_handler != null)
                    {
                        CallingActivity.ca.callactivity_handler.post(myRunnable);
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static String format_timeduration_from_seconds(long seconds)
    {
        String positive = "";
        final long absSeconds = Math.abs(seconds);
        // Log.i(TAG,"format_timeduration_from_seconds:seconds="+seconds+" absSeconds="+absSeconds);
        int hours = (int) (absSeconds / 3600);

        if (hours < 1)
        {
            positive = String.format("%02d:%02d", (absSeconds % 3600) / 60, absSeconds % 60);
        }
        else
        {
            positive = String.format("%d:%02d:%02d", hours, (absSeconds % 3600) / 60, absSeconds % 60);
        }

        return seconds < 0 ? "-" + positive : positive;
    }

    public static ByteBuffer string_to_bytebuffer(String input_chars, int output_number_of_bytes)
    {
        try
        {
            ByteBuffer ret = ByteBuffer.allocateDirect(output_number_of_bytes);
            ret.rewind();
            ret.put(input_chars.getBytes());
            return ret;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return null;
    }

    public static ByteBuffer hexstring_to_bytebuffer(String in)
    {
        try
        {
            byte[] in_bytes = in.getBytes(StandardCharsets.US_ASCII);
            ByteBuffer ret = ByteBuffer.allocateDirect(in_bytes.length / 2);
            for (int i = 0; i < in_bytes.length; i = i + 2)
            {
                // Log.i(TAG, "hexstring_to_bytebuffer:i=" + i + " byte=" + in_bytes[i] + " byte2=" + in_bytes[i + 1]);
                byte b1 = in_bytes[i + 1];
                byte b2 = in_bytes[i];
                // Log.i(TAG, "hexstring_to_bytebuffer:res=" + two_hex_bytes_to_dec_int(b1, b2));
                ret.put((byte) two_hex_bytes_to_dec_int(b1, b2));
            }

            ret.rewind();
            return ret;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return null;
    }

    public static byte[] bytebuffer_to_bytearray(ByteBuffer in)
    {
        byte[] out = new byte[in.limit()];
        in.rewind();
        in.get(out);
        return out;
    }

    public static int two_hex_bytes_to_dec_int(byte b1, byte b2)
    {
        int res = 0;
        // ascii:0 .. 9 -> byte:48 ..  57
        // ascii:A .. F -> byte:65 ..  70
        // ascii:a .. f -> byte:97 .. 102
        if (48 <= b1 && b1 <= 57)
        {
            res = b1 - 48;
        }
        else if ('A' <= b1 && b1 <= 'F')
        {
            res = b1 - 65 + 10;
        }
        else if ('a' <= b1 && b1 <= 'f')
        {
            res = b1 - 97 + 10;
        }

        if (48 <= b2 && b2 <= 57)
        {
            res = res + ((b2 - 48) * 16);
        }
        else if ('A' <= b2 && b2 <= 'F')
        {
            res = res + ((b2 - 65 + 10) * 16);
        }
        else if ('a' <= b2 && b2 <= 'f')
        {
            res = res + ((b2 - 97 + 10) * 16);
        }

        return res;
    }

    public static String utf8_string_from_bytes_with_padding(final ByteBuffer buf, final int max_bytes_output,
                                                             final String default_str)
    {
        String ret = default_str;
        try
        {
            byte[] byte_buf = new byte[max_bytes_output];
            Arrays.fill(byte_buf, (byte)0x0);
            buf.rewind();
            buf.get(byte_buf);

            int start_index = 0;
            int end_index = max_bytes_output - 1;
            for(int j=0;j<max_bytes_output;j++)
            {
                if (byte_buf[j] == 0)
                {
                    start_index = j+1;
                }
                else
                {
                    break;
                }
            }

            for(int j=(max_bytes_output-1);j>=0;j--)
            {
                if (byte_buf[j] == 0)
                {
                    end_index = j;
                }
                else
                {
                    break;
                }
            }

            byte[] byte_buf_stripped = Arrays.copyOfRange(byte_buf, start_index,end_index);
            ret = new String(byte_buf_stripped, StandardCharsets.UTF_8);
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
        return ret;
    }

    public static String fourbytes_of_long_to_hex(final long in)
    {
        return String.format("%08x", in);
    }

    public static String bytebuffer_to_hexstring(ByteBuffer in, boolean upper_case)
    {
        try
        {
            in.rewind();
            StringBuilder sb = new StringBuilder("");
            while (in.hasRemaining())
            {
                if (upper_case)
                {
                    sb.append(String.format("%02X", in.get()));
                }
                else
                {
                    sb.append(String.format("%02x", in.get()));
                }
            }
            in.rewind();
            return sb.toString();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public static ByteBuffer file_to_bytebuffer(String filename_with_fullpath, boolean is_vfs)
    {
        if (is_vfs)
        {
            // Log.i(TAG, "file_to_bytebuffer:001");

            info.guardianproject.iocipher.File file = new info.guardianproject.iocipher.File(filename_with_fullpath);
            int size = (int) file.length();
            // Log.i(TAG, "file_to_bytebuffer:002:size=" + size);
            ByteBuffer ret = ByteBuffer.allocateDirect(size);
            byte[] bytes = new byte[size];

            try
            {
                BufferedInputStream buf = new BufferedInputStream(
                        new info.guardianproject.iocipher.FileInputStream(file));
                buf.read(bytes, 0, bytes.length);
                buf.close();
                ret = ret.put(bytes);
                return ret;
            }
            catch (Exception e)
            {
                // e.printStackTrace();
                Log.i(TAG, "file_to_bytebuffer:EE01:" + e.getMessage());
            }
        }

        Log.i(TAG, "file_to_bytebuffer:EE99:NULL");
        return null;
    }

    public static String bytesToHex(byte[] bytes, int start, int len)
    {
        char[] hexChars = new char[(len) * 2];
        // System.out.println("blen=" + (len));

        for (int j = start; j < (start + len); j++)
        {
            int v = bytes[j] & 0xFF;
            hexChars[(j - start) * 2] = MainActivity.hexArray[v >>> 4];
            hexChars[(j - start) * 2 + 1] = MainActivity.hexArray[v & 0x0F];
        }

        return new String(hexChars);
    }

    public static String bytes_to_hex(ByteBuffer in)
    {
        try
        {
            final StringBuilder builder = new StringBuilder();

            for (byte b : in.array())
            {
                builder.append(String.format("%02x", b));
            }

            return builder.toString();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return "*ERROR*";
    }

    public static String bytes_to_hex(byte[] in)
    {
        try
        {
            final StringBuilder builder = new StringBuilder();

            for (byte b : in)
            {
                builder.append(String.format("%02x", b));
            }

            return builder.toString();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return "*ERROR*";
    }

    public static byte[] hex_to_bytes(String s)
    {
        int len = s.length();
        byte[] data = new byte[len / 2];

        for (int i = 0; i < len; i += 2)
        {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
        }

        return data;
    }

    public static com.bumptech.glide.load.Key StringSignature2(final String in)
    {
        com.bumptech.glide.load.Key ret = new StringObjectKey(in);
        return ret;
    }

    public static Bitmap getResizedBitmap(Bitmap bm, int newHeight, int newWidth)
    {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // create a matrix for the manipulation
        Matrix matrix = new Matrix();
        // resize the bit map
        matrix.postScale(scaleWidth, scaleHeight);
        // recreate the new Bitmap
        Bitmap resizedBitmap = Bitmap.createBitmap(bm, 0, 0, width, height, matrix, false);
        return resizedBitmap;
    }

    public static PackageInfo get_my_pkg_info()
    {
        return MainActivity.packageInfo_s;
    }

    static String get_network_connections()
    {
        try
        {
            Detector.updateReportMap();
            return Collector.updateReports();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "get_network_connections:EE01:" + e.getMessage());
            return "ERROR_getting_network_connections";
        }
    }

    static boolean sync_have_internet_connectivity(final Context context)
    {
        if (context == null)
        {
            return HAVE_INTERNET_CONNECTIVITY;
        }
        try
        {
            final ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null)
            {
                return HAVE_INTERNET_CONNECTIVITY;
            }
            final Network network = cm.getActiveNetwork();
            if (network == null)
            {
                HAVE_INTERNET_CONNECTIVITY = false;
                return false;
            }
            final NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            HAVE_INTERNET_CONNECTIVITY = caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
            return HAVE_INTERNET_CONNECTIVITY;
        }
        catch (Exception e)
        {
            Log.i(TAG, "sync_have_internet_connectivity:EE:" + e.getMessage());
            return HAVE_INTERNET_CONNECTIVITY;
        }
    }

    static String long_date_time_format(long timestamp_in_millis)
    {
        try
        {
            return MainActivity.df_date_time_long.format(new Date(timestamp_in_millis));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "_Datetime_ERROR_";
        }
    }

    static String long_date_time_format_for_filename(long timestamp_in_millis)
    {
        try
        {
            return MainActivity.df_date_time_long_for_filename.format(new Date(timestamp_in_millis));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "_Datetime_ERROR_";
        }
    }

    static String long_date_time_format_or_empty(long timestamp_in_millis)
    {
        try
        {
            return MainActivity.df_date_time_long.format(new Date(timestamp_in_millis));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "";
        }
    }

    static String only_date_time_format(long timestamp_in_millis)
    {
        try
        {
            return MainActivity.df_date_only.format(new Date(timestamp_in_millis));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "_Datetime_ERROR_";
        }
    }

    // KHANDAQ (perf): format_chat_date_header runs up to ~3x per message-row bind (and per scroll
    // frame). The old body allocated 2-3 Calendars + a fresh Locale-loaded SimpleDateFormat (the
    // DateFormatSymbols month-name load is the heaviest cost) on every call. Cache the two formatters
    // and the day boundaries: boundaries are recomputed only when "now" crosses midnight, formatters
    // only when Locale.getDefault() changes. One shared scratch Calendar, guarded by a lock (all calls
    // are on the UI thread so it never contends). Behaviour-preserving: same midnight boundaries in the
    // default timezone, same Today/Yesterday/"d MMMM"/"d MMMM yyyy" outputs and same year rule.
    private static final Object DATE_HDR_LOCK = new Object();
    private static java.util.Locale dateHdrLocale = null;
    private static java.text.SimpleDateFormat dateHdrSameYearFmt = null;
    private static java.text.SimpleDateFormat dateHdrOtherYearFmt = null;
    private static java.util.Calendar dateHdrScratchCal = null;
    private static long dateHdrTodayStartMs = 0L, dateHdrTomorrowStartMs = 0L, dateHdrYesterdayStartMs = 0L;
    private static int dateHdrTodayYear = 0;

    /** Telegram-style chat date pill: Today / Yesterday / 15 June / 15 June 2024. */
    static String format_chat_date_header(final Context context, final long timestamp_in_millis)
    {
        if (context == null || timestamp_in_millis <= 0L)
        {
            return "";
        }

        try
        {
            synchronized (DATE_HDR_LOCK)
            {
                final java.util.Locale loc = java.util.Locale.getDefault();
                if (dateHdrSameYearFmt == null || !loc.equals(dateHdrLocale))
                {
                    dateHdrLocale = loc;
                    dateHdrSameYearFmt = new java.text.SimpleDateFormat("d MMMM", loc);
                    dateHdrOtherYearFmt = new java.text.SimpleDateFormat("d MMMM yyyy", loc);
                    dateHdrScratchCal = java.util.Calendar.getInstance();
                    dateHdrTomorrowStartMs = 0L; // force boundary refresh
                }

                final long now = System.currentTimeMillis();
                if (now < dateHdrTodayStartMs || now >= dateHdrTomorrowStartMs)
                {
                    final java.util.Calendar c = dateHdrScratchCal;
                    c.setTimeInMillis(now);
                    truncateCalendarToDay(c);
                    dateHdrTodayStartMs = c.getTimeInMillis();
                    dateHdrTodayYear = c.get(java.util.Calendar.YEAR);
                    c.add(java.util.Calendar.DAY_OF_YEAR, 1);
                    dateHdrTomorrowStartMs = c.getTimeInMillis();
                    c.add(java.util.Calendar.DAY_OF_YEAR, -2);
                    dateHdrYesterdayStartMs = c.getTimeInMillis();
                }

                if (timestamp_in_millis >= dateHdrTodayStartMs && timestamp_in_millis < dateHdrTomorrowStartMs)
                {
                    return context.getString(R.string.chat_date_today);
                }
                if (timestamp_in_millis >= dateHdrYesterdayStartMs && timestamp_in_millis < dateHdrTodayStartMs)
                {
                    return context.getString(R.string.chat_date_yesterday);
                }

                dateHdrScratchCal.setTimeInMillis(timestamp_in_millis);
                final int msgYear = dateHdrScratchCal.get(java.util.Calendar.YEAR);
                final java.text.SimpleDateFormat fmt = (msgYear == dateHdrTodayYear)
                        ? dateHdrSameYearFmt : dateHdrOtherYearFmt;
                return fmt.format(new Date(timestamp_in_millis));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return only_date_time_format(timestamp_in_millis);
        }
    }

    private static void truncateCalendarToDay(final java.util.Calendar calendar)
    {
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calendar.set(java.util.Calendar.MINUTE, 0);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
    }

    static String short_time_format(long timestamp_in_millis)
    {
        if (timestamp_in_millis <= 0)
        {
            return "";
        }

        try
        {
            return MainActivity.df_time_only.format(new Date(timestamp_in_millis));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "";
        }
    }

    static String format_group_message_time(final com.zoffcc.applications.sorm.GroupMessage m, final boolean outgoing)
    {
        if (m == null)
        {
            return "";
        }

        long ts;
        if (outgoing)
        {
            ts = m.sent_timestamp > 0 ? m.sent_timestamp : m.rcvd_timestamp;
        }
        else
        {
            ts = m.rcvd_timestamp > 0 ? m.rcvd_timestamp : m.sent_timestamp;
        }

        return short_time_format(ts);
    }

    static String format_chat_message_time(final com.zoffcc.applications.sorm.Message m, final boolean outgoing)
    {
        if (m == null)
        {
            return "";
        }

        long ts;
        if (outgoing)
        {
            ts = m.sent_timestamp > 0 ? m.sent_timestamp : m.rcvd_timestamp;
        }
        else
        {
            ts = m.rcvd_timestamp > 0 ? m.rcvd_timestamp : m.sent_timestamp;
        }

        return short_time_format(ts);
    }

    static String seconds_time_format_or_empty(long time_in_seconds)
    {
        try
        {
            return MainActivity.df_seconds_time.format(new Date(time_in_seconds * 1000));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "";
        }
    }

    static void waiting_for_orbot_info(final boolean enable)
    {
        if (enable)
        {
            Runnable myRunnable = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        Log.i(TAG, "waiting_for_orbot_info:" + enable);
                        MainActivity.waiting_view.setVisibility(View.VISIBLE);
                        MainActivity.waiting_image.setVisibility(View.VISIBLE);
                        MainActivity.normal_container.setVisibility(View.INVISIBLE);
                    }
                    catch (Exception e)
                    {
                        Log.i(TAG, "waiting_for_orbot_info:EE:" + e.getMessage());
                    }
                }
            };

            if (MainActivity.main_handler_s != null)
            {
                MainActivity.main_handler_s.post(myRunnable);
            }
        }
        else
        {
            Runnable myRunnable = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        Log.i(TAG, "waiting_for_orbot_info:" + enable);
                        MainActivity.waiting_view.setVisibility(View.GONE);
                        MainActivity.waiting_image.setVisibility(View.GONE);
                        MainActivity.normal_container.setVisibility(View.VISIBLE);
                    }
                    catch (Exception e)
                    {
                        Log.i(TAG, "waiting_for_orbot_info:EE:" + e.getMessage());
                    }
                }
            };

            if (MainActivity.main_handler_s != null)
            {
                MainActivity.main_handler_s.post(myRunnable);
            }
        }
    }

    static byte[] read_chunk_from_SD_file(String file_name_with_path, long position, long file_chunk_length, boolean real_file_path)
    {
        final byte[] out = new byte[(int) file_chunk_length];

        if (real_file_path)
        {
            try
            {
                RandomAccessFile raf = new RandomAccessFile(file_name_with_path, "r");
                FileChannel inChannel = raf.getChannel();
                MappedByteBuffer buffer = inChannel.map(FileChannel.MapMode.READ_ONLY, position, file_chunk_length);

                // Log.i(TAG, "read_chunk_from_SD_file:" + buffer.limit() + " <-> " + file_chunk_length);
                buffer.get(out);

                try
                {
                    inChannel.close();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    raf.close();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            catch (Exception e)
            {
                Log.w(TAG, "read_chunk_from_SD_file:EE path=" + file_name_with_path + " pos=" + position
                        + " len=" + file_chunk_length + " real=" + real_file_path + " err=" + e.getMessage());
                e.printStackTrace();
            }
        }
        else
        {
            try
            {
                PositionInputStream fis = cache_ft_fis_saf.get(file_name_with_path);
                if (fis == null)
                {
                    InputStream fis_regular = context_s.getContentResolver().openInputStream(
                            Uri.parse(file_name_with_path));
                    fis = new PositionInputStream(fis_regular);
                    if (fis != null)
                    {
                        try
                        {
                            cache_ft_fis_saf.remove(file_name_with_path);
                        }
                        catch (Exception e)
                        {
                        }
                        cache_ft_fis_saf.put(file_name_with_path, fis);
                    }

                    long actually_skipped = fis.skip(position);
                    if (actually_skipped != position)
                    {
                        Log.i(TAG, "can NOT read check at position:" + position + " got pos=" + actually_skipped);
                    }
                }
                else
                {
                    if (fis.getPosition() < position)
                    {
                        fis.skip(position - fis.getPosition());
                    }
                    else
                    {
                        if (fis.getPosition() > position)
                        {
                            fis.close();
                            cache_ft_fis_saf.remove(file_name_with_path);
                            InputStream fis_regular = context_s.getContentResolver().openInputStream(
                                    Uri.parse(file_name_with_path));
                            fis = new PositionInputStream(fis_regular);
                            if (fis != null)
                            {
                                cache_ft_fis_saf.put(file_name_with_path, fis);
                            }

                            long actually_skipped = fis.skip(position);
                            if (actually_skipped != position)
                            {
                                Log.i(TAG,
                                      "can NOT read check at position:" + position + " got pos=" + actually_skipped);
                            }
                        }
                    }
                }

                fis.read(out, 0, (int) file_chunk_length);
            }
            catch (Exception e)
            {
                Log.w(TAG, "read_chunk_from_SD_file:EE path=" + file_name_with_path + " pos=" + position
                        + " len=" + file_chunk_length + " real=" + real_file_path + " err=" + e.getMessage());
                e.printStackTrace();
            }
        }

        return out;
    }

    static void write_chunk_to_VFS_file(String file_name_with_path, long position, long file_chunk_length, final byte[] data)
    {
        if (VFS_CUSTOM_WRITE_CACHE)
        {
            write_chunk_to_VFS_file__with_custom_write_cache(file_name_with_path, position, file_chunk_length, data);
        }
        else
        {
            write_chunk_to_VFS_file__no_extra_write_cache(file_name_with_path, position, file_chunk_length, data);
        }
    }

    /**
     * Flush + close the buffered write cache for a VFS file so its tail (up to one buffer) hits disk.
     * Chunked group downloads otherwise leave the last buffered bytes in RAM — lost on app close,
     * making an already-complete file look short and re-download on next launch.
     */
    static void flush_close_vfs_write_cache(final String file_name_with_path)
    {
        if (!VFS_CUSTOM_WRITE_CACHE || file_name_with_path == null)
        {
            return;
        }
        try
        {
            final BufferedOutputStreamCustom fos = cache_ft_fos.get(file_name_with_path);
            if (fos != null)
            {
                try
                {
                    fos.flush();
                    fos.close();
                }
                catch (Exception ignored)
                {
                }
            }
        }
        catch (Exception ignored)
        {
        }
        cache_ft_fos.remove(file_name_with_path);
    }

    /** Flush (but keep open) the buffered write cache for one VFS file so written bytes hit disk. */
    static void flush_vfs_write_cache(final String file_name_with_path)
    {
        if (!VFS_CUSTOM_WRITE_CACHE || file_name_with_path == null)
        {
            return;
        }
        try
        {
            final BufferedOutputStreamCustom fos = cache_ft_fos.get(file_name_with_path);
            if (fos != null)
            {
                fos.flush();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    /** Flush all in-flight VFS download buffers to disk (e.g. when the app goes to background). */
    static void flush_all_vfs_write_caches()
    {
        if (!VFS_CUSTOM_WRITE_CACHE)
        {
            return;
        }
        try
        {
            for (final BufferedOutputStreamCustom fos : new java.util.ArrayList<>(cache_ft_fos.values()))
            {
                try
                {
                    if (fos != null)
                    {
                        fos.flush();
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
    }

    static void write_chunk_to_VFS_file__no_extra_write_cache(String file_name_with_path, long position, long file_chunk_length, final byte[] data)
    {
        try
        {
            final ByteBuffer data_bb = ByteBuffer.wrap(data);
            info.guardianproject.iocipher.RandomAccessFile raf = new info.guardianproject.iocipher.RandomAccessFile(
                    file_name_with_path, "rw");
            info.guardianproject.iocipher.IOCipherFileChannel inChannel = raf.getChannel();
            // inChannel.lseek(position, OsConstants.SEEK_SET);
            inChannel.write(data_bb, position);

            try
            {
                inChannel.close();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            try
            {
                raf.close();
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

    static void write_chunk_to_VFS_file__with_custom_write_cache(String file_name_with_path, final long position, long file_chunk_length, final byte[] data)
    {
        try
        {
            BufferedOutputStreamCustom fos = cache_ft_fos.get(file_name_with_path);
            if (fos == null)
            {
                // Log.i(TAG, "write_chunk_to_VFS_file:cache:fail");
                fos = new BufferedOutputStreamCustom(file_name_with_path);
                try
                {
                    cache_ft_fos.remove(file_name_with_path);
                }
                catch (Exception e)
                {
                }
                cache_ft_fos.put(file_name_with_path, fos);
            }
            else
            {
                // Log.i(TAG,"write_chunk_to_VFS_file:cache:HIT");
            }

            // Log.i(TAG, "write_chunk_to_VFS_file:write:pos=" + position + " len=" + file_chunk_length);
            fos.seek(position);
            fos.write(data);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static String get_fileExt(final String filename)
    {
        try
        {

            final Uri f = Uri.fromFile(new File(filename));
            return MimeTypeMap.getFileExtensionFromUrl(f.toString());
        }
        catch (Exception e)
        {
            return "";
        }
    }

    static int hash_to_bucket(String hash_value, int number_of_buckets)
    {
        try
        {
            int ret = 0;
            int value = (Integer.parseInt(hash_value.substring(hash_value.length() - 1, hash_value.length() - 0), 16) +
                         (Integer.parseInt(hash_value.substring(hash_value.length() - 2, hash_value.length() - 1), 16) *
                          16) +
                         (Integer.parseInt(hash_value.substring(hash_value.length() - 3, hash_value.length() - 2), 16) *
                          (16 * 2)) +
                         (Integer.parseInt(hash_value.substring(hash_value.length() - 4, hash_value.length() - 3), 16) *
                          (16 * 3)));

            // Log.i(TAG, "hash_to_bucket:value=" + value);

            ret = (value % number_of_buckets);

            // BigInteger bigInt = new BigInteger(1, hash_value.getBytes());
            // int ret = (int) (bigInt.longValue() % (long) number_of_buckets);
            // // Log.i(TAG, "hash_to_bucket:" + "ret=" + ret + " hash_as_int=" + bigInt + " hash=" + hash_value);
            return ret;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "hash_to_bucket:EE:" + e.getMessage());
            return 0;
        }
    }

    public static boolean isColorLight(int color)
    {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // System.out.println("HSV="+hsv[0]+" "+hsv[1]+" "+hsv[2]);
        return !(hsv[2] < 0.5);
    }

    public static float getColorLuminance(int color)
    {
        double gamma = 2.2;
        double r = Math.pow((Color.red(color) / 255.0), gamma);
        double g = Math.pow((Color.green(color) / 255.0), gamma);
        double b = Math.pow((Color.blue(color) / 255.0), gamma);

        return (float) ((0.2126 * r) + (0.7152 * g) + (0.0722 * b));
    }

    public static boolean isColorDarkLum(int color)
    {
        if (getColorLuminance(color) <= 0.5)
        {
            return true;
        }
        else
        {
            return false;
        }
    }

    public static double getColorDarkBrightness(int color)
    {
        double luminosity = Math.sqrt(Math.pow(Color.red(color), 2) * 0.299 + Math.pow(Color.green(color), 2) * 0.587 +
                                      Math.pow(Color.blue(color), 2) * 0.114);

        return luminosity;
    }

    public static boolean isColorDarkBrightness(int color)
    {
        double luminosity = Math.sqrt(Math.pow(Color.red(color), 2) * 0.299 + Math.pow(Color.green(color), 2) * 0.587 +
                                      Math.pow(Color.blue(color), 2) * 0.114);

        if (luminosity > 146)
        {
            return false;
        }
        else
        {
            return true;
        }
    }

    public static int lightenColor(int inColor, float inAmount)
    {
        return Color.argb(Color.alpha(inColor), (int) Math.min(255, red(inColor) + 255 * inAmount),
                          (int) Math.min(255, green(inColor) + 255 * inAmount),
                          (int) Math.min(255, blue(inColor) + 255 * inAmount));
    }

    public static int darkenColor(int inColor, float inAmount)
    {
        return Color.argb(Color.alpha(inColor), (int) Math.max(0, red(inColor) - 255 * inAmount),
                          (int) Math.max(0, green(inColor) - 255 * inAmount),
                          (int) Math.max(0, blue(inColor) - 255 * inAmount));
    }

    static int get_toxconnection_wrapper(int TOX_CONNECTION_)
    {
        if (TOX_CONNECTION_ == 0)
        {
            return 0;
        }
        else
        {
            return 1;
        }
    }

    static int get_combined_connection_status(String friend_pubkey, int a_TOX_CONNECTION)
    {
        int ret = TOX_CONNECTION_NONE.value;

        if (HelperRelay.is_any_relay(friend_pubkey))
        {
            ret = a_TOX_CONNECTION;
        }
        else
        {
            String relay_ = HelperRelay.get_relay_for_friend(friend_pubkey);

            if (relay_ == null)
            {
                // friend has no relay
                ret = a_TOX_CONNECTION;
            }
            else
            {
                // friend with relay
                if (a_TOX_CONNECTION != TOX_CONNECTION_NONE.value)
                {
                    ret = a_TOX_CONNECTION;
                }
                else
                {
                    int friend_con_status = orma.selectFromFriendList().tox_public_key_stringEq(friend_pubkey).get(
                            0).TOX_CONNECTION_real;
                    int relay_con_status = orma.selectFromFriendList().tox_public_key_stringEq(relay_).get(
                            0).TOX_CONNECTION_real;

                    if ((friend_con_status != TOX_CONNECTION_NONE.value) ||
                        (relay_con_status != TOX_CONNECTION_NONE.value))
                    {
                        // if one of them is online, return combined "online" as status
                        // TODO: do not always return TCP, make this better
                        ret = TOX_CONNECTION_TCP.value;
                    }
                }
            }
        }

        return ret;
    }

    /*************************************************************************/

    public static boolean tox_friend_resend_msgv3_wrapper(Message m)
    {
        if (m.msg_idv3_hash == null)
        {
            m.resend_count++;
            update_message_in_db_resend_count(m);
            return false;
        }

        if (m.msg_idv3_hash.length() < TOX_HASH_LENGTH)
        {
            m.resend_count++;
            update_message_in_db_resend_count(m);
            return false;
        }
        ByteBuffer hash_bytes = hexstring_to_bytebuffer(m.msg_idv3_hash);
        long res = MainActivity.tox_messagev3_friend_send_message(tox_friend_by_public_key__wrapper(m.tox_friendpubkey),
                                                                  TRIFA_MSG_TYPE_TEXT.value, m.text, hash_bytes,
                                                                  (m.sent_timestamp / 1000));

        m.resend_count++;
        m.message_id = res;
        update_message_in_db_resend_count(m);
        update_message_in_db_messageid(m);
        update_single_message(m, true);

        return true;
    }

    /*
     * returns: send_message_result class
     *
     *    send_message_result: NULL (if friend does not exist)
     *
     *    long msg_num: -98 (msgv3 error), -1 -2 -3 -4 -5 -6 (msgv3 error), -99 (msgv3 error),
     *                  "0..(UINT32_MAX-1)" (message sent OK)
     *
     *    long msg_num: -1 -2 -3 -4 -5 -6 -99 (msgv2 error),
     *                  MESSAGE_V2_MSG_SENT_OK (msgv2 sent OK), "0..(UINT32_MAX-1)" (message "old" sent OK)
     *
     *    boolean msg_v2: true if msgv2 was used, false otherwise
     *
     */
    public static MainActivity.send_message_result tox_friend_send_message_wrapper(final String friend_pubkey, int a_TOX_MESSAGE_TYPE, @NonNull String message, long timestamp_unixtime_seconds)
    {
        // Log.d(TAG, "tox_friend_send_message_wrapper:" + friend_pubkey);
        long friendnum_to_use = tox_friend_by_public_key__wrapper(friend_pubkey);
        if (friendnum_to_use < 0)
        {
            friendnum_to_use = HelperFriend.ensure_friend_in_tox_core(friend_pubkey);
        }

        FriendList f = main_get_friend(friend_pubkey);
        if (f == null)
        {
            HelperFriend.ensure_contact_record_for_pubkey(friend_pubkey, friendnum_to_use);
            f = main_get_friend(friend_pubkey);
        }

        boolean need_call_push_url = false;

        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();

        boolean msgv1 = true;

        if (f == null)
        {
            return null;
        }

        HelperFriend.reconcile_friend_connection_state(friend_pubkey, friendnum_to_use);

        if (HelperFriend.is_own_public_key(friend_pubkey) || FavoritesChatHelper.isFavoritesChat(friend_pubkey))
        {
            final MainActivity.send_message_result result = new MainActivity.send_message_result();
            final ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
            MainActivity.tox_messagev3_get_new_message_id(hash_bytes);
            result.msg_num = 1;
            result.msg_v2 = false;
            result.msg_hash_hex = "";
            result.msg_hash_v3_hex = bytebuffer_to_hexstring(hash_bytes, true);
            result.raw_message_buf_hex = "";
            return result;
        }

        if (peer_supports_msgv2(f) && !peer_supports_msgv3(f))
        {
            msgv1 = false;
        }

        // Log.d(TAG, "tox_friend_send_message_wrapper:f conn" + f.TOX_CONNECTION_real);
        final int live_conn = (friendnum_to_use >= 0)
                ? MainActivity.tox_friend_get_connection_status(friendnum_to_use) : TOX_CONNECTION_NONE.value;
        if (live_conn != f.TOX_CONNECTION_real)
        {
            f.TOX_CONNECTION_real = live_conn;
        }

        String relay_pubkey = null;
        if (live_conn == TOX_CONNECTION_NONE.value)
        {
            relay_pubkey = HelperRelay.get_relay_for_friend(f.tox_public_key_string);

            // msgV2 relay delivery requires TOX_CAPABILITY_MSGV2 (iOS/Khandaq uses msgV3 over tox_friend_send_message only)
            if (relay_pubkey != null && peer_supports_msgv2(f))
            {
                friendnum_to_use = tox_friend_by_public_key__wrapper(relay_pubkey);
                msgv1 = false;
            }
            else
            {
                need_call_push_url = true;
            }
        }

        if (MessageChunker.shouldChunk(message) && friendnum_to_use >= 0
                && live_conn != TOX_CONNECTION_NONE.value)
        {
            final MainActivity.send_message_result result = new MainActivity.send_message_result();
            result.msg_v2 = false;
            result.msg_hash_hex = "";
            result.msg_hash_v3_hex = "";
            result.raw_message_buf_hex = "";
            result.msg_num = MessageChunker.sendChunked(friendnum_to_use, message) ? 1L : -3L;
            return result;
        }

        // KHANDAQ (#14): not chunked → a single Tox message must fit the protocol limit, so trim as a
        // safety net (the chunked path above already sent the full text to an online friend). Short
        // messages are unchanged; this only prevents an over-length message from being rejected.
        message = trim_to_utf8_length_bytes(message, ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH);
        if (message == null)
        {
            message = "";
        }

        if (live_conn == TOX_CONNECTION_NONE.value && relay_pubkey == null)
        {
            final MainActivity.send_message_result result = new MainActivity.send_message_result();
            result.msg_v2 = false;
            result.msg_hash_hex = "";
            result.msg_hash_v3_hex = "";
            result.raw_message_buf_hex = "";
            result.msg_num = -3L;
            return result;
        }

        if (msgv1)
        {
            MainActivity.send_message_result result = new MainActivity.send_message_result();
            result.msg_v2 = false;
            result.msg_hash_hex = "";
            result.raw_message_buf_hex = "";

            // Desktop / classic Tox clients (e.g. qTox on Windows) expect plain tox_friend_send_message payloads.
            if (!peer_supports_msgv3(f))
            {
                long res = MainActivity.tox_friend_send_message(friendnum_to_use, a_TOX_MESSAGE_TYPE, message);
                result.msg_num = res;
                result.msg_hash_v3_hex = "";

                if (need_call_push_url)
                {
                    friend_call_push_url(f.tox_public_key_string, System.currentTimeMillis());
                }

                return result;
            }

            // TRIfA msgV3 peers
            ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
            MainActivity.tox_messagev3_get_new_message_id(hash_bytes);
            long res = MainActivity.tox_messagev3_friend_send_message(friendnum_to_use, a_TOX_MESSAGE_TYPE, message,
                                                                      hash_bytes, timestamp_unixtime_seconds);

            result.msg_num = res;
            result.msg_hash_v3_hex = bytebuffer_to_hexstring(hash_bytes, true);

            if (need_call_push_url)
            {
                friend_call_push_url(f.tox_public_key_string, System.currentTimeMillis());
            }

            return result;
        }
        else // use msgV2
        {
            MainActivity.send_message_result result = new MainActivity.send_message_result();
            ByteBuffer raw_message_buf = ByteBuffer.allocateDirect((int) TOX_MAX_FILETRANSFER_SIZE_MSGV2);
            ByteBuffer raw_message_length_buf = ByteBuffer.allocateDirect((int) 2); // 2 bytes for length
            ByteBuffer msg_id_buffer = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
            // use msg V2 API Call
            // long t_sec = (System.currentTimeMillis() / 1000);
            long res = MainActivity.tox_util_friend_send_message_v2(friendnum_to_use, a_TOX_MESSAGE_TYPE,
                                                                    timestamp_unixtime_seconds, message,
                                                                    message.length(), raw_message_buf,
                                                                    raw_message_length_buf, msg_id_buffer);
            if (PREF__X_battery_saving_mode)
            {
                Log.i(TAG, "global_last_activity_for_battery_savings_ts:002:*PING*");
            }
            final int len_low_byte = raw_message_length_buf.array()[raw_message_length_buf.arrayOffset()] & 0xFF;
            final int len_high_byte =
                    (raw_message_length_buf.array()[raw_message_length_buf.arrayOffset() + 1] & 0xFF) * 256;
            final int raw_message_length_int = len_low_byte + len_high_byte;

            result.error_num = res;

            if (res == -9999)
            {
                // msg V2 OK
                result.msg_num = MESSAGE_V2_MSG_SENT_OK;
                result.msg_v2 = true;
                result.msg_hash_hex = bytesToHex(msg_id_buffer.array(), msg_id_buffer.arrayOffset(),
                                                 msg_id_buffer.limit());
                result.raw_message_buf_hex = bytesToHex(raw_message_buf.array(), raw_message_buf.arrayOffset(),
                                                        raw_message_length_int);
                if (need_call_push_url)
                {
                    friend_call_push_url(f.tox_public_key_string, System.currentTimeMillis());
                }
                return result;
            }
            else
            {
                if (res == -9991)
                {
                    result.msg_num = -1;
                }
                else
                {
                    result.msg_num = res;
                }

                result.msg_v2 = true;
                result.msg_hash_hex = "";
                result.raw_message_buf_hex = "";
                if (need_call_push_url)
                {
                    friend_call_push_url(f.tox_public_key_string, System.currentTimeMillis());
                }
                return result;
            }
        }
    }

    static void set_new_random_nospam_value()
    {
        // Log.i(TAG, "old ToxID=" + MainActivity.get_my_toxid());
        // Log.i(TAG, "old NOSPAM=" + MainActivity.tox_self_get_nospam());
        java.security.SecureRandom random = new java.security.SecureRandom();
        long new_nospam = (long) random.nextInt() + (1L << 31);
        // Log.i(TAG, "generated NOSPAM=" + new_nospam);
        MainActivity.tox_self_set_nospam(new_nospam);
        update_savedata_file_wrapper(); // set new random nospam

        try
        {
            update_toxid_display_s();
        }
        catch (Exception e)
        {
            // e.printStackTrace();
        }

        // Log.i(TAG, "new ToxID=" + MainActivity.get_my_toxid());
        // Log.i(TAG, "new NOSPAM=" + MainActivity.tox_self_get_nospam());
    }

    static void update_savedata_file_wrapper_throttled()
    {
        // Log.i(TAG, "update_savedata_file_wrapper_throttled:** CALL");
        long currentTime = System.currentTimeMillis();

        if (currentTime - update_savedata_file_wrapper_throttled_last_trigger_ts >=
            INTERVAL_UPDATE_SAVE_FILE_WRAPPER_THROTTLED_MS)
        {
            update_savedata_file_wrapper_throttled_last_trigger_ts = currentTime;
            // Log.i(TAG, "update_savedata_file_wrapper_throttled:-> REAL");
            update_savedata_file_wrapper();
        }
        else
        {
            long delta_t_ms = currentTime - update_savedata_file_wrapper_throttled_last_trigger_ts;
            // Log.i(TAG, "update_savedata_file_wrapper_throttled:  TRIG delta ms=" + delta_t_ms);
            long trigger_in_ms_again = INTERVAL_UPDATE_SAVE_FILE_WRAPPER_THROTTLED_MS - delta_t_ms;
            if ((trigger_in_ms_again < 1) || (trigger_in_ms_again > (INTERVAL_UPDATE_SAVE_FILE_WRAPPER_THROTTLED_MS + 1)))
            {
                trigger_in_ms_again = INTERVAL_UPDATE_SAVE_FILE_WRAPPER_THROTTLED_MS;
            }
            final long trigger_in_ms_again_ = trigger_in_ms_again + 2;
            final Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(trigger_in_ms_again_);
                    // Log.i(TAG, "update_savedata_file_wrapper_throttled:__ CALL from Trigger");
                    update_savedata_file_wrapper_throttled();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
            thread.start();
        }
    }

    // KHANDAQ (audit): update_savedata_file_wrapper() runs a slow PBKDF2 encrypt of the whole tox
    // savedata (semaphore-guarded, thread-safe). On group-admin dialog taps it ran on the UI thread
    // and froze the app. Persisting is fire-and-forget (the change already took effect in the live tox
    // instance), so run it off the UI thread. Safe to call from any thread — it already is.
    static void update_savedata_file_wrapper_async()
    {
        new Thread(() -> {
            try
            {
                update_savedata_file_wrapper();
            }
            catch (Throwable t)
            {
                t.printStackTrace();
            }
        }, "savedata-persist").start();
    }

    static void update_savedata_file_wrapper()
    {
        // KHANDAQ (#244): last line of defence. If startup refused the loaded identity (no usable
        // savedata although this device had a profile, or the loaded key differs from the anchor),
        // the live tox instance holds a keypair that is NOT the user's — persisting it would
        // overwrite the real profile and orphan every contact. Never write in that state.
        if (MainActivity.identity_load_blocked)
        {
            Log.i(TAG, "update_savedata_file(): BLOCKED: identity not verified");
            return;
        }

        if (is_tox_started == true)
        {
            //Log.i(TAG, "update_savedata_file:delta_ms="
            //           + (System.currentTimeMillis() - update_savedata_file_wrapper_last_ts));
            //update_savedata_file_wrapper_last_ts = System.currentTimeMillis();
            // KHANDAQ (audit #7): make the savedata mutex exception-safe. Previously a Throwable from the
            // native update_savedata_file()/sha256 skipped release() -> permit never returned -> every
            // future persist deadlocks (Tox identity silently stops being saved); and an InterruptedException
            // from acquire() (permit NOT obtained) still hit release() -> permit count inflates to 2 -> two
            // threads write savedata.tox concurrently -> corrupted/lost identity. Acquire outside the try;
            // return on interrupt WITHOUT releasing; release exactly once in finally.
            try
            {
                MainActivity.semaphore_tox_savedata.acquire();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            try
            {
                MainActivity.update_savedata_file(TrifaSetPatternActivity.bytesToString(
                        TrifaSetPatternActivity.sha256(TrifaSetPatternActivity.StringToBytes2(PREF__DB_secrect_key))));
            }
            finally
            {
                MainActivity.semaphore_tox_savedata.release();
            }
        }
        else
        {
            Log.i(TAG, "update_savedata_file(): ERROR:Tox not ready:001");
        }
    }

    /** Reload open DM chat after incoming message (covers push→open race and missed add_single_message). */
    static void schedule_dm_chat_refresh_for_friend(final String friend_pubkey)
    {
        if ((friend_pubkey == null) || (friend_pubkey.length() < 2))
        {
            return;
        }

        final Runnable refresh = () -> {
            try
            {
                if (MainActivity.message_list_activity == null)
                {
                    return;
                }
                final String open_pk = MainActivity.message_list_activity.get_friend_pubkey();
                if ((open_pk == null) || !friend_pubkey.equalsIgnoreCase(open_pk))
                {
                    return;
                }
                if (MainActivity.message_list_fragment != null)
                {
                    MainActivity.message_list_fragment.update_all_messages(false, false, PREF__messageview_paging);
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "schedule_dm_chat_refresh_for_friend:EE:" + e.getMessage());
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(refresh);
            main_handler_s.postDelayed(refresh, 800);
        }
    }

    static void receive_incoming_message(int msg_type, int tox_message_type, long friend_number, String friend_message_text_utf8, byte[] raw_message, long raw_message_length, String original_sender_pubkey, byte[] msgV3hash_bin, long message_timestamp)
    {
        try
        {
            String sender_pk = null;
            if ((original_sender_pubkey != null) &&
                (original_sender_pubkey.length() == (TOX_PUBLIC_KEY_SIZE * 2)))
            {
                sender_pk = original_sender_pubkey.toUpperCase(Locale.ROOT);
            }
            else if (friend_number >= 0)
            {
                sender_pk = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
                if (sender_pk != null)
                {
                    sender_pk = sender_pk.toUpperCase(Locale.ROOT);
                }
            }
            if (sender_pk != null)
            {
                HelperFriend.ensure_contact_record_for_pubkey(sender_pk, friend_number);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "receive_incoming_message:ensure_contact:EE:" + e.getMessage());
        }

        // incoming msg can be:
        // (msg_type == 0) msgV1 text only message -> msg_type, friend_number, friend_message_text_utf8 [, msgV3hash_bin, message_timestamp]
        // (msg_type == 1) msgV2 direct message    -> msg_type, friend_number, friend_message_text_utf8, raw_message, raw_message_length
        // (msg_type == 2) msgV2 relay message     -> msg_type, friend_number, friend_message_text_utf8, raw_message, raw_message_length, original_sender_pubkey
        if (msg_type == 0)
        {
            // msgV1 text only message
            if ((friend_message_text_utf8 == null) || (friend_message_text_utf8.length() < 1))
            {
                Log.w(TAG, "receive_incoming_message:skip empty text fn=" + friend_number);
                return;
            }

            String msgV3hash_hex_string = null;
            if (msgV3hash_bin != null)
            {
                msgV3hash_hex_string = HelperGeneric.bytesToHex(msgV3hash_bin, 0, msgV3hash_bin.length);

                int got_messages = orma.selectFromMessage().tox_friendpubkeyEq(
                        HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).directionEq(0).msg_idv3_hashEq(
                        msgV3hash_hex_string).count();

                // Log.i(TAG, "friend_message:friend:" + friend_number + " msgV3hash_hex_string:" + msgV3hash_hex_string +
                //           " got_messages=" + got_messages);

                if (got_messages > 0)
                {
                    // HINT: we already have received a message with this hash
                    // still send the msgV3 high level ACK, and then ignore
                    HelperMessage.send_msgv3_high_level_ack(friend_number, msgV3hash_hex_string);
                    return;
                }
            }

            if (tox_message_type == TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK.value)
            {
                // TODO: ack message in database and update messagelist UI
                process_msgv3_high_level_ack(friend_number, msgV3hash_hex_string, message_timestamp);
                return;
            }

            if (msgV3hash_bin != null)
            {
                int got_messages_mirrored = orma.selectFromMessage().tox_friendpubkeyEq(
                        HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).directionEq(1).msg_idv3_hashEq(
                        msgV3hash_hex_string).count();

                if (got_messages_mirrored > 0)
                {
                    Log.i(TAG, "receive_incoming_message:mirrored msgv3 hash, skip display fn=" + friend_number);
                    HelperMessage.send_msgv3_high_level_ack(friend_number, msgV3hash_hex_string);
                    return;
                }

                update_friend_msgv3_capability(friend_number, 1);
            }
            else
            {
                update_friend_msgv3_capability(friend_number, 0);
            }

            try
            {
                FriendList profile_friend = main_get_friend(friend_number);
                if (profile_friend != null)
                {
                    HelperFriend.sync_friend_profile_from_tox(friend_number, profile_friend);
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "receive_incoming_message:sync_profile:EE:" + e.getMessage());
            }

            // if message list for this friend is open, then don't do notification and "new" badge
            boolean do_notification = true;
            boolean do_badge_update = true;

            // Log.i(TAG, "noti_and_badge:001:" + message_list_activity);
            if (MainActivity.message_list_activity != null)
            {
                // Log.i(TAG, "noti_and_badge:002:" + message_list_activity.get_current_friendnum() + ":" + friend_number);
                if (MainActivity.message_list_activity.get_current_friendnum() == friend_number)
                {
                    // Log.i(TAG, "noti_and_badge:003:");
                    do_badge_update = false;
                }
            }

            Message m = new Message();

            if (!do_badge_update)
            {
                Log.i(TAG, "noti_and_badge:004a:");
                m.is_new = false;
            }
            else
            {
                Log.i(TAG, "noti_and_badge:004b:");
                m.is_new = true;
            }

            // m.tox_friendnum = friend_number;
            m.tox_friendpubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            m.direction = 0; // msg received
            m.TOX_MESSAGE_TYPE = 0;
            m.read = false;
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
            m.rcvd_timestamp = System.currentTimeMillis();
            m.rcvd_timestamp_ms = 0;
            if (message_timestamp > 0)
            {
                m.sent_timestamp = message_timestamp * 1000;
            }
            else
            {
                m.sent_timestamp = System.currentTimeMillis();
            }
            m.sent_timestamp_ms = 0;
            m.text = friend_message_text_utf8;
            m.msg_version = 0;
            m.msg_idv3_hash = (msgV3hash_hex_string != null) ? msgV3hash_hex_string : "";
            m.sent_push = 0;

            if (m.tox_friendpubkey == null || m.tox_friendpubkey.length() < (TOX_PUBLIC_KEY_SIZE * 2))
            {
                Log.w(TAG, "friend_message:skip invalid pubkey friend=" + friend_number);
                return;
            }

            long db_result = -1;

            if (MainActivity.message_list_activity != null)
            {
                if (MainActivity.message_list_activity.get_current_friendnum() == friend_number)
                {
                    db_result = HelperMessage.insert_into_message_db(m, true);
                }
                else
                {
                    db_result = HelperMessage.insert_into_message_db(m, false);
                }
            }
            else
            {
                db_result = HelperMessage.insert_into_message_db(m, false);
            }

            if (db_result == -1)
            {
                // error on inserting message into db, most likely msgV3 double message.
                // KHANDAQ (dup fix): still ACK the duplicate. Otherwise the sender keeps resending (up to
                // MAX_TEXTMSG_RESEND_COUNT) because it never got a receipt — which is exactly what produced
                // the "same message 5 times" reconnect bursts. ACKing the dup tells the sender to stop.
                if (msgV3hash_hex_string != null)
                {
                    HelperMessage.send_msgv3_high_level_ack(friend_number, msgV3hash_hex_string);
                }
                return;
            }

            String friendname = null;
            try
            {
                // update "new" status on friendlist fragment
                FriendList f = (FriendList) orma.selectFromFriendList().tox_public_key_stringEq(m.tox_friendpubkey).toList().get(0);
                // HelperFriend.update_single_friend_in_friendlist_view(f);
                HelperFriend.add_all_friends_clear_wrapper(0);

                if (f.notification_silent)
                {
                    do_notification = false;
                }

                if (f.alias_name == null)
                {
                    friendname = f.name;
                }
                else if (f.alias_name.length() < 1)
                {
                    friendname = f.name;
                }
                else
                {
                    friendname = f.alias_name;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "update *new* status:EE1:" + e.getMessage());
            }

            if (do_notification)
            {
                change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.tox_friendpubkey, friendname, m.text);
            }

            if (msgV3hash_hex_string != null)
            {
                HelperMessage.send_msgv3_high_level_ack(friend_number, msgV3hash_hex_string);
            }

            schedule_dm_chat_refresh_for_friend(m.tox_friendpubkey);
        }
        else if (msg_type == 1)
        {
            // msgV2 direct message
            // Log.i(TAG,
            //      "friend_message_v2:friend:" + friend_number + " ts:" + ts_sec + " systime" + System.currentTimeMillis() +
            //      " message:" + friend_message);
            // if message list for this friend is open, then don't do notification and "new" badge
            boolean do_notification = true;
            boolean do_badge_update = true;

            // Log.i(TAG, "noti_and_badge:001:" + message_list_activity);
            if (MainActivity.message_list_activity != null)
            {
                // Log.i(TAG, "noti_and_badge:002:" + message_list_activity.get_current_friendnum() + ":" + friend_number);
                if (MainActivity.message_list_activity.get_current_friendnum() == friend_number)
                {
                    // Log.i(TAG, "noti_and_badge:003:");
                    // no badge update when chat is open, but still show notification
                    do_badge_update = false;
                }
            }

            ByteBuffer raw_message_buf = ByteBuffer.allocateDirect((int) raw_message_length);
            raw_message_buf.put(raw_message, 0, (int) raw_message_length);
            ByteBuffer msg_id_buffer = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
            MainActivity.tox_messagev2_get_message_id(raw_message_buf, msg_id_buffer);
            long ts_sec = MainActivity.tox_messagev2_get_ts_sec(raw_message_buf);
            long ts_ms = MainActivity.tox_messagev2_get_ts_ms(raw_message_buf);
            String msg_id_as_hex_string = bytesToHex(msg_id_buffer.array(), msg_id_buffer.arrayOffset(),
                                                     msg_id_buffer.limit());
            // Log.i(TAG, "TOX_FILE_KIND_MESSAGEV2_SEND:MSGv2HASH:2=" + msg_id_as_hex_string + " len=" +
            //           msg_id_as_hex_string.length());
            int already_have_message = orma.selectFromMessage().tox_friendpubkeyEq(
                    HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).msg_id_hashEq(
                    msg_id_as_hex_string).count();

            long pin_timestamp = System.currentTimeMillis();

            if (already_have_message > 0)
            {
                // it's a double send, ignore it
                // send message receipt v2, most likely the other party did not get it yet
                // TODO: use received timstamp, not "now" here!
                HelperFriend.send_friend_msg_receipt_v2_wrapper(friend_number, msg_type, msg_id_buffer,
                                                                (pin_timestamp / 1000));
                return;
            }

            // add FT message to UI
            Message m = new Message();

            if (!do_badge_update)
            {
                Log.i(TAG, "noti_and_badge:004a:");
                m.is_new = false;
            }
            else
            {
                Log.i(TAG, "noti_and_badge:004b:");
                m.is_new = true;
            }

            m.tox_friendpubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            m.direction = 0; // msg received
            m.TOX_MESSAGE_TYPE = 0;
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
            m.filetransfer_id = -1;
            m.filedb_id = -1;
            m.state = TOX_FILE_CONTROL_RESUME.value;
            m.ft_accepted = false;
            m.ft_outgoing_started = false;
            m.ft_outgoing_queued = false;
            m.sent_timestamp = (ts_sec * 1000); // sent time as unix timestamp -> convert to milliseconds
            m.sent_timestamp_ms = ts_ms; // "ms" part of timestamp (could be just an increasing number)
            m.rcvd_timestamp = pin_timestamp;
            m.rcvd_timestamp_ms = 0;
            m.text = friend_message_text_utf8;
            m.msg_version = 1;
            m.msg_id_hash = msg_id_as_hex_string;
            m.sent_push = 0;
            // Log.i(TAG, "TOX_FILE_KIND_MESSAGEV2_SEND:" + long_date_time_format(m.rcvd_timestamp));

            if (MainActivity.message_list_activity != null)
            {
                if (MainActivity.message_list_activity.get_current_friendnum() == friend_number)
                {
                    HelperMessage.insert_into_message_db(m, true);
                }
                else
                {
                    HelperMessage.insert_into_message_db(m, false);
                }
            }
            else
            {
                HelperMessage.insert_into_message_db(m, false);
            }

            HelperFriend.send_friend_msg_receipt_v2_wrapper(friend_number, msg_type, msg_id_buffer,
                                                            (pin_timestamp / 1000));
            String friendname = null;
            try
            {
                // update "new" status on friendlist fragment
                FriendList f = (FriendList) orma.selectFromFriendList().tox_public_key_stringEq(m.tox_friendpubkey).toList().get(0);
                // HelperFriend.update_single_friend_in_friendlist_view(f);
                HelperFriend.add_all_friends_clear_wrapper(0);

                if (f.notification_silent)
                {
                    do_notification = false;
                }

                if (f.alias_name == null)
                {
                    friendname = f.name;
                }
                else if (f.alias_name.length() < 1)
                {
                    friendname = f.name;
                }
                else
                {
                    friendname = f.alias_name;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "update *new* status:EE1:" + e.getMessage());
            }

            if (do_notification)
            {
                change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.tox_friendpubkey, friendname, m.text);
            }
        }
        else if (msg_type == 2)
        {
            // msgV2 relay message
            long friend_number_real_sender = tox_friend_by_public_key__wrapper(original_sender_pubkey);
            // Log.i(TAG,
            //      "friend_message_v2:friend:" + friend_number + " ts:" + ts_sec + " systime" + System.currentTimeMillis() +
            //      " message:" + friend_message);
            // if message list for this friend is open, then don't do notification and "new" badge
            boolean do_notification = true;
            boolean do_badge_update = true;

            // Log.i(TAG, "noti_and_badge:001:" + message_list_activity);
            if (MainActivity.message_list_activity != null)
            {
                // Log.i(TAG, "noti_and_badge:002:" + message_list_activity.get_current_friendnum() + ":" + friend_number);
                if (MainActivity.message_list_activity.get_current_friendnum() == friend_number_real_sender)
                {
                    // Log.i(TAG, "noti_and_badge:003:");
                    // no badge update when chat is open, but still show notification
                    do_badge_update = false;
                }
            }

            ByteBuffer raw_message_buf = ByteBuffer.allocateDirect((int) raw_message_length);
            raw_message_buf.put(raw_message, 0, (int) raw_message_length);
            ByteBuffer msg_id_buffer = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
            MainActivity.tox_messagev2_get_message_id(raw_message_buf, msg_id_buffer);
            long ts_sec = MainActivity.tox_messagev2_get_ts_sec(raw_message_buf);
            long ts_ms = MainActivity.tox_messagev2_get_ts_ms(raw_message_buf);
            Log.i(TAG, "receive_incoming_message:TOX_FILE_KIND_MESSAGEV2_SEND:raw_msg=" + bytes_to_hex(raw_message));
            String msg_id_as_hex_string = bytesToHex(msg_id_buffer.array(), msg_id_buffer.arrayOffset(),
                                                     msg_id_buffer.limit());
            Log.i(TAG, "receive_incoming_message:TOX_FILE_KIND_MESSAGEV2_SEND:MSGv2HASH:2=" + msg_id_as_hex_string);
            int already_have_message = orma.selectFromMessage().tox_friendpubkeyEq(
                    HelperFriend.tox_friend_get_public_key__wrapper(friend_number_real_sender)).msg_id_hashEq(
                    msg_id_as_hex_string).count();

            long pin_timestamp = System.currentTimeMillis();

            if (already_have_message > 0)
            {
                // it's a double send, ignore it
                // send message receipt v2, most likely the other party did not get it yet
                // Log.i(TAG,
                //      "receive_incoming_message:ACK1:" + get_friend_name_from_num(friend_number_real_sender) + " " +
                //      msg_id_as_hex_string);
                HelperFriend.send_friend_msg_receipt_v2_wrapper(friend_number_real_sender, msg_type, msg_id_buffer,
                                                                (pin_timestamp / 1000));
                return;
            }

            // add FT message to UI
            Message m = new Message();

            if (!do_badge_update)
            {
                Log.i(TAG, "noti_and_badge:004a:");
                m.is_new = false;
            }
            else
            {
                Log.i(TAG, "noti_and_badge:004b:");
                m.is_new = true;
            }

            m.tox_friendpubkey = original_sender_pubkey;
            m.direction = 0; // msg received
            m.TOX_MESSAGE_TYPE = 0;
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
            m.filetransfer_id = -1;
            m.filedb_id = -1;
            m.state = TOX_FILE_CONTROL_RESUME.value;
            m.ft_accepted = false;
            m.ft_outgoing_started = false;
            m.ft_outgoing_queued = false;
            m.sent_timestamp = (ts_sec * 1000); // sent time as unix timestamp -> convert to milliseconds
            m.sent_timestamp_ms = ts_ms; // "ms" part of timestamp (could be just an increasing number)
            m.rcvd_timestamp = pin_timestamp;
            m.rcvd_timestamp_ms = 0;
            m.text = friend_message_text_utf8;
            m.msg_version = 1;
            m.msg_id_hash = msg_id_as_hex_string;
            m.sent_push = 0;
            Log.i(TAG,
                  "receive_incoming_message:TOX_FILE_KIND_MESSAGEV2_SEND:" + long_date_time_format(m.rcvd_timestamp));

            if (MainActivity.message_list_activity != null)
            {
                if (MainActivity.message_list_activity.get_current_friendnum() == friend_number_real_sender)
                {
                    HelperMessage.insert_into_message_db(m, true);
                }
                else
                {
                    HelperMessage.insert_into_message_db(m, false);
                }
            }
            else
            {
                HelperMessage.insert_into_message_db(m, false);
            }

            // send message receipt v2 to the relay
            // Log.i(TAG, "receive_incoming_message:ACK2:" + get_friend_name_from_num(friend_number_real_sender) + " " +
            //           msg_id_as_hex_string);
            HelperFriend.send_friend_msg_receipt_v2_wrapper(friend_number_real_sender, msg_type, msg_id_buffer,
                                                            (pin_timestamp / 1000));

            String friendname = null;
            try
            {
                // update "new" status on friendlist fragment
                FriendList f = (FriendList) orma.selectFromFriendList().tox_public_key_stringEq(m.tox_friendpubkey).toList().get(0);
                // HelperFriend.update_single_friend_in_friendlist_view(f);
                HelperFriend.add_all_friends_clear_wrapper(0);

                if (f.notification_silent)
                {
                    do_notification = false;
                }

                if (f.alias_name == null)
                {
                    friendname = f.name;
                }
                else if (f.alias_name.length() < 1)
                {
                    friendname = f.name;
                }
                else
                {
                    friendname = f.alias_name;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "update *new* status:EE1:" + e.getMessage());
            }

            if (do_notification)
            {
                change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.tox_friendpubkey, friendname, m.text);
            }
        }
    }

    static void reverse_u_and_v_planes(byte[] buf, int frame_width_px, int frame_height_px)
    {
        // TODO: make this less slow and aweful!
        try
        {
            int pos = 0;
            int start = frame_width_px * frame_height_px;
            int off = (frame_width_px * frame_height_px) / 4;
            int start2 = start + off;
            byte b = 0;

            for (pos = 0; pos < off; pos++)
            {
                b = buf[start + pos];
                buf[start + pos] = buf[start2 + pos];
                buf[start2 + pos] = b;
            }
        }
        catch (Exception e)
        {
            // e.printStackTrace();
        }
    }

    static void save_sps_pps_nal(byte[] sps_pps_nal_unit_bytes, int length)
    {
        if (sps_pps_nal_unit_bytes != null)
        {
            global_sps_pps_nal_unit_bytes = Arrays.copyOf(sps_pps_nal_unit_bytes, length);
        }
    }

    public static byte[] YV12totoNV12(byte[] input, byte[] output, int width, int height)
    {
        final int frameSize = width * height;
        final int qFrameSize = frameSize / 4;
        System.arraycopy(input, 0, output, 0, frameSize); // Y
        for (int i = 0; i < qFrameSize; i++)
        {
            output[frameSize + i * 2] = input[frameSize + i + qFrameSize]; // Cb (U)
            output[frameSize + i * 2 + 1] = input[frameSize + i]; // Cr (V)
        }
        return output;
    }

    public static byte[] YV12toNV21(final byte[] input, final byte[] output, final int width, final int height)
    {

        final int size = width * height;
        final int quarter = size / 4;
        final int vPosition = size; // This is where V starts
        final int uPosition = size + quarter; // This is where U starts

        System.arraycopy(input, 0, output, 0, size); // Y is same

        for (int i = 0; i < quarter; i++)
        {
            output[size + i * 2] = input[vPosition + i]; // For NV21, V first
            output[size + i * 2 + 1] = input[uPosition + i]; // For Nv21, U second
        }
        return output;
    }

    // the color transform, @see http://stackoverflow.com/questions/15739684/mediacodec-and-camera-color-space-incorrect
    public static byte[] NV21toNV12(byte[] input, byte[] output, int width, int height)
    {
        final int frameSize = width * height;
        final int qFrameSize = frameSize / 4;
        System.arraycopy(input, 0, output, 0, frameSize); // Y
        for (int i = 0; i < qFrameSize; i++)
        {
            output[frameSize + i * 2] = input[frameSize + i * 2 + 1]; // Cb (U)
            output[frameSize + i * 2 + 1] = input[frameSize + i * 2]; // Cr (V)
        }
        return output;
    }

    static int toxav_video_send_frame_uv_reversed_wrapper(final byte[] buf2, final long friendnum, final int frame_width_px, final int frame_height_px, long capture_ts)
    {
        // Log.i(TAG, "toxav_video_send_frame_uv_reversed_wrapper:all:--START--:");
        // long s_time_a = System.currentTimeMillis();

/*
        try
        {
            android.os.Process.setThreadPriority(Thread.MAX_PRIORITY);
            // android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            // android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
*/

        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) && (MainActivity.PREF__use_H264_hw_encoding) &&
            (Callstate.video_out_codec == VIDEO_CODEC_H264))
        {
            final long video_frame_age = capture_ts;

            if (java_video_encoder_first_frame_in == 1)
            {
                Callstate.java_video_encoder_first_frame_in = 0;
                Callstate.java_video_encoder_delay_start_ts = System.currentTimeMillis();
            }

            final Thread new_thread = new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        // Log.i(TAG, "toxav_video_send_frame_uv_reversed_wrapper:feed:--START--:");
                        // long s_time = System.currentTimeMillis();

                        if (MainActivity.PREF__camera_get_preview_format.equals("YV12"))
                        {
                            if (buf_video_send_frame == null)
                            {
                                buf_video_send_frame = new byte[buf2.length];
                            }
                            else if (buf_video_send_frame.length < buf2.length)
                            {
                                buf_video_send_frame = new byte[buf2.length];
                            }
                            buf_video_send_frame = YV12totoNV12(buf2, buf_video_send_frame, frame_width_px,
                                                                frame_height_px);
                            feed_h264_encoder(buf_video_send_frame, frame_width_px, frame_height_px, video_frame_age);
                        }
                        else // (PREF__camera_get_preview_format == "NV21")
                        {
                            if (buf_video_send_frame == null)
                            {
                                buf_video_send_frame = new byte[buf2.length];
                            }
                            else if (buf_video_send_frame.length < buf2.length)
                            {
                                buf_video_send_frame = new byte[buf2.length];
                            }
                            buf_video_send_frame = NV21toNV12(buf2, buf_video_send_frame, frame_width_px,
                                                              frame_height_px);
                            feed_h264_encoder(buf_video_send_frame, frame_width_px, frame_height_px, video_frame_age);
                        }

                        // Log.i(TAG, "toxav_video_send_frame_uv_reversed_wrapper:feed:--END--:" + (System.currentTimeMillis() - s_time) + "ms");
                    }
                    catch (Exception e)
                    {
                        // e.printStackTrace();
                    }
                }
            };
            new_thread.start();

            final Thread new_thread2 = new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        // Log.i(TAG, "toxav_video_send_frame_uv_reversed_wrapper:fetch:--START--:");
                        // long s_time = System.currentTimeMillis();

                        for (int jj = 0; jj < 2; jj++)
                        {
                            CallingActivity.h264_encoder_output_data h264_out_data = fetch_from_h264_encoder();

                            if (h264_out_data != null)
                            {
                                if (h264_out_data.sps_pps != null)
                                {
                                    save_sps_pps_nal(h264_out_data.sps_pps, h264_out_data.data_len);
                                }

                                if (h264_out_data.data != null)
                                {
                                    MainActivity.video_buffer_2.rewind();
                                    long data_length = 0;

                                    if (global_sps_pps_nal_unit_bytes != null)
                                    {
                                        if (send_sps_pps_every_x_frames_current >= send_sps_pps_every_x_frames)
                                        {
                                            // Log.i(TAG, "video_send_frame_uv_reversed_wrapper:send_sps_pps:1");
                                            if (h264_out_data.sps_pps == null)
                                            {
                                                // only add sps/pps if this NALU does not contain it already
                                                MainActivity.video_buffer_2.put(global_sps_pps_nal_unit_bytes);
                                                data_length = data_length + global_sps_pps_nal_unit_bytes.length;
                                            }
                                            send_sps_pps_every_x_frames_current = 0;

                                            if (set_vdelay_every_x_frames_current >= set_vdelay_every_x_frames)
                                            {
                                                set_vdelay_every_x_frames_current = 0;

                                                video_frame_age_values[video_frame_age_values_cur_index] = (int) (
                                                        System.currentTimeMillis() - video_frame_age);
                                                video_frame_age_values_cur_index++;
                                                if (video_frame_age_values_cur_index >=
                                                    video_frame_age_values_cur_index_count)
                                                {
                                                    video_frame_age_values_cur_index = 0;
                                                }

                                                video_frame_age_mean = 0;
                                                for (int kk = 0; kk < video_frame_age_values_cur_index_count; kk++)
                                                {
                                                    video_frame_age_mean =
                                                            video_frame_age_mean + video_frame_age_values[kk];
                                                }

                                                video_frame_age_mean = video_frame_age_mean / 10;

                                                if (1 == 1)
                                                {
                                                    toxav_option_set(friendnum,
                                                                     TOXAV_CLIENT_VIDEO_CAPTURE_DELAY_MS.value,
                                                                     video_frame_age_mean + Callstate.delay_add);
                                                }
                                                else
                                                {
                                                    toxav_option_set(friendnum,
                                                                     TOXAV_CLIENT_VIDEO_CAPTURE_DELAY_MS.value,
                                                                     Callstate.java_video_encoder_delay);
                                                }
                                            }

                                            set_vdelay_every_x_frames_current++;

                                        }

                                        send_sps_pps_every_x_frames_current++;
                                    }

                                    MainActivity.video_buffer_2.put(h264_out_data.data, 0, h264_out_data.data_len);
                                    data_length = data_length + h264_out_data.data_len;

                                    // Log.i(TAG,
                                    //      "H264:video_frame_age=" + (System.currentTimeMillis() - video_frame_age));

                                    if (Callstate.java_video_encoder_delay_set == 0)
                                    {
                                        Callstate.java_video_encoder_delay = (System.currentTimeMillis() -
                                                                              Callstate.java_video_encoder_delay_start_ts);


                                        if ((Callstate.java_video_encoder_delay < 3) ||
                                            (Callstate.java_video_encoder_delay > 300))
                                        {
                                            Callstate.java_video_encoder_delay = 120;
                                        }

                                        Callstate.java_video_encoder_delay_set = 1;
                                        //Log.i(TAG,
                                        //      "java_video_encoder_delay=" + Callstate.java_video_encoder_delay + " ms");
                                    }

                                    //Log.i(TAG,
                                    //      "java_video_encoder_delay=" + Callstate.java_video_encoder_delay + " ms " +
                                    //      ((int) (System.currentTimeMillis() - h264_out_data.pts)) + " ms");

                                    //Log.i(TAG, "java_video_encoder_delay=" +
                                    //           (int) (System.currentTimeMillis() - video_frame_age) + " ms");


                                    Callstate.delay_add = (int) (System.currentTimeMillis() - h264_out_data.pts);
                                    if ((Callstate.delay_add < 1) || (Callstate.delay_add > 25))
                                    {
                                        Callstate.delay_add = 10;
                                    }

                                    //Log.i(TAG, "V:AGE:" + Callstate.delay_add + " : " + video_frame_age + " : " +
                                    //           ((int) (System.currentTimeMillis() - video_frame_age)));
                                    // Log.i(TAG, "toxav_video_send_frame_h264_age:--START--");
                                    // long s_time_send = System.currentTimeMillis();
                                    MainActivity.toxav_video_send_frame_h264_age(friendnum, frame_width_px,
                                                                                 frame_height_px, data_length,
                                                                                 (int) (System.currentTimeMillis() -
                                                                                        video_frame_age) +
                                                                                 Callstate.delay_add);

                                    // Log.i(TAG, "toxav_video_send_frame_h264_age:--END--:" + (System.currentTimeMillis() - s_time_send) + "ms");
                                }
                                else
                                {
                                    // Log.i(TAG, "video_send_frame_uv_reversed_wrapper:buf_out==null:#" + jj);
                                }
                            }
                        }

                        // Log.i(TAG, "toxav_video_send_frame_uv_reversed_wrapper:fetch:--END--:" + (System.currentTimeMillis() - s_time) + "ms");

                    }
                    catch (Exception e)
                    {
                        // e.printStackTrace();
                    }
                }
            };
            new_thread2.start();

            try
            {
                new_thread.join();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            if (1 == 1)
            {
                try
                {
                    new_thread2.join();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            // Log.i(TAG, "toxav_video_send_frame_uv_reversed_wrapper:all:--END--:" + (System.currentTimeMillis() - s_time_a) + "ms");

            return 0;
        }
        else
        {
            return MainActivity.toxav_video_send_frame_uv_reversed(friendnum, frame_width_px, frame_height_px);
        }
    }

    static int toxav_video_send_frame_wrapper(byte[] buf, long friendnum, int frame_width_px, int frame_height_px, long capture_ts)
    {
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) && (MainActivity.PREF__use_H264_hw_encoding) &&
            (Callstate.video_out_codec == VIDEO_CODEC_H264))
        {
            feed_h264_encoder(buf, frame_width_px, frame_height_px, capture_ts);

            for (int jj = 0; jj < 2; jj++)
            {
                CallingActivity.h264_encoder_output_data h264_out_data = fetch_from_h264_encoder();
                byte[] buf_out = h264_out_data.data;

                if (h264_out_data.sps_pps != null)
                {
                    save_sps_pps_nal(h264_out_data.sps_pps, h264_out_data.data_len);
                }

                if (buf_out != null)
                {
                    if (global_sps_pps_nal_unit_bytes != null)
                    {
                        if (send_sps_pps_every_x_frames_current > send_sps_pps_every_x_frames)
                        {
                            // Log.i(TAG, "video_send_frame_uv_reversed_wrapper:send_sps_pps:2");
                            MainActivity.video_buffer_2.rewind();
                            MainActivity.video_buffer_2.put(global_sps_pps_nal_unit_bytes);
                            MainActivity.toxav_video_send_frame_h264(friendnum, frame_width_px, frame_height_px,
                                                                     global_sps_pps_nal_unit_bytes.length);
                            send_sps_pps_every_x_frames_current = 0;
                        }

                        send_sps_pps_every_x_frames_current++;
                    }

                    MainActivity.video_buffer_2.rewind();
                    MainActivity.video_buffer_2.put(buf_out, 0, h264_out_data.data_len);
                    MainActivity.toxav_video_send_frame_h264(friendnum, frame_width_px, frame_height_px,
                                                             h264_out_data.data_len);
                }
                else
                {
                    // Log.i(TAG, "toxav_video_send_frame_wrapper:buf_out==null");
                }
            }

            return 0;
        }
        else
        {
            return MainActivity.toxav_video_send_frame(friendnum, frame_width_px, frame_height_px);
        }
    }

    static void import_toxsave_file_unsecure(final Context context, @NonNull final File f_src)
    {
        // KHANDAQ #24: validate the file BEFORE stopping tox, so a bad file can't leave the
        // tox thread stopped (the old order stopped tox first, then bailed out).
        if (f_src == null || !f_src.exists() || f_src.length() < 64L)
        {
            // KHANDAQ (audit #15): this method owns f_src (a plaintext savedata staging copy) and
            // deletes it after the copy below; the reject path used to return without deleting it.
            if (f_src != null)
            {
                //noinspection ResultOfMethodCallIgnored
                f_src.delete();
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(context.getString(R.string.settings_import_tox_profile));
            builder.setMessage(context.getString(R.string.settings_import_tox_profile_invalid_file));
            builder.setPositiveButton(android.R.string.ok, null);
            builder.create().show();
            return;
        }

        // KHANDAQ #24: the stop-tox wait + DB wipe + savedata copy used to run on the UI thread
        // (a busy-wait loop), freezing the app for the whole import ("очень долго"). Run it on a
        // background thread behind a progress dialog; only touch the UI from the main handler.
        final android.app.ProgressDialog progress = new android.app.ProgressDialog(context);
        progress.setMessage(context.getString(R.string.import_toxsave_in_progress));
        progress.setCancelable(false);
        progress.setIndeterminate(true);
        try { progress.show(); } catch (Exception ignored) {}

        MainActivity.global_stop_tox();

        new Thread(() ->
        {
            Log.i(TAG, "Waiting for ToxThread to stop ...");
            try
            {
                int guard = 0;
                while (guard < 200) // ~10s safety cap so we never hang forever
                {
                    // HINT: wait for tox thread to stop ...
                    //noinspection BusyWait
                    Thread.sleep(50);
                    // KHANDAQ (#249): wait on is_tox_started alone. global_stop_tox() only calls
                    // stop_tox_fg() when tox was actually running (MainActivity.java:3555), so
                    // stop_tox_fg_done stays false when it was not — and the old condition then
                    // burned the whole 10s cap behind the progress dialog for nothing.
                    if (!is_tox_started)
                    {
                        Log.i(TAG, "ToxThread has ended");
                        break;
                    }
                    guard++;
                }
            }
            catch (Exception ignored)
            {
            }

            // KHANDAQ (#249): is_tox_started only drops after ToxServiceThread.join() + tox_kill()
            // (TrifaToxService.java:1649/:517), so it is the one trustworthy proof that the old tox
            // instance is really gone and that a fresh one may be brought up in this same process.
            final boolean tox_is_down = !is_tox_started;

            // KHANDAQ #24: every table must be wiped on a profile REPLACE. Previously these deletes
            // were built but never .execute()'d, so old messages / files / filetransfers / relays /
            // peer caches survived the import and bled into the freshly imported profile.
            try
            {
                orma.deleteFromFriendList().execute();
                orma.deleteFromConferenceDB().execute();
                orma.deleteFromGroupDB().execute();
                orma.deleteFromMessage().execute();
                orma.deleteFromConferenceMessage().execute();
                orma.deleteFromGroupMessage().execute();
                orma.deleteFromGroupPeerDB().execute(); // KHANDAQ #202: stale peer rows survived import
                orma.deleteFromConferencePeerCacheDB().execute();
                orma.deleteFromFileDB().execute();
                orma.deleteFromFiletransfer().execute();
                orma.deleteFromRelayListDB().execute();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            File f_dst = new File(MainActivity.app_files_directory + "/" + "savedata.tox");
            // KHANDAQ (#249): read the source size BEFORE the finally below deletes the staging file.
            final long src_len = f_src.length();
            boolean copied = false;
            try
            {
                ls_file(f_src);
                ls_file(f_dst);

                // write toxsave data
                io_file_copy(f_src, f_dst);
                // KHANDAQ (#249): the copy result was never checked. A short/failed write must not be
                // followed by a restart that then finds a truncated savedata.tox where the old profile
                // used to be — in that case keep the old "exit and let the user retry" behaviour.
                copied = f_dst.exists() && (f_dst.length() == src_len);

                ls_file(f_dst);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                try
                {
                    // KHANDAQ (audit #15): delete the unencrypted import file on every path — a failed
                    // io_file_copy() used to skip this and leave the private key in the app cache, and
                    // the dialog below ends the process, so nothing later would have cleaned it up.
                    //noinspection ResultOfMethodCallIgnored
                    f_src.delete();
                }
                catch (Exception ignored)
                {
                }
            }

            // KHANDAQ (#249): the import stops tox, but NOTHING in this process ever starts it again:
            // stop_tox_fg() clears is_tox_started only, while the sole start path
            // "if (!TOX_SERVICE_STARTED) { startService(); tox_thread_start(); }"
            // (MainActivity.java:1668/1676) stays shut for as long as TOX_SERVICE_STARTED is true.
            // The entire restart hung on the System.exit(0) behind the OK button below, so a user who
            // pressed Home or swiped the task away instead of tapping OK kept a live process (the
            // foreground service holds it up) with a dead tox: the app sat offline / "connecting"
            // until a force-stop, and only a cold start — which resets the static — healed it.
            // Re-arm the gate and re-enter MainActivity instead; that is the same in-process tox
            // restart logout->login already runs on (ProfileContentFragment.performLogout()).
            // Gated on tox_is_down on purpose: re-arming while tox_kill is still in flight starts a
            // SECOND iterate thread on the tox being freed → native SIGSEGV (see the warning in
            // MainActivity.manually_log_out()).
            final boolean restart_in_process = copied && tox_is_down;

            if (restart_in_process)
            {
                // KHANDAQ (#249): the identity we just imported is a DIFFERENT one on purpose, so let
                // the next load re-point the #244 anchor at it exactly once. Without this the load is
                // refused as a mismatch, and a refused session never writes savedata and never
                // registers the 1:1 custom packet ids — the imported profile would connect and then
                // quietly fail to persist or to receive edits, deletes and reactions.
                try
                {
                    final android.content.SharedPreferences p_id =
                            android.preference.PreferenceManager.getDefaultSharedPreferences(
                                    MainActivity.main_activity_s);
                    if (p_id != null)
                    {
                        p_id.edit().putBoolean(MainActivity.PREF_IDENTITY_REANCHOR_ONCE, true).apply();
                    }
                }
                catch (Exception e)
                {
                    logI(TAG, "import: could not arm re-anchor:" + e.getMessage());
                }

                TrifaToxService.TOX_SERVICE_STARTED = false;
            }

            // back on the UI thread: dismiss progress and show the final restart dialog
            final Runnable finalUi = () ->
            {
                try { if (progress.isShowing()) { progress.dismiss(); } } catch (Exception ignored) {}

                if (restart_in_process)
                {
                    try
                    {
                        Log.i(TAG, "Import ToxSaveFile complete, restarting MainActivity in-process.");
                        Toast.makeText(context, R.string.import_toxsave_ok_title, Toast.LENGTH_LONG).show();
                        // CLEAR_TASK on purpose: MainActivity is singleTask, so plain navigation would
                        // reuse the existing instance and skip the onCreate that owns the tox start.
                        final Intent restart_intent = new Intent(context, MainActivity.class);
                        restart_intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        context.startActivity(restart_intent);
                    }
                    catch (Exception e)
                    {
                        Log.i(TAG, "Import ToxSaveFile: in-process restart FAILED, exiting the application.");
                        System.exit(0);
                    }
                    return;
                }

                try
                {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(context);
                    builder.setTitle(context.getString(R.string.import_toxsave_ok_title));
                    builder.setMessage(context.getString(R.string.import_toxsave_ok_message));
                    builder.setCancelable(false);
                    builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener()
                    {
                        public void onClick(DialogInterface dialog, int id)
                        {
                            try
                            {
                                Log.i(TAG, "Import ToxSaveFile complete, now exiting the application.");
                                System.exit(0);
                            }
                            catch (Exception ignored)
                            {
                            }
                        }
                    });
                    Log.i(TAG, "Import ToxSaveFile: show final Dialog.");
                    builder.create().show();
                }
                catch (Exception e)
                {
                    Log.i(TAG, "Import ToxSaveFile with ERRORs, still exiting the application.");
                    System.exit(0);
                }
            };

            if (MainActivity.main_handler_s != null)
            {
                MainActivity.main_handler_s.post(finalUi);
            }
            else
            {
                System.exit(0);
            }

            if (restart_in_process)
            {
                // KHANDAQ (#249): make sure the re-entry actually took. Android 10+ silently drops an
                // activity launch from a backgrounded app — which is precisely the situation this fix
                // exists for (the user pressed Home / swiped the task away during the import). The new
                // MainActivity.onCreate re-issues startService(), so TOX_SERVICE_STARTED flipping back
                // to true is the proof it ran; if it never does, fall back to the old exit so that the
                // next cold start brings tox up instead of leaving the process online-less forever.
                try
                {
                    int restart_guard = 0;
                    while ((restart_guard < 100) && (!TrifaToxService.TOX_SERVICE_STARTED)) // ~5s
                    {
                        //noinspection BusyWait
                        Thread.sleep(50);
                        restart_guard++;
                    }

                    if (!TrifaToxService.TOX_SERVICE_STARTED)
                    {
                        Log.i(TAG, "Import ToxSaveFile: restart did not take, now exiting the application.");
                        System.exit(0);
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }, "import_toxsave").start();
    }

    /**
     * KHANDAQ (#28): apply a decrypted + integrity-verified password backup. Mirrors the .tox
     * import background apply (stop tox, wipe every table, restart) but ALSO restores the encrypted
     * SQLCipher DB + IOCipher VFS and installs the backup's DB key BEFORE the restart, so the
     * restored history/media open on next launch. Staged files are already SHA-verified by the
     * caller; this method consumes (and deletes) them.
     */
    static void applyRestoredProfileFromBackup(final Context context, final File stagedDb,
                                               final File stagedVfs, final byte[] toxBytes,
                                               final String dbKey)
    {
        final android.app.ProgressDialog progress = new android.app.ProgressDialog(context);
        progress.setMessage(context.getString(R.string.import_toxsave_in_progress));
        progress.setCancelable(false);
        progress.setIndeterminate(true);
        try { progress.show(); } catch (Exception ignored) {}

        MainActivity.global_stop_tox();

        new Thread(() ->
        {
            Log.i(TAG, "restore: waiting for ToxThread to stop ...");
            try
            {
                int guard = 0;
                while (guard < 200) // ~10s safety cap
                {
                    //noinspection BusyWait
                    Thread.sleep(50);
                    if ((stop_tox_fg_done) && (!is_tox_started)) { break; }
                    guard++;
                }
            }
            catch (Exception ignored)
            {
            }

            try
            {
                orma.deleteFromFriendList().execute();
                orma.deleteFromConferenceDB().execute();
                orma.deleteFromGroupDB().execute();
                orma.deleteFromMessage().execute();
                orma.deleteFromConferenceMessage().execute();
                orma.deleteFromGroupMessage().execute();
                orma.deleteFromGroupPeerDB().execute(); // KHANDAQ #202: stale peer rows survived import
                orma.deleteFromConferencePeerCacheDB().execute();
                orma.deleteFromFileDB().execute();
                orma.deleteFromFiletransfer().execute();
                orma.deleteFromRelayListDB().execute();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            try
            {
                // identity
                final File toxDst = new File(MainActivity.app_files_directory + "/" + "savedata.tox");
                try (java.io.OutputStream os = new java.io.FileOutputStream(toxDst))
                {
                    os.write(toxBytes);
                }
                // restored encrypted DB + VFS (drop stale journal siblings first)
                final File dbDst = new File(context.getDir("dbs", Context.MODE_PRIVATE), MainActivity.MAIN_DB_NAME);
                final File vfsDst = new File(context.getDir("vfs", Context.MODE_PRIVATE), MainActivity.MAIN_VFS_NAME);
                delete_db_siblings(dbDst);
                delete_db_siblings(vfsDst);
                io_file_copy(stagedDb, dbDst);
                io_file_copy(stagedVfs, vfsDst);
                // install the backup's key BEFORE the DB/VFS are opened on the next launch
                DbSecretKeyStorage.persistRestoredDbSecretKey(context, dbKey);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                try { stagedDb.delete(); } catch (Exception ignored) {}
                try { stagedVfs.delete(); } catch (Exception ignored) {}
            }

            final Runnable finalUi = () ->
            {
                try { if (progress.isShowing()) { progress.dismiss(); } } catch (Exception ignored) {}
                try
                {
                    final AlertDialog.Builder builder = new AlertDialog.Builder(context);
                    builder.setTitle(context.getString(R.string.import_toxsave_ok_title));
                    builder.setMessage(context.getString(R.string.import_toxsave_ok_message));
                    builder.setCancelable(false);
                    builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener()
                    {
                        public void onClick(DialogInterface dialog, int id)
                        {
                            try { Log.i(TAG, "restore complete, exiting."); System.exit(0); }
                            catch (Exception ignored) {}
                        }
                    });
                    builder.create().show();
                }
                catch (Exception e)
                {
                    Log.i(TAG, "restore with ERRORs, still exiting."); System.exit(0);
                }
            };
            if (MainActivity.main_handler_s != null) { MainActivity.main_handler_s.post(finalUi); }
            else { System.exit(0); }
        }, "kbk-restore").start();
    }

    private static void delete_db_siblings(final File base)
    {
        for (final String ext : new String[]{"-journal", "-wal", "-shm"})
        {
            try { new File(base.getAbsolutePath() + ext).delete(); } catch (Exception ignored) {}
        }
    }

    static void ls_file(File f)
    {
        try
        {
            Log.i(TAG, "ls_file:" + f.getAbsolutePath() + " size=" + f.length());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void io_file_copy(File src, File dst) throws java.io.IOException
    {
        try (java.io.InputStream in = new java.io.FileInputStream(src))
        {
            try (java.io.OutputStream out = new java.io.FileOutputStream(dst))
            {
                // Transfer bytes from in to out
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) > 0)
                {
                    out.write(buf, 0, len);
                }
            }
        }
    }

    public static Bitmap drawableToBitmap(Drawable drawable)
    {
        if (drawable instanceof BitmapDrawable)
        {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        int width = drawable.getIntrinsicWidth();
        width = width > 0 ? width : 1;
        int height = drawable.getIntrinsicHeight();
        height = height > 0 ? height : 1;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    static boolean battery_saving_can_sleep()
    {
        if ((last_log_battery_savings_criteria_ts + 60000) < System.currentTimeMillis())
        {
            last_log_battery_savings_criteria_ts = System.currentTimeMillis();
            Log.i(TAG, "battery_saving_can_sleep:global_self_connection_status:" + global_self_connection_status +
                       " global_showing_messageview=" + global_showing_messageview + " global_showing_anygroupview=" +
                       global_showing_anygroupview + " Callstate.state=" + Callstate.state +
                       " Callstate.audio_group_active=" + Callstate.audio_group_active +
                       " global_self_last_went_online_timestamp=" + global_self_last_went_online_timestamp +
                       " global_self_last_went_online_timestamp2=" +
                       (System.currentTimeMillis() - global_self_last_went_online_timestamp) +
                       " global_last_activity_for_battery_savings_ts=" + global_last_activity_for_battery_savings_ts +
                       " global_last_activity_for_battery_savings_ts2=" +
                       (System.currentTimeMillis() - global_last_activity_for_battery_savings_ts) +
                       " SECONDS_TO_STAY_ONLINE_IN_BATTERY_SAVINGS_MODE=" +
                       SECONDS_TO_STAY_ONLINE_IN_BATTERY_SAVINGS_MODE + " System.currentTimeMillis()=" +
                       System.currentTimeMillis());
        }

        if ((!global_showing_messageview) && (!global_showing_anygroupview) && (Callstate.state == 0) &&
            (!Callstate.audio_group_active) && (!Callstate.audio_ngc_group_active))
        {
            if (global_self_last_went_online_timestamp != -1)
            {
                if ((global_self_last_went_online_timestamp + SECONDS_TO_STAY_ONLINE_IN_BATTERY_SAVINGS_MODE * 1000) <
                    System.currentTimeMillis())
                {
                    if ((global_last_activity_for_battery_savings_ts +
                         SECONDS_TO_STAY_ONLINE_IN_BATTERY_SAVINGS_MODE * 1000) < System.currentTimeMillis())
                    {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    static void vfs__detach()
    {
        try
        {
            //Log.i(TAG, "VFS:detachThread:" + Thread.currentThread().getId() + ":" + Thread.currentThread().getName());
            //vfs.detachThread();
            //Log.i(TAG, "VFS:detachThread:OK");

            Runnable myRunnable = new Runnable()
            {
                @Override
                public void run()
                {
                    Log.i(TAG, "VFS:detachThread:" + Thread.currentThread().getId() + ":" +
                               Thread.currentThread().getName());
                    vfs.detachThread();
                    Log.i(TAG, "VFS:detachThread:OK");
                }
            };
            if (main_handler_s != null)
            {
                //main_handler_s.post(myRunnable);
            }
            //Thread.sleep(400);

            Log.i(TAG, "VFS:detachThread:END");
        }
        catch (Exception e5)
        {
            Log.i(TAG, "VFS:detachThread:EE5:" + e5.getMessage());
            e5.printStackTrace();
        }
    }

    static void vfs__unmount()
    {
        Log.i(TAG, "VFS:unmount:start ...");

        try
        {
            Log.i(TAG, "VFS:unmount:start[2] ...");

            if (vfs.isMounted())
            {
                Log.i(TAG, "VFS:unmount:2:" + Thread.currentThread().getId() + ":" + Thread.currentThread().getName());
                vfs.unmount();
            }
            Log.i(TAG, "VFS:unmount:2:OK");

            Thread.sleep(10);
            Log.i(TAG, "VFS:unmount:END");
        }
        catch (Exception e5)
        {
            Log.i(TAG, "VFS:unmount:EE01:" + e5.getMessage());
            e5.printStackTrace();
        }

    }

    static String get_sqlite_search_string(String search_string_in)
    {
        String sql_like_pattern = "";

        try
        {
            // TODO: seems default "ESCAPE" is "\"
            // and "like" is set to caseinsensitive

            // TODO: check if there is a possible SQL injection here
            //       the good news is that only text that the user entered himself will go here
            //       so a user would have to want to self sabotage, by trying to inject here
            String tmp_str = search_string_in.replace("\\", "\\\\"). // \ -> \\
                    replace("%", "\\%"). // % -> \%
                    replace("_", "\\_"). // _ -> \_
                    replace("'", "''"); // ' -> ''
            // Log.i(TAG, "get_sqlite_search_string:" + tmp_str);
            sql_like_pattern = "%" + tmp_str + "%";
        }
        catch (Exception e)
        {
        }

        return sql_like_pattern;
    }

    static void print_stack_trace()
    {
        try
        {
            StackTraceElement[] elements = Thread.currentThread().getStackTrace();
            for (int i = 3; i < elements.length; i++)
            {
                StackTraceElement s = elements[i];
                Log.i("STACK_TRACE:",
                      "STACK_TRACE:\tat " + s.getClassName() + "." + s.getMethodName() + "(" + s.getFileName() + ":" +
                      s.getLineNumber() + ")");
            }
        }
        catch (Exception e)
        {
        }
    }

    /*
     *
     * print the caller method and filename and linenumer
     *
     */
    static void get_caller_method()
    {
        try
        {
            Log.i(TAG, "CALLED_BY:" + Thread.currentThread().getStackTrace()[4].getMethodName() + " " +
                       Thread.currentThread().getStackTrace()[4].getFileName() + ":" + Thread.currentThread().getStackTrace()[4].getLineNumber());
        }
        catch(Exception ignored)
        {
        }
    }

    /*
     * log the source line where this function is called from.
     * good for trace logging.
     */
    static void log_source_line()
    {
        try
        {
            final int offset = 3;
            Log.i(TAG, "INSANE_TRACE:" + Thread.currentThread().getStackTrace()[offset].getMethodName() + " " +
                       Thread.currentThread().getStackTrace()[offset].getFileName() + ":" + Thread.currentThread().getStackTrace()[offset].getLineNumber());
        }
        catch(Exception ignored)
        {
            ignored.printStackTrace();
        }
    }

    static void draw_main_top_icon__real(ImageView view, Context c, int blur_color, boolean is_fg)
    {
        try
        {
            view.setBackgroundColor(Color.TRANSPARENT);

            Drawable d = c.getResources().getDrawable(R.drawable.web_hi_res_512);
            Drawable currentState = d.getCurrent();
            if (currentState instanceof BitmapDrawable)
            {
                Bitmap bm1 = ((BitmapDrawable) currentState).getBitmap();
                Bitmap bm = bm1.copy(bm1.getConfig(), true);

                Bitmap alpha = bm.extractAlpha();

                int radius = 40;
                if (is_nightmode_active(c))
                {
                    radius = 80;
                }

                BlurMaskFilter blurMaskFilter = new BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER);

                Paint paint = new Paint();
                paint.setMaskFilter(blurMaskFilter);
                paint.setColor(blur_color);

                Canvas canvas = new Canvas(bm);
                canvas.drawBitmap(alpha, 0, 0, paint);

                view.setImageBitmap(bm);
            }
        }
        catch (Exception e)
        {
        }
    }

    static void draw_main_top_icon(ImageView view, Context c, int blur_color, boolean is_fg)
    {
        try
        {
            if (!is_fg)
            {
                Runnable myRunnable = () -> {
                    try
                    {
                        draw_main_top_icon__real(view, c, blur_color, is_fg);
                    }
                    catch (Exception e)
                    {
                    }
                };

                try
                {
                    if (MainActivity.main_handler_s != null)
                    {
                        MainActivity.main_handler_s.post(myRunnable);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            else
            {
                draw_main_top_icon__real(view, c, blur_color, is_fg);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void touch(File file) throws IOException
    {
        if (!file.exists())
        {
            File parent = file.getParentFile();
            if (parent != null)
            {
                if (!parent.exists())
                {
                    if (!parent.mkdirs())
                    {
                        throw new IOException("Cannot create parent directories for file: " + file);
                    }
                }
            }
            file.createNewFile();
        }

        boolean success = file.setLastModified(System.currentTimeMillis());
        if (!success)
        {
            throw new IOException("Unable to set the last modification time for " + file);
        }
    }

    public static boolean string_is_in_list(String input, String[] list)
    {
        try
        {
            return (Arrays.asList(list).contains(input));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public static boolean validate_ipv4(final String ip)
    {
        try
        {
            return PATTERN_IPV4.matcher(ip).matches();
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * Validate the given IPv4 or IPv6 address.
     *
     * @param address the IP address as a String.
     * @return true if a valid address, false otherwise
     */
    public static boolean IPisValid(String address)
    {
        return isValidIPv4(address) || isValidIPv6(address);
    }

    /**
     * Validate the given IPv4 or IPv6 address and netmask.
     *
     * @param address the IP address as a String.
     * @return true if a valid address with netmask, false otherwise
     */
    public static boolean IPisValidWithNetMask(String address)
    {
        return isValidIPv4WithNetmask(address) || isValidIPv6WithNetmask(address);
    }

    /**
     * Validate the given IPv4 address.
     *
     * @param address the IP address as a String.
     * @return true if a valid IPv4 address, false otherwise
     */
    public static boolean isValidIPv4(String address)
    {
        if (address.length() == 0)
        {
            return false;
        }

        int octet;
        int octets = 0;

        String temp = address + ".";

        int pos;
        int start = 0;
        while (start < temp.length() && (pos = temp.indexOf('.', start)) > start)
        {
            if (octets == 4)
            {
                return false;
            }
            try
            {
                octet = Integer.parseInt(temp.substring(start, pos));
            }
            catch (NumberFormatException ex)
            {
                return false;
            }
            if (octet < 0 || octet > 255)
            {
                return false;
            }
            start = pos + 1;
            octets++;
        }

        return octets == 4;
    }

    public static boolean isValidIPv4WithNetmask(String address)
    {
        int index = address.indexOf("/");
        String mask = address.substring(index + 1);

        return (index > 0) && isValidIPv4(address.substring(0, index)) && (isValidIPv4(mask) || isMaskValue(mask, 32));
    }

    public static boolean isValidIPv6WithNetmask(String address)
    {
        int index = address.indexOf("/");
        String mask = address.substring(index + 1);

        return (index > 0) &&
               (isValidIPv6(address.substring(0, index)) && (isValidIPv6(mask) || isMaskValue(mask, 128)));
    }

    public static boolean isIPPortValid(String port)
    {
        // HINT: valid number for IP ports: 1 - 65535
        try
        {
            int port_int = Integer.parseInt(port);
            // KHANDAQ (audit): 65535 IS a valid port; the old strict `< 65535` wrongly rejected it.
            if ((port_int > 0) && (port_int <= 65535))
            {
                return true;
            }
        }
        catch (Exception e)
        {
            return false;
        }
        return false;
    }

    private static boolean isMaskValue(String component, int size)
    {
        try
        {
            int value = Integer.parseInt(component);

            return value >= 0 && value <= size;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    /**
     * Validate the given IPv6 address.
     *
     * @param address the IP address as a String.
     * @return true if a valid IPv4 address, false otherwise
     */
    public static boolean isValidIPv6(String address)
    {
        if (address.length() == 0)
        {
            return false;
        }

        int octet;
        int octets = 0;

        String temp = address + ":";
        boolean doubleColonFound = false;
        int pos;
        int start = 0;
        while (start < temp.length() && (pos = temp.indexOf(':', start)) >= start)
        {
            if (octets == 8)
            {
                return false;
            }

            if (start != pos)
            {
                String value = temp.substring(start, pos);

                if (pos == (temp.length() - 1) && value.indexOf('.') > 0)
                {
                    if (!isValidIPv4(value))
                    {
                        return false;
                    }

                    octets++; // add an extra one as address covers 2 words.
                }
                else
                {
                    try
                    {
                        octet = Integer.parseInt(temp.substring(start, pos), 16);
                    }
                    catch (NumberFormatException ex)
                    {
                        return false;
                    }
                    if (octet < 0 || octet > 0xffff)
                    {
                        return false;
                    }
                }
            }
            else
            {
                if (pos != 1 && pos != temp.length() - 1 && doubleColonFound)
                {
                    return false;
                }
                doubleColonFound = true;
            }
            start = pos + 1;
            octets++;
        }

        return octets == 8 || doubleColonFound;
    }


    public static String trim_to_utf8_length_bytes(String input_string, final int max_length_in_bytes)
    {
        if (input_string == null)
        {
            return null;
        }
        try
        {
            // KHANDAQ: the old version shaved one char at a time, re-encoding the whole string each
            // pass — O(n^2), so pasting a long message (tens of KB) froze the UI for seconds. Fast
            // path when it already fits; otherwise binary-search the longest char prefix that fits.
            if (input_string.getBytes(StandardCharsets.UTF_8).length <= max_length_in_bytes)
            {
                return input_string;
            }

            int lo = 0;
            int hi = input_string.length();
            int best = 0;
            while (lo <= hi)
            {
                final int mid = (lo + hi) >>> 1;
                final int bytes = input_string.substring(0, mid).getBytes(StandardCharsets.UTF_8).length;
                if (bytes <= max_length_in_bytes)
                {
                    best = mid;
                    lo = mid + 1;
                }
                else
                {
                    hi = mid - 1;
                }
            }
            return input_string.substring(0, best);
        }
        catch (Exception e)
        {
        }

        return null;
    }

    static boolean is_nightmode_active(final Context c)
    {
        try
        {
            final int nightModeFlags = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            switch (nightModeFlags)
            {
                case Configuration.UI_MODE_NIGHT_YES:
                    return true;
                case Configuration.UI_MODE_NIGHT_NO:
                    return false;
                case Configuration.UI_MODE_NIGHT_UNDEFINED:
                    return false;
            }
        }
        catch (Exception e)
        {

        }
        return false;
    }

    static void display_toast(final String toast_text, final boolean length_long, final int delay_ms)
    {
        display_toast_with_context(context_s, toast_text, length_long, delay_ms);
    }

    static void display_toast_with_context(final Context c, final String toast_text, final boolean length_long, final int delay_ms)
    {
        try
        {
            int toast_length_ = Toast.LENGTH_SHORT;

            if (length_long)
            {
                toast_length_ = Toast.LENGTH_LONG;
            }

            final int toast_length = toast_length_;

            if (delay_ms == 0)
            {
                Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            Toast.makeText(c, toast_text, toast_length).show();
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
            else
            {
                try
                {
                    Thread t = new Thread()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                sleep(delay_ms);
                            }
                            catch (Exception e2)
                            {
                                e2.printStackTrace();
                            }

                            Runnable myRunnable = new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    try
                                    {
                                        Toast.makeText(c, toast_text, toast_length).show();
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
                    };
                    t.start();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "display_toast_with_context:EE01:" + e.getMessage());
        }
    }

    static void display_toast_with_context_custom_duration(final Context c, final String toast_text, final int custom_duration_ms, final int delay_ms)
    {
        try
        {
            new Handler().postDelayed(new Runnable()
            {
                @Override
                public void run()
                {
                    final Toast toast = Toast.makeText(c, toast_text, Toast.LENGTH_SHORT);
                    toast.show();
                    Handler handler = new Handler();
                    handler.postDelayed(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            toast.cancel();
                        }
                    }, custom_duration_ms);
                }
            }, delay_ms);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "display_toast_with_context_custom_duration:EE01:" + e.getMessage());
        }
    }

    static String filter_out_non_hex_chars(final String in)
    {
        if (in == null)
        {
            // string is null
            return null;
        }

        final String out = in.toUpperCase().replaceAll("[^0-9A-F]", "");
        return out;
    }

    static String truncate_utf8_to_max_bytes(final String value, final int maxBytes)
    {
        if ((value == null) || (maxBytes <= 0))
        {
            return "";
        }

        final byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= maxBytes)
        {
            return value;
        }

        int end = maxBytes;
        while (end > 0 && ((utf8[end] & 0xC0) == 0x80))
        {
            end--;
        }

        return new String(utf8, 0, end, StandardCharsets.UTF_8);
    }

    static String sanitize_tox_id_input(final String in)
    {
        if (in == null)
        {
            return null;
        }

        String cleaned = in.replace(" ", "").replace("\r", "").replace("\n", "");
        if (cleaned.regionMatches(true, 0, "tox:", 0, 4))
        {
            cleaned = cleaned.substring(4);
        }
        else if (cleaned.regionMatches(true, 0, "khandaq:", 0, 8))
        {
            cleaned = cleaned.substring(8);
        }

        return filter_out_non_hex_chars(cleaned);
    }

    static boolean is_valid_tox_address_checksum(final byte[] address)
    {
        final int checksum = tox_data_checksum(address, TOX_PUBLIC_KEY_SIZE + TOX_NOSPAM_SIZE);
        final int stored = ((address[TOX_PUBLIC_KEY_SIZE + TOX_NOSPAM_SIZE] & 0xFF) |
                ((address[TOX_PUBLIC_KEY_SIZE + TOX_NOSPAM_SIZE + 1] & 0xFF) << 8));
        return checksum == stored;
    }

    static int tox_data_checksum(final byte[] data, final int length)
    {
        final byte[] checksum = new byte[2];

        for (int i = 0; i < length; i++)
        {
            checksum[i % 2] ^= data[i];
        }

        return ((checksum[0] & 0xFF) | ((checksum[1] & 0xFF) << 8));
    }

    static String bytes_to_hex_upper(final byte[] bytes)
    {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);

        for (byte b : bytes)
        {
            sb.append(String.format("%02X", b));
        }

        return sb.toString();
    }

    static String normalize_tox_address(final String input)
    {
        final String hex = sanitize_tox_id_input(input);

        if (hex == null)
        {
            return null;
        }

        if (hex.length() == (TOX_ADDRESS_SIZE * 2))
        {
            final byte[] address = hex_to_bytes(hex);
            if (!is_valid_tox_address_checksum(address))
            {
                return null;
            }

            return hex;
        }

        if (hex.length() == (TOX_PUBLIC_KEY_SIZE * 2))
        {
            final byte[] address = new byte[TOX_ADDRESS_SIZE];
            System.arraycopy(hex_to_bytes(hex), 0, address, 0, TOX_PUBLIC_KEY_SIZE);
            final int checksum = tox_data_checksum(address, TOX_PUBLIC_KEY_SIZE + TOX_NOSPAM_SIZE);
            address[TOX_PUBLIC_KEY_SIZE + TOX_NOSPAM_SIZE] = (byte) (checksum & 0xFF);
            address[TOX_PUBLIC_KEY_SIZE + TOX_NOSPAM_SIZE + 1] = (byte) ((checksum >> 8) & 0xFF);
            return bytes_to_hex_upper(address);
        }

        return null;
    }

    static boolean is_valid_tox_address_input(final String input)
    {
        return normalize_tox_address(input) != null;
    }

    static boolean is_valid_tox_public_key(final String pubkey)
    {
        if (pubkey == null)
        {
            // string is null
            return false;
        }

        if (pubkey.length() != (TOX_PUBLIC_KEY_SIZE * 2))
        {
            // string length is not correct
            return false;
        }

        if (!pubkey.toUpperCase().matches("^[A-Z0-9]+$"))
        {
            // string contains illegal characters
            return false;
        }

        return true;
    }

    public static void do_fade_anim_on_fab(final FloatingActionButton fab, boolean fade_in, final String caller_class_name)
    {
        try
        {
            // Log.i(TAG, "caller_class_name:" + caller_class_name);
            // com.zoffcc.applications.trifa.MessageListFragment
            // com.zoffcc.applications.trifa.MessageListActivity
            // com.zoffcc.applications.trifa.ConferenceMessageListFragment
            // com.zoffcc.applications.trifa.ConferenceMessageListActivity
            // com.zoffcc.applications.trifa.GroupMessageListFragment
            // com.zoffcc.applications.trifa.GroupMessageListActivity

            if (fade_in)
            {
                if (caller_class_name.contains("rifa.ConferenceMessage"))
                {
                    ConferenceMessageListFragment.faded_in = true;
                }
                else if (caller_class_name.contains("rifa.GroupMessage"))
                {
                    GroupMessageListFragment.faded_in = true;
                }
                else
                {
                    MessageListFragment.faded_in = true;
                }

                ObjectAnimator scaleUpX = ObjectAnimator.ofFloat(fab, "scaleX", 0.0f, 1f);
                ObjectAnimator scaleUpY = ObjectAnimator.ofFloat(fab, "scaleY", 0.0f, 1f);
                scaleUpX.setDuration(FAB_SCROLL_TO_BOTTOM_FADEOUT_MS);
                scaleUpY.setDuration(FAB_SCROLL_TO_BOTTOM_FADEOUT_MS);

                AnimatorSet scaleUp = new AnimatorSet();
                scaleUp.play(scaleUpX).with(scaleUpY);

                scaleUp.start();
            }
            else
            {
                if (caller_class_name.contains("rifa.ConferenceMessage"))
                {
                    ConferenceMessageListFragment.faded_in = false;
                }
                else if (caller_class_name.contains("rifa.GroupMessage"))
                {
                    GroupMessageListFragment.faded_in = false;
                }
                else
                {
                    MessageListFragment.faded_in = false;
                }

                ObjectAnimator scaleDownX = ObjectAnimator.ofFloat(fab, "scaleX", 1f, 0.0f);
                ObjectAnimator scaleDownY = ObjectAnimator.ofFloat(fab, "scaleY", 1f, 0.0f);
                scaleDownX.setDuration(FAB_SCROLL_TO_BOTTOM_FADEIN_MS);
                scaleDownY.setDuration(FAB_SCROLL_TO_BOTTOM_FADEIN_MS);

                AnimatorSet scaleDown = new AnimatorSet();
                scaleDown.play(scaleDownX).with(scaleDownY);

                scaleDown.start();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    public static void set_fade_anim_on_fab(final FloatingActionButton fab, boolean fade_in)
    {
        try
        {
            int fade_ms = FAB_SCROLL_TO_BOTTOM_FADEOUT_MS;
            int start = 1;
            int end = 0;

            if (fade_in)
            {
                fade_ms = FAB_SCROLL_TO_BOTTOM_FADEIN_MS;
                start = 0;
                end = 1;
            }

            final AlphaAnimation anim_fade_out = new AlphaAnimation(start, end);
            anim_fade_out.setDuration(fade_ms);
            anim_fade_out.setStartOffset(fade_ms);
            anim_fade_out.setFillAfter(false);

            fab.setAnimation(anim_fade_out);
        }
        catch (Exception ignored)
        {
        }
    }

    public static void fill_own_avatar_icon(Context context, CircleImageView img_avatar)
    {
        String peerPubkey = null;
        try
        {
            final String toxid = MainActivity.get_my_toxid();
            if (toxid != null && toxid.length() >= ToxVars.TOX_PUBLIC_KEY_SIZE * 2)
            {
                peerPubkey = toxid.substring(0, ToxVars.TOX_PUBLIC_KEY_SIZE * 2);
            }
        }
        catch (Exception ignored)
        {
        }

        ChatBubbleUiHelper.fill_own_avatar_icon(context, img_avatar, peerPubkey, global_my_name);
    }

    public static void fill_friend_avatar_icon(Message m, Context context, CircleImageView img_avatar)
    {
        // KHANDAQ (tester feedback): in a 1:1 chat the sender is already obvious from the bubble
        // colour + side, so a per-message sender-initial avatar is redundant and only clutters the
        // look — the text bubbles already hide it. This method is called ONLY from the 1:1 message
        // holders (groups use GroupMessageListHolder_* and keep their per-peer avatars), so hide it
        // here to make every 1:1 bubble (text, voice, file, media) consistent.
        try
        {
            if (img_avatar != null)
            {
                img_avatar.setVisibility(View.GONE);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void set_avatar_img_height_in_chat(CircleImageView v)
    {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        if (PREF__compact_chatlist)
        {
            p.height = (int) dp2px(MESSAGE_AVATAR_HEIGHT_COMPACT_LAYOUT[PREF__global_font_size]);
        }
        else
        {
            p.height = (int) dp2px(MESSAGE_AVATAR_HEIGHT_NORMAL_LAYOUT[PREF__global_font_size]);
        }
        v.setLayoutParams(p);
    }

    public static void clear_audio_play_buffers()
    {
        try
        {
            audio_buffer_2.clear();
            byte[] b2 = new byte[audio_buffer_2.limit()];
            audio_buffer_2.put(b2);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "clear audio play buffers:003:EE03" + e.getMessage());
        }

        try
        {
            for (int i = 0; i < NativeAudio.n_audio_in_buffer_max_count; i++)
            {
                NativeAudio.n_audio_buffer[i].clear();
                Log.i(TAG, "clear audio play buffers:008:limit=" + NativeAudio.n_audio_buffer[i].limit());
                byte[] b3 = new byte[NativeAudio.n_audio_buffer[i].limit()];
                NativeAudio.n_audio_buffer[i].put(b3);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "clear audio play buffers:008:EE08" + e.getMessage());
        }
    }

    synchronized public static void applyCallStreamVolume(AudioManager manager, boolean speaker)
    {
        try
        {
            int stream = AudioManager.STREAM_VOICE_CALL;
            int max = manager.getStreamMaxVolume(stream);
            int volume = speaker ? max : Math.max(1, (max * 85) / 100);
            manager.setStreamVolume(stream, volume, 0);
            Log.i(TAG, "AUDIOROUTE:applyCallStreamVolume:speaker=" + speaker + " vol=" + volume + "/" + max);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static void finalizeCallAudioRoute(AudioManager manager, boolean speaker)
    {
        try
        {
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            {
                manager.setSpeakerphoneOn(speaker);
            }
            applyCallStreamVolume(manager, speaker);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    synchronized public static void set_calling_audio_mode()
    {
        try
        {
            AudioManager manager = MainActivity.audio_manager_s;
            if (manager == null)
            {
                return;
            }
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            applyCallStreamVolume(manager, Callstate.audio_speaker);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    synchronized public static void reset_audio_mode()
    {
        get_caller_method();
        try
        {
            print_stack_trace();
            MainActivity.audio_manager_s.setMode(AudioManager.MODE_NORMAL);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            {
                MainActivity.audio_manager_s.clearCommunicationDevice();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            set_rec_preset(true);
        }
        catch(Exception ignored)
        {
        }

        if ((Callstate.state != 0) || (Callstate.audio_group_active) || (Callstate.audio_ngc_group_active))
        {
            Log.i(TAG,"stop_audio_system:001:preset_TRUE");
            stop_audio_system();
        }
    }

    synchronized public static void set_audio_to_loudspeaker(AudioManager manager)
    {
        try
        {
            set_rec_preset(true);
        }
        catch(Exception ignored)
        {
        }

        if ((Callstate.state != 0) || (Callstate.audio_group_active) || (Callstate.audio_ngc_group_active))
        {
            Log.i(TAG,"restart_audio_system__normal_call:002:preset_TRUE");
            restart_audio_system();
        }

        try
        {
            Log.i(TAG, "AUDIOROUTE:set_audio_to_loudspeaker");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            {
                Boolean result = null;
                ArrayList<Integer> targetTypes = new ArrayList<>();
                targetTypes.add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
                List<AudioDeviceInfo> devices = manager.getAvailableCommunicationDevices();
                for (Integer targetType : targetTypes)
                {
                    for (AudioDeviceInfo device : devices)
                    {
                        if (device.getType() == targetType)
                        {
                            result = manager.setCommunicationDevice(device);
                            Log.i(TAG, "AUDIOROUTE:setCommunicationDevice type:" + targetType + " result:" + result);
                            if (result)
                            {
                                Callstate.audio_speaker = true;
                            }
                        }
                    }
                }
            }
            else
            {
                print_stack_trace();
                manager.setWiredHeadsetOn(false);
                manager.setBluetoothScoOn(false);
                Callstate.audio_speaker = true;
                finalizeCallAudioRoute(manager, true);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            {
                finalizeCallAudioRoute(manager, true);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "AUDIOROUTE:set_audio_to_loudspeaker:EE:" + e.getMessage());
        }
    }

    synchronized public static void set_audio_to_ear(AudioManager manager)
    {
        try
        {
            Log.i(TAG, "AUDIOROUTE:set_audio_to_ear");

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            {
                Boolean result = null;
                ArrayList<Integer> targetTypes = new ArrayList<>();
                targetTypes.add(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE);
                List<AudioDeviceInfo> devices = manager.getAvailableCommunicationDevices();
                for (Integer targetType : targetTypes)
                {
                    for (AudioDeviceInfo device : devices)
                    {
                        if (device.getType() == targetType)
                        {
                            result = manager.setCommunicationDevice(device);
                            Log.i(TAG, "AUDIOROUTE:setCommunicationDevice type:" + targetType + " result:" + result);
                            if (result)
                            {
                                Callstate.audio_speaker = false;
                            }
                        }
                    }
                }
            }
            else
            {
                print_stack_trace();
                manager.setWiredHeadsetOn(false);
                manager.setBluetoothScoOn(false);
                Callstate.audio_speaker = false;
                finalizeCallAudioRoute(manager, false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            {
                finalizeCallAudioRoute(manager, false);
            }

            try
            {
                set_rec_preset(false);
            }
            catch(Exception ignored)
            {
            }

            if ((Callstate.state != 0) || (Callstate.audio_group_active) || (Callstate.audio_ngc_group_active))
            {
                Log.i(TAG,"restart_audio_system__normal_call:003:preset_false");
                restart_audio_system();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "AUDIOROUTE:set_audio_to_ear:EE:" + e.getMessage());
        }
    }

    synchronized public static void set_audio_to_headset(AudioManager manager)
    {
        try
        {
            Log.i(TAG, "AUDIOROUTE:set_audio_to_headset");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            {
                Boolean result = null;
                ArrayList<Integer> targetTypes = new ArrayList<>();
                targetTypes.add(AudioDeviceInfo.TYPE_WIRED_HEADSET);
                List<AudioDeviceInfo> devices = manager.getAvailableCommunicationDevices();
                for (Integer targetType : targetTypes)
                {
                    for (AudioDeviceInfo device : devices)
                    {
                        if (device.getType() == targetType)
                        {
                            result = manager.setCommunicationDevice(device);
                            Log.i(TAG, "AUDIOROUTE:setCommunicationDevice type:" + targetType + " result:" + result);
                            if (result)
                            {
                                // HINT: set some var here?
                            }
                        }
                    }
                }
            }
            else
            {
                print_stack_trace();
                manager.setBluetoothScoOn(false);
                manager.setWiredHeadsetOn(true);
                finalizeCallAudioRoute(manager, false);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            {
                finalizeCallAudioRoute(manager, false);
            }

            try
            {
                set_rec_preset(false);
            }
            catch(Exception ignored)
            {
            }

            if ((Callstate.state != 0) || (Callstate.audio_group_active) || (Callstate.audio_ngc_group_active))
            {
                Log.i(TAG,"restart_audio_system__normal_call:004:preset_false");
                restart_audio_system();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "AUDIOROUTE:set_audio_to_headset:EE:" + e.getMessage());
        }
    }

    static void restart_audio_system()
    {
        get_caller_method();
        Log.i(TAG, "restart_audio_system:enter");
        stop_audio_system();
        start_audio_system();
        Log.i(TAG, "restart_audio_system:DONE");
    }

    static void start_audio_system()
    {
        get_caller_method();
        Log.i(TAG, "start_audio_system:enter");
        synchronized (audio_system_start_stop_lock)
        {
            try
            {
                if (AudioReceiver.stopped)
                {
                    CallingActivity.audio_receiver_thread = new AudioReceiver();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            try
            {
                if (AudioRecording.stopped)
                {
                    CallingActivity.audio_thread = new AudioRecording();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        Log.i(TAG, "start_audio_system:DONE");
    }

    static void stop_ngc_audio_system()
    {
        get_caller_method();
        Log.i(TAG, "stop_ngc_audio_system:enter");
        synchronized (audio_system_start_stop_lock)
        {
            try
            {
                if (!AudioRecording.stopped)
                {
                    AudioRecording.close();
                    GroupMessageListActivity.NGC_Group_video_play_thread.join(AUDIO_THREAD_JOIN_TIMEOUT_MS);
                    GroupMessageListActivity.NGC_Group_video_play_thread = null;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            try
            {
                if (!AudioReceiver.stopped)
                {
                    AudioReceiver.close();
                    GroupMessageListActivity.NGC_Group_video_play_thread.join(AUDIO_THREAD_JOIN_TIMEOUT_MS);
                    GroupMessageListActivity.NGC_Group_video_play_thread = null;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        Log.i(TAG, "stop_ngc_audio_system:DONE");
    }

    static void stop_audio_system()
    {
        get_caller_method();
        Log.i(TAG, "stop_audio_system:enter");
        synchronized (audio_system_start_stop_lock)
        {
            try
            {
                if (!AudioRecording.stopped)
                {
                    AudioRecording.close();
                    CallingActivity.audio_thread.join(AUDIO_THREAD_JOIN_TIMEOUT_MS);
                    CallingActivity.audio_thread = null;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            try
            {
                if (!AudioReceiver.stopped)
                {
                    AudioReceiver.close();
                    CallingActivity.audio_receiver_thread.join(AUDIO_THREAD_JOIN_TIMEOUT_MS);
                    CallingActivity.audio_receiver_thread = null;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        Log.i(TAG, "stop_audio_system:DONE");
    }

    static public File saveBitmapToFile(File file, final int REQUIRED_SIZE)
    {
        try {

            // BitmapFactory options to downsize the image
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            o.inSampleSize = 6;
            // factor of downsizing the image

            java.io.FileInputStream inputStream = new java.io.FileInputStream(file);
            BitmapFactory.decodeStream(inputStream, null, o);
            inputStream.close();

            // Find the correct scale value. It should be the power of 2.
            int scale = 1;
            while(o.outWidth / scale / 2 >= REQUIRED_SIZE &&
                  o.outHeight / scale / 2 >= REQUIRED_SIZE)
            {
                scale *= 2;
            }

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            inputStream = new java.io.FileInputStream(file);

            Bitmap selectedBitmap = BitmapFactory.decodeStream(inputStream, null, o2);
            inputStream.close();

            // here i override the original image file
            file.createNewFile();
            java.io.FileOutputStream outputStream = new java.io.FileOutputStream(file);

            selectedBitmap.compress(Bitmap.CompressFormat.JPEG, 80 , outputStream);

            return file;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    static public String trfia_sync_type_to_str(int sync_type)
    {
        if (sync_type == TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_TOXPROXY.value)
        {
            return "TRIFA_SYNC_TYPE_TOXPROXY";
        }
        else if (sync_type == TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value)
        {
            return "TRIFA_SYNC_TYPE_NGC_PEERS";
        }
        else if (sync_type == TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value)
        {
            return "TRIFA_SYNC_TYPE_NONE";
        }
        else
        {
            return "TRIFA_SYNC_TYPE UNKNOWN";
        }
    }

    static public String get_trifa_build_str()
    {
        String build_str = "";

        try
        {
            build_str = build_str + BuildConfig.GitHash.substring(0, 4);
        }
        catch (Exception e)
        {
            return "????????";
        }

        try
        {
            build_str = build_str + "-" + MainActivity.getNativeLibTOXGITHASH().substring(0, 3);
        }
        catch (Exception e)
        {
            return "????????";
        }

        try
        {
            build_str = build_str + "-" + MainActivity.getNativeLibGITHASH().substring(0, 3);
        }
        catch (Exception e)
        {
            return "????????";
        }

        try
        {
            build_str = build_str + "-" + Build.VERSION.SDK_INT;
        }
        catch (Exception e)
        {
            return "????????";
        }

        String abis = "??";
        try
        {
            abis = Build.SUPPORTED_ABIS[0];
        }
        catch(Exception e)
        {
            try
            {
                abis = Build.CPU_ABI;
            }
            catch(Exception ignored)
            {
            }
        }

        build_str = build_str + "-" + abis;

        return build_str;
    }

    static public void append_logger_msg(final String logmsg)
    {
        if (logmsg != null)
        {
            final String lower = logmsg.toLowerCase();
            if (lower.contains("bootstrap") || lower.contains("reconnect") || lower.contains("network")
                    || lower.contains("netmonitor"))
            {
                NetworkDiagnosticsLog.log("trace", logmsg);
            }
        }

        if (DEBUG_USE_LOGFRIEND)
        {
            try
            {
                // Log.i(TAG, "append_logger_msg:000:LOG_FRIEND_TOXID=" + LOG_FRIEND_TOXID + " msg=" + logmsg);
                String log_friend_pubkey = LOG_FRIEND_TOXID;
                if ((log_friend_pubkey == null) || (log_friend_pubkey.length() < 2))
                {
                    try
                    {
                        log_friend_pubkey = get_g_opts(LOGFRIEND_TOXID_DB_KEY);
                        if ((log_friend_pubkey == null) || (log_friend_pubkey.length() < 2))
                        {
                            // some error with the TOX PUBKEY of log friend
                            // Log.i(TAG, "append_logger_msg:ret1: LOG_FRIEND_TOXID=" + log_friend_pubkey);
                            return;
                        }
                        // Log.i(TAG, "append_logger_msg: LOG_FRIEND_TOXID=" + log_friend_pubkey);
                    }
                    catch (Exception e)
                    {
                        // some error with the TOX PUBKEY of log friend
                        // Log.i(TAG, "append_logger_msg:ret2: LOG_FRIEND_TOXID=" + LOG_FRIEND_TOXID);
                        return;
                    }
                }

                Log.i(TAG, "append_logger_msg:3:msg=" + logmsg);

                long pin_timestamp = System.currentTimeMillis();
                Message m = new Message();

                m.is_new = false;
                m.tox_friendpubkey = log_friend_pubkey;
                m.direction = 0; // msg received
                m.TOX_MESSAGE_TYPE = 0;
                m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
                m.filetransfer_id = -1;
                m.filedb_id = -1;
                m.state = TOX_FILE_CONTROL_CANCEL.value;
                m.ft_accepted = false;
                m.ft_outgoing_started = false;
                m.ft_outgoing_queued = false;
                m.sent_timestamp = pin_timestamp;
                m.sent_timestamp_ms = 0;
                m.rcvd_timestamp = pin_timestamp;
                m.rcvd_timestamp_ms = 0;
                m.text = logmsg;
                m.msg_version = 0;
                m.resend_count = 0;
                m.sent_push = 0;
                m.msg_idv3_hash = "";
                m.msg_id_hash = "";
                m.raw_msgv2_bytes = "";

                long row_id = -99;
                if (MainActivity.message_list_activity != null)
                {
                    final long friend_number_ = tox_friend_by_public_key__wrapper(log_friend_pubkey);
                    if (MainActivity.message_list_activity.get_current_friendnum() == friend_number_)
                    {
                        row_id = HelperMessage.insert_into_message_db(m, true);
                    }
                    else
                    {
                        row_id = HelperMessage.insert_into_message_db(m, false);
                    }
                }
                else
                {
                    row_id = HelperMessage.insert_into_message_db(m, false);
                }
                // Log.i(TAG, "append_logger_msg:row_id=" + row_id);
            }
            catch(Exception e)
            {
            }
        }
        else
        {
            Log.i(TAG, "append_logger_msg:3:msg=" + logmsg);
        }
    }

    static public void trigger_proper_wakeup_outside_tox_service_thread()
    {
        append_logger_msg(TAG + "::trigger_proper_wakeup_outside_tox_service_thread");
        TrifaToxService.need_wakeup_now = true;
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        try
        {
            trifa_service_thread.interrupt();
        }
        catch(Exception ignored)
        {
        }
    }

    static public void trigger_proper_wakeup_from_tox_service_thread()
    {
        append_logger_msg(TAG + "::trigger_proper_wakeup_from_tox_service_thread");
        TrifaToxService.need_wakeup_now = true;
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        try
        {
            trifa_service_thread.interrupt();
        }
        catch(Exception ignored)
        {
        }
    }
}

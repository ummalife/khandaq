/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 - 2020 Zoff <zoff@zoff.cc>
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


import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothHeadset;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.vanniktech.emoji.EmojiManager;
import com.yariksoffice.lingver.Lingver;
import com.zoffcc.applications.nativeaudio.NativeAudio;
import com.zoffcc.applications.sorm.ConferenceDB;
import com.zoffcc.applications.sorm.ConferenceMessage;
import com.zoffcc.applications.sorm.ConferencePeerCacheDB;
import com.zoffcc.applications.sorm.FileDB;
import com.zoffcc.applications.sorm.Filetransfer;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;
import com.zoffcc.applications.sorm.OrmaDatabase;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Semaphore;

import androidx.annotation.NonNull;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.RecyclerView;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import info.guardianproject.iocipher.FileInputStream;
import info.guardianproject.iocipher.FileOutputStream;
import info.guardianproject.iocipher.VirtualFileSystem;
import info.guardianproject.netcipher.proxy.OrbotHelper;
import info.guardianproject.netcipher.proxy.StatusCallback;
import permissions.dispatcher.NeedsPermission;
import permissions.dispatcher.RuntimePermissions;

import static com.zoffcc.applications.nativeaudio.NativeAudio.n_audio_in_buffer_max_count;
import static com.zoffcc.applications.nativeaudio.NativeAudio.na_set_audio_play_volume_percent;
import static com.zoffcc.applications.nativeaudio.NativeAudio.set_aec_active;
import static com.zoffcc.applications.nativeaudio.NativeAudio.set_audio_aec_delay;
import static com.zoffcc.applications.nativeaudio.NativeAudio.set_gainprocessing_active;
import static com.zoffcc.applications.nativeaudio.NativeAudio.set_rnnoise_active;
import static com.zoffcc.applications.sorm.OrmaDatabase.run_multi_sql;
import static com.zoffcc.applications.sorm.OrmaDatabase.set_schema_upgrade_callback;
import static com.zoffcc.applications.trifa.AudioReceiver.channels_;
import static com.zoffcc.applications.trifa.AudioReceiver.sampling_rate_;
import static com.zoffcc.applications.trifa.AudioRecording.audio_engine_starting;
import static com.zoffcc.applications.trifa.CallingActivity.initializeScreenshotSecurity;
import static com.zoffcc.applications.trifa.CallingActivity.on_call_started_actions;
import static com.zoffcc.applications.trifa.CallingActivity.set_debug_text;
import static com.zoffcc.applications.trifa.CallingActivity.toggle_osd_view_including_cam_preview;
import static com.zoffcc.applications.trifa.CallingActivity.update_calling_friend_connection_status;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_CONFERENCE;
import static com.zoffcc.applications.trifa.ConfGroupAudioService.do_update_group_title;
import static com.zoffcc.applications.trifa.ConferenceAudioActivity.conf_id;
import static com.zoffcc.applications.trifa.FriendListFragment.fl_loading_progressbar;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.play_ngc_incoming_audio_frame;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.show_ngc_incoming_video_frame_v2;
import static com.zoffcc.applications.trifa.HelperConference.get_last_conference_message_in_this_conference_within_n_seconds_from_sender_pubkey;
import static com.zoffcc.applications.trifa.HelperConference.tox_conference_by_confid__wrapper;
import static com.zoffcc.applications.trifa.HelperFiletransfer.check_auto_accept_incoming_filetransfer;
import static com.zoffcc.applications.trifa.HelperFiletransfer.resume_stalled_incoming_filetransfers;
import static com.zoffcc.applications.trifa.HelperFiletransfer.flush_and_close_vfs_ft_from_cache;
import static com.zoffcc.applications.trifa.HelperFiletransfer.get_incoming_filetransfer_local_filename;
import static com.zoffcc.applications.trifa.HelperFiletransfer.remove_ft_from_cache;
import static com.zoffcc.applications.trifa.HelperFiletransfer.remove_vfs_ft_from_cache;
import static com.zoffcc.applications.trifa.HelperFriend.get_friend_avatar_saved_hash_hex;
import static com.zoffcc.applications.trifa.HelperFriend.get_friend_msgv3_capability;
import static com.zoffcc.applications.trifa.HelperFriend.get_friend_name_from_pubkey;
import static com.zoffcc.applications.trifa.HelperFriend.main_get_friend;
import static com.zoffcc.applications.trifa.HelperFriend.send_friend_msg_receipt_v2_wrapper;
import static com.zoffcc.applications.trifa.HelperFriend.send_pushurl_to_all_friends;
import static com.zoffcc.applications.trifa.HelperFriend.send_pushurl_to_friend;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_get_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperFriend.update_friend_in_db_capabilities;
import static com.zoffcc.applications.trifa.HelperFriend.update_friend_in_db_ip_addr_str;
import static com.zoffcc.applications.trifa.HelperFriend.update_friend_msgv3_capability;
import static com.zoffcc.applications.trifa.HelperGeneric.append_logger_msg;
import static com.zoffcc.applications.trifa.HelperGeneric.bytes_to_hex;
import static com.zoffcc.applications.trifa.HelperGeneric.del_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast_with_context_custom_duration;
import static com.zoffcc.applications.trifa.HelperGeneric.draw_main_top_icon;
import static com.zoffcc.applications.trifa.HelperGeneric.get_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.hexstring_to_bytebuffer;
import static com.zoffcc.applications.trifa.HelperGeneric.is_nightmode_active;
import static com.zoffcc.applications.trifa.HelperGeneric.set_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper_throttled_last_trigger_ts;
import static com.zoffcc.applications.trifa.HelperGeneric.write_chunk_to_VFS_file;
import static com.zoffcc.applications.trifa.HelperGroup.add_group_peer_to_db;
import static com.zoffcc.applications.trifa.HelperGroup.add_system_message_to_group_chat;
import static com.zoffcc.applications.trifa.HelperGroup.android_tox_callback_group_message_cb_method_wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.get_last_group_message_in_this_group_within_n_seconds_from_sender_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.group_message_add_from_sync;
import static com.zoffcc.applications.trifa.HelperGroup.handle_incoming_group_file;
import static com.zoffcc.applications.trifa.HelperGroup.handle_incoming_sync_group_file;
import static com.zoffcc.applications.trifa.HelperGroup.handle_incoming_sync_group_message;
import static com.zoffcc.applications.trifa.HelperGroup.is_group_muted_or_kicked_peer;
import static com.zoffcc.applications.trifa.HelperGroup.send_ngch_request;
import static com.zoffcc.applications.trifa.HelperGroup.set_group_active;
import static com.zoffcc.applications.trifa.HelperGroup.sync_group_message_history;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupnum__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_name__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_db_name;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_db_privacy_state;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_db_topic;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_friendlist;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_groupmessagelist;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_messages_peer_role;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_peer_in_db;
import static com.zoffcc.applications.trifa.HelperMessage.set_message_msg_at_relay_from_id;
import static com.zoffcc.applications.trifa.HelperMsgNotification.change_msg_notification;
import static com.zoffcc.applications.trifa.HelperRelay.get_own_relay_connection_status_real;
import static com.zoffcc.applications.trifa.HelperRelay.have_own_relay;
import static com.zoffcc.applications.trifa.HelperRelay.invite_to_conference_own_relay;
import static com.zoffcc.applications.trifa.HelperRelay.invite_to_group_own_relay;
import static com.zoffcc.applications.trifa.HelperRelay.is_any_relay;
import static com.zoffcc.applications.trifa.HelperRelay.own_push_token_load;
import static com.zoffcc.applications.trifa.HelperRelay.send_pushtoken_to_relay;
import static com.zoffcc.applications.trifa.HelperToxNotification.tox_notification_change_wrapper;
import static com.zoffcc.applications.trifa.TRIFAGlobals.AVATAR_INCOMING_MAX_BYTE_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONFERENCE_ID_LENGTH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONNECTION_STATUS_MANUAL_LOGOUT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONTROL_PROXY_MESSAGE_TYPE.CONTROL_PROXY_MESSAGE_TYPE_PROXY_PUBKEY_FOR_FRIEND;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONTROL_PROXY_MESSAGE_TYPE.CONTROL_PROXY_MESSAGE_TYPE_PUSH_URL_FOR_FRIEND;
import static com.zoffcc.applications.trifa.TRIFAGlobals.DELETE_SQL_AND_VFS_ON_ERROR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FRIEND_AVATAR_FILENAME;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_AUDIO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_INIT_PLAY_DELAY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_MIN_AUDIO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_MIN_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GROUP_ID_LENGTH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.HIGHER_GLOBAL_AUDIO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOWER_GLOBAL_AUDIO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOWER_GLOBAL_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOWER_NGC_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOWER_NGC_VIDEO_QUANTIZER;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MAX_ALLOWED_INCOMING_FILESIZE_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_GROUP_SYNC_DOUBLE_INTERVAL_SECS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_SYNC_DOUBLE_INTERVAL_SECS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NGC_AUDIO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NORMAL_GLOBAL_AUDIO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_ADD;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_TOKEN_DB_KEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_TOKEN_DB_KEY_NEED_ACK;
import static com.zoffcc.applications.trifa.TRIFAGlobals.ORBOT_PROXY_HOST;
import static com.zoffcc.applications.trifa.TRIFAGlobals.ORBOT_PROXY_PORT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF__DB_secrect_key__user_hash;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_PUSH_SETUP_HOWTO_URL;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_INCOMING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_OUTGOING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UINT32_MAX_JAVA;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UPDATE_MESSAGE_PROGRESS_AFTER_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UPDATE_MESSAGE_PROGRESS_AFTER_BYTES_SMALL_FILES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UPDATE_MESSAGE_PROGRESS_SMALL_FILE_IS_LESS_THAN_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_PREFIX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_TMP_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_CODEC_H264;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_CODEC_H265;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_CODEC_VP8;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_FRAME_RATE_INCOMING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_FRAME_RATE_OUTGOING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.bootstrapping;
import static com.zoffcc.applications.trifa.TRIFAGlobals.count_video_frame_received;
import static com.zoffcc.applications.trifa.TRIFAGlobals.count_video_frame_sent;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_for_battery_savings_ts;
import static com.zoffcc.applications.trifa.HelperFiletransfer.fail_incoming_filetransfer;
import static com.zoffcc.applications.trifa.HelperFiletransfer.verify_incoming_file_complete;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_incoming_ft_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_outgoung_ft_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_last_went_offline_timestamp;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_last_went_online_timestamp;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_showing_anygroupview;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_showing_messageview;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_tox_self_status;
import static com.zoffcc.applications.trifa.TRIFAGlobals.last_video_frame_received;
import static com.zoffcc.applications.trifa.TRIFAGlobals.last_video_frame_sent;
import static com.zoffcc.applications.trifa.TRIFAGlobals.orbot_is_really_running;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_DECODER_CURRENT_BITRATE;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_DECODER_IN_USE_H264;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_DECODER_IN_USE_H265;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_DECODER_IN_USE_VP8;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_ENCODER_CURRENT_BITRATE;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_ENCODER_IN_USE_H264;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_ENCODER_IN_USE_VP8;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_NETWORK_ROUND_TRIP_MS;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_PLAY_BUFFER_ENTRIES;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_CALL_COMM_INFO.TOXAV_CALL_COMM_PLAY_DELAY;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_FRIEND_CALL_STATE.TOXAV_FRIEND_CALL_STATE_ACCEPTING_A;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_FRIEND_CALL_STATE.TOXAV_FRIEND_CALL_STATE_ACCEPTING_V;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_FRIEND_CALL_STATE.TOXAV_FRIEND_CALL_STATE_ERROR;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_FRIEND_CALL_STATE.TOXAV_FRIEND_CALL_STATE_FINISHED;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_FRIEND_CALL_STATE.TOXAV_FRIEND_CALL_STATE_NONE;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_FRIEND_CALL_STATE.TOXAV_FRIEND_CALL_STATE_SENDING_A;
import static com.zoffcc.applications.trifa.ToxVars.TOXAV_FRIEND_CALL_STATE.TOXAV_FRIEND_CALL_STATE_SENDING_V;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONFERENCE_TYPE.TOX_CONFERENCE_TYPE_AV;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONFERENCE_TYPE.TOX_CONFERENCE_TYPE_TEXT;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CAPABILITY_MSGV3;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_RESUME;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_ID_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_AVATAR;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_DATA;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_FTV2;
import static com.zoffcc.applications.trifa.ToxVars.TOX_HASH_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_FILE_AND_HEADER_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_USER_STATUS.TOX_USER_STATUS_AWAY;
import static com.zoffcc.applications.trifa.ToxVars.TOX_USER_STATUS.TOX_USER_STATUS_BUSY;
import static com.zoffcc.applications.trifa.ToxVars.TOX_USER_STATUS.TOX_USER_STATUS_NONE;
import static com.zoffcc.applications.trifa.TrifaToxService.TOX_SERVICE_STARTED;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.TrifaToxService.resend_old_messages;
import static com.zoffcc.applications.trifa.TrifaToxService.resend_v3_messages;
import static com.zoffcc.applications.trifa.TrifaToxService.vfs;

/*

first actually relayed message via ToxProxy

2019-08-28 22:20:43.286148 [D] friend_message_v2_cb:
fn=1 res=1 msg=🍔👍😜👍😜 @%\4äö ubnc Ovid n JB von in BK ni ubvzv8 ctcitccccccizzvvcvvv        u  tiigi gig i g35667u 6 66

 */

@SuppressWarnings({"UnusedReturnValue", "deprecation", "JniMissingFunction", "unused", "RedundantSuppression", "unchecked", "ConstantConditions", "RedundantCast", "Convert2Lambda", "EmptyCatchBlock", "PointlessBooleanExpression", "SimplifiableIfStatement", "ResultOfMethodCallIgnored"})
@SuppressLint({"StaticFieldLeak", "SimpleDateFormat", "SetTextI18n"})
@RuntimePermissions
public class MainActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.MainActivity";
    // --------- global config ---------
    // --------- global config ---------
    // --------- global config ---------
    final static boolean CTOXCORE_NATIVE_LOGGING = false; // set "false" for release builds
    final static boolean NDK_STDOUT_LOGGING = false; // set "false" for release builds
    final static boolean DEBUG_BATTERY_OPTIMIZATION_LOGGING = false;  // set "false" for release builds
    final static boolean INSANE_TRACE_LOGGING = false; // set "false" for release builds
    final static int ORMA_CURRENT_DB_SCHEMA_VERSION = 10245; // increase for database schema changes
    final static boolean DB_ENCRYPT = true; // set "true" always!
    final static boolean VFS_ENCRYPT = true; // set "true" always!
    final static boolean AEC_DEBUG_DUMP = false; // set "false" for release builds
    final static boolean VFS_CUSTOM_WRITE_CACHE = true; // set "true" for release builds
    final static boolean DEBUG_USE_LOGFRIEND = false; // set "false" for release builds
    public final static boolean DEBUG_BSN_ON_PROFILE = false; // set "false" for release builds
    // --------- global config ---------
    // --------- global config ---------
    // --------- global config ---------

    static View main_profile_bar = null;
    static de.hdodenhof.circleimageview.CircleImageView main_profile_avatar = null;
    static TextView main_profile_name = null;
    static ImageView top_imageview = null;
    static ImageView top_imageview2 = null;
    static ImageView top_imageview3 = null;
    static boolean native_lib_loaded = false;
    static boolean native_audio_lib_loaded = false;
    static String app_files_directory = "";
    // static boolean stop_me = false;
    // static Thread ToxServiceThread = null;
    static Semaphore semaphore_videoout_bitmap = new Semaphore(1);
    static Semaphore semaphore_tox_savedata = new Semaphore(1);
    Handler main_handler = null;
    static Handler main_handler_s = null;
    static Context context_s = null;
    static MainActivity main_activity_s = null;
    static volatile boolean main_activity_resumed = false;
    static AudioManager audio_manager_s = null;
    static Resources resources = null;
    static DisplayMetrics metrics = null;
    static int AudioMode_old;
    static int RingerMode_old;
    static boolean isSpeakerPhoneOn_old;
    static boolean isWiredHeadsetOn_old;
    static boolean isBluetoothScoOn_old;
    static Notification notification = null;
    static NotificationManager nmn3 = null;
    static NotificationChannel notification_channel_toxservice = null;
    static NotificationChannel notification_channel_newmessage_sound_and_vibrate = null;
    static NotificationChannel notification_channel_newmessage_sound = null;
    static NotificationChannel notification_channel_newmessage_vibrate = null;
    static NotificationChannel notification_channel_newmessage_silent = null;
    static String channelId_toxservice = null;
    static String channelId_newmessage_sound_and_vibrate = null;
    static String channelId_newmessage_sound = null;
    static String channelId_newmessage_vibrate = null;
    static String channelId_newmessage_silent = null;
    static int NOTIFICATION_ID = 293821038;
    static int WATCHDOG_NOTIFICATION_ID = 696935351;
    static FriendListFragment friend_list_fragment = null;
    static FriendListFragment contacts_list_fragment = null;
    static MessageListFragment message_list_fragment = null;
    static MessageListActivity message_list_activity = null;
    static ConferenceMessageListFragment conference_message_list_fragment = null;
    static ConferenceMessageListActivity conference_message_list_activity = null;
    static GroupMessageListFragment group_message_list_fragment = null;
    static GroupMessageListActivity group_message_list_activity = null;
    static ConferenceAudioActivity conference_audio_activity = null;
    final static String MAIN_DB_NAME = "main.db";
    final static String MAIN_VFS_NAME = "files.db";
    final static String DB_SHM_EXT = "-shm";
    final static String DB_WAL_EXT = "-wal";
    static String SD_CARD_TMP_DIR = "";
    static String SD_CARD_STATIC_DIR = "";
    static String SD_CARD_FILES_EXPORT_DIR = "";
    static String SD_CARD_FILES_DEBUG_DIR = "";
    static String SD_CARD_FILES_OUTGOING_WRAPPER_DIR = "";
    static String SD_CARD_ENC_FILES_EXPORT_DIR = "/unenc_files/";
    static String SD_CARD_ENC_CHATS_EXPORT_DIR = "/unenc_chats/";
    static String SD_CARD_FULL_FILES_EXPORT_DIR = "/fullexport/";
    static String SD_CARD_TMP_DUMMYFILE = null;
    final static int AddFriendActivity_ID = 10001;
    final static int CallingActivity_ID = 10002;
    final static int ProfileActivity_ID = 10003;
    final static int SettingsActivity_ID = 10004;
    final static int AboutpageActivity_ID = 10005;
    final static int MaintenanceActivity_ID = 10006;
    final static int WhiteListFromDozeActivity_ID = 10008;
    final static int SelectFriendSingleActivity_ID = 10009;
    final static int SelectLanguageActivity_ID = 10010;
    final static int CallingWaitingActivity_ID = 10011;
    final static int AddPrivateGroupActivity_ID = 10012;
    final static int AddPublicGroupActivity_ID = 10013;
    final static int JoinPublicGroupActivity_ID = 10014;
    final static int Notification_new_message_ID = 10023;
    final static int Notification_watchdog_trifa_stopped_ID = 10099;
    static long Notification_new_message_last_shown_timestamp = -1;
    final static long Notification_new_message_every_millis = 2000; // ~2 seconds between notifications
    final static long UPDATE_MESSAGES_WHILE_FT_ACTIVE_MILLIS = 30000; // ~30 seconds
    final static long UPDATE_MESSAGES_NORMAL_MILLIS = 500; // ~0.5 seconds
    static String temp_string_a = "";
    static ByteBuffer video_buffer_1 = null;
    static ByteBuffer video_buffer_2 = null;
    final static int audio_in_buffer_max_count = 2; // how many out play buffers? [we are now only using buffer "0" !!]
    public final static int audio_out_buffer_mult = 1;
    static ByteBuffer audio_buffer_2 = null; // given to JNI with set_JNI_audio_buffer2() for incoming audio (group and call)
    static long debug__audio_pkt_incoming = 0;
    static long debug__audio_frame_played = 0;
    static long debug__audio_play_buf_count_max = -1;
    static long debug__audio_play_buf01 = 0;
    static long debug__audio_play_buf02 = 0;
    static long debug__audio_play_buf03 = 0;
    static long debug__audio_play_buf04 = 0;
    static long debug__audio_play_buf05 = 0;
    static long debug__audio_play_buf06 = 0;
    static long debug__audio_play_factor = 0;
    static long debug__audio_play_iter = 0;
    // public static long[] audio_buffer_2_ts = new long[n_audio_in_buffer_max_count];
    // static ByteBuffer audio_buffer_play = null;
    static int audio_buffer_play_length = 0;
    static int[] audio_buffer_2_read_length = new int[audio_in_buffer_max_count];
    static TrifaToxService tox_service_fg = null;
    static long update_all_messages_global_timestamp = -1;
    final static SimpleDateFormat df_date_time_long = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    final static SimpleDateFormat df_seconds_time = new SimpleDateFormat("mm:ss");
    final static SimpleDateFormat df_date_time_long_for_filename = new SimpleDateFormat("yyyy-MM-dd_HH_mm_ss");
    final static SimpleDateFormat df_date_only = new SimpleDateFormat("yyyy-MM-dd");
    final static SimpleDateFormat df_time_only = new SimpleDateFormat("HH:mm");
    static long last_updated_fps = -1;
    final static long update_fps_every_ms = 1500L; // update every 1.5 seconds
    //
    static long tox_self_capabilites = 0;
    //
    static boolean PREF__UV_reversed = true; // TODO: on older phones this needs to be "false"
    static boolean PREF__notification_sound = true;
    static boolean PREF__notification_vibrate = false;
    static boolean PREF__notification_show_content = true; // KHANDAQ #200: show sender+preview by default
    static boolean PREF__notification = true;
    static final int MIN_AUDIO_SAMPLINGRATE_OUT = 48000;
    static final int SAMPLE_RATE_FIXED = 48000;
    static int PREF__min_audio_samplingrate_out = SAMPLE_RATE_FIXED;
    static String PREF__DB_secrect_key = "98rj93ßjw3j8j4vj9w8p9eüiü9aci092"; // this is just a dummy, this value is not used!
    static boolean PREF__DB_wal_mode = true;
    private static final String ALLOWED_CHARACTERS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!§$%&()=?,.;:-_+";
    static boolean PREF__software_echo_cancel = false;
    static int PREF__higher_video_quality = 0;
    static int PREF__higher_audio_quality = 1;
    static int PREF__video_call_quality = 0;
    static int PREF__X_audio_play_buffer_custom = 0;
    static int PREF__udp_enabled = 0; // 0 -> Tox TCP mode, 1 -> Tox UDP mode
    static int PREF__audiosource = 2; // 1 -> VOICE_COMMUNICATION, 2 -> VOICE_RECOGNITION
    static boolean PREF__orbot_enabled = false;
    static boolean PREF__local_discovery_enabled = false;
    static boolean PREF__ipv6_enabled = true;
    static boolean PREF__audiorec_asynctask = true;
    static boolean PREF__cam_recording_hint = false; // careful with this paramter!! it can break camerapreview buffer size!!
    static boolean PREF__set_fps = false;
    static boolean PREF__fps_half = false;
    static boolean PREF__h264_encoder_use_intra_refresh = true;
    static boolean PREF__conference_show_system_messages = false;
    static boolean PREF__X_battery_saving_mode = false;
    static int PREF__X_battery_saving_timeout = 15; // in minutes
    static boolean PREF__X_misc_button_enabled = false;
    static String PREF__X_misc_button_msg = "t"; // TODO: hardcoded for now!
    static boolean PREF__U_keep_nospam = false;
    static boolean PREF__use_native_audio_play = true;
    static boolean PREF__tox_set_do_not_sync_av = false;
    static boolean PREF__use_audio_rec_effects = false;
    static boolean PREF__window_security = false;
    static boolean PREF__send_read_receipts = true;
    public static int PREF__X_eac_delay_ms = 80;
    static boolean PREF__force_udp_only = false;
    static boolean PREF__use_incognito_keyboard = true;
    static boolean PREF__speakerphone_tweak = false;
    public static float PREF_mic_gain_factor = 1.0f;
    public static boolean PREF__mic_gain_factor_toggle = false;
    // from toxav/toxav.h -> valid values: 2.5, 5, 10, 20, 40 or 60 millseconds
    // 120 is also valid!!
    static int FRAME_SIZE_FIXED = 40; // this is only for recording audio!
    static int PREF__X_audio_recording_frame_size = FRAME_SIZE_FIXED; // !! 120 seems to work also !!
    static boolean PREF__X_zoom_incoming_video = false;
    static boolean PREF__use_software_aec = true;
    static boolean PREF__allow_screen_off_in_audio_call = true;
    static boolean PREF__use_H264_hw_encoding = false;
    static String PREF__camera_get_preview_format = "YV12"; // "YV12"; // "NV21";
    // static boolean PREF__use_camera_x = false;
    static boolean PREF__NO_RECYCLE_VIDEO_FRAME_BITMAP = true;
    static int PREF__audio_play_volume_percent = 100;
    static int PREF__video_play_delay_ms = GLOBAL_INIT_PLAY_DELAY;
    static int GLOBAL_AV_BUFFER_MS = 120;
    static int PREF__audio_group_play_volume_percent = 100;
    static boolean PREF__auto_accept_image = true;
    static boolean PREF__auto_accept_video = false;
    static boolean PREF__auto_accept_all_upto = false;
    // KHANDAQ (Figma "Загрузка вложений"): global attachment auto-download policy. 0=Никогда, 1=Только Wi-Fi, 2=Всегда (default = current behavior)
    static int PREF__attachment_download_mode = 2;
    static int PREF__video_cam_resolution = 0;
    static final int PREF_GLOBAL_FONT_SIZE_DEFAULT = 2;
    static int PREF__global_font_size = PREF_GLOBAL_FONT_SIZE_DEFAULT;
    static boolean PREF__allow_open_encrypted_file_via_intent = false;
    static boolean PREF__allow_file_sharing_to_trifa_via_intent = true;
    static boolean PREF__compact_friendlist = false;
    static boolean PREF__compact_chatlist = true;
    static boolean PREF__use_push_service = false;
    static String[] PREF__toxirc_muted_peers = {};
    static boolean PREF__hide_setup_push_tip = true;
    static boolean PREF__show_friendnumber_on_friendlist = false;
    static int PREF__dark_mode_pref = 0;
    static boolean PREF__allow_push_server_ntfy = false;
    static boolean PREF__allow_push_server_sunup = false;
    static boolean PREF__messageview_paging = true;
    static int PREF__message_paging_num_msgs_per_page = 50;
    static int PREF__ngc_video_bitrate = LOWER_NGC_VIDEO_BITRATE; // ~600 kbits/s -> ~60 kbytes/s
    static int PREF__ngc_video_frame_delta_ms = 120; // 120 ms -> 8.3 fps
    static int PREF__ngc_video_max_quantizer = LOWER_NGC_VIDEO_QUANTIZER; // 47 -> default, 51 -> lowest quality, 30 -> very high quality and lots of bandwidth!
    static int PREF__ngc_audio_bitrate = NGC_AUDIO_BITRATE;
    static int PREF__ngc_audio_samplerate = 48000;
    static int PREF__ngc_audio_channels = 1;
    static boolean PREF__gainprocessing_active = true;
    static boolean PREF__rnnoise_active = false;
    static boolean PREF__trust_all_webcerts = false; // HINT: !!be careful with this option!!

    static String versionName = "";
    static int versionCode = -1;
    static PackageInfo packageInfo_s = null;
    IntentFilter receiverFilter1 = null;
    IntentFilter receiverFilter2 = null;
    IntentFilter receiverFilter3 = null;
    IntentFilter receiverFilter4 = null;
    static HeadsetStateReceiver receiver1 = null;
    static HeadsetStateReceiver receiver2 = null;
    static HeadsetStateReceiver receiver3 = null;
    static HeadsetStateReceiver receiver4 = null;
    static TextView waiting_view = null;
    static ProgressBar waiting_image = null;
    static ViewGroup normal_container = null;
    static ClipboardManager clipboard;
    private ClipData clip;
    static List<Long> selected_messages = new ArrayList<Long>();
    static List<Long> selected_messages_text_only = new ArrayList<Long>();
    static List<Long> selected_messages_incoming_file = new ArrayList<Long>();
    static List<Long> selected_conference_messages = new ArrayList<Long>();
    static List<Long> selected_group_messages = new ArrayList<Long>();
    static List<Long> selected_group_messages_text_only = new ArrayList<Long>();
    static List<Long> selected_group_messages_incoming_file = new ArrayList<Long>();
    //
    // YUV conversion -------
    static ScriptIntrinsicYuvToRGB yuvToRgb = null;
    static Allocation alloc_in = null;
    static Allocation alloc_out = null;
    static Bitmap video_frame_image = null;
    static boolean video_frame_image_valid = false;
    static int buffer_size_in_bytes = 0;
    // YUV conversion -------

    // ---- lookup cache ----
    // KHANDAQ (audit): these two are read/written from BOTH the tox iterate/callback thread and the UI
    // thread (view-holder binds). A plain HashMap can corrupt its bucket chain on a concurrent put
    // (resize) → infinite loop / ANR. ConcurrentHashMap is safe (matches HelperGroup usage). Callers
    // must never put a null value (ConcurrentHashMap forbids it) — guarded at the put sites.
    static Map<String, Long> cache_pubkey_fnum = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    static Map<Long, String> cache_fnum_pubkey = new java.util.concurrent.ConcurrentHashMap<Long, String>();
    // static Map<String, String> cache_peernum_pubkey = new HashMap<String, String>();
    // static Map<String, String> cache_peername_pubkey = new HashMap<String, String>();
    static Map<String, String> cache_peername_pubkey2 = new HashMap<String, String>();
    static Map<String, Long> cache_confid_confnum = new HashMap<String, Long>();
    // ---- lookup cache ----

    // ---- lookup cache for conference drawer ----
    static Map<String, Long> lookup_peer_listnum_pubkey = new HashMap<String, Long>();
    // ---- lookup cache for conference drawer ----

    // main bottom navigation ----------
    private BottomNavigationView bottomNavigationView = null;
    private EditText mainChatSearch = null;
    private FriendListFragment contactsFragment = null;
    private FriendListFragment chatsFragment = null;
    private SettingsTabFragment settingsFragment = null;
    private ProfileTabFragment profileFragment = null;
    private int currentMainTab = R.id.bottom_nav_chats;
    private static final String PREF_CHAT_FILTER_TAB = "chat_list_filter_tab";
    private View chatFilterTabsContainer = null;
    private View chatFilterTabDirect = null;
    private View chatFilterTabGroups = null;
    private View chatFilterTabFavorites = null;
    private TextView chatFilterBadgeDirect = null;
    private TextView chatFilterBadgeGroups = null;
    private TextView chatFilterBadgeFavorites = null;
    private TextView chatFilterLabelDirect = null;
    private TextView chatFilterLabelGroups = null;
    private TextView chatFilterLabelFavorites = null;
    private ChatFilterTabIndicatorHelper chatFilterTabIndicatorHelper = null;
    private int currentChatFilterTab = FriendListFragment.CHAT_FILTER_DIRECT;
    // main bottom navigation ----------

    Spinner spinner_own_status = null;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        HelperGeneric.logI(TAG, "M:STARTUP:super onCreate");
        super.onCreate(savedInstanceState);
        HelperGeneric.logI(TAG, "M:STARTUP:onCreate");
        HelperGeneric.logI(TAG, "onCreate");

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        HelperGeneric.logI(TAG, "M:STARTUP:Lingver set");
        Log.d(TAG, "Lingver_Locale: " + Lingver.getInstance().getLocale());
        Log.d(TAG, "Lingver_Language: " + Lingver.getInstance().getLanguage());
        // Log.d(TAG, "Actual_Language: " + resources.configuration.getLocaleCompat());

        try
        {
            if (BuildConfig.DEBUG)
            {
                HelperGeneric.logI(TAG, "***** BuildConfig.DEBUG *****");
                //StrictMode.setVmPolicy(
                //        new StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build());
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        resources = this.getResources();
        metrics = resources.getDisplayMetrics();
        global_showing_messageview = false;
        global_showing_anygroupview = false;

        HelperGeneric.logI(TAG, "is_nightmode_active:" + is_nightmode_active(this));

        try
        {
            HelperGeneric.logI(TAG, "M:STARTUP:Thread=" + Thread.currentThread().getName());
        }
        catch (Exception e)
        {
        }

        HelperGeneric.logI(TAG, "M:STARTUP:setContentView start");
        setContentView(R.layout.activity_main);
        ThemeTransitionHelper.scheduleCrossfadeAfterCreate(this);
        HelperGeneric.logI(TAG, "M:STARTUP:setContentView end");

        main_profile_bar = findViewById(R.id.main_profile_bar);
        main_profile_name = findViewById(R.id.main_profile_name);
        if (main_profile_bar != null)
        {
            main_profile_bar.setOnClickListener(v -> open_profile_activity());
        }

        if (native_lib_loaded)
        {
            HelperGeneric.logI(TAG, "M:STARTUP:native_lib_loaded OK");
        }
        else
        {
            HelperGeneric.logI(TAG, "M:STARTUP:native_lib_loaded failed");
            show_wrong_credentials();
            finish();
            return;
        }

        /*
        try
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            {
                ExactAlarmChagedReceiver ar = new ExactAlarmChagedReceiver();
                IntentFilter filter = new IntentFilter();
                filter.addAction("android.intent.action.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED");
                this.registerReceiver(ar, filter);
                HelperGeneric.logI(TAG, "ExactAlarmChagedReceiver registered");
            }
            else
            {
                HelperGeneric.logI(TAG, "ExactAlarmChagedReceiver:below API S");
            }
        }
        catch(Exception e22)
        {
            HelperGeneric.logI(TAG, "ExactAlarmChagedReceiver:EE:" + e22.getMessage());
            e22.printStackTrace();
        }
        */

        HelperGeneric.logI(TAG, "M:STARTUP:toolbar");
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
        {
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        MainHeaderBrandingHelper.setup(this, toolbar);

        HelperGeneric.logI(TAG, "M:STARTUP:EmojiManager install");
        // EmojiManager.install(new IosEmojiProvider());
        EmojiManager.install(new KhandaqGoogleEmojiProvider());
        // EmojiManager.install(new com.vanniktech.emoji.twitter.TwitterEmojiProvider());


        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        PREF__DB_secrect_key = DbSecretKeyStorage.resolveDbSecretKey(this);

        if (DB_ENCRYPT && TextUtils.isEmpty(PREF__DB_secrect_key))
        {
            Log.w(TAG, "M:STARTUP:empty DB secret key");
            show_wrong_credentials();
            finish();
            return;
        }

        main_handler = new Handler(getMainLooper());
        main_handler_s = main_handler;
        context_s = this.getBaseContext();
        main_activity_s = this;
        TRIFAGlobals.CONFERENCE_CHAT_BG_CORNER_RADIUS_IN_PX = (int) HelperGeneric.dp2px(10);
        TRIFAGlobals.CONFERENCE_CHAT_DRAWER_ICON_CORNER_RADIUS_IN_PX = (int) HelperGeneric.dp2px(20);

        if ((!TOX_SERVICE_STARTED) || (orma == null))
        {
            HelperGeneric.logI(TAG, "M:STARTUP:init DB (background)");

            // KHANDAQ: initialize the (SQLCipher-encrypted) database on a BACKGROUND thread. Its key
            // derivation + first-run table creation is heavy and on slower devices took >5s on the UI
            // thread, which triggered an ANR ("приложение не отвечает") that looked like a crash.
            // onCreate now returns immediately; the rest of startup runs in onCreateContinue() once
            // the database is ready.
            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        String dbs_path = getDir("dbs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_DB_NAME;
                        File database_dir = new File(new File(dbs_path).getParent());
                        database_dir.mkdirs();

                        if (DB_ENCRYPT)
                        {
                            orma = OrmaDatabase_wrapper(dbs_path, PREF__DB_secrect_key, PREF__DB_wal_mode);
                            DbSecretKeyStorage.persistLastWorkingDbSecretKey(MainActivity.this, PREF__DB_secrect_key);
                        }
                        else
                        {
                            orma = OrmaDatabase_wrapper(dbs_path, null, PREF__DB_wal_mode);
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        HelperGeneric.logI(TAG, "M:STARTUP:init DB:EE1");
                        HelperGeneric.logI(TAG, "db:EE1:" + e.getMessage());
                        runOnUiThread(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                show_wrong_credentials();
                                finish();
                            }
                        });
                        return;
                    }

                    runOnUiThread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            // KHANDAQ: the activity may have been finished/destroyed while the DB
                            // initialized in the background — don't continue startup on a dead activity.
                            if (isFinishing() || isDestroyed())
                            {
                                HelperGeneric.logI(TAG, "M:STARTUP:onCreateContinue skipped (activity gone)");
                                return;
                            }
                            onCreateContinue(savedInstanceState);
                        }
                    });
                }
            }).start();

            return;
        }

        onCreateContinue(savedInstanceState);
    }

    private void onCreateContinue(final Bundle savedInstanceState)
    {
        // KHANDAQ: re-derive the locals that were declared in onCreate before the DB init was moved
        // off the UI thread (both are only used from here on).
        final Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);

        FavoritesChatHelper.ensureInitialized(this);

        if (PREF__window_security)
        {
            // prevent screenshots and also dont show the window content in recent activity screen
            initializeScreenshotSecurity(this);
        }

        //        try
        //        {
        //            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        //        }
        //        catch (Exception e)
        //        {
        //            e.printStackTrace();
        //            HelperGeneric.logI(TAG, "onCreate:setThreadPriority:EE:" + e.getMessage());
        //        }

        HelperGeneric.logI(TAG, "M:STARTUP:getVersionInfo");
        getVersionInfo();

        try
        {
            packageInfo_s = getPackageManager().getPackageInfo(getPackageName(), 0);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        //        if (canceller == null)
        //        {
        //            canceller = new EchoCanceller();
        //        }


        //        try
        //        {
        //            ((Toolbar) getSupportActionBar().getCustomView().getParent()).setContentInsetsAbsolute(0, 0);
        //        }
        //        catch (Exception e)
        //        {
        //            e.printStackTrace();
        //        }
        bootstrapping = false;
        waiting_view = (TextView) findViewById(R.id.waiting_view);
        waiting_image = (ProgressBar) findViewById(R.id.waiting_image);
        normal_container = (ViewGroup) findViewById(R.id.normal_container);
        waiting_view.setVisibility(View.GONE);
        waiting_image.setVisibility(View.GONE);
        normal_container.setVisibility(View.VISIBLE);
        SD_CARD_TMP_DIR = getExternalFilesDir(null).getAbsolutePath() + "/tmpdir/";
        SD_CARD_STATIC_DIR = getExternalFilesDir(null).getAbsolutePath() + "/_staticdir/";
        SD_CARD_FILES_EXPORT_DIR = getExternalFilesDir(null).getAbsolutePath() + "/vfs_export/";
        SD_CARD_FILES_DEBUG_DIR = getExternalFilesDir(null).getAbsolutePath() + "/debug/";
        SD_CARD_FILES_OUTGOING_WRAPPER_DIR = getExternalFilesDir(null).getAbsolutePath() + "/outgoing/";
        // HelperGeneric.logI(TAG, "SD_CARD_FILES_EXPORT_DIR:" + SD_CARD_FILES_EXPORT_DIR);
        SD_CARD_TMP_DUMMYFILE = HelperGeneric.make_some_static_dummy_file(this.getBaseContext());
        audio_manager_s = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        HelperGeneric.logI(TAG, "java.library.path:" + System.getProperty("java.library.path"));
        nmn3 = (NotificationManager) context_s.getSystemService(NOTIFICATION_SERVICE);

        try
        {
            na_set_audio_play_volume_percent(100);
        }
        catch (Exception ee)
        {
            ee.printStackTrace();
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        {
            HelperGeneric.logI(TAG, "M:STARTUP:notification channels");
            HelperMsgNotification.ensure_notification_channels(this, nmn3);
            String channelName;
            HelperToxNotification.ensureChannel(this);
            channelId_toxservice = HelperToxNotification.CHANNEL_ID_TOX_SERVICE;
            channelName = getString(R.string.notification_channel_toxservice);
            int importance = NotificationManager.IMPORTANCE_MIN;
            notification_channel_toxservice = new NotificationChannel(channelId_toxservice, channelName, importance);
            notification_channel_toxservice.setDescription(channelId_toxservice);
            notification_channel_toxservice.setSound(null, null);
            notification_channel_toxservice.enableVibration(false);
            nmn3.createNotificationChannel(notification_channel_toxservice);
        }

        // prefs ----------
        PREF__UV_reversed = settings.getBoolean("video_uv_reversed", true);
        PREF__notification_sound = settings.getBoolean("notifications_new_message_sound", true);
        PREF__notification_vibrate = settings.getBoolean("notifications_new_message_vibrate", false);
        PREF__notification_show_content = settings.getBoolean("notification_show_content", true);
        PREF__notification = settings.getBoolean("notifications_new_message", true);
        PREF__software_echo_cancel = settings.getBoolean("software_echo_cancel", false);
        PREF__fps_half = settings.getBoolean("fps_half", false);
        PREF__h264_encoder_use_intra_refresh = settings.getBoolean("h264_encoder_use_intra_refresh", true);
        PREF__U_keep_nospam = settings.getBoolean("U_keep_nospam", false);
        PREF__set_fps = settings.getBoolean("set_fps", false);
        PREF__conference_show_system_messages = settings.getBoolean("conference_show_system_messages", false);
        PREF__X_battery_saving_mode = settings.getBoolean("X_battery_saving_mode", false);
        PREF__X_misc_button_enabled = settings.getBoolean("X_misc_button_enabled", false);
        PREF__local_discovery_enabled = settings.getBoolean("local_discovery_enabled", true);
        PREF__force_udp_only = settings.getBoolean("force_udp_only", false);
        PREF__use_incognito_keyboard = settings.getBoolean("use_incognito_keyboard", true);
        PREF__speakerphone_tweak = settings.getBoolean("speakerphone_tweak", false);
        PREF__mic_gain_factor_toggle = settings.getBoolean("mic_gain_factor_toggle", false);
        PREF__window_security = settings.getBoolean("window_security", false);
        PREF__send_read_receipts = settings.getBoolean("pref_send_read_receipts", true);
        PREF__use_native_audio_play = settings.getBoolean("X_use_native_audio_play", true);
        PREF__use_H264_hw_encoding = settings.getBoolean("use_H264_hw_encoding", false);

        try
        {
            if (settings.getString("X_battery_saving_timeout", "15").compareTo("15") == 0)
            {
                PREF__X_battery_saving_timeout = 15;
            }
            else
            {
                PREF__X_battery_saving_timeout = Integer.parseInt(settings.getString("X_battery_saving_timeout", "15"));
                HelperGeneric.logI(TAG, "PREF__X_battery_saving_timeout:1:=" + PREF__X_battery_saving_timeout);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__X_battery_saving_timeout = 15;
        }

        // NOTE (Khandaq): this migration sets PREF__udp_enabled=false ("TCP mode"), but it is
        // currently a NO-OP at the transport level: the native layer in create_tox()
        // (jni-c-toxcore.c, "options.udp_enabled = true;") unconditionally re-enables UDP, ignoring
        // this flag. So the shipped libjni-c-toxcore.so ALWAYS runs UDP-enabled regardless of what we
        // set here. That is actually acceptable today: NGC public-group discovery (the onion DHT
        // announce-lookup a chat-id join depends on) needs UDP, and pure-TCP discovery is an unsolved
        // toxcore-level gap. The real consequence is for joiners on UDP-blocked networks (mobile
        // carriers / some regions): their chat-id join cannot complete (see HelperGroup
        // .maybe_hint_udp_blocked) — the working bypass is a friend-assisted invite. The pref is kept
        // so a future rebuilt .so that honors it can flip transport without another migration.
        // Do NOT trust this flag to mean "TCP-only" until jni-c-toxcore.c:699 is rebuilt and shipped.
        if (!settings.getBoolean("khandaq_ngc_udp_tcp_v3", false))
        {
            settings.edit().putBoolean("udp_enabled", false).
                    putBoolean("khandaq_ngc_udp_tcp_v3", true).apply();
        }

        boolean tmp1 = settings.getBoolean("udp_enabled", false);

        if (tmp1)
        {
            PREF__udp_enabled = 1;
        }
        else
        {
            PREF__udp_enabled = 0;
        }

        try
        {
            PREF__video_call_quality = Integer.parseInt(settings.getString("video_call_quality", "1"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__video_call_quality = 1;
        }


        try
        {
            PREF__higher_audio_quality = Integer.parseInt(settings.getString("higher_audio_quality", "1"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__higher_audio_quality = 1;
        }

        if (PREF__higher_audio_quality == 2)
        {
            GLOBAL_AUDIO_BITRATE = HIGHER_GLOBAL_AUDIO_BITRATE;
        }
        else if (PREF__higher_audio_quality == 1)
        {
            GLOBAL_AUDIO_BITRATE = NORMAL_GLOBAL_AUDIO_BITRATE;
        }
        else
        {
            GLOBAL_AUDIO_BITRATE = LOWER_GLOBAL_AUDIO_BITRATE;
        }

        // ------- access the clipboard -------
        clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        // ------- access the clipboard -------
        PREF__orbot_enabled = settings.getBoolean("orbot_enabled", false);

        if (PREF__orbot_enabled)
        {
            HelperGeneric.logI(TAG, "M:STARTUP:wait for orbot");
            boolean orbot_installed = OrbotHelper.isOrbotInstalled(this);

            //if (orbot_installed)
            {
                boolean orbot_running = orbot_is_really_running; // OrbotHelper.isOrbotRunning(this);
                HelperGeneric.logI(TAG, "waiting_for_orbot_info:orbot_running=" + orbot_running);

                if (orbot_running)
                {
                    HelperGeneric.logI(TAG, "waiting_for_orbot_info:F1");
                    HelperGeneric.waiting_for_orbot_info(false);
                    OrbotHelper.get(this).statusTimeout(120 * 1000).addStatusCallback(new StatusCallback()
                    {
                        @Override
                        public void onEnabled(Intent statusIntent)
                        {
                        }

                        @Override
                        public void onStarting()
                        {
                        }

                        @Override
                        public void onStopping()
                        {
                        }

                        @Override
                        public void onDisabled()
                        {
                            // we got a broadcast with a status of off, so keep waiting
                        }

                        @Override
                        public void onStatusTimeout()
                        {
                            // throw new RuntimeException("Orbot status request timed out");
                            HelperGeneric.logI(TAG, "waiting_for_orbot_info:EEO1:" + "Orbot status request timed out");
                        }

                        @Override
                        public void onNotYetInstalled()
                        {
                        }
                    }).init(); // allow 60 seconds to connect to Orbot
                }
                else
                {
                    orbot_is_really_running = false;

                    if (OrbotHelper.requestStartTor(this))
                    {
                        HelperGeneric.logI(TAG, "waiting_for_orbot_info:*T2");
                        HelperGeneric.waiting_for_orbot_info(true);
                    }
                    else
                    {
                        // should never get here
                        HelperGeneric.logI(TAG, "waiting_for_orbot_info:F3");
                        HelperGeneric.waiting_for_orbot_info(false);
                    }

                    OrbotHelper.get(this).statusTimeout(120 * 1000).addStatusCallback(new StatusCallback()
                    {
                        @Override
                        public void onEnabled(Intent statusIntent)
                        {
                        }

                        @Override
                        public void onStarting()
                        {
                        }

                        @Override
                        public void onStopping()
                        {
                        }

                        @Override
                        public void onDisabled()
                        {
                            // we got a broadcast with a status of off, so keep waiting
                        }

                        @Override
                        public void onStatusTimeout()
                        {
                            // throw new RuntimeException("Orbot status request timed out");
                            HelperGeneric.logI(TAG, "waiting_for_orbot_info:EEO2:" + "Orbot status request timed out");
                        }

                        @Override
                        public void onNotYetInstalled()
                        {
                        }
                    }).init(); // allow 60 seconds to connect to Orbot
                }
            }
            /*
            else
            {
                HelperGeneric.logI(TAG, "waiting_for_orbot_info:F4");
                HelperGeneric.waiting_for_orbot_info(false);
                Intent orbot_get = OrbotHelper.getOrbotInstallIntent(this);

                try
                {
                    startActivity(orbot_get);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
             */
        }
        else
        {
            HelperGeneric.logI(TAG, "waiting_for_orbot_info:F5");
            HelperGeneric.waiting_for_orbot_info(false);
        }

        HelperGeneric.logI(TAG, "PREF__UV_reversed:2=" + PREF__UV_reversed);
        HelperGeneric.logI(TAG, "PREF__notification_sound:2=" + PREF__notification_sound);
        HelperGeneric.logI(TAG, "PREF__notification_vibrate:2=" + PREF__notification_vibrate);

        try
        {
            if (settings.getString("min_audio_samplingrate_out", "8000").compareTo("Auto") == 0)
            {
                PREF__min_audio_samplingrate_out = 8000;
            }
            else
            {
                PREF__min_audio_samplingrate_out = Integer.parseInt(
                        settings.getString("min_audio_samplingrate_out", "" + MIN_AUDIO_SAMPLINGRATE_OUT));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__min_audio_samplingrate_out = MIN_AUDIO_SAMPLINGRATE_OUT;
        }

        // ------- FIXED -------
        PREF__min_audio_samplingrate_out = SAMPLE_RATE_FIXED;
        // ------- FIXED -------

        try
        {
            PREF__allow_screen_off_in_audio_call = settings.getBoolean("allow_screen_off_in_audio_call", true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_screen_off_in_audio_call = true;
        }

        try
        {
            PREF__auto_accept_image = settings.getBoolean("auto_accept_image", true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__auto_accept_image = true;
        }

        try
        {
            PREF__auto_accept_video = settings.getBoolean("auto_accept_video", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__auto_accept_video = false;
        }

        try
        {
            PREF__auto_accept_all_upto = settings.getBoolean("auto_accept_all_upto", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__auto_accept_all_upto = false;
        }

        try
        {
            PREF__attachment_download_mode = settings.getInt("attachment_download_mode", 2);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__attachment_download_mode = 2;
        }

        try
        {
            PREF__X_zoom_incoming_video = settings.getBoolean("X_zoom_incoming_video", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__X_zoom_incoming_video = false;
        }

        try
        {
            PREF__X_audio_recording_frame_size = Integer.parseInt(
                    settings.getString("X_audio_recording_frame_size", "" + 40));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__X_audio_recording_frame_size = 40;
        }

        // ------- FIXED -------
        PREF__X_audio_recording_frame_size = FRAME_SIZE_FIXED;
        // ------- FIXED -------

        try
        {
            PREF__video_cam_resolution = Integer.parseInt(settings.getString("video_cam_resolution", "" + 1));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__video_cam_resolution = 1;
        }

        HelperGeneric.applyGlobalVideoBitrateFromPrefs(this);

        try
        {
            PREF__global_font_size = Integer.parseInt(
                    settings.getString("global_font_size", "" + PREF_GLOBAL_FONT_SIZE_DEFAULT));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__global_font_size = PREF_GLOBAL_FONT_SIZE_DEFAULT;
        }

        PREF__camera_get_preview_format = settings.getString("camera_get_preview_format", "YV12");


        try
        {
            PREF__compact_friendlist = settings.getBoolean("compact_friendlist", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__compact_friendlist = false;
        }

        try
        {
            PREF__allow_file_sharing_to_trifa_via_intent = settings.getBoolean("allow_file_sharing_to_trifa_via_intent",
                                                                               true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_file_sharing_to_trifa_via_intent = true;
        }

        try
        {
            PREF__allow_open_encrypted_file_via_intent = settings.getBoolean("allow_open_encrypted_file_via_intent",
                                                                             false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_open_encrypted_file_via_intent = false;
        }

        PREF__compact_chatlist = true;

        try
        {
            PREF__allow_push_server_ntfy = settings.getBoolean("allow_push_server_ntfy", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_push_server_ntfy = false;
        }

        try
        {
            PREF__allow_push_server_sunup = settings.getBoolean("allow_push_server_sunup", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_push_server_sunup = false;
        }

        try
        {
            PREF__use_push_service = settings.getBoolean("use_push_service", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__use_push_service = false;
        }

        try
        {
            PREF__gainprocessing_active = settings.getBoolean("gainprocessing_active", true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__gainprocessing_active = true;
        }

        try
        {
            PREF__rnnoise_active = settings.getBoolean("rnnoise_active", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__rnnoise_active = false;
        }

        //    PREF__use_camera_x = false;

        // prefs ----------

        // TODO: remake this into something nicer ----------
        top_imageview = (ImageView) this.findViewById(R.id.main_maintopimage);
        if (top_imageview != null)
        {
            top_imageview.setVisibility(View.GONE);
        }

        // Legacy header icons removed with profile bar (1C); tips disabled until re-homed.
        top_imageview2 = null;
        top_imageview3 = null;

        if ((PREF__U_keep_nospam == true) && (top_imageview2 != null))
        {
            top_imageview2.setBackgroundColor(Color.TRANSPARENT);
            // top_imageview.setBackgroundColor(Color.parseColor("#C62828"));
            final Drawable d1 = new IconicsDrawable(this).icon(FontAwesome.Icon.faw_exclamation_circle).paddingDp(
                    15).color(getResources().getColor(R.color.md_red_600)).sizeDp(100);
            top_imageview2.setImageDrawable(d1);
            fadeInAndShowImage(top_imageview2, 5000);
        }
        else if (top_imageview2 != null)
        {
            top_imageview2.setVisibility(View.GONE);
        }

        own_push_token_load();
        if ((PREF__hide_setup_push_tip == false) && (TRIFAGlobals.global_notification_token == null) &&
            (top_imageview3 != null))
        {
            // show icon for PUSH tip
            top_imageview3.setBackgroundColor(Color.TRANSPARENT);
            final Drawable d1 = new IconicsDrawable(this).icon(FontAwesome.Icon.faw_info_circle).paddingDp(15).color(
                    getResources().getColor(R.color.md_yellow_600)).sizeDp(100);
            top_imageview3.setImageDrawable(d1);
            fadeInAndShowImage(top_imageview3, 5000);
        }
        else if (top_imageview3 != null)
        {
            top_imageview3.setVisibility(View.GONE);
        }

        if (top_imageview != null)
        {
            fadeInAndShowImage(top_imageview, 5000);
        }
        // update_main_profile_bar() deferred until after VFS mount (needs IOCipher)
        // TODO: remake this into something nicer ----------
        // --------- status spinner ---------
        spinner_own_status = (Spinner) findViewById(R.id.spinner_own_status);
        ArrayList<String> own_online_status_string_values = new ArrayList<String>(
                Arrays.asList(getString(R.string.MainActivity_available), getString(R.string.MainActivity_away),
                              getString(R.string.MainActivity_busy)));
        ArrayAdapter<String> myAdapter = new OwnStatusSpinnerAdapter(this, R.layout.own_status_spinner_item,
                                                                     own_online_status_string_values);

        if (spinner_own_status != null)
        {
            spinner_own_status.setAdapter(myAdapter);
            spinner_own_status.setSelection(global_tox_self_status);
            spinner_own_status.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
            {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View v, int position, long id)
                {
                    if (is_tox_started)
                    {
                        try
                        {
                            if (id == 0)
                            {
                                // status: available
                                tox_self_set_status(TOX_USER_STATUS_NONE.value);
                                global_tox_self_status = TOX_USER_STATUS_NONE.value;
                            }
                            else if (id == 1)
                            {
                                // status: away
                                tox_self_set_status(TOX_USER_STATUS_AWAY.value);
                                global_tox_self_status = TOX_USER_STATUS_AWAY.value;
                            }
                            else if (id == 2)
                            {
                                // status: busy
                                tox_self_set_status(TOX_USER_STATUS_BUSY.value);
                                global_tox_self_status = TOX_USER_STATUS_BUSY.value;
                            }
                        }
                        catch (Exception e2)
                        {
                            e2.printStackTrace();
                        }
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parentView)
                {
                    // your code here
                }
            });
        }

        // --------- status spinner ---------
        // get permission ----------
        HelperGeneric.logI(TAG, "M:STARTUP:permissions");
        MainActivityPermissionsDispatcher.dummyForPermissions001WithPermissionCheck(this);
        // get permission ----------
        setupBottomNavigation(savedInstanceState);
        apply_main_header_icon_tint(toolbar);
        // reset calling state
        Callstate.state = 0;
        Callstate.tox_call_state = ToxVars.TOXAV_FRIEND_CALL_STATE.TOXAV_FRIEND_CALL_STATE_NONE.value;
        Callstate.call_first_video_frame_received = -1;
        Callstate.call_first_audio_frame_received = -1;
        VIDEO_FRAME_RATE_OUTGOING = 0;
        last_video_frame_sent = -1;
        VIDEO_FRAME_RATE_INCOMING = 0;
        last_video_frame_received = -1;
        count_video_frame_received = 0;
        count_video_frame_sent = 0;
        HelperGeneric.logI(TAG, "friend_pubkey:set:002");
        Callstate.friend_pubkey = "-1";
        Callstate.audio_speaker = false;
        Callstate.other_audio_enabled = 1;
        Callstate.other_video_enabled = 1;
        Callstate.my_audio_enabled = 1;
        Callstate.my_video_enabled = 1;

        String native_api = getNativeLibAPI();
        HelperGeneric.logI(TAG, "loaded:native_api=" + native_api);
        HelperGeneric.logI(TAG, "loaded:c-toxcore:v" + tox_version_major() + "." + tox_version_minor() + "." + tox_version_patch());
        HelperGeneric.logI(TAG, "loaded:jni-c-toxcore:v" + jnictoxcore_version());
        HelperGeneric.logI(TAG, "loaded:libavutil:v" + libavutil_version());

        if ((!TOX_SERVICE_STARTED) || (vfs == null))
        {
            HelperGeneric.logI(TAG, "M:STARTUP:init VFS");

            if (VFS_ENCRYPT)
            {
                try
                {
                    String dbFile = getDir("vfs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_VFS_NAME;
                    File database_dir = new File(new File(dbFile).getParent());
                    database_dir.mkdirs();
                    // HelperGeneric.logI(TAG, "vfs:path=" + dbFile);
                    vfs = VirtualFileSystem.get();

                    try
                    {
                        if (!vfs.isMounted())
                        {
                            HelperGeneric.logI(TAG, "VFS:mount:[1]:start:" + Thread.currentThread().getId() + ":" +
                                       Thread.currentThread().getName());
                            vfs.mount(dbFile, PREF__DB_secrect_key);
                            HelperGeneric.logI(TAG, "VFS:mount:[1]:end");
                        }
                    }
                    catch (Exception ee)
                    {
                        HelperGeneric.logI(TAG, "vfs:EE1:" + ee.getMessage());
                        ee.printStackTrace();
                        HelperGeneric.logI(TAG, "VFS:mount:[2]:start:" + Thread.currentThread().getId() + ":" +
                                   Thread.currentThread().getName());
                        vfs.mount(dbFile, PREF__DB_secrect_key);
                        HelperGeneric.logI(TAG, "VFS:mount:[2]:end");
                    }

                    // HelperGeneric.logI(TAG, "vfs:open(1)=OK:path=" + dbFile);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "vfs:EE2:" + e.getMessage());
                    String dbFile = getDir("vfs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_VFS_NAME;

                    if (DELETE_SQL_AND_VFS_ON_ERROR)
                    {
                        try
                        {
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**--------:" + dbFile);
                            new File(dbFile).delete();
                            HelperGeneric.logI(TAG, "vfs:**deleting database**--------:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                            HelperGeneric.logI(TAG, "vfs:**deleting database**:" + dbFile);
                        }
                        catch (Exception e3)
                        {
                            e3.printStackTrace();
                            HelperGeneric.logI(TAG, "vfs:EE3:" + e3.getMessage());
                        }
                    }

                    try
                    {
                        // HelperGeneric.logI(TAG, "vfs:path=" + dbFile);
                        vfs = VirtualFileSystem.get();
                        vfs.createNewContainer(dbFile, PREF__DB_secrect_key);
                        HelperGeneric.logI(TAG, "VFS:mount:[3]:start:" + Thread.currentThread().getId() + ":" +
                                   Thread.currentThread().getName());
                        vfs.mount(PREF__DB_secrect_key);
                        HelperGeneric.logI(TAG, "VFS:mount:[3]:end");
                        // HelperGeneric.logI(TAG, "vfs:open(2)=OK:path=" + dbFile);
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                        HelperGeneric.logI(TAG, "vfs:EE4:" + e2.getMessage());
                        show_wrong_credentials();
                        finish();
                        return;
                    }
                }

                // HelperGeneric.logI(TAG, "vfs:encrypted:(1)prefix=" + VFS_PREFIX);
            }
            else
            {
                // VFS not encrypted -------------
                VFS_PREFIX = getExternalFilesDir(null).getAbsolutePath() + "/vfs/";
                // HelperGeneric.logI(TAG, "vfs:not_encrypted:(2)prefix=" + VFS_PREFIX);
                // VFS not encrypted -------------
            }
        }

        update_main_profile_bar();

        // cleanup temp dirs --------
        if (!TOX_SERVICE_STARTED)
        {
            HelperGeneric.logI(TAG, "M:STARTUP:cleanup_temp_dirs (background)");
            HelperGeneric.cleanup_temp_dirs();
        }

        // cleanup temp dirs --------
        // ---------- DEBUG, just a test ----------
        // ---------- DEBUG, just a test ----------
        // ---------- DEBUG, just a test ----------
        //        if (VFS_ENCRYPT)
        //        {
        //            if (vfs.isMounted())
        //            {
        //                vfs_listFilesAndFilesSubDirectories("/", 0, "");
        //            }
        //        }
        //        // ---------- DEBUG, just a test ----------
        //        // ---------- DEBUG, just a test ----------
        //        // ---------- DEBUG, just a test ----------
        app_files_directory = getFilesDir().getAbsolutePath();
        // --- forground service ---
        // --- forground service ---
        // --- forground service ---
        Intent i = new Intent(this, TrifaToxService.class);

        if (!TOX_SERVICE_STARTED)
        {
            HelperGeneric.logI(TAG, "M:STARTUP:start ToxService");
            HelperGeneric.logI(TAG, "set_all_conferences_inactive:005");
            HelperConference.set_all_conferences_inactive();
            startService(i);
        }

        if (!TOX_SERVICE_STARTED)
        {
            HelperGeneric.logI(TAG, "M:STARTUP:start ToxThread");
            tox_thread_start();
        }

        // --- forground service ---
        // --- forground service ---
        // --- forground service ---
        receiverFilter1 = new IntentFilter(AudioManager.ACTION_HEADSET_PLUG);
        receiver1 = new HeadsetStateReceiver();
        registerReceiver(receiver1, receiverFilter1);
        receiverFilter2 = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        receiver2 = new HeadsetStateReceiver();
        registerReceiver(receiver2, receiverFilter2);
        // --
        receiverFilter3 = new IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        receiver3 = new HeadsetStateReceiver();
        registerReceiver(receiver3, receiverFilter3);
        // --
        receiverFilter4 = new IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
        receiver4 = new HeadsetStateReceiver();
        registerReceiver(receiver4, receiverFilter4);
        // --
        MainActivity.set_av_call_status(Callstate.state);

        /*
        ////// WATCHDOG //////
        ////// WATCHDOG //////
        ////// WATCHDOG //////
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            Context context_fg1 = getApplicationContext();
            Intent intent_fg1 = new Intent(this, WatchdogService.class);
            context_fg1.startForegroundService(intent_fg1);
        }
        ////// WATCHDOG //////
        ////// WATCHDOG //////
        ////// WATCHDOG //////
        */

        HelperGeneric.logI(TAG, "M:STARTUP:-- DONE --");
    }

    void upgrade_db_schema_do(int old_version, int new_version)
    {
        if (new_version == 10015) {
            run_multi_sql("CREATE TABLE `BootstrapNodeEntryDB` (`num` INTEGER NOT NULL, `udp_node` BOOLEAN NOT NULL, `ip` TEXT NOT NULL, `port` INTEGER NOT NULL, `key_hex` TEXT NOT NULL, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("CREATE INDEX `index_num_on_BootstrapNodeEntryDB` ON `BootstrapNodeEntryDB` (`num`)");
            run_multi_sql("CREATE INDEX `index_udp_node_on_BootstrapNodeEntryDB` ON `BootstrapNodeEntryDB` (`udp_node`)");
            run_multi_sql("CREATE INDEX `index_ip_on_BootstrapNodeEntryDB` ON `BootstrapNodeEntryDB` (`ip`)");
            run_multi_sql("CREATE INDEX `index_port_on_BootstrapNodeEntryDB` ON `BootstrapNodeEntryDB` (`port`)");
            run_multi_sql("CREATE INDEX `index_key_hex_on_BootstrapNodeEntryDB` ON `BootstrapNodeEntryDB` (`key_hex`)");
            run_multi_sql("CREATE TABLE `ConferenceDB` (`who_invited__tox_public_key_string` TEXT NOT NULL, `name` TEXT , `peer_count` INTEGER NOT NULL DEFAULT -1, `own_peer_number` INTEGER NOT NULL DEFAULT -1, `kind` INTEGER NOT NULL DEFAULT 0, `tox_conference_number` INTEGER NOT NULL DEFAULT -1, `conference_active` BOOLEAN NOT NULL DEFAULT false, `notification_silent` BOOLEAN DEFAULT false, `conference_identifier` TEXT PRIMARY KEY)");
            run_multi_sql("CREATE INDEX `index_who_invited__tox_public_key_string_on_ConferenceDB` ON `ConferenceDB` (`who_invited__tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_name_on_ConferenceDB` ON `ConferenceDB` (`name`)");
            run_multi_sql("CREATE INDEX `index_peer_count_on_ConferenceDB` ON `ConferenceDB` (`peer_count`)");
            run_multi_sql("CREATE INDEX `index_own_peer_number_on_ConferenceDB` ON `ConferenceDB` (`own_peer_number`)");
            run_multi_sql("CREATE INDEX `index_kind_on_ConferenceDB` ON `ConferenceDB` (`kind`)");
            run_multi_sql("CREATE INDEX `index_tox_conference_number_on_ConferenceDB` ON `ConferenceDB` (`tox_conference_number`)");
            run_multi_sql("CREATE INDEX `index_conference_active_on_ConferenceDB` ON `ConferenceDB` (`conference_active`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_ConferenceDB` ON `ConferenceDB` (`notification_silent`)");
            run_multi_sql("CREATE TABLE `ConferenceMessage` (`conference_identifier` TEXT NOT NULL DEFAULT -1, `tox_peerpubkey` TEXT NOT NULL, `tox_peername` TEXT , `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER , `rcvd_timestamp` INTEGER , `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT , `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("CREATE INDEX `index_conference_identifier_on_ConferenceMessage` ON `ConferenceMessage` (`conference_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_peerpubkey_on_ConferenceMessage` ON `ConferenceMessage` (`tox_peerpubkey`)");
            run_multi_sql("CREATE INDEX `index_tox_peername_on_ConferenceMessage` ON `ConferenceMessage` (`tox_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_ConferenceMessage` ON `ConferenceMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_ConferenceMessage` ON `ConferenceMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_ConferenceMessage` ON `ConferenceMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_ConferenceMessage` ON `ConferenceMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_ConferenceMessage` ON `ConferenceMessage` (`is_new`)");
            run_multi_sql("CREATE TABLE `ConferencePeerCacheDB` (`conference_identifier` TEXT NOT NULL, `peer_pubkey` TEXT NOT NULL, `peer_name` TEXT NOT NULL, `last_update_timestamp` INTEGER NOT NULL DEFAULT -1, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("CREATE UNIQUE INDEX `index_conference_identifier_peer_pubkey_on_ConferencePeerCacheDB` ON `ConferencePeerCacheDB` (`conference_identifier`, `peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_conference_identifier_on_ConferencePeerCacheDB` ON `ConferencePeerCacheDB` (`conference_identifier`)");
            run_multi_sql("CREATE INDEX `index_peer_pubkey_on_ConferencePeerCacheDB` ON `ConferencePeerCacheDB` (`peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_peer_name_on_ConferencePeerCacheDB` ON `ConferencePeerCacheDB` (`peer_name`)");
            run_multi_sql("CREATE INDEX `index_last_update_timestamp_on_ConferencePeerCacheDB` ON `ConferencePeerCacheDB` (`last_update_timestamp`)");
            run_multi_sql("CREATE TABLE `FileDB` (`kind` INTEGER NOT NULL, `direction` INTEGER NOT NULL, `tox_public_key_string` TEXT NOT NULL, `path_name` TEXT NOT NULL, `file_name` TEXT NOT NULL, `filesize` INTEGER NOT NULL DEFAULT -1, `is_in_VFS` BOOLEAN NOT NULL DEFAULT true, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("CREATE INDEX `index_kind_on_FileDB` ON `FileDB` (`kind`)");
            run_multi_sql("CREATE INDEX `index_direction_on_FileDB` ON `FileDB` (`direction`)");
            run_multi_sql("CREATE INDEX `index_tox_public_key_string_on_FileDB` ON `FileDB` (`tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_FileDB` ON `FileDB` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_FileDB` ON `FileDB` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_filesize_on_FileDB` ON `FileDB` (`filesize`)");
            run_multi_sql("CREATE INDEX `index_is_in_VFS_on_FileDB` ON `FileDB` (`is_in_VFS`)");
            run_multi_sql("CREATE TABLE `Filetransfer` (`tox_public_key_string` TEXT NOT NULL, `direction` INTEGER NOT NULL, `file_number` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `state` INTEGER NOT NULL, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `path_name` TEXT NOT NULL, `file_name` TEXT NOT NULL, `fos_open` BOOLEAN NOT NULL DEFAULT false, `filesize` INTEGER NOT NULL DEFAULT -1, `current_position` INTEGER NOT NULL DEFAULT 0, `message_id` INTEGER NOT NULL DEFAULT -1, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("CREATE INDEX `index_tox_public_key_string_on_Filetransfer` ON `Filetransfer` (`tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Filetransfer` ON `Filetransfer` (`direction`)");
            run_multi_sql("CREATE INDEX `index_file_number_on_Filetransfer` ON `Filetransfer` (`file_number`)");
            run_multi_sql("CREATE INDEX `index_kind_on_Filetransfer` ON `Filetransfer` (`kind`)");
            run_multi_sql("CREATE INDEX `index_state_on_Filetransfer` ON `Filetransfer` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Filetransfer` ON `Filetransfer` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Filetransfer` ON `Filetransfer` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_Filetransfer` ON `Filetransfer` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_Filetransfer` ON `Filetransfer` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_message_id_on_Filetransfer` ON `Filetransfer` (`message_id`)");
            run_multi_sql("CREATE TABLE `FriendList` (`name` TEXT , `alias_name` TEXT , `status_message` TEXT , `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT , `avatar_filename` TEXT , `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT -1, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE TABLE `Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT -1, `filetransfer_id` INTEGER NOT NULL DEFAULT -1, `sent_timestamp` INTEGER , `rcvd_timestamp` INTEGER , `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT , `filename_fullpath` TEXT , `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE TABLE `TRIFADatabaseGlobals` (`key` TEXT NOT NULL, `value` TEXT NOT NULL)");
            run_multi_sql("CREATE INDEX `index_key_on_TRIFADatabaseGlobals` ON `TRIFADatabaseGlobals` (`key`)");
            run_multi_sql("CREATE INDEX `index_value_on_TRIFADatabaseGlobals` ON `TRIFADatabaseGlobals` (`value`)");
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `filename_fullpath`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `filename_fullpath`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
        }

        if (new_version == 10082) {
            run_multi_sql("CREATE TABLE `__temp_ConferenceMessage` (`conference_identifier` TEXT NOT NULL DEFAULT - 1, `tox_peerpubkey` TEXT NOT NULL, `tox_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_ConferenceMessage` (`conference_identifier`, `tox_peerpubkey`, `tox_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `id`) SELECT `conference_identifier`, `tox_peerpubkey`, `tox_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `id` FROM `ConferenceMessage`");
            run_multi_sql("DROP TABLE `ConferenceMessage`");
            run_multi_sql("ALTER TABLE `__temp_ConferenceMessage` RENAME TO `ConferenceMessage`");
            run_multi_sql("CREATE INDEX `index_conference_identifier_on_ConferenceMessage` ON `ConferenceMessage` (`conference_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_peerpubkey_on_ConferenceMessage` ON `ConferenceMessage` (`tox_peerpubkey`)");
            run_multi_sql("CREATE INDEX `index_tox_peername_on_ConferenceMessage` ON `ConferenceMessage` (`tox_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_ConferenceMessage` ON `ConferenceMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_ConferenceMessage` ON `ConferenceMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_ConferenceMessage` ON `ConferenceMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_ConferenceMessage` ON `ConferenceMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_ConferenceMessage` ON `ConferenceMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_ConferenceMessage` ON `ConferenceMessage` (`was_synced`)");
        }

        if (new_version == 10015) {
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
        }

        if (new_version == 10084) {
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_update` BOOLEAN DEFAULT false, `avatar_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `last_online_timestamp_real` INTEGER NOT NULL DEFAULT - 1, `added_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `added_timestamp`, `is_relay`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `added_timestamp`, `is_relay`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_timestamp_on_FriendList` ON `FriendList` (`avatar_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_real_on_FriendList` ON `FriendList` (`last_online_timestamp_real`)");
            run_multi_sql("CREATE INDEX `index_added_timestamp_on_FriendList` ON `FriendList` (`added_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
        }

        if (new_version == 10086) {
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
        }

        if (new_version == 10025) {
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_on_off`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `notification_silent`, `sort`, `last_online_timestamp`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_on_off`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `notification_silent`, `sort`, `last_online_timestamp`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
            run_multi_sql("CREATE TABLE `RelayListDB` (`TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `own_relay` BOOLEAN NOT NULL DEFAULT false, `last_online_timestamp` INTEGER NOT NULL DEFAULT -1, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_RelayListDB` ON `RelayListDB` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_RelayListDB` ON `RelayListDB` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_own_relay_on_RelayListDB` ON `RelayListDB` (`own_relay`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_RelayListDB` ON `RelayListDB` (`last_online_timestamp`)");
        }

        if (new_version == 10093) {
            run_multi_sql("CREATE TABLE `__temp_ConferenceMessage` (`message_id_tox` TEXT, `conference_identifier` TEXT NOT NULL DEFAULT - 1, `tox_peerpubkey` TEXT NOT NULL, `tox_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_ConferenceMessage` (`conference_identifier`, `tox_peerpubkey`, `tox_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `id`) SELECT `conference_identifier`, `tox_peerpubkey`, `tox_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `id` FROM `ConferenceMessage`");
            run_multi_sql("DROP TABLE `ConferenceMessage`");
            run_multi_sql("ALTER TABLE `__temp_ConferenceMessage` RENAME TO `ConferenceMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_ConferenceMessage` ON `ConferenceMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_conference_identifier_on_ConferenceMessage` ON `ConferenceMessage` (`conference_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_peerpubkey_on_ConferenceMessage` ON `ConferenceMessage` (`tox_peerpubkey`)");
            run_multi_sql("CREATE INDEX `index_tox_peername_on_ConferenceMessage` ON `ConferenceMessage` (`tox_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_ConferenceMessage` ON `ConferenceMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_ConferenceMessage` ON `ConferenceMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_ConferenceMessage` ON `ConferenceMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_ConferenceMessage` ON `ConferenceMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_ConferenceMessage` ON `ConferenceMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_ConferenceMessage` ON `ConferenceMessage` (`was_synced`)");
        }

        if (new_version == 10025) {
            run_multi_sql("CREATE TABLE `__temp_RelayListDB` (`TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `own_relay` BOOLEAN NOT NULL DEFAULT false, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `tox_public_key_string_of_owner` TEXT, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_RelayListDB` (`TOX_CONNECTION`, `TOX_CONNECTION_on_off`, `own_relay`, `last_online_timestamp`, `tox_public_key_string`) SELECT `TOX_CONNECTION`, `TOX_CONNECTION_on_off`, `own_relay`, `last_online_timestamp`, `tox_public_key_string` FROM `RelayListDB`");
            run_multi_sql("DROP TABLE `RelayListDB`");
            run_multi_sql("ALTER TABLE `__temp_RelayListDB` RENAME TO `RelayListDB`");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_RelayListDB` ON `RelayListDB` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_RelayListDB` ON `RelayListDB` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_own_relay_on_RelayListDB` ON `RelayListDB` (`own_relay`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_RelayListDB` ON `RelayListDB` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_tox_public_key_string_of_owner_on_RelayListDB` ON `RelayListDB` (`tox_public_key_string_of_owner`)");
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_on_off`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `notification_silent`, `sort`, `last_online_timestamp`, `is_relay`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_on_off`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `notification_silent`, `sort`, `last_online_timestamp`, `is_relay`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
        }

        if (new_version == 10026) {
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 2, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
        }

        if (new_version == 10096) {
            run_multi_sql("CREATE TABLE `__temp_Filetransfer` (`tox_public_key_string` TEXT NOT NULL, `direction` INTEGER NOT NULL, `file_number` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `state` INTEGER NOT NULL, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `path_name` TEXT NOT NULL, `file_name` TEXT NOT NULL, `fos_open` BOOLEAN NOT NULL DEFAULT false, `filesize` INTEGER NOT NULL DEFAULT - 1, `current_position` INTEGER NOT NULL DEFAULT 0, `message_id` INTEGER NOT NULL DEFAULT - 1, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Filetransfer` (`tox_public_key_string`, `direction`, `file_number`, `kind`, `state`, `ft_accepted`, `ft_outgoing_started`, `path_name`, `file_name`, `fos_open`, `filesize`, `current_position`, `message_id`, `id`) SELECT `tox_public_key_string`, `direction`, `file_number`, `kind`, `state`, `ft_accepted`, `ft_outgoing_started`, `path_name`, `file_name`, `fos_open`, `filesize`, `current_position`, `message_id`, `id` FROM `Filetransfer`");
            run_multi_sql("DROP TABLE `Filetransfer`");
            run_multi_sql("ALTER TABLE `__temp_Filetransfer` RENAME TO `Filetransfer`");
            run_multi_sql("CREATE INDEX `index_tox_public_key_string_on_Filetransfer` ON `Filetransfer` (`tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Filetransfer` ON `Filetransfer` (`direction`)");
            run_multi_sql("CREATE INDEX `index_file_number_on_Filetransfer` ON `Filetransfer` (`file_number`)");
            run_multi_sql("CREATE INDEX `index_kind_on_Filetransfer` ON `Filetransfer` (`kind`)");
            run_multi_sql("CREATE INDEX `index_state_on_Filetransfer` ON `Filetransfer` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Filetransfer` ON `Filetransfer` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Filetransfer` ON `Filetransfer` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_Filetransfer` ON `Filetransfer` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_Filetransfer` ON `Filetransfer` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_message_id_on_Filetransfer` ON `Filetransfer` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Filetransfer` ON `Filetransfer` (`storage_frame_work`)");
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 2, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
        }

        if (new_version == 10028) {
            run_multi_sql("CREATE TABLE `TRIFADatabaseGlobalsNew` (`value` TEXT NOT NULL, `key` TEXT PRIMARY KEY)");
            run_multi_sql("CREATE INDEX `index_value_on_TRIFADatabaseGlobalsNew` ON `TRIFADatabaseGlobalsNew` (`value`)");
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_update` BOOLEAN DEFAULT false, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `notification_silent`, `sort`, `last_online_timestamp`, `is_relay`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `notification_silent`, `sort`, `last_online_timestamp`, `is_relay`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
        }

        if (new_version == 10099) {
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 2, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
        }

        if (new_version == 10028) {
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_update` BOOLEAN DEFAULT false, `avatar_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `notification_silent`, `sort`, `last_online_timestamp`, `is_relay`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `notification_silent`, `sort`, `last_online_timestamp`, `is_relay`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_timestamp_on_FriendList` ON `FriendList` (`avatar_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
        }

        if (new_version == 10101) {
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 2, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `msg_at_relay` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
            run_multi_sql("CREATE INDEX `index_msg_at_relay_on_Message` ON `Message` (`msg_at_relay`)");
        }

        if (new_version == 10079) {
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_update` BOOLEAN DEFAULT false, `avatar_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `added_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `is_relay`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `is_relay`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_timestamp_on_FriendList` ON `FriendList` (`avatar_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_added_timestamp_on_FriendList` ON `FriendList` (`added_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
        }

        if (new_version == 10112) {
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_update` BOOLEAN DEFAULT false, `avatar_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `last_online_timestamp_real` INTEGER NOT NULL DEFAULT - 1, `added_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `push_url` TEXT, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_timestamp_on_FriendList` ON `FriendList` (`avatar_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_real_on_FriendList` ON `FriendList` (`last_online_timestamp_real`)");
            run_multi_sql("CREATE INDEX `index_added_timestamp_on_FriendList` ON `FriendList` (`added_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
            run_multi_sql("CREATE INDEX `index_push_url_on_FriendList` ON `FriendList` (`push_url`)");
        }

        if (new_version == 10124) {
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_update` BOOLEAN DEFAULT false, `avatar_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `last_online_timestamp_real` INTEGER NOT NULL DEFAULT - 1, `added_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `push_url` TEXT, `capabilities` INTEGER NOT NULL DEFAULT 0, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `push_url`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `push_url`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_timestamp_on_FriendList` ON `FriendList` (`avatar_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_real_on_FriendList` ON `FriendList` (`last_online_timestamp_real`)");
            run_multi_sql("CREATE INDEX `index_added_timestamp_on_FriendList` ON `FriendList` (`added_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
            run_multi_sql("CREATE INDEX `index_push_url_on_FriendList` ON `FriendList` (`push_url`)");
            run_multi_sql("CREATE INDEX `index_capabilities_on_FriendList` ON `FriendList` (`capabilities`)");
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 2, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `msg_at_relay` BOOLEAN NOT NULL DEFAULT false, `msg_idv3_hash` TEXT, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
            run_multi_sql("CREATE INDEX `index_msg_at_relay_on_Message` ON `Message` (`msg_at_relay`)");
            run_multi_sql("CREATE INDEX `index_msg_idv3_hash_on_Message` ON `Message` (`msg_idv3_hash`)");
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 2, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `msg_at_relay` BOOLEAN NOT NULL DEFAULT false, `msg_idv3_hash` TEXT, `sent_push` BOOLEAN, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
            run_multi_sql("CREATE INDEX `index_msg_at_relay_on_Message` ON `Message` (`msg_at_relay`)");
            run_multi_sql("CREATE INDEX `index_msg_idv3_hash_on_Message` ON `Message` (`msg_idv3_hash`)");
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 2, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `msg_at_relay` BOOLEAN NOT NULL DEFAULT false, `msg_idv3_hash` TEXT, `sent_push` INTEGER, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
            run_multi_sql("CREATE INDEX `index_msg_at_relay_on_Message` ON `Message` (`msg_at_relay`)");
            run_multi_sql("CREATE INDEX `index_msg_idv3_hash_on_Message` ON `Message` (`msg_idv3_hash`)");
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_update` BOOLEAN DEFAULT false, `avatar_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `last_online_timestamp_real` INTEGER NOT NULL DEFAULT - 1, `added_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `push_url` TEXT, `capabilities` INTEGER NOT NULL DEFAULT 0, `msgv3_capability` INTEGER NOT NULL DEFAULT 0, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `push_url`, `capabilities`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `push_url`, `capabilities`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_timestamp_on_FriendList` ON `FriendList` (`avatar_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_real_on_FriendList` ON `FriendList` (`last_online_timestamp_real`)");
            run_multi_sql("CREATE INDEX `index_added_timestamp_on_FriendList` ON `FriendList` (`added_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
            run_multi_sql("CREATE INDEX `index_push_url_on_FriendList` ON `FriendList` (`push_url`)");
            run_multi_sql("CREATE INDEX `index_capabilities_on_FriendList` ON `FriendList` (`capabilities`)");
            run_multi_sql("CREATE INDEX `index_msgv3_capability_on_FriendList` ON `FriendList` (`msgv3_capability`)");
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 5, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `msg_at_relay` BOOLEAN NOT NULL DEFAULT false, `msg_idv3_hash` TEXT, `sent_push` INTEGER, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
            run_multi_sql("CREATE INDEX `index_msg_at_relay_on_Message` ON `Message` (`msg_at_relay`)");
            run_multi_sql("CREATE INDEX `index_msg_idv3_hash_on_Message` ON `Message` (`msg_idv3_hash`)");
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 2, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `msg_at_relay` BOOLEAN NOT NULL DEFAULT false, `msg_idv3_hash` TEXT, `sent_push` INTEGER, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
            run_multi_sql("CREATE INDEX `index_msg_at_relay_on_Message` ON `Message` (`msg_at_relay`)");
            run_multi_sql("CREATE INDEX `index_msg_idv3_hash_on_Message` ON `Message` (`msg_idv3_hash`)");
        }

        if (new_version == 10127) {
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 4, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `msg_at_relay` BOOLEAN NOT NULL DEFAULT false, `msg_idv3_hash` TEXT, `sent_push` INTEGER, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
            run_multi_sql("CREATE INDEX `index_msg_at_relay_on_Message` ON `Message` (`msg_at_relay`)");
            run_multi_sql("CREATE INDEX `index_msg_idv3_hash_on_Message` ON `Message` (`msg_idv3_hash`)");
        }

        if (new_version == 10154) {
            run_multi_sql("CREATE TABLE `GroupDB` (`who_invited__tox_public_key_string` TEXT NOT NULL, `name` TEXT , `peer_count` INTEGER NOT NULL DEFAULT -1, `own_peer_number` INTEGER NOT NULL DEFAULT -1, `privacy_state` INTEGER NOT NULL DEFAULT 0, `tox_group_number` INTEGER NOT NULL DEFAULT -1, `notification_silent` BOOLEAN DEFAULT false, `group_identifier` TEXT PRIMARY KEY)");
            run_multi_sql("CREATE INDEX `index_who_invited__tox_public_key_string_on_GroupDB` ON `GroupDB` (`who_invited__tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_name_on_GroupDB` ON `GroupDB` (`name`)");
            run_multi_sql("CREATE INDEX `index_peer_count_on_GroupDB` ON `GroupDB` (`peer_count`)");
            run_multi_sql("CREATE INDEX `index_own_peer_number_on_GroupDB` ON `GroupDB` (`own_peer_number`)");
            run_multi_sql("CREATE INDEX `index_privacy_state_on_GroupDB` ON `GroupDB` (`privacy_state`)");
            run_multi_sql("CREATE INDEX `index_tox_group_number_on_GroupDB` ON `GroupDB` (`tox_group_number`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_GroupDB` ON `GroupDB` (`notification_silent`)");
            run_multi_sql("CREATE TABLE `GroupMessage` (`message_id_tox` TEXT , `group_identifier` TEXT NOT NULL DEFAULT -1, `tox_group_peer_pubkey` TEXT NOT NULL, `tox_group_peername` TEXT , `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER , `rcvd_timestamp` INTEGER , `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT , `was_synced` BOOLEAN , `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE TABLE `__temp_GroupDB` (`who_invited__tox_public_key_string` TEXT NOT NULL, `name` TEXT, `peer_count` INTEGER NOT NULL DEFAULT - 1, `own_peer_number` INTEGER NOT NULL DEFAULT - 1, `privacy_state` INTEGER NOT NULL DEFAULT 0, `tox_group_number` INTEGER NOT NULL DEFAULT - 1, `group_active` BOOLEAN NOT NULL DEFAULT false, `notification_silent` BOOLEAN DEFAULT false, `group_identifier` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_GroupDB` (`who_invited__tox_public_key_string`, `name`, `peer_count`, `own_peer_number`, `privacy_state`, `tox_group_number`, `notification_silent`, `group_identifier`) SELECT `who_invited__tox_public_key_string`, `name`, `peer_count`, `own_peer_number`, `privacy_state`, `tox_group_number`, `notification_silent`, `group_identifier` FROM `GroupDB`");
            run_multi_sql("DROP TABLE `GroupDB`");
            run_multi_sql("ALTER TABLE `__temp_GroupDB` RENAME TO `GroupDB`");
            run_multi_sql("CREATE INDEX `index_who_invited__tox_public_key_string_on_GroupDB` ON `GroupDB` (`who_invited__tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_name_on_GroupDB` ON `GroupDB` (`name`)");
            run_multi_sql("CREATE INDEX `index_peer_count_on_GroupDB` ON `GroupDB` (`peer_count`)");
            run_multi_sql("CREATE INDEX `index_own_peer_number_on_GroupDB` ON `GroupDB` (`own_peer_number`)");
            run_multi_sql("CREATE INDEX `index_privacy_state_on_GroupDB` ON `GroupDB` (`privacy_state`)");
            run_multi_sql("CREATE INDEX `index_tox_group_number_on_GroupDB` ON `GroupDB` (`tox_group_number`)");
            run_multi_sql("CREATE INDEX `index_group_active_on_GroupDB` ON `GroupDB` (`group_active`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_GroupDB` ON `GroupDB` (`notification_silent`)");
            run_multi_sql("CREATE TABLE `__temp_GroupDB` (`who_invited__tox_public_key_string` TEXT NOT NULL, `name` TEXT, `topic` TEXT, `peer_count` INTEGER NOT NULL DEFAULT - 1, `own_peer_number` INTEGER NOT NULL DEFAULT - 1, `privacy_state` INTEGER NOT NULL DEFAULT 0, `tox_group_number` INTEGER NOT NULL DEFAULT - 1, `group_active` BOOLEAN NOT NULL DEFAULT false, `notification_silent` BOOLEAN DEFAULT false, `group_identifier` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_GroupDB` (`who_invited__tox_public_key_string`, `name`, `peer_count`, `own_peer_number`, `privacy_state`, `tox_group_number`, `group_active`, `notification_silent`, `group_identifier`) SELECT `who_invited__tox_public_key_string`, `name`, `peer_count`, `own_peer_number`, `privacy_state`, `tox_group_number`, `group_active`, `notification_silent`, `group_identifier` FROM `GroupDB`");
            run_multi_sql("DROP TABLE `GroupDB`");
            run_multi_sql("ALTER TABLE `__temp_GroupDB` RENAME TO `GroupDB`");
            run_multi_sql("CREATE INDEX `index_who_invited__tox_public_key_string_on_GroupDB` ON `GroupDB` (`who_invited__tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_name_on_GroupDB` ON `GroupDB` (`name`)");
            run_multi_sql("CREATE INDEX `index_topic_on_GroupDB` ON `GroupDB` (`topic`)");
            run_multi_sql("CREATE INDEX `index_peer_count_on_GroupDB` ON `GroupDB` (`peer_count`)");
            run_multi_sql("CREATE INDEX `index_own_peer_number_on_GroupDB` ON `GroupDB` (`own_peer_number`)");
            run_multi_sql("CREATE INDEX `index_privacy_state_on_GroupDB` ON `GroupDB` (`privacy_state`)");
            run_multi_sql("CREATE INDEX `index_tox_group_number_on_GroupDB` ON `GroupDB` (`tox_group_number`)");
            run_multi_sql("CREATE INDEX `index_group_active_on_GroupDB` ON `GroupDB` (`group_active`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_GroupDB` ON `GroupDB` (`notification_silent`)");
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `msg_id_hash` TEXT, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_GroupMessage` ON `GroupMessage` (`msg_id_hash`)");
        }

        if (new_version == 10157) {
            run_multi_sql("CREATE TABLE `__temp_Filetransfer` (`tox_public_key_string` TEXT NOT NULL, `direction` INTEGER NOT NULL, `file_number` INTEGER NOT NULL, `kind` INTEGER NOT NULL, `state` INTEGER NOT NULL, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `path_name` TEXT NOT NULL, `file_name` TEXT NOT NULL, `fos_open` BOOLEAN NOT NULL DEFAULT false, `filesize` INTEGER NOT NULL DEFAULT - 1, `current_position` INTEGER NOT NULL DEFAULT 0, `message_id` INTEGER NOT NULL DEFAULT - 1, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `tox_file_id_hex` TEXT, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Filetransfer` (`tox_public_key_string`, `direction`, `file_number`, `kind`, `state`, `ft_accepted`, `ft_outgoing_started`, `path_name`, `file_name`, `fos_open`, `filesize`, `current_position`, `message_id`, `storage_frame_work`, `id`) SELECT `tox_public_key_string`, `direction`, `file_number`, `kind`, `state`, `ft_accepted`, `ft_outgoing_started`, `path_name`, `file_name`, `fos_open`, `filesize`, `current_position`, `message_id`, `storage_frame_work`, `id` FROM `Filetransfer`");
            run_multi_sql("DROP TABLE `Filetransfer`");
            run_multi_sql("ALTER TABLE `__temp_Filetransfer` RENAME TO `Filetransfer`");
            run_multi_sql("CREATE INDEX `index_tox_public_key_string_on_Filetransfer` ON `Filetransfer` (`tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Filetransfer` ON `Filetransfer` (`direction`)");
            run_multi_sql("CREATE INDEX `index_file_number_on_Filetransfer` ON `Filetransfer` (`file_number`)");
            run_multi_sql("CREATE INDEX `index_kind_on_Filetransfer` ON `Filetransfer` (`kind`)");
            run_multi_sql("CREATE INDEX `index_state_on_Filetransfer` ON `Filetransfer` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Filetransfer` ON `Filetransfer` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Filetransfer` ON `Filetransfer` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_Filetransfer` ON `Filetransfer` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_Filetransfer` ON `Filetransfer` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_message_id_on_Filetransfer` ON `Filetransfer` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Filetransfer` ON `Filetransfer` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_tox_file_id_hex_on_Filetransfer` ON `Filetransfer` (`tox_file_id_hex`)");
            run_multi_sql("CREATE TABLE `__temp_Message` (`message_id` INTEGER NOT NULL, `tox_friendpubkey` TEXT NOT NULL, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `state` INTEGER NOT NULL DEFAULT 1, `ft_accepted` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_started` BOOLEAN NOT NULL DEFAULT false, `filedb_id` INTEGER NOT NULL DEFAULT - 1, `filetransfer_id` INTEGER NOT NULL DEFAULT - 1, `sent_timestamp` INTEGER DEFAULT 0, `sent_timestamp_ms` INTEGER DEFAULT 0, `rcvd_timestamp` INTEGER DEFAULT 0, `rcvd_timestamp_ms` INTEGER DEFAULT 0, `read` BOOLEAN NOT NULL, `send_retries` INTEGER NOT NULL DEFAULT 0, `is_new` BOOLEAN NOT NULL, `text` TEXT, `filename_fullpath` TEXT, `msg_id_hash` TEXT, `raw_msgv2_bytes` TEXT, `msg_version` INTEGER NOT NULL DEFAULT 0, `resend_count` INTEGER NOT NULL DEFAULT 4, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `ft_outgoing_queued` BOOLEAN NOT NULL DEFAULT false, `msg_at_relay` BOOLEAN NOT NULL DEFAULT false, `msg_idv3_hash` TEXT, `sent_push` INTEGER, `filetransfer_kind` INTEGER DEFAULT 0, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_Message` (`message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id`) SELECT `message_id`, `tox_friendpubkey`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `state`, `ft_accepted`, `ft_outgoing_started`, `filedb_id`, `filetransfer_id`, `sent_timestamp`, `sent_timestamp_ms`, `rcvd_timestamp`, `rcvd_timestamp_ms`, `read`, `send_retries`, `is_new`, `text`, `filename_fullpath`, `msg_id_hash`, `raw_msgv2_bytes`, `msg_version`, `resend_count`, `storage_frame_work`, `ft_outgoing_queued`, `msg_at_relay`, `msg_idv3_hash`, `sent_push`, `id` FROM `Message`");
            run_multi_sql("DROP TABLE `Message`");
            run_multi_sql("ALTER TABLE `__temp_Message` RENAME TO `Message`");
            run_multi_sql("CREATE INDEX `index_message_id_on_Message` ON `Message` (`message_id`)");
            run_multi_sql("CREATE INDEX `index_tox_friendpubkey_on_Message` ON `Message` (`tox_friendpubkey`)");
            run_multi_sql("CREATE INDEX `index_direction_on_Message` ON `Message` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_Message` ON `Message` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_Message` ON `Message` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_state_on_Message` ON `Message` (`state`)");
            run_multi_sql("CREATE INDEX `index_ft_accepted_on_Message` ON `Message` (`ft_accepted`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_started_on_Message` ON `Message` (`ft_outgoing_started`)");
            run_multi_sql("CREATE INDEX `index_filedb_id_on_Message` ON `Message` (`filedb_id`)");
            run_multi_sql("CREATE INDEX `index_filetransfer_id_on_Message` ON `Message` (`filetransfer_id`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_Message` ON `Message` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_ms_on_Message` ON `Message` (`rcvd_timestamp_ms`)");
            run_multi_sql("CREATE INDEX `index_send_retries_on_Message` ON `Message` (`send_retries`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_Message` ON `Message` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_text_on_Message` ON `Message` (`text`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_Message` ON `Message` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_raw_msgv2_bytes_on_Message` ON `Message` (`raw_msgv2_bytes`)");
            run_multi_sql("CREATE INDEX `index_msg_version_on_Message` ON `Message` (`msg_version`)");
            run_multi_sql("CREATE INDEX `index_resend_count_on_Message` ON `Message` (`resend_count`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_Message` ON `Message` (`storage_frame_work`)");
            run_multi_sql("CREATE INDEX `index_ft_outgoing_queued_on_Message` ON `Message` (`ft_outgoing_queued`)");
            run_multi_sql("CREATE INDEX `index_msg_at_relay_on_Message` ON `Message` (`msg_at_relay`)");
            run_multi_sql("CREATE INDEX `index_msg_idv3_hash_on_Message` ON `Message` (`msg_idv3_hash`)");
        }

        if (new_version == 10172) {
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `msg_id_hash` TEXT, `sent_privately_to_tox_group_peer_pubkey` TEXT, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `msg_id_hash`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `msg_id_hash`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_GroupMessage` ON `GroupMessage` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_sent_privately_to_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`sent_privately_to_tox_group_peer_pubkey`)");
        }

        if (new_version == 10189) {
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_ftid_hex` TEXT, `avatar_update` BOOLEAN DEFAULT false, `avatar_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `last_online_timestamp_real` INTEGER NOT NULL DEFAULT - 1, `added_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `push_url` TEXT, `capabilities` INTEGER NOT NULL DEFAULT 0, `msgv3_capability` INTEGER NOT NULL DEFAULT 0, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `push_url`, `capabilities`, `msgv3_capability`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `push_url`, `capabilities`, `msgv3_capability`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_ftid_hex_on_FriendList` ON `FriendList` (`avatar_ftid_hex`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_timestamp_on_FriendList` ON `FriendList` (`avatar_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_real_on_FriendList` ON `FriendList` (`last_online_timestamp_real`)");
            run_multi_sql("CREATE INDEX `index_added_timestamp_on_FriendList` ON `FriendList` (`added_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
            run_multi_sql("CREATE INDEX `index_push_url_on_FriendList` ON `FriendList` (`push_url`)");
            run_multi_sql("CREATE INDEX `index_capabilities_on_FriendList` ON `FriendList` (`capabilities`)");
            run_multi_sql("CREATE INDEX `index_msgv3_capability_on_FriendList` ON `FriendList` (`msgv3_capability`)");
        }

        if (new_version == 10195) {
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `msg_id_hash` TEXT, `sent_privately_to_tox_group_peer_pubkey` TEXT, `path_name` TEXT, `file_name` TEXT, `filename_fullpath` TEXT, `filesize` INTEGER NOT NULL DEFAULT - 1, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_GroupMessage` ON `GroupMessage` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_sent_privately_to_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`sent_privately_to_tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_GroupMessage` ON `GroupMessage` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_GroupMessage` ON `GroupMessage` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_filesize_on_GroupMessage` ON `GroupMessage` (`filesize`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_GroupMessage` ON `GroupMessage` (`storage_frame_work`)");
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `TRIFA_SYNC_TYPE` INTEGER, `msg_id_hash` TEXT, `sent_privately_to_tox_group_peer_pubkey` TEXT, `path_name` TEXT, `file_name` TEXT, `filename_fullpath` TEXT, `filesize` INTEGER NOT NULL DEFAULT - 1, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_SYNC_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_SYNC_TYPE`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_GroupMessage` ON `GroupMessage` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_sent_privately_to_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`sent_privately_to_tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_GroupMessage` ON `GroupMessage` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_GroupMessage` ON `GroupMessage` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_filesize_on_GroupMessage` ON `GroupMessage` (`filesize`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_GroupMessage` ON `GroupMessage` (`storage_frame_work`)");
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `TRIFA_SYNC_TYPE` INTEGER, `sync_confirmations` INTEGER NOT NULL DEFAULT 0, `msg_id_hash` TEXT, `sent_privately_to_tox_group_peer_pubkey` TEXT, `path_name` TEXT, `file_name` TEXT, `filename_fullpath` TEXT, `filesize` INTEGER NOT NULL DEFAULT - 1, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `TRIFA_SYNC_TYPE`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `TRIFA_SYNC_TYPE`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_SYNC_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_SYNC_TYPE`)");
            run_multi_sql("CREATE INDEX `index_sync_confirmations_on_GroupMessage` ON `GroupMessage` (`sync_confirmations`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_GroupMessage` ON `GroupMessage` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_sent_privately_to_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`sent_privately_to_tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_GroupMessage` ON `GroupMessage` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_GroupMessage` ON `GroupMessage` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_filesize_on_GroupMessage` ON `GroupMessage` (`filesize`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_GroupMessage` ON `GroupMessage` (`storage_frame_work`)");
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `TRIFA_SYNC_TYPE` INTEGER, `sync_confirmations` INTEGER NOT NULL DEFAULT 0, `tox_group_peer_pubkey_syncer_01` TEXT, `tox_group_peer_pubkey_syncer_02` TEXT, `tox_group_peer_pubkey_syncer_03` TEXT, `msg_id_hash` TEXT, `sent_privately_to_tox_group_peer_pubkey` TEXT, `path_name` TEXT, `file_name` TEXT, `filename_fullpath` TEXT, `filesize` INTEGER NOT NULL DEFAULT - 1, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `TRIFA_SYNC_TYPE`, `sync_confirmations`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `TRIFA_SYNC_TYPE`, `sync_confirmations`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_SYNC_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_SYNC_TYPE`)");
            run_multi_sql("CREATE INDEX `index_sync_confirmations_on_GroupMessage` ON `GroupMessage` (`sync_confirmations`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_01_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_01`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_02_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_02`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_03_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_03`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_GroupMessage` ON `GroupMessage` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_sent_privately_to_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`sent_privately_to_tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_GroupMessage` ON `GroupMessage` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_GroupMessage` ON `GroupMessage` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_filesize_on_GroupMessage` ON `GroupMessage` (`filesize`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_GroupMessage` ON `GroupMessage` (`storage_frame_work`)");
        }

        if (new_version == 10203) {
            run_multi_sql("CREATE TABLE `GroupPeerDB` (`group_identifier` TEXT NOT NULL, `tox_group_peer_pubkey` TEXT NOT NULL, `peer_name` TEXT , `last_update_timestamp` INTEGER NOT NULL DEFAULT -1, `first_join_timestamp` INTEGER NOT NULL DEFAULT -1, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupPeerDB` ON `GroupPeerDB` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupPeerDB` ON `GroupPeerDB` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_peer_name_on_GroupPeerDB` ON `GroupPeerDB` (`peer_name`)");
            run_multi_sql("CREATE INDEX `index_last_update_timestamp_on_GroupPeerDB` ON `GroupPeerDB` (`last_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_first_join_timestamp_on_GroupPeerDB` ON `GroupPeerDB` (`first_join_timestamp`)");
            run_multi_sql("CREATE UNIQUE INDEX `index_group_identifier_tox_group_peer_pubkey_on_GroupPeerDB` ON `GroupPeerDB` (`group_identifier`, `tox_group_peer_pubkey`)");
        }

        if (new_version == 10206) {
            run_multi_sql("CREATE TABLE `__temp_GroupPeerDB` (`group_identifier` TEXT NOT NULL, `tox_group_peer_pubkey` TEXT NOT NULL, `peer_name` TEXT, `last_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `first_join_timestamp` INTEGER NOT NULL DEFAULT - 1, `Tox_Group_Role` INTEGER NOT NULL DEFAULT 2, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupPeerDB` (`group_identifier`, `tox_group_peer_pubkey`, `peer_name`, `last_update_timestamp`, `first_join_timestamp`, `id`) SELECT `group_identifier`, `tox_group_peer_pubkey`, `peer_name`, `last_update_timestamp`, `first_join_timestamp`, `id` FROM `GroupPeerDB`");
            run_multi_sql("DROP TABLE `GroupPeerDB`");
            run_multi_sql("ALTER TABLE `__temp_GroupPeerDB` RENAME TO `GroupPeerDB`");
            run_multi_sql("CREATE UNIQUE INDEX `index_group_identifier_tox_group_peer_pubkey_on_GroupPeerDB` ON `GroupPeerDB` (`group_identifier`, `tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupPeerDB` ON `GroupPeerDB` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupPeerDB` ON `GroupPeerDB` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_peer_name_on_GroupPeerDB` ON `GroupPeerDB` (`peer_name`)");
            run_multi_sql("CREATE INDEX `index_last_update_timestamp_on_GroupPeerDB` ON `GroupPeerDB` (`last_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_first_join_timestamp_on_GroupPeerDB` ON `GroupPeerDB` (`first_join_timestamp`)");
            run_multi_sql("CREATE INDEX `index_Tox_Group_Role_on_GroupPeerDB` ON `GroupPeerDB` (`Tox_Group_Role`)");
        }

        if (new_version == 10221) {
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `TRIFA_SYNC_TYPE` INTEGER, `sync_confirmations` INTEGER NOT NULL DEFAULT 0, `tox_group_peer_pubkey_syncer_01` TEXT, `tox_group_peer_pubkey_syncer_02` TEXT, `tox_group_peer_pubkey_syncer_03` TEXT, `tox_group_peer_pubkey_syncer_01_sent_timestamp` INTEGER, `tox_group_peer_pubkey_syncer_02_sent_timestamp` INTEGER, `tox_group_peer_pubkey_syncer_03_sent_timestamp` INTEGER, `msg_id_hash` TEXT, `sent_privately_to_tox_group_peer_pubkey` TEXT, `path_name` TEXT, `file_name` TEXT, `filename_fullpath` TEXT, `filesize` INTEGER NOT NULL DEFAULT - 1, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `TRIFA_SYNC_TYPE`, `sync_confirmations`, `tox_group_peer_pubkey_syncer_01`, `tox_group_peer_pubkey_syncer_02`, `tox_group_peer_pubkey_syncer_03`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `TRIFA_SYNC_TYPE`, `sync_confirmations`, `tox_group_peer_pubkey_syncer_01`, `tox_group_peer_pubkey_syncer_02`, `tox_group_peer_pubkey_syncer_03`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_SYNC_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_SYNC_TYPE`)");
            run_multi_sql("CREATE INDEX `index_sync_confirmations_on_GroupMessage` ON `GroupMessage` (`sync_confirmations`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_01_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_01`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_02_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_02`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_03_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_03`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_01_sent_timestamp_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_01_sent_timestamp`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_02_sent_timestamp_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_02_sent_timestamp`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_03_sent_timestamp_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_03_sent_timestamp`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_GroupMessage` ON `GroupMessage` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_sent_privately_to_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`sent_privately_to_tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_GroupMessage` ON `GroupMessage` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_GroupMessage` ON `GroupMessage` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_filesize_on_GroupMessage` ON `GroupMessage` (`filesize`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_GroupMessage` ON `GroupMessage` (`storage_frame_work`)");
        }

        if (new_version == 10226) {
            run_multi_sql("CREATE TABLE `__temp_FriendList` (`name` TEXT, `alias_name` TEXT, `status_message` TEXT, `TOX_CONNECTION` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_real` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off` INTEGER NOT NULL DEFAULT 0, `TOX_CONNECTION_on_off_real` INTEGER NOT NULL DEFAULT 0, `TOX_USER_STATUS` INTEGER NOT NULL DEFAULT 0, `avatar_pathname` TEXT, `avatar_filename` TEXT, `avatar_ftid_hex` TEXT, `avatar_update` BOOLEAN DEFAULT false, `avatar_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `notification_silent` BOOLEAN DEFAULT false, `sort` INTEGER NOT NULL DEFAULT 0, `last_online_timestamp` INTEGER NOT NULL DEFAULT - 1, `last_online_timestamp_real` INTEGER NOT NULL DEFAULT - 1, `added_timestamp` INTEGER NOT NULL DEFAULT - 1, `is_relay` BOOLEAN DEFAULT false, `push_url` TEXT, `ip_addr_str` TEXT, `capabilities` INTEGER NOT NULL DEFAULT 0, `msgv3_capability` INTEGER NOT NULL DEFAULT 0, `tox_public_key_string` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_FriendList` (`name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_ftid_hex`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `push_url`, `capabilities`, `msgv3_capability`, `tox_public_key_string`) SELECT `name`, `alias_name`, `status_message`, `TOX_CONNECTION`, `TOX_CONNECTION_real`, `TOX_CONNECTION_on_off`, `TOX_CONNECTION_on_off_real`, `TOX_USER_STATUS`, `avatar_pathname`, `avatar_filename`, `avatar_ftid_hex`, `avatar_update`, `avatar_update_timestamp`, `notification_silent`, `sort`, `last_online_timestamp`, `last_online_timestamp_real`, `added_timestamp`, `is_relay`, `push_url`, `capabilities`, `msgv3_capability`, `tox_public_key_string` FROM `FriendList`");
            run_multi_sql("DROP TABLE `FriendList`");
            run_multi_sql("ALTER TABLE `__temp_FriendList` RENAME TO `FriendList`");
            run_multi_sql("CREATE INDEX `index_alias_name_on_FriendList` ON `FriendList` (`alias_name`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_FriendList` ON `FriendList` (`TOX_CONNECTION`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off`)");
            run_multi_sql("CREATE INDEX `index_TOX_CONNECTION_on_off_real_on_FriendList` ON `FriendList` (`TOX_CONNECTION_on_off_real`)");
            run_multi_sql("CREATE INDEX `index_TOX_USER_STATUS_on_FriendList` ON `FriendList` (`TOX_USER_STATUS`)");
            run_multi_sql("CREATE INDEX `index_avatar_ftid_hex_on_FriendList` ON `FriendList` (`avatar_ftid_hex`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_on_FriendList` ON `FriendList` (`avatar_update`)");
            run_multi_sql("CREATE INDEX `index_avatar_update_timestamp_on_FriendList` ON `FriendList` (`avatar_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_FriendList` ON `FriendList` (`notification_silent`)");
            run_multi_sql("CREATE INDEX `index_sort_on_FriendList` ON `FriendList` (`sort`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_on_FriendList` ON `FriendList` (`last_online_timestamp`)");
            run_multi_sql("CREATE INDEX `index_last_online_timestamp_real_on_FriendList` ON `FriendList` (`last_online_timestamp_real`)");
            run_multi_sql("CREATE INDEX `index_added_timestamp_on_FriendList` ON `FriendList` (`added_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_relay_on_FriendList` ON `FriendList` (`is_relay`)");
            run_multi_sql("CREATE INDEX `index_push_url_on_FriendList` ON `FriendList` (`push_url`)");
            run_multi_sql("CREATE INDEX `index_ip_addr_str_on_FriendList` ON `FriendList` (`ip_addr_str`)");
            run_multi_sql("CREATE INDEX `index_capabilities_on_FriendList` ON `FriendList` (`capabilities`)");
            run_multi_sql("CREATE INDEX `index_msgv3_capability_on_FriendList` ON `FriendList` (`msgv3_capability`)");
        }

        if (new_version == 10240) {
            run_multi_sql("CREATE TABLE `__temp_GroupDB` (`who_invited__tox_public_key_string` TEXT NOT NULL, `name` TEXT, `topic` TEXT, `peer_count` INTEGER NOT NULL DEFAULT - 1, `own_peer_number` INTEGER NOT NULL DEFAULT - 1, `privacy_state` INTEGER NOT NULL DEFAULT 0, `tox_group_number` INTEGER NOT NULL DEFAULT - 1, `group_active` BOOLEAN NOT NULL DEFAULT false, `group_we_left` BOOLEAN NOT NULL DEFAULT false, `notification_silent` BOOLEAN DEFAULT false, `group_identifier` TEXT PRIMARY KEY)");
            run_multi_sql("INSERT INTO `__temp_GroupDB` (`who_invited__tox_public_key_string`, `name`, `topic`, `peer_count`, `own_peer_number`, `privacy_state`, `tox_group_number`, `group_active`, `notification_silent`, `group_identifier`) SELECT `who_invited__tox_public_key_string`, `name`, `topic`, `peer_count`, `own_peer_number`, `privacy_state`, `tox_group_number`, `group_active`, `notification_silent`, `group_identifier` FROM `GroupDB`");
            run_multi_sql("DROP TABLE `GroupDB`");
            run_multi_sql("ALTER TABLE `__temp_GroupDB` RENAME TO `GroupDB`");
            run_multi_sql("CREATE INDEX `index_who_invited__tox_public_key_string_on_GroupDB` ON `GroupDB` (`who_invited__tox_public_key_string`)");
            run_multi_sql("CREATE INDEX `index_name_on_GroupDB` ON `GroupDB` (`name`)");
            run_multi_sql("CREATE INDEX `index_topic_on_GroupDB` ON `GroupDB` (`topic`)");
            run_multi_sql("CREATE INDEX `index_peer_count_on_GroupDB` ON `GroupDB` (`peer_count`)");
            run_multi_sql("CREATE INDEX `index_own_peer_number_on_GroupDB` ON `GroupDB` (`own_peer_number`)");
            run_multi_sql("CREATE INDEX `index_privacy_state_on_GroupDB` ON `GroupDB` (`privacy_state`)");
            run_multi_sql("CREATE INDEX `index_tox_group_number_on_GroupDB` ON `GroupDB` (`tox_group_number`)");
            run_multi_sql("CREATE INDEX `index_group_active_on_GroupDB` ON `GroupDB` (`group_active`)");
            run_multi_sql("CREATE INDEX `index_group_we_left_on_GroupDB` ON `GroupDB` (`group_we_left`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_GroupDB` ON `GroupDB` (`notification_silent`)");
        }

        if (new_version == 10241) {
            run_multi_sql("CREATE TABLE `__temp_GroupMessage` (`message_id_tox` TEXT, `group_identifier` TEXT NOT NULL DEFAULT - 1, `tox_group_peer_pubkey` TEXT NOT NULL, `tox_group_peer_role` INTEGER NOT NULL DEFAULT - 1, `private_message` INTEGER, `tox_group_peername` TEXT, `direction` INTEGER NOT NULL, `TOX_MESSAGE_TYPE` INTEGER NOT NULL, `TRIFA_MESSAGE_TYPE` INTEGER NOT NULL DEFAULT 0, `sent_timestamp` INTEGER, `rcvd_timestamp` INTEGER, `read` BOOLEAN NOT NULL, `is_new` BOOLEAN NOT NULL, `text` TEXT, `was_synced` BOOLEAN, `TRIFA_SYNC_TYPE` INTEGER, `sync_confirmations` INTEGER NOT NULL DEFAULT 0, `tox_group_peer_pubkey_syncer_01` TEXT, `tox_group_peer_pubkey_syncer_02` TEXT, `tox_group_peer_pubkey_syncer_03` TEXT, `tox_group_peer_pubkey_syncer_01_sent_timestamp` INTEGER, `tox_group_peer_pubkey_syncer_02_sent_timestamp` INTEGER, `tox_group_peer_pubkey_syncer_03_sent_timestamp` INTEGER, `msg_id_hash` TEXT, `sent_privately_to_tox_group_peer_pubkey` TEXT, `path_name` TEXT, `file_name` TEXT, `filename_fullpath` TEXT, `filesize` INTEGER NOT NULL DEFAULT - 1, `storage_frame_work` BOOLEAN NOT NULL DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupMessage` (`message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `TRIFA_SYNC_TYPE`, `sync_confirmations`, `tox_group_peer_pubkey_syncer_01`, `tox_group_peer_pubkey_syncer_02`, `tox_group_peer_pubkey_syncer_03`, `tox_group_peer_pubkey_syncer_01_sent_timestamp`, `tox_group_peer_pubkey_syncer_02_sent_timestamp`, `tox_group_peer_pubkey_syncer_03_sent_timestamp`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id`) SELECT `message_id_tox`, `group_identifier`, `tox_group_peer_pubkey`, `private_message`, `tox_group_peername`, `direction`, `TOX_MESSAGE_TYPE`, `TRIFA_MESSAGE_TYPE`, `sent_timestamp`, `rcvd_timestamp`, `read`, `is_new`, `text`, `was_synced`, `TRIFA_SYNC_TYPE`, `sync_confirmations`, `tox_group_peer_pubkey_syncer_01`, `tox_group_peer_pubkey_syncer_02`, `tox_group_peer_pubkey_syncer_03`, `tox_group_peer_pubkey_syncer_01_sent_timestamp`, `tox_group_peer_pubkey_syncer_02_sent_timestamp`, `tox_group_peer_pubkey_syncer_03_sent_timestamp`, `msg_id_hash`, `sent_privately_to_tox_group_peer_pubkey`, `path_name`, `file_name`, `filename_fullpath`, `filesize`, `storage_frame_work`, `id` FROM `GroupMessage`");
            run_multi_sql("DROP TABLE `GroupMessage`");
            run_multi_sql("ALTER TABLE `__temp_GroupMessage` RENAME TO `GroupMessage`");
            run_multi_sql("CREATE INDEX `index_message_id_tox_on_GroupMessage` ON `GroupMessage` (`message_id_tox`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupMessage` ON `GroupMessage` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_role_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_role`)");
            run_multi_sql("CREATE INDEX `index_private_message_on_GroupMessage` ON `GroupMessage` (`private_message`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peername_on_GroupMessage` ON `GroupMessage` (`tox_group_peername`)");
            run_multi_sql("CREATE INDEX `index_direction_on_GroupMessage` ON `GroupMessage` (`direction`)");
            run_multi_sql("CREATE INDEX `index_TOX_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TOX_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_MESSAGE_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_MESSAGE_TYPE`)");
            run_multi_sql("CREATE INDEX `index_rcvd_timestamp_on_GroupMessage` ON `GroupMessage` (`rcvd_timestamp`)");
            run_multi_sql("CREATE INDEX `index_is_new_on_GroupMessage` ON `GroupMessage` (`is_new`)");
            run_multi_sql("CREATE INDEX `index_was_synced_on_GroupMessage` ON `GroupMessage` (`was_synced`)");
            run_multi_sql("CREATE INDEX `index_TRIFA_SYNC_TYPE_on_GroupMessage` ON `GroupMessage` (`TRIFA_SYNC_TYPE`)");
            run_multi_sql("CREATE INDEX `index_sync_confirmations_on_GroupMessage` ON `GroupMessage` (`sync_confirmations`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_01_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_01`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_02_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_02`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_03_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_03`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_01_sent_timestamp_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_01_sent_timestamp`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_02_sent_timestamp_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_02_sent_timestamp`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_syncer_03_sent_timestamp_on_GroupMessage` ON `GroupMessage` (`tox_group_peer_pubkey_syncer_03_sent_timestamp`)");
            run_multi_sql("CREATE INDEX `index_msg_id_hash_on_GroupMessage` ON `GroupMessage` (`msg_id_hash`)");
            run_multi_sql("CREATE INDEX `index_sent_privately_to_tox_group_peer_pubkey_on_GroupMessage` ON `GroupMessage` (`sent_privately_to_tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_path_name_on_GroupMessage` ON `GroupMessage` (`path_name`)");
            run_multi_sql("CREATE INDEX `index_file_name_on_GroupMessage` ON `GroupMessage` (`file_name`)");
            run_multi_sql("CREATE INDEX `index_filesize_on_GroupMessage` ON `GroupMessage` (`filesize`)");
            run_multi_sql("CREATE INDEX `index_storage_frame_work_on_GroupMessage` ON `GroupMessage` (`storage_frame_work`)");
            run_multi_sql("CREATE TABLE `__temp_GroupPeerDB` (`group_identifier` TEXT NOT NULL, `tox_group_peer_pubkey` TEXT NOT NULL, `peer_name` TEXT, `last_update_timestamp` INTEGER NOT NULL DEFAULT - 1, `first_join_timestamp` INTEGER NOT NULL DEFAULT - 1, `Tox_Group_Role` INTEGER NOT NULL DEFAULT 2, `notification_silent` BOOLEAN DEFAULT false, `id` INTEGER PRIMARY KEY AUTOINCREMENT)");
            run_multi_sql("INSERT INTO `__temp_GroupPeerDB` (`group_identifier`, `tox_group_peer_pubkey`, `peer_name`, `last_update_timestamp`, `first_join_timestamp`, `Tox_Group_Role`, `id`) SELECT `group_identifier`, `tox_group_peer_pubkey`, `peer_name`, `last_update_timestamp`, `first_join_timestamp`, `Tox_Group_Role`, `id` FROM `GroupPeerDB`");
            run_multi_sql("DROP TABLE `GroupPeerDB`");
            run_multi_sql("ALTER TABLE `__temp_GroupPeerDB` RENAME TO `GroupPeerDB`");
            run_multi_sql("CREATE UNIQUE INDEX `index_group_identifier_tox_group_peer_pubkey_on_GroupPeerDB` ON `GroupPeerDB` (`group_identifier`, `tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_group_identifier_on_GroupPeerDB` ON `GroupPeerDB` (`group_identifier`)");
            run_multi_sql("CREATE INDEX `index_tox_group_peer_pubkey_on_GroupPeerDB` ON `GroupPeerDB` (`tox_group_peer_pubkey`)");
            run_multi_sql("CREATE INDEX `index_peer_name_on_GroupPeerDB` ON `GroupPeerDB` (`peer_name`)");
            run_multi_sql("CREATE INDEX `index_last_update_timestamp_on_GroupPeerDB` ON `GroupPeerDB` (`last_update_timestamp`)");
            run_multi_sql("CREATE INDEX `index_first_join_timestamp_on_GroupPeerDB` ON `GroupPeerDB` (`first_join_timestamp`)");
            run_multi_sql("CREATE INDEX `index_Tox_Group_Role_on_GroupPeerDB` ON `GroupPeerDB` (`Tox_Group_Role`)");
            run_multi_sql("CREATE INDEX `index_notification_silent_on_GroupPeerDB` ON `GroupPeerDB` (`notification_silent`)");
        }

        if (new_version == 10242) {
            run_multi_sql("ALTER TABLE Filetransfer ADD COLUMN transfer_start_ts INTEGER DEFAULT 0");
            run_multi_sql("CREATE INDEX `index_transfer_start_ts_on_Filetransfer` ON `Filetransfer` (`transfer_start_ts`)");
        }

        // KHANDAQ (#9 message edit): edited flag + timestamp + pending-send marker on both tables.
        if (new_version == 10243) {
            run_multi_sql("ALTER TABLE Message ADD COLUMN edited BOOLEAN NOT NULL DEFAULT false");
            run_multi_sql("ALTER TABLE Message ADD COLUMN edited_timestamp INTEGER DEFAULT 0");
            run_multi_sql("ALTER TABLE Message ADD COLUMN edit_pending BOOLEAN NOT NULL DEFAULT false");
            run_multi_sql("CREATE INDEX `index_edited_on_Message` ON `Message` (`edited`)");
            run_multi_sql("CREATE INDEX `index_edit_pending_on_Message` ON `Message` (`edit_pending`)");
            run_multi_sql("ALTER TABLE GroupMessage ADD COLUMN edited BOOLEAN NOT NULL DEFAULT false");
            run_multi_sql("ALTER TABLE GroupMessage ADD COLUMN edited_timestamp INTEGER DEFAULT 0");
            run_multi_sql("ALTER TABLE GroupMessage ADD COLUMN edit_pending BOOLEAN NOT NULL DEFAULT false");
            run_multi_sql("CREATE INDEX `index_edited_on_GroupMessage` ON `GroupMessage` (`edited`)");
            run_multi_sql("CREATE INDEX `index_edit_pending_on_GroupMessage` ON `GroupMessage` (`edit_pending`)");
        }

        // KHANDAQ (#192 reactions): serialized reactions JSON + "not yet delivered" flag, both tables.
        if (new_version == 10244) {
            run_multi_sql("ALTER TABLE Message ADD COLUMN reactions TEXT");
            run_multi_sql("ALTER TABLE Message ADD COLUMN reactions_pending BOOLEAN NOT NULL DEFAULT false");
            run_multi_sql("CREATE INDEX `index_reactions_pending_on_Message` ON `Message` (`reactions_pending`)");
            run_multi_sql("ALTER TABLE GroupMessage ADD COLUMN reactions TEXT");
            run_multi_sql("ALTER TABLE GroupMessage ADD COLUMN reactions_pending BOOLEAN NOT NULL DEFAULT false");
            run_multi_sql("CREATE INDEX `index_reactions_pending_on_GroupMessage` ON `GroupMessage` (`reactions_pending`)");
        }

        // KHANDAQ (#192 reactions): durable anchor for 1:1 file/media/voice reactions (symmetric tox
        // file_id hex), stamped at send/receive so it survives Filetransfer-row deletion on completion.
        if (new_version == 10245) {
            run_multi_sql("ALTER TABLE Message ADD COLUMN ft_id_anchor_hex TEXT");
            run_multi_sql("CREATE INDEX `index_ft_id_anchor_hex_on_Message` ON `Message` (`ft_id_anchor_hex`)");
        }
    }

    private OrmaDatabase OrmaDatabase_wrapper(String dbs_path, String pref__db_secrect_key, boolean pref__db_wal_mode)
    {
        set_schema_upgrade_callback(new OrmaDatabase.schema_upgrade_callback()
        {
            @Override
            public void upgrade(int old_version, int new_version)
            {
                HelperGeneric.logI(TAG, "trying to upgrade schema from " + old_version + " to " + new_version);
                upgrade_db_schema_do(old_version, new_version);
            }
        });

        OrmaDatabase orma = new OrmaDatabase(dbs_path, pref__db_secrect_key, pref__db_wal_mode);
        try
        {
            OrmaDatabase.init(ORMA_CURRENT_DB_SCHEMA_VERSION);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        return orma;
    }

    static void manually_log_out()
    {
        // KHANDAQ (logout crash fix): arm manually_logged_out BEFORE global_stop_tox() so that any
        // Fragment onResume / UI callback racing the async tox teardown sees it and skips native
        // tox_self_get_*() calls on the half-killed tox.
        TrifaToxService.manually_logged_out = true;
        global_stop_tox();
        // KHANDAQ: do NOT reset TOX_SERVICE_STARTED here. Doing so made MainActivity.onCreate's
        // "if (!TOX_SERVICE_STARTED) tox_thread_start()" guard fire during logout and start a SECOND
        // iterate thread on the tox that stop_tox_fg is simultaneously killing → tox_iterate() on a
        // freed/NULL tox → native SIGSEGV/SIGABRT crash (reproduced on logout). The Service stays
        // "started" across logout (known-good); a proper race-free same-process re-login is a separate
        // fix. Crash-avoidance takes priority.
        try
        {
            final Thread t = new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        Thread.sleep(50);
                        HelperGeneric.logI(TAG, "connection_status: manual logout");
                        tox_notification_change_wrapper(CONNECTION_STATUS_MANUAL_LOGOUT, "");
                    }
                    catch (Exception e)
                    {
                    }
                }
            };
            t.start();
        }
        catch(Exception e)
        {
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu)
    {
        if (menu != null)
        {
            final boolean contactsOrChatsTab = (currentMainTab == R.id.bottom_nav_contacts)
                    || (currentMainTab == R.id.bottom_nav_chats);
            final MenuItem addFriendItem = menu.findItem(R.id.item_addfriend);
            final MenuItem groupMenuItem = menu.findItem(R.id.item_group_menu);

            if (addFriendItem != null)
            {
                addFriendItem.setVisible(contactsOrChatsTab);
                if (contactsOrChatsTab)
                {
                    updateAddFriendMenuIcon(addFriendItem);
                }
            }
            if (groupMenuItem != null)
            {
                // KHANDAQ: the group menu (create private/public group) stays. Only "join public group
                // by chat-id" is hidden (android:visible="false" on item_join_group_public in
                // res/menu/menu_main.xml) pending the NGC cold-chat-id discovery fix — cold join by id
                // does not connect yet. Group creation + friend-invite flow are unaffected.
                groupMenuItem.setVisible(currentMainTab == R.id.bottom_nav_chats);
                if (currentMainTab == R.id.bottom_nav_chats)
                {
                    updateGroupMenuIcon(groupMenuItem);
                }
            }
            tint_main_options_menu_icons(menu);
        }

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return super.onCreateOptionsMenu(menu);
    }

    private void apply_main_header_icon_tint(final Toolbar toolbar)
    {
        final int iconTint = ContextCompat.getColor(this, R.color.tg_chat_title);
        if (toolbar != null)
        {
            final Drawable nav = toolbar.getNavigationIcon();
            if (nav != null)
            {
                final Drawable tinted = DrawableCompat.wrap(nav.mutate());
                DrawableCompat.setTint(tinted, iconTint);
                toolbar.setNavigationIcon(tinted);
            }
        }
    }

    private void tint_main_options_menu_icons(final Menu menu)
    {
        if (menu == null)
        {
            return;
        }
        final int iconTint = ContextCompat.getColor(this, R.color.tg_chat_title);
        for (int i = 0; i < menu.size(); i++)
        {
            final MenuItem item = menu.getItem(i);
            final Drawable icon = item.getIcon();
            if (icon != null)
            {
                final Drawable tinted = DrawableCompat.wrap(icon.mutate());
                DrawableCompat.setTint(tinted, iconTint);
                item.setIcon(tinted);
            }
        }
    }

    void remove_all_progressDialogs()
    {
        try
        {
            fl_loading_progressbar.setVisibility(View.GONE);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item)
    {
        switch (item.getItemId())
        {
            case R.id.item_addfriend:
                final Intent intent = new Intent(this, AddFriendActivity.class);
                startActivityForResult(intent, AddFriendActivity_ID);
                break;
            case R.id.item_create_group_private:
                show_create_private_group(null);
                break;
            case R.id.item_create_group_public:
                final Intent intent3 = new Intent(this, AddPublicGroupActivity.class);
                startActivityForResult(intent3, AddPublicGroupActivity_ID);
                break;
            case R.id.item_join_group_public:
                final Intent intent4 = new Intent(this, JoinPublicGroupActivity.class);
                startActivityForResult(intent4, JoinPublicGroupActivity_ID);
                break;
        }
        return true;
    }

    public void vfs_listFilesAndFilesSubDirectories(String directoryName, int depth, String parent)
    {
        if (VFS_ENCRYPT)
        {
            info.guardianproject.iocipher.File directory1 = new info.guardianproject.iocipher.File(directoryName);
            info.guardianproject.iocipher.File[] fList1 = directory1.listFiles();

            for (info.guardianproject.iocipher.File file : fList1)
            {
                if (file.isFile())
                {
                    // final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    // final String human_datetime = df.format(new Date(file.lastModified()));
                    HelperGeneric.logI(TAG, "VFS:f:" + parent + "/" + file.getName() + " bytes=" + file.length());
                }
                else if (file.isDirectory())
                {
                    HelperGeneric.logI(TAG, "VFS:d:" + parent + "/" + file.getName() + "/");
                    vfs_listFilesAndFilesSubDirectories(file.getAbsolutePath(), depth + 1,
                                                        parent + "/" + file.getName());
                }
            }
        }
        else
        {
            java.io.File directory1 = new java.io.File(directoryName);
            java.io.File[] fList1 = directory1.listFiles();

            for (File file : fList1)
            {
                if (file.isFile())
                {
                    // final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    // final String human_datetime = df.format(new Date(file.lastModified()));
                    HelperGeneric.logI(TAG, "VFS:f:" + parent + "/" + file.getName() + " bytes=" + file.length());
                }
                else if (file.isDirectory())
                {
                    HelperGeneric.logI(TAG, "VFS:d:" + parent + "/" + file.getName() + "/");
                    vfs_listFilesAndFilesSubDirectories(file.getAbsolutePath(), depth + 1,
                                                        parent + "/" + file.getName());
                }
            }
        }
    }


    // ------- for runtime permissions -------
    // ------- for runtime permissions -------
    // ------- for runtime permissions -------
    @NeedsPermission({Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE})
    void dummyForPermissions001()
    {
    }

    @SuppressLint("NeedOnRequestPermissionsResult")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // NOTE: delegate the permission handling to generated method
        MainActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults);
    }
    // ------- for runtime permissions -------
    // ------- for runtime permissions -------
    // ------- for runtime permissions -------

    // this is NOT a crpytographically secure random string generator!!
    // it should only be used to generate status messages or tox user strings to be sort of unique
    static String getRandomString(final int sizeOfRandomString)
    {
        final Random random = new Random();
        final StringBuilder sb = new StringBuilder(sizeOfRandomString);

        for (int i = 0; i < sizeOfRandomString; ++i)
        {
            sb.append(ALLOWED_CHARACTERS.charAt(random.nextInt(ALLOWED_CHARACTERS.length())));
        }

        return sb.toString();
    }

    void tox_thread_start()
    {
        try
        {
            Thread t = new Thread()
            {
                @Override
                public void run()
                {
                    long counter = 0;

                    while (tox_service_fg == null)
                    {
                        counter++;

                        if (counter > 100)
                        {
                            break;
                        }

                        try
                        {
                            Thread.sleep(100);
                        }
                        catch (Exception e)
                        {
                            // e.printStackTrace();
                        }
                    }

                    try
                    {
                        // [TODO: move this also to Service.]
                        // HINT: seems to work pretty ok now.
                        if (!is_tox_started)
                        {
                            int PREF__orbot_enabled_to_int = 0;

                            if (PREF__orbot_enabled)
                            {
                                PREF__orbot_enabled_to_int = 1;
                                // need to wait for Orbot to be active ...
                                // max 20 seconds!
                                int max_sleep_iterations = 40;
                                int sleep_iteration = 0;

                                while (!OrbotHelper.isOrbotRunning(context_s))
                                {
                                    // sleep 0.5 seconds
                                    sleep_iteration++;

                                    try
                                    {
                                        Thread.sleep(500);
                                    }
                                    catch (Exception e)
                                    {
                                        e.printStackTrace();
                                    }

                                    if (sleep_iteration > max_sleep_iterations)
                                    {
                                        // giving up
                                        break;
                                    }
                                }

                                try
                                {
                                    Thread.sleep(1000);
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }

                                // remove "waiting for orbot view"
                                HelperGeneric.logI(TAG, "waiting_for_orbot_info:+F99");
                                orbot_is_really_running = true;
                                HelperGeneric.waiting_for_orbot_info(false);
                            }

                            int PREF__local_discovery_enabled_to_int = 0;

                            if (PREF__local_discovery_enabled)
                            {
                                PREF__local_discovery_enabled_to_int = 1;
                            }

                            int PREF__ipv6_enabled_to_int = 0;
                            if (PREF__ipv6_enabled)
                            {
                                PREF__ipv6_enabled_to_int = 1;
                            }

                            int PREF__force_udp_only_to_int = 0;
                            if (PREF__force_udp_only)
                            {
                                PREF__force_udp_only_to_int = 1;
                            }

                            init_tox_with_heal(PREF__udp_enabled, PREF__local_discovery_enabled_to_int,
                                 PREF__orbot_enabled_to_int, ORBOT_PROXY_HOST, ORBOT_PROXY_PORT,
                                 PREF__ipv6_enabled_to_int, PREF__force_udp_only_to_int, PREF__ngc_video_bitrate,
                                 PREF__ngc_video_max_quantizer,
                                 PREF__ngc_audio_bitrate, PREF__ngc_audio_samplerate, PREF__ngc_audio_channels);
                        }

                        HelperGeneric.logI(TAG, "set_all_conferences_inactive:002");
                        HelperConference.set_all_conferences_inactive();
                        tox_service_fg.tox_thread_start_fg();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            };
            t.start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "tox_thread_start:EE:" + e.getMessage());
        }
    }

    // KHANDAQ (#195): load the Tox profile WITHOUT ever silently losing the identity.
    //  * savedata.tox is encrypted with sha256(current DB key). After a password change / key
    //    restore it can be left on sha256(an OLDER key); decryption then fails, native
    //    create_tox() returns NULL, the old path parked savedata.tox -> .broken and the next
    //    cold start minted a BRAND-NEW keypair (ALL friends lost — the "IDs change on update"
    //    report). The DB key already probes several candidates; savedata had no such fallback.
    //  * Retry native init() with every DB-key candidate while savedata.tox is still present and,
    //    on success, re-encrypt it under the CURRENT key so it stays in sync from then on.
    //  * If the live profile is gone but a parked ".broken" exists, restore it once (guarded by a
    //    pref so a truly unloadable file can't loop) so a fresh ID isn't minted over a
    //    recoverable identity.
    private boolean init_tox_with_heal(int udp, int localdisc, int orbot, String orbot_host,
                                       long orbot_port, int ipv6, int force_udp, int ngc_video_bitrate,
                                       int ngc_video_max_quantizer, int ngc_audio_bitrate,
                                       int ngc_audio_samplerate, int ngc_audio_channels)
    {
        final java.io.File sd = new java.io.File(app_files_directory, "savedata.tox");
        final java.io.File broken = new java.io.File(app_files_directory, "savedata.tox.broken");
        android.content.SharedPreferences prefs = null;
        try { prefs = PreferenceManager.getDefaultSharedPreferences(this); } catch (Exception ignored) {}

        boolean sd_existed = sd.exists();
        if (!sd_existed && broken.exists()
                && (prefs == null || !prefs.getBoolean("kq_broken_restore_tried", false)))
        {
            try
            {
                if (prefs != null) { prefs.edit().putBoolean("kq_broken_restore_tried", true).apply(); }
                if (broken.renameTo(sd)) { sd_existed = true; }
                HelperGeneric.logI(TAG, "init_tox_with_heal: restored parked savedata.tox.broken");
            }
            catch (Exception e) { e.printStackTrace(); }
        }

        final String primary_pass = savedata_passphrase_for(PREF__DB_secrect_key);
        init(app_files_directory, udp, localdisc, orbot, orbot_host, orbot_port, primary_pass, ipv6,
             force_udp, ngc_video_bitrate, ngc_video_max_quantizer, ngc_audio_bitrate,
             ngc_audio_samplerate, ngc_audio_channels);

        if (get_my_toxid() != null)
        {
            if (prefs != null) { try { prefs.edit().putBoolean("kq_broken_restore_tried", false).apply(); } catch (Exception ignored) {} }
            register_khandaq_lossless_pktids();
            return true;
        }

        // decrypt failed but savedata is present -> try every DB-key candidate (savedata may be
        // encrypted under an older key). On the first that loads, re-sync it to the current key.
        if (sd_existed)
        {
            try
            {
                for (final String cand : DbSecretKeyStorage.candidateDbSecretKeys(this))
                {
                    if (TextUtils.isEmpty(cand)) { continue; }
                    final String pass = savedata_passphrase_for(cand);
                    if (pass.equals(primary_pass)) { continue; }
                    init(app_files_directory, udp, localdisc, orbot, orbot_host, orbot_port, pass, ipv6,
                         force_udp, ngc_video_bitrate, ngc_video_max_quantizer, ngc_audio_bitrate,
                         ngc_audio_samplerate, ngc_audio_channels);
                    if (get_my_toxid() != null)
                    {
                        try { update_savedata_file(primary_pass); } catch (Exception ignored) {}
                        if (prefs != null) { try { prefs.edit().putBoolean("kq_broken_restore_tried", false).apply(); } catch (Exception ignored) {} }
                        register_khandaq_lossless_pktids();
                        HelperGeneric.logI(TAG, "init_tox_with_heal: recovered identity via candidate key + re-synced savedata");
                        return true;
                    }
                }
            }
            catch (Exception e) { e.printStackTrace(); }
        }

        HelperGeneric.logI(TAG, "init_tox_with_heal: could not load identity with any key candidate");
        return false;
    }

    // KHANDAQ (#193 fix): register the RECEIVE callback for our 1:1 custom lossless packet ids —
    // edit (186), delete-for-both (187) and reactions (188). The native lib only auto-registers
    // 170/176/181-183, so without this toxcore silently DROPS these packets on the recipient and
    // delete-for-both / edit / reactions never apply on an Android peer (iOS registered them, which
    // is why Android->iOS worked but iOS/Android->Android did not). The JNI registrar already exists.
    private void register_khandaq_lossless_pktids()
    {
        try
        {
            int last_res = -1000;
            for (int pktid = 184; pktid <= 188; pktid++)
            {
                last_res = tox_callback_friend_lossless_packet_per_pktid(pktid);
            }
            // plain android Log on purpose: this runs very early, before the app logger is up
            android.util.Log.i("trifa.KQREG", "registered lossless pktids 184-188, last_res=" + last_res);
        }
        catch (Throwable e)
        {
            android.util.Log.i("trifa.KQREG", "register FAILED: " + e);
            e.printStackTrace();
        }
    }

    // savedata.tox passphrase for a given DB key = Base64(SHA256(UTF8(dbKey))) — the exact
    // expression used when writing savedata (HelperGeneric.update_savedata_file_wrapper).
    static String savedata_passphrase_for(final String dbKey)
    {
        return TrifaSetPatternActivity.bytesToString(TrifaSetPatternActivity.sha256(
                TrifaSetPatternActivity.StringToBytes2(dbKey)));
    }

    //    static void stop_tox()
    //    {
    //        try
    //        {
    //            Thread t = new Thread()
    //            {
    //                @Override
    //                public void run()
    //                {
    //                    long counter = 0;
    //                    while (tox_service_fg == null)
    //                    {
    //                        counter++;
    //                        if (counter > 100)
    //                        {
    //                            break;
    //                        }
    //
    //                        try
    //                        {
    //                            Thread.sleep(100);
    //                        }
    //                        catch (Exception e)
    //                        {
    //                            e.printStackTrace();
    //                        }
    //                    }
    //
    //                    try
    //                    {
    //
    //                        tox_service_fg.stop_tox_fg();
    //                    }
    //                    catch (Exception e)
    //                    {
    //                        e.printStackTrace();
    //                    }
    //                }
    //            };
    //            t.start();
    //        }
    //        catch (Exception e)
    //        {
    //            e.printStackTrace();
    //            HelperGeneric.logI(TAG, "stop_tox:EE:" + e.getMessage());
    //        }
    //    }

    static void global_stop_tox()
    {
        try
        {
            ConfGroupAudioService.stop_me(true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            CallAudioService.stop_me(true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            if (is_tox_started)
            {
                tox_service_fg.stop_tox_fg(false);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    void global_start_tox()
    {
        try
        {
            if (!is_tox_started)
            {
                int PREF__orbot_enabled_to_int = 0;

                if (PREF__orbot_enabled)
                {
                    PREF__orbot_enabled_to_int = 1;
                }

                int PREF__local_discovery_enabled_to_int = 0;

                if (PREF__local_discovery_enabled)
                {
                    PREF__local_discovery_enabled_to_int = 1;
                }

                int PREF__ipv6_enabled_to_int = 0;
                if (PREF__ipv6_enabled)
                {
                    PREF__ipv6_enabled_to_int = 1;
                }

                int PREF__force_udp_only_to_int = 0;
                if (PREF__force_udp_only)
                {
                    PREF__force_udp_only_to_int = 1;
                }

                init_tox_with_heal(PREF__udp_enabled, PREF__local_discovery_enabled_to_int,
                     PREF__orbot_enabled_to_int, ORBOT_PROXY_HOST, ORBOT_PROXY_PORT, PREF__ipv6_enabled_to_int,
                     PREF__force_udp_only_to_int, PREF__ngc_video_bitrate, PREF__ngc_video_max_quantizer,
                     PREF__ngc_audio_bitrate, PREF__ngc_audio_samplerate, PREF__ngc_audio_channels);

                HelperGeneric.logI(TAG, "set_all_conferences_inactive:001");
                HelperConference.set_all_conferences_inactive();
                tox_service_fg.tox_thread_start_fg();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        try
        {
            unregisterReceiver(receiver1);
        }
        catch (Exception e)
        {
        }

        try
        {
            unregisterReceiver(receiver2);
        }
        catch (Exception e)
        {
        }

        try
        {
            unregisterReceiver(receiver3);
        }
        catch (Exception e)
        {
        }

        try
        {
            unregisterReceiver(receiver4);
        }
        catch (Exception e)
        {
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        // just in case, update own activity pointer!
        main_activity_s = this;
    }

    @Override
    protected void onPause()
    {
        HelperGeneric.logI(TAG, "onPause");
        main_activity_resumed = false;
        super.onPause();
    }

    private void refreshFriendListFragmentReferences()
    {
        if (chatsFragment != null)
        {
            MainActivity.friend_list_fragment = chatsFragment;
        }
        if (contactsFragment != null)
        {
            MainActivity.contacts_list_fragment = contactsFragment;
        }
    }

    @Override
    protected void onResume()
    {
        HelperGeneric.logI(TAG, "onResume");
        main_activity_resumed = true;
        super.onResume();
        refreshFriendListFragmentReferences();
        ProfileTabConnectionIndicator.updateAsync();
        MainHeaderBrandingHelper.update(this);

        if (main_handler_s != null)
        {
            main_handler_s.post(new Runnable()
            {
                @Override
                public void run()
                {
                    HelperMsgNotification.consume_pending_open_chat(MainActivity.this);
                    if (is_tox_started)
                    {
                        resume_stalled_incoming_filetransfers();
                        TrifaToxService.wakeup_tox_thread();
                    }
                }
            });
        }

        /*
         // **************************************
         // **************************************
         // **************************************
        VFS_Append_test_001();
         // **************************************
         // **************************************
         // **************************************
         */

        // prefs ----------
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        PREF__UV_reversed = settings.getBoolean("video_uv_reversed", true);
        PREF__notification_sound = settings.getBoolean("notifications_new_message_sound", true);
        PREF__notification_vibrate = settings.getBoolean("notifications_new_message_vibrate", true);
        PREF__notification_show_content = settings.getBoolean("notification_show_content", true);
        PREF__notification = settings.getBoolean("notifications_new_message", true);
        if ((nmn3 != null) && (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O))
        {
            HelperMsgNotification.ensure_notification_channels(this, nmn3);
        }
        PREF__software_echo_cancel = settings.getBoolean("software_echo_cancel", false);
        PREF__fps_half = settings.getBoolean("fps_half", false);
        PREF__h264_encoder_use_intra_refresh = settings.getBoolean("h264_encoder_use_intra_refresh", true);
        PREF__U_keep_nospam = settings.getBoolean("U_keep_nospam", false);
        PREF__set_fps = settings.getBoolean("set_fps", false);
        PREF__conference_show_system_messages = settings.getBoolean("conference_show_system_messages", false);
        PREF__X_battery_saving_mode = settings.getBoolean("X_battery_saving_mode", false);
        PREF__X_misc_button_enabled = settings.getBoolean("X_misc_button_enabled", false);
        PREF__local_discovery_enabled = settings.getBoolean("local_discovery_enabled", true);
        PREF__force_udp_only = settings.getBoolean("force_udp_only", false);
        PREF__use_incognito_keyboard = settings.getBoolean("use_incognito_keyboard", true);
        PREF__speakerphone_tweak = settings.getBoolean("speakerphone_tweak", false);
        PREF__mic_gain_factor_toggle = settings.getBoolean("mic_gain_factor_toggle", false);
        PREF__window_security = settings.getBoolean("window_security", false);
        PREF__send_read_receipts = settings.getBoolean("pref_send_read_receipts", true);
        PREF__use_native_audio_play = settings.getBoolean("X_use_native_audio_play", true);
        PREF__tox_set_do_not_sync_av = settings.getBoolean("X_tox_set_do_not_sync_av", false);
        PREF__use_H264_hw_encoding = settings.getBoolean("use_H264_hw_encoding", false);

        // reset trigger for throttled saving
        update_savedata_file_wrapper_throttled_last_trigger_ts = 0;

        remove_all_progressDialogs();

        try
        {
            if (settings.getString("X_battery_saving_timeout", "15").compareTo("15") == 0)
            {
                PREF__X_battery_saving_timeout = 15;
            }
            else
            {
                PREF__X_battery_saving_timeout = Integer.parseInt(settings.getString("X_battery_saving_timeout", "15"));
                HelperGeneric.logI(TAG, "PREF__X_battery_saving_timeout:2:=" + PREF__X_battery_saving_timeout);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__X_battery_saving_timeout = 15;
        }

        try
        {
            PREF__X_eac_delay_ms = Integer.parseInt(settings.getString("X_eac_delay_ms_2", "80"));
        }
        catch (Exception e)
        {
            PREF__X_eac_delay_ms = 80;
            e.printStackTrace();
        }

        try
        {
            int temp = Integer.parseInt(settings.getString("message_paging_num_msgs_per_page", "50"));

            // HINT: sanity check pref value
            if ((temp < 0) || (temp > 999))
            {
                temp = 50;
            }

            PREF__message_paging_num_msgs_per_page = temp;

            if (PREF__message_paging_num_msgs_per_page == 0)
            {
                PREF__messageview_paging = false;
            }
            else
            {
                PREF__messageview_paging = true;
            }
        }
        catch (Exception e)
        {
            PREF__message_paging_num_msgs_per_page = 50;
            PREF__messageview_paging = true;
            e.printStackTrace();
        }

        // HINT: disable paging for now!!!!!!!!!!!!
        // HINT: disable paging for now!!!!!!!!!!!!
        // HINT: disable paging for now!!!!!!!!!!!!
        PREF__message_paging_num_msgs_per_page = 0;
        PREF__messageview_paging = false;
        // HINT: disable paging for now!!!!!!!!!!!!
        // HINT: disable paging for now!!!!!!!!!!!!
        // HINT: disable paging for now!!!!!!!!!!!!

        try
        {
            PREF__X_audio_play_buffer_custom = Integer.parseInt(settings.getString("X_audio_play_buffer_custom", "0"));
        }
        catch (Exception e)
        {
            PREF__X_audio_play_buffer_custom = 0;
            e.printStackTrace();
        }


        try
        {
            PREF_mic_gain_factor = (float) (settings.getInt("mic_gain_factor", 1));
            HelperGeneric.logI(TAG, "PREF_mic_gain_factor:1=" + PREF_mic_gain_factor);
            // PREF_mic_gain_factor = PREF_mic_gain_factor + 1.0f;

            if (PREF_mic_gain_factor < 1.0f)
            {
                PREF_mic_gain_factor = 1.0f;
            }
            else if (PREF_mic_gain_factor > 30.0f)
            {
                PREF_mic_gain_factor = 30.0f;
            }
            HelperGeneric.logI(TAG, "PREF_mic_gain_factor:2=" + PREF_mic_gain_factor);
        }
        catch (Exception e)
        {
            PREF_mic_gain_factor = 1.0f;
            HelperGeneric.logI(TAG, "PREF_mic_gain_factor:E=" + PREF_mic_gain_factor);
            e.printStackTrace();
        }

        if ((PREF__U_keep_nospam == true) && (top_imageview2 != null))
        {
            top_imageview2.setBackgroundColor(Color.TRANSPARENT);
            // top_imageview.setBackgroundColor(Color.parseColor("#C62828"));
            final Drawable d1 = new IconicsDrawable(this).icon(FontAwesome.Icon.faw_exclamation_circle).paddingDp(
                    15).color(getResources().getColor(R.color.md_red_600)).sizeDp(100);
            top_imageview2.setImageDrawable(d1);
            top_imageview2.setVisibility(View.VISIBLE);
        }
        else if (top_imageview2 != null)
        {
            top_imageview2.setVisibility(View.GONE);
        }

        own_push_token_load();
        if ((PREF__hide_setup_push_tip == false) && (TRIFAGlobals.global_notification_token == null) &&
            (top_imageview3 != null))
        {
            // show icon for PUSH tip
            top_imageview3.setBackgroundColor(Color.TRANSPARENT);
            final Drawable d1 = new IconicsDrawable(this).icon(FontAwesome.Icon.faw_info_circle).paddingDp(15).color(
                    getResources().getColor(R.color.md_yellow_600)).sizeDp(100);
            top_imageview3.setImageDrawable(d1);
            top_imageview3.setVisibility(View.VISIBLE);
        }
        else if (top_imageview3 != null)
        {
            top_imageview3.setVisibility(View.GONE);
        }

        if (top_imageview3 != null)
        {
            top_imageview3.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                try
                {
                    AlertDialog ad = new AlertDialog.Builder(view.getContext()).setNegativeButton(
                            R.string.MainActivity_no_button, new DialogInterface.OnClickListener()
                            {
                                public void onClick(DialogInterface dialog, int id)
                                {
                                }
                            }).setPositiveButton(R.string.MainActivity_ok_take_me_there_button,
                                                 new DialogInterface.OnClickListener()
                                                 {
                                                     public void onClick(DialogInterface dialog, int id)
                                                     {
                                                         try
                                                         {
                                                             Intent i = new Intent(Intent.ACTION_VIEW);
                                                             i.setData(Uri.parse(TOX_PUSH_SETUP_HOWTO_URL));
                                                             startActivity(i);
                                                         }
                                                         catch (Exception e)
                                                         {
                                                         }
                                                     }
                                                 }).create();
                    ad.setTitle(getString(R.string.MainActivity_setup_push_tip_title));
                    ad.setMessage(getString(R.string.MainActivity_setup_push_tip_text));
                    ad.setCancelable(false);
                    ad.setCanceledOnTouchOutside(false);
                    ad.show();
                }
                catch (Exception ee2)
                {
                }
            }
        });
        }

        try
        {
            if (have_own_relay())
            {
                int relay_connection_status_real = get_own_relay_connection_status_real();

                if (relay_connection_status_real == 2)
                {
                    draw_main_top_icon(top_imageview, context_s, Color.parseColor("#04b431"), true);
                }
                else if (relay_connection_status_real == 1)
                {
                    draw_main_top_icon(top_imageview, context_s, Color.parseColor("#ffce00"), true);
                }
                else
                {
                    draw_main_top_icon(top_imageview, context_s, Color.parseColor("#ff0000"), true);
                }
            }
            else
            {
                draw_main_top_icon(top_imageview, this, Color.GRAY, true);
            }
        }
        catch (Exception e)
        {
        }


        // NOTE (Khandaq): this migration sets PREF__udp_enabled=false ("TCP mode"), but it is
        // currently a NO-OP at the transport level: the native layer in create_tox()
        // (jni-c-toxcore.c, "options.udp_enabled = true;") unconditionally re-enables UDP, ignoring
        // this flag. So the shipped libjni-c-toxcore.so ALWAYS runs UDP-enabled regardless of what we
        // set here. That is actually acceptable today: NGC public-group discovery (the onion DHT
        // announce-lookup a chat-id join depends on) needs UDP, and pure-TCP discovery is an unsolved
        // toxcore-level gap. The real consequence is for joiners on UDP-blocked networks (mobile
        // carriers / some regions): their chat-id join cannot complete (see HelperGroup
        // .maybe_hint_udp_blocked) — the working bypass is a friend-assisted invite. The pref is kept
        // so a future rebuilt .so that honors it can flip transport without another migration.
        // Do NOT trust this flag to mean "TCP-only" until jni-c-toxcore.c:699 is rebuilt and shipped.
        if (!settings.getBoolean("khandaq_ngc_udp_tcp_v3", false))
        {
            settings.edit().putBoolean("udp_enabled", false).
                    putBoolean("khandaq_ngc_udp_tcp_v3", true).apply();
        }

        boolean tmp1 = settings.getBoolean("udp_enabled", false);

        if (tmp1)
        {
            PREF__udp_enabled = 1;
        }
        else
        {
            PREF__udp_enabled = 0;
        }

        try
        {
            PREF__video_call_quality = Integer.parseInt(settings.getString("video_call_quality", "1"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__video_call_quality = 1;
        }

        try
        {
            PREF__higher_audio_quality = Integer.parseInt(settings.getString("higher_audio_quality", "1"));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__higher_audio_quality = 1;
        }

        if (PREF__higher_audio_quality == 2)
        {
            GLOBAL_AUDIO_BITRATE = HIGHER_GLOBAL_AUDIO_BITRATE;
        }
        else if (PREF__higher_audio_quality == 1)
        {
            GLOBAL_AUDIO_BITRATE = NORMAL_GLOBAL_AUDIO_BITRATE;
        }
        else
        {
            GLOBAL_AUDIO_BITRATE = LOWER_GLOBAL_AUDIO_BITRATE;
        }

        try
        {
            if (settings.getString("min_audio_samplingrate_out", "8000").compareTo("Auto") == 0)
            {
                PREF__min_audio_samplingrate_out = 8000;
            }
            else
            {
                PREF__min_audio_samplingrate_out = Integer.parseInt(
                        settings.getString("min_audio_samplingrate_out", "" + MIN_AUDIO_SAMPLINGRATE_OUT));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__min_audio_samplingrate_out = MIN_AUDIO_SAMPLINGRATE_OUT;
        }

        // ------- FIXED -------
        PREF__min_audio_samplingrate_out = SAMPLE_RATE_FIXED;
        // ------- FIXED -------


        HelperGeneric.logI(TAG, "PREF__UV_reversed:2=" + PREF__UV_reversed);
        HelperGeneric.logI(TAG, "PREF__min_audio_samplingrate_out:2=" + PREF__min_audio_samplingrate_out);

        try
        {
            PREF__allow_screen_off_in_audio_call = settings.getBoolean("allow_screen_off_in_audio_call", true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_screen_off_in_audio_call = true;
        }

        try
        {
            PREF__auto_accept_image = settings.getBoolean("auto_accept_image", true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__auto_accept_image = true;
        }

        try
        {
            PREF__auto_accept_video = settings.getBoolean("auto_accept_video", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__auto_accept_video = false;
        }

        try
        {
            PREF__auto_accept_all_upto = settings.getBoolean("auto_accept_all_upto", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__auto_accept_all_upto = false;
        }

        try
        {
            PREF__attachment_download_mode = settings.getInt("attachment_download_mode", 2);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__attachment_download_mode = 2;
        }

        try
        {
            PREF__X_zoom_incoming_video = settings.getBoolean("X_zoom_incoming_video", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__X_zoom_incoming_video = false;
        }

        try
        {
            PREF__X_audio_recording_frame_size = Integer.parseInt(
                    settings.getString("X_audio_recording_frame_size", "" + 40));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__X_audio_recording_frame_size = 40;
        }

        // ------- FIXED -------
        PREF__X_audio_recording_frame_size = FRAME_SIZE_FIXED;
        // ------- FIXED -------

        try
        {
            PREF__video_cam_resolution = Integer.parseInt(settings.getString("video_cam_resolution", "" + 1));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__video_cam_resolution = 1;
        }

        HelperGeneric.applyGlobalVideoBitrateFromPrefs(this);

        try
        {
            PREF__dark_mode_pref = Integer.parseInt(settings.getString("dark_mode_pref", "" + 0));
        }
        catch (Exception e)
        {
            PREF__dark_mode_pref = 0;
        }

        MainApplication.applyDarkModeFromPref(settings.getString("dark_mode_pref", "0"));

        if (PREF__dark_mode_pref == 0)
        {
            // follow system. try to guess if we actually have dark mode active now
            int currentNightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            switch (currentNightMode)
            {
                case Configuration.UI_MODE_NIGHT_NO:// Night mode is not active, we're in day time
                    PREF__dark_mode_pref = 2;
                    break;
                case Configuration.UI_MODE_NIGHT_YES:// Night mode is active, we're at night!
                    PREF__dark_mode_pref = 1;
                    break;
                case Configuration.UI_MODE_NIGHT_UNDEFINED:// We don't know what mode we're in, assume notnight
                    PREF__dark_mode_pref = 1;
                    break;
            }
        }

        try
        {
            PREF__global_font_size = Integer.parseInt(
                    settings.getString("global_font_size", "" + PREF_GLOBAL_FONT_SIZE_DEFAULT));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__global_font_size = PREF_GLOBAL_FONT_SIZE_DEFAULT;
        }

        PREF__camera_get_preview_format = settings.getString("camera_get_preview_format", "YV12");

        try
        {
            PREF__compact_friendlist = settings.getBoolean("compact_friendlist", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__compact_friendlist = false;
        }

        try
        {
            PREF__allow_file_sharing_to_trifa_via_intent = settings.getBoolean("allow_file_sharing_to_trifa_via_intent",
                                                                               true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_file_sharing_to_trifa_via_intent = true;
        }

        try
        {
            PREF__allow_open_encrypted_file_via_intent = settings.getBoolean("allow_open_encrypted_file_via_intent",
                                                                             false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_open_encrypted_file_via_intent = false;
        }

        PREF__compact_chatlist = true;

        try
        {
            PREF__allow_push_server_ntfy = settings.getBoolean("allow_push_server_ntfy", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_push_server_ntfy = false;
        }

        try
        {
            PREF__allow_push_server_sunup = settings.getBoolean("allow_push_server_sunup", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__allow_push_server_sunup = false;
        }

        try
        {
            PREF__use_push_service = settings.getBoolean("use_push_service", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__use_push_service = false;
        }

        try
        {
            PREF__gainprocessing_active = settings.getBoolean("gainprocessing_active", true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__gainprocessing_active = true;
        }
        if (PREF__gainprocessing_active)
        {
            set_gainprocessing_active(1);
        }
        else
        {
            set_gainprocessing_active(0);
        }

        try
        {
            PREF__rnnoise_active = settings.getBoolean("rnnoise_active", false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            PREF__rnnoise_active = false;
        }
        if (PREF__rnnoise_active)
        {
            set_rnnoise_active(1);
        }
        else
        {
            set_rnnoise_active(0);
        }

        //    PREF__use_camera_x = false;

        try
        {
            final String muted_peers_01 = settings.getString("toxirc_muted_peers_01", "");
            final String muted_peers_02 = settings.getString("toxirc_muted_peers_02", "");
            final String muted_peers_03 = settings.getString("toxirc_muted_peers_03", "");

            int count_muted = 0;
            if (muted_peers_01.length() > 0)
            {
                count_muted++;
            }
            if (muted_peers_02.length() > 0)
            {
                count_muted++;
            }
            if (muted_peers_03.length() > 0)
            {
                count_muted++;
            }

            if (count_muted > 0)
            {
                PREF__toxirc_muted_peers = new String[count_muted];
                int i = 0;
                if (muted_peers_01.length() > 0)
                {
                    PREF__toxirc_muted_peers[i] = muted_peers_01;
                    i++;
                }

                if (muted_peers_02.length() > 0)
                {
                    PREF__toxirc_muted_peers[i] = muted_peers_02;
                    i++;
                }

                if (muted_peers_03.length() > 0)
                {
                    PREF__toxirc_muted_peers[i] = muted_peers_03;
                    i++;
                }
            }
            else
            {
                PREF__toxirc_muted_peers = new String[]{};
            }
        }
        catch (Exception e)
        {
            PREF__toxirc_muted_peers = new String[]{};
        }

        // prefs ----------

        update_main_profile_bar();

        if (spinner_own_status != null)
        {
            spinner_own_status.setSelection(global_tox_self_status);
        }
        // just in case, update own activity pointer!
        main_activity_s = this;

        try
        {
            // ask user to whitelist app from DozeMode/BatteryOptimizations
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                SharedPreferences settings2 = PreferenceManager.getDefaultSharedPreferences(this);
                boolean asked_for_whitelist_doze_already = settings2.getBoolean("asked_whitelist_doze", false);

                if (!asked_for_whitelist_doze_already)
                {
                    settings2.edit().putBoolean("asked_whitelist_doze", true).commit();
                    final Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    ResolveInfo resolve_activity = getPackageManager().resolveActivity(intent, 0);

                    if (resolve_activity != null)
                    {
                        AlertDialog ad = new AlertDialog.Builder(this).setNegativeButton(
                                R.string.MainActivity_no_button, new DialogInterface.OnClickListener()
                                {
                                    public void onClick(DialogInterface dialog, int id)
                                    {
                                    }
                                }).setPositiveButton(R.string.MainActivity_ok_take_me_there_button,
                                                     new DialogInterface.OnClickListener()
                                                     {
                                                         public void onClick(DialogInterface dialog, int id)
                                                         {
                                                             startActivity(intent);
                                                         }
                                                     }).create();
                        ad.setTitle(getString(R.string.MainActivity_info_dialog_title));
                        ad.setMessage(getString(R.string.MainActivity_add_to_batt_opt));
                        ad.setCancelable(false);
                        ad.setCanceledOnTouchOutside(false);
                        ad.show();
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        // Pending FCM token from legacy NEED_ACK row (auto-apply, no dialog)
        try
        {
            if (get_g_opts(NOTIFICATION_TOKEN_DB_KEY_NEED_ACK) != null)
            {
                HelperRelay.apply_notification_token_auto(get_g_opts(NOTIFICATION_TOKEN_DB_KEY_NEED_ACK));
            }
        }
        catch (Exception ee2)
        {
            HelperGeneric.logI(TAG, "NOTIFICATION_TOKEN_DB_KEY_NEED_ACK:EE03:" + ee2.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent i)
    {
        HelperGeneric.logI(TAG, "onNewIntent:i=" + i);
        super.onNewIntent(i);
        setIntent(i);
        final String open_chat_key = HelperMsgNotification.extract_open_chat_key_from_intent(i);
        if (open_chat_key != null)
        {
            HelperMsgNotification.store_pending_open_chat_key(open_chat_key);
            if (main_handler_s != null)
            {
                main_handler_s.post(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        HelperMsgNotification.consume_pending_open_chat(MainActivity.this);
                    }
                });
            }
        }
    }

    @Override
    public void onBackPressed()
    {
        if (currentMainTab == R.id.bottom_nav_settings && settingsFragment != null && settingsFragment.handleBackPress())
        {
            return;
        }

        super.onBackPressed();
    }

    // -- this is for incoming video --
    // -- this is for incoming video --
    static void allocate_video_buffer_1(int frame_width_px1, int frame_height_px1, long ystride, long ustride, long vstride)
    {
        try
        {
            //Log.i("semaphore_01","acquire:01");
            semaphore_videoout_bitmap.acquire();
            //Log.i("semaphore_01","acquire:01:OK");
        }
        catch (InterruptedException e)
        {
            //Log.i("semaphore_01","release:01");
            semaphore_videoout_bitmap.release();
            //Log.i("semaphore_01","release:01:OK");
            return;
        }

        if (video_buffer_1 != null)
        {
            video_buffer_1 = null;
        }

        if (video_frame_image != null)
        {
            video_frame_image_valid = false;

            if (!video_frame_image.isRecycled())
            {
                if (!PREF__NO_RECYCLE_VIDEO_FRAME_BITMAP)
                {
                    HelperGeneric.logI(TAG, "video_frame_image.recycle:start");
                    video_frame_image.recycle();
                    HelperGeneric.logI(TAG, "video_frame_image.recycle:end");
                }
            }

            video_frame_image = null;
        }

        /*
         * YUV420 frame with width * height
         *
         * @param y Luminosity plane. Size = MAX(width, abs(ystride)) * height.
         * @param u U chroma plane. Size = MAX(width/2, abs(ustride)) * (height/2).
         * @param v V chroma plane. Size = MAX(width/2, abs(vstride)) * (height/2).
         */
        int y_layer_size = (int) Math.max(frame_width_px1, Math.abs(ystride)) * frame_height_px1;
        int u_layer_size = (int) Math.max((frame_width_px1 / 2), Math.abs(ustride)) * (frame_height_px1 / 2);
        int v_layer_size = (int) Math.max((frame_width_px1 / 2), Math.abs(vstride)) * (frame_height_px1 / 2);
        int frame_width_px = (int) Math.max(frame_width_px1, Math.abs(ystride));
        int frame_height_px = (int) frame_height_px1;
        buffer_size_in_bytes = y_layer_size + v_layer_size + u_layer_size;
        HelperGeneric.logI(TAG, "YUV420 frame w1=" + frame_width_px1 + " h1=" + frame_height_px1 + " bytes=" + buffer_size_in_bytes);
        HelperGeneric.logI(TAG, "YUV420 frame w=" + frame_width_px + " h=" + frame_height_px + " bytes=" + buffer_size_in_bytes);
        HelperGeneric.logI(TAG, "YUV420 frame ystride=" + ystride + " ustride=" + ustride + " vstride=" + vstride);
        video_buffer_1 = ByteBuffer.allocateDirect(buffer_size_in_bytes);
        set_JNI_video_buffer(video_buffer_1, frame_width_px, frame_height_px);
        RenderScript rs = RenderScript.create(context_s);
        yuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));
        // --------- works !!!!! ---------
        // --------- works !!!!! ---------
        // --------- works !!!!! ---------
        Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(frame_width_px).setY(frame_height_px);
        yuvType.setYuvFormat(ImageFormat.YV12);
        alloc_in = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);
        Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(frame_width_px).setY(frame_height_px);
        alloc_out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
        // --------- works !!!!! ---------
        // --------- works !!!!! ---------
        // --------- works !!!!! ---------
        video_frame_image = Bitmap.createBitmap(frame_width_px, frame_height_px, Bitmap.Config.ARGB_8888);

        if (video_frame_image == null)
        {
            video_frame_image_valid = false;
            video_buffer_1 = null;
        }
        else
        {
            video_frame_image_valid = true;
        }

        //Log.i("semaphore_01","release:02");
        semaphore_videoout_bitmap.release();
        //Log.i("semaphore_01","relase:02:OK");
    }
    // -- this is for incoming video --
    // -- this is for incoming video --

    static
    {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true);
    }

    // -------- native methods --------
    // -------- native methods --------
    // -------- native methods --------
    public native void init(@NonNull String data_dir, int udp_enabled, int local_discovery_enabled, int orbot_enabled, String orbot_host, long orbot_port, String tox_encrypt_passphrase_hash, int enable_ipv6, int force_udp_only_mode, int ngc_video_bitrate, int max_quantizer, int ngc_audio_bitrate, int ngc_audio_sampling_rate, int ngc_audio_channel_count);

    // KHANDAQ (#193): register the friend lossless receive callback for a custom packet id
    // (used for edit/delete/reaction — ids 184-188). The native lib exposes this already.
    public native int tox_callback_friend_lossless_packet_per_pktid(int pktid);

    public native String getNativeLibAPI();

    public static native String getNativeLibGITHASH();

    public static native String getNativeLibTOXGITHASH();

    public static native void update_savedata_file(String tox_encrypt_passphrase_hash);

    public static native void export_savedata_file_unsecure(String tox_encrypt_passphrase_hash, String export_full_path_of_file);

    public static native String get_my_toxid();

    public static native String tox_get_all_tcp_relays();

    public static native String tox_get_all_udp_connections();

    // *UNUSED* public static native void bootstrap();

    public static native int add_tcp_relay_single(String ip, String key_hex, long port);

    public static native int bootstrap_single(String ip, String key_hex, long port);

    public static native int tox_self_get_connection_status();

    public static native void init_tox_callbacks();

    public static native long tox_iteration_interval();

    public static native long tox_iterate();

    // ----------- TRIfA internal -----------
    public static native int jni_iterate_group_audio(int delta_new, int want_ms_output);

    public static native int jni_iterate_videocall_audio(int delta_new, int want_ms_output, int channels, int sample_rate, int send_emtpy_buffer);

    public static native void tox_set_do_not_sync_av(int do_not_sync_av);

    public static native void tox_set_onion_active(int active);
    // ----------- TRIfA internal -----------

    public static native long tox_kill();

    public static native void exit();

    public static native long tox_friend_send_message(long friendnum, int a_TOX_MESSAGE_TYPE, @NonNull String message);

    public static native long tox_version_major();

    public static native long tox_version_minor();

    public static native long tox_version_patch();

    public static native String jnictoxcore_version();

    public static native String libavutil_version();

    public static native String libopus_version();

    public static native String libvpx_version();

    public static native String libsodium_version();

    public static native long tox_max_filename_length();

    public static native long tox_file_id_length();

    public static native long tox_max_message_length();

    public static native long tox_friend_add(@NonNull String toxid_str, @NonNull String message);

    public static native long tox_friend_add_norequest(@NonNull String public_key_str);

    public static native long tox_self_get_friend_list_size();

    public static native void tox_self_set_nospam(long nospam); // this actually needs an "uint32_t" which is an unsigned 32bit integer value

    public static native long tox_self_get_nospam(); // this actually returns an "uint32_t" which is an unsigned 32bit integer value

    public static native long tox_friend_by_public_key(@NonNull String friend_public_key_string);

    public static native String tox_friend_get_name(long friend_number);

    public static native String tox_friend_get_status_message(long friend_number);

    public static native String tox_friend_get_public_key(long friend_number);

    public static native long tox_friend_get_capabilities(long friend_number);

    public static native long[] tox_self_get_friend_list();

    public static native int tox_self_set_name(@NonNull String name);

    public static native int tox_self_set_status_message(@NonNull String status_message);

    public static native void tox_self_set_status(int a_TOX_USER_STATUS);

    public static native int tox_self_set_typing(long friend_number, int typing);

    public static native int tox_friend_get_connection_status(long friend_number);

    public static native String tox_friend_get_connection_ip(long friend_number);

    public static native int tox_friend_delete(long friend_number);

    public static native String tox_self_get_name();

    public static native long tox_self_get_name_size();

    public static native long tox_self_get_status_message_size();

    public static native String tox_self_get_status_message();

    public static native long tox_self_get_capabilities();

    public static native int tox_friend_send_lossless_packet(long friend_number, @NonNull byte[] data, int data_length);

    public static native int tox_file_control(long friend_number, long file_number, int a_TOX_FILE_CONTROL);

    public static native int tox_hash(ByteBuffer hash_buffer, ByteBuffer data_buffer, long data_length);

    public static native int tox_file_seek(long friend_number, long file_number, long position);

    public static native int tox_file_get_file_id(long friend_number, long file_number, ByteBuffer file_id_buffer);

    // public static native long tox_file_sending_active(long friend_number);

    // public static native long tox_file_receiving_active(long friend_number);

    public static native long tox_file_send(long friend_number, long kind, long file_size, ByteBuffer file_id_buffer, String file_name, long filename_length);

    public static native int tox_file_send_chunk(long friend_number, long file_number, long position, ByteBuffer data_buffer, long data_length);


    // --------------- Message V2 -------------
    // --------------- Message V2 -------------
    // --------------- Message V2 -------------
    public static native long tox_messagev2_size(long text_length, long type, long alter_type);

    public static native int tox_messagev2_wrap(long text_length, long type, long alter_type, ByteBuffer message_text_buffer, long ts_sec, long ts_ms, ByteBuffer raw_message_buffer, ByteBuffer msgid_buffer);

    public static native int tox_messagev2_get_message_id(ByteBuffer raw_message_buffer, ByteBuffer msgid_buffer);

    public static native long tox_messagev2_get_ts_sec(ByteBuffer raw_message_buffer);

    public static native long tox_messagev2_get_ts_ms(ByteBuffer raw_message_buffer);

    public static native long tox_messagev2_get_message_text(ByteBuffer raw_message_buffer, long raw_message_len, int is_alter_msg, long alter_type, ByteBuffer message_text_buffer);

    public static native String tox_messagev2_get_sync_message_pubkey(ByteBuffer raw_message_buffer);

    public static native long tox_messagev2_get_sync_message_type(ByteBuffer raw_message_buffer);

    public static native int tox_util_friend_send_msg_receipt_v2(long friend_number, long ts_sec, ByteBuffer msgid_buffer);

    public static native long tox_util_friend_send_message_v2(long friend_number, int type, long ts_sec, String message, long length, ByteBuffer raw_message_back_buffer, ByteBuffer raw_message_back_buffer_length, ByteBuffer msgid_back_buffer);

    public static native int tox_util_friend_resend_message_v2(long friend_number, ByteBuffer raw_message_buffer, long raw_msg_len);
    // --------------- Message V2 -------------
    // --------------- Message V2 -------------
    // --------------- Message V2 -------------

    // --------------- Message V3 -------------
    // --------------- Message V3 -------------
    // --------------- Message V3 -------------
    public static native int tox_messagev3_get_new_message_id(ByteBuffer hash_buffer);

    public static native long tox_messagev3_friend_send_message(long friendnum, int a_TOX_MESSAGE_TYPE, @NonNull String message, @NonNull ByteBuffer mag_hash, long timestamp);
    // --------------- Message V3 -------------
    // --------------- Message V3 -------------
    // --------------- Message V3 -------------


    // --------------- Conference -------------
    // --------------- Conference -------------
    // --------------- Conference -------------
    public static native long tox_conference_join(long friend_number, ByteBuffer cookie_buffer, long cookie_length);

    public static native long tox_conference_peer_count(long conference_number);

    public static native long tox_conference_peer_get_name_size(long conference_number, long peer_number);

    public static native String tox_conference_peer_get_name(long conference_number, long peer_number);

    public static native String tox_conference_peer_get_public_key(long conference_number, long peer_number);

    public static native long tox_conference_offline_peer_count(long conference_number);

    public static native long tox_conference_offline_peer_get_name_size(long conference_number, long offline_peer_number);

    public static native String tox_conference_offline_peer_get_name(long conference_number, long offline_peer_number);

    public static native String tox_conference_offline_peer_get_public_key(long conference_number, long offline_peer_number);

    public static native long tox_conference_offline_peer_get_last_active(long conference_number, long offline_peer_number);

    public static native int tox_conference_peer_number_is_ours(long conference_number, long peer_number);

    public static native long tox_conference_get_title_size(long conference_number);

    public static native String tox_conference_get_title(long conference_number);

    public static native int tox_conference_get_type(long conference_number);

    public static native int tox_conference_send_message(long conference_number, int a_TOX_MESSAGE_TYPE, @NonNull String message);

    public static native int tox_conference_delete(long conference_number);

    public static native long tox_conference_get_chatlist_size();

    public static native long[] tox_conference_get_chatlist();

    public static native int tox_conference_get_id(long conference_number, ByteBuffer cookie_buffer);

    public static native int tox_conference_new();

    public static native int tox_conference_invite(long friend_number, long conference_number);

    public static native int tox_conference_set_title(long conference_number, String title);

    // --------------- Conference -------------
    // --------------- Conference -------------
    // --------------- Conference -------------

    // --------------- new Groups -------------
    // --------------- new Groups -------------
    // --------------- new Groups -------------

    /**
     * Creates a new group chat.
     * <p>
     * This function creates a new group chat object and adds it to the chats array.
     * <p>
     * The caller of this function has Founder role privileges.
     * <p>
     * The client should initiate its peer list with self info after calling this function, as
     * the peer_join callback will not be triggered.
     *
     * @param a_TOX_GROUP_PRIVACY_STATE The privacy state of the group. If this is set to TOX_GROUP_PRIVACY_STATE_PUBLIC,
     *                                  the group will attempt to announce itself to the DHT and anyone with the Chat ID may join.
     *                                  Otherwise a friend invite will be required to join the group.
     * @param group_name                The name of the group. The name must be non-NULL.
     * @param my_peer_name              The name of the peer creating the group.
     * @return group_number on success, UINT32_MAX on failure.
     */
    public static native long tox_group_new(int a_TOX_GROUP_PRIVACY_STATE, @NonNull String group_name, @NonNull String my_peer_name);

    /**
     * Joins a group chat with specified Chat ID.
     * <p>
     * This function creates a new group chat object, adds it to the chats array, and sends
     * a DHT announcement to find peers in the group associated with chat_id. Once a peer has been
     * found a join attempt will be initiated.
     *
     * @param chat_id_buffer The Chat ID of the group you wish to join. This must be TOX_GROUP_CHAT_ID_SIZE bytes.
     * @param password       The password required to join the group. Set to NULL if no password is required.
     * @param my_peer_name   The name of the peer joining the group.
     * @return group_number on success, UINT32_MAX on failure.
     */
    public static native long tox_group_join(@NonNull ByteBuffer chat_id_buffer, long chat_id_length, @NonNull String my_peer_name, String password);

    public static native int tox_group_leave(long group_number, String part_message);

    public static native int tox_group_disconnect(long group_number);

    public static native long tox_group_self_get_peer_id(long group_number);

    public static native int tox_group_self_set_name(long group_number, @NonNull String my_peer_name);

    public static native String tox_group_self_get_name(long group_number);

    /** Prebuilt lib may lack this JNI symbol — never crash callers. */
    private static volatile boolean tox_group_self_get_name_jni_available = true;

    public static String tox_group_self_get_name_safe(final long group_number)
    {
        if (!tox_group_self_get_name_jni_available || group_number < 0)
        {
            return null;
        }
        try
        {
            return tox_group_self_get_name(group_number);
        }
        catch (Throwable ignored)
        {
            tox_group_self_get_name_jni_available = false;
            return null;
        }
    }

    public static native String tox_group_self_get_public_key(long group_number);

    public static native int tox_group_self_get_role(long group_number);

    public static native int tox_group_peer_get_role(long group_number, long peer_id);

    public static native int tox_group_get_chat_id(long group_number, @NonNull ByteBuffer chat_id_buffer);

    public static native long tox_group_get_number_groups();

    public static native long[] tox_group_get_grouplist();

    public static native long tox_group_peer_count(long group_number);

    public static native int tox_group_get_peer_limit(long group_number);

    public static native int tox_group_founder_set_peer_limit(long group_number, int max_peers);

    public static native int tox_group_founder_set_password(long group_number, @Nullable String password);

    public static native int tox_group_founder_set_topic_lock(long group_number, int topic_lock);

    public static native int tox_group_founder_set_privacy_state(long group_number, int privacy_state);

    public static native int tox_group_get_topic_lock(long group_number);

    public static native long tox_group_offline_peer_count(long group_number);

    public static native long[] tox_group_get_peerlist(long group_number);

    public static native long tox_group_by_chat_id(@NonNull ByteBuffer chat_id_buffer);

    public static native int tox_group_get_privacy_state(long group_number);

    public static native int tox_group_mod_kick_peer(long group_number, long peer_id);

    public static native int tox_group_mod_set_role(long group_number, long peer_id, int a_Tox_Group_Role);

    public static native int tox_group_founder_set_voice_state(long group_number, int a_Tox_Group_Voice_State);

    public static native int tox_group_get_voice_state(long group_number);

    public static native String tox_group_peer_get_public_key(long group_number, long peer_id);

    public static native String tox_group_savedpeer_get_public_key(long group_number, long slot_num);

    public static native long tox_group_peer_by_public_key(long group_number, @NonNull String peer_public_key_string);

    public static native String tox_group_peer_get_name(long group_number, long peer_id);

    public static native String tox_group_get_name(long group_number);

    public static native String tox_group_get_topic(long group_number);

    public static native int tox_group_set_topic(long group_number, @NonNull String topic);

    public static native int tox_group_peer_get_connection_status(long group_number, long peer_id);

    public static native int tox_group_invite_friend(long group_number, long friend_number);

    public static native int tox_group_is_connected(long group_number);

    public static native int tox_group_reconnect(long group_number);

    public static native int tox_group_send_custom_packet(long group_number, int lossless, @NonNull byte[] data, int data_length);

    public static native int tox_group_send_custom_private_packet(long group_number, long peer_id, int lossless, @NonNull byte[] data, int data_length);

    /**
     * Send a text chat message to the group.
     * <p>
     * This function creates a group message packet and pushes it into the send
     * queue.
     * <p>
     * The message length may not exceed TOX_MAX_MESSAGE_LENGTH. Larger messages
     * must be split by the client and sent as separate messages. Other clients can
     * then reassemble the fragments. Messages may not be empty.
     *
     * @param group_number       The group number of the group the message is intended for.
     * @param a_TOX_MESSAGE_TYPE Message type (normal, action, ...).
     * @param message            A non-NULL pointer to the first element of a byte array
     *                           containing the message text.
     * @return message_id on success. return < 0 on error.
     */
    public static native long tox_group_send_message(long group_number, int a_TOX_MESSAGE_TYPE, @NonNull String message);

    /**
     * Send a text chat message to the specified peer in the specified group.
     * <p>
     * This function creates a group private message packet and pushes it into the send
     * queue.
     * <p>
     * The message length may not exceed TOX_MAX_MESSAGE_LENGTH. Larger messages
     * must be split by the client and sent as separate messages. Other clients can
     * then reassemble the fragments. Messages may not be empty.
     *
     * @param group_number The group number of the group the message is intended for.
     * @param peer_id      The ID of the peer the message is intended for.
     * @param message      A non-NULL pointer to the first element of a byte array
     *                     containing the message text.
     * @return 0 on success. return < 0 on error.
     */
    public static native int tox_group_send_private_message(long group_number, long peer_id, int a_TOX_MESSAGE_TYPE, @NonNull String message);

    /**
     * Send a text chat message to the specified peer in the specified group.
     * <p>
     * This function creates a group private message packet and pushes it into the send
     * queue.
     * <p>
     * The message length may not exceed TOX_MAX_MESSAGE_LENGTH. Larger messages
     * must be split by the client and sent as separate messages. Other clients can
     * then reassemble the fragments. Messages may not be empty.
     *
     * @param group_number           The group number of the group the message is intended for.
     * @param peer_public_key_string A memory region of at least TOX_PUBLIC_KEY_SIZE bytes of the peer the
     *                               message is intended for. If this parameter is NULL, this function will return false.
     * @param message                A non-NULL pointer to the first element of a byte array
     *                               containing the message text.
     * @return 0 on success. return < 0 on error.
     */
    public static native int tox_group_send_private_message_by_peerpubkey(long group_number, @NonNull String peer_public_key_string, int a_TOX_MESSAGE_TYPE, @NonNull String message);

    /**
     * Accept an invite to a group chat that the client previously received from a friend. The invite
     * is only valid while the inviter is present in the group.
     *
     * @param invite_data_buffer The invite data received from the `group_invite` event.
     * @param my_peer_name       The name of the peer joining the group.
     * @param password           The password required to join the group. Set to NULL if no password is required.
     * @return the group_number on success, UINT32_MAX on failure.
     */
    public static native long tox_group_invite_accept(long friend_number, @NonNull ByteBuffer invite_data_buffer, long invite_data_length, @NonNull String my_peer_name, String password);

    public static native int toxav_ngc_video_encode(int vbitrate, int max_quantizer, int width, int height, byte[] y, int y_bytes, byte[] u, int u_bytes, byte[] v, int v_bytes, byte[] encoded_frame_bytes);

    public static native int toxav_ngc_video_decode(byte[] encoded_frame_bytes, int encoded_frame_size_bytes, int width, int height, byte[] y, byte[] u, byte[] v, int flush_decoder);

    public static native int toxav_ngc_audio_encode(byte[] pcm, int sample_count_per_frame, byte[] encoded_frame_bytes);

    public static native int toxav_ngc_audio_decode(byte[] encoded_frame_bytes, int encoded_frame_size_bytes, byte[] pcm_decoded);
    // --------------- new Groups -------------
    // --------------- new Groups -------------
    // --------------- new Groups -------------


    // --------------- AV - Conference --------
    // --------------- AV - Conference --------
    // --------------- AV - Conference --------
    public static native long toxav_join_av_groupchat(long friend_number, ByteBuffer cookie_buffer, long cookie_length);

    public static native long toxav_add_av_groupchat();

    public static native long toxav_groupchat_enable_av(long conference_number);

    public static native long toxav_groupchat_disable_av(long conference_number);

    public static native int toxav_groupchat_av_enabled(long conference_number);

    public static native int toxav_group_send_audio(long groupnumber, long sample_count, int channels, long sampling_rate);

    // --------------- AV - Conference --------
    // --------------- AV - Conference --------
    // --------------- AV - Conference --------

    // --------------- AV -------------
    // --------------- AV -------------
    // --------------- AV -------------
    public static native int toxav_answer(long friendnum, long audio_bit_rate, long video_bit_rate);

    public static native long toxav_iteration_interval();

    public static native int toxav_call(long friendnum, long audio_bit_rate, long video_bit_rate);

    public static native int toxav_bit_rate_set(long friendnum, long audio_bit_rate, long video_bit_rate);

    public static native int toxav_call_control(long friendnum, int a_TOXAV_CALL_CONTROL);

    public static native int toxav_video_send_frame_uv_reversed(long friendnum, int frame_width_px, int frame_height_px);

    public static native int toxav_video_send_frame(long friendnum, int frame_width_px, int frame_height_px);

    public static native int toxav_video_send_frame_h264(long friendnum, int frame_width_px, int frame_height_px, long data_len);

    public static native int toxav_video_send_frame_h264_age(long friendnum, int frame_width_px, int frame_height_px, long data_len, int age_ms);

    public static native int toxav_option_set(long friendnum, long a_TOXAV_OPTIONS_OPTION, long value);

    public static native void set_av_call_status(int status);

    public static native void set_audio_play_volume_percent(int volume_percent);

    // ----------- TRIfA internal -----------
    // buffer is for incoming video (call)
    public static native long set_JNI_video_buffer(ByteBuffer buffer, int frame_width_px, int frame_height_px);

    // buffer2 is for sending video (call)
    public static native void set_JNI_video_buffer2(ByteBuffer buffer2, int frame_width_px, int frame_height_px);

    // audio_buffer is for sending audio (group and call)
    public static native void set_JNI_audio_buffer(ByteBuffer audio_buffer);

    // audio_buffer2 is for incoming audio (group and call)
    public static native void set_JNI_audio_buffer2(ByteBuffer audio_buffer2);
    // ----------- TRIfA internal -----------

    /**
     * Send an audio frame to a friend.
     * <p>
     * The expected format of the PCM data is: [s1c1][s1c2][...][s2c1][s2c2][...]...
     * Meaning: sample 1 for channel 1, sample 1 for channel 2, ...
     * For mono audio, this has no meaning, every sample is subsequent. For stereo,
     * this means the expected format is LRLRLR... with samples for left and right
     * alternating.
     *
     * @param friend_number The friend number of the friend to which to send an
     *                      audio frame.
     * @param sample_count  Number of samples in this frame. Valid numbers here are
     *                      ((sample rate) * (audio length) / 1000), where audio length can be
     *                      2.5, 5, 10, 20, 40 or 60 millseconds.
     * @param channels      Number of audio channels. Supported values are 1 and 2.
     * @param sampling_rate Audio sampling rate used in this frame. Valid sampling
     *                      rates are 8000, 12000, 16000, 24000, or 48000.
     */
    public static native int toxav_audio_send_frame(long friend_number, long sample_count, int channels, long sampling_rate);
    // --------------- AV -------------
    // --------------- AV -------------
    // --------------- AV -------------

    // -------- native methods --------
    // -------- native methods --------
    // -------- native methods --------

    // -------- called by AV native methods --------
    // -------- called by AV native methods --------
    // -------- called by AV native methods --------

    static void launch_outgoing_calling_activity(Activity fromActivity, Intent intent)
    {
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        boolean activityLaunched = false;

        if (fromActivity != null && !fromActivity.isFinishing())
        {
            try
            {
                fromActivity.startActivity(intent);
                activityLaunched = true;
            }
            catch (Exception e)
            {
                Log.w(TAG, "CALL:outgoing launch failed: " + e.getMessage());
            }
        }

        if (!activityLaunched)
        {
            try
            {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context_s.startActivity(intent);
            }
            catch (Exception e)
            {
                Log.w(TAG, "CALL:outgoing fallback launch failed: " + e.getMessage());
            }
        }
    }

    static void launch_incoming_call_activity(boolean videoCall)
    {
        try
        {
            TrifaToxService.wakeup_tox_thread();
        }
        catch (Exception ignored)
        {
        }

        Intent intent = new Intent(context_s, CallingActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // main_activity_s stays non-null in onPause — Background Activity Launch blocks startActivity
        // when the app is only minimized. Use full-screen call notification instead (like iOS CallKit).
        final boolean inForeground = main_activity_resumed && main_activity_s != null && !main_activity_s.isFinishing();
        boolean activityLaunched = false;

        if (inForeground)
        {
            try
            {
                main_activity_s.startActivity(intent);
                activityLaunched = true;
            }
            catch (Exception e)
            {
                Log.w(TAG, "CALL:foreground launch failed: " + e.getMessage());
            }
        }

        if (!activityLaunched)
        {
            try
            {
                context_s.startActivity(intent);
                activityLaunched = true;
            }
            catch (Exception e)
            {
                Log.w(TAG, "CALL:background launch blocked: " + e.getMessage());
            }
        }

        if (!inForeground || !activityLaunched)
        {
            org.khandaq.messenger.HelperCallNotification.show(context_s, Callstate.friend_alias_name, videoCall);
        }
    }

    static void android_toxav_callback_call_cb_method(long friend_number, int audio_enabled, int video_enabled)
    {
        if (Callstate.state != 0)
        {
            // don't accept a new call if we already are in a call
            return;
        }

        HelperGeneric.logI(TAG, "toxav_call:from=" + friend_number + " audio=" + audio_enabled + " video=" + video_enabled);
        final long fn = friend_number;
        final int f_audio_enabled = audio_enabled;
        final int f_video_enabled = video_enabled;
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (Callstate.state == 0)
                    {
                        HelperGeneric.logI(TAG, "CALL:start:show activity");

                        if (PREF__use_software_aec || f_video_enabled != 0)
                        {
                            set_aec_active(1);
                        }
                        else
                        {
                            set_aec_active(0);
                        }
                        set_gainprocessing_active(1);

                        if (f_video_enabled == 0)
                        {
                            Callstate.audio_call = true;
                            set_debug_text("_AUDIO_");

                            HelperGeneric.logI(TAG, "toxav_call:Callstate.audio_call = true");
                        }
                        else
                        {
                            Callstate.audio_call = false;
                            set_debug_text("VIDEO");

                            HelperGeneric.logI(TAG, "toxav_call:Callstate.audio_call = false");
                        }

                        Callstate.state = 1;
                        Callstate.incoming_one_on_one_call = true;
                        Callstate.accepted_call = 0;
                        Callstate.call_first_video_frame_received = -1;
                        Callstate.call_first_audio_frame_received = -1;
                        Callstate.call_start_timestamp = -1;
                        Callstate.audio_speaker = (f_video_enabled != 0);
                        Callstate.other_audio_enabled = 1;
                        Callstate.other_video_enabled = 1;
                        Callstate.my_audio_enabled = 1;
                        Callstate.my_video_enabled = 1;
                        VIDEO_FRAME_RATE_OUTGOING = 0;
                        last_video_frame_sent = -1;
                        count_video_frame_received = 0;
                        count_video_frame_sent = 0;
                        VIDEO_FRAME_RATE_INCOMING = 0;
                        last_video_frame_received = -1;
                        MainActivity.set_av_call_status(Callstate.state);
                        HelperGeneric.clear_audio_play_buffers();
                        try
                        {
                            set_audio_aec_delay(PREF__X_eac_delay_ms);
                        }
                        catch (Exception e)
                        {
                        }
                        HelperGeneric.logI(TAG, "friend_pubkey:set:003");
                        Callstate.friend_pubkey = HelperFriend.tox_friend_get_public_key__wrapper(fn);
                        Callstate.friend_alias_name = get_friend_name_from_pubkey(Callstate.friend_pubkey);
                        Callstate.other_audio_enabled = f_audio_enabled;
                        Callstate.other_video_enabled = f_video_enabled;
                        Callstate.call_init_timestamp = System.currentTimeMillis();
                        HelperCall.logCallEvent(Callstate.friend_pubkey,
                                f_video_enabled == 0 ? R.string.call_log_incoming_voice
                                                     : R.string.call_log_incoming_video);
                        launch_incoming_call_activity(f_video_enabled != 0);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "CALL:start:EE:" + e.getMessage());
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
        else
        {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(myRunnable);
        }
    }

    static void android_toxav_callback_video_receive_frame_pts_cb_method(long friend_number, long frame_width_px, long frame_height_px, long ystride, long ustride, long vstride, long pts)
    {
/*
        if (pts != 0)
        {
            long ts1 = System.currentTimeMillis();
            global_last_video_ts_no_correction = ts1;
            int pts_delta = (int) (pts - global_last_video_pts);
            int local_delta = (int) (ts1 - global_last_video_ts);
            int abs1 = Math.abs((int) (ts1 - global_last_video_ts));
            int abs2 = Math.abs((int) (pts - global_last_video_pts));
            int local_delta_correction = pts_delta - local_delta;
            int local_delta_correction_abs = Math.abs(local_delta - pts_delta);

            HelperGeneric.logI(TAG, "V:pts_delta=" + pts_delta + " local_delta=" + local_delta + " local_delta_correction=" +
                       local_delta_correction + " local_delta_correction_abs=" + local_delta_correction_abs);

            HelperGeneric.logI(TAG, "V:pts=" + pts);

            global_last_video_pts = pts;
            global_last_video_ts = ts1;
        }
*/
        android_toxav_callback_video_receive_frame_cb_method(friend_number, frame_width_px, frame_height_px, ystride,
                                                             ustride, vstride);
    }

    static void android_toxav_callback_video_receive_frame_cb_method(long friend_number, long frame_width_px, long frame_height_px, long ystride, long ustride, long vstride)
    {
        final long incoming_video_frame_ts = System.currentTimeMillis();

        if (Callstate.other_video_enabled == 0)
        {
            return;
        }

        if (Callstate.audio_call)
        {
            return;
        }

        if (tox_friend_by_public_key__wrapper(Callstate.friend_pubkey) != friend_number)
        {
            // not the friend we are in call with now
            return;
        }

        // HelperGeneric.logI(TAG,
        //      "---> VIDEO_FRAME_RATE_INCOMING w=" + frame_width_px + " h=" + frame_height_px + " ystride=" + ystride);

        //        HelperGeneric.logI(TAG,
        //              "toxav_video_receive_frame:from=" + friend_number + " video width=" + frame_width_px + " video height=" +
        //              frame_height_px + " call_first_video_frame_received=" + Callstate.call_first_video_frame_received);

        if ((Callstate.call_first_video_frame_received == -1) || (Callstate.frame_width_px != frame_width_px) ||
            (Callstate.frame_height_px != frame_height_px) || (Callstate.ystride != ystride) ||
            (Callstate.ustride != ustride) || (Callstate.vstride != vstride))
        {
            //            HelperGeneric.logI(TAG, "toxav_video_receive_frame:from=" + friend_number + " video width=" + frame_width_px +
            //                       " video height=" + frame_height_px);
            Callstate.call_first_video_frame_received = System.currentTimeMillis();
            last_video_frame_received = System.currentTimeMillis();
            count_video_frame_received++;
            // allocate new video buffer on 1 frame
            allocate_video_buffer_1((int) frame_width_px, (int) frame_height_px, ystride, ustride, vstride);
            temp_string_a =
                    "" + (int) ((Callstate.call_first_video_frame_received - Callstate.call_start_timestamp) / 1000) +
                    "s";
            CallingActivity.update_top_text_line(temp_string_a, 3);
            Callstate.frame_width_px = frame_width_px;
            Callstate.frame_height_px = frame_height_px;
            Callstate.ystride = ystride;
            Callstate.ustride = ustride;
            Callstate.vstride = vstride;
        }
        else
        {
            if ((count_video_frame_received > 20) || ((last_video_frame_sent + 2000) < System.currentTimeMillis()))
            {
                VIDEO_FRAME_RATE_INCOMING = (int) ((((float) count_video_frame_received / ((float) (
                        (System.currentTimeMillis() - last_video_frame_received) / 1000.0f))) / 1.0f) + 0.5);
                // HelperGeneric.logI(TAG, "VIDEO_FRAME_RATE_INCOMING=" + VIDEO_FRAME_RATE_INCOMING + " fps");
                HelperGeneric.update_fps();
                last_video_frame_received = System.currentTimeMillis();
                count_video_frame_received = -1;
            }

            count_video_frame_received++;
        }

        try
        {
            try
            {
                //Log.i("semaphore_01","acquire:05");
                semaphore_videoout_bitmap.acquire();
                //Log.i("semaphore_01","acquire:05:OK");
            }
            catch (InterruptedException e)
            {
                //Log.i("semaphore_01","release:05");
                semaphore_videoout_bitmap.release();
                //Log.i("semaphore_01","release:05:OK");
                return;
            }

            if ((video_frame_image_valid == true) && (video_frame_image != null))
            {
                if (!video_frame_image.isRecycled())
                {
                    alloc_in.copyFrom(video_buffer_1.array());
                    yuvToRgb.setInput(alloc_in);
                    yuvToRgb.forEach(alloc_out);
                    alloc_out.copyTo(video_frame_image);
                }
            }

            //Log.i("semaphore_01","release:06");
            semaphore_videoout_bitmap.release();
            //Log.i("semaphore_01","release:06:OK");
        }
        catch (Exception e)
        {
            e.printStackTrace();

            try
            {
                //Log.i("semaphore_01","release:07");
                semaphore_videoout_bitmap.release();
                //Log.i("semaphore_01","release:07:OK");
            }
            catch (Exception e2)
            {
            }
        }

        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    try
                    {
                        //Log.i("semaphore_01","acquire:08");
                        semaphore_videoout_bitmap.acquire();
                        //Log.i("semaphore_01","acquire:08:OK");
                    }
                    catch (InterruptedException e)
                    {
                        //Log.i("semaphore_01","release:08");
                        semaphore_videoout_bitmap.release();
                        //Log.i("semaphore_01","release:08:OK");
                        return;
                    }

                    if (video_frame_image_valid == true && CallingActivity.mContentView != null)
                    {
                        CallingActivity.mContentView.setBitmap(video_frame_image);
                        Callstate.java_video_play_delay = System.currentTimeMillis() - incoming_video_frame_ts;
                    }

                    //Log.i("semaphore_01","release:09");
                    semaphore_videoout_bitmap.release();
                    //Log.i("semaphore_01","release:09:OK");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    //Log.i("semaphore_01","release:10");
                    semaphore_videoout_bitmap.release();
                    //Log.i("semaphore_01","release:10:OK");
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    static void android_toxav_callback_video_receive_frame_h264_cb_method(long friend_number, long buf_size)
    {
        // HINT: Disabled. this is now handled by c-toxcore. how nice.
    }

    static void android_toxav_callback_call_state_cb_method(long friend_number, int a_TOXAV_FRIEND_CALL_STATE)
    {
        HelperCall.ensureCallFriendPubkey(friend_number);

        if (!HelperCall.isCallFriendNumber(friend_number))
        {
            return;
        }

        HelperGeneric.logI(TAG, "toxav_call_state:INCOMING_CALL:from=" + friend_number + " state=" + a_TOXAV_FRIEND_CALL_STATE);
        HelperGeneric.logI(TAG, "Callstate.tox_call_state:INCOMING_CALL=" + a_TOXAV_FRIEND_CALL_STATE + " old=" +
                   Callstate.tox_call_state);

        if (Callstate.state == 1)
        {
            int old_value = Callstate.tox_call_state;
            Callstate.tox_call_state = a_TOXAV_FRIEND_CALL_STATE;

            if ((a_TOXAV_FRIEND_CALL_STATE &
                 (TOXAV_FRIEND_CALL_STATE_SENDING_A.value + TOXAV_FRIEND_CALL_STATE_SENDING_V.value +
                  TOXAV_FRIEND_CALL_STATE_ACCEPTING_A.value + TOXAV_FRIEND_CALL_STATE_ACCEPTING_V.value)) > 0)
            {
                HelperGeneric.logI(TAG, "toxav_call_state:from=" + friend_number + " call starting");
                Callstate.call_start_timestamp = System.currentTimeMillis();
                Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            if (CallingActivity.accept_button != null)
                            {
                                CallingActivity.accept_button.setVisibility(View.GONE);
                            }
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        try
                        {
                            if (CallingActivity.caller_avatar_view != null)
                            {
                                CallingActivity.caller_avatar_view.setVisibility(View.GONE);
                            }
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        if (Callstate.audio_call)
                        {
                            toggle_osd_view_including_cam_preview(!Callstate.audio_call);
                        }
                    }
                };
                HelperCall.postToCallActivity(myRunnable);
                HelperGeneric.logI(TAG, "on_call_started_actions:02");
                on_call_started_actions();

                if (Callstate.audio_call)
                {
                    toggle_osd_view_including_cam_preview(!Callstate.audio_call);
                }
            }
            else if ((a_TOXAV_FRIEND_CALL_STATE & (TOXAV_FRIEND_CALL_STATE_FINISHED.value)) > 0)
            {
                HelperGeneric.logI(TAG, "toxav_call_state:from=" + friend_number + " call ending(1)");
                org.khandaq.messenger.HelperCallNotification.cancel(context_s);
                CallAudioService.stop_me(false);
            }
            else if ((old_value > TOXAV_FRIEND_CALL_STATE_NONE.value) &&
                     (a_TOXAV_FRIEND_CALL_STATE == TOXAV_FRIEND_CALL_STATE_NONE.value))
            {
                HelperGeneric.logI(TAG, "toxav_call_state:from=" + friend_number + " call ending(2)");
                org.khandaq.messenger.HelperCallNotification.cancel(context_s);
                CallAudioService.stop_me(false);
            }
            else if ((a_TOXAV_FRIEND_CALL_STATE & (TOXAV_FRIEND_CALL_STATE_ERROR.value)) > 0)
            {
                HelperGeneric.logI(TAG, "toxav_call_state:from=" + friend_number + " call ERROR(3)");
                CallAudioService.stop_me(false);
            }
        }
    }

    static void android_toxav_callback_bit_rate_status_cb_method(long friend_number, long audio_bit_rate, long video_bit_rate)
    {
        if (tox_friend_by_public_key__wrapper(Callstate.friend_pubkey) != friend_number)
        {
            // not the friend we are in call with now
            return;
        }

        // HelperGeneric.logI(TAG,
        //       "toxav_bit_rate_status:from=" + friend_number + " audio_bit_rate=" + audio_bit_rate + " video_bit_rate=" +
        //      video_bit_rate);

        // TODO: suggested bitrates!!!! ---------------
        if (Callstate.state == 1)
        {
            final long friend_number_ = friend_number;
            long audio_bit_rate2 = audio_bit_rate;
            long video_bit_rate2 = video_bit_rate;

            if (audio_bit_rate2 < GLOBAL_MIN_AUDIO_BITRATE)
            {
                audio_bit_rate2 = GLOBAL_MIN_AUDIO_BITRATE;
            }

            if (video_bit_rate2 < GLOBAL_MIN_VIDEO_BITRATE)
            {
                video_bit_rate2 = GLOBAL_MIN_VIDEO_BITRATE;
            }

            final long audio_bit_rate_ = audio_bit_rate2;
            final long video_bit_rate_ = video_bit_rate2;
            Runnable myRunnable = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        // Apply toxcore suggested bitrate with quality/network bounds
                        HelperGeneric.adaptVideoBitrateFromSuggestion(video_bit_rate_);
                        HelperGeneric.update_bitrates();
                        HelperGeneric.logI(TAG, "toxav_bit_rate_status:CALL:toxav_bit_rate_set");
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        HelperGeneric.logI(TAG, "toxav_bit_rate_status:CALL:EE:" + e.getMessage());
                    }
                }
            };

            if (main_handler_s != null)
            {
                main_handler_s.post(myRunnable);
            }
        }

        // TODO: suggested bitrates!!!! ---------------
    }

    static void android_toxav_callback_call_comm_cb_method(long friend_number, long a_TOXAV_CALL_COMM_INFO, long comm_number)
    {
        // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:" + a_TOXAV_CALL_COMM_INFO + ":" + comm_number);
        if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_DECODER_IN_USE_VP8.value)
        {
            // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:3:" + a_TOXAV_CALL_COMM_INFO + ":" + comm_number);
            Callstate.video_in_codec = VIDEO_CODEC_VP8;
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_DECODER_IN_USE_H264.value)
        {
            // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:4:" + a_TOXAV_CALL_COMM_INFO + ":" + comm_number);
            Callstate.video_in_codec = VIDEO_CODEC_H264;
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_DECODER_IN_USE_H265.value)
        {
            // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:4:" + a_TOXAV_CALL_COMM_INFO + ":" + comm_number);
            Callstate.video_in_codec = VIDEO_CODEC_H265;
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_ENCODER_IN_USE_VP8.value)
        {
            Callstate.video_out_codec = VIDEO_CODEC_VP8;
            // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:1:" + a_TOXAV_CALL_COMM_INFO + ":" + comm_number);
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_ENCODER_IN_USE_H264.value)
        {
            Callstate.video_out_codec = VIDEO_CODEC_H264;
            // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:2:" + a_TOXAV_CALL_COMM_INFO + ":" + comm_number);
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_DECODER_CURRENT_BITRATE.value)
        {
            Callstate.video_in_bitrate = comm_number;
            // HelperGeneric.logI(TAG,
            //      "android_toxav_callback_call_comm_cb_method:TOXAV_CALL_COMM_DECODER_CURRENT_BITRATE:" + comm_number);
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_ENCODER_CURRENT_BITRATE.value)
        {
            Callstate.video_bitrate = comm_number;
            // HelperGeneric.logI(TAG,
            //      "android_toxav_callback_call_comm_cb_method:TOXAV_CALL_COMM_ENCODER_CURRENT_BITRATE:" + comm_number);
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_PLAY_BUFFER_ENTRIES.value)
        {
            if (comm_number < 0)
            {
                Callstate.play_buffer_entries = 0;
            }
            else if (comm_number > 9900)
            {
                Callstate.play_buffer_entries = 99;
            }
            else
            {
                Callstate.play_buffer_entries = (int) comm_number;
                // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:play_buffer_entries=:" + comm_number);
            }
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_NETWORK_ROUND_TRIP_MS.value)
        {
            if (comm_number < 0)
            {
                Callstate.round_trip_time = 0;
            }
            else if (comm_number > 9900)
            {
                Callstate.round_trip_time = 9900;
            }
            else
            {
                Callstate.round_trip_time = comm_number;
                // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:round_trip_time=:" + Callstate.round_trip_time);
            }
        }
        else if (a_TOXAV_CALL_COMM_INFO == TOXAV_CALL_COMM_PLAY_DELAY.value)
        {
            if (comm_number < 0)
            {
                Callstate.play_delay = 0;
            }
            else if (comm_number > 9900)
            {
                Callstate.play_delay = 9900;
            }
            else
            {
                Callstate.play_delay = comm_number;
                // HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:play_delay=:" + Callstate.play_delay);
            }
        }

        try
        {
            HelperGeneric.update_bitrates();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "android_toxav_callback_call_comm_cb_method:EE:" + e.getMessage());
        }
    }

    static long global_last_audio_ts_no_correction = 0;
    static long global_last_audio_pts = 0;
    static long global_last_audio_ts = 0;

    static long global_last_video_ts_no_correction = 0;
    static long global_last_video_pts = 0;
    static long global_last_video_ts = 0;

    static void android_toxav_callback_audio_receive_frame_pts_cb_method(long friend_number, long sample_count, int channels, long sampling_rate, long pts)
    {
        /*
        if (pts != 0)
        {
            long ts1 = System.currentTimeMillis();
            global_last_audio_ts_no_correction = ts1;
            int pts_delta = (int) (pts - global_last_audio_pts);
            int local_delta = (int) (ts1 - global_last_audio_ts);
            int abs1 = Math.abs((int) (ts1 - global_last_audio_ts));
            int abs2 = Math.abs((int) (pts - global_last_audio_pts));
            int local_delta_correction = pts_delta - local_delta;
            int local_delta_correction_abs = Math.abs(local_delta - pts_delta);

            int audio_pkt_to_video_pkt_delta = (int) (global_last_audio_ts_no_correction -
                                                      global_last_video_ts_no_correction);
            // audio_to_video_out_of_sync < 0  ... audio is too late
            // audio_to_video_out_of_sync == 0 ... audio in sync with video
            // audio_to_video_out_of_sync > 0  ... audio is too early
            int audio_to_video_out_of_sync = (int) (pts - global_last_video_pts) - audio_pkt_to_video_pkt_delta;

            HelperGeneric.logI(TAG, "pts_delta=" + pts_delta + " local_delta=" + local_delta + " local_delta_correction=" +
                       local_delta_correction + " local_delta_correction_abs=" + local_delta_correction_abs);
            HelperGeneric.logI(TAG, "audio_pkt_to_video_pkt_delta=" + audio_pkt_to_video_pkt_delta);

            HelperGeneric.logI(TAG, "A:pts=" + pts);

            global_last_audio_pts = pts;
            global_last_audio_ts = ts1;
        }
        */

        android_toxav_callback_audio_receive_frame_cb_method(friend_number, sample_count, channels, sampling_rate);
    }

    static void android_toxav_callback_audio_receive_frame_cb_method(long friend_number, long sample_count, int channels, long sampling_rate)
    {
        // HelperGeneric.logI(TAG,
        //      "audio_play:android_toxav_callback_audio_receive_frame_cb_method:" + friend_number + " " + sample_count +
        //      " " + channels + " " + sampling_rate);

        if (tox_friend_by_public_key__wrapper(Callstate.friend_pubkey) != friend_number)
        {
            // not the friend we are in call with now
            return;
        }

        if (Callstate.other_audio_enabled == 0)
        {
            if (Callstate.call_first_audio_frame_received == -1)
            {
                sampling_rate_ = sampling_rate;
                HelperGeneric.logI(TAG, "audio_play:read:incoming sampling_rate[0]=" + sampling_rate + " kHz");
                channels_ = channels;
            }

            return;
        }

        if (Callstate.call_first_audio_frame_received == -1)
        {
            Callstate.call_first_audio_frame_received = System.currentTimeMillis();
            sampling_rate_ = sampling_rate;
            HelperGeneric.logI(TAG, "audio_play:read:incoming sampling_rate[1]=" + sampling_rate + " Hz");
            channels_ = channels;
            HelperGeneric.logI(TAG,
                  "audio_play:read:init sample_count=" + sample_count + " channels=" + channels + " sampling_rate=" +
                  sampling_rate);
            temp_string_a =
                    "" + (int) ((Callstate.call_first_audio_frame_received - Callstate.call_start_timestamp) / 1000) +
                    "s";
            CallingActivity.update_top_text_line(temp_string_a, 4);
            // HINT: PCM_16 needs 2 bytes per sample per channel
            AudioReceiver.buffer_size = ((int) ((48000 * 2) * 2)) * audio_out_buffer_mult;  // TODO: this is really bad
            AudioReceiver.sleep_millis = (int) (((float) sample_count / (float) sampling_rate) * 1000.0f *
                                                0.9f); // TODO: this is bad also
            HelperGeneric.logI(TAG, "audio_play:read:init buffer_size=" + AudioReceiver.buffer_size);
            HelperGeneric.logI(TAG, "audio_play:read:init sleep_millis=" + AudioReceiver.sleep_millis);

            // reset audio in buffers
            try
            {
                if (audio_buffer_2 != null)
                {
                    audio_buffer_2.clear();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }


            if (audio_buffer_2 == null)
            {
                audio_buffer_2 = ByteBuffer.allocateDirect(AudioReceiver.buffer_size);
                HelperGeneric.logI(TAG, "audio_play:audio_buffer_2[" + 0 + "] size=" + AudioReceiver.buffer_size);

                // audio_buffer_play = ByteBuffer.allocateDirect(AudioReceiver.buffer_size);
                // always write to buffer[0] in the pipeline !! -----------
                set_JNI_audio_buffer2(audio_buffer_2);
            }
            audio_buffer_2_read_length[0] = 0;

            int frame_size_ = (int) ((sample_count * 1000) / sampling_rate);

            // always write to buffer[0] in the pipeline !! -----------
            HelperGeneric.logI(TAG, "audio_play:audio_buffer_play size=" + AudioReceiver.buffer_size);
        }

        // HelperGeneric.logI(TAG, "audio_play:NativeAudio Play:001a:" + NativeAudio.channel_count + " " + channels_);

        debug__audio_pkt_incoming++;

        if (sampling_rate_ != sampling_rate)
        {
            sampling_rate_ = sampling_rate;
        }

        if (channels_ != channels)
        {
            channels_ = channels;
        }

        // HelperGeneric.logI(TAG, "audio_play:NativeAudio Play:001b:" + NativeAudio.channel_count + " " + channels_);

        if (sample_count == 0)
        {
            return;
        }

        // TODO: dirty hack, "make good"
        try
        {
            if (PREF__use_native_audio_play)
            {
                if (audio_engine_starting)
                {
                    // native audio engine is down. lets wait for it to get up ...
                    while (audio_engine_starting == true)
                    {
                        try
                        {
                            Thread.sleep(20);
                            HelperGeneric.logI(TAG, "audio_play:sleep --------");
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }

                // HelperGeneric.logI(TAG, "audio_play:NativeAudio Play:001c:" + NativeAudio.channel_count + " " + channels_);
                if ((NativeAudio.sampling_rate != (int) sampling_rate_) || (NativeAudio.channel_count != channels_))
                {
                    HelperGeneric.logI(TAG, "audio_play:values_changed");
                    NativeAudio.sampling_rate = (int) sampling_rate_;
                    NativeAudio.channel_count = channels_;
                    HelperGeneric.logI(TAG, "audio_play:NativeAudio restart Engine");
                    // TODO: locking? or something like that
                    NativeAudio.restartNativeAudioPlayEngine((int) sampling_rate_, channels_);
                }

                audio_buffer_2.position(0);
                int incoming_bytes = (int) ((sample_count * channels) * 2);

                if (NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] < NativeAudio.n_buf_size_in_bytes)
                {
                    int remain_bytes = incoming_bytes - (NativeAudio.n_buf_size_in_bytes -
                                                         NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf]);
                    int remain_start_pos = (incoming_bytes - remain_bytes);
                    NativeAudio.n_audio_buffer[NativeAudio.n_cur_buf].position(
                            NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf]);
                    NativeAudio.n_audio_buffer[NativeAudio.n_cur_buf].put(audio_buffer_2.array(),
                                                                          audio_buffer_2.arrayOffset(),
                                                                          Math.min(incoming_bytes,
                                                                                   NativeAudio.n_buf_size_in_bytes -
                                                                                   NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf]));

                    //HelperGeneric.logI(TAG,
                    //      "audio_play:play_buffers:003:n_audio_in_buffer_max_count=" + n_audio_in_buffer_max_count +
                    //      " n_cur_buf=" + NativeAudio.n_cur_buf + " n_bytes_in_buffer=" +
                    //      NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf]);

                    if (remain_bytes > 0)
                    {
                        //audio_buffer_2.position(remain_start_pos);
                        debug__audio_frame_played++;
                        audio_buffer_2.position(0);
                        int res = NativeAudio.PlayPCM16(NativeAudio.n_cur_buf);

                        NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] = 0;

                        if (NativeAudio.n_cur_buf + 1 >= n_audio_in_buffer_max_count)
                        {
                            NativeAudio.n_cur_buf = 0;
                        }
                        else
                        {
                            NativeAudio.n_cur_buf++;
                        }

                        NativeAudio.n_audio_buffer[NativeAudio.n_cur_buf].position(0);
                        NativeAudio.n_audio_buffer[NativeAudio.n_cur_buf].put(audio_buffer_2.array(), remain_start_pos,
                                                                              remain_bytes);
                        NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] = remain_bytes;
                    }
                    else if (remain_bytes == 0)
                    {
                        debug__audio_frame_played++;
                        NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] = 0;
                        int res = NativeAudio.PlayPCM16(NativeAudio.n_cur_buf);

                        if (NativeAudio.n_cur_buf + 1 >= n_audio_in_buffer_max_count)
                        {
                            NativeAudio.n_cur_buf = 0;
                        }
                        else
                        {
                            NativeAudio.n_cur_buf++;
                        }
                    }
                    else
                    {
                        NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] =
                                NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] + incoming_bytes;
                    }
                }
                else
                {
                    NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] = 0;

                    if (NativeAudio.n_cur_buf + 1 >= n_audio_in_buffer_max_count)
                    {
                        NativeAudio.n_cur_buf = 0;
                    }
                    else
                    {
                        NativeAudio.n_cur_buf++;
                    }
                }
            } // PREF__use_native_audio_play -----
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "audio_play:EE3:" + e.getMessage());
        }
    }

    static void android_toxav_callback_group_audio_receive_frame_cb_method(long conference_number, long peer_number, long sample_count, int channels, long sampling_rate)
    {
        if (!Callstate.audio_ngc_group_active)
        {
            return;
        }

        if (tox_conference_by_confid__wrapper(conf_id) != conference_number)
        {
            // not the group we are in group audio call with now
            return;
        }

        if (Callstate.call_first_audio_frame_received == -1)
        {
            Callstate.call_first_audio_frame_received = System.currentTimeMillis();
            sampling_rate_ = sampling_rate;
            HelperGeneric.logI(TAG, "group_audio_receive_frame:read:incoming sampling_rate[1]=" + sampling_rate + " Hz");
            channels_ = channels;
            HelperGeneric.logI(TAG, "group_audio_receive_frame:read:init sample_count=" + sample_count + " channels=" + channels +
                       " sampling_rate=" + sampling_rate);
            temp_string_a =
                    "" + (int) ((Callstate.call_first_audio_frame_received - Callstate.call_start_timestamp) / 1000) +
                    "s";
            // HINT: PCM_16 needs 2 bytes per sample per channel
            AudioReceiver.buffer_size = ((int) ((48000 * 2) * 2)) * audio_out_buffer_mult; // TODO: this is really bad
            AudioReceiver.sleep_millis = (int) (((float) sample_count / (float) sampling_rate) * 1000.0f *
                                                0.9f); // TODO: this is bad also
            HelperGeneric.logI(TAG, "group_audio_receive_frame:read:init buffer_size=" + AudioReceiver.buffer_size);
            HelperGeneric.logI(TAG, "group_audio_receive_frame:read:init sleep_millis=" + AudioReceiver.sleep_millis);
            // reset audio in buffers
            try
            {
                if (audio_buffer_2 != null)
                {
                    audio_buffer_2.clear();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            if (audio_buffer_2 == null)
            {
                audio_buffer_2 = ByteBuffer.allocateDirect(AudioReceiver.buffer_size);
                HelperGeneric.logI(TAG, "group_audio_receive_frame:audio_buffer_2[" + 0 + "] size=" + AudioReceiver.buffer_size);

                // audio_buffer_play = ByteBuffer.allocateDirect(AudioReceiver.buffer_size);
                // always write to buffer[0] in the pipeline !! -----------
                set_JNI_audio_buffer2(audio_buffer_2);
            }
            audio_buffer_2_read_length[0] = 0;

            int frame_size_ = (int) ((sample_count * 1000) / sampling_rate);

            // always write to buffer[0] in the pipeline !! -----------
            HelperGeneric.logI(TAG, "group_audio_receive_frame:audio_buffer_play size=" + AudioReceiver.buffer_size);
        }

        if (sampling_rate_ != sampling_rate)
        {
            sampling_rate_ = sampling_rate;
        }

        if (channels_ != channels)
        {
            channels_ = channels;
        }

        if (sample_count == 0)
        {
            return;
        }

        // TODO: dirty hack, "make good"
        try
        {
            if (PREF__use_native_audio_play)
            {
                if (audio_engine_starting)
                {
                    // native audio engine is down. lets wait for it to get up ...
                    while (audio_engine_starting == true)
                    {
                        try
                        {
                            Thread.sleep(20);
                            HelperGeneric.logI(TAG, "group_audio_receive_frame:sleep --------");
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                }

                // HelperGeneric.logI(TAG, "audio_play:NativeAudio Play:001");
                if ((NativeAudio.sampling_rate != (int) sampling_rate_) || (NativeAudio.channel_count != channels_))
                {
                    HelperGeneric.logI(TAG, "group_audio_receive_frame:values_changed");
                    NativeAudio.sampling_rate = (int) sampling_rate_;
                    NativeAudio.channel_count = channels_;
                    HelperGeneric.logI(TAG, "group_audio_receive_frame:NativeAudio restart Engine");
                    // TODO: locking? or something like that
                    NativeAudio.restartNativeAudioPlayEngine((int) sampling_rate_, channels_);
                }

                audio_buffer_2.position(0);
                int incoming_bytes = (int) ((sample_count * channels) * 2);

                if (NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] < NativeAudio.n_buf_size_in_bytes)
                {
                    int remain_bytes = incoming_bytes - (NativeAudio.n_buf_size_in_bytes -
                                                         NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf]);
                    int remain_start_pos = (incoming_bytes - remain_bytes);
                    NativeAudio.n_audio_buffer[NativeAudio.n_cur_buf].position(
                            NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf]);
                    NativeAudio.n_audio_buffer[NativeAudio.n_cur_buf].put(audio_buffer_2.array(),
                                                                          audio_buffer_2.arrayOffset(),
                                                                          Math.min(incoming_bytes,
                                                                                   NativeAudio.n_buf_size_in_bytes -
                                                                                   NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf]));

                    if (remain_bytes > 0)
                    {
                        //audio_buffer_2.position(remain_start_pos);
                        audio_buffer_2.position(0);
                        int res = NativeAudio.PlayPCM16(NativeAudio.n_cur_buf);
                        NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] = 0;

                        if (NativeAudio.n_cur_buf + 1 >= n_audio_in_buffer_max_count)
                        {
                            NativeAudio.n_cur_buf = 0;
                        }
                        else
                        {
                            NativeAudio.n_cur_buf++;
                        }

                        NativeAudio.n_audio_buffer[NativeAudio.n_cur_buf].position(0);
                        NativeAudio.n_audio_buffer[NativeAudio.n_cur_buf].put(audio_buffer_2.array(), remain_start_pos,
                                                                              remain_bytes);
                        NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] = remain_bytes;
                    }
                    else if (remain_bytes == 0)
                    {
                        NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] = 0;
                        int res = NativeAudio.PlayPCM16(NativeAudio.n_cur_buf);

                        if (NativeAudio.n_cur_buf + 1 >= n_audio_in_buffer_max_count)
                        {
                            NativeAudio.n_cur_buf = 0;
                        }
                        else
                        {
                            NativeAudio.n_cur_buf++;
                        }
                    }
                    else
                    {
                        NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] =
                                NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] + incoming_bytes;
                    }
                }
                else
                {
                    NativeAudio.n_bytes_in_buffer[NativeAudio.n_cur_buf] = 0;

                    if (NativeAudio.n_cur_buf + 1 >= n_audio_in_buffer_max_count)
                    {
                        NativeAudio.n_cur_buf = 0;
                    }
                    else
                    {
                        NativeAudio.n_cur_buf++;
                    }
                }
            } // PREF__use_native_audio_play -----
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "group_audio_receive_frame:EE3:" + e.getMessage());
        }
    }

    // -------- called by AV native methods --------
    // -------- called by AV native methods --------
    // -------- called by AV native methods --------


    // -------- called by native methods --------
    // -------- called by native methods --------
    // -------- called by native methods --------

    static void android_tox_callback_self_connection_status_cb_method(int a_TOX_CONNECTION)
    {
        final int connection_status_prev = global_self_connection_status;
        HelperGeneric.logI(TAG, "self_connection_status:" + a_TOX_CONNECTION);
        global_self_connection_status = a_TOX_CONNECTION;
        TrifaToxService.write_debug_file("CB_SELF_CONN_STATUS__cstatus:" + a_TOX_CONNECTION + "_b:" + bootstrapping);

        if ((connection_status_prev == TOX_CONNECTION_NONE.value) && (a_TOX_CONNECTION != TOX_CONNECTION_NONE.value))
        {
            // we just went online
            append_logger_msg(TAG + "::" + "went online:self connection status=" + a_TOX_CONNECTION);
        }
        else if ((connection_status_prev != TOX_CONNECTION_NONE.value) && (a_TOX_CONNECTION == TOX_CONNECTION_NONE.value))
        {
            // we just went OFFLINE
            append_logger_msg(TAG + "::" + "went OFFLINE:self connection status=" + a_TOX_CONNECTION);
        }

        if (a_TOX_CONNECTION != 0)
        {
            ConnectionQualityMonitor.get().onToxConnected();
        }
        else
        {
            ConnectionQualityMonitor.get().onToxDisconnected();
        }

        if ((connection_status_prev == TOX_CONNECTION_NONE.value) && (a_TOX_CONNECTION != TOX_CONNECTION_NONE.value))
        {
            DeliveryRestoreCoordinator.onConnectionRestored();
        }

        if (bootstrapping)
        {
            HelperGeneric.logI(TAG, "self_connection_status:bootstrapping=true");

            // we just went online
            if (a_TOX_CONNECTION != 0)
            {
                HelperGeneric.logI(TAG, "self_connection_status:bootstrapping set to false");
                bootstrapping = false;
                global_self_last_went_online_timestamp = System.currentTimeMillis();
                global_self_last_went_offline_timestamp = -1;
            }
            else
            {
                global_self_last_went_offline_timestamp = System.currentTimeMillis();
            }
        }
        else
        {
            if (a_TOX_CONNECTION != 0)
            {
                global_self_last_went_online_timestamp = System.currentTimeMillis();
                global_self_last_went_offline_timestamp = -1;

                HelperGeneric.logI(TAG, "self_connection_status:went_online");
                // TODO: stop any active calls
            }
            else
            {
                global_self_last_went_offline_timestamp = System.currentTimeMillis();
            }
        }

        // -- notification ------------------
        // -- notification ------------------
        tox_notification_change_wrapper(a_TOX_CONNECTION, "");
        ProfileTabConnectionIndicator.updateAsync();
        MainHeaderBrandingHelper.updateAsync();
        // -- notification ------------------
        // -- notification ------------------
    }

    static void android_tox_callback_friend_name_cb_method(long friend_number, String friend_name, long length)
    {
        final Runnable work = () -> {
            FriendList f = main_get_friend(friend_number);

            if (f != null)
            {
                HelperFriend.apply_friend_name_from_callback(friend_number, f, friend_name);
                HelperFriend.update_single_friend_in_friendlist_view(f);
                MessageListActivity.notify_friend_profile_updated(friend_number);
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(work);
        }
        else
        {
            work.run();
        }
    }

    static void android_tox_callback_friend_status_message_cb_method(long friend_number, String status_message, long length)
    {
        // HelperGeneric.logI(TAG, "friend_status_message:friend:" + friend_number + " status message:" + status_message);
        FriendList f = main_get_friend(friend_number);

        if (f != null)
        {
            f.status_message = status_message;
            HelperFriend.update_friend_in_db_status_message(f);
            HelperFriend.update_single_friend_in_friendlist_view(f);
        }
    }

    static void android_tox_callback_friend_status_cb_method(long friend_number, int a_TOX_USER_STATUS)
    {
        // HelperGeneric.logI(TAG, "friend_status:friend:" + friend_number + " status:" + a_TOX_USER_STATUS);
        FriendList f = main_get_friend(friend_number);

        if (f != null)
        {
            f.TOX_USER_STATUS = a_TOX_USER_STATUS;
            // HelperGeneric.logI(TAG, "friend_status:2:f.TOX_USER_STATUS=" + f.TOX_USER_STATUS);
            HelperFriend.update_friend_in_db_status(f);

            try
            {
                if (message_list_activity != null)
                {
                    // HelperGeneric.logI(TAG, "friend_status:002");
                    message_list_activity.set_friend_status_icon();
                    // HelperGeneric.logI(TAG, "friend_status:003");
                }
            }
            catch (Exception e)
            {
                // e.printStackTrace();
                HelperGeneric.logI(TAG, "friend_status:EE1:" + e.getMessage());
            }

            HelperFriend.update_single_friend_in_friendlist_view(f);
        }
    }

    static void android_tox_callback_friend_connection_status_cb_method(long friend_number, int a_TOX_CONNECTION)
    {
        FriendList f = main_get_friend(friend_number);
        // HelperGeneric.logI(TAG, "friend_connection_status:pubkey=" + f.tox_public_key_string + " friend:" +
        //           get_friend_name_from_pubkey(f.tox_public_key_string) + " connection status:" + a_TOX_CONNECTION);

        if (f != null)
        {
            ConnectionHealthMonitor.onFriendConnectionChanged(friend_number, a_TOX_CONNECTION);

            if (f.TOX_CONNECTION_real != a_TOX_CONNECTION)
            {
                if (f.TOX_CONNECTION_real == TOX_CONNECTION_NONE.value)
                {
                    // ******** friend just came online ********
                    // update and save this friends TOX CAPABILITIES
                    long friend_capabilities = tox_friend_get_capabilities(friend_number);
                    // HelperGeneric.logI(TAG, "" + get_friend_name_from_num(friend_number) + " friend_capabilities: " + friend_capabilities + " decoded:" + TOX_CAPABILITY_DECODE_TO_STRING(TOX_CAPABILITY_DECODE(friend_capabilities)) + " " + (1L << 63L));
                    f.capabilities = friend_capabilities;
                    update_friend_in_db_capabilities(f);
                    if ((friend_capabilities & TOX_CAPABILITY_MSGV3) != 0)
                    {
                        update_friend_msgv3_capability(friend_number, 1);
                    }
                    HelperFriend.sync_friend_name_from_tox(friend_number, f);
                    // Force-resend pending friend-assisted group joins now AND a couple times over
                    // the next seconds: at this exact instant the lossless channel may not be ready,
                    // so a single attempt can race the transition (sent=0) and stall the join.
                    HelperGroup.schedule_friend_online_invite_resends();
                    // KHANDAQ: also flush any manual "invite to group" that failed earlier because this
                    // friend was momentarily offline (flapping connection) — re-fire now that it is online
                    // (immediate + a couple of delayed retries to ride out the channel-ready race).
                    HelperGroup.schedule_manual_invite_resends(f.tox_public_key_string);
                    MessageDeliveryWatchdog.tick();
                    // KHANDAQ (#9): deliver edits made while this friend was offline (packet is
                    // rebuilt from the CURRENT row text — repeated edits collapse into one).
                    HelperMessageEdit.flushPendingEditsForFriend(friend_number);
                    // KHANDAQ (#179): deliver deletes made while this friend was offline
                    HelperMessageDelete.flushPendingDeletesForFriend(friend_number);
                    // KHANDAQ (#192): deliver reaction changes made while this friend was offline
                    // (packet is rebuilt from the CURRENT own-reaction state — toggles collapse)
                    HelperMessageReaction.flushPendingReactionsForFriend(friend_number);
                }
            }

            if (f.TOX_CONNECTION_real != a_TOX_CONNECTION)
            {
                if (f.TOX_CONNECTION_real == TOX_CONNECTION_NONE.value)
                {
                    // TODO: check when we have actual FTs that are stale
                    // ******** friend just came online ********
                    // check for stale filetransfers
                    try
                    {
                        // HelperGeneric.logI(TAG, "check_for_stale_ft:001:friend=" + f);
                        List<com.zoffcc.applications.sorm.Filetransfer> fts_active = orma.selectFromFiletransfer().file_numberNotEq(-1).kindEq(
                                TOX_FILE_KIND_DATA.value).tox_public_key_stringEq(f.tox_public_key_string).toList();
                        for (com.zoffcc.applications.sorm.Filetransfer ft : fts_active)
                        {
                            // HelperGeneric.logI(TAG, "check_for_stale_ft:002:ft=" + ft);

                            ByteBuffer file_id_buffer = ByteBuffer.allocateDirect(TOX_FILE_ID_LENGTH);
                            tox_file_get_file_id(friend_number, ft.file_number, file_id_buffer);
                            final String file_id_buffer_hex = HelperGeneric.bytesToHex(file_id_buffer.array(),
                                                                                       file_id_buffer.arrayOffset(),
                                                                                       file_id_buffer.limit()).toUpperCase();
                            //HelperGeneric.logI(TAG, "check_for_stale_ft:003:ft_hex_from_db=" + ft.tox_file_id_hex +
                            //           " file_id_buffer_hex=" + file_id_buffer_hex);

                        }
                    }
                    catch (Exception e)
                    {
                        // e.printStackTrace();
                        // HelperGeneric.logI(TAG, "check_for_stale_ft:088:EE:" + e.getMessage());
                    }
                }
            }

            if (f.TOX_CONNECTION_real != a_TOX_CONNECTION)
            {
                if (f.TOX_CONNECTION_real == TOX_CONNECTION_NONE.value)
                {
                    // ******** friend just came online ********
                    if (have_own_relay())
                    {
                        if (!is_any_relay(f.tox_public_key_string))
                        {
                            HelperRelay.send_relay_pubkey_to_friend(HelperRelay.get_own_relay_pubkey(),
                                                                    f.tox_public_key_string);

                            HelperRelay.send_friend_pubkey_to_relay(HelperRelay.get_own_relay_pubkey(),
                                                                    f.tox_public_key_string);
                        }
                        else
                        {
                            if (HelperRelay.is_own_relay(f.tox_public_key_string))
                            {
                                HelperRelay.invite_to_all_conferences_own_relay(HelperRelay.get_own_relay_pubkey());
                                HelperRelay.invite_to_all_groups_own_relay(HelperRelay.get_own_relay_pubkey());
                                HelperRelay.send_all_friend_pubkeys_to_relay(HelperRelay.get_own_relay_pubkey());
                            }
                        }

                        if (HelperRelay.is_own_relay(f.tox_public_key_string))
                        {
                            send_pushtoken_to_relay();
                        }
                    }
                }
            }

            if (f.TOX_CONNECTION_real != a_TOX_CONNECTION)
            {
                if (f.TOX_CONNECTION_real == TOX_CONNECTION_NONE.value)
                {
                    // ******** friend just came online ********
                    // resend latest msgV3 message that was not "read"
                    try
                    {
                        if (get_friend_msgv3_capability(friend_number) == 1)
                        {
                            resend_v3_messages(f.tox_public_key_string);
                        }
                        else
                        {
                            // HelperGeneric.logI(TAG, "friend_connection_status:resend_old_messages" +
                            //            get_friend_name_from_pubkey(f.tox_public_key_string));
                            resend_old_messages(f.tox_public_key_string);
                        }
                        HelperFiletransfer.retry_pending_incoming_files_for_friend(f.tox_public_key_string);
                    }
                    catch (Exception e)
                    {
                    }
                }
            }

            if (a_TOX_CONNECTION != TOX_CONNECTION_NONE.value)
            {
                try
                {
                    final String ip_str = tox_friend_get_connection_ip(tox_friend_by_public_key__wrapper(f.tox_public_key_string));
                    f.ip_addr_str = ip_str.replaceAll("\0", "");
                    update_friend_in_db_ip_addr_str(f);
                }
                catch(Exception e)
                {
                    f.ip_addr_str = "";
                    update_friend_in_db_ip_addr_str(f);
                }
            }
            else
            {
                f.ip_addr_str = "";
                update_friend_in_db_ip_addr_str(f);
            }

            if (f.TOX_CONNECTION_real != a_TOX_CONNECTION)
            {
                if (f.TOX_CONNECTION_real == TOX_CONNECTION_NONE.value)
                {
                    // ******** friend just came online ********
                    if (PREF__use_push_service)
                    {
                        if (!is_any_relay(f.tox_public_key_string))
                        {
                            send_pushurl_to_friend(f.tox_public_key_string);
                        }
                    }
                }
            }

            if (f.TOX_CONNECTION_real != a_TOX_CONNECTION)
            {
                if (a_TOX_CONNECTION == 0)
                {
                    // HelperGeneric.logI(TAG, "friend_connection_status:friend:" + friend_number + ":went offline");
                    // TODO: stop any active calls to/from this friend
                    try
                    {
                        // HelperGeneric.logI(TAG, "friend_connection_status:friend:" + friend_number + ":stop any calls");
                        toxav_call_control(friend_number, ToxVars.TOXAV_CALL_CONTROL.TOXAV_CALL_CONTROL_CANCEL.value);

                        if (tox_friend_by_public_key__wrapper(Callstate.friend_pubkey) == friend_number)
                        {
                            CallAudioService.stop_me(false);
                        }
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                    }
                }

                f.TOX_CONNECTION_real = a_TOX_CONNECTION;
                f.TOX_CONNECTION_on_off_real = HelperGeneric.get_toxconnection_wrapper(f.TOX_CONNECTION);
                HelperFriend.update_friend_in_db_connection_status_real(f);

                if (a_TOX_CONNECTION != TOX_CONNECTION_NONE.value)
                {
                    HelperFiletransfer.on_friend_connection_available(f.tox_public_key_string);
                    // KHANDAQ: friend just came online — re-arm unacked outgoing messages so they are
                    // resent and re-acknowledged (re-syncs delivery status, clears stale "failed").
                    HelperMessage.rearm_unacked_direct_messages_on_reconnect(f.tox_public_key_string);
                }
            }

            if (is_any_relay(f.tox_public_key_string))
            {
                if (!HelperRelay.is_own_relay(f.tox_public_key_string))
                {
                    FriendList f_real = HelperRelay.get_friend_for_relay(f.tox_public_key_string);

                    if (f_real != null)
                    {
                        HelperGeneric.update_friend_connection_status_helper(a_TOX_CONNECTION, f_real, true);
                    }
                }
                else // is own relay
                {
                    if (a_TOX_CONNECTION == 2)
                    {
                        draw_main_top_icon(top_imageview, context_s, Color.parseColor("#04b431"), false);
                    }
                    else if (a_TOX_CONNECTION == 1)
                    {
                        draw_main_top_icon(top_imageview, context_s, Color.parseColor("#ffce00"), false);
                    }
                    else
                    {
                        draw_main_top_icon(top_imageview, context_s, Color.parseColor("#ff0000"), false);
                    }
                }
            }

            HelperGeneric.update_friend_connection_status_helper(a_TOX_CONNECTION, f, false);

            // update connection status bar color on calling activity
            if (friend_number == tox_friend_by_public_key__wrapper(Callstate.friend_pubkey))
            {
                try
                {
                    update_calling_friend_connection_status(a_TOX_CONNECTION);
                }
                catch (Exception e)
                {
                }
            }
        }
    }

    static void android_tox_callback_friend_typing_cb_method(long friend_number, final int typing)
    {
        // HelperGeneric.logI(TAG, "friend_typing_cb:fn=" + friend_number + " typing=" + typing);
        final long friend_number_ = friend_number;
        Runnable myRunnable = new Runnable()
        {
            @SuppressLint("SetTextI18n")
            @Override
            public void run()
            {
                try
                {
                    if (message_list_activity != null)
                    {
                        if (message_list_activity.get_current_friendnum() == friend_number_)
                        {
                            message_list_activity.set_friend_typing_indicator(typing);
                        }
                    }
                }
                catch (Exception e)
                {
                    // e.printStackTrace();
                    HelperGeneric.logI(TAG, "friend_typing_cb:EE:" + e.getMessage());
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    static void android_tox_callback_friend_read_receipt_message_v2_cb_method(final long friend_number, long ts_sec, byte[] msg_id)
    {
        if (PREF__X_battery_saving_mode)
        {
            // HelperGeneric.logI(TAG, "friend_read_receipt_message_v2_cb::IN:fn=" + get_friend_name_from_num(friend_number));
            //**// HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:003:*PING*");
        }
        //**// global_last_activity_for_battery_savings_ts = System.currentTimeMillis();

        ByteBuffer msg_id_buffer = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
        msg_id_buffer.put(msg_id, 0, (int) TOX_HASH_LENGTH);
        final String message_id_hash_as_hex_string = HelperGeneric.bytesToHex(msg_id_buffer.array(),
                                                                              msg_id_buffer.arrayOffset(),
                                                                              msg_id_buffer.limit());
        // HelperGeneric.logI(TAG, "receipt_message_v2_cb:MSGv2HASH:2=" + message_id_hash_as_hex_string);

        try
        {
            final int m_try = orma.selectFromMessage().msg_id_hashEq(message_id_hash_as_hex_string).tox_friendpubkeyEq(
                    HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).directionEq(1).readEq(
                    false).toList().size();

            if (m_try < 1)
            {
                // HINT: it must be an ACK send from a friends toxproxy to singal the receipt of the message on behalf of the friend

                if (is_any_relay(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)))
                {
                    FriendList friend_of_relay = HelperRelay.get_friend_for_relay(
                            HelperFriend.tox_friend_get_public_key__wrapper(friend_number));

                    if (friend_of_relay != null)
                    {

                        Message m = (Message) orma.selectFromMessage().msg_id_hashEq(
                                message_id_hash_as_hex_string).tox_friendpubkeyEq(
                                friend_of_relay.tox_public_key_string).directionEq(1).readEq(false).toList().get(0);

                        if (m != null)
                        {
                            // HelperGeneric.logI(TAG, "receipt_message_v2_cb:msgid_via_relay found");
                            Runnable myRunnable = new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    try
                                    {
                                        set_message_msg_at_relay_from_id(m.id, true);
                                        m.msg_at_relay = true;
                                        HelperMessage.update_single_message(m, true);
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
                }

                return;
            }

            final Message m = (Message) orma.selectFromMessage().msg_id_hashEq(message_id_hash_as_hex_string).tox_friendpubkeyEq(
                    HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).directionEq(1).readEq(
                    false).toList().get(0);

            if (m != null)
            {
                HelperGeneric.logI(TAG, "receipt_message_v2_cb:msgid found");
                Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            if (!is_any_relay(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)))
                            {
                                // only update if the "read receipt" comes from a friend, but not it's relay!
                                m.raw_msgv2_bytes = "";
                                m.rcvd_timestamp = System.currentTimeMillis();
                                m.read = true;
                                HelperMessage.update_message_in_db_read_rcvd_timestamp_rawmsgbytes(m);
                            }

                            m.resend_count = 2;
                            HelperMessage.update_message_in_db_resend_count(m);
                            HelperMessage.update_single_message(m, true);
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
                HelperGeneric.logI(TAG, "receipt_message_v2_cb:msgid *NOT* found");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void android_tox_callback_friend_read_receipt_cb_method(long friend_number, long message_id)
    {
        // HelperGeneric.logI(TAG,
        //s      "friend_read_receipt:friend:" + get_friend_name_from_num(friend_number) + " message_id:" + message_id);
        if (PREF__X_battery_saving_mode)
        {
            HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:004:*PING*");
        }
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();

        try
        {
            if (get_friend_msgv3_capability(friend_number) == 1)
            {
                // HINT: friend has msgV3 capability, ignore normal read receipts
                // HelperGeneric.logI(TAG, "friend_read_receipt:msgV3:ignore low level ACK");
                return;
            }

            // there can be older messages with same message_id for this friend! so always take the latest one! -------
            final Message m = (Message) orma.selectFromMessage().message_idEq(message_id).tox_friendpubkeyEq(
                    HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).directionEq(
                    1).orderByIdDesc().toList().get(0);
            // there can be older messages with same message_id for this friend! so always take the latest one! -------

            // HelperGeneric.logI(TAG, "friend_read_receipt:m=" + m);
            // HelperGeneric.logI(TAG, "friend_read_receipt:m:message_id=" + m.message_id + " text=" + m.text + " friendpubkey=" + m.tox_friendpubkey + " read=" + m.read + " direction=" + m.direction);

            if (m != null)
            {
                // HelperGeneric.logI(TAG,
                //      "friend_read_receipt:friend:" + get_friend_name_from_num(friend_number) + " message:" + m.text +
                //      " m=" + m);

                Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            m.rcvd_timestamp = System.currentTimeMillis();
                            m.read = true;
                            HelperMessage.update_message_in_db_read_rcvd_timestamp_rawmsgbytes(m);
                            // TODO this updates all messages. should be done nicer and faster!
                            // update_message_view();
                            HelperMessage.update_single_message(m, true);
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
            HelperGeneric.logI(TAG, "friend_read_receipt:EE:" + e.getMessage());
            e.printStackTrace();
        }
    }

    static void android_tox_callback_friend_request_cb_method(String friend_public_key, String friend_request_message, long length)
    {
        final String friend_public_key__ = friend_public_key.substring(0, TOX_PUBLIC_KEY_SIZE * 2).toUpperCase();
        final String message = (friend_request_message != null) ? friend_request_message : "";

        if (main_handler_s == null || main_activity_s == null)
        {
            return;
        }

        main_handler_s.post(new Runnable()
        {
            @Override
            public void run()
            {
                showIncomingFriendRequestDialog(friend_public_key__, message);
            }
        });
    }

    private static void showIncomingFriendRequestDialog(final String friendPublicKey, final String message)
    {
        if (main_activity_s == null || main_activity_s.isFinishing())
        {
            return;
        }

        String body = message.length() > 0 ? message : friendPublicKey;

        new AlertDialog.Builder(main_activity_s)
                .setTitle(R.string.friend_request_dialog_title)
                .setMessage(body)
                .setNegativeButton(R.string.friend_request_decline, null)
                .setPositiveButton(R.string.friend_request_accept, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        HelperFriend.add_friend_to_system(friendPublicKey, false, null);
                        display_toast(context_s.getString(R.string.invite_friend_success), false, 300);
                    }
                })
                .show();
    }

    static void android_tox_callback_friend_message_v2_cb_method(long friend_number, String friend_message, long length, long ts_sec, long ts_ms, byte[] raw_message, long raw_message_length)
    {
        // HelperGeneric.logI(TAG, "friend_message_v2_cb::IN:fn=" + get_friend_name_from_num(friend_number) + " len=" + length);
        if (PREF__X_battery_saving_mode)
        {
            HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:005:*PING*");
        }
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        HelperGeneric.receive_incoming_message(1, 0, friend_number, friend_message, raw_message, raw_message_length,
                                               null, null, 0);
    }

    static void android_tox_callback_friend_lossless_packet_cb_method(long friend_number, byte[] data, long length)
    {
        // HelperGeneric.logI(TAG, "friend_lossless_packet_cb::IN:fn=" + get_friend_name_from_num(friend_number) + " len=" + length +
        //           " data=" + bytes_to_hex(data));

        if (length > 0)
        {
            if (data[0] == (byte) CONTROL_PROXY_MESSAGE_TYPE_PROXY_PUBKEY_FOR_FRIEND.value)
            {
                if (length == (TOX_PUBLIC_KEY_SIZE + 1))
                {
                    // HelperGeneric.logI(TAG, "friend_lossless_packet_cb:recevied CONTROL_PROXY_MESSAGE_TYPE_PROXY_PUBKEY_FOR_FRIEND");
                    String relay_pubkey = bytes_to_hex(data).substring(2);
                    // HelperGeneric.logI(TAG, "friend_lossless_packet_cb:recevied pubkey:" + relay_pubkey);
                    HelperFriend.add_friend_to_system(relay_pubkey.toUpperCase(), true,
                                                      HelperFriend.tox_friend_get_public_key__wrapper(friend_number));
                }
            }
            else if (data[0] == (byte) MessageChunker.PKT_CHUNK || data[0] == (byte) MessageChunker.PKT_CHUNK_ACK)
            {
                MessageChunker.handleIncoming(friend_number, data, (int) length);
            }
            else if (data[0] == (byte) HelperGroup.LOSSLESS_PKT_GROUP_INVITE_REQUEST)
            {
                HelperGroup.handle_group_invite_request(friend_number, data, (int) length);
            }
            else if (data[0] == (byte) ConnectionHealthMonitor.PKT_CONN_KEEPALIVE)
            {
                ConnectionHealthMonitor.onKeepaliveReceived(friend_number);
            }
            else if (data[0] == (byte) HelperMessageEdit.PKT_MSG_EDIT)
            {
                // KHANDAQ (#9): app-level edit of an own 1:1 message
                HelperMessageEdit.handleIncomingFriendEdit(friend_number, data, (int) length);
            }
            else if (data[0] == (byte) HelperMessageDelete.PKT_MSG_DELETE)
            {
                // KHANDAQ (#179): app-level delete-for-both of an own 1:1 message
                HelperMessageDelete.handleIncomingFriendDelete(friend_number, data, (int) length);
            }
            else if (data[0] == (byte) HelperMessageReaction.PKT_MSG_REACTION)
            {
                // KHANDAQ (#192): app-level message reaction (add/remove)
                HelperMessageReaction.handleIncomingFriendReaction(friend_number, data, (int) length);
            }
            else if (data[0] == (byte) CONTROL_PROXY_MESSAGE_TYPE_PUSH_URL_FOR_FRIEND.value)
            {
                //HelperGeneric.logI(TAG,
                //      "android_tox_callback_friend_lossless_packet_cb_method:CONTROL_PROXY_MESSAGE_TYPE_PUSH_URL_FOR_FRIEND:len=" +
                //      length);
                if (length > ("https://".length() + 1))
                {
                    final String pushurl = new String(Arrays.copyOfRange(data, 1, data.length), StandardCharsets.UTF_8);
                    //HelperGeneric.logI(TAG,
                    //      "android_tox_callback_friend_lossless_packet_cb_method:CONTROL_PROXY_MESSAGE_TYPE_PUSH_URL_FOR_FRIEND:pushurl=" +
                    //      pushurl);
                    HelperFriend.add_pushurl_for_friend(pushurl,
                                                        HelperFriend.tox_friend_get_public_key__wrapper(friend_number));
                }
                else
                {
                    if (length == 0)
                    {
                        HelperFriend.remove_pushurl_for_friend(
                                HelperFriend.tox_friend_get_public_key__wrapper(friend_number));
                    }
                }
            }
        }
    }

    static void android_tox_callback_friend_sync_message_v2_cb_method(long friend_number, long ts_sec, long ts_ms, byte[] raw_message, long raw_message_length, byte[] raw_data, long raw_data_length)
    {
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb::IN:fn=" + get_friend_name_from_num(friend_number));

        if (!HelperRelay.is_own_relay(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)))
        {
            // sync message only accepted from my own relay
            return;
        }

        if (PREF__X_battery_saving_mode)
        {
            HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:006:*PING*");
        }
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:fn=" + friend_number + " full rawmsg    =" + bytes_to_hex(raw_message));
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:fn=" + friend_number + " wrapped rawdata=" + bytes_to_hex(raw_data));
        final ByteBuffer raw_message_buf_wrapped = ByteBuffer.allocateDirect((int) raw_data_length);
        raw_message_buf_wrapped.put(raw_data, 0, (int) raw_data_length);
        ByteBuffer raw_message_buf = ByteBuffer.allocateDirect((int) raw_message_length);
        raw_message_buf.put(raw_message, 0, (int) raw_message_length);
        long msg_sec = tox_messagev2_get_ts_sec(raw_message_buf);
        long msg_ms = tox_messagev2_get_ts_ms(raw_message_buf);
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:sec=" + msg_sec + " ms=" + msg_ms);
        ByteBuffer msg_id_buffer = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
        tox_messagev2_get_message_id(raw_message_buf, msg_id_buffer);
        String msg_id_as_hex_string = HelperGeneric.bytesToHex(msg_id_buffer.array(), msg_id_buffer.arrayOffset(),
                                                               msg_id_buffer.limit());
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:MSGv2HASH=" + msg_id_as_hex_string);
        String real_sender_as_hex_string = tox_messagev2_get_sync_message_pubkey(raw_message_buf);
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:real sender pubkey=" + real_sender_as_hex_string + " " +
        //           get_friend_name_from_pubkey(real_sender_as_hex_string));
        long msgv2_type = tox_messagev2_get_sync_message_type(raw_message_buf);
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:msg type=" + ToxVars.TOX_FILE_KIND.value_str((int) msgv2_type));
        ByteBuffer msg_id_buffer_wrapped = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
        tox_messagev2_get_message_id(raw_message_buf_wrapped, msg_id_buffer_wrapped);
        String msg_id_as_hex_string_wrapped = HelperGeneric.bytesToHex(msg_id_buffer_wrapped.array(),
                                                                       msg_id_buffer_wrapped.arrayOffset(),
                                                                       msg_id_buffer_wrapped.limit());
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:MSGv2HASH=" + msg_id_as_hex_string_wrapped + " len=" +
        //           msg_id_as_hex_string_wrapped.length());

        if (msgv2_type == ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_MESSAGEV2_SEND.value)
        {
            sync_messagev2_send(friend_number, raw_data, raw_data_length, raw_message_buf_wrapped, msg_id_buffer,
                                real_sender_as_hex_string);
        }
        else if (msgv2_type == ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_MESSAGEV2_ANSWER.value)
        {
            HelperMessage.sync_messagev2_answer(raw_message_buf_wrapped, friend_number, msg_id_buffer,
                                                real_sender_as_hex_string, msg_id_as_hex_string_wrapped);
        }
    }

    static void sync_messagev2_send(long friend_number, byte[] raw_data, long raw_data_length, ByteBuffer raw_message_buf_wrapped, ByteBuffer msg_id_buffer, String real_sender_as_hex_string)
    {
        long msg_wrapped_sec = tox_messagev2_get_ts_sec(raw_message_buf_wrapped);
        long msg_wrapped_ms = tox_messagev2_get_ts_ms(raw_message_buf_wrapped);
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:sec=" + msg_wrapped_sec + " ms=" + msg_wrapped_ms);
        ByteBuffer msg_text_buffer_wrapped = ByteBuffer.allocateDirect((int) raw_data_length);
        long text_length = tox_messagev2_get_message_text(raw_message_buf_wrapped, raw_data_length, 0, 0,
                                                          msg_text_buffer_wrapped);
        String wrapped_msg_text_as_string = "";

        try
        {
            wrapped_msg_text_as_string = new String(msg_text_buffer_wrapped.array(),
                                                    msg_text_buffer_wrapped.arrayOffset(), (int) text_length,
                                                    StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        String msg_text_as_hex_string_wrapped = HelperGeneric.bytesToHex(msg_text_buffer_wrapped.array(),
                                                                         msg_text_buffer_wrapped.arrayOffset(),
                                                                         msg_text_buffer_wrapped.limit());
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:len=" + text_length + " wrapped msg text str=" +
        //            wrapped_msg_text_as_string);
        // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:wrapped msg text hex=" + msg_text_as_hex_string_wrapped);

        try
        {
            if (tox_friend_by_public_key__wrapper(real_sender_as_hex_string) == -1)
            {
                // pubkey does NOT belong to a friend. it is probably a conference id
                // check it here

                // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:LL:" + orma.selectFromConferenceDB().toList());
                String real_conference_id = real_sender_as_hex_string;

                long conference_num = HelperConference.tox_conference_by_confid__wrapper(real_conference_id);
                // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:1:conference_num=" + conference_num);
                if (conference_num > -1)
                {
                    String real_sender_peer_pubkey = wrapped_msg_text_as_string.substring(0, 64);
                    long real_text_length = (text_length - 64 - 9);
                    String real_sender_text_ = wrapped_msg_text_as_string.substring(64);

                    String real_sender_text = "";
                    String real_send_message_id = "";

                    // HelperGeneric.logI(TAG,
                    //      "xxxxxxxxxxxxx2:" + real_sender_text_.length() + " " + real_sender_text_.substring(8, 9) +
                    //      " " + real_sender_text_.substring(9) + " " + real_sender_text_.substring(0, 8));

                    if ((real_sender_text_.length() > 8) && (real_sender_text_.startsWith(":", 8)))
                    {
                        real_sender_text = real_sender_text_.substring(9);
                        real_send_message_id = real_sender_text_.substring(0, 8).toLowerCase();
                    }
                    else
                    {
                        real_sender_text = real_sender_text_;
                        real_send_message_id = "";
                    }


                    long sync_msg_received_timestamp = (msg_wrapped_sec * 1000) + msg_wrapped_ms;

                    // add text as conference message
                    long sender_peer_num = HelperConference.get_peernum_from_peer_pubkey(real_conference_id,
                                                                                         real_sender_peer_pubkey);
                    // HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:sender_peer_num=" + sender_peer_num);

                    // now check if this is "potentially" a double message, we can not be sure a 100%
                    // since there is no uniqe key for each message
                    ConferenceMessage cm = get_last_conference_message_in_this_conference_within_n_seconds_from_sender_pubkey(
                            real_conference_id, real_sender_peer_pubkey, sync_msg_received_timestamp,
                            real_send_message_id, MESSAGE_SYNC_DOUBLE_INTERVAL_SECS, false);

                    if (cm != null)
                    {
                        if (cm.text.equals(real_sender_text))
                        {
                            HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:potentially double message:1");
                            // ok it's a "potentially" double message
                            // just ignore it, but still send "receipt" to proxy so it won't send this message again
                            send_friend_msg_receipt_v2_wrapper(friend_number, 3, msg_id_buffer,
                                                               (System.currentTimeMillis() / 1000));
                            return;
                        }
                    }

                    HelperGeneric.conference_message_add_from_sync(
                            HelperConference.tox_conference_by_confid__wrapper(real_conference_id), sender_peer_num,
                            real_sender_peer_pubkey, TRIFA_MSG_TYPE_TEXT.value, real_sender_text, real_text_length,
                            sync_msg_received_timestamp, real_send_message_id);

                    send_friend_msg_receipt_v2_wrapper(friend_number, 3, msg_id_buffer,
                                                       (System.currentTimeMillis() / 1000));
                    return;
                }
                else
                {
                    real_conference_id = real_conference_id.toLowerCase();
                    long group_num = tox_group_by_groupid__wrapper(real_conference_id);
                    if (group_num > -1)
                    {
                        // sync message is a group (NGC) message
                        String real_sender_peer_pubkey = wrapped_msg_text_as_string.substring(0, 64);
                        long real_text_length = (text_length - 64);
                        String real_sender_text_ = wrapped_msg_text_as_string.substring(64);

                        String real_sender_text = "";
                        String real_send_message_id = "";

                        if ((real_sender_text_.length() > 8) && (real_sender_text_.startsWith(":", 8)))
                        {
                            real_sender_text = real_sender_text_.substring(9);
                            real_send_message_id = real_sender_text_.substring(0, 8).toLowerCase();
                        }
                        else
                        {
                            real_sender_text = real_sender_text_;
                            real_send_message_id = "";
                        }

                        long sync_msg_received_timestamp = (msg_wrapped_sec * 1000) + msg_wrapped_ms;

                        // add text as conference message
                        long sender_peer_num = HelperGroup.get_group_peernum_from_peer_pubkey(real_conference_id,
                                                                                              real_sender_peer_pubkey);

                        GroupMessage gm = get_last_group_message_in_this_group_within_n_seconds_from_sender_pubkey(
                                real_conference_id, real_sender_peer_pubkey, sync_msg_received_timestamp,
                                real_send_message_id, MESSAGE_GROUP_SYNC_DOUBLE_INTERVAL_SECS, real_sender_text);

                        // HelperGeneric.logI(TAG, "m.message_id_tox=" + real_send_message_id);

                        if (gm != null)
                        {
                            HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:potentially double message:2");
                            // ok it's a "potentially" double message
                            // just ignore it, but still send "receipt" to proxy so it won't send this message again
                            send_friend_msg_receipt_v2_wrapper(friend_number, 3, msg_id_buffer,
                                                               (System.currentTimeMillis() / 1000));
                            return;
                        }

                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        //if (real_conference_id.equals(TOX_TRIFA_PUBLIC_GROUPID))
                        //{
                        //    HelperGeneric.logI(TAG, "DEBUG:ignoring toxproxy for TOX_TRIFA_PUBLIC_GROUPID:DEBUG");
                        //    return;
                        //}
                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        // ========= DEBUG =========
                        // ========= DEBUG =========

                        String syncer_pubkey = null;
                        try
                        {
                            syncer_pubkey = tox_friend_get_public_key__wrapper(friend_number);
                        }
                        catch(Exception e)
                        {
                        }

                        String peer_name = null;
                        final String peer_name_saved = tox_group_peer_get_name__wrapper(real_conference_id, real_sender_peer_pubkey);
                        if (peer_name_saved != null)
                        {
                            // HINT: use saved name instead of name from sync message
                            peer_name = peer_name_saved;
                        }

                        group_message_add_from_sync(real_conference_id, syncer_pubkey, sender_peer_num, real_sender_peer_pubkey,
                                                    TRIFA_MSG_TYPE_TEXT.value, real_sender_text, real_text_length,
                                                    sync_msg_received_timestamp, real_send_message_id,
                                                    TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_TOXPROXY.value,
                                                    peer_name);

                        send_friend_msg_receipt_v2_wrapper(friend_number, 3, msg_id_buffer,
                                                           (System.currentTimeMillis() / 1000));
                        return;
                    }
                    else
                    {
                        // sync message from unkown original sender
                        // still send "receipt" to our relay, or else it will send us this message forever
                        HelperGeneric.logI(TAG, "friend_sync_message_v2_cb:send receipt for unknown message");
                        send_friend_msg_receipt_v2_wrapper(friend_number, 4, msg_id_buffer,
                                                           (System.currentTimeMillis() / 1000));
                    }
                    return;
                }
            }
            else
            {
                HelperGeneric.receive_incoming_message(2, 0,
                                                       tox_friend_by_public_key__wrapper(real_sender_as_hex_string),
                                                       wrapped_msg_text_as_string, raw_data, raw_data_length,
                                                       real_sender_as_hex_string, null, 0);
            }
        }
        catch (Exception e2)
        {
            e2.printStackTrace();
        }

        // send message receipt v2 to own relay
        send_friend_msg_receipt_v2_wrapper(friend_number, 4, msg_id_buffer, (System.currentTimeMillis() / 1000));
    }

    // --- incoming message ---
    // --- incoming message ---
    // --- incoming message ---
    static void android_tox_callback_friend_message_cb_method(long friend_number, int message_type, String friend_message, long length, byte[] msgV3hash_bin, long message_timestamp)
    {
        if (PREF__X_battery_saving_mode)
        {
            HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:007:*PING*");
        }
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();

        final long fn = friend_number;
        final int mt = message_type;
        final String msg = friend_message;
        final byte[] hash = msgV3hash_bin;
        final long ts = message_timestamp;
        final int previewLen = (msg != null) ? Math.min(msg.length(), 48) : 0;
        final String preview = (msg != null && previewLen > 0) ? msg.substring(0, previewLen) : "";
        HelperGeneric.logI(TAG, "friend_message_cb:IN fn=" + HelperFriend.get_friend_name_from_num(fn)
                + " type=" + mt + " len=" + length + " msgv3=" + (hash != null)
                + " preview=" + preview);

        final Runnable work = () -> HelperGeneric.receive_incoming_message(0, mt, fn, msg, null, 0, null, hash, ts);

        if (main_handler_s != null)
        {
            main_handler_s.post(work);
        }
        else
        {
            work.run();
        }
    }
    // --- incoming message ---
    // --- incoming message ---
    // --- incoming message ---

    static void android_tox_callback_file_recv_control_cb_method(long friend_number, long file_number, int a_TOX_FILE_CONTROL)
    {
        if (PREF__X_battery_saving_mode)
        {
            HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:008:*PING*:file_recv_control_cb");
        }
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        // HelperGeneric.logI(TAG, "file_recv_control:" + friend_number + ":fn==" + file_number + ":" + a_TOX_FILE_CONTROL);

        if (a_TOX_FILE_CONTROL == TOX_FILE_CONTROL_CANCEL.value)
        {
            HelperGeneric.logI(TAG, "file_recv_control:CANCEL fn=" + friend_number + " filenum=" + file_number);
            HelperFiletransfer.cancel_filetransfer(friend_number, file_number);
        }
        else if (a_TOX_FILE_CONTROL == TOX_FILE_CONTROL_RESUME.value)
        {
            FileTransferDebug.logFileControlResume(friend_number, file_number);
            try
            {
                long ft_id = HelperFiletransfer.get_outgoing_filetransfer_id_from_friendnum_and_filenum(friend_number,
                                                                                                          file_number);
                if (ft_id < 0)
                {
                    ft_id = HelperFiletransfer.get_filetransfer_id_from_friendnum_and_filenum(friend_number,
                                                                                              file_number);
                }
                if (ft_id < 0)
                {
                    HelperGeneric.logI(TAG, "file_recv_control:TOX_FILE_CONTROL_RESUME:unknown ft fn=" + file_number);
                    return;
                }
                com.zoffcc.applications.sorm.Filetransfer ft_check = orma.selectFromFiletransfer().idEq(ft_id).get(0);

                // -------- DEBUG --------
                //                List<Filetransfer> ft_res = orma.selectFromFiletransfer().
                //                        tox_public_key_stringEq(tox_friend_get_public_key__wrapper(friend_number)).
                //                        orderByIdDesc().
                //                        limit(30).toList();
                //                int ii;
                //                HelperGeneric.logI(TAG, "file_recv_control:SQL:===============================================");
                //                for (ii = 0; ii < ft_res.size(); ii++)
                //                {
                //                    HelperGeneric.logI(TAG, "file_recv_control:SQL:" + ft_res.get(ii));
                //                }
                //                HelperGeneric.logI(TAG, "file_recv_control:SQL:===============================================");
                // -------- DEBUG --------

                if (ft_check.kind == TOX_FILE_KIND_AVATAR.value)
                {
                    //HelperGeneric.logI(TAG, "file_recv_control:TOX_FILE_CONTROL_RESUME::+AVATAR+");
                    //HelperGeneric.logI(TAG, "file_recv_control:TOX_FILE_CONTROL_RESUME:ft_id=" + ft_id);
                    HelperFiletransfer.set_filetransfer_state_from_id(ft_id, TOX_FILE_CONTROL_RESUME.value);
                    // if outgoing FT set "ft_accepted" to true
                    HelperFiletransfer.set_filetransfer_accepted_from_id(ft_id);
                }
                else
                {
                    //HelperGeneric.logI(TAG, "file_recv_control:TOX_FILE_CONTROL_RESUME::*DATA*");
                    //HelperGeneric.logI(TAG, "file_recv_control:TOX_FILE_CONTROL_RESUME:ft_id=" + ft_id);
                    long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft_id, friend_number);
                    //HelperGeneric.logI(TAG, "file_recv_control:TOX_FILE_CONTROL_RESUME:msg_id=" + msg_id);
                    HelperFiletransfer.set_filetransfer_state_from_id(ft_id, TOX_FILE_CONTROL_RESUME.value);
                    HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_RESUME.value);
                    // if outgoing FT set "ft_accepted" to true
                    HelperFiletransfer.set_filetransfer_accepted_from_id(ft_id);
                    HelperGeneric.set_message_accepted_from_id(msg_id);

                    // update_all_messages_global(true);
                    try
                    {
                        if (ft_id != -1)
                        {
                            HelperMessage.update_single_message_from_messge_id(msg_id, true);
                        }
                    }
                    catch (Exception e)
                    {
                    }
                    TrifaToxService.wakeup_tox_thread();
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        else if (a_TOX_FILE_CONTROL == TOX_FILE_CONTROL_PAUSE.value)
        {
            try
            {
                long ft_id = HelperFiletransfer.get_filetransfer_id_from_friendnum_and_filenum(friend_number,
                                                                                               file_number);
                long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft_id, friend_number);
                HelperFiletransfer.set_filetransfer_state_from_id(ft_id, TOX_FILE_CONTROL_PAUSE.value);
                HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_PAUSE.value);

                // update_all_messages_global(true);
                try
                {
                    if (ft_id != -1)
                    {
                        HelperMessage.update_single_message_from_messge_id(msg_id, true);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            catch (Exception e2)
            {
                e2.printStackTrace();
            }
        }
    }

    static void android_tox_callback_file_chunk_request_cb_method(long friend_number, long file_number, long position, long length)
    {
        FileTransferDebug.logFileChunkRequest(friend_number, file_number, position, length);
        //if (PREF__X_battery_saving_mode)
        //{
        //    HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:009:*PING*");
        //}
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        global_last_activity_outgoung_ft_ts = System.currentTimeMillis();

        // HelperGeneric.logI(TAG, "file_chunk_request:" + friend_number + ":" + file_number + ":" + position + ":" + length);

        try
        {
            com.zoffcc.applications.sorm.Filetransfer ft = orma.selectFromFiletransfer().directionEq(TRIFA_FT_DIRECTION_OUTGOING.value).stateNotEq(
                    TOX_FILE_CONTROL_CANCEL.value).tox_public_key_stringEq(
                    tox_friend_get_public_key__wrapper(friend_number)).file_numberEq(
                    file_number).orderByIdDesc().get(0);

            if (ft == null)
            {
                HelperGeneric.logI(TAG, "file_chunk_request:ft=NULL");
                return;
            }

            // HelperGeneric.logI(TAG, "file_chunk_request:ft=" + ft.kind + ":" + ft);

            if (ft.kind == TOX_FILE_KIND_AVATAR.value)
            {
                if (length == 0)
                {
                    // avatar transfer finished -----------
                    // done below // orma.deleteFromFiletransfer().idEq(ft.id);
                    // avatar transfer finished -----------
                    ByteBuffer avatar_chunk = ByteBuffer.allocateDirect(1);
                    int res = tox_file_send_chunk(friend_number, file_number, position, avatar_chunk, 0);
                    // HelperGeneric.logI(TAG, "file_chunk_request:res(2)=" + res);
                    // remove FT from DB
                    HelperFiletransfer.delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);
                }
                else
                {
                    // TODO: this is really aweful and slow. FIX ME -------------
                    if (VFS_ENCRYPT)
                    {
                        final String fname = HelperGeneric.get_vfs_image_filename_own_avatar();

                        if (fname != null)
                        {
                            final ByteBuffer avatar_bytes = HelperGeneric.file_to_bytebuffer(fname, true);

                            if (avatar_bytes != null)
                            {
                                long avatar_chunk_length = length;
                                byte[] bytes_chunck = new byte[(int) avatar_chunk_length];
                                avatar_bytes.position((int) position);
                                avatar_bytes.get(bytes_chunck, 0, (int) avatar_chunk_length);
                                ByteBuffer avatar_chunk = ByteBuffer.allocateDirect((int) avatar_chunk_length);
                                avatar_chunk.put(bytes_chunck);
                                int res = tox_file_send_chunk(friend_number, file_number, position, avatar_chunk,
                                                              avatar_chunk_length);
                                // HelperGeneric.logI(TAG, "file_chunk_request:res(1)=" + res);
                                // int res = tox_hash(hash_bytes, avatar_bytes, avatar_bytes.capacity());
                            }
                        }
                    }
                }
            }
            else if (ft.kind == TOX_FILE_KIND_FTV2.value)
            {
                if (length == 0)
                {
                    remove_ft_from_cache((Filetransfer) ft);

                    HelperGeneric.logI(TAG, "file_chunk_request:file fully sent");
                    // transfer finished -----------
                    long filedb_id = -1;

                    // put into "FileDB" table
                    FileDB file_ = new FileDB();
                    file_.kind = ft.kind;
                    file_.direction = ft.direction;
                    file_.tox_public_key_string = ft.tox_public_key_string;
                    file_.path_name = ft.path_name;
                    file_.file_name = ft.file_name;
                    file_.is_in_VFS = false;
                    file_.filesize = ft.filesize;
                    long row_id = orma.insertIntoFileDB(file_);
                    // HelperGeneric.logI(TAG, "file_chunk_request:FileDB:row_id=" + row_id);
                    filedb_id = orma.selectFromFileDB().tox_public_key_stringEq(
                            ft.tox_public_key_string).file_nameEq(ft.file_name).path_nameEq(
                            ft.path_name).directionEq(ft.direction).filesizeEq(
                            ft.filesize).orderByIdDesc().get(0).id;
                    // HelperGeneric.logI(TAG, "file_chunk_request:FileDB:filedb_id=" + filedb_id);

                    // HelperGeneric.logI(TAG, "file_chunk_request:file_READY:001:f.id=" + ft.id);
                    long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft.id, friend_number);
                    // HelperGeneric.logI(TAG, "file_chunk_request:file_READY:001a:msg_id=" + msg_id);
                    String full_path_builder = ft.path_name + "/" + ft.file_name;
                    if (ft.storage_frame_work)
                    {
                        full_path_builder = ft.path_name;
                    }
                    HelperMessage.update_message_in_db_filename_fullpath_friendnum_and_filenum(friend_number,
                                                                                               file_number,
                                                                                               full_path_builder);
                    HelperMessage.set_message_state_from_friendnum_and_filenum(friend_number, file_number,
                                                                               TOX_FILE_CONTROL_CANCEL.value);
                    HelperMessage.set_message_filedb_from_friendnum_and_filenum(friend_number, file_number, filedb_id);
                    HelperFiletransfer.set_filetransfer_for_message_from_friendnum_and_filenum(friend_number,
                                                                                               file_number, -1);

                    try
                    {
                        // HelperGeneric.logI(TAG, "file_chunk_request:file_READY:002");

                        if (ft.id != -1)
                        {
                            // HelperGeneric.logI(TAG, "file_chunk_request:file_READY:003:f.id=" + ft.id + " msg_id=" + msg_id);
                            HelperMessage.update_single_message_from_messge_id(msg_id, true);
                        }
                    }
                    catch (Exception e)
                    {
                        HelperGeneric.logI(TAG, "file_chunk_request:file_READY:EE:" + e.getMessage());
                    }

                    // transfer finished -----------
                    HelperFiletransfer.delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);
                    HelperFiletransfer.try_start_next_queued_outgoing_file(ft.tox_public_key_string);
                }
                else
                {
                    if (position == 0)
                    {
                        // this is the first chunk of an outgoing FT
                        // remember this timestamp as FT start time
                        try
                        {
                            orma.updateFiletransfer().idEq(ft.id).transfer_start_ts(
                                    System.currentTimeMillis()).execute();
                        }
                        catch(Exception e)
                        {
                        }
                    }

                    if (ft.storage_frame_work)
                    {
                        long file_chunk_length = length;
                        byte[] bytes_chunck = HelperGeneric.read_chunk_from_SD_file(ft.path_name, position,
                                                                                    file_chunk_length, false);
                        // HINT: we must add TOX_FILE_ID_LENGTH (32 bytes) before the actual data, and send that to toxcore
                        ByteBuffer file_chunk = ByteBuffer.allocateDirect(
                                (int) (file_chunk_length + TOX_FILE_ID_LENGTH));
                        ByteBuffer file_id_hash_bytes = hexstring_to_bytebuffer(ft.tox_file_id_hex);
                        file_chunk.put(file_id_hash_bytes);
                        file_chunk.put(bytes_chunck);
                        int res = tox_file_send_chunk(friend_number, file_number, position, file_chunk,
                                                      file_chunk_length + TOX_FILE_ID_LENGTH);
                        HelperFiletransfer.handle_outgoing_chunk_send_result(ft, friend_number, file_number, position,
                                length, res);
                    }
                    else
                    {
                        final String fname = new File(ft.path_name + "/" + ft.file_name).getAbsolutePath();
                        // HelperGeneric.logI(TAG, "file_chunk_request:fname=" + fname);
                        long file_chunk_length = length;
                        byte[] bytes_chunck = HelperGeneric.read_chunk_from_SD_file(fname, position, file_chunk_length,
                                                                                    true);
                        // HINT: we must add TOX_FILE_ID_LENGTH (32 bytes) before the actual data, and send that to toxcore
                        ByteBuffer file_chunk = ByteBuffer.allocateDirect(
                                (int) (file_chunk_length + TOX_FILE_ID_LENGTH));
                        ByteBuffer file_id_hash_bytes = hexstring_to_bytebuffer(ft.tox_file_id_hex);
                        file_chunk.put(file_id_hash_bytes);
                        file_chunk.put(bytes_chunck);
                        int res = tox_file_send_chunk(friend_number, file_number, position, file_chunk,
                                                      file_chunk_length + TOX_FILE_ID_LENGTH);
                        HelperFiletransfer.handle_outgoing_chunk_send_result(ft, friend_number, file_number, position,
                                length, res);
                    }

                    if (ft.filesize < UPDATE_MESSAGE_PROGRESS_SMALL_FILE_IS_LESS_THAN_BYTES)
                    {
                        if ((ft.current_position + UPDATE_MESSAGE_PROGRESS_AFTER_BYTES_SMALL_FILES) < position)
                        {
                            ft.current_position = position;
                            HelperFiletransfer.update_filetransfer_db_current_position((Filetransfer) ft);

                            if (ft.kind != TOX_FILE_KIND_AVATAR.value)
                            {
                                // update_all_messages_global(false);
                                try
                                {
                                    if (ft.id != -1)
                                    {
                                        HelperMessage.update_single_message_from_ftid(ft.id, true);
                                    }
                                }
                                catch (Exception e)
                                {
                                }
                            }
                        }
                    }
                    else
                    {
                        if ((ft.current_position + UPDATE_MESSAGE_PROGRESS_AFTER_BYTES) < position)
                        {
                            ft.current_position = position;
                            HelperFiletransfer.update_filetransfer_db_current_position((Filetransfer) ft);

                            if (ft.kind != TOX_FILE_KIND_AVATAR.value)
                            {
                                // update_all_messages_global(false);
                                try
                                {
                                    if (ft.id != -1)
                                    {
                                        HelperMessage.update_single_message_from_ftid(ft.id, true);
                                    }
                                }
                                catch (Exception e)
                                {
                                }
                            }
                        }
                    }
                }
            }
            else // TOX_FILE_KIND_DATA.value
            {
                if (length == 0)
                {
                    remove_ft_from_cache((Filetransfer) ft);

                    HelperGeneric.logI(TAG, "file_chunk_request:file fully sent");
                    // transfer finished -----------
                    long filedb_id = -1;

                    // put into "FileDB" table
                    FileDB file_ = new FileDB();
                    file_.kind = ft.kind;
                    file_.direction = ft.direction;
                    file_.tox_public_key_string = ft.tox_public_key_string;
                    file_.path_name = ft.path_name;
                    file_.file_name = ft.file_name;
                    file_.is_in_VFS = false;
                    file_.filesize = ft.filesize;
                    long row_id = orma.insertIntoFileDB(file_);
                    // HelperGeneric.logI(TAG, "file_chunk_request:FileDB:row_id=" + row_id);
                    filedb_id = orma.selectFromFileDB().tox_public_key_stringEq(
                            ft.tox_public_key_string).file_nameEq(ft.file_name).path_nameEq(
                            ft.path_name).directionEq(ft.direction).filesizeEq(
                            ft.filesize).orderByIdDesc().get(0).id;
                    // HelperGeneric.logI(TAG, "file_chunk_request:FileDB:filedb_id=" + filedb_id);

                    // HelperGeneric.logI(TAG, "file_chunk_request:file_READY:001:f.id=" + ft.id);
                    long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft.id, friend_number);
                    // HelperGeneric.logI(TAG, "file_chunk_request:file_READY:001a:msg_id=" + msg_id);
                    String full_path_builder = ft.path_name + "/" + ft.file_name;
                    if (ft.storage_frame_work)
                    {
                        full_path_builder = ft.path_name;
                    }
                    HelperMessage.update_message_in_db_filename_fullpath_friendnum_and_filenum(friend_number,
                                                                                               file_number,
                                                                                               full_path_builder);
                    HelperMessage.set_message_state_from_friendnum_and_filenum(friend_number, file_number,
                                                                               TOX_FILE_CONTROL_CANCEL.value);
                    HelperMessage.set_message_filedb_from_friendnum_and_filenum(friend_number, file_number, filedb_id);
                    HelperFiletransfer.set_filetransfer_for_message_from_friendnum_and_filenum(friend_number,
                                                                                               file_number, -1);

                    try
                    {
                        // HelperGeneric.logI(TAG, "file_chunk_request:file_READY:002");

                        if (ft.id != -1)
                        {
                            // HelperGeneric.logI(TAG, "file_chunk_request:file_READY:003:f.id=" + ft.id + " msg_id=" + msg_id);
                            HelperMessage.update_single_message_from_messge_id(msg_id, true);
                        }
                    }
                    catch (Exception e)
                    {
                        HelperGeneric.logI(TAG, "file_chunk_request:file_READY:EE:" + e.getMessage());
                    }

                    // transfer finished -----------
                    ByteBuffer dummy_chunk = ByteBuffer.allocateDirect(1);
                    int res = tox_file_send_chunk(friend_number, file_number, position, dummy_chunk, 0);
                    // HelperGeneric.logI(TAG, "file_chunk_request:res(2)=" + res);
                    // remove FT from DB
                    HelperFiletransfer.delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);
                    HelperFiletransfer.try_start_next_queued_outgoing_file(ft.tox_public_key_string);
                }
                else
                {
                    if (position == 0)
                    {
                        // this is the first chunk of an outgoing FT
                        // remember this timestamp as FT start time
                        try
                        {
                            orma.updateFiletransfer().idEq(ft.id).transfer_start_ts(
                                    System.currentTimeMillis()).execute();
                        }
                        catch(Exception e)
                        {
                        }
                    }

                    if (ft.storage_frame_work)
                    {
                        long file_chunk_length = length;
                        byte[] bytes_chunck = HelperGeneric.read_chunk_from_SD_file(ft.path_name, position,
                                                                                    file_chunk_length, false);
                        ByteBuffer file_chunk = ByteBuffer.allocateDirect((int) file_chunk_length);
                        file_chunk.put(bytes_chunck);
                        int res = tox_file_send_chunk(friend_number, file_number, position, file_chunk,
                                                      file_chunk_length);
                        HelperFiletransfer.handle_outgoing_chunk_send_result(ft, friend_number, file_number, position,
                                length, res);
                    }
                    else
                    {
                        final String fname = new File(ft.path_name + "/" + ft.file_name).getAbsolutePath();
                        // HelperGeneric.logI(TAG, "file_chunk_request:fname=" + fname);
                        long file_chunk_length = length;
                        byte[] bytes_chunck = HelperGeneric.read_chunk_from_SD_file(fname, position, file_chunk_length,
                                                                                    true);
                        // byte[] bytes_chunck = new byte[(int) file_chunk_length];
                        // avatar_bytes.position((int) position);
                        // avatar_bytes.get(bytes_chunck, 0, (int) file_chunk_length);
                        ByteBuffer file_chunk = ByteBuffer.allocateDirect((int) file_chunk_length);
                        file_chunk.put(bytes_chunck);
                        int res = tox_file_send_chunk(friend_number, file_number, position, file_chunk,
                                                      file_chunk_length);
                        HelperFiletransfer.handle_outgoing_chunk_send_result(ft, friend_number, file_number, position,
                                length, res);
                    }

                    if (ft.filesize < UPDATE_MESSAGE_PROGRESS_SMALL_FILE_IS_LESS_THAN_BYTES)
                    {
                        if ((ft.current_position + UPDATE_MESSAGE_PROGRESS_AFTER_BYTES_SMALL_FILES) < position)
                        {
                            ft.current_position = position;
                            HelperFiletransfer.update_filetransfer_db_current_position((Filetransfer) ft);

                            if (ft.kind != TOX_FILE_KIND_AVATAR.value)
                            {
                                // update_all_messages_global(false);
                                try
                                {
                                    if (ft.id != -1)
                                    {
                                        HelperMessage.update_single_message_from_ftid(ft.id, true);
                                    }
                                }
                                catch (Exception e)
                                {
                                }
                            }
                        }
                    }
                    else
                    {
                        if ((ft.current_position + UPDATE_MESSAGE_PROGRESS_AFTER_BYTES) < position)
                        {
                            ft.current_position = position;
                            HelperFiletransfer.update_filetransfer_db_current_position((Filetransfer) ft);

                            if (ft.kind != TOX_FILE_KIND_AVATAR.value)
                            {
                                // update_all_messages_global(false);
                                try
                                {
                                    if (ft.id != -1)
                                    {
                                        HelperMessage.update_single_message_from_ftid(ft.id, true);
                                    }
                                }
                                catch (Exception e)
                                {
                                }
                            }
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "file_chunk_request:EE1:" + e.getMessage());
        }
    }

    static void android_tox_callback_file_recv_cb_method(long friend_number, long file_number, int a_TOX_FILE_KIND, long file_size, String filename, long filename_length)
    {
        FileTransferDebug.logFileRecv(friend_number, file_number, file_size, filename);
        // HINT: ~~IOCipher can only handle files up to 2GBytes in size~~
        //         ^^^ this is no longer true!!
        if (file_size >= MAX_ALLOWED_INCOMING_FILESIZE_BYTES)
        {
            try
            {
                tox_file_control(friend_number, file_number, TOX_FILE_CONTROL_CANCEL.value);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            display_toast(context_s.getString(R.string.chat_incoming_file_too_large), false, 800);
            return;
        }

        if (PREF__X_battery_saving_mode)
        {
            HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:010:*PING*:file_recv_cb");
        }
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        // HelperGeneric.logI(TAG,
        //      "file_recv:" + get_friend_name_from_num(friend_number) + ":fn==" + file_number + ":" + a_TOX_FILE_KIND +
        //      ":" + file_size + ":" + filename + ":" + filename_length);

        if (a_TOX_FILE_KIND == TOX_FILE_KIND_AVATAR.value)
        {
            // HelperGeneric.logI(TAG, "file_recv:TOX_FILE_KIND_AVATAR");

            if (file_size > AVATAR_INCOMING_MAX_BYTE_SIZE)
            {
                HelperGeneric.logI(TAG, "file_recv:avatar_too_large");
                try
                {
                    tox_file_control(friend_number, file_number, TOX_FILE_CONTROL_CANCEL.value);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                return;
            }
            else if (file_size == 0)
            {
                // HelperGeneric.logI(TAG, "file_recv:avatar_size_zero");

                // friend wants to unset avatar
                HelperFriend.del_friend_avatar(HelperFriend.tox_friend_get_public_key__wrapper(friend_number),
                                               VFS_PREFIX + VFS_FILE_DIR + "/" +
                                               HelperFriend.tox_friend_get_public_key__wrapper(friend_number) + "/",
                                               FRIEND_AVATAR_FILENAME);

                try
                {
                    tox_file_control(friend_number, file_number, TOX_FILE_CONTROL_CANCEL.value);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                return;
            }

            ByteBuffer file_id_buffer = ByteBuffer.allocateDirect(TOX_FILE_ID_LENGTH);
            tox_file_get_file_id(friend_number, file_number, file_id_buffer);
            final String file_id_buffer_hex = HelperGeneric.bytesToHex(file_id_buffer.array(),
                                                                       file_id_buffer.arrayOffset(),
                                                                       file_id_buffer.limit()).toUpperCase();
            final String saved_hash_hex = get_friend_avatar_saved_hash_hex(HelperFriend.tox_friend_get_public_key__wrapper(friend_number));
            // HelperGeneric.logI(TAG, "file_recv:TOX_FILE_KIND_AVATAR:incoming hash:" + file_id_buffer_hex + " saved hash:" + saved_hash_hex);

            if ((file_id_buffer_hex != null) && (saved_hash_hex != null) && (saved_hash_hex.equals(file_id_buffer_hex)))
            {
                HelperGeneric.logI(TAG, "file_recv:avatar_hash_is_the_same");
                try
                {
                    tox_file_control(friend_number, file_number, TOX_FILE_CONTROL_CANCEL.value);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                return;
            }

            String file_name_avatar = FRIEND_AVATAR_FILENAME;
            Filetransfer f = new Filetransfer();
            f.tox_public_key_string = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            f.direction = TRIFA_FT_DIRECTION_INCOMING.value;
            f.file_number = file_number;
            f.kind = a_TOX_FILE_KIND;
            f.message_id = -1;
            f.state = TOX_FILE_CONTROL_RESUME.value;
            f.path_name = VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + f.tox_public_key_string + "/";
            f.file_name = file_name_avatar;
            f.filesize = file_size;
            f.current_position = 0;
            long row_id = HelperFiletransfer.insert_into_filetransfer_db(f);
            f.id = row_id;
            // TODO: we just accept incoming avatar, maybe make some checks first?
            tox_file_control(friend_number, file_number, TOX_FILE_CONTROL_RESUME.value);
        }
        else if (a_TOX_FILE_KIND == TOX_FILE_KIND_FTV2.value)
        {
            String filename_corrected = get_incoming_filetransfer_local_filename(filename,
                                                                                 HelperFriend.tox_friend_get_public_key__wrapper(
                                                                                         friend_number));


            // --- notification ---
            // --- notification ---
            // --- notification ---
            boolean do_notification = true;
            boolean do_badge_update = true;

            if (MainActivity.message_list_activity != null)
            {
                if (MainActivity.message_list_activity.get_current_friendnum() == friend_number)
                {
                    do_badge_update = false;
                }
            }
            // --- notification ---
            // --- notification ---
            // --- notification ---

            ByteBuffer file_id_buffer = ByteBuffer.allocateDirect(TOX_FILE_ID_LENGTH);
            tox_file_get_file_id(friend_number, file_number, file_id_buffer);
            final String file_id_buffer_hex = HelperGeneric.bytesToHex(file_id_buffer.array(),
                                                                       file_id_buffer.arrayOffset(),
                                                                       file_id_buffer.limit()).toUpperCase();
            HelperGeneric.logI(TAG, "TOX_FILE_ID_LENGTH=" + TOX_FILE_ID_LENGTH + " file_id_buffer_hex=" + file_id_buffer_hex);

            Filetransfer f = new Filetransfer();
            f.tox_public_key_string = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            f.direction = TRIFA_FT_DIRECTION_INCOMING.value;
            f.file_number = file_number;
            f.kind = a_TOX_FILE_KIND;
            f.state = TOX_FILE_CONTROL_PAUSE.value;
            f.path_name = VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + f.tox_public_key_string + "/";
            f.file_name = filename_corrected;
            f.filesize = file_size;
            f.ft_accepted = false;
            f.ft_outgoing_started = false; // dummy for incoming FTs, but still set it here
            f.current_position = 0;
            f.message_id = -1;
            f.tox_file_id_hex = file_id_buffer_hex;
            long ft_id = HelperFiletransfer.insert_into_filetransfer_db(f);
            f.id = ft_id;
            // add FT message to UI
            Message m = new Message();

            if (!do_badge_update)
            {
                m.is_new = false;
            }
            else
            {
                m.is_new = true;
            }

            m.tox_friendpubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            m.direction = 0; // msg received
            m.TOX_MESSAGE_TYPE = 0;
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.filetransfer_id = ft_id;
            m.filedb_id = -1;
            m.state = TOX_FILE_CONTROL_PAUSE.value;
            m.ft_accepted = false;
            m.ft_outgoing_started = false; // dummy for incoming FTs, but still set it here
            m.ft_outgoing_queued = false;
            m.rcvd_timestamp = System.currentTimeMillis();
            m.sent_timestamp = m.rcvd_timestamp;
            m.text = filename + "\n" + file_size + " bytes";
            m.sent_push = 0;
            m.filetransfer_kind = TOX_FILE_KIND_FTV2.value;
            // KHANDAQ (#192): durable reaction anchor (symmetric tox file_id) for this incoming 1:1 file.
            m.ft_id_anchor_hex = file_id_buffer_hex;
            long new_msg_id = -1;

            if (message_list_activity != null)
            {
                if (message_list_activity.get_current_friendnum() == friend_number)
                {
                    new_msg_id = HelperMessage.insert_into_message_db(m, true);
                    m.id = new_msg_id;
                }
                else
                {
                    new_msg_id = HelperMessage.insert_into_message_db(m, false);
                    m.id = new_msg_id;
                }
            }
            else
            {
                new_msg_id = HelperMessage.insert_into_message_db(m, false);
                m.id = new_msg_id;
            }

            f.message_id = new_msg_id;
            HelperFiletransfer.update_filetransfer_db_full(f);

            String friendname = null;
            try
            {
                // update "new" status on friendlist fragment
                FriendList f2 = (FriendList) orma.selectFromFriendList().tox_public_key_stringEq(m.tox_friendpubkey).toList().get(0);
                // HelperFriend.update_single_friend_in_friendlist_view(f2);
                HelperFriend.add_all_friends_clear_wrapper(0);

                if (f2.notification_silent)
                {
                    do_notification = false;
                }

                if (f2.alias_name == null)
                {
                    friendname = f2.name;
                }
                else if (f2.alias_name.length() < 1)
                {
                    friendname = f2.name;
                }
                else
                {
                    friendname = f2.alias_name;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                HelperGeneric.logI(TAG, "update *new* status:EE1:" + e.getMessage());
            }

            // --- notification ---
            // --- notification ---
            // --- notification ---
            if (do_notification)
            {
                change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.tox_friendpubkey, friendname,
                        HelperMsgNotification.media_label_for_filename(context_s, f.file_name));
            }
            // --- notification ---
            // --- notification ---
            // --- notification ---

            check_auto_accept_incoming_filetransfer(m);
            HelperFiletransfer.ensure_incoming_dm_file_accepted(m);
        }
        else // DATA file ft
        {
            String filename_corrected = get_incoming_filetransfer_local_filename(filename,
                                                                                 HelperFriend.tox_friend_get_public_key__wrapper(
                                                                                         friend_number));

            // --- notification ---
            // --- notification ---
            // --- notification ---
            boolean do_notification = true;
            boolean do_badge_update = true;

            if (MainActivity.message_list_activity != null)
            {
                if (MainActivity.message_list_activity.get_current_friendnum() == friend_number)
                {
                    do_badge_update = false;
                }
            }
            // --- notification ---
            // --- notification ---
            // --- notification ---


            Filetransfer f = new Filetransfer();
            f.tox_public_key_string = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            f.direction = TRIFA_FT_DIRECTION_INCOMING.value;
            f.file_number = file_number;
            f.kind = a_TOX_FILE_KIND;
            f.state = TOX_FILE_CONTROL_PAUSE.value;
            f.path_name = VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + f.tox_public_key_string + "/";
            f.file_name = filename_corrected;
            f.filesize = file_size;
            f.ft_accepted = false;
            f.ft_outgoing_started = false; // dummy for incoming FTs, but still set it here
            f.current_position = 0;
            f.message_id = -1;
            long ft_id = HelperFiletransfer.insert_into_filetransfer_db(f);
            f.id = ft_id;
            // add FT message to UI
            Message m = new Message();

            if (!do_badge_update)
            {
                m.is_new = false;
            }
            else
            {
                m.is_new = true;
            }

            m.tox_friendpubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            m.direction = 0; // msg received
            m.TOX_MESSAGE_TYPE = 0;
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.filetransfer_id = ft_id;
            m.filedb_id = -1;
            m.state = TOX_FILE_CONTROL_PAUSE.value;
            m.ft_accepted = false;
            m.ft_outgoing_started = false; // dummy for incoming FTs, but still set it here
            m.ft_outgoing_queued = false;
            m.rcvd_timestamp = System.currentTimeMillis();
            m.sent_timestamp = m.rcvd_timestamp;
            m.text = filename + "\n" + file_size + " bytes";
            m.sent_push = 0;
            m.filetransfer_kind = TOX_FILE_KIND_DATA.value;
            long new_msg_id = -1;

            if (message_list_activity != null)
            {
                if (message_list_activity.get_current_friendnum() == friend_number)
                {
                    new_msg_id = HelperMessage.insert_into_message_db(m, true);
                    m.id = new_msg_id;
                }
                else
                {
                    new_msg_id = HelperMessage.insert_into_message_db(m, false);
                    m.id = new_msg_id;
                }
            }
            else
            {
                new_msg_id = HelperMessage.insert_into_message_db(m, false);
                m.id = new_msg_id;
            }

            f.message_id = new_msg_id;
            HelperFiletransfer.update_filetransfer_db_full(f);

            String friendname = null;
            try
            {
                // update "new" status on friendlist fragment
                FriendList f2 = (FriendList) orma.selectFromFriendList().tox_public_key_stringEq(m.tox_friendpubkey).toList().get(0);
                // HelperFriend.update_single_friend_in_friendlist_view(f2);
                HelperFriend.add_all_friends_clear_wrapper(0);

                if (f2.notification_silent)
                {
                    do_notification = false;
                }

                if (f2.alias_name == null)
                {
                    friendname = f2.name;
                }
                else if (f2.alias_name.length() < 1)
                {
                    friendname = f2.name;
                }
                else
                {
                    friendname = f2.alias_name;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                HelperGeneric.logI(TAG, "update *new* status:EE1:" + e.getMessage());
            }

            // --- notification ---
            // --- notification ---
            // --- notification ---
            if (do_notification)
            {
                change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.tox_friendpubkey, friendname,
                        HelperMsgNotification.media_label_for_filename(context_s, f.file_name));
            }
            // --- notification ---
            // --- notification ---
            // --- notification ---

            check_auto_accept_incoming_filetransfer(m);
            HelperFiletransfer.ensure_incoming_dm_file_accepted(m);
        }
    }

    static void android_tox_callback_file_recv_chunk_cb_method(long friend_number, long file_number, long position, byte[] data, long length)
    {
        //if (PREF__X_battery_saving_mode)
        //{
        //    HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:011:*PING*");
        //}
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
        global_last_activity_incoming_ft_ts = System.currentTimeMillis();

        //HelperGeneric.logI(TAG, "file_recv_chunk:" + friend_number + ":fn==" + file_number + ":position=" + position + ":length=" + length + ":data len=" + data.length + ":data=" + data);
        //HelperGeneric.logI(TAG, "file_recv_chunk:--START--");
        //HelperGeneric.logI(TAG, "file_recv_chunk:" + friend_number + ":" + file_number + ":" + position + ":" + length);
        Filetransfer f = null;

        try
        {
            f = (Filetransfer) orma.selectFromFiletransfer().directionEq(TRIFA_FT_DIRECTION_INCOMING.value).file_numberEq(
                    file_number).stateNotEq(TOX_FILE_CONTROL_CANCEL.value).tox_public_key_stringEq(
                    HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).orderByIdDesc().get(0);

            if (f == null)
            {
                return;
            }

            if (position == 0)
            {
                // HelperGeneric.logI(TAG, "file_recv_chunk:START-O-F:filesize==" + f.filesize);

                orma.updateFiletransfer().idEq(f.id).transfer_start_ts(System.currentTimeMillis()).execute();

                // file start. just to be sure, make directories
                if (VFS_ENCRYPT)
                {
                    info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                            f.path_name + "/" + f.file_name);
                    info.guardianproject.iocipher.File f2 = new info.guardianproject.iocipher.File(f1.getParent());
                    // HelperGeneric.logI(TAG, "file_recv_chunk:f1=" + f1.getAbsolutePath());
                    // HelperGeneric.logI(TAG, "file_recv_chunk:f2=" + f2.getAbsolutePath());
                    f2.mkdirs();
                }
                else
                {
                    java.io.File f1 = new java.io.File(f.path_name + "/" + f.file_name);
                    java.io.File f2 = new java.io.File(f1.getParent());
                    // HelperGeneric.logI(TAG, "file_recv_chunk:f1=" + f1.getAbsolutePath());
                    // HelperGeneric.logI(TAG, "file_recv_chunk:f2=" + f2.getAbsolutePath());
                    f2.mkdirs();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return;
        }

        if (length == 0)
        {
            try
            {
                flush_and_close_vfs_ft_from_cache(f);

                if (!verify_incoming_file_complete(f))
                {
                    fail_incoming_filetransfer(f, friend_number, file_number, "incomplete_before_move");
                    return;
                }

                // HelperGeneric.logI(TAG, "file_recv_chunk:file fully received");
                HelperGeneric.move_tmp_file_to_real_file(f.path_name, f.file_name,
                                                         VFS_PREFIX + VFS_FILE_DIR + "/" + f.tox_public_key_string +
                                                         "/", f.file_name);
                if (HelperFiletransfer.get_vfs_file_length(VFS_PREFIX + VFS_FILE_DIR + "/" + f.tox_public_key_string + "/",
                                                           f.file_name) < f.filesize)
                {
                    fail_incoming_filetransfer(f, friend_number, file_number, "incomplete_after_move");
                    return;
                }

                long filedb_id = -1;

                if (f.kind != TOX_FILE_KIND_AVATAR.value)
                {
                    // put into "FileDB" table
                    FileDB file_ = new FileDB();
                    file_.kind = f.kind;
                    file_.direction = f.direction;
                    file_.tox_public_key_string = f.tox_public_key_string;
                    file_.path_name = VFS_PREFIX + VFS_FILE_DIR + "/" + f.tox_public_key_string + "/";
                    file_.file_name = f.file_name;
                    file_.filesize = f.filesize;
                    long row_id = orma.insertIntoFileDB(file_);
                    // HelperGeneric.logI(TAG, "file_recv_chunk:FileDB:row_id=" + row_id);
                    filedb_id = orma.selectFromFileDB().tox_public_key_stringEq(
                            f.tox_public_key_string).file_nameEq(f.file_name).orderByIdDesc().get(0).id;
                    // HelperGeneric.logI(TAG, "file_recv_chunk:FileDB:filedb_id=" + filedb_id);
                }

                // HelperGeneric.logI(TAG, "file_recv_chunk:kind=" + f.kind);

                if (f.kind == TOX_FILE_KIND_AVATAR.value)
                {
                    ByteBuffer file_id_buffer = ByteBuffer.allocateDirect(TOX_FILE_ID_LENGTH);
                    tox_file_get_file_id(friend_number, file_number, file_id_buffer);
                    final String file_id_buffer_hex = HelperGeneric.bytesToHex(file_id_buffer.array(),
                                                                               file_id_buffer.arrayOffset(),
                                                                               file_id_buffer.limit()).toUpperCase();

                    // we have received an avatar image for a friend. and the filetransfer is complete here
                    HelperFriend.set_friend_avatar(HelperFriend.tox_friend_get_public_key__wrapper(friend_number),
                                                   VFS_PREFIX + VFS_FILE_DIR + "/" + f.tox_public_key_string + "/",
                                                   f.file_name, file_id_buffer_hex);
                    // HelperGeneric.logI(TAG, "file_recv_chunk:kind=avatar:set_friend_avatar:" + VFS_PREFIX + VFS_FILE_DIR + "/" +
                    //           f.tox_public_key_string + "/" + f.file_name);
                }
                else
                {
                    // HelperGeneric.logI(TAG, "file_recv_chunk:file_READY:001:f.id=" + f.id);
                    long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(f.id, friend_number);
                    // HelperGeneric.logI(TAG, "file_recv_chunk:file_READY:001a:msg_id=" + msg_id);
                    HelperMessage.update_message_in_db_filename_fullpath_friendnum_and_filenum(friend_number,
                                                                                               file_number, VFS_PREFIX +
                                                                                                            VFS_FILE_DIR +
                                                                                                            "/" +
                                                                                                            f.tox_public_key_string +
                                                                                                            "/" +
                                                                                                            f.file_name);
                    HelperMessage.set_message_state_from_friendnum_and_filenum(friend_number, file_number,
                                                                               TOX_FILE_CONTROL_CANCEL.value);
                    HelperMessage.set_message_filedb_from_friendnum_and_filenum(friend_number, file_number, filedb_id);
                    HelperFiletransfer.set_filetransfer_for_message_from_friendnum_and_filenum(friend_number,
                                                                                               file_number, -1);

                    try
                    {
                        // HelperGeneric.logI(TAG, "file_recv_chunk:file_READY:002");

                        if (f.id != -1)
                        {
                            // HelperGeneric.logI(TAG, "file_recv_chunk:file_READY:003:f.id=" + f.id + " msg_id=" + msg_id);
                            HelperMessage.update_single_message_from_messge_id(msg_id, true);
                        }
                    }
                    catch (Exception e)
                    {
                        HelperGeneric.logI(TAG, "file_recv_chunk:file_READY:EE:" + e.getMessage());
                    }
                }

                remove_vfs_ft_from_cache(f);

                // remove FT from DB
                HelperFiletransfer.delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);
            }
            catch (Exception e2)
            {
                remove_vfs_ft_from_cache(f);
                e2.printStackTrace();
                fail_incoming_filetransfer(f, friend_number, file_number, "completion_error:" + e2.getMessage());
            }
        }
        else // normal chunck recevied ---------- (NOT start, and NOT end)
        {
            try
            {
                if (VFS_ENCRYPT)
                {
                    try
                    {
                        if (f.kind == TOX_FILE_KIND_FTV2.value)
                        {
                            write_chunk_to_VFS_file(f.path_name + "/" + f.file_name, position,
                                                    length - TOX_FILE_ID_LENGTH,
                                                    Arrays.copyOfRange(data, TOX_FILE_ID_LENGTH, data.length));
                        }
                        else
                        {
                            write_chunk_to_VFS_file(f.path_name + "/" + f.file_name, position, length, data);
                        }
                    }
                    catch (Exception e)
                    {
                        HelperGeneric.logI(TAG, "file_recv_chunk:write_failed:" + e.getMessage());
                        e.printStackTrace();
                        fail_incoming_filetransfer(f, friend_number, file_number, "chunk_write:" + e.getMessage());
                        return;
                    }
                }

                if (f.filesize < UPDATE_MESSAGE_PROGRESS_SMALL_FILE_IS_LESS_THAN_BYTES)
                {
                    if ((f.current_position + UPDATE_MESSAGE_PROGRESS_AFTER_BYTES_SMALL_FILES) < position)
                    {
                        f.current_position = position;
                        // HelperGeneric.logI(TAG, "file_recv_chunk:filesize==:2:" + f.filesize);
                        HelperFiletransfer.update_filetransfer_db_current_position(f);

                        if (f.kind != TOX_FILE_KIND_AVATAR.value)
                        {
                            // update_all_messages_global(false);
                            try
                            {
                                if (f.id != -1)
                                {
                                    HelperMessage.update_single_message_from_ftid(f.id, true);
                                }
                            }
                            catch (Exception e)
                            {
                            }
                        }
                    }
                }
                else
                {
                    if ((f.current_position + UPDATE_MESSAGE_PROGRESS_AFTER_BYTES) < position)
                    {
                        f.current_position = position;
                        // HelperGeneric.logI(TAG, "file_recv_chunk:filesize==:2:" + f.filesize);
                        HelperFiletransfer.update_filetransfer_db_current_position(f);

                        if (f.kind != TOX_FILE_KIND_AVATAR.value)
                        {
                            // update_all_messages_global(false);
                            try
                            {
                                if (f.id != -1)
                                {
                                    HelperMessage.update_single_message_from_ftid(f.id, true);
                                }
                            }
                            catch (Exception e)
                            {
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                HelperGeneric.logI(TAG, "file_recv_chunk:EE1:" + e.getMessage());
            }
        }
    }

    // void test(int i)
    // {
    //    HelperGeneric.logI(TAG, "test:" + i);
    // }

    static void android_tox_log_cb_method(int a_TOX_LOG_LEVEL, String file, long line, String function, String message)
    {
        if (CTOXCORE_NATIVE_LOGGING)
        {
            HelperGeneric.logI(TAG, "C-TOXCORE:" + ToxVars.TOX_LOG_LEVEL.value_str(a_TOX_LOG_LEVEL) + ":file=" + file + ":linenum=" +
                       line + ":func=" + function + ":msg=" + message);
        }
    }

    static void logger_XX(int level, String text)
    {
        HelperGeneric.logI(TAG, text);
    }
    // -------- called by native methods --------
    // -------- called by native methods --------
    // -------- called by native methods --------

    // -------- called by native Conference methods --------
    // -------- called by native Conference methods --------
    // -------- called by native Conference methods --------

    static void android_tox_callback_conference_connected_cb_method(long conference_number)
    {
        // invite also my ToxProxy -------------
        if (tox_conference_get_type(conference_number) == TOX_CONFERENCE_TYPE_TEXT.value)
        {
            if (have_own_relay())
            {
                invite_to_conference_own_relay(conference_number);
            }
        }
        HelperGeneric.logI(TAG, "conference_connected_cb:cf_num=" + conference_number);
        HelperGeneric.update_savedata_file_wrapper();
    }

    static void android_tox_callback_conference_invite_cb_method(final long friend_number, final int a_TOX_CONFERENCE_TYPE, final byte[] cookie_buffer, final long cookie_length)
    {
        // HelperGeneric.logI(TAG, "conference_invite_cb:fn=" + friend_number + " type=" + a_TOX_CONFERENCE_TYPE + " cookie_length=" +
        //            cookie_length + " cookie=" + bytes_to_hex(cookie_buffer));

        //try
        //{
        //Thread t = new Thread()
        //{
        // @Override
        //public void run()
        //{
        ByteBuffer cookie_buf2 = ByteBuffer.allocateDirect((int) cookie_length);
        cookie_buf2.put(cookie_buffer);
        HelperGeneric.logI(TAG, "conference_invite_cb:bytebuffer offset=" + cookie_buf2.arrayOffset());

        long conference_num = -1;
        if (a_TOX_CONFERENCE_TYPE != TOX_CONFERENCE_TYPE_AV.value)
        {
            conference_num = tox_conference_join(friend_number, cookie_buf2, cookie_length);
        }
        else
        {
            conference_num = toxav_join_av_groupchat(friend_number, cookie_buf2, cookie_length);
            HelperGeneric.update_savedata_file_wrapper();
            long result = toxav_groupchat_disable_av(conference_num);
            HelperGeneric.logI(TAG, "conference_invite_cb:toxav_groupchat_disable_av result=" + result);
        }

        display_toast(context_s.getString(R.string.invite_join_conference_success), false, 300);

        cache_confid_confnum.clear();

        HelperGeneric.logI(TAG, "conference_invite_cb:tox_conference_join res=" + conference_num);
        // strip first 3 bytes of cookie to get the conference_id.
        // this is aweful and hardcoded
        String conference_identifier = bytes_to_hex(
                Arrays.copyOfRange(cookie_buffer, 3, (int) (3 + CONFERENCE_ID_LENGTH)));
        HelperGeneric.logI(TAG, "conference_invite_cb:conferenc ID=" + conference_identifier);

        // invite also my ToxProxy -------------
        if (a_TOX_CONFERENCE_TYPE == TOX_CONFERENCE_TYPE_TEXT.value)
        {
            if (have_own_relay())
            {
                invite_to_conference_own_relay(conference_num);
            }
        }
        // invite also my ToxProxy -------------


        HelperConference.add_conference_wrapper(friend_number, conference_num, conference_identifier,
                                                a_TOX_CONFERENCE_TYPE, true);
        HelperGeneric.update_savedata_file_wrapper();
    }

    static void android_tox_callback_conference_message_cb_method(long conference_number, long peer_number, int a_TOX_MESSAGE_TYPE, String message_orig, long length)
    {
        if (tox_conference_get_type(conference_number) == TOX_CONFERENCE_TYPE_AV.value)
        {
            // we do not yet process messages from AV groups
            return;
        }

        // HelperGeneric.logI(TAG,"conference_message_cb:cf_num=" + conference_number + " pnum=" + peer_number + " msg=" + message_orig);
        int res = tox_conference_peer_number_is_ours(conference_number, peer_number);

        if (res == 1)
        {
            // HINT: do not add our own messages, they are already in the DB!
            return;
        }

        String message_ = "";
        String message_id_ = "";

        // HelperGeneric.logI(TAG, "xxxxxxxxxxxxx1:" + message_orig.length() + " " + message_orig.substring(8, 9) + " " +
        //           message_orig.substring(9) + " " + message_orig.substring(0, 8));

        if ((message_orig.length() > 8) && (message_orig.startsWith(":", 8)))
        {
            message_ = message_orig.substring(9);
            message_id_ = message_orig.substring(0, 8).toLowerCase();
        }
        else
        {
            message_ = message_orig;
            message_id_ = "";
        }

        boolean do_notification = true;
        boolean do_badge_update = true;
        String conf_id = "-1";
        ConferenceDB conf_temp = null;

        try
        {
            // TODO: cache me!!
            conf_temp = (ConferenceDB) orma.selectFromConferenceDB().tox_conference_numberEq(
                    conference_number).conference_activeEq(true).toList().get(0);
            conf_id = conf_temp.conference_identifier;
        }
        catch (Exception e)
        {
            // e.printStackTrace();
        }

        String conferencename = null;
        try
        {
            if (conf_temp.notification_silent)
            {
                do_notification = false;
            }
            conferencename = conf_temp.name;
        }
        catch (Exception e)
        {
            // e.printStackTrace();
            do_notification = false;
        }

        if (conference_message_list_activity != null)
        {
            HelperGeneric.logI(TAG,
                  "noti_and_badge:002conf:" + conference_message_list_activity.get_current_conf_id() + ":" + conf_id);

            if (conference_message_list_activity.get_current_conf_id().equals(conf_id))
            {
                // HelperGeneric.logI(TAG, "noti_and_badge:003:");
                // no notifcation and no badge update
                do_notification = false;
                do_badge_update = false;
            }
        }

        ConferenceMessage m = new ConferenceMessage();
        m.is_new = do_badge_update;
        // m.tox_friendnum = friend_number;
        m.tox_peerpubkey = HelperConference.tox_conference_peer_get_public_key__wrapper(conference_number, peer_number);
        m.direction = 0; // msg received
        m.TOX_MESSAGE_TYPE = 0;
        m.read = false;
        m.tox_peername = null;
        m.conference_identifier = conf_id;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.rcvd_timestamp = System.currentTimeMillis();
        m.sent_timestamp = System.currentTimeMillis();
        m.text = message_;
        m.message_id_tox = message_id_;
        m.was_synced = false;

        // now check if this is "potentially" a double message, we can not be sure a 100% since there is no uniqe key for each message
        ConferenceMessage cm = get_last_conference_message_in_this_conference_within_n_seconds_from_sender_pubkey(
                conf_id, m.tox_peerpubkey, m.sent_timestamp, m.message_id_tox, MESSAGE_SYNC_DOUBLE_INTERVAL_SECS, true);
        if (cm != null)
        {
            if (cm.text.equals(message_))
            {
                HelperGeneric.logI(TAG, "conference_message_cb:potentially double message:2");
                // ok it's a "potentially" double message
                return;
            }
        }

        try
        {
            m.tox_peername = HelperConference.tox_conference_peer_get_name__wrapper(m.conference_identifier,
                                                                                    m.tox_peerpubkey);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (conference_message_list_activity != null)
        {
            if (conference_message_list_activity.get_current_conf_id().equals(conf_id))
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
            HelperGeneric.logI(TAG, "conference_message_cb:new_msg_id=" + new_msg_id);
        }

        // HelperConference.update_single_conference_in_friendlist_view(conf_temp);
        HelperFriend.add_all_friends_clear_wrapper(0);

        if (do_notification)
        {
            change_msg_notification(NOTIFICATION_EDIT_ACTION_ADD.value, m.conference_identifier, conferencename, m.text);
        }
    }

    static void android_tox_callback_conference_title_cb_method(long conference_number, long peer_number, String title, long title_length)
    {
        // HelperGeneric.logI(TAG, "conference_title_cb:" + "confnum=" + conference_number + " peernum=" + peer_number + " new_title=" +
        //           title + " title_length=" + title_length);

        try
        {
            ConferenceDB conf_temp2 = null;

            try
            {
                try
                {
                    // TODO: cache me!!
                    conf_temp2 = (ConferenceDB) orma.selectFromConferenceDB().tox_conference_numberEq(
                            conference_number).conference_activeEq(true).get(0);

                    if (conf_temp2 != null)
                    {
                        // update it in the Database
                        orma.updateConferenceDB().conference_identifierEq(conf_temp2.conference_identifier).name(
                                title).execute();
                    }
                }
                catch (Exception e)
                {
                    // e.printStackTrace();
                }
            }
            catch (Exception e2)
            {
                e2.printStackTrace();
                HelperGeneric.logI(TAG, "get_conference_title_from_confid:EE:3:" + e2.getMessage());
            }

            HelperConference.update_single_conference_in_friendlist_view(conf_temp2);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "android_tox_callback_conference_title_cb_method:EE1:" + e.getMessage());
        }

        try
        {
            if (ConfGroupAudioService.running)
            {
                if (conference_number == tox_conference_by_confid__wrapper(ConfGroupAudioService.conf_id))
                {
                    // update group title while in notification only
                    do_update_group_title();
                }
            }
        }
        catch (Exception e)
        {
        }
    }

    static void android_tox_callback_conference_peer_name_cb_method(long conference_number, long peer_number, String name, long name_length)
    {
        // HelperGeneric.logI(TAG, "conference_peer_name_cb:cf_num=" + conference_number);

        try
        {
            ConferenceDB conf_temp = null;

            try
            {
                // TODO: cache me!!
                conf_temp = (ConferenceDB) orma.selectFromConferenceDB().tox_conference_numberEq(
                        conference_number).conference_activeEq(true).get(0);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            if (conf_temp != null)
            {
                // --------------- BAD !! ---------------
                // --------------- BAD !! ---------------
                // --------------- BAD !! ---------------
                // workaround to fix a bug, where messages would appear to be from the wrong user in a conference
                // because toxcore changes all "peer numbers" when somebody leaves the confrence
                // but this can happen very often. so for now, clear cache every time
                // cache_peernum_pubkey.clear();
                cache_peername_pubkey2.clear();
                // --------------- BAD !! ---------------
                // --------------- BAD !! ---------------
                // --------------- BAD !! ---------------
                ConferenceMessage m = new ConferenceMessage();
                m.is_new = false;
                // m.tox_friendnum = friend_number;
                m.tox_peerpubkey = TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
                m.direction = 0; // msg received
                m.TOX_MESSAGE_TYPE = 0;
                m.read = false;
                m.tox_peername = "* System Message *";
                m.conference_identifier = conf_temp.conference_identifier;
                m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
                m.rcvd_timestamp = System.currentTimeMillis();
                m.sent_timestamp = System.currentTimeMillis();
                String peer_name_temp = "Unknown";
                String peer_name_temp2 = null;
                m.was_synced = false;

                try
                {
                    // don't use the wrapper here!
                    String peer_pubkey_temp = tox_conference_peer_get_public_key(conference_number, peer_number);
                    // HelperGeneric.logI(TAG, "namelist_change_cb:003:peer_pubkey_temp=" + peer_pubkey_temp);
                    // don't use the wrapper here!
                    peer_name_temp = name;
                    // HelperGeneric.logI(TAG, "namelist_change_cb:004:peer_name_temp=" + peer_name_temp);

                    try
                    {
                        if ((peer_name_temp != null) && (!peer_name_temp.equals("")))
                        {
                            peer_name_temp2 = peer_name_temp;
                        }
                    }
                    catch (Exception e5)
                    {
                        e5.printStackTrace();
                    }

                    if (peer_name_temp == null)
                    {
                        peer_name_temp = "Unknown";
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "namelist_change_cb:005:EE1:" + e.getMessage());
                }

                try
                {
                    if (conference_message_list_activity != null)
                    {
                        // HelperGeneric.logI(TAG, "namelist_change_cb:INFO:" + " 001");

                        if (conference_message_list_activity.get_current_conf_id().equals(
                                conf_temp.conference_identifier))
                        {
                            String peer_pubkey_temp2 = tox_conference_peer_get_public_key(conference_number,
                                                                                          peer_number);
                            //HelperGeneric.logI(TAG,
                            //      "namelist_change_cb:INFO:" + " 002 " + conference_number + ":" + peer_number + ":" +
                            //      peer_pubkey_temp2);
                            conference_message_list_activity.add_group_user(peer_pubkey_temp2, peer_number,
                                                                            peer_name_temp2, false);
                            //HelperGeneric.logI(TAG, "namelist_change_cb:INFO:" + " 003");
                        }
                    }
                }
                catch (Exception e3)
                {
                    e3.printStackTrace();
                }

                try
                {
                    // TODO: this is just doing nothing useful! check me! FIX ME!
                    ConferencePeerCacheDB cpcdb = new ConferencePeerCacheDB();
                    cpcdb.conference_identifier = conf_temp.conference_identifier;
                    String peer_pubkey_temp2 = tox_conference_peer_get_public_key(conference_number, peer_number);
                    cpcdb.peer_pubkey = peer_pubkey_temp2;
                    cpcdb.peer_name = peer_name_temp2;
                    cpcdb.last_update_timestamp = System.currentTimeMillis();
                    orma.insertIntoConferencePeerCacheDB(cpcdb);
                    // HelperGeneric.logI(TAG, "namelist_change_cb:insertIntoConferencePeerCacheDB:" + cpcdb);
                }
                catch (Exception e4)
                {
                    // e4.printStackTrace();
                }

                m.text = "" + peer_name_temp + " changed name.";
                // HelperGeneric.logI(TAG, "namelist_change_cb:INFO:" + peer_name_temp + " changed name.");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void android_tox_callback_conference_peer_list_changed_cb_method(long conference_number)
    {
        // update all peers in this conference
        // HelperGeneric.logI(TAG, "conference_peer_list_changed_cb:cf_num=" + conference_number);

        try
        {
            ConferenceDB conf_temp = null;

            try
            {
                // TODO: cache me!!
                conf_temp = (ConferenceDB) orma.selectFromConferenceDB().tox_conference_numberEq(
                        conference_number).conference_activeEq(true).get(0);
            }
            catch (Exception e)
            {
                // e.printStackTrace();
            }

            // --------------- BAD !! ---------------
            // --------------- BAD !! ---------------
            // --------------- BAD !! ---------------
            // workaround to fix a bug, where messages would appear to be from the wrong user in a conference
            // because toxcore changes all "peer numbers" when somebody leaves the confrence
            // but this can happen very often. so for now, clear cache every time
            // cache_peernum_pubkey.clear();
            cache_peername_pubkey2.clear();
            // --------------- BAD !! ---------------
            // --------------- BAD !! ---------------
            // --------------- BAD !! ---------------

            if (conf_temp != null)
            {
                try
                {
                    if (conference_message_list_activity != null)
                    {
                        // HelperGeneric.logI(TAG, "peer_list_changed_cb:INFO:" + " 001.1");

                        if (conference_message_list_activity.get_current_conf_id().equals(
                                conf_temp.conference_identifier))
                        {
                            // HelperGeneric.logI(TAG, "peer_list_changed_cb:INFO:" + " 002.1 " + conference_number);
                            conference_message_list_activity.update_group_all_users();
                        }
                    }
                }
                catch (Exception e3)
                {
                    e3.printStackTrace();
                }

                try
                {
                    if (conference_audio_activity != null)
                    {
                        // HelperGeneric.logI(TAG, "peer_list_changed_cb:INFO:" + " 001.1");

                        if (conference_audio_activity.get_current_conf_id().equals(conf_temp.conference_identifier))
                        {
                            // HelperGeneric.logI(TAG, "peer_list_changed_cb:INFO:" + " 002.1 " + conference_number);
                            conference_audio_activity.update_group_all_users();
                        }
                    }
                }
                catch (Exception e3)
                {
                    e3.printStackTrace();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        // HINT: this is just too much
        // update_savedata_file_wrapper();
    }

    // ------------------------
    // this is an old toxcore 0.1.x callback
    // not called anymore!!
    // android_tox_callback_conference_peer_list_changed_cb_method --> is used now instead
    static void android_tox_callback_conference_namelist_change_cb_method(long conference_number, long peer_number, int a_TOX_CONFERENCE_STATE_CHANGE)
    {
    }
    // ------------------------
    // this is an old toxcore 0.1.x callback
    // not called anymore!!

    // -------- called by native Conference methods --------
    // -------- called by native Conference methods --------
    // -------- called by native Conference methods --------

    // -------- called by native new Group methods --------
    // -------- called by native new Group methods --------
    // -------- called by native new Group methods --------

    static void android_tox_callback_group_message_cb_method(long group_number, long peer_id, int a_TOX_MESSAGE_TYPE, String message_orig, long length, long message_id)
    {
        android_tox_callback_group_message_cb_method_wrapper(group_number, peer_id, a_TOX_MESSAGE_TYPE, message_orig,
                                                             length, message_id, false);
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
    }

    static void android_tox_callback_group_private_message_cb_method(long group_number, long peer_id, int a_TOX_MESSAGE_TYPE, String message_orig, long length)
    {
        android_tox_callback_group_message_cb_method_wrapper(group_number, peer_id, a_TOX_MESSAGE_TYPE, message_orig,
                                                             length, 0, true);
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();
    }

    static void android_tox_callback_group_privacy_state_cb_method(long group_number, final int a_TOX_GROUP_PRIVACY_STATE)
    {
        HelperGeneric.logI(TAG,
              "group_privacy_state_cb:group_number=" + group_number + " privacy_state=" + a_TOX_GROUP_PRIVACY_STATE);
        final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
        update_group_in_db_privacy_state(group_identifier, a_TOX_GROUP_PRIVACY_STATE);
        update_group_in_friendlist(group_identifier);
        update_group_in_groupmessagelist(group_identifier);
        add_system_message_to_group_chat(group_identifier, "privacy state changed to: " +
                                                           ToxVars.TOX_GROUP_PRIVACY_STATE.value_str(
                                                                   a_TOX_GROUP_PRIVACY_STATE));
        update_savedata_file_wrapper();
    }

    static void android_tox_callback_group_invite_cb_method(long friend_number, final byte[] invite_data, final long invite_data_length, String group_name)
    {
        HelperGeneric.logI(TAG,
              "group_invite_cb:fn=" + friend_number + " invite_data_length=" + invite_data_length + " invite_data=" +
              bytes_to_hex(invite_data) + " groupname=" + group_name);

        // friend-assisted join: if we already created a stuck (peerless) instance of
        // this group via tox_group_join, drop it first — tox_group_invite_accept
        // would otherwise create a duplicate instance of the same chat id.
        try
        {
            final String incoming_chat_id = bytes_to_hex(
                    Arrays.copyOfRange(invite_data, 0, GROUP_ID_LENGTH)).toLowerCase();
            final long existing_gn = HelperGroup.tox_group_by_groupid__wrapper(incoming_chat_id);
            if (existing_gn >= 0)
            {
                final long existing_peers = tox_group_peer_count(existing_gn);
                final int existing_conn = tox_group_is_connected(existing_gn);
                if (existing_conn == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value
                    && existing_peers > 1L)
                {
                    HelperGeneric.logI(TAG, "group_invite_cb:already in group with peers, ignoring invite gn=" + existing_gn);
                    return;
                }
                HelperGeneric.logI(TAG, "group_invite_cb:leaving stuck instance gn=" + existing_gn + " peers=" + existing_peers
                           + " conn=" + existing_conn + " before invite accept");
                tox_group_leave(existing_gn, null);
                update_savedata_file_wrapper();
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "group_invite_cb:dup-check:EE:" + e.getMessage());
        }

        final ByteBuffer invite_data_buf_wrapped = ByteBuffer.allocateDirect((int) invite_data_length);
        invite_data_buf_wrapped.put(invite_data, 0, (int) invite_data_length);
        invite_data_buf_wrapped.rewind();
        long new_group_num = tox_group_invite_accept(friend_number, invite_data_buf_wrapped, invite_data_length,
                                                     HelperGroup.get_group_peer_join_name(), null);

        HelperGeneric.logI(TAG, "group_invite_cb:fn=" + friend_number + " got invited to group num=" + new_group_num);
        update_savedata_file_wrapper();

        if ((new_group_num >= 0) && (new_group_num < UINT32_MAX_JAVA))
        {
            display_toast(context_s.getString(R.string.invite_join_group_success), false, 300);

            // TODO: get real group_identifier and privacy_state, get it via API call
            String group_identifier = bytes_to_hex(Arrays.copyOfRange(invite_data, 0, GROUP_ID_LENGTH));
            HelperGroup.clear_pending_friend_assisted_join(group_identifier);
            HelperGroup.add_group_wrapper(friend_number, new_group_num, group_identifier, 0);
            final int new_privacy_state = tox_group_get_privacy_state(new_group_num);
            HelperGeneric.logI(TAG, "group_invite_cb:fn=" + friend_number + " got invited to group num=" + new_group_num +
                       " new_privacy_state=" + new_privacy_state);
            update_group_in_db_privacy_state(group_identifier, new_privacy_state);
            update_group_in_friendlist(group_identifier);
            update_group_in_groupmessagelist(group_identifier);
            HelperGroup.reconnect_group_if_disconnected(new_group_num, group_identifier);
            HelperGroup.sync_group_peers_from_tox_to_db(new_group_num);
            HelperGroup.on_group_connected(new_group_num);
            if (have_own_relay())
            {
                invite_to_group_own_relay(new_group_num);
            }
        }
    }

    static void android_tox_callback_group_peer_join_cb_method(long group_number, long peer_id)
    {
        HelperGeneric.logI(TAG, "group_peer_join_cb:group_number=" + group_number + " peer_id=" + peer_id
                + " id=" + tox_group_by_groupnum__wrapper(group_number));

        final String temp_group_identifier = tox_group_by_groupnum__wrapper(group_number);
        update_group_in_friendlist(temp_group_identifier);
        String group_peer_pubkey = null;
        try
        {
            group_peer_pubkey = tox_group_peer_get_public_key(group_number, peer_id);
        }
        catch(Exception e)
        {
        }
        int peer_role = tox_group_peer_get_role(group_number, peer_id);
        if (peer_role < 0)
        {
            peer_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
        }
        update_group_messages_peer_role(temp_group_identifier, group_peer_pubkey, peer_role);
        HelperGroup.record_group_peer_on_join(group_number, temp_group_identifier, peer_id, group_peer_pubkey, peer_role);
        HelperGroup.notify_group_peer_joined(group_number, peer_id);
        update_group_in_friendlist(temp_group_identifier);
        update_group_in_groupmessagelist(temp_group_identifier, true);
        int privacy_state = tox_group_get_privacy_state(group_number);
        if (privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            send_ngch_request(temp_group_identifier, tox_group_peer_get_public_key__wrapper(group_number, peer_id));
            sync_group_message_history(group_number, peer_id);
            HelperGroup.sync_group_message_history_to_all_peers(group_number);
            HelperGroup.schedule_group_message_resync(group_number);
        }
        // update_savedata_file_wrapper_throttled();
        update_savedata_file_wrapper();
    }

    static void android_tox_callback_group_peer_exit_cb_method(long group_number, long peer_id, int a_Tox_Group_Exit_Type)
    {
        // HelperGeneric.logI(TAG, "group_peer_exit_cb:group_number=" + group_number + " peer_id=" + peer_id + " exit_type=" +
        //           a_Tox_Group_Exit_Type);

        final String temp_group_identifier = tox_group_by_groupnum__wrapper(group_number);
        HelperGroup.record_group_peer_last_seen_on_exit(group_number, peer_id);
        HelperGroup.handle_group_peer_transport_lost(group_number, peer_id, a_Tox_Group_Exit_Type);
        // intentional leave/kick -> not a member anymore, drop from members DB
        HelperGroup.remove_group_peer_from_db_on_exit(group_number, peer_id, a_Tox_Group_Exit_Type);
        HelperGroup.sync_group_peers_from_tox_to_db(group_number);
        update_group_in_friendlist(temp_group_identifier);
        update_group_in_groupmessagelist(temp_group_identifier, true);
        HelperGroup.notify_group_peer_exit(group_number, peer_id, a_Tox_Group_Exit_Type);
        update_savedata_file_wrapper();
    }

    static void android_tox_callback_group_peer_name_cb_method(long group_number, long peer_id)
    {
        String group_peer_pubkey = null;
        try
        {
            group_peer_pubkey = tox_group_peer_get_public_key(group_number, peer_id);
        }
        catch(Exception e)
        {
        }
        final String temp_group_identifier = tox_group_by_groupnum__wrapper(group_number);

        int peer_role = tox_group_peer_get_role(group_number, peer_id);
        if (peer_role < 0)
        {
            peer_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
        }
        update_group_peer_in_db(group_number, temp_group_identifier, peer_id, group_peer_pubkey, peer_role);
        update_group_in_groupmessagelist(temp_group_identifier);
        HelperGroup.notify_group_peer_name_changed(group_number, peer_id);
        update_savedata_file_wrapper();
    }

    static void android_tox_callback_group_join_fail_cb_method(long group_number, int a_Tox_Group_Join_Fail)
    {
        HelperGeneric.logI(TAG, "group_join_fail_cb:group_number=" + group_number + " fail=" + a_Tox_Group_Join_Fail);
        HelperGroup.handle_group_join_fail_async(group_number, a_Tox_Group_Join_Fail);
        update_savedata_file_wrapper();
    }

    static void android_tox_callback_group_self_join_cb_method(long group_number)
    {
        HelperGeneric.logI(TAG, "group_self_join_cb:group_number=" + group_number);
        final String group_identifier = tox_group_by_groupnum__wrapper(group_number);

        set_group_active(group_identifier);
        add_system_message_to_group_chat(group_identifier, "You joined the group");
        update_savedata_file_wrapper();
        update_group_in_friendlist(group_identifier);
        update_group_in_groupmessagelist(group_identifier);
        int peer_role = tox_group_self_get_role(group_number);
        if (peer_role < 0)
        {
            peer_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
        }
        try
        {
            add_group_peer_to_db(group_number, group_identifier, tox_group_self_get_peer_id(group_number),
                                 tox_group_self_get_public_key(group_number), peer_role);
        }
        catch(Exception e)
        {
        }
        if (have_own_relay())
        {
            invite_to_group_own_relay(group_number);
        }

        HelperGroup.on_group_self_joined(group_number, group_identifier);
    }

    static void android_tox_callback_group_moderation_cb_method(long group_number, long source_peer_id, long target_peer_id, int a_Tox_Group_Mod_Event)
    {
        try
        {
            if ((source_peer_id == UINT32_MAX_JAVA) || (target_peer_id == UINT32_MAX_JAVA))
            {
                // HelperGeneric.logI(TAG, "group_moderation_cb:ERROR:group_number=" + group_number + " speer=" + source_peer_id + " tpeer=" + target_peer_id + " a_Tox_Group_Mod_Event=" + a_Tox_Group_Mod_Event);
                return;
            }

            final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
            HelperGeneric.logI(TAG, "group_moderation_cb:group_number=" + group_number + " group_identifier=" + group_identifier + " speer=" + source_peer_id + " tpeer=" + target_peer_id + " a_Tox_Group_Mod_Event=" + a_Tox_Group_Mod_Event);

            String group_peer_pubkey = null;
            try
            {
                group_peer_pubkey = tox_group_peer_get_public_key(group_number, target_peer_id);
            }
            catch(Exception e)
            {
                return;
            }

            int target_role = -1;
            if (a_Tox_Group_Mod_Event == ToxVars.Tox_Group_Mod_Event.TOX_GROUP_MOD_EVENT_USER.value)
            {
                target_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value;
            }
            else if (a_Tox_Group_Mod_Event == ToxVars.Tox_Group_Mod_Event.TOX_GROUP_MOD_EVENT_OBSERVER.value)
            {
                target_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
            }
            else if (a_Tox_Group_Mod_Event == ToxVars.Tox_Group_Mod_Event.TOX_GROUP_MOD_EVENT_MODERATOR.value)
            {
                target_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value;
            }

            if (target_role >= 0)
            {
                update_group_peer_in_db(group_number, group_identifier, target_peer_id, group_peer_pubkey, target_role);
                update_group_messages_peer_role(group_identifier, group_peer_pubkey, target_role);
                add_system_message_to_group_chat(group_identifier,
                        "peer " + target_peer_id + " changed role to "
                                + ToxVars.Tox_Group_Mod_Event.value_str(a_Tox_Group_Mod_Event));
            }

            update_group_in_groupmessagelist(group_identifier);
            update_savedata_file_wrapper();
        }
        catch(Exception e)
        {

        }
    }

    static void android_tox_callback_group_connection_status_cb_method(long group_number, int a_TOX_GROUP_CONNECTION_STATUS)
    {
        HelperGeneric.logI(TAG, "group_connection_status_cb:group_number=" + group_number + " status=" + a_TOX_GROUP_CONNECTION_STATUS
                + " id=" + tox_group_by_groupnum__wrapper(group_number));

        final String conn_group_identifier = tox_group_by_groupnum__wrapper(group_number);

        if (a_TOX_GROUP_CONNECTION_STATUS ==
            TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
        {
            HelperGroup.sync_group_peers_from_tox_to_db(group_number);
            update_group_in_groupmessagelist(conn_group_identifier, true);
            HelperGroup.on_group_connected(group_number);
        }
        else if (conn_group_identifier != null)
        {
            HelperGroup.schedule_group_auto_reconnect(group_number, conn_group_identifier);
            update_group_in_groupmessagelist(conn_group_identifier, true);
        }

        try
        {
            if (group_message_list_activity != null)
            {
                if (group_message_list_activity.get_current_group_id().equals(tox_group_by_groupnum__wrapper(group_number)))
                {
                    group_message_list_activity.set_group_connection_status_icon();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        update_savedata_file_wrapper();
    }

    static void android_tox_callback_group_topic_cb_method(long group_number, long peer_id, String topic, long topic_length)
    {
        // HelperGeneric.logI(TAG, "group_topic_cb: groupnum=" + group_number + " peer=" + peer_id + " topic=" + topic);
        if (topic == null)
        {
            topic = "";
        }
        final String group_identifier = tox_group_by_groupnum__wrapper(group_number);
        update_group_in_db_topic(group_identifier, topic);
        if ((topic != null) && (topic.length() > 0))
        {
            update_group_in_db_name(group_identifier, topic);
        }
        update_group_in_friendlist(group_identifier);
        update_group_in_groupmessagelist(group_identifier);
        update_savedata_file_wrapper();
    }

    /** Routes chunked NGC file transfer packets (0x12 begin / 0x13 chunk / 0x14 request). */
    private static boolean routeNgcChunkedFileTransferPacket(final long group_number, final long peer_id,
                                                             final byte[] data, final long length)
    {
        if (data == null || length < NgcGroupFileTransfer.FILE_REQUEST_HEADER
                || length > TOX_MAX_NGC_FILE_AND_HEADER_SIZE)
        {
            return false;
        }
        if (data[0] != (byte) 0x66 || data[1] != (byte) 0x77 || data[2] != (byte) 0x88
                || data[3] != (byte) 0x11 || data[4] != (byte) 0x34 || data[5] != (byte) 0x35
                || data[6] != (byte) 0x1)
        {
            return false;
        }

        if (data[7] == NgcGroupFileTransfer.PKT_FILE_REQUEST)
        {
            NgcGroupFileTransfer.handleIncomingFileRequest(group_number, peer_id, data, (int) length);
            return true;
        }
        if (data[7] == NgcGroupFileTransfer.PKT_FILE_CHUNK
                && length >= (NgcGroupFileTransfer.FILE_CHUNK_HEADER + 1))
        {
            NgcGroupFileTransfer.handleIncomingChunk(group_number, peer_id, data, (int) length);
            return true;
        }
        if (data[7] == NgcGroupFileTransfer.PKT_FILE_BEGIN
                && length >= NgcGroupFileTransfer.FILE_BEGIN_HEADER)
        {
            NgcGroupFileTransfer.handleIncomingBegin(group_number, peer_id, data, (int) length);
            return true;
        }
        return false;
    }

    static void android_tox_callback_group_custom_packet_cb_method(long group_number, long peer_id, final byte[] data, long length)
    {
        try
        {
            // HelperGeneric.logI(TAG,
            //      "group_custom_packet_cb:group_number=" + group_number + " peer_id=" + peer_id + " length=" + length +
            //      " data=" + HelperGeneric.bytesToHex(data, 0, (int) length));
        }
        catch (Exception e)
        {
        }

        // check for muted or kicked peers
        if (is_group_muted_or_kicked_peer(group_number, peer_id))
        {
            return;
        }
        // check for muted or kicked peers

        if (routeNgcChunkedFileTransferPacket(group_number, peer_id, data, length))
        {
            return;
        }

        // KHANDAQ (#9): message-edit packets are short (16-byte header + text) — own length gate,
        // handled before the big-file/histsync block whose minimum length would reject them.
        // (#193 fix: the delete packet is EXACTLY 16 bytes, the old ">= 17" gate dropped it)
        if ((length >= 16) && (length <= TOX_MAX_NGC_FILE_AND_HEADER_SIZE))
        {
            if ((data[0] == (byte) 0x66) && (data[1] == (byte) 0x77) && (data[2] == (byte) 0x88) &&
                (data[3] == (byte) 0x11) && (data[4] == (byte) 0x34) && (data[5] == (byte) 0x35) &&
                (data[6] == (byte) 0x1) && (data[7] == HelperMessageEdit.NGC_PKT_MSG_EDIT))
            {
                HelperMessageEdit.handleIncomingGroupEdit(group_number, peer_id, data, (int) length);
                return;
            }
            // KHANDAQ (#179): delete-for-both broadcast (fixed 16 bytes, same NGC header family)
            if ((data[0] == (byte) 0x66) && (data[1] == (byte) 0x77) && (data[2] == (byte) 0x88) &&
                (data[3] == (byte) 0x11) && (data[4] == (byte) 0x34) && (data[5] == (byte) 0x35) &&
                (data[6] == (byte) 0x1) && (data[7] == HelperMessageDelete.NGC_PKT_MSG_DELETE))
            {
                HelperMessageDelete.handleIncomingGroupDelete(group_number, peer_id, data, (int) length);
                return;
            }
            // KHANDAQ (#192): message reactions broadcast (same NGC header family)
            if ((data[0] == (byte) 0x66) && (data[1] == (byte) 0x77) && (data[2] == (byte) 0x88) &&
                (data[3] == (byte) 0x11) && (data[4] == (byte) 0x34) && (data[5] == (byte) 0x35) &&
                (data[6] == (byte) 0x1) && (data[7] == HelperMessageReaction.NGC_PKT_MSG_REACTION))
            {
                HelperMessageReaction.handleIncomingGroupReaction(group_number, peer_id, data, (int) length);
                return;
            }
        }

        // check for correct signature of packets
        final long header_ngc_audio_v1 = 6 + 1 + 1 + 1 + 1;
        final long header_ngc_video_v1 = 6 + 1 + 1 + 1 + 1 + 1;
        final long header_ngc_video_v2 = 6 + 1 + 1 + 1 + 1 + 1 + 2 + 1;
        final long header_ngc_histsync_and_files = 6 + 1 + 1 + 32 + 4 + 255;
        if ((length <= TOX_MAX_NGC_FILE_AND_HEADER_SIZE) && (length >= (header_ngc_histsync_and_files + 1)))
        {
            // @formatter:off
                /*
                | what      | Length in bytes| Contents                                           |
                |------     |--------        |------------------                                  |
                | magic     |       6        |  0x667788113435                                    |
                | version   |       1        |  0x01                                              |
                | pkt id    |       1        |  0x11
                 */
                // @formatter:on

            if ((data[0] == (byte) 0x66) && (data[1] == (byte) 0x77) && (data[2] == (byte) 0x88) &&
                (data[3] == (byte) 0x11) && (data[4] == (byte) 0x34) && (data[5] == (byte) 0x35))
            {
                if ((data[6] == (byte) 0x1) && (data[7] == (byte) 0x11))
                {
                    handle_incoming_group_file(group_number, peer_id, data, length, header_ngc_histsync_and_files);
                }
                else if ((data[6] == (byte) 0x1) && (data[7] == (byte) 0x12))
                {
                    NgcGroupFileTransfer.handleIncomingBegin(group_number, peer_id, data, (int) length);
                }
                else if ((data[6] == (byte) 0x1) && (data[7] == (byte) 0x13))
                {
                    NgcGroupFileTransfer.handleIncomingChunk(group_number, peer_id, data, (int) length);
                }
                else
                {
                    // HelperGeneric.logI(TAG, "group_custom_packet_cb:wrong signature 2");
                }
            }
            else
            {
                // HelperGeneric.logI(TAG, "group_custom_packet_cb:wrong signature 1");
            }
        }

        if ((length <= TOX_MAX_NGC_FILE_AND_HEADER_SIZE) && (length >= (header_ngc_video_v1 + 1)))
        {
            // @formatter:off
                /*
                | what      | Length in bytes| Contents                                           |
                |------     |--------        |------------------                                  |
                | magic     |       6        |  0x667788113435                                    |
                | version   |       1        |  0x01                                              |
                | pkt id    |       1        |  0x21
                 */
            // @formatter:on

            if ((data[0] == (byte) 0x66) && (data[1] == (byte) 0x77) && (data[2] == (byte) 0x88) &&
                (data[3] == (byte) 0x11) && (data[4] == (byte) 0x34) && (data[5] == (byte) 0x35))
            {
                // byte 640 and 480. LOL
                if ((data[6] == (byte) 0x01) && (data[7] == (byte) 0x21)
                    && (data[8] == (byte) 480) && (data[9] == (byte) 640) && (data[10] == (byte) 1))
                {
                    // disable ngc video version 1 -----------
                    // show_ngc_incoming_video_frame_v1(group_number, peer_id, data, length);
                    // disable ngc video version 1 -----------
                }
                else if ((data[6] == (byte) 0x02) && (data[7] == (byte) 0x21)
                         && (data[8] == (byte) 480) && (data[9] == (byte) 640) && (data[10] == (byte) 1)
                         && (length >= (header_ngc_video_v2 + 1)))
                {
                    // HelperGeneric.logI(TAG, "group_custom_packet_cb:video_v2");
                    show_ngc_incoming_video_frame_v2(group_number, peer_id, data, length);
                }
                else
                {
                    // HelperGeneric.logI(TAG, "group_custom_packet_cb:wrong signature 2");
                }
            }
            else
            {
                // HelperGeneric.logI(TAG, "group_custom_packet_cb:wrong signature 1");
            }
        }

        if ((length <= TOX_MAX_NGC_FILE_AND_HEADER_SIZE) && (length >= (header_ngc_audio_v1 + 1)))
        {
            // @formatter:off
                /*
                | what          | Length in bytes| Contents                                           |
                |------         |--------        |------------------                                  |
                | magic         |       6        |  0x667788113435                                    |
                | version       |       1        |  0x01                                              |
                | pkt id        |       1        |  0x31                                              |
                | audio channels|       1        |  uint8_t always 1 (for MONO)                       |
                | sampling freq |       1        |  uint8_t always 48 (for 48kHz)                     |
                | data          |[1, 1363]       |  *uint8_t  bytes, zero not allowed!                |
                 */
            // @formatter:on

            if ((data[0] == (byte) 0x66) && (data[1] == (byte) 0x77) && (data[2] == (byte) 0x88) &&
                (data[3] == (byte) 0x11) && (data[4] == (byte) 0x34) && (data[5] == (byte) 0x35))
            {
                if ((data[6] == (byte) 0x01) && (data[7] == (byte) 0x31)
                    && (data[8] == (byte) 1) && (data[9] == (byte) 48))
                {
                    play_ngc_incoming_audio_frame(group_number, peer_id, data, length);
                }
                else
                {
                }
            }
            else
            {
            }
        }
    }

    static void android_tox_callback_group_custom_private_packet_cb_method(long group_number, long peer_id, final byte[] data, long length)
    {
        try
        {
            //HelperGeneric.logI(TAG,
            //      "group_custom_private_packet_cb:group_number=" + group_number + " peer_id=" + peer_id + " length=" + length +
            //      " data=" + HelperGeneric.bytesToHex(data, 0, (int) length));
        }
        catch(Exception e)
        {
        }

        try
        {
            long res = tox_group_self_get_peer_id(group_number);
            if (res == peer_id)
            {
                // HINT: ignore own packets
                // HelperGeneric.logI(TAG, "group_custom_private_packet_cb:gn=" + group_number + " peerid=" + peer_id + " ignoring own packet");
                return;
            }
        }
        catch(Exception e)
        {
        }

        // check for muted or kicked peers
        if (is_group_muted_or_kicked_peer(group_number, peer_id))
        {
            return;
        }
        // check for muted or kicked peers

        if (routeNgcChunkedFileTransferPacket(group_number, peer_id, data, length))
        {
            return;
        }

        // check for correct signature of packets
        final long header = 6 + 1 + 1;
        if ((length > TOX_MAX_NGC_FILE_AND_HEADER_SIZE) || (length < header))
        {
            HelperGeneric.logI(TAG, "group_custom_private_packet_cb: data length has wrong size: " + length);
            return;
        }

        // @formatter:off
            /*
            | what      | Length in bytes| Contents                                           |
            |------     |--------        |------------------                                  |
            | magic     |       6        |  0x667788113435                                    |
            | version   |       1        |  0x01                                              |
             */
        // @formatter:on

        if (
                (data[0] == (byte)0x66) &&
                (data[1] == (byte)0x77) &&
                (data[2] == (byte)0x88) &&
                (data[3] == (byte)0x11) &&
                (data[4] == (byte)0x34) &&
                (data[5] == (byte)0x35))
        {
            if ((data[6] == (byte)0x1) && (data[7] == (byte)0x1))
            {
                // HelperGeneric.logI(TAG, "group_custom_private_packet_cb: got ngch_request");
                int privacy_state = tox_group_get_privacy_state(group_number);
                if (privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
                {
                    sync_group_message_history(group_number, peer_id);
                }
                else
                {
                    HelperGeneric.logI(TAG, "group_custom_private_packet_cb: only sync history for public groups!");
                }
            }
            else if ((data[6] == (byte)0x1) && (data[7] == (byte)0x2))
            {
                final int header_syncmsg = 6 + 1 + 1 + 4 + 32 + 4 + 25;
                if (length >= (header_syncmsg + 1))
                {
                    HelperGeneric.logI(TAG, "group_custom_private_packet_cb:ngch_syncmsg gn=" + group_number + " peer=" + peer_id
                            + " len=" + length);
                    handle_incoming_sync_group_message(group_number, peer_id, data, length);
                }
            }
            else if ((data[6] == (byte)0x1) && (data[7] == (byte)0x3))
            {
                // HelperGeneric.logI(TAG, "group_custom_private_packet_cb: got ngch_syncfile:xxxxxxx");
                final int header_syncfile = 6 + 1 + 1 + 32 + 32 + 4 + 25 + 255;
                if (length >= (header_syncfile + 1))
                {
                    // HelperGeneric.logI(TAG, "group_custom_private_packet_cb: got ngch_syncfile");
                    handle_incoming_sync_group_file(group_number, peer_id, data, length);
                }
            }
            else if ((data[6] == (byte)0x1) && (data[7] == (byte)0x4))
            {
                if (length >= (6 + 1 + 1 + 4))
                {
                    HelperGroup.handle_incoming_group_delivery_receipt(group_number, peer_id, data, (int) length);
                }
            }
        }
    }

    // -------- called by native new Group methods --------
    // -------- called by native new Group methods --------
    // -------- called by native new Group methods --------

    /*
     * this is used to load the native library on
     * application startup. The library has already been unpacked at
     * installation time by the package manager.
     */
    static
    {
        try
        {
            System.loadLibrary("jni-c-toxcore");
            native_lib_loaded = true;
            HelperGeneric.logI(TAG, "successfully loaded jni-c-toxcore library");
            try
            {
                System.loadLibrary("jni-c-toxcore-topic-patch");
                HelperGeneric.logI(TAG, "successfully loaded jni-c-toxcore-topic-patch library");
            }
            catch (java.lang.UnsatisfiedLinkError patchErr)
            {
                // Optional: only needed when prebuilt libjni-c-toxcore.so lacks tox_group_set_topic JNI.
                HelperGeneric.logI(TAG, "jni-c-toxcore-topic-patch not loaded (may be linked in main lib)");
            }
        }
        catch (java.lang.UnsatisfiedLinkError e)
        {
            native_lib_loaded = false;
            HelperGeneric.logI(TAG, "loadLibrary jni-c-toxcore failed!");
            e.printStackTrace();
        }

        try
        {
            System.loadLibrary("native-audio-jni");
            native_audio_lib_loaded = true;
            HelperGeneric.logI(TAG, "successfully loaded native-audio-jni library");
        }
        catch (java.lang.UnsatisfiedLinkError e)
        {
            native_audio_lib_loaded = false;
            HelperGeneric.logI(TAG, "loadLibrary native-audio-jni failed!");
            e.printStackTrace();

            final Thread t = new Thread()
            {
                @Override
                public void run()
                {
                    try
                    {
                        Thread.sleep(6 * 1000);
                        display_toast("loadLibrary native-audio-jni failed!", false, 5000);
                    }
                    catch (Exception e)
                    {
                        HelperGeneric.logI(TAG, "loadLibrary native-audio-jni failed: toast failed to show");
                    }
                }
            };
            t.start();
        }

        if (NDK_STDOUT_LOGGING)
        {
            try
            {
                System.loadLibrary("loggingstdout");
                HelperGeneric.logI(TAG, "successfully loaded loggingstdout library");
                com.zoffcc.applications.loggingstdout.LoggingStdout.start_logging();
            }
            catch (java.lang.UnsatisfiedLinkError e)
            {
                HelperGeneric.logI(TAG, "loadLibrary loggingstdout failed!");
                e.printStackTrace();

                final Thread t = new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            Thread.sleep(6 * 1000);
                            display_toast("loadLibrary loggingstdout failed!", false, 5000);
                        }
                        catch (Exception e)
                        {
                            HelperGeneric.logI(TAG, "loadLibrary loggingstdout failed: toast failed to show");
                        }
                    }
                };
                t.start();
            }
        }
    }

    public void show_add_friend(View view)
    {
        Intent intent = new Intent(this, AddFriendActivity.class);
        // intent.putExtra("key", value);
        startActivityForResult(intent, AddFriendActivity_ID);
    }

    public void show_create_private_group(@Nullable View view)
    {
        startActivityForResult(new Intent(this, AddPrivateGroupActivity.class), AddPrivateGroupActivity_ID);
    }

    void open_profile_activity()
    {
        try
        {
            if (Callstate.state != 0)
            {
                return;
            }

            if (bottomNavigationView != null)
            {
                bottomNavigationView.setSelectedItemId(R.id.bottom_nav_profile);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    void exit_application_from_menu()
    {
        try
        {
            ConfGroupAudioService.stop_me(true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            CallAudioService.stop_me(true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            if (is_tox_started)
            {
                tox_service_fg.stop_tox_fg(true);
                tox_service_fg.stop_me(true);
            }
            else
            {
                tox_service_fg.stop_me(true);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void setupBottomNavigation(final Bundle savedInstanceState)
    {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        mainChatSearch = findViewById(R.id.main_chat_search);
        // KHANDAQ (#34): the chat-list search field acts as a button that opens the dedicated global
        // search screen (Telegram-style: searches every chat/group + all messages at once).
        if (mainChatSearch != null)
        {
            mainChatSearch.setFocusable(false);
            mainChatSearch.setFocusableInTouchMode(false);
            mainChatSearch.setOnClickListener(v ->
                    startActivity(new android.content.Intent(MainActivity.this, GlobalSearchActivity.class)));
        }
        setupChatFilterTabs();

        if (savedInstanceState == null)
        {
            contactsFragment = FriendListFragment.newInstance(FriendListFragment.LIST_MODE_CONTACTS);
            chatsFragment = FriendListFragment.newInstance(FriendListFragment.LIST_MODE_CHATS);
            settingsFragment = new SettingsTabFragment();
            profileFragment = new ProfileTabFragment();

            getSupportFragmentManager().beginTransaction()
                    .add(R.id.main_fragment_container, contactsFragment, "tab_contacts")
                    .add(R.id.main_fragment_container, chatsFragment, "tab_chats")
                    .add(R.id.main_fragment_container, settingsFragment, "tab_settings")
                    .add(R.id.main_fragment_container, profileFragment, "tab_profile")
                    .hide(contactsFragment)
                    .hide(settingsFragment)
                    .hide(profileFragment)
                    // KHANDAQ: onCreateContinue() may run from the async DB-init callback AFTER the
                    // activity already saved its state (e.g. screen locked during startup), where a
                    // plain commit() throws "Can not perform this action after onSaveInstanceState".
                    // This is the initial tab setup (no back stack), so allowing state loss is safe.
                    .commitAllowingStateLoss();
            bindChatFilterPagerListener();
        }
        else
        {
            contactsFragment = (FriendListFragment) getSupportFragmentManager().findFragmentByTag("tab_contacts");
            chatsFragment = (FriendListFragment) getSupportFragmentManager().findFragmentByTag("tab_chats");
            settingsFragment = (SettingsTabFragment) getSupportFragmentManager().findFragmentByTag("tab_settings");
            profileFragment = (ProfileTabFragment) getSupportFragmentManager().findFragmentByTag("tab_profile");
            currentMainTab = savedInstanceState.getInt("current_main_tab", R.id.bottom_nav_chats);
            bindChatFilterPagerListener();
        }

        bottomNavigationView.setOnItemSelectedListener(item -> {
            switchMainTab(item.getItemId());
            return true;
        });

        if (savedInstanceState == null)
        {
            bottomNavigationView.setSelectedItemId(R.id.bottom_nav_chats);
            currentMainTab = R.id.bottom_nav_chats;
        }
        else
        {
            bottomNavigationView.setSelectedItemId(currentMainTab);
        }

        updateMainToolbarForTab(currentMainTab);
        applyChatFilterTabSelection(currentChatFilterTab, false, false);
        ProfileTabConnectionIndicator.updateAsync();
        MainHeaderBrandingHelper.update(this);
        ProfileTabAvatarHelper.updateAsync();
    }

    private void setupChatFilterTabs()
    {
        chatFilterTabsContainer = findViewById(R.id.chat_list_filter_tabs);
        chatFilterTabDirect = findViewById(R.id.chat_filter_tab_direct);
        chatFilterTabGroups = findViewById(R.id.chat_filter_tab_groups);
        chatFilterTabFavorites = findViewById(R.id.chat_filter_tab_favorites);
        final View slidingIndicator = findViewById(R.id.chat_filter_tab_indicator_sliding);

        if (chatFilterTabDirect != null)
        {
            chatFilterLabelDirect = chatFilterTabDirect.findViewById(R.id.chat_filter_tab_label);
            chatFilterBadgeDirect = chatFilterTabDirect.findViewById(R.id.chat_filter_tab_badge);
            chatFilterLabelDirect.setText(R.string.tab_chats);
            chatFilterTabDirect.setOnClickListener(v -> selectChatFilterTab(FriendListFragment.CHAT_FILTER_DIRECT));
        }
        if (chatFilterTabGroups != null)
        {
            chatFilterLabelGroups = chatFilterTabGroups.findViewById(R.id.chat_filter_tab_label);
            chatFilterBadgeGroups = chatFilterTabGroups.findViewById(R.id.chat_filter_tab_badge);
            chatFilterLabelGroups.setText(R.string.chat_filter_groups);
            chatFilterTabGroups.setOnClickListener(v -> selectChatFilterTab(FriendListFragment.CHAT_FILTER_GROUPS));
        }
        if (chatFilterTabFavorites != null)
        {
            chatFilterLabelFavorites = chatFilterTabFavorites.findViewById(R.id.chat_filter_tab_label);
            chatFilterBadgeFavorites = chatFilterTabFavorites.findViewById(R.id.chat_filter_tab_badge);
            chatFilterLabelFavorites.setText(R.string.chat_filter_favorites);
            chatFilterTabFavorites.setOnClickListener(v -> selectChatFilterTab(FriendListFragment.CHAT_FILTER_FAVORITES));
        }

        final int activeColor = ContextCompat.getColor(this, R.color.tg_chat_title);
        final int inactiveColor = ContextCompat.getColor(this, R.color.tg_chat_preview);
        chatFilterTabIndicatorHelper = new ChatFilterTabIndicatorHelper(
                chatFilterTabDirect,
                chatFilterTabGroups,
                chatFilterTabFavorites,
                chatFilterLabelDirect,
                chatFilterLabelGroups,
                chatFilterLabelFavorites,
                slidingIndicator,
                activeColor,
                inactiveColor);

        if (chatFilterTabsContainer != null)
        {
            chatFilterTabsContainer.addOnLayoutChangeListener(
                    (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                        if (chatFilterTabIndicatorHelper != null)
                        {
                            chatFilterTabIndicatorHelper.remeasureTabs();
                        }
                    });
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        currentChatFilterTab = prefs.getInt(PREF_CHAT_FILTER_TAB, FriendListFragment.CHAT_FILTER_DIRECT);
    }

    private void bindChatFilterPagerListener()
    {
        if (chatsFragment == null)
        {
            return;
        }

        chatsFragment.setChatFilterPagerListener(new FriendListFragment.ChatFilterPagerListener()
        {
            @Override
            public void onChatFilterPageScrolled(final int position, final float positionOffset)
            {
                if (chatFilterTabIndicatorHelper != null)
                {
                    chatFilterTabIndicatorHelper.setScrollPosition(position, positionOffset);
                }
            }

            @Override
            public void onChatFilterPageSelected(final int tab)
            {
                applyChatFilterTabSelectionFromPager(tab);
            }
        });
    }

    private void applyChatFilterTabSelectionFromPager(final int tab)
    {
        currentChatFilterTab = tab;
        PreferenceManager.getDefaultSharedPreferences(this).edit().putInt(PREF_CHAT_FILTER_TAB, tab).apply();
        if (chatFilterTabIndicatorHelper != null)
        {
            chatFilterTabIndicatorHelper.snapToTab(tab);
        }
        if (chatsFragment != null)
        {
            chatsFragment.syncChatFilterTabSelection(tab);
        }
        refreshChatFilterTabBadges();
    }

    private void selectChatFilterTab(final int tab)
    {
        applyChatFilterTabSelection(tab, true, true);
    }

    private void applyChatFilterTabSelection(final int tab, final boolean reloadList)
    {
        applyChatFilterTabSelection(tab, reloadList, false);
    }

    private void applyChatFilterTabSelection(final int tab, final boolean reloadList, final boolean smoothPager)
    {
        currentChatFilterTab = tab;
        PreferenceManager.getDefaultSharedPreferences(this).edit().putInt(PREF_CHAT_FILTER_TAB, tab).apply();

        if (chatsFragment != null)
        {
            chatsFragment.selectChatFilterPage(tab, smoothPager);
            if (reloadList)
            {
                chatsFragment.applyChatFilterTabFromMain(tab, true);
            }
            else
            {
                chatsFragment.syncChatFilterTabSelection(tab);
            }
        }

        if (!smoothPager && chatFilterTabIndicatorHelper != null)
        {
            chatFilterTabIndicatorHelper.snapToTab(tab);
        }

        refreshChatFilterTabBadges();
    }

    public void refreshChatFilterTabBadges()
    {
        if (chatFilterTabsContainer == null || currentMainTab != R.id.bottom_nav_chats)
        {
            return;
        }

        final ChatListFilterHelper.TabUnreadCounts counts = ChatListFilterHelper.computeUnreadCounts(this);
        ChatListUiHelper.bind_unread_badge(chatFilterBadgeDirect, counts.direct);
        ChatListUiHelper.bind_unread_badge(chatFilterBadgeGroups, counts.groups);
        ChatListUiHelper.bind_unread_badge(chatFilterBadgeFavorites, counts.favorites);
    }

    public void onChatFavoriteChanged()
    {
        refreshChatFilterTabBadges();
        if (chatsFragment != null && currentMainTab == R.id.bottom_nav_chats)
        {
            chatsFragment.add_all_friends_clear(0);
        }
    }

    private void switchMainTab(final int tabId)
    {
        if (contactsFragment == null || chatsFragment == null || settingsFragment == null || profileFragment == null)
        {
            return;
        }

        currentMainTab = tabId;
        final FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.hide(contactsFragment);
        transaction.hide(chatsFragment);
        transaction.hide(settingsFragment);
        transaction.hide(profileFragment);

        if (tabId == R.id.bottom_nav_contacts)
        {
            transaction.show(contactsFragment);
            contactsFragment.add_all_friends_clear_force(0);
        }
        else if (tabId == R.id.bottom_nav_chats)
        {
            transaction.show(chatsFragment);
            chatsFragment.add_all_friends_clear(0);
        }
        else if (tabId == R.id.bottom_nav_settings)
        {
            transaction.show(settingsFragment);
        }
        else if (tabId == R.id.bottom_nav_profile)
        {
            transaction.show(profileFragment);
        }

        // KHANDAQ: tab switches (incl. the initial one during async startup) must survive a
        // state-saved activity — show/hide of tabs has no back stack, so state loss is safe.
        transaction.commitAllowingStateLoss();
        updateMainToolbarForTab(tabId);
    }

    private void updateMainToolbarForTab(final int tabId)
    {
        // KHANDAQ: header title = current tab name (Figma); connection state stays in the spinner/dot.
        final int tabTitleRes;
        if (tabId == R.id.bottom_nav_contacts)
        {
            tabTitleRes = R.string.tab_contacts;
        }
        else if (tabId == R.id.bottom_nav_settings)
        {
            tabTitleRes = R.string.tab_settings;
        }
        else if (tabId == R.id.bottom_nav_profile)
        {
            tabTitleRes = R.string.tab_profile;
        }
        else
        {
            tabTitleRes = R.string.tab_chats;
        }
        MainHeaderBrandingHelper.setTitle(this, tabTitleRes);

        if (mainChatSearch != null)
        {
            mainChatSearch.setVisibility(tabId == R.id.bottom_nav_chats ? View.VISIBLE : View.GONE);
        }

        if (chatFilterTabsContainer != null)
        {
            chatFilterTabsContainer.setVisibility(tabId == R.id.bottom_nav_chats ? View.VISIBLE : View.GONE);
        }

        // KHANDAQ: add-contact FAB (Figma) is shown only on the Контакты tab.
        final View fabAddContact = findViewById(R.id.fab_add_contact);
        if (fabAddContact != null)
        {
            fabAddContact.setVisibility(tabId == R.id.bottom_nav_contacts ? View.VISIBLE : View.GONE);
        }

        if (tabId == R.id.bottom_nav_chats)
        {
            applyChatFilterTabSelection(currentChatFilterTab, true);
        }

        invalidateOptionsMenu();
    }

    void toggleDarkModeFromToolbar()
    {
        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        final String currentValue = settings.getString("dark_mode_pref", "0");
        final String nextValue;

        if ("1".equals(currentValue) || ("0".equals(currentValue) && is_nightmode_active(this)))
        {
            nextValue = "2";
        }
        else
        {
            nextValue = "1";
        }

        ThemeTransitionHelper.recreateWithThemeCrossfade(this, nextValue);
    }

    private Drawable createMainToolbarMenuIcon(final GoogleMaterial.Icon icon)
    {
        return new IconicsDrawable(this)
                .icon(icon)
                .color(ContextCompat.getColor(this, R.color.tg_chat_title))
                .sizeDp(24);
    }

    private void updateAddFriendMenuIcon(final MenuItem addFriendItem)
    {
        addFriendItem.setIcon(createMainToolbarMenuIcon(GoogleMaterial.Icon.gmd_person_add));
        addFriendItem.setTitle(R.string.layout___add_friend);
    }

    private void updateGroupMenuIcon(final MenuItem groupMenuItem)
    {
        groupMenuItem.setIcon(createMainToolbarMenuIcon(GoogleMaterial.Icon.gmd_group_add));
        groupMenuItem.setTitle(R.string.group_actions_menu_title);
    }

    private void updateThemeToggleMenuIcon(final MenuItem themeItem)
    {
        final GoogleMaterial.Icon icon = is_nightmode_active(this)
                ? GoogleMaterial.Icon.gmd_brightness_3
                : GoogleMaterial.Icon.gmd_wb_sunny;
        themeItem.setIcon(createMainToolbarMenuIcon(icon));
        themeItem.setTitle(R.string.theme_toggle_accessibility);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState)
    {
        super.onSaveInstanceState(outState);
        outState.putInt("current_main_tab", currentMainTab);
    }

    static void update_main_profile_bar()
    {
        try
        {
            if (main_activity_s == null)
            {
                return;
            }

            if (main_profile_name != null)
            {
                String display_name = global_my_name;
                if ((display_name == null) || (display_name.trim().isEmpty()))
                {
                    display_name = main_activity_s.getString(R.string.title_activity_profile);
                }
                main_profile_name.setText(display_name.trim());
            }

            if (main_profile_avatar != null)
            {
                HelperGeneric.fill_own_avatar_icon(main_activity_s, main_profile_avatar);
            }

            ProfileTabAvatarHelper.updateAsync();
        }
        catch (Throwable ignored)
        {
        }
    }

    public void show_wrong_credentials()
    {
        Intent intent = new Intent(this, WrongCredentials.class);
        startActivity(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == AddFriendActivity_ID)
        {
            if (resultCode == RESULT_OK)
            {
                String friend_tox_id1 = data.getStringExtra("toxid");
                HelperFriend.add_friend_real(friend_tox_id1);
            }
            else
            {
                // (resultCode == RESULT_CANCELED)
            }
        }
        else if (requestCode == AddPrivateGroupActivity_ID)
        {
            if (resultCode == RESULT_OK && data != null)
            {
                final boolean created = HelperGroup.create_new_group(
                        ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value,
                        data.getStringExtra("group_name"),
                        R.string.add_private_group_success,
                        R.string.add_private_group_failed,
                        "You created the Group (as Private [invite only] Group)",
                        data.getStringExtra("group_password"));
                if (created && chatsFragment != null)
                {
                    applyChatFilterTabSelection(FriendListFragment.CHAT_FILTER_GROUPS, false);
                    chatsFragment.add_all_friends_clear(0);
                }
            }
        }
        else if (requestCode == AddPublicGroupActivity_ID)
        {
            if (resultCode == RESULT_OK)
            {
                HelperGroup.create_new_group(
                        ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value,
                        data.getStringExtra("group_name"),
                        R.string.add_public_group_success,
                        R.string.add_public_group_failed,
                        "You created the Group (as Public Group)");
            }
        }
        else if (requestCode == JoinPublicGroupActivity_ID)
        {
            // JoinPublicGroupActivity opens the group chat on success.
        }
    }

    void sendEmailWithAttachment(Context c, final String recipient, final String subject, final String message, final String full_file_name, final String full_file_name_suppl)
    {
        try
        {
            Intent emailIntent = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", recipient, null));
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(Uri.parse("file://" + full_file_name));
            HelperGeneric.logI(TAG, "email:full_file_name=" + full_file_name);
            File ff = new File(full_file_name);
            HelperGeneric.logI(TAG, "email:full_file_name exists:" + ff.exists());

            try
            {
                if (new File(full_file_name_suppl).length() > 0)
                {
                    uris.add(Uri.parse("file://" + full_file_name_suppl));
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                HelperGeneric.logI(TAG, "email:EE1:" + e.getMessage());
            }

            List<ResolveInfo> resolveInfos = getPackageManager().queryIntentActivities(emailIntent, 0);
            List<LabeledIntent> intents = new ArrayList<>();

            if (resolveInfos.size() != 0)
            {
                for (ResolveInfo info : resolveInfos)
                {
                    Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    HelperGeneric.logI(TAG, "email:" + "comp=" + info.activityInfo.packageName + " " + info.activityInfo.name);
                    intent.setComponent(new ComponentName(info.activityInfo.packageName, info.activityInfo.name));
                    intent.putExtra(Intent.EXTRA_EMAIL, new String[]{recipient});

                    if (subject != null)
                    {
                        intent.putExtra(Intent.EXTRA_SUBJECT, subject);
                    }

                    if (message != null)
                    {
                        intent.putExtra(Intent.EXTRA_TEXT, message);
                        // ArrayList<String> extra_text = new ArrayList<String>();
                        // extra_text.add(message);
                        // intent.putStringArrayListExtra(android.content.Intent.EXTRA_TEXT, extra_text);
                        // HelperGeneric.logI(TAG, "email:" + "message=" + message);
                        // HelperGeneric.logI(TAG, "email:" + "intent extra_text=" + extra_text);
                    }

                    intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                    intents.add(new LabeledIntent(intent, info.activityInfo.packageName,
                                                  info.loadLabel(getPackageManager()), info.icon));
                }

                try
                {
                    Intent chooser = Intent.createChooser(intents.remove(intents.size() - 1),
                                                          "Send email with attachments");
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.toArray(new LabeledIntent[intents.size()]));
                    startActivity(chooser);
                }
                catch (Exception email_app)
                {
                    email_app.printStackTrace();
                    HelperGeneric.logI(TAG, "email:" + "Error starting Email App");
                    new AlertDialog.Builder(c).setMessage(
                            R.string.MainActivity_error_starting_email_app).setPositiveButton(
                            R.string.MainActivity_button_ok, null).show();
                }
            }
            else
            {
                HelperGeneric.logI(TAG, "email:" + "No Email App found");
                new AlertDialog.Builder(c).setMessage(R.string.MainActivity_no_email_app_found).setPositiveButton(
                        R.string.MainActivity_button_ok, null).show();
            }
        }
        catch (ActivityNotFoundException e)
        {
            // cannot send email for some reason
            e.printStackTrace();
            HelperGeneric.logI(TAG, "email:EE2:" + e.getMessage());
        }
    }

    static String safe_string_XX(byte[] in)
    {
        HelperGeneric.logI(TAG, "safe_string:in=" + in);
        String out = "";

        try
        {
            out = new String(in, "UTF-8");  // Best way to decode using "UTF-8"
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

        HelperGeneric.logI(TAG, "safe_string:out=" + out);
        return out;
    }

    void getVersionInfo()
    {
        try
        {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionName = packageInfo.versionName;
            versionCode = packageInfo.versionCode;
        }
        catch (PackageManager.NameNotFoundException e)
        {
            e.printStackTrace();
        }
    }

    final static char[] hexArray = "0123456789ABCDEF".toCharArray();

    static class delete_selected_messages_asynchtask extends AsyncTask<Void, Void, String>
    {
        ProgressDialog progressDialog2;
        private WeakReference<Context> weakContext;
        boolean update_message_list = false;
        boolean update_friend_list = false;
        String dialog_text = "";
        // KHANDAQ (#205): when false, delete locally only (skip the KQ retract packet) → «Удалить у меня».
        boolean send_to_peer = true;

        delete_selected_messages_asynchtask(Context c, ProgressDialog progressDialog2, boolean update_message_list, boolean update_friend_list, String dialog_text)
        {
            this(c, progressDialog2, update_message_list, update_friend_list, dialog_text, true);
        }

        delete_selected_messages_asynchtask(Context c, ProgressDialog progressDialog2, boolean update_message_list, boolean update_friend_list, String dialog_text, boolean send_to_peer)
        {
            this.weakContext = new WeakReference<>(c);
            this.progressDialog2 = progressDialog2;
            this.update_message_list = update_message_list;
            this.update_friend_list = update_friend_list;
            this.dialog_text = dialog_text;
            this.send_to_peer = send_to_peer;
        }

        @Override
        protected String doInBackground(Void... voids)
        {
            // sort ascending (lowest ID on top)
            Collections.sort(selected_messages, new Comparator<Long>()
            {
                public int compare(Long o1, Long o2)
                {
                    return o1.compareTo(o2);
                }
            });
            Iterator i = selected_messages.iterator();

            while (i.hasNext())
            {
                try
                {
                    long mid = (Long) i.next();
                    final Message m_to_delete = (Message) orma.selectFromMessage().idEq(mid).get(0);

                    // KHANDAQ (#179/#205): retract own messages on the peer too (KQ delete packet;
                    // old clients drop it silently → local-only against them). Must run BEFORE the
                    // row disappears — the packet is built from msg_idv3_hash. Skipped for «Удалить у меня».
                    if (send_to_peer)
                    {
                        try
                        {
                            HelperMessageDelete.sendOwnFriendDelete(m_to_delete);
                        }
                        catch (Exception ignored)
                        {
                        }
                    }

                    // ---------- delete fileDB if this message is an outgoing file ----------
                    if (m_to_delete.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                    {
                        if (m_to_delete.direction == 1)
                        {
                            try
                            {
                                // cleanup duplicated outgoing files from provider here ************
                                if (m_to_delete.storage_frame_work == false)
                                {
                                    if (m_to_delete.filename_fullpath.startsWith(SD_CARD_FILES_OUTGOING_WRAPPER_DIR))
                                    {
                                        // HINT: real file (no storage framework) and correct directory, delete the file now
                                        try
                                        {
                                            new File(m_to_delete.filename_fullpath).delete();
                                        }
                                        catch (Exception e)
                                        {
                                        }
                                    }
                                }
                                // FileDB file_ = orma.selectFromFileDB().idEq(m_to_delete.filedb_id).get(0);
                                orma.deleteFromFileDB().idEq(m_to_delete.filedb_id).execute();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                                HelperGeneric.logI(TAG, "delete_selected_messages_asynchtask:EE4:" + e.getMessage());
                            }
                        }
                    }
                    // ---------- delete fileDB if this message is an outgoing file ----------

                    // ---------- delete fileDB and VFS file if this message is an incoming file ----------
                    if (m_to_delete.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                    {
                        if (m_to_delete.direction == 0)
                        {
                            try
                            {
                                FileDB file_ = (FileDB) orma.selectFromFileDB().idEq(m_to_delete.filedb_id).get(0);

                                try
                                {
                                    info.guardianproject.iocipher.File f_vfs = new info.guardianproject.iocipher.File(
                                            file_.path_name + "/" + file_.file_name);

                                    if (f_vfs.exists())
                                    {
                                        f_vfs.delete();
                                    }
                                }
                                catch (Exception e6)
                                {
                                    e6.printStackTrace();
                                    HelperGeneric.logI(TAG, "delete_selected_messages_asynchtask:EE5:" + e6.getMessage());
                                }

                                orma.deleteFromFileDB().idEq(m_to_delete.filedb_id).execute();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                                HelperGeneric.logI(TAG, "delete_selected_messages_asynchtask:EE4:" + e.getMessage());
                            }
                        }
                    }

                    // ---------- delete fileDB and VFS file if this message is an incoming file ----------

                    // ---------- delete the message itself ----------
                    try
                    {
                        long message_id_to_delete = m_to_delete.id;

                        try
                        {
                            if (update_message_list)
                            {
                                Runnable myRunnable = new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        try
                                        {
                                            MainActivity.message_list_fragment.adapter.remove_item(m_to_delete);
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

                            // let message delete animation finish (maybe use yet another asynctask here?) ------------
                            try
                            {
                                if (update_message_list)
                                {
                                    Thread.sleep(50);
                                }
                            }
                            catch (Exception sleep_ex)
                            {
                                sleep_ex.printStackTrace();
                            }

                            // let message delete animation finish (maybe use yet another asynctask here?) ------------
                            orma.deleteFromMessage().idEq(message_id_to_delete).execute();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                            HelperGeneric.logI(TAG, "delete_selected_messages_asynchtask:EE1:" + e.getMessage());
                        }
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                        HelperGeneric.logI(TAG, "delete_selected_messages_asynchtask:EE2:" + e2.getMessage());
                    }

                    // ---------- delete the message itself ----------
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                    HelperGeneric.logI(TAG, "delete_selected_messages_asynchtask:EE3:" + e2.getMessage());
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(String result)
        {
            selected_messages.clear();
            selected_messages_incoming_file.clear();
            selected_messages_text_only.clear();

            try
            {
                progressDialog2.dismiss();
                Context c = weakContext.get();
                Toast.makeText(c, R.string.MainActivity_toast_msg_deleted, Toast.LENGTH_SHORT).show();
            }
            catch (Exception e4)
            {
                e4.printStackTrace();
                HelperGeneric.logI(TAG, "save_selected_messages_asynchtask:EE3:" + e4.getMessage());
            }
        }

        @Override
        protected void onPreExecute()
        {
            super.onPreExecute();

            if (this.progressDialog2 == null)
            {
                try
                {
                    Context c = weakContext.get();
                    progressDialog2 = ProgressDialog.show(c, "", dialog_text);
                    progressDialog2.setCanceledOnTouchOutside(false);
                    progressDialog2.setOnCancelListener(new DialogInterface.OnCancelListener()
                    {
                        @Override
                        public void onCancel(DialogInterface dialog)
                        {
                        }
                    });
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "onPreExecute:start:EE:" + e.getMessage());
                }
            }
        }
    }

    static class save_selected_messages_asynchtask extends AsyncTask<Void, Void, String>
    {
        ProgressDialog progressDialog2;
        private WeakReference<Context> weakContext;
        private String export_directory = "";

        save_selected_messages_asynchtask(Context c, ProgressDialog progressDialog2)
        {
            this.weakContext = new WeakReference<>(c);
            this.progressDialog2 = progressDialog2;
        }

        @Override
        protected String doInBackground(Void... voids)
        {
            Iterator i = selected_messages_incoming_file.iterator();

            while (i.hasNext())
            {
                try
                {
                    long mid = (Long) i.next();
                    Message m = (Message) orma.selectFromMessage().idEq(mid).get(0);
                    FileDB file_ = (FileDB) orma.selectFromFileDB().idEq(m.filedb_id).get(0);
                    HelperGeneric.export_vfs_file_to_real_file(file_.path_name, file_.file_name,
                                                               SD_CARD_FILES_EXPORT_DIR + "/" + m.tox_friendpubkey +
                                                               "/", file_.file_name);

                    export_directory = SD_CARD_FILES_EXPORT_DIR + "/" + m.tox_friendpubkey + "/";
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                    HelperGeneric.logI(TAG, "save_selected_messages_asynchtask:EE1:" + e2.getMessage());
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(String result)
        {
            selected_messages.clear();
            selected_messages_incoming_file.clear();
            selected_messages_text_only.clear();

            try
            {
                // need to redraw all items again here, to remove the selections
                MainActivity.message_list_fragment.adapter.redraw_all_items();
            }
            catch (Exception e)
            {
                e.printStackTrace();
                HelperGeneric.logI(TAG, "save_selected_messages_asynchtask:EE2:" + e.getMessage());
            }

            try
            {
                progressDialog2.dismiss();
                Context c = weakContext.get();
                Toast.makeText(c, c.getString(R.string.files_exported_to, export_directory), Toast.LENGTH_SHORT).show();
            }
            catch (Exception e4)
            {
                e4.printStackTrace();
                HelperGeneric.logI(TAG, "save_selected_messages_asynchtask:EE3:" + e4.getMessage());
            }
        }

        @Override
        protected void onPreExecute()
        {
        }
    }

    static class save_selected_message_custom_asynchtask extends AsyncTask<Void, Void, String>
    {
        ProgressDialog progressDialog2;
        private final WeakReference<Context> weakContext;
        private final FileDB f;
        private final String fname;
        private final String export_display_name;
        private final View v;
        private final RecyclerView.Adapter<? extends RecyclerView.ViewHolder> a;
        private final int pos;

        save_selected_message_custom_asynchtask(Context c, ProgressDialog progressDialog2, FileDB file_,
                                                  String export_filename, String export_display_name,
                                                  RecyclerView.Adapter<? extends RecyclerView.ViewHolder> a,
                                                  int pos_in_adaper, View v)
        {
            this.weakContext = new WeakReference<>(c);
            this.progressDialog2 = progressDialog2;
            this.f = file_;
            this.fname = export_filename;
            this.export_display_name = export_display_name;
            this.v = v;
            this.a = a;
            this.pos = pos_in_adaper;
        }

        @Override
        protected String doInBackground(Void... voids)
        {
            final Context c = weakContext.get();
            if (c == null)
            {
                return null;
            }

            try
            {
                final String public_path = HelperGeneric.export_vfs_file_to_public_storage(
                        c, f.path_name, f.file_name, export_display_name);
                if ((public_path != null) && (!public_path.isEmpty()))
                {
                    return public_path;
                }
            }
            catch (Exception e)
            {
                HelperGeneric.logI(TAG, "save_selected_message_custom_asynchtask:public:EE:" + e.getMessage());
            }

            try
            {
                final String fallback_name = HelperGeneric.export_display_name_for_message(export_display_name,
                        f.file_name);
                HelperGeneric.export_vfs_file_to_real_file(f.path_name, f.file_name, fname, fallback_name);
                final java.io.File exported = new java.io.File(fname, fallback_name);
                if (exported.exists() && exported.isFile() && (exported.length() > 0))
                {
                    return exported.getAbsolutePath();
                }
            }
            catch (Exception e)
            {
                HelperGeneric.logI(TAG, "save_selected_message_custom_asynchtask:fallback:EE:" + e.getMessage());
            }

            return null;
        }

        @Override
        protected void onPostExecute(String result)
        {
            try
            {
                a.notifyItemChanged(pos);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            try
            {
                progressDialog2.dismiss();
                Context c = weakContext.get();
                if (c == null)
                {
                    return;
                }

                if ((result == null) || (result.isEmpty()))
                {
                    display_toast_with_context_custom_duration(c, "Не удалось сохранить файл", 3500, 0);
                }
                else if (result.contains("/Khandaq/") ||
                         result.startsWith(Environment.DIRECTORY_PICTURES) ||
                         result.startsWith(Environment.DIRECTORY_DOWNLOADS) ||
                         result.startsWith(Environment.DIRECTORY_MOVIES))
                {
                    display_toast_with_context_custom_duration(c, "Сохранено:\n" + result, 4500, 0);
                }
                else
                {
                    display_toast_with_context_custom_duration(c, "Сохранено (папка приложения):\n" + result, 4500, 0);
                }
            }
            catch (Exception e4)
            {
                e4.printStackTrace();
                HelperGeneric.logI(TAG, "save_selected_message_custom_asynchtask:EE3:" + e4.getMessage());
            }
        }

        @Override
        protected void onPreExecute()
        {
        }
    }

    static class save_selected_group_message_custom_asynchtask extends AsyncTask<Void, Void, String>
    {
        private final WeakReference<Context> weakContext;
        private final String saved_path;
        private final String saved_file;
        private final String fname;
        private final View v;
        private ProgressDialog progressDialog2;

        save_selected_group_message_custom_asynchtask(Context c, String saved_path, String saved_file,
                                                      String export_dst_pathname, View v)
        {
            this.weakContext = new WeakReference<>(c);
            this.saved_path = saved_path;
            this.saved_file = saved_file;
            this.fname = export_dst_pathname;
            this.v = v;
        }

        @Override
        protected String doInBackground(Void... voids)
        {
            final Context c = weakContext.get();
            if (c == null)
            {
                return null;
            }

            try
            {
                final String public_path = HelperGeneric.export_vfs_file_to_public_storage(c, saved_path, saved_file);
                if ((public_path != null) && (!public_path.isEmpty()))
                {
                    return public_path;
                }
            }
            catch (Exception e)
            {
                HelperGeneric.logI(TAG, "save_selected_group_message_custom_asynchtask:public:EE:" + e.getMessage());
            }

            try
            {
                HelperGeneric.export_vfs_file_to_real_file(saved_path, saved_file, fname, saved_file);
                final java.io.File f = new java.io.File(fname, saved_file);
                if (f.exists() && f.isFile() && (f.length() > 0))
                {
                    return f.getAbsolutePath();
                }
            }
            catch (Exception e)
            {
                HelperGeneric.logI(TAG, "save_selected_group_message_custom_asynchtask:fallback:EE:" + e.getMessage());
            }

            return null;
        }

        @Override
        protected void onPostExecute(String result)
        {
            try
            {
                if (progressDialog2 != null)
                {
                    progressDialog2.dismiss();
                }
            }
            catch (Exception ignored)
            {
            }

            try
            {
                Context c = weakContext.get();
                if (c == null)
                {
                    return;
                }

                if ((result == null) || (result.isEmpty()))
                {
                    display_toast_with_context_custom_duration(c,
                            "Не удалось сохранить файл", 3500, 0);
                }
                else if (result.contains("/Khandaq/") ||
                         result.startsWith(Environment.DIRECTORY_PICTURES) ||
                         result.startsWith(Environment.DIRECTORY_DOWNLOADS) ||
                         result.startsWith(Environment.DIRECTORY_MOVIES))
                {
                    display_toast_with_context_custom_duration(c,
                            "Сохранено:\n" + result, 4500, 0);
                }
                else
                {
                    display_toast_with_context_custom_duration(c,
                            "Сохранено (папка приложения):\n" + result, 4500, 0);
                }
            }
            catch (Exception e4)
            {
                e4.printStackTrace();
                HelperGeneric.logI(TAG, "save_selected_group_message_custom_asynchtask:EE3:" + e4.getMessage());
            }
        }

        @Override
        protected void onPreExecute()
        {
            try
            {
                Context c = weakContext.get();
                if (c != null)
                {
                    progressDialog2 = ProgressDialog.show(c, "", "Сохранение...");
                    progressDialog2.setCanceledOnTouchOutside(false);
                }
            }
            catch (Exception ignored)
            {
            }
        }
    }

    static class delete_selected_conference_messages_asynchtask extends AsyncTask<Void, Void, String>
    {
        ProgressDialog progressDialog2;
        private WeakReference<Context> weakContext;
        boolean update_conf_message_list = false;
        String dialog_text = "";

        delete_selected_conference_messages_asynchtask(Context c, ProgressDialog progressDialog2, boolean update_conf_message_list, String dialog_text)
        {
            this.weakContext = new WeakReference<>(c);
            this.progressDialog2 = progressDialog2;
            this.update_conf_message_list = update_conf_message_list;
            this.dialog_text = dialog_text;
        }

        @Override
        protected String doInBackground(Void... voids)
        {
            // sort ascending (lowest ID on top)
            Collections.sort(selected_conference_messages, new Comparator<Long>()
            {
                public int compare(Long o1, Long o2)
                {
                    return o1.compareTo(o2);
                }
            });
            Iterator i = selected_conference_messages.iterator();

            while (i.hasNext())
            {
                try
                {
                    long mid = (Long) i.next();
                    final ConferenceMessage m_to_delete = (ConferenceMessage) orma.selectFromConferenceMessage().idEq(mid).get(0);

                    // ---------- delete the message itself ----------
                    try
                    {
                        long message_id_to_delete = m_to_delete.id;

                        try
                        {
                            if (update_conf_message_list)
                            {
                                Runnable myRunnable = new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        try
                                        {
                                            MainActivity.conference_message_list_fragment.adapter.remove_item(
                                                    m_to_delete);
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

                            // let message delete animation finish (maybe use yet another asynctask here?) ------------
                            try
                            {
                                if (update_conf_message_list)
                                {
                                    Thread.sleep(50);
                                }
                            }
                            catch (Exception sleep_ex)
                            {
                                sleep_ex.printStackTrace();
                            }

                            // let message delete animation finish (maybe use yet another asynctask here?) ------------
                            orma.deleteFromConferenceMessage().idEq(message_id_to_delete).execute();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                            HelperGeneric.logI(TAG, "delete_selected_conference_messages_asynchtask:EE1:" + e.getMessage());
                        }
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                        HelperGeneric.logI(TAG, "delete_selected_conference_messages_asynchtask:EE2:" + e2.getMessage());
                    }

                    // ---------- delete the message itself ----------
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                    HelperGeneric.logI(TAG, "delete_selected_conference_messages_asynchtask:EE3:" + e2.getMessage());
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(String result)
        {
            selected_conference_messages.clear();

            try
            {
                progressDialog2.dismiss();
                Context c = weakContext.get();
                Toast.makeText(c, R.string.MainActivity_toast_msgs_deleted, Toast.LENGTH_SHORT).show();
            }
            catch (Exception e4)
            {
                e4.printStackTrace();
                HelperGeneric.logI(TAG, "delete_selected_conference_messages_asynchtask:EE3:" + e4.getMessage());
            }
        }

        @Override
        protected void onPreExecute()
        {
            super.onPreExecute();

            if (this.progressDialog2 == null)
            {
                try
                {
                    Context c = weakContext.get();
                    progressDialog2 = ProgressDialog.show(c, "", dialog_text);
                    progressDialog2.setCanceledOnTouchOutside(false);
                    progressDialog2.setOnCancelListener(new DialogInterface.OnCancelListener()
                    {
                        @Override
                        public void onCancel(DialogInterface dialog)
                        {
                        }
                    });
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "onPreExecute:start:EE:" + e.getMessage());
                }
            }
        }
    }

    static class delete_selected_group_messages_asynchtask extends AsyncTask<Void, Void, String>
    {
        ProgressDialog progressDialog2;
        private WeakReference<Context> weakContext;
        boolean update_group_message_list = false;
        String dialog_text = "";

        delete_selected_group_messages_asynchtask(Context c, ProgressDialog progressDialog2, boolean update_group_message_list, String dialog_text)
        {
            this.weakContext = new WeakReference<>(c);
            this.progressDialog2 = progressDialog2;
            this.update_group_message_list = update_group_message_list;
            this.dialog_text = dialog_text;
        }

        @Override
        protected String doInBackground(Void... voids)
        {
            // sort ascending (lowest ID on top)
            Collections.sort(selected_group_messages, new Comparator<Long>()
            {
                public int compare(Long o1, Long o2)
                {
                    return o1.compareTo(o2);
                }
            });
            Iterator i = selected_group_messages.iterator();

            while (i.hasNext())
            {
                try
                {
                    long mid = (Long) i.next();
                    final GroupMessage m_to_delete = (GroupMessage) orma.selectFromGroupMessage().idEq(mid).get(0);

                    // KHANDAQ (#179): broadcast retraction of own group text messages (best effort,
                    // reaches currently-online peers; old clients drop the packet silently)
                    try
                    {
                        HelperMessageDelete.sendOwnGroupDelete(m_to_delete);
                    }
                    catch (Exception ignored)
                    {
                    }

                    // ---------- delete file if this message is an outgoing file ----------
                    if (m_to_delete.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                    {
                        if (m_to_delete.direction == 1)
                        {
                            try
                            {
                                // cleanup duplicated outgoing files from provider here ************
                                if (m_to_delete.storage_frame_work == false)
                                {
                                    if (m_to_delete.filename_fullpath.startsWith(SD_CARD_FILES_OUTGOING_WRAPPER_DIR))
                                    {
                                        // HINT: real file (no storage framework) and correct directory, delete the file now
                                        try
                                        {
                                            new File(m_to_delete.filename_fullpath).delete();
                                        }
                                        catch (Exception e)
                                        {
                                        }
                                    }
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                                HelperGeneric.logI(TAG, "delete_selected_messages_asynchtask:EE4:" + e.getMessage());
                            }
                        }
                    }
                    // ---------- delete file if this message is an outgoing file ----------

                    // ---------- delete VFS file if this message is an incoming file ----------
                    if (m_to_delete.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                    {
                        if (m_to_delete.direction == 0)
                        {
                            try
                            {
                                info.guardianproject.iocipher.File f_vfs = new info.guardianproject.iocipher.File(
                                        m_to_delete.path_name + "/" + m_to_delete.file_name);

                                if (f_vfs.exists())
                                {
                                    f_vfs.delete();
                                }
                            }
                            catch (Exception e6)
                            {
                                e6.printStackTrace();
                                HelperGeneric.logI(TAG, "delete_selected_messages_asynchtask:EE5:" + e6.getMessage());
                            }
                        }
                    }
                    // ---------- delete VFS file if this message is an incoming file ----------

                    // ---------- delete the message itself ----------
                    try
                    {
                        long message_id_to_delete = m_to_delete.id;

                        try
                        {
                            if (update_group_message_list)
                            {
                                Runnable myRunnable = new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        try
                                        {
                                            MainActivity.group_message_list_fragment.adapter.remove_item(m_to_delete);
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

                            // let message delete animation finish (maybe use yet another asynctask here?) ------------
                            try
                            {
                                if (update_group_message_list)
                                {
                                    Thread.sleep(50);
                                }
                            }
                            catch (Exception sleep_ex)
                            {
                                sleep_ex.printStackTrace();
                            }

                            // let message delete animation finish (maybe use yet another asynctask here?) ------------
                            orma.deleteFromGroupMessage().idEq(message_id_to_delete).execute();
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                            HelperGeneric.logI(TAG, "delete_selected_group_messages_asynchtask:EE1:" + e.getMessage());
                        }
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                        HelperGeneric.logI(TAG, "delete_selected_group_messages_asynchtask:EE2:" + e2.getMessage());
                    }

                    // ---------- delete the message itself ----------
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                    HelperGeneric.logI(TAG, "delete_selected_group_messages_asynchtask:EE3:" + e2.getMessage());
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(String result)
        {
            selected_group_messages.clear();

            try
            {
                progressDialog2.dismiss();
                Context c = weakContext.get();
                Toast.makeText(c, R.string.MainActivity_toast_msgs_deleted, Toast.LENGTH_SHORT).show();
            }
            catch (Exception e4)
            {
                e4.printStackTrace();
                HelperGeneric.logI(TAG, "delete_selected_group_messages_asynchtask:EE3:" + e4.getMessage());
            }
        }

        @Override
        protected void onPreExecute()
        {
            super.onPreExecute();

            if (this.progressDialog2 == null)
            {
                try
                {
                    Context c = weakContext.get();
                    progressDialog2 = ProgressDialog.show(c, "", dialog_text);
                    progressDialog2.setCanceledOnTouchOutside(false);
                    progressDialog2.setOnCancelListener(new DialogInterface.OnCancelListener()
                    {
                        @Override
                        public void onCancel(DialogInterface dialog)
                        {
                        }
                    });
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "onPreExecute:start:EE:" + e.getMessage());
                }
            }
        }
    }

    static class send_message_result
    {
        long msg_num;
        boolean msg_v2;
        String msg_hash_hex;
        String msg_hash_v3_hex;
        String raw_message_buf_hex;
        long error_num;
    }

    /*************************************************************************/
    /* this function now really sends a 1:1 to a friend (or a friends relay) */
    private void fadeInAndShowImage(final View img, long start_after_millis)
    {
        Animation fadeIn = new AlphaAnimation(0, 1);
        fadeIn.setInterpolator(new AccelerateInterpolator());
        fadeIn.setDuration(1000);
        fadeIn.setStartOffset(start_after_millis);
        fadeIn.setAnimationListener(new Animation.AnimationListener()
        {
            public void onAnimationEnd(Animation animation)
            {
            }

            public void onAnimationRepeat(Animation animation)
            {
            }

            public void onAnimationStart(Animation animation)
            {
                img.setVisibility(View.VISIBLE);
            }
        });
        img.startAnimation(fadeIn);
    }

    private void fadeOutAndHideImage(final View img, long start_after_millis)
    {
        Animation fadeOut = new AlphaAnimation(1, 0);
        fadeOut.setInterpolator(new AccelerateInterpolator());
        fadeOut.setDuration(1000);
        fadeOut.setStartOffset(start_after_millis);
        fadeOut.setAnimationListener(new Animation.AnimationListener()
        {
            public void onAnimationEnd(Animation animation)
            {
                img.setVisibility(View.GONE);
            }

            public void onAnimationRepeat(Animation animation)
            {
            }

            public void onAnimationStart(Animation animation)
            {
            }
        });
        img.startAnimation(fadeOut);
    }

    // --------- make app crash ---------
    // --------- make app crash ---------
    // --------- make app crash ---------
    public static void crash_app_java(int type)
    {
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======================+++++");
        System.out.println("+++++======= TYPE:J =======+++++");
        System.out.println("+++++======================+++++");

        if (type == 1)
        {
            Java_Crash_001();
        }
        else if (type == 2)
        {
            Java_Crash_002();
        }
        else
        {
            stackOverflow();
        }
    }

    public static void Java_Crash_001()
    {
        Integer i = null;
        i.byteValue();
    }

    public static void Java_Crash_002()
    {
        View v = null;
        v.bringToFront();
    }

    static byte[] cipherWriteRandomByteReturnBuf(int bytes, String filename) {
        byte[] random_buf;
        try {
            info.guardianproject.iocipher.File f = new info.guardianproject.iocipher.File(filename);
            info.guardianproject.iocipher.FileOutputStream out = new info.guardianproject.iocipher.FileOutputStream(f);

            Random prng = new Random();
            random_buf = new byte[bytes];
            prng.nextBytes(random_buf);

            out.write(random_buf);
            out.close();

        } catch (IOException e) {
            Log.e(TAG, e.getCause().toString());
            return null;
        }
        return random_buf;
    }

    public static void VFS_Append_test_001()
    {
        try {
            for(int i=0;i<3;i++)
            {
                String name = "/testFileManySizes_" + i;
                info.guardianproject.iocipher.File f = new info.guardianproject.iocipher.File(name);
                byte[] bufrandom = cipherWriteRandomByteReturnBuf(i, name);
                Log.v(TAG, "write: bytes=" + i);

                f = new info.guardianproject.iocipher.File(name);
                FileOutputStream out = new FileOutputStream(f, true);
                Log.v(TAG, "append: length:before=" + f.length());
                for(int k=0;k<2;k++)
                {
                    out.write(19);
                    Log.v(TAG, "append: length:+k=" + f.length());
                }
                out.close();

                f = new info.guardianproject.iocipher.File(name);
                byte[] orig_in = new byte[i];
                FileInputStream in = new FileInputStream(f);
                in.read(orig_in, 0, i);
                //*****//assertEquals(i + 2, f.length());
                for(int j=0;j<i;j++)
                {
                    //*****//assertEquals(bufrandom[j], orig_in[j]);
                }
                Log.v(TAG, "CMP: " + bytes_to_hex(bufrandom) + " <--> " + bytes_to_hex(orig_in));
                Log.v(TAG, "read: bytes=" + i + " OK");
                in.close();

                f = new info.guardianproject.iocipher.File(name);
                f.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, e.getCause().toString());
        }
    }

    public static void stackOverflow()
    {
        stackOverflow();
    }

    public static void crash_app_C()
    {
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======= CRASH  =======+++++");
        System.out.println("+++++======================+++++");
        System.out.println("+++++======= TYPE:C =======+++++");
        System.out.println("+++++======================+++++");
        AppCrashC();
    }

    public static native void AppCrashC();
    // --------- make app crash ---------
    // --------- make app crash ---------
    // --------- make app crash ---------

}


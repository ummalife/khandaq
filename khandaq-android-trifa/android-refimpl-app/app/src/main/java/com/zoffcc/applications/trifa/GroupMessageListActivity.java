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

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.DocumentsContract;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.github.piasy.rxandroidaudio.AudioRecorder;
import com.google.speech.levelmeter.BarLevelDrawable;
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.materialdrawer.AccountHeader;
import com.mikepenz.materialdrawer.AccountHeaderBuilder;
import com.mikepenz.materialdrawer.Drawer;
import com.mikepenz.materialdrawer.DrawerBuilder;
import com.mikepenz.materialdrawer.model.ProfileDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IDrawerItem;
import com.mikepenz.materialdrawer.model.interfaces.IProfile;
import com.vanniktech.emoji.EmojiPopup;
import com.vanniktech.emoji.listeners.OnEmojiBackspaceClickListener;
import com.vanniktech.emoji.listeners.OnEmojiPopupDismissListener;
import com.vanniktech.emoji.listeners.OnEmojiPopupShownListener;
import com.vanniktech.emoji.listeners.OnSoftKeyboardCloseListener;
import com.vanniktech.emoji.listeners.OnSoftKeyboardOpenListener;
import com.zoffcc.applications.nativeaudio.NativeAudio;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.GroupPeerDB;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import androidx.annotation.Px;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import static com.zoffcc.applications.nativeaudio.NativeAudio.get_vu_in;
import static com.zoffcc.applications.nativeaudio.NativeAudio.get_vu_out;
import static com.zoffcc.applications.nativeaudio.NativeAudio.na_set_audio_play_volume_percent;
import static com.zoffcc.applications.trifa.AudioReceiver.channels_;
import static com.zoffcc.applications.trifa.AudioReceiver.sampling_rate_;
import static com.zoffcc.applications.trifa.CallingActivity.audio_thread;
import static com.zoffcc.applications.trifa.CallingActivity.initializeScreenshotSecurity;
import static com.zoffcc.applications.trifa.CameraWrapper.YUV420rotate90;
import static com.zoffcc.applications.trifa.GroupMessageListFragment.group_search_messages_text;
import static com.zoffcc.applications.trifa.HelperFiletransfer.copy_outgoing_file_to_sdcard_dir;
import static com.zoffcc.applications.trifa.HelperFiletransfer.resolve_uri_file_size;
import static com.zoffcc.applications.trifa.HelperFriend.format_group_peer_list_display_name;
import static com.zoffcc.applications.trifa.HelperFriend.friendlist_has_avatar_for_pubkey;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.bytebuffer_to_hexstring;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.do_fade_anim_on_fab;
import static com.zoffcc.applications.trifa.HelperGeneric.fourbytes_of_long_to_hex;
import static com.zoffcc.applications.trifa.HelperGeneric.get_uniq_tmp_filename;
import static com.zoffcc.applications.trifa.HelperGeneric.seconds_time_format_or_empty;
import static com.zoffcc.applications.trifa.HelperGeneric.set_calling_audio_mode;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.get_group_peernum_from_peer_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.insert_into_group_message_db;
import static com.zoffcc.applications.trifa.HelperGroup.is_group_active;
import static com.zoffcc.applications.trifa.HelperGroup.is_group_we_left;
import static com.zoffcc.applications.trifa.HelperGroup.ngc_get_index_video_incoming_peer_list;
import static com.zoffcc.applications.trifa.HelperGroup.ngc_purge_video_incoming_peer_list;
import static com.zoffcc.applications.trifa.HelperGroup.ngc_set_video_call_icon;
import static com.zoffcc.applications.trifa.HelperGroup.ngc_set_video_info_text;
import static com.zoffcc.applications.trifa.HelperGroup.ngc_update_video_incoming_peer_list;
import static com.zoffcc.applications.trifa.HelperGroup.ngc_update_video_incoming_peer_list_ts;
import static com.zoffcc.applications.trifa.HelperGroup.send_group_file;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_name__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperMsgNotification.change_msg_notification;
import static com.zoffcc.applications.trifa.MainActivity.PREF__X_battery_saving_mode;
import static com.zoffcc.applications.trifa.MainActivity.PREF__audio_play_volume_percent;
import static com.zoffcc.applications.trifa.MainActivity.PREF__messageview_paging;
import static com.zoffcc.applications.trifa.MainActivity.PREF__ngc_video_bitrate;
import static com.zoffcc.applications.trifa.MainActivity.PREF__ngc_video_frame_delta_ms;
import static com.zoffcc.applications.trifa.MainActivity.PREF__ngc_video_max_quantizer;
import static com.zoffcc.applications.trifa.MainActivity.PREF__use_incognito_keyboard;
import static com.zoffcc.applications.trifa.MainActivity.PREF__window_security;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_TMP_DIR;
import static com.zoffcc.applications.trifa.MainActivity.SelectFriendSingleActivity_ID;
import static com.zoffcc.applications.trifa.MainActivity.audio_out_buffer_mult;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.lookup_peer_listnum_pubkey;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages_incoming_file;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages_text_only;
import static com.zoffcc.applications.trifa.HelperGroup.ensure_group_in_tox;
import static com.zoffcc.applications.trifa.HelperGroup.get_effective_group_title;
import static com.zoffcc.applications.trifa.HelperGroup.group_send_failure_reason;
import static com.zoffcc.applications.trifa.HelperGroup.group_send_precheck_failure_reason;
import static com.zoffcc.applications.trifa.HelperGroup.send_group_text_message_resilient;
import static com.zoffcc.applications.trifa.HelperGroup.GROUP_SEND_QUEUE_WHEN_UNCONNECTED;
import static com.zoffcc.applications.trifa.HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX;
import static com.zoffcc.applications.trifa.HelperGroup.schedule_pending_group_message_flush;
import static com.zoffcc.applications.trifa.HelperGroup.should_refresh_group_connect_header;
import static com.zoffcc.applications.trifa.HelperGroup.GroupMemberDisplay;
import static com.zoffcc.applications.trifa.HelperGroup.collect_group_members_for_display;
import static com.zoffcc.applications.trifa.HelperGroup.format_group_list_status_subtitle;
import static com.zoffcc.applications.trifa.HelperGroup.is_group_mesh_degraded;
import static com.zoffcc.applications.trifa.HelperGroup.lookup_group_peer_by_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.format_group_member_status_line;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_peerlist;
import static com.zoffcc.applications.trifa.HelperGroup.invite_friend_to_group;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_is_connected;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_offline_peer_count;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_count;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_connection_status;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_savedpeer_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_peer_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_packet;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_message;
import static com.zoffcc.applications.trifa.MainActivity.tox_max_message_length;
import static com.zoffcc.applications.trifa.MainActivity.toxav_ngc_audio_decode;
import static com.zoffcc.applications.trifa.MainActivity.toxav_ngc_audio_encode;
import static com.zoffcc.applications.trifa.MainActivity.toxav_ngc_video_decode;
import static com.zoffcc.applications.trifa.MainActivity.toxav_ngc_video_encode;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FT_OUTGOING_FILESIZE_BYTE_USE_STORAGE_FRAMEWORK;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FT_OUTGOING_FILESIZE_NGC_MAX_TOTAL;
import static com.zoffcc.applications.trifa.TRIFAGlobals.KHANDAQ_MAX_FILE_TRANSFER_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.HIGHER_NGC_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.HIGHER_NGC_VIDEO_QUANTIZER;
import static com.zoffcc.applications.trifa.TRIFAGlobals.INTERVAL_UPDATE_NGC_GROUP_ALL_USERS_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOWER_NGC_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOWER_NGC_VIDEO_QUANTIZER;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NGC_AUDIO_PCM_BUFFER_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NGC_AUDIO_PCM_BUFFER_SAMPLES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NGC_NEW_PEERS_TIMEDELTA_IN_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_REMOVE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TEXT_QUOTE_STRING_1;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TEXT_QUOTE_STRING_2;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_for_battery_savings_ts;
import static com.zoffcc.applications.trifa.ToxVars.MAX_GC_PACKET_CHUNK_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_HASH_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_FILESIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_VIDEO_AND_HEADER_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.TrifaToxService.wakeup_tox_thread;

public class GroupMessageListActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.GrpMsgLstActivity";
    static String group_id = "-1";
    String group_id_prev = "-1";
    //
    static com.vanniktech.emoji.EmojiEditText ml_new_group_message = null;
    private GroupMentionInputController groupMentionInputController = null;
    EmojiPopup emojiPopup = null;
    ImageView insert_emoji = null;
    TextView ml_maintext = null;
    ViewGroup rootView = null;
    //
    private static Thread NGC_Group_video_check_incoming_thread = null;
    private static boolean NGC_Group_video_check_incoming_thread_running = false;
    static Thread NGC_Group_video_play_thread = null;
    static Thread NGC_Group_audio_record_thread = null;
    private static boolean NGC_Group_video_play_thread_running = false;
    private static boolean NGC_Group_audio_record_thread_running = false;
    // KHANDAQ (security D-6): touched from the NGC video callback thread and the UI thread — a plain
    // HashMap here threw ConcurrentModificationException (DoS) during an active group video session.
    static Map<String, Long> lookup_ngc_incoming_video_peer_list = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    static int ngc_incoming_video_peer_toggle_current_index = 0;
    static int flush_decoder = 0;
    static long last_video_seq_num = -1;
    static BarLevelDrawable ngc_audio_bar_in_v = null;
    static BarLevelDrawable ngc_audio_bar_out_v = null;
    private Runnable pendingNgcPermissionAction = null;
    //
    static GroupGroupAudioService ngc_group_audio_service = null;
    public static String ngc_channelId = "";
    static NotificationChannel ngc_notification_channel_group_audio_play_service = null;
    static NotificationManager ngc_nmn3 = null;
    static BlockingQueue<byte[]> ngc_audio_in_queue = new LinkedBlockingQueue<byte[]>(3 * 5);
    static BlockingQueue<byte[]> ngc_audio_out_queue = new LinkedBlockingQueue<byte[]>(3 * 5);
    //
    ImageView ml_icon = null;
    ImageView ml_status_icon = null;
    static View ngc_video_view_container = null;
    static CustomVideoImageView ngc_video_view = null;
    static CustomVideoImageView ngc_video_own_view = null;
    ImageButton ngc_camera_toggle_button = null;
    ImageButton ngc_camera_next_button = null;
    ImageButton ngc_video_quality_toggle_button = null;
    ImageButton ngc_mute_button = null;
    ImageButton ngc_video_off_button = null;
    static TextView ngc_camera_info_text = null;
    static final int NGC_FRONT_CAMERA_USED = 1;
    static final int NGC_BACK_CAMERA_USED = 2;
    static int ngc_active_camera_type = NGC_BACK_CAMERA_USED;
    static boolean ngc_audio_mute = true;
    static boolean ngc_video_off = true;
    static final int NGC_VIDEO_ICON_STATE_INACTIVE = 0;
    static final int NGC_VIDEO_ICON_STATE_INCOMING = 1;
    static final int NGC_VIDEO_ICON_STATE_ACTIVE = 2;
    ImageButton ml_phone_icon = null;
    ImageButton ml_button_01 = null;
    ImageButton ml_button_recaudio = null;
    static ChatVoiceRecordingUiHelper voiceRecordingUiHelper = null;
    static boolean ml_is_recording = false;
    static boolean ml_is_rec_ok = false;
    static volatile String ml_voice_recording_temp_path = null;
    static ImageButton ml_video_icon = null;
    static boolean sending_video_to_group = false;
    static String ngc_video_showing_video_from_peer_pubkey = "-1";
    static long ngc_video_frame_last_incoming_ts = -1L;
    // KHANDAQ (security D-5): cap incoming NGC video processing to ~30 fps. A flooding peer otherwise
    // forces ~0.5 MB of allocations + a UI-thread Runnable per frame → ANR/jank.
    static long ngc_video_frame_last_processed_ts = 0L;
    static final long NGC_VIDEO_MIN_FRAME_INTERVAL_MS = 33L;
    static long ngc_video_packet_last_incoming_ts = -1L;
    static long ngc_audio_packet_last_incoming_ts = -1L;
    static Bitmap ngc_video_frame_image = null;
    static Bitmap ngc_own_video_frame_image = null;
    static Allocation ngc_alloc_in = null;
    static Allocation ngc_own_alloc_in = null;
    static Allocation ngc_alloc_out = null;
    static Allocation ngc_own_alloc_out = null;
    static ScriptIntrinsicYuvToRGB ngc_yuvToRgb = null;
    static ScriptIntrinsicYuvToRGB ngc_own_yuvToRgb = null;
    static boolean attachemnt_instead_of_send = true;
    static final int MEDIAPICK_ID_001 = 8004;
    // KHANDAQ: group attach now mirrors the 1:1 sheet (camera + media grid).
    static final int CAMERA_PHOTO_ID = 8026;
    static final int CAMERA_VIDEO_ID = 8027;
    // KHANDAQ (Figma): "Файл" attach — pick any document (not just media) and send it.
    static final int FILEPICK_ID = 8028;
    private Uri pending_camera_capture_uri = null;

    private boolean mediaPreviewResultHandled = false;
    static ActionMode amode = null;
    static MenuItem amode_save_menu_item = null;
    static MenuItem amode_info_menu_item = null;
    static boolean oncreate_finished = false;
    SearchView messageSearchView = null;
    private static long update_group_all_users_last_trigger_ts = 0;
    private String last_drawer_peers_fingerprint = "";
    private String last_drawer_status_fingerprint = "";
    //
    static long last_processed_camera_frame = -1;

    private CameraDevice mCameraDevice;
    private CameraCaptureSession mCaptureSession;
    private ImageReader mImageReader;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    // static byte[] y_buf__ = new byte[240 * 320];
    static byte[] y_buf__ = new byte[480 * 640];
    static byte[] u_buf__ = new byte[(480 / 2) * (640 / 2)];
    static byte[] v_buf__ = new byte[(480 / 2) * (640 / 2)];

    private static final int CAMERAX_NGC_IMAGE_WIDTH = 640;
    private static final int CAMERAX_NGC_IMAGE_HEIGHT = 480;
    private static final int CAMERAX_NGC_CAPTURE_MAX_IMAGES = 2;
    //


    // main drawer ----------
    Drawer group_message_drawer = null;
    AccountHeader group_message_drawer_header = null;
    ProfileDrawerItem group_message_profile_item = null;
    // long peers_in_list_next_num = 0;
    // main drawer ----------

    Handler group_handler = null;
    static Handler group_handler_s = null;
    private Runnable group_connect_header_refresh_runnable = null;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        oncreate_finished = false;
        HelperGeneric.logI(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        HelperGeneric.logI(TAG, "onCreate:002");

        // ---------- video stuff ----------
        final int ngc_frame_width_px = 480; // + 32; // 240 + 16;
        final int ngc_frame_height_px = 640; // 320;
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_video_frame_image = Bitmap.createBitmap(ngc_frame_width_px, ngc_frame_height_px, Bitmap.Config.ARGB_8888);
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        if (1 == 2 + 2)
        {
            RenderScript rs = RenderScript.create(this);
            ngc_yuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));
            Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(ngc_frame_width_px).setY(
                    ngc_frame_height_px);
            yuvType.setYuvFormat(ImageFormat.YV12);
            ngc_alloc_in = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);
            Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(ngc_frame_width_px).setY(
                    ngc_frame_height_px);
            ngc_alloc_out = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
        }
        //
        //
        final int ngc_own_frame_width_px = 480;
        final int ngc_own_frame_height_px = 640;
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_own_video_frame_image = Bitmap.createBitmap(ngc_own_frame_width_px, ngc_own_frame_height_px,
                                                        Bitmap.Config.ARGB_8888);
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        if (1 == 2 + 2)
        {
            RenderScript own_rs = RenderScript.create(this);
            ngc_own_yuvToRgb = ScriptIntrinsicYuvToRGB.create(own_rs, Element.U8_4(own_rs));
            Type.Builder own_yuvType = new Type.Builder(own_rs, Element.U8(own_rs)).setX(ngc_own_frame_width_px).setY(
                    ngc_own_frame_height_px);
            own_yuvType.setYuvFormat(ImageFormat.YV12);
            ngc_own_alloc_in = Allocation.createTyped(own_rs, own_yuvType.create(), Allocation.USAGE_SCRIPT);
            Type.Builder own_rgbaType = new Type.Builder(own_rs, Element.RGBA_8888(own_rs)).setX(
                    ngc_own_frame_width_px).setY(ngc_own_frame_height_px);
            ngc_own_alloc_out = Allocation.createTyped(own_rs, own_rgbaType.create(), Allocation.USAGE_SCRIPT);
        }
        //
        //
        sending_video_to_group = false;
        // ---------- video stuff ----------

        amode = null;
        amode_save_menu_item = null;
        amode_info_menu_item = null;
        selected_group_messages.clear();
        selected_group_messages_text_only.clear();
        selected_group_messages_incoming_file.clear();

        try
        {
            // reset search and filter flags, sooner
            group_search_messages_text = null;
        }
        catch (Exception e)
        {
        }

        group_handler = new Handler(getMainLooper());
        group_handler_s = group_handler;

        Intent intent = getIntent();
        group_id = intent.getStringExtra("group_id");
        if (savedInstanceState != null)
        {
            final String saved_group_id = savedInstanceState.getString("saved_group_id");
            if ((saved_group_id != null) && (!saved_group_id.equals("-1")))
            {
                group_id = saved_group_id;
            }
        }
        // HelperGeneric.logI(TAG, "onCreate:003:conf_id=" + conf_id + " conf_id_prev=" + conf_id_prev);
        group_id_prev = group_id;

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        setContentView(R.layout.activity_group_message_list);
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        MainActivity.group_message_list_activity = this;

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        final Drawable drawer_header_icon = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_group).color(
                getResources().getColor(R.color.md_dark_primary_text)).sizeDp(100);

        group_message_profile_item = new ProfileDrawerItem().withName(
                getString(R.string.group_member_list_title)).withIcon(drawer_header_icon);

        // Create the AccountHeader
        group_message_drawer_header = new AccountHeaderBuilder().withActivity(
                this).withSelectionListEnabledForSingleProfile(false).withTextColor(
                getResources().getColor(R.color.md_dark_primary_text)).withHeaderBackground(
                R.color.colorHeader).withCompactStyle(true).addProfiles(
                group_message_profile_item).withOnAccountHeaderListener(new AccountHeader.OnAccountHeaderListener()
        {
            @Override
            public boolean onProfileChanged(View view, IProfile profile, boolean currentProfile)
            {
                return false;
            }
        }).build();

        //        PrimaryDrawerItem item1 = new PrimaryDrawerItem().withIdentifier(1).
        //                withIdentifier(1L).
        //                withName("User1").
        //                withIcon(GoogleMaterial.Icon.gmd_face);


        // create the drawer and remember the `Drawer` result object
        group_message_drawer = new DrawerBuilder().withActivity(this).withAccountHeader(
                group_message_drawer_header).withInnerShadow(false).withRootView(
                R.id.drawer_container).withShowDrawerOnFirstLaunch(false).withTranslucentStatusBar(
                false).withOnDrawerItemClickListener(new Drawer.OnDrawerItemClickListener()
        {
            @Override
            public boolean onItemClick(View view, int position, IDrawerItem drawerItem)
            {
                HelperGeneric.logI(TAG, "drawer:item=" + position);
                if (position == 1)
                {
                    // profile
                    try
                    {
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                return true;
            }
        }).build();

        HelperToolbar.enableUpNavigation(this, toolbar);

        try
        {
            final androidx.recyclerview.widget.RecyclerView drawer_list = group_message_drawer.getRecyclerView();
            if (drawer_list != null)
            {
                drawer_list.setVerticalScrollBarEnabled(true);
                drawer_list.setNestedScrollingEnabled(true);
            }
        }
        catch (Exception ignored)
        {
        }

        rootView = (ViewGroup) findViewById(R.id.emoji_bar);
        ml_new_group_message = (com.vanniktech.emoji.EmojiEditText) findViewById(R.id.ml_new_message);
        HelperGeneric.apply_chat_input_field_style(ml_new_group_message);
        ChatReplyPreviewController.bind(this, ml_new_group_message);
        groupMentionInputController = new GroupMentionInputController();
        groupMentionInputController.bind(this, ml_new_group_message, findViewById(R.id.emoji_bar),
                () -> group_id);

        messageSearchView = (SearchView) findViewById(R.id.group_search_view_messages);
        messageSearchView.setQueryHint(getString(R.string.messages_search_default_text));
        // KHANDAQ: search lives in the ⋮ overflow now; it is hidden until revealed (enterGroupSearchMode).
        messageSearchView.setIconifiedByDefault(false);
        messageSearchView.setOnCloseListener(() ->
        {
            exitGroupSearchMode();
            return false;
        });

        try
        {
            // reset search and filter flags
            messageSearchView.setQuery("", false);
            group_search_messages_text = null;
        }
        catch (Exception e)
        {
        }

        // give focus to text input
        ml_new_group_message.requestFocus();
        try
        {
            if ((group_id != null) && (!group_id.equals("-1")))
            {
                final String draft = ChatDraftHelper.load_group_draft(group_id);
                if ((draft != null) && (!draft.isEmpty()))
                {
                    ml_new_group_message.setText(draft);
                    ml_new_group_message.setSelection(draft.length());
                }
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            // hide softkeyboard initially
            // since it takes a lot of screen space
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        catch (Exception e)
        {
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        insert_emoji = (ImageView) findViewById(R.id.insert_emoji);
        ml_maintext = (TextView) findViewById(R.id.ml_maintext);
        ChatBubbleUiHelper.apply_chat_input_bar_theme(findViewById(R.id.emoji_bar));
        ml_icon = (ImageView) findViewById(R.id.ml_icon);
        ml_status_icon = (ImageView) findViewById(R.id.ml_status_icon);
        ml_phone_icon = (ImageButton) findViewById(R.id.ml_phone_icon);
        ml_video_icon = (ImageButton) findViewById(R.id.ml_video_icon);
        apply_chat_header();
        ngc_video_view_container = findViewById(R.id.ngc_video_view_container);
        ngc_video_view = findViewById(R.id.ngc_video_view);
        ngc_video_own_view = findViewById(R.id.ngc_video_own_view);
        ngc_camera_toggle_button = (ImageButton) findViewById(R.id.ngc_camera_toggle_button);
        ngc_camera_next_button = (ImageButton) findViewById(R.id.ngc_camera_next_button);
        ngc_video_quality_toggle_button = (ImageButton) findViewById(R.id.ngc_video_quality_toggle_button);
        ngc_mute_button = (ImageButton) findViewById(R.id.ngc_mute_button);
        ngc_video_off_button = (ImageButton) findViewById(R.id.ngc_video_off_button);
        ngc_camera_info_text = findViewById(R.id.ngc_camera_info_text);
        ml_button_01 = (ImageButton) findViewById(R.id.ml_button_01);
        ml_button_recaudio = (ImageButton) findViewById(R.id.ml_button_recaudio);
        ngc_audio_bar_in_v = (BarLevelDrawable) findViewById(R.id.ngc_audio_bar_in_v);
        ngc_audio_bar_out_v = (BarLevelDrawable) findViewById(R.id.ngc_audio_bar_out_v);
        ml_button_recaudio.setBackgroundColor(Color.TRANSPARENT);
        voiceRecordingUiHelper = ChatVoiceRecordingUiHelper.bind(findViewById(R.id.emoji_bar),
                new ChatVoiceRecordingUiHelper.RecordingActionListener()
                {
                    @Override
                    public void onSend()
                    {
                        signal_group_voice_recording_send();
                    }

                    @Override
                    public void onCancel()
                    {
                        signal_group_voice_recording_cancel();
                    }
                });

        ml_is_recording = false;
        ml_is_rec_ok = false;

        ngc_camera_info_text.setText("");

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_nmn3 = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
        {
            String channelName = "Tox NGC Group Audio Play";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            ngc_channelId = "trifa_ngc_audio_play";
            ngc_notification_channel_group_audio_play_service = new NotificationChannel(ngc_channelId, channelName,
                                                                                        importance);
            ngc_notification_channel_group_audio_play_service.setDescription(ngc_channelId);
            ngc_notification_channel_group_audio_play_service.setSound(null, null);
            ngc_notification_channel_group_audio_play_service.enableVibration(false);
            ngc_nmn3.createNotificationChannel(ngc_notification_channel_group_audio_play_service);
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        final Drawable d9 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_rotate_right).backgroundColor(
                Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(50);
        ngc_camera_next_button.setImageDrawable(d9);
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        // on startup always use back camera
        ngc_active_camera_type = NGC_BACK_CAMERA_USED;

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        if (ngc_active_camera_type == NGC_FRONT_CAMERA_USED)
        {
            final Drawable d5 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_camera_front).backgroundColor(
                    Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(50);
            ngc_camera_toggle_button.setImageDrawable(d5);
            HelperGeneric.logI(TAG, "ngc_active_camera_type(5)=" + ngc_active_camera_type);
        }
        else
        {
            final Drawable d6 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_camera_rear).backgroundColor(
                    Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(50);
            ngc_camera_toggle_button.setImageDrawable(d6);
            HelperGeneric.logI(TAG, "ngc_active_camera_type(6)=" + ngc_active_camera_type);
        }
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        PREF__ngc_video_bitrate = LOWER_NGC_VIDEO_BITRATE;
        if (PREF__ngc_video_bitrate == LOWER_NGC_VIDEO_BITRATE)
        {
            Drawable d2a = new IconicsDrawable(this).icon(
                    GoogleMaterial.Icon.gmd_photo_size_select_small).backgroundColor(Color.TRANSPARENT).color(
                    getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
            ngc_video_quality_toggle_button.setImageDrawable(d2a);
        }
        else
        {
            Drawable d2a = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_hd).backgroundColor(
                    Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
            ngc_video_quality_toggle_button.setImageDrawable(d2a);
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_camera_toggle_button.setOnTouchListener(new View.OnTouchListener()
        {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() != MotionEvent.ACTION_UP)
                {
                    HelperGeneric.logI(TAG, "active_camera_type(7)=" + ngc_active_camera_type);

                    if (ngc_active_camera_type == NGC_FRONT_CAMERA_USED)
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_camera_front).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.md_green_600)).sizeDp(7);
                        ngc_camera_toggle_button.setImageDrawable(d2a);
                    }
                    else
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_camera_rear).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.md_green_600)).sizeDp(7);
                        ngc_camera_toggle_button.setImageDrawable(d2a);
                    }
                }
                else
                {
                    HelperGeneric.logI(TAG, "ngc_active_camera_type(8)=" + ngc_active_camera_type);

                    if (ngc_active_camera_type == NGC_FRONT_CAMERA_USED)
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_camera_rear).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                        ngc_camera_toggle_button.setImageDrawable(d2a);
                    }
                    else
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_camera_front).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                        ngc_camera_toggle_button.setImageDrawable(d2a);
                    }

                    try
                    {
                        if (ngc_active_camera_type == NGC_FRONT_CAMERA_USED)
                        {
                            ngc_active_camera_type = NGC_BACK_CAMERA_USED;
                            HelperGeneric.logI(TAG, "ngc_active_camera_type(8a)=" + ngc_active_camera_type);
                        }
                        else
                        {
                            ngc_active_camera_type = NGC_FRONT_CAMERA_USED;
                            HelperGeneric.logI(TAG, "ngc_active_camera_type(8b)=" + ngc_active_camera_type);
                        }
                        closeCamera();

                        if (ngc_video_off == false)
                        {
                            openCamera();
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                return true;
            }
        });

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_camera_next_button.setOnTouchListener(new View.OnTouchListener()
        {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() != MotionEvent.ACTION_UP)
                {
                    Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                            GoogleMaterial.Icon.gmd_rotate_right).backgroundColor(Color.TRANSPARENT).color(
                            getResources().getColor(R.color.md_green_600)).sizeDp(7);
                    ngc_camera_next_button.setImageDrawable(d2a);
                }
                else
                {
                    Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                            GoogleMaterial.Icon.gmd_rotate_right).backgroundColor(Color.TRANSPARENT).color(
                            getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                    ngc_camera_next_button.setImageDrawable(d2a);

                    try
                    {
                        // toggle to next ngc peer with incoming video
                        if (ngc_incoming_video_peer_toggle_current_index < 1000)
                        {
                            ngc_incoming_video_peer_toggle_current_index++;
                        }
                        else
                        {
                            ngc_incoming_video_peer_toggle_current_index = 0;
                        }
                        flush_decoder = 1;
                        ngc_video_showing_video_from_peer_pubkey = ngc_get_index_video_incoming_peer_list(
                                ngc_incoming_video_peer_toggle_current_index);
                        ngc_video_frame_last_incoming_ts = System.currentTimeMillis();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                return true;
            }
        });

        // on startup always mute mic
        ngc_audio_mute = true;
        ngc_audio_bar_in_v.setLevel(0);

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        if (ngc_audio_mute == true)
        {
            final Drawable d5 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_mic_off).backgroundColor(
                    Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(50);
            ngc_mute_button.setImageDrawable(d5);
        }
        else
        {
            final Drawable d6 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_mic).backgroundColor(
                    Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(50);
            ngc_mute_button.setImageDrawable(d6);
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_mute_button.setOnTouchListener(new View.OnTouchListener()
        {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() != MotionEvent.ACTION_UP)
                {
                    if (ngc_audio_mute == true)
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_mic_off).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.md_green_600)).sizeDp(7);
                        ngc_mute_button.setImageDrawable(d2a);
                    }
                    else
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_mic).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.md_green_600)).sizeDp(7);
                        ngc_mute_button.setImageDrawable(d2a);
                    }
                }
                else
                {
                    if (ngc_audio_mute == true)
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_mic).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                        ngc_mute_button.setImageDrawable(d2a);
                    }
                    else
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_mic_off).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                        ngc_mute_button.setImageDrawable(d2a);
                    }

                    try
                    {
                        if (ngc_audio_mute == true)
                        {
                            ngc_audio_mute = false;
                            try
                            {
                                if (AudioRecording.stopped)
                                {
                                    audio_thread = new AudioRecording();
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                        else
                        {
                            ngc_audio_mute = true;
                            ngc_audio_bar_in_v.setLevel(0);
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                return true;
            }
        });

        ngc_video_off = true;
        if (ngc_video_off == true)
        {
            final Drawable d5 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_videocam_off).backgroundColor(
                    Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(50);
            ngc_video_off_button.setImageDrawable(d5);
        }
        else
        {
            final Drawable d6 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_videocam).backgroundColor(
                    Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(50);
            ngc_video_off_button.setImageDrawable(d6);
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_video_off_button.setOnTouchListener(new View.OnTouchListener()
        {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() != MotionEvent.ACTION_UP)
                {
                    if (ngc_video_off == true)
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_videocam_off).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.md_green_600)).sizeDp(7);
                        ngc_video_off_button.setImageDrawable(d2a);
                    }
                    else
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_videocam).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.md_green_600)).sizeDp(7);
                        ngc_video_off_button.setImageDrawable(d2a);
                    }
                }
                else
                {
                    if (ngc_video_off == true)
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_videocam).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                        ngc_video_off_button.setImageDrawable(d2a);
                    }
                    else
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_videocam_off).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                        ngc_video_off_button.setImageDrawable(d2a);
                    }

                    try
                    {
                        if (ngc_video_off == true)
                        {
                            ngc_video_off = false;
                            try
                            {
                                openCamera();
                            }
                            catch (Exception e)
                            {
                            }
                        }
                        else
                        {
                            ngc_video_off = true;
                            try
                            {
                                closeCamera();
                                // clear preview View
                                final Bitmap empty_bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
                                ngc_video_own_view.setBitmap(empty_bitmap);
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
                    }
                }
                return true;
            }
        });

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_video_quality_toggle_button.setOnTouchListener(new View.OnTouchListener()
        {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() != MotionEvent.ACTION_UP)
                {
                    if (PREF__ngc_video_bitrate == LOWER_NGC_VIDEO_BITRATE)
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_photo_size_select_small).backgroundColor(
                                Color.TRANSPARENT).color(getResources().getColor(R.color.md_green_600)).sizeDp(7);
                        ngc_video_quality_toggle_button.setImageDrawable(d2a);
                    }
                    else
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_hd).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.md_green_600)).sizeDp(7);
                        ngc_video_quality_toggle_button.setImageDrawable(d2a);
                    }
                }
                else
                {
                    if (PREF__ngc_video_bitrate == LOWER_NGC_VIDEO_BITRATE)
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_hd).backgroundColor(Color.TRANSPARENT).color(
                                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                        ngc_video_quality_toggle_button.setImageDrawable(d2a);
                    }
                    else
                    {
                        Drawable d2a = new IconicsDrawable(v.getContext()).icon(
                                GoogleMaterial.Icon.gmd_photo_size_select_small).backgroundColor(
                                Color.TRANSPARENT).color(getResources().getColor(R.color.colorPrimaryDark)).sizeDp(7);
                        ngc_video_quality_toggle_button.setImageDrawable(d2a);
                    }

                    try
                    {
                        if (PREF__ngc_video_bitrate == LOWER_NGC_VIDEO_BITRATE)
                        {
                            PREF__ngc_video_bitrate = HIGHER_NGC_VIDEO_BITRATE;
                            PREF__ngc_video_max_quantizer = HIGHER_NGC_VIDEO_QUANTIZER;
                            HelperGeneric.logI(TAG, "PREF__ngc_video_bitrate(8a)=" + PREF__ngc_video_bitrate);
                        }
                        else
                        {
                            PREF__ngc_video_bitrate = LOWER_NGC_VIDEO_BITRATE;
                            PREF__ngc_video_max_quantizer = LOWER_NGC_VIDEO_QUANTIZER;
                            HelperGeneric.logI(TAG, "PREF__ngc_video_bitrate(8b)=" + PREF__ngc_video_bitrate);
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
                return true;
            }
        });

        ml_phone_icon.setVisibility(View.GONE);
        ml_status_icon.setVisibility(View.INVISIBLE);

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        final Drawable d3 = new IconicsDrawable(this).icon(FontAwesome.Icon.faw_video).color(
                getResources().getColor(R.color.icon_colors)).sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);
        ml_video_icon.setImageDrawable(d3);
        ngc_video_view_container.setVisibility(View.GONE);

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ml_icon.setImageResource(R.drawable.circle_red);
        set_group_connection_status_icon();

        final View groupHeaderTap = findViewById(R.id.ml_header_group_info_tap);
        if (groupHeaderTap != null)
        {
            groupHeaderTap.setOnClickListener(v -> open_group_info_activity());
        }

        // KHANDAQ (Figma single-row header): group avatar = teal circle + white group glyph.
        final de.hdodenhof.circleimageview.CircleImageView groupAvatar = findViewById(R.id.ml_group_avatar);
        if (groupAvatar != null)
        {
            groupAvatar.setImageDrawable(new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_group)
                                                 .color(android.graphics.Color.WHITE).sizeDp(22));
        }

        final ImageButton membersIcon = (ImageButton) findViewById(R.id.ml_members_icon);
        if (membersIcon != null)
        {
            final Drawable membersDrawable = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_group).color(
                    getResources().getColor(R.color.icon_colors)).sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);
            membersIcon.setImageDrawable(membersDrawable);
            membersIcon.setOnClickListener(v ->
            {
                try
                {
                    if (group_message_drawer != null)
                    {
                        if (group_message_drawer.isDrawerOpen())
                        {
                            group_message_drawer.closeDrawer();
                        }
                        else
                        {
                            group_message_drawer.openDrawer();
                        }
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            });
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        messageSearchView.setOnQueryTextListener(new SearchView.OnQueryTextListener()
        {

            @Override
            public boolean onQueryTextSubmit(String query)
            {
                // HelperGeneric.logI(TAG, "search:1:" + query);

                if ((query == null) || (query.length() == 0))
                {
                    try
                    {
                        // all messages
                        group_search_messages_text = null;
                        MainActivity.group_message_list_fragment.update_all_messages(true, PREF__messageview_paging);
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                    }
                }
                else
                {
                    try
                    {
                        // all messages and search string
                        group_search_messages_text = query;
                        MainActivity.group_message_list_fragment.update_all_messages(true, PREF__messageview_paging);
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                    }
                }

                return false;
            }

            @Override
            public boolean onQueryTextChange(String query)
            {
                // HelperGeneric.logI(TAG, "search:2:" + query);

                if ((query == null) || (query.length() == 0))
                {
                    try
                    {
                        // all messages
                        group_search_messages_text = null;
                        MainActivity.group_message_list_fragment.update_all_messages(true, PREF__messageview_paging);
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                    }
                }
                else
                {
                    try
                    {
                        // all messages and search string
                        group_search_messages_text = query;
                        MainActivity.group_message_list_fragment.update_all_messages(true, PREF__messageview_paging);
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                    }
                }

                return true;
            }
        });

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        setUpEmojiPopup();

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        final Drawable d1 = new IconicsDrawable(getBaseContext()).icon(
                GoogleMaterial.Icon.gmd_sentiment_satisfied).color(getResources().getColor(R.color.icon_colors)).sizeDp(
                80);

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        insert_emoji.setImageDrawable(d1);

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        insert_emoji.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(final View v)
            {
                emojiPopup.toggle();
            }
        });

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        final Drawable add_attachement_icon = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_attachment).color(
                getResources().getColor(R.color.icon_colors)).sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);
        final Drawable send_message_icon = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_send).color(
                getResources().getColor(R.color.icon_colors)).sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);
        final Drawable mic_icon = getResources().getDrawable(R.drawable.baseline_keyboard_voice_24);

        ChatInputBarHelper.setupAttachButton(ml_button_01, add_attachement_icon, v -> send_attatchment(v));

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        if (PREF__use_incognito_keyboard)
        {
            ml_new_group_message.setImeOptions(
                    EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        else
        {
            ml_new_group_message.setImeOptions(EditorInfo.IME_ACTION_SEND);
        }

        if (PREF__window_security)
        {
            // prevent screenshots and also dont show the window content in recent activity screen
            initializeScreenshotSecurity(this);
        }

        ChatInputBarHelper.bindMicSendTextWatcher(ml_new_group_message, ml_button_recaudio, mic_icon, send_message_icon);

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        set_peer_count_header();
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        set_peer_names_and_avatars();
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        ml_button_recaudio.setOnLongClickListener(new View.OnLongClickListener()
        {
            @Override
            public boolean onLongClick(View v)
            {
                return start_group_voice_message_recording(v);
            }
        });
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        ml_button_recaudio.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(final View v)
            {
                if (ml_is_rec_ok)
                {
                    return;
                }

                if (ml_is_recording)
                {
                    signal_group_voice_recording_send();
                    return;
                }

                if (ChatInputBarHelper.isSendMode((ImageButton) v))
                {
                    send_message_onclick(null);
                    return;
                }

                start_group_voice_message_recording(v);
            }
        });

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        HelperGeneric.logI(TAG, "onCreate:099");
        oncreate_finished = true;
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    synchronized void set_peer_count_header()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        Thread t = new Thread()
        {
            @Override
            public void run()
            {
                if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
                final long conference_num = tox_group_by_groupid__wrapper(group_id);
                final String f_name = get_effective_group_title(conference_num, group_id);

                Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            final String members_subtitle = format_group_list_status_subtitle(
                                    GroupMessageListActivity.this, group_id);
                            if (members_subtitle != null && !members_subtitle.isEmpty())
                            {
                                ml_maintext.setText(f_name + "\n" + members_subtitle);
                            }
                            else if (tox_group_peer_count(conference_num) > -1)
                            {
                                ml_maintext.setText(f_name + "\n" + format_group_list_status_subtitle(
                                        GroupMessageListActivity.this, group_id));
                            }
                            else
                            {
                                ml_maintext.setText(f_name);
                            }
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
                if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
            }
        };
        t.start();
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    static class group_list_peer
    {
        String peer_name;
        String raw_name;
        long peer_num;
        String peer_pubkey;
        int peer_connection_status;
        boolean notification_silent;
        boolean self;
        boolean online;
        long last_seen_ms;
    }

    synchronized void set_peer_names_and_avatars()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        final List<GroupMemberDisplay> members = collect_group_members_for_display(group_id);
        final long conference_num = tox_group_by_groupid__wrapper(group_id);
        final java.util.HashMap<String, Long> online_peer_nums = new java.util.HashMap<>();
        final java.util.HashMap<String, Integer> online_connection_status = new java.util.HashMap<>();

        if (conference_num >= 0)
        {
            final long num_peers = tox_group_peer_count(conference_num);
            if (num_peers > 0)
            {
                final long[] peers = tox_group_get_peerlist(conference_num);
                if (peers != null)
                {
                    for (long peer : peers)
                    {
                        try
                        {
                            final String pubkey = tox_group_peer_get_public_key(conference_num, peer);
                            if (pubkey == null)
                            {
                                continue;
                            }
                            final String key = pubkey.toLowerCase();
                            online_peer_nums.put(key, peer);
                            online_connection_status.put(key,
                                    tox_group_peer_get_connection_status(conference_num, peer));
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                }
            }
        }

        final List<group_list_peer> all_peers = new ArrayList<>();
        for (final GroupMemberDisplay member : members)
        {
            final group_list_peer glp = new group_list_peer();
            glp.peer_pubkey = member.pubkey;
            glp.peer_name = member.name;
            glp.raw_name = member.name;
            glp.self = member.self;
            glp.online = member.online;
            glp.last_seen_ms = member.last_seen_ms;
            glp.peer_connection_status = member.connection_status;

            if (member.pubkey != null)
            {
                final Long peer_num = online_peer_nums.get(member.pubkey.toLowerCase());
                glp.peer_num = peer_num != null ? peer_num : Math.abs((long) member.pubkey.hashCode());
                final Integer conn = online_connection_status.get(member.pubkey.toLowerCase());
                if (conn != null)
                {
                    glp.peer_connection_status = conn;
                }
            }
            else
            {
                glp.peer_num = 0L;
            }

            final GroupPeerDB peer_from_db = lookup_group_peer_by_pubkey(group_id, member.pubkey);
            glp.notification_silent = peer_from_db != null && peer_from_db.notification_silent;
            all_peers.add(glp);
        }

        sync_group_drawer_peers(all_peers);
    }

    private void start_group_connect_header_refresh()
    {
        stop_group_connect_header_refresh();
        if (group_handler == null)
        {
            return;
        }
        group_connect_header_refresh_runnable = new Runnable()
        {
            @Override
            public void run()
            {
                set_peer_count_header();
                set_group_connection_status_icon();
                if (should_refresh_group_connect_header(group_id) && group_handler != null)
                {
                    group_handler.postDelayed(this, 2000L);
                }
            }
        };
        group_handler.postDelayed(group_connect_header_refresh_runnable, 2000L);
    }

    private void stop_group_connect_header_refresh()
    {
        if (group_handler != null && group_connect_header_refresh_runnable != null)
        {
            group_handler.removeCallbacks(group_connect_header_refresh_runnable);
        }
        group_connect_header_refresh_runnable = null;
    }

    @Override
    protected void onPause()
    {
        HelperGeneric.logI(TAG, "onPause");
        super.onPause();
        if (groupMentionInputController != null)
        {
            groupMentionInputController.dismiss();
        }
        stop_group_connect_header_refresh();

        ml_button_recaudio.setImageResource(R.drawable.baseline_keyboard_voice_24);
        ChatInputBarHelper.resetMicSendIcon(ml_button_recaudio,
                getResources().getDrawable(R.drawable.baseline_keyboard_voice_24));
        ml_button_recaudio.setBackgroundColor(Color.TRANSPARENT);
        stop_group_voice_recording_ui();
        ml_is_recording = false;
        ml_is_rec_ok = false;
        delete_group_voice_recording_temp_file();

        // HINT: here we leave the GroupMessageListActivity
        //       but it could be that we are starting the CallingActivity because there is an icoming call
        //       then this would reset the values for the incoming call :-(
        stop_group_video(this, !Callstate.incoming_one_on_one_call);
        closeCamera();
        MainActivity.group_message_list_activity = null;
        ngc_video_packet_last_incoming_ts = -1;
        NGC_Group_video_check_incoming_thread_running = false;
        try
        {
            NGC_Group_video_check_incoming_thread.interrupt();
        }
        catch (Exception e)
        {
        }
        ngc_purge_video_incoming_peer_list();
        ngc_incoming_video_peer_toggle_current_index = 0;
        flush_decoder = 1;
        try
        {
            if ((group_id != null) && (!group_id.equals("-1")))
            {
                ChatDraftHelper.save_group_draft(group_id, ml_new_group_message.getText().toString());
            }
        }
        catch (Exception ignored)
        {
        }
        // HelperGeneric.logI(TAG, "onPause:001:conf_id=" + conf_id);
        group_id = "-1";
        // HelperGeneric.logI(TAG, "onPause:002:conf_id=" + conf_id);
    }

    @Override
    protected void onStop()
    {
        try
        {
            na_set_audio_play_volume_percent(100);
        }
        catch (Exception ee)
        {
            ee.printStackTrace();
        }

        if (emojiPopup != null)
        {
            emojiPopup.dismiss();
        }
        super.onStop();
        ml_button_recaudio.setImageResource(R.drawable.baseline_keyboard_voice_24);
        ChatInputBarHelper.resetMicSendIcon(ml_button_recaudio,
                getResources().getDrawable(R.drawable.baseline_keyboard_voice_24));
        ml_button_recaudio.setBackgroundColor(Color.TRANSPARENT);
        ml_is_recording = false;
        ml_is_rec_ok = false;
        delete_group_voice_recording_temp_file();
    }

    void apply_chat_header()
    {
        ChatBubbleUiHelper.apply_chat_header_theme(
                (Toolbar) findViewById(R.id.toolbar),
                (android.view.View) null,
                findViewById(R.id.ml_header_group_info_tap),
                ml_maintext,
                ml_phone_icon,
                ml_video_icon,
                this);
    }

    @Override
    protected void onResume()
    {
        HelperGeneric.logI(TAG, "onResume");
        super.onResume();
        apply_chat_header();

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        // reset update trigger timestamp
        update_group_all_users_last_trigger_ts = 0;
        last_drawer_peers_fingerprint = "";
        last_drawer_status_fingerprint = "";

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        stop_group_video(this, true);
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        closeCamera();
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        ngc_video_packet_last_incoming_ts = -1;
        ngc_incoming_video_peer_toggle_current_index = 0;
        flush_decoder = 1;
        ngc_purge_video_incoming_peer_list();
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        try
        {
            na_set_audio_play_volume_percent(PREF__audio_play_volume_percent);
            HelperGeneric.logI(TAG, "NGC:PREF__audio_play_volume_percent=" + PREF__audio_play_volume_percent);
        }
        catch (Exception ee)
        {
            ee.printStackTrace();
        }
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        // HelperGeneric.logI(TAG, "onResume:001:conf_id=" + conf_id);

        if (group_id.equals("-1"))
        {
            group_id = group_id_prev;
            // HelperGeneric.logI(TAG, "onResume:001:conf_id=" + conf_id);
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        change_msg_notification(NOTIFICATION_EDIT_ACTION_REMOVE.value, group_id, null, null);
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        MainActivity.group_message_list_activity = this;
        wakeup_tox_thread();
        HelperGroup.on_group_chat_foreground(group_id);
        set_peer_count_header();
        start_group_connect_header_refresh();
        try
        {
            ensure_group_in_tox(group_id);
            set_group_connection_status_icon();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        try
        {
            update_group_all_users();
        }
        catch (Exception e)
        {
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        NGC_Group_video_check_incoming_thread = new Thread()
        {
            @Override
            public void run()
            {
                HelperGeneric.logI(TAG, "NGC_Group_video_check_incoming_thread:starting ...");
                HelperGeneric.logI(TAG, "NGC_Group_video_check_incoming_thread_running:true:003");
                while (NGC_Group_video_check_incoming_thread_running)
                {
                    try
                    {
                        // HelperGeneric.logI(TAG, "NGC_Group_video_check_incoming_thread:running --=> " + (ngc_video_packet_last_incoming_ts + (2 * 1000)) + " " + System.currentTimeMillis());
                        ngc_update_video_incoming_peer_list_ts();
                        if ((ngc_video_packet_last_incoming_ts + (2 * 1000)) < System.currentTimeMillis())
                        {
                            if (sending_video_to_group)
                            {
                            }
                            else
                            {
                                ngc_set_video_call_icon(NGC_VIDEO_ICON_STATE_INACTIVE);
                            }
                        }
                        else
                        {
                            if (sending_video_to_group)
                            {
                            }
                            else
                            {
                                ngc_set_video_call_icon(NGC_VIDEO_ICON_STATE_INCOMING);
                            }
                        }

                        if (sending_video_to_group)
                        {
                            String peer_name_txt = "";
                            if (!lookup_ngc_incoming_video_peer_list.isEmpty())
                            {
                                peer_name_txt = tox_group_peer_get_name__wrapper(group_id,
                                                                                 ngc_video_showing_video_from_peer_pubkey);
                                if ((peer_name_txt == null) || (peer_name_txt.equals("")) ||
                                    (peer_name_txt.equals("-1")))
                                {
                                    peer_name_txt = getString(R.string.group_peer_unknown);
                                }
                            }
                            if (lookup_ngc_incoming_video_peer_list.isEmpty())
                            {
                                ngc_set_video_info_text("streams:0");
                            }
                            else
                            {
                                ngc_set_video_info_text(
                                        "streams:" + lookup_ngc_incoming_video_peer_list.size() + "\n" + peer_name_txt);
                            }
                        }

                        try
                        {
                            Thread.sleep(2000);
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
                HelperGeneric.logI(TAG, "NGC_Group_video_check_incoming_thread:finished");
            }
        };
        NGC_Group_video_check_incoming_thread_running = true;
        NGC_Group_video_check_incoming_thread.start();

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState)
    {
        super.onSaveInstanceState(outState);
        final String id_to_save = group_id.equals("-1") ? group_id_prev : group_id;
        if ((id_to_save != null) && (!id_to_save.equals("-1")))
        {
            outState.putString("saved_group_id", id_to_save);
        }
    }

    static void signal_group_voice_recording_send()
    {
        ml_is_rec_ok = true;
        ml_is_recording = false;
        stop_group_voice_recording_ui();
    }

    static void signal_group_voice_recording_cancel()
    {
        ml_is_rec_ok = false;
        ml_is_recording = false;
        stop_group_voice_recording_ui();
    }

    static void stop_group_voice_recording_ui()
    {
        set_recording_pop_visibilty_s(false);
    }

    static void delete_group_voice_recording_temp_file()
    {
        final String path = ml_voice_recording_temp_path;
        ml_voice_recording_temp_path = null;
        if (path != null)
        {
            try
            {
                new File(path).delete();
            }
            catch (Exception ignored)
            {
            }
        }
    }

    static String resolve_group_id_for_voice_send()
    {
        if ((group_id != null) && (!group_id.equals("-1")))
        {
            return group_id;
        }
        if (MainActivity.group_message_list_activity != null)
        {
            final String prev = MainActivity.group_message_list_activity.group_id_prev;
            if ((prev != null) && (!prev.equals("-1")))
            {
                return prev;
            }
        }
        return "-1";
    }

    static void send_group_voice_recording_file(final Context context, final String audio_rec_filename_final)
    {
        if ((audio_rec_filename_final == null) || (audio_rec_filename_final.length() < 10))
        {
            return;
        }

        final String groupid = resolve_group_id_for_voice_send();
        if (groupid.equals("-1"))
        {
            HelperGeneric.logI(TAG, "send_group_voice_recording_file:no group id");
            return;
        }

        try
        {
            final File f2 = new File(audio_rec_filename_final);
            if (!waitForRecordedAudioFileReady(f2))
            {
                HelperGeneric.logI(TAG, "send_group_voice_recording_file:file not ready");
                display_toast(context.getString(R.string.group_file_prepare_failed), true, 100);
                return;
            }

            HelperGeneric.logI(TAG, "send_group_voice_recording_file:add to FT queue");
            HelperGeneric.logI(TAG, "send_group_voice_recording_file:file_size_in_bytes=" + f2.length());
            final GroupOutgoingFileHandle handle = add_outgoing_file(context, groupid, f2.getParent(), f2.getName(),
                    null, f2.length(), false, true, true);
            if (handle == null)
            {
                HelperGeneric.logI(TAG, "send_group_voice_recording_file:add_outgoing_file failed");
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "send_group_voice_recording_file:EE:" + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean waitForRecordedAudioFileReady(final File file)
    {
        if (file == null)
        {
            return false;
        }

        final long deadline = System.currentTimeMillis() + 3000L;
        long lastSize = -1L;
        while (System.currentTimeMillis() < deadline)
        {
            final long size = file.length();
            if ((size > 0L) && (size == lastSize))
            {
                return true;
            }
            lastSize = size;
            try
            {
                Thread.sleep(50L);
            }
            catch (InterruptedException ignored)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        return file.isFile() && file.length() > 0L;
    }

    boolean start_group_voice_message_recording(final View v)
    {
        if (ChatInputBarHelper.isSendMode((ImageButton) v))
        {
            return false;
        }

        if (ml_is_rec_ok)
        {
            return false;
        }

        if (ml_is_recording)
        {
            return false;
        }

        if (!HelperCall.hasMicrophonePermission(v.getContext()))
        {
            HelperCall.requestCallPermissions((Activity) v.getContext(), true);
            HelperCall.showMissingPermissionToast(v.getContext(), true);
            return false;
        }

        ChatVoiceSessionHelper.onVoiceRecordingStarting();

        final Thread ml_rec_audio_thread = new Thread()
        {
            @Override
            public void run()
            {
                String audio_rec_filename_final = null;
                ml_is_recording = true;
                ml_is_rec_ok = false;
                ml_voice_recording_temp_path = null;

                set_recording_pop_text_s("0:00");
                set_recording_pop_visibilty_s(true);

                try
                {
                    HelperGeneric.logI(TAG, "record_audio:start");
                    final AudioRecorder mAudioRecorder = AudioRecorder.getInstance();
                    File rec_dir = new File(SD_CARD_TMP_DIR);
                    if (!rec_dir.exists() && !rec_dir.mkdirs())
                    {
                        rec_dir = v.getContext().getCacheDir();
                    }
                    final String rec_base_dir = rec_dir.getAbsolutePath();
                    final String file_prefix = resolve_group_id_for_voice_send();
                    String audio_rec_filename = rec_base_dir + "/" + file_prefix + "_" + System.nanoTime();
                    String audio_rec_filename_uniq_part = get_uniq_tmp_filename(audio_rec_filename, 1000);
                    audio_rec_filename_final =
                            audio_rec_filename + "_" + audio_rec_filename_uniq_part + ".file.m4a";
                    File f = new File(audio_rec_filename_final);
                    boolean file_exists = true;
                    try
                    {
                        file_exists = f.exists();
                    }
                    catch (Exception e)
                    {
                        abort_group_voice_recording(false);
                        return;
                    }

                    long count = 0;
                    while (file_exists)
                    {
                        audio_rec_filename = rec_base_dir + "/" + file_prefix + "_" + System.nanoTime();
                        audio_rec_filename_uniq_part = get_uniq_tmp_filename(audio_rec_filename, 1000);
                        audio_rec_filename_final =
                                audio_rec_filename + "_" + audio_rec_filename_uniq_part + ".file.m4a";
                        f = new File(audio_rec_filename_final);
                        try
                        {
                            file_exists = f.exists();
                        }
                        catch (Exception e)
                        {
                            abort_group_voice_recording(false);
                            return;
                        }

                        try
                        {
                            Thread.sleep(50);
                        }
                        catch (Exception ignored)
                        {
                        }
                        count++;

                        if (count > 50)
                        {
                            abort_group_voice_recording(false);
                            return;
                        }
                    }

                    ml_voice_recording_temp_path = audio_rec_filename_final;
                    final File mAudioFile = new File(audio_rec_filename_final);
                    try
                    {
                        mAudioRecorder.prepareRecord(MediaRecorder.AudioSource.MIC,
                                                     MediaRecorder.OutputFormat.MPEG_4,
                                                     MediaRecorder.AudioEncoder.AAC, 44100, 20000,
                                                     mAudioFile);
                    }
                    catch (Exception prepare_error)
                    {
                        HelperGeneric.logI(TAG, "record_audio:prepare:EE:" + prepare_error.getMessage());
                        abort_group_voice_recording(false);
                        return;
                    }

                    if (!HelperCall.hasMicrophonePermission(v.getContext()))
                    {
                        abort_group_voice_recording(false);
                        return;
                    }

                    final boolean rec_start_result = mAudioRecorder.startRecord();
                    if (!rec_start_result)
                    {
                        abort_group_voice_recording(false);
                        return;
                    }

                    while (ml_is_recording)
                    {
                        try
                        {
                            Thread.sleep(200);
                        }
                        catch (Exception ignored)
                        {
                        }

                        if (!HelperCall.hasMicrophonePermission(v.getContext()))
                        {
                            ml_is_rec_ok = false;
                            ml_is_recording = false;
                            break;
                        }

                        set_recording_pop_text_s(seconds_time_format_or_empty(mAudioRecorder.progress()));
                    }

                    stop_group_voice_recording_ui();
                    final int rec_result = mAudioRecorder.stopRecord();
                    HelperGeneric.logI(TAG, "record_audio:finished:res=" + rec_result);
                    if (rec_result == -1)
                    {
                        ml_is_rec_ok = false;
                    }
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "record_audio:EE:" + e.getMessage());
                    e.printStackTrace();
                    ml_is_recording = false;
                    ml_is_rec_ok = false;
                }

                stop_group_voice_recording_ui();
                final boolean send_recording = ml_is_rec_ok;
                ml_is_recording = false;
                ml_is_rec_ok = false;

                if (send_recording)
                {
                    send_group_voice_recording_file(v.getContext(), audio_rec_filename_final);
                }

                try
                {
                    if ((audio_rec_filename_final != null) && (audio_rec_filename_final.length() > 10))
                    {
                        new File(audio_rec_filename_final).delete();
                    }
                }
                catch (Exception ignored)
                {
                }
                ml_voice_recording_temp_path = null;
            }
        };
        ml_rec_audio_thread.start();
        return true;
    }

    static void abort_group_voice_recording(final boolean send)
    {
        ml_is_rec_ok = send;
        ml_is_recording = false;
        stop_group_voice_recording_ui();
    }

    static void set_recording_pop_text_s(final String t)
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (voiceRecordingUiHelper != null)
                    {
                        voiceRecordingUiHelper.setTimerText(t);
                    }
                }
                catch (Exception e)
                {
                }
            }
        };

        if (group_handler_s != null)
        {
            group_handler_s.post(myRunnable);
        }
        else if (MainActivity.group_message_list_activity != null)
        {
            MainActivity.group_message_list_activity.runOnUiThread(myRunnable);
        }
    }

    static void set_recording_pop_visibilty_s(final boolean visible)
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (voiceRecordingUiHelper != null)
                    {
                        if (visible)
                        {
                            voiceRecordingUiHelper.show();
                        }
                        else
                        {
                            voiceRecordingUiHelper.hide();
                        }
                    }
                }
                catch (Exception e)
                {
                }
            }
        };

        if (group_handler_s != null)
        {
            group_handler_s.post(myRunnable);
        }
        else if (MainActivity.group_message_list_activity != null)
        {
            MainActivity.group_message_list_activity.runOnUiThread(myRunnable);
        }
    }

    private void setUpEmojiPopup()
    {
        emojiPopup = EmojiPopup.Builder.fromRootView(rootView).setOnEmojiBackspaceClickListener(
                new OnEmojiBackspaceClickListener()
                {
                    @Override
                    public void onEmojiBackspaceClick(View v)
                    {

                    }

                }).setOnEmojiPopupShownListener(new OnEmojiPopupShownListener()
        {
            @Override
            public void onEmojiPopupShown()
            {
                final Drawable d1 = new IconicsDrawable(getBaseContext()).icon(FontAwesome.Icon.faw_keyboard).color(
                        getResources().getColor(R.color.icon_colors)).sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);

                insert_emoji.setImageDrawable(d1);
                // insert_emoji.setImageResource(R.drawable.about_icon_email);
            }
        }).setOnSoftKeyboardOpenListener(new OnSoftKeyboardOpenListener()
        {
            @Override
            public void onKeyboardOpen(@Px final int keyBoardHeight)
            {
                // Log.d(TAG, "Opened soft keyboard");
            }
        }).setOnEmojiPopupDismissListener(new OnEmojiPopupDismissListener()
        {
            @Override
            public void onEmojiPopupDismiss()
            {
                final Drawable d1 = new IconicsDrawable(getBaseContext()).icon(
                        GoogleMaterial.Icon.gmd_sentiment_satisfied).color(
                        getResources().getColor(R.color.icon_colors)).sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);

                insert_emoji.setImageDrawable(d1);
                // insert_emoji.setImageResource(R.drawable.emoji_ios_category_people);
            }
        }).setOnSoftKeyboardCloseListener(new OnSoftKeyboardCloseListener()
        {
            @Override
            public void onKeyboardClose()
            {
                // Log.d(TAG, "Closed soft keyboard");
            }
        }).build(ml_new_group_message);
    }

    String get_current_group_id()
    {
        return group_id;
    }

    /** Restore chat context before gallery/preview callbacks (onActivityResult runs before onResume). */
    void prepareGroupMediaSendContext()
    {
        if (group_id.equals("-1") && group_id_prev != null && !group_id_prev.equals("-1"))
        {
            group_id = group_id_prev;
        }
        MainActivity.group_message_list_activity = this;
    }

    static boolean is_valid_group_id(final String id)
    {
        return id != null && !id.isEmpty() && !"-1".equals(id);
    }

    static String resolve_group_id_for_outgoing_attachment(final String groupid_local,
                                                           final boolean activity_group_num)
    {
        if (!activity_group_num)
        {
            return is_valid_group_id(groupid_local) ? groupid_local : null;
        }

        if (MainActivity.group_message_list_activity != null)
        {
            String gid = MainActivity.group_message_list_activity.get_current_group_id();
            if (!is_valid_group_id(gid))
            {
                gid = MainActivity.group_message_list_activity.group_id_prev;
            }
            if (is_valid_group_id(gid))
            {
                return gid;
            }
        }

        if (is_valid_group_id(groupid_local))
        {
            return groupid_local;
        }

        return null;
    }

    public void set_group_connection_status_icon()
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (is_group_we_left(group_id_prev))
                    {
                        ml_icon.setImageResource(R.drawable.circle_pink);
                    }
                    else
                    {
                        if (is_group_active(group_id_prev))
                        {
                            final long group_num = tox_group_by_groupid__wrapper(group_id_prev);
                            final int conn = tox_group_is_connected(group_num);
                            if (conn ==
                                TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
                            {
                                if (is_group_mesh_degraded(group_id_prev))
                                {
                                    ml_icon.setImageResource(R.drawable.circle_orange);
                                }
                                else
                                {
                                    ml_icon.setImageResource(R.drawable.circle_green);
                                }
                            }
                            else
                            {
                                ml_icon.setImageResource(R.drawable.circle_orange);
                            }
                        }
                        else
                        {
                            ml_icon.setImageResource(R.drawable.circle_red);
                        }
                    }
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (event.getAction() == KeyEvent.ACTION_DOWN)
        {
            switch (event.getKeyCode())
            {
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (!event.isShiftPressed())
                    {
                        // HelperGeneric.logI(TAG, "dispatchKeyEvent:KEYCODE_ENTER");
                        send_message_onclick(null);
                        return true;
                    }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    public static void add_quote_group_message_text(final String quote_text)
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (MainActivity.group_message_list_activity != null)
                    {
                        ChatReplyPreviewController.startReplyToPlainText(MainActivity.group_message_list_activity,
                                quote_text);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "add_quote_message_text:EE01:" + e.getMessage());
                }
            }
        };

        if (group_handler_s != null)
        {
            group_handler_s.post(myRunnable);
        }
    }

    public void send_attatchment(View view)
    {
        // KHANDAQ (Figma attachments 2031:13703): mirror the 1:1 attach sheet — teal camera/video/gallery
        // chips + a grid of recent device media — but route everything to the GROUP send flow.
        final com.google.android.material.bottomsheet.BottomSheetDialog sheet =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        final View content = getLayoutInflater().inflate(R.layout.bottomsheet_attach, null);
        ((ImageView) content.findViewById(R.id.attach_chip_camera_icon)).setImageDrawable(
                new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_photo_camera).color(Color.WHITE).sizeDp(26));
        ((ImageView) content.findViewById(R.id.attach_chip_video_icon)).setImageDrawable(
                new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_videocam).color(Color.WHITE).sizeDp(26));
        ((ImageView) content.findViewById(R.id.attach_chip_gallery_icon)).setImageDrawable(
                new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_photo_library).color(Color.WHITE).sizeDp(26));
        ((ImageView) content.findViewById(R.id.attach_chip_file_icon)).setImageDrawable(
                new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_insert_drive_file).color(Color.WHITE).sizeDp(26));
        content.findViewById(R.id.attach_chip_camera).setOnClickListener(v -> {
            sheet.dismiss();
            open_group_camera_capture(false);
        });
        content.findViewById(R.id.attach_chip_video).setOnClickListener(v -> {
            sheet.dismiss();
            open_group_camera_capture(true);
        });
        content.findViewById(R.id.attach_chip_gallery).setOnClickListener(v -> {
            sheet.dismiss();
            open_group_gallery_picker();
        });
        content.findViewById(R.id.attach_chip_file).setOnClickListener(v -> {
            sheet.dismiss();
            open_group_file_picker();
        });

        final androidx.recyclerview.widget.RecyclerView grid = content.findViewById(R.id.attach_media_grid);
        final android.widget.Button sendSelected = content.findViewById(R.id.attach_send_selected);
        final java.util.ArrayList<Uri> recentMedia = MediaSendPreviewHelper.queryRecentMedia(this, 60);
        if (!recentMedia.isEmpty())
        {
            grid.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 4));
            grid.setAdapter(new MediaGridAdapter(recentMedia,
                    uri ->
                    {
                        sheet.dismiss();
                        sendPickedMediaToGroup(java.util.Collections.singletonList(uri));
                    },
                    selectedList ->
                    {
                        if (selectedList.isEmpty())
                        {
                            sendSelected.setVisibility(View.GONE);
                            sendSelected.setTag(null);
                        }
                        else
                        {
                            sendSelected.setText(getString(R.string.attach_send_selected, selectedList.size()));
                            sendSelected.setTag(new java.util.ArrayList<>(selectedList));
                            sendSelected.setVisibility(View.VISIBLE);
                        }
                    }));
            sendSelected.setOnClickListener(v ->
            {
                final Object tag = sendSelected.getTag();
                if (tag instanceof java.util.List)
                {
                    @SuppressWarnings("unchecked")
                    final java.util.List<Uri> sel = (java.util.List<Uri>) tag;
                    if (!sel.isEmpty())
                    {
                        sheet.dismiss();
                        sendPickedMediaToGroup(sel);
                    }
                }
            });
            grid.setVisibility(View.VISIBLE);
        }
        else if (!MediaSendPreviewHelper.hasMediaReadPermission(this))
        {
            try
            {
                final String[] perms = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        ? new String[]{android.Manifest.permission.READ_MEDIA_IMAGES,
                        android.Manifest.permission.READ_MEDIA_VIDEO}
                        : new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE};
                requestPermissions(perms, 9100);
            }
            catch (Exception ignored)
            {
            }
        }

        sheet.setContentView(content);
        sheet.show();
    }

    private void open_group_gallery_picker()
    {
        // KHANDAQ (Figma): clean media picker — modern Photo Picker (no Video/Photos/Audio tabs) on
        // Android 13+, else a media-only document picker. onActivityResult handling is unchanged.
        Intent intent = MediaSendPreviewHelper.buildMediaPickerIntent(this);
        mediaPreviewResultHandled = false;
        startActivityForResult(intent, MEDIAPICK_ID_001);
    }

    // KHANDAQ (Figma): "Файл" — the system document picker for any file type; the resulting Uri goes
    // straight through add_attachment_ngc (the same non-media send path a gallery pick falls back to).
    private void open_group_file_picker()
    {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        mediaPreviewResultHandled = false;
        try
        {
            startActivityForResult(intent, FILEPICK_ID);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "open_group_file_picker:EE:" + e.getMessage());
        }
    }

    private void open_group_camera_capture(final boolean video)
    {
        try
        {
            final java.io.File dir = new java.io.File(getExternalFilesDir(null), "tmpdir");
            if (!dir.exists())
            {
                dir.mkdirs();
            }
            final String name = (video ? "VID_" : "IMG_") + System.currentTimeMillis() + (video ? ".mp4" : ".jpg");
            final java.io.File outFile = new java.io.File(dir, name);
            final Uri outUri = androidx.core.content.FileProvider.getUriForFile(
                    this, KhandaqProviders.STD_FILE_PROVIDER, outFile);
            pending_camera_capture_uri = outUri;

            final Intent intent = new Intent(video
                    ? android.provider.MediaStore.ACTION_VIDEO_CAPTURE
                    : android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, outUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) == null)
            {
                android.widget.Toast.makeText(this, R.string.attach_no_camera, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            mediaPreviewResultHandled = false;
            startActivityForResult(intent, video ? CAMERA_VIDEO_ID : CAMERA_PHOTO_ID);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "open_group_camera_capture:EE:" + e.getMessage());
        }
    }

    /** Route 1..N picked media Uris into the existing GROUP send preview / send flow. */
    private void sendPickedMediaToGroup(final java.util.List<Uri> uris)
    {
        if (uris == null || uris.isEmpty())
        {
            return;
        }
        final Intent picked = new Intent();
        if (uris.size() == 1)
        {
            picked.setData(uris.get(0));
        }
        else
        {
            android.content.ClipData clip = android.content.ClipData.newRawUri("media", uris.get(0));
            for (int i = 1; i < uris.size(); i++)
            {
                clip.addItem(new android.content.ClipData.Item(uris.get(i)));
            }
            picked.setClipData(clip);
        }
        mediaPreviewResultHandled = false;
        if (!MediaSendPreviewHelper.launchPreviewIfNeeded(this, picked,
                MediaSendPreviewHelper.TARGET_GROUP, -1, group_id, true))
        {
            MediaSendPreviewHelper.dispatchAttachments(this, uris,
                    MediaSendPreviewHelper.TARGET_GROUP, -1, group_id, true);
        }
    }

    void open_group_info_activity()
    {
        try
        {
            if ((group_id == null) || (group_id.equals("-1")))
            {
                return;
            }

            final Intent intent = new Intent(this, GroupInfoActivity.class);
            intent.putExtra("group_id", group_id);
            startActivity(intent);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void open_group_peer_info_activity(final Context context, final String peer_pubkey,
                                                     final String group_identifier)
    {
        try
        {
            if ((context == null) || (peer_pubkey == null) || (group_identifier == null))
            {
                return;
            }

            if (peer_pubkey.equals(TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY) || peer_pubkey.equals("-1"))
            {
                return;
            }

            final Intent intent = new Intent(context, GroupPeerInfoActivity.class);
            intent.putExtra("peer_pubkey", peer_pubkey);
            intent.putExtra("group_id", group_identifier);
            context.startActivity(intent);
        }
        catch (Exception ignored)
        {
        }
    }

    synchronized public void send_text_message_from_preview(final String caption)
    {
        if (caption == null || caption.trim().isEmpty())
        {
            return;
        }

        try
        {
            wakeup_tox_thread();

            final String msg = ChatReplyPreviewController.composeOutgoingText(caption.trim().substring(0,
                    (int) Math.min(tox_max_message_length(), caption.trim().length())));

            final String send_block_reason = group_send_precheck_failure_reason(group_id);
            if (send_block_reason != null)
            {
                display_toast(send_block_reason, true, 400);
                return;
            }

            final long group_num = tox_group_by_groupid__wrapper(group_id);
            final long message_id = send_group_text_message_resilient(group_id, group_num, msg);
            if (message_id == GROUP_SEND_QUEUE_WHEN_UNCONNECTED)
            {
                GroupMessage m = new GroupMessage();
                m.is_new = false;
                m.tox_group_peer_pubkey = tox_group_self_get_public_key(group_num).toUpperCase();
                m.direction = 1;
                m.TOX_MESSAGE_TYPE = 0;
                m.read = true;
                m.tox_group_peername = null;
                m.private_message = 0;
                m.group_identifier = group_id.toLowerCase();
                m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
                m.sent_timestamp = System.currentTimeMillis();
                m.rcvd_timestamp = System.currentTimeMillis();
                m.text = msg;
                m.was_synced = false;
                m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
                m.message_id_tox = PENDING_GROUP_MESSAGE_ID_TOX;
                insert_into_group_message_db(m, true);
                schedule_pending_group_message_flush(group_id);
                return;
            }
            if (message_id <= -1)
            {
                display_toast(group_send_failure_reason(message_id), true, 400);
                return;
            }

            GroupMessage m = new GroupMessage();
            m.is_new = false;
            m.tox_group_peer_pubkey = tox_group_self_get_public_key(group_num).toUpperCase();
            m.direction = 1;
            m.TOX_MESSAGE_TYPE = 0;
            m.read = true;
            m.tox_group_peername = null;
            m.private_message = 0;
            m.group_identifier = group_id.toLowerCase();
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
            m.sent_timestamp = System.currentTimeMillis();
            m.rcvd_timestamp = System.currentTimeMillis();
            m.text = msg;
            m.was_synced = false;
            m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
            m.message_id_tox = fourbytes_of_long_to_hex(message_id);
            insert_into_group_message_db(m, true);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void finish_group_text_send(final String send_group_id, final GroupMessage m, final long message_id)
    {
        if (PREF__X_battery_saving_mode)
        {
            HelperGeneric.logI(TAG, "global_last_activity_for_battery_savings_ts:001:*PING*");
        }
        global_last_activity_for_battery_savings_ts = System.currentTimeMillis();

        if (message_id == GROUP_SEND_QUEUE_WHEN_UNCONNECTED)
        {
            m.message_id_tox = PENDING_GROUP_MESSAGE_ID_TOX;
            insert_into_group_message_db(m, true);
            schedule_pending_group_message_flush(send_group_id);
        }
        else if (HelperFriend.is_group_text_send_success(message_id))
        {
            m.message_id_tox = fourbytes_of_long_to_hex(message_id);
            insert_into_group_message_db(m, true);
        }
        else
        {
            display_toast(group_send_failure_reason(message_id), true, 400);
            try
            {
                ml_new_group_message.setText(m.text);
                ml_new_group_message.setSelection(m.text.length());
                ChatDraftHelper.save_group_draft(send_group_id, m.text);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    synchronized public void send_message_onclick(View view)
    {
        // HelperGeneric.logI(TAG,"send_message_onclick:---start");

        // KHANDAQ (#9): edit mode — the send button SAVES the edit instead of sending a new message
        if (ChatReplyPreviewController.isEditActive())
        {
            final String newBody =
                    HelperGeneric.normalize_chat_input_text(ml_new_group_message.getText().toString());
            if (newBody == null || newBody.trim().isEmpty())
            {
                return;
            }
            if (newBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > HelperMessageEdit.MAX_EDIT_TEXT_BYTES)
            {
                display_toast(getString(R.string.chat_edit_too_long), true, 400);
                return; // edit mode stays active so the user can shorten the text
            }
            final com.zoffcc.applications.sorm.GroupMessage egm = ChatReplyPreviewController.consumeEditGroup();
            if (egm == null)
            {
                return;
            }
            final long gnum = tox_group_by_groupid__wrapper(egm.group_identifier);
            if (HelperMessageEdit.sendOwnGroupEdit(gnum, egm, newBody))
            {
                HelperGeneric.clear_chat_input_field(ml_new_group_message);
            }
            else
            {
                display_toast(getString(R.string.chat_edit_failed), true, 400);
            }
            return;
        }

        String msg = "";
        try
        {
            wakeup_tox_thread();

            final String normalized = HelperGeneric.normalize_chat_input_text(ml_new_group_message.getText().toString());
            if (normalized.isEmpty())
            {
                if (view != null)
                {
                    send_attatchment(view);
                }
                return;
            }

            final String truncated = normalized.substring(0, (int) Math.min(tox_max_message_length(), normalized.length()));
            final java.util.List<GroupMentionHelper.MentionEntry> mentions =
                    GroupMentionHelper.collectMentionsForOutgoing(truncated, group_id);
            final String withMentions = GroupMentionHelper.encodeMentionsBlock(truncated, mentions);

            msg = ChatReplyPreviewController.composeOutgoingText(withMentions);

            final String send_block_reason = group_send_precheck_failure_reason(group_id);
            if (send_block_reason != null)
            {
                display_toast(send_block_reason, true, 400);
                return;
            }

            try
            {
                GroupMessage m = new GroupMessage();
                m.is_new = false; // own messages are always "not new"
                m.tox_group_peer_pubkey = tox_group_self_get_public_key(
                        tox_group_by_groupid__wrapper(group_id)).toUpperCase();
                m.direction = 1; // msg sent
                m.TOX_MESSAGE_TYPE = 0;
                m.read = true; // !!!! there is not "read status" with conferences in Tox !!!!
                m.tox_group_peername = null;
                m.private_message = 0;
                m.group_identifier = group_id.toLowerCase();
                m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
                m.sent_timestamp = System.currentTimeMillis();
                m.rcvd_timestamp = System.currentTimeMillis(); // since we do not have anything better assume "now"
                m.text = msg;
                m.was_synced = false;
                m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;

                m.tox_group_peer_role = -1;
                try
                {
                    int peer_role_get = tox_group_self_get_role(tox_group_by_groupid__wrapper(group_id));
                    if (peer_role_get >= 0)
                    {
                        m.tox_group_peer_role = peer_role_get;
                    }

                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                if ((msg != null) && (!msg.equalsIgnoreCase("")))
                {
                    final String send_msg = msg;
                    final GroupMessage pending = m;
                    final String send_group_id = group_id;
                    HelperGeneric.clear_chat_input_field(ml_new_group_message);
                    ChatDraftHelper.clear_group_draft(group_id);
                    if (emojiPopup != null)
                    {
                        emojiPopup.dismiss();
                    }

                    final Thread send_thread = new Thread(() ->
                    {
                        final long group_num = tox_group_by_groupid__wrapper(send_group_id);
                        final long message_id = send_group_text_message_resilient(send_group_id, group_num, send_msg);
                        runOnUiThread(() -> finish_group_text_send(send_group_id, pending, message_id));
                    }, "grp-txt-send");
                    send_thread.setDaemon(true);
                    send_thread.start();
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

    static final class GroupOutgoingFileHandle
    {
        final String groupId;
        final String msgIdHash;
        final boolean chunked;

        GroupOutgoingFileHandle(final String groupId, final String msgIdHash, final boolean chunked)
        {
            this.groupId = groupId;
            this.msgIdHash = msgIdHash;
            this.chunked = chunked;
        }
    }

    static GroupOutgoingFileHandle add_attachment_and_get_handle(final Context c, final Intent data,
                                                                 final String groupid_local,
                                                                 final boolean activity_group_num)
    {
        return add_attachment_and_get_handle(c, data, groupid_local, activity_group_num, true);
    }

    static GroupOutgoingFileHandle add_attachment_and_get_handle(final Context c, final Intent data,
                                                                 final String groupid_local,
                                                                 final boolean activity_group_num,
                                                                 final boolean startTransferImmediately)
    {
        try
        {
            if ((data == null) || (data.getData() == null))
            {
                return null;
            }

            String fileName = null;

            try
            {
                DocumentFile documentFile = DocumentFile.fromSingleUri(c, data.getData());
                if (documentFile != null)
                {
                    fileName = documentFile.getName();
                }

                ContentResolver cr = c.getApplicationContext().getContentResolver();
                Cursor metaCursor = cr.query(data.getData(), null, null, null, null);
                if (metaCursor != null)
                {
                    try
                    {
                        if (metaCursor.moveToFirst())
                        {
                            int j;
                            for (j = 0; j < metaCursor.getColumnNames().length; j++)
                            {
                                if (metaCursor.getColumnName(j).equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                                {
                                    if (metaCursor.getString(j) != null)
                                    {
                                        if (metaCursor.getString(j).length() > 0)
                                        {
                                            fileName = metaCursor.getString(j);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    finally
                    {
                        metaCursor.close();
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            final String fileName_ = HelperFiletransfer.resolve_attachment_display_name(c, data.getData(), fileName);
            if (fileName_ == null)
            {
                return null;
            }

            final String groupid = resolve_group_id_for_outgoing_attachment(groupid_local, activity_group_num);
            if (groupid == null)
            {
                HelperGeneric.logI(TAG, "add_attachment_and_get_handle:no group id local=" + groupid_local
                        + " activityPeer=" + activity_group_num);
                return null;
            }

            return add_outgoing_file(c, groupid, data.getData().toString(), fileName_, data.getData(), 0, false,
                                     activity_group_num, false, startTransferImmediately);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "add_attachment_and_get_handle:EE:" + e.getMessage());
            return null;
        }
    }

    static GroupOutgoingFileHandle add_attachment_from_uri(final Context c, final Uri uri, final String groupid_local,
                                                           final boolean activity_group_num,
                                                           final boolean startTransferImmediately)
    {
        if (uri == null)
        {
            return null;
        }
        final Intent single = new Intent();
        single.setData(uri);
        single.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return add_attachment_and_get_handle(c, single, groupid_local, activity_group_num, startTransferImmediately);
    }

    static void add_attachment_ngc(Context c, Intent data, Intent orig_intent, String groupid_local, boolean activity_group_num)
    {
        HelperGeneric.logI(TAG, "add_attachment:001");

        try
        {
            String fileName = null;

            try
            {
                DocumentFile documentFile = DocumentFile.fromSingleUri(c, data.getData());

                fileName = documentFile.getName();
                // HelperGeneric.logI(TAG, "file_attach_for_send:documentFile:fileName=" + fileName);
                // HelperGeneric.logI(TAG, "file_attach_for_send:documentFile:fileLength=" + documentFile.length());

                ContentResolver cr = c.getApplicationContext().getContentResolver();
                Cursor metaCursor = cr.query(data.getData(), null, null, null, null);
                if (metaCursor != null)
                {
                    try
                    {
                        if (metaCursor.moveToFirst())
                        {
                            String file_path = metaCursor.getString(0);
                            // HelperGeneric.logI(TAG, "file_attach_for_send:metaCursor_path:fp=" + file_path);
                            // HelperGeneric.logI(TAG, "file_attach_for_send:metaCursor_path:column names=" +
                            //            metaCursor.getColumnNames().length);
                            int j;
                            for (j = 0; j < metaCursor.getColumnNames().length; j++)
                            {
                                // HelperGeneric.logI(TAG, "file_attach_for_send:metaCursor_path:column name=" +
                                //           metaCursor.getColumnName(j));
                                // HelperGeneric.logI(TAG,
                                //       "file_attach_for_send:metaCursor_path:column data=" + metaCursor.getString(j));
                                if (metaCursor.getColumnName(j).equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                                {
                                    if (metaCursor.getString(j) != null)
                                    {
                                        if (metaCursor.getString(j).length() > 0)
                                        {
                                            fileName = metaCursor.getString(j);
                                            // HelperGeneric.logI(TAG, "file_attach_for_send:filename new=" + fileName);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    finally
                    {
                        metaCursor.close();
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            final String fileName_ = HelperFiletransfer.resolve_attachment_display_name(c, data.getData(), fileName);

            if (fileName_ != null)
            {
                if (activity_group_num)
                {
                    HelperGeneric.logI(TAG, "add_outgoing_file:activity_group_num:true");
                    final Thread t = new Thread()
                    {
                        @Override
                        public void run()
                        {
                            if (groupid_local.equals("-1"))
                            {
                                // ok, we need to wait for onResume to finish and give us the friendnum
                                HelperGeneric.logI(TAG,
                                      "add_outgoing_file:ok, we need to wait for onResume to finish and give us the friendnum");
                                long loop = 0;
                                while (loop < 100)
                                {
                                    loop++;
                                    try
                                    {
                                        Thread.sleep(20);
                                    }
                                    catch (InterruptedException e)
                                    {
                                        e.printStackTrace();
                                    }

                                    if (MainActivity.group_message_list_activity != null)
                                    {
                                        if (!MainActivity.group_message_list_activity.get_current_group_id().equals(
                                                "-1"))
                                        {
                                            // got friendnum
                                            HelperGeneric.logI(TAG, "add_outgoing_file:got groupnum");
                                            break;
                                        }
                                    }
                                }

                                loop = 0;
                                while (loop < 1000)
                                {
                                    loop++;
                                    try
                                    {
                                        Thread.sleep(20);
                                    }
                                    catch (InterruptedException e)
                                    {
                                        e.printStackTrace();
                                    }

                                    if (oncreate_finished)
                                    {
                                        HelperGeneric.logI(TAG, "add_outgoing_file:oncreate_finished");
                                        break;
                                    }
                                }
                            }

                            try
                            {
                                Thread.sleep(50);
                            }
                            catch (InterruptedException e)
                            {
                                e.printStackTrace();
                            }

                            if ((MainActivity.group_message_list_activity == null) ||
                                (MainActivity.group_message_list_activity.get_current_group_id().equals("-1")))
                            {
                                // sorry, still no groupnum
                                HelperGeneric.logI(TAG, "add_outgoing_file:sorry, still no groupnum");
                                return;
                            }
                            HelperGeneric.logI(TAG, "add_outgoing_file:add_outgoing_file:thread_01");
                            final GroupOutgoingFileHandle handle = add_outgoing_file(c,
                                    MainActivity.group_message_list_activity.get_current_group_id(),
                                    data.getData().toString(), fileName_, data.getData(), 0, false,
                                    activity_group_num, false);
                            if (handle != null)
                            {
                                HelperGroup.start_group_file_send_by_hash(handle.groupId, handle.msgIdHash);
                            }
                        }
                    };
                    t.start();
                }
                else
                {
                    HelperGeneric.logI(TAG, "add_outgoing_file:activity_group_num:FALSE");
                    final Thread t2 = new Thread()
                    {
                        @Override
                        public void run()
                        {
                            HelperGeneric.logI(TAG, "add_outgoing_file:add_outgoing_file:thread_02");

                            long loop = 0;
                            while (loop < 100)
                            {
                                loop++;
                                try
                                {
                                    Thread.sleep(20);
                                }
                                catch (InterruptedException e)
                                {
                                    e.printStackTrace();
                                }

                                if (MainActivity.group_message_list_activity != null)
                                {
                                    if (!MainActivity.group_message_list_activity.get_current_group_id().equals("-1"))
                                    {
                                        // got friendnum
                                        HelperGeneric.logI(TAG, "add_outgoing_file:got groupnum:02");
                                        break;
                                    }
                                }
                            }

                            loop = 0;
                            while (loop < 1000)
                            {
                                loop++;
                                try
                                {
                                    Thread.sleep(20);
                                }
                                catch (InterruptedException e)
                                {
                                    e.printStackTrace();
                                }

                                if (oncreate_finished)
                                {
                                    HelperGeneric.logI(TAG, "add_outgoing_file:oncreate_finished:02");
                                    break;
                                }
                            }
                            final GroupOutgoingFileHandle handle = add_outgoing_file(c, groupid_local,
                                    data.getData().toString(), fileName_, data.getData(), 0,
                                    false, activity_group_num, false);
                            if (handle != null)
                            {
                                HelperGroup.start_group_file_send_by_hash(handle.groupId, handle.msgIdHash);
                            }
                        }
                    };
                    t2.start();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "select_file:22:EE1:" + e.getMessage());
        }
    }

    static boolean onClick_message_helper(final View v, boolean is_selected, final GroupMessage message_)
    {
        try
        {
            if (is_selected)
            {
                v.setBackgroundColor(Color.TRANSPARENT);
                is_selected = false;
                selected_group_messages.remove(message_.id);
                selected_group_messages_text_only.remove(message_.id);
                selected_group_messages_incoming_file.remove(message_.id);
                if (selected_group_messages_incoming_file.size() == selected_group_messages.size())
                {
                    amode_save_menu_item.setVisible(true);
                }
                else
                {
                    amode_save_menu_item.setVisible(false);
                }

                if (selected_group_messages.size() == 1)
                {
                    amode_info_menu_item.setVisible(true);
                }
                else
                {
                    amode_info_menu_item.setVisible(false);
                }

                if (selected_group_messages.isEmpty())
                {
                    // last item was de-selected
                    amode.finish();
                }
                else
                {
                    if (amode != null)
                    {
                        amode.setTitle("" + selected_group_messages.size() + " selected");
                    }
                }
            }
            else
            {
                if (!selected_group_messages.isEmpty())
                {
                    v.setBackgroundResource(R.drawable.bg_message_selection); // KHANDAQ (#4): translucent teal selection (was opaque gray)
                    is_selected = true;
                    selected_group_messages.add(message_.id);

                    if (message_.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
                    {
                        selected_group_messages_text_only.add(message_.id);
                    }
                    else if (message_.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                    {
                        // KHANDAQ (#13): voice messages are listen-only — never offer "save" for them.
                        if (message_.direction == 0 && !HelperFiletransfer.isVoiceMessagePath(message_.filename_fullpath))
                        {
                            selected_group_messages_incoming_file.add(message_.id);
                        }
                    }

                    if (selected_group_messages_incoming_file.size() == selected_group_messages.size())
                    {
                        amode_save_menu_item.setVisible(true);
                    }
                    else
                    {
                        amode_save_menu_item.setVisible(false);
                    }


                    if (selected_group_messages.size() == 1)
                    {
                        amode_info_menu_item.setVisible(true);
                    }
                    else
                    {
                        amode_info_menu_item.setVisible(false);
                    }

                    if (amode != null)
                    {
                        amode.setTitle("" + selected_group_messages.size() + " selected");
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return is_selected;
    }

    static class long_click_message_return
    {
        boolean is_selected;
        boolean ret_value;
    }

    static GroupMessageListActivity.long_click_message_return onLongClick_message_helper(Context context, final View v, boolean is_selected, final GroupMessage message_)
    {
        GroupMessageListActivity.long_click_message_return ret = new GroupMessageListActivity.long_click_message_return();

        try
        {
            if (is_selected)
            {
                ret.is_selected = true;
            }
            else
            {
                if (selected_group_messages.isEmpty())
                {
                    try
                    {
                        amode = MainActivity.group_message_list_activity.startSupportActionMode(
                                new ToolbarActionMode(context));
                        amode_save_menu_item = amode.getMenu().findItem(R.id.action_save);
                        amode_info_menu_item = amode.getMenu().findItem(R.id.action_info);
                        v.setBackgroundResource(R.drawable.bg_message_selection); // KHANDAQ (#4): translucent teal selection (was opaque gray)
                        ret.is_selected = true;
                        selected_group_messages.add(message_.id);

                        if (message_.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
                        {
                            selected_group_messages_text_only.add(message_.id);
                        }
                        else if (message_.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                        {
                            // KHANDAQ (#13): voice messages are listen-only — never offer "save" for them.
                            if (message_.direction == 0 && !HelperFiletransfer.isVoiceMessagePath(message_.filename_fullpath))
                            {
                                selected_group_messages_incoming_file.add(message_.id);
                            }
                        }

                        if (selected_group_messages_incoming_file.size() == selected_group_messages.size())
                        {
                            amode_save_menu_item.setVisible(true);
                        }
                        else
                        {
                            amode_save_menu_item.setVisible(false);
                        }


                        if (selected_group_messages.size() == 1)
                        {
                            amode_info_menu_item.setVisible(true);
                        }
                        else
                        {
                            amode_info_menu_item.setVisible(false);
                        }

                        if (amode != null)
                        {
                            amode.setTitle("" + selected_group_messages.size() + " selected");
                        }
                        ret.ret_value = true;
                        return ret;
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        ret.ret_value = true;
        return ret;
    }

    @Override
    public void onBackPressed()
    {
        if (group_message_drawer != null && group_message_drawer.isDrawerOpen())
        {
            group_message_drawer.closeDrawer();
        }
        else
        {
            super.onBackPressed();
        }
    }

    synchronized void update_group_all_users()
    {
        // HelperGeneric.logI(TAG, "update_group_all_users:** CALL");
        long currentTime = System.currentTimeMillis();

        if (currentTime - update_group_all_users_last_trigger_ts >= INTERVAL_UPDATE_NGC_GROUP_ALL_USERS_MS)
        {
            update_group_all_users_last_trigger_ts = currentTime;
            // HelperGeneric.logI(TAG, "update_group_all_users:-> REAL");
            update_group_all_users_real();
        }
        else
        {
            long delta_t_ms = currentTime - update_group_all_users_last_trigger_ts;
            // HelperGeneric.logI(TAG, "update_group_all_users:  TRIG delta ms=" + delta_t_ms);
            long trigger_in_ms_again = INTERVAL_UPDATE_NGC_GROUP_ALL_USERS_MS - delta_t_ms;
            if ((trigger_in_ms_again < 1) || (trigger_in_ms_again > (INTERVAL_UPDATE_NGC_GROUP_ALL_USERS_MS + 1)))
            {
                trigger_in_ms_again = INTERVAL_UPDATE_NGC_GROUP_ALL_USERS_MS;
            }
            final long trigger_in_ms_again_ = trigger_in_ms_again + 2;
            final Thread thread = new Thread(() -> {
                try
                {
                    Thread.sleep(trigger_in_ms_again_);
                    // HelperGeneric.logI(TAG, "update_group_all_users:__ CALL from Trigger");
                    update_group_all_users();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            });
            thread.start();
        }
    }

    void update_group_all_users_real()
    {
        try
        {
            set_peer_count_header();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            set_peer_names_and_avatars();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    synchronized void remove_group_all_users()
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
                        Runnable myRunnable = new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                try
                                {
                                    lookup_peer_listnum_pubkey.clear();
                                    group_message_drawer.removeAllItems();
                                }
                                catch (Exception e2)
                                {
                                    e2.printStackTrace();
                                }
                            }
                        };

                        if (group_handler_s != null)
                        {
                            group_handler_s.post(myRunnable);
                        }

                        // TODO: hack to be synced with additions later
                        Thread.sleep(120);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            };
            t.start();
            t.join();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "remove_group_user:EE:" + e.getMessage());
        }
    }

    synchronized void remove_group_user(String peer_pubkey)
    {
        if ((peer_pubkey == null) || peer_pubkey.isEmpty() || group_handler_s == null)
        {
            return;
        }

        final String key = peer_pubkey.toLowerCase();
        group_handler_s.post(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (group_message_drawer == null || group_message_drawer.getAdapter() == null)
                    {
                        return;
                    }

                    Long peer_num = lookup_peer_listnum_pubkey.get(key);
                    if (peer_num == null)
                    {
                        final int item_count = group_message_drawer.getAdapter().getItemCount();
                        for (int i = 0; i < item_count; i++)
                        {
                            final IDrawerItem drawer_item = group_message_drawer.getAdapter().getItem(i);
                            if (!(drawer_item instanceof ConferenceCustomDrawerPeerItem))
                            {
                                continue;
                            }
                            final ConferenceCustomDrawerPeerItem peer_item =
                                    (ConferenceCustomDrawerPeerItem) drawer_item;
                            if (peer_item.peer_pubkey != null
                                && peer_item.peer_pubkey.equalsIgnoreCase(key))
                            {
                                peer_num = peer_item.getIdentifier();
                                break;
                            }
                        }
                    }

                    if (peer_num != null)
                    {
                        lookup_peer_listnum_pubkey.remove(key);
                        group_message_drawer.removeItem(peer_num);
                        last_drawer_peers_fingerprint = "";
                        last_drawer_status_fingerprint = "";
                    }
                }
                catch (Exception e2)
                {
                    Log.w(TAG, "remove_group_user:EE:" + e2.getMessage());
                }
            }
        });
    }

    private String build_drawer_peers_fingerprint(final List<group_list_peer> peers)
    {
        final StringBuilder sb = new StringBuilder();
        for (final group_list_peer peer : peers)
        {
            sb.append(peer.peer_pubkey).append('|').append(peer.online ? '1' : '0').append('|').
                    append(peer.peer_name).append('|').append(peer.notification_silent).append('|').
                    append(peer.self).append(';');
        }
        return sb.toString();
    }

    private String build_drawer_status_fingerprint(final List<group_list_peer> peers)
    {
        final StringBuilder sb = new StringBuilder();
        for (final group_list_peer peer : peers)
        {
            sb.append(peer.peer_pubkey).append(':');
            if (peer.online)
            {
                sb.append('O');
            }
            else
            {
                sb.append(peer.last_seen_ms / 60_000L);
            }
            sb.append(';');
        }
        return sb.toString();
    }

    synchronized void sync_group_drawer_peers(final List<group_list_peer> peers)
    {
        if ((peers == null) || (group_handler_s == null))
        {
            return;
        }

        final String structural_fp = build_drawer_peers_fingerprint(peers);
        final String status_fp = build_drawer_status_fingerprint(peers);
        if (structural_fp.equals(last_drawer_peers_fingerprint)
            && status_fp.equals(last_drawer_status_fingerprint))
        {
            return;
        }

        final boolean structural_changed = !structural_fp.equals(last_drawer_peers_fingerprint);
        last_drawer_peers_fingerprint = structural_fp;
        last_drawer_status_fingerprint = status_fp;
        final List<group_list_peer> peers_copy = new ArrayList<>(peers);

        group_handler_s.post(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (structural_changed)
                    {
                        rebuild_group_drawer_peers(peers_copy);
                    }
                    else
                    {
                        update_group_drawer_peer_descriptions(peers_copy);
                    }
                }
                catch (Exception e2)
                {
                    e2.printStackTrace();
                    HelperGeneric.logI(TAG, "sync_group_drawer_peers:EE:" + e2.getMessage());
                }
            }
        });
    }

    private void rebuild_group_drawer_peers(final List<group_list_peer> peers_copy)
    {
        lookup_peer_listnum_pubkey.clear();
        group_message_drawer.removeAllItems();
        for (final group_list_peer peer : peers_copy)
        {
            group_message_drawer.addItem(create_group_drawer_peer_item(peer));
        }
    }

    private void update_group_drawer_peer_descriptions(final List<group_list_peer> peers_copy)
    {
        final HashMap<String, group_list_peer> by_pubkey = new HashMap<>();
        for (final group_list_peer peer : peers_copy)
        {
            if (peer.peer_pubkey != null)
            {
                by_pubkey.put(peer.peer_pubkey.toLowerCase(), peer);
            }
        }

        final int item_count = group_message_drawer.getAdapter().getItemCount();
        for (int i = 0; i < item_count; i++)
        {
            final IDrawerItem drawer_item = group_message_drawer.getAdapter().getItem(i);
            if (!(drawer_item instanceof ConferenceCustomDrawerPeerItem))
            {
                continue;
            }
            final ConferenceCustomDrawerPeerItem peer_item = (ConferenceCustomDrawerPeerItem) drawer_item;
            if (peer_item.peer_pubkey == null)
            {
                continue;
            }
            final group_list_peer peer = by_pubkey.get(peer_item.peer_pubkey.toLowerCase());
            if (peer == null)
            {
                continue;
            }
            final String status_line = format_group_member_status_line(GroupMessageListActivity.this,
                                                                       peer.online, peer.last_seen_ms);
            peer_item.withDescription(status_line);
            peer_item.setPeerOnline(peer.online);
        }
        group_message_drawer.getAdapter().notifyDataSetChanged();
    }

    private ConferenceCustomDrawerPeerItem create_group_drawer_peer_item(final group_list_peer peer)
    {
        final boolean have_avatar_for_pubkey = friendlist_has_avatar_for_pubkey(peer.peer_pubkey);
        final String status_line = format_group_member_status_line(GroupMessageListActivity.this, peer.online,
                                                                   peer.last_seen_ms);

        ConferenceCustomDrawerPeerItem new_item = new ConferenceCustomDrawerPeerItem(
                have_avatar_for_pubkey, peer.peer_pubkey, group_id, peer.peer_name).
                withIdentifier(peer.peer_num).
                withName(peer.peer_name).
                withDescription(status_line).
                setPeerOnline(peer.online).
                setSelfPeer(peer.self).
                withOnDrawerItemClickListener(new Drawer.OnDrawerItemClickListener()
                {
                    @Override
                    public boolean onItemClick(View view, int position, IDrawerItem drawerItem)
                    {
                        open_group_peer_info_activity(view.getContext(), peer.peer_pubkey, group_id);
                        return true;
                    }
                });

        if (peer.self)
        {
            new_item.withTextColor(Color.parseColor("#FF5733"));
        }
        else if (peer.notification_silent)
        {
            new_item.withTextColor(Color.parseColor("#d7b442"));
        }
        return new_item;
    }

    public void show_add_friend_group(View view)
    {
        HelperGeneric.logI(TAG, "show_add_friend_group");
        Intent intent = new Intent(this, FriendSelectSingleActivity.class);
        intent.putExtra("group_id", group_id);
        intent.putExtra("offline", 1);
        startActivityForResult(intent, SelectFriendSingleActivity_ID);
    }

    // KHANDAQ (Figma single-row header): search / members / add / video moved to the ⋮ overflow.
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu)
    {
        getMenuInflater().inflate(R.menu.menu_group_chat_header, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item)
    {
        final int id = item.getItemId();
        if (id == R.id.action_group_search)
        {
            enterGroupSearchMode();
            return true;
        }
        else if (id == R.id.action_group_members)
        {
            toggleMembersDrawer();
            return true;
        }
        else if (id == R.id.action_group_add)
        {
            show_add_friend_group(findViewById(R.id.toolbar));
            return true;
        }
        else if (id == R.id.action_group_video)
        {
            toggle_group_video(findViewById(R.id.toolbar));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void toggleMembersDrawer()
    {
        try
        {
            if (group_message_drawer != null)
            {
                if (group_message_drawer.isDrawerOpen())
                {
                    group_message_drawer.closeDrawer();
                }
                else
                {
                    group_message_drawer.openDrawer();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void enterGroupSearchMode()
    {
        final View infoTap = findViewById(R.id.ml_header_group_info_tap);
        if (infoTap != null)
        {
            infoTap.setVisibility(View.GONE);
        }
        if (messageSearchView != null)
        {
            messageSearchView.setVisibility(View.VISIBLE);
            messageSearchView.setIconified(false);
            messageSearchView.requestFocus();
        }
    }

    private void exitGroupSearchMode()
    {
        if (messageSearchView != null)
        {
            messageSearchView.setVisibility(View.GONE);
        }
        final View infoTap = findViewById(R.id.ml_header_group_info_tap);
        if (infoTap != null)
        {
            infoTap.setVisibility(View.VISIBLE);
        }
    }

    public void openCamera()
    {
        if (!HelperCall.hasCameraPermission(this))
        {
            ensureNgcVideoPermissions(this::openCameraInternal);
            return;
        }

        openCameraInternal();
    }

    private void openCameraInternal()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try
        {
            if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
            String cameraId = manager.getCameraIdList()[0];
            try
            {
                if (ngc_active_camera_type == NGC_FRONT_CAMERA_USED)
                {
                    for (String cameraId_iter : manager.getCameraIdList())
                    {
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId_iter);
                        if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_FRONT)
                        {
                            cameraId = cameraId_iter;
                        }
                    }
                }
                else
                {
                    for (String cameraId_iter : manager.getCameraIdList())
                    {
                        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId_iter);
                        if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_BACK)
                        {
                            cameraId = cameraId_iter;
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }

            if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] outputSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            Size selectedSize = chooseOptimalSize(outputSizes, CAMERAX_NGC_IMAGE_WIDTH, CAMERAX_NGC_IMAGE_HEIGHT);
            mImageReader = ImageReader.newInstance(selectedSize.getWidth(), selectedSize.getHeight(),
                                                   ImageFormat.YUV_420_888, CAMERAX_NGC_CAPTURE_MAX_IMAGES);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
            manager.openCamera(cameraId, mStateCallback, mBackgroundHandler);
            if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
            if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        }
        catch (Exception e2)
        {
            e2.printStackTrace();
            if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        }
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback()
    {
        @Override
        public void onOpened(CameraDevice camera)
        {
            mCameraDevice = camera;
            createCaptureSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera)
        {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error)
        {
            camera.close();
            mCameraDevice = null;
        }
    };

    private void createCaptureSession()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        try
        {
            Surface surface = mImageReader.getSurface();
            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            mCameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback()
            {
                @Override
                public void onConfigured(CameraCaptureSession session)
                {
                    mCaptureSession = session;
                    startPreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session)
                {
                    // Handle configuration failure
                }
            }, mBackgroundHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
        catch (Exception e2)
        {
            e2.printStackTrace();
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    private void startPreview()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        try
        {
            CaptureRequest.Builder captureRequestBuilder = mCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(mImageReader.getSurface());
            mCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
        }
        catch (CameraAccessException e)
        {
            e.printStackTrace();
        }
        catch (Exception e2)
        {
            e2.printStackTrace();
        }

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener()
    {
        @Override
        public void onImageAvailable(ImageReader reader)
        {
            try
            {
                if (ngc_video_off)
                {
                    return;
                }

                Image image = reader.acquireLatestImage();
                if (image == null)
                {
                    return;
                }

                if ((last_processed_camera_frame == -1) ||
                    ((last_processed_camera_frame + PREF__ngc_video_frame_delta_ms) < System.currentTimeMillis()))
                {
                    last_processed_camera_frame = System.currentTimeMillis();

                    int yRowStride = image.getPlanes()[0].getRowStride();
                    int uRowStride = image.getPlanes()[1].getRowStride();
                    int vRowStride = image.getPlanes()[2].getRowStride();
                    int ypixelStride = image.getPlanes()[0].getPixelStride();
                    int upixelStride = image.getPlanes()[1].getPixelStride();
                    int vpixelStride = image.getPlanes()[2].getPixelStride();
                    //HelperGeneric.logI(TAG, "yRowStride="+ yRowStride +  " uRowStride=" + uRowStride + " vRowStride=" + vRowStride +
                    //           " ypixelStride=" + ypixelStride + " upixelStride=" + upixelStride + " vpixelStride=" + vpixelStride);

                    ByteBuffer y_buffer = image.getPlanes()[0].getBuffer();
                    final int y_size_in_bytes = y_buffer.remaining();
                    byte[] y_data = new byte[y_size_in_bytes];
                    y_buffer.get(y_data);

                    ByteBuffer u_buffer = image.getPlanes()[1].getBuffer();
                    final int u_size_in_bytes = u_buffer.remaining();
                    byte[] u_data = new byte[u_size_in_bytes];
                    u_buffer.get(u_data);

                    ByteBuffer v_buffer = image.getPlanes()[2].getBuffer();
                    final int v_size_in_bytes = v_buffer.remaining();
                    byte[] v_data = new byte[v_size_in_bytes];
                    v_buffer.get(v_data);

                    if ((upixelStride == 2) && (vpixelStride == upixelStride))
                    {
                        for (int b = 1; b < (u_size_in_bytes / upixelStride); b++)
                        {
                            u_data[b] = u_data[b * upixelStride];
                            v_data[b] = v_data[b * vpixelStride];
                        }
                    }

                    // Process the captured YUV frame data
                    //HelperGeneric.logI(TAG, "IIIIIIIIII:camera_image:bytes=" + y_size_in_bytes + " " + u_size_in_bytes + " " +
                    //           v_size_in_bytes);

                    //ByteBuffer yuv_frame_data_buf = ByteBuffer.allocateDirect(y_size_in_bytes + u_size_in_bytes + v_size_in_bytes);
                    //yuv_frame_data_buf.rewind();
                    //
                    //yuv_frame_data_buf.put(y_data);
                    //yuv_frame_data_buf.put(u_data);
                    //yuv_frame_data_buf.put(v_data);

                    //y_buffer.rewind();
                    //u_buffer.rewind();
                    //v_buffer.rewind();

                    final byte[][] buf3 = {null};
                    byte[] buf2 = new byte[((640 * 480) * 3 / 2)];
                    buf3[0] = new byte[((640 * 480) * 3 / 2)];

                    final int off_u = 640 * 480;
                    final int off_v = (640 * 480) + (640 * 480) / 4;
                    //y_buffer.get(buf2, 0, 640 * 480);
                    //u_buffer.get(buf2, off_u, (640 * 480) / 4);
                    //v_buffer.get(buf2, off_v, (640 * 480) / 4);

                    System.arraycopy(y_data, 0, buf2, 0, off_u);
                    System.arraycopy(u_data, 0, buf2, off_u, (off_u / 4));
                    System.arraycopy(v_data, 0, buf2, off_v, (off_u / 4));

                    byte[] finalBuf = buf2;
                    Runnable myRunnable = new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                buf3[0] = YUV420rotate90(finalBuf, buf3[0], 640, 480);
                                if (ngc_active_camera_type == NGC_FRONT_CAMERA_USED)
                                {
                                    // rotate 180 degrees more
                                    byte[] buf4 = new byte[((640 * 480) * 3 / 2)];
                                    buf4 = YUV420rotate90(buf3[0], buf4, 480, 640);
                                    buf3[0] = YUV420rotate90(buf4, buf3[0], 640, 480);
                                }
                                //
                                ngc_own_alloc_in.copyFrom(buf3[0]);
                                ngc_own_yuvToRgb.setInput(ngc_own_alloc_in);
                                ngc_own_yuvToRgb.forEach(ngc_own_alloc_out);
                                ngc_own_alloc_out.copyTo(ngc_own_video_frame_image);
                                ngc_video_own_view.setBitmap(ngc_own_video_frame_image);
                                final int y_bytes_ = 640 * 480;
                                final int uv_bytes_ = (640 / 2) * (480 / 2);
                                System.arraycopy(buf3[0], 0, y_buf__, 0, y_bytes_);
                                System.arraycopy(buf3[0], y_bytes_, u_buf__, 0, uv_bytes_);
                                System.arraycopy(buf3[0], y_bytes_ + uv_bytes_, v_buf__, 0, uv_bytes_);
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
                    // HelperGeneric.logI(TAG, "IIIIIIIIII:camera_image:skip_frame");
                }
                image.close();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    };

    public void closeCamera()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }

        try
        {
            if (mCaptureSession != null)
            {
                mCaptureSession.close();
                mCaptureSession = null;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            if (mCameraDevice != null)
            {
                mCameraDevice.close();
                mCameraDevice = null;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            if (mImageReader != null)
            {
                mImageReader.close();
                mImageReader = null;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            flush_output_image();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    private void flush_output_image()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        try
        {
            ngc_video_view.setBitmap(null);
        }
        catch (Exception e)
        {
        }
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    @SuppressLint("RestrictedApi")
    private Size chooseOptimalSize(Size[] choices, int width, int height)
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        try
        {
            List<Size> bigEnough = new ArrayList<>();
            for (Size option : choices)
            {
                if (option.getWidth() >= width && option.getHeight() >= height)
                {
                    bigEnough.add(option);
                }
            }
            if (bigEnough.size() > 0)
            {
                if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
                return choices[0]; // Collections.min(bigEnough, new CompareSizesByArea());
            }
            else
            {
                if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
                return choices[0];
            }
        }
        catch (Exception e)
        {
            if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
            return new Size(640, 480);
        }
    }

    synchronized public static void stop_group_video(final Context c, boolean reset_call_values)
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        ngc_video_showing_video_from_peer_pubkey = "-1";
        NGC_Group_video_play_thread_running = false;
        NGC_Group_audio_record_thread_running = false;
        HelperGeneric.logI(TAG, "NGC_Group_video_play_thread_running:false:001");
        ngc_video_view_container.setVisibility(View.GONE);
        sending_video_to_group = false;

        // ---- stop audio stuff
        try
        {
            GroupGroupAudioService.stop_me(true, reset_call_values);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        // ---- stop audio stuff

        ngc_set_video_call_icon(NGC_VIDEO_ICON_STATE_INACTIVE);
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    public static void play_ngc_incoming_audio_frame(final long group_number, final long peer_id, final byte[] encoded_audio_and_header, long length)
    {
        if (MainActivity.group_message_list_activity == null)
        {
            // NGC group activity not open
            return;
        }

        final long conference_num = tox_group_by_groupid__wrapper(group_id);
        if (conference_num != group_number)
        {
            // wrong NGC group
            return;
        }

        // HelperGeneric.logI(TAG, "play_ngc_incoming_audio_frame:delta=" + (System.currentTimeMillis() - ngc_audio_packet_last_incoming_ts));
        ngc_audio_packet_last_incoming_ts = System.currentTimeMillis();
        ngc_video_packet_last_incoming_ts = System.currentTimeMillis();

        if ((ngc_video_frame_image != null) && (!ngc_video_frame_image.isRecycled()))
        {
            if (sending_video_to_group == true)
            {
                final String ngc_incoming_audio_from_peer = tox_group_peer_get_public_key__wrapper(group_number,
                                                                                                   peer_id);
                ngc_update_video_incoming_peer_list(ngc_incoming_audio_from_peer);
                if (ngc_video_showing_video_from_peer_pubkey.equals("-1"))
                {
                    ngc_video_showing_video_from_peer_pubkey = ngc_incoming_audio_from_peer;
                }
                else if (!ngc_video_showing_video_from_peer_pubkey.equalsIgnoreCase(ngc_incoming_audio_from_peer))
                {
                    // we are already playing the video/audio of a different peer in the group
                    return;
                }
                // remove header from data (10 bytes)
                final int pcm_encoded_length = (int) (length - 10);
                final byte[] pcm_encoded_buf = new byte[pcm_encoded_length];
                final byte[] pcm_decoded_buf = new byte[20000];
                final int bytes_in_40ms = 1920;
                final byte[] pcm_decoded_buf_delta_1 = new byte[bytes_in_40ms * 2];
                final byte[] pcm_decoded_buf_delta_2 = new byte[bytes_in_40ms * 2];
                final byte[] pcm_decoded_buf_delta_3 = new byte[bytes_in_40ms * 2];
                try
                {
                    System.arraycopy(encoded_audio_and_header, 10, pcm_encoded_buf, 0, pcm_encoded_length);
                    //
                    int decoded_samples = toxav_ngc_audio_decode(pcm_encoded_buf, pcm_encoded_length, pcm_decoded_buf);
                    // HelperGeneric.logI(TAG, "play_ngc_incoming_audio_frame:toxav_ngc_audio_decode:decoded_samples=" + decoded_samples);

                    // put pcm data into a FIFO
                    System.arraycopy(pcm_decoded_buf, 0, pcm_decoded_buf_delta_1, 0, (bytes_in_40ms * 2));
                    ngc_audio_in_queue.offer(pcm_decoded_buf_delta_1);
                    System.arraycopy(pcm_decoded_buf, (bytes_in_40ms * 2), pcm_decoded_buf_delta_2, 0,
                                     (bytes_in_40ms * 2));
                    ngc_audio_in_queue.offer(pcm_decoded_buf_delta_2);
                    System.arraycopy(pcm_decoded_buf, ((bytes_in_40ms * 2) * 2), pcm_decoded_buf_delta_3, 0,
                                     (bytes_in_40ms * 2));
                    ngc_audio_in_queue.offer(pcm_decoded_buf_delta_3);
                    ngc_video_frame_last_incoming_ts = System.currentTimeMillis();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    public static void show_ngc_incoming_video_frame_v1(final long group_number, final long peer_id, final byte[] encoded_video_and_header, long length)
    {
        if (MainActivity.group_message_list_activity == null)
        {
            // NGC group activity not open
            return;
        }

        final long conference_num = tox_group_by_groupid__wrapper(group_id);
        if (conference_num != group_number)
        {
            // wrong NGC group
            return;
        }

        // KHANDAQ (security D-5): drop frames that arrive faster than our ~30 fps cap (anti-flood).
        final long now_av_frame = System.currentTimeMillis();
        if ((now_av_frame - ngc_video_frame_last_processed_ts) < NGC_VIDEO_MIN_FRAME_INTERVAL_MS)
        {
            return;
        }
        ngc_video_frame_last_processed_ts = now_av_frame;

        ngc_video_packet_last_incoming_ts = System.currentTimeMillis();

        if ((ngc_video_frame_image != null) && (!ngc_video_frame_image.isRecycled()))
        {
            if (sending_video_to_group == true)
            {
                final String ngc_incoming_video_from_peer = tox_group_peer_get_public_key__wrapper(group_number,
                                                                                                   peer_id);
                ngc_update_video_incoming_peer_list(ngc_incoming_video_from_peer);

                if (ngc_video_showing_video_from_peer_pubkey.equals("-1"))
                {
                    ngc_video_showing_video_from_peer_pubkey = ngc_incoming_video_from_peer;
                }
                else if (!ngc_video_showing_video_from_peer_pubkey.equalsIgnoreCase(ngc_incoming_video_from_peer))
                {
                    // we are already showing the video of a different peer in the group
                    return;
                }

                // remove header from data (11 bytes)
                final int yuv_frame_encoded_bytes = (int) (length - 11);
                if ((yuv_frame_encoded_bytes > 0) && (yuv_frame_encoded_bytes < 40000))
                {
                    // TODO: make faster and better. this is not optimized.
                    final byte[] yuv_frame_encoded_buf = new byte[yuv_frame_encoded_bytes];
                    int w2 = 480 + 32; // 240 + 16; // encoder stride added
                    int h2 = 640; // 320;
                    final int y_bytes2 = w2 * h2;
                    final int u_bytes2 = (w2 * h2) / 4;
                    final int v_bytes2 = (w2 * h2) / 4;
                    final byte[] y_buf2 = new byte[y_bytes2];
                    final byte[] u_buf2 = new byte[u_bytes2];
                    final byte[] v_buf2 = new byte[v_bytes2];
                    int ystride = -1;
                    try
                    {
                        System.arraycopy(encoded_video_and_header, 11, yuv_frame_encoded_buf, 0,
                                         yuv_frame_encoded_bytes);
                        //
                        ystride = toxav_ngc_video_decode(yuv_frame_encoded_buf, yuv_frame_encoded_bytes, w2, h2, y_buf2,
                                                         u_buf2, v_buf2, flush_decoder);
                        //if (ystride != -1)
                        //{
                        //    HelperGeneric.logI(TAG, "toxav_ngc_video_decode:ystride=" + ystride);
                        //}
                        flush_decoder = 0;
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        return;
                    }
                    final int ystride_ = ystride;
                    //
                    Runnable myRunnable = new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                if (ystride_ == -1)
                                {
                                    ngc_video_view.setImageResource(R.drawable.round_loading_animation);
                                }
                                else
                                {
                                    int w2_decoder = ystride_; // encoder stride
                                    int w2_decoder_uv = ystride_ / 2; // encoder stride
                                    int h2_decoder = 640; // 320;
                                    int h2_decoder_uv = h2_decoder / 2;
                                    final int y_bytes2_decoder = h2_decoder * w2_decoder;
                                    final int u_bytes2_decoder = (h2_decoder_uv * w2_decoder_uv);
                                    final int v_bytes2_decoder = (h2_decoder_uv * w2_decoder_uv);

                                    // KHANDAQ (security D-4): w2_decoder is the stride the (untrusted) remote
                                    // frame made the decoder report. If it overflows the fixed y/u/v buffers,
                                    // the put() below throws IndexOutOfBounds → DoS. Drop the bad frame.
                                    if (w2_decoder < 2 || y_bytes2_decoder > y_buf2.length
                                            || u_bytes2_decoder > u_buf2.length
                                            || v_bytes2_decoder > v_buf2.length)
                                    {
                                        ngc_video_view.setImageResource(R.drawable.round_loading_animation);
                                        return;
                                    }

                                    ByteBuffer yuv_frame_data_buf = ByteBuffer.allocateDirect(
                                            y_bytes2_decoder + u_bytes2_decoder + v_bytes2_decoder);
                                    yuv_frame_data_buf.rewind();
                                    //
                                    yuv_frame_data_buf.put(y_buf2, 0, y_bytes2_decoder);
                                    yuv_frame_data_buf.put(u_buf2, 0, u_bytes2_decoder);
                                    yuv_frame_data_buf.put(v_buf2, 0, v_bytes2_decoder);
                                    //
                                    yuv_frame_data_buf.rewind();
                                    ngc_alloc_in.copyFrom(yuv_frame_data_buf.array());
                                    ngc_yuvToRgb.setInput(ngc_alloc_in);
                                    ngc_yuvToRgb.forEach(ngc_alloc_out);
                                    ngc_alloc_out.copyTo(ngc_video_frame_image);
                                    ngc_video_view.setBitmap(ngc_video_frame_image);
                                }
                                ngc_video_frame_last_incoming_ts = System.currentTimeMillis();
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
    }

    public static void show_ngc_incoming_video_frame_v2(final long group_number, final long peer_id, final byte[] encoded_video_and_header, long length)
    {
        if (MainActivity.group_message_list_activity == null)
        {
            // NGC group activity not open
            return;
        }

        final long conference_num = tox_group_by_groupid__wrapper(group_id);
        if (conference_num != group_number)
        {
            // wrong NGC group
            return;
        }

        // KHANDAQ (security D-5): drop frames that arrive faster than our ~30 fps cap (anti-flood).
        final long now_av_frame = System.currentTimeMillis();
        if ((now_av_frame - ngc_video_frame_last_processed_ts) < NGC_VIDEO_MIN_FRAME_INTERVAL_MS)
        {
            return;
        }
        ngc_video_frame_last_processed_ts = now_av_frame;

        ngc_video_packet_last_incoming_ts = System.currentTimeMillis();

        if ((ngc_video_frame_image != null) && (!ngc_video_frame_image.isRecycled()))
        {
            if (sending_video_to_group == true)
            {
                final String ngc_incoming_video_from_peer = tox_group_peer_get_public_key__wrapper(group_number,
                                                                                                   peer_id);
                ngc_update_video_incoming_peer_list(ngc_incoming_video_from_peer);

                if (ngc_video_showing_video_from_peer_pubkey.equals("-1"))
                {
                    ngc_video_showing_video_from_peer_pubkey = ngc_incoming_video_from_peer;
                }
                else if (!ngc_video_showing_video_from_peer_pubkey.equalsIgnoreCase(ngc_incoming_video_from_peer))
                {
                    // we are already showing the video of a different peer in the group
                    return;
                }

                // remove header from data (14 bytes)
                final int yuv_frame_encoded_bytes = (int) (length - 14);
                if ((yuv_frame_encoded_bytes > 0) && (yuv_frame_encoded_bytes < 40000))
                {
                    // TODO: make faster and better. this is not optimized.
                    final byte[] yuv_frame_encoded_buf = new byte[yuv_frame_encoded_bytes];
                    int w2 = 480 + 32; // 240 + 16; // encoder stride added
                    int h2 = 640; // 320;
                    final int y_bytes2 = w2 * h2;
                    final int u_bytes2 = (w2 * h2) / 4;
                    final int v_bytes2 = (w2 * h2) / 4;
                    final byte[] y_buf2 = new byte[y_bytes2];
                    final byte[] u_buf2 = new byte[u_bytes2];
                    final byte[] v_buf2 = new byte[v_bytes2];
                    int ystride = -1;
                    final byte[] chkskum = new byte[1];
                    final byte[] low_seqnum = new byte[1];
                    final byte[] high_seqnum = new byte[1];
                    try
                    {
                        System.arraycopy(encoded_video_and_header, 14, yuv_frame_encoded_buf, 0,
                                         yuv_frame_encoded_bytes);

                        System.arraycopy(encoded_video_and_header, 11, low_seqnum, 0, 1);
                        System.arraycopy(encoded_video_and_header, 12, high_seqnum, 0, 1);
                        System.arraycopy(encoded_video_and_header, 13, chkskum, 0, 1);
                        long seqnum = Byte.toUnsignedInt(low_seqnum[0]) + Integer.toUnsignedLong((high_seqnum[0] << 8));
                        if (seqnum != (last_video_seq_num + 1))
                        {
                            //HelperGeneric.logI(TAG, "!!!!!!!seqnumber_missing!!!!! " + seqnum + " -> " + (last_video_seq_num + 1));
                        }
                        last_video_seq_num = seqnum;
                        final long crc_8 = Integer.toUnsignedLong(calc_crc_8(yuv_frame_encoded_buf));
                        if (Byte.toUnsignedInt(chkskum[0]) != crc_8)
                        {
                            //HelperGeneric.logI(TAG, "checksum=" + Byte.toUnsignedInt(chkskum[0])
                            //       + " crc8=" + crc_8 + " seqnum=" + seqnum
                            //       + " yuv_frame_encoded_bytes=" + (yuv_frame_encoded_bytes + 14));
                        }
                        //
                        ystride = toxav_ngc_video_decode(yuv_frame_encoded_buf, yuv_frame_encoded_bytes, w2, h2, y_buf2,
                                                         u_buf2, v_buf2, flush_decoder);
                        //if (ystride != -1)
                        //{
                        //    HelperGeneric.logI(TAG, "toxav_ngc_video_decode:ystride=" + ystride);
                        //}
                        flush_decoder = 0;
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        return;
                    }
                    final int ystride_ = ystride;
                    //
                    Runnable myRunnable = new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                if (ystride_ == -1)
                                {
                                    ngc_video_view.setImageResource(R.drawable.round_loading_animation);
                                }
                                else
                                {
                                    int w2_decoder = ystride_; // encoder stride
                                    int w2_decoder_uv = ystride_ / 2; // encoder stride
                                    int h2_decoder = 640; // 320;
                                    int h2_decoder_uv = h2_decoder / 2;
                                    final int y_bytes2_decoder = h2_decoder * w2_decoder;
                                    final int u_bytes2_decoder = (h2_decoder_uv * w2_decoder_uv);
                                    final int v_bytes2_decoder = (h2_decoder_uv * w2_decoder_uv);

                                    // KHANDAQ (security D-4): w2_decoder is the stride the (untrusted) remote
                                    // frame made the decoder report. If it overflows the fixed y/u/v buffers,
                                    // the put() below throws IndexOutOfBounds → DoS. Drop the bad frame.
                                    if (w2_decoder < 2 || y_bytes2_decoder > y_buf2.length
                                            || u_bytes2_decoder > u_buf2.length
                                            || v_bytes2_decoder > v_buf2.length)
                                    {
                                        ngc_video_view.setImageResource(R.drawable.round_loading_animation);
                                        return;
                                    }

                                    ByteBuffer yuv_frame_data_buf = ByteBuffer.allocateDirect(
                                            y_bytes2_decoder + u_bytes2_decoder + v_bytes2_decoder);
                                    yuv_frame_data_buf.rewind();
                                    //
                                    yuv_frame_data_buf.put(y_buf2, 0, y_bytes2_decoder);
                                    yuv_frame_data_buf.put(u_buf2, 0, u_bytes2_decoder);
                                    yuv_frame_data_buf.put(v_buf2, 0, v_bytes2_decoder);
                                    //
                                    yuv_frame_data_buf.rewind();
                                    ngc_alloc_in.copyFrom(yuv_frame_data_buf.array());
                                    ngc_yuvToRgb.setInput(ngc_alloc_in);
                                    ngc_yuvToRgb.forEach(ngc_alloc_out);
                                    ngc_alloc_out.copyTo(ngc_video_frame_image);
                                    ngc_video_view.setBitmap(ngc_video_frame_image);
                                }
                                ngc_video_frame_last_incoming_ts = System.currentTimeMillis();
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
    }

    private static int calc_crc_8(byte[] yuv_frame_encoded_buf)
    {
        final int CRC_POLYNOM = 0x9c;
        final int CRC_PRESET = 0xFF;
        int crc_U = CRC_PRESET;
        for (int i = 0; i < yuv_frame_encoded_buf.length; i++)
        {
            crc_U ^= Byte.toUnsignedInt(yuv_frame_encoded_buf[i]);
            for (int j = 0; j < 8; j++)
            {
                if ((crc_U & 0x01) != 0)
                {
                    crc_U = (crc_U >>> 1) ^ CRC_POLYNOM;
                }
                else
                {
                    crc_U = (crc_U >>> 1);
                }
            }
        }
        return crc_U;
    }

    synchronized public static void start_group_video(final Context c)
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        // just in case there is a thread still running
        ngc_video_showing_video_from_peer_pubkey = "-1";
        NGC_Group_video_play_thread_running = false;
        NGC_Group_audio_record_thread_running = false;
        HelperGeneric.logI(TAG, "NGC_Group_video_play_thread_running:false:002");
        ngc_video_view_container.setVisibility(View.VISIBLE);
        sending_video_to_group = true;
        ngc_set_video_call_icon(NGC_VIDEO_ICON_STATE_ACTIVE);

        // init audio stuff ------
        try
        {
            set_calling_audio_mode();
        }
        catch (Exception ee)
        {
            ee.printStackTrace();
        }

        HelperGeneric.logI(TAG, "group_audio_service:start");
        try
        {
            Intent i = new Intent(c, GroupGroupAudioService.class);
            i.putExtra("group_id", group_id);
            c.startService(i);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "group_audio_service:EE01:" + e.getMessage());
            e.printStackTrace();
        }
        // init audio stuff ------

        NGC_Group_video_play_thread = new Thread()
        {
            @Override
            public void run()
            {
                final int sleep_millis = PREF__ngc_video_frame_delta_ms;
                try
                {
                    this.setName("t_gv_play");
                    android.os.Process.setThreadPriority(Thread.NORM_PRIORITY);
                    android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                try
                {
                    Thread.sleep(sleep_millis + 50);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    HelperGeneric.logI(TAG, "NGC_Group_video_play_thread:starting ...");
                    NGC_Group_video_play_thread_running = true;
                    HelperGeneric.logI(TAG, "NGC_Group_video_play_thread_running:true:003");
                    while (NGC_Group_video_play_thread_running)
                    {
                        // HelperGeneric.logI(TAG, "NGC_Group_video_play_thread:running --=>");
                        final int w = 480; // 240;
                        final int h = 640; // 320;
                        final int video_enc_bitrate = PREF__ngc_video_bitrate;
                        final int y_bytes = w * h;
                        final int u_bytes = (w * h) / 4;
                        final int v_bytes = (w * h) / 4;
                        // byte[] y_buf = new byte[y_bytes];
                        byte[] y_buf = y_buf__;
                        byte[] u_buf = u_buf__;
                        byte[] v_buf = v_buf__;
                        byte[] encoded_vframe = new byte[40000];
                        int encoded_bytes = toxav_ngc_video_encode(video_enc_bitrate, PREF__ngc_video_max_quantizer, w,
                                                                   h, y_buf, y_bytes, u_buf, u_bytes, v_buf, v_bytes,
                                                                   encoded_vframe);
                        // HelperGeneric.logI(TAG, "toxav_ngc_video_encode:bytes=" + encoded_bytes + " video_enc_bitrate=" + video_enc_bitrate);

                        if ((encoded_bytes < 1) || (encoded_bytes > TOX_MAX_NGC_VIDEO_AND_HEADER_SIZE))
                        {
                            // some error with encoding
                        }
                        else
                        {
                            final int header_length = 6 + 1 + 1 + 1 + 1 + 1 + 2 + 1;
                            long data_length_ = header_length + encoded_bytes;
                            final int data_length = (int) data_length_;
                            //
                            try
                            {
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
                                data_buf.put((byte) 0x02);
                                //
                                data_buf.put((byte) 0x21);
                                //
                                data_buf.put((byte) w); // width: always 480 --> LOL
                                data_buf.put((byte) h); // height: always 640 --> LOL
                                data_buf.put((byte) 1); // codec: always 1  (1 -> H264)
                                //
                                data_buf.put((byte) 1); // seq num low byte
                                data_buf.put((byte) 0); // seq num high byte
                                data_buf.put((byte) 0); // CRC8 checksum
                                //
                                data_buf.put(encoded_vframe, 0, encoded_bytes); // put encoded video frame into buffer
                                //
                                byte[] data = new byte[data_length];
                                data_buf.rewind();
                                data_buf.get(data);
                                if (data_length < MAX_GC_PACKET_CHUNK_SIZE)
                                {
                                    int result = tox_group_send_custom_packet(tox_group_by_groupid__wrapper(group_id),
                                                                              0, data, data_length);
                                    // HelperGeneric.logI(TAG, "toxav_ngc_video_encode:ls:tox_group_send_custom_packet:result=" + result + " bytes=" + encoded_bytes);
                                }
                                else
                                {
                                    int result = tox_group_send_custom_packet(tox_group_by_groupid__wrapper(group_id),
                                                                              1, data, data_length);
                                    // HelperGeneric.logI(TAG, "toxav_ngc_video_encode:LL:tox_group_send_custom_packet:result=" + result + " bytes=" + encoded_bytes);
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                            //
                            if ((ngc_video_frame_last_incoming_ts + (5 * 1000)) < System.currentTimeMillis())
                            {
                                if (ngc_video_frame_last_incoming_ts != -1)
                                {
                                    HelperGeneric.logI(TAG,
                                          "toxav_ngc_video_encode:no incoming video for 5 seconds. resetting video and peer ...");
                                    Runnable myRunnable = new Runnable()
                                    {
                                        @Override
                                        public void run()
                                        {
                                            try
                                            {
                                                ngc_video_view.setImageResource(R.drawable.round_loading_animation);
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
                                ngc_video_frame_last_incoming_ts = -1;
                                ngc_video_showing_video_from_peer_pubkey = "-1";
                            }
                        }
                        //
                        Thread.sleep(sleep_millis); // sleep
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                HelperGeneric.logI(TAG, "NGC_Group_video_play_thread:finished");
            }
        };
        NGC_Group_video_play_thread.start();

        NGC_Group_audio_record_thread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    this.setName("t_ga_rec");
                    android.os.Process.setThreadPriority(Thread.NORM_PRIORITY);
                    android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_LESS_FAVORABLE);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:starting ...");
                    NGC_Group_audio_record_thread_running = true;
                    HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:true:003");
                    while (NGC_Group_audio_record_thread_running)
                    {
                        try
                        {
                            if (ngc_audio_mute == true)
                            {
                                try
                                {
                                    ngc_audio_out_queue.poll(1, TimeUnit.MILLISECONDS);
                                    ngc_audio_out_queue.poll(1, TimeUnit.MILLISECONDS);
                                    ngc_audio_out_queue.poll(1, TimeUnit.MILLISECONDS);
                                }
                                catch (Exception e)
                                {
                                }
                                Thread.sleep(120);
                            }
                            else
                            {
                                final int buffers_in_queue = ngc_audio_out_queue.size();
                                // HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:buffers_in_queue=" + buffers_in_queue);
                                if (buffers_in_queue > 2)
                                {
                                    final byte[] buf_out_1 = ngc_audio_out_queue.poll(1, TimeUnit.MILLISECONDS);
                                    // HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:buf1=" + bytes_to_hex(buf_out_1));
                                    final byte[] buf_out_2 = ngc_audio_out_queue.poll(1, TimeUnit.MILLISECONDS);
                                    // HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:buf2=" + bytes_to_hex(buf_out_2));
                                    final byte[] buf_out_3 = ngc_audio_out_queue.poll(1, TimeUnit.MILLISECONDS);
                                    // HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:buf3=" + bytes_to_hex(buf_out_3));
                                    if ((buf_out_1 == null) || (buf_out_2 == null) || (buf_out_3 == null))
                                    {
                                        HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:no data in buffers");
                                    }
                                    else if ((buf_out_1.length != NGC_AUDIO_PCM_BUFFER_BYTES) ||
                                             (buf_out_2.length != NGC_AUDIO_PCM_BUFFER_BYTES) ||
                                             (buf_out_3.length != NGC_AUDIO_PCM_BUFFER_BYTES))
                                    {
                                        HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:wrong buffer sizes");
                                    }
                                    else
                                    {
                                        // send ngc audio packet
                                        final byte[] pcm_audio_buffer = new byte[3 *
                                                                                 NGC_AUDIO_PCM_BUFFER_BYTES]; // 3 x 3840 bytes pcm data
                                        final int max_encoded_bytes = (MAX_GC_PACKET_CHUNK_SIZE - 10);
                                        final byte[] encoded_aframe = new byte[max_encoded_bytes];
                                        final int samples_per_120ms = NGC_AUDIO_PCM_BUFFER_SAMPLES;
                                        // copy the 3 pcm buffers into new buffer ---------
                                        System.arraycopy(buf_out_1, 0, pcm_audio_buffer, 0 * NGC_AUDIO_PCM_BUFFER_BYTES,
                                                         NGC_AUDIO_PCM_BUFFER_BYTES);
                                        System.arraycopy(buf_out_2, 0, pcm_audio_buffer, 1 * NGC_AUDIO_PCM_BUFFER_BYTES,
                                                         NGC_AUDIO_PCM_BUFFER_BYTES);
                                        System.arraycopy(buf_out_3, 0, pcm_audio_buffer, 2 * NGC_AUDIO_PCM_BUFFER_BYTES,
                                                         NGC_AUDIO_PCM_BUFFER_BYTES);
                                        // copy the 3 pcm buffers into new buffer ---------
                                        int encoded_bytes = toxav_ngc_audio_encode(pcm_audio_buffer, samples_per_120ms,
                                                                                   encoded_aframe);
                                        if ((encoded_bytes < 1) || (encoded_bytes > max_encoded_bytes))
                                        {
                                            // some error with encoding
                                        }
                                        else
                                        {
                                            // HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:encoded bytes=" + encoded_bytes);
                                            final int header_length = 6 + 1 + 1 + 1 + 1;
                                            long data_length_ = header_length + encoded_bytes;
                                            final int data_length = (int) data_length_;
                                            //
                                            try
                                            {
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
                                                data_buf.put((byte) 0x01);
                                                //
                                                data_buf.put((byte) 0x31);
                                                //
                                                data_buf.put((byte) 1); // always 1 (for MONO)
                                                data_buf.put((byte) 48); // always 48 (for 48kHz)
                                                //
                                                data_buf.put(encoded_aframe, 0,
                                                             encoded_bytes); // put encoded audio frame into buffer
                                                //
                                                byte[] data = new byte[data_length];
                                                data_buf.rewind();
                                                data_buf.get(data);
                                                if (data_length < MAX_GC_PACKET_CHUNK_SIZE)
                                                {
                                                    int result = tox_group_send_custom_packet(
                                                            tox_group_by_groupid__wrapper(group_id), 0, data,
                                                            data_length);
                                                    // HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:ls:tox_group_send_custom_packet:result=" + result + " bytes=" + encoded_bytes);
                                                }
                                                else
                                                {
                                                    int result = tox_group_send_custom_packet(
                                                            tox_group_by_groupid__wrapper(group_id), 1, data,
                                                            data_length);
                                                    // HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:LL:tox_group_send_custom_packet:result=" + result + " bytes=" + encoded_bytes);
                                                }
                                            }
                                            catch (Exception e)
                                            {
                                                e.printStackTrace();
                                            }
                                            //
                                        }

                                    }
                                }
                                else
                                {
                                    Thread.sleep(60);
                                }
                            }
                        }
                        catch (Exception e)
                        {
                        }
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                HelperGeneric.logI(TAG, "NGC_Group_audio_record_thread:finished");
            }
        };
        NGC_Group_audio_record_thread.start();

        // update every x times per second -----------
        final int update_per_sec = 8;
        final Handler ha2 = new Handler();
        ha2.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    // HelperGeneric.logI(TAG, "update_call_time -> call");
                    update_call_ngc_audio_bars();
                    if (NGC_Group_audio_record_thread_running)
                    {
                        ha2.postDelayed(this, 1000 / update_per_sec);
                    }
                }
                catch (Exception e)
                {
                }
            }
        }, 1000);
        // update every x times per second -----------

        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    public void toggle_group_video(final View view)
    {
        if (sending_video_to_group)
        {
            stop_group_video(view.getContext(), true);
            closeCamera();
        }
        else
        {
            AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext());
            builder.setTitle(R.string.group_video_join_title);
            builder.setMessage(R.string.group_video_join_message);

            builder.setNegativeButton(R.string.group_video_join_decline, null);
            builder.setPositiveButton(R.string.group_video_join_confirm, new DialogInterface.OnClickListener()
            {
                @Override
                public void onClick(DialogInterface dialog, int which)
                {
                    if (!(view.getContext() instanceof GroupMessageListActivity))
                    {
                        return;
                    }

                    final GroupMessageListActivity activity = (GroupMessageListActivity) view.getContext();
                    activity.ensureNgcVideoPermissions(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            ngc_incoming_video_peer_toggle_current_index = 0;
                            flush_decoder = 1;
                            start_group_video(activity);
                        }
                    });
                }
            });

            AlertDialog dialog = builder.create();
            dialog.show();
        }
    }

    private boolean hasNgcVideoPermissions()
    {
        return HelperCall.hasMicrophonePermission(this) && HelperCall.hasCameraPermission(this);
    }

    private void ensureNgcVideoPermissions(final Runnable onGranted)
    {
        if (hasNgcVideoPermissions())
        {
            if (onGranted != null)
            {
                onGranted.run();
            }

            return;
        }

        pendingNgcPermissionAction = onGranted;
        HelperCall.requestCallPermissions(this, false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != HelperCall.REQUEST_CALL_PERMISSIONS)
        {
            return;
        }

        if (!hasNgcVideoPermissions())
        {
            HelperCall.showMissingPermissionToast(this, false);
            pendingNgcPermissionAction = null;
            return;
        }

        final Runnable action = pendingNgcPermissionAction;
        pendingNgcPermissionAction = null;

        if (action != null)
        {
            action.run();
        }
    }

    // KHANDAQ (#12): any picker we launch from the group chat (file/camera/gallery) briefly sends
    // us to the background; suppress the app-lock for that single return so it doesn't demand a PIN.
    @Override
    public void startActivityForResult(Intent intent, int requestCode)
    {
        AppLockHelper.suppressNextLock();
        super.startActivityForResult(intent, requestCode);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SelectFriendSingleActivity_ID && resultCode == RESULT_OK && data != null &&
            data.getData() != null)
        {
            try
            {
                final String result_data = data.getData().toString();
                final String result_friend_pubkey = result_data.substring(2);
                if (result_friend_pubkey.length() == TOX_PUBLIC_KEY_SIZE * 2)
                {
                    if (group_id.equals("-1"))
                    {
                        group_id = group_id_prev;
                    }
                    invite_friend_to_group(group_id, result_friend_pubkey);
                }
            }
            catch (Exception e)
            {
                Log.w(TAG, "onActivityResult:invite_friend_to_group:EE:" + e.getMessage());
            }
        }
        else if ((requestCode == CAMERA_PHOTO_ID || requestCode == CAMERA_VIDEO_ID)
                && resultCode == Activity.RESULT_OK)
        {
            final Uri captured = pending_camera_capture_uri;
            pending_camera_capture_uri = null;
            if (captured != null)
            {
                MediaSendPreviewHelper.dispatchAttachments(this, java.util.Collections.singletonList(captured),
                        MediaSendPreviewHelper.TARGET_GROUP, -1, group_id, true);
            }
        }
        else if (requestCode == MEDIAPICK_ID_001 && resultCode == Activity.RESULT_OK)
        {
            if (data != null)
            {
                prepareGroupMediaSendContext();

                final List<Uri> pickedUris = MediaSendPreviewHelper.collectUris(data);
                MediaSendPreviewHelper.persistUriPermissions(this, pickedUris);

                if (MediaSendPreviewHelper.launchPreviewIfNeeded(this, data,
                        MediaSendPreviewHelper.TARGET_GROUP, -1, group_id, true))
                {
                    return;
                }

                if (pickedUris.isEmpty())
                {
                    add_attachment_ngc(this, data, data, group_id, true);
                }
                else
                {
                    MediaSendPreviewHelper.dispatchAttachments(this, pickedUris,
                            MediaSendPreviewHelper.TARGET_GROUP, -1, group_id, true);
                }
            }
        }
        else if (requestCode == FILEPICK_ID && resultCode == Activity.RESULT_OK)
        {
            // KHANDAQ (Figma): "Файл" — send the picked document as-is (no media preview).
            if (data != null && data.getData() != null)
            {
                prepareGroupMediaSendContext();
                MediaSendPreviewHelper.persistUriPermissions(this,
                        java.util.Collections.singletonList(data.getData()));
                add_attachment_ngc(this, data, data, group_id, true);
            }
        }
        else if (requestCode == MediaSendPreviewHelper.REQUEST_PREVIEW && resultCode == Activity.RESULT_OK)
        {
            if (mediaPreviewResultHandled)
            {
                return;
            }
            mediaPreviewResultHandled = true;
            prepareGroupMediaSendContext();
            MediaSendPreviewHelper.handlePreviewResult(this, data,
                    MediaSendPreviewHelper.TARGET_GROUP, -1, group_id, true);
        }
    }

    static GroupOutgoingFileHandle add_outgoing_file(Context c, String groupid, String filepath, String filename, Uri uri, long file_size_manual, boolean real_file_path, boolean update_message_view, boolean use_file_size_manual)
    {
        return add_outgoing_file(c, groupid, filepath, filename, uri, file_size_manual, real_file_path,
                update_message_view, use_file_size_manual, true);
    }

    static GroupOutgoingFileHandle add_outgoing_file(Context c, String groupid, String filepath, String filename, Uri uri, long file_size_manual, boolean real_file_path, boolean update_message_view, boolean use_file_size_manual, final boolean startTransferImmediately)
    {
        long file_size_hint = -1;
        try
        {
            if (use_file_size_manual)
            {
                file_size_hint = file_size_manual;
            }
            else
            {
                file_size_hint = resolve_uri_file_size(c, uri);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:EE003:" + e.getMessage());
        }

        if (file_size_hint > FT_OUTGOING_FILESIZE_NGC_MAX_TOTAL)
        {
            display_toast(c.getString(R.string.group_file_too_large,
                    android.text.format.Formatter.formatFileSize(c, KHANDAQ_MAX_FILE_TRANSFER_BYTES)), true, 100);
            HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:file_size=File too large");
            return null;
        }

        MessageListActivity.outgoing_file_wrapped ofw = copy_outgoing_file_to_sdcard_dir(filepath, filename,
                file_size_hint > 0 ? file_size_hint : 1);

        if (ofw == null)
        {
            display_toast(c.getString(R.string.group_file_prepare_failed), true, 100);
            return null;
        }

        long file_size = ofw.file_size_wrapped;
        if (file_size < 1)
        {
            HelperGeneric.logI(TAG, "file length 0 ?");
            display_toast(c.getString(R.string.group_file_prepare_failed), true, 100);
            return null;
        }

        if (file_size > FT_OUTGOING_FILESIZE_NGC_MAX_TOTAL)
        {
            display_toast(c.getString(R.string.group_file_too_large,
                    android.text.format.Formatter.formatFileSize(c, KHANDAQ_MAX_FILE_TRANSFER_BYTES)), true, 100);
            return null;
        }

        if (file_size > 50L * 1024L * 1024L && startTransferImmediately)
        {
            display_toast(c.getString(R.string.group_file_send_large_confirm_message,
                    android.text.format.Formatter.formatFileSize(c, file_size)), false, 3500);
        }

        if (file_size > TOX_MAX_NGC_FILESIZE)
        {
            HelperGeneric.logI(TAG, "add_outgoing_file:chunked:bytes=" + file_size);
        }

        if ((file_size < 1) || (file_size > TRIFAGlobals.KHANDAQ_MAX_FILE_TRANSFER_BYTES))
        {
            display_toast(c.getString(R.string.group_file_prepare_failed), true, 100);
            return null;
        }

        HelperGeneric.logI(TAG, "add_outgoing_file:001");

        // add FT message to UI
        GroupMessage m = new GroupMessage();
        m.is_new = false; // own messages are always "not new"
        m.tox_group_peer_pubkey = tox_group_self_get_public_key(
                tox_group_by_groupid__wrapper(groupid)).toUpperCase();
        m.direction = 1; // msg sent
        m.TOX_MESSAGE_TYPE = 0;
        m.read = true; // !!!! there is not "read status" with conferences in Tox !!!!
        m.tox_group_peername = null;
        m.private_message = 0;
        m.group_identifier = groupid.toLowerCase();
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
        m.sent_timestamp = System.currentTimeMillis();
        m.rcvd_timestamp = System.currentTimeMillis(); // since we do not have anything better assume "now"
        m.text = HelperFiletransfer.buildOutgoingFileMessageText(c, ofw.filename_wrapped, ofw.file_size_wrapped);
        m.was_synced = false;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
        m.path_name = ofw.filepath_wrapped;
        m.file_name = ofw.filename_wrapped;
        m.filename_fullpath = new java.io.File(ofw.filepath_wrapped + "/" + ofw.filename_wrapped).getAbsolutePath();
        m.storage_frame_work = false;
        try
        {
            m.filesize = new java.io.File(ofw.filepath_wrapped + "/" + ofw.filename_wrapped).length();
        }
        catch (Exception ee)
        {
            m.filesize = 0;
        }

        ByteBuffer hash_bytes = ByteBuffer.allocateDirect(TOX_HASH_LENGTH);
        MainActivity.tox_messagev3_get_new_message_id(hash_bytes);
        m.msg_id_hash = bytebuffer_to_hexstring(hash_bytes, true);
        m.message_id_tox = "";
        insert_into_group_message_db(m, true);
        HelperGeneric.logI(TAG, "add_outgoing_file:090");

        if (startTransferImmediately)
        {
            HelperGeneric.logI(TAG, "add_outgoing_file:091:send_group_file:start");
            HelperGroup.record_ngc_transfer_started(m.group_identifier, m.msg_id_hash);
            send_group_file(m);
            HelperGeneric.logI(TAG, "add_outgoing_file:092:send_group_file:done");
        }

        return new GroupOutgoingFileHandle(groupid.toLowerCase(), m.msg_id_hash,
                NgcGroupFileTransfer.shouldUseChunkedTransfer(file_size));
    }

    static void show_messagelist_for_id(Context c, String id, String fill_out_text)
    {
        Intent intent = new Intent(c, GroupMessageListActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (fill_out_text != null)
        {
            intent.putExtra("fillouttext", fill_out_text);
        }
        intent.putExtra("group_id", id);
        c.startActivity(intent);
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        setIntent(intent);
        final String new_group_id = intent.getStringExtra("group_id");
        if ((new_group_id == null) || new_group_id.equalsIgnoreCase(group_id))
        {
            return;
        }
        try
        {
            if ((group_id != null) && (ml_new_group_message != null))
            {
                ChatDraftHelper.save_group_draft(group_id, ml_new_group_message.getText().toString());
            }
        }
        catch (Exception ignored)
        {
        }
        recreate();
    }

    public void scroll_to_bottom(View v)
    {
        try
        {
            MainActivity.group_message_list_fragment.listingsView.scrollToPosition(
                    MainActivity.group_message_list_fragment.adapter.getItemCount() - 1);
        }
        catch (Exception ignored)
        {
        }

        GroupMessageListFragment.is_at_bottom = true;

        try
        {
            do_fade_anim_on_fab(MainActivity.group_message_list_fragment.unread_messages_notice_button, false,
                                this.getClass().getName());
            MainActivity.group_message_list_fragment.unread_messages_notice_button.setSupportBackgroundTintList(
                    (ContextCompat.getColorStateList(context_s, R.color.message_list_scroll_to_bottom_fab_bg_normal)));
        }
        catch (Exception ignored)
        {
        }
    }

    static void init_native_audio_stuff()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        final int sampling_rate = 48000;
        final int channels = 1;
        final int sample_count = NGC_AUDIO_PCM_BUFFER_SAMPLES; // bytes = sample_count * 2

        if (Callstate.call_first_audio_frame_received == -1)
        {
            Callstate.call_first_audio_frame_received = System.currentTimeMillis();
            // HINT: PCM_16 needs 2 bytes per sample per channel
            AudioReceiver.buffer_size = ((int) ((48000 * 2) * 2)) * audio_out_buffer_mult; // TODO: this is really bad
            AudioReceiver.sleep_millis = (int) (((float) sample_count / (float) sampling_rate) * 1000.0f *
                                                0.9f); // TODO: this is bad also
            HelperGeneric.logI(TAG, "init_native_audio_stuff:read:init buffer_size=" + AudioReceiver.buffer_size);
            HelperGeneric.logI(TAG, "init_native_audio_stuff:read:init sleep_millis=" + AudioReceiver.sleep_millis);
        }

        if (sampling_rate_ != sampling_rate)
        {
            sampling_rate_ = sampling_rate;
        }

        if (channels_ != channels)
        {
            channels_ = channels;
        }

        if ((NativeAudio.sampling_rate != (int) sampling_rate_) || (NativeAudio.channel_count != channels_))
        {
            HelperGeneric.logI(TAG, "init_native_audio_stuff:values_changed");
            NativeAudio.sampling_rate = (int) sampling_rate_;
            NativeAudio.channel_count = channels_;
            HelperGeneric.logI(TAG, "init_native_audio_stuff:NativeAudio restart Engine");
            // TODO: locking? or something like that
            NativeAudio.restartNativeAudioPlayEngine((int) sampling_rate_, channels_);
        }
        NativeAudio.n_cur_buf = 0;
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }

    static void update_call_ngc_audio_bars()
    {
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
        try
        {
            Runnable myRunnable = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        if (ngc_audio_mute == false)
                        {
                            ngc_audio_bar_in_v.setLevel(get_vu_in() / 90.0f);
                        }
                        ngc_audio_bar_out_v.setLevel(get_vu_out() / 140.0f);
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
        catch (Exception e)
        {
            e.printStackTrace();
        }
        if (com.zoffcc.applications.trifa.MainActivity.INSANE_TRACE_LOGGING) { com.zoffcc.applications.trifa.HelperGeneric.log_source_line(); }
    }
}

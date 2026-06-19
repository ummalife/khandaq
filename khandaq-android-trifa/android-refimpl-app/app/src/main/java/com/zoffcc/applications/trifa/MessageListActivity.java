/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.piasy.rxandroidaudio.AudioRecorder;
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.FriendList;
import com.vanniktech.emoji.EmojiPopup;
import com.vanniktech.emoji.listeners.OnEmojiBackspaceClickListener;
import com.vanniktech.emoji.listeners.OnEmojiPopupDismissListener;
import com.vanniktech.emoji.listeners.OnEmojiPopupShownListener;
import com.vanniktech.emoji.listeners.OnSoftKeyboardCloseListener;
import com.vanniktech.emoji.listeners.OnSoftKeyboardOpenListener;
import com.zoffcc.applications.sorm.Filetransfer;
import com.zoffcc.applications.sorm.Message;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.annotation.Px;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import static android.widget.Toast.LENGTH_LONG;
import static com.zoffcc.applications.nativeaudio.NativeAudio.set_aec_active;
import static com.zoffcc.applications.nativeaudio.NativeAudio.set_gainprocessing_active;
import static com.zoffcc.applications.nativeaudio.NativeAudio.set_audio_aec_delay;
import static com.zoffcc.applications.trifa.CallingActivity.initializeScreenshotSecurity;
import static com.zoffcc.applications.trifa.CallingActivity.set_debug_text;
import static com.zoffcc.applications.trifa.CallingActivity.update_top_text_line;
import static com.zoffcc.applications.trifa.HelperFiletransfer.copy_outgoing_file_to_sdcard_dir;
import static com.zoffcc.applications.trifa.HelperFiletransfer.insert_into_filetransfer_db;
import static com.zoffcc.applications.trifa.HelperFiletransfer.queue_and_try_send_outgoing_file;
import static com.zoffcc.applications.trifa.HelperFiletransfer.update_filetransfer_db_full;
import static com.zoffcc.applications.trifa.HelperFriend.friend_call_push_url;
import static com.zoffcc.applications.trifa.HelperFriend.get_friend_name_from_pubkey;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_get_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.get_vfs_image_filename_friend_avatar;
import static com.zoffcc.applications.trifa.HelperGeneric.put_vfs_image_on_imageview_real;
import static com.zoffcc.applications.trifa.HelperFriend.main_get_friend;
import static com.zoffcc.applications.trifa.HelperGeneric.do_fade_anim_on_fab;
import static com.zoffcc.applications.trifa.HelperGeneric.get_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.get_uniq_tmp_filename;
import static com.zoffcc.applications.trifa.HelperGeneric.seconds_time_format_or_empty;
import static com.zoffcc.applications.trifa.HelperGeneric.set_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.tox_friend_send_message_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.trim_to_utf8_length_bytes;
import static com.zoffcc.applications.trifa.HelperMessage.insert_into_message_db;
import static com.zoffcc.applications.trifa.HelperMessage.update_single_message_from_messge_id;
import static com.zoffcc.applications.trifa.HelperMsgNotification.change_msg_notification;
import static com.zoffcc.applications.trifa.MainActivity.CallingWaitingActivity_ID;
import static com.zoffcc.applications.trifa.MainActivity.launch_outgoing_calling_activity;
import static com.zoffcc.applications.trifa.MainActivity.PREF__X_eac_delay_ms;
import static com.zoffcc.applications.trifa.MainActivity.PREF__messageview_paging;
import static com.zoffcc.applications.trifa.MainActivity.PREF__use_incognito_keyboard;
import static com.zoffcc.applications.trifa.MainActivity.PREF__use_software_aec;
import static com.zoffcc.applications.trifa.MainActivity.PREF__window_security;
import static com.zoffcc.applications.trifa.HelperGeneric.clear_audio_play_buffers;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_FILES_OUTGOING_WRAPPER_DIR;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_TMP_DIR;
import static com.zoffcc.applications.trifa.MainActivity.bootstrap_single;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.main_activity_s;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.message_list_activity;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages_incoming_file;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages_text_only;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_set_typing;
import static com.zoffcc.applications.trifa.MessageListFragment.search_messages_text;
import static com.zoffcc.applications.trifa.MessageListFragment.show_only_files;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FT_OUTGOING_FILESIZE_BYTE_USE_STORAGE_FRAMEWORK;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FT_OUTGOING_FILESIZE_FRIEND_MAX_TOTAL;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FT_OUTGOING_FILESIZE_NGC_MAX_TOTAL;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_AUDIO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.GLOBAL_VIDEO_BITRATE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_REMOVE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TEXT_QUOTE_STRING_1;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TEXT_QUOTE_STRING_2;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_OUTGOING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_FRAME_RATE_INCOMING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VIDEO_FRAME_RATE_OUTGOING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.count_video_frame_received;
import static com.zoffcc.applications.trifa.TRIFAGlobals.count_video_frame_sent;
import static com.zoffcc.applications.trifa.TRIFAGlobals.last_video_frame_received;
import static com.zoffcc.applications.trifa.TRIFAGlobals.last_video_frame_sent;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_DATA;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.TrifaToxService.wakeup_tox_thread;

// import com.vanniktech.emoji.listeners.OnEmojiClickedListener;

/** @noinspection ConstantValue*/
public class MessageListActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.MsgListActivity";
    /** Survives onPause while gallery/preview picker is open (onActivityResult runs before onResume). */
    static String outgoing_attachment_friend_pubkey = null;
    long friendnum = -1;
    long friendnum_prev = -1;
    String friend_pubkey = null;
    static final int MEDIAPICK_ID_001 = 8002;
    // KHANDAQ (#17): camera capture (photo/video) result codes + the URI we hand the camera app.
    static final int CAMERA_PHOTO_ID = 8021;
    static final int CAMERA_VIDEO_ID = 8022;
    private Uri pending_camera_capture_uri = null;

    private boolean mediaPreviewResultHandled = false;
    /** True while document picker or {@link MediaSendPreviewActivity} owns the foreground. */
    private boolean outgoingMediaPickerActive = false;
    //
    static com.vanniktech.emoji.EmojiEditText ml_new_message = null;
    com.vanniktech.emoji.EmojiPopup emojiPopup = null;
    ImageView insert_emoji = null;
    TextView ml_maintext = null;
    TextView ml_presence_status = null;
    ViewGroup rootView = null;
    //
    static TextView ml_friend_typing = null;
    ImageView ml_icon = null;
    de.hdodenhof.circleimageview.CircleImageView ml_avatar = null;
    ImageView ml_status_icon = null;
    ImageButton ml_phone_icon = null;
    ImageButton ml_video_icon = null;
    ImageButton ml_attach_button_01 = null;
    ImageButton ml_button_recaudio = null;
    static ChatVoiceRecordingUiHelper voiceRecordingUiHelper = null;
    static boolean ml_is_recording = false;
    static boolean ml_is_rec_ok = false;
    static int global_typing = 0;
    Thread typing_flag_thread = null;
    final static int TYPING_FLAG_DEACTIVATE_DELAY_IN_MILLIS = 1000; // 1 second
    static boolean attachemnt_instead_of_send = true;
    static boolean friend_is_typing = false;
    private Runnable typing_anim_runnable = null;
    private int typing_dot_frame = 0;
    private static final int TYPING_ANIM_INTERVAL_MS = 450;
    static ActionMode amode = null;
    static MenuItem amode_save_menu_item = null;
    static MenuItem amode_info_menu_item = null;
    static boolean oncreate_finished = false;
    private boolean pendingCallAfterPermission = false;
    CustomSpinner spinner_filter_msgs = null;
    SearchView messageSearchView = null;

    Handler mla_handler = null;
    static Handler mla_handler_s = null;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        oncreate_finished = false;
        HelperGeneric.logI(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        HelperGeneric.logI(TAG, "onCreate:002");

        amode = null;
        amode_save_menu_item = null;
        amode_info_menu_item = null;
        selected_messages.clear();
        selected_messages_text_only.clear();
        selected_messages_incoming_file.clear();

        try
        {
            // reset search and filter flags, sooner
            show_only_files = false;
            search_messages_text = null;
        }
        catch (Exception e)
        {
        }

        mla_handler = new Handler(Looper.getMainLooper())
        {
            @Override
            public void handleMessage(android.os.Message m)
            {
                try
                {
                    switch (m.what)
                    {
                        case 1:
                            stop_self_typing_indicator();
                            break;
                        default:
                            super.handleMessage(m);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    super.handleMessage(m);
                }
            }
        };

        mla_handler_s = mla_handler;

        Intent intent = getIntent();
        friendnum = intent.getLongExtra("friendnum", -1);
        friend_pubkey = intent.getStringExtra("friend_pubkey");
        if (friend_pubkey != null)
        {
            friend_pubkey = friend_pubkey.toUpperCase();
        }
        // HelperGeneric.logI(TAG, "onCreate:003:friendnum=" + friendnum + " friendnum_prev=" + friendnum_prev);
        friendnum_prev = friendnum;
        resolve_friend_for_chat();
        sync_outgoing_attachment_friend_pubkey();

        setContentView(R.layout.activity_message_list);

        message_list_activity = this;

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.enableUpNavigation(this, toolbar);

        rootView = (ViewGroup) findViewById(R.id.emoji_bar);
        ml_new_message = (com.vanniktech.emoji.EmojiEditText) findViewById(R.id.ml_new_message);
        HelperGeneric.apply_chat_input_field_style(ml_new_message);
        ChatReplyPreviewController.bind(this, ml_new_message);

        messageSearchView = (SearchView) findViewById(R.id.search_view_messages);
        messageSearchView.setQueryHint(getString(R.string.messages_search_default_text));
        messageSearchView.setIconifiedByDefault(true);

        spinner_filter_msgs = (CustomSpinner) findViewById(R.id.spinner_filter_msgs);
        ArrayList<String> chat_filter_labels = new ArrayList<>(Arrays.asList(
                getString(R.string.chat_filter_all_messages),
                getString(R.string.chat_filter_files_only)));
        ArrayAdapter<String> myAdapter = new FilterMsgsSpinnerAdapter(this,
                R.layout.chat_filter_spinner_dropdown_item, chat_filter_labels);

        if (spinner_filter_msgs != null)
        {
            spinner_filter_msgs.setContentDescription(getString(R.string.chat_filter_spinner_description));
            spinner_filter_msgs.setAdapter(myAdapter);
            spinner_filter_msgs.setSelection(0);
            spinner_filter_msgs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
            {
                @Override
                public void onItemSelected(AdapterView<?> parentView, View v, int position, long id)
                {
                    try
                    {
                        if (id == 0)
                        {
                            // id: all messages
                            messageSearchView.setQuery("", false);
                            messageSearchView.setIconified(true);
                            show_only_files = false;
                            search_messages_text = null;
                            MainActivity.message_list_fragment.update_all_messages(false, false,
                                                                                   PREF__messageview_paging);
                        }
                        else if (id == 1)
                        {
                            // id: only files
                            messageSearchView.setQuery("", false);
                            messageSearchView.setIconified(true);
                            show_only_files = true;
                            search_messages_text = null;
                            MainActivity.message_list_fragment.update_all_messages(false, false,
                                                                                   PREF__messageview_paging);
                        }
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent)
                {
                }
            });
        }

        try
        {
            // reset search and filter flags
            messageSearchView.setQuery("", false);
            messageSearchView.setIconified(true);
            show_only_files = false;
            search_messages_text = null;
        }
        catch (Exception e)
        {
        }

        // give focus to text input
        ml_new_message.requestFocus();
        try
        {
            // hide softkeyboard initially
            // since it takes a lot of screen space
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        catch (Exception e)
        {
        }

        insert_emoji = (ImageView) findViewById(R.id.insert_emoji);
        ml_friend_typing = (TextView) findViewById(R.id.ml_friend_typing);
        ml_maintext = (TextView) findViewById(R.id.ml_maintext);
        ml_presence_status = (TextView) findViewById(R.id.ml_presence_status);
        ChatBubbleUiHelper.apply_chat_input_bar_theme(findViewById(R.id.emoji_bar));
        ml_avatar = (de.hdodenhof.circleimageview.CircleImageView) findViewById(R.id.ml_avatar);
        ml_icon = (ImageView) findViewById(R.id.ml_icon);
        ml_status_icon = (ImageView) findViewById(R.id.ml_status_icon);
        ml_phone_icon = (ImageButton) findViewById(R.id.ml_phone_icon);
        ml_video_icon = (ImageButton) findViewById(R.id.ml_video_icon);
        apply_chat_header();
        ml_attach_button_01 = (ImageButton) findViewById(R.id.ml_button_01);
        ml_button_recaudio = (ImageButton) findViewById(R.id.ml_button_recaudio);
        ml_button_recaudio.setBackgroundColor(Color.TRANSPARENT);
        voiceRecordingUiHelper = ChatVoiceRecordingUiHelper.bind(findViewById(R.id.emoji_bar),
                new ChatVoiceRecordingUiHelper.RecordingActionListener()
                {
                    @Override
                    public void onSend()
                    {
                        ml_is_rec_ok = true;
                        ml_is_recording = false;
                        set_recording_pop_visibilty_s(false);
                    }

                    @Override
                    public void onCancel()
                    {
                        ml_is_rec_ok = false;
                        ml_is_recording = false;
                        set_recording_pop_visibilty_s(false);
                    }
                });

        ml_is_recording = false;
        ml_is_rec_ok = false;
        ml_icon.setImageResource(R.drawable.circle_red);
        ml_status_icon.setImageResource(R.drawable.circle_green);
        update_friend_presence_status();

        final View profileHeaderTap = findViewById(R.id.ml_header_profile_tap);
        if (profileHeaderTap != null)
        {
            profileHeaderTap.setOnClickListener(v -> open_friend_info_activity());
        }

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
                        show_only_files = false;
                        search_messages_text = null;
                        MainActivity.message_list_fragment.update_all_messages(false, false, PREF__messageview_paging);
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
                        show_only_files = false;
                        search_messages_text = query;
                        MainActivity.message_list_fragment.update_all_messages(false, false, PREF__messageview_paging);
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
                        show_only_files = false;
                        MessageListFragment.search_messages_text = null;
                        MainActivity.message_list_fragment.update_all_messages(false, false, PREF__messageview_paging);
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
                        show_only_files = false;
                        MessageListFragment.search_messages_text = query;
                        MainActivity.message_list_fragment.update_all_messages(false, false, PREF__messageview_paging);
                    }
                    catch (Exception e2)
                    {
                        e2.printStackTrace();
                    }
                }

                return true;
            }
        });

        setUpEmojiPopup();

        final Drawable d1 = new IconicsDrawable(getBaseContext()).
                icon(GoogleMaterial.Icon.gmd_sentiment_satisfied).
                color(getResources().
                        getColor(R.color.icon_colors)).
                sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);

        insert_emoji.setImageDrawable(d1);
        // insert_emoji.setImageResource(R.drawable.emoji_ios_category_people);

        insert_emoji.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(final View v)
            {
                emojiPopup.toggle();
            }
        });

        final Drawable add_attachement_icon = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_attachment).color(
                getResources().getColor(R.color.icon_colors)).sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);
        final Drawable send_message_icon = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_send).color(
                getResources().getColor(R.color.icon_colors)).sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);
        final Drawable mic_icon = getResources().getDrawable(R.drawable.baseline_keyboard_voice_24);

        ml_friend_typing.setText("");
        ChatInputBarHelper.setupAttachButton(ml_attach_button_01, add_attachement_icon, v -> send_attatchment(v));

        if (PREF__use_incognito_keyboard)
        {
            ml_new_message.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        else
        {
            ml_new_message.setImeOptions(EditorInfo.IME_ACTION_SEND);
        }

        // clear input text field
        ml_new_message.setText("");

        // add text change listener to input text field
        ml_new_message.addTextChangedListener(new TextWatcher()
        {
            public void afterTextChanged(Editable s)
            {
                // TODO bad hack!
                // HelperGeneric.logI(TAG, "TextWatcher:afterTextChanged");
                if (global_typing == 0)
                {
                    global_typing = 1;  // typing = 1

                    Runnable myRunnable = new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                tox_self_set_typing(friendnum, global_typing);
                                // HelperGeneric.logI(TAG, "typing:fn#" + friendnum + ":activated");
                            }
                            catch (Exception e)
                            {
                                HelperGeneric.logI(TAG, "typing:fn#" + friendnum + ":EE1" + e.getMessage());
                            }
                        }
                    };

                    if (main_handler_s != null)
                    {
                        main_handler_s.post(myRunnable);
                    }
                }

                try
                {
                    typing_flag_thread.interrupt();
                }
                catch (Exception e)
                {
                    // e.printStackTrace();
                }

                typing_flag_thread = new Thread()
                {
                    @Override
                    public void run()
                    {
                        boolean skip_flag_update = false;
                        try
                        {
                            Thread.sleep(TYPING_FLAG_DEACTIVATE_DELAY_IN_MILLIS); // sleep for n seconds
                        }
                        catch (Exception e)
                        {
                            // e.printStackTrace();
                            // ok, dont update typing flag
                            skip_flag_update = true;
                        }

                        if (global_typing == 1)
                        {
                            if (skip_flag_update == false)
                            {
                                global_typing = 0;  // typing = 0
                                Runnable myRunnable = new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        try
                                        {
                                            tox_self_set_typing(friendnum, global_typing);
                                            // HelperGeneric.logI(TAG, "typing:fn#" + friendnum + ":DEactivated");
                                        }
                                        catch (Exception e)
                                        {
                                            HelperGeneric.logI(TAG, "typing:fn#" + friendnum + ":EE2" + e.getMessage());
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
                };
                typing_flag_thread.start();
                // TODO bad hack! sends way too many "typing" messages --------
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after)
            {
                // HelperGeneric.logI(TAG,"TextWatcher:beforeTextChanged");
            }

            public void onTextChanged(CharSequence s, int start, int before, int count)
            {
                // HelperGeneric.logI(TAG,"TextWatcher:onTextChanged");
            }
        });

        // fill out input text field with shared text value
        boolean restored_shared_text = false;
        try
        {
            String fillouttext = intent.getStringExtra("fillouttext");
            if ((fillouttext != null) && (fillouttext.length() > 0))
            {
                ml_new_message.setText("");
                ml_new_message.append(fillouttext);
                restored_shared_text = true;
            }
        }
        catch (Exception ignored)
        {
        }

        if (!restored_shared_text)
        {
            try
            {
                if ((friend_pubkey != null) && (friend_pubkey.length() > 2))
                {
                    final String draft = ChatDraftHelper.load_friend_draft(friend_pubkey);
                    if ((draft != null) && (!draft.isEmpty()))
                    {
                        ml_new_message.setText(draft);
                        ml_new_message.setSelection(draft.length());
                    }
                }
            }
            catch (Exception ignored)
            {
            }
        }

        ChatInputBarHelper.bindMicSendTextWatcher(ml_new_message, ml_button_recaudio, mic_icon, send_message_icon);

        if (PREF__window_security)
        {
            // prevent screenshots and also dont show the window content in recent activity screen
            initializeScreenshotSecurity(this);
        }

        refresh_chat_header();

        ml_button_recaudio.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {

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

                // Direct/Favorites chat recording needs RECORD_AUDIO too — request it instead of failing silently.
                if (!HelperCall.hasMicrophonePermission(v.getContext()))
                {
                    HelperCall.requestCallPermissions((Activity) v.getContext(), true);
                    HelperCall.showMissingPermissionToast(v.getContext(), true);
                    return false;
                }

                // display_toast("LONG", false, 0);
                ChatVoiceSessionHelper.onVoiceRecordingStarting();
                final Thread ml_rec_audio_thread = new Thread()
                {
                    @Override
                    public void run()
                    {
                        String audio_rec_filename_final = null;
                        ml_is_recording = true;
                        ml_is_rec_ok = false;
                        final MessageListActivity activity = MainActivity.message_list_activity;

                        set_recording_pop_text_s("0:00");
                        set_recording_pop_visibilty_s(true);

                        try
                        {
                            HelperGeneric.logI(TAG, "onCreate:record_audio:start");
                            AudioRecorder mAudioRecorder = AudioRecorder.getInstance();
                            if (activity == null)
                            {
                                set_recording_pop_visibilty_s(false);
                                ml_is_recording = false;
                                ml_is_rec_ok = false;
                                return;
                            }
                            activity.resolve_friend_for_chat();
                            final String chatPubkey = activity.get_friend_pubkey();
                            if ((chatPubkey == null) || chatPubkey.isEmpty())
                            {
                                set_recording_pop_visibilty_s(false);
                                ml_is_recording = false;
                                ml_is_rec_ok = false;
                                return;
                            }
                            final String fpubkey = chatPubkey;
                            File rec_dir = new File(SD_CARD_TMP_DIR);
                            if (!rec_dir.exists() && !rec_dir.mkdirs())
                            {
                                rec_dir = v.getContext().getCacheDir();
                            }
                            final String rec_base_dir = rec_dir.getAbsolutePath();
                            String audio_rec_filename = rec_base_dir + "/" + fpubkey + "_" + System.nanoTime();
                            String audio_rec_filename_uniq_part  = get_uniq_tmp_filename(audio_rec_filename ,1000);
                            audio_rec_filename_final = audio_rec_filename + "_" + audio_rec_filename_uniq_part + ".file.m4a";
                            File f = new File(audio_rec_filename_final);
                            boolean file_exists = true;
                            try
                            {
                                file_exists = f.exists();
                            }
                            catch(Exception e)
                            {
                                set_recording_pop_visibilty_s(false);
                                ml_is_recording = false;
                                ml_is_rec_ok = false;
                                return;
                            }

                            long count = 0;
                            while(file_exists)
                            {
                                audio_rec_filename = rec_base_dir + "/" + fpubkey + "_" + System.nanoTime();
                                audio_rec_filename_uniq_part  = get_uniq_tmp_filename(audio_rec_filename ,1000);
                                audio_rec_filename_final = audio_rec_filename + "_" + audio_rec_filename_uniq_part + ".file.m4a";
                                f = new File(audio_rec_filename_final);
                                try
                                {
                                    file_exists = f.exists();
                                }
                                catch(Exception e)
                                {
                                    set_recording_pop_visibilty_s(false);
                                    ml_is_recording = false;
                                    ml_is_rec_ok = false;
                                    return;
                                }

                                try
                                {
                                    Thread.sleep(50);
                                }
                                catch(Exception e)
                                {
                                }
                                count++;

                                if (count > 50)
                                {
                                    // HINT: just in case of an endless loop, we return here
                                    set_recording_pop_visibilty_s(false);
                                    ml_is_recording = false;
                                    ml_is_rec_ok = false;
                                    return;
                                }
                            }

                            File mAudioFile = new File(audio_rec_filename_final);
                            // HelperGeneric.logI(TAG, "onCreate:record_audio:file=" + audio_rec_filename_final);
                            try
                            {
                                mAudioRecorder.prepareRecord(MediaRecorder.AudioSource.MIC, MediaRecorder.OutputFormat.MPEG_4,
                                                             MediaRecorder.AudioEncoder.AAC, mAudioFile);
                            }
                            catch (Exception prepare_error)
                            {
                                HelperGeneric.logI(TAG, "onCreate:record_audio:prepare:EE:" + prepare_error.getMessage());
                                set_recording_pop_visibilty_s(false);
                                ml_is_recording = false;
                                ml_is_rec_ok = false;
                                return;
                            }
                            boolean rec_start_result = mAudioRecorder.startRecord();
                            if (!rec_start_result)
                            {
                                // HINT: some problem on starting the recording
                                set_recording_pop_visibilty_s(false);
                                ml_is_recording = false;
                                ml_is_rec_ok = false;
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

                                set_recording_pop_text_s(seconds_time_format_or_empty(mAudioRecorder.progress()));
                            }
                            set_recording_pop_visibilty_s(false);
                            int rec_result = mAudioRecorder.stopRecord();
                            HelperGeneric.logI(TAG, "onCreate:record_audio:finished:res=" + rec_result);
                            if (rec_result == -1)
                            {
                                ml_is_rec_ok = false;
                            }
                        }
                        catch(Exception e)
                        {
                            HelperGeneric.logI(TAG, "onCreate:record_audio:EE:" + e.getMessage());
                            e.printStackTrace();
                            ml_is_recording = false;
                            ml_is_rec_ok = false;
                        }
                        set_recording_pop_visibilty_s(false);

                        if (ml_is_rec_ok)
                        {
                            HelperGeneric.logI(TAG, "onCreate:record_audio:------ OK ------");
                            HelperGeneric.logI(TAG, "onCreate:record_audio:------ OK ------");
                            HelperGeneric.logI(TAG, "onCreate:record_audio:------ OK ------");

                            if (audio_rec_filename_final != null)
                            {
                                HelperGeneric.logI(TAG, "onCreate:record_audio:add to FT queue ...");
                                File f2 = new File(audio_rec_filename_final);
                                add_outgoing_file(v.getContext(),
                                        activity != null ? activity.get_current_friendnum() : -1L,
                                        f2.getParent(), f2.getName(), null, f2.length(),
                                        false, true, true);
                            }
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
                        ml_is_rec_ok = false;
                        ml_is_recording = false;
                    }
                };
                ml_rec_audio_thread.start();

                return true;
            }
        });

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
                    ml_is_rec_ok = true;
                    ml_is_recording = false;
                    set_recording_pop_visibilty_s(false);
                    return;
                }

                if (ChatInputBarHelper.isSendMode((ImageButton) v))
                {
                    send_message_onclick(null);
                    return;
                }

                // Tap starts recording too (tap = start, tap again = stop & send); long-press still works.
                v.performLongClick();
            }
        });

        HelperGeneric.logI(TAG, "onCreate:099");
        oncreate_finished = true;
    }

    @Override
    protected void onPause()
    {
        try
        {
            HelperGeneric.logI(TAG, "is_at_bottom=" + MainActivity.message_list_fragment.is_at_bottom);
        }
        catch (Exception e)
        {
        }

        HelperGeneric.logI(TAG, "onPause");
        super.onPause();

        ml_button_recaudio.setImageResource(R.drawable.baseline_keyboard_voice_24);
        ChatInputBarHelper.resetMicSendIcon(ml_button_recaudio,
                getResources().getDrawable(R.drawable.baseline_keyboard_voice_24));
        ml_button_recaudio.setBackgroundColor(Color.TRANSPARENT);
        set_recording_pop_visibilty_s(false);
        ml_is_recording = false;
        ml_is_rec_ok = false;

        stop_self_typing_indicator_s();
        stop_typing_animation();
        friend_is_typing = false;

        if (emojiPopup != null)
        {
            emojiPopup.dismiss();
        }

        try
        {
            final String pk = get_friend_pubkey();
            if (pk != null)
            {
                ChatDraftHelper.save_friend_draft(pk, ml_new_message.getText().toString());
            }
        }
        catch (Exception ignored)
        {
        }

        // ** // MainActivity.message_list_fragment = null;
        if (!outgoingMediaPickerActive)
        {
            message_list_activity = null;
            friendnum = -1;
        }
        else
        {
            sync_outgoing_attachment_friend_pubkey();
        }
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        ml_button_recaudio.setImageResource(R.drawable.baseline_keyboard_voice_24);
        ChatInputBarHelper.resetMicSendIcon(ml_button_recaudio,
                getResources().getDrawable(R.drawable.baseline_keyboard_voice_24));
        ml_button_recaudio.setBackgroundColor(Color.TRANSPARENT);
        ml_is_recording = false;
        ml_is_rec_ok = false;
    }

    @Override
    protected void onResume()
    {
        HelperGeneric.logI(TAG, "onResume");
        super.onResume();

        // HelperGeneric.logI(TAG, "onResume:001:friendnum=" + friendnum);

        if (friendnum == -1)
        {
            friendnum = friendnum_prev;
            // HelperGeneric.logI(TAG, "onResume:001:friendnum(-->friendnum_prev)=" + friendnum);
        }

        if (FavoritesChatHelper.isFavoritesChat(friend_pubkey))
        {
            change_msg_notification(NOTIFICATION_EDIT_ACTION_REMOVE.value, FavoritesChatHelper.CHAT_ID, null, null);
        }
        else if (friendnum >= 0)
        {
            change_msg_notification(NOTIFICATION_EDIT_ACTION_REMOVE.value,
                    tox_friend_get_public_key__wrapper(friendnum), null, null);
        }

        // ----- convert old messages which did not contain a sent timestamp -----
        try
        {
            boolean need_migrate_old_msg_date = true;

            if (get_g_opts("MIGRATE_OLD_MSG_DATE_done") != null)
            {
                if (get_g_opts("MIGRATE_OLD_MSG_DATE_done").equals("true"))
                {
                    need_migrate_old_msg_date = false;
                }
            }

            if (need_migrate_old_msg_date == true)
            {
                orma.run_multi_sql(
                        "update Message set sent_timestamp_ms=rcvd_timestamp_ms," + "sent_timestamp=rcvd_timestamp" +
                        " where " + " sent_timestamp_ms='0'" + " and sent_timestamp='0'" + " and direction='0'" +
                        " and msg_version='0'");
                HelperGeneric.logI(TAG, "onCreate:migrate_old_msg_date");

                // now remember that we did that, and don't do it again
                set_g_opts("MIGRATE_OLD_MSG_DATE_done", "true");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "onCreate:migrate_old_msg_date:EE:" + e.getMessage());
        }
        // ----- convert old messages which did not contain a sent timestamp -----


        // ----- convert filetransfer messages which did not contain a sent timestamp -----
        try
        {
            boolean need_migrate_old_ft_date = true;

            if (get_g_opts("MIGRATE_OLD_FT_DATE_done") != null)
            {
                if (get_g_opts("MIGRATE_OLD_FT_DATE_done").equals("true"))
                {
                    need_migrate_old_ft_date = false;
                }
            }

            if (need_migrate_old_ft_date == true)
            {
                orma.run_multi_sql(
                        "update Message set sent_timestamp_ms=rcvd_timestamp_ms," + "sent_timestamp=rcvd_timestamp" +
                        " where " + " sent_timestamp_ms='0'" + " and sent_timestamp='0'" + " and direction='0'" +
                        " and TRIFA_MESSAGE_TYPE ='1'");
                HelperGeneric.logI(TAG, "onCreate:migrate_old_ft_date");

                // now remember that we did that, and don't do it again
                set_g_opts("MIGRATE_OLD_FT_DATE_done", "true");
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "onCreate:migrate_old_ft_date:EE:" + e.getMessage());
        }
        // ----- convert filetransfer messages which did not contain a sent timestamp -----

        try
        {
            HelperGeneric.logI(TAG, "is_at_bottom=" + MainActivity.message_list_fragment.is_at_bottom);
        }
        catch (Exception e)
        {
        }

        resolve_friend_for_chat();
        sync_outgoing_attachment_friend_pubkey();
        message_list_activity = this;
        apply_chat_header();
        refresh_chat_header();
        wakeup_tox_thread();
        schedule_delayed_message_refresh();
    }

    /** Restore chat context before gallery/preview callbacks (onActivityResult runs before onResume). */
    void prepareFriendMediaSendContext()
    {
        if (!FavoritesChatHelper.isFavoritesChat(friend_pubkey) && friendnum < 0 && friendnum_prev >= 0)
        {
            friendnum = friendnum_prev;
        }
        message_list_activity = this;
        sync_outgoing_attachment_friend_pubkey();
        HelperGeneric.logI(TAG, "prepareFriendMediaSendContext:pubkey=" + outgoing_attachment_friend_pubkey
                + " favorites=" + FavoritesChatHelper.isFavoritesChat(outgoing_attachment_friend_pubkey));
    }

    private void sync_outgoing_attachment_friend_pubkey()
    {
        if ((friend_pubkey != null) && (friend_pubkey.length() > 2))
        {
            outgoing_attachment_friend_pubkey = friend_pubkey;
        }
    }

    void resolve_friend_for_chat()
    {
        if (FavoritesChatHelper.isFavoritesChat(friend_pubkey))
        {
            friendnum = -1;
            friendnum_prev = -1;
            return;
        }

        if ((friend_pubkey != null) && (friend_pubkey.length() > 2))
        {
            final long fn = HelperFriend.ensure_friend_in_tox_core(friend_pubkey);
            if (fn >= 0)
            {
                friendnum = fn;
                friendnum_prev = fn;
            }
        }
        else if (friendnum >= 0)
        {
            try
            {
                friend_pubkey = tox_friend_get_public_key__wrapper(friendnum);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    String get_friend_pubkey()
    {
        if ((friend_pubkey != null) && (friend_pubkey.length() > 2))
        {
            return friend_pubkey;
        }
        if (friendnum >= 0)
        {
            try
            {
                return tox_friend_get_public_key__wrapper(friendnum);
            }
            catch (Exception ignored)
            {
            }
        }
        return null;
    }

    static String resolve_outgoing_file_chat_pubkey()
    {
        if (message_list_activity != null)
        {
            final String pk = message_list_activity.get_friend_pubkey();
            if (pk != null && !pk.isEmpty())
            {
                return pk;
            }
        }
        if ((outgoing_attachment_friend_pubkey != null) && (outgoing_attachment_friend_pubkey.length() > 2))
        {
            return outgoing_attachment_friend_pubkey;
        }
        return null;
    }

    private void schedule_delayed_message_refresh()
    {
        if (mla_handler_s == null)
        {
            return;
        }

        final Runnable refresh = () -> {
            try
            {
                if (MainActivity.message_list_fragment != null)
                {
                    resolve_friend_for_chat();
                    if (friendnum >= 0 || FavoritesChatHelper.isFavoritesChat(get_friend_pubkey()))
                    {
                        MainActivity.message_list_fragment.update_all_messages(false, false, PREF__messageview_paging);
                    }
                }
            }
            catch (Exception ignored)
            {
            }
        };

        mla_handler_s.postDelayed(refresh, 700);
        mla_handler_s.postDelayed(refresh, 2000);
    }

    static void cancelVoiceRecording()
    {
        ml_is_rec_ok = false;
        ml_is_recording = false;
        set_recording_pop_visibilty_s(false);
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

        if (mla_handler_s != null)
        {
            mla_handler_s.post(myRunnable);
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

        if (mla_handler_s != null)
        {
            mla_handler_s.post(myRunnable);
        }
    }

    static void stop_friend_typing_indicator_s()
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (message_list_activity != null)
                    {
                        message_list_activity.set_friend_typing_indicator(0);
                    }
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "friend_typing_cb:EE.b:" + e.getMessage());
                }
            }
        };

        if (mla_handler_s != null)
        {
            mla_handler_s.post(myRunnable);
        }
    }

    static void stop_self_typing_indicator_s()
    {
        try
        {
            // HelperGeneric.logI(TAG, "stop_self_typing_indicator_s");
            android.os.Message m = new android.os.Message();
            m.what = 1;
            mla_handler_s.handleMessage(m);
        }
        catch (Exception e)
        {
            e.getMessage();
        }
    }

    void stop_self_typing_indicator()
    {
        if (global_typing == 1)
        {
            global_typing = 0;  // typing = 0
            try
            {
                // HelperGeneric.logI(TAG, "typing:fn#" + get_current_friendnum() + ":stop_self_typing_indicator");
                tox_self_set_typing(get_current_friendnum(), global_typing);
            }
            catch (Exception e)
            {
                HelperGeneric.logI(TAG, "typing:fn#" + get_current_friendnum() + ":EE2.b" + e.getMessage());
            }
        }
    }

    private void setUpEmojiPopup()
    {

        //        .setOnEmojiClickedListener(new OnEmojiClickedListener()
        //        {
        //        @Override
        //        public void onEmojiClicked(@NonNull final Emoji emoji)
        //        {
        //            Log.d(TAG, "Clicked on emoji");
        //        }})

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
                final Drawable d1 = new IconicsDrawable(getBaseContext()).
                        icon(FontAwesome.Icon.faw_keyboard).
                        color(getResources().
                                getColor(R.color.icon_colors)).
                        sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);

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
                final Drawable d1 = new IconicsDrawable(getBaseContext()).
                        icon(GoogleMaterial.Icon.gmd_sentiment_satisfied).
                        color(getResources().
                                getColor(R.color.icon_colors)).
                        sizeDp(ChatInputBarHelper.CHAT_INPUT_ICON_DP);

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
        }).
                setBackgroundColor(getResources().getColor(R.color.md_grey_800)).
                build(ml_new_message);
    }

    long get_current_friendnum()
    {
        return friendnum;
    }

    public void set_friend_status_icon()
    {
        update_friend_presence_status();
    }

    public void update_friend_presence_status()
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                apply_friend_presence_status_ui();
            }
        };
        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    public void set_friend_typing_indicator(final int typing)
    {
        friend_is_typing = (typing == 1);
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                if (friend_is_typing)
                {
                    start_typing_animation();
                }
                else
                {
                    stop_typing_animation();
                    apply_friend_presence_status_ui();
                }
            }
        };
        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    private void apply_friend_presence_status_ui()
    {
        if (ml_presence_status == null)
        {
            return;
        }
        if (FavoritesChatHelper.isFavoritesChat(get_friend_pubkey()))
        {
            ml_presence_status.setText(getString(R.string.favorites_chat_subtitle));
            ml_presence_status.setVisibility(View.VISIBLE);
            ml_presence_status.setTextColor(ContextCompat.getColor(this, R.color.tg_chat_header_subtitle));
            if (ml_phone_icon != null)
            {
                ml_phone_icon.setVisibility(View.GONE);
            }
            if (ml_video_icon != null)
            {
                ml_video_icon.setVisibility(View.GONE);
            }
            return;
        }
        try
        {
            if (friend_is_typing)
            {
                return;
            }
            final FriendList fl = TrifaToxService.orma.selectFromFriendList().
                    tox_public_key_stringEq(tox_friend_get_public_key__wrapper(friendnum)).
                    toList().get(0);
            final String presence = HelperFriend.format_chat_presence_line(MessageListActivity.this, fl);
            ml_presence_status.setText(presence);
            ml_presence_status.setVisibility(presence.isEmpty() ? View.GONE : View.VISIBLE);
            if (HelperFriend.is_friend_presence_online(fl))
            {
                ml_presence_status.setTextColor(ContextCompat.getColor(this, R.color.tg_chat_header_presence_online));
            }
            else
            {
                ml_presence_status.setTextColor(ContextCompat.getColor(this, R.color.tg_chat_header_subtitle));
            }
        }
        catch (Exception e)
        {
            ml_presence_status.setVisibility(View.GONE);
        }
    }

    private void start_typing_animation()
    {
        stop_typing_animation();
        if (ml_presence_status == null || main_handler_s == null)
        {
            return;
        }
        typing_dot_frame = 0;
        ml_presence_status.setVisibility(View.VISIBLE);
        ml_presence_status.setTextColor(ContextCompat.getColor(this, R.color.tg_chat_header_presence_online));
        typing_anim_runnable = new Runnable()
        {
            @Override
            public void run()
            {
                if (!friend_is_typing || ml_presence_status == null)
                {
                    return;
                }
                final String base = getString(R.string.chat_presence_typing);
                final String dots = typing_dot_frame == 0 ? "…" : typing_dot_frame == 1 ? "." : "..";
                ml_presence_status.setText(base + dots);
                typing_dot_frame = (typing_dot_frame + 1) % 3;
                if (main_handler_s != null)
                {
                    main_handler_s.postDelayed(this, TYPING_ANIM_INTERVAL_MS);
                }
            }
        };
        main_handler_s.post(typing_anim_runnable);
    }

    private void stop_typing_animation()
    {
        if (typing_anim_runnable != null && main_handler_s != null)
        {
            main_handler_s.removeCallbacks(typing_anim_runnable);
        }
        typing_anim_runnable = null;
    }

    @Override
    protected void onDestroy()
    {
        stop_typing_animation();
        friend_is_typing = false;
        if (this == message_list_activity)
        {
            message_list_activity = null;
        }
        if ((outgoing_attachment_friend_pubkey != null)
                && outgoing_attachment_friend_pubkey.equals(friend_pubkey))
        {
            outgoing_attachment_friend_pubkey = null;
        }
        super.onDestroy();
    }

    void apply_chat_header()
    {
        ChatBubbleUiHelper.apply_chat_header_theme(
                (Toolbar) findViewById(R.id.toolbar),
                findViewById(R.id.ml_header_bar),
                findViewById(R.id.ml_header_profile_tap),
                ml_maintext,
                ml_phone_icon,
                ml_video_icon,
                this);
    }

    static void notify_friend_profile_updated(long friendnum)
    {
        try
        {
            if (message_list_activity == null)
            {
                return;
            }

            if (message_list_activity.friendnum != friendnum)
            {
                return;
            }

            message_list_activity.refresh_chat_header();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    void refresh_chat_header()
    {
        resolve_friend_for_chat();
        if (FavoritesChatHelper.isFavoritesChat(get_friend_pubkey()))
        {
            ml_maintext.setText(FavoritesChatHelper.displayName(this));
            apply_chat_header();
            apply_friend_presence_status_ui();
            if (ml_avatar != null)
            {
                ml_avatar.setVisibility(View.VISIBLE);
                FavoritesChatHelper.bindListAvatar(ml_avatar);
            }
            return;
        }

        final long fn = friendnum;
        final String header_pubkey = get_friend_pubkey();
        Thread t = new Thread()
        {
            @Override
            public void run()
            {
                if (fn >= 0)
                {
                    HelperFriend.sync_friend_name_from_tox(fn, main_get_friend(fn));
                }
                final String f_name = (header_pubkey != null) ?
                        HelperFriend.get_friend_name_from_pubkey(header_pubkey) :
                        HelperFriend.get_friend_name_from_num(fn);
                final String friend_pubkey = (header_pubkey != null) ? header_pubkey :
                        tox_friend_get_public_key__wrapper(fn);
                final String friend_pubkey_final = friend_pubkey;

                Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        apply_chat_header();
                        ml_maintext.setText(f_name);
                        update_friend_presence_status();

                        if (ml_avatar == null)
                        {
                            return;
                        }

                        ml_avatar.setVisibility(View.VISIBLE);
                        ChatBubbleUiHelper.fill_friend_list_avatar(MessageListActivity.this,
                                friend_pubkey_final, f_name, ml_avatar);
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

    public void set_friend_connection_status_icon()
    {
        update_friend_presence_status();
    }

    public void send_attatchment(View view)
    {
        HelperGeneric.logI(TAG, "send_attatchment:---start");
        stop_self_typing_indicator_s();

        // KHANDAQ (#17): offer the camera (photo/video) alongside the gallery so the user can shoot
        // and send media right away, instead of only picking an existing file.
        final CharSequence[] options = {
                getString(R.string.attach_option_camera_photo),
                getString(R.string.attach_option_camera_video),
                getString(R.string.attach_option_gallery)
        };

        new android.app.AlertDialog.Builder(this).setTitle(R.string.attach_option_title).setItems(options, (dialog, which) ->
        {
            switch (which)
            {
                case 0:
                    open_camera_capture(false);
                    break;
                case 1:
                    open_camera_capture(true);
                    break;
                default:
                    open_gallery_picker();
                    break;
            }
        }).show();
    }

    private void open_gallery_picker()
    {
        // ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file browser.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
        {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, "*/*");
        }

        MediaSendPreviewHelper.configureGalleryPickerIntent(intent);

        mediaPreviewResultHandled = false;
        outgoingMediaPickerActive = true;
        sync_outgoing_attachment_friend_pubkey();
        startActivityForResult(intent, MEDIAPICK_ID_001);
    }

    private void open_camera_capture(final boolean video)
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

            final Intent intent = new Intent(video ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, outUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            if (intent.resolveActivity(getPackageManager()) == null)
            {
                Toast.makeText(this, R.string.attach_no_camera, Toast.LENGTH_SHORT).show();
                return;
            }

            sync_outgoing_attachment_friend_pubkey();
            outgoingMediaPickerActive = true;
            startActivityForResult(intent, video ? CAMERA_VIDEO_ID : CAMERA_PHOTO_ID);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "open_camera_capture:EE:" + e.getMessage());
            Toast.makeText(this, R.string.attach_no_camera, Toast.LENGTH_SHORT).show();
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

    public static void add_quote_message_text(final String quote_text)
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (message_list_activity != null)
                    {
                        ChatReplyPreviewController.startReplyToPlainText(message_list_activity, quote_text);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "add_quote_message_text:EE01:" + e.getMessage());
                }
            }
        };

        if (mla_handler_s != null)
        {
            mla_handler_s.post(myRunnable);
        }
    }

    public void send_text_message(final String friend_pubkey, final String message)
    {
        String msg = trim_to_utf8_length_bytes(
                ChatReplyPreviewController.composeOutgoingText(HelperGeneric.normalize_chat_input_text(message)),
                TOX_MSGV3_MAX_MESSAGE_LENGTH);
        if ((msg == null) || msg.isEmpty())
        {
            return;
        }

        if (FavoritesChatHelper.isFavoritesChat(friend_pubkey))
        {
            final long rowId = FavoritesChatHelper.sendTextMessage(msg, true);
            if (rowId <= 0)
            {
                display_toast(getString(R.string.dm_send_failed_friend), true, 400);
                return;
            }

            HelperGeneric.clear_chat_input_field(ml_new_message);
            ChatDraftHelper.clear_friend_draft(friend_pubkey);
            if (emojiPopup != null)
            {
                emojiPopup.dismiss();
            }
            stop_self_typing_indicator_s();
            return;
        }

        if (!is_tox_started)
        {
            display_toast(getString(R.string.dm_send_failed_not_ready), true, 400);
            return;
        }

        final String dm_precheck = HelperFriend.dm_send_precheck_failure_reason(friend_pubkey);
        if (dm_precheck != null)
        {
            display_toast(dm_precheck, true, 400);
            return;
        }

        wakeup_tox_thread();

        Message m = new Message();
        m.tox_friendpubkey = friend_pubkey;
        m.direction = 1; // msg sent
        m.TOX_MESSAGE_TYPE = 0;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.rcvd_timestamp = 0L;
        m.is_new = false; // own messages are always "not new"
        m.sent_timestamp = System.currentTimeMillis();
        m.read = false;
        m.text = msg;
        m.msg_version = 0;
        m.resend_count = 0; // we have tried to resend this message "0" times
        m.sent_push = 0;
        m.msg_idv3_hash = "";
        m.msg_id_hash = "";
        m.raw_msgv2_bytes = "";

        MainActivity.send_message_result result = tox_friend_send_message_wrapper(friend_pubkey, 0, msg,
                                                                                  (m.sent_timestamp / 1000));

        if (result == null)
        {
            display_toast(getString(R.string.dm_send_failed_friend), true, 400);
            return;
        }

        long res = result.msg_num;

        if (HelperFriend.is_dm_message_send_success(res))
        {
            m.resend_count = 1; // we sent the message successfully
            m.message_id = res;
        }
        else
        {
            m.resend_count = 0; // sending was NOT successfull
            m.message_id = -1;
            display_toast(HelperFriend.dm_send_failure_reason(res, friend_pubkey), true, 400);
        }

        if (result.msg_v2)
        {
            m.msg_version = 1;
        }
        else
        {
            m.msg_version = 0;
        }

        if ((result.msg_hash_hex != null) && (!result.msg_hash_hex.equalsIgnoreCase("")))
        {
            // msgV2 message -----------
            m.msg_id_hash = result.msg_hash_hex;
            // msgV2 message -----------
        }

        if ((result.msg_hash_v3_hex != null) && (!result.msg_hash_v3_hex.equalsIgnoreCase("")))
        {
            // msgV3 message -----------
            m.msg_idv3_hash = result.msg_hash_v3_hex;
            // msgV3 message -----------
        }

        if ((result.raw_message_buf_hex != null) && (!result.raw_message_buf_hex.equalsIgnoreCase("")))
        {
            // save raw message bytes of this v2 msg into the database
            // we need it if we want to resend it later
            m.raw_msgv2_bytes = result.raw_message_buf_hex;
        }

        long row_id = insert_into_message_db(m, true);
        m.id = row_id;

        if (!HelperFriend.is_dm_message_send_success(res))
        {
            friend_call_push_url(friend_pubkey, m.sent_timestamp);
        }

        HelperGeneric.clear_chat_input_field(ml_new_message);
        ChatDraftHelper.clear_friend_draft(friend_pubkey);
        if (emojiPopup != null)
        {
            emojiPopup.dismiss();
        }
        stop_self_typing_indicator_s();
    }

    /* HINT: send a message to a friend */
    synchronized public void send_message_onclick(View view)
    {
        // HelperGeneric.logI(TAG, "send_message_onclick:---start");
        try
        {
            resolve_friend_for_chat();

            final String normalized = HelperGeneric.normalize_chat_input_text(ml_new_message.getText().toString());
            if (!normalized.isEmpty())
            {
                final String pk = get_friend_pubkey();
                if ((pk == null) || pk.isEmpty())
                {
                    display_toast(getString(R.string.dm_send_failed_friend), true, 400);
                    return;
                }
                if (FavoritesChatHelper.isFavoritesChat(pk))
                {
                    send_text_message(pk, normalized);
                    return;
                }
                if (friendnum < 0)
                {
                    final long fn = HelperFriend.ensure_friend_in_tox_core(pk);
                    if (fn >= 0)
                    {
                        friendnum = fn;
                        friendnum_prev = fn;
                    }
                }
                send_text_message(pk, normalized);
            }
            else if (friendnum < 0)
            {
                display_toast(getString(R.string.dm_send_failed_friend), true, 400);
            }
            else if (view != null)
            {
                send_attatchment(view);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        // HelperGeneric.logI(TAG,"send_message_onclick:---end");
    }

    static long add_attachment_and_get_message_id(final Context c, final Intent data,
                                                  final long friendnum_local, final boolean activity_friend_num)
    {
        return add_attachment_and_get_message_id(c, data, friendnum_local, activity_friend_num, true);
    }

    static long add_attachment_and_get_message_id(final Context c, final Intent data,
                                                  final long friendnum_local, final boolean activity_friend_num,
                                                  final boolean startTransferImmediately)
    {
        try
        {
            if ((data == null) || (data.getData() == null))
            {
                return -1L;
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
                return -1L;
            }

            long friendnum = friendnum_local;
            if (activity_friend_num)
            {
                if (MainActivity.message_list_activity == null)
                {
                    return -1L;
                }
                if (!FavoritesChatHelper.isFavoritesChat(
                        MainActivity.message_list_activity.get_friend_pubkey())
                        && MainActivity.message_list_activity.get_current_friendnum() == -1)
                {
                    return -1L;
                }
                friendnum = MainActivity.message_list_activity.get_current_friendnum();
            }

            return add_outgoing_file(c, friendnum, data.getData().toString(), fileName_, data.getData(), 0, false,
                                     activity_friend_num, false, startTransferImmediately);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "add_attachment_and_get_message_id:EE:" + e.getMessage());
            return -1L;
        }
    }

    static long add_attachment_from_uri(final Context c, final Uri uri, final long friendnum_local,
                                        final boolean activity_friend_num, final boolean startTransferImmediately)
    {
        if (uri == null)
        {
            return -1L;
        }
        final Intent single = new Intent();
        single.setData(uri);
        single.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return add_attachment_and_get_message_id(c, single, friendnum_local, activity_friend_num,
                startTransferImmediately);
    }

    static void add_attachment(Context c, Intent data, Intent orig_intent, long friendnum_local, boolean activity_friend_num)
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
                if (activity_friend_num)
                {
                    if (c instanceof MessageListActivity)
                    {
                        ((MessageListActivity) c).prepareFriendMediaSendContext();
                    }

                    final Runnable dispatchOutgoing = () ->
                    {
                        try
                        {
                            final String chatPubkey = resolve_outgoing_file_chat_pubkey();
                            final boolean favoritesChat = FavoritesChatHelper.isFavoritesChat(chatPubkey);
                            long fn = -1L;
                            if (!favoritesChat)
                            {
                                if (MainActivity.message_list_activity != null)
                                {
                                    fn = MainActivity.message_list_activity.get_current_friendnum();
                                }
                                if (fn < 0)
                                {
                                    HelperGeneric.logI(TAG, "add_outgoing_file:no friendnum pubkey=" + chatPubkey);
                                    return;
                                }
                            }

                            HelperGeneric.logI(TAG, "add_outgoing_file:dispatch favorites=" + favoritesChat
                                    + " fn=" + fn + " file=" + fileName_);
                            add_outgoing_file(c, fn, data.getData().toString(), fileName_, data.getData(), 0, false,
                                    activity_friend_num, false);
                        }
                        catch (Exception e)
                        {
                            HelperGeneric.logI(TAG, "add_outgoing_file:dispatch:EE:" + e.getMessage());
                        }
                    };

                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                    {
                        dispatchOutgoing.run();
                    }
                    else if (main_handler_s != null)
                    {
                        main_handler_s.post(dispatchOutgoing);
                    }
                    else
                    {
                        new Thread(dispatchOutgoing).start();
                    }
                }
                else
                {
                    add_outgoing_file(c, friendnum_local, data.getData().toString(), fileName_, data.getData(), 0, false,
                                      activity_friend_num, false);
                }
            }
            else
            {
                HelperGeneric.logI(TAG, "add_attachment:no filename uri="
                        + (data != null && data.getData() != null ? data.getData() : "null"));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "select_file:22:EE1:" + e.getMessage());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        HelperGeneric.logI(TAG, "onActivityResult:requestCode=" + requestCode + " resultCode=" + resultCode);

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == MEDIAPICK_ID_001 || requestCode == MediaSendPreviewHelper.REQUEST_PREVIEW
                || requestCode == CAMERA_PHOTO_ID || requestCode == CAMERA_VIDEO_ID)
        {
            outgoingMediaPickerActive = false;
            prepareFriendMediaSendContext();
        }

        // KHANDAQ (#17): a captured photo/video lands at the URI we passed to the camera (data is null
        // for capture intents) — send it through the same media path as a gallery pick.
        if ((requestCode == CAMERA_PHOTO_ID || requestCode == CAMERA_VIDEO_ID) && resultCode == Activity.RESULT_OK)
        {
            final Uri captured = pending_camera_capture_uri;
            pending_camera_capture_uri = null;
            if (captured != null)
            {
                MediaSendPreviewHelper.dispatchAttachments(this, java.util.Collections.singletonList(captured),
                        MediaSendPreviewHelper.TARGET_FRIEND, friendnum, null, true);
            }
            return;
        }

        if (requestCode == CallingWaitingActivity_ID)
        {
            HelperGeneric.logI(TAG, "friend_to_call:CallingWaitingActivity_ID returned");
            if (resultCode == Activity.RESULT_OK)
            {
                HelperGeneric.logI(TAG, "friend_to_call came online, call him now ...");
                try
                {
                    friendnum = tox_friend_by_public_key__wrapper(data.getStringExtra("friendnum_pk"));
                    HelperGeneric.logI(TAG, "friend_to_call:friendnum=" + friendnum + " friendnum_prev=" + friendnum_prev + " pk=" +
                               data.getStringExtra("friendnum_pk"));
                    friendnum_prev = friendnum;
                    start_call_to_friend_real(null);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }
        else if (requestCode == MEDIAPICK_ID_001 && resultCode == Activity.RESULT_OK)
        {
            if (data == null)
            {
                return;
            }

            final List<Uri> pickedUris = MediaSendPreviewHelper.collectUris(data);
            MediaSendPreviewHelper.persistUriPermissions(this, pickedUris);

            if (MediaSendPreviewHelper.launchPreviewIfNeeded(this, data,
                    MediaSendPreviewHelper.TARGET_FRIEND, friendnum, null, true))
            {
                outgoingMediaPickerActive = true;
                return;
            }

            if (pickedUris.isEmpty())
            {
                add_attachment(this, data, data, -1, true);
            }
            else
            {
                MediaSendPreviewHelper.dispatchAttachments(this, pickedUris,
                        MediaSendPreviewHelper.TARGET_FRIEND, friendnum, null, true);
            }
        }
        else if (requestCode == MediaSendPreviewHelper.REQUEST_PREVIEW && resultCode == Activity.RESULT_OK)
        {
            if (mediaPreviewResultHandled)
            {
                return;
            }
            mediaPreviewResultHandled = true;
            MediaSendPreviewHelper.handlePreviewResult(this, data,
                    MediaSendPreviewHelper.TARGET_FRIEND, friendnum, null, true);
        }
    }

    static class outgoing_file_wrapped
    {
        String filepath_wrapped = null;
        String filename_wrapped = null;
        long file_size_wrapped = -1;
    }

    static long add_outgoing_file(Context c, long friendnum, String filepath, String filename, Uri uri, long file_size_manual, boolean real_file_path, boolean update_message_view, boolean use_file_size_manual)
    {
        return add_outgoing_file(c, friendnum, filepath, filename, uri, file_size_manual, real_file_path,
                update_message_view, use_file_size_manual, true);
    }

    static long add_outgoing_file(Context c, long friendnum, String filepath, String filename, Uri uri, long file_size_manual, boolean real_file_path, boolean update_message_view, boolean use_file_size_manual, final boolean startTransferImmediately)
    {
        // HelperGeneric.logI(TAG, "add_outgoing_file:001");

        // HelperGeneric.logI(TAG,
        //      "add_outgoing_file:filepath=" + filepath + " filename=" + filename + " uri=" + uri.toString() + " uri2=" +
        //      uri);

        long file_size = -1;
        try
        {
            if (use_file_size_manual)
            {
                file_size = file_size_manual;
            }
            else if (uri != null)
            {
                DocumentFile documentFile = DocumentFile.fromSingleUri(c, uri);
                file_size = documentFile.length();
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "add_outgoing_file:favorites_size:EE:" + e.getMessage());
        }

        if (FavoritesChatHelper.isFavoritesChat(resolve_outgoing_file_chat_pubkey()))
        {
            if (file_size < 1L && uri != null)
            {
                try
                {
                    final DocumentFile documentFile = DocumentFile.fromSingleUri(c, uri);
                    if (documentFile != null)
                    {
                        file_size = documentFile.length();
                    }
                }
                catch (Exception ignored)
                {
                }
            }
            return FavoritesChatHelper.sendLocalOutgoingFile(c, filepath, filename, file_size, update_message_view);
        }

        if (friendnum < 0)
        {
            HelperGeneric.logI(TAG, "add_outgoing_file:no friendnum");
            return -1L;
        }

        file_size = -1;
        try
        {
            if (use_file_size_manual)
            {
                file_size = file_size_manual;
                // HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:file_size=" + file_size);
            }
            else
            {
                DocumentFile documentFile = DocumentFile.fromSingleUri(c, uri);
                String fileName = documentFile.getName();
                // HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:fileName=" + fileName);
                // HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:fileLength=" + documentFile.length());
                file_size = documentFile.length();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:EE003:" + e.getMessage());
            // file length unknown?
            return -1L;
        }

        if (file_size < 1)
        {
            HelperGeneric.logI(TAG, "file length 0 ?");
            // KHANDAQ: Tox cannot transfer a 0-byte file (size 0 is the special streaming/avatar
            // mode), so empty files were silently dropped. Tell the user instead of doing nothing.
            display_toast(c.getString(R.string.chat_file_empty), true, 100);
            return -1L;
        }

        FileTransferDebug.logUserSelectedFile(
                filepath != null ? filepath + "/" + filename : String.valueOf(uri), file_size);
        FileTransferDebug.logPeerConnectionStatus(friendnum);

        if (file_size > FT_OUTGOING_FILESIZE_FRIEND_MAX_TOTAL)
        {
            display_toast(c.getString(R.string.chat_file_too_large), true, 100);
            HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:file_size=File too large");
            return -1L;
        }

        if (file_size < FT_OUTGOING_FILESIZE_BYTE_USE_STORAGE_FRAMEWORK) // less than xxx Bytes filesize
        {
            HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:0001");
            outgoing_file_wrapped ofw = copy_outgoing_file_to_sdcard_dir(filepath, filename, file_size);

            if (ofw == null)
            {
                HelperGeneric.logI(TAG, "add_outgoing_file:documentFile:ERR:0002");
                return -1L;
            }

            Filetransfer f = new Filetransfer();
            f.tox_public_key_string = tox_friend_get_public_key__wrapper(friendnum);
            f.direction = TRIFA_FT_DIRECTION_OUTGOING.value;
            f.file_number = -1; // add later when we actually have the number
            f.kind = TOX_FILE_KIND_DATA.value;
            f.state = TOX_FILE_CONTROL_PAUSE.value;
            f.path_name = ofw.filepath_wrapped;
            f.file_name = ofw.filename_wrapped;
            f.filesize = ofw.file_size_wrapped;
            f.ft_accepted = false;
            f.ft_outgoing_started = false;
            f.current_position = 0;
            f.storage_frame_work = false;

            long ft_id = insert_into_filetransfer_db(f);
            f.id = ft_id;

            HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:2:" + ft_id);

            // ---------- DEBUG ----------
            Filetransfer ft_tmp = (Filetransfer) orma.selectFromFiletransfer().idEq(ft_id).get(0);
            HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:4a:" + "fid=" + ft_tmp.id + " mid=" + ft_tmp.message_id);
            // ---------- DEBUG ----------


            // add FT message to UI
            Message m = new Message();

            m.tox_friendpubkey = tox_friend_get_public_key__wrapper(friendnum);
            m.direction = 1; // msg outgoing
            m.TOX_MESSAGE_TYPE = 0;
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.filetransfer_id = ft_id;
            m.filedb_id = -1;
            m.state = TOX_FILE_CONTROL_PAUSE.value;
            m.ft_accepted = false;
            m.ft_outgoing_started = false;
            m.ft_outgoing_queued = false;
            m.filename_fullpath = new java.io.File(ofw.filepath_wrapped + "/" + ofw.filename_wrapped).getAbsolutePath();
            m.sent_timestamp = System.currentTimeMillis();
            m.text = HelperFiletransfer.buildOutgoingFileMessageText(c, ofw.filename_wrapped, ofw.file_size_wrapped);
            m.storage_frame_work = false;
            m.sent_push = 0;
            m.is_new = false; // no notification for outgoing filetransfers
            m.filetransfer_kind = TOX_FILE_KIND_DATA.value;

            long new_msg_id = insert_into_message_db(m, update_message_view);
            m.id = new_msg_id;

            // ---------- DEBUG ----------
            HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:3:" + new_msg_id);
            Message m_tmp = (Message) orma.selectFromMessage().idEq(new_msg_id).get(0);
            // HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:4:" + m.filetransfer_id + "::" + m_tmp);
            // ---------- DEBUG ----------

            f.message_id = new_msg_id;
            // ** // update_filetransfer_db_messageid_from_id(f, ft_id);
            update_filetransfer_db_full(f);

            // ---------- DEBUG ----------
            Filetransfer ft_tmp2 = (Filetransfer) orma.selectFromFiletransfer().idEq(ft_id).get(0);
            HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:4b:" + "fid=" + ft_tmp2.id + " mid=" + ft_tmp2.message_id);
            // ---------- DEBUG ----------

            finish_outgoing_file_enqueue(new_msg_id, update_message_view, startTransferImmediately);
            return new_msg_id;
        }
        else
        {
            // HelperGeneric.logI(TAG, "add_outgoing_file:friendnum(2)=" + friendnum);

            Filetransfer f = new Filetransfer();
            f.tox_public_key_string = tox_friend_get_public_key__wrapper(friendnum);
            f.direction = TRIFA_FT_DIRECTION_OUTGOING.value;
            f.file_number = -1; // add later when we actually have the number
            f.kind = TOX_FILE_KIND_DATA.value;
            f.state = TOX_FILE_CONTROL_PAUSE.value;
            f.path_name = filepath;
            f.file_name = filename;
            f.filesize = file_size;
            f.ft_accepted = false;
            f.ft_outgoing_started = false;
            f.current_position = 0;
            f.storage_frame_work = true;

            // HelperGeneric.logI(TAG, "add_outgoing_file:tox_public_key_string=" + f.tox_public_key_string);

            long ft_id = insert_into_filetransfer_db(f);
            f.id = ft_id;

            // Message m_tmp = orma.selectFromMessage().tox_friendpubkeyEq(tox_friend_get_public_key__wrapper(3)).orderByMessage_idDesc().get(0);
            HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:2:" + ft_id);

            // ---------- DEBUG ----------
            Filetransfer ft_tmp = orma.selectFromFiletransfer().idEq(ft_id).get(0);
            HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:4a:" + "fid=" + ft_tmp.id + " mid=" + ft_tmp.message_id);
            // ---------- DEBUG ----------


            // add FT message to UI
            Message m = new Message();

            m.tox_friendpubkey = tox_friend_get_public_key__wrapper(friendnum);
            m.direction = 1; // msg outgoing
            m.TOX_MESSAGE_TYPE = 0;
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
            m.filetransfer_id = ft_id;
            m.filedb_id = -1;
            m.state = TOX_FILE_CONTROL_PAUSE.value;
            m.ft_accepted = false;
            m.ft_outgoing_started = false;
            m.ft_outgoing_queued = false;
            m.filename_fullpath = filepath;
            m.sent_timestamp = System.currentTimeMillis();
            m.text = HelperFiletransfer.buildOutgoingFileMessageText(c, filename, file_size);
            m.storage_frame_work = true;
            m.is_new = false; // no notification for outgoing filetransfers
            m.filetransfer_kind = TOX_FILE_KIND_DATA.value;

            long new_msg_id = insert_into_message_db(m, update_message_view);
            m.id = new_msg_id;

            // ---------- DEBUG ----------
            HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:3:" + new_msg_id);
            Message m_tmp = orma.selectFromMessage().idEq(new_msg_id).get(0);
            // HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:4:" + m.filetransfer_id + "::" + m_tmp);
            // ---------- DEBUG ----------

            f.message_id = new_msg_id;
            // ** // update_filetransfer_db_messageid_from_id(f, ft_id);
            update_filetransfer_db_full(f);

            // ---------- DEBUG ----------
            Filetransfer ft_tmp2 = orma.selectFromFiletransfer().idEq(ft_id).get(0);
            HelperGeneric.logI(TAG, "add_outgoing_file:MM2MM:4b:" + "fid=" + ft_tmp2.id + " mid=" + ft_tmp2.message_id);
            // ---------- DEBUG ----------

            finish_outgoing_file_enqueue(new_msg_id, update_message_view, startTransferImmediately);
            return new_msg_id;
        }
    }

    private static void finish_outgoing_file_enqueue(final long new_msg_id, final boolean update_message_view,
                                                     final boolean startTransferImmediately)
    {
        if (new_msg_id <= 0L)
        {
            return;
        }
        if (startTransferImmediately)
        {
            queue_and_try_send_outgoing_file(new_msg_id, update_message_view);
            return;
        }
        try
        {
            orma.updateMessage().idEq(new_msg_id).ft_outgoing_queued(true).execute();
            if (update_message_view)
            {
                update_single_message_from_messge_id(new_msg_id, true);
            }
            try
            {
                final Message staged = (Message) orma.selectFromMessage().idEq(new_msg_id).get(0);
                if ((staged != null) && (staged.tox_friendpubkey != null) && (!staged.tox_friendpubkey.isEmpty()))
                {
                    HelperFiletransfer.try_start_next_queued_outgoing_file(staged.tox_friendpubkey);
                }
            }
            catch (Exception e)
            {
                HelperGeneric.logI(TAG, "finish_outgoing_file_enqueue:kick:EE:" + e.getMessage());
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "finish_outgoing_file_enqueue:EE:" + e.getMessage());
        }
    }

    void open_friend_info_activity()
    {
        try
        {
            if (friendnum < 0)
            {
                return;
            }

            final Intent intent = new Intent(this, FriendInfoActivity.class);
            intent.putExtra("friendnum", friendnum);
            if (friend_pubkey != null)
            {
                intent.putExtra("friend_pubkey", friend_pubkey);
            }
            startActivity(intent);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void start_audio_call_to_friend(View view)
    {
        HelperGeneric.logI(TAG, "start_call_to_friend_real:audio_only");
        Callstate.audio_call = true;
        set_debug_text("_AUDIO_");
        HelperGeneric.logI(TAG, "toxav_call:Callstate.audio_call = true");
        start_call_to_friend_internal(view);
    }

    public void start_call_to_friend(View view)
    {
        Callstate.audio_call = false;
        set_debug_text("VIDEO");
        start_call_to_friend_internal(view);
    }

    private void start_call_to_friend_internal(View view)
    {
        if (!HelperCall.hasRequiredCallPermissions(this, Callstate.audio_call))
        {
            pendingCallAfterPermission = true;
            HelperCall.requestCallPermissions(this, Callstate.audio_call);
            return;
        }

        pendingCallAfterPermission = false;

        if (is_friend_online_real(friendnum) == 0)
        {
            final long fn = friendnum;
            final String calling_friend_pk = tox_friend_get_public_key__wrapper(fn);
            Intent intent = new Intent(context_s, CallingWaitingActivity.class);
            intent.putExtra("calling_friend_pk", calling_friend_pk);
            startActivityForResult(intent, CallingWaitingActivity_ID);
        }
        else
        {
            start_call_to_friend_real(view);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != HelperCall.REQUEST_CALL_PERMISSIONS)
        {
            return;
        }

        if (!pendingCallAfterPermission)
        {
            return;
        }

        if (HelperCall.hasRequiredCallPermissions(this, Callstate.audio_call))
        {
            start_call_to_friend_internal(null);
        }
        else
        {
            pendingCallAfterPermission = false;
            HelperCall.showMissingPermissionToast(this, Callstate.audio_call);
        }
    }

    public void start_call_to_friend_real(View view)
    {
        HelperGeneric.logI(TAG, "start_call_to_friend_real");

        if (!is_tox_started)
        {
            HelperGeneric.logI(TAG, "TOX:offline");
            return;
        }

        HelperGeneric.logI(TAG, "start_call_to_friend_real:friendnum=" + friendnum);

        if (is_friend_online(friendnum) == 0)
        {
            HelperGeneric.logI(TAG, "TOX:friend offline");
            try
            {
                Toast.makeText(this, R.string.friend_not_online, Toast.LENGTH_SHORT).show();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            return;
        }

        final long fn = friendnum;

        // these 2 bitrate values are very strange!! sometimes no video!!
        final int f_audio_enabled = 1;
        final int f_video_enabled = 1;

        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    HelperGeneric.logI(TAG, "CALL:start:(2.0):Callstate.state=" + Callstate.state);

                    if (Callstate.state == 0)
                    {
                        HelperGeneric.logI(TAG, "CALL:start:(2.1):show activity");
                        if (PREF__use_software_aec || !Callstate.audio_call)
                        {
                            set_aec_active(1);
                        }
                        else
                        {
                            set_aec_active(0);
                        }
                        set_gainprocessing_active(1);

                        Callstate.state = 1;
                        Callstate.accepted_call = 1; // we started the call, so it's already accepted on our side
                        Callstate.call_first_video_frame_received = -1;
                        Callstate.call_start_timestamp = -1;
                        HelperGeneric.logI(TAG, "friend_pubkey:set:004");
                        Callstate.friend_pubkey = tox_friend_get_public_key__wrapper(fn);
                        Callstate.camera_opened = Callstate.audio_call;
                        Callstate.audio_speaker = !Callstate.audio_call;
                        Callstate.other_audio_enabled = 1;
                        Callstate.other_video_enabled = 1;
                        Callstate.my_audio_enabled = 1;
                        Callstate.my_video_enabled = 1;
                        MainActivity.set_av_call_status(Callstate.state);
                        clear_audio_play_buffers();
                        try
                        {
                            set_audio_aec_delay(PREF__X_eac_delay_ms);
                        }
                        catch (Exception e)
                        {
                        }
                        Intent intent = new Intent(context_s, CallingActivity.class);
                        Callstate.friend_alias_name = get_friend_name_from_pubkey(Callstate.friend_pubkey);

                        Thread t = new Thread()
                        {
                            @Override
                            public void run()
                            {
                                HelperGeneric.logI(TAG, "wating for camera open");

                                try
                                {
                                    Thread.sleep(20);
                                }
                                catch (Exception e)
                                {
                                    e.printStackTrace();
                                }

                                boolean waiting = true;
                                int i = 0;
                                while (waiting)
                                {
                                    i++;
                                    if (Callstate.camera_opened)
                                    {
                                        HelperGeneric.logI(TAG, "Callstate.camera_opened" + Callstate.camera_opened);
                                        waiting = false;
                                    }

                                    if (i > 80)
                                    {
                                        waiting = false;
                                    }

                                    try
                                    {
                                        Thread.sleep(200); // wait a bit
                                    }
                                    catch (Exception e)
                                    {
                                        e.printStackTrace();
                                    }
                                }
                                try
                                {
                                    CallingActivity.top_text_line_str2 = "0s";
                                    update_top_text_line();
                                    HelperGeneric.logI(TAG, "CALL_OUT:001:friendnum=" + fn + " f_audio_enabled=" + f_audio_enabled +
                                               " f_video_enabled=" + f_video_enabled);

                                    Callstate.audio_bitrate = GLOBAL_AUDIO_BITRATE;
                                    Callstate.video_bitrate = GLOBAL_VIDEO_BITRATE;
                                    VIDEO_FRAME_RATE_OUTGOING = 0;
                                    last_video_frame_sent = -1;
                                    VIDEO_FRAME_RATE_INCOMING = 0;
                                    last_video_frame_received = -1;
                                    count_video_frame_received = 0;
                                    count_video_frame_sent = 0;

                                    int call_res;
                                    if (Callstate.audio_call)
                                    {
                                        call_res = MainActivity.toxav_call(fn, GLOBAL_AUDIO_BITRATE, 0);
                                        HelperGeneric.logI(TAG, "toxav_call:audio_call:RES=" + call_res);
                                    }
                                    else
                                    {
                                        call_res = MainActivity.toxav_call(fn, GLOBAL_AUDIO_BITRATE,
                                                                           GLOBAL_VIDEO_BITRATE);
                                        HelperGeneric.logI(TAG, "toxav_call:video_call:RES=" + call_res);
                                    }

                                    wakeup_tox_thread();

                                    final String friend_pk = Callstate.friend_pubkey;
                                    if (call_res == 1)
                                    {
                                        friend_call_push_url(friend_pk, System.currentTimeMillis());
                                        HelperCall.logCallEvent(friend_pk,
                                                Callstate.audio_call ? R.string.call_log_outgoing_voice
                                                                     : R.string.call_log_outgoing_video);
                                    }
                                    else
                                    {
                                        HelperCall.logCallEvent(friend_pk, R.string.call_log_failed);
                                        try
                                        {
                                            Toast.makeText(context_s, R.string.call_start_failed, LENGTH_LONG).show();
                                        }
                                        catch (Exception e)
                                        {
                                        }
                                    }
                                    HelperGeneric.logI(TAG, "CALL_OUT:002");
                                }
                                catch (Exception e)
                                {
                                    HelperGeneric.logI(TAG, "CALL_OUT:EE1:" + e.getMessage());
                                    e.printStackTrace();
                                }
                            }
                        };
                        t.start();

                        Callstate.other_audio_enabled = f_audio_enabled;
                        Callstate.other_video_enabled = f_video_enabled;
                        Callstate.call_init_timestamp = System.currentTimeMillis();
                        launch_outgoing_calling_activity(MessageListActivity.this, intent);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.logI(TAG, "CALL:start:(2):EE:" + e.getMessage());
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri     The Uri to query.
     * @author paulburke
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    public static String getPath(final Context context, final Uri uri)
    {
        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri))
        {
            HelperGeneric.logI(TAG, "getPath:001:uri=" + uri);

            // ExternalStorageProvider
            if (isExternalStorageDocument(uri))
            {
                HelperGeneric.logI(TAG, "getPath:002");
                final String docId = DocumentsContract.getDocumentId(uri);
                HelperGeneric.logI(TAG, "getPath:003:docId=" + docId);
                final String[] split = docId.split(":");
                HelperGeneric.logI(TAG, "getPath:004:split=" + split[0] + " " + split[1]);
                final String type = split[0];
                HelperGeneric.logI(TAG, "getPath:005:type=" + type);

                if ("primary".equalsIgnoreCase(type))
                {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }
                else
                {
                    // TODO handle non-primary volumes
                    try
                    {
                        String strSDCardPath = System.getenv("SECONDARY_STORAGE");
                        HelperGeneric.logI(TAG, "getPath:SECONDARY_STORAGE=" + strSDCardPath);

                        if ((strSDCardPath == null) || (strSDCardPath.length() == 0))
                        {
                            HelperGeneric.logI(TAG, "getPath:006");
                            strSDCardPath = System.getenv("EXTERNAL_SDCARD_STORAGE");
                            HelperGeneric.logI(TAG, "getPath:EXTERNAL_SDCARD_STORAGE=" + strSDCardPath);
                        }

                        if (strSDCardPath == null)
                        {
                            // ok, last try
                            strSDCardPath = "/storage/" + type;
                            return strSDCardPath + "/" + split[1];
                        }

                        //If may get a full path that is not the right one, even if we don't have the SD Card there.
                        //We just need the "/mnt/extSdCard/" i.e and check if it's writable
                        HelperGeneric.logI(TAG, "getPath:007");
                        if (strSDCardPath != null)
                        {
                            HelperGeneric.logI(TAG, "getPath:008");
                            if (strSDCardPath.contains(":"))
                            {
                                HelperGeneric.logI(TAG, "getPath:009");
                                strSDCardPath = strSDCardPath.substring(0, strSDCardPath.indexOf(":"));
                            }
                            // File externalFilePath = new File(strSDCardPath);
                            HelperGeneric.logI(TAG, "getPath:strSDCardPath=" + strSDCardPath);

                            return strSDCardPath + "/" + split[1];
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        HelperGeneric.logI(TAG, "getPath:EE3:" + e.getMessage());
                    }
                }
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri))
            {

                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"),
                                                                  Long.valueOf(id));

                return getDataColumn(context, contentUri, null, null);
            }
            // MediaProvider
            else if (isMediaDocument(uri))
            {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type))
                {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }
                else if ("video".equals(type))
                {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                }
                else if ("audio".equals(type))
                {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{split[1]};

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme()))
        {
            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme()))
        {
            return uri.getPath();
        }

        return null;
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context       The context.
     * @param uri           The Uri to query.
     * @param selection     (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */

    public static String getDataColumn(Context context, Uri uri, String selection, String[] selectionArgs)
    {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {column};

        try
        {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst())
            {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        }
        finally
        {
            if (cursor != null)
            {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri)
    {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri)
    {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri)
    {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    static boolean onClick_message_helper(final View v, boolean is_selected, final Message message_)
    {
        try
        {
            if (is_selected)
            {
                v.setBackgroundColor(Color.TRANSPARENT);
                is_selected = false;
                selected_messages.remove(message_.id);
                selected_messages_text_only.remove(message_.id);
                selected_messages_incoming_file.remove(message_.id);
                if (selected_messages_incoming_file.size() == selected_messages.size())
                {
                    amode_save_menu_item.setVisible(true);
                }
                else
                {
                    amode_save_menu_item.setVisible(false);
                }

                if (selected_messages.size() == 1)
                {
                    amode_info_menu_item.setVisible(true);
                }
                else
                {
                    amode_info_menu_item.setVisible(false);
                }

                if (selected_messages.isEmpty())
                {
                    // last item was de-selected
                    amode.finish();
                }
                else
                {
                    if (amode != null)
                    {
                        amode.setTitle("" + selected_messages.size() + " selected");
                    }
                }
            }
            else
            {
                if (!selected_messages.isEmpty())
                {
                    v.setBackgroundColor(Color.GRAY);
                    is_selected = true;
                    selected_messages.add(message_.id);
                    if (message_.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
                    {
                        selected_messages_text_only.add(message_.id);
                    }
                    else if (message_.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                    {
                        if (message_.direction == 0)
                        {
                            selected_messages_incoming_file.add(message_.id);
                        }
                    }

                    if (selected_messages_incoming_file.size() == selected_messages.size())
                    {
                        amode_save_menu_item.setVisible(true);
                    }
                    else
                    {
                        amode_save_menu_item.setVisible(false);
                    }

                    if (selected_messages.size() == 1)
                    {
                        amode_info_menu_item.setVisible(true);
                    }
                    else
                    {
                        amode_info_menu_item.setVisible(false);
                    }

                    if (amode != null)
                    {
                        amode.setTitle("" + selected_messages.size() + " selected");
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

    static long_click_message_return onLongClick_message_helper(Context context, final View v, boolean is_selected, final Message message_)
    {
        long_click_message_return ret = new long_click_message_return();

        try
        {
            if (is_selected)
            {
                ret.is_selected = true;
            }
            else
            {
                if (selected_messages.isEmpty())
                {
                    try
                    {
                        amode = message_list_activity.startSupportActionMode(new ToolbarActionMode(context));
                        amode_save_menu_item = amode.getMenu().findItem(R.id.action_save);
                        amode_info_menu_item = amode.getMenu().findItem(R.id.action_info);
                        v.setBackgroundColor(Color.GRAY);
                        ret.is_selected = true;
                        selected_messages.add(message_.id);
                        if (message_.TRIFA_MESSAGE_TYPE == TRIFA_MSG_TYPE_TEXT.value)
                        {
                            selected_messages_text_only.add(message_.id);
                        }
                        else if (message_.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                        {
                            if (message_.direction == 0)
                            {
                                selected_messages_incoming_file.add(message_.id);
                            }
                        }

                        if (selected_messages_incoming_file.size() == selected_messages.size())
                        {
                            amode_save_menu_item.setVisible(true);
                        }
                        else
                        {
                            amode_save_menu_item.setVisible(false);
                        }

                        if (selected_messages.size() == 1)
                        {
                            amode_info_menu_item.setVisible(true);
                        }
                        else
                        {
                            amode_info_menu_item.setVisible(false);
                        }

                        if (amode != null)
                        {
                            amode.setTitle("" + selected_messages.size() + " selected");
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

    static void show_messagelist_for_friend(Context c, String friend_pubkey, String fill_out_text)
    {
        Intent intent = new Intent(c, MessageListActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (fill_out_text != null)
        {
            intent.putExtra("fillouttext", fill_out_text);
        }
        intent.putExtra("friendnum", tox_friend_by_public_key__wrapper(friend_pubkey));
        intent.putExtra("friend_pubkey", friend_pubkey);
        c.startActivity(intent);
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        setIntent(intent);
        final String new_friend_pubkey = intent.getStringExtra("friend_pubkey");
        if ((new_friend_pubkey == null) || new_friend_pubkey.equalsIgnoreCase(friend_pubkey))
        {
            return;
        }
        try
        {
            if ((friend_pubkey != null) && (ml_new_message != null))
            {
                ChatDraftHelper.save_friend_draft(friend_pubkey, ml_new_message.getText().toString());
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
            MainActivity.message_list_fragment.listingsView.scrollToPosition(
                    MainActivity.message_list_fragment.adapter.getItemCount() - 1);
        }
        catch (Exception ignored)
        {
        }

        MessageListFragment.is_at_bottom = true;

        try
        {
            do_fade_anim_on_fab(MainActivity.message_list_fragment.unread_messages_notice_button, false,
                                this.getClass().getName());
            MainActivity.message_list_fragment.unread_messages_notice_button.setSupportBackgroundTintList(
                    (ContextCompat.getColorStateList(context_s, R.color.message_list_scroll_to_bottom_fab_bg_normal)));
        }
        catch (Exception ignored)
        {
        }
    }
}

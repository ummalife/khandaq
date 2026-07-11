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

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.luseen.autolinklibrary.AutoLinkMode;
import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.Message;

import java.net.URLConnection;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.HelperFiletransfer.get_filetransfer_filenum_from_id;
import static com.zoffcc.applications.trifa.HelperFiletransfer.bindOutgoingCompactAudioUi;
import static com.zoffcc.applications.trifa.HelperFiletransfer.bindOutgoingCompactMediaUi;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isAudioMessage;
import static com.zoffcc.applications.trifa.HelperFiletransfer.kick_stalled_outgoing_send;
import static com.zoffcc.applications.trifa.HelperFiletransfer.outgoingFileDisplayLabel;
import static com.zoffcc.applications.trifa.HelperFiletransfer.queue_and_try_send_outgoing_file;
import static com.zoffcc.applications.trifa.HelperFiletransfer.remove_ft_from_cache;
import static com.zoffcc.applications.trifa.HelperFiletransfer.resetOutgoingFtAudioPlayer;
import static com.zoffcc.applications.trifa.HelperFiletransfer.set_filetransfer_state_from_id;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.get_vfs_image_filename_own_avatar;
import static com.zoffcc.applications.trifa.HelperGeneric.long_date_time_format;
import static com.zoffcc.applications.trifa.HelperMessage.set_message_queueing_from_id;
import static com.zoffcc.applications.trifa.HelperMessage.set_message_state_from_id;
import static com.zoffcc.applications.trifa.HelperMessage.update_single_message_from_messge_id;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.MainActivity.tox_file_control;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_FTV2;

/*
 *
 * HINT: this is the START POINT for outgoing FT
 * when nothing has started yet, but a file was selected to send
 *
 */
public class MessageListHolder_file_outgoing_state_pause_not_yet_started extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener
{
    private static final String TAG = "trifa.MessageListHldr01";

    private Context context;

    ImageButton button_ok;
    ImageButton button_cancel;
    com.daimajia.numberprogressbar.NumberProgressBar ft_progressbar;
    ViewGroup ft_preview_container;
    ViewGroup ft_buttons_container;
    ImageButton ft_preview_image;
    EmojiTextViewLinks textView;
    ImageView imageView;
    ImageView m_status;
    de.hdodenhof.circleimageview.CircleImageView img_avatar;
    TextView date_time;
    TextView message_text_date_string;
    ViewGroup message_text_date;
    ViewGroup rounded_bg_container;
    me.jagar.chatvoiceplayerlibrary.VoicePlayerView ft_audio_player;

    public MessageListHolder_file_outgoing_state_pause_not_yet_started(View itemView, Context c)
    {
        super(itemView);

        // Log.i(TAG, "MessageListHolder");

        this.context = c;

        button_ok = (ImageButton) itemView.findViewById(R.id.ft_button_ok);
        button_cancel = (ImageButton) itemView.findViewById(R.id.ft_button_cancel);
        ft_progressbar = (com.daimajia.numberprogressbar.NumberProgressBar) itemView.findViewById(R.id.ft_progressbar);
        ft_preview_container = (ViewGroup) itemView.findViewById(R.id.ft_preview_container);
        ft_buttons_container = (ViewGroup) itemView.findViewById(R.id.ft_buttons_container);
        ft_preview_image = (ImageButton) itemView.findViewById(R.id.ft_preview_image);
        rounded_bg_container = (ViewGroup) itemView.findViewById(R.id.ft_outgoing_rounded_bg);
        textView = (EmojiTextViewLinks) itemView.findViewById(R.id.m_text);
        imageView = (ImageView) itemView.findViewById(R.id.m_icon);
        m_status = (ImageView) itemView.findViewById(R.id.m_status);
        img_avatar = (de.hdodenhof.circleimageview.CircleImageView) itemView.findViewById(R.id.img_avatar);
        date_time = (TextView) itemView.findViewById(R.id.date_time);
        message_text_date_string = (TextView) itemView.findViewById(R.id.message_text_date_string);
        message_text_date = (ViewGroup) itemView.findViewById(R.id.message_text_date);
        ft_audio_player = itemView.findViewById(R.id.ft_audio_player);
    }

    public void bindMessageList(Message m)
    {
        // Log.i(TAG, "bindMessageList");

        if (m == null)
        {
            // TODO: should never be null!!
            // only afer a crash
            m = new Message();
        }

        date_time.setText(long_date_time_format(m.sent_timestamp));
        // Media/file bubbles had only the near-invisible external date; show the in-bubble time like text messages.
        ChatBubbleUiHelper.bind_bubble_time(ChatBubbleUiHelper.find_bubble_time(itemView), date_time,
                HelperGeneric.format_chat_message_time(m, true), true);

        // KHANDAQ #23: queued, transfer not started yet -> clock (sending).
        ChatBubbleUiHelper.bind_outgoing_file_status(m_status, MessageStatusHelper.OutgoingStatus.SENDING);

        final Message message = m;

        textView.addAutoLinkMode(AutoLinkMode.MODE_URL, AutoLinkMode.MODE_EMAIL, AutoLinkMode.MODE_HASHTAG,
                                 AutoLinkMode.MODE_MENTION);

        ft_progressbar.setVisibility(View.GONE);
        ft_buttons_container.setVisibility(View.VISIBLE);
        ft_preview_container.setVisibility(View.VISIBLE);
        ft_preview_image.setVisibility(View.VISIBLE);

        final Message message2 = message;

        ChatBubbleUiHelper.apply_file_message_bubble(rounded_bg_container, true, false);

        // --------- message date header (show only if different from previous message) ---------
        // --------- message date header (show only if different from previous message) ---------
        // --------- message date header (show only if different from previous message) ---------
        message_text_date.setVisibility(View.GONE);
        int my_position = this.getAdapterPosition();
        if (my_position != RecyclerView.NO_POSITION)
        {
            if (MainActivity.message_list_fragment != null)
            {
                if (MainActivity.message_list_fragment.adapter != null)
                {
                    if (my_position < 1)
                    {
                        message_text_date_string.setText(
                                MainActivity.message_list_fragment.adapter.getDateHeaderText(my_position));
                        message_text_date.setVisibility(View.VISIBLE);
                    }
                    else
                    {
                        if (!MainActivity.message_list_fragment.adapter.getDateHeaderText(my_position).equals(
                                MainActivity.message_list_fragment.adapter.getDateHeaderText(my_position - 1)))
                        {
                            message_text_date_string.setText(
                                    MainActivity.message_list_fragment.adapter.getDateHeaderText(my_position));
                            message_text_date.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
        }
        // --------- message date header (show only if different from previous message) ---------
        // --------- message date header (show only if different from previous message) ---------
        // --------- message date header (show only if different from previous message) ---------


        final Drawable d1 = new IconicsDrawable(context).
                icon(GoogleMaterial.Icon.gmd_check_circle).
                backgroundColor(Color.TRANSPARENT).
                color(Color.parseColor("#EF088A29")).sizeDp(50);
        button_ok.setImageDrawable(d1);
        final Drawable d2 = new IconicsDrawable(context).
                icon(GoogleMaterial.Icon.gmd_highlight_off).
                backgroundColor(Color.TRANSPARENT).
                color(Color.parseColor("#A0FF0000")).sizeDp(50);
        button_cancel.setImageDrawable(d2);
        ft_buttons_container.setVisibility(View.VISIBLE);

        button_ok.setVisibility(View.VISIBLE);
        button_cancel.setVisibility(View.VISIBLE);

        HelperGeneric.fill_own_avatar_icon(context, img_avatar);
        resetOutgoingFtAudioPlayer(ft_audio_player);

        final String label = outgoingFileDisplayLabel(context, message);
        final boolean transferUiActive = message.ft_outgoing_queued;

        if (isAudioMessage(context, message))
        {
            bindOutgoingCompactAudioUi(context, message, textView, imageView, ft_preview_container, ft_preview_image,
                                       ft_buttons_container, ft_progressbar, ft_audio_player, button_ok, button_cancel,
                                       false, 0, true);
            button_ok.setVisibility(View.GONE);
            setup_cancel_button(message);
            HelperGeneric.set_avatar_img_height_in_chat(img_avatar);
            return;
        }

        if (bindOutgoingCompactMediaUi(context, itemView, message, textView, imageView, ft_preview_container,
                                       ft_preview_image, ft_buttons_container, ft_progressbar, ft_audio_player,
                                       button_ok, button_cancel, transferUiActive))
        {
            if (transferUiActive)
            {
                kick_stalled_outgoing_send(message);
                ChatTransferProgressHelper.applyDirect(context, itemView, message, true);
            }
            else
            {
                textView.setVisibility(View.VISIBLE);
                textView.setAutoLinkText(context.getString(R.string.chat_ft_status_confirm_send));
            }

            if (transferUiActive)
            {
                button_ok.setVisibility(View.GONE);
            }
            else
            {
                button_ok.setVisibility(View.VISIBLE);
            }

            button_ok.setOnTouchListener(new View.OnTouchListener()
            {
                @Override
                public boolean onTouch(View v, MotionEvent event)
                {
                    if (event.getAction() == MotionEvent.ACTION_DOWN)
                    {
                        try
                        {
                            queue_and_try_send_outgoing_file(message.id, true);
                            button_ok.setVisibility(View.GONE);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                            Log.i(TAG, "button_ok:media:EE:" + e.getMessage());
                        }
                    }
                    return true;
                }
            });

            setup_cancel_button(message);
            HelperGeneric.set_avatar_img_height_in_chat(img_avatar);
            return;
        }

        if (message.ft_outgoing_queued)
        {
            String status_line;
            if (global_self_connection_status == TOX_CONNECTION_NONE.value)
            {
                status_line = context.getString(R.string.chat_ft_status_waiting_self_online);
            }
            else if (is_friend_online_real(tox_friend_by_public_key__wrapper(message.tox_friendpubkey)) == 0)
            {
                status_line = context.getString(R.string.chat_ft_status_waiting_friend_online);
            }
            else
            {
                status_line = context.getString(R.string.chat_ft_status_sending);
            }
            textView.setAutoLinkText(label + "\n\n" + status_line);
        }
        else
        {
            textView.setAutoLinkText(label + "\n\n" +
                    context.getString(R.string.chat_ft_status_confirm_send));
        }

        if (message.ft_outgoing_queued)
        {
            button_ok.setVisibility(View.GONE);
        }
        else
        {
            button_ok.setVisibility(View.VISIBLE);
        }

        button_ok.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() == MotionEvent.ACTION_DOWN)
                {
                    try
                    {
                        queue_and_try_send_outgoing_file(message.id, true);
                        button_ok.setVisibility(View.GONE);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                        Log.i(TAG, "button_ok:generic:EE:" + e.getMessage());
                    }
                }
                return true;
            }
        });

        setup_cancel_button(message);

        ft_preview_container.setVisibility(View.GONE);
        ft_preview_image.setVisibility(View.GONE);

        if (message.ft_outgoing_queued)
        {
            kick_stalled_outgoing_send(message);
            ChatTransferProgressHelper.applyDirect(context, itemView, message, true);
        }

        HelperGeneric.set_avatar_img_height_in_chat(img_avatar);
    }

    private void setup_cancel_button(final Message message)
    {
        button_cancel.setOnTouchListener(new View.OnTouchListener()
        {
            @Override
            public boolean onTouch(View v, MotionEvent event)
            {
                if (event.getAction() == MotionEvent.ACTION_DOWN)
                {
                    AlertDialog.Builder builder = new AlertDialog.Builder(v.getContext());
                    builder.setTitle(
                            v.getContext().getString(R.string.MessageListHolder_file_outgoing_cancel_ft_title));
                    builder.setMessage(
                            v.getContext().getString(R.string.MessageListHolder_file_outgoing_cancel_ft_message));

                    builder.setNegativeButton(v.getContext().getString(R.string.MainActivity_no_button), null);
                    builder.setPositiveButton(v.getContext().getString(R.string.MainActivity_yes_button),
                                              new DialogInterface.OnClickListener()
                                              {
                                                  @Override
                                                  public void onClick(DialogInterface dialog, int which)
                                                  {
                                                      cancel_outgoing_filetransfer(message);
                                                  }
                                              });

                    AlertDialog dialog = builder.create();
                    dialog.show();
                }
                return true;
            }
        });
    }

    private void cancel_outgoing_filetransfer(final Message message)
    {
        try
        {
            set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_CANCEL.value);
            if (message.ft_outgoing_queued)
            {
                set_message_queueing_from_id(message.id, false);
            }
            else
            {
                // cancel FT
                Log.i(TAG, "button_cancel:OnTouch:001");
                int res = tox_file_control(tox_friend_by_public_key__wrapper(message.tox_friendpubkey),
                                           get_filetransfer_filenum_from_id(message.filetransfer_id),
                                           TOX_FILE_CONTROL_CANCEL.value);
                Log.i(TAG, "button_cancel:OnTouch:res=" + res);
            }

            set_message_state_from_id(message.id, TOX_FILE_CONTROL_CANCEL.value);

            // TODO: cleanup duplicated outgoing files from provider here ************
            remove_ft_from_cache(message);

            button_ok.setVisibility(View.GONE);
            button_cancel.setVisibility(View.GONE);
            ft_progressbar.setVisibility(View.GONE);

            // update message view
            update_single_message_from_messge_id(message.id, true);
            Log.i(TAG, "button_cancel:OnTouch:099");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "button_cancel:OnTouch:EE:" + e.getMessage());
        }
    }

    @Override
    public void onClick(View v)
    {
        Log.i(TAG, "onClick");
        try
        {
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "onClick:EE:" + e.getMessage());
        }
    }

    @Override
    public boolean onLongClick(final View v)
    {
        Log.i(TAG, "onLongClick");

        // final Message m2 = this.message;

        //        PopupMenu menu = new PopupMenu(v.getContext(), v);
        //        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener()
        //        {
        //            @Override
        //            public boolean onMenuItemClick(MenuItem item)
        //            {
        //                int id = item.getItemId();
        //                return true;
        //            }
        //        });
        //        menu.inflate(R.menu.menu_friendlist_item);
        //        menu.show();

        return true;
    }
}

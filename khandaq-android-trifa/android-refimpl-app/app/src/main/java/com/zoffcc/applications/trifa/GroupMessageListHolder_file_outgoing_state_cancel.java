package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
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
import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.GroupMessage;

import java.net.URLConnection;

import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.ChatMediaHelper.bindVideoPreview;
import static com.zoffcc.applications.trifa.ChatMediaHelper.groupMediaOpenTouchListener;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupAudioMessage;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupImageMessage;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupVideoMessage;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.short_time_format;
import static com.zoffcc.applications.trifa.MainActivity.PREF__compact_chatlist;
import static com.zoffcc.applications.trifa.MainActivity.PREF__global_font_size;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_TEXT_SIZE;

public class GroupMessageListHolder_file_outgoing_state_cancel extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener
{
    private static final String TAG = "trifa.MessageListHolder";

    private GroupMessage message_;
    private Context context;

    ImageButton button_ok;
    ImageButton button_cancel;
    com.daimajia.numberprogressbar.NumberProgressBar ft_progressbar;
    ViewGroup ft_preview_container;
    ViewGroup ft_buttons_container;
    ImageButton ft_preview_image;
    EmojiTextViewLinks textView;
    ImageView imageView;
    de.hdodenhof.circleimageview.CircleImageView img_avatar;
    TextView date_time;
    ViewGroup layout_message_container;
    ViewGroup rounded_bg_container;
    boolean is_selected = false;
    TextView message_text_date_string;
    ViewGroup message_text_date;
    me.jagar.chatvoiceplayerlibrary.VoicePlayerView ft_audio_player;

    public GroupMessageListHolder_file_outgoing_state_cancel(View itemView, Context c)
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
        img_avatar = (de.hdodenhof.circleimageview.CircleImageView) itemView.findViewById(R.id.img_avatar);
        date_time = (TextView) itemView.findViewById(R.id.date_time);
        layout_message_container = (ViewGroup) itemView.findViewById(R.id.layout_message_container);
        message_text_date_string = (TextView) itemView.findViewById(R.id.message_text_date_string);
        message_text_date = (ViewGroup) itemView.findViewById(R.id.message_text_date);
        ft_audio_player = itemView.findViewById(R.id.ft_audio_player);
    }

    @SuppressLint("ClickableViewAccessibility")
    public void bindMessageList(GroupMessage m)
    {
        // Log.i(TAG, "bindMessageList");

        if (m == null)
        {
            // TODO: should never be null!!
            // only afer a crash
            m = new GroupMessage();
        }

        message_ = m;

        if (!GroupMessageLayoutHelper.isRenderableMessage(context, m))
        {
            GroupMessageLayoutHelper.applyRowVisibility(itemView, layout_message_container,
                    GroupMessageLayoutHelper.hiddenRowLayout());
            return;
        }

        if (ft_audio_player != null)
        {
            ft_audio_player.setVisibility(View.GONE);
        }
        ChatFileBubbleHelper.hide(itemView);
        ChatFileBubbleHelper.hideMediaPreview(itemView);

        is_selected = false;
        if (selected_group_messages.isEmpty())
        {
            is_selected = false;
        }
        else
        {
            is_selected = selected_group_messages.contains(m.id);
        }

        if (is_selected)
        {
            layout_message_container.setBackgroundColor(Color.GRAY);
        }
        else
        {
            layout_message_container.setBackgroundColor(Color.TRANSPARENT);
        }

        itemView.setOnClickListener(this);
        itemView.setOnLongClickListener(this);

        layout_message_container.setOnClickListener(onclick_listener);
        layout_message_container.setOnLongClickListener(onlongclick_listener);

        textView.setVisibility(View.GONE);
        imageView.setVisibility(View.GONE);

        final GroupMessage message = m;

        final boolean is_image = isGroupImageMessage(context, message);
        final boolean is_video = isGroupVideoMessage(context, message);
        final boolean is_audio = isGroupAudioMessage(context, message);

        // Build the preview image only when the displayed message changes — progress re-binds otherwise
        // restart the loading spinner / re-decode the partial file every time => visible flicker.
        final String previewSig = "ld:" + (message.msg_id_hash != null ? message.msg_id_hash : "");
        final boolean previewAlreadyBuilt = previewSig.equals(ft_preview_image.getTag(R.id.ft_preview_image));
        if (!previewAlreadyBuilt)
        {
            GlideApp.with(context).clear(ft_preview_image);
            ft_preview_image.setImageResource(R.drawable.round_loading_animation);
            ft_preview_image.setTag(R.id.ft_preview_image, previewSig);
        }

        if (is_image)
        {
            ChatFileBubbleHelper.showMediaPreview(itemView, (int) dp2px(150));
            textView.setVisibility(View.GONE);

            ft_preview_image.setOnTouchListener(groupMediaOpenTouchListener(context, message, null));

            final String mediaPath = HelperFiletransfer.resolveGroupMessageMediaPath(message);
            final RequestOptions glide_options = new RequestOptions().
                    centerCrop().
                    optionalTransform(new RoundedCorners(ChatBubbleUiHelper.media_corner_radius_px(context)));

            // KHANDAQ (#67): always (re)issue the load so a recycled/cleared preview is repopulated
            // (served from Glide memory cache on repeat = no flicker); only the spinner reset is gated.
            if (message.storage_frame_work && (mediaPath != null))
            {
                try
                {
                    GlideApp.
                            with(context).
                            load(Uri.parse(mediaPath)).
                            apply(glide_options).
                            diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                            skipMemoryCache(false).
                            priority(Priority.LOW).
                            placeholder(R.drawable.round_loading_animation).
                            into(ft_preview_image);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            else if (VFS_ENCRYPT && HelperFiletransfer.isIocipherVirtualPath(mediaPath))
            {
                try
                {
                    GlideApp.
                            with(context).
                            load(new info.guardianproject.iocipher.File(mediaPath)).
                            apply(glide_options).
                            diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                            skipMemoryCache(false).
                            priority(Priority.LOW).
                            placeholder(R.drawable.round_loading_animation).
                            into(ft_preview_image);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            else if (mediaPath != null)
            {
                try
                {
                    GlideApp.
                            with(context).
                            load(new java.io.File(mediaPath)).
                            apply(glide_options).
                            diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                            skipMemoryCache(false).
                            priority(Priority.LOW).
                            placeholder(R.drawable.round_loading_animation).
                            into(ft_preview_image);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }
        else if (is_video)  // ---- a video ----
        {
            textView.setVisibility(View.GONE);

            ChatFileBubbleHelper.showMediaPreview(itemView, (int) dp2px(180));

            if (!previewAlreadyBuilt)
            {
                bindVideoPreview(context, message.filename_fullpath, message.id, null, ft_preview_image);
            }
            ft_preview_image.setOnTouchListener(groupMediaOpenTouchListener(context, message, null));
        }
        else if (is_audio) // ---- an audio file ----
        {
            if (PREF__compact_chatlist)
            {
                textView.setVisibility(View.GONE);
                imageView.setVisibility(View.GONE);
            }

            ft_progressbar.setVisibility(View.GONE);
            ft_buttons_container.setVisibility(View.GONE);
            button_ok.setVisibility(View.GONE);
            button_cancel.setVisibility(View.GONE);

            ft_preview_container.setVisibility(View.VISIBLE);
            ChatFileBubbleHelper.hideMediaPreview(itemView);

            if (ft_audio_player != null)
            {
                HelperFiletransfer.safeRefreshAudioPlayer(ft_audio_player, message.filename_fullpath);
            }
        }
        else // ---- not an image or a video or an audio ----
        {
            textView.setVisibility(View.GONE);
            ChatFileBubbleHelper.hideMediaPreview(itemView);
        }

        ft_preview_container.setVisibility(View.VISIBLE);
        if (!is_image && !is_video)
        {
            ChatFileBubbleHelper.hideMediaPreview(itemView);
        }

        ft_buttons_container.setVisibility(View.GONE);

        HelperGeneric.fill_own_avatar_icon(context, img_avatar);
        img_avatar.setVisibility(View.GONE);

        final int layoutPosition = this.getAdapterPosition();
        final GroupMessageLayoutHelper.RowLayout rowLayout =
                GroupMessageLayoutHelper.layoutFor(m, layoutPosition, context);
        GroupMessageLayoutHelper.applyRowVisibility(itemView, layout_message_container, rowLayout);
        GroupMessageLayoutHelper.applyTopMargin(itemView, rowLayout);
        if (rowLayout.hidePeerHeader)
        {
            img_avatar.setVisibility(View.GONE);
            imageView.setVisibility(View.GONE);
        }

        HelperGeneric.set_avatar_img_height_in_chat(img_avatar);
        ChatTransferProgressHelper.applyGroup(context, itemView, message_, true);
        ChatBubbleUiHelper.bind_bubble_time(ChatBubbleUiHelper.find_bubble_time(itemView), date_time,
                HelperGeneric.format_group_message_time(m, true), true);
    }

    @Override
    public void onClick(View v)
    {
        // Log.i(TAG, "onClick");
    }

    void DetachedFromWindow(boolean release)
    {
        // KHANDAQ: outgoing photo/file bubbles have no audio player set up — onPause/onStop NPE'd internally
        // (logged stack trace). Guard null + swallow the lib's internal NPE for non-audio holders.
        if (ft_audio_player == null)
        {
            return;
        }
        try
        {
            ft_audio_player.onPause();
            if (release)
            {
                ft_audio_player.onStop();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    @Override
    public boolean onLongClick(final View v)
    {
        // Log.i(TAG, "onLongClick");
        return true;
    }

    private View.OnClickListener onclick_listener = new View.OnClickListener()
    {
        @Override
        public void onClick(final View v)
        {
            is_selected = GroupMessageListActivity.onClick_message_helper(v, is_selected, message_);
        }
    };

    private View.OnLongClickListener onlongclick_listener = new View.OnLongClickListener()
    {
        @Override
        public boolean onLongClick(final View v)
        {
            GroupMessageListActivity.long_click_message_return res =
                    GroupMessageListActivity.onLongClick_message_helper(context, v, is_selected,
                                                                                           message_);
            is_selected = res.is_selected;
            return res.ret_value;
        }
    };
}

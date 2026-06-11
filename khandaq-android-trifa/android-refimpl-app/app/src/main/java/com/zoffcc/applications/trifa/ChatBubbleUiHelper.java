package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.amulyakhare.textdrawable.TextDrawable;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import de.hdodenhof.circleimageview.CircleImageView;

import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.hash_to_bucket;
import static com.zoffcc.applications.trifa.HelperGeneric.isColorDarkBrightness;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FRIEND_AVATAR_FILENAME;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

final class ChatBubbleUiHelper
{
    /** Auto-generated Tox identicons use a fixed VFS filename; keep Telegram-style placeholders for those. */
    static boolean shouldLoadVfsAvatar(final FriendList friend)
    {
        if (friend == null || friend.avatar_pathname == null || friend.avatar_filename == null)
        {
            return false;
        }

        return !FRIEND_AVATAR_FILENAME.equals(friend.avatar_filename);
    }

    private ChatBubbleUiHelper()
    {
    }

    static void apply_chat_screen_background(final View view)
    {
        if (view != null)
        {
            view.setBackgroundColor(ContextCompat.getColor(view.getContext(), R.color.tg_chat_screen_bg));
        }
    }

    static GradientDrawable create_incoming_bubble_drawable(final Context context)
    {
        return create_bubble_drawable(context, false);
    }

    static GradientDrawable create_outgoing_bubble_drawable(final Context context)
    {
        return create_bubble_drawable(context, true);
    }

    private static GradientDrawable create_bubble_drawable(final Context context, final boolean outgoing)
    {
        final float radius = context.getResources().getDimension(R.dimen.tg_bubble_radius);
        final float tail = context.getResources().getDimension(R.dimen.tg_bubble_tail_radius);
        final GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        if (outgoing)
        {
            shape.setCornerRadii(new float[]{radius, radius, radius, radius, tail, tail, radius, radius});
            shape.setColor(ContextCompat.getColor(context, R.color.tg_bubble_outgoing));
        }
        else
        {
            shape.setCornerRadii(new float[]{radius, radius, radius, radius, radius, radius, tail, tail});
            shape.setColor(ContextCompat.getColor(context, R.color.tg_bubble_incoming));
            final int stroke = ContextCompat.getColor(context, R.color.tg_bubble_incoming_stroke);
            if (Color.alpha(stroke) > 0 && stroke != ContextCompat.getColor(context, R.color.tg_bubble_incoming))
            {
                shape.setStroke((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f,
                        context.getResources().getDisplayMetrics()), stroke);
            }
        }
        return shape;
    }

    static void apply_incoming_bubble(final ViewGroup container)
    {
        if (container == null)
        {
            return;
        }
        final Context context = container.getContext();
        container.setBackground(create_incoming_bubble_drawable(context));
        final int padH = (int) context.getResources().getDimension(R.dimen.tg_bubble_padding_h);
        final int padV = (int) context.getResources().getDimension(R.dimen.tg_bubble_padding_v);
        container.setPadding(padH, padV, padH, padV);
    }

    static void apply_outgoing_bubble(final ViewGroup container)
    {
        if (container == null)
        {
            return;
        }
        final Context context = container.getContext();
        container.setBackground(create_outgoing_bubble_drawable(context));
        final int padH = (int) context.getResources().getDimension(R.dimen.tg_bubble_padding_h);
        final int padV = (int) context.getResources().getDimension(R.dimen.tg_bubble_padding_v);
        container.setPadding(padH, padV, padH, padV);
    }

    static void apply_message_text_style(final EmojiTextViewLinks textView, final boolean outgoing)
    {
        if (textView == null)
        {
            return;
        }
        final Context context = textView.getContext();
        final int textColor = ContextCompat.getColor(context, R.color.tg_bubble_text);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.tg_bubble_text_size));
        textView.setTextColor(textColor);
        force_spannable_text_color(textView, textColor);
        final int linkColor = ContextCompat.getColor(context, R.color.tg_bubble_link);
        textView.setMentionModeColor(linkColor);
        textView.setHashtagModeColor(linkColor);
        textView.setUrlModeColor(linkColor);
        textView.setPhoneModeColor(linkColor);
        textView.setEmailModeColor(linkColor);
        textView.setCustomModeColor(linkColor);
        textView.setLinkTextColor(linkColor);
    }

    private static void force_spannable_text_color(final TextView textView, final int textColor)
    {
        final CharSequence current = textView.getText();
        if (current instanceof Spannable)
        {
            final Spannable span = (Spannable) current;
            span.setSpan(new ForegroundColorSpan(textColor), 0, span.length(),
                    Spannable.SPAN_INCLUSIVE_INCLUSIVE);
        }
    }

    static int peer_name_color(final Context context, final String peerPubkey)
    {
        if (peerPubkey == null || peerPubkey.compareTo("-1") == 0)
        {
            return context.getResources().getColor(R.color.tg_chat_preview);
        }
        return ChatColors.get_shade(
                ChatColors.PeerAvatarColors[hash_to_bucket(peerPubkey, ChatColors.get_size())],
                peerPubkey);
    }

    static void apply_peer_name_style(final TextView peerNameView, final String peerPubkey)
    {
        if (peerNameView == null)
        {
            return;
        }
        final Context context = peerNameView.getContext();
        peerNameView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.tg_bubble_peer_name_size));
        peerNameView.setTypeface(peerNameView.getTypeface(), android.graphics.Typeface.BOLD);
        peerNameView.setTextColor(peer_name_color(context, peerPubkey));
        peerNameView.setMaxLines(1);
        peerNameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    static void bind_bubble_time(final TextView bubbleTime, final TextView externalTime,
                                 final String timeText, final boolean outgoing)
    {
        if (bubbleTime != null)
        {
            bubbleTime.setVisibility(View.VISIBLE);
            bubbleTime.setText(timeText);
            final Context context = bubbleTime.getContext();
            bubbleTime.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    context.getResources().getDimension(R.dimen.tg_bubble_time_size));
            bubbleTime.setTextColor(ContextCompat.getColor(context,
                    outgoing ? R.color.tg_bubble_time_outgoing : R.color.tg_bubble_time_incoming));
            bubbleTime.setPadding((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f,
                    context.getResources().getDisplayMetrics()), 0, 0,
                    (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2f,
                            context.getResources().getDisplayMetrics()));
            bubbleTime.setGravity(Gravity.BOTTOM);
        }
        if (externalTime != null)
        {
            externalTime.setVisibility(View.GONE);
        }
    }

    static TextView find_bubble_time(final View itemView)
    {
        if (itemView == null)
        {
            return null;
        }
        return itemView.findViewById(R.id.bubble_time);
    }

    static void apply_chat_input_bar_theme(final View emojiBar)
    {
        if (emojiBar == null)
        {
            return;
        }
        emojiBar.setBackgroundColor(ContextCompat.getColor(emojiBar.getContext(), R.color.tg_chat_input_bar_bg));
    }

    static void apply_chat_input_field_theme(final TextView field)
    {
        if (field == null)
        {
            return;
        }
        final Context context = field.getContext();
        field.setTextColor(ContextCompat.getColor(context, R.color.tg_chat_input_text));
        field.setHintTextColor(ContextCompat.getColor(context, R.color.tg_chat_input_hint));
    }

    static void apply_chat_header_theme(final View headerRow, final TextView titleView)
    {
        apply_chat_header_theme(null, headerRow, headerRow, titleView, null, null, headerRow.getContext());
    }

    static void apply_chat_header_theme(@Nullable Toolbar toolbar, @Nullable View headerBar,
                                        @Nullable View profileTap, @Nullable TextView titleView,
                                        @Nullable ImageButton phoneIcon, @Nullable ImageButton videoIcon,
                                        Context context)
    {
        if (context == null)
        {
            return;
        }

        final int bg = ContextCompat.getColor(context, R.color.tg_chat_header_bg);
        final int titleColor = ContextCompat.getColor(context, R.color.tg_chat_header_title);
        final int iconColor = ContextCompat.getColor(context, R.color.tg_chat_header_icon);

        if (toolbar != null)
        {
            toolbar.setBackgroundColor(bg);
        }
        if (headerBar != null)
        {
            headerBar.setBackgroundColor(bg);
        }
        if ((profileTap != null) && (profileTap != headerBar))
        {
            profileTap.setBackgroundColor(Color.TRANSPARENT);
        }
        if (titleView != null)
        {
            titleView.setTextColor(titleColor);
        }

        set_chat_header_action_icon(phoneIcon, FontAwesome.Icon.faw_phone, iconColor, context);
        set_chat_header_action_icon(videoIcon, FontAwesome.Icon.faw_video, iconColor, context);
    }

    private static void set_chat_header_action_icon(@Nullable ImageButton button, FontAwesome.Icon icon,
                                                    int color, Context context)
    {
        if (button == null)
        {
            return;
        }

        button.setImageDrawable(new IconicsDrawable(context).icon(icon).color(color).sizeDp(80));
    }

    static void set_outgoing_row_gravity(final LinearLayout bubbleRow)
    {
        if (bubbleRow != null)
        {
            bubbleRow.setGravity(Gravity.END);
        }
    }

    static void bind_outgoing_delivery_status(final ImageView indicator, final Message message)
    {
        MessageStatusHelper.bindOutgoingIndicator(indicator, message);
    }

    static void bind_outgoing_delivery_status(final ImageView indicator, final GroupMessage message)
    {
        MessageStatusHelper.bindOutgoingIndicator(indicator, message);
    }

    static void hide_delivery_indicator(final ImageView indicator)
    {
        if (indicator != null)
        {
            indicator.setVisibility(View.GONE);
        }
    }

    static void apply_group_incoming_avatar(final CircleImageView avatar)
    {
        if (avatar == null)
        {
            return;
        }
        final Context context = avatar.getContext();
        final int size = (int) context.getResources().getDimension(R.dimen.tg_chat_avatar_in_bubble);
        final ViewGroup.LayoutParams params = avatar.getLayoutParams();
        params.width = size;
        params.height = size;
        avatar.setLayoutParams(params);
        avatar.setBorderWidth(0);
    }

    static void fill_friend_list_avatar(final Context context, final String peerPubkey, final String peerName,
                                        final CircleImageView avatar)
    {
        if (avatar == null || context == null)
        {
            return;
        }

        final int size = (int) context.getResources().getDimension(R.dimen.tg_chat_avatar_size);
        final ViewGroup.LayoutParams params = avatar.getLayoutParams();
        if (params != null)
        {
            params.width = size;
            params.height = size;
            avatar.setLayoutParams(params);
        }
        avatar.setBorderWidth(0);
        fill_peer_avatar(context, peerPubkey, peerName, avatar, size, 22, false);
    }

    static void fill_profile_peer_avatar(final Context context, final String peerPubkey, final String peerName,
                                         final CircleImageView avatar)
    {
        if (avatar == null || context == null)
        {
            return;
        }

        int size = Math.min(avatar.getWidth(), avatar.getHeight());
        if (size <= 0)
        {
            size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 120f,
                    context.getResources().getDisplayMetrics());
        }
        fill_peer_avatar(context, peerPubkey, peerName, avatar, size, 48, false);
    }

    static void fill_profile_peer_icon(final ImageView icon, final String peerPubkey, final String peerName)
    {
        if (icon == null)
        {
            return;
        }

        final Context context = icon.getContext();
        int size = Math.min(icon.getWidth(), icon.getHeight());
        if (size <= 0)
        {
            size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 120f,
                    context.getResources().getDisplayMetrics());
        }

        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GlideApp.with(context).clear(icon);

        final int bgColor = peer_name_color(context, peerPubkey);
        final int textColor = isColorDarkBrightness(bgColor) ? Color.WHITE : Color.BLACK;
        final int fontPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 48f,
                context.getResources().getDisplayMetrics());
        final TextDrawable placeholder = TextDrawable.builder().beginConfig().width(size).height(size)
                .textColor(textColor).bold().fontSize(fontPx).endConfig()
                .buildRound(peer_initials(peerName, peerPubkey), bgColor);
        placeholder.setBounds(0, 0, size, size);
        icon.setImageDrawable(placeholder);
        icon.invalidate();

        if (!VFS_ENCRYPT || peerPubkey == null || peerPubkey.compareTo("-1") == 0)
        {
            return;
        }

        try
        {
            final java.util.List<FriendList> friends = orma.selectFromFriendList().tox_public_key_stringEq(peerPubkey).toList();
            if (friends.isEmpty())
            {
                return;
            }

            final FriendList friend = friends.get(0);
            if (!shouldLoadVfsAvatar(friend))
            {
                return;
            }

            final info.guardianproject.iocipher.File avatarFile =
                    new info.guardianproject.iocipher.File(friend.avatar_pathname + "/" + friend.avatar_filename);
            if (avatarFile.length() <= 0)
            {
                return;
            }

            final RequestOptions glideOptions = new RequestOptions().fitCenter().circleCrop();
            GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE).signature(
                    new com.bumptech.glide.signature.StringSignatureZ(
                            "_profile_avatar_" + friend.avatar_pathname + "/" + friend.avatar_filename + "_" +
                                    friend.avatar_update_timestamp)).skipMemoryCache(false).apply(glideOptions).into(icon);
        }
        catch (Exception ignored)
        {
        }
    }

    static void fill_own_avatar_icon(final Context context, final CircleImageView avatar, final String peerPubkey,
                                     final String peerName)
    {
        if (avatar == null || context == null)
        {
            return;
        }

        final int size = (int) context.getResources().getDimension(R.dimen.tg_chat_avatar_size);
        final ViewGroup.LayoutParams params = avatar.getLayoutParams();
        if (params != null && params.width > 0 && params.height > 0)
        {
            fill_peer_avatar(context, peerPubkey, peerName, avatar, Math.min(params.width, params.height), 16, false);
        }
        else
        {
            fill_peer_avatar(context, peerPubkey, peerName, avatar, size, 16, false);
        }

        if (!VFS_ENCRYPT)
        {
            return;
        }

        try
        {
            final String fname = HelperGeneric.get_vfs_image_filename_own_avatar();
            if (fname == null)
            {
                return;
            }

            final info.guardianproject.iocipher.File avatarFile = new info.guardianproject.iocipher.File(fname);
            if (avatarFile.length() <= 0)
            {
                return;
            }

            GlideApp.with(context).clear(avatar);
            final RequestOptions glideOptions = new RequestOptions().fitCenter();
            GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE).skipMemoryCache(false)
                    .apply(glideOptions).into(avatar);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void fill_peer_avatar(final Context context, final String peerPubkey, final String peerName,
                                        final CircleImageView avatar, final int sizePx, final int fontSp,
                                        final boolean resizeLayout)
    {
        if (resizeLayout)
        {
            final ViewGroup.LayoutParams params = avatar.getLayoutParams();
            if (params != null)
            {
                params.width = sizePx;
                params.height = sizePx;
                avatar.setLayoutParams(params);
            }
            avatar.setBorderWidth(0);
        }

        GlideApp.with(context).clear(avatar);
        set_peer_placeholder(avatar, context, peerPubkey, peerName, sizePx, fontSp);

        if (!VFS_ENCRYPT || peerPubkey == null || peerPubkey.compareTo("-1") == 0)
        {
            return;
        }

        try
        {
            final java.util.List<FriendList> friends = orma.selectFromFriendList().tox_public_key_stringEq(peerPubkey).toList();
            if (friends.isEmpty())
            {
                return;
            }

            final FriendList friend = friends.get(0);
            if (!shouldLoadVfsAvatar(friend))
            {
                return;
            }

            final info.guardianproject.iocipher.File avatarFile =
                    new info.guardianproject.iocipher.File(friend.avatar_pathname + "/" + friend.avatar_filename);
            if (avatarFile.length() <= 0)
            {
                return;
            }

            final RequestOptions glideOptions = new RequestOptions().fitCenter();
            GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE).signature(
                    new com.bumptech.glide.signature.StringSignatureZ(
                            "_avatar_" + friend.avatar_pathname + "/" + friend.avatar_filename + "_" +
                                    friend.avatar_update_timestamp)).skipMemoryCache(false).apply(glideOptions).into(avatar);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void set_peer_placeholder(final CircleImageView avatar, final Context context,
                                             final String peerPubkey, final String peerName, final int sizePx,
                                             final int fontSp)
    {
        final int bgColor = peer_name_color(context, peerPubkey);
        final int textColor = isColorDarkBrightness(bgColor) ? Color.WHITE : Color.BLACK;
        final int fontPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSp,
                context.getResources().getDisplayMetrics());
        final TextDrawable placeholder = TextDrawable.builder().beginConfig().width(sizePx).height(sizePx)
                .textColor(textColor).bold().fontSize(fontPx).endConfig()
                .buildRound(peer_initials(peerName, peerPubkey), bgColor);
        placeholder.setBounds(0, 0, sizePx, sizePx);
        avatar.setImageDrawable(placeholder);
        avatar.invalidate();
    }

    static void fill_group_peer_avatar(final Context context, final String peerPubkey, final String peerName,
                                       final CircleImageView avatar)
    {
        if (avatar == null || context == null)
        {
            return;
        }

        apply_group_incoming_avatar(avatar);
        final int size = (int) context.getResources().getDimension(R.dimen.tg_chat_avatar_in_bubble);
        fill_peer_avatar(context, peerPubkey, peerName, avatar, size, 20, false);
    }

    static void fill_drawer_peer_icon(final ImageView icon, final String peerPubkey, final String peerName)
    {
        if (icon == null)
        {
            return;
        }

        final Context context = icon.getContext();
        final int size = (int) dp2px(36);
        final ViewGroup.LayoutParams params = icon.getLayoutParams();
        if (params != null)
        {
            params.width = size;
            params.height = size;
            icon.setLayoutParams(params);
        }
        icon.setPadding(0, 0, 0, 0);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GlideApp.with(context).clear(icon);

        final int bgColor = peer_name_color(context, peerPubkey);
        final int textColor = isColorDarkBrightness(bgColor) ? Color.WHITE : Color.BLACK;
        final int fontPx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                context.getResources().getDisplayMetrics());
        final TextDrawable placeholder = TextDrawable.builder().beginConfig().width(size).height(size)
                .textColor(textColor).bold().fontSize(fontPx).endConfig()
                .buildRound(peer_initials(peerName, peerPubkey), bgColor);
        placeholder.setBounds(0, 0, size, size);
        icon.setImageDrawable(placeholder);
        icon.invalidate();

        if (!VFS_ENCRYPT || peerPubkey == null || peerPubkey.compareTo("-1") == 0)
        {
            return;
        }

        try
        {
            final java.util.List<FriendList> friends = orma.selectFromFriendList().tox_public_key_stringEq(peerPubkey).toList();
            if (friends.isEmpty())
            {
                return;
            }

            final FriendList friend = friends.get(0);
            if (!shouldLoadVfsAvatar(friend))
            {
                return;
            }

            final info.guardianproject.iocipher.File avatarFile =
                    new info.guardianproject.iocipher.File(friend.avatar_pathname + "/" + friend.avatar_filename);
            if (avatarFile.length() <= 0)
            {
                return;
            }

            final RequestOptions glideOptions = new RequestOptions().fitCenter().circleCrop();
            GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE).signature(
                    new com.bumptech.glide.signature.StringSignatureZ(
                            "_drawer_avatar_" + friend.avatar_pathname + "/" + friend.avatar_filename + "_" +
                                    friend.avatar_update_timestamp)).skipMemoryCache(false).apply(glideOptions).into(icon);
        }
        catch (Exception ignored)
        {
        }
    }

    private static String peer_initials(final String peerName, final String peerPubkey)
    {
        if (peerName != null)
        {
            final String trimmed = peerName.trim();
            if (trimmed.length() > 0 && !trimmed.equals("-1") && !trimmed.equalsIgnoreCase("Unknown"))
            {
                final int codePoint = trimmed.codePointAt(0);
                if (Character.isLetterOrDigit(codePoint))
                {
                    return new String(Character.toChars(codePoint)).toUpperCase(java.util.Locale.ROOT);
                }
            }
        }

        if (peerPubkey != null && peerPubkey.length() >= 2)
        {
            return peerPubkey.substring(peerPubkey.length() - 2).toUpperCase(java.util.Locale.ROOT);
        }

        return "?";
    }
}

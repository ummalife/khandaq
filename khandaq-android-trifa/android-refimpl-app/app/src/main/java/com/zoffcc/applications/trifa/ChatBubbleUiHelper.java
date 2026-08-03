package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
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
import com.vanniktech.emoji.EmojiUtils;
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import de.hdodenhof.circleimageview.CircleImageView;

import java.text.BreakIterator;

import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.hash_to_bucket;
import static com.zoffcc.applications.trifa.HelperGeneric.isColorDarkBrightness;
import static com.zoffcc.applications.trifa.HelperGeneric.normalize_chat_input_text;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FRIEND_AVATAR_FILENAME;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_toxid;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_EMOJI_ONLY_EMOJI_SIZE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_EMOJI_SIZE;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

final class ChatBubbleUiHelper
{
    /** Telegram-style sticker messages: 1–3 emoji only, no bubble background. */
    private static final int STICKER_EMOJI_MAX_COUNT = 3;
    /**
     * KHANDAQ (#F5): a genuine friend avatar is loadable whenever we have a saved VFS path+file.
     * This USED to also reject FRIEND_AVATAR_FILENAME to hide auto-generated identicons — but
     * identicons are now disabled (Identicon.create_avatar_identicon_for_pubkey is a no-op) and
     * EVERY real received avatar is saved under exactly FRIEND_AVATAR_FILENAME (set_friend_avatar).
     * So that check suppressed exactly the real avatars → friends' photos never showed (placeholder
     * fallback). Drop it: a non-null pathname+filename now means a genuine saved avatar.
     */
    static boolean shouldLoadVfsAvatar(final FriendList friend)
    {
        return friend != null && friend.avatar_pathname != null && friend.avatar_filename != null;
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

    /** File/image/video bubbles: theme background, no thick stroke; edge-to-edge for photos/videos. */
    static void apply_file_message_bubble(final ViewGroup container, final boolean outgoing,
                                            final boolean edgeToEdgeMedia)
    {
        if (container == null)
        {
            return;
        }

        final Context context = container.getContext();

        if (edgeToEdgeMedia)
        {
            container.setBackground(null);
            container.setPadding(0, 0, 0, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            {
                container.setClipToOutline(false);
                container.setOutlineProvider(null);
            }
            // KHANDAQ (#196): right-align outgoing photos/videos like text (drop own-avatar column)
            if (outgoing) { align_outgoing_media_bubble(container, true); }
            return;
        }

        // voice/file card — restore the default left layout in case this holder was recycled from a photo
        if (outgoing) { align_outgoing_media_bubble(container, false); }
        apply_compact_file_bubble(container, outgoing);
    }

    // KHANDAQ (#196): edge-to-edge outgoing media (photos/videos) hugs the RIGHT edge like text
    // bubbles, with no own-avatar column. rightAlign=false restores the original left/weighted
    // layout so a recycled holder that later shows voice/file is not stuck in the photo arrangement.
    private static void align_outgoing_media_bubble(final ViewGroup bubble, final boolean rightAlign)
    {
        try
        {
            final int m20 = (int) (bubble.getContext().getResources().getDisplayMetrics().density * 20);
            if (bubble.getParent() instanceof LinearLayout)
            {
                final LinearLayout row = (LinearLayout) bubble.getParent();
                row.setGravity(rightAlign ? (Gravity.END | Gravity.CENTER_VERTICAL)
                                          : (Gravity.START | Gravity.TOP));
                final View av = row.findViewById(R.id.img_avatar);
                if (av != null) { av.setVisibility(rightAlign ? View.GONE : View.VISIBLE); }
                final View cr = row.findViewById(R.id.img_corner);
                if (cr != null) { cr.setVisibility(rightAlign ? View.GONE : View.VISIBLE); }
            }
            final ViewGroup.LayoutParams lp = bubble.getLayoutParams();
            if (lp instanceof LinearLayout.LayoutParams)
            {
                final LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
                if (rightAlign)
                {
                    llp.weight = 0f;
                    llp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    llp.leftMargin = 0;
                    llp.rightMargin = m20;
                }
                else
                {
                    llp.weight = 6f;
                    llp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    llp.leftMargin = m20;
                    llp.rightMargin = 0;
                }
                bubble.setLayoutParams(llp);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    /** Compact document/file card — subtle rounded background, minimal padding. */
    static void apply_compact_file_bubble(final ViewGroup container, final boolean outgoing)
    {
        if (container == null)
        {
            return;
        }

        container.setBackground(null);
        container.setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            container.setClipToOutline(false);
            container.setOutlineProvider(null);
        }
    }

    static void style_media_preview(final View preview)
    {
        if (preview == null)
        {
            return;
        }
        preview.setElevation(0f);
        preview.setBackgroundColor(Color.TRANSPARENT);
        preview.setPadding(0, 0, 0, 0);
        if (preview instanceof ImageView)
        {
            ((ImageView) preview).setScaleType(ImageView.ScaleType.CENTER_CROP);
            ((ImageView) preview).setAdjustViewBounds(true);
        }

        final Context context = preview.getContext();
        final int radius = media_corner_radius_px(context);
        View clipTarget = preview;
        if (preview.getParent() instanceof ViewGroup)
        {
            final ViewGroup parent = (ViewGroup) preview.getParent();
            if (parent.findViewById(R.id.ft_media_info_bar) != null)
            {
                clipTarget = parent;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            final View target = clipTarget;
            target.setClipToOutline(true);
            target.setOutlineProvider(new ViewOutlineProvider()
            {
                @Override
                public void getOutline(final View view, final Outline outline)
                {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
        }
    }

    static int media_corner_radius_px(final Context context)
    {
        return (int) context.getResources().getDimension(R.dimen.tg_bubble_radius);
    }

    // KHANDAQ (Figma): photo/video bubbles hug the media at a sane size instead of filling the row
    // full-width with letterbox. Glide loads with .override(these) + FitCenter so the bitmap is the
    // final fitted size (≤ max), and the preview view wraps to it.
    // KHANDAQ: bumped up — photos looked too small (capped ~62% of screen width). Bigger, closer
    // to Telegram (~70-75% width) while still leaving the bubble margins.
    static final int MEDIA_THUMB_MAX_W_DP = 300;
    static final int MEDIA_THUMB_MAX_H_DP = 400;

    static int media_thumb_max_w_px(final Context context)
    {
        return (int) (context.getResources().getDisplayMetrics().density * MEDIA_THUMB_MAX_W_DP);
    }

    static int media_thumb_max_h_px(final Context context)
    {
        return (int) (context.getResources().getDisplayMetrics().density * MEDIA_THUMB_MAX_H_DP);
    }

    /** Make a media preview hug its bitmap (wrap_content + FitCenter), not the full row width. */
    static void apply_media_thumb_wrap(final View preview)
    {
        if (preview == null)
        {
            return;
        }
        final ViewGroup.LayoutParams lp = preview.getLayoutParams();
        if (lp != null)
        {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            preview.setLayoutParams(lp);
        }
        if (preview instanceof ImageView)
        {
            ((ImageView) preview).setScaleType(ImageView.ScaleType.FIT_CENTER);
            ((ImageView) preview).setAdjustViewBounds(true);
        }
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

    static boolean is_sticker_style_emoji_message(@Nullable final CharSequence rawText)
    {
        if (rawText == null)
        {
            return false;
        }
        final String normalized = normalize_chat_input_text(rawText.toString());
        if (normalized.isEmpty() || !EmojiUtils.isOnlyEmojis(normalized))
        {
            return false;
        }
        final int emojiCount = count_grapheme_clusters(normalized);
        return emojiCount >= 1 && emojiCount <= STICKER_EMOJI_MAX_COUNT;
    }

    private static int count_grapheme_clusters(final String text)
    {
        if (text == null || text.isEmpty())
        {
            return 0;
        }
        final BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        int count = 0;
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next())
        {
            if (end > start)
            {
                count++;
            }
        }
        return count;
    }

    static void configure_message_emoji_size(final EmojiTextViewLinks textView, final CharSequence displayText,
                                             final int fontSizePrefIndex, final boolean forceLargeEmojiOnly)
    {
        if (textView == null)
        {
            return;
        }
        final int emojiSizeDp = forceLargeEmojiOnly || is_sticker_style_emoji_message(displayText)
                ? MESSAGE_EMOJI_ONLY_EMOJI_SIZE[fontSizePrefIndex]
                : MESSAGE_EMOJI_SIZE[fontSizePrefIndex];
        textView.setEmojiSize((int) dp2px(emojiSizeDp));
    }

    /**
     * Apply bubble background for text messages, or transparent sticker layout for 1–3 emoji-only messages.
     */
    static void bind_text_message_bubble(final ViewGroup container, final EmojiTextViewLinks textView,
                                         final boolean outgoing, final CharSequence displayText,
                                         final int fontSizePrefIndex, final boolean hasReplyQuote,
                                         final boolean forceStandardBubble)
    {
        if (container == null || textView == null)
        {
            return;
        }
        final boolean sticker = !forceStandardBubble && !hasReplyQuote
                && is_sticker_style_emoji_message(displayText);
        if (sticker)
        {
            apply_emoji_sticker_message(container, textView, fontSizePrefIndex);
        }
        else
        {
            apply_standard_text_message_bubble(container, textView, outgoing, displayText, fontSizePrefIndex);
        }
    }

    private static void apply_emoji_sticker_message(final ViewGroup container, final EmojiTextViewLinks textView,
                                                    final int fontSizePrefIndex)
    {
        container.setBackground(null);
        container.setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            container.setClipToOutline(false);
        }

        textView.setBackground(null);
        textView.setIncludeFontPadding(false);
        textView.setPadding(0, 0, 0, 0);
        configure_message_emoji_size(textView, null, fontSizePrefIndex, true);

        if (textView.getParent() instanceof ChatBubbleMetaLayout)
        {
            ((ChatBubbleMetaLayout) textView.getParent()).setMetaVisible(false);
        }

        final ViewGroup.LayoutParams lp = textView.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams)
        {
            final LinearLayout.LayoutParams marginParams = (LinearLayout.LayoutParams) lp;
            marginParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            marginParams.weight = 0f;
            textView.setLayoutParams(marginParams);
        }
        else if (lp != null)
        {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            textView.setLayoutParams(lp);
        }
    }

    private static void apply_standard_text_message_bubble(final ViewGroup container,
                                                           final EmojiTextViewLinks textView,
                                                           final boolean outgoing,
                                                           final CharSequence displayText,
                                                           final int fontSizePrefIndex)
    {
        if (outgoing)
        {
            apply_outgoing_bubble(container);
        }
        else
        {
            apply_incoming_bubble(container);
        }
        apply_message_text_style(textView, outgoing);
        configure_message_emoji_size(textView, displayText, fontSizePrefIndex, false);

        if (textView.getParent() instanceof ChatBubbleMetaLayout)
        {
            textView.setPadding(textView.getPaddingLeft(), textView.getPaddingTop(),
                    textView.getPaddingRight(), 0);
            final ChatBubbleMetaLayout metaLayout = (ChatBubbleMetaLayout) textView.getParent();
            metaLayout.setMetaVisible(true);
            metaLayout.scheduleMetaLayout();
            return;
        }

        final ViewGroup.LayoutParams lp = textView.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams)
        {
            final LinearLayout.LayoutParams marginParams = (LinearLayout.LayoutParams) lp;
            if (marginParams.width != ViewGroup.LayoutParams.WRAP_CONTENT)
            {
                marginParams.width = 0;
                marginParams.weight = 1f;
            }
            final Context context = textView.getContext();
            marginParams.bottomMargin = 0;
            textView.setLayoutParams(marginParams);
            final int timePadBottom = (int) context.getResources().getDimension(R.dimen.tg_bubble_time_pad_bottom);
            textView.setPadding(textView.getPaddingLeft(), textView.getPaddingTop(),
                    textView.getPaddingRight(), timePadBottom);
        }
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
        final ViewGroup.LayoutParams lp = peerNameView.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams)
        {
            final ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) lp;
            marginParams.bottomMargin = (int) context.getResources().getDimension(R.dimen.tg_peer_name_bubble_bottom);
            peerNameView.setLayoutParams(marginParams);
        }
    }

    static void bind_bubble_time(final TextView bubbleTime, final TextView externalTime,
                                 final String timeText, final boolean outgoing)
    {
        if (bubbleTime != null)
        {
            bubbleTime.setVisibility(View.VISIBLE);
            bubbleTime.setText(timeText);
            apply_bubble_time_style(bubbleTime, outgoing, true);
            schedule_meta_layout_if_needed(bubbleTime);
            if (externalTime != null)
            {
                externalTime.setVisibility(View.GONE);
            }
            return;
        }

        if (externalTime != null && timeText != null && !timeText.isEmpty())
        {
            externalTime.setVisibility(View.VISIBLE);
            externalTime.setText(timeText);
            apply_bubble_time_style(externalTime, outgoing, false);
        }
        else if (externalTime != null)
        {
            externalTime.setVisibility(View.GONE);
        }
    }

    // KHANDAQ (Android edited-marker parity): show a Telegram-style «изменено» before the timestamp when
    // the message was edited (own + incoming). It shares the grey/small bubble-time style, so
    // "изменено 19:07" reads as a system note. `edited` applies to text messages only (files can't be edited).
    static void bind_bubble_time(final TextView bubbleTime, final TextView externalTime,
                                 final String timeText, final boolean outgoing, final boolean edited)
    {
        String t = timeText;
        if (edited && timeText != null)
        {
            final TextView anchor = (bubbleTime != null) ? bubbleTime : externalTime;
            if (anchor != null)
            {
                t = anchor.getContext().getString(R.string.message_edited_marker) + " " + timeText;
            }
        }
        bind_bubble_time(bubbleTime, externalTime, t, outgoing);
    }

    private static void apply_bubble_time_style(final TextView timeView, final boolean outgoing,
                                                final boolean inlineInBubble)
    {
        final Context context = timeView.getContext();
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                context.getResources().getDimension(R.dimen.tg_bubble_time_size));
        timeView.setTextColor(ContextCompat.getColor(context,
                outgoing ? R.color.tg_bubble_time_outgoing : R.color.tg_bubble_time_incoming));
        if (inlineInBubble)
        {
            final int timePadStart = (int) context.getResources().getDimension(R.dimen.tg_bubble_time_pad_start);
            final int timePadBottom = (int) context.getResources().getDimension(R.dimen.tg_bubble_time_pad_bottom);
            timeView.setPadding(timePadStart, 0, 0, timePadBottom);
            timeView.setGravity(Gravity.BOTTOM);
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
        field.setBackgroundResource(android.R.color.transparent);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
        {
            field.setBackgroundTintList(null);
            field.setBackgroundTintMode(null);
        }
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

    private static void schedule_meta_layout_if_needed(final View view)
    {
        if (view == null)
        {
            return;
        }
        View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
        while (parent != null)
        {
            if (parent instanceof ChatBubbleMetaLayout)
            {
                ((ChatBubbleMetaLayout) parent).scheduleMetaLayout();
                return;
            }
            parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
        }
    }

    static void bind_outgoing_delivery_status(final ImageView indicator, final Message message)
    {
        MessageStatusHelper.bindOutgoingIndicator(indicator, message);
        bind_delivery_retry_click(indicator, message, null);
        schedule_meta_layout_if_needed(indicator);
    }

    static void bind_outgoing_delivery_status(final ImageView indicator, final GroupMessage message)
    {
        MessageStatusHelper.bindOutgoingIndicator(indicator, message);
        bind_delivery_retry_click(indicator, null, message);
        schedule_meta_layout_if_needed(indicator);
    }

    private static void bind_delivery_retry_click(final ImageView indicator, final Message directMessage,
                                                  final GroupMessage groupMessage)
    {
        if (indicator == null)
        {
            return;
        }

        final boolean canRetry = (directMessage != null && MessageDeliveryRetryHelper.canRetryDirect(directMessage))
                || (groupMessage != null && MessageDeliveryRetryHelper.canRetryGroup(groupMessage));

        if (!canRetry)
        {
            indicator.setOnClickListener(null);
            indicator.setClickable(false);
            return;
        }

        indicator.setClickable(true);
        indicator.setOnClickListener(v ->
        {
            final android.content.Context ctx = v.getContext();
            if (directMessage != null)
            {
                MessageDeliveryRetryHelper.retryDirectMessage(ctx, directMessage);
            }
            else if (groupMessage != null)
            {
                MessageDeliveryRetryHelper.retryGroupMessage(ctx, groupMessage);
            }
        });
    }

    // KHANDAQ: delivery indicator for outgoing file/media bubbles. The holder passes an explicit
    // status (clock=queued/waiting, single check=transferring, double check=fully sent/delivered).
    static void bind_outgoing_file_status(final ImageView indicator,
                                          final MessageStatusHelper.OutgoingStatus status)
    {
        if (indicator == null)
        {
            return;
        }
        MessageStatusHelper.bindOutgoingIndicatorDirect(indicator, status);
        indicator.setOnClickListener(null);
        indicator.setClickable(false);
    }

    static void hide_delivery_indicator(final ImageView indicator)
    {
        if (indicator != null)
        {
            indicator.setVisibility(View.GONE);
            schedule_meta_layout_if_needed(indicator);
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

    // KHANDAQ (#3): the chat-header avatar must stay at the toolbar's 38dp. Reusing
    // fill_friend_list_avatar there blew it up to tg_chat_avatar_size (54dp — sized for the 72dp
    // chat-LIST rows), so it overflowed the wrap_content toolbar, overlapping the status bar and
    // the peer name.
    static void fill_chat_header_avatar(final Context context, final String peerPubkey, final String peerName,
                                        final CircleImageView avatar)
    {
        if (avatar == null || context == null)
        {
            return;
        }

        final int size = (int) (context.getResources().getDisplayMetrics().density * 38);
        final ViewGroup.LayoutParams params = avatar.getLayoutParams();
        if (params != null)
        {
            params.width = size;
            params.height = size;
            avatar.setLayoutParams(params);
        }
        avatar.setBorderWidth(0);
        fill_peer_avatar(context, peerPubkey, peerName, avatar, size, 16, false);
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
            // KHANDAQ: own avatar path is constant — key the cache on length+mtime so replacing the
            // photo actually updates (otherwise the RESOURCE disk cache keeps serving the old image).
            final String sig = fname + "_" + avatarFile.length() + "_" + avatarFile.lastModified();
            GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .signature(new com.bumptech.glide.signature.StringSignatureZ(sig)).skipMemoryCache(false)
                    .apply(glideOptions).into(avatar);
        }
        catch (Exception ignored)
        {
        }
    }

    // KHANDAQ (perf): fill_peer_avatar ran a selectFromFriendList query + an encrypted-VFS file .length()
    // stat on the UI thread on EVERY row bind during scroll. Memoize the resolved avatar metadata per
    // pubkey for a short window so a scroll burst does the DB+VFS work once per peer instead of once per
    // bind. Kept fully synchronous (no background thread) so there is no view-recycling race; Glide's own
    // memory cache makes the repeated load cheap. Self-heals within the TTL when an avatar changes.
    private static final class AvatarMeta
    {
        final boolean loadable;
        final String path;
        final String filename;
        final long updateTs;

        AvatarMeta(final boolean l, final String p, final String f, final long t)
        {
            loadable = l;
            path = p;
            filename = f;
            updateTs = t;
        }
    }

    private static final AvatarMeta AVATAR_META_NONE = new AvatarMeta(false, null, null, 0L);
    private static final long AVATAR_META_CACHE_TTL_MS = 3000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, AvatarMeta> avatar_meta_cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> avatar_meta_cache_ts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static AvatarMeta resolve_avatar_meta(final String peerPubkey)
    {
        final Long ts = avatar_meta_cache_ts.get(peerPubkey);
        if (ts != null && (System.currentTimeMillis() - ts) < AVATAR_META_CACHE_TTL_MS)
        {
            final AvatarMeta cached = avatar_meta_cache.get(peerPubkey);
            if (cached != null)
            {
                return cached;
            }
        }
        AvatarMeta v = AVATAR_META_NONE;
        try
        {
            final java.util.List<FriendList> friends =
                    orma.selectFromFriendList().tox_public_key_stringEq(peerPubkey).toList();
            if (!friends.isEmpty())
            {
                final FriendList friend = friends.get(0);
                if (shouldLoadVfsAvatar(friend))
                {
                    final info.guardianproject.iocipher.File avatarFile =
                            new info.guardianproject.iocipher.File(
                                    friend.avatar_pathname + "/" + friend.avatar_filename);
                    if (avatarFile.length() > 0)
                    {
                        v = new AvatarMeta(true, friend.avatar_pathname, friend.avatar_filename,
                                friend.avatar_update_timestamp);
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
        avatar_meta_cache.put(peerPubkey, v);
        avatar_meta_cache_ts.put(peerPubkey, System.currentTimeMillis());
        return v;
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
            final AvatarMeta meta = resolve_avatar_meta(peerPubkey);
            if (!meta.loadable)
            {
                return;
            }

            final info.guardianproject.iocipher.File avatarFile =
                    new info.guardianproject.iocipher.File(meta.path + "/" + meta.filename);

            final RequestOptions glideOptions = new RequestOptions().fitCenter();
            GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE).signature(
                    new com.bumptech.glide.signature.StringSignatureZ(
                            "_avatar_" + meta.path + "/" + meta.filename + "_" +
                                    meta.updateTs)).skipMemoryCache(false).apply(glideOptions).into(avatar);
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
        fill_drawer_peer_icon(icon, peerPubkey, peerName, false);
    }

    static void fill_drawer_peer_icon(final ImageView icon, final String peerPubkey, final String peerName,
                                      final boolean isSelf)
    {
        if (icon == null)
        {
            return;
        }

        String avatarLookupPubkey = peerPubkey;
        if (isSelf)
        {
            try
            {
                if ((global_my_toxid != null) && (global_my_toxid.length() >= (TOX_PUBLIC_KEY_SIZE * 2)))
                {
                    avatarLookupPubkey = global_my_toxid.substring(0, (TOX_PUBLIC_KEY_SIZE * 2));
                }
            }
            catch (Exception ignored)
            {
            }
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

        if (!VFS_ENCRYPT || avatarLookupPubkey == null || avatarLookupPubkey.compareTo("-1") == 0)
        {
            return;
        }

        if (isSelf)
        {
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

                final RequestOptions glideOptions = new RequestOptions().fitCenter().circleCrop();
                GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE).skipMemoryCache(
                        false).apply(glideOptions).into(icon);
            }
            catch (Exception ignored)
            {
            }
            return;
        }

        try
        {
            final java.util.List<FriendList> friends = orma.selectFromFriendList().tox_public_key_stringEq(
                    avatarLookupPubkey).toList();
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

    interface ReplyQuoteClickListener
    {
        void onReplyQuoteClicked(MessageReplyHelper.ReplyMeta replyMeta);
    }

    static void bind_reply_quote(final View bubbleRoot, @Nullable final MessageReplyHelper.ReplyMeta replyMeta,
                                 @Nullable final ReplyQuoteClickListener clickListener)
    {
        if (bubbleRoot == null)
        {
            return;
        }

        final View quoteBlock = bubbleRoot.findViewById(R.id.chat_reply_quote_block);
        final View quoteBar = bubbleRoot.findViewById(R.id.chat_reply_quote_bar);
        final TextView quoteName = bubbleRoot.findViewById(R.id.chat_reply_quote_name);
        final TextView quoteText = bubbleRoot.findViewById(R.id.chat_reply_quote_text);
        if (quoteBlock == null || quoteName == null || quoteText == null)
        {
            return;
        }

        if (replyMeta == null || (replyMeta.previewText == null || replyMeta.previewText.trim().isEmpty()))
        {
            quoteBlock.setVisibility(View.GONE);
            quoteBlock.setOnClickListener(null);
            return;
        }

        final Context context = bubbleRoot.getContext();
        quoteBlock.setVisibility(View.VISIBLE);
        if (quoteBar != null)
        {
            quoteBar.setBackgroundColor(ContextCompat.getColor(context, R.color.tg_reply_quote_accent));
        }
        final String name = replyMeta.senderName == null || replyMeta.senderName.isEmpty()
                ? context.getString(R.string.chat_reply_unknown_sender) : replyMeta.senderName;
        quoteName.setText(name);
        quoteName.setTextColor(ContextCompat.getColor(context, R.color.tg_reply_quote_name));
        quoteText.setText(MessageReplyHelper.previewForDisplay(context, replyMeta.previewText, 160));
        quoteText.setTextColor(ContextCompat.getColor(context, R.color.tg_reply_quote_text));
        quoteBlock.setBackground(ContextCompat.getDrawable(context, R.drawable.bg_chat_reply_quote));
        if (clickListener != null && !replyMeta.legacyQuote)
        {
            quoteBlock.setOnClickListener(v -> clickListener.onReplyQuoteClicked(replyMeta));
        }
        else if (clickListener != null)
        {
            quoteBlock.setOnClickListener(v -> clickListener.onReplyQuoteClicked(replyMeta));
        }
        else
        {
            quoteBlock.setOnClickListener(null);
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

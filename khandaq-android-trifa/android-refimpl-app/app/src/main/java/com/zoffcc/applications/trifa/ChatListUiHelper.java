package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import static com.zoffcc.applications.trifa.HelperFiletransfer.display_name_from_message_text;
import static com.zoffcc.applications.trifa.HelperFiletransfer.guess_mime_type_from_filename;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isVoiceMessagePath;
import static com.zoffcc.applications.trifa.HelperGroup.format_group_list_status_subtitle;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_name__wrapper;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

final class ChatListUiHelper
{
    private static final int PREVIEW_MAX_CHARS = 120;

    private ChatListUiHelper()
    {
    }

    // KHANDAQ (#22): the unread badge was a DB COUNT query per chat row on every bind, re-run on
    // every scroll/refresh — a freeze source on older phones. Cache the count per chat for a short
    // window: a badge doesn't need sub-second freshness and the list re-queries within ~1s anyway.
    // invalidate_*() lets the read/new-message paths force an immediate refresh when needed.
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> unread_count_cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> unread_count_cache_ts =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNREAD_COUNT_CACHE_TTL_MS = 1000L;

    static int cached_friend_unread_count(final String friendPubkey)
    {
        if (friendPubkey == null)
        {
            return 0;
        }
        final String key = "f:" + friendPubkey;
        final Integer cached = peek_unread_cache(key);
        if (cached != null)
        {
            return cached;
        }
        int count = 0;
        try
        {
            count = TrifaToxService.orma.selectFromMessage().tox_friendpubkeyEq(friendPubkey).is_newEq(true).count();
        }
        catch (Exception ignored)
        {
        }
        put_unread_cache(key, count);
        return count;
    }

    static int cached_group_unread_count(final String groupIdentifier)
    {
        if (groupIdentifier == null)
        {
            return 0;
        }
        final String lower = groupIdentifier.toLowerCase(java.util.Locale.ROOT);
        final String key = "g:" + lower;
        final Integer cached = peek_unread_cache(key);
        if (cached != null)
        {
            return cached;
        }
        int count = 0;
        try
        {
            count = TrifaToxService.orma.selectFromGroupMessage().group_identifierEq(lower).is_newEq(true).count();
        }
        catch (Exception ignored)
        {
        }
        put_unread_cache(key, count);
        return count;
    }

    static void invalidate_unread_count_friend(final String friendPubkey)
    {
        if (friendPubkey != null)
        {
            unread_count_cache_ts.remove("f:" + friendPubkey);
        }
    }

    static void invalidate_unread_count_group(final String groupIdentifier)
    {
        if (groupIdentifier != null)
        {
            unread_count_cache_ts.remove("g:" + groupIdentifier.toLowerCase(java.util.Locale.ROOT));
        }
    }

    private static Integer peek_unread_cache(final String key)
    {
        final Long ts = unread_count_cache_ts.get(key);
        if (ts != null && (System.currentTimeMillis() - ts) < UNREAD_COUNT_CACHE_TTL_MS)
        {
            return unread_count_cache.get(key);
        }
        return null;
    }

    private static void put_unread_cache(final String key, final int count)
    {
        unread_count_cache.put(key, count);
        unread_count_cache_ts.put(key, System.currentTimeMillis());
    }

    static void bind_preview_text_color(final TextView previewView, final boolean is_draft)
    {
        if (previewView == null)
        {
            return;
        }

        previewView.setTextColor(previewView.getResources().getColor(
                is_draft ? R.color.tg_chat_draft : R.color.tg_chat_preview));
    }

    static void prepare_telegram_row(final View row, final TextView titleView, final TextView previewView,
                                     final TextView timeView, final ImageView notificationView,
                                     final ImageView statusIcon, final ImageView userStatusIcon,
                                     final ImageView relayIcon, final TextView ipAddrView)
    {
        if (row != null)
        {
            row.setBackgroundResource(R.drawable.tg_chat_item_ripple);
        }
        if (titleView != null)
        {
            titleView.setTextColor(titleView.getResources().getColor(R.color.tg_chat_title));
        }
        if (previewView != null)
        {
            previewView.setTextColor(previewView.getResources().getColor(R.color.tg_chat_preview));
        }
        if (timeView != null)
        {
            timeView.setTextColor(timeView.getResources().getColor(R.color.tg_chat_time));
        }
        if (notificationView != null)
        {
            notificationView.setVisibility(View.GONE);
        }
        if (statusIcon != null)
        {
            statusIcon.setVisibility(View.GONE);
        }
        if (userStatusIcon != null)
        {
            userStatusIcon.setVisibility(View.GONE);
        }
        if (relayIcon != null)
        {
            relayIcon.setVisibility(View.GONE);
        }
        if (ipAddrView != null)
        {
            ipAddrView.setVisibility(View.GONE);
        }
    }

    static void bind_unread_badge(final TextView unreadView, final int count)
    {
        if (unreadView == null)
        {
            return;
        }
        if (count > 0)
        {
            unreadView.setVisibility(View.VISIBLE);
            unreadView.setText(count > 99 ? "99+" : String.valueOf(count));
        }
        else
        {
            unreadView.setVisibility(View.INVISIBLE);
        }
    }

    static String format_chat_list_time(final Context context, final long timestamp_ms)
    {
        final long ts = normalize_message_timestamp_ms(timestamp_ms);
        if (ts <= 0L)
        {
            return "";
        }
        final Calendar now = Calendar.getInstance();
        final Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);

        final boolean same_day = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                                 && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        if (same_day)
        {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
        }

        final Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        final boolean is_yesterday = yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                                     && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        if (is_yesterday)
        {
            return context.getString(R.string.chat_list_time_yesterday);
        }

        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR))
        {
            return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(ts));
        }
        return new SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(new Date(ts));
    }

    /** Ignore epoch/zero and legacy second-based timestamps stored as ms. */
    static long normalize_message_timestamp_ms(long ts)
    {
        if (ts <= 0L)
        {
            return 0L;
        }
        if (ts < 10_000_000_000L)
        {
            ts *= 1000L;
        }
        if (ts < 946684800000L)
        {
            return 0L;
        }
        return ts;
    }

    static boolean friend_has_messages(final String friend_pubkey)
    {
        if (TextUtils.isEmpty(friend_pubkey))
        {
            return false;
        }
        try
        {
            return orma.selectFromMessage().tox_friendpubkeyEq(friend_pubkey).count() > 0;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static String friend_last_message_preview(final Context context, final String friend_pubkey)
    {
        try
        {
            final String draft = ChatDraftHelper.load_friend_draft(friend_pubkey);
            if (!TextUtils.isEmpty(draft))
            {
                return ChatDraftHelper.format_draft_preview(context, draft);
            }

            final List<Message> messages = orma.selectFromMessage().tox_friendpubkeyEq(friend_pubkey).
                    orderByRcvd_timestampDesc().limit(1).toList();
            if (messages.isEmpty())
            {
                return context.getString(R.string.chat_list_no_messages);
            }
            final Message message = messages.get(0);
            return format_message_preview(context, message.text, message.filename_fullpath, message.direction == 1);
        }
        catch (Exception ignored)
        {
            return context.getString(R.string.chat_list_no_messages);
        }
    }

    static long friend_last_message_timestamp_ms(final String friend_pubkey)
    {
        try
        {
            final List<Message> messages = orma.selectFromMessage().tox_friendpubkeyEq(friend_pubkey).
                    orderByRcvd_timestampDesc().limit(1).toList();
            if (messages.isEmpty())
            {
                return 0L;
            }
            final Message message = messages.get(0);
            return normalize_message_timestamp_ms(
                    Math.max(message.rcvd_timestamp_ms, message.sent_timestamp_ms) > 0L
                            ? Math.max(message.rcvd_timestamp_ms, message.sent_timestamp_ms)
                            : Math.max(message.rcvd_timestamp, message.sent_timestamp));
        }
        catch (Exception ignored)
        {
            return 0L;
        }
    }

    static String group_last_message_preview(final Context context, final String group_identifier)
    {
        try
        {
            final String draft = ChatDraftHelper.load_group_draft(group_identifier);
            if (!TextUtils.isEmpty(draft))
            {
                return ChatDraftHelper.format_draft_preview(context, draft);
            }

            final List<GroupMessage> messages = orma.selectFromGroupMessage().
                    group_identifierEq(group_identifier.toLowerCase()).
                    orderByRcvd_timestampDesc().limit(20).toList();
            if (messages.isEmpty())
            {
                return format_group_list_status_subtitle(context, group_identifier);
            }
            GroupMessage latest = messages.get(0);
            long latestTs = GroupMessageLayoutHelper.effectiveSortTimestampMs(latest);
            for (int i = 1; i < messages.size(); i++)
            {
                final GroupMessage candidate = messages.get(i);
                final long candidateTs = GroupMessageLayoutHelper.effectiveSortTimestampMs(candidate);
                if (candidateTs >= latestTs)
                {
                    latest = candidate;
                    latestTs = candidateTs;
                }
            }
            return format_group_message_preview(context, latest);
        }
        catch (Exception ignored)
        {
            return format_group_list_status_subtitle(context, group_identifier);
        }
    }

    static long group_last_message_timestamp_ms(final String group_identifier)
    {
        try
        {
            final List<GroupMessage> messages = orma.selectFromGroupMessage().
                    group_identifierEq(group_identifier.toLowerCase()).
                    orderByRcvd_timestampDesc().limit(20).toList();
            if (messages.isEmpty())
            {
                return 0L;
            }
            long latestTs = 0L;
            for (GroupMessage message : messages)
            {
                latestTs = Math.max(latestTs, GroupMessageLayoutHelper.effectiveSortTimestampMs(message));
            }
            return normalize_message_timestamp_ms(latestTs);
        }
        catch (Exception ignored)
        {
            return 0L;
        }
    }

    /** Strip reply/mention wire metadata and return user-visible preview text. */
    static String sanitize_preview_body(final String rawText)
    {
        if (TextUtils.isEmpty(rawText))
        {
            return "";
        }
        return GroupMentionHelper.notificationPreviewText(rawText);
    }

    static String truncate_preview(final String body)
    {
        if (TextUtils.isEmpty(body))
        {
            return "";
        }
        final String single = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (single.length() <= PREVIEW_MAX_CHARS)
        {
            return single;
        }
        return single.substring(0, PREVIEW_MAX_CHARS - 3) + "...";
    }

    private static String format_group_message_preview(final Context context, final GroupMessage message)
    {
        if (message == null)
        {
            return "";
        }

        final String displayText = GroupMessageLayoutHelper.displayTextForMessage(context, message);
        final String body = format_message_body(context, displayText, message.filename_fullpath);
        final boolean outgoing = message.direction == 1;

        if (outgoing)
        {
            return context.getString(R.string.chat_list_preview_you, body);
        }

        String sender = message.tox_group_peername;
        if (TextUtils.isEmpty(sender) || "-1".equals(sender))
        {
            sender = tox_group_peer_get_name__wrapper(message.group_identifier, message.tox_group_peer_pubkey);
        }
        if (TextUtils.isEmpty(sender) || "-1".equals(sender))
        {
            sender = HelperFriend.resolve_name_for_pubkey(message.tox_group_peer_pubkey, "");
        }
        if (TextUtils.isEmpty(sender))
        {
            return body;
        }
        return context.getString(R.string.chat_list_preview_sender, sender, body);
    }

    private static String format_message_preview(final Context context, final String text,
                                                 final String filename, final boolean outgoing)
    {
        final String body = format_message_body(context, text, filename);
        if (outgoing)
        {
            return context.getString(R.string.chat_list_preview_you, body);
        }
        return body;
    }

    private static String format_message_body(final Context context, final String text, final String filename)
    {
        String body = sanitize_preview_body(text);
        if (TextUtils.isEmpty(body))
        {
            if (!TextUtils.isEmpty(filename))
            {
                body = media_preview_label(context, filename, text);
            }
            else
            {
                body = context.getString(R.string.chat_list_preview_empty);
            }
        }
        else if (!TextUtils.isEmpty(filename)
                && (isVoiceMessagePath(filename) || isVoiceMessagePath(body)))
        {
            body = context.getString(R.string.voice_message_label);
        }
        else if (!TextUtils.isEmpty(filename) && body.startsWith("[KQ|"))
        {
            body = media_preview_label(context, filename, text);
        }

        return truncate_preview(body);
    }

    private static String media_preview_label(final Context context, final String filename, final String text)
    {
        if (isVoiceMessagePath(filename) || isVoiceMessagePath(text)
                || isVoiceMessagePath(display_name_from_message_text(text)))
        {
            return context.getString(R.string.voice_message_label);
        }

        final String mime = guess_mime_type_from_filename(
                !TextUtils.isEmpty(filename) ? filename : display_name_from_message_text(text));
        if (mime != null && mime.startsWith("image/"))
        {
            return context.getString(R.string.media_label_photo);
        }
        if (mime != null && mime.startsWith("video/"))
        {
            return context.getString(R.string.media_label_video);
        }
        if (mime != null && mime.startsWith("audio/"))
        {
            return context.getString(R.string.voice_message_label);
        }
        return context.getString(R.string.chat_list_preview_file);
    }
}

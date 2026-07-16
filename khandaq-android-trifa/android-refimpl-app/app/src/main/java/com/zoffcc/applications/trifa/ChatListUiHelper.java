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

    // KHANDAQ (perf): the chat-list preview + timestamp used to fire 2-3 uncached Message/GroupMessage
    // queries per row on every bind/scroll (friend_has_messages COUNT + two identical last-message
    // SELECTs). Collapse them into ONE query cached with the same short TTL as the unread badge above,
    // so a scroll burst reads the DB once per chat instead of once per bind. Freshness is bounded by
    // the TTL exactly like the unread count (both self-heal on the next list refresh).
    private static final long LAST_MSG_CACHE_TTL_MS = 1000L;

    private static final class FriendLastMsg
    {
        final boolean hasMessages;
        final String text;
        final String filename;
        final boolean outgoing;
        final long timestampMs;

        FriendLastMsg(final boolean h, final String t, final String f, final boolean o, final long ts)
        {
            hasMessages = h;
            text = t;
            filename = f;
            outgoing = o;
            timestampMs = ts;
        }
    }

    private static final FriendLastMsg FRIEND_LAST_MSG_EMPTY = new FriendLastMsg(false, null, null, false, 0L);
    private static final java.util.concurrent.ConcurrentHashMap<String, FriendLastMsg> friend_last_msg_cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> friend_last_msg_cache_ts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static FriendLastMsg cached_friend_last_msg(final String friend_pubkey)
    {
        if (TextUtils.isEmpty(friend_pubkey))
        {
            return FRIEND_LAST_MSG_EMPTY;
        }
        final Long ts = friend_last_msg_cache_ts.get(friend_pubkey);
        if (ts != null && (System.currentTimeMillis() - ts) < LAST_MSG_CACHE_TTL_MS)
        {
            final FriendLastMsg cached = friend_last_msg_cache.get(friend_pubkey);
            if (cached != null)
            {
                return cached;
            }
        }
        FriendLastMsg v = FRIEND_LAST_MSG_EMPTY;
        try
        {
            final List<Message> m = orma.selectFromMessage().tox_friendpubkeyEq(friend_pubkey).
                    orderByRcvd_timestampDesc().limit(1).toList();
            if (!m.isEmpty())
            {
                final Message msg = m.get(0);
                final long tms = Math.max(msg.rcvd_timestamp_ms, msg.sent_timestamp_ms) > 0L
                        ? Math.max(msg.rcvd_timestamp_ms, msg.sent_timestamp_ms)
                        : Math.max(msg.rcvd_timestamp, msg.sent_timestamp);
                v = new FriendLastMsg(true, msg.text, msg.filename_fullpath, msg.direction == 1,
                        normalize_message_timestamp_ms(tms));
            }
        }
        catch (Exception ignored)
        {
        }
        friend_last_msg_cache.put(friend_pubkey, v);
        friend_last_msg_cache_ts.put(friend_pubkey, System.currentTimeMillis());
        return v;
    }

    private static final class GroupLastMsg
    {
        final GroupMessage latest;
        final long timestampMs;

        GroupLastMsg(final GroupMessage l, final long ts)
        {
            latest = l;
            timestampMs = ts;
        }
    }

    private static final GroupLastMsg GROUP_LAST_MSG_EMPTY = new GroupLastMsg(null, 0L);
    private static final java.util.concurrent.ConcurrentHashMap<String, GroupLastMsg> group_last_msg_cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> group_last_msg_cache_ts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static GroupLastMsg cached_group_last_msg(final String group_identifier)
    {
        if (TextUtils.isEmpty(group_identifier))
        {
            return GROUP_LAST_MSG_EMPTY;
        }
        final String lower = group_identifier.toLowerCase(Locale.ROOT);
        final Long ts = group_last_msg_cache_ts.get(lower);
        if (ts != null && (System.currentTimeMillis() - ts) < LAST_MSG_CACHE_TTL_MS)
        {
            final GroupLastMsg cached = group_last_msg_cache.get(lower);
            if (cached != null)
            {
                return cached;
            }
        }
        GroupLastMsg v = GROUP_LAST_MSG_EMPTY;
        try
        {
            final List<GroupMessage> messages = orma.selectFromGroupMessage().
                    group_identifierEq(lower).
                    orderByRcvd_timestampDesc().limit(20).toList();
            if (!messages.isEmpty())
            {
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
                v = new GroupLastMsg(latest, normalize_message_timestamp_ms(latestTs));
            }
        }
        catch (Exception ignored)
        {
        }
        group_last_msg_cache.put(lower, v);
        group_last_msg_cache_ts.put(lower, System.currentTimeMillis());
        return v;
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
        return cached_friend_last_msg(friend_pubkey).hasMessages;
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

            final FriendLastMsg lm = cached_friend_last_msg(friend_pubkey);
            if (!lm.hasMessages)
            {
                return context.getString(R.string.chat_list_no_messages);
            }
            return format_message_preview(context, lm.text, lm.filename, lm.outgoing);
        }
        catch (Exception ignored)
        {
            return context.getString(R.string.chat_list_no_messages);
        }
    }

    static long friend_last_message_timestamp_ms(final String friend_pubkey)
    {
        return cached_friend_last_msg(friend_pubkey).timestampMs;
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

            final GroupLastMsg lm = cached_group_last_msg(group_identifier);
            if (lm.latest == null)
            {
                return format_group_list_status_subtitle(context, group_identifier);
            }
            return format_group_message_preview(context, lm.latest);
        }
        catch (Exception ignored)
        {
            return format_group_list_status_subtitle(context, group_identifier);
        }
    }

    static long group_last_message_timestamp_ms(final String group_identifier)
    {
        return cached_group_last_msg(group_identifier).timestampMs;
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

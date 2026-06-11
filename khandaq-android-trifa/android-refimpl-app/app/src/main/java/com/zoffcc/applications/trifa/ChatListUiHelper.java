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

import static com.zoffcc.applications.trifa.HelperGroup.format_group_list_status_subtitle;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

final class ChatListUiHelper
{
    private ChatListUiHelper()
    {
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
        if (timestamp_ms <= 0L)
        {
            return "";
        }
        final Calendar now = Calendar.getInstance();
        final Calendar then = Calendar.getInstance();
        then.setTimeInMillis(timestamp_ms);

        final boolean same_day = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                                 && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR);
        if (same_day)
        {
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timestamp_ms));
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
            return new SimpleDateFormat("d MMM", Locale.getDefault()).format(new Date(timestamp_ms));
        }
        return new SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(new Date(timestamp_ms));
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
            long ts = Math.max(message.rcvd_timestamp_ms, message.sent_timestamp_ms);
            if (ts <= 0L)
            {
                ts = Math.max(message.rcvd_timestamp, message.sent_timestamp);
            }
            return ts;
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
                    orderByRcvd_timestampDesc().limit(1).toList();
            if (messages.isEmpty())
            {
                return format_group_list_status_subtitle(context, group_identifier);
            }
            final GroupMessage message = messages.get(0);
            return format_message_preview(context, message.text, message.filename_fullpath, message.direction == 1);
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
                    orderByRcvd_timestampDesc().limit(1).toList();
            if (messages.isEmpty())
            {
                return 0L;
            }
            final GroupMessage message = messages.get(0);
            return Math.max(message.rcvd_timestamp, message.sent_timestamp);
        }
        catch (Exception ignored)
        {
            return 0L;
        }
    }

    private static String format_message_preview(final Context context, final String text,
                                                 final String filename, final boolean outgoing)
    {
        String body = text;
        if (TextUtils.isEmpty(body))
        {
            if (!TextUtils.isEmpty(filename))
            {
                body = context.getString(R.string.chat_list_preview_file);
            }
            else
            {
                body = context.getString(R.string.chat_list_preview_empty);
            }
        }
        body = body.replace('\n', ' ').trim();
        if (body.length() > 120)
        {
            body = body.substring(0, 117) + "...";
        }
        if (outgoing)
        {
            return context.getString(R.string.chat_list_preview_you, body);
        }
        return body;
    }
}

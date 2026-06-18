package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.zoffcc.applications.sorm.FriendList;

import static com.zoffcc.applications.trifa.TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE;

/**
 * Telegram-style online / last-seen labels for the contact (friend) list.
 * Timestamps are tracked locally when this client observes connection changes.
 */
final class FriendPresenceHelper
{
    private static final long JUST_NOW_MS = 60_000L;
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final long ONE_WEEK_MS = 7L * 24L * ONE_HOUR_MS;

    private FriendPresenceHelper()
    {
    }

    static boolean is_friend_online(final FriendList f)
    {
        if (f == null)
        {
            return false;
        }
        if (f.last_online_timestamp == LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
        {
            return true;
        }
        return f.TOX_CONNECTION_on_off != 0;
    }

    static long resolve_last_seen_ms(final FriendList f)
    {
        if (f == null || is_friend_online(f))
        {
            return 0L;
        }
        long ts = f.last_online_timestamp;
        if (ts == LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
        {
            return 0L;
        }
        if (ts > 0L && ts != LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE)
        {
            return ts;
        }
        final long real = f.last_online_timestamp_real;
        if (real > 0L && real != LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE
                && real != LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
        {
            return real;
        }
        return 0L;
    }

    static String format_contact_list_status(final Context context, final FriendList f)
    {
        if (context == null || f == null)
        {
            return "";
        }
        if (is_friend_online(f))
        {
            return context.getString(R.string.chat_presence_online);
        }

        final long last_seen_ms = resolve_last_seen_ms(f);
        if (last_seen_ms <= 0L)
        {
            if (f.last_online_timestamp == LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE
                    && f.last_online_timestamp_real == LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE)
            {
                return context.getString(R.string.group_member_last_seen_long_ago);
            }
            return context.getString(R.string.contact_list_presence_recently);
        }

        final long delta_ms = Math.max(0L, System.currentTimeMillis() - last_seen_ms);
        if (delta_ms < JUST_NOW_MS)
        {
            return context.getString(R.string.group_member_last_seen_just_now);
        }
        final long minutes = delta_ms / 60_000L;
        if (minutes < 60L)
        {
            return context.getResources().getQuantityString(R.plurals.group_member_last_seen_minutes,
                    (int) minutes, (int) minutes);
        }
        if (delta_ms < ONE_WEEK_MS)
        {
            final long hours = Math.max(1L, minutes / 60L);
            return context.getResources().getQuantityString(R.plurals.group_member_last_seen_hours,
                    (int) hours, (int) hours);
        }
        return context.getString(R.string.group_member_last_seen_long_ago);
    }

    static void bind_contact_list_presence_subtitle(final TextView subtitleView, final Context context,
                                                    final FriendList f)
    {
        if (subtitleView == null || context == null || f == null)
        {
            return;
        }
        subtitleView.setText(format_contact_list_status(context, f));
        if (is_friend_online(f))
        {
            subtitleView.setTextColor(ContextCompat.getColor(context, R.color.tg_chat_header_presence_online));
        }
        else
        {
            subtitleView.setTextColor(ContextCompat.getColor(context, R.color.tg_chat_preview));
        }
    }
}

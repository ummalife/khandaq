package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

final class MessageStatusHelper
{
    enum OutgoingStatus
    {
        SENDING,
        SENT,
        DELIVERED,
        READ
    }

    private MessageStatusHelper()
    {
    }

    static OutgoingStatus resolveDirectOutgoing(final Message message)
    {
        if (message == null)
        {
            return OutgoingStatus.SENT;
        }

        if (isSending(message))
        {
            return OutgoingStatus.SENDING;
        }

        if (message.read)
        {
            return OutgoingStatus.READ;
        }

        if (message.msg_at_relay || (message.sent_push > 0))
        {
            return OutgoingStatus.DELIVERED;
        }

        return OutgoingStatus.SENT;
    }

    static OutgoingStatus resolveGroupOutgoing(final GroupMessage message)
    {
        if (message == null)
        {
            return OutgoingStatus.SENT;
        }

        if ((message.message_id_tox == null) || message.message_id_tox.isEmpty())
        {
            return OutgoingStatus.SENDING;
        }

        if (message.sync_confirmations > 0)
        {
            return OutgoingStatus.DELIVERED;
        }

        return OutgoingStatus.SENT;
    }

    static int drawableForStatus(final OutgoingStatus status, final boolean groupChat)
    {
        switch (status)
        {
            case SENDING:
                return R.drawable.msg_status_clock;
            case SENT:
                return R.drawable.msg_status_check_one;
            case DELIVERED:
                return R.drawable.msg_status_check_two;
            case READ:
                if (groupChat)
                {
                    return R.drawable.msg_status_check_two;
                }
                return R.drawable.msg_status_check_two_read;
            default:
                return R.drawable.msg_status_check_one;
        }
    }

    private static final String TAG_STATUS_DRAWABLE = "msg_status_drawable";

    static void bindOutgoingIndicator(final ImageView indicator, final Message message)
    {
        bindOutgoingIndicator(indicator, resolveDirectOutgoing(message), false);
    }

    static void bindOutgoingIndicator(final ImageView indicator, final GroupMessage message)
    {
        bindOutgoingIndicator(indicator, resolveGroupOutgoing(message), true);
    }

    private static void bindOutgoingIndicator(final ImageView indicator, final OutgoingStatus status,
                                            final boolean groupChat)
    {
        if (indicator == null)
        {
            return;
        }

        final Context context = indicator.getContext();
        final int drawableRes = drawableForStatus(status, groupChat);
        final int size = (int) context.getResources().getDimension(R.dimen.msg_status_icon_size);

        if (indicator.getVisibility() == View.VISIBLE
                && Integer.valueOf(drawableRes).equals(indicator.getTag(R.id.m_icon)))
        {
            return;
        }

        indicator.setTag(R.id.m_icon, drawableRes);
        indicator.animate().cancel();
        indicator.setAlpha(0.55f);
        indicator.setImageResource(drawableRes);
        indicator.getLayoutParams().width = size;
        indicator.getLayoutParams().height = size;
        indicator.setVisibility(View.VISIBLE);
        indicator.animate().alpha(1f).setDuration(160).start();
    }

    private static boolean isSending(final Message message)
    {
        if (message.ft_outgoing_queued)
        {
            return true;
        }

        if (message.message_id == -1)
        {
            return true;
        }

        return message.ft_outgoing_queued;
    }
}

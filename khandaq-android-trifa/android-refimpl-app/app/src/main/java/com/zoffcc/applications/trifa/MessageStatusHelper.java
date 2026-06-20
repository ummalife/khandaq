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
        NOT_DELIVERED,
        FAILED,
        SENT,
        DELIVERED,
        READ
    }

    static final long DIRECT_DELIVERY_TIMEOUT_MS = MessageDeliveryWatchdog.DELIVERY_TIMEOUT_MS;

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

        if (MessageDeliveryRetryHelper.isDirectFailed(message))
        {
            return OutgoingStatus.FAILED;
        }

        if (isDirectDeliveryOverdue(message))
        {
            return OutgoingStatus.NOT_DELIVERED;
        }

        if (message.msg_at_relay)
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

        // KHANDAQ: NGC private (in-group direct) messages get NO delivery receipt and carry no
        // message_id_tox, so the normal sync_confirmations path would leave them on a clock forever.
        // They are only stored after the native send returns success, so show them as sent.
        if (message.private_message != 0)
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

        if (MessageDeliveryRetryHelper.isGroupFailed(message))
        {
            return OutgoingStatus.FAILED;
        }

        if (isGroupDeliveryOverdue(message))
        {
            return OutgoingStatus.NOT_DELIVERED;
        }

        return OutgoingStatus.SENT;
    }

    static int drawableForStatus(final OutgoingStatus status, final boolean groupChat)
    {
        switch (status)
        {
            case SENDING:
            case NOT_DELIVERED:
                return R.drawable.msg_status_clock;
            case FAILED:
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

    // KHANDAQ: bind an explicit status. File/media bubbles cannot reuse resolveDirectOutgoing()
    // (it would flag any sent file older than the 10s timeout as NOT_DELIVERED forever, since
    // files never set msg_at_relay/read). The outgoing-file holder class already encodes the real
    // transfer state, so the holder passes the right status directly.
    static void bindOutgoingIndicatorDirect(final ImageView indicator, final OutgoingStatus status)
    {
        bindOutgoingIndicator(indicator, status, false);
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
        indicator.setAlpha(status == OutgoingStatus.FAILED ? 1f : 0.55f);
        indicator.setImageResource(drawableRes);
        if (status == OutgoingStatus.FAILED)
        {
            indicator.setColorFilter(context.getResources().getColor(R.color.md_red_500));
        }
        else
        {
            indicator.clearColorFilter();
        }
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

    private static boolean isDirectDeliveryOverdue(final Message message)
    {
        if (message.message_id <= -1)
        {
            return false;
        }

        if (message.sent_timestamp <= 0)
        {
            return false;
        }

        return (System.currentTimeMillis() - message.sent_timestamp) > DIRECT_DELIVERY_TIMEOUT_MS;
    }

    private static boolean isGroupDeliveryOverdue(final GroupMessage message)
    {
        if (message.sent_timestamp <= 0)
        {
            return false;
        }

        return (System.currentTimeMillis() - message.sent_timestamp) > DIRECT_DELIVERY_TIMEOUT_MS;
    }
}

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.zoffcc.applications.sorm.GroupMessage;

import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;

/** Vertical spacing and bubble grouping for group chat rows. */
public final class GroupMessageLayoutHelper
{
    private GroupMessageLayoutHelper()
    {
    }

    public static final class RowLayout
    {
        public int topMarginPx;
        /** @deprecated use {@link #showPeerName} */
        public boolean hidePeerHeader;
        public boolean hideEntireRow;
        /** First message in a run from the same sender (name above bubble). */
        public boolean showPeerName;
        /** Last message in a run from the same sender (avatar on the left, Telegram-style). */
        public boolean showAvatar;
    }

    public static RowLayout hiddenRowLayout()
    {
        final RowLayout layout = new RowLayout();
        layout.hideEntireRow = true;
        layout.topMarginPx = 0;
        return layout;
    }

    public static boolean hasNonBlankText(final String text)
    {
        return text != null && !text.trim().isEmpty();
    }

    /** Timeline key used for chat ordering and last-message preview (rcvd can be newer than sent on sync). */
    public static long effectiveSortTimestampMs(final GroupMessage message)
    {
        if (message == null)
        {
            return 0L;
        }
        final long sent = message.sent_timestamp > 0L ? message.sent_timestamp : 0L;
        final long rcvd = message.rcvd_timestamp > 0L ? message.rcvd_timestamp : 0L;
        if (sent <= 0L)
        {
            return rcvd;
        }
        if (rcvd <= 0L)
        {
            return sent;
        }
        return Math.max(sent, rcvd);
    }

    public static void sortMessagesForChatDisplay(final java.util.List<GroupMessage> messages)
    {
        if (messages == null || messages.size() < 2)
        {
            return;
        }
        java.util.Collections.sort(messages, new java.util.Comparator<GroupMessage>()
        {
            @Override
            public int compare(final GroupMessage a, final GroupMessage b)
            {
                final int byTime = Long.compare(effectiveSortTimestampMs(a), effectiveSortTimestampMs(b));
                if (byTime != 0)
                {
                    return byTime;
                }
                return Long.compare(a.id, b.id);
            }
        });
    }

    public static int sortedInsertIndex(final java.util.List<GroupMessage> messages, final GroupMessage message)
    {
        if (messages == null || messages.isEmpty())
        {
            return 0;
        }
        final long ts = effectiveSortTimestampMs(message);
        for (int i = messages.size() - 1; i >= 0; i--)
        {
            if (effectiveSortTimestampMs(messages.get(i)) <= ts)
            {
                return i + 1;
            }
        }
        return 0;
    }

    /** Whether a group message should occupy a row in the chat list. */
    public static boolean isRenderableMessage(final Context context, final GroupMessage message)
    {
        if (message == null)
        {
            return false;
        }

        if (message.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            return hasNonBlankText(message.text)
                    || hasNonBlankText(message.file_name)
                    || hasNonBlankText(message.filename_fullpath)
                    || hasNonBlankText(message.path_name);
        }

        if (TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(message.tox_group_peer_pubkey))
        {
            if (HelperGroup.isReconnectNoiseSystemMessage(message.text))
            {
                return false;
            }
            if (context == null)
            {
                return hasNonBlankText(message.text);
            }
            return hasNonBlankText(displayTextForMessage(context, message));
        }

        return hasNonBlankText(message.text);
    }

    public static boolean isRenderableMessageForDb(final GroupMessage message)
    {
        return isRenderableMessage(null, message);
    }

    public static RowLayout layoutFor(final GroupMessage message, final int position)
    {
        return layoutFor(message, position, null);
    }

    public static RowLayout layoutFor(final GroupMessage message, final int position, final Context context)
    {
        final RowLayout layout = new RowLayout();
        layout.topMarginPx = marginPx(context, R.dimen.tg_message_margin_diff_sender);
        layout.hidePeerHeader = false;
        layout.hideEntireRow = false;
        layout.showPeerName = true;
        layout.showAvatar = true;

        if (message == null)
        {
            layout.hideEntireRow = true;
            return layout;
        }

        if (!isRenderableMessage(context, message))
        {
            return hiddenRowLayout();
        }

        final boolean isSystem = TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(message.tox_group_peer_pubkey);
        if (isSystem && HelperGroup.isReconnectNoiseSystemMessage(message.text))
        {
            layout.hideEntireRow = true;
            layout.topMarginPx = 0;
            return layout;
        }

        if (position <= 0)
        {
            layout.topMarginPx = marginPx(context,
                    isSystem ? R.dimen.tg_message_margin_system : R.dimen.tg_message_margin_diff_sender);
            return layout;
        }

        try
        {
            if (MainActivity.group_message_list_fragment == null
                    || MainActivity.group_message_list_fragment.adapter == null)
            {
                return layout;
            }

            final GroupMessagelistAdapter adapter = MainActivity.group_message_list_fragment.adapter;
            final GroupMessage previous = adapter.get_item(position - 1);
            if (previous == null)
            {
                return layout;
            }

            final boolean prevSystem = TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(previous.tox_group_peer_pubkey);
            if (isSystem || prevSystem)
            {
                layout.topMarginPx = marginPx(context, R.dimen.tg_message_margin_diff_sender);
                return layout;
            }

            layout.showPeerName = !sameSenderGroup(message, previous);
            if (layout.showPeerName)
            {
                layout.topMarginPx = marginPx(context, R.dimen.tg_message_margin_diff_sender);
            }
            else
            {
                layout.topMarginPx = marginPx(context, R.dimen.tg_message_margin_same_sender);
            }

            layout.showAvatar = true;
            final int item_count = adapter.getItemCount();
            if (position + 1 < item_count)
            {
                final GroupMessage next = adapter.get_item(position + 1);
                if (next != null && sameSenderGroup(next, message))
                {
                    layout.showAvatar = false;
                }
            }

            layout.hidePeerHeader = !layout.showPeerName;
        }
        catch (Exception ignored)
        {
        }

        return layout;
    }

    public static void applyTopMargin(final View itemView, final RowLayout layout)
    {
        if (itemView == null || layout == null)
        {
            return;
        }

        final ViewGroup.LayoutParams params = itemView.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams))
        {
            return;
        }

        final ViewGroup.MarginLayoutParams marginParams = (ViewGroup.MarginLayoutParams) params;
        marginParams.topMargin = layout.topMarginPx;
        itemView.setLayoutParams(marginParams);
    }

    public static void applyRowVisibility(final View itemView, final View contentRoot, final RowLayout layout)
    {
        if (layout != null && layout.hideEntireRow)
        {
            if (itemView != null)
            {
                itemView.setVisibility(View.GONE);
            }
            if (contentRoot != null)
            {
                contentRoot.setVisibility(View.GONE);
            }
            return;
        }

        if (itemView != null)
        {
            itemView.setVisibility(View.VISIBLE);
        }
        if (contentRoot != null)
        {
            contentRoot.setVisibility(View.VISIBLE);
        }
    }

    public static String displayTextForMessage(final Context context, final GroupMessage message)
    {
        if (message == null)
        {
            return "";
        }

        if (TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(message.tox_group_peer_pubkey))
        {
            return HelperGroup.formatSystemMessageForDisplay(context, message.text);
        }

        return message.text == null ? "" : message.text;
    }

    private static boolean sameSenderGroup(final GroupMessage current, final GroupMessage previous)
    {
        if (current.direction != previous.direction)
        {
            return false;
        }

        if (current.direction == 1)
        {
            return true;
        }

        if (current.tox_group_peer_pubkey == null || previous.tox_group_peer_pubkey == null)
        {
            return false;
        }

        return current.tox_group_peer_pubkey.equals(previous.tox_group_peer_pubkey);
    }

    private static int marginPx(final Context context, final int dimenResId)
    {
        if (context != null)
        {
            return (int) context.getResources().getDimension(dimenResId);
        }
        return (int) dp2px(12);
    }
}

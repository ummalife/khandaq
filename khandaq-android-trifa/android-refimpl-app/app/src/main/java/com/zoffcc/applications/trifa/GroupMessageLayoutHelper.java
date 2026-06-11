package com.zoffcc.applications.trifa;

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
    private static final int MARGIN_SAME_SENDER_DP = 6;
    private static final int MARGIN_DIFF_SENDER_DP = 14;
    private static final int MARGIN_SYSTEM_DP = 4;

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
        layout.topMarginPx = (int) dp2px(MARGIN_DIFF_SENDER_DP);
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
            layout.topMarginPx = (int) dp2px(isSystem ? MARGIN_SYSTEM_DP : MARGIN_DIFF_SENDER_DP);
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
                layout.topMarginPx = (int) dp2px(MARGIN_DIFF_SENDER_DP);
                return layout;
            }

            layout.showPeerName = !sameSenderGroup(message, previous);
            if (layout.showPeerName)
            {
                layout.topMarginPx = (int) dp2px(MARGIN_DIFF_SENDER_DP);
            }
            else
            {
                layout.topMarginPx = (int) dp2px(MARGIN_SAME_SENDER_DP);
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
}

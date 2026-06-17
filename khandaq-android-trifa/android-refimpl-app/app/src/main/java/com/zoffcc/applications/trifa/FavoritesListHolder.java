package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.FavoritesChatHelper.CHAT_ID;

final class FavoritesListHolder extends RecyclerView.ViewHolder implements View.OnClickListener
{
    private final Context context;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView unreadCount;
    private final TextView chatTimeView;
    private final de.hdodenhof.circleimageview.CircleImageView avatar;
    private final ViewGroup rowContainer;

    FavoritesListHolder(final View itemView, final Context context)
    {
        super(itemView);
        this.context = context;
        titleView = itemView.findViewById(R.id.f_name);
        subtitleView = itemView.findViewById(R.id.f_status_message);
        unreadCount = itemView.findViewById(R.id.f_unread_count);
        chatTimeView = itemView.findViewById(R.id.f_chat_time);
        avatar = itemView.findViewById(R.id.f_avatar_icon);
        rowContainer = itemView.findViewById(R.id.chat_list_row);

        itemView.setOnClickListener(this);
        itemView.setOnLongClickListener(v -> true);
    }

    void bind()
    {
        titleView.setText(FavoritesChatHelper.displayName(context));
        subtitleView.setText(ChatListUiHelper.friend_last_message_preview(context, CHAT_ID));
        ChatListUiHelper.bind_preview_text_color(subtitleView, ChatDraftHelper.has_friend_draft(CHAT_ID));

        final long ts = ChatListUiHelper.friend_last_message_timestamp_ms(CHAT_ID);
        if (ts > 0L)
        {
            chatTimeView.setText(ChatListUiHelper.format_chat_list_time(context, ts));
            chatTimeView.setVisibility(View.VISIBLE);
        }
        else
        {
            chatTimeView.setVisibility(View.GONE);
        }

        ChatListUiHelper.bind_unread_badge(unreadCount, FavoritesChatHelper.unreadCount());
        ChatListUiHelper.prepare_telegram_row(rowContainer, titleView, subtitleView, chatTimeView,
                null, null, null, null, null);

        FavoritesChatHelper.bindListAvatar(avatar);

        final ImageView statusIcon = itemView.findViewById(R.id.f_status_icon);
        final ImageView userStatusIcon = itemView.findViewById(R.id.f_user_status_icon);
        final ImageView relayIcon = itemView.findViewById(R.id.f_relay_icon);
        final ImageView notificationIcon = itemView.findViewById(R.id.f_notification);
        final TextView lastOnline = itemView.findViewById(R.id.f_last_online_timestamp);
        final TextView ipAddr = itemView.findViewById(R.id.f_ip_addr_text);

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
        if (notificationIcon != null)
        {
            notificationIcon.setVisibility(View.GONE);
        }
        if (lastOnline != null)
        {
            lastOnline.setVisibility(View.GONE);
        }
        if (ipAddr != null)
        {
            ipAddr.setVisibility(View.GONE);
        }

        rowContainer.setBackgroundResource(R.drawable.friend_list_item_ripple);
    }

    @Override
    public void onClick(final View v)
    {
        FavoritesChatHelper.openChat(v.getContext());
    }
}

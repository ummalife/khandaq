package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;

import java.util.Objects;

/** Avoids chat-list flicker by comparing row payload before RecyclerView notifications. */
final class ChatListRefreshHelper
{
    private ChatListRefreshHelper()
    {
    }

    static boolean groupListDbRowEquals(final GroupDB left, final GroupDB right)
    {
        if (left == right)
        {
            return true;
        }
        if (left == null || right == null)
        {
            return false;
        }
        return left.group_active == right.group_active
                && left.group_we_left == right.group_we_left
                && left.notification_silent == right.notification_silent
                && left.privacy_state == right.privacy_state
                && left.peer_count == right.peer_count
                && left.tox_group_number == right.tox_group_number
                && left.own_peer_number == right.own_peer_number
                && Objects.equals(left.name, right.name)
                && Objects.equals(left.topic, right.topic)
                && Objects.equals(left.who_invited__tox_public_key_string, right.who_invited__tox_public_key_string);
    }

    static boolean friendListDbRowEquals(final FriendList left, final FriendList right)
    {
        if (left == right)
        {
            return true;
        }
        if (left == null || right == null)
        {
            return false;
        }
        return left.TOX_CONNECTION == right.TOX_CONNECTION
                && left.TOX_CONNECTION_on_off == right.TOX_CONNECTION_on_off
                && left.TOX_CONNECTION_real == right.TOX_CONNECTION_real
                && left.notification_silent == right.notification_silent
                && left.is_relay == right.is_relay
                && left.last_online_timestamp == right.last_online_timestamp
                && left.last_online_timestamp_real == right.last_online_timestamp_real
                && Objects.equals(left.name, right.name)
                && Objects.equals(left.tox_public_key_string, right.tox_public_key_string);
    }
}

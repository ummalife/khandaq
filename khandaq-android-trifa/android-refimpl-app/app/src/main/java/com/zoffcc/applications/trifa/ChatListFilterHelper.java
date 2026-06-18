package com.zoffcc.applications.trifa;

import android.content.Context;

import com.zoffcc.applications.sorm.ConferenceDB;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;

import java.util.List;

import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_CONFERENCE;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FAVORITES;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FRIEND;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_GROUP;
import static com.zoffcc.applications.trifa.HelperGroup.is_removed_legacy_public_community_group;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

final class ChatListFilterHelper
{
    static final class TabUnreadCounts
    {
        int direct;
        int groups;
        int favorites;
    }

    private ChatListFilterHelper()
    {
    }

    static TabUnreadCounts computeUnreadCounts(final Context context)
    {
        final TabUnreadCounts counts = new TabUnreadCounts();
        if (orma == null)
        {
            return counts;
        }

        try
        {
            final List<FriendList> friends = orma.selectFromFriendList().is_relayNotEq(true).toList();
            if (friends != null)
            {
                for (int i = 0; i < friends.size(); i++)
                {
                    final FriendList friend = friends.get(i);
                    if (HelperFriend.is_own_public_key(friend.tox_public_key_string))
                    {
                        continue;
                    }
                    final int unread = orma.selectFromMessage()
                            .tox_friendpubkeyEq(friend.tox_public_key_string)
                            .is_newEq(true)
                            .count();
                    if (unread <= 0)
                    {
                        continue;
                    }
                    counts.direct += unread;
                    if (ChatFavoritesHelper.isFavorite(context, ChatFavoritesHelper.friendKey(friend.tox_public_key_string)))
                    {
                        counts.favorites += unread;
                    }
                }
            }

            final List<ConferenceDB> conferences = orma.selectFromConferenceDB().toList();
            if (conferences != null)
            {
                for (int i = 0; i < conferences.size(); i++)
                {
                    final ConferenceDB conference = conferences.get(i);
                    final int unread = orma.selectFromConferenceMessage()
                            .conference_identifierEq(conference.conference_identifier)
                            .is_newEq(true)
                            .count();
                    if (unread <= 0)
                    {
                        continue;
                    }
                    counts.groups += unread;
                    if (ChatFavoritesHelper.isFavorite(context,
                            ChatFavoritesHelper.conferenceKey(conference.conference_identifier)))
                    {
                        counts.favorites += unread;
                    }
                }
            }

            final List<GroupDB> groups = orma.selectFromGroupDB().toList();
            if (groups != null)
            {
                for (int i = 0; i < groups.size(); i++)
                {
                    final GroupDB group = groups.get(i);
                    if (is_removed_legacy_public_community_group(group.group_identifier))
                    {
                        continue;
                    }
                    final int unread = orma.selectFromGroupMessage()
                            .group_identifierEq(group.group_identifier.toLowerCase())
                            .is_newEq(true)
                            .count();
                    if (unread <= 0)
                    {
                        continue;
                    }
                    counts.groups += unread;
                    if (ChatFavoritesHelper.isFavorite(context, ChatFavoritesHelper.groupKey(group.group_identifier)))
                    {
                        counts.favorites += unread;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return counts;
    }

    static boolean matchesFilter(final Context context, final CombinedFriendsAndConferences item, final int filterTab)
    {
        if (item == null)
        {
            return false;
        }

        if (item.is_friend == COMBINED_IS_FAVORITES)
        {
            return true;
        }

        switch (filterTab)
        {
            case FriendListFragment.CHAT_FILTER_DIRECT:
                return item.is_friend == COMBINED_IS_FRIEND;
            case FriendListFragment.CHAT_FILTER_GROUPS:
                return item.is_friend == COMBINED_IS_GROUP || item.is_friend == COMBINED_IS_CONFERENCE;
            case FriendListFragment.CHAT_FILTER_FAVORITES:
                return ChatFavoritesHelper.isFavorite(context, item);
            default:
                return true;
        }
    }
}

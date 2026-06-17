package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.zoffcc.applications.sorm.ConferenceDB;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_CONFERENCE;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FRIEND;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_GROUP;

final class ChatFavoritesHelper
{
    private static final String PREFS_NAME = "chat_favorites";
    private static final String PREFS_KEY = "favorite_keys";

    private ChatFavoritesHelper()
    {
    }

    static String friendKey(final String pubkey)
    {
        return keyWithPrefix("friend:", pubkey);
    }

    static String groupKey(final String groupId)
    {
        return keyWithPrefix("group:", groupId);
    }

    static String conferenceKey(final String conferenceId)
    {
        return keyWithPrefix("conference:", conferenceId);
    }

    private static String keyWithPrefix(final String prefix, final String id)
    {
        if (id == null)
        {
            return null;
        }
        final String normalized = id.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty())
        {
            return null;
        }
        return prefix + normalized;
    }

    static boolean isFavorite(final Context context, final CombinedFriendsAndConferences item)
    {
        if (context == null || item == null)
        {
            return false;
        }

        if (item.is_friend == COMBINED_IS_FRIEND && item.friend_item != null)
        {
            return isFavorite(context, friendKey(item.friend_item.tox_public_key_string));
        }
        if (item.is_friend == COMBINED_IS_GROUP && item.group_item != null)
        {
            return isFavorite(context, groupKey(item.group_item.group_identifier));
        }
        if (item.is_friend == COMBINED_IS_CONFERENCE && item.conference_item != null)
        {
            return isFavorite(context, conferenceKey(item.conference_item.conference_identifier));
        }
        return false;
    }

    static boolean isFavorite(final Context context, final String key)
    {
        if (TextUtils.isEmpty(key))
        {
            return false;
        }
        return getFavoriteKeys(context).contains(key);
    }

    static boolean toggleFavorite(final Context context, final CombinedFriendsAndConferences item)
    {
        final String key = favoriteKeyForItem(item);
        if (TextUtils.isEmpty(key))
        {
            return false;
        }

        final Set<String> keys = getFavoriteKeys(context);
        final boolean nowFavorite;
        if (keys.contains(key))
        {
            keys.remove(key);
            nowFavorite = false;
        }
        else
        {
            keys.add(key);
            nowFavorite = true;
        }
        saveFavoriteKeys(context, keys);
        return nowFavorite;
    }

    static boolean toggleFavoriteFriend(final Context context, final FriendList friend)
    {
        if (friend == null)
        {
            return false;
        }
        return toggleFavoriteKey(context, friendKey(friend.tox_public_key_string));
    }

    static boolean toggleFavoriteGroup(final Context context, final GroupDB group)
    {
        if (group == null)
        {
            return false;
        }
        return toggleFavoriteKey(context, groupKey(group.group_identifier));
    }

    static boolean toggleFavoriteConference(final Context context, final ConferenceDB conference)
    {
        if (conference == null)
        {
            return false;
        }
        return toggleFavoriteKey(context, conferenceKey(conference.conference_identifier));
    }

    private static boolean toggleFavoriteKey(final Context context, final String key)
    {
        if (TextUtils.isEmpty(key))
        {
            return false;
        }

        final Set<String> keys = getFavoriteKeys(context);
        final boolean nowFavorite;
        if (keys.contains(key))
        {
            keys.remove(key);
            nowFavorite = false;
        }
        else
        {
            keys.add(key);
            nowFavorite = true;
        }
        saveFavoriteKeys(context, keys);
        return nowFavorite;
    }

    private static String favoriteKeyForItem(final CombinedFriendsAndConferences item)
    {
        if (item == null)
        {
            return null;
        }
        if (item.is_friend == COMBINED_IS_FRIEND && item.friend_item != null)
        {
            return friendKey(item.friend_item.tox_public_key_string);
        }
        if (item.is_friend == COMBINED_IS_GROUP && item.group_item != null)
        {
            return groupKey(item.group_item.group_identifier);
        }
        if (item.is_friend == COMBINED_IS_CONFERENCE && item.conference_item != null)
        {
            return conferenceKey(item.conference_item.conference_identifier);
        }
        return null;
    }

    private static Set<String> getFavoriteKeys(final Context context)
    {
        final SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final Set<String> stored = prefs.getStringSet(PREFS_KEY, null);
        if (stored == null)
        {
            return new HashSet<>();
        }
        final HashSet<String> keys = new HashSet<>();
        for (final String key : stored)
        {
            if (!TextUtils.isEmpty(key))
            {
                keys.add(key);
            }
        }
        return keys;
    }

    private static void saveFavoriteKeys(final Context context, final Set<String> keys)
    {
        context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(PREFS_KEY)
                .putStringSet(PREFS_KEY, new HashSet<>(keys))
                .commit();
    }
}

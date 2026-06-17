/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.l4digital.fastscroll.FastScroller;
import com.zoffcc.applications.sorm.ConferenceDB;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;

import java.util.Iterator;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_CONFERENCE;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FAVORITES;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FRIEND;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_GROUP;
import static com.zoffcc.applications.trifa.MainActivity.PREF__compact_friendlist;

public class FriendlistAdapter extends RecyclerView.Adapter implements FastScroller.SectionIndexer
{
    private static final String TAG = "trifa.FriendlistAdapter";

    private final List<CombinedFriendsAndConferences> friendlistitems;
    private Context context;

    public FriendlistAdapter(Context context, List<CombinedFriendsAndConferences> items)
    {
        // Log.i(TAG, "FriendlistAdapter");

        this.friendlistitems = items;
        this.context = context;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position)
    {
        try
        {
            final CombinedFriendsAndConferences item = this.friendlistitems.get(position);
            if (item.is_friend == COMBINED_IS_GROUP && item.group_item != null
                    && item.group_item.group_identifier != null)
            {
                return stableId("g", item.group_item.group_identifier);
            }
            if (item.is_friend == COMBINED_IS_FRIEND && item.friend_item != null
                    && item.friend_item.tox_public_key_string != null)
            {
                return stableId("f", item.friend_item.tox_public_key_string);
            }
            if (item.is_friend == COMBINED_IS_CONFERENCE && item.conference_item != null
                    && item.conference_item.conference_identifier != null)
            {
                return stableId("c", item.conference_item.conference_identifier);
            }
            if (item.is_friend == COMBINED_IS_FAVORITES)
            {
                return stableId("fav", "favorites");
            }
        }
        catch (Exception ignored)
        {
        }
        return position;
    }

    private static long stableId(final String prefix, final String key)
    {
        return ((long) (prefix + key).hashCode()) & 0x00000000ffffffffL;
    }

    public void replaceAllItems(final List<CombinedFriendsAndConferences> newItems)
    {
        this.friendlistitems.clear();
        if (newItems != null)
        {
            this.friendlistitems.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType)
    {
        // Log.i(TAG, "onCreateViewHolder");

        View view;
        switch (viewType)
        {
            case CombinedFriendsAndConferences_model.ITEM_IS_FRIEND:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.chat_list_item, parent, false);
                return new FriendListHolder(view, this.context);

            case CombinedFriendsAndConferences_model.ITEM_IS_GROUP:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.chat_list_item, parent, false);
                return new GroupListHolder(view, this.context);

            case CombinedFriendsAndConferences_model.ITEM_IS_CONFERENCE:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.chat_list_item, parent, false);
                return new ConferenceListHolder(view, this.context);

            case CombinedFriendsAndConferences_model.ITEM_IS_FAVORITES:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.chat_list_item, parent, false);
                return new FavoritesListHolder(view, this.context);
        }

        view = LayoutInflater.from(parent.getContext()).inflate(R.layout.chat_list_item, parent, false);
        return new FriendListHolder(view, this.context);
    }

    @Override
    public int getItemViewType(int position)
    {
        CombinedFriendsAndConferences my_item = this.friendlistitems.get(position);

        if (my_item.is_friend == COMBINED_IS_FRIEND)
        {
            return CombinedFriendsAndConferences_model.ITEM_IS_FRIEND;
        }
        else if (my_item.is_friend == COMBINED_IS_GROUP)
        {
            return CombinedFriendsAndConferences_model.ITEM_IS_GROUP;
        }
        else if (my_item.is_friend == COMBINED_IS_FAVORITES)
        {
            return CombinedFriendsAndConferences_model.ITEM_IS_FAVORITES;
        }
        else // is conference
        {
            return CombinedFriendsAndConferences_model.ITEM_IS_CONFERENCE;
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position)
    {
        // Log.i(TAG, "onBindViewHolder:position=" + position);

        try
        {
            CombinedFriendsAndConferences fl2 = this.friendlistitems.get(position);
            // Log.i(TAG, "onBindViewHolder:fl2=" + fl2);

            int type = getItemViewType(position);
            // Log.i(TAG, "onBindViewHolder:type=" + type);

            switch (type)
            {
                case CombinedFriendsAndConferences_model.ITEM_IS_FRIEND:
                    // Log.i(TAG, "onBindViewHolder:ITEM_IS_FRIEND");
                    ((FriendListHolder) holder).bindFriendList(fl2.friend_item);
                    break;
                case CombinedFriendsAndConferences_model.ITEM_IS_CONFERENCE:
                    // Log.i(TAG, "onBindViewHolder:ITEM_IS_CONFERENCE");
                    ((ConferenceListHolder) holder).bindFriendList(fl2.conference_item);
                    break;
                case CombinedFriendsAndConferences_model.ITEM_IS_GROUP:
                    // Log.i(TAG, "onBindViewHolder:ITEM_IS_GROUP");
                    ((GroupListHolder) holder).bindFriendList(fl2.group_item);
                    break;
                case CombinedFriendsAndConferences_model.ITEM_IS_FAVORITES:
                    ((FavoritesListHolder) holder).bind();
                    break;
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "onBindViewHolder:EE1:" + e.getMessage());
            e.printStackTrace();
            ((FriendListHolder) holder).bindFriendList(null);
        }
    }

    @Override
    public int getItemCount()
    {
        if (this.friendlistitems != null)
        {
            return this.friendlistitems.size();
        }
        else
        {
            return 0;
        }
    }

    public void add_item(CombinedFriendsAndConferences new_item)
    {
        // Log.i(TAG, "add_item:" + new_item + ":" + this.friendlistitems.size());

        try
        {
            this.friendlistitems.add(new_item);
            // TODO: use "notifyItemInserted" !!
            this.notifyDataSetChanged();
            // Log.i(TAG, "add_item:002:" + this.friendlistitems.size());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "add_item:EE:" + e.getMessage());
        }
    }

    public void clear_items()
    {
        this.friendlistitems.clear();
        this.notifyDataSetChanged();
    }

    public boolean update_item(CombinedFriendsAndConferences new_item_combined, int is_friend)
    {
        // Log.i(TAG, "update_item:" + new_item);
        boolean found_item = false;

        try
        {
            Iterator it = this.friendlistitems.iterator();
            while (it.hasNext())
            {
                CombinedFriendsAndConferences f_combined = (CombinedFriendsAndConferences) it.next();

                if (is_friend == COMBINED_IS_FRIEND)
                {
                    if (f_combined.is_friend == COMBINED_IS_FRIEND)
                    {
                        FriendList f = f_combined.friend_item;
                        FriendList new_item = new_item_combined.friend_item;

                        if (f.tox_public_key_string.compareTo(new_item.tox_public_key_string) == 0)
                        {
                            found_item = true;
                            int pos = this.friendlistitems.indexOf(f_combined);
                            final boolean rowChanged = !ChatListRefreshHelper.friendListDbRowEquals(f, new_item);
                            this.friendlistitems.set(pos, new_item_combined);
                            if (rowChanged)
                            {
                                this.notifyItemChanged(pos);
                            }
                            break;
                        }
                    }
                }
                else if (is_friend == COMBINED_IS_GROUP)
                {
                    if (f_combined.is_friend == COMBINED_IS_GROUP)
                    {
                        GroupDB f = f_combined.group_item;
                        GroupDB new_item = new_item_combined.group_item;

                        if (f.group_identifier.compareTo(new_item.group_identifier) == 0)
                        {
                            found_item = true;
                            int pos = this.friendlistitems.indexOf(f_combined);
                            final boolean rowChanged = !ChatListRefreshHelper.groupListDbRowEquals(f, new_item);
                            this.friendlistitems.set(pos, new_item_combined);
                            if (rowChanged)
                            {
                                this.notifyItemChanged(pos);
                            }
                            break;
                        }
                    }
                }
                else if (is_friend == COMBINED_IS_FAVORITES)
                {
                    if (f_combined.is_friend == COMBINED_IS_FAVORITES)
                    {
                        found_item = true;
                        int pos = this.friendlistitems.indexOf(f_combined);
                        this.friendlistitems.set(pos, new_item_combined);
                        this.notifyItemChanged(pos);
                        break;
                    }
                }
                else // is conference
                {
                    if (f_combined.is_friend == COMBINED_IS_CONFERENCE)
                    {
                        ConferenceDB f = f_combined.conference_item;
                        ConferenceDB new_item = new_item_combined.conference_item;

                        if (f.conference_identifier.compareTo(new_item.conference_identifier) == 0)
                        {
                            found_item = true;
                            int pos = this.friendlistitems.indexOf(f_combined);
                            this.friendlistitems.set(pos, new_item_combined);
                            this.notifyItemChanged(pos);
                            break;
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "update_item:EE:" + e.getMessage());
        }

        return found_item;
    }

    public boolean remove_item(final CombinedFriendsAndConferences item_combined, final int is_friend)
    {
        boolean removed_item = false;

        try
        {
            int pos = 0;
            final Iterator it = this.friendlistitems.iterator();
            while (it.hasNext())
            {
                final CombinedFriendsAndConferences f_combined = (CombinedFriendsAndConferences) it.next();

                if (is_friend == COMBINED_IS_FRIEND)
                {
                    if (f_combined.is_friend == COMBINED_IS_FRIEND)
                    {
                        final FriendList f = f_combined.friend_item;
                        final FriendList remove_item = item_combined.friend_item;

                        if (f != null && remove_item != null &&
                            f.tox_public_key_string.compareTo(remove_item.tox_public_key_string) == 0)
                        {
                            it.remove();
                            this.notifyItemRemoved(pos);
                            removed_item = true;
                            break;
                        }
                    }
                }
                else if (is_friend == COMBINED_IS_GROUP)
                {
                    if (f_combined.is_friend == COMBINED_IS_GROUP)
                    {
                        final GroupDB f = f_combined.group_item;
                        final GroupDB remove_item = item_combined.group_item;

                        if (f != null && remove_item != null &&
                            f.group_identifier.compareTo(remove_item.group_identifier) == 0)
                        {
                            it.remove();
                            this.notifyItemRemoved(pos);
                            removed_item = true;
                            break;
                        }
                    }
                }
                else if (is_friend == COMBINED_IS_FAVORITES)
                {
                    if (f_combined.is_friend == COMBINED_IS_FAVORITES)
                    {
                        it.remove();
                        this.notifyItemRemoved(pos);
                        removed_item = true;
                        break;
                    }
                }
                else
                {
                    if (f_combined.is_friend == COMBINED_IS_CONFERENCE)
                    {
                        final ConferenceDB f = f_combined.conference_item;
                        final ConferenceDB remove_item = item_combined.conference_item;

                        if (f != null && remove_item != null &&
                            f.conference_identifier.compareTo(remove_item.conference_identifier) == 0)
                        {
                            it.remove();
                            this.notifyItemRemoved(pos);
                            removed_item = true;
                            break;
                        }
                    }
                }

                pos++;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "remove_item:EE:" + e.getMessage());
        }

        return removed_item;
    }

    public int indexOfGroup(final String groupIdentifier)
    {
        if (groupIdentifier == null)
        {
            return -1;
        }
        for (int i = 0; i < friendlistitems.size(); i++)
        {
            final CombinedFriendsAndConferences item = friendlistitems.get(i);
            if (item.is_friend == COMBINED_IS_GROUP && item.group_item != null
                    && item.group_item.group_identifier != null
                    && item.group_item.group_identifier.equalsIgnoreCase(groupIdentifier))
            {
                return i;
            }
        }
        return -1;
    }

    public void notifyGroupRowChanged(final String groupIdentifier)
    {
        final int pos = indexOfGroup(groupIdentifier);
        if (pos >= 0)
        {
            notifyItemChanged(pos);
        }
    }

    public boolean remove_group_item(@Nullable String group_identifier)
    {
        if (group_identifier == null)
        {
            return false;
        }

        try
        {
            int pos = 0;
            Iterator it = this.friendlistitems.iterator();

            while (it.hasNext())
            {
                CombinedFriendsAndConferences item = (CombinedFriendsAndConferences) it.next();

                if (item.is_friend == COMBINED_IS_GROUP && item.group_item != null &&
                    item.group_item.group_identifier != null &&
                    item.group_item.group_identifier.equalsIgnoreCase(group_identifier))
                {
                    it.remove();
                    this.notifyItemRemoved(pos);
                    return true;
                }

                pos++;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "remove_group_item:EE:" + e.getMessage());
        }

        return false;
    }

    @Override
    public String getSectionText(int position)
    {
        // set fastscroller bluble text
        return " ";
    }
}

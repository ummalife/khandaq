/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2021 Zoff <zoff@zoff.cc>
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
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.FriendList;

import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.zoffcc.applications.trifa.HelperFriend.main_get_friend;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperRelay.get_pushurl_for_friend;
import static com.zoffcc.applications.trifa.HelperRelay.get_relay_for_friend;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP;

public class FriendSelectSingleAdapter extends ArrayAdapter<FriendSelectSingle>
{
    List<FriendSelectSingle> datalist;
    Context context;
    int resource;

    public FriendSelectSingleAdapter(Context context, int resource, List<FriendSelectSingle> input_datalist)
    {
        super(context, resource, input_datalist);
        this.context = context;
        this.resource = resource;
        this.datalist = input_datalist;
    }

    @NonNull
    @Override
    public View getView(final int position, @Nullable View convertView, @NonNull ViewGroup parent)
    {
        LayoutInflater layoutInflater = LayoutInflater.from(context);
        View view = layoutInflater.inflate(resource, null, false);
        TextView textViewName = view.findViewById(R.id.textViewName);
        de.hdodenhof.circleimageview.CircleImageView avatar = (de.hdodenhof.circleimageview.CircleImageView) view.findViewById(
                R.id.f_avatar_icon);
        ImageView f_status_icon = (ImageView) view.findViewById(R.id.f_status_icon);
        ImageView f_relay_icon = (ImageView) view.findViewById(R.id.f_relay_icon);
        FriendSelectSingle friend_entry = datalist.get(position);

        if (friend_entry.getType() == 0)
        {
            final FriendList fl = main_get_friend(friend_entry.pubkey);

            // ------ now fill with data ------
            textViewName.setText(friend_entry.getName());
            ChatBubbleUiHelper.fill_friend_list_avatar(context, fl.tox_public_key_string, friend_entry.getName(), avatar);

            f_status_icon.setVisibility(View.VISIBLE);
            f_relay_icon.setVisibility(View.INVISIBLE);

            String relay_ = get_relay_for_friend(fl.tox_public_key_string);

            if (relay_ != null) // friend HAS a relay
            {
                FriendList relay_fl = main_get_friend(tox_friend_by_public_key__wrapper(relay_));

                if (relay_fl != null)
                {
                    if (fl.TOX_CONNECTION_real == 0)
                    {
                        f_status_icon.setImageResource(R.drawable.circle_red);
                    }
                    else
                    {
                        f_status_icon.setImageResource(R.drawable.circle_green);
                    }

                    if (relay_fl.TOX_CONNECTION_real == 0)
                    {
                        f_relay_icon.setImageResource(R.drawable.circle_red);
                    }
                    else
                    {
                        f_relay_icon.setImageResource(R.drawable.circle_green);
                    }

                    f_status_icon.setVisibility(View.VISIBLE);
                    f_relay_icon.setVisibility(View.VISIBLE);
                }
            }
            else // friend has no relay
            {
                // Log.d(TAG, "004");

                String get_pushurl_for_friend = get_pushurl_for_friend(fl.tox_public_key_string);

                if ((get_pushurl_for_friend != null) && (get_pushurl_for_friend.length() > "https:".length()))
                {
                    // friend has push support
                    f_relay_icon.setImageResource(R.drawable.circle_orange);
                    f_relay_icon.setVisibility(View.VISIBLE);
                }
                else
                {
                    if (fl.TOX_CONNECTION == 0)
                    {
                        f_status_icon.setImageResource(R.drawable.circle_red);
                    }
                    else
                    {
                        f_status_icon.setImageResource(R.drawable.circle_green);
                    }
                }
            }

            try
            {
                if (fl.TOX_CONNECTION_real == TOX_CONNECTION_NONE.value)
                {
                    avatar.setBorderColor(Color.parseColor("#40000000"));
                }
                else if (fl.TOX_CONNECTION_real == TOX_CONNECTION_TCP.value)
                {
                    avatar.setBorderColor(Color.parseColor("#FFCE00"));
                }
                else // UDP
                {
                    avatar.setBorderColor(Color.parseColor("#04B431"));
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
            // ------ now fill with data ------
        }
        else if (friend_entry.getType() == 2)
        {
            f_status_icon.setVisibility(View.INVISIBLE);
            f_relay_icon.setVisibility(View.INVISIBLE);
            avatar.setBorderColor(Color.parseColor("#40000000"));
            textViewName.setText(friend_entry.getName());
        }

        return view;
    }
}

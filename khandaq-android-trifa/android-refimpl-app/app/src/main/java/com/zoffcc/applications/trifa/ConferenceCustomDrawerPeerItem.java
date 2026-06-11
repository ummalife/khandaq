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
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.mikepenz.materialdrawer.model.AbstractBadgeableDrawerItem;
import com.zoffcc.applications.sorm.FriendList;

import java.util.List;

import static com.zoffcc.applications.trifa.HelperFriend.friendlist_has_avatar_for_pubkey;
import static com.zoffcc.applications.trifa.HelperGeneric.StringSignature2;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.hash_to_bucket;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONFERENCE_CHAT_DRAWER_ICON_CORNER_RADIUS_IN_PX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FRIEND_AVATAR_FILENAME;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_PREFIX;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class ConferenceCustomDrawerPeerItem extends AbstractBadgeableDrawerItem<ConferenceCustomDrawerPeerItem>
{
    private static final String TAG = "trifa.ConfPeerDItem";
    ImageView icon = null;
    String peer_pubkey = null;
    String group_id = null;
    String peer_display_name = null;
    boolean have_avatar_for_pubkey = false;
    boolean peer_online = false;

    ConferenceCustomDrawerPeerItem setPeerOnline(final boolean online)
    {
        peer_online = online;
        return this;
    }

    ConferenceCustomDrawerPeerItem(boolean have_avatar_for_pubkey, String peer_pubkey)
    {
        this(have_avatar_for_pubkey, peer_pubkey, null, null);
    }

    ConferenceCustomDrawerPeerItem(boolean have_avatar_for_pubkey, String peer_pubkey, String group_id,
                                   String peer_display_name)
    {
        this.peer_pubkey = peer_pubkey;
        this.group_id = group_id;
        this.peer_display_name = peer_display_name;
        this.have_avatar_for_pubkey = have_avatar_for_pubkey;
    }

    @Override
    public void bindView(ViewHolder viewHolder, List payloads)
    {
        super.bindView(viewHolder, payloads);

        Context c = viewHolder.itemView.getContext();
        try
        {
            final TextView description = viewHolder.itemView.findViewById(
                    com.mikepenz.materialdrawer.R.id.material_drawer_description);
            if (description != null)
            {
                description.setTextColor(peer_online ? Color.parseColor("#64B5F6") : Color.parseColor("#9E9E9E"));
            }
        }
        catch (Exception ignored)
        {
        }
        viewHolder.itemView.setOnLongClickListener(new View.OnLongClickListener()
        {
            @Override
            public boolean onLongClick(View v)
            {
                try
                {
                    if ((group_id != null) && (peer_pubkey != null))
                    {
                        GroupMemberActions.showActionMenu(v.getContext(), group_id, peer_pubkey, peer_display_name);
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                return true;
            }
        });
        // Log.i(TAG, "bindView:context=" + c);
        icon = (ImageView) viewHolder.itemView.findViewById(com.mikepenz.materialdrawer.R.id.material_drawer_icon);
        if (icon != null)
        {
            icon.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams parameter = new LinearLayout.LayoutParams((int) dp2px(36), (int) dp2px(36));
            parameter.setMargins(parameter.leftMargin, (int) dp2px(6), (int) dp2px(10), (int) dp2px(0));
            icon.setLayoutParams(parameter);
            icon.setBackground(null);
            ChatBubbleUiHelper.fill_drawer_peer_icon(icon, peer_pubkey, peer_display_name);
        }
    }
}
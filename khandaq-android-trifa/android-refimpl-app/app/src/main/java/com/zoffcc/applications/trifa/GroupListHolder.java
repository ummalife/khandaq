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

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.GroupDB;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.FriendListFragment.fl_loading_progressbar;
import static com.zoffcc.applications.trifa.HelperFriend.delete_friend_all_files;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.delete_group;
import static com.zoffcc.applications.trifa.HelperGroup.delete_group_all_files;
import static com.zoffcc.applications.trifa.HelperGroup.delete_group_all_messages;
import static com.zoffcc.applications.trifa.HelperGroup.format_group_list_status_subtitle;
import static com.zoffcc.applications.trifa.HelperGroup.is_group_we_left;
import static com.zoffcc.applications.trifa.HelperGroup.set_group_group_we_left;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.MainActivity.PREF__dark_mode_pref;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_disconnect;
import static com.zoffcc.applications.trifa.HelperGroup.get_effective_group_title;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_is_connected;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_leave;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FL_NOTIFICATION_ICON_ALPHA_NOT_SELECTED;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FL_NOTIFICATION_ICON_ALPHA_SELECTED;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FL_NOTIFICATION_ICON_SIZE_DP_NOT_SELECTED;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FL_NOTIFICATION_ICON_SIZE_DP_SELECTED;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class GroupListHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener
{
    private static final String TAG = "trifa.GroupLstHldr";

    private GroupDB group;
    private Context context;

    private TextView textView;
    private TextView statusText;
    private TextView unread_count;
    private de.hdodenhof.circleimageview.CircleImageView avatar;
    private ImageView imageView;
    private ImageView imageView2;
    private ImageView f_notification;
    private ViewGroup f_conf_container_parent;
    private TextView chatTimeView;

    synchronized static void remove_progress_dialog()
    {
        try
        {
            fl_loading_progressbar.setVisibility(View.GONE);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public GroupListHolder(View itemView, Context c)
    {
        super(itemView);

        // Log.i(TAG, "FriendListHolder");

        this.context = c;

        f_conf_container_parent = (ViewGroup) itemView.findViewById(R.id.chat_list_row);
        chatTimeView = (TextView) itemView.findViewById(R.id.f_chat_time);

        textView = (TextView) itemView.findViewById(R.id.f_name);
        statusText = (TextView) itemView.findViewById(R.id.f_status_message);
        unread_count = (TextView) itemView.findViewById(R.id.f_unread_count);
        avatar = (de.hdodenhof.circleimageview.CircleImageView) itemView.findViewById(R.id.f_avatar_icon);
        imageView = (ImageView) itemView.findViewById(R.id.f_status_icon);
        imageView2 = (ImageView) itemView.findViewById(R.id.f_user_status_icon);
        f_notification = (ImageView) itemView.findViewById(R.id.f_notification);
    }

    public void bindFriendList(GroupDB fl)
    {
        if (fl == null)
        {
            textView.setText("*ERROR*");
            statusText.setText("fl == null");
            return;
        }

        // Log.i(TAG, "bindFriendList:" + fl.tox_conference_number);

        this.group = fl;

        long group_number = tox_group_by_groupid__wrapper(fl.group_identifier);

        itemView.setOnClickListener(this);
        itemView.setOnLongClickListener(this);

        if (fl.notification_silent)
        {
            final Drawable d_notification = new IconicsDrawable(context).
                    icon(GoogleMaterial.Icon.gmd_notifications_off).
                    color(context.getResources().
                            getColor(R.color.icon_colors)).
                    alpha(FL_NOTIFICATION_ICON_ALPHA_NOT_SELECTED).sizeDp(FL_NOTIFICATION_ICON_SIZE_DP_NOT_SELECTED);
            f_notification.setImageDrawable(d_notification);
            f_notification.setOnClickListener(this);
        }
        else
        {
            final Drawable d_notification = new IconicsDrawable(context).
                    icon(GoogleMaterial.Icon.gmd_notifications_active).
                    color(context.getResources().
                            getColor(R.color.icon_colors)).
                    alpha(FL_NOTIFICATION_ICON_ALPHA_SELECTED).sizeDp(FL_NOTIFICATION_ICON_SIZE_DP_SELECTED);
            f_notification.setImageDrawable(d_notification);
            f_notification.setOnClickListener(this);
        }

        final Drawable group_icon;
        if (fl.privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
        {
            group_icon = new IconicsDrawable(context).icon(GoogleMaterial.Icon.gmd_public).
                    color(context.getResources().getColor(R.color.tg_chat_preview)).sizeDp(28);
        }
        else
        {
            group_icon = new IconicsDrawable(context).icon(GoogleMaterial.Icon.gmd_security).
                    color(context.getResources().getColor(R.color.tg_chat_preview)).sizeDp(28);
        }
        avatar.setImageDrawable(group_icon);

        try
        {
            statusText.setText(format_group_list_status_subtitle(context, fl.group_identifier));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            statusText.setText("#" + fl.tox_group_number);
        }

        try
        {
            if (is_group_we_left(fl.group_identifier))
            {
                imageView.setImageResource(R.drawable.circle_pink);
            }
            else
            {
                if (fl.group_active)
                {
                    if (tox_group_is_connected(tox_group_by_groupid__wrapper(fl.group_identifier)) == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value)
                    {
                        imageView.setImageResource(R.drawable.circle_green);
                    }
                    else
                    {
                        imageView.setImageResource(R.drawable.circle_orange);
                    }
                }
                else
                {
                    imageView.setImageResource(R.drawable.circle_red);
                }
            }
        }
        catch(Exception ignored)
        {
        }

        String group_title = get_effective_group_title(group_number, fl.group_identifier);
        textView.setText(group_title);

        imageView2.setVisibility(View.INVISIBLE);

        int new_messages_count = 0;
        try
        {
            new_messages_count = orma.selectFromGroupMessage().
                    group_identifierEq(fl.group_identifier.toLowerCase()).is_newEq(true).count();
        }
        catch (Exception ignored)
        {
        }
        ChatListUiHelper.bind_unread_badge(unread_count, new_messages_count);
        apply_telegram_chat_row(fl);
    }

    private void apply_telegram_chat_row(final GroupDB fl)
    {
        ChatListUiHelper.prepare_telegram_row(f_conf_container_parent, textView, statusText, chatTimeView,
                                              f_notification, imageView, imageView2, null, null);
        statusText.setText(ChatListUiHelper.group_last_message_preview(context, fl.group_identifier));
        ChatListUiHelper.bind_preview_text_color(statusText,
                ChatDraftHelper.has_group_draft(fl.group_identifier));
        if (chatTimeView != null)
        {
            chatTimeView.setText(ChatListUiHelper.format_chat_list_time(context,
                    ChatListUiHelper.group_last_message_timestamp_ms(fl.group_identifier)));
        }
        try
        {
            avatar.setBorderWidth(0);
        }
        catch (Exception ignored)
        {
        }
    }

    @Override
    public void onClick(View v)
    {
        Log.i(TAG, "onClick");
        try
        {
            if (v.equals(f_notification))
            {
                if (!this.group.notification_silent)
                {
                    this.group.notification_silent = true;
                    orma.updateGroupDB().group_identifierEq(this.group.group_identifier.toLowerCase()).
                            notification_silent(this.group.notification_silent).execute();

                    final Drawable d_notification = new IconicsDrawable(context).
                            icon(GoogleMaterial.Icon.gmd_notifications_off).
                            color(context.getResources().
                                    getColor(R.color.icon_colors)).
                            alpha(FL_NOTIFICATION_ICON_ALPHA_NOT_SELECTED).sizeDp(
                            FL_NOTIFICATION_ICON_SIZE_DP_NOT_SELECTED);
                    f_notification.setImageDrawable(d_notification);
                }
                else
                {
                    this.group.notification_silent = false;
                    orma.updateGroupDB().group_identifierEq(this.group.group_identifier.toLowerCase()).
                            notification_silent(this.group.notification_silent).execute();

                    final Drawable d_notification = new IconicsDrawable(context).
                            icon(GoogleMaterial.Icon.gmd_notifications_active).
                            color(context.getResources().
                                    getColor(R.color.icon_colors)).
                            alpha(FL_NOTIFICATION_ICON_ALPHA_SELECTED).sizeDp(FL_NOTIFICATION_ICON_SIZE_DP_SELECTED);
                    f_notification.setImageDrawable(d_notification);
                }
            }
            else
            {
                try
                {
                    fl_loading_progressbar.setVisibility(View.VISIBLE);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                if (this.group.privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
                {
                    Intent intent = new Intent(v.getContext(), GroupMessageListActivity.class);
                    intent.putExtra("group_id", this.group.group_identifier);
                    v.getContext().startActivity(intent);
                }
                else
                {
                    Intent intent = new Intent(v.getContext(), GroupMessageListActivity.class);
                    intent.putExtra("group_id", this.group.group_identifier);
                    v.getContext().startActivity(intent);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "onClick:EE:" + e.getMessage());
        }
    }

    @Override
    public boolean onLongClick(final View v)
    {
        Log.i(TAG, "onLongClick");

        final GroupDB f2 = this.group;

        PopupMenu menu = new PopupMenu(v.getContext(), v);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener()
        {
            @Override
            public boolean onMenuItemClick(MenuItem item)
            {
                int id = item.getItemId();
                switch (id)
                {
                    case R.id.item_info:
                        // show group info page -----------------
                        Intent intent = new Intent(v.getContext(), GroupInfoActivity.class);
                        intent.putExtra("group_id", f2.group_identifier);
                        v.getContext().startActivity(intent);
                        // show group info page -----------------
                        break;
                    case R.id.item_leave:
                        // leave group -----------------
                        show_confirm_group_leave_dialog(v, f2);
                        // leave group -----------------
                        break;
                    case R.id.item_dummy01:
                        break;
                    case R.id.item_delete:
                        // delete group -----------------
                        show_confirm_group_del_dialog(v, f2);
                        // delete group -----------------
                        break;
                }
                return true;
            }
        });
        menu.inflate(R.menu.menu_grouplist_item);

        menu.show();

        return true;

    }

    public void show_confirm_group_leave_dialog(final View view, final GroupDB f2)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext());
        builder.setTitle("Leave Group?");
        builder.setMessage("Do you want to leave this Group?");

        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener()
        {
            @Override
            public void onClick(DialogInterface dialog, int which)
            {
                if (f2.group_identifier != null)
                {
                    final long group_num = tox_group_by_groupid__wrapper(f2.group_identifier);
                    tox_group_disconnect(group_num);
                    update_savedata_file_wrapper(); // after leaving a conference
                    set_group_group_we_left(f2.group_identifier);
                }

                Runnable myRunnable2 = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            try
                            {
                                if (MainActivity.friend_list_fragment != null)
                                {
                                    // reload friendlist
                                    MainActivity.friend_list_fragment.add_all_friends_clear(0);
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                            Log.i(TAG, "onMenuItemClick:8:EE:" + e.getMessage());
                        }
                    }
                };

                // TODO: use own handler
                if (main_handler_s != null)
                {
                    main_handler_s.post(myRunnable2);
                }
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    public void show_confirm_group_del_dialog(final View view, final GroupDB f2)
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext());
        builder.setTitle("Delete Group?");
        builder.setMessage("Do you want to delete this Group including all Messages?");

        builder.setNegativeButton("Cancel", null);
        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener()
        {
            @Override
            public void onClick(DialogInterface dialog, int which)
            {
                if (f2.group_identifier != null)
                {
                    final long group_num = tox_group_by_groupid__wrapper(f2.group_identifier);
                    tox_group_leave(group_num, "bye");
                    update_savedata_file_wrapper(); // after deleteing a conference
                }

                Log.i(TAG, "onMenuItemClick:info:33");
                delete_group_all_files(f2.group_identifier);
                delete_group_all_messages(f2.group_identifier);
                delete_group(f2.group_identifier);
                Log.i(TAG, "onMenuItemClick:info:34");

                Runnable myRunnable2 = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            try
                            {
                                if (MainActivity.friend_list_fragment != null)
                                {
                                    // reload friendlist
                                    // TODO: only remove 1 item, don't clear all!! this can crash
                                    MainActivity.friend_list_fragment.add_all_friends_clear(0);
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                            Log.i(TAG, "onMenuItemClick:8:EE:" + e.getMessage());
                        }
                    }
                };

                // TODO: use own handler
                if (main_handler_s != null)
                {
                    main_handler_s.post(myRunnable2);
                }
            }
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

}

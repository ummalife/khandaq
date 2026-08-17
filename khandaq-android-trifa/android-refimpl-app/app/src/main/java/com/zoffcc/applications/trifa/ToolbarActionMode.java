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
import android.os.Build;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.view.ActionMode;
import androidx.core.view.MenuItemCompat;

import static com.zoffcc.applications.trifa.HelperConference.copy_selected_conference_messages;
import static com.zoffcc.applications.trifa.HelperConference.copy_selected_group_messages;
import static com.zoffcc.applications.trifa.HelperConference.delete_selected_conference_messages;
import static com.zoffcc.applications.trifa.HelperConference.delete_selected_group_messages;
import static com.zoffcc.applications.trifa.HelperMessage.copy_selected_messages;
import static com.zoffcc.applications.trifa.HelperMessage.delete_selected_messages;
import static com.zoffcc.applications.trifa.HelperMessage.save_selected_messages;
import static com.zoffcc.applications.trifa.HelperMessage.show_select_conference_message_info;
import static com.zoffcc.applications.trifa.HelperMessage.show_select_group_message_info;
import static com.zoffcc.applications.trifa.HelperMessage.show_select_message_info;
import static com.zoffcc.applications.trifa.HelperReply.replyToSelectedDirectMessage;
import static com.zoffcc.applications.trifa.HelperReply.replyToSelectedGroupMessage;
import static com.zoffcc.applications.trifa.MainActivity.selected_conference_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages_incoming_file;
import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages_text_only;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages_incoming_file;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages_text_only;
import static com.zoffcc.applications.trifa.MessageListActivity.amode;
import static com.zoffcc.applications.trifa.MessageListActivity.amode_info_menu_item;
import static com.zoffcc.applications.trifa.MessageListActivity.amode_save_menu_item;

public class ToolbarActionMode implements ActionMode.Callback
{
    private static final String TAG = "trifa.ToolbarActionMode";

    private Context context;
    private boolean action_active = false;

    public ToolbarActionMode(Context context)
    {
        this.context = context;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu)
    {
        Log.i(TAG, "onCreateActionMode");
        mode.getMenuInflater().inflate(R.menu.toolbar_message_activity, menu); // Inflate the menu over action mode
        action_active = false;
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu)
    {
        // Log.i(TAG, "onPrepareActionMode");

        //Sometimes the menu will not be visible so for that we need to set their visibility manually in this method
        //So here show action menu according to SDK Levels
        if (Build.VERSION.SDK_INT < 11)
        {
            MenuItemCompat.setShowAsAction(menu.findItem(R.id.action_delete), MenuItemCompat.SHOW_AS_ACTION_NEVER);
            MenuItemCompat.setShowAsAction(menu.findItem(R.id.action_copy), MenuItemCompat.SHOW_AS_ACTION_NEVER);
        }
        else
        {
            menu.findItem(R.id.action_delete).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.findItem(R.id.action_copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.findItem(R.id.action_reply).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            final MenuItem forwardItem = menu.findItem(R.id.action_forward);
            if (forwardItem != null)
            {
                forwardItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            }
        }

        final MenuItem replyItem = menu.findItem(R.id.action_reply);
        if (replyItem != null)
        {
            replyItem.setVisible(HelperReply.canReplyToCurrentSelection());
        }

        // KHANDAQ (#9): "Изменить" — exactly one OWN text message, addressable, inside the window
        final MenuItem editItem = menu.findItem(R.id.action_edit);
        if (editItem != null)
        {
            editItem.setVisible(HelperMessageEdit.canEditCurrentSelection());
        }

        // KHANDAQ (#192): "Реакция" — exactly one message that can hold a reaction
        final MenuItem reactItem = menu.findItem(R.id.action_react);
        if (reactItem != null)
        {
            reactItem.setVisible(HelperMessageReaction.canReactToCurrentSelection());
        }

        // KHANDAQ (#Saved): hide "В избранное" (forward-to-self) when we are ALREADY inside the
        // Favorites/Saved chat — re-saving a Saved message into Saved is meaningless. Only the 1:1 view
        // can be the Favorites chat, so gate on the same null-checks the forward click-handler uses.
        final MenuItem forwardVisItem = menu.findItem(R.id.action_forward);
        if (forwardVisItem != null)
        {
            boolean inFavoritesChat = false;
            try
            {
                if (MainActivity.message_list_activity != null
                        && MainActivity.group_message_list_activity == null
                        && MainActivity.conference_message_list_activity == null)
                {
                    inFavoritesChat = FavoritesChatHelper.isFavoritesChat(
                            MainActivity.message_list_activity.get_friend_pubkey());
                }
            }
            catch (Exception ignored)
            {
            }
            forwardVisItem.setVisible(!inFavoritesChat);
        }

        action_active = false;
        return true;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item)
    {
        switch (item.getItemId())
        {
            case R.id.action_delete:
                action_active = true;
                // Toast.makeText(context, "You selected Delete menu.", Toast.LENGTH_SHORT).show(); // Show toast
                if ((selected_conference_messages.isEmpty()) && (MainActivity.conference_message_list_activity == null))
                {

                    if ((selected_group_messages.isEmpty()) && (MainActivity.group_message_list_activity == null))
                    {
                        // normal 1:1 chat view — KHANDAQ (#205): offer «у всех»/«у меня» when the
                        // selection has an own message; the dialog owns mode.finish(), so return early.
                        showDeleteScopeFor1on1(mode);
                        return true;
                    }
                    else
                    {
                        // group chat view
                        delete_selected_group_messages(context, true, "deleting Messages ...");
                    }
                }
                else
                {
                    // conference view
                    delete_selected_conference_messages(context, true, "deleting Messages ...");
                }
                mode.finish(); // Finish action mode
                break;

            case R.id.action_forward:
                action_active = true;
                if ((selected_group_messages.isEmpty()) && (MainActivity.group_message_list_activity == null))
                {
                    FavoritesChatHelper.forwardSelectedDirectMessages(context);
                }
                else if ((selected_conference_messages.isEmpty())
                        && (MainActivity.conference_message_list_activity == null))
                {
                    FavoritesChatHelper.forwardSelectedGroupMessages(context);
                }
                try
                {
                    if (MainActivity.message_list_fragment != null)
                    {
                        MainActivity.message_list_fragment.adapter.redraw_all_items();
                    }
                    if (MainActivity.group_message_list_fragment != null)
                    {
                        MainActivity.group_message_list_fragment.adapter.redraw_all_items();
                    }
                }
                catch (Exception ignored)
                {
                }
                mode.finish();
                break;

            case R.id.action_reply:
                action_active = true;
                if ((selected_group_messages.isEmpty()) && (MainActivity.group_message_list_activity == null))
                {
                    replyToSelectedDirectMessage(context);
                }
                else if ((selected_conference_messages.isEmpty())
                        && (MainActivity.conference_message_list_activity == null))
                {
                    replyToSelectedGroupMessage(context);
                }
                mode.finish();
                break;

            case R.id.action_edit:
                // KHANDAQ (#9): prefill the input with the old text; the send button saves the edit
                action_active = true;
                if ((selected_group_messages.isEmpty()) && (MainActivity.group_message_list_activity == null))
                {
                    HelperMessageEdit.editSelectedDirectMessage(context);
                }
                else if ((selected_conference_messages.isEmpty())
                        && (MainActivity.conference_message_list_activity == null))
                {
                    HelperMessageEdit.editSelectedGroupMessage(context);
                }
                mode.finish();
                break;

            case R.id.action_react:
                // KHANDAQ (#192): quick-bar dialog, pick toggles the own reaction.
                // Deliberately DO NOT set action_active=true: reactSelected*Message captures the
                // target message synchronously into the picker callback, so we let
                // onDestroyActionMode clear the selection and redraw. Leaving the row selected
                // made its next long-press a silent no-op until the chat was re-entered.
                if ((selected_group_messages.isEmpty()) && (MainActivity.group_message_list_activity == null))
                {
                    HelperMessageReaction.reactSelectedDirectMessage(context);
                }
                else if ((selected_conference_messages.isEmpty())
                        && (MainActivity.conference_message_list_activity == null))
                {
                    HelperMessageReaction.reactSelectedGroupMessage(context);
                }
                mode.finish();
                break;

            case R.id.action_copy:
                action_active = true;
                // Toast.makeText(context, "You selected Copy menu.", Toast.LENGTH_SHORT).show(); // Show toast
                if ((selected_conference_messages.isEmpty()) && (MainActivity.conference_message_list_activity == null))
                {
                    if ((selected_group_messages.isEmpty()) && (MainActivity.group_message_list_activity == null))
                    {
                        // normal chat view
                        copy_selected_messages(context);
                    }
                    else
                    {
                        // group chat view
                        copy_selected_group_messages(context);
                    }
                }
                else
                {
                    // conference view
                    copy_selected_conference_messages(context);
                }
                mode.finish(); // Finish action mode
                break;

            case R.id.action_save:
                action_active = true;
                // Toast.makeText(context, "You selected Copy menu.", Toast.LENGTH_SHORT).show(); // Show toast
                if ((selected_group_messages.isEmpty()) && (MainActivity.group_message_list_activity == null))
                {
                    // normal chat view
                    save_selected_messages(context);
                }
                else
                {
                    // group chat view
                    // TODO: write me
                }

                mode.finish(); // Finish action mode
                break;

            case R.id.action_info:
                action_active = true;

                if ((selected_conference_messages.isEmpty()) && (MainActivity.conference_message_list_activity == null))
                {
                    if ((selected_group_messages.isEmpty()) && (MainActivity.group_message_list_activity == null))
                    {
                        // normal chat view
                        show_select_message_info(context);
                    }
                    else
                    {
                        // group chat view
                        show_select_group_message_info(context);
                    }
                }
                else
                {
                    // conference view
                    show_select_conference_message_info(context);
                }
                mode.finish(); // Finish action mode
                break;
        }
        return false;
    }

    // KHANDAQ (#205): Telegram-style delete scope for a 1:1 chat. If the selection has at least one
    // OWN message in a real (non-Favorites) chat, ask «Удалить у всех» (retract on the peer) vs
    // «Удалить у меня» (local only). Otherwise (incoming-only / Saved Messages) delete locally.
    private void showDeleteScopeFor1on1(final ActionMode mode)
    {
        boolean hasOwn = false;
        try
        {
            for (Long mid : selected_messages)
            {
                com.zoffcc.applications.sorm.Message m = TrifaToxService.orma.selectFromMessage().idEq(mid).get(0);
                if (m != null && m.direction == 1 && m.tox_friendpubkey != null
                    && !FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey))
                {
                    hasOwn = true;
                    break;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        if (!hasOwn)
        {
            // only incoming / Saved Messages — local removal is the only meaningful action
            delete_selected_messages(context, true, false, "deleting Messages ...");
            mode.finish();
            return;
        }

        final CharSequence[] items = new CharSequence[]{
                context.getString(R.string.delete_for_everyone),
                context.getString(R.string.delete_for_me)};

        try
        {
            new androidx.appcompat.app.AlertDialog.Builder(context).setItems(items,
                    new android.content.DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which)
                        {
                            // 0 = у всех (send_to_peer=true), 1 = у меня (local only)
                            delete_selected_messages(context, true, false, "deleting Messages ...", which == 0);
                            mode.finish();
                        }
                    }).setNegativeButton(android.R.string.cancel, null).show();
        }
        catch (Exception e)
        {
            // fall back to a plain retract-for-both if the dialog can't be shown
            delete_selected_messages(context, true, false, "deleting Messages ...");
            mode.finish();
        }
    }

    @Override
    public void onDestroyActionMode(ActionMode mode)
    {
        try
        {
            // KHANDAQ: ALWAYS clear the selection + redraw when the action mode ends. This used to be
            // gated on action_active==false, so Reply/Edit/Copy/Save/Info (which set it true) left their
            // rows in selected_messages — the translucent-teal highlight stuck to the row AND the next
            // plain tap silently re-entered multi-select on a phantom, still-non-empty selection.
            // Delete/Forward already refresh the list themselves; a second redraw here is cheap.
            {
                selected_conference_messages.clear();

                selected_group_messages.clear();
                selected_group_messages_incoming_file.clear();
                selected_group_messages_text_only.clear();

                selected_messages.clear();
                selected_messages_incoming_file.clear();
                selected_messages_text_only.clear();

                try
                {
                    if (MainActivity.conference_message_list_fragment != null)
                    {
                        // need to redraw all items again here, to remove the selections
                        MainActivity.conference_message_list_fragment.adapter.redraw_all_items();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    if (MainActivity.group_message_list_fragment != null)
                    {
                        // KHANDAQ (user video 17.08): selection-only repaint, same reason as the 1:1
                        // branch below — a full notifyDataSetChanged reloads every visible photo.
                        MainActivity.group_message_list_fragment.adapter.redraw_selection_only();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                try
                {
                    if (MainActivity.message_list_fragment != null)
                    {
                        // KHANDAQ (user video 17.08): repaint the selection highlight ONLY. This used
                        // to be redraw_all_items() (notifyDataSetChanged), which reshuffles holders in
                        // an adapter without stable ids and makes every visible photo re-decode from
                        // disk behind a spinner — the "photos reload when I cancel the selection" the
                        // user recorded. The selected_* sets were cleared just above, so the payload
                        // bind paints every row unselected.
                        MainActivity.message_list_fragment.adapter.redraw_selection_only();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            try
            {
                if (amode != null)
                {
                    amode = null;
                    amode_save_menu_item = null;
                    amode_info_menu_item = null;
                }
            }
            catch (Exception ignored)
            {
            }

            try
            {
                if (GroupMessageListActivity.amode != null)
                {
                    GroupMessageListActivity.amode = null;
                    GroupMessageListActivity.amode_save_menu_item = null;
                    GroupMessageListActivity.amode_info_menu_item = null;
                }
            }
            catch (Exception ignored)
            {
            }

            try
            {
                if (ConferenceMessageListActivity.amode != null)
                {
                    ConferenceMessageListActivity.amode = null;
                    ConferenceMessageListActivity.amode_save_menu_item = null;
                    ConferenceMessageListActivity.amode_info_menu_item = null;
                }
            }
            catch (Exception ignored)
            {
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
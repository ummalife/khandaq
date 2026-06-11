package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupMessage;

import androidx.appcompat.app.AlertDialog;

import static com.zoffcc.applications.trifa.FriendListHolder.show_messagelist_acticvity_for_friend;
import static com.zoffcc.applications.trifa.HelperFriend.add_friend_real;
import static com.zoffcc.applications.trifa.HelperFriend.lookup_friendlist_by_pubkey;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.get_group_peernum_from_peer_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.group_notification_silent_peer_get;
import static com.zoffcc.applications.trifa.HelperGroup.group_notification_silent_peer_set;
import static com.zoffcc.applications.trifa.HelperGroup.insert_into_group_message_db;
import static com.zoffcc.applications.trifa.HelperGroup.is_likely_automated_test_peer;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_groupmessagelist;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_peer_in_db;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_mod_kick_peer;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_private_message_by_peerpubkey;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.open_group_peer_info_activity;

final class GroupMemberActions
{
    private GroupMemberActions()
    {
    }

    static void showActionMenu(final Context context, final String group_id, final String peer_pubkey,
                               final String display_name)
    {
        if ((context == null) || (group_id == null) || (peer_pubkey == null))
        {
            return;
        }

        final long group_num = tox_group_by_groupid__wrapper(group_id);
        if (group_num < 0)
        {
            return;
        }

        final FriendList friend = lookup_friendlist_by_pubkey(peer_pubkey);
        final boolean is_friend = friend != null;
        final boolean is_self;
        try
        {
            is_self = peer_pubkey.equalsIgnoreCase(tox_group_self_get_public_key(group_num));
        }
        catch (Exception e)
        {
            return;
        }

        if (is_self)
        {
            return;
        }

        final int self_role = tox_group_self_get_role(group_num);
        int peer_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value;
        try
        {
            peer_role = tox_group_peer_get_role(group_num, get_group_peernum_from_peer_pubkey(group_id, peer_pubkey));
        }
        catch (Exception ignored)
        {
        }

        final boolean can_kick = GroupPeerInfoActivity_canKickPeer(self_role, peer_role, false);
        final boolean muted = group_notification_silent_peer_get(group_id, peer_pubkey);
        final boolean probable_bot = is_likely_automated_test_peer(display_name, peer_pubkey);

        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> actions = new java.util.ArrayList<>();

        labels.add(context.getString(R.string.group_member_action_info));
        actions.add(() -> open_group_peer_info_activity(context, peer_pubkey, group_id));

        labels.add(context.getString(R.string.group_member_action_private_msg));
        actions.add(() -> showPrivateMessageDialog(context, group_id, peer_pubkey, display_name));

        if (is_friend)
        {
            labels.add(context.getString(R.string.group_member_action_open_chat));
            actions.add(() -> show_messagelist_acticvity_for_friend(context, peer_pubkey));
        }
        else
        {
            labels.add(context.getString(R.string.group_member_action_add_friend));
            actions.add(() -> add_friend_real(peer_pubkey.toUpperCase()));
        }

        labels.add(context.getString(R.string.group_member_action_copy_id));
        actions.add(() -> copyToClipboard(context, peer_pubkey));

        labels.add(muted
                   ? context.getString(R.string.group_member_action_unmute)
                   : context.getString(R.string.group_member_action_mute));
        actions.add(() ->
        {
            group_notification_silent_peer_set(group_id, peer_pubkey, !muted);
            update_group_in_groupmessagelist(group_id);
            display_toast(context.getString(
                    muted ? R.string.group_member_action_unmute_done : R.string.group_member_action_mute_done), false,
                    200);
        });

        if (can_kick)
        {
            labels.add(context.getString(R.string.group_member_action_kick));
            actions.add(() -> confirmKickPeer(context, group_id, group_num, peer_pubkey, display_name));
        }

        if (probable_bot)
        {
            labels.add(context.getString(R.string.group_member_action_bot_info));
            actions.add(() -> showBotInfoDialog(context, peer_pubkey));
        }

        final CharSequence[] items = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(context).setTitle(display_name).setItems(items, new DialogInterface.OnClickListener()
        {
            @Override
            public void onClick(DialogInterface dialog, int which)
            {
                try
                {
                    actions.get(which).run();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }).show();
    }

    private static void showPrivateMessageDialog(final Context context, final String group_id,
                                                final String peer_pubkey, final String display_name)
    {
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(context.getString(R.string.group_member_action_private_msg_hint));

        new AlertDialog.Builder(context).
                setTitle(context.getString(R.string.group_member_action_private_msg_to, display_name)).
                setView(input).
                setPositiveButton(context.getString(R.string.group_member_action_send), new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        sendPrivateGroupMessage(context, group_id, peer_pubkey, input.getText().toString().trim());
                    }
                }).
                setNegativeButton(context.getString(R.string.cancel), null).
                show();
    }

    private static void sendPrivateGroupMessage(final Context context, final String group_id,
                                                final String peer_pubkey, final String text)
    {
        if (text.isEmpty())
        {
            return;
        }

        try
        {
            final int res = tox_group_send_private_message_by_peerpubkey(tox_group_by_groupid__wrapper(group_id),
                                                                       peer_pubkey, 0, text);
            if (res == 0)
            {
                GroupMessage m = new GroupMessage();
                m.is_new = false;
                m.tox_group_peer_pubkey = tox_group_self_get_public_key(
                        tox_group_by_groupid__wrapper(group_id)).toUpperCase();
                m.direction = 1;
                m.TOX_MESSAGE_TYPE = 0;
                m.read = true;
                m.tox_group_peername = null;
                m.sent_privately_to_tox_group_peer_pubkey = peer_pubkey;
                m.private_message = 1;
                m.group_identifier = group_id;
                m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
                m.sent_timestamp = System.currentTimeMillis();
                m.rcvd_timestamp = System.currentTimeMillis();
                m.text = text;
                m.was_synced = false;
                m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
                insert_into_group_message_db(m, true);
                display_toast(context.getString(R.string.group_member_action_private_msg_sent), false, 200);
            }
            else
            {
                Toast.makeText(context, R.string.group_member_action_private_msg_failed, Toast.LENGTH_SHORT).show();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Toast.makeText(context, R.string.group_member_action_private_msg_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static void copyToClipboard(final Context context, final String text)
    {
        try
        {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("tox_pubkey", text));
            display_toast(context.getString(R.string.group_member_action_copy_done), false, 200);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static void confirmKickPeer(final Context context, final String group_id, final long group_num,
                                        final String peer_pubkey, final String display_name)
    {
        new AlertDialog.Builder(context).
                setTitle(R.string.group_peer_kick_confirm_title).
                setMessage(context.getString(R.string.group_peer_kick_confirm_message, display_name)).
                setPositiveButton(R.string.layout___delete, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        kickPeer(context, group_id, group_num, peer_pubkey, display_name);
                    }
                }).
                setNegativeButton(R.string.cancel, null).
                show();
    }

    private static void kickPeer(final Context context, final String group_id, final long group_num,
                                 final String peer_pubkey, final String display_name)
    {
        try
        {
            final long peer_num = get_group_peernum_from_peer_pubkey(group_id, peer_pubkey);
            if (peer_num < 0)
            {
                Toast.makeText(context, R.string.group_peer_kick_failed, Toast.LENGTH_LONG).show();
                return;
            }

            final int result = tox_group_mod_kick_peer(group_num, peer_num);
            update_savedata_file_wrapper();
            if (result >= 0)
            {
                update_group_peer_in_db(group_num, group_id, peer_num, peer_pubkey,
                                        ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value);
                update_group_in_groupmessagelist(group_id);
                Toast.makeText(context, context.getString(R.string.group_peer_kick_success, display_name),
                               Toast.LENGTH_LONG).show();
            }
            else
            {
                Toast.makeText(context, R.string.group_peer_kick_failed, Toast.LENGTH_LONG).show();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Toast.makeText(context, R.string.group_peer_kick_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static void showBotInfoDialog(final Context context, final String peer_pubkey)
    {
        new AlertDialog.Builder(context).
                setTitle(R.string.group_member_action_bot_info_title).
                setMessage(context.getString(R.string.group_member_action_bot_info_body, peer_pubkey)).
                setPositiveButton(R.string.group_member_action_copy_id, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        copyToClipboard(context, peer_pubkey);
                    }
                }).
                setNegativeButton(R.string.cancel, null).
                show();
    }

    // package-visible mirror of GroupPeerInfoActivity.canKickPeer for menus
    static boolean GroupPeerInfoActivity_canKickPeer(final int selfRole, final int peerRole, final boolean isSelfPeer)
    {
        if (isSelfPeer)
        {
            return false;
        }
        if (peerRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value)
        {
            return false;
        }
        if (selfRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value)
        {
            return true;
        }
        if (selfRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value)
        {
            return peerRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value
                   || peerRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
        }
        return false;
    }
}

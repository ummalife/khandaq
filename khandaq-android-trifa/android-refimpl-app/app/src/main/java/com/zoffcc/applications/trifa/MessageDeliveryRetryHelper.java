package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.util.Log;
import android.widget.Toast;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import static com.zoffcc.applications.trifa.HelperFriend.get_friend_msgv3_capability;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.tox_friend_resend_msgv3_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.tox_friend_send_message_wrapper;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_messageid;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_resend_count;
import static com.zoffcc.applications.trifa.HelperMessage.update_single_message;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.TrifaToxService.wakeup_tox_thread;

/**
 * Manual + automatic outbox retry for stale outgoing messages.
 */
public final class MessageDeliveryRetryHelper
{
    private static final String TAG = "trifa.MsgDeliveryRetry";

    private MessageDeliveryRetryHelper()
    {
    }

    static boolean isDirectFailed(final Message message)
    {
        if (message == null || message.TRIFA_MESSAGE_TYPE != TRIFA_MSG_TYPE_TEXT.value)
        {
            return false;
        }

        if (message.message_id <= -1 || message.read)
        {
            return false;
        }

        return message.send_retries >= MessageDeliveryWatchdog.MAX_SEND_RETRIES;
    }

    static boolean isGroupFailed(final GroupMessage message)
    {
        if (message == null || message.TRIFA_MESSAGE_TYPE != TRIFA_MSG_TYPE_TEXT.value)
        {
            return false;
        }

        if (message.sync_confirmations > 0)
        {
            return false;
        }

        if (message.sent_timestamp <= 0)
        {
            return false;
        }

        return (System.currentTimeMillis() - message.sent_timestamp)
                > MessageDeliveryWatchdog.DELIVERY_TIMEOUT_MS * MessageDeliveryWatchdog.MAX_SEND_RETRIES;
    }

    static boolean canRetryDirect(final Message message)
    {
        return isDirectFailed(message) || MessageStatusHelper.resolveDirectOutgoing(message)
                == MessageStatusHelper.OutgoingStatus.NOT_DELIVERED;
    }

    static boolean canRetryGroup(final GroupMessage message)
    {
        return isGroupFailed(message) || MessageStatusHelper.resolveGroupOutgoing(message)
                == MessageStatusHelper.OutgoingStatus.NOT_DELIVERED;
    }

    static void retryDirectMessage(final android.content.Context context, final Message message)
    {
        if (message == null || message.tox_friendpubkey == null)
        {
            return;
        }

        final long fn = tox_friend_by_public_key__wrapper(message.tox_friendpubkey);
        if (fn < 0 || is_friend_online_real(fn) == 0)
        {
            Toast.makeText(context, R.string.chat_ft_status_waiting_self_online, Toast.LENGTH_SHORT).show();
            TrifaToxService.requestManualReconnect();
            return;
        }

        try
        {
            message.send_retries = 0;
            orma.updateMessage().idEq(message.id).send_retries(0).execute();
            update_single_message(message, true);

            boolean didRetry = false;
            if ((message.msg_idv3_hash != null) && (message.msg_idv3_hash.length() >= ToxVars.TOX_HASH_LENGTH))
            {
                didRetry = tox_friend_resend_msgv3_wrapper(message);
            }
            else if (get_friend_msgv3_capability(message.tox_friendpubkey) != 1)
            {
                final MainActivity.send_message_result result = tox_friend_send_message_wrapper(
                        message.tox_friendpubkey, 0, message.text, (message.sent_timestamp / 1000));
                if (result != null && HelperFriend.is_dm_message_send_success(result.msg_num))
                {
                    message.message_id = result.msg_num;
                    message.resend_count++;
                    update_message_in_db_messageid(message);
                    update_message_in_db_resend_count(message);
                    didRetry = true;
                }
            }

            if (didRetry)
            {
                wakeup_tox_thread();
                Log.i(TAG, "retryDirect:id=" + message.id);
            }
            else
            {
                MessageDeliveryWatchdog.tick();
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "retryDirect:EE:" + e.getMessage());
        }
    }

    static void retryGroupMessage(final android.content.Context context, final GroupMessage message)
    {
        if (message == null || message.group_identifier == null)
        {
            return;
        }

        final long groupNum = HelperGroup.tox_group_by_groupid__wrapper(message.group_identifier);
        if (groupNum < 0)
        {
            Toast.makeText(context, R.string.chat_ft_status_waiting_self_online, Toast.LENGTH_SHORT).show();
            TrifaToxService.requestManualReconnect();
            return;
        }

        HelperGroup.schedule_group_message_resync(groupNum);
        if (HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX.equals(message.message_id_tox))
        {
            HelperGroup.flush_pending_group_messages(message.group_identifier);
        }
        wakeup_tox_thread();
        Log.i(TAG, "retryGroup:gid=" + message.group_identifier + " id=" + message.id);
    }

    static void retryGroupFile(final GroupMessage message)
    {
        if (message == null || message.group_identifier == null || message.msg_id_hash == null)
        {
            return;
        }

        HelperGroup.clear_ngc_file_transfer_failed(message.group_identifier, message.msg_id_hash);
        HelperGroup.start_group_file_send_by_hash(message.group_identifier, message.msg_id_hash);
        wakeup_tox_thread();
    }
}

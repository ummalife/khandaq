package com.zoffcc.applications.trifa;

import android.util.Log;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.zoffcc.applications.trifa.HelperFriend.get_friend_msgv3_capability;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.tox_friend_resend_msgv3_wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.tox_friend_send_message_wrapper;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_messageid;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_resend_count;
import static com.zoffcc.applications.trifa.HelperMessage.update_single_message;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MAX_TEXTMSG_RESEND_COUNT_OLDMSG_VERSION;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * Detects undelivered direct/group text messages and retries delivery.
 */
final class MessageDeliveryWatchdog
{
    private static final String TAG = "MsgDeliveryWatchdog";

    static final long DELIVERY_TIMEOUT_MS = 10_000L;
    static final long WATCH_INTERVAL_MS = 5_000L;
    static final int MAX_SEND_RETRIES = 3;

    private static final int MAX_DIRECT_RETRIES_PER_TICK = 12;
    private static final int MAX_GROUP_RESYNC_PER_TICK = 6;

    private MessageDeliveryWatchdog()
    {
    }

    static void tick()
    {
        if (!is_tox_started)
        {
            return;
        }

        watch_undelivered_direct_messages();
        watch_undelivered_group_messages();
        flush_all_pending_group_messages();
    }

    private static void flush_all_pending_group_messages()
    {
        try
        {
            final List<GroupMessage> pending = orma.selectFromGroupMessage().
                    directionEq(1).
                    message_id_toxEq(HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX).
                    orderBySent_timestampAsc().
                    limit(20).
                    toList();
            if (pending == null || pending.isEmpty())
            {
                return;
            }
            final java.util.HashSet<String> groups = new java.util.HashSet<>();
            for (GroupMessage gm : pending)
            {
                if (gm != null && gm.group_identifier != null)
                {
                    groups.add(gm.group_identifier.toLowerCase(java.util.Locale.ENGLISH));
                }
            }
            for (String gid : groups)
            {
                HelperGroup.flush_pending_group_messages(gid);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "flush_all_pending_group_messages:EE:" + e.getMessage());
        }
    }

    private static void watch_undelivered_direct_messages()
    {
        try
        {
            final long deadline = System.currentTimeMillis() - DELIVERY_TIMEOUT_MS;
            final List<Message> stale = orma.selectFromMessage().
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                    readEq(false).
                    message_idGt(-1L).
                    sent_timestampLt(deadline).
                    resend_countLt(MAX_TEXTMSG_RESEND_COUNT_OLDMSG_VERSION).
                    orderBySent_timestampAsc().
                    limit(MAX_DIRECT_RETRIES_PER_TICK).
                    toList();

            if ((stale == null) || stale.isEmpty())
            {
                return;
            }

            int retried = 0;
            final Iterator<Message> it = stale.iterator();
            while (it.hasNext())
            {
                final Message m = it.next();
                if (m == null)
                {
                    continue;
                }

                if (m.send_retries >= MAX_SEND_RETRIES)
                {
                    Log.i(TAG, "direct_give_up:id=" + m.id + " retries=" + m.send_retries);
                    continue;
                }

                final long fn = tox_friend_by_public_key__wrapper(m.tox_friendpubkey);
                final boolean friend_online = (fn >= 0) && (is_friend_online_real(fn) != 0);

                // KHANDAQ: only burn a retry when the friend is actually reachable. Previously the
                // counter was bumped every tick even while the friend was OFFLINE (no real send
                // happened — we just continue below), so a message to an offline contact hit
                // MAX_SEND_RETRIES and showed a red "failed" indicator after ~15s without a single real
                // delivery attempt. Offline messages must simply wait for the friend to come online.
                if (!friend_online)
                {
                    continue;
                }

                m.send_retries++;
                orma.updateMessage().idEq(m.id).send_retries(m.send_retries).execute();
                update_single_message(m, true);

                boolean did_retry = false;
                if ((m.msg_idv3_hash != null) && (m.msg_idv3_hash.length() >= ToxVars.TOX_HASH_LENGTH))
                {
                    did_retry = tox_friend_resend_msgv3_wrapper(m);
                }
                else if (get_friend_msgv3_capability(m.tox_friendpubkey) != 1)
                {
                    final MainActivity.send_message_result result = tox_friend_send_message_wrapper(
                            m.tox_friendpubkey, 0, m.text, (m.sent_timestamp / 1000));
                    if (result != null && HelperFriend.is_dm_message_send_success(result.msg_num))
                    {
                        m.message_id = result.msg_num;
                        m.resend_count++;
                        update_message_in_db_messageid(m);
                        update_message_in_db_resend_count(m);
                        did_retry = true;
                    }
                }

                if (did_retry)
                {
                    retried++;
                    TrifaToxService.wakeup_tox_thread();
                    Log.i(TAG, "direct_retry:id=" + m.id + " fn=" + fn + " resend_count=" + m.resend_count);
                }
            }

            if (retried > 0)
            {
                Log.i(TAG, "direct_retry:batch=" + retried);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "watch_undelivered_direct_messages:EE:" + e.getMessage());
        }
    }

    private static void watch_undelivered_group_messages()
    {
        try
        {
            final long deadline = System.currentTimeMillis() - DELIVERY_TIMEOUT_MS;
            final List<GroupMessage> stale = orma.selectFromGroupMessage().
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).
                    sync_confirmationsEq(0).
                    sent_timestampLt(deadline).
                    orderBySent_timestampAsc().
                    limit(40).
                    toList();

            if ((stale == null) || stale.isEmpty())
            {
                return;
            }

            final Set<Long> resynced_groups = new HashSet<>();
            int resync_count = 0;

            for (GroupMessage gm : stale)
            {
                if (gm == null || gm.group_identifier == null)
                {
                    continue;
                }

                final long group_num = HelperGroup.tox_group_by_groupid__wrapper(gm.group_identifier);
                if (group_num < 0)
                {
                    continue;
                }

                if (resynced_groups.contains(group_num))
                {
                    continue;
                }

                if (resync_count >= MAX_GROUP_RESYNC_PER_TICK)
                {
                    break;
                }

                resynced_groups.add(group_num);
                HelperGroup.schedule_group_message_resync(group_num);
                resync_count++;
                Log.i(TAG, "group_resync:gn=" + group_num + " gid=" + HelperGroup.group_identifier_short(
                        gm.group_identifier, false) + " msg_db_id=" + gm.id);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "watch_undelivered_group_messages:EE:" + e.getMessage());
        }
    }
}

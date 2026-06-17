/**
 * DEBUG-only broadcast receiver for automated group QA (adb am broadcast).
 */
package com.zoffcc.applications.trifa;

import org.khandaq.messenger.BuildConfig;
import org.khandaq.messenger.R;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.zoffcc.applications.trifa.TrifaToxService.wakeup_tox_thread;

public class QaGroupBroadcastReceiver extends BroadcastReceiver
{
    private static final String TAG = "trifa.QaGroupRcvr";

    public static final String ACTION_CREATE_PUBLIC = "org.khandaq.qa.CREATE_PUBLIC_GROUP";
    public static final String ACTION_CREATE_PRIVATE = "org.khandaq.qa.CREATE_PRIVATE_GROUP";
    public static final String ACTION_JOIN_PUBLIC = "org.khandaq.qa.JOIN_PUBLIC_GROUP";
    public static final String ACTION_SEND_MESSAGE = "org.khandaq.qa.SEND_GROUP_MESSAGE";
    public static final String ACTION_DUMP_MESH = "org.khandaq.qa.DUMP_MESH";
    public static final String ACTION_REVIVE_GROUPS = "org.khandaq.qa.REVIVE_GROUPS";
    public static final String ACTION_LOG_TOX_ID = "org.khandaq.qa.LOG_TOX_ID";
    public static final String ACTION_ADD_FRIEND = "org.khandaq.qa.ADD_FRIEND";
    public static final String ACTION_ACCEPT_FRIEND = "org.khandaq.qa.ACCEPT_FRIEND";
    public static final String ACTION_FORCE_ADD_FRIEND = "org.khandaq.qa.FORCE_ADD_FRIEND";
    public static final String ACTION_SEND_FRIEND_MESSAGE = "org.khandaq.qa.SEND_FRIEND_MESSAGE";
    public static final String ACTION_VERIFY_FRIEND_MESSAGE = "org.khandaq.qa.VERIFY_FRIEND_MESSAGE";

    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_GROUP_ID = "group_id";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_TOX_ID = "tox_id";

    private static String friendPublicKeyFromToxId(@Nullable final String toxId)
    {
        if (toxId == null || toxId.isEmpty())
        {
            return null;
        }
        final String normalized = HelperGeneric.normalize_tox_address(toxId.trim());
        if (normalized == null || normalized.length() < (ToxVars.TOX_PUBLIC_KEY_SIZE * 2))
        {
            return toxId.trim().toUpperCase().substring(0, Math.min(toxId.trim().length(), ToxVars.TOX_PUBLIC_KEY_SIZE * 2));
        }
        return normalized.substring(0, ToxVars.TOX_PUBLIC_KEY_SIZE * 2).toUpperCase();
    }

    @Override
    public void onReceive(final Context context, @Nullable final Intent intent)
    {
        if (!BuildConfig.DEBUG || intent == null || intent.getAction() == null)
        {
            return;
        }

        ensure_app_context(context);

        final String action = intent.getAction();
        HelperGeneric.logI(TAG, "qa_group_broadcast:recv action=" + action);

        final Thread t = new Thread(() -> handle_action(context, action, intent), "qa-group-" + action);
        t.setDaemon(true);
        t.start();
    }

    private static void ensure_app_context(@NonNull final Context context)
    {
        if (MainActivity.context_s == null)
        {
            MainActivity.context_s = context.getApplicationContext();
        }
        try
        {
            final Intent svc = new Intent(context, TrifaToxService.class);
            context.startService(svc);
        }
        catch (Exception e)
        {
            Log.w(TAG, "qa_group_broadcast:startService:EE:" + e.getMessage());
        }
        if (!TrifaToxService.TOX_SERVICE_STARTED)
        {
            try
            {
                final Intent launch = new Intent(context, MainActivity.class);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
            }
            catch (Exception e)
            {
                Log.w(TAG, "qa_group_broadcast:launch:EE:" + e.getMessage());
            }
        }
    }

    private static void handle_action(@NonNull final Context context, @NonNull final String action,
                                      @NonNull final Intent intent)
    {
        if (!HelperGroup.wait_for_tox_ready(120_000L))
        {
            Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=tox_not_ready");
            return;
        }

        wakeup_tox_thread();

        switch (action)
        {
            case ACTION_CREATE_PUBLIC:
            {
                final String name = intent.getStringExtra(EXTRA_NAME);
                if (name == null || name.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_name");
                    return;
                }
                final boolean ok = HelperGroup.create_new_group(
                        ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value,
                        name,
                        R.string.add_public_group_success,
                        R.string.add_public_group_failed,
                        "QA created public group");
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action + " ok=" + ok + " name=" + name);
                return;
            }
            case ACTION_CREATE_PRIVATE:
            {
                final String name = intent.getStringExtra(EXTRA_NAME);
                if (name == null || name.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_name");
                    return;
                }
                final boolean ok = HelperGroup.create_new_group(
                        ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value,
                        name,
                        R.string.add_private_group_success,
                        R.string.add_private_group_failed,
                        "QA created private group");
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action + " ok=" + ok + " name=" + name);
                return;
            }
            case ACTION_JOIN_PUBLIC:
            {
                final String group_id = intent.getStringExtra(EXTRA_GROUP_ID);
                if (group_id == null || group_id.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_group_id");
                    return;
                }
                final boolean ok = HelperGroup.join_public_group_by_id(group_id, false);
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action + " ok=" + ok + " group_id=" + group_id);
                return;
            }
            case ACTION_SEND_MESSAGE:
            {
                final String group_id = intent.getStringExtra(EXTRA_GROUP_ID);
                final String text = intent.getStringExtra(EXTRA_TEXT);
                if (group_id == null || group_id.isEmpty() || text == null || text.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_args");
                    return;
                }
                final long msg_id = HelperGroup.send_group_text_message_resilient(group_id, -1L, text);
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action + " msg_id=" + msg_id + " group_id="
                        + group_id + " text=" + text);
                return;
            }
            case ACTION_DUMP_MESH:
            {
                HelperGroup.qa_dump_group_mesh(intent.getStringExtra(EXTRA_GROUP_ID));
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action);
                return;
            }
            case ACTION_REVIVE_GROUPS:
            {
                HelperGroup.qa_revive_all_stuck_groups();
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action);
                return;
            }
            case ACTION_LOG_TOX_ID:
            {
                final String tox_id = MainActivity.get_my_toxid();
                HelperGeneric.logI(TAG, "qa_toxid:device=" + tox_id);
                return;
            }
            case ACTION_ADD_FRIEND:
            {
                final String tox_id = intent.getStringExtra(EXTRA_TOX_ID);
                if (tox_id == null || tox_id.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_tox_id");
                    return;
                }
                HelperFriend.add_friend_real(tox_id);
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action + " tox_id=" + tox_id);
                return;
            }
            case ACTION_ACCEPT_FRIEND:
            {
                final String tox_id = intent.getStringExtra(EXTRA_TOX_ID);
                if (tox_id == null || tox_id.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_tox_id");
                    return;
                }
                final String friendPk = friendPublicKeyFromToxId(tox_id);
                if (friendPk == null)
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=invalid_tox_id");
                    return;
                }
                HelperFriend.add_friend_to_system(friendPk, false, null);
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action + " tox_id=" + friendPk);
                return;
            }
            case ACTION_FORCE_ADD_FRIEND:
            {
                final String tox_id = intent.getStringExtra(EXTRA_TOX_ID);
                if (tox_id == null || tox_id.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_tox_id");
                    return;
                }
                HelperFriend.add_friend_real_norequest(tox_id);
                final String friendPk = friendPublicKeyFromToxId(tox_id);
                final long fn = friendPk != null ? MainActivity.tox_friend_by_public_key(friendPk) : -1L;
                if (friendPk != null && fn >= 0)
                {
                    HelperFriend.ensure_contact_record_for_pubkey(friendPk, fn);
                }
                HelperGeneric.logI(TAG, "qa_force_add_friend:done tox_id=" + friendPk + " fnum=" + fn);
                return;
            }
            case ACTION_SEND_FRIEND_MESSAGE:
            {
                final String tox_id = intent.getStringExtra(EXTRA_TOX_ID);
                final String text = intent.getStringExtra(EXTRA_TEXT);
                if (tox_id == null || tox_id.isEmpty() || text == null || text.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_args");
                    return;
                }
                final String friendPk = friendPublicKeyFromToxId(tox_id);
                if (friendPk == null)
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=invalid_tox_id");
                    return;
                }
                final MainActivity.send_message_result res = HelperGeneric.tox_friend_send_message_wrapper(
                        friendPk,
                        ToxVars.TOX_MESSAGE_TYPE.TOX_MESSAGE_TYPE_NORMAL.value,
                        text,
                        System.currentTimeMillis() / 1000L);
                HelperGeneric.logI(TAG, "qa_group_broadcast:done action=" + action + " tox_id=" + tox_id + " msg="
                        + (res != null ? res.msg_num : "null"));
                return;
            }
            case ACTION_VERIFY_FRIEND_MESSAGE:
            {
                final String text = intent.getStringExtra(EXTRA_TEXT);
                if (text == null || text.isEmpty())
                {
                    Log.w(TAG, "qa_group_broadcast:fail action=" + action + " reason=missing_text");
                    return;
                }
                long count = 0L;
                try
                {
                    if (TrifaToxService.orma != null)
                    {
                        count = TrifaToxService.orma.selectFromMessage().textEq(text).count();
                    }
                }
                catch (Exception e)
                {
                    Log.w(TAG, "qa_friend_verify:EE:" + e.getMessage());
                }
                HelperGeneric.logI(TAG, "qa_friend_verify:found=" + (count > 0) + " text=" + text);
                return;
            }
            default:
                Log.w(TAG, "qa_group_broadcast:fail unknown action=" + action);
        }
    }
}

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import com.zoffcc.applications.sorm.Message;

import java.util.ArrayList;
import java.util.List;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperMessage.insert_into_message_db;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;

public final class HelperCall
{
    public static final int REQUEST_CALL_PERMISSIONS = 4401;
    private static final int LOCAL_CALL_LOG_RESEND_COUNT = 99;

    private HelperCall()
    {
    }

    public static boolean hasMicrophonePermission(final Context context)
    {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
               PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasCameraPermission(final Context context)
    {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
               PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasRequiredCallPermissions(final Context context, final boolean audioOnly)
    {
        if (!hasMicrophonePermission(context))
        {
            return false;
        }

        if (!audioOnly && !hasCameraPermission(context))
        {
            return false;
        }

        return true;
    }

    public static void requestCallPermissions(final Activity activity, final boolean audioOnly)
    {
        final List<String> needed = new ArrayList<>();

        if (!hasMicrophonePermission(activity))
        {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }

        if (!audioOnly && !hasCameraPermission(activity))
        {
            needed.add(Manifest.permission.CAMERA);
        }

        if (!needed.isEmpty())
        {
            ActivityCompat.requestPermissions(activity, needed.toArray(new String[0]), REQUEST_CALL_PERMISSIONS);
        }
    }

    public static void showMissingPermissionToast(final Context context, final boolean audioOnly)
    {
        if (!hasMicrophonePermission(context))
        {
            display_toast(context.getString(R.string.call_permission_mic_required), false, 300);
            return;
        }

        if (!audioOnly)
        {
            display_toast(context.getString(R.string.call_permission_camera_required), false, 300);
        }
    }

    public static void postToCallActivity(final Runnable runnable)
    {
        if (runnable == null)
        {
            return;
        }

        final Handler callHandler = CallingActivity.callactivity_handler_s;

        if (callHandler != null)
        {
            callHandler.post(runnable);
            return;
        }

        if (MainActivity.main_handler_s != null)
        {
            MainActivity.main_handler_s.post(runnable);
        }
        else
        {
            new Handler(Looper.getMainLooper()).post(runnable);
        }
    }

    public static boolean isCallFriendNumber(final long friend_number)
    {
        if (Callstate.state != 1)
        {
            return false;
        }

        if (Callstate.friend_pubkey == null || Callstate.friend_pubkey.equals("-1"))
        {
            return true;
        }

        return HelperFriend.tox_friend_by_public_key__wrapper(Callstate.friend_pubkey) == friend_number;
    }

    public static void ensureCallFriendPubkey(final long friend_number)
    {
        if (Callstate.friend_pubkey == null || Callstate.friend_pubkey.equals("-1"))
        {
            Callstate.friend_pubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
        }
    }

    public static void logCallEvent(final String friend_pubkey, final int string_id)
    {
        if ((friend_pubkey == null) || friend_pubkey.equals("-1") || (context_s == null))
        {
            return;
        }

        try
        {
            final Message m = new Message();
            m.tox_friendpubkey = friend_pubkey;
            m.direction = 1;
            m.TOX_MESSAGE_TYPE = 0;
            m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
            m.message_id = -1;
            m.resend_count = LOCAL_CALL_LOG_RESEND_COUNT;
            m.sent_timestamp = System.currentTimeMillis();
            m.sent_timestamp_ms = m.sent_timestamp;
            m.text = context_s.getString(string_id);
            m.read = true;
            m.is_new = false;
            m.sent_push = 0;
            insert_into_message_db(m, true);
        }
        catch (Exception e)
        {
            android.util.Log.w("trifa.HelperCall", "logCallEvent:EE:" + e.getMessage());
        }
    }
}

package com.zoffcc.applications.trifa;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import org.khandaq.messenger.KhandaqPush;
import org.khandaq.messenger.R;

/**
 * KHANDAQ (0.2.56): what a wake push from the relay does on this phone.
 *
 * <p>The relay sends a wake as a notification message with data. In the background FCM shows that notification
 * by itself and does not call onMessageReceived, so the app learns nothing until the user taps it; only in the
 * foreground does it reach the app. A data-only wake reaches onMessageReceived in the background as well, and
 * this is what it does there:
 * <ul>
 * <li>Tox is running (maybe asleep in battery saving): wake it. It receives the message or the call and tells of
 * it with its own notification, so nothing is shown here, and no banner is left for a wake that brought nothing.
 * <li>Tox is not running: it cannot be brought up from here. Its start lives in MainActivity (database key, sign-in
 * page, identity checks, Orbot), and the foreground service alone does not start it. So the user is told that
 * somebody is trying to reach them, and a tap opens the app, which connects. One notification, however many
 * wakes, and it goes away when the app opens.
 * </ul>
 * A notification message that does reach onMessageReceived (app in the foreground) only wakes Tox, as before.
 */
public final class WakePush
{
    private static final String TAG = "trifa.WakePush";
    /** The broadcast WakeupAlarmReceiver wakes a sleeping Tox on. */
    static final String WAKE_ACTION = "com.zoffcc.applications.trifa.TOXSERVICE_ALARM";
    static final String WAITING_NOTIFICATION_TAG = "khandaq_wake_waiting";
    static final int WAITING_NOTIFICATION_ID = 1;

    enum Action
    {
        /** Wake the running Tox; it shows the message or the call itself. */
        NUDGE,
        /** Tox is not running: tell the user, and open the app on a tap. */
        NOTIFY,
        /** Tox is not running and new-message notifications are off. */
        NONE
    }

    private WakePush()
    {
    }

    static Action decide(final boolean dataOnly, final boolean toxRunning, final boolean notificationsWanted)
    {
        if (!dataOnly || toxRunning)
        {
            return Action.NUDGE;
        }
        return notificationsWanted ? Action.NOTIFY : Action.NONE;
    }

    /** Called by the FCM service for a push that is not a registration challenge. */
    public static void onWake(final Context context, final boolean dataOnly)
    {
        final Action action = decide(dataOnly, TrifaToxService.is_tox_started, notificationsWanted(context));
        Log.i(TAG, "wake push: dataOnly=" + dataOnly + " action=" + action);
        switch (action)
        {
            case NUDGE:
                final Intent wake = new Intent(WAKE_ACTION);
                wake.setPackage(context.getPackageName());
                context.sendBroadcast(wake);
                break;
            case NOTIFY:
                notifyWaiting(context);
                break;
            default:
                break;
        }
    }

    /** Takes the notification of a wake back once the app is open. */
    static void cancelWaiting(final Context context)
    {
        try
        {
            final NotificationManager nm = (NotificationManager) context.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm != null)
            {
                nm.cancel(WAITING_NOTIFICATION_TAG, WAITING_NOTIFICATION_ID);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "cancelWaiting:EE:" + e.getMessage());
        }
    }

    private static boolean notificationsWanted(final Context context)
    {
        try
        {
            // The key MainActivity reads PREF__notification from; this process may not have run it yet.
            return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("notifications_new_message",
                                                                                     true) &&
                   NotificationManagerCompat.from(context).areNotificationsEnabled();
        }
        catch (Exception e)
        {
            Log.i(TAG, "notificationsWanted:EE:" + e.getMessage());
            return true;
        }
    }

    private static void notifyWaiting(final Context context)
    {
        try
        {
            final NotificationManager nm = (NotificationManager) context.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (nm == null)
            {
                return;
            }
            final Intent open = new Intent(context, StartMainActivityWrapper.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            final PendingIntent tap = PendingIntent.getActivity(context, WAITING_NOTIFICATION_ID, open,
                                                                PendingIntent.FLAG_UPDATE_CURRENT |
                                                                PendingIntent.FLAG_IMMUTABLE);
            // The channel the relay's own notification uses (MainApplication.ensureFcmNotificationChannel).
            final NotificationCompat.Builder b = new NotificationCompat.Builder(context,
                                                                                KhandaqPush.FCM_WAKE_CHANNEL_ID);
            b.setSmallIcon(R.drawable.ic_stat_khandaq);
            b.setContentTitle(context.getString(R.string.notification_app_title));
            b.setContentText(context.getString(R.string.notification_fcm_wake_waiting));
            b.setCategory(NotificationCompat.CATEGORY_MESSAGE);
            b.setPriority(NotificationCompat.PRIORITY_HIGH);
            b.setAutoCancel(true);
            // A sender wakes again for each message and on every retry: one sound while this one is shown.
            b.setOnlyAlertOnce(true);
            b.setContentIntent(tap);
            nm.notify(WAITING_NOTIFICATION_TAG, WAITING_NOTIFICATION_ID, b.build());
        }
        catch (Exception e)
        {
            Log.i(TAG, "notifyWaiting:EE:" + e.getMessage());
        }
    }
}

package org.khandaq.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.zoffcc.applications.trifa.CallingActivity;
import com.zoffcc.applications.trifa.StartMainActivityWrapper;

/** Heads-up / full-screen incoming call alert when the app is in background. */
public final class HelperCallNotification {
    private static final String TAG = "KhandaqCallNoti";
    public static final int INCOMING_CALL_NOTIFICATION_ID = 886681;
    public static final String ACTION_DECLINE = "org.khandaq.messenger.DECLINE_INCOMING_CALL";

    private HelperCallNotification() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                KhandaqPush.INCOMING_CALL_CHANNEL_ID,
                context.getString(R.string.notification_channel_incoming_call),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.notification_channel_incoming_call_desc));
        channel.enableVibration(true);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        Uri ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        channel.setSound(ringtone, attrs);
        nm.createNotificationChannel(channel);
    }

    public static void show(Context context, String callerName, boolean videoCall) {
        ensureChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        String title = context.getString(R.string.notification_incoming_call_title);
        String body = (callerName != null && !callerName.isEmpty())
                ? callerName
                : context.getString(R.string.notification_incoming_call_unknown);
        if (videoCall) {
            body += " · " + context.getString(R.string.notification_incoming_call_video);
        }

        Intent callIntent = new Intent(context, CallingActivity.class);
        callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context, 0, callIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent declineIntent = new Intent(context, IncomingCallReceiver.class);
        declineIntent.setAction(ACTION_DECLINE);
        PendingIntent declinePendingIntent = PendingIntent.getBroadcast(
                context, 1, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean useFullScreen = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nmCheck = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nmCheck != null && !nmCheck.canUseFullScreenIntent()) {
                useFullScreen = false;
                Log.w(TAG, "USE_FULL_SCREEN_INTENT not granted — enable in app notification settings");
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, KhandaqPush.INCOMING_CALL_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullScreenPendingIntent)
                .addAction(0, context.getString(R.string.notification_incoming_call_decline), declinePendingIntent);

        if (useFullScreen) {
            builder.setFullScreenIntent(fullScreenPendingIntent, true);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        try {
            nm.notify(INCOMING_CALL_NOTIFICATION_ID, builder.build());
            Log.i(TAG, "incoming call notification shown");
        } catch (Exception e) {
            Log.w(TAG, "notify failed: " + e.getMessage());
            try {
                Intent fallback = new Intent(context, StartMainActivityWrapper.class);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception e2) {
                Log.w(TAG, "fallback start failed: " + e2.getMessage());
            }
        }
    }

    public static void cancel(Context context) {
        if (context == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(INCOMING_CALL_NOTIFICATION_ID);
            }
        } catch (Exception ignored) {
        }
    }
}

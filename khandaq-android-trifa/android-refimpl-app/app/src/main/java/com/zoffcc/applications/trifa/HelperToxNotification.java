/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2020 Zoff <zoff@zoff.cc>
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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import static android.content.Context.NOTIFICATION_SERVICE;

public class HelperToxNotification
{
    private static final String TAG = "trifa.Hlp.ToxNoti";
    /** New channel id so existing installs pick up IMPORTANCE_MIN without the old "online" label. */
    static final String CHANNEL_ID_TOX_SERVICE = "khandaq_background_service";
    private static final String LEGACY_CHANNEL_ID_TOX_SERVICE = "khandaq_online_service";
    static int ONGOING_NOTIFICATION_ID = 1030;

    static void ensureChannel(final Context c)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
        {
            return;
        }
        final NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm == null)
        {
            return;
        }
        try
        {
            nm.deleteNotificationChannel(LEGACY_CHANNEL_ID_TOX_SERVICE);
        }
        catch (Exception ignored)
        {
        }

        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID_TOX_SERVICE,
                c.getString(R.string.notification_channel_toxservice),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(c.getString(R.string.notification_channel_toxservice_desc));
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            channel.setAllowBubbles(false);
        }
        nm.createNotificationChannel(channel);
        MainActivity.channelId_toxservice = CHANNEL_ID_TOX_SERVICE;
    }

    /** Minimal silent notification required for the foreground Tox service. No connection status. */
    private static Notification build_foreground_notification(final Context c)
    {
        ensureChannel(c);
        final Intent notificationIntent = new Intent(c, MainActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final PendingIntent pendingIntent = PendingIntent.getActivity(c, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        final NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID_TOX_SERVICE);
        b.setContentTitle(c.getString(R.string.notification_fgs_title));
        b.setContentText(c.getString(R.string.notification_fgs_text));
        b.setSmallIcon(R.mipmap.ic_launcher);
        b.setContentIntent(pendingIntent);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setSilent(true);
        b.setLocalOnly(true);
        b.setCategory(Notification.CATEGORY_SERVICE);
        b.setPriority(NotificationCompat.PRIORITY_MIN);
        b.setVisibility(NotificationCompat.VISIBILITY_SECRET);
        b.setSound(null);
        b.setVibrate(null);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        {
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return b.build();
    }

    static Notification tox_notification_setup(final Context c, final NotificationManager nmn2)
    {
        Log.i(TAG, "tox_notification_setup:start");
        Log.i(TAG, "tox_notification_setup:end");
        return build_foreground_notification(c);
    }

    static void tox_notification_cancel(final Context c)
    {
        Log.i(TAG, "tox_notification_cancel:start");

        try
        {
            final NotificationManager nmn2 = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            nmn2.cancel(ONGOING_NOTIFICATION_ID);
            Log.i(TAG, "tox_notification_cancel:OK");
        }
        catch (Exception e3)
        {
            e3.printStackTrace();
        }

        Log.i(TAG, "tox_notification_cancel:end");
    }

    /** Connection status updates the in-app profile indicator only — not the notification shade. */
    static void tox_notification_change(final Context c, final NotificationManager nmn2, final int a_TOXCONNECTION,
                                        final String message)
    {
        Log.i(TAG, "tox_notification_change:profile_only connection=" + a_TOXCONNECTION);
        ProfileTabConnectionIndicator.updateAsync();
    }

    static void tox_notification_change_wrapper(final int a_TOXCONNECTION, final String message)
    {
        Log.i(TAG, "tox_notification_change_wrapper:profile_only");
        ProfileTabConnectionIndicator.updateAsync();
    }
}

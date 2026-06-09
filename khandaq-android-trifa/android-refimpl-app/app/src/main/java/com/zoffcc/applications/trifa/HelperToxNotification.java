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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import static android.content.Context.NOTIFICATION_SERVICE;
import static com.zoffcc.applications.trifa.MainActivity.PREF__orbot_enabled;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.nmn3;
import static com.zoffcc.applications.trifa.TRIFAGlobals.CONNECTION_STATUS_MANUAL_LOGOUT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.bootstrapping;
import static com.zoffcc.applications.trifa.TrifaToxService.manually_logged_out;

public class HelperToxNotification
{
    private static final String TAG = "trifa.Hlp.ToxNoti";
    static int ONGOING_NOTIFICATION_ID = 1030;

    private static class StatusAppearance
    {
        final int smallIcon;
        final int accentColor;

        StatusAppearance(int smallIcon, int accentColor)
        {
            this.smallIcon = smallIcon;
            this.accentColor = accentColor;
        }
    }

    private static StatusAppearance resolve_status_appearance(int connection, boolean is_bootstrapping, boolean is_manual_logout)
    {
        if (is_manual_logout)
        {
            return new StatusAppearance(R.drawable.circle_manuallyoffline_notification, Color.parseColor("#ff0000"));
        }
        if (is_bootstrapping)
        {
            return new StatusAppearance(R.drawable.circle_orange_notification, Color.parseColor("#ffce00"));
        }
        if (connection == 0)
        {
            return new StatusAppearance(R.drawable.circle_red_notification, Color.parseColor("#ff0000"));
        }
        if (PREF__orbot_enabled)
        {
            return new StatusAppearance(R.drawable.circle_torproxy_notification, Color.parseColor("#7c16ae"));
        }
        return new StatusAppearance(R.drawable.circle_green_notification, Color.parseColor("#04b431"));
    }

    private static String resolve_status_text(Context c, int connection, boolean is_bootstrapping, boolean is_manual_logout,
                                              String message)
    {
        if (is_manual_logout)
        {
            return c.getString(R.string.notification_status_offline_manual);
        }
        if (is_bootstrapping)
        {
            String status = c.getString(R.string.notification_status_connecting);
            if ((message != null) && (!message.isEmpty()))
            {
                return status + " · " + message.trim();
            }
            return status;
        }
        if (connection == 0)
        {
            if (PREF__orbot_enabled)
            {
                return c.getString(R.string.notification_status_offline_tor);
            }
            String status = c.getString(R.string.notification_status_offline);
            if ((message != null) && (!message.isEmpty()))
            {
                return status + " · " + message.trim();
            }
            return status;
        }
        if (PREF__orbot_enabled)
        {
            return c.getString(R.string.notification_status_online_tor);
        }
        return c.getString(R.string.notification_status_online);
    }

    private static Notification build_foreground_notification(Context c, int connection, String message)
    {
        Intent notificationIntent = new Intent(c, MainActivity.class);
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(c, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        final boolean is_manual_logout = manually_logged_out || (connection == CONNECTION_STATUS_MANUAL_LOGOUT);
        final StatusAppearance appearance = resolve_status_appearance(connection, bootstrapping, is_manual_logout);
        final String statusText = resolve_status_text(c, connection, bootstrapping, is_manual_logout, message);

        NotificationCompat.Builder b = new NotificationCompat.Builder(c, MainActivity.channelId_toxservice);
        b.setContentTitle(c.getString(R.string.notification_app_title));
        b.setContentText(statusText);
        b.setSmallIcon(appearance.smallIcon);
        b.setContentIntent(pendingIntent);
        b.setOngoing(true);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setSilent(true);
        b.setCategory(Notification.CATEGORY_SERVICE);
        b.setPriority(NotificationCompat.PRIORITY_MIN);
        b.setVisibility(NotificationCompat.VISIBILITY_SECRET);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            b.setColor(appearance.accentColor);
        }

        try
        {
            b.setSound(null);
        }
        catch (Exception ignored)
        {
        }

        return b.build();
    }

    static Notification tox_notification_setup(Context c, NotificationManager nmn2)
    {
        Log.i(TAG, "tox_notification_setup:start");
        nmn2 = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        Notification notification2 = build_foreground_notification(c, 0, "");
        Log.i(TAG, "tox_notification_setup:end");
        return notification2;
    }

    static void tox_notification_cancel(Context c)
    {
        Log.i(TAG, "tox_notification_cancel:start");

        try
        {
            NotificationManager nmn2 = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
            nmn2.cancel(ONGOING_NOTIFICATION_ID);
            Log.i(TAG, "tox_notification_cancel:OK");
        }
        catch (Exception e3)
        {
            e3.printStackTrace();
        }

        Log.i(TAG, "tox_notification_cancel:end");
    }

    static void tox_notification_change(Context c, NotificationManager nmn2, int a_TOXCONNECTION, String message)
    {
        Log.i(TAG, "tox_notification_change:start");
        Notification notification2 = build_foreground_notification(c, a_TOXCONNECTION, message);
        try
        {
            nmn2.notify(ONGOING_NOTIFICATION_ID, notification2);
        }
        catch (Exception ignored)
        {
        }
        Log.i(TAG, "tox_notification_change:end");
    }

    static void tox_notification_change_wrapper(int a_TOXCONNECTION, final String message)
    {
        Log.i(TAG, "tox_notification_change_wrapper:start");
        final int a_TOXCONNECTION_f = a_TOXCONNECTION;
        final Context static_context = context_s;

        try
        {
            Thread t = new Thread()
            {
                @Override
                public void run()
                {
                    long counter = 0;

                    while (MainActivity.tox_service_fg == null)
                    {
                        counter++;

                        if (counter > 10)
                        {
                            break;
                        }

                        try
                        {
                            Thread.sleep(100);
                        }
                        catch (Exception e)
                        {
                        }
                    }

                    try
                    {
                        tox_notification_change(static_context, nmn3, a_TOXCONNECTION_f, message);
                        Log.i(TAG, "tox_notification_change_wrapper:DONE");
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            };
            t.start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "tox_notification_change_wrapper:EE01:" + e.getMessage());
        }

        Log.i(TAG, "tox_notification_change_wrapper:end");
    }
}

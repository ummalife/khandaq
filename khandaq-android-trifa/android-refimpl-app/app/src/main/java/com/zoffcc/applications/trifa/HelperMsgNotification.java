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
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.util.HashSet;

import androidx.core.app.NotificationCompat;

import static com.zoffcc.applications.trifa.MainActivity.Notification_new_message_ID;
import static com.zoffcc.applications.trifa.MainActivity.Notification_new_message_every_millis;
import static com.zoffcc.applications.trifa.MainActivity.Notification_new_message_last_shown_timestamp;
import static com.zoffcc.applications.trifa.MainActivity.PREF__notification;
import static com.zoffcc.applications.trifa.MainActivity.PREF__notification_show_content;
import static com.zoffcc.applications.trifa.MainActivity.PREF__notification_sound;
import static com.zoffcc.applications.trifa.MainActivity.PREF__notification_vibrate;
import static com.zoffcc.applications.trifa.HelperFriend.get_friend_name_from_pubkey;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.get_friend_notification_large_icon;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_ADD;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_CLEAR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_EMPTY_THE_LIST;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NOTIFICATION_EDIT_ACTION.NOTIFICATION_EDIT_ACTION_REMOVE;

public class HelperMsgNotification
{
    private static final String TAG = "trifa.Hlp.Noti";
    private static final long MESSAGE_SOUND_DEBOUNCE_MILLIS = 1200;
    private static long last_message_sound_timestamp = 0;

    public static final String EXTRA_OPEN_CHAT_KEY = "OPEN_CHAT_KEY";
    public static final String EXTRA_SENDER_PUBKEY = "sender_pubkey";

    static HashSet<String> global_active_notifications = new HashSet<String>();

    static Uri get_message_notification_sound_uri(final Context context)
    {
        try
        {
            final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
            final String ringtone = settings.getString("notifications_new_message_ringtone", null);
            if ((ringtone != null) && (!ringtone.isEmpty()))
            {
                return Uri.parse(ringtone);
            }
        }
        catch (Exception ignored)
        {
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    static AudioAttributes notification_sound_attributes()
    {
        return new AudioAttributes.Builder().
                setUsage(AudioAttributes.USAGE_NOTIFICATION).
                setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).
                build();
    }

    static void ensure_notification_channels(final Context context, final NotificationManager nm)
    {
        if ((context == null) || (nm == null) || (Build.VERSION.SDK_INT < Build.VERSION_CODES.O))
        {
            return;
        }

        final Uri sound_uri = get_message_notification_sound_uri(context);
        final AudioAttributes sound_attrs = notification_sound_attributes();

        MainActivity.channelId_newmessage_sound_and_vibrate = "khandaq_msg_sound_vibrate_v2";
        final NotificationChannel sound_and_vibrate = new NotificationChannel(
                MainActivity.channelId_newmessage_sound_and_vibrate,
                context.getString(R.string.notification_channel_message_sound_vibrate),
                NotificationManager.IMPORTANCE_HIGH);
        sound_and_vibrate.setDescription(context.getString(R.string.notification_channel_message_sound_vibrate));
        sound_and_vibrate.setSound(sound_uri, sound_attrs);
        sound_and_vibrate.enableVibration(true);
        nm.createNotificationChannel(sound_and_vibrate);
        MainActivity.notification_channel_newmessage_sound_and_vibrate = sound_and_vibrate;

        MainActivity.channelId_newmessage_sound = "khandaq_msg_sound_v2";
        final NotificationChannel sound_only = new NotificationChannel(
                MainActivity.channelId_newmessage_sound,
                context.getString(R.string.notification_channel_message_sound),
                NotificationManager.IMPORTANCE_HIGH);
        sound_only.setDescription(context.getString(R.string.notification_channel_message_sound));
        sound_only.setSound(sound_uri, sound_attrs);
        sound_only.enableVibration(false);
        nm.createNotificationChannel(sound_only);
        MainActivity.notification_channel_newmessage_sound = sound_only;

        MainActivity.channelId_newmessage_vibrate = "khandaq_msg_vibrate_v2";
        final NotificationChannel vibrate_only = new NotificationChannel(
                MainActivity.channelId_newmessage_vibrate,
                context.getString(R.string.notification_channel_message_vibrate),
                NotificationManager.IMPORTANCE_HIGH);
        vibrate_only.setDescription(context.getString(R.string.notification_channel_message_vibrate));
        vibrate_only.setSound(null, null);
        vibrate_only.enableVibration(true);
        nm.createNotificationChannel(vibrate_only);
        MainActivity.notification_channel_newmessage_vibrate = vibrate_only;

        MainActivity.channelId_newmessage_silent = "khandaq_msg_silent_v2";
        final NotificationChannel silent = new NotificationChannel(
                MainActivity.channelId_newmessage_silent,
                context.getString(R.string.notification_channel_message_silent),
                NotificationManager.IMPORTANCE_DEFAULT);
        silent.setDescription(context.getString(R.string.notification_channel_message_silent));
        silent.setSound(null, null);
        silent.enableVibration(false);
        nm.createNotificationChannel(silent);
        MainActivity.notification_channel_newmessage_silent = silent;
    }

    /*
     * action: NOTIFICATION_EDIT_ACTION
     * key: either a friend pubkey or a conference id or a group id, both as hex string representation
     */
    static synchronized void change_msg_notification(final int action, final String key,
                                                     final String notification_title, final String notification_text)
    {
        if (action == NOTIFICATION_EDIT_ACTION_CLEAR.value)
        {
            Log.i(TAG, "change_msg_notification:NOTIFICATION_EDIT_ACTION_CLEAR");
            global_active_notifications.clear();
            remove_msg_notification();
        }
        else if (action == NOTIFICATION_EDIT_ACTION_EMPTY_THE_LIST.value)
        {
            Log.i(TAG, "change_msg_notification:NOTIFICATION_EDIT_ACTION_EMPTY_THE_LIST");
            // only call this when clicking on the notification to remove it
            // so the notification is now already removed!
            global_active_notifications.clear();
        }
        else if (action == NOTIFICATION_EDIT_ACTION_ADD.value)
        {
            if (key != null)
            {
                if (key.length() > 1)
                {
                    Log.i(TAG, "change_msg_notification:NOTIFICATION_EDIT_ACTION_ADD");
                    global_active_notifications.add(key);
                }
            }
            show_msg_notification(notification_title, notification_text, key);
        }
        else if (action == NOTIFICATION_EDIT_ACTION_REMOVE.value)
        {
            Log.i(TAG, "change_msg_notification:NOTIFICATION_EDIT_ACTION_REMOVE");
            if (key != null)
            {
                if (key.length() > 1)
                {
                    global_active_notifications.remove(key);
                }
            }

            if (global_active_notifications.isEmpty())
            {
                remove_msg_notification();
            }
        }
    }

    static void remove_msg_notification()
    {
        Log.i(TAG, "noti_and_badge:remove_notification:");
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    NotificationManager notificationManager = (NotificationManager) context_s.getSystemService(
                            Context.NOTIFICATION_SERVICE);
                    notificationManager.cancel(Notification_new_message_ID);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };

        try
        {
            if (main_handler_s != null)
            {
                main_handler_s.post(myRunnable);
            }
        }
        catch (Exception e)
        {
        }
    }

    private static String resolve_sender_label(String name, String key)
    {
        if (name != null)
        {
            final String trimmed = name.trim();
            if (!trimmed.isEmpty() && !"?".equals(trimmed))
            {
                return trimmed;
            }
        }
        if (key != null && key.length() >= 8)
        {
            try
            {
                final String fromDb = get_friend_name_from_pubkey(key);
                if (fromDb != null)
                {
                    final String trimmed = fromDb.trim();
                    if (!trimmed.isEmpty() && !"?".equals(trimmed))
                    {
                        return trimmed;
                    }
                }
            }
            catch (Exception ignored)
            {
            }
            return key.substring(0, 8).toUpperCase();
        }
        return null;
    }

    private static String trim_message_preview(String text)
    {
        if (text == null)
        {
            return "";
        }
        final String trimmed = text.trim().replace('\n', ' ');
        if (trimmed.length() <= 160)
        {
            return trimmed;
        }
        return trimmed.substring(0, 157) + "...";
    }

    static boolean is_viewing_friend_chat(final String friend_pubkey)
    {
        if ((friend_pubkey == null) || (MainActivity.message_list_activity == null))
        {
            return false;
        }
        try
        {
            final long friend_num = MainActivity.message_list_activity.get_current_friendnum();
            if (friend_num < 0)
            {
                return false;
            }
            return friend_pubkey.equals(HelperFriend.tox_friend_get_public_key__wrapper(friend_num));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static void play_message_sound_only()
    {
        if ((!PREF__notification) || (!PREF__notification_sound) || (context_s == null))
        {
            return;
        }

        Runnable soundRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    final long now = System.currentTimeMillis();
                    if ((last_message_sound_timestamp + MESSAGE_SOUND_DEBOUNCE_MILLIS) > now)
                    {
                        return;
                    }
                    last_message_sound_timestamp = now;

                    final Uri sound_uri = get_message_notification_sound_uri(context_s);
                    if (sound_uri == null)
                    {
                        return;
                    }

                    final Ringtone ringtone = RingtoneManager.getRingtone(context_s, sound_uri);
                    if (ringtone == null)
                    {
                        return;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    {
                        ringtone.setAudioAttributes(notification_sound_attributes());
                    }

                    if (!ringtone.isPlaying())
                    {
                        ringtone.play();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(soundRunnable);
        }
    }

    static void show_msg_notification(final String nf_title, final String nf_text, final String nf_key)
    {
        Log.i(TAG, "noti_and_badge:show_notification:");
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    final boolean appInForeground = MainActivity.main_activity_resumed &&
                                                    (MainActivity.main_activity_s != null) &&
                                                    (!MainActivity.main_activity_s.isFinishing());

                    if (appInForeground || is_viewing_friend_chat(nf_key))
                    {
                        play_message_sound_only();
                        return;
                    }

                    // allow notification every n seconds
                    if ((Notification_new_message_last_shown_timestamp + Notification_new_message_every_millis) <
                        System.currentTimeMillis())
                    {
                        if (PREF__notification)
                        {
                            if (PREF__notification_sound)
                            {
                                ensure_notification_channels(context_s,
                                        (NotificationManager) context_s.getSystemService(
                                                Context.NOTIFICATION_SERVICE));
                            }
                            Notification_new_message_last_shown_timestamp = System.currentTimeMillis();
                            Intent notificationIntent = new Intent(context_s, StartMainActivityWrapper.class);
                            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                            notificationIntent.setAction(
                                    "com.zoffcc.applications.trifa." + (long) (Math.random() * 100000));
                            notificationIntent.putExtra("CLEAR_NEW_MESSAGE_NOTIFICATION", "1");
                            if (nf_key != null)
                            {
                                notificationIntent.putExtra(EXTRA_OPEN_CHAT_KEY, nf_key);
                            }
                            final int pending_request_code = (nf_key != null) ? nf_key.hashCode() : 0;
                            PendingIntent pendingIntent = PendingIntent.getActivity(context_s, pending_request_code,
                                                                                    notificationIntent,
                                                                                    PendingIntent.FLAG_UPDATE_CURRENT |
                                                                                    PendingIntent.FLAG_IMMUTABLE);
                            // -- notification ------------------
                            // -- notification -----------------
                            NotificationCompat.Builder b;

                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                            {
                                if ((PREF__notification_sound) && (PREF__notification_vibrate))
                                {
                                    b = new NotificationCompat.Builder(context_s,
                                                                       MainActivity.channelId_newmessage_sound_and_vibrate);
                                }
                                else if ((PREF__notification_sound) && (!PREF__notification_vibrate))
                                {
                                    b = new NotificationCompat.Builder(context_s,
                                                                       MainActivity.channelId_newmessage_sound);
                                }
                                else if ((!PREF__notification_sound) && (PREF__notification_vibrate))
                                {
                                    b = new NotificationCompat.Builder(context_s,
                                                                       MainActivity.channelId_newmessage_vibrate);
                                }
                                else
                                {
                                    b = new NotificationCompat.Builder(context_s,
                                                                       MainActivity.channelId_newmessage_silent);
                                }
                            }
                            else
                            {
                                b = new NotificationCompat.Builder(context_s);
                            }

                            b.setContentIntent(pendingIntent);
                            b.setSmallIcon(R.drawable.circle_orange);
                            b.setPriority(NotificationCompat.PRIORITY_HIGH);
                            b.setCategory(NotificationCompat.CATEGORY_MESSAGE);
                            b.setLights(Color.parseColor("#ffce00"), 500, 500);

                            if ((PREF__notification_sound) && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O))
                            {
                                b.setSound(get_message_notification_sound_uri(context_s));
                            }

                            if (PREF__notification_vibrate)
                            {
                                long[] vibrate_pattern = {100, 300};
                                b.setVibrate(vibrate_pattern);
                            }

                            final String senderLabel = resolve_sender_label(nf_title, nf_key);
                            final String genericBody = context_s.getString(
                                    R.string.MainActivity_notification_new_message2);
                            b.setContentTitle(senderLabel != null
                                    ? senderLabel
                                    : context_s.getString(R.string.notification_app_title));
                            b.setAutoCancel(true);

                            if (PREF__notification_show_content)
                            {
                                final String preview = trim_message_preview(nf_text);
                                if (!preview.isEmpty())
                                {
                                    b.setContentText(preview);
                                    b.setStyle(new NotificationCompat.BigTextStyle().bigText(preview));
                                }
                                else
                                {
                                    b.setContentText(genericBody);
                                }
                            }
                            else
                            {
                                b.setContentText(genericBody);
                            }

                            try
                            {
                                final android.graphics.Bitmap largeIcon =
                                        get_friend_notification_large_icon(context_s, nf_key);
                                if (largeIcon != null)
                                {
                                    b.setLargeIcon(largeIcon);
                                }
                            }
                            catch (Exception ignored)
                            {
                            }

                            Notification notification3 = b.build();
                            MainActivity.nmn3.notify(Notification_new_message_ID, notification3);
                            // -- notification ------------------
                            // -- notification ------------------
                        }
                    }
                    else if (PREF__notification_sound)
                    {
                        play_message_sound_only();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };

        try
        {
            if (main_handler_s != null)
            {
                main_handler_s.post(myRunnable);
            }
        }
        catch (Exception e)
        {
        }
    }

    static void store_pending_open_chat_key(final String key)
    {
        if ((key != null) && (key.length() > 1))
        {
            TRIFAGlobals.pending_notification_chat_key = key;
        }
    }

    static String extract_open_chat_key_from_intent(final Intent intent)
    {
        if (intent == null)
        {
            return null;
        }
        String key = intent.getStringExtra(EXTRA_OPEN_CHAT_KEY);
        if ((key != null) && (key.length() > 1))
        {
            return key;
        }
        key = intent.getStringExtra(EXTRA_SENDER_PUBKEY);
        if ((key != null) && (key.length() > 1))
        {
            return key;
        }
        key = intent.getStringExtra("from");
        if ((key != null) && (key.length() > 1))
        {
            return key;
        }
        return null;
    }

    static void consume_pending_open_chat(final Context c)
    {
        final String key = TRIFAGlobals.pending_notification_chat_key;
        if ((key == null) || (key.length() < 2) || (c == null))
        {
            return;
        }
        TRIFAGlobals.pending_notification_chat_key = null;
        open_chat_from_notification_key(c, key);
    }

    static void open_chat_from_notification_key(final Context c, final String key)
    {
        if ((c == null) || (key == null) || (key.length() < 2))
        {
            return;
        }
        try
        {
            final long friend_num = tox_friend_by_public_key__wrapper(key);
            if (friend_num >= 0)
            {
                MessageListActivity.show_messagelist_for_friend(c, key, null);
                return;
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            if (orma.selectFromGroupDB().group_identifierEq(key.toLowerCase()).count() > 0)
            {
                GroupMessageListActivity.show_messagelist_for_id(c, key, null);
                return;
            }
            if (orma.selectFromGroupDB().group_identifierEq(key).count() > 0)
            {
                GroupMessageListActivity.show_messagelist_for_id(c, key, null);
                return;
            }
        }
        catch (Exception ignored)
        {
        }
        try
        {
            if (orma.selectFromConferenceDB().conference_identifierEq(key).count() > 0)
            {
                Intent intent = new Intent(c, ConferenceMessageListActivity.class);
                intent.putExtra("conf_id", key);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(intent);
            }
        }
        catch (Exception ignored)
        {
        }
    }
}

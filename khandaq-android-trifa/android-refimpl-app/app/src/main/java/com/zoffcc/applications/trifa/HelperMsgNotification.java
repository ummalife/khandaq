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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.zoffcc.applications.sorm.FriendList;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    // KHANDAQ #200: per-conversation notification ids so different chats no longer collapse into one
    // shared slot (which made every message look identical / "duplicated"), plus a per-chat rate limit
    // so a message in chat B is never dropped just because chat A notified within the window.
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> notif_id_by_key =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> last_shown_by_key =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger notif_id_counter =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int NOTIF_ID_BASE = 20100; // clear of Notification_new_message_ID (10023) and call ids

    private static int notif_id_for_key(final String key)
    {
        if (key == null || key.isEmpty())
        {
            return Notification_new_message_ID;
        }
        Integer id = notif_id_by_key.get(key);
        if (id == null)
        {
            id = NOTIF_ID_BASE + (notif_id_counter.getAndIncrement() & 0x3fff);
            final Integer prev = notif_id_by_key.putIfAbsent(key, id);
            if (prev != null)
            {
                id = prev;
            }
        }
        return id;
    }

    static String media_label_for_filename(final Context ctx, final String file_name)
    {
        try
        {
            if (ctx != null && file_name != null)
            {
                final String lower = file_name.toLowerCase(java.util.Locale.ENGLISH);
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                        || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".heic")
                        || lower.endsWith(".bmp"))
                {
                    return ctx.getString(R.string.notification_media_photo);
                }
                if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".mkv")
                        || lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".avi"))
                {
                    return ctx.getString(R.string.notification_media_video);
                }
                if (lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".oga")
                        || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".wav")
                        || lower.endsWith(".opus") || lower.endsWith(".amr"))
                {
                    return ctx.getString(R.string.notification_media_voice);
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return ctx != null ? ctx.getString(R.string.notification_media_file) : "File";
    }

    static Uri get_message_notification_sound_uri(final Context context)
    {
        try
        {
            final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
            final String ringtone = settings.getString("notifications_new_message_ringtone", null);
            if (ringtone != null)
            {
                if (ringtone.isEmpty())
                {
                    return null;
                }
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

        ensure_request_channel(context, nm);
    }

    /**
     * KHANDAQ (redesign, E22): the channel of requests and invitations, a kind of their own. Since they
     * became cards in Requests (E12) nothing told of them and no channel was theirs, so this ID is new;
     * the message channels above keep theirs, and with them the user's settings.
     */
    static void ensure_request_channel(final Context context, final NotificationManager nm)
    {
        if ((context == null) || (nm == null))
        {
            return;
        }
        final NotificationChannel requests = new NotificationChannel(CHANNEL_ID_REQUESTS,
                context.getString(R.string.air_band_channel_requests), NotificationManager.IMPORTANCE_HIGH);
        requests.setDescription(context.getString(R.string.air_band_channel_requests_description));
        requests.setSound(get_message_notification_sound_uri(context), notification_sound_attributes());
        requests.enableVibration(true);
        nm.createNotificationChannel(requests);
    }

    /*
     * action: NOTIFICATION_EDIT_ACTION
     * key: either a friend pubkey or a conference id or a group id, both as hex string representation
     */
    static synchronized void change_msg_notification(final int action, final String key,
                                                     final String notification_title, final String notification_text)
    {
        change_msg_notification(action, key, notification_title, notification_text, 0L, null);
    }

    /**
     * KHANDAQ (redesign, E22): as above, with what the in-app band needs to know of a new message: the
     * sender's send time ({@code sent_at_ms}, 0 when unknown), so that a late delivery comes without a
     * band, and the member who wrote in a group ({@code group_peer_key}, null otherwise), whose name
     * leads the text.
     */
    static synchronized void change_msg_notification(final int action, final String key,
                                                     final String notification_title, final String notification_text,
                                                     final long sent_at_ms, @Nullable final String group_peer_key)
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
            show_msg_notification(notification_title, notification_text, key, sent_at_ms, group_peer_key);
        }
        else if (action == NOTIFICATION_EDIT_ACTION_REMOVE.value)
        {
            Log.i(TAG, "change_msg_notification:NOTIFICATION_EDIT_ACTION_REMOVE");
            if (key != null)
            {
                if (key.length() > 1)
                {
                    global_active_notifications.remove(key);
                    // KHANDAQ #200: cancel THIS conversation's own notification when its chat is opened.
                    remove_msg_notification(key);
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
        remove_msg_notification(null);
    }

    // KHANDAQ #200: key == null cancels the legacy shared slot AND every per-chat notification we posted;
    // key != null cancels just that one conversation's notification (used when its chat is opened).
    static void remove_msg_notification(final String key)
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
                    if ((key == null) || (key.length() <= 1))
                    {
                        notificationManager.cancel(Notification_new_message_ID);
                        for (final Integer id : notif_id_by_key.values())
                        {
                            try { notificationManager.cancel(id); } catch (Exception ignored) {}
                        }
                        notif_id_by_key.clear();
                        last_shown_by_key.clear();
                    }
                    else
                    {
                        notificationManager.cancel(notif_id_for_key(key));
                        notif_id_by_key.remove(key);
                        last_shown_by_key.remove(key);
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

    static String resolve_sender_label(String name, String key)
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

    static String trim_message_preview(String text)
    {
        if (text == null)
        {
            return "";
        }
        return ChatListUiHelper.truncate_preview(ChatListUiHelper.sanitize_preview_body(text));
    }

    static boolean is_viewing_friend_chat(final String friend_pubkey)
    {
        if ((friend_pubkey == null) || (MainActivity.message_list_activity == null))
        {
            return false;
        }
        try
        {
            final String open_pk = MainActivity.message_list_activity.get_friend_pubkey();
            return (open_pk != null) && friend_pubkey.equalsIgnoreCase(open_pk);
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

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    {
                        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context_s);
                        final int percent = prefs.getInt(
                                NotificationRingtoneSettingsActivity.PREF_NOTIFICATION_VOLUME_PERCENT, 100);
                        ringtone.setVolume(Math.max(0f, Math.min(1f, percent / 100f)));
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
        show_msg_notification(nf_title, nf_text, nf_key, 0L, null);
    }

    static void show_msg_notification(final String nf_title, final String nf_text, final String nf_key,
                                      final long sent_at_ms, @Nullable final String group_peer_key)
    {
        Log.i(TAG, "noti_and_badge:show_notification:");
        // KHANDAQ (redesign, E22): while a screen of the app is in front the message may come as the
        // in-app band instead. What the band says reads the database, so it is put together here, before
        // the hop to the main thread, and only when a band could come down at all.
        AirBand.Content band_content = null;
        if (PREF__notification && (context_s != null) && AirBand.mayShow())
        {
            try
            {
                band_content = AirBand.forMessage(context_s, nf_key, nf_title, nf_text, group_peer_key);
            }
            catch (Exception e)
            {
                Log.i(TAG, "show_msg_notification:band:EE:" + e.getMessage());
            }
        }
        final AirBand.Content band = band_content;
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

                    // KHANDAQ (redesign, E22): the band takes the place of the notification and of the
                    // sound-only path below; a gate, a call or a locked phone keep those paths.
                    if (band != null)
                    {
                        switch (AirBand.messageOutcome(nf_key, sent_at_ms))
                        {
                            case BAND:
                                if (AirBand.show(band))
                                {
                                    play_message_sound_only();
                                    return;
                                }
                                // It could not come down: as before the band.
                                break;
                            case QUIET:
                                if (!is_viewing_friend_chat(nf_key))
                                {
                                    // The backlog of coming back and a late delivery: no band, no sound.
                                    return;
                                }
                                // The chat on screen keeps its sound, below.
                                break;
                            default:
                                break;
                        }
                    }

                    if (appInForeground || is_viewing_friend_chat(nf_key))
                    {
                        // KHANDAQ (#H3 parity): suppress the ring for the first 3s after the app becomes
                        // active. On entry the Tox backlog / offline-queue flushes and each flushed message
                        // runs this callback, which rang "on entry, nothing new". A message that genuinely
                        // arrives while already foregrounded (>3s after resume) still rings normally.
                        if ((System.currentTimeMillis() - MainActivity.main_activity_resumed_ts) >= 3000L)
                        {
                            play_message_sound_only();
                        }
                        return;
                    }

                    // KHANDAQ #200: rate-limit PER conversation (not globally), so a message in chat B
                    // is never suppressed just because chat A notified within the window.
                    final String rl_key = (nf_key != null && nf_key.length() > 1) ? nf_key : "__default__";
                    final Long last_key_shown = last_shown_by_key.get(rl_key);
                    if ((last_key_shown == null) ||
                        ((last_key_shown + Notification_new_message_every_millis) < System.currentTimeMillis()))
                    {
                        if (PREF__notification)
                        {
                            if (PREF__notification_sound)
                            {
                                ensure_notification_channels(context_s,
                                        (NotificationManager) context_s.getSystemService(
                                                Context.NOTIFICATION_SERVICE));
                            }
                            last_shown_by_key.put(rl_key, System.currentTimeMillis());
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
                                    // KHANDAQ #200: an empty preview is almost always a media/file message:
                                    // show an attachment label instead of the generic "new message" body.
                                    b.setContentText(context_s.getString(R.string.notification_media_generic));
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
                            // KHANDAQ #200: post under a stable PER-CHAT id so different conversations
                            // no longer overwrite one shared notification slot.
                            MainActivity.nmn3.notify(notif_id_for_key(nf_key), notification3);
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

    private static volatile long pending_notification_chat_key_ts = 0L;
    // KHANDAQ (audit #8): how long a chat-open may wait behind the app lock. The tap already cleared
    // the notification, so a key that is still pending long after the tap must be dropped rather than
    // popping a chat open out of context on some later, unrelated unlock. Generous enough that the
    // honest path (cold start -> password -> DB mount -> MainActivity) never loses the chat-open.
    private static final long PENDING_OPEN_CHAT_MAX_AGE_MS = 120_000L;

    static void store_pending_open_chat_key(final String key)
    {
        if ((key != null) && (key.length() > 1))
        {
            TRIFAGlobals.pending_notification_chat_key = key;
            pending_notification_chat_key_ts = System.currentTimeMillis();
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
        // KHANDAQ (audit #8): a stale deferral must not surface later out of context: the tap that
        // stored this key already dismissed the notification, so an expired key is dropped and the user
        // reopens the chat from the list.
        if ((System.currentTimeMillis() - pending_notification_chat_key_ts) > PENDING_OPEN_CHAT_MAX_AGE_MS)
        {
            TRIFAGlobals.pending_notification_chat_key = null;
            return;
        }
        // KHANDAQ (audit #8): a notification tap on a locked app runs this from MainActivity.onResume,
        // which would open the chat ON TOP of the PIN screen the app-lock just raised. The question is
        // "is a gate up / owed right now", not "was this process ever unlocked": on a warm process the
        // background gate fires with process_unlocked already true. Keep the key pending instead of
        // dropping it: MainActivity resumes again when the PIN screen finishes, and the chat opens then.
        if (AppLockHelper.isGateUp(c))
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
        // KHANDAQ (redesign, E22): the notification of a request or an invitation opens Requests.
        if (OPEN_REQUESTS_KEY.equals(key))
        {
            AirBand.openRequests(c);
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

    // ---- KHANDAQ (redesign, E22): requests and invitations, a kind of their own ----

    /** Their channel, created next to the message channels (ensure_request_channel). */
    static final String CHANNEL_ID_REQUESTS = "khandaq_requests";
    /** The chat key of a notification that opens Requests rather than a chat. */
    static final String OPEN_REQUESTS_KEY = "air:requests";
    /** They post under a tag of their own, clear of the message ids that remove_msg_notification takes back. */
    private static final String REQUEST_NOTIFICATION_TAG = "khandaq_request";
    /** The cards whose notification is up, so that an answer takes it back. */
    private static final Set<String> posted_request_cards = ConcurrentHashMap.newKeySet();

    private static String request_card(final String public_key)
    {
        return "request:" + public_key;
    }

    /** One card per kind, contact and group, as AirInviteStore keeps them: a repeat replaces the card. */
    private static String invite_card(final AirInviteStore.Invite invite)
    {
        return "invite:" + invite.kind.name() + ":" + invite.inviterKey + ":" + invite.groupId();
    }

    /** True when a request from this key already waits as a card, so a repeat is not told again. */
    static boolean is_request_waiting(final String public_key)
    {
        final AirRequestStore.Pending probe = AirRequestStore.Pending.of(public_key, "", 0L);
        if (probe == null)
        {
            return false;
        }
        for (final AirRequestStore.Pending waiting : AirRequestStore.snapshot())
        {
            if (waiting.publicKey.equals(probe.publicKey))
            {
                return true;
            }
        }
        return false;
    }

    /** True when this contact's invitation to this group already waits as a card. */
    static boolean is_invite_waiting(final AirInviteStore.Kind kind, final String inviter_key, final byte[] data)
    {
        final AirInviteStore.Invite probe = AirInviteStore.Invite.of("probe", kind, inviter_key, "",
                                                                     AirInviteStore.hex(data), 0, 0L);
        if (probe == null)
        {
            return false;
        }
        final String card = invite_card(probe);
        for (final AirInviteStore.Invite waiting : AirInviteStore.snapshot())
        {
            if (invite_card(waiting).equals(card))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * A new contact request, from the toxcore callback: the band while a screen of the app is in front,
     * otherwise a notification of its own. The card waits in Requests either way.
     */
    static void notify_incoming_request(final String public_key, final String message)
    {
        final AirRequestStore.Pending request = AirRequestStore.Pending.of(public_key, message, 0L);
        if ((request == null) || (!PREF__notification) || (context_s == null))
        {
            return;
        }
        tell_request(request_card(request.publicKey), AirBand.forRequest(context_s, request.publicKey, request.message),
                     null);
    }

    /**
     * A new invitation to a group, kept as a card: who invites, then the group. As on iOS, the invitation
     * of a muted contact waits in Requests without a sound, a band or a notification.
     */
    static void notify_group_invite(final AirInviteStore.Invite invite)
    {
        if ((invite == null) || (!PREF__notification) || (context_s == null))
        {
            return;
        }
        final FriendList inviter = HelperFriend.lookup_friendlist_by_pubkey(invite.inviterKey);
        if ((inviter != null) && inviter.notification_silent)
        {
            return;
        }
        Bitmap photo = null;
        try
        {
            photo = get_friend_notification_large_icon(context_s, invite.inviterKey);
        }
        catch (Exception ignored)
        {
        }
        tell_request(invite_card(invite), AirBand.forInvite(context_s, invite, inviter), photo);
    }

    /**
     * On the main thread: the band once past the grace of coming back while a screen with content is in
     * front; nothing within that grace, which is the backlog; the notification while the app is away,
     * locked, on a gate or in a call, or when the band could not come down.
     */
    private static void tell_request(final String card, final AirBand.Content content, @Nullable final Bitmap photo)
    {
        if (main_handler_s == null)
        {
            return;
        }
        main_handler_s.post(() -> {
            try
            {
                switch (AirBand.requestOutcome())
                {
                    case QUIET:
                        return;
                    case BAND:
                        if (AirBand.show(content))
                        {
                            play_message_sound_only();
                            return;
                        }
                        break;
                    default:
                        break;
                }
                post_request_notification(card, content, photo);
            }
            catch (Exception e)
            {
                Log.i(TAG, "tell_request:EE:" + e.getMessage());
            }
        });
    }

    private static void post_request_notification(final String card, final AirBand.Content content,
                                                  @Nullable final Bitmap photo)
    {
        final Context c = context_s;
        final NotificationManager nm = (c == null) ? null
                                                   : (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null)
        {
            return;
        }
        ensure_request_channel(c, nm);
        final Intent open = new Intent(c, StartMainActivityWrapper.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // An action of its own per card, so that no two cards share one PendingIntent.
        open.setAction("com.zoffcc.applications.trifa.open_requests." + card.hashCode());
        open.putExtra(EXTRA_OPEN_CHAT_KEY, OPEN_REQUESTS_KEY);
        final PendingIntent tap = PendingIntent.getActivity(c, card.hashCode(), open,
                                                            PendingIntent.FLAG_UPDATE_CURRENT |
                                                            PendingIntent.FLAG_IMMUTABLE);
        final NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL_ID_REQUESTS);
        b.setSmallIcon(R.drawable.ic_stat_khandaq);
        b.setContentTitle(content.title);
        if (!content.body.isEmpty())
        {
            b.setContentText(content.body);
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(content.body));
        }
        if (photo != null)
        {
            b.setLargeIcon(photo);
        }
        b.setCategory(NotificationCompat.CATEGORY_SOCIAL);
        b.setPriority(NotificationCompat.PRIORITY_HIGH);
        b.setAutoCancel(true);
        b.setContentIntent(tap);
        if (!PREF__notification_sound)
        {
            // The channel rings; the app's own sound setting still keeps this one quiet.
            b.setSilent(true);
        }
        nm.notify(REQUEST_NOTIFICATION_TAG, card.hashCode(), b.build());
        posted_request_cards.add(card);
    }

    /** Takes back the notification of a request or an invitation once its card is answered (main thread). */
    static void forget_answered_requests()
    {
        final Context c = context_s;
        if (posted_request_cards.isEmpty() || (c == null))
        {
            return;
        }
        final Set<String> waiting = new HashSet<>();
        for (final AirRequestStore.Pending request : AirRequestStore.snapshot())
        {
            waiting.add(request_card(request.publicKey));
        }
        for (final AirInviteStore.Invite invite : AirInviteStore.snapshot())
        {
            waiting.add(invite_card(invite));
        }
        final NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        for (final String card : posted_request_cards)
        {
            if (!waiting.contains(card))
            {
                posted_request_cards.remove(card);
                if (nm != null)
                {
                    nm.cancel(REQUEST_NOTIFICATION_TAG, card.hashCode());
                }
            }
        }
    }
}

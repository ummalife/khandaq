package com.zoffcc.applications.trifa;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import com.zoffcc.applications.sorm.FriendList;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * KHANDAQ (0.2.64): a wake push that carries a preview (docs/PUSH_PREVIEWS.md, "Android FirebaseMessagingService").
 *
 * <p>The relay sends it data-only, so this runs in the background too, often in a process that has just started for
 * it: no database, no Tox, no screen. All it needs is in SharedPreferences: this installation's master key M, from
 * which the key of the sender follows, and the settings. It decrypts the preview, shows it on the chat's own
 * notification (so that the message, when Tox brings it, updates that one rather than adding a second), and wakes
 * Tox as a wake always did. A preview it cannot read goes the way every wake went before (WakePush).
 */
public final class PushPreviewReceiver
{
    private static final String TAG = "trifa.PushPreviewRecv";

    static final String DATA_PREVIEW = "khq_pv";
    static final String DATA_REPEAT = "khq_rep";
    static final String DATA_SENDER = "sender_pubkey";

    /** How long the handler waits for the name of a sender this device knows, when the database is open anyway. */
    private static final long LOCAL_NAME_WAIT_MS = 250L;
    private static final ExecutorService LOCAL_NAME = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "khandaq-push-preview-name");
        thread.setDaemon(true);
        return thread;
    });

    /** What became of a preview. */
    enum Outcome
    {
        /** Not ours to read: the caller sends it the old way. */
        UNREADABLE,
        SHOWN,
        /** Already shown, by a push or over Tox. */
        ALREADY_SHOWN,
        /** That chat is on the screen. */
        CHAT_OPEN,
        /** That chat is muted on this device. */
        MUTED,
        /** New-message notifications are off. */
        NOTIFICATIONS_OFF
    }

    private PushPreviewReceiver()
    {
    }

    /**
     * Called by the FCM service before WakePush, on the service's worker thread. True when the push was taken care
     * of here (a preview read, whatever became of it, or the relay's silent repeat of one), false when it is to go
     * the way every wake went before.
     */
    public static boolean onWake(final Context context, final Map<String, String> data)
    {
        if ((context == null) || (data == null))
        {
            return false;
        }
        try
        {
            final String preview = data.get(DATA_PREVIEW);
            if ((preview == null) || preview.isEmpty())
            {
                if (!"1".equals(data.get(DATA_REPEAT)))
                {
                    return false;
                }
                // The relay repeats an event it already sent a preview of (a retry of the sender) without the
                // preview: it wakes Tox like the first one, and never puts up "somebody is trying to reach you".
                Log.i(TAG, "silent repeat of a preview push");
                nudge(context);
                return true;
            }
            final Outcome outcome = present(context, data.get(DATA_SENDER), preview);
            if (outcome == Outcome.UNREADABLE)
            {
                Log.i(TAG, "preview push not readable here");
                return false;
            }
            Log.i(TAG, "preview push: " + outcome);
            nudge(context);
            return true;
        }
        catch (Throwable e)
        {
            // Whatever went wrong here, the wake still goes the way it always went (WakePush).
            Log.i(TAG, "onWake:EE:" + e.getClass().getSimpleName());
            return false;
        }
    }

    /** Wakes a running Tox, as WakePush does; a Tox that does not run starts when the notification is tapped. */
    private static void nudge(final Context context)
    {
        try
        {
            if (TrifaToxService.is_tox_started)
            {
                final Intent wake = new Intent(WakePush.WAKE_ACTION);
                wake.setPackage(context.getPackageName());
                context.sendBroadcast(wake);
            }
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "nudge:EE:" + e.getClass().getSimpleName());
        }
    }

    private static Outcome present(final Context context, final String senderHex, final String preview)
    {
        if (!PushPreviewStore.supported() || (senderHex == null)
            || (senderHex.length() != PushPreviewCrypto.PUBLIC_KEY_BYTES * 2))
        {
            return Outcome.UNREADABLE;
        }
        final String senderKey = PushPreviewStore.friendKey(senderHex);
        // Only a contact this device handed its K to and has not deleted or blocked since: anyone else's wake is the
        // generic one, as every wake was before previews.
        if ((senderKey == null) || !PushPreviewStore.isAllowedSender(context, senderKey))
        {
            return Outcome.UNREADABLE;
        }
        final byte[] key = PushPreviewCrypto.friendKey(PushPreviewStore.existingMasterKey(context),
                                                       PushPreviewCrypto.unhex(senderKey,
                                                                               PushPreviewCrypto.PUBLIC_KEY_BYTES));
        final PushPreviewPayload p = PushPreviewPayload.parse(PushPreviewCrypto.open(key, preview));
        if (p == null)
        {
            return Outcome.UNREADABLE;
        }

        // The app keeps a friend's chat under the friend's key and a group's under its chat id, lowercase.
        final String chatKey = (p.groupId != null) ? p.groupId.toLowerCase(Locale.ROOT) : senderKey;
        if (PushPreviewStore.isMuted(context, chatKey))
        {
            return Outcome.MUTED;
        }
        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        if (!settings.getBoolean("notifications_new_message", true)
            || !NotificationManagerCompat.from(context).areNotificationsEnabled())
        {
            return Outcome.NOTIFICATIONS_OFF;
        }
        if (isChatOpen(chatKey, p.groupId != null))
        {
            return Outcome.CHAT_OPEN;
        }

        final NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null)
        {
            return Outcome.UNREADABLE;
        }
        final String localName = (p.senderName == null) ? localName(senderKey) : null;
        final SharedPreferences seen = PushPreviewSeen.prefs(context);
        final PushPreviewSeen.Event event = PushPreviewSeen.forPreview(senderKey, p);
        final long nowMs = System.currentTimeMillis();
        // One decision for the check and the record: the Tox path (HelperMsgNotification) asks the same memory.
        synchronized (PushPreviewSeen.SHARED)
        {
            // The marks of the previews the chat's notification shows and Tox has not brought yet: one of them
            // again is a retry of that event under another event id, and adds nothing.
            final ArrayList<String> held = HelperMsgNotification.active_push_preview_marks(nm, chatKey);
            if (((held != null) && !Collections.disjoint(held, event.marks))
                || !PushPreviewSeen.SHARED.pushMayShow(seen, event, nowMs))
            {
                return Outcome.ALREADY_SHOWN;
            }
            final PushPreviewText.Shown shown = PushPreviewText.select(
                    p, localName, settings.getBoolean("notification_show_content", true), nowMs / 1000L,
                    labels(context));
            post(context, nm, settings, chatKey, shown, event, (held != null) ? held : new ArrayList<String>());
            PushPreviewSeen.SHARED.pushShown(seen, event, nowMs);
        }
        HelperMsgNotification.note_chat_notification_shown(chatKey);
        return Outcome.SHOWN;
    }

    /**
     * The chat is on the screen, the same test the Tox path makes. Nothing is on the screen in a process the push
     * started, and these references are null there.
     */
    private static boolean isChatOpen(final String chatKey, final boolean group)
    {
        try
        {
            if (!group)
            {
                return HelperMsgNotification.is_viewing_friend_chat(chatKey);
            }
            final GroupMessageListActivity open = MainActivity.group_message_list_activity;
            final String openId = (open != null) ? open.get_current_group_id() : null;
            return (openId != null) && openId.equalsIgnoreCase(chatKey);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /**
     * The name the sender has on this device, for a preview without one: only from a database that is already open,
     * and only as long as LOCAL_NAME_WAIT_MS, since the lock of that database may be held by something slow.
     */
    private static String localName(final String senderKey)
    {
        if (TrifaToxService.orma == null)
        {
            return null;
        }
        Future<String> lookup = null;
        try
        {
            lookup = LOCAL_NAME.submit(() -> {
                final List<FriendList> found = TrifaToxService.orma.selectFromFriendList().
                        tox_public_key_stringEq(senderKey).toList();
                if ((found == null) || found.isEmpty())
                {
                    return null;
                }
                final FriendList f = found.get(0);
                return ((f.alias_name != null) && !f.alias_name.trim().isEmpty()) ? f.alias_name : f.name;
            });
            return lookup.get(LOCAL_NAME_WAIT_MS, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            if (lookup != null)
            {
                lookup.cancel(true);
            }
            return null;
        }
    }

    /** {@code held}: the marks the chat's notification holds; this preview's join them on the one posted now. */
    private static void post(final Context context, final NotificationManager nm, final SharedPreferences settings,
                             final String chatKey, final PushPreviewText.Shown shown,
                             final PushPreviewSeen.Event event, final ArrayList<String> held)
    {
        // The defaults the running app ends up with (MainActivity.onResume); the vibration has no switch of its own.
        final boolean sound = settings.getBoolean("notifications_new_message_sound", true);
        final boolean vibrate = settings.getBoolean("notifications_new_message_vibrate", true);

        final NotificationCompat.Builder b = new NotificationCompat.Builder(
                context, HelperMsgNotification.message_channel_id(context, nm, sound, vibrate));
        b.setContentIntent(HelperMsgNotification.open_chat_pending_intent(context, chatKey));
        b.setSmallIcon(R.drawable.circle_orange);
        b.setPriority(NotificationCompat.PRIORITY_HIGH);
        b.setCategory(NotificationCompat.CATEGORY_MESSAGE);
        b.setLights(Color.parseColor("#ffce00"), 500, 500);
        if (vibrate)
        {
            b.setVibrate(new long[]{100, 300});
        }
        b.setContentTitle(shown.title);
        final String body = shown.messageText ? HelperMsgNotification.trim_message_preview(shown.body) : shown.body;
        b.setContentText(body);
        b.setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        b.setAutoCancel(true);

        // The marks of the previews this chat's notification already shows stay on it, with this one's.
        final ArrayList<String> marks = new ArrayList<>(held);
        marks.removeAll(event.marks);
        marks.addAll(event.marks);
        HelperMsgNotification.put_push_preview_marks(b, marks);

        final Notification notification = b.build();
        HelperMsgNotification.post_msg_notification(nm, chatKey, notification);
    }

    /** The words of push previews and reaction notifications, from the app's resources. */
    static PushPreviewText.Labels labels(final Context context)
    {
        return new PushPreviewText.Labels()
        {
            @Override
            public String appTitle()
            {
                return context.getString(R.string.notification_app_title);
            }

            @Override
            public String newMessage()
            {
                return context.getString(R.string.push_preview_new_message);
            }

            @Override
            public String newReaction()
            {
                return context.getString(R.string.push_preview_new_reaction);
            }

            @Override
            public String voice()
            {
                return context.getString(R.string.notification_media_voice);
            }

            @Override
            public String video()
            {
                return context.getString(R.string.notification_media_video);
            }

            @Override
            public String photo()
            {
                return context.getString(R.string.notification_media_photo);
            }

            @Override
            public String file()
            {
                return context.getString(R.string.notification_media_file);
            }

            @Override
            public String fileNamed(final String name)
            {
                return context.getString(R.string.push_preview_file_named, name);
            }

            @Override
            public String reactionToText(final String emoji, final String text)
            {
                return context.getString(R.string.push_preview_reaction_text, emoji, text);
            }

            @Override
            public String reactionToVoice(final String emoji)
            {
                return context.getString(R.string.push_preview_reaction_voice, emoji);
            }

            @Override
            public String reactionToVideo(final String emoji)
            {
                return context.getString(R.string.push_preview_reaction_video, emoji);
            }

            @Override
            public String reactionToPhoto(final String emoji)
            {
                return context.getString(R.string.push_preview_reaction_photo, emoji);
            }

            @Override
            public String reactionToFile(final String emoji)
            {
                return context.getString(R.string.push_preview_reaction_file, emoji);
            }

            @Override
            public String reactionToMessage(final String emoji)
            {
                return context.getString(R.string.push_preview_reaction_message, emoji);
            }

            @Override
            public String location()
            {
                return context.getString(R.string.notification_media_location);
            }

            @Override
            public String reactionToLocation(final String emoji)
            {
                return context.getString(R.string.push_preview_reaction_location, emoji);
            }
        };
    }
}

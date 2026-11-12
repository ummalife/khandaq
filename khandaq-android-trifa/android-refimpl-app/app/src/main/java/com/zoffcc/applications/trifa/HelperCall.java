package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.zoffcc.applications.sorm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import static com.zoffcc.applications.trifa.HelperMessage.insert_into_message_db;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;

public final class HelperCall
{
    public static final int REQUEST_CALL_PERMISSIONS = 4401;
    // Marks the local call-log lines ("Missed call", ...) that logCallEvent writes as our own text rows.
    static final int LOCAL_CALL_LOG_RESEND_COUNT = 99;

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

    /**
     * KHANDAQ (redesign, E9): a call without the access it needs does not start, and a dialog says why
     * with the app's settings one tap away; it used to be a toast. Without the camera a video call may
     * go with sound alone ({@code callWithoutVideo}). Returns the dialog shown, so the call screen can
     * close with it or dismiss it (stage 3b).
     */
    static androidx.appcompat.app.AlertDialog explainMissingPermission(final Activity activity,
                                                                      final boolean audioOnly,
                                                                      final Runnable callWithoutVideo)
    {
        final boolean microphone = hasMicrophonePermission(activity);
        final androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(
                activity).setTitle(microphone ? R.string.air_call_camera_title : R.string.air_call_mic_title)
                         .setMessage(microphone ? R.string.air_call_camera_message : R.string.air_call_mic_message)
                         .setNegativeButton(R.string.air_call_cancel, null);
        if (microphone && !audioOnly && (callWithoutVideo != null))
        {
            builder.setPositiveButton(R.string.air_call_audio_only, (dialog, which) -> callWithoutVideo.run());
            builder.setNeutralButton(R.string.air_call_open_settings, (dialog, which) -> openAppSettings(activity));
        }
        else
        {
            builder.setPositiveButton(R.string.air_call_open_settings, (dialog, which) -> openAppSettings(activity));
        }
        final androidx.appcompat.app.AlertDialog shown = builder.create();
        AirUi.showDialog(activity, shown, false);
        return shown;
    }

    /** The system page of this app, where its permissions are switched on. */
    static void openAppSettings(final Context context)
    {
        try
        {
            final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                             Uri.fromParts("package", context.getPackageName(), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
        catch (Exception e)
        {
            android.util.Log.w("trifa.HelperCall", "openAppSettings:EE:" + e.getMessage());
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

    /**
     * KHANDAQ (redesign, E9): each one-to-one call leaves one entry, written as it ends and saying what
     * happened (design/DESIGN.md, "Files, voice and calls on the phone"): "Call, 4:12" once it was
     * answered, "Missed call" for theirs that nobody answered, "No answer" for ours. The log used to say
     * only the direction as the call began ("Outgoing voice call"), and a missed call wrote two lines.
     */
    private static final Object CALL_ENTRY_LOCK = new Object();
    private static String callEntryPubkey = null;
    private static boolean callEntryIncoming = false;

    /** A call to or from {@code friend_pubkey} begins; its entry waits for the end. */
    static void callBegins(final String friend_pubkey, final boolean incoming)
    {
        if ((friend_pubkey == null) || friend_pubkey.equals("-1"))
        {
            return;
        }
        synchronized (CALL_ENTRY_LOCK)
        {
            callEntryPubkey = friend_pubkey;
            callEntryIncoming = incoming;
        }
    }

    /**
     * The call ends. Every one-to-one call ends in Callstate.reset_values (CallAudioService.stop_me,
     * CallingActivity.stop_active_call, on_call_ended_actions), which calls this first, while
     * call_start_timestamp still holds the moment the call was answered.
     */
    static void callEnds()
    {
        final String pubkey;
        final boolean incoming;
        synchronized (CALL_ENTRY_LOCK)
        {
            pubkey = callEntryPubkey;
            incoming = callEntryIncoming;
            callEntryPubkey = null;
        }
        if ((pubkey == null) || (context_s == null))
        {
            return;
        }
        logCallEntry(pubkey, callEntryText(context_s, incoming, Callstate.call_start_timestamp,
                                           System.currentTimeMillis()));
    }

    static String callEntryText(final Context context, final boolean incoming, final long answeredAtMs,
                                final long endedAtMs)
    {
        if ((answeredAtMs > 0) && (endedAtMs >= answeredAtMs))
        {
            return context.getString(R.string.air_call_entry_format,
                                     AirUi.localDigits(context, callDuration((endedAtMs - answeredAtMs) / 1000L)));
        }
        return context.getString(incoming ? R.string.air_call_missed : R.string.air_call_no_answer);
    }

    /** "0:07", "4:12", "1:02:03": minutes and seconds, and the hours once there are any. */
    static String callDuration(final long seconds)
    {
        final long total = Math.max(0L, seconds);
        final long hours = total / 3600L;
        final long minutes = (total % 3600L) / 60L;
        final long rest = total % 60L;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
                         : String.format(Locale.ROOT, "%d:%02d", minutes, rest);
    }

    /** What a stored call entry says, read back from its words in any language the app ships. */
    static final class StoredCall
    {
        static final int ANSWERED = 0;
        static final int MISSED = 1;
        static final int NO_ANSWER = 2;

        final int kind;
        /** The length of an answered call in ASCII digits ("4:12"); null for the other kinds. */
        final String length;

        StoredCall(final int kind, final String length)
        {
            this.kind = kind;
            this.length = length;
        }
    }

    private static final Object CALL_WORDS_LOCK = new Object();
    private static String[][] callWords = null;

    /**
     * KHANDAQ (redesign, E9): the history keeps an entry in the words of the language the app had when
     * the call ended. Shown, the entry is said again in the app's language now, so a change of language
     * reaches the entries already written, as on iOS. Rows that are not call entries, and entries in
     * words no language of this build has (older builds), come back as they were stored.
     */
    static String shownText(final Context context, final Message m)
    {
        if (m == null)
        {
            return null;
        }
        if ((context == null) || !HelperMessageEdit.isLocalCallLogRow(m))
        {
            return m.text;
        }
        final StoredCall call = readStoredCall(m.text, callWords(context));
        if (call == null)
        {
            return m.text;
        }
        if (call.kind == StoredCall.MISSED)
        {
            return context.getString(R.string.air_call_missed);
        }
        if (call.kind == StoredCall.NO_ANSWER)
        {
            return context.getString(R.string.air_call_no_answer);
        }
        return context.getString(R.string.air_call_entry_format, AirUi.localDigits(context, call.length));
    }

    /**
     * Reads a stored entry against {missed, no answer, "Call, %1$s"} of each language. The length of a
     * call is digits of any script with colons between them; it comes back in ASCII digits.
     */
    static StoredCall readStoredCall(final String stored, final String[][] languages)
    {
        if ((stored == null) || (languages == null))
        {
            return null;
        }
        for (final String[] words : languages)
        {
            if ((words == null) || (words.length < 3))
            {
                continue;
            }
            if (stored.equals(words[0]))
            {
                return new StoredCall(StoredCall.MISSED, null);
            }
            if (stored.equals(words[1]))
            {
                return new StoredCall(StoredCall.NO_ANSWER, null);
            }
            final String format = words[2];
            final int slot = format == null ? -1 : format.indexOf("%1$s");
            if (slot < 0)
            {
                continue;
            }
            final String before = format.substring(0, slot);
            final String after = format.substring(slot + "%1$s".length());
            if ((stored.length() > before.length() + after.length()) && stored.startsWith(before)
                && stored.endsWith(after))
            {
                final String length = stored.substring(before.length(), stored.length() - after.length());
                if (isCallLength(length))
                {
                    return new StoredCall(StoredCall.ANSWERED, AirConversationModel.localDigits(length, '0'));
                }
            }
        }
        return null;
    }

    /** "0:47", "1:02:03" in any digits: decimal digits and colons, starting and ending with a digit. */
    private static boolean isCallLength(final String text)
    {
        if (text.isEmpty() || !isDecimalDigit(text.charAt(0)) || !isDecimalDigit(text.charAt(text.length() - 1)))
        {
            return false;
        }
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if ((c != ':') && !isDecimalDigit(c))
            {
                return false;
            }
        }
        return true;
    }

    private static boolean isDecimalDigit(final char c)
    {
        return Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER;
    }

    /** {missed, no answer, entry format} in every language the app ships: read once, they never change. */
    private static String[][] callWords(final Context context)
    {
        synchronized (CALL_WORDS_LOCK)
        {
            if (callWords == null)
            {
                final String[] codes = AppLocaleHelper.SUPPORTED_LANG_CODES;
                final String[][] table = new String[codes.length][];
                for (int i = 0; i < codes.length; i++)
                {
                    final Configuration configuration = new Configuration(context.getResources().getConfiguration());
                    configuration.setLocale(AppLocaleHelper.localeForCode(codes[i]));
                    final Resources resources = context.createConfigurationContext(configuration).getResources();
                    table[i] = new String[]{resources.getString(R.string.air_call_missed),
                                            resources.getString(R.string.air_call_no_answer),
                                            resources.getString(R.string.air_call_entry_format)};
                }
                callWords = table;
            }
            return callWords;
        }
    }

    public static void logCallEvent(final String friend_pubkey, final int string_id)
    {
        if (context_s == null)
        {
            return;
        }
        logCallEntry(friend_pubkey, context_s.getString(string_id));
    }

    private static void logCallEntry(final String friend_pubkey, final String text)
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
            m.text = text;
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

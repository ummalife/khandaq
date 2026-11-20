package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.net.Uri;

import org.khandaq.messenger.R;

import java.util.Locale;

/**
 * KHANDAQ (testers' ideas, 0.2.67): a document picked with File no longer goes the moment the picker
 * closes. It is asked about from below, with its name and size and Send, Cancel apart (AirQuestion), in
 * a conversation and in a group alike; only Send sends it. A photo or a video picked the same way, or
 * taken with the camera, opens the media preview instead, which has its own Send.
 */
final class AirFileSendQuestion
{
    private AirFileSendQuestion()
    {
    }

    /**
     * Asks before {@code send} runs. Nothing is sent when the sheet cannot be shown (the activity is going)
     * or when it is left any other way than Send.
     */
    static void ask(final Activity activity, final Uri uri, final Runnable send)
    {
        if (activity == null || uri == null || send == null)
        {
            return;
        }
        final String name = HelperFiletransfer.resolve_attachment_display_name(activity, uri, null);
        final long size = HelperFiletransfer.resolve_uri_file_size(activity, uri);
        AirQuestion.ask(activity, activity.getString(R.string.air_file_send_title),
                        what(activity.getString(R.string.air_file_send_what_format), name,
                             size > 0 ? AirByteSize.text(activity, size) : null),
                        activity.getString(R.string.air_compose_send), false, send);
    }

    /** "report.pdf, 2.4 MB" in the language's own comma; the name alone when the size is not known. */
    static String what(final String format, final String name, final String size)
    {
        if (size == null || size.isEmpty())
        {
            return name;
        }
        // Both are words already (the size in the app's digits), so no locale formats anything here.
        return String.format(Locale.ROOT, format, name, size);
    }
}

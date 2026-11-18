package com.zoffcc.applications.trifa;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.net.Uri;
import android.util.Pair;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ContentInfoCompat;
import androidx.core.view.OnReceiveContentListener;
import androidx.core.view.ViewCompat;

/**
 * KHANDAQ (0.2.60): a photo or a video pasted into the field of a chat goes out as one picked from the gallery,
 * through the send preview: pasted from the field's menu (Paste), given by a keyboard (an image or a GIF it offers),
 * or dropped on it. Text, and anything that is not a photo or a video, is pasted by the field as before.
 * <p>
 * The field tells keyboards it takes images and videos ({@link #MIME_TYPES}); AppCompatEditText, and the platform
 * from Android 12, bring a paste, a keyboard's content and a drop here (ViewCompat.performReceiveContent).
 */
final class ComposerMediaPaste
{
    /** What the field takes besides text. */
    static final String[] MIME_TYPES = {"image/*", "video/*"};

    /** Where the photos and videos of one paste go: the chat's send preview, on the main thread. */
    interface Sink
    {
        void send(@NonNull List<Uri> uris);
    }

    private ComposerMediaPaste()
    {
    }

    /** Photos and videos pasted into {@code field} go to {@code sink}; the field keeps everything else. */
    static void attach(@NonNull final View field, @NonNull final Sink sink)
    {
        ViewCompat.setOnReceiveContentListener(field, MIME_TYPES, new Listener(sink));
    }

    static final class Listener implements OnReceiveContentListener
    {
        private final Sink sink;
        /**
         * The last payload that brought media. A keyboard's content is readable only while its InputContentInfo
         * lives, and the payload's extras hold it: the send preview reads the files after this returns.
         */
        @Nullable
        private ContentInfoCompat held;

        Listener(@NonNull final Sink sink)
        {
            this.sink = sink;
        }

        @Nullable
        @Override
        public ContentInfoCompat onReceiveContent(@NonNull final View view, @NonNull final ContentInfoCompat payload)
        {
            final Pair<ContentInfoCompat, ContentInfoCompat> split = split(view.getContext(), payload);
            final List<Uri> uris = uris(split.first);
            if (!uris.isEmpty())
            {
                held = payload;
                sink.send(uris);
            }
            return split.second;
        }
    }

    /**
     * The photos and videos of a payload (first) and the rest, which the field pastes itself (second); either is
     * null when there is none. An item is a photo or a video when it is a file (a uri) and the clip says it holds
     * images or videos, or, for a clip that does not say (a file manager's), when the file is one.
     */
    @NonNull
    static Pair<ContentInfoCompat, ContentInfoCompat> split(@NonNull final Context context,
                                                           @NonNull final ContentInfoCompat payload)
    {
        final ClipDescription description = payload.getClip().getDescription();
        final boolean declared = description != null && (description.hasMimeType(MIME_TYPES[0])
                                                          || description.hasMimeType(MIME_TYPES[1]));
        return payload.partition(item -> item.getUri() != null
                                         && (declared || MediaSendPreviewHelper.isPreviewableMedia(context,
                                                                                                   item.getUri())));
    }

    /** The files of a payload, in order. */
    @NonNull
    static List<Uri> uris(@Nullable final ContentInfoCompat media)
    {
        if (media == null)
        {
            return Collections.emptyList();
        }
        final ClipData clip = media.getClip();
        final List<Uri> uris = new ArrayList<>(clip.getItemCount());
        for (int i = 0; i < clip.getItemCount(); i++)
        {
            final Uri uri = clip.getItemAt(i).getUri();
            if (uri != null)
            {
                uris.add(uri);
            }
        }
        return uris;
    }
}

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * KHANDAQ (Khandaq Testers 8263): Share and Open in another app for a file the app shows itself (a PDF, a
 * photo, a video), from the More button (three dots) of the viewer's top bar. The other app gets one content
 * URI with a read grant for it alone: the file itself when it is a content URI or a plain file under one of
 * the app's FileProvider paths, otherwise a plain copy made for it under tmpdir/share. That copy outlives the
 * viewer, as the other app may read it after the viewer has closed; tmpdir is emptied when the app next
 * starts (HelperGeneric.cleanup_temp_dirs), as for the copies the photo and video viewers already decrypt
 * there.
 */
final class ChatFileShare
{
    private static final String TAG = "trifa.ChatFileShare";

    enum Action
    {
        SHARE,
        VIEW
    }

    /** What the viewer shows: a content URI, a plain path or a path in the encrypted store. */
    static final class Source
    {
        final String path;
        final boolean storageFramework;
        @Nullable
        final String name;
        @Nullable
        final String mimeType;

        Source(final String path, final boolean storageFramework, @Nullable final String name,
               @Nullable final String mimeType)
        {
            this.path = path;
            this.storageFramework = storageFramework;
            this.name = name;
            this.mimeType = mimeType;
        }
    }

    static final String SHARE_DIR = "share";
    /** File systems take 255 bytes for a name; the rest is room for what a receiving app adds to it. */
    static final int MAX_NAME_BYTES = 200;
    static final String FALLBACK_NAME = "file";
    private static final String UNSAFE_NAME_CHARS = "/\\:*?\"<>|";
    /** The longest end of a name still taken for its extension when the name is shortened. */
    private static final int MAX_EXTENSION_CHARS = 16;
    private static final int MENU_SHARE = 1;
    private static final int MENU_OPEN_ELSEWHERE = 2;

    /** One file is made ready at a time: a second tap while the first is being decrypted does nothing. */
    private static final AtomicBoolean PREPARING = new AtomicBoolean(false);
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private ChatFileShare()
    {
    }

    /**
     * The More button (three dots) of a viewer's top bar ({@code R.id.media_overlay_more}): Share, and Open
     * in another app. The source is asked for at the tap, as a viewer may learn a plain copy of its file once
     * it shows it.
     */
    static void bindMoreButton(final Activity activity, final Supplier<Source> source,
                               @Nullable final View progress)
    {
        final View more = activity.findViewById(R.id.media_overlay_more);
        if (more == null)
        {
            return;
        }
        more.setOnClickListener(anchor -> {
            final PopupMenu menu = new PopupMenu(activity, anchor);
            menu.getMenu().add(0, MENU_SHARE, 0, R.string.air_file_share);
            menu.getMenu().add(0, MENU_OPEN_ELSEWHERE, 1, R.string.media_viewer_open_elsewhere);
            menu.setOnMenuItemClickListener(item -> {
                final Action action = (item.getItemId() == MENU_OPEN_ELSEWHERE) ? Action.VIEW : Action.SHARE;
                start(activity, source.get(), action, progress, null);
                return true;
            });
            menu.show();
        });
    }

    /**
     * Makes the file ready off the main thread and starts the system chooser for it; {@code done} runs on
     * the main thread afterwards, whether the chooser could start or not.
     */
    static void start(final Activity activity, @Nullable final Source source, final Action action,
                      @Nullable final View progress, @Nullable final Runnable done)
    {
        if ((source == null) || !PREPARING.compareAndSet(false, true))
        {
            if (done != null)
            {
                done.run();
            }
            return;
        }

        if (progress != null)
        {
            progress.setVisibility(View.VISIBLE);
        }
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            Uri ready = null;
            try
            {
                ready = prepare(app, source);
            }
            catch (Exception e)
            {
                // No path in the log: it names the file.
                Log.i(TAG, "start:prepare:EE:" + e.getClass().getSimpleName());
            }
            finally
            {
                // Even after an Error: the next tap must not find a share still under way.
                final Uri uri = ready;
                activity.runOnUiThread(() -> prepared(activity, source, action, progress, done, uri));
            }
        }, "file-share").start();
    }

    private static void prepared(final Activity activity, final Source source, final Action action,
                                 @Nullable final View progress, @Nullable final Runnable done, @Nullable final Uri uri)
    {
        PREPARING.set(false);
        if (progress != null)
        {
            progress.setVisibility(View.GONE);
        }
        if (!activity.isFinishing() && !activity.isDestroyed())
        {
            if (uri == null)
            {
                sayFailed(activity);
            }
            else
            {
                launch(activity, uri, mimeTypeOf(source), action);
            }
        }
        if (done != null)
        {
            done.run();
        }
    }

    private static void launch(final Activity activity, final Uri uri, final String mimeType, final Action action)
    {
        final Intent target;
        if (action == Action.VIEW)
        {
            target = new Intent(Intent.ACTION_VIEW);
            target.setDataAndType(uri, mimeType);
        }
        else
        {
            target = new Intent(Intent.ACTION_SEND);
            target.setType(mimeType);
            target.putExtra(Intent.EXTRA_STREAM, uri);
        }
        target.setClipData(ClipData.newRawUri("", uri));
        target.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final CharSequence title = activity.getString(
                (action == Action.VIEW) ? R.string.media_viewer_open_elsewhere : R.string.air_file_share);
        try
        {
            activity.startActivity(Intent.createChooser(target, title));
        }
        catch (Exception e)
        {
            Log.i(TAG, "launch:EE:" + e.getClass().getSimpleName());
            sayFailed(activity);
        }
    }

    private static void sayFailed(final Activity activity)
    {
        // The viewer stays on screen, over the conversation and its line under the field: a toast.
        HelperGeneric.display_toast_with_context(activity, activity.getString(R.string.chat_opening_file_failed),
                                                 false, 0);
    }

    /** A content URI another app can read with a grant (worker thread). */
    private static Uri prepare(final Context context, final Source source) throws IOException
    {
        final String path = source.path;
        if (TextUtils.isEmpty(path))
        {
            throw new FileNotFoundException("no file");
        }
        if (source.storageFramework || path.startsWith("content://"))
        {
            return Uri.parse(path);
        }

        if (MainActivity.VFS_ENCRYPT && HelperFiletransfer.isIocipherVirtualPath(path))
        {
            final info.guardianproject.iocipher.File encrypted = new info.guardianproject.iocipher.File(path);
            if (!encrypted.exists())
            {
                throw new FileNotFoundException("missing file");
            }
            final File copy = copyTarget(source);
            try (InputStream in = new info.guardianproject.iocipher.FileInputStream(encrypted))
            {
                Files.copy(in, copy.toPath());
            }
            catch (IOException | RuntimeException e)
            {
                deleteQuietly(copy);
                throw e;
            }
            return FileProvider.getUriForFile(context, KhandaqProviders.STD_FILE_PROVIDER, copy);
        }

        final File plain = new File(path);
        if (!plain.isFile())
        {
            throw new FileNotFoundException("missing file");
        }
        final Uri direct = providerUri(context, plain);
        if (direct != null)
        {
            return direct;
        }
        final File copy = copyTarget(source);
        try
        {
            Files.copy(plain.toPath(), copy.toPath());
        }
        catch (IOException | RuntimeException e)
        {
            deleteQuietly(copy);
            throw e;
        }
        return FileProvider.getUriForFile(context, KhandaqProviders.STD_FILE_PROVIDER, copy);
    }

    /** The URI of a plain file under one of the app's FileProvider paths, or null when it is under none. */
    @Nullable
    private static Uri providerUri(final Context context, final File file)
    {
        for (final String authority : new String[]{KhandaqProviders.STD_FILE_PROVIDER,
                                                   KhandaqProviders.EXT2_PROVIDER})
        {
            try
            {
                return FileProvider.getUriForFile(context, authority, file);
            }
            catch (IllegalArgumentException outsideItsPaths)
            {
                // Try the next provider.
            }
        }
        return null;
    }

    /** A new folder of its own under tmpdir/share, so that the copy can keep the name of the file. */
    private static File copyTarget(final Source source) throws IOException
    {
        String name = source.name;
        if (TextUtils.isEmpty(name))
        {
            name = new File(source.path).getName();
        }
        name = safeFileName(name, FALLBACK_NAME);
        if ((name.lastIndexOf('.') <= 0) && !TextUtils.isEmpty(source.mimeType))
        {
            final String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(source.mimeType);
            if (!TextUtils.isEmpty(extension))
            {
                name = name + "." + extension;
            }
        }
        final File dir = new File(new File(MainActivity.SD_CARD_TMP_DIR, SHARE_DIR),
                                  System.currentTimeMillis() + "-" + SEQUENCE.incrementAndGet());
        if (!dir.isDirectory() && !dir.mkdirs())
        {
            throw new IOException("share dir not made");
        }
        return new File(dir, name);
    }

    private static String mimeTypeOf(final Source source)
    {
        String mimeType = source.mimeType;
        if (TextUtils.isEmpty(mimeType) || "application/octet-stream".equals(mimeType))
        {
            final String name = TextUtils.isEmpty(source.name) ? source.path : source.name;
            mimeType = HelperFiletransfer.guess_mime_type_from_filename(name);
        }
        return TextUtils.isEmpty(mimeType) ? "application/octet-stream" : mimeType;
    }

    private static void deleteQuietly(final File file)
    {
        if (file.exists() && !file.delete())
        {
            Log.w(TAG, "deleteQuietly:copy left");
        }
    }

    /**
     * A name for a copy handed to another app: no folder in it, no control character, no leading or trailing
     * dot or space, at most {@link #MAX_NAME_BYTES} bytes in UTF-8 with its extension kept; {@code fallback}
     * when nothing is left.
     */
    static String safeFileName(@Nullable final String name, final String fallback)
    {
        final StringBuilder clean = new StringBuilder();
        if (name != null)
        {
            int i = 0;
            while (i < name.length())
            {
                final int codePoint = name.codePointAt(i);
                i += Character.charCount(codePoint);
                final boolean unsafe = (codePoint < 0x20) || (codePoint == 0x7f)
                        || (UNSAFE_NAME_CHARS.indexOf(codePoint) >= 0);
                if (unsafe)
                {
                    clean.append('_');
                }
                else
                {
                    clean.appendCodePoint(codePoint);
                }
            }
        }

        int start = 0;
        int end = clean.length();
        while ((start < end) && isTrimmed(clean.charAt(start)))
        {
            start++;
        }
        while ((end > start) && isTrimmed(clean.charAt(end - 1)))
        {
            end--;
        }
        final String safe = (start < end) ? clean.substring(start, end) : fallback;
        if (utf8Length(safe) <= MAX_NAME_BYTES)
        {
            return safe;
        }

        final int dot = safe.lastIndexOf('.');
        final boolean keepsExtension = (dot > 0) && ((safe.length() - dot) <= MAX_EXTENSION_CHARS);
        final String extension = keepsExtension ? safe.substring(dot) : "";
        final String base = keepsExtension ? safe.substring(0, dot) : safe;
        return cutToUtf8(base, MAX_NAME_BYTES - utf8Length(extension)) + extension;
    }

    private static boolean isTrimmed(final char c)
    {
        return (c == '.') || Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static int utf8Length(final String text)
    {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The longest start of {@code text} of at most {@code maxBytes} bytes in UTF-8, cut between characters. */
    private static String cutToUtf8(final String text, final int maxBytes)
    {
        int bytes = 0;
        int i = 0;
        while (i < text.length())
        {
            final int codePoint = text.codePointAt(i);
            final int size = (codePoint < 0x80) ? 1 : (codePoint < 0x800) ? 2 : (codePoint < 0x10000) ? 3 : 4;
            if ((bytes + size) > maxBytes)
            {
                break;
            }
            bytes += size;
            i += Character.charCount(codePoint);
        }
        return text.substring(0, i);
    }
}

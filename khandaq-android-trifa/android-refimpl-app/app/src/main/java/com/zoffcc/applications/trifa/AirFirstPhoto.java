package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * KHANDAQ (testers' ideas, 0.2.67): the photo that can come with the name on the first page of the first launch
 * (AirFirstLaunchActivity). Picked and cropped as the You screen does it, it waits in the app's own files until the
 * profile exists. Once Tox runs with the new profile it becomes our photo through the pipeline of the You screen
 * (ProfileContentFragment.save_own_avatar), into the profile's encrypted file store, and the waiting copy goes.
 * Starting the first launch again, moving a profile in instead, or deleting the profile forgets it.
 */
final class AirFirstPhoto
{
    private static final String TAG = "trifa.AirFirstPhoto";
    /** Set when a profile was made with a photo; cleared once the photo is ours, or forgotten. */
    static final String PREF_PENDING = "khandaq_first_launch_photo_pending";
    static final String FILE_NAME = "first_launch_photo.jpg";

    private AirFirstPhoto()
    {
    }

    /** Where the photo waits: the app's own files, not the cache, so that it is there when the profile is. */
    static File file(final Context context)
    {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    static boolean has(final Context context)
    {
        return usable(file(context));
    }

    static boolean usable(@Nullable final File photo)
    {
        return photo != null && photo.isFile() && photo.length() > 0;
    }

    /**
     * Puts a fresh crop in the place of the photo, replacing the one kept before. The crop goes either way; false
     * when it held nothing or could not be moved, and then no photo is kept.
     */
    static boolean moveInto(final File crop, final File photo)
    {
        try
        {
            if (!usable(crop))
            {
                return false;
            }
            // The cache and the files of the app lie on one file system: a rename, else a copy.
            if (crop.renameTo(photo))
            {
                return usable(photo);
            }
            copy(crop, photo);
            return usable(photo) && photo.length() == crop.length();
        }
        catch (IOException e)
        {
            Log.i(TAG, "move:EE:" + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            photo.delete();
            return false;
        }
        finally
        {
            //noinspection ResultOfMethodCallIgnored
            crop.delete();
        }
    }

    /** The waiting photo for the tile of the first page, sharp enough at {@code side} pixels; null without one. */
    @Nullable
    static Bitmap preview(final Context context, final int side)
    {
        final File photo = file(context);
        if (!usable(photo))
        {
            return null;
        }
        return ProfileContentFragment.decode_downsampled_bitmap(photo.getAbsolutePath(), Math.max(1, 2 * side));
    }

    /** Forgets the photo: its file, and the mark that it waits for the profile. */
    static void forget(final Context context)
    {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_PENDING).apply();
    }

    /**
     * From the Tox thread once the profile is open (TrifaToxService, where the name of the first launch is set): a
     * photo that waits becomes ours, on a thread of its own so that Tox does not wait for it. Nothing waits on most
     * starts, and then this only reads one preference.
     */
    static void applySoon(@Nullable final Context context)
    {
        if (context == null)
        {
            return;
        }
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!PreferenceManager.getDefaultSharedPreferences(app).getBoolean(PREF_PENDING, false))
        {
            return;
        }
        new Thread(() -> apply(app), "first_launch_photo").start();
    }

    /** The waiting photo becomes ours, once, then its copy goes. One at a time: a second start finds it done. */
    private static synchronized void apply(final Context context)
    {
        try
        {
            if (!PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_PENDING, false))
            {
                return;
            }
            final File photo = file(context);
            if (!usable(photo))
            {
                forget(context);
                return;
            }
            // The pipeline copies through the outgoing folder of the main screen and writes into the file store of
            // the profile: without them it waits for the next start.
            if (TrifaToxService.vfs == null || TrifaToxService.orma == null || MainActivity.context_s == null
                || TextUtils.isEmpty(MainActivity.SD_CARD_FILES_OUTGOING_WRAPPER_DIR))
            {
                Log.i(TAG, "apply:not yet");
                return;
            }
            final int failure = ProfileContentFragment.save_own_avatar(context, Uri.fromFile(photo));
            forget(context);
            if (failure != 0)
            {
                // Chosen again on the You screen if wanted; the step that failed is in the log.
                Log.i(TAG, "apply:failed");
                return;
            }
            Log.i(TAG, "apply:done");
            HelperGeneric.send_avatar_to_all_friends();
            // Our tile on the Air screen and on You.
            new Handler(Looper.getMainLooper()).post(MainActivity::update_main_profile_bar);
        }
        catch (Throwable e)
        {
            Log.i(TAG, "apply:EE:" + e.getMessage());
        }
    }

    private static void copy(final File from, final File to) throws IOException
    {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to))
        {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                out.write(buffer, 0, read);
            }
        }
    }
}

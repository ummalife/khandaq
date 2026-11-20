package com.zoffcc.applications.trifa;

import android.content.Context;

import java.io.File;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * KHANDAQ (beta 2026-11-20): where the crash files are, for the handler that writes them
 * (MainApplication.save_error_msg) and for the crash report on the About page that sends them.
 *
 * The handler has written the stack trace and the log of a crash into the app's own folder since the audit of
 * 2026-08-20, while the report kept looking in /sdcard/trifa/crashes, which the app cannot write to since Android 10
 * (requestLegacyExternalStorage is false). So the report went out without the crash. Both now take the folder here,
 * and the paths of KhandaqProviders.STD_FILE_PROVIDER (xml/stdfilepaths.xml) cover both of its places.
 */
final class CrashReportFiles
{
    static final String DIR_NAME = "crashes";
    /** The files the handler writes, one a crash: crash_yyyy-MM-dd_HH_mm_ss.txt. */
    static final String CRASH_PREFIX = "crash_";
    /** The file the About page writes when the report is sent: the device, the version and the log of now. */
    static final String REPORT_PREFIX = "report_";
    static final String SUFFIX = ".txt";

    private CrashReportFiles()
    {
    }

    /**
     * The folder crash files go to: the app's external files, else its internal ones, both private to the app.
     * External storage can be absent or unmounted; internal is always there. Losing the crash file would be worse.
     */
    @NonNull
    static File dir(@NonNull final Context context)
    {
        final File external = context.getExternalFilesDir(null);
        return external != null ? new File(external, DIR_NAME) : internalDir(context);
    }

    /** Both folders a crash file can be in: the one of now first, then internal, written when external was absent. */
    @NonNull
    static File[] dirs(@NonNull final Context context)
    {
        final File now = dir(context);
        final File internal = internalDir(context);
        return now.equals(internal) ? new File[]{now} : new File[]{now, internal};
    }

    @NonNull
    private static File internalDir(@NonNull final Context context)
    {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /** The crash file written last in the folders; null when there is none. */
    @Nullable
    static File newestCrash(@Nullable final File... folders)
    {
        if (folders == null)
        {
            return null;
        }
        File newest = null;
        for (final File folder : folders)
        {
            final File[] files = folder == null ? null : folder.listFiles();
            if (files == null)
            {
                continue;
            }
            for (final File file : files)
            {
                if (isCrashFile(file) && (newest == null || isNewer(file, newest)))
                {
                    newest = file;
                }
            }
        }
        return newest;
    }

    /** A crash file of the handler with something in it. */
    static boolean isCrashFile(@Nullable final File file)
    {
        return file != null && file.isFile() && file.length() > 0 && file.getName().startsWith(CRASH_PREFIX)
               && file.getName().endsWith(SUFFIX);
    }

    /** By the time written; of two written in the same second, the later name, whose date has the seconds too. */
    private static boolean isNewer(@NonNull final File file, @NonNull final File than)
    {
        final long a = file.lastModified();
        final long b = than.lastModified();
        return a != b ? a > b : file.getName().compareTo(than.getName()) > 0;
    }

    /** The name of a report written at that moment, in the form of the handler's names. */
    @NonNull
    static String reportName(@NonNull final String date)
    {
        return REPORT_PREFIX + date + SUFFIX;
    }

    /** Deletes the reports sent before, so that the folder keeps the crash files and one report at most. */
    static void dropReports(@Nullable final File folder)
    {
        final File[] files = folder == null ? null : folder.listFiles();
        if (files == null)
        {
            return;
        }
        for (final File file : files)
        {
            if (file.isFile() && file.getName().startsWith(REPORT_PREFIX) && file.getName().endsWith(SUFFIX))
            {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }
}

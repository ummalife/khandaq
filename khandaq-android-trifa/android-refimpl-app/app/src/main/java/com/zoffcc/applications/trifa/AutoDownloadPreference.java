package com.zoffcc.applications.trifa;

import android.content.SharedPreferences;

import static com.zoffcc.applications.trifa.TRIFAGlobals.AUTO_ACCEPT_FT_MAX_ANYKIND_SIZE_IN_MB;
import static com.zoffcc.applications.trifa.TRIFAGlobals.AUTO_ACCEPT_FT_MAX_IMAGE_SIZE_IN_MB;
import static com.zoffcc.applications.trifa.TRIFAGlobals.AUTO_ACCEPT_FT_MAX_VIDEO_SIZE_IN_MB;
import static com.zoffcc.applications.trifa.TRIFAGlobals.KHANDAQ_MAX_FILE_TRANSFER_BYTES;

/**
 * KHANDAQ (0.2.57, SPEC-network part 3): a 1:1 file up to 200 MB downloads by itself, and the person can turn
 * that off in Settings. The three switches there (pictures, videos, all files) are on for a new install, and they
 * are turned on once for an install that had them off, at its first start of 0.2.57; what the person sets after
 * that is read as it is. The attachment download mode (Never, Wi-Fi only, Always) is left as the person set it:
 * groups read it too, and their files keep their own rules. A voice note downloads by itself whatever the
 * switches say, as before. The caps of TRIFAGlobals stay.
 */
final class AutoDownloadPreference
{
    static final String KEY_PICTURES = "auto_accept_image";
    static final String KEY_VIDEOS = "auto_accept_video";
    static final String KEY_ALL_FILES = "auto_accept_all_upto";
    /** Set once the three switches were turned on for this install. */
    static final String APPLIED_KEY = "auto_download_default_v0257_applied";
    static final boolean DEFAULT = true;

    private static final long MB = 1024L * 1024L;

    private AutoDownloadPreference()
    {
    }

    /** Turns the three switches on, once for this install, before they are read; true when it did so now. */
    static boolean applyDefaultOnce(final SharedPreferences settings)
    {
        if (settings.getBoolean(APPLIED_KEY, false))
        {
            return false;
        }
        settings.edit().
                putBoolean(KEY_PICTURES, true).
                putBoolean(KEY_VIDEOS, true).
                putBoolean(KEY_ALL_FILES, true).
                putBoolean(APPLIED_KEY, true).
                apply();
        return true;
    }

    /**
     * Whether a 1:1 file of theirs downloads by itself, with the caps of TRIFAGlobals. {@code mode}: 0 never,
     * 1 Wi-Fi only, 2 always; {@code onWifi} counts for mode 1 only; {@code mime} may be null.
     */
    static boolean allows(final int mode, final boolean onWifi, final boolean pictures, final boolean videos,
                          final boolean allFiles, final long size, final String mime, final boolean voice)
    {
        return allows(mode, onWifi, pictures, videos, allFiles, size, mime, voice,
                      AUTO_ACCEPT_FT_MAX_IMAGE_SIZE_IN_MB * MB, AUTO_ACCEPT_FT_MAX_VIDEO_SIZE_IN_MB * MB,
                      AUTO_ACCEPT_FT_MAX_ANYKIND_SIZE_IN_MB * MB);
    }

    /** The rule with its caps in bytes (JVM tests cannot load TRIFAGlobals, whose colours need Android). */
    static boolean allows(final int mode, final boolean onWifi, final boolean pictures, final boolean videos,
                          final boolean allFiles, final long size, final String mime, final boolean voice,
                          final long pictureCap, final long videoCap, final long anyCap)
    {
        if ((size <= 0) || (size > KHANDAQ_MAX_FILE_TRANSFER_BYTES))
        {
            return false;
        }
        if (voice)
        {
            return true;
        }
        if ((mode == 0) || ((mode == 1) && !onWifi))
        {
            return false;
        }
        if (allFiles && (size <= anyCap))
        {
            return true;
        }
        if (mime == null)
        {
            return false;
        }
        if (pictures && mime.startsWith("image/") && (size <= pictureCap))
        {
            return true;
        }
        return videos && mime.startsWith("video/") && (size <= videoCap);
    }
}

package com.zoffcc.applications.trifa;

import android.net.Uri;

import org.khandaq.messenger.BuildConfig;

/** FileProvider authority strings scoped to {@link BuildConfig#APPLICATION_ID}. */
public final class KhandaqProviders
{
    public static final String STD_FILE_PROVIDER = BuildConfig.APPLICATION_ID + ".std_fileprovider";
    public static final String EXT1_FILE_PROVIDER = BuildConfig.APPLICATION_ID + ".ext1_fileprovider";
    public static final String EXT2_PROVIDER = BuildConfig.APPLICATION_ID + ".ext2_provider";

    private KhandaqProviders()
    {
    }

    public static Uri ext1FilesUri()
    {
        return Uri.parse("content://" + EXT1_FILE_PROVIDER + "/");
    }
}

package com.zoffcc.applications.trifa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * KHANDAQ (redesign, stage 5): puts the demo world's overlay back when the process starts, debug builds only.
 * A provider is created before the application and before any screen or service, so a demo contact or group
 * is never added to toxcore by a start that came before the overlay. It serves no data.
 */
public final class AirDemoWorldInstaller extends ContentProvider
{
    @Override
    public boolean onCreate()
    {
        final Context context = getContext();
        if (context != null)
        {
            AirDemoWorld.install(context);
        }
        return true;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull final Uri uri, @Nullable final String[] projection, @Nullable final String selection,
                        @Nullable final String[] selectionArgs, @Nullable final String sortOrder)
    {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull final Uri uri)
    {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull final Uri uri, @Nullable final ContentValues values)
    {
        return null;
    }

    @Override
    public int delete(@NonNull final Uri uri, @Nullable final String selection, @Nullable final String[] selectionArgs)
    {
        return 0;
    }

    @Override
    public int update(@NonNull final Uri uri, @Nullable final ContentValues values, @Nullable final String selection,
                      @Nullable final String[] selectionArgs)
    {
        return 0;
    }
}

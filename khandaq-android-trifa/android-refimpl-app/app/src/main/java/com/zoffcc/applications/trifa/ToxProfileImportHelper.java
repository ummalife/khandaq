package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import static com.zoffcc.applications.trifa.HelperGeneric.import_toxsave_file_unsecure;
import static com.zoffcc.applications.trifa.HelperGeneric.io_file_copy;
import static com.zoffcc.applications.trifa.MainActivity.export_savedata_file_unsecure;

/**
 * Shared helpers for Tox profile export/import (.tox savedata) via Storage Access Framework.
 */
public final class ToxProfileImportHelper
{
    private static final String TAG = "trifa.ToxProfileImport";
    static final String EXTRA_OPEN_IMPORT_PICKER = "open_import_picker";
    static final String EXTRA_OPEN_EXPORT_PICKER = "open_export_picker";
    static final String EXPORT_SUGGESTED_FILENAME = "khandaq-profile.tox";
    private static final long MIN_SAVEDATA_BYTES = 64L;
    static final String[] TOX_IMPORT_MIME_TYPES = new String[] {
            "application/octet-stream",
            "application/x-tox",
            "*/*",
    };

    public enum ImportMode
    {
        FIRST_LAUNCH,
        REPLACE_EXISTING
    }

    private ToxProfileImportHelper()
    {
    }

    static void ensureStoragePathsInitialized(@NonNull final Context context)
    {
        if (MainActivity.app_files_directory == null || MainActivity.app_files_directory.isEmpty())
        {
            MainActivity.app_files_directory = context.getApplicationContext().getFilesDir().getAbsolutePath();
        }

        if (MainActivity.SD_CARD_FILES_EXPORT_DIR == null || MainActivity.SD_CARD_FILES_EXPORT_DIR.isEmpty())
        {
            final File exportDir = context.getApplicationContext().getExternalFilesDir(null);
            if (exportDir != null)
            {
                MainActivity.SD_CARD_FILES_EXPORT_DIR = exportDir.getAbsolutePath() + "/vfs_export/";
            }
        }
    }

    @NonNull
    static File savedataDestination(@NonNull final Context context)
    {
        ensureStoragePathsInitialized(context);
        return new File(MainActivity.app_files_directory, "savedata.tox");
    }

    static void promptExportSavedata(@NonNull final AppCompatActivity activity,
                                     @NonNull final Runnable launchCreateDocument)
    {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_export_tox_profile)
                .setMessage(R.string.settings_export_tox_profile_confirm_picker)
                .setPositiveButton(R.string.settings_export_tox_profile_confirm_yes, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int id)
                    {
                        launchCreateDocument.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static void handleExportDestination(@NonNull final Context context, @Nullable final Uri destinationUri)
    {
        if (destinationUri == null)
        {
            return;
        }

        ensureStoragePathsInitialized(context);
        final File staging = new File(context.getCacheDir(), "export_savedata.tox");

        try
        {
            export_savedata_file_unsecure("_", staging.getAbsolutePath());

            if (!staging.exists() || staging.length() < MIN_SAVEDATA_BYTES)
            {
                Toast.makeText(context, R.string.settings_export_tox_profile_failed, Toast.LENGTH_LONG).show();
                return;
            }

            try (InputStream in = new FileInputStream(staging);
                 OutputStream out = context.getContentResolver().openOutputStream(destinationUri))
            {
                if (out == null)
                {
                    throw new java.io.IOException("Unable to open export destination");
                }

                final byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0)
                {
                    out.write(buf, 0, len);
                }
            }

            staging.delete();

            final String label = resolveUriDisplayLabel(context, destinationUri);
            new AlertDialog.Builder(context)
                    .setTitle(R.string.settings_export_tox_profile)
                    .setMessage(context.getString(R.string.settings_export_tox_profile_done_persists, label))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
        catch (Exception e)
        {
            Log.e(TAG, "export failed: " + e.getMessage(), e);
            Toast.makeText(context, R.string.settings_export_tox_profile_failed, Toast.LENGTH_LONG).show();
        }
    }

    @NonNull
    private static String resolveUriDisplayLabel(@NonNull final Context context, @NonNull final Uri uri)
    {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null))
        {
            if (cursor != null && cursor.moveToFirst())
            {
                final String name = cursor.getString(0);
                if (name != null && !name.isEmpty())
                {
                    return name;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return uri.toString();
    }

    static boolean copyUriToFile(@NonNull final Context context, @NonNull final Uri uri, @NonNull final File dst)
            throws java.io.IOException
    {
        try (InputStream in = context.getContentResolver().openInputStream(uri))
        {
            if (in == null)
            {
                throw new java.io.IOException("Unable to open selected file");
            }

            final File parent = dst.getParentFile();
            if (parent != null)
            {
                parent.mkdirs();
            }

            try (FileOutputStream out = new FileOutputStream(dst))
            {
                final byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0)
                {
                    out.write(buf, 0, len);
                }
            }
        }

        return dst.exists() && dst.length() >= MIN_SAVEDATA_BYTES;
    }

    static final int TOXSAVE_OK = 0;
    static final int TOXSAVE_ENCRYPTED = 1;
    static final int TOXSAVE_INVALID = 2;

    // KHANDAQ #153: an encrypted or non-.tox file used to be imported blindly; on restart the
    // native tox_new() then failed and the app crash-looped on a NULL tox handle.
    // A plain tox save starts with 00 00 00 00 1F 1B ED 15 (STATE_COOKIE_GLOBAL, little-endian),
    // a toxencryptsave file starts with the magic "toxEsave".
    static int validateToxSaveFile(@NonNull final File f)
    {
        try (FileInputStream in = new FileInputStream(f))
        {
            final byte[] head = new byte[8];
            if (in.read(head) != 8)
            {
                return TOXSAVE_INVALID;
            }

            if ((head[0] == 't') && (head[1] == 'o') && (head[2] == 'x') && (head[3] == 'E') &&
                (head[4] == 's') && (head[5] == 'a') && (head[6] == 'v') && (head[7] == 'e'))
            {
                return TOXSAVE_ENCRYPTED;
            }

            if ((head[0] == 0) && (head[1] == 0) && (head[2] == 0) && (head[3] == 0) &&
                ((head[4] & 0xff) == 0x1f) && ((head[5] & 0xff) == 0x1b) &&
                ((head[6] & 0xff) == 0xed) && ((head[7] & 0xff) == 0x15))
            {
                return TOXSAVE_OK;
            }

            return TOXSAVE_INVALID;
        }
        catch (Exception e)
        {
            return TOXSAVE_INVALID;
        }
    }

    static void showImportError(@NonNull final Context context, @NonNull final String message)
    {
        new AlertDialog.Builder(context)
                .setTitle(R.string.settings_import_tox_profile)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    static void handlePickedUri(@NonNull final AppCompatActivity activity,
                                @Nullable final Uri uri,
                                @NonNull final ImportMode mode,
                                @Nullable final Runnable onFirstLaunchSuccess)
    {
        if (uri == null)
        {
            return;
        }

        ensureStoragePathsInitialized(activity);

        try
        {
            final File importStaging = new File(activity.getCacheDir(), "import_savedata.tox");
            if (!copyUriToFile(activity, uri, importStaging))
            {
                showImportError(activity, activity.getString(R.string.settings_import_tox_profile_invalid_file));
                return;
            }

            final int validity = validateToxSaveFile(importStaging);
            if (validity != TOXSAVE_OK)
            {
                importStaging.delete();
                showImportError(activity, activity.getString(
                        (validity == TOXSAVE_ENCRYPTED) ? R.string.settings_import_tox_profile_encrypted_file
                                                        : R.string.settings_import_tox_profile_invalid_file));
                return;
            }

            switch (mode)
            {
                case FIRST_LAUNCH:
                    io_file_copy(importStaging, savedataDestination(activity));
                    importStaging.delete();
                    if (onFirstLaunchSuccess != null)
                    {
                        onFirstLaunchSuccess.run();
                    }
                    break;

                case REPLACE_EXISTING:
                    import_toxsave_file_unsecure(activity, importStaging);
                    break;
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "import failed: " + e.getMessage(), e);
            showImportError(activity, activity.getString(R.string.settings_import_tox_profile_failed));
        }
    }

    static void showReplaceImportConfirmation(@NonNull final AppCompatActivity activity,
                                              @NonNull final Runnable onPickFile)
    {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_import_tox_profile)
                .setMessage(buildImportDialogMessage(activity, false))
                .setPositiveButton(R.string.settings_import_tox_profile_pick_file, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int id)
                    {
                        onPickFile.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    static void showFirstLaunchImportInfo(@NonNull final AppCompatActivity activity,
                                          @NonNull final Runnable onPickFile)
    {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_import_tox_profile)
                .setMessage(buildImportDialogMessage(activity, true))
                .setPositiveButton(R.string.settings_import_tox_profile_pick_file, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int id)
                    {
                        onPickFile.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @NonNull
    private static String buildImportDialogMessage(@NonNull final AppCompatActivity activity, final boolean firstLaunch)
    {
        final String base = firstLaunch
                ? activity.getString(R.string.layout___first_launch_import_profile_hint)
                : activity.getString(R.string.settings_import_tox_profile_replace_warning);

        return base + "\n\n" + activity.getString(R.string.settings_import_tox_profile_picker_hint);
    }
}

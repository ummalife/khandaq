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
    // package-visible: ExportActivity applies the same "did the JNI actually write a savedata" floor
    static final long MIN_SAVEDATA_BYTES = 64L;
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
            // KHANDAQ (audit #15): the JNI returns without writing when tox_global is NULL, so a staging
            // file left by an earlier run (process killed between write and delete) would be copied out
            // as if it were fresh. Same pre-delete as ExportActivity's bundle sweep.
            //noinspection ResultOfMethodCallIgnored
            staging.delete();

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
        finally
        {
            // KHANDAQ (audit #15): the staging file is the Tox PRIVATE KEY in the clear. It used to be
            // deleted only on the success path, so the early return on a short/missing export and every
            // failure between the JNI write and the copy (unopenable destination, I/O error) left it
            // sitting in the app cache. Delete it on every exit instead.
            //noinspection ResultOfMethodCallIgnored
            staging.delete();
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
        // KHANDAQ (#249 QA): every refusal here — encrypted file, bad magic, failed copy — used to
        // reach the user as a dialog and NOTHING else, so a rejected import is indistinguishable in
        // logcat from a picker whose result never came back. That cost a QA round: the staged .tox
        // had been copied straight out of app storage, where savedata is always "toxEsave"-encrypted,
        // instead of being produced by Settings → Export profile (which writes it in the clear).
        Log.i(TAG, "import refused: " + message);
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

        final File importStaging = new File(activity.getCacheDir(), "import_savedata.tox");
        // KHANDAQ (audit #15): true once import_toxsave_file_unsecure() has taken ownership of the
        // staging file — it copies and then deletes it on its own background thread, so deleting it
        // here would pull the profile out from under the import. Every other exit must delete it.
        boolean handedOver = false;

        try
        {
            if (!copyUriToFile(activity, uri, importStaging))
            {
                showImportError(activity, activity.getString(R.string.settings_import_tox_profile_invalid_file));
                return;
            }

            final int validity = validateToxSaveFile(importStaging);
            if (validity != TOXSAVE_OK)
            {
                showImportError(activity, activity.getString(
                        (validity == TOXSAVE_ENCRYPTED) ? R.string.settings_import_tox_profile_encrypted_file
                                                        : R.string.settings_import_tox_profile_invalid_file));
                return;
            }

            switch (mode)
            {
                case FIRST_LAUNCH:
                    io_file_copy(importStaging, savedataDestination(activity));
                    // deleted here as well as in the finally: onFirstLaunchSuccess may never return
                    // (it can restart the process), and then no finally would run.
                    //noinspection ResultOfMethodCallIgnored
                    importStaging.delete();
                    if (onFirstLaunchSuccess != null)
                    {
                        onFirstLaunchSuccess.run();
                    }
                    break;

                case REPLACE_EXISTING:
                    import_toxsave_file_unsecure(activity, importStaging);
                    handedOver = true;
                    break;
            }
        }
        catch (Exception e)
        {
            Log.e(TAG, "import failed: " + e.getMessage(), e);
            showImportError(activity, activity.getString(R.string.settings_import_tox_profile_failed));
        }
        finally
        {
            if (!handedOver)
            {
                // plaintext private key: gone on the invalid/encrypted early returns and on any throw
                //noinspection ResultOfMethodCallIgnored
                importStaging.delete();
            }
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

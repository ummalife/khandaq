package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.khandaq.messenger.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * KHANDAQ (#28): Android UI layer over {@link BackupHelper} — password prompt, file gathering and
 * background export of a {@code .kbk} encrypted backup (DB + VFS + identity, key wrapped under a
 * passphrase). Restore lives in the same class in a later phase.
 */
final class PasswordBackupHelper
{
    private static final String TAG = "trifa.PwBackup";
    static final String SUGGESTED_FILENAME = "khandaq-backup.kbk";
    static final String MIME_TYPE = "application/octet-stream";
    private static final int MIN_PASSPHRASE_LEN = 8;

    private PasswordBackupHelper()
    {
    }

    interface PassphraseConsumer
    {
        void onPassphrase(char[] passphrase);
    }

    /**
     * Show a "set a password (twice) + warning" dialog. On confirm with a valid, matching passphrase
     * it calls {@code consumer}; the caller then launches the SAF create-document picker.
     */
    static void promptForBackupPassphrase(final Activity activity, final PassphraseConsumer consumer)
    {
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText pass1 = passwordField(activity, activity.getString(R.string.backup_password_enter));
        final EditText pass2 = passwordField(activity, activity.getString(R.string.backup_password_confirm));
        final TextView warn = new TextView(activity);
        warn.setText(activity.getString(R.string.backup_password_warning));
        warn.setPadding(0, pad / 2, 0, 0);

        box.addView(pass1);
        box.addView(pass2);
        box.addView(warn);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.backup_password_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)   // overridden below to keep dialog open on error
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                {
                    final String p1 = pass1.getText().toString();
                    final String p2 = pass2.getText().toString();
                    if (p1.length() < MIN_PASSPHRASE_LEN)
                    {
                        Toast.makeText(activity, R.string.backup_password_too_short, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (!p1.equals(p2))
                    {
                        Toast.makeText(activity, R.string.backup_password_mismatch, Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    consumer.onPassphrase(p1.toCharArray());
                }));
        dialog.show();
    }

    private static EditText passwordField(final Context context, final String hint)
    {
        final EditText e = new EditText(context);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    /**
     * Background write of the backup container to {@code uri}. Reads the live identity, the resolved
     * DB key and the on-disk SQLCipher DB + IOCipher VFS (consistent at commit boundaries — the DB is
     * in DELETE journal mode), and streams them through {@link BackupHelper}.
     */
    static void performBackup(final Activity activity, final android.net.Uri uri, final char[] passphrase)
    {
        if (uri == null)
        {
            scrub(passphrase);
            return;
        }

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle(R.string.backup_password_title);
        progress.setMessage(activity.getString(R.string.backup_in_progress));
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setCancelable(false);
        progress.setMax(100);
        try { progress.show(); } catch (Exception ignored) {}

        new Thread(() ->
        {
            File tmpTox = null;
            String error = null;
            try
            {
                final String dbKey = MainActivity.PREF__DB_secrect_key;
                if (dbKey == null || dbKey.isEmpty())
                {
                    throw new BackupHelper.BackupException("database key unavailable");
                }

                // live Tox identity
                tmpTox = new File(activity.getCacheDir(), "backup_savedata.tox");
                MainActivity.export_savedata_file_unsecure("_", tmpTox.getAbsolutePath());
                final byte[] toxBytes = readAll(tmpTox);

                final File dbFile = new File(activity.getDir("dbs", Context.MODE_PRIVATE), MainActivity.MAIN_DB_NAME);
                final File vfsFile = new File(activity.getDir("vfs", Context.MODE_PRIVATE), MainActivity.MAIN_VFS_NAME);
                if (!dbFile.isFile() || !vfsFile.isFile())
                {
                    throw new BackupHelper.BackupException("profile files missing");
                }

                int versionCode = 0;
                try
                {
                    versionCode = activity.getPackageManager()
                            .getPackageInfo(activity.getPackageName(), 0).versionCode;
                }
                catch (Exception ignored)
                {
                }

                final BackupHelper.ProgressListener listener = (done, total) ->
                {
                    final int pct = total > 0 ? (int) (done * 100L / total) : 0;
                    activity.runOnUiThread(() ->
                    {
                        try { progress.setProgress(pct); } catch (Exception ignored) {}
                    });
                    return true;
                };

                try (OutputStream out = activity.getContentResolver().openOutputStream(uri))
                {
                    if (out == null)
                    {
                        throw new BackupHelper.BackupException("cannot open destination");
                    }
                    BackupHelper.writeBackup(out, passphrase, System.currentTimeMillis(), versionCode,
                            dbKey, toxBytes, dbFile, vfsFile, listener);
                }
            }
            catch (Exception e)
            {
                error = e.getMessage();
                Log.w(TAG, "performBackup failed: " + error);
            }
            finally
            {
                if (tmpTox != null) { try { tmpTox.delete(); } catch (Exception ignored) {} }
                scrub(passphrase);
            }

            final String finalError = error;
            activity.runOnUiThread(() ->
            {
                try { if (progress.isShowing()) { progress.dismiss(); } } catch (Exception ignored) {}
                final boolean ok = finalError == null;
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.backup_password_title)
                        .setMessage(ok ? activity.getString(R.string.backup_done)
                                       : activity.getString(R.string.backup_failed) + "\n" + finalError)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "kbk-backup").start();
    }

    private static byte[] readAll(final File f) throws java.io.IOException
    {
        final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        try (InputStream in = new FileInputStream(f))
        {
            int n;
            while ((n = in.read(buf)) > 0)
            {
                bos.write(buf, 0, n);
            }
        }
        return bos.toByteArray();
    }

    private static void scrub(final char[] passphrase)
    {
        if (passphrase != null)
        {
            java.util.Arrays.fill(passphrase, '\0');
        }
    }
}

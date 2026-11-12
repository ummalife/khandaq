package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
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
     * The "set a password (twice) + warning" sheet from below (E21). On the verb with a valid, matching
     * passphrase it calls {@code consumer}; the caller then launches the SAF create-document picker.
     */
    static void promptForBackupPassphrase(final Activity activity, final PassphraseConsumer consumer)
    {
        final AirSheet sheet = AirSheet.create(activity, activity.getString(R.string.backup_password_title),
                                               activity.getString(R.string.backup_password_warning));
        if (sheet == null)
        {
            return;
        }
        final AirStepKit.LineField pass1 = new AirStepKit.LineField(
                sheet.context, activity.getString(R.string.backup_password_enter), true);
        final AirStepKit.LineField pass2 = new AirStepKit.LineField(
                sheet.context, activity.getString(R.string.backup_password_confirm), true);
        sheet.add(pass1, R.dimen.kq_space_16);
        sheet.add(pass2, R.dimen.kq_space_12);

        // E21: a refusal stands under the field it is about, and goes once that field changes.
        final TextWatcher endRefusals = new TextWatcher()
        {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after)
            {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count)
            {
            }

            @Override
            public void afterTextChanged(final Editable s)
            {
                AirUi.endUnder(pass1);
                AirUi.endUnder(pass2);
            }
        };
        pass1.edit.addTextChangedListener(endRefusals);
        pass2.edit.addTextChangedListener(endRefusals);

        // The verb says what comes next; a refusal keeps the sheet open with what was typed.
        sheet.verb(activity.getString(R.string.air_backup_choose_place), false, v ->
        {
            final String p1 = pass1.text();
            final String p2 = pass2.text();
            if (p1.length() < MIN_PASSPHRASE_LEN)
            {
                AirUi.sayUnder(pass1, activity.getString(R.string.backup_password_too_short), false);
                return;
            }
            if (!p1.equals(p2))
            {
                AirUi.sayUnder(pass2, activity.getString(R.string.backup_password_mismatch), false);
                return;
            }
            final char[] passphrase = p1.toCharArray();
            sheet.dismissThen(() -> consumer.onPassphrase(passphrase));
        });
        sheet.cancel(null);
        sheet.withKeyboard().show();
        AirStepKit.showKeyboard(pass1.edit);
    }

    /**
     * Background write of the backup container to {@code uri}. Reads the live identity, the resolved
     * DB key and the on-disk SQLCipher DB + IOCipher VFS (consistent at commit boundaries — the DB is
     * in DELETE journal mode), and streams them through {@link BackupHelper}.
     */
    static void performBackup(final Activity activity, final android.net.Uri uri, final char[] passphrase)
    {
        performBackup(activity, uri, passphrase, null);
    }

    /**
     * As above; {@code anchor} is the row that started it (Settings, Export profile): what came of the
     * backup stands under it (E21), and without one it is said from below.
     */
    static void performBackup(final Activity activity, final android.net.Uri uri, final char[] passphrase,
                              @Nullable final View anchor)
    {
        if (uri == null)
        {
            scrub(passphrase);
            return;
        }

        // E21: a sheet that waits on the work, with a still bar that moves only as the work does.
        final AirSheet progress = AirSheet.create(activity, activity.getString(R.string.backup_password_title),
                                                  activity.getString(R.string.backup_in_progress));
        final AirSheet.Progress bar = progress == null ? null
                                                       : progress.add(new AirSheet.Progress(progress.context),
                                                                      R.dimen.kq_space_24);
        if (progress != null)
        {
            progress.holdOpen().show();
        }

        new Thread(() ->
        {
            File tmpTox = null;
            String error = null;
            boolean failed = false;
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

                final int[] shown = {-1};
                final BackupHelper.ProgressListener listener = (done, total) ->
                {
                    final int pct = total > 0 ? (int) (done * 100L / total) : 0;
                    // Once per percent, not once per 8 KiB block.
                    if (bar != null && pct != shown[0])
                    {
                        shown[0] = pct;
                        activity.runOnUiThread(() -> bar.set(pct));
                    }
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
                // Failure is the catch itself, not a message: an EOFException has none, and a backup
                // that did not finish must never be reported as made.
                failed = true;
                error = describe(e);
                Log.w(TAG, "performBackup failed: " + error);
            }
            finally
            {
                if (tmpTox != null) { try { tmpTox.delete(); } catch (Exception ignored) {} }
                scrub(passphrase);
            }

            final boolean ok = !failed;
            final String finalError = error;
            activity.runOnUiThread(() ->
            {
                if (progress != null)
                {
                    progress.dismiss();
                }
                final String title = activity.getString(R.string.backup_password_title);
                if (ok)
                {
                    AirSheet.sayUnderOrTell(anchor, activity, title, activity.getString(R.string.backup_done), false);
                }
                else
                {
                    AirSheet.sayUnderOrTell(anchor, activity, title,
                                            activity.getString(R.string.backup_failed) + "\n" + finalError, true);
                }
            });
        }, "kbk-backup").start();
    }

    /** The reason of a failure for its line: the exception's message, or its kind when it has none. */
    private static String describe(final Exception e)
    {
        final String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    private static EditText passwordField(final Context context, final String hint)
    {
        final EditText e = new EditText(context);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    /** Single-field passphrase prompt for restore (the maintenance screen; it keeps its old dialogs). */
    static void promptPassphraseForRestore(final Activity activity, final PassphraseConsumer consumer)
    {
        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (activity.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);
        final EditText pass = passwordField(activity, activity.getString(R.string.restore_password_enter));
        box.addView(pass);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.restore_password_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) ->
                        consumer.onPassphrase(pass.getText().toString().toCharArray()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Background decrypt + integrity-verify of a {@code .kbk} into staging, then hand off to the
     * destructive apply. The live profile is only touched after the backup fully verifies.
     */
    static void performRestore(final Activity activity, final android.net.Uri uri, final char[] passphrase)
    {
        if (uri == null)
        {
            scrub(passphrase);
            return;
        }

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle(R.string.restore_password_title);
        progress.setMessage(activity.getString(R.string.restore_in_progress));
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setCancelable(false);
        progress.setMax(100);
        try { progress.show(); } catch (Exception ignored) {}

        new Thread(() ->
        {
            final File stagedDb = new File(activity.getCacheDir(), "restore_db.tmp");
            final File stagedVfs = new File(activity.getCacheDir(), "restore_vfs.tmp");
            String error = null;
            boolean failed = false;
            BackupHelper.Manifest manifest = null;
            try (InputStream in = activity.getContentResolver().openInputStream(uri))
            {
                if (in == null)
                {
                    throw new BackupHelper.BackupException("cannot open backup file");
                }
                manifest = BackupHelper.readHeader(in, passphrase);

                int versionCode = 0;
                try
                {
                    versionCode = activity.getPackageManager()
                            .getPackageInfo(activity.getPackageName(), 0).versionCode;
                }
                catch (Exception ignored)
                {
                }
                if (manifest.appVersionCode > versionCode)
                {
                    throw new BackupHelper.BackupException(activity.getString(R.string.restore_too_new));
                }

                final ProgressDialog p = progress;
                final BackupHelper.ProgressListener listener = (done, total) ->
                {
                    final int pct = total > 0 ? (int) (done * 100L / total) : 0;
                    activity.runOnUiThread(() ->
                    {
                        try { p.setProgress(pct); } catch (Exception ignored) {}
                    });
                    return true;
                };
                BackupHelper.extractBlobs(in, manifest, stagedDb, stagedVfs, listener);
            }
            catch (Exception e)
            {
                // KHANDAQ (stage 5): the catch decides, not the message. readLong() at the end of a backup
                // cut right after its database throws an EOFException with no message, and a null message
                // used to read as success and hand half-extracted staging to the destructive apply.
                failed = true;
                error = describe(e);
                Log.w(TAG, "performRestore decrypt/verify failed: " + error);
            }

            final String finalError = error;
            final boolean finalFailed = failed;
            final BackupHelper.Manifest finalManifest = manifest;
            activity.runOnUiThread(() ->
            {
                try { if (progress.isShowing()) { progress.dismiss(); } } catch (Exception ignored) {}
                if (finalFailed || finalManifest == null)
                {
                    try { stagedDb.delete(); stagedVfs.delete(); } catch (Exception ignored) {}
                    scrub(passphrase);
                    new AlertDialog.Builder(activity)
                            .setTitle(R.string.restore_password_title)
                            .setMessage(activity.getString(R.string.restore_failed) + "\n"
                                    + (finalError != null ? finalError : ""))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                scrub(passphrase);
                // staging is verified — hand off to the destructive apply (stop tox, wipe, copy, restart)
                HelperGeneric.applyRestoredProfileFromBackup(activity, stagedDb, stagedVfs,
                        finalManifest.toxSavedata, finalManifest.dbKey);
            });
        }, "kbk-restore-decrypt").start();
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

/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2024 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;

import static com.zoffcc.applications.trifa.CallingActivity.initializeScreenshotSecurity;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.io_file_copy;
import static com.zoffcc.applications.trifa.MainActivity.DB_SHM_EXT;
import static com.zoffcc.applications.trifa.MainActivity.DB_WAL_EXT;
import static com.zoffcc.applications.trifa.MainActivity.MAIN_DB_NAME;
import static com.zoffcc.applications.trifa.MainActivity.MAIN_VFS_NAME;
import static com.zoffcc.applications.trifa.MainActivity.PREF__DB_secrect_key;
import static com.zoffcc.applications.trifa.MainActivity.PREF__window_security;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_FULL_FILES_EXPORT_DIR;
import static com.zoffcc.applications.trifa.MainActivity.export_savedata_file_unsecure;
import static com.zoffcc.applications.trifa.MainActivity.manually_log_out;

public class ExportActivity extends AppCompatActivity
{
    /**
     * KHANDAQ (audit2 #6): destination picker for the ENCRYPTED backup, so this screen can offer it as
     * its default action instead of only writing the plaintext bundle. Same plumbing as
     * MaintenanceActivity's — registered in onCreate because registerForActivityResult must run before
     * the activity is STARTED.
     */
    private androidx.activity.result.ActivityResultLauncher<String> backup_create_launcher;
    private char[] pending_backup_passphrase;
    /** KHANDAQ (re-audit 2026-08-21, R-06): device re-auth in front of the raw bundle. */
    private PlaintextExportGate export_gate;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_export);

        // KHANDAQ (re-audit 2026-08-21, R-06): same rule as the launchers - built in onCreate().
        export_gate = new PlaintextExportGate(this, () -> ExportActivity.this);

        backup_create_launcher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
                        PasswordBackupHelper.MIME_TYPE),
                uri ->
                {
                    final char[] pp = pending_backup_passphrase;
                    pending_backup_passphrase = null;
                    if (uri != null && pp != null)
                    {
                        PasswordBackupHelper.performBackup(ExportActivity.this, uri, pp);
                    }
                    else if (pp != null)
                    {
                        java.util.Arrays.fill(pp, '\0');   // picker cancelled — never leave it in memory
                    }
                });

        // KHANDAQ (audit #15): the bundle written here holds the Tox PRIVATE KEY in the clear, so stale
        // copies must not pile up — but the sweep deliberately does NOT run on entering this screen.
        // This is also the screen that shows the database password, so the normal flow is
        // "export -> leave -> come back to copy the password": sweeping on entry would delete the very
        // bundle the user just made. The wipe therefore happens where it cannot destroy a finished
        // export: right before a new export writes, and on any failed/aborted export.

        if (PREF__window_security)
        {
            // prevent screenshots and also dont show the window content in recent activity screen
            initializeScreenshotSecurity(this);
        }

        TextView text_toxpass = findViewById(R.id.text_toxpass);
        TextView text_dbpass = findViewById(R.id.text_dbpass);

        Intent intent = getIntent();
        final String act = intent != null ? intent.getStringExtra("act") : null;

        if ("MaintenanceActivity".equals(act))
        {
            text_toxpass.setText(TrifaSetPatternActivity.bytesToString(TrifaSetPatternActivity.sha256(TrifaSetPatternActivity.StringToBytes2(PREF__DB_secrect_key))));
            text_dbpass.setText(PREF__DB_secrect_key);
        }
        else
        {
            text_toxpass.setText("");
            text_dbpass.setText("");
        }

        Button export_full_as_zip = findViewById(R.id.export_full_as_zip);

        export_full_as_zip.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                try
                {
                    AlertDialog.Builder builder = new AlertDialog.Builder(v.getContext());
                    builder.setTitle(R.string.maintenance_export_all_files_title);
                    // KHANDAQ (audit #15): this used to name SD_CARD_FILES_EXPORT_DIR (= .../vfs_export/)
                    // + SD_CARD_FULL_FILES_EXPORT_DIR, a directory nothing is ever written to. The user
                    // could neither find the bundle nor delete the plaintext key in it. Name the real path.
                    final String shown_dir = full_export_dir_path(v.getContext());
                    builder.setMessage(v.getContext().getString(R.string.maintenance_export_all_files_message,
                            (shown_dir != null) ? shown_dir : SD_CARD_FULL_FILES_EXPORT_DIR));

                    // KHANDAQ (audit2 #6): the encrypted backup is now the DEFAULT action of this dialog and
                    // the raw bundle is the deliberate, explicitly-labelled alternative. The reviewer asked
                    // to "make the existing password-encrypted backup format the default and deprecate raw
                    // identity export"; that format already exists (PasswordBackupHelper / BackupHelper) and
                    // carries the same three things this bundle does — Tox identity, DB key, DB + VFS — so
                    // nothing is lost by steering here. The raw path is kept, not removed: other Tox clients
                    // import a plain savedata file, and silently taking that away would strand users.
                    builder.setPositiveButton(R.string.backup_password_title, new DialogInterface.OnClickListener()
                    {
                        public void onClick(DialogInterface dialog, int id)
                        {
                            start_encrypted_backup();
                        }
                    });
                    // KHANDAQ (re-audit 2026-08-21, R-06): the raw bundle carries the Tox private key
                    // and the database key in the clear. Picking it now costs a second, explicit
                    // confirmation and the device credential - the encrypted route above costs neither.
                    builder.setNeutralButton(R.string.maintenance_export_confirm, new DialogInterface.OnClickListener()
                    {
                        public void onClick(DialogInterface dialog, int id)
                        {
                            export_gate.require(R.string.plaintext_export_warning_title,
                                                R.string.plaintext_export_warning_message,
                                                R.string.maintenance_export_confirm,
                                                new Runnable()
                                                {
                                                    @Override
                                                    public void run()
                                                    {
                                                        // The bundle is written straight to app-owned
                                                        // storage rather than through a SAF picker, so
                                                        // the grant is spent here instead.
                                                        PlaintextExportGate.clearAuthorisation();
                                                        export_all_files(v.getContext());
                                                    }
                                                });
                        }
                    });
                    builder.setNegativeButton(R.string.cancel, null);

                    AlertDialog dialog = builder.create();
                    dialog.show();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });
    }

    /** KHANDAQ (audit2 #6): the encrypted route — ask for a passphrase, then for a destination. */
    private void start_encrypted_backup()
    {
        try
        {
            PasswordBackupHelper.promptForBackupPassphrase(ExportActivity.this, passphrase ->
            {
                pending_backup_passphrase = passphrase;
                backup_create_launcher.launch(PasswordBackupHelper.SUGGESTED_FILENAME);
            });
        }
        catch (Exception e)
        {
            // Never silently fall back to writing plaintext — that is the whole point of this change.
            android.util.Log.w("trifa.ExportActivity", "start_encrypted_backup failed: " + e.getMessage());
            display_toast(getString(R.string.backup_failed), true, 400);
        }
    }

    /**
     * the one true location of the "export all files" bundle. the confirmation dialog and the writer
     * must not disagree about it, so both ask here. null if external storage is not available.
     */
    static String full_export_dir_path(final Context context)
    {
        final File ext_dir = context.getExternalFilesDir(null);

        if (ext_dir == null)
        {
            return null;
        }

        return ext_dir.getAbsolutePath() + SD_CARD_FULL_FILES_EXPORT_DIR;
    }

    /**
     * delete everything a previous "export all files" run left in the bundle directory, above all
     * unsecure_export_savedata.tox (plaintext Tox private key). best effort, never throws.
     */
    static void sweep_full_export_dir(final Context context)
    {
        try
        {
            final String dir_path = full_export_dir_path(context);

            if (dir_path == null)
            {
                return;
            }

            final File[] stale = new File(dir_path).listFiles();

            if (stale == null)
            {
                return;
            }

            for (File f : stale)
            {
                if (f.isDirectory())
                {
                    sweep_dir_recursive(f);
                }

                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static void sweep_dir_recursive(final File dir)
    {
        final File[] children = dir.listFiles();

        if (children == null)
        {
            return;
        }

        for (File f : children)
        {
            if (f.isDirectory())
            {
                sweep_dir_recursive(f);
            }

            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public static void export_all_files(final Context context)
    {
        try
        {
            new export_all_files_async_task(context).execute();
        }
        catch (Exception e)
        {
        }
    }

    private static class export_all_files_async_task extends AsyncTask<Void, Void, Boolean>
    {
        private ProgressDialog dialog;
        private final Context c;

        public export_all_files_async_task(Context c)
        {
            this.c = c;
            dialog = new ProgressDialog(c);
        }

        @Override
        protected void onPreExecute()
        {
            manually_log_out();

            dialog.setMessage(c.getString(R.string.maintenance_exporting));
            dialog.setCancelable(false);
            dialog.show();
        }

        @Override
        protected Boolean doInBackground(Void... args)
        {
            boolean export_complete = false;

            try
            {
                // KHANDAQ (audit #15): unlike the rest of this bundle (SQLCipher DB + IOCipher VFS,
                // both encrypted) the .tox file below is the Tox PRIVATE KEY in the clear, and it lives
                // on EXTERNAL storage. Wipe the whole previous bundle before writing a new one, so no
                // stale key survives and a run that dies mid-way cannot pass old data off as fresh.
                sweep_full_export_dir(c);

                String export_dir_string = full_export_dir_path(c);

                if (export_dir_string == null)
                {
                    throw new java.io.IOException("no external files dir");
                }

                // make directory
                File export_dir = new File(export_dir_string);
                export_dir.mkdirs();

                // first export the tox save file unencrypted
                File export_tox_file = new File(export_dir_string + "/" + "unsecure_export_savedata.tox");
                export_savedata_file_unsecure("_", export_tox_file.getAbsolutePath());

                // KHANDAQ (audit #15): the JNI returns without writing anything when tox_global is NULL
                // (it races the tox teardown that onPreExecute kicks off). That used to still produce a
                // "export complete" toast and a bundle with no identity in it, which only shows up when
                // the user tries to import it on the new device. Fail loudly instead.
                if ((!export_tox_file.exists()) || (export_tox_file.length() < ToxProfileImportHelper.MIN_SAVEDATA_BYTES))
                {
                    throw new java.io.IOException("savedata not written");
                }

                // now export all other files
                final ArrayList<String> files_to_export = new ArrayList<>();
                final ArrayList<String> files_to_export_optional = new ArrayList<>();
                files_to_export.add(c.getDir("dbs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_DB_NAME);
                files_to_export_optional.add(c.getDir("dbs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_DB_NAME + DB_SHM_EXT);
                files_to_export_optional.add(c.getDir("dbs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_DB_NAME + DB_WAL_EXT);
                //
                files_to_export.add(c.getDir("vfs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_VFS_NAME);
                files_to_export_optional.add(c.getDir("vfs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_VFS_NAME + DB_SHM_EXT);
                files_to_export_optional.add(c.getDir("vfs", MODE_PRIVATE).getAbsolutePath() + "/" + MAIN_VFS_NAME + DB_WAL_EXT);

                for (String export_file : files_to_export)
                {
                    final String dst_file = export_dir_string + "/" + new File(export_file).getName();
                    io_file_copy(new File(export_file), new File(dst_file));
                }

                for (String export_file : files_to_export_optional)
                {
                    try
                    {
                        final String dst_file = export_dir_string + "/" + new File(export_file).getName();
                        io_file_copy(new File(export_file), new File(dst_file));
                    }
                    catch(Exception e)
                    {
                    }
                }

                export_complete = true;
            }
            catch (Exception e)
            {
                e.printStackTrace();
                display_toast(c.getString(R.string.maintenance_export_failed), true, 0);
                return false;
            }
            finally
            {
                // KHANDAQ (audit #15): on success the plaintext savedata IS the deliverable — the user
                // collects the bundle by hand over USB/a file manager, so it has to stay until the next
                // export or the next entry into this screen sweeps it. A failed export has no
                // deliverable, so nothing of it — least of all the private key — may survive.
                if (!export_complete)
                {
                    sweep_full_export_dir(c);
                }
            }

            return true;
        }

        @Override
        protected void onPostExecute(Boolean result)
        {
            if (dialog.isShowing())
            {
                dialog.dismiss();
            }

            if (result)
            {
                display_toast(c.getString(R.string.maintenance_export_ready), true, 0);
            }
        }
    }
}
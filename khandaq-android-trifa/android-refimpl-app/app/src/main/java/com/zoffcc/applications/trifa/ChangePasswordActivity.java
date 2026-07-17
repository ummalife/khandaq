package com.zoffcc.applications.trifa;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.zoffcc.applications.sorm.OrmaDatabase;

import org.khandaq.messenger.R;

import java.io.File;

/**
 * KHANDAQ (#10): Профиль → Детали → Изменить пароль. Verifies the current password (or the live
 * auto-key for skip-mode profiles), derives the new passphrase with a candidate salt (committed
 * only on success), stages both Keystore-wrapped, then restarts the app — the actual crash-safe
 * re-encryption runs in the fresh process in {@link DbRekeyHelper#performPendingRekeyIfNeeded}
 * before the databases are opened.
 */
public class ChangePasswordActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.ChangePwActy";

    private EditText oldPassword;
    private EditText newPassword1;
    private EditText newPassword2;
    private Button saveButton;
    private SharedPreferences prefs;
    private boolean manualMode = false;
    private volatile boolean working = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        manualMode = DbSecretKeyStorage.prefersManualPasswordUnlock(this);

        oldPassword = findViewById(R.id.cp_old_password);
        newPassword1 = findViewById(R.id.cp_new_password_1);
        newPassword2 = findViewById(R.id.cp_new_password_2);
        saveButton = findViewById(R.id.cp_save_button);

        if (!manualMode)
        {
            // skip-mode profile: no current password exists — this flow SETS the first one
            findViewById(R.id.cp_old_label).setVisibility(View.GONE);
            oldPassword.setVisibility(View.GONE);
        }

        findViewById(R.id.cp_back_button).setOnClickListener(v -> finish());
        saveButton.setOnClickListener(v -> onSavePressed());
    }

    private void onSavePressed()
    {
        if (working)
        {
            return;
        }

        final String oldPw = oldPassword.getText().toString();
        final String newPw1 = newPassword1.getText().toString();
        final String newPw2 = newPassword2.getText().toString();

        if (manualMode && oldPw.isEmpty())
        {
            Toast.makeText(this, R.string.cp_err_old_wrong, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!newPw1.equals(newPw2))
        {
            Toast.makeText(this, R.string.cp_err_mismatch, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!SetPasswordActivity.isPasswordValid(newPw1))
        {
            Toast.makeText(this, R.string.cp_err_weak, Toast.LENGTH_SHORT).show();
            return;
        }

        working = true;
        saveButton.setEnabled(false);

        // PBKDF2 + probes off the UI thread
        new Thread(() -> {
            String errToast = null;
            String oldKey = null;
            String newKey = null;
            String saltB64 = null;

            try
            {
                final String dbPath = getDir("dbs", MODE_PRIVATE).getAbsolutePath()
                                      + "/" + MainActivity.MAIN_DB_NAME;
                final boolean haveDb = new File(dbPath).isFile();

                // ---- verify the CURRENT key ------------------------------------------------------
                if (manualMode)
                {
                    oldKey = DbSecretKeyStorage.unlockManualPasswordHash(this, oldPw);
                    if (oldKey == null)
                    {
                        errToast = getString(R.string.cp_err_old_wrong);
                    }
                }
                else
                {
                    // skip-mode: the live resolved key IS the current passphrase. The static can be
                    // a dummy after a process re-creation — fall back to resolving it from storage.
                    oldKey = MainActivity.PREF__DB_secrect_key;
                    if (oldKey == null || oldKey.isEmpty()
                        || (haveDb && !OrmaDatabase.probeEncryptedDatabase(dbPath, oldKey)))
                    {
                        oldKey = DbSecretKeyStorage.resolveDbSecretKey(this);
                    }
                    if (oldKey == null || oldKey.isEmpty()
                        || (haveDb && !OrmaDatabase.probeEncryptedDatabase(dbPath, oldKey)))
                    {
                        errToast = getString(R.string.cp_err_generic);
                    }
                }

                // ---- derive the NEW key with a candidate salt (no side effects) ------------------
                if (errToast == null)
                {
                    // ALWAYS a fresh salt: a stolen old salt must not stay useful for offline
                    // dictionaries against the new password. Committed only when the rekey succeeds.
                    saltB64 = DbSecretKeyStorage.generateSaltB64();
                    newKey = DbSecretKeyStorage.deriveManualPasswordHashWithSalt(newPw1, saltB64);
                    if (newKey.equals(oldKey))
                    {
                        errToast = getString(R.string.cp_err_same);
                    }
                }
            }
            catch (Exception e)
            {
                errToast = getString(R.string.cp_err_generic);
            }

            final String fErr = errToast;
            final String fOld = oldKey;
            final String fNew = newKey;
            final String fSalt = saltB64;
            runOnUiThread(() -> {
                working = false;
                saveButton.setEnabled(true);
                if (isFinishing() || isDestroyed())
                {
                    return;
                }
                if (fErr != null)
                {
                    Toast.makeText(this, fErr, Toast.LENGTH_SHORT).show();
                    return;
                }
                confirmAndStage(fOld, fNew, fSalt);
            });
        }).start();
    }

    private void confirmAndStage(final String oldKey, final String newKey, final String saltB64)
    {
        new AlertDialog.Builder(this)
                .setTitle(R.string.cp_title)
                .setMessage(R.string.cp_confirm_text)
                .setNegativeButton(R.string.MainActivity_no_button, null)
                .setPositiveButton(R.string.MainActivity_button_ok, (d, w) -> {
                    if (!DbSecretKeyStorage.stagePendingRekey(this, oldKey, newKey, saltB64))
                    {
                        Toast.makeText(this, R.string.cp_err_generic, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // KHANDAQ (rekey): savedata.tox is toxencryptsave-encrypted with sha256(OLD key)
                    // — the SQLCipher rekey alone left it behind, so the restarted app failed with
                    // «файл повреждён или защищён паролем», parked the profile as .broken and
                    // created a NEW Tox identity (tester report). Snapshot the live savedata now so
                    // the rekey process can move it onto the new key.
                    if (!DbRekeyHelper.stageSavedataSnapshot(this))
                    {
                        DbSecretKeyStorage.clearPendingRekey(this);
                        Toast.makeText(this, R.string.cp_err_generic, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // after the restart the user must log in with the NEW password
                    DbSecretKeyStorage.setSessionLoggedIn(this, false);
                    // same shutdown pattern as the profile wipe: log out, end the process so the
                    // fresh launch performs the rekey before any DB is opened
                    try
                    {
                        MainActivity.manually_log_out();
                    }
                    catch (Exception ignored)
                    {
                    }
                    TRIFAGlobals.PREF__DB_secrect_key__user_hash = "";
                    Toast.makeText(this, R.string.cp_restarting, Toast.LENGTH_SHORT).show();
                    try
                    {
                        finishAffinity();
                    }
                    catch (Exception ignored)
                    {
                    }
                    System.exit(0);
                })
                .show();
    }
}

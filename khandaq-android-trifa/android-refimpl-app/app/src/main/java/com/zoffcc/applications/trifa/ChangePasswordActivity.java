package com.zoffcc.applications.trifa;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DimenRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.zoffcc.applications.sorm.OrmaDatabase;

import org.khandaq.messenger.R;

import java.io.File;

/**
 * KHANDAQ (#10): Profile, Password and PIN, Change password. Verifies the current password (or the live
 * auto-key for a profile without a password), derives the new passphrase with a candidate salt (committed
 * only on success), stages both Keystore-wrapped, then ends the process: the actual crash-safe
 * re-encryption runs in the next process in {@link DbRekeyHelper#performPendingRekeyIfNeeded} before the
 * databases are opened.
 *
 * KHANDAQ (redesign stage 4, E20): drawn as ChangePasswordController on iOS. A profile with a password
 * changes it: the old one, then the new one twice; a profile that opens with the key kept on this phone
 * sets its first one. The name is the username half of the form and the fields carry the autofill hints,
 * so a password manager saves the pair. The rule stands under the new pair; the action waits, as Log in
 * does, while a field is empty; a refusal stands under the field it concerns, never in a toast, and a
 * wrong old password shakes its field once (not with reduced motion). What the step does next is asked
 * from below, with the verb on the button.
 */
public class ChangePasswordActivity extends AirScreenActivity
{
    private static final String TAG = "trifa.ChangePwActy";
    /** The new-password autofill hint (androidx.autofill HintConstants.AUTOFILL_HINT_NEW_PASSWORD). */
    private static final String AUTOFILL_HINT_NEW_PASSWORD = "newPassword";

    // What the check off the UI thread found.
    private static final int CHECK_OK = 0;
    private static final int CHECK_WRONG_OLD = 1;
    private static final int CHECK_KEY_FAILED = 2;
    private static final int CHECK_SAME = 3;
    private static final int CHECK_FAILED = 4;

    private boolean manualMode = false;
    private volatile boolean working = false;
    /** Set once the rekey is staged and the process is about to end; the fields are then not cleared. */
    private boolean staged = false;
    @Nullable
    private AirStepKit.LineField oldField;
    @Nullable
    private TextView oldNote;
    private AirStepKit.LineField newField;
    private AirStepKit.LineField repeatField;
    private TextView newNote;
    private AirStepKit.MainBar bar;
    @Nullable
    private BottomSheetDialog question;

    @Override
    protected int screenTitle()
    {
        return DbSecretKeyStorage.prefersManualPasswordUnlock(this) ? R.string.pd_change_password
                                                                    : R.string.air_first_password_set;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        manualMode = DbSecretKeyStorage.prefersManualPasswordUnlock(this);
        final String title = getString(manualMode ? R.string.pd_change_password : R.string.air_first_password_set);

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            // The title stands on the page in the display face, as on the other password pages.
            actionBar.setTitle(null);
        }
        setTitle(title);
        // Done in the last field hands the focus to the column, so no field raises the keyboard again.
        column.setFocusableInTouchMode(true);
        column.setDefaultFocusHighlightEnabled(false);

        column.addView(AirStepKit.header(this, title, manualMode ? null : getString(R.string.air_password_set_body)),
                       AirScreenKit.matchWidth());
        final String name = DbSecretKeyStorage.loadProfileName(this);
        if (!TextUtils.isEmpty(name))
        {
            // The name is the username half of this form, so a password manager saves the two as a pair. The
            // page shows it in a line that says whose password this is, never as a bare name.
            final AirStepKit.UsernameText username = new AirStepKit.UsernameText(this, R.style.TextAppearance_KQ_Label,
                                                                                 R.color.kq_ink_2);
            username.setUsername(name, getString(R.string.air_first_password_for_profile, name));
            column.addView(username, spaced(R.dimen.kq_space_16));
        }

        if (manualMode)
        {
            // A profile with a password: the old one first, its refusal under it.
            final AirStepKit.LineField field = new AirStepKit.LineField(this, getString(R.string.air_password_old_placeholder),
                                                                        true);
            field.edit.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
            prepare(field, R.string.air_password_old_placeholder);
            column.addView(field, spaced(R.dimen.kq_space_24));
            oldNote = AirStepKit.fieldNote(this, null);
            column.addView(oldNote, spaced(R.dimen.kq_space_6));
            oldField = field;
        }
        newField = new AirStepKit.LineField(this, getString(R.string.cp_new_password), true);
        repeatField = new AirStepKit.LineField(this, getString(R.string.air_first_repeat_placeholder), true);
        newField.edit.setAutofillHints(AUTOFILL_HINT_NEW_PASSWORD);
        repeatField.edit.setAutofillHints(AUTOFILL_HINT_NEW_PASSWORD);
        prepare(newField, R.string.cp_new_password);
        prepare(repeatField, R.string.air_first_repeat_placeholder);
        column.addView(newField, spaced(manualMode ? R.dimen.kq_space_12 : R.dimen.kq_space_24));
        column.addView(repeatField, spaced(R.dimen.kq_space_8));
        // The rule under the new pair (SetPasswordActivity.isPasswordValid), and its refusals in its place.
        newNote = AirStepKit.fieldNote(this, getString(R.string.air_first_password_rule));
        column.addView(newNote, spaced(R.dimen.kq_space_6));

        if (oldField != null)
        {
            AirStepKit.chain(oldField.edit, newField.edit, this::mainTapped);
        }
        AirStepKit.chain(newField.edit, repeatField.edit, this::mainTapped);
        AirStepKit.chain(repeatField.edit, null, this::mainTapped);

        bar = new AirStepKit.MainBar(this, verb(), this::mainTapped);
        updateWaiting();
        AirStepKit.showKeyboard(oldField != null ? oldField.edit : newField.edit);
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        if (!staged && !isChangingConfigurations() && isFinishing())
        {
            // Left without changing the password: what was typed is not offered for saving.
            forgetPasswords();
        }
    }

    @Override
    protected void onDestroy()
    {
        if (question != null)
        {
            question.setOnDismissListener(null);
            question.dismiss();
            question = null;
        }
        super.onDestroy();
    }

    // ---- the fields ----

    private void prepare(final AirStepKit.LineField field, @StringRes final int description)
    {
        field.edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        // The password never goes into the saved state of the page.
        field.edit.setSaveEnabled(false);
        field.edit.setContentDescription(getString(description));
        field.edit.addTextChangedListener(new TextWatcher()
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
                changed(field);
            }
        });
    }

    /** Typing takes the refusal of that field away again and gives the rule back. */
    private void changed(final AirStepKit.LineField field)
    {
        if (field == oldField && oldNote != null)
        {
            AirStepKit.showHint(oldNote, null);
        }
        else if (newNote != null && (field == newField || field == repeatField))
        {
            AirStepKit.showHint(newNote, getString(R.string.air_first_password_rule));
        }
        updateWaiting();
    }

    private void updateWaiting()
    {
        if (bar == null || newField == null || repeatField == null || bar.isBusy())
        {
            return;
        }
        bar.setWaiting(waitingReason());
    }

    /** Why the action waits: a field still empty, in the order of the page. */
    @Nullable
    private String waitingReason()
    {
        if (oldField != null && oldField.text().isEmpty())
        {
            return getString(R.string.air_password_old_first);
        }
        if (newField.text().isEmpty())
        {
            return getString(R.string.air_first_password_empty);
        }
        if (repeatField.text().isEmpty())
        {
            return getString(R.string.air_first_password_repeat_first);
        }
        return null;
    }

    private AirStepKit.LineField firstEmpty()
    {
        if (oldField != null && oldField.text().isEmpty())
        {
            return oldField;
        }
        return newField.text().isEmpty() ? newField : repeatField;
    }

    /** The words of the rule the new password breaks, with the checks of first launch in their order; 0 when none. */
    private int ruleRefusal()
    {
        final String first = newField.text();
        if (first.length() < TRIFAGlobals.LEN_TRIFA_MANUAL_PASSWORD_MIN_LEN)
        {
            return R.string.air_first_password_short;
        }
        if (!SetPasswordActivity.isPasswordValid(first))
        {
            return R.string.air_first_password_weak;
        }
        if (!TextUtils.equals(first, repeatField.text()))
        {
            return R.string.air_first_password_mismatch;
        }
        return 0;
    }

    private void forgetPasswords()
    {
        for (final AirStepKit.LineField field : new AirStepKit.LineField[]{oldField, newField, repeatField})
        {
            if (field != null)
            {
                field.clear();
            }
        }
        final AutofillManager autofill = getSystemService(AutofillManager.class);
        if (autofill != null)
        {
            autofill.cancel();
        }
    }

    // ---- the action ----

    private String verb()
    {
        return getString(manualMode ? R.string.pd_change_password : R.string.air_password_set_action);
    }

    @StringRes
    private int failedWords()
    {
        return manualMode ? R.string.air_password_change_failed : R.string.air_password_not_set;
    }

    private void setBusy(final boolean on)
    {
        bar.setBusy(on ? getString(manualMode ? R.string.air_password_checking : R.string.air_password_preparing) : null);
        for (final AirStepKit.LineField field : new AirStepKit.LineField[]{oldField, newField, repeatField})
        {
            if (field != null)
            {
                field.edit.setEnabled(!on);
            }
        }
        if (!on)
        {
            updateWaiting();
        }
    }

    private void mainTapped()
    {
        if (working || bar.isBusy())
        {
            return;
        }
        if (waitingReason() != null)
        {
            // Enter on a hardware keyboard while a field is empty: the bar says why, the focus goes there.
            updateWaiting();
            AirStepKit.showKeyboard(firstEmpty().edit);
            return;
        }
        final int rule = ruleRefusal();
        if (rule != 0)
        {
            AirUi.sayOnLine(newNote, getString(rule), true);
            final AirStepKit.LineField target = rule == R.string.air_first_password_mismatch ? repeatField : newField;
            AirStepKit.showKeyboard(target.edit);
            target.edit.selectAll();
            return;
        }

        final String oldPw = oldField != null ? oldField.text() : "";
        final String newPw1 = newField.text();
        working = true;
        setBusy(true);

        // PBKDF2 + probes off the UI thread
        new Thread(() -> {
            int result = CHECK_OK;
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
                        result = CHECK_WRONG_OLD;
                    }
                }
                else
                {
                    // skip-mode: the live resolved key IS the current passphrase. The static can be
                    // a dummy after a process re-creation: fall back to resolving it from storage.
                    oldKey = MainActivity.PREF__DB_secrect_key;
                    if (oldKey == null || oldKey.isEmpty()
                        || (haveDb && !OrmaDatabase.probeEncryptedDatabase(dbPath, oldKey)))
                    {
                        oldKey = DbSecretKeyStorage.resolveDbSecretKey(this);
                    }
                    if (oldKey == null || oldKey.isEmpty()
                        || (haveDb && !OrmaDatabase.probeEncryptedDatabase(dbPath, oldKey)))
                    {
                        result = CHECK_KEY_FAILED;
                    }
                }

                // ---- derive the NEW key with a candidate salt (no side effects) ------------------
                if (result == CHECK_OK)
                {
                    // ALWAYS a fresh salt: a stolen old salt must not stay useful for offline
                    // dictionaries against the new password. Committed only when the rekey succeeds.
                    saltB64 = DbSecretKeyStorage.generateSaltB64();
                    newKey = DbSecretKeyStorage.deriveManualPasswordHashWithSalt(newPw1, saltB64);
                    if (newKey.equals(oldKey))
                    {
                        result = CHECK_SAME;
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "check:EE:" + e.getMessage());
                result = CHECK_FAILED;
            }

            final int fResult = result;
            final String fOld = oldKey;
            final String fNew = newKey;
            final String fSalt = saltB64;
            runOnUiThread(() -> {
                working = false;
                if (isFinishing() || isDestroyed())
                {
                    return;
                }
                setBusy(false);
                checked(fResult, fOld, fNew, fSalt);
            });
        }).start();
    }

    /** What the check found, said where it belongs; a pass asks from below before anything is staged. */
    private void checked(final int result, final String oldKey, final String newKey, final String saltB64)
    {
        switch (result)
        {
            case CHECK_OK:
                confirmAndStage(oldKey, newKey, saltB64);
                return;
            case CHECK_WRONG_OLD:
                if (oldField != null && oldNote != null)
                {
                    AirUi.sayOnLine(oldNote, getString(R.string.air_password_wrong_old), true);
                    AirStepKit.shakeOnce(oldField);
                    AirStepKit.showKeyboard(oldField.edit);
                    oldField.edit.selectAll();
                }
                return;
            case CHECK_SAME:
                bar.sayFailure(getString(R.string.cp_err_same));
                return;
            case CHECK_KEY_FAILED:
                // The key kept on this phone did not open the profile: nothing the person typed was wrong.
                bar.sayFailure(getString(R.string.air_password_set_failed));
                return;
            default:
                bar.sayFailure(getString(failedWords()));
        }
    }

    private void confirmAndStage(final String oldKey, final String newKey, final String saltB64)
    {
        AirStepKit.hideKeyboard(this, column);
        question = AirQuestion.ask(this, getString(manualMode ? R.string.air_password_change_question
                                                              : R.string.air_password_set_question),
                                   getString(R.string.air_password_restart_consequence), verb(), false,
                                   () -> stage(oldKey, newKey, saltB64));
    }

    private void stage(final String oldKey, final String newKey, final String saltB64)
    {
        question = null;
        if (isFinishing() || isDestroyed())
        {
            return;
        }
        if (!DbSecretKeyStorage.stagePendingRekey(this, oldKey, newKey, saltB64))
        {
            bar.sayFailure(getString(failedWords()));
            return;
        }
        // KHANDAQ (rekey): savedata.tox is toxencryptsave-encrypted with sha256(OLD key); the
        // SQLCipher rekey alone left it behind, so the restarted app failed with "the file is damaged
        // or protected by a password", parked the profile as .broken and created a NEW Tox identity
        // (tester report). Snapshot the live savedata now so the rekey process can move it onto the
        // new key.
        if (!DbRekeyHelper.stageSavedataSnapshot(this))
        {
            DbSecretKeyStorage.clearPendingRekey(this);
            bar.sayFailure(getString(failedWords()));
            return;
        }
        staged = true;
        // The new password is taken: a password manager may save it with the name.
        final AutofillManager autofill = getSystemService(AutofillManager.class);
        if (autofill != null)
        {
            autofill.commit();
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
        // The app does not start again by itself: the rekey runs when it is opened next.
        Toast.makeText(this, R.string.air_password_reopen, Toast.LENGTH_SHORT).show();
        try
        {
            finishAffinity();
        }
        catch (Exception ignored)
        {
        }
        System.exit(0);
    }

    private LinearLayout.LayoutParams spaced(@DimenRes final int top)
    {
        final LinearLayout.LayoutParams params = AirScreenKit.matchWidth();
        params.topMargin = getResources().getDimensionPixelSize(top);
        return params;
    }
}

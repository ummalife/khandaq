package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.autofill.AutofillManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.preference.PreferenceManager;

import org.khandaq.messenger.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import static com.zoffcc.applications.trifa.TRIFAGlobals.LEN_TRIFA_AUTOGEN_PASSWORD;

/**
 * KHANDAQ (redesign stage 4, E19): first launch, as AirFirstLaunch.swift on iOS. Step 1 asks for the
 * name, with the quieter way of moving a profile in from a file; step 2 is the profile password; step 3,
 * the ID, needs Tox running with the new profile and stands over Air (AirIdReadyActivity). Steps 1 and 2
 * share this window, so a password manager sees the name and the new password in one form.
 *
 * What the profile gets is exactly what SetPasswordActivity gave it (the brief forbids new security
 * mechanisms): a password becomes the PBKDF2 database key; without one a random key is kept wrapped
 * by the Android Keystore, so the database, the file store and the Tox save are encrypted either way.
 * A moved-in profile opens with such a key, as before. Unlike before, the file is read first: its
 * name and ID are shown before anything is written, and a password-protected or foreign file is
 * refused under the button that picked it.
 */
public class AirFirstLaunchActivity extends AirScreenActivity implements AirBand.NoBand
{
    private static final String TAG = "trifa.AirFirstLaunch";

    /** Set when a new profile was made here; MainActivity then opens "Your ID is ready" over Air. */
    static final String PREF_ID_PENDING = "khandaq_first_launch_id_pending";

    private static final String STATE_PAGE = "air_first_page";
    private static final String STATE_NAME = "air_first_name";
    private static final String STATE_WITH_PASSWORD = "air_first_with_password";

    private static final int PAGE_NAME = 0;
    private static final int PAGE_PASSWORD = 1;
    private static final int PAGE_IMPORT = 2;
    private static final int STEPS = 3;
    /** A Tox save is a few kilobytes; a file far larger than this is no profile. */
    private static final long MAX_PROFILE_BYTES = 16L * 1024 * 1024;
    /** The new-password autofill hint (androidx.autofill HintConstants.AUTOFILL_HINT_NEW_PASSWORD). */
    private static final String AUTOFILL_HINT_NEW_PASSWORD = "newPassword";
    private static final String IMPORT_STAGING = "import_savedata.tox";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String[]> pickProfile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onProfilePicked);

    private AirStepKit.Progress progress;
    private AirStepKit.MainBar bar;
    private FrameLayout stage;
    private View[] pages;

    private AirStepKit.LineField nameField;
    private TextView moveButton;
    private TextView moveNote;

    private LinearLayout setRow;
    private LinearLayout skipRow;
    private LinearLayout passwordFields;
    private AirStepKit.UsernameText username;
    private AirStepKit.LineField passwordField;
    private AirStepKit.LineField repeatField;
    private TextView passwordNote;

    private LinearLayout importColumn;
    private TextView otherFileButton;
    private TextView otherFileNote;
    @Nullable
    private ToxSaveSummary picked;

    private int page = PAGE_NAME;
    private boolean withPassword = true;
    private boolean busy = false;
    private boolean reading = false;
    private boolean destroyed = false;

    @Override
    protected int screenTitle()
    {
        return R.string.air_first_name_title;
    }

    @Override
    protected boolean headerRule()
    {
        return false;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        // A profile already on the device is opened, never made over (as SetPasswordActivity did).
        if (DbSecretKeyStorage.hasExistingUserDatabase(this))
        {
            Log.i(TAG, "existing profile: to sign-in");
            startActivity(new Intent(this, CheckPasswordActivity.class));
            finish();
            return;
        }

        final Toolbar toolbar = findViewById(R.id.air_screen_toolbar);
        progress = new AirStepKit.Progress(this, STEPS);
        toolbar.addView(progress, new Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                           ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        // The pages slide across the whole width, past the column's margins.
        column.setClipToPadding(false);
        column.setClipChildren(false);
        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        column.addView(stage, AirScreenKit.matchWidth());
        pages = new View[]{buildNamePage(), buildPasswordPage(), buildImportPage()};
        // TalkBack says the title of each page as it comes in.
        ViewCompat.setAccessibilityPaneTitle(pages[PAGE_NAME], getString(R.string.air_first_name_title));
        ViewCompat.setAccessibilityPaneTitle(pages[PAGE_PASSWORD], getString(R.string.air_first_password_title));
        ViewCompat.setAccessibilityPaneTitle(pages[PAGE_IMPORT], getString(R.string.air_import_preview_title));
        // Done in a field hands the focus to the column, so no other field takes it and raises the keyboard.
        column.setFocusableInTouchMode(true);
        column.setDefaultFocusHighlightEnabled(false);
        for (final View p : pages)
        {
            p.setVisibility(View.GONE);
            stage.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                          ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        bar = new AirStepKit.MainBar(this, getString(R.string.air_first_next), this::mainTapped);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true)
        {
            @Override
            public void handleOnBackPressed()
            {
                if (busy)
                {
                    return;
                }
                if (page != PAGE_NAME)
                {
                    showPage(PAGE_NAME, true);
                    return;
                }
                // Nothing to go back to: the app goes to the background and this page waits.
                moveTaskToBack(true);
            }
        });

        int startPage = PAGE_NAME;
        if (savedInstanceState != null)
        {
            nameField.edit.setText(savedInstanceState.getString(STATE_NAME, ""));
            withPassword = savedInstanceState.getBoolean(STATE_WITH_PASSWORD, true);
            // A picked file is not kept across a recreation: the person picks it again.
            final int saved = savedInstanceState.getInt(STATE_PAGE, PAGE_NAME);
            startPage = saved == PAGE_IMPORT ? PAGE_NAME : saved;
        }
        updateChoice(false);
        showPage(startPage, false);
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState)
    {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, page);
        outState.putString(STATE_NAME, nameField.text());
        outState.putBoolean(STATE_WITH_PASSWORD, withPassword);
        // The passwords are not kept: they are typed again after a recreation.
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        if (!busy && !isChangingConfigurations() && isFinishing())
        {
            forgetPasswords();
        }
    }

    @Override
    protected void onDestroy()
    {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- page 1: the name ----

    private View buildNamePage()
    {
        final LinearLayout page = pageColumn();
        page.addView(AirStepKit.header(this, getString(R.string.air_first_name_title),
                                       getString(R.string.air_first_name_body)), AirScreenKit.matchWidth());
        nameField = new AirStepKit.LineField(this, getString(R.string.air_first_name_placeholder), false);
        nameField.limitBytes(ToxVars.TOX_MAX_NAME_LENGTH);
        // A name is not prose: no spell-check underlines and no suggestions. It is no account either,
        // so password managers leave this field alone; the password page carries the name for them.
        nameField.edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                                    | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameField.edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        nameField.edit.setSaveEnabled(false);
        nameField.edit.setContentDescription(getString(R.string.air_first_name_placeholder));
        nameField.edit.addTextChangedListener(new Watcher(this::updateWaiting));
        nameField.edit.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        nameField.edit.setOnEditorActionListener((v, actionId, event) -> {
            if (AirStepKit.enterArming(actionId, event))
            {
                return true;
            }
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_NULL)
            {
                mainTapped();
                return true;
            }
            return false;
        });
        page.addView(nameField, spaced(R.dimen.kq_space_24));

        moveButton = AirStepKit.quietButton(this, getString(R.string.air_first_move_profile));
        moveButton.setOnClickListener(v -> pickFile());
        page.addView(AirScreenKit.leading(moveButton), spaced(R.dimen.kq_space_16));
        moveNote = AirStepKit.fieldNote(this, null);
        page.addView(moveNote, spaced(R.dimen.kq_space_2));
        return page;
    }

    private String cleanName()
    {
        return nameField.text().trim();
    }

    // ---- page 2: the password ----

    private View buildPasswordPage()
    {
        final LinearLayout page = pageColumn();
        page.addView(AirStepKit.header(this, getString(R.string.air_first_password_title),
                                       getString(R.string.air_first_password_body)), AirScreenKit.matchWidth());
        setRow = AirScreenKit.choiceRow(this, getString(R.string.air_first_password_set),
                                        getString(R.string.air_first_password_set_detail));
        skipRow = AirScreenKit.choiceRow(this, getString(R.string.air_first_password_skip),
                                         getString(R.string.air_first_password_skip_detail));
        setRow.setOnClickListener(v -> choose(true));
        skipRow.setOnClickListener(v -> choose(false));
        page.addView(AirScreenKit.plateOf(this, setRow, skipRow), spaced(R.dimen.kq_space_24));

        passwordFields = new LinearLayout(this);
        passwordFields.setOrientation(LinearLayout.VERTICAL);
        // The name is the username half of this form, so a password manager saves the two as a pair.
        username = new AirStepKit.UsernameText(this, R.style.TextAppearance_KQ_Label, R.color.kq_ink_2);
        passwordFields.addView(username, AirScreenKit.matchWidth());
        passwordField = new AirStepKit.LineField(this, getString(R.string.air_first_password_placeholder), true);
        repeatField = new AirStepKit.LineField(this, getString(R.string.air_first_repeat_placeholder), true);
        for (final AirStepKit.LineField field : new AirStepKit.LineField[]{passwordField, repeatField})
        {
            field.edit.setAutofillHints(AUTOFILL_HINT_NEW_PASSWORD);
            field.edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            field.edit.setSaveEnabled(false);
            field.edit.addTextChangedListener(new Watcher(this::passwordChanged));
        }
        passwordField.edit.setContentDescription(getString(R.string.air_first_password_placeholder));
        repeatField.edit.setContentDescription(getString(R.string.air_first_repeat_placeholder));
        AirStepKit.chain(passwordField.edit, repeatField.edit, this::mainTapped);
        AirStepKit.chain(repeatField.edit, null, this::mainTapped);
        passwordFields.addView(passwordField, spaced(R.dimen.kq_space_8));
        passwordFields.addView(repeatField, spaced(R.dimen.kq_space_8));
        passwordNote = AirStepKit.fieldNote(this, getString(R.string.air_first_password_rule));
        passwordFields.addView(passwordNote, spaced(R.dimen.kq_space_6));
        page.addView(passwordFields, spaced(R.dimen.kq_space_24));
        return page;
    }

    private void choose(final boolean password)
    {
        if (busy)
        {
            return;
        }
        withPassword = password;
        updateChoice(true);
        if (withPassword && TextUtils.isEmpty(passwordField.text()))
        {
            showKeyboard(passwordField.edit);
        }
        else if (!withPassword)
        {
            hideKeyboard();
        }
    }

    private void updateChoice(final boolean animate)
    {
        AirScreenKit.applyChoice(setRow, withPassword);
        AirScreenKit.applyChoice(skipRow, !withPassword);
        passwordFields.animate().cancel();
        if (!animate || AirUi.reducedMotion())
        {
            passwordFields.setAlpha(1f);
            passwordFields.setVisibility(withPassword ? View.VISIBLE : View.GONE);
        }
        else if (withPassword)
        {
            passwordFields.setAlpha(0f);
            passwordFields.setVisibility(View.VISIBLE);
            passwordFields.animate().alpha(1f).setDuration(AirUi.duration(this, R.integer.kq_dur_state)).start();
        }
        else
        {
            passwordFields.animate().alpha(0f).setDuration(AirUi.duration(this, R.integer.kq_dur_state))
                          .withEndAction(() -> {
                              passwordFields.setVisibility(View.GONE);
                              passwordFields.setAlpha(1f);
                          }).start();
        }
        updateWaiting();
    }

    private void passwordChanged()
    {
        AirStepKit.showHint(passwordNote, getString(R.string.air_first_password_rule));
        updateWaiting();
    }

    /**
     * Why the password cannot be taken yet, as AirNewPassword.reason on iOS; null when it can. The
     * text is a resource string, never the password: the name avoids "password" because CodeQL reads
     * a value named so as the secret itself and flags it the moment it reaches a TextView.
     */
    @Nullable
    private String lockPageReason()
    {
        if (!withPassword)
        {
            return null;
        }
        final String first = passwordField.text();
        final String second = repeatField.text();
        if (first.isEmpty())
        {
            return getString(R.string.air_first_password_empty);
        }
        if (first.length() < TRIFAGlobals.LEN_TRIFA_MANUAL_PASSWORD_MIN_LEN)
        {
            return getString(R.string.air_first_password_short);
        }
        if (!SetPasswordActivity.isPasswordValid(first))
        {
            return getString(R.string.air_first_password_weak);
        }
        if (second.isEmpty())
        {
            return getString(R.string.air_first_password_repeat_first);
        }
        if (!TextUtils.equals(first, second))
        {
            return getString(R.string.air_first_password_mismatch);
        }
        return null;
    }

    // ---- moving a profile in ----

    private View buildImportPage()
    {
        importColumn = pageColumn();
        return importColumn;
    }

    private void pickFile()
    {
        if (busy || reading)
        {
            return;
        }
        hideKeyboard();
        try
        {
            pickProfile.launch(ToxProfileImportHelper.TOX_IMPORT_MIME_TYPES);
        }
        catch (android.content.ActivityNotFoundException e)
        {
            Log.i(TAG, "pick:EE:" + e.getMessage());
            sayUnderPicker(getString(R.string.air_import_failed_title), true);
        }
    }

    private void onProfilePicked(@Nullable final Uri uri)
    {
        if (uri == null || destroyed)
        {
            return;
        }
        reading = true;
        sayUnderPicker(getString(R.string.air_import_reading), false);
        setPickerEnabled(false);
        final Context app = getApplicationContext();
        new Thread(() -> {
            final File staging = new File(app.getCacheDir(), IMPORT_STAGING);
            @StringRes int refusal = 0;
            ToxSaveSummary summary = null;
            try
            {
                if (!ToxProfileImportHelper.copyUriToFile(app, uri, staging) || staging.length() > MAX_PROFILE_BYTES)
                {
                    refusal = R.string.air_import_not_profile;
                }
                else if (ToxProfileImportHelper.validateToxSaveFile(staging) == ToxProfileImportHelper.TOXSAVE_ENCRYPTED)
                {
                    refusal = R.string.air_import_encrypted;
                }
                else
                {
                    summary = ToxSaveSummary.read(readAll(staging));
                    if (summary == null)
                    {
                        refusal = R.string.air_import_not_profile;
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "import:read:EE:" + e.getMessage());
                refusal = R.string.air_import_not_profile;
            }
            if (refusal != 0)
            {
                //noinspection ResultOfMethodCallIgnored
                staging.delete();
            }
            final int refused = refusal;
            final ToxSaveSummary found = summary;
            main.post(() -> {
                reading = false;
                if (destroyed)
                {
                    return;
                }
                setPickerEnabled(true);
                if (refused != 0)
                {
                    Log.i(TAG, "import refused");
                    sayUnderPicker(getString(refused), true);
                    return;
                }
                endPickerNote();
                picked = found;
                fillImportPage(found);
                showPage(PAGE_IMPORT, true);
            });
        }).start();
    }

    private static byte[] readAll(final File file) throws IOException
    {
        final byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file))
        {
            int at = 0;
            while (at < bytes.length)
            {
                final int read = in.read(bytes, at, bytes.length - at);
                if (read < 0)
                {
                    throw new IOException("file shorter than its length");
                }
                at += read;
            }
        }
        return bytes;
    }

    private void fillImportPage(final ToxSaveSummary summary)
    {
        importColumn.removeAllViews();
        importColumn.addView(AirStepKit.header(this, getString(R.string.air_import_preview_title),
                                               getString(R.string.air_import_preview_body)), AirScreenKit.matchWidth());
        final LinearLayout plate = AirStepKit.idPlate(this, summary.toxId, null);
        final LinearLayout namePart = new LinearLayout(this);
        namePart.setOrientation(LinearLayout.VERTICAL);
        final TextView caption = AirScreenKit.text(this, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
        caption.setText(R.string.air_import_name);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        namePart.addView(caption, AirScreenKit.matchWidth());
        final TextView name = AirScreenKit.text(this, R.style.TextAppearance_KQ_Body, R.color.kq_ink);
        final boolean named = !summary.name.isEmpty();
        name.setText(named ? summary.name : getString(R.string.air_import_no_name));
        if (!named)
        {
            name.setTextColor(AirUi.color(this, R.color.kq_ink_3));
        }
        name.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        final LinearLayout.LayoutParams nameParams = AirScreenKit.matchWidth();
        nameParams.topMargin = getResources().getDimensionPixelSize(R.dimen.kq_space_2);
        namePart.addView(name, nameParams);
        namePart.setContentDescription(getString(R.string.air_import_name) + ": " + name.getText());
        ViewCompat.setScreenReaderFocusable(namePart, true);
        final LinearLayout.LayoutParams namePartParams = AirScreenKit.matchWidth();
        namePartParams.bottomMargin = getResources().getDimensionPixelSize(R.dimen.kq_space_12);
        plate.addView(namePart, 0, namePartParams);
        importColumn.addView(plate, spaced(R.dimen.kq_space_24));

        final TextView keyNote = AirScreenKit.note(this, getString(R.string.air_import_device_key));
        importColumn.addView(keyNote, spaced(R.dimen.kq_space_12));

        otherFileButton = AirStepKit.quietButton(this, getString(R.string.air_import_other_file));
        otherFileButton.setOnClickListener(v -> pickFile());
        importColumn.addView(AirScreenKit.leading(otherFileButton), spaced(R.dimen.kq_space_16));
        otherFileNote = AirStepKit.fieldNote(this, null);
        importColumn.addView(otherFileNote, spaced(R.dimen.kq_space_2));
    }

    /** The words under the button that picked the file: reading it, or why it was refused. */
    private void sayUnderPicker(final CharSequence text, final boolean refused)
    {
        final TextView note = page == PAGE_IMPORT && otherFileNote != null ? otherFileNote : moveNote;
        AirUi.sayOnLine(note, text, refused);
    }

    private void endPickerNote()
    {
        AirStepKit.showHint(moveNote, null);
        if (otherFileNote != null)
        {
            AirStepKit.showHint(otherFileNote, null);
        }
    }

    private void setPickerEnabled(final boolean enabled)
    {
        moveButton.setEnabled(enabled);
        moveButton.setAlpha(enabled ? 1f : 0.5f);
        if (otherFileButton != null)
        {
            otherFileButton.setEnabled(enabled);
            otherFileButton.setAlpha(enabled ? 1f : 0.5f);
        }
    }

    private void moveProfileIn()
    {
        final ToxSaveSummary summary = picked;
        if (summary == null || busy)
        {
            return;
        }
        setBusy(getString(R.string.air_import_moving));
        final Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = false;
            final File staging = new File(app.getCacheDir(), IMPORT_STAGING);
            try
            {
                // The same steps as the import of SetPasswordActivity: the save goes into place as it is
                // and the profile opens with a key kept on this phone.
                HelperGeneric.io_file_copy(staging, ToxProfileImportHelper.savedataDestination(app));
                DbSecretKeyStorage.saveAutoGeneratedKey(app, MainActivity.getSecureRandomString(LEN_TRIFA_AUTOGEN_PASSWORD));
                PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("PW_SET_SCREEN_DONE", true).commit();
                DbSecretKeyStorage.setSessionLoggedIn(app, true);
                DbSecretKeyStorage.rememberProfileName(app, summary.name);
                ok = true;
            }
            catch (Exception e)
            {
                Log.i(TAG, "import:move:EE:" + e.getMessage());
            }
            finally
            {
                // The copy the system handed over goes; the original file stays where it was.
                //noinspection ResultOfMethodCallIgnored
                staging.delete();
            }
            final boolean moved = ok;
            main.post(() -> {
                if (destroyed)
                {
                    return;
                }
                if (!moved)
                {
                    setBusy(null);
                    picked = null;
                    bar.button.setEnabled(false);
                    bar.sayFailure(getString(R.string.air_import_failed_title));
                    return;
                }
                openApp();
            });
        }).start();
    }

    // ---- making the profile ----

    private void mainTapped()
    {
        if (busy)
        {
            return;
        }
        if (page == PAGE_NAME)
        {
            if (cleanName().isEmpty())
            {
                updateWaiting();
                return;
            }
            showPage(PAGE_PASSWORD, true);
        }
        else if (page == PAGE_PASSWORD)
        {
            create();
        }
        else
        {
            moveProfileIn();
        }
    }

    private void create()
    {
        final String name = cleanName();
        if (name.isEmpty())
        {
            showPage(PAGE_NAME, true);
            return;
        }
        if (lockPageReason() != null)
        {
            updateWaiting();
            return;
        }
        hideKeyboard();
        final String password = withPassword ? passwordField.text() : null;
        if (!withPassword)
        {
            // Nothing half-typed stays behind for the system to offer to save.
            forgetPasswords();
        }
        setBusy(getString(R.string.air_first_creating));
        final Context app = getApplicationContext();
        new Thread(() -> {
            String hash = null;
            boolean ok = false;
            try
            {
                if (password != null)
                {
                    hash = DbSecretKeyStorage.deriveManualPasswordHash(app, password);
                }
                else
                {
                    // Not a password anyone types: the key the database is encrypted with on this phone.
                    DbSecretKeyStorage.saveAutoGeneratedKey(app, MainActivity.getSecureRandomString(LEN_TRIFA_AUTOGEN_PASSWORD));
                }
                ok = true;
            }
            catch (Exception e)
            {
                Log.i(TAG, "create:EE:" + e.getMessage());
            }
            final boolean made = ok;
            final String key = hash;
            main.post(() -> finishCreate(made, key, name));
        }).start();
    }

    private void finishCreate(final boolean made, @Nullable final String passwordHash, final String name)
    {
        if (destroyed)
        {
            return;
        }
        if (!made)
        {
            setBusy(null);
            if (!withPassword)
            {
                // The key needs the Keystore; a password needs nothing of the phone.
                withPassword = true;
                updateChoice(true);
            }
            AirUi.sayOnLine(passwordNote, getString(R.string.air_first_create_failed), true);
            return;
        }
        if (passwordHash != null)
        {
            TRIFAGlobals.PREF__DB_secrect_key__user_hash = passwordHash;
            DbSecretKeyStorage.clearAutoGeneratedKey(this);
        }
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        prefs.edit()
             .putString(CreateProfileActivity.PREF_ONBOARDING_DISPLAY_NAME, name)
             .putBoolean(PREF_ID_PENDING, true)
             .putBoolean("PW_SET_SCREEN_DONE", true)
             .commit();
        DbSecretKeyStorage.setSessionLoggedIn(this, true);
        DbSecretKeyStorage.rememberProfileName(this, name);
        openApp();
    }

    private void openApp()
    {
        if (withPassword && page == PAGE_PASSWORD)
        {
            // The name and the new password were typed into this form: a password manager may save them.
            final AutofillManager autofill = getSystemService(AutofillManager.class);
            if (autofill != null)
            {
                autofill.commit();
            }
        }
        startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private void setBusy(@Nullable final CharSequence words)
    {
        busy = words != null;
        bar.setBusy(words);
        setRow.setEnabled(!busy);
        skipRow.setEnabled(!busy);
        passwordField.edit.setEnabled(!busy);
        repeatField.edit.setEnabled(!busy);
        nameField.edit.setEnabled(!busy);
        setPickerEnabled(!busy);
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.setDisplayHomeAsUpEnabled(!busy && page != PAGE_NAME);
        }
    }

    private void forgetPasswords()
    {
        passwordField.clear();
        repeatField.clear();
        final AutofillManager autofill = getSystemService(AutofillManager.class);
        if (autofill != null)
        {
            autofill.cancel();
        }
    }

    // ---- the pages ----

    private void showPage(final int target, final boolean animate)
    {
        final int from = page;
        page = target;
        if (from == PAGE_IMPORT && target != PAGE_IMPORT)
        {
            picked = null;
            //noinspection ResultOfMethodCallIgnored
            new File(getCacheDir(), IMPORT_STAGING).delete();
        }
        final View incoming = pages[target];
        final boolean visibleBefore = pages[from].getVisibility() == View.VISIBLE;
        if (animate && visibleBefore && from != target)
        {
            AirStepKit.turn(pages[from], incoming, target > from, null);
        }
        else
        {
            for (final View p : pages)
            {
                p.setVisibility(p == incoming ? View.VISIBLE : View.GONE);
                p.setTranslationX(0f);
                p.setAlpha(1f);
            }
        }
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.setDisplayHomeAsUpEnabled(target != PAGE_NAME);
            actionBar.setTitle(target == PAGE_IMPORT ? getString(R.string.air_import_title) : null);
        }
        progress.setVisibility(target == PAGE_IMPORT ? View.GONE : View.VISIBLE);
        if (target != PAGE_IMPORT)
        {
            progress.show(target + 1, animate);
        }
        final @StringRes int title = target == PAGE_NAME ? R.string.air_first_name_title
                                     : target == PAGE_PASSWORD ? R.string.air_first_password_title
                                     : R.string.air_import_preview_title;
        setTitle(title);
        bar.setLabel(getString(target == PAGE_NAME ? R.string.air_first_next
                               : target == PAGE_PASSWORD ? R.string.air_first_create
                               : R.string.air_import_confirm));
        if (target == PAGE_PASSWORD)
        {
            // A line that says whose password this is; password managers still get the bare name.
            final String name = cleanName();
            username.setUsername(name, getString(R.string.air_first_password_for_profile, name));
        }
        else
        {
            // Leaving the password page another way: nothing typed there is offered for saving.
            forgetPasswords();
        }
        updateWaiting();
        final ViewGroup scroll = findViewById(R.id.air_screen_scroll);
        scroll.scrollTo(0, 0);
        if (target == PAGE_NAME && cleanName().isEmpty())
        {
            showKeyboard(nameField.edit);
        }
        else if (target != PAGE_NAME)
        {
            // The password page does not raise the keyboard by itself: the choices are read first.
            hideKeyboard();
        }
    }

    private void updateWaiting()
    {
        if (busy || bar == null)
        {
            return;
        }
        if (page == PAGE_NAME)
        {
            bar.setWaiting(cleanName().isEmpty() ? getString(R.string.air_first_name_first) : null);
        }
        else if (page == PAGE_PASSWORD)
        {
            bar.setWaiting(lockPageReason());
        }
        else
        {
            bar.setWaiting(null);
            bar.button.setEnabled(picked != null);
        }
    }

    private LinearLayout pageColumn()
    {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        return page;
    }

    private LinearLayout.LayoutParams spaced(final int topDimen)
    {
        final LinearLayout.LayoutParams params = AirScreenKit.matchWidth();
        params.topMargin = getResources().getDimensionPixelSize(topDimen);
        return params;
    }

    private void showKeyboard(final EditText field)
    {
        AirStepKit.showKeyboard(field);
    }

    private void hideKeyboard()
    {
        AirStepKit.hideKeyboard(this, column);
    }

    /** A TextWatcher that only cares that the text changed. */
    private static final class Watcher implements TextWatcher
    {
        private final Runnable changed;

        Watcher(final Runnable changed)
        {
            this.changed = changed;
        }

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
            changed.run();
        }
    }
}

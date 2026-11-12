package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.preference.PreferenceManager;

import org.khandaq.messenger.R;

/**
 * KHANDAQ Profile, Password and PIN: the app lock (PIN) switch, the lock timeout and the profile password.
 * The lock and the password reuse the app's existing, tested screens (PinActivity, ChangePasswordActivity).
 * Deleting the profile moved to the bottom of the You screen (redesign, E17, as on iOS); {@link #wipeProfile}
 * stays here: it uses a wipe-on-next-launch flag so the encrypted DB is removed by
 * {@link StartMainActivityWrapper} in a fresh process (no open file handles), then routes to onboarding.
 *
 * KHANDAQ (redesign stage 4, E20): drawn as the screens of Settings, as ProfileDetailsController on iOS.
 * The PIN section holds App lock and, while it is on, when it locks; the Profile password section holds
 * Change password, which reads Set a password for a profile that opens with the key kept on this phone.
 * Each section says under it what cannot be recovered.
 */
public class ProfileDetailsActivity extends AirScreenActivity
{
    static final String PREF__pending_profile_wipe = "PREF__pending_profile_wipe";

    private static final int REQ_SET_PIN = 4801;

    // lock timeout choices, parallel arrays (seconds + label resource)
    private static final int[] TIMEOUT_SECONDS = {0, 60, 300, 1800};

    private SharedPreferences prefs;
    private SwitchCompat lockSwitch;
    private AirSettingRow timeoutRow;
    @Nullable
    private AlertDialog timeoutDialog;
    // guards programmatic setChecked() from re-entering the toggle listener
    private boolean suppressToggle = false;

    @Override
    protected int screenTitle()
    {
        return R.string.air_you_security;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // ---- app lock toggle ----
        final AirSettingRow lockRow = new AirSettingRow(this, getString(R.string.pd_lock_title), false)
                .detail(getString(R.string.air_security_pin));
        lockSwitch = lockRow.withSwitch(AppLockHelper.isEnabled(this), new CompoundButton.OnCheckedChangeListener()
        {
            @Override
            public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked)
            {
                if (suppressToggle)
                {
                    return;
                }
                if (isChecked)
                {
                    // Enabling requires setting a PIN first. Keep the switch OFF until the PIN
                    // is confirmed (onActivityResult); revert the visual state meanwhile.
                    suppressToggle = true;
                    buttonView.setChecked(false);
                    suppressToggle = false;
                    final Intent pin = new Intent(ProfileDetailsActivity.this, PinActivity.class);
                    pin.putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SET);
                    startActivityForResult(pin, REQ_SET_PIN);
                }
                else
                {
                    prefs.edit().putBoolean(AppLockHelper.PREF_ENABLED, false).apply();
                    AppLockHelper.clearPin(ProfileDetailsActivity.this);
                    showTimeoutRow();
                }
            }
        });

        // ---- lock timeout ----
        timeoutRow = new AirSettingRow(this, getString(R.string.pd_timeout_title), true);
        timeoutRow.setOnClickListener(v -> showTimeoutPicker());
        updateTimeoutValueLabel();
        AirScreenKit.addSection(column, getString(R.string.air_security_pin_section),
                                getString(R.string.air_security_pin_note), AirScreenKit.plateOf(this, lockRow, timeoutRow));
        showTimeoutRow();

        // ---- change password ----
        // A profile that opens with the key kept on this phone has no password yet: the same page sets one.
        final boolean hasPassword = DbSecretKeyStorage.prefersManualPasswordUnlock(this);
        final AirSettingRow passwordRow = new AirSettingRow(
                this, getString(hasPassword ? R.string.pd_change_password : R.string.air_first_password_set), true);
        // KHANDAQ (#10): SetPasswordActivity redirects to CheckPassword on an existing DB, so it
        // could never change a password: route to the real change-password flow instead.
        passwordRow.setOnClickListener(v -> startActivity(new Intent(this, ChangePasswordActivity.class)));
        AirScreenKit.addSection(column, getString(R.string.air_first_password_title),
                                getString(hasPassword ? R.string.air_security_password_note
                                                      : R.string.air_security_device_key_note),
                                AirScreenKit.plateOf(this, passwordRow));
    }

    @Override
    protected void onDestroy()
    {
        if (timeoutDialog != null)
        {
            timeoutDialog.dismiss();
            timeoutDialog = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SET_PIN && resultCode == RESULT_OK)
        {
            // PIN was set + confirmed: now it's safe to enable the lock.
            prefs.edit().putBoolean(AppLockHelper.PREF_ENABLED, true).apply();
            if (lockSwitch != null)
            {
                suppressToggle = true;
                lockSwitch.setChecked(true);
                suppressToggle = false;
            }
            showTimeoutRow();
        }
    }

    /** When it locks means something only while the lock is on, as on iOS. */
    private void showTimeoutRow()
    {
        if (timeoutRow != null && lockSwitch != null)
        {
            timeoutRow.setVisibility(lockSwitch.isChecked() ? View.VISIBLE : View.GONE);
        }
    }

    private String[] timeoutLabels()
    {
        return new String[]{
                getString(R.string.pd_timeout_now),
                getString(R.string.pd_timeout_1m),
                getString(R.string.pd_timeout_5m),
                getString(R.string.pd_timeout_30m)};
    }

    private int currentTimeoutIndex()
    {
        // The timeout the lock itself uses, so a legacy implicit 0 reads as the default it is migrated to.
        final int cur = AppLockHelper.timeoutSec(this);
        for (int i = 0; i < TIMEOUT_SECONDS.length; i++)
        {
            if (TIMEOUT_SECONDS[i] == cur)
            {
                return i;
            }
        }
        return 0;
    }

    private void updateTimeoutValueLabel()
    {
        if (timeoutRow != null)
        {
            timeoutRow.setValue(timeoutLabels()[currentTimeoutIndex()]);
        }
    }

    /** The choice as radio rows, as the choices of Settings; picking one closes it. */
    private void showTimeoutPicker()
    {
        if (isFinishing() || isDestroyed())
        {
            return;
        }
        final Resources res = getResources();
        final String[] labels = timeoutLabels();
        final int current = currentTimeoutIndex();
        final LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPaddingRelative(0, res.getDimensionPixelSize(R.dimen.kq_space_8), 0, 0);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(choices, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pd_timeout_title)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .create();
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_24);
        for (int i = 0; i < TIMEOUT_SECONDS.length; i++)
        {
            final LinearLayout choice = AirScreenKit.choiceRow(this, labels[i], null, side);
            AirScreenKit.applyChoice(choice, i == current);
            final int which = i;
            choice.setOnClickListener(v -> {
                dialog.dismiss();
                prefs.edit().putInt(AppLockHelper.PREF_TIMEOUT, TIMEOUT_SECONDS[which])
                        .putBoolean(AppLockHelper.PREF_TIMEOUT_USER_SET, true).apply();
                updateTimeoutValueLabel();
            });
            choices.addView(choice, AirScreenKit.matchWidth());
        }
        // From when the time is counted, under the choices.
        final TextView note = AirScreenKit.note(this, getString(R.string.air_security_lock_note));
        note.setPaddingRelative(side, res.getDimensionPixelSize(R.dimen.kq_space_8), side, 0);
        choices.addView(note, AirScreenKit.matchWidth());
        if (timeoutDialog != null)
        {
            timeoutDialog.dismiss();
        }
        timeoutDialog = dialog;
        AirUi.showDialog(this, dialog, false);
    }

    /**
     * Stop tox, arm a one-shot wipe flag + reset the onboarding gate, then kill the process so the
     * encrypted DB files are unlocked. {@link StartMainActivityWrapper} performs the actual deletion
     * on the next (fresh) launch and routes to onboarding. The You screen asks first.
     */
    static void wipeProfile(final Activity activity)
    {
        try
        {
            MainActivity.manually_log_out();
        }
        catch (Exception ignored)
        {
        }
        try
        {
            PreferenceManager.getDefaultSharedPreferences(activity).edit()
                    .putBoolean(PREF__pending_profile_wipe, true)
                    .putBoolean("PW_SET_SCREEN_DONE", false)
                    .commit();
            TRIFAGlobals.PREF__DB_secrect_key__user_hash = "";
        }
        catch (Exception ignored)
        {
        }
        Toast.makeText(activity, R.string.pd_delete_profile, Toast.LENGTH_SHORT).show();
        // give the toast/teardown a beat, then end the process (releases DB file locks)
        try
        {
            activity.finishAffinity();
        }
        catch (Exception ignored)
        {
        }
        System.exit(0);
    }
}

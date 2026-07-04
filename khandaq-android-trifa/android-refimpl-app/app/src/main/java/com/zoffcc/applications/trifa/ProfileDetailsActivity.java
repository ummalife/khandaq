package com.zoffcc.applications.trifa;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.switchmaterial.SwitchMaterial;

import org.khandaq.messenger.R;

/**
 * KHANDAQ Профиль → Детали: app lock (PIN) toggle + lock timeout + change password + delete profile.
 * Lock + change-password reuse the app's existing, tested screens; delete uses a wipe-on-next-launch
 * flag so the encrypted DB is removed by {@link StartMainActivityWrapper} in a fresh process (no open
 * file handles), then routes to onboarding.
 */
public class ProfileDetailsActivity extends AppCompatActivity
{
    static final String PREF__pending_profile_wipe = "PREF__pending_profile_wipe";

    private static final int REQ_SET_PIN = 4801;

    // lock timeout choices, parallel arrays (seconds + label resource)
    private static final int[] TIMEOUT_SECONDS = {0, 60, 300, 1800};

    private SharedPreferences prefs;
    private SwitchMaterial lockSwitch;
    private TextView timeoutValue;
    // guards programmatic setChecked() from re-entering the toggle listener
    private boolean suppressToggle = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_details);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        final ImageView back = findViewById(R.id.pd_back_button);
        if (back != null)
        {
            back.setOnClickListener(v -> finish());
        }

        // ---- app lock toggle ----
        lockSwitch = findViewById(R.id.pd_lock_switch);
        if (lockSwitch != null)
        {
            lockSwitch.setChecked(AppLockHelper.isEnabled(this));
            lockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
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
                    }
                }
            });
        }

        // ---- lock timeout ----
        timeoutValue = findViewById(R.id.pd_timeout_value);
        updateTimeoutValueLabel();
        final View timeoutRow = findViewById(R.id.pd_timeout_row);
        if (timeoutRow != null)
        {
            timeoutRow.setOnClickListener(v -> showTimeoutPicker());
        }

        // ---- change password ----
        final View changePw = findViewById(R.id.pd_change_password_row);
        if (changePw != null)
        {
            // KHANDAQ (#10): SetPasswordActivity redirects to CheckPassword on an existing DB, so it
            // could never change a password — route to the real change-password flow instead.
            changePw.setOnClickListener(v -> startActivity(new Intent(this, ChangePasswordActivity.class)));
        }

        // ---- delete profile ----
        final View deleteRow = findViewById(R.id.pd_delete_row);
        if (deleteRow != null)
        {
            deleteRow.setOnClickListener(v -> confirmDeleteProfile());
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SET_PIN && resultCode == RESULT_OK)
        {
            // PIN was set + confirmed — now it's safe to enable the lock.
            prefs.edit().putBoolean(AppLockHelper.PREF_ENABLED, true).apply();
            if (lockSwitch != null)
            {
                suppressToggle = true;
                lockSwitch.setChecked(true);
                suppressToggle = false;
            }
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
        final int cur = prefs.getInt(AppLockHelper.PREF_TIMEOUT, 0);
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
        if (timeoutValue != null)
        {
            timeoutValue.setText(timeoutLabels()[currentTimeoutIndex()]);
        }
    }

    private void showTimeoutPicker()
    {
        new AlertDialog.Builder(this)
                .setTitle(R.string.pd_timeout_title)
                .setSingleChoiceItems(timeoutLabels(), currentTimeoutIndex(), (dialog, which) ->
                {
                    prefs.edit().putInt(AppLockHelper.PREF_TIMEOUT, TIMEOUT_SECONDS[which]).apply();
                    updateTimeoutValueLabel();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteProfile()
    {
        new AlertDialog.Builder(this)
                .setMessage(R.string.pd_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pd_delete_now, (d, w) -> performProfileWipe())
                .show();
    }

    /**
     * Stop tox, arm a one-shot wipe flag + reset the onboarding gate, then kill the process so the
     * encrypted DB files are unlocked. {@link StartMainActivityWrapper} performs the actual deletion
     * on the next (fresh) launch and routes to onboarding.
     */
    private void performProfileWipe()
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
            prefs.edit()
                    .putBoolean(PREF__pending_profile_wipe, true)
                    .putBoolean("PW_SET_SCREEN_DONE", false)
                    .commit();
            TRIFAGlobals.PREF__DB_secrect_key__user_hash = "";
        }
        catch (Exception ignored)
        {
        }
        Toast.makeText(this, R.string.pd_delete_profile, Toast.LENGTH_SHORT).show();
        // give the toast/teardown a beat, then end the process (releases DB file locks)
        try
        {
            finishAffinity();
        }
        catch (Exception ignored)
        {
        }
        System.exit(0);
    }
}

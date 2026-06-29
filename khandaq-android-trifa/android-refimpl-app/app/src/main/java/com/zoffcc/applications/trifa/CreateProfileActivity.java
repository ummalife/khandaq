package com.zoffcc.applications.trifa;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import org.khandaq.messenger.R;

// KHANDAQ "Создание профиля" (Figma 2031:9523) — onboarding display-name step inserted between the
// intro slides and SetPassword. Saves the entered name; it is applied as the Tox display name on
// first profile creation (see TrifaToxService). The "profile name" label is stored for future use.
public class CreateProfileActivity extends AppCompatActivity
{
    public static final String PREF_ONBOARDING_DISPLAY_NAME = "khandaq_onboarding_display_name";
    public static final String PREF_ONBOARDING_PROFILE_LABEL = "khandaq_onboarding_profile_label";

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_profile);

        final EditText nameField = findViewById(R.id.create_profile_name_field);
        final EditText labelField = findViewById(R.id.create_profile_label_field);
        final ImageView back = findViewById(R.id.back_button);
        final Button next = findViewById(R.id.create_profile_next_button);

        if (back != null)
        {
            back.setOnClickListener(v ->
            {
                startActivity(new Intent(this, OnboardingActivity.class));
                finish();
            });
        }

        next.setOnClickListener(v ->
        {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            final String name = nameField.getText().toString().trim();
            final String label = labelField.getText().toString().trim();
            prefs.edit()
                 .putString(PREF_ONBOARDING_DISPLAY_NAME, name)
                 .putString(PREF_ONBOARDING_PROFILE_LABEL, label)
                 .apply();
            startActivity(new Intent(this, SetPasswordActivity.class));
            finish();
        });
    }
}

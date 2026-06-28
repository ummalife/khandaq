package com.zoffcc.applications.trifa;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.khandaq.messenger.R;

/**
 * KHANDAQ "Импорт профиля" (Figma 2031:9544): numbered instructions for importing a .tox profile.
 * The primary button launches the existing first-launch import (SetPasswordActivity auto-opens the
 * .tox file picker), so the working import logic is reused — this screen is purely presentational.
 */
public class ImportProfileInfoActivity extends AppCompatActivity
{
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_import_profile_info);

        ImageView backButton = (ImageView) findViewById(R.id.back_button);
        if (backButton != null)
        {
            backButton.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    startActivity(new Intent(ImportProfileInfoActivity.this, OnboardingActivity.class));
                    finish();
                }
            });
        }

        TextView pickButton = (TextView) findViewById(R.id.import_pick_button);
        if (pickButton != null)
        {
            pickButton.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    Intent intent = new Intent(ImportProfileInfoActivity.this, SetPasswordActivity.class);
                    intent.putExtra("KHANDAQ_AUTOSTART_IMPORT", true);
                    startActivity(intent);
                    finish();
                }
            });
        }
    }
}

package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.khandaq.messenger.R;

// KHANDAQ: full-screen editor for profile Имя / Статус (Figma) — replaces the old inline dialog.
// Returns the new value via RESULT_OK; ProfileContentFragment writes it through saveProfileChanges().
public class ProfileFieldEditActivity extends AppCompatActivity
{
    public static final String EXTRA_IS_NAME = "khandaq.profile_edit.is_name";
    public static final String EXTRA_VALUE = "khandaq.profile_edit.value";
    public static final String RESULT_VALUE = "khandaq.profile_edit.result_value";

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_field_edit);

        final boolean isName = getIntent().getBooleanExtra(EXTRA_IS_NAME, true);
        final String value = getIntent().getStringExtra(EXTRA_VALUE);

        final TextView title = findViewById(R.id.edit_title);
        final TextView label = findViewById(R.id.edit_label);
        final EditText field = findViewById(R.id.edit_field);
        final ImageView back = findViewById(R.id.edit_back_button);
        final Button save = findViewById(R.id.edit_save_button);

        title.setText(isName ? R.string.profile_label_name : R.string.profile_label_status);
        label.setText(isName ? R.string.profile_edit_name_hint : R.string.profile_edit_status_hint);
        field.setHint(isName ? R.string.profile_edit_name_hint : R.string.profile_edit_status_hint);
        if (value != null)
        {
            field.setText(value);
            field.setSelection(value.length());
        }
        field.requestFocus();

        back.setOnClickListener(v -> finish());
        save.setOnClickListener(v ->
        {
            final Intent data = new Intent();
            data.putExtra(RESULT_VALUE, field.getText().toString());
            setResult(Activity.RESULT_OK, data);
            finish();
        });
    }
}

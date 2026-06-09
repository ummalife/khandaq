/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
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

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.google.android.material.textfield.TextInputLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import static com.zoffcc.applications.trifa.HelperGeneric.is_valid_tox_address_input;
import static com.zoffcc.applications.trifa.HelperGeneric.normalize_tox_address;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ADDRESS_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;

public class AddFriendActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.AddFrdActivity";
    EditText toxid_text = null;
    Button button_add = null;
    TextInputLayout friend_toxid_inputlayout = null;

    private final ActivityResultLauncher<String> requestCameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted ->
            {
                if (granted)
                {
                    launchQrScan();
                }
            });

    private final ActivityResultLauncher<Intent> qrScanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result ->
            {
                if (result.getResultCode() == RESULT_OK && result.getData() != null)
                {
                    final String contents = result.getData().getStringExtra(QrScanActivity.EXTRA_RESULT);
                    if (contents != null)
                    {
                        toxid_text.setText(contents);
                    }
                }
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_addfriend);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        toxid_text = (EditText) findViewById(R.id.friend_toxid);
        button_add = (Button) findViewById(R.id.friend_addbutton);
        friend_toxid_inputlayout = (TextInputLayout) findViewById(R.id.friend_toxid_inputlayout);

        toxid_text.setText("");
        friend_toxid_inputlayout.setError(null);

        toxid_text.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void afterTextChanged(Editable editable)
            {
                String toxid = editable.toString().
                        replace(" ", "").
                        replace("\r", "").
                        replace("\n", "");

                if (is_valid_tox_address_input(toxid))
                {
                    button_add.setEnabled(true);
                    friend_toxid_inputlayout.setErrorEnabled(false);
                }
                else if (toxid.length() == ((TOX_PUBLIC_KEY_SIZE * 2) + "tox:".length()))
                {
                    button_add.setEnabled(true);
                    friend_toxid_inputlayout.setErrorEnabled(false);
                }
                else if (toxid.length() == ((TOX_ADDRESS_SIZE * 2) + "tox:".length()))
                {
                    button_add.setEnabled(true);
                    friend_toxid_inputlayout.setErrorEnabled(false);
                }
                else
                {
                    button_add.setEnabled(false);
                    if (toxid.length() > 0)
                    {
                        friend_toxid_inputlayout.setError(getString(R.string.AddFriendActivity_3));
                    }
                    else
                    {
                        friend_toxid_inputlayout.setError(getString(R.string.AddFriendActivity_4));
                    }
                }
            }

            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2)
            {
            }

            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2)
            {
            }
        });
    }

    private void launchQrScan()
    {
        qrScanLauncher.launch(new Intent(this, QrScanActivity.class));
    }

    public void read_qr_code(View v)
    {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        {
            launchQrScan();
        }
        else
        {
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    public void add_friend_clicked(View v)
    {
        Intent intent = new Intent();
        boolean toxid_ok = false;
        if (toxid_text.getText() != null)
        {
            if (toxid_text.getText().length() > 0)
            {
                toxid_ok = true;
            }
        }

        if (toxid_ok == true)
        {
            final String tox_id_text_clean = normalize_tox_address(toxid_text.getText().toString());

            intent.putExtra("toxid", tox_id_text_clean);
            setResult(RESULT_OK, intent);
        }
        else
        {
            setResult(RESULT_CANCELED, intent);
        }
        finish();
    }

    public void cancel_clicked(View v)
    {
        finish();
    }
}

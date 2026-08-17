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
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

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
                else
                {
                    Toast.makeText(AddFriendActivity.this, R.string.qr_scan_permission_denied,
                            Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<Intent> qrScanLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result ->
            {
                if (result.getResultCode() == RESULT_OK && result.getData() != null)
                {
                    final String contents = result.getData().getStringExtra(QrScanActivity.EXTRA_RESULT);
                    applyScannedQrContent(contents);
                }
            });

    private final ActivityResultLauncher<String> galleryPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri ->
            {
                if (uri == null)
                {
                    return;
                }
                decodeQrFromGallery(uri);
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_addfriend);
        ViewUtil.keep_content_above_keyboard(findViewById(android.R.id.content));

        // KHANDAQ: the "Add friend" button sits at the very bottom of the screen and was drawn under
        // the system navigation bar — on a 3-button nav it is almost impossible to hit. Same fix the
        // other screens use: pad the content root by the status-bar/nav-bar (and keyboard) insets.
        ViewUtil.applyImeBottomInsets(this);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.enableUpNavigation(this, toolbar);

        toxid_text = (EditText) findViewById(R.id.friend_toxid);
        button_add = (Button) findViewById(R.id.friend_addbutton);
        friend_toxid_inputlayout = (TextInputLayout) findViewById(R.id.friend_toxid_inputlayout);

        toxid_text.setText("");
        friend_toxid_inputlayout.setError(null);

        // Tester request: one-tap paste of a copied ID (the paste icon inside the field).
        friend_toxid_inputlayout.setEndIconOnClickListener(v ->
        {
            try
            {
                final android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if ((clipboard != null) && clipboard.hasPrimaryClip()
                        && (clipboard.getPrimaryClip().getItemCount() > 0))
                {
                    final CharSequence text =
                            clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
                    if (text != null && text.length() > 0)
                    {
                        toxid_text.setText(text.toString().trim());
                        toxid_text.setSelection(toxid_text.getText().length());
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });

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

    public void read_qr_code_from_gallery(View v)
    {
        galleryPickerLauncher.launch("image/*");
    }

    public void add_self_clicked(View v)
    {
        FavoritesChatHelper.openChat(this);
        finish();
    }

    private void decodeQrFromGallery(final Uri uri)
    {
        QrImageDecoder.decodeFromUri(this, uri, new QrImageDecoder.Callback()
        {
            @Override
            public void onSuccess(final String value)
            {
                runOnUiThread(() -> applyScannedQrContent(value));
            }

            @Override
            public void onFailure()
            {
                runOnUiThread(() -> Toast.makeText(AddFriendActivity.this,
                        R.string.qr_scan_image_error, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void applyScannedQrContent(final String contents)
    {
        if ((contents == null) || (contents.isEmpty()))
        {
            Toast.makeText(this, R.string.qr_scan_image_error, Toast.LENGTH_LONG).show();
            return;
        }

        final String normalized = normalize_tox_address(contents);
        if (normalized != null)
        {
            toxid_text.setText(normalized);
            button_add.setEnabled(true);
            friend_toxid_inputlayout.setErrorEnabled(false);
            return;
        }

        toxid_text.setText(contents.trim());
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

            if (tox_id_text_clean == null)
            {
                friend_toxid_inputlayout.setError(getString(R.string.AddFriendActivity_3));
                setResult(RESULT_CANCELED, intent);
            }
            else
            {
                intent.putExtra("toxid", tox_id_text_clean);
                setResult(RESULT_OK, intent);
            }
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

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

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView.OnEditorActionListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.text.PrecomputedTextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.documentfile.provider.DocumentFile;
import de.hdodenhof.circleimageview.CircleImageView;

import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;
import static com.zoffcc.applications.trifa.HelperFiletransfer.copy_outgoing_file_to_sdcard_dir;
import static com.zoffcc.applications.trifa.HelperGeneric.copy_real_file_to_vfs_file;
import static com.zoffcc.applications.trifa.HelperGeneric.del_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.del_own_avatar;
import static com.zoffcc.applications.trifa.HelperGeneric.get_network_connections;
import static com.zoffcc.applications.trifa.HelperGeneric.get_vfs_image_filename_own_avatar;
import static com.zoffcc.applications.trifa.HelperGeneric.is_nightmode_active;
import static com.zoffcc.applications.trifa.HelperGeneric.need_rotate_image_to_exif;
import static com.zoffcc.applications.trifa.HelperGeneric.put_vfs_image_on_imageview_real;
import static com.zoffcc.applications.trifa.HelperGeneric.rotate_image_to_exif;
import static com.zoffcc.applications.trifa.HelperGeneric.scale_bitmap_keep_aspect;
import static com.zoffcc.applications.trifa.HelperGeneric.send_avatar_to_all_friends;
import static com.zoffcc.applications.trifa.HelperGeneric.set_g_opts;
import static com.zoffcc.applications.trifa.HelperGeneric.set_new_random_nospam_value;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperRelay.get_own_relay_pubkey;
import static com.zoffcc.applications.trifa.HelperRelay.have_own_pushurl;
import static com.zoffcc.applications.trifa.HelperRelay.have_own_relay;
import static com.zoffcc.applications.trifa.HelperRelay.own_push_token_load;
import static com.zoffcc.applications.trifa.HelperRelay.push_token_to_push_url;
import static com.zoffcc.applications.trifa.HelperRelay.remove_own_pushurl_in_db;
import static com.zoffcc.applications.trifa.HelperRelay.remove_own_relay_in_db;
import static com.zoffcc.applications.trifa.MainActivity.clipboard;
import static com.zoffcc.applications.trifa.MainActivity.friend_list_fragment;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.MainActivity.tox_get_all_tcp_relays;
import static com.zoffcc.applications.trifa.MainActivity.tox_get_all_udp_connections;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_capabilities;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_name_size;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_status_message;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_status_message_size;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_set_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_set_status_message;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_OWN_AVATAR_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_OWN_AVATAR_DIR_FILENAME_WITH_EXTENSION;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_OWN_AVATAR_DIR_FILE_EXTENSION;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_PREFIX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_status_message;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CAPABILITY_DECODE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CAPABILITY_DECODE_TO_STRING;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NAME_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_STATUS_MESSAGE_LENGTH;

public class ProfileActivity extends AppCompatActivity
{
    static final String TAG = "trifa.ProfileActy";
    CircleImageView profile_icon = null;
    FloatingActionButton profile_icon_edit = null;
    FloatingActionButton profile_icon_remove = null;
    ImageView mytoxid_imageview = null;
    TextView mytoxid_textview = null;
    TextView my_toxcapabilities_textview = null;
    EditText mynick_edittext = null;
    EditText mystatus_message_edittext = null;
    Button profile_save_button = null;
    Button new_nospam_button = null;
    Button copy_toxid_button = null;
    Button remove_own_relay_button = null;
    Button remove_own_pushurl_button = null;
    Button load_network_connections_button = null;
    TextView my_relay_toxid_textview = null;
    TextView my_relay_toxid_text = null;
    TextView my_pushurl_textview = null;
    TextView my_pushurl_text = null;
    TextView mytox_network_connections = null;
    static final int MEDIAPICK_ID_002 = 8003;

    static Handler profile_handler_s = null;
    private boolean stop_me_netconn = false;
    private boolean profileDirty = false;

    private final TextWatcher profileFieldWatcher = new TextWatcher()
    {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after)
        {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count)
        {
            profileDirty = true;
        }

        @Override
        public void afterTextChanged(Editable s)
        {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        profile_icon = findViewById(R.id.profile_icon);
        profile_icon_edit = findViewById(R.id.profile_icon_edit);
        profile_icon_remove = findViewById(R.id.profile_icon_remove);
        mytoxid_imageview = findViewById(R.id.mytoxid_imageview);
        mytoxid_textview = findViewById(R.id.mytoxid_textview);
        mynick_edittext = findViewById(R.id.mynick_edittext);
        mystatus_message_edittext = findViewById(R.id.mystatus_message_edittext);
        profile_save_button = findViewById(R.id.profile_save_button);
        my_toxcapabilities_textview = findViewById(R.id.my_toxcapabilities_textview);

        new_nospam_button = findViewById(R.id.new_nospam_button);
        remove_own_relay_button = findViewById(R.id.remove_relay_button);
        my_relay_toxid_textview = findViewById(R.id.my_relay_toxid_textview);
        my_relay_toxid_text = findViewById(R.id.my_relay_toxid_text);
        remove_own_pushurl_button = findViewById(R.id.remove_own_pushurl_button);
        my_pushurl_textview = findViewById(R.id.my_pushurl_textview);
        my_pushurl_text = findViewById(R.id.my_pushurl_text);
        copy_toxid_button = findViewById(R.id.copy_toxid_button);

        mytox_network_connections = findViewById(R.id.mytox_network_connections);
        load_network_connections_button = findViewById(R.id.load_network_connections_button);

        my_toxcapabilities_textview.setText(
                TOX_CAPABILITY_DECODE_TO_STRING(TOX_CAPABILITY_DECODE(tox_self_get_capabilities())));

        load_network_connections_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                try
                {
                    final Thread t4 = new Thread()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                final String net_conn_text =
                                        "THIS feature is BETA, and does not show all connections yet!!\n\nCurrently conntected TCP Relays:\n(If you are using a Proxy this will show the connections after the Proxy!)\n\n" +
                                        tox_get_all_tcp_relays() + "\nCurrent UDP connections:\n\n" +
                                        tox_get_all_udp_connections() + "\n\nOLD:\n" + get_network_connections();
                                PrecomputedTextCompat.Params tvcp = TextViewCompat.getTextMetricsParams(
                                        mytox_network_connections);
                                PrecomputedTextCompat pText = PrecomputedTextCompat.create(net_conn_text, tvcp);
                                Runnable myRunnable = new Runnable()
                                {
                                    @Override
                                    public void run()
                                    {
                                        try
                                        {
                                            TextViewCompat.setPrecomputedText(mytox_network_connections, pText);
                                        }
                                        catch (Exception e)
                                        {
                                            e.printStackTrace();
                                        }
                                    }
                                };
                                if (main_handler_s != null)
                                {
                                    main_handler_s.post(myRunnable);
                                }
                            }
                            catch (Exception ignored)
                            {
                            }
                        }
                    };
                    t4.start();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });

        new_nospam_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                try
                {
                    set_new_random_nospam_value();
                    Toast.makeText(v.getContext(), "generated new Random NOSPAM value", Toast.LENGTH_SHORT).show();

                    // ---- change display to the new ToxID ----
                    update_toxid_display();
                    // ---- change display to the new ToxID ----
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });

        my_relay_toxid_text.setVisibility(View.GONE);

        if (have_own_relay())
        {
            my_relay_toxid_text.setVisibility(View.VISIBLE);

            remove_own_relay_button.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    try
                    {
                        remove_own_relay_in_db();

                        // load all friends into data list ---
                        Log.i(TAG, "onMenuItemClick:6");
                        try
                        {
                            if (friend_list_fragment != null)
                            {
                                // reload friendlist
                                friend_list_fragment.add_all_friends_clear(0);
                            }
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        my_relay_toxid_textview.setVisibility(View.INVISIBLE);
                        remove_own_relay_button.setVisibility(View.INVISIBLE);

                        try
                        {
                            remove_own_relay_button.setVisibility(View.GONE);
                        }
                        catch (Exception e1)
                        {
                        }

                        try
                        {
                            my_relay_toxid_textview.setVisibility(View.GONE);
                        }
                        catch (Exception e1)
                        {
                        }

                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            });
            my_relay_toxid_textview.setText(get_own_relay_pubkey());
            my_relay_toxid_textview.setVisibility(View.VISIBLE);
        }
        else
        {
            remove_own_relay_button.setVisibility(View.INVISIBLE);
            try
            {
                remove_own_relay_button.setVisibility(View.GONE);
            }
            catch (Exception e1)
            {
            }

            my_relay_toxid_textview.setText("--");
            my_relay_toxid_textview.setVisibility(View.INVISIBLE);
            try
            {
                my_relay_toxid_textview.setVisibility(View.GONE);
            }
            catch (Exception e1)
            {
            }
        }

        remove_own_pushurl_button.setVisibility(View.GONE);
        my_pushurl_textview.setVisibility(View.GONE);
        my_pushurl_text.setVisibility(View.GONE);

        if (have_own_pushurl())
        {
            own_push_token_load();
            remove_own_pushurl_button.setVisibility(View.VISIBLE);

            remove_own_pushurl_button.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    try
                    {
                        remove_own_pushurl_in_db();

                        my_pushurl_textview.setVisibility(View.INVISIBLE);
                        remove_own_pushurl_button.setVisibility(View.INVISIBLE);

                        try
                        {
                            remove_own_pushurl_button.setVisibility(View.GONE);
                        }
                        catch (Exception e1)
                        {
                        }

                        try
                        {
                            my_pushurl_textview.setVisibility(View.GONE);
                        }
                        catch (Exception e1)
                        {
                        }

                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            });

            final String push_url_temp = push_token_to_push_url(TRIFAGlobals.global_notification_token);
            if (push_url_temp != null)
            {
                my_pushurl_textview.setText(push_url_temp);
                my_pushurl_textview.setVisibility(View.VISIBLE);
                my_pushurl_text.setVisibility(View.VISIBLE);
            }
        }

        copy_toxid_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                try
                {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", MainActivity.get_my_toxid()));
                    Toast.makeText(v.getContext(), getString(R.string.id_copied_to_clipboard), Toast.LENGTH_SHORT).show();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.enableUpNavigation(this, toolbar);

        // don't show keyboard when activity starts
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        final Drawable d1 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_face).color(
                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(200);
        profile_icon.setImageDrawable(d1);
        update_avatar_remove_button_visibility();

        mytoxid_textview.setText("");
        mynick_edittext.setText(global_my_name);
        mystatus_message_edittext.setText(global_my_status_message);
        mynick_edittext.addTextChangedListener(profileFieldWatcher);
        mystatus_message_edittext.addTextChangedListener(profileFieldWatcher);
        profileDirty = false;

        profile_save_button.setOnClickListener(v -> saveProfileChanges(true));

        final OnEditorActionListener saveOnDoneListener = (v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE)
            {
                saveProfileChanges(true);
                return true;
            }
            return false;
        };
        mystatus_message_edittext.setOnEditorActionListener(saveOnDoneListener);

        profile_icon_edit.setOnClickListener(v -> {
            // select new avatar image
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            {
                intent.putExtra(Intent.EXTRA_MIME_TYPES, "image/*");
            }
            startActivityForResult(intent, MEDIAPICK_ID_002);
        });

        profile_icon_remove.setOnClickListener(v -> {
            try
            {
                AlertDialog ad = new AlertDialog.Builder(v.getContext()).
                        setNegativeButton(R.string.MainActivity_no_button, new DialogInterface.OnClickListener()
                        {
                            public void onClick(DialogInterface dialog, int id)
                            {
                            }
                        }).
                        setPositiveButton(R.string.MainActivity_button_ok, new DialogInterface.OnClickListener()
                        {
                            public void onClick(DialogInterface dialog, int id)
                            {
                                try
                                {
                                    del_own_avatar();
                                }
                                catch (Exception ignored)
                                {
                                }

                                try
                                {
                                    del_g_opts("VFS_OWN_AVATAR_FNAME");
                                    del_g_opts("VFS_OWN_AVATAR_FILE_EXTENSION");
                                }
                                catch (Exception ignored)
                                {
                                }

                                try
                                {
                                    final Drawable d1 = new IconicsDrawable(v.getContext()).icon(
                                            GoogleMaterial.Icon.gmd_face).color(
                                            getResources().getColor(R.color.colorPrimaryDark)).sizeDp(200);
                                    profile_icon.setImageDrawable(d1);
                                    update_avatar_remove_button_visibility();
                                }
                                catch (Exception ignored)
                                {
                                }
                            }
                        }).create();
                ad.setTitle(getString(R.string.ProfileActivity_delete_avatar_dialog_title));
                ad.setMessage(getString(R.string.ProfileActivity_delete_avatar_dialog_text));
                ad.setCancelable(false);
                ad.setCanceledOnTouchOutside(false);
                ad.show();
            }
            catch (Exception ignored)
            {
            }
        });
        profile_handler_s = profile_handler;
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        reloadProfileFromTox();

        mytox_network_connections.setText("");

        final Thread t1 = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Thread.sleep(50);
                    android.os.Message msg2 = new android.os.Message();
                    Bundle b2 = new Bundle();
                    msg2.what = 3;
                    msg2.setData(b2);
                    profile_handler_s.sendMessage(msg2);
                }
                catch (Exception ignored)
                {
                }
            }
        };
        t1.start();

        final Thread t2 = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    Thread.sleep(50);
                    android.os.Message msg2 = new android.os.Message();
                    Bundle b2 = new Bundle();
                    msg2.what = 1;
                    msg2.setData(b2);
                    profile_handler_s.sendMessage(msg2);
                }
                catch (Exception ignored)
                {
                }
            }
        };
        t2.start();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MEDIAPICK_ID_002 && resultCode == Activity.RESULT_OK)
        {
            if ((data == null) || (data.getData() == null))
            {
                Toast.makeText(this, getString(R.string.profile_avatar_error_generic), Toast.LENGTH_SHORT).show();
                return;
            }

            try
            {
                final Uri uri = data.getData();
                final String fileName_ = resolve_picked_image_display_name(uri);

                long file_size = -1;
                try
                {
                    DocumentFile documentFile = DocumentFile.fromSingleUri(this.getApplicationContext(), uri);
                    if (documentFile != null)
                    {
                        file_size = documentFile.length();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }

                if (file_size > 10 * 1024 * 1024)
                {
                    Toast.makeText(this, getString(R.string.profile_avatar_error_too_large), Toast.LENGTH_SHORT).show();
                    return;
                }

                // DocumentFile.length() often returns -1; validate size after copy instead.
                if ((file_size >= 0) && (file_size < 100))
                {
                    Toast.makeText(this, getString(R.string.profile_avatar_error_generic), Toast.LENGTH_SHORT).show();
                    return;
                }

                MessageListActivity.outgoing_file_wrapped ofw = copy_outgoing_file_to_sdcard_dir(uri.toString(),
                                                                                                 fileName_,
                                                                                                 file_size > 0 ? file_size : 0);
                if (ofw == null)
                {
                    Toast.makeText(this, getString(R.string.profile_avatar_error_generic), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (ofw.file_size_wrapped < 100)
                {
                    Toast.makeText(this, getString(R.string.profile_avatar_error_generic), Toast.LENGTH_SHORT).show();
                    return;
                }

                if (ofw.file_size_wrapped > 10 * 1024 * 1024)
                {
                    Toast.makeText(this, getString(R.string.profile_avatar_error_too_large), Toast.LENGTH_SHORT).show();
                    return;
                }

                final String local_image_path = ofw.filepath_wrapped + "/" + ofw.filename_wrapped;
                Bitmap b = BitmapFactory.decodeFile(local_image_path);
                if (b == null)
                {
                    Toast.makeText(this, getString(R.string.profile_avatar_error_unsupported), Toast.LENGTH_SHORT).show();
                    try
                    {
                        new java.io.File(local_image_path).delete();
                    }
                    catch (Exception ignored)
                    {
                    }
                    return;
                }

                Bitmap out;
                if (need_rotate_image_to_exif(b, local_image_path))
                {
                    out = scale_bitmap_keep_aspect(rotate_image_to_exif(b, local_image_path), 640, 640);
                }
                else
                {
                    out = scale_bitmap_keep_aspect(b, 640, 640);
                }

                java.io.File file = new java.io.File(ofw.filepath_wrapped, ofw.filename_wrapped);
                Log.i(TAG, "profile_avatar:1:w x h=" + out.getWidth() + " " + out.getHeight());
                try
                {
                    java.io.FileOutputStream fOut = new java.io.FileOutputStream(file);
                    out.compress(Bitmap.CompressFormat.JPEG, 80, fOut);
                    fOut.flush();
                    fOut.close();
                    b.recycle();
                    out.recycle();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    Toast.makeText(this, getString(R.string.profile_avatar_error_generic), Toast.LENGTH_SHORT).show();
                    return;
                }

                Log.i(TAG, "select_avatar:p=" + ofw.filepath_wrapped + " f=" + ofw.filename_wrapped);
                copy_real_file_to_vfs_file(ofw.filepath_wrapped, ofw.filename_wrapped, VFS_PREFIX + VFS_OWN_AVATAR_DIR,
                                           VFS_OWN_AVATAR_DIR_FILENAME_WITH_EXTENSION);

                try
                {
                    file.delete();
                }
                catch (Exception ignored)
                {
                }

                final String vfs_avatar_path =
                        VFS_PREFIX + VFS_OWN_AVATAR_DIR + "/" + VFS_OWN_AVATAR_DIR_FILENAME_WITH_EXTENSION;
                info.guardianproject.iocipher.File vfs_avatar = new info.guardianproject.iocipher.File(vfs_avatar_path);
                if ((!vfs_avatar.exists()) || (vfs_avatar.length() < 100))
                {
                    Toast.makeText(this, getString(R.string.profile_avatar_error_generic), Toast.LENGTH_SHORT).show();
                    return;
                }

                set_g_opts("VFS_OWN_AVATAR_FNAME", vfs_avatar_path);
                set_g_opts("VFS_OWN_AVATAR_FILE_EXTENSION", VFS_OWN_AVATAR_DIR_FILE_EXTENSION);

                final Drawable d2 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_face).color(
                        getResources().getColor(R.color.colorPrimaryDark)).sizeDp(200);
                put_vfs_image_on_imageview_real(ProfileActivity.this, profile_icon, d2, vfs_avatar_path, true, false,
                                                null);

                send_avatar_to_all_friends();
                update_avatar_remove_button_visibility();
                Toast.makeText(this, getString(R.string.profile_avatar_saved_toast), Toast.LENGTH_SHORT).show();
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Toast.makeText(this, getString(R.string.profile_avatar_error_generic), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String resolve_picked_image_display_name(final Uri uri)
    {
        String fileName1 = null;

        try
        {
            DocumentFile documentFile = DocumentFile.fromSingleUri(this.getApplicationContext(), uri);
            if (documentFile != null)
            {
                fileName1 = documentFile.getName();
            }

            ContentResolver cr = getApplicationContext().getContentResolver();
            Cursor metaCursor = cr.query(uri, null, null, null, null);
            if (metaCursor != null)
            {
                try
                {
                    if (metaCursor.moveToFirst())
                    {
                        int j;
                        for (j = 0; j < metaCursor.getColumnNames().length; j++)
                        {
                            if (metaCursor.getColumnName(j).equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                            {
                                if (metaCursor.getString(j) != null)
                                {
                                    if (metaCursor.getString(j).length() > 0)
                                    {
                                        fileName1 = metaCursor.getString(j);
                                    }
                                }
                            }
                        }
                    }
                }
                finally
                {
                    metaCursor.close();
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if ((fileName1 == null) || (fileName1.length() < 1))
        {
            return "avatar_pick.jpg";
        }

        return fileName1;
    }

    private void update_avatar_remove_button_visibility()
    {
        if (profile_icon_remove == null)
        {
            return;
        }

        try
        {
            String fname = get_vfs_image_filename_own_avatar();
            boolean has_avatar = false;

            if (fname != null)
            {
                info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(fname);
                has_avatar = f1.exists() && (f1.length() > 0);
            }

            profile_icon_remove.setVisibility(has_avatar ? View.VISIBLE : View.GONE);
        }
        catch (Exception ignored)
        {
            profile_icon_remove.setVisibility(View.GONE);
        }
    }

    static void update_toxid_display_s()
    {
        try
        {
            android.os.Message msg2 = new android.os.Message();
            Bundle b2 = new Bundle();
            msg2.what = 1;
            msg2.setData(b2);
            profile_handler_s.sendMessage(msg2);
        }
        catch (Exception e)
        {
            // e.printStackTrace();
        }
    }

    void update_toxid_display()
    {
        try
        {
            mytoxid_imageview.setImageBitmap(encodeAsBitmap("tox:" + MainActivity.get_my_toxid()));
            // HINT: https://toktok.ltd/spec.html#messenger -> "Tox ID:"
            // 32 	long term public key
            // 4 	nospam
            // 2 	checksum
            String my_tox_id_temp = MainActivity.get_my_toxid();

            if (my_tox_id_temp == null)
            {
                // on error use Echobots ToxID
                // TODO: do something else here
                my_tox_id_temp = "76518406F6A9F2217E8DC487CC783C25CC16A15EB36FF32E335A235342C48A39218F515C39A6";
            }

            String my_pk_key_temp = my_tox_id_temp.substring(0, 64);
            String my_nospam_temp = my_tox_id_temp.substring(64, 72);
            String my_chksum_temp = my_tox_id_temp.substring(72, my_tox_id_temp.length());
            String color_pkey = "<font color=\"#331bc5\">";
            String color_nospam = "<font color=\"#990d45\">";
            String color_chksum = "<font color=\"#006600\">";
            String ec = "</font>";

            if (is_nightmode_active(getApplicationContext()))
            {
                color_pkey = "<font color=\"#8affffff\">";
            }

            mytoxid_textview.setText(Html.fromHtml(
                    color_pkey + my_pk_key_temp + ec + color_nospam + my_nospam_temp + ec + color_chksum +
                    my_chksum_temp + ec));
        }
        catch (WriterException e)
        {
            e.printStackTrace();

            try
            {
                mytoxid_imageview.setImageBitmap(encodeAsBitmap("123")); // in case something goes wrong
                mytoxid_textview.setText(MainActivity.get_my_toxid());
            }
            catch (WriterException e2)
            {
                e2.printStackTrace();
            }

        }
        catch (Exception e3)
        {
            e3.printStackTrace();
        }

    }

    void reloadProfileFromTox()
    {
        if (!is_tox_started)
        {
            return;
        }

        try
        {
            if (tox_self_get_name_size() > 0)
            {
                String name = tox_self_get_name();
                if (name != null)
                {
                    global_my_name = name;
                    mynick_edittext.setText(global_my_name);
                }
            }

            if (tox_self_get_status_message_size() > 0)
            {
                String status = tox_self_get_status_message();
                if (status != null)
                {
                    global_my_status_message = status;
                    mystatus_message_edittext.setText(global_my_status_message);
                }
            }
            else
            {
                global_my_status_message = "";
                mystatus_message_edittext.setText("");
            }

            profileDirty = false;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    void saveProfileChanges(boolean showToast)
    {
        if (!is_tox_started)
        {
            return;
        }

        try
        {
            String nick = mynick_edittext.getText().toString().trim();
            if (nick.isEmpty())
            {
                return;
            }

            global_my_name = nick.substring(0, Math.min(nick.length(), TOX_MAX_NAME_LENGTH));
            global_my_status_message = mystatus_message_edittext.getText().toString().substring(0, Math.min(
                    mystatus_message_edittext.getText().toString().length(), TOX_MAX_STATUS_MESSAGE_LENGTH));
            tox_self_set_name(global_my_name);
            tox_self_set_status_message(global_my_status_message);
            update_savedata_file_wrapper();
            MainActivity.update_main_profile_bar();
            profileDirty = false;

            if (showToast)
            {
                hide_soft_keyboard();
                Toast.makeText(this, getString(R.string.profile_saved_toast), Toast.LENGTH_SHORT).show();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    void hide_soft_keyboard()
    {
        try
        {
            final View focused = getCurrentFocus();
            if (focused != null)
            {
                final InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null)
                {
                    imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        if (profileDirty)
        {
            saveProfileChanges(false);
        }
    }

    Bitmap encodeAsBitmap(String str) throws WriterException
    {
        BitMatrix result;
        try
        {
            result = new MultiFormatWriter().encode(str, BarcodeFormat.QR_CODE, 200, 200, null);
        }
        catch (IllegalArgumentException iae)
        {
            // Unsupported format
            return null;
        }
        int w = result.getWidth();
        int h = result.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++)
        {
            int offset = y * w;
            for (int x = 0; x < w; x++)
            {
                pixels[offset + x] = result.get(x, y) ? BLACK : WHITE;
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, 200, 0, 0, w, h);
        return bitmap;
    }

    Handler profile_handler = new Handler()
    {
        @Override
        public void handleMessage(android.os.Message msg)
        {
            super.handleMessage(msg);

            try
            {
                int id = msg.what;

                if (id == 1)
                {
                    update_toxid_display();
                }
                else if (id == 3)
                {
                    try
                    {
                        String fname = get_vfs_image_filename_own_avatar();
                        if (fname != null)
                        {
                            final Drawable d1 = new IconicsDrawable(getApplicationContext()).icon(
                                    GoogleMaterial.Icon.gmd_face).color(
                                    getResources().getColor(R.color.colorPrimaryDark)).sizeDp(200);
                            put_vfs_image_on_imageview_real(getApplicationContext(), profile_icon, d1, fname, true,
                                                            false, null);
                        }
                        update_avatar_remove_button_visibility();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    };

}

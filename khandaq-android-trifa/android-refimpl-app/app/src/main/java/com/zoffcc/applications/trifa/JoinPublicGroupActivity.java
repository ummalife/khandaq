/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2022 Zoff <zoff@zoff.cc>
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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import com.google.android.material.textfield.TextInputLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import static com.zoffcc.applications.trifa.HelperGroup.join_public_group_by_id;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_CHAT_ID_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ADDRESS_SIZE;

public class JoinPublicGroupActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.JoinPubGrpActy";
    EditText groupid_text = null;
    EditText group_password_text = null;
    Button button_join = null;
    TextInputLayout join_group_inputlayout = null;
    ProgressBar join_progress = null;
    volatile boolean join_in_progress = false;

    /** Split arbitrary text into maximal runs of hex characters. */
    private static java.util.List<String> hex_runs(final String raw)
    {
        final java.util.List<String> runs = new java.util.ArrayList<>();
        if (raw == null)
        {
            return runs;
        }
        final StringBuilder cur = new StringBuilder();
        for (int i = 0; i < raw.length(); i++)
        {
            final char c = raw.charAt(i);
            final boolean is_hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (is_hex)
            {
                cur.append(c);
            }
            else if (cur.length() > 0)
            {
                runs.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0)
        {
            runs.add(cur.toString());
        }
        return runs;
    }

    /**
     * Extract the group chat-id (64 hex) from raw input. Accepts either a bare chat-id or a full
     * shared invite that also contains the inviter's Tox ID and prose: hex runs are matched by exact
     * length, so the 64-char chat-id and the 76-char Tox ID never collide.
     */
    static String extract_chat_id(final String raw)
    {
        for (final String run : hex_runs(raw))
        {
            if (run.length() == (TOX_GROUP_CHAT_ID_SIZE * 2))
            {
                return run;
            }
        }
        return null;
    }

    /** Extract the inviter's Tox ID (76 hex) from a shared invite, if present. */
    static String extract_inviter_toxid(final String raw)
    {
        for (final String run : hex_runs(raw))
        {
            if (run.length() == (TOX_ADDRESS_SIZE * 2))
            {
                return run;
            }
        }
        return null;
    }

    private void validateGroupIdAndUpdateJoinButton()
    {
        if (groupid_text == null || button_join == null || join_group_inputlayout == null)
        {
            return;
        }

        if (join_in_progress)
        {
            button_join.setEnabled(false);
            return;
        }

        final String raw = groupid_text.getText() != null ? groupid_text.getText().toString() : "";

        if (raw.length() == 0)
        {
            button_join.setEnabled(false);
            join_group_inputlayout.setErrorEnabled(false);
            join_group_inputlayout.setError(null);
            groupid_text.setError(null);
            return;
        }

        // Accept either a bare 64-hex chat-id or a full shared invite (which also carries the
        // inviter's Tox ID and prose). We just need to find a valid chat-id token inside it.
        if (extract_chat_id(raw) == null)
        {
            button_join.setEnabled(false);
            join_group_inputlayout.setErrorEnabled(false);
            groupid_text.setError(getString(R.string.join_public_group_error_length));
            return;
        }

        join_group_inputlayout.setErrorEnabled(false);
        groupid_text.setError(null);
        button_join.setEnabled(true);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_joinpublicgroup);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.setScreenTitle(this, toolbar, R.string.join_public_activity_name);

        groupid_text = (EditText) findViewById(R.id.group_join_group_id);
        group_password_text = (EditText) findViewById(R.id.group_join_password);
        button_join = (Button) findViewById(R.id.friend_joingroup);
        join_group_inputlayout = (TextInputLayout) findViewById(R.id.join_group_inputlayout);
        join_progress = (ProgressBar) findViewById(R.id.join_group_progress);

        groupid_text.setText("");
        join_group_inputlayout.setError(null);
        join_group_inputlayout.setErrorEnabled(false);
        button_join.setEnabled(false);

        groupid_text.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void afterTextChanged(Editable editable)
            {
                validateGroupIdAndUpdateJoinButton();
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

        try
        {
            Intent intent = getIntent();
            String ngc_group_pubkey = intent.getStringExtra("ngc_group_pubkey");
            if ((ngc_group_pubkey != null) && (ngc_group_pubkey.length() > 1))
            {
                groupid_text.setText(ngc_group_pubkey);
            }
        }
        catch(Exception e)
        {
        }

        validateGroupIdAndUpdateJoinButton();
    }

    public void join_group_public_clicked(View v)
    {
        Intent intent = new Intent();
        boolean group_name_ok = false;
        if (groupid_text.getText() != null)
        {
            if (groupid_text.getText().length() > 0)
            {
                group_name_ok = true;
            }
        }

        final String raw_input = (groupid_text.getText() != null) ? groupid_text.getText().toString() : "";
        final String group_id_clean = extract_chat_id(raw_input);
        if (group_name_ok == true && group_id_clean != null)
        {
            // A shared invite may also carry the inviter's Tox ID. If present, add them as a contact:
            // once the friendship connects (which works over TCP relays, no UDP needed), the existing
            // friend-assisted-join machinery pulls us into the group even when a chat-id-only join
            // can't complete (UDP-blocked discovery). This is the receiving end of the share invite.
            final String inviter_toxid = extract_inviter_toxid(raw_input);

            intent.putExtra("group_id", group_id_clean);
            final String password = (group_password_text != null && group_password_text.getText() != null)
                    ? group_password_text.getText().toString().trim()
                    : null;
            final String password_final = (password != null && password.length() > 0) ? password : null;

            join_in_progress = true;
            button_join.setEnabled(false);
            if (join_progress != null)
            {
                join_progress.setVisibility(View.VISIBLE);
            }
            groupid_text.setEnabled(false);
            if (group_password_text != null)
            {
                group_password_text.setEnabled(false);
            }

            final String group_id_final = group_id_clean;
            final Intent result_intent = intent;
            new Thread(() ->
            {
                if (inviter_toxid != null
                    && !HelperFriend.is_own_public_key(inviter_toxid.substring(0, ToxVars.TOX_PUBLIC_KEY_SIZE * 2)))
                {
                    // add_friend_real is idempotent (no-op if already a contact) and sends a request.
                    HelperFriend.add_friend_real(inviter_toxid);
                    // arm the friend-assisted join so we get invited as soon as the inviter is online.
                    HelperGroup.send_group_invite_request_to_friends(group_id_final);
                    Log.i(TAG, "join_group_public_clicked:invite has inviter contact -> friend-assisted join armed");
                }
                final boolean joined = join_public_group_by_id(group_id_final, password_final, true);
                runOnUiThread(() ->
                {
                    join_in_progress = false;
                    if (join_progress != null)
                    {
                        join_progress.setVisibility(View.GONE);
                    }
                    groupid_text.setEnabled(true);
                    if (group_password_text != null)
                    {
                        group_password_text.setEnabled(true);
                    }

                    if (joined)
                    {
                        setResult(RESULT_OK, result_intent);
                        GroupMessageListActivity.show_messagelist_for_id(
                                JoinPublicGroupActivity.this, group_id_final, null);
                        finish();
                    }
                    else
                    {
                        validateGroupIdAndUpdateJoinButton();
                    }
                });
            }, "join-public-group").start();
            return;
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

    static void show_join_public_group_activity(Context c, final String ngc_group_pubkey)
    {
        Intent intent = new Intent(c, JoinPublicGroupActivity.class);
        intent.putExtra("ngc_group_pubkey", ngc_group_pubkey);
        c.startActivity(intent);
    }
}

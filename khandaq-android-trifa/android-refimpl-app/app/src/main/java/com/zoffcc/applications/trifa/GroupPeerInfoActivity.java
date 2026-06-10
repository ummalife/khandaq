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

import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.GroupPeerDB;

import java.util.Random;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.Toolbar;

import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.hash_to_bucket;
import static com.zoffcc.applications.trifa.HelperGeneric.long_date_time_format;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.get_group_peernum_from_peer_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.group_notification_silent_peer_get;
import static com.zoffcc.applications.trifa.HelperGroup.group_notification_silent_peer_set;
import static com.zoffcc.applications.trifa.HelperGroup.insert_into_group_message_db;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_name__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_groupmessagelist;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_peer_in_db;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.group_message_list_activity;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_mod_kick_peer;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_mod_set_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_private_packet;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_private_message_by_peerpubkey;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class GroupPeerInfoActivity extends AppCompatActivity
{
    static final String TAG = "trifa.GrpPeerInfoActy";
    de.hdodenhof.circleimageview.CircleImageView profile_icon = null;
    TextView peer_toxid = null;
    TextView peer_name = null;
    EditText group_send_private_message = null;
    String peer_pubkey = null;
    TextView group_peerrole_text = null;
    TextView peer_first_join_text = null;
    AppCompatButton group_kickpeer_button = null;
    TextView group_kickpeer_hint = null;
    AppCompatButton group_notification_silent_button = null;
    long group_num = -1;
    int peer_role = -1;
    int self_role = -1;
    boolean is_self_peer = false;
    AppCompatSpinner group_peerrole_select = null;
    String group_id = null;
    private String[] tox_ngc_group_role_items;
    private AppCompatButton group_peerrole_set_button = null;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_peer_info);

        Intent intent = getIntent();
        peer_pubkey = intent.getStringExtra("peer_pubkey");
        group_id = intent.getStringExtra("group_id");
        group_num = tox_group_by_groupid__wrapper(group_id);

        profile_icon = (de.hdodenhof.circleimageview.CircleImageView) findViewById(R.id.pi_profile_icon);
        peer_toxid = (TextView) findViewById(R.id.pi_toxprvkey_textview);
        peer_name = (TextView) findViewById(R.id.pi_nick_text);
        group_peerrole_text = (TextView) findViewById(R.id.group_peerrole_text);
        group_send_private_message = (EditText) findViewById(R.id.group_send_private_message);
        group_kickpeer_button = findViewById(R.id.group_kickpeer_button);
        group_kickpeer_hint = findViewById(R.id.group_kickpeer_hint);
        group_notification_silent_button = findViewById(R.id.group_notification_silent_button);
        group_peerrole_select = findViewById(R.id.group_peerrole_select);
        group_peerrole_set_button = findViewById(R.id.group_peerrole_set_button);
        peer_first_join_text = findViewById(R.id.peer_first_join_text);

        this.tox_ngc_group_role_items = new String[]{"---", "MODERATOR", "USER", "OBSERVER"};
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                                                                tox_ngc_group_role_items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        group_peerrole_select.setAdapter(adapter);

        group_peerrole_select.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
        {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
            {
                Log.i(TAG, "selected_new_role:" + parent.getItemAtPosition(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent)
            {

            }
        });

        group_peerrole_set_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                try
                {
                    String role_str = (String) group_peerrole_select.getSelectedItem();
                    int new_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value;
                    if (role_str.equals("MODERATOR"))
                    {
                        new_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value;
                    }
                    else if (role_str.equals("USER"))
                    {
                        new_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value;
                    }
                    else if (role_str.equals("OBSERVER"))
                    {
                        new_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
                    }
                    else
                    {
                        // nothing valid selected
                        return;
                    }

                    int result = tox_group_mod_set_role(group_num,
                                                        get_group_peernum_from_peer_pubkey(group_id, peer_pubkey),
                                                        new_role);
                    Log.i(TAG, "setting new role to: " + new_role + " result=" + result);
                    update_savedata_file_wrapper();

                    if (result == 1)
                    {
                        update_group_peer_in_db(group_num, group_id,
                                                get_group_peernum_from_peer_pubkey(group_id, peer_pubkey),
                                                peer_pubkey, new_role);
                        update_group_in_groupmessagelist(group_id);
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        });

        updateNotificationSilentButtonText();
        group_notification_silent_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                try
                {
                    final boolean currently_silent = group_notification_silent_peer_get(group_id, peer_pubkey);
                    group_notification_silent_peer_set(group_id, peer_pubkey, !currently_silent);
                    updateNotificationSilentButtonText();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });


        group_kickpeer_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                if (!canKickPeer(self_role, peer_role, is_self_peer))
                {
                    showKickDisabledHint();
                    return;
                }

                final String peer_display_name = peer_name.getText().toString();
                new AlertDialog.Builder(GroupPeerInfoActivity.this)
                        .setTitle(R.string.group_peer_kick_confirm_title)
                        .setMessage(getString(R.string.group_peer_kick_confirm_message, peer_display_name))
                        .setPositiveButton(R.string.layout___delete, new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialog, int which)
                            {
                                kickPeerFromGroup(peer_display_name);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.enableUpNavigation(this, toolbar);

        final Drawable d1 = new IconicsDrawable(this).icon(GoogleMaterial.Icon.gmd_face).color(
                getResources().getColor(R.color.colorPrimaryDark)).sizeDp(200);
        profile_icon.setImageDrawable(d1);

        String peer_name_txt = tox_group_peer_get_name__wrapper(group_id, peer_pubkey);

        if ((peer_name_txt == null) || (peer_name_txt.equals("")) || (peer_name_txt.equals("-1")))
        {
            peer_name_txt = "Unknown";
        }

        peer_toxid.setText(peer_pubkey);
        peer_name.setText(peer_name_txt);

        peer_first_join_text.setText("unknown");
        try
        {
           GroupPeerDB peer_from_db = (GroupPeerDB) orma.selectFromGroupPeerDB().group_identifierEq(group_id).
                    tox_group_peer_pubkeyEq(peer_pubkey).toList().get(0);
            peer_first_join_text.setText(long_date_time_format(peer_from_db.first_join_timestamp));
        }
        catch (Exception e)
        {
        }

        try
        {
            final String self_pubkey = tox_group_self_get_public_key(group_num);
            is_self_peer = (peer_pubkey != null) && (self_pubkey != null)
                           && peer_pubkey.equalsIgnoreCase(self_pubkey);
            self_role = tox_group_self_get_role(group_num);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            peer_role = tox_group_peer_get_role(group_num,
                                                get_group_peernum_from_peer_pubkey(group_id, peer_pubkey));
            group_peerrole_text.setText(ToxVars.Tox_Group_Role.value_str(peer_role));
            if (peer_role == 1)
            {
                group_peerrole_select.setSelection(1);
            }
            else if (peer_role == 2)
            {
                group_peerrole_select.setSelection(2);
            }
            else if (peer_role == 3)
            {
                group_peerrole_select.setSelection(3);
            }
            else
            {
                group_peerrole_select.setSelection(0);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            try
            {
                group_peerrole_select.setSelection(0);
            }
            catch (Exception e2)
            {
            }
        }

        updateKickPeerButtonState();

        try
        {
            int peer_color_fg = getResources().getColor(R.color.colorPrimaryDark);
            int peer_color_bg = ChatColors.get_shade(
                    ChatColors.PeerAvatarColors[hash_to_bucket(peer_pubkey, ChatColors.get_size())], peer_pubkey);

            final Drawable smiley_face = new IconicsDrawable(context_s).icon(
                    GoogleMaterial.Icon.gmd_sentiment_satisfied).backgroundColor(Color.TRANSPARENT).color(
                    peer_color_fg).sizeDp(70);

            profile_icon.setPadding((int) dp2px(0), (int) dp2px(0), (int) dp2px(0), (int) dp2px(0));
            profile_icon.setImageDrawable(smiley_face);

            // we need to do the rounded corner background manually here, to change the color ---------------
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(peer_color_bg);
            profile_icon.setBackground(shape);
            // we need to do the rounded corner background manually here, to change the color ---------------

        }
        catch (Exception e2)
        {
            e2.printStackTrace();
        }
    }

    @Override
    protected void onPause()
    {
        super.onPause();

        try
        {
            String private_message_text = group_send_private_message.getText().toString();
            if (private_message_text != null)
            {
                if (private_message_text.length() > 0)
                {
                    int res = tox_group_send_private_message_by_peerpubkey(tox_group_by_groupid__wrapper(group_id),
                                                                           peer_pubkey, 0, private_message_text);
                    Log.i(TAG, "onPause:tox_group_send_private_message_by_peerpubkey:res=" + res);

                    if (res == 0)
                    {
                        GroupMessage m = new GroupMessage();
                        m.is_new = false; // own messages are always "not new"
                        m.tox_group_peer_pubkey = tox_group_self_get_public_key(
                                tox_group_by_groupid__wrapper(group_id)).toUpperCase();
                        m.direction = 1; // msg sent
                        m.TOX_MESSAGE_TYPE = 0;
                        m.read = true; // !!!! there is not "read status" with conferences in Tox !!!!
                        m.tox_group_peername = null;
                        m.sent_privately_to_tox_group_peer_pubkey = peer_pubkey;
                        m.private_message = 1;
                        m.group_identifier = group_id;
                        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
                        m.sent_timestamp = System.currentTimeMillis();
                        m.rcvd_timestamp = System.currentTimeMillis(); // since we do not have anything better assume "now"
                        m.text = private_message_text;
                        m.was_synced = false;
                        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;

                        insert_into_group_message_db(m, true);
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        updateNotificationSilentButtonText();
    }

    private void updateNotificationSilentButtonText()
    {
        if (group_notification_silent_peer_get(group_id, peer_pubkey))
        {
            group_notification_silent_button.setText("activate Notifications for Peer");
        }
        else
        {
            group_notification_silent_button.setText("mute Notifications for Peer");
        }
    }

    private static boolean canKickPeer(final int selfRole, final int peerRole, final boolean isSelfPeer)
    {
        if (isSelfPeer)
        {
            return false;
        }

        if (peerRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value)
        {
            return false;
        }

        if (selfRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value)
        {
            return true;
        }

        if (selfRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value)
        {
            return peerRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_USER.value
                   || peerRole == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
        }

        return false;
    }

    private String getKickDisabledHintText()
    {
        if (is_self_peer)
        {
            return getString(R.string.group_peer_kick_hint_cannot_self);
        }

        if (peer_role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value)
        {
            return getString(R.string.group_peer_kick_hint_cannot_founder);
        }

        if (self_role != ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value
            && self_role != ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value)
        {
            return getString(R.string.group_peer_kick_hint_no_permission);
        }

        if (self_role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value
            && peer_role == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_MODERATOR.value)
        {
            return getString(R.string.group_peer_kick_hint_moderator_limit);
        }

        return getString(R.string.group_peer_kick_hint_no_permission);
    }

    private void updateKickPeerButtonState()
    {
        final boolean kickAllowed = canKickPeer(self_role, peer_role, is_self_peer);
        group_kickpeer_button.setEnabled(kickAllowed);
        group_kickpeer_button.setAlpha(kickAllowed ? 1.0f : 0.45f);

        if (kickAllowed)
        {
            group_kickpeer_hint.setVisibility(View.GONE);
        }
        else
        {
            group_kickpeer_hint.setText(getKickDisabledHintText());
            group_kickpeer_hint.setVisibility(View.VISIBLE);
        }
    }

    private void showKickDisabledHint()
    {
        Toast.makeText(this, getKickDisabledHintText(), Toast.LENGTH_LONG).show();
    }

    private void kickPeerFromGroup(final String peerDisplayName)
    {
        try
        {
            final long peer_num = get_group_peernum_from_peer_pubkey(group_id, peer_pubkey);
            if (peer_num < 0)
            {
                Toast.makeText(this, R.string.group_peer_kick_failed, Toast.LENGTH_LONG).show();
                return;
            }

            final int result = tox_group_mod_kick_peer(group_num, peer_num);
            Log.i(TAG, "kicking peer. result=" + result);
            update_savedata_file_wrapper();

            if (result >= 0)
            {
                peer_role = ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value;
                group_peerrole_text.setText(ToxVars.Tox_Group_Role.value_str(peer_role));
                group_peerrole_select.setSelection(3);
                update_group_peer_in_db(group_num, group_id, peer_num, peer_pubkey, peer_role);
                update_group_in_groupmessagelist(group_id);
                updateKickPeerButtonState();
                Toast.makeText(this, getString(R.string.group_peer_kick_success, peerDisplayName),
                               Toast.LENGTH_LONG).show();
            }
            else
            {
                Toast.makeText(this, R.string.group_peer_kick_failed, Toast.LENGTH_LONG).show();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Toast.makeText(this, R.string.group_peer_kick_failed, Toast.LENGTH_LONG).show();
        }
    }
}

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

import org.khandaq.messenger.BuildConfig;
import org.khandaq.messenger.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import android.widget.Toast;

import com.zoffcc.applications.sorm.GroupPeerDB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.CameraWrapper.YUV420rotate90;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.update_savedata_file_wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.clear_group_group_we_left;
import static com.zoffcc.applications.trifa.HelperGroup.ensure_group_in_tox;
import static com.zoffcc.applications.trifa.HelperGroup.collect_group_members_for_display;
import static com.zoffcc.applications.trifa.HelperGroup.format_group_list_status_subtitle;
import static com.zoffcc.applications.trifa.HelperGroup.get_display_peer_limit;
import static com.zoffcc.applications.trifa.HelperGroup.get_effective_group_title;
import static com.zoffcc.applications.trifa.HelperGroup.humanize_group_connection_status;
import static com.zoffcc.applications.trifa.HelperGroup.humanize_group_privacy;
import static com.zoffcc.applications.trifa.HelperGroup.humanize_group_role;
import static com.zoffcc.applications.trifa.HelperGroup.sanitize_group_peer_name;
import static com.zoffcc.applications.trifa.HelperGroup.save_group_title_if_changed;
import static com.zoffcc.applications.trifa.HelperGroup.sanitize_group_topic;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_db_privacy_state;
import static com.zoffcc.applications.trifa.HelperGroup.get_group_peernum_from_peer_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.is_group_we_left;
import static com.zoffcc.applications.trifa.HelperGroup.set_group_group_we_left;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupnum__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_groupmessagelist;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_peer_in_db;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_ENC_FILES_EXPORT_DIR;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_founder_set_password__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_founder_set_privacy_state__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_founder_set_topic_lock__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_get_topic_lock__wrapper;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_founder_set_peer_limit;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_founder_set_voice_state;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_get_voice_state;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_is_connected;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_mod_set_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_offline_peer_count;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_count;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_name;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_reconnect;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_savedpeer_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_peer_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_set_name;
import static com.zoffcc.applications.trifa.TRIFAGlobals.NGC_NEW_PEERS_TIMEDELTA_IN_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.ToxVars.GC_MAX_SAVED_PEERS;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class GroupInfoActivity extends AppCompatActivity
{
    static final String TAG = "trifa.GrpInfoActy";
    TextView this_group_id = null;
    EditText this_title = null;
    EditText group_myname_text = null;
    EditText peer_limit_text = null;
    TextView this_privacy_status_text = null;
    TextView group_connection_status_text = null;
    TextView group_myrole_text = null;
    AppCompatButton group_copy_id_button = null;
    static TextView group_num_msgs_text = null;
    static TextView group_num_system_msgs_text = null;
    Button group_reconnect_button = null;
    Button group_dumpofflinepeers_button = null;
    Button group_del_sysmsgs_button = null;
    AppCompatSpinner group_voicestate_select = null;
    private AppCompatButton group_voicestate_set_button = null;
    EditText group_password_text = null;
    Button group_password_set_button = null;
    SwitchCompat group_topic_lock_switch = null;
    AppCompatSpinner group_privacy_select = null;
    private AppCompatButton group_privacy_set_button = null;
    private String[] tox_ngc_group_voicestate_items;
    private String[] tox_ngc_group_privacy_items;
    private boolean group_topic_lock_switch_programmatic = false;
    String group_id = "-1";
    TextView group_info_summary_text = null;
    TextView group_info_header_title = null;
    TextView group_info_header_subtitle = null;
    TextView group_info_header_status = null;
    TextView group_info_members_header = null;
    TextView group_info_members_empty = null;
    View group_info_settings_section = null;
    View group_info_founder_section = null;
    View group_info_full_id_section = null;
    View group_info_peer_limit_section = null;
    RecyclerView group_info_members_list = null;
    GroupInfoMembersAdapter group_info_members_adapter = null;
    private String group_chat_id_for_copy = "";

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_groupinfo);

        Intent intent = getIntent();
        group_id = intent.getStringExtra("group_id");

        this_group_id = (TextView) findViewById(R.id.group_id_text);
        group_copy_id_button = findViewById(R.id.group_copy_id_button);
        this_title = (EditText) findViewById(R.id.group_name_text);
        group_myname_text = (EditText) findViewById(R.id.group_myname_text);
        peer_limit_text = (EditText) findViewById(R.id.peer_limit_text);
        this_privacy_status_text = (TextView) findViewById(R.id.group_privacy_status_text);
        group_connection_status_text = (TextView) findViewById(R.id.group_connection_status_text);
        group_myrole_text = (TextView) findViewById(R.id.group_myrole_text);
        group_voicestate_select = findViewById(R.id.group_voicestate_select);
        group_voicestate_set_button = findViewById(R.id.group_voicestate_set_button);
        group_password_text = findViewById(R.id.group_password_text);
        group_password_set_button = findViewById(R.id.group_password_set_button);
        group_topic_lock_switch = findViewById(R.id.group_topic_lock_switch);
        group_privacy_select = findViewById(R.id.group_privacy_select);
        group_privacy_set_button = findViewById(R.id.group_privacy_set_button);
        group_info_summary_text = findViewById(R.id.group_info_summary_text);
        group_info_header_title = findViewById(R.id.group_info_header_title);
        group_info_header_subtitle = findViewById(R.id.group_info_header_subtitle);
        group_info_header_status = findViewById(R.id.group_info_header_status);
        group_info_members_header = findViewById(R.id.group_info_members_header);
        group_info_members_empty = findViewById(R.id.group_info_members_empty);
        group_info_settings_section = findViewById(R.id.group_info_settings_section);
        group_info_founder_section = findViewById(R.id.group_info_founder_section);
        group_info_full_id_section = findViewById(R.id.group_info_full_id_section);
        // KHANDAQ: hide the group ID / chat-id section. Joining a group by this ID needs UDP peer
        // discovery, which is blocked on the target networks, so members are only ever added manually
        // via friend-invite. Showing a non-functional ID just confuses users.
        if (group_info_full_id_section != null)
        {
            group_info_full_id_section.setVisibility(View.GONE);
        }
        group_info_peer_limit_section = findViewById(R.id.group_info_peer_limit_section);
        group_info_members_list = findViewById(R.id.group_info_members_list);

        if (group_info_members_list != null)
        {
            group_info_members_list.setLayoutManager(new LinearLayoutManager(this));
            group_info_members_adapter = new GroupInfoMembersAdapter(this, group_id);
            group_info_members_list.setAdapter(group_info_members_adapter);
        }

        configure_release_ui();

        this.tox_ngc_group_voicestate_items = new String[]{"---", "FOUNDER", "MODERATOR", "ALL"};
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                                                                tox_ngc_group_voicestate_items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        group_voicestate_select.setAdapter(adapter);

        int current_voicestate = 0;
        try
        {
            current_voicestate = tox_group_get_voice_state(tox_group_by_groupid__wrapper(group_id));
            HelperGeneric.logI(TAG, "current_voicestate:" + current_voicestate);
            if (current_voicestate == ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_FOUNDER.value)
            {
                group_voicestate_select.setSelection(1);
            }
            else if (current_voicestate == ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_MODERATOR.value)
            {
                group_voicestate_select.setSelection(2);
            }
            else if (current_voicestate == ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_ALL.value)
            {
                group_voicestate_select.setSelection(3);
            }
            else
            {
                // nothing valid selected
                group_voicestate_select.setSelection(0);
            }
        }
        catch(Exception e)
        {
        }

        group_voicestate_select.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
        {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
            {
                HelperGeneric.logI(TAG, "selected_new_voicestate:" + parent.getItemAtPosition(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent)
            {

            }
        });

        group_voicestate_set_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                try
                {
                    String role_str = (String) group_voicestate_select.getSelectedItem();
                    int new_role = ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_ALL.value;
                    if (role_str.equals("FOUNDER"))
                    {
                        new_role = ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_FOUNDER.value;
                    }
                    else if (role_str.equals("MODERATOR"))
                    {
                        new_role = ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_MODERATOR.value;
                    }
                    else if (role_str.equals("ALL"))
                    {
                        new_role = ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_ALL.value;
                    }
                    else
                    {
                        // nothing valid selected
                        return;
                    }

                    int result = tox_group_founder_set_voice_state(tox_group_by_groupid__wrapper(group_id), new_role);
                    HelperGeneric.logI(TAG, "setting new voicestate to: " + new_role + " result=" + result);
                    update_savedata_file_wrapper();
                }
                catch (Exception ignored)
                {
                }
            }
        });

        this.tox_ngc_group_privacy_items = new String[]{"---", "PUBLIC", "PRIVATE"};
        if (group_privacy_select != null)
        {
            final ArrayAdapter<CharSequence> privacyAdapter = new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, tox_ngc_group_privacy_items);
            privacyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            group_privacy_select.setAdapter(privacyAdapter);
        }

        if (group_privacy_set_button != null)
        {
            group_privacy_set_button.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View view)
                {
                    try
                    {
                        final String privacy_str = (String) group_privacy_select.getSelectedItem();
                        int new_privacy = -1;
                        if ("PUBLIC".equals(privacy_str))
                        {
                            new_privacy = ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value;
                        }
                        else if ("PRIVATE".equals(privacy_str))
                        {
                            new_privacy = ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value;
                        }
                        else
                        {
                            return;
                        }

                        final long group_num = tox_group_by_groupid__wrapper(group_id);
                        final int result = tox_group_founder_set_privacy_state__wrapper(group_num, new_privacy);
                        if (result >= 0)
                        {
                            update_group_in_db_privacy_state(group_id, new_privacy);
                            this_privacy_status_text.setText(humanize_group_privacy(GroupInfoActivity.this, new_privacy));
                            update_savedata_file_wrapper();
                            apply_user_facing_group_info(group_num);
                            Toast.makeText(GroupInfoActivity.this, getString(R.string.group_privacy_set), Toast.LENGTH_SHORT).show();
                        }
                        else
                        {
                            Toast.makeText(GroupInfoActivity.this, getString(R.string.group_title_save_failed_toast), Toast.LENGTH_SHORT).show();
                        }
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            });
        }

        if (group_password_set_button != null)
        {
            group_password_set_button.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View view)
                {
                    try
                    {
                        final long group_num = tox_group_by_groupid__wrapper(group_id);
                        String password = null;
                        if (group_password_text != null && group_password_text.getText() != null)
                        {
                            password = group_password_text.getText().toString();
                        }
                        final int result = tox_group_founder_set_password__wrapper(group_num,
                                (password == null || password.isEmpty()) ? null : password);
                        if (result >= 0)
                        {
                            update_savedata_file_wrapper();
                            Toast.makeText(GroupInfoActivity.this, getString(R.string.group_password_set), Toast.LENGTH_SHORT).show();
                        }
                        else
                        {
                            Toast.makeText(GroupInfoActivity.this, getString(R.string.group_title_save_failed_toast), Toast.LENGTH_SHORT).show();
                        }
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            });
        }

        if (group_topic_lock_switch != null)
        {
            group_topic_lock_switch.setOnCheckedChangeListener((buttonView, isChecked) ->
            {
                if (group_topic_lock_switch_programmatic)
                {
                    return;
                }
                try
                {
                    final long group_num = tox_group_by_groupid__wrapper(group_id);
                    final int lock_state = isChecked
                            ? ToxVars.Tox_Group_Topic_Lock.TOX_GROUP_TOPIC_LOCK_ENABLED.value
                            : ToxVars.Tox_Group_Topic_Lock.TOX_GROUP_TOPIC_LOCK_DISABLED.value;
                    final int result = tox_group_founder_set_topic_lock__wrapper(group_num, lock_state);
                    if (result >= 0)
                    {
                        update_savedata_file_wrapper();
                    }
                    else
                    {
                        group_topic_lock_switch_programmatic = true;
                        group_topic_lock_switch.setChecked(!isChecked);
                        group_topic_lock_switch_programmatic = false;
                        Toast.makeText(GroupInfoActivity.this, getString(R.string.group_title_save_failed_toast), Toast.LENGTH_SHORT).show();
                    }
                }
                catch (Exception ignored)
                {
                }
            });
        }

        try
        {
            update_reconnect_button_visibility(false);
        }
        catch(Exception ignored)
        {
        }

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.enableUpNavigation(this, toolbar);
        if (getSupportActionBar() != null)
        {
            getSupportActionBar().setTitle(getString(R.string.title_activity_groupinfo));
        }

        this_title.setText("*error*");

        long group_num = -1;

        try
        {
            group_num = tox_group_by_groupid__wrapper(group_id);
            if (group_num < 0)
            {
                group_num = ensure_group_in_tox(group_id);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        populate_group_chat_id_text(group_num);

        try
        {
            peer_limit_text.setText("" + get_display_peer_limit(group_num));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            group_myname_text.setText(tox_group_peer_get_name(group_num, tox_group_self_get_peer_id(group_num)));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            this_title.setText(get_effective_group_title(group_num, group_id));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (group_copy_id_button != null)
        {
            group_copy_id_button.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(final View v)
                {
                    copy_group_chat_id_to_clipboard(v);
                }
            });
            // Long-press shares a richer invite (group id + my Tox ID + how-to) so that a joiner on a
            // UDP-blocked network can add me as a contact and be invited in via the friend path.
            group_copy_id_button.setOnLongClickListener(new View.OnLongClickListener()
            {
                @Override
                public boolean onLongClick(final View v)
                {
                    share_group_invite(v);
                    return true;
                }
            });
        }

        int privacy_state = -1;
        try
        {
            privacy_state = orma.selectFromGroupDB().
                    group_identifierEq(group_id.toLowerCase()).
                    toList().get(0).privacy_state;
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        this_privacy_status_text.setText(humanize_group_privacy(this, privacy_state));

        if (group_privacy_select != null)
        {
            if (privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value)
            {
                group_privacy_select.setSelection(1);
            }
            else if (privacy_state == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value)
            {
                group_privacy_select.setSelection(2);
            }
            else
            {
                group_privacy_select.setSelection(0);
            }
        }

        if (group_topic_lock_switch != null && group_num >= 0)
        {
            try
            {
                final int topic_lock = tox_group_get_topic_lock__wrapper(group_num);
                if (topic_lock >= 0)
                {
                    group_topic_lock_switch_programmatic = true;
                    group_topic_lock_switch.setChecked(
                            topic_lock == ToxVars.Tox_Group_Topic_Lock.TOX_GROUP_TOPIC_LOCK_ENABLED.value);
                    group_topic_lock_switch_programmatic = false;
                }
                else
                {
                    group_topic_lock_switch.setVisibility(View.GONE);
                }
            }
            catch (Exception ignored)
            {
                group_topic_lock_switch.setVisibility(View.GONE);
            }
        }

        group_update_connected_status_on_groupinfo(group_num);

        final long group_num_ = group_num;
        if (group_reconnect_button != null)
        {
            group_reconnect_button.setOnClickListener(new View.OnClickListener()
            {
                @Override
                public void onClick(View v)
                {
                    try
                    {
                        long active_group_num = group_num_;
                        if (active_group_num < 0)
                        {
                            active_group_num = ensure_group_in_tox(group_id);
                        }
                        if (active_group_num >= 0)
                        {
                            tox_group_reconnect(active_group_num);
                            update_savedata_file_wrapper();
                            clear_group_group_we_left(group_id);
                            group_update_connected_status_on_groupinfo(active_group_num);
                            peer_limit_text.setText("" + get_display_peer_limit(active_group_num));
                        }
                        else
                        {
                            Toast.makeText(GroupInfoActivity.this,
                                           getString(R.string.group_send_group_not_found), Toast.LENGTH_LONG).show();
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            });
        }


        if (group_dumpofflinepeers_button != null)
        {
            group_dumpofflinepeers_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                try
                {
                    long offline_num_peers = tox_group_offline_peer_count(group_num_);

                    if (offline_num_peers > 0)
                    {
                        List<GroupMessageListActivity.group_list_peer> group_peers_offline = new ArrayList<>();
                        long i = 0;
                        for (i = 0; i < GC_MAX_SAVED_PEERS; i++)
                        {
                            try
                            {
                                String peer_pubkey_temp = tox_group_savedpeer_get_public_key(group_num_, i);
                                if (peer_pubkey_temp.compareToIgnoreCase("-1") == 0)
                                {
                                    continue;
                                }
                                String peer_name = "zzzzzoffline " + i;
                                GroupPeerDB peer_from_db = null;
                                try
                                {
                                    peer_from_db = (GroupPeerDB) orma.selectFromGroupPeerDB().group_identifierEq(
                                            group_id).tox_group_peer_pubkeyEq(peer_pubkey_temp).toList().get(0);
                                }
                                catch (Exception e)
                                {
                                }

                                String peerrole = "";

                                if (peer_from_db != null)
                                {
                                    peer_name = peer_from_db.peer_name;
                                    if ((peer_from_db.first_join_timestamp + NGC_NEW_PEERS_TIMEDELTA_IN_MS) >
                                        System.currentTimeMillis())
                                    {
                                        peer_name = "_NEW_ " + peer_name;
                                    }
                                    peerrole = ToxVars.Tox_Group_Role.value_char(peer_from_db.Tox_Group_Role) + " ";
                                }

                                // HelperGeneric.logI(TAG, "groupnum=" + conference_num + " peernum=" + offline_peers[(int) i] + " peer_name=" +
                                //           peer_name);
                                String peer_name_temp =  peerrole + peer_name + " :" + i + ": " + peer_pubkey_temp.substring(0, 6);

                                GroupMessageListActivity.group_list_peer glp3 = new GroupMessageListActivity.group_list_peer();
                                glp3.peer_pubkey = peer_pubkey_temp;
                                glp3.peer_num = i;
                                glp3.peer_name = peer_name_temp;
                                glp3.peer_connection_status = ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value;
                                group_peers_offline.add(glp3);
                            }
                            catch (Exception ignored)
                            {
                            }
                        }

                        try
                        {
                            Collections.sort(group_peers_offline, new Comparator<GroupMessageListActivity.group_list_peer>()
                            {
                                @Override
                                public int compare(GroupMessageListActivity.group_list_peer p1, GroupMessageListActivity.group_list_peer p2)
                                {
                                    String name1 = p1.peer_pubkey;
                                    String name2 = p2.peer_pubkey;
                                    return name1.compareToIgnoreCase(name2);
                                }
                            });
                        }
                        catch (Exception ignored)
                        {
                        }

                        StringBuilder logstr = new StringBuilder();
                        for (GroupMessageListActivity.group_list_peer peerloffline : group_peers_offline)
                        {
                            logstr.append(peerloffline.peer_pubkey).append(":").append(peerloffline.peer_num).append("\n");
                        }
                        HelperGeneric.logI(TAG, "\n\nNGC_GROUP_OFFLINE_PEERLIST:\n" + logstr + "\n\n");

                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });
        }

        if (group_del_sysmsgs_button != null)
        {
            group_del_sysmsgs_button.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                try
                {
                    AlertDialog.Builder builder = new AlertDialog.Builder(v.getContext());
                    builder.setTitle(getString(R.string.group_delete_system_msgs_title));
                    builder.setMessage(getString(R.string.group_delete_system_msgs_message));

                    builder.setPositiveButton(getString(R.string.group_delete_system_msgs_confirm), new DialogInterface.OnClickListener()
                    {
                        public void onClick(DialogInterface dialog, int id)
                        {
                            try
                            {
                                final Thread t2 = new Thread()
                                {
                                    @Override
                                    public void run()
                                    {
                                        try
                                        {
                                            HelperGeneric.logI(TAG, "del_group_system_messages:START:");
                                            display_toast(getString(R.string.group_delete_system_msgs_progress), true, 0);
                                            orma.deleteFromGroupMessage().
                                                    group_identifierEq(group_id).
                                                    tox_group_peer_pubkeyEq(TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY).
                                                    execute();
                                            HelperGeneric.logI(TAG, "del_group_system_messages:DONE:");
                                            display_toast(getString(R.string.group_delete_system_msgs_done), true, 0);
                                            reload_message_counts(group_id);
                                        }
                                        catch (Exception e2)
                                        {
                                            e2.printStackTrace();
                                            HelperGeneric.logI(TAG, "del_group_system_messages:EE:" + e2.getMessage());
                                        }
                                    }
                                };
                                t2.start();
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    });
                    builder.setNegativeButton(getString(R.string.cancel), null);

                    AlertDialog dialog = builder.create();
                    dialog.show();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });
        }

        try
        {
            final int myrole = tox_group_self_get_role(group_num);
            if (group_myrole_text != null)
            {
                group_myrole_text.setText(humanize_group_role(this, myrole));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        apply_user_facing_group_info(group_num);
        reload_group_members_list();
    }

    private void group_update_connected_status_on_groupinfo(final long group_num)
    {
        try
        {
            if (group_num < 0)
            {
                if (group_connection_status_text != null)
                {
                    group_connection_status_text.setText(getString(R.string.group_info_connection_disconnected));
                }
                update_reconnect_button_visibility(true);
                apply_user_facing_group_info(group_num);
                return;
            }

            final int is_connected = tox_group_is_connected(group_num);
            if (is_group_we_left(group_id))
            {
                if (group_connection_status_text != null)
                {
                    group_connection_status_text.setText(getString(R.string.group_info_connection_disconnected));
                }
                update_reconnect_button_visibility(true);
            }
            else
            {
                if (group_connection_status_text != null)
                {
                    group_connection_status_text.setText(humanize_group_connection_status(this, is_connected, group_id));
                }
                update_reconnect_button_visibility(
                        is_connected != TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value);
            }
            apply_user_facing_group_info(group_num);
        }
        catch(Exception ignored)
        {
        }
    }

    private void update_reconnect_button_visibility(final boolean show)
    {
        if (group_reconnect_button == null)
        {
            return;
        }
        if (!BuildConfig.DEBUG)
        {
            group_reconnect_button.setVisibility(View.GONE);
            return;
        }
        group_reconnect_button.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    static void reload_message_counts(final String group_id)
    {
        try
        {
            Thread t = new Thread()
            {
                @Override
                public void run()
                {
                    String num_str1 = "*ERROR*";
                    String num_str2 = "*ERROR*";
                    try
                    {
                        num_str1 = "" + orma.selectFromGroupMessage().group_identifierEq(group_id).tox_group_peer_pubkeyNotEq(TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY).count();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    try
                    {
                        num_str2 = "" + orma.selectFromGroupMessage().group_identifierEq(group_id).tox_group_peer_pubkeyEq(
                                TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY).count();
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    final String num_str_1 = num_str1;
                    final String num_str_2 = num_str2;

                    Runnable myRunnable = new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                if (group_num_msgs_text == null && group_num_system_msgs_text == null)
                                {
                                    return;
                                }
                                try
                                {
                                    if (group_num_msgs_text != null)
                                    {
                                        group_num_msgs_text.setText(context_s.getString(R.string.group_info_message_count_user,
                                                Integer.parseInt(num_str_1)));
                                    }
                                }
                                catch (Exception ignored)
                                {
                                    if (group_num_msgs_text != null)
                                    {
                                        group_num_msgs_text.setText(num_str_1);
                                    }
                                }
                                try
                                {
                                    if (group_num_system_msgs_text != null)
                                    {
                                        group_num_system_msgs_text.setText(
                                                context_s.getString(R.string.group_info_system_message_count,
                                                        Integer.parseInt(num_str_2)));
                                    }
                                }
                                catch (Exception ignored)
                                {
                                    if (group_num_system_msgs_text != null)
                                    {
                                        group_num_system_msgs_text.setText(num_str_2);
                                    }
                                }
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
            };
            t.start();
        }
        catch(Exception e)
        {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        final long group_num = ensure_group_in_tox(group_id);
        if (group_num >= 0)
        {
            group_update_connected_status_on_groupinfo(group_num);
            peer_limit_text.setText("" + get_display_peer_limit(group_num));
            this_title.setText(get_effective_group_title(group_num, group_id));
            populate_group_chat_id_text(group_num);
        }
        reload_message_counts(group_id);
        apply_user_facing_group_info(group_num);
        reload_group_members_list();
    }

    private void populate_group_chat_id_text(final long group_num)
    {
        if (this_group_id == null)
        {
            return;
        }

        String chat_id = null;
        if (group_num >= 0)
        {
            try
            {
                chat_id = tox_group_by_groupnum__wrapper(group_num);
            }
            catch (Exception ignored)
            {
            }
        }
        if ((chat_id == null || chat_id.length() == 0) && group_id != null && !group_id.equals("-1"))
        {
            chat_id = group_id;
        }

        if (chat_id == null || chat_id.length() == 0)
        {
            group_chat_id_for_copy = "";
            this_group_id.setText("*error*");
            if (group_copy_id_button != null)
            {
                group_copy_id_button.setEnabled(false);
            }
            return;
        }

        group_chat_id_for_copy = chat_id.toUpperCase();
        this_group_id.setText(group_chat_id_for_copy.toLowerCase());
        if (group_copy_id_button != null)
        {
            group_copy_id_button.setEnabled(true);
        }
    }

    private void copy_group_chat_id_to_clipboard(final View v)
    {
        if (group_chat_id_for_copy == null || group_chat_id_for_copy.length() == 0)
        {
            return;
        }
        try
        {
            final ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null)
            {
                clipboard.setPrimaryClip(ClipData.newPlainText("", group_chat_id_for_copy));
                Toast.makeText(v.getContext(), getString(R.string.id_copied_to_clipboard), Toast.LENGTH_SHORT).show();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * Build and share a rich group invite: the public chat-id plus my own Tox ID and a short how-to.
     * This operationalizes the friend-assisted-join workaround at scale — a joiner whose network
     * blocks UDP peer discovery (so a plain chat-id join can never complete) can add the inviter as
     * a contact, after which the existing friend-invite path pulls them into the group over TCP.
     */
    private void share_group_invite(final View v)
    {
        if (group_chat_id_for_copy == null || group_chat_id_for_copy.length() == 0)
        {
            return;
        }
        try
        {
            String my_toxid = "";
            try
            {
                my_toxid = MainActivity.get_my_toxid();
            }
            catch (Exception ignored)
            {
            }
            if (my_toxid == null)
            {
                my_toxid = "";
            }
            final String invite_text = getString(R.string.group_share_invite_template,
                    group_chat_id_for_copy.toLowerCase(), my_toxid.toUpperCase());
            final android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(android.content.Intent.EXTRA_TEXT, invite_text);
            startActivity(android.content.Intent.createChooser(send,
                    getString(R.string.group_share_invite_chooser)));
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void configure_release_ui()
    {
        boolean founder = false;
        try
        {
            final long group_num = tox_group_by_groupid__wrapper(group_id);
            founder = tox_group_self_get_role(group_num) == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value;
        }
        catch (Exception ignored)
        {
        }
        final int founder_visibility = founder ? View.VISIBLE : View.GONE;
        final int extended_founder_visibility = (founder && HelperGroup.is_tox_group_extended_state_jni_supported())
                ? View.VISIBLE : View.GONE;

        if (group_info_founder_section != null)
        {
            group_info_founder_section.setVisibility(founder_visibility);
        }
        if (group_voicestate_select != null)
        {
            group_voicestate_select.setVisibility(founder_visibility);
        }
        if (group_voicestate_set_button != null)
        {
            group_voicestate_set_button.setVisibility(founder_visibility);
        }
        if (group_info_peer_limit_section != null)
        {
            group_info_peer_limit_section.setVisibility(founder_visibility);
        }
        if (group_password_text != null)
        {
            final View passwordField = (View) group_password_text.getParent();
            if (passwordField != null)
            {
                passwordField.setVisibility(extended_founder_visibility);
            }
        }
        if (group_password_set_button != null)
        {
            group_password_set_button.setVisibility(extended_founder_visibility);
        }
        if (group_topic_lock_switch != null)
        {
            group_topic_lock_switch.setVisibility(extended_founder_visibility);
        }
        if (group_privacy_select != null)
        {
            final View privacyRow = (View) group_privacy_select.getParent();
            if (privacyRow != null)
            {
                privacyRow.setVisibility(extended_founder_visibility);
            }
        }
    }

    private void apply_user_facing_group_info(final long group_num)
    {
        try
        {
            configure_release_ui();

            int privacy_state = -1;
            try
            {
                privacy_state = orma.selectFromGroupDB().
                        group_identifierEq(group_id.toLowerCase()).
                        toList().get(0).privacy_state;
            }
            catch (Exception ignored)
            {
            }

            final String title = get_effective_group_title(group_num, group_id);
            final String members = format_group_list_status_subtitle(this, group_id);
            final String privacy = humanize_group_privacy(this, privacy_state);
            final String connection = (group_connection_status_text != null)
                    ? group_connection_status_text.getText().toString()
                    : humanize_group_connection_status(this,
                    group_num >= 0 ? tox_group_is_connected(group_num) : -1, group_id);

            if (this_privacy_status_text != null)
            {
                this_privacy_status_text.setText(privacy);
            }
            if (group_info_header_title != null)
            {
                group_info_header_title.setText(title);
            }
            if (group_info_header_subtitle != null)
            {
                group_info_header_subtitle.setText(members);
            }
            if (group_info_header_status != null)
            {
                group_info_header_status.setText(privacy + " · " + connection);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void reload_group_members_list()
    {
        if (group_info_members_adapter == null)
        {
            return;
        }

        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    final List<HelperGroup.GroupMemberDisplay> members =
                            HelperGroup.dedupe_group_members_for_display(
                                    HelperGroup.collect_group_members_for_display(group_id));
                    if (main_handler_s == null)
                    {
                        return;
                    }
                    main_handler_s.post(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                group_info_members_adapter.setMembers(members);
                                if (group_info_members_header != null)
                                {
                                    // KHANDAQ (#21): use the same authoritative toxcore count as the
                                    // in-group header (peer_count + offline_peer_count) so the info
                                    // screen and the chat header never disagree. Falls back to the
                                    // rendered list size if toxcore has no count yet.
                                    final long[] auth = HelperGroup.count_authoritative_group_members(group_id);
                                    final long total = Math.max(auth[0] + auth[1], members.size());
                                    group_info_members_header.setText(
                                            getString(R.string.group_info_members_header, (int) total));
                                }
                                if (group_info_members_empty != null)
                                {
                                    group_info_members_empty.setVisibility(
                                            members.isEmpty() ? View.VISIBLE : View.GONE);
                                }
                                if (group_info_members_list != null)
                                {
                                    group_info_members_list.setVisibility(
                                            members.isEmpty() ? View.GONE : View.VISIBLE);
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    });
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    @Override
    protected void onPause()
    {
        super.onPause();

        final long group_num;
        try
        {
            group_num = tox_group_by_groupid__wrapper(group_id);
        }
        catch (Exception e)
        {
            return;
        }

        if (group_num < 0)
        {
            return;
        }

        try
        {
            if (this_title != null)
            {
                TrifaToxService.wakeup_tox_thread();
                final String new_title = sanitize_group_topic(this_title.getText().toString());
                if (new_title.length() > 0)
                {
                    final String current_title = get_effective_group_title(group_num, group_id);
                    if (!new_title.equals(current_title))
                    {
                        final boolean saved = save_group_title_if_changed(group_id, new_title);
                        final int toastRes = saved
                                ? R.string.group_title_saved_toast
                                : R.string.group_title_save_failed_toast;
                        // Application context: activity may already be finishing in onPause.
                        display_toast(getString(toastRes), false, 0);
                    }
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            if (group_myname_text != null)
            {
                String my_new_name = sanitize_group_peer_name(group_myname_text.getText().toString());
                if (my_new_name.length() > 0)
                {
                    tox_group_self_set_name(group_num, my_new_name);
                    update_savedata_file_wrapper();
                }
            }
        }
        catch (Exception ignored)
        {
        }

        try
        {
            if (tox_group_self_get_role(group_num) == ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_FOUNDER.value)
            {
                String new_peer_limit = peer_limit_text.getText().toString();
                if ((new_peer_limit != null) && (new_peer_limit.length() > 0))
                {
                    int parsed_limit = Integer.parseInt(new_peer_limit);
                    if (parsed_limit >= 1 && parsed_limit <= 65535)
                    {
                        tox_group_founder_set_peer_limit(group_num, parsed_limit);
                        update_savedata_file_wrapper();
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }
}

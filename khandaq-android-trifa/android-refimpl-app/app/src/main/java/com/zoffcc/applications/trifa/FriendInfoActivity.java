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

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zoffcc.applications.sorm.FriendList;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import static com.zoffcc.applications.trifa.HelperFriend.count_friend_messages;
import static com.zoffcc.applications.trifa.HelperFriend.get_friend_capabilities_from_pubkey;
import static com.zoffcc.applications.trifa.HelperFriend.lookup_friend_in_db;
import static com.zoffcc.applications.trifa.HelperFriend.main_get_friend;
import static com.zoffcc.applications.trifa.HelperFriend.resolve_friend_profile_name;
import static com.zoffcc.applications.trifa.HelperFriend.resolve_friend_profile_status;
import static com.zoffcc.applications.trifa.HelperFriend.resolve_friend_public_key;
import static com.zoffcc.applications.trifa.HelperFriend.sync_friend_profile_from_tox;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.darkenColor;
import static com.zoffcc.applications.trifa.HelperGeneric.is_nightmode_active;
import static com.zoffcc.applications.trifa.HelperRelay.get_pushurl_for_friend;
import static com.zoffcc.applications.trifa.HelperRelay.get_relay_for_friend;
import static com.zoffcc.applications.trifa.HelperRelay.is_valid_pushurl_for_friend_with_whitelist;
import static com.zoffcc.applications.trifa.HelperRelay.remove_friend_pushurl_in_db;
import static com.zoffcc.applications.trifa.HelperRelay.remove_friend_relay_in_db;
import static com.zoffcc.applications.trifa.MainActivity.friend_list_fragment;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CAPABILITY_DECODE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CAPABILITY_DECODE_TO_STRING;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class FriendInfoActivity extends AppCompatActivity
{
    static final String TAG = "trifa.FriendInfoActy";
    de.hdodenhof.circleimageview.CircleImageView profile_icon = null;
    TextView mytoxid = null;
    TextView mynick = null;
    TextView mystatus_message = null;
    EditText alias_text = null;
    TextView fi_relay_pubkey_textview = null;
    TextView fi_toxcapabilities_textview = null;
    TextView fi_relay_text = null;
    TextView friend_num_msgs_text = null;
    LinearLayout fi_message_stats_section = null;
    Button remove_friend_relay_button = null;
    TextView fi_pushurl_textview = null;
    TextView fi_pushurl_text = null;
    Button remove_friend_pushurl_button = null;
    String friend_pubkey = null;

    long friendnum = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_friendinfo);

        final Intent intent = getIntent();
        friendnum = intent.getLongExtra("friendnum", -1);
        friend_pubkey = resolve_friend_public_key(friendnum, intent.getStringExtra("friend_pubkey"));
        if (friendnum < 0 && friend_pubkey != null)
        {
            friendnum = tox_friend_by_public_key__wrapper(friend_pubkey);
        }

        profile_icon = (de.hdodenhof.circleimageview.CircleImageView) findViewById(R.id.fi_profile_icon);
        mytoxid = (TextView) findViewById(R.id.fi_toxprvkey_textview);
        mynick = (TextView) findViewById(R.id.fi_nick_text);
        mystatus_message = (TextView) findViewById(R.id.fi_status_message_text);
        alias_text = (EditText) findViewById(R.id.fi_alias_text);
        fi_relay_pubkey_textview = (TextView) findViewById(R.id.fi_relay_pubkey_textview);
        fi_relay_text = (TextView) findViewById(R.id.fi_relay_text);
        remove_friend_relay_button = (Button) findViewById(R.id.remove_friend_relay_button);

        // KHANDAQ (#56): open the 1:1 chat from the contact profile (parity with iOS).
        final Button fi_message_button = (Button) findViewById(R.id.fi_message_button);
        if (fi_message_button != null)
        {
            fi_message_button.setOnClickListener(v ->
            {
                if (friend_pubkey != null)
                {
                    FriendListHolder.show_messagelist_acticvity_for_friend(FriendInfoActivity.this, friend_pubkey);
                }
            });
        }

        fi_pushurl_textview = (TextView) findViewById(R.id.fi_pushurl_textview);
        fi_pushurl_text = (TextView) findViewById(R.id.fi_pushurl_text);
        remove_friend_pushurl_button = (Button) findViewById(R.id.remove_friend_pushurl_button);
        fi_toxcapabilities_textview = (TextView) findViewById(R.id.fi_toxcapabilities_textview);
        friend_num_msgs_text = (TextView) findViewById(R.id.friend_num_msgs_text);
        fi_message_stats_section = (LinearLayout) findViewById(R.id.fi_message_stats_section);

        final View ip_section = findViewById(R.id.fi_ipaddr_section);
        if (ip_section != null)
        {
            ip_section.setVisibility(View.GONE);
        }

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.enableUpNavigation(this, toolbar);

        // KHANDAQ (#F1): pad the toolbar down by the status-bar inset so the title ("Информация о
        // друге") isn't cramped under the status-bar clock (edge-to-edge on Android 15+ / some OEM
        // skins like Realme/MIUI). Same idiom as AvatarCropActivity.
        if (toolbar != null)
        {
            final int toolbar_pad_left = toolbar.getPaddingLeft();
            final int toolbar_pad_right = toolbar.getPaddingRight();
            final int toolbar_pad_bottom = toolbar.getPaddingBottom();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, wi) ->
            {
                final androidx.core.graphics.Insets sys =
                        wi.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
                v.setPadding(toolbar_pad_left, sys.top, toolbar_pad_right, toolbar_pad_bottom);
                return wi;
            });
        }

        alias_text.setText("");
        populate_friend_profile_from_cache();
        schedule_friend_profile_refresh();

        String msgv3_single_cap = "";

        try
        {
            final FriendList f = lookup_friend_in_db(friend_pubkey);

            if (f != null && f.msgv3_capability == 1)
            {
                msgv3_single_cap = " MSGV3-lite";
            }
        }
        catch (Exception e)
        {
        }

        if (friend_pubkey != null)
        {
            fi_toxcapabilities_textview.setText(TOX_CAPABILITY_DECODE_TO_STRING(TOX_CAPABILITY_DECODE(
                    get_friend_capabilities_from_pubkey(friend_pubkey))) +
                                                msgv3_single_cap);
        }

        String friend_relay_pubkey = friend_pubkey != null ? get_relay_for_friend(friend_pubkey) : null;

        fi_relay_pubkey_textview.setText("");

        try
        {
            if (friend_relay_pubkey == null)
            {
                fi_relay_text.setVisibility(View.GONE);
                fi_relay_pubkey_textview.setVisibility(View.GONE);
                remove_friend_relay_button.setVisibility(View.GONE);
            }
            else
            {
                fi_relay_text.setVisibility(View.VISIBLE);
                fi_relay_pubkey_textview.setVisibility(View.VISIBLE);
                fi_relay_pubkey_textview.setText(friend_relay_pubkey);

                remove_friend_relay_button.setText("remove Friends Relay");
                remove_friend_relay_button.setOnClickListener(new View.OnClickListener()
                {
                    @Override
                    public void onClick(View v)
                    {
                        try
                        {
                            remove_friend_relay_in_db(friend_pubkey);
                            remove_friend_relay_button.setVisibility(View.GONE);
                            fi_relay_text.setVisibility(View.GONE);
                            fi_relay_pubkey_textview.setVisibility(View.GONE);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                        try
                        {
                            friend_list_fragment.add_all_friends_clear(0);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }

                    }
                });
                remove_friend_relay_button.setVisibility(View.VISIBLE);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        String pushurl_for_friend = friend_pubkey != null ? get_pushurl_for_friend(friend_pubkey) : null;

        fi_pushurl_textview.setText("");

        try
        {
            if (pushurl_for_friend == null)
            {
                fi_pushurl_text.setVisibility(View.GONE);
                fi_pushurl_textview.setVisibility(View.GONE);
                remove_friend_pushurl_button.setVisibility(View.GONE);
            }
            else
            {
                fi_pushurl_text.setVisibility(View.VISIBLE);
                fi_pushurl_textview.setVisibility(View.VISIBLE);
                fi_pushurl_textview.setText(pushurl_for_friend);

                boolean is_valid = false;
                if (pushurl_for_friend.length() > "https://".length())
                {
                    if (is_valid_pushurl_for_friend_with_whitelist(pushurl_for_friend))
                    {
                        is_valid = true;
                    }
                }

                if (!is_valid)
                {
                    Spannable spannable = new SpannableString(pushurl_for_friend + "\n" + "(*invalid*)");
                    spannable.setSpan(new ForegroundColorSpan(Color.RED), pushurl_for_friend.length(),
                                      (pushurl_for_friend + "\n" + "(*invalid*)").length(),
                                      Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    fi_pushurl_textview.setText(spannable, TextView.BufferType.SPANNABLE);
                }
                else
                {
                    Spannable spannable = new SpannableString(pushurl_for_friend + "\n" + "( OK )");
                    spannable.setSpan(new ForegroundColorSpan(darkenColor(Color.GREEN, 0.3f)),
                                      pushurl_for_friend.length(), (pushurl_for_friend + "\n" + "( OK )").length(),
                                      Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    fi_pushurl_textview.setText(spannable, TextView.BufferType.SPANNABLE);
                }

                remove_friend_pushurl_button.setText("remove Friend Push URL");
                remove_friend_pushurl_button.setOnClickListener(new View.OnClickListener()
                {
                    @Override
                    public void onClick(View v)
                    {
                        try
                        {
                            remove_friend_pushurl_in_db(friend_pubkey);
                            remove_friend_pushurl_button.setVisibility(View.GONE);
                            fi_pushurl_text.setVisibility(View.GONE);
                            fi_pushurl_textview.setVisibility(View.GONE);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                });
                remove_friend_pushurl_button.setVisibility(View.VISIBLE);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            FriendList f = lookup_friend_in_db(friend_pubkey);
            if (f == null && friendnum >= 0)
            {
                f = main_get_friend(friendnum);
            }
            if (f != null)
            {
                String displayName = f.name;
                if (f.alias_name != null && f.alias_name.length() > 0)
                {
                    displayName = f.alias_name;
                }
                ChatBubbleUiHelper.fill_profile_peer_avatar(this, friend_pubkey, displayName, profile_icon);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "EE2:" + e.getMessage());
        }

    }

    void populate_friend_profile_from_cache()
    {
        FriendList f = lookup_friend_in_db(friend_pubkey);
        if (f == null && friendnum >= 0)
        {
            f = main_get_friend(friendnum);
        }

        if (f != null && friend_pubkey == null)
        {
            friend_pubkey = f.tox_public_key_string;
        }

        apply_friend_profile_to_ui(f);
    }

    void apply_friend_profile_to_ui(final FriendList f)
    {
        if (friend_pubkey != null)
        {
            String color_pkey = "<font color=\"#331bc5\">";
            String ec = "</font>";
            if (is_nightmode_active(getApplicationContext()))
            {
                color_pkey = "<font color=\"#8affffff\">";
            }
            mytoxid.setText(Html.fromHtml(color_pkey + friend_pubkey + ec));
        }
        else if (f != null && f.tox_public_key_string != null)
        {
            String color_pkey = "<font color=\"#331bc5\">";
            String ec = "</font>";
            if (is_nightmode_active(getApplicationContext()))
            {
                color_pkey = "<font color=\"#8affffff\">";
            }
            mytoxid.setText(Html.fromHtml(color_pkey + f.tox_public_key_string + ec));
        }
        else
        {
            mytoxid.setText("—");
        }

        mynick.setText(resolve_friend_profile_name(f, friendnum));
        mystatus_message.setText(resolve_friend_profile_status(f, friendnum));

        try
        {
            if (f != null && f.alias_name != null)
            {
                alias_text.setText(f.alias_name);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    void schedule_friend_profile_refresh()
    {
        if (friend_pubkey == null && friendnum < 0)
        {
            return;
        }

        final long friendnum_local = friendnum;
        final String friend_pubkey_local = friend_pubkey;
        final Thread t = new Thread()
        {
            @Override
            public void run()
            {
                FriendList f = lookup_friend_in_db(friend_pubkey_local);
                if (f == null && friendnum_local >= 0)
                {
                    f = main_get_friend(friendnum_local);
                }
                if (f != null && friendnum_local >= 0)
                {
                    sync_friend_profile_from_tox(friendnum_local, f);
                }

                final FriendList f_final = f;
                Runnable myRunnable = new Runnable()
                {
                    @Override
                    public void run()
                    {
                        apply_friend_profile_to_ui(f_final);
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

    @Override
    protected void onResume()
    {
        super.onResume();
        populate_friend_profile_from_cache();
        schedule_friend_profile_refresh();
        refresh_friend_message_count();
    }

    void refresh_friend_message_count()
    {
        if (friend_pubkey == null || fi_message_stats_section == null)
        {
            return;
        }

        final String friend_pubkey_local = friend_pubkey;
        final Thread t = new Thread()
        {
            @Override
            public void run()
            {
                final long count = count_friend_messages(friend_pubkey_local);
                if (count < 0L)
                {
                    if (main_handler_s != null)
                    {
                        main_handler_s.post(() ->
                        {
                            if (fi_message_stats_section != null)
                            {
                                fi_message_stats_section.setVisibility(View.GONE);
                            }
                        });
                    }
                    return;
                }

                final String count_text = getString(R.string.friend_info_message_count, count);
                if (main_handler_s != null)
                {
                    main_handler_s.post(() ->
                    {
                        if (fi_message_stats_section != null)
                        {
                            fi_message_stats_section.setVisibility(View.VISIBLE);
                        }
                        if (friend_num_msgs_text != null)
                        {
                            friend_num_msgs_text.setText(count_text);
                        }
                    });
                }
            }
        };
        t.start();
    }

    @Override
    protected void onPause()
    {
        super.onPause();

        try
        {
            String alias_name = alias_text.getText().toString();
            if (friend_pubkey == null)
            {
                return;
            }
            if (alias_name != null)
            {
                if (alias_name.length() > 0)
                {
                    orma.updateFriendList().
                            tox_public_key_stringEq(friend_pubkey).
                            alias_name(alias_name).execute();
                }
                else
                {
                    orma.updateFriendList().
                            tox_public_key_stringEq(friend_pubkey).
                            alias_name("").execute();
                }
            }
            else
            {
                orma.updateFriendList().
                        tox_public_key_stringEq(friend_pubkey).
                        alias_name("").execute();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}

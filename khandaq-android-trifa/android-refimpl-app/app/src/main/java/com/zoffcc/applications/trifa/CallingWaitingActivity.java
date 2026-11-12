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
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import static com.zoffcc.applications.trifa.CallingActivity.initializeScreenshotSecurity;
import static com.zoffcc.applications.trifa.HelperFriend.friend_call_push_url;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.MainActivity.PREF__window_security;

public class CallingWaitingActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.CallWtActivity";

    String calling_friend_pk = null;
    static boolean running = false;
    static boolean got_online = false;
    static Thread CallWThread = null;
    ImageButton decline_waiting_button = null;
    TextView call_waiting_friend_name = null;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        Log.i(TAG, "onCreate:01");

        calling_friend_pk = null;
        got_online = false;

        try
        {
            ConfGroupAudioService.stop_me(false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_calling_waiting);

        if (PREF__window_security)
        {
            initializeScreenshotSecurity(this);
        }

        decline_waiting_button = findViewById(R.id.decline_waiting_button);
        call_waiting_friend_name = findViewById(R.id.call_waiting_friend_name);
        call_waiting_friend_name.setText("");
        ViewCompat.setAccessibilityHeading(call_waiting_friend_name, true);

        // KHANDAQ (redesign, stage 3b; stage 5): the call screens stand on the flat lock ground (lock-top),
        // dark in both themes as on iOS, so the bars take its colour and keep light icons.
        final int ground = ContextCompat.getColor(this, R.color.kq_lock_top);
        getWindow().setStatusBarColor(ground);
        getWindow().setNavigationBarColor(ground);
        final WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(),
                                                                                   getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);

        // Cancel is a click, so TalkBack can press it too: the old touch listener acted on ACTION_DOWN,
        // which TalkBack's double tap never sends.
        decline_waiting_button.setOnClickListener(v -> {
            Log.i(TAG, "decline_button_pressed:stop_me()");
            stop_me();
        });

        try
        {
            Bundle extras = getIntent().getExtras();
            if (extras != null)
            {
                calling_friend_pk = extras.getString("calling_friend_pk", null);
            }
        }
        catch (Exception e)
        {
            calling_friend_pk = null;
        }

        if (calling_friend_pk != null)
        {
            String name = HelperFriend.resolve_name_for_pubkey(calling_friend_pk, "").trim();
            if (name.isEmpty())
            {
                // The fallback title of a contact without a name: the start of its key.
                name = calling_friend_pk.substring(0, Math.min(8, calling_friend_pk.length()));
            }
            call_waiting_friend_name.setText(name);
            ((TextView) findViewById(R.id.call_waiting_text)).setText(
                    getString(R.string.air_call_waiting_format, AirConversationModel.shortName(name)));
            // What the app did: the thread below sends the wake signal at once when one can go.
            ((TextView) findViewById(R.id.call_waiting_detail)).setText(
                    HelperFriend.can_wake_friend_phone(calling_friend_pk) ? R.string.air_call_waiting_push
                                                                          : R.string.air_call_waiting_no_push);
        }

        if (calling_friend_pk != null)
        {
            running = true;
            CallWThread = new Thread()
            {
                @Override
                public void run()
                {
                    Log.i(TAG, "CallWThread:starting");

                    boolean sent_ping_message = false;
                    while (running)
                    {
                        try
                        {
                            if (is_friend_online_real(tox_friend_by_public_key__wrapper(calling_friend_pk)) != 0)
                            {
                                running = false;
                                got_online = true;
                            }
                            else
                            {
                                if (!sent_ping_message)
                                {
                                    friend_call_push_url(calling_friend_pk, System.currentTimeMillis());
                                    Log.i(TAG, "sent ping to push url");
                                    sent_ping_message = true;
                                }
                            }
                            Thread.sleep(60);
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                    Log.i(TAG, "finish_me():002");
                    finish_me();
                }
            };

            Log.i(TAG, "onCreate:thread:2");
            CallWThread.start();
            Log.i(TAG, "onCreate:thread:3");
        }
        else
        {
            Log.i(TAG, "stop_me():002");
            stop_me();
            Log.i(TAG, "finish_me():003");
            finish_me();
        }

        Log.i(TAG, "onCreate:99");
    }

    @Override
    protected void onPause()
    {
        super.onPause();
        Log.i(TAG, "stop_me():004");
        stop_me();
    }

    void finish_me()
    {
        Log.i(TAG, "finish_me:001");
        if (got_online)
        {
            Log.i(TAG, "finish_me:RESULT_OK");
            final Intent data = new Intent();
            data.putExtra("friendnum_pk", calling_friend_pk);
            setResult(Activity.RESULT_OK, data);
        }
        else
        {
            final Intent data = new Intent();
            data.putExtra("friendnum_pk", -1);
            Log.i(TAG, "finish_me:RESULT_CANCELED");
            setResult(Activity.RESULT_CANCELED, data);
        }
        finish();
    }

    public static void stop_me()
    {
        running = false;
        try
        {
            if (CallWThread != null)
            {
                // KHANDAQ (ANR, 2026-08-26): stop_me(), the same bounded join as the other audio threads.
                CallWThread.join(HelperGeneric.AUDIO_THREAD_JOIN_TIMEOUT_MS);
                CallWThread = null;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}

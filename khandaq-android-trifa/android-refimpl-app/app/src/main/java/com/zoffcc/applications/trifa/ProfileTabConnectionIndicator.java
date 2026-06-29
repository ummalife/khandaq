package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.khandaq.messenger.R;

import static com.zoffcc.applications.trifa.MainActivity.main_activity_s;
import static com.zoffcc.applications.trifa.TRIFAGlobals.bootstrapping;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.manually_logged_out;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;

/**
 * iOS-style presence dot on the Profile tab avatar: green = online, yellow = connecting, red = offline.
 */
final class ProfileTabConnectionIndicator
{
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int COLOR_ONLINE = 0xFF2E9B4F;     // green
    private static final int COLOR_CONNECTING = 0xFFE6A100;  // yellow/amber
    private static final int COLOR_OFFLINE = 0xFFD7322F;     // red

    static void updateAsync()
    {
        mainHandler.post(ProfileTabConnectionIndicator::update);
    }

    @SuppressWarnings("RestrictedApi")
    private static void update()
    {
        final MainActivity activity = main_activity_s;
        if (activity == null || activity.isFinishing())
        {
            return;
        }

        final BottomNavigationView nav = activity.findViewById(R.id.bottom_navigation);
        if (nav == null)
        {
            return;
        }

        nav.post(() ->
        {
            try
            {
                if (!is_tox_started || manually_logged_out)
                {
                    nav.removeBadge(R.id.bottom_nav_profile);
                    return;
                }

                final int color;
                if (global_self_connection_status != TOX_CONNECTION_NONE.value && !bootstrapping)
                {
                    color = COLOR_ONLINE;
                }
                else if (bootstrapping)
                {
                    color = COLOR_CONNECTING;
                }
                else
                {
                    color = COLOR_OFFLINE;
                }

                final BadgeDrawable badge = nav.getOrCreateBadge(R.id.bottom_nav_profile);
                badge.clearNumber();
                badge.setBackgroundColor(color);
                badge.setBadgeGravity(BadgeDrawable.BOTTOM_END);
                badge.setVisible(true);
            }
            catch (Throwable ignored)
            {
            }
        });
    }
}

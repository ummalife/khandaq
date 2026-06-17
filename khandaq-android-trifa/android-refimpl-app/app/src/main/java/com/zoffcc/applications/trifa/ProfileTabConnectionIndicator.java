package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarItemView;

import org.khandaq.messenger.R;

import static com.zoffcc.applications.trifa.MainActivity.main_activity_s;

/**
 * Legacy hook: connection dot on Profile tab was removed — keep call sites as no-op cleanup.
 */
final class ProfileTabConnectionIndicator
{
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int DOT_VIEW_ID = View.generateViewId();

    static void updateAsync()
    {
        mainHandler.post(ProfileTabConnectionIndicator::removeDotIfPresent);
    }

    private static void removeDotIfPresent()
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
                final View tabView = nav.findViewById(R.id.bottom_nav_profile);
                if (tabView instanceof NavigationBarItemView)
                {
                    final View dotView = tabView.findViewById(DOT_VIEW_ID);
                    if (dotView != null)
                    {
                        ((NavigationBarItemView) tabView).removeView(dotView);
                    }
                }
            }
            catch (Throwable ignored)
            {
            }
        });
    }
}

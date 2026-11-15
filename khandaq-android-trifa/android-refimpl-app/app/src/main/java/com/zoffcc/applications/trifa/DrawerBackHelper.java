package com.zoffcc.applications.trifa;

import android.view.View;

import com.mikepenz.materialdrawer.Drawer;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

/**
 * KHANDAQ (0.2.56): Back closes an open drawer (the member list of a chat) first. Android 16 does not call
 * onBackPressed for an app that targets it, so this goes through the dispatcher, with the callback on only
 * while the drawer is open: Back with the drawer closed stays the system's, with its predictive animation.
 */
final class DrawerBackHelper
{
    private DrawerBackHelper()
    {
    }

    /** Call once the drawer is built, from onCreate. */
    static void attach(@NonNull final ComponentActivity activity, @NonNull final Drawer drawer)
    {
        final OnBackPressedCallback callback = new OnBackPressedCallback(false)
        {
            @Override
            public void handleOnBackPressed()
            {
                if (drawer.isDrawerOpen())
                {
                    drawer.closeDrawer();
                    return;
                }
                // Closed without telling the listener: Back goes on as if this were off.
                setEnabled(false);
                activity.getOnBackPressedDispatcher().onBackPressed();
            }
        };
        drawer.getDrawerLayout().addDrawerListener(new DrawerLayout.SimpleDrawerListener()
        {
            @Override
            public void onDrawerOpened(@NonNull final View drawerView)
            {
                callback.setEnabled(true);
            }

            @Override
            public void onDrawerClosed(@NonNull final View drawerView)
            {
                callback.setEnabled(false);
            }
        });
        // A drawer the screen restored open (after a recreation) tells no listener.
        activity.getLifecycle().addObserver((LifecycleEventObserver) (source, event) -> {
            if (event == Lifecycle.Event.ON_RESUME)
            {
                callback.setEnabled(drawer.isDrawerOpen());
            }
        });
        activity.getOnBackPressedDispatcher().addCallback(activity, callback);
    }
}

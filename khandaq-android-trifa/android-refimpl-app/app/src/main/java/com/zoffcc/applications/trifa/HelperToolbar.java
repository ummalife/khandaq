package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.util.TypedValue;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * Consistent back navigation for secondary screens (same pattern as SettingsActivity).
 */
public final class HelperToolbar
{
    private HelperToolbar()
    {
    }

    public static void enableUpNavigation(AppCompatActivity activity, Toolbar toolbar)
    {
        if (activity == null || toolbar == null)
        {
            return;
        }

        TypedValue typedValue = new TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.homeAsUpIndicator, typedValue, true))
        {
            toolbar.setNavigationIcon(typedValue.resourceId);
        }

        toolbar.setNavigationOnClickListener(v -> activity.finish());

        if (activity.getSupportActionBar() != null)
        {
            activity.getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            activity.getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
    }

    public static void setScreenTitle(AppCompatActivity activity, Toolbar toolbar, int titleResId)
    {
        enableUpNavigation(activity, toolbar);
        if (activity.getSupportActionBar() != null)
        {
            activity.getSupportActionBar().setTitle(titleResId);
        }
        else
        {
            toolbar.setTitle(titleResId);
        }
    }
}

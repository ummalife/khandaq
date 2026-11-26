package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * KHANDAQ (QA, 27.11): opens one screen of the app straight from adb in a debug build, so a screen can be
 * looked at on a device or an emulator without going through the messenger first:
 * {@code adb shell am start -n <applicationId>/com.zoffcc.applications.trifa.AirDebugOpenActivity --es screen
 * NetworkDiagnosticsActivity}. Only an activity this app declares in this package opens, and every other
 * extra goes along to it. Logcat tag trifa.AirDebugOpen says what was opened or why not. Debug builds only.
 */
public final class AirDebugOpenActivity extends Activity
{
    private static final String TAG = "trifa.AirDebugOpen";
    private static final String EXTRA_SCREEN = "screen";

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        final Intent asked = getIntent();
        final String screen = asked == null ? null : asked.getStringExtra(EXTRA_SCREEN);
        final ComponentName target = declaredScreen(screen);
        if (target == null)
        {
            Log.w(TAG, "no such screen: " + screen);
        }
        else
        {
            final Intent open = new Intent().setComponent(target);
            final Bundle extras = asked.getExtras();
            if (extras != null)
            {
                extras.remove(EXTRA_SCREEN);
                open.putExtras(extras);
            }
            try
            {
                startActivity(open);
                Log.i(TAG, "opened " + target.getClassName());
            }
            catch (RuntimeException e)
            {
                Log.w(TAG, "could not open " + screen, e);
            }
        }
        finish();
    }

    /** The activity of this package named {@code screen} when the app declares it, never this one. */
    private ComponentName declaredScreen(final String screen)
    {
        if (screen == null || !screen.matches("[A-Za-z0-9_]+"))
        {
            return null;
        }
        final String name = AirDebugOpenActivity.class.getPackage().getName() + "." + screen;
        if (name.equals(AirDebugOpenActivity.class.getName()))
        {
            return null;
        }
        try
        {
            final PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_ACTIVITIES);
            if (info.activities != null)
            {
                for (final ActivityInfo activity : info.activities)
                {
                    if (name.equals(activity.name))
                    {
                        return new ComponentName(getPackageName(), name);
                    }
                }
            }
        }
        catch (PackageManager.NameNotFoundException ignored)
        {
            // Not this package after all: nothing to open.
        }
        return null;
    }
}

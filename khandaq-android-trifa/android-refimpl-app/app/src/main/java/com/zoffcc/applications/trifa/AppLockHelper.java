package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF__DB_secrect_key__user_hash;

/**
 * KHANDAQ (Profile → Детали): optional app lock. When enabled, the app asks for the password
 * again after it has been in the background longer than the configured timeout. Enforcement is
 * additive — it reuses the existing, tested CheckPasswordActivity unlock screen and never touches
 * the cold-start / keystore flow, so it cannot lock the user out beyond the normal password gate.
 */
public class AppLockHelper
{
    static final String PREF_ENABLED = "PREF__app_lock_enabled";
    static final String PREF_TIMEOUT = "PREF__app_lock_timeout_sec";

    private static int started_count = 0;
    private static long backgrounded_at_ms = 0L;
    private static boolean was_backgrounded = false;

    static boolean isEnabled(final Context c)
    {
        try
        {
            return PreferenceManager.getDefaultSharedPreferences(c).getBoolean(PREF_ENABLED, false);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static int timeoutSec(final Context c)
    {
        try
        {
            return PreferenceManager.getDefaultSharedPreferences(c).getInt(PREF_TIMEOUT, 0);
        }
        catch (Exception e)
        {
            return 0;
        }
    }

    /** A lock only makes sense when a manual password actually exists. */
    static boolean hasPassword()
    {
        return !TextUtils.isEmpty(PREF__DB_secrect_key__user_hash);
    }

    /** Never re-lock while on the auth / onboarding screens themselves. */
    private static boolean isAuthScreen(final Activity a)
    {
        return (a instanceof CheckPasswordActivity) || (a instanceof SetPasswordActivity)
                || (a instanceof StartMainActivityWrapper) || (a instanceof OnboardingActivity)
                || (a instanceof CreateProfileActivity);
    }

    static void register(final Application app)
    {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks()
        {
            @Override
            public void onActivityStarted(final Activity a)
            {
                started_count++;
                if (started_count == 1 && was_backgrounded)
                {
                    was_backgrounded = false;
                    try
                    {
                        if (isAuthScreen(a) || !isEnabled(a) || !hasPassword())
                        {
                            return;
                        }
                        final long elapsed = System.currentTimeMillis() - backgrounded_at_ms;
                        if (elapsed >= (long) timeoutSec(a) * 1000L)
                        {
                            final Intent i = new Intent(a, CheckPasswordActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            a.startActivity(i);
                        }
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }

            @Override
            public void onActivityStopped(final Activity a)
            {
                started_count--;
                if (started_count <= 0)
                {
                    started_count = 0;
                    was_backgrounded = true;
                    backgrounded_at_ms = System.currentTimeMillis();
                }
            }

            @Override
            public void onActivityCreated(final Activity a, final Bundle b) { }

            @Override
            public void onActivityResumed(final Activity a) { }

            @Override
            public void onActivityPaused(final Activity a) { }

            @Override
            public void onActivitySaveInstanceState(final Activity a, final Bundle b) { }

            @Override
            public void onActivityDestroyed(final Activity a) { }
        });
    }
}

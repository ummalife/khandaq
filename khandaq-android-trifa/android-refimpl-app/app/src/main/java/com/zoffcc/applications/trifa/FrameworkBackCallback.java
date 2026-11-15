package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.RequiresApi;

/**
 * KHANDAQ (0.2.56): Back for an activity without the AndroidX dispatcher (a PreferenceActivity, a
 * ListActivity). From Android 13 with android:enableOnBackInvokedCallback, and on Android 16 for an app
 * that targets it, the platform does not call onBackPressed: Back goes to a callback registered with the
 * window or, with none, leaves the screen. This registers one only while the activity has something to
 * step back from; older versions keep calling onBackPressed.
 */
final class FrameworkBackCallback
{
    private final Activity activity;
    private final Runnable onBack;
    /** The registered OnBackInvokedCallback, held as an Object so that older versions never load its class. */
    private Object registered = null;

    FrameworkBackCallback(final Activity activity, final Runnable onBack)
    {
        this.activity = activity;
        this.onBack = onBack;
    }

    /** Registers the callback while wanted is true; does nothing before Android 13. Main thread only. */
    void setWanted(final boolean wanted)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        {
            return;
        }
        if (wanted && registered == null)
        {
            registered = Api33.register(activity, onBack);
        }
        else if (!wanted && registered != null)
        {
            Api33.unregister(activity, registered);
            registered = null;
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private static final class Api33
    {
        static Object register(final Activity activity, final Runnable onBack)
        {
            final OnBackInvokedCallback callback = onBack::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(final Activity activity, final Object callback)
        {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
        }
    }
}

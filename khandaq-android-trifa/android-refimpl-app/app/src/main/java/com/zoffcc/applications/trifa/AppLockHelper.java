package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;

import androidx.preference.PreferenceManager;

import java.security.MessageDigest;
import java.security.SecureRandom;

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
    // KHANDAQ PIN: a LOCAL UI gate only. This SHA-256(salt+pin) hash never touches DB encryption /
    // DbSecretKeyStorage — the DB still opens with its own key; the PIN merely covers the UI.
    static final String PREF_PIN_HASH = "PREF__app_lock_pin_hash";
    static final String PREF_PIN_SALT = "PREF__app_lock_pin_salt";

    private static int started_count = 0;
    private static long backgrounded_at_ms = 0L;
    private static boolean was_backgrounded = false;
    // KHANDAQ (#12): set right before the app launches its OWN picker (file/camera/gallery/audio).
    // The system picker sends us to the background, and coming back would otherwise trip the lock.
    // This suppresses exactly one lock check — the return from that picker. Real backgrounding
    // (no pending internal picker) still locks normally.
    private static volatile boolean suppress_next_lock = false;

    /** Call just before startActivityForResult() for an in-app picker so the return doesn't re-lock. */
    static void suppressNextLock()
    {
        suppress_next_lock = true;
    }

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

    // KHANDAQ: default grace period. With 0 ("немедленно") every app switch — a notification,
    // a share sheet, a quick look at another app — demanded the password again ("постоянно
    // просит пароль"). Users who explicitly picked "Сразу" in the picker keep their 0.
    static final int DEFAULT_TIMEOUT_SEC = 60;
    // Set when the user picks a timeout in the picker themselves. Older builds (≤0.2.11) stored an
    // implicit 0 without any user choice — a stored 0 WITHOUT this marker is migrated to the default.
    static final String PREF_TIMEOUT_USER_SET = "PREF__app_lock_timeout_user_set";

    static int timeoutSec(final Context c)
    {
        try
        {
            final android.content.SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);
            final int v = p.getInt(PREF_TIMEOUT, DEFAULT_TIMEOUT_SEC);
            if (v == 0 && !p.getBoolean(PREF_TIMEOUT_USER_SET, false))
            {
                // Legacy implicit 0 ("PIN на каждый чих") — one-time migration to the sane default.
                p.edit().putInt(PREF_TIMEOUT, DEFAULT_TIMEOUT_SEC).apply();
                return DEFAULT_TIMEOUT_SEC;
            }
            return v;
        }
        catch (Exception e)
        {
            return DEFAULT_TIMEOUT_SEC;
        }
    }

    /** A lock only makes sense when a manual password actually exists. */
    static boolean hasPassword()
    {
        return !TextUtils.isEmpty(PREF__DB_secrect_key__user_hash);
    }

    /** True once a local PIN has been set (the preferred unlock method). */
    static boolean hasPin(final Context c)
    {
        try
        {
            final String h = PreferenceManager.getDefaultSharedPreferences(c).getString(PREF_PIN_HASH, "");
            return !TextUtils.isEmpty(h);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Persist a freshly chosen PIN as SHA-256(salt + pin). Does NOT enable the lock — caller decides. */
    static void savePin(final Context c, final String pin)
    {
        try
        {
            final byte[] saltBytes = new byte[16];
            new SecureRandom().nextBytes(saltBytes);
            final String salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP);
            PreferenceManager.getDefaultSharedPreferences(c).edit()
                    .putString(PREF_PIN_SALT, salt)
                    .putString(PREF_PIN_HASH, hashPin(salt, pin))
                    .apply();
        }
        catch (Exception ignored)
        {
        }
    }

    /** Constant enough for a local gate: recompute SHA-256(salt + pin) and compare to the stored hash. */
    static boolean verifyPin(final Context c, final String pin)
    {
        try
        {
            final android.content.SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(c);
            final String salt = p.getString(PREF_PIN_SALT, "");
            final String stored = p.getString(PREF_PIN_HASH, "");
            if (TextUtils.isEmpty(stored))
            {
                return false;
            }
            return stored.equals(hashPin(salt, pin));
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Forget the local PIN (used when the lock is turned off). Never touches DB encryption. */
    static void clearPin(final Context c)
    {
        try
        {
            PreferenceManager.getDefaultSharedPreferences(c).edit()
                    .remove(PREF_PIN_HASH)
                    .remove(PREF_PIN_SALT)
                    .apply();
        }
        catch (Exception ignored)
        {
        }
    }

    private static String hashPin(final String salt, final String pin) throws Exception
    {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update((salt == null ? "" : salt).getBytes("UTF-8"));
        final byte[] digest = md.digest((pin == null ? "" : pin).getBytes("UTF-8"));
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    /** Never re-lock while on the auth / onboarding screens themselves. */
    private static boolean isAuthScreen(final Activity a)
    {
        return (a instanceof CheckPasswordActivity) || (a instanceof SetPasswordActivity)
                || (a instanceof PinActivity)
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
                    // KHANDAQ (#12): we just came back from our own picker (file/camera/gallery/audio) —
                    // that's not a real "left the app", so don't demand the PIN/password.
                    if (suppress_next_lock)
                    {
                        suppress_next_lock = false;
                        return;
                    }
                    try
                    {
                        if (isAuthScreen(a) || !isEnabled(a))
                        {
                            return;
                        }
                        final boolean pin = hasPin(a);
                        // Nothing to enforce with — no PIN and no manual password.
                        if (!pin && !hasPassword())
                        {
                            return;
                        }
                        final long elapsed = System.currentTimeMillis() - backgrounded_at_ms;
                        if (elapsed >= (long) timeoutSec(a) * 1000L)
                        {
                            final Intent i;
                            if (pin)
                            {
                                // Preferred local gate: the PIN unlock screen.
                                i = new Intent(a, PinActivity.class);
                                i.putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_UNLOCK);
                            }
                            else
                            {
                                // Legacy fallback: lock was enabled before PIN existed.
                                i = new Intent(a, CheckPasswordActivity.class);
                            }
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
                    // KHANDAQ: an activity recreate (theme switch, rotation, split-screen) briefly
                    // drops started_count to 0 but is NOT "leaving the app" — don't arm the lock.
                    if (a.isChangingConfigurations())
                    {
                        return;
                    }
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

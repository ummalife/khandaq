package com.zoffcc.applications.trifa;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * KHANDAQ (#T2): one-shot current-location fetch for "share my location". Uses the platform
 * LocationManager (no Google Play Services dependency). User-initiated only.
 * <p>
 * KHANDAQ (owner, 28.11: "it sends another place, not where I am"): the place sent is where the person is now. The
 * share took the newest fix the system had, from whichever radio and however rough, and after 15 seconds without a
 * fix any fix at all, however old; now it listens to every radio that is on and sends the most accurate fresh fix
 * (LocationFixPicker), and asks once to allow the exact location where only the approximate one is allowed.
 */
final class ShareLocationHelper
{
    static final int PERMISSION_REQUEST_CODE = 3084;
    /** How long a share waits for a fix of where the person is now. GPS alone, indoors, takes a while. */
    static final long MAX_WAIT_MS = 15_000L;
    /** With no fresh fix when the wait ends, a fix the system had from this recently still goes. */
    static final long FALLBACK_MAX_AGE_MS = 120_000L;

    /** The exact location was asked for in this run already (shouldAskForPrecise). */
    private static boolean askedForPrecise = false;

    interface Callback
    {
        void onLocation(double latitude, double longitude);

        void onError(String reason);
    }

    static boolean hasPermission(final Context c)
    {
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * KHANDAQ (owner, 28.11): only the approximate location is allowed (Android 12+), so a place sent is kilometres
     * off. A share asks once in a run to allow the exact one; after a "no" it sends the approximate one, as allowed.
     */
    static boolean shouldAskForPrecise(final Context c)
    {
        if (askedForPrecise || Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
        {
            return false;
        }
        return ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION)
               != PackageManager.PERMISSION_GRANTED
               && ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION)
                  == PackageManager.PERMISSION_GRANTED;
    }

    static void requestPermission(final Activity a)
    {
        // Asking for both shows the system's choice of the exact or the approximate location, and to one who allowed
        // the approximate one, the offer of the exact one. Once in a run: the answer comes back to the share.
        askedForPrecise = true;
        ActivityCompat.requestPermissions(a, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, PERMISSION_REQUEST_CODE);
    }

    static void fetchOnce(final Context c, final Callback cb)
    {
        if (!hasPermission(c))
        {
            cb.onError("no_permission");
            return;
        }
        final LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null)
        {
            cb.onError("no_location_manager");
            return;
        }
        new Fix(lm, cb).start();
    }

    /** How old a fix is, by the clock that does not jump (the fix's own time may come from another clock). */
    static long ageMs(final Location l)
    {
        final long nanos = l.getElapsedRealtimeNanos();
        if (nanos > 0L)
        {
            return Math.max(0L, (SystemClock.elapsedRealtimeNanos() - nanos) / 1_000_000L);
        }
        return Math.max(0L, System.currentTimeMillis() - l.getTime());
    }

    /**
     * One share's wait for a fix: the fixes the system already has, then fresh ones from every radio that is on, on
     * the main looper. Delivers once: the fix, or the reason there is none.
     */
    private static final class Fix implements LocationListener
    {
        private final LocationManager lm;
        private final Callback cb;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final LocationFixPicker fresh = new LocationFixPicker();
        private final LocationFixPicker recent = new LocationFixPicker(FALLBACK_MAX_AGE_MS);
        private final Runnable patience = this::finishIfSettled;
        private final Runnable end = () -> finish("timeout");
        private long startedAt;
        private boolean listening = false;
        private boolean done = false;

        Fix(final LocationManager lm, final Callback cb)
        {
            this.lm = lm;
            this.cb = cb;
        }

        @SuppressWarnings("MissingPermission")
        void start()
        {
            startedAt = SystemClock.elapsedRealtime();
            try
            {
                for (final String p : lm.getProviders(true))
                {
                    final Location l = lm.getLastKnownLocation(p);
                    if (l != null)
                    {
                        consider(l);
                    }
                }
            }
            catch (Exception ignored)
            {
            }
            if (fresh.isSettled(0L))
            {
                finish("timeout");
                return;
            }

            for (final String p : radios())
            {
                try
                {
                    lm.requestLocationUpdates(p, 0L, 0f, this, Looper.getMainLooper());
                    listening = true;
                }
                catch (Exception ignored)
                {
                }
            }
            if (!listening)
            {
                // Location is off: a fix the system had from just now still goes, else the chat says none came.
                finish("location_off");
                return;
            }
            handler.postDelayed(patience, LocationFixPicker.PATIENCE_MS);
            handler.postDelayed(end, MAX_WAIT_MS);
        }

        /** The radios that are on: the fused one (Android 12+, GPS and Wi-Fi together), GPS, the network one. */
        private List<String> radios()
        {
            final List<String> wanted = new ArrayList<>(3);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            {
                wanted.add(LocationManager.FUSED_PROVIDER);
            }
            wanted.add(LocationManager.GPS_PROVIDER);
            wanted.add(LocationManager.NETWORK_PROVIDER);
            final List<String> on = new ArrayList<>(3);
            for (final String p : wanted)
            {
                try
                {
                    if (lm.isProviderEnabled(p))
                    {
                        on.add(p);
                    }
                }
                catch (Exception ignored)
                {
                }
            }
            return on;
        }

        private void consider(final Location l)
        {
            final long age = ageMs(l);
            fresh.consider(l.getLatitude(), l.getLongitude(), l.hasAccuracy(), l.getAccuracy(), age);
            recent.consider(l.getLatitude(), l.getLongitude(), l.hasAccuracy(), l.getAccuracy(), age);
        }

        private void finishIfSettled()
        {
            if (fresh.isSettled(SystemClock.elapsedRealtime() - startedAt))
            {
                finish("timeout");
            }
        }

        private void finish(final String reasonIfNone)
        {
            if (done)
            {
                return;
            }
            done = true;
            handler.removeCallbacks(patience);
            handler.removeCallbacks(end);
            if (listening)
            {
                try
                {
                    lm.removeUpdates(this);
                }
                catch (Exception ignored)
                {
                }
            }
            final LocationFixPicker pick = fresh.hasFix() ? fresh : recent;
            if (pick.hasFix())
            {
                cb.onLocation(pick.latitude(), pick.longitude());
            }
            else
            {
                cb.onError(reasonIfNone);
            }
        }

        @Override
        public void onLocationChanged(@NonNull final Location location)
        {
            if (done)
            {
                return;
            }
            consider(location);
            finishIfSettled();
        }

        // Abstract before Android 11: a provider calls them there.
        @Override
        public void onStatusChanged(final String provider, final int status, final Bundle extras)
        {
        }

        @Override
        public void onProviderEnabled(@NonNull final String provider)
        {
        }

        @Override
        public void onProviderDisabled(@NonNull final String provider)
        {
        }
    }

    private ShareLocationHelper()
    {
    }
}

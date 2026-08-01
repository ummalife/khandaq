package com.zoffcc.applications.trifa;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * KHANDAQ (#T2): one-shot current-location fetch for "share my location". Uses the platform
 * LocationManager (no Google Play Services dependency): fast last-known first, then a single fresh
 * update with a timeout fallback. User-initiated only.
 */
final class ShareLocationHelper
{
    static final int PERMISSION_REQUEST_CODE = 3084;

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

    static void requestPermission(final Activity a)
    {
        ActivityCompat.requestPermissions(a, new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, PERMISSION_REQUEST_CODE);
    }

    @SuppressWarnings("MissingPermission")
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

        // 1) fast path: freshest recent last-known fix across providers
        Location best = null;
        try
        {
            for (final String p : lm.getProviders(true))
            {
                final Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime()))
                {
                    best = l;
                }
            }
        }
        catch (Exception ignored)
        {
        }
        if (best != null && (System.currentTimeMillis() - best.getTime()) < 120_000L)
        {
            cb.onLocation(best.getLatitude(), best.getLongitude());
            return;
        }

        // 2) request one fresh update, with a timeout that falls back to any last-known fix
        final String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                        ? LocationManager.NETWORK_PROVIDER : null);
        if (provider == null)
        {
            if (best != null)
            {
                cb.onLocation(best.getLatitude(), best.getLongitude());
            }
            else
            {
                cb.onError("location_off");
            }
            return;
        }

        final Location fallback = best;
        final Handler handler = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        // KHANDAQ (#T2): keep a handle to the 15s timeout so a successful fix can cancel it — otherwise
        // the posted Runnable (and the captured Activity context) is retained on the main looper for 15s.
        final Runnable[] timeout = new Runnable[1];
        final LocationListener listener = new LocationListener()
        {
            @Override
            public void onLocationChanged(final Location location)
            {
                if (done[0])
                {
                    return;
                }
                done[0] = true;
                try { lm.removeUpdates(this); } catch (Exception ignored) {}
                if (timeout[0] != null)
                {
                    handler.removeCallbacks(timeout[0]);
                }
                cb.onLocation(location.getLatitude(), location.getLongitude());
            }

            @Override public void onStatusChanged(String p, int s, Bundle e) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try
        {
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
        }
        catch (Exception e)
        {
            if (fallback != null)
            {
                cb.onLocation(fallback.getLatitude(), fallback.getLongitude());
            }
            else
            {
                cb.onError("request_failed");
            }
            return;
        }
        // 15s timeout → use whatever last-known we had, else error
        timeout[0] = () -> {
            if (done[0])
            {
                return;
            }
            done[0] = true;
            try { lm.removeUpdates(listener); } catch (Exception ignored) {}
            if (fallback != null)
            {
                cb.onLocation(fallback.getLatitude(), fallback.getLongitude());
            }
            else
            {
                cb.onError("timeout");
            }
        };
        handler.postDelayed(timeout[0], 15_000L);
    }

    private ShareLocationHelper()
    {
    }
}

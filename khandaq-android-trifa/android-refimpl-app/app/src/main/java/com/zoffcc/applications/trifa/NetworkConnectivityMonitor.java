package com.zoffcc.applications.trifa;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import static com.zoffcc.applications.trifa.HelperGeneric.append_logger_msg;
import static com.zoffcc.applications.trifa.HelperGeneric.sync_have_internet_connectivity;
import static com.zoffcc.applications.trifa.TRIFAGlobals.HAVE_INTERNET_CONNECTIVITY;
import static com.zoffcc.applications.trifa.ReconnectBackoffCoordinator.Reason.NETWORK_CHANGE;

/**
 * Modern network change detection (WiFi/mobile/VPN/IP changes).
 * Replaces unreliable CONNECTIVITY_ACTION on API 24+.
 */
final class NetworkConnectivityMonitor
{
    private static final String TAG = "trifa.NetMonitor";
    private static final long REBOOTSTRAP_DEBOUNCE_MS = 500L;

    private static NetworkConnectivityMonitor instance;
    private static ConnectivityManager.NetworkCallback networkCallback;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRebootstrap;

    static void register(final Context context)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
        {
            return;
        }
        if (instance != null)
        {
            return;
        }
        instance = new NetworkConnectivityMonitor();
        instance.start(context.getApplicationContext());
    }

    private void start(final Context appContext)
    {
        sync_have_internet_connectivity(appContext);

        final ConnectivityManager cm =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null)
        {
            return;
        }

        networkCallback = new ConnectivityManager.NetworkCallback()
        {
            @Override
            public void onAvailable(final Network network)
            {
                handleNetworkEvent(appContext, "available");
            }

            @Override
            public void onLost(final Network network)
            {
                sync_have_internet_connectivity(appContext);
                append_logger_msg(TAG + "::onLost HAVE_INTERNET=" + HAVE_INTERNET_CONNECTIVITY);
                ConnectionQualityMonitor.get().onInternetLost();
                NetworkDiagnosticsLog.log("network_lost", "default network lost");
            }

            @Override
            public void onCapabilitiesChanged(final Network network, final NetworkCapabilities caps)
            {
                handleNetworkEvent(appContext, "capabilities");
            }
        };

        try
        {
            cm.registerDefaultNetworkCallback(networkCallback);
            Log.i(TAG, "registerDefaultNetworkCallback OK");
        }
        catch (Exception e)
        {
            Log.w(TAG, "registerDefaultNetworkCallback failed, fallback to NetworkRequest", e);
            try
            {
                final NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(request, networkCallback);
            }
            catch (Exception e2)
            {
                Log.e(TAG, "registerNetworkCallback failed", e2);
            }
        }
    }

    private void handleNetworkEvent(final Context appContext, final String reason)
    {
        final boolean wasOnline = HAVE_INTERNET_CONNECTIVITY;
        sync_have_internet_connectivity(appContext);
        append_logger_msg(TAG + "::" + reason + " HAVE_INTERNET=" + HAVE_INTERNET_CONNECTIVITY
                + " was=" + wasOnline);

        if (!HAVE_INTERNET_CONNECTIVITY)
        {
            return;
        }

        if (!TrifaToxService.is_tox_started || TrifaToxService.orma == null)
        {
            return;
        }

        scheduleRebootstrap(reason);
    }

    private void scheduleRebootstrap(final String reason)
    {
        if (pendingRebootstrap != null)
        {
            debounceHandler.removeCallbacks(pendingRebootstrap);
        }
        pendingRebootstrap = new Runnable()
        {
            @Override
            public void run()
            {
                Log.i(TAG, "network rebootstrap (" + reason + ")");
                append_logger_msg(TAG + "::rebootstrap reason=" + reason);
                ReconnectBackoffCoordinator.get().scheduleReconnect(NETWORK_CHANGE, true);
            }
        };
        debounceHandler.postDelayed(pendingRebootstrap, REBOOTSTRAP_DEBOUNCE_MS);
    }
}

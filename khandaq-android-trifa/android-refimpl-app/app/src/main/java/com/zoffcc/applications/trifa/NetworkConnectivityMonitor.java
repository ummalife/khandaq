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
    private static final long REBOOTSTRAP_MIN_INTERVAL_MS = 10_000L;

    private static NetworkConnectivityMonitor instance;
    private static ConnectivityManager.NetworkCallback networkCallback;

    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRebootstrap;
    private long lastNetworkHandle = -1L;
    private boolean lastValidated = false;
    private long lastRebootstrapMs = 0L;

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
                final boolean networkChanged = updateNetworkHandle(network);
                handleNetworkEvent(appContext, "available", networkChanged);
            }

            @Override
            public void onLost(final Network network)
            {
                sync_have_internet_connectivity(appContext);
                append_logger_msg(TAG + "::onLost HAVE_INTERNET=" + HAVE_INTERNET_CONNECTIVITY);
                if (!HAVE_INTERNET_CONNECTIVITY)
                {
                    lastNetworkHandle = -1L;
                    lastValidated = false;
                    ConnectionQualityMonitor.get().onInternetLost();
                    NetworkDiagnosticsLog.log("network_lost", "default network lost");
                }
            }

            @Override
            public void onCapabilitiesChanged(final Network network, final NetworkCapabilities caps)
            {
                // fires every few seconds on mobile (signal level changes) — only react
                // to a real network switch or the network becoming validated
                final boolean networkChanged = updateNetworkHandle(network);
                final boolean validated =
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                final boolean becameValidated = validated && !lastValidated;
                lastValidated = validated;
                if (networkChanged || becameValidated)
                {
                    handleNetworkEvent(appContext, "capabilities", true);
                }
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

    private boolean updateNetworkHandle(final Network network)
    {
        final long handle = network.getNetworkHandle();
        final boolean changed = (lastNetworkHandle != -1L) && (handle != lastNetworkHandle);
        if (handle != lastNetworkHandle)
        {
            lastValidated = false;
        }
        lastNetworkHandle = handle;
        return changed;
    }

    private void handleNetworkEvent(final Context appContext, final String reason,
                                    final boolean meaningfulChange)
    {
        final boolean wasOnline = HAVE_INTERNET_CONNECTIVITY;
        sync_have_internet_connectivity(appContext);
        append_logger_msg(TAG + "::" + reason + " HAVE_INTERNET=" + HAVE_INTERNET_CONNECTIVITY
                + " was=" + wasOnline + " change=" + meaningfulChange);

        if (!HAVE_INTERNET_CONNECTIVITY)
        {
            return;
        }

        if (!TrifaToxService.is_tox_started || TrifaToxService.orma == null)
        {
            return;
        }

        final boolean cameBackOnline = !wasOnline;
        if (!meaningfulChange && !cameBackOnline)
        {
            return;
        }

        // tox already connected and same network: skip churn unless enough time passed
        final long now = android.os.SystemClock.elapsedRealtime();
        if (TRIFAGlobals.global_self_connection_status !=
            ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value
            && (now - lastRebootstrapMs) < REBOOTSTRAP_MIN_INTERVAL_MS)
        {
            NetworkDiagnosticsLog.log("rebootstrap_skip",
                    "reason=" + reason + " tox online, too soon");
            return;
        }
        lastRebootstrapMs = now;

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

package com.zoffcc.applications.trifa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import static com.zoffcc.applications.trifa.HelperGeneric.append_logger_msg;
import static com.zoffcc.applications.trifa.HelperGeneric.sync_have_internet_connectivity;
import static com.zoffcc.applications.trifa.TRIFAGlobals.HAVE_INTERNET_CONNECTIVITY;
import static com.zoffcc.applications.trifa.ReconnectBackoffCoordinator.Reason.NETWORK_CHANGE;

/**
 * Legacy CONNECTIVITY_ACTION fallback for API levels without NetworkCallback.
 *
 * <p>KHANDAQ (external audit: exported receiver): this component is exported, so it is reachable by
 * an explicit intent from any app on the device. The action check and the "extras never decide
 * anything" rule live in {@link ConnectivityBroadcastPolicy}, which explains both and is unit
 * tested; everything here is the Android plumbing around that decision.
 */
public class ConnectionManager extends BroadcastReceiver
{
    private static final String TAG = "trifa.ConManager";

    @Override
    public void onReceive(Context context, Intent intent)
    {
        try
        {
            final String action = (intent == null) ? null : intent.getAction();
            // FIRST, before a single extra is read: anything that is not the real connectivity
            // broadcast is somebody else's intent aimed at an exported component.
            if (!ConnectivityBroadcastPolicy.accepts(action))
            {
                Log.i(TAG, "onReceive: ignoring foreign intent action=" + action);
                return;
            }

            Log.i(TAG, "onReceive:intent=" + intent);

            // Past the action check these can only have come from the system, since the action is a
            // protected broadcast. `failOver` is gone from the decision because it was provably
            // dead: the old condition was `HAVE && (failOver || !noConnectivity)` evaluated AFTER
            // `if (noConnectivity) HAVE = false`, so noConnectivity=true forced the whole thing
            // false whatever failOver said, and noConnectivity=false made `!noConnectivity` true
            // whatever failOver said. What is left below is the same expression, minus the branch
            // that could never change its value.
            final boolean noConnectivity =
                    intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
            final boolean failOver =
                    intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);
            final String reason = intent.getStringExtra(ConnectivityManager.EXTRA_REASON);
            final NetworkInfo info1 = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            final NetworkInfo info2 = intent.getParcelableExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO);

            // The authoritative state: asked of ConnectivityManager, not taken from the intent.
            sync_have_internet_connectivity(context);
            if (noConnectivity)
            {
                HAVE_INTERNET_CONNECTIVITY = false;
            }

            append_logger_msg(TAG + "::HAVE_INTERNET=" + HAVE_INTERNET_CONNECTIVITY
                    + " noConn=" + noConnectivity + " failOver=" + failOver + " reason=" + reason);

            if (ConnectivityBroadcastPolicy.shouldScheduleReconnect(action, HAVE_INTERNET_CONNECTIVITY))
            {
                ReconnectBackoffCoordinator.get().scheduleReconnect(NETWORK_CHANGE, true);
            }

            Log.i(TAG, "onReceive: mNetworkInfo=" + info1 + " mOtherNetworkInfo="
                    + (info2 == null ? "[none]" : info2));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "onReceive:EE:" + e.getMessage());
        }
    }
}

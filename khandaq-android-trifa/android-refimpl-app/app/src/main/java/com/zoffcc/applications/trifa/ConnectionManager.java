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

/** Legacy CONNECTIVITY_ACTION fallback for API levels without NetworkCallback. */
public class ConnectionManager extends BroadcastReceiver
{
    private static final String TAG = "trifa.ConManager";

    @Override
    public void onReceive(Context context, Intent intent)
    {
        try
        {
            Log.i(TAG, "onReceive:intent=" + intent);

            final boolean noConnectivity =
                    intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
            final boolean failOver =
                    intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);
            final String reason = intent.getStringExtra(ConnectivityManager.EXTRA_REASON);
            final NetworkInfo info1 = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            final NetworkInfo info2 = intent.getParcelableExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO);

            sync_have_internet_connectivity(context);
            if (noConnectivity)
            {
                HAVE_INTERNET_CONNECTIVITY = false;
            }

            append_logger_msg(TAG + "::HAVE_INTERNET=" + HAVE_INTERNET_CONNECTIVITY
                    + " noConn=" + noConnectivity + " failOver=" + failOver + " reason=" + reason);

            if (HAVE_INTERNET_CONNECTIVITY && (failOver || noConnectivity == false))
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

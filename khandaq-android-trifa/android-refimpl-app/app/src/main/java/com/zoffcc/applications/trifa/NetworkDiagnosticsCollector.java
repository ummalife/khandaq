package com.zoffcc.applications.trifa;

import android.content.Context;

import static com.zoffcc.applications.trifa.HelperGeneric.sync_have_internet_connectivity;
import static com.zoffcc.applications.trifa.HelperGeneric.get_network_connections;
import static com.zoffcc.applications.trifa.MainActivity.tox_get_all_tcp_relays;
import static com.zoffcc.applications.trifa.TRIFAGlobals.HAVE_INTERNET_CONNECTIVITY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_connection_status;

/**
 * Collects a snapshot of network / tox state for the diagnostics screen.
 */
final class NetworkDiagnosticsCollector
{
    private NetworkDiagnosticsCollector()
    {
    }

    static String collect(final Context context)
    {
        sync_have_internet_connectivity(context);

        final StringBuilder sb = new StringBuilder();
        sb.append("HAVE_INTERNET=").append(HAVE_INTERNET_CONNECTIVITY).append('\n');
        sb.append("global_self_connection_status=").append(global_self_connection_status).append('\n');

        try
        {
            sb.append("tox_self_connection_status=").append(tox_self_get_connection_status()).append('\n');
        }
        catch (Exception e)
        {
            sb.append("tox_self_connection_status=error:").append(e.getMessage()).append('\n');
        }

        sb.append("reconnect_attempt=").append(ReconnectBackoffCoordinator.get().getAttempt()).append('\n');
        sb.append("connection_quality=").append(ConnectionQualityMonitor.get().getLabel()).append('\n');
        sb.append("estimated_rtt_ms=").append(ConnectionQualityMonitor.get().getEstimatedRttMs()).append('\n');

        try
        {
            sb.append("\n--- TCP relays ---\n").append(tox_get_all_tcp_relays()).append('\n');
            sb.append("\n--- UDP ---\n").append(MainActivity.tox_get_all_udp_connections()).append('\n');
            sb.append("\n--- legacy ---\n").append(get_network_connections()).append('\n');
        }
        catch (Exception e)
        {
            sb.append("native_dump_error=").append(e.getMessage()).append('\n');
        }

        return sb.toString();
    }
}

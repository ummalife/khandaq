package com.zoffcc.applications.trifa;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.MainActivity.tox_friend_send_lossless_packet;
import static com.zoffcc.applications.trifa.MainActivity.tox_self_get_friend_list;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_self_connection_status;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.wakeup_tox_thread;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE;

/**
 * Runs alongside {@code tox_iterate()}: keepalive probes, silent-disconnect detection, transition logging.
 */
public final class ConnectionHealthMonitor
{
    private static final String TAG = "trifa.ConnHealth";

    /** Lossless packet id in custom range 160..191 (184 = group invite). */
    public static final int PKT_CONN_KEEPALIVE = 185;

    static final long TICK_INTERVAL_MS = 5_000L;
    static final long FRIEND_KEEPALIVE_INTERVAL_MS = 30_000L;
    static final long FRIEND_SILENT_PROBE_MS = 60_000L;
    static final int MAX_MISSED_KEEPALIVES = 3;

    private static final ConcurrentHashMap<Long, Long> friendLastActivityMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> friendLastKeepaliveSentMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> friendMissedKeepalives = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> friendLastLoggedConn = new ConcurrentHashMap<>();

    private static long lastTickMs = 0L;

    private ConnectionHealthMonitor()
    {
    }

    static void onFriendConnectionChanged(final long friendNumber, final int connectionStatus)
    {
        final Integer prev = friendLastLoggedConn.put(friendNumber, connectionStatus);
        if (prev == null || prev != connectionStatus)
        {
            NetworkDiagnosticsLog.log("friend_conn_transition",
                    "fn=" + friendNumber + " conn=" + connectionStatus);
            Log.i(TAG, "friend_conn fn=" + friendNumber + " conn=" + connectionStatus);
        }

        if (connectionStatus != TOX_CONNECTION_NONE.value)
        {
            noteFriendActivity(friendNumber);
        }
        else
        {
            friendMissedKeepalives.remove(friendNumber);
        }
    }

    static void onFriendActivity(final long friendNumber)
    {
        noteFriendActivity(friendNumber);
    }

    static void onKeepaliveReceived(final long friendNumber)
    {
        noteFriendActivity(friendNumber);
        friendMissedKeepalives.put(friendNumber, 0);
    }

    static void tick()
    {
        if (!is_tox_started)
        {
            return;
        }

        final long now = System.currentTimeMillis();
        if ((now - lastTickMs) < TICK_INTERVAL_MS)
        {
            return;
        }
        lastTickMs = now;

        if (global_self_connection_status == TOX_CONNECTION_NONE.value)
        {
            return;
        }

        tickFriendKeepalives(now);
    }

    private static void noteFriendActivity(final long friendNumber)
    {
        friendLastActivityMs.put(friendNumber, System.currentTimeMillis());
        friendMissedKeepalives.put(friendNumber, 0);
    }

    private static void tickFriendKeepalives(final long now)
    {
        final long[] friendNumbers;
        try
        {
            friendNumbers = tox_self_get_friend_list();
        }
        catch (Exception e)
        {
            return;
        }

        if (friendNumbers == null || friendNumbers.length == 0)
        {
            return;
        }

        for (long fn : friendNumbers)
        {
            if (fn < 0 || is_friend_online_real(fn) == 0)
            {
                continue;
            }

            final long lastActivity = friendLastActivityMs.getOrDefault(fn, now);
            final long lastSent = friendLastKeepaliveSentMs.getOrDefault(fn, 0L);
            final boolean dueForPing = (now - lastSent) >= FRIEND_KEEPALIVE_INTERVAL_MS;
            final boolean silentTooLong = (now - lastActivity) >= FRIEND_SILENT_PROBE_MS;

            if (!dueForPing && !silentTooLong)
            {
                continue;
            }

            if (sendKeepalive(fn))
            {
                friendLastKeepaliveSentMs.put(fn, now);
            }

            if (silentTooLong)
            {
                final int missed = friendMissedKeepalives.merge(fn, 1, (a, b) -> a + b);
                if (missed >= MAX_MISSED_KEEPALIVES)
                {
                    NetworkDiagnosticsLog.log("friend_silent_disconnect", "fn=" + fn + " missed=" + missed);
                    Log.i(TAG, "friend silent disconnect fn=" + fn + " — rebootstrap");
                    friendMissedKeepalives.put(fn, 0);
                    ReconnectBackoffCoordinator.get().scheduleReconnect(
                            ReconnectBackoffCoordinator.Reason.OFFLINE_PERIODIC, true);
                    wakeup_tox_thread();
                }
            }
        }
    }

    private static boolean sendKeepalive(final long friendNumber)
    {
        try
        {
            final byte[] packet = new byte[]{(byte) PKT_CONN_KEEPALIVE};
            final int res = tox_friend_send_lossless_packet(friendNumber, packet, packet.length);
            return res == 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }
}

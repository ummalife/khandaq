package com.zoffcc.applications.trifa;

import android.util.Log;

import java.util.Set;
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
    /**
     * KHANDAQ: flag in the second keepalive byte: this build reassembles chunked texts (MessageChunker).
     * Earlier builds send the bare 1-byte packet, and every build reads only data[0], so the extra byte is
     * ignored there. Those builds reassemble chunks and then lose the text, so they must not get any.
     */
    static final int KEEPALIVE_FLAG_REASSEMBLES_CHUNKS = 0x01;

    static final long TICK_INTERVAL_MS = 5_000L;
    static final long FRIEND_KEEPALIVE_INTERVAL_MS = 30_000L;
    static final long FRIEND_SILENT_PROBE_MS = 60_000L;
    static final int MAX_MISSED_KEEPALIVES = 3;

    private static final ConcurrentHashMap<Long, Long> friendLastActivityMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Long> friendLastKeepaliveSentMs = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> friendMissedKeepalives = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Integer> friendLastLoggedConn = new ConcurrentHashMap<>();
    /**
     * KHANDAQ: friends that sent us a keepalive since they last came online. Only Khandaq for Android sends
     * PKT_CONN_KEEPALIVE; iOS, desktop and other Tox clients never do, so their silence says nothing about
     * the link. Counting it anyway scheduled an urgent rebootstrap every 15 s for as long as such a friend
     * stayed online (an emulator with two iOS friends logged one per friend every 15 s).
     */
    private static final Set<Long> friendsSendingKeepalives = ConcurrentHashMap.newKeySet();
    /** KHANDAQ: friends whose last keepalive carried {@link #KEEPALIVE_FLAG_REASSEMBLES_CHUNKS}. */
    private static final Set<Long> friendsReassemblingChunks = ConcurrentHashMap.newKeySet();

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
            noteFriendOffline(friendNumber);
        }
    }

    static void onFriendActivity(final long friendNumber)
    {
        noteFriendActivity(friendNumber);
    }

    static void onKeepaliveReceived(final long friendNumber, final byte[] data, final int length)
    {
        friendsSendingKeepalives.add(friendNumber);
        if (data != null && length >= 2 && data.length >= 2 && (data[1] & KEEPALIVE_FLAG_REASSEMBLES_CHUNKS) != 0)
        {
            friendsReassemblingChunks.add(friendNumber);
        }
        else
        {
            friendsReassemblingChunks.remove(friendNumber);
        }
        noteFriendActivity(friendNumber);
        friendMissedKeepalives.put(friendNumber, 0);
    }

    /** Whether this friend runs Khandaq for Android: it sent us a keepalive since it last came online. */
    static boolean sendsKeepalives(final long friendNumber)
    {
        return friendsSendingKeepalives.contains(friendNumber);
    }

    /**
     * Whether this friend reassembles chunked texts: its keepalive since it last came online carried
     * {@link #KEEPALIVE_FLAG_REASSEMBLES_CHUNKS}. iOS, desktop and other Tox clients drop chunks, and Khandaq
     * for Android builds before the flag lose the reassembled text.
     */
    static boolean reassemblesChunks(final long friendNumber)
    {
        return friendsReassemblingChunks.contains(friendNumber);
    }

    /**
     * A friend that sends keepalives and has been quiet for {@link #FRIEND_SILENT_PROBE_MS}. A friend that
     * never sends them is never silent: it just does not speak this packet.
     */
    static boolean isSilent(final long friendNumber, final long now)
    {
        if (!sendsKeepalives(friendNumber))
        {
            return false;
        }
        final long lastActivity = friendLastActivityMs.getOrDefault(friendNumber, now);
        return (now - lastActivity) >= FRIEND_SILENT_PROBE_MS;
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

    static void noteFriendOffline(final long friendNumber)
    {
        friendMissedKeepalives.remove(friendNumber);
        // the friend number may come back as another client (a deleted friend's number is reused)
        friendsSendingKeepalives.remove(friendNumber);
        friendsReassemblingChunks.remove(friendNumber);
        // the first tick after it comes back sends our keepalive at once instead of up to 30 s later: each
        // side learns within a tick whether the other reassembles chunks
        friendLastKeepaliveSentMs.remove(friendNumber);
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

            final long lastSent = friendLastKeepaliveSentMs.getOrDefault(fn, 0L);
            final boolean dueForPing = (now - lastSent) >= FRIEND_KEEPALIVE_INTERVAL_MS;
            final boolean silentTooLong = isSilent(fn, now);

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
            final byte[] packet = new byte[]{(byte) PKT_CONN_KEEPALIVE, (byte) KEEPALIVE_FLAG_REASSEMBLES_CHUNKS};
            final int res = tox_friend_send_lossless_packet(friendNumber, packet, packet.length);
            // KHANDAQ: 1 = sent. Testing for 0 never recorded a sent keepalive, so every friend got one on
            // every 5 s tick instead of every 30 s.
            return HelperFriend.is_friend_lossless_packet_send_ok(res);
        }
        catch (Exception e)
        {
            return false;
        }
    }
}

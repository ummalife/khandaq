package com.zoffcc.applications.trifa;

import android.util.Log;

import com.zoffcc.applications.sorm.Message;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TrifaToxService.is_tox_started;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.TrifaToxService.wakeup_tox_thread;

/**
 * Flushes pending text, group sync, and file transfers after DHT / peer connectivity is restored.
 */
final class DeliveryRestoreCoordinator
{
    private static final String TAG = "trifa.DeliveryRestore";

    private DeliveryRestoreCoordinator()
    {
    }

    static void onConnectionRestored()
    {
        if (!is_tox_started || orma == null)
        {
            return;
        }

        NetworkDiagnosticsLog.log("delivery_restore", "start");
        Log.i(TAG, "onConnectionRestored");

        try
        {
            MessageDeliveryWatchdog.tick();
        }
        catch (Exception e)
        {
            Log.i(TAG, "watchdog:EE:" + e.getMessage());
        }

        try
        {
            HelperGroup.maintain_all_groups();
        }
        catch (Exception e)
        {
            Log.i(TAG, "maintain_groups:EE:" + e.getMessage());
        }

        try
        {
            HelperFiletransfer.resume_stalled_incoming_filetransfers();
        }
        catch (Exception e)
        {
            Log.i(TAG, "resume_incoming_ft:EE:" + e.getMessage());
        }

        flushQueuedOutgoingFiles();
        wakeup_tox_thread();
        NetworkDiagnosticsLog.log("delivery_restore", "done");
    }

    private static void flushQueuedOutgoingFiles()
    {
        try
        {
            final List<Message> queued = orma.selectFromMessage().
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    ft_outgoing_queuedEq(true).
                    orderBySent_timestampAsc().
                    limit(40).
                    toList();

            if (queued == null || queued.isEmpty())
            {
                return;
            }

            final Set<String> pubkeys = new HashSet<>();
            final Iterator<Message> it = queued.iterator();
            while (it.hasNext())
            {
                final Message m = it.next();
                if (m != null && m.tox_friendpubkey != null && !m.tox_friendpubkey.isEmpty())
                {
                    pubkeys.add(m.tox_friendpubkey);
                }
            }

            for (String pk : pubkeys)
            {
                HelperFiletransfer.try_start_next_queued_outgoing_file(pk);
            }

            Log.i(TAG, "flushQueuedOutgoingFiles:pubkeys=" + pubkeys.size());
        }
        catch (Exception e)
        {
            Log.i(TAG, "flushQueuedOutgoingFiles:EE:" + e.getMessage());
        }
    }
}

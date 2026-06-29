package com.zoffcc.applications.trifa;

import android.util.Log;

import com.zoffcc.applications.sorm.Message;

import java.util.List;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_RESUME;

/**
 * Limits simultaneous tox file transfers to avoid saturating weak links.
 */
final class FileTransferConcurrencyGate
{
    private static final String TAG = "trifa.FtConcurrency";

    /** Max active outgoing DM file transfers across all friends. */
    static final int MAX_CONCURRENT_OUTGOING = 2;

    private FileTransferConcurrencyGate()
    {
    }

    static boolean canStartOutgoingTransfer()
    {
        return countActiveOutgoingTransfers() < MAX_CONCURRENT_OUTGOING;
    }

    static int countActiveOutgoingTransfers()
    {
        try
        {
            final List<Message> active = orma.selectFromMessage().
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    ft_outgoing_startedEq(true).
                    stateEq(TOX_FILE_CONTROL_PAUSE.value).
                    limit(10).
                    toList();

            if (active == null)
            {
                return 0;
            }

            int count = 0;
            for (Message m : active)
            {
                if (m != null && m.filetransfer_id > 0)
                {
                    count++;
                }
            }
            return count;
        }
        catch (Exception e)
        {
            Log.i(TAG, "countActiveOutgoingTransfers:EE:" + e.getMessage());
            return 0;
        }
    }

    static boolean isTransferStillActive(final Message m)
    {
        if (m == null || !m.ft_outgoing_started)
        {
            return false;
        }

        return m.state == TOX_FILE_CONTROL_PAUSE.value || m.state == TOX_FILE_CONTROL_RESUME.value;
    }
}

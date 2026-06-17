package com.zoffcc.applications.trifa;

import android.util.Log;

import static com.zoffcc.applications.trifa.MainActivity.tox_friend_get_connection_status;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_is_connected;

final class FileTransferDebug
{
    static final String TAG = "FileTransfer";

    private FileTransferDebug()
    {
    }

    static void logUserSelectedFile(final String path, final long size)
    {
        Log.d(TAG, "1. User selected file: " + path + ", size: " + size);
    }

    static void logPeerConnectionStatus(final long friendNum)
    {
        final int status = friendNum >= 0 ? tox_friend_get_connection_status(friendNum) : -1;
        Log.d(TAG, "2. Peer connection status: " + status + " friend=" + friendNum);
    }

    static void logToxFileSendCall(final long friendNum, final long fileSize)
    {
        Log.d(TAG, "3. Calling tox_file_send(), friend=" + friendNum + ", fileSize=" + fileSize);
    }

    static void logToxFileSendResult(final long result)
    {
        Log.d(TAG, "4. tox_file_send() returned fileNum=" + result);
    }

    static void logFileChunkRequest(final long friend, final long file, final long position, final long length)
    {
        Log.d(TAG, "5. file_chunk_request_cb called: friend=" + friend + ", file=" + file
                + ", position=" + position + ", length=" + length);
    }

    static void logFileRecv(final long friend, final long file, final long fileSize, final String filename)
    {
        Log.d(TAG, "6. file_recv_cb called: friend=" + friend + ", file=" + file + ", size=" + fileSize
                + ", name=" + filename);
    }

    static void logFileControlResume(final long friend, final long file)
    {
        Log.d(TAG, "7. TOX_FILE_CONTROL_RESUME accept: friend=" + friend + ", file=" + file);
    }

    static void logQueueDecision(final long msgId, final boolean peerOnlineReal, final boolean queued)
    {
        Log.d(TAG, "Q. queue_and_try_send msgId=" + msgId + " peerOnlineReal=" + peerOnlineReal + " queued=" + queued);
    }

    static void logGroupConnection(final long groupNum)
    {
        Log.d(TAG, "G1. Group connection state: " + (groupNum >= 0 ? tox_group_is_connected(groupNum) : -1)
                + " group=" + groupNum);
    }

    static void logGroupCustomPacketSend(final long groupNum, final int dataLen)
    {
        Log.d(TAG, "G2. Sending custom packet, group=" + groupNum + ", dataLen=" + dataLen);
    }

    static void logGroupCustomPacketResult(final int result)
    {
        Log.d(TAG, "G3. Custom packet send result: " + result);
    }
}

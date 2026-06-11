package com.zoffcc.applications.trifa;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.zoffcc.applications.trifa.MainActivity.tox_friend_send_lossless_packet;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH;

/**
 * Chunked text delivery over lossless packets (182=data, 183=ack) for weak networks / long messages.
 */
public final class MessageChunker
{
    static final int PKT_CHUNK = 182;
    static final int PKT_CHUNK_ACK = 183;
    private static final int HEADER_BYTES = 1 + 8 + 2 + 2;

    private static final Map<String, IncomingAssembly> assemblies = new ConcurrentHashMap<>();

    private MessageChunker()
    {
    }

    static boolean shouldChunk(final String text)
    {
        if (text == null)
        {
            return false;
        }
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        final int limit = Math.min(ConnectionQualityMonitor.get().getAdaptiveChunkPayloadBytes() * 2,
                TOX_MSGV3_MAX_MESSAGE_LENGTH);
        return bytes.length > limit;
    }

    static boolean sendChunked(final long friendNumber, final String text)
    {
        try
        {
            final byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            final byte[] msgId = messageId8(payload);
            final int chunkPayload = ConnectionQualityMonitor.get().getAdaptiveChunkPayloadBytes();
            final int total = (payload.length + chunkPayload - 1) / chunkPayload;

            for (int seq = 0; seq < total; seq++)
            {
                final int offset = seq * chunkPayload;
                final int len = Math.min(chunkPayload, payload.length - offset);
                final byte[] packet = new byte[HEADER_BYTES + len];
                packet[0] = (byte) PKT_CHUNK;
                System.arraycopy(msgId, 0, packet, 1, 8);
                putU16(packet, 9, seq);
                putU16(packet, 11, total);
                System.arraycopy(payload, offset, packet, HEADER_BYTES, len);

                final int res = tox_friend_send_lossless_packet(friendNumber, packet, packet.length);
                if (res != 0)
                {
                    NetworkDiagnosticsLog.log("chunk_send_fail", "seq=" + seq + " res=" + res);
                    return false;
                }
            }
            NetworkDiagnosticsLog.log("chunk_send_ok", "chunks=" + total + " bytes=" + payload.length);
            return true;
        }
        catch (Exception e)
        {
            NetworkDiagnosticsLog.log("chunk_send_error", e.getMessage());
            return false;
        }
    }

    static void handleIncoming(final long friendNumber, final byte[] data, final int length)
    {
        if (data == null || length < HEADER_BYTES)
        {
            return;
        }
        final int pktId = data[0] & 0xff;
        if (pktId == PKT_CHUNK_ACK)
        {
            return;
        }
        if (pktId != PKT_CHUNK)
        {
            return;
        }

        final byte[] msgId = Arrays.copyOfRange(data, 1, 9);
        final int seq = getU16(data, 9);
        final int total = getU16(data, 11);
        final byte[] chunk = Arrays.copyOfRange(data, HEADER_BYTES, length);
        final String key = friendNumber + ":" + bytesToHex(msgId);

        IncomingAssembly asm = assemblies.get(key);
        if (asm == null)
        {
            asm = new IncomingAssembly(total);
            assemblies.put(key, asm);
        }
        asm.put(seq, chunk);

        sendAck(friendNumber, msgId, seq);

        if (asm.isComplete())
        {
            assemblies.remove(key);
            final String message = new String(asm.join(), StandardCharsets.UTF_8);
            HelperGeneric.receive_incoming_message(1, 0, friendNumber, message, null, 0, null, null, 0);
            NetworkDiagnosticsLog.log("chunk_recv_ok", "chunks=" + total);
        }
    }

    private static void sendAck(final long friendNumber, final byte[] msgId, final int seq)
    {
        final byte[] packet = new byte[HEADER_BYTES];
        packet[0] = (byte) PKT_CHUNK_ACK;
        System.arraycopy(msgId, 0, packet, 1, 8);
        putU16(packet, 9, seq);
        putU16(packet, 11, seq);
        tox_friend_send_lossless_packet(friendNumber, packet, packet.length);
    }

    private static byte[] messageId8(final byte[] payload) throws Exception
    {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final byte[] hash = md.digest(payload);
        return Arrays.copyOf(hash, 8);
    }

    private static void putU16(final byte[] buf, final int offset, final int value)
    {
        buf[offset] = (byte) ((value >> 8) & 0xff);
        buf[offset + 1] = (byte) (value & 0xff);
    }

    private static int getU16(final byte[] buf, final int offset)
    {
        return ((buf[offset] & 0xff) << 8) | (buf[offset + 1] & 0xff);
    }

    private static String bytesToHex(final byte[] bytes)
    {
        final StringBuilder sb = new StringBuilder();
        for (final byte b : bytes)
        {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static final class IncomingAssembly
    {
        private final byte[][] chunks;
        private int received;

        IncomingAssembly(final int total)
        {
            chunks = new byte[total][];
        }

        void put(final int seq, final byte[] data)
        {
            if (seq < 0 || seq >= chunks.length || chunks[seq] != null)
            {
                return;
            }
            chunks[seq] = data;
            received++;
        }

        boolean isComplete()
        {
            return received == chunks.length;
        }

        byte[] join()
        {
            int size = 0;
            for (final byte[] c : chunks)
            {
                size += c.length;
            }
            final byte[] out = new byte[size];
            int pos = 0;
            for (final byte[] c : chunks)
            {
                System.arraycopy(c, 0, out, pos, c.length);
                pos += c.length;
            }
            return out;
        }
    }
}

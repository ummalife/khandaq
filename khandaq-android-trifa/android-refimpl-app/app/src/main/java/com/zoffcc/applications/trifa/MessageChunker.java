package com.zoffcc.applications.trifa;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.zoffcc.applications.trifa.MainActivity.tox_friend_send_lossless_packet;
import static com.zoffcc.applications.trifa.ToxVars.MAX_CRYPTO_DATA_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH;

/**
 * Chunked text delivery over lossless packets (182=data, 183=ack) for weak networks / long messages.
 */
public final class MessageChunker
{
    static final int PKT_CHUNK = 182;
    static final int PKT_CHUNK_ACK = 183;
    private static final int HEADER_BYTES = 1 + 8 + 2 + 2;
    private static final int CHUNK_SEND_MAX_RETRIES = 3;
    private static final long CHUNK_RETRY_DELAY_MS = 200L;

    /**
     * KHANDAQ (audit2 #3): smallest chunk payload any shipped Khandaq sender emits
     * (ConnectionQualityMonitor.getAdaptiveChunkPayloadBytes(), WEAK tier). The chunk COUNT bound must be
     * derived with this value, because it is the tier that needs the most chunks for the same message.
     */
    private static final int CHUNK_PAYLOAD_MIN = 256;
    /**
     * KHANDAQ (audit2 #3): largest chunk payload a lossless packet can physically carry
     * (MAX_CRYPTO_DATA_SIZE minus our header). The adaptive sender never goes above 1024, so an honest
     * chunk always fits; anything larger is a forged length and is dropped before it is accounted for.
     */
    private static final int CHUNK_PAYLOAD_MAX = MAX_CRYPTO_DATA_SIZE - HEADER_BYTES;
    /**
     * KHANDAQ (audit2 #3): hard ceiling on one reassembled text message. The single-message limit is
     * TOX_MSGV3_MAX_MESSAGE_LENGTH (1334 B) and msgV2 text tops out at TOX_MESSAGEV2_MAX_TEXT_LENGTH
     * (4096 B); the chunker only exists to carry more than that, so the cap is set at 1 MiB — 786x the
     * single-message limit, 256x msgV2, and ~1 million characters of chat text. No honest client can
     * produce more (iOS caps its input at 1000 chars), so nothing a shipped sender emits is dropped.
     */
    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;
    /**
     * KHANDAQ (audit2 #3): chunks a real message can ever need = MAX_MESSAGE_BYTES at the smallest payload
     * a sender uses => 1048576 / 256 = 4096. `total` is attacker-chosen u16 (up to 65535) and used to be
     * turned into a byte[total][] on the very first packet, so 13-byte packets with fresh msg_ids allocated
     * ~256-512 KiB each, forever. Same spirit as NgcGroupFileTransfer.MAX_LEGIT_CHUNKS.
     */
    private static final int MAX_LEGIT_CHUNKS = MAX_MESSAGE_BYTES / CHUNK_PAYLOAD_MIN;
    /** Incomplete assemblies idle for longer than this can no longer belong to a message still arriving. */
    private static final long ASSEMBLY_IDLE_TTL_MS = 120_000L;
    /** In-flight incomplete messages one friend may hold: sendChunked is sequential, so 1 is honest; 4 is slack. */
    private static final int MAX_ASSEMBLIES_PER_PEER = 4;
    /** Hard ceiling over ALL peers (NgcGroupFileTransfer uses the same number for its incoming assemblies). */
    private static final int MAX_ASSEMBLIES_TOTAL = 24;
    /** Bytes held across all incomplete assemblies; the per-peer cap alone still allows 4 * 1 MiB per friend. */
    private static final long ASSEMBLY_TOTAL_BYTES_MAX = 8L * 1024L * 1024L;

    private static final Map<String, IncomingAssembly> assemblies = new ConcurrentHashMap<>();
    /** Bytes currently held in {@link #assemblies}. Guarded by {@code synchronized (assemblies)}. */
    private static long assembliesBytes = 0L;

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
            // KHANDAQ (audit2 #3): never emit what the receiver now refuses to assemble, and never let
            // `total` wrap the u16 header field (that produced a message that could never complete).
            if (payload.length > MAX_MESSAGE_BYTES)
            {
                NetworkDiagnosticsLog.log("chunk_send_too_big", "bytes=" + payload.length + " max=" + MAX_MESSAGE_BYTES);
                return false;
            }
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

                final int res = sendChunkWithRetry(friendNumber, packet);
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

    private static int sendChunkWithRetry(final long friendNumber, final byte[] packet)
    {
        for (int attempt = 0; attempt < CHUNK_SEND_MAX_RETRIES; attempt++)
        {
            final int res = tox_friend_send_lossless_packet(friendNumber, packet, packet.length);
            if (res == 0)
            {
                return 0;
            }
            if (attempt + 1 < CHUNK_SEND_MAX_RETRIES)
            {
                try
                {
                    Thread.sleep(CHUNK_RETRY_DELAY_MS * (attempt + 1));
                }
                catch (InterruptedException ignored)
                {
                    Thread.currentThread().interrupt();
                    return res;
                }
                TrifaToxService.wakeup_tox_thread();
            }
            else
            {
                return res;
            }
        }
        return -1;
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
        final int chunkLen = length - HEADER_BYTES;

        // KHANDAQ (audit2 #3): seq/total/len are bytes an accepted contact chose. Reject them BEFORE any
        // allocation: a 13-byte packet used to declare total=65535 and cost us a 65535-entry array.
        if (total < 1 || total > MAX_LEGIT_CHUNKS || seq < 0 || seq >= total || chunkLen < 1
            || chunkLen > CHUNK_PAYLOAD_MAX)
        {
            NetworkDiagnosticsLog.log("chunk_recv_bad_header",
                                      "fn=" + friendNumber + " seq=" + seq + " total=" + total + " len=" + chunkLen);
            return;
        }

        final byte[] chunk = Arrays.copyOfRange(data, HEADER_BYTES, length);
        final String key = friendNumber + ":" + bytesToHex(msgId);
        final long now = System.currentTimeMillis();

        final IncomingAssembly complete;
        synchronized (assemblies)
        {
            pruneIdleAssemblies(now);

            IncomingAssembly asm = assemblies.get(key);
            if (asm == null)
            {
                if (!makeRoomForNewAssembly(friendNumber))
                {
                    return;
                }
                asm = new IncomingAssembly(friendNumber, total, now);
                assemblies.put(key, asm);
            }
            else if (asm.totalChunks != total)
            {
                // The chunk count is part of the message identity; a peer changing it mid-message is forged.
                NetworkDiagnosticsLog.log("chunk_recv_total_mismatch", "had=" + asm.totalChunks + " got=" + total);
                return;
            }

            if (asm.bytes + chunkLen > MAX_MESSAGE_BYTES
                || assembliesBytes + chunkLen > ASSEMBLY_TOTAL_BYTES_MAX)
            {
                // Drop the whole message rather than keep half of it around: a flooder pays for its own bytes.
                dropAssembly(key);
                NetworkDiagnosticsLog.log("chunk_recv_over_quota",
                                          "fn=" + friendNumber + " msg_bytes=" + asm.bytes + " all_bytes=" +
                                          assembliesBytes);
                return;
            }

            if (asm.put(seq, chunk, now))
            {
                assembliesBytes += chunkLen;
            }

            if (asm.isComplete())
            {
                assemblies.remove(key);
                assembliesBytes -= asm.bytes;
                complete = asm;
            }
            else
            {
                complete = null;
            }
        }

        sendAck(friendNumber, msgId, seq);

        if (complete != null)
        {
            final String message = new String(complete.join(), StandardCharsets.UTF_8);
            HelperGeneric.receive_incoming_message(1, 0, friendNumber, message, null, 0, null, null, 0);
            NetworkDiagnosticsLog.log("chunk_recv_ok", "chunks=" + total);
        }
    }

    /** KHANDAQ (audit2 #3): incomplete assemblies had no TTL at all — they lived for the whole process. */
    private static void pruneIdleAssemblies(final long now)
    {
        for (final Map.Entry<String, IncomingAssembly> entry : assemblies.entrySet())
        {
            final IncomingAssembly asm = entry.getValue();
            if (asm != null && (now - asm.lastChunkTs) > ASSEMBLY_IDLE_TTL_MS)
            {
                assembliesBytes -= asm.bytes;
                assemblies.remove(entry.getKey());
                NetworkDiagnosticsLog.log("chunk_recv_expired",
                                          "fn=" + asm.friendNumber + " have=" + asm.chunks.size() + "/" +
                                          asm.totalChunks);
            }
        }
    }

    /**
     * KHANDAQ (audit2 #3): per-peer and global quotas. Evict the flooding peer's own oldest assembly first
     * (house style of NgcGroupFileTransfer.evictStaleAssemblies) so one peer cannot push out other chats.
     */
    private static boolean makeRoomForNewAssembly(final long friendNumber)
    {
        int guard = MAX_ASSEMBLIES_TOTAL + MAX_ASSEMBLIES_PER_PEER + 8;
        while (countAssembliesOfPeer(friendNumber) >= MAX_ASSEMBLIES_PER_PEER && guard-- > 0)
        {
            if (!dropAssembly(pickOldestAssemblyKey(friendNumber)))
            {
                return false;
            }
        }
        while (assemblies.size() >= MAX_ASSEMBLIES_TOTAL && guard-- > 0)
        {
            String victim = pickOldestAssemblyKey(friendNumber);
            if (victim == null)
            {
                victim = pickOldestAssemblyKey(null);
            }
            if (!dropAssembly(victim))
            {
                return false;
            }
        }
        return assemblies.size() < MAX_ASSEMBLIES_TOTAL;
    }

    private static int countAssembliesOfPeer(final long friendNumber)
    {
        int count = 0;
        for (final IncomingAssembly asm : assemblies.values())
        {
            if (asm != null && asm.friendNumber == friendNumber)
            {
                count++;
            }
        }
        return count;
    }

    /** Least recently fed assembly; {@code friendNumber != null} restricts the search to that peer. */
    private static String pickOldestAssemblyKey(final Long friendNumber)
    {
        String victimKey = null;
        long victimTs = Long.MAX_VALUE;
        for (final Map.Entry<String, IncomingAssembly> entry : assemblies.entrySet())
        {
            final IncomingAssembly asm = entry.getValue();
            if (asm == null || (friendNumber != null && asm.friendNumber != friendNumber))
            {
                continue;
            }
            if (asm.lastChunkTs < victimTs)
            {
                victimTs = asm.lastChunkTs;
                victimKey = entry.getKey();
            }
        }
        return victimKey;
    }

    private static boolean dropAssembly(final String key)
    {
        if (key == null)
        {
            return false;
        }
        final IncomingAssembly asm = assemblies.remove(key);
        if (asm == null)
        {
            return false;
        }
        assembliesBytes -= asm.bytes;
        return true;
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
        final long friendNumber;
        final int totalChunks;
        /**
         * KHANDAQ (audit2 #3): sparse on purpose. The old byte[total][] made the DECLARED count cost memory
         * up front; now a forged total=4096 costs nothing until the peer actually pays the bytes for it.
         */
        final Map<Integer, byte[]> chunks = new HashMap<>();
        /** Bytes actually stored — also the idle-TTL / quota accounting unit. */
        int bytes;
        long lastChunkTs;

        IncomingAssembly(final long friendNumber, final int totalChunks, final long now)
        {
            this.friendNumber = friendNumber;
            this.totalChunks = totalChunks;
            this.lastChunkTs = now;
        }

        /** @return true when the chunk was stored (a duplicate seq is ignored and must not be accounted). */
        boolean put(final int seq, final byte[] data, final long now)
        {
            lastChunkTs = now;
            if (seq < 0 || seq >= totalChunks || chunks.containsKey(seq))
            {
                return false;
            }
            chunks.put(seq, data);
            bytes += data.length;
            return true;
        }

        boolean isComplete()
        {
            return chunks.size() == totalChunks;
        }

        byte[] join()
        {
            final byte[] out = new byte[bytes];
            int pos = 0;
            // seq order is explicit here: the sparse map has no ordering of its own.
            for (int seq = 0; seq < totalChunks; seq++)
            {
                final byte[] c = chunks.get(seq);
                if (c == null)
                {
                    continue;
                }
                System.arraycopy(c, 0, out, pos, c.length);
                pos += c.length;
            }
            return out;
        }
    }
}

package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.zoffcc.applications.trifa.HelperGeneric.bytebuffer_to_hexstring;
import static com.zoffcc.applications.trifa.HelperGeneric.hex_to_bytes;
import static com.zoffcc.applications.trifa.HelperGeneric.write_chunk_to_VFS_file;
import static com.zoffcc.applications.trifa.HelperFiletransfer.get_incoming_filetransfer_local_filename;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isGroupMessageMediaReady;
import static com.zoffcc.applications.trifa.HelperFiletransfer.isIocipherVirtualPath;
import static com.zoffcc.applications.trifa.HelperGroup.mark_ngc_file_transfer_failed;
import static com.zoffcc.applications.trifa.HelperGroup.notify_group_file_send_progress_bytes;
import static com.zoffcc.applications.trifa.HelperGroup.notify_group_file_recv_progress_bytes;
import static com.zoffcc.applications.trifa.HelperGroup.record_ngc_transfer_started;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_is_connected;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_peer_id;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_packet;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_custom_private_packet;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_by_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_peer_get_connection_status;
import static com.zoffcc.applications.trifa.TRIFAGlobals.KHANDAQ_MAX_FILE_TRANSFER_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_last_activity_incoming_ft_ts;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_PREFIX;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_FILESIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_NGC_FILE_AND_HEADER_SIZE;

/**
 * Chunked NGC group file transfer (pkt 0x12 begin + 0x13 chunks) for files up to 200 MB.
 * Streams from disk — does not load the whole file into RAM.
 */
public final class NgcGroupFileTransfer
{
    private static final String TAG = "NgcGroupFileTransfer";

    static final byte PKT_FILE_BEGIN = 0x12;
    static final byte PKT_FILE_CHUNK = 0x13;
    /** Receiver asks the original sender to (re)transmit begin + chunks. */
    static final byte PKT_FILE_REQUEST = 0x14;

    static final int FILE_BEGIN_HEADER = 6 + 1 + 1 + 32 + 4 + 255 + 8 + 4 + 4;
    static final int FILE_CHUNK_HEADER = 6 + 1 + 1 + 32 + 4 + 4;
    static final int FILE_REQUEST_HEADER = 6 + 1 + 1 + 32 + 4;
    /** KHANDAQ (0.2.56): the most chunks one iPhone request lists (iOS kOCTNgcNackMaxIndicesPerPacket). */
    static final int FILE_REQUEST_MAX_LISTED_CHUNKS = 256;
    static final int CHUNK_PAYLOAD_MAX = TOX_MAX_NGC_FILE_AND_HEADER_SIZE - FILE_CHUNK_HEADER;

    private static final int MAX_ORPHAN_CHUNKS = 512;
    /**
     * KHANDAQ (audit A): chunks a real file can ever need — the largest file we accept, carried at the
     * largest payload a packet can hold. totalChunks/chunkPayload in a BEGIN are attacker-chosen bytes:
     * matching totalChunks against ceil(totalSize/chunkPayload) alone still lets chunkPayload=1 declare
     * 209_715_200 chunks for a "200 MB" file and OOM-kill us on the received[] allocation. Same bound as
     * iOS (kOCTNgcMaxFileTransferBytes / kOCTNgcChunkPayloadMax) and desktop (maxChunks): 200 MB / 36952
     * = 5676 chunks, so an honest 200 MB file still transfers.
     */
    private static final long MAX_LEGIT_CHUNKS =
            (KHANDAQ_MAX_FILE_TRANSFER_BYTES + CHUNK_PAYLOAD_MAX - 1) / CHUNK_PAYLOAD_MAX;
    /**
     * KHANDAQ (audit B): distinct msg_ids we buffer pre-BEGIN chunks for. The legitimate chunk-before-BEGIN
     * race is one transfer racing its own BEGIN by milliseconds; without a cap any peer grows the map by a
     * key (and ~37 KB per packet) per forged msg_id for the whole process lifetime. Mirrors iOS' 8.
     */
    private static final int MAX_ORPHAN_ASSEMBLIES = 8;
    /** Orphans older than this can no longer be racing a live BEGIN — drop them (iOS: 60 s). */
    private static final long ORPHAN_CHUNK_TTL_MS = 60_000L;
    /**
     * Hard ceiling over ALL orphan keys. The per-key cap alone allows 8 * 512 * ~37 KB. Honest orphans are
     * milliseconds old when their BEGIN lands, so shedding the globally OLDEST first drops a flooder's
     * backlog and leaves the racing transfer's freshly queued chunks intact.
     */
    private static final long ORPHAN_TOTAL_BYTES_MAX = 16L * 1024L * 1024L;
    /** Bytes currently held in {@link #orphanChunks}. Guarded by {@code synchronized (orphanChunks)}. */
    private static long orphanBytes = 0L;
    /**
     * KHANDAQ (audit B): in-memory incoming assemblies. Each costs a boolean[] plus a VFS file, and a peer
     * can open one per forged msg_id that never completes. Eviction is STATE-only — the partial file, its
     * .rxmap and the DB row all stay, so the transfer rebuilds and resumes via restoreAssemblyFromMessage.
     */
    private static final int MAX_INCOMING_ASSEMBLIES = 24;

    /**
     * KHANDAQ (0.2.56): keys (group:msg) of files whose own send is queued or running (sendGroupFileChunked,
     * sendGroupFileSmall), and of those a resend for one member's request is (handleIncomingFileRequest). Each kind
     * once per file. Apart, so that a resend that runs for one member cannot swallow the file's own send.
     */
    private static final java.util.Set<String> inflightOwnSends = ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> inflightServes = ConcurrentHashMap.newKeySet();
    /** Diagnostic: counts every incoming chunk packet that reaches the callback (delivery vs processing). */
    private static final java.util.concurrent.atomic.AtomicLong recvChunkPktCount =
            new java.util.concurrent.atomic.AtomicLong();
    /** Diagnostic: chunks dropped because they came from a peer that does not own the transfer. */
    private static final java.util.concurrent.atomic.AtomicLong rejectedForeignChunkCount =
            new java.util.concurrent.atomic.AtomicLong();
    /** While incoming chunks arrive faster than this, suppress resend requests (transfer is healthy). */
    private static final long INCOMING_PROGRESS_STALL_MS = 8_000L;

    /** KHANDAQ (0.2.56): one pace for every group file packet this device sends (NgcUploadPace). */
    private static final NgcUploadPace PACE = new NgcUploadPace();
    /** KHANDAQ (0.2.56): our group files that wait for a member to send them to (NgcOutgoingFileWaits). */
    private static final NgcOutgoingFileWaits WAITS = new NgcOutgoingFileWaits();
    /** KHANDAQ (0.2.56): how often each incoming file asked its sender for missing chunks (NgcFileNackBudget). */
    private static final NgcFileNackBudget NACKS = new NgcFileNackBudget();
    /**
     * KHANDAQ (0.2.56): a file whose group has had nobody to send to for this long, in the middle of its send, waits
     * for a member (WAITS) instead of going on. A member a moment away (a peer list read between two of toxcore's
     * updates) does not stop it.
     */
    private static final long NO_MEMBER_GRACE_MS = 5_000L;
    /**
     * KHANDAQ (0.2.56): every member refused the packet for this long: they are gone, or stuck. Not a live member
     * whose send array is full: toxcore drops a member whose oldest unacknowledged packet is GC_CONFIRMED_PEER_TIMEOUT
     * (58 s) old ("Send array stuck; timing out peer"), so its refusals end well within this. The file then waits for
     * a member like one that found nobody. It used to be said not sent after 20 s of refusals.
     */
    private static final long REFUSED_LIMIT_MS = 75_000L;
    /** A packet went to at least one member. */
    private static final int SEND_OK = 1;
    /** Nobody took the packet: no member to send to for NO_MEMBER_GRACE_MS, or refused by all for REFUSED_LIMIT_MS. */
    private static final int SEND_NO_MEMBER = -1;
    /** The file was cancelled, or the thread interrupted. */
    private static final int SEND_STOPPED = -2;
    /**
     * KHANDAQ (0.2.56): g_opts key prefix ("kqngcsend_" + group|msg) of our group files that have not gone whole yet,
     * in the key/value table the cancel tombstone uses (no schema change). Set when a file's send begins, removed
     * once it went whole; a restart brings the files that still have it back to wait for a member.
     */
    private static final String PENDING_SEND_PREFIX = "kqngcsend_";
    /** keyLike pattern of PENDING_SEND_PREFIX: its "_" escaped, as LIKE reads it as any character. */
    private static final String PENDING_SEND_LIKE = "kqngcsend\\_%";
    /** Keys (group|msg) of our pending files: those marked in this run, and those the database had at its start. */
    private static final java.util.Set<String> pendingSends = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicBoolean pendingSendsLoadQueued =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile boolean pendingSendsLoaded = false;
    /** The database work of the send states (pending marks, finding a waiting file's row): off the tox thread. */
    private static final ExecutorService STATE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "ngc-group-file-state");
        t.setDaemon(true);
        return t;
    });

    /** Outgoing FT targets: one requester peer (resend) or fan-out to every online peer. */
    private static final class PeerSendTargets
    {
        /** {@code true} unless this is a FILE_REQUEST resend to a specific peer. */
        final boolean fanOutAllOnline;
        long[] peerIds;

        PeerSendTargets(final long[] peerIds, final boolean fanOutAllOnline)
        {
            this.peerIds = peerIds != null ? peerIds : new long[0];
            this.fanOutAllOnline = fanOutAllOnline;
        }

        void refreshOnlinePeers(final long groupNum)
        {
            if (groupNum < 0)
            {
                return;
            }
            if (fanOutAllOnline)
            {
                peerIds = HelperGroup.pickChunkedFileUnicastPeerIds(groupNum);
            }
            else if (peerIds.length > 0 && !isGroupPeer(groupNum, peerIds[0]))
            {
                // KHANDAQ (0.2.56): the member who asked has left: nobody to serve.
                peerIds = new long[0];
            }
        }

        boolean hasPrivatePeer()
        {
            return peerIds.length > 0;
        }

        String describe()
        {
            if (peerIds.length == 0)
            {
                return "broadcast";
            }
            if (peerIds.length == 1)
            {
                return "peer=" + peerIds[0];
            }
            final StringBuilder sb = new StringBuilder("peers=");
            for (int i = 0; i < peerIds.length; i++)
            {
                if (i > 0)
                {
                    sb.append(',');
                }
                sb.append(peerIds[i]);
            }
            return sb.toString();
        }
    }

    /** Whether the peer is still in the group's peer list (toxcore lists the peers it has not timed out). */
    static boolean isGroupPeer(final long groupNum, final long peerId)
    {
        try
        {
            final long[] peers = MainActivity.tox_group_get_peerlist(groupNum);
            if (peers != null)
            {
                for (final long p : peers)
                {
                    if (p == peerId)
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return false;
    }

    /** How many user-initiated files upload in parallel (they share one tox connection but interleave chunks). */
    private static final int MAX_PARALLEL_USER_SENDS = 3;

    /** User-initiated chunked sends — small pool so multiple files upload concurrently instead of one-at-a-time. */
    private static final ExecutorService SEND_EXECUTOR = Executors.newFixedThreadPool(MAX_PARALLEL_USER_SENDS, r -> {
        final Thread t = new Thread(r, "ngc-group-file-send");
        t.setDaemon(true);
        return t;
    });

    /**
     * KHANDAQ (0.2.56): our files of one packet (sendGroupFileSmall), on a thread of their own. They went on
     * SEND_EXECUTOR, and a chunked send holds a thread of it for the whole of its paced send (half an hour for 100 MB
     * to eight members): a photo waited behind three big files, its row saying "sending" all that time. Its one packet
     * still waits for the pace, which it shares with the chunked sends, but not for a whole file.
     */
    private static final ExecutorService SMALL_SEND_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "ngc-group-file-send-small");
        t.setDaemon(true);
        return t;
    });

    /** FILE_REQUEST resends (background recovery / cross-group history-sync) — single thread, lower priority. */
    private static final ExecutorService RESEND_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "ngc-group-file-resend");
        t.setDaemon(true);
        return t;
    });

    /** >0 while a user-initiated send is running — background resends defer to it to keep the tox node free. */
    private static final java.util.concurrent.atomic.AtomicInteger activeUserSends =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Global rate-limit for servicing incoming resend requests so background sync can't saturate transport. */
    private static final java.util.concurrent.atomic.AtomicLong lastResendServiceTs =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long RESEND_SERVICE_THROTTLE_MS = 4_000L;
    /** Per-message cooldown for selective (NACK) resends — bypasses the global full-resend throttle. */
    private static final Map<String, Long> selectiveResendTs = new ConcurrentHashMap<>();
    private static final long SELECTIVE_RESEND_COOLDOWN_MS = 2_500L;
    /** Guards {@code tox_group_reconnect} so a mid-transfer flap can't reset the lossless session repeatedly. */
    private static final Map<String, Long> lastGroupReconnectTs = new ConcurrentHashMap<>();
    private static final long GROUP_RECONNECT_MIN_INTERVAL_MS = 25_000L;

    private static final Map<String, IncomingAssembly> assemblies = new ConcurrentHashMap<>();
    private static final Map<String, List<OrphanChunk>> orphanChunks = new ConcurrentHashMap<>();
    /** Receives that finished — set the instant the last chunk lands so a late BEGIN/chunk/NACK/timeout
     *  can't spawn a phantom "all missing" re-download (which showed a false "Download failed"). */
    private static final java.util.Set<String> completedReceives = ConcurrentHashMap.newKeySet();

    private static String receiveDoneKey(final String groupId, final String msgIdHash)
    {
        return groupId.toLowerCase(Locale.ROOT) + ":" + msgIdHash.toLowerCase(Locale.ROOT);
    }

    static boolean isReceiveAlreadyComplete(final String groupId, final String msgIdHash)
    {
        return groupId != null && msgIdHash != null
                && completedReceives.contains(receiveDoneKey(groupId, msgIdHash));
    }

    static void markReceiveComplete(final String groupId, final String msgIdHash)
    {
        if (groupId != null && msgIdHash != null)
        {
            completedReceives.add(receiveDoneKey(groupId, msgIdHash));
        }
    }

    private NgcGroupFileTransfer()
    {
    }

    static boolean shouldUseChunkedTransfer(final long fileSize)
    {
        return fileSize > TOX_MAX_NGC_FILESIZE;
    }

    /**
     * The file-chunk admission rule, as a pure predicate so it can be tested without a Tox stack or VFS.
     *
     * KHANDAQ (internal audit L-1): {@code chunkIndex} and {@code chunkSize} are u32 fields read straight
     * out of a group packet, so they are fully attacker-chosen — and {@code readU32Be} hands back an int,
     * which is NEGATIVE for anything above 2^31. Every bound below has to hold before the chunk is copied
     * out of the packet or written into the file:
     * <ul>
     *   <li>index inside the count the BEGIN declared (a negative index would also index backwards);</li>
     *   <li>the bytes are actually present in this packet;</li>
     *   <li>the chunk is no larger than the payload size the BEGIN declared;</li>
     *   <li>index*payload + size stays inside the declared file size — the bound that was missing, which
     *       let a chunk write past the end of the file it claimed to belong to.</li>
     * </ul>
     * An honest sender always emits {@code chunkSize <= chunkPayload} and a short final chunk, so nothing
     * a shipped client produces is rejected here.
     *
     * @param chunkIndex   declared 0-based chunk index (may be negative — see above)
     * @param chunkSize    declared payload bytes of this chunk (may be negative — see above)
     * @param packetLength total bytes actually received in this packet, header included
     * @param totalChunks  chunk count declared by the BEGIN packet for this transfer
     * @param chunkPayload payload size per chunk declared by the BEGIN packet
     * @param totalSize    file size declared by the BEGIN packet
     */
    static boolean isValidFileChunkHeader(final int chunkIndex, final int chunkSize, final int packetLength,
                                          final int totalChunks, final int chunkPayload, final long totalSize)
    {
        if (chunkIndex < 0 || chunkIndex >= totalChunks || chunkSize < 1)
        {
            return false;
        }
        if ((FILE_CHUNK_HEADER + chunkSize) > packetLength || chunkSize > chunkPayload)
        {
            return false;
        }
        // long arithmetic on purpose: index and payload are both bounded above, but their product is not
        // guaranteed to fit an int, and an overflow here would wrap into a passing comparison.
        final long chunkOffset = (long) chunkIndex * (long) chunkPayload;
        return (chunkOffset + (long) chunkSize) <= totalSize;
    }

    static boolean isReceiveComplete(final String groupId, final String msgIdHash)
    {
        if ((groupId == null) || (msgIdHash == null))
        {
            return false;
        }
        if (isReceiveAlreadyComplete(groupId, msgIdHash))
        {
            return true;
        }

        final GroupMessage message = HelperGroup.find_group_message_by_msg_id_hash(groupId, msgIdHash);
        return (message != null) && isGroupMessageMediaReady(message, null);
    }

    static boolean hasIncomingAssemblyInProgress(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        try
        {
            final long groupNum = tox_group_by_groupid__wrapper(groupId);
            if (groupNum < 0)
            {
                return false;
            }
            byte[] msgId = hex_to_bytes(msgIdHash);
            if (msgId.length != 32)
            {
                final byte[] fixed = new byte[32];
                System.arraycopy(msgId, 0, fixed, 0, Math.min(msgId.length, 32));
                msgId = fixed;
            }
            final IncomingAssembly asm = assemblies.get(assemblyKey(groupNum, msgId));
            if (asm == null || asm.receivedCount <= 0 || asm.receivedCount >= asm.totalChunks)
            {
                return false;
            }
            // Suppress resend only while chunks are ACTIVELY arriving; allow it once the flow stalls.
            return (System.currentTimeMillis() - asm.lastChunkTs) < INCOMING_PROGRESS_STALL_MS;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static void prepareRetryReceive(final GroupMessage message)
    {
        if (message == null || message.msg_id_hash == null || message.group_identifier == null)
        {
            return;
        }

        try
        {
            byte[] msgId = hex_to_bytes(message.msg_id_hash);
            if (msgId.length != 32)
            {
                final byte[] fixed = new byte[32];
                System.arraycopy(msgId, 0, fixed, 0, Math.min(msgId.length, 32));
                msgId = fixed;
            }

            final long groupNum = tox_group_by_groupid__wrapper(message.group_identifier);
            if (groupNum < 0)
            {
                return;
            }

            final String groupId = message.group_identifier.toLowerCase(Locale.ROOT);
            final String assemblyKey = assemblyKey(groupNum, msgId);

            // NON-DESTRUCTIVE retry: keep any in-progress assembly and already-written chunks.
            // The sender re-emits the whole file on FILE_REQUEST; the receiver fills only the gaps
            // (duplicates are skipped). Wiping here reset multi-MB downloads to 0 every retry cycle.
            if (assemblies.get(assemblyKey) == null)
            {
                restoreAssemblyFromMessage(groupNum, groupId, msgId, message);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "prepareRetryReceive:EE:" + e.getMessage());
        }
    }

    static void sendGroupFileChunked(final GroupMessage g)
    {
        enqueueChunkedSend(g, -1L, null);
    }

    /** @param targetPeerId {@code >= 0} sends via private packet to that peer (FILE_REQUEST resend path). */
    static void sendGroupFileChunked(final GroupMessage g, final long targetPeerId)
    {
        enqueueChunkedSend(g, targetPeerId, null);
    }

    /** Selective resend: {@code wanted[i] == true} sends only chunk {@code i} (receiver's missing set). */
    static void sendGroupFileChunkedSelective(final GroupMessage g, final long targetPeerId, final boolean[] wanted)
    {
        enqueueChunkedSend(g, targetPeerId, wanted);
    }

    /**
     * One in-flight (queued or running) send per message and kind — receiver resend requests arrive every ~12s and
     * would otherwise pile up full re-sends on the single executor, starving freshly picked user files.
     */
    private static void enqueueChunkedSend(final GroupMessage g, final long targetPeerId, final boolean[] wanted)
    {
        if (g == null || g.group_identifier == null || g.msg_id_hash == null)
        {
            return;
        }
        // KHANDAQ (audit2 #5): single gate in front of BEGIN + every chunk worker. The cancel is
        // persisted now, so this also holds for the first send attempt after an app restart.
        if (HelperGroup.is_ngc_send_cancelled(g.group_identifier, g.msg_id_hash))
        {
            HelperGeneric.logI(TAG, "send:skip cancelled msg=" + g.msg_id_hash.substring(0, 8));
            return;
        }
        final String key = beginResendKey(g.group_identifier, g.msg_id_hash);
        final boolean isUserSend = targetPeerId < 0L;
        // KHANDAQ (0.2.56): a resend for one member's request is not needed while the file's own send goes to every
        // member, and must never take the place of that send: the file then stayed unsent with nothing to send it.
        final boolean queued = isUserSend
                ? inflightOwnSends.add(key)
                : (!inflightOwnSends.contains(key) && inflightServes.add(key));
        if (!queued)
        {
            HelperGeneric.logI(TAG, "send:skip already in-flight msg=" + g.msg_id_hash.substring(0, 8));
            return;
        }
        if (isUserSend)
        {
            WAITS.remove(g.group_identifier, g.msg_id_hash);
        }
        final ExecutorService exec = isUserSend ? SEND_EXECUTOR : RESEND_EXECUTOR;
        if (isUserSend)
        {
            activeUserSends.incrementAndGet();
        }
        try
        {
            exec.execute(() ->
            {
                try
                {
                    if (wanted != null)
                    {
                        sendGroupFileSelectiveOnWorker(g, targetPeerId, wanted);
                    }
                    else
                    {
                        sendGroupFileChunkedOnWorker(g, targetPeerId);
                    }
                }
                finally
                {
                    (isUserSend ? inflightOwnSends : inflightServes).remove(key);
                    if (isUserSend)
                    {
                        activeUserSends.decrementAndGet();
                    }
                }
            });
        }
        catch (RuntimeException e)
        {
            (isUserSend ? inflightOwnSends : inflightServes).remove(key);
            if (isUserSend)
            {
                activeUserSends.decrementAndGet();
                waitForMember(g, "send not queued: " + e.getMessage());
            }
        }
    }

    static void sendGroupFileSmall(final GroupMessage g)
    {
        if (g == null || g.group_identifier == null || g.msg_id_hash == null)
        {
            return;
        }
        final String key = beginResendKey(g.group_identifier, g.msg_id_hash);
        if (!inflightOwnSends.add(key))
        {
            HelperGeneric.logI(TAG, "send:skip already in-flight msg=" + g.msg_id_hash.substring(0, 8));
            return;
        }
        WAITS.remove(g.group_identifier, g.msg_id_hash);
        try
        {
            SMALL_SEND_EXECUTOR.execute(() ->
            {
                try
                {
                    // KHANDAQ (audit2 #5): small files never pass through the chunk sender, so the
                    // cancellation gate has to sit here — checked at emit time, not at enqueue time.
                    if (HelperGroup.is_ngc_send_cancelled(g.group_identifier, g.msg_id_hash))
                    {
                        return;
                    }
                    HelperGroup.send_group_image(g);
                }
                finally
                {
                    inflightOwnSends.remove(key);
                }
            });
        }
        catch (RuntimeException e)
        {
            inflightOwnSends.remove(key);
            waitForMember(g, "send not queued: " + e.getMessage());
        }
    }

    /**
     * KHANDAQ (0.2.56): the send of a file of more than one packet: its BEGIN, then its chunks, each to every member
     * at the pace (NgcUploadPace).
     *
     * <p>The file's own send ({@code targetPeerId < 0}) says how the file stands: it goes, it waits for a member to
     * send it to (WAITS), it went, or it failed for a reason of this device (no file, too large, no such group).
     * Before, a group with nobody else online was waited for 45 s, the BEGIN 20 s more, and the file said not sent;
     * and a resend pushed its chunks back to back until toxcore refused them, then said not sent again after 20 s.
     *
     * <p>A resend for one member's request ({@code targetPeerId >= 0}) serves that member and leaves the file's state
     * alone: it reported progress and "failed" for a file that had gone, and its chunks moved the resume point of the
     * file's own send.
     */
    private static void sendGroupFileChunkedOnWorker(final GroupMessage g, final long targetPeerId)
    {
        final boolean ownSend = targetPeerId < 0L;
        try
        {
            TrifaToxService.wakeup_tox_thread();

            final OutgoingFileSource src = openOutgoingFileSource(g);
            if (src == null || src.size < 1L)
            {
                HelperGeneric.logI(TAG, "send:missing file " + g.filename_fullpath);
                failOwnSend(g, ownSend);
                return;
            }
            try (InputStream in = src.stream)
            {
                sendChunkedFrom(g, targetPeerId, ownSend, in, src.size);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "send:EE:" + e.getMessage());
            if (g != null)
            {
                failOwnSend(g, ownSend);
            }
        }
    }

    private static void sendChunkedFrom(final GroupMessage g, final long targetPeerId, final boolean ownSend,
                                        final InputStream in, final long fileSize) throws IOException
    {
        if (fileSize > KHANDAQ_MAX_FILE_TRANSFER_BYTES)
        {
            HelperGeneric.logI(TAG, "send:file too large " + fileSize);
            failOwnSend(g, ownSend);
            return;
        }

        final long groupNum = tox_group_by_groupid__wrapper(g.group_identifier);
        if (groupNum < 0)
        {
            failOwnSend(g, ownSend);
            return;
        }

        FileTransferDebug.logGroupConnection(groupNum);

        final PeerSendTargets sendTargets = ownSend
                ? new PeerSendTargets(HelperGroup.pickChunkedFileUnicastPeerIds(groupNum), true)
                : new PeerSendTargets(new long[]{targetPeerId}, false);
        if (!sendTargets.hasPrivatePeer())
        {
            // KHANDAQ (0.2.56): nobody else in the group, or nobody online: the file waits for a member.
            endUnsent(g, ownSend, SEND_NO_MEMBER, "send");
            return;
        }
        HelperGeneric.logI(TAG, "send:unicast " + sendTargets.describe() + (ownSend ? "" : " (request)"));
        for (final long peerId : sendTargets.peerIds)
        {
            // KHANDAQ (#126): best-effort 2s nudge only — the real reachability test is the
            // private-send return code; don't burn 15s/peer on a NONE status.
            waitForPeerOnline(groupNum, peerId, 2000L);
        }

        if (ownSend)
        {
            record_ngc_transfer_started(g.group_identifier, g.msg_id_hash);
            notify_group_file_send_progress_bytes(g.group_identifier, g.msg_id_hash, 0L, fileSize);
        }

        byte[] msgId;
        try
        {
            msgId = hex_to_bytes(g.msg_id_hash);
        }
        catch (Exception e)
        {
            msgId = new byte[32];
        }
        if (msgId.length != 32)
        {
            final byte[] fixed = new byte[32];
            System.arraycopy(msgId, 0, fixed, 0, Math.min(msgId.length, 32));
            msgId = fixed;
        }

        final int totalChunks = (int) ((fileSize + CHUNK_PAYLOAD_MAX - 1) / CHUNK_PAYLOAD_MAX);
        final int lastSentChunk = ownSend
                ? HelperGroup.ngc_outgoing_last_chunk_index(g.group_identifier, g.msg_id_hash) : -1;
        final int startChunk = lastSentChunk + 1;
        if (startChunk >= totalChunks)
        {
            notify_group_file_send_progress_bytes(g.group_identifier, g.msg_id_hash, fileSize, fileSize);
            HelperGroup.clear_ngc_outgoing_send_resume_state(g.group_identifier, g.msg_id_hash);
            outgoingSent(g);
            HelperGeneric.logI(TAG, "send:already complete chunks=" + totalChunks);
            return;
        }

        // KHANDAQ (0.2.56): the BEGIN goes first, on a resume too: a member that came since has nothing of the file,
        // and one that has a part of it keeps that part (a BEGIN of the same file never wipes an assembly). Without it
        // a resumed send's chunks reached nobody who could keep them.
        final PacedSend send = new PacedSend(groupNum, sendTargets, g,
                                             buildBeginPacket(msgId, g.file_name, fileSize, totalChunks));
        final int beginRes = send.begin();
        if (beginRes != SEND_OK)
        {
            endUnsent(g, ownSend, beginRes, "BEGIN");
            return;
        }

        if (startChunk > 0)
        {
            HelperGeneric.logI(TAG, "send:resume from chunk " + startChunk + " last=" + lastSentChunk);
            notify_group_file_send_progress_bytes(g.group_identifier, g.msg_id_hash,
                                                  Math.min(fileSize, (long) startChunk * CHUNK_PAYLOAD_MAX), fileSize);
        }

        final byte[] readBuf = new byte[CHUNK_PAYLOAD_MAX];
        if (startChunk > 0)
        {
            skipFully(in, (long) startChunk * CHUNK_PAYLOAD_MAX, readBuf);
        }

        for (int idx = startChunk; idx < totalChunks; idx++)
        {
            final int toRead = (int) Math.min(CHUNK_PAYLOAD_MAX, fileSize - (long) idx * CHUNK_PAYLOAD_MAX);
            try
            {
                readFully(in, readBuf, toRead);
            }
            catch (IOException e)
            {
                HelperGeneric.logI(TAG, "send:unexpected EOF at chunk " + idx);
                failOwnSend(g, ownSend);
                return;
            }

            final int res = send.chunk(buildChunkPacket(msgId, idx, readBuf, toRead));
            if (res != SEND_OK)
            {
                endUnsent(g, ownSend, res, "chunk " + idx);
                return;
            }

            if (ownSend)
            {
                HelperGroup.record_ngc_outgoing_chunk_sent(g.group_identifier, g.msg_id_hash, idx);
                final long bytesDone = Math.min(fileSize, (long) (idx + 1) * CHUNK_PAYLOAD_MAX);
                notify_group_file_send_progress_bytes(g.group_identifier, g.msg_id_hash, bytesDone, fileSize);
            }
            TrifaToxService.wakeup_tox_thread();
        }

        if (ownSend)
        {
            notify_group_file_send_progress_bytes(g.group_identifier, g.msg_id_hash, fileSize, fileSize);
            HelperGroup.clear_ngc_outgoing_send_resume_state(g.group_identifier, g.msg_id_hash);
            outgoingSent(g);
        }
        HelperGeneric.logI(TAG, "send:done chunks=" + totalChunks + " bytes=" + fileSize
                + (ownSend ? "" : " (request of peer " + targetPeerId + ")"));
    }

    /** A packet did not go: an own send waits for a member when nobody took it; a stop (cancel) changes nothing. */
    private static void endUnsent(final GroupMessage g, final boolean ownSend, final int res, final String what)
    {
        if (res == SEND_NO_MEMBER)
        {
            HelperGeneric.logI(TAG, "send:" + what + " went to nobody msg=" + g.msg_id_hash.substring(0, 8)
                    + (ownSend ? "" : " (request)"));
            if (ownSend)
            {
                waitForMember(g, "no member took the " + what);
            }
        }
        else
        {
            HelperGeneric.logI(TAG, "send:stopped at " + what + " msg=" + g.msg_id_hash.substring(0, 8));
        }
    }

    /** An own send failed for a reason of this device: the file is said not sent, and its "Send again" tries it. */
    private static void failOwnSend(final GroupMessage g, final boolean ownSend)
    {
        if (ownSend && g != null)
        {
            mark_ngc_file_transfer_failed(g.group_identifier, g.msg_id_hash);
        }
    }

    static void handleIncomingBegin(final long groupNumber, final long peerId, final byte[] data, final int length)
    {
        if (length < FILE_BEGIN_HEADER || data[7] != PKT_FILE_BEGIN)
        {
            return;
        }

        if (isSelfGroupPeer(groupNumber, peerId))
        {
            return;
        }

        try
        {
            final byte[] msgId = Arrays.copyOfRange(data, 8, 40);
            final String assemblyKey = assemblyKey(groupNumber, msgId);
            final String displayFilename = resolveDisplayFilename(data, 44, 255);
            final long totalSize = readU64Be(data, 299);
            final int chunkPayload = readU32Be(data, 307);
            final int totalChunks = readU32Be(data, 311);

            // Validate the scalars BEFORE deriving anything from them — chunkPayload is the divisor below.
            if (totalSize < 1 || totalSize > KHANDAQ_MAX_FILE_TRANSFER_BYTES || totalChunks < 1
                    || chunkPayload < 1 || chunkPayload > CHUNK_PAYLOAD_MAX)
            {
                HelperGeneric.logI(TAG, "handleBegin:reject totalSize=" + totalSize + " chunks="
                        + totalChunks + " payload=" + chunkPayload);
                return;
            }
            // KHANDAQ (audit A): the declared count must be exactly the one the (already size-capped) file
            // implies AND no larger than a real file can need — otherwise a single BEGIN with chunkPayload=1
            // sizes received[] from an attacker-chosen 32-bit count and OOM-kills the app. See MAX_LEGIT_CHUNKS.
            final long expectedChunks = (totalSize + chunkPayload - 1L) / chunkPayload;
            if (totalChunks != expectedChunks || expectedChunks > MAX_LEGIT_CHUNKS)
            {
                HelperGeneric.logI(TAG, "handleBegin:reject chunks=" + totalChunks + " expected="
                        + expectedChunks + " max=" + MAX_LEGIT_CHUNKS + " payload=" + chunkPayload);
                return;
            }

            final String groupIdRaw = HelperGroup.tox_group_by_groupnum__wrapper(groupNumber);
            if (groupIdRaw == null)
            {
                return;
            }
            final String groupId = groupIdRaw.toLowerCase(Locale.ROOT);
            final String msgIdHex = bytebuffer_to_hexstring(ByteBuffer.wrap(msgId), true);

            if (isReceiveAlreadyComplete(groupId, msgIdHex))
            {
                return;
            }

            final String beginFromPubkey = peerPubkeyOf(groupNumber, peerId);

            final IncomingAssembly existingAsm = assemblies.get(assemblyKey);
            boolean replaceExisting = false;
            if (existingAsm != null)
            {
                // KHANDAQ (audit #5): msg_id_hash is public inside the group, so any member can forge a
                // BEGIN for someone else's transfer. While chunks are actually flowing, only the declared
                // sender may touch the assembly — a foreign BEGIN with a different size wiped the victim's
                // progress. Once the flow has stalled we do NOT refuse: a peer that grabs the msg_id and
                // then goes quiet must never be able to lock the genuine sender out of it for good.
                if (!isBoundSender(existingAsm, beginFromPubkey, peerId))
                {
                    if (isReceivingActively(existingAsm))
                    {
                        HelperGeneric.logI(TAG, "handleBegin:drop foreign peer=" + peerId + " msg="
                                + msgIdHex.substring(0, 8));
                        return;
                    }
                    HelperGeneric.logI(TAG, "handleBegin:rebind stalled assembly to peer=" + peerId + " msg="
                            + msgIdHex.substring(0, 8) + " progress=" + existingAsm.receivedCount + "/"
                            + existingAsm.totalChunks);
                    // Deferred: the checks below can still reject this BEGIN (see replaceExisting), and
                    // isBoundSender does not bind — nothing here touches existingAsm until they all pass.
                    replaceExisting = true;
                }
                else if (existingAsm.totalSize == totalSize && existingAsm.totalChunks == totalChunks)
                {
                    // Same file already being assembled — NEVER wipe progress on a duplicate BEGIN
                    // (resend requests make the sender re-emit BEGIN; resetting here lost all prior chunks).
                    HelperGeneric.logI(TAG, "handleBegin:dup-skip msg=" + msgIdHex.substring(0, 8)
                            + " progress=" + existingAsm.receivedCount + "/" + existingAsm.totalChunks);
                    // KHANDAQ (0.2.56): its sender is there: the file is not silent (NgcFileNackBudget). Its requests
                    // stay counted, as an answer to each of them starts with this BEGIN.
                    NACKS.heard(receiveDoneKey(groupId, msgIdHex));
                    flushOrphanChunks(groupNumber, assemblyKey);
                    return;
                }
                else
                {
                    // Different size/chunks for the same msgId, from the sender this assembly belongs to —
                    // genuinely stale, replace it right away (as before).
                    assemblies.remove(assemblyKey);
                    dropOrphanList(assemblyKey);
                }
            }

            final GroupMessage dbMessage = HelperGroup.find_group_message_by_msg_id_hash(groupId, msgIdHex);
            if (dbMessage != null)
            {
                if (dbMessage.direction == 1)
                {
                    return;
                }
                if (isGroupMessageMediaReady(dbMessage, null))
                {
                    return;
                }
                // KHANDAQ (audit #5): the row already names who sent this file, and only that peer ever
                // serves it (handleIncomingFileRequest answers just for its own messages). A BEGIN from
                // anyone else would fill the file with content of their choosing while the row keeps the
                // original sender's name. "-1" on either side is "unknown", not an identity to match.
                if (isRealPubkey(dbMessage.tox_group_peer_pubkey) && isRealPubkey(beginFromPubkey)
                        && !dbMessage.tox_group_peer_pubkey.equalsIgnoreCase(beginFromPubkey))
                {
                    HelperGeneric.logI(TAG, "handleBegin:drop foreign-origin peer=" + peerId + " msg="
                            + msgIdHex.substring(0, 8));
                    return;
                }
            }

            // KHANDAQ (#120): the sender broadcasts (pushes) file chunks to all online peers. When the
            // attachment-download policy forbids auto-download (and the user has not tapped to fetch this
            // file), record a not-downloaded message so it shows with a download affordance, but do NOT
            // create an assembly — the pushed chunks then have no target and are dropped. A later manual
            // tap sets the bypass marker and re-requests the file (sender re-emits BEGIN), which downloads.
            if (!HelperGroup.ngc_incoming_download_allowed(groupId, msgIdHex))
            {
                if (dbMessage == null)
                {
                    final IncomingAssembly meta = new IncomingAssembly();
                    meta.displayFilename = displayFilename;
                    meta.filename = get_incoming_filetransfer_local_filename(displayFilename, groupId);
                    meta.totalSize = totalSize;
                    meta.totalChunks = totalChunks;
                    meta.chunkPayload = chunkPayload;
                    meta.outPath = VFS_PREFIX + VFS_FILE_DIR + "/" + groupId + "/" + meta.filename;
                    meta.msgId = msgId;
                    meta.groupId = groupId;
                    meta.groupNumber = groupNumber;
                    meta.peerId = peerId;
                    meta.senderPubkey = beginFromPubkey;
                    HelperGroup.handle_incoming_group_file_begin(groupNumber, peerId, groupId, msgId, meta);
                    // handle_incoming_group_file_begin seeds progress=0 ("0% downloading"); undo it so the
                    // row renders as not-downloaded (snapshot pct<0 → FAILED → download affordance).
                    HelperGroup.clear_ngc_file_transfer_progress(groupId, msgIdHex);
                }
                return;
            }

            if (replaceExisting)
            {
                // Drop the old assembly only HERE: every check above can still reject this BEGIN, and a
                // rejected BEGIN must not cost the running transfer its state.
                assemblies.remove(assemblyKey);
                dropOrphanList(assemblyKey);
            }

            final IncomingAssembly asm = createAssembly(groupNumber, peerId, groupId, msgId, msgIdHex,
                    displayFilename, totalSize, chunkPayload, totalChunks, dbMessage);
            if (asm == null)
            {
                return;
            }

            putAssembly(assemblyKey, asm);

            HelperGeneric.logI(TAG, "handleBegin:recv gn=" + groupNumber + " peer=" + peerId + " msg="
                    + msgIdHex.substring(0, 8) + " size=" + totalSize + " chunks=" + totalChunks);

            record_ngc_transfer_started(groupId, msgIdHex);
            notify_group_file_recv_progress_bytes(groupId, msgIdHex, 0L, totalSize);
            // KHANDAQ (0.2.56): the file starts here, with all its requests for missing chunks (NgcFileNackBudget).
            NACKS.restart(receiveDoneKey(groupId, msgIdHex));

            if (dbMessage == null)
            {
                HelperGroup.handle_incoming_group_file_begin(groupNumber, peerId, groupId, msgId, asm);
            }
            else
            {
                HelperGroup.update_incoming_group_file_metadata(dbMessage, asm);
            }

            HelperGroup.arm_ngc_incoming_file_timeout(groupId, msgIdHex);
            flushOrphanChunks(groupNumber, assemblyKey);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "handleBegin:EE:" + e.getMessage());
        }
    }

    static void handleIncomingChunk(final long groupNumber, final long peerId, final byte[] data, final int length)
    {
        if (length < FILE_CHUNK_HEADER + 1 || data[7] != PKT_FILE_CHUNK)
        {
            return;
        }

        if (isSelfGroupPeer(groupNumber, peerId))
        {
            return;
        }

        try
        {
            final byte[] msgId = Arrays.copyOfRange(data, 8, 40);
            final int dbgIdx = readU32Be(data, 40);
            final long dbgTotal = recvChunkPktCount.incrementAndGet();
            if (dbgIdx < 5 || (dbgIdx & 63) == 0 || (dbgTotal & 127L) == 0)
            {
                HelperGeneric.logI(TAG, "handleChunk:rawpkt idx=" + dbgIdx + " len=" + length
                        + " totalRecvPkts=" + dbgTotal);
            }
            final String assemblyKey = assemblyKey(groupNumber, msgId);
            IncomingAssembly asm = assemblies.get(assemblyKey);
            if (asm == null)
            {
                final String groupIdRaw = HelperGroup.tox_group_by_groupnum__wrapper(groupNumber);
                if (groupIdRaw != null)
                {
                    final String groupId = groupIdRaw.toLowerCase(Locale.ROOT);
                    final String msgIdHex = bytebuffer_to_hexstring(ByteBuffer.wrap(msgId), true);
                    if (isReceiveAlreadyComplete(groupId, msgIdHex))
                    {
                        return;
                    }
                    // KHANDAQ (#120): drop pushed chunks for files the user has not opted to download
                    // (policy = Никогда / Wi-Fi-only off Wi-Fi). Do NOT restore an assembly from the DB
                    // record here, or the broadcast would silently resume the download.
                    if (!HelperGroup.ngc_incoming_download_allowed(groupId, msgIdHex))
                    {
                        return;
                    }
                    final GroupMessage dbMessage = HelperGroup.find_group_message_by_msg_id_hash(groupId, msgIdHex);
                    if (dbMessage != null && dbMessage.direction != 1 && !isGroupMessageMediaReady(dbMessage, null))
                    {
                        asm = restoreAssemblyFromMessage(groupNumber, groupId, msgId, dbMessage);
                        if (asm != null)
                        {
                            assemblies.put(assemblyKey, asm);
                            HelperGroup.arm_ngc_incoming_file_timeout(groupId, msgIdHex);
                        }
                    }
                }

                if (asm == null)
                {
                    queueOrphanChunk(assemblyKey, peerId, peerPubkeyOf(groupNumber, peerId), data, length);
                    return;
                }
            }

            // Resolve the delivering peer only once we know we keep the chunk — peer_ids get reassigned,
            // the pubkey is the stable identity. Doing it above would cost a JNI call plus a string per
            // broadcast chunk of a file this peer never downloads (manual / Wi-Fi-only policy).
            applyChunk(groupNumber, peerId, peerPubkeyOf(groupNumber, peerId), assemblyKey, asm, msgId,
                       data, length);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "handleChunk:EE:" + e.getMessage());
        }
    }

    /**
     * KHANDAQ: a peer pubkey we may bind a transfer to. The JNI hands back the literal string "-1"
     * (== TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY) when the toxcore lookup fails, and system-message rows carry
     * that same "-1" — it means "no identity", never a peer that owns something.
     */
    private static boolean isRealPubkey(final String pubkey)
    {
        return (pubkey != null) && !pubkey.isEmpty() && !TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(pubkey);
    }

    /** Peer pubkey for a group peer number, or {@code null} when it cannot be resolved. */
    private static String peerPubkeyOf(final long groupNumber, final long peerId)
    {
        if (peerId < 0)
        {
            return null;
        }
        try
        {
            final String pubkey = HelperGroup.tox_group_peer_get_public_key__wrapper(groupNumber, peerId);
            return isRealPubkey(pubkey) ? pubkey : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /** Chunks are still landing for this assembly — same "healthy transfer" window the NACK timer uses. */
    private static boolean isReceivingActively(final IncomingAssembly asm)
    {
        return (asm.receivedCount > 0)
                && ((System.currentTimeMillis() - asm.lastChunkTs) < INCOMING_PROGRESS_STALL_MS);
    }

    /**
     * KHANDAQ (audit #5): the peer this assembly was declared for — the BEGIN sender, or the peer the
     * stored row names when we resumed from the DB. The msg_id travels in every group message, so without
     * this any member could push chunks into another member's transfer and corrupt the file they receive.
     * PURE predicate — it must stay free of side effects: handleIncomingBegin calls it before checks that
     * can still reject the BEGIN, and a rejected BEGIN must never re-bind the assembly to its sender.
     * Binding happens where a packet is accepted (createAssembly / applyChunk's adopt block).
     * NOTE: callers must never turn a "false" here into a permanent refusal — see their comments.
     */
    private static boolean isBoundSender(final IncomingAssembly asm, final String fromPubkey, final long fromPeerId)
    {
        if (isRealPubkey(asm.senderPubkey) && isRealPubkey(fromPubkey))
        {
            return asm.senderPubkey.equalsIgnoreCase(fromPubkey);
        }
        // No identity to compare (lookup failed for one side, or nothing was ever bound) — fall back to the
        // peer number seen at BEGIN, which is all we have.
        return asm.peerId < 0 || asm.peerId == fromPeerId;
    }

    private static void applyChunk(final long groupNumber, final long peerId, final String fromPubkey,
                                   final String assemblyKey, IncomingAssembly asm, final byte[] msgId,
                                   final byte[] data, final int length)
    {
        // KHANDAQ (audit #5): drop packets from anyone but the peer this transfer belongs to — ONLY the
        // foreign packet, the real transfer keeps running (no failure marking, no timeout reset), so an
        // injector cannot kill the download either.
        boolean adoptSender = false;
        if (!isBoundSender(asm, fromPubkey, peerId))
        {
            // Idle time is NOT a reason to open a bound assembly: a pause of INCOMING_PROGRESS_STALL_MS is
            // routine on mobile (and a DB-restored assembly starts at lastChunkTs = 0, i.e. always looks
            // stalled), so any member could otherwise append bytes to someone else's download — permanently,
            // since received[idx] then blocks the honest resend of that index. The genuine sender is not
            // locked out: a full resend always leads with a BEGIN, which re-binds a stalled assembly in
            // handleIncomingBegin (itself guarded by the DB-row origin check).
            if (isReceivingActively(asm) || isRealPubkey(asm.senderPubkey))
            {
                final long n = rejectedForeignChunkCount.incrementAndGet();
                if ((n & 63L) == 1L)
                {
                    HelperGeneric.logI(TAG, "handleChunk:drop foreign peer=" + peerId + " dropped=" + n);
                }
                return;
            }
            // No identity was ever established here (pubkey lookup failed at BEGIN and the stored row names
            // nobody) — bind to the peer that actually delivers, once the packet proves itself valid below.
            adoptSender = true;
        }
        else if (!isRealPubkey(asm.senderPubkey) && isRealPubkey(fromPubkey))
        {
            // Same assembly without an identity, but from the peer number we already had: record its pubkey
            // now that it resolves, so later peer_id reassignment cannot re-attribute the transfer.
            adoptSender = true;
        }

        final int chunkIndex = readU32Be(data, 40);
        final int chunkSize = readU32Be(data, 44);
        // KHANDAQ (internal audit L-1): chunkSize was bounded only by the packet length, so a chunk
        // could carry more than the BEGIN declared as the payload size and write past the declared
        // end of the file — the stored file then disagreed with its own metadata. iOS and desktop
        // have had both bounds for a while; this brings Android in line. An honest sender always
        // sends chunkSize <= chunkPayload and a short final chunk, so nothing legitimate is rejected.
        if (!isValidFileChunkHeader(chunkIndex, chunkSize, length, asm.totalChunks, asm.chunkPayload,
                                    asm.totalSize))
        {
            HelperGeneric.logI(TAG, "handleChunk:reject idx=" + chunkIndex + " size=" + chunkSize
                    + " len=" + length + " total=" + asm.totalChunks);
            return;
        }

        if (adoptSender)
        {
            if (isRealPubkey(fromPubkey))
            {
                asm.senderPubkey = fromPubkey;
            }
            asm.peerId = peerId;
            HelperGeneric.logI(TAG, "handleChunk:bind unowned assembly to peer=" + peerId);
        }

        if (asm.received[chunkIndex])
        {
            // KHANDAQ (0.2.56): its sender sends the file again (a send that waited for us goes again from its first
            // chunk): that is not silence, and the requests for missing chunks wait until it stops. A big file that
            // came again was otherwise given up while its first part came, and spent its requests on a sender busy
            // sending (NgcFileNackBudget).
            final String dupMsgIdHex = bytebuffer_to_hexstring(ByteBuffer.wrap(msgId), true);
            NACKS.heard(receiveDoneKey(asm.groupId, dupMsgIdHex));
            HelperGroup.arm_ngc_incoming_file_timeout(asm.groupId, dupMsgIdHex);
            return;
        }

        final long offset = (long) chunkIndex * asm.chunkPayload;
        final byte[] chunkData = Arrays.copyOfRange(data, FILE_CHUNK_HEADER, FILE_CHUNK_HEADER + chunkSize);
        writeChunk(asm, offset, chunkSize, chunkData);

        asm.received[chunkIndex] = true;
        asm.receivedCount++;
        asm.bytesReceived += chunkSize;
        asm.lastChunkTs = System.currentTimeMillis();

        global_last_activity_incoming_ft_ts = System.currentTimeMillis();

        final String msgIdHex = bytebuffer_to_hexstring(ByteBuffer.wrap(msgId), true);
        // KHANDAQ (0.2.56): a new chunk: the file is not silent (NgcFileNackBudget), its requests stay counted.
        NACKS.heard(receiveDoneKey(asm.groupId, msgIdHex));
        if ((chunkIndex & 127) == 0 || chunkIndex + 1 >= asm.totalChunks)
        {
            HelperGeneric.logI(TAG, "handleChunk:recv msg=" + msgIdHex.substring(0, 8) + " idx=" + chunkIndex
                    + "/" + asm.totalChunks + " bytes=" + asm.bytesReceived);
        }
        HelperGroup.notify_group_file_recv_progress_bytes(asm.groupId, msgIdHex, asm.bytesReceived, asm.totalSize);
        HelperGroup.arm_ngc_incoming_file_timeout(asm.groupId, msgIdHex);

        if (asm.receivedCount >= asm.totalChunks)
        {
            markReceiveComplete(asm.groupId, msgIdHex);
            NACKS.forget(receiveDoneKey(asm.groupId, msgIdHex));
            // Persist the buffered tail to disk NOW, else the file looks short after restart and re-downloads.
            HelperGeneric.flush_close_vfs_write_cache(asm.outPath);
            deleteReceivedBitmap(asm.outPath);
            assemblies.remove(assemblyKey);
            dropOrphanList(assemblyKey);
            HelperGroup.disarm_ngc_incoming_file_timeout(asm.groupId, msgIdHex);
            HelperGroup.clear_ngc_file_transfer_failed(asm.groupId, msgIdHex);
            HelperGroup.handle_incoming_group_file_complete(groupNumber, peerId, asm);
        }
        else if ((asm.receivedCount % 16) == 0)
        {
            // Snapshot progress so a restart resumes from here instead of re-downloading from scratch.
            persistReceivedBitmap(asm);
        }
    }

    private static IncomingAssembly createAssembly(final long groupNumber, final long peerId, final String groupId,
                                                   final byte[] msgId, final String msgIdHex,
                                                   final String displayFilename, final long totalSize,
                                                   final int chunkPayload, final int totalChunks,
                                                   final GroupMessage dbMessage)
    {
        final String localName = get_incoming_filetransfer_local_filename(displayFilename, groupId);
        final String vfsDir = VFS_PREFIX + VFS_FILE_DIR + "/" + groupId + "/";
        String outPath = vfsDir + localName;

        if (dbMessage != null && dbMessage.filename_fullpath != null && !dbMessage.filename_fullpath.isEmpty())
        {
            outPath = dbMessage.filename_fullpath;
        }

        if (!isIocipherVirtualPath(outPath))
        {
            final java.io.File plainOut = new java.io.File(outPath);
            final java.io.File plainDir = plainOut.getParentFile();
            if (plainDir != null)
            {
                plainDir.mkdirs();
            }
            if (!plainOut.isFile())
            {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(plainOut))
                {
                    fos.write(new byte[0]);
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "createAssembly:open plain:" + e.getMessage());
                    return null;
                }
            }
        }
        else
        {
            final info.guardianproject.iocipher.File outDir = new info.guardianproject.iocipher.File(vfsDir);
            outDir.mkdirs();
            final info.guardianproject.iocipher.File outFile = new info.guardianproject.iocipher.File(outPath);
            if (!outFile.exists())
            {
                try (info.guardianproject.iocipher.FileOutputStream fos =
                             new info.guardianproject.iocipher.FileOutputStream(outFile))
                {
                    fos.write(new byte[0]);
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "createAssembly:open:" + e.getMessage());
                    return null;
                }
            }
        }

        final IncomingAssembly asm = new IncomingAssembly();
        asm.displayFilename = displayFilename;
        asm.filename = localName;
        asm.totalSize = totalSize;
        asm.totalChunks = totalChunks;
        asm.chunkPayload = chunkPayload;
        asm.outPath = outPath;
        asm.received = new boolean[totalChunks];
        asm.msgId = msgId;
        asm.groupId = groupId;
        asm.groupNumber = groupNumber;
        asm.peerId = peerId;
        asm.createdTs = System.currentTimeMillis();
        // KHANDAQ: lock in the sender's pubkey now (peerId is valid for the actual sender at this
        // moment); later peer_id reassignment must not re-attribute the transfer to someone else.
        if (peerId >= 0)
        {
            asm.senderPubkey = peerPubkeyOf(groupNumber, peerId);
        }
        if (!isRealPubkey(asm.senderPubkey))
        {
            // KHANDAQ (audit #5): resumed from the DB (no live BEGIN peer) — the stored row already names
            // the peer that sent this file, and only that peer ever (re)sends its chunks. Bind to it so the
            // resumed transfer is protected too, instead of accepting chunks from whoever answers first.
            // A row holding "-1" (system message / failed lookup) names nobody and must not be bound to.
            if ((dbMessage != null) && (dbMessage.direction == 0)
                    && isRealPubkey(dbMessage.tox_group_peer_pubkey))
            {
                asm.senderPubkey = dbMessage.tox_group_peer_pubkey;
            }
        }
        // Restore partial progress persisted in a previous session so we resume instead of re-downloading.
        loadReceivedBitmap(asm);
        return asm;
    }

    /**
     * KHANDAQ (0.2.56): the write cache (HelperGeneric.write_chunk_to_VFS_file) opens its file with a FileOutputStream
     * the first time this process writes to it, and that empties the file (O_TRUNC). A download resumed after a
     * restart from its .rxmap kept as zeros every chunk its map said it had, and came out whole and wrong (QA live
     * 2026-11-15: 77 of 86 chunks zero, sha256 different). Such a file is written in place while no cache holds it.
     */
    private static void writeChunk(final IncomingAssembly asm, final long offset, final int size, final byte[] data)
    {
        if (bypassesWriteCache(asm.resumedFromDisk, HelperGeneric.has_vfs_write_cache(asm.outPath)))
        {
            HelperGeneric.write_chunk_to_VFS_file__no_extra_write_cache(asm.outPath, offset, size, data);
            return;
        }
        write_chunk_to_VFS_file(asm.outPath, offset, size, data);
    }

    /**
     * KHANDAQ (0.2.56): a file with chunks on disk from before this process is written in place; a cache this
     * process opened holds only what it wrote itself, so it keeps the cache.
     */
    static boolean bypassesWriteCache(final boolean resumedFromDisk, final boolean cacheOpen)
    {
        return resumedFromDisk && !cacheOpen;
    }

    // ---- Persistent received-chunk bitmap (resume partial downloads across app restarts) ----

    private static final int RXMAP_MAGIC = 0x52584d31; // "RXM1"
    private static final int RXMAP_HEADER = 4 + 4 + 8;  // magic + totalChunks + totalSize

    private static String rxMapPath(final String outPath)
    {
        return outPath + ".rxmap";
    }

    private static void persistReceivedBitmap(final IncomingAssembly asm)
    {
        if (asm == null || asm.outPath == null || asm.received == null || asm.totalChunks < 1)
        {
            return;
        }
        try
        {
            // Flush the data buffer FIRST so the bitmap only ever marks chunks that are durably on disk.
            HelperGeneric.flush_vfs_write_cache(asm.outPath);
            final int n = asm.totalChunks;
            final byte[] out = new byte[RXMAP_HEADER + (n + 7) / 8];
            final ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
            bb.putInt(RXMAP_MAGIC);
            bb.putInt(n);
            bb.putLong(asm.totalSize);
            for (int i = 0; i < n; i++)
            {
                if (asm.received[i])
                {
                    out[RXMAP_HEADER + (i >> 3)] |= (byte) (1 << (i & 7));
                }
            }
            writeRxMapFile(rxMapPath(asm.outPath), out);
        }
        catch (Exception ignored)
        {
        }
    }

    private static void loadReceivedBitmap(final IncomingAssembly asm)
    {
        if (asm == null || asm.outPath == null || asm.received == null || asm.totalChunks < 1)
        {
            return;
        }
        try
        {
            final byte[] data = readRxMapFile(rxMapPath(asm.outPath));
            if (data == null || data.length < RXMAP_HEADER)
            {
                return;
            }
            final ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            if (bb.getInt() != RXMAP_MAGIC)
            {
                return;
            }
            final int n = bb.getInt();
            final long sz = bb.getLong();
            if (n != asm.totalChunks || sz != asm.totalSize)
            {
                return; // stale map for a different file at the same path
            }
            if (data.length < RXMAP_HEADER + (n + 7) / 8)
            {
                return;
            }
            int count = 0;
            long bytes = 0;
            for (int i = 0; i < n; i++)
            {
                if ((data[RXMAP_HEADER + (i >> 3)] & (1 << (i & 7))) != 0)
                {
                    asm.received[i] = true;
                    count++;
                    bytes += Math.min((long) asm.chunkPayload, asm.totalSize - (long) i * asm.chunkPayload);
                }
            }
            asm.receivedCount = count;
            asm.bytesReceived = bytes;
            asm.resumedFromDisk = count > 0;
            // Leave lastChunkTs = 0 so the assembly reads as "stalled" and the first selective NACK
            // fires immediately to resume, instead of being suppressed as "in progress".
            if (count > 0)
            {
                HelperGeneric.logI(TAG, "rxmap:restored " + count + "/" + n + " bytes=" + bytes);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static void deleteReceivedBitmap(final String outPath)
    {
        if (outPath == null)
        {
            return;
        }
        final String p = rxMapPath(outPath);
        try
        {
            if (isIocipherVirtualPath(p))
            {
                new info.guardianproject.iocipher.File(p).delete();
            }
            else
            {
                new java.io.File(p).delete();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static void writeRxMapFile(final String p, final byte[] data) throws IOException
    {
        if (isIocipherVirtualPath(p))
        {
            try (info.guardianproject.iocipher.FileOutputStream fos =
                         new info.guardianproject.iocipher.FileOutputStream(
                                 new info.guardianproject.iocipher.File(p)))
            {
                fos.write(data);
                fos.flush();
            }
        }
        else
        {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(p))
            {
                fos.write(data);
                fos.flush();
            }
        }
    }

    private static byte[] readRxMapFile(final String p)
    {
        try
        {
            if (isIocipherVirtualPath(p))
            {
                final info.guardianproject.iocipher.File f = new info.guardianproject.iocipher.File(p);
                if (!f.exists() || f.length() <= 0)
                {
                    return null;
                }
                final int len = (int) f.length();
                final byte[] buf = new byte[len];
                try (info.guardianproject.iocipher.FileInputStream fis =
                             new info.guardianproject.iocipher.FileInputStream(f))
                {
                    int off = 0;
                    while (off < len)
                    {
                        final int r = fis.read(buf, off, len - off);
                        if (r < 0)
                        {
                            break;
                        }
                        off += r;
                    }
                }
                return buf;
            }
            final java.io.File f = new java.io.File(p);
            if (!f.isFile() || f.length() <= 0)
            {
                return null;
            }
            final int len = (int) f.length();
            final byte[] buf = new byte[len];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(f))
            {
                int off = 0;
                while (off < len)
                {
                    final int r = fis.read(buf, off, len - off);
                    if (r < 0)
                    {
                        break;
                    }
                    off += r;
                }
            }
            return buf;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /** Persist the in-progress received bitmap for a transfer (called when a transfer stalls). */
    static void persistIncomingProgress(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        try
        {
            final long groupNum = tox_group_by_groupid__wrapper(groupId);
            if (groupNum < 0)
            {
                return;
            }
            byte[] msgId = hex_to_bytes(msgIdHash);
            if (msgId.length != 32)
            {
                final byte[] fixed = new byte[32];
                System.arraycopy(msgId, 0, fixed, 0, Math.min(msgId.length, 32));
                msgId = fixed;
            }
            final IncomingAssembly asm = assemblies.get(assemblyKey(groupNum, msgId));
            if (asm != null && asm.receivedCount > 0 && asm.receivedCount < asm.totalChunks)
            {
                persistReceivedBitmap(asm);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static IncomingAssembly restoreAssemblyFromMessage(final long groupNumber, final String groupId,
                                                               final byte[] msgId, final GroupMessage message)
    {
        // KHANDAQ (audit A): the stored filesize sizes received[] exactly like the BEGIN does, so bound it
        // the same way — the send path never produces a group file above KHANDAQ_MAX_FILE_TRANSFER_BYTES.
        if (message == null || message.filesize < 1 || message.filesize > KHANDAQ_MAX_FILE_TRANSFER_BYTES)
        {
            return null;
        }

        final int chunkPayload = CHUNK_PAYLOAD_MAX;
        final int totalChunks = (int) ((message.filesize + chunkPayload - 1) / chunkPayload);
        final String displayFilename = resolveDisplayFilenameFromMessage(message);
        final String msgIdHex = bytebuffer_to_hexstring(ByteBuffer.wrap(msgId), true);

        final IncomingAssembly asm = createAssembly(groupNumber, -1L, groupId, msgId, msgIdHex,
                displayFilename, message.filesize, chunkPayload, totalChunks, message);
        if (asm == null)
        {
            return null;
        }

        putAssembly(assemblyKey(groupNumber, msgId), asm);
        return asm;
    }

    /**
     * KHANDAQ (audit B): bound the number of in-memory assemblies. Anything evicted here keeps its partial
     * file, its .rxmap (flushed first) and its DB row, so the next chunk / NACK rebuilds it and the download
     * resumes where it stopped — no message, file or unread state is lost.
     */
    private static void putAssembly(final String assemblyKey, final IncomingAssembly asm)
    {
        evictStaleAssemblies(assemblyKey, isRealPubkey(asm.senderPubkey) ? asm.senderPubkey : null);
        assemblies.put(assemblyKey, asm);
    }

    private static void evictStaleAssemblies(final String keepKey, final String incomingSenderPubkey)
    {
        int guard = MAX_INCOMING_ASSEMBLIES + 8;
        while (assemblies.size() >= MAX_INCOMING_ASSEMBLIES && guard-- > 0)
        {
            // Make the peer that is filling the map pay first: evict its own least recently fed transfer
            // before touching anyone else's (same idea as the orphan-key eviction).
            String victim = pickEvictableAssemblyKey(keepKey, incomingSenderPubkey);
            if (victim == null)
            {
                victim = pickEvictableAssemblyKey(keepKey, null);
            }
            if (victim == null)
            {
                return;
            }
            final IncomingAssembly stale = assemblies.remove(victim);
            if (stale == null)
            {
                continue;
            }
            if (stale.receivedCount > 0)
            {
                persistReceivedBitmap(stale); // keep the bytes already downloaded
            }
            dropOrphanList(victim);
            HelperGeneric.logI(TAG, "assemblies:evict " + victim + " progress=" + stale.receivedCount
                    + "/" + stale.totalChunks);
        }
    }

    /** Least recently fed assembly; {@code senderPubkey != null} restricts the search to that sender. */
    private static String pickEvictableAssemblyKey(final String keepKey, final String senderPubkey)
    {
        String victimKey = null;
        long victimTs = Long.MAX_VALUE;
        for (final Map.Entry<String, IncomingAssembly> entry : assemblies.entrySet())
        {
            final IncomingAssembly a = entry.getValue();
            if (a == null || entry.getKey().equals(keepKey))
            {
                continue;
            }
            if (senderPubkey != null
                    && !(isRealPubkey(a.senderPubkey) && senderPubkey.equalsIgnoreCase(a.senderPubkey)))
            {
                continue;
            }
            // createdTs keeps a freshly opened (still chunk-less) assembly from looking infinitely stale.
            final long ts = Math.max(a.lastChunkTs, a.createdTs);
            if (ts < victimTs)
            {
                victimTs = ts;
                victimKey = entry.getKey();
            }
        }
        return victimKey;
    }

    private static void queueOrphanChunk(final String assemblyKey, final long peerId, final String fromPubkey,
                                         final byte[] data, final int length)
    {
        final long now = System.currentTimeMillis();

        synchronized (orphanChunks)
        {
            pruneExpiredOrphanChunks(now);

            List<OrphanChunk> list = orphanChunks.get(assemblyKey);
            if (list == null)
            {
                // KHANDAQ (audit B): a NEW key is the only growth an attacker fully controls (one per
                // forged msg_id). Cap the keys and evict a key fed by THIS peer first, so a flooder sheds
                // its own backlog before an honest transfer that is racing its BEGIN loses anything.
                while (orphanChunks.size() >= MAX_ORPHAN_ASSEMBLIES)
                {
                    String staleKey = leastRecentlyFedOrphanKey(fromPubkey, peerId, true);
                    if (staleKey == null)
                    {
                        staleKey = leastRecentlyFedOrphanKey(null, -1L, false);
                    }
                    if (staleKey == null)
                    {
                        return;
                    }
                    removeOrphanListLocked(staleKey);
                }
                list = new ArrayList<>();
                orphanChunks.put(assemblyKey, list);
            }

            final OrphanChunk orphan = new OrphanChunk();
            orphan.data = Arrays.copyOf(data, length);
            orphan.length = length;
            // KHANDAQ (audit #5): remember who sent it — these are replayed once the BEGIN lands, and until
            // then anyone in the group can queue chunks under a foreign msg_id.
            orphan.peerId = peerId;
            orphan.senderPubkey = fromPubkey;
            orphan.queuedTs = now;

            if (list.size() >= MAX_ORPHAN_CHUNKS)
            {
                orphanBytes -= list.remove(0).length;
            }
            list.add(orphan);
            orphanBytes += length;

            // Ceiling across all keys — see ORPHAN_TOTAL_BYTES_MAX.
            while (orphanBytes > ORPHAN_TOTAL_BYTES_MAX && dropOldestOrphanLocked())
            {
            }
        }
    }

    // ---- orphan-map maintenance. Everything below runs under synchronized (orphanChunks); only
    // dropOrphanList / flushOrphanChunks take that lock themselves. ----

    /** Drop orphans that can no longer be racing a live BEGIN. Lists are append-ordered, so head = oldest. */
    private static void pruneExpiredOrphanChunks(final long now)
    {
        final java.util.Iterator<Map.Entry<String, List<OrphanChunk>>> it = orphanChunks.entrySet().iterator();
        while (it.hasNext())
        {
            final List<OrphanChunk> list = it.next().getValue();
            while (!list.isEmpty() && (now - list.get(0).queuedTs) > ORPHAN_CHUNK_TTL_MS)
            {
                orphanBytes -= list.remove(0).length;
            }
            if (list.isEmpty())
            {
                it.remove();
            }
        }
        if (orphanBytes < 0L)
        {
            orphanBytes = 0L;
        }
    }

    /** @return {@code false} when nothing is left to drop. */
    private static boolean dropOldestOrphanLocked()
    {
        String oldestKey = null;
        long oldestTs = Long.MAX_VALUE;
        for (final Map.Entry<String, List<OrphanChunk>> entry : orphanChunks.entrySet())
        {
            final List<OrphanChunk> list = entry.getValue();
            if (!list.isEmpty() && list.get(0).queuedTs < oldestTs)
            {
                oldestTs = list.get(0).queuedTs;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey == null)
        {
            return false;
        }
        final List<OrphanChunk> list = orphanChunks.get(oldestKey);
        orphanBytes -= list.remove(0).length;
        if (list.isEmpty())
        {
            orphanChunks.remove(oldestKey);
        }
        return true;
    }

    /**
     * Key whose last queued chunk is oldest. {@code restrictToSender} limits the search to keys last fed by
     * the given peer, so the eviction hits the flooder's own entries before anybody else's.
     */
    private static String leastRecentlyFedOrphanKey(final String fromPubkey, final long peerId,
                                                    final boolean restrictToSender)
    {
        String staleKey = null;
        long staleTs = Long.MAX_VALUE;
        for (final Map.Entry<String, List<OrphanChunk>> entry : orphanChunks.entrySet())
        {
            final List<OrphanChunk> list = entry.getValue();
            if (list.isEmpty())
            {
                continue;
            }
            final OrphanChunk last = list.get(list.size() - 1);
            if (restrictToSender)
            {
                final boolean sameSender = (isRealPubkey(fromPubkey) && isRealPubkey(last.senderPubkey))
                        ? fromPubkey.equalsIgnoreCase(last.senderPubkey)
                        : (last.peerId == peerId);
                if (!sameSender)
                {
                    continue;
                }
            }
            if (last.queuedTs < staleTs)
            {
                staleTs = last.queuedTs;
                staleKey = entry.getKey();
            }
        }
        return staleKey;
    }

    private static List<OrphanChunk> removeOrphanListLocked(final String assemblyKey)
    {
        final List<OrphanChunk> list = orphanChunks.remove(assemblyKey);
        if (list != null)
        {
            for (final OrphanChunk o : list)
            {
                orphanBytes -= o.length;
            }
            if (orphanBytes < 0L)
            {
                orphanBytes = 0L;
            }
        }
        return list;
    }

    /** Forget any buffered pre-BEGIN chunks for this transfer (keeps {@link #orphanBytes} honest). */
    private static void dropOrphanList(final String assemblyKey)
    {
        synchronized (orphanChunks)
        {
            removeOrphanListLocked(assemblyKey);
        }
    }

    private static void flushOrphanChunks(final long groupNumber, final String assemblyKey)
    {
        final List<OrphanChunk> list;
        synchronized (orphanChunks)
        {
            list = removeOrphanListLocked(assemblyKey);
        }
        if (list == null || list.isEmpty())
        {
            return;
        }

        final IncomingAssembly asm = assemblies.get(assemblyKey);
        if (asm == null)
        {
            return;
        }

        final long now = System.currentTimeMillis();
        for (OrphanChunk orphan : list)
        {
            // Expired while it waited — the sender re-sends it on the next NACK, so nothing is lost.
            if ((now - orphan.queuedTs) > ORPHAN_CHUNK_TTL_MS)
            {
                continue;
            }
            // Replay with the origin recorded at queue time, not the peer that delivered the BEGIN —
            // applyChunk drops the ones that came from somebody else.
            applyChunk(groupNumber, orphan.peerId, orphan.senderPubkey, assemblyKey, asm, asm.msgId,
                       orphan.data, orphan.length);
        }
    }

    private static String resolveDisplayFilename(final byte[] data, final int offset, final int maxLen)
    {
        final String parsed = utf8Filename(data, offset, maxLen).trim();
        if (!parsed.isEmpty())
        {
            return parsed;
        }
        return "file.bin";
    }

    private static String resolveDisplayFilenameFromMessage(final GroupMessage message)
    {
        if (message.file_name != null && !message.file_name.isEmpty()
                && !HelperFiletransfer.looksLikeInternalFtName(message.file_name))
        {
            return message.file_name;
        }

        final String fromText = HelperFiletransfer.display_name_from_message_text(message.text);
        if (fromText != null && !fromText.isEmpty())
        {
            return fromText;
        }

        return "file.bin";
    }

    private static byte[] buildBeginPacket(final byte[] msgId, final String fileName, final long totalSize,
                                           final int totalChunks)
    {
        final ByteBuffer buf = ByteBuffer.allocate(FILE_BEGIN_HEADER).order(ByteOrder.BIG_ENDIAN);
        putMagic(buf);
        buf.put(PKT_FILE_BEGIN);
        buf.put(msgId, 0, Math.min(32, msgId.length));
        if (msgId.length < 32)
        {
            for (int i = msgId.length; i < 32; i++)
            {
                buf.put((byte) 0);
            }
        }
        buf.putInt(0);
        putFilename(buf, fileName);
        buf.putLong(totalSize);
        buf.putInt(CHUNK_PAYLOAD_MAX);
        buf.putInt(totalChunks);
        return buf.array();
    }

    private static byte[] buildChunkPacket(final byte[] msgId, final int chunkIndex, final byte[] payload, final int len)
    {
        final ByteBuffer buf = ByteBuffer.allocate(FILE_CHUNK_HEADER + len).order(ByteOrder.BIG_ENDIAN);
        putMagic(buf);
        buf.put(PKT_FILE_CHUNK);
        buf.put(msgId, 0, Math.min(32, msgId.length));
        if (msgId.length < 32)
        {
            for (int i = msgId.length; i < 32; i++)
            {
                buf.put((byte) 0);
            }
        }
        buf.putInt(chunkIndex);
        buf.putInt(len);
        buf.put(payload, 0, len);
        return buf.array();
    }

    private static void putMagic(final ByteBuffer buf)
    {
        buf.put((byte) 0x66);
        buf.put((byte) 0x77);
        buf.put((byte) 0x88);
        buf.put((byte) 0x11);
        buf.put((byte) 0x34);
        buf.put((byte) 0x35);
        buf.put((byte) 0x01);
    }

    private static void putFilename(final ByteBuffer buf, final String fileName)
    {
        byte[] fn = fileName.getBytes(StandardCharsets.UTF_8);
        if (fn.length > 255)
        {
            fn = Arrays.copyOf(fn, 255);
        }
        buf.put(fn);
        for (int i = fn.length; i < 255; i++)
        {
            buf.put((byte) 0);
        }
    }

    private static String utf8Filename(final byte[] data, final int offset, final int maxLen)
    {
        int end = offset;
        while (end < offset + maxLen && data[end] != 0)
        {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long readU64Be(final byte[] data, final int offset)
    {
        long v = 0;
        for (int i = 0; i < 8; i++)
        {
            v = (v << 8) | (data[offset + i] & 0xffL);
        }
        return v;
    }

    private static int readU32Be(final byte[] data, final int offset)
    {
        return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }

    private static boolean isSelfGroupPeer(final long groupNumber, final long peerId)
    {
        try
        {
            return tox_group_self_get_peer_id(groupNumber) == peerId;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static String assemblyKey(final long groupNumber, final byte[] msgId)
    {
        return groupNumber + ":" + bytebuffer_to_hexstring(ByteBuffer.wrap(msgId), true);
    }

    /**
     * KHANDAQ: a chunked group send needs BOTH the group CONNECTED and at least one ONLINE peer to
     * receive the custom packets. Returns true once both hold (within timeout), nudging a reconnect
     * while it waits. Used to gate large media so a video can't be pushed into a group that looks
     * open in the UI but has no transport / no peers (which previously stalled at 0% → Upload Failed).
     */
    static boolean waitForGroupSendReady(final long groupNum, final GroupMessage g, final long timeoutMs)
    {
        final int connected = TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value;
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            if (g != null && HelperGroup.is_ngc_send_cancelled(g.group_identifier, g.msg_id_hash))
            {
                return false;
            }
            try
            {
                if (tox_group_is_connected(groupNum) == connected
                        && HelperGroup.pickChunkedFileUnicastPeerIds(groupNum).length > 0)
                {
                    return true;
                }
            }
            catch (Exception ignored)
            {
            }
            TrifaToxService.wakeup_tox_thread();
            if (g != null && g.group_identifier != null)
            {
                maybeReconnectIfDisconnected(groupNum, g);
            }
            try
            {
                Thread.sleep(500L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        try
        {
            return tox_group_is_connected(groupNum) == connected
                    && HelperGroup.pickChunkedFileUnicastPeerIds(groupNum).length > 0;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** Wait until a specific peer has direct or relay transport (required for private custom packets). */
    static boolean waitForPeerOnline(final long groupNum, final long peerId, final long timeoutMs)
    {
        if (groupNum < 0 || peerId < 0)
        {
            return false;
        }
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                if (HelperGroup.is_group_peer_online(tox_group_peer_get_connection_status(groupNum, peerId)))
                {
                    return true;
                }
            }
            catch (Exception ignored)
            {
            }
            TrifaToxService.wakeup_tox_thread();
            HelperGroup.send_ngch_keepalive_packet(groupNum, peerId);
            try
            {
                Thread.sleep(500L);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        try
        {
            return HelperGroup.is_group_peer_online(tox_group_peer_get_connection_status(groupNum, peerId));
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static void handleIncomingFileRequest(final long groupNumber, final long peerId, final byte[] data, final int length)
    {
        if (length < FILE_REQUEST_HEADER || data[7] != PKT_FILE_REQUEST)
        {
            return;
        }

        if (isSelfGroupPeer(groupNumber, peerId))
        {
            return;
        }

        try
        {
            final byte[] msgId = Arrays.copyOfRange(data, 8, 40);
            final String msgIdHex = bytebuffer_to_hexstring(ByteBuffer.wrap(msgId), true);
            final String groupIdRaw = HelperGroup.tox_group_by_groupnum__wrapper(groupNumber);
            if (groupIdRaw == null)
            {
                return;
            }
            final String groupId = groupIdRaw.toLowerCase(Locale.ROOT);
            final GroupMessage outgoing = HelperGroup.find_group_message_by_msg_id_hash(groupId, msgIdHex);
            if (outgoing == null || outgoing.direction != 1)
            {
                return;
            }

            final String selfPubkey = MainActivity.tox_group_self_get_public_key(groupNumber).toUpperCase(Locale.ROOT);
            if (outgoing.tox_group_peer_pubkey == null
                    || !selfPubkey.equalsIgnoreCase(outgoing.tox_group_peer_pubkey))
            {
                return;
            }

            // KHANDAQ (audit2 #5): the outgoing row survives a cancel (we only stop sending, we do not
            // delete the message), so a remote FILE_REQUEST could resurrect a cancelled file. The
            // cancellation is persisted, hence this also holds on the first request after a restart.
            // Non-cancelled files are untouched — the NACK/retry path below still runs normally.
            if (HelperGroup.is_ngc_send_cancelled(groupId, msgIdHex))
            {
                HelperGeneric.logI(TAG, "handleFileRequest:cancelled msg=" + msgIdHex.substring(0, 8));
                return;
            }

            final boolean[] wanted = parseWantedChunks(data, length, outgoing.filesize);
            final long now = System.currentTimeMillis();

            if (wanted == null)
            {
                // Legacy FULL resend — wasteful; gate behind user-send priority + global throttle.
                if (activeUserSends.get() > 0)
                {
                    HelperGeneric.logI(TAG, "handleFileRequest:defer (user send active) msg="
                            + msgIdHex.substring(0, 8));
                    return;
                }
                if (now - lastResendServiceTs.get() < RESEND_SERVICE_THROTTLE_MS)
                {
                    HelperGeneric.logI(TAG, "handleFileRequest:throttle msg=" + msgIdHex.substring(0, 8));
                    return;
                }
                lastResendServiceTs.set(now);
            }
            else
            {
                // Selective NACK — targeted & cheap; must NOT be starved by the background full-resend flood.
                final String cd = beginResendKey(groupId, msgIdHex);
                final Long last = selectiveResendTs.get(cd);
                if (last != null && (now - last) < SELECTIVE_RESEND_COOLDOWN_MS)
                {
                    return;
                }
                selectiveResendTs.put(cd, now);
            }

            HelperGeneric.logI(TAG, "handleFileRequest:resend group=" + groupId + " msg=" + msgIdHex.substring(0, 8)
                    + " requester_peer=" + peerId + (wanted != null ? " selective" : " full"));
            // KHANDAQ (0.2.56): serving one member leaves the file's own state alone. The failed mark and the start
            // of a transfer were set here, so a file that had gone showed as going at 0 % again, and with no
            // progress from the selective resend, as not sent 30 s later; and the full resend cleared the resume
            // point of the file's own send.
            if (wanted != null)
            {
                sendGroupFileChunkedSelective(outgoing, peerId, wanted);
            }
            else
            {
                sendGroupFileChunked(outgoing, peerId);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "handleFileRequest:EE:" + e.getMessage());
        }
    }

    static void sendFileResendRequest(final GroupMessage message)
    {
        if (message == null || message.group_identifier == null || message.msg_id_hash == null)
        {
            return;
        }
        if (!shouldUseChunkedTransfer(message.filesize))
        {
            return;
        }
        if (isReceiveAlreadyComplete(message.group_identifier, message.msg_id_hash))
        {
            return;
        }
        if (hasIncomingAssemblyInProgress(message.group_identifier, message.msg_id_hash))
        {
            return;
        }

        // KHANDAQ (0.2.56): a file asks NgcFileNackBudget.MAX_REQUESTS times at most, MIN_GAP_MS (the 4 s this gap
        // always was) apart. It asked every few seconds for as long as its sender was online, with no end.
        final String nackKey = receiveDoneKey(message.group_identifier, message.msg_id_hash);
        if (!NACKS.takeRequest(nackKey, nowMs()))
        {
            return;
        }
        if (NACKS.requestsTaken(nackKey) >= NgcFileNackBudget.MAX_REQUESTS)
        {
            HelperGeneric.logI(TAG, "sendFileResendRequest:last of " + NgcFileNackBudget.MAX_REQUESTS
                    + " requests msg=" + message.msg_id_hash.substring(0, 8));
        }

        try
        {
            final long groupNum = tox_group_by_groupid__wrapper(message.group_identifier);
            if (groupNum < 0)
            {
                return;
            }

            byte[] msgId = hex_to_bytes(message.msg_id_hash);
            if (msgId.length != 32)
            {
                final byte[] fixed = new byte[32];
                System.arraycopy(msgId, 0, fixed, 0, Math.min(msgId.length, 32));
                msgId = fixed;
            }

            final byte[] missingBitmap = buildMissingChunkBitmap(groupNum, msgId);
            final int totalChunks = (int) ((message.filesize + CHUNK_PAYLOAD_MAX - 1) / CHUNK_PAYLOAD_MAX);
            final byte[] requestPkt = buildFileRequestPacket(msgId, totalChunks, missingBitmap);
            if (missingBitmap != null)
            {
                int miss = 0;
                for (int i = 0; i < totalChunks; i++)
                {
                    if ((missingBitmap[i >> 3] & (1 << (i & 7))) != 0)
                    {
                        miss++;
                    }
                }
                HelperGeneric.logI(TAG, "sendFileResendRequest:nack msg=" + message.msg_id_hash.substring(0, 8)
                        + " missing=" + miss + "/" + totalChunks);
            }
            long senderPeerId = -1L;
            if (message.tox_group_peer_pubkey != null && !message.tox_group_peer_pubkey.isEmpty())
            {
                try
                {
                    senderPeerId = tox_group_peer_by_public_key(groupNum,
                            message.tox_group_peer_pubkey.toUpperCase(Locale.ROOT));
                }
                catch (Exception ignored)
                {
                }
            }

            int okCount = 0;
            if (senderPeerId >= 0L)
            {
                // KHANDAQ (#126): do NOT gate on is_group_peer_online — NGC status reads NONE for reachable
                // peers and would suppress the one correct lossless recovery target (the actual sender). The
                // private-send return code is the real reachability signal.
                waitForPeerOnline(groupNum, senderPeerId, 2000L);
                final int res = tox_group_send_custom_private_packet(
                        groupNum, senderPeerId, 1, requestPkt, requestPkt.length);
                HelperGeneric.logI(TAG, "sendFileResendRequest:private msg="
                        + message.msg_id_hash.substring(0, 8) + " peer=" + senderPeerId + " res=" + res);
                if (HelperGroup.is_ngc_custom_packet_send_ok(res))
                {
                    okCount++;
                }
            }

            // KHANDAQ (#126): only spray other peers when the sender-targeted send failed — non-senders ignore
            // a sprayed NACK anyway (self-pubkey check in handleIncomingFileRequest), so spraying on success is
            // pure transport waste during the flapping scenario.
            if (okCount == 0)
            {
                final long[] onlinePeers = HelperGroup.pickChunkedFileUnicastPeerIds(groupNum);
                for (final long peerId : onlinePeers)
                {
                    if (peerId == senderPeerId)
                    {
                        continue;
                    }
                    waitForPeerOnline(groupNum, peerId, 2000L);
                    final int res = tox_group_send_custom_private_packet(
                            groupNum, peerId, 1, requestPkt, requestPkt.length);
                    HelperGeneric.logI(TAG, "sendFileResendRequest:private msg="
                            + message.msg_id_hash.substring(0, 8) + " peer=" + peerId + " res=" + res);
                    if (HelperGroup.is_ngc_custom_packet_send_ok(res))
                    {
                        okCount++;
                    }
                }
            }

            if (okCount == 0)
            {
                final int res = tox_group_send_custom_packet(groupNum, 1, requestPkt, requestPkt.length);
                HelperGeneric.logI(TAG, "sendFileResendRequest:broadcast msg="
                        + message.msg_id_hash.substring(0, 8) + " res=" + res);
            }
            TrifaToxService.wakeup_tox_thread();
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "sendFileResendRequest:EE:" + e.getMessage());
        }
    }

    /**
     * @param totalChunks    total chunk count (only meaningful when {@code missingBitmap != null})
     * @param missingBitmap  bit {@code i} set ⇒ chunk {@code i} still missing; {@code null} ⇒ legacy full request
     */
    static byte[] buildFileRequestPacket(final byte[] msgId, final int totalChunks, final byte[] missingBitmap)
    {
        final int bmLen = (missingBitmap != null) ? missingBitmap.length : 0;
        final ByteBuffer buf = ByteBuffer.allocate(FILE_REQUEST_HEADER + bmLen).order(ByteOrder.BIG_ENDIAN);
        putMagic(buf);
        buf.put(PKT_FILE_REQUEST);
        buf.put(msgId, 0, Math.min(32, msgId.length));
        if (msgId.length < 32)
        {
            for (int i = msgId.length; i < 32; i++)
            {
                buf.put((byte) 0);
            }
        }
        buf.putInt(bmLen > 0 ? totalChunks : 0);
        if (bmLen > 0)
        {
            buf.put(missingBitmap);
        }
        return buf.array();
    }

    /** @return bitmap (bit i set ⇒ chunk i still missing), or {@code null} when no assembly exists yet. */
    private static byte[] buildMissingChunkBitmap(final long groupNum, final byte[] msgId)
    {
        final IncomingAssembly asm = assemblies.get(assemblyKey(groupNum, msgId));
        if (asm == null || asm.received == null || asm.totalChunks < 1)
        {
            return null;
        }
        final byte[] bm = new byte[(asm.totalChunks + 7) / 8];
        for (int i = 0; i < asm.totalChunks; i++)
        {
            if (!asm.received[i])
            {
                bm[i >> 3] |= (byte) (1 << (i & 7));
            }
        }
        return bm;
    }

    /**
     * Parses a FILE_REQUEST into per-chunk wanted[] (true ⇒ send); null ⇒ legacy full request. After the msg id comes
     * Android's layout, the file's chunk count (u32) and a bitmap of the missing chunks, or iOS's, a list of them
     * (parseListedChunks).
     */
    static boolean[] parseWantedChunks(final byte[] data, final int length, final long fileSize)
    {
        if (length <= FILE_REQUEST_HEADER)
        {
            return null;
        }
        try
        {
            final int expectedTotal = (int) ((fileSize + CHUNK_PAYLOAD_MAX - 1) / CHUNK_PAYLOAD_MAX);
            final boolean[] listed = parseListedChunks(data, length, expectedTotal);
            if (listed != null)
            {
                return listed;
            }
            final int total = readU32Be(data, 40);
            if (total < 1 || total != expectedTotal)
            {
                return null;
            }
            if ((length - FILE_REQUEST_HEADER) < (total + 7) / 8)
            {
                return null;
            }
            final boolean[] wanted = new boolean[total];
            for (int i = 0; i < total; i++)
            {
                if ((data[FILE_REQUEST_HEADER + (i >> 3)] & (1 << (i & 7))) != 0)
                {
                    wanted[i] = true;
                }
            }
            return wanted;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * KHANDAQ (0.2.56): the request of an iPhone (OCTNgcGroupFileTransfer sendNackForAssembly) lists the chunks it
     * misses: after the msg id a count n (u32, 1 to FILE_REQUEST_MAX_LISTED_CHUNKS), then n chunk indices (u32 each,
     * big-endian), and the packet ends there. Android read the count as the file's chunk count, found it was not, and
     * took the request for a full one: dropped while one of our files went, else the whole file again from its first
     * chunk at the pace. The iPhone ignores the chunks it has, counts that as silence, and gives the file up after a
     * minute of it. Indices beyond the file are left out. Android's own request never has this length: its bitmap of
     * n chunks is (n + 7) / 8 bytes, not 4 n. Null when the packet is not of this layout.
     */
    private static boolean[] parseListedChunks(final byte[] data, final int length, final int expectedTotal)
    {
        final int n = readU32Be(data, 40);
        if (n < 1 || n > FILE_REQUEST_MAX_LISTED_CHUNKS || length != FILE_REQUEST_HEADER + 4 * n)
        {
            return null;
        }
        final boolean[] wanted = new boolean[Math.max(0, expectedTotal)];
        for (int k = 0; k < n; k++)
        {
            final long idx = readU32Be(data, FILE_REQUEST_HEADER + 4 * k) & 0xffffffffL;
            if (idx < wanted.length)
            {
                wanted[(int) idx] = true;
            }
        }
        return wanted;
    }

    /**
     * Resend only the chunks the receiver marked missing — avoids re-streaming the whole file each retry. KHANDAQ
     * (0.2.56): at the pace, like every other packet of a group file; it pushed them back to back and gave up at the
     * first refusal (-99) of toxcore's full send array.
     */
    private static void sendGroupFileSelectiveOnWorker(final GroupMessage g, final long targetPeerId,
                                                       final boolean[] wanted)
    {
        try
        {
            TrifaToxService.wakeup_tox_thread();
            final OutgoingFileSource src = openOutgoingFileSource(g);
            if (src == null || src.size < 1L)
            {
                HelperGeneric.logI(TAG, "send:selective missing file " + g.filename_fullpath);
                return;
            }
            try (InputStream in = src.stream)
            {
                final long fileSize = src.size;
                final long groupNum = tox_group_by_groupid__wrapper(g.group_identifier);
                if (groupNum < 0)
                {
                    return;
                }
                final PeerSendTargets targets = new PeerSendTargets(new long[]{targetPeerId}, false);

                byte[] msgId;
                try
                {
                    msgId = hex_to_bytes(g.msg_id_hash);
                }
                catch (Exception e)
                {
                    msgId = new byte[32];
                }
                if (msgId.length != 32)
                {
                    final byte[] fixed = new byte[32];
                    System.arraycopy(msgId, 0, fixed, 0, Math.min(msgId.length, 32));
                    msgId = fixed;
                }

                final int totalChunks = (int) ((fileSize + CHUNK_PAYLOAD_MAX - 1) / CHUNK_PAYLOAD_MAX);
                // (Re)send BEGIN so a receiver that lost its assembly can rebuild; existing assemblies dup-skip it.
                final PacedSend send = new PacedSend(groupNum, targets, g,
                                                     buildBeginPacket(msgId, g.file_name, fileSize, totalChunks));
                if (send.begin() != SEND_OK)
                {
                    HelperGeneric.logI(TAG, "send:selective BEGIN went to nobody peer=" + targetPeerId);
                    return;
                }

                int sent = 0;
                final byte[] readBuf = new byte[CHUNK_PAYLOAD_MAX];
                for (int idx = 0; idx < totalChunks; idx++)
                {
                    final int toRead = (int) Math.min(CHUNK_PAYLOAD_MAX, fileSize - (long) idx * CHUNK_PAYLOAD_MAX);
                    if (idx >= wanted.length || !wanted[idx])
                    {
                        skipFully(in, toRead, readBuf);
                        continue;
                    }
                    readFully(in, readBuf, toRead);
                    final int res = send.chunk(buildChunkPacket(msgId, idx, readBuf, toRead));
                    if (res != SEND_OK)
                    {
                        HelperGeneric.logI(TAG, "send:selective chunk " + idx + " not sent res=" + res);
                        return;
                    }
                    sent++;
                    TrifaToxService.wakeup_tox_thread();
                }
                HelperGeneric.logI(TAG, "send:selective done sent=" + sent + "/" + totalChunks);
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "send:selective:EE:" + e.getMessage());
        }
    }

    private static void readFully(final InputStream in, final byte[] buf, final int n) throws IOException
    {
        int off = 0;
        while (off < n)
        {
            final int r = in.read(buf, off, n - off);
            if (r < 0)
            {
                throw new IOException("EOF at " + off + "/" + n);
            }
            off += r;
        }
    }

    private static void skipFully(final InputStream in, final long n, final byte[] scratch) throws IOException
    {
        long rem = n;
        while (rem > 0)
        {
            final long s = in.skip(rem);
            if (s > 0)
            {
                rem -= s;
                continue;
            }
            final int toRead = (int) Math.min(scratch.length, rem);
            final int r = in.read(scratch, 0, toRead);
            if (r < 0)
            {
                break;
            }
            rem -= r;
        }
    }

    private static String beginResendKey(final String groupId, final String msgIdHash)
    {
        return groupId.toLowerCase(Locale.ROOT) + ":" + msgIdHash.toLowerCase(Locale.ROOT);
    }

    private static boolean isGroupTransportConnected(final long groupNum)
    {
        try
        {
            return tox_group_is_connected(groupNum)
                    == TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    /**
     * Reconnect ONLY when the group is genuinely disconnected, rate-limited per group. {@code tox_group_reconnect}
     * tears down the lossless session (drops in-flight chunks + a 2s stall), so it must never fire on routine
     * send_array backpressure or brief peer flaps mid-transfer — doing so was what stalled large transfers.
     */
    static void maybeReconnectIfDisconnected(final long groupNum, final GroupMessage g)
    {
        if (g == null || g.group_identifier == null)
        {
            return;
        }
        if (isGroupTransportConnected(groupNum))
        {
            return;
        }
        final long now = System.currentTimeMillis();
        final Long last = lastGroupReconnectTs.get(g.group_identifier);
        if (last != null && (now - last) < GROUP_RECONNECT_MIN_INTERVAL_MS)
        {
            return;
        }
        lastGroupReconnectTs.put(g.group_identifier, now);
        HelperGroup.kickstart_group_connection(groupNum, g.group_identifier);
    }

    /** A monotonic clock in milliseconds, for the pace and the budgets (the wall clock can step). */
    static long nowMs()
    {
        return System.nanoTime() / 1_000_000L;
    }

    /** Sleeps; false when the thread was interrupted (its flag set again). */
    static boolean sleepQuietly(final long ms)
    {
        try
        {
            Thread.sleep(ms);
            return true;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static boolean isStopped(final GroupMessage g)
    {
        return Thread.currentThread().isInterrupted()
               || (g != null && HelperGroup.is_ngc_send_cancelled(g.group_identifier, g.msg_id_hash));
    }

    /**
     * KHANDAQ (0.2.56): waits until the pace lets a packet go (NgcUploadPace); false when the file was stopped
     * meanwhile. The packet's cost is spent by the caller once it went ({@link #spendPace}).
     */
    static boolean awaitPace(final GroupMessage g)
    {
        while (true)
        {
            final long waitMs;
            synchronized (PACE)
            {
                final long now = nowMs();
                if (PACE.ready(now))
                {
                    return true;
                }
                waitMs = PACE.msUntilReady(now);
            }
            if (isStopped(g) || !sleepQuietly(Math.max(1L, Math.min(waitMs, 50L))))
            {
                return false;
            }
        }
    }

    /** KHANDAQ (0.2.56): a packet of this many bytes went to this many members (NgcUploadPace.cost). */
    static void spendPace(final long packetBytes, final int members)
    {
        PACE.spend(NgcUploadPace.cost(packetBytes, members));
    }

    /**
     * KHANDAQ (0.2.56): the group at a number (its id, or null when the number names no group) is still the file's
     * group. toxcore gives the lowest free number to the next group joined or created, so a number names another group
     * once its group is left.
     */
    static boolean isGroupOfFile(final String fileGroupId, final String groupIdAtNumber)
    {
        return fileGroupId != null && groupIdAtNumber != null && fileGroupId.equalsIgnoreCase(groupIdAtNumber);
    }

    /**
     * KHANDAQ (0.2.56): the packets of one file to its members, at the pace. A member that comes while the file goes
     * gets the BEGIN before its next chunk: a receiver keeps chunks only for a file whose BEGIN it has (others are
     * dropped after a minute), and with the BEGIN it asks for the chunks it missed. A member that leaves and comes
     * back gets the BEGIN again.
     */
    private static final class PacedSend
    {
        private final long groupNum;
        private final PeerSendTargets targets;
        private final GroupMessage g;
        private final byte[] beginPkt;
        /** The members that took the BEGIN. */
        private final java.util.Set<Long> begun = new java.util.HashSet<>();
        private int refusals = 0;

        PacedSend(final long groupNum, final PeerSendTargets targets, final GroupMessage g, final byte[] beginPkt)
        {
            this.groupNum = groupNum;
            this.targets = targets;
            this.g = g;
            this.beginPkt = beginPkt;
        }

        int begin()
        {
            return send(beginPkt, true);
        }

        int chunk(final byte[] chunkPkt)
        {
            return send(chunkPkt, false);
        }

        /**
         * SEND_OK once the packet went to at least one member; SEND_NO_MEMBER when the group had nobody to send to
         * for NO_MEMBER_GRACE_MS, or every member refused it for REFUSED_LIMIT_MS, or its number is no longer the
         * file's group; SEND_STOPPED on a cancel.
         * Refusals are toxcore's send array, full until the member acknowledges what it has: they are waited out.
         */
        private int send(final byte[] pkt, final boolean isBegin)
        {
            long nobodySinceMs = -1L;
            long refusedSinceMs = -1L;
            int attempt = 0;
            while (true)
            {
                if (isStopped(g))
                {
                    return SEND_STOPPED;
                }
                // KHANDAQ (0.2.56): groupNum is the number the file's group had when the send began. A group left
                // meanwhile frees it, toxcore gives it to the next group joined or created, and the members of that
                // group would get the BEGIN and the chunks: the BEGIN names no group. The file waits for its own group
                // instead, found again by its id.
                if (!isGroupOfFile(g.group_identifier, HelperGroup.tox_group_by_groupnum__wrapper(groupNum)))
                {
                    HelperGeneric.logI(TAG, "send:group number " + groupNum + " is no longer the file's group msg="
                            + g.msg_id_hash.substring(0, 8));
                    return SEND_NO_MEMBER;
                }
                targets.refreshOnlinePeers(groupNum);
                if (!targets.hasPrivatePeer())
                {
                    begun.clear();
                    final long now = nowMs();
                    if (nobodySinceMs < 0L)
                    {
                        nobodySinceMs = now;
                    }
                    if ((now - nobodySinceMs) >= NO_MEMBER_GRACE_MS)
                    {
                        return SEND_NO_MEMBER;
                    }
                    TrifaToxService.wakeup_tox_thread();
                    maybeReconnectIfDisconnected(groupNum, g);
                    if (!sleepQuietly(500L))
                    {
                        return SEND_STOPPED;
                    }
                    continue;
                }
                nobodySinceMs = -1L;
                keepBegunOfTargets();
                if (!isBegin && !sendBeginToNewMembers())
                {
                    return SEND_STOPPED;
                }
                if (!awaitPace(g))
                {
                    return SEND_STOPPED;
                }
                int took = 0;
                for (final long peerId : targets.peerIds)
                {
                    final int res = tox_group_send_custom_private_packet(groupNum, peerId, 1, pkt, pkt.length);
                    if (HelperGroup.is_ngc_custom_packet_send_ok(res))
                    {
                        took++;
                        if (isBegin)
                        {
                            begun.add(peerId);
                        }
                    }
                }
                if (took > 0)
                {
                    spendPace(pkt.length, took);
                    return SEND_OK;
                }

                // Every member refused it: their send arrays are full until they acknowledge, or they are gone.
                final long now = nowMs();
                if (refusedSinceMs < 0L)
                {
                    refusedSinceMs = now;
                }
                if ((now - refusedSinceMs) >= REFUSED_LIMIT_MS)
                {
                    return SEND_NO_MEMBER;
                }
                attempt++;
                refusals++;
                if ((refusals & 63) == 1)
                {
                    HelperGeneric.logI(TAG, "send:backpressure refusals=" + refusals + " " + targets.describe());
                }
                TrifaToxService.wakeup_tox_thread();
                if (!sleepQuietly(Math.min(120L, 10L + attempt * 2L)))
                {
                    return SEND_STOPPED;
                }
            }
        }

        /** A member that left loses its BEGIN: if it comes back, it gets the BEGIN again. */
        private void keepBegunOfTargets()
        {
            final java.util.Set<Long> now = new java.util.HashSet<>();
            for (final long peerId : targets.peerIds)
            {
                now.add(peerId);
            }
            begun.retainAll(now);
        }

        /** The BEGIN to each member that does not have it, one try each; false on a cancel. */
        private boolean sendBeginToNewMembers()
        {
            for (final long peerId : targets.peerIds)
            {
                if (begun.contains(peerId))
                {
                    continue;
                }
                if (!awaitPace(g))
                {
                    return false;
                }
                final int res = tox_group_send_custom_private_packet(groupNum, peerId, 1, beginPkt,
                                                                     beginPkt.length);
                if (HelperGroup.is_ngc_custom_packet_send_ok(res))
                {
                    spendPace(beginPkt.length, 1);
                    begun.add(peerId);
                    HelperGeneric.logI(TAG, "send:begin to member that came peer=" + peerId);
                }
            }
            return true;
        }
    }

    // ---- KHANDAQ (0.2.56): how our group files stand ----

    private static String stateKey(final String groupId, final String msgIdHash)
    {
        return groupId.toLowerCase(Locale.ROOT) + "|" + msgIdHash.toLowerCase(Locale.ROOT);
    }

    /**
     * KHANDAQ (0.2.56): our file has nobody to go to. It waits for a member (WAITS) instead of being said not sent,
     * and goes again from its BEGIN, from its first chunk, once its group has one (resumeWaitingSends): what went
     * before was lost with the members that left, and a member that comes has none of it.
     */
    static void waitForMember(final GroupMessage g, final String why)
    {
        if (g == null || g.group_identifier == null || g.msg_id_hash == null)
        {
            return;
        }
        HelperGeneric.logI(TAG, "send:waits for a member (" + why + ") msg=" + g.msg_id_hash.substring(0, 8));
        markOutgoingPending(g.group_identifier, g.msg_id_hash);
        HelperGroup.clear_ngc_outgoing_send_resume_state(g.group_identifier, g.msg_id_hash);
        HelperGroup.clear_ngc_file_transfer_progress(g.group_identifier, g.msg_id_hash);
        WAITS.add(g.group_identifier, g.msg_id_hash);
        HelperGroup.refresh_group_file_row(g.group_identifier, g.msg_id_hash);
    }

    /** KHANDAQ (0.2.56): our file went whole: no longer pending, and nothing brings it back. */
    static void outgoingSent(final GroupMessage g)
    {
        if (g == null || g.group_identifier == null || g.msg_id_hash == null)
        {
            return;
        }
        WAITS.remove(g.group_identifier, g.msg_id_hash);
        clearOutgoingPending(g.group_identifier, g.msg_id_hash);
        // KHANDAQ (0.2.56, QA live): its "sent" came within the per-chunk throttle of its last chunk, which ate it:
        // the row said "54 KB left, sending" for minutes after the file went, until the chat was opened again.
        HelperGroup.refresh_group_file_row(g.group_identifier, g.msg_id_hash);
    }

    /** KHANDAQ (0.2.56): the user cancelled our file: it waits for nothing, and a restart does not bring it back. */
    static void forgetOutgoing(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        WAITS.remove(groupId, msgIdHash);
        clearOutgoingPending(groupId, msgIdHash);
    }

    /**
     * KHANDAQ (0.2.56): our file is ours to send until it went whole, across a restart too (PENDING_SEND_PREFIX).
     * The mark is written off the calling thread.
     */
    static void markOutgoingPending(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return;
        }
        final String key = stateKey(groupId, msgIdHash);
        if (!pendingSends.add(key))
        {
            return;
        }
        runState(() -> HelperGeneric.set_g_opts(PENDING_SEND_PREFIX + key, "1"));
    }

    /** The file is no longer pending. Removed from the database also when this run never read the mark. */
    private static void clearOutgoingPending(final String groupId, final String msgIdHash)
    {
        final String key = stateKey(groupId, msgIdHash);
        pendingSends.remove(key);
        runState(() -> HelperGeneric.del_g_opts(PENDING_SEND_PREFIX + key));
    }

    private static void runState(final Runnable work)
    {
        try
        {
            STATE_EXECUTOR.execute(() ->
            {
                try
                {
                    work.run();
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "state:EE:" + e.getMessage());
                }
            });
        }
        catch (RuntimeException e)
        {
            HelperGeneric.logI(TAG, "state:not queued:" + e.getMessage());
        }
    }

    /** KHANDAQ (0.2.56): our file waits for a member to send it to. */
    static boolean isOutgoingWaiting(final String groupId, final String msgIdHash)
    {
        return WAITS.contains(groupId, msgIdHash);
    }

    /** KHANDAQ (0.2.56): the own send of our file is queued or running. */
    static boolean isOutgoingGoing(final String groupId, final String msgIdHash)
    {
        return groupId != null && msgIdHash != null && inflightOwnSends.contains(beginResendKey(groupId, msgIdHash));
    }

    /**
     * KHANDAQ (0.2.56): our file has not gone whole yet: its send began and did not end. Memory only, never blocks:
     * the marks of earlier runs are read once in the background, and their rows drawn again then.
     */
    static boolean isOutgoingPending(final String groupId, final String msgIdHash)
    {
        if (groupId == null || msgIdHash == null)
        {
            return false;
        }
        ensurePendingSendsLoaded();
        return pendingSends.contains(stateKey(groupId, msgIdHash));
    }

    /**
     * KHANDAQ (0.2.56): our file is ours to send and nothing brings it: pending, and neither going, nor waiting for a
     * member (the service's tick brings those), nor failed, nor sent.
     */
    static boolean needsOutgoingResume(final String groupId, final String msgIdHash)
    {
        return isOutgoingPending(groupId, msgIdHash) && !isOutgoingGoing(groupId, msgIdHash)
               && !isOutgoingWaiting(groupId, msgIdHash)
               && !HelperGroup.is_ngc_file_transfer_failed(groupId, msgIdHash)
               && !HelperGroup.is_ngc_outgoing_send_complete(groupId, msgIdHash);
    }

    /**
     * KHANDAQ (0.2.56): the service's tick (every 4 s while we are online). Our files that wait for a member go once
     * their group has had someone to send to for NgcOutgoingFileWaits.PEER_SETTLE_MS. Runs on the tox thread, so
     * only toxcore is asked here; finding the rows and the sends run on STATE_EXECUTOR.
     */
    static void resumeWaitingSends()
    {
        ensurePendingSendsLoaded();
        final long now = nowMs();
        for (final String groupId : WAITS.groups())
        {
            final long groupNum = tox_group_by_groupid__wrapper(groupId);
            final boolean reachable = groupNum >= 0 && HelperGroup.pickChunkedFileUnicastPeerIds(groupNum).length > 0;
            final List<NgcOutgoingFileWaits.File> due = WAITS.due(groupId, reachable, now);
            if (due.isEmpty())
            {
                continue;
            }
            HelperGeneric.logI(TAG, "send:the group has a member, " + due.size() + " waiting file(s) go gn=" + groupNum);
            try
            {
                STATE_EXECUTOR.execute(() -> resumeDue(due));
            }
            catch (RuntimeException e)
            {
                for (final NgcOutgoingFileWaits.File f : due)
                {
                    WAITS.add(f.groupId, f.msgIdHash);
                }
            }
        }
    }

    private static void resumeDue(final List<NgcOutgoingFileWaits.File> due)
    {
        for (final NgcOutgoingFileWaits.File f : due)
        {
            try
            {
                final GroupMessage m = HelperGroup.find_group_message_by_msg_id_hash(f.groupId, f.msgIdHash);
                if (m == null || m.direction != 1)
                {
                    // The message is gone (deleted): nothing to send.
                    clearOutgoingPending(f.groupId, f.msgIdHash);
                    continue;
                }
                // The automatic path: it never lifts a user's cancel.
                HelperGroup.resume_group_file_send(m);
            }
            catch (Exception e)
            {
                HelperGeneric.logI(TAG, "send:resume:EE:" + e.getMessage());
                WAITS.add(f.groupId, f.msgIdHash);
            }
        }
    }

    /** Reads the pending marks of earlier runs once, in the background; again on a later call if it failed. */
    private static void ensurePendingSendsLoaded()
    {
        if (pendingSendsLoaded || TrifaToxService.orma == null || !pendingSendsLoadQueued.compareAndSet(false, true))
        {
            return;
        }
        try
        {
            STATE_EXECUTOR.execute(NgcGroupFileTransfer::loadPendingSends);
        }
        catch (RuntimeException e)
        {
            pendingSendsLoadQueued.set(false);
        }
    }

    /** The files an earlier run did not send whole wait for a member now, and their rows say so. */
    private static void loadPendingSends()
    {
        try
        {
            final List<com.zoffcc.applications.sorm.TRIFADatabaseGlobalsNew> rows =
                    TrifaToxService.orma.selectFromTRIFADatabaseGlobalsNew().keyLike(PENDING_SEND_LIKE).toList();
            int waiting = 0;
            if (rows != null)
            {
                for (final com.zoffcc.applications.sorm.TRIFADatabaseGlobalsNew row : rows)
                {
                    if (row == null || row.key == null || !row.key.startsWith(PENDING_SEND_PREFIX))
                    {
                        continue;
                    }
                    final String key = row.key.substring(PENDING_SEND_PREFIX.length());
                    final int bar = key.indexOf('|');
                    if (bar <= 0 || bar >= key.length() - 1)
                    {
                        continue;
                    }
                    final String groupId = key.substring(0, bar);
                    final String msgIdHash = key.substring(bar + 1);
                    // Went whole or goes in this run already: the mark is on its way out, or no longer needed here.
                    if (HelperGroup.is_ngc_outgoing_send_complete(groupId, msgIdHash)
                        || isOutgoingGoing(groupId, msgIdHash))
                    {
                        continue;
                    }
                    pendingSends.add(key);
                    WAITS.add(groupId, msgIdHash);
                    HelperGroup.refresh_group_file_row(groupId, msgIdHash);
                    waiting++;
                }
            }
            pendingSendsLoaded = true;
            HelperGeneric.logI(TAG, "send:pending from earlier runs=" + waiting);
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "send:pending load:EE:" + e.getMessage());
            pendingSendsLoadQueued.set(false);
        }
    }

    // ---- KHANDAQ (0.2.56): how often an incoming file asks for missing chunks (NgcFileNackBudget) ----

    /** The user asked for the incoming file: it may ask its sender for what it misses again, all its requests. */
    static void restartReceiveRequests(final String groupId, final String msgIdHash)
    {
        if (groupId != null && msgIdHash != null)
        {
            NACKS.restart(receiveDoneKey(groupId, msgIdHash));
        }
    }

    /**
     * A round of the receive watchdog found the incoming file silent, its sender online. True once its silence has
     * lasted NgcFileNackBudget.SILENCE_LIMIT_MS: the file is given up.
     */
    static boolean receiveSilentRound(final String groupId, final String msgIdHash)
    {
        return groupId != null && msgIdHash != null
               && NACKS.silentRound(receiveDoneKey(groupId, msgIdHash), nowMs());
    }

    /** A round of the receive watchdog found the sender of the incoming file away: not the file's silence. */
    static void receiveSenderAway(final String groupId, final String msgIdHash)
    {
        if (groupId != null && msgIdHash != null)
        {
            NACKS.senderAway(receiveDoneKey(groupId, msgIdHash));
        }
    }

    /** The incoming file was given up and nothing started it over: the automatic retries leave it alone. */
    static boolean hasGivenUpReceive(final String groupId, final String msgIdHash)
    {
        return groupId != null && msgIdHash != null && NACKS.gaveUp(receiveDoneKey(groupId, msgIdHash));
    }

    private static final class OutgoingFileSource
    {
        InputStream stream;
        long size;
    }

    private static OutgoingFileSource openOutgoingFileSource(final GroupMessage g) throws IOException
    {
        if (g == null)
        {
            return null;
        }

        String path = g.filename_fullpath;
        if ((path == null) || path.isEmpty())
        {
            if ((g.path_name != null) && (g.file_name != null) && !g.file_name.isEmpty())
            {
                path = g.path_name.endsWith("/") ? g.path_name + g.file_name : g.path_name + "/" + g.file_name;
            }
        }

        if (path == null || path.isEmpty())
        {
            return null;
        }

        final OutgoingFileSource source = new OutgoingFileSource();
        if (isIocipherVirtualPath(path))
        {
            final info.guardianproject.iocipher.File vfsFile = new info.guardianproject.iocipher.File(path);
            if (!vfsFile.exists() || vfsFile.length() < 1L)
            {
                return null;
            }
            source.stream = new info.guardianproject.iocipher.FileInputStream(vfsFile);
            source.size = vfsFile.length();
            return source;
        }

        final java.io.File plain = new java.io.File(path);
        if (!plain.isFile() || plain.length() < 1L)
        {
            return null;
        }
        source.stream = new FileInputStream(plain);
        source.size = plain.length();
        return source;
    }

    static final class IncomingAssembly
    {
        String displayFilename;
        String filename;
        long totalSize;
        int totalChunks;
        int chunkPayload;
        String outPath;
        boolean[] received;
        int receivedCount;
        long bytesReceived;
        byte[] msgId;
        String groupId;
        long groupNumber;
        long peerId;
        /** KHANDAQ: sender pubkey captured when the transfer is first seen, while peerId is still
         *  valid. NGC reuses peer_ids on leave/rejoin, so resolving the sender from peerId LATER (at
         *  completion) can attribute the file/voice to the WRONG person — use this stable pubkey. */
        String senderPubkey;
        /** Set on each freshly stored chunk — used to detect a stalled (vs actively progressing) transfer. */
        volatile long lastChunkTs;
        /** When this assembly was opened — an assembly that never got a chunk still has an age to rank by. */
        long createdTs;
        /** KHANDAQ (0.2.56): its .rxmap gave it chunks already on disk; they are kept (writeChunk). */
        boolean resumedFromDisk;
    }

    private static final class OrphanChunk
    {
        byte[] data;
        int length;
        /** Origin of the queued chunk — checked against the assembly when the BEGIN finally arrives. */
        long peerId;
        String senderPubkey;
        /** Queue time — orphans past ORPHAN_CHUNK_TTL_MS can no longer belong to a BEGIN still in flight. */
        long queuedTs;
    }
}

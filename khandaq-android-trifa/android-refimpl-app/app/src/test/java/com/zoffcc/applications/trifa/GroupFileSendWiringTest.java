package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56, QA live): how the send and the receive of a group file are wired. The rules themselves are tested
 * in NgcUploadPaceTest, NgcOutgoingFileWaitsTest, NgcFileNackBudgetTest and GroupFileSendPhaseTest; the senders call
 * toxcore, so these read the sources (comments left out) that use them.
 *
 * QA (GW5-GW10): a group file sent while nobody else was online said "Not sent" after about a minute and was not
 * sent when the member came online; "Send again" got its BEGIN through and nothing else, every chunk refused (-99)
 * by toxcore's full send array. And an incoming file asked its sender for missing chunks without end.
 */
public class GroupFileSendWiringTest
{
    private static String code(final String name) throws java.io.IOException
    {
        return AntispamOnRequestTest.code(source(name));
    }

    @Test
    public void a_file_with_nobody_to_go_to_waits_for_a_member() throws Exception
    {
        final String ngc = code("NgcGroupFileTransfer.java");
        final String send = body(ngc, "private static void sendChunkedFrom(");
        // Nobody else in the group, nobody online, the BEGIN or a chunk taken by nobody: it waits.
        assertTrue(send.contains("endUnsent(g, ownSend, SEND_NO_MEMBER, \"send\")"));
        assertTrue(send.contains("endUnsent(g, ownSend, beginRes, \"BEGIN\")"));
        assertTrue(send.contains("endUnsent(g, ownSend, res, \"chunk \" + idx)"));
        // It used to wait 45 s for a member (resolveSendTargets) and 20 s for its BEGIN, then be said not sent.
        assertFalse(ngc.contains("GROUP_SEND_CONN_WAIT_MS"));
        assertFalse(ngc.contains("CHUNK_SEND_STALL_MS"));
        assertFalse(send.contains("mark_ngc_file_transfer_failed"));

        final String unsent = body(ngc, "private static void endUnsent(");
        assertTrue(unsent.contains("waitForMember(g,"));
        assertFalse(unsent.contains("mark_ngc_file_transfer_failed"));
        assertFalse(unsent.contains("failOwnSend"));

        final String waitFor = body(ngc, "static void waitForMember(");
        assertTrue(waitFor.contains("markOutgoingPending("));
        assertTrue(waitFor.contains("WAITS.add("));
        assertFalse(waitFor.contains("mark_ngc_file_transfer_failed"));
    }

    @Test
    public void a_resumed_send_starts_with_its_begin() throws Exception
    {
        // A member that came since has nothing of the file; a resume skipped the BEGIN, and its chunks were dropped.
        final String send = body(code("NgcGroupFileTransfer.java"), "private static void sendChunkedFrom(");
        final int begin = send.indexOf("send.begin()");
        assertTrue(begin > 0);
        assertTrue(begin < send.indexOf("if (startChunk > 0)"));
        assertTrue(begin < send.indexOf("send.chunk("));
    }

    @Test
    public void every_packet_of_a_file_goes_at_the_pace() throws Exception
    {
        final String ngc = code("NgcGroupFileTransfer.java");
        final String paced = body(ngc, "private static final class PacedSend");
        // The private sends of file packets are those of PacedSend, after the pace let them go; the others are the
        // requests for missing chunks (sendFileResendRequest, through sendFileRequestPrivately).
        final String request = body(ngc, "static void sendFileResendRequest(");
        final String requestPackets = body(ngc, "private static int sendFileRequestPrivately(");
        assertEquals(count(ngc, "tox_group_send_custom_private_packet("),
                     count(paced, "tox_group_send_custom_private_packet(")
                     + count(request, "tox_group_send_custom_private_packet(")
                     + count(requestPackets, "tox_group_send_custom_private_packet("));
        assertEquals(1, count(requestPackets, "tox_group_send_custom_private_packet("));
        final String sendLoop = body(paced, "private int send(final byte[] pkt, final boolean isBegin)");
        final int sendWait = sendLoop.indexOf("awaitPace(g, overRelay)");
        assertTrue(sendWait > 0);
        assertTrue(sendWait < sendLoop.indexOf("tox_group_send_custom_private_packet("));
        assertTrue(sendLoop.contains("spendPace(pkt.length, took, overRelay)"));
        final String newMembers = body(paced, "private boolean sendBeginToNewMembers()");
        final int beginWait = newMembers.indexOf("awaitPace(g, overRelay)");
        assertTrue(beginWait > 0);
        assertTrue(beginWait < newMembers.indexOf("tox_group_send_custom_private_packet("));
        // A refusal is toxcore's full send array: waited out, never "not sent".
        assertTrue(sendLoop.contains("REFUSED_LIMIT_MS"));
        assertFalse(paced.contains("mark_ngc_file_transfer_failed"));

        // The resend of the chunks a member misses goes the same way (it gave up at the first refusal).
        final String selective = body(ngc, "private static void sendGroupFileSelectiveOnWorker(");
        assertTrue(selective.contains("new PacedSend("));
        assertTrue(selective.contains("send.chunk("));

        // A file of one packet too, to the group.
        final String image = body(code("HelperGroup.java"), "static void send_group_image(");
        final int packet = image.indexOf("tox_group_send_custom_packet(");
        assertTrue(packet > 0);
        final int imageWait = image.indexOf("NgcGroupFileTransfer.awaitPace(g, overRelay)");
        assertTrue(imageWait > 0);
        assertTrue(imageWait < packet);
        assertTrue(image.indexOf("NgcGroupFileTransfer.spendPace(") > packet);
    }

    @Test
    public void a_file_packet_to_a_member_over_a_relay_waits_for_the_pace_of_the_relays_too() throws Exception
    {
        // QA 0.2.57: over a TCP relay a member lost the chunks of a group file sent at 256 and 128 KiB/s and got them
        // whole at 64; an iPhone over a relay got 27 of the 86 chunks Android sent it at 512 KiB/s. The rule is tested
        // in NgcUploadPaceTest; the senders call toxcore, so this reads how they use it.
        final String ngc = code("NgcGroupFileTransfer.java");
        assertTrue(ngc.contains("private static final NgcUploadPace RELAY_PACE = "
                                + "new NgcUploadPace(NgcUploadPace.RELAYED_BYTES_PER_SECOND);"));
        assertTrue(body(ngc, "static boolean awaitPace(final GroupMessage g, final boolean overRelay)")
                           .contains("NgcUploadPace.msUntilReady(PACE, RELAY_PACE, overRelay, nowMs())"));
        assertTrue(body(ngc, "static void spendPace(final long packetBytes, final int members, final boolean overRelay")
                           .contains("NgcUploadPace.spend(PACE, RELAY_PACE, packetBytes, members, overRelay)"));
        // As toxcore says each member is reached, our own peer left out (NgcUploadPace.overRelay).
        final String relay = body(ngc, "static boolean overRelay(final long groupNum, final long... peerIds)");
        assertTrue(relay.contains("tox_group_self_get_peer_id(groupNum)"));
        assertTrue(relay.contains("tox_group_peer_get_connection_status(groupNum, peerId)"));

        // The BEGIN and each chunk: the members they go to now, asked before every packet.
        final String paced = body(ngc, "private static final class PacedSend");
        final String sendLoop = body(paced, "private int send(final byte[] pkt, final boolean isBegin)");
        final int refreshed = sendLoop.indexOf("targets.refreshOnlinePeers(groupNum)");
        assertTrue(refreshed > 0);
        final int asked = sendLoop.indexOf("final boolean overRelay = overRelay(groupNum, targets.peerIds);");
        assertTrue(asked > refreshed);
        assertTrue(asked < sendLoop.indexOf("awaitPace(g, overRelay)"));
        // The BEGIN to a member that came: that member alone.
        final String newMembers = body(paced, "private boolean sendBeginToNewMembers()");
        final int member = newMembers.indexOf("final boolean overRelay = overRelay(groupNum, peerId);");
        assertTrue(member > 0);
        assertTrue(member < newMembers.indexOf("awaitPace(g, overRelay)"));
        assertTrue(newMembers.contains("spendPace(beginPkt.length, 1, overRelay)"));
        // The whole file again, or the chunks a member asked for, go to that member alone: the send asks about it.
        assertTrue(body(ngc, "private static void sendChunkedFrom(")
                           .contains(": new PeerSendTargets(new long[]{targetPeerId}, false);"));
        assertTrue(body(ngc, "private static void sendGroupFileSelectiveOnWorker(")
                           .contains("new PeerSendTargets(new long[]{targetPeerId}, false);"));

        // A small file goes as one packet to the group: every member.
        final String image = body(code("HelperGroup.java"), "static void send_group_image(");
        final int picked = image.indexOf("final long[] members = pickChunkedFileUnicastPeerIds(groupNum);");
        assertTrue(picked > 0);
        final int small = image.indexOf("final boolean overRelay = NgcGroupFileTransfer.overRelay(groupNum, members);");
        assertTrue(small > picked);
        assertTrue(small < image.indexOf("NgcGroupFileTransfer.awaitPace(g, overRelay)"));
        assertTrue(image.contains("NgcGroupFileTransfer.spendPace(data_length, members.length, overRelay)"));
    }

    @Test
    public void a_small_file_with_nobody_to_go_to_waits_and_is_not_said_sent() throws Exception
    {
        // toxcore takes a group packet for nobody and says it went: the file showed as sent and reached no one.
        final String image = body(code("HelperGroup.java"), "static void send_group_image(");
        final int nobody = image.indexOf("NgcGroupFileTransfer.waitForMember(g, \"small file, nobody to send to\")");
        assertTrue(nobody > 0);
        assertTrue(nobody < image.indexOf("tox_group_send_custom_packet("));
        assertFalse(image.contains("waitForGroupConnected"));
        assertTrue(image.indexOf("NgcGroupFileTransfer.outgoingSent(g)") > image.indexOf("tox_group_send_custom_packet("));
    }

    @Test
    public void the_service_brings_the_waiting_files_back() throws Exception
    {
        final String service = body(code("TrifaToxService.java"), "void start_queued_filetransfers(");
        assertTrue(service.contains("NgcGroupFileTransfer.resumeWaitingSends()"));
        final String resume = body(code("NgcGroupFileTransfer.java"), "static void resumeWaitingSends()");
        assertTrue(resume.contains("WAITS.due("));
        assertTrue(resume.contains("ensurePendingSendsLoaded()"));
    }

    @Test
    public void the_retries_of_our_files_ask_whether_the_file_is_still_ours_to_send() throws Exception
    {
        // isGroupMessageMediaReady is true for every file of ours, whole on this device: no retry ever sent one.
        final String helper = code("HelperGroup.java");
        final String schedule = body(helper, "private static void schedule_auto_retry_group_file(");
        final String outgoingBranch = block(schedule, schedule.indexOf('{', schedule.indexOf("if (outgoing)")));
        assertTrue(outgoingBranch.contains("NgcGroupFileTransfer.needsOutgoingResume("));
        assertFalse(outgoingBranch.contains("isGroupMessageMediaReady"));

        final String pending = body(helper, "static void retry_pending_outgoing_group_files(");
        assertTrue(pending.contains("NgcGroupFileTransfer.needsOutgoingResume("));
        assertFalse(pending.contains("isGroupMessageMediaReady"));
    }

    @Test
    public void a_new_file_no_longer_stops_the_waiting_ones() throws Exception
    {
        final String helper = code("HelperGroup.java");
        assertFalse(helper.contains("cancelStaleOutgoingChunkedSends"));
        final String send = body(helper, "static void send_group_file(");
        final int mark = send.indexOf("NgcGroupFileTransfer.markOutgoingPending(");
        assertTrue(mark >= 0);
        assertTrue(mark < send.indexOf("NgcGroupFileTransfer.sendGroupFileChunked(g)"));
        assertTrue(mark < send.indexOf("NgcGroupFileTransfer.sendGroupFileSmall(g)"));
    }

    @Test
    public void nothing_but_the_send_says_our_file_not_sent() throws Exception
    {
        // The batch worker said "not sent" after 30 s without progress, and the outgoing watchdog after 30 s.
        final String queue = body(code("OutgoingAttachmentQueue.java"), "private static void waitForGroupFileSend(");
        assertFalse(queue.contains("mark_ngc_file_transfer_failed"));
        assertFalse(queue.contains("record_ngc_transfer_started"));
        assertTrue(queue.contains("NgcGroupFileTransfer.isOutgoingGoing("));
        assertFalse(code("HelperGroup.java").contains("arm_ngc_outgoing_file_timeout"));

        // The row: from what the send says, not from 30 s without progress or from the group's connection.
        final String row = body(code("ChatTransferProgressHelper.java"), "static Snapshot snapshotFromGroupMessage(");
        final int phase = row.indexOf("outgoingGroupPhase(");
        assertTrue(phase > 0);
        final String outgoingBranch = block(row, row.indexOf('{', row.lastIndexOf("if (outgoing)", phase)));
        assertTrue(outgoingBranch.contains("outgoingGroupPhase("));
        assertFalse(outgoingBranch.contains("is_ngc_transfer_stalled"));
        assertFalse(outgoingBranch.contains("tox_group_is_connected"));
    }

    @Test
    public void serving_a_members_request_leaves_our_files_state_alone() throws Exception
    {
        // A file that had gone showed as going at 0 % again, then as not sent 30 s later.
        final String ngc = code("NgcGroupFileTransfer.java");
        final String request = body(ngc, "static void handleIncomingFileRequest(");
        assertFalse(request.contains("record_ngc_transfer_started"));
        assertFalse(request.contains("clear_ngc_file_transfer_failed"));
        assertFalse(request.contains("clear_ngc_outgoing_send_resume_state"));
        // And it never takes the place of the file's own send, nor swallows it: the own send is let in by its key
        // alone, a resend of the whole file does not run while it does, and a resend of the chunks a member asked for
        // has a key of its own (SERVES) and leaves where the own send stands alone.
        final String enqueue = body(ngc, "private static void enqueueChunkedSend(");
        final int own = enqueue.indexOf("else if (inflightOwnSends.contains(key))");
        assertTrue(own > 0);
        assertTrue(own < enqueue.indexOf("inflightServes.add(key)"));
        assertEquals(1, count(enqueue, "inflightOwnSends.add(key)"));
        final int ownLetIn = enqueue.indexOf("queued = inflightOwnSends.add(key);");
        assertTrue(ownLetIn > 0);
        assertTrue(ownLetIn < enqueue.indexOf("else if (serveKey != null)"));
        final String selective = body(ngc, "private static void sendGroupFileSelectiveOnWorker(");
        assertFalse(selective.contains("inflightOwnSends"));
        assertFalse(selective.contains("record_ngc_outgoing_chunk_sent"));
    }

    @Test
    public void a_members_request_during_the_own_send_is_served_not_dropped() throws Exception
    {
        // QA 0.2.57 (g5, g6): a group file of 6 MiB, its own send 96 s at the relay pace. Every request of a member
        // during it was dropped ("send:skip already in-flight"); an iPhone spent its 24 requests on it and gave the
        // file up, and so did the desktop. The rule is tested in NgcFileServesTest; the resends call toxcore, so this
        // reads the source.
        final String enqueue = body(code("NgcGroupFileTransfer.java"), "private static void enqueueChunkedSend(");
        final int selective = enqueue.indexOf("else if (serveKey != null)");
        assertTrue(selective > 0);
        // A request for chunks is looked at before the own send's branch, which is for the whole file alone.
        assertTrue(selective < enqueue.indexOf("else if (inflightOwnSends.contains(key))"));
        final String serve = block(enqueue, enqueue.indexOf('{', selective));
        assertFalse(serve.contains("queued = false"));
        assertTrue(serve.contains("queued = SERVES.ask(serveKey, asked);"));
        // Queued, it goes on the resends' thread, as one that came after the own send.
        assertTrue(enqueue.contains("final ExecutorService exec = isUserSend ? SEND_EXECUTOR : RESEND_EXECUTOR;"));
        assertTrue(enqueue.contains("sendGroupFileSelectiveOnWorker(g, targetPeerId, serveKey)"));
    }

    @Test
    public void the_chunks_the_own_send_passed_reach_the_member_during_it() throws Exception
    {
        // The own send counts a chunk as sent once any member took it, and does not come back for a member that toxcore
        // refused it to (its send array full). A request while it runs takes the chunks it passed (NgcFileServesTest),
        // as the desktop's answerGroupFileRequest does; a request when it does not run, all that was asked, as before.
        final String ngc = code("NgcGroupFileTransfer.java");
        final String enqueue = body(ngc, "private static void enqueueChunkedSend(");
        final String serve = block(enqueue, enqueue.indexOf('{', enqueue.indexOf("else if (serveKey != null)")));
        final int at = serve.indexOf("final int ownSendAt = inflightOwnSends.contains(key) ? ownSendPassed(g) : -1;");
        assertTrue(at >= 0);
        final int passed = serve.indexOf(
                "final boolean[] asked = (ownSendAt < 0) ? wanted : NgcFileServes.passedByOwnSend(wanted, ownSendAt);");
        assertTrue(passed > at);
        assertTrue(passed < serve.indexOf("SERVES.ask(serveKey, asked)"));
        // Where the own send stands: past the last chunk it sent (its resume point), recorded once that chunk went.
        assertTrue(body(ngc, "private static int ownSendPassed(final GroupMessage g)").contains(
                "return HelperGroup.ngc_outgoing_last_chunk_index(g.group_identifier, g.msg_id_hash) + 1;"));
        final String send = body(ngc, "private static void sendChunkedFrom(");
        final int went = send.indexOf("if (res != SEND_OK)", send.indexOf("send.chunk("));
        assertTrue(went > 0);
        assertTrue(send.indexOf("HelperGroup.record_ngc_outgoing_chunk_sent(g.group_identifier, g.msg_id_hash, idx);")
                   > went);
        // They go to that member alone, at the pace of the others, relays included (PacedSend).
        final String selective = body(ngc, "private static void sendGroupFileSelectiveOnWorker(");
        assertTrue(selective.contains("new PeerSendTargets(new long[]{targetPeerId}, false);"));
        assertTrue(selective.contains("new PacedSend("));
        assertTrue(selective.contains("SERVES.takeNext(serveKey, at)"));
    }

    @Test
    public void a_members_request_of_chunks_merges_into_its_resend_and_no_cooldown_drops_it() throws Exception
    {
        // QA 0.2.57: the packets of one request after its first, and an iPhone's repeats, were dropped by the cooldown
        // of 2.5 s per file, and a request while another member's resend of the file ran by the one resend per file.
        final String ngc = code("NgcGroupFileTransfer.java");
        assertFalse(ngc.contains("SELECTIVE_RESEND_COOLDOWN_MS"));
        assertFalse(ngc.contains("selectiveResendTs"));
        final String enqueue = body(ngc, "private static void enqueueChunkedSend(");
        // One resend per file and member.
        assertTrue(enqueue.contains("(!isUserSend && (wanted != null)) ? (key + \":\" + targetPeerId) : null"));
        assertTrue(enqueue.contains("sendGroupFileSelectiveOnWorker(g, targetPeerId, serveKey)"));
        // Ended however it ends, also when it was not queued.
        assertEquals(2, count(enqueue, "endInflightSend(isUserSend, key, serveKey)"));
        assertTrue(body(ngc, "private static void endInflightSend(").contains("SERVES.end(serveKey)"));
        // The resend sends what its member asked for, the requests that came meanwhile counted.
        final String selective = body(ngc, "private static void sendGroupFileSelectiveOnWorker(");
        assertTrue(selective.contains("SERVES.takeNext(serveKey, at)"));
    }

    @Test
    public void an_incoming_file_asks_for_its_missing_chunks_as_a_list() throws Exception
    {
        // QA 0.2.57: an iPhone dropped Android's bitmap request (55 bytes for 86 chunks).
        final String request = body(code("NgcGroupFileTransfer.java"), "static void sendFileResendRequest(");
        assertTrue(request.contains("buildListedFileRequestPackets(msgId, missing)"));
        // Without an assembly yet it asks for the whole file, as before.
        assertTrue(request.contains("buildFileRequestPacket(msgId, totalChunks, null)"));
        assertFalse(code("NgcGroupFileTransfer.java").contains("buildMissingChunkBitmap"));
    }

    @Test
    public void a_user_cancel_ends_the_wait() throws Exception
    {
        final String cancel = body(code("HelperGroup.java"), "static void cancel_ngc_file_send_by_user(");
        assertTrue(cancel.contains("NgcGroupFileTransfer.forgetOutgoing(groupId, msgIdHash)"));
    }

    @Test
    public void an_incoming_file_asks_within_its_budget() throws Exception
    {
        final String ngc = code("NgcGroupFileTransfer.java");
        final String request = body(ngc, "static void sendFileResendRequest(");
        final int take = request.indexOf("NACKS.takeRequest(");
        assertTrue(take > 0);
        assertTrue(take < request.indexOf("sendFileRequestPrivately("));
        assertTrue(take < request.indexOf("tox_group_send_custom_packet("));
        assertFalse(ngc.contains("FILE_RESEND_REQUEST_COOLDOWN_MS"));

        // The watchdog gives the file up after its minute of silence before it asks again; a round without its
        // sender is not silence.
        final String watchdog = body(code("HelperGroup.java"), "static void arm_ngc_incoming_file_timeout(");
        final int silent = watchdog.indexOf("NgcGroupFileTransfer.receiveSilentRound(groupId, msgIdHash)");
        assertTrue(silent > 0);
        assertTrue(silent < watchdog.indexOf("NgcGroupFileTransfer.sendFileResendRequest(m)"));
        assertTrue(watchdog.contains("NgcGroupFileTransfer.receiveSenderAway(groupId, msgIdHash)"));

        // A BEGIN of a file being assembled, and a chunk it has, end the silence but give no request back; only a new
        // assembly and the user's tap do.
        final String begin = body(ngc, "static void handleIncomingBegin(");
        final int dupAt = begin.indexOf("handleBegin:dup-skip");
        assertTrue(dupAt > 0);
        final String dupSkip = begin.substring(dupAt, begin.indexOf("flushOrphanChunks(groupNumber, assemblyKey)", dupAt));
        assertTrue(dupSkip.contains("NACKS.heard("));
        assertFalse(dupSkip.contains("NACKS.restart("));
        final String apply = body(ngc, "private static void applyChunk(");
        final String dupChunk = block(apply, apply.indexOf('{', apply.indexOf("if (asm.received[chunkIndex])")));
        assertTrue(dupChunk.contains("NACKS.heard("));
        assertTrue(body(code("HelperGroup.java"), "static void mark_ngc_manual_download_requested(")
                           .contains("NgcGroupFileTransfer.restartReceiveRequests(groupId, msgIdHash)"));

        // Given up, it is not asked for by the automatic retries; its row offers to download it again.
        final String schedule = body(code("HelperGroup.java"), "private static void schedule_auto_retry_group_file(");
        assertTrue(schedule.contains("NgcGroupFileTransfer.hasGivenUpReceive("));
        assertTrue(body(code("HelperGroup.java"), "static void kickstart_incoming_chunked_file_transfer(")
                           .contains("NgcGroupFileTransfer.hasGivenUpReceive("));
    }

    @Test
    public void the_chat_list_marks_our_group_file_by_its_send() throws Exception
    {
        // The list read our group file's mark from its message id, which a file never gets: always in transit.
        final String delivery = body(code("AirRepository.java"), "static AirModel.Delivery groupDelivery(");
        final int file = delivery.indexOf("message.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value");
        assertTrue(file > 0);
        final int phase = delivery.indexOf(
                "ChatTransferProgressHelper.outgoingGroupPhase(message.group_identifier, message.msg_id_hash)");
        assertTrue(phase > file);
        assertTrue(delivery.indexOf("return AirModel.Delivery.ofGroupFile(", file) > file);
        assertTrue(phase < delivery.indexOf("MessageStatusHelper.resolveGroupOutgoing(message)"));
        // The same phase as the file's row.
        final String row = body(code("ChatTransferProgressHelper.java"), "static Snapshot snapshotFromGroupMessage(");
        assertTrue(row.contains("outgoingGroupPhase(message.group_identifier, message.msg_id_hash)"));
    }

    @Test
    public void a_download_resumed_after_a_restart_keeps_its_chunks() throws Exception
    {
        // QA live 2026-11-15: the write cache opened the partial file with a FileOutputStream, which empties it, so
        // a download resumed from its .rxmap came out whole with 77 of its 86 chunks zero.
        assertTrue(NgcGroupFileTransfer.bypassesWriteCache(true, false));
        assertFalse(NgcGroupFileTransfer.bypassesWriteCache(true, true));
        assertFalse(NgcGroupFileTransfer.bypassesWriteCache(false, false));
        assertFalse(NgcGroupFileTransfer.bypassesWriteCache(false, true));

        final String ngc = code("NgcGroupFileTransfer.java");
        final String apply = body(ngc, "private static void applyChunk(");
        assertTrue(apply.contains("writeChunk(asm, offset, chunkSize, chunkData)"));
        assertFalse(apply.contains("write_chunk_to_VFS_file("));
        final String write = body(ngc, "private static void writeChunk(");
        final int bypass = write.indexOf("bypassesWriteCache(asm.resumedFromDisk, HelperGeneric.has_vfs_write_cache(asm.outPath))");
        assertTrue(bypass > 0);
        assertTrue(write.indexOf("write_chunk_to_VFS_file__no_extra_write_cache(", bypass) > bypass);
        assertTrue(body(ngc, "private static void loadReceivedBitmap(").contains("asm.resumedFromDisk = count > 0"));
    }

    private static int count(final String text, final String what)
    {
        int n = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length()))
        {
            n++;
        }
        return n;
    }

    @Test
    public void the_row_of_our_file_says_sent_once_it_went() throws Exception
    {
        // QA live: the "sent" of a paced file came within the per-chunk throttle of its last chunk and was eaten;
        // the row said "54 KB left, sending" until the chat was opened again.
        final String sent = body(code("NgcGroupFileTransfer.java"), "static void outgoingSent(");
        assertTrue(sent.contains("HelperGroup.refresh_group_file_row(g.group_identifier, g.msg_id_hash)"));
        final String row = body(code("HelperGroup.java"), "static void refresh_group_file_row(");
        assertTrue(row.indexOf("ngc_progress_refresh_ts.remove(") < row.indexOf("post_group_file_progress_refresh("));
        // Every end of an own send that went goes through outgoingSent, after its progress says the whole file.
        final String send = body(code("NgcGroupFileTransfer.java"), "private static void sendChunkedFrom(");
        assertEquals(2, count(send, "outgoingSent(g)"));
        final String image = body(code("HelperGroup.java"), "static void send_group_image(");
        assertEquals(1, count(image, "NgcGroupFileTransfer.outgoingSent(g)"));
    }

    @Test
    public void a_step_of_a_group_file_redraws_its_progress_alone() throws Exception
    {
        // Beta (Khandaq Testers, Android, 8240; 0.2.67 on a Galaxy S25): the picture of a video being sent to a group
        // went blank and came back about twice a second. Every step of the transfer drew the whole row again, and the
        // whole row puts the preview back. A step under 100 % now draws the progress alone, as a 1:1 transfer does.
        final String group = code("HelperGroup.java");
        assertTrue(body(group, "private static void set_ngc_file_transfer_progress(")
                           .contains("post_group_file_progress_refresh(groupId, msgIdHash, percent < 100)"));
        final String received = body(group, "static void notify_group_file_recv_progress_bytes(");
        assertTrue(received.contains("post_group_file_progress_refresh(groupId, msgIdHash, percent < 100)"));
        assertFalse(received.contains("post_group_file_progress_refresh(groupId, msgIdHash)"));

        // A step and a change of state are throttled apart: a step must not eat the "sent" that comes right after it.
        final String post = body(group, "post_group_file_progress_refresh(final String groupId, final String msgIdHash,");
        assertTrue(post.contains("(progressOnly ? PROGRESS_STEP_KEY : \"\")"));
        assertTrue(post.contains("refresh_file_progress_by_hash(msgIdHash, progressOnly)"));
        assertTrue(body(code("GroupMessageListFragment.java"),
                        "void refresh_file_progress_by_hash(final String msgIdHash, final boolean progressOnly)")
                           .contains("adapter.refresh_file_progress_by_hash(msgIdHash, progressOnly)"));

        final String adapter = code("GroupMessagelistAdapter.java");
        assertTrue(body(adapter, "public void refresh_file_progress_by_hash(final String msgIdHash, final boolean progressOnly)")
                           .contains("this.notifyItemChanged(pos, MessagelistAdapter.PAYLOAD_TRANSFER_PROGRESS)"));
        // That payload draws the progress of a group file by itself.
        assertTrue(body(adapter, "public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,")
                           .contains("ChatTransferProgressHelper.applyGroup(context, holder.itemView, m, m.direction == 1)"));
    }

    @Test
    public void a_video_preview_drawn_again_is_back_in_the_same_frame() throws Exception
    {
        // A row drawn whole (a change of state, a reaction) clears its preview first (hideMediaPreview) and puts the
        // video's picture back from the cache of composed pictures. From Glide's memory it is there in the same frame;
        // with the memory skipped it was decoded again on another thread, a few frames later: a blink.
        final String video = body(code("ChatMediaHelper.java"),
                                  "static void bindVideoPreview(final Context context, final String vfsPath,");
        assertTrue(video.contains("video_thumb_cache.get(vfsPath)"));
        assertFalse(video.contains("skipMemoryCache(true)"));
    }

    @Test
    public void a_group_number_is_the_files_group_only_while_it_has_the_files_id()
    {
        // The id of the group at the number (tox_group_by_groupnum__wrapper), in whatever case it comes.
        assertTrue(NgcGroupFileTransfer.isGroupOfFile("5a1b2c3d4e5f", "5A1B2C3D4E5F"));
        // The number names another group (it took the number of a group left), or none.
        assertFalse(NgcGroupFileTransfer.isGroupOfFile("5a1b2c3d4e5f", "5a1b2c3d4e60"));
        assertFalse(NgcGroupFileTransfer.isGroupOfFile("5a1b2c3d4e5f", null));
        assertFalse(NgcGroupFileTransfer.isGroupOfFile(null, "5a1b2c3d4e5f"));
    }

    @Test
    public void a_paced_send_stops_when_its_group_number_names_another_group() throws Exception
    {
        // A group left in the middle of a send frees its number, and toxcore gives it to the next group joined or
        // created: the send went on with the number, and that group's members got the file (its BEGIN names no group).
        // The send calls toxcore, so this reads the source.
        final String paced = body(code("NgcGroupFileTransfer.java"), "private static final class PacedSend");
        final String sendLoop = body(paced, "private int send(final byte[] pkt, final boolean isBegin)");
        final String pass = block(sendLoop, sendLoop.indexOf('{', sendLoop.indexOf("while (true)")));
        final int check = pass.indexOf(
                "if (!isGroupOfFile(g.group_identifier, HelperGroup.tox_group_by_groupnum__wrapper(groupNum)))");
        assertTrue(check > 0);
        assertTrue(check < pass.indexOf("targets.refreshOnlinePeers(groupNum)"));
        assertTrue(check < pass.indexOf("tox_group_send_custom_private_packet("));
        assertTrue(block(pass, pass.indexOf('{', check)).contains("return SEND_NO_MEMBER;"));
        // Nobody took the packet: the own send waits for a member, and its group is found by its id again.
        assertTrue(body(code("NgcGroupFileTransfer.java"), "private static void endUnsent(").contains("waitForMember(g,"));
        assertTrue(body(code("NgcGroupFileTransfer.java"), "static void resumeWaitingSends()")
                           .contains("tox_group_by_groupid__wrapper(groupId)"));
    }

    @Test
    public void a_small_file_does_not_wait_behind_the_chunked_sends() throws Exception
    {
        // A chunked send holds a thread of SEND_EXECUTOR for the whole of its paced send, and small files went on the
        // same three threads: a photo waited behind three big files, its row saying "sending".
        final String ngc = code("NgcGroupFileTransfer.java");
        assertTrue(ngc.contains(
                "private static final ExecutorService SMALL_SEND_EXECUTOR = Executors.newSingleThreadExecutor("));
        final String small = body(ngc, "static void sendGroupFileSmall(");
        assertTrue(small.contains("SMALL_SEND_EXECUTOR.execute("));
        assertFalse(small.replace("SMALL_SEND_EXECUTOR", "").contains("SEND_EXECUTOR"));
        assertEquals(1, count(ngc, "SMALL_SEND_EXECUTOR.execute("));
        // The chunked sends keep their pool, the resends for a member their thread.
        final String enqueue = body(ngc, "private static void enqueueChunkedSend(");
        assertTrue(enqueue.contains("final ExecutorService exec = isUserSend ? SEND_EXECUTOR : RESEND_EXECUTOR;"));
        assertTrue(ngc.contains("Executors.newFixedThreadPool(MAX_PARALLEL_USER_SENDS,"));
    }

    @Test
    public void a_download_whose_map_is_not_read_starts_from_an_empty_file() throws Exception
    {
        // A map of an earlier version may mark chunks its file no longer has (NgcRxMapTest): it is not read, and the
        // download keeps nothing of its file.
        final String ngc = code("NgcGroupFileTransfer.java");
        final String load = body(ngc, "private static void loadReceivedBitmap(");
        final int read = load.indexOf("rxMapChunks(readRxMapFile(rxMapPath(asm.outPath)), asm.totalChunks,");
        assertTrue(read > 0);
        final int none = load.indexOf("if (onDisk == null)", read);
        assertTrue(none > read);
        assertEquals("return;", block(load, load.indexOf('{', none)).trim());
        assertTrue(none < load.indexOf("asm.received[i] = true;"));
        assertTrue(none < load.indexOf("asm.resumedFromDisk = count > 0"));
        assertTrue(body(ngc, "private static void persistReceivedBitmap(")
                           .contains("writeRxMapFile(rxMapPath(asm.outPath), rxMapBytes(asm.received, asm.totalSize))"));
        // Not resumed, its first chunk goes through the write cache, which opens the file with a FileOutputStream
        // (not to append): IOCipher empties the file (O_TRUNC), as for a fresh download.
        assertFalse(NgcGroupFileTransfer.bypassesWriteCache(false, false));
        final String cached = body(code("HelperGeneric.java"),
                                   "static void write_chunk_to_VFS_file__with_custom_write_cache(");
        assertTrue(cached.contains("fos = new BufferedOutputStreamCustom(file_name_with_path);"));
        final String stream = body(code("BufferedOutputStreamCustom.java"), "public BufferedOutputStreamCustom(String path)");
        assertTrue(stream.contains("super(path);"));
    }

    /**
     * KHANDAQ (0.2.59, group files): QA live of 0.2.59. The last of 171 chunks came with 80 missing, and the file read
     * as downloaded: it is written in place, so it had its full length, and isGroupMessageMediaReady went by the
     * length. The watchdog, the automatic retry and the start of a download all ask it, and none asked for the rest.
     */
    @Test
    public void a_file_whose_last_chunk_came_first_is_not_ready() throws Exception
    {
        final String ready = body(code("HelperFiletransfer.java"), "static boolean isGroupMessageMediaReady(");
        final int length = ready.indexOf("actualLength < message.filesize");
        assertTrue(length > 0);
        assertTrue(length < ready.indexOf("NgcGroupFileTransfer.isReceiveUnfinished(message, mediaPath)"));

        final String ngc = code("NgcGroupFileTransfer.java");
        final String unfinished = body(ngc, "static boolean isReceiveUnfinished(");
        assertTrue(unfinished.contains("if (message == null || message.direction == 1"));
        assertTrue(unfinished.contains("!shouldUseChunkedTransfer(message.filesize)"));
        final int assembly = unfinished.indexOf("incomingAssemblyOf(message.group_identifier, message.msg_id_hash)");
        assertTrue(assembly > 0);
        assertTrue(unfinished.contains("return asm.receivedCount < asm.totalChunks;"));
        assertTrue(assembly < unfinished.indexOf("rxMapMissesChunksAt(mediaPath, message.filesize)"));

        // The map is written once the last chunk landed, missing chunks or not, and a map written is looked for
        // again: the count of maps written goes up after the map is on disk.
        final String chunk = body(ngc, "private static void applyChunk(");
        assertTrue(chunk.contains("else if ((asm.receivedCount % 16) == 0 || chunkIndex + 1 >= asm.totalChunks)"));
        final String persist = body(ngc, "private static void persistReceivedBitmap(");
        final int write = persist.indexOf("writeRxMapFile(rxMapPath(asm.outPath),");
        assertTrue(write > 0);
        assertTrue(write < persist.indexOf("RXMAP_WRITES.incrementAndGet();"));
        final String at = body(ngc, "private static boolean rxMapMissesChunksAt(");
        assertTrue(at.indexOf("final long writes = RXMAP_WRITES.get();") < at.indexOf("readRxMapFile(rxMapPath(path))"));
    }

    /**
     * KHANDAQ (0.2.59, group files): a sender not in the group is away. It counted as online while the group was
     * connected: a phone back on another address timed its sender out, and its watchdog gave the file up as silent a
     * second before the sender came back.
     */
    @Test
    public void a_sender_not_in_the_group_is_away() throws Exception
    {
        final String online = body(code("HelperGroup.java"), "static boolean is_group_message_sender_online(");
        final int lookup = online.indexOf("get_group_peernum_from_peer_pubkey(");
        assertTrue(lookup > 0);
        final int missing = online.indexOf("if (peer_id < 0)", lookup);
        assertTrue(missing > lookup);
        assertEquals("return false;", block(online, online.indexOf('{', missing)).trim());
        assertFalse(online.contains("tox_group_is_connected("));
    }

    private static String body(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        return block(text, text.indexOf('{', at));
    }

    private static String block(final String text, final int open)
    {
        assertTrue("no opening brace", open >= 0);
        int depth = 0;
        for (int i = open; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (c == '{')
            {
                depth++;
            }
            else if (c == '}')
            {
                depth--;
                if (depth == 0)
                {
                    return text.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces");
    }

    private static String source(final String name) throws java.io.IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final java.io.File file = new java.io.File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()),
                                  java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new java.io.File("").getAbsolutePath());
    }
}

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
        // requests for missing chunks (sendFileResendRequest).
        final String request = body(ngc, "static void sendFileResendRequest(");
        assertEquals(count(ngc, "tox_group_send_custom_private_packet("),
                     count(paced, "tox_group_send_custom_private_packet(")
                     + count(request, "tox_group_send_custom_private_packet("));
        final String sendLoop = body(paced, "private int send(final byte[] pkt, final boolean isBegin)");
        assertTrue(sendLoop.indexOf("awaitPace(g)") < sendLoop.indexOf("tox_group_send_custom_private_packet("));
        assertTrue(sendLoop.contains("spendPace(pkt.length, took)"));
        final String newMembers = body(paced, "private boolean sendBeginToNewMembers()");
        assertTrue(newMembers.indexOf("awaitPace(g)") < newMembers.indexOf("tox_group_send_custom_private_packet("));
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
        assertTrue(image.indexOf("NgcGroupFileTransfer.awaitPace(g)") < packet);
        assertTrue(image.indexOf("NgcGroupFileTransfer.spendPace(") > packet);
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
        // And it never takes the place of the file's own send.
        final String enqueue = body(ngc, "private static void enqueueChunkedSend(");
        assertTrue(enqueue.contains("!inflightOwnSends.contains(key) && inflightServes.add(key)"));
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
        assertTrue(take < request.indexOf("tox_group_send_custom_private_packet("));
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

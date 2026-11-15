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

    private static int count(final String text, final String what)
    {
        int n = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length()))
        {
            n++;
        }
        return n;
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

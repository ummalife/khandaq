package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.ChatTransferProgressHelper.Phase;
import static com.zoffcc.applications.trifa.ChatTransferProgressHelper.isAbandonableQueuedSend;
import static com.zoffcc.applications.trifa.ChatTransferProgressHelper.outgoingGroupPhase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56, QA live GW5-GW10): what the row of one of our group files says, from what its send says of it
 * (ChatTransferProgressHelper.outgoingGroupPhase).
 *
 * QA: a file sent with nobody else online said "Not sent" after about a minute while it was still ours to send; a
 * small one said "sent" when it had gone to nobody; and after a restart every chunked file of ours showed as waiting.
 */
public class GroupFileSendPhaseTest
{
    /** complete, cancelled, failed, waiting, going, pending */
    private static Phase phase(final boolean complete, final boolean cancelled, final boolean failed,
                               final boolean waiting, final boolean going, final boolean pending)
    {
        return outgoingGroupPhase(complete, cancelled, failed, waiting, going, pending);
    }

    @Test
    public void a_file_that_waits_for_a_member_waits_and_offers_cancel()
    {
        // Its send found nobody: it is not "not sent", and it is not going either.
        final Phase waiting = phase(false, false, false, true, false, true);
        assertEquals(Phase.PENDING, waiting);
        assertTrue(isAbandonableQueuedSend(waiting, true));
    }

    @Test
    public void a_file_that_goes_shows_its_progress()
    {
        assertEquals(Phase.TRANSFERRING, phase(false, false, false, false, true, true));
    }

    @Test
    public void a_file_that_went_whole_is_sent()
    {
        assertEquals(Phase.COMPLETE, phase(true, false, false, false, false, false));
        // Whatever else is still set of it.
        assertEquals(Phase.COMPLETE, phase(true, false, true, true, true, true));
    }

    @Test
    public void only_a_cancel_or_a_failure_of_this_device_is_not_sent()
    {
        assertEquals(Phase.FAILED, phase(false, false, true, false, false, true));
        // The cancel holds even over a file that was waiting: its tombstone outlives the process.
        assertEquals(Phase.FAILED, phase(false, true, false, true, false, true));
        assertEquals(Phase.FAILED, phase(false, true, false, false, true, true));
    }

    @Test
    public void a_file_an_earlier_run_did_not_send_whole_waits()
    {
        // Pending from its mark in the database, before the service's tick takes it.
        assertEquals(Phase.PENDING, phase(false, false, false, false, false, true));
    }

    @Test
    public void a_file_with_nothing_left_to_send_is_sent()
    {
        // A row of an earlier run that went whole: no mark, no send. It showed as waiting forever.
        assertEquals(Phase.COMPLETE, phase(false, false, false, false, false, false));
    }

    @Test
    public void the_chat_list_counts_only_a_file_that_waits_or_goes()
    {
        // Every group file of ours counted as in transit in the chat list, for good: it never gets a message id.
        assertEquals(AirModel.Delivery.TRANSIT, AirModel.Delivery.ofGroupFile(Phase.PENDING));
        assertEquals(AirModel.Delivery.TRANSIT, AirModel.Delivery.ofGroupFile(Phase.TRANSFERRING));
        assertEquals(AirModel.Delivery.SENT, AirModel.Delivery.ofGroupFile(Phase.COMPLETE));
        assertEquals(AirModel.Delivery.SENT,
                     AirModel.Delivery.ofGroupFile(phase(false, false, false, false, false, false)));
        // Cancelled, or not sent for a reason of this device: it will not go on its own, so it has no mark.
        assertNull(AirModel.Delivery.ofGroupFile(Phase.FAILED));
    }
}

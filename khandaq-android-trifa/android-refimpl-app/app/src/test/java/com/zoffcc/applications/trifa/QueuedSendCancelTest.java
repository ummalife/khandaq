package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.ChatTransferProgressHelper.Phase;
import static com.zoffcc.applications.trifa.ChatTransferProgressHelper.isAbandonableQueuedSend;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (#247) — which transfer rows offer a cancel while nothing is moving yet.
 *
 * QA found a group file queued against an absent peer sitting in the chat forever with no cancel and
 * no retry. The cause was that three renderers — the inline buttons, the media overlay and the
 * plain-file row — each decided this for themselves, and all three only considered TRANSFERRING. The
 * rule now has one definition; these pin it so a future edit to any one renderer cannot quietly
 * reintroduce the dead end, and so the deliberately excluded cases stay excluded.
 */
public class QueuedSendCancelTest
{
    @Test
    public void a_queued_outgoing_send_can_be_abandoned()
    {
        // The QA case: our file, queued, never started because no peer was reachable.
        assertTrue(isAbandonableQueuedSend(Phase.PENDING, true));
    }

    @Test
    public void a_queued_incoming_transfer_cannot()
    {
        // Incoming PENDING means the sender has not offered the file yet — there is no local
        // transfer to abandon, and the incoming retry path already covers that row.
        assertFalse(isAbandonableQueuedSend(Phase.PENDING, false));
    }

    @Test
    public void no_other_phase_qualifies_in_either_direction()
    {
        // TRANSFERRING and FAILED have their own affordances and must not be routed through this
        // rule; IDLE and COMPLETE have nothing to cancel at all.
        for (final Phase phase : new Phase[]{Phase.IDLE, Phase.TRANSFERRING, Phase.COMPLETE, Phase.FAILED})
        {
            assertFalse(phase.name(), isAbandonableQueuedSend(phase, true));
            assertFalse(phase.name(), isAbandonableQueuedSend(phase, false));
        }
    }

    @Test
    public void every_phase_is_covered_by_these_cases()
    {
        // If a phase is ever added, this fails and forces a decision about it rather than letting it
        // default to "no affordance" — which is the exact shape of the bug being fixed.
        assertTrue(Phase.values().length == 5);
    }
}

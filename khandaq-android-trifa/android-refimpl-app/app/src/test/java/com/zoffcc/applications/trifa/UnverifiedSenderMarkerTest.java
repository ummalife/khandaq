package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — which group rows must be marked "sender not verified"
 * (audit2 finding 1, step 2 of DESIGN-ngc-signed-history-sync.md).
 *
 * A row that reached us through history sync carries an author nobody signed for: the transport
 * authenticates the SYNCING peer, not the claimed original sender. The marker is what stops such a row
 * from rendering identically to a live, toxcore-authenticated one.
 *
 * These exist because the positive case is hard to reach on a device — it needs an incoming row that
 * arrived via history sync, which needs a second live group peer. The rule is covered here even while
 * that is not reproducible, so a later refactor cannot quietly drop the marker.
 */
public class UnverifiedSenderMarkerTest
{
    private static GroupMessage row(final boolean wasSynced)
    {
        final GroupMessage m = new GroupMessage();
        m.was_synced = wasSynced;
        return m;
    }

    @Test
    public void marksARowThatArrivedThroughHistorySync()
    {
        assertTrue(GroupMessageListHolder_text_incoming_not_read
                           .should_mark_unverified_sender(false, row(true)));
    }

    /** A live message is authenticated by the transport — marking it would cry wolf. */
    @Test
    public void doesNotMarkALiveMessage()
    {
        assertFalse(GroupMessageListHolder_text_incoming_not_read
                            .should_mark_unverified_sender(false, row(false)));
    }

    /** System rows have no claimed author, so "unverified sender" is meaningless on them. */
    @Test
    public void doesNotMarkASystemMessageEvenWhenSynced()
    {
        assertFalse(GroupMessageListHolder_text_incoming_not_read
                            .should_mark_unverified_sender(true, row(true)));
        assertFalse(GroupMessageListHolder_text_incoming_not_read
                            .should_mark_unverified_sender(true, row(false)));
    }

    /** A recycled holder can be asked to bind before its message is set; that must not mark anything. */
    @Test
    public void doesNotMarkANullRow()
    {
        assertFalse(GroupMessageListHolder_text_incoming_not_read
                            .should_mark_unverified_sender(false, null));
        assertFalse(GroupMessageListHolder_text_incoming_not_read
                            .should_mark_unverified_sender(true, null));
    }

    /** Default-constructed rows are not synced, so a freshly built message is never marked. */
    @Test
    public void aFreshlyConstructedRowIsNotMarked()
    {
        assertFalse(GroupMessageListHolder_text_incoming_not_read
                            .should_mark_unverified_sender(false, new GroupMessage()));
    }
}

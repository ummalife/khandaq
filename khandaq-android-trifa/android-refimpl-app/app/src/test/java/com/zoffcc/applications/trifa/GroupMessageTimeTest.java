package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * KHANDAQ (QA live 0.2.61): a group text a member synced to us when we came back online showed the time it
 * reached us (04:44), not the time it was written (04:42), among neighbours the transcript places by the time
 * they were sent. HelperGeneric.group_message_time_ms is the time an entry of a group shows.
 */
public class GroupMessageTimeTest
{
    private static final long WRITTEN = 1_763_520_120_000L;
    private static final long ARRIVED = WRITTEN + 2 * 60 * 1000L;

    private static GroupMessage message(final boolean synced, final long sent, final long rcvd)
    {
        final GroupMessage m = new GroupMessage();
        m.was_synced = synced;
        m.sent_timestamp = sent;
        m.rcvd_timestamp = rcvd;
        return m;
    }

    @Test
    public void aSyncedTextShowsTheTimeItWasWritten()
    {
        assertEquals(WRITTEN, HelperGeneric.group_message_time_ms(message(true, WRITTEN, ARRIVED), false));
    }

    @Test
    public void aSyncedTextWithoutItsSendingTimeShowsItsArrival()
    {
        assertEquals(ARRIVED, HelperGeneric.group_message_time_ms(message(true, 0, ARRIVED), false));
    }

    @Test
    public void aMembersLiveTextStillShowsItsArrival()
    {
        assertEquals(ARRIVED, HelperGeneric.group_message_time_ms(message(false, WRITTEN, ARRIVED), false));
        assertEquals(WRITTEN, HelperGeneric.group_message_time_ms(message(false, WRITTEN, 0), false));
    }

    @Test
    public void oursShowsTheTimeWeSentIt()
    {
        assertEquals(WRITTEN, HelperGeneric.group_message_time_ms(message(false, WRITTEN, ARRIVED), true));
        assertEquals(WRITTEN, HelperGeneric.group_message_time_ms(message(true, WRITTEN, ARRIVED), true));
        assertEquals(ARRIVED, HelperGeneric.group_message_time_ms(message(false, 0, ARRIVED), true));
    }
}

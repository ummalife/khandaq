package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_ERROR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.from_gc_conn_state;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * KHANDAQ (0.2.56): the group connection status callback carries toxcore's raw GC_Conn_State
 * (group_common.h: CS_NONE 0, CS_DISCONNECTED 1, CS_CONNECTING 2, CS_CONNECTED 3), while
 * tox_group_is_connected() and every consumer in the app use -1 / 0 / 1.
 *
 * Compared without the conversion, a group that DISCONNECTED (1) equalled CONNECTED (1) and ran
 * on_group_connected, and a group that really CONNECTED (3) was handed to the auto-reconnect.
 */
public class GroupConnectionStatusTest
{
    // GC_Conn_State, spelled out here on purpose: these are toxcore's numbers, not ours.
    private static final int CS_NONE = 0;
    private static final int CS_DISCONNECTED = 1;
    private static final int CS_CONNECTING = 2;
    private static final int CS_CONNECTED = 3;

    @Test
    public void connectedIsConnected()
    {
        assertEquals(TOX_GROUP_CONNECTION_STATUS_CONNECTED.value, from_gc_conn_state(CS_CONNECTED));
    }

    @Test
    public void connectingIsConnecting()
    {
        assertEquals(TOX_GROUP_CONNECTION_STATUS_CONNECTING.value, from_gc_conn_state(CS_CONNECTING));
    }

    @Test
    public void aDisconnectedGroupIsNotConnected()
    {
        // The bug: raw 1 is numerically the same as CONNECTED's 1.
        assertEquals(CS_DISCONNECTED, TOX_GROUP_CONNECTION_STATUS_CONNECTED.value);
        assertNotEquals(TOX_GROUP_CONNECTION_STATUS_CONNECTED.value, from_gc_conn_state(CS_DISCONNECTED));
        assertEquals(TOX_GROUP_CONNECTION_STATUS_ERROR.value, from_gc_conn_state(CS_DISCONNECTED));
    }

    @Test
    public void anUninitialisedGroupIsNotConnected()
    {
        assertEquals(TOX_GROUP_CONNECTION_STATUS_ERROR.value, from_gc_conn_state(CS_NONE));
    }

    @Test
    public void unknownStatesAreNotConnected()
    {
        // Nothing outside 0..3 exists today; a future state must not be read as connected or connecting.
        for (final int state : new int[]{-99, -1, 4, 5, 255, Integer.MAX_VALUE, Integer.MIN_VALUE})
        {
            assertEquals("state " + state, TOX_GROUP_CONNECTION_STATUS_ERROR.value, from_gc_conn_state(state));
        }
    }

    @Test
    public void theConversionMatchesWhatTheGetterReturns()
    {
        // tox_group_is_connected() in the zoff99 toxcore 0.2.19 fork: CS_CONNECTING -> 0, CS_CONNECTED -> 1,
        // everything else -> -1. The callback and the getter must agree on every state.
        assertEquals(-1, from_gc_conn_state(CS_NONE));
        assertEquals(-1, from_gc_conn_state(CS_DISCONNECTED));
        assertEquals(0, from_gc_conn_state(CS_CONNECTING));
        assertEquals(1, from_gc_conn_state(CS_CONNECTED));
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.AirErrorCode.Call.FRIEND_ADD;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_FOUNDER_SET_PASSWORD;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_FOUNDER_SET_PEER_LIMIT;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_FOUNDER_SET_PRIVACY_STATE;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_FOUNDER_SET_TOPIC_LOCK;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_FOUNDER_SET_VOICE_STATE;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_KICK_PEER;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_SEND_MESSAGE;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_SET_ROLE;
import static com.zoffcc.applications.trifa.AirErrorCode.Call.GROUP_TOPIC_SET;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The codes under Details (redesign stage 5): the one iOS gives for the same failure, "domain number",
 * with objcTox's numbers for toxcore's errors (OCTTox.m, the fillError methods).
 */
public class AirErrorCodeTest
{
    private static final String TOX = "me.dvor.objcTox.OCTToxErrorDomain ";

    @Test
    public void a_code_is_the_domain_and_the_number_as_ios_shows_it()
    {
        assertEquals(TOX + "3", AirErrorCode.tox(GROUP_SET_ROLE, 3));
        assertEquals("org.khandaq.messenger.ProfileOpening 1",
                     AirErrorCode.profileOpening(AirErrorCode.PROFILE_SAVE_UNREADABLE));
        assertEquals("org.khandaq.messenger.ProfileOpening 2",
                     AirErrorCode.profileOpening(AirErrorCode.PROFILE_HISTORY_UNREADABLE));
        assertEquals("me.dvor.objcTox.OCTManagerErrorDomain 19",
                     AirErrorCode.manager(AirErrorCode.MANAGER_CREATE_TOX_BAD_FORMAT));
    }

    @Test
    public void every_table_has_a_code_for_each_of_toxcores_errors()
    {
        // How many errors each of toxcore's enums has (tox.h), OK not counted.
        assertEquals(ToxVars.TOX_ERR_FRIEND_ADD.values().length - 1, FRIEND_ADD.toxErrors());
        assertEquals(6, GROUP_SET_ROLE.toxErrors());
        assertEquals(6, GROUP_KICK_PEER.toxErrors());
        assertEquals(7, GROUP_SEND_MESSAGE.toxErrors());
        assertEquals(6, GROUP_TOPIC_SET.toxErrors());
        assertEquals(6, GROUP_FOUNDER_SET_PASSWORD.toxErrors());
        assertEquals(6, GROUP_FOUNDER_SET_TOPIC_LOCK.toxErrors());
        assertEquals(5, GROUP_FOUNDER_SET_PEER_LIMIT.toxErrors());
        assertEquals(5, GROUP_FOUNDER_SET_VOICE_STATE.toxErrors());
        assertEquals(5, GROUP_FOUNDER_SET_PRIVACY_STATE.toxErrors());
        for (final AirErrorCode.Call call : AirErrorCode.Call.values())
        {
            // OK, a negative number and one past the table are the call's unknown error, never a crash.
            assertEquals(call.name(), 0, call.code(0));
            assertEquals(call.name(), 0, call.code(-4));
            assertEquals(call.name(), 0, call.code(call.toxErrors() + 1));
            assertEquals(call.name(), 0, call.code(Long.MAX_VALUE));
            for (int error = 1; error <= call.toxErrors(); error++)
            {
                final int code = call.code(error);
                assertTrue(call.name() + " " + error, code >= 0 && code <= call.toxErrors());
            }
        }
    }

    @Test
    public void objcTox_renumbers_a_few_of_toxcores_errors()
    {
        // TOX_ERR_FRIEND_ADD: NULL is the unknown error, TOO_LONG 1, ALREADY_SENT 4, MALLOC 7.
        assertEquals(0, FRIEND_ADD.code(ToxVars.TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_NULL.ordinal()));
        assertEquals(1, FRIEND_ADD.code(ToxVars.TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_TOO_LONG.ordinal()));
        assertEquals(4, FRIEND_ADD.code(ToxVars.TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_ALREADY_SENT.ordinal()));
        assertEquals(5, FRIEND_ADD.code(ToxVars.TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_BAD_CHECKSUM.ordinal()));
        assertEquals(7, FRIEND_ADD.code(ToxVars.TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_MALLOC.ordinal()));
        // Kick: FAIL_ACTION (4) and FAIL_SEND (5) are both FailSend 4, SELF (6) is Permissions 3.
        assertEquals(4, GROUP_KICK_PEER.code(4));
        assertEquals(4, GROUP_KICK_PEER.code(5));
        assertEquals(3, GROUP_KICK_PEER.code(6));
        // The others keep toxcore's numbers.
        for (int error = 1; error <= 6; error++)
        {
            assertEquals(error, GROUP_SET_ROLE.code(error));
        }
        for (int error = 1; error <= 7; error++)
        {
            assertEquals(error, GROUP_SEND_MESSAGE.code(error));
        }
    }

    @Test
    public void the_bridge_result_of_a_failed_call_gives_toxcores_code()
    {
        // tox_group_mod_set_role returns the negative of toxcore's error; -99 is a call never made.
        assertEquals(TOX + "3", AirErrorCode.toxResult(GROUP_SET_ROLE, -3));
        assertEquals(TOX + "0", AirErrorCode.toxResult(GROUP_SET_ROLE, -99));
        // A result the caller took as a failure but that is no error number reads as the unknown error.
        assertEquals(TOX + "0", AirErrorCode.toxResult(GROUP_SET_ROLE, 0));
        assertEquals(TOX + "4", AirErrorCode.toxResult(GROUP_KICK_PEER, -5));
        assertEquals(TOX + "3", AirErrorCode.toxResult(GROUP_KICK_PEER, -6));
        // tox_friend_add: -(BAD_CHECKSUM) is objcTox's BadChecksum.
        final int badChecksum = ToxVars.TOX_ERR_FRIEND_ADD.TOX_ERR_FRIEND_ADD_BAD_CHECKSUM.ordinal();
        assertEquals(TOX + "5", AirErrorCode.toxResult(FRIEND_ADD, -badChecksum));
        // What this device checks before calling toxcore says what toxcore would.
        assertEquals(TOX + "1", AirErrorCode.tox(GROUP_SET_ROLE, AirErrorCode.TOX_GROUP_NOT_FOUND));
        assertEquals(TOX + "2", AirErrorCode.tox(GROUP_KICK_PEER, AirErrorCode.TOX_PEER_NOT_FOUND));
        assertEquals(TOX + "3", AirErrorCode.tox(GROUP_SET_ROLE, AirErrorCode.TOX_PERMISSIONS));
    }

    @Test
    public void a_group_message_kept_for_later_or_not_connected_has_no_code()
    {
        // -1 to -4 are toxcore's GROUP_NOT_FOUND, TOO_LONG, EMPTY and BAD_TYPE.
        assertEquals(TOX + "1", AirErrorCode.groupSend(-1));
        assertEquals(TOX + "2", AirErrorCode.groupSend(-2));
        assertEquals(TOX + "4", AirErrorCode.groupSend(-4));
        // The bridge's own failure before toxcore (no memory) is the unknown error.
        assertEquals(TOX + "0", AirErrorCode.groupSend(-22));
        // Kept to go once connected, not connected, and sent: no code.
        assertNull(AirErrorCode.groupSend(HelperGroup.GROUP_SEND_QUEUE_WHEN_UNCONNECTED));
        assertNull(AirErrorCode.groupSend(AirErrorCode.BRIDGE_NO_CALL));
        assertNull(AirErrorCode.groupSend(0));
        assertNull(AirErrorCode.groupSend(12345));
    }
}

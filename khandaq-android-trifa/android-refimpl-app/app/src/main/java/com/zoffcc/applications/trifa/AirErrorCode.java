package com.zoffcc.applications.trifa;

import androidx.annotation.Nullable;

/**
 * KHANDAQ (redesign stage 5, E21): the code an error gives for support, the one iOS gives for the same
 * failure (design/DESIGN.md, "Errors and questions on the phone"): the domain of the error and its
 * number, as "me.dvor.objcTox.OCTToxErrorDomain 3". It waits under Details with Copy and never enters
 * the words.
 * <p>
 * iOS takes toxcore's errors through objcTox, which numbers them its own way: 0 is the unknown error of
 * each call, and a few calls renumber toxcore's errors or fold two into one. The tables below are
 * objcTox's (OCTTox.m, the fillError methods), indexed by toxcore's number, so the same failure gives
 * the same code on both phones. The JNI bridge returns a failed call as the negative of toxcore's number,
 * or as a number of its own (-99, -98, -22, -21) when the call never reached toxcore; a number no table
 * knows reads as the call's unknown error, never as no code and never as a crash.
 */
final class AirErrorCode
{
    /** objcTox's domain for the errors of toxcore's calls (kOCTToxErrorDomain). */
    static final String TOX_DOMAIN = "me.dvor.objcTox.OCTToxErrorDomain";
    /** objcTox's domain for a profile that did not open (kOCTManagerErrorDomain). */
    static final String MANAGER_DOMAIN = "me.dvor.objcTox.OCTManagerErrorDomain";
    /** The checks the app makes itself before a profile opens (ProfileOpening.errorDomain on iOS). */
    static final String PROFILE_OPENING_DOMAIN = "org.khandaq.messenger.ProfileOpening";

    /** ProfileOpening 1: the Tox save is there and cannot be read. */
    static final int PROFILE_SAVE_UNREADABLE = 1;
    /** ProfileOpening 2: what keeps the history is there and cannot be read (its key file on iOS). */
    static final int PROFILE_HISTORY_UNREADABLE = 2;
    /** OCTManagerInitErrorCreateToxBadFormat: toxcore did not take the save as a profile. */
    static final int MANAGER_CREATE_TOX_BAD_FORMAT = 19;

    /** toxcore's GROUP_NOT_FOUND, the same number in every group call that has it. */
    static final int TOX_GROUP_NOT_FOUND = 1;
    /** toxcore's PEER_NOT_FOUND of the moderation calls (Tox_Err_Group_Mod_Set_Role, _Kick_Peer). */
    static final int TOX_PEER_NOT_FOUND = 2;
    /** toxcore's PERMISSIONS of the moderation calls: the role you have does not allow it. */
    static final int TOX_PERMISSIONS = 3;

    /**
     * A toxcore call iOS also makes and shows the failure of with its code, with objcTox's code for each
     * of toxcore's errors. iOS shows no code for a private message to a member (the line under its field
     * says what failed), so that call has none here either.
     */
    enum Call
    {
        // TOX_ERR_FRIEND_ADD: NULL is objcTox's unknown error, and the others move down by one.
        FRIEND_ADD(0, 0, 1, 2, 3, 4, 5, 6, 7),
        // Tox_Err_Group_Mod_Set_Role: objcTox keeps toxcore's numbers.
        GROUP_SET_ROLE(0, 1, 2, 3, 4, 5, 6),
        // Tox_Err_Group_Mod_Kick_Peer: FAIL_ACTION and FAIL_SEND are objcTox's FailSend, SELF its Permissions.
        GROUP_KICK_PEER(0, 1, 2, 3, 4, 4, 3),
        GROUP_SEND_MESSAGE(0, 1, 2, 3, 4, 5, 6, 7),
        // The group settings (iOS GroupInfoController shows a code under each of them).
        GROUP_TOPIC_SET(0, 1, 2, 3, 4, 5, 6),
        GROUP_FOUNDER_SET_PASSWORD(0, 1, 2, 3, 4, 5, 6),
        GROUP_FOUNDER_SET_TOPIC_LOCK(0, 1, 2, 3, 4, 5, 6),
        GROUP_FOUNDER_SET_PEER_LIMIT(0, 1, 2, 3, 4, 5),
        GROUP_FOUNDER_SET_VOICE_STATE(0, 1, 2, 3, 4, 5),
        GROUP_FOUNDER_SET_PRIVACY_STATE(0, 1, 2, 3, 4, 5);

        /** objcTox's code for toxcore's error number i; i = 0 is toxcore's OK and stands for unknown. */
        private final int[] codes;

        Call(final int... codes)
        {
            this.codes = codes;
        }

        /** How many errors toxcore has for this call, OK not counted. */
        int toxErrors()
        {
            return codes.length - 1;
        }

        /** objcTox's code for toxcore's error number; 0, the unknown error, for one it does not list. */
        int code(final long toxError)
        {
            return (toxError > 0 && toxError < codes.length) ? codes[(int) toxError] : 0;
        }
    }

    /** What the JNI bridge returns for a call it did not make or for an error it does not name. */
    static final long BRIDGE_NO_CALL = -99L;

    private AirErrorCode()
    {
    }

    /** "domain number", the form iOS shows under Details (ErrorAlert.details). */
    static String of(final String domain, final long number)
    {
        return domain + " " + number;
    }

    /** The code of toxcore's error number {@code toxError} of {@code call}. */
    static String tox(final Call call, final long toxError)
    {
        return of(TOX_DOMAIN, call.code(toxError));
    }

    /**
     * The code of a failed call from what the JNI bridge returned: the negative of toxcore's error, or a
     * number of the bridge's own, which reads as the unknown error. Only for a result the caller already
     * takes as a failure: a call that returns 0 or 1 for success is told apart by its caller.
     */
    static String toxResult(final Call call, final long jniResult)
    {
        return tox(call, jniResult < 0 ? -jniResult : 0);
    }

    /**
     * The code of a group message toxcore refused, from what the send returned (HelperGroup
     * group_send_failure_reason has the words). A message kept for later (-98) and a group not
     * connected (-99, into which the bridge also folds the errors it does not name) say no error of
     * toxcore's, so they have none; nor has a message that went.
     */
    @Nullable
    static String groupSend(final long messageId)
    {
        if (messageId >= 0 || messageId == HelperGroup.GROUP_SEND_QUEUE_WHEN_UNCONNECTED
            || messageId == BRIDGE_NO_CALL)
        {
            return null;
        }
        return toxResult(Call.GROUP_SEND_MESSAGE, messageId);
    }

    static String profileOpening(final int number)
    {
        return of(PROFILE_OPENING_DOMAIN, number);
    }

    static String manager(final int number)
    {
        return of(MANAGER_DOMAIN, number);
    }
}

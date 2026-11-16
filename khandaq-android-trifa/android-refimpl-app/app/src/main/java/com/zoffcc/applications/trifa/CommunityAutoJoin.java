package com.zoffcc.applications.trifa;

import android.util.Log;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KHANDAQ (0.2.57, SPEC-community part B): what the app does for Khandaq Community, by the rules of
 * KhandaqCommunity. The profile joins it once by itself, through the join by chat id a person uses, with the
 * profile's own name and no password, muted; and never again once the person has left it or deleted it. The join
 * counts once the group has connected: toxcore saves a group only then, so a start that finds an earlier join gone
 * (it never connected) joins again.
 * <p>
 * The marks of the profile (autoJoinDone, left) are kept with the profile's other settings, in its own encrypted
 * database (TRIFADatabaseGlobalsNew, through get_g_opts and set_g_opts), under the start of its public key
 * (KhandaqCommunity.markKey): they last across restarts and updates, and a profile imported over this one starts
 * without them.
 */
final class CommunityAutoJoin
{
    private static final String TAG = "trifa.Community";
    private static final String MARK_SET = "true";
    private static final int NO_SESSION = -1;

    /**
     * The tox session (TrifaToxService.tox_session_number) whose join runs now, or NO_SESSION. One join at a time:
     * the node can connect again while a join still waits for toxcore. A join left from an earlier session (a
     * profile import, a logout and login) stops at its next step and does not hold up the join of this one.
     */
    private static final AtomicInteger JOINING_SESSION = new AtomicInteger(NO_SESSION);

    private CommunityAutoJoin()
    {
    }

    /**
     * Our node is connected: the first time after the start, and every time it comes back. The join, when the
     * rules call for one, runs on a thread of its own: it waits for toxcore, and this is called on the tox thread.
     */
    static void onNodeConnected()
    {
        final int session = TrifaToxService.tox_session_number.get();
        final int running = JOINING_SESSION.get();
        if ((running == session) || !JOINING_SESSION.compareAndSet(running, session))
        {
            return;
        }
        final Thread t = new Thread(() -> {
            try
            {
                joinIfDue(session);
            }
            catch (Throwable e)
            {
                Log.w(TAG, "auto_join:EE:" + e.getMessage());
            }
            finally
            {
                JOINING_SESSION.compareAndSet(session, NO_SESSION);
            }
        }, "khandaq-community-join");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Whether the tox session a join began in still runs. A profile import, or a logout and login, starts tox again in
     * this process, with another profile maybe: a join of the last session must not land in it, past its marks.
     */
    private static boolean sessionGoesOn(final int session)
    {
        return TrifaToxService.is_tox_started && (TrifaToxService.tox_session_number.get() == session);
    }

    private static void joinIfDue(final int session)
    {
        // Not the person's profile (#244): a stand-in identity joins nothing and marks nothing.
        if ((TrifaToxService.orma == null) || !sessionGoesOn(session) || MainActivity.identity_load_blocked)
        {
            return;
        }
        final KhandaqCommunity.Store store = profileStore();
        if (store == null)
        {
            return;
        }
        // The group's own mark of a leave by hand (as in a version without auto-join) makes the leave final too.
        if (HelperGroup.is_group_we_left(KhandaqCommunity.CHAT_ID))
        {
            KhandaqCommunity.noteGroupLeft(store, KhandaqCommunity.CHAT_ID);
        }
        final long groupNumber = HelperGroup.tox_group_by_groupid__wrapper(KhandaqCommunity.CHAT_ID);
        // Connected now means connected once: kept, also when it connected before this version marked it.
        if (KhandaqCommunity.noteGroupKept(store, (groupNumber >= 0) && connectedNow(groupNumber)))
        {
            NetworkDiagnosticsLog.log("community", "kept: never joined by itself again");
        }
        final boolean connected = TRIFAGlobals.global_self_connection_status
                                  != ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value;
        // A row without the group in toxcore: an earlier join that never connected, which toxcore did not save.
        final boolean newGroup = !hasRow();
        final boolean joined = KhandaqCommunity.autoJoin(store, connected, groupNumber >= 0, newGroup, () -> {
            // No toast and no words on screen: the person did not ask for this join.
            final boolean here = HelperGroup.join_public_group_by_id(KhandaqCommunity.CHAT_ID, null, false, null,
                                                                     () -> sessionGoesOn(session))
                                 && inGroup();
            if (!here)
            {
                NetworkDiagnosticsLog.log("community", "auto_join: not joined yet, the next connection tries again");
            }
            return here;
        });
        if (joined)
        {
            NetworkDiagnosticsLog.log("community", newGroup ? "auto_join: joined, muted"
                                                            : "auto_join: joined again, it had not connected before");
        }
    }

    /**
     * The group connected for the first time since the start (MainActivity's group self-join): toxcore has its
     * state and keeps it in the saved data now. Khandaq Community is the profile's for good then.
     */
    static void onGroupConnected(final String groupId)
    {
        if (!KhandaqCommunity.isCommunity(groupId) || MainActivity.identity_load_blocked)
        {
            return;
        }
        try
        {
            final KhandaqCommunity.Store store = profileStore();
            if ((store != null) && KhandaqCommunity.noteGroupKept(store, true))
            {
                NetworkDiagnosticsLog.log("community", "kept: never joined by itself again");
            }
        }
        catch (Throwable e)
        {
            Log.w(TAG, "kept:EE:" + e.getMessage());
        }
    }

    /** QA only (QaGroupBroadcastReceiver, debug builds): the running profile's two marks are removed. */
    static void qaForgetMarks()
    {
        for (final String mark : new String[]{KhandaqCommunity.MARK_AUTO_JOIN_DONE, KhandaqCommunity.MARK_LEFT})
        {
            final String key = KhandaqCommunity.markKey(mark, TRIFAGlobals.global_my_toxid);
            if (key != null)
            {
                HelperGeneric.del_g_opts(key);
            }
        }
    }

    /** The profile is in the group: toxcore has it, from a join by hand or an earlier one. */
    private static boolean inGroup()
    {
        return HelperGroup.tox_group_by_groupid__wrapper(KhandaqCommunity.CHAT_ID) >= 0;
    }

    private static boolean connectedNow(final long groupNumber)
    {
        return MainActivity.tox_group_is_connected(groupNumber)
               == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value;
    }

    /** The app has a row of the group: it joined it before, by itself or by hand. */
    private static boolean hasRow()
    {
        return TrifaToxService.orma.selectFromGroupDB().group_identifierEq(
                KhandaqCommunity.CHAT_ID.toLowerCase(Locale.ROOT)).count() > 0;
    }

    /**
     * The person left a group or deleted it, however they did it: when it is Khandaq Community, the app never
     * joins it by itself again.
     */
    static void noteGroupLeft(final String groupId)
    {
        if (!KhandaqCommunity.isCommunity(groupId))
        {
            return;
        }
        try
        {
            final KhandaqCommunity.Store store = profileStore();
            if (store == null)
            {
                Log.w(TAG, "left: the profile's key is not known yet, the mark waits for the group's own");
                return;
            }
            if (KhandaqCommunity.noteGroupLeft(store, groupId))
            {
                NetworkDiagnosticsLog.log("community", "left: never joined by itself again");
            }
        }
        catch (Throwable e)
        {
            Log.w(TAG, "left:EE:" + e.getMessage());
        }
    }

    /** The marks of the profile now running, or null while its Tox ID is not known. */
    private static KhandaqCommunity.Store profileStore()
    {
        final String ownToxId = TRIFAGlobals.global_my_toxid;
        final String doneKey = KhandaqCommunity.markKey(KhandaqCommunity.MARK_AUTO_JOIN_DONE, ownToxId);
        final String leftKey = KhandaqCommunity.markKey(KhandaqCommunity.MARK_LEFT, ownToxId);
        if ((doneKey == null) || (leftKey == null))
        {
            return null;
        }
        return new KhandaqCommunity.Store()
        {
            @Override
            public boolean autoJoinDone()
            {
                return MARK_SET.equals(HelperGeneric.get_g_opts(doneKey));
            }

            @Override
            public boolean left()
            {
                return MARK_SET.equals(HelperGeneric.get_g_opts(leftKey));
            }

            @Override
            public void setAutoJoinDone()
            {
                HelperGeneric.set_g_opts(doneKey, MARK_SET);
            }

            @Override
            public void setLeft()
            {
                HelperGeneric.set_g_opts(leftKey, MARK_SET);
            }

            @Override
            public void mute(final String chatId)
            {
                // The group's own notification switch, the one its dossier turns (AirGroupDossierActivity.setMuted).
                TrifaToxService.orma.updateGroupDB().group_identifierEq(chatId.toLowerCase(Locale.ROOT)).
                        notification_silent(true).execute();
                AirRepository.requestRefresh();
            }
        };
    }
}

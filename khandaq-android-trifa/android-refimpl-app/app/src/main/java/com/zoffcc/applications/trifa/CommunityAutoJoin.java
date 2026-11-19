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

    /**
     * Khandaq Community refused us as full (waitForRoom): the tox session of the last refusal, how many came in a row
     * in it, and the wait that runs now (NO_WAIT for none). Nothing joins the Community by itself while one runs.
     */
    private static final Object ROOM_LOCK = new Object();
    private static final int NO_WAIT = 0;
    private static int roomSession = NO_SESSION;
    private static int roomRefusals = 0;
    private static int roomWait = NO_WAIT;
    private static int roomWaits = NO_WAIT;

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
    static boolean sessionGoesOn(final int session)
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
        // KHANDAQ (0.2.60): the Community keeps its sound; the mute an earlier auto-join left goes, once.
        if (KhandaqCommunity.restoreSoundOnce(store))
        {
            NetworkDiagnosticsLog.log("community", "sound: on again, once (the auto-join no longer mutes)");
        }
        final long groupNumber = HelperGroup.tox_group_by_groupid__wrapper(KhandaqCommunity.CHAT_ID);
        // Connected now means connected once: kept, also when it connected before this version marked it.
        if (KhandaqCommunity.noteGroupKept(store, (groupNumber >= 0) && connectedNow(groupNumber)))
        {
            NetworkDiagnosticsLog.log("community", "kept: never joined by itself again");
        }
        // Full when it was last asked: it is asked again when its pause is over (waitForRoom).
        if (waitsForRoom(KhandaqCommunity.CHAT_ID))
        {
            return;
        }
        final boolean connected = TRIFAGlobals.global_self_connection_status
                                  != ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value;
        // A row without the group in toxcore: an earlier join that never connected, which toxcore did not save.
        final boolean newGroup = !hasRow();
        final boolean joined = KhandaqCommunity.autoJoin(store, connected, groupNumber >= 0,
                                                         new KhandaqCommunity.Join()
        {
            @Override
            public boolean join()
            {
                // No toast and no words on screen: the person did not ask for this join. It stops at its next step
                // once its tox session ended, or once the person left the group (or deleted it) meanwhile.
                final boolean here = HelperGroup.join_public_group_by_id(KhandaqCommunity.CHAT_ID, null, false, null,
                                                                         () -> sessionGoesOn(session) && !store.left())
                                     && inGroup();
                if (!here && !store.left())
                {
                    NetworkDiagnosticsLog.log("community", "auto_join: not joined yet, the next connection tries again");
                }
                return here;
            }

            @Override
            public void leaveAgain()
            {
                // The leave the person made: toxcore leaves it, its row is marked left and inactive.
                GroupListHolder.leave_group(KhandaqCommunity.CHAT_ID);
                NetworkDiagnosticsLog.log("community", "auto_join: left while it joined, left again");
            }
        });
        if (joined)
        {
            // Its row was there: a join toxcore did not keep (it never connected), or one let go while full.
            NetworkDiagnosticsLog.log("community", newGroup ? "auto_join: joined"
                                                            : "auto_join: joined again, into its row as it was");
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
        synchronized (ROOM_LOCK)
        {
            // It let us in: a refusal that comes later is the first one again.
            roomSession = NO_SESSION;
            roomRefusals = 0;
            roomWait = NO_WAIT;
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

    /**
     * Khandaq Community refused our join as full (KhandaqCommunity.waitsForRoom): {@code groupNumber} is the instance
     * toxcore refused. Every client over its limit asked again at once (a hard reconnect and the quick retries) and at
     * every start, each try a handshake with the founder and the members. So the app lets go of the instance, which
     * stops toxcore asking; keeps the row as it is, in the list and pinned, with its switch and its history; keeps the
     * person's leave mark unset; and asks again by its chat id once the pause is over (KhandaqCommunity
     * .roomRetryDelayMs: 5 minutes, doubled after each refusal in a row, an hour at most), and at the next start.
     * On the tox thread, from the group's join fail.
     */
    static void waitForRoom(final long groupNumber)
    {
        final int session = TrifaToxService.tox_session_number.get();
        final int wait;
        final long delayMs;
        synchronized (ROOM_LOCK)
        {
            roomRefusals = (roomSession == session) ? roomRefusals + 1 : 1;
            roomSession = session;
            delayMs = KhandaqCommunity.roomRetryDelayMs(roomRefusals);
            roomWaits = (roomWaits == Integer.MAX_VALUE) ? (NO_WAIT + 1) : (roomWaits + 1);
            roomWait = roomWaits;
            wait = roomWait;
        }
        HelperGroup.let_go_of_group_instance(groupNumber);
        try
        {
            final KhandaqCommunity.Store store = profileStore();
            if (store != null)
            {
                KhandaqCommunity.noteGroupLetGo(store);
            }
        }
        catch (Throwable e)
        {
            Log.w(TAG, "full:EE:" + e.getMessage());
        }
        NetworkDiagnosticsLog.log("community", "full: let go of it, asked again in " + (delayMs / 60_000L) + " min");
        final Thread t = new Thread(() -> {
            try
            {
                Thread.sleep(delayMs);
            }
            catch (InterruptedException e)
            {
                return;
            }
            synchronized (ROOM_LOCK)
            {
                // A later refusal began a wait of its own, or the group let us in meanwhile.
                if (roomWait != wait)
                {
                    return;
                }
                roomWait = NO_WAIT;
            }
            if (sessionGoesOn(session))
            {
                onNodeConnected();
            }
        }, "khandaq-community-room");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Whether {@code groupId} is Khandaq Community and the app waits for room in it (waitForRoom): until the pause is
     * over, nothing joins it by itself, neither the auto-join nor a screen that shows it.
     */
    static boolean waitsForRoom(final String groupId)
    {
        if (!KhandaqCommunity.isCommunity(groupId))
        {
            return false;
        }
        synchronized (ROOM_LOCK)
        {
            return (roomWait != NO_WAIT) && (roomSession == TrifaToxService.tox_session_number.get());
        }
    }

    /**
     * Whether the row made now for a group starts muted (KhandaqCommunity.mutesNewRow): Khandaq Community given a new
     * row after the profile was imported or restored again. False for any other group, and while the profile's Tox
     * ID is not known.
     */
    static boolean mutesNewRow(final String groupId)
    {
        if (!KhandaqCommunity.isCommunity(groupId))
        {
            return false;
        }
        try
        {
            final KhandaqCommunity.Store store = profileStore();
            return (store != null) && KhandaqCommunity.mutesNewRow(groupId, store);
        }
        catch (Throwable e)
        {
            Log.w(TAG, "new_row:EE:" + e.getMessage());
            return false;
        }
    }

    /** The person turned the notifications of a group off or on: Khandaq Community's switch stays with the profile. */
    static void noteSoundChoice(final String groupId, final boolean muted)
    {
        if (!KhandaqCommunity.isCommunity(groupId))
        {
            return;
        }
        try
        {
            final KhandaqCommunity.Store store = profileStore();
            if ((store != null) && KhandaqCommunity.noteSoundChoice(store, groupId, muted))
            {
                NetworkDiagnosticsLog.log("community", muted ? "sound: off by the person" : "sound: on by the person");
            }
        }
        catch (Throwable e)
        {
            Log.w(TAG, "sound:EE:" + e.getMessage());
        }
    }

    /** The person deleted a group: a leave for good (noteGroupLeft); Khandaq Community's switch goes with its row. */
    static void noteGroupDeleted(final String groupId)
    {
        noteGroupLeft(groupId);
        if (!KhandaqCommunity.isCommunity(groupId))
        {
            return;
        }
        try
        {
            final KhandaqCommunity.Store store = profileStore();
            if (store != null)
            {
                KhandaqCommunity.noteRowDeleted(store, groupId);
            }
        }
        catch (Throwable e)
        {
            Log.w(TAG, "deleted:EE:" + e.getMessage());
        }
    }

    /** QA only (QaGroupBroadcastReceiver, debug builds): the running profile's marks are removed. */
    static void qaForgetMarks()
    {
        for (final String mark : new String[]{KhandaqCommunity.MARK_AUTO_JOIN_DONE, KhandaqCommunity.MARK_LEFT,
                                              KhandaqCommunity.MARK_MUTED, KhandaqCommunity.MARK_SOUND_RESTORED})
        {
            final String key = KhandaqCommunity.markKey(mark, TRIFAGlobals.global_my_toxid);
            if (key != null)
            {
                HelperGeneric.del_g_opts(key);
            }
        }
    }

    /** The profile is in the group: toxcore has it, from a join by hand or an earlier one. */
    static boolean inGroup()
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

    /**
     * Whether the person left Khandaq Community or deleted it ({@code groupId}), by the profile's own mark, whatever
     * its row says: a row deleted is gone with its own mark. False for any other group, and while the profile's
     * Tox ID is not known.
     */
    static boolean leftByThePerson(final String groupId)
    {
        if (!KhandaqCommunity.isCommunity(groupId))
        {
            return false;
        }
        try
        {
            final KhandaqCommunity.Store store = profileStore();
            return (store != null) && store.left();
        }
        catch (Throwable e)
        {
            Log.w(TAG, "left?:EE:" + e.getMessage());
            return false;
        }
    }

    /** The marks of the profile now running, or null while its Tox ID is not known. */
    private static KhandaqCommunity.Store profileStore()
    {
        final String ownToxId = TRIFAGlobals.global_my_toxid;
        final String doneKey = KhandaqCommunity.markKey(KhandaqCommunity.MARK_AUTO_JOIN_DONE, ownToxId);
        final String leftKey = KhandaqCommunity.markKey(KhandaqCommunity.MARK_LEFT, ownToxId);
        final String mutedKey = KhandaqCommunity.markKey(KhandaqCommunity.MARK_MUTED, ownToxId);
        final String soundKey = KhandaqCommunity.markKey(KhandaqCommunity.MARK_SOUND_RESTORED, ownToxId);
        if ((doneKey == null) || (leftKey == null) || (mutedKey == null) || (soundKey == null))
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
            public void clearAutoJoinDone()
            {
                HelperGeneric.del_g_opts(doneKey);
            }

            @Override
            public void setLeft()
            {
                HelperGeneric.set_g_opts(leftKey, MARK_SET);
            }

            @Override
            public boolean keptMuted()
            {
                return MARK_SET.equals(HelperGeneric.get_g_opts(mutedKey));
            }

            @Override
            public void keepMuted(final boolean muted)
            {
                if (muted)
                {
                    HelperGeneric.set_g_opts(mutedKey, MARK_SET);
                }
                else
                {
                    HelperGeneric.del_g_opts(mutedKey);
                }
            }

            @Override
            public boolean soundRestored()
            {
                return MARK_SET.equals(HelperGeneric.get_g_opts(soundKey));
            }

            @Override
            public void setSoundRestored()
            {
                HelperGeneric.set_g_opts(soundKey, MARK_SET);
            }

            @Override
            public void unmute(final String chatId)
            {
                // The group's own notification switch, the one its dossier turns (AirGroupDossierActivity.setMuted).
                TrifaToxService.orma.updateGroupDB().group_identifierEq(chatId.toLowerCase(Locale.ROOT)).
                        notification_silent(false).execute();
                // KHANDAQ (0.2.64): and the copy of it the push preview handler reads.
                PushPreviewPeers.refreshMutedChats();
                AirRepository.requestRefresh();
            }
        };
    }
}

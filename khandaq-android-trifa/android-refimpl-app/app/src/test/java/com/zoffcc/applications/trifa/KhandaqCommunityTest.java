package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57, SPEC-community part B, item 8): Khandaq Community is joined once by itself, and never again once
 * the person left it. KHANDAQ (0.2.60): it keeps its sound; the mute an earlier auto-join left is lifted once. The
 * rules (KhandaqCommunity) are held as the phone runs them, and the app's code is checked to ask them where it
 * connects, joins, leaves and takes files.
 */
public class KhandaqCommunityTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    /** A Tox ID: the public key (64 hexadecimal characters), then the nospam and the checksum. */
    private static final String TOX_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" + "1122334455AA";
    private static final String OTHER_TOX_ID =
            "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210" + "66778899BBCC";
    private static final String OTHER_GROUP = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90";
    private static final String LEGACY_TRIFA_GROUP = "154b3973bd0e66304fd6179a8a54759073649e09e6e368f0334fc6ed666ab762";

    /**
     * The marks as the profile's settings keep them (get_g_opts and set_g_opts): the map outlives one start, so a
     * store made again over it is the app after a restart.
     */
    private static final class ProfileStore implements KhandaqCommunity.Store
    {
        final Map<String, String> settings;
        final String toxId;
        /** KHANDAQ (0.2.60): the groups whose switch the store turned on (restoreSoundOnce). */
        final List<String> unmuted = new ArrayList<>();

        ProfileStore(final Map<String, String> settings, final String toxId)
        {
            this.settings = settings;
            this.toxId = toxId;
        }

        private String key(final String mark)
        {
            return KhandaqCommunity.markKey(mark, toxId);
        }

        @Override
        public boolean autoJoinDone()
        {
            return "true".equals(settings.get(key(KhandaqCommunity.MARK_AUTO_JOIN_DONE)));
        }

        @Override
        public boolean left()
        {
            return "true".equals(settings.get(key(KhandaqCommunity.MARK_LEFT)));
        }

        @Override
        public void setAutoJoinDone()
        {
            settings.put(key(KhandaqCommunity.MARK_AUTO_JOIN_DONE), "true");
        }

        @Override
        public void clearAutoJoinDone()
        {
            settings.remove(key(KhandaqCommunity.MARK_AUTO_JOIN_DONE));
        }

        @Override
        public void setLeft()
        {
            settings.put(key(KhandaqCommunity.MARK_LEFT), "true");
        }

        @Override
        public boolean keptMuted()
        {
            return "true".equals(settings.get(key(KhandaqCommunity.MARK_MUTED)));
        }

        @Override
        public void keepMuted(final boolean on)
        {
            if (on)
            {
                settings.put(key(KhandaqCommunity.MARK_MUTED), "true");
            }
            else
            {
                settings.remove(key(KhandaqCommunity.MARK_MUTED));
            }
        }

        @Override
        public boolean soundRestored()
        {
            return "true".equals(settings.get(key(KhandaqCommunity.MARK_SOUND_RESTORED)));
        }

        @Override
        public void setSoundRestored()
        {
            settings.put(key(KhandaqCommunity.MARK_SOUND_RESTORED), "true");
        }

        @Override
        public void unmute(final String chatId)
        {
            unmuted.add(chatId);
        }
    }

    /** The app's join by chat id, counted; it took when {@code takes} says so. */
    private static final class Join implements KhandaqCommunity.Join
    {
        boolean takes;
        int calls;
        int leftAgain;
        /** What the person does while the join runs, if anything. */
        Runnable meanwhile;

        Join(final boolean takes)
        {
            this.takes = takes;
        }

        @Override
        public boolean join()
        {
            calls++;
            if (meanwhile != null)
            {
                meanwhile.run();
            }
            return takes;
        }

        @Override
        public void leaveAgain()
        {
            leftAgain++;
        }
    }

    // ---- the decision (SPEC Tests: join or not) ----

    @Test
    public void it_joins_only_when_connected_never_joined_by_itself_never_left_and_not_in_it()
    {
        for (int bits = 0; bits < 16; bits++)
        {
            final boolean connected = (bits & 1) != 0;
            final boolean autoJoinDone = (bits & 2) != 0;
            final boolean left = (bits & 4) != 0;
            final boolean alreadyInGroup = (bits & 8) != 0;
            assertEquals("connected=" + connected + " done=" + autoJoinDone + " left=" + left + " in=" + alreadyInGroup,
                         connected && !autoJoinDone && !left && !alreadyInGroup,
                         KhandaqCommunity.shouldAutoJoin(connected, autoJoinDone, left, alreadyInGroup));
        }
    }

    @Test
    public void offline_or_already_in_it_nothing_is_tried()
    {
        final ProfileStore store = new ProfileStore(new HashMap<>(), TOX_ID);
        final Join join = new Join(true);
        assertFalse(KhandaqCommunity.autoJoin(store, false, false, join));
        assertFalse(KhandaqCommunity.autoJoin(store, true, true, join));
        assertEquals(0, join.calls);
        assertFalse(store.autoJoinDone());
        assertFalse(store.keptMuted());
    }

    // ---- the auto-join leaves its sound on (KHANDAQ 0.2.60; it muted from 0.2.57) ----

    @Test
    public void a_join_that_made_the_group_keeps_its_sound_and_the_profile_is_marked_once_the_group_connected()
    {
        final ProfileStore store = new ProfileStore(new HashMap<>(), TOX_ID);
        final Join join = new Join(true);
        assertTrue(KhandaqCommunity.autoJoin(store, true, false, join));
        assertEquals(1, join.calls);
        assertFalse("its sound stays on", store.keptMuted());
        // Toxcore saves the group only once it has connected: until then the join does not count.
        assertFalse(store.autoJoinDone());
        assertFalse(KhandaqCommunity.noteGroupKept(store, false));
        assertFalse(store.autoJoinDone());

        assertTrue(KhandaqCommunity.noteGroupKept(store, true));
        assertTrue(store.autoJoinDone());
        assertFalse(KhandaqCommunity.noteGroupKept(store, true));
        assertFalse(store.left());
        assertFalse(store.keptMuted());
    }

    @Test
    public void a_group_joined_by_hand_keeps_the_defaults_of_every_group()
    {
        // In it already (joined by hand by ID or by an invitation): no join, no mute, nothing marked.
        final ProfileStore store = new ProfileStore(new HashMap<>(), TOX_ID);
        final Join join = new Join(true);
        assertFalse(KhandaqCommunity.autoJoin(store, true, true, join));
        assertEquals(0, join.calls);
        assertFalse(store.keptMuted());
        assertFalse(store.autoJoinDone());

        // Joined by hand and lost before it connected (toxcore did not save it): joined again, its switch as it was.
        assertTrue(KhandaqCommunity.autoJoin(store, true, false, join));
        assertEquals(1, join.calls);
        assertFalse(store.keptMuted());

        // Once it connected it is the profile's for good, as one the app joined.
        assertTrue(KhandaqCommunity.noteGroupKept(store, true));
        assertFalse(KhandaqCommunity.autoJoin(store, true, false, join));
        assertEquals(1, join.calls);
    }

    @Test
    public void a_join_that_failed_marks_nothing_and_the_next_connection_tries_again()
    {
        final ProfileStore store = new ProfileStore(new HashMap<>(), TOX_ID);
        final Join join = new Join(false);
        assertFalse(KhandaqCommunity.autoJoin(store, true, false, join));
        assertFalse(store.keptMuted());
        assertFalse(store.autoJoinDone());

        join.takes = true;
        assertTrue(KhandaqCommunity.autoJoin(store, true, false, join));
        assertEquals(2, join.calls);
        assertFalse(store.keptMuted());
    }

    @Test
    public void a_join_that_never_connected_is_made_again_at_the_next_start_and_keeps_its_switch()
    {
        // Live QA 2026-11-16: toxcore saves a group only once it has connected, so a restart before that loses it.
        final Map<String, String> settings = new HashMap<>();
        assertTrue(KhandaqCommunity.autoJoin(new ProfileStore(settings, TOX_ID), true, false, new Join(true)));

        // Next start: the row is there, toxcore has no group. It is joined again; the switch stays as it was (on, as
        // the first join left it, or as the person turned it since).
        final ProfileStore restarted = new ProfileStore(settings, TOX_ID);
        final Join join = new Join(true);
        assertTrue(KhandaqCommunity.autoJoin(restarted, true, false, join));
        assertEquals(1, join.calls);
        assertFalse(restarted.keptMuted());
        assertFalse(restarted.autoJoinDone());
    }

    @Test
    public void once_it_connected_it_is_not_joined_by_itself_again_after_a_restart()
    {
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore store = new ProfileStore(settings, TOX_ID);
        assertTrue(KhandaqCommunity.autoJoin(store, true, false, new Join(true)));
        assertTrue(KhandaqCommunity.noteGroupKept(store, true));

        // Next start: the group is gone (kicked); it is not joined by itself again.
        final ProfileStore restarted = new ProfileStore(settings, TOX_ID);
        final Join join = new Join(true);
        assertFalse(KhandaqCommunity.autoJoin(restarted, true, false, join));
        assertEquals(0, join.calls);
        assertFalse(restarted.keptMuted());
    }

    // ---- left: never again, across a restart (SPEC Tests) ----

    @Test
    public void leaving_is_final_across_a_restart()
    {
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore store = new ProfileStore(settings, TOX_ID);
        assertTrue(KhandaqCommunity.autoJoin(store, true, false, new Join(true)));
        // The person leaves it, by the id in the case the database keeps it in, before it ever connected.
        assertTrue(KhandaqCommunity.noteGroupLeft(store, KhandaqCommunity.CHAT_ID.toLowerCase(Locale.ROOT)));
        assertFalse(KhandaqCommunity.noteGroupLeft(store, KhandaqCommunity.CHAT_ID));

        final ProfileStore restarted = new ProfileStore(settings, TOX_ID);
        assertTrue(restarted.left());
        final Join join = new Join(true);
        assertFalse(KhandaqCommunity.autoJoin(restarted, true, false, join));
        assertFalse(KhandaqCommunity.autoJoin(restarted, true, false, join));
        assertEquals(0, join.calls);
    }

    @Test
    public void a_leave_before_any_auto_join_is_final_too()
    {
        // Joined by hand, left by hand: the app never puts the person in by itself afterwards.
        final Map<String, String> settings = new HashMap<>();
        assertTrue(KhandaqCommunity.noteGroupLeft(new ProfileStore(settings, TOX_ID), KhandaqCommunity.CHAT_ID));
        final ProfileStore restarted = new ProfileStore(settings, TOX_ID);
        final Join join = new Join(true);
        assertFalse(KhandaqCommunity.autoJoin(restarted, true, false, join));
        assertEquals(0, join.calls);
        assertFalse(restarted.autoJoinDone());
    }

    @Test
    public void leaving_another_group_changes_nothing()
    {
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore store = new ProfileStore(settings, TOX_ID);
        assertFalse(KhandaqCommunity.noteGroupLeft(store, OTHER_GROUP));
        assertFalse(KhandaqCommunity.noteGroupLeft(store, LEGACY_TRIFA_GROUP));
        assertFalse(KhandaqCommunity.noteGroupLeft(store, null));
        assertTrue(settings.isEmpty());
    }

    // ---- the marks are the profile's ----

    @Test
    public void the_marks_are_kept_under_the_start_of_the_profiles_key_never_the_whole_key()
    {
        assertEquals("community.left:01234567", KhandaqCommunity.markKey(KhandaqCommunity.MARK_LEFT, TOX_ID));
        assertEquals("community.autoJoinDone:01234567",
                     KhandaqCommunity.markKey(KhandaqCommunity.MARK_AUTO_JOIN_DONE, TOX_ID.toUpperCase(Locale.ROOT)));
        assertFalse(KhandaqCommunity.markKey(KhandaqCommunity.MARK_LEFT, TOX_ID).contains("89ABCDEF"));
        // No Tox ID yet, or none that holds a public key: no key, and so no mark is read or written.
        assertNull(KhandaqCommunity.markKey(KhandaqCommunity.MARK_LEFT, null));
        assertNull(KhandaqCommunity.markKey(KhandaqCommunity.MARK_LEFT, ""));
        assertNull(KhandaqCommunity.markKey(KhandaqCommunity.MARK_LEFT, TOX_ID.substring(0, 63)));
        assertNull(KhandaqCommunity.markKey(KhandaqCommunity.MARK_LEFT, "G" + TOX_ID.substring(1)));
    }

    @Test
    public void another_profile_imported_over_this_one_starts_without_its_marks()
    {
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore first = new ProfileStore(settings, TOX_ID);
        assertTrue(KhandaqCommunity.autoJoin(first, true, false, new Join(true)));
        assertTrue(KhandaqCommunity.noteGroupKept(first, true));
        assertTrue(KhandaqCommunity.noteGroupLeft(first, KhandaqCommunity.CHAT_ID));

        final ProfileStore imported = new ProfileStore(settings, OTHER_TOX_ID);
        assertFalse(imported.autoJoinDone());
        final Join join = new Join(true);
        assertTrue(KhandaqCommunity.autoJoin(imported, true, false, join));
        assertEquals(1, join.calls);

        // The first profile, imported back, still has its leave.
        assertFalse(KhandaqCommunity.autoJoin(new ProfileStore(settings, TOX_ID), true, false, new Join(true)));
    }

    // ---- the group ----

    @Test
    public void the_community_is_known_by_its_chat_id_in_either_case()
    {
        assertEquals(64, KhandaqCommunity.CHAT_ID.length());
        assertEquals(KhandaqCommunity.CHAT_ID.toUpperCase(Locale.ROOT), KhandaqCommunity.CHAT_ID);
        assertTrue(KhandaqCommunity.isCommunity(KhandaqCommunity.CHAT_ID));
        assertTrue(KhandaqCommunity.isCommunity(KhandaqCommunity.CHAT_ID.toLowerCase(Locale.ROOT)));
        assertTrue(KhandaqCommunity.isCommunity(" " + KhandaqCommunity.CHAT_ID + "\n"));
        assertFalse(KhandaqCommunity.isCommunity(OTHER_GROUP));
        assertFalse(KhandaqCommunity.isCommunity(null));
        assertFalse(KhandaqCommunity.isCommunity(""));
        // Never the old TRIfA group, which HelperGroup.is_removed_legacy_public_community_group keeps away.
        assertFalse(KhandaqCommunity.isCommunity(LEGACY_TRIFA_GROUP));
        assertTrue(read(JAVA + "HelperGroup.java").contains("\"" + LEGACY_TRIFA_GROUP + "\""));
    }

    @Test
    public void muted_the_community_notifies_nothing_not_even_a_mention()
    {
        assertFalse(KhandaqCommunity.mentionBreaksMute(KhandaqCommunity.CHAT_ID, true));
        assertFalse(KhandaqCommunity.mentionBreaksMute(KhandaqCommunity.CHAT_ID.toLowerCase(Locale.ROOT), true));
        // Turned on by the person, it is a group like any other; and a mention breaks through every other mute.
        assertTrue(KhandaqCommunity.mentionBreaksMute(KhandaqCommunity.CHAT_ID, false));
        assertTrue(KhandaqCommunity.mentionBreaksMute(OTHER_GROUP, true));
        assertTrue(KhandaqCommunity.mentionBreaksMute(OTHER_GROUP, false));

        // The mention is the only thing that turns a notification back on after the group's mute, and it asks.
        final String group = read(JAVA + "HelperGroup.java");
        assertEquals(1, count(bare(group), "do_notification=true;}"));
        assertTrue(bare(group).contains("if(GroupMentionHelper.isSelfMentioned(message_,group_id)"
                                        + "&&KhandaqCommunity.mentionBreaksMute(group_id,group_temp.notification_silent))"
                                        + "{do_notification=true;}"));
        // Sounds and the launcher's badge come only with a notification: the app sets no badge count of its own.
        final String notifications = read(JAVA + "HelperMsgNotification.java");
        assertFalse(notifications.contains(".setNumber("));
        assertFalse(notifications.contains("ShortcutBadger"));
    }

    @Test
    public void files_of_others_from_history_sync_never_come_by_themselves_whatever_their_date()
    {
        // History sync carries a small file whole, with the date its sender claims (up to 200 minutes back). In
        // the Community the gate says no for any date, so the synced file is refused before anything is written:
        // it never shows in the chat. The gate itself takes no date.
        final String group = read(JAVA + "HelperGroup.java");
        final String synced = bare(method(group, "private static void group_file_add_from_sync("));
        assertTrue(synced.contains("{booleando_notification=true;booleando_badge_update=true;"));
        final int gate = synced.indexOf("if(!ngc_incoming_download_allowed(group_identifier,message_id_hash)){");
        assertTrue(gate > 0);
        // (bare() takes the spaces out of the log text too.)
        assertTrue(synced.substring(gate).startsWith("if(!ngc_incoming_download_allowed(group_identifier,message_id_hash)){"
                                                     + "HelperGeneric.logI(TAG,\"group_file_add_from_sync:attachment"
                                                     + "downloadnotallowed->droppingsyncedfile\");return;}"));
        final String beforeGate = synced.substring(0, gate);
        assertFalse("nothing is stored before the gate", beforeGate.contains("insert_into_group_message_db"));
        assertFalse("nothing is written before the gate", beforeGate.contains("save_"));
        assertTrue(group.contains("static boolean ngc_incoming_download_allowed(final String groupId, final String msgIdHash)"));

        // The live paths ask the same gate: a small file keeps its row and waits for a tap, a chunked one too.
        final String small = bare(method(group, "static void handle_incoming_group_file("));
        assertTrue(small.contains("if(ngc_incoming_download_allowed(group_id,m.msg_id_hash)){save_group_incoming_file("));
        final String chunked = read(JAVA + "NgcGroupFileTransfer.java");
        assertTrue(count(chunked, "HelperGroup.ngc_incoming_download_allowed(groupId, msgIdHex)") >= 2);
    }

    @Test
    public void files_of_others_in_the_community_wait_for_a_tap_whatever_the_setting_says()
    {
        assertFalse(KhandaqCommunity.fileComesByItself(KhandaqCommunity.CHAT_ID, true));
        assertFalse(KhandaqCommunity.fileComesByItself(KhandaqCommunity.CHAT_ID.toLowerCase(Locale.ROOT), true));
        assertFalse(KhandaqCommunity.fileComesByItself(KhandaqCommunity.CHAT_ID, false));
        // Every other group does as the setting says.
        assertTrue(KhandaqCommunity.fileComesByItself(OTHER_GROUP, true));
        assertFalse(KhandaqCommunity.fileComesByItself(OTHER_GROUP, false));
    }

    // ---- the app asks the rules ----

    @Test
    public void our_node_connecting_starts_the_join_after_a_start_and_every_reconnect()
    {
        final String callback = method(read(JAVA + "MainActivity.java"),
                                       "static void android_tox_callback_self_connection_status_cb_method(");
        final int wentOnline = callback.indexOf("DeliveryRestoreCoordinator.onConnectionRestored();");
        final int join = callback.indexOf("CommunityAutoJoin.onNodeConnected();");
        assertTrue(wentOnline > 0);
        assertTrue("the join starts where the node comes online", join > wentOnline);
        assertTrue(join < callback.indexOf('}', wentOnline));
        assertTrue(bare(callback.substring(0, wentOnline)).endsWith(
                "if((connection_status_prev==TOX_CONNECTION_NONE.value)&&(a_TOX_CONNECTION!=TOX_CONNECTION_NONE.value)){"));
    }

    @Test
    public void the_join_counts_once_the_group_connected_and_a_join_toxcore_did_not_keep_is_made_again()
    {
        // The first sync of a group (toxcore's self join) is where it goes into the saved state.
        final String selfJoin = method(read(JAVA + "MainActivity.java"),
                                       "static void android_tox_callback_group_self_join_cb_method(long group_number)");
        final int saved = selfJoin.indexOf("update_savedata_file_wrapper();");
        final int kept = selfJoin.indexOf("CommunityAutoJoin.onGroupConnected(group_identifier);");
        assertTrue(saved > 0);
        assertTrue("the mark follows the save", kept > saved);

        final String auto = read(JAVA + "CommunityAutoJoin.java");
        final String joinIfDue = bare(method(auto, JOIN_IF_DUE));
        assertTrue(joinIfDue.contains(
                "KhandaqCommunity.noteGroupKept(store,(groupNumber>=0)&&connectedNow(groupNumber))"));
        assertTrue(joinIfDue.contains("finalbooleannewGroup=!hasRow();"));
        assertTrue(joinIfDue.contains(
                "KhandaqCommunity.autoJoin(store,connected,groupNumber>=0,newKhandaqCommunity.Join()"));
        assertTrue(bare(method(auto, "private static boolean connectedNow(final long groupNumber)")).contains(
                "TOX_GROUP_CONNECTION_STATUS_CONNECTED.value"));
    }

    @Test
    public void the_join_is_the_join_by_chat_id_with_the_profile_name_and_no_password()
    {
        final String auto = read(JAVA + "CommunityAutoJoin.java");
        assertTrue(bare(auto).contains("HelperGroup.join_public_group_by_id(KhandaqCommunity.CHAT_ID,null,false,null,"
                                       + "()->sessionGoesOn(session)&&!store.left())"));
        // A stand-in identity (#244) joins nothing.
        assertTrue(bare(method(auto, JOIN_IF_DUE)).contains("MainActivity.identity_load_blocked"));
        // KHANDAQ (0.2.60): the lift of an earlier mute turns the group's own switch, the one its dossier turns; the
        // app no longer mutes it by itself.
        assertTrue(bare(auto).contains("notification_silent(false).execute();"));
        assertFalse(bare(auto).contains("notification_silent(true)"));
        final String join = method(read(JAVA + "HelperGroup.java"), JOIN_BY_ID);
        // KHANDAQ (tester 21.11, 8257): the profile name, unless the group's settings gave you a name of its own.
        assertTrue(bare(join).contains("get_group_peer_join_name(normalized_group_id),password);"));
    }

    @Test
    public void a_join_of_an_earlier_tox_session_stops_before_it_reaches_the_next_one()
    {
        // A profile import, or a logout and login, start tox again in the same process: each start is a new session.
        final String start = read(JAVA + "TrifaToxService.java");
        final int ledger = start.indexOf("NgcHistoryServeLedger.reset();");
        final int numbered = start.indexOf("tox_session_number.incrementAndGet();", ledger);
        assertTrue(ledger > 0);
        assertTrue("numbered before it counts as started",
                   numbered > ledger && numbered < start.indexOf("is_tox_started = true;", ledger));

        // The auto-join keeps the session it began in, and its join asks for it at every step.
        final String auto = read(JAVA + "CommunityAutoJoin.java");
        final String connected = bare(method(auto, "static void onNodeConnected()"));
        assertTrue(connected.contains("finalintsession=TrifaToxService.tox_session_number.get();"));
        assertTrue(connected.contains("joinIfDue(session);"));
        assertTrue("a join of the last session does not hold up this one",
                   connected.contains("JOINING_SESSION.compareAndSet(session,NO_SESSION);"));
        assertTrue(bare(method(auto, "static boolean sessionGoesOn(final int session)")).contains(
                "returnTrifaToxService.is_tox_started&&(TrifaToxService.tox_session_number.get()==session);"));
        assertTrue(bare(method(auto, JOIN_IF_DUE)).contains("!sessionGoesOn(session)"));

        // The join by chat id asks before each step that brings the group into toxcore or gives it a row.
        final String helper = read(JAVA + "HelperGroup.java");
        final String join = bare(method(helper, JOIN_BY_ID));
        assertEquals("no row is made without asking", 0, count(join, "finish_public_group_join("));
        assertEquals(4, count(join, "finish_join_if_wanted("));
        final int ready = join.indexOf("if(!wait_for_tox_ready(12000L))");
        assertTrue(ready > 0 && join.indexOf("if(!join_still_wanted(still_wanted))", ready) > ready);
        final int attempt = join.indexOf("join_chat_id_buffer.rewind();new_group_num=MainActivity.tox_group_join(");
        assertTrue("every attempt asks first", attempt > 0 && join.substring(0, attempt).endsWith(
                "if(!join_still_wanted(still_wanted)){HelperGeneric.logI(TAG,\"join_group:nolongerwanted,stoppedbeforeattempt=\"+attempt);returnfalse;}"));
        final String finish = bare(method(helper, "private static boolean finish_join_if_wanted("));
        assertTrue(finish.contains("if(join_still_wanted(still_wanted)){returnfinish_public_group_join(group_num,"
                                   + "group_identifier,show_toast);}if(made_by_this_join){tox_group_leave(group_num,\"\");}"));
        assertTrue(join.contains("finish_join_if_wanted(new_group_num,normalized_group_id,show_toast,still_wanted,true);"));
        // A join the person asks for asks nothing.
        assertTrue(bare(method(helper, "static boolean join_public_group_by_id(final String group_id, @Nullable final"
                                       + " String password, final boolean show_toast,\n"))
                           .contains("returnjoin_public_group_by_id(group_id,password,show_toast,failures,null);"));
    }

    @Test
    public void the_retry_of_a_refused_join_stops_once_the_group_is_deleted_or_the_community_left()
    {
        // A deleted group has no row, and so no "we left" mark either; the Community's leave is the profile's mark.
        final String helper = read(JAVA + "HelperGroup.java");
        assertTrue(bare(method(helper, "private static boolean join_fail_retry_wanted(@NonNull final String group_identifier)"))
                           .contains("returngroup_db_row_exists(group_identifier)&&!is_group_we_left(group_identifier)"
                                     + "&&!CommunityAutoJoin.leftByThePerson(group_identifier);"));
        assertTrue(bare(method(read(JAVA + "CommunityAutoJoin.java"), "static boolean leftByThePerson(final String groupId)"))
                           .contains("return(store!=null)&&store.left();"));

        final String retry = bare(method(helper,
                                         "private static void schedule_group_join_fail_retry(@NonNull final String group_identifier)"));
        assertTrue("asked before the retry starts",
                   retry.indexOf("if(!join_fail_retry_wanted(group_identifier)){return;}") < retry.indexOf("newThread("));
        final int asked = retry.indexOf("if(!is_tox_started||!join_fail_retry_wanted(group_identifier)){return;}");
        assertTrue("asked after every pause", asked > retry.indexOf("Thread.sleep(2500L*attempt);"));
        assertTrue("before the way of a group that connected",
                   asked < retry.indexOf("finish_public_group_join(existing,group_identifier,false);"));
        final int join = retry.indexOf(
                "join_public_group_by_id(group_identifier,null,false,null,()->join_fail_retry_wanted(group_identifier));");
        assertTrue("before the join, which asks at each of its steps too", join > asked);
        assertFalse(retry.contains("join_public_group_by_id(group_identifier,false);"));
    }

    // ---- a new row of it after an import or a restore (review, 2026-11-16) ----

    @Test
    public void a_row_made_again_after_an_import_keeps_the_switch_the_person_left_it_with()
    {
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore store = new ProfileStore(settings, TOX_ID);
        assertFalse(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, store));
        // KHANDAQ (0.2.60): the auto-join leaves its sound on, and so does the row made again after an import.
        assertTrue(KhandaqCommunity.autoJoin(store, true, false, new Join(true)));
        assertTrue(KhandaqCommunity.noteGroupKept(store, true));
        assertFalse(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, new ProfileStore(settings, TOX_ID)));
        // The person mutes it, and the profile keeps that they did.
        assertTrue(KhandaqCommunity.noteSoundChoice(store, COMMUNITY_ROW_ID, true));
        final ProfileStore imported = new ProfileStore(settings, TOX_ID);
        assertTrue("its row made again after an import",
                   KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, imported));
        assertTrue(KhandaqCommunity.mutesNewRow(COMMUNITY_ROW_ID, imported));
        assertFalse(KhandaqCommunity.mutesNewRow(OTHER_GROUP, imported));
        assertFalse("kept with this profile only",
                    KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, new ProfileStore(settings, OTHER_TOX_ID)));

        // The person turns its sound on: the row made after the next import has it on.
        assertTrue(KhandaqCommunity.noteSoundChoice(imported, COMMUNITY_ROW_ID, false));
        assertFalse(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, new ProfileStore(settings, TOX_ID)));
        assertFalse("nothing changes, nothing is written",
                    KhandaqCommunity.noteSoundChoice(imported, KhandaqCommunity.CHAT_ID, false));
        // And off again: muted after the next import.
        assertTrue(KhandaqCommunity.noteSoundChoice(imported, KhandaqCommunity.CHAT_ID, true));
        assertTrue(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, new ProfileStore(settings, TOX_ID)));
        // The switch of another group is not the Community's.
        assertFalse(KhandaqCommunity.noteSoundChoice(imported, OTHER_GROUP, false));
        assertTrue(imported.keptMuted());
    }

    @Test
    public void a_row_joined_by_hand_left_or_deleted_comes_back_as_it_was_not_muted_by_a_guess()
    {
        // Joined by hand (by its chat id, before the auto-join ran), never muted: it connects, so the profile has it
        // for good, and its row made again after an import has its sound on, as it had.
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore store = new ProfileStore(settings, TOX_ID);
        assertTrue(KhandaqCommunity.noteGroupKept(store, true));
        assertFalse(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, new ProfileStore(settings, TOX_ID)));

        // Muted, then left: a leave keeps the row, muted, and a join by hand takes that row again, so its switch
        // stays kept: the row made after an import is muted as that row was.
        final Map<String, String> other = new HashMap<>();
        final ProfileStore auto = new ProfileStore(other, TOX_ID);
        assertTrue(KhandaqCommunity.autoJoin(auto, true, false, new Join(true)));
        assertTrue(KhandaqCommunity.noteSoundChoice(auto, KhandaqCommunity.CHAT_ID, true));
        assertTrue(KhandaqCommunity.noteGroupLeft(auto, KhandaqCommunity.CHAT_ID));
        assertTrue(auto.keptMuted());
        assertTrue(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, new ProfileStore(other, TOX_ID)));

        // Deleted: its row is gone, and its switch with it; a join by hand makes a row as for any group.
        assertTrue(KhandaqCommunity.noteRowDeleted(auto, COMMUNITY_ROW_ID));
        assertFalse(auto.keptMuted());
        assertFalse(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, auto));
        assertFalse("nothing to forget", KhandaqCommunity.noteRowDeleted(auto, KhandaqCommunity.CHAT_ID));
        // Joined again by hand and muted by the person: that comes back after an import, the leave stays.
        assertTrue(KhandaqCommunity.noteSoundChoice(auto, KhandaqCommunity.CHAT_ID, true));
        final ProfileStore imported = new ProfileStore(other, TOX_ID);
        assertTrue(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, imported));
        assertTrue(imported.left());

        // The row of another group is not the Community's.
        assertFalse(KhandaqCommunity.noteRowDeleted(imported, OTHER_GROUP));
        assertTrue(imported.keptMuted());
    }

    // ---- the sound an earlier auto-join took (KHANDAQ 0.2.60, the owner's rule 2026-11-18) ----

    @Test
    public void the_mute_of_an_earlier_auto_join_is_lifted_once()
    {
        // A profile from 0.2.57-0.2.59: the auto-join muted the row it made, and the profile kept that it did.
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore store = new ProfileStore(settings, TOX_ID);
        store.setAutoJoinDone();
        store.keepMuted(true);

        assertTrue(KhandaqCommunity.restoreSoundOnce(store));
        assertEquals(Collections.singletonList(KhandaqCommunity.CHAT_ID), store.unmuted);
        assertFalse(store.keptMuted());
        assertTrue(store.soundRestored());
        assertFalse(KhandaqCommunity.mutesNewRow(KhandaqCommunity.CHAT_ID, new ProfileStore(settings, TOX_ID)));

        // Once: the person mutes it afterwards, and that stays, after a restart too.
        assertTrue(KhandaqCommunity.noteSoundChoice(store, KhandaqCommunity.CHAT_ID, true));
        final ProfileStore restarted = new ProfileStore(settings, TOX_ID);
        assertFalse(KhandaqCommunity.restoreSoundOnce(restarted));
        assertTrue(restarted.unmuted.isEmpty());
        assertTrue(restarted.keptMuted());

        // The mark is the profile's: another profile imported over this one has its own.
        assertFalse(new ProfileStore(settings, OTHER_TOX_ID).soundRestored());
    }

    @Test
    public void a_community_with_its_sound_on_or_left_is_left_as_it_is_and_marked_done()
    {
        // Its sound on (joined by hand, or turned on by the person): nothing to lift, and a later mute stays.
        final ProfileStore store = new ProfileStore(new HashMap<>(), TOX_ID);
        assertFalse(KhandaqCommunity.restoreSoundOnce(store));
        assertTrue(store.unmuted.isEmpty());
        assertTrue(store.soundRestored());
        assertTrue(KhandaqCommunity.noteSoundChoice(store, KhandaqCommunity.CHAT_ID, true));
        assertFalse(KhandaqCommunity.restoreSoundOnce(store));
        assertTrue(store.unmuted.isEmpty());
        assertTrue(store.keptMuted());

        // Left: its row and its switch stay as the person left them.
        final ProfileStore leaver = new ProfileStore(new HashMap<>(), TOX_ID);
        leaver.keepMuted(true);
        assertTrue(KhandaqCommunity.noteGroupLeft(leaver, KhandaqCommunity.CHAT_ID));
        assertFalse(KhandaqCommunity.restoreSoundOnce(leaver));
        assertTrue(leaver.unmuted.isEmpty());
        assertTrue(leaver.keptMuted());
        assertTrue(leaver.soundRestored());
    }

    @Test
    public void the_lift_runs_where_the_auto_join_runs_before_it_joins()
    {
        final String auto = read(JAVA + "CommunityAutoJoin.java");
        final String joinIfDue = bare(method(auto, JOIN_IF_DUE));
        final int store = joinIfDue.indexOf("finalKhandaqCommunity.Storestore=profileStore();");
        final int left = joinIfDue.indexOf("KhandaqCommunity.noteGroupLeft(store,KhandaqCommunity.CHAT_ID);");
        final int lift = joinIfDue.indexOf("if(KhandaqCommunity.restoreSoundOnce(store)){");
        assertTrue(store > 0 && left > store);
        assertTrue("after the leave is read, so a Community the person left is not touched", lift > left);
        assertTrue("before a full Community returns early",
                   lift < joinIfDue.indexOf("if(waitsForRoom(KhandaqCommunity.CHAT_ID)){return;}"));
        assertTrue(lift < joinIfDue.indexOf("KhandaqCommunity.autoJoin("));
        // The mark is kept under the profile, as the others are.
        assertTrue(bare(auto).contains("KhandaqCommunity.markKey(KhandaqCommunity.MARK_SOUND_RESTORED,ownToxId);"));
    }

    @Test
    public void a_new_row_of_a_group_asks_the_rule_and_both_switches_keep_the_persons_choice()
    {
        final String rows = bare(method(read(JAVA + "HelperGroup.java"),
                                        "static void new_or_updated_group(long group_num, "
                                        + "String who_invited_public_key,"));
        final int muted = rows.indexOf("conf_new.notification_silent=CommunityAutoJoin.mutesNewRow(group_identifier);");
        assertTrue(muted > 0 && muted < rows.indexOf("orma.insertIntoGroupDB(conf_new);"));

        final String dossier = bare(method(read(JAVA + "AirGroupDossierActivity.java"),
                                           "private void setMuted(final boolean muted)"));
        assertTrue(dossier.indexOf("CommunityAutoJoin.noteSoundChoice(groupId,muted);")
                   > dossier.indexOf(".notification_silent(muted).execute();"));
        final String holder = bare(read(JAVA + "GroupListHolder.java"));
        assertTrue(holder.contains("notification_silent(this.group.notification_silent).execute();"
                                   + "CommunityAutoJoin.noteSoundChoice(this.group.group_identifier,true);"));
        assertTrue(holder.contains("CommunityAutoJoin.noteSoundChoice(this.group.group_identifier,false);"));

        final String auto = read(JAVA + "CommunityAutoJoin.java");
        assertTrue(bare(method(auto, "static boolean mutesNewRow(final String groupId)")).contains(
                "return(store!=null)&&KhandaqCommunity.mutesNewRow(groupId,store);"));
        assertTrue(bare(method(auto, "static void qaForgetMarks()")).contains("KhandaqCommunity.MARK_MUTED"));
        assertTrue(bare(method(auto, "static void qaForgetMarks()")).contains("KhandaqCommunity.MARK_SOUND_RESTORED"));
    }

    // ---- a full Community (review, 2026-11-16) ----

    private static final int PEER_LIMIT = ToxVars.TOX_GROUP_JOIN_FAIL.TOX_GROUP_JOIN_FAIL_PEER_LIMIT.value;
    private static final long MINUTE_MS = 60_000L;

    @Test
    public void toxcore_says_full_password_and_unknown_in_the_order_of_tox_h()
    {
        assertEquals(0, ToxVars.TOX_GROUP_JOIN_FAIL.TOX_GROUP_JOIN_FAIL_PEER_LIMIT.value);
        assertEquals(1, ToxVars.TOX_GROUP_JOIN_FAIL.TOX_GROUP_JOIN_FAIL_INVALID_PASSWORD.value);
        assertEquals(2, ToxVars.TOX_GROUP_JOIN_FAIL.TOX_GROUP_JOIN_FAIL_UNKNOWN.value);
    }

    @Test
    public void only_the_community_refused_as_full_waits_for_room()
    {
        assertTrue(KhandaqCommunity.waitsForRoom(KhandaqCommunity.CHAT_ID, PEER_LIMIT));
        assertTrue(KhandaqCommunity.waitsForRoom(COMMUNITY_ROW_ID, PEER_LIMIT));
        // Another refusal of it, and the same refusal of another group, are tried again at once as before.
        assertFalse(KhandaqCommunity.waitsForRoom(KhandaqCommunity.CHAT_ID,
                                                  ToxVars.TOX_GROUP_JOIN_FAIL.TOX_GROUP_JOIN_FAIL_INVALID_PASSWORD.value));
        assertFalse(KhandaqCommunity.waitsForRoom(KhandaqCommunity.CHAT_ID,
                                                  ToxVars.TOX_GROUP_JOIN_FAIL.TOX_GROUP_JOIN_FAIL_UNKNOWN.value));
        assertFalse(KhandaqCommunity.waitsForRoom(OTHER_GROUP, PEER_LIMIT));
        assertFalse(KhandaqCommunity.waitsForRoom(LEGACY_TRIFA_GROUP, PEER_LIMIT));
        assertFalse(KhandaqCommunity.waitsForRoom(null, PEER_LIMIT));
    }

    @Test
    public void the_pause_before_asking_a_full_community_again_grows_from_five_minutes_to_an_hour()
    {
        assertEquals(5 * MINUTE_MS, KhandaqCommunity.roomRetryDelayMs(1));
        assertEquals(10 * MINUTE_MS, KhandaqCommunity.roomRetryDelayMs(2));
        assertEquals(20 * MINUTE_MS, KhandaqCommunity.roomRetryDelayMs(3));
        assertEquals(40 * MINUTE_MS, KhandaqCommunity.roomRetryDelayMs(4));
        assertEquals(60 * MINUTE_MS, KhandaqCommunity.roomRetryDelayMs(5));
        assertEquals(60 * MINUTE_MS, KhandaqCommunity.roomRetryDelayMs(6));
        assertEquals(60 * MINUTE_MS, KhandaqCommunity.roomRetryDelayMs(Integer.MAX_VALUE));
        assertEquals(5 * MINUTE_MS, KhandaqCommunity.roomRetryDelayMs(0));
    }

    @Test
    public void a_full_community_let_go_of_is_joined_again_later_with_its_switch_and_its_leave_untouched()
    {
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore store = new ProfileStore(settings, TOX_ID);
        store.setAutoJoinDone();
        assertTrue(KhandaqCommunity.noteGroupLetGo(store));
        assertFalse(store.autoJoinDone());
        assertFalse(store.left());
        assertFalse(KhandaqCommunity.noteGroupLetGo(store));

        // The next try, or the next start, joins it again; its row was kept, so its switch stays as it was.
        final ProfileStore restarted = new ProfileStore(settings, TOX_ID);
        final Join join = new Join(true);
        assertTrue(KhandaqCommunity.autoJoin(restarted, true, false, join));
        assertEquals(1, join.calls);
        assertFalse(restarted.keptMuted());

        // A Community the person left stays left.
        final ProfileStore leaver = new ProfileStore(new HashMap<>(), TOX_ID);
        leaver.setAutoJoinDone();
        leaver.setLeft();
        KhandaqCommunity.noteGroupLetGo(leaver);
        assertTrue(leaver.left());
        final Join never = new Join(true);
        assertFalse(KhandaqCommunity.autoJoin(leaver, true, false, never));
        assertEquals(0, never.calls);
    }

    @Test
    public void a_full_community_is_let_go_of_and_nothing_joins_it_again_before_its_pause_is_over()
    {
        final String helper = read(JAVA + "HelperGroup.java");
        // No hard reconnect and no quick retries for it: those are for every other refusal.
        final String fail = bare(method(helper, "static void handle_group_join_fail_async(final long group_number, final int fail_type)"));
        final int full = fail.indexOf("if(KhandaqCommunity.waitsForRoom(group_identifier,fail_type)){"
                                      + "CommunityAutoJoin.waitForRoom(group_number);update_group_in_friendlist(group_identifier);return;}");
        assertTrue(full > 0);
        assertTrue(full < fail.indexOf("reconnect_group_if_disconnected(group_number,group_identifier,true);"));
        assertTrue(full < fail.indexOf("schedule_group_join_fail_retry(group_identifier);"));

        // toxcore lets go of the instance, without a notice; a friend's late invitation is not taken by itself.
        final String letGo = bare(method(helper, "static void let_go_of_group_instance(final long group_num)"));
        assertTrue(letGo.contains("clear_pending_friend_assisted_join(tox_group_by_groupnum__wrapper(group_num));"
                                  + "finalintres=tox_group_leave(group_num,\"\");"));
        assertFalse(letGo.contains("tox_group_send_custom_packet"));
        assertFalse("its row stays", letGo.contains("delete_group"));
        assertFalse("its leave mark stays unset", letGo.contains("set_group_group_we_left"));

        final String auto = read(JAVA + "CommunityAutoJoin.java");
        final String room = bare(method(auto, "static void waitForRoom(final long groupNumber)"));
        assertTrue(room.contains("delayMs=KhandaqCommunity.roomRetryDelayMs(roomRefusals);"));
        assertTrue(room.contains("HelperGroup.let_go_of_group_instance(groupNumber);"));
        assertTrue(room.contains("KhandaqCommunity.noteGroupLetGo(store);"));
        assertFalse(room.contains("setLeft"));
        assertTrue("asked again once the pause is over, in the same session only",
                   room.contains("Thread.sleep(delayMs);") && room.contains("if(sessionGoesOn(session)){onNodeConnected();}"));
        assertTrue(bare(method(auto, "static void onGroupConnected(final String groupId)")).contains("roomWait=NO_WAIT;"));

        // Nothing joins it by itself meanwhile: the auto-join, the screens that show it, the friends asked to invite us.
        final String joinIfDue = bare(method(auto, JOIN_IF_DUE));
        final int gate = joinIfDue.indexOf("if(waitsForRoom(KhandaqCommunity.CHAT_ID)){return;}");
        assertTrue(gate > 0 && gate < joinIfDue.indexOf("KhandaqCommunity.autoJoin("));
        final String ensure = bare(method(helper, "static long ensure_group_in_tox(@NonNull String group_identifier)"));
        final int ensureGate = ensure.indexOf("if(CommunityAutoJoin.waitsForRoom(group_identifier)){return-1;}");
        assertTrue(ensureGate > 0 && ensureGate < ensure.indexOf("MainActivity.tox_group_join("));
        assertTrue(bare(method(helper, "static void schedule_post_join_peer_discovery(")).contains(
                "&&!is_group_we_left(group_identifier)&&!CommunityAutoJoin.waitsForRoom(group_identifier))"));
    }

    private static final String JOIN_IF_DUE = "private static void joinIfDue(final int session)";
    /** The join by chat id itself, the one with every argument. */
    private static final String JOIN_BY_ID =
            "static boolean join_public_group_by_id(final String group_id, @Nullable final String password, final boolean show_toast,\n"
            + "                                           @Nullable final java.util.function.Consumer<String> failures,\n"
            + "                                           @Nullable final java.util.function.BooleanSupplier still_wanted)";

    // ---- its name and its place at the top of the list (the owner's rule, 2026-11-16) ----

    /** A row of a chat list, told apart as FriendListFragment.CHAT_ROWS tells its rows apart. */
    private static final class ListRow
    {
        final String name;
        /** Null for a row that is not a group. */
        final String groupId;
        final boolean weLeft;

        ListRow(final String name, final String groupId, final boolean weLeft)
        {
            this.name = name;
            this.groupId = groupId;
            this.weLeft = weLeft;
        }
    }

    private static final KhandaqCommunity.ChatRows<ListRow> ROWS = new KhandaqCommunity.ChatRows<ListRow>()
    {
        @Override
        public boolean pinned(final ListRow row)
        {
            return (row.groupId != null) && KhandaqCommunity.pinned(row.groupId, row.weLeft);
        }

        @Override
        public boolean saved(final ListRow row)
        {
            return "Saved Messages".equals(row.name);
        }
    };

    private static final String COMMUNITY_ROW_ID = KhandaqCommunity.CHAT_ID.toLowerCase(Locale.ROOT);
    private static final ListRow SAVED = new ListRow("Saved Messages", null, false);
    private static final ListRow AMINA = new ListRow("Amina", null, false);
    private static final ListRow STUDY = new ListRow("Study circle", OTHER_GROUP, false);
    private static final ListRow COMMUNITY = new ListRow("Khandaq Community", COMMUNITY_ROW_ID, false);
    private static final ListRow COMMUNITY_LEFT = new ListRow("Khandaq Community", COMMUNITY_ROW_ID, true);

    @Test
    public void the_community_goes_by_its_own_name_whatever_its_topic()
    {
        final String topic = "Official Khandaq community · Be kind, no spam or ads · Admins never write first";
        for (final String id : new String[]{KhandaqCommunity.CHAT_ID, COMMUNITY_ROW_ID})
        {
            assertEquals("Khandaq Community", KhandaqCommunity.displayName(id, topic));
            assertEquals("Khandaq Community", KhandaqCommunity.displayName(id, ""));
            assertEquals("Khandaq Community", KhandaqCommunity.displayName(id, null));
            // The hook every screen names a group through, and every name stored for one.
            assertEquals("Khandaq Community", HelperGroup.get_group_display_name(id, topic));
        }
    }

    @Test
    public void other_groups_keep_the_name_they_have()
    {
        assertEquals("Study circle", KhandaqCommunity.displayName(OTHER_GROUP, "Study circle"));
        assertEquals("", KhandaqCommunity.displayName(OTHER_GROUP, null));
        assertEquals("Official Khandaq community",
                     HelperGroup.get_group_display_name(OTHER_GROUP, "Official Khandaq community"));
        assertEquals("", HelperGroup.get_group_display_name(LEGACY_TRIFA_GROUP, null));
    }

    @Test
    public void every_name_shown_or_stored_for_a_group_goes_through_the_name_rule()
    {
        final String helper = read(JAVA + "HelperGroup.java");
        // The title the list, the chat, the dossier, the pickers and a report show: each way out asks the rule.
        final String title = method(helper,
                                    "static String get_effective_group_title(final long group_num, final String group_identifier)");
        assertEquals(count(title, "return "), count(title, "return get_group_display_name(group_identifier, "));
        // The stored name that notifications and the search read.
        assertTrue(bare(method(helper, "static void update_group_in_db_name(final String group_identifier, final String name)"))
                           .contains(".name(get_group_display_name(group_identifier,name))."));
        assertTrue(bare(method(helper, "static void name_group_from_invitation(final String group_identifier,"))
                           .contains(".name(get_group_display_name(id,name))."));
        assertTrue(bare(helper).contains("conf_new.name=get_group_display_name(group_identifier,group_topic);"));
        // A row stored before, or a row of it the person left, gets its name where the groups load at the start.
        assertTrue(bare(method(helper, "static void store_community_display_name()"))
                           .contains(".name(KhandaqCommunity.DISPLAY_NAME)."));
        assertTrue(method(read(JAVA + "TrifaToxService.java"), "void load_and_add_all_groups()")
                           .contains("HelperGroup.store_community_display_name();"));
    }

    @Test
    public void its_row_stands_second_right_below_saved_messages()
    {
        final List<ListRow> all = Arrays.asList(SAVED, AMINA, STUDY, COMMUNITY);
        assertEquals(Arrays.asList("Saved Messages", "Khandaq Community", "Amina", "Study circle"), pin(all, all));
    }

    @Test
    public void without_saved_messages_on_top_its_row_stands_first()
    {
        final List<ListRow> all = Arrays.asList(AMINA, STUDY, COMMUNITY);
        assertEquals(Arrays.asList("Khandaq Community", "Amina", "Study circle"), pin(all, all));
        assertEquals(Collections.singletonList("Khandaq Community"), pin(Collections.<ListRow>emptyList(), all));
    }

    @Test
    public void a_filter_that_left_it_out_shows_it_as_it_shows_saved_messages()
    {
        // Saved Messages stands in every filter of the list (FavoritesChatHelper.prependPinnedRow); so does it.
        final List<ListRow> all = Arrays.asList(SAVED, AMINA, STUDY, COMMUNITY);
        assertEquals(Arrays.asList("Saved Messages", "Khandaq Community", "Amina"), pin(Arrays.asList(SAVED, AMINA), all));
    }

    @Test
    public void once_left_its_row_is_not_pinned()
    {
        assertTrue(KhandaqCommunity.pinned(KhandaqCommunity.CHAT_ID, false));
        assertTrue(KhandaqCommunity.pinned(COMMUNITY_ROW_ID, false));
        assertFalse(KhandaqCommunity.pinned(KhandaqCommunity.CHAT_ID, true));
        assertFalse(KhandaqCommunity.pinned(OTHER_GROUP, false));
        assertFalse(KhandaqCommunity.pinned(null, false));
        // A row of it the person left is a group like any other: it stays where the list put it.
        final List<ListRow> all = Arrays.asList(SAVED, AMINA, COMMUNITY_LEFT, STUDY);
        assertEquals(Arrays.asList("Saved Messages", "Amina", "Khandaq Community", "Study circle"), pin(all, all));
    }

    @Test
    public void both_chat_lists_pin_the_row_of_the_group_the_person_is_in()
    {
        final String list = read(JAVA + "FriendListFragment.java");
        final String filter = bare(method(list, "private List<CombinedFriendsAndConferences> filterChatsListItems("));
        final int saved = filter.indexOf("FavoritesChatHelper.prependPinnedRow(filtered);");
        assertTrue(saved >= 0 && filter.indexOf("KhandaqCommunity.pinRow(filtered,items,CHAT_ROWS);") > saved);
        assertTrue(bare(list).contains(
                "KhandaqCommunity.pinned(row.group_item.group_identifier,row.group_item.group_we_left)"));
        assertTrue(bare(read(JAVA + "AirRepository.java")).contains("KhandaqCommunity.pinned(groupId,group.group_we_left)"));
    }

    private static List<String> pin(final List<ListRow> shown, final List<ListRow> all)
    {
        final List<ListRow> rows = new ArrayList<>(shown);
        KhandaqCommunity.pinRow(rows, all, ROWS);
        final List<String> names = new ArrayList<>();
        for (final ListRow row : rows)
        {
            names.add(row.name);
        }
        return names;
    }

    @Test
    public void leaving_and_deleting_the_group_both_mark_the_leave_before_anything_else()
    {
        final String list = read(JAVA + "GroupListHolder.java");
        final String leave = method(list, "static void leave_group(final String group_identifier)");
        final int left = leave.indexOf("CommunityAutoJoin.noteGroupLeft(group_identifier);");
        assertTrue(left > 0);
        assertTrue(left < leave.indexOf("HelperGroup.leave_group_with_notice(group_num, \"left\");"));
        assertTrue(left < leave.indexOf("set_group_group_we_left(group_identifier);"));
        final String delete = method(list, "private static void delete_group_now(final GroupDB f2)");
        final int deleted = delete.indexOf("CommunityAutoJoin.noteGroupDeleted(f2.group_identifier);");
        assertTrue(deleted > 0);
        assertTrue(deleted < delete.indexOf("HelperGroup.leave_group_with_notice(group_num, \"bye\");"));
        assertTrue(deleted < delete.indexOf("delete_group(f2.group_identifier);"));
        // A delete is a leave too, and the Community's switch goes with its row; a leave keeps the row and its switch.
        final String auto = read(JAVA + "CommunityAutoJoin.java");
        final String onDelete = bare(method(auto, "static void noteGroupDeleted(final String groupId)"));
        assertTrue(onDelete.startsWith("staticvoidnoteGroupDeleted(finalStringgroupId){noteGroupLeft(groupId);"));
        assertTrue(onDelete.contains("KhandaqCommunity.noteRowDeleted(store,groupId);"));
        assertFalse(bare(method(auto, "static void noteGroupLeft(final String groupId)")).contains("noteRowDeleted"));

        // The auto-join's join stops at its next step on a leave, and leaves again as the person leaves.
        final String joinIfDue = bare(method(read(JAVA + "CommunityAutoJoin.java"), JOIN_IF_DUE));
        final int again = joinIfDue.indexOf("publicvoidleaveAgain()");
        assertTrue(again > 0 && joinIfDue.indexOf("GroupListHolder.leave_group(KhandaqCommunity.CHAT_ID);", again) > again);
    }

    @Test
    public void a_leave_made_while_the_join_ran_stays_and_the_group_the_join_gave_back_is_left_again()
    {
        final Map<String, String> settings = new HashMap<>();
        final ProfileStore store = new ProfileStore(settings, TOX_ID);
        final Join join = new Join(true);
        // The person leaves it from its row while the join runs; the leave marks itself first.
        join.meanwhile = () -> KhandaqCommunity.noteGroupLeft(store, KhandaqCommunity.CHAT_ID);
        assertFalse(KhandaqCommunity.autoJoin(store, true, false, join));
        assertEquals(1, join.leftAgain);
        assertTrue(store.left());
        assertFalse("nothing of the join stays", store.keptMuted());
        final Join later = new Join(true);
        assertFalse(KhandaqCommunity.autoJoin(new ProfileStore(settings, TOX_ID), true, false, later));
        assertEquals("never by itself again, after a restart too", 0, later.calls);

        // A join the leave stopped on its way leaves again too: the row's own mark is put back.
        final ProfileStore other = new ProfileStore(new HashMap<>(), TOX_ID);
        final Join stopped = new Join(false);
        stopped.meanwhile = () -> KhandaqCommunity.noteGroupLeft(other, KhandaqCommunity.CHAT_ID);
        assertFalse(KhandaqCommunity.autoJoin(other, true, false, stopped));
        assertEquals(1, stopped.leftAgain);
        assertFalse(other.keptMuted());

        // Without a leave nothing is left again.
        final Join plain = new Join(true);
        assertTrue(KhandaqCommunity.autoJoin(new ProfileStore(new HashMap<>(), TOX_ID), true, false, plain));
        assertEquals(0, plain.leftAgain);
    }

    @Test
    public void every_group_file_path_asks_the_community_rule()
    {
        final String gate = method(read(JAVA + "HelperGroup.java"),
                                   "static boolean ngc_incoming_download_allowed(final String groupId, final String msgIdHash)");
        assertTrue(bare(gate).contains(
                "KhandaqCommunity.fileComesByItself(groupId,HelperFiletransfer.attachment_auto_download_allowed())"
                + "||is_ngc_manual_download_requested(groupId,msgIdHash);"));
    }

    private static String bare(final String source)
    {
        return source.replaceAll("\\s+", "");
    }

    private static int count(final String text, final String part)
    {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
        {
            n++;
        }
        return n;
    }

    /** The text of the method that starts with signature, up to its closing brace at class level. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

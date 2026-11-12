package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.GroupPeerDB;
import com.zoffcc.applications.sorm.Message;
import com.zoffcc.applications.sorm.OrmaDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.khandaq.messenger.R;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KHANDAQ (redesign, stage 5): the made-up demo world of the Play frames and of the measurements with
 * hundreds of chats, in debug builds only (src/debug). It fills a fresh test profile with the people, groups
 * and texts of the iOS store frames (OCTManagerMock), with Ibrahim Gasanov where iOS has Bilal Haddad.
 *
 * <p>Everything goes into the database and the stores the screens read: FriendList, Message (the favorites
 * chat included), GroupDB, GroupPeerDB, GroupMessage, AirRequestStore and AirContactMarks. Nothing goes into
 * toxcore: the contacts and groups are announced to {@link AirLiveOverride} before their rows exist, so the
 * app never adds them to toxcore or joins them, and a message written to one of them waits in the database.
 * What toxcore would say about a group (your role and key in it, its members, their presence, the counts)
 * comes from the overlay. A contact's presence lives in its row, which every start of toxcore resets, so a
 * thread puts it back.
 *
 * <p>A profile with a contact or a group of its own is refused: the world is for a fresh test profile, and
 * the name it gives you would otherwise reach real contacts. The state (which rows are demo rows, the name
 * the profile had) is kept in this app's preferences until "clear".
 */
final class AirDemoWorld
{
    static final String TAG = "trifa.AirDemoWorld";

    static final String WORLD_STORE = "store";
    static final String WORLD_BULK = "bulk";
    static final String WORLD_CLEAR = "clear";

    static final String EXTRA_WORLD = "world";
    static final String EXTRA_LANG = "lang";
    static final String EXTRA_OBSERVER = "observer";
    static final String EXTRA_CONTACTS = "contacts";
    static final String EXTRA_THREAD = "thread";
    static final String EXTRA_GROUP_THREAD = "group_thread";

    private static final String PREFS = "air_demo_world";
    private static final String PREF_STATE = "state";

    // The keys of OCTManagerMock, so both platforms draw the same tiles in the same tones.
    static final String KEY_AMINA = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71";
    static final String KEY_YUSUF = "2C8E5F1A9B3D7046E2A8C5F19D3B7E604A2F8C1D5E9B3A7046C2E8F1A5D9B3E7";
    static final String KEY_IBRAHIM = "9D1B7E3F5A2C8046B9E1D7F3A5C2084E6B1D9F7A3C5E2048D6B9F1E7A3C5D208";
    static final String KEY_LONG = "4E6A2C8F0B5D7193E4A6C8F2B0D5E7193A4C6E8F2D0B5A7193E6C4A8F2B0D5E7";
    static final String KEY_LEILA = "B5D3F1A7C9E2064B8D5F3A1C7E9B2064D8F5A3C1E7B9D2046F8A5C3E1B7D9F20";
    static final String KEY_REQUEST = "0142EB6ABBDC1C2A417431B4E940E0F48C7C31073CF8606F628A331619493721";
    static final String KEY_SELF_IN_GROUPS = "5E1F7A33C0D94B28A6E1F09C3D7B52E4A8C61F0D93B27E5A4C08F16D2B93E7A5";
    private static final Set<String> STORE_KEYS = new HashSet<>(
            Arrays.asList(KEY_AMINA, KEY_YUSUF, KEY_IBRAHIM, KEY_LONG, KEY_LEILA));

    // Made-up group ids, lower case as toxcore's chat ids are stored.
    static final String GROUP_HIKING = String.format(Locale.ROOT, "%064x", 0xD101L);
    static final String GROUP_MOSQUE = String.format(Locale.ROOT, "%064x", 0xD102L);
    private static final int HIKING_NUMBER = 101;
    private static final int MOSQUE_NUMBER = 102;

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    private static final long PRESENCE_PERIOD_MS = 3_000L;

    private static final int IN = 0;
    private static final int OUT_DELIVERED = 1;
    /** Left this phone, no receipt yet: one check. */
    private static final int OUT_SENT = 2;
    /** Waits for the person who is offline: the In transit tray. */
    private static final int OUT_WAITING = 3;
    private static final int CALL_NO_ANSWER = 4;
    private static final int SAVED_NOTE = 5;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "air-demo-world");
        thread.setDaemon(true);
        return thread;
    });

    @Nullable
    private static volatile State current = null;
    private static boolean presenceStarted = false;
    private static int inserted = 0;

    private AirDemoWorld()
    {
    }

    // ---- entry points ----

    /** From AirDemoWorldInstaller when the process starts: a world seeded before keeps its overlay. */
    static void install(final Context context)
    {
        final State state = load(context);
        if (state == null || state.isEmpty())
        {
            return;
        }
        current = state;
        AirLiveOverride.source = new Overlay(state);
        startPresence();
        Log.i(TAG, "install: overlay for " + state.friends.size() + " contacts and " + state.groups.size() + " groups");
    }

    /** From AirDemoWorldActivity: one request at a time, on the worker. */
    static void request(final Context context, final Intent intent)
    {
        final String world = textOr(intent.getStringExtra(EXTRA_WORLD), WORLD_STORE);
        final String lang = textOr(intent.getStringExtra(EXTRA_LANG), "en");
        final boolean observer = intent.getBooleanExtra(EXTRA_OBSERVER, true);
        final int contacts = clamp(intent.getIntExtra(EXTRA_CONTACTS, 300), 1, 2000);
        final int thread = clamp(intent.getIntExtra(EXTRA_THREAD, 5000), 0, 50000);
        final int groupThread = clamp(intent.getIntExtra(EXTRA_GROUP_THREAD, 2000), 0, 50000);
        final Context app = context.getApplicationContext();
        Log.i(TAG, "request: world=" + world + " lang=" + lang + " observer=" + observer + " contacts=" + contacts
                   + " thread=" + thread + " group_thread=" + groupThread);
        WORKER.execute(() -> run(app, world, lang, observer, contacts, thread, groupThread));
    }

    private static void run(final Context app, final String world, final String lang, final boolean observer,
                            final int contacts, final int thread, final int groupThread)
    {
        final long started = System.currentTimeMillis();
        try
        {
            if (!WORLD_STORE.equals(world) && !WORLD_BULK.equals(world) && !WORLD_CLEAR.equals(world))
            {
                Log.w(TAG, "refused: world is store, bulk or clear, not " + world);
                return;
            }
            if (!"en".equals(lang) && !"ru".equals(lang))
            {
                Log.w(TAG, "refused: lang is en or ru, not " + lang);
                return;
            }
            if ((TrifaToxService.orma == null) || !TrifaToxService.is_tox_started)
            {
                Log.w(TAG, "refused: no profile is open; start Khandaq on this Android user, sign in, then ask again");
                return;
            }
            State previous = current;
            if (previous == null)
            {
                previous = load(app);
            }
            if (previous == null)
            {
                previous = new State();
            }

            if (WORLD_CLEAR.equals(world))
            {
                // Rows first, the overlay last: while a demo row exists the app must keep it out of toxcore.
                clearRows(previous, true);
                current = null;
                AirLiveOverride.source = null;
                save(app, null);
                AirRepository.requestRefreshNow();
                Log.i(TAG, "done: clear in " + (System.currentTimeMillis() - started) + " ms");
                return;
            }

            final String own = ownDataReason(previous);
            if (own != null)
            {
                Log.w(TAG, "refused: this profile has " + own
                           + " of its own; seed a fresh test profile in a secondary Android user");
                return;
            }

            final long now = System.currentTimeMillis();
            final Resources res = resources(app, lang);
            final boolean bulk = WORLD_BULK.equals(world);
            final State next = plan(res, lang, observer, bulk ? contacts : 0, now);
            if (previous.selfChanged)
            {
                next.selfChanged = true;
                next.selfName = previous.selfName;
                next.selfStatus = previous.selfStatus;
            }
            else
            {
                next.selfChanged = true;
                next.selfName = textOr(TRIFAGlobals.global_my_name, "");
                next.selfStatus = textOr(TRIFAGlobals.global_my_status_message, "");
            }

            clearRows(previous, false);
            // Rows of these keys left by a world whose state was lost would collide with the new ones.
            clearRows(next, false);
            // The overlay before the rows: from here on these keys and groups stay out of toxcore.
            current = next;
            AirLiveOverride.source = new Overlay(next);
            save(app, next);

            inserted = 0;
            insertStore(res, next, now);
            if (bulk)
            {
                insertBulk(res, next, contacts, thread, groupThread, now);
            }
            save(app, next);
            setSelf(app, res.getString(R.string.air_demo_name_self), res.getString(R.string.air_demo_self_status));
            startPresence();
            AirRepository.requestRefreshNow();
            Log.i(TAG, "done: " + world + " (" + lang + ") in " + (System.currentTimeMillis() - started) + " ms: "
                       + next.friends.size() + " contacts, " + next.groups.size() + " groups, " + inserted
                       + " messages");
        }
        catch (Throwable e)
        {
            Log.e(TAG, "failed: " + e, e);
        }
    }

    // ---- the world ----

    /** Who is in the world, before any row is written: the overlay needs it first. */
    private static State plan(final Resources res, final String lang, final boolean observer, final int contacts,
                              final long now)
    {
        final State s = new State();
        s.lang = lang;
        s.friends.addAll(Arrays.asList(KEY_AMINA, KEY_YUSUF, KEY_IBRAHIM, KEY_LONG, KEY_LEILA));
        s.online.put(KEY_AMINA, new long[]{AirModel.TOX_CONNECTION_UDP, now - 130L * MINUTE});
        s.online.put(KEY_IBRAHIM, new long[]{AirModel.TOX_CONNECTION_TCP, now - 40L * MINUTE});
        s.request = KEY_REQUEST;

        final String self = res.getString(R.string.air_demo_name_self);
        final String hamza = res.getString(R.string.air_demo_name_hamza);
        final String maryam = res.getString(R.string.air_demo_name_maryam);

        // The group you founded: 9 members, you and 4 others online (OCTManagerMock.populateStageThreeGroups).
        final List<Member> hiking = new ArrayList<>();
        hiking.add(new Member(KEY_SELF_IN_GROUPS, self, AirGroupModel.ROLE_FOUNDER, true, now, true));
        hiking.add(new Member(KEY_AMINA, res.getString(R.string.air_demo_name_amina), AirGroupModel.ROLE_MODERATOR,
                              true, now, false));
        hiking.add(new Member(KEY_IBRAHIM, res.getString(R.string.air_demo_name_ibrahim), AirGroupModel.ROLE_USER,
                              true, now, false));
        hiking.add(new Member(peerKey(HIKING_NUMBER, 5), res.getString(R.string.air_demo_name_khadija),
                              AirGroupModel.ROLE_USER, true, now, false));
        hiking.add(new Member(peerKey(HIKING_NUMBER, 6), res.getString(R.string.air_demo_name_omar),
                              AirGroupModel.ROLE_USER, true, now, false));
        hiking.add(new Member(KEY_YUSUF, res.getString(R.string.air_demo_name_yusuf), AirGroupModel.ROLE_USER, false,
                              now - 25L * MINUTE, false));
        hiking.add(new Member(peerKey(HIKING_NUMBER, 8), res.getString(R.string.air_demo_name_zaynab),
                              AirGroupModel.ROLE_USER, false, now - 3L * HOUR, false));
        hiking.add(new Member(peerKey(HIKING_NUMBER, 9), hamza, AirGroupModel.ROLE_USER, false, 0L, false));
        hiking.add(new Member(peerKey(HIKING_NUMBER, 10), maryam, AirGroupModel.ROLE_OBSERVER, false, now - 2L * DAY,
                              false));
        s.groups.put(GROUP_HIKING, new Group(GROUP_HIKING, res.getString(R.string.air_demo_name_hiking),
                                             ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value,
                                             AirGroupModel.ROLE_FOUNDER, KEY_SELF_IN_GROUPS, hiking));

        if (observer)
        {
            // The group where you only read: 24 members, you and 7 others online.
            final List<Member> mosque = new ArrayList<>();
            mosque.add(new Member(KEY_SELF_IN_GROUPS, self, AirGroupModel.ROLE_OBSERVER, true, now, true));
            mosque.add(new Member(peerKey(MOSQUE_NUMBER, 1), res.getString(R.string.air_demo_name_imam),
                                  AirGroupModel.ROLE_FOUNDER, true, now, false));
            mosque.add(new Member(peerKey(MOSQUE_NUMBER, 2), hamza, AirGroupModel.ROLE_MODERATOR, true, now, false));
            mosque.add(new Member(peerKey(MOSQUE_NUMBER, 3), maryam, AirGroupModel.ROLE_MODERATOR, false,
                                  now - 2L * HOUR, false));
            for (int index = 4; index <= 23; index++)
            {
                final boolean online = index <= 8;
                mosque.add(new Member(peerKey(MOSQUE_NUMBER, index),
                                      res.getString(R.string.air_demo_listener_format, index - 3),
                                      AirGroupModel.ROLE_USER, online, online ? now : now - index * HOUR, false));
            }
            s.groups.put(GROUP_MOSQUE, new Group(GROUP_MOSQUE, res.getString(R.string.air_demo_name_mosque),
                                                 ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value,
                                                 AirGroupModel.ROLE_OBSERVER, KEY_SELF_IN_GROUPS, mosque));
        }

        s.bulkContacts = contacts;
        for (int index = 1; index <= contacts; index++)
        {
            final String key = bulkKey(index);
            s.friends.add(key);
            if (index % 3 == 0)
            {
                s.online.put(key, new long[]{AirModel.TOX_CONNECTION_UDP, 0L});
            }
        }
        return s;
    }

    /** The world of the five store frames (OCTManagerMock.populateStageTwoDemo and Three). */
    private static void insertStore(final Resources res, final State s, final long now)
    {
        final long yesterday = dayStart(now, -1);

        final String amina = res.getString(R.string.air_demo_name_amina);
        insertFriend(KEY_AMINA, amina, res.getString(R.string.air_demo_amina_status), AirModel.TOX_CONNECTION_UDP,
                     TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW, true, null);
        insertMessage(KEY_AMINA, IN, res.getString(R.string.air_demo_amina_1), yesterday + 19L * HOUR + 40L * MINUTE);
        insertMessage(KEY_AMINA, OUT_DELIVERED, res.getString(R.string.air_demo_amina_2),
                      yesterday + 19L * HOUR + 42L * MINUTE);
        insertMessage(KEY_AMINA, IN, res.getString(R.string.air_demo_amina_3), now - 125L * MINUTE);
        insertMessage(KEY_AMINA, IN, res.getString(R.string.air_demo_amina_4), now - 124L * MINUTE);
        insertMessage(KEY_AMINA, OUT_DELIVERED, res.getString(R.string.air_demo_amina_5), now - 118L * MINUTE);
        insertMessage(KEY_AMINA, OUT_DELIVERED, res.getString(R.string.air_demo_amina_6), now - 117L * MINUTE);
        insertMessage(KEY_AMINA, IN, res.getString(R.string.air_demo_amina_7), now - 35L * MINUTE);
        insertMessage(KEY_AMINA, OUT_SENT, res.getString(R.string.air_demo_amina_8), now - 3L * MINUTE);

        // Offline for 25 minutes, three texts in transit. The wake address is too short to be called
        // (HelperFriend.wake_url_for_friend), it only lets the tray say that the phone gets a signal, as on iOS.
        final String yusuf = res.getString(R.string.air_demo_name_yusuf);
        insertFriend(KEY_YUSUF, yusuf, "", AirModel.TOX_CONNECTION_NONE, now - 25L * MINUTE, true, "demo");
        insertMessage(KEY_YUSUF, IN, res.getString(R.string.air_demo_yusuf_1), yesterday + 21L * HOUR + 10L * MINUTE);
        insertMessage(KEY_YUSUF, OUT_DELIVERED, res.getString(R.string.air_demo_yusuf_2),
                      yesterday + 21L * HOUR + 12L * MINUTE);
        insertMessage(KEY_YUSUF, CALL_NO_ANSWER, res.getString(R.string.air_call_no_answer),
                      yesterday + 21L * HOUR + 30L * MINUTE);
        insertMessage(KEY_YUSUF, OUT_WAITING, res.getString(R.string.air_demo_yusuf_3), now - 12L * MINUTE);
        insertMessage(KEY_YUSUF, OUT_WAITING, res.getString(R.string.air_demo_yusuf_4), now - 11L * MINUTE);
        insertMessage(KEY_YUSUF, OUT_WAITING, res.getString(R.string.air_demo_yusuf_5), now - 6L * MINUTE);

        final String ibrahim = res.getString(R.string.air_demo_name_ibrahim);
        insertFriend(KEY_IBRAHIM, ibrahim, "", AirModel.TOX_CONNECTION_TCP, TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW,
                     true, null);
        insertMessage(KEY_IBRAHIM, IN, res.getString(R.string.air_demo_ibrahim_1), now - 50L * MINUTE);
        insertMessage(KEY_IBRAHIM, OUT_DELIVERED, res.getString(R.string.air_demo_ibrahim_2), now - 45L * MINUTE);

        insertFriend(KEY_LONG, res.getString(R.string.air_demo_name_long), "", AirModel.TOX_CONNECTION_NONE,
                     now - 8L * DAY, true, null);
        insertMessage(KEY_LONG, IN, res.getString(R.string.air_demo_long_1), now - 9L * DAY);

        // Added by ID, the request not answered yet: never online, nothing received, "waiting for reply".
        insertFriend(KEY_LEILA, res.getString(R.string.air_demo_name_leila), "", AirModel.TOX_CONNECTION_NONE,
                     TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_OFFLINE, false, null);

        s.savedNotes.add(insertMessage(FavoritesChatHelper.CHAT_ID, SAVED_NOTE, res.getString(R.string.air_demo_saved_1),
                                       yesterday + 8L * HOUR));
        s.savedNotes.add(insertMessage(FavoritesChatHelper.CHAT_ID, SAVED_NOTE, res.getString(R.string.air_demo_saved_2),
                                       now - 20L * MINUTE));

        AirRequestStore.add(KEY_REQUEST, res.getString(R.string.air_demo_request_1), now - 40L * MINUTE);

        for (final Group group : s.groups.values())
        {
            insertGroup(group, now);
        }
        final Group hiking = s.groups.get(GROUP_HIKING);
        if (hiking != null)
        {
            insertGroupText(hiking, hiking.member(KEY_AMINA), res.getString(R.string.air_demo_group_1),
                            now - 25L * MINUTE);
            insertGroupText(hiking, hiking.member(KEY_IBRAHIM), res.getString(R.string.air_demo_group_ibrahim),
                            now - 22L * MINUTE);
            insertGroupText(hiking, hiking.member(peerKey(HIKING_NUMBER, 6)), res.getString(R.string.air_demo_group_omar),
                            now - 20L * MINUTE);
            // Khadija asks you by name, with your key in the mention block: the row reads as a mention of you.
            final String handle = firstWord(res.getString(R.string.air_demo_name_self));
            final GroupMentionHelper.MentionEntry mention = new GroupMentionHelper.MentionEntry();
            mention.pubkey = KEY_SELF_IN_GROUPS;
            mention.handle = handle;
            insertGroupText(hiking, hiking.member(peerKey(HIKING_NUMBER, 5)), GroupMentionHelper.encodeMentionsBlock(
                    res.getString(R.string.air_demo_group_khadija_format, handle), Collections.singletonList(mention)),
                            now - 12L * MINUTE);
            insertGroupText(hiking, hiking.member(KEY_SELF_IN_GROUPS), res.getString(R.string.air_demo_group_self),
                            now - 10L * MINUTE);
        }
        final Group mosque = s.groups.get(GROUP_MOSQUE);
        if (mosque != null)
        {
            insertGroupText(mosque, mosque.member(peerKey(MOSQUE_NUMBER, 1)), res.getString(R.string.air_demo_group_2),
                            now - 3L * HOUR);
            insertGroupText(mosque, mosque.member(peerKey(MOSQUE_NUMBER, 2)), res.getString(R.string.air_demo_group_hamza),
                            now - 90L * MINUTE);
        }

        // What this phone has seen of them: Amina online for 2 h 10 min and in the evenings of the last five
        // days, Yusuf this morning and last night, Ibrahim for the last 40 minutes.
        AirContactMarks.noteOffline(KEY_AMINA);
        AirContactMarks.noteOnline(KEY_AMINA, now - 130L * MINUTE);
        for (long minutes = 0L; minutes <= 120L; minutes += 60L)
        {
            AirContactMarks.noteOnline(KEY_AMINA, now - minutes * MINUTE);
        }
        for (int day = 1; day <= 5; day++)
        {
            final long base = dayStart(now, -day);
            for (int hour = 19; hour <= 21; hour++)
            {
                AirContactMarks.noteOnline(KEY_AMINA, base + hour * HOUR + 10L * MINUTE);
            }
        }
        AirContactMarks.setVerified(KEY_AMINA, now - 3L * DAY);
        AirContactMarks.noteOnline(KEY_YUSUF, now - 25L * MINUTE - HOUR);
        AirContactMarks.noteOnline(KEY_YUSUF, now - 25L * MINUTE);
        AirContactMarks.noteOnline(KEY_YUSUF, yesterday + 21L * HOUR + 5L * MINUTE);
        AirContactMarks.noteOffline(KEY_YUSUF);
        AirContactMarks.noteOffline(KEY_IBRAHIM);
        AirContactMarks.noteOnline(KEY_IBRAHIM, now - 40L * MINUTE);
        AirContactMarks.noteOnline(KEY_IBRAHIM, now);
    }

    /** Hundreds of chats, one of thousands of messages, and thousands more in the group you founded. */
    private static void insertBulk(final Resources res, final State s, final int contacts, final int thread,
                                   final int groupThread, final long now)
    {
        final String message = res.getString(R.string.air_demo_bulk_message);
        final String[] texts = {message, res.getString(R.string.air_demo_yusuf_3), res.getString(R.string.air_demo_saved_1),
                                res.getString(R.string.air_demo_saved_2)};
        for (int index = 1; index <= contacts; index++)
        {
            final String key = bulkKey(index);
            final boolean online = index % 3 == 0;
            insertFriend(key, res.getString(R.string.air_demo_contact_format, index), "",
                         online ? AirModel.TOX_CONNECTION_UDP : AirModel.TOX_CONNECTION_NONE,
                         online ? TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW : now - index * HOUR, true, null);
            if (index == 1)
            {
                // Every third one ours, ten minutes apart, before the last one (as iOS DEMO_LONG_THREAD).
                for (int n = 0; n < thread; n++)
                {
                    insertMessage(key, n % 3 == 0 ? OUT_DELIVERED : IN, texts[n % texts.length],
                                  now - ((long) (thread - n) * 10L * MINUTE + 3_700_000L));
                    if ((n + 1) % 500 == 0)
                    {
                        Log.i(TAG, "bulk: " + (n + 1) + " of " + thread + " messages in one chat");
                    }
                }
            }
            insertMessage(key, IN, message, now - index * 3_700_000L);
            if (index % 100 == 0)
            {
                Log.i(TAG, "bulk: " + index + " of " + contacts + " contacts");
            }
        }

        final Group hiking = s.groups.get(GROUP_HIKING);
        if (hiking == null || groupThread <= 0)
        {
            return;
        }
        final List<Member> authors = Arrays.asList(hiking.member(KEY_SELF_IN_GROUPS), hiking.member(KEY_AMINA),
                                                   hiking.member(KEY_IBRAHIM), hiking.member(peerKey(HIKING_NUMBER, 5)),
                                                   hiking.member(peerKey(HIKING_NUMBER, 6)));
        final String[] groupTexts = {res.getString(R.string.air_demo_group_1),
                                     res.getString(R.string.air_demo_group_ibrahim),
                                     res.getString(R.string.air_demo_group_omar),
                                     res.getString(R.string.air_demo_group_self), message};
        for (int n = 0; n < groupThread; n++)
        {
            // All of them older than the messages of the store frame.
            insertGroupText(hiking, authors.get(n % authors.size()), groupTexts[n % groupTexts.length],
                            now - ((long) (groupThread - n) * 10L * MINUTE + 2L * HOUR));
            if ((n + 1) % 500 == 0)
            {
                Log.i(TAG, "bulk: " + (n + 1) + " of " + groupThread + " messages in the group");
            }
        }
    }

    // ---- rows ----

    private static OrmaDatabase db()
    {
        final OrmaDatabase orma = TrifaToxService.orma;
        if (orma == null)
        {
            throw new IllegalStateException("the profile closed");
        }
        return orma;
    }

    private static void insertFriend(final String key, final String name, final String status, final int link,
                                     final long lastOnline, final boolean answered, @Nullable final String wake)
    {
        final FriendList f = new FriendList();
        f.tox_public_key_string = key;
        f.name = name;
        f.status_message = status;
        f.TOX_CONNECTION = link;
        f.TOX_CONNECTION_real = link;
        f.TOX_CONNECTION_on_off = link == AirModel.TOX_CONNECTION_NONE ? 0 : 1;
        f.TOX_CONNECTION_on_off_real = f.TOX_CONNECTION_on_off;
        f.TOX_USER_STATUS = 0;
        f.last_online_timestamp = lastOnline;
        f.last_online_timestamp_real = lastOnline;
        f.added_timestamp = System.currentTimeMillis();
        f.is_relay = false;
        f.push_url = wake;
        f.capabilities = 0L;
        // One who never answered stays "waiting for reply" (AirRepository.neverConnected).
        f.msgv3_capability = answered ? 1L : 0L;
        db().insertIntoFriendList(f);
    }

    /** A 1:1 row as the app writes one of that kind; returns its row id. */
    private static long insertMessage(final String key, final int kind, final String text, final long timeMs)
    {
        final Message m = new Message();
        m.tox_friendpubkey = key;
        m.TOX_MESSAGE_TYPE = 0;
        m.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value;
        m.state = 1;
        m.text = text;
        m.is_new = false;
        m.msg_version = 0;
        // No hash: nothing can be acknowledged or woken for a demo row.
        m.msg_idv3_hash = "";
        m.msg_id_hash = "";
        m.raw_msgv2_bytes = "";
        m.sent_timestamp = timeMs;
        switch (kind)
        {
            case IN:
                m.direction = 0;
                m.read = false;
                m.rcvd_timestamp = timeMs;
                break;
            case OUT_DELIVERED:
                m.direction = 1;
                m.read = true;
                m.message_id = 1;
                m.resend_count = 1;
                m.rcvd_timestamp = timeMs + 2_000L;
                break;
            case OUT_SENT:
                m.direction = 1;
                m.read = false;
                m.message_id = 5;
                m.resend_count = 1;
                m.sent_push = 1;
                break;
            case OUT_WAITING:
                m.direction = 1;
                m.read = false;
                m.message_id = -1;
                m.resend_count = 0;
                // Counted as woken already: TrifaToxService.resend_unsent_text_messages never tries.
                m.sent_push = 1;
                break;
            case CALL_NO_ANSWER:
                // As HelperCall.logCallEntry writes a call that was not answered.
                m.direction = 1;
                m.read = true;
                m.message_id = -1;
                m.resend_count = HelperCall.LOCAL_CALL_LOG_RESEND_COUNT;
                m.sent_timestamp_ms = timeMs;
                break;
            default:
                // A note to yourself, as FavoritesChatHelper writes one.
                m.direction = 1;
                m.read = true;
                m.message_id = 1;
                m.resend_count = 1;
                m.sent_timestamp_ms = timeMs;
                m.rcvd_timestamp = timeMs / 1000L;
                m.rcvd_timestamp_ms = timeMs;
                break;
        }
        inserted++;
        return db().insertIntoMessage(m);
    }

    private static void insertGroup(final Group group, final long now)
    {
        final GroupDB row = new GroupDB();
        row.group_identifier = group.id;
        row.who_invited__tox_public_key_string = "";
        row.name = group.name;
        row.topic = "";
        row.peer_count = -1;
        row.own_peer_number = -1;
        row.privacy_state = group.privacy;
        row.tox_group_number = -1;
        row.group_active = true;
        row.group_we_left = false;
        row.notification_silent = false;
        db().insertIntoGroupDB(row);
        // The members' roles where the screens look for a member toxcore does not list (AirGroupPanel.roleOf).
        for (final Member member : group.members)
        {
            if (member.self)
            {
                continue;
            }
            final GroupPeerDB peer = new GroupPeerDB();
            peer.group_identifier = group.id;
            peer.tox_group_peer_pubkey = member.key;
            peer.peer_name = member.name;
            peer.last_update_timestamp = now;
            peer.first_join_timestamp = now;
            peer.Tox_Group_Role = member.role;
            peer.notification_silent = false;
            db().insertIntoGroupPeerDB(peer);
        }
    }

    /** A group row as the app writes a received one, or one of ours with its receipts back. */
    private static void insertGroupText(final Group group, @Nullable final Member author, final String text,
                                        final long timeMs)
    {
        if (author == null)
        {
            return;
        }
        final GroupMessage m = new GroupMessage();
        m.message_id_tox = messageIdTox();
        m.group_identifier = group.id;
        m.tox_group_peer_pubkey = author.key;
        m.tox_group_peer_role = author.self ? 0 : author.role;
        m.private_message = 0;
        m.tox_group_peername = author.self ? null : author.name;
        m.direction = author.self ? 1 : 0;
        m.TOX_MESSAGE_TYPE = 0;
        m.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value;
        m.sent_timestamp = timeMs;
        m.rcvd_timestamp = timeMs;
        m.read = author.self;
        m.is_new = false;
        m.text = text;
        m.was_synced = false;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
        m.sync_confirmations = author.self ? 3 : 0;
        m.msg_id_hash = randomHex(32);
        inserted++;
        db().insertIntoGroupMessage(m);
    }

    /** Removes every row of the world in {@code state}; puts your own name back when asked. */
    private static void clearRows(final State state, final boolean restoreSelf)
    {
        for (final String key : state.friends)
        {
            try
            {
                db().deleteFromMessage().tox_friendpubkeyEq(key).execute();
                db().deleteFromFriendList().tox_public_key_stringEq(key).execute();
            }
            catch (Exception e)
            {
                Log.w(TAG, "clear: contact: " + e);
            }
            AirContactMarks.forget(key);
            ChatListUiHelper.invalidate_unread_count_friend(key);
        }
        for (final Long id : state.savedNotes)
        {
            try
            {
                db().deleteFromMessage().idEq(id).execute();
            }
            catch (Exception e)
            {
                Log.w(TAG, "clear: note: " + e);
            }
        }
        for (final String id : state.groups.keySet())
        {
            try
            {
                db().deleteFromGroupMessage().group_identifierEq(id).execute();
                db().deleteFromGroupPeerDB().group_identifierEq(id).execute();
                db().deleteFromGroupDB().group_identifierEq(id).execute();
            }
            catch (Exception e)
            {
                Log.w(TAG, "clear: group: " + e);
            }
            ChatListUiHelper.invalidate_unread_count_group(id);
        }
        if (state.request != null)
        {
            AirRequestStore.remove(state.request);
        }
        if (restoreSelf && state.selfChanged)
        {
            setSelf(null, state.selfName, state.selfStatus);
        }
    }

    /** Your name and status as ProfileContentFragment.saveProfileChanges saves them. */
    private static void setSelf(@Nullable final Context app, final String name, final String status)
    {
        TRIFAGlobals.global_my_name = name;
        TRIFAGlobals.global_my_status_message = status;
        MainActivity.tox_self_set_name(name);
        final Context context = app != null ? app : MainActivity.context_s;
        if (context != null)
        {
            DbSecretKeyStorage.rememberProfileName(context, name);
        }
        MainActivity.tox_self_set_status_message(status);
        HelperGeneric.update_savedata_file_wrapper();
        new Handler(Looper.getMainLooper()).post(MainActivity::update_main_profile_bar);
    }

    /** Null when the profile holds nothing but this world, else what it holds of its own. */
    @Nullable
    private static String ownDataReason(final State previous)
    {
        final List<FriendList> friends = db().selectFromFriendList().toList();
        if (friends != null)
        {
            for (final FriendList friend : friends)
            {
                final String key = friend.tox_public_key_string == null ? ""
                                   : friend.tox_public_key_string.trim().toUpperCase(Locale.ROOT);
                if (key.isEmpty() || friend.is_relay || FavoritesChatHelper.isFavoritesChat(friend.tox_public_key_string)
                    || HelperFriend.is_own_public_key(key) || STORE_KEYS.contains(key) || isBulkKey(key)
                    || previous.friends.contains(key))
                {
                    continue;
                }
                return "a contact";
            }
        }
        final List<GroupDB> groups = db().selectFromGroupDB().toList();
        if (groups != null)
        {
            for (final GroupDB group : groups)
            {
                final String id = group.group_identifier == null ? ""
                                  : group.group_identifier.trim().toLowerCase(Locale.ROOT);
                if (id.isEmpty() || GROUP_HIKING.equals(id) || GROUP_MOSQUE.equals(id) || previous.groups.containsKey(id))
                {
                    continue;
                }
                return "a group";
            }
        }
        return null;
    }

    // ---- presence ----

    private static synchronized void startPresence()
    {
        if (presenceStarted)
        {
            return;
        }
        presenceStarted = true;
        final Thread thread = new Thread(AirDemoWorld::presenceLoop, "air-demo-presence");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Every start of toxcore marks every contact offline (HelperFriend.set_all_friends_offline) and nothing
     * reports these contacts online again, so the rows are put back; one contact stands for all, since the
     * reset touches every row at once. The time since when they are online is kept in memory only
     * (AirContactMarks), so it is put back as well.
     */
    private static void presenceLoop()
    {
        while (true)
        {
            try
            {
                Thread.sleep(PRESENCE_PERIOD_MS);
            }
            catch (InterruptedException e)
            {
                return;
            }
            final State state = current;
            final OrmaDatabase orma = TrifaToxService.orma;
            if (state == null || state.online.isEmpty() || orma == null || !TrifaToxService.is_tox_started)
            {
                continue;
            }
            try
            {
                boolean changed = false;
                final String sentinel = state.online.keySet().iterator().next();
                final List<FriendList> rows = orma.selectFromFriendList().tox_public_key_stringEq(sentinel).toList();
                if (rows == null || rows.isEmpty())
                {
                    continue;
                }
                final FriendList row = rows.get(0);
                if (row.TOX_CONNECTION == AirModel.TOX_CONNECTION_NONE
                    || row.last_online_timestamp != TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
                {
                    applyOnline(orma, state);
                    changed = true;
                }
                for (final Map.Entry<String, long[]> entry : state.online.entrySet())
                {
                    final long since = entry.getValue()[1];
                    if (since <= 0L)
                    {
                        continue;
                    }
                    final Long shown = AirContactMarks.onlineSince(entry.getKey());
                    if (shown == null || shown != since)
                    {
                        if (shown != null)
                        {
                            AirContactMarks.noteOffline(entry.getKey());
                        }
                        AirContactMarks.noteOnline(entry.getKey(), since);
                        changed = true;
                    }
                }
                if (changed)
                {
                    AirRepository.requestRefreshNow();
                }
            }
            catch (Exception e)
            {
                Log.w(TAG, "presence: " + e);
            }
        }
    }

    private static void applyOnline(final OrmaDatabase orma, final State state)
    {
        for (final Map.Entry<String, long[]> entry : state.online.entrySet())
        {
            final int link = (int) entry.getValue()[0];
            orma.updateFriendList().tox_public_key_stringEq(entry.getKey())
                .TOX_CONNECTION(link).TOX_CONNECTION_real(link)
                .TOX_CONNECTION_on_off(1).TOX_CONNECTION_on_off_real(1)
                .last_online_timestamp(TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
                .last_online_timestamp_real(TRIFAGlobals.LAST_ONLINE_TIMSTAMP_ONLINE_NOW)
                .execute();
        }
    }

    // ---- the overlay ----

    /** What toxcore would say about the demo contacts and groups. */
    private static final class Overlay implements AirLiveOverride.Source
    {
        private final Set<String> friends;
        private final Map<String, Group> groups;

        Overlay(final State state)
        {
            friends = Collections.unmodifiableSet(new HashSet<>(state.friends));
            groups = Collections.unmodifiableMap(new HashMap<>(state.groups));
        }

        @Nullable
        private Group find(@Nullable final String groupId)
        {
            return groupId == null ? null : groups.get(groupId.trim().toLowerCase(Locale.ROOT));
        }

        @Override
        public boolean ownsFriend(final String publicKey)
        {
            return publicKey != null && friends.contains(publicKey.trim().toUpperCase(Locale.ROOT));
        }

        @Override
        public boolean ownsGroup(final String groupId)
        {
            return find(groupId) != null;
        }

        @Override
        public boolean groupConnected(final String groupId)
        {
            return find(groupId) != null;
        }

        @Override
        public int groupSelfRole(final String groupId)
        {
            final Group group = find(groupId);
            return group == null ? -1 : group.selfRole;
        }

        @Nullable
        @Override
        public String groupSelfPublicKey(final String groupId)
        {
            final Group group = find(groupId);
            return group == null ? null : group.selfKey;
        }

        @Override
        public List<HelperGroup.GroupMemberDisplay> groupMembers(final String groupId)
        {
            final List<HelperGroup.GroupMemberDisplay> out = new ArrayList<>();
            final Group group = find(groupId);
            if (group == null)
            {
                return out;
            }
            final long now = System.currentTimeMillis();
            for (final Member member : group.members)
            {
                final HelperGroup.GroupMemberDisplay display = new HelperGroup.GroupMemberDisplay();
                display.pubkey = member.key;
                display.self = member.self;
                display.online = member.online;
                display.probable_bot = false;
                display.connection_status = member.online ? AirModel.TOX_CONNECTION_UDP : AirModel.TOX_CONNECTION_NONE;
                display.last_seen_ms = member.online ? now : member.lastSeenMs;
                display.name = HelperGroup.format_group_member_list_label(MainActivity.context_s, member.key, member.name,
                                                                          member.online, member.self);
                out.add(display);
            }
            return out;
        }

        @Override
        public long[] groupCounts(final String groupId)
        {
            final Group group = find(groupId);
            if (group == null)
            {
                return new long[]{0L, 0L};
            }
            long online = 0L;
            long offline = 0L;
            for (final Member member : group.members)
            {
                if (member.online)
                {
                    online++;
                }
                else
                {
                    offline++;
                }
            }
            return new long[]{online, offline};
        }
    }

    // ---- state ----

    static final class Member
    {
        final String key;
        final String name;
        final int role;
        final boolean online;
        final long lastSeenMs;
        final boolean self;

        Member(final String key, final String name, final int role, final boolean online, final long lastSeenMs,
               final boolean self)
        {
            this.key = key;
            this.name = name;
            this.role = role;
            this.online = online;
            this.lastSeenMs = lastSeenMs;
            this.self = self;
        }

        JSONObject toJson() throws JSONException
        {
            return new JSONObject().put("k", key).put("n", name).put("r", role).put("o", online).put("s", lastSeenMs)
                                   .put("me", self);
        }

        static Member fromJson(final JSONObject o) throws JSONException
        {
            return new Member(o.getString("k"), o.optString("n", ""), o.optInt("r", AirGroupModel.ROLE_USER),
                              o.optBoolean("o", false), o.optLong("s", 0L), o.optBoolean("me", false));
        }
    }

    static final class Group
    {
        final String id;
        final String name;
        final int privacy;
        final int selfRole;
        final String selfKey;
        final List<Member> members;

        Group(final String id, final String name, final int privacy, final int selfRole, final String selfKey,
              final List<Member> members)
        {
            this.id = id;
            this.name = name;
            this.privacy = privacy;
            this.selfRole = selfRole;
            this.selfKey = selfKey;
            this.members = Collections.unmodifiableList(new ArrayList<>(members));
        }

        @Nullable
        Member member(final String key)
        {
            for (final Member member : members)
            {
                if (member.key.equalsIgnoreCase(key))
                {
                    return member;
                }
            }
            return null;
        }

        JSONObject toJson() throws JSONException
        {
            final JSONArray list = new JSONArray();
            for (final Member member : members)
            {
                list.put(member.toJson());
            }
            return new JSONObject().put("id", id).put("name", name).put("privacy", privacy).put("role", selfRole)
                                   .put("key", selfKey).put("members", list);
        }

        static Group fromJson(final JSONObject o) throws JSONException
        {
            final List<Member> members = new ArrayList<>();
            final JSONArray list = o.optJSONArray("members");
            if (list != null)
            {
                for (int i = 0; i < list.length(); i++)
                {
                    members.add(Member.fromJson(list.getJSONObject(i)));
                }
            }
            return new Group(o.getString("id"), o.optString("name", ""), o.optInt("privacy", 0),
                             o.optInt("role", -1), o.optString("key", ""), members);
        }
    }

    static final class State
    {
        String lang = "en";
        int bulkContacts = 0;
        /** Every demo contact, upper case: their rows, marks and messages go on "clear". */
        final Set<String> friends = new LinkedHashSet<>();
        /** The contacts shown online: {link (TCP 1, UDP 2), online since or 0}. */
        final Map<String, long[]> online = new LinkedHashMap<>();
        final List<Long> savedNotes = new ArrayList<>();
        @Nullable
        String request = null;
        /** The name and status the profile had before the world: "clear" puts them back. */
        boolean selfChanged = false;
        String selfName = "";
        String selfStatus = "";
        /** By group id, lower case. */
        final Map<String, Group> groups = new LinkedHashMap<>();

        boolean isEmpty()
        {
            return friends.isEmpty() && groups.isEmpty() && savedNotes.isEmpty() && request == null && !selfChanged;
        }

        @Nullable
        String encode()
        {
            try
            {
                final JSONObject o = new JSONObject();
                o.put("lang", lang);
                o.put("bulk", bulkContacts);
                o.put("friends", new JSONArray(friends));
                final JSONArray onlineList = new JSONArray();
                for (final Map.Entry<String, long[]> entry : online.entrySet())
                {
                    onlineList.put(new JSONArray().put(entry.getKey()).put(entry.getValue()[0]).put(entry.getValue()[1]));
                }
                o.put("online", onlineList);
                final JSONArray notes = new JSONArray();
                for (final Long id : savedNotes)
                {
                    notes.put(id.longValue());
                }
                o.put("saved", notes);
                if (request != null)
                {
                    o.put("request", request);
                }
                if (selfChanged)
                {
                    o.put("self_name", selfName);
                    o.put("self_status", selfStatus);
                }
                final JSONArray groupList = new JSONArray();
                for (final Group group : groups.values())
                {
                    groupList.put(group.toJson());
                }
                o.put("groups", groupList);
                return o.toString();
            }
            catch (JSONException e)
            {
                Log.w(TAG, "encode: " + e);
                return null;
            }
        }

        @Nullable
        static State decode(@Nullable final String json)
        {
            if (TextUtils.isEmpty(json))
            {
                return null;
            }
            try
            {
                final JSONObject o = new JSONObject(json);
                final State s = new State();
                s.lang = o.optString("lang", "en");
                s.bulkContacts = o.optInt("bulk", 0);
                final JSONArray friendList = o.optJSONArray("friends");
                if (friendList != null)
                {
                    for (int i = 0; i < friendList.length(); i++)
                    {
                        s.friends.add(friendList.getString(i));
                    }
                }
                final JSONArray onlineList = o.optJSONArray("online");
                if (onlineList != null)
                {
                    for (int i = 0; i < onlineList.length(); i++)
                    {
                        final JSONArray entry = onlineList.getJSONArray(i);
                        s.online.put(entry.getString(0), new long[]{entry.getLong(1), entry.getLong(2)});
                    }
                }
                final JSONArray notes = o.optJSONArray("saved");
                if (notes != null)
                {
                    for (int i = 0; i < notes.length(); i++)
                    {
                        s.savedNotes.add(notes.getLong(i));
                    }
                }
                s.request = o.has("request") ? o.getString("request") : null;
                if (o.has("self_name"))
                {
                    s.selfChanged = true;
                    s.selfName = o.optString("self_name", "");
                    s.selfStatus = o.optString("self_status", "");
                }
                final JSONArray groupList = o.optJSONArray("groups");
                if (groupList != null)
                {
                    for (int i = 0; i < groupList.length(); i++)
                    {
                        final Group group = Group.fromJson(groupList.getJSONObject(i));
                        s.groups.put(group.id, group);
                    }
                }
                return s;
            }
            catch (JSONException e)
            {
                Log.w(TAG, "decode: " + e);
                return null;
            }
        }
    }

    @Nullable
    private static State load(final Context context)
    {
        return State.decode(prefs(context).getString(PREF_STATE, null));
    }

    private static void save(final Context context, @Nullable final State state)
    {
        final String json = state == null ? null : state.encode();
        final SharedPreferences.Editor editor = prefs(context).edit();
        if (json == null)
        {
            editor.remove(PREF_STATE);
        }
        else
        {
            editor.putString(PREF_STATE, json);
        }
        editor.commit();
    }

    private static SharedPreferences prefs(final Context context)
    {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- small helpers ----

    /** The demo strings in the language of the world, whatever the app's language is now. */
    private static Resources resources(final Context context, final String lang)
    {
        final Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(Locale.forLanguageTag(lang));
        return context.createConfigurationContext(configuration).getResources();
    }

    /** A member key of a demo group as OCTManagerMock.addDemoPeer makes one: per group, as NGC keys are. */
    static String peerKey(final int groupNumber, final int peerId)
    {
        return String.format(Locale.ROOT, "%064X", 0xA000L + groupNumber * 0x100L + peerId);
    }

    /** The key of bulk contact {@code index}, as the iOS DEMO_BULK world makes it. */
    static String bulkKey(final int index)
    {
        return String.format(Locale.ROOT, "%064d", index);
    }

    private static boolean isBulkKey(final String key)
    {
        if (key.length() != 64)
        {
            return false;
        }
        for (int i = 0; i < key.length(); i++)
        {
            final char c = key.charAt(i);
            if (c < '0' || c > '9')
            {
                return false;
            }
        }
        return true;
    }

    /** Midnight of the day {@code offsetDays} from the one of {@code nowMs}, in this phone's time zone. */
    private static long dayStart(final long nowMs, final int offsetDays)
    {
        final Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(nowMs);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_YEAR, offsetDays);
        return calendar.getTimeInMillis();
    }

    private static String firstWord(final String name)
    {
        final String trimmed = name.trim();
        final int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    private static String messageIdTox()
    {
        String id;
        do
        {
            id = randomHex(4);
        }
        while ("00000000".equals(id));
        return id;
    }

    private static String randomHex(final int bytes)
    {
        final byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        final char[] digits = "0123456789abcdef".toCharArray();
        final StringBuilder out = new StringBuilder(bytes * 2);
        for (final byte b : raw)
        {
            out.append(digits[(b >> 4) & 0x0f]).append(digits[b & 0x0f]);
        }
        return out.toString();
    }

    private static String textOr(@Nullable final String value, final String fallback)
    {
        return TextUtils.isEmpty(value) ? fallback : value.trim();
    }

    private static int clamp(final int value, final int min, final int max)
    {
        return Math.max(min, Math.min(max, value));
    }
}

/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;

import com.zoffcc.applications.sorm.GroupDB;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, stage 5): the group settings, opened from the group dossier and from the menu of a
 * group in the list, drawn as GroupInfoController on iOS (design/DESIGN.md, "Groups on the phone"): your
 * name in this group and the topic, each with what it does; who can join, with the password and the
 * member limit; who can write, with whether only the founder and the moderators change the topic. The
 * founder changes the last two; everyone else reads them with "The group's founder changes this.". A
 * value with a page of its own (name, topic, password, limit) opens on this screen in place of the list,
 * with its field, what it does under it and Save; a refusal or a failure takes the place of that line, a
 * failure with its code under Details. Toasts only confirm.
 *
 * <p>Who may change what, the toxcore calls and the database writes are the old screen's: the founder
 * sets who can join, the password and the topic lock (when the native library has those calls), the
 * member limit and who can write; everyone sets their own name in the group; the topic goes to toxcore,
 * which takes it from the founder and the moderators and, while the topic is not locked, from members
 * too, never from an observer, so its row opens only where toxcore would take it. Reads and writes run
 * on a worker, never on the main thread, and nothing changes by leaving the screen: only Save, a choice
 * or the switch change a setting. The members stay in the dossier.
 */
public class GroupInfoActivity extends AirScreenActivity
{
    static final String TAG = "trifa.GrpInfoActy";
    /** The group's key in hexadecimal, as every caller puts it. */
    static final String EXTRA_GROUP_ID = "group_id";

    private static final String STATE_FIELD = "air_gs_field";
    private static final String STATE_TEXT = "air_gs_text";
    /** How many lines a name or a topic wraps to in its field before the field scrolls. */
    private static final int FIELD_MAX_LINES = 4;
    private static final int PRIVACY_PUBLIC = ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value;
    private static final int PRIVACY_PRIVATE = ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value;
    /** Who can write, in the order the choices stand (AirGroupSettingsModel.voiceChoice). */
    private static final int[] VOICE_STATES = {ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_ALL.value,
                                               ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_MODERATOR.value,
                                               ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_FOUNDER.value};
    private static final int[] VOICE_TITLES = {R.string.air_gs_voice_all, R.string.air_gs_voice_moderator,
                                               R.string.air_gs_voice_founder};
    private static final int[] VOICE_ROWS = {R.id.air_gs_voice_all_row, R.id.air_gs_voice_moderator_row,
                                             R.id.air_gs_voice_founder_row};
    private static final Object WORKER_LOCK = new Object();
    private static Handler worker = null;

    /** A value with a page of its own, and the row of the list that opens it. */
    private enum Field
    {
        SELF_NAME(R.string.air_gs_self_name, R.id.air_gs_self_name_row),
        TOPIC(R.string.air_gs_topic, R.id.air_gs_topic_row),
        PASSWORD(R.string.air_gs_password, R.id.air_gs_password_row),
        PEER_LIMIT(R.string.air_gs_limit, R.id.air_gs_limit_row);

        @StringRes
        final int title;
        final int row;

        Field(@StringRes final int title, final int row)
        {
            this.title = title;
            this.row = row;
        }
    }

    /** What the screen shows, read off the main thread; the screen writes its own changes into it. */
    private static final class State
    {
        /** toxcore has the group now, so its settings can change here. */
        boolean live;
        /** The native library has the founder's calls for who can join, the password and the topic lock. */
        boolean extended;
        /** Your role, -1 while toxcore does not say. */
        int selfRole = -1;
        /** Your name in this group; empty while toxcore does not say. */
        String selfName = "";
        /** The topic; empty when there is none. */
        String topic = "";
        /** TOPIC_LOCK_ENABLED or TOPIC_LOCK_DISABLED, -1 while toxcore does not say. */
        int topicLock = -1;
        /** The privacy state toxcore gives, else the stored one; -1 when neither says. */
        int privacy = -1;
        /** Who can write as toxcore gives it, -1 while it does not say. */
        int voice = -1;
        int peerLimit = HelperGroup.DEFAULT_GROUP_PEER_LIMIT;
        /** The peers in the group now, -1 while toxcore does not say. */
        long peers = -1;

        boolean founder()
        {
            return live && selfRole == AirGroupModel.ROLE_FOUNDER;
        }

        boolean topicOpens()
        {
            return live && AirGroupSettingsModel.canEditTopic(selfRole, topicLock);
        }

        boolean full()
        {
            return AirGroupSettingsModel.isFull(peers, peerLimit);
        }

        /** The same screen: a member coming or going changes nothing here unless the group fills up. */
        boolean sameAs(@Nullable final State other)
        {
            return other != null && live == other.live && extended == other.extended && selfRole == other.selfRole
                   && selfName.equals(other.selfName) && topic.equals(other.topic) && topicLock == other.topicLock
                   && privacy == other.privacy && voice == other.voice && peerLimit == other.peerLimit
                   && full() == other.full();
        }
    }

    /** How a change went: done, or failed with the code for support when the call gave one. */
    private static final class Outcome
    {
        static final Outcome DONE = new Outcome(true, null);

        final boolean done;
        @Nullable
        final String code;

        private Outcome(final boolean done, @Nullable final String code)
        {
            this.done = done;
            this.code = code;
        }

        static Outcome failed(@Nullable final String code)
        {
            return new Outcome(false, code);
        }
    }

    /** A change, run on the worker. */
    private interface Write
    {
        Outcome run();
    }

    /** One toxcore call on the group's number, with the JNI bridge's result: 0 or more when it went through. */
    private interface ToxCall
    {
        int run(long groupNum);
    }

    private interface Failure
    {
        void say(@Nullable String code);
    }

    /** What a change that went through writes into the shown state. */
    private interface Update
    {
        void apply(State s);
    }

    private interface Query<T>
    {
        T get();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** Back on the page of one value goes back to the list, once the change on its way is done. */
    private final OnBackPressedCallback fieldBack = new OnBackPressedCallback(false)
    {
        @Override
        public void handleOnBackPressed()
        {
            if (!busy)
            {
                closeField();
            }
        }
    };

    private String groupId = "";
    private boolean destroyed = false;
    /** A change is on its way to toxcore: further taps wait for it. */
    private boolean busy = false;
    /** What the screen shows; null until the first read. */
    @Nullable
    private State shown = null;
    /** The reads asked for, so a slower earlier one never draws over a later one. */
    private int reads = 0;

    // The list.
    @Nullable
    private LinearLayout inviteOnlyRow;
    @Nullable
    private LinearLayout openRow;
    /** E21: why who can join did not change, under the two choices. */
    @Nullable
    private TextView joinNote;
    @Nullable
    private LinearLayout[] voiceRows;
    @Nullable
    private TextView writeNote;
    @Nullable
    private SwitchCompat lockSwitch;
    @Nullable
    private TextView lockNote;
    /** The switch is set back by the screen, not by a tap. */
    private boolean lockQuiet = false;

    // The page of one value.
    @Nullable
    private Field open;
    @Nullable
    private AirStepKit.LineField field;
    @Nullable
    private TextView fieldNote;
    @Nullable
    private AirStepKit.MainBar saveBar;
    /** The line under the field says a refusal or a failure instead of what the value does. */
    private boolean noteSays = false;

    // A page of one value to open again once the group is read, after the process was gone.
    @Nullable
    private Field pendingField;
    @Nullable
    private String pendingText;

    @Override
    protected int screenTitle()
    {
        return R.string.air_group_settings;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        final String id = getIntent().getStringExtra(EXTRA_GROUP_ID);
        if (!GroupMessageListActivity.is_valid_group_id(id))
        {
            finish();
            return;
        }
        groupId = id;
        getOnBackPressedDispatcher().addCallback(this, fieldBack);
        if (savedInstanceState != null)
        {
            final String name = savedInstanceState.getString(STATE_FIELD);
            if (name != null)
            {
                try
                {
                    pendingField = Field.valueOf(name);
                    pendingText = savedInstanceState.getString(STATE_TEXT);
                }
                catch (IllegalArgumentException ignored)
                {
                    pendingField = null;
                }
            }
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        // As the old screen did on every return: a group that dropped out of toxcore is brought back,
        // unless this device left it. A page of one value stays as it is.
        if (open == null && !groupId.isEmpty())
        {
            refresh(true);
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState)
    {
        super.onSaveInstanceState(outState);
        final Field kept = open != null ? open : pendingField;
        if (kept == null)
        {
            return;
        }
        outState.putString(STATE_FIELD, kept.name());
        // A password never goes into the saved state.
        if (kept != Field.PASSWORD)
        {
            final AirStepKit.LineField line = field;
            outState.putString(STATE_TEXT, open != null && line != null ? line.text() : pendingText);
        }
    }

    @Override
    protected void onDestroy()
    {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- reading ----

    private static Handler worker()
    {
        synchronized (WORKER_LOCK)
        {
            if (worker == null)
            {
                final HandlerThread thread = new HandlerThread("air-group-settings", Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }
    }

    /** Reads the group on the worker and shows it; {@code ensure} brings it back into toxcore first. */
    private void refresh(final boolean ensure)
    {
        final String id = groupId;
        final int read = ++reads;
        worker().post(() -> {
            final State state = read(id, ensure);
            main.post(() -> show(state, read));
        });
    }

    /** toxcore and the database, so never on the main thread. */
    private static State read(final String groupId, final boolean ensure)
    {
        final State s = new State();
        final Long found = ask("group", () -> ensure ? HelperGroup.ensure_group_in_tox(groupId)
                                                      : HelperGroup.tox_group_by_groupid__wrapper(groupId));
        final long num = found == null ? -1 : found;
        s.live = num >= 0;
        s.extended = Boolean.TRUE.equals(ask("extended", HelperGroup::is_tox_group_extended_state_jni_supported));
        String topic = null;
        if (s.live)
        {
            final Integer role = ask("role", () -> MainActivity.tox_group_self_get_role(num));
            s.selfRole = role != null && AirGroupModel.isKnownRole(role) ? role : -1;
            final String name = ask("name", () -> MainActivity.tox_group_self_get_name(num));
            s.selfName = isKnown(name) ? name : "";
            topic = ask("topic", () -> MainActivity.tox_group_get_topic(num));
            if (s.extended)
            {
                final Integer lock = ask("lock", () -> HelperGroup.tox_group_get_topic_lock__wrapper(num));
                s.topicLock = lock != null && isLock(lock) ? lock : -1;
            }
            final Integer privacy = ask("privacy", () -> MainActivity.tox_group_get_privacy_state(num));
            s.privacy = privacy != null && isPrivacy(privacy) ? privacy : -1;
            final Integer voice = ask("voice", () -> MainActivity.tox_group_get_voice_state(num));
            s.voice = voice != null && AirGroupSettingsModel.voiceChoice(voice) >= 0 ? voice : -1;
            final Integer limit = ask("limit", () -> HelperGroup.get_display_peer_limit(num));
            s.peerLimit = limit != null ? limit : HelperGroup.DEFAULT_GROUP_PEER_LIMIT;
            final Long peers = ask("peers", () -> MainActivity.tox_group_peer_count(num));
            s.peers = peers != null && peers >= 0 ? peers : -1;
        }
        // What toxcore does not give, the profile stored: the topic and who can join.
        final GroupDB row = ask("stored", () -> stored(groupId));
        if (!isKnown(topic) && row != null)
        {
            topic = row.topic;
        }
        s.topic = isKnown(topic) ? topic : "";
        if (s.privacy < 0 && row != null && isPrivacy(row.privacy_state))
        {
            s.privacy = row.privacy_state;
        }
        return s;
    }

    @Nullable
    private static GroupDB stored(final String groupId)
    {
        final List<GroupDB> rows = TrifaToxService.orma.selectFromGroupDB()
                                                       .group_identifierEq(groupId.toLowerCase(Locale.ROOT))
                                                       .toList();
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    /** One read; null when it threw (an older native library, no profile open yet). */
    @Nullable
    private static <T> T ask(final String what, final Query<T> query)
    {
        try
        {
            return query.get();
        }
        catch (Throwable e)
        {
            Log.i(TAG, "read:" + what + ":EE:" + e.getMessage());
            return null;
        }
    }

    /** The JNI bridge gives null without toxcore and "-1" for a text it could not read. */
    private static boolean isKnown(@Nullable final String text)
    {
        return text != null && !"-1".equals(text);
    }

    private static boolean isPrivacy(final int state)
    {
        return state == PRIVACY_PUBLIC || state == PRIVACY_PRIVATE;
    }

    private static boolean isLock(final int lock)
    {
        return lock == AirGroupSettingsModel.TOPIC_LOCK_ENABLED || lock == AirGroupSettingsModel.TOPIC_LOCK_DISABLED;
    }

    // ---- the list ----

    private void show(final State s, final int read)
    {
        if (destroyed || isFinishing() || read != reads)
        {
            return;
        }
        final boolean changed = !s.sameAs(shown);
        shown = s;
        if (open != null)
        {
            // The list is drawn again when the page of one value closes.
            return;
        }
        if (changed || column.getChildCount() == 0)
        {
            build(s);
        }
        final Field pending = pendingField;
        if (pending != null)
        {
            final String text = pendingText;
            pendingField = null;
            pendingText = null;
            if (opens(s, pending))
            {
                openField(pending, text);
            }
        }
    }

    /** Whether the row of {@code f} opens its page: the rights of the old screen, as toxcore checks them. */
    private static boolean opens(final State s, final Field f)
    {
        switch (f)
        {
            case SELF_NAME:
                return s.live;
            case TOPIC:
                return s.topicOpens();
            case PASSWORD:
                return s.founder() && s.extended;
            default:
                return s.founder();
        }
    }

    private void build(final State s)
    {
        column.removeAllViews();
        inviteOnlyRow = null;
        openRow = null;
        joinNote = null;
        voiceRows = null;
        writeNote = null;
        lockSwitch = null;
        lockNote = null;

        if (!s.live)
        {
            AirScreenKit.addSection(column, null, null, AirScreenKit.body(this, getString(R.string.air_gs_not_here)));
        }
        buildNames(s);
        buildJoin(s);
        buildWrite(s);
    }

    /** Your name in this group, then the topic, each with what it does. */
    private void buildNames(final State s)
    {
        // Under the title, not at the trailing side: a name of 128 bytes or a topic of 512 wraps there.
        final String nameTitle = getString(R.string.air_gs_self_name);
        final AirSettingRow name = new AirSettingRow(this, nameTitle, s.live).detail(s.selfName);
        name.setId(R.id.air_gs_self_name_row);
        if (s.live)
        {
            name.setOnClickListener(v -> openField(Field.SELF_NAME, null));
        }
        else
        {
            staticRow(name, nameTitle, s.selfName);
        }
        AirScreenKit.addSection(column, null, getString(R.string.air_gs_self_name_note), AirScreenKit.plateOf(this, name));

        final String topicTitle = getString(R.string.air_gs_topic);
        final String topicWords = s.topic.isEmpty() ? getString(R.string.air_gs_topic_none) : s.topic;
        final boolean topicOpens = s.topicOpens();
        final AirSettingRow topic = new AirSettingRow(this, topicTitle, topicOpens).detail(topicWords);
        topic.setId(R.id.air_gs_topic_row);
        if (topicOpens)
        {
            topic.setOnClickListener(v -> openField(Field.TOPIC, null));
        }
        else
        {
            staticRow(topic, topicTitle, topicWords);
        }
        AirScreenKit.addSection(column, null, getString(topicNote(s)), AirScreenKit.plateOf(this, topic));
    }

    @StringRes
    private static int topicNote(final State s)
    {
        if (!s.live || s.topicOpens())
        {
            return R.string.air_gs_topic_note;
        }
        // An observer never changes it; while the topic is not locked everyone else may.
        return s.topicLock == AirGroupSettingsModel.TOPIC_LOCK_ENABLED ? R.string.air_gs_topic_locked
                                                                        : R.string.air_gs_topic_not_observers;
    }

    /** Who can join; for the founder also the password and the member limit. */
    private void buildJoin(final State s)
    {
        final String heading = getString(R.string.air_gs_join);
        if (s.founder() && s.extended)
        {
            final LinearLayout inviteOnly = AirScreenKit.choiceRow(this, getString(R.string.air_group_privacy_invite_only),
                                                                   getString(R.string.air_group_privacy_invite_only_note));
            inviteOnly.setId(R.id.air_gs_invite_only_row);
            inviteOnly.setOnClickListener(v -> setPrivacy(PRIVACY_PRIVATE));
            final LinearLayout openToAll = AirScreenKit.choiceRow(this, getString(R.string.air_group_privacy_open),
                                                                  getString(R.string.air_group_privacy_open_note));
            openToAll.setId(R.id.air_gs_open_row);
            openToAll.setOnClickListener(v -> setPrivacy(PRIVACY_PUBLIC));
            inviteOnlyRow = inviteOnly;
            openRow = openToAll;
            joinNote = failureLine();
            AirScreenKit.addSection(column, heading, null, choices(inviteOnly, openToAll), joinNote);
            showPrivacy(s.privacy);
        }
        else
        {
            final AirSettingRow row = privacyRow(s.privacy);
            if (row != null)
            {
                AirScreenKit.addSection(column, heading, null, AirScreenKit.plateOf(this, row));
            }
        }
        if (!s.founder())
        {
            return;
        }

        AirSettingRow password = null;
        if (s.extended)
        {
            // toxcore gives no way to read the password back, so the row says nothing of it.
            password = new AirSettingRow(this, getString(R.string.air_gs_password), true);
            password.setId(R.id.air_gs_password_row);
            password.setOnClickListener(v -> openField(Field.PASSWORD, null));
        }
        final AirSettingRow limit = new AirSettingRow(this, getString(R.string.air_gs_limit), true);
        limit.setId(R.id.air_gs_limit_row);
        limit.setValue(NumberFormat.getIntegerInstance(AirUi.locale(this)).format(s.peerLimit));
        limit.setOnClickListener(v -> openField(Field.PEER_LIMIT, null));
        String note = s.extended ? getString(R.string.air_gs_password_note) : null;
        if (s.full())
        {
            final String full = getString(R.string.air_gs_limit_full);
            note = note == null ? full : note + " " + full;
        }
        AirScreenKit.addSection(column, null, note, AirScreenKit.plateOf(this, password, limit));
    }

    /** Who can write; for the founder also whether only the founder and the moderators change the topic. */
    private void buildWrite(final State s)
    {
        final String heading = getString(R.string.air_gs_write);
        if (!s.founder())
        {
            final AirSettingRow row = voiceRow(s.voice);
            if (row != null)
            {
                AirScreenKit.addSection(column, heading, getString(R.string.air_gs_founder_only),
                                        AirScreenKit.plateOf(this, row));
            }
            return;
        }
        final LinearLayout[] rows = new LinearLayout[VOICE_STATES.length];
        for (int i = 0; i < rows.length; i++)
        {
            final int state = VOICE_STATES[i];
            rows[i] = AirScreenKit.choiceRow(this, getString(VOICE_TITLES[i]),
                                             i == 0 ? getString(R.string.air_gs_voice_all_note) : null);
            rows[i].setId(VOICE_ROWS[i]);
            rows[i].setOnClickListener(v -> setVoice(state));
        }
        voiceRows = rows;
        writeNote = failureLine();
        AirScreenKit.addSection(column, heading, null, choices(rows), writeNote);
        showVoice(s.voice);

        // The old screen showed the switch only while toxcore answered for the lock.
        if (s.extended && isLock(s.topicLock))
        {
            final AirSettingRow lock = new AirSettingRow(this, getString(R.string.air_gs_topic_lock), false);
            lock.setId(R.id.air_gs_topic_lock_row);
            lockSwitch = lock.withSwitch(s.topicLock == AirGroupSettingsModel.TOPIC_LOCK_ENABLED,
                                         (button, checked) -> setTopicLock(checked));
            lockNote = failureLine();
            AirScreenKit.addSection(column, null, null, AirScreenKit.plateOf(this, lock), lockNote);
        }
    }

    /** Who can join as it is, for a member: the choice in words, with what it means. */
    @Nullable
    private AirSettingRow privacyRow(final int privacy)
    {
        final int title;
        final int detail;
        if (privacy == PRIVACY_PRIVATE)
        {
            title = R.string.air_group_privacy_invite_only;
            detail = R.string.air_group_privacy_invite_only_note;
        }
        else if (privacy == PRIVACY_PUBLIC)
        {
            title = R.string.air_group_privacy_open;
            detail = R.string.air_group_privacy_open_note;
        }
        else
        {
            return null;
        }
        final AirSettingRow row = new AirSettingRow(this, getString(title), false).detail(getString(detail));
        row.setId(R.id.air_gs_join_row);
        staticRow(row, getString(title), getString(detail));
        return row;
    }

    /** Who can write as it is, for a member. */
    @Nullable
    private AirSettingRow voiceRow(final int voice)
    {
        final int choice = AirGroupSettingsModel.voiceChoice(voice);
        if (choice < 0)
        {
            return null;
        }
        final String title = getString(VOICE_TITLES[choice]);
        final String detail = choice == 0 ? getString(R.string.air_gs_voice_all_note) : null;
        final AirSettingRow row = new AirSettingRow(this, title, false).detail(detail);
        row.setId(R.id.air_gs_voice_row);
        staticRow(row, title, detail);
        return row;
    }

    /** A row that only says how a setting is: no button to TalkBack, its words read as one. */
    private static void staticRow(final AirSettingRow row, final CharSequence title, @Nullable final CharSequence words)
    {
        row.setClickable(false);
        row.setFocusable(false);
        ViewCompat.setAccessibilityDelegate(row, null);
        ViewCompat.setScreenReaderFocusable(row, true);
        row.setContentDescription(TextUtils.isEmpty(words) ? title : title + ". " + words);
    }

    /** Choice rows on one plate with a hairline between them, as on New group. */
    private LinearLayout choices(final LinearLayout... rows)
    {
        final LinearLayout plate = AirScreenKit.plate(this);
        for (int i = 0; i < rows.length; i++)
        {
            if (i > 0)
            {
                plate.addView(rule());
            }
            plate.addView(rows[i], AirScreenKit.matchWidth());
        }
        return plate;
    }

    private View rule()
    {
        final View rule = new View(this);
        rule.setBackgroundColor(AirUi.color(this, R.color.kq_line));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, getResources().getDimensionPixelSize(R.dimen.kq_stroke_hairline));
        params.setMarginStart(getResources().getDimensionPixelSize(R.dimen.kq_space_16));
        rule.setLayoutParams(params);
        return rule;
    }

    /** E21: the line a failure stands on under its section, hidden until there is one. */
    private TextView failureLine()
    {
        final TextView line = AirScreenKit.note(this, "");
        line.setVisibility(View.GONE);
        return line;
    }

    private void showPrivacy(final int privacy)
    {
        if (inviteOnlyRow != null && openRow != null)
        {
            AirScreenKit.applyChoice(inviteOnlyRow, privacy == PRIVACY_PRIVATE);
            AirScreenKit.applyChoice(openRow, privacy == PRIVACY_PUBLIC);
        }
    }

    private void showVoice(final int voice)
    {
        final LinearLayout[] rows = voiceRows;
        if (rows == null)
        {
            return;
        }
        final int choice = AirGroupSettingsModel.voiceChoice(voice);
        for (int i = 0; i < rows.length; i++)
        {
            AirScreenKit.applyChoice(rows[i], i == choice);
        }
    }

    // ---- changes on the list ----

    private void setPrivacy(final int privacy)
    {
        final State s = shown;
        if (busy || s == null || s.privacy == privacy)
        {
            return;
        }
        final String id = groupId;
        change(() -> writePrivacy(id, privacy), () -> {
            final State now = shown;
            if (now != null)
            {
                now.privacy = privacy;
            }
            showPrivacy(privacy);
            endLine(joinNote);
            toast(R.string.air_gs_saved);
        }, code -> sayFailure(joinNote, code));
    }

    private void setVoice(final int voice)
    {
        final State s = shown;
        if (busy || s == null || s.voice == voice)
        {
            return;
        }
        final String id = groupId;
        change(() -> writeVoice(id, voice), () -> {
            final State now = shown;
            if (now != null)
            {
                now.voice = voice;
            }
            showVoice(voice);
            endLine(writeNote);
            toast(R.string.air_gs_saved);
        }, code -> sayFailure(writeNote, code));
    }

    private void setTopicLock(final boolean on)
    {
        if (lockQuiet)
        {
            return;
        }
        if (busy || shown == null)
        {
            setLockQuietly(!on);
            return;
        }
        final int lock = on ? AirGroupSettingsModel.TOPIC_LOCK_ENABLED : AirGroupSettingsModel.TOPIC_LOCK_DISABLED;
        final String id = groupId;
        change(() -> writeTopicLock(id, lock), () -> {
            final State now = shown;
            if (now != null)
            {
                now.topicLock = lock;
            }
            endLine(lockNote);
            toast(R.string.air_gs_saved);
        }, code -> {
            // The switch goes back to what the group keeps.
            setLockQuietly(!on);
            sayFailure(lockNote, code);
        });
    }

    private void setLockQuietly(final boolean on)
    {
        final SwitchCompat view = lockSwitch;
        if (view == null)
        {
            return;
        }
        lockQuiet = true;
        view.setChecked(on);
        lockQuiet = false;
    }

    private void sayFailure(@Nullable final TextView line, @Nullable final String code)
    {
        if (line != null)
        {
            AirUi.sayOnLine(line, getString(R.string.air_group_setting_not_saved), true, code);
        }
    }

    private static void endLine(@Nullable final TextView line)
    {
        if (line != null && line.getVisibility() != View.GONE)
        {
            AirUi.endLine(line);
        }
    }

    /** Runs {@code write} on the worker; one change at a time, its outcome back on the main thread. */
    private void change(final Write write, final Runnable done, final Failure failed)
    {
        busy = true;
        setSaveButtonEnabled(false);
        worker().post(() -> {
            Outcome outcome;
            try
            {
                outcome = write.run();
            }
            catch (Throwable e)
            {
                Log.i(TAG, "change:EE:" + e.getMessage());
                outcome = Outcome.failed(null);
            }
            final Outcome result = outcome;
            main.post(() -> {
                busy = false;
                setSaveButtonEnabled(true);
                if (destroyed || isFinishing())
                {
                    return;
                }
                if (result.done)
                {
                    done.run();
                }
                else
                {
                    failed.say(result.code);
                }
            });
        });
    }

    // ---- the page of one value ----

    private void openField(final Field f, @Nullable final String restored)
    {
        final State s = shown;
        if (busy || s == null || open != null || !opens(s, f))
        {
            return;
        }
        open = f;
        fieldBack.setEnabled(true);
        setBarTitle(f.title);
        column.removeAllViews();

        final AirStepKit.LineField line = new AirStepKit.LineField(this, getString(f.title), f == Field.PASSWORD);
        final EditText edit = line.edit;
        edit.setId(R.id.air_gs_field);
        // A group's name, topic or password is no account: password managers leave these alone, and what
        // is typed goes into the saved state only through onSaveInstanceState, never a password.
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        edit.setSaveEnabled(false);
        switch (f)
        {
            case SELF_NAME:
                // A name is not prose: no spell-check underlines and no suggestions.
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME
                                  | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                line.limitBytes(ToxVars.TOX_GROUP_MAX_PART_LENGTH);
                wrap(edit);
                break;
            case TOPIC:
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                line.limitBytes(ToxVars.TOX_GROUP_MAX_TOPIC_LENGTH);
                wrap(edit);
                break;
            case PASSWORD:
                line.limitBytes(ToxVars.TOX_GROUP_MAX_PASSWORD_SIZE);
                break;
            default:
                edit.setInputType(InputType.TYPE_CLASS_NUMBER);
                edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AirGroupSettingsModel.PEER_LIMIT_MAX_CHARS)});
                break;
        }
        // The password cannot be read back from toxcore, so its field starts empty.
        final String value = f == Field.PASSWORD ? "" : (restored != null ? restored : initialValue(s, f));
        edit.setText(value);
        edit.setSelection(edit.length());

        final CharSequence hint = hint(f);
        final TextView note = AirStepKit.fieldNote(this, hint);
        note.setId(R.id.air_gs_field_note);
        noteSays = false;
        edit.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(final CharSequence text, final int start, final int count, final int after)
            {
            }

            @Override
            public void onTextChanged(final CharSequence text, final int start, final int before, final int count)
            {
            }

            @Override
            public void afterTextChanged(final Editable text)
            {
                if (noteSays)
                {
                    noteSays = false;
                    AirStepKit.showHint(note, hint);
                }
            }
        });
        AirStepKit.chain(edit, null, this::saveField);

        TextView remove = null;
        if (f == Field.PASSWORD)
        {
            remove = AirStepKit.quietButton(this, getString(R.string.air_gs_password_remove));
            remove.setId(R.id.air_gs_password_remove);
            remove.setOnClickListener(v -> removePassword());
        }
        AirScreenKit.addSection(column, null, null, line, note, remove == null ? null : AirScreenKit.leading(remove));

        field = line;
        fieldNote = note;
        saveBar = new AirStepKit.MainBar(this, getString(R.string.air_gs_save), this::saveField);
        saveBar.button.setId(R.id.air_gs_save);
        scrollToTop();
        AirStepKit.showKeyboard(edit);
    }

    private void closeField()
    {
        final Field f = open;
        if (f == null)
        {
            return;
        }
        AirStepKit.hideKeyboard(this, column);
        open = null;
        field = null;
        fieldNote = null;
        saveBar = null;
        noteSays = false;
        fieldBack.setEnabled(false);
        final LinearLayout bottom = findViewById(R.id.air_screen_bottom);
        bottom.removeAllViews();
        bottom.setVisibility(View.GONE);
        setBarTitle(R.string.air_group_settings);
        scrollToTop();
        final State s = shown;
        if (s != null)
        {
            build(s);
        }
        else
        {
            column.removeAllViews();
        }
        // TalkBack comes back to the row the page was opened from.
        final View row = column.findViewById(f.row);
        if (row != null)
        {
            row.post(() -> row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        }
    }

    private static String initialValue(final State s, final Field f)
    {
        switch (f)
        {
            case SELF_NAME:
                return s.selfName;
            case TOPIC:
                return s.topic;
            case PEER_LIMIT:
                // Digits as iOS puts them in the field; any script's digits are read back.
                return String.valueOf(s.peerLimit);
            default:
                return "";
        }
    }

    /** What the value does, under its field until something is refused or fails. */
    private CharSequence hint(final Field f)
    {
        switch (f)
        {
            case SELF_NAME:
                return getString(R.string.air_gs_self_name_note);
            case TOPIC:
                return getString(R.string.air_gs_topic_note);
            case PASSWORD:
                return getString(R.string.air_gs_password_note);
            default:
                return limitRange();
        }
    }

    private String limitRange()
    {
        return AirUi.localDigits(this, getString(R.string.air_gs_limit_range));
    }

    private void saveField()
    {
        final Field f = open;
        final AirStepKit.LineField line = field;
        if (busy || f == null || line == null)
        {
            return;
        }
        final String text = line.text();
        final String id = groupId;
        switch (f)
        {
            case SELF_NAME:
            {
                final String name = AirGroupModel.cleanName(text);
                if (name.isEmpty())
                {
                    refuse(getString(R.string.air_gs_name_empty));
                    return;
                }
                final String sent = HelperGroup.sanitize_group_peer_name(name);
                change(() -> writeSelfName(id, sent), () -> fieldSaved(s -> s.selfName = sent, R.string.air_gs_saved),
                       this::fieldFailed);
                break;
            }
            case TOPIC:
            {
                // The old screen never cleared the topic: an empty one is not sent.
                final String topic = AirGroupModel.cleanName(text);
                if (topic.isEmpty())
                {
                    refuse(getString(R.string.air_gs_topic_empty));
                    return;
                }
                final String sent = HelperGroup.sanitize_group_topic(topic);
                change(() -> writeTopic(id, sent), () -> fieldSaved(s -> s.topic = sent, R.string.air_gs_saved),
                       this::fieldFailed);
                break;
            }
            case PASSWORD:
            {
                // An empty field removes the password, as on the old screen.
                final String password = text.isEmpty() ? null : text;
                change(() -> writePassword(id, password),
                       () -> fieldSaved(null, password == null ? R.string.air_gs_password_removed : R.string.air_gs_saved),
                       this::fieldFailed);
                break;
            }
            default:
            {
                final int limit = AirGroupSettingsModel.peerLimit(text);
                if (limit == 0)
                {
                    refuse(limitRange());
                    return;
                }
                change(() -> writePeerLimit(id, limit), () -> fieldSaved(s -> s.peerLimit = limit, R.string.air_gs_saved),
                       this::fieldFailed);
                break;
            }
        }
    }

    private void removePassword()
    {
        final AirStepKit.LineField line = field;
        if (busy || line == null)
        {
            return;
        }
        line.edit.setText("");
        saveField();
    }

    /** The value went through: back to the list with it, the toast, then what toxcore keeps now. */
    private void fieldSaved(@Nullable final Update update, @StringRes final int confirmation)
    {
        final State s = shown;
        if (s != null && update != null)
        {
            update.apply(s);
        }
        closeField();
        toast(confirmation);
        refresh(false);
    }

    private void fieldFailed(@Nullable final String code)
    {
        final TextView note = fieldNote;
        if (note == null)
        {
            return;
        }
        noteSays = true;
        AirUi.sayOnLine(note, getString(R.string.air_group_setting_not_saved), true, code);
    }

    /** E21: a rule the value breaks, in place of what it does, with one shake of the field. */
    private void refuse(final CharSequence words)
    {
        final TextView note = fieldNote;
        final AirStepKit.LineField line = field;
        if (note == null || line == null)
        {
            return;
        }
        noteSays = true;
        AirUi.sayOnLine(note, words, false);
        AirStepKit.shakeOnce(line);
    }

    private void setSaveButtonEnabled(final boolean enabled)
    {
        final AirStepKit.MainBar bar = saveBar;
        if (bar != null)
        {
            bar.button.setEnabled(enabled);
        }
    }

    private void setBarTitle(@StringRes final int title)
    {
        final ActionBar bar = getSupportActionBar();
        if (bar != null)
        {
            bar.setTitle(title);
        }
    }

    private void scrollToTop()
    {
        final ScrollView scroll = findViewById(R.id.air_screen_scroll);
        if (scroll != null)
        {
            scroll.scrollTo(0, 0);
        }
    }

    /** One paragraph that wraps instead of scrolling sideways, so a long name or topic stays in view. */
    private static void wrap(final EditText edit)
    {
        edit.setHorizontallyScrolling(false);
        edit.setMaxLines(FIELD_MAX_LINES);
    }

    // ---- the writes, on the worker: the old screen's calls and database writes ----

    private static Outcome writeSelfName(final String groupId, final String name)
    {
        return call(groupId, null, "self name", num -> MainActivity.tox_group_self_set_name(num, name), null);
    }

    private static Outcome writeTopic(final String groupId, final String topic)
    {
        // Checks it, sets it and stores it as the old screen did: the database, the lists and the save.
        if (!HelperGroup.save_group_title_if_changed(groupId, topic))
        {
            Log.i(TAG, "topic: not saved");
            return Outcome.failed(null);
        }
        AirRepository.requestRefresh();
        return Outcome.DONE;
    }

    private static Outcome writePassword(final String groupId, @Nullable final String password)
    {
        return call(groupId, AirErrorCode.Call.GROUP_FOUNDER_SET_PASSWORD, password == null ? "password removed" : "password",
                    num -> HelperGroup.tox_group_founder_set_password__wrapper(num, password), null);
    }

    private static Outcome writePeerLimit(final String groupId, final int limit)
    {
        return call(groupId, AirErrorCode.Call.GROUP_FOUNDER_SET_PEER_LIMIT, "peer limit " + limit,
                    num -> MainActivity.tox_group_founder_set_peer_limit(num, limit), null);
    }

    private static Outcome writePrivacy(final String groupId, final int privacy)
    {
        return call(groupId, AirErrorCode.Call.GROUP_FOUNDER_SET_PRIVACY_STATE, "privacy " + privacy,
                    num -> HelperGroup.tox_group_founder_set_privacy_state__wrapper(num, privacy),
                    () -> HelperGroup.update_group_in_db_privacy_state(groupId, privacy));
    }

    private static Outcome writeVoice(final String groupId, final int voice)
    {
        return call(groupId, AirErrorCode.Call.GROUP_FOUNDER_SET_VOICE_STATE, "voice " + voice,
                    num -> MainActivity.tox_group_founder_set_voice_state(num, voice), null);
    }

    private static Outcome writeTopicLock(final String groupId, final int lock)
    {
        return call(groupId, AirErrorCode.Call.GROUP_FOUNDER_SET_TOPIC_LOCK, "topic lock " + lock,
                    num -> HelperGroup.tox_group_founder_set_topic_lock__wrapper(num, lock), null);
    }

    /**
     * One toxcore call on the group as toxcore has it now. When it goes through, {@code stored} writes what
     * the profile keeps besides the save, and the save follows; when it fails, the code is the one iOS gives
     * for the same failure ({@code code} null: a call iOS gives no code for here).
     */
    private static Outcome call(final String groupId, @Nullable final AirErrorCode.Call code, final String what,
                                final ToxCall toxCall, @Nullable final Runnable stored)
    {
        final long num = groupNumber(groupId);
        if (num < 0)
        {
            Log.i(TAG, what + ": the group is not in toxcore");
            return Outcome.failed(code == null ? null : AirErrorCode.tox(code, AirErrorCode.TOX_GROUP_NOT_FOUND));
        }
        TrifaToxService.wakeup_tox_thread();
        final int result = toxCall.run(num);
        Log.i(TAG, what + ": result=" + result);
        if (result < 0)
        {
            return Outcome.failed(code == null ? null : AirErrorCode.toxResult(code, result));
        }
        if (stored != null)
        {
            stored.run();
        }
        HelperGeneric.update_savedata_file_wrapper_async();
        return Outcome.DONE;
    }

    private static long groupNumber(final String groupId)
    {
        final Long num = ask("number", () -> HelperGroup.tox_group_by_groupid__wrapper(groupId));
        return num == null ? -1 : num;
    }
}

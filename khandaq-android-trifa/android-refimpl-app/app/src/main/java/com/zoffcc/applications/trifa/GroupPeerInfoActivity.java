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

import org.khandaq.messenger.R;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.ViewCompat;

import static com.zoffcc.applications.trifa.HelperFriend.lookup_friendlist_by_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.group_notification_silent_peer_get;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_name__wrapper;

/**
 * KHANDAQ (redesign, stage 5): a member of a group, in the frame of the Air screens. Who they are: the
 * name, then the role, presence and mute in one line, as their row in the group dossier and
 * GroupMemberStatusFormatter on iOS say it; a private message; what may be done with them
 * (AirGroupMemberChoices.screen: the direct chat or adding them, muting them in this group, the changes
 * of role and removing them, only those this phone's role allows); how this phone reaches them while
 * they are online; their key in this group with Copy. Opened from the member's sheet ("Key and link"),
 * from an author or a mention in the group conversation, and from the old member drawer. The screen
 * follows the member while it is open; another member's role shows only while the group is connected
 * (AirGroupModel.shownRole), as in the dossier.
 */
public class GroupPeerInfoActivity extends AirScreenActivity
{
    static final String TAG = "trifa.GrpPeerInfoActy";
    /** How often the open screen reads the member again: presence and roles have no broadcast here. */
    private static final long POLL_MS = 3000L;
    private static final Object WORKER_LOCK = new Object();
    private static Handler worker = null;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable()
    {
        @Override
        public void run()
        {
            refresh();
            main.postDelayed(this, POLL_MS);
        }
    };

    String peer_pubkey = null;
    String group_id = null;
    private boolean destroyed = false;
    @Nullable
    private State shown = null;
    /** How many times the mute switch was turned here: a read begun before the last turn does not undo it. */
    private int muteTurns = 0;
    /** The rows the actions plate shows now, so an unchanged read leaves them, and TalkBack, alone. */
    private String actionsShown = null;

    /** The member's name as this device last had it, empty for none. */
    private String knownName = "";
    private TextView name;
    private TextView status;
    private View messageSection;
    private TextView messageHeading;
    private EditText messageField;
    private TextView send;
    /** Whether the field and Send are on now; null before the first read. */
    @Nullable
    private Boolean writesShown = null;
    private View actionsSection;
    private LinearLayout actionsPlate;
    /** E21: what an action on the member could not do (a role, a removal, adding them), under the actions. */
    private TextView actionNote;
    @Nullable
    private SwitchCompat muteSwitch;
    private View linkSection;
    private TextView linkText;

    /** What the screen shows, read off the main thread. */
    private static final class State
    {
        /** As the dossier's row has them: the role only where a row shows one. */
        final AirGroupModel.Member member;
        final boolean friend;
        /** Your role, -1 while toxcore does not say. */
        final int selfRole;
        /** False where you are an observer: toxcore takes no private message from you (AirGroupModel). */
        final boolean writesPrivately;
        /** toxcore's link to the member while they are online (TOX_CONNECTION), NONE otherwise. */
        final int link;
        final boolean muted;
        /** muteTurns when the read began. */
        final int muteTurns;

        State(final AirGroupModel.Member member, final boolean friend, final int selfRole,
              final boolean writesPrivately, final int link, final boolean muted, final int muteTurns)
        {
            this.member = member;
            this.friend = friend;
            this.selfRole = selfRole;
            this.writesPrivately = writesPrivately;
            this.link = link;
            this.muted = muted;
            this.muteTurns = muteTurns;
        }
    }

    @Override
    protected int screenTitle()
    {
        return R.string.air_group_member_title;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        final Intent intent = getIntent();
        peer_pubkey = intent.getStringExtra("peer_pubkey");
        group_id = intent.getStringExtra("group_id");
        if (!GroupMessageListActivity.is_valid_group_id(group_id) || TextUtils.isEmpty(peer_pubkey))
        {
            finish();
            return;
        }
        build();
        // The first frame has the name this device knows now; the first read brings the rest.
        String known = null;
        try
        {
            known = tox_group_peer_get_name__wrapper(group_id, peer_pubkey);
        }
        catch (Exception e)
        {
            Log.i(TAG, "onCreate:name:EE:" + e.getMessage());
        }
        setNames(usableName(known), false);
        // KHANDAQ (stage 5): words typed on an earlier visit, or in the member's sheet, are still here;
        // only Send sends them.
        final String draft = GroupMemberActions.privateDraft(group_id, peer_pubkey);
        if (draft != null && messageField.length() == 0)
        {
            messageField.setText(draft);
            messageField.setSelection(messageField.length());
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        // At once, then on the dossier's beat: back from the direct chat, a new contact shows as one.
        main.removeCallbacks(poll);
        main.post(poll);
    }

    @Override
    protected void onPause()
    {
        main.removeCallbacks(poll);
        // KHANDAQ (stage 5): leaving the screen never sends. It used to send whatever was typed and clear
        // the field even when that send failed, so words went without a tap or were lost. Now they stay as
        // a draft for the next visit, and only Send sends them.
        if (messageField != null)
        {
            GroupMemberActions.keepPrivateDraft(group_id, peer_pubkey, messageField.getText());
        }
        super.onPause();
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
                final HandlerThread thread = new HandlerThread("air-group-member", Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }
    }

    /** Reads the member on the worker and shows them. */
    private void refresh()
    {
        final String id = group_id;
        final String key = peer_pubkey;
        if (destroyed || id == null || key == null)
        {
            return;
        }
        final int turns = muteTurns;
        worker().post(() -> {
            final State state = read(id, key, turns);
            main.post(() -> show(state));
        });
    }

    /** toxcore and the database, so never on the main thread. */
    private static State read(final String groupId, final String peerKey, final int muteTurns)
    {
        // The dossier's own reading, so the role, presence and name are the ones the member's row shows.
        AirGroupModel.Member member = null;
        // Your right as the group's own bar has it, the stand-in of a debug build included.
        boolean writesPrivately = true;
        try
        {
            final AirGroupPanel.Snapshot group = AirGroupPanel.read(groupId);
            writesPrivately = group.right.writesPrivately();
            for (final AirGroupModel.Member candidate : group.members)
            {
                if (candidate.publicKey.equalsIgnoreCase(peerKey))
                {
                    member = candidate;
                    break;
                }
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "read:group:EE:" + e.getMessage());
        }

        final long groupNum = tox_group_by_groupid__wrapper(groupId);
        int selfRole = -1;
        boolean self = member != null && member.self;
        int link = ToxVars.TOX_CONNECTION.TOX_CONNECTION_NONE.value;
        if (groupNum >= 0)
        {
            try
            {
                selfRole = MainActivity.tox_group_self_get_role(groupNum);
                final String selfKey = MainActivity.tox_group_self_get_public_key(groupNum);
                self = self || (selfKey != null && selfKey.equalsIgnoreCase(peerKey));
                if (!self && member != null && member.online)
                {
                    final long peerId = MainActivity.tox_group_peer_by_public_key(groupNum, peerKey);
                    if (peerId >= 0)
                    {
                        link = MainActivity.tox_group_peer_get_connection_status(groupNum, peerId);
                    }
                }
            }
            catch (Throwable e)
            {
                Log.i(TAG, "read:tox:EE:" + e.getMessage());
            }
        }

        if (member == null || member.self != self || member.name.isEmpty())
        {
            // Not among the members this device has now (they left, or the group is not in toxcore), or
            // without a name there: the name this device knows, not online unless the row said so, and a
            // role only by the rule of the rows.
            String known = member == null ? null : member.name;
            if (TextUtils.isEmpty(known))
            {
                try
                {
                    known = tox_group_peer_get_name__wrapper(groupId, peerKey);
                }
                catch (Throwable e)
                {
                    Log.i(TAG, "read:name:EE:" + e.getMessage());
                }
            }
            member = new AirGroupModel.Member(peerKey, usableName(known),
                                              AirGroupPanel.roleOf(groupId, groupNum, peerKey),
                                              member != null && member.online, member == null ? 0L : member.lastSeenMs,
                                              self);
        }

        boolean friend = false;
        boolean muted = false;
        try
        {
            friend = lookup_friendlist_by_pubkey(peerKey) != null;
            muted = group_notification_silent_peer_get(groupId, peerKey);
        }
        catch (Throwable e)
        {
            Log.i(TAG, "read:db:EE:" + e.getMessage());
        }
        return new State(member, friend, selfRole, writesPrivately, link, muted, muteTurns);
    }

    /** A name toxcore or the database gave; empty for none ("-1" is the old code's none). */
    private static String usableName(@Nullable final String raw)
    {
        return raw == null || raw.isEmpty() || "-1".equals(raw) ? "" : raw;
    }

    // ---- drawing ----

    /** The screen's parts, once; {@link #show} fills them in on every read. */
    private void build()
    {
        column.removeAllViews();
        final Resources res = getResources();

        // Who they are: the name, then the role, presence and mute in one line.
        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        name = AirScreenKit.text(this, R.style.TextAppearance_KQ_Display, R.color.kq_ink);
        ViewCompat.setAccessibilityHeading(name, true);
        head.addView(name, AirScreenKit.matchWidth());
        status = AirScreenKit.text(this, R.style.TextAppearance_KQ_Air_LabelRegular, R.color.kq_ink_2);
        status.setFontFeatureSettings("tnum");
        final LinearLayout.LayoutParams statusParams = AirScreenKit.matchWidth();
        statusParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_4);
        head.addView(status, statusParams);
        AirScreenKit.addSection(column, null, null, head);

        // A private message: only Send sends it, and what came of it stands under the field (E21). An
        // observer reads what a moderator writes to them in the group, but the field and Send are off.
        messageHeading = AirScreenKit.heading(this, "");
        messageField = GroupMemberActions.privateMessageField(this);
        messageField.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after)
            {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count)
            {
            }

            @Override
            public void afterTextChanged(Editable s)
            {
                // The words under the field were about the text before this change.
                AirUi.endUnder(messageField);
            }
        });
        send = AirScreenKit.button(this, getString(R.string.group_member_action_send), true);
        send.setOnClickListener(v -> sendTyped());
        messageSection = AirScreenKit.addSection(column, null, null, messageHeading, messageField,
                                                 AirScreenKit.leading(send));

        // What may be done with them; a refusal stands under the rows.
        actionsPlate = AirScreenKit.plate(this);
        actionNote = AirScreenKit.note(this, "");
        actionNote.setVisibility(View.GONE);
        actionsSection = AirScreenKit.addSection(column, null, null, actionsPlate, actionNote);
        actionsSection.setVisibility(View.GONE);

        // How this phone reaches them, while they are online.
        linkText = AirScreenKit.body(this, "");
        linkSection = AirScreenKit.addSection(column, getString(R.string.air_dossier_link_title), null, linkText);
        linkSection.setVisibility(View.GONE);

        // Their key in this group, in groups of four, with Copy.
        final String key = peer_pubkey.toUpperCase(Locale.ROOT);
        final View copy = AirScreenKit.iconButton(this, getString(R.string.air_dossier_copy_key), R.drawable.kq_ic_copy);
        copy.setOnClickListener(v -> copyKey(key));
        AirScreenKit.addSection(column, getString(R.string.air_dossier_key_title), null,
                                AirScreenKit.keyText(this, key), AirScreenKit.leading(copy));
    }

    private void show(final State s)
    {
        if (destroyed || isFinishing())
        {
            return;
        }
        shown = s;
        final AirGroupModel.Member member = s.member;
        setNames(member.name, member.self);

        // The mute turned here last stands until a read begun after it says the same.
        final boolean muted = (s.muteTurns == muteTurns || muteSwitch == null) ? s.muted : muteSwitch.isChecked();
        String line = AirGroupPanel.statusLine(this, member, System.currentTimeMillis());
        if (muted && !member.self)
        {
            line = getString(R.string.air_group_member_status_format, line, getString(R.string.air_group_member_muted));
        }
        setText(status, line);

        messageSection.setVisibility(member.self ? View.GONE : View.VISIBLE);
        showWritesPrivately(s.writesPrivately);

        final List<AirGroupMemberChoices.Choice> rows = AirGroupMemberChoices.screen(member.self, s.friend,
                                                                                     member.role, s.selfRole);
        final String next = rows.toString();
        if (!next.equals(actionsShown))
        {
            actionsShown = next;
            fillActions(rows, muted);
        }
        else if (muteSwitch != null && s.muteTurns == muteTurns && muteSwitch.isChecked() != s.muted)
        {
            // Muted or unmuted elsewhere meanwhile: the switch follows without acting again.
            muteSwitch.setOnCheckedChangeListener(null);
            muteSwitch.setChecked(s.muted);
            muteSwitch.setOnCheckedChangeListener(this::onMuteTurned);
        }
        actionsSection.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);

        final int linkWords;
        if (s.link == ToxVars.TOX_CONNECTION.TOX_CONNECTION_UDP.value)
        {
            linkWords = R.string.air_dossier_link_direct;
        }
        else if (s.link == ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP.value)
        {
            linkWords = R.string.air_dossier_link_relay;
        }
        else
        {
            linkWords = 0;
        }
        linkSection.setVisibility(linkWords == 0 ? View.GONE : View.VISIBLE);
        if (linkWords != 0)
        {
            setText(linkText, getString(linkWords));
        }
    }

    /** The name as the screen's heading, yours with "(you)", and in the private message's heading. */
    private void setNames(final String known, final boolean self)
    {
        knownName = known == null ? "" : known;
        final String who = displayName();
        setText(name, self ? getString(R.string.air_group_member_you_format, who) : who);
        setText(messageHeading, getString(R.string.air_group_private_to_format, who));
    }

    /** The member's name for the questions and the headings; "Unknown" while this device has none. */
    private String displayName()
    {
        return knownName.isEmpty() ? getString(R.string.group_peer_unknown) : knownName;
    }

    /** The rows of the choices, built again only when they change. */
    private void fillActions(final List<AirGroupMemberChoices.Choice> rows, final boolean muted)
    {
        actionsPlate.removeAllViews();
        muteSwitch = null;
        for (final AirGroupMemberChoices.Choice choice : rows)
        {
            final AirSettingRow row = new AirSettingRow(this, getString(GroupMemberActions.choiceLabel(choice)),
                                                        choice == AirGroupMemberChoices.Choice.OPEN_CHAT);
            final AirGroupModel.MemberAction action = AirGroupMemberChoices.action(choice);
            if (choice == AirGroupMemberChoices.Choice.OPEN_CHAT)
            {
                row.setOnClickListener(v -> FriendListHolder.show_messagelist_acticvity_for_friend(this, peer_pubkey));
            }
            else if (choice == AirGroupMemberChoices.Choice.ADD_FRIEND)
            {
                row.setOnClickListener(v -> {
                    AirUi.endLine(actionNote);
                    GroupMemberActions.addFriend(this, peer_pubkey);
                });
            }
            else if (choice == AirGroupMemberChoices.Choice.MUTE)
            {
                muteSwitch = row.withSwitch(muted, this::onMuteTurned);
            }
            else if (action != null)
            {
                if (action == AirGroupModel.MemberAction.KICK)
                {
                    row.danger();
                }
                row.setOnClickListener(v -> {
                    // A new choice: what the last one could not do is no longer the news.
                    AirUi.endLine(actionNote);
                    final State s = shown;
                    GroupMemberActions.confirmMemberAction(this, group_id, peer_pubkey, displayName(), action,
                                                           s == null ? AirGroupModel.ROLE_UNKNOWN : s.member.role,
                                                           this::refresh);
                });
            }
            else
            {
                continue;
            }
            actionsPlate.addView(row, AirScreenKit.matchWidth());
        }
    }

    private void onMuteTurned(final CompoundButton button, final boolean checked)
    {
        muteTurns++;
        GroupMemberActions.setMemberMuted(group_id, peer_pubkey, checked);
        refresh();
    }

    /**
     * E21: what an action on this member could not do, under the actions, a failure in red with Details
     * for its code and a rule in the second ink. False when this screen is going or shows no actions, so
     * the caller says it elsewhere.
     */
    boolean sayAtActions(final CharSequence text, final boolean failed, @Nullable final String code)
    {
        final TextView note = actionNote;
        if (note == null || destroyed || isFinishing() || TextUtils.isEmpty(text)
            || actionsSection.getVisibility() != View.VISIBLE)
        {
            return false;
        }
        AirUi.sayOnLine(note, text, failed, code);
        note.post(() -> note.requestRectangleOnScreen(new android.graphics.Rect(0, 0, note.getWidth(),
                                                                                note.getHeight()), false));
        return true;
    }

    // ---- actions ----

    /**
     * The field and Send, on or off as your role allows, changed only when that changes: off, the reason
     * stands under the field (GroupMemberActions.cannotWritePrivately); on again, it goes.
     */
    private void showWritesPrivately(final boolean writes)
    {
        if (writesShown != null && writesShown == writes)
        {
            return;
        }
        writesShown = writes;
        if (writes)
        {
            messageField.setEnabled(true);
            send.setEnabled(true);
            AirUi.endUnder(messageField);
        }
        else
        {
            GroupMemberActions.cannotWritePrivately(messageField, send);
        }
    }

    /** Send: the words go as they stand; a failure stays under the field with the words in it (E21). */
    private void sendTyped()
    {
        final String text = messageField.getText().toString();
        if (text.trim().isEmpty())
        {
            return;
        }
        if (!AirGroupModel.writesPrivately(AirGroupPanel.selfRole(group_id)))
        {
            // Made an observer since the last read: the words stay, nothing tries to go.
            showWritesPrivately(false);
            return;
        }
        if (GroupMemberActions.sendPrivateGroupMessage(group_id, peer_pubkey, text))
        {
            GroupMemberActions.keepPrivateDraft(group_id, peer_pubkey, null);
            messageField.setText("");
            AirUi.sayUnder(messageField, getString(R.string.group_member_action_private_msg_sent), false);
        }
        else
        {
            AirUi.sayUnder(messageField, getString(R.string.air_group_private_not_sent), true);
        }
    }

    private void copyKey(final String key)
    {
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null)
        {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.air_dossier_key_title), key));
        // From Android 13 the system shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        {
            toast(R.string.air_dossier_key_copied);
        }
        else
        {
            column.announceForAccessibility(getString(R.string.air_dossier_key_copied));
        }
    }

    /** Sets a text only when it differs, so an unchanged read neither lays out nor speaks. */
    private static void setText(final TextView view, final CharSequence text)
    {
        if (view != null && !TextUtils.equals(view.getText(), text))
        {
            view.setText(text);
        }
    }
}

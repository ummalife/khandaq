package com.zoffcc.applications.trifa;

import android.app.Activity;
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
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.TextViewCompat;

import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, stage 3b, E14): the group dossier, as AirGroupDossierController on iOS: a screen
 * opened from the group's title, not the folding panel of a person, since the members need the height
 * (design/DESIGN.md, "Groups on the phone"). In order: the name and the counts; your role; who gets a
 * message now, the members by role and online first, each with role and presence, with a search from 20
 * members; the group key in groups of four with Copy, Invite, the QR code and who can join;
 * notifications; the history (files, service messages, clearing it from this device); the group
 * settings; Leave the group with its consequence. The screen follows the group while it is open.
 *
 * <p>Files and Leave are answered by the conversation under the dossier, through the result
 * ({@link #EXTRA_ACTION}).
 */
public final class AirGroupDossierActivity extends AirScreenActivity
{
    static final int REQUEST_CODE = 0x4B51;
    static final String EXTRA_GROUP_ID = "group_id";
    static final String EXTRA_ACTION = "air_group_dossier_action";
    /** Show the group's files: the conversation's Files filter. */
    static final String ACTION_FILES = "files";
    /** This device left the group: the conversation closes too. */
    static final String ACTION_LEFT = "left";

    private static final String TAG = "trifa.AirGroupDossier";
    /** How often the open screen reads the group again: presence and roles have no broadcast here. */
    private static final long POLL_MS = 3000L;
    private static final int GROUP_KEY_HEX_LENGTH = ToxVars.TOX_GROUP_CHAT_ID_SIZE * 2;
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

    private String groupId = "";
    private boolean destroyed = false;
    @Nullable
    private State shown = null;
    private String query = "";
    /** What the member rows show now, so an unchanged read leaves them alone. */
    private String membersShown = null;

    private TextView name;
    private TextView counts;
    private View roleSection;
    private TextView roleText;
    private TextView membersHeading;
    private TextView membersNote;
    private EditText search;
    private LinearLayout membersHolder;
    /** E21: what a member's action could not do (a role, a removal, adding them), under the members. */
    @Nullable
    private TextView memberActionNote;
    private TextView privacyNote;
    private View invite;
    @Nullable
    private TextView inviteNote;
    private TextView muteNote;
    private LinearLayout historyPlate;
    private int historySystemCount = -1;
    private View leaveSection;

    /** What the screen shows, read off the main thread. */
    private static final class State
    {
        final AirGroupPanel.Snapshot group;
        /** toxcore has the group now, so its members can be acted on. */
        final boolean inTox;
        /** Your role, -1 while toxcore does not say. */
        final int selfRole;
        /** toxcore's privacy state, -1 while it does not say. */
        final int privacy;
        final boolean muted;
        /** This device left the group: there is nothing to leave or invite to any more. */
        final boolean left;
        final int systemCount;

        State(final AirGroupPanel.Snapshot group, final boolean inTox, final int selfRole, final int privacy,
              final boolean muted, final boolean left, final int systemCount)
        {
            this.group = group;
            this.inTox = inTox;
            this.selfRole = selfRole;
            this.privacy = privacy;
            this.muted = muted;
            this.left = left;
            this.systemCount = systemCount;
        }
    }

    /** Opens the dossier over {@code activity}, which hears Files and Leave in onActivityResult. */
    static void open(final Activity activity, final String groupId)
    {
        final Intent intent = new Intent(activity, AirGroupDossierActivity.class);
        intent.putExtra(EXTRA_GROUP_ID, groupId);
        // The three-argument form: an in-app screen, not a system picker, so the conversation's
        // startActivityForResult does not arm the app lock's one-time suppression for it.
        activity.startActivityForResult(intent, REQUEST_CODE, null);
    }

    @Override
    protected int screenTitle()
    {
        return R.string.air_group_dossier_title;
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
        refresh();
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        main.removeCallbacks(poll);
        main.postDelayed(poll, POLL_MS);
        // E21: the invite picker closes when there is nobody to invite; its words come back here.
        AirComposerNotice.deliverOnReturn(this::sayAtMembers);
    }

    @Override
    protected void onPause()
    {
        main.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy()
    {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, @Nullable final Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != MainActivity.SelectFriendSingleActivity_ID || resultCode != RESULT_OK || data == null
            || data.getData() == null)
        {
            return;
        }
        // "0:" and the key: a contact. The picker lists groups too only when asked to, which it is not here.
        final String picked = data.getData().toString();
        if (!picked.startsWith("0:"))
        {
            return;
        }
        final String key = picked.substring(2);
        if (key.length() != ToxVars.TOX_PUBLIC_KEY_SIZE * 2)
        {
            return;
        }
        final String id = groupId;
        worker().post(() -> {
            HelperGroup.invite_friend_to_group(id, key, text -> main.post(() -> sayAtInvite(text, true)));
            main.post(this::refresh);
        });
    }

    // ---- reading ----

    private static Handler worker()
    {
        synchronized (WORKER_LOCK)
        {
            if (worker == null)
            {
                final HandlerThread thread = new HandlerThread("air-group-dossier", Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }
    }

    /** Reads the group on the worker and shows it. */
    private void refresh()
    {
        final String id = groupId;
        worker().post(() -> {
            final State state = read(id);
            main.post(() -> show(state));
        });
    }

    /** toxcore and the database, so never on the main thread. */
    private static State read(final String groupId)
    {
        final AirGroupPanel.Snapshot group = AirGroupPanel.read(groupId);
        final long groupNum = HelperGroup.tox_group_by_groupid__wrapper(groupId);
        int selfRole = -1;
        int privacy = -1;
        if (groupNum >= 0)
        {
            try
            {
                selfRole = MainActivity.tox_group_self_get_role(groupNum);
                privacy = MainActivity.tox_group_get_privacy_state(groupNum);
            }
            catch (Throwable e)
            {
                Log.i(TAG, "read:state:EE:" + e.getMessage());
            }
        }
        final String stored = groupId.toLowerCase(Locale.ROOT);
        boolean muted = false;
        boolean left = false;
        try
        {
            final List<GroupDB> rows = TrifaToxService.orma.selectFromGroupDB().group_identifierEq(stored).toList();
            if (rows != null && !rows.isEmpty())
            {
                muted = rows.get(0).notification_silent;
                left = rows.get(0).group_we_left;
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "read:group:EE:" + e.getMessage());
        }
        int systemCount = 0;
        try
        {
            systemCount = TrifaToxService.orma.selectFromGroupMessage().group_identifierEq(stored)
                                                .tox_group_peer_pubkeyEq(TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY)
                                                .count();
        }
        catch (Throwable e)
        {
            Log.i(TAG, "read:system:EE:" + e.getMessage());
        }
        return new State(group, groupNum >= 0, selfRole, privacy, muted, left, systemCount);
    }

    // ---- drawing ----

    private void show(final State s)
    {
        if (destroyed || isFinishing())
        {
            return;
        }
        if (shown == null)
        {
            build(s);
        }
        shown = s;
        name.setText(s.group.title);
        counts.setText(AirGroupPanel.headerText(this, s.group.reach));

        final int role = roleWords(s.selfRole);
        roleSection.setVisibility(role == 0 ? View.GONE : View.VISIBLE);
        if (role != 0)
        {
            setText(roleText, getString(role));
        }

        if (privacyNote != null)
        {
            final int who = s.privacy == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value
                            ? R.string.air_group_privacy_open_note
                            : (s.privacy == ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value
                               ? R.string.air_group_privacy_invite_only_note : 0);
            privacyNote.setVisibility(who == 0 ? View.GONE : View.VISIBLE);
            if (who != 0)
            {
                setText(privacyNote, getString(who));
            }
        }
        if (invite != null)
        {
            invite.setVisibility(s.left ? View.GONE : View.VISIBLE);
        }
        if (s.left)
        {
            endInviteNote();
        }
        fillHistory(s.systemCount);
        leaveSection.setVisibility(s.left ? View.GONE : View.VISIBLE);
        showMembers();
    }

    /** The screen's parts, once; {@link #show} fills them in on every read. */
    private void build(final State s)
    {
        column.removeAllViews();
        final Resources res = getResources();

        // The group, and how many are here.
        final LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        name = AirScreenKit.text(this, R.style.TextAppearance_KQ_Display, R.color.kq_ink);
        ViewCompat.setAccessibilityHeading(name, true);
        head.addView(name, AirScreenKit.matchWidth());
        counts = AirScreenKit.text(this, R.style.TextAppearance_KQ_Air_LabelRegular, R.color.kq_ink_2);
        counts.setFontFeatureSettings("tnum");
        final LinearLayout.LayoutParams countsParams = AirScreenKit.matchWidth();
        countsParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_4);
        head.addView(counts, countsParams);
        AirScreenKit.addSection(column, null, null, head);

        // Your role and what it allows.
        roleText = AirScreenKit.body(this, "");
        roleSection = AirScreenKit.addSection(column, getString(R.string.air_group_your_role), null, roleText);

        // Who gets a message now, or the members of a group this phone only reads.
        membersHeading = AirScreenKit.heading(this, "");
        membersNote = AirScreenKit.note(this, "");
        search = searchField();
        membersHolder = new LinearLayout(this);
        membersHolder.setOrientation(LinearLayout.VERTICAL);
        memberActionNote = AirScreenKit.note(this, "");
        memberActionNote.setVisibility(View.GONE);
        AirScreenKit.addSection(column, null, null, membersHeading, membersNote, search, membersHolder,
                                memberActionNote);

        // The key, and who can join with it.
        final String key = s.group.groupId.toUpperCase(Locale.ROOT);
        if (key.length() == GROUP_KEY_HEX_LENGTH)
        {
            final TextView keyText = AirScreenKit.keyText(this, key);
            keyText.setContentDescription(getString(R.string.air_group_key) + ": "
                                          + AirConversationModel.keyLines(key, 0));
            privacyNote = AirScreenKit.note(this, "");
            final View copy = AirScreenKit.iconButton(this, getString(R.string.air_group_copy_key), R.drawable.kq_ic_copy);
            copy.setOnClickListener(v -> copyKey(key));
            invite = AirScreenKit.iconButton(this, getString(R.string.air_group_invite), R.drawable.kq_ic_user_plus);
            invite.setOnClickListener(v -> invite());
            final View qr = AirScreenKit.iconButton(this, getString(R.string.air_dossier_qr), R.drawable.kq_ic_scan);
            qr.setOnClickListener(v -> showQr(key));
            // E21: what stopped an invitation stands under the Invite button.
            inviteNote = AirScreenKit.note(this, "");
            inviteNote.setVisibility(View.GONE);
            AirScreenKit.addSection(column, getString(R.string.air_group_key), null, keyText, privacyNote,
                                    AirScreenKit.buttonRow(this, copy, invite), inviteNote, AirScreenKit.leading(qr));
        }

        // Notifications.
        final AirSettingRow mute = new AirSettingRow(this, getString(R.string.air_dossier_mute), false);
        mute.withSwitch(s.muted, (button, checked) -> setMuted(checked));
        muteNote = AirScreenKit.note(this, "");
        showMuteNote(s.muted);
        AirScreenKit.addSection(column, getString(R.string.air_dossier_notifications_title), null,
                                AirScreenKit.plateOf(this, mute), muteNote);

        // The history on this device.
        historyPlate = AirScreenKit.plate(this);
        AirScreenKit.addSection(column, getString(R.string.air_group_history), getString(R.string.air_group_history_note),
                                historyPlate);

        // Everything else the group has: topic, password, member limit, who may write.
        final AirSettingRow settings = new AirSettingRow(this, getString(R.string.air_group_settings), true)
                .detail(getString(R.string.air_group_settings_detail));
        settings.setOnClickListener(v -> openSettings());
        AirScreenKit.addSection(column, null, null, AirScreenKit.plateOf(this, settings));

        // Leaving, with what it means said before it is pressed.
        final TextView leave = AirScreenKit.button(this, getString(R.string.air_group_leave), false);
        leave.setTextColor(AirUi.color(this, R.color.kq_danger));
        leave.setMaxLines(3);
        leave.setOnClickListener(v -> askLeave());
        leaveSection = AirScreenKit.addSection(column, null, getString(R.string.air_group_leave_message), leave);
    }

    private EditText searchField()
    {
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setSingleLine(true);
        TextViewCompat.setTextAppearance(field, R.style.TextAppearance_KQ_Body);
        field.setTextColor(AirUi.color(this, R.color.kq_ink));
        field.setHintTextColor(AirUi.color(this, R.color.kq_ink_3));
        field.setHint(R.string.air_group_search_members);
        field.setBackgroundResource(R.drawable.air_bg_input);
        final int pad = getResources().getDimensionPixelSize(R.dimen.kq_space_12);
        field.setPaddingRelative(pad, pad, pad, pad);
        field.setMinHeight(getResources().getDimensionPixelSize(R.dimen.kq_size_target_android));
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        field.setVisibility(View.GONE);
        field.addTextChangedListener(new TextWatcher()
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
                query = s.toString().trim();
                showMembers();
            }
        });
        return field;
    }

    /** The members as the last read found them, filtered by the search. */
    private void showMembers()
    {
        final State s = shown;
        if (s == null)
        {
            return;
        }
        final List<AirGroupModel.Member> members = s.group.members;
        final boolean searchable = members.size() >= AirGroupModel.SEARCH_THRESHOLD;
        if (!searchable && search.getVisibility() == View.VISIBLE)
        {
            // The search goes with the list that needed it, and takes its words with it. Hidden first: the
            // emptied field calls this again, which then draws the whole list.
            search.setVisibility(View.GONE);
            search.setText("");
            return;
        }
        search.setVisibility(searchable ? View.VISIBLE : View.GONE);
        final boolean writes = s.group.right == AirGroupModel.ComposeRight.WRITE;
        setText(membersHeading, getString(writes ? R.string.air_group_who_gets_now : R.string.air_group_members));

        final List<AirGroupModel.Member> found = AirGroupModel.filtered(members, searchable ? query : "");
        final AirGroupModel.Reach reach = s.group.reach;
        final String note;
        if (found.isEmpty() && searchable && !query.isEmpty())
        {
            note = getString(R.string.air_group_nobody_found);
        }
        else if (writes)
        {
            note = reach.connected && reach.recipients() > 0
                   ? getString(R.string.air_group_who_gets_now_note_format, reach.online, reach.recipients())
                   : AirGroupPanel.hintText(this, reach);
        }
        else
        {
            note = "";
        }
        setText(membersNote, AirUi.localDigits(this, note));
        membersNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);

        final long now = System.currentTimeMillis();
        final StringBuilder print = new StringBuilder();
        print.append(s.inTox).append('\n');
        for (final AirGroupModel.Member member : found)
        {
            print.append(member.publicKey).append('|').append(member.name).append('|').append(member.self)
                 .append('|').append(AirGroupPanel.statusLine(this, member, now)).append('\n');
        }
        final String next = print.toString();
        if (next.equals(membersShown))
        {
            return;
        }
        membersShown = next;
        membersHolder.removeAllViews();
        if (found.isEmpty())
        {
            return;
        }
        final LinearLayout plate = AirScreenKit.plate(this);
        for (final AirGroupModel.Member member : found)
        {
            plate.addView(memberRow(member, now, s.inTox && !member.self && !member.publicKey.isEmpty()),
                          AirScreenKit.matchWidth());
        }
        membersHolder.addView(plate, AirScreenKit.matchWidth());
    }

    /** A member: tile, name and the role with presence. Yours only says who you are; any other opens the actions. */
    private View memberRow(final AirGroupModel.Member member, final long now, final boolean opens)
    {
        final Resources res = getResources();
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.air_bg_row);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_16);
        final int vertical = res.getDimensionPixelSize(R.dimen.kq_space_10);
        row.setPaddingRelative(side, vertical, side, vertical);
        row.setMinimumHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android)
                             + res.getDimensionPixelSize(R.dimen.kq_space_8));

        final AirTileView tile = new AirTileView(this);
        tile.bind(member.self ? AirTileView.Kind.ME : AirTileView.Kind.PERSON, member.name,
                  AirModel.Tone.index(member.toneKey()));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final int tileSize = res.getDimensionPixelSize(R.dimen.kq_size_tile_phone);
        row.addView(tile, new LinearLayout.LayoutParams(tileSize, tileSize));

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        final String title = member.self ? getString(R.string.air_group_member_you_format, member.name) : member.name;
        final TextView nameView = AirScreenKit.text(this, R.style.TextAppearance_KQ_Air_BodyStrong, R.color.kq_ink);
        nameView.setText(title);
        nameView.setMaxLines(2);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(nameView, AirScreenKit.matchWidth());

        final String status = AirGroupPanel.statusLine(this, member, now);
        final LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        if (member.online)
        {
            // A still dot before "online", as in the list's reach line.
            final View dot = new View(this);
            dot.setBackgroundResource(R.drawable.air_bg_dot);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final int dotSize = res.getDimensionPixelSize(R.dimen.kq_size_dot);
            final LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dotSize, dotSize);
            dotParams.setMarginEnd(res.getDimensionPixelSize(R.dimen.kq_space_6));
            statusRow.addView(dot, dotParams);
        }
        final TextView statusView = AirScreenKit.text(this, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
        statusView.setFontFeatureSettings("tnum");
        statusView.setText(status);
        statusView.setMaxLines(2);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusRow.addView(statusView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final LinearLayout.LayoutParams statusParams = AirScreenKit.matchWidth();
        statusParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_2);
        texts.addView(statusRow, statusParams);

        final LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0,
                                                                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                                                                    1f);
        textsParams.setMarginStart(res.getDimensionPixelSize(R.dimen.kq_space_12));
        row.addView(texts, textsParams);
        row.setContentDescription(title + ", " + status);
        row.setFocusable(true);
        if (opens)
        {
            row.setClickable(true);
            row.setOnClickListener(v -> openMember(member));
            row.setOnLongClickListener(v -> {
                openMember(member);
                return true;
            });
            final String hint = getString(R.string.air_group_member_hint);
            ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat()
            {
                @Override
                public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfoCompat info)
                {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                    info.setHintText(hint);
                }
            });
        }
        return row;
    }

    /** Files, then the service messages while there are any, then clearing the history from this device. */
    private void fillHistory(final int systemCount)
    {
        if (systemCount == historySystemCount)
        {
            return;
        }
        historySystemCount = systemCount;
        historyPlate.removeAllViews();
        final AirSettingRow files = new AirSettingRow(this, getString(R.string.air_dossier_files_title), true);
        files.setOnClickListener(v -> finishWith(ACTION_FILES));
        historyPlate.addView(files, AirScreenKit.matchWidth());
        if (systemCount > 0)
        {
            final AirSettingRow system = new AirSettingRow(this, AirUi.localDigits(this, getString(
                    R.string.air_group_remove_system_format, systemCount)), false);
            system.setOnClickListener(v -> askRemoveSystem());
            historyPlate.addView(system, AirScreenKit.matchWidth());
        }
        final AirSettingRow clear = new AirSettingRow(this, getString(R.string.air_group_clear_history), false).danger();
        clear.setOnClickListener(v -> askClearHistory());
        historyPlate.addView(clear, AirScreenKit.matchWidth());
    }

    // ---- actions ----

    private void openMember(final AirGroupModel.Member member)
    {
        if (memberActionNote != null)
        {
            // A new choice: what the last one could not do is no longer the news.
            AirUi.endLine(memberActionNote);
        }
        GroupMemberActions.showActionMenu(this, groupId, member.publicKey, member.name, this::refresh);
    }

    /**
     * E21: what a member's action could not do, under the members it was chosen from, a failure in red
     * and a rule in the second ink. False when this screen is going, so the caller says it elsewhere.
     */
    boolean sayAtMembers(final CharSequence text, final boolean failed)
    {
        return sayAtMembers(text, failed, null);
    }

    /** As above; a failure with a code for support (AirErrorCode) ends in Details, which opens it. */
    boolean sayAtMembers(final CharSequence text, final boolean failed, @Nullable final String code)
    {
        final TextView note = memberActionNote;
        if (note == null || destroyed || isFinishing() || TextUtils.isEmpty(text))
        {
            return false;
        }
        AirUi.sayOnLine(note, text, failed, code);
        // Under a long list it may stand below the edge: brought into sight once it has its height.
        note.post(() -> note.requestRectangleOnScreen(new android.graphics.Rect(0, 0, note.getWidth(),
                                                                                note.getHeight()), false));
        return true;
    }

    private void copyKey(final String key)
    {
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null)
        {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.air_group_key), key));
        // From Android 13 the system shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        {
            toast(R.string.air_group_key_copied);
        }
        else
        {
            column.announceForAccessibility(getString(R.string.air_group_key_copied));
        }
    }

    private void showQr(final String key)
    {
        final State s = shown;
        final String title = s == null ? "" : s.group.title;
        // The group's link, as on iOS (GroupJoinHelper), so a camera that reads it knows a group from a person.
        AirKeyQr.show(this, "khandaq:group:" + key, key, getString(R.string.air_group_qr_heading_format, title),
                      getString(R.string.air_group_qr_subtitle));
    }

    private void invite()
    {
        endInviteNote();
        final State s = shown;
        if (s == null)
        {
            return;
        }
        final Set<String> members = new HashSet<>();
        for (final AirGroupModel.Member member : s.group.members)
        {
            members.add(member.publicKey);
        }
        // The picker lists the contacts who are not in the group yet; with none it has nothing to offer,
        // and that is said here, at the button, instead of in a toast from an empty picker.
        worker().post(() -> {
            final boolean anyone = anyoneToInvite(members);
            main.post(() -> {
                if (destroyed || isFinishing())
                {
                    return;
                }
                if (!anyone)
                {
                    sayAtInvite(getString(R.string.group_invite_no_friends_available), false);
                    return;
                }
                final Intent intent = new Intent(this, FriendSelectSingleActivity.class);
                intent.putExtra("group_id", groupId);
                intent.putExtra("offline", 1);
                startActivityForResult(intent, MainActivity.SelectFriendSingleActivity_ID);
            });
        });
    }

    /** True when a contact outside the group is there to invite: the picker's own list, read the same way. */
    private static boolean anyoneToInvite(final Set<String> memberKeys)
    {
        try
        {
            final List<com.zoffcc.applications.sorm.FriendList> friends = TrifaToxService.orma.selectFromFriendList()
                    .is_relayNotEq(true).toList();
            if (friends == null)
            {
                return false;
            }
            for (final com.zoffcc.applications.sorm.FriendList friend : friends)
            {
                final String key = friend.tox_public_key_string;
                if (key != null && !memberKeys.contains(key.toUpperCase(Locale.ROOT))
                    && !FavoritesChatHelper.isFavoritesChat(key))
                {
                    return true;
                }
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "invite:friends:EE:" + e.getMessage());
            // Unknown: let the picker decide, as before.
            return true;
        }
        return false;
    }

    /** E21: what stopped an invitation, under the Invite button: a rule in the second ink, a failure in red. */
    private void sayAtInvite(final CharSequence text, final boolean failed)
    {
        final TextView note = inviteNote;
        if (note == null || destroyed || TextUtils.isEmpty(text))
        {
            return;
        }
        AirUi.sayOnLine(note, text, failed);
    }

    private void endInviteNote()
    {
        if (inviteNote != null)
        {
            AirUi.endLine(inviteNote);
        }
    }

    private void setMuted(final boolean muted)
    {
        showMuteNote(muted);
        final String id = groupId.toLowerCase(Locale.ROOT);
        worker().post(() -> {
            try
            {
                // The group's own notification switch, which the notifications already obey.
                TrifaToxService.orma.updateGroupDB().group_identifierEq(id).notification_silent(muted).execute();
            }
            catch (Throwable e)
            {
                Log.i(TAG, "mute:EE:" + e.getMessage());
            }
            AirRepository.requestRefresh();
        });
    }

    private void showMuteNote(final boolean muted)
    {
        setText(muteNote, getString(muted ? R.string.air_group_muted_note : R.string.air_group_unmuted_note));
    }

    private void askRemoveSystem()
    {
        AirQuestion.ask(this, getString(R.string.group_delete_system_msgs_title),
                        getString(R.string.air_group_remove_system_message),
                        getString(R.string.air_group_remove_system_action), true, () -> {
                    final String id = groupId.toLowerCase(Locale.ROOT);
                    worker().post(() -> {
                        try
                        {
                            TrifaToxService.orma.deleteFromGroupMessage().group_identifierEq(id).tox_group_peer_pubkeyEq(
                                    TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY).execute();
                        }
                        catch (Throwable e)
                        {
                            Log.i(TAG, "removeSystem:EE:" + e.getMessage());
                        }
                        AirRepository.requestRefresh();
                        refresh();
                    });
                });
    }

    private void askClearHistory()
    {
        AirQuestion.ask(this, getString(R.string.air_group_clear_history_title),
                        getString(R.string.air_group_clear_history_message), getString(R.string.air_group_clear_history),
                        true, () -> {
                    final String id = groupId;
                    worker().post(() -> {
                        clearHistoryHere(id);
                        // As on iOS, back to the conversation, which reads its now empty history on return.
                        main.post(() -> {
                            if (!destroyed)
                            {
                                finish();
                            }
                        });
                    });
                });
    }

    /**
     * Removes the group's messages from this device, with this device's copies of their files. Nothing
     * goes out: the members keep theirs (deleting single messages may retract our own for everyone, this
     * does not).
     */
    private static void clearHistoryHere(final String groupId)
    {
        final String id = groupId.toLowerCase(Locale.ROOT);
        try
        {
            final List<GroupMessage> files = TrifaToxService.orma.selectFromGroupMessage().group_identifierEq(id)
                                                                 .TRIFA_MESSAGE_TYPEEq(
                                                                         TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value)
                                                                 .toList();
            if (files != null)
            {
                for (final GroupMessage m : files)
                {
                    deleteLocalCopy(m);
                }
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "clear:files:EE:" + e.getMessage());
        }
        try
        {
            TrifaToxService.orma.deleteFromGroupMessage().group_identifierEq(id).execute();
        }
        catch (Throwable e)
        {
            Log.i(TAG, "clear:EE:" + e.getMessage());
        }
        AirRepository.requestRefresh();
    }

    /** The file of a message, as deleting that message removes it (MainActivity.delete_selected_group_messages_asynchtask). */
    private static void deleteLocalCopy(final GroupMessage m)
    {
        try
        {
            if (m.direction == 0)
            {
                // A received file is kept in the encrypted store.
                if (!TextUtils.isEmpty(m.path_name) && !TextUtils.isEmpty(m.file_name))
                {
                    HelperGeneric.delete_vfs_file(m.path_name, m.file_name);
                }
            }
            else if (!m.storage_frame_work && m.filename_fullpath != null
                     && !MainActivity.SD_CARD_FILES_OUTGOING_WRAPPER_DIR.isEmpty()
                     && m.filename_fullpath.startsWith(MainActivity.SD_CARD_FILES_OUTGOING_WRAPPER_DIR))
            {
                // This app's copy of a file we sent; the original stays where it was picked from.
                // KHANDAQ (QA live, F4): the folder of its own the copy lies in goes with it.
                if (!HelperFiletransfer.delete_outgoing_copy(m.filename_fullpath))
                {
                    Log.i(TAG, "clear:copy:gone");
                }
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "clear:file:EE:" + e.getMessage());
        }
    }

    private void openSettings()
    {
        final Intent intent = new Intent(this, GroupInfoActivity.class);
        intent.putExtra("group_id", groupId);
        startActivity(intent);
    }

    private void askLeave()
    {
        final State s = shown;
        if (s == null)
        {
            return;
        }
        AirQuestion.ask(this, getString(R.string.air_group_leave_title_format, s.group.title),
                        getString(R.string.air_group_leave_message), getString(R.string.air_group_leave), true, () -> {
                    GroupListHolder.leave_group(groupId);
                    AirRepository.requestRefresh();
                    finishWith(ACTION_LEFT);
                });
    }

    private void finishWith(final String action)
    {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_ACTION, action).putExtra(EXTRA_GROUP_ID, groupId));
        finish();
    }

    // ---- small helpers ----

    /** Your role in one or two sentences; 0 while toxcore does not say. */
    @StringRes
    private static int roleWords(final int role)
    {
        switch (role)
        {
            case AirGroupModel.ROLE_FOUNDER:
                return R.string.air_group_you_founder;
            case AirGroupModel.ROLE_MODERATOR:
                return R.string.air_group_you_moderator;
            case AirGroupModel.ROLE_USER:
                return R.string.air_group_you_user;
            case AirGroupModel.ROLE_OBSERVER:
                return R.string.air_group_you_observer;
            default:
                return 0;
        }
    }

    /** Sets a text only when it differs, so an unchanged read neither lays out nor speaks. */
    private static void setText(final TextView view, final CharSequence text)
    {
        if (!TextUtils.equals(view.getText(), text))
        {
            view.setText(text);
        }
    }
}

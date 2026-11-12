package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;

import org.khandaq.messenger.R;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * KHANDAQ (redesign, stage 3, E13): the group conversation's parts of the design, over the activity that
 * still runs the transcript, the composer's buttons and the calls. The header (the group's tile and name),
 * the band with "9 members, 4 online now" and the tiles of who gets a message right now, the line under
 * the field about who gets it with the length counter, and the bar in place of the field when you may only
 * read. The rules are AirGroupModel's; this only reads toxcore off the main thread and draws.
 */
final class AirGroupPanel
{
    private static final String TAG = "trifa.AirGroupPanel";
    /** From this text scale on, the tiles of who gets a message now take the phone size. */
    private static final float LARGE_TEXT_SCALE = 1.3f;

    private final GroupMessageListActivity activity;
    private final View root;
    private final Toolbar toolbar;
    private final View infoTap;
    private final AirTileView tile;
    private final TextView name;
    private final View band;
    private final TextView counts;
    private final View reachRow;
    private final LinearLayout tiles;
    private final View composer;
    private final EditText field;
    private final ImageButton micSend;
    private final View hintRow;
    private final View hintDot;
    private final TextView hint;
    /** E21: what the composer refused or what failed to send, on the line of the hint for a while. */
    private final AirComposerNotice notice;
    private final TextView counter;
    private final View readOnly;
    private final TextView readOnlyText;
    /** An observer's: why there is nobody to write to from here, in place of {@link #ask}. */
    private final TextView readOnlyWhy;
    private final TextView ask;
    private final View replyBar;

    private Snapshot shown;
    private int tileKeysHash;

    private final TextWatcher fieldWatcher = new TextWatcher()
    {
        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after)
        {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count)
        {
        }

        @Override
        public void afterTextChanged(final Editable s)
        {
            // A change of the words answers a refusal: the line says who gets the message again.
            notice.end();
            updateCounter();
        }
    };

    /** What the panel shows, read off the main thread. */
    static final class Snapshot
    {
        final String groupId;
        final String title;
        final AirGroupModel.Reach reach;
        /** In the order of the dossier. */
        final List<AirGroupModel.Member> members;
        final AirGroupModel.ComposeRight right;

        Snapshot(final String groupId, final String title, final AirGroupModel.Reach reach,
                 final List<AirGroupModel.Member> members, final AirGroupModel.ComposeRight right)
        {
            this.groupId = groupId;
            this.title = title;
            this.reach = reach;
            this.members = members;
            this.right = right;
        }
    }

    AirGroupPanel(final GroupMessageListActivity activity)
    {
        this.activity = activity;
        root = activity.findViewById(android.R.id.content);
        toolbar = activity.findViewById(R.id.toolbar);
        infoTap = activity.findViewById(R.id.ml_header_group_info_tap);
        tile = activity.findViewById(R.id.air_group_tile);
        name = activity.findViewById(R.id.ml_maintext);
        // One line that ends in an ellipsis (AirChatPanel says why).
        name.setSingleLine(true);
        band = activity.findViewById(R.id.air_group_band);
        counts = activity.findViewById(R.id.air_group_counts);
        reachRow = activity.findViewById(R.id.air_group_reach_row);
        tiles = activity.findViewById(R.id.air_group_tiles);
        composer = activity.findViewById(R.id.emoji_bar);
        field = activity.findViewById(R.id.ml_new_message);
        micSend = activity.findViewById(R.id.ml_button_recaudio);
        hintRow = activity.findViewById(R.id.air_compose_hint_row);
        hintDot = activity.findViewById(R.id.air_compose_dot);
        hint = activity.findViewById(R.id.air_compose_hint);
        counter = activity.findViewById(R.id.air_compose_counter);
        notice = new AirComposerNotice(hint, hintDot, this::redrawHint);
        readOnly = activity.findViewById(R.id.air_group_read_only);
        readOnlyText = activity.findViewById(R.id.air_group_read_only_text);
        readOnlyWhy = activity.findViewById(R.id.air_group_read_only_why);
        ask = activity.findViewById(R.id.air_group_ask);
        replyBar = activity.findViewById(R.id.chat_reply_preview_bar);

        // Both before the activity binds its helpers: the microphone keeps the design's tint between the
        // send arrow's turns, and the reply and edit bar takes the design's colours.
        micSend.setTag(R.id.air_mic_tint, ContextCompat.getColorStateList(activity, R.color.air_icon_button_tint));
        if (replyBar != null)
        {
            replyBar.setTag(R.id.air_reply_style, Boolean.TRUE);
        }
    }

    /** After the activity bound the composer: the design's look and the panel's own listeners. */
    void start()
    {
        AirChatPanel.styleWindow(activity, root);
        styleHeader();
        AirChatPanel.styleComposer(activity, composer, field, micSend);
        AirChatPanel.styleReplyBar(activity);
        AirChatPanel.styleVoiceBar(activity, GroupMessageListActivity.voiceRecordingUiHelper);

        // The band and the line under the field each read as one sentence.
        counts.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        counter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        ViewCompat.setScreenReaderFocusable(hintRow, true);
        // A line longer than the row wraps rather than being cut (a long translation, a large text size),
        // as the iOS line does (AirComposeHintView).
        hint.setMaxLines(Integer.MAX_VALUE);

        AirUi.markAsButton(band);
        band.setOnClickListener(v -> activity.open_group_info_activity());
        // The tiles lie in a scroll view, which takes a tap for itself: the strip spans its whole width
        // and opens the dossier too. The band stays the one thing TalkBack offers.
        tiles.setOnClickListener(v -> activity.open_group_info_activity());
        tiles.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        AirUi.markAsButton(infoTap);
        AirUi.markAsButton(ask);
        ask.setOnClickListener(v -> askModerator());
        field.addTextChangedListener(fieldWatcher);
        updateCounter();
    }

    /** The header in the design's colours; GroupMessageListActivity.apply_chat_header calls it. */
    void styleHeader()
    {
        final int ink = AirUi.color(activity, R.color.kq_ink);
        toolbar.setBackgroundColor(AirUi.color(activity, R.color.kq_surface));
        final Drawable navigation = toolbar.getNavigationIcon();
        if (navigation != null)
        {
            DrawableCompat.setTint(navigation.mutate(), ink);
        }
        final Drawable overflow = toolbar.getOverflowIcon();
        if (overflow != null)
        {
            DrawableCompat.setTint(overflow.mutate(), ink);
        }
        final ActionBar bar = activity.getSupportActionBar();
        if (bar != null)
        {
            bar.setDisplayShowTitleEnabled(false);
        }
        name.setTextColor(ink);
        infoTap.setBackgroundResource(R.drawable.air_bg_icon_button);
    }

    // ---- reading ----

    /** The group as the panel shows it: toxcore and the database, so never on the main thread. */
    static Snapshot read(final String groupId)
    {
        final long groupNum = HelperGroup.tox_group_by_groupid__wrapper(groupId);
        String title = HelperGroup.get_effective_group_title(groupNum, groupId);
        if (TextUtils.isEmpty(title))
        {
            // As the group's row in the list (AirRepository.groupEntry) names it.
            title = groupId.substring(0, Math.min(8, groupId.length()));
        }
        boolean connected = false;
        int selfRole = -1;
        int voice = AirGroupModel.VOICE_ALL;
        final AirLiveOverride.Source standIn = AirLiveOverride.group(groupId);
        if (standIn != null)
        {
            connected = standIn.groupConnected(groupId);
            selfRole = standIn.groupSelfRole(groupId);
        }
        else if (groupNum >= 0)
        {
            try
            {
                connected = MainActivity.tox_group_is_connected(groupNum)
                            == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value;
                selfRole = MainActivity.tox_group_self_get_role(groupNum);
                voice = MainActivity.tox_group_get_voice_state(groupNum);
            }
            catch (Throwable e)
            {
                Log.i(TAG, "read:state:EE:" + e.getMessage());
            }
        }

        final List<AirGroupModel.Member> members = new ArrayList<>();
        try
        {
            for (final HelperGroup.GroupMemberDisplay display : HelperGroup.dedupe_group_members_for_display(
                    HelperGroup.collect_group_members_for_display(groupId)))
            {
                if (display == null || TextUtils.isEmpty(display.pubkey))
                {
                    continue;
                }
                // A role only where the row shows one (AirGroupModel.shownRole): another member's waits for
                // the group to be connected.
                final int known = display.self ? selfRole : roleOf(groupId, groupNum, display.pubkey, connected);
                final int role = AirGroupModel.shownRole(connected, display.self, known);
                // The old member list labels our own row "Name (you)"; the dossier says who you are itself,
                // and the tile takes its letters from the name, so ours is read without that suffix.
                final String name = display.self
                                    ? HelperFriend.format_group_peer_list_display_name(
                                            display.pubkey, HelperGroup.resolve_group_self_raw_name(groupNum))
                                    : display.name;
                final boolean online = AirGroupModel.shownOnline(connected, display.self, display.online);
                // Out of the group your own row says "offline", never when this device last saw you.
                final long lastSeen = display.self && !online ? 0L : display.last_seen_ms;
                members.add(new AirGroupModel.Member(display.pubkey, name, role, online, lastSeen, display.self));
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "read:members:EE:" + e.getMessage());
        }

        final long[] counted = HelperGroup.count_authoritative_group_members(groupId);
        // toxcore's count includes us; online counts the others.
        final AirGroupModel.Reach reach = new AirGroupModel.Reach(
                (int) Math.min(Integer.MAX_VALUE, Math.max(0L, counted[0]) + Math.max(0L, counted[1])),
                members.size(), (int) Math.max(0L, counted[0] - 1L), connected);
        final AirGroupModel.ComposeRight right = selfRole < 0 ? AirGroupModel.ComposeRight.WRITE
                                                              : AirGroupModel.ComposeRight.make(selfRole, voice);
        return new Snapshot(groupId, title, reach, AirGroupModel.sorted(members), right);
    }

    /**
     * Your role in the group as {@link #read} takes it, the stand-in of a debug build first, then toxcore;
     * -1 when neither says. One quick read, as the member menu makes on a tap: the private message
     * sheet asks it when it opens and again on Send (AirGroupModel.writesPrivately).
     */
    static int selfRole(final String groupId)
    {
        final AirLiveOverride.Source standIn = AirLiveOverride.group(groupId);
        if (standIn != null)
        {
            return standIn.groupSelfRole(groupId);
        }
        try
        {
            final long groupNum = HelperGroup.tox_group_by_groupid__wrapper(groupId);
            return groupNum < 0 ? -1 : MainActivity.tox_group_self_get_role(groupNum);
        }
        catch (Throwable e)
        {
            Log.i(TAG, "selfRole:EE:" + e.getMessage());
            return -1;
        }
    }

    /**
     * The member's role as the group screens show it (AirGroupModel.shownRole): yours always, another
     * member's none while the group is not connected, so the dossier, the member's own screen and the
     * member menu say the same.
     */
    static int roleOf(final String groupId, final long groupNum, final String publicKey)
    {
        boolean connected = false;
        if (groupNum >= 0)
        {
            try
            {
                connected = MainActivity.tox_group_is_connected(groupNum)
                            == TRIFAGlobals.TOX_GROUP_CONNECTION_STATUS.TOX_GROUP_CONNECTION_STATUS_CONNECTED.value;
                final String selfKey = MainActivity.tox_group_self_get_public_key(groupNum);
                if (publicKey != null && publicKey.equalsIgnoreCase(selfKey))
                {
                    return AirGroupModel.shownRole(connected, true, MainActivity.tox_group_self_get_role(groupNum));
                }
            }
            catch (Throwable ignored)
            {
                // toxcore does not know the group: not connected.
            }
        }
        return roleOf(groupId, groupNum, publicKey, connected);
    }

    /**
     * As above, for a reader that already asked whether the group is connected: toxcore's role while the
     * member is in the peer list, else the one last stored here. A role no row would show is not looked up.
     */
    static int roleOf(final String groupId, final long groupNum, final String publicKey, final boolean groupConnected)
    {
        if (!groupConnected)
        {
            return AirGroupModel.ROLE_UNKNOWN;
        }
        if (groupNum >= 0)
        {
            try
            {
                final long peerId = MainActivity.tox_group_peer_by_public_key(groupNum, publicKey);
                if (peerId >= 0)
                {
                    final int role = MainActivity.tox_group_peer_get_role(groupNum, peerId);
                    if (AirGroupModel.isKnownRole(role))
                    {
                        return role;
                    }
                }
            }
            catch (Throwable ignored)
            {
                // Not in the peer list now: the stored role below.
            }
        }
        try
        {
            final com.zoffcc.applications.sorm.GroupPeerDB stored = HelperGroup.lookup_group_peer_by_pubkey(groupId,
                                                                                                           publicKey);
            if (stored != null && AirGroupModel.isKnownRole(stored.Tox_Group_Role))
            {
                return stored.Tox_Group_Role;
            }
        }
        catch (Throwable ignored)
        {
            // No stored row.
        }
        return AirGroupModel.ROLE_UNKNOWN;
    }

    // ---- drawing ----

    void show(final Snapshot s)
    {
        if (s == null)
        {
            return;
        }
        shown = s;
        setText(name, s.title);
        tile.bind(AirTileView.Kind.GROUP, s.title, AirModel.Tone.index(s.groupId));

        final String header = headerText(activity, s.reach);
        setText(counts, header);
        final boolean writes = s.right == AirGroupModel.ComposeRight.WRITE;
        final List<AirGroupModel.Member> reachable = s.reach.connected ? AirGroupModel.reachableNow(s.members)
                                                                       : new ArrayList<AirGroupModel.Member>();
        showTiles(reachable);
        final boolean showsReach = writes && !reachable.isEmpty();
        reachRow.setVisibility(showsReach ? View.VISIBLE : View.GONE);
        final StringBuilder spoken = new StringBuilder(s.title);
        appendSentence(spoken, header);
        if (showsReach)
        {
            final List<String> names = new ArrayList<>();
            for (final AirGroupModel.Member member : reachable)
            {
                names.add(member.name);
            }
            appendSentence(spoken, activity.getString(R.string.air_group_get_now_spoken_format,
                                                      TextUtils.join(", ", names)));
        }
        setDescription(band, spoken.toString());
        final StringBuilder title = new StringBuilder(s.title);
        appendSentence(title, header);
        setDescription(infoTap, title.toString());

        if (writes)
        {
            readOnly.setVisibility(View.GONE);
            composer.setVisibility(View.VISIBLE);
            showHint(s.reach);
            updateCounter();
        }
        else
        {
            // In place of the field: why, and whom to ask. A reply or a correction begun before cannot go
            // any more, so its bar goes with the field.
            if (composer.getVisibility() == View.VISIBLE)
            {
                ChatReplyPreviewController.clear();
            }
            composer.setVisibility(View.GONE);
            hintRow.setVisibility(View.GONE);
            readOnly.setVisibility(View.VISIBLE);
            setText(readOnlyText, activity.getString(readOnlyText(s.right)));
            if (s.right.asks())
            {
                readOnlyWhy.setVisibility(View.GONE);
                ask.setVisibility(View.VISIBLE);
                final boolean canAsk = !AirGroupModel.moderators(s.members).isEmpty();
                setText(ask, activity.getString(s.right.asksFounder() ? R.string.air_group_ask_founder
                                                                      : R.string.air_group_ask_moderator));
                ask.setEnabled(canAsk);
                ask.setAlpha(canAsk ? 1f : 0.5f);
                setDescription(ask, canAsk ? null
                                           : ask.getText() + ". " + activity.getString(R.string.air_group_ask_nobody));
            }
            else
            {
                // An observer: toxcore refuses their private messages too (AirGroupModel.writesPrivately),
                // so no button leads to one; the line says so, word for word as on the computer.
                ask.setVisibility(View.GONE);
                setText(readOnlyWhy, activity.getString(R.string.air_group_observer_no_private));
                readOnlyWhy.setVisibility(View.VISIBLE);
            }
        }
    }

    /** "9 members, 4 online now": the line under the group's name, in the conversation and its dossier. */
    static String headerText(final Context context, final AirGroupModel.Reach reach)
    {
        final String members = context.getResources().getQuantityString(R.plurals.air_group_members_count,
                                                                         reach.total, reach.total);
        switch (reach.header())
        {
            case OFFLINE:
                return context.getString(R.string.air_group_header_offline_format, members);
            case NOBODY:
                return context.getString(R.string.air_group_header_none_format, members);
            case SOME:
            default:
                return context.getString(R.string.air_group_header_format, members, reach.online);
        }
    }

    /** The line under the field: who gets a message now (also the group dossier's note). */
    static String hintText(final Context context, final AirGroupModel.Reach reach)
    {
        switch (reach.hint())
        {
            case OFFLINE:
                return context.getString(R.string.air_group_hint_offline);
            case ALONE:
                return context.getString(R.string.air_group_hint_alone);
            case NOBODY:
                return context.getString(R.string.air_group_hint_none);
            case ONE_OTHER:
                return context.getString(R.string.air_group_hint_one_other);
            case ALL:
                return context.getResources().getQuantityString(R.plurals.air_group_hint_all_format, reach.online,
                                                                reach.online);
            case SOME:
            default:
                return context.getResources().getQuantityString(R.plurals.air_group_hint_format, reach.online,
                                                                reach.online, reach.recipients());
        }
    }

    /**
     * E21: a refusal or a failed send on the line under the field; false while this phone only reads the
     * group and there is no field to say it under.
     */
    boolean sayAtComposer(final CharSequence text, final boolean failed)
    {
        return sayAtComposer(text, failed, null);
    }

    /** {@link #sayAtComposer} for a failure with a code for support, opened from Details under the field. */
    boolean sayAtComposer(final CharSequence text, final boolean failed,
                          @androidx.annotation.Nullable final String code)
    {
        if (composer.getVisibility() != View.VISIBLE)
        {
            return false;
        }
        notice.show(text, failed, code);
        updateHintRow();
        return true;
    }

    /** The line's own words once a notice is over. */
    private void redrawHint()
    {
        updateCounter();
        final Snapshot s = shown;
        if (s != null)
        {
            showHint(s.reach);
        }
        else
        {
            setText(hint, "");
            hintDot.setVisibility(View.GONE);
            updateHintRow();
        }
    }

    private void showHint(final AirGroupModel.Reach reach)
    {
        if (notice.isShown())
        {
            // The notice has the line until it ends; it draws the hint again then.
            return;
        }
        setText(hint, hintText(activity, reach));
        switch (reach.dot())
        {
            case LIVE:
                showDot(R.color.kq_live);
                break;
            case TRANSIT:
                showDot(R.color.kq_transit);
                break;
            case NONE:
            default:
                hintDot.setVisibility(View.GONE);
                break;
        }
        updateHintRow();
    }

    private void showDot(final int colorRes)
    {
        ViewCompat.setBackgroundTintList(hintDot, ColorStateList.valueOf(AirUi.color(activity, colorRes)));
        hintDot.setVisibility(View.VISIBLE);
    }

    /** The tiles of who gets a message now; rebuilt only when the people change. */
    private void showTiles(final List<AirGroupModel.Member> reachable)
    {
        final StringBuilder keys = new StringBuilder();
        for (final AirGroupModel.Member member : reachable)
        {
            keys.append(member.toneKey()).append('|').append(member.name).append(';');
        }
        final int hash = keys.toString().hashCode();
        if (hash == tileKeysHash && tiles.getChildCount() == reachable.size())
        {
            return;
        }
        tileKeysHash = hash;
        tiles.removeAllViews();
        final int side = activity.getResources().getDimensionPixelSize(
                activity.getResources().getConfiguration().fontScale >= LARGE_TEXT_SCALE ? R.dimen.kq_size_tile_phone
                                                                                          : R.dimen.kq_size_tile_desk);
        final int gap = activity.getResources().getDimensionPixelSize(R.dimen.kq_space_4);
        for (int i = 0; i < reachable.size(); i++)
        {
            final AirGroupModel.Member member = reachable.get(i);
            final AirTileView view = new AirTileView(activity);
            view.bind(AirTileView.Kind.PERSON, member.name, AirModel.Tone.index(member.toneKey()));
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(side, side);
            if (i > 0)
            {
                params.setMarginStart(gap);
            }
            tiles.addView(view, params);
        }
    }

    private static int readOnlyText(final AirGroupModel.ComposeRight right)
    {
        switch (right)
        {
            case MODERATORS_ONLY:
                return R.string.air_group_read_only_moderators;
            case FOUNDER_ONLY:
                return R.string.air_group_read_only_founder;
            case OBSERVER:
            default:
                return R.string.air_group_read_only_observer;
        }
    }

    // ---- the length of a message ----

    /** How many bytes Send would send now: the reply header travels in the same message. */
    private int composedBytes(final String body)
    {
        if (ChatReplyPreviewController.isEditActive())
        {
            return utf8Length(body);
        }
        return utf8Length(MessageReplyHelper.encode(ChatReplyPreviewController.peekPending(), body));
    }

    /** The counter once a tenth of the room is left; over the limit Send waits instead of cutting. */
    void updateCounter()
    {
        String text = null;
        boolean over = false;
        final String body = HelperGeneric.normalize_chat_input_text(String.valueOf(field.getText()));
        if (!body.isEmpty())
        {
            final boolean edit = ChatReplyPreviewController.isEditActive();
            final Integer remaining = edit ? AirConversationModel.composeRemaining(utf8Length(body),
                                                                                   HelperMessageEdit.MAX_EDIT_TEXT_BYTES)
                                           : AirGroupModel.composeRemaining(composedBytes(body));
            if (remaining != null)
            {
                final int left = remaining;
                over = left < 0;
                text = left >= 0 ? quantity(R.plurals.air_compose_bytes_left, left)
                                 : quantity(edit ? R.plurals.air_compose_bytes_over_edit
                                                 : R.plurals.air_compose_bytes_over_group, -left);
            }
        }
        if (text == null)
        {
            counter.setVisibility(View.GONE);
        }
        else
        {
            setText(counter, text);
            counter.setTextColor(AirUi.color(activity, over ? R.color.kq_danger : R.color.kq_ink_3));
            counter.setVisibility(View.VISIBLE);
        }
        giveCounterTheLine(over);
        // Send waits over the limit: the button stays, dimmed, and says why when tapped.
        micSend.setAlpha(over ? 0.4f : 1f);
        updateHintRow();
    }

    /**
     * The bytes by which {@code message}, as it would go, is over the group limit; 0 when it fits. Send
     * calls it with the text, the mentions and the reply header put together.
     */
    static int bytesOver(final String message)
    {
        return Math.max(0, utf8Length(message) - AirGroupModel.COMPOSE_LIMIT_BYTES);
    }

    /** Send was tapped over the limit: the counter says it, and TalkBack hears it. */
    void sayOverLimit(final int bytes)
    {
        final String text = quantity(R.plurals.air_compose_bytes_over_group, bytes);
        setText(counter, text);
        counter.setTextColor(AirUi.color(activity, R.color.kq_danger));
        counter.setVisibility(View.VISIBLE);
        giveCounterTheLine(true);
        updateHintRow();
        // The counter under the field already says it in the danger colour; an error is not a toast (E21).
        counter.announceForAccessibility(text);
    }

    /**
     * Over the limit the counter takes the line under the field: it is then the one thing to act on.
     * Beside the hint a long sentence was clipped, because a weighted hint makes LinearLayout measure
     * the counter as if the row were empty.
     */
    private void giveCounterTheLine(final boolean over)
    {
        hint.setVisibility(over && !notice.isShown() ? View.GONE : View.VISIBLE);
        if (over)
        {
            hintDot.setVisibility(View.GONE);
        }
        else if (shown != null)
        {
            showHint(shown.reach);
        }
        AirChatPanel.fitCounter(counter, over);
    }

    private void updateHintRow()
    {
        if (composer.getVisibility() != View.VISIBLE)
        {
            hintRow.setVisibility(View.GONE);
            return;
        }
        final boolean hasHint = hint.getVisibility() == View.VISIBLE && hint.getText().length() > 0;
        final boolean hasCounter = counter.getVisibility() == View.VISIBLE;
        hintRow.setVisibility(hasHint || hasCounter ? View.VISIBLE : View.GONE);
        final StringBuilder spoken = new StringBuilder();
        if (hasHint)
        {
            appendSentence(spoken, hint.getText().toString());
        }
        if (hasCounter)
        {
            appendSentence(spoken, counter.getText().toString());
        }
        setDescription(hintRow, spoken.toString());
    }

    // ---- asking for the right to write ----

    /**
     * "Write to a moderator": a private message in the group to the one moderator, or a choice among
     * them, online first, when there are several.
     */
    private void askModerator()
    {
        final Snapshot s = shown;
        if (s == null || !s.right.asks())
        {
            return;
        }
        final List<AirGroupModel.Member> moderators = AirGroupModel.moderators(s.members);
        if (moderators.isEmpty())
        {
            return;
        }
        if (moderators.size() == 1)
        {
            final AirGroupModel.Member only = moderators.get(0);
            GroupMemberActions.showPrivateMessageDialog(activity, s.groupId, only.publicKey, only.name);
            return;
        }
        final long now = System.currentTimeMillis();
        final CharSequence[] items = new CharSequence[moderators.size()];
        for (int i = 0; i < moderators.size(); i++)
        {
            final AirGroupModel.Member member = moderators.get(i);
            items[i] = member.name + ", " + statusLine(activity, member, now);
        }
        new AlertDialog.Builder(activity).setTitle(activity.getString(
                s.right.asksFounder() ? R.string.air_group_ask_founder : R.string.air_group_ask_moderator))
                .setItems(items, (dialog, which) -> {
                    final AirGroupModel.Member member = moderators.get(which);
                    GroupMemberActions.showPrivateMessageDialog(activity, s.groupId, member.publicKey, member.name);
                }).setNegativeButton(R.string.cancel, null).show();
    }

    /** The second line of a member: the role, then "online" or how long ago this device saw them. */
    static String statusLine(final Context context, final AirGroupModel.Member member, final long nowMs)
    {
        final String presence;
        if (member.online)
        {
            presence = context.getString(R.string.air_reach_live);
        }
        else if (member.lastSeenMs > 0L)
        {
            presence = AirUi.awayText(context, Math.max(0L, nowMs - member.lastSeenMs) / 1000L);
        }
        else
        {
            presence = context.getString(R.string.air_group_member_offline);
        }
        if (!AirGroupModel.isKnownRole(member.role))
        {
            return presence;
        }
        return context.getString(R.string.air_group_member_status_format, roleName(context, member.role), presence);
    }

    /** The role in words: "founder", "moderator", "member", "observer"; empty when toxcore did not say. */
    static String roleName(final Context context, final int role)
    {
        switch (role)
        {
            case AirGroupModel.ROLE_FOUNDER:
                return context.getString(R.string.air_group_role_founder);
            case AirGroupModel.ROLE_MODERATOR:
                return context.getString(R.string.air_group_role_moderator);
            case AirGroupModel.ROLE_USER:
                return context.getString(R.string.air_group_role_user);
            case AirGroupModel.ROLE_OBSERVER:
                return context.getString(R.string.air_group_role_observer);
            default:
                return "";
        }
    }

    // ---- small helpers ----

    private String quantity(final int plural, final int count)
    {
        return activity.getResources().getQuantityString(plural, count, count);
    }

    private static int utf8Length(final String text)
    {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Sets a text only when it differs, so an unchanged read neither lays out nor speaks. */
    private static void setText(final TextView view, final CharSequence text)
    {
        if (!TextUtils.equals(view.getText(), text))
        {
            view.setText(text);
        }
    }

    private static void setDescription(final View view, final CharSequence text)
    {
        if (!TextUtils.equals(view.getContentDescription(), text))
        {
            view.setContentDescription(text);
        }
    }

    /** Marks that already end a sentence, in the four languages of the app. */
    private static final String SENTENCE_ENDS = ".!?\u3002\uff01\uff1f\u061f";

    private static void appendSentence(final StringBuilder sentence, final String part)
    {
        if (part == null || part.trim().isEmpty())
        {
            return;
        }
        if (sentence.length() > 0)
        {
            // A group name may end a sentence of its own ("... answers."): no second stop.
            final char last = sentence.charAt(sentence.length() - 1);
            sentence.append(SENTENCE_ENDS.indexOf(last) >= 0 ? " " : ". ");
        }
        sentence.append(part);
    }
}

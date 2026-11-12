package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import com.zoffcc.applications.sorm.FriendList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, E15): New group as one screen instead of two steps, as AirNewGroupController on iOS
 * and design/DESIGN.md "Groups on the phone". The name (one line, up to 48 bytes, not changeable later),
 * who can join with what each choice means, the contacts online to invite, and "Create group" on a bar
 * above the keyboard with the reason it waits. The group is named and closed as the founder chose before
 * it exists; a password stays in the group settings.
 */
public final class AirNewGroupActivity extends AirScreenActivity
{
    /** Whether "By invitation only" is chosen when the screen opens (true unless the caller says open). */
    static final String EXTRA_INVITE_ONLY = "org.khandaq.messenger.NEW_GROUP_INVITE_ONLY";

    /** The search over the contacts shows from this many contacts on, as on iOS. */
    private static final int SEARCH_FROM = 8;
    private static final String STATE_INVITE_ONLY = "air_ng_invite_only";
    private static final String STATE_CHOSEN = "air_ng_chosen";

    private EditText nameField;
    private EditText searchField;
    private LinearLayout inviteOnlyRow;
    private LinearLayout openRow;
    private LinearLayout plate;
    private TextView emptyNote;
    private TextView create;
    private TextView why;

    private boolean inviteOnly = true;
    /** The chosen contacts by key: a friend number is only a slot of this session. */
    private final Set<String> chosen = new LinkedHashSet<>();
    private List<AirGroupModel.InviteCandidate> candidates = new ArrayList<>();

    /** Opens the screen with "By invitation only" or "Open to anyone with the group key" chosen. */
    static void open(final Context context, final boolean inviteOnly)
    {
        final Intent intent = new Intent(context, AirNewGroupActivity.class);
        intent.putExtra(EXTRA_INVITE_ONLY, inviteOnly);
        context.startActivity(intent);
    }

    @Override
    protected int screenTitle()
    {
        return R.string.air_new_group_title;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        inviteOnly = getIntent().getBooleanExtra(EXTRA_INVITE_ONLY, true);
        if (savedInstanceState != null)
        {
            inviteOnly = savedInstanceState.getBoolean(STATE_INVITE_ONLY, inviteOnly);
            final String[] kept = savedInstanceState.getStringArray(STATE_CHOSEN);
            if (kept != null)
            {
                chosen.addAll(Arrays.asList(kept));
            }
        }
        candidates = loadCandidates();
        // Only those who can be invited now stay chosen: a contact may have gone offline meanwhile.
        final Set<String> invitable = new LinkedHashSet<>();
        for (final AirGroupModel.InviteCandidate candidate : candidates)
        {
            if (candidate.canInvite())
            {
                invitable.add(candidate.publicKey);
            }
        }
        chosen.retainAll(invitable);

        buildName();
        buildPrivacy();
        buildInvite();
        buildCreateBar();
        updateChoice();
        reloadList();
        updateButton();
        if (savedInstanceState == null)
        {
            nameField.requestFocus();
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState)
    {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_INVITE_ONLY, inviteOnly);
        outState.putStringArray(STATE_CHOSEN, chosen.toArray(new String[0]));
    }

    // ---- the parts ----

    private void buildName()
    {
        final TextView label = AirScreenKit.heading(this, getString(R.string.air_new_group_name));
        // A name is written as the founder means it: no correction of a word the dictionary lacks.
        nameField = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                          | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, getString(R.string.air_new_group_name_placeholder));
        nameField.setId(R.id.air_ng_name_field);
        label.setLabelFor(nameField.getId());
        nameField.setSingleLine(true);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameField.addTextChangedListener(new TextWatcher()
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
                updateButton();
            }
        });
        final LinearLayout section = AirScreenKit.addSection(column, null, getString(R.string.air_new_group_name_note),
                                                             nameField);
        section.addView(label, 0, AirScreenKit.matchWidth());
        ((LinearLayout.LayoutParams) nameField.getLayoutParams()).topMargin =
                getResources().getDimensionPixelSize(R.dimen.kq_space_8);
    }

    private void buildPrivacy()
    {
        inviteOnlyRow = AirScreenKit.choiceRow(this, getString(R.string.air_group_privacy_invite_only),
                                               getString(R.string.air_group_privacy_invite_only_note));
        openRow = AirScreenKit.choiceRow(this, getString(R.string.air_group_privacy_open),
                                         getString(R.string.air_group_privacy_open_note));
        inviteOnlyRow.setOnClickListener(v -> {
            inviteOnly = true;
            updateChoice();
        });
        openRow.setOnClickListener(v -> {
            inviteOnly = false;
            updateChoice();
        });
        final LinearLayout choices = AirScreenKit.plate(this);
        choices.addView(inviteOnlyRow, AirScreenKit.matchWidth());
        choices.addView(rule());
        choices.addView(openRow, AirScreenKit.matchWidth());
        AirScreenKit.addSection(column, getString(R.string.air_new_group_privacy), null, choices);
    }

    private void buildInvite()
    {
        searchField = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                            getString(R.string.air_invite_search));
        searchField.setId(R.id.air_ng_search_field);
        searchField.setSingleLine(true);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setContentDescription(getString(R.string.air_invite_search));
        searchField.setVisibility(candidates.size() < SEARCH_FROM ? View.GONE : View.VISIBLE);
        searchField.addTextChangedListener(new TextWatcher()
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
                reloadList();
            }
        });
        plate = AirScreenKit.plate(this);
        emptyNote = AirScreenKit.note(this, "");
        AirScreenKit.addSection(column, getString(R.string.air_group_invite),
                                getString(R.string.air_new_group_invite_note), searchField, plate, emptyNote);
    }

    private void buildCreateBar()
    {
        final LinearLayout bottom = findViewById(R.id.air_screen_bottom);
        why = AirScreenKit.note(this, "");
        create = AirScreenKit.button(this, getString(R.string.air_new_group_create), true);
        create.setOnClickListener(v -> createGroup());
        // TalkBack hears why the button is off as its hint, as on New contact.
        ViewCompat.setAccessibilityDelegate(create, new AccessibilityDelegateCompat()
        {
            @Override
            public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfoCompat info)
            {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                if (!host.isEnabled() && why.getVisibility() == View.VISIBLE)
                {
                    info.setHintText(why.getText());
                }
            }
        });
        bottom.addView(create, AirScreenKit.matchWidth());
        final LinearLayout.LayoutParams whyParams = AirScreenKit.matchWidth();
        whyParams.topMargin = getResources().getDimensionPixelSize(R.dimen.kq_space_6);
        bottom.addView(why, whyParams);
        bottom.setVisibility(View.VISIBLE);
    }

    // ---- state ----

    private void updateChoice()
    {
        AirScreenKit.applyChoice(inviteOnlyRow, inviteOnly);
        AirScreenKit.applyChoice(openRow, !inviteOnly);
    }

    /** "Create group" waits for a name that fits; the line under it says why. */
    private void updateButton()
    {
        final String name = AirGroupModel.cleanName(String.valueOf(nameField.getText()));
        final String reason;
        if (name.isEmpty())
        {
            reason = getString(R.string.air_new_group_name_first);
        }
        else if (AirGroupModel.nameOverflow(name) > 0)
        {
            reason = getString(R.string.air_new_group_name_too_long);
        }
        else
        {
            reason = null;
        }
        create.setEnabled(reason == null);
        create.setText(chosen.isEmpty() ? getString(R.string.air_new_group_create)
                                        : getString(R.string.air_new_group_create_invite_format, chosen.size()));
        why.setText(reason);
        why.setVisibility(reason == null ? View.GONE : View.VISIBLE);
    }

    private void reloadList()
    {
        plate.removeAllViews();
        final String query = searchField.getVisibility() == View.VISIBLE ? String.valueOf(searchField.getText()) : "";
        final List<AirGroupModel.InviteCandidate> shown = AirGroupModel.arranged(candidates, query);
        for (int i = 0; i < shown.size(); i++)
        {
            if (i > 0)
            {
                plate.addView(rule());
            }
            plate.addView(inviteRow(shown.get(i)), AirScreenKit.matchWidth());
        }
        plate.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        final String note;
        if (candidates.isEmpty())
        {
            note = getString(R.string.air_invite_no_contacts);
        }
        else if (shown.isEmpty())
        {
            note = getString(R.string.air_invite_nobody_found);
        }
        else
        {
            note = null;
        }
        emptyNote.setText(note);
        emptyNote.setVisibility(note == null ? View.GONE : View.VISIBLE);
    }

    // ---- creating ----

    private void createGroup()
    {
        final String name = AirGroupModel.cleanName(String.valueOf(nameField.getText()));
        if (name.isEmpty() || AirGroupModel.nameOverflow(name) > 0)
        {
            updateButton();
            return;
        }
        create.setEnabled(false);
        final int privacy = inviteOnly ? ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value
                                       : ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value;
        // The service lines the two old steps stored, so a group made here reads as one made there.
        // E21: a group that was not created is said under the button, not in a toast.
        final String[] failure = new String[1];
        final String created = HelperGroup.create_new_group_id(
                privacy, name, 0, inviteOnly ? R.string.add_private_group_failed : R.string.add_public_group_failed,
                inviteOnly ? "You created the Group (as Private [invite only] Group)"
                           : "You created the Group (as Public Group)", null, text -> failure[0] = text);
        if (created == null)
        {
            updateButton();
            if (!TextUtils.isEmpty(failure[0]))
            {
                AirUi.sayOnLine(why, failure[0], true);
            }
            return;
        }
        final List<String> invite = new ArrayList<>();
        for (final AirGroupModel.InviteCandidate candidate : candidates)
        {
            if (candidate.canInvite() && chosen.contains(candidate.publicKey))
            {
                invite.add(candidate.publicKey);
            }
        }
        final int sent = invite.isEmpty() ? 0 : HelperGroup.invite_friends_to_new_group(created, invite);
        AirRepository.requestRefresh();
        GroupListHolder.open_group_chat(this, created);
        if (sent > 0)
        {
            HelperGeneric.display_toast(getResources().getQuantityString(R.plurals.air_invite_sent, sent, sent), false,
                                        300);
        }
        finish();
    }

    /** The contacts that can be asked, as on iOS: online ones can be invited now, the rest wait. */
    private List<AirGroupModel.InviteCandidate> loadCandidates()
    {
        final List<AirGroupModel.InviteCandidate> list = new ArrayList<>();
        try
        {
            for (final FriendList friend : TrifaToxService.orma.selectFromFriendList().is_relayNotEq(true).toList())
            {
                final String key = friend.tox_public_key_string;
                if (TextUtils.isEmpty(key) || HelperFriend.is_own_public_key(key) || FavoritesChatHelper.isFavoritesChat(
                        key) || AirBlockList.isBlocked(key))
                {
                    continue;
                }
                String name = HelperFriend.display_name_from_friendlist(friend, key);
                if (TextUtils.isEmpty(name))
                {
                    name = HelperFriend.peer_pubkey_short_id(key);
                }
                list.add(new AirGroupModel.InviteCandidate(HelperFriend.tox_friend_by_public_key__wrapper(key), key, name,
                                                           FriendPresenceHelper.is_friend_online(friend), false));
            }
        }
        catch (Throwable e)
        {
            HelperGeneric.logI("trifa.AirNewGroup", "loadCandidates:EE:" + e.getMessage());
        }
        return list;
    }

    // ---- rows ----

    private EditText field(final int inputType, final CharSequence hint)
    {
        final EditText field = new EditText(this);
        field.setInputType(inputType);
        TextViewCompat.setTextAppearance(field, R.style.TextAppearance_KQ_Body);
        field.setTextColor(AirUi.color(this, R.color.kq_ink));
        field.setHintTextColor(AirUi.color(this, R.color.kq_ink_3));
        field.setHint(hint);
        field.setBackgroundResource(R.drawable.air_bg_input);
        final int pad = getResources().getDimensionPixelSize(R.dimen.kq_space_12);
        field.setPaddingRelative(pad, pad, pad, pad);
        field.setMinHeight(getResources().getDimensionPixelSize(R.dimen.kq_size_target_android));
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return field;
    }

    /** A contact: tile, name, the note when they cannot be invited now, and the check mark to choose them. */
    private View inviteRow(final AirGroupModel.InviteCandidate candidate)
    {
        final Resources res = getResources();
        final boolean enabled = candidate.canInvite();
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.air_bg_row);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_16);
        final int vertical = res.getDimensionPixelSize(R.dimen.kq_space_10);
        row.setPaddingRelative(side, vertical, side, vertical);
        row.setMinimumHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android));

        final AirTileView tile = new AirTileView(this);
        tile.bind(AirTileView.Kind.PERSON, candidate.name, AirModel.Tone.index(candidate.publicKey));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final int tileSize = res.getDimensionPixelSize(R.dimen.kq_size_tile_phone);
        row.addView(tile, new LinearLayout.LayoutParams(tileSize, tileSize));

        final LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = AirScreenKit.text(this, R.style.TextAppearance_KQ_Body,
                                                enabled ? R.color.kq_ink : R.color.kq_ink_3);
        name.setText(candidate.name);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name, AirScreenKit.matchWidth());
        final String note = noteFor(candidate);
        if (note != null)
        {
            final TextView noteView = AirScreenKit.text(this, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
            noteView.setText(note);
            final LinearLayout.LayoutParams noteParams = AirScreenKit.matchWidth();
            noteParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_2);
            texts.addView(noteView, noteParams);
        }
        final LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0,
                                                                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                                                                    1f);
        textsParams.setMarginStart(res.getDimensionPixelSize(R.dimen.kq_space_12));
        textsParams.setMarginEnd(res.getDimensionPixelSize(R.dimen.kq_space_12));
        row.addView(texts, textsParams);

        final String spoken = note == null ? candidate.name : candidate.name + ", " + note;
        row.setContentDescription(spoken);
        if (enabled)
        {
            final FrameLayout mark = new FrameLayout(this);
            final ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.kq_ic_check);
            ImageViewCompat.setImageTintList(check, ColorStateList.valueOf(AirUi.color(this, R.color.kq_on_ink)));
            final int checkSize = res.getDimensionPixelSize(R.dimen.kq_size_icon_small);
            mark.addView(check, new FrameLayout.LayoutParams(checkSize, checkSize, Gravity.CENTER));
            final int markSize = res.getDimensionPixelSize(R.dimen.kq_size_icon);
            row.addView(mark, new LinearLayout.LayoutParams(markSize, markSize));
            applyMark(mark, chosen.contains(candidate.publicKey));

            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                if (!chosen.remove(candidate.publicKey))
                {
                    chosen.add(candidate.publicKey);
                }
                applyMark(mark, chosen.contains(candidate.publicKey));
                updateButton();
            });
            ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat()
            {
                @Override
                public void onInitializeAccessibilityNodeInfo(final View host,
                                                              final AccessibilityNodeInfoCompat info)
                {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(CheckBox.class.getName());
                    info.setCheckable(true);
                    info.setChecked(chosen.contains(candidate.publicKey));
                }
            });
        }
        else
        {
            // Read, not offered: the note says why.
            row.setFocusable(true);
        }
        return row;
    }

    /** The round mark of a contact: an outline, or filled in ink with the check while chosen. */
    private void applyMark(final FrameLayout mark, final boolean isChosen)
    {
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(getResources().getDimensionPixelSize(R.dimen.kq_stroke_strong),
                       AirUi.color(this, isChosen ? R.color.kq_ink : R.color.kq_line_strong));
        ring.setColor(isChosen ? AirUi.color(this, R.color.kq_ink) : android.graphics.Color.TRANSPARENT);
        mark.setBackground(ring);
        mark.getChildAt(0).setVisibility(isChosen ? View.VISIBLE : View.INVISIBLE);
    }

    @Nullable
    private String noteFor(final AirGroupModel.InviteCandidate candidate)
    {
        switch (AirGroupModel.InviteNote.of(candidate))
        {
            case ALREADY_MEMBER:
                return getString(R.string.air_invite_already_member);
            case WHEN_ONLINE:
                return getString(R.string.air_invite_when_online);
            case NONE:
            default:
                return candidate.online ? getString(R.string.air_reach_live) : null;
        }
    }

    /** The hairline between rows of a plate, from the text's start as in the lists. */
    private View rule()
    {
        final View rule = new View(this);
        rule.setBackgroundColor(AirUi.color(this, R.color.kq_line));
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                                                                               getResources().getDimensionPixelSize(
                                                                                       R.dimen.kq_stroke_hairline));
        params.setMarginStart(getResources().getDimensionPixelSize(R.dimen.kq_space_16));
        rule.setLayoutParams(params);
        return rule;
    }
}

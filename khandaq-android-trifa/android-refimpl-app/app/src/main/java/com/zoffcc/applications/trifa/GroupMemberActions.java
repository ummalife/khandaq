package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.zoffcc.applications.sorm.GroupMessage;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.widget.NestedScrollView;
import androidx.core.widget.TextViewCompat;

import static com.zoffcc.applications.trifa.FriendListHolder.show_messagelist_acticvity_for_friend;
import static com.zoffcc.applications.trifa.HelperFriend.add_friend_real;
import static com.zoffcc.applications.trifa.HelperFriend.lookup_friendlist_by_pubkey;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGroup.get_group_peernum_from_peer_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.group_notification_silent_peer_get;
import static com.zoffcc.applications.trifa.HelperGroup.group_notification_silent_peer_set;
import static com.zoffcc.applications.trifa.HelperGroup.insert_into_group_message_db;
import static com.zoffcc.applications.trifa.HelperGroup.is_likely_automated_test_peer;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_in_groupmessagelist;
import static com.zoffcc.applications.trifa.HelperGroup.update_group_peer_in_db;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_mod_kick_peer;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_mod_set_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_public_key;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_role;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_send_private_message_by_peerpubkey;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.open_group_peer_info_activity;

final class GroupMemberActions
{
    private static final String TAG = "trifa.GroupMemberActs";
    /**
     * Where a refusal of an action is said on the old group screen, which no longer opens: its member
     * list. The group dossier and the member's own screen have lines of their own (sayRefusal).
     */
    private static final int[] OLDER_PLACES = {R.id.group_info_members_list};
    /**
     * KHANDAQ (stage 5): private messages typed and not sent, by group and member, for as long as the app
     * runs. Leaving the member's screen or the sheet keeps the words for the next time, in either of
     * them; only Send sends them. Nothing is written to disk, so a deleted group or profile leaves no
     * draft behind.
     */
    private static final Map<String, String> PRIVATE_DRAFTS = new ConcurrentHashMap<>();

    private GroupMemberActions()
    {
    }

    static void showActionMenu(final Context context, final String group_id, final String peer_pubkey,
                               final String display_name)
    {
        showActionMenu(context, group_id, peer_pubkey, display_name, null);
    }

    /**
     * The member's actions, from below (redesign stage 5), as GroupPeerActionsPresenter on iOS: the
     * name, then the choices of AirGroupMemberChoices.sheet as rows, removing in the danger colour, and
     * Cancel apart. {@code after} runs once toxcore took a change of role or a removal, and again after
     * an Undo, so the group dossier can show the member in their new place.
     */
    static void showActionMenu(final Context context, final String group_id, final String peer_pubkey,
                               final String display_name, @Nullable final Runnable after)
    {
        if ((context == null) || (group_id == null) || (peer_pubkey == null))
        {
            return;
        }

        final long group_num = tox_group_by_groupid__wrapper(group_id);
        if (group_num < 0)
        {
            return;
        }

        final boolean is_self;
        try
        {
            is_self = peer_pubkey.equalsIgnoreCase(tox_group_self_get_public_key(group_num));
        }
        catch (Exception e)
        {
            return;
        }

        if (is_self)
        {
            return;
        }

        final boolean is_friend = lookup_friendlist_by_pubkey(peer_pubkey) != null;
        final int self_role = tox_group_self_get_role(group_num);
        // KHANDAQ (redesign, E14): toxcore's role while the member is in the peer list, else the one stored
        // here; an unknown role offers no role or removal action at all.
        final int peer_role = AirGroupPanel.roleOf(group_id, group_num, peer_pubkey);
        final boolean muted = group_notification_silent_peer_get(group_id, peer_pubkey);
        final boolean probable_bot = is_likely_automated_test_peer(display_name, peer_pubkey);

        final AirSheet sheet = AirSheet.create(context, display_name, null);
        if (sheet == null)
        {
            return;
        }
        final LinearLayout plate = AirScreenKit.plate(sheet.context);
        for (final AirGroupMemberChoices.Choice choice : AirGroupMemberChoices.sheet(is_friend, muted, peer_role,
                                                                                    self_role, probable_bot))
        {
            final AirSettingRow row = new AirSettingRow(sheet.context, context.getString(choiceLabel(choice)), false);
            if (choice == AirGroupMemberChoices.Choice.KICK)
            {
                row.danger();
            }
            // The sheet goes first, then the choice acts, so whatever it opens comes in over the screen.
            row.setOnClickListener(v -> sheet.dismissThen(
                    () -> runChoice(context, choice, group_id, peer_pubkey, display_name, peer_role, after)));
            plate.addView(row, AirScreenKit.matchWidth());
        }
        // A long list (the founder's, a large font, a phone on its side) scrolls between the name and
        // Cancel instead of running off the screen; a short one keeps its own height.
        final NestedScrollView scroll = new NestedScrollView(sheet.context);
        scroll.addView(plate, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                           ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.add(scroll, R.dimen.kq_space_12);
        final LinearLayout.LayoutParams scrollParams = (LinearLayout.LayoutParams) scroll.getLayoutParams();
        scrollParams.height = 0;
        scrollParams.weight = 1f;
        scroll.setLayoutParams(scrollParams);
        sheet.cancel(null);
        // Every choice in sight at once, also where the sheet would otherwise open half way.
        final BottomSheetBehavior<?> behavior = sheet.dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
    }

    /** The words of a choice, on the sheet and on the member's own screen. */
    @StringRes
    static int choiceLabel(final AirGroupMemberChoices.Choice choice)
    {
        switch (choice)
        {
            case PRIVATE_MESSAGE:
                return R.string.group_member_action_private_msg;
            case OPEN_CHAT:
                return R.string.group_member_action_open_chat;
            case ADD_FRIEND:
                return R.string.group_member_action_add_friend;
            case COPY_ID:
                return R.string.group_member_action_copy_id;
            case MUTE:
                return R.string.air_group_member_mute;
            case UNMUTE:
                return R.string.air_group_member_unmute;
            case DETAILS:
                return R.string.air_group_member_details;
            case BOT_INFO:
                return R.string.group_member_action_bot_info;
            default:
                final AirGroupModel.MemberAction action = AirGroupMemberChoices.action(choice);
                return actionLabel(action == null ? AirGroupModel.MemberAction.KICK : action);
        }
    }

    private static void runChoice(final Context context, final AirGroupMemberChoices.Choice choice,
                                  final String group_id, final String peer_pubkey, final String display_name,
                                  final int peer_role, @Nullable final Runnable after)
    {
        try
        {
            switch (choice)
            {
                case PRIVATE_MESSAGE:
                    showPrivateMessageDialog(context, group_id, peer_pubkey, display_name);
                    break;
                case OPEN_CHAT:
                    show_messagelist_acticvity_for_friend(context, peer_pubkey);
                    break;
                case ADD_FRIEND:
                    addFriend(context, peer_pubkey);
                    break;
                case COPY_ID:
                    copyToClipboard(context, peer_pubkey);
                    break;
                case MUTE:
                case UNMUTE:
                {
                    final boolean mute = choice == AirGroupMemberChoices.Choice.MUTE;
                    setMemberMuted(group_id, peer_pubkey, mute);
                    toast(context, context.getString(mute ? R.string.air_group_member_muted_format
                                                          : R.string.air_group_member_unmuted_format, display_name),
                          null);
                    break;
                }
                case DETAILS:
                    open_group_peer_info_activity(context, peer_pubkey, group_id);
                    break;
                case BOT_INFO:
                    showBotInfoDialog(context, peer_pubkey);
                    break;
                default:
                {
                    final AirGroupModel.MemberAction action = AirGroupMemberChoices.action(choice);
                    if (action != null)
                    {
                        confirmMemberAction(context, group_id, peer_pubkey, display_name, action, peer_role, after);
                    }
                    break;
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /** Adds the member as a contact by their key; what stops it is said where the member was chosen. */
    static void addFriend(final Context context, final String peer_pubkey)
    {
        add_friend_real(peer_pubkey.toUpperCase(), new AirComposerNotice.Field()
        {
            @Override
            public boolean say(final CharSequence text, final boolean failed)
            {
                return sayRefusal(context, text, failed, OLDER_PLACES, null);
            }

            @Override
            public boolean say(final CharSequence text, final boolean failed, @Nullable final String code)
            {
                return sayRefusal(context, text, failed, OLDER_PLACES, code);
            }
        });
    }

    /** The member's messages in this group come with or without a notification on this device. */
    static void setMemberMuted(final String group_id, final String peer_pubkey, final boolean muted)
    {
        group_notification_silent_peer_set(group_id, peer_pubkey, muted);
        update_group_in_groupmessagelist(group_id);
    }

    /**
     * A private message to one member, from below (also "Write to a moderator" under a read-only group,
     * AirGroupPanel). Only Send sends it. Closing the sheet any other way keeps the words for the next
     * time, here or on the member's own screen; a message that did not go is said under the field with
     * the words still in it (E21). An observer gets the sheet with the field off and the reason under it
     * ({@link #cannotWritePrivately}): toxcore takes no private message from them.
     */
    static void showPrivateMessageDialog(final Context context, final String group_id,
                                         final String peer_pubkey, final String display_name)
    {
        final AirSheet sheet = AirSheet.create(context, context.getString(R.string.air_group_private_to_format,
                                                                          display_name), null);
        if (sheet == null)
        {
            return;
        }
        final EditText input = privateMessageField(sheet.context);
        final String draft = privateDraft(group_id, peer_pubkey);
        if (draft != null)
        {
            input.setText(draft);
            input.setSelection(input.length());
        }
        sheet.add(input, R.dimen.kq_space_16);
        input.addTextChangedListener(new TextWatcher()
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
                // The words under the field were about the text before this change.
                AirUi.endUnder(input);
            }
        });
        final boolean writes = AirGroupModel.writesPrivately(AirGroupPanel.selfRole(group_id));
        final TextView send = sheet.verb(context.getString(R.string.group_member_action_send), false, v -> {
            final String text = input.getText().toString().trim();
            if (text.isEmpty())
            {
                return;
            }
            if (!AirGroupModel.writesPrivately(AirGroupPanel.selfRole(group_id)))
            {
                // Made an observer while the sheet stood open: the words stay, nothing tries to go.
                cannotWritePrivately(input, v);
                return;
            }
            if (sendPrivateGroupMessage(group_id, peer_pubkey, text))
            {
                // Sent: no draft is left, and the screen under the sheet says it went.
                keepPrivateDraft(group_id, peer_pubkey, null);
                sheet.dismissThen(() -> toast(context, context.getString(R.string.group_member_action_private_msg_sent),
                                              null));
                return;
            }
            AirUi.sayUnder(input, context.getString(R.string.air_group_private_not_sent), true);
        });
        sheet.cancel(null);
        // Cancel, Back, a tap outside and a swipe down all close the sheet through here.
        sheet.dialog.setOnDismissListener(d -> keepPrivateDraft(group_id, peer_pubkey, input.getText()));
        if (!writes)
        {
            input.setEnabled(false);
            send.setEnabled(false);
        }
        if (!sheet.withKeyboard().show())
        {
            return;
        }
        if (writes)
        {
            AirStepKit.showKeyboard(input);
        }
        else
        {
            cannotWritePrivately(input, send);
        }
    }

    /**
     * Where you are an observer: the private message field and its Send are off, and the line under
     * the field says why in the words of the group's read-only bar. toxcore refuses every private
     * message of an observer (AirGroupModel.writesPrivately), so nothing is sent to find that out.
     */
    static void cannotWritePrivately(final EditText field, final View send)
    {
        field.setEnabled(false);
        send.setEnabled(false);
        AirUi.sayUnder(field, field.getContext().getString(R.string.air_group_observer_no_private), false);
    }

    /** The field of a private message: framed as the design's inputs, a few lines high, learning nothing. */
    static EditText privateMessageField(final Context context)
    {
        final EditText field = new EditText(context);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                           | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        TextViewCompat.setTextAppearance(field, R.style.TextAppearance_KQ_Body);
        // Words kept in a field that is off (an observer's) read as waiting, in the third ink.
        field.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                                              new int[]{AirUi.color(context, R.color.kq_ink_3),
                                                        AirUi.color(context, R.color.kq_ink)}));
        field.setHintTextColor(AirUi.color(context, R.color.kq_ink_3));
        field.setHint(R.string.group_private_msg_hint);
        field.setBackgroundResource(R.drawable.air_bg_input);
        final int pad = context.getResources().getDimensionPixelSize(R.dimen.kq_space_12);
        field.setPaddingRelative(pad, pad, pad, pad);
        field.setMinHeight(context.getResources().getDimensionPixelSize(R.dimen.kq_size_target_android));
        field.setMaxLines(6);
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return field;
    }

    private static String privateDraftKey(@Nullable final String group_id, @Nullable final String peer_pubkey)
    {
        return (group_id == null ? "" : group_id.toLowerCase(Locale.ROOT)) + "/"
               + (peer_pubkey == null ? "" : peer_pubkey.toUpperCase(Locale.ROOT));
    }

    /** The words typed to this member in this group and not sent, or null. */
    @Nullable
    static String privateDraft(@Nullable final String group_id, @Nullable final String peer_pubkey)
    {
        return PRIVATE_DRAFTS.get(privateDraftKey(group_id, peer_pubkey));
    }

    /** Keeps what stands in the field as the draft; nothing, or only spaces, leaves none. */
    static void keepPrivateDraft(@Nullable final String group_id, @Nullable final String peer_pubkey,
                                 @Nullable final CharSequence text)
    {
        final String key = privateDraftKey(group_id, peer_pubkey);
        if (text == null || text.toString().trim().isEmpty())
        {
            PRIVATE_DRAFTS.remove(key);
        }
        else
        {
            PRIVATE_DRAFTS.put(key, text.toString());
        }
    }

    /**
     * Sends a private message to one member and keeps it in the group's history here; false when
     * toxcore did not take it. The caller says how it went where it was sent from.
     */
    static boolean sendPrivateGroupMessage(final String group_id, final String peer_pubkey, final String text)
    {
        if (group_id == null || peer_pubkey == null || text == null || text.isEmpty())
        {
            return false;
        }

        try
        {
            final long group_num = tox_group_by_groupid__wrapper(group_id);
            final int res = tox_group_send_private_message_by_peerpubkey(group_num, peer_pubkey, 0, text);
            Log.i(TAG, "send_private_message:res=" + res);
            if (res == 0)
            {
                GroupMessage m = new GroupMessage();
                m.is_new = false;
                m.tox_group_peer_pubkey = tox_group_self_get_public_key(group_num).toUpperCase();
                m.direction = 1;
                m.TOX_MESSAGE_TYPE = 0;
                m.read = true;
                m.tox_group_peername = null;
                m.sent_privately_to_tox_group_peer_pubkey = peer_pubkey;
                m.private_message = 1;
                m.group_identifier = group_id;
                m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
                m.sent_timestamp = System.currentTimeMillis();
                m.rcvd_timestamp = System.currentTimeMillis();
                m.text = text;
                m.was_synced = false;
                m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
                insert_into_group_message_db(m, true);
                return true;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return false;
    }

    private static void copyToClipboard(final Context context, final String text)
    {
        try
        {
            final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null)
            {
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("tox_pubkey", text));
            final String copied = context.getString(R.string.group_member_action_copy_done);
            // From Android 13 the system shows its own "copied" confirmation.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            {
                toast(context, copied, null);
            }
            else
            {
                final Activity activity = activityOf(context);
                final View content = activity == null ? null : activity.findViewById(android.R.id.content);
                if (content != null)
                {
                    content.announceForAccessibility(copied);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    /**
     * KHANDAQ (redesign, E14): a change of role or a removal, asked once in one sentence that starts with
     * the name, so no language has to decline it. A change of role can be undone from the toast for five
     * seconds; a removal has none, the member needs a new invitation. {@code after} runs once toxcore took
     * the change, and again after an Undo.
     */
    static void confirmMemberAction(final Context context, final String group_id, final String peer_pubkey,
                                    final String display_name, final AirGroupModel.MemberAction action,
                                    final int peer_role, @Nullable final Runnable after)
    {
        final int title;
        final int message;
        switch (action)
        {
            case MAKE_MODERATOR:
                title = R.string.air_group_make_moderator_title_format;
                message = R.string.air_group_make_moderator_message;
                break;
            case MAKE_USER:
                title = R.string.air_group_make_user_title_format;
                message = R.string.air_group_make_user_message;
                break;
            case MAKE_OBSERVER:
                title = R.string.air_group_make_observer_title_format;
                message = R.string.air_group_make_observer_message;
                break;
            case KICK:
            default:
                title = R.string.air_group_kick_title_format;
                message = R.string.air_group_kick_message;
                break;
        }
        // E21: asked from below, the verb on the button; removing cannot be undone, so it is in the danger
        // colour.
        AirQuestion.ask(context, context.getString(title, display_name), context.getString(message),
                        context.getString(actionLabel(action)), action == AirGroupModel.MemberAction.KICK, () -> {
                    if (action == AirGroupModel.MemberAction.KICK)
                    {
                        kickPeer(context, group_id, peer_pubkey, display_name, after);
                    }
                    else
                    {
                        changeRole(context, group_id, peer_pubkey, display_name, action.role, peer_role, after);
                    }
                });
    }

    @StringRes
    static int actionLabel(final AirGroupModel.MemberAction action)
    {
        switch (action)
        {
            case MAKE_MODERATOR:
                return R.string.air_group_make_moderator;
            case MAKE_USER:
                return R.string.air_group_make_user;
            case MAKE_OBSERVER:
                return R.string.air_group_make_observer;
            case KICK:
            default:
                return R.string.air_group_kick;
        }
    }

    /** Gives the member the role; then the toast with Undo, which puts the old role back if still allowed. */
    private static void changeRole(final Context context, final String group_id, final String peer_pubkey,
                                   final String display_name, final int role, final int old_role,
                                   @Nullable final Runnable after)
    {
        final String failure = setRole(group_id, peer_pubkey, role);
        if (failure != null)
        {
            sayRefusal(context, context.getString(R.string.group_role_set_failed), true, OLDER_PLACES, failure);
            return;
        }
        if (after != null)
        {
            after.run();
        }
        final AirGroupModel.MemberAction back = AirGroupModel.MemberAction.giving(old_role);
        final Runnable undo = back == null ? null : () -> {
            final long group_num = tox_group_by_groupid__wrapper(group_id);
            // Our own role may have changed in the five seconds; toxcore would refuse, so ask the rules first,
            // and a refusal gives the code toxcore would have given.
            final String undo_failure;
            if (group_num < 0)
            {
                undo_failure = AirErrorCode.tox(AirErrorCode.Call.GROUP_SET_ROLE, AirErrorCode.TOX_GROUP_NOT_FOUND);
            }
            else if (!AirGroupModel.canSet(old_role, role, tox_group_self_get_role(group_num)))
            {
                undo_failure = AirErrorCode.tox(AirErrorCode.Call.GROUP_SET_ROLE, AirErrorCode.TOX_PERMISSIONS);
            }
            else
            {
                undo_failure = setRole(group_id, peer_pubkey, old_role);
            }
            if (undo_failure != null)
            {
                sayRefusal(context, context.getString(R.string.group_role_set_failed), true, OLDER_PLACES,
                           undo_failure);
                return;
            }
            if (after != null)
            {
                after.run();
            }
        };
        toast(context, context.getString(nowLabel(role), display_name), undo);
    }

    @StringRes
    private static int nowLabel(final int role)
    {
        switch (role)
        {
            case AirGroupModel.ROLE_MODERATOR:
                return R.string.air_group_now_moderator_format;
            case AirGroupModel.ROLE_OBSERVER:
                return R.string.air_group_now_observer_format;
            case AirGroupModel.ROLE_USER:
            default:
                return R.string.air_group_now_user_format;
        }
    }

    /**
     * toxcore's change of role, stored here too. Null when toxcore took it; otherwise the code of the
     * failure for Details (AirErrorCode), the one iOS gives: a group or a member this device cannot find
     * reads as toxcore's GROUP_NOT_FOUND or PEER_NOT_FOUND, which toxcore itself would say.
     */
    @Nullable
    private static String setRole(final String group_id, final String peer_pubkey, final int role)
    {
        try
        {
            final long group_num = tox_group_by_groupid__wrapper(group_id);
            final long peer_num = get_group_peernum_from_peer_pubkey(group_id, peer_pubkey);
            if (group_num < 0 || peer_num < 0)
            {
                final int missing = group_num < 0 ? AirErrorCode.TOX_GROUP_NOT_FOUND : AirErrorCode.TOX_PEER_NOT_FOUND;
                return AirErrorCode.tox(AirErrorCode.Call.GROUP_SET_ROLE, missing);
            }
            final int result = tox_group_mod_set_role(group_num, peer_num, role);
            HelperGeneric.update_savedata_file_wrapper_async();
            if (result != 1)
            {
                return AirErrorCode.toxResult(AirErrorCode.Call.GROUP_SET_ROLE, result);
            }
            update_group_peer_in_db(group_num, group_id, peer_num, peer_pubkey, role);
            update_group_in_groupmessagelist(group_id);
            return null;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return AirErrorCode.tox(AirErrorCode.Call.GROUP_SET_ROLE, 0);
        }
    }

    private static void kickPeer(final Context context, final String group_id, final String peer_pubkey,
                                 final String display_name, @Nullable final Runnable after)
    {
        try
        {
            final long group_num = tox_group_by_groupid__wrapper(group_id);
            final long peer_num = get_group_peernum_from_peer_pubkey(group_id, peer_pubkey);
            if (group_num < 0 || peer_num < 0)
            {
                final int missing = group_num < 0 ? AirErrorCode.TOX_GROUP_NOT_FOUND : AirErrorCode.TOX_PEER_NOT_FOUND;
                sayRefusal(context, context.getString(R.string.group_peer_kick_failed), true, OLDER_PLACES,
                           AirErrorCode.tox(AirErrorCode.Call.GROUP_KICK_PEER, missing));
                return;
            }

            final int result = tox_group_mod_kick_peer(group_num, peer_num);
            HelperGeneric.update_savedata_file_wrapper_async();
            if (result >= 0)
            {
                update_group_peer_in_db(group_num, group_id, peer_num, peer_pubkey,
                                        ToxVars.Tox_Group_Role.TOX_GROUP_ROLE_OBSERVER.value);
                update_group_in_groupmessagelist(group_id);
                if (after != null)
                {
                    after.run();
                }
                toast(context, context.getString(R.string.air_group_kicked_format, display_name), null);
            }
            else
            {
                sayRefusal(context, context.getString(R.string.group_peer_kick_failed), true, OLDER_PLACES,
                           AirErrorCode.toxResult(AirErrorCode.Call.GROUP_KICK_PEER, result));
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            sayRefusal(context, context.getString(R.string.group_peer_kick_failed), true, OLDER_PLACES,
                       AirErrorCode.tox(AirErrorCode.Call.GROUP_KICK_PEER, 0));
        }
    }

    /**
     * E21: what an action on a member could not do is said where it was chosen: under the members of the
     * group dossier, under the actions of the member's own screen, under the given places of an older
     * screen, or under the group's field. Only with none of them on screen does it come as a toast
     * (AirComposerNotice.say). A failure with a code (AirErrorCode) ends in Details wherever the words
     * stand, except in a toast. Always true, so it can stand as a refusal sink.
     */
    private static boolean sayRefusal(final Context context, final CharSequence text, final boolean failed,
                                      final int[] places, @Nullable final String code)
    {
        final Activity activity = activityOf(context);
        if (activity instanceof AirGroupDossierActivity
            && ((AirGroupDossierActivity) activity).sayAtMembers(text, failed, code))
        {
            return true;
        }
        if (activity instanceof GroupPeerInfoActivity
            && ((GroupPeerInfoActivity) activity).sayAtActions(text, failed, code))
        {
            return true;
        }
        if (activity != null && !activity.isFinishing())
        {
            for (final int place : places)
            {
                if (AirUi.sayUnder(activity.findViewById(place), text, failed, code))
                {
                    return true;
                }
            }
        }
        AirComposerNotice.say(text, failed, code);
        return true;
    }

    /** The design's toast over the screen the menu was opened from, with Undo when one is given. */
    private static void toast(final Context context, final CharSequence text, @Nullable final Runnable undo)
    {
        final Activity activity = activityOf(context);
        final View anchor = activity == null ? null : activity.findViewById(android.R.id.content);
        if (anchor == null)
        {
            display_toast(text.toString(), false, 200);
            return;
        }
        AirToast.show(anchor, text, undo == null ? AirToast.SHORT_MS : AirToast.UNDO_MS,
                      undo == null ? null : context.getString(R.string.air_undo), undo);
    }

    @Nullable
    private static Activity activityOf(final Context context)
    {
        Context current = context;
        while (current instanceof ContextWrapper)
        {
            if (current instanceof Activity)
            {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    /** What an automated test client in a public group is, from below, with its ID to copy. */
    private static void showBotInfoDialog(final Context context, final String peer_pubkey)
    {
        final AirSheet sheet = AirSheet.create(context, context.getString(R.string.group_member_action_bot_info_title),
                                               context.getString(R.string.group_member_action_bot_info_body,
                                                                 peer_pubkey));
        if (sheet == null)
        {
            return;
        }
        sheet.verbThen(context.getString(R.string.group_member_action_copy_id), false,
                       () -> copyToClipboard(context, peer_pubkey));
        sheet.cancel(null);
        sheet.show();
    }
}

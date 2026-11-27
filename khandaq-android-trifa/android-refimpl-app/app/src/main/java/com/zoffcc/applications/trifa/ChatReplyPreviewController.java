package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.vanniktech.emoji.EmojiEditText;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import androidx.annotation.Nullable;

import static com.zoffcc.applications.trifa.HelperFriend.resolve_name_for_pubkey;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_peer_get_name__wrapper;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;

/** Reply preview bar above the chat input (Telegram-style). */
final class ChatReplyPreviewController
{
    @Nullable
    private static MessageReplyHelper.ReplyMeta pendingReply;

    // KHANDAQ (#9 message edit): the same bar doubles as the edit banner (mutually exclusive with
    // reply). While set, the send button SAVES the edit instead of sending a new message.
    @Nullable
    private static Message pendingEditDirect;
    @Nullable
    private static GroupMessage pendingEditGroup;

    @Nullable
    private static View bar;
    @Nullable
    private static TextView headerView;
    @Nullable
    private static TextView previewView;
    @Nullable
    private static EmojiEditText inputField;

    private ChatReplyPreviewController()
    {
    }

    static void bind(final Activity activity, final EmojiEditText messageField)
    {
        inputField = messageField;
        bar = activity.findViewById(R.id.chat_reply_preview_bar);
        headerView = activity.findViewById(R.id.chat_reply_header);
        previewView = activity.findViewById(R.id.chat_reply_preview_text);
        final ImageButton cancel = activity.findViewById(R.id.chat_reply_cancel);
        if (cancel != null)
        {
            cancel.setOnClickListener(v -> clear());
        }
        applyTheme(activity);
        hideBar();
    }

    static void clear()
    {
        final boolean wasEdit = (pendingEditDirect != null) || (pendingEditGroup != null);
        pendingReply = null;
        pendingEditDirect = null;
        pendingEditGroup = null;
        hideBar();
        // cancelling an edit must also drop the prefilled old text from the input
        if (wasEdit && inputField != null)
        {
            inputField.setText("");
        }
    }

    // KHANDAQ (leak): these static View refs point into the chat Activity's view tree and were never
    // released — the destroyed Activity leaked. Call from the owning Activity's onDestroy. Guard on the
    // caller's own input field so that if a newer chat Activity already re-bound, we don't wipe its refs.
    static void unbind(@Nullable final EmojiEditText messageField)
    {
        if (messageField != null && inputField != messageField)
        {
            return;
        }
        pendingReply = null;
        pendingEditDirect = null;
        pendingEditGroup = null;
        bar = null;
        headerView = null;
        previewView = null;
        inputField = null;
    }

    // ---- KHANDAQ (#9 message edit) --------------------------------------------------------------

    static boolean isEditActive()
    {
        return (pendingEditDirect != null) || (pendingEditGroup != null);
    }

    @Nullable
    static Message consumeEditDirect()
    {
        final Message m = pendingEditDirect;
        pendingEditDirect = null;
        pendingEditGroup = null;
        pendingReply = null;
        hideBar();
        return m;
    }

    @Nullable
    static GroupMessage consumeEditGroup()
    {
        final GroupMessage m = pendingEditGroup;
        pendingEditDirect = null;
        pendingEditGroup = null;
        pendingReply = null;
        hideBar();
        return m;
    }

    static void startEditDirectMessage(final Context context, final Message message)
    {
        if (message == null)
        {
            return;
        }
        pendingReply = null;
        pendingEditGroup = null;
        pendingEditDirect = message;
        showEditBar(context, MessageReplyHelper.parse(message.text == null ? "" : message.text).bodyText);
    }

    static void startEditGroupMessage(final Context context, final GroupMessage message)
    {
        if (message == null)
        {
            return;
        }
        pendingReply = null;
        pendingEditDirect = null;
        pendingEditGroup = message;
        // KHANDAQ (#T5): strip BOTH the reply header and the @mention block ([KQ|m|…][KQ/m]) so the edit
        // prefill shows only the visible text, never the raw wire markers.
        showEditBar(context, GroupMentionHelper.parse(message.text == null ? "" : message.text).bodyText);
    }

    private static void showEditBar(final Context context, final String oldBody)
    {
        final String body = (oldBody == null) ? "" : oldBody;
        if (bar != null && headerView != null && previewView != null)
        {
            applyTheme(context);
            headerView.setText(context.getString(R.string.chat_edit_header));
            previewView.setText(MessageReplyHelper.previewForDisplay(context, body, 120));
            bar.setVisibility(View.VISIBLE);
        }
        if (inputField != null)
        {
            inputField.setText(body);
            try
            {
                inputField.setSelection(body.length());
            }
            catch (Exception ignored)
            {
            }
            inputField.requestFocus();
        }
    }

    // ---- KHANDAQ (#9 message edit) --------------------------------------------------------------

    @Nullable
    static MessageReplyHelper.ReplyMeta peekPending()
    {
        return pendingReply;
    }

    static String composeOutgoingText(final String userText)
    {
        if (pendingReply == null)
        {
            return userText == null ? "" : userText;
        }
        final String encoded = MessageReplyHelper.encode(pendingReply, userText);
        clear();
        return encoded;
    }

    static void startReplyToMessage(final Context context, final Message message)
    {
        if (message == null)
        {
            return;
        }
        final String previewText = previewTextForDirectMessage(context, message);
        if (previewText == null || previewText.trim().isEmpty())
        {
            return;
        }
        final MessageReplyHelper.ReplyMeta meta = new MessageReplyHelper.ReplyMeta();
        meta.localMessageId = message.id;
        meta.sortTimestampMs = message.direction == 0 ? message.rcvd_timestamp : message.sent_timestamp;
        meta.senderPubkeyTail = MessageReplyHelper.pubkeyTail(message.tox_friendpubkey);
        if (message.direction == 1)
        {
            // KHANDAQ (0.2.58): the person knows you by your profile name.
            nameOwnAuthor(meta, context.getString(R.string.chat_reply_self_name),
                          context.getString(R.string.chat_reply_unknown_sender), TRIFAGlobals.global_my_name);
        }
        else
        {
            // KHANDAQ #198: reject a hex fallback (6-char short-id or the raw pubkey) so a nameless
            // friend never renders as hex in the quote header.
            String resolved = resolve_name_for_pubkey(message.tox_friendpubkey, message.tox_friendpubkey);
            if (resolved != null)
            {
                resolved = resolved.trim();
            }
            if (resolved == null || resolved.isEmpty()
                    || HelperGroup.is_short_hex_peer_id(resolved, message.tox_friendpubkey)
                    || resolved.equalsIgnoreCase(message.tox_friendpubkey))
            {
                meta.senderName = context.getString(R.string.chat_reply_unknown_sender);
            }
            else
            {
                meta.senderName = resolved;
            }
        }
        meta.previewText = previewText;
        show(context, meta);
    }

    static void startReplyToGroupMessage(final Context context, final GroupMessage message)
    {
        if (message == null || !GroupMessageLayoutHelper.isRenderableMessage(context, message))
        {
            return;
        }
        if (message.private_message != 0)
        {
            answerPrivately(context, message);
            return;
        }
        final String previewText = previewTextForGroupMessage(context, message);
        if (previewText == null || previewText.trim().isEmpty())
        {
            return;
        }

        final MessageReplyHelper.ReplyMeta meta = new MessageReplyHelper.ReplyMeta();
        meta.localMessageId = message.id;
        meta.sortTimestampMs = GroupMessageLayoutHelper.effectiveSortTimestampMs(message);
        meta.senderPubkeyTail = MessageReplyHelper.pubkeyTail(message.tox_group_peer_pubkey);
        if (message.direction == 1)
        {
            // KHANDAQ (0.2.58): the group knows you by the name you have in it, else by your profile name.
            // Our own row has no name frozen on it (tox_group_peername is null on what we send).
            nameOwnAuthor(meta, context.getString(R.string.chat_reply_self_name),
                          context.getString(R.string.chat_reply_unknown_sender),
                          groupSelfName(message.group_identifier), TRIFAGlobals.global_my_name);
        }
        else
        {
            // KHANDAQ #198: prefer the peer nickname FROZEN on the row at receive time (matches iOS
            // groupPeerName). The live lookup degrades to a 6-hex pubkey short-id ("E30537") on any
            // miss and, being non-empty, used to shadow the reliable stored name in the quote header.
            String resolved = null;
            final String stored = message.tox_group_peername;
            if (stored != null && !stored.trim().isEmpty()
                    && !HelperGroup.is_short_hex_peer_id(stored.trim(), message.tox_group_peer_pubkey))
            {
                resolved = stored.trim();
            }
            if (resolved == null)
            {
                try
                {
                    final String live = resolve_name_for_pubkey(message.tox_group_peer_pubkey,
                            tox_group_peer_get_name__wrapper(message.group_identifier, message.tox_group_peer_pubkey));
                    if (live != null && !live.trim().isEmpty()
                            && !HelperGroup.is_short_hex_peer_id(live.trim(), message.tox_group_peer_pubkey))
                    {
                        resolved = live.trim();
                    }
                }
                catch (Exception ignored)
                {
                }
            }
            if (resolved == null)
            {
                // last resort: a visible short-id beats nothing, else localized "Unknown"
                resolved = (stored != null && !stored.trim().isEmpty())
                        ? stored.trim() : context.getString(R.string.chat_reply_unknown_sender);
            }
            meta.senderName = resolved;
        }
        meta.previewText = previewText;
        show(context, meta);
    }

    /**
     * KHANDAQ (26.11.2026): Reply on a private message of a group. A reply into the group would quote the
     * private words to every member (DESIGN-group-contacts-private-mentions.md), so the answer goes
     * privately to the other one of the two: the author of a message we got, the recipient of ours.
     */
    private static void answerPrivately(final Context context, final GroupMessage message)
    {
        final String member = message.direction == 1 ? message.sent_privately_to_tox_group_peer_pubkey
                                                     : message.tox_group_peer_pubkey;
        if (member == null || member.length() != 64 || message.group_identifier == null)
        {
            return;
        }
        GroupMemberActions.showPrivateMessageDialog(context, message.group_identifier, member,
                                                    GroupContactExchange.memberName(message.group_identifier, member));
    }

    static void startReplyToPlainText(final Context context, final String quoteText)
    {
        if (quoteText == null || quoteText.trim().isEmpty())
        {
            return;
        }
        final MessageReplyHelper.ReplyMeta meta = new MessageReplyHelper.ReplyMeta();
        meta.previewText = quoteText;
        meta.senderName = context.getString(R.string.chat_reply_unknown_sender);
        show(context, meta);
    }

    /**
     * KHANDAQ (0.2.58, as iOS 0.2.56 in ChatReplyController.replyMeta): a reply to our own message. The
     * header travels, and every client shows the name it carries, so it names us as the others in the chat
     * know us: the first of {@code knownAs} that is a name, else {@code unknownWord}. "You" there named the
     * reader on the other side. Only the bar above the field says {@code selfWord} (barName). Pure, so it
     * can be unit tested.
     */
    static void nameOwnAuthor(final MessageReplyHelper.ReplyMeta meta, final String selfWord,
                              final String unknownWord, @Nullable final String... knownAs)
    {
        meta.senderName = unknownWord;
        if (knownAs != null)
        {
            for (final String name : knownAs)
            {
                final String trimmed = name == null ? "" : name.trim();
                // "-1" is what the old name lookups give for none.
                if (!trimmed.isEmpty() && !"-1".equals(trimmed))
                {
                    meta.senderName = trimmed;
                    break;
                }
            }
        }
        meta.localSenderName = selfWord;
    }

    /** How the bar above the field names the author: as the header does, unless only the bar says "You". */
    static String barName(final MessageReplyHelper.ReplyMeta meta)
    {
        final String local = meta.localSenderName;
        return (local == null || local.isEmpty()) ? meta.senderName : local;
    }

    /**
     * KHANDAQ (0.2.59): the bar answers a message of yours, and says "In reply to yourself" (chat_reply_header_self):
     * "In reply to" + "You" put "You" in the wrong grammatical case in Russian.
     */
    static boolean barRepliesToSelf(final MessageReplyHelper.ReplyMeta meta)
    {
        final String local = meta.localSenderName;
        return local != null && !local.isEmpty();
    }

    /** Our name in this group as its members see it; null while toxcore has none for us or the group is not there. */
    @Nullable
    private static String groupSelfName(@Nullable final String groupIdentifier)
    {
        if (groupIdentifier == null)
        {
            return null;
        }
        try
        {
            return MainActivity.tox_group_self_get_name_safe(HelperGroup.tox_group_by_groupid__wrapper(groupIdentifier));
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    private static void show(final Context context, final MessageReplyHelper.ReplyMeta meta)
    {
        pendingReply = meta;
        if (bar == null || headerView == null || previewView == null)
        {
            return;
        }
        applyTheme(context);
        headerView.setText(barRepliesToSelf(meta) ? context.getString(R.string.chat_reply_header_self)
                                                  : context.getString(R.string.chat_reply_header, barName(meta)));
        previewView.setText(MessageReplyHelper.quoteForDisplay(context, meta.previewText, 120));
        bar.setVisibility(View.VISIBLE);
        if (inputField != null)
        {
            inputField.requestFocus();
        }
    }

    private static void hideBar()
    {
        if (bar != null)
        {
            bar.setVisibility(View.GONE);
        }
    }

    private static void applyTheme(final Context context)
    {
        if (bar == null || headerView == null || previewView == null)
        {
            return;
        }
        // KHANDAQ (redesign, stage 2): the one-to-one chat tags its bar for the design-system colours.
        if (bar.getTag(R.id.air_reply_style) != null)
        {
            bar.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.kq_sunken));
            headerView.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.kq_ink));
            previewView.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.kq_ink_2));
            return;
        }
        bar.setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.tg_reply_preview_bg));
        headerView.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.tg_reply_preview_header));
        previewView.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.tg_reply_preview_text));
    }

    @Nullable
    private static String previewTextForDirectMessage(final Context context, final Message message)
    {
        if (message.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            return ChatMediaHelper.messageMediaDisplayLabel(context, message);
        }
        final String displayText = message.text == null ? "" : message.text;
        final String body = MessageReplyHelper.parse(displayText).bodyText;
        if (body != null && !body.trim().isEmpty())
        {
            return body.trim();
        }
        return displayText.trim().isEmpty() ? null : displayText.trim();
    }

    @Nullable
    private static String previewTextForGroupMessage(final Context context, final GroupMessage message)
    {
        if (message.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            return ChatMediaHelper.groupMessageMediaDisplayLabel(context, message);
        }
        final String displayText = GroupMessageLayoutHelper.displayTextForMessage(context, message);
        // KHANDAQ (#T5): strip BOTH the reply header and the @mention block so the reply quote never
        // leaks raw [KQ|m|…] markers — locally in the reply bar AND over the wire to recipients.
        final String body = GroupMentionHelper.parse(displayText).bodyText;
        if (body != null && !body.trim().isEmpty())
        {
            return body.trim();
        }
        return displayText == null || displayText.trim().isEmpty() ? null : displayText.trim();
    }
}

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
        pendingReply = null;
        hideBar();
    }

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
            meta.senderName = context.getString(R.string.chat_reply_self_name);
        }
        else
        {
            meta.senderName = resolve_name_for_pubkey(message.tox_friendpubkey, message.tox_friendpubkey);
            if (meta.senderName == null || meta.senderName.isEmpty())
            {
                meta.senderName = context.getString(R.string.chat_reply_unknown_sender);
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
            meta.senderName = context.getString(R.string.chat_reply_self_name);
        }
        else
        {
            try
            {
                meta.senderName = resolve_name_for_pubkey(message.tox_group_peer_pubkey,
                        tox_group_peer_get_name__wrapper(message.group_identifier, message.tox_group_peer_pubkey));
            }
            catch (Exception ignored)
            {
            }
            if (meta.senderName == null || meta.senderName.isEmpty())
            {
                meta.senderName = message.tox_group_peername;
            }
            if (meta.senderName == null || meta.senderName.isEmpty())
            {
                meta.senderName = context.getString(R.string.chat_reply_unknown_sender);
            }
        }
        meta.previewText = previewText;
        show(context, meta);
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

    private static void show(final Context context, final MessageReplyHelper.ReplyMeta meta)
    {
        pendingReply = meta;
        if (bar == null || headerView == null || previewView == null)
        {
            return;
        }
        applyTheme(context);
        headerView.setText(context.getString(R.string.chat_reply_header, meta.senderName));
        previewView.setText(MessageReplyHelper.previewForDisplay(context, meta.previewText, 120));
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
        final String body = MessageReplyHelper.parse(displayText).bodyText;
        if (body != null && !body.trim().isEmpty())
        {
            return body.trim();
        }
        return displayText == null || displayText.trim().isEmpty() ? null : displayText.trim();
    }
}

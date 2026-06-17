package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/** Reply action from selection toolbar + scroll-to-original. */
final class HelperReply
{
    private HelperReply()
    {
    }

    static void replyToSelectedDirectMessage(final Context context)
    {
        if (context == null || selected_messages.size() != 1)
        {
            return;
        }
        final long messageId = selected_messages.iterator().next();
        try
        {
            final java.util.List<Message> rows = orma.selectFromMessage().idEq(messageId).toList();
            if (!rows.isEmpty())
            {
                ChatReplyPreviewController.startReplyToMessage(context, rows.get(0));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void replyToSelectedGroupMessage(final Context context)
    {
        if (context == null || selected_group_messages.size() != 1)
        {
            return;
        }
        final long messageId = selected_group_messages.iterator().next();
        try
        {
            final java.util.List<GroupMessage> rows = orma.selectFromGroupMessage().idEq(messageId).toList();
            if (!rows.isEmpty())
            {
                ChatReplyPreviewController.startReplyToGroupMessage(context, rows.get(0));
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void scrollToReplyTargetInDirectChat(final MessageReplyHelper.ReplyMeta replyMeta)
    {
        if (replyMeta == null || MainActivity.message_list_fragment == null)
        {
            return;
        }
        MainActivity.message_list_fragment.scrollToReplyTarget(replyMeta);
    }

    static void scrollToReplyTargetInGroupChat(final MessageReplyHelper.ReplyMeta replyMeta)
    {
        if (replyMeta == null || MainActivity.group_message_list_fragment == null)
        {
            return;
        }
        MainActivity.group_message_list_fragment.scrollToReplyTarget(replyMeta);
    }

    static boolean canReplyToCurrentSelection()
    {
        if (!selected_messages.isEmpty())
        {
            return selected_messages.size() == 1;
        }
        return !selected_group_messages.isEmpty() && selected_group_messages.size() == 1;
    }
}

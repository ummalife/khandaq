package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.util.Log;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.Nullable;

import static com.zoffcc.applications.trifa.MainActivity.selected_group_messages;
import static com.zoffcc.applications.trifa.MainActivity.selected_messages;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/** Reply action from selection toolbar + scroll-to-original. */
final class HelperReply
{
    private static final String TAG = "trifa.HelperReply";

    /**
     * KHANDAQ (inbox #10, 27.11): the messages deleted here that replies quote (ReplyTargetTombstones), in the open
     * profile's g_opts rows. Not final only so that a unit test, which has no database, can put a store of its own here.
     */
    static volatile ReplyTargetTombstones.Store REPLY_TARGETS = new ReplyTargetTombstones.Store(
            HelperMessageDelete.G_OPTS_ROWS);

    private HelperReply()
    {
    }

    /** The date a reply to the message names it by (ChatReplyPreviewController.startReplyToMessage). */
    static long replyDate(final Message m)
    {
        return (m.direction == 0) ? m.rcvd_timestamp : m.sent_timestamp;
    }

    /**
     * A message of a chat with one person, or of Saved Messages, is deleted here: to be called while its row is still
     * there, and before the list lets it go, so that a reply to it says it was deleted.
     */
    static void rememberDeletedReplyTarget(@Nullable final Message m)
    {
        if (m == null)
        {
            return;
        }
        try
        {
            REPLY_TARGETS.record(ReplyTargetTombstones.direct(m.tox_friendpubkey), m.id, replyDate(m));
        }
        catch (Exception e)
        {
            Log.i(TAG, "rememberDeletedReplyTarget:EE:" + e.getMessage());
        }
    }

    /** A message of a group is deleted here, as rememberDeletedReplyTarget(Message). */
    static void rememberDeletedReplyTarget(@Nullable final GroupMessage m)
    {
        if (m == null)
        {
            return;
        }
        try
        {
            REPLY_TARGETS.record(ReplyTargetTombstones.group(m.group_identifier), m.id,
                                 GroupMessageLayoutHelper.effectiveSortTimestampMs(m));
        }
        catch (Exception e)
        {
            Log.i(TAG, "rememberDeletedReplyTarget:EE:" + e.getMessage());
        }
    }

    /** The chat is gone, with every reply in it: what was deleted in it goes too. */
    static void forgetDeletedReplyTargets(@Nullable final String chatKey)
    {
        try
        {
            REPLY_TARGETS.forget(chatKey);
        }
        catch (Exception e)
        {
            Log.i(TAG, "forgetDeletedReplyTargets:EE:" + e.getMessage());
        }
    }

    /** The reply in the row m says so when the message it quotes was deleted here. To be called before it is shown. */
    static void markDeletedTarget(@Nullable final MessageReplyHelper.ReplyMeta reply, @Nullable final Message m)
    {
        if ((reply == null) || (m == null))
        {
            return;
        }
        final String friend = m.tox_friendpubkey;
        reply.targetDeleted = isDeleted(ReplyTargetTombstones.direct(friend), reply, m.id,
                                        date -> nearDirect(friend, date));
    }

    static void markDeletedTarget(@Nullable final MessageReplyHelper.ReplyMeta reply, @Nullable final GroupMessage m)
    {
        if ((reply == null) || (m == null))
        {
            return;
        }
        final String group = m.group_identifier;
        reply.targetDeleted = isDeleted(ReplyTargetTombstones.group(group), reply, m.id,
                                        date -> nearGroup(group, date));
    }

    private static boolean isDeleted(@Nullable final String chatKey, final MessageReplyHelper.ReplyMeta reply,
                                     final long replyRowId, final ReplyTargetTombstones.Live live)
    {
        try
        {
            return REPLY_TARGETS.isDeleted(chatKey, reply, replyRowId, live);
        }
        catch (Exception e)
        {
            Log.i(TAG, "isDeleted:EE:" + e.getMessage());
            return false;
        }
    }

    /** The messages of the chat with friendPubkey dated near dateMs, as a reply names them (replyDate). */
    @Nullable
    private static List<long[]> nearDirect(@Nullable final String friendPubkey, final long dateMs)
    {
        if ((friendPubkey == null) || (orma == null))
        {
            return null;
        }
        final long from = dateMs - ReplyTargetTombstones.WINDOW_MS;
        final long to = dateMs + ReplyTargetTombstones.WINDOW_MS;
        try
        {
            final List<long[]> near = new ArrayList<>();
            for (final Message row : orma.selectFromMessage().tox_friendpubkeyEq(friendPubkey).directionEq(0)
                                         .rcvd_timestampBetween(from, to).toList())
            {
                near.add(new long[]{row.id, replyDate(row)});
            }
            for (final Message row : orma.selectFromMessage().tox_friendpubkeyEq(friendPubkey).directionEq(1)
                                         .sent_timestampBetween(from, to).toList())
            {
                near.add(new long[]{row.id, replyDate(row)});
            }
            return near;
        }
        catch (Exception e)
        {
            Log.i(TAG, "nearDirect:EE:" + e.getMessage());
            return null;
        }
    }

    /**
     * The messages of the group dated near dateMs (GroupMessageLayoutHelper.effectiveSortTimestampMs, the later of
     * its two dates): each has one of them in the window when that one does.
     */
    @Nullable
    private static List<long[]> nearGroup(@Nullable final String groupIdentifier, final long dateMs)
    {
        if ((groupIdentifier == null) || (orma == null))
        {
            return null;
        }
        final long from = dateMs - ReplyTargetTombstones.WINDOW_MS;
        final long to = dateMs + ReplyTargetTombstones.WINDOW_MS;
        try
        {
            final Map<Long, long[]> near = new LinkedHashMap<>();
            for (final GroupMessage row : orma.selectFromGroupMessage().group_identifierEq(groupIdentifier)
                                              .sent_timestampBetween(from, to).toList())
            {
                near.put(row.id, new long[]{row.id, GroupMessageLayoutHelper.effectiveSortTimestampMs(row)});
            }
            for (final GroupMessage row : orma.selectFromGroupMessage().group_identifierEq(groupIdentifier)
                                              .rcvd_timestampBetween(from, to).toList())
            {
                near.put(row.id, new long[]{row.id, GroupMessageLayoutHelper.effectiveSortTimestampMs(row)});
            }
            return new ArrayList<>(near.values());
        }
        catch (Exception e)
        {
            Log.i(TAG, "nearGroup:EE:" + e.getMessage());
            return null;
        }
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

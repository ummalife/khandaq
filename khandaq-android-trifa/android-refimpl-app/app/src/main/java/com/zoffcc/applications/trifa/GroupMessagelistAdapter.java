/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2022 Zoff <zoff@zoff.cc>
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

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.l4digital.fastscroll.FastScroller;
import com.zoffcc.applications.sorm.GroupMessage;

import java.util.Iterator;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.HelperGeneric.format_chat_date_header;
import static com.zoffcc.applications.trifa.HelperGeneric.only_date_time_format;
import static com.zoffcc.applications.trifa.MainActivity.PREF__compact_chatlist;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;

public class GroupMessagelistAdapter extends RecyclerView.Adapter implements FastScroller.SectionIndexer,
        ChatDateSeparatorHelper.DateHeaderSource
{
    private static final String TAG = "trifa.GrpMesgelistAdptr";

    private final List<com.zoffcc.applications.sorm.GroupMessage> messagelistitems;
    private Context context;

    private GroupMessage getSectionText_message_object = null;
    long getSectionText_message_object_ts = -1L;
    long getSectionText_message_object_ts2 = -1L;
    String getSectionText_message_object_ts_string = " ";
    String getSectionText_message_object_ts_string2 = " ";


    public GroupMessagelistAdapter(Context context, List<com.zoffcc.applications.sorm.GroupMessage> items)
    {
        // HelperGeneric.logI(TAG, "GroupMessagelistAdapter");

        this.messagelistitems = items;
        this.context = context;
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder)
    {
        if (holder instanceof GroupMessageListHolder_file_outgoing_state_cancel)
        {
            ((GroupMessageListHolder_file_outgoing_state_cancel) holder).DetachedFromWindow(false);
        }
        else if (holder instanceof GroupMessageListHolder_file_incoming_state_cancel)
        {
            ((GroupMessageListHolder_file_incoming_state_cancel) holder).DetachedFromWindow(false);
        }
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType)
    {
        View view = null;

        switch (viewType)
        {
            case Message_model.TEXT_INCOMING_NOT_READ:
                if (PREF__compact_chatlist)
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_entry_read_compact,
                                                                            parent, false);
                }
                else
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_entry_read, parent,
                                                                            false);
                }
                return new GroupMessageListHolder_text_incoming_not_read(view, this.context);

            case Message_model.TEXT_INCOMING_HAVE_READ:
                if (PREF__compact_chatlist)
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_entry_read_compact,
                                                                            parent, false);
                }
                else
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_entry_read, parent,
                                                                            false);
                }
                return new GroupMessageListHolder_text_incoming_not_read(view, this.context);

            case Message_model.TEXT_OUTGOING_NOT_READ:
                if (PREF__compact_chatlist)
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(
                            R.layout.message_list_self_entry_read_compact, parent, false);
                }
                else
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_self_entry_read,
                                                                            parent, false);
                }
                return new GroupMessageListHolder_text_outgoing_read(view, this.context);

            case Message_model.TEXT_OUTGOING_HAVE_READ:
                if (PREF__compact_chatlist)
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(
                            R.layout.message_list_self_entry_read_compact, parent, false);
                }
                else
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_self_entry_read,
                                                                            parent, false);
                }
                return new GroupMessageListHolder_text_outgoing_read(view, this.context);

            case Message_model.FILE_INCOMING_STATE_CANCEL:
                if (PREF__compact_chatlist)
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_group_list_ft_incoming_compact,
                                                                            parent, false);
                }
                else
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_group_list_ft_incoming, parent,
                                                                            false);
                }
                return new GroupMessageListHolder_file_incoming_state_cancel(view, this.context);

            case Message_model.FILE_OUTGOING_STATE_CANCEL:
                if (PREF__compact_chatlist)
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_ft_outgoing_compact,
                                                                            parent, false);
                }
                else
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_ft_outgoing, parent,
                                                                            false);
                }
                return new GroupMessageListHolder_file_outgoing_state_cancel(view, this.context);
        }

        view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_error, parent, false);
        return new GroupMessageListHolder_error(view, this.context);
    }

    @Override
    public int getItemViewType(int position)
    {
        if (position < 0 || position >= getItemCount())
        {
            return Message_model.ERROR_UNKNOWN;
        }

        final GroupMessage my_msg = this.messagelistitems.get(position);
        if (my_msg == null)
        {
            return Message_model.ERROR_UNKNOWN;
        }

        if (my_msg.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            // FILE -------------
            if (my_msg.direction == 0)
            {
                return Message_model.FILE_INCOMING_STATE_CANCEL;
            }
            else
            {
                return Message_model.FILE_OUTGOING_STATE_CANCEL;
            }
        }
        else
        {
            // TEXT -------------
            if (my_msg.direction == 0)
            {
                if (my_msg.read)
                {
                    return Message_model.TEXT_INCOMING_HAVE_READ;
                }
                else
                {
                    return Message_model.TEXT_INCOMING_NOT_READ;
                }
            }
            else
            {
                if (my_msg.read)
                {
                    return Message_model.TEXT_OUTGOING_HAVE_READ;
                }
                else
                {
                    return Message_model.TEXT_OUTGOING_NOT_READ;
                }
            }
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position)
    {
        if (position < 0 || position >= getItemCount())
        {
            return;
        }

        final GroupMessage m2 = this.messagelistitems.get(position);
        bindViewHolderForType(holder, m2);

        // KHANDAQ: Telegram-style captions — merge an adjacent TEXT into its media bubble.
        ChatCaptionHelper.apply_group_caption_state(context, this.messagelistitems, position, holder);
    }

    private void bindViewHolderForType(final RecyclerView.ViewHolder holder, final GroupMessage message)
    {
        try
        {
            final int viewType = holder.getItemViewType();
            switch (viewType)
            {
                case Message_model.TEXT_INCOMING_NOT_READ:
                case Message_model.TEXT_INCOMING_HAVE_READ:
                    if (holder instanceof GroupMessageListHolder_text_incoming_not_read)
                    {
                        ((GroupMessageListHolder_text_incoming_not_read) holder).bindMessageList(message);
                    }
                    break;

                case Message_model.TEXT_OUTGOING_NOT_READ:
                case Message_model.TEXT_OUTGOING_HAVE_READ:
                    if (holder instanceof GroupMessageListHolder_text_outgoing_read)
                    {
                        ((GroupMessageListHolder_text_outgoing_read) holder).bindMessageList(message);
                    }
                    break;

                case Message_model.FILE_INCOMING_STATE_CANCEL:
                    if (holder instanceof GroupMessageListHolder_file_incoming_state_cancel)
                    {
                        ((GroupMessageListHolder_file_incoming_state_cancel) holder).bindMessageList(message);
                    }
                    break;

                case Message_model.FILE_OUTGOING_STATE_CANCEL:
                    if (holder instanceof GroupMessageListHolder_file_outgoing_state_cancel)
                    {
                        ((GroupMessageListHolder_file_outgoing_state_cancel) holder).bindMessageList(message);
                    }
                    break;

                default:
                    if (holder instanceof GroupMessageListHolder_error)
                    {
                        ((GroupMessageListHolder_error) holder).bindMessageList(message);
                    }
                    break;
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "onBindViewHolder:EE1:" + e.getMessage());
            if (holder instanceof GroupMessageListHolder_error)
            {
                try
                {
                    ((GroupMessageListHolder_error) holder).bindMessageList(message);
                }
                catch (Exception ignored)
                {
                }
            }
        }
    }

    @Override
    public int getItemCount()
    {
        if (this.messagelistitems != null)
        {
            // HelperGeneric.logI(TAG, "getItemCount:" + this.messagelistitems.size());
            return this.messagelistitems.size();
        }
        else
        {
            return 0;
        }
    }

    public void add_list_clear(List<com.zoffcc.applications.sorm.GroupMessage> new_items)
    {
        // HelperGeneric.logI(TAG, "add_list_clear:" + new_items);

        try
        {
            // HelperGeneric.logI(TAG, "add_list_clear:001:new_items=" + new_items);
            this.messagelistitems.clear();
            this.messagelistitems.addAll(new_items);
            this.notifyDataSetChanged();
            // HelperGeneric.logI(TAG, "add_list_clear:002");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "add_list_clear:EE:" + e.getMessage());
        }
    }

    synchronized public void redraw_all_items()
    {
        this.notifyDataSetChanged();
    }

    private boolean isDuplicateGroupMessage(final GroupMessage candidate, final List<GroupMessage> items)
    {
        if (candidate == null || items == null)
        {
            return false;
        }

        for (int i = 0; i < items.size(); i++)
        {
            final GroupMessage existing = items.get(i);
            if (candidate.id > 0 && existing.id == candidate.id)
            {
                return true;
            }

            if (candidate.msg_id_hash != null && !candidate.msg_id_hash.isEmpty()
                    && candidate.msg_id_hash.equalsIgnoreCase(existing.msg_id_hash)
                    && candidate.group_identifier != null
                    && candidate.group_identifier.equalsIgnoreCase(existing.group_identifier))
            {
                return true;
            }
        }

        return false;
    }

    public void add_item(GroupMessage new_item)
    {
        // HelperGeneric.logI(TAG, "add_item:" + new_item + ":" + this.messagelistitems.size());

        try
        {
            if (new_item != null && isDuplicateGroupMessage(new_item, this.messagelistitems))
            {
                return;
            }

            final int insertPos = GroupMessageLayoutHelper.sortedInsertIndex(this.messagelistitems, new_item);
            this.messagelistitems.add(insertPos, new_item);
            this.notifyItemInserted(insertPos);
            // HelperGeneric.logI(TAG, "add_item:002:" + this.messagelistitems.size());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "add_item:EE:" + e.getMessage());
        }
    }

    synchronized public boolean update_item(final GroupMessage new_item)
    {
        // HelperGeneric.logI(TAG, "update_item:" + new_item);

        boolean found_item = false;

        try
        {
            Iterator it = this.messagelistitems.iterator();
            while (it.hasNext())
            {
                GroupMessage m2 = (GroupMessage) it.next();

                if (m2.id == new_item.id)
                {
                    found_item = true;
                    int pos = this.messagelistitems.indexOf(m2);
                    // HelperGeneric.logI(TAG, "update_item:003:" + pos);
                    final GroupMessage old_item = m2;
                    this.messagelistitems.set(pos, new_item);
                    this.notifyItemChanged(pos);
                    // KHANDAQ (captions): a FILE state change (e.g. media downloaded) can turn the
                    // NEXT row into a merged caption — rebind it too so it collapses/expands in sync.
                    // Only on an actual content flip, not on every progress tick (#172 flicker).
                    if ((pos + 1) < this.messagelistitems.size()
                            && ((old_item.filename_fullpath == null) != (new_item.filename_fullpath == null)
                                || old_item.TRIFA_MESSAGE_TYPE != new_item.TRIFA_MESSAGE_TYPE))
                    {
                        this.notifyItemChanged(pos + 1);
                    }
                    break;
                }
            }

            // this.notifyDataSetChanged();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "update_item:EE:" + e.getMessage());
        }

        return found_item;
    }

    synchronized public void refresh_file_progress_by_hash(final String msgIdHash)
    {
        if (msgIdHash == null)
        {
            return;
        }

        try
        {
            for (int pos = 0; pos < this.messagelistitems.size(); pos++)
            {
                final GroupMessage m2 = this.messagelistitems.get(pos);
                if (msgIdHash.equalsIgnoreCase(m2.msg_id_hash))
                {
                    this.notifyItemChanged(pos);
                    return;
                }
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "refresh_file_progress_by_hash:EE:" + e.getMessage());
        }
    }

    synchronized public void remove_placeholder_by_msg_id_hash(final String msgIdHash)
    {
        if (msgIdHash == null)
        {
            return;
        }

        try
        {
            for (int pos = 0; pos < this.messagelistitems.size(); pos++)
            {
                final GroupMessage m2 = this.messagelistitems.get(pos);
                if (m2.id < 0 && msgIdHash.equalsIgnoreCase(m2.msg_id_hash))
                {
                    this.messagelistitems.remove(pos);
                    this.notifyItemRemoved(pos);
                    return;
                }
            }
        }
        catch (Exception e)
        {
            HelperGeneric.logI(TAG, "remove_placeholder_by_msg_id_hash:EE:" + e.getMessage());
        }
    }

    synchronized public void remove_item(final GroupMessage del_item)
    {
        try
        {
            Iterator it = this.messagelistitems.iterator();
            while (it.hasNext())
            {
                GroupMessage m2 = (GroupMessage) it.next();

                if (m2.id == del_item.id)
                {
                    int pos = this.messagelistitems.indexOf(m2);
                    this.messagelistitems.remove(pos);
                    this.notifyItemRemoved(pos);
                    break;
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "update_item:EE:" + e.getMessage());
        }
    }

    @Override
    public String getSectionText(int position)
    {
        // set fastscroller bluble text
        return " ";
    }

    public String getDateHeaderText(int position)
    {
        try
        {
            final GroupMessage message = (GroupMessage) messagelistitems.get(position);
            final long ts = GroupMessageLayoutHelper.effectiveSortTimestampMs(message);
            return format_chat_date_header(context, ts);
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return " ";
        }
    }

    public boolean shouldShowDateHeader(int position)
    {
        if (position < 1)
        {
            return true;
        }

        try
        {
            return !getDateHeaderText(position).equals(getDateHeaderText(position - 1));
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return true;
        }
    }

    public int findPositionForReply(final MessageReplyHelper.ReplyMeta replyMeta)
    {
        if (replyMeta == null || messagelistitems == null)
        {
            return -1;
        }

        if (replyMeta.localMessageId > 0L)
        {
            for (int i = 0; i < messagelistitems.size(); i++)
            {
                final GroupMessage message = (GroupMessage) messagelistitems.get(i);
                if (message != null && message.id == replyMeta.localMessageId)
                {
                    return i;
                }
            }
        }

        for (int i = 0; i < messagelistitems.size(); i++)
        {
            final GroupMessage message = (GroupMessage) messagelistitems.get(i);
            if (message == null)
            {
                continue;
            }
            final long ts = GroupMessageLayoutHelper.effectiveSortTimestampMs(message);
            if (Math.abs(ts - replyMeta.sortTimestampMs) > 5000L)
            {
                continue;
            }
            if (replyMeta.senderPubkeyTail == null || replyMeta.senderPubkeyTail.isEmpty()
                    || MessageReplyHelper.pubkeyTail(message.tox_group_peer_pubkey).equals(replyMeta.senderPubkeyTail))
            {
                return i;
            }
        }
        return -1;
    }

    public GroupMessage get_item(int position)
    {
        try
        {
            return (GroupMessage) messagelistitems.get(position);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    public String getPrvPeer(int position)
    {
        try
        {
            GroupMessage getSectionText_message_object2 = (GroupMessage) messagelistitems.get(position);

            if (getSectionText_message_object2.direction == 0)
            {
                // incoming msg
                return ("I_" + getSectionText_message_object2.tox_group_peer_pubkey);
            }
            else
            {
                // outgoing msg
                return ("O_" + getSectionText_message_object2.tox_group_peer_pubkey);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }

    public static class DateTime_in_out
    {
        long timestamp;
        int direction;
        String pk;
    }

    public DateTime_in_out getDateTime(int position)
    {
        DateTime_in_out ret = new DateTime_in_out();

        try
        {
            // direction: 0 -> msg received, 1 -> msg sent
            GroupMessage getSectionText_message_object2 = (GroupMessage) messagelistitems.get(position);

            if (getSectionText_message_object2.direction == 0)
            {
                // incoming msg
                ret.direction = 0;
                ret.timestamp = getSectionText_message_object2.sent_timestamp;
                ret.pk = getSectionText_message_object2.tox_group_peer_pubkey;
                return ret;
            }
            else
            {
                // outgoing msg
                ret.direction = 1;
                ret.timestamp = getSectionText_message_object2.sent_timestamp;
                ret.pk = getSectionText_message_object2.tox_group_peer_pubkey;
                return ret;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return null;
        }
    }
}

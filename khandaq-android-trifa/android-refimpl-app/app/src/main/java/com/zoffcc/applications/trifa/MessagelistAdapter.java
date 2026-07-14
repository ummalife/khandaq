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

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.l4digital.fastscroll.FastScroller;
import com.zoffcc.applications.sorm.Message;

import java.util.Iterator;
import java.util.List;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.HelperGeneric.format_chat_date_header;
import static com.zoffcc.applications.trifa.HelperGeneric.only_date_time_format;
import static com.zoffcc.applications.trifa.MainActivity.PREF__compact_chatlist;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_PAGING_SHOW_NEWER_HASH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.MESSAGE_PAGING_SHOW_OLDER_HASH;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;

/*
 *
 * HINT: in this class we decide what type of message/filetransfer view is actually shown
 *
 */
public class MessagelistAdapter extends RecyclerView.Adapter implements FastScroller.SectionIndexer,
        ChatDateSeparatorHelper.DateHeaderSource
{
    private static final String TAG = "trifa.MessagelistAdptr";

    private final List<com.zoffcc.applications.sorm.Message> messagelistitems;
    private Context context;
    long getSectionText_message_object_ts = -1L;
    long getSectionText_message_object_ts2 = -1L;
    String getSectionText_message_object_ts_string = " ";
    String getSectionText_message_object_ts_string2 = " ";


    public MessagelistAdapter(Context context, List<com.zoffcc.applications.sorm.Message> items)
    {
        Log.i(TAG, "MessagelistAdapter");

        this.messagelistitems = items;
        this.context = context;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType)
    {
        // Log.i(TAG, "MessageListHolder");

        View view = null;

        switch (viewType)
        {
            case Message_model.PAGING_NEWER:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_paging_newer, parent,
                                                                        false);
                return new MessageListHolder_paging(view, this.context);
            case Message_model.PAGING_OLDER:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_paging_older, parent,
                                                                        false);
                return new MessageListHolder_paging(view, this.context);
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
                return new MessageListHolder_text_incoming_not_read(view, this.context);
            case Message_model.TEXT_INCOMING_HAVE_READ:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_entry_read, parent,
                                                                        false);
                // return new MessageListHolder_text_incoming_read___unused___(view, this.context);
                return new MessageListHolder_error(view, this.context);

            case Message_model.TEXT_OUTGOING_NOT_READ:
                if (PREF__compact_chatlist)
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_self_entry_compact,
                                                                            parent, false);
                }
                else
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_self_entry, parent,
                                                                            false);
                }
                return new MessageListHolder_text_outgoing_not_read(view, this.context);
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
                return new MessageListHolder_text_outgoing_read(view, this.context);

            case Message_model.FILE_INCOMING_STATE_CANCEL:
                if (PREF__compact_chatlist)
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_ft_incoming_compact,
                                                                            parent, false);
                }
                else
                {
                    view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_ft_incoming, parent,
                                                                            false);
                }
                return new MessageListHolder_file_incoming_state_cancel(view, this.context);
            case Message_model.FILE_INCOMING_STATE_PAUSE_HAS_ACCEPTED:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_ft_incoming, parent,
                                                                        false);
                return new MessageListHolder_file_incoming_state_pause_has_accepted(view, this.context);
            case Message_model.FILE_INCOMING_STATE_PAUSE_NOT_YET_ACCEPTED:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_ft_incoming, parent,
                                                                        false);
                return new MessageListHolder_file_incoming_state_pause_not_yet_accepted(view, this.context);
            case Message_model.FILE_INCOMING_STATE_RESUME:
                view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_ft_incoming, parent,
                                                                        false);
                return new MessageListHolder_file_incoming_state_resume(view, this.context);

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
                return new MessageListHolder_file_outgoing_state_cancel(view, this.context);
            case Message_model.FILE_OUTGOING_STATE_PAUSE_HAS_ACCEPTED:
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
                return new MessageListHolder_file_outgoing_state_pause_has_accepted(view, this.context);
            case Message_model.FILE_OUTGOING_STATE_PAUSE_NOT_YET_ACCEPTED:
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
                return new MessageListHolder_file_outgoing_state_pause_not_yet_accepted(view, this.context);
            case Message_model.FILE_OUTGOING_STATE_PAUSE_NOT_YET_STARTED:
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
                return new MessageListHolder_file_outgoing_state_pause_not_yet_started(view, this.context);
            case Message_model.FILE_OUTGOING_STATE_RESUME:
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
                return new MessageListHolder_file_outgoing_state_resume(view, this.context);
        }

        view = LayoutInflater.from(parent.getContext()).inflate(R.layout.message_list_error, parent, false);
        return new MessageListHolder_error(view, this.context);
    }

    @Override
    public int getItemViewType(int position)
    {
        Message my_msg = (Message) this.messagelistitems.get(position);

        if (my_msg.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
        {
            // FILE -------------
            if (my_msg.direction == 0)
            {
                // incoming file -----------
                if (my_msg.state == TOX_FILE_CONTROL_CANCEL.value)
                {
                    // ------- STATE: CANCEL -------------
                    return Message_model.FILE_INCOMING_STATE_CANCEL;
                    // ------- STATE: CANCEL -------------
                }
                else if (my_msg.state == TOX_FILE_CONTROL_PAUSE.value)
                {
                    // ------- STATE: PAUSE -------------
                    if (my_msg.ft_accepted == false)
                    {
                        // not yet accepted
                        return Message_model.FILE_INCOMING_STATE_PAUSE_NOT_YET_ACCEPTED;
                        // not yet accepted
                    }
                    else
                    {
                        // has accepted
                        return Message_model.FILE_INCOMING_STATE_PAUSE_HAS_ACCEPTED;
                        // has accepted
                    }
                    // ------- STATE: PAUSE -------------
                }
                else
                {
                    // ------- STATE: RESUME -------------
                    return Message_model.FILE_INCOMING_STATE_RESUME;
                    // ------- STATE: RESUME -------------
                }
            }
            else
            {
                // outgoing file -----------
                if (my_msg.state == TOX_FILE_CONTROL_CANCEL.value)
                {
                    // ------- STATE: CANCEL -------------
                    return Message_model.FILE_OUTGOING_STATE_CANCEL;
                    // ------- STATE: CANCEL -------------
                }
                else if (my_msg.state == TOX_FILE_CONTROL_PAUSE.value)
                {
                    // ------- STATE: PAUSE -------------
                    if (my_msg.ft_accepted == false)
                    {
                        if (my_msg.ft_outgoing_started == false)
                        {
                            return Message_model.FILE_OUTGOING_STATE_PAUSE_NOT_YET_STARTED;
                        }
                        else
                        {
                            // not yet accepted
                            return Message_model.FILE_OUTGOING_STATE_PAUSE_NOT_YET_ACCEPTED;
                            // not yet accepted
                        }
                    }
                    else
                    {
                        // has accepted
                        return Message_model.FILE_OUTGOING_STATE_PAUSE_HAS_ACCEPTED;
                        // has accepted
                    }
                    // ------- STATE: PAUSE -------------
                }
                else
                {
                    // ------- STATE: RESUME -------------
                    return Message_model.FILE_OUTGOING_STATE_RESUME;
                    // ------- STATE: RESUME -------------
                }
            }
            // FILE -------------
        }
        else
        {
            // TEXT -------------
            if (TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(my_msg.tox_friendpubkey)
                    && (MESSAGE_PAGING_SHOW_OLDER_HASH.equals(my_msg.msg_idv3_hash)
                        || MESSAGE_PAGING_SHOW_NEWER_HASH.equals(my_msg.msg_idv3_hash)))
            {
                if (MESSAGE_PAGING_SHOW_OLDER_HASH.equals(my_msg.msg_idv3_hash))
                {
                    return Message_model.PAGING_OLDER;
                }
                else
                {
                    return Message_model.PAGING_NEWER;
                }
            }
            else if (my_msg.direction == 0)
            {
                // msg to me
                if (my_msg.read)
                {
                    // has read
                    return Message_model.TEXT_INCOMING_HAVE_READ;
                }
                else
                {
                    // not yet read
                    return Message_model.TEXT_INCOMING_NOT_READ;
                }
                // msg to me
            }
            else
            {
                // msg from me
                if (my_msg.read)
                {
                    // has read
                    return Message_model.TEXT_OUTGOING_HAVE_READ;
                }
                else
                {
                    // not yet read
                    return Message_model.TEXT_OUTGOING_NOT_READ;
                }
                // msg from me
            }
            // TEXT -------------
        }

        // return Message_model.ERROR_UNKNOWN;
    }

    @Override
    public void onViewDetachedFromWindow(RecyclerView.ViewHolder holder)
    {
        try
        {
            ((MessageListHolder_file_outgoing_state_cancel) holder).DetachedFromWindow(false);
        }
        catch(Exception e)
        {
        }

        try
        {
            ((MessageListHolder_file_incoming_state_cancel) holder).DetachedFromWindow(false);
        }
        catch(Exception e1)
        {
        }

        /*
        try
        {
            ((MessageListHolder_file_incoming_state_cancel) holder).DetachedFromWindow();
        }
        catch (Exception e)
        {
        }
        */
        super.onViewDetachedFromWindow(holder);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position)
    {
        // Log.i(TAG, "onBindViewHolder:position=" + position);

        try
        {
            Message m2 = (Message) this.messagelistitems.get(position);

            switch (getItemViewType(position))
            {
                case Message_model.PAGING_NEWER:
                    ((MessageListHolder_paging) holder).bindMessageList(m2);
                    break;
                case Message_model.PAGING_OLDER:
                    ((MessageListHolder_paging) holder).bindMessageList(m2);
                    break;
                case Message_model.TEXT_INCOMING_NOT_READ:
                    ((MessageListHolder_text_incoming_not_read) holder).bindMessageList(m2);
                    break;
                case Message_model.TEXT_INCOMING_HAVE_READ:
                    //((MessageListHolder_text_incoming_read___unused___) holder).bindMessageList(m2);
                    break;
                case Message_model.TEXT_OUTGOING_NOT_READ:
                    ((MessageListHolder_text_outgoing_not_read) holder).bindMessageList(m2);
                    break;
                case Message_model.TEXT_OUTGOING_HAVE_READ:
                    ((MessageListHolder_text_outgoing_read) holder).bindMessageList(m2);
                    break;

                case Message_model.FILE_INCOMING_STATE_CANCEL:
                    ((MessageListHolder_file_incoming_state_cancel) holder).bindMessageList(m2);
                    break;
                case Message_model.FILE_INCOMING_STATE_PAUSE_HAS_ACCEPTED:
                    ((MessageListHolder_file_incoming_state_pause_has_accepted) holder).bindMessageList(m2);
                    break;
                case Message_model.FILE_INCOMING_STATE_PAUSE_NOT_YET_ACCEPTED:
                    ((MessageListHolder_file_incoming_state_pause_not_yet_accepted) holder).bindMessageList(m2);
                    break;
                case Message_model.FILE_INCOMING_STATE_RESUME:
                    ((MessageListHolder_file_incoming_state_resume) holder).bindMessageList(m2);
                    break;

                case Message_model.FILE_OUTGOING_STATE_CANCEL:
                    ((MessageListHolder_file_outgoing_state_cancel) holder).bindMessageList(m2);
                    break;
                case Message_model.FILE_OUTGOING_STATE_PAUSE_HAS_ACCEPTED:
                    ((MessageListHolder_file_outgoing_state_pause_has_accepted) holder).bindMessageList(m2);
                    break;
                case Message_model.FILE_OUTGOING_STATE_PAUSE_NOT_YET_ACCEPTED:
                    ((MessageListHolder_file_outgoing_state_pause_not_yet_accepted) holder).bindMessageList(m2);
                    break;
                case Message_model.FILE_OUTGOING_STATE_PAUSE_NOT_YET_STARTED:
                    ((MessageListHolder_file_outgoing_state_pause_not_yet_started) holder).bindMessageList(m2);
                    break;
                case Message_model.FILE_OUTGOING_STATE_RESUME:
                    ((MessageListHolder_file_outgoing_state_resume) holder).bindMessageList(m2);
                    break;

                default:
                    ((MessageListHolder_error) holder).bindMessageList(null);
                    break;
            }

            // KHANDAQ: Telegram-style captions — merge an adjacent TEXT into its media bubble.
            ChatCaptionHelper.apply_caption_state(context, this.messagelistitems, position, holder);
        }
        catch (Exception e)
        {
            // Log.i(TAG, "onBindViewHolder:EE1:" + e.getMessage());
            e.printStackTrace();
            try
            {
                ((MessageListHolder_error) holder).bindMessageList(null);
            }
            catch (Exception e22)
            {
                e22.printStackTrace();
                Log.i(TAG, "onBindViewHolder:EE22:" + e.getMessage());
            }
        }
    }

    @Override
    public int getItemCount()
    {
        if (this.messagelistitems != null)
        {
            // Log.i(TAG, "getItemCount:" + this.messagelistitems.size());
            return this.messagelistitems.size();
        }
        else
        {
            return 0;
        }
    }

    public void add_list_clear(List<com.zoffcc.applications.sorm.Message> new_items)
    {
        // Log.i(TAG, "add_list_clear:" + new_items);

        try
        {
            // KHANDAQ: skip the full rebuild when nothing visible changed — a blind
            // notifyDataSetChanged() blanks every media thumbnail (Glide reload) and makes the
            // whole chat blink on each onResume / delayed refresh.
            if (same_rendered_state(this.messagelistitems, new_items))
            {
                return;
            }
            // Log.i(TAG, "add_list_clear:001:new_items=" + new_items);
            this.messagelistitems.clear();
            this.messagelistitems.addAll(new_items);
            this.notifyDataSetChanged();
            // Log.i(TAG, "add_list_clear:002");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "add_list_clear:EE:" + e.getMessage());
        }
    }

    // is_new is deliberately ignored: it is reset in the DB on every resume and never rendered
    // inside a chat row, so including it would defeat the skip on every re-entry.
    private static boolean same_rendered_state(final List<com.zoffcc.applications.sorm.Message> old_items,
                                               final List<com.zoffcc.applications.sorm.Message> new_items)
    {
        if (old_items == null || new_items == null || old_items.size() != new_items.size())
        {
            return false;
        }

        for (int i = 0; i < old_items.size(); i++)
        {
            final com.zoffcc.applications.sorm.Message a = old_items.get(i);
            final com.zoffcc.applications.sorm.Message b = new_items.get(i);
            if (a == null || b == null)
            {
                return false;
            }
            if (a.id != b.id || a.message_id != b.message_id || a.direction != b.direction ||
                a.TOX_MESSAGE_TYPE != b.TOX_MESSAGE_TYPE || a.TRIFA_MESSAGE_TYPE != b.TRIFA_MESSAGE_TYPE ||
                a.state != b.state || a.ft_accepted != b.ft_accepted ||
                a.ft_outgoing_started != b.ft_outgoing_started || a.filedb_id != b.filedb_id ||
                a.filetransfer_id != b.filetransfer_id || a.sent_timestamp != b.sent_timestamp ||
                a.rcvd_timestamp != b.rcvd_timestamp || a.read != b.read || a.edited != b.edited ||
                a.msg_at_relay != b.msg_at_relay || a.sent_push != b.sent_push ||
                a.filetransfer_kind != b.filetransfer_kind || a.resend_count != b.resend_count ||
                !java.util.Objects.equals(a.text, b.text) ||
                !java.util.Objects.equals(a.filename_fullpath, b.filename_fullpath))
            {
                return false;
            }
        }

        return true;
    }

    private boolean isDuplicateMessage(final Message candidate)
    {
        if (candidate == null)
        {
            return false;
        }

        for (int i = 0; i < messagelistitems.size(); i++)
        {
            final Message existing = messagelistitems.get(i);
            if (candidate.id > 0 && existing.id == candidate.id)
            {
                return true;
            }

            if (candidate.filetransfer_id > 0 && existing.filetransfer_id == candidate.filetransfer_id)
            {
                return true;
            }

            if (candidate.msg_id_hash != null && !candidate.msg_id_hash.isEmpty()
                    && candidate.msg_id_hash.equalsIgnoreCase(existing.msg_id_hash)
                    && candidate.tox_friendpubkey != null
                    && candidate.tox_friendpubkey.equalsIgnoreCase(existing.tox_friendpubkey))
            {
                return true;
            }
        }

        return false;
    }

    // KHANDAQ (#181): payload marker for byte-counter-only transfer updates (no full rebind)
    static final Object PAYLOAD_TRANSFER_PROGRESS = new Object();

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position,
                                 @NonNull java.util.List payloads)
    {
        boolean progressOnly = !payloads.isEmpty();
        for (Object p : payloads)
        {
            if (p != PAYLOAD_TRANSFER_PROGRESS)
            {
                progressOnly = false;
                break;
            }
        }

        if (progressOnly)
        {
            try
            {
                final Message m = (Message) this.messagelistitems.get(position);
                ChatTransferProgressHelper.applyDirect(context, holder.itemView, m, m.direction == 1);
                return;
            }
            catch (Exception ignored)
            {
                // fall through to the full bind
            }
        }

        super.onBindViewHolder(holder, position, payloads);
    }

    public void add_item(Message new_item)
    {
        // Log.i(TAG, "add_item:" + new_item + ":" + this.messagelistitems.size());

        try
        {
            if (new_item != null && isDuplicateMessage(new_item))
            {
                return;
            }

            this.messagelistitems.add(new_item);
            // TODO: use "notifyItemInserted" !!
            this.notifyDataSetChanged();
            // Log.i(TAG, "add_item:002:" + this.messagelistitems.size());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "add_item:EE:" + e.getMessage());
        }
    }

    //    public void clear_items()
    //    {
    //        this.messagelistitems.clear();
    //        this.notifyDataSetChanged();
    //    }

    synchronized public void redraw_all_items()
    {
        this.notifyDataSetChanged();
    }

    synchronized public boolean update_item(final Message new_item)
    {
        // Log.i(TAG, "update_item:" + new_item);

        boolean found_item = false;

        try
        {
            Iterator it = this.messagelistitems.iterator();
            while (it.hasNext())
            {
                Message m2 = (Message) it.next();

                if (m2.id == new_item.id)
                {
                    found_item = true;
                    int pos = this.messagelistitems.indexOf(m2);
                    // Log.i(TAG, "update_item:003:" + pos);
                    final Message old_item = m2;
                    this.messagelistitems.set(pos, new_item);
                    // KHANDAQ (#181): a mid-transfer tick changes only the byte counters — a full
                    // rebind resets the holder's spinner/Glide load every time and the bubble
                    // blinks through the whole transfer. Ship such updates as a PROGRESS payload
                    // (bound via ChatTransferProgressHelper.applyDirect, no preview reload).
                    final boolean progressOnly =
                            old_item.TRIFA_MESSAGE_TYPE == TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value
                            && old_item.TRIFA_MESSAGE_TYPE == new_item.TRIFA_MESSAGE_TYPE
                            && old_item.state == new_item.state
                            && old_item.filedb_id == new_item.filedb_id
                            && old_item.filetransfer_id == new_item.filetransfer_id
                            && ((old_item.filename_fullpath == null) == (new_item.filename_fullpath == null));
                    if (progressOnly)
                    {
                        this.notifyItemChanged(pos, PAYLOAD_TRANSFER_PROGRESS);
                    }
                    else
                    {
                        this.notifyItemChanged(pos);
                    }
                    // KHANDAQ (captions): a FILE state change (e.g. download finished) can turn the
                    // NEXT row into a merged caption — rebind it too so it collapses/expands in sync.
                    // Only on an actual state/filedb flip: rebinding on every progress tick made the
                    // neighbour row (and its Glide preview) flicker through the whole transfer (#172).
                    if ((pos + 1) < this.messagelistitems.size()
                            && (old_item.state != new_item.state || old_item.filedb_id != new_item.filedb_id))
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
            Log.i(TAG, "update_item:EE:" + e.getMessage());
        }

        return found_item;
    }

    synchronized public void remove_item(final Message del_item)
    {
        try
        {
            Iterator it = this.messagelistitems.iterator();
            while (it.hasNext())
            {
                Message m2 = (Message) it.next();

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
            Log.i(TAG, "update_item:EE:" + e.getMessage());
        }
    }

    @Override
    public CharSequence getSectionText(int position)
    {
        // set fastscroller bluble text
        try
        {
            Message getSectionText_message_object = (Message) messagelistitems.get(position);

            if (getSectionText_message_object.direction == 0)
            {
                // incoming msg
                if (getSectionText_message_object.rcvd_timestamp == getSectionText_message_object_ts)
                {
                    return getSectionText_message_object_ts_string;
                }
                else
                {
                    getSectionText_message_object_ts = getSectionText_message_object.rcvd_timestamp;
                    getSectionText_message_object_ts_string =
                            "  " + only_date_time_format(getSectionText_message_object.rcvd_timestamp) + "          ";
                    return getSectionText_message_object_ts_string;
                }
            }
            else
            {
                // outgoing msg
                if (getSectionText_message_object.sent_timestamp == getSectionText_message_object_ts)
                {
                    return getSectionText_message_object_ts_string;
                }
                else
                {
                    getSectionText_message_object_ts = getSectionText_message_object.sent_timestamp;
                    getSectionText_message_object_ts_string =
                            "  " + only_date_time_format(getSectionText_message_object.sent_timestamp) + "          ";
                    return getSectionText_message_object_ts_string;
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return " ";
        }
    }

    public String getDateHeaderText(int position)
    {
        try
        {
            Message m = (Message) messagelistitems.get(position);
            final long ts = m.direction == 0 ? m.rcvd_timestamp : m.sent_timestamp;
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
                final Message message = messagelistitems.get(i);
                if (message != null && message.id == replyMeta.localMessageId)
                {
                    return i;
                }
            }
        }

        for (int i = 0; i < messagelistitems.size(); i++)
        {
            final Message message = messagelistitems.get(i);
            if (message == null)
            {
                continue;
            }
            final long ts = message.direction == 0 ? message.rcvd_timestamp : message.sent_timestamp;
            if (Math.abs(ts - replyMeta.sortTimestampMs) > 5000L)
            {
                continue;
            }
            if (replyMeta.senderPubkeyTail == null || replyMeta.senderPubkeyTail.isEmpty()
                    || MessageReplyHelper.pubkeyTail(message.tox_friendpubkey).equals(replyMeta.senderPubkeyTail))
            {
                return i;
            }
        }
        return -1;
    }

    public static class DateTime_in_out
    {
        long timestamp;
        int direction;
    }

    public MessagelistAdapter.DateTime_in_out getDateTime(int position)
    {
        MessagelistAdapter.DateTime_in_out ret = new MessagelistAdapter.DateTime_in_out();

        try
        {
            // direction: 0 -> msg received, 1 -> msg sent
            Message getSectionText_message_object2 = (Message) messagelistitems.get(position);

            if (getSectionText_message_object2.direction == 0)
            {
                // incoming msg
                ret.direction = 0;
                if (getSectionText_message_object2.msg_version == 1)
                {
                    ret.timestamp = getSectionText_message_object2.sent_timestamp;
                }
                else
                {
                    ret.timestamp = getSectionText_message_object2.rcvd_timestamp;
                }
                return ret;
            }
            else
            {
                // outgoing msg
                ret.direction = 1;
                if (getSectionText_message_object2.msg_version == 1)
                {
                    ret.timestamp = getSectionText_message_object2.sent_timestamp;
                }
                else
                {
                    ret.timestamp = getSectionText_message_object2.sent_timestamp;
                }
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

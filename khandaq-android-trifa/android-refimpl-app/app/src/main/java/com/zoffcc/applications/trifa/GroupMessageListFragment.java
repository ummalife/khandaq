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

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.l4digital.fastscroll.FastScroller;
import com.zoffcc.applications.sorm.GroupMessage;

import java.util.List;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.HelperGeneric.do_fade_anim_on_fab;
import static com.zoffcc.applications.trifa.HelperGeneric.get_sqlite_search_string;
import static com.zoffcc.applications.trifa.HelperGroup.should_show_group_system_messages;
import static com.zoffcc.applications.trifa.MainActivity.PREF__messageview_paging;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_showing_anygroupview;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class GroupMessageListFragment extends Fragment
{
    private static final String TAG = "trifa.GrpMsgListFrgnt";
    List<com.zoffcc.applications.sorm.GroupMessage> data_values = null;
    String current_group_id = "-1";
    com.l4digital.fastscroll.FastScrollRecyclerView listingsView = null;
    GroupMessagelistAdapter adapter = null;
    static boolean is_at_bottom = true;
    static boolean faded_in = false;
    TextView scrollDateHeader = null;
    ConversationDateHeader conversationDateHeader = null;
    boolean is_data_loaded = true;
    static String group_search_messages_text = null;
    // KHANDAQ (user request 17.08): the same attachment folders as a 1:1 chat —
    // documents / audio / voice notes. The ATTACH_* constants live in MessageListFragment.
    static boolean group_show_only_files = false;
    static int group_attachment_filter_kind = MessageListFragment.ATTACH_ALL;
    FloatingActionButton unread_messages_notice_button = null;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        // HelperGeneric.logI(TAG, "onCreateView");
        View view = inflater.inflate(R.layout.group_message_list_layout, container, false);

        unread_messages_notice_button = view.findViewById(R.id.unread_messages_notice_button);
        unread_messages_notice_button.setAnimation(null);
        unread_messages_notice_button.setVisibility(View.INVISIBLE);
        unread_messages_notice_button.setSupportBackgroundTintList(
                (ContextCompat.getColorStateList(context_s, R.color.message_list_scroll_to_bottom_fab_bg_normal)));

        GroupMessageListActivity mla = (GroupMessageListActivity) (getActivity());
        if (mla != null)
        {
            current_group_id = mla.get_current_group_id();
        }
        // HelperGeneric.logI(TAG, "current_conf_id=" + current_conf_id);

        // default is: at bottom
        is_at_bottom = true;
        faded_in = false;

        try
        {
            // reset "new" flags for messages -------
            if (orma != null)
            {
                orma.updateGroupMessage().
                        group_identifierEq(current_group_id.toLowerCase()).
                        is_new(false).execute();
            }
            // reset "new" flags for messages -------
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }


        try
        {
            if (orma != null)
            {
                // Same loader as everywhere else — see load_group_messages_for_display().
                data_values = load_group_messages_for_display();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            // data_values is NULL here!!
        }

        // --------------
        // --------------
        // --------------
        adapter = new GroupMessagelistAdapter(view.getContext(), data_values);
        listingsView = (com.l4digital.fastscroll.FastScrollRecyclerView) view.findViewById(R.id.msg_rv_list);

        scrollDateHeader = (TextView) view.findViewById(R.id.scroll_date_header);
        scrollDateHeader.setText("");
        scrollDateHeader.setVisibility(View.INVISIBLE);
        ChatDateSeparatorHelper.applyTheme(scrollDateHeader);
        conversationDateHeader = new ConversationDateHeader(view.getContext(), scrollDateHeader);

        final LinearLayoutManager linearLayoutManager = new LinearLayoutManager(getActivity());
        linearLayoutManager.setStackFromEnd(true); // pin to bottom element
        listingsView.setLayoutManager(linearLayoutManager);
        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        // No cross-fade on item updates — progress refreshes otherwise make file/video tiles blink.
        itemAnimator.setSupportsChangeAnimations(false);
        listingsView.setItemAnimator(itemAnimator);
        listingsView.setHasFixedSize(false);

        listingsView.setFastScrollListener(new FastScroller.FastScrollListener()
        {
            @Override
            public void onFastScrollStart(FastScroller fastScroller)
            {
                if (!is_at_bottom)
                {
                    if (faded_in)
                    {
                        try
                        {
                            do_fade_anim_on_fab(MainActivity.group_message_list_fragment.unread_messages_notice_button,
                                                false, this.getClass().getName());
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                }
            }

            @Override
            public void onFastScrollStop(FastScroller fastScroller)
            {
                if (!is_at_bottom)
                {
                    if (!faded_in)
                    {
                        try
                        {
                            do_fade_anim_on_fab(MainActivity.group_message_list_fragment.unread_messages_notice_button,
                                                true, this.getClass().getName());
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                }
            }
        });

        RecyclerView.OnScrollListener mScrollListener = new RecyclerView.OnScrollListener()
        {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState)
            {
                super.onScrollStateChanged(recyclerView, newState);

                if (newState == RecyclerView.SCROLL_STATE_DRAGGING)
                {
                    if (!is_at_bottom)
                    {
                        if (faded_in)
                        {
                            try
                            {
                                do_fade_anim_on_fab(
                                        MainActivity.group_message_list_fragment.unread_messages_notice_button, false,
                                        this.getClass().getName());
                            }
                            catch (Exception ignored)
                            {
                            }
                        }
                    }
                    conversationDateHeader.show();
                }
                else if (newState == RecyclerView.SCROLL_STATE_IDLE)
                {
                    if (!is_at_bottom)
                    {
                        if (!faded_in)
                        {
                            try
                            {
                                do_fade_anim_on_fab(
                                        MainActivity.group_message_list_fragment.unread_messages_notice_button, true,
                                        this.getClass().getName());
                            }
                            catch (Exception ignored)
                            {

                            }
                        }
                    }
                    conversationDateHeader.hide();
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy)
            {
                int visibleItemCount = linearLayoutManager.getChildCount();
                int totalItemCount = linearLayoutManager.getItemCount();
                int pastVisibleItems = linearLayoutManager.findFirstVisibleItemPosition();

                scrollDateHeader.setText(adapter.getDateHeaderText(pastVisibleItems));

                if (pastVisibleItems + visibleItemCount >= totalItemCount)
                {
                    // Bottom of the list
                    if (!is_at_bottom)
                    {
                        // HelperGeneric.logI(TAG, "onScrolled:at bottom");
                        is_at_bottom = true;
                        try
                        {
                            do_fade_anim_on_fab(unread_messages_notice_button, false, this.getClass().getName());
                            unread_messages_notice_button.setSupportBackgroundTintList(
                                    (ContextCompat.getColorStateList(context_s,
                                                                     R.color.message_list_scroll_to_bottom_fab_bg_normal)));
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                }
                else
                {
                    if (is_at_bottom)
                    {
                        // HelperGeneric.logI(TAG, "onScrolled:NOT at bottom");
                        is_at_bottom = false;
                        try
                        {
                            do_fade_anim_on_fab(unread_messages_notice_button, true, this.getClass().getName());
                            unread_messages_notice_button.setVisibility(View.VISIBLE);
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                }
            }
        };

        listingsView.addOnScrollListener(mScrollListener);
        listingsView.setAdapter(adapter);
        // --------------
        // --------------

        // KHANDAQ (#209): swipe-to-reply on every group row type.
        final GroupMessagelistAdapter swipe_adapter = adapter;
        new androidx.recyclerview.widget.ItemTouchHelper(new ReplySwipeCallback(getActivity(),
                new ReplySwipeCallback.Trigger()
                {
                    @Override
                    public boolean isSwipeable(int position)
                    {
                        com.zoffcc.applications.sorm.GroupMessage m = swipe_adapter.getMessageAtPosition(position);
                        if (m == null)
                        {
                            return false;
                        }
                        // KHANDAQ (#T3): swipe-to-reply now goes through the RecyclerView-level ItemTouchHelper
                        // for EVERY row type, including incoming text. The old per-ViewHolder daimajia swipe on
                        // incoming-text rows was unreliable on first entry into a group (worked only after
                        // reopening the chat); the daimajia listener on those rows is disabled to avoid a double
                        // gesture. One consistent mechanism = reliable from the first open.
                        return true;
                    }

                    @Override
                    public void onReply(int position)
                    {
                        com.zoffcc.applications.sorm.GroupMessage m = swipe_adapter.getMessageAtPosition(position);
                        if (m != null)
                        {
                            ChatReplyPreviewController.startReplyToGroupMessage(getActivity(), m);
                        }
                    }
                })).attachToRecyclerView(listingsView);


        // a = new MessagelistArrayAdapter(context, data_values);
        // setListAdapter(a);

        // MainActivity.group_message_list_fragment = this;

        is_data_loaded = false;

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState)
    {
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onAttach(Context context)
    {
        HelperGeneric.logI(TAG, "onAttach(Context)");
        super.onAttach(context);
    }

    @Override
    public void onAttach(Activity activity)
    {
        HelperGeneric.logI(TAG, "onAttach(Activity)");
        super.onAttach(activity);
    }

    @Override
    public void onResume()
    {
        global_showing_anygroupview = true;

        HelperGeneric.logI(TAG, "onResume");
        super.onResume();

        try
        {
            final GroupMessageListActivity mla = (GroupMessageListActivity) getActivity();
            if (mla != null)
            {
                current_group_id = mla.get_current_group_id();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            // reset "new" flags for messages -------
            if (orma != null)
            {
                orma.updateGroupMessage().
                        group_identifierEq(current_group_id.toLowerCase()).
                        is_new(false).execute();
            }
            // reset "new" flags for messages -------
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        // KHANDAQ (#22): load off the UI thread so opening a busy group doesn't freeze older phones.
        update_all_messages_async();

        if (!is_data_loaded)
        {
            // default is: at bottom
            is_at_bottom = true;
            is_data_loaded = true;
        }

        MainActivity.group_message_list_fragment = this;
    }

    @Override
    public void onPause()
    {
        super.onPause();

        // KHANDAQ: voice playback is global (ChatVoicePlaybackManager) and keeps playing when the
        // user leaves this group — do NOT stop it here anymore (tester report).

        global_showing_anygroupview = false;
        MainActivity.group_message_list_fragment = null;
    }

    void stopVisibleVoicePlayback()
    {
        if (listingsView == null)
        {
            return;
        }

        try
        {
            for (int i = 0; i < listingsView.getChildCount(); i++)
            {
                final View child = listingsView.getChildAt(i);
                try
                {
                    final RecyclerView.ViewHolder vh = listingsView.getChildViewHolder(child);
                    ((GroupMessageListHolder_file_outgoing_state_cancel) vh).DetachedFromWindow(true);
                }
                catch (Exception ignored)
                {
                }
                try
                {
                    final RecyclerView.ViewHolder vh = listingsView.getChildViewHolder(child);
                    ((GroupMessageListHolder_file_incoming_state_cancel) vh).DetachedFromWindow(true);
                }
                catch (Exception ignored)
                {
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    synchronized void modify_message(final GroupMessage m)
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    adapter.update_item(m);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    synchronized void refresh_file_progress_by_hash(final String msgIdHash)
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    adapter.refresh_file_progress_by_hash(msgIdHash);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    synchronized void add_message(final GroupMessage m)
    {
        Runnable myRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    adapter.add_item(m);
                    if (is_at_bottom)
                    {
                        listingsView.scrollToPosition(adapter.getItemCount() - 1);
                    }
                    else
                    {
                        try
                        {
                            // set color of FAB to "red"-ish color, to indicate that there are also new messages/FTs
                            unread_messages_notice_button.setSupportBackgroundTintList(
                                    (ContextCompat.getColorStateList(context_s,
                                                                     R.color.message_list_scroll_to_bottom_fab_bg_new_message)));
                        }
                        catch (Exception ignored)
                        {
                        }
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(myRunnable);
        }
    }

    void update_all_messages(boolean always, boolean paging)
    {
        HelperGeneric.logI(TAG, "update_all_messages");

        try
        {
            // reset "new" flags for messages -------
            if (orma != null)
            {
                orma.updateGroupMessage().
                        group_identifierEq(current_group_id.toLowerCase()).
                        is_new(false).execute();
            }
            // reset "new" flags for messages -------
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            if ((always) || (data_values != null))
            {
                // KHANDAQ: no early data_values.clear() — the list is shared with the adapter, and
                // add_list_clear() needs the old content to detect "nothing changed" and skip the blink.
                //
                // One loader for every path (this one, onCreateView and the async open-chat path):
                // the four query branches that used to live here were copies of it, and an
                // attachment filter added to the loader alone silently did nothing here.
                adapter.add_list_clear(load_group_messages_for_display());
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "data_values:005:EE1:" + e.getMessage());
        }

    }

    // KHANDAQ (#22): opening a group read+sorted ALL its messages on the UI thread (onResume), which
    // froze older phones on a busy group. Run the heavy DB read + GroupMessageLayoutHelper sort off
    // the UI thread; only the adapter update touches the main thread. A generation counter drops a
    // stale load if the user re-enters/switches before it finishes. Used only for the open-chat path.
    private static volatile int group_message_load_generation = 0;
    private static final java.util.concurrent.ExecutorService group_message_load_executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();


    /**
     * KHANDAQ (user request 17.08): keep only the attachments of the selected kind.
     *
     * Voice notes are checked FIRST and excluded from the audio bucket, so "voice" is a separate
     * place to look rather than a duplicate of "audio" — same rule as the 1:1 screen.
     *
     * The context comes from MainActivity, not getContext(): this runs on a background executor
     * where the fragment may already be detached.
     */
    private static java.util.List<GroupMessage> filter_group_attachments_by_kind(
            final java.util.List<GroupMessage> input)
    {
        if ((input == null) || (group_attachment_filter_kind == MessageListFragment.ATTACH_ALL))
        {
            return input;
        }

        final java.util.List<GroupMessage> kept = new java.util.ArrayList<>(input.size());
        for (final GroupMessage m : input)
        {
            if (m == null)
            {
                continue;
            }
            try
            {
                final boolean is_voice = HelperFiletransfer.isGroupVoiceMessage(m);
                final boolean is_audio = HelperFiletransfer.isGroupAudioMessage(MainActivity.context_s, m);

                final boolean take;
                switch (group_attachment_filter_kind)
                {
                    case MessageListFragment.ATTACH_VOICE:
                        take = is_voice;
                        break;
                    case MessageListFragment.ATTACH_AUDIO:
                        take = is_audio && !is_voice;
                        break;
                    case MessageListFragment.ATTACH_FILES:
                    default:
                        // "Files" means documents: everything that is not a voice note or music.
                        take = !is_voice && !is_audio;
                        break;
                }

                if (take)
                {
                    kept.add(m);
                }
            }
            catch (Exception e)
            {
                // A message whose kind cannot be determined stays visible: dropping an attachment on
                // a classification error would hide someone's file with no way to find it again.
                kept.add(m);
            }
        }
        return kept;
    }

    private java.util.List<GroupMessage> load_group_messages_for_display()
    {
        if (group_show_only_files)
        {
            // System messages are never file messages, so the show-system-messages branch does not
            // need repeating here.
            final java.util.List<GroupMessage> files = orma.selectFromGroupMessage().
                    group_identifierEq(current_group_id.toLowerCase()).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    orderBySent_timestampAsc().
                    toList();
            GroupMessageLayoutHelper.sortMessagesForChatDisplay(files);
            return filter_group_attachments_by_kind(files);
        }

        if ((group_search_messages_text == null) || (group_search_messages_text.length() == 0))
        {
            if (should_show_group_system_messages(current_group_id))
            {
                final java.util.List<GroupMessage> loaded = orma.selectFromGroupMessage().
                        group_identifierEq(current_group_id.toLowerCase()).
                        orderBySent_timestampAsc().
                        toList();
                GroupMessageLayoutHelper.sortMessagesForChatDisplay(loaded);
                return loaded;
            }
            final java.util.List<GroupMessage> loaded = orma.selectFromGroupMessage().
                    group_identifierEq(current_group_id.toLowerCase()).
                    tox_group_peer_pubkeyNotEq(TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY).
                    orderBySent_timestampAsc().
                    toList();
            GroupMessageLayoutHelper.sortMessagesForChatDisplay(loaded);
            return loaded;
        }

        if (should_show_group_system_messages(current_group_id))
        {
            return orma.selectFromGroupMessage().
                    group_identifierEq(current_group_id.toLowerCase()).
                    orderBySent_timestampAsc().
                    textLike(get_sqlite_search_string(group_search_messages_text)).
                    toList();
        }
        return orma.selectFromGroupMessage().
                group_identifierEq(current_group_id.toLowerCase()).
                tox_group_peer_pubkeyNotEq(TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY).
                orderBySent_timestampAsc().
                textLike(get_sqlite_search_string(group_search_messages_text)).
                toList();
    }

    void update_all_messages_async()
    {
        final int loadGen = ++group_message_load_generation;
        group_message_load_executor.execute(new Runnable()
        {
            @Override
            public void run()
            {
                // reset "new" flags (DB write) off the UI thread
                try
                {
                    if (orma != null && current_group_id != null)
                    {
                        orma.updateGroupMessage().
                                group_identifierEq(current_group_id.toLowerCase()).
                                is_new(false).execute();
                    }
                }
                catch (Exception ignored)
                {
                }

                final java.util.List<GroupMessage> loaded;
                try
                {
                    loaded = load_group_messages_for_display();
                }
                catch (Exception e)
                {
                    return;
                }
                if (loaded == null)
                {
                    return;
                }

                final Activity act = getActivity();
                if (act == null)
                {
                    return;
                }

                act.runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (loadGen != group_message_load_generation)
                        {
                            return; // a newer load superseded this one
                        }
                        try
                        {
                            // no early clear — shared list, see add_list_clear unchanged-skip
                            adapter.add_list_clear(loaded);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                });
            }
        });
    }

    public void scrollToReplyTarget(final MessageReplyHelper.ReplyMeta replyMeta)
    {
        if (adapter == null || listingsView == null || replyMeta == null)
        {
            return;
        }

        final int position = adapter.findPositionForReply(replyMeta);
        if (position >= 0)
        {
            listingsView.smoothScrollToPosition(position);
        }
    }

}

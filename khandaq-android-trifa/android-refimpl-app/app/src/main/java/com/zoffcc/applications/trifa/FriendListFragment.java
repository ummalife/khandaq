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

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.zoffcc.applications.sorm.ConferenceDB;
import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_CONFERENCE;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FRIEND;
import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_GROUP;
import static com.zoffcc.applications.trifa.MainActivity.main_handler_s;
import static com.zoffcc.applications.trifa.TRIFAGlobals.INTERVAL_ADD_ALL_FRIENDS_CLEAR_MS;
import static com.zoffcc.applications.trifa.TRIFAGlobals.ONE_HOUR_IN_MS;
import static com.zoffcc.applications.trifa.HelperGroup.is_removed_legacy_public_community_group;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class FriendListFragment extends Fragment
{
    public interface ChatFilterPagerListener
    {
        void onChatFilterPageScrolled(int position, float positionOffset);

        void onChatFilterPageSelected(int tab);
    }

    private static final String TAG = "trifa.FriendListFrgnt";
    public static final String ARG_LIST_MODE = "list_mode";
    public static final int LIST_MODE_CHATS = 0;
    public static final int LIST_MODE_CONTACTS = 1;

    public static final int CHAT_FILTER_DIRECT = 0;
    public static final int CHAT_FILTER_GROUPS = 1;
    public static final int CHAT_FILTER_FAVORITES = 2;

    static final int MessageListActivity_ID = 2;
    static final int FriendInfoActivity_ID = 3;
    List<FriendList> data_values2 = new ArrayList<FriendList>();
    // FriendlistArrayAdapter a = null;
    static Boolean in_update_data = false;
    static final Boolean in_update_data_lock = false;
    //  View view1 = null;
    com.l4digital.fastscroll.FastScrollRecyclerView listingsView = null;
    static View fl_loading_progressbar = null;
    FriendlistAdapter adapter = null;
    private ListEmptyStateHelper emptyStateHelper = null;
    @Nullable
    private RecyclerView.AdapterDataObserver emptyStateObserver = null;
    public static Semaphore semaphore_friendlist_ui_01 = new Semaphore(1);
    private final Semaphore listReloadSemaphore = new Semaphore(1);
    private long add_all_friends_clear_last_trigger_ts = 0;
    private static final long PRESENCE_REFRESH_INTERVAL_MS = 60_000L;
    private static final long CHAT_LIST_FULL_RELOAD_MIN_INTERVAL_MS = 2_500L;
    private long chat_list_full_reload_last_ts = 0L;
    private Runnable pendingFullChatListReloadRunnable = null;
    private int listMode = LIST_MODE_CHATS;
    private int chatFilterTab = CHAT_FILTER_DIRECT;
    private ViewPager2 chatFilterPager = null;
    private ChatFilterListPage[] chatFilterPages = new ChatFilterListPage[3];
    private ChatFilterPagerListener chatFilterPagerListener = null;
    private boolean suppressPagerCallback = false;

    public void setChatFilterTab(final int tab)
    {
        if (listMode != LIST_MODE_CHATS)
        {
            return;
        }

        final int normalizedTab = normalizeChatFilterTab(tab);
        if (chatFilterTab == normalizedTab)
        {
            return;
        }

        applyChatFilterTabFromMain(tab, true);
    }

    void syncChatFilterTabSelection(final int tab)
    {
        chatFilterTab = normalizeChatFilterTab(tab);
        if (usesChatFilterPager())
        {
            activateChatFilterPage(chatFilterTab);
        }
        else
        {
            refreshEmptyState();
        }
    }

    public void setChatFilterPagerListener(final ChatFilterPagerListener listener)
    {
        chatFilterPagerListener = listener;
    }

    public void selectChatFilterPage(final int tab, final boolean smooth)
    {
        if (chatFilterPager == null)
        {
            chatFilterTab = normalizeChatFilterTab(tab);
            return;
        }

        final int normalizedTab = normalizeChatFilterTab(tab);
        chatFilterTab = normalizedTab;
        suppressPagerCallback = true;
        chatFilterPager.setCurrentItem(normalizedTab, smooth);
        suppressPagerCallback = false;
        activateChatFilterPage(normalizedTab);
    }

    private boolean usesChatFilterPager()
    {
        return listMode == LIST_MODE_CHATS && chatFilterPager != null;
    }

    private void activateChatFilterPage(final int tab)
    {
        chatFilterTab = normalizeChatFilterTab(tab);
        if (chatFilterPages != null && tab >= 0 && tab < chatFilterPages.length)
        {
            final ChatFilterListPage page = chatFilterPages[tab];
            if (page != null)
            {
                adapter = page.adapter;
                listingsView = page.recyclerView;
            }
        }
        refreshEmptyState();
    }

    void applyChatFilterTabFromMain(final int tab, final boolean reloadList)
    {
        if (listMode != LIST_MODE_CHATS)
        {
            return;
        }

        chatFilterTab = normalizeChatFilterTab(tab);
        if (usesChatFilterPager())
        {
            if (reloadList)
            {
                reloadChatsListFromDb(0);
            }
            else
            {
                refreshAllChatFilterEmptyStates();
            }
            return;
        }

        if (reloadList)
        {
            reloadChatsListFromDb(0);
        }
        else
        {
            refreshEmptyState();
        }
    }

    private void refreshAllChatFilterEmptyStates()
    {
        if (!usesChatFilterPager() || chatFilterPages == null)
        {
            refreshEmptyState();
            return;
        }

        final boolean loadingVisible = fl_loading_progressbar != null
                && fl_loading_progressbar.getVisibility() == View.VISIBLE;
        for (int i = 0; i < chatFilterPages.length; i++)
        {
            if (chatFilterPages[i] != null)
            {
                chatFilterPages[i].refreshEmptyState(loadingVisible);
            }
        }
    }

    private void handleEmptyStateActionForTab(final int filterTab)
    {
        final Activity activity = getActivity();
        if (!(activity instanceof MainActivity))
        {
            return;
        }

        final MainActivity mainActivity = (MainActivity) activity;
        if (listMode == LIST_MODE_CONTACTS || filterTab == CHAT_FILTER_DIRECT)
        {
            mainActivity.show_add_friend(null);
            return;
        }

        if (filterTab == CHAT_FILTER_GROUPS)
        {
            mainActivity.show_create_private_group(null);
        }
    }

    private int normalizeChatFilterTab(final int tab)
    {
        if (tab == CHAT_FILTER_GROUPS)
        {
            return CHAT_FILTER_GROUPS;
        }
        if (tab == CHAT_FILTER_FAVORITES)
        {
            return CHAT_FILTER_FAVORITES;
        }
        return CHAT_FILTER_DIRECT;
    }

    private boolean shouldIncludeInChatList(final CombinedFriendsAndConferences cfac)
    {
        if (listMode != LIST_MODE_CHATS || getContext() == null)
        {
            return true;
        }
        return ChatListFilterHelper.matchesFilter(getContext(), cfac, chatFilterTab);
    }

    private void upsertChatListItem(final CombinedFriendsAndConferences cfac, final int entityType)
    {
        if (usesChatFilterPager())
        {
            if (getContext() == null || chatFilterPages == null)
            {
                return;
            }

            for (int i = 0; i < chatFilterPages.length; i++)
            {
                if (chatFilterPages[i] != null)
                {
                    chatFilterPages[i].upsertItem(getContext(), cfac, entityType);
                }
            }
            return;
        }

        if (adapter == null || !isAdded())
        {
            return;
        }

        final boolean shouldShow = shouldIncludeInChatList(cfac);
        final boolean found = adapter.update_item(cfac, entityType);
        if (shouldShow)
        {
            if (!found)
            {
                adapter.add_item(cfac);
            }
        }
        else if (found)
        {
            adapter.remove_item(cfac, entityType);
        }
    }

    public int getChatFilterTab()
    {
        return chatFilterTab;
    }

    public void notifyChatListUpdated()
    {
        final Activity activity = getActivity();
        if (activity instanceof MainActivity)
        {
            ((MainActivity) activity).refreshChatFilterTabBadges();
        }
    }

    public static FriendListFragment newInstance(final int listMode)
    {
        final FriendListFragment fragment = new FriendListFragment();
        final Bundle args = new Bundle();
        args.putInt(ARG_LIST_MODE, listMode);
        fragment.setArguments(args);
        return fragment;
    }

    public boolean isContactsListMode()
    {
        return listMode == LIST_MODE_CONTACTS;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        if (getArguments() != null)
        {
            listMode = getArguments().getInt(ARG_LIST_MODE, LIST_MODE_CHATS);
        }
    }

    private final Runnable presence_refresh_runnable = new Runnable()
    {
        @Override
        public void run()
        {
            // Connection dots / subtitles are resolved on bind; avoid full-list redraws here.
            if (main_handler_s != null && isResumed())
            {
                main_handler_s.postDelayed(this, PRESENCE_REFRESH_INTERVAL_MS);
            }
        }
    };

    public void refresh_group_chat_list_row(final String groupIdentifier)
    {
        if (groupIdentifier == null || main_handler_s == null)
        {
            return;
        }

        main_handler_s.post(() ->
        {
            try
            {
                if (!isAdded())
                {
                    return;
                }

                if (usesChatFilterPager() && chatFilterPages != null)
                {
                    for (int i = 0; i < chatFilterPages.length; i++)
                    {
                        if (chatFilterPages[i] != null && chatFilterPages[i].adapter != null)
                        {
                            chatFilterPages[i].adapter.notifyGroupRowChanged(groupIdentifier);
                        }
                    }
                    return;
                }

                if (adapter != null)
                {
                    adapter.notifyGroupRowChanged(groupIdentifier);
                }
            }
            catch (Exception ignored)
            {
            }
        });
    }

    private void scheduleDeferredFullChatListReload(final int delay)
    {
        if (main_handler_s == null)
        {
            return;
        }
        if (pendingFullChatListReloadRunnable != null)
        {
            main_handler_s.removeCallbacks(pendingFullChatListReloadRunnable);
        }
        final int initialDelay = Math.max(delay, 0);
        pendingFullChatListReloadRunnable = () ->
        {
            pendingFullChatListReloadRunnable = null;
            chat_list_full_reload_last_ts = 0L;
            add_all_friends_clear_real(0);
        };
        main_handler_s.postDelayed(pendingFullChatListReloadRunnable,
                initialDelay + CHAT_LIST_FULL_RELOAD_MIN_INTERVAL_MS);
    }

    private boolean shouldRunFullChatListReload()
    {
        final long now = System.currentTimeMillis();
        if ((now - chat_list_full_reload_last_ts) < CHAT_LIST_FULL_RELOAD_MIN_INTERVAL_MS)
        {
            return false;
        }
        chat_list_full_reload_last_ts = now;
        return true;
    }

    private void start_presence_refresh_timer()
    {
        // Intentionally disabled: connection/subtitle state is bound on demand, not polled.
    }

    private void stop_presence_refresh_timer()
    {
        if (main_handler_s != null)
        {
            main_handler_s.removeCallbacks(presence_refresh_runnable);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        super.onCreateView(inflater, container, savedInstanceState);

        Log.i(TAG, "onCreateView");
        if (listMode == LIST_MODE_CHATS)
        {
            return createChatsPagerView(inflater, container);
        }
        return createSingleListView(inflater, container);
    }

    private View createSingleListView(final LayoutInflater inflater, final ViewGroup container)
    {
        final View view1 = inflater.inflate(R.layout.friend_list_layout, container, false);
        Log.i(TAG, "onCreateView:view1=" + view1);

        final List<CombinedFriendsAndConferences> data_values = new ArrayList<>();
        data_values.clear();

        fl_loading_progressbar = view1.findViewById(R.id.fl_loading_progressbar);
        fl_loading_progressbar.setVisibility(View.GONE);

        listingsView = view1.findViewById(R.id.rv_list);
        listingsView.getRecycledViewPool().clear();
        listingsView.setLayoutManager(new LinearLayoutManager(view1.getContext()));
        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        listingsView.setItemAnimator(itemAnimator);
        listingsView.setHasFixedSize(true);

        adapter = new FriendlistAdapter(view1.getContext(), data_values);
        listingsView.setAdapter(adapter);
        listingsView.getRecycledViewPool().clear();
        adapter.clear_items();
        adapter.notifyDataSetChanged();

        emptyStateHelper = new ListEmptyStateHelper(view1.findViewById(R.id.list_empty_state));
        // KHANDAQ (Figma contacts empty-state): action1 = Копировать MyID, action2 = Показать QR-код.
        emptyStateHelper.setOnActionClickListener(this::copyMyIdFromEmptyState);
        emptyStateHelper.setOnAction2ClickListener(this::showMyQrFromEmptyState);
        initEmptyStateObserver();
        adapter.registerAdapterDataObserver(emptyStateObserver);
        refreshEmptyState();

        registerListFragmentReference();
        return view1;
    }

    private View createChatsPagerView(final LayoutInflater inflater, final ViewGroup container)
    {
        final View root = inflater.inflate(R.layout.friend_list_chats_pager_layout, container, false);
        fl_loading_progressbar = root.findViewById(R.id.fl_loading_progressbar);
        if (fl_loading_progressbar != null)
        {
            fl_loading_progressbar.setVisibility(View.GONE);
        }

        chatFilterPager = root.findViewById(R.id.chat_filter_pager);
        initEmptyStateObserver();

        final RecyclerView.Adapter<ChatFilterPageHolder> pagerAdapter = new RecyclerView.Adapter<ChatFilterPageHolder>()
        {
            @NonNull
            @Override
            public ChatFilterPageHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType)
            {
                final View pageView = inflater.inflate(R.layout.chat_list_filter_page, parent, false);
                final int filterTab = ChatFilterListPage.filterTabForPosition(viewType);
                return new ChatFilterPageHolder(pageView, filterTab);
            }

            @Override
            public void onBindViewHolder(@NonNull final ChatFilterPageHolder holder, final int position)
            {
                chatFilterPages[position] = holder.page;
                if (position == chatFilterTab)
                {
                    activateChatFilterPage(position);
                }
            }

            @Override
            public int getItemCount()
            {
                return 3;
            }

            @Override
            public int getItemViewType(final int position)
            {
                return position;
            }
        };

        chatFilterPager.setAdapter(pagerAdapter);
        chatFilterPager.setOffscreenPageLimit(2);
        chatFilterPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback()
        {
            @Override
            public void onPageScrolled(final int position, final float positionOffset, final int positionOffsetPixels)
            {
                if (chatFilterPagerListener != null)
                {
                    chatFilterPagerListener.onChatFilterPageScrolled(position, positionOffset);
                }
            }

            @Override
            public void onPageSelected(final int position)
            {
                activateChatFilterPage(position);
                if (!suppressPagerCallback && chatFilterPagerListener != null)
                {
                    chatFilterPagerListener.onChatFilterPageSelected(position);
                }
            }
        });

        chatFilterPager.setCurrentItem(chatFilterTab, false);
        registerListFragmentReference();
        return root;
    }

    private void initEmptyStateObserver()
    {
        emptyStateObserver = new RecyclerView.AdapterDataObserver()
        {
            @Override
            public void onChanged()
            {
                refreshEmptyState();
            }

            @Override
            public void onItemRangeInserted(final int positionStart, final int itemCount)
            {
                refreshEmptyState();
            }

            @Override
            public void onItemRangeRemoved(final int positionStart, final int itemCount)
            {
                refreshEmptyState();
            }
        };
    }

    private final class ChatFilterPageHolder extends RecyclerView.ViewHolder
    {
        final ChatFilterListPage page;

        ChatFilterPageHolder(final View itemView, final int filterTab)
        {
            super(itemView);
            page = new ChatFilterListPage(filterTab);
            page.bindView(itemView, FriendListFragment.this::handleEmptyStateActionForTab);
            page.registerEmptyStateObserver(emptyStateObserver);
        }
    }

    private void registerListFragmentReference()
    {
        if (listMode == LIST_MODE_CHATS)
        {
            MainActivity.friend_list_fragment = this;
        }
        else if (listMode == LIST_MODE_CONTACTS)
        {
            MainActivity.contacts_list_fragment = this;
        }
    }

    @Override
    public void onDestroyView()
    {
        if (emptyStateObserver != null)
        {
            if (usesChatFilterPager() && chatFilterPages != null)
            {
                for (int i = 0; i < chatFilterPages.length; i++)
                {
                    if (chatFilterPages[i] != null)
                    {
                        chatFilterPages[i].unregisterEmptyStateObserver(emptyStateObserver);
                    }
                }
            }
            else if (adapter != null)
            {
                try
                {
                    adapter.unregisterAdapterDataObserver(emptyStateObserver);
                }
                catch (Exception ignored)
                {
                }
            }
        }

        emptyStateObserver = null;
        emptyStateHelper = null;
        chatFilterPager = null;
        chatFilterPages = new ChatFilterListPage[3];

        if (listMode == LIST_MODE_CHATS && MainActivity.friend_list_fragment == this)
        {
            MainActivity.friend_list_fragment = null;
        }
        else if (listMode == LIST_MODE_CONTACTS && MainActivity.contacts_list_fragment == this)
        {
            MainActivity.contacts_list_fragment = null;
        }

        super.onDestroyView();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState)
    {
        super.onActivityCreated(savedInstanceState);
        Log.i(TAG, "onActivityCreated");

        registerListFragmentReference();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);
        Log.i(TAG, "onViewCreated");

        if (listMode == LIST_MODE_CONTACTS)
        {
            add_all_friends_clear_force(0);
        }
    }

    @Override
    public void onAttach(Context context)
    {
        super.onAttach(context);
        Log.i(TAG, "onAttach(Context)");

        in_update_data = false;
    }

    @Override
    public void onAttach(Activity activity)
    {
        super.onAttach(activity);
        Log.i(TAG, "onAttach(Activity)");
    }

    synchronized void modify_friend(final CombinedFriendsAndConferences c, int is_friend)
    {
        // Log.i(TAG, "modify_friend");

        if (is_friend == COMBINED_IS_FRIEND)
        {
            final FriendList f = c.friend_item;

            if (f == null)
            {
                Log.i(TAG, "modify_friend:EE02:" + f+ " FRIEND is NULL, this should not happen!!");
                return;
            }

            try
            {
                if (f.is_relay == true)
                {
                    // do not update anything if this is a relay
                    return;
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "modify_friend:EE01:" + e.getMessage());
            }

            // Log.i(TAG, "modify_friend:start");
            // KHANDAQ (audit): the DB read + deep_copy used to run on the UI thread (posted runnable).
            // Do that off the UI thread and post ONLY the adapter upsert back to main.
            final FriendList ff = f;
            new Thread(() -> {
                try
                {
                    if (orma == null)
                    {
                        return;
                    }
                    final List<com.zoffcc.applications.sorm.FriendList> rows = orma.selectFromFriendList().
                            tox_public_key_stringEq(ff.tox_public_key_string).
                            toList();
                    if (rows == null || rows.isEmpty() || rows.get(0) == null)
                    {
                        return;
                    }
                    final FriendList n = (FriendList) com.zoffcc.applications.sorm.FriendList.deep_copy(rows.get(0));
                    if (main_handler_s != null)
                    {
                        main_handler_s.post(() -> {
                            try
                            {
                                if (adapter == null || !isAdded())
                                {
                                    return;
                                }
                                CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                                cfac.is_friend = COMBINED_IS_FRIEND;
                                cfac.friend_item = n;
                                upsertChatListItem(cfac, cfac.is_friend);
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                            }
                        });
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }, "friend-modify-db").start();
        }
        else if (is_friend == COMBINED_IS_GROUP)
        {
            final GroupDB cc = c.group_item;
            if (cc == null || cc.group_identifier == null)
            {
                return;
            }

            // Log.i(TAG, "modify_friend:start");
            Runnable myRunnable = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        if (adapter == null || !isAdded())
                        {
                            return;
                        }

                        final List<com.zoffcc.applications.sorm.GroupDB> rows = orma.selectFromGroupDB().
                                group_identifierEq(cc.group_identifier.toLowerCase()).
                                toList();
                        if (rows == null || rows.isEmpty())
                        {
                            return;
                        }

                        final GroupDB conf2 = rows.get(0);

                        if (conf2 != null)
                        {
                            GroupDB n = (GroupDB) GroupDB.deep_copy(conf2);
                            CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                            cfac.is_friend = COMBINED_IS_GROUP;
                            cfac.group_item = n;
                            upsertChatListItem(cfac, cfac.is_friend);
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            };

            try
            {
                if (main_handler_s != null)
                {
                    main_handler_s.post(myRunnable);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "modify_friend:EE1:" + e.getMessage());
            }
        }
        else // is conference -----------------------------
        {
            final ConferenceDB cc = c.conference_item;

            // Log.i(TAG, "modify_friend:start");
            Runnable myRunnable = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        if (adapter == null || !isAdded())
                        {
                            return;
                        }

                        final List<com.zoffcc.applications.sorm.ConferenceDB> rows = orma.selectFromConferenceDB().
                                conference_identifierEq(cc.conference_identifier).
                                toList();
                        if (rows == null || rows.isEmpty())
                        {
                            return;
                        }

                        final ConferenceDB conf2 = rows.get(0);

                        if (conf2 != null)
                        {
                            ConferenceDB n = (ConferenceDB) ConferenceDB.deep_copy(conf2);
                            CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                            cfac.is_friend = COMBINED_IS_CONFERENCE;
                            cfac.conference_item = n;
                            upsertChatListItem(cfac, cfac.is_friend);
                        }
                    }
                    catch (Exception e)
                    {
                        // e.printStackTrace();
                    }
                }
            };

            try
            {
                if (main_handler_s != null)
                {
                    main_handler_s.post(myRunnable);
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "modify_friend:EE1:" + e.getMessage());
            }
        }
    }

    public void remove_group_from_list(@Nullable final String group_identifier)
    {
        if (group_identifier == null || adapter == null)
        {
            return;
        }

        Runnable runnable = new Runnable()
        {
            @Override
            public void run()
            {
                adapter.remove_group_item(group_identifier);
            }
        };

        if (main_handler_s != null)
        {
            main_handler_s.post(runnable);
        }
    }

    @Override
    public void onStart()
    {
        super.onStart();
        Log.i(TAG, "onStart");
    }

    @Override
    public void onResume()
    {
        super.onResume();

        Log.i(TAG, "onResume");

        // reset friend update trigger timestamp
        add_all_friends_clear_last_trigger_ts = 0;

        try
        {
            FriendListHolder.remove_progress_dialog();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            ConferenceListHolder.remove_progress_dialog();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            GroupListHolder.remove_progress_dialog();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        Log.i(TAG, "onResume");

        try
        {
            boolean SORT_CORRECTLY = true;
            if (SORT_CORRECTLY != true)
            {
                try
                {
                    // reload friendlist
                    Log.i(TAG, "onResume:AA");
                    List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().
                            is_relayNotEq(true).
                            orderByTOX_CONNECTION_on_offDesc().
                            orderByNotification_silentAsc().
                            orderByLast_online_timestampDesc().
                            toList();

                    if (fl != null)
                    {
                        Log.i(TAG, "onResume:fl.size=" + fl.size());
                        if (fl.size() > 0)
                        {
                            int i = 0;
                            for (i = 0; i < fl.size(); i++)
                            {
                                FriendList n = (FriendList) com.zoffcc.applications.sorm.FriendList.deep_copy(fl.get(i));
                                final CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
                                cc.is_friend = COMBINED_IS_FRIEND;
                                cc.friend_item = n;
                                modify_friend(cc, cc.is_friend);
                                // Log.i(TAG, "onResume:modify_friend:" + n);
                            }
                        }
                    }

                    // reload conferences
                    List<com.zoffcc.applications.sorm.ConferenceDB> confs = orma.selectFromConferenceDB().
                            orderByConference_activeDesc().
                            orderByNotification_silentAsc().
                            toList();

                    if (confs != null)
                    {
                        if (confs.size() > 0)
                        {
                            int i = 0;
                            for (i = 0; i < confs.size(); i++)
                            {
                                ConferenceDB n = (ConferenceDB) ConferenceDB.deep_copy(confs.get(i));
                                CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                                cfac.is_friend = COMBINED_IS_CONFERENCE;
                                cfac.conference_item = n;
                                modify_friend(cfac, cfac.is_friend);
                                // Log.i(TAG, "onResume:modify_friend:" + n);
                            }
                        }
                    }

                    // reload groups
                    List<com.zoffcc.applications.sorm.GroupDB> groups = orma.selectFromGroupDB().
                            orderByNotification_silentAsc().
                            toList();

                    if (groups != null)
                    {
                        if (groups.size() > 0)
                        {
                            int i = 0;
                            for (i = 0; i < groups.size(); i++)
                            {
                                if (is_removed_legacy_public_community_group(groups.get(i).group_identifier))
                                {
                                    continue;
                                }
                                GroupDB n = (GroupDB) GroupDB.deep_copy(groups.get(i));
                                CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                                cfac.is_friend = COMBINED_IS_GROUP;
                                cfac.group_item = n;
                                modify_friend(cfac, cfac.is_friend);
                                // Log.i(TAG, "onResume:modify_friend:" + n);
                            }
                        }
                    }


                    Log.i(TAG, "onResume:BB");
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            else
            {
                add_all_friends_clear(0);
            }
        }
        catch (Exception ee)
        {
            ee.printStackTrace();

            try
            {
                // reload friendlist
                Log.i(TAG, "onResume:AA");
                List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().
                        is_relayNotEq(true).
                        orderByTOX_CONNECTION_on_offDesc().
                        orderByNotification_silentAsc().
                        orderByLast_online_timestampDesc().
                        toList();

                if (fl != null)
                {
                    Log.i(TAG, "onResume:fl.size=" + fl.size());
                    if (fl.size() > 0)
                    {
                        int i = 0;
                        for (i = 0; i < fl.size(); i++)
                        {
                            FriendList n = (FriendList) com.zoffcc.applications.sorm.FriendList.deep_copy(fl.get(i));
                            final CombinedFriendsAndConferences cc = new CombinedFriendsAndConferences();
                            cc.is_friend = COMBINED_IS_FRIEND;
                            cc.friend_item = n;
                            modify_friend(cc, cc.is_friend);
                            // Log.i(TAG, "onResume:modify_friend:" + n);
                        }
                    }
                }

                // reload conferences
                List<com.zoffcc.applications.sorm.ConferenceDB> confs = orma.selectFromConferenceDB().
                        orderByConference_activeDesc().
                        orderByNotification_silentAsc().
                        toList();

                if (confs != null)
                {
                    if (confs.size() > 0)
                    {
                        int i = 0;
                        for (i = 0; i < confs.size(); i++)
                        {
                            ConferenceDB n = (ConferenceDB) ConferenceDB.deep_copy(confs.get(i));
                            CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                            cfac.is_friend = COMBINED_IS_CONFERENCE;
                            cfac.conference_item = n;
                            modify_friend(cfac, cfac.is_friend);
                            // Log.i(TAG, "onResume:modify_friend:" + n);
                        }
                    }
                }

                // reload groups
                List<com.zoffcc.applications.sorm.GroupDB> groups = orma.selectFromGroupDB().
                        orderByNotification_silentAsc().
                        toList();

                if (groups != null)
                {
                    if (groups.size() > 0)
                    {
                        int i = 0;
                        for (i = 0; i < groups.size(); i++)
                        {
                            if (is_removed_legacy_public_community_group(groups.get(i).group_identifier))
                            {
                                continue;
                            }
                            GroupDB n = (GroupDB) GroupDB.deep_copy(groups.get(i));
                            CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                            cfac.is_friend = COMBINED_IS_GROUP;
                            cfac.group_item = n;
                            modify_friend(cfac, cfac.is_friend);
                            // Log.i(TAG, "onResume:modify_friend:" + n);
                        }
                    }
                }

                Log.i(TAG, "onResume:BB");
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        MainActivity.friend_list_fragment = (listMode == LIST_MODE_CHATS) ? this : MainActivity.friend_list_fragment;
        if (listMode == LIST_MODE_CONTACTS)
        {
            MainActivity.contacts_list_fragment = this;
        }
        start_presence_refresh_timer();
    }

    @Override
    public void onPause()
    {
        stop_presence_refresh_timer();
        super.onPause();
    }

    void add_all_friends_clear_force(final int delay)
    {
        add_all_friends_clear_last_trigger_ts = 0;
        add_all_friends_clear_real(delay);
    }

    private List<CombinedFriendsAndConferences> buildChatsListItems()
    {
        return filterChatsListItems(buildRawChatsListItems());
    }

    private List<CombinedFriendsAndConferences> buildRawChatsListItems()
    {
        final List<CombinedFriendsAndConferences> items = new ArrayList<>();

        List<com.zoffcc.applications.sorm.FriendList> fl = orma.selectFromFriendList().
                is_relayNotEq(true).
                added_timestampGt(System.currentTimeMillis() - ONE_HOUR_IN_MS).
                orderByTOX_CONNECTION_on_offDesc().
                orderByNotification_silentAsc().
                orderByLast_online_timestampDesc().
                toList();

        if (fl != null)
        {
            for (int i = 0; i < fl.size(); i++)
            {
                FriendList n = (FriendList) FriendList.deep_copy(fl.get(i));
                if (HelperFriend.is_own_public_key(n.tox_public_key_string))
                {
                    continue;
                }
                CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                cfac.is_friend = COMBINED_IS_FRIEND;
                cfac.friend_item = n;
                items.add(cfac);
            }
        }

        List<com.zoffcc.applications.sorm.FriendList> fl2m = orma.selectFromFriendList().
                is_relayNotEq(true).
                added_timestampLe(System.currentTimeMillis() - ONE_HOUR_IN_MS).
                orderByTOX_CONNECTION_on_offDesc().
                orderByNotification_silentAsc().
                orderByLast_online_timestampDesc().
                toList();

        if (fl2m != null)
        {
            for (int i = 0; i < fl2m.size(); i++)
            {
                FriendList n = (FriendList) FriendList.deep_copy(fl2m.get(i));
                if (HelperFriend.is_own_public_key(n.tox_public_key_string))
                {
                    continue;
                }
                try
                {
                    int new_messages_count = orma.selectFromMessage().tox_friendpubkeyEq(
                            n.tox_public_key_string).is_newEq(true).count();
                    if (new_messages_count > 0)
                    {
                        CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                        cfac.is_friend = COMBINED_IS_FRIEND;
                        cfac.friend_item = n;
                        items.add(cfac);
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }

        List<com.zoffcc.applications.sorm.ConferenceDB> confsm = orma.selectFromConferenceDB().
                orderByConference_activeDesc().
                orderByNotification_silentAsc().
                toList();

        if (confsm != null)
        {
            for (int i = 0; i < confsm.size(); i++)
            {
                ConferenceDB n = (ConferenceDB) ConferenceDB.deep_copy(confsm.get(i));
                try
                {
                    int new_messages_count = orma.selectFromConferenceMessage().
                            conference_identifierEq(n.conference_identifier).is_newEq(true).count();
                    if (new_messages_count > 0)
                    {
                        CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                        cfac.is_friend = COMBINED_IS_CONFERENCE;
                        cfac.conference_item = n;
                        items.add(cfac);
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }

        List<com.zoffcc.applications.sorm.GroupDB> groupsm = orma.selectFromGroupDB().
                orderByNotification_silentAsc().
                toList();

        if (groupsm != null)
        {
            for (int i = 0; i < groupsm.size(); i++)
            {
                if (is_removed_legacy_public_community_group(groupsm.get(i).group_identifier))
                {
                    continue;
                }
                GroupDB n = (GroupDB) GroupDB.deep_copy(groupsm.get(i));
                try
                {
                    int new_messages_count = orma.selectFromGroupMessage().
                            group_identifierEq(n.group_identifier.toLowerCase()).is_newEq(true).count();
                    if (new_messages_count > 0)
                    {
                        CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                        cfac.is_friend = COMBINED_IS_GROUP;
                        cfac.group_item = n;
                        items.add(cfac);
                    }
                }
                catch (Exception ignored)
                {
                }
            }
        }

        List<com.zoffcc.applications.sorm.FriendList> fl2 = orma.selectFromFriendList().
                is_relayNotEq(true).
                added_timestampLe(System.currentTimeMillis() - ONE_HOUR_IN_MS).
                orderByTOX_CONNECTION_on_offDesc().
                orderByNotification_silentAsc().
                orderByLast_online_timestampDesc().
                toList();

        if (fl2 != null)
        {
            for (int i = 0; i < fl2.size(); i++)
            {
                FriendList n = (FriendList) FriendList.deep_copy(fl2.get(i));
                if (HelperFriend.is_own_public_key(n.tox_public_key_string))
                {
                    continue;
                }
                int new_messages_count = 0;
                try
                {
                    new_messages_count = orma.selectFromMessage().tox_friendpubkeyEq(
                            n.tox_public_key_string).is_newEq(true).count();
                }
                catch (Exception ignored)
                {
                }

                if (new_messages_count == 0)
                {
                    CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                    cfac.is_friend = COMBINED_IS_FRIEND;
                    cfac.friend_item = n;
                    items.add(cfac);
                }
            }
        }

        List<com.zoffcc.applications.sorm.ConferenceDB> confs = orma.selectFromConferenceDB().
                orderByConference_activeDesc().
                orderByNotification_silentAsc().
                toList();

        if (confs != null)
        {
            for (int i = 0; i < confs.size(); i++)
            {
                ConferenceDB n = (ConferenceDB) ConferenceDB.deep_copy(confs.get(i));
                int new_messages_count = 0;
                try
                {
                    new_messages_count = orma.selectFromConferenceMessage().
                            conference_identifierEq(n.conference_identifier).is_newEq(true).count();
                }
                catch (Exception ignored)
                {
                }

                if (new_messages_count == 0)
                {
                    CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                    cfac.is_friend = COMBINED_IS_CONFERENCE;
                    cfac.conference_item = n;
                    items.add(cfac);
                }
            }
        }

        List<com.zoffcc.applications.sorm.GroupDB> groups = orma.selectFromGroupDB().
                orderByNotification_silentAsc().
                toList();

        if (groups != null)
        {
            for (int i = 0; i < groups.size(); i++)
            {
                if (is_removed_legacy_public_community_group(groups.get(i).group_identifier))
                {
                    continue;
                }
                GroupDB n = (GroupDB) GroupDB.deep_copy(groups.get(i));
                int new_messages_count = 0;
                try
                {
                    new_messages_count = orma.selectFromGroupMessage().
                            group_identifierEq(n.group_identifier.toLowerCase()).is_newEq(true).count();
                }
                catch (Exception ignored)
                {
                }

                if (new_messages_count == 0)
                {
                    CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                    cfac.is_friend = COMBINED_IS_GROUP;
                    cfac.group_item = n;
                    items.add(cfac);
                }
            }
        }

        return items;
    }

    private List<CombinedFriendsAndConferences> filterChatsListItems(final List<CombinedFriendsAndConferences> items)
    {
        if (listMode != LIST_MODE_CHATS || getContext() == null)
        {
            return items;
        }

        final List<CombinedFriendsAndConferences> filtered = new ArrayList<>();
        for (int i = 0; i < items.size(); i++)
        {
            if (ChatListFilterHelper.matchesFilter(getContext(), items.get(i), chatFilterTab))
            {
                filtered.add(items.get(i));
            }
        }
        FavoritesChatHelper.prependPinnedRow(filtered);
        return filtered;
    }

    private void reloadChatsListFromDb(final int delay)
    {
        final Thread loaderThread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    if (delay > 0)
                    {
                        Thread.sleep(delay);
                    }

                    listReloadSemaphore.acquire();
                    final List<CombinedFriendsAndConferences> loadedItems = buildRawChatsListItems();

                    if (main_handler_s == null)
                    {
                        listReloadSemaphore.release();
                        return;
                    }

                    main_handler_s.post(() -> {
                        try
                        {
                            if (!isAdded())
                            {
                                return;
                            }

                            if (usesChatFilterPager())
                            {
                                if (getContext() == null)
                                {
                                    return;
                                }

                                for (int i = 0; i < chatFilterPages.length; i++)
                                {
                                    if (chatFilterPages[i] != null)
                                    {
                                        chatFilterPages[i].setFilteredItems(getContext(), loadedItems);
                                    }
                                }
                                activateChatFilterPage(chatFilterTab);
                                notifyChatListUpdated();
                                return;
                            }

                            if (adapter == null)
                            {
                                return;
                            }

                            final List<CombinedFriendsAndConferences> filteredItems =
                                    filterChatsListItems(loadedItems);
                            adapter.replaceAllItems(filteredItems);
                            notifyChatListUpdated();
                        }
                        catch (Exception e)
                        {
                            Log.i(TAG, "reloadChatsListFromDb:UI:EE:" + e.getMessage());
                        }
                        finally
                        {
                            listReloadSemaphore.release();
                        }
                    });
                }
                catch (InterruptedException e)
                {
                    listReloadSemaphore.release();
                }
                catch (Exception e)
                {
                    Log.i(TAG, "reloadChatsListFromDb:EE:" + e.getMessage());
                    listReloadSemaphore.release();
                }
            }
        };
        loaderThread.start();
    }

    private void reloadContactsListFromDb(final int delay)
    {
        final Thread loaderThread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    if (delay > 0)
                    {
                        Thread.sleep(delay);
                    }

                    listReloadSemaphore.acquire();

                    final List<com.zoffcc.applications.sorm.FriendList> allFriends =
                            orma.selectFromFriendList().
                                    is_relayNotEq(true).
                                    orderByTOX_CONNECTION_on_offDesc().
                                    orderByNotification_silentAsc().
                                    orderByLast_online_timestampDesc().
                                    toList();

                    final List<CombinedFriendsAndConferences> loadedItems = new ArrayList<>();
                    if (allFriends != null)
                    {
                        for (int i = 0; i < allFriends.size(); i++)
                        {
                            FriendList n = (FriendList) FriendList.deep_copy(allFriends.get(i));
                            CombinedFriendsAndConferences cfac = new CombinedFriendsAndConferences();
                            cfac.is_friend = COMBINED_IS_FRIEND;
                            cfac.friend_item = n;
                            loadedItems.add(cfac);
                        }
                    }

                    if (main_handler_s == null)
                    {
                        listReloadSemaphore.release();
                        return;
                    }

                    main_handler_s.post(() -> {
                        try
                        {
                            if (adapter == null || !isAdded())
                            {
                                return;
                            }

                            adapter.clear_items();
                            for (int i = 0; i < loadedItems.size(); i++)
                            {
                                adapter.add_item(loadedItems.get(i));
                            }
                            adapter.notifyDataSetChanged();
                        }
                        catch (Exception e)
                        {
                            Log.i(TAG, "reloadContactsListFromDb:UI:EE:" + e.getMessage());
                        }
                        finally
                        {
                            listReloadSemaphore.release();
                        }
                    });
                }
                catch (InterruptedException e)
                {
                    listReloadSemaphore.release();
                }
                catch (Exception e)
                {
                    Log.i(TAG, "reloadContactsListFromDb:EE:" + e.getMessage());
                    listReloadSemaphore.release();
                }
            }
        };
        loaderThread.start();
    }

    void add_all_friends_clear_real(final int delay)
    {
        if (listMode == LIST_MODE_CONTACTS)
        {
            reloadContactsListFromDb(delay);
            return;
        }

        reloadChatsListFromDb(delay);
    }

    private int resolveEmptyStateKind()
    {
        if (listMode == LIST_MODE_CONTACTS)
        {
            return ListEmptyStateHelper.KIND_CONTACTS;
        }

        if (chatFilterTab == CHAT_FILTER_GROUPS)
        {
            return ListEmptyStateHelper.KIND_GROUPS;
        }

        if (chatFilterTab == CHAT_FILTER_FAVORITES)
        {
            return ListEmptyStateHelper.KIND_FAVORITES;
        }

        return ListEmptyStateHelper.KIND_CHATS;
    }

    private void refreshEmptyState()
    {
        if (usesChatFilterPager())
        {
            refreshAllChatFilterEmptyStates();
            return;
        }

        if (emptyStateHelper == null || adapter == null)
        {
            return;
        }

        if (fl_loading_progressbar != null && fl_loading_progressbar.getVisibility() == View.VISIBLE)
        {
            emptyStateHelper.setVisible(false);
            return;
        }

        if (adapter.getItemCount() > 0)
        {
            emptyStateHelper.setVisible(false);
            return;
        }

        emptyStateHelper.bind(resolveEmptyStateKind());
        emptyStateHelper.setVisible(true);
    }

    private void handleEmptyStateAction()
    {
        handleEmptyStateActionForTab(chatFilterTab);
    }

    // KHANDAQ (Figma contacts empty-state): when you have no contacts, share your own MyID.
    private void copyMyIdFromEmptyState()
    {
        try
        {
            final android.content.ClipboardManager cb = (android.content.ClipboardManager)
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null)
            {
                cb.setPrimaryClip(android.content.ClipData.newPlainText("", MainActivity.get_my_toxid()));
                android.widget.Toast.makeText(requireContext(), R.string.id_copied_to_clipboard,
                                              android.widget.Toast.LENGTH_SHORT).show();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private void showMyQrFromEmptyState()
    {
        try
        {
            final View content = getLayoutInflater().inflate(R.layout.dialog_my_qr, null);
            final android.widget.ImageView qr = content.findViewById(R.id.qr_modal_image);
            qr.setImageBitmap(ProfileContentFragment.encodeAsBitmap("tox:" + MainActivity.get_my_toxid()));
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setView(content)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
        catch (Exception ignored)
        {
        }
    }

    synchronized void add_all_friends_clear(final int delay)
    {
        if (listMode == LIST_MODE_CHATS && !shouldRunFullChatListReload())
        {
            scheduleDeferredFullChatListReload(delay);
            return;
        }

        // Log.i(TAG, "add_all_friends_clear:** CALL");
        long currentTime = System.currentTimeMillis();
        if (currentTime - add_all_friends_clear_last_trigger_ts >= INTERVAL_ADD_ALL_FRIENDS_CLEAR_MS)
        {
            // Log.i(TAG, "add_all_friends_clear:-> REAL");
            add_all_friends_clear_real(delay);
            add_all_friends_clear_last_trigger_ts = currentTime;
        }
        else
        {
            long delta_t_ms = currentTime - add_all_friends_clear_last_trigger_ts;
            // Log.i(TAG, "add_all_friends_clear:  TRIG delta ms=" + delta_t_ms);
            long trigger_in_ms_again = INTERVAL_ADD_ALL_FRIENDS_CLEAR_MS - delta_t_ms;
            if ((trigger_in_ms_again < 1) || (trigger_in_ms_again > (INTERVAL_ADD_ALL_FRIENDS_CLEAR_MS + 1)))
            {
                trigger_in_ms_again = INTERVAL_ADD_ALL_FRIENDS_CLEAR_MS;
            }
            final long trigger_in_ms_again_ = trigger_in_ms_again + 2;
            final Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(trigger_in_ms_again_);
                    // Log.i(TAG, "add_all_friends_clear:__ CALL from Trigger");
                    add_all_friends_clear(0);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
            thread.start();
        }
    }

    // name is confusing, just update all friends!! already set to offline in DB
    public void set_all_friends_to_offline()
    {
        // Log.i(TAG, "set_all_friends_to_offline");
        add_all_friends_clear(0);
    }
}

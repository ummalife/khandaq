package com.zoffcc.applications.trifa;

import android.content.Context;
import android.view.View;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.List;

import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.FriendListFragment.CHAT_FILTER_DIRECT;
import static com.zoffcc.applications.trifa.FriendListFragment.CHAT_FILTER_FAVORITES;
import static com.zoffcc.applications.trifa.FriendListFragment.CHAT_FILTER_GROUPS;

/**
 * One chat-filter tab page: RecyclerView + adapter + empty state.
 */
final class ChatFilterListPage
{
    interface EmptyStateActionHandler
    {
        void onEmptyStateAction(int filterTab);
    }

    final int filterTab;
    com.l4digital.fastscroll.FastScrollRecyclerView recyclerView;
    FriendlistAdapter adapter;
    ListEmptyStateHelper emptyStateHelper;
    View emptyStateRoot;
    private EmptyStateActionHandler emptyStateActionHandler;

    ChatFilterListPage(final int filterTab)
    {
        this.filterTab = filterTab;
    }

    void bindView(final View root, final EmptyStateActionHandler actionHandler)
    {
        emptyStateActionHandler = actionHandler;
        recyclerView = root.findViewById(R.id.rv_list);
        emptyStateRoot = root.findViewById(R.id.list_empty_state);
        emptyStateHelper = new ListEmptyStateHelper(emptyStateRoot);
        emptyStateHelper.setOnActionClickListener(() -> {
            if (emptyStateActionHandler != null)
            {
                emptyStateActionHandler.onEmptyStateAction(filterTab);
            }
        });

        recyclerView.getRecycledViewPool().clear();
        recyclerView.setLayoutManager(new LinearLayoutManager(root.getContext()));
        final DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        recyclerView.setItemAnimator(itemAnimator);
        recyclerView.setHasFixedSize(true);

        final List<CombinedFriendsAndConferences> data = new ArrayList<>();
        adapter = new FriendlistAdapter(root.getContext(), data);
        recyclerView.setAdapter(adapter);
    }

    void registerEmptyStateObserver(final RecyclerView.AdapterDataObserver observer)
    {
        if (adapter != null && observer != null)
        {
            adapter.registerAdapterDataObserver(observer);
        }
    }

    void unregisterEmptyStateObserver(final RecyclerView.AdapterDataObserver observer)
    {
        if (adapter != null && observer != null)
        {
            try
            {
                adapter.unregisterAdapterDataObserver(observer);
            }
            catch (Exception ignored)
            {
            }
        }
    }

    boolean matchesFilter(final Context context, final CombinedFriendsAndConferences item)
    {
        return ChatListFilterHelper.matchesFilter(context, item, filterTab);
    }

    void upsertItem(final Context context, final CombinedFriendsAndConferences cfac, final int entityType)
    {
        if (adapter == null || context == null)
        {
            return;
        }

        final boolean shouldShow = matchesFilter(context, cfac);
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

    void setFilteredItems(final Context context, final List<CombinedFriendsAndConferences> allItems)
    {
        if (adapter == null || context == null)
        {
            return;
        }

        adapter.clear_items();
        if (allItems != null)
        {
            final List<CombinedFriendsAndConferences> withPinned = new ArrayList<>(allItems);
            FavoritesChatHelper.prependPinnedRow(withPinned);
            final List<CombinedFriendsAndConferences> filtered = new ArrayList<>();
            for (int i = 0; i < withPinned.size(); i++)
            {
                if (matchesFilter(context, withPinned.get(i)))
                {
                    filtered.add(withPinned.get(i));
                }
            }
            adapter.replaceAllItems(filtered);
            return;
        }

        adapter.replaceAllItems(java.util.Collections.singletonList(FavoritesChatHelper.buildListItem()));
    }

    int resolveEmptyStateKind()
    {
        if (filterTab == CHAT_FILTER_GROUPS)
        {
            return ListEmptyStateHelper.KIND_GROUPS;
        }
        if (filterTab == CHAT_FILTER_FAVORITES)
        {
            return ListEmptyStateHelper.KIND_FAVORITES;
        }
        return ListEmptyStateHelper.KIND_CHATS;
    }

    void refreshEmptyState(final boolean loadingVisible)
    {
        if (emptyStateHelper == null || adapter == null)
        {
            return;
        }

        if (loadingVisible)
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

    static int filterTabForPosition(final int position)
    {
        if (position == CHAT_FILTER_GROUPS)
        {
            return CHAT_FILTER_GROUPS;
        }
        if (position == CHAT_FILTER_FAVORITES)
        {
            return CHAT_FILTER_FAVORITES;
        }
        return CHAT_FILTER_DIRECT;
    }
}

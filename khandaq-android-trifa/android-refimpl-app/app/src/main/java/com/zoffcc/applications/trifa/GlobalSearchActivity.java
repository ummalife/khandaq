package com.zoffcc.applications.trifa;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.zoffcc.applications.trifa.HelperGeneric.get_sqlite_search_string;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (#34): Telegram-style GLOBAL search. Opened from the chat-list search field, it searches
 * across EVERY conversation at once — chats/groups by name and all message text — and shows grouped
 * results ("Chats and groups" + "Messages"). Tapping a result opens that conversation. Read-only: it
 * never touches the list filter pipeline, so it cannot regress the main chat list.
 */
public class GlobalSearchActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.GlobalSearch";
    private static final int RESULT_CAP = 40;
    private static final long DEBOUNCE_MS = 220;

    private EditText input;
    private TextView emptyView;
    private ResultsAdapter adapter;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;
    private Thread searchThread;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_global_search);

        input = findViewById(R.id.global_search_input);
        emptyView = findViewById(R.id.global_search_empty);
        final RecyclerView recycler = findViewById(R.id.global_search_results);
        final ImageButton back = findViewById(R.id.global_search_back);

        adapter = new ResultsAdapter();
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        back.setOnClickListener(v -> finish());

        input.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s)
            {
                scheduleSearch(s == null ? "" : s.toString());
            }
        });

        input.requestFocus();
    }

    private void scheduleSearch(final String query)
    {
        if (pendingSearch != null)
        {
            ui.removeCallbacks(pendingSearch);
        }
        pendingSearch = () -> runSearch(query);
        ui.postDelayed(pendingSearch, DEBOUNCE_MS);
    }

    private void runSearch(final String rawQuery)
    {
        final String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty())
        {
            applyResults(new ArrayList<>(), false);
            return;
        }

        searchThread = new Thread(() ->
        {
            final List<Row> results = buildResults(query);
            ui.post(() -> applyResults(results, true));
        }, "global-search");
        searchThread.start();
    }

    /** Background DB sweep — chats/groups by name first, then 1:1 and group messages by text. */
    private List<Row> buildResults(final String query)
    {
        final String needle = query.toLowerCase(Locale.ROOT);
        final String likePattern = get_sqlite_search_string(query);

        final List<Row> chatHits = new ArrayList<>();
        final List<Row> messageHits = new ArrayList<>();
        final Map<String, String> groupNames = new HashMap<>();

        try
        {
            // 1) friends by display / raw / alias name
            final List<FriendList> friends = orma.selectFromFriendList().toList();
            if (friends != null)
            {
                for (int i = 0; i < friends.size(); i++)
                {
                    final FriendList fl = friends.get(i);
                    if (fl == null || fl.is_relay)
                    {
                        continue;
                    }
                    final String display = HelperFriend.get_friend_name_from_pubkey(fl.tox_public_key_string);
                    final String haystack = (safe(display) + " " + safe(fl.name) + " " + safe(fl.alias_name))
                            .toLowerCase(Locale.ROOT);
                    if (haystack.contains(needle))
                    {
                        chatHits.add(Row.chat(display, fl.tox_public_key_string, false));
                    }
                }
            }

            // 2) groups by name (active only) — also cache id->name for message rows
            final List<GroupDB> groups = orma.selectFromGroupDB().toList();
            if (groups != null)
            {
                for (int i = 0; i < groups.size(); i++)
                {
                    final GroupDB g = groups.get(i);
                    if (g == null || !g.group_active)
                    {
                        continue;
                    }
                    groupNames.put(g.group_identifier, safe(g.name));
                    if (safe(g.name).toLowerCase(Locale.ROOT).contains(needle))
                    {
                        chatHits.add(Row.chat(safe(g.name), g.group_identifier, true));
                    }
                }
            }

            // 3) 1:1 messages by text
            final List<Message> msgs = orma.selectFromMessage().textLike(likePattern).toList();
            if (msgs != null)
            {
                Collections.sort(msgs, new Comparator<Message>()
                {
                    @Override
                    public int compare(Message a, Message b)
                    {
                        return Long.compare(b.sent_timestamp, a.sent_timestamp);
                    }
                });
                for (int i = 0; i < msgs.size() && messageHits.size() < RESULT_CAP; i++)
                {
                    final Message m = msgs.get(i);
                    if (m == null || m.text == null || m.text.trim().isEmpty())
                    {
                        continue;
                    }
                    final String name = HelperFriend.get_friend_name_from_pubkey(m.tox_friendpubkey);
                    messageHits.add(Row.message(name, oneLine(m.text), m.tox_friendpubkey, false));
                }
            }

            // 4) group messages by text
            final List<GroupMessage> gmsgs = orma.selectFromGroupMessage().textLike(likePattern).toList();
            if (gmsgs != null)
            {
                Collections.sort(gmsgs, new Comparator<GroupMessage>()
                {
                    @Override
                    public int compare(GroupMessage a, GroupMessage b)
                    {
                        return Long.compare(b.sent_timestamp, a.sent_timestamp);
                    }
                });
                int added = 0;
                for (int i = 0; i < gmsgs.size() && added < RESULT_CAP; i++)
                {
                    final GroupMessage gm = gmsgs.get(i);
                    if (gm == null || gm.text == null || gm.text.trim().isEmpty())
                    {
                        continue;
                    }
                    String name = groupNames.get(gm.group_identifier);
                    if (name == null || name.isEmpty())
                    {
                        name = safe(gm.tox_group_peername);
                    }
                    messageHits.add(Row.message(name, oneLine(gm.text), gm.group_identifier, true));
                    added++;
                }
            }
        }
        catch (Exception e)
        {
            // Read-only search: never crash the screen on a malformed query / DB hiccup.
        }

        final List<Row> out = new ArrayList<>();
        if (!chatHits.isEmpty())
        {
            out.add(Row.header(getString(R.string.global_search_section_chats)));
            out.addAll(chatHits);
        }
        if (!messageHits.isEmpty())
        {
            out.add(Row.header(getString(R.string.global_search_section_messages)));
            out.addAll(messageHits);
        }
        return out;
    }

    private void applyResults(final List<Row> rows, final boolean queryActive)
    {
        adapter.setRows(rows);
        final boolean showEmpty = queryActive && rows.isEmpty();
        emptyView.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
    }

    private void openRow(final Row row)
    {
        if (row.isGroup)
        {
            if (row.targetId == null || row.targetId.isEmpty())
            {
                return;
            }
            final Intent intent = new Intent(this, GroupMessageListActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("group_id", row.targetId);
            startActivity(intent);
        }
        else
        {
            if (row.targetId == null || row.targetId.isEmpty())
            {
                return;
            }
            FriendListHolder.show_messagelist_acticvity_for_friend(this, row.targetId);
        }
    }

    private static String safe(final String s)
    {
        return s == null ? "" : s;
    }

    private static String oneLine(final String s)
    {
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    // ---- model ----

    private static final class Row
    {
        static final int TYPE_HEADER = 0;
        static final int TYPE_RESULT = 1;

        int type;
        String title;
        String subtitle;   // message snippet (null for chat rows / headers)
        boolean isGroup;
        String targetId;   // friend pubkey or group_identifier

        static Row header(String title)
        {
            Row r = new Row();
            r.type = TYPE_HEADER;
            r.title = title;
            return r;
        }

        static Row chat(String title, String targetId, boolean isGroup)
        {
            Row r = new Row();
            r.type = TYPE_RESULT;
            r.title = title;
            r.targetId = targetId;
            r.isGroup = isGroup;
            return r;
        }

        static Row message(String title, String snippet, String targetId, boolean isGroup)
        {
            Row r = new Row();
            r.type = TYPE_RESULT;
            r.title = title;
            r.subtitle = snippet;
            r.targetId = targetId;
            r.isGroup = isGroup;
            return r;
        }
    }

    // ---- adapter ----

    private final class ResultsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
    {
        private final List<Row> rows = new ArrayList<>();

        void setRows(final List<Row> newRows)
        {
            rows.clear();
            if (newRows != null)
            {
                rows.addAll(newRows);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position)
        {
            return rows.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
        {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == Row.TYPE_HEADER)
            {
                return new HeaderHolder(inflater.inflate(R.layout.item_global_search_header, parent, false));
            }
            return new ResultHolder(inflater.inflate(R.layout.item_global_search_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position)
        {
            final Row row = rows.get(position);
            if (holder instanceof HeaderHolder)
            {
                ((HeaderHolder) holder).title.setText(row.title);
                return;
            }
            final ResultHolder rh = (ResultHolder) holder;
            rh.title.setText(row.title);
            if (row.subtitle == null || row.subtitle.isEmpty())
            {
                rh.subtitle.setVisibility(View.GONE);
            }
            else
            {
                rh.subtitle.setVisibility(View.VISIBLE);
                rh.subtitle.setText(row.subtitle);
            }
            rh.itemView.setOnClickListener(v -> openRow(row));
        }

        @Override
        public int getItemCount()
        {
            return rows.size();
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder
    {
        final TextView title;

        HeaderHolder(@NonNull View itemView)
        {
            super(itemView);
            title = itemView.findViewById(R.id.header_title);
        }
    }

    private static final class ResultHolder extends RecyclerView.ViewHolder
    {
        final TextView title;
        final TextView subtitle;

        ResultHolder(@NonNull View itemView)
        {
            super(itemView);
            title = itemView.findViewById(R.id.row_title);
            subtitle = itemView.findViewById(R.id.row_subtitle);
        }
    }
}

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import com.vanniktech.emoji.EmojiEditText;

import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.HelperGroup.collect_group_members_for_display;
import static com.zoffcc.applications.trifa.HelperGroup.dedupe_group_members_for_display;

/** @mention autocomplete popup for group chat input. */
final class GroupMentionInputController
{
    interface GroupIdProvider
    {
        String getGroupId();
    }

    private Activity activity;
    private EmojiEditText inputField;
    private View anchorView;
    private GroupIdProvider groupIdProvider;
    private PopupWindow popupWindow;
    private RecyclerView mentionList;
    private MentionAdapter adapter;
    private int mentionStart = -1;
    private boolean suppressWatcher;

    void bind(final Activity activity,
              final EmojiEditText inputField,
              final View anchorView,
              final GroupIdProvider groupIdProvider)
    {
        this.activity = activity;
        this.inputField = inputField;
        this.anchorView = anchorView;
        this.groupIdProvider = groupIdProvider;

        final View content = LayoutInflater.from(activity).inflate(R.layout.group_mention_picker, null, false);
        mentionList = content.findViewById(R.id.group_mention_list);
        mentionList.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new MentionAdapter();
        mentionList.setAdapter(adapter);

        popupWindow = new PopupWindow(content,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false);
        popupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        popupWindow.setOutsideTouchable(true);
        // KHANDAQ (26.11.2026): Air's plate carries its own line instead of a shadow (air_bg_mention_list).
        popupWindow.setElevation(0f);

        inputField.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after)
            {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count)
            {
            }

            @Override
            public void afterTextChanged(final Editable s)
            {
                if (suppressWatcher)
                {
                    return;
                }
                updateMentionQuery(s);
            }
        });
    }

    void dismiss()
    {
        if (popupWindow != null && popupWindow.isShowing())
        {
            popupWindow.dismiss();
        }
        mentionStart = -1;
    }

    private void updateMentionQuery(final Editable text)
    {
        if (inputField == null || text == null)
        {
            dismiss();
            return;
        }

        final int cursor = Math.max(0, inputField.getSelectionStart());
        if (cursor <= 0)
        {
            dismiss();
            return;
        }

        int atIndex = -1;
        for (int i = cursor - 1; i >= 0; i--)
        {
            final char c = text.charAt(i);
            if (c == '@')
            {
                if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1)))
                {
                    atIndex = i;
                }
                break;
            }
            if (Character.isWhitespace(c))
            {
                break;
            }
        }

        if (atIndex < 0)
        {
            dismiss();
            return;
        }

        mentionStart = atIndex;
        final String query = text.subSequence(atIndex + 1, cursor).toString();
        if (query.contains("\n") || query.contains(" "))
        {
            dismiss();
            return;
        }

        final String groupId = groupIdProvider == null ? null : groupIdProvider.getGroupId();
        if (groupId == null || groupId.equals("-1"))
        {
            dismiss();
            return;
        }

        // The handles are made from every member of the group, so a twin's handle does not depend on who the
        // query leaves in the list; the list, the row and the inserted text all take theirs from this map.
        final List<HelperGroup.GroupMemberDisplay> all =
                dedupe_group_members_for_display(collect_group_members_for_display(groupId));
        final Map<String, String> handles = GroupMentionHelper.mentionHandles(all);
        final List<HelperGroup.GroupMemberDisplay> filtered = matchingMembers(all, handles, query);
        if (filtered.isEmpty())
        {
            dismiss();
            return;
        }

        adapter.setMembers(filtered, handles);
        showPopup();
    }

    static List<HelperGroup.GroupMemberDisplay> matchingMembers(final List<HelperGroup.GroupMemberDisplay> all,
                                                                final String query)
    {
        return matchingMembers(all, GroupMentionHelper.mentionHandles(all), query);
    }

    /**
     * KHANDAQ (26.11.2026): the members a query after "@" offers, as Telegram does: yourself left out, a
     * handle that starts with the query or a name that holds it, in any script, without regard to case or
     * accents; online first, then by name.
     */
    static List<HelperGroup.GroupMemberDisplay> matchingMembers(final List<HelperGroup.GroupMemberDisplay> all,
                                                                final Map<String, String> handles,
                                                                final String query)
    {
        final List<HelperGroup.GroupMemberDisplay> result = new ArrayList<>();
        final String q = fold(query);
        for (int i = 0; i < all.size(); i++)
        {
            final HelperGroup.GroupMemberDisplay member = all.get(i);
            if (member == null || member.self)
            {
                continue;
            }

            final String handle = GroupMentionHelper.handleOf(handles, member);
            if (q.isEmpty() || fold(handle).startsWith(q) || fold(member.name).contains(q))
            {
                result.add(member);
            }
        }

        final Collator byName = Collator.getInstance();
        byName.setStrength(Collator.PRIMARY);
        Collections.sort(result, (a, b) -> {
            if (a.online != b.online)
            {
                return a.online ? -1 : 1;
            }
            return byName.compare(a.name == null ? "" : a.name, b.name == null ? "" : b.name);
        });
        return result;
    }

    /** Lower case without accents, for matching what is typed after "@" in any script. */
    static String fold(final String text)
    {
        if (text == null || text.isEmpty())
        {
            return "";
        }
        final String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        final StringBuilder sb = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++)
        {
            final char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK)
            {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private void showPopup()
    {
        if (popupWindow == null || anchorView == null || activity == null)
        {
            return;
        }

        // KHANDAQ (26.11.2026): as high as its rows, up to four and a half, so a fifth shows there is more to
        // scroll; one member used to stand in a plate as high as five.
        final int row = activity.getResources().getDimensionPixelSize(R.dimen.group_mention_row_height);
        final int padding = mentionList.getPaddingTop() + mentionList.getPaddingBottom();
        final int maxHeight = row * 9 / 2 + padding;
        mentionList.getLayoutParams().height = adapter.getItemCount() > 4 ? maxHeight
                                                                          : ViewGroup.LayoutParams.WRAP_CONTENT;
        mentionList.requestLayout();

        anchorView.post(() -> {
            if (popupWindow.isShowing())
            {
                popupWindow.dismiss();
            }
            final View content = popupWindow.getContentView();
            content.measure(
                    View.MeasureSpec.makeMeasureSpec(anchorView.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
            final int popupHeight = content.getMeasuredHeight();
            popupWindow.showAsDropDown(anchorView, 0, -(anchorView.getHeight() + popupHeight));
        });
    }

    private void insertMention(final HelperGroup.GroupMemberDisplay member)
    {
        if (inputField == null || mentionStart < 0 || member == null)
        {
            dismiss();
            return;
        }

        final Editable editable = inputField.getText();
        if (editable == null)
        {
            dismiss();
            return;
        }

        final int cursor = Math.max(mentionStart, inputField.getSelectionStart());
        final String handle = adapter.handleOf(member);
        final String token = GroupMentionHelper.insertMentionToken(handle);

        suppressWatcher = true;
        try
        {
            editable.replace(mentionStart, cursor, token);
            inputField.setSelection(mentionStart + token.length());
        }
        finally
        {
            suppressWatcher = false;
        }

        dismiss();
    }

    private final class MentionAdapter extends RecyclerView.Adapter<MentionAdapter.Holder>
    {
        private final List<HelperGroup.GroupMemberDisplay> members = new ArrayList<>();
        private Map<String, String> handles = new HashMap<>();

        void setMembers(final List<HelperGroup.GroupMemberDisplay> items, final Map<String, String> byKey)
        {
            members.clear();
            if (items != null)
            {
                members.addAll(items);
            }
            handles = byKey == null ? new HashMap<>() : byKey;
            notifyDataSetChanged();
        }

        String handleOf(final HelperGroup.GroupMemberDisplay member)
        {
            return GroupMentionHelper.handleOf(handles, member);
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType)
        {
            final View row = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.group_mention_picker_row, parent, false);
            return new Holder(row);
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, final int position)
        {
            final HelperGroup.GroupMemberDisplay member = members.get(position);
            final String handle = handleOf(member);

            String displayName = member.name == null ? handle : member.name;
            final int paren = displayName.indexOf(" (");
            if (paren > 0)
            {
                displayName = displayName.substring(0, paren);
            }

            holder.nameView.setText(displayName);
            holder.handleView.setText("@" + handle);
            // The member's tile, in the tone the group's own tiles give them (keyed on the member key).
            holder.tile.bind(AirTileView.Kind.PERSON, displayName,
                             AirModel.Tone.index(member.pubkey == null || member.pubkey.isEmpty() ? displayName
                                                                                                   : member.pubkey));
            holder.itemView.setContentDescription(displayName + ", @" + handle);
            holder.itemView.setOnClickListener(v -> insertMention(member));
        }

        @Override
        public int getItemCount()
        {
            return members.size();
        }

        final class Holder extends RecyclerView.ViewHolder
        {
            final AirTileView tile;
            final android.widget.TextView nameView;
            final android.widget.TextView handleView;

            Holder(final View itemView)
            {
                super(itemView);
                tile = itemView.findViewById(R.id.group_mention_tile);
                nameView = itemView.findViewById(R.id.group_mention_name);
                handleView = itemView.findViewById(R.id.group_mention_handle);
            }
        }
    }
}

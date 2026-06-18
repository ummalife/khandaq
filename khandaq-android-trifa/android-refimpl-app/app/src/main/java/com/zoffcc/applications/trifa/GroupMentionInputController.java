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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        popupWindow.setElevation(12f);

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

        final List<HelperGroup.GroupMemberDisplay> filtered = filterMembers(groupId, query);
        if (filtered.isEmpty())
        {
            dismiss();
            return;
        }

        adapter.setMembers(filtered);
        showPopup();
    }

    private List<HelperGroup.GroupMemberDisplay> filterMembers(final String groupId, final String query)
    {
        final List<HelperGroup.GroupMemberDisplay> all =
                dedupe_group_members_for_display(collect_group_members_for_display(groupId));
        final List<HelperGroup.GroupMemberDisplay> result = new ArrayList<>();
        final String q = query == null ? "" : query.toLowerCase(Locale.ENGLISH);
        final Set<String> usedHandles = new HashSet<>();

        for (int i = 0; i < all.size(); i++)
        {
            final HelperGroup.GroupMemberDisplay member = all.get(i);
            if (member == null || member.self)
            {
                continue;
            }

            final String handle = GroupMentionHelper.mentionHandleForMember(member, usedHandles, true);
            final String name = member.name == null ? "" : member.name.toLowerCase(Locale.ENGLISH);
            if (q.isEmpty()
                    || handle.toLowerCase(Locale.ENGLISH).startsWith(q)
                    || name.contains(q))
            {
                result.add(member);
            }
        }

        return result;
    }

    private void showPopup()
    {
        if (popupWindow == null || anchorView == null || activity == null)
        {
            return;
        }

        final int maxHeight = (int) (240 * activity.getResources().getDisplayMetrics().density);
        mentionList.getLayoutParams().height = maxHeight;

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
        final Set<String> used = new HashSet<>();
        final String handle = GroupMentionHelper.mentionHandleForMember(member, used, false);
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

        void setMembers(final List<HelperGroup.GroupMemberDisplay> items)
        {
            members.clear();
            if (items != null)
            {
                members.addAll(items);
            }
            notifyDataSetChanged();
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
            final Set<String> used = new HashSet<>();
            final String handle = GroupMentionHelper.mentionHandleForMember(member, used, false);

            String displayName = member.name == null ? handle : member.name;
            final int paren = displayName.indexOf(" (");
            if (paren > 0)
            {
                displayName = displayName.substring(0, paren);
            }

            holder.nameView.setText(displayName);
            holder.handleView.setText("@" + handle);
            ChatBubbleUiHelper.fill_group_peer_avatar(holder.itemView.getContext(), member.pubkey, displayName,
                    holder.avatarView);
            holder.itemView.setOnClickListener(v -> insertMention(member));
        }

        @Override
        public int getItemCount()
        {
            return members.size();
        }

        final class Holder extends RecyclerView.ViewHolder
        {
            final de.hdodenhof.circleimageview.CircleImageView avatarView;
            final android.widget.TextView nameView;
            final android.widget.TextView handleView;

            Holder(final View itemView)
            {
                super(itemView);
                avatarView = itemView.findViewById(R.id.group_mention_avatar);
                nameView = itemView.findViewById(R.id.group_mention_name);
                handleView = itemView.findViewById(R.id.group_mention_handle);
            }
        }
    }
}

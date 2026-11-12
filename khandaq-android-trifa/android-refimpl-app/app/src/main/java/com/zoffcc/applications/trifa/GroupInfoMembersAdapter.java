package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.GroupMessageListActivity.open_group_peer_info_activity;

/**
 * The members of the group dossier. KHANDAQ (redesign, E14): in AirGroupModel's order (by role, online
 * first, then the most recently seen), each with the role and the presence in words, in the design's
 * colours.
 */
class GroupInfoMembersAdapter extends RecyclerView.Adapter<GroupInfoMembersAdapter.MemberHolder>
{
    private final Context context;
    private final String group_id;
    private final List<AirGroupModel.Member> members = new ArrayList<>();

    GroupInfoMembersAdapter(final Context context, final String group_id)
    {
        this.context = context;
        this.group_id = group_id;
    }

    void setMembers(final List<AirGroupModel.Member> items)
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
    public MemberHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        final View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.group_info_member_row, parent,
                                                                           false);
        return new MemberHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MemberHolder holder, int position)
    {
        final AirGroupModel.Member member = members.get(position);
        final String status = AirGroupPanel.statusLine(context, member, System.currentTimeMillis());
        holder.nameView.setText(member.name);
        holder.statusView.setText(status);
        if (holder.onlineDot != null)
        {
            ViewCompat.setBackgroundTintList(holder.onlineDot, ColorStateList.valueOf(
                    AirUi.color(context, member.online ? R.color.kq_live : R.color.kq_line_strong)));
        }
        holder.itemView.setContentDescription(member.name + ", " + status);

        holder.itemView.setOnClickListener(v -> open_group_peer_info_activity(v.getContext(), member.publicKey,
                                                                              group_id));
        holder.itemView.setOnLongClickListener(v ->
        {
            GroupMemberActions.showActionMenu(v.getContext(), group_id, member.publicKey, member.name);
            return true;
        });
    }

    @Override
    public int getItemCount()
    {
        return members.size();
    }

    static final class MemberHolder extends RecyclerView.ViewHolder
    {
        final View onlineDot;
        final TextView nameView;
        final TextView statusView;

        MemberHolder(View itemView)
        {
            super(itemView);
            onlineDot = itemView.findViewById(R.id.group_member_online_dot);
            nameView = itemView.findViewById(R.id.group_member_name);
            statusView = itemView.findViewById(R.id.group_member_status);
        }
    }
}

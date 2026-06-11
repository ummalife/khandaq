package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.HelperGroup.GroupMemberDisplay;
import static com.zoffcc.applications.trifa.HelperGroup.format_group_member_status_line;
import static com.zoffcc.applications.trifa.GroupMessageListActivity.open_group_peer_info_activity;

class GroupInfoMembersAdapter extends RecyclerView.Adapter<GroupInfoMembersAdapter.MemberHolder>
{
    private final Context context;
    private final String group_id;
    private final List<GroupMemberDisplay> members = new ArrayList<>();

    GroupInfoMembersAdapter(final Context context, final String group_id)
    {
        this.context = context;
        this.group_id = group_id;
    }

    void setMembers(final List<GroupMemberDisplay> items)
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
        final GroupMemberDisplay member = members.get(position);
        holder.nameView.setText(member.name);
        holder.statusView.setText(format_group_member_status_line(context, member.online, member.last_seen_ms));
        if (member.online)
        {
            holder.statusView.setTextColor(Color.parseColor("#64B5F6"));
        }
        else
        {
            holder.statusView.setTextColor(context.getResources().getColor(R.color.md_grey_600));
        }

        if (member.self)
        {
            holder.nameView.setTextColor(Color.parseColor("#FF5733"));
        }
        else
        {
            holder.nameView.setTextColor(context.getResources().getColor(R.color.md_black_1000));
        }

        holder.itemView.setOnClickListener(v -> open_group_peer_info_activity(v.getContext(), member.pubkey, group_id));
        holder.itemView.setOnLongClickListener(v ->
        {
            GroupMemberActions.showActionMenu(v.getContext(), group_id, member.pubkey, member.name);
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
        final TextView nameView;
        final TextView statusView;

        MemberHolder(View itemView)
        {
            super(itemView);
            nameView = itemView.findViewById(R.id.group_member_name);
            statusView = itemView.findViewById(R.id.group_member_status);
        }
    }
}

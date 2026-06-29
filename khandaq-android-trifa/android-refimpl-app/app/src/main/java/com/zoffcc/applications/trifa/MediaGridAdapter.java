package com.zoffcc.applications.trifa;

import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * KHANDAQ (Figma attachments 2031:13703): grid of recent device media in the attach bottom sheet.
 * Tap a thumbnail body = quick single send (onPick). Tap the corner circle (or any thumbnail once a
 * selection exists) = toggle multi-select; the sheet shows a "Отправить (N)" button to send them all.
 */
class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.VH>
{
    interface OnPick
    {
        void onPick(Uri uri);
    }

    interface OnSelectionChanged
    {
        void onSelectionChanged(List<Uri> selected);
    }

    private final List<Uri> items;
    private final OnPick pickCallback;
    private final OnSelectionChanged selectionCallback;
    private final LinkedHashSet<Uri> selected = new LinkedHashSet<>();

    MediaGridAdapter(final List<Uri> items, final OnPick pickCallback, final OnSelectionChanged selectionCallback)
    {
        this.items = items;
        this.pickCallback = pickCallback;
        this.selectionCallback = selectionCallback;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
    {
        final View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_media_grid, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position)
    {
        final Uri uri = items.get(position);
        try
        {
            GlideApp.with(h.thumb.getContext())
                    .load(uri)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .into(h.thumb);
        }
        catch (Exception ignored)
        {
        }

        final boolean isVideo = (uri != null) && uri.toString().contains("/video");
        if (isVideo)
        {
            h.videoBadge.setImageDrawable(new IconicsDrawable(h.videoBadge.getContext())
                    .icon(GoogleMaterial.Icon.gmd_videocam).color(Color.WHITE).sizeDp(16));
            h.videoBadge.setVisibility(View.VISIBLE);
        }
        else
        {
            h.videoBadge.setVisibility(View.GONE);
        }

        final boolean isSelected = selected.contains(uri);
        final int teal = ContextCompat.getColor(h.select.getContext(), R.color.khandaq_teal);
        h.select.setImageDrawable(isSelected
                ? new IconicsDrawable(h.select.getContext()).icon(GoogleMaterial.Icon.gmd_check_circle)
                        .color(teal).sizeDp(24)
                : new IconicsDrawable(h.select.getContext()).icon(GoogleMaterial.Icon.gmd_radio_button_unchecked)
                        .color(Color.WHITE).sizeDp(24));
        h.thumb.setAlpha(isSelected ? 0.6f : 1f);

        h.select.setOnClickListener(v -> toggle(uri));
        h.itemView.setOnClickListener(v ->
        {
            if (!selected.isEmpty())
            {
                toggle(uri);
            }
            else if (pickCallback != null && uri != null)
            {
                pickCallback.onPick(uri);
            }
        });
    }

    private void toggle(final Uri uri)
    {
        if (uri == null)
        {
            return;
        }
        if (selected.contains(uri))
        {
            selected.remove(uri);
        }
        else
        {
            selected.add(uri);
        }
        notifyDataSetChanged();
        if (selectionCallback != null)
        {
            selectionCallback.onSelectionChanged(new ArrayList<>(selected));
        }
    }

    @Override
    public int getItemCount()
    {
        return items != null ? items.size() : 0;
    }

    static class VH extends RecyclerView.ViewHolder
    {
        final ImageView thumb;
        final ImageView videoBadge;
        final ImageView select;

        VH(@NonNull View itemView)
        {
            super(itemView);
            thumb = itemView.findViewById(R.id.media_grid_thumb);
            videoBadge = itemView.findViewById(R.id.media_grid_video_badge);
            select = itemView.findViewById(R.id.media_grid_select);
        }
    }
}

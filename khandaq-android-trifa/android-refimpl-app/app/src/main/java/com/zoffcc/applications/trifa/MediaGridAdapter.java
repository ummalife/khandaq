package com.zoffcc.applications.trifa;

import android.graphics.Color;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;

import org.khandaq.messenger.R;

import java.util.List;

/**
 * KHANDAQ (Figma attachments 2031:13703): grid of recent device media (images + videos) shown in the
 * in-app attach bottom sheet. Tapping a cell routes the content Uri into the existing media send preview.
 */
class MediaGridAdapter extends RecyclerView.Adapter<MediaGridAdapter.VH>
{
    interface OnPick
    {
        void onPick(Uri uri);
    }

    private final List<Uri> items;
    private final OnPick callback;

    MediaGridAdapter(final List<Uri> items, final OnPick callback)
    {
        this.items = items;
        this.callback = callback;
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
        h.itemView.setOnClickListener(v ->
        {
            if (callback != null && uri != null)
            {
                callback.onPick(uri);
            }
        });
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

        VH(@NonNull View itemView)
        {
            super(itemView);
            thumb = itemView.findViewById(R.id.media_grid_thumb);
            videoBadge = itemView.findViewById(R.id.media_grid_video_badge);
        }
    }
}

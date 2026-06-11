package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.chrisbanes.photoview.PhotoView;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

public class MediaSendPreviewActivity extends AppCompatActivity
{
    private ArrayList<Uri> uris;
    private EditText captionField;
    private TextView counterView;
    private ViewPager2 pager;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_send_preview);

        uris = getIntent().getParcelableArrayListExtra(MediaSendPreviewHelper.EXTRA_URI_LIST);
        if (uris == null || uris.isEmpty())
        {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        captionField = findViewById(R.id.media_preview_caption);
        counterView = findViewById(R.id.media_preview_counter);
        pager = findViewById(R.id.media_preview_pager);

        pager.setAdapter(new PreviewAdapter(this, uris));
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback()
        {
            @Override
            public void onPageSelected(int position)
            {
                updateCounter(position);
            }
        });

        if (uris.size() > 1)
        {
            counterView.setVisibility(View.VISIBLE);
            updateCounter(0);
        }

        final ImageButton closeButton = findViewById(R.id.media_preview_close);
        final Button cancelButton = findViewById(R.id.media_preview_cancel);
        final Button sendButton = findViewById(R.id.media_preview_send);

        final View.OnClickListener cancelListener = v ->
        {
            setResult(Activity.RESULT_CANCELED);
            finish();
        };

        closeButton.setOnClickListener(cancelListener);
        cancelButton.setOnClickListener(cancelListener);

        sendButton.setOnClickListener(v ->
        {
            final Intent result = new Intent();
            result.putParcelableArrayListExtra(MediaSendPreviewHelper.EXTRA_URI_LIST, uris);
            final String caption = captionField.getText() == null ? "" : captionField.getText().toString().trim();
            if (!caption.isEmpty())
            {
                result.putExtra(MediaSendPreviewHelper.EXTRA_CAPTION, caption);
            }
            setResult(Activity.RESULT_OK, result);
            finish();
        });
    }

    @Override
    public void onBackPressed()
    {
        setResult(Activity.RESULT_CANCELED);
        super.onBackPressed();
    }

    private void updateCounter(final int position)
    {
        counterView.setText(getString(R.string.media_send_counter_format, position + 1, uris.size()));
    }

    private static final class PreviewAdapter extends RecyclerView.Adapter<PreviewAdapter.Holder>
    {
        private final Context context;
        private final List<Uri> items;

        PreviewAdapter(final Context context, final List<Uri> items)
        {
            this.context = context;
            this.items = items;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
        {
            final View view = LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.item_media_send_preview_page, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position)
        {
            final Uri uri = items.get(position);
            final boolean isVideo = MediaSendPreviewHelper.isVideoUri(context, uri);

            holder.photoView.setVisibility(View.GONE);
            holder.videoFrame.setVisibility(View.GONE);
            holder.videoIcon.setVisibility(View.GONE);

            if (isVideo)
            {
                holder.videoFrame.setVisibility(View.VISIBLE);
                holder.videoIcon.setVisibility(View.VISIBLE);
                holder.videoFrame.setImageResource(R.drawable.round_loading_animation);
                bindVideoFrame(context, uri, holder.videoFrame);
            }
            else
            {
                holder.photoView.setVisibility(View.VISIBLE);
                GlideApp.with(context).
                        load(uri).
                        diskCacheStrategy(DiskCacheStrategy.NONE).
                        skipMemoryCache(true).
                        into(holder.photoView);
            }
        }

        @Override
        public int getItemCount()
        {
            return items.size();
        }

        static void bindVideoFrame(final Context context, final Uri uri, final ImageView target)
        {
            new Thread()
            {
                @Override
                public void run()
                {
                    Bitmap frame = null;
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try
                    {
                        retriever.setDataSource(context, uri);
                        frame = retriever.getFrameAtTime(0);
                    }
                    catch (Exception ignored)
                    {
                    }
                    finally
                    {
                        try
                        {
                            retriever.release();
                        }
                        catch (Exception ignored)
                        {
                        }
                    }

                    final Bitmap frameFinal = frame;
                    if (!(context instanceof Activity))
                    {
                        return;
                    }

                    ((Activity) context).runOnUiThread(() ->
                    {
                        if (frameFinal != null)
                        {
                            target.setImageBitmap(frameFinal);
                        }
                        else
                        {
                            target.setBackgroundColor(Color.DKGRAY);
                        }
                    });
                }
            }.start();
        }

        static final class Holder extends RecyclerView.ViewHolder
        {
            final PhotoView photoView;
            final ImageView videoFrame;
            final ImageView videoIcon;

            Holder(final View itemView)
            {
                super(itemView);
                photoView = itemView.findViewById(R.id.media_preview_photo);
                videoFrame = itemView.findViewById(R.id.media_preview_video_frame);
                videoIcon = itemView.findViewById(R.id.media_preview_video_icon);
            }
        }
    }
}

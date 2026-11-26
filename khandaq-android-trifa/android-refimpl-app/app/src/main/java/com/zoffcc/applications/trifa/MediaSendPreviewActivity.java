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
import android.util.Log;
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
import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.iconics.IconicsDrawable;
import com.vanniktech.emoji.EmojiEditText;
import com.vanniktech.emoji.EmojiPopup;

import java.util.ArrayList;
import java.util.List;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.IntentCompat;
import androidx.core.os.BundleCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

public class MediaSendPreviewActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.MediaSendPrev";
    /** KHANDAQ (Khandaq Testers 8265): the chat's media picker, opened by the tile that adds more. */
    private static final int REQUEST_ADD_MORE = 8011;
    /** KHANDAQ (Khandaq Testers 8264): Draw and write (PhotoMarkupActivity), the second edit of a photo. */
    private static final int REQUEST_MARKUP = 8012;
    private static final int EDIT_CROP = 1;
    private static final int EDIT_MARKUP = 2;
    private static final String STATE_URIS = "media_send_preview_uris";

    private ArrayList<Uri> uris;
    private EmojiEditText captionField;
    private EmojiPopup emojiPopup;
    private TextView counterView;
    private ViewPager2 pager;
    private RecyclerView thumbs;
    private ThumbnailAdapter thumbAdapter;
    // KHANDAQ (#7): crop/rotate editor before send.
    private PreviewAdapter previewAdapter;
    private ImageButton editButton;
    private int editingPosition = -1;
    /**
     * KHANDAQ (0.2.56): Back closes the open emoji panel first; on only while it is open, as Android 16 no
     * longer calls onBackPressed for an app that targets it. Back that leaves sends nothing: the result
     * stays RESULT_CANCELED, which only the send button changes.
     */
    private final OnBackPressedCallback emojiBackCallback = new OnBackPressedCallback(false)
    {
        @Override
        public void handleOnBackPressed()
        {
            if (emojiPopup != null && emojiPopup.isShowing())
            {
                emojiPopup.dismiss();
                return;
            }
            setEnabled(false);
            getOnBackPressedDispatcher().onBackPressed();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, emojiBackCallback);
        setContentView(R.layout.activity_media_send_preview);
        ViewUtil.applyImeBottomInsets(this); // KHANDAQ (#1): keep the caption field above the keyboard on edge-to-edge

        // KHANDAQ (Khandaq Testers 8265): what was added or cropped here outlives the activity being made anew,
        // as it may be behind the picker.
        final ArrayList<Uri> restored = (savedInstanceState == null) ? null
                : BundleCompat.getParcelableArrayList(savedInstanceState, STATE_URIS, Uri.class);
        uris = ((restored != null) && !restored.isEmpty()) ? restored
                : getIntent().getParcelableArrayListExtra(MediaSendPreviewHelper.EXTRA_URI_LIST);
        if (uris == null || uris.isEmpty())
        {
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        MediaSendPreviewHelper.persistUriPermissions(this, uris);

        captionField = findViewById(R.id.media_preview_caption);
        counterView = findViewById(R.id.media_preview_counter);
        pager = findViewById(R.id.media_preview_pager);
        thumbs = findViewById(R.id.media_preview_thumbs);

        previewAdapter = new PreviewAdapter(this, uris);
        pager.setAdapter(previewAdapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback()
        {
            @Override
            public void onPageSelected(int position)
            {
                updateCounter(position);
                updateEditButtonVisibility(position);
                if (thumbAdapter != null)
                {
                    thumbAdapter.setSelected(position);
                }
            }
        });

        if (uris.size() > 1)
        {
            counterView.setVisibility(View.VISIBLE);
            updateCounter(0);
        }

        // KHANDAQ (Khandaq Testers 8265): the strip shows for one item too, as it ends in the tile that adds more.
        thumbs.setVisibility(View.VISIBLE);
        thumbs.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        thumbAdapter = new ThumbnailAdapter(this, uris, position -> pager.setCurrentItem(position, true),
                                            this::openAddMore);
        thumbAdapter.setAddShown(addMoreRoom() > 0);
        thumbs.setAdapter(thumbAdapter);

        final ImageButton backButton = findViewById(R.id.media_preview_close);
        final ImageButton sendButton = findViewById(R.id.media_preview_send);
        final ImageView emojiButton = findViewById(R.id.media_preview_emoji);

        // KHANDAQ (#7): crop/rotate the current photo before sending.
        editButton = findViewById(R.id.media_preview_edit);
        editButton.setOnClickListener(v -> showEditMenu(v, pager.getCurrentItem()));
        updateEditButtonVisibility(0);

        final View.OnClickListener cancelListener = v ->
        {
            setResult(Activity.RESULT_CANCELED);
            finish();
        };

        backButton.setOnClickListener(cancelListener);

        final View.OnClickListener sendListener = v ->
        {
            final Intent result = new Intent();
            result.putParcelableArrayListExtra(MediaSendPreviewHelper.EXTRA_URI_LIST, uris);
            // KHANDAQ (Khandaq Testers 8265): the chat reads what was added here once this screen is gone.
            MediaSendPreviewHelper.grantContentUriReadPermissions(result, this, uris);
            final String caption = captionField.getText() == null ? "" : captionField.getText().toString().trim();
            if (!caption.isEmpty())
            {
                result.putExtra(MediaSendPreviewHelper.EXTRA_CAPTION, caption);
            }
            setResult(Activity.RESULT_OK, result);
            finish();
        };
        sendButton.setOnClickListener(sendListener);

        emojiPopup = EmojiPopup.Builder.fromRootView(findViewById(R.id.media_preview_root)).
                setOnEmojiPopupShownListener(() -> {
                    emojiButton.setImageDrawable(new IconicsDrawable(this).icon(FontAwesome.Icon.faw_keyboard).
                            color(Color.WHITE).sizeDp(24));
                    emojiBackCallback.setEnabled(true);
                }).
                setOnEmojiPopupDismissListener(() -> {
                    emojiButton.setImageResource(R.drawable.ic_emoji_smile_24);
                    emojiBackCallback.setEnabled(false);
                }).
                setBackgroundColor(getResources().getColor(R.color.md_grey_800)).
                build(captionField);

        emojiButton.setOnClickListener(v ->
        {
            captionField.requestFocus();
            emojiPopup.toggle();
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState)
    {
        super.onSaveInstanceState(outState);
        if (uris != null)
        {
            outState.putParcelableArrayList(STATE_URIS, uris);
        }
    }

    /** KHANDAQ (Khandaq Testers 8265): how many more items this send takes. */
    private int addMoreRoom()
    {
        return MediaSendPreviewHelper.addMoreRoom((uris == null) ? 0 : uris.size(),
                                                  MediaSendPreviewHelper.sendItemLimit());
    }

    /**
     * KHANDAQ (Khandaq Testers 8265): the tile at the end of the strip opens the chat's own media picker, for as
     * many more items as the send takes.
     */
    private void openAddMore()
    {
        final int room = addMoreRoom();
        if (room <= 0)
        {
            thumbAdapter.setAddShown(false);
            return;
        }
        if (emojiPopup != null && emojiPopup.isShowing())
        {
            emojiPopup.dismiss();
        }
        try
        {
            startActivityForResult(MediaSendPreviewHelper.buildMediaPickerIntent(this, room), REQUEST_ADD_MORE);
        }
        catch (Exception e)
        {
            Log.i(TAG, "openAddMore:EE:" + e.getMessage());
        }
    }

    /**
     * KHANDAQ (Khandaq Testers 8265): the items picked go after those already here, and the first of them shows.
     * What is no photo or video, is here already, or is past the limit of a send is left out; the size of the
     * files is checked when they are sent, as for the first pick.
     */
    private void addPicked(final Intent data)
    {
        final List<Uri> picked = MediaSendPreviewHelper.collectUris(data);
        if (picked.isEmpty() || (uris == null))
        {
            return;
        }
        MediaSendPreviewHelper.persistUriPermissions(this, picked);
        final List<Uri> media = new ArrayList<>(picked.size());
        for (final Uri uri : picked)
        {
            if (MediaSendPreviewHelper.isPreviewableMedia(this, uri))
            {
                media.add(uri);
            }
        }
        final List<Uri> added = MediaSendPreviewHelper.itemsToAdd(uris, media, addMoreRoom(), Uri::toString);
        if (added.isEmpty())
        {
            return;
        }
        final int first = uris.size();
        uris.addAll(added);
        previewAdapter.notifyItemRangeInserted(first, added.size());
        thumbAdapter.notifyItemRangeInserted(first, added.size());
        thumbAdapter.setAddShown(addMoreRoom() > 0);
        counterView.setVisibility(View.VISIBLE);
        pager.setCurrentItem(first, true);
        updateCounter(first);
        thumbs.scrollToPosition(first);
    }

    // KHANDAQ (#7): show the edit (crop/rotate) button only for image pages, never for video.
    private void updateEditButtonVisibility(final int position)
    {
        if (editButton == null || uris == null || position < 0 || position >= uris.size())
        {
            return;
        }
        final boolean isVideo = MediaSendPreviewHelper.isVideoUri(this, uris.get(position));
        editButton.setVisibility(isVideo ? View.GONE : View.VISIBLE);
    }

    /** KHANDAQ (Khandaq Testers 8264): the edit button asks which edit, as on iOS: crop it, or draw on it. */
    private void showEditMenu(final View anchor, final int position)
    {
        final PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, EDIT_CROP, 0, R.string.media_send_edit_crop);
        menu.getMenu().add(0, EDIT_MARKUP, 1, R.string.media_send_edit_markup);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == EDIT_MARKUP)
            {
                launchMarkupEditor(position);
            }
            else
            {
                launchCropEditor(position);
            }
            return true;
        });
        menu.show();
    }

    /**
     * KHANDAQ (Khandaq Testers 8264): draw and write on the current photo. What is drawn comes back as a new
     * JPEG in the cache, as a crop does, and takes the page's place; the photo picked is never written.
     */
    private void launchMarkupEditor(final int position)
    {
        if (uris == null || position < 0 || position >= uris.size())
        {
            return;
        }
        final Uri source = uris.get(position);
        if (MediaSendPreviewHelper.isVideoUri(this, source))
        {
            return;
        }
        try
        {
            editingPosition = position;
            final java.io.File dest = new java.io.File(getCacheDir(),
                    "edit_markup_" + position + "_" + System.nanoTime() + ".jpg");
            final Intent markup = new Intent(this, PhotoMarkupActivity.class);
            markup.setData(source);
            markup.putExtra(PhotoMarkupActivity.EXTRA_OUTPUT_PATH, dest.getAbsolutePath());
            startActivityForResult(markup, REQUEST_MARKUP);
        }
        catch (Exception e)
        {
            editingPosition = -1;
            Log.i(TAG, "launchMarkupEditor:EE:" + e.getClass().getSimpleName());
            sayEditFailed();
        }
    }

    // KHANDAQ (#7): open uCrop (free crop + rotate/scale gestures) on the current photo; the result
    // returns to onActivityResult as UCrop.REQUEST_CROP and replaces that page's uri in place.
    private void launchCropEditor(final int position)
    {
        if (uris == null || position < 0 || position >= uris.size())
        {
            return;
        }
        final Uri source = uris.get(position);
        if (MediaSendPreviewHelper.isVideoUri(this, source))
        {
            return;
        }
        try
        {
            editingPosition = position;
            final Uri dest = Uri.fromFile(new java.io.File(getCacheDir(),
                    "edit_crop_" + position + "_" + System.nanoTime() + ".jpg"));
            final com.yalantis.ucrop.UCrop.Options options = new com.yalantis.ucrop.UCrop.Options();
            options.setToolbarColor(getResources().getColor(R.color.colorPrimaryDark));
            options.setStatusBarColor(getResources().getColor(R.color.colorPrimaryDark));
            options.setActiveControlsWidgetColor(getResources().getColor(R.color.colorPrimary));
            options.setFreeStyleCropEnabled(true); // any aspect ratio; rotate/scale gestures on by default
            options.setCompressionFormat(Bitmap.CompressFormat.JPEG);
            options.setCompressionQuality(90);
            final Intent crop = com.yalantis.ucrop.UCrop.of(source, dest)
                    .withMaxResultSize(2560, 2560)
                    .withOptions(options)
                    .getIntent(this);
            startActivityForResult(crop, com.yalantis.ucrop.UCrop.REQUEST_CROP);
        }
        catch (Exception e)
        {
            editingPosition = -1;
            e.printStackTrace();
            // The editor did not open: said as a failed edit, where the edit button is.
            sayEditFailed();
        }
    }

    /**
     * E21: an edit that failed is said at the top bar, next to the edit button, on the counter's plate,
     * instead of in a toast; the counter (or nothing, for a single picture) comes back after the notice.
     */
    private void sayEditFailed()
    {
        if (counterView == null || isFinishing())
        {
            return;
        }
        counterView.removeCallbacks(restoreCounter);
        final String words = getString(R.string.media_send_edit_failed);
        counterView.setText(words);
        counterView.setVisibility(View.VISIBLE);
        counterView.announceForAccessibility(words);
        counterView.postDelayed(restoreCounter, AirComposerNotice.SHOW_MS);
    }

    private final Runnable restoreCounter = () -> {
        if (counterView == null || pager == null)
        {
            return;
        }
        if (uris != null && uris.size() > 1)
        {
            updateCounter(pager.getCurrentItem());
        }
        else
        {
            counterView.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ADD_MORE)
        {
            if (resultCode == Activity.RESULT_OK)
            {
                addPicked(data);
            }
            return;
        }
        if (requestCode == REQUEST_MARKUP)
        {
            final int pos = editingPosition;
            editingPosition = -1;
            if (resultCode == Activity.RESULT_OK)
            {
                replaceEdited(pos, (data == null) ? null
                        : IntentCompat.getParcelableExtra(data, PhotoMarkupActivity.EXTRA_OUTPUT_URI, Uri.class));
            }
            else if (data != null && data.getBooleanExtra(PhotoMarkupActivity.EXTRA_FAILED, false))
            {
                sayEditFailed();
            }
            return;
        }
        if (requestCode != com.yalantis.ucrop.UCrop.REQUEST_CROP)
        {
            return;
        }
        final int pos = editingPosition;
        editingPosition = -1;
        if (resultCode == Activity.RESULT_OK)
        {
            final Uri cropped = (data == null) ? null : com.yalantis.ucrop.UCrop.getOutput(data);
            replaceEdited(pos, cropped);
        }
        else if (data != null && com.yalantis.ucrop.UCrop.getError(data) != null)
        {
            sayEditFailed();
        }
    }

    /** The page edited takes the edit's copy in place, the crop's or the drawing's. */
    private void replaceEdited(final int pos, final Uri edited)
    {
        if (edited != null && pos >= 0 && pos < uris.size())
        {
            uris.set(pos, edited);
            if (previewAdapter != null)
            {
                previewAdapter.notifyItemChanged(pos);
            }
            if (thumbAdapter != null)
            {
                thumbAdapter.notifyItemChanged(pos);
            }
        }
    }

    private void updateCounter(final int position)
    {
        counterView.setText(getString(R.string.media_send_counter_format, position + 1, uris.size()));
    }

    private interface ThumbClickListener
    {
        void onThumbClick(int position);
    }

    private static final class ThumbnailAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>
    {
        private static final int TYPE_THUMB = 0;
        /** KHANDAQ (Khandaq Testers 8265): the tile at the end, which adds more items. */
        private static final int TYPE_ADD = 1;

        private final Context context;
        private final List<Uri> items;
        private final ThumbClickListener listener;
        private final Runnable onAdd;
        private int selected;
        private boolean addShown;

        ThumbnailAdapter(final Context context, final List<Uri> items, final ThumbClickListener listener,
                         final Runnable onAdd)
        {
            this.context = context;
            this.items = items;
            this.listener = listener;
            this.onAdd = onAdd;
        }

        /** The tile that adds more, after the items; gone once the send takes no more. */
        void setAddShown(final boolean shown)
        {
            if (shown == addShown)
            {
                return;
            }
            addShown = shown;
            if (shown)
            {
                notifyItemInserted(items.size());
            }
            else
            {
                notifyItemRemoved(items.size());
            }
        }

        @Override
        public int getItemViewType(final int position)
        {
            return (position >= items.size()) ? TYPE_ADD : TYPE_THUMB;
        }

        void setSelected(final int position)
        {
            final int prev = selected;
            selected = position;
            notifyItemChanged(prev);
            notifyItemChanged(selected);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
        {
            if (viewType == TYPE_ADD)
            {
                final View tile = LayoutInflater.from(parent.getContext()).inflate(
                        R.layout.item_media_send_preview_add, parent, false);
                tile.setOnClickListener(v ->
                {
                    if (onAdd != null)
                    {
                        onAdd.run();
                    }
                });
                return new AddHolder(tile);
            }
            final View view = LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.item_media_send_preview_thumb, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position)
        {
            if (!(viewHolder instanceof Holder))
            {
                return;
            }
            final Holder holder = (Holder) viewHolder;
            final Uri uri = items.get(position);
            final boolean isVideo = MediaSendPreviewHelper.isVideoUri(context, uri);
            holder.videoIcon.setVisibility(isVideo ? View.VISIBLE : View.GONE);
            holder.selectedOverlay.setVisibility(position == selected ? View.VISIBLE : View.GONE);

            GlideApp.with(context).
                    load(uri).
                    diskCacheStrategy(DiskCacheStrategy.NONE).
                    skipMemoryCache(true).
                    centerCrop().
                    into(holder.image);

            holder.itemView.setOnClickListener(v ->
            {
                final int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null)
                {
                    listener.onThumbClick(pos);
                }
            });
        }

        @Override
        public int getItemCount()
        {
            return items.size() + (addShown ? 1 : 0);
        }

        static final class AddHolder extends RecyclerView.ViewHolder
        {
            AddHolder(final View itemView)
            {
                super(itemView);
            }
        }

        static final class Holder extends RecyclerView.ViewHolder
        {
            final ImageView image;
            final View selectedOverlay;
            final ImageView videoIcon;

            Holder(final View itemView)
            {
                super(itemView);
                image = itemView.findViewById(R.id.media_preview_thumb_image);
                selectedOverlay = itemView.findViewById(R.id.media_preview_thumb_selected);
                videoIcon = itemView.findViewById(R.id.media_preview_thumb_video_icon);
            }
        }
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
                            // KHANDAQ (0.2.56): no frame came; the loading spinner goes, or it would say it still comes.
                            target.setImageDrawable(null);
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

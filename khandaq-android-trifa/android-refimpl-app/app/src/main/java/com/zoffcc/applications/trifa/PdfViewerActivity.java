package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.github.chrisbanes.photoview.PhotoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;

/**
 * KHANDAQ (Khandaq Testers 8263): a PDF of a chat, a group or Saved Messages opens in the app, a page at a
 * time (swipe to the next, two fingers to zoom), with android.graphics.pdf.PdfRenderer, instead of going to
 * another app. A PDF kept encrypted is read from a plain copy in tmpdir, made when the viewer opens and
 * deleted when it closes. One the system cannot draw (with a password, or broken) opens in another app, with
 * a word on why. Share and Open in another app are in the More button of the top bar (ChatFileShare).
 */
public class PdfViewerActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.PdfViewerActy";

    /** A page is drawn twice as wide as the screen, so that it stays sharp when zoomed. */
    private static final int RENDER_SCALE = 2;
    /** 16 MB for one page at most. */
    static final long MAX_PAGE_PIXELS = 4_000_000L;
    /** Past this side many phones cannot draw a bitmap (their largest texture). */
    static final int MAX_PAGE_SIDE_PX = 4096;
    /** The pages kept drawn: the one on screen and those on each side of it. */
    private static final int DRAWN_BYTES = 48 * 1024 * 1024;
    /** How far from the page on screen a page is still drawn: a fast swipe through many skips the others. */
    private static final int DRAW_REACH = 1;

    /** PdfRenderer is used from this one thread: it opens one page at a time and is closed after them. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            work -> new Thread(work, "pdf-viewer"));
    // The worker's own.
    private PdfRenderer renderer = null;
    private File plainCopy = null;

    // The main thread's own.
    private final LruCache<Integer, Bitmap> drawn = new LruCache<Integer, Bitmap>(DRAWN_BYTES)
    {
        @Override
        protected int sizeOf(final Integer page, final Bitmap bitmap)
        {
            return bitmap.getByteCount();
        }
    };
    private final Set<Integer> drawing = new HashSet<>();
    private final Set<Integer> failed = new HashSet<>();
    private boolean closed = false;
    private int pageCount = 0;
    /** Read by the worker to skip the pages swiped past. */
    private volatile int shownPage = 0;

    private RecyclerView pages;
    private LinearLayoutManager pagesLayout;
    private PagerSnapHelper pagesSnap;
    private ProgressBar loading;
    private TextView pageLabel;
    private View overlay;

    private String vfsPath;
    private String exportPath;
    private boolean storageFramework;
    private String mimeType;
    private String displayName;

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);
        ChatMediaHelper.bindMediaOverlay(this);

        final Intent intent = getIntent();
        vfsPath = intent.getStringExtra(ChatMediaHelper.EXTRA_VFS_PATH);
        exportPath = intent.getStringExtra(ChatMediaHelper.EXTRA_EXPORT_PATH);
        storageFramework = "1".equals(intent.getStringExtra(ChatMediaHelper.EXTRA_STORAGE_FRAMEWORK));
        mimeType = intent.getStringExtra(ChatMediaHelper.EXTRA_MIME_TYPE);
        displayName = intent.getStringExtra(ChatMediaHelper.EXTRA_DISPLAY_NAME);

        overlay = findViewById(R.id.media_overlay_bar);
        loading = findViewById(R.id.pdf_viewer_loading);
        pageLabel = findViewById(R.id.pdf_viewer_page);
        final TextView title = findViewById(R.id.pdf_viewer_title);
        title.setText(TextUtils.isEmpty(displayName) ? "" : displayName);
        ChatFileShare.bindMoreButton(this, this::shareSource, loading);

        pages = findViewById(R.id.pdf_viewer_pages);
        pagesLayout = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        pages.setLayoutManager(pagesLayout);
        pages.setHasFixedSize(true);
        pagesSnap = new PagerSnapHelper();
        pagesSnap.attachToRecyclerView(pages);
        pages.addOnScrollListener(new RecyclerView.OnScrollListener()
        {
            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView, final int dx, final int dy)
            {
                pageShown();
            }

            @Override
            public void onScrollStateChanged(@NonNull final RecyclerView recyclerView, final int newState)
            {
                if (newState == RecyclerView.SCROLL_STATE_IDLE)
                {
                    pageShown();
                }
            }
        });
        pages.setAdapter(new PageAdapter());

        loading.setVisibility(View.VISIBLE);
        worker.execute(this::openDocument);
    }

    @Override
    protected void onDestroy()
    {
        closed = true;
        drawn.evictAll();
        if (pages != null)
        {
            pages.setAdapter(null);
        }
        // After whatever the worker still opens or draws: the renderer is closed, then the plain copy goes.
        worker.execute(this::releaseDocument);
        worker.shutdown();
        super.onDestroy();
    }

    /** What Share and Open in another app hand over: the file the chat holds, never the viewer's own copy. */
    private ChatFileShare.Source shareSource()
    {
        final boolean exported = !TextUtils.isEmpty(exportPath);
        return new ChatFileShare.Source(exported ? exportPath : vfsPath, !exported && storageFramework,
                                        displayName, mimeType);
    }

    // ---- worker thread ----

    private void openDocument()
    {
        ParcelFileDescriptor descriptor = null;
        try
        {
            descriptor = openSource();
            final PdfRenderer opened = new PdfRenderer(descriptor);
            // The renderer owns the descriptor from here on, and closes it.
            descriptor = null;
            renderer = opened;
            final int count = opened.getPageCount();
            if (count < 1)
            {
                throw new IOException("no pages");
            }
            runOnUiThread(() -> showPages(count));
        }
        catch (Exception e)
        {
            // SecurityException: a password. IOException: not a PDF, or broken. IllegalArgumentException:
            // a descriptor that cannot seek (a pipe from a provider). No path in the log: it names the file.
            Log.i(TAG, "openDocument:EE:" + e.getClass().getSimpleName());
            closeQuietly(descriptor);
            releaseDocument();
            runOnUiThread(this::openElsewhere);
        }
    }

    private ParcelFileDescriptor openSource() throws Exception
    {
        if (!TextUtils.isEmpty(exportPath))
        {
            return ParcelFileDescriptor.open(new File(exportPath), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (TextUtils.isEmpty(vfsPath))
        {
            throw new IOException("no file");
        }
        if (storageFramework || vfsPath.startsWith("content://"))
        {
            final ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(Uri.parse(vfsPath), "r");
            if (descriptor == null)
            {
                throw new IOException("no descriptor");
            }
            return descriptor;
        }
        if (VFS_ENCRYPT && HelperFiletransfer.isIocipherVirtualPath(vfsPath))
        {
            // A suffix of its own: a second viewer of the same file has its own copy, so deleting this one
            // on close never takes the file away from the other.
            plainCopy = new File(ChatMediaHelper.resolveVfsToCachedPath(vfsPath,
                                                                         "_pdf" + Long.toHexString(System.nanoTime())));
            return ParcelFileDescriptor.open(plainCopy, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        return ParcelFileDescriptor.open(new File(vfsPath), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private Bitmap renderPage(final int index, final int targetWidthPx)
    {
        if (renderer == null)
        {
            return null;
        }
        try (PdfRenderer.Page page = renderer.openPage(index))
        {
            final int[] size = PdfPageFit.bitmapSize(page.getWidth(), page.getHeight(), targetWidthPx,
                                                     MAX_PAGE_PIXELS, MAX_PAGE_SIDE_PX);
            final Bitmap bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
            // The renderer draws on what is there: a page has no background of its own.
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
        catch (Exception | OutOfMemoryError e)
        {
            Log.i(TAG, "renderPage:EE:" + e.getClass().getSimpleName());
            return null;
        }
    }

    private void releaseDocument()
    {
        if (renderer != null)
        {
            try
            {
                renderer.close();
            }
            catch (Exception e)
            {
                Log.i(TAG, "releaseDocument:close:EE:" + e.getClass().getSimpleName());
            }
            renderer = null;
        }
        if (plainCopy != null)
        {
            if (!plainCopy.delete() && plainCopy.exists())
            {
                Log.w(TAG, "releaseDocument:plain copy left");
            }
            plainCopy = null;
        }
    }

    private static void closeQuietly(final ParcelFileDescriptor descriptor)
    {
        if (descriptor == null)
        {
            return;
        }
        try
        {
            descriptor.close();
        }
        catch (Exception ignored)
        {
        }
    }

    // ---- main thread ----

    private void showPages(final int count)
    {
        if (closed)
        {
            return;
        }
        pageCount = count;
        loading.setVisibility(View.GONE);
        final RecyclerView.Adapter<?> adapter = pages.getAdapter();
        if (adapter != null)
        {
            adapter.notifyDataSetChanged();
        }
        showPageLabel(0);
    }

    /** The system cannot draw this PDF: it opens in another app, as before the viewer, with a word on why. */
    private void openElsewhere()
    {
        if (closed || isFinishing())
        {
            return;
        }
        AirComposerNotice.sayOnReturn(getString(R.string.pdf_viewer_opens_elsewhere), false);
        ChatFileShare.start(this, shareSource(), ChatFileShare.Action.VIEW, loading, this::finish);
    }

    private void showPageLabel(final int page)
    {
        if (pageCount > 0)
        {
            pageLabel.setText(getString(R.string.pdf_viewer_page_format, page + 1, pageCount));
        }
    }

    private void pageShown()
    {
        final View view = pagesSnap.findSnapView(pagesLayout);
        if (view == null)
        {
            return;
        }
        final int page = pagesLayout.getPosition(view);
        if ((page == RecyclerView.NO_POSITION) || (page >= pageCount))
        {
            return;
        }
        if (page != shownPage)
        {
            shownPage = page;
            showPageLabel(page);
        }
        // A page skipped by a fast swipe may come back from the view cache without being bound again.
        for (int near = Math.max(0, page - DRAW_REACH); near <= Math.min(pageCount - 1, page + DRAW_REACH); near++)
        {
            final RecyclerView.ViewHolder holder = pages.findViewHolderForAdapterPosition(near);
            if ((holder instanceof PageHolder) && !((PageHolder) holder).showsPage())
            {
                final Bitmap bitmap = drawn.get(near);
                if (bitmap != null)
                {
                    ((PageHolder) holder).show(bitmap);
                }
                else
                {
                    draw(near);
                }
            }
        }
    }

    private void draw(final int page)
    {
        if (closed || drawing.contains(page) || failed.contains(page))
        {
            return;
        }
        drawing.add(page);
        final int width = RENDER_SCALE * Math.max(pages.getWidth(), getResources().getDisplayMetrics().widthPixels);
        worker.execute(() -> {
            final boolean skipped = Math.abs(page - shownPage) > DRAW_REACH;
            final Bitmap bitmap = skipped ? null : renderPage(page, width);
            runOnUiThread(() -> drew(page, bitmap, skipped));
        });
    }

    private void drew(final int page, final Bitmap bitmap, final boolean skipped)
    {
        drawing.remove(page);
        if (closed)
        {
            return;
        }
        if (bitmap == null)
        {
            if (!skipped)
            {
                failed.add(page);
            }
            return;
        }
        drawn.put(page, bitmap);
        final RecyclerView.ViewHolder holder = pages.findViewHolderForAdapterPosition(page);
        if ((holder instanceof PageHolder) && (((PageHolder) holder).page == page))
        {
            ((PageHolder) holder).show(bitmap);
        }
    }

    private void toggleOverlay()
    {
        overlay.setVisibility((overlay.getVisibility() == View.VISIBLE) ? View.GONE : View.VISIBLE);
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageHolder>
    {
        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType)
        {
            final PhotoView photo = (PhotoView) LayoutInflater.from(parent.getContext()).inflate(
                    R.layout.item_pdf_viewer_page, parent, false);
            photo.setOnViewTapListener((view, x, y) -> toggleOverlay());
            return new PageHolder(photo);
        }

        @Override
        public void onBindViewHolder(@NonNull final PageHolder holder, final int position)
        {
            holder.page = position;
            holder.photo.setContentDescription(getString(R.string.pdf_viewer_page_format, position + 1, pageCount));
            final Bitmap bitmap = drawn.get(position);
            if (bitmap != null)
            {
                holder.show(bitmap);
            }
            else
            {
                holder.clear();
                draw(position);
            }
        }

        @Override
        public void onViewRecycled(@NonNull final PageHolder holder)
        {
            holder.page = RecyclerView.NO_POSITION;
            holder.clear();
        }

        @Override
        public int getItemCount()
        {
            return pageCount;
        }
    }

    private static final class PageHolder extends RecyclerView.ViewHolder
    {
        final PhotoView photo;
        int page = RecyclerView.NO_POSITION;

        PageHolder(final PhotoView photo)
        {
            super(photo);
            this.photo = photo;
        }

        void show(final Bitmap bitmap)
        {
            photo.setImageDrawable(new BitmapDrawable(photo.getResources(), bitmap));
        }

        void clear()
        {
            photo.setImageDrawable(null);
        }

        boolean showsPage()
        {
            return photo.getDrawable() != null;
        }
    }
}

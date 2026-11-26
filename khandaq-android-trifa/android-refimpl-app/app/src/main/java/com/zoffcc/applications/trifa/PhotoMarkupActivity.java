package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.FutureTarget;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KHANDAQ (Khandaq Testers 8264): Draw and write, the second edit of a photo on the send screen (the first is
 * Crop and rotate, uCrop). The photo is read at most {@link #MAX_SIDE_PX} on a side, as a crop's result is, and
 * what is drawn on it is saved as a new JPEG at the path the send screen gives, in its cache: the photo in the
 * gallery, or wherever it was picked from, is never written. The result has that copy's file URI.
 */
public class PhotoMarkupActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.PhotoMarkupActy";

    /** Where the copy goes: a file path of the send screen's cache. The photo is the intent's data. */
    static final String EXTRA_OUTPUT_PATH = "markup_output_path";
    /** On an OK result: the file URI of the copy, as an extra, as uCrop gives its own. */
    static final String EXTRA_OUTPUT_URI = "markup_output_uri";
    /** On a result that is not OK: the photo could not be read, or the copy not written. */
    static final String EXTRA_FAILED = "markup_failed";
    static final int MAX_SIDE_PX = 2560;
    static final int JPEG_QUALITY = 90;
    private static final int MAX_TEXT_CHARS = 120;

    private static final int[] COLORS = {Color.WHITE, Color.BLACK, 0xFFE53935, 0xFFFDD835, 0xFF43A047, 0xFF1E88E5};
    private static final int[] COLOR_NAMES = {R.string.photo_markup_color_white, R.string.photo_markup_color_black,
                                              R.string.photo_markup_color_red, R.string.photo_markup_color_yellow,
                                              R.string.photo_markup_color_green, R.string.photo_markup_color_blue};

    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            work -> new Thread(work, "photo-markup"));
    private final List<View> swatches = new ArrayList<>();

    private PhotoMarkupView canvas;
    private ProgressBar loading;
    private View undo;
    private View done;
    private View text;
    private File output;
    private boolean saving = false;
    private boolean closed = false;

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_markup);
        ViewUtil.applyImeBottomInsets(this);

        final Uri source = getIntent().getData();
        final String outputPath = getIntent().getStringExtra(EXTRA_OUTPUT_PATH);
        if ((source == null) || (outputPath == null) || outputPath.isEmpty())
        {
            fail();
            return;
        }
        output = new File(outputPath);

        canvas = findViewById(R.id.photo_markup_canvas);
        loading = findViewById(R.id.photo_markup_loading);
        undo = findViewById(R.id.photo_markup_undo);
        done = findViewById(R.id.photo_markup_done);
        text = findViewById(R.id.photo_markup_text);

        findViewById(R.id.photo_markup_cancel).setOnClickListener(v -> cancel());
        undo.setOnClickListener(v -> canvas.undo());
        done.setOnClickListener(v -> save());
        text.setOnClickListener(v -> askForText());
        canvas.setListener(count -> {
            undo.setEnabled(count > 0);
            undo.setAlpha((count > 0) ? 1f : 0.4f);
        });
        undo.setAlpha(0.4f);
        addSwatches();
        setToolsEnabled(false);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true)
        {
            @Override
            public void handleOnBackPressed()
            {
                cancel();
            }
        });

        final android.content.Context app = getApplicationContext();
        worker.execute(() -> {
            Bitmap photo = null;
            FutureTarget<Bitmap> target = null;
            try
            {
                // Never from the caches: the photo is read for this edit only.
                target = GlideApp.with(app).asBitmap().load(source)
                                 .downsample(DownsampleStrategy.CENTER_INSIDE)
                                 .skipMemoryCache(true)
                                 .diskCacheStrategy(DiskCacheStrategy.NONE)
                                 .submit(MAX_SIDE_PX, MAX_SIDE_PX);
                // A copy of its own: the decoded bitmap goes back to Glide's pool when its request is cleared.
                photo = target.get().copy(Bitmap.Config.ARGB_8888, false);
            }
            catch (Exception | OutOfMemoryError e)
            {
                Log.i(TAG, "load:EE:" + e.getClass().getSimpleName());
            }
            finally
            {
                if (target != null)
                {
                    GlideApp.with(app).clear(target);
                }
            }
            final Bitmap loaded = photo;
            runOnUiThread(() -> showPhoto(loaded));
        });
    }

    @Override
    protected void onDestroy()
    {
        closed = true;
        worker.shutdown();
        super.onDestroy();
    }

    private void showPhoto(final Bitmap photo)
    {
        if (closed || isFinishing())
        {
            return;
        }
        if (photo == null)
        {
            fail();
            return;
        }
        loading.setVisibility(View.GONE);
        canvas.setPhoto(photo);
        setToolsEnabled(true);
    }

    private void addSwatches()
    {
        final LinearLayout row = findViewById(R.id.photo_markup_colors);
        final float density = getResources().getDisplayMetrics().density;
        final int size = Math.round(32f * density);
        final int margin = Math.round(5f * density);
        for (int i = 0; i < COLORS.length; i++)
        {
            final int color = COLORS[i];
            final View swatch = new View(this);
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart(margin);
            params.setMarginEnd(margin);
            swatch.setLayoutParams(params);
            swatch.setContentDescription(getString(COLOR_NAMES[i]));
            swatch.setClickable(true);
            swatch.setFocusable(true);
            swatch.setOnClickListener(v -> chooseColor(color));
            swatch.setTag(color);
            swatches.add(swatch);
            row.addView(swatch);
        }
        chooseColor(COLORS[0]);
    }

    private void chooseColor(final int color)
    {
        if (canvas != null)
        {
            canvas.setColor(color);
        }
        final float density = getResources().getDisplayMetrics().density;
        for (final View swatch : swatches)
        {
            final int own = (Integer) swatch.getTag();
            final boolean chosen = (own == color);
            final GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(own);
            // The colour chosen has a white ring; the others a faint one, so that black shows on black.
            dot.setStroke(Math.round(density * (chosen ? 3f : 1.5f)), chosen ? Color.WHITE : 0x80FFFFFF);
            swatch.setBackground(dot);
            swatch.setSelected(chosen);
        }
    }

    private void askForText()
    {
        if ((canvas == null) || !canvas.hasPhoto() || saving)
        {
            return;
        }
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEXT_CHARS)});
        field.setHint(R.string.photo_markup_text_hint);
        final int pad = Math.round(getResources().getDisplayMetrics().density * 20f);
        final android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.setPadding(pad, pad / 2, pad, 0);
        holder.addView(field);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.photo_markup_add_text)
                .setView(holder)
                .setPositiveButton(android.R.string.ok, (d, which) -> canvas.addLabel(field.getText().toString()))
                .setNegativeButton(R.string.cancel, null)
                .create();
        if (dialog.getWindow() != null)
        {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        field.requestFocus();
    }

    private void save()
    {
        if ((canvas == null) || !canvas.hasPhoto() || saving)
        {
            return;
        }
        if (canvas.markCount() == 0)
        {
            // Nothing drawn: the photo stays as it was, with no copy of it.
            cancel();
            return;
        }
        final Bitmap marked;
        try
        {
            marked = canvas.render();
        }
        catch (RuntimeException | OutOfMemoryError e)
        {
            Log.i(TAG, "save:render:EE:" + e.getClass().getSimpleName());
            fail();
            return;
        }
        saving = true;
        setToolsEnabled(false);
        loading.setVisibility(View.VISIBLE);
        final File target = output;
        worker.execute(() -> {
            boolean written = false;
            try (FileOutputStream out = new FileOutputStream(target))
            {
                written = marked.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            }
            catch (IOException e)
            {
                Log.i(TAG, "save:write:EE:" + e.getClass().getSimpleName());
            }
            if (!written && target.exists() && !target.delete())
            {
                Log.w(TAG, "save:partial copy left");
            }
            final boolean saved = written;
            runOnUiThread(() -> saved(saved, target));
        });
    }

    private void saved(final boolean written, final File target)
    {
        if (!written)
        {
            fail();
            return;
        }
        setResult(Activity.RESULT_OK, new Intent().putExtra(EXTRA_OUTPUT_URI, Uri.fromFile(target)));
        finish();
    }

    private void cancel()
    {
        if (saving)
        {
            return;
        }
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private void fail()
    {
        setResult(Activity.RESULT_CANCELED, new Intent().putExtra(EXTRA_FAILED, true));
        finish();
    }

    private void setToolsEnabled(final boolean enabled)
    {
        done.setEnabled(enabled);
        done.setAlpha(enabled ? 1f : 0.4f);
        text.setEnabled(enabled);
        text.setAlpha(enabled ? 1f : 0.4f);
        for (final View swatch : swatches)
        {
            swatch.setEnabled(enabled);
        }
        canvas.setEnabled(enabled);
        if (!enabled)
        {
            undo.setEnabled(false);
        }
        else
        {
            undo.setEnabled(canvas.markCount() > 0);
        }
    }
}

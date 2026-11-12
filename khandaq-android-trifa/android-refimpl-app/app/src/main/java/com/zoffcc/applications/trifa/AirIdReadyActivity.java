package com.zoffcc.applications.trifa;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.List;

/**
 * KHANDAQ (redesign stage 4, E19): "Your ID is ready", the third step of first launch, as
 * AirIdReadyController on iOS. It stands over Air while Tox comes up with the new profile: the QR code,
 * the ID in its three parts whose groups come in one after another (at once with reduced motion or
 * TalkBack), Share, and Add the first person, which opens Air with the command line in focus.
 */
public class AirIdReadyActivity extends AirScreenActivity implements AirBand.NoBand
{
    private static final String TAG = "trifa.AirIdReady";
    private static final long POLL_MS = 250;
    private static final int STEPS = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> groups = new ArrayList<>();
    private ImageView qr;
    private FrameLayout idHolder;
    private TextView waiting;
    private TextView share;
    @Nullable
    private String shownId;
    private boolean destroyed;

    private final Runnable poll = new Runnable()
    {
        @Override
        public void run()
        {
            if (destroyed)
            {
                return;
            }
            final String id = idNow();
            if (id != null && id.length() >= ToxSaveSummary.ID_HEX_LENGTH)
            {
                bind(id, true);
            }
            else
            {
                main.postDelayed(this, POLL_MS);
            }
        }
    };

    /**
     * Opens the page once after a new profile was made on first launch (AirFirstLaunchActivity). True when
     * it opens now, so nothing else (the battery question) comes up over it.
     */
    static boolean openIfPending(final Activity activity)
    {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!prefs.getBoolean(AirFirstLaunchActivity.PREF_ID_PENDING, false))
        {
            return false;
        }
        prefs.edit().remove(AirFirstLaunchActivity.PREF_ID_PENDING).apply();
        activity.startActivity(new Intent(activity, AirIdReadyActivity.class));
        return true;
    }

    @Override
    protected int screenTitle()
    {
        return R.string.air_first_id_title;
    }

    @Override
    protected boolean headerRule()
    {
        return false;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            // As on iOS there is no way back to the password page: the profile is made.
            actionBar.setDisplayHomeAsUpEnabled(false);
            actionBar.setTitle(null);
        }
        final Toolbar toolbar = findViewById(R.id.air_screen_toolbar);
        final AirStepKit.Progress progress = new AirStepKit.Progress(this, STEPS);
        toolbar.addView(progress, new Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                           ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        progress.show(STEPS - 1, false);
        progress.show(STEPS, savedInstanceState == null);

        final Resources res = getResources();
        column.addView(AirStepKit.header(this, getString(R.string.air_first_id_title),
                                         getString(R.string.air_first_id_body)), AirScreenKit.matchWidth());

        // The QR code: dark modules on white in both themes, half the width of the page, as on iOS.
        qr = new ImageView(this);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setContentDescription(getString(R.string.air_you_qr) + ". " + getString(R.string.air_you_qr_hint));
        final HalfSquare card = new HalfSquare(this, res.getDimensionPixelSize(R.dimen.air_you_qr_max));
        final GradientDrawable plate = new GradientDrawable();
        plate.setColor(AirUi.color(this, R.color.kq_fixed_white));
        plate.setCornerRadius(res.getDimension(R.dimen.kq_radius_card));
        plate.setStroke(res.getDimensionPixelSize(R.dimen.kq_stroke_hairline), AirUi.color(this, R.color.kq_line));
        card.setBackground(plate);
        final int cardPad = res.getDimensionPixelSize(R.dimen.kq_space_12);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        card.addView(qr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                      ViewGroup.LayoutParams.MATCH_PARENT));
        final LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                                   ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER_HORIZONTAL;
        cardParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_24);
        column.addView(card, cardParams);

        // The ID; until Tox is up with the new profile there is none, and the page says so.
        idHolder = new FrameLayout(this);
        waiting = AirScreenKit.note(this, getString(R.string.myid_not_ready));
        idHolder.addView(waiting, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                               ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams idParams = AirScreenKit.matchWidth();
        idParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_16);
        column.addView(idHolder, idParams);

        share = AirScreenKit.button(this, getString(R.string.air_first_share), false);
        share.setOnClickListener(v -> share());
        share.setEnabled(false);
        final LinearLayout.LayoutParams shareParams = AirScreenKit.matchWidth();
        shareParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_12);
        column.addView(share, shareParams);

        new AirStepKit.MainBar(this, getString(R.string.air_first_add_person), this::addFirstPerson);

        final String id = idNow();
        if (id != null && id.length() >= ToxSaveSummary.ID_HEX_LENGTH)
        {
            bind(id, savedInstanceState == null);
        }
        else
        {
            main.postDelayed(poll, POLL_MS);
        }
    }

    @Override
    protected void onDestroy()
    {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Nullable
    private static String idNow()
    {
        if (!TrifaToxService.is_tox_started || TrifaToxService.manually_logged_out)
        {
            return null;
        }
        return MainActivity.get_my_toxid();
    }

    private void bind(final String toxId, final boolean animate)
    {
        final String id = toxId.substring(0, ToxSaveSummary.ID_HEX_LENGTH).toUpperCase(java.util.Locale.ROOT);
        if (id.equals(shownId))
        {
            return;
        }
        shownId = id;
        Bitmap bitmap = null;
        try
        {
            // The same content as the You screen, so every scanner that read it still does.
            bitmap = ProfileContentFragment.encodeAsBitmap("tox:" + id);
        }
        catch (Exception e)
        {
            Log.i(TAG, "qr:EE:" + e.getMessage());
        }
        if (bitmap != null)
        {
            final BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
            drawable.setFilterBitmap(false);
            drawable.setAntiAlias(false);
            qr.setImageDrawable(drawable);
        }
        groups.clear();
        idHolder.removeAllViews();
        idHolder.addView(AirStepKit.idPlate(this, id, groups),
                         new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                      ViewGroup.LayoutParams.WRAP_CONTENT));
        share.setEnabled(true);
        AirStepKit.assemble(groups, animate);
    }

    private void share()
    {
        final String id = shownId;
        if (id == null)
        {
            return;
        }
        final Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, id);
        try
        {
            startActivity(Intent.createChooser(send, getString(R.string.air_first_share)));
        }
        catch (android.content.ActivityNotFoundException e)
        {
            Log.i(TAG, "share:EE:" + e.getMessage());
        }
    }

    private void addFirstPerson()
    {
        AirFragment.focusCommandOnResume = true;
        finish();
    }

    /** The QR card: a square half as wide as its place, and no wider than the design's maximum. */
    @SuppressLint("ViewConstructor")
    private static final class HalfSquare extends FrameLayout
    {
        private final int maxSide;

        HalfSquare(final Context context, final int maxSide)
        {
            super(context);
            this.maxSide = maxSide;
        }

        @Override
        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
        {
            final int available = MeasureSpec.getSize(widthMeasureSpec);
            final int side = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? maxSide / 2
                                                                                               : Math.min(available / 2, maxSide);
            final int exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
            super.onMeasure(exact, exact);
        }
    }
}

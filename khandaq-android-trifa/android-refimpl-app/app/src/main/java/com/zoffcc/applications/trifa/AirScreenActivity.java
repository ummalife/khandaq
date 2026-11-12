package com.zoffcc.applications.trifa;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, Air): the frame of a screen reached from settings or a dossier, as
 * AirScrollController on iOS. The header carries the way back and the title; the screen fills one
 * scrolling column with AirScreenKit sections. The system bars take the page colour, so the screen
 * reads as one surface from edge to edge in both themes.
 */
abstract class AirScreenActivity extends AppCompatActivity
{
    /** The column the screen fills; 16dp from the sides, centred at the text measure on a wide window. */
    protected LinearLayout column;

    @StringRes
    protected abstract int screenTitle();

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.air_screen);
        // Edge to edge (targetSdk 35 and later): the header clears the status bar and the bottom bar
        // the keyboard, as in the conversation.
        ViewUtil.applyImeBottomInsets(this);
        final Toolbar toolbar = findViewById(R.id.air_screen_toolbar);
        setSupportActionBar(toolbar);
        final ActionBar bar = getSupportActionBar();
        if (bar != null)
        {
            bar.setDisplayHomeAsUpEnabled(true);
            bar.setTitle(screenTitle());
        }
        final int ink = AirUi.color(this, R.color.kq_ink);
        toolbar.setTitleTextAppearance(this, R.style.TextAppearance_KQ_Heading);
        toolbar.setTitleTextColor(ink);
        final Drawable navigation = toolbar.getNavigationIcon();
        if (navigation != null)
        {
            DrawableCompat.setTint(navigation.mutate(), ink);
        }
        if (!headerRule())
        {
            findViewById(R.id.air_screen_rule).setVisibility(View.GONE);
        }
        column = findViewById(R.id.air_screen_column);
        keepTextMeasure(findViewById(R.id.air_screen_root), column, findViewById(R.id.air_screen_bottom));
        styleWindow();
    }

    /**
     * Whether a hairline closes the header, as under the Air header and the conversation's, and as the
     * opaque navigation bar on iOS draws it: content the keyboard lifts slides under a drawn edge. The
     * first-launch and sign-in pages keep an open header, as LoginCoordinator on iOS does.
     */
    protected boolean headerRule()
    {
        return true;
    }

    @Override
    public boolean onSupportNavigateUp()
    {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    /** A toast over this screen. */
    protected void toast(@StringRes final int text)
    {
        AirToast.show(findViewById(R.id.air_screen_root), getString(text));
    }

    /**
     * On a tablet, or a phone on its side, the column and the bar under it keep the text measure of
     * the design (kq_size_text_measure) in the middle of the window instead of running edge to edge;
     * a window narrower than the measure keeps its 16dp sides.
     */
    private static void keepTextMeasure(final View host, final View... columns)
    {
        final int measure = host.getResources().getDimensionPixelSize(R.dimen.kq_size_text_measure);
        final int least = host.getResources().getDimensionPixelSize(R.dimen.kq_space_16);
        host.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            final int side = Math.max(least, (right - left - measure) / 2);
            for (final View column : columns)
            {
                if (column != null && (column.getPaddingStart() != side || column.getPaddingEnd() != side))
                {
                    column.setPaddingRelative(side, column.getPaddingTop(), side, column.getPaddingBottom());
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void styleWindow()
    {
        final int page = AirUi.color(this, R.color.kq_bg);
        final boolean night = HelperGeneric.is_nightmode_active(this);
        final Window window = getWindow();
        window.setStatusBarColor(page);
        window.setNavigationBarColor(page);
        final WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(!night);
        bars.setAppearanceLightNavigationBars(!night);
    }
}

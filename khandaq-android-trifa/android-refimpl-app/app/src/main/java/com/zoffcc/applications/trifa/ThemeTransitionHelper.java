package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.preference.PreferenceManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Telegram-style theme switch: snapshot of the current UI, recreate with new night mode,
 * then crossfade the snapshot away over the freshly themed activity.
 */
final class ThemeTransitionHelper
{
    private static final long CROSSFADE_MS = 350L;

    @Nullable
    private static Bitmap pendingSnapshot;
    private static boolean pendingCrossfade;

    private ThemeTransitionHelper()
    {
    }

    static void recreateWithThemeCrossfade(@NonNull final Activity activity, @NonNull final String darkModePrefValue)
    {
        captureSnapshot(activity);
        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(activity);
        settings.edit().putString("dark_mode_pref", darkModePrefValue).apply();
        MainApplication.applyDarkModeFromPref(darkModePrefValue);
        activity.recreate();
    }

    static void scheduleCrossfadeAfterCreate(@NonNull final Activity activity)
    {
        if (!pendingCrossfade || pendingSnapshot == null)
        {
            return;
        }
        activity.getWindow().getDecorView().post(() -> playCrossfade(activity));
    }

    private static void captureSnapshot(@NonNull final Activity activity)
    {
        clearPendingSnapshot();
        try
        {
            final View decor = activity.getWindow().getDecorView();
            final int width = decor.getWidth();
            final int height = decor.getHeight();
            if (width <= 0 || height <= 0)
            {
                pendingCrossfade = false;
                return;
            }

            final Bitmap snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(snapshot);
            decor.draw(canvas);
            pendingSnapshot = snapshot;
            pendingCrossfade = true;
        }
        catch (Exception ignored)
        {
            pendingCrossfade = false;
            clearPendingSnapshot();
        }
    }

    private static void playCrossfade(@NonNull final Activity activity)
    {
        if (!pendingCrossfade || pendingSnapshot == null || pendingSnapshot.isRecycled())
        {
            clearPendingSnapshot();
            pendingCrossfade = false;
            return;
        }

        pendingCrossfade = false;
        final Bitmap snapshot = pendingSnapshot;
        pendingSnapshot = null;

        if (!(activity.getWindow().getDecorView() instanceof ViewGroup))
        {
            snapshot.recycle();
            return;
        }

        final ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        final ImageView overlay = new ImageView(activity);
        overlay.setScaleType(ImageView.ScaleType.FIT_XY);
        overlay.setImageBitmap(snapshot);
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        overlay.setContentDescription(activity.getString(R.string.theme_toggle_accessibility));

        decor.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final ValueAnimator animator = ValueAnimator.ofFloat(1f, 0f);
        animator.setDuration(CROSSFADE_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(animation ->
        {
            final float alpha = (float) animation.getAnimatedValue();
            overlay.setAlpha(alpha);
        });
        animator.addListener(new AnimatorListenerAdapter()
        {
            @Override
            public void onAnimationEnd(final Animator animation)
            {
                try
                {
                    decor.removeView(overlay);
                }
                catch (Exception ignored)
                {
                }
                overlay.setImageBitmap(null);
                snapshot.recycle();
            }

            @Override
            public void onAnimationCancel(final Animator animation)
            {
                onAnimationEnd(animation);
            }
        });
        animator.start();
    }

    private static void clearPendingSnapshot()
    {
        if (pendingSnapshot != null && !pendingSnapshot.isRecycled())
        {
            pendingSnapshot.recycle();
        }
        pendingSnapshot = null;
    }
}

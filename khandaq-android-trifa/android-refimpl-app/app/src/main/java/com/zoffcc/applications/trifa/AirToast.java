package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.snackbar.Snackbar;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, Air): the toast (kq-toast) on Android: an ink plate with the card radius that floats
 * above the bottom edge, words in the label type and the on-ink colour, and at most one action, in normal
 * case and underlined. A Snackbar underneath, so TalkBack reads it, a swipe dismisses it and, with TalkBack
 * on, it stays as long as the system recommends.
 */
final class AirToast
{
    /** How long a plain toast stays. */
    static final int SHORT_MS = 3000;
    /** How long a toast with Undo stays: the owner's five seconds. */
    static final int UNDO_MS = 5000;

    private AirToast()
    {
    }

    static Snackbar show(final View anchor, final CharSequence text)
    {
        return show(anchor, text, SHORT_MS, null, null);
    }

    static Snackbar show(final View anchor, final CharSequence text, final int durationMs,
                         @Nullable final CharSequence action, @Nullable final Runnable onAction)
    {
        final Snackbar bar = make(anchor, text, durationMs);
        if (action != null && onAction != null)
        {
            bar.setAction(action, v -> onAction.run());
        }
        bar.show();
        return bar;
    }

    /** A toast in the design's look, not shown yet: the caller adds its action and callbacks. */
    static Snackbar make(final View anchor, final CharSequence text, final int durationMs)
    {
        final Context context = anchor.getContext();
        final Resources res = context.getResources();
        final int ink = AirUi.color(context, R.color.kq_ink);
        final int onInk = AirUi.color(context, R.color.kq_on_ink);
        final Snackbar bar = Snackbar.make(anchor, text, durationMs);
        final View plate = bar.getView();

        final GradientDrawable ground = new GradientDrawable();
        ground.setColor(ink);
        ground.setCornerRadius(res.getDimension(R.dimen.kq_radius_card));
        plate.setBackground(ground);
        bar.setBackgroundTint(ink);
        plate.setElevation(res.getDimension(R.dimen.air_toast_elevation));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        {
            plate.setOutlineSpotShadowColor(AirUi.color(context, R.color.kq_shadow));
            plate.setOutlineAmbientShadowColor(AirUi.color(context, R.color.kq_shadow));
        }
        final ViewGroup.LayoutParams params = plate.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams)
        {
            // Setting the parameters again is what makes the bar keep these margins when it shows.
            final int side = res.getDimensionPixelSize(R.dimen.kq_space_16);
            ((ViewGroup.MarginLayoutParams) params).setMargins(side, 0, side,
                                                               res.getDimensionPixelSize(R.dimen.kq_space_20));
            plate.setLayoutParams(params);
        }

        final TextView message = plate.findViewById(com.google.android.material.R.id.snackbar_text);
        if (message != null)
        {
            TextViewCompat.setTextAppearance(message, R.style.TextAppearance_KQ_Label);
            message.setTextColor(onInk);
            message.setMaxLines(4);
        }
        final TextView actionView = plate.findViewById(com.google.android.material.R.id.snackbar_action);
        if (actionView != null)
        {
            TextViewCompat.setTextAppearance(actionView, R.style.TextAppearance_KQ_Air_LabelStrong);
            actionView.setAllCaps(false);
            actionView.setPaintFlags(actionView.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        }
        bar.setTextColor(onInk);
        bar.setActionTextColor(onInk);
        return bar;
    }
}

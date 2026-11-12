package com.zoffcc.applications.trifa;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.StyleRes;
import androidx.core.widget.TextViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign): a short number that rolls to its new value instead of blinking. A larger
 * value comes in from below, a smaller one from above; with reduced motion it simply changes.
 * The parent describes the number to TalkBack, so this view stays out of the accessibility tree.
 */
final class AirRollingLabel extends FrameLayout
{
    private TextView current;
    private TextView incoming;
    private String text = "";
    private int count = Integer.MIN_VALUE;
    private final Interpolator easeOut;

    public AirRollingLabel(final Context context)
    {
        this(context, null);
    }

    public AirRollingLabel(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        easeOut = AnimationUtils.loadInterpolator(context, R.interpolator.kq_ease_out);
        current = makeText(context);
        incoming = makeText(context);
        incoming.setVisibility(View.GONE);
        addView(current, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        addView(incoming, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private static TextView makeText(final Context context)
    {
        final TextView view = new TextView(context);
        view.setMaxLines(1);
        view.setIncludeFontPadding(false);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    /** The text style of both faces; digits are tabular so a rolling number keeps its width. */
    void configure(@StyleRes final int appearance, @ColorInt final int color)
    {
        for (final TextView view : new TextView[]{current, incoming})
        {
            TextViewCompat.setTextAppearance(view, appearance);
            view.setTextColor(color);
            view.setFontFeatureSettings("tnum");
        }
    }

    void setTextColor(@ColorInt final int color)
    {
        current.setTextColor(color);
        incoming.setTextColor(color);
    }

    String text()
    {
        return text;
    }

    /** Shows {@code value}; the direction follows whether it grew. The first value never animates. */
    void setCount(final int value, final String shown, final boolean animated)
    {
        final boolean first = count == Integer.MIN_VALUE;
        final boolean upward = value >= count;
        count = value;
        setText(shown, animated && !first, upward);
    }

    void setText(final String value, final boolean animated, final boolean upward)
    {
        final String next = value == null ? "" : value;
        if (next.equals(text))
        {
            return;
        }
        text = next;
        current.animate().cancel();
        incoming.animate().cancel();
        if (incoming.getVisibility() == VISIBLE)
        {
            // A roll was still running (its end action never runs on cancel): its target is the value on screen.
            final TextView shown = incoming;
            incoming = current;
            current = shown;
        }
        settle();

        if (!animated || AirUi.reducedMotion() || !isLaidOut() || getHeight() == 0 || getVisibility() != VISIBLE)
        {
            current.setText(next);
            return;
        }

        final float distance = getHeight() * 0.8f;
        final long duration = AirUi.duration(getContext(), R.integer.kq_dur_state);
        final TextView leaving = current;
        final TextView arriving = incoming;
        arriving.setText(next);
        arriving.setAlpha(0f);
        arriving.setTranslationY(upward ? distance : -distance);
        arriving.setVisibility(View.VISIBLE);
        leaving.animate().translationY(upward ? -distance : distance).alpha(0f).setDuration(duration)
                .setInterpolator(easeOut).start();
        arriving.animate().translationY(0f).alpha(1f).setDuration(duration).setInterpolator(easeOut)
                .withEndAction(() -> {
                    current = arriving;
                    incoming = leaving;
                    settle();
                }).start();
    }

    /** The resting state: one face shown in place, the other hidden and reset. */
    private void settle()
    {
        current.setAlpha(1f);
        current.setTranslationY(0f);
        current.setVisibility(View.VISIBLE);
        incoming.setAlpha(1f);
        incoming.setTranslationY(0f);
        incoming.setVisibility(View.GONE);
    }
}

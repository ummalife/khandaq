package com.zoffcc.applications.trifa;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.widget.TextViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, E11): the ruler under the ID field (kq-ruler), as AirIdRulerView on iOS. The three
 * parts of an ID, the key (64), antispam (8) and the check (4), each with its count, in the design's
 * proportions 64 : 16 : 12 so the short parts stay readable. They stand left to right in every
 * language, under the ID whose characters run that way; at a large font they stand one under the other.
 */
@SuppressLint("ViewConstructor")
final class AirIdRulerView extends LinearLayout
{
    /** From this font scale on the parts stand one under the other. */
    private static final float VERTICAL_FONT_SCALE = 1.3f;
    private static final int[] WEIGHTS = {64, 16, 12};

    private final Bar[] bars = new Bar[3];
    private final TextView[] names = new TextView[3];
    private final TextView[] counts = new TextView[3];
    private final LinearLayout[] parts = new LinearLayout[3];
    private final String[] partNames;

    AirIdRulerView(final Context context)
    {
        super(context);
        final Resources res = context.getResources();
        partNames = new String[]{res.getString(R.string.air_nc_ruler_key), res.getString(R.string.air_nc_ruler_antispam),
                                 res.getString(R.string.air_nc_ruler_check)};
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        final boolean vertical = res.getConfiguration().fontScale >= VERTICAL_FONT_SCALE;
        setOrientation(vertical ? VERTICAL : HORIZONTAL);
        final int gap = res.getDimensionPixelSize(R.dimen.kq_space_6);
        for (int i = 0; i < 3; i++)
        {
            final LinearLayout part = new LinearLayout(context);
            part.setOrientation(VERTICAL);
            part.setLayoutDirection(LAYOUT_DIRECTION_LTR);
            part.setFocusable(true);
            part.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            final Bar bar = new Bar(context);
            part.addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                               res.getDimensionPixelSize(R.dimen.kq_size_bar)));
            final TextView name = AirScreenKit.text(context, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
            name.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);
            name.setText(partNames[i]);
            if (!vertical)
            {
                // "check" has three quarters of the antispam part: one line that shrinks to a quarter
                // less, as on iOS, instead of a word broken in two.
                name.setMaxLines(1);
                name.setEllipsize(TextUtils.TruncateAt.END);
                final int full = Math.round(name.getTextSize());
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(name, Math.round(full * 0.75f), full, 1,
                                                                           TypedValue.COMPLEX_UNIT_PX);
            }
            name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            final LayoutParams nameParams = AirScreenKit.matchWidth();
            nameParams.topMargin = gap;
            part.addView(name, nameParams);
            final TextView count = AirScreenKit.text(context, R.style.TextAppearance_KQ_Air_CaptionMedium, R.color.kq_ink_3);
            count.setTextAlignment(TEXT_ALIGNMENT_TEXT_START);
            count.setFontFeatureSettings("tnum");
            count.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            part.addView(count, AirScreenKit.matchWidth());
            final LayoutParams params;
            if (vertical)
            {
                params = AirScreenKit.matchWidth();
                params.topMargin = i == 0 ? 0 : res.getDimensionPixelSize(R.dimen.kq_space_8);
            }
            else
            {
                params = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, WEIGHTS[i]);
                params.leftMargin = i == 0 ? 0 : gap;
            }
            addView(part, params);
            parts[i] = part;
            bars[i] = bar;
            names[i] = name;
            counts[i] = count;
        }
        update(0, AirNewContactModel.RulerState.FILLING, false);
    }

    /** How much of each part is typed; the check part turns green or red once the ID is whole. */
    void update(final int count, final AirNewContactModel.RulerState check, final boolean animated)
    {
        final AirNewContactModel.RulerPart[] sizes = AirNewContactModel.RulerPart.parts(count);
        for (int i = 0; i < 3; i++)
        {
            final AirNewContactModel.RulerState state = i == 2 ? check : AirNewContactModel.RulerState.FILLING;
            bars[i].set((float) sizes[i].filled / sizes[i].size, state, animated);
            counts[i].setText(sizes[i].filled + "/" + sizes[i].size);
            final int text = AirUi.color(getContext(), state == AirNewContactModel.RulerState.BAD
                                                       ? R.color.kq_danger : R.color.kq_ink_3);
            names[i].setTextColor(text);
            counts[i].setTextColor(text);
            parts[i].setContentDescription(getResources().getString(R.string.air_nc_ruler_part_format, partNames[i],
                                                                    sizes[i].filled, sizes[i].size));
        }
    }

    /** The check part shakes once when the check fails; nothing moves with reduced motion. */
    void shakeCheck()
    {
        if (AirUi.reducedMotion())
        {
            return;
        }
        final float four = getResources().getDimension(R.dimen.kq_space_4);
        final float two = getResources().getDimension(R.dimen.kq_space_2);
        final ObjectAnimator shake = ObjectAnimator.ofFloat(parts[2], View.TRANSLATION_X, 0f, -four, four, -two, 0f);
        shake.setDuration(getResources().getInteger(R.integer.kq_dur_layout));
        shake.setInterpolator(new DecelerateInterpolator());
        shake.start();
    }

    /** One part's bar: the sunken track and the typed share in ink, or in green or red for the check. */
    private static final class Bar extends View
    {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction = 0f;
        private ValueAnimator animator;

        Bar(final Context context)
        {
            super(context);
            track.setColor(AirUi.color(context, R.color.kq_sunken));
            fill.setColor(AirUi.color(context, R.color.kq_ink));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void set(final float next, final AirNewContactModel.RulerState state, final boolean animated)
        {
            final int color;
            switch (state)
            {
                case GOOD:
                    color = R.color.kq_live;
                    break;
                case BAD:
                    color = R.color.kq_danger;
                    break;
                default:
                    color = R.color.kq_ink;
                    break;
            }
            fill.setColor(AirUi.color(getContext(), color));
            final float target = Math.max(0f, Math.min(1f, next));
            if (animator != null)
            {
                animator.cancel();
                animator = null;
            }
            if (!animated || !isAttachedToWindow() || AirUi.reducedMotion())
            {
                fraction = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(fraction, target);
            animator.setDuration(AirUi.duration(getContext(), R.integer.kq_dur_layout));
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(a -> {
                fraction = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void onDraw(final Canvas canvas)
        {
            final float radius = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            canvas.drawRoundRect(rect, radius, radius, track);
            if (fraction > 0f)
            {
                rect.set(0, 0, getWidth() * fraction, getHeight());
                canvas.drawRoundRect(rect, radius, radius, fill);
            }
        }
    }
}

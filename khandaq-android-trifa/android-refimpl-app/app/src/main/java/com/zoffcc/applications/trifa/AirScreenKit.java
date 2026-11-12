package com.zoffcc.applications.trifa;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, Air): the parts every settings-like screen is built from, mirroring AirKit and
 * AirScrollController.addSection on iOS: a section heading, rows flush on a surface plate, the words
 * under a section, buttons and a key in groups of four. Screens build them in code, so the same
 * section reads the same in the settings tab, the Blocked screen and the screens after them.
 */
final class AirScreenKit
{
    /** Callback of a segmented control: the index of the option chosen. */
    interface SegmentListener
    {
        void onSegmentPicked(int index);
    }

    private AirScreenKit()
    {
    }

    static TextView text(final Context context, @StyleRes final int appearance, final int colorRes)
    {
        final TextView view = new TextView(context);
        TextViewCompat.setTextAppearance(view, appearance);
        view.setTextColor(AirUi.color(context, colorRes));
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return view;
    }

    /** A section heading: a label in the quiet ink, announced as a heading. */
    static TextView heading(final Context context, final CharSequence title)
    {
        final TextView view = text(context, R.style.TextAppearance_KQ_Label, R.color.kq_ink_3);
        view.setText(title);
        ViewCompat.setAccessibilityHeading(view, true);
        return view;
    }

    /** Words that explain a screen, above its rows or in place of them. */
    static TextView body(final Context context, final CharSequence words)
    {
        final TextView view = text(context, R.style.TextAppearance_KQ_Air_LabelRegular, R.color.kq_ink_2);
        view.setText(words);
        return view;
    }

    /** The quiet line under a section, for a consequence the rows do not say themselves. */
    static TextView note(final Context context, final CharSequence words)
    {
        final TextView view = text(context, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
        view.setText(words);
        return view;
    }

    /** The surface plate rows sit on, edge to edge, with the card radius. */
    static LinearLayout plate(final Context context)
    {
        final LinearLayout plate = new LinearLayout(context);
        plate.setOrientation(LinearLayout.VERTICAL);
        plate.setBackgroundResource(R.drawable.air_bg_plate);
        plate.setClipToOutline(true);
        return plate;
    }

    /** A plate holding the rows, for a section that is a list. */
    static LinearLayout plateOf(final Context context, final View... rows)
    {
        final LinearLayout plate = plate(context);
        for (View row : rows)
        {
            if (row != null)
            {
                plate.addView(row, matchWidth());
            }
        }
        return plate;
    }

    /**
     * Appends a section to a screen's column: its heading, its parts and the note, 8dp apart; 24dp above
     * it when another section comes first. Either text may be null.
     */
    static LinearLayout addSection(final LinearLayout column, @Nullable final CharSequence title,
                                   @Nullable final CharSequence note, final View... parts)
    {
        final Context context = column.getContext();
        final Resources res = context.getResources();
        final int gap = res.getDimensionPixelSize(R.dimen.kq_space_8);
        final LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        boolean first = true;
        if (title != null)
        {
            section.addView(heading(context, title), matchWidth());
            first = false;
        }
        for (View part : parts)
        {
            if (part == null)
            {
                continue;
            }
            final LinearLayout.LayoutParams params = matchWidth();
            params.topMargin = first ? 0 : gap;
            section.addView(part, params);
            first = false;
        }
        if (note != null)
        {
            final LinearLayout.LayoutParams params = matchWidth();
            params.topMargin = first ? 0 : gap;
            section.addView(note(context, note), params);
        }
        final LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = column.getChildCount() == 0 ? 0 : res.getDimensionPixelSize(R.dimen.kq_space_24);
        column.addView(section, params);
        return section;
    }

    /** The design-system button; ink for the one main action of a place. Sized to its label. */
    static TextView button(final Context context, final CharSequence label, final boolean ink)
    {
        final TextView button = new TextView(context, null, 0,
                                             ink ? R.style.Widget_KQ_Air_Button_Ink : R.style.Widget_KQ_Air_Button);
        button.setText(label);
        AirUi.markAsButton(button);
        return button;
    }

    /** The design-system button with an icon before its label, as the dossier's Copy key and QR. */
    static LinearLayout iconButton(final Context context, final CharSequence label, @DrawableRes final int icon)
    {
        final Resources res = context.getResources();
        final LinearLayout button = new LinearLayout(context, null, 0, R.style.Widget_KQ_Air_ButtonRow);
        final ImageView image = new ImageView(context);
        image.setImageResource(icon);
        ImageViewCompat.setImageTintList(image, ColorStateList.valueOf(AirUi.color(context, R.color.kq_ink)));
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final int size = res.getDimensionPixelSize(R.dimen.kq_size_icon);
        button.addView(image, new LinearLayout.LayoutParams(size, size));
        final TextView text = new TextView(context, null, 0, R.style.Widget_KQ_Air_ButtonRowText);
        text.setText(label);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                                   ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.setMarginStart(res.getDimensionPixelSize(R.dimen.kq_space_6));
        button.addView(text, textParams);
        button.setContentDescription(label);
        AirUi.markAsButton(button);
        return button;
    }

    /**
     * Buttons in equal parts side by side, 8dp apart, or one under the other when the widest label would
     * not fit its part (a long language, a large font), as AirKit.buttonRow on iOS.
     */
    static LinearLayout buttonRow(final Context context, final View... buttons)
    {
        final ButtonRow row = new ButtonRow(context);
        for (View button : buttons)
        {
            if (button != null)
            {
                row.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
        return row;
    }

    /** A button that keeps to the start of its line instead of stretching. */
    static View leading(final View view)
    {
        final LinearLayout line = new LinearLayout(view.getContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.START);
        line.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                         ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    /**
     * A public key in groups of four, four groups a line, left to right in every language. TalkBack reads
     * the groups one after another under the "Key" label.
     */
    static TextView keyText(final Context context, final String key)
    {
        final TextView view = text(context, R.style.TextAppearance_KQ_Mono, R.color.kq_ink);
        view.setTextDirection(View.TEXT_DIRECTION_LTR);
        view.setText(AirConversationModel.keyLines(key, 4));
        view.setContentDescription(context.getString(R.string.air_dossier_key_title) + ": "
                                   + AirConversationModel.keyLines(key, 0));
        return view;
    }

    /**
     * A choice of a few short options (kq-seg): a sunken well with the chosen option raised. Each option
     * stays on one line, sized to its label, and the spare width is shared out evenly. When the labels do
     * not fit side by side (large text, a long translation), the same choice stands as radio rows
     * (kq-radio), one under another. TalkBack reads "name: option" in both forms.
     */
    static LinearLayout segment(final Context context, final CharSequence name, final CharSequence[] labels,
                                final int selected, final SegmentListener listener)
    {
        return new SegmentChoice(context, name, labels, selected, listener);
    }

    /**
     * One answer of a choice (kq-radio): a round mark, filled while chosen, then the title and, when given,
     * its consequence under it. sidePadding is the row's own inset at the start and the end.
     */
    static LinearLayout choiceRow(final Context context, final CharSequence title,
                                  @Nullable final CharSequence detail, final int sidePadding)
    {
        final Resources res = context.getResources();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.air_bg_row);
        final int vertical = res.getDimensionPixelSize(R.dimen.kq_space_12);
        row.setPaddingRelative(sidePadding, vertical, sidePadding, vertical);
        row.setMinimumHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android));
        row.setClickable(true);
        row.setFocusable(true);

        final FrameLayout mark = new FrameLayout(context);
        final View dot = new View(context);
        final GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(AirUi.color(context, R.color.kq_ink));
        dot.setBackground(dotShape);
        final int dotSize = res.getDimensionPixelSize(R.dimen.kq_size_dot);
        mark.addView(dot, new FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER));
        final int markSize = res.getDimensionPixelSize(R.dimen.kq_size_icon);
        final LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(markSize, markSize);
        markParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_2);
        markParams.setMarginEnd(res.getDimensionPixelSize(R.dimen.kq_space_12));
        row.addView(mark, markParams);

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView titleView = text(context, R.style.TextAppearance_KQ_Body, R.color.kq_ink);
        titleView.setText(title);
        texts.addView(titleView, matchWidth());
        if (detail != null && detail.length() > 0)
        {
            final TextView detailView = text(context, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
            detailView.setText(detail);
            final LinearLayout.LayoutParams detailParams = matchWidth();
            detailParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_2);
            texts.addView(detailView, detailParams);
            row.setContentDescription(title + ". " + detail);
        }
        else
        {
            row.setContentDescription(title);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setTag(R.id.air_ng_mark, mark);
        ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat()
        {
            @Override
            public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfoCompat info)
            {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(RadioButton.class.getName());
                info.setCheckable(true);
                info.setChecked(host.isSelected());
            }
        });
        return row;
    }

    /** One answer of a choice with the row inset of a list (kq_space_16 at both sides). */
    static LinearLayout choiceRow(final Context context, final CharSequence title, @Nullable final CharSequence detail)
    {
        return choiceRow(context, title, detail,
                         context.getResources().getDimensionPixelSize(R.dimen.kq_space_16));
    }

    /** Fills or empties the mark of a choiceRow. */
    static void applyChoice(final LinearLayout row, final boolean selected)
    {
        final Context context = row.getContext();
        row.setSelected(selected);
        final FrameLayout mark = (FrameLayout) row.getTag(R.id.air_ng_mark);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(context.getResources().getDimensionPixelSize(R.dimen.kq_stroke_strong),
                       AirUi.color(context, selected ? R.color.kq_ink : R.color.kq_line_strong));
        mark.setBackground(ring);
        mark.getChildAt(0).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
    }

    static LinearLayout.LayoutParams matchWidth()
    {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                             ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** The row of buttonRow: it measures the labels first and stands them one under the other if needed. */
    @SuppressLint("ViewConstructor")
    private static final class ButtonRow extends LinearLayout
    {
        private final int gap;

        ButtonRow(final Context context)
        {
            super(context);
            gap = context.getResources().getDimensionPixelSize(R.dimen.kq_space_8);
            setOrientation(HORIZONTAL);
        }

        @Override
        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
        {
            final int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int widest = 0;
            int shown = 0;
            for (int i = 0; i < getChildCount(); i++)
            {
                final View child = getChildAt(i);
                if (child.getVisibility() == GONE)
                {
                    continue;
                }
                child.measure(unspecified, unspecified);
                widest = Math.max(widest, child.getMeasuredWidth());
                shown++;
            }
            final int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            final boolean sideBySide = shown < 2 || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                                       || widest * shown + gap * (shown - 1) <= available;
            boolean first = true;
            for (int i = 0; i < getChildCount(); i++)
            {
                final View child = getChildAt(i);
                final LayoutParams params = (LayoutParams) child.getLayoutParams();
                params.width = sideBySide ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
                params.weight = sideBySide ? 1f : 0f;
                final int space = first || child.getVisibility() == GONE ? 0 : gap;
                params.setMarginStart(sideBySide ? space : 0);
                params.topMargin = sideBySide ? 0 : space;
                // The start margin turns into left or right only when resolved; measuring reads those.
                params.resolveLayoutDirection(getLayoutDirection());
                if (child.getVisibility() != GONE)
                {
                    first = false;
                }
            }
            if (getOrientation() != (sideBySide ? HORIZONTAL : VERTICAL))
            {
                setOrientation(sideBySide ? HORIZONTAL : VERTICAL);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    /**
     * segment(): the well of options and the radio rows of the same choice. Measuring picks the form: the
     * well when every label fits on one line side by side, the rows otherwise.
     */
    @SuppressLint("ViewConstructor")
    private static final class SegmentChoice extends LinearLayout
    {
        private final LinearLayout well;
        private final LinearLayout rows;
        private final TextView[] options;
        private final LinearLayout[] radios;
        private final int optionSide;

        SegmentChoice(final Context context, final CharSequence name, final CharSequence[] labels,
                      final int selected, final SegmentListener listener)
        {
            super(context);
            setOrientation(VERTICAL);
            final Resources res = context.getResources();
            optionSide = res.getDimensionPixelSize(R.dimen.kq_space_8);

            well = new LinearLayout(context);
            well.setOrientation(HORIZONTAL);
            well.setBackgroundResource(R.drawable.air_bg_segment);
            final int pad = res.getDimensionPixelSize(R.dimen.kq_space_2);
            well.setPaddingRelative(pad, 0, pad, 0);
            rows = new LinearLayout(context);
            rows.setOrientation(VERTICAL);

            options = new TextView[labels.length];
            radios = new LinearLayout[labels.length];
            for (int i = 0; i < labels.length; i++)
            {
                final int index = i;
                final TextView option = new TextView(context);
                TextViewCompat.setTextAppearance(option, R.style.TextAppearance_KQ_Label);
                option.setTextColor(res.getColorStateList(R.color.air_segment_text, context.getTheme()));
                option.setBackgroundResource(R.drawable.air_bg_segment_option);
                option.setGravity(Gravity.CENTER);
                option.setSingleLine(true);
                option.setEllipsize(android.text.TextUtils.TruncateAt.END);
                option.setMinHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android));
                final int vertical = res.getDimensionPixelSize(R.dimen.kq_space_12);
                option.setPaddingRelative(optionSide, vertical, optionSide, vertical);
                option.setText(labels[i]);
                option.setClickable(true);
                option.setFocusable(true);
                option.setContentDescription(name + ": " + labels[i]);
                AirUi.markAsToggle(option);
                option.setOnClickListener(v -> pick(index, listener));
                options[i] = option;
                // The width is the label's own, set when measuring; the weight shares out the rest.
                well.addView(option, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                final LinearLayout radio = choiceRow(context, labels[i], null, 0);
                radio.setContentDescription(name + ": " + labels[i]);
                radio.setOnClickListener(v -> pick(index, listener));
                radios[i] = radio;
                rows.addView(radio, matchWidth());
            }
            show(selected);
            addView(well, matchWidth());
            addView(rows, matchWidth());
            rows.setVisibility(GONE);
        }

        private void pick(final int index, final SegmentListener listener)
        {
            if (options[index].isSelected())
            {
                return;
            }
            show(index);
            listener.onSegmentPicked(index);
        }

        private void show(final int index)
        {
            for (int i = 0; i < options.length; i++)
            {
                options[i].setSelected(i == index);
                applyChoice(radios[i], i == index);
            }
        }

        @Override
        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
        {
            int natural = well.getPaddingLeft() + well.getPaddingRight();
            for (final TextView option : options)
            {
                final int labelWidth = (int) Math.ceil(option.getPaint().measureText(option.getText().toString()))
                                       + 2 * optionSide;
                ((LinearLayout.LayoutParams) option.getLayoutParams()).width = labelWidth;
                natural += labelWidth;
            }
            final int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
            final boolean sideBySide = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                                       || natural <= available;
            if ((well.getVisibility() == VISIBLE) != sideBySide)
            {
                well.setVisibility(sideBySide ? VISIBLE : GONE);
                rows.setVisibility(sideBySide ? GONE : VISIBLE);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}

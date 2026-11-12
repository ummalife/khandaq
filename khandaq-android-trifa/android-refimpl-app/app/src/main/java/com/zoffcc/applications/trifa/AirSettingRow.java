package com.zoffcc.applications.trifa;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, Air): one settings row, as AirSettingRow on iOS. The title and an optional detail
 * line; at the trailing side the value and a chevron when the row opens something, or a switch that the
 * whole row toggles. Rows sit flush on a plate (AirScreenKit.plateOf), 56dp high at least. When the
 * value beside the title would leave the title too narrow for its widest word (a large font), the value
 * goes under the title instead, where it has the whole width.
 */
@SuppressLint("ViewConstructor")
final class AirSettingRow extends LinearLayout
{
    private final TextView title;
    private final TextView detail;
    private final TextView value;
    private final TextView valueBelow;
    private final ImageView chevron;
    private final int valueSpace;
    private final int chevronSpace;
    @Nullable
    private SwitchCompat toggle;

    AirSettingRow(final Context context, final CharSequence titleText, final boolean opens)
    {
        super(context);
        final Resources res = context.getResources();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android)
                         + res.getDimensionPixelSize(R.dimen.kq_space_8));
        // The background first: its focus ring is an inset, and setting it resets the padding to that inset.
        setBackgroundResource(R.drawable.air_bg_row);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_16);
        final int vertical = res.getDimensionPixelSize(R.dimen.kq_space_12);
        setPaddingRelative(side, vertical, side, vertical);
        setClickable(true);
        setFocusable(true);

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        title = AirScreenKit.text(context, R.style.TextAppearance_KQ_Body, R.color.kq_ink);
        title.setText(titleText);
        texts.addView(title, AirScreenKit.matchWidth());
        valueBelow = AirScreenKit.text(context, R.style.TextAppearance_KQ_Air_LabelRegular, R.color.kq_ink_2);
        valueBelow.setVisibility(GONE);
        final LayoutParams belowParams = AirScreenKit.matchWidth();
        belowParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_2);
        texts.addView(valueBelow, belowParams);
        detail = AirScreenKit.text(context, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
        detail.setVisibility(GONE);
        final LayoutParams detailParams = AirScreenKit.matchWidth();
        detailParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_2);
        texts.addView(detail, detailParams);
        addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        value = AirScreenKit.text(context, R.style.TextAppearance_KQ_Air_LabelRegular, R.color.kq_ink_2);
        value.setMaxLines(2);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setTextAlignment(TEXT_ALIGNMENT_VIEW_END);
        value.setVisibility(GONE);
        final LayoutParams valueParams = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                          ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.setMarginStart(res.getDimensionPixelSize(R.dimen.kq_space_8));
        addView(value, valueParams);
        valueSpace = res.getDimensionPixelSize(R.dimen.kq_space_8);

        chevron = new ImageView(context);
        chevron.setImageResource(R.drawable.kq_ic_chevron_down);
        ImageViewCompat.setImageTintList(chevron, ColorStateList.valueOf(AirUi.color(context, R.color.kq_ink_3)));
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        chevron.setVisibility(opens ? VISIBLE : GONE);
        final int icon = res.getDimensionPixelSize(R.dimen.kq_size_icon_small);
        final LayoutParams chevronParams = new LayoutParams(icon, icon);
        chevronParams.setMarginStart(res.getDimensionPixelSize(R.dimen.kq_space_8));
        addView(chevron, chevronParams);
        chevronSpace = icon + res.getDimensionPixelSize(R.dimen.kq_space_8);
        pointChevron();

        AirUi.markAsButton(this);
    }

    /** The quiet line under the title. */
    AirSettingRow detail(@Nullable final CharSequence text)
    {
        detail.setText(text);
        detail.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
        return this;
    }

    /** The value at the trailing side, or under the title when it does not fit there; empty hides it. */
    void setValue(@Nullable final CharSequence text)
    {
        value.setText(text);
        valueBelow.setText(text);
        value.setVisibility(TextUtils.isEmpty(text) || toggle != null ? GONE : VISIBLE);
        valueBelow.setVisibility(GONE);
        requestLayout();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
    {
        final boolean shown = toggle == null && !TextUtils.isEmpty(value.getText());
        boolean below = false;
        if (shown && MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED)
        {
            final int room = MeasureSpec.getSize(widthMeasureSpec) - getPaddingStart() - getPaddingEnd() - valueSpace
                             - (chevron.getVisibility() == GONE ? 0 : chevronSpace);
            below = widestUnbreakable(title) + value.getPaint().measureText(value.getText().toString()) > room;
        }
        value.setVisibility(shown && !below ? VISIBLE : GONE);
        valueBelow.setVisibility(shown && below ? VISIBLE : GONE);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /** The widest piece of the text a line may not break inside: a word, or one CJK character. */
    private static float widestUnbreakable(final TextView view)
    {
        final String text = view.getText().toString();
        if (text.isEmpty())
        {
            return 0f;
        }
        final java.text.BreakIterator lines = java.text.BreakIterator.getLineInstance(view.getTextLocale());
        lines.setText(text);
        float widest = 0f;
        int start = lines.first();
        for (int end = lines.next(); end != java.text.BreakIterator.DONE; start = end, end = lines.next())
        {
            widest = Math.max(widest, view.getPaint().measureText(text.substring(start, end).trim()));
        }
        return widest;
    }

    /** The title in the danger colour, for a row whose action cannot be taken back. */
    AirSettingRow danger()
    {
        title.setTextColor(AirUi.color(getContext(), R.color.kq_danger));
        return this;
    }

    /**
     * A switch at the trailing side instead of the value and chevron. The whole row toggles it and reads
     * as one switch to TalkBack; the listener hears every change, the first setting included only when
     * it is given after this call.
     */
    SwitchCompat withSwitch(final boolean checked, final CompoundButton.OnCheckedChangeListener listener)
    {
        final SwitchCompat view = new SwitchCompat(getContext());
        final Resources res = getResources();
        view.setThumbTintList(res.getColorStateList(R.color.air_switch_thumb, getContext().getTheme()));
        view.setTrackTintList(res.getColorStateList(R.color.air_switch_track, getContext().getTheme()));
        view.setChecked(checked);
        view.setClickable(false);
        view.setFocusable(false);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setOnCheckedChangeListener(listener);
        final LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                     ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(res.getDimensionPixelSize(R.dimen.kq_space_12));
        addView(view, params);
        toggle = view;
        value.setVisibility(GONE);
        valueBelow.setVisibility(GONE);
        chevron.setVisibility(GONE);
        setOnClickListener(v -> view.toggle());
        ViewCompat.setAccessibilityDelegate(this, new AccessibilityDelegateCompat()
        {
            @Override
            public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfoCompat info)
            {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(view.isChecked());
            }
        });
        return view;
    }

    @Override
    public void onRtlPropertiesChanged(final int layoutDirection)
    {
        super.onRtlPropertiesChanged(layoutDirection);
        pointChevron();
    }

    /** The icon set's chevron points down; a row that opens something points it to the trailing side. */
    private void pointChevron()
    {
        chevron.setRotation(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 90f : -90f);
    }
}

package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import org.khandaq.messenger.R;

/**
 * 24 cells, one per clock hour, as AirDayStripView on iOS: green for an hour the person was online, the
 * current hour outlined (and solid green while they are online). The compact strip sits under the chat
 * header; the tall one in the dossier, with 0, 6, 12, 18 and 24 under it in tabular figures.
 * Mirrored for right-to-left.
 */
final class AirDayStripView extends View
{
    private final boolean tall;
    private final Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cell = new RectF();
    private final float gap;
    private final float tick;
    private final float cellHeight;
    private final float axisHeight;
    private boolean[] online = new boolean[24];
    private int nowHour = 0;
    private boolean nowOnline = false;

    public AirDayStripView(final Context context)
    {
        this(context, null);
    }

    public AirDayStripView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
        boolean isTall = false;
        if (attrs != null)
        {
            final TypedArray values = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.tag});
            isTall = "tall".equals(values.getString(0));
            values.recycle();
        }
        tall = isTall;
        gap = getResources().getDimension(R.dimen.kq_space_2);
        tick = getResources().getDimension(R.dimen.kq_radius_tick);
        cellHeight = getResources().getDimension(tall ? R.dimen.kq_size_hour_cell_tall : R.dimen.kq_size_hour_cell);
        cellPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(getResources().getDimension(R.dimen.kq_stroke_strong));
        axisPaint.setTextSize(getResources().getDimension(R.dimen.kq_text_caption));
        axisPaint.setTypeface(ResourcesCompat.getFont(context, R.font.ibm_plex_sans_regular));
        axisPaint.setFontFeatureSettings("tnum");
        final Paint.FontMetrics metrics = axisPaint.getFontMetrics();
        axisHeight = tall ? getResources().getDimension(R.dimen.kq_space_4) + (metrics.descent - metrics.ascent) : 0f;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setStrip(final AirConversationModel.DayStrip strip, final boolean isNowOnline)
    {
        if (strip == null)
        {
            return;
        }
        online = strip.online.clone();
        nowHour = Math.max(0, Math.min(23, strip.nowHour));
        nowOnline = isNowOnline;
        invalidate();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
    {
        final int ring = (int) Math.ceil(1 + ringPaint.getStrokeWidth());
        final int height = (int) Math.ceil(cellHeight + axisHeight) + 2 * ring;
        final int width = tall ? MeasureSpec.getSize(widthMeasureSpec)
                               : (int) getResources().getDimension(R.dimen.kq_size_hours) + 2 * ring;
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(final Canvas canvas)
    {
        super.onDraw(canvas);
        final Context context = getContext();
        final float ringRoom = 1 + ringPaint.getStrokeWidth();
        final float left = ringRoom;
        final float top = ringRoom;
        final float usable = getWidth() - 2 * ringRoom;
        final float width = Math.max(1f, (usable - gap * 23) / 24f);
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        final int liveColor = AirUi.color(context, R.color.kq_live);
        final int cellColor = AirUi.color(context, R.color.kq_live_cell);
        final int emptyColor = AirUi.color(context, R.color.kq_sunken);
        for (int hour = 0; hour < 24; hour++)
        {
            final int slot = rtl ? 23 - hour : hour;
            final float x = left + slot * (width + gap);
            cell.set(x, top, x + width, top + cellHeight);
            if (hour == nowHour && nowOnline)
            {
                cellPaint.setColor(liveColor);
            }
            else if (online[hour])
            {
                cellPaint.setColor(cellColor);
            }
            else
            {
                cellPaint.setColor(emptyColor);
            }
            canvas.drawRoundRect(cell, tick, tick, cellPaint);
            if (hour == nowHour)
            {
                final float inset = 1 + ringPaint.getStrokeWidth() / 2f;
                ringPaint.setColor(AirUi.color(context, R.color.kq_ink));
                canvas.drawRoundRect(new RectF(cell.left - inset, cell.top - inset, cell.right + inset,
                                               cell.bottom + inset), tick + 1, tick + 1, ringPaint);
            }
        }
        if (tall)
        {
            axisPaint.setColor(AirUi.color(context, R.color.kq_ink_3));
            final Paint.FontMetrics metrics = axisPaint.getFontMetrics();
            final float baseline = top + cellHeight + getResources().getDimension(R.dimen.kq_space_4) - metrics.ascent;
            for (int value = 0; value <= 24; value += 6)
            {
                final String label = AirUi.localDigits(context, String.valueOf(value));
                final float labelWidth = axisPaint.measureText(label);
                final float fraction = value / 24f;
                final float position = rtl ? 1f - fraction : fraction;
                float x = left + position * usable - labelWidth / 2f;
                x = Math.min(Math.max(left, x), left + usable - labelWidth);
                canvas.drawText(label, x, baseline, axisPaint);
            }
        }
    }
}

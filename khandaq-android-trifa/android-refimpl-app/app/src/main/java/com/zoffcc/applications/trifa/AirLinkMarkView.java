package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import org.khandaq.messenger.R;

/**
 * The link to the person in a 30 by 10 mark beside the presence line, as AirLinkMarkView on iOS: two
 * dots joined by a line when the link is direct, a small box on the line for a relay, and a short
 * dashed line with a hollow far dot when there is no link. Mirrored for right-to-left.
 */
final class AirLinkMarkView extends View
{
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dash;
    private final RectF box = new RectF();
    private AirConversationModel.LinkState state = AirConversationModel.LinkState.NONE;

    public AirLinkMarkView(final Context context)
    {
        this(context, null);
    }

    public AirLinkMarkView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
        final float density = getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeWidth(getResources().getDimension(R.dimen.kq_stroke_mark));
        dash = new DashPathEffect(new float[]{2 * density, 3 * density}, 0);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setState(final AirConversationModel.LinkState next)
    {
        if (next != null && next != state)
        {
            state = next;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
    {
        final int width = (int) getResources().getDimension(R.dimen.kq_size_link_mark);
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(Math.round(width / 3f), heightMeasureSpec));
    }

    @Override
    protected void onDraw(final Canvas canvas)
    {
        super.onDraw(canvas);
        final float scale = getWidth() / 30f;
        final float midY = getHeight() / 2f;
        final boolean live = state != AirConversationModel.LinkState.NONE;
        final int color = AirUi.color(getContext(), live ? R.color.kq_live : R.color.kq_ink_3);
        fill.setColor(color);
        stroke.setColor(color);
        final float radius = 3 * scale;

        canvas.drawCircle(x(4, scale), midY, radius, fill);
        stroke.setPathEffect(null);
        switch (state)
        {
            case DIRECT:
                canvas.drawLine(x(7, scale), midY, x(23, scale), midY, stroke);
                break;
            case RELAY:
            {
                canvas.drawLine(x(7, scale), midY, x(12.5f, scale), midY, stroke);
                canvas.drawLine(x(17.5f, scale), midY, x(23, scale), midY, stroke);
                final float side = 5 * scale;
                final float left = Math.min(x(12.5f, scale), x(17.5f, scale));
                box.set(left, midY - side / 2f, left + side, midY + side / 2f);
                canvas.drawRoundRect(box, scale, scale, stroke);
                break;
            }
            case NONE:
            default:
                stroke.setPathEffect(dash);
                canvas.drawLine(x(10, scale), midY, x(20, scale), midY, stroke);
                stroke.setPathEffect(null);
                break;
        }
        if (live)
        {
            canvas.drawCircle(x(26, scale), midY, radius, fill);
        }
        else
        {
            canvas.drawCircle(x(26, scale), midY, radius - stroke.getStrokeWidth() / 2f, stroke);
        }
    }

    private float x(final float value, final float scale)
    {
        final float scaled = value * scale;
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - scaled : scaled;
    }
}

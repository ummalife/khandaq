package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.res.ResourcesCompat;

import org.khandaq.messenger.R;

/**
 * The path picture of the dossier's "Link" section, as AirPathView on iOS: you on one side, the person
 * on the other, joined by a solid green line when there is a link, with a small box in the middle when
 * it runs through a relay, and a dashed grey line when there is none. Mirrored for right-to-left.
 */
final class AirPathView extends View
{
    private static final float HEIGHT_DP = 76f;
    private static final float RADIUS_DP = 18f;
    private static final float CENTER_Y_DP = 30f;
    private static final float END_INSET_DP = 26f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint endStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint strongText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint quietText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dash;
    private final float density;
    private AirConversationModel.LinkState state = AirConversationModel.LinkState.NONE;
    private String initials = "";
    private String name = "";

    public AirPathView(final Context context)
    {
        this(context, null);
    }

    public AirPathView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(getResources().getDimension(R.dimen.kq_stroke_strong));
        endFill.setStyle(Paint.Style.FILL);
        endStroke.setStyle(Paint.Style.STROKE);
        endStroke.setStrokeWidth(getResources().getDimension(R.dimen.kq_stroke_hairline));
        nodeFill.setStyle(Paint.Style.FILL);
        nodeStroke.setStyle(Paint.Style.STROKE);
        nodeStroke.setStrokeWidth(getResources().getDimension(R.dimen.kq_stroke_strong));
        dash = new DashPathEffect(new float[]{4 * density, 4 * density}, 0);
        final float captionSize = getResources().getDimension(R.dimen.kq_text_caption);
        final Typeface strong = ResourcesCompat.getFont(context, R.font.ibm_plex_sans_semibold);
        final Typeface regular = ResourcesCompat.getFont(context, R.font.ibm_plex_sans_regular);
        strongText.setTextSize(captionSize);
        strongText.setTypeface(strong);
        strongText.setTextAlign(Paint.Align.CENTER);
        quietText.setTextSize(captionSize);
        quietText.setTypeface(regular);
        quietText.setTextAlign(Paint.Align.CENTER);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void configure(final AirConversationModel.LinkState next, final String personInitials, final String personName)
    {
        state = next == null ? AirConversationModel.LinkState.NONE : next;
        initials = personInitials == null ? "" : personInitials;
        name = personName == null ? "" : personName;
        invalidate();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
    {
        // The name under the far end is set in the caption size, which grows with the phone's font; the
        // view grows with it, so at font 2.0 the name is not cut at the bottom.
        final Paint.FontMetrics metrics = quietText.getFontMetrics();
        final float nameBottom = (CENTER_Y_DP + RADIUS_DP + 6f) * density - metrics.ascent + metrics.descent;
        final int height = Math.round(Math.max(HEIGHT_DP * density, nameBottom));
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(final Canvas canvas)
    {
        super.onDraw(canvas);
        final Context context = getContext();
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        final boolean live = state != AirConversationModel.LinkState.NONE;
        final float r = RADIUS_DP * density;
        final float y = CENTER_Y_DP * density;
        final float inset = END_INSET_DP * density;
        final float near = rtl ? getWidth() - inset : inset;
        final float far = rtl ? inset : getWidth() - inset;
        final float start = Math.min(near, far) + r + 2 * density;
        final float end = Math.max(near, far) - r - 2 * density;
        final float mid = (start + end) / 2f;

        line.setColor(AirUi.color(context, live ? R.color.kq_live : R.color.kq_ink_3));
        line.setPathEffect(live ? null : dash);
        if (state == AirConversationModel.LinkState.RELAY)
        {
            final float half = 6 * density;
            canvas.drawLine(start, y, mid - half - 4 * density, y, line);
            canvas.drawLine(mid + half + 4 * density, y, end, y, line);
            final RectF node = new RectF(mid - half, y - half, mid + half, y + half);
            final float tick = getResources().getDimension(R.dimen.kq_radius_tick);
            nodeFill.setColor(AirUi.color(context, R.color.kq_surface));
            nodeStroke.setColor(AirUi.color(context, R.color.kq_live));
            canvas.drawRoundRect(node, tick, tick, nodeFill);
            canvas.drawRoundRect(node, tick, tick, nodeStroke);
            quietText.setColor(AirUi.color(context, R.color.kq_ink_3));
            drawFitted(canvas, context.getString(R.string.air_path_relay), mid, y + 12 * density
                       - quietText.getFontMetrics().ascent, 140 * density, quietText);
        }
        else
        {
            canvas.drawLine(start, y, end, y, line);
        }

        endFill.setColor(AirUi.color(context, R.color.kq_sunken));
        endStroke.setColor(AirUi.color(context, R.color.kq_line_strong));
        for (float x : new float[]{near, far})
        {
            canvas.drawCircle(x, y, r, endFill);
            canvas.drawCircle(x, y, r, endStroke);
        }

        strongText.setColor(AirUi.color(context, R.color.kq_ink_2));
        final float centerBaseline = y - (strongText.descent() + strongText.ascent()) / 2f;
        drawFitted(canvas, context.getString(R.string.air_you), near, centerBaseline, 2 * r - 4 * density, strongText);
        drawFitted(canvas, initials, far, centerBaseline, 2 * r - 4 * density, strongText);

        quietText.setColor(AirUi.color(context, R.color.kq_ink_2));
        final float nameWidth = Math.min(getWidth() / 2f, 160 * density);
        float nameCenter = far;
        nameCenter = Math.max(nameWidth / 2f, Math.min(getWidth() - nameWidth / 2f, nameCenter));
        drawFitted(canvas, name, nameCenter, y + r + 4 * density - quietText.getFontMetrics().ascent, nameWidth,
                   quietText);
    }

    private static void drawFitted(final Canvas canvas, final String text, final float centerX, final float baseline,
                                   final float maxWidth, final TextPaint paint)
    {
        if (TextUtils.isEmpty(text))
        {
            return;
        }
        // As iOS: the label first shrinks to 70% of its size, and only then loses its end.
        final float size = paint.getTextSize();
        final float width = paint.measureText(text);
        if (width > maxWidth)
        {
            paint.setTextSize(size * Math.max(0.7f, maxWidth / width));
        }
        final CharSequence fitted = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END);
        canvas.drawText(fitted, 0, fitted.length(), centerX, baseline, paint);
        paint.setTextSize(size);
    }
}

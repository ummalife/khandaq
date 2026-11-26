package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * KHANDAQ (Khandaq Testers 8264): the photo of a send under the finger. One finger draws a line in the colour
 * chosen; words put on the photo are moved by dragging them. The marks are kept in the photo's own pixels
 * (PhotoMarkupMath), and {@link #render()} draws them on a copy of the photo: the photo itself is never drawn on.
 */
public class PhotoMarkupView extends View
{
    interface Listener
    {
        void onMarksChanged(int count);
    }

    private abstract static class Mark
    {
        final int color;

        Mark(final int color)
        {
            this.color = color;
        }

        abstract void draw(Canvas canvas, Paint paint);
    }

    private static final class Stroke extends Mark
    {
        final Path path = new Path();
        final float width;
        float lastX;
        float lastY;
        boolean moved = false;

        Stroke(final int color, final float width, final float x, final float y)
        {
            super(color);
            this.width = width;
            path.moveTo(x, y);
            lastX = x;
            lastY = y;
        }

        void to(final float x, final float y)
        {
            // Through the middle of each step: a smooth line however fast the finger goes.
            path.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f);
            lastX = x;
            lastY = y;
            moved = true;
        }

        void end()
        {
            // A tap leaves a dot: a line too short to see is drawn with round caps.
            path.lineTo(moved ? lastX : lastX + 0.01f, lastY);
        }

        @Override
        void draw(final Canvas canvas, final Paint paint)
        {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(width);
            paint.setColor(color);
            canvas.drawPath(path, paint);
        }
    }

    private static final class Label extends Mark
    {
        final String text;
        final float size;
        float x;
        float y;

        Label(final int color, final String text, final float size, final float x, final float y)
        {
            super(color);
            this.text = text;
            this.size = size;
            this.x = x;
            this.y = y;
        }

        private void style(final Paint paint)
        {
            paint.reset();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(size);
            paint.setFakeBoldText(true);
            paint.setColor(color);
            // Words stay readable on any photo: a shadow that stands out from their colour.
            final boolean dark = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) < 128_000;
            paint.setShadowLayer(size * 0.08f, 0f, 0f, dark ? 0xCCFFFFFF : 0xCC000000);
        }

        RectF bounds(final Paint paint)
        {
            style(paint);
            final float halfWidth = paint.measureText(text) / 2f;
            final float pad = size * 0.3f;
            return new RectF(x - halfWidth - pad, y - (size / 2f) - pad, x + halfWidth + pad, y + (size / 2f) + pad);
        }

        @Override
        void draw(final Canvas canvas, final Paint paint)
        {
            style(paint);
            final Paint.FontMetrics metrics = paint.getFontMetrics();
            canvas.drawText(text, x, y - ((metrics.ascent + metrics.descent) / 2f), paint);
        }
    }

    private final List<Mark> marks = new ArrayList<>();
    private final Paint markPaint = new Paint();
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Matrix toView = new Matrix();
    private final Matrix toPhoto = new Matrix();
    private final float[] point = new float[2];

    @Nullable
    private Bitmap photo = null;
    private int color = Color.WHITE;
    @Nullable
    private Stroke stroke = null;
    @Nullable
    private Label dragged = null;
    private float dragDx;
    private float dragDy;
    /** A second finger ends the line; nothing more is drawn until every finger is up. */
    private boolean waitingForUp = false;
    @Nullable
    private Listener listener = null;

    public PhotoMarkupView(final Context context)
    {
        super(context);
    }

    public PhotoMarkupView(final Context context, @Nullable final AttributeSet attrs)
    {
        super(context, attrs);
    }

    void setListener(@Nullable final Listener listener)
    {
        this.listener = listener;
    }

    void setPhoto(final Bitmap photo)
    {
        this.photo = photo;
        marks.clear();
        fit();
        invalidate();
        changed();
    }

    boolean hasPhoto()
    {
        return photo != null;
    }

    void setColor(final int color)
    {
        this.color = color;
    }

    int markCount()
    {
        return marks.size();
    }

    void undo()
    {
        if (!marks.isEmpty())
        {
            marks.remove(marks.size() - 1);
            invalidate();
            changed();
        }
    }

    /** Words in the colour chosen, in the middle of the photo, to be dragged where they go. */
    void addLabel(final String text)
    {
        if ((photo == null) || (text == null) || text.trim().isEmpty())
        {
            return;
        }
        final int width = photo.getWidth();
        final int height = photo.getHeight();
        marks.add(new Label(color, text.trim(), PhotoMarkupMath.textSize(width, height), width / 2f, height / 2f));
        invalidate();
        changed();
    }

    /** A new bitmap: the photo with the marks on it (main thread, as the marks are the view's). */
    Bitmap render()
    {
        if (photo == null)
        {
            throw new IllegalStateException("no photo");
        }
        final Bitmap out = photo.copy(Bitmap.Config.ARGB_8888, true);
        if (out == null)
        {
            throw new IllegalStateException("no copy");
        }
        final Canvas canvas = new Canvas(out);
        final Paint paint = new Paint();
        for (final Mark mark : marks)
        {
            mark.draw(canvas, paint);
        }
        return out;
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh)
    {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    private void fit()
    {
        if (photo == null)
        {
            return;
        }
        final float[] fit = PhotoMarkupMath.fit(photo.getWidth(), photo.getHeight(), getWidth(), getHeight());
        toView.setScale(fit[0], fit[0]);
        toView.postTranslate(fit[1], fit[2]);
        toView.invert(toPhoto);
    }

    @Override
    protected void onDraw(final Canvas canvas)
    {
        super.onDraw(canvas);
        if (photo == null)
        {
            return;
        }
        canvas.save();
        canvas.concat(toView);
        canvas.clipRect(0, 0, photo.getWidth(), photo.getHeight());
        canvas.drawBitmap(photo, 0, 0, photoPaint);
        for (final Mark mark : marks)
        {
            mark.draw(canvas, markPaint);
        }
        if (stroke != null)
        {
            stroke.draw(canvas, markPaint);
        }
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event)
    {
        if ((photo == null) || !isEnabled())
        {
            return false;
        }
        switch (event.getActionMasked())
        {
            case MotionEvent.ACTION_DOWN:
            {
                waitingForUp = false;
                final float[] at = toPhoto(event.getX(), event.getY());
                dragged = labelAt(at[0], at[1]);
                if (dragged != null)
                {
                    dragDx = dragged.x - at[0];
                    dragDy = dragged.y - at[1];
                }
                else
                {
                    stroke = new Stroke(color, PhotoMarkupMath.brushWidth(photo.getWidth(), photo.getHeight()),
                                        at[0], at[1]);
                }
                final ViewParent parent = getParent();
                if (parent != null)
                {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (waitingForUp)
                {
                    return true;
                }
                if (dragged != null)
                {
                    final float[] at = toPhoto(event.getX(), event.getY());
                    dragged.x = PhotoMarkupMath.clamp(at[0] + dragDx, 0f, photo.getWidth());
                    dragged.y = PhotoMarkupMath.clamp(at[1] + dragDy, 0f, photo.getHeight());
                }
                else if (stroke != null)
                {
                    for (int i = 0; i < event.getHistorySize(); i++)
                    {
                        final float[] at = toPhoto(event.getHistoricalX(i), event.getHistoricalY(i));
                        stroke.to(at[0], at[1]);
                    }
                    final float[] at = toPhoto(event.getX(), event.getY());
                    stroke.to(at[0], at[1]);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                keepStroke();
                dragged = null;
                waitingForUp = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!waitingForUp)
                {
                    keepStroke();
                }
                stroke = null;
                dragged = null;
                waitingForUp = false;
                performClick();
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                stroke = null;
                dragged = null;
                waitingForUp = false;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick()
    {
        return super.performClick();
    }

    private void keepStroke()
    {
        if (stroke != null)
        {
            stroke.end();
            marks.add(stroke);
            stroke = null;
            changed();
        }
    }

    private float[] toPhoto(final float x, final float y)
    {
        point[0] = x;
        point[1] = y;
        toPhoto.mapPoints(point);
        return new float[]{point[0], point[1]};
    }

    /** The words under the finger, the ones put last first, as they are drawn over the others. */
    @Nullable
    private Label labelAt(final float x, final float y)
    {
        for (int i = marks.size() - 1; i >= 0; i--)
        {
            final Mark mark = marks.get(i);
            if ((mark instanceof Label) && ((Label) mark).bounds(markPaint).contains(x, y))
            {
                return (Label) mark;
            }
        }
        return null;
    }

    private void changed()
    {
        if (listener != null)
        {
            listener.onMarksChanged(marks.size());
        }
    }
}

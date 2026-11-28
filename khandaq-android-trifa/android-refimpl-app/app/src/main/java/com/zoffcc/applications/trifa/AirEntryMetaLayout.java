package com.zoffcc.applications.trifa;

import android.content.Context;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (owner, 28.11.2026): the meta of an entry of the transcript, the "edited" word, the time and the mark of
 * our own, at the trailing edge of the entry instead of a time column at its start and a mark column at its end.
 * The meta sits on the last line of the text, on its baseline, when the trailing side of that line has room for
 * {@link #GAP_TO_TEXT_DP} and the meta; otherwise it takes a line of its own right under the text, still at the
 * trailing edge. The room is read from the text's own laid-out line (android.text.Layout), never counted in
 * characters: a right-to-left paragraph in a left-to-right screen ends at the right edge, so its meta goes under
 * it, and the mirror case in a right-to-left screen. An entry without text (a file, a voice note, a photo without
 * a caption, a call row) narrows its body by the meta and stands the meta beside it, level with its bottom.
 *
 * <p>The children are the body, then the meta pieces, found by id: {@code air_entry_edited}, {@code date_time},
 * {@code m_icon}. The body is the text itself, or a view whose last line may be a text of the entry
 * ({@link #setTailText}, the caption of a photo). It extends LinearLayout only so that its children keep the
 * LinearLayout.LayoutParams the row holders cast to; it measures and places them itself.
 */
public class AirEntryMetaLayout extends LinearLayout
{
    /** Between the end of the text, or the body, and the meta. */
    static final int GAP_TO_TEXT_DP = 8;
    /** Between the pieces of the meta: the edited word, the time and the mark. */
    static final int GAP_IN_META_DP = 4;

    @Nullable
    private TextView tailText;
    private boolean textBody = true;

    // What the last measure decided, for the layout pass after it.
    private View body;
    private TextView edited;
    private TextView time;
    private View mark;
    @Nullable
    private TextView measuredTail;
    private boolean metaOnLastLine;
    private int metaWidth;
    private int metaAbove;
    private int metaBelow;
    private int topShift;

    public AirEntryMetaLayout(final Context context)
    {
        super(context);
        setOrientation(VERTICAL);
    }

    public AirEntryMetaLayout(final Context context, @Nullable final AttributeSet attrs)
    {
        super(context, attrs);
        setOrientation(VERTICAL);
    }

    public AirEntryMetaLayout(final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
    }

    /**
     * A text inside the body whose last line is the bottom of the body (the caption under a photo): while it shows,
     * the meta follows its last line as it follows the text of a text entry, and the body keeps the whole width.
     */
    void setTailText(@Nullable final TextView text)
    {
        if (tailText != text)
        {
            tailText = text;
            requestLayout();
        }
    }

    /**
     * Whether a body that is a TextView reads as a text, the meta following its last line (the default), or as a
     * block the meta stands beside, as a file card: the call row, whose words are the label of the call.
     */
    void setTextBody(final boolean text)
    {
        if (textBody != text)
        {
            textBody = text;
            requestLayout();
        }
    }

    /** Whether the last layout pass put the meta on the last line of the text (for tests and the screenshots). */
    boolean isMetaOnLastLine()
    {
        return metaOnLastLine;
    }

    /** Whether the meta stands on a line of its own under a text. */
    boolean isMetaOnOwnLine()
    {
        return measuredTail != null && !metaOnLastLine;
    }

    // ---- the rule ----

    /**
     * Whether a meta {@code metaWidth} wide fits on the last line of {@code layout} with the gap before it: the
     * room between the line and the trailing edge of the text's box ({@code boxWidth} wide, the layout starting
     * {@code layoutLeft} into it), the right edge in a left-to-right screen and the left one in a right-to-left one.
     */
    static boolean fitsOnLastLine(@Nullable final Layout layout, final int layoutLeft, final int boxWidth,
                                  final boolean rtlScreen, final int metaWidth, final int gap)
    {
        if (layout == null || layout.getLineCount() == 0)
        {
            return false;
        }
        final int last = layout.getLineCount() - 1;
        final float room = rtlScreen ? layoutLeft + layout.getLineLeft(last)
                                     : boxWidth - (layoutLeft + layout.getLineRight(last));
        return room >= gap + metaWidth;
    }

    // ---- measuring ----

    private void findChildren()
    {
        body = null;
        edited = null;
        time = null;
        mark = null;
        for (int i = 0; i < getChildCount(); i++)
        {
            final View child = getChildAt(i);
            final int id = child.getId();
            if (id == R.id.air_entry_edited && child instanceof TextView)
            {
                edited = (TextView) child;
            }
            else if (id == R.id.date_time && child instanceof TextView)
            {
                time = (TextView) child;
            }
            else if (id == R.id.m_icon)
            {
                mark = child;
            }
            else if (body == null)
            {
                body = child;
            }
        }
    }

    private static boolean shown(@Nullable final View view)
    {
        return view != null && view.getVisibility() != GONE;
    }

    /** The text the meta follows: the body when it is one, else the tail text while it shows inside the body. */
    @Nullable
    private TextView tail()
    {
        if (!shown(body))
        {
            return null;
        }
        if (body instanceof TextView)
        {
            return textBody ? (TextView) body : null;
        }
        if (tailText == null || tailText.getVisibility() != VISIBLE)
        {
            return null;
        }
        // Only a text inside the body, every view between them shown.
        ViewParent parent = tailText.getParent();
        while (parent instanceof View)
        {
            if (parent == body)
            {
                return tailText;
            }
            if (((View) parent).getVisibility() != VISIBLE)
            {
                return null;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private int dp(final int dp)
    {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
    {
        findChildren();
        if (body == null)
        {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        final int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        // The meta: the pieces in a row, 4 apart, and how far it reaches above and below the time's baseline.
        metaWidth = 0;
        int pieces = 0;
        for (final View piece : new View[]{edited, time, mark})
        {
            if (shown(piece))
            {
                // As the layout sizes them: the words as wide as they are, the mark at its own size.
                measureChild(piece, widthMeasureSpec, unspecified);
                metaWidth += piece.getMeasuredWidth();
                pieces++;
            }
        }
        if (pieces > 1)
        {
            metaWidth += (pieces - 1) * dp(GAP_IN_META_DP);
        }
        measureExtents();
        final int gap = dp(GAP_TO_TEXT_DP);

        measuredTail = null;
        metaOnLastLine = false;
        topShift = 0;
        final boolean metaShown = pieces > 0;
        int width;
        int height;
        if (!shown(body))
        {
            width = metaWidth;
            height = metaShown ? metaAbove + metaBelow : 0;
        }
        else
        {
            final TextView tail = tail();
            final MarginLayoutParams lp = (MarginLayoutParams) body.getLayoutParams();
            final int horizontalMargins = lp.leftMargin + lp.rightMargin;
            final int verticalMargins = lp.topMargin + lp.bottomMargin;
            if (tail != null)
            {
                // A text: the whole width, the meta on its last line or under it.
                measureChildWithMargins(body, widthMeasureSpec, 0, heightMeasureSpec, 0);
                measuredTail = tail;
                final int bodyHeight = body.getMeasuredHeight() + verticalMargins;
                width = Math.max(body.getMeasuredWidth() + horizontalMargins, metaWidth);
                metaOnLastLine = metaShown && fitsOnLastLine(tail.getLayout(), tail.getCompoundPaddingLeft(),
                                                             tail.getMeasuredWidth(), isRtl(), metaWidth, gap);
                if (!metaShown)
                {
                    height = bodyHeight;
                }
                else if (metaOnLastLine)
                {
                    // The text's last line ends the body: the baseline is this far above the body's bottom.
                    final int baselineY = bodyHeight - lp.bottomMargin - belowLastBaseline(tail);
                    final int metaTop = baselineY - metaAbove;
                    topShift = Math.max(0, -metaTop);
                    height = Math.max(bodyHeight, baselineY + metaBelow) + topShift;
                }
                else
                {
                    height = bodyHeight + metaAbove + metaBelow;
                }
            }
            else
            {
                // Not a text: the body never runs under the meta, which stands beside it, level with its bottom.
                final int room = metaShown ? metaWidth + gap : 0;
                measureChildWithMargins(body, widthMeasureSpec, room, heightMeasureSpec, 0);
                width = body.getMeasuredWidth() + horizontalMargins + room;
                height = Math.max(body.getMeasuredHeight() + verticalMargins, metaShown ? metaAbove + metaBelow : 0);
            }
        }
        width += getPaddingLeft() + getPaddingRight();
        height += getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(resolveSize(Math.max(width, getSuggestedMinimumWidth()), widthMeasureSpec),
                             resolveSize(Math.max(height, getSuggestedMinimumHeight()), heightMeasureSpec));
    }

    /** From the baseline of the text's last line down to the bottom of its view. */
    private static int belowLastBaseline(final TextView text)
    {
        final Layout layout = text.getLayout();
        if (layout == null || layout.getLineCount() == 0)
        {
            return 0;
        }
        final int baseline = text.getTotalPaddingTop() + layout.getLineBaseline(layout.getLineCount() - 1);
        return Math.max(0, text.getMeasuredHeight() - baseline);
    }

    /**
     * How far the meta reaches above and below the baseline of its time: the words stand on the baseline, the mark
     * is centred on the line of the time. Without words the mark stands on the baseline.
     */
    private void measureExtents()
    {
        int top = 0;
        int bottom = 0;
        boolean any = false;
        for (final TextView words : new TextView[]{edited, time})
        {
            if (shown(words))
            {
                final int pieceTop = -baselineOf(words);
                top = any ? Math.min(top, pieceTop) : pieceTop;
                bottom = any ? Math.max(bottom, pieceTop + words.getMeasuredHeight())
                             : pieceTop + words.getMeasuredHeight();
                any = true;
            }
        }
        if (shown(mark))
        {
            final int markTop = markTop(0);
            top = any ? Math.min(top, markTop) : markTop;
            bottom = any ? Math.max(bottom, markTop + mark.getMeasuredHeight()) : markTop + mark.getMeasuredHeight();
        }
        metaAbove = Math.max(0, -top);
        metaBelow = Math.max(0, bottom);
    }

    private static int baselineOf(final TextView words)
    {
        final int baseline = words.getBaseline();
        return baseline >= 0 ? baseline : words.getMeasuredHeight();
    }

    /** The top of the mark for a baseline at {@code baselineY}: centred on the time's line, else on the baseline. */
    private int markTop(final int baselineY)
    {
        if (shown(time))
        {
            final int timeTop = baselineY - baselineOf(time);
            return timeTop + (time.getMeasuredHeight() - mark.getMeasuredHeight()) / 2;
        }
        return baselineY - mark.getMeasuredHeight();
    }

    private boolean isRtl()
    {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
    }

    // ---- placing ----

    @Override
    protected void onLayout(final boolean changed, final int l, final int t, final int r, final int b)
    {
        if (body == null)
        {
            super.onLayout(changed, l, t, r, b);
            return;
        }
        final int width = r - l;
        final boolean rtl = isRtl();
        final int innerLeft = getPaddingLeft();
        final int innerRight = width - getPaddingRight();
        final int innerTop = getPaddingTop() + topShift;
        final boolean metaShown = shown(edited) || shown(time) || shown(mark);

        int bodyBottom = innerTop;
        if (shown(body))
        {
            final MarginLayoutParams lp = (MarginLayoutParams) body.getLayoutParams();
            final int bodyWidth = body.getMeasuredWidth();
            final int bodyLeft = rtl ? innerRight - lp.rightMargin - bodyWidth : innerLeft + lp.leftMargin;
            final int bodyTop = innerTop + lp.topMargin;
            body.layout(bodyLeft, bodyTop, bodyLeft + bodyWidth, bodyTop + body.getMeasuredHeight());
            bodyBottom = bodyTop + body.getMeasuredHeight();
        }
        if (!metaShown)
        {
            return;
        }

        final int baselineY;
        final TextView tail = measuredTail;
        if (tail != null && shown(body))
        {
            final int tailTop = topIn(tail);
            final Layout layout = tail.getLayout();
            if (metaOnLastLine && layout != null && layout.getLineCount() > 0)
            {
                baselineY = tailTop + tail.getTotalPaddingTop() + layout.getLineBaseline(layout.getLineCount() - 1);
            }
            else
            {
                // A line of its own, as tall as the meta, right under the text.
                baselineY = tailTop + tail.getHeight() + metaAbove;
            }
        }
        else
        {
            // Level with the bottom of the body, or of the meta when it is the taller.
            baselineY = Math.max(bodyBottom, innerTop + metaAbove + metaBelow) - metaBelow;
        }
        placeMeta(rtl, rtl ? innerLeft : innerRight, baselineY);
    }

    /** The pieces in reading order from the trailing edge inwards: the mark at the edge, then the time, then edited. */
    private void placeMeta(final boolean rtl, final int edge, final int baselineY)
    {
        final int gap = dp(GAP_IN_META_DP);
        int x = edge;
        boolean first = true;
        for (final View piece : new View[]{mark, time, edited})
        {
            if (!shown(piece))
            {
                continue;
            }
            if (!first)
            {
                x = rtl ? x + gap : x - gap;
            }
            first = false;
            final int pieceWidth = piece.getMeasuredWidth();
            final int top = piece == mark ? markTop(baselineY) : baselineY - baselineOf((TextView) piece);
            final int left = rtl ? x : x - pieceWidth;
            piece.layout(left, top, left + pieceWidth, top + piece.getMeasuredHeight());
            x = rtl ? x + pieceWidth : x - pieceWidth;
        }
    }

    /** The top of {@code view} in this layout's coordinates, through the views between them. */
    private int topIn(final View view)
    {
        int top = 0;
        View at = view;
        while (at != null && at != this)
        {
            top += at.getTop();
            final ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }
        return top;
    }
}

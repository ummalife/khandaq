/**
 * Telegram-style text bubble: timestamp + delivery icon at bottom-right,
 * inline on the last line when there is room, otherwise on a separate row below.
 */
package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.luseen.autolinklibrary.EmojiTextViewLinks;

import androidx.annotation.Nullable;

public class ChatBubbleMetaLayout extends FrameLayout
{
    private static final float INLINE_SLOP_DP = 4f;

    private EmojiTextViewLinks textView;
    private View metaRow;
    private boolean metaVisible = true;
    private boolean stacked;

    public ChatBubbleMetaLayout(final Context context)
    {
        super(context);
    }

    public ChatBubbleMetaLayout(final Context context, @Nullable final AttributeSet attrs)
    {
        super(context, attrs);
    }

    public ChatBubbleMetaLayout(final Context context, @Nullable final AttributeSet attrs, final int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onFinishInflate()
    {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++)
        {
            final View child = getChildAt(i);
            if (child instanceof EmojiTextViewLinks)
            {
                textView = (EmojiTextViewLinks) child;
            }
            else if (child.getId() == R.id.bubble_meta_row)
            {
                metaRow = child;
            }
        }
        if (textView != null)
        {
            textView.addOnLayoutChangeListener(new OnLayoutChangeListener()
            {
                @Override
                public void onLayoutChange(final View v, final int left, final int top, final int right,
                                           final int bottom, final int oldLeft, final int oldTop,
                                           final int oldRight, final int oldBottom)
                {
                    requestLayout();
                }
            });
        }
    }

    void setMetaVisible(final boolean visible)
    {
        metaVisible = visible;
        if (metaRow != null)
        {
            metaRow.setVisibility(visible ? VISIBLE : GONE);
        }
        requestLayout();
    }

    void scheduleMetaLayout()
    {
        post(new Runnable()
        {
            @Override
            public void run()
            {
                requestLayout();
            }
        });
    }

    private int contentMaxWidth(final int widthMeasureSpec)
    {
        int maxW = (int) (280f * getResources().getDisplayMetrics().density);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST)
        {
            maxW = Math.min(maxW, MeasureSpec.getSize(widthMeasureSpec));
        }
        else if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY)
        {
            maxW = Math.min(maxW, MeasureSpec.getSize(widthMeasureSpec));
        }
        return maxW;
    }

    private static float lastLineWidth(final Layout layout)
    {
        if (layout == null || layout.getLineCount() == 0)
        {
            return 0f;
        }
        return layout.getLineWidth(layout.getLineCount() - 1);
    }

    private boolean needsStackedLayout(final Layout layout, final int metaWidth, final int textWidth)
    {
        if (!metaVisible || metaRow == null || metaRow.getVisibility() != VISIBLE)
        {
            return false;
        }
        if (layout == null || layout.getLineCount() == 0)
        {
            return false;
        }
        final float slop = INLINE_SLOP_DP * getResources().getDisplayMetrics().density;
        return lastLineWidth(layout) + metaWidth + slop > textWidth;
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
    {
        if (textView == null)
        {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        final int maxContentWidth = contentMaxWidth(widthMeasureSpec);
        final int metaWidthSpec = MeasureSpec.makeMeasureSpec(maxContentWidth, MeasureSpec.AT_MOST);
        final int heightUnspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        int metaW = 0;
        int metaH = 0;
        if (metaRow != null && metaVisible && metaRow.getVisibility() == VISIBLE)
        {
            measureChildWithMargins(metaRow, metaWidthSpec, 0, heightUnspec, 0);
            metaW = metaRow.getMeasuredWidth();
            metaH = metaRow.getMeasuredHeight();
        }

        final int textWidthSpec = MeasureSpec.makeMeasureSpec(maxContentWidth, MeasureSpec.AT_MOST);
        measureChildWithMargins(textView, textWidthSpec, 0, heightUnspec, 0);

        final Layout layout = textView.getLayout();
        final int textW = textView.getMeasuredWidth();
        final int textH = textView.getMeasuredHeight();

        stacked = needsStackedLayout(layout, metaW, textW);

        int totalW = textW;
        int totalH = textH;
        if (metaVisible && metaRow != null && metaRow.getVisibility() == VISIBLE)
        {
            if (stacked)
            {
                totalW = Math.max(textW, metaW);
                totalH = textH + metaH;
            }
            else
            {
                totalW = Math.max(textW, (int) (lastLineWidth(layout) + metaW));
            }
        }

        setMeasuredDimension(
                resolveSize(totalW, widthMeasureSpec),
                resolveSize(totalH, heightMeasureSpec));
    }

    @Override
    protected void onLayout(final boolean changed, final int left, final int top, final int right, final int bottom)
    {
        if (textView == null)
        {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }

        final int textW = textView.getMeasuredWidth();
        final int textH = textView.getMeasuredHeight();
        layoutTextChild(textView, 0, 0, textW, textH);

        if (metaRow == null || !metaVisible || metaRow.getVisibility() != VISIBLE)
        {
            return;
        }

        final int metaW = metaRow.getMeasuredWidth();
        final int metaH = metaRow.getMeasuredHeight();
        final int containerW = getMeasuredWidth();
        final int metaLeft = containerW - metaW;

        if (stacked)
        {
            layoutTextChild(metaRow, metaLeft, textH, metaLeft + metaW, textH + metaH);
        }
        else
        {
            final Layout layout = textView.getLayout();
            int metaTop = textH - metaH;
            if (layout != null && layout.getLineCount() > 0)
            {
                final int lastLine = layout.getLineCount() - 1;
                metaTop = textView.getTop() + layout.getLineBottom(lastLine) - metaH;
            }
            layoutTextChild(metaRow, metaLeft, metaTop, metaLeft + metaW, metaTop + metaH);
        }
    }

    private static void layoutTextChild(final View child, final int l, final int t, final int r, final int b)
    {
        final ViewGroup.LayoutParams raw = child.getLayoutParams();
        int leftMargin = 0;
        int topMargin = 0;
        int rightMargin = 0;
        int bottomMargin = 0;
        if (raw instanceof MarginLayoutParams)
        {
            final MarginLayoutParams lp = (MarginLayoutParams) raw;
            leftMargin = lp.leftMargin;
            topMargin = lp.topMargin;
            rightMargin = lp.rightMargin;
            bottomMargin = lp.bottomMargin;
        }
        child.layout(l + leftMargin, t + topMargin, r - rightMargin, b - bottomMargin);
    }
}

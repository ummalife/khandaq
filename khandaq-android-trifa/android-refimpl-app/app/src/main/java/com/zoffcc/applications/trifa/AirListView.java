package com.zoffcc.applications.trifa;

import android.content.Context;
import android.util.AttributeSet;

import androidx.recyclerview.widget.RecyclerView;

/**
 * KHANDAQ (0.2.57): Air's list. Its scrollbar wakes when the list comes into view and when it scrolls,
 * not on every refresh.
 *
 * <p>View.onVisibilityAggregated wakes a view's scrollbars for 1.6 s each time the view is reported
 * visible, not only when it becomes visible, and ViewGroup.attachViewToParent sends that report for the
 * list each time a row is attached back, which LinearLayoutManager does for every row on every layout
 * pass. Air lays out again on each refresh (a contact's state, a new message), so the strip at the right
 * edge flashed with nothing scrolled, or stayed up while refreshes came about once a second.
 */
final class AirListView extends RecyclerView
{
    /** What the last visibility report said: only a change to visible may wake the scrollbar. */
    private boolean visible;
    private boolean scrollbarsQuiet;

    public AirListView(final Context context)
    {
        super(context);
    }

    public AirListView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
    }

    @Override
    public void onVisibilityAggregated(final boolean isVisible)
    {
        scrollbarsQuiet = !comesIntoView(visible, isVisible);
        visible = isVisible;
        try
        {
            super.onVisibilityAggregated(isVisible);
        }
        finally
        {
            scrollbarsQuiet = false;
        }
    }

    @Override
    protected boolean awakenScrollBars(final int startDelay, final boolean invalidate)
    {
        return !scrollbarsQuiet && super.awakenScrollBars(startDelay, invalidate);
    }

    /** Whether a visibility report is the list coming into view, rather than a repeat while it stays in view. */
    static boolean comesIntoView(final boolean wasVisible, final boolean isVisible)
    {
        return isVisible && !wasVisible;
    }

    /**
     * Whether rows that just landed are put back at the top: a list at its top when they were handed over
     * stays there, unless it was scrolled away while they were being compared.
     */
    static boolean keepsTop(final boolean wasAtTop, final boolean isAtTop)
    {
        return wasAtTop && isAtTop;
    }
}

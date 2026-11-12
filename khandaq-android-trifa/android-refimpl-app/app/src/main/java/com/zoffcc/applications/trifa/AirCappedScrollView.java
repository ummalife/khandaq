package com.zoffcc.applications.trifa;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.core.widget.NestedScrollView;

/**
 * KHANDAQ (redesign, stage 2): a scroll view as tall as its content up to a cap, then scrolling, as the
 * tray's list (at most 28% of the screen) and the dossier (the conversation keeps 64dp below it) are on
 * iOS. Without a cap it is a plain NestedScrollView.
 *
 * <p>It can also report a swipe up made at the end of its content, which closes the dossier on iOS.
 */
public final class AirCappedScrollView extends NestedScrollView
{
    /** A swipe up: at least this far (dp) and this fast (dp per second), as the dossier's grip on iOS. */
    private static final float SWIPE_DISTANCE_DP = 16f;
    private static final float SWIPE_SPEED_DP = 300f;

    private int maxHeightPx = 0;
    private Runnable onSwipeUpAtEnd = null;
    private VelocityTracker tracker = null;
    private float downY = 0f;
    /**
     * The content was at its end when the finger went down. A swipe that first scrolls to the end only
     * reads the content; as on iOS, where the swipe is recognised early in the gesture, it does not close.
     */
    private boolean atEndOnDown = false;

    public AirCappedScrollView(final Context context)
    {
        super(context);
    }

    public AirCappedScrollView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
    }

    public AirCappedScrollView(final Context context, final AttributeSet attrs, final int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
    }

    /** 0 lifts the cap. */
    void setMaxHeight(final int px)
    {
        final int next = Math.max(0, px);
        if (next != maxHeightPx)
        {
            maxHeightPx = next;
            requestLayout();
        }
    }

    /** Runs when a swipe up ends with the content at its end; null stops listening. */
    void setOnSwipeUpAtEnd(final Runnable action)
    {
        onSwipeUpAtEnd = action;
        if (action == null)
        {
            recycleTracker();
        }
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event)
    {
        if (onSwipeUpAtEnd != null)
        {
            track(event);
        }
        return super.dispatchTouchEvent(event);
    }

    private void track(final MotionEvent event)
    {
        switch (event.getActionMasked())
        {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                atEndOnDown = !canScrollVertically(1);
                if (tracker == null)
                {
                    tracker = VelocityTracker.obtain();
                }
                else
                {
                    tracker.clear();
                }
                tracker.addMovement(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (tracker != null)
                {
                    tracker.addMovement(event);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (tracker != null)
                {
                    tracker.addMovement(event);
                    tracker.computeCurrentVelocity(1000);
                    final float density = getResources().getDisplayMetrics().density;
                    final boolean swipe = event.getY() - downY < -SWIPE_DISTANCE_DP * density
                                          && tracker.getYVelocity() < -SWIPE_SPEED_DP * density;
                    final Runnable action = onSwipeUpAtEnd;
                    if (swipe && atEndOnDown && !canScrollVertically(1) && action != null)
                    {
                        post(action);
                    }
                }
                recycleTracker();
                break;
            case MotionEvent.ACTION_CANCEL:
                recycleTracker();
                break;
            default:
                break;
        }
    }

    private void recycleTracker()
    {
        if (tracker != null)
        {
            tracker.recycle();
            tracker = null;
        }
    }

    @Override
    protected void onDetachedFromWindow()
    {
        recycleTracker();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
    {
        int spec = heightMeasureSpec;
        if (maxHeightPx > 0)
        {
            final int mode = MeasureSpec.getMode(heightMeasureSpec);
            final int size = MeasureSpec.getSize(heightMeasureSpec);
            final int cap = mode == MeasureSpec.UNSPECIFIED ? maxHeightPx : Math.min(size, maxHeightPx);
            spec = MeasureSpec.makeMeasureSpec(cap, mode == MeasureSpec.EXACTLY ? MeasureSpec.EXACTLY
                                                                               : MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthMeasureSpec, spec);
    }
}

/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * <p>
 * KHANDAQ (#209): Telegram-style swipe-to-reply for the message RecyclerViews.
 * A single ItemTouchHelper callback attached at the list level covers every row type
 * (text/file/photo/video/voice, incoming + outgoing) without per-holder wiring. The swipe
 * never "commits" (getSwipeThreshold is huge) — instead we rubber-band-translate the row,
 * latch when it passes the trigger distance while the finger is down, and fire the reply on
 * release via clearView(). Vertical scrolling is untouched (0 drag dirs, RIGHT swipe only).
 */

package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Canvas;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

public class ReplySwipeCallback extends ItemTouchHelper.SimpleCallback
{
    public interface Trigger
    {
        // true when the row at this adapter position is a real, replyable message
        boolean isSwipeable(int position);

        // start the reply compose bar for the row at this position
        void onReply(int position);
    }

    private final Trigger trigger;
    private final float maxDx;      // how far the bubble can slide
    private final float triggerDx;  // distance past which release triggers the reply
    // KHANDAQ (#T3): latch only "triggered while the finger was down" — the actual target row is
    // resolved from the swiped ViewHolder in clearView(), so a list rebuild during the ~200ms settle
    // (incoming msg / remote delete-for-both / re-sort) can't make us quote a stale position.
    private boolean latchedTriggered = false;

    public ReplySwipeCallback(Context context, Trigger trigger)
    {
        super(0, ItemTouchHelper.RIGHT);
        this.trigger = trigger;
        final float density = context.getResources().getDisplayMetrics().density;
        this.maxDx = density * 72f;
        this.triggerDx = density * 56f;
    }

    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target)
    {
        return false;
    }

    @Override
    public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder)
    {
        final int pos = viewHolder.getBindingAdapterPosition();
        if (pos == RecyclerView.NO_POSITION || !trigger.isSwipeable(pos))
        {
            return 0;
        }
        return super.getSwipeDirs(recyclerView, viewHolder);
    }

    @Override
    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder)
    {
        // never let RecyclerView "commit" the swipe (which would animate the row off-screen)
        return 1000f;
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction)
    {
        // unreachable because getSwipeThreshold is huge; the reply fires from clearView()
    }

    @Override
    public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive)
    {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE)
        {
            final float clamped = Math.max(0f, Math.min(dX, maxDx));
            // Only update the latch while the finger is actually down. During the settle-back
            // animation isCurrentlyActive is false and dX shrinks — updating there would clear
            // the latch before clearView() runs.
            if (isCurrentlyActive)
            {
                latchedTriggered = (clamped >= triggerDx);
            }
            super.onChildDraw(c, recyclerView, viewHolder, clamped, dY, actionState, isCurrentlyActive);
        }
        else
        {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }
    }

    @Override
    public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder)
    {
        super.clearView(recyclerView, viewHolder);
        if (latchedTriggered)
        {
            latchedTriggered = false;
            // Resolve the target from the SWIPED holder now (post-settle), so a mid-swipe list rebuild
            // can't misdirect the reply. NO_POSITION (row removed meanwhile) → silently no-op.
            final int pos = viewHolder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && trigger.isSwipeable(pos))
            {
                try
                {
                    trigger.onReply(pos);
                }
                catch (Exception ignored)
                {
                }
            }
        }
    }
}

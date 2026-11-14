/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * <p>
 * KHANDAQ (#209): Telegram-style swipe-to-reply for the message RecyclerViews.
 * A single ItemTouchHelper callback attached at the list level covers every row type
 * (text/file/photo/video/voice, incoming + outgoing) without per-holder wiring. The swipe
 * never "commits" (getSwipeThreshold is huge) — instead we rubber-band-translate the row,
 * latch when it passes the trigger distance while the finger is down, and fire the reply on
 * release via clearView(). Vertical scrolling is untouched (0 drag dirs, one swipe direction).
 * KHANDAQ (0.2.56): the row goes toward the start edge, as in Telegram: to the left, and to the
 * right in a right-to-left layout (ItemTouchHelper.START). It went to the right before. A reply
 * badge (the chat date pill colours) grows in the space the row leaves, and a light haptic tick
 * marks the point past which releasing replies.
 */

package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.mikepenz.google_material_typeface_library.GoogleMaterial;
import com.mikepenz.iconics.IconicsDrawable;

import org.khandaq.messenger.R;

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
    private final float badgeRadius;
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int badgeAlpha;
    private final IconicsDrawable replyIcon;
    // KHANDAQ (#T3): latch only "triggered while the finger was down" — the actual target row is
    // resolved from the swiped ViewHolder in clearView(), so a list rebuild during the ~200ms settle
    // (incoming msg / remote delete-for-both / re-sort) can't make us quote a stale position.
    private boolean latchedTriggered = false;

    public ReplySwipeCallback(Context context, Trigger trigger)
    {
        super(0, ItemTouchHelper.START);
        this.trigger = trigger;
        final float density = context.getResources().getDisplayMetrics().density;
        this.maxDx = density * 72f;
        this.triggerDx = density * 56f;
        this.badgeRadius = density * 16f;
        final int badgeColor = ContextCompat.getColor(context, R.color.tg_date_separator_bg);
        this.badgePaint.setColor(badgeColor);
        this.badgeAlpha = Color.alpha(badgeColor);
        this.replyIcon = new IconicsDrawable(context).icon(GoogleMaterial.Icon.gmd_reply).color(
                ContextCompat.getColor(context, R.color.tg_date_separator_text)).sizeDp(20);
        final int half = Math.round(density * 10f);
        this.replyIcon.setBounds(-half, -half, half, half);
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
            final boolean rtl = recyclerView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            final float clamped = replyOffset(dX, maxDx, rtl);
            // Only update the latch while the finger is actually down. During the settle-back
            // animation isCurrentlyActive is false and dX shrinks — updating there would clear
            // the latch before clearView() runs.
            if (isCurrentlyActive)
            {
                final boolean triggered = (Math.abs(clamped) >= triggerDx);
                if (triggered && !latchedTriggered)
                {
                    recyclerView.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                                                       ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                                       : HapticFeedbackConstants.KEYBOARD_TAP);
                }
                latchedTriggered = triggered;
            }
            // Drawn before the row (ItemTouchHelper draws from the list's decoration pass), so it shows
            // only in the strip the row has left at its end edge.
            drawReplyBadge(c, viewHolder.itemView, clamped, rtl);
            super.onChildDraw(c, recyclerView, viewHolder, clamped, dY, actionState, isCurrentlyActive);
        }
        else
        {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }
    }

    /**
     * KHANDAQ (0.2.56): how far the row is drawn for a swipe of dX: only toward the start edge (left, or
     * right when the layout is right-to-left) and at most maxDx; a move the other way leaves it in place.
     */
    static float replyOffset(final float dX, final float maxDx, final boolean rtl)
    {
        if (rtl)
        {
            return Math.max(0f, Math.min(dX, maxDx));
        }
        return Math.min(0f, Math.max(dX, -maxDx));
    }

    /** KHANDAQ (0.2.56): the reply badge's size, 0..1: it grows with the swipe and is full at the reply point. */
    static float replyBadgeProgress(final float offset, final float triggerDx)
    {
        if (triggerDx <= 0f)
        {
            return 1f;
        }
        return Math.min(1f, Math.abs(offset) / triggerDx);
    }

    private void drawReplyBadge(final Canvas c, final View row, final float offset, final boolean rtl)
    {
        final float progress = replyBadgeProgress(offset, triggerDx);
        if (progress <= 0f)
        {
            return;
        }
        // centred in the strip the row has left uncovered: offset is negative left to right, positive right to left
        final float cx = (rtl ? row.getLeft() : row.getRight()) + offset / 2f;
        final float cy = row.getTop() + row.getTranslationY() + row.getHeight() / 2f;
        badgePaint.setAlpha(Math.round(badgeAlpha * progress));
        replyIcon.setAlpha(Math.round(255 * progress));
        final int save = c.save();
        c.translate(cx, cy);
        // the arrow points to the start edge; mirror it for a right-to-left layout
        c.scale(rtl ? -progress : progress, progress);
        c.drawCircle(0f, 0f, badgeRadius, badgePaint);
        replyIcon.draw(c);
        c.restoreToCount(save);
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

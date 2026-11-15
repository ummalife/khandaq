/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * <p>
 * KHANDAQ (#209): Telegram-style swipe-to-reply for the message RecyclerViews.
 * A single ItemTouchHelper callback attached at the list level covers every row type
 * (text/file/photo/video/voice, incoming + outgoing) without per-holder wiring. The swipe
 * never "commits" (getSwipeThreshold is huge, and since 0.2.56 so is getSwipeEscapeVelocity) —
 * instead we rubber-band-translate the row, latch when it passes the trigger distance while the
 * finger is down, and fire the reply on release via clearView(). Vertical scrolling is untouched
 * (0 drag dirs, one swipe direction).
 * KHANDAQ (0.2.56): the row goes toward the start edge, as in Telegram: to the left, and to the
 * right in a right-to-left layout (ItemTouchHelper.START). It went to the right before. A reply
 * badge (the chat date pill colours) grows in the space the row leaves, and a light haptic tick
 * marks the point past which releasing replies.
 * KHANDAQ (0.2.56, QA 15.11): the reply goes to the message the swipe started on, held by its key
 * (see Sessions) wherever the list moves it meanwhile, and to nothing once it is gone. One touch
 * swipes one message. Lists get the swipe through attach().
 */

package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
        // KHANDAQ (0.2.56, QA 15.11): the key (messageKey) of the message at this adapter position when it is
        // a real, replyable message, else null
        Object messageKeyAt(int position);

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
    // KHANDAQ (#T3): latch only "triggered while the finger was down" — the actual target is resolved in
    // clearView(), so a list rebuild during the ~200ms settle (incoming msg / remote delete-for-both / re-sort)
    // can't make us quote a stale position. KHANDAQ (0.2.56, QA 15.11): the latch is per swipe, and the target
    // is the swiped message's key, not the swiped row (see Sessions).
    private final Sessions sessions = new Sessions();
    // KHANDAQ (0.2.56, QA 15.11): tells Sessions where each touch begins; never takes a touch
    private final RecyclerView.OnItemTouchListener touchWatcher = new RecyclerView.SimpleOnItemTouchListener()
    {
        @Override
        public boolean onInterceptTouchEvent(@NonNull final RecyclerView view, @NonNull final MotionEvent event)
        {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
            {
                sessions.touchDown();
            }
            return false;
        }
    };

    /**
     * KHANDAQ (0.2.56, QA 15.11): puts the reply swipe on a message list. The touch watcher goes in ahead of
     * ItemTouchHelper, which can take a touch over from its first event, so it sees every touch begin; the one call
     * adds both, which is why the constructor is private.
     */
    public static void attach(@NonNull final Context context, @NonNull final RecyclerView list, @NonNull final Trigger trigger)
    {
        final ReplySwipeCallback callback = new ReplySwipeCallback(context, trigger);
        list.addOnItemTouchListener(callback.touchWatcher);
        new ItemTouchHelper(callback).attachToRecyclerView(list);
    }

    private ReplySwipeCallback(Context context, Trigger trigger)
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
        if (!sessions.allows(messageKeyOf(viewHolder)))
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

    /**
     * KHANDAQ (0.2.56, QA 15.11): nor by its speed. ItemTouchHelper also commits a swipe when the finger leaves at
     * this velocity or faster (120 dp/s by default), whatever getSwipeThreshold says, and a committed swipe never
     * reaches clearView(): the row stayed 72 dp aside with the badge on until it left the screen, the release made
     * no reply, and the next touch on the row made it. Out of reach, every release slides the row back and ends in
     * clearView().
     */
    @Override
    public float getSwipeEscapeVelocity(final float defaultValue)
    {
        return Float.MAX_VALUE;
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction)
    {
        // unreachable: neither the distance (getSwipeThreshold) nor the speed (getSwipeEscapeVelocity) can commit
        // the swipe; the reply fires from clearView()
    }

    /**
     * KHANDAQ (0.2.56, QA 15.11): ItemTouchHelper takes up the row under the finger, or lets go of it (null): on
     * release, and also mid-swipe when the list rebuilds or scrolls that row away, after which it takes up whichever
     * row is under the finger next.
     */
    @Override
    public void onSelectedChanged(@Nullable final RecyclerView.ViewHolder viewHolder, final int actionState)
    {
        super.onSelectedChanged(viewHolder, actionState);
        if (viewHolder == null)
        {
            sessions.deselect();
        }
        else if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE)
        {
            sessions.select(viewHolder, messageKeyOf(viewHolder));
        }
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
            if (isCurrentlyActive && sessions.drag(viewHolder, Math.abs(clamped) >= triggerDx))
            {
                recyclerView.performHapticFeedback(Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                                                   ? HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                                                   : HapticFeedbackConstants.KEYBOARD_TAP);
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
        // The swiped message, when its row came to rest after the finger had it past the reply point. The row may
        // show another message by now (a rebuilt list hands its rows around), so the message is looked up by its
        // key; gone from the list meanwhile (remote delete-for-both) → silently no-op.
        final Object key = sessions.rest(viewHolder);
        if (key == null)
        {
            return;
        }
        final RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        final int pos = positionOf(key, viewHolder.getBindingAdapterPosition(),
                                   adapter == null ? 0 : adapter.getItemCount(), trigger);
        if (pos != RecyclerView.NO_POSITION)
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

    private Object messageKeyOf(final RecyclerView.ViewHolder viewHolder)
    {
        final int pos = viewHolder.getBindingAdapterPosition();
        return pos == RecyclerView.NO_POSITION ? null : trigger.messageKeyAt(pos);
    }

    /**
     * KHANDAQ (0.2.56, QA 15.11): a message's key for Trigger.messageKeyAt: its database row id, which outlives a
     * reload of the list, or, for a message not stored yet (no id of its own: 0 or negative), the object itself.
     */
    static Object messageKey(final long id, final Object message)
    {
        if (message == null)
        {
            return null;
        }
        return id > 0 ? Long.valueOf(id) : message;
    }

    /**
     * KHANDAQ (0.2.56, QA 15.11): where the message with this key is among the list's count rows: at hint (where
     * its row is) when it is still there, else wherever the list has moved it; NO_POSITION once it is gone.
     */
    static int positionOf(final Object key, final int hint, final int count, final Trigger trigger)
    {
        if (key == null)
        {
            return RecyclerView.NO_POSITION;
        }
        if (hint >= 0 && hint < count && key.equals(trigger.messageKeyAt(hint)))
        {
            return hint;
        }
        // newest first: a reply mostly goes to a recent message
        for (int i = count - 1; i >= 0; i--)
        {
            if (key.equals(trigger.messageKeyAt(i)))
            {
                return i;
            }
        }
        return RecyclerView.NO_POSITION;
    }

    /**
     * KHANDAQ (0.2.56, QA 15.11): which message a swipe replies to. A swipe used to stand for its row. A message
     * arriving mid-swipe rebuilds the list (the one-to-one list on every message): ItemTouchHelper lets go of the
     * swiped row, which a rebuilt list may bind to another message before it is back at rest, and takes up
     * whichever row is under the finger next, already at the full distance of the touch; the reply went to what
     * those rows showed, and the latch was one flag for all of them. Now a swipe is the message under the finger
     * when its row starts to move, held by its key: in that touch no other message's row moves (its own, rebuilt
     * under the finger, carries the swipe on), and it replies once, when the row carrying it comes to rest after
     * the finger had it past the reply point. Rows (ViewHolders) are compared by identity.
     */
    static final class Sessions
    {
        private int touches;
        private Swipe swipe;

        private static final class Swipe
        {
            final Object key;
            final int touch;
            Object row;
            boolean active;
            boolean past;
            boolean replied;

            Swipe(final Object key, final int touch)
            {
                this.key = key;
                this.touch = touch;
            }
        }

        /** A finger came down on the list. */
        void touchDown()
        {
            touches++;
        }

        /** Whether a row showing the message with this key may move now. */
        boolean allows(final Object key)
        {
            if (key == null)
            {
                return false;
            }
            final Swipe s = swipe;
            return s == null || s.touch != touches || (!s.replied && key.equals(s.key));
        }

        /** ItemTouchHelper took up this row, which shows the message with this key. */
        void select(final Object row, final Object key)
        {
            final Swipe s = swipe;
            if (s == null || s.touch != touches || s.replied || key == null || !key.equals(s.key))
            {
                swipe = new Swipe(key, touches);
            }
            swipe.row = row;
            swipe.active = true;
        }

        /** A frame of the row under the finger; true when the row has just got past the reply point (the tick). */
        boolean drag(final Object row, final boolean pastReplyPoint)
        {
            final Swipe s = swipe;
            if (s == null || !s.active || s.row != row)
            {
                return false;
            }
            final boolean crossed = pastReplyPoint && !s.past;
            s.past = pastReplyPoint;
            return crossed;
        }

        /** ItemTouchHelper let go of the row: the finger left, or the list took the row away. */
        void deselect()
        {
            if (swipe != null)
            {
                swipe.active = false;
            }
        }

        /** The row is back at rest: the key of the message to reply to, at most once per swipe, else null. */
        Object rest(final Object row)
        {
            final Swipe s = swipe;
            if (s == null || s.row != row)
            {
                return null;
            }
            // a row the list removed comes to rest before ItemTouchHelper reports letting go of it
            s.active = false;
            if (!s.past || s.replied || s.key == null)
            {
                return null;
            }
            s.replied = true;
            return s.key;
        }
    }
}

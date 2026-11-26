package com.zoffcc.applications.trifa;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.CancellationSignal;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.RelativeLayout;

import java.util.function.BooleanSupplier;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsAnimationControlListenerCompat;
import androidx.core.view.WindowInsetsAnimationControllerCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * KHANDAQ (Khandaq Testers 8251): the transcript of a chat (a person, Saved Messages, a group), whose list dragged
 * down toward older messages takes the keyboard down with it, as in Telegram.
 *
 * <p>The list is a nested-scrolling child of this layout. A drag down with the keyboard up asks the window for
 * control of the keyboard; once given, the keyboard takes the drag first, both ways, the composer follows it
 * (the bottom padding of the window's content, which ViewUtil.applyImeBottomInsets keeps as tall as the keyboard),
 * and the rest of the drag scrolls the list. When the finger lets go, the keyboard goes down or back up: as the
 * fling says, otherwise to the nearer state (ChatKeyboardPull).
 *
 * <p>The window gives that control only from Android 11 on, and only to a window drawn edge to edge (the
 * window must cover the keyboard; Android 16 draws this app so). Without it the keyboard just goes down
 * as the drag starts, as any keyboard does. A drag up, a scroll, a long press and a fling keep working as
 * before: nothing of them is taken while the keyboard is down or the drag goes up.
 */
final class ChatKeyboardPullLayout extends RelativeLayout implements NestedScrollingParent3
{
    private static final String TAG = "trifa.KbdPull";
    /** How long the keyboard takes to go the rest of the way once the finger lets go. */
    private static final long SETTLE_MS = 220L;

    private final NestedScrollingParentHelper parentHelper = new NestedScrollingParentHelper(this);
    private final int touchSlop;
    private final float minFlingVelocity;

    /** False while something lies over the keyboard (the emoji panel): the keyboard then only goes down. */
    @Nullable
    private BooleanSupplier followsFinger = null;

    /** A finger drags the list. */
    private boolean dragging = false;
    /** This drag has taken the keyboard already, or let it go down. */
    private boolean pulled = false;
    /** How far this drag went down before it took the keyboard. */
    private int pulledDown = 0;
    /** The fling that ended this drag while the keyboard was half way, 0 for none. */
    private float flingVelocityY = 0f;

    @Nullable
    private CancellationSignal request = null;
    @Nullable
    private WindowInsetsAnimationControllerCompat controller = null;
    @Nullable
    private ValueAnimator settle = null;
    /** The keyboard's bottom inset as it is drawn now. */
    private int bottom = 0;
    /** The content of the window, whose bottom padding holds the composer above the keyboard. */
    @Nullable
    private View content = null;
    /** The padding of the content follows the keyboard (it is what holds the composer above it). */
    private boolean padsContent = false;
    private int navigationBottom = 0;

    public ChatKeyboardPullLayout(final Context context)
    {
        this(context, null);
    }

    public ChatKeyboardPullLayout(final Context context, final AttributeSet attrs)
    {
        this(context, attrs, 0);
    }

    public ChatKeyboardPullLayout(final Context context, final AttributeSet attrs, final int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minFlingVelocity = configuration.getScaledMinimumFlingVelocity();
    }

    /** What says whether the keyboard may follow the finger now; null: always. */
    void setFollowsFinger(@Nullable final BooleanSupplier followsFinger)
    {
        this.followsFinger = followsFinger;
    }

    // ---- nested scrolling: the list is the child ----

    @Override
    public boolean onStartNestedScroll(@NonNull final View child, @NonNull final View target, final int axes,
                                       final int type)
    {
        return (type == ViewCompat.TYPE_TOUCH) && ((axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0);
    }

    @Override
    public void onNestedScrollAccepted(@NonNull final View child, @NonNull final View target, final int axes,
                                       final int type)
    {
        parentHelper.onNestedScrollAccepted(child, target, axes, type);
        if (type == ViewCompat.TYPE_TOUCH)
        {
            dragging = true;
            pulled = false;
            pulledDown = 0;
            flingVelocityY = 0f;
        }
    }

    @Override
    public void onNestedPreScroll(@NonNull final View target, final int dx, final int dy, @NonNull final int[] consumed,
                                  final int type)
    {
        if (type != ViewCompat.TYPE_TOUCH)
        {
            return;
        }
        final WindowInsetsAnimationControllerCompat c = following();
        if (c != null)
        {
            // The keyboard takes the drag first, both ways, until it is down or up; the rest scrolls the list.
            final int next = ChatKeyboardPull.pulledBottom(bottom, dy, hiddenBottom(c), shownBottom(c));
            consumed[1] = next - bottom;
            moveTo(c, next);
            return;
        }
        if (pulled || (controller != null) || (request != null))
        {
            // Taken already, or the last pull's keyboard still settles: the list scrolls as it always does.
            return;
        }
        if (dy >= 0)
        {
            pulledDown = 0;
            return;
        }
        pulledDown -= dy;
        if (ChatKeyboardPull.pullStarts(pulledDown, touchSlop) && keyboardUp())
        {
            pulled = true;
            take();
        }
    }

    @Override
    public void onNestedScroll(@NonNull final View target, final int dxConsumed, final int dyConsumed,
                               final int dxUnconsumed, final int dyUnconsumed, final int type,
                               @NonNull final int[] consumed)
    {
        // The keyboard takes its part before the list (onNestedPreScroll). Only a list at its oldest message
        // leaves something over while the drag is still to take the keyboard: that part stays here, or the list
        // would stretch for it and keep the rest of the drag (ChatKeyboardPull.holdsOverscroll).
        final boolean undecided = (type == ViewCompat.TYPE_TOUCH) && dragging && !pulled && (controller == null)
                                  && (request == null);
        if ((dyUnconsumed < 0) && ChatKeyboardPull.holdsOverscroll(undecided, keyboardUp(), dyUnconsumed))
        {
            consumed[1] += dyUnconsumed;
        }
    }

    @Override
    public void onNestedScroll(@NonNull final View target, final int dxConsumed, final int dyConsumed,
                               final int dxUnconsumed, final int dyUnconsumed, final int type)
    {
    }

    @Override
    public boolean onNestedPreFling(@NonNull final View target, final float velocityX, final float velocityY)
    {
        final WindowInsetsAnimationControllerCompat c = following();
        if ((c != null) && (bottom != hiddenBottom(c)) && (bottom != shownBottom(c)))
        {
            // The keyboard is half way: the fling takes it the rest of the way, and the list stays where it is.
            flingVelocityY = velocityY;
            return true;
        }
        return false;
    }

    @Override
    public boolean onNestedFling(@NonNull final View target, final float velocityX, final float velocityY,
                                 final boolean consumed)
    {
        return false;
    }

    @Override
    public void onStopNestedScroll(@NonNull final View target, final int type)
    {
        parentHelper.onStopNestedScroll(target, type);
        if (type != ViewCompat.TYPE_TOUCH)
        {
            return;
        }
        dragging = false;
        final WindowInsetsAnimationControllerCompat c = following();
        if (c != null)
        {
            settleTo(c, ChatKeyboardPull.settlesShown(bottom, hiddenBottom(c), shownBottom(c), flingVelocityY,
                                                      minFlingVelocity));
        }
        // Still waiting for the keyboard: onReady takes it down, as the drag that asked for it went down.
    }

    @Override
    public int getNestedScrollAxes()
    {
        return parentHelper.getNestedScrollAxes();
    }

    // The framework's own nested scrolling (a child that is not an androidx one) goes the same way as a touch.

    @Override
    public boolean onStartNestedScroll(@NonNull final View child, @NonNull final View target, final int axes)
    {
        return onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onNestedScrollAccepted(@NonNull final View child, @NonNull final View target, final int axes)
    {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onNestedPreScroll(@NonNull final View target, final int dx, final int dy, @NonNull final int[] consumed)
    {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onNestedScroll(@NonNull final View target, final int dxConsumed, final int dyConsumed,
                               final int dxUnconsumed, final int dyUnconsumed)
    {
    }

    @Override
    public void onStopNestedScroll(@NonNull final View target)
    {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH);
    }

    @Override
    protected void onDetachedFromWindow()
    {
        final CancellationSignal pending = request;
        request = null;
        if (pending != null)
        {
            pending.cancel();
        }
        final WindowInsetsAnimationControllerCompat c = controller;
        if ((c != null) && c.isReady())
        {
            final boolean up = ChatKeyboardPull.settlesShown(bottom, hiddenBottom(c), shownBottom(c), 0f,
                                                             minFlingVelocity);
            release();
            c.finish(up);
        }
        else
        {
            release();
        }
        super.onDetachedFromWindow();
    }

    // ---- the keyboard ----

    private boolean keyboardUp()
    {
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        return (insets != null) && insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    /** The keyboard while the finger has it: not while it is asked for, nor while it settles. */
    @Nullable
    private WindowInsetsAnimationControllerCompat following()
    {
        final WindowInsetsAnimationControllerCompat c = controller;
        return ((c != null) && (settle == null) && c.isReady()) ? c : null;
    }

    private static int hiddenBottom(final WindowInsetsAnimationControllerCompat c)
    {
        return c.getHiddenStateInsets().bottom;
    }

    private static int shownBottom(final WindowInsetsAnimationControllerCompat c)
    {
        return c.getShownStateInsets().bottom;
    }

    /** A drag down took the keyboard: the finger moves it from now on, or it simply goes down. */
    private void take()
    {
        final BooleanSupplier follows = followsFinger;
        final Activity activity = activityOf(getContext());
        final Window window = (activity == null) ? null : activity.getWindow();
        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.R) || (window == null)
            || ((follows != null) && !follows.getAsBoolean()))
        {
            hideKeyboard();
            return;
        }
        content = activity.findViewById(android.R.id.content);
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
        navigationBottom = (insets == null) ? 0 : insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
        // Read now: once the window gives the keyboard to the finger, it lays the content out as if the keyboard
        // were down already, and the padding says nothing more.
        final int keyboardBottom = (insets == null) ? 0 : insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        padsContent = (content != null)
                      && ChatKeyboardPull.padsContent(content.getPaddingBottom(), keyboardBottom, navigationBottom);
        final CancellationSignal signal = new CancellationSignal();
        request = signal;
        try
        {
            WindowCompat.getInsetsController(window, this).controlWindowInsetsAnimation(
                    WindowInsetsCompat.Type.ime(), -1L, new LinearInterpolator(), signal, control);
        }
        catch (Exception e)
        {
            Log.i(TAG, "take:EE:" + e.getMessage());
            release();
            hideKeyboard();
        }
    }

    private final WindowInsetsAnimationControlListenerCompat control = new WindowInsetsAnimationControlListenerCompat()
    {
        @Override
        public void onReady(@NonNull final WindowInsetsAnimationControllerCompat ready, final int types)
        {
            if (request == null)
            {
                // Asked for by a layout that has left the window since.
                ready.finish(true);
                return;
            }
            request = null;
            controller = ready;
            bottom = ready.getCurrentInsets().bottom;
            if (padsContent)
            {
                // The window has laid the content out for a keyboard that is down already: the composer goes
                // back above the keyboard, which is still where it was, and follows it from here.
                moveTo(ready, bottom);
            }
            if (!dragging || !padsContent)
            {
                // The finger let go before the keyboard came, or the composer is held up by something else
                // than the padding: the keyboard goes the whole way down at once.
                settleTo(ready, false);
            }
        }

        @Override
        public void onFinished(@NonNull final WindowInsetsAnimationControllerCompat finished)
        {
            if (finished == controller)
            {
                release();
            }
        }

        @Override
        public void onCancelled(@Nullable final WindowInsetsAnimationControllerCompat cancelled)
        {
            final boolean asked = (controller == null) && (request != null);
            if ((cancelled == null) || (cancelled == controller))
            {
                release();
            }
            if (asked)
            {
                // Not given: the window does not cover the keyboard (it is resized for it instead), or the
                // keyboard went already. It goes down as any keyboard does.
                hideKeyboard();
            }
        }
    };

    private void moveTo(final WindowInsetsAnimationControllerCompat c, final int next)
    {
        if (!c.isReady())
        {
            return;
        }
        bottom = next;
        final int hidden = hiddenBottom(c);
        final int shown = shownBottom(c);
        final float fraction = (shown == hidden) ? 1f : (float) (shown - next) / (float) (shown - hidden);
        c.setInsetsAndAlpha(Insets.of(0, 0, 0, next), 1f, fraction);
        final View host = content;
        if (padsContent && (host != null))
        {
            host.setPadding(host.getPaddingLeft(), host.getPaddingTop(), host.getPaddingRight(),
                            Math.max(next, navigationBottom));
        }
    }

    /** The keyboard goes the rest of the way, up or down, and is the window's again. */
    private void settleTo(final WindowInsetsAnimationControllerCompat c, final boolean up)
    {
        if (!c.isReady() || (settle != null))
        {
            return;
        }
        final int target = up ? shownBottom(c) : hiddenBottom(c);
        if (target == bottom)
        {
            c.finish(up);
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofInt(bottom, target);
        animator.setDuration(SETTLE_MS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> moveTo(c, (Integer) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter()
        {
            @Override
            public void onAnimationEnd(final Animator animation)
            {
                if (settle != animator)
                {
                    return;
                }
                if (c.isReady())
                {
                    c.finish(up);
                }
                else
                {
                    release();
                }
            }
        });
        settle = animator;
        animator.start();
    }

    /** The keyboard is the window's again; the content reads its padding from the window once more. */
    private void release()
    {
        final ValueAnimator running = settle;
        settle = null;
        controller = null;
        request = null;
        if (running != null)
        {
            running.cancel();
        }
        final View host = content;
        content = null;
        if (padsContent && (host != null))
        {
            ViewCompat.requestApplyInsets(host);
        }
        padsContent = false;
    }

    private void hideKeyboard()
    {
        final InputMethodManager input =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if ((input != null) && (getWindowToken() != null))
        {
            input.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Nullable
    private static Activity activityOf(@Nullable final Context context)
    {
        Context c = context;
        while (c instanceof ContextWrapper)
        {
            if (c instanceof Activity)
            {
                return (Activity) c;
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}

package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (Khandaq Testers 8251): the rules of a keyboard pulled down by the message list, apart from
 * {@link ChatKeyboardPullLayout} so that they are tested without a window.
 *
 * <p>A bottom is the keyboard's bottom inset in pixels. A drag's dy is RecyclerView's: negative while the finger
 * goes down, toward older messages. A fling's velocity is RecyclerView's too: positive while the finger goes up.
 */
final class ChatKeyboardPull
{
    private ChatKeyboardPull()
    {
    }

    /** A drag down takes the keyboard once it has gone the touch slop: a deliberate pull, not a jitter. */
    static boolean pullStarts(final int pulledDown, final int touchSlop)
    {
        return pulledDown >= Math.max(1, touchSlop);
    }

    /**
     * Whether what the list could not scroll of a drag stays with the pull: a drag down with the keyboard up,
     * while it is still to say whether the drag takes the keyboard. The list stands at its oldest message then;
     * from Android 12 on it would stretch for that part, and a stretched list keeps the rest of the drag to
     * itself, so the drag would never go far enough to take the keyboard.
     */
    static boolean holdsOverscroll(final boolean undecided, final boolean keyboardUp, final int dyUnconsumed)
    {
        return undecided && keyboardUp && (dyUnconsumed < 0);
    }

    /**
     * Whether the bottom padding of the window's content is what holds the composer above the keyboard: it is as
     * tall as the keyboard, which stands above the navigation bar. Read before the window gives the keyboard to
     * the finger, as from then on the window lays the content out as if the keyboard were down already.
     */
    static boolean padsContent(final int contentPaddingBottom, final int keyboardBottom, final int navigationBottom)
    {
        return (keyboardBottom > navigationBottom) && (Math.abs((long) contentPaddingBottom - (long) keyboardBottom) <= 1L);
    }

    /** The keyboard's bottom inset after the finger moved by dy, held between its hidden and shown insets. */
    static int pulledBottom(final int bottom, final int dy, final int hidden, final int shown)
    {
        final long low = Math.min(hidden, shown);
        final long high = Math.max(hidden, shown);
        final long next = (long) bottom + (long) dy;
        return (int) Math.max(low, Math.min(high, next));
    }

    /**
     * Whether the keyboard goes back up once the finger lets go. A fling says it by its direction; without one
     * the keyboard goes to the nearer of its two states, and back up from halfway.
     */
    static boolean settlesShown(final int bottom, final int hidden, final int shown, final float velocityY,
                                final float minFlingVelocity)
    {
        if (shown <= hidden)
        {
            return false;
        }
        if ((velocityY != 0f) && (Math.abs(velocityY) >= minFlingVelocity))
        {
            return velocityY > 0f;
        }
        return 2L * ((long) bottom - (long) hidden) >= ((long) shown - (long) hidden);
    }
}

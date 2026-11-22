package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (Khandaq Testers 8251): the message list dragged down takes the keyboard down with it, as in Telegram
 * (ChatKeyboardPullLayout). These are its rules: when a drag takes the keyboard, where the finger puts it, and
 * where it goes once the finger lets go.
 */
public class ChatKeyboardPullTest
{
    private static final int HIDDEN = 0;
    private static final int SHOWN = 900;
    private static final float MIN_FLING = 150f;

    @Test
    public void a_jitter_leaves_the_keyboard_a_drag_takes_it()
    {
        assertFalse(ChatKeyboardPull.pullStarts(0, 16));
        assertFalse(ChatKeyboardPull.pullStarts(15, 16));
        assertTrue(ChatKeyboardPull.pullStarts(16, 16));
        // A slop of nothing still wants the finger to have moved.
        assertFalse(ChatKeyboardPull.pullStarts(0, 0));
        assertTrue(ChatKeyboardPull.pullStarts(1, 0));
    }

    @Test
    public void the_keyboard_follows_the_finger_both_ways()
    {
        // The finger goes down 120 px: the keyboard as much.
        assertEquals(780, ChatKeyboardPull.pulledBottom(SHOWN, -120, HIDDEN, SHOWN));
        // And back up 50 px.
        assertEquals(830, ChatKeyboardPull.pulledBottom(780, 50, HIDDEN, SHOWN));
    }

    @Test
    public void the_keyboard_stops_at_its_two_states_and_the_list_takes_the_rest()
    {
        assertEquals(HIDDEN, ChatKeyboardPull.pulledBottom(40, -120, HIDDEN, SHOWN));
        assertEquals(SHOWN, ChatKeyboardPull.pulledBottom(880, 120, HIDDEN, SHOWN));
        // Up already, a drag up is the list's alone; down already, a drag down too.
        assertEquals(SHOWN, ChatKeyboardPull.pulledBottom(SHOWN, 30, HIDDEN, SHOWN));
        assertEquals(HIDDEN, ChatKeyboardPull.pulledBottom(HIDDEN, -30, HIDDEN, SHOWN));
        // No overflow at the extremes of an int.
        assertEquals(HIDDEN, ChatKeyboardPull.pulledBottom(HIDDEN, Integer.MIN_VALUE, HIDDEN, SHOWN));
        assertEquals(SHOWN, ChatKeyboardPull.pulledBottom(SHOWN, Integer.MAX_VALUE, HIDDEN, SHOWN));
    }

    @Test
    public void a_list_at_its_oldest_message_leaves_the_drag_to_the_pull()
    {
        // Android 12+ stretches a list pulled past its top, and the stretched list keeps the rest of the drag:
        // in a short chat the pull never reached the touch slop and the keyboard stayed up.
        assertTrue(ChatKeyboardPull.holdsOverscroll(true, true, -5));
        // Not once the drag has taken the keyboard or let it go, nor with the keyboard down, nor for a drag up.
        assertFalse(ChatKeyboardPull.holdsOverscroll(false, true, -5));
        assertFalse(ChatKeyboardPull.holdsOverscroll(true, false, -5));
        assertFalse(ChatKeyboardPull.holdsOverscroll(true, true, 5));
        assertFalse(ChatKeyboardPull.holdsOverscroll(true, true, 0));
    }

    @Test
    public void the_padding_holds_the_composer_when_it_is_as_tall_as_the_keyboard()
    {
        // Edge to edge (Android 16): the content's bottom padding is the keyboard's inset.
        assertTrue(ChatKeyboardPull.padsContent(883, 883, 63));
        assertTrue(ChatKeyboardPull.padsContent(884, 883, 63));
        // The window is resized for the keyboard instead: the padding is the navigation bar's.
        assertFalse(ChatKeyboardPull.padsContent(63, 883, 63));
        // No keyboard above the navigation bar: the padding says nothing.
        assertFalse(ChatKeyboardPull.padsContent(63, 63, 63));
        assertFalse(ChatKeyboardPull.padsContent(0, 0, 0));
    }

    @Test
    public void a_fling_settles_the_keyboard_by_its_direction()
    {
        // RecyclerView's velocity: negative while the finger goes down.
        assertFalse(ChatKeyboardPull.settlesShown(850, HIDDEN, SHOWN, -2000f, MIN_FLING));
        assertTrue(ChatKeyboardPull.settlesShown(100, HIDDEN, SHOWN, 2000f, MIN_FLING));
    }

    @Test
    public void without_a_fling_the_keyboard_goes_to_the_nearer_state()
    {
        assertTrue(ChatKeyboardPull.settlesShown(600, HIDDEN, SHOWN, 0f, MIN_FLING));
        assertTrue(ChatKeyboardPull.settlesShown(450, HIDDEN, SHOWN, 0f, MIN_FLING));
        assertFalse(ChatKeyboardPull.settlesShown(449, HIDDEN, SHOWN, 0f, MIN_FLING));
        assertFalse(ChatKeyboardPull.settlesShown(HIDDEN, HIDDEN, SHOWN, 0f, MIN_FLING));
        // A movement too slow to be a fling counts as none.
        assertTrue(ChatKeyboardPull.settlesShown(600, HIDDEN, SHOWN, -100f, MIN_FLING));
    }

    @Test
    public void a_keyboard_without_height_settles_down()
    {
        assertFalse(ChatKeyboardPull.settlesShown(0, 0, 0, 0f, MIN_FLING));
        assertFalse(ChatKeyboardPull.settlesShown(0, 0, 0, 5000f, MIN_FLING));
    }
}

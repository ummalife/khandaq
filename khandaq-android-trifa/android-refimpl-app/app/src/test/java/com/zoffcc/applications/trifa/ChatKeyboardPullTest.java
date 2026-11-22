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

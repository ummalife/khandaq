package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): swipe a message to reply goes toward the start edge, as in Telegram: to the left,
 * and to the right in a right-to-left layout. It went to the right before.
 */
public class ReplySwipeCallbackTest
{
    private static final float MAX = 72f;
    private static final float TRIGGER = 56f;

    @Test
    public void left_to_right_the_row_moves_only_to_the_left()
    {
        assertEquals(-30f, ReplySwipeCallback.replyOffset(-30f, MAX, false), 0f);
        assertEquals(-MAX, ReplySwipeCallback.replyOffset(-500f, MAX, false), 0f); // capped
        assertEquals(0f, ReplySwipeCallback.replyOffset(40f, MAX, false), 0f); // the other way: stays put
        assertEquals(0f, ReplySwipeCallback.replyOffset(0f, MAX, false), 0f);
    }

    @Test
    public void right_to_left_it_is_mirrored()
    {
        assertEquals(30f, ReplySwipeCallback.replyOffset(30f, MAX, true), 0f);
        assertEquals(MAX, ReplySwipeCallback.replyOffset(500f, MAX, true), 0f);
        assertEquals(0f, ReplySwipeCallback.replyOffset(-40f, MAX, true), 0f);
    }

    @Test
    public void the_reply_point_is_reachable()
    {
        // releasing replies once the row is TRIGGER away; the cap must not stop it short of that
        assertTrue(Math.abs(ReplySwipeCallback.replyOffset(-500f, MAX, false)) >= TRIGGER);
        assertTrue(ReplySwipeCallback.replyOffset(500f, MAX, true) >= TRIGGER);
    }

    @Test
    public void the_badge_grows_until_the_reply_point()
    {
        assertEquals(0f, ReplySwipeCallback.replyBadgeProgress(0f, TRIGGER), 0f);
        assertEquals(0.5f, ReplySwipeCallback.replyBadgeProgress(-28f, TRIGGER), 1e-6f);
        assertEquals(0.5f, ReplySwipeCallback.replyBadgeProgress(28f, TRIGGER), 1e-6f); // right to left
        assertEquals(1f, ReplySwipeCallback.replyBadgeProgress(-TRIGGER, TRIGGER), 0f);
        assertEquals(1f, ReplySwipeCallback.replyBadgeProgress(-MAX, TRIGGER), 0f);
    }

    @Test
    public void the_swipe_direction_is_the_start_edge() throws Exception
    {
        final String source = new String(Files.readAllBytes(
                new File("src/main/java/com/zoffcc/applications/trifa/ReplySwipeCallback.java").toPath()),
                                         StandardCharsets.UTF_8);
        assertTrue(source.contains("super(0, ItemTouchHelper.START);"));
        assertFalse(source.contains("ItemTouchHelper.RIGHT"));
    }
}

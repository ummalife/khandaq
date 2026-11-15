package com.zoffcc.applications.trifa;

import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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

    /**
     * KHANDAQ (0.2.56, QA 15.11): after a quick swipe the row stayed 72 dp aside, with the badge on and no reply.
     * ItemTouchHelper (recyclerview 1.3.2, checkHorizontalSwipe) commits a swipe when the finger leaves at
     * getSwipeEscapeVelocity() or faster, whatever getSwipeThreshold() says, and a committed swipe never reaches
     * clearView(). The swipe that stuck: 600 px in 405 ms on the 420 dpi emulator.
     */
    @Test
    public void a_quick_swipe_never_commits_so_the_row_always_slides_back() throws Exception
    {
        final ReplySwipeCallback callback = callbackWithoutContext();
        final float density = 2.625f;
        final float defaultEscape = 120f * density; // recyclerview's item_touch_helper_swipe_escape_velocity
        // ItemTouchHelper measures the velocity capped at this (item_touch_helper_swipe_escape_max_velocity)
        final float measurable = callback.getSwipeVelocityThreshold(800f * density);
        final float stuckSwipe = 600f / 0.405f;
        assertTrue("a swipe this quick commits", callback.getSwipeEscapeVelocity(defaultEscape) > stuckSwipe);
        assertTrue("some swipe commits by its speed", callback.getSwipeEscapeVelocity(defaultEscape) > measurable);
        // and no drag is long enough: past this share of the list width it would commit too
        assertTrue(callback.getSwipeThreshold(null) > 1f);
    }

    // ---- KHANDAQ (0.2.56, QA 15.11): the reply goes to the message the swipe started on ----

    // rows (ViewHolders), which Sessions tells apart by identity
    private static final Object ROW_A = new Object();
    private static final Object ROW_B = new Object();

    /**
     * A message arrives mid-swipe: the list is rebuilt, ItemTouchHelper lets go of the swiped row (it slides back,
     * by now bound to another message), and the finger is over another message's row, which ItemTouchHelper would
     * take up at the full distance of the touch. That row stays put, and the reply goes to the swiped message.
     */
    @Test
    public void a_message_arriving_mid_swipe_does_not_turn_the_reply_to_another_message()
    {
        final ReplySwipeCallback.Sessions sessions = new ReplySwipeCallback.Sessions();
        sessions.touchDown();
        assertTrue(sessions.allows(7L));
        sessions.select(ROW_A, 7L); // row A shows message 7 when it starts to move
        assertTrue("past the reply point: the tick", sessions.drag(ROW_A, true));
        sessions.deselect(); // the rebuild takes row A away
        assertFalse("another message's row moves in the same touch", sessions.allows(9L));
        assertFalse("nor the message row A shows now", sessions.allows(8L));
        assertEquals("the reply goes to the swiped message", Long.valueOf(7L), sessions.rest(ROW_A));
        assertNull("once", sessions.rest(ROW_A));
    }

    /**
     * The rebuilt list puts the swiped message's new row under the finger (nothing scrolled): that row carries the
     * swipe on, with no second tick for the same crossing, and the old row coming to rest replies nothing.
     */
    @Test
    public void the_swiped_message_rebuilt_under_the_finger_carries_the_swipe_on()
    {
        final ReplySwipeCallback.Sessions sessions = new ReplySwipeCallback.Sessions();
        sessions.touchDown();
        sessions.select(ROW_A, 7L);
        assertTrue(sessions.drag(ROW_A, true));
        sessions.deselect(); // the rebuild takes row A away
        assertTrue(sessions.allows(7L)); // message 7 again, now on row B
        sessions.select(ROW_B, 7L);
        assertFalse("the same crossing does not tick twice", sessions.drag(ROW_B, true));
        assertNull("the old row at rest", sessions.rest(ROW_A));
        assertFalse("a frame of the old row counts for nothing", sessions.drag(ROW_A, false));
        sessions.deselect(); // the finger lifts
        assertEquals(Long.valueOf(7L), sessions.rest(ROW_B));
    }

    @Test
    public void one_touch_replies_once_and_the_next_touch_swipes_anew()
    {
        final ReplySwipeCallback.Sessions sessions = new ReplySwipeCallback.Sessions();
        sessions.touchDown();
        sessions.select(ROW_A, 7L);
        sessions.drag(ROW_A, true);
        // a row the list removes comes to rest before ItemTouchHelper reports letting go of it
        assertEquals(Long.valueOf(7L), sessions.rest(ROW_A));
        sessions.deselect();
        assertFalse("nothing more moves in this touch", sessions.allows(7L));
        assertFalse(sessions.allows(9L));
        sessions.touchDown();
        assertTrue(sessions.allows(9L));
        assertTrue(sessions.allows(7L));
    }

    @Test
    public void a_release_short_of_the_reply_point_replies_nothing_and_each_crossing_ticks_once()
    {
        final ReplySwipeCallback.Sessions sessions = new ReplySwipeCallback.Sessions();
        sessions.touchDown();
        sessions.select(ROW_A, 7L);
        assertFalse(sessions.drag(ROW_A, false));
        assertTrue(sessions.drag(ROW_A, true));
        assertFalse("still past: no second tick", sessions.drag(ROW_A, true));
        assertFalse(sessions.drag(ROW_A, false)); // back short of it
        assertTrue("past again: a tick", sessions.drag(ROW_A, true));
        assertFalse(sessions.drag(ROW_A, false));
        sessions.deselect();
        assertNull(sessions.rest(ROW_A));
    }

    @Test
    public void a_row_without_a_message_neither_moves_nor_replies()
    {
        final ReplySwipeCallback.Sessions sessions = new ReplySwipeCallback.Sessions();
        sessions.touchDown();
        assertFalse(sessions.allows(null));
        // ItemTouchHelper takes up a row still sliding back on touch-down without asking first
        sessions.select(ROW_A, null);
        sessions.drag(ROW_A, true);
        sessions.deselect();
        assertNull(sessions.rest(ROW_A));
    }

    @Test
    public void the_reply_finds_the_message_wherever_the_list_has_moved_it()
    {
        final List<Object> rows = new ArrayList<>(Arrays.asList(5L, 6L, 7L, 8L));
        final ReplySwipeCallback.Trigger trigger = keysOf(rows);
        assertEquals(2, ReplySwipeCallback.positionOf(7L, 2, rows.size(), trigger)); // where its row is
        rows.add(0, 4L); // an older message loaded above it
        assertEquals(3, ReplySwipeCallback.positionOf(7L, 2, rows.size(), trigger));
        assertEquals(3, ReplySwipeCallback.positionOf(7L, RecyclerView.NO_POSITION, rows.size(), trigger));
        assertEquals(3, ReplySwipeCallback.positionOf(7L, 99, rows.size(), trigger));
        rows.remove(Long.valueOf(7L)); // deleted for both meanwhile
        assertEquals(RecyclerView.NO_POSITION, ReplySwipeCallback.positionOf(7L, 3, rows.size(), trigger));
        assertEquals(RecyclerView.NO_POSITION, ReplySwipeCallback.positionOf(null, 0, rows.size(), trigger));
    }

    @Test
    public void a_message_is_known_by_its_row_id_or_without_one_by_itself()
    {
        final Object loaded = new Object();
        final Object reloaded = new Object(); // the same database row read again is another object
        assertEquals(ReplySwipeCallback.messageKey(42L, loaded), ReplySwipeCallback.messageKey(42L, reloaded));
        assertNotEquals(ReplySwipeCallback.messageKey(42L, loaded), ReplySwipeCallback.messageKey(43L, loaded));
        final Object unstoredA = new Object();
        final Object unstoredB = new Object(); // not stored yet: no id of their own
        assertNotEquals(ReplySwipeCallback.messageKey(-1L, unstoredA), ReplySwipeCallback.messageKey(-1L, unstoredB));
        assertNotEquals(ReplySwipeCallback.messageKey(0L, unstoredA), ReplySwipeCallback.messageKey(0L, unstoredB));
        assertEquals(unstoredA, ReplySwipeCallback.messageKey(-1L, unstoredA));
        assertNull(ReplySwipeCallback.messageKey(42L, null));
    }

    /** What a JVM test cannot drive: ItemTouchHelper and the two chat lists. */
    @Test
    public void the_lists_get_the_swipe_through_attach_and_the_reply_is_looked_up_by_key()
    {
        final String callback = squeeze(source("ReplySwipeCallback.java"));
        // the touch watcher goes in ahead of ItemTouchHelper, and nothing attaches the callback without it
        assertTrue(callback.contains("list.addOnItemTouchListener(callback.touchWatcher); "
                                     + "new ItemTouchHelper(callback).attachToRecyclerView(list);"));
        assertTrue(callback.contains("private ReplySwipeCallback(Context context, Trigger trigger)"));
        assertTrue(callback.contains("if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { sessions.touchDown(); }"));
        assertTrue(callback.contains("if (!sessions.allows(messageKeyOf(viewHolder))) { return 0; }"));
        assertTrue(callback.contains("sessions.select(viewHolder, messageKeyOf(viewHolder));"));
        assertTrue(callback.contains("if (isCurrentlyActive && sessions.drag(viewHolder, Math.abs(clamped) >= triggerDx))"));
        assertTrue(callback.contains("final Object key = sessions.rest(viewHolder);"));
        assertTrue(callback.contains("final int pos = positionOf(key, viewHolder.getBindingAdapterPosition(), "));
        assertFalse(callback.contains("latchedTriggered"));
        for (final String fragment : new String[]{"MessageListFragment.java", "GroupMessageListFragment.java"})
        {
            final String text = squeeze(source(fragment));
            assertTrue(fragment, text.contains("ReplySwipeCallback.attach(getActivity(), listingsView, "
                                               + "new ReplySwipeCallback.Trigger()"));
            assertTrue(fragment, text.contains("public Object messageKeyAt(int position)"));
            assertTrue(fragment, text.contains("return ReplySwipeCallback.messageKey(m.id, m);"));
        }
    }

    private static ReplySwipeCallback.Trigger keysOf(final List<Object> rows)
    {
        return new ReplySwipeCallback.Trigger()
        {
            @Override
            public Object messageKeyAt(final int position)
            {
                return position >= 0 && position < rows.size() ? rows.get(position) : null;
            }

            @Override
            public void onReply(final int position)
            {
            }
        };
    }

    /**
     * The callback builds its badge from a Context, which a JVM test does not have; the swipe decisions under test
     * read no field, so an instance made without running its constructor is enough.
     */
    private static ReplySwipeCallback callbackWithoutContext() throws Exception
    {
        final Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        field.setAccessible(true);
        final Object unsafe = field.get(null);
        return (ReplySwipeCallback) unsafe.getClass().getMethod("allocateInstance", Class.class)
                                          .invoke(unsafe, ReplySwipeCallback.class);
    }

    /** Line comments out, whitespace runs to one space: the code as one line. */
    private static String squeeze(final String text)
    {
        return text.replaceAll("(?m)//.*$", "").replaceAll("\\s+", " ").trim();
    }

    private static String source(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java/", "app/src/main/java/"})
        {
            final File f = new File(root + "com/zoffcc/applications/trifa/" + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                }
                catch (IOException e)
                {
                    throw new AssertionError(f + ": " + e.getMessage());
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

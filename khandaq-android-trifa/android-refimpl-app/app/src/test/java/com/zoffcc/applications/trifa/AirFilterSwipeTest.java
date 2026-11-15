package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56, QA 15.11): a sideways swipe on the chat list moves to the next filter, and no longer also opens
 * the chat it started on. What counts as sideways is AirModel.isSidewaysSwipe; the listener that takes the gesture
 * is an inner class of the fragment working on MotionEvents, which a JVM test cannot drive, so its shape is pinned
 * in the source, the way AirAutofillFormTest pins the forms.
 */
public class AirFilterSwipeTest
{
    private static final float SLOP = 21f; // the 8 dp touch slop at 420 dpi

    @Test
    public void a_clearly_sideways_move_past_the_slop_is_a_filter_swipe()
    {
        assertTrue(AirModel.isSidewaysSwipe(-30f, 5f, SLOP));
        assertTrue("either way", AirModel.isSidewaysSwipe(30f, -5f, SLOP));
        assertTrue("from the slop on", AirModel.isSidewaysSwipe(SLOP, 0f, SLOP));
        assertTrue(AirModel.isSidewaysSwipe(-400f, 100f, SLOP));
    }

    @Test
    public void a_tap_a_scroll_or_a_diagonal_is_not()
    {
        assertFalse("a tap", AirModel.isSidewaysSwipe(0f, 0f, SLOP));
        assertFalse("a tap's wobble", AirModel.isSidewaysSwipe(SLOP - 1f, 0f, SLOP));
        assertFalse("a scroll", AirModel.isSidewaysSwipe(10f, 60f, SLOP));
        assertFalse("a diagonal", AirModel.isSidewaysSwipe(40f, 30f, SLOP));
        assertFalse("only 1.5 times more across than down", AirModel.isSidewaysSwipe(45f, 30f, SLOP));
    }

    @Test
    public void the_list_lets_go_of_a_swipe_once_it_is_sideways()
    {
        final String swipe = squeeze(classBody(source("AirFragment.java"), "private final class FilterSwipe"));
        // Taken: the row under the finger gets a cancel, so it neither opens nor long-presses, and the rest of the
        // gesture comes to onTouchEvent. It used to return false whatever the gesture.
        assertTrue(swipe.contains("public boolean onInterceptTouchEvent(@NonNull final RecyclerView view, "
                                  + "@NonNull final MotionEvent event) { track(view, event); return claimed; }"));
        assertTrue(swipe.contains("public void onTouchEvent(@NonNull final RecyclerView view, "
                                  + "@NonNull final MotionEvent event) { track(view, event); }"));
        // When: clearly sideways past the slop, not while searching, not once the list scrolls.
        assertTrue(swipe.contains("if (!claimed && !searching && view.getScrollState() != "
                                  + "RecyclerView.SCROLL_STATE_DRAGGING && AirModel.isSidewaysSwipe(event.getX() "
                                  + "- downX, event.getY() - downY, touchSlop)) { claimed = true; }"));
        // Every gesture starts untaken, and only a taken one changes the filter, by the rule it always had.
        assertTrue(swipe.contains("case MotionEvent.ACTION_DOWN: claimed = false;"));
        assertTrue(swipe.contains("if (tracker != null && claimed) {"));
        assertTrue(swipe.contains("if (searching || !AirModel.isSidewaysSwipe(dx, dy, touchSlop)) { return; }"));
    }

    // ---- reading the sources ----

    /** The text between the braces of the first block that follows {@code head}. */
    private static String classBody(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        final int open = text.indexOf('{', at);
        assertTrue("no opening brace", open >= 0);
        int depth = 0;
        for (int i = open; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (c == '{')
            {
                depth++;
            }
            else if (c == '}')
            {
                depth--;
                if (depth == 0)
                {
                    return text.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces");
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

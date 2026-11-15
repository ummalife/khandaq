package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57): the strip at the right edge of Air's list flashed on refreshes with nothing scrolled.
 * Two wake-ups did it: RecyclerView.scrollToPosition(0), called on every refresh at the top, and the
 * framework's "visible" report sent each time a layout pass attaches the rows back. Views cannot be built
 * in a JVM test here, so the rules are tested as functions and the wiring is pinned in the sources.
 */
public class AirListScrollbarTest
{
    private static final String JAVA = "java/com/zoffcc/applications/trifa/";

    @Test
    public void only_coming_into_view_wakes_the_scrollbar()
    {
        assertTrue(AirListView.comesIntoView(false, true));
        // A layout pass attaching the rows back reports "visible" again: no wake-up.
        assertFalse(AirListView.comesIntoView(true, true));
        assertFalse(AirListView.comesIntoView(true, false));
        assertFalse(AirListView.comesIntoView(false, false));
    }

    @Test
    public void the_list_keeps_its_top_only_while_it_is_still_there()
    {
        // At the top when the rows were handed over and still there when they land: a contact who came
        // online lands above the rows in view, so the list goes back to its first row.
        assertTrue(AirListView.keepsTop(true, true));
        // Scrolled away while the rows were compared: the list stays where the user took it.
        assertFalse(AirListView.keepsTop(true, false));
        assertFalse(AirListView.keepsTop(false, true));
        assertFalse(AirListView.keepsTop(false, false));
    }

    @Test
    public void the_air_list_is_the_list_with_quiet_scrollbars()
    {
        final String layout = read("res/layout/fragment_air.xml");
        final int id = layout.indexOf("android:id=\"@+id/air_list\"");
        assertTrue(id > 0);
        final int tag = layout.lastIndexOf('<', id);
        assertTrue(layout.startsWith("<com.zoffcc.applications.trifa.AirListView", tag));
        // The design keeps its scrollbar: it still shows while the list scrolls.
        final String view = layout.substring(tag, layout.indexOf("/>", id));
        assertTrue(view.contains("android:scrollbars=\"vertical\""));
    }

    @Test
    public void the_quiet_list_only_holds_back_the_repeated_visible_report()
    {
        final String body = squeeze(read(JAVA + "AirListView.java"));
        assertTrue(body.contains("public void onVisibilityAggregated(final boolean isVisible) { scrollbarsQuiet ="
                                 + " !comesIntoView(visible, isVisible); visible = isVisible; try {"
                                 + " super.onVisibilityAggregated(isVisible); } finally { scrollbarsQuiet = false; } }"));
        assertTrue(body.contains("protected boolean awakenScrollBars(final int startDelay, final boolean invalidate)"
                                 + " { return !scrollbarsQuiet && super.awakenScrollBars(startDelay, invalidate); }"));
    }

    @Test
    public void a_refresh_at_the_top_goes_back_there_through_the_layout_manager()
    {
        final String render = squeeze(methodBody(read(JAVA + "AirFragment.java"), "private void render()"));
        assertTrue(render.contains("final boolean atTop = list != null && !list.canScrollVertically(-1);"));
        assertTrue(render.contains("AirListView.keepsTop(atTop, !list.canScrollVertically(-1))) {"
                                   + " list.getLayoutManager().scrollToPosition(0); }"));
        assertEquals(-1, render.indexOf("list.scrollToPosition("));
    }

    private static String methodBody(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head, at >= 0);
        int depth = 0;
        for (int i = text.indexOf('{', at); i < text.length(); i++)
        {
            if (text.charAt(i) == '{')
            {
                depth++;
            }
            else if (text.charAt(i) == '}' && --depth == 0)
            {
                return text.substring(at, i + 1);
            }
        }
        throw new AssertionError(head + " has no end");
    }

    private static String squeeze(final String text)
    {
        return text.replaceAll("(?m)//.*$", "").replaceAll("\\s+", " ").trim();
    }

    private static String read(final String path)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/", "app/src/main/"})
        {
            final File f = new File(root + path);
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
        throw new AssertionError(path + " not found from " + new File("").getAbsolutePath());
    }
}

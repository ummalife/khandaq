package com.zoffcc.applications.trifa;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * KHANDAQ (tester 23.11, Android topic): sending a photo, the attach sheet did not open fully and Send was reached
 * only by scrolling down. The sheet opens whole, the Send of a selection floats over the grid of recent photos
 * instead of waiting under the row of chips, and the chip names keep to one line. Views cannot be built in a JVM
 * test here, so this reads the layout and the sources, as the other audit tests do.
 */
public class AttachSheetInReachTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String LAYOUT = "src/main/res/layout/bottomsheet_attach.xml";
    private static final String[] CHIPS = {"@+id/attach_chip_camera", "@+id/attach_chip_video",
            "@+id/attach_chip_gallery", "@+id/attach_chip_file", "@+id/attach_chip_location"};

    @Test
    public void the_send_of_a_selection_floats_over_the_grid()
    {
        final Document layout = xml(LAYOUT);
        final Element grid = byId(layout, "@+id/attach_media_grid");
        final Element send = byId(layout, "@+id/attach_send_selected");
        // One frame holds both: the button is on screen whenever the photos are, and showing it adds no height.
        final Element frame = (Element) send.getParentNode();
        assertSame(frame, grid.getParentNode());
        assertEquals("FrameLayout", frame.getTagName());
        assertEquals("wrap_content", frame.getAttribute("android:layout_height"));
        assertEquals("bottom|end", send.getAttribute("android:layout_gravity"));
        assertEquals("a small button, not a bar", "wrap_content", send.getAttribute("android:layout_width"));
        assertEquals("gone", send.getAttribute("android:visibility"));
        assertEquals("gone", grid.getAttribute("android:visibility"));
        // The button stands over the grid, not over the gap under it.
        final double overGrid = dp(send.getAttribute("android:layout_marginBottom"))
                - dp(grid.getAttribute("android:layout_marginBottom"));
        assertTrue(overGrid >= 0);
        // The last row of photos can scroll clear of the button.
        assertEquals("false", grid.getAttribute("android:clipToPadding"));
        assertTrue(dp(grid.getAttribute("android:paddingBottom"))
                >= overGrid + dp(send.getAttribute("android:layout_height")));
    }

    @Test
    public void nothing_of_the_sheet_waits_under_the_chips()
    {
        final Document layout = xml(LAYOUT);
        final Element sheet = layout.getDocumentElement();
        final Node row = byId(layout, CHIPS[0]).getParentNode();
        Element last = null;
        final NodeList children = sheet.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            if (children.item(i) instanceof Element)
            {
                last = (Element) children.item(i);
            }
        }
        assertSame("the row of chips closes the sheet", row, last);
    }

    @Test
    public void the_chip_names_keep_to_one_line()
    {
        final Document layout = xml(LAYOUT);
        for (final String id : CHIPS)
        {
            final Element name = onlyChild(byId(layout, id), "TextView");
            assertEquals(id, "1", name.getAttribute("android:maxLines"));
            // The width is the chip's fifth of the row, and the name shrinks into it before it is cut.
            assertEquals(id, "match_parent", name.getAttribute("android:layout_width"));
            assertEquals(id, "uniform", name.getAttribute("app:autoSizeTextType"));
            assertEquals(id, name.getAttribute("android:textSize"), name.getAttribute("app:autoSizeMaxTextSize"));
            // The Russian word for Location fits a fifth of a 320dp screen with text at 115% only at 9sp.
            assertTrue(id, dp(name.getAttribute("app:autoSizeMinTextSize")) >= 9.0);
            assertEquals(id, "end", name.getAttribute("android:ellipsize"));
        }
    }

    @Test
    public void both_chats_open_the_sheet_whole()
    {
        for (final String activity : new String[]{"MessageListActivity.java", "GroupMessageListActivity.java"})
        {
            final String attach = method(read(JAVA + activity), "public void send_attatchment(View view)");
            assertTrue(activity, attach.contains("R.layout.bottomsheet_attach"));
            assertTrue(activity, attach.contains("AttachSheet.showWhole(sheet);"));
            assertFalse(activity, attach.contains("sheet.show();"));
        }
        final String show = method(read(JAVA + "AttachSheet.java"), "static void showWhole(final BottomSheetDialog sheet)");
        final int skip = show.indexOf("behavior.setSkipCollapsed(true);");
        final int expand = show.indexOf("behavior.setState(BottomSheetBehavior.STATE_EXPANDED);");
        final int open = show.indexOf("sheet.show();");
        assertTrue(skip > 0);
        assertTrue(expand > 0);
        assertTrue("the state is set before the sheet shows", open > Math.max(skip, expand));
    }

    private static Element onlyChild(final Element parent, final String tag)
    {
        Element found = null;
        final NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++)
        {
            assertTrue(parent.getAttribute("android:id") + " has one " + tag, found == null);
            found = (Element) children.item(i);
        }
        if (found == null)
        {
            fail(parent.getAttribute("android:id") + " has no " + tag);
        }
        return found;
    }

    /** "232dp" or "13sp" as a number. */
    private static double dp(final String value)
    {
        assertTrue(value, value.endsWith("dp") || value.endsWith("sp"));
        return Double.parseDouble(value.substring(0, value.length() - 2));
    }

    private static Element byId(final Document layout, final String id)
    {
        final NodeList nodes = layout.getElementsByTagName("*");
        Element found = null;
        for (int i = 0; i < nodes.getLength(); i++)
        {
            final Element element = (Element) nodes.item(i);
            if (id.equals(element.getAttribute("android:id")))
            {
                assertTrue("one view " + id, found == null);
                found = element;
            }
        }
        if (found == null)
        {
            fail("no view " + id);
        }
        return found;
    }

    private static Document xml(final String name)
    {
        try
        {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(name));
        }
        catch (Exception e)
        {
            throw new AssertionError("cannot read " + name, e);
        }
    }

    /** The body of the method whose declaration starts with {@code signature}. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start > 0);
        assertEquals(signature + " is declared once", start, source.lastIndexOf(signature));
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String name)
    {
        try
        {
            return new String(Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        catch (IOException e)
        {
            throw new AssertionError(name + ": " + e.getMessage());
        }
    }

    private static File file(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                return f;
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

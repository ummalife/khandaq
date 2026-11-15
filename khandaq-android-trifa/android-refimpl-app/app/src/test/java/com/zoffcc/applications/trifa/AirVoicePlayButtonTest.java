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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * KHANDAQ (0.2.56, QA live 2026-11-14): the owner asked for a play icon as in Telegram instead of the word
 * Listen on a voice note, on every platform. The desktop draws it in AirPlayButton; the phone matches it:
 * a 36dp ink disc with an on-ink triangle (11 by 13, 1.5 past the centre, round corners), two round-ended
 * bars (3.5 by 12, 4 apart) while the note plays, sunken with an ink-3 mark while it waits, the pressed and
 * hover inks, not mirrored right to left, and the words as what TalkBack says. Views cannot be built in a
 * JVM test here, so this reads the resources and the sources, as the other audit tests do.
 */
public class AirVoicePlayButtonTest
{
    private static final String DIR = "src/main/";
    private static final String RES = "res/";
    private static final String PLAYER = "java/me/jagar/chatvoiceplayerlibrary/";
    private static final double EPSILON = 1e-9;
    /** The disc the glyphs are drawn for, as Size::control on the desktop. */
    private static final double DISC = 36.0;

    @Test
    public void listen_and_pause_are_image_buttons_named_by_their_words()
    {
        final Document layout = xml(RES + "layout/voiceplayerview_main_view.xml");
        final Element play = byId(layout, "@+id/imgPlay");
        final Element pause = byId(layout, "@+id/imgPause");
        for (final Element button : new Element[]{play, pause})
        {
            // An ImageButton is the ImageView both players take the ids for, and TalkBack calls it a button.
            assertEquals("ImageButton", button.getTagName());
            assertEquals("@style/Widget.KQ.Air.VoiceButton", button.getAttribute("style"));
            assertFalse("the words are not drawn", button.hasAttribute("android:text"));
        }
        assertEquals("@string/air_voice_listen", play.getAttribute("android:contentDescription"));
        assertEquals("@drawable/air_voice_ic_play", play.getAttribute("android:src"));
        assertEquals("@string/air_voice_pause", pause.getAttribute("android:contentDescription"));
        assertEquals("@drawable/air_voice_ic_pause", pause.getAttribute("android:src"));
        // At rest the note shows the triangle; the players swap the two while it plays.
        assertFalse(play.hasAttribute("android:visibility"));
        assertEquals("gone", pause.getAttribute("android:visibility"));
    }

    @Test
    public void the_target_is_a_finger_high_with_the_disc_at_its_start()
    {
        final Element style = style(xml(RES + "values/air_file_styles.xml"), "Widget.KQ.Air.VoiceButton");
        assertEquals("@dimen/kq_size_target_android", item(style, "android:layout_width"));
        assertEquals("@dimen/kq_size_target_android", item(style, "android:layout_height"));
        assertEquals("@drawable/air_voice_bg_play", item(style, "android:background"));
        assertEquals("center", item(style, "android:scaleType"));
        assertEquals("@color/air_button_ink_text", item(style, "android:tint"));

        final double target = dp(item(style, "android:layout_width"));
        assertTrue(target >= 48.0);
        assertEquals(DISC, dp("@dimen/kq_size_control"), EPSILON);
        // The padding leaves the glyph a box the size of the disc: at the start (relative padding, so it
        // follows a right-to-left layout) and in the middle from top to bottom, where the disc is drawn.
        final double start = dp(item(style, "android:paddingStart"));
        final double end = dp(item(style, "android:paddingEnd"));
        final double top = dp(item(style, "android:paddingTop"));
        final double bottom = dp(item(style, "android:paddingBottom"));
        assertEquals(0.0, start, EPSILON);
        assertEquals(DISC, target - start - end, EPSILON);
        assertEquals(top, bottom, EPSILON);
        assertEquals(DISC, target - top - bottom, EPSILON);

        final Element disc = onlyChild(xml(RES + "drawable/air_voice_bg_play.xml").getDocumentElement(), "item");
        assertEquals("@dimen/kq_size_control", disc.getAttribute("android:width"));
        assertEquals("@dimen/kq_size_control", disc.getAttribute("android:height"));
        assertEquals("start|center_vertical", disc.getAttribute("android:gravity"));

        // The rest of the target is the gap before the wave, about the 10 of the desktop.
        final Element wave = byId(xml(RES + "layout/voiceplayerview_main_view.xml"), "@+id/containerLinearLayout");
        assertFalse(wave.hasAttribute("android:layout_marginStart"));
        assertEquals(12.0, end, EPSILON);
    }

    @Test
    public void the_disc_is_ink_with_its_hover_and_pressed_inks_and_sunken_while_the_note_waits()
    {
        final Element disc = onlyChild(xml(RES + "drawable/air_voice_bg_play.xml").getDocumentElement(), "item");
        final List<Element> states = children(onlyChild(disc, "selector"), "item");
        assertEquals(5, states.size());
        assertDisc(states.get(0), "android:state_enabled", "false", "@color/kq_sunken");
        assertDisc(states.get(1), "android:state_pressed", "true", "@color/kq_ink_pressed");
        assertDisc(states.get(2), "android:state_hovered", "true", "@color/kq_ink_hover");
        assertDisc(states.get(3), "android:state_focused", "true", "@color/kq_ink");
        assertDisc(states.get(4), null, null, "@color/kq_ink");
        // The focus ring under a keyboard, as on the other ink buttons.
        final Element ring = onlyChild(onlyChild(states.get(3), "shape"), "stroke");
        assertEquals("@color/kq_focus", ring.getAttribute("android:color"));
        assertEquals("@dimen/kq_stroke_focus", ring.getAttribute("android:width"));

        // The mark: on-ink on the disc, ink-3 on the sunken one of a note that waits.
        final List<Element> marks = children(xml(RES + "color/air_button_ink_text.xml").getDocumentElement(), "item");
        assertEquals(2, marks.size());
        assertEquals("false", marks.get(0).getAttribute("android:state_enabled"));
        assertEquals("@color/kq_ink_3", marks.get(0).getAttribute("android:color"));
        assertFalse(marks.get(1).hasAttribute("android:state_enabled"));
        assertEquals("@color/kq_on_ink", marks.get(1).getAttribute("android:color"));

        // Light and dark have each of them, and a press or a pointer shows on the disc.
        for (final String values : new String[]{"values", "values-night"})
        {
            final Document colours = xml(RES + values + "/kq_colors.xml");
            final String ink = colour(colours, "kq_ink");
            assertNotEquals(values, ink, colour(colours, "kq_ink_pressed"));
            assertNotEquals(values, ink, colour(colours, "kq_ink_hover"));
            assertNotEquals(values, ink, colour(colours, "kq_on_ink"));
            assertNotEquals(values, colour(colours, "kq_sunken"), colour(colours, "kq_ink_3"));
            colour(colours, "kq_focus");
        }
    }

    @Test
    public void the_triangle_is_11_by_13_past_the_centre_with_round_corners()
    {
        final Element path = onlyChild(glyph("air_voice_ic_play.xml"), "path");
        final List<List<double[]>> shapes = subpaths(path.getAttribute("android:pathData"));
        assertEquals(1, shapes.size());
        final List<double[]> corners = shapes.get(0);
        assertEquals(3, corners.size());
        assertTrue(path.getAttribute("android:pathData").trim().endsWith("Z"));

        double left = Double.MAX_VALUE, right = -Double.MAX_VALUE, top = Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
        for (final double[] corner : corners)
        {
            left = Math.min(left, corner[0]);
            right = Math.max(right, corner[0]);
            top = Math.min(top, corner[1]);
            bottom = Math.max(bottom, corner[1]);
        }
        assertEquals(11.0, right - left, EPSILON);
        assertEquals(13.0, bottom - top, EPSILON);
        assertEquals(DISC / 2, (top + bottom) / 2, EPSILON);
        // Its weight sits in its left half, so it moves 1.5 past the centre of the disc.
        assertEquals(DISC / 2 + 1.5, (left + right) / 2, EPSILON);
        // It points forward, to the right in either direction: one corner at the far end on the middle line,
        // the other two on the back edge.
        int back = 0;
        for (final double[] corner : corners)
        {
            if (corner[0] == right)
            {
                assertEquals(DISC / 2, corner[1], EPSILON);
            }
            else
            {
                assertEquals(left, corner[0], EPSILON);
                back++;
            }
        }
        assertEquals(2, back);

        // Filled and stroked in the same colour, so the stroke rounds its corners.
        assertEquals("@color/kq_on_ink", path.getAttribute("android:fillColor"));
        assertEquals("@color/kq_on_ink", path.getAttribute("android:strokeColor"));
        assertEquals(2.0, Double.parseDouble(path.getAttribute("android:strokeWidth")), EPSILON);
        assertEquals("round", path.getAttribute("android:strokeLineJoin"));
    }

    @Test
    public void the_bars_are_3_5_by_12_with_round_ends_4_apart()
    {
        final Element path = onlyChild(glyph("air_voice_ic_pause.xml"), "path");
        final double width = Double.parseDouble(path.getAttribute("android:strokeWidth"));
        assertEquals(3.5, width, EPSILON);
        // Each bar is a vertical line stroked with round caps: a round end of half its width at both ends.
        assertEquals("round", path.getAttribute("android:strokeLineCap"));
        assertEquals("#00000000", path.getAttribute("android:fillColor"));
        assertEquals("@color/kq_on_ink", path.getAttribute("android:strokeColor"));

        final List<List<double[]>> bars = subpaths(path.getAttribute("android:pathData"));
        assertEquals(2, bars.size());
        final double[] middles = new double[2];
        for (int i = 0; i < 2; i++)
        {
            final List<double[]> bar = bars.get(i);
            assertEquals(2, bar.size());
            assertEquals("a bar stands upright", bar.get(0)[0], bar.get(1)[0], EPSILON);
            final double top = Math.min(bar.get(0)[1], bar.get(1)[1]) - width / 2;
            final double bottom = Math.max(bar.get(0)[1], bar.get(1)[1]) + width / 2;
            assertEquals(12.0, bottom - top, EPSILON);
            assertEquals(DISC / 2, (top + bottom) / 2, EPSILON);
            middles[i] = bar.get(0)[0];
        }
        final double gap = (Math.max(middles[0], middles[1]) - width / 2) - (Math.min(middles[0], middles[1]) + width / 2);
        assertEquals(4.0, gap, EPSILON);
        assertEquals(DISC / 2, (middles[0] + middles[1]) / 2, EPSILON);
    }

    @Test
    public void both_players_keep_the_ids_the_swap_and_the_long_press()
    {
        for (final String player : new String[]{"VoicePlayerView.java", "vVoicePlayerView.java"})
        {
            final String source = read(PLAYER + player);
            assertTrue(player, source.contains("inflate(R.layout.voiceplayerview_main_view, this);"));
            // The ids are ImageButtons: a TextView field would fail at run time with a ClassCastException.
            assertTrue(player, source.contains("private ImageView imgPlay, imgPause;"));
            assertFalse(player, source.contains("TextView imgPlay"));
            assertFalse(player, source.contains("TextView imgPause"));
            assertTrue(player, source.contains("imgPlay = this.findViewById(R.id.imgPlay);"));
            assertTrue(player, source.contains("imgPause = this.findViewById(R.id.imgPause);"));
            assertTrue(player, source.contains("imgPlay.setOnClickListener(imgPlayClickListener);"));
            assertTrue(player, source.contains("imgPause.setOnClickListener(imgPauseClickListener);"));
            assertTrue(player, source.contains("imgPause.setVisibility(playing ? View.VISIBLE : View.GONE);"));
            assertTrue(player, source.contains("imgPlay.setVisibility(playing ? View.GONE : View.VISIBLE);"));
            // A long press on the button still selects the note (#192): the player takes the gesture over.
            assertTrue(player, source.contains("return true; // steal the rest of the gesture from the play button / seekbar"));
            assertTrue(player, source.contains("t.performLongClick();"));
        }
    }

    @Test
    public void the_words_stay_in_the_four_languages_for_talkback()
    {
        for (final String values : new String[]{"values", "values-ru", "values-ar", "values-zh-rCN"})
        {
            final Document strings = xml(RES + values + "/strings_air_files.xml");
            for (final String name : new String[]{"air_voice_listen", "air_voice_pause"})
            {
                assertFalse(values + "/" + name, named(strings, "string", name).getTextContent().trim().isEmpty());
            }
        }
    }

    // ---- helpers ----

    /** A glyph is drawn for the 36dp disc, scales with it, and keeps its direction right to left. */
    private static Element glyph(final String name)
    {
        final Element vector = xml(RES + "drawable/" + name).getDocumentElement();
        assertEquals("vector", vector.getTagName());
        assertEquals("@dimen/kq_size_control", vector.getAttribute("android:width"));
        assertEquals("@dimen/kq_size_control", vector.getAttribute("android:height"));
        assertEquals(DISC, Double.parseDouble(vector.getAttribute("android:viewportWidth")), EPSILON);
        assertEquals(DISC, Double.parseDouble(vector.getAttribute("android:viewportHeight")), EPSILON);
        assertEquals("false", vector.getAttribute("android:autoMirrored"));
        return vector;
    }

    private static void assertDisc(final Element state, final String attribute, final String value,
                                   final String colour)
    {
        int attributes = 0;
        for (int i = 0; i < state.getAttributes().getLength(); i++)
        {
            if (state.getAttributes().item(i).getNodeName().startsWith("android:state_"))
            {
                attributes++;
            }
        }
        if (attribute == null)
        {
            assertEquals("the last item is the disc at rest", 0, attributes);
        }
        else
        {
            assertEquals(1, attributes);
            assertEquals(value, state.getAttribute(attribute));
        }
        final Element shape = onlyChild(state, "shape");
        assertEquals("oval", shape.getAttribute("android:shape"));
        assertEquals(colour, onlyChild(shape, "solid").getAttribute("android:color"));
    }

    /** The subpaths of a path written in absolute M, L, H, V and Z, as their points. */
    private static List<List<double[]>> subpaths(final String data)
    {
        assertTrue("absolute M, L, H, V and Z only: " + data, data.matches("[MLHVZ0-9.,\\s-]+"));
        final List<List<double[]>> out = new ArrayList<>();
        final Matcher command = Pattern.compile("([MLHVZ])([^MLHVZ]*)").matcher(data);
        List<double[]> current = null;
        double[] at = null;
        while (command.find())
        {
            final String args = command.group(2).trim();
            final String[] parts = args.isEmpty() ? new String[0] : args.split("[,\\s]+");
            final double[] n = new double[parts.length];
            for (int i = 0; i < parts.length; i++)
            {
                n[i] = Double.parseDouble(parts[i]);
            }
            switch (command.group(1))
            {
                case "M":
                    assertEquals(data, 2, n.length);
                    current = new ArrayList<>();
                    out.add(current);
                    at = new double[]{n[0], n[1]};
                    break;
                case "L":
                    assertEquals(data, 2, n.length);
                    at = new double[]{n[0], n[1]};
                    break;
                case "H":
                    assertEquals(data, 1, n.length);
                    at = new double[]{n[0], at[1]};
                    break;
                case "V":
                    assertEquals(data, 1, n.length);
                    at = new double[]{at[0], n[0]};
                    break;
                default:
                    assertEquals(data, 0, n.length);
                    continue;
            }
            if (current == null)
            {
                fail("a path starts with M: " + data);
            }
            current.add(at);
        }
        return out;
    }

    /** A dimension in dp: a literal one or a kq_dimens token. */
    private static double dp(final String value)
    {
        String literal = value;
        if (value.startsWith("@dimen/"))
        {
            literal = named(xml(RES + "values/kq_dimens.xml"), "dimen", value.substring("@dimen/".length()))
                    .getTextContent().trim();
        }
        assertTrue(value + " = " + literal, literal.endsWith("dp"));
        return Double.parseDouble(literal.substring(0, literal.length() - 2));
    }

    private static String colour(final Document colours, final String name)
    {
        final String value = named(colours, "color", name).getTextContent().trim();
        assertTrue(name + " = " + value, value.matches("#[0-9A-Fa-f]{6}|#[0-9A-Fa-f]{8}"));
        return value.toUpperCase();
    }

    private static Element style(final Document styles, final String name)
    {
        return named(styles, "style", name);
    }

    private static String item(final Element style, final String name)
    {
        return named(style, "item", name).getTextContent().trim();
    }

    private static Element named(final Node parent, final String tag, final String name)
    {
        final NodeList nodes = parent instanceof Document ? ((Document) parent).getElementsByTagName(tag)
                                                          : ((Element) parent).getElementsByTagName(tag);
        Element found = null;
        for (int i = 0; i < nodes.getLength(); i++)
        {
            final Element element = (Element) nodes.item(i);
            if (name.equals(element.getAttribute("name")))
            {
                assertTrue("one " + tag + " " + name, found == null);
                found = element;
            }
        }
        if (found == null)
        {
            fail("no " + tag + " " + name);
        }
        return found;
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

    private static List<Element> children(final Element parent, final String tag)
    {
        final List<Element> out = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
        {
            if (child instanceof Element && tag.equals(((Element) child).getTagName()))
            {
                out.add((Element) child);
            }
        }
        return out;
    }

    private static Element onlyChild(final Element parent, final String tag)
    {
        final List<Element> found = children(parent, tag);
        assertEquals("one " + tag + " in " + parent.getTagName(), 1, found.size());
        return found.get(0);
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

    private static String read(final String name)
    {
        try
        {
            return new String(Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        catch (IOException e)
        {
            throw new AssertionError("cannot read " + name, e);
        }
    }

    private static File file(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + DIR + name);
            if (f.isFile())
            {
                return f;
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.zoffcc.applications.trifa.NoNetworkNotice.BACK_ONLINE_MS;
import static com.zoffcc.applications.trifa.NoNetworkNotice.CONNECTING_CAP_MS;
import static com.zoffcc.applications.trifa.NoNetworkNotice.Phase.BACK_ONLINE;
import static com.zoffcc.applications.trifa.NoNetworkNotice.Phase.CONNECTING;
import static com.zoffcc.applications.trifa.NoNetworkNotice.Phase.HIDDEN;
import static com.zoffcc.applications.trifa.NoNetworkNotice.Phase.NO_NETWORK;
import static com.zoffcc.applications.trifa.NoNetworkNotice.SHOW_AFTER_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57, SPEC-network part 2): the timing of the "no network" band (SPEC Tests: nothing for an outage
 * under 3 s; offline, connecting, back online, hidden; the 30 s cap).
 */
public class NoNetworkNoticeTest
{
    private static final long T0 = 1_000_000L;
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";

    @Test
    public void the_timing_is_the_spec_s()
    {
        assertEquals(3_000L, SHOW_AFTER_MS);
        assertEquals(1_500L, BACK_ONLINE_MS);
        assertEquals(30_000L, CONNECTING_CAP_MS);
    }

    @Test
    public void an_outage_under_3_s_shows_nothing()
    {
        final NoNetworkNotice n = new NoNetworkNotice();
        n.node(true, T0);
        assertEquals(HIDDEN, n.network(false, T0));
        assertEquals(T0 + SHOW_AFTER_MS, n.nextAt());
        assertEquals(HIDDEN, n.advance(T0 + SHOW_AFTER_MS - 1));
        // A handover: the next network within 3 s.
        assertEquals(HIDDEN, n.network(true, T0 + SHOW_AFTER_MS - 1));
        assertEquals(-1L, n.nextAt());
        assertEquals(HIDDEN, n.advance(T0 + 60_000L));
    }

    @Test
    public void no_network_then_connecting_then_back_online_then_hidden()
    {
        final NoNetworkNotice n = new NoNetworkNotice();
        n.node(true, T0);
        n.network(false, T0);
        assertEquals(NO_NETWORK, n.advance(T0 + SHOW_AFTER_MS));
        // It stays while there is no network, whatever our node said last.
        assertEquals(NO_NETWORK, n.node(false, T0 + 30_000L));
        assertEquals(NO_NETWORK, n.advance(T0 + 3_600_000L));
        assertEquals(-1L, n.nextAt());
        // The network is back, our node not yet.
        final long back = T0 + 3_600_000L;
        assertEquals(CONNECTING, n.network(true, back));
        assertEquals(back + CONNECTING_CAP_MS, n.nextAt());
        // Our node is online.
        final long online = back + 4_000L;
        assertEquals(BACK_ONLINE, n.node(true, online));
        assertEquals(online + BACK_ONLINE_MS, n.nextAt());
        assertEquals(BACK_ONLINE, n.advance(online + BACK_ONLINE_MS - 1));
        assertEquals(HIDDEN, n.advance(online + BACK_ONLINE_MS));
        assertEquals(-1L, n.nextAt());
    }

    @Test
    public void a_node_that_never_noticed_the_cut_is_back_online_at_once()
    {
        final NoNetworkNotice n = new NoNetworkNotice();
        n.node(true, T0);
        n.network(false, T0);
        n.advance(T0 + 10_000L);
        assertEquals(BACK_ONLINE, n.network(true, T0 + 12_000L));
        assertEquals(HIDDEN, n.advance(T0 + 12_000L + BACK_ONLINE_MS));
    }

    @Test
    public void after_30_s_of_connecting_the_band_goes_anyway()
    {
        final NoNetworkNotice n = new NoNetworkNotice();
        n.network(false, T0);
        n.advance(T0 + SHOW_AFTER_MS);
        final long back = T0 + 50_000L;
        assertEquals(CONNECTING, n.network(true, back));
        assertEquals(CONNECTING, n.advance(back + CONNECTING_CAP_MS - 1));
        assertEquals(HIDDEN, n.advance(back + CONNECTING_CAP_MS));
        // Our node online later: the band does not come back to say so.
        assertEquals(HIDDEN, n.node(true, back + CONNECTING_CAP_MS + 5_000L));
        assertEquals(-1L, n.nextAt());
    }

    @Test
    public void a_quick_drop_while_connecting_keeps_the_band_as_it_is()
    {
        final NoNetworkNotice n = new NoNetworkNotice();
        n.network(false, T0);
        n.advance(T0 + SHOW_AFTER_MS);
        final long back = T0 + 20_000L;
        n.network(true, back);
        assertEquals(CONNECTING, n.network(false, back + 1_000L));
        assertEquals(back + 1_000L + SHOW_AFTER_MS, n.nextAt());
        assertEquals(CONNECTING, n.network(true, back + 2_000L));
        assertEquals(BACK_ONLINE, n.node(true, back + 5_000L));
        // A longer one is "No network" again.
        final long lost = back + 5_500L;
        n.network(false, lost);
        assertEquals(HIDDEN, n.advance(back + 5_000L + BACK_ONLINE_MS));
        assertEquals(NO_NETWORK, n.advance(lost + SHOW_AFTER_MS));
    }

    @Test
    public void connecting_with_a_network_is_not_this_notice()
    {
        final NoNetworkNotice n = new NoNetworkNotice();
        assertEquals(HIDDEN, n.node(false, T0));
        assertEquals(HIDDEN, n.node(true, T0 + 1_000L));
        assertEquals(HIDDEN, n.node(false, T0 + 2_000L));
        assertEquals(HIDDEN, n.advance(T0 + 120_000L));
        assertEquals(-1L, n.nextAt());
    }

    @Test
    public void a_start_without_a_network_shows_the_band_after_3_s()
    {
        final NoNetworkNotice n = new NoNetworkNotice();
        assertEquals(HIDDEN, n.network(false, T0));
        assertEquals(NO_NETWORK, n.advance(T0 + SHOW_AFTER_MS));
        // The same report again changes nothing.
        assertEquals(NO_NETWORK, n.network(false, T0 + 10_000L));
    }

    // ---- the band on the phone (AirNoNetworkBand) ----

    @Test
    public void the_band_stands_under_the_header_of_the_list_and_of_a_chat()
    {
        // Under the header and above what it pushes down: the list, the conversation with its message field.
        between("layout/fragment_air.xml", "android:id=\"@+id/air_pills_scroll\"", "android:id=\"@+id/air_list\"");
        between("layout/activity_message_list.xml", "android:id=\"@+id/air_chat_rule\"",
                "android:id=\"@+id/air_chat_body\"");
        between("layout/activity_group_message_list.xml", "android:id=\"@+id/air_group_rule\"",
                "android:id=\"@+id/drawer_container\"");
    }

    @Test
    public void the_band_keeps_to_the_tokens_and_speaks_once_without_a_sound()
    {
        final String layout = read(RES + "layout/air_no_network_band.xml");
        assertTrue(layout.contains("<com.zoffcc.applications.trifa.AirNoNetworkBand"));
        assertTrue(layout.contains("android:background=\"@color/kq_sunken\""));
        assertTrue(layout.contains("android:src=\"@drawable/kq_ic_wifi_off\""));
        assertTrue("it starts gone", layout.contains("android:visibility=\"gone\""));
        assertFalse("no colour of its own", layout.matches("(?s).*\"#[0-9A-Fa-f]{3,8}\".*"));

        final String band = read(JAVA + "AirNoNetworkBand.java");
        assertTrue(band.contains("rule.setColor(AirUi.color(context, R.color.kq_line));"));
        assertTrue(band.contains("image = R.drawable.kq_ic_clock;"));
        assertTrue(band.contains("image = R.drawable.kq_ic_check;"));
        assertTrue(band.contains("image = R.drawable.kq_ic_wifi_off;"));
        // The title once, as the band comes; nothing for a screen that opens with the band up.
        assertEquals(1, count(band, "announceForAccessibility("));
        assertTrue(band.contains("if (event && (before == HIDDEN) && coming)"));
        assertTrue(band.contains("present(HIDDEN, phase, false);"));
        for (final String noise : new String[]{"Vibrat", "performHapticFeedback", "playSoundEffect", "MediaPlayer",
                                               "Ringtone", "SoundPool"})
        {
            assertFalse(noise, band.contains(noise));
        }
        // Reduced motion: nothing travels, the words fade within AirUi's 120 ms cap.
        assertTrue(band.contains("final boolean reduced = AirUi.reducedMotion();"));
        assertTrue(band.contains("animator.setDuration(AirUi.duration(getContext(), duration));"));
    }

    @Test
    public void the_system_s_network_and_our_node_feed_the_band()
    {
        final String monitor = bare(read(JAVA + "NetworkConnectivityMonitor.java"));
        assertTrue(monitor.contains(bare("public void onAvailable(final Network network)"
                                         + " { AirNoNetworkBand.networkChanged(true);")));
        assertTrue(monitor.contains(bare("defaultNetworkLost = true; AirNoNetworkBand.networkChanged(false);")));
        assertTrue("a start without a network", monitor.contains(
                bare("if (cm.getActiveNetwork() == null) { AirNoNetworkBand.networkChanged(false); }")));

        final String main = read(JAVA + "MainActivity.java");
        final int callback = main.indexOf("static void android_tox_callback_self_connection_status_cb_method(");
        assertTrue(callback > 0);
        final String body = main.substring(callback, main.indexOf("\n    }\n", callback));
        assertTrue(body.contains("AirNoNetworkBand.nodeChanged();"));
    }

    @Test
    public void the_words_are_the_spec_s_and_every_language_of_the_network_line_has_them()
    {
        assertEquals("No network", stringOf("values", "air_network_title_offline"));
        // The SPEC's "Connecting" ends in U+2026, which the glyph check keeps out; the network line's word is it.
        assertEquals("Connecting", stringOf("values", "air_network_title_connecting"));
        assertEquals("Back online", stringOf("values", "air_no_network_back"));
        assertEquals("Messages and files will wait and go on their own once you\\'re back online.",
                     stringOf("values", "air_no_network_note"));
        // The translations themselves live in the resources only; here: there is one, and it is not English.
        for (final String folder : new String[]{"values-ru", "values-ar", "values-zh-rCN"})
        {
            for (final String name : new String[]{"air_no_network_back", "air_no_network_note"})
            {
                final String translated = stringOf(folder, name);
                assertFalse(folder + " " + name, translated.trim().isEmpty());
                assertNotEquals(folder + " " + name, stringOf("values", name), translated);
            }
        }
    }

    private static void between(final String layout, final String above, final String below)
    {
        final String text = read(RES + layout);
        final String include = "<include layout=\"@layout/air_no_network_band\" />";
        assertEquals(layout, 1, count(text, include));
        final int at = text.indexOf(include);
        assertTrue(layout + ": under the header", text.indexOf(above) < at);
        assertTrue(layout + ": above what it pushes down", text.indexOf(below) > at);
    }

    private static String stringOf(final String folder, final String name)
    {
        final String strings = read(RES + folder + "/strings_air.xml");
        // The name, then any attribute (tools:ignore) up to the end of the tag.
        final int start = strings.indexOf("<string name=\"" + name + "\"");
        assertTrue(folder + " " + name, start >= 0);
        final int text = strings.indexOf('>', start) + 1;
        return strings.substring(text, strings.indexOf("</string>", text));
    }

    private static int count(final String text, final String part)
    {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1))
        {
            found++;
        }
        return found;
    }

    private static String bare(final String source)
    {
        return source.replaceAll("\\s+", "");
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

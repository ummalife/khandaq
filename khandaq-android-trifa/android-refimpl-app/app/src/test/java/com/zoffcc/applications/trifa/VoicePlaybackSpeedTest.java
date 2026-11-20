package com.zoffcc.applications.trifa;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (testers' ideas, 0.2.67): voice notes play at 1x, 1.5x or 2x. The pill on the note that plays goes to
 * the next speed; the speed is one for the whole app, kept across restarts, and the pitch stays.
 */
public class VoicePlaybackSpeedTest
{
    private static final String JAVA = "src/main/java/";
    private static final String TRIFA = JAVA + "com/zoffcc/applications/trifa/";
    private static final String PLAYER = JAVA + "me/jagar/chatvoiceplayerlibrary/";
    private static final String RES = "src/main/res/";
    /** U+00D7, written by its number: the glyph itself is not allowed in the sources. */
    private static final char MULTIPLICATION_SIGN = (char) 0x00D7;

    @After
    public void back_to_normal()
    {
        VoicePlaybackSpeed.current = VoicePlaybackSpeed.NORMAL;
    }

    @Test
    public void a_tap_goes_from_one_to_one_and_a_half_to_two_and_back()
    {
        assertEquals(1.5f, VoicePlaybackSpeed.next(1f), 0f);
        assertEquals(2f, VoicePlaybackSpeed.next(1.5f), 0f);
        assertEquals(1f, VoicePlaybackSpeed.next(2f), 0f);
        // A speed that is not a step (a later version's, a broken value) goes back to normal.
        assertEquals(1f, VoicePlaybackSpeed.next(1.25f), 0f);
        assertEquals(1f, VoicePlaybackSpeed.next(Float.NaN), 0f);
    }

    @Test
    public void the_speed_is_kept_for_the_whole_app_across_restarts()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        VoicePlaybackSpeed.load(settings);
        assertEquals(1f, VoicePlaybackSpeed.current, 0f);

        assertEquals(1.5f, VoicePlaybackSpeed.cycle(settings), 0f);
        assertEquals(1.5f, VoicePlaybackSpeed.current, 0f);
        assertEquals(1.5f, (Float) settings.values.get(VoicePlaybackSpeed.KEY), 0f);

        // The next start reads what was kept.
        VoicePlaybackSpeed.current = VoicePlaybackSpeed.NORMAL;
        VoicePlaybackSpeed.load(settings);
        assertEquals(1.5f, VoicePlaybackSpeed.current, 0f);

        assertEquals(2f, VoicePlaybackSpeed.cycle(settings), 0f);
        assertEquals(1f, VoicePlaybackSpeed.cycle(settings), 0f);
        assertEquals(1f, (Float) settings.values.get(VoicePlaybackSpeed.KEY), 0f);
    }

    @Test
    public void a_kept_value_that_is_not_a_speed_counts_as_normal()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        settings.values.put(VoicePlaybackSpeed.KEY, 3.5f);
        VoicePlaybackSpeed.load(settings);
        assertEquals(1f, VoicePlaybackSpeed.current, 0f);

        settings.values.put(VoicePlaybackSpeed.KEY, "fast");
        VoicePlaybackSpeed.current = 2f;
        VoicePlaybackSpeed.load(settings);
        assertEquals(1f, VoicePlaybackSpeed.current, 0f);
    }

    @Test
    public void the_pill_writes_the_number_in_the_language_of_the_app()
    {
        assertEquals("1", VoicePlaybackSpeed.number(Locale.US, 1f));
        assertEquals("1.5", VoicePlaybackSpeed.number(Locale.US, 1.5f));
        assertEquals("2", VoicePlaybackSpeed.number(Locale.US, 2f));
        assertEquals("1,5", VoicePlaybackSpeed.number(new Locale("ru"), 1.5f));
        assertEquals("1.5", VoicePlaybackSpeed.number(Locale.SIMPLIFIED_CHINESE, 1.5f));
    }

    @Test
    public void the_player_takes_the_speed_after_each_start_and_keeps_the_pitch()
    {
        final String manager = read(TRIFA + "ChatVoicePlaybackManager.java");
        // Both starts, the resume of the same note and a new one, set the speed after start().
        final Matcher starts = Pattern.compile("mp\\.start\\(\\);\\s*\\}?\\s*applySpeed\\(\\);").matcher(manager);
        int count = 0;
        while (starts.find())
        {
            count++;
        }
        assertEquals(2, count);
        assertEquals(2, occurrences(manager, "mp.start();"));
        // Only on a playing player: setPlaybackParams with a speed would start a paused one.
        assertTrue(manager.contains("if (mp != null && prepared && mp.isPlaying())\n            {\n"
                                    + "                mp.setPlaybackParams(new PlaybackParams().allowDefaults()"));
        assertTrue(manager.contains(".setSpeed(VoicePlaybackSpeed.current)"));
        assertTrue(manager.contains(".setPitch(1f));"));
        // A tap counts at once on the note that plays.
        final int cycle = manager.indexOf("public static float cycleSpeed(final Context context)");
        assertTrue(cycle > 0);
        final String body = manager.substring(cycle, manager.indexOf("\n    }\n", cycle));
        assertTrue(body.contains("VoicePlaybackSpeed.cycle(PreferenceManager.getDefaultSharedPreferences(context))"));
        assertTrue(body.indexOf("applySpeed();") > body.indexOf("VoicePlaybackSpeed.cycle("));
        assertTrue(body.contains("notifyTickOnUi();"));
        // Kept across restarts: read before any screen.
        assertTrue(read(TRIFA + "MainApplication.java").contains("VoicePlaybackSpeed.load(sp);"));
    }

    @Test
    public void the_pill_stands_on_the_note_that_plays_in_both_directions()
    {
        for (final String player : new String[]{"VoicePlayerView.java", "vVoicePlayerView.java"})
        {
            final String view = read(PLAYER + player);
            assertTrue(player, view.contains("txtSpeed = this.findViewById(R.id.txtSpeed);"));
            assertTrue(player, view.contains("txtSpeed.setOnClickListener(speedClickListener);"));
            assertTrue(player, view.contains("applySpeedUi(playing || (mine && pos > 0));"));
            assertTrue(player, view.contains("ChatVoicePlaybackManager.cycleSpeed(getContext());"));
            assertTrue(player, view.contains("v.announceForAccessibility("));
            assertTrue(player, view.contains("applyPlaybackUi(mine, mine && playing, pos, dur);"));
            assertTrue(player, view.contains("applyPlaybackUi(mine, playing, pos, dur);"));
        }
        final String layout = read(RES + "layout/voiceplayerview_main_view.xml");
        final int pill = layout.indexOf("android:id=\"@+id/txtSpeed\"");
        assertTrue(pill > layout.indexOf("android:id=\"@+id/txtTime\""));
        final String attributes = layout.substring(pill, layout.indexOf("/>", pill));
        assertTrue(attributes.contains("android:minHeight=\"@dimen/kq_size_target_android\""));
        assertTrue(attributes.contains("android:minWidth=\"@dimen/kq_size_target_android\""));
        assertTrue(attributes.contains("android:visibility=\"gone\""));
        assertTrue(attributes.contains("android:background=\"@drawable/air_bg_voice_speed\""));
    }

    @Test
    public void the_pill_is_there_in_all_four_languages()
    {
        final String[][] expected = {
                {"values", "Playback speed %1$s"},
                {"values-ru", "Скорость воспроизведения %1$s"},
                {"values-ar", "سرعة التشغيل %1$s"},
                {"values-zh-rCN", "播放速度 %1$s"},
        };
        for (final String[] language : expected)
        {
            final String strings = read(RES + language[0] + "/strings_air_files.xml");
            // A Latin x after the number: the multiplication sign is not in the design's glyphs.
            assertEquals(language[0], "%1$sx", string(strings, "air_voice_speed_format", language[0]));
            assertFalse(language[0], string(strings, "air_voice_speed_format", language[0])
                                                  .indexOf(MULTIPLICATION_SIGN) >= 0);
            assertEquals(language[0], language[1], string(strings, "air_voice_speed_a11y", language[0]));
        }
    }

    private static int occurrences(final String text, final String part)
    {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
        {
            count++;
        }
        return count;
    }

    private static String string(final String strings, final String name, final String language)
    {
        final Matcher found = Pattern.compile("<string name=\"" + name + "\">([^<]*)</string>").matcher(strings);
        assertTrue(language + " has no " + name, found.find());
        return found.group(1);
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
                    throw new AssertionError(name + ": " + e.getMessage());
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

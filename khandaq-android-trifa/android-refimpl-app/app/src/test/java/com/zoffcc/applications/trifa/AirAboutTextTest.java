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
 * Settings > About (redesign stage 5): the build string wraps only after its own hyphens, toxcore's
 * version reads as on iOS, and the page is built from the parts of Settings with the build details kept
 * to debug builds.
 */
public class AirAboutTextTest
{
    /** The zero-width space AirAboutText puts after a hyphen. */
    private static final String BREAK = String.valueOf((char) 0x200B);

    @Test
    public void the_build_string_may_break_after_each_hyphen()
    {
        assertEquals("1a2b-" + BREAK + "0c3-" + BREAK + "9f1-" + BREAK + "33-" + BREAK + "arm64-" + BREAK + "v8a",
                     AirAboutText.breakableAtSeparators("1a2b-0c3-9f1-33-arm64-v8a"));
        assertEquals("????-" + BREAK + "000-" + BREAK + "000-" + BREAK + "33-" + BREAK + "x86_64",
                     AirAboutText.breakableAtSeparators("????-000-000-33-x86_64"));
    }

    @Test
    public void a_value_without_a_hyphen_or_ending_in_one_is_left_alone()
    {
        assertEquals("0.2.45", AirAboutText.breakableAtSeparators("0.2.45"));
        assertEquals("????????", AirAboutText.breakableAtSeparators("????????"));
        assertEquals("x86_64", AirAboutText.breakableAtSeparators("x86_64"));
        assertEquals("abc-", AirAboutText.breakableAtSeparators("abc-"));
        assertEquals("", AirAboutText.breakableAtSeparators(""));
        assertEquals("", AirAboutText.breakableAtSeparators(null));
    }

    @Test
    public void taking_the_breaks_out_gives_the_build_string_back()
    {
        final String build = "1a2b-0c3-9f1-35-armeabi-v7a";
        assertEquals(build, AirAboutText.breakableAtSeparators(build).replace(BREAK, ""));
    }

    @Test
    public void toxcore_version_reads_as_on_ios()
    {
        assertEquals("0.2.21", AirAboutText.toxcoreVersion(0, 2, 21));
        assertEquals("1.0.0", AirAboutText.toxcoreVersion(1, 0, 0));
    }

    @Test
    public void the_page_is_built_from_the_settings_parts_and_keeps_the_debug_gate() throws Exception
    {
        final String page = read("Aboutpage.java");
        assertTrue(page.contains("extends AirScreenActivity"));
        assertTrue(page.contains("AirScreenKit.addSection("));
        assertTrue(page.contains("if (BuildConfig.DEBUG)"));
        assertTrue(page.contains("AirAboutText.breakableAtSeparators("));
        assertFalse(page.contains("R.layout.activity_about"));
        assertFalse(page.contains("tg_chat_"));
        assertFalse(page.contains("setAllCaps"));
        assertFalse(page.contains("DEBUG_TAP_COUNT"));
    }

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + "src/main/java/com/zoffcc/applications/trifa/" + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

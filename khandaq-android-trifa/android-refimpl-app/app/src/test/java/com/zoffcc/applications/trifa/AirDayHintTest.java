package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * The hint beside the day strip of a conversation: the hours of the past day, or when the person is
 * usually online. The Russian hint read only "past day: from 14 to 15" and left a tester asking what the
 * hours were (0.2.65), so both hints say they are hours online, in every language of the app, as on iOS
 * and the desktop.
 */
public class AirDayHintTest
{
    private static final String DIR = "src/main/res/";

    /** Each language and its word for being online. */
    private static final String[][] ONLINE = {
            {"values", "online"},
            {"values-ru", "в сети"},
            {"values-ar", "متصل"},
            {"values-zh-rCN", "在线"},
    };

    @Test
    public void both_hints_say_the_hours_are_hours_online() throws Exception
    {
        for (final String[] language : ONLINE)
        {
            final String strings = read(language[0] + "/strings_air.xml");
            final String ranges = string(strings, "air_day_hint_ranges_format", language[0]);
            final String best = string(strings, "air_day_best_format", language[0]);
            assertTrue(language[0] + ": " + ranges, ranges.contains(language[1]) && ranges.contains("%1$s"));
            assertTrue(language[0] + ": " + best,
                       best.contains(language[1]) && best.contains("%1$d") && best.contains("%2$d"));
        }
    }

    private static String string(final String strings, final String name, final String language)
    {
        final Matcher found = Pattern.compile("<string name=\"" + name + "\">([^<]*)</string>").matcher(strings);
        assertTrue(language + " has no " + name, found.find());
        return found.group(1);
    }

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + DIR + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

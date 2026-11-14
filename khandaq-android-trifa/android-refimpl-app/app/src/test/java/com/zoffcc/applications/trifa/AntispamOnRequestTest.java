package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA live, F16): the antispam, and so the ID, changes only when the user asks for it.
 *
 * QA: overnight the ID of QA Android changed by itself, and a request sent to the ID read the evening before
 * never arrived. toxcore drops a request whose antispam is not the current one, with nothing shown on either
 * side. HelperFriend.add_friend_to_system gave the profile a new random antispam after every friend added: an
 * accepted request, or a friend's push relay. The switch that kept it is hidden in release builds. The desktop
 * and iOS change the antispam only on request, and DESIGN.md asks first: "Your old ID will stop taking new
 * requests."
 */
public class AntispamOnRequestTest
{
    private static final String PACKAGE_DIR = "com/zoffcc/applications/trifa/";
    private static final String NEW_ANTISPAM = "set_new_random_nospam_value";
    private static final String SET_ANTISPAM = "tox_self_set_nospam";

    @Test
    public void adding_a_friend_does_not_change_the_antispam() throws IOException
    {
        final String helperFriend = code(read(sourceDir(), "HelperFriend.java"));
        assertTrue("add_friend_to_system is in HelperFriend", helperFriend.contains("static void add_friend_to_system("));
        assertFalse(helperFriend.contains(NEW_ANTISPAM));
        assertFalse(helperFriend.contains(SET_ANTISPAM));
    }

    @Test
    public void only_the_profile_screen_asks_for_a_new_antispam() throws IOException
    {
        // HelperGeneric defines the change, ProfileContentFragment calls it from its two asked actions, and
        // MainActivity declares the native setter. Any other file would change the ID behind the user's back.
        assertEquals(Arrays.asList("HelperGeneric.java", "ProfileContentFragment.java"), filesMentioning(NEW_ANTISPAM));
        assertEquals(Arrays.asList("HelperGeneric.java", "MainActivity.java"), filesMentioning(SET_ANTISPAM));
    }

    @Test
    public void comments_do_not_count() throws IOException
    {
        assertEquals("a(); \nb();\nc(\"x//y\");\n",
                     code("a(); // " + NEW_ANTISPAM + "()\n/* " + NEW_ANTISPAM + "()\n */b();\nc(\"x//y\");\n"));
    }

    /** The names of the main sources whose code, comments left out, mentions name followed by "(". */
    private static List<String> filesMentioning(final String name) throws IOException
    {
        final TreeSet<String> files = new TreeSet<>();
        final File dir = sourceDir();
        final File[] all = dir.listFiles();
        assertTrue("sources listed in " + dir, all != null && all.length > 0);
        for (final File file : all)
        {
            final String fileName = file.getName();
            if (file.isFile() && (fileName.endsWith(".java") || fileName.endsWith(".kt"))
                && mentionsCall(code(read(dir, fileName)), name))
            {
                files.add(fileName);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(files));
    }

    private static boolean mentionsCall(final String code, final String name)
    {
        int from = 0;
        while (true)
        {
            final int at = code.indexOf(name, from);
            if (at < 0)
            {
                return false;
            }
            final boolean startsWord = at == 0 || !Character.isJavaIdentifierPart(code.charAt(at - 1));
            int after = at + name.length();
            while (after < code.length() && Character.isWhitespace(code.charAt(after)))
            {
                after++;
            }
            if (startsWord && after < code.length() && code.charAt(after) == '(')
            {
                return true;
            }
            from = at + name.length();
        }
    }

    /**
     * The text without its comments: a block comment goes whole, a line comment up to the line break. Quotes
     * are followed, so "//" inside a string stays. Good enough for these sources, not a Java lexer.
     */
    static String code(final String text)
    {
        final StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        boolean inString = false;
        char quote = 0;
        while (i < text.length())
        {
            final char c = text.charAt(i);
            if (inString)
            {
                out.append(c);
                if (c == '\\' && i + 1 < text.length())
                {
                    out.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == quote)
                {
                    inString = false;
                }
                i++;
            }
            else if (c == '"' || c == '\'')
            {
                inString = true;
                quote = c;
                out.append(c);
                i++;
            }
            else if (text.startsWith("//", i))
            {
                final int end = text.indexOf('\n', i);
                i = end < 0 ? text.length() : end;
            }
            else if (text.startsWith("/*", i))
            {
                final int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            }
            else
            {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static File sourceDir()
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final File dir = new File(root, PACKAGE_DIR);
            if (dir.isDirectory())
            {
                return dir;
            }
        }
        throw new AssertionError(PACKAGE_DIR + " not found from " + new File("").getAbsolutePath());
    }

    private static String read(final File dir, final String name) throws IOException
    {
        return new String(Files.readAllBytes(new File(dir, name).toPath()), StandardCharsets.UTF_8);
    }
}

package com.zoffcc.applications.trifa;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * KHANDAQ (redesign, stage 5): AirLiveOverride, the hook through which a debug build stands in for toxcore
 * for the made-up demo world (src/debug, AirDemoWorld). Without a source every reader asks toxcore as before;
 * a source answers for its own contacts and groups only; code in src/main never sets one and never names the
 * demo world, so a release build has neither; the demo strings exist in both languages and keep the glyph
 * rules of the client texts.
 */
public class AirLiveOverrideTest
{
    private static final String OWN_KEY = repeat("AB", 32);
    private static final String OTHER_KEY = repeat("CD", 32);
    private static final String OWN_GROUP = repeat("e1", 32);
    private static final String OTHER_GROUP = repeat("f2", 32);

    private static final Pattern DEMO_STRING = Pattern.compile(
            "<string name=\"(air_demo_[a-z0-9_]+)\">(.*?)</string>", Pattern.DOTALL);
    private static final Pattern FORMAT_ARGUMENT = Pattern.compile("%\\d+\\$[a-z]");

    @After
    public void noSourceLeftBehind()
    {
        AirLiveOverride.source = null;
    }

    @Test
    public void withoutASourceToxcoreAnswersEverything()
    {
        AirLiveOverride.source = null;
        assertFalse(AirLiveOverride.ownsFriend(OWN_KEY));
        assertFalse(AirLiveOverride.ownsFriend(null));
        assertNull(AirLiveOverride.group(OWN_GROUP));
        assertNull(AirLiveOverride.group(null));
    }

    @Test
    public void aSourceAnswersForItsOwnContactsAndGroupsOnly()
    {
        final FakeSource fake = new FakeSource();
        AirLiveOverride.source = fake;
        assertTrue(AirLiveOverride.ownsFriend(OWN_KEY));
        assertTrue(AirLiveOverride.ownsFriend(OWN_KEY.toLowerCase(java.util.Locale.ROOT)));
        assertFalse(AirLiveOverride.ownsFriend(OTHER_KEY));
        assertFalse(AirLiveOverride.ownsFriend(null));
        assertSame(fake, AirLiveOverride.group(OWN_GROUP));
        assertNull(AirLiveOverride.group(OTHER_GROUP));
        assertNull(AirLiveOverride.group(null));
    }

    @Test
    public void mainCodeNeverSetsTheSourceNorNamesTheDemoWorld() throws IOException
    {
        final List<String> offenders = new ArrayList<>();
        for (final File file : filesUnder(moduleDir("src/main/java"), ".java"))
        {
            final String text = read(file);
            if (!file.getName().equals("AirLiveOverride.java") && text.contains("AirLiveOverride.source"))
            {
                offenders.add(file.getName() + " sets or reads AirLiveOverride.source");
            }
            if (text.contains("AirDemoWorld"))
            {
                offenders.add(file.getName() + " names AirDemoWorld");
            }
        }
        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void theDemoNamesStayOutOfMain() throws IOException
    {
        final List<String> names = new ArrayList<>();
        for (final String locale : new String[]{"values", "values-ru"})
        {
            for (final Map.Entry<String, String> entry : demoStrings(locale).entrySet())
            {
                if (entry.getKey().startsWith("air_demo_name_"))
                {
                    names.add(entry.getValue());
                }
            }
        }
        assertTrue("no demo names found", names.size() >= 2 * 13);
        final List<String> offenders = new ArrayList<>();
        final List<File> files = new ArrayList<>(filesUnder(moduleDir("src/main"), ".java"));
        files.addAll(filesUnder(moduleDir("src/main"), ".xml"));
        for (final File file : files)
        {
            // What reaches the APK: comments do not (a comment may cite a name of the iOS world).
            final String raw = read(file);
            final String text = file.getName().endsWith(".java") ? withoutJavaComments(raw)
                                                                  : raw.replaceAll("(?s)<!--.*?-->", "");
            for (final String name : names)
            {
                if (text.contains(name))
                {
                    offenders.add(file.getPath() + " contains " + name);
                }
            }
        }
        assertTrue(String.join("\n", offenders), offenders.isEmpty());
    }

    @Test
    public void theCommentStripperKeepsCodeAndStrings()
    {
        assertEquals("int a = 1; \nString s = \"x // y /* z */\";\n",
                     withoutJavaComments("int a = 1; // one\nString s = \"x // y /* z */\";/* gone */\n"));
        assertEquals("char c = '\"'; String t = \"a\\\"b\";",
                     withoutJavaComments("char c = '\"'; /** doc \"x\" */String t = \"a\\\"b\";"));
    }

    @Test
    public void theDemoStringsMatchInBothLanguagesAndKeepTheGlyphRules() throws IOException
    {
        final Map<String, String> english = demoStrings("values");
        final Map<String, String> russian = demoStrings("values-ru");
        assertEquals(new TreeSet<>(english.keySet()), new TreeSet<>(russian.keySet()));
        for (final Map.Entry<String, String> entry : english.entrySet())
        {
            assertEquals(entry.getKey(), formatArguments(entry.getValue()),
                         formatArguments(russian.get(entry.getKey())));
        }

        final List<File> files = new ArrayList<>();
        files.add(new File(moduleDir("src/debug/res"), "values/air_demo_world.xml"));
        files.add(new File(moduleDir("src/debug/res"), "values-ru/air_demo_world.xml"));
        for (final File file : filesUnder(moduleDir("src/debug/java"), ".java"))
        {
            if (file.getName().startsWith("AirDemoWorld"))
            {
                files.add(file);
            }
        }
        files.add(new File(moduleDir("src/main/java"), "com/zoffcc/applications/trifa/AirLiveOverride.java"));
        for (final File file : files)
        {
            final String text = read(file);
            for (int i = 0; i < text.length(); i++)
            {
                final char c = text.charAt(i);
                if (isBanned(c))
                {
                    fail(file.getName() + " has the banned glyph U+" + Integer.toHexString(c).toUpperCase(
                            java.util.Locale.ROOT) + " at " + i);
                }
            }
        }
    }

    // ---- helpers ----

    /** The dashes, ellipsis, multiplication sign, dots, bullets, boxes, checks, arrows and shapes no client text uses. */
    private static boolean isBanned(final char c)
    {
        return (c >= 0x2012 && c <= 0x2015) || c == 0x2026 || c == 0x00D7 || c == 0x00B7 || c == 0x2022 || c == 0x2023
               || c == 0x2043 || c == 0x2219 || (c >= 0x2610 && c <= 0x2612) || (c >= 0x2713 && c <= 0x2718)
               || c == 0x2705 || c == 0x274C || (c >= 0x2190 && c <= 0x21FF) || (c >= 0x27F5 && c <= 0x27FF)
               || (c >= 0x2B00 && c <= 0x2B0D) || (c >= 0x2500 && c <= 0x25FF);
    }

    /** The source without its line and block comments; string and character literals stay whole. */
    static String withoutJavaComments(final String source)
    {
        final StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length())
        {
            final char c = source.charAt(i);
            final char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/')
            {
                while (i < source.length() && source.charAt(i) != '\n')
                {
                    i++;
                }
            }
            else if (c == '/' && next == '*')
            {
                final int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            }
            else if (c == '"' || c == '\'')
            {
                out.append(c);
                i++;
                while (i < source.length() && source.charAt(i) != c)
                {
                    if (source.charAt(i) == '\\' && i + 1 < source.length())
                    {
                        out.append(source.charAt(i));
                        i++;
                    }
                    out.append(source.charAt(i));
                    i++;
                }
                if (i < source.length())
                {
                    out.append(source.charAt(i));
                    i++;
                }
            }
            else
            {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<String> formatArguments(final String value)
    {
        final List<String> found = new ArrayList<>();
        final Matcher matcher = FORMAT_ARGUMENT.matcher(value == null ? "" : value);
        while (matcher.find())
        {
            found.add(matcher.group());
        }
        Collections.sort(found);
        return found;
    }

    private static Map<String, String> demoStrings(final String folder) throws IOException
    {
        final File file = new File(moduleDir("src/debug/res"), folder + "/air_demo_world.xml");
        final Map<String, String> strings = new LinkedHashMap<>();
        final Matcher matcher = DEMO_STRING.matcher(read(file));
        while (matcher.find())
        {
            strings.put(matcher.group(1), matcher.group(2).replace("\\'", "'").replace("\\@", "@"));
        }
        assertFalse(folder + " has no demo strings", strings.isEmpty());
        return strings;
    }

    private static File moduleDir(final String relative)
    {
        for (final String root : new String[]{"", "app/"})
        {
            final File dir = new File(root + relative);
            if (dir.isDirectory())
            {
                return dir;
            }
        }
        throw new AssertionError(relative + " not found from " + new File("").getAbsolutePath());
    }

    private static List<File> filesUnder(final File dir, final String suffix)
    {
        final List<File> found = new ArrayList<>();
        final File[] children = dir.listFiles();
        if (children == null)
        {
            return found;
        }
        for (final File child : children)
        {
            if (child.isDirectory())
            {
                found.addAll(filesUnder(child, suffix));
            }
            else if (child.getName().endsWith(suffix))
            {
                found.add(child);
            }
        }
        return found;
    }

    private static String read(final File file) throws IOException
    {
        assertNotNull(file);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String repeat(final String part, final int times)
    {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++)
        {
            out.append(part);
        }
        return out.toString();
    }

    private static final class FakeSource implements AirLiveOverride.Source
    {
        @Override
        public boolean ownsFriend(final String publicKey)
        {
            return OWN_KEY.equalsIgnoreCase(publicKey);
        }

        @Override
        public boolean ownsGroup(final String groupId)
        {
            return OWN_GROUP.equalsIgnoreCase(groupId);
        }

        @Override
        public boolean groupConnected(final String groupId)
        {
            return true;
        }

        @Override
        public int groupSelfRole(final String groupId)
        {
            return 0;
        }

        @Override
        public String groupSelfPublicKey(final String groupId)
        {
            return OWN_KEY;
        }

        @Override
        public List<HelperGroup.GroupMemberDisplay> groupMembers(final String groupId)
        {
            return Collections.emptyList();
        }

        @Override
        public long[] groupCounts(final String groupId)
        {
            return new long[]{5L, 4L};
        }
    }
}

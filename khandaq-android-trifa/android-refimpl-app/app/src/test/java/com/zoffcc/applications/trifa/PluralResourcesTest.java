package com.zoffcc.applications.trifa;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — counted words in the four shipped languages (resConfigs, #248).
 *
 * The group header used a single form for every count (Russian "members" in the form for 5-20,
 * Arabic in the form for 11-99) because it was a plain string, not a plural. And values-ar and
 * values-zh-rCN had no plurals at all, so "last seen 5 minutes ago" fell back to English on an
 * Arabic or Chinese phone. Android only uses a quantity a locale defines, so these pin every plural
 * to the full integer category set of each language, with the same format arguments as the English
 * text. Plain JUnit over the resource files, no Android runtime.
 */
public class PluralResourcesTest
{
    // CLDR integer categories. A missing one silently falls back to "other".
    private static final Map<String, Set<String>> LOCALE_QUANTITIES = new LinkedHashMap<>();

    static
    {
        LOCALE_QUANTITIES.put("values", new TreeSet<>(Arrays.asList("one", "other")));
        LOCALE_QUANTITIES.put("values-ru", new TreeSet<>(Arrays.asList("one", "few", "many", "other")));
        LOCALE_QUANTITIES.put("values-ar",
                              new TreeSet<>(Arrays.asList("zero", "one", "two", "few", "many", "other")));
        LOCALE_QUANTITIES.put("values-zh-rCN", new TreeSet<>(Arrays.asList("other")));
    }

    private static final Pattern FORMAT_ARG = Pattern.compile("%(\\d+\\$)?[a-zA-Z]");

    @Test
    public void every_plural_has_each_category_its_language_needs()
    {
        final Map<String, Map<String, String>> english = plurals("values");
        assertTrue("no plurals in values/strings.xml", english.size() > 0);

        for (Map.Entry<String, Set<String>> locale : LOCALE_QUANTITIES.entrySet())
        {
            final Map<String, Map<String, String>> translated = plurals(locale.getKey());
            for (String name : english.keySet())
            {
                final Map<String, String> items = translated.get(name);
                assertNotNull(locale.getKey() + " has no <plurals name=\"" + name + "\">", items);
                assertEquals(locale.getKey() + "/" + name, locale.getValue(), new TreeSet<>(items.keySet()));
            }
        }
    }

    @Test
    public void every_item_keeps_the_english_format_arguments()
    {
        final Map<String, Map<String, String>> english = plurals("values");
        for (String locale : LOCALE_QUANTITIES.keySet())
        {
            for (Map.Entry<String, Map<String, String>> plural : plurals(locale).entrySet())
            {
                final Map<String, String> source = english.get(plural.getKey());
                assertNotNull(locale + " has a plural English lacks: " + plural.getKey(), source);
                final Set<String> expected = formatArguments(source.get("other"));
                for (Map.Entry<String, String> item : plural.getValue().entrySet())
                {
                    assertEquals(locale + "/" + plural.getKey() + "/" + item.getKey(), expected,
                                 formatArguments(item.getValue()));
                }
            }
        }
    }

    @Test
    public void member_count_agrees_with_the_number()
    {
        final Map<String, String> ru = plurals("values-ru").get("group_header_member_count");
        assertNotNull("values-ru: group_header_member_count is not a <plurals>", ru);
        assertEquals("%1$d участник · %2$d онлайн", ru.get("one"));
        assertEquals("%1$d участника · %2$d онлайн", ru.get("few"));
        assertEquals("%1$d участников · %2$d онлайн", ru.get("many"));

        final Map<String, String> ar = plurals("values-ar").get("group_header_member_count");
        assertNotNull("values-ar: group_header_member_count is not a <plurals>", ar);
        assertEquals("%1$d عضوان · %2$d متصل", ar.get("two"));
        assertEquals("%1$d أعضاء · %2$d متصل", ar.get("few"));
        assertEquals("%1$d عضوًا · %2$d متصل", ar.get("many"));
    }

    private static Set<String> formatArguments(String text)
    {
        final Set<String> out = new TreeSet<>();
        final Matcher m = FORMAT_ARG.matcher(text);
        while (m.find())
        {
            out.add(m.group());
        }
        return out;
    }

    /** {@code <plurals name>} -> quantity -> text, from {@code res/<folder>/strings.xml}. */
    private static Map<String, Map<String, String>> plurals(String folder)
    {
        final Map<String, Map<String, String>> out = new LinkedHashMap<>();
        try
        {
            final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                    stringsFile(folder));
            final NodeList nodes = doc.getElementsByTagName("plurals");
            for (int i = 0; i < nodes.getLength(); i++)
            {
                final Element plural = (Element) nodes.item(i);
                final Map<String, String> items = new LinkedHashMap<>();
                final NodeList children = plural.getElementsByTagName("item");
                for (int j = 0; j < children.getLength(); j++)
                {
                    final Element item = (Element) children.item(j);
                    items.put(item.getAttribute("quantity"), item.getTextContent());
                }
                out.put(plural.getAttribute("name"), items);
            }
        }
        catch (Exception e)
        {
            throw new AssertionError("cannot read " + folder + "/strings.xml", e);
        }
        return out;
    }

    private static File stringsFile(String folder)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (String root : new String[]{"src/main/res", "app/src/main/res"})
        {
            final File f = new File(root, folder + "/strings.xml");
            if (f.isFile())
            {
                return f;
            }
        }
        throw new AssertionError("res/" + folder + "/strings.xml not found from " + new File("").getAbsolutePath());
    }
}

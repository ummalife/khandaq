package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #7, 27.11): every sheet from below opens whole. On a tablet in landscape the "Create" sheet and the
 * question sheets opened at their automatic peek height, which is little more than the title there, with the rows
 * and the verbs below the screen until the sheet was dragged up. Pinned on the sources with their comments left out.
 */
public class SheetsOpenWholeTest
{
    private static final String JAVA = "com/zoffcc/applications/trifa/";
    /** A sheet made, its class named short or in full. */
    private static final Pattern MAKES_A_SHEET = Pattern.compile("\\bnew\\s+([\\w.]+\\.)?BottomSheetDialog\\s*\\(");

    @Test
    public void a_sheet_opens_expanded_without_a_collapsed_state()
    {
        final String open = bare(body(code("AirSheet.java"), "static void openWhole(final BottomSheetDialog sheet)"));
        final int skip = open.indexOf("behavior.setSkipCollapsed(true);");
        assertTrue(skip >= 0);
        assertTrue(open.indexOf("behavior.setState(BottomSheetBehavior.STATE_EXPANDED);") > skip);
    }

    @Test
    public void the_question_sheets_and_the_create_sheet_open_whole()
    {
        final String show = bare(body(code("AirSheet.java"), "boolean show()"));
        final int whole = show.indexOf("openWhole(dialog);");
        assertTrue(whole >= 0);
        assertTrue(show.indexOf("dialog.show();") > whole);

        final String create = bare(body(code("AirFragment.java"), "private void showCreateSheet()"));
        final int createWhole = create.indexOf("AirSheet.openWhole(sheet);");
        assertTrue(createWhole >= 0);
        assertTrue(create.indexOf("sheet.show();") > createWhole);
    }

    /** A new sheet made somewhere else would open at its peek height again: it goes through one of these. */
    @Test
    public void sheets_are_made_only_where_they_open_whole() throws IOException
    {
        final List<String> makers = new ArrayList<>();
        final File root = root();
        collect(root, root, makers);
        makers.sort(null);
        assertEquals(Arrays.asList(JAVA + "AirFragment.java", JAVA + "AirSheet.java", JAVA + "AttachSheet.java"), makers);
    }

    private static void collect(final File root, final File dir, final List<String> makers) throws IOException
    {
        final File[] files = dir.listFiles();
        if (files == null)
        {
            return;
        }
        for (final File file : files)
        {
            if (file.isDirectory())
            {
                collect(root, file, makers);
            }
            else if (file.getName().endsWith(".java") && MAKES_A_SHEET.matcher(read(file)).find())
            {
                makers.add(root.toURI().relativize(file.toURI()).getPath());
            }
        }
    }

    private static String read(final File file) throws IOException
    {
        return AntispamOnRequestTest.code(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private static String code(final String name)
    {
        try
        {
            return AntispamOnRequestTest.code(new String(Files.readAllBytes(new File(root(), JAVA + name).toPath()),
                                                         StandardCharsets.UTF_8));
        }
        catch (final IOException e)
        {
            throw new AssertionError(name, e);
        }
    }

    private static File root()
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final File dir = new File(root);
            if (new File(dir, JAVA + "AirSheet.java").isFile())
            {
                return dir;
            }
        }
        throw new AssertionError("src/main/java not found from " + new File("").getAbsolutePath());
    }

    private static String bare(final String text)
    {
        return text.replaceAll("\\s+", "");
    }

    private static String body(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        final int open = text.indexOf('{', at);
        assertTrue("no opening brace", open >= 0);
        int depth = 0;
        for (int i = open; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (c == '{')
            {
                depth++;
            }
            else if (c == '}')
            {
                depth--;
                if (depth == 0)
                {
                    return text.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError("unbalanced braces");
    }
}

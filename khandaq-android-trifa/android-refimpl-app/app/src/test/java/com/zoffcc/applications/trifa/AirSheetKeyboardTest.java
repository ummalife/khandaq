package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A sheet with fields takes the keyboard down as it closes (stage 5, before the release): the screen under
 * it kept a keyboard it did not ask for, and a group's read-only bar, where "Write to a moderator" opens a
 * private message, has no field to type in. Every way out of the sheet (its verb, Cancel, Back, a tap
 * outside, a swipe down) ends in the dialog's dismiss, so the keyboard goes there, while the sheet still
 * has its window; only sheets made with withKeyboard do it.
 */
public class AirSheetKeyboardTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";

    @Test
    public void the_dialog_takes_the_keyboard_down_before_it_goes() throws Exception
    {
        final String sheet = read("AirSheet.java");
        final int dismiss = sheet.indexOf("public void dismiss()");
        assertTrue(dismiss > 0);
        final int guard = sheet.indexOf("if (fields)", dismiss);
        final int down = sheet.indexOf("keyboardDown();", dismiss);
        final int gone = sheet.indexOf("super.dismiss();", dismiss);
        assertTrue(guard > dismiss && down > guard && gone > down);
        assertTrue(sheet.contains("hideSoftInputFromWindow(decor.getWindowToken(), 0)"));
    }

    @Test
    public void only_a_sheet_with_fields_does() throws Exception
    {
        final String sheet = read("AirSheet.java");
        assertEquals(1, count(sheet, "fields = true;"));
        final int with = sheet.indexOf("AirSheet withKeyboard()");
        assertTrue(with > 0 && sheet.indexOf("fields = true;") > with);
        // The private message of "Write to a moderator" is one of them.
        assertTrue(read("GroupMemberActions.java").contains("sheet.withKeyboard().show()"));
    }

    private static int count(final String text, final String part)
    {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
        {
            n++;
        }
        return n;
    }

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + JAVA + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

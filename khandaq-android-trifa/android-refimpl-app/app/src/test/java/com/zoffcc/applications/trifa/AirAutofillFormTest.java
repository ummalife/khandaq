package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (stage 5): the first-launch password page shows a line that names the profile ("For the profile
 * Amina") instead of the bare name, and password managers still get a form of username plus new password,
 * with the bare name as the username. Views cannot be built in a JVM test here (no Robolectric resources),
 * so this pins the structure in the sources and the resources, the way the other audit tests read them.
 */
public class AirAutofillFormTest
{
    @Test
    public void the_username_view_gives_autofill_the_bare_name()
    {
        final String body = classBody(source("AirStepKit.java"), "static final class UsernameText");
        assertTrue(body.contains("setAutofillHints(View.AUTOFILL_HINT_USERNAME);"));
        assertTrue(body.contains("setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);"));
        // setUsername keeps the bare name for autofill and shows the line.
        assertTrue(squeeze(body).contains(
                "void setUsername(final CharSequence name, final CharSequence shown) { username = name; setText(shown); }"));
        // The value autofill reads: the bare name once one is set, the shown text otherwise.
        assertTrue(squeeze(body).contains("public AutofillValue getAutofillValue() { return username == null"
                                          + " ? super.getAutofillValue() : AutofillValue.forText(username); }"));
        // The node's text in the structure is the bare name too, for services that read text, not value.
        final String structure = methodBody(body, "public void onProvideAutofillStructure(");
        assertTrue(structure.contains("super.onProvideAutofillStructure(structure, flags);"));
        assertTrue(structure.contains("structure.setText(username);"));
        // A saved username never replaces the name.
        assertEquals("", methodBody(body, "public void autofill(final AutofillValue value)")
                .replaceAll("(?m)^\\s*//.*$", "").trim());
    }

    @Test
    public void the_first_launch_form_is_username_plus_new_password()
    {
        final String text = source("AirFirstLaunchActivity.java");
        assertTrue(text.contains("AUTOFILL_HINT_NEW_PASSWORD = \"newPassword\";"));
        // One container holds the username view and both password fields, so they form one form.
        assertTrue(text.contains("username = new AirStepKit.UsernameText("));
        assertTrue(text.contains("passwordFields.addView(username,"));
        assertTrue(text.contains("passwordFields.addView(passwordField,"));
        assertTrue(text.contains("passwordFields.addView(repeatField,"));
        assertTrue(squeeze(text).contains("for (final AirStepKit.LineField field : new AirStepKit.LineField[]"
                                          + "{passwordField, repeatField}) { field.edit.setAutofillHints("
                                          + "AUTOFILL_HINT_NEW_PASSWORD); field.edit.setImportantForAutofill("
                                          + "View.IMPORTANT_FOR_AUTOFILL_YES);"));
        // The page shows the line, never the bare name.
        assertTrue(text.contains(
                "username.setUsername(name, getString(R.string.air_first_password_for_profile, name));"));
        assertFalse(text.contains("username.setText("));
    }

    @Test
    public void the_change_password_page_shows_the_line_too()
    {
        final String text = source("ChangePasswordActivity.java");
        assertTrue(text.contains(
                "username.setUsername(name, getString(R.string.air_first_password_for_profile, name));"));
        assertFalse(text.contains("username.setText("));
    }

    @Test
    public void the_line_exists_in_four_languages_and_holds_the_name_once()
    {
        final Pattern item = Pattern.compile("<string name=\"air_first_password_for_profile\">([^<]*)</string>");
        for (final String values : new String[]{"values", "values-ru", "values-ar", "values-zh-rCN"})
        {
            final Matcher m = item.matcher(read(resFile(values + "/strings_s5_f1.xml")));
            assertTrue(values + ": air_first_password_for_profile missing", m.find());
            final String line = m.group(1);
            assertEquals(values + ": the name once", 1, line.split("%1\\$s", -1).length - 1);
            assertFalse(values + ": no other argument", line.replace("%1$s", "").contains("%"));
            assertNotEquals(values + ": words around the name", "%1$s", line.trim());
        }
    }

    // ---- reading the sources ----

    /** The text between the braces of the first block that follows {@code head}. */
    private static String classBody(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        return block(text, text.indexOf('{', at));
    }

    private static String methodBody(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        return block(text, text.indexOf('{', at));
    }

    private static String block(final String text, final int open)
    {
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

    /** Line comments out, whitespace runs to one space: the code as one line. */
    private static String squeeze(final String text)
    {
        return text.replaceAll("(?m)//.*$", "").replaceAll("\\s+", " ").trim();
    }

    private static String source(final String name)
    {
        return read(file("java/com/zoffcc/applications/trifa/" + name));
    }

    private static File resFile(final String path)
    {
        return file("res/" + path);
    }

    private static File file(final String path)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/", "app/src/main/"})
        {
            final File f = new File(root + path);
            if (f.isFile())
            {
                return f;
            }
        }
        throw new AssertionError(path + " not found from " + new File("").getAbsolutePath());
    }

    private static String read(final File file)
    {
        try
        {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new AssertionError(file + ": " + e.getMessage());
        }
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #12, 27.11): a theme switch fades once, without a flash. The snapshot of the old window was laid over
 * the new one from a posted runnable, after the new window's first frame: the screen showed the new theme for a frame,
 * then the old one under the snapshot, then faded to the new. Pinned on the sources with their comments left out.
 */
public class ThemeSwitchNoFlashTest
{
    @Test
    public void the_snapshot_covers_the_new_window_before_its_first_frame()
    {
        final String helper = code("ThemeTransitionHelper.java");
        final String schedule = bare(body(helper, "static void scheduleCrossfadeAfterCreate(@NonNull final Activity activity)"));
        final int cover = schedule.indexOf("finalRunnablefade=coverWithSnapshot(activity);");
        assertTrue(cover >= 0);
        assertTrue(schedule.indexOf("activity.getWindow().getDecorView().post(fade);") > cover);
        // Nothing of the overlay waits in a posted lambda: only the fade does.
        assertFalse(schedule.contains("post(()->"));

        final String covering = bare(body(helper, "private static Runnable coverWithSnapshot(@NonNull final Activity activity)"));
        final int added = covering.indexOf("decor.addView(overlay,");
        assertTrue(added >= 0);
        assertTrue(covering.indexOf("return()->fadeOut(activity,decor,overlay,snapshot);") > added);
        assertFalse("the fade starts after the first frame", covering.contains("animator.start();"));

        assertTrue(bare(body(helper, "private static void fadeOut(")).contains("animator.start();"));
    }

    @Test
    public void the_main_screen_asks_for_the_cover_as_it_is_created()
    {
        final String create = bare(body(code("MainActivity.java"), "protected void onCreate(Bundle savedInstanceState)"));
        final int content = create.indexOf("setContentView(R.layout.activity_main);");
        assertTrue(content >= 0);
        assertTrue(create.indexOf("ThemeTransitionHelper.scheduleCrossfadeAfterCreate(this);") > content);
    }

    private static String code(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final File file = new File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                try
                {
                    return AntispamOnRequestTest.code(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                }
                catch (final IOException e)
                {
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
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

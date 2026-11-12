package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The line under the header of an Air screen (stage 5, before the release): a form the keyboard lifts
 * slides under a drawn edge, as under the Air header and the conversation's and as the opaque navigation
 * bar on iOS; the first-launch and sign-in pages keep an open header, as LoginCoordinator on iOS does.
 * You and Settings, the two pages of the main screen under its own header, close it with the same line.
 */
public class AirHeaderRuleTest
{
    private static final String DIR = "src/main/";
    private static final String JAVA = "java/com/zoffcc/applications/trifa/";

    @Test
    public void the_line_sits_between_the_header_and_the_column() throws Exception
    {
        final String layout = read("res/layout/air_screen.xml");
        final int toolbar = layout.indexOf("@+id/air_screen_toolbar");
        final int rule = layout.indexOf("@+id/air_screen_rule");
        final int scroll = layout.indexOf("@+id/air_screen_scroll");
        assertTrue(toolbar > 0 && rule > toolbar && scroll > rule);
        final String view = layout.substring(rule, scroll);
        assertTrue(view.contains("android:layout_height=\"@dimen/kq_stroke_hairline\""));
        assertTrue(view.contains("android:background=\"@color/kq_line\""));
    }

    @Test
    public void every_screen_keeps_it_but_the_first_launch_sign_in_and_the_lock() throws Exception
    {
        final String frame = read(JAVA + "AirScreenActivity.java");
        assertTrue(frame.contains("if (!headerRule())"));
        assertTrue(frame.contains("findViewById(R.id.air_screen_rule).setVisibility(View.GONE);"));
        for (final String open : new String[]{"AirFirstLaunchActivity", "AirIdReadyActivity", "CheckPasswordActivity"})
        {
            assertTrue(open, read(JAVA + open + ".java").contains(
                    "protected boolean headerRule()\n    {\n        return false;\n    }"));
        }
        assertTrue(read(JAVA + "PinActivity.java").contains(
                "protected boolean headerRule()\n    {\n        return !isUnlockIntent();\n    }"));
        for (final String kept : new String[]{"AirNewContactActivity", "AirNewGroupActivity", "AirNetworkActivity",
                                              "AirBlockedActivity", "AirGroupDossierActivity", "GroupInfoActivity",
                                              "GroupPeerInfoActivity", "ProfileDetailsActivity",
                                              "ChangePasswordActivity", "Aboutpage"})
        {
            final String source = read(JAVA + kept + ".java");
            assertTrue(kept, source.contains("extends AirScreenActivity"));
            assertFalse(kept, source.contains("headerRule()"));
            assertFalse(kept, source.contains("air_screen_rule"));
        }
    }

    @Test
    public void you_and_settings_close_the_main_header_with_the_same_line() throws Exception
    {
        // The line is the last view of the main header, under the toolbar and what the old tabs put there.
        final String layout = read("res/layout/activity_main.xml");
        final int header = layout.indexOf("@+id/main_header_container");
        final int toolbar = layout.indexOf("@+id/toolbar", header);
        final int tabs = layout.indexOf("@layout/chat_list_filter_tabs", toolbar);
        final int rule = layout.indexOf("@+id/main_header_rule", tabs);
        final int closed = layout.indexOf("</LinearLayout>", tabs);
        assertTrue(header > 0 && toolbar > header && tabs > toolbar && rule > tabs && closed > rule);
        final String view = layout.substring(rule, closed);
        assertTrue(view.contains("android:layout_height=\"@dimen/kq_stroke_hairline\""));
        assertTrue(view.contains("android:background=\"@color/kq_line\""));
        assertFalse(view.contains("android:visibility"));

        // The header, and so its line, stands over You and Settings only: Air draws its own, and every
        // other tab lands on Air. The pages themselves leave the line alone.
        final String main = read(JAVA + "MainActivity.java");
        assertTrue(main.contains("(requestedTabId == R.id.bottom_nav_settings || requestedTabId == R.id.bottom_nav_profile)\n"
                                 + "                          ? requestedTabId : R.id.main_screen_air;"));
        assertTrue(main.contains("header.setVisibility(air ? View.GONE : View.VISIBLE);"));
        for (final String page : new String[]{"MainActivity", "ProfileContentFragment", "AirYouScreen",
                                              "SettingsTabFragment"})
        {
            assertFalse(page, read(JAVA + page + ".java").contains("main_header_rule"));
        }
    }

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + DIR + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Your tile, your name and, right under the name, the state of your node (E4), at the top of the main
 * screen. A tester on Android 16 found the line a finger below the name: it had the height of a whole
 * target, 48dp, and its words sat in the middle of it, so the column outgrew the tile. iOS keeps the two
 * lines 2pt apart.
 */
public class AirYouBlockTest
{
    private static final String DIR = "src/main/";

    @Test
    public void the_state_of_the_node_sits_right_under_the_name() throws Exception
    {
        final String layout = read("res/layout/fragment_air.xml");
        final int name = layout.indexOf("@+id/air_you_name");
        final int row = layout.indexOf("@+id/air_net_row", name);
        final int text = layout.indexOf("@+id/air_net_text", row);
        assertTrue(name > 0 && row > name && text > row);
        final String view = layout.substring(row, text);
        assertTrue(view.contains("android:minHeight=\"@dimen/kq_size_control_small\""));
        assertFalse(view.contains("kq_size_target_android"));
        assertFalse(view.contains("android:layout_margin"));
        assertFalse(view.contains("android:paddingTop"));
    }

    @Test
    public void the_line_still_opens_the_network_screen() throws Exception
    {
        final String fragment = read("java/com/zoffcc/applications/trifa/AirFragment.java");
        assertTrue(fragment.contains(
                "root.findViewById(R.id.air_net_row).setOnClickListener(v -> AirNetworkActivity.open(requireContext()));"));
        assertTrue(fragment.contains("ViewCompat.addAccessibilityAction(you, getString(R.string.air_settings_network),"));
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

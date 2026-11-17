package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.zoffcc.applications.trifa.TRIFAGlobals.KHANDAQ_MAX_FILE_TRANSFER_BYTES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57, SPEC-network part 3): a 1:1 file up to 200 MB downloads by itself, on a new install and, once,
 * on an install that had it off; what the person sets after that stays.
 */
public class AutoDownloadPreferenceTest
{
    private static final long MB = 1024L * 1024L;
    private static final int ALWAYS = 2;
    private static final long PICTURE_CAP = 12 * MB;
    private static final long VIDEO_CAP = 200 * MB;
    private static final long ANY_CAP = 200 * MB;

    @Test
    public void a_new_profile_downloads_every_file_by_itself()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        assertTrue(AutoDownloadPreference.applyDefaultOnce(settings));
        assertEquals(Boolean.TRUE, settings.values.get(AutoDownloadPreference.KEY_PICTURES));
        assertEquals(Boolean.TRUE, settings.values.get(AutoDownloadPreference.KEY_VIDEOS));
        assertEquals(Boolean.TRUE, settings.values.get(AutoDownloadPreference.KEY_ALL_FILES));
        assertEquals(Boolean.TRUE, settings.values.get(AutoDownloadPreference.APPLIED_KEY));
        assertEquals("auto_download_default_v0257_applied", AutoDownloadPreference.APPLIED_KEY);
        assertTrue(AutoDownloadPreference.DEFAULT);
    }

    @Test
    public void a_profile_with_everything_off_is_turned_on_once()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        settings.values.put(AutoDownloadPreference.KEY_PICTURES, Boolean.FALSE);
        settings.values.put(AutoDownloadPreference.KEY_VIDEOS, Boolean.FALSE);
        settings.values.put(AutoDownloadPreference.KEY_ALL_FILES, Boolean.FALSE);
        settings.values.put("attachment_download_mode", 1);
        assertTrue(AutoDownloadPreference.applyDefaultOnce(settings));
        assertEquals(Boolean.TRUE, settings.values.get(AutoDownloadPreference.KEY_PICTURES));
        assertEquals(Boolean.TRUE, settings.values.get(AutoDownloadPreference.KEY_VIDEOS));
        assertEquals(Boolean.TRUE, settings.values.get(AutoDownloadPreference.KEY_ALL_FILES));
        assertEquals(1, settings.commits);
        // The download mode is the person's: groups read it too.
        assertEquals(1, settings.values.get("attachment_download_mode"));
        // The next start writes nothing.
        assertFalse(AutoDownloadPreference.applyDefaultOnce(settings));
        assertEquals(1, settings.commits);
    }

    @Test
    public void switched_off_again_it_stays_off_after_a_restart()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        assertTrue(AutoDownloadPreference.applyDefaultOnce(settings));
        // The person switches the three off in Settings.
        settings.values.put(AutoDownloadPreference.KEY_PICTURES, Boolean.FALSE);
        settings.values.put(AutoDownloadPreference.KEY_VIDEOS, Boolean.FALSE);
        settings.values.put(AutoDownloadPreference.KEY_ALL_FILES, Boolean.FALSE);
        // A restart, and one more.
        assertFalse(AutoDownloadPreference.applyDefaultOnce(settings));
        assertFalse(AutoDownloadPreference.applyDefaultOnce(settings));
        assertEquals(Boolean.FALSE, settings.values.get(AutoDownloadPreference.KEY_PICTURES));
        assertEquals(Boolean.FALSE, settings.values.get(AutoDownloadPreference.KEY_VIDEOS));
        assertEquals(Boolean.FALSE, settings.values.get(AutoDownloadPreference.KEY_ALL_FILES));
        assertFalse(rule(ALWAYS, false, false, false, false, MB, "application/pdf", false));
        assertFalse(rule(ALWAYS, false, false, false, false, MB, "image/jpeg", false));
    }

    @Test
    public void the_default_rule_takes_every_file_up_to_200_mb()
    {
        assertEquals(200L * MB, KHANDAQ_MAX_FILE_TRANSFER_BYTES);
        for (final String mime : new String[]{"application/pdf", "image/png", "video/mp4", "application/zip", null})
        {
            assertTrue(String.valueOf(mime), on(1L, mime));
            assertTrue(String.valueOf(mime), on(150L * MB, mime));
            assertTrue(String.valueOf(mime), on(KHANDAQ_MAX_FILE_TRANSFER_BYTES, mime));
            // A bigger one waits for a tap.
            assertFalse(String.valueOf(mime), on(KHANDAQ_MAX_FILE_TRANSFER_BYTES + 1, mime));
        }
        assertFalse(on(0L, "image/png"));
    }

    @Test
    public void each_switch_keeps_its_cap()
    {
        // Pictures alone: up to 12 MB; videos alone: up to their cap; all files: up to 200 MB.
        assertTrue(rule(ALWAYS, false, true, false, false, 12 * MB, "image/png", false));
        assertFalse(rule(ALWAYS, false, true, false, false, 12 * MB + 1, "image/png", false));
        assertFalse(rule(ALWAYS, false, true, false, false, MB, "video/mp4", false));
        assertTrue(rule(ALWAYS, false, false, true, false, VIDEO_CAP, "video/mp4", false));
        assertFalse(rule(ALWAYS, false, false, true, false, VIDEO_CAP + 1, "video/mp4", false));
        assertFalse(rule(ALWAYS, false, false, true, false, MB, "application/pdf", false));
        assertTrue(rule(ALWAYS, false, false, false, true, 200 * MB, "application/pdf", false));
    }

    @Test
    public void the_caps_are_the_ones_of_the_globals()
    {
        final String globals = read("src/main/java/com/zoffcc/applications/trifa/TRIFAGlobals.java");
        assertTrue(globals.contains("static int AUTO_ACCEPT_FT_MAX_IMAGE_SIZE_IN_MB = 12;"));
        assertTrue(globals.contains("static int AUTO_ACCEPT_FT_MAX_VIDEO_SIZE_IN_MB = 200;"));
        assertTrue(globals.contains("static int AUTO_ACCEPT_FT_MAX_ANYKIND_SIZE_IN_MB = 200;"));
        final String rule = read("src/main/java/com/zoffcc/applications/trifa/AutoDownloadPreference.java");
        assertTrue(rule.replaceAll("\\s+", "").contains(
                "AUTO_ACCEPT_FT_MAX_IMAGE_SIZE_IN_MB*MB,AUTO_ACCEPT_FT_MAX_VIDEO_SIZE_IN_MB*MB,"
                + "AUTO_ACCEPT_FT_MAX_ANYKIND_SIZE_IN_MB*MB);"));
    }

    @Test
    public void the_download_mode_still_counts()
    {
        assertFalse("Never", rule(0, true, true, true, true, MB, "image/png", false));
        assertFalse("Wi-Fi only, on mobile data", rule(1, false, true, true, true, MB, "image/png", false));
        assertTrue("Wi-Fi only, on Wi-Fi", rule(1, true, true, true, true, MB, "image/png", false));
    }

    @Test
    public void a_voice_note_keeps_its_exemption()
    {
        assertTrue(rule(0, false, false, false, false, MB, "audio/ogg", true));
        assertTrue(rule(1, false, false, false, false, MB, null, true));
        assertFalse("not past the protocol's cap",
                    rule(ALWAYS, false, false, false, false, KHANDAQ_MAX_FILE_TRANSFER_BYTES + 1, "audio/ogg", true));
    }

    @Test
    public void the_rule_is_applied_before_the_switches_are_read_and_settings_show_it()
    {
        final String main = read("src/main/java/com/zoffcc/applications/trifa/MainActivity.java");
        int from = 0;
        int sites = 0;
        while (true)
        {
            final int apply = main.indexOf("AutoDownloadPreference.applyDefaultOnce(settings);", from);
            if (apply < 0)
            {
                break;
            }
            sites++;
            final int read = main.indexOf("settings.getBoolean(AutoDownloadPreference.KEY_PICTURES,", apply);
            assertTrue(read > apply);
            assertTrue(main.indexOf("settings.getBoolean(AutoDownloadPreference.KEY_ALL_FILES,", apply) > read);
            from = apply + 1;
        }
        assertEquals("both places that read the switches", 2, sites);
        assertFalse(main.contains("settings.getBoolean(\"auto_accept_"));
        final String prefs = read("src/main/res/xml/pref_general.xml");
        for (final String key : new String[]{"auto_accept_image", "auto_accept_video", "auto_accept_all_upto"})
        {
            final int at = prefs.indexOf("android:key=\"" + key + "\"");
            assertTrue(key, at > 0);
            final int open = prefs.lastIndexOf("<SwitchPreference", at);
            assertTrue(key, prefs.substring(open, at).contains("android:defaultValue=\"true\""));
        }
        // The automatic retries ask the rule; they used to accept every file.
        final String helper = read("src/main/java/com/zoffcc/applications/trifa/HelperFiletransfer.java");
        assertFalse(helper.contains("should_force_auto_accept_incoming"));
        assertTrue(helper.replaceAll("\\s+", "").contains("FileResume.acceptsByItself(false,"
                                                          + "incoming_refused_by_person(current,f),"
                                                          + "auto_download_allows(current))"));
    }

    @Test
    public void a_switch_turned_in_advanced_counts_at_once()
    {
        // Advanced is shown inside MainActivity, which reads the switches only in onCreate and onResume: the
        // listener of the preferences keeps them current, as it does the text size.
        final String app = read("src/main/java/com/zoffcc/applications/trifa/MainApplication.java");
        final int listener = app.indexOf("sp_change_listener = (sharedPreferences, key) ->");
        assertTrue(listener > 0);
        final String[][] switches = {{"KEY_PICTURES", "PREF__auto_accept_image"},
                                     {"KEY_VIDEOS", "PREF__auto_accept_video"},
                                     {"KEY_ALL_FILES", "PREF__auto_accept_all_upto"}};
        for (final String[] s : switches)
        {
            final int key = app.indexOf("AutoDownloadPreference." + s[0] + ".equals(key)", listener);
            assertTrue(s[0], key > listener);
            final int set = app.indexOf("MainActivity." + s[1] + " = autoDownloadSwitch(sharedPreferences, key);", key);
            assertTrue(s[1], set > key);
            assertTrue(s[1] + " right under its key", app.indexOf("else if", key + 1) > set
                                                     || app.indexOf("else if", key + 1) < 0);
        }
        assertTrue(app.contains("return sharedPreferences.getBoolean(key, AutoDownloadPreference.DEFAULT);"));
    }

    private static boolean on(final long size, final String mime)
    {
        return rule(ALWAYS, false, AutoDownloadPreference.DEFAULT, AutoDownloadPreference.DEFAULT,
                    AutoDownloadPreference.DEFAULT, size, mime, false);
    }

    /** The rule with the caps of TRIFAGlobals (checked in the_caps_are_the_ones_of_the_globals). */
    private static boolean rule(final int mode, final boolean onWifi, final boolean pictures, final boolean videos,
                                final boolean allFiles, final long size, final String mime, final boolean voice)
    {
        return AutoDownloadPreference.allows(mode, onWifi, pictures, videos, allFiles, size, mime, voice, PICTURE_CAP,
                                             VIDEO_CAP, ANY_CAP);
    }

    private static String read(final String name)
    {
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

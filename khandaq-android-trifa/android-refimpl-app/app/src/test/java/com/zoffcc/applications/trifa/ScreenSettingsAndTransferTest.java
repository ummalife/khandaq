package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * KHANDAQ (redesign stage 5): the phone's font size reaches the open screens, the battery question keeps
 * the preference of the old dialog, and a backup or a restore that failed is never taken for done.
 */
public class ScreenSettingsAndTransferTest
{
    private static final Pattern ACTIVITY = Pattern.compile("<activity\\s[^>]*>", Pattern.DOTALL);
    private static final Pattern CONFIG_CHANGES = Pattern.compile("android:configChanges=\"([^\"]*)\"");
    private static final Pattern NAME = Pattern.compile("android:name=\"([^\"]*)\"");

    @Test
    public void only_the_call_screens_keep_a_font_size_change_to_themselves() throws IOException
    {
        final Matcher activity = ACTIVITY.matcher(read(file("src/main/AndroidManifest.xml")));
        final Set<String> keeping = new TreeSet<>();
        int activities = 0;
        while (activity.find())
        {
            activities++;
            final String tag = activity.group();
            final Matcher changes = CONFIG_CHANGES.matcher(tag);
            if (changes.find() && Arrays.asList(changes.group(1).split("\\|")).contains("fontScale"))
            {
                final Matcher name = NAME.matcher(tag);
                keeping.add(name.find() ? name.group(1) : tag);
            }
        }
        assertTrue("activities found in the manifest: " + activities, activities > 10);
        // Every other screen is drawn again for the new size, as for a change of theme.
        assertEquals(new TreeSet<>(Arrays.asList("com.zoffcc.applications.trifa.CallingActivity",
                                                 "com.zoffcc.applications.trifa.CallingWaitingActivity")), keeping);
    }

    @Test
    public void the_battery_question_keeps_the_preference_of_the_old_dialog() throws IOException
    {
        // A profile that answered the old dialog is not asked again.
        assertEquals("asked_whitelist_doze", AirBatteryQuestion.PREF_ASKED);
        final String main = read(file("src/main/java/com/zoffcc/applications/trifa/MainActivity.java"));
        assertFalse("the old dialog left the main screen", main.contains("R.string.MainActivity_add_to_batt_opt"));
        final int idPage = main.indexOf("AirIdReadyActivity.openIfPending(");
        final int battery = main.indexOf("AirBatteryQuestion.askOnceIfDue(");
        assertTrue("Your ID is ready is decided first, so the question never comes over it",
                   idPage >= 0 && battery > idPage);
    }

    @Test
    public void a_backup_cut_after_its_database_fails_without_a_message() throws IOException
    {
        final File dir = Files.createTempDirectory("kbk-cut").toFile();
        final File db = new File(dir, "db.bin");
        final File vfs = new File(dir, "vfs.bin");
        final File dbOut = new File(dir, "db.out");
        final File vfsOut = new File(dir, "vfs.out");
        try
        {
            final byte[] dbData = new byte[4096];
            final byte[] vfsData = new byte[5000];
            new Random(1).nextBytes(dbData);
            new Random(2).nextBytes(vfsData);
            Files.write(db.toPath(), dbData);
            Files.write(vfs.toPath(), vfsData);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            BackupHelper.writeBackup(out, "a backup password".toCharArray(), 1_750_000_000_000L, 1, "db key",
                                     new byte[2048], db, vfs, null);
            final byte[] whole = out.toByteArray();
            // Cut right after the database: the length of the media blob and the blob itself are missing.
            final ByteArrayInputStream in = new ByteArrayInputStream(
                    Arrays.copyOf(whole, whole.length - Long.BYTES - vfsData.length));
            final BackupHelper.Manifest manifest = BackupHelper.readHeader(in, "a backup password".toCharArray());
            try
            {
                BackupHelper.extractBlobs(in, manifest, dbOut, vfsOut, null);
                fail("a cut backup was extracted");
            }
            catch (IOException e)
            {
                // The failure the restore used to take for success: it has no message.
                assertNull(e.getMessage());
            }
        }
        finally
        {
            for (final File f : new File[]{db, vfs, dbOut, vfsOut, dir})
            {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        // So the catch decides, not the message.
        final String helper = read(file("src/main/java/com/zoffcc/applications/trifa/PasswordBackupHelper.java"));
        assertFalse(helper.contains("finalError != null || finalManifest == null"));
        assertFalse(helper.contains("ok = finalError == null"));
        assertTrue(helper.contains("if (finalFailed || finalManifest == null)"));
        assertTrue(helper.contains("final boolean ok = !failed;"));
    }

    private static String read(final File file) throws IOException
    {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File file(final String path)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + path);
            if (f.isFile())
            {
                return f;
            }
        }
        throw new AssertionError(path + " not found from " + new File("").getAbsolutePath());
    }
}

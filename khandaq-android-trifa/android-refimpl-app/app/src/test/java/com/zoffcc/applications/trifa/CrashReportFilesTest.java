package com.zoffcc.applications.trifa;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (beta 2026-11-20): the crash report of the About page sends the newest crash file the handler wrote, from
 * the folder the app's FileProvider can hand over. Before, it looked in /sdcard/trifa/crashes, where nothing is
 * written since Android 10, and no report reached anyone with the crash in it.
 */
public class CrashReportFilesTest
{
    private static final String RES = "src/main/res/";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static File write(final File dir, final String name, final String text, final long modified)
            throws IOException
    {
        final File file = new File(dir, name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setLastModified(modified));
        return file;
    }

    @Test
    public void theNewestCrashIsTheLastWrittenInEitherFolder() throws IOException
    {
        final File external = tmp.newFolder("external");
        final File internal = tmp.newFolder("internal");
        write(external, "crash_2026-11-19_10_00_00.txt", "old stack", 1_000_000L);
        final File newest = write(internal, "crash_2026-11-20_22_13_05.txt", "new stack", 3_000_000L);
        // Written later, but none of them is a crash file of the handler.
        write(external, "report_2026-11-20_23_00_00.txt", "report", 9_000_000L);
        write(external, "crash_single.log", "other", 9_000_000L);
        write(external, "crash_2026-11-21_00_00_00.txt", "", 9_000_000L);
        assertTrue(new File(external, "crash_2026-11-22_00_00_00.txt").mkdir());

        assertEquals(newest, CrashReportFiles.newestCrash(external, internal));
    }

    @Test
    public void twoCrashesInOneSecondGoByTheirNames() throws IOException
    {
        final File dir = tmp.newFolder("same");
        write(dir, "crash_2026-11-20_22_13_05.txt", "first", 5_000_000L);
        final File later = write(dir, "crash_2026-11-20_22_13_06.txt", "second", 5_000_000L);

        assertEquals(later, CrashReportFiles.newestCrash(dir));
    }

    @Test
    public void withoutACrashFileThereIsNone() throws IOException
    {
        final File dir = tmp.newFolder("none");
        write(dir, "report_2026-11-20_23_00_00.txt", "report", 1_000L);

        assertNull(CrashReportFiles.newestCrash(dir, new File(dir, "missing"), null));
        assertNull(CrashReportFiles.newestCrash((File[]) null));
    }

    @Test
    public void theReportsSentBeforeGoAndTheCrashFilesStay() throws IOException
    {
        final File dir = tmp.newFolder("drop");
        final File crash = write(dir, "crash_2026-11-20_22_13_05.txt", "stack", 1_000L);
        final File report = write(dir, "report_2026-11-20_23_00_00.txt", "report", 2_000L);

        CrashReportFiles.dropReports(dir);
        CrashReportFiles.dropReports(new File(dir, "missing"));
        CrashReportFiles.dropReports(null);

        assertTrue(crash.isFile());
        assertFalse(report.exists());
    }

    @Test
    public void aReportIsNeverTakenForACrash() throws IOException
    {
        final String name = CrashReportFiles.reportName("2026-11-20_23_00_00");
        assertEquals("report_2026-11-20_23_00_00.txt", name);

        final File dir = tmp.newFolder("report");
        assertFalse(CrashReportFiles.isCrashFile(write(dir, name, "report", 1_000L)));
    }

    /** FileProvider refuses a file outside its paths: both places of the folder must be among them. */
    @Test
    public void theProviderPathsCoverBothPlacesOfTheFolder() throws IOException
    {
        final String xml = read("xml/stdfilepaths.xml");
        final String path = Pattern.quote("path=\"/" + CrashReportFiles.DIR_NAME + "/\"");

        assertTrue(Pattern.compile("<external-files-path " + path).matcher(xml).find());
        assertTrue(Pattern.compile("<files-path " + path).matcher(xml).find());
    }

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File file = new File(root + RES + name);
            if (file.isFile())
            {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

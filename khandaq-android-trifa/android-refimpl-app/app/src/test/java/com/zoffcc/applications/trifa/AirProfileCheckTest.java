package com.zoffcc.applications.trifa;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Why a profile did not open (redesign stage 4, E20): a wrong password, a damaged file or no access,
 * told apart by looking at the database and the savedata after the password check failed. Since stage 5
 * a file that does not read is looked for before the profile opens at all.
 */
public class AirProfileCheckTest
{
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void whole_files_mean_the_password_was_wrong() throws IOException
    {
        final File db = file("main.db", new byte[4096 * 3]);
        assertEquals(AirProfileCheck.Failure.WRONG_PASSWORD,
                     AirProfileCheck.classify(db, file("savedata.tox", "toxEsave....".getBytes("US-ASCII"))));
        assertEquals(AirProfileCheck.Failure.WRONG_PASSWORD,
                     AirProfileCheck.classify(db, file("plain.tox", new byte[]{0, 0, 0, 0, 0x1f, 0x1b, (byte) 0xed, 0x15, 1})));
        assertEquals(AirProfileCheck.Failure.WRONG_PASSWORD, AirProfileCheck.classify(db, null));
        assertEquals(AirProfileCheck.Failure.WRONG_PASSWORD,
                     AirProfileCheck.classify(db, new File(folder.getRoot(), "missing.tox")));
    }

    @Test
    public void a_database_cut_off_mid_page_is_damaged() throws IOException
    {
        assertEquals(AirProfileCheck.Failure.DAMAGED,
                     AirProfileCheck.classify(file("main.db", new byte[4096 + 300]), null));
    }

    @Test
    public void a_savedata_without_a_tox_header_is_damaged() throws IOException
    {
        final File db = file("main.db", new byte[1024 * 8]);
        assertEquals(AirProfileCheck.Failure.DAMAGED,
                     AirProfileCheck.classify(db, file("savedata.tox", "not a profile".getBytes("US-ASCII"))));
        assertEquals(AirProfileCheck.Failure.DAMAGED, AirProfileCheck.classify(db, file("short.tox", new byte[3])));
    }

    @Test
    public void a_file_the_app_cannot_read_is_no_access() throws IOException
    {
        final File db = file("main.db", new byte[4096]);
        // Some file systems (and a JVM run as root) ignore the flag; the case is then not testable here.
        assumeTrue(db.setReadable(false));
        assumeFalse(db.canRead());
        try
        {
            assertEquals(AirProfileCheck.Failure.NO_ACCESS, AirProfileCheck.classify(db, null));
        }
        finally
        {
            //noinspection ResultOfMethodCallIgnored
            db.setReadable(true);
        }
    }

    @Test
    public void a_failure_ios_gives_a_code_for_has_the_same_one() throws IOException
    {
        // Stage 5: the codes under Details, as iOS gives them for the same failure.
        final File db = file("main.db", new byte[4096]);
        final AirProfileCheck.Verdict wrong = AirProfileCheck.check(db, file("ok.tox", ascii("toxEsave....")));
        assertEquals(AirProfileCheck.Failure.WRONG_PASSWORD, wrong.failure);
        assertNull(wrong.code);

        final AirProfileCheck.Verdict notToxcore = AirProfileCheck.check(db, file("bad.tox", ascii("not a profile")));
        assertEquals(AirProfileCheck.Failure.DAMAGED, notToxcore.failure);
        assertEquals("me.dvor.objcTox.OCTManagerErrorDomain 19", notToxcore.code);

        // A database cut off mid-page has no counterpart on iOS, so no code.
        final AirProfileCheck.Verdict cut = AirProfileCheck.check(file("cut.db", new byte[4096 + 300]), null);
        assertEquals(AirProfileCheck.Failure.DAMAGED, cut.failure);
        assertNull(cut.code);
    }

    @Test
    public void a_save_it_cannot_read_comes_before_the_history_as_on_ios() throws IOException
    {
        final File db = file("main.db", new byte[4096]);
        final File save = file("savedata.tox", ascii("toxEsave...."));
        // Some file systems (and a JVM run as root) ignore the flag; the case is then not testable here.
        assumeTrue(save.setReadable(false));
        assumeFalse(save.canRead());
        try
        {
            AirProfileCheck.Verdict verdict = AirProfileCheck.check(db, save);
            assertEquals(AirProfileCheck.Failure.NO_ACCESS, verdict.failure);
            assertEquals("org.khandaq.messenger.ProfileOpening 1", verdict.code);
            // The same look stops sign-in before anything opens, whatever the password.
            assertEquals("org.khandaq.messenger.ProfileOpening 1", AirProfileCheck.unreadable(db, save).code);
            // With both unreadable, the save is named, as iOS checks it first.
            assumeTrue(db.setReadable(false));
            assumeFalse(db.canRead());
            verdict = AirProfileCheck.check(db, save);
            assertEquals("org.khandaq.messenger.ProfileOpening 1", verdict.code);
            //noinspection ResultOfMethodCallIgnored
            save.setReadable(true);
            verdict = AirProfileCheck.check(db, save);
            assertEquals(AirProfileCheck.Failure.NO_ACCESS, verdict.failure);
            assertEquals("org.khandaq.messenger.ProfileOpening 2", verdict.code);
        }
        finally
        {
            //noinspection ResultOfMethodCallIgnored
            save.setReadable(true);
            //noinspection ResultOfMethodCallIgnored
            db.setReadable(true);
        }
    }

    @Test
    public void files_that_read_or_are_not_there_yet_stop_nothing() throws IOException
    {
        final File db = file("main.db", new byte[4096]);
        assertNull(AirProfileCheck.unreadable(db, file("savedata.tox", ascii("toxEsave...."))));
        assertNull(AirProfileCheck.unreadable(db, null));
        // A profile before its first save has no savedata yet, and a new one no database.
        assertNull(AirProfileCheck.unreadable(new File(folder.getRoot(), "missing.db"),
                                              new File(folder.getRoot(), "missing.tox")));
    }

    @Test
    public void sign_in_looks_at_the_files_before_it_opens_the_profile() throws IOException
    {
        // Stage 5: a save that does not read stops sign-in with its code (ProfileOpening 1), as on iOS, even
        // with the right password or a remembered session; before, the database opened and Tox started
        // without its save, under another ID.
        final String page = source("CheckPasswordActivity.java");
        assertTrue(page.contains("if (!notOpened && noAccess == null && !TextUtils.isEmpty(key) "
                                 + "&& DbSecretKeyStorage.isSessionLoggedIn(this))"));
        final int look = page.indexOf("AirProfileCheck.Verdict failure = AirProfileCheck.unreadable(database, save);");
        final int unlock = page.indexOf("DbSecretKeyStorage.unlockManualPasswordHash(app, password)");
        assertTrue(look > 0);
        assertTrue(unlock > look);
        assertTrue(page.contains("() -> logInWithoutPassword(profileKey, reason)"));
        assertTrue(page.contains("AirUi.sayOnLine(note, getString(R.string.air_signin_no_access), true, noAccess.code);"));
    }

    private static byte[] ascii(final String text)
    {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String source(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final File file = new File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }

    private File file(final String name, final byte[] bytes) throws IOException
    {
        final File f = folder.newFile(name);
        try (FileOutputStream out = new FileOutputStream(f))
        {
            out.write(bytes);
        }
        return f;
    }
}

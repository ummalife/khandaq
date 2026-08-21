package com.zoffcc.applications.trifa;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (re-audit 2026-08-21, R-06) — the plaintext-export gate, on a real device.
 *
 * The point of {@link PlaintextExportGate} is not the dialog; a dialog is advice. The point is that
 * {@link ToxProfileImportHelper#handleExportDestination} — the one method that actually writes the
 * Tox private key out of the app — refuses to run without a live, single-use authorisation. That
 * property is what a future fourth entry point cannot accidentally undo, so that property is what is
 * tested here, rather than the UI in front of it.
 *
 * The refusal is observed through a side effect that does not need the Tox native layer: the export
 * begins by deleting its staging file in the app cache (a stale one from a killed run would
 * otherwise be copied out as though fresh). A refused export never gets that far, so a staging file
 * planted before the call must still exist after it, byte for byte. Delete the guard and this test
 * fails — it does not merely stop proving something.
 */
@RunWith(AndroidJUnit4.class)
public class PlaintextExportGateDeviceTest
{
    private static final String STAGING_NAME = "export_savedata.tox";
    private static final byte[] CANARY = "khandaq-r06-canary".getBytes();

    private Context ctx;

    @Before
    public void setUp() throws Exception
    {
        ctx = ApplicationProvider.getApplicationContext();
        PlaintextExportGate.clearAuthorisation();
    }

    // ---------------------------------------------------------------- the token itself

    @Test
    public void noGrantMeansNoAuthorisation()
    {
        assertFalse("a gate that has never granted anything must not authorise",
                    PlaintextExportGate.consumeAuthorisation());
    }

    @Test
    public void grantIsSingleUse() throws Exception
    {
        grantFor(60_000L);
        assertTrue("the granted authorisation must be spendable once",
                   PlaintextExportGate.consumeAuthorisation());
        assertFalse("the same authorisation must not be spendable twice — one confirmation, one file",
                    PlaintextExportGate.consumeAuthorisation());
    }

    @Test
    public void expiredGrantIsRefused() throws Exception
    {
        grantFor(-1L);   // deadline already in the past
        assertFalse("an authorisation past its deadline must not be honoured",
                    PlaintextExportGate.consumeAuthorisation());
    }

    @Test
    public void clearRevokesAnOutstandingGrant() throws Exception
    {
        grantFor(60_000L);
        PlaintextExportGate.clearAuthorisation();
        assertFalse("a revoked authorisation must not be spendable",
                    PlaintextExportGate.consumeAuthorisation());
    }

    // ---------------------------------------------------------------- the write it guards

    @Test
    public void exportWithoutAuthorisationWritesNothing() throws Exception
    {
        final File staging = plantStagingCanary();
        final File destination = new File(ctx.getCacheDir(), "r06-destination.tox");
        //noinspection ResultOfMethodCallIgnored
        destination.delete();

        ToxProfileImportHelper.handleExportDestination(ctx, Uri.fromFile(destination));

        assertTrue("an unauthorised export must return before touching the staging file",
                   staging.exists());
        assertEquals("the staging file must be untouched, which proves the refusal happened first",
                     CANARY.length, staging.length());
        assertFalse("an unauthorised export must not create the destination", destination.exists());
    }

    /**
     * The mirror image: with an authorisation present the method proceeds past the guard and spends
     * it. Whether the export then succeeds depends on the Tox layer being up, which this test does
     * not require — reaching the staging delete is the observable that separates "guard passed" from
     * "guard refused", and the grant being spent is what stops it being reused.
     */
    @Test
    public void authorisedExportPassesTheGuardAndSpendsTheGrant() throws Exception
    {
        final File staging = plantStagingCanary();
        final File destination = new File(ctx.getCacheDir(), "r06-destination-ok.tox");
        //noinspection ResultOfMethodCallIgnored
        destination.delete();

        grantFor(60_000L);
        try
        {
            ToxProfileImportHelper.handleExportDestination(ctx, Uri.fromFile(destination));
        }
        catch (Throwable ignored)
        {
            // No Tox profile open in the test process: the native write is allowed to fail. The
            // guard has already been passed by then, which is the whole claim.
        }

        assertFalse("an authorised export must clear the stale staging file before writing",
                    staging.exists() && staging.length() == CANARY.length);
        assertFalse("the authorisation must have been spent by the export",
                    PlaintextExportGate.consumeAuthorisation());
    }

    // ---------------------------------------------------------------- helpers

    /** Sets the private deadline directly: the public way in is a dialog, which a test cannot tap. */
    private static void grantFor(final long millisFromNow) throws Exception
    {
        final Field f = PlaintextExportGate.class.getDeclaredField("authorisedUntilElapsedMs");
        f.setAccessible(true);
        f.setLong(null, SystemClock.elapsedRealtime() + millisFromNow);
    }

    private File plantStagingCanary() throws Exception
    {
        final File staging = new File(ctx.getCacheDir(), STAGING_NAME);
        try (OutputStream out = new FileOutputStream(staging))
        {
            out.write(CANARY);
        }
        assertTrue(staging.exists());
        return staging;
    }
}

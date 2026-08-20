package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (external audit: exported ConnectionManager receiver).
 *
 * <p>The finding: an exported receiver whose {@code onReceive} read caller-controlled extras and
 * scheduled reconnect work, so any app on the device could drive the connectivity path with an
 * explicit intent. These tests pin the two properties that close it — an exact action match, and a
 * decision that no intent extra can influence — plus an equivalence check against the old
 * expression, because a hardening change that quietly alters honest behaviour is its own bug.
 */
public class ConnectivityBroadcastPolicyTest
{
    // ------------------------------------------------------------------ the action gate (positive)

    @Test
    public void acceptsTheRealConnectivityAction()
    {
        assertTrue(ConnectivityBroadcastPolicy.accepts("android.net.conn.CONNECTIVITY_CHANGE"));
        assertTrue(ConnectivityBroadcastPolicy.accepts(ConnectivityBroadcastPolicy.CONNECTIVITY_ACTION));
    }

    // ------------------------------------------------------------------ the action gate (negative)

    @Test
    public void refusesAnExplicitIntentWithNoAction()
    {
        // The realistic attack shape: `new Intent().setComponent(theExportedReceiver)` carries no
        // action at all, because the real action is a protected broadcast a third party cannot send.
        assertFalse(ConnectivityBroadcastPolicy.accepts(null));
        assertFalse(ConnectivityBroadcastPolicy.accepts(""));
    }

    @Test
    public void refusesAnyOtherAction()
    {
        assertFalse(ConnectivityBroadcastPolicy.accepts("android.intent.action.BOOT_COMPLETED"));
        assertFalse(ConnectivityBroadcastPolicy.accepts("com.evil.app.PING"));
        assertFalse(ConnectivityBroadcastPolicy.accepts("android.net.conn.CONNECTIVITY_CHANGE_SUPL"));
    }

    @Test
    public void refusesActionsThatOnlyLookRight()
    {
        // Substring / prefix / case tricks: the match is exact, so none of these get through.
        assertFalse(ConnectivityBroadcastPolicy.accepts("android.net.conn.CONNECTIVITY_CHANG"));
        assertFalse(ConnectivityBroadcastPolicy.accepts(" android.net.conn.CONNECTIVITY_CHANGE"));
        assertFalse(ConnectivityBroadcastPolicy.accepts("android.net.conn.CONNECTIVITY_CHANGE "));
        assertFalse(ConnectivityBroadcastPolicy.accepts("ANDROID.NET.CONN.CONNECTIVITY_CHANGE"));
        assertFalse(ConnectivityBroadcastPolicy.accepts("x android.net.conn.CONNECTIVITY_CHANGE x"));
    }

    // -------------------------------------------------------------------- nothing foreign schedules

    @Test
    public void aForeignIntentNeverSchedulesReconnectEvenWhileOnline()
    {
        // The point of the finding: online is the normal state, so "online" must not be enough on
        // its own. Without the action gate this is exactly the call an unprivileged app could force.
        assertFalse(ConnectivityBroadcastPolicy.shouldScheduleReconnect(null, true));
        assertFalse(ConnectivityBroadcastPolicy.shouldScheduleReconnect("com.evil.app.PING", true));
    }

    @Test
    public void theRealBroadcastSchedulesOnlyWhenTheSystemSaysWeAreOnline()
    {
        assertTrue(ConnectivityBroadcastPolicy.shouldScheduleReconnect(
                ConnectivityBroadcastPolicy.CONNECTIVITY_ACTION, true));
        assertFalse(ConnectivityBroadcastPolicy.shouldScheduleReconnect(
                ConnectivityBroadcastPolicy.CONNECTIVITY_ACTION, false));
    }

    // ------------------------------------------------------------- no honest behaviour was changed

    /** The pre-fix expression, transcribed exactly, including the order the globals were written. */
    private static boolean legacyDecision(final boolean haveInternetFromSystem,
                                          final boolean noConnectivityExtra,
                                          final boolean failOverExtra)
    {
        boolean have = haveInternetFromSystem;
        if (noConnectivityExtra)
        {
            have = false;
        }
        return have && (failOverExtra || !noConnectivityExtra);
    }

    @Test
    public void matchesTheOldExpressionOnEveryInput()
    {
        for (int bits = 0; bits < 8; bits++)
        {
            final boolean haveFromSystem = (bits & 1) != 0;
            final boolean noConnectivity = (bits & 2) != 0;
            final boolean failOver = (bits & 4) != 0;

            // What the receiver now holds when it asks the policy: the system's answer, with the
            // system's own noConnectivity flag applied exactly as before.
            final boolean haveAfterOverride = haveFromSystem && !noConnectivity;

            assertEquals("bits=" + bits,
                         legacyDecision(haveFromSystem, noConnectivity, failOver),
                         ConnectivityBroadcastPolicy.shouldScheduleReconnect(
                                 ConnectivityBroadcastPolicy.CONNECTIVITY_ACTION, haveAfterOverride));
        }
    }
}

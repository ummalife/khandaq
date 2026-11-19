package com.zoffcc.applications.trifa;

import android.content.Context;
import android.os.PowerManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPowerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.64): a wakeup (a push, or the alarm) holds a wake lock of its own. Wakeups used to share one static
 * lock, which the first one set to null when its hold was over, so a second wakeup within the hold crashed the app in
 * isHeld() (live QA of 0.2.64: a text and a voice message pushed 18 seconds apart, the crash 20 seconds after the
 * second). The relay sends a push with a preview for every event, so wakeups that close together are ordinary.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WakeupAlarmReceiverTest
{
    private final List<Throwable> uncaught = Collections.synchronizedList(new ArrayList<>());
    private Thread.UncaughtExceptionHandler saved_handler;

    @Before
    public void catch_what_the_wakeup_threads_throw()
    {
        ShadowPowerManager.clearWakeLocks();
        saved_handler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> uncaught.add(e));
    }

    @After
    public void restore_the_handler()
    {
        Thread.setDefaultUncaughtExceptionHandler(saved_handler);
    }

    @Test
    public void two_wakeups_within_the_hold_each_take_and_release_a_lock_of_their_own() throws Exception
    {
        final Context context = ApplicationProvider.getApplicationContext();

        final Thread first = WakeupAlarmReceiver.start_wakeup(context, 400);
        final PowerManager.WakeLock first_lock = ShadowPowerManager.getLatestWakeLock();
        Thread.sleep(150);
        final Thread second = WakeupAlarmReceiver.start_wakeup(context, 400);
        final PowerManager.WakeLock second_lock = ShadowPowerManager.getLatestWakeLock();

        assertNotNull(first_lock);
        assertNotNull(second_lock);
        assertNotSame("each wakeup takes a lock of its own", first_lock, second_lock);
        assertTrue(first_lock.isHeld());
        assertTrue(second_lock.isHeld());

        first.join(5000);
        second.join(5000);
        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
        assertFalse("the first wakeup released its lock", first_lock.isHeld());
        assertFalse("the second wakeup released its lock", second_lock.isHeld());
        assertEquals("no wakeup thread may throw", Collections.emptyList(), new ArrayList<>(uncaught));
    }

    @Test
    public void a_wakeup_cut_short_still_releases_its_lock() throws Exception
    {
        final Context context = ApplicationProvider.getApplicationContext();

        final Thread wakeup = WakeupAlarmReceiver.start_wakeup(context, 60_000);
        final PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        assertNotNull(lock);
        assertTrue(lock.isHeld());

        wakeup.interrupt();
        wakeup.join(5000);
        assertFalse(wakeup.isAlive());
        assertFalse(lock.isHeld());
        assertEquals(Collections.emptyList(), new ArrayList<>(uncaught));
    }

    @Test
    public void the_receiver_holds_the_lock_for_twenty_seconds()
    {
        assertEquals(20_000L, WakeupAlarmReceiver.WAKE_HOLD_MS);
    }
}

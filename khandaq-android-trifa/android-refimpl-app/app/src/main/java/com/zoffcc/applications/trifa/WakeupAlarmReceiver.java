/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2019 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import static com.zoffcc.applications.trifa.HelperGeneric.append_logger_msg;
import static com.zoffcc.applications.trifa.HelperGeneric.trigger_proper_wakeup_outside_tox_service_thread;
import static com.zoffcc.applications.trifa.TrifaToxService.trifa_service_thread;

public class WakeupAlarmReceiver extends BroadcastReceiver
{
    private static final String TAG = "trifa.WakeupAlrmRcvr";
    /** How long one wakeup keeps the CPU awake, for Tox to connect and take what is waiting. */
    static final long WAKE_HOLD_MS = 20 * 1000;
    /** The wake lock's own timeout runs this far past the hold, in case its thread never gets to release it. */
    private static final long WAKE_LOCK_TIMEOUT_MARGIN_MS = 10 * 1000;

    @Override
    public void onReceive(Context context, Intent intent2)
    {
        start_wakeup(context, WAKE_HOLD_MS);
    }

    /**
     * KHANDAQ (0.2.64): one wakeup, with a wake lock of its own that the thread it starts holds for {@code hold_ms}
     * and then releases. Returns that thread, started.
     * <p>
     * All wakeups used to share one static wake lock, and the thread of each set it to null when its 20 seconds were
     * over. A second wakeup within those 20 seconds (two pushes, or a push and the alarm) found the lock held, took
     * none, and crashed the app in isHeld() when its own 20 seconds were over, because the first thread had set the
     * lock to null in between. The relay coalesces plain wakes to one in 45 seconds, but it sends a push with a
     * preview for every event, so two wakeups that close together are ordinary since 0.2.64.
     */
    static Thread start_wakeup(final Context context, final long hold_ms)
    {
        final PowerManager.WakeLock wakelock = acquire_wakelock(context, hold_ms + WAKE_LOCK_TIMEOUT_MARGIN_MS);
        final Thread t = new Thread("khandaq_wakeup")
        {
            @Override
            public void run()
            {
                try
                {
                    try
                    {
                        Log.i(TAG, "AlarmReceiver:" + "onReceive");
                        TrifaToxService.write_debug_file("AlarmReceiver_onReceive");

                        if (trifa_service_thread != null)
                        {
                            append_logger_msg(TAG + "::" + "need_wakeup_now trigger 004");
                            trigger_proper_wakeup_outside_tox_service_thread();
                            TrifaToxService.write_debug_file("AlarmReceiver_interrupt");
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                    try
                    {
                        Thread.sleep(hold_ms); // keep the wake lock for the hold
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                }
                finally
                {
                    release_wakelock(wakelock);
                }
            }
        };
        t.start();
        return t;
    }

    /** A partial wake lock for one wakeup, held until it is released or {@code timeout_ms} has passed; or null. */
    private static PowerManager.WakeLock acquire_wakelock(final Context context, final long timeout_ms)
    {
        try
        {
            final PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null)
            {
                return null;
            }
            final PowerManager.WakeLock wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                                  "trifa:trifa_wakeup_lock");
            // Taken once and released once, by its own thread, or by the timeout if that thread never gets there.
            wakelock.setReferenceCounted(false);
            wakelock.acquire(timeout_ms);
            Log.i(TAG, "acquiring wakelock");
            TrifaToxService.write_debug_file("AlarmReceiver_aq_wakelock");
            return wakelock;
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "acquire_wakelock:EE:" + e.getMessage());
            return null;
        }
    }

    private static void release_wakelock(final PowerManager.WakeLock wakelock)
    {
        if (wakelock == null)
        {
            return;
        }
        try
        {
            if (wakelock.isHeld())
            {
                Log.i(TAG, "releasing wakelock");
                TrifaToxService.write_debug_file("AlarmReceiver_rl_wakelock");
                wakelock.release();
            }
        }
        catch (RuntimeException e)
        {
            Log.i(TAG, "release_wakelock:EE:" + e.getMessage());
        }
    }
}

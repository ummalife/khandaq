package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.AudioRecording.NATIVE_AUDIO_ENGINE_START_TIMEOUT_MS;
import static com.zoffcc.applications.trifa.AudioRecording.should_keep_waiting_for_engine;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — the recording thread's wait for the native audio engine must always end.
 *
 * Play reported a user-perceived ANR rate of 2.17% against a crash rate of 0.00%. Nothing was
 * failing; something was hanging, and this was it.
 *
 * AudioRecording waited on AudioReceiver.native_audio_engine_running and on nothing else. That flag
 * is set only after NativeAudio.createEngine() returns (AudioReceiver:94) — inside a try/catch, so a
 * device where the engine fails to start leaves it false for the life of the process.
 * AudioRecording.close() sets `stopped`, which the wait did not read. AudioReceiver.close() sets the
 * flag to false, which is the very value the wait was hoping to leave. So no caller, in any order,
 * could end it.
 *
 * That mattered because HelperGeneric.stop_audio_system() then join()ed this thread with no timeout,
 * from the main thread — ConferenceAudioActivity.onPause(), CallingActivity.stop_active_call() and
 * ConfGroupAudioService.ButtonReceiver.onReceive() all reach it. Leaving a group call could freeze
 * the app permanently, and a freeze is an ANR, not a crash, which is exactly the shape reported.
 *
 * The condition is a pure predicate so that "this wait terminates" is a property a test can hold
 * rather than a claim about code nobody re-reads. The last case below is the one that matters: over
 * every combination of inputs, the loop makes progress towards ending.
 */
public class NativeAudioEngineWaitTest
{
    private static final int T = NATIVE_AUDIO_ENGINE_START_TIMEOUT_MS;

    @Test
    public void waits_while_the_engine_is_down_and_nobody_asked_to_stop()
    {
        assertTrue(should_keep_waiting_for_engine(false, false, 0, T));
        assertTrue(should_keep_waiting_for_engine(false, false, T - 100, T));
    }

    @Test
    public void stops_once_the_engine_is_up()
    {
        assertFalse(should_keep_waiting_for_engine(true, false, 0, T));
    }

    /** The bug. close() sets `stopped`, and the old wait had no idea. */
    @Test
    public void stops_when_close_was_called()
    {
        assertFalse(should_keep_waiting_for_engine(false, true, 0, T));
        assertFalse(should_keep_waiting_for_engine(false, true, T - 100, T));
    }

    /** The other half of the bug: an engine that never comes up must not be waited on forever. */
    @Test
    public void stops_at_the_deadline_even_if_the_engine_never_arrives()
    {
        assertTrue(should_keep_waiting_for_engine(false, false, T - 1, T));
        assertFalse(should_keep_waiting_for_engine(false, false, T, T));
        assertFalse(should_keep_waiting_for_engine(false, false, T + 5000, T));
    }

    /** A deadline already past on entry ends the wait immediately rather than on the next tick. */
    @Test
    public void a_zero_budget_never_waits()
    {
        assertFalse(should_keep_waiting_for_engine(false, false, 0, 0));
    }

    /**
     * The property the old code broke: from any state, advancing the clock ends the wait. Walked
     * exhaustively rather than argued, because "it obviously terminates" is what was believed about
     * the version that did not.
     */
    @Test
    public void the_wait_always_terminates()
    {
        for (boolean engine : new boolean[]{false, true})
        {
            for (boolean stop : new boolean[]{false, true})
            {
                int waited = 0;
                int ticks = 0;
                while (should_keep_waiting_for_engine(engine, stop, waited, T))
                {
                    waited += 100;
                    ticks++;
                    if (ticks > (T / 100) + 10)
                    {
                        throw new AssertionError(
                                "wait did not end: engine_running=" + engine + " stop_requested=" + stop);
                    }
                }
                assertTrue("must end within the budget", waited <= T);
            }
        }
    }

    /** An interrupted sleep still advances the clock in run(); a caller that forgets would hang. */
    @Test
    public void progress_is_what_ends_it_not_the_engine()
    {
        int waited = 0;
        while (should_keep_waiting_for_engine(false, false, waited, T))
        {
            waited += 100;
        }
        assertFalse(should_keep_waiting_for_engine(false, false, waited, T));
    }
}

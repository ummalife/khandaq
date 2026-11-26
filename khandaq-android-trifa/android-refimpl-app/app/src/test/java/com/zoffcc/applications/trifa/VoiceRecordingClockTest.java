package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (Khandaq Testers 8249): a voice note can pause while it records hands-free, and its time leaves the
 * pauses out: the timer of the bar stops while paused and goes on from there. The clock reads a fake time here;
 * nothing is recorded.
 */
public class VoiceRecordingClockTest
{
    /** A time that moves only when the test says so. */
    private static final class FakeTime implements VoiceRecordingClock.TimeSource
    {
        long now = 1_000_000L;

        @Override
        public long nowMs()
        {
            return now;
        }
    }

    @Test
    public void a_running_note_counts_the_time_from_its_start()
    {
        final FakeTime time = new FakeTime();
        final VoiceRecordingClock clock = new VoiceRecordingClock(time);
        assertEquals(0L, clock.elapsedMs());
        clock.start();
        assertEquals(0L, clock.elapsedMs());
        time.now += 1_500L;
        assertEquals(1_500L, clock.elapsedMs());
        assertEquals(1L, clock.elapsedSeconds());
        assertFalse(clock.isPaused());
    }

    @Test
    public void a_pause_stops_the_time_and_a_resume_goes_on_from_there()
    {
        final FakeTime time = new FakeTime();
        final VoiceRecordingClock clock = new VoiceRecordingClock(time);
        clock.start();
        time.now += 2_000L;
        clock.pause();
        assertTrue(clock.isPaused());
        time.now += 60_000L; // a minute paused
        assertEquals(2_000L, clock.elapsedMs());
        clock.resume();
        assertFalse(clock.isPaused());
        assertEquals(2_000L, clock.elapsedMs());
        time.now += 3_500L;
        assertEquals(5_500L, clock.elapsedMs());
        assertEquals(5L, clock.elapsedSeconds());
    }

    @Test
    public void several_pauses_leave_all_of_them_out()
    {
        final FakeTime time = new FakeTime();
        final VoiceRecordingClock clock = new VoiceRecordingClock(time);
        clock.start();
        for (int i = 0; i < 5; i++)
        {
            time.now += 1_000L; // recorded
            clock.pause();
            time.now += 10_000L; // paused
            clock.resume();
        }
        assertEquals(5_000L, clock.elapsedMs());
    }

    @Test
    public void pausing_twice_or_resuming_a_running_note_changes_nothing()
    {
        final FakeTime time = new FakeTime();
        final VoiceRecordingClock clock = new VoiceRecordingClock(time);
        clock.start();
        time.now += 1_000L;
        clock.resume(); // runs already
        time.now += 1_000L;
        clock.pause();
        time.now += 1_000L;
        clock.pause(); // paused already: the paused second stays out
        time.now += 1_000L;
        assertEquals(2_000L, clock.elapsedMs());
        clock.resume();
        time.now += 500L;
        assertEquals(2_500L, clock.elapsedMs());
    }

    @Test
    public void a_clock_not_started_neither_runs_nor_resumes()
    {
        final FakeTime time = new FakeTime();
        final VoiceRecordingClock clock = new VoiceRecordingClock(time);
        clock.resume();
        time.now += 5_000L;
        assertEquals(0L, clock.elapsedMs());
        assertFalse(clock.isPaused());
        clock.pause();
        assertEquals(0L, clock.elapsedMs());
    }

    @Test
    public void a_stopped_clock_holds_its_time_for_good()
    {
        final FakeTime time = new FakeTime();
        final VoiceRecordingClock clock = new VoiceRecordingClock(time);
        clock.start();
        time.now += 4_200L;
        clock.stop();
        time.now += 10_000L;
        clock.resume();
        time.now += 10_000L;
        assertEquals(4_200L, clock.elapsedMs());
        assertFalse(clock.isPaused());
        // Stopped while paused: the time of the pause stays out.
        final VoiceRecordingClock paused = new VoiceRecordingClock(time);
        paused.start();
        time.now += 3_000L;
        paused.pause();
        time.now += 7_000L;
        paused.stop();
        assertEquals(3_000L, paused.elapsedMs());
    }

    @Test
    public void a_clock_starts_once()
    {
        final FakeTime time = new FakeTime();
        final VoiceRecordingClock clock = new VoiceRecordingClock(time);
        clock.start();
        time.now += 3_000L;
        clock.start();
        assertEquals(3_000L, clock.elapsedMs());
    }

    @Test
    public void a_time_source_that_steps_back_counts_no_time()
    {
        final FakeTime time = new FakeTime();
        final VoiceRecordingClock clock = new VoiceRecordingClock(time);
        clock.start();
        time.now += 2_000L;
        clock.pause();
        clock.resume();
        time.now -= 5_000L;
        assertEquals(2_000L, clock.elapsedMs());
    }

    @Test
    public void the_default_clock_runs_on_a_monotonic_time()
    {
        final VoiceRecordingClock clock = new VoiceRecordingClock();
        clock.start();
        final long first = clock.elapsedMs();
        assertTrue(first >= 0L);
        assertTrue(clock.elapsedMs() >= first);
    }
}

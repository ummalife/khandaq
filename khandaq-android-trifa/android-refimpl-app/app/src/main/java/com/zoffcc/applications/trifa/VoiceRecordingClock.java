package com.zoffcc.applications.trifa;

/**
 * KHANDAQ (Khandaq Testers 8249): the time a voice note has recorded. A pause stops it and a resume goes on
 * from where it stopped, so the timer of the recording bar and the length of the note leave the pauses out.
 *
 * <p>The recording thread reads it while the bar pauses and resumes it on the main thread, hence the locks.
 * The time source is monotonic and can be replaced in tests.
 */
final class VoiceRecordingClock
{
    interface TimeSource
    {
        long nowMs();
    }

    private final TimeSource time;
    /** The time of the stretches that have ended, by a pause or the stop. */
    private long recordedMs = 0L;
    /** When the stretch that runs now began. */
    private long runningSinceMs = 0L;
    private boolean started = false;
    private boolean running = false;
    private boolean stopped = false;

    VoiceRecordingClock()
    {
        this(() -> System.nanoTime() / 1_000_000L);
    }

    VoiceRecordingClock(final TimeSource time)
    {
        this.time = time;
    }

    /** Starts from nothing. A clock starts once. */
    synchronized void start()
    {
        if (started)
        {
            return;
        }
        started = true;
        running = true;
        recordedMs = 0L;
        runningSinceMs = time.nowMs();
    }

    synchronized void pause()
    {
        if (running)
        {
            recordedMs += stretchMs();
            running = false;
        }
    }

    synchronized void resume()
    {
        if (started && !stopped && !running)
        {
            runningSinceMs = time.nowMs();
            running = true;
        }
    }

    /** Holds the time for good: a stopped clock neither runs nor resumes. */
    synchronized void stop()
    {
        pause();
        stopped = true;
    }

    synchronized boolean isPaused()
    {
        return started && !stopped && !running;
    }

    synchronized long elapsedMs()
    {
        return recordedMs + (running ? stretchMs() : 0L);
    }

    long elapsedSeconds()
    {
        return elapsedMs() / 1000L;
    }

    /** The running stretch; a time source that steps back counts as no time. */
    private long stretchMs()
    {
        return Math.max(0L, time.nowMs() - runningSinceMs);
    }
}

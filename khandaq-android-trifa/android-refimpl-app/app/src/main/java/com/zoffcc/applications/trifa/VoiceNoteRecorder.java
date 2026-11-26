package com.zoffcc.applications.trifa;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.io.File;

/**
 * KHANDAQ (Khandaq Testers 8249): the recorder of one voice note, which can pause and go on into the same
 * note. The piasy AudioRecorder used before cannot pause; MediaRecorder can since Android 7, and the app
 * needs Android 8. The note keeps the format it always had: AAC in MPEG-4 at 44.1 kHz and 64 kbps from the
 * microphone, set up in the order AudioRecorder set it up.
 *
 * <p>The time of the note leaves out its pauses (VoiceRecordingClock), and so does the file, as MediaRecorder
 * joins the stretches. Stopping a running note that is kept waits 300 ms first, as AudioRecorder did, so that
 * the last word reaches the file. A paused note has nothing more to catch, and a cancelled one is thrown
 * away: both stop at once and free the microphone for the next note.
 */
final class VoiceNoteRecorder
{
    private static final String TAG = "trifa.VoiceNoteRec";
    static final int SAMPLE_RATE_HZ = 44100;
    static final int BIT_RATE_BPS = 64000;
    /** How long AudioRecorder let a running note go on after it was asked to stop. */
    private static final long STOP_DELAY_MS = 300L;

    private final VoiceRecordingClock clock = new VoiceRecordingClock();
    private MediaRecorder recorder = null;
    private boolean paused = false;
    private boolean stopped = false;

    /** Starts recording into the file; false when the microphone or the encoder would not start. */
    @SuppressWarnings("deprecation") // MediaRecorder(Context) is there from Android 12 on only
    synchronized boolean start(final Context context, final File output)
    {
        if ((recorder != null) || stopped)
        {
            return false;
        }
        final MediaRecorder r = ((context != null) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S))
                                ? new MediaRecorder(context) : new MediaRecorder();
        try
        {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioSamplingRate(SAMPLE_RATE_HZ);
            r.setAudioEncodingBitRate(BIT_RATE_BPS);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setOutputFile(output.getAbsolutePath());
            r.prepare();
            r.start();
        }
        catch (Exception e)
        {
            Log.w(TAG, "start:EE:" + e.getMessage());
            close(r);
            stopped = true;
            return false;
        }
        recorder = r;
        clock.start();
        return true;
    }

    /** Pauses a running note: the microphone and the clock stop. True when the note is paused now. */
    synchronized boolean pause()
    {
        if ((recorder == null) || stopped)
        {
            return false;
        }
        if (paused)
        {
            return true;
        }
        try
        {
            recorder.pause();
        }
        catch (Exception e)
        {
            Log.w(TAG, "pause:EE:" + e.getMessage());
            return false;
        }
        paused = true;
        clock.pause();
        return true;
    }

    /** Goes on with a paused note, into the same file. True when the note records again. */
    synchronized boolean resume()
    {
        if ((recorder == null) || stopped)
        {
            return false;
        }
        if (!paused)
        {
            return true;
        }
        try
        {
            recorder.resume();
        }
        catch (Exception e)
        {
            Log.w(TAG, "resume:EE:" + e.getMessage());
            return false;
        }
        paused = false;
        clock.resume();
        return true;
    }

    /** Pauses a running note or goes on with a paused one; says whether it is paused now. */
    synchronized boolean togglePause()
    {
        return paused ? !resume() : pause();
    }

    synchronized boolean isPaused()
    {
        return paused;
    }

    /** The seconds recorded so far, without the pauses. */
    long elapsedSeconds()
    {
        return clock.elapsedSeconds();
    }

    /**
     * Stops and closes the note; keep says whether it is going to be sent. Returns its seconds without the
     * pauses, or -1 when there is nothing to keep: it never started, it was stopped already, or it stopped
     * before any sound reached the file. AudioRecorder.stopRecord answered so.
     */
    int stop(final boolean keep)
    {
        final boolean running;
        synchronized (this)
        {
            if ((recorder == null) || stopped)
            {
                stopped = true;
                return -1;
            }
            running = !paused;
        }
        if (keep && running)
        {
            try
            {
                Thread.sleep(STOP_DELAY_MS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this)
        {
            final MediaRecorder r = recorder;
            recorder = null;
            stopped = true;
            clock.stop();
            if (r == null)
            {
                return -1;
            }
            int seconds = -1;
            try
            {
                r.stop();
                seconds = (int) clock.elapsedSeconds();
            }
            catch (Exception e)
            {
                Log.w(TAG, "stop:EE(no audio data recorded):" + e.getMessage());
            }
            close(r);
            return seconds;
        }
    }

    private static void close(final MediaRecorder r)
    {
        try
        {
            r.reset();
        }
        catch (Exception e)
        {
            Log.w(TAG, "reset:EE:" + e.getMessage());
        }
        try
        {
            r.release();
        }
        catch (Exception e)
        {
            Log.w(TAG, "release:EE:" + e.getMessage());
        }
    }
}

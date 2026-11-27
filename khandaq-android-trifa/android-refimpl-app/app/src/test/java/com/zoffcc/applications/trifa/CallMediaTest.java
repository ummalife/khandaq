package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (calls, 27.11): the sound and the video of a 1:1 call as the testers' calls asked for them: the platform's
 * echo canceller runs on every route, the microphone and the speaker are not pushed into clipping, the audio is not
 * started again as the screen goes, the screen stays on through the call, and a weak link can take the video down.
 * The recorder is configured in C and the call screen needs a running call, so those are read from their sources.
 */
public class CallMediaTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String NATIVE_AUDIO = "../native-audio-jni/src/main/cpp/native-audio-jni.c";

    private static String read(final String path) throws IOException
    {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** The source from the first occurrence of {@code from} to the first occurrence of {@code to} after it. */
    private static String between(final String source, final String from, final String to)
    {
        final int start = source.indexOf(from);
        assertTrue("missing: " + from, start >= 0);
        final int end = source.indexOf(to, start);
        assertTrue("missing after " + from + ": " + to, end > start);
        return source.substring(start, end);
    }

    // ---- the sound ----

    @Test
    public void aCallRecordsForVoiceCommunicationWithThePlatformsProcessing() throws IOException
    {
        final String recorder = between(read(NATIVE_AUDIO), "createAudioRecorder:SL_IID_ANDROIDCONFIGURATION...",
                                        "(*recorderObject)->Realize(");
        assertTrue(recorder.contains("SLuint32 presetValue = SL_ANDROID_RECORDING_PRESET_VOICE_COMMUNICATION;"));
        assertFalse(recorder.contains("presetValue = SL_ANDROID_RECORDING_PRESET_VOICE_RECOGNITION;"));
        // SL_ANDROID_PERFORMANCE_LATENCY takes all pre-processing off, the echo canceller with it.
        assertTrue(recorder.contains("SLuint32 presetValue2 = SL_ANDROID_PERFORMANCE_NONE;"));
        assertFalse(recorder.contains("presetValue2 = SL_ANDROID_PERFORMANCE_LATENCY"));
    }

    @Test
    public void theMicrophoneIsNotTripledBeforeTheGainControl() throws IOException
    {
        final String source = read(NATIVE_AUDIO);
        final int gain = source.indexOf("const int gain_manually = 3;");
        assertTrue(gain > 0);
        final int conditional = source.lastIndexOf("#if ", gain);
        assertEquals("#if 0", source.substring(conditional, conditional + 5));
        assertTrue(source.indexOf("#endif", conditional) > gain);
    }

    @Test
    public void theSpeakerIsNotBoostedAndTheEarpieceKeepsItsDouble() throws IOException
    {
        final String processing = between(read(JAVA + "CallingActivity.java"),
                                          "static void applyCallAudioProcessing(boolean speaker)", "catch (Exception e)");
        assertTrue(processing.contains("na_set_call_playback_gain(speaker ? 1 : 2);"));
        assertTrue(processing.contains("set_aec_active(1);"));
    }

    @Test
    public void leavingTheCallScreenDoesNotStartTheAudioAgain() throws IOException
    {
        final String onPause = between(read(JAVA + "CallingActivity.java"), "protected void onPause()",
                                       "sensor_manager.unregisterListener(this);");
        assertFalse(onPause.contains("restart_audio_system"));
        assertFalse(onPause.contains("set_rec_preset"));
    }

    @Test
    public void theCallScreenKeepsTheScreenOnOnEveryAndroid() throws IOException
    {
        final String onCreate = between(read(JAVA + "CallingActivity.java"), "if (Build.VERSION.SDK_INT >= 27)",
                                        "super.onCreate(savedInstanceState);");
        final int newer = onCreate.indexOf("setShowWhenLocked(true);");
        final int older = onCreate.indexOf("else");
        assertTrue(newer > 0 && older > newer);
        assertTrue(onCreate.substring(newer, older).contains("FLAG_KEEP_SCREEN_ON"));
        assertTrue(onCreate.substring(older).contains("FLAG_KEEP_SCREEN_ON"));
    }

    // ---- the video ----

    @Test
    public void aWeakLinkCanTakeTheVideoDownWhateverTheQuality()
    {
        // The high quality, the default, held the video at 1500 kbit/s at least, above the mobile ceilings.
        assertTrue(TRIFAGlobals.VIDEO_ENCODER_MIN_BITRATE_LOW <= 300);
        assertTrue(TRIFAGlobals.VIDEO_ENCODER_MIN_BITRATE_MED <= 300);
        assertTrue(TRIFAGlobals.VIDEO_ENCODER_MIN_BITRATE_HIGH <= 300);
        assertTrue(TRIFAGlobals.GLOBAL_MIN_VIDEO_BITRATE <= TRIFAGlobals.VIDEO_ENCODER_MIN_BITRATE_LOW);
        assertTrue(TRIFAGlobals.VIDEO_ENCODER_MIN_BITRATE_HIGH < TRIFAGlobals.VIDEO_BITRATE_3G_CEILING);
    }

    @Test
    public void aMobileLinkStartsAndTopsOutAt1000() throws IOException
    {
        assertEquals(1000, TRIFAGlobals.VIDEO_BITRATE_CELLULAR_CEILING);
        assertEquals(400, TRIFAGlobals.VIDEO_BITRATE_3G_CEILING);
        assertTrue(TRIFAGlobals.VIDEO_BITRATE_CELLULAR_CEILING < TRIFAGlobals.VIDEO_BITRATE_WIFI_CEILING);
        // The ceiling holds the encoder's top as well as the rate a call starts at.
        final String limits = between(read(JAVA + "CallingActivity.java"), "public static void set_min_and_max_video_bitrate()",
                                      "TOXAV_ENCODER_VIDEO_MAX_BITRATE");
        assertTrue(limits.contains("applyNetworkVideoBitrateCap(context_s, value1)"));
    }
}

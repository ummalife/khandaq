package com.zoffcc.applications.trifa;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (Khandaq Testers 8249): a voice note recorded hands-free can pause and go on into the same note: a
 * 1:1 chat once the recording is locked, a group from the start. The piasy AudioRecorder cannot pause, so both
 * chats record with VoiceNoteRecorder, in the format notes always had. Views and MediaRecorder cannot run in a
 * JVM test here and nothing may be recorded, so this reads the resources and the sources, as the other audit
 * tests do; VoiceRecordingClockTest covers the time.
 */
public class VoiceRecordingPauseTest
{
    private static final String DIR = "src/main/";
    private static final String JAVA = DIR + "java/com/zoffcc/applications/trifa/";
    private static final String RES = DIR + "res/";
    private static final String[] LOCALES = {"values", "values-ru", "values-ar", "values-zh-rCN"};

    @Test
    public void the_bar_has_a_pause_button_named_by_what_it_does()
    {
        final Element pause = byId(xml(RES + "layout/chat_voice_recording_bar.xml"), "@+id/voice_recording_pause");
        assertNotNull(pause);
        assertEquals("ImageButton", pause.getTagName());
        assertEquals("@style/Widget.KQ.Air.IconButton", pause.getAttribute("style"));
        assertEquals("@string/voice_recording_pause", pause.getAttribute("android:contentDescription"));
        assertEquals("@drawable/air_voice_ic_pause", pause.getAttribute("android:src"));
        // Hidden until the recording runs hands-free.
        assertEquals("gone", pause.getAttribute("android:visibility"));
        // The icons are the design system's: the voice note's two bars, and the mic that goes on recording.
        assertTrue(new File(RES + "drawable/air_voice_ic_pause.xml").isFile());
        assertTrue(new File(RES + "drawable/kq_ic_mic.xml").isFile());
    }

    @Test
    public void pause_and_resume_are_said_in_every_language_the_app_ships()
    {
        for (final String locale : LOCALES)
        {
            final Document strings = xml(RES + locale + "/strings.xml");
            final String pause = string(strings, "voice_recording_pause");
            final String resume = string(strings, "voice_recording_resume");
            assertFalse(locale, pause.trim().isEmpty());
            assertFalse(locale, resume.trim().isEmpty());
            assertNotEquals(locale, pause, resume);
        }
        assertEquals("Pause recording", string(xml(RES + "values/strings.xml"), "voice_recording_pause"));
        assertEquals("Resume recording", string(xml(RES + "values/strings.xml"), "voice_recording_resume"));
    }

    @Test
    public void the_button_swaps_its_glyph_and_words_and_shows_only_hands_free()
    {
        final String helper = read(JAVA + "ChatVoiceRecordingUiHelper.java");
        assertTrue(helper.contains("findViewById(R.id.voice_recording_pause)"));
        assertTrue(helper.contains("paused ? R.drawable.kq_ic_mic : R.drawable.air_voice_ic_pause"));
        assertTrue(helper.contains("paused ? R.string.voice_recording_resume : R.string.voice_recording_pause"));
        assertTrue(helper.contains("(pauseWhileRecording || locked) ? View.VISIBLE : View.GONE"));
        assertTrue(helper.contains("setPausedUi(actionListener.onTogglePause());"));
        // A 1:1 chat shows it once the slide up locks the recording; a group from the start.
        final String direct = read(JAVA + "MessageListActivity.java");
        final int lock = direct.indexOf("voice_rec_locked = true;");
        assertTrue(lock > 0);
        assertTrue(direct.indexOf("voiceRecordingUiHelper.setLockedUi(true);", lock) > lock);
        assertTrue(read(JAVA + "GroupMessageListActivity.java")
                           .contains("voiceRecordingUiHelper.setPauseWhileRecording(true);"));
    }

    @Test
    public void both_chats_record_with_the_recorder_that_pauses()
    {
        for (final String activity : new String[]{"MessageListActivity.java", "GroupMessageListActivity.java"})
        {
            final String source = read(JAVA + activity);
            assertFalse(activity, source.contains("rxandroidaudio.AudioRecorder"));
            assertFalse(activity, source.contains("AudioRecorder.getInstance()"));
            assertTrue(activity, source.contains("final VoiceNoteRecorder mAudioRecorder = new VoiceNoteRecorder();"));
            assertTrue(activity, source.contains("mAudioRecorder.start(v.getContext(), mAudioFile)"));
            assertTrue(activity, source.contains("voice_note_recorder.set(mAudioRecorder);"));
            // The timer shows the recorded time, which holds still while paused.
            assertTrue(activity, source.contains("seconds_time_format_or_empty(mAudioRecorder.elapsedSeconds())"));
            // A kept note gets its last 300 ms; whatever happened, the microphone is given up.
            assertTrue(activity, source.contains("mAudioRecorder.stop(ml_is_rec_ok)"));
            assertTrue(activity, source.contains("mAudioRecorder.stop(false);"));
            assertTrue(activity, source.contains("voice_note_recorder.compareAndSet(mAudioRecorder, null);"));
            assertTrue(activity, source.contains("recorder.togglePause()"));
        }
    }

    @Test
    public void the_note_keeps_the_format_it_had()
    {
        final String recorder = read(JAVA + "VoiceNoteRecorder.java");
        final String[] setup = {
                "r.setAudioSource(MediaRecorder.AudioSource.MIC);",
                "r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);",
                "r.setAudioSamplingRate(SAMPLE_RATE_HZ);",
                "r.setAudioEncodingBitRate(BIT_RATE_BPS);",
                "r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);",
                "r.setOutputFile(output.getAbsolutePath());",
                "r.prepare();",
                "r.start();"};
        int at = 0;
        for (final String step : setup)
        {
            final int next = recorder.indexOf(step, at);
            assertTrue(step, next >= at);
            at = next;
        }
        assertEquals(44100, VoiceNoteRecorder.SAMPLE_RATE_HZ);
        assertEquals(64000, VoiceNoteRecorder.BIT_RATE_BPS);
        // Pause and resume are MediaRecorder's own, from Android 7 on; the app needs Android 8.
        assertTrue(recorder.contains("recorder.pause();"));
        assertTrue(recorder.contains("recorder.resume();"));
        // The voice-note marker of the file name stays, so the chats still show a voice note.
        for (final String activity : new String[]{"MessageListActivity.java", "GroupMessageListActivity.java"})
        {
            assertTrue(activity, read(JAVA + activity).contains("+ \".file.m4a\";"));
        }
    }

    private static String read(final String path)
    {
        try
        {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            throw new AssertionError(path, e);
        }
    }

    private static Document xml(final String path)
    {
        try
        {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(path));
        }
        catch (Exception e)
        {
            throw new AssertionError(path, e);
        }
    }

    private static Element byId(final Document document, final String id)
    {
        final NodeList all = document.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++)
        {
            final Element e = (Element) all.item(i);
            if (id.equals(e.getAttribute("android:id")))
            {
                return e;
            }
        }
        return null;
    }

    private static String string(final Document strings, final String name)
    {
        final NodeList all = strings.getElementsByTagName("string");
        for (int i = 0; i < all.getLength(); i++)
        {
            final Element e = (Element) all.item(i);
            if (name.equals(e.getAttribute("name")))
            {
                return e.getTextContent();
            }
        }
        throw new AssertionError("no string " + name);
    }
}

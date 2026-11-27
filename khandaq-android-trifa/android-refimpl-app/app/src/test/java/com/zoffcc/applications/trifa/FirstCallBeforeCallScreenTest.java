package com.zoffcc.applications.trifa;

import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The first audio call after the app started could go nowhere. The outgoing call's thread does not wait for a
 * camera on an audio call, so it updated the call screen about 0.2 s after starting, and on a slow phone
 * CallingActivity.onCreate had not made the screen's handler yet. Posting to no handler threw, and the throw ended
 * the thread before toxav_call: the screen said "Calling" and the other side never rang. A second call worked,
 * because the first screen had left its handler behind.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class FirstCallBeforeCallScreenTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";

    @After
    public void noScreenAfterEachTest()
    {
        CallingActivity.callactivity_handler_s = null;
    }

    @Test
    public void updatesBeforeTheCallScreenExistsDoNotThrow()
    {
        CallingActivity.callactivity_handler_s = null;

        CallingActivity.update_top_text_line("0s", 3);
        CallingActivity.update_top_text_line();
        CallingActivity.update_calling_friend_connection_status(ToxVars.TOX_CONNECTION.TOX_CONNECTION_TCP.value);

        assertEquals("the text is kept for the screen to read when it is created", "0s",
                     CallingActivity.top_text_line_str3);
        assertFalse(CallingActivity.post_to_call_screen(() -> {
        }));
    }

    @Test
    public void updatesReachTheCallScreenOnceItExists()
    {
        CallingActivity.callactivity_handler_s = new Handler(Looper.getMainLooper());
        final boolean[] ran = {false};

        assertTrue(CallingActivity.post_to_call_screen(() -> ran[0] = true));
        ShadowLooper.idleMainLooper();

        assertTrue(ran[0]);
    }

    @Test
    public void theScreensTextCannotStopTheCallFromBeingPlaced() throws IOException
    {
        final String source = new String(Files.readAllBytes(Paths.get(JAVA + "MessageListActivity.java")),
                                          StandardCharsets.UTF_8);
        final int start = source.indexOf("CallingActivity.top_text_line_str2 = \"0s\";");
        assertTrue("the outgoing call's first screen update", start >= 0);
        final int call = source.indexOf("MainActivity.toxav_call(", start);
        assertTrue("toxav_call after it", call > start);

        final String beforeTheCall = source.substring(start, call);
        final int update = beforeTheCall.indexOf("update_top_text_line();");
        assertTrue(update >= 0);
        final int ownTry = beforeTheCall.lastIndexOf("try", update);
        final int ownCatch = beforeTheCall.indexOf("catch (Exception e)", update);
        assertTrue("the update sits in its own try", ownTry >= 0);
        assertTrue("and its own catch, before toxav_call", ownCatch > update);
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): what a wake push does. A data-only wake reaches the app in the background too: it wakes a
 * running Tox and shows nothing itself (Tox shows the message or the call), and with Tox down it tells the user
 * once that somebody is trying to reach them. A notification message keeps doing what it did.
 */
public class WakePushTest
{
    @Test
    public void a_notification_message_only_wakes_tox_as_before()
    {
        assertEquals(WakePush.Action.NUDGE, WakePush.decide(false, true, true));
        assertEquals(WakePush.Action.NUDGE, WakePush.decide(false, false, true));
        assertEquals(WakePush.Action.NUDGE, WakePush.decide(false, false, false));
    }

    @Test
    public void a_data_only_wake_with_tox_running_wakes_it_and_shows_nothing_itself()
    {
        assertEquals(WakePush.Action.NUDGE, WakePush.decide(true, true, true));
        assertEquals(WakePush.Action.NUDGE, WakePush.decide(true, true, false));
    }

    @Test
    public void a_data_only_wake_with_tox_down_tells_the_user()
    {
        assertEquals(WakePush.Action.NOTIFY, WakePush.decide(true, false, true));
    }

    @Test
    public void a_data_only_wake_with_tox_down_and_notifications_off_shows_nothing()
    {
        assertEquals(WakePush.Action.NONE, WakePush.decide(true, false, false));
    }

    @Test
    public void the_wake_broadcast_is_the_one_the_receiver_listens_for() throws IOException
    {
        final String manifest = read("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml");
        final Matcher receiver = Pattern.compile(
                "<receiver android:name=\"com\\.zoffcc\\.applications\\.trifa\\.WakeupAlarmReceiver\"(.*?)</receiver>",
                Pattern.DOTALL).matcher(manifest);
        assertTrue("WakeupAlarmReceiver is in the manifest", receiver.find());
        assertTrue(receiver.group(1).contains("android:exported=\"false\""));
        assertTrue(receiver.group(1).contains("<action android:name=\"" + WakePush.WAKE_ACTION + "\""));
    }

    @Test
    public void the_fcm_service_hands_every_wake_to_wake_push() throws IOException
    {
        final String service = read("src/main/java/org/khandaq/messenger/KhandaqFirebaseMessagingService.java",
                                    "app/src/main/java/org/khandaq/messenger/KhandaqFirebaseMessagingService.java");
        assertTrue(service.contains("WakePush.onWake(this, message.getNotification() == null)"));
    }

    private static String read(final String... candidates) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String candidate : candidates)
        {
            final File file = new File(candidate);
            if (file.isFile())
            {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(candidates[0] + " not found from " + new File("").getAbsolutePath());
    }
}

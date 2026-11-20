package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (testers' ideas, 0.2.67): Settings, Notifications has a row that opens the phone's settings of the
 * background channel, where the notification of the background service can be hidden while Khandaq keeps running.
 */
public class BackgroundNotificationRowTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";

    @Test
    public void the_row_is_there_from_android_8_where_notifications_have_channels()
    {
        assertFalse(HelperToxNotification.hasChannelSettings(25));
        assertTrue(HelperToxNotification.hasChannelSettings(26));
        assertTrue(HelperToxNotification.hasChannelSettings(36));
        // The channel of the notification the background service shows.
        assertEquals("khandaq_background_service", HelperToxNotification.CHANNEL_ID_TOX_SERVICE);
        final String helper = read(JAVA + "HelperToxNotification.java");
        assertTrue(method(helper, "private static Notification build_foreground_notification(final Context c)")
                           .contains("new NotificationCompat.Builder(c, CHANNEL_ID_TOX_SERVICE)"));
    }

    @Test
    public void the_row_stands_under_message_text_with_what_hiding_means()
    {
        final String settings = read(JAVA + "SettingsTabFragment.java");
        final int guard = settings.indexOf("if (HelperToxNotification.hasChannelSettings(Build.VERSION.SDK_INT))");
        final int row = settings.indexOf(
                "background = navigationRow(context, R.string.air_settings_background_notification,");
        assertTrue(guard > 0 && row > guard);
        assertTrue(settings.indexOf("background.detail(getString(R.string.air_settings_background_notification_note));")
                   > row);
        assertTrue(settings.indexOf("R.string.settings_notif_preview") < row);
        // plateOf leaves out a row that is null: below Android 8 the plate holds Show message text alone.
        assertTrue(settings.contains("notificationsPlate = AirScreenKit.plateOf(context, preview, background);"));
        assertTrue(settings.contains("AirScreenKit.addSection(column, getString(R.string.air_settings_notifications),"
                                     + " null, notificationsPlate);"));
        final String kit = read(JAVA + "AirScreenKit.java");
        assertTrue(method(kit, "static LinearLayout plateOf(final Context context, final View... rows)")
                           .contains("if (row != null)"));
    }

    @Test
    public void the_row_opens_the_channel_page_then_the_app_page_and_says_when_neither_opens()
    {
        final String helper = read(JAVA + "HelperToxNotification.java");
        final String open = method(helper, "static boolean openChannelSettings(final Activity activity)");
        final int channel = open.indexOf("new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)");
        final int app = open.indexOf("new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)");
        assertTrue(channel > 0 && app > channel);
        final String channelIntent = open.substring(channel, app);
        assertTrue(channelIntent.contains(".putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())"));
        assertTrue(channelIntent.contains(".putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID_TOX_SERVICE);"));
        assertTrue(open.contains("for (final Intent page : new Intent[]{channel, app})"));
        assertTrue(open.contains("return false;"));

        final String settings = read(JAVA + "SettingsTabFragment.java");
        final String tapped = method(settings, "private void openBackgroundNotificationSettings()");
        final int opened = tapped.indexOf("if (HelperToxNotification.openChannelSettings(activity))");
        assertTrue(opened > 0);
        assertTrue(tapped.indexOf("AirUi.endUnder(notificationsPlate);", opened) > opened);
        assertTrue(tapped.contains("AirSheet.sayUnderOrTell(notificationsPlate, activity,"));
        assertTrue(tapped.contains("getString(R.string.air_settings_background_notification_failed), true);"));
    }

    @Test
    public void the_row_speaks_in_all_four_languages()
    {
        final String[][] titles = {
                {"values", "Background notification"},
                {"values-ru", "Фоновое уведомление"},
                {"values-ar", "إشعار الخلفية"},
                {"values-zh-rCN", "后台通知"},
        };
        for (final String[] language : titles)
        {
            final String air = read(RES + language[0] + "/strings_air.xml");
            assertEquals(language[0], language[1], value(air, "air_settings_background_notification", language[0]));
            for (final String name : new String[]{"air_settings_background_notification_note",
                                                  "air_settings_background_notification_failed"})
            {
                final String words = value(air, name, language[0]);
                assertTrue(language[0] + " " + name, words.contains("Khandaq"));
                assertFalse(language[0] + " " + name, words.contains("Tox"));
            }
        }
        assertEquals("Khandaq работает в фоне, чтобы получать сообщения. Это уведомление можно скрыть, приложение"
                     + " продолжит работать.",
                     value(read(RES + "values-ru/strings_air.xml"), "air_settings_background_notification_note", "ru"));
    }

    private static String value(final String xml, final String name, final String language)
    {
        final Matcher found = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(xml);
        assertTrue(language + " " + name, found.find());
        return found.group(1);
    }

    /** The body of the method whose declaration starts with {@code signature}. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start > 0);
        assertEquals(signature + " is declared once", start, source.lastIndexOf(signature));
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name + ": " + e.getMessage());
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

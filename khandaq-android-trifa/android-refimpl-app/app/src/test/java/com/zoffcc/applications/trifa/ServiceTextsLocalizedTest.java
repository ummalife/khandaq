package com.zoffcc.applications.trifa;

import org.junit.Test;
import org.khandaq.messenger.R;

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
 * KHANDAQ (testers' ideas, 0.2.67): the texts release users saw in English whatever their language, some with "Tox"
 * in them: the call and group video notifications and their channels, the crash screen, the group service lines and
 * the buttons of the media notification. They are string resources in the four languages now.
 */
public class ServiceTextsLocalizedTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";
    private static final String[] LANGUAGES = {"values", "values-ru", "values-ar", "values-zh-rCN"};
    private static final String[] NEW_STRINGS = {
            "notification_channel_call", "notification_channel_call_desc", "notification_call_title",
            "notification_call_text", "notification_channel_group_video", "notification_channel_group_video_desc",
            "notification_group_video_text", "notification_media_stop", "crash_title", "crash_restart",
            "crash_send_email", "crash_send_later", "group_sys_you_created_open", "group_sys_you_created_invite_only",
            "group_sys_privacy_open", "group_sys_privacy_invite_only", "group_sys_peer_now_moderator",
            "group_sys_peer_now_member", "group_sys_peer_now_observer"};

    @Test
    public void who_can_join_and_a_role_are_said_in_the_app_s_words()
    {
        assertEquals(R.string.group_sys_privacy_open,
                     HelperGroup.group_privacy_line_res(ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PUBLIC.value));
        assertEquals(R.string.group_sys_privacy_invite_only,
                     HelperGroup.group_privacy_line_res(ToxVars.TOX_GROUP_PRIVACY_STATE.TOX_GROUP_PRIVACY_STATE_PRIVATE.value));
        assertEquals(0, HelperGroup.group_privacy_line_res(7));
        assertEquals(R.string.group_sys_peer_now_moderator,
                     HelperGroup.group_role_line_res(ToxVars.Tox_Group_Mod_Event.TOX_GROUP_MOD_EVENT_MODERATOR.value));
        assertEquals(R.string.group_sys_peer_now_member,
                     HelperGroup.group_role_line_res(ToxVars.Tox_Group_Mod_Event.TOX_GROUP_MOD_EVENT_USER.value));
        assertEquals(R.string.group_sys_peer_now_observer,
                     HelperGroup.group_role_line_res(ToxVars.Tox_Group_Mod_Event.TOX_GROUP_MOD_EVENT_OBSERVER.value));
        // A kick sets no role and the line of the member leaving says it.
        assertEquals(0, HelperGroup.group_role_line_res(ToxVars.Tox_Group_Mod_Event.TOX_GROUP_MOD_EVENT_KICK.value));
    }

    @Test
    public void lines_stored_in_english_before_are_said_the_same_way()
    {
        assertEquals(R.string.group_sys_you_created_open,
                     HelperGroup.legacy_group_line_res("You created the Group (as Public Group)"));
        assertEquals(R.string.group_sys_you_created_invite_only,
                     HelperGroup.legacy_group_line_res("You created the Group (as Private [invite only] Group)"));
        assertEquals(R.string.group_sys_privacy_open,
                     HelperGroup.legacy_group_line_res("privacy state changed to: TOX_GROUP_PRIVACY_STATE_PUBLIC"));
        assertEquals(R.string.group_sys_privacy_invite_only,
                     HelperGroup.legacy_group_line_res("privacy state changed to: TOX_GROUP_PRIVACY_STATE_PRIVATE"));
        assertEquals(R.string.group_sys_peer_now_moderator,
                     HelperGroup.legacy_group_line_res("peer 3 changed role to TOX_GROUP_MOD_EVENT_MODERATOR"));
        assertEquals(R.string.group_sys_peer_now_observer,
                     HelperGroup.legacy_group_line_res("peer 12 changed role to TOX_GROUP_MOD_EVENT_OBSERVER"));
        assertEquals(R.string.group_sys_peer_now_member,
                     HelperGroup.legacy_group_line_res("peer 0 changed role to TOX_GROUP_MOD_EVENT_USER"));
        assertEquals(3L, HelperGroup.legacy_role_line_peer("peer 3 changed role to TOX_GROUP_MOD_EVENT_MODERATOR"));

        // Anything else keeps the way it was shown. A kick was never stored as a role line.
        assertEquals(0, HelperGroup.legacy_group_line_res("peer 3 changed role to TOX_GROUP_MOD_EVENT_KICK"));
        for (final String other : new String[]{null, "", "You joined the group", "privacy state changed to: UNKNOWN",
                                               "peer Amina changed role to TOX_GROUP_MOD_EVENT_USER",
                                               "peer 3 changed role to TOX_GROUP_MOD_EVENT_USER today",
                                               "Amina joined the group"})
        {
            assertEquals(String.valueOf(other), 0, HelperGroup.legacy_group_line_res(other));
            assertEquals(String.valueOf(other), -1L, HelperGroup.legacy_role_line_peer(other));
        }

        final String helper = read(JAVA + "HelperGroup.java");
        final String display = method(helper, "static String formatSystemMessageForDisplay(final Context context, final String raw)");
        final int legacy = display.indexOf("final int legacy = legacy_group_line_res(raw);");
        assertTrue(legacy > 0);
        assertTrue(legacy < display.indexOf("if (raw.startsWith(\"You joined the group\"))"));
        assertTrue(display.contains("context.getString(R.string.group_sys_peer_number, peer)"));
    }

    @Test
    public void new_service_lines_are_stored_in_the_language_of_the_phone()
    {
        final String main = read(JAVA + "MainActivity.java");
        assertTrue(main.contains("add_system_message_to_group_chat(group_identifier, HelperGroup.group_privacy_line(a_TOX_GROUP_PRIVACY_STATE));"));
        assertTrue(main.contains("add_system_message_to_group_chat(group_identifier, HelperGroup.group_joined_line());"));
        assertTrue(main.contains("HelperGroup.group_role_line(group_number, target_peer_id, a_Tox_Group_Mod_Event));"));
        assertTrue(main.contains("HelperGroup.group_created_line(true),"));
        assertTrue(main.contains("HelperGroup.group_created_line(false));"));
        final String create = read(JAVA + "AirNewGroupActivity.java");
        assertTrue(create.contains("HelperGroup.group_created_line(inviteOnly), null, text -> failure[0] = text);"));
        for (final String source : new String[]{main, create})
        {
            assertFalse(source.contains("\"You created the Group"));
            assertFalse(source.contains("\"You joined the group\""));
            assertFalse(source.contains("\"privacy state changed to: \""));
            assertFalse(source.contains("\" changed role to \""));
        }
        // Who the line is about is found by name, as for joins and leaves.
        assertTrue(method(read(JAVA + "HelperGroup.java"),
                          "static String group_role_line(final long group_number, final long peer_id, final int mod_event)")
                           .contains("resolveGroupPeerLabel(group_number, peer_id)"));
    }

    @Test
    public void the_notifications_and_their_channels_speak_the_language_of_the_phone()
    {
        final String call = read(JAVA + "CallAudioService.java");
        assertFalse(call.contains("\"Tox:\""));
        assertTrue(call.contains("final String call_title = getString(R.string.notification_call_title);"));
        assertTrue(call.contains("b.setContentText(getString(R.string.notification_call_text));"));
        // TalkBack: the microphone and End, as on the call screen.
        assertTrue(call.contains("shown.setContentDescription(R.id.status_bar_play, getString(R.string.air_call_mic));"));
        assertTrue(call.contains("shown.setContentDescription(R.id.status_bar_stop, getString(R.string.air_call_end));"));

        final String calling = read(JAVA + "CallingActivity.java");
        assertFalse(calling.contains("\"Tox Call\""));
        assertTrue(calling.contains("String channelName = getString(R.string.notification_channel_call);"));
        assertTrue(calling.contains(
                "notification_channel_call_audio_play_service.setDescription(getString(R.string.notification_channel_call_desc));"));
        assertFalse(calling.contains("notification_channel_call_audio_play_service.setDescription(channelId);"));

        final String group = read(JAVA + "GroupMessageListActivity.java");
        assertFalse(group.contains("\"Tox NGC Group Audio Play\""));
        assertTrue(group.contains("String channelName = getString(R.string.notification_channel_group_video);"));
        assertTrue(group.contains("getString(R.string.notification_channel_group_video_desc));"));
        assertFalse(group.contains("setDescription(ngc_channelId);"));

        final String video = read(JAVA + "GroupGroupAudioService.java");
        assertFalse(video.contains("\"Tox:\""));
        assertFalse(video.contains("\"#G1\""));
        assertFalse(video.contains("\"#G2\""));
        assertTrue(video.contains("b.setContentText(getString(R.string.notification_group_video_text));"));
        assertTrue(video.contains("HelperGroup.get_effective_group_title("));

        // The background channel keeps the description HelperToxNotification gives it.
        final String main = read(JAVA + "MainActivity.java");
        assertFalse(main.contains("notification_channel_toxservice.setDescription(channelId_toxservice);"));
        assertTrue(main.contains(
                "notification_channel_toxservice.setDescription(getString(R.string.notification_channel_toxservice_desc));"));
        assertTrue(read(JAVA + "HelperToxNotification.java").contains(
                "channel.setDescription(c.getString(R.string.notification_channel_toxservice_desc));"));
    }

    @Test
    public void the_crash_screen_and_the_media_buttons_take_their_words_from_resources()
    {
        final String crash = read(RES + "layout/activity_crash.xml");
        final Matcher text = Pattern.compile("android:text=\"([^\"]*)\"").matcher(crash);
        int words = 0;
        while (text.find())
        {
            assertTrue(text.group(1), text.group(1).isEmpty() || text.group(1).startsWith("@string/crash_"));
            words += text.group(1).isEmpty() ? 0 : 1;
        }
        assertEquals(4, words);
        for (final String layout : new String[]{"gas_status_bar.xml", "gas_status_bar_expanded.xml"})
        {
            final String xml = read(RES + "layout/" + layout);
            assertTrue(layout, xml.contains("android:contentDescription=\"@string/air_voice_pause\""));
            assertTrue(layout, xml.contains("android:contentDescription=\"@string/notification_media_stop\""));
            assertFalse(layout, xml.contains("android:contentDescription=\"Pause\""));
            assertFalse(layout, xml.contains("android:contentDescription=\"Stop\""));
        }
    }

    @Test
    public void every_new_text_is_there_in_all_four_languages_and_none_says_tox()
    {
        for (final String language : LANGUAGES)
        {
            final String strings = read(RES + language + "/strings.xml");
            for (final String name : NEW_STRINGS)
            {
                final Matcher found = Pattern.compile("<string name=\"" + name + "\">([^<]+)</string>").matcher(strings);
                assertTrue(language + " " + name, found.find());
                final String value = found.group(1);
                assertFalse(language + " " + name + ": " + value, value.contains("Tox"));
                assertEquals(language + " " + name, name.startsWith("group_sys_peer_now_"), value.contains("%1$s"));
            }
            assertTrue(language, read(RES + language + "/strings_air_files.xml").contains("<string name=\"air_voice_pause\">"));
        }
        // The Russian reads as the rest of the app.
        final String ru = read(RES + "values-ru/strings.xml");
        assertTrue(ru.contains("<string name=\"notification_call_title\">Звонок</string>"));
        assertTrue(ru.contains("<string name=\"group_sys_peer_now_moderator\">%1$s теперь модератор</string>"));
        assertTrue(ru.contains("<string name=\"crash_restart\">Нажмите в любом месте, чтобы перезапустить Khandaq</string>"));
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

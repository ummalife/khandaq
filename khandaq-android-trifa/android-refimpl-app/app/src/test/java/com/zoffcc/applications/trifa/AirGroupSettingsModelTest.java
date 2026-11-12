package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_FOUNDER;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_MODERATOR;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_OBSERVER;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_USER;
import static com.zoffcc.applications.trifa.AirGroupModel.VOICE_ALL;
import static com.zoffcc.applications.trifa.AirGroupModel.VOICE_FOUNDER;
import static com.zoffcc.applications.trifa.AirGroupModel.VOICE_MODERATOR;
import static com.zoffcc.applications.trifa.AirGroupSettingsModel.TOPIC_LOCK_DISABLED;
import static com.zoffcc.applications.trifa.AirGroupSettingsModel.TOPIC_LOCK_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The rules of the group settings (GroupInfoActivity), as GroupInfoController and AirGroupSettingsText on iOS. */
public class AirGroupSettingsModelTest
{
    @Test
    public void the_topic_lock_is_toxcores_values()
    {
        assertEquals(0, TOPIC_LOCK_ENABLED);
        assertEquals(1, TOPIC_LOCK_DISABLED);
    }

    @Test
    public void with_the_lock_on_only_the_founder_and_the_moderators_change_the_topic()
    {
        assertTrue(AirGroupSettingsModel.canEditTopic(ROLE_FOUNDER, TOPIC_LOCK_ENABLED));
        assertTrue(AirGroupSettingsModel.canEditTopic(ROLE_MODERATOR, TOPIC_LOCK_ENABLED));
        assertFalse(AirGroupSettingsModel.canEditTopic(ROLE_USER, TOPIC_LOCK_ENABLED));
        assertFalse(AirGroupSettingsModel.canEditTopic(ROLE_OBSERVER, TOPIC_LOCK_ENABLED));
    }

    @Test
    public void with_the_lock_off_everyone_but_observers_changes_the_topic()
    {
        assertTrue(AirGroupSettingsModel.canEditTopic(ROLE_FOUNDER, TOPIC_LOCK_DISABLED));
        assertTrue(AirGroupSettingsModel.canEditTopic(ROLE_MODERATOR, TOPIC_LOCK_DISABLED));
        assertTrue(AirGroupSettingsModel.canEditTopic(ROLE_USER, TOPIC_LOCK_DISABLED));
        assertFalse(AirGroupSettingsModel.canEditTopic(ROLE_OBSERVER, TOPIC_LOCK_DISABLED));
    }

    @Test
    public void an_unknown_lock_or_role_leaves_the_attempt_to_toxcore_but_never_for_an_observer()
    {
        // The lock unread (an old native library gives -99, a failed query a negative error).
        assertTrue(AirGroupSettingsModel.canEditTopic(ROLE_USER, -99));
        assertTrue(AirGroupSettingsModel.canEditTopic(ROLE_USER, -3));
        assertFalse(AirGroupSettingsModel.canEditTopic(ROLE_OBSERVER, -99));
        // The role unread.
        assertTrue(AirGroupSettingsModel.canEditTopic(-1, TOPIC_LOCK_ENABLED));
        assertTrue(AirGroupSettingsModel.canEditTopic(-1, TOPIC_LOCK_DISABLED));
    }

    @Test
    public void a_limit_is_digits_from_1_to_65535()
    {
        assertEquals(100, AirGroupSettingsModel.peerLimit("100"));
        assertEquals(1, AirGroupSettingsModel.peerLimit("1"));
        assertEquals(65535, AirGroupSettingsModel.peerLimit("65535"));
        assertEquals(42, AirGroupSettingsModel.peerLimit("  42 "));
        assertEquals(7, AirGroupSettingsModel.peerLimit("0007"));
        assertEquals(0, AirGroupSettingsModel.peerLimit("0"));
        assertEquals(0, AirGroupSettingsModel.peerLimit("65536"));
        assertEquals(0, AirGroupSettingsModel.peerLimit("1000000"));
        assertEquals(0, AirGroupSettingsModel.peerLimit(""));
        assertEquals(0, AirGroupSettingsModel.peerLimit("   "));
        assertEquals(0, AirGroupSettingsModel.peerLimit(null));
    }

    @Test
    public void a_limit_refuses_signs_separators_and_letters()
    {
        assertEquals(0, AirGroupSettingsModel.peerLimit("-5"));
        assertEquals(0, AirGroupSettingsModel.peerLimit("+5"));
        assertEquals(0, AirGroupSettingsModel.peerLimit("1,000"));
        assertEquals(0, AirGroupSettingsModel.peerLimit("1 000"));
        assertEquals(0, AirGroupSettingsModel.peerLimit("12a"));
        assertEquals(0, AirGroupSettingsModel.peerLimit("1.5"));
        // Superscript two has a numeric value but is no decimal digit.
        assertEquals(0, AirGroupSettingsModel.peerLimit("²"));
    }

    @Test
    public void a_limit_reads_the_digits_of_other_scripts()
    {
        // Arabic-Indic, as the Arabic keyboard types them, and extended Arabic-Indic (Persian, Urdu).
        assertEquals(100, AirGroupSettingsModel.peerLimit("١٠٠"));
        assertEquals(250, AirGroupSettingsModel.peerLimit("۲۵۰"));
        // Full-width digits from a Chinese keyboard.
        assertEquals(64, AirGroupSettingsModel.peerLimit("６４"));
    }

    @Test
    public void the_group_is_full_at_its_limit()
    {
        assertTrue(AirGroupSettingsModel.isFull(100, 100));
        assertTrue(AirGroupSettingsModel.isFull(101, 100));
        assertFalse(AirGroupSettingsModel.isFull(99, 100));
        // No limit known: never said to be full.
        assertFalse(AirGroupSettingsModel.isFull(5, 0));
        assertFalse(AirGroupSettingsModel.isFull(5, -1));
    }

    @Test
    public void who_can_write_lists_all_then_moderators_then_the_founder()
    {
        assertEquals(0, AirGroupSettingsModel.voiceChoice(VOICE_ALL));
        assertEquals(1, AirGroupSettingsModel.voiceChoice(VOICE_MODERATOR));
        assertEquals(2, AirGroupSettingsModel.voiceChoice(VOICE_FOUNDER));
        assertEquals(-1, AirGroupSettingsModel.voiceChoice(-2));
        assertEquals(-1, AirGroupSettingsModel.voiceChoice(3));
    }

    @Test
    public void who_can_write_is_toxcores_voice_state()
    {
        assertEquals(ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_ALL.value, VOICE_ALL);
        assertEquals(ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_MODERATOR.value, VOICE_MODERATOR);
        assertEquals(ToxVars.Tox_Group_Voice_State.TOX_GROUP_VOICE_STATE_FOUNDER.value, VOICE_FOUNDER);
    }

    @Test
    public void the_screen_makes_the_old_screens_calls_and_changes_nothing_on_leaving() throws IOException
    {
        final String source = source("GroupInfoActivity.java");
        for (final String call : new String[]{"ensure_group_in_tox(", "tox_group_self_set_name(",
                                              "save_group_title_if_changed(",
                                              "tox_group_founder_set_privacy_state__wrapper(",
                                              "update_group_in_db_privacy_state(",
                                              "tox_group_founder_set_password__wrapper(",
                                              "tox_group_founder_set_topic_lock__wrapper(",
                                              "tox_group_founder_set_peer_limit(",
                                              "tox_group_founder_set_voice_state(",
                                              "update_savedata_file_wrapper_async("})
        {
            assertTrue(call, source.contains(call));
        }
        // The old screen sent the name, the topic and the limit when it was left; only Save sends them now.
        assertFalse(source.contains("void onPause("));
        // Who can join reads in words, never as toxcore's names for it.
        assertFalse(source.contains("\"PUBLIC\""));
        assertFalse(source.contains("\"PRIVATE\""));
    }

    private static String source(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final File file = new File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

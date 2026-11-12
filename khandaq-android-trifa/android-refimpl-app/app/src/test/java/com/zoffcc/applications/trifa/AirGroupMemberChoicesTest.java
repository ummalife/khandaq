package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.ADD_FRIEND;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.BOT_INFO;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.COPY_ID;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.DETAILS;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.KICK;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.MAKE_MODERATOR;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.MAKE_OBSERVER;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.MAKE_USER;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.MUTE;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.OPEN_CHAT;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.PRIVATE_MESSAGE;
import static com.zoffcc.applications.trifa.AirGroupMemberChoices.Choice.UNMUTE;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_FOUNDER;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_MODERATOR;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_OBSERVER;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_UNKNOWN;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_USER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What the sheet of a group member and the member's own screen offer (redesign stage 5): the order of
 * GroupPeerActionsPresenter on iOS, and only what this phone's role allows, so nothing stands disabled.
 */
public class AirGroupMemberChoicesTest
{
    @Test
    public void the_sheet_keeps_the_ios_order_with_removing_before_the_roles()
    {
        assertEquals(Arrays.asList(PRIVATE_MESSAGE, ADD_FRIEND, COPY_ID, MUTE, KICK, MAKE_MODERATOR, MAKE_OBSERVER,
                                   DETAILS),
                     AirGroupMemberChoices.sheet(false, false, ROLE_USER, ROLE_FOUNDER, false));
        assertEquals(Arrays.asList(PRIVATE_MESSAGE, OPEN_CHAT, COPY_ID, UNMUTE, KICK, MAKE_USER, DETAILS),
                     AirGroupMemberChoices.sheet(true, true, ROLE_OBSERVER, ROLE_MODERATOR, false));
    }

    @Test
    public void the_sheet_of_a_plain_member_offers_no_role_or_removal()
    {
        assertEquals(Arrays.asList(PRIVATE_MESSAGE, ADD_FRIEND, COPY_ID, MUTE, DETAILS),
                     AirGroupMemberChoices.sheet(false, false, ROLE_MODERATOR, ROLE_USER, false));
        // A moderator over another moderator, or anyone over the founder: nothing to change.
        assertEquals(Arrays.asList(PRIVATE_MESSAGE, OPEN_CHAT, COPY_ID, MUTE, DETAILS),
                     AirGroupMemberChoices.sheet(true, false, ROLE_MODERATOR, ROLE_MODERATOR, false));
        assertEquals(Arrays.asList(PRIVATE_MESSAGE, OPEN_CHAT, COPY_ID, MUTE, DETAILS),
                     AirGroupMemberChoices.sheet(true, false, ROLE_FOUNDER, ROLE_FOUNDER, false));
    }

    @Test
    public void a_role_not_shown_yet_offers_no_role_or_removal_even_to_the_founder()
    {
        // The group is not connected: the rows show no role (AirGroupModel.shownRole), so neither do the choices.
        assertEquals(Arrays.asList(PRIVATE_MESSAGE, ADD_FRIEND, COPY_ID, MUTE, DETAILS),
                     AirGroupMemberChoices.sheet(false, false, ROLE_UNKNOWN, ROLE_FOUNDER, false));
        assertEquals(Arrays.asList(ADD_FRIEND, MUTE),
                     AirGroupMemberChoices.screen(false, false, ROLE_UNKNOWN, ROLE_FOUNDER));
    }

    @Test
    public void a_test_client_also_says_what_it_is()
    {
        final List<AirGroupMemberChoices.Choice> choices = AirGroupMemberChoices.sheet(false, false, ROLE_USER, ROLE_USER,
                                                                                       true);
        assertEquals(BOT_INFO, choices.get(choices.size() - 1));
        assertFalse(AirGroupMemberChoices.sheet(false, false, ROLE_USER, ROLE_USER, false).contains(BOT_INFO));
    }

    @Test
    public void the_screen_puts_removing_last_and_your_own_screen_has_no_actions()
    {
        assertEquals(Arrays.asList(ADD_FRIEND, MUTE, MAKE_MODERATOR, MAKE_OBSERVER, KICK),
                     AirGroupMemberChoices.screen(false, false, ROLE_USER, ROLE_FOUNDER));
        assertEquals(Arrays.asList(OPEN_CHAT, MUTE, MAKE_USER, KICK),
                     AirGroupMemberChoices.screen(false, true, ROLE_OBSERVER, ROLE_MODERATOR));
        assertEquals(Arrays.asList(OPEN_CHAT, MUTE),
                     AirGroupMemberChoices.screen(false, true, ROLE_USER, ROLE_OBSERVER));
        assertTrue(AirGroupMemberChoices.screen(true, false, ROLE_FOUNDER, ROLE_FOUNDER).isEmpty());
    }

    @Test
    public void each_role_choice_stands_for_its_member_action_and_back()
    {
        for (final AirGroupModel.MemberAction action : AirGroupModel.MemberAction.values())
        {
            assertEquals(action, AirGroupMemberChoices.action(AirGroupMemberChoices.of(action)));
        }
        assertNull(AirGroupMemberChoices.action(PRIVATE_MESSAGE));
        assertNull(AirGroupMemberChoices.action(MUTE));
        assertNull(AirGroupMemberChoices.action(DETAILS));
    }

    @Test
    public void leaving_the_member_screen_never_sends_and_the_old_list_dialog_is_gone() throws Exception
    {
        final String screen = read("GroupPeerInfoActivity.java");
        final int pause = screen.indexOf("protected void onPause()");
        assertTrue(pause >= 0);
        final String onPause = screen.substring(pause, screen.indexOf("super.onPause();", pause));
        assertFalse(onPause.contains("sendPrivateGroupMessage"));
        assertFalse(onPause.contains("tox_group_send_private_message"));
        assertTrue(onPause.contains("keepPrivateDraft"));

        final String actions = read("GroupMemberActions.java");
        assertFalse(actions.contains("AlertDialog"));
        assertTrue(actions.contains("AirGroupMemberChoices.sheet("));
    }

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + "src/main/java/com/zoffcc/applications/trifa/" + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

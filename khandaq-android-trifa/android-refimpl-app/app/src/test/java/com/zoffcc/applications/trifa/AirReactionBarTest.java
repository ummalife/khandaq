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
 * KHANDAQ (testers' ideas, 0.2.67): a message held down in a chat or a group shows the seven quick reactions in a
 * bar over it (AirReactionBar), with the selection bar at the top. A tap toggles our reaction as the React dialog
 * does and ends the selection; React stays in the menu.
 */
public class AirReactionBarTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";

    @Test
    public void the_bar_stands_above_the_message_or_below_it_at_the_top_of_the_log()
    {
        // Room above: 4 above the message.
        assertEquals(440, AirReactionBar.top(500, 600, 56, 4, 1000));
        assertEquals(0, AirReactionBar.top(60, 100, 56, 4, 1000));
        // The message at the top of the log: 4 below it.
        assertEquals(134, AirReactionBar.top(30, 130, 56, 4, 1000));
        // The message fills the log: the bar at its top, over the message.
        assertEquals(4, AirReactionBar.top(-50, 990, 56, 4, 1000));
        // A log lower than the bar (the keyboard up on a small phone): at its top.
        assertEquals(0, AirReactionBar.top(-10, 30, 56, 4, 40));
    }

    @Test
    public void the_bar_is_away_while_its_message_is_scrolled_away()
    {
        assertFalse(AirReactionBar.shows(-100, -1, 1000));
        assertFalse(AirReactionBar.shows(-100, 0, 1000));
        assertTrue(AirReactionBar.shows(-100, 1, 1000));
        assertTrue(AirReactionBar.shows(999, 1100, 1000));
        assertFalse(AirReactionBar.shows(1000, 1100, 1000));
        assertFalse(AirReactionBar.shows(10, 10, 1000));
    }

    @Test
    public void each_emoji_has_its_target_or_a_share_of_a_narrow_phone()
    {
        // Seven 48dp targets and 4dp on both sides.
        assertEquals(344, AirReactionBar.width(7, 48, 4, 400));
        assertEquals(330, AirReactionBar.width(7, 48, 4, 330));
        // Not laid out yet: the natural width.
        assertEquals(344, AirReactionBar.width(7, 48, 4, 0));
        assertEquals(344, AirReactionBar.width(7, 48, 4, -16));
    }

    @Test
    public void the_bar_offers_the_seven_of_the_dialog_and_toggles_them_the_same_way()
    {
        assertEquals(7, HelperMessageReaction.QUICK_REACTIONS.length);
        final String bar = read(JAVA + "AirReactionBar.java");
        assertTrue(bar.contains("for (final String emoji : HelperMessageReaction.QUICK_REACTIONS)"));
        assertTrue(bar.contains("final String own = HelperMessageReaction.ownEmojiOf(inGroup, id);"));
        assertTrue(bar.contains("cell.setSelected(ours);"));
        // A pick toggles, then ends the selection.
        final String pick = method(bar, "private void pick(final String emoji)");
        assertTrue(pick.indexOf("HelperMessageReaction.toggleOwnReaction(group, id, emoji);") < pick.indexOf("end.run();"));
        // Taps on the pill do not fall through to the message under it.
        assertTrue(bar.contains("plate.setOnTouchListener((v, event) -> true);"));
        // No shadow: in the design only a toast, a dialog and a sheet cast one.
        assertTrue(bar.contains("plate.setOutlineProvider(null);"));

        final String helper = read(JAVA + "HelperMessageReaction.java");
        final String toggle = method(helper, "static void toggleOwnReaction(final boolean group, final long id, final String emoji)");
        assertTrue(toggle.contains("orma.selectFromGroupMessage().idEq(id).toList()"));
        assertTrue(toggle.contains("toggleOwnGroupReaction(rows.get(0), emoji);"));
        assertTrue(toggle.contains("orma.selectFromMessage().idEq(id).toList()"));
        assertTrue(toggle.contains("toggleOwnFriendReaction(rows.get(0), emoji);"));
        // The dialog of the menu toggles with the same two.
        assertTrue(helper.contains("emoji -> toggleOwnFriendReaction(m, emoji)"));
        assertTrue(helper.contains("emoji -> toggleOwnGroupReaction(gm, emoji)"));
        final String own = method(helper, "static String ownEmojiOf(final boolean group, final long id)");
        assertEquals(2, count(own, "actorEmoji(rows.get(0).reactions, OWN_MARKER)"));
    }

    @Test
    public void the_selection_bar_brings_the_bar_and_keeps_react_in_its_menu()
    {
        final String mode = read(JAVA + "ToolbarActionMode.java");
        assertTrue(method(mode, "public boolean onCreateActionMode(").contains("reaction_bar = new AirReactionBar(context);"));
        final String prepare = method(mode, "public boolean onPrepareActionMode(");
        final int can = prepare.indexOf("final boolean can_react = HelperMessageReaction.canReactToCurrentSelection();");
        assertTrue(can > 0);
        assertTrue(prepare.indexOf("reactItem.setVisible(can_react);") > can);
        assertTrue(prepare.indexOf("reaction_bar.update(can_react, mode::finish);") > can);
        assertTrue(method(mode, "public void onDestroyActionMode(").contains("reaction_bar.dismiss();"));
        // React stays in the menu, with the dialog behind it.
        final String menu = read(RES + "menu/toolbar_message_activity.xml");
        assertTrue(menu.contains("android:id=\"@+id/action_react\""));
        assertTrue(mode.contains("HelperMessageReaction.reactSelectedDirectMessage(context);"));
        assertTrue(mode.contains("HelperMessageReaction.reactSelectedGroupMessage(context);"));
    }

    @Test
    public void both_chats_ask_again_once_the_held_message_is_selected()
    {
        // The selection bar starts before the message joins the selection; the chats prepare it again after.
        final String direct = method(read(JAVA + "MessageListActivity.java"), "static long_click_message_return onLongClick_message_helper(");
        assertTrue(direct.indexOf("amode.invalidate();") > direct.indexOf("selected_messages.add(message_.id);"));
        final String group = method(read(JAVA + "GroupMessageListActivity.java"),
                                    "static GroupMessageListActivity.long_click_message_return onLongClick_message_helper(");
        assertTrue(group.indexOf("amode.invalidate();") > group.indexOf("selected_group_messages.add(message_.id);"));
        // The bar finds the log of either chat by one id, in the layout it can stand over.
        for (final String layout : new String[]{"message_list_layout.xml", "group_message_list_layout.xml"})
        {
            final String xml = read(RES + "layout/" + layout);
            assertTrue(layout, xml.contains("<RelativeLayout"));
            assertTrue(layout, xml.contains("android:id=\"@+id/msg_rv_list\""));
        }
        assertTrue(read(JAVA + "AirReactionBar.java").contains("activity.findViewById(R.id.msg_rv_list)"));
    }

    @Test
    public void the_bar_looks_as_a_raised_pill_and_is_named_in_all_four_languages()
    {
        final String plate = read(RES + "drawable/air_bg_reaction_bar.xml");
        assertTrue(plate.contains("@color/kq_raised"));
        assertTrue(plate.contains("@color/kq_line_strong"));
        assertTrue(plate.contains("@dimen/kq_radius_round"));
        assertTrue(read(RES + "values/air_dimens.xml").contains("<dimen name=\"air_reaction_bar_emoji\">24dp</dimen>"));
        // TalkBack names the bar as it appears with the menu's word for it.
        assertTrue(read(JAVA + "AirReactionBar.java")
                           .contains("ViewCompat.setAccessibilityPaneTitle(plate, context.getString(R.string.chat_react_action));"));
        final String[][] expected = {
                {"values", "React"},
                {"values-ru", "Реакция"},
                {"values-ar", "تفاعل"},
                {"values-zh-rCN", "回应"},
        };
        for (final String[] language : expected)
        {
            final Matcher found = Pattern.compile("<string name=\"chat_react_action\">([^<]*)</string>")
                                         .matcher(read(RES + language[0] + "/strings.xml"));
            assertTrue(language[0], found.find());
            assertEquals(language[0], language[1], found.group(1));
        }
    }

    private static int count(final String text, final String part)
    {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length()))
        {
            count++;
        }
        return count;
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

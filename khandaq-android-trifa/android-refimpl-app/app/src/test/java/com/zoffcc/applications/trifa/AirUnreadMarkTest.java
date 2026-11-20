package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (testers' ideas, 0.2.67): a chat or a group opened with unread messages opens at the first of them,
 * under "Unread messages" (AirUnreadMark). What was unread is read before the chat clears it, the first row of
 * the log among it carries the mark, and the mark stays there until the chat is left.
 */
public class AirUnreadMarkTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";

    /** A log showing these message ids, in this order. */
    private static final class Log implements AirUnreadMark.Rows
    {
        long[] ids;

        Log(final long... ids)
        {
            this.ids = ids;
        }

        @Override
        public long idAt(final int position)
        {
            return (position >= 0 && position < ids.length) ? ids[position] : AirUnreadMark.NONE;
        }
    }

    private static Set<Long> ids(final Long... ids)
    {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test
    public void the_first_row_of_the_log_among_the_unread_carries_the_mark()
    {
        // A group sorts its log by the later of sent and received: the unread row with the lowest id need not be
        // the first one shown.
        final Log log = new Log(10, 11, 15, 12, 16);
        assertEquals(2, AirUnreadMark.firstUnreadPosition(log, 5, ids(12L, 15L, 16L)));
        // Rows the log leaves out (hidden system lines, a filter) are no place for the mark.
        assertEquals(-1, AirUnreadMark.firstUnreadPosition(log, 5, ids(99L)));
        assertEquals(-1, AirUnreadMark.firstUnreadPosition(log, 5, ids()));
        assertEquals(-1, AirUnreadMark.firstUnreadPosition(log, 2, ids(15L)));
        assertEquals(-1, AirUnreadMark.firstUnreadPosition(null, 5, ids(15L)));
    }

    @Test
    public void the_mark_is_placed_once_and_stays_until_the_chat_is_left()
    {
        final Log log = new Log(1, 2, 3, 4);
        final AirUnreadMark mark = new AirUnreadMark(log);
        mark.capture(ids(3L, 4L));
        assertEquals(2, mark.resolve(4));
        assertEquals(3L, mark.anchorId());
        // Taken once: a second load of the log does not open the chat there again.
        assertEquals(-1, mark.resolve(4));

        // Messages that come in while the chat is open, or while it is away, do not move it.
        log.ids = new long[]{1, 2, 3, 4, 5, 6};
        mark.capture(ids(5L, 6L));
        assertEquals(-1, mark.resolve(6));
        assertEquals(3L, mark.anchorId());
    }

    @Test
    public void a_chat_with_nothing_unread_has_no_mark_until_it_comes_back_to_some()
    {
        final Log log = new Log(1, 2, 3);
        final AirUnreadMark mark = new AirUnreadMark(log);
        mark.capture(ids());
        mark.capture(null);
        assertEquals(-1, mark.resolve(3));
        assertEquals(AirUnreadMark.NONE, mark.anchorId());

        // Back in the chat after messages came while it was away: those are what is unread now.
        log.ids = new long[]{1, 2, 3, 7, 8};
        mark.capture(ids(7L, 8L));
        assertEquals(3, mark.resolve(5));
        assertEquals(7L, mark.anchorId());
    }

    @Test
    public void unread_rows_the_log_does_not_show_leave_no_mark()
    {
        final AirUnreadMark mark = new AirUnreadMark(new Log(1, 2, 3));
        mark.capture(ids(40L));
        assertEquals(-1, mark.resolve(3));
        assertEquals(AirUnreadMark.NONE, mark.anchorId());
    }

    @Test
    public void the_opened_row_stands_in_the_middle_or_its_mark_at_the_top()
    {
        assertEquals(400, AirUnreadMark.openedTop(1000, 200));
        assertEquals(0, AirUnreadMark.openedTop(1000, 1000));
        // A row taller than the log keeps its mark and its start in view.
        assertEquals(0, AirUnreadMark.openedTop(1000, 1600));
        assertEquals(0, AirUnreadMark.openedTop(0, 40));
    }

    @Test
    public void what_was_unread_is_read_before_each_reset_of_a_conversation()
    {
        final String fragment = read(JAVA + "MessageListFragment.java");
        for (final String method : new String[]{"public View onCreateView(", "public void onResume()"})
        {
            final String body = method(fragment, method);
            final int capture = body.indexOf("capture_unread(fpk);");
            final int reset = body.indexOf("is_new(");
            assertTrue(method, capture > 0 && reset > capture);
            assertEquals(method + ": one reset", reset, body.lastIndexOf("is_new("));
        }
        final String created = method(fragment, "public View onCreateView(");
        assertTrue(created.indexOf("unread_mark.restore(savedInstanceState, query_friend_pubkey());")
                   < created.indexOf("capture_unread(fpk);"));
        assertTrue(created.contains("listingsView.addItemDecoration(unread_mark);"));
        // The chat opens there after the load, and the jump button below turns ink as for new messages under it.
        final String load = method(fragment, "void update_all_messages_async(final boolean from_resume_fragment, final boolean scroll_to_end_if_at_bottom)");
        assertTrue(load.indexOf("unread_mark.place(listingsView") > load.indexOf("adapter.add_list_clear(ml);"));
        assertTrue(load.contains("tint_jump_button(true);"));
        // The plain log only: not the files of the chat, not a search in it.
        final String capture = method(fragment, "private void capture_unread(final String fpk)");
        assertTrue(capture.contains("!show_only_files"));
        assertTrue(capture.contains("search_messages_text.isEmpty()"));
        assertTrue(capture.contains("AirUnreadMark.unreadOfFriend(fpk)"));
        assertTrue(method(fragment, "public void onSaveInstanceState(")
                           .contains("unread_mark.save(outState, query_friend_pubkey());"));
    }

    @Test
    public void what_was_unread_is_read_before_each_reset_of_a_group()
    {
        final String fragment = read(JAVA + "GroupMessageListFragment.java");
        for (final String method : new String[]{"public View onCreateView(", "public void onResume()"})
        {
            final String body = method(fragment, method);
            final int capture = body.indexOf("capture_unread();");
            final int reset = body.indexOf("is_new(");
            assertTrue(method, capture > 0 && reset > capture);
            assertEquals(method + ": one reset", reset, body.lastIndexOf("is_new("));
        }
        final String created = method(fragment, "public View onCreateView(");
        assertTrue(created.indexOf("unread_mark.restore(savedInstanceState, current_group_id);")
                   < created.indexOf("capture_unread();"));
        assertTrue(created.contains("listingsView.addItemDecoration(unread_mark);"));
        // The group's log is loaded in onCreateView: the mark is placed once the adapter has it.
        assertTrue(created.indexOf("unread_mark.place(listingsView, null);") > created.indexOf("listingsView.setAdapter(adapter);"));
        final String load = method(fragment, "void update_all_messages_async()");
        assertTrue(load.indexOf("unread_mark.place(listingsView, null);") > load.indexOf("adapter.add_list_clear(loaded);"));
        final String capture = method(fragment, "private void capture_unread()");
        assertTrue(capture.contains("!group_show_only_files"));
        assertTrue(capture.contains("group_search_messages_text.isEmpty()"));
        assertTrue(capture.contains("AirUnreadMark.unreadOfGroup(current_group_id)"));
        assertTrue(method(fragment, "public void onSaveInstanceState(").contains("unread_mark.save(outState, current_group_id);"));
    }

    @Test
    public void unread_means_what_the_list_counts_and_came_from_others()
    {
        final String mark = read(JAVA + "AirUnreadMark.java");
        assertTrue(mark.contains("tox_friendpubkeyEq(friendPubkey).\n                    is_newEq(true).directionEq(0).toList()"));
        assertTrue(mark.contains("group_identifierEq(groupIdentifier.toLowerCase()).is_newEq(true).directionEq(0).toList()"));
        // The list's unread count is the same flag.
        assertTrue(read(JAVA + "ChatListUiHelper.java").contains("tox_friendpubkeyEq(friendPubkey).is_newEq(true).count()"));
        // TalkBack hears the mark once, as the chat opens there.
        assertTrue(mark.contains("list.announceForAccessibility(list.getContext().getString(R.string.air_unread_mark));"));
    }

    @Test
    public void the_mark_reads_as_the_day_mark_does_in_ink()
    {
        final String layout = read(RES + "layout/air_unread_mark.xml");
        assertTrue(layout.contains("android:text=\"@string/air_unread_mark\""));
        assertTrue(layout.contains("android:textAppearance=\"@style/TextAppearance.KQ.Air.CaptionStrong\""));
        assertTrue(layout.contains("android:textColor=\"@color/kq_ink\""));
        assertTrue(layout.contains("android:textAlignment=\"viewStart\""));
        final String entry = read(RES + "layout/air_entry_text.xml");
        assertTrue(entry.contains("android:id=\"@+id/message_text_date\""));
        assertTrue(read(JAVA + "AirUnreadMark.java").contains("row.findViewById(R.id.message_text_date)"));
    }

    @Test
    public void the_mark_is_there_in_all_four_languages()
    {
        final String[][] expected = {
                {"values", "Unread messages"},
                {"values-ru", "Непрочитанные сообщения"},
                {"values-ar", "رسائل غير مقروءة"},
                {"values-zh-rCN", "未读消息"},
        };
        for (final String[] language : expected)
        {
            final Matcher found = Pattern.compile("<string name=\"air_unread_mark\">([^<]*)</string>")
                                         .matcher(read(RES + language[0] + "/strings_air.xml"));
            assertTrue(language[0], found.find());
            assertEquals(language[0], language[1], found.group(1));
        }
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

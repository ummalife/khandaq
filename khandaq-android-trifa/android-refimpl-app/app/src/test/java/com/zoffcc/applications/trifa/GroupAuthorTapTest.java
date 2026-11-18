package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.58, QA live 18.11): a tap on the author's name over a member's message in a group did
 * nothing. The holders opened the member's profile from the old bubble's photo and name, which the
 * transcript entry hides; the name it shows (air_entry_author over a text, air_file_author over a file)
 * had no listener. The rule is pure and tested here; the views are Android's, so their wiring is read
 * from the sources (comments left out), as GroupFileSendWiringTest does.
 */
public class GroupAuthorTapTest
{
    /** A member's key in the group: 64 hex digits. */
    private static final String MEMBER_KEY = "DFE8032B" + "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123" + "79E5";
    private static final Pattern FORBIDDEN = Pattern.compile(
            "[\\u2012\\u2013\\u2014\\u2015\\u2026\\u00D7\\u00B7\\u2022\\u2023\\u2043\\u2190-\\u21FF"
            + "\\u2500-\\u259F\\u25A0-\\u25FF\\u2610-\\u2612\\u2713-\\u2718\\u27F0-\\u27FF\\u2B00-\\u2BFF]");

    private static GroupMessage incoming()
    {
        final GroupMessage m = new GroupMessage();
        m.direction = 0;
        m.tox_group_peer_pubkey = MEMBER_KEY;
        m.group_identifier = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
        return m;
    }

    private static boolean opens(final boolean system, final GroupMessage m, final String key)
    {
        return GroupMessageListHolder_text_incoming_not_read.should_open_author_profile(system, m, key);
    }

    @Test
    public void a_members_message_opens_the_authors_profile()
    {
        assertEquals(64, MEMBER_KEY.length());
        assertTrue(opens(false, incoming(), MEMBER_KEY));
        assertTrue(opens(false, incoming(), MEMBER_KEY.toLowerCase(java.util.Locale.ROOT)));
    }

    @Test
    public void relayed_history_with_its_author_named_opens_too()
    {
        // The row names its author (with the "sender not verified" line when unsigned), so the name leads to them.
        final GroupMessage relayed = incoming();
        relayed.was_synced = true;
        relayed.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value;
        assertTrue(opens(false, relayed, MEMBER_KEY));
    }

    @Test
    public void our_own_messages_do_not()
    {
        final GroupMessage own = incoming();
        own.direction = 1;
        assertFalse(opens(false, own, MEMBER_KEY));
    }

    @Test
    public void a_service_line_does_not()
    {
        assertFalse(opens(true, incoming(), MEMBER_KEY));
        final GroupMessage system = incoming();
        system.tox_group_peer_pubkey = TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY;
        assertFalse(opens(false, system, TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY));
    }

    @Test
    public void an_author_the_group_could_not_confirm_does_not()
    {
        // KQ-03: the row reads "Author not confirmed" instead of the name; it must not lead to the claimed member.
        final GroupMessage unattributed = incoming();
        unattributed.was_synced = true;
        unattributed.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS_UNATTRIBUTED.value;
        assertFalse(opens(false, unattributed, MEMBER_KEY));
    }

    @Test
    public void a_name_without_a_members_key_does_not()
    {
        assertFalse(opens(false, incoming(), null));
        assertFalse(opens(false, incoming(), ""));
        assertFalse(opens(false, incoming(), "-1"));
        // The IRC bridge names its nicks by a made-up key of 61 hex digits: no member has it.
        assertFalse(opens(false, incoming(), MEMBER_KEY.substring(1, MEMBER_KEY.length() - 2)));
        assertFalse(opens(false, null, MEMBER_KEY));
    }

    @Test
    public void the_name_a_text_entry_shows_is_wired()
    {
        final String holder = code("GroupMessageListHolder_text_incoming_not_read.java");
        assertTrue(holder.contains("entry_author = (TextView) itemView.findViewById(R.id.air_entry_author);"));
        final String entry = bare(body(holder, "private void bindEntry("));
        final String wired = bare("AirTranscript.bindAuthorTap(entry_author, layout_message_container,"
                                  + " should_open_author_profile(is_system_message, m, authorKey) ? v -> {"
                                  + " if (!is_selected)");
        assertTrue(entry.contains(wired));
        assertTrue(entry.contains(bare("GroupMessageListActivity.open_group_peer_info_activity("
                                       + " v.getContext(), authorKey, m.group_identifier);")));
        // After the entry is drawn: bindGroupText decides whether the author shows at all.
        assertTrue(entry.indexOf("AirTranscript.bindGroupText(") < entry.indexOf("AirTranscript.bindAuthorTap("));
    }

    @Test
    public void the_name_a_file_entry_shows_is_wired()
    {
        final String holder = code("GroupMessageListHolder_file_incoming_state_cancel.java");
        assertTrue(holder.contains("file_author = (TextView) itemView.findViewById(R.id.air_file_author);"));
        final String bind = bare(body(holder, "public void bindMessageList("));
        assertTrue(bind.contains(bare("AirTranscript.bindAuthorTap(file_author, layout_message_container,"
                                      + " GroupMessageListHolder_text_incoming_not_read.should_open_author_profile("
                                      + " is_system_message, m, author_pubkey) ? v -> { if (!is_selected)")));
        assertTrue(bind.contains(bare("GroupMessageListActivity.open_group_peer_info_activity("
                                      + " v.getContext(), author_pubkey, m.group_identifier);")));
    }

    @Test
    public void the_name_keeps_the_long_press_and_says_what_it_does()
    {
        final String tap = bare(body(code("AirTranscript.java"), "static void bindAuthorTap("));
        // A long press on the name is the entry's (the message's actions), and never turns into a tap.
        assertTrue(tap.contains(bare("author.setOnLongClickListener(opens && row != null ? v -> {"
                                     + " row.performLongClick(); return true; } : null);")));
        // A recycled row that opened a profile becomes words again.
        assertTrue(tap.contains("author.setClickable(opens);"));
        assertTrue(tap.contains("author.setLongClickable(opens&&row!=null);"));
        assertTrue(tap.contains(bare("author.setImportantForAccessibility(opens ? View.IMPORTANT_FOR_ACCESSIBILITY_YES"
                                     + " : View.IMPORTANT_FOR_ACCESSIBILITY_NO);")));
        assertTrue(tap.contains(bare("AirUi.markAsButton(author, author.getContext().getString(R.string.group_msg_author_hint));"
                                     + " } else { author.setAccessibilityDelegate(null); }")));
    }

    @Test
    public void the_author_stays_words_elsewhere()
    {
        // Our own entries and every entry of a conversation with one person share air_entry_text; none of them taps.
        for (final String name : new String[]{"GroupMessageListHolder_text_outgoing_read.java",
                                              "GroupMessageListHolder_file_outgoing_state_cancel.java",
                                              "MessageListHolder_text_incoming_not_read.java",
                                              "MessageListHolder_text_outgoing_read.java",
                                              "MessageListHolder_text_outgoing_not_read.java",
                                              "MessageListHolder_file_incoming_state_cancel.java",
                                              "AirFileEntry.java"})
        {
            assertFalse(name, code(name).contains("bindAuthorTap"));
        }
        final String layout = read("src/main/res/layout/air_entry_text.xml");
        final int author = layout.indexOf("android:id=\"@+id/air_entry_author\"");
        assertTrue(author > 0);
        assertTrue(layout.substring(author, layout.indexOf("/>", author)).contains("android:importantForAccessibility=\"no\""));
    }

    @Test
    public void the_hint_is_in_every_shipped_language()
    {
        for (final String folder : new String[]{"values", "values-ru", "values-ar", "values-zh-rCN"})
        {
            final String hint = stringOf(folder, "group_msg_author_hint");
            assertFalse(folder, hint.trim().isEmpty());
            assertFalse(folder, hint.contains("%"));
            assertFalse(folder + " has a forbidden glyph", FORBIDDEN.matcher(hint).find());
        }
    }

    // ---- sources ----

    private static String code(final String name)
    {
        return AntispamOnRequestTest.code(read("src/main/java/com/zoffcc/applications/trifa/" + name));
    }

    private static String body(final String text, final String head)
    {
        final int at = text.indexOf(head);
        assertTrue(head + " not found", at >= 0);
        final int open = text.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (c == '{')
            {
                depth++;
            }
            else if (c == '}')
            {
                depth--;
                if (depth == 0)
                {
                    return text.substring(open + 1, i);
                }
            }
        }
        throw new AssertionError(head + " has no end");
    }

    private static String bare(final String source)
    {
        return source.replaceAll("\\s+", "");
    }

    private static String stringOf(final String folder, final String name)
    {
        final String strings = read("src/main/res/" + folder + "/strings.xml");
        final String open = "<string name=\"" + name + "\">";
        final int start = strings.indexOf(open);
        assertTrue(folder + " " + name, start >= 0);
        return strings.substring(start + open.length(), strings.indexOf("</string>", start));
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
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

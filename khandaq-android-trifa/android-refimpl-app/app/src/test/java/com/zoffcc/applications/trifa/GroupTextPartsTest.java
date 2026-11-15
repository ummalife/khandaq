package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): Send refused a group text over TOX_GROUP_MAX_MESSAGE_LENGTH (1372 bytes), so a long text
 * or a paste could not go at all. It now goes as several group messages (AirGroupModel.textParts), each one
 * whole NGC message, cut as a long direct message is: at a line break, else at a space, else after a whole
 * character; never inside a code point, a surrogate pair or an emoji sequence. The reply header and the
 * mention block stay in the first part, and the rows of the parts keep their order in the chat.
 */
public class GroupTextPartsTest
{
    private static final int LIMIT = AirGroupModel.COMPOSE_LIMIT_BYTES;

    private static final String GRIN = "😀"; // 4 bytes
    private static final String THUMB_SKIN_TONE = "👍🏽"; // base and skin tone modifier, 8 bytes
    private static final String FAMILY = "👨‍👩‍👧"; // three emoji joined by two ZWJ, 18 bytes
    private static final String WOMAN_TECHNOLOGIST_DARK = "👩🏿‍💻"; // skin tone and ZWJ, 15 bytes
    private static final String HEADER = "[KQ|7|ABCDEF12|1731370000000|Amina]\nHello\n[KQ/end]\n";
    private static final String KEY = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    @Test
    public void a_part_is_one_ngc_group_message()
    {
        // toxcore/tox.h: #define TOX_GROUP_MAX_MESSAGE_LENGTH 1372
        assertEquals(1372, LIMIT);
        assertEquals(8, bytes(THUMB_SKIN_TONE));
        assertEquals(18, bytes(FAMILY));
        assertEquals(15, bytes(WOMAN_TECHNOLOGIST_DARK));
    }

    @Test
    public void a_text_that_fits_goes_as_one_message_as_it_is()
    {
        final String ascii = repeat("a", LIMIT);
        assertEquals(Collections.singletonList(ascii), parts(ascii, ascii));
        final String cyrillic = repeat("я", LIMIT / 2);
        assertEquals(LIMIT, bytes(cyrillic));
        assertEquals(Collections.singletonList(cyrillic), parts(cyrillic, cyrillic));
        final String emoji = repeat(GRIN, LIMIT / 4);
        assertEquals(LIMIT, bytes(emoji));
        assertEquals(Collections.singletonList(emoji), parts(emoji, emoji));
        assertTrue(parts("", "").isEmpty());
    }

    @Test
    public void one_byte_over_the_limit_goes_as_two_messages()
    {
        final String ascii = repeat("a", LIMIT);
        assertEquals(Arrays.asList(ascii, "b"), parts(ascii + "b", ascii + "b"));
        final String cyrillic = repeat("я", LIMIT / 2);
        assertEquals(Arrays.asList(cyrillic, "ж"), parts(cyrillic + "ж", cyrillic + "ж"));
        // 1371 bytes and a two-byte letter: the letter does not fit, and it is not cut in half.
        final String odd = repeat("a", LIMIT - 1) + "ж";
        assertEquals(LIMIT + 1, bytes(odd));
        assertEquals(Arrays.asList(repeat("a", LIMIT - 1), "ж"), parts(odd, odd));
    }

    @Test
    public void a_text_without_spaces_is_cut_at_the_limit()
    {
        assertEquals(Arrays.asList(repeat("a", LIMIT), repeat("a", LIMIT), repeat("a", 256)),
                     parts(repeat("a", 3000), repeat("a", 3000)));
        // Cyrillic: 686 letters are 1372 bytes.
        final String letters = repeat("я", 1500);
        assertEquals(Arrays.asList(repeat("я", 686), repeat("я", 686), repeat("я", 128)), parts(letters, letters));
    }

    @Test
    public void cyrillic_words_are_cut_at_a_space_and_every_part_fits()
    {
        final String text = repeat("привет мир ", 400).trim();
        final List<String> parts = parts(text, text);
        assertTrue(parts.size() >= 4);
        for (final String part : parts)
        {
            assertFits(part);
            assertFalse(part, part.startsWith(" ") || part.endsWith(" "));
            for (final String word : part.split(" ", -1))
            {
                assertTrue(part, word.equals("привет") || word.equals("мир"));
            }
        }
        assertEquals(text, String.join(" ", parts));
    }

    @Test
    public void a_line_break_is_preferred_to_a_later_space()
    {
        final String first = repeat("строка ", 100).trim(); // 1299 bytes
        final String text = first + "\n" + repeat("word ", 40).trim();
        final List<String> parts = parts(text, text);
        assertEquals(Arrays.asList(first, repeat("word ", 40).trim()), parts);
    }

    @Test
    public void emoji_with_skin_tones_and_zwj_sequences_stay_whole()
    {
        for (final String emoji : new String[]{THUMB_SKIN_TONE, FAMILY, WOMAN_TECHNOLOGIST_DARK})
        {
            // Shifted by one byte so that no cut falls on a boundary by chance.
            final String text = "a" + repeat(emoji, 3 * LIMIT / bytes(emoji));
            final List<String> parts = parts(text, text);
            assertTrue(parts.size() >= 3);
            assertEquals(text, String.join("", parts));
            final int perPart = (LIMIT - 1) / bytes(emoji);
            assertEquals("a" + repeat(emoji, perPart), parts.get(0));
            for (int i = 1; i < parts.size(); i++)
            {
                assertFits(parts.get(i));
                assertEquals(emoji, "", parts.get(i).replace(emoji, ""));
            }
        }
    }

    @Test
    public void no_part_ends_inside_a_character_for_any_mix()
    {
        final String[] pieces = {"a", "я", "€", GRIN, THUMB_SKIN_TONE, FAMILY, WOMAN_TECHNOLOGIST_DARK, " ", "\n"};
        final java.util.Random random = new java.util.Random(1372);
        for (int round = 0; round < 200; round++)
        {
            final StringBuilder sb = new StringBuilder();
            final int count = 200 + random.nextInt(1500);
            for (int i = 0; i < count; i++)
            {
                sb.append(pieces[random.nextInt(pieces.length)]);
            }
            final String text = sb.toString();
            final List<int[]> ranges = AirGroupModel.textParts(text, text);
            int previousEnd = 0;
            for (final int[] range : ranges)
            {
                final String part = text.substring(range[0], range[1]);
                assertFits(part);
                assertTrue(range[0] >= previousEnd);
                // Only white space is dropped between parts.
                assertEquals("", text.substring(previousEnd, range[0]).trim());
                assertWholeAt(text, range[0]);
                assertWholeAt(text, range[1]);
                previousEnd = range[1];
            }
            assertEquals("", text.substring(previousEnd).trim());
        }
    }

    @Test
    public void the_reply_header_and_the_mention_block_stay_in_the_first_part()
    {
        final GroupMentionHelper.MentionEntry bilal = new GroupMentionHelper.MentionEntry();
        bilal.pubkey = KEY;
        bilal.handle = "bilal";
        final String body = "@bilal " + repeat("ответ ", 400).trim();
        final String withMentions = GroupMentionHelper.encodeMentionsBlock(body, Collections.singletonList(bilal));
        final String wire = HEADER + withMentions;
        assertTrue(AirGroupModel.headFits(wire, body));

        final List<String> parts = parts(wire, body);
        assertTrue(parts.size() >= 3);
        final String head = wire.substring(0, wire.length() - body.length());
        assertTrue(parts.get(0).startsWith(head));
        final GroupMentionHelper.ParsedGroupText first = GroupMentionHelper.parse(parts.get(0));
        assertNotNull(first.reply);
        assertEquals("Hello", first.reply.previewText);
        assertEquals(1, first.mentions.size());
        assertEquals(KEY, first.mentions.get(0).pubkey);
        assertTrue(first.bodyText.startsWith("@bilal ответ"));

        final List<String> bodies = new ArrayList<>();
        bodies.add(first.bodyText);
        for (int i = 1; i < parts.size(); i++)
        {
            assertFits(parts.get(i));
            final GroupMentionHelper.ParsedGroupText later = GroupMentionHelper.parse(parts.get(i));
            assertNull(later.reply);
            assertTrue(later.mentions.isEmpty());
            assertEquals(parts.get(i), later.bodyText);
            bodies.add(later.bodyText);
        }
        assertFits(parts.get(0));
        assertEquals(body, String.join(" ", bodies));
    }

    @Test
    public void a_head_exactly_at_the_limit_leaves_the_text_no_room_and_does_not_fit()
    {
        final String body = repeat("x", 100);
        final String head = repeat("h", LIMIT - 1) + "\n";
        assertFalse(AirGroupModel.headFits(head + body, body));
        assertTrue(AirGroupModel.headFits(head.substring(1) + body, body));
        assertTrue(AirGroupModel.headFits(body, body));
    }

    @Test
    public void a_mention_block_that_would_fill_the_first_part_is_left_out()
    {
        final List<GroupMentionHelper.MentionEntry> many = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++)
        {
            final GroupMentionHelper.MentionEntry entry = new GroupMentionHelper.MentionEntry();
            entry.pubkey = KEY.substring(2) + String.format(java.util.Locale.ROOT, "%02d", i);
            entry.handle = "member_with_a_long_name_" + i;
            many.add(entry);
            text.append('@').append(entry.handle).append(' ');
        }
        final String body = text.toString().trim();
        final String wire = HEADER + GroupMentionHelper.encodeMentionsBlock(body, many);
        assertFalse(AirGroupModel.headFits(wire, body));
        // Without the block the reply header fits, and the handles still name the members in the text.
        assertTrue(AirGroupModel.headFits(HEADER + body, body));
        final List<String> parts = parts(HEADER + body, body);
        assertTrue(parts.get(0).startsWith(HEADER));
        for (final String part : parts)
        {
            assertFits(part);
        }
    }

    @Test
    public void the_rows_of_the_parts_keep_their_order_when_added_one_by_one()
    {
        // The sender gives the parts times a millisecond apart; a member stamps them as they arrive, often
        // in the same millisecond. Each row is added from a thread of its own, in any order.
        final long now = 1_731_370_000_000L;
        final List<GroupMessage> shown = new ArrayList<>();
        final GroupMessage earlier = row(1, now - 5000);
        shown.add(earlier);
        final GroupMessage[] parts = {row(10, now), row(11, now), row(12, now), row(13, now + 1)};
        for (final int index : new int[]{2, 0, 3, 1})
        {
            final GroupMessage part = parts[index];
            shown.add(GroupMessageLayoutHelper.sortedInsertIndex(shown, part), part);
        }
        assertEquals(Arrays.asList(1L, 10L, 11L, 12L, 13L), ids(shown));

        // A reload sorts them the same way.
        final List<GroupMessage> reloaded = new ArrayList<>(Arrays.asList(parts[3], parts[1], earlier, parts[2],
                                                                          parts[0]));
        GroupMessageLayoutHelper.sortMessagesForChatDisplay(reloaded);
        assertEquals(ids(shown), ids(reloaded));
    }

    private static GroupMessage row(final long id, final long timestamp)
    {
        final GroupMessage m = new GroupMessage();
        m.id = id;
        m.sent_timestamp = timestamp;
        m.rcvd_timestamp = timestamp;
        return m;
    }

    private static List<Long> ids(final List<GroupMessage> rows)
    {
        final List<Long> out = new ArrayList<>();
        for (final GroupMessage m : rows)
        {
            out.add(m.id);
        }
        return out;
    }

    private static List<String> parts(final String wire, final String body)
    {
        final List<String> out = new ArrayList<>();
        for (final int[] range : AirGroupModel.textParts(wire, body))
        {
            out.add(wire.substring(range[0], range[1]));
        }
        return out;
    }

    /** A cut at index i leaves every code point and every emoji sequence of these texts whole. */
    private static void assertWholeAt(final String text, final int i)
    {
        if (i <= 0 || i >= text.length())
        {
            return;
        }
        final char before = text.charAt(i - 1);
        final int after = text.codePointAt(i);
        final String where = "cut at " + i + " of " + text.length();
        assertFalse(where, Character.isHighSurrogate(before) && Character.isLowSurrogate(text.charAt(i)));
        assertFalse(where, before == '‍' || after == 0x200D);
        assertFalse(where, after >= 0x1F3FB && after <= 0x1F3FF);
    }

    private static void assertFits(final String part)
    {
        assertTrue(bytes(part) + " bytes", bytes(part) <= LIMIT);
        assertFalse(part.isEmpty());
    }

    private static int bytes(final String text)
    {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String repeat(final String text, final int times)
    {
        final StringBuilder sb = new StringBuilder(text.length() * Math.max(0, times));
        for (int i = 0; i < times; i++)
        {
            sb.append(text);
        }
        return sb.toString();
    }
}

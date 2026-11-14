package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA live, F13): a long direct message to a friend whose app does not put chunks back together
 * lost its end. It now goes as several messages, each one whole Tox message, cut where the desktop cuts:
 * at a line break, else at a space, else after a whole character.
 */
public class MessageSplitterTest
{
    private static final int LIMIT = ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH;

    private static final String FAMILY = "👨‍👩‍👧"; // ZWJ, 18 bytes
    private static final String FLAG_RU = "🇷🇺"; // two regional indicators, 8 bytes
    private static final String FLAG_GB = "🇬🇧";
    private static final String THUMB_SKIN_TONE = "👍🏽"; // 8 bytes
    private static final String FLAG_ENGLAND = "🏴󠁧󠁢󠁥󠁮"
                                               + "󠁧󠁿"; // black flag and tags, 28 bytes
    private static final String KEYCAP_ONE = "1️⃣"; // 7 bytes
    private static final String RED_HEART = "❤️"; // 6 bytes
    private static final String E_ACUTE = "é"; // combining acute, 3 bytes
    private static final String GRIN = "😀"; // 4 bytes

    @Test
    public void a_part_is_one_msgv3_message()
    {
        assertEquals(1334, LIMIT);
        assertEquals(AirConversationModel.COMPOSE_LIMIT_BYTES, LIMIT);
    }

    @Test
    public void an_empty_text_goes_as_no_message()
    {
        assertTrue(MessageSplitter.split("", 0, LIMIT).isEmpty());
        assertTrue(MessageSplitter.split(null, 0, LIMIT).isEmpty());
    }

    @Test
    public void a_text_that_fits_goes_whole_and_as_it_is()
    {
        final String ascii = repeat("a", LIMIT);
        assertEquals(Collections.singletonList(ascii), MessageSplitter.split(ascii, 0, LIMIT));
        final String cyrillic = repeat("я", LIMIT / 2);
        assertEquals(Collections.singletonList(cyrillic), MessageSplitter.split(cyrillic, 0, LIMIT));
        final String emoji = repeat(GRIN, LIMIT / 4) + "ab";
        assertEquals(LIMIT, bytes(emoji));
        assertEquals(Collections.singletonList(emoji), MessageSplitter.split(emoji, 0, LIMIT));
        // Only a cut drops white space.
        assertEquals(Collections.singletonList(" a \n"), MessageSplitter.split(" a \n", 0, LIMIT));
        // One byte more and it is two.
        assertEquals(Arrays.asList(ascii, "b"), MessageSplitter.split(ascii + "b", 0, LIMIT));
    }

    @Test
    public void ascii_words_are_cut_at_the_last_space_that_fits()
    {
        final StringBuilder words = new StringBuilder();
        for (int i = 0; i < 400; i++)
        {
            words.append(i == 0 ? "" : " ").append(String.format(Locale.ROOT, "word%04d", i));
        }
        final String text = words.toString();
        final List<String> parts = MessageSplitter.split(text, 0, LIMIT);
        assertEquals(3, parts.size());
        assertEquals(text.substring(0, text.lastIndexOf(' ', LIMIT)), parts.get(0));
        for (String part : parts)
        {
            assertFits(part);
            assertFalse(part, part.startsWith(" ") || part.endsWith(" "));
        }
        assertEquals(text, String.join(" ", parts));
    }

    @Test
    public void a_text_without_spaces_is_cut_at_the_limit()
    {
        assertEquals(Arrays.asList(repeat("a", LIMIT), repeat("a", LIMIT), repeat("a", 332)),
                     MessageSplitter.split(repeat("a", 3000), 0, LIMIT));
    }

    @Test
    public void cyrillic_is_cut_after_a_whole_letter_or_at_a_space()
    {
        final String letters = repeat("я", 1000);
        assertEquals(Arrays.asList(repeat("я", 667), repeat("я", 333)), MessageSplitter.split(letters, 0, LIMIT));
        // An odd limit does not take half a letter.
        assertEquals(Arrays.asList(repeat("я", 667), repeat("я", 333)),
                     MessageSplitter.split(letters, 0, LIMIT + 1));

        final String words = repeat("привет мир ", 150).trim();
        final List<String> parts = MessageSplitter.split(words, 0, LIMIT);
        assertEquals(3, parts.size());
        for (String part : parts)
        {
            assertFits(part);
            for (String word : part.split(" ", -1))
            {
                assertTrue(part, word.equals("привет") || word.equals("мир"));
            }
        }
        assertEquals(words, String.join(" ", parts));
    }

    @Test
    public void a_surrogate_pair_at_the_limit_goes_whole_to_the_next_part()
    {
        assertEquals(Arrays.asList("a" + repeat(GRIN, 333), repeat(GRIN, 67)),
                     MessageSplitter.split("a" + repeat(GRIN, 400), 0, LIMIT));
    }

    @Test
    public void an_emoji_sequence_a_flag_a_skin_tone_and_an_accent_stay_whole()
    {
        for (String cluster : new String[]{FAMILY, FLAG_RU, THUMB_SKIN_TONE, FLAG_ENGLAND, KEYCAP_ONE, RED_HEART,
                                           E_ACUTE})
        {
            for (int room = 1; room < bytes(cluster); room++)
            {
                // The limit falls inside the cluster: its first bytes would fit and the rest would not.
                final String before = repeat("a", LIMIT - room);
                final String after = repeat("b", 10);
                assertEquals(escape(cluster) + " with " + room + " bytes of room", Arrays.asList(before, cluster + after),
                             MessageSplitter.split(before + cluster + after, 0, LIMIT));
            }
        }
    }

    @Test
    public void flags_in_a_row_pair_up_from_the_start_of_the_part()
    {
        // Room for one flag and the first regional indicator of the next.
        final String before = repeat("a", LIMIT - 12);
        assertEquals(Arrays.asList(before + FLAG_RU, FLAG_GB + "b"),
                     MessageSplitter.split(before + FLAG_RU + FLAG_GB + "b", 0, LIMIT));
    }

    @Test
    public void a_line_break_comes_before_a_later_space_and_its_white_space_goes()
    {
        final String first = repeat("x", 100);
        final String rest = repeat("word ", 300).trim();
        for (String lineBreak : new String[]{"\n", "\n\n", "\r\n", " \n \n"})
        {
            final List<String> parts = MessageSplitter.split(first + lineBreak + rest, 0, LIMIT);
            assertEquals(escape(lineBreak), first, parts.get(0));
            assertEquals(escape(lineBreak), rest, String.join(" ", parts.subList(1, parts.size())));
        }
        // The indent of the line after the cut stays.
        assertEquals(Arrays.asList(first, "    " + repeat("y", 1300)),
                     MessageSplitter.split(first + "\n    " + repeat("y", 1300), 0, LIMIT));
    }

    @Test
    public void a_space_just_past_the_limit_still_gives_a_full_part()
    {
        assertEquals(Arrays.asList(repeat("a", LIMIT), "b"), MessageSplitter.split(repeat("a", LIMIT) + " b", 0, LIMIT));
        assertEquals(Arrays.asList(repeat("a", LIMIT), "b"), MessageSplitter.split(repeat("a", LIMIT) + "\nb", 0, LIMIT));
    }

    @Test
    public void white_space_alone_makes_no_part()
    {
        assertEquals(Arrays.asList("a", "b"), MessageSplitter.split("a" + repeat("\n", 2000) + "b", 0, LIMIT));
        // An indent longer than a message goes too.
        assertEquals(Arrays.asList("a", "b"), MessageSplitter.split("a\n" + repeat(" ", 2000) + "b", 0, LIMIT));
    }

    @Test
    public void an_indent_or_a_head_that_leaves_no_room_for_the_next_character_goes_first()
    {
        // The indent and the flag are 29 bytes, the flag alone 28: the indent goes, not half the flag.
        assertEquals(Arrays.asList("a", FLAG_ENGLAND, "b"), MessageSplitter.split("a\n " + FLAG_ENGLAND + "b", 0, 28));
        // The flag does not fit beside the header: the header goes alone, then the text.
        final String header = "[KQ|1|AB|2|N]\nq\n[KQ/end]\n";
        assertEquals(Arrays.asList(header.trim(), FLAG_ENGLAND + " x"),
                     MessageSplitter.split(header + FLAG_ENGLAND + " x", header.length(), 40));
    }

    @Test
    public void the_reply_header_stays_whole_in_the_first_part()
    {
        final String header = "[KQ|7|ABCDEF12|1731370000000|Khandaq Demo]\nHello\n[KQ/end]\n";
        final String body = repeat("answer ", 300).trim();
        final List<String> parts = MessageSplitter.split(header + body, header.length(), LIMIT);
        assertTrue(parts.size() > 1);
        assertTrue(parts.get(0).startsWith(header));
        final MessageReplyHelper.ParsedMessage first = MessageReplyHelper.parse(parts.get(0));
        assertNotNull(first.reply);
        assertEquals("Hello", first.reply.previewText);
        final List<String> bodies = new ArrayList<>(parts);
        bodies.set(0, parts.get(0).substring(header.length()));
        assertEquals(body, String.join(" ", bodies));
        for (int i = 1; i < parts.size(); i++)
        {
            assertFits(parts.get(i));
            assertNull(MessageReplyHelper.parse(parts.get(i)).reply);
        }
        // Without it, the last line break that fits would have sent the header alone.
        assertEquals(header.trim(), MessageSplitter.split(header + body, 0, LIMIT).get(0));
        // A head that fills a message is split as the rest is.
        assertEquals(Arrays.asList(repeat("h", LIMIT), repeat("h", 66) + " tail"),
                     MessageSplitter.split(repeat("h", 1400) + " tail", 1400, LIMIT));
    }

    @Test
    public void the_parts_leave_in_order_and_each_finds_its_own_wake_push()
    {
        assertEquals(1000L, MessageSplitter.partSentTimestamp(1000L, 0));
        assertTrue(MessageSplitter.partSentTimestamp(1000L, 1) > MessageSplitter.partSentTimestamp(1000L, 0));
        // update_message_in_db_sent_push_set takes the earliest row from sent time minus this.
        assertTrue(MessageSplitter.PART_GAP_MS > TRIFAGlobals.PUSH_URL_TRIGGER_GET_MESSAGE_FOR_delta_ms_prev);
        assertEquals(1000L + 9 * MessageSplitter.PART_GAP_MS, MessageSplitter.partSentTimestamp(1000L, 9));
    }

    /**
     * Random texts of whole characters: every part fits, no part is white space or starts with a line
     * break or ends with white space, nothing but white space at a cut is lost, and every cut falls
     * between two of the characters.
     */
    @Test
    public void random_texts_lose_nothing_and_are_cut_only_between_whole_characters()
    {
        final String[] characters = {"a", "Z", "7", "я", "中", "ب", GRIN, FAMILY, FLAG_RU, FLAG_GB, THUMB_SKIN_TONE,
                                     FLAG_ENGLAND, KEYCAP_ONE, RED_HEART, E_ACUTE, " ", " ", " ", "\t", "\n", "\r\n"};
        final Random random = new Random(20261114L);
        for (int limit : new int[]{LIMIT, 97, 41, 28})
        {
            for (int round = 0; round < 150; round++)
            {
                final StringBuilder text = new StringBuilder();
                final Set<Integer> boundaries = new HashSet<>();
                final int count = random.nextInt(round % 10 == 0 ? 40 : 1500);
                for (int i = 0; i < count; i++)
                {
                    boundaries.add(text.length());
                    // A third of the texts have no white space at all, so every cut is a hard one.
                    final int pick = random.nextInt(round % 3 == 0 ? characters.length - 6 : characters.length);
                    text.append(characters[pick]);
                }
                boundaries.add(text.length());
                final String input = text.toString().trim();
                final int offset = text.indexOf(input);
                final List<int[]> ranges = MessageSplitter.ranges(input, 0, limit);
                if (input.isEmpty())
                {
                    assertTrue(ranges.isEmpty());
                    continue;
                }
                int previousEnd = 0;
                final StringBuilder kept = new StringBuilder();
                for (int[] range : ranges)
                {
                    final String part = input.substring(range[0], range[1]);
                    final String where = "limit " + limit + " round " + round + " part at " + range[0];
                    assertTrue(where, bytes(part) <= limit);
                    assertFalse(where, part.trim().isEmpty());
                    assertFalse(where, part.charAt(0) == '\n' || part.charAt(0) == '\r');
                    assertFalse(where, Character.isWhitespace(part.charAt(part.length() - 1)));
                    assertTrue(where, boundaries.contains(range[0] + offset) && boundaries.contains(range[1] + offset));
                    assertTrue(where, input.substring(previousEnd, range[0]).trim().isEmpty());
                    previousEnd = range[1];
                    kept.append(part);
                }
                assertEquals(input.length(), previousEnd);
                assertEquals(withoutSpace(input), withoutSpace(kept.toString()));
                assertEquals(ranges.size(), MessageSplitter.split(input, 0, limit).size());
            }
        }
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
        final StringBuilder out = new StringBuilder(text.length() * Math.max(0, times));
        for (int i = 0; i < times; i++)
        {
            out.append(text);
        }
        return out.toString();
    }

    private static String withoutSpace(final String text)
    {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++)
        {
            if (!Character.isWhitespace(text.charAt(i)))
            {
                out.append(text.charAt(i));
            }
        }
        return out.toString();
    }

    private static String escape(final String text)
    {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++)
        {
            out.append(String.format(Locale.ROOT, "\\u%04X", (int) text.charAt(i)));
        }
        return out.toString();
    }
}

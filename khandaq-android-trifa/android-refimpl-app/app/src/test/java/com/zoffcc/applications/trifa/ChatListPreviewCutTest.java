package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.ChatListUiHelper.PREVIEW_MAX_CHARS;
import static com.zoffcc.applications.trifa.ChatListUiHelper.clip_preview;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA live, F15): a chat preview is cut between characters, never inside one.
 *
 * QA: after a message of keycaps, flags, a heart on fire and England flags, uiautomator could no longer dump
 * the chat list: "Bad surrogate pair (U+db40 U+2e)". The preview was cut at a fixed number of chars, inside
 * the surrogate pair of an emoji tag, and "..." followed it.
 */
public class ChatListPreviewCutTest
{
    /** The chars a shortened preview keeps before its "...". */
    private static final int KEEP = PREVIEW_MAX_CHARS - 3;

    private static final String GRIN = "😀"; // 😀
    private static final String FLAG_TR = "🇹🇷"; // 🇹🇷, two regional indicators
    private static final String HEART_ON_FIRE = "❤️‍🔥"; // ❤️‍🔥, a ZWJ sequence
    private static final String FLAG_ENGLAND = "🏴󠁧󠁢󠁥󠁮"
                                               + "󠁧󠁿"; // black flag and six tags, 14 chars
    private static final String TAG_A = "󠁡"; // emoji tag "a"

    @Test
    public void a_preview_that_fits_stays_as_it_is()
    {
        assertEquals("see you at 5", clip_preview(" see you\nat 5 "));
        final String fits = repeat("a", PREVIEW_MAX_CHARS);
        assertEquals(fits, clip_preview(fits));
    }

    @Test
    public void plain_text_is_cut_where_it_always_was()
    {
        assertEquals(repeat("a", KEEP) + "...", clip_preview(repeat("a", 200)));
    }

    @Test
    public void an_emoji_across_the_cut_is_left_out_whole()
    {
        // The cut at KEEP chars fell between the two halves of the first grin.
        final String preview = clip_preview(repeat("a", KEEP - 1) + repeat(GRIN, 3));
        assertEquals(repeat("a", KEEP - 1) + "...", preview);
        assertWholeCodePoints(preview);
    }

    @Test
    public void a_tag_sequence_flag_is_not_cut()
    {
        // The case QA ran into: the cut fell inside the tags of the England flag.
        final String preview = clip_preview(repeat("a", KEEP - 7) + FLAG_ENGLAND + "bbbb");
        assertEquals(repeat("a", KEEP - 7) + "...", preview);
        assertWholeCodePoints(preview);
    }

    @Test
    public void a_flag_and_a_zwj_sequence_are_not_cut()
    {
        // After the first regional indicator of the flag, which alone reads as a letter in a box.
        assertEquals(repeat("a", KEEP - 2) + "...", clip_preview(repeat("a", KEEP - 2) + FLAG_TR + "bbbb"));
        // Inside the fire of the heart on fire, after the heart and the joiner.
        assertEquals(repeat("a", KEEP - 4) + "...", clip_preview(repeat("a", KEEP - 4) + HEART_ON_FIRE + "bbbb"));
    }

    @Test
    public void a_character_longer_than_the_preview_is_cut_between_code_points()
    {
        // A black flag with sixty tags is one grapheme cluster of 122 chars: it is cut, but not inside a pair.
        final String preview = clip_preview("🏴" + repeat(TAG_A, 60));
        assertWholeCodePoints(preview);
        assertEquals(KEEP - 1 + 3, preview.length());
    }

    @Test
    public void the_cut_never_leaves_half_a_surrogate_pair()
    {
        final String[] tails = {GRIN, FLAG_TR, HEART_ON_FIRE, FLAG_ENGLAND};
        for (int lead = 0; lead < 20; lead++)
        {
            for (final String tail : tails)
            {
                final String preview = clip_preview(repeat("a", KEEP - 10 + lead) + repeat(tail, 8));
                assertTrue(preview.length() <= PREVIEW_MAX_CHARS);
                assertTrue(preview.endsWith("..."));
                assertWholeCodePoints(preview);
            }
        }
    }

    @Test
    public void whole_character_cut_keeps_its_bounds()
    {
        assertEquals(0, MessageSplitter.wholeCharacterCut("abc", 0));
        assertEquals(3, MessageSplitter.wholeCharacterCut("abc", 3));
        assertEquals(3, MessageSplitter.wholeCharacterCut("abc", 10));
        assertEquals(1, MessageSplitter.wholeCharacterCut("a" + GRIN, 2));
    }

    private static void assertWholeCodePoints(final String text)
    {
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (Character.isHighSurrogate(c))
            {
                assertTrue("half a surrogate pair at " + i,
                           i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)));
                i++;
            }
            else
            {
                assertFalse("half a surrogate pair at " + i, Character.isLowSurrogate(c));
            }
        }
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
}

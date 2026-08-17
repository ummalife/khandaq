package com.zoffcc.applications.trifa;

import com.vanniktech.emoji.emoji.Emoji;

import org.junit.Test;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (user request 17.08) — what the emoji picker refuses to offer, and what it must keep.
 *
 * The keep-list is the half worth testing: a filter written against "religious symbols" is one
 * careless edit away from taking the mosque and the Kaaba with it, which would be the opposite of
 * what the requesting user asked for. Device QA already caught two gaps this test now pins down
 * (same-sex FAMILY sequences, and the handshake-ZWJ spelling of "holding hands").
 */
public class KhandaqEmojiFilterTest
{
    /**
     * Minimal stand-in: the filter only ever reads getUnicode(), so the codepoints handed to the
     * real constructor are irrelevant here — what matters is the exact string under test.
     */
    private static Emoji emoji(final String unicode)
    {
        return new Emoji(unicode.codePoints().toArray(), new String[]{"x"}, 0, false)
        {
            @Override
            public String getUnicode()
            {
                return unicode;
            }
        };
    }

    private static void assertBlocked(final String... unicodes)
    {
        for (final String u : unicodes)
        {
            assertTrue("must be filtered out: " + u, KhandaqEmojiFilter.isBlocked(emoji(u)));
        }
    }

    private static void assertKept(final String... unicodes)
    {
        for (final String u : unicodes)
        {
            assertFalse("must stay in the picker: " + u, KhandaqEmojiFilter.isBlocked(emoji(u)));
        }
    }

    @Test
    public void blocksCrossesStarsAndPlacesOfOtherWorship()
    {
        assertBlocked("✝️", "☦️", "✡️", "🔯", "🕎", "🕉️", "☸️", "☯️", "🛐", "⛪", "🕍", "🛕", "⛩️");
    }

    @Test
    public void blocksTheWholeZodiac()
    {
        assertBlocked("♈", "♉", "♊", "♋", "♌", "♍", "♎", "♏", "♐", "♑", "♒", "♓", "⛎");
    }

    @Test
    public void blocksDevilsAndDemons()
    {
        assertBlocked("👿", "😈", "👹", "👺", "👻", "👽", "👾");
    }

    @Test
    public void blocksLgbtImagery()
    {
        assertBlocked("🏳️‍🌈", "🏳️‍⚧️", "⚧️",
                      "👨‍❤️‍👨", "👩‍❤️‍👩", "👨‍❤️‍💋‍👨", "👩‍❤️‍💋‍👩",
                      "👬", "👭", "👨‍🤝‍👨", "👩‍🤝‍👩");
    }

    /** Same-sex families — the gap device QA found after the first version shipped. */
    @Test
    public void blocksSameSexFamilies()
    {
        assertBlocked("👨‍👨‍👦", "👨‍👨‍👧", "👨‍👨‍👧‍👦", "👩‍👩‍👦", "👩‍👩‍👧‍👧");
    }

    /**
     * The point of the whole exercise: Islamic symbols STAY. A category-level cut would have removed
     * these along with the crosses, so this is the test that makes the narrow filter worth having.
     */
    @Test
    public void keepsIslamicSymbols()
    {
        assertKept("🕌",   // mosque
                   "🕋",   // kaaba
                   "☪️",   // star and crescent
                   "📿",   // prayer beads
                   "🤲");  // palms up together
    }

    @Test
    public void keepsMixedSexFamiliesAndOrdinaryEmoji()
    {
        assertKept("👨‍👩‍👦", "👨‍👩‍👧‍👦", "👫", "🙂", "🌙", "🌴", "📄", "✅");
    }

    @Test
    public void survivesNullAndEmpty()
    {
        assertTrue(KhandaqEmojiFilter.isBlocked(null));
        assertFalse(KhandaqEmojiFilter.isBlocked(emoji("🙂")));
    }
}

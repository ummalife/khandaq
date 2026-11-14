package com.zoffcc.applications.trifa;

import com.vanniktech.emoji.emoji.Emoji;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * KHANDAQ (user request, 17 Aug 2026): which emoji the in-app picker refuses to offer.
 *
 * A user asked for imagery that a Muslim audience should not be handed by their own messenger to be
 * removed: symbols of other religions and of the occult, devils, and LGBT imagery. That is a product
 * stance, so the list is written out explicitly here rather than inferred from a category name — a
 * category-level cut would take the mosque and the Kaaba with it, which is the opposite of the ask.
 *
 * Matched by CODEPOINT, not by shortcode: the shortcodes in the emoji set are an implementation
 * detail of the dependency (they have been renamed between versions), while the codepoints are
 * Unicode and cannot drift. ZWJ sequences are matched by containment so the variation-selector-16
 * and non-VS16 spellings both hit.
 *
 * What this does NOT do: it does not remove anything from messages you RECEIVE. EmojiManager builds
 * its text→image map from the same categories, so a filtered emoji simply stops being drawn with the
 * Google artwork and falls back to the system font — the character itself still arrives and is still
 * rendered by Android. Nothing short of rewriting other people's messages could change that, and
 * this feature does not attempt it.
 */
final class KhandaqEmojiFilter
{
    private KhandaqEmojiFilter()
    {
    }

    /**
     * Symbols of other religions, of the occult and of astrology.
     *
     * DELIBERATELY ABSENT, and must stay absent: 🕌 U+1F54C mosque, 🕋 U+1F54B kaaba,
     * ☪️ U+262A star and crescent, 📿 U+1F4FF prayer beads, 🤲 U+1F932 palms-up. Those are the ones
     * the requesting audience actually wants to keep, so a future "just drop the Symbols category"
     * shortcut would be a regression, not a simplification.
     */
    private static final Set<String> BLOCKED_SYMBOLS = new HashSet<>(Arrays.asList(
            "✝",             // ✝ latin cross
            "☦",             // ☦ orthodox cross
            "✡",             // ✡ star of David
            "🔯",       // 🔯 six-pointed star with dot
            "🕎",       // 🕎 menorah
            "🕉",       // 🕉 om
            "☸",             // ☸ wheel of dharma
            "☯",             // ☯ yin yang
            "🛐",       // 🛐 place of worship
            "⛪",             // ⛪ church
            "🕍",       // 🕍 synagogue
            "🛕",       // 🛕 hindu temple
            "⛩",             // ⛩ shinto shrine
            "♈", "♉", "♊", "♋", "♌", "♍",  // ♈..♍ zodiac
            "♎", "♏", "♐", "♑", "♒", "♓",  // ♎..♓ zodiac
            "⛎"              // ⛎ ophiuchus
    ));

    /** Devils, demons and the like. */
    private static final Set<String> BLOCKED_CREATURES = new HashSet<>(Arrays.asList(
            "👿",       // 👿 angry face with horns (imp)
            "😈",       // 😈 smiling face with horns
            "👹",       // 👹 ogre
            "👺",       // 👺 goblin
            "👻",       // 👻 ghost
            "👽",       // 👽 alien
            "👾"        // 👾 alien monster
    ));

    /**
     * LGBT imagery. Same-sex couples and the pride/trans flags; the mixed-sex couple emoji are NOT
     * here — they fall under the living-beings switch below, which is a separate decision.
     */
    private static final String[] BLOCKED_CONTAINS = {
            "🏳️‍🌈",   // 🏳️‍🌈 rainbow flag
            "🏳‍🌈",         // 🏳‍🌈 (no VS16)
            "🏳️‍⚧",         // 🏳️‍⚧️ transgender flag
            "🏳‍⚧",               // (no VS16)
            "👨‍❤️‍👨",  // 👨‍❤️‍👨
            "👨‍❤‍👨",
            "👩‍❤️‍👩",  // 👩‍❤️‍👩
            "👩‍❤‍👩",
            "👨‍❤️‍💋‍👨",  // 👨‍❤️‍💋‍👨
            "👨‍❤‍💋‍👨",
            "👩‍❤️‍💋‍👩",  // 👩‍❤️‍💋‍👩
            "👩‍❤‍💋‍👩",
            // Same-sex FAMILY sequences — 👨‍👨‍👦, 👩‍👩‍👧 and every child combination built on them.
            // Found by scrolling the picker after the first version of this filter shipped: the couple
            // and kiss sequences were gone while a whole screen of two-father / two-mother families was
            // still there, because those spell the pair as 👨‍👨 rather than 👨‍❤️‍👨.
            "👨‍👨",           // 👨‍👨 (two fathers, any children)
            "👩‍👩",           // 👩‍👩 (two mothers, any children)
            // "Holding hands" also exists as a ZWJ sequence with the handshake joiner, and that is the
            // spelling this emoji set actually ships — the single-codepoint 👬 / 👭 below did not match
            // it, so a row of same-sex pairs survived the first pass. The mixed 👩‍🤝‍👨 is deliberately
            // not listed: it is a man and a woman.
            "👨‍🤝‍👨",     // 👨‍🤝‍👨 men holding hands
            "👩‍🤝‍👩",     // 👩‍🤝‍👩 women holding hands
    };

    /** Single-codepoint same-sex pair emoji. */
    private static final Set<String> BLOCKED_PAIRS = new HashSet<>(Arrays.asList(
            "👬",       // 👬 two men holding hands
            "👭",       // 👭 two women holding hands
            "⚧"              // ⚧ transgender symbol
    ));

    /**
     * @return true when the picker must not offer this emoji at all.
     *
     * Never throws: a filter that crashes takes the whole emoji keyboard down with it, and an emoji
     * shown by mistake is a far smaller problem than a chat screen that cannot open its keyboard.
     */
    static boolean isBlocked(final Emoji emoji)
    {
        try
        {
            if (emoji == null)
            {
                return true;
            }

            final String unicode = emoji.getUnicode();
            if (unicode == null || unicode.isEmpty())
            {
                return false;
            }

            // Skin-tone and VS16 variants share the base codepoint, so strip the selector before the
            // exact-match sets are consulted.
            final String bare = unicode.replace("️", "");

            if (BLOCKED_SYMBOLS.contains(bare) || BLOCKED_CREATURES.contains(bare)
                || BLOCKED_PAIRS.contains(bare))
            {
                return true;
            }

            for (final String needle : BLOCKED_CONTAINS)
            {
                if (unicode.contains(needle))
                {
                    return true;
                }
            }

            return false;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** BLOCKED_CONTAINS as {@link #isBlockedSequence} compares it: without U+FE0F and skin tones. */
    private static final String[] BLOCKED_CONTAINS_BARE = bareAll(BLOCKED_CONTAINS);

    private static boolean isSkinTone(final int codePoint)
    {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    /** The text without U+FE0F and the skin tones U+1F3FB..U+1F3FF. */
    static String withoutSelectorsAndSkinTones(final String text)
    {
        final StringBuilder bare = new StringBuilder(text.length());
        text.codePoints()
            .filter(codePoint -> codePoint != 0xFE0F && !isSkinTone(codePoint))
            .forEach(bare::appendCodePoint);
        return bare.toString();
    }

    private static String[] bareAll(final String[] texts)
    {
        final String[] bare = new String[texts.length];
        for (int i = 0; i < texts.length; i++)
        {
            bare[i] = withoutSelectorsAndSkinTones(texts[i]);
        }
        return bare;
    }

    /**
     * KHANDAQ (owner, 2026-11-14; QA live F14): whether a joined (ZWJ) sequence in a message is one
     * the picker does not offer. Such a sequence is drawn apart, as its members, never as one
     * picture or one glyph of the system font: see {@link KhandaqJoinedEmoji}.
     *
     * Compared without U+FE0F and the skin tones: a message can carry any spelling and any skin
     * tone of a sequence, and the picker drops a sequence with all of its skin tones. Keep in step
     * with BLOCKED_SEQUENCES of the desktop (khandaq-desktop/src/persistence/smileypack.cpp).
     *
     * Never throws, like {@link #isBlocked}.
     */
    static boolean isBlockedSequence(final String sequence)
    {
        try
        {
            if (sequence == null || sequence.isEmpty())
            {
                return false;
            }
            final String bare = withoutSelectorsAndSkinTones(sequence);
            for (final String needle : BLOCKED_CONTAINS_BARE)
            {
                if (bare.contains(needle))
                {
                    return true;
                }
            }
            return false;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /**
     * @return true when the user asked for emoji depicting living beings to be hidden as well.
     *
     * Behind a setting, off by default, because "living beings" is most of the standard set — every
     * face, person, hand and animal — and switching it on unasked would gut the emoji keyboard for
     * every other user of the app. The people who want it can turn it on; nobody else is surprised.
     */
    static boolean hideLivingBeings()
    {
        return MainActivity.PREF__hide_living_being_emoji;
    }
}

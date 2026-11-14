package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * KHANDAQ (owner, 2026-11-14; QA live F14): a joined (ZWJ) emoji sequence in a message is drawn as one
 * character, unless it is one the picker does not offer.
 *
 * <p>QA: com.vanniktech:emoji-google:0.9.0 has the pictures of Emoji 13.0 and older. A newer joined
 * sequence, such as the heart on fire U+2764 U+FE0F U+200D U+1F525 (Emoji 13.1), is not among them, so
 * the set drew the members it has, a heart and a fire, side by side, in the bubbles and in the input
 * field. Where the set has no picture of the whole sequence and the system font has one glyph for it,
 * the set's pictures of the members are taken off and the font draws the sequence.
 *
 * <p>The sequences the picker does not offer ({@link KhandaqEmojiFilter#isBlockedSequence}) keep the
 * pictures of their members, drawn apart, as they always were: the set has no picture of them, and
 * the font must not draw them either.
 *
 * <p>Pure logic, without Android, so it is unit-testable; KhandaqGoogleEmojiProvider applies it to the
 * spans of a text. The same sequences are found on the desktop (smileypack.cpp, joinedSequences).
 */
final class KhandaqJoinedEmoji
{
    static final int ZERO_WIDTH_JOINER = 0x200D;

    private KhandaqJoinedEmoji()
    {
    }

    static boolean isSkinTone(final int codePoint)
    {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    /**
     * What follows an emoji inside its sequence: the presentation selectors, a skin tone, the keycap
     * mark and the tag letters of a subdivision flag.
     */
    static boolean isSequenceExtender(final int codePoint)
    {
        return codePoint == 0xFE0F || codePoint == 0xFE0E || codePoint == 0x20E3 || isSkinTone(codePoint)
               || (codePoint >= 0xE0020 && codePoint <= 0xE007F);
    }

    /**
     * An emoji that can stand on either side of a joiner: the symbols and pictographs of the BMP that
     * have an emoji form (U+203C to U+3299: ☠, ❤, ♀, ⚕ ...), without the combining marks for symbols
     * (U+20D0 to U+20FF, the keycap mark among them), and everything from U+1F000 on.
     */
    static boolean isSequenceMember(final int codePoint)
    {
        return (codePoint >= 0x203C && codePoint <= 0x3299 && !(codePoint >= 0x20D0 && codePoint <= 0x20FF))
               || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF && !isSkinTone(codePoint));
    }

    private static int skipExtenders(final CharSequence text, int position)
    {
        while (position < text.length())
        {
            final int codePoint = Character.codePointAt(text, position);
            if (!isSequenceExtender(codePoint))
            {
                break;
            }
            position += Character.charCount(codePoint);
        }
        return position;
    }

    /**
     * The joined emoji sequences of a text: an emoji, and one or more times a joiner and an emoji, each
     * with its extenders.
     *
     * @return the start and the end (exclusive) of each sequence, in chars
     */
    static List<int[]> joinedSequences(final CharSequence text)
    {
        final List<int[]> sequences = new ArrayList<>();
        if (text == null)
        {
            return sequences;
        }
        int position = 0;
        while (position < text.length())
        {
            final int codePoint = Character.codePointAt(text, position);
            if (!isSequenceMember(codePoint))
            {
                position += Character.charCount(codePoint);
                continue;
            }
            final int start = position;
            int end = skipExtenders(text, position + Character.charCount(codePoint));
            boolean joined = false;
            while (end + 1 < text.length() && text.charAt(end) == ZERO_WIDTH_JOINER)
            {
                final int member = Character.codePointAt(text, end + 1);
                if (!isSequenceMember(member))
                {
                    break;
                }
                end = skipExtenders(text, end + 1 + Character.charCount(member));
                joined = true;
            }
            if (joined)
            {
                sequences.add(new int[]{start, end});
            }
            position = end;
        }
        return sequences;
    }

    /** The end of a sequence without the presentation selectors after its last member, which draw nothing. */
    private static int visibleEnd(final CharSequence text, final int start, int end)
    {
        while (end > start && (text.charAt(end - 1) == '️' || text.charAt(end - 1) == '︎'))
        {
            end--;
        }
        return end;
    }

    /**
     * Whether the set's pictures inside a joined sequence are taken off, so that the system font draws
     * the sequence as one character.
     *
     * @param text      the text the sequence is in
     * @param start     where the sequence starts, as {@link #joinedSequences} found it
     * @param end       where it ends (exclusive)
     * @param pictures  the start and the end of each picture the set put inside the sequence
     * @param fontHasIt whether the system font has one glyph for a sequence
     * @return false when there is nothing to take off, when one picture is the whole sequence (the set
     * has it), when the picker does not offer the sequence, or when the font has no glyph for it
     */
    static boolean takePicturesOff(final CharSequence text, final int start, final int end, final List<int[]> pictures,
                                   final Predicate<String> fontHasIt)
    {
        if (pictures == null || pictures.isEmpty())
        {
            return false;
        }
        if (pictures.size() == 1)
        {
            final int[] picture = pictures.get(0);
            if (picture[0] <= start && picture[1] >= visibleEnd(text, start, end))
            {
                return false;
            }
        }
        final String sequence = text.subSequence(start, end).toString();
        if (KhandaqEmojiFilter.isBlockedSequence(sequence))
        {
            return false;
        }
        return fontHasIt.test(sequence);
    }
}

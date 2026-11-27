package com.zoffcc.applications.trifa;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * KHANDAQ (inbox #20, 27.11): where emoji can be in a long text, so that the emoji set's search runs there and
 * not over the whole text.
 *
 * <p>The emoji library (com.vanniktech:emoji 0.9.0) finds the emoji of a text with one pattern: every emoji of
 * the set, longest first, joined by "|", case-insensitive. Its matcher tries the whole alternation at every
 * place where an emoji of the set could start, digits among them, so a text of tens of thousands of chars took
 * it a long while, on the main thread: once for each change of the input field and three times for each long
 * bubble. Android said that Khandaq was not responding in a chat with a long piece of code.
 *
 * <p>Every emoji of the set has a char outside ASCII (a symbol, a surrogate, a presentation selector, the joiner
 * or the keycap mark), and its chars in ASCII are few: the digit or sign of a keycap, before the mark. So a match
 * of the whole text holds such a char, and has at most {@link #before} chars before the first of them,
 * {@link #after} after the last and {@link #between} between two. The windows are the runs of such chars with
 * that room around them, joined where they touch: every match of the whole text lies in one window, and the
 * matcher, run on the windows alone, finds the same ones there, as the pattern is a list of plain strings,
 * without anchors or look-around. A text without such chars has no emoji to find. As the search folds case, the
 * other cases of these chars count as well.
 *
 * <p>Pure logic, without Android, so it is unit-testable; KhandaqGoogleEmojiProvider runs the set's search on the
 * windows.
 */
final class EmojiSearchWindows
{
    /** A text up to this many chars is searched whole, as the library does: its search is quick there. */
    static final int WHOLE_TEXT_UP_TO = 1024;

    /**
     * What stands between two windows searched together: no emoji of the set holds it, so no match runs from one
     * window into the next, and each window is searched as it would be alone.
     */
    static final char BETWEEN_WINDOWS = '\n';

    /** The chars outside ASCII that the set's emoji have, with their other cases. */
    private final BitSet emojiChars;

    /** The most chars in ASCII that an emoji of the set has before its first char outside ASCII. */
    final int before;

    /** The most chars in ASCII that an emoji of the set has after its last char outside ASCII. */
    final int after;

    /** The most chars in ASCII that an emoji of the set has between two of its chars outside ASCII. */
    final int between;

    private EmojiSearchWindows(final BitSet emojiChars, final int before, final int after, final int between)
    {
        this.emojiChars = emojiChars;
        this.before = before;
        this.after = after;
        this.between = between;
    }

    /**
     * The windows for the emoji of a set, or null where they cannot be had: an emoji without a char outside
     * ASCII, or with a char whose other case is in ASCII or has another number of chars, could match where no
     * char of the set is, and one that holds {@link #BETWEEN_WINDOWS} could run from one window into the next.
     */
    @Nullable
    static EmojiSearchWindows of(@Nullable final Iterable<String> emoji)
    {
        if (emoji == null)
        {
            return null;
        }
        // The set's chars outside ASCII, each once: thousands of emoji share a few hundred of them.
        final BitSet own = new BitSet(0x10000);
        int before = 0;
        int after = 0;
        int between = 0;
        boolean any = false;
        for (final String unicode : emoji)
        {
            if (unicode == null || unicode.isEmpty())
            {
                continue;
            }
            boolean outsideAscii = false;
            // The chars in ASCII since the last char outside it, or since the start.
            int ascii = 0;
            for (int i = 0; i < unicode.length(); i++)
            {
                final char c = unicode.charAt(i);
                if (c == BETWEEN_WINDOWS)
                {
                    return null;
                }
                if (c < 0x80)
                {
                    ascii++;
                    continue;
                }
                own.set(c);
                if (outsideAscii)
                {
                    between = Math.max(between, ascii);
                }
                else
                {
                    before = Math.max(before, ascii);
                    outsideAscii = true;
                }
                ascii = 0;
            }
            if (!outsideAscii)
            {
                return null;
            }
            after = Math.max(after, ascii);
            any = true;
        }
        if (!any)
        {
            return null;
        }
        final BitSet chars = new BitSet(0x10000);
        for (int c = own.nextSetBit(0); c >= 0; c = own.nextSetBit(c + 1))
        {
            if (!addWithItsCases(chars, (char) c))
            {
                return null;
            }
        }
        return new EmojiSearchWindows(chars, before, after, between);
    }

    /**
     * Puts a char outside ASCII and its other cases in the set; false when a case of it is in ASCII or has another
     * number of chars (the sharp s is "SS" in capitals): a match could then hold none of the set's chars, or a
     * number of the text's chars other than its emoji has.
     */
    private static boolean addWithItsCases(final BitSet chars, final char c)
    {
        final char lower = Character.toLowerCase(c);
        final char upper = Character.toUpperCase(c);
        final char title = Character.toTitleCase(c);
        if (lower < 0x80 || upper < 0x80 || title < 0x80)
        {
            return false;
        }
        final String alone = String.valueOf(c);
        if (alone.toLowerCase(Locale.ROOT).length() != 1 || alone.toUpperCase(Locale.ROOT).length() != 1)
        {
            return false;
        }
        chars.set(c);
        chars.set(lower);
        chars.set(upper);
        chars.set(title);
        return true;
    }

    /** Whether a char of a text can be part of an emoji of the set: itself or, as the search folds case, a case. */
    boolean isEmojiChar(final char c)
    {
        return c >= 0x80 && (emojiChars.get(c) || emojiChars.get(Character.toLowerCase(c))
                             || emojiChars.get(Character.toUpperCase(c)));
    }

    /**
     * Where the set's search runs in a text: null for the whole text (a short one, or one that is mostly emoji),
     * else the windows in order, {start, end} each, end exclusive, none touching another; none at all when the
     * text has no emoji to find.
     */
    @Nullable
    List<int[]> windows(@Nullable final CharSequence text)
    {
        if (text == null || text.length() <= WHOLE_TEXT_UP_TO)
        {
            return null;
        }
        final int length = text.length();
        final List<int[]> windows = new ArrayList<>();
        int covered = 0;
        int start = -1;
        int end = -1;
        int i = 0;
        while (i < length)
        {
            if (!isEmojiChar(text.charAt(i)))
            {
                i++;
                continue;
            }
            // A run of the set's chars, at most `between` other chars between two of them: the chars outside
            // ASCII of a match are one run.
            final int first = i;
            int last = i;
            for (int next = i + 1; next < length && next - last - 1 <= between; next++)
            {
                if (isEmojiChar(text.charAt(next)))
                {
                    last = next;
                }
            }
            i = last + 1;
            // A match whose chars are in the run starts at most `before` chars before it and ends at most `after`
            // chars after it.
            final int from = Math.max(0, first - before);
            final int to = Math.min(length, last + 1 + after);
            if (start >= 0 && from <= end)
            {
                end = Math.max(end, to);
                continue;
            }
            if (start >= 0)
            {
                windows.add(new int[]{start, end});
                covered += end - start;
            }
            start = from;
            end = to;
        }
        if (start >= 0)
        {
            windows.add(new int[]{start, end});
            covered += end - start;
        }
        // Mostly emoji: the library's search of the whole text, without copying the windows out.
        return covered * 2 > length ? null : windows;
    }
}

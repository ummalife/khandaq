package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.List;

/**
 * KHANDAQ (inbox #26, 27.11): an entry of the transcript lays out at most {@link #ENTRY_MAX_CHARS} of its text,
 * and Read more under it opens the whole text in AirLongTextActivity. All that the list does to the words of an
 * entry costs as much as the words are long: the emoji pictures, the link patterns, EmojiCompat, the line
 * breaking. A note of 45,000 chars held the main thread for up to 1.5 s on the emulator as its conversation
 * opened, longer on a slow phone, up to the "isn't responding" dialog. The reader lays the text out in pieces of
 * at most {@link #PIECE_MAX_CHARS}, in a list that lays out only the pieces on the screen.
 *
 * <p>A cut falls at the last line break near the limit, else at the last space there, else after the last whole
 * character (MessageSplitter.wholeCharacterCut): an emoji sequence, a flag, a letter with its marks stay whole.
 * Pure Java, no Android runtime.
 */
final class AirLongText
{
    /** The most chars of a text that an entry shows: about a screen and a half on a phone. */
    static final int ENTRY_MAX_CHARS = 3000;
    /** A cut of an entry's text at a line break or a space keeps at least this much of it. */
    static final int ENTRY_MIN_CHARS = ENTRY_MAX_CHARS * 3 / 4;
    /** The most chars of one piece of the reader. */
    static final int PIECE_MAX_CHARS = 800;
    /** A cut between two pieces at a line break or a space keeps at least this much in the first. */
    static final int PIECE_MIN_CHARS = PIECE_MAX_CHARS / 2;
    /** With a search, the part an entry shows starts at most this many chars before the match. */
    static final int SEARCH_LEAD_CHARS = 200;
    static final String ELLIPSIS = "…";

    /** Chars after a cut that tell whether a grapheme cluster goes on past it (a ZWJ family, a tag flag). */
    private static final int CLUSTER_SLACK = 64;

    private AirLongText()
    {
    }

    /** Whether an entry shows only a part of text and offers the whole of it. */
    static boolean isCut(final String text)
    {
        return text != null && text.length() > ENTRY_MAX_CHARS;
    }

    /**
     * What an entry shows of text: all of it when it fits, else its start with an ellipsis after it. With a
     * search whose first match lies past that start, the part around the match instead, with an ellipsis on
     * either side, so that the match which brought the message into the results is on the screen. The match is
     * found as EmojiTextViewLinks.buildHighlightString finds the one it marks.
     */
    static String shown(final String text, final String search)
    {
        if (!isCut(text))
        {
            return text;
        }
        final int[] head = cut(text, 0, ENTRY_MAX_CHARS, ENTRY_MIN_CHARS);
        int start = 0;
        int[] part = head;
        final int match = firstMatch(text, search);
        if (match >= 0 && match + search.length() > head[0])
        {
            start = partStart(text, match);
            part = cut(text, start, ENTRY_MAX_CHARS, ENTRY_MIN_CHARS);
        }
        final StringBuilder shown = new StringBuilder(part[0] - start + 2);
        if (start > 0)
        {
            shown.append(ELLIPSIS);
        }
        shown.append(text, start, part[0]);
        if (part[1] < text.length())
        {
            shown.append(ELLIPSIS);
        }
        return shown.toString();
    }

    /**
     * The pieces the reader lays text out in, in order, none of them empty. A piece that ends a paragraph before
     * an empty line ends in a line break, so the empty line stays between the two pieces.
     */
    static List<String> pieces(final String text)
    {
        final List<String> pieces = new ArrayList<>();
        if (text == null)
        {
            return pieces;
        }
        final int length = text.length();
        int start = 0;
        while (start < length)
        {
            final int[] part = cut(text, start, PIECE_MAX_CHARS, PIECE_MIN_CHARS);
            if (part[0] > start)
            {
                final String piece = text.substring(start, part[0]);
                pieces.add(lineBreaks(text, part[0], part[1]) >= 2 ? piece + "\n" : piece);
            }
            start = part[1];
        }
        return pieces;
    }

    /**
     * Where the part of text that starts at from ends, as {end, next}: at most maxChars on, at the last line
     * break at least minChars on, else at the last space there, else after the last whole character. The white
     * space at a cut belongs to neither part; next is where the text goes on, at the indent of its line. When
     * the rest fits it is the part, without the white space it ends in. next is always past from.
     */
    static int[] cut(final String text, final int from, final int maxChars, final int minChars)
    {
        final int length = text.length();
        if (length - from <= maxChars)
        {
            int end = length;
            while (end > from && Character.isWhitespace(text.charAt(end - 1)))
            {
                end--;
            }
            return new int[]{end, length};
        }
        final String window = text.substring(from, Math.min(length, from + maxChars + CLUSTER_SLACK));
        int hard = from + MessageSplitter.wholeCharacterCut(window, maxChars);
        if (hard <= from)
        {
            hard = from + maxChars;
        }
        // White space right at hard can go too: the part before it is maxChars long at most.
        final int floor = from + Math.max(1, minChars);
        for (int i = hard; i >= floor; i--)
        {
            if (isLineBreak(text.charAt(i)) && !markAfter(text, i))
            {
                return spaceCut(text, from, i);
            }
        }
        for (int i = hard; i >= floor; i--)
        {
            if (Character.isWhitespace(text.charAt(i)) && !markAfter(text, i))
            {
                return spaceCut(text, from, i);
            }
        }
        return new int[]{hard, hard};
    }

    /** A cut at the white space at index at: the part before it without its trailing white space, and the rest. */
    private static int[] spaceCut(final String text, final int from, final int at)
    {
        int end = at;
        while (end > from && Character.isWhitespace(text.charAt(end - 1)))
        {
            end--;
        }
        int next = at;
        int lineStart = -1;
        while (next < text.length() && Character.isWhitespace(text.charAt(next)))
        {
            if (isLineBreak(text.charAt(next)))
            {
                lineStart = next + 1;
            }
            next++;
        }
        if (next < text.length() && lineStart >= 0)
        {
            next = lineStart;
        }
        return new int[]{end, next};
    }

    /** Where the part around a match at index match starts: its line, if that starts close enough; else a word. */
    private static int partStart(final String text, final int match)
    {
        final int floor = Math.max(0, match - SEARCH_LEAD_CHARS);
        for (int i = match - 1; i >= floor; i--)
        {
            if (isLineBreak(text.charAt(i)))
            {
                return i + 1;
            }
        }
        for (int i = floor; i < match; i++)
        {
            if (Character.isWhitespace(text.charAt(i)))
            {
                return i + 1;
            }
        }
        return floor == 0 ? 0 : MessageSplitter.wholeCharacterCut(text, floor);
    }

    /** The first match of search in text, ignoring case as the highlight does, or -1. */
    private static int firstMatch(final String text, final String search)
    {
        if (search == null || search.isEmpty())
        {
            return -1;
        }
        final int match = text.toLowerCase().indexOf(search.toLowerCase());
        return match < text.length() ? match : -1;
    }

    /** How many line breaks lie in text[from, to); a CR LF counts once. */
    private static int lineBreaks(final String text, final int from, final int to)
    {
        int count = 0;
        for (int i = from; i < to; i++)
        {
            final char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')))
            {
                count++;
            }
        }
        return count;
    }

    /** Whether the code point after index i is a mark that belongs to the char at i. */
    private static boolean markAfter(final String text, final int i)
    {
        if (i + 1 >= text.length())
        {
            return false;
        }
        switch (Character.getType(text.codePointAt(i + 1)))
        {
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.COMBINING_SPACING_MARK:
                return true;
            default:
                return false;
        }
    }

    private static boolean isLineBreak(final char c)
    {
        return c == '\n' || c == '\r';
    }
}

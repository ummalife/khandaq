package com.zoffcc.applications.trifa;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KHANDAQ (QA live, F13): a text longer than one Tox message goes as several messages when the friend's
 * app does not put chunks back together (iOS, the desktop, other Tox clients) or the friend is offline.
 * Before, it went as one message cut at the limit, and its end was lost. Each part holds at most maxBytes
 * of UTF-8, which is what toxcore checks: the JNI layer sends String.getBytes in UTF-8.
 *
 * The cut is where the desktop's splitMessage makes it: at the last line break that fits, else at the
 * last space, else after the last whole character. Whole means a grapheme cluster: an emoji with its skin
 * tone, a ZWJ family, a flag, a letter with its accents stay in one part where a part has room for them,
 * and a surrogate pair is never split. The white space at a cut goes, so no part starts or ends with an
 * empty line or a space; the indent of the line after a line break stays, unless it would cost a whole
 * character. The first headChars chars (the reply header) stay in the first part. Pure Java, no Android
 * runtime.
 */
final class MessageSplitter
{
    private static final int ZERO_WIDTH_JOINER = 0x200D;

    /**
     * How far apart the sent times of the parts are. A wake push finds its message by sent time, in a
     * window that reaches PUSH_URL_TRIGGER_GET_MESSAGE_FOR_delta_ms_prev back and takes the earliest row
     * (update_message_in_db_sent_push_set). Parts a millisecond apart would all count their pushes on
     * the first part, and resend_push_for_v3_messages would wake the friend for the others every 5 s.
     */
    static final long PART_GAP_MS = TRIFAGlobals.PUSH_URL_TRIGGER_GET_MESSAGE_FOR_delta_ms_prev + 1L;

    private MessageSplitter()
    {
    }

    /** The sent time of part index of a text whose first part is sent at firstSentMs. */
    static long partSentTimestamp(final long firstSentMs, final int index)
    {
        return firstSentMs + index * PART_GAP_MS;
    }

    /** The parts text goes as, in order: one when it fits, none when it is empty. */
    static List<String> split(final String text, final int headChars, final int maxBytes)
    {
        final List<int[]> ranges = ranges(text, headChars, maxBytes);
        final List<String> parts = new ArrayList<>(ranges.size());
        for (final int[] range : ranges)
        {
            parts.add(text.substring(range[0], range[1]));
        }
        return parts;
    }

    /** Where each part of {@link #split} lies in text, as {start, end}. */
    static List<int[]> ranges(final String text, final int headChars, final int maxBytes)
    {
        if (maxBytes < 4)
        {
            throw new IllegalArgumentException("maxBytes " + maxBytes + " is less than one code point");
        }
        final List<int[]> ranges = new ArrayList<>();
        if (text == null || text.isEmpty())
        {
            return ranges;
        }
        final int length = text.length();
        if (AirUtf8Limit.utf8Length(text, 0, length) <= maxBytes)
        {
            ranges.add(new int[]{0, length});
            return ranges;
        }
        // A head that leaves the first part no room is split as the rest is: the text matters more.
        final int head = headChars > 0 && headChars < length
                         && AirUtf8Limit.utf8Length(text, 0, headChars) < maxBytes ? headChars : 0;
        BreakIterator clusters = null;
        int start = 0;
        while (start < length)
        {
            final int end = start + AirUtf8Limit.fittingChars(text, start, maxBytes);
            if (end >= length)
            {
                ranges.add(new int[]{start, length});
                break;
            }
            final int from = Math.max(start, head);
            final int content = firstNonSpace(text, from, end);
            if (content < 0)
            {
                // White space alone fills the room after the head: it goes, and the head goes on its own.
                addHead(ranges, text, start, from);
                start = skipSpace(text, end);
                continue;
            }
            int cut = lastSpace(text, content, end, true);
            if (cut < 0)
            {
                cut = lastSpace(text, content, end, false);
            }
            if (cut >= 0)
            {
                ranges.add(new int[]{start, trimEnd(text, start, cut)});
                start = nextStart(text, cut);
                continue;
            }
            if (clusters == null)
            {
                clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
                clusters.setText(text);
            }
            cut = lastWholeCharacter(text, clusters, from, content, end);
            if (cut >= 0)
            {
                ranges.add(new int[]{start, cut});
                start = cut;
            }
            else if (content > start)
            {
                // The first character does not fit beside the head or the indent before it: the head goes
                // on its own, the indent goes, and the character opens the next part.
                addHead(ranges, text, start, from);
                start = content;
            }
            else
            {
                // One character longer than a message: it is cut between two code points.
                ranges.add(new int[]{start, end});
                start = end;
            }
        }
        return ranges;
    }

    /**
     * KHANDAQ (QA live, F15): where to cut text so that at most maxChars chars stay and every grapheme cluster
     * stays whole, for a text that is shortened to a number of chars (the chat list preview). When the first
     * cluster alone is longer than that, the cut falls between two code points, never inside a surrogate
     * pair. text.length() when the text fits.
     */
    static int wholeCharacterCut(final String text, final int maxChars)
    {
        final int length = text.length();
        if (maxChars >= length)
        {
            return length;
        }
        if (maxChars <= 0)
        {
            return 0;
        }
        final BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
        clusters.setText(text);
        final int cut = lastWholeCharacter(text, clusters, 0, 0, maxChars);
        if (cut > 0)
        {
            return cut;
        }
        return Character.isHighSurrogate(text.charAt(maxChars - 1)) && Character.isLowSurrogate(text.charAt(maxChars))
               ? maxChars - 1 : maxChars;
    }

    /** The head, text[start, from), as a part of its own, unless there is none. */
    private static void addHead(final List<int[]> ranges, final String text, final int start, final int from)
    {
        final int end = trimEnd(text, start, from);
        if (end > start)
        {
            ranges.add(new int[]{start, end});
        }
    }

    /**
     * The last line break (lineBreak) or the last other white space in (content, end] that a cut can drop,
     * or -1: not one that a mark after it belongs to.
     */
    private static int lastSpace(final String text, final int content, final int end, final boolean lineBreak)
    {
        for (int i = end; i > content; i--)
        {
            final char c = text.charAt(i);
            final boolean wanted = lineBreak ? isLineBreak(c) : !isLineBreak(c) && Character.isWhitespace(c);
            if (wanted && (i + 1 >= text.length() || !extendsCluster(text.codePointAt(i + 1))))
            {
                return i;
            }
        }
        return -1;
    }

    /**
     * Where the part after a cut at white space starts: past that white space, but at the indent of the
     * last line in it when it holds a line break.
     */
    private static int nextStart(final String text, final int cut)
    {
        int lineStart = -1;
        int i = cut;
        while (i < text.length() && Character.isWhitespace(text.charAt(i)))
        {
            if (isLineBreak(text.charAt(i)))
            {
                lineStart = i + 1;
            }
            i++;
        }
        return i < text.length() && lineStart >= 0 ? lineStart : i;
    }

    /**
     * The last cut in (content, end] that leaves every grapheme cluster whole, or -1. The walk starts at
     * from, where the part's cuts may start, so the regional indicators of flags pair up from there.
     */
    private static int lastWholeCharacter(final String text, final BreakIterator clusters, final int from,
                                          final int content, final int end)
    {
        int cut = -1;
        int regionalIndicators = 0;
        int i = from;
        while (i < end)
        {
            final int before = text.codePointAt(i);
            regionalIndicators = isRegionalIndicator(before) ? regionalIndicators + 1 : 0;
            i += Character.charCount(before);
            if (i > content && i <= end && !holdsTogether(text, i, before, regionalIndicators)
                && clusters.isBoundary(i))
            {
                cut = i;
            }
        }
        return cut;
    }

    /**
     * Whether the code points on both sides of index i are one grapheme cluster by the rules that chat text
     * needs: CR LF, an emoji ZWJ sequence, a mark, variation selector, skin tone or emoji tag after its
     * base, and the two regional indicators of a flag. On Android java.text.BreakIterator (ICU) knows them;
     * the JDK 17 that runs the unit tests cuts ZWJ sequences, flags and skin tones.
     */
    private static boolean holdsTogether(final String text, final int i, final int before,
                                         final int regionalIndicators)
    {
        if (i >= text.length())
        {
            return false;
        }
        final int after = text.codePointAt(i);
        if (before == '\r' && after == '\n')
        {
            return true;
        }
        if (before == ZERO_WIDTH_JOINER || after == ZERO_WIDTH_JOINER || extendsCluster(after))
        {
            return true;
        }
        return isRegionalIndicator(after) && regionalIndicators % 2 == 1;
    }

    /** A code point that belongs to the one before it: a mark, a variation selector, a skin tone, a tag. */
    private static boolean extendsCluster(final int codePoint)
    {
        switch (Character.getType(codePoint))
        {
            case Character.NON_SPACING_MARK:
            case Character.ENCLOSING_MARK:
            case Character.COMBINING_SPACING_MARK:
                return true;
            default:
                return (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF) || (codePoint >= 0xE0020 && codePoint <= 0xE007F);
        }
    }

    private static boolean isRegionalIndicator(final int codePoint)
    {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    private static boolean isLineBreak(final char c)
    {
        return c == '\n' || c == '\r';
    }

    /** The first char in [from, end) that is not white space, or -1. */
    private static int firstNonSpace(final String text, final int from, final int end)
    {
        for (int i = from; i < end; i++)
        {
            if (!Character.isWhitespace(text.charAt(i)))
            {
                return i;
            }
        }
        return -1;
    }

    private static int skipSpace(final String text, final int from)
    {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i)))
        {
            i++;
        }
        return i;
    }

    private static int trimEnd(final String text, final int start, final int end)
    {
        int i = end;
        while (i > start && Character.isWhitespace(text.charAt(i - 1)))
        {
            i--;
        }
        return i;
    }
}

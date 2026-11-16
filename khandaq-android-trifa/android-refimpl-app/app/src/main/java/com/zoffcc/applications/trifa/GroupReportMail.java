package com.zoffcc.applications.trifa;

import java.text.BreakIterator;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * KHANDAQ (0.2.57, SPEC-community part B, item 5): Report, on a message someone else wrote in a group, any group:
 * a mail to support for the person to send from their own mail app. App Review asks for it on iOS (guideline 1.2,
 * content from strangers), and every platform has it.
 * <p>
 * The mail is the desktop's (aircommunity.cpp, reportMailUrl), so that support gets the same mail from every
 * platform: to support@khandaq.org, the subject "Report: " and the group's name, and a body with the group, the
 * first 8 characters of the author's key in the group in upper case ("unknown" when the message has none stored, or
 * came through history sync and no signature proved it: reportedAuthorKey), the time in UTC (ISO 8601) and the
 * text, up to 500 characters as a person counts them (firstCharacters), its lines ended with CRLF. The words of the
 * mail are support's, in English: the person reads them before sending. Free of Android, so that the unit tests
 * hold it as the phone builds it.
 */
final class GroupReportMail
{
    static final String ADDRESS = "support@khandaq.org";
    /** The text of the message goes up to this many characters, as a person counts them; none is cut in half. */
    static final int TEXT_LIMIT = 500;
    /** The author is named by the first characters of their key in the group. */
    static final int AUTHOR_KEY_CHARS = 8;
    /** The author of a message that has no key of its author stored: named so, never guessed. */
    static final String UNKNOWN_AUTHOR = "unknown";

    /** The joiners and marks of characters a BreakIterator may split off them (wholeClusterEnd). */
    private static final int ZWJ = 0x200D;
    private static final int ZWNJ = 0x200C;
    private static final int EMOJI_PRESENTATION = 0xFE0F;
    private static final int SKIN_TONE_FIRST = 0x1F3FB;
    private static final int SKIN_TONE_LAST = 0x1F3FF;
    private static final int TAG_FIRST = 0xE0020;
    private static final int TAG_LAST = 0xE007F;
    private static final int REGIONAL_INDICATOR_FIRST = 0x1F1E6;
    private static final int REGIONAL_INDICATOR_LAST = 0x1F1FF;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int GROUP_KEY_HEX_LENGTH = 64;
    /** GroupMessage.direction of a message that came in. */
    private static final int DIRECTION_INCOMING = 0;

    private GroupReportMail()
    {
    }

    /**
     * Whether a message of a group can be reported: it came in, and its stored author key is not ours there. Our own
     * messages cannot, nor the lines the app writes itself (system messages, TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY). A
     * message whose author key was not stored can: its mail names the author unknown (authorPrefix).
     */
    static boolean reportable(final int direction, final String authorKey, final String ownGroupKey)
    {
        if ((direction != DIRECTION_INCOMING) || TRIFAGlobals.TRIFA_SYSTEM_MESSAGE_PEER_PUBKEY.equals(authorKey))
        {
            return false;
        }
        return (authorKey == null) || (ownGroupKey == null) || !ownGroupKey.equalsIgnoreCase(authorKey);
    }

    static String subject(final String groupName)
    {
        return "Report: " + simplified(groupName);
    }

    /** The body, lines ended with CRLF; {@code timeMs} is when the message was written. */
    static String body(final String groupName, final String authorKey, final long timeMs, final String text)
    {
        return mailLines("Group: " + simplified(groupName) + "\nAuthor: " + authorPrefix(authorKey) + "\nTime: "
                         + isoUtc(timeMs) + "\nMessage:\n" + firstCharacters(text, TEXT_LIMIT));
    }

    /** The mailto: link, its subject and body percent-encoded as UTF-8 (RFC 6068). */
    static String mailto(final String groupName, final String authorKey, final long timeMs, final String text)
    {
        return "mailto:" + ADDRESS + "?subject=" + percentEncoded(subject(groupName)) + "&body="
               + percentEncoded(body(groupName, authorKey, timeMs, text));
    }

    /**
     * The author as the mail names them: the first characters, in upper case, of the key in the group stored with
     * the message when it came (GroupMessage.tox_group_peer_pubkey). Never looked up again from a member's number:
     * toxcore gives the number of a member who left to the next one who joins, so a lookup would name an innocent
     * newcomer. A message without such a key: UNKNOWN_AUTHOR.
     */
    static String authorPrefix(final String authorKey)
    {
        if ((authorKey == null) || (authorKey.length() != GROUP_KEY_HEX_LENGTH))
        {
            return UNKNOWN_AUTHOR;
        }
        for (int i = 0; i < authorKey.length(); i++)
        {
            if (Character.digit(authorKey.charAt(i), 16) < 0)
            {
                return UNKNOWN_AUTHOR;
            }
        }
        return authorKey.substring(0, AUTHOR_KEY_CHARS).toUpperCase(Locale.ROOT);
    }

    /**
     * The key the mail names the author by (authorPrefix), the rule on every platform. A message that came live keeps
     * the key stored when it came. One that came through history sync ({@code cameThroughHistory},
     * GroupMessage.was_synced) carries the key the peer that relayed it claimed, signed by no one: it names its
     * author only when a signature proved this very message ({@code signatureVerified},
     * NgcSignedHistory.isAuthorVerified), else none (null), which the mail says as UNKNOWN_AUTHOR.
     */
    static String reportedAuthorKey(final String storedKey, final boolean cameThroughHistory,
                                    final boolean signatureVerified)
    {
        return (cameThroughHistory && !signatureVerified) ? null : storedKey;
    }

    /**
     * When the message was written, as the mail's Time line gives it, by the rule of iOS (OCTMessageAbstract.date). A
     * message that came live is timed by when it came ({@code receivedMs}, as its bubble shows it): a message of a
     * group carries no time of its own. One that came through history sync ({@code cameThroughHistory}) came when
     * the sync ran, so it is timed by the time its record gave ({@code sentMs}), never later than it came, as a clock
     * ahead of ours would have it. Either one when the other is not known (0). The desktop times a synced file by its
     * record too, and a live entry by when it came.
     */
    static long reportedTimeMs(final long sentMs, final long receivedMs, final boolean cameThroughHistory)
    {
        if (cameThroughHistory && (sentMs > 0))
        {
            return (receivedMs > 0) ? Math.min(sentMs, receivedMs) : sentMs;
        }
        return (receivedMs > 0) ? receivedMs : sentMs;
    }

    /** ISO 8601 in UTC, to the second: 2026-11-16T05:54:57Z; empty for a time not known (0), as the desktop has it. */
    static String isoUtc(final long timeMs)
    {
        if (timeMs <= 0)
        {
            return "";
        }
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timeMs));
    }

    /**
     * The first {@code limit} characters of text as a person counts them, as Swift's String counts them on iOS: each
     * an extended grapheme cluster, so a flag, emoji joined by ZWJ, an emoji with its skin tone, a letter with its
     * combining marks or a pair of surrogates is one character, never cut in half. On the phone java.text's
     * BreakIterator is ICU's, which follows UAX #29. The ICU of API 26 to 28 (58 and 60) is older than most emoji
     * joined by ZWJ and splits them after the ZWJ, and a JVM before JDK 20 splits flags and emoji too: each character
     * it gives goes on to the end of what it split off (wholeClusterEnd), so they count and cut as on iOS.
     */
    static String firstCharacters(final String text, final int limit)
    {
        if (text == null)
        {
            return "";
        }
        final BreakIterator clusters = BreakIterator.getCharacterInstance(Locale.ROOT);
        clusters.setText(text);
        int end = 0;
        for (int characters = 0; (characters < limit) && (end < text.length()); characters++)
        {
            final int next = clusters.following(end);
            end = (next == BreakIterator.DONE) ? text.length() : wholeClusterEnd(text, next);
        }
        return text.substring(0, end);
    }

    /**
     * Where the character a BreakIterator ended at {@code cut} really ends: the cut moves on while it falls right
     * after a ZWJ, right before a ZWJ, a variation selector, a mark that extends the character before it (a combining
     * mark, the tag of a flag such as England's, UAX #29 Extend) or a skin tone, between the two halves of a flag
     * (regional indicators, paired from the start of their run), or inside a pair of surrogates.
     */
    static int wholeClusterEnd(final String text, final int cut)
    {
        int end = cut;
        while ((end > 0) && (end < text.length()))
        {
            final char before = text.charAt(end - 1);
            final int after = text.codePointAt(end);
            final boolean halfOfASurrogatePair = Character.isHighSurrogate(before) && Character.isLowSurrogate(
                    text.charAt(end));
            final boolean halfOfAFlag = isRegionalIndicator(after) && (regionalIndicatorsBefore(text, end) % 2 == 1);
            if ((before != ZWJ) && !extendsThePrevious(after) && !halfOfASurrogatePair && !halfOfAFlag)
            {
                break;
            }
            // A lone low surrogate is one char, as codePointAt gives it.
            end += Character.charCount(after);
        }
        return end;
    }

    /** Whether a character never begins a character of its own but goes with the one before it (UAX #29 Extend). */
    private static boolean extendsThePrevious(final int codePoint)
    {
        if ((codePoint == ZWJ) || (codePoint == ZWNJ) || (codePoint == EMOJI_PRESENTATION)
            || ((codePoint >= SKIN_TONE_FIRST) && (codePoint <= SKIN_TONE_LAST))
            || ((codePoint >= TAG_FIRST) && (codePoint <= TAG_LAST)))
        {
            return true;
        }
        final int type = Character.getType(codePoint);
        return (type == Character.NON_SPACING_MARK) || (type == Character.ENCLOSING_MARK);
    }

    private static boolean isRegionalIndicator(final int codePoint)
    {
        return (codePoint >= REGIONAL_INDICATOR_FIRST) && (codePoint <= REGIONAL_INDICATOR_LAST);
    }

    /** How many regional indicators stand right before {@code end}, in a run. */
    private static int regionalIndicatorsBefore(final String text, final int end)
    {
        int count = 0;
        int i = end;
        while (i > 0)
        {
            final int codePoint = text.codePointBefore(i);
            if (!isRegionalIndicator(codePoint))
            {
                break;
            }
            count++;
            i -= Character.charCount(codePoint);
        }
        return count;
    }

    /** Lines of a mail end with CRLF (RFC 6068), whatever ended them before. */
    static String mailLines(final String text)
    {
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
    }

    /**
     * The text trimmed, each run of white space inside it one space, as QString::simplified gives the name on the
     * desktop: a name over several lines stays on the subject's one line.
     */
    static String simplified(final String text)
    {
        if (text == null)
        {
            return "";
        }
        final StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (isSpace(c))
            {
                space = out.length() > 0;
                continue;
            }
            if (space)
            {
                out.append(' ');
                space = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** White space as QChar::isSpace reads it: the separators, tab to carriage return, and NEL. */
    private static boolean isSpace(final char c)
    {
        return ((c >= '\t') && (c <= '\r')) || (c == '\u0085') || Character.isSpaceChar(c);
    }

    /**
     * Every byte of the UTF-8 of text as %XX, but for the unreserved characters of RFC 3986, as
     * QUrl::toPercentEncoding gives them on the desktop. A surrogate without its pair is U+FFFD.
     */
    static String percentEncoded(final String text)
    {
        final StringBuilder out = new StringBuilder(text.length() * 3);
        int i = 0;
        while (i < text.length())
        {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if ((codePoint >= Character.MIN_SURROGATE) && (codePoint <= Character.MAX_SURROGATE))
            {
                codePoint = 0xFFFD;
            }
            if (codePoint < 0x80)
            {
                appendByte(out, codePoint);
            }
            else if (codePoint < 0x800)
            {
                appendByte(out, 0xC0 | (codePoint >> 6));
                appendByte(out, 0x80 | (codePoint & 0x3F));
            }
            else if (codePoint < 0x10000)
            {
                appendByte(out, 0xE0 | (codePoint >> 12));
                appendByte(out, 0x80 | ((codePoint >> 6) & 0x3F));
                appendByte(out, 0x80 | (codePoint & 0x3F));
            }
            else
            {
                appendByte(out, 0xF0 | (codePoint >> 18));
                appendByte(out, 0x80 | ((codePoint >> 12) & 0x3F));
                appendByte(out, 0x80 | ((codePoint >> 6) & 0x3F));
                appendByte(out, 0x80 | (codePoint & 0x3F));
            }
        }
        return out.toString();
    }

    private static void appendByte(final StringBuilder out, final int value)
    {
        final boolean unreserved = ((value >= 'A') && (value <= 'Z')) || ((value >= 'a') && (value <= 'z'))
                                   || ((value >= '0') && (value <= '9')) || (value == '-') || (value == '.')
                                   || (value == '_') || (value == '~');
        if (unreserved)
        {
            out.append((char) value);
        }
        else
        {
            out.append('%').append(HEX[(value >> 4) & 0xF]).append(HEX[value & 0xF]);
        }
    }
}

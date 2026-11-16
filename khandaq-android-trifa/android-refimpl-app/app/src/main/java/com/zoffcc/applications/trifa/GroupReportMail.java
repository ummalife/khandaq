package com.zoffcc.applications.trifa;

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
 * first 8 characters of the author's key in the group in upper case ("unknown" when the message has none stored),
 * the time in UTC (ISO 8601) and the text, up to 500 characters, its lines ended with CRLF. The words of the mail are support's, in English: the person reads
 * them before sending. Free of Android, so that the unit tests hold it as the phone builds it.
 */
final class GroupReportMail
{
    static final String ADDRESS = "support@khandaq.org";
    /** The text of the message goes up to this many characters; none is cut in half. */
    static final int TEXT_LIMIT = 500;
    /** The author is named by the first characters of their key in the group. */
    static final int AUTHOR_KEY_CHARS = 8;
    /** The author of a message that has no key of its author stored: named so, never guessed. */
    static final String UNKNOWN_AUTHOR = "unknown";

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

    /** The first {@code limit} characters of text: a pair of surrogates is one character, never cut in half. */
    static String firstCharacters(final String text, final int limit)
    {
        if (text == null)
        {
            return "";
        }
        int units = 0;
        for (int characters = 0; (characters < limit) && (units < text.length()); characters++)
        {
            final boolean pair = Character.isHighSurrogate(text.charAt(units)) && (units + 1 < text.length())
                                 && Character.isLowSurrogate(text.charAt(units + 1));
            units += pair ? 2 : 1;
        }
        return text.substring(0, units);
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

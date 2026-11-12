package com.zoffcc.applications.trifa;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The conversation with one person (design/redesign/02-plan.md, screens 5, 6, 7, 10 and 16) as plain
 * values: the same rules as AirConversationModel.swift on iOS, case for case. Nothing here touches the
 * database or a view, so the rules (which state a message is in, where a run of one author starts, what
 * the header, the hint under the field and the day strip say) can be tested on their own. The chat
 * screen turns the values into words with its string resources.
 */
final class AirConversationModel
{
    /** Two messages of one author further apart than this start a new run, so the name shows again. */
    static final long RUN_GAP_SECONDS = 300L;
    /** How long the day strip and the best hours remember an hour: the last seven days. */
    static final long KEEP_SECONDS = 7L * 86400L;

    private AirConversationModel()
    {
    }

    // ---- the state of a message ----

    /** The state of one of our own messages, as the transcript and the tray draw it. */
    enum EntryStatus
    {
        /** Incoming messages and notes to yourself: no mark at all. */
        NONE,
        /** Waits on this device until the person is online (an amber clock, in the tray). */
        TRANSIT,
        /** The person is online and the message is leaving right now (a faint clock). */
        SENDING,
        /** Left this device, no receipt yet (one check). */
        SENT,
        /** The person's device confirmed it (two checks). */
        DELIVERED,
        /** The network gave up on it; "Retry" sends the same text again (red). */
        FAILED;

        /**
         * An outgoing text: {@code messageId} is -1 until toxcore has taken the message, {@code isDelivered}
         * turns true with the receipt. Undelivered while the person is away means it waits for them: the
         * app sends every undelivered text again when they come online. On Android a resend toxcore
         * refused stores its error code, below -1, so any negative id means "not taken yet".
         */
        static EntryStatus ofOutgoingText(final boolean isSaved, final boolean isDelivered, final long messageId,
                                          final boolean personOnline)
        {
            if (isSaved)
            {
                return NONE;
            }
            if (isDelivered)
            {
                return DELIVERED;
            }
            if (!personOnline)
            {
                return TRANSIT;
            }
            return messageId < 0L ? SENDING : SENT;
        }

        /**
         * A text row of the Android history. It also knows that the delivery watchdog gave up on a
         * message (sent, never confirmed, retries spent), which iOS learns from a refused send instead.
         * A copy held by a relay is not delivered: the person has not received it yet.
         */
        static EntryStatus ofDirectText(final boolean isSaved, final boolean isDelivered, final boolean gaveUp,
                                        final long messageId, final boolean personOnline)
        {
            if (isSaved)
            {
                return NONE;
            }
            if (isDelivered)
            {
                return DELIVERED;
            }
            if (gaveUp)
            {
                return FAILED;
            }
            return ofOutgoingText(false, false, messageId, personOnline);
        }

        /** The icon from the design-system sprite, null for no mark. */
        String iconName()
        {
            switch (this)
            {
                case TRANSIT:
                case SENDING:
                    return "clock";
                case SENT:
                    return "check";
                case DELIVERED:
                    return "check-double";
                case FAILED:
                    return "alert";
                case NONE:
                default:
                    return null;
            }
        }
    }

    // ---- runs of one author ----

    /** What the transcript needs to know about a neighbouring entry to decide where a run starts. */
    static final class RunKey
    {
        /** The author: an empty string for ourselves, the friend's key otherwise. */
        final String author;
        final long dateMs;
        /** Text entries share runs; a call or a file stands on its own row and breaks the run after it. */
        final boolean isText;

        RunKey(final String author, final long dateMs, final boolean isText)
        {
            this.author = author == null ? "" : author;
            this.dateMs = dateMs;
            this.isText = isText;
        }
    }

    /**
     * An entry opens a new run (and shows its author) unless the entry before it in reading order is a
     * text by the same author on the same day, written less than five minutes earlier.
     */
    static boolean startsRun(final RunKey entry, final RunKey previous, final TimeZone zone)
    {
        if (previous == null)
        {
            return true;
        }
        if (!previous.isText || !previous.author.equals(entry.author))
        {
            return true;
        }
        if (!sameDay(previous.dateMs, entry.dateMs, zone))
        {
            return true;
        }
        return entry.dateMs - previous.dateMs > RUN_GAP_SECONDS * 1000L;
    }

    /** The first word of a name: the author label and the hints say "Amina", not "Amina Safina". */
    static String shortName(final String name)
    {
        if (name == null)
        {
            return "";
        }
        final String trimmed = trimSpace(name);
        int end = 0;
        while (end < trimmed.length())
        {
            final int codePoint = trimmed.codePointAt(end);
            if (isSpace(codePoint))
            {
                break;
            }
            end += Character.charCount(codePoint);
        }
        final String first = trimmed.substring(0, end);
        return first.isEmpty() ? trimmed : first;
    }

    // ---- day marks ----

    /** Which words the day mark above the first entry of a day uses. */
    enum DayKind
    {
        TODAY,
        YESTERDAY,
        /** The date without the year: "11 November". */
        SAME_YEAR,
        /** The date with the year. */
        OTHER_YEAR;

        /** The skeleton for DateFormat.getBestDateTimePattern, as iOS sets its formatter template. */
        String dateTemplate()
        {
            return this == OTHER_YEAR ? "dMMMMy" : "dMMMM";
        }
    }

    static DayKind dayKind(final long dateMs, final long nowMs, final TimeZone zone)
    {
        if (sameDay(dateMs, nowMs, zone))
        {
            return DayKind.TODAY;
        }
        final Calendar yesterday = calendar(zone, nowMs);
        yesterday.add(Calendar.DAY_OF_MONTH, -1);
        if (sameDay(dateMs, yesterday.getTimeInMillis(), zone))
        {
            return DayKind.YESTERDAY;
        }
        final int year = calendar(zone, dateMs).get(Calendar.YEAR);
        return year == calendar(zone, nowMs).get(Calendar.YEAR) ? DayKind.SAME_YEAR : DayKind.OTHER_YEAR;
    }

    static boolean sameDay(final long firstMs, final long secondMs, final TimeZone zone)
    {
        final Calendar first = calendar(zone, firstMs);
        final Calendar second = calendar(zone, secondMs);
        return first.get(Calendar.ERA) == second.get(Calendar.ERA)
               && first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
               && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    // ---- the link and the presence line ----

    /** How the link to the person looks right now: the mark beside the presence line, the dossier's path. */
    enum LinkState
    {
        DIRECT,
        RELAY,
        NONE;

        static LinkState of(final AirModel.Reach reach)
        {
            if (reach == null)
            {
                return NONE;
            }
            switch (reach.kind)
            {
                case DIRECT:
                    return DIRECT;
                case RELAY:
                    return RELAY;
                default:
                    return NONE;
            }
        }
    }

    /**
     * The quiet line under the name: "online since 9:40, direct", "offline for 25 minutes", "typing".
     * {@code sinceMs} is when this device saw the person come online; without it the time is left out.
     */
    static final class PresenceLine
    {
        enum Kind
        {
            TYPING,
            DIRECT,
            DIRECT_SINCE,
            RELAY,
            RELAY_SINCE,
            PENDING,
            CONNECTING,
            AWAY,
            /** Groups and notes to yourself: no line. */
            EMPTY
        }

        final Kind kind;
        final long sinceMs;
        /** For {@link Kind#AWAY}: the unit and the number, at the thresholds of the list rows. */
        final AirModel.AwayWords away;

        private PresenceLine(final Kind kind, final long sinceMs, final AirModel.AwayWords away)
        {
            this.kind = kind;
            this.sinceMs = sinceMs;
            this.away = away;
        }

        static PresenceLine of(final AirModel.Reach reach, final Long onlineSinceMs, final boolean isTyping)
        {
            if (reach == null)
            {
                return new PresenceLine(Kind.EMPTY, 0L, null);
            }
            if (isTyping && reach.isLive())
            {
                return new PresenceLine(Kind.TYPING, 0L, null);
            }
            switch (reach.kind)
            {
                case DIRECT:
                    return onlineSinceMs != null ? new PresenceLine(Kind.DIRECT_SINCE, onlineSinceMs, null)
                                                 : new PresenceLine(Kind.DIRECT, 0L, null);
                case RELAY:
                    return onlineSinceMs != null ? new PresenceLine(Kind.RELAY_SINCE, onlineSinceMs, null)
                                                 : new PresenceLine(Kind.RELAY, 0L, null);
                case PENDING:
                    return new PresenceLine(Kind.PENDING, 0L, null);
                case CONNECTING:
                    return new PresenceLine(Kind.CONNECTING, 0L, null);
                case AWAY:
                    return new PresenceLine(Kind.AWAY, 0L, AirModel.AwayWords.of(reach.secondsAgo));
                case GROUP:
                case LOCAL:
                default:
                    return new PresenceLine(Kind.EMPTY, 0L, null);
            }
        }
    }

    // ---- the line under the field ----

    /** The line under the message field: what happens to a message sent now. */
    static final class ComposeHint
    {
        enum Kind
        {
            /** "The note stays on this device only." */
            SAVED,
            /** "Goes out now: Amina is online." */
            LIVE,
            /** "Waits in transit: Yusuf is offline. It will go on its own". */
            WAIT,
            /** The contact was removed: no line. */
            EMPTY
        }

        enum Dot
        {
            LIVE,
            TRANSIT,
            NONE
        }

        final Kind kind;
        final Dot dot;

        private ComposeHint(final Kind kind, final Dot dot)
        {
            this.kind = kind;
            this.dot = dot;
        }

        /** {@code contactExists} is false once the contact was removed. */
        static ComposeHint make(final boolean isSaved, final boolean contactExists, final AirModel.Reach reach)
        {
            if (isSaved)
            {
                return new ComposeHint(Kind.SAVED, Dot.NONE);
            }
            if (!contactExists)
            {
                return new ComposeHint(Kind.EMPTY, Dot.NONE);
            }
            if (reach != null && reach.isLive())
            {
                return new ComposeHint(Kind.LIVE, Dot.LIVE);
            }
            return new ComposeHint(Kind.WAIT, Dot.TRANSIT);
        }

        @Override
        public boolean equals(final Object other)
        {
            if (!(other instanceof ComposeHint))
            {
                return false;
            }
            final ComposeHint that = (ComposeHint) other;
            return kind == that.kind && dot == that.dot;
        }

        @Override
        public int hashCode()
        {
            return kind.hashCode() * 31 + dot.hashCode();
        }
    }

    /**
     * The length counter under the field. Tox carries at most 1372 bytes in one message and toxcore keeps
     * 38 of them for the message id and time of msgV3, so one message holds 1334 bytes of text; the counter
     * shows the room left before that, once only a tenth of it remains.
     */
    static final int COMPOSE_LIMIT_BYTES = 1334;

    /** Null while there is plenty of room; otherwise the bytes left, negative once over the limit. */
    static Integer composeRemaining(final int byteCount)
    {
        return composeRemaining(byteCount, COMPOSE_LIMIT_BYTES);
    }

    /**
     * The same rule against another limit. Android only: a correction of a sent message holds at most
     * HelperMessageEdit.MAX_EDIT_TEXT_BYTES and is refused above it, where a new message is cut.
     */
    static Integer composeRemaining(final int byteCount, final int limitBytes)
    {
        final int left = limitBytes - byteCount;
        return (double) left < (double) limitBytes * 0.1 ? Integer.valueOf(left) : null;
    }

    // ---- the tray ----

    /**
     * The messages of {@code before} that are no longer in the tray, in the order they waited: they leave
     * one by one in that order, as on iOS. Cancelled ones are among them; the caller keeps only those
     * that went out.
     */
    static List<Long> leftTray(final List<Long> before, final Set<Long> now)
    {
        final List<Long> left = new ArrayList<>();
        if (before == null)
        {
            return left;
        }
        for (final Long id : before)
        {
            if (id != null && (now == null || !now.contains(id)))
            {
                left.add(id);
            }
        }
        return left;
    }

    // ---- digits ----

    /**
     * The text with every decimal digit written from {@code zeroDigit}, the zero of the language's own
     * number formatter: the "24" of a translation, the ":00" after an hour and a clock from the old time
     * formatter, which keeps the language the app started in, then read like the numbers the language
     * formats itself (Arabic-Indic in Arabic, where "%d:00" alone gives one hour in two scripts; 0 to 9
     * again after a switch from Arabic). Only for words of the app, never for what people wrote.
     */
    static String localDigits(final String text, final char zeroDigit)
    {
        if (text == null)
        {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            final int value = Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER ? Character.digit(c, 10) : -1;
            final char wanted = value >= 0 ? (char) (zeroDigit + value) : c;
            if (out == null && wanted != c)
            {
                out = new StringBuilder(text.length()).append(text, 0, i);
            }
            if (out != null)
            {
                out.append(wanted);
            }
        }
        return out == null ? text : out.toString();
    }

    // ---- the key ----

    /** A key in groups of four, so two people can read it to each other: "7F3A 91C0 5D2E". */
    static List<String> keyGroups(final String key, final int size)
    {
        if (key == null || size <= 0)
        {
            return Collections.emptyList();
        }
        final List<String> characters = new ArrayList<>();
        final String upper = key.toUpperCase(Locale.ROOT);
        int index = 0;
        while (index < upper.length())
        {
            final int codePoint = upper.codePointAt(index);
            final int next = index + Character.charCount(codePoint);
            if (!isSpace(codePoint))
            {
                characters.add(upper.substring(index, next));
            }
            index = next;
        }
        final List<String> groups = new ArrayList<>();
        for (int start = 0; start < characters.size(); start += size)
        {
            final StringBuilder group = new StringBuilder();
            for (int i = start; i < Math.min(start + size, characters.size()); i++)
            {
                group.append(characters.get(i));
            }
            groups.add(group.toString());
        }
        return groups;
    }

    static List<String> keyGroups(final String key)
    {
        return keyGroups(key, 4);
    }

    /** The groups in lines of {@code perLine}, joined by spaces and line breaks. */
    static String keyLines(final String key, final int perLine)
    {
        final List<String> all = keyGroups(key);
        if (perLine <= 0)
        {
            return join(all, 0, all.size(), " ");
        }
        final StringBuilder lines = new StringBuilder();
        for (int start = 0; start < all.size(); start += perLine)
        {
            if (start > 0)
            {
                lines.append('\n');
            }
            lines.append(join(all, start, Math.min(start + perLine, all.size()), " "));
        }
        return lines.toString();
    }

    // ---- the day strip ----

    /**
     * The 24 cells of the day strip. Each cell is a clock hour; it shows the most recent time that hour
     * came round, so the hours after now belong to yesterday and the strip always covers the last day.
     */
    static final class DayStrip
    {
        /** Index 0 is the hour from midnight to one o'clock. */
        final boolean[] online;
        /** The current clock hour, outlined on the strip. */
        final int nowHour;

        private DayStrip(final boolean[] online, final int nowHour)
        {
            this.online = online;
            this.nowHour = nowHour;
        }

        boolean hasMarks()
        {
            for (boolean on : online)
            {
                if (on)
                {
                    return true;
                }
            }
            return false;
        }

        /** {@code hours} holds the start of every hour the person was seen online, in seconds since 1970. */
        static DayStrip make(final Set<Long> hours, final long nowMs, final TimeZone zone)
        {
            final Calendar now = calendar(zone, nowMs);
            final int nowHour = now.get(Calendar.HOUR_OF_DAY);
            final boolean[] online = new boolean[24];
            for (int hour = 0; hour < 24; hour++)
            {
                final Calendar start = calendar(zone, nowMs);
                if (hour > nowHour)
                {
                    start.add(Calendar.DAY_OF_MONTH, -1);
                }
                start.set(Calendar.HOUR_OF_DAY, hour);
                start.set(Calendar.MINUTE, 0);
                start.set(Calendar.SECOND, 0);
                start.set(Calendar.MILLISECOND, 0);
                online[hour] = hours != null && hours.contains(start.getTimeInMillis() / 1000L);
            }
            return new DayStrip(online, nowHour);
        }

        /** Runs of online hours as {from, to} on the clock: 7 to 9 is the hours 7 and 8. */
        List<int[]> ranges()
        {
            final List<int[]> result = new ArrayList<>();
            int start = -1;
            for (int hour = 0; hour <= 24; hour++)
            {
                final boolean isOn = hour < 24 && online[hour];
                if (isOn && start < 0)
                {
                    start = hour;
                }
                else if (!isOn && start >= 0)
                {
                    result.add(new int[]{start, hour});
                    start = -1;
                }
            }
            return result;
        }
    }

    /**
     * When the person is usually online, from the last week of hourly marks: the longest run of clock
     * hours seen on the most days, as {from, to}. Needs marks on at least two different days, or it would
     * only repeat the strip; null when there is no such time.
     */
    static int[] bestHours(final Set<Long> hours, final long nowMs, final TimeZone zone)
    {
        if (hours == null || hours.isEmpty())
        {
            return null;
        }
        final double weekAgo = nowMs / 1000.0 - KEEP_SECONDS;
        final List<Set<Long>> daysPerHour = new ArrayList<>(24);
        for (int i = 0; i < 24; i++)
        {
            daysPerHour.add(new HashSet<Long>());
        }
        final Set<Long> allDays = new HashSet<>();
        for (Long start : hours)
        {
            if (start == null || (double) start < weekAgo)
            {
                continue;
            }
            final Calendar date = calendar(zone, start * 1000L);
            final int hour = date.get(Calendar.HOUR_OF_DAY);
            final long day = startOfDayMs(zone, start * 1000L) / 1000L;
            daysPerHour.get(hour).add(day);
            allDays.add(day);
        }
        if (allDays.size() < 2)
        {
            return null;
        }
        final int[] counts = new int[24];
        int top = 0;
        for (int hour = 0; hour < 24; hour++)
        {
            counts[hour] = daysPerHour.get(hour).size();
            top = Math.max(top, counts[hour]);
        }
        if (top < 2)
        {
            return null;
        }
        int[] best = null;
        int bestLength = 0;
        int start = -1;
        for (int hour = 0; hour <= 24; hour++)
        {
            final boolean isTop = hour < 24 && counts[hour] == top;
            if (isTop && start < 0)
            {
                start = hour;
            }
            else if (!isTop && start >= 0)
            {
                if (hour - start > bestLength)
                {
                    best = new int[]{start, hour};
                    bestLength = hour - start;
                }
                start = -1;
            }
        }
        // A person online almost all day has no "best time"; the strip says enough.
        if (best == null || bestLength >= 20)
        {
            return null;
        }
        return best;
    }

    /** What the words beside the day strip say, in the order iOS tries them (dayStripHint). */
    enum DayHint
    {
        /** "usually 7:00 to 9:00". */
        BEST,
        /** "past day: 7:00 to 9:00, 21:00 to 22:00". */
        RANGES,
        /** "no marks yet". */
        EMPTY;

        static DayHint of(final int[] best, final DayStrip strip)
        {
            if (best != null)
            {
                return BEST;
            }
            return strip != null && strip.hasMarks() ? RANGES : EMPTY;
        }
    }

    /** The start of the clock hour holding {@code ms}, in seconds since 1970: how the day strip files it. */
    static long hourStartSeconds(final long ms, final TimeZone zone)
    {
        final Calendar start = calendar(zone, ms);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        return start.getTimeInMillis() / 1000L;
    }

    // ---- helpers ----

    private static Calendar calendar(final TimeZone zone, final long ms)
    {
        final Calendar calendar = Calendar.getInstance(zone == null ? TimeZone.getDefault() : zone, Locale.ROOT);
        calendar.setTimeInMillis(ms);
        return calendar;
    }

    private static long startOfDayMs(final TimeZone zone, final long ms)
    {
        final Calendar start = calendar(zone, ms);
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        return start.getTimeInMillis();
    }

    /** Unicode white space, as Swift's whitespacesAndNewlines: the Z categories, tab to carriage return, NEL. */
    static boolean isSpace(final int codePoint)
    {
        return Character.isSpaceChar(codePoint) || (codePoint >= 0x09 && codePoint <= 0x0D) || codePoint == 0x85;
    }

    private static String trimSpace(final String text)
    {
        int start = 0;
        int end = text.length();
        while (start < end)
        {
            final int codePoint = text.codePointAt(start);
            if (!isSpace(codePoint))
            {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start)
        {
            final int codePoint = text.codePointBefore(end);
            if (!isSpace(codePoint))
            {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return text.substring(start, end);
    }

    private static String join(final List<String> parts, final int from, final int to, final String separator)
    {
        final StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++)
        {
            if (i > from)
            {
                out.append(separator);
            }
            out.append(parts.get(i));
        }
        return out.toString();
    }
}

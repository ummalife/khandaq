package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import static com.zoffcc.applications.trifa.AirConversationModel.ComposeHint;
import static com.zoffcc.applications.trifa.AirConversationModel.DayHint;
import static com.zoffcc.applications.trifa.AirConversationModel.DayKind;
import static com.zoffcc.applications.trifa.AirConversationModel.DayStrip;
import static com.zoffcc.applications.trifa.AirConversationModel.EntryStatus;
import static com.zoffcc.applications.trifa.AirConversationModel.LinkState;
import static com.zoffcc.applications.trifa.AirConversationModel.PresenceLine;
import static com.zoffcc.applications.trifa.AirConversationModel.RunKey;
import static com.zoffcc.applications.trifa.AirModel.Reach;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The rules of AirConversationModel.swift, case for case: the same inputs give the same answers on
 * both clients. Times are built in a fixed zone so the tests do not depend on the machine.
 */
public class AirConversationModelTest
{
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final TimeZone KOLKATA = TimeZone.getTimeZone("Asia/Kolkata");

    private static long at(final TimeZone zone, final int year, final int month, final int day, final int hour,
                           final int minute, final int second)
    {
        final Calendar calendar = Calendar.getInstance(zone, Locale.ROOT);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, second);
        return calendar.getTimeInMillis();
    }

    private static long utc(final int year, final int month, final int day, final int hour, final int minute)
    {
        return at(UTC, year, month, day, hour, minute, 0);
    }

    private static long hourStart(final int year, final int month, final int day, final int hour)
    {
        return utc(year, month, day, hour, 0) / 1000L;
    }

    // ---- entry status ----

    @Test
    public void outgoing_text_status_follows_the_ios_order()
    {
        // Notes to yourself never carry a mark, whatever the rest says.
        assertEquals(EntryStatus.NONE, EntryStatus.ofOutgoingText(true, true, 5, true));
        assertEquals(EntryStatus.NONE, EntryStatus.ofOutgoingText(true, false, -1, false));
        // The receipt wins over the person's state.
        assertEquals(EntryStatus.DELIVERED, EntryStatus.ofOutgoingText(false, true, 5, false));
        assertEquals(EntryStatus.DELIVERED, EntryStatus.ofOutgoingText(false, true, -1, true));
        // Undelivered while the person is away: it waits for them, taken by toxcore or not.
        assertEquals(EntryStatus.TRANSIT, EntryStatus.ofOutgoingText(false, false, -1, false));
        assertEquals(EntryStatus.TRANSIT, EntryStatus.ofOutgoingText(false, false, 7, false));
        // The person is online: leaving now, or left.
        assertEquals(EntryStatus.SENDING, EntryStatus.ofOutgoingText(false, false, -1, true));
        // A resend toxcore refused keeps its error code as the id: still not taken, so still leaving.
        assertEquals(EntryStatus.SENDING, EntryStatus.ofOutgoingText(false, false, -3, true));
        assertEquals(EntryStatus.SENT, EntryStatus.ofOutgoingText(false, false, 7, true));
        assertEquals(EntryStatus.SENT, EntryStatus.ofOutgoingText(false, false, 0, true));
    }

    @Test
    public void a_message_the_watchdog_gave_up_on_is_failed_until_its_receipt()
    {
        assertEquals(EntryStatus.FAILED, EntryStatus.ofDirectText(false, false, true, 7, true));
        assertEquals(EntryStatus.FAILED, EntryStatus.ofDirectText(false, false, true, 7, false));
        assertEquals(EntryStatus.DELIVERED, EntryStatus.ofDirectText(false, true, true, 7, true));
        assertEquals(EntryStatus.NONE, EntryStatus.ofDirectText(true, false, true, 7, true));
        // Otherwise the iOS rule.
        assertEquals(EntryStatus.SENT, EntryStatus.ofDirectText(false, false, false, 7, true));
        assertEquals(EntryStatus.TRANSIT, EntryStatus.ofDirectText(false, false, false, -1, false));
        assertEquals(EntryStatus.SENDING, EntryStatus.ofDirectText(false, false, false, -1, true));
    }

    @Test
    public void each_status_has_its_own_shape()
    {
        assertNull(EntryStatus.NONE.iconName());
        assertEquals("clock", EntryStatus.TRANSIT.iconName());
        assertEquals("clock", EntryStatus.SENDING.iconName());
        assertEquals("check", EntryStatus.SENT.iconName());
        assertEquals("check-double", EntryStatus.DELIVERED.iconName());
        assertEquals("alert", EntryStatus.FAILED.iconName());
    }

    // ---- runs ----

    @Test
    public void a_run_breaks_on_author_kind_day_and_a_gap_over_five_minutes()
    {
        final long noon = utc(2026, 11, 11, 12, 0);
        final RunKey first = new RunKey("A", noon, true);

        assertTrue(AirConversationModel.startsRun(first, null, UTC));
        assertTrue(AirConversationModel.startsRun(new RunKey("A", noon + 1000L, true),
                                                  new RunKey("A", noon, false), UTC));
        assertTrue(AirConversationModel.startsRun(new RunKey("", noon + 1000L, true), first, UTC));
        assertTrue(AirConversationModel.startsRun(new RunKey("B", noon + 1000L, true), first, UTC));

        assertFalse(AirConversationModel.startsRun(new RunKey("A", noon + 299_000L, true), first, UTC));
        assertFalse(AirConversationModel.startsRun(new RunKey("A", noon + 300_000L, true), first, UTC));
        assertTrue(AirConversationModel.startsRun(new RunKey("A", noon + 301_000L, true), first, UTC));
        // A file right after a text still joins the run; only the entry after the file starts a new one.
        assertFalse(AirConversationModel.startsRun(new RunKey("A", noon + 1000L, false), first, UTC));

        final RunKey lateEvening = new RunKey("A", utc(2026, 11, 10, 23, 59), true);
        assertTrue(AirConversationModel.startsRun(new RunKey("A", utc(2026, 11, 11, 0, 1), true), lateEvening, UTC));
        assertEquals("", new RunKey(null, noon, true).author);
    }

    @Test
    public void short_name_is_the_first_word()
    {
        assertEquals("Amina", AirConversationModel.shortName("  Amina Safina "));
        assertEquals("Amina", AirConversationModel.shortName("Amina"));
        assertEquals("Yusuf", AirConversationModel.shortName(" Yusuf Ali"));
        assertEquals("Omar", AirConversationModel.shortName("Omar\nIbn"));
        assertEquals("", AirConversationModel.shortName("   "));
        assertEquals("", AirConversationModel.shortName(""));
        assertEquals("", AirConversationModel.shortName(null));
        assertEquals("بلال", AirConversationModel.shortName("بلال الحبشي"));
    }

    // ---- day marks ----

    @Test
    public void day_mark_says_today_yesterday_or_the_date_with_the_year_when_it_differs()
    {
        final long now = utc(2026, 11, 11, 12, 0);
        assertEquals(DayKind.TODAY, AirConversationModel.dayKind(utc(2026, 11, 11, 0, 0), now, UTC));
        assertEquals(DayKind.TODAY, AirConversationModel.dayKind(utc(2026, 11, 11, 23, 59), now, UTC));
        assertEquals(DayKind.YESTERDAY, AirConversationModel.dayKind(utc(2026, 11, 10, 23, 59), now, UTC));
        assertEquals(DayKind.YESTERDAY, AirConversationModel.dayKind(utc(2026, 11, 10, 0, 0), now, UTC));
        assertEquals(DayKind.SAME_YEAR, AirConversationModel.dayKind(utc(2026, 11, 9, 23, 59), now, UTC));
        assertEquals(DayKind.SAME_YEAR, AirConversationModel.dayKind(utc(2026, 1, 1, 0, 0), now, UTC));
        assertEquals(DayKind.OTHER_YEAR, AirConversationModel.dayKind(utc(2025, 12, 31, 23, 0), now, UTC));
        // Yesterday across a new year.
        assertEquals(DayKind.YESTERDAY, AirConversationModel.dayKind(utc(2025, 12, 31, 10, 0),
                                                                    utc(2026, 1, 1, 9, 0), UTC));
        assertEquals("dMMMM", DayKind.SAME_YEAR.dateTemplate());
        assertEquals("dMMMMy", DayKind.OTHER_YEAR.dateTemplate());
    }

    // ---- link and presence ----

    @Test
    public void link_state_from_reach()
    {
        assertEquals(LinkState.DIRECT, LinkState.of(Reach.DIRECT));
        assertEquals(LinkState.RELAY, LinkState.of(Reach.RELAY));
        assertEquals(LinkState.NONE, LinkState.of(Reach.away(30)));
        assertEquals(LinkState.NONE, LinkState.of(Reach.PENDING));
        assertEquals(LinkState.NONE, LinkState.of(Reach.CONNECTING));
        assertEquals(LinkState.NONE, LinkState.of(Reach.LOCAL));
        assertEquals(LinkState.NONE, LinkState.of(Reach.group(3)));
        assertEquals(LinkState.NONE, LinkState.of(null));
    }

    @Test
    public void presence_line_cases()
    {
        final long since = utc(2026, 11, 11, 9, 40);

        assertEquals(PresenceLine.Kind.TYPING, PresenceLine.of(Reach.DIRECT, since, true).kind);
        assertEquals(PresenceLine.Kind.TYPING, PresenceLine.of(Reach.RELAY, null, true).kind);
        // Typing from someone who is not linked is stale: the line says when they were seen.
        assertEquals(PresenceLine.Kind.AWAY, PresenceLine.of(Reach.away(1500), null, true).kind);

        final PresenceLine direct = PresenceLine.of(Reach.DIRECT, since, false);
        assertEquals(PresenceLine.Kind.DIRECT_SINCE, direct.kind);
        assertEquals(since, direct.sinceMs);
        assertEquals(PresenceLine.Kind.DIRECT, PresenceLine.of(Reach.DIRECT, null, false).kind);
        assertEquals(PresenceLine.Kind.RELAY_SINCE, PresenceLine.of(Reach.RELAY, since, false).kind);
        assertEquals(PresenceLine.Kind.RELAY, PresenceLine.of(Reach.RELAY, null, false).kind);
        assertEquals(PresenceLine.Kind.PENDING, PresenceLine.of(Reach.PENDING, since, false).kind);
        assertEquals(PresenceLine.Kind.CONNECTING, PresenceLine.of(Reach.CONNECTING, null, false).kind);
        assertEquals(PresenceLine.Kind.EMPTY, PresenceLine.of(Reach.group(4), null, false).kind);
        assertEquals(PresenceLine.Kind.EMPTY, PresenceLine.of(Reach.LOCAL, null, true).kind);
        assertEquals(PresenceLine.Kind.EMPTY, PresenceLine.of(null, null, false).kind);

        // awayText thresholds, as AirPresenceLine.awayText on iOS.
        assertAway(0, AirModel.AwayWords.Unit.JUST_NOW, 0);
        assertAway(59, AirModel.AwayWords.Unit.JUST_NOW, 0);
        assertAway(60, AirModel.AwayWords.Unit.MINUTES, 1);
        assertAway(1500, AirModel.AwayWords.Unit.MINUTES, 25);
        assertAway(3599, AirModel.AwayWords.Unit.MINUTES, 59);
        assertAway(3600, AirModel.AwayWords.Unit.HOURS, 1);
        assertAway(86399, AirModel.AwayWords.Unit.HOURS, 23);
        assertAway(86400, AirModel.AwayWords.Unit.DAYS, 1);
        assertAway(86400 * 7 - 1, AirModel.AwayWords.Unit.DAYS, 6);
        assertAway(86400 * 7, AirModel.AwayWords.Unit.LONG_AGO, 0);
        assertEquals(AirModel.AwayWords.Unit.LONG_AGO, PresenceLine.of(Reach.AWAY_LONG_AGO, null, false).away.unit);
    }

    private static void assertAway(final long seconds, final AirModel.AwayWords.Unit unit, final long amount)
    {
        final PresenceLine line = PresenceLine.of(Reach.away(seconds), null, false);
        assertEquals(PresenceLine.Kind.AWAY, line.kind);
        assertEquals(seconds + " s", unit, line.away.unit);
        assertEquals(seconds + " s", amount, line.away.amount);
    }

    // ---- the field ----

    @Test
    public void compose_hint_cases()
    {
        assertHint(ComposeHint.Kind.SAVED, ComposeHint.Dot.NONE, ComposeHint.make(true, true, Reach.LOCAL));
        assertHint(ComposeHint.Kind.SAVED, ComposeHint.Dot.NONE, ComposeHint.make(true, false, Reach.DIRECT));
        assertHint(ComposeHint.Kind.EMPTY, ComposeHint.Dot.NONE, ComposeHint.make(false, false, Reach.DIRECT));
        assertHint(ComposeHint.Kind.LIVE, ComposeHint.Dot.LIVE, ComposeHint.make(false, true, Reach.DIRECT));
        assertHint(ComposeHint.Kind.LIVE, ComposeHint.Dot.LIVE, ComposeHint.make(false, true, Reach.RELAY));
        assertHint(ComposeHint.Kind.WAIT, ComposeHint.Dot.TRANSIT, ComposeHint.make(false, true, Reach.away(90)));
        assertHint(ComposeHint.Kind.WAIT, ComposeHint.Dot.TRANSIT, ComposeHint.make(false, true, Reach.PENDING));
        assertHint(ComposeHint.Kind.WAIT, ComposeHint.Dot.TRANSIT, ComposeHint.make(false, true, Reach.CONNECTING));
        assertEquals(ComposeHint.make(false, true, Reach.DIRECT), ComposeHint.make(false, true, Reach.RELAY));
        assertFalse(ComposeHint.make(false, true, Reach.DIRECT).equals(ComposeHint.make(false, true, Reach.PENDING)));
    }

    private static void assertHint(final ComposeHint.Kind kind, final ComposeHint.Dot dot, final ComposeHint hint)
    {
        assertEquals(kind, hint.kind);
        assertEquals(dot, hint.dot);
    }

    @Test
    public void length_counter_appears_with_the_last_tenth_of_room()
    {
        assertEquals(1334, AirConversationModel.COMPOSE_LIMIT_BYTES);
        assertNull(AirConversationModel.composeRemaining(0));
        assertNull(AirConversationModel.composeRemaining(1200));
        assertEquals(Integer.valueOf(133), AirConversationModel.composeRemaining(1201));
        assertEquals(Integer.valueOf(1), AirConversationModel.composeRemaining(1333));
        assertEquals(Integer.valueOf(0), AirConversationModel.composeRemaining(1334));
        assertEquals(Integer.valueOf(-66), AirConversationModel.composeRemaining(1400));
    }

    @Test
    public void a_correction_counts_against_its_own_smaller_limit()
    {
        assertEquals(1300, HelperMessageEdit.MAX_EDIT_TEXT_BYTES);
        assertNull(AirConversationModel.composeRemaining(1170, 1300));
        assertEquals(Integer.valueOf(129), AirConversationModel.composeRemaining(1171, 1300));
        assertEquals(Integer.valueOf(0), AirConversationModel.composeRemaining(1300, 1300));
        assertEquals(Integer.valueOf(-34), AirConversationModel.composeRemaining(1334, 1300));
        // The one-argument form is the same rule against the message limit.
        assertEquals(AirConversationModel.composeRemaining(1201),
                     AirConversationModel.composeRemaining(1201, AirConversationModel.COMPOSE_LIMIT_BYTES));
    }

    // ---- the tray ----

    @Test
    public void messages_leave_the_tray_in_the_order_they_waited()
    {
        final List<Long> before = Arrays.asList(5L, 3L, 9L, 4L);
        assertEquals(Arrays.asList(5L, 9L), AirConversationModel.leftTray(before, set(3L, 4L)));
        assertEquals(before, AirConversationModel.leftTray(before, Collections.<Long>emptySet()));
        assertEquals(before, AirConversationModel.leftTray(before, null));
        assertEquals(Collections.<Long>emptyList(), AirConversationModel.leftTray(before, set(3L, 4L, 5L, 9L)));
        // A message that only joined the tray now has not left it.
        assertEquals(Arrays.asList(3L), AirConversationModel.leftTray(Arrays.asList(3L), set(7L)));
        assertEquals(Collections.<Long>emptyList(), AirConversationModel.leftTray(null, set(1L)));
        assertEquals(Collections.<Long>emptyList(),
                     AirConversationModel.leftTray(Arrays.asList((Long) null), Collections.<Long>emptySet()));
    }

    // ---- the key ----

    @Test
    public void key_in_groups_of_four()
    {
        assertEquals(Arrays.asList("7F3A", "91C0", "5D2E"), AirConversationModel.keyGroups("7f3a91c05d2e"));
        assertEquals(Arrays.asList("7F3A", "91"), AirConversationModel.keyGroups(" 7f3a\n9 1 "));
        assertEquals(Arrays.asList("ABC"), AirConversationModel.keyGroups("abc"));
        assertEquals(Collections.<String>emptyList(), AirConversationModel.keyGroups(""));
        assertEquals(Collections.<String>emptyList(), AirConversationModel.keyGroups("   "));
        assertEquals(Collections.<String>emptyList(), AirConversationModel.keyGroups(null));
        assertEquals(Collections.<String>emptyList(), AirConversationModel.keyGroups("abcd", 0));
        assertEquals(Arrays.asList("AB", "CD", "E"), AirConversationModel.keyGroups("abcde", 2));

        final StringBuilder key = new StringBuilder();
        for (int i = 0; i < 64; i++)
        {
            key.append(Integer.toHexString(i % 16));
        }
        final String lines = AirConversationModel.keyLines(key.toString(), 4);
        final String[] split = lines.split("\n");
        assertEquals(4, split.length);
        assertEquals("0123 4567 89AB CDEF", split[0]);
        assertEquals("0123 4567 89AB CDEF", split[3]);
        assertEquals("0123 4567 89AB CDEF 0123", AirConversationModel.keyLines(key.substring(0, 20), 0));
        assertEquals("0123 4567\n89AB", AirConversationModel.keyLines(key.substring(0, 12), 2));
        assertEquals("", AirConversationModel.keyLines("", 4));
    }

    // ---- the day strip ----

    @Test
    public void day_strip_shows_the_last_time_each_hour_came_round()
    {
        final long now = utc(2026, 11, 11, 10, 30);
        final Set<Long> hours = new HashSet<>(Arrays.asList(
                hourStart(2026, 11, 11, 9),
                hourStart(2026, 11, 11, 10),
                // Yesterday's 22 is still on the strip: 22 o'clock has not come round today yet.
                hourStart(2026, 11, 10, 22),
                // Yesterday's 8 is not: that cell already shows today's 8.
                hourStart(2026, 11, 10, 8),
                // Two days ago.
                hourStart(2026, 11, 9, 23)));
        final DayStrip strip = DayStrip.make(hours, now, UTC);

        assertEquals(10, strip.nowHour);
        assertTrue(strip.online[9]);
        assertTrue(strip.online[10]);
        assertTrue(strip.online[22]);
        assertFalse(strip.online[8]);
        assertFalse(strip.online[23]);
        assertTrue(strip.hasMarks());
        assertRanges(strip.ranges(), new int[]{9, 11}, new int[]{22, 23});

        final DayStrip lateRun = DayStrip.make(new HashSet<>(Arrays.asList(hourStart(2026, 11, 10, 22),
                                                                           hourStart(2026, 11, 10, 23),
                                                                           hourStart(2026, 11, 11, 0))), now, UTC);
        assertRanges(lateRun.ranges(), new int[]{0, 1}, new int[]{22, 24});

        final DayStrip empty = DayStrip.make(Collections.<Long>emptySet(), now, UTC);
        assertFalse(empty.hasMarks());
        assertTrue(empty.ranges().isEmpty());
        assertFalse(DayStrip.make(null, now, UTC).hasMarks());
    }

    @Test
    public void hours_are_filed_by_the_start_of_the_local_clock_hour()
    {
        final long ms = at(KOLKATA, 2026, 11, 11, 10, 47, 12);
        final long start = AirConversationModel.hourStartSeconds(ms, KOLKATA);
        assertEquals(at(KOLKATA, 2026, 11, 11, 10, 0, 0) / 1000L, start);
        // Half an hour off UTC: the start is not a multiple of 3600.
        assertEquals(1800L, start % 3600L);
        assertTrue(DayStrip.make(Collections.singleton(start), ms, KOLKATA).online[10]);
        assertEquals(hourStart(2026, 11, 11, 10), AirConversationModel.hourStartSeconds(utc(2026, 11, 11, 10, 59), UTC));
    }

    // ---- best hours ----

    @Test
    public void best_hours_need_two_days_and_take_the_longest_top_run()
    {
        final long now = utc(2026, 11, 11, 18, 0);

        // One day only: the strip already says it.
        assertNull(AirConversationModel.bestHours(set(hourStart(2026, 11, 11, 9), hourStart(2026, 11, 11, 10)), now, UTC));
        // Two days, but no hour on both.
        assertNull(AirConversationModel.bestHours(set(hourStart(2026, 11, 10, 9), hourStart(2026, 11, 11, 15)), now, UTC));
        assertNull(AirConversationModel.bestHours(Collections.<Long>emptySet(), now, UTC));
        assertNull(AirConversationModel.bestHours(null, now, UTC));

        assertArrayEquals(new int[]{9, 11}, AirConversationModel.bestHours(set(
                hourStart(2026, 11, 10, 9), hourStart(2026, 11, 10, 10),
                hourStart(2026, 11, 11, 9), hourStart(2026, 11, 11, 10), hourStart(2026, 11, 11, 15)), now, UTC));

        // The longest run of the top count wins; between equal runs, the first.
        assertArrayEquals(new int[]{20, 23}, AirConversationModel.bestHours(set(
                hourStart(2026, 11, 9, 7), hourStart(2026, 11, 10, 7),
                hourStart(2026, 11, 9, 20), hourStart(2026, 11, 9, 21), hourStart(2026, 11, 9, 22),
                hourStart(2026, 11, 10, 20), hourStart(2026, 11, 10, 21), hourStart(2026, 11, 10, 22)), now, UTC));
        assertArrayEquals(new int[]{7, 8}, AirConversationModel.bestHours(set(
                hourStart(2026, 11, 9, 7), hourStart(2026, 11, 10, 7),
                hourStart(2026, 11, 9, 20), hourStart(2026, 11, 10, 20)), now, UTC));

        // Counted in days, not marks: three days at 6 beat two days at 9 and 10.
        assertArrayEquals(new int[]{6, 7}, AirConversationModel.bestHours(set(
                hourStart(2026, 11, 8, 6), hourStart(2026, 11, 9, 6), hourStart(2026, 11, 10, 6),
                hourStart(2026, 11, 9, 9), hourStart(2026, 11, 10, 9),
                hourStart(2026, 11, 9, 10), hourStart(2026, 11, 10, 10)), now, UTC));

        // Marks older than a week do not count.
        assertNull(AirConversationModel.bestHours(set(hourStart(2026, 11, 3, 9), hourStart(2026, 11, 11, 9)), now, UTC));
        assertArrayEquals(new int[]{9, 10}, AirConversationModel.bestHours(set(
                hourStart(2026, 11, 4, 18), hourStart(2026, 11, 11, 9), hourStart(2026, 11, 10, 9)), now, UTC));

        // Online around the clock: no best time.
        final Set<Long> always = new HashSet<>();
        for (int hour = 0; hour < 24; hour++)
        {
            always.add(hourStart(2026, 11, 9, hour));
            always.add(hourStart(2026, 11, 10, hour));
        }
        assertNull(AirConversationModel.bestHours(always, now, UTC));
        // Nineteen hours is still a best time; twenty is not.
        final Set<Long> nineteen = new HashSet<>();
        final Set<Long> twenty = new HashSet<>();
        for (int hour = 0; hour < 20; hour++)
        {
            if (hour < 19)
            {
                nineteen.add(hourStart(2026, 11, 9, hour));
                nineteen.add(hourStart(2026, 11, 10, hour));
            }
            twenty.add(hourStart(2026, 11, 9, hour));
            twenty.add(hourStart(2026, 11, 10, hour));
        }
        assertArrayEquals(new int[]{0, 19}, AirConversationModel.bestHours(nineteen, now, UTC));
        assertNull(AirConversationModel.bestHours(twenty, now, UTC));
    }

    @Test
    public void day_hint_says_the_best_hours_then_the_ranges_then_nothing_yet()
    {
        final long now = utc(2026, 11, 11, 10, 30);
        final DayStrip marked = DayStrip.make(set(hourStart(2026, 11, 11, 9)), now, UTC);
        final DayStrip blank = DayStrip.make(Collections.<Long>emptySet(), now, UTC);
        assertEquals(DayHint.BEST, DayHint.of(new int[]{7, 9}, marked));
        assertEquals(DayHint.BEST, DayHint.of(new int[]{7, 9}, blank));
        assertEquals(DayHint.RANGES, DayHint.of(null, marked));
        assertEquals(DayHint.EMPTY, DayHint.of(null, blank));
        assertEquals(DayHint.EMPTY, DayHint.of(null, null));
    }

    @Test
    public void digits_follow_the_language_of_the_numbers_around_them()
    {
        final char arabicZero = '٠';
        // "Last 24 hours: from 18:00 to 20:00" as Arabic formats it: the hours in Arabic-Indic digits from
        // "%d", the "24" of the translation and the ":00" after each hour in 0 to 9.
        final String mixed = "آخر 24 ساعة: من ١٨:00";
        assertEquals("آخر ٢٤ ساعة: من ١٨:٠٠",
                     AirConversationModel.localDigits(mixed, arabicZero));
        assertEquals("٠١٢٣٤٥٦٧٨٩",
                     AirConversationModel.localDigits("0123456789", arabicZero));
        // Letters and digits already in the language stay; every 0 to 9 changes, so a key never goes through it.
        assertEquals("KD ٧٢F ١", AirConversationModel.localDigits("KD 72F ١", arabicZero));
        // A language that writes 0 to 9 keeps the very text, and takes back a clock the old formatter kept
        // in Arabic-Indic digits from before a switch of language.
        final String clock = "19:46";
        assertSame(clock, AirConversationModel.localDigits(clock, '0'));
        assertEquals("19:46", AirConversationModel.localDigits("١٩:٤٦", '0'));
        // Extended Arabic-Indic (Persian) digits count as digits too.
        assertEquals("٢٤", AirConversationModel.localDigits("۲۴", arabicZero));
        assertEquals("", AirConversationModel.localDigits(null, arabicZero));
        assertEquals("", AirConversationModel.localDigits("", arabicZero));
    }

    private static Set<Long> set(final Long... values)
    {
        return new HashSet<>(Arrays.asList(values));
    }

    private static void assertRanges(final List<int[]> ranges, final int[]... expected)
    {
        assertEquals(expected.length, ranges.size());
        for (int i = 0; i < expected.length; i++)
        {
            assertArrayEquals("range " + i, expected[i], ranges.get(i));
        }
    }
}

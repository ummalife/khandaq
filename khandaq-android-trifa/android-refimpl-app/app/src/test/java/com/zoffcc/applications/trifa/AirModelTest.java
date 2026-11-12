package com.zoffcc.applications.trifa;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static com.zoffcc.applications.trifa.AirModel.Command;
import static com.zoffcc.applications.trifa.AirModel.Delivery;
import static com.zoffcc.applications.trifa.AirModel.EmptyState;
import static com.zoffcc.applications.trifa.AirModel.Entry;
import static com.zoffcc.applications.trifa.AirModel.EntryKind;
import static com.zoffcc.applications.trifa.AirModel.Filter;
import static com.zoffcc.applications.trifa.AirModel.ListBuilder;
import static com.zoffcc.applications.trifa.AirModel.MessageHit;
import static com.zoffcc.applications.trifa.AirModel.NetworkState;
import static com.zoffcc.applications.trifa.AirModel.PreviewStyle;
import static com.zoffcc.applications.trifa.AirModel.Reach;
import static com.zoffcc.applications.trifa.AirModel.Request;
import static com.zoffcc.applications.trifa.AirModel.Row;
import static com.zoffcc.applications.trifa.AirModel.SectionKind;
import static com.zoffcc.applications.trifa.AirModel.Snapshot;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (redesign): the Air screen's rules as plain values: which section a row stands in and in
 * what order, what each filter and the command line keep, every count and empty state, and the small
 * helpers the rows use (reach, network, tone, initials, the request key, highlights, strings). The same
 * cases as khandaq-ios/AntidoteTests/AirListModelTests.swift, so both phones agree.
 */
public class AirModelTest
{
    // ---- sections and order ----

    @Test
    public void sections_stand_in_their_order_and_rows_sort_by_activity()
    {
        final List<Entry> entries = Arrays.asList(
                entry("away-old", Reach.away(7200), 10),
                entry("live-new", Reach.DIRECT, 300),
                entry("relay", Reach.RELAY, 200),
                entry("group-live", EntryKind.GROUP, Reach.group(2), 100),
                entry("group-empty", EntryKind.GROUP, Reach.group(0), 50),
                entry("linking", Reach.CONNECTING, 20),
                entry("pending", Reach.PENDING, 0),
                saved());
        final List<Request> requests = Arrays.asList(request("older", 1), request("newer", 2));

        final Snapshot snapshot = build(entries, requests, Filter.ALL, "");

        assertEquals(Arrays.asList(SectionKind.REQUESTS, SectionKind.LIVE, SectionKind.AWAY, SectionKind.SAVED),
                     kinds(snapshot));
        assertEquals(Arrays.asList("NEWER", "OLDER"), ids(snapshot, SectionKind.REQUESTS));
        assertEquals(Arrays.asList("live-new", "relay", "group-live"), ids(snapshot, SectionKind.LIVE));
        assertEquals(Arrays.asList("group-empty", "linking", "away-old", "pending"), ids(snapshot, SectionKind.AWAY));
        assertEquals(Collections.singletonList("saved"), ids(snapshot, SectionKind.SAVED));
        assertNull(snapshot.emptyState);
        assertEquals(10, snapshot.itemCount());
    }

    @Test
    public void contacts_without_a_conversation_come_last_by_name()
    {
        final List<Entry> entries = Arrays.asList(
                entry("k2", "zed", EntryKind.CONTACT),
                entry("k1", "Anna", EntryKind.CONTACT),
                entry("chat", "Bob", 5),
                entry("k3", "anna", EntryKind.CONTACT));

        final Snapshot snapshot = build(entries, Collections.<Request>emptyList(), Filter.ALL, "");

        assertEquals(Arrays.asList("chat", "k1", "k3", "k2"), ids(snapshot, SectionKind.AWAY));
    }

    @Test
    public void rows_carry_a_heading_with_its_count_except_for_requests()
    {
        final Snapshot snapshot = build(Arrays.asList(entry("a", Reach.DIRECT, 2), entry("b", Reach.away(60), 1)),
                                        Collections.singletonList(request("r", 1)), Filter.ALL, "");

        final List<String> rows = new ArrayList<>();
        for (Row row : snapshot.rows)
        {
            rows.add(row.id() + (row.type == Row.Type.HEADER ? "=" + row.count : ""));
        }
        assertEquals(Arrays.asList("request:R", "header:live=1", "entry:a", "header:away=1", "entry:b"), rows);
    }

    @Test
    public void a_heading_keeps_its_id_when_its_count_changes()
    {
        final Snapshot before = build(Collections.singletonList(entry("a", Reach.DIRECT, 1)),
                                      Collections.<Request>emptyList(), Filter.ALL, "");
        final Snapshot after = build(Arrays.asList(entry("a", Reach.DIRECT, 1), entry("b", Reach.DIRECT, 2)),
                                     Collections.<Request>emptyList(), Filter.ALL, "");

        assertEquals(before.rows.get(0).id(), after.rows.get(0).id());
        assertFalse(before.rows.get(0).sameContent(after.rows.get(0)));
        assertTrue(before.rows.get(0).sameContent(before.rows.get(0)));
    }

    // ---- filters ----

    @Test
    public void invitations_stand_with_the_requests_and_count_with_them()
    {
        final Request invite = Request.invite("invite-1", "cd34", " Book club ", "Amina", false, "", 3);
        final List<Request> cards = Arrays.asList(request("older", 1), invite, request("newer", 2));

        final Snapshot snapshot = build(Collections.singletonList(saved()), cards, Filter.REQUESTS, "");

        assertEquals(Arrays.asList("invite-1", "NEWER", "OLDER"), ids(snapshot, SectionKind.REQUESTS));
        assertEquals(3, snapshot.counts.requests);
        assertEquals(Request.Kind.GROUP_INVITE, invite.kind);
        assertEquals("CD34", invite.publicKey);
        assertEquals("Book club", invite.groupName);
        // The inviter coming online is a change the card has to show.
        assertNotEquals(invite, Request.invite("invite-1", "cd34", "Book club", "Amina", true, "", 3));
        assertEquals(invite, Request.invite("invite-1", "CD34", "Book club", "Amina", false, "", 3));
        assertEquals(Request.Kind.CONTACT, request("r", 1).kind);
    }

    @Test
    public void requests_filter_keeps_cards_and_unanswered_contacts()
    {
        final List<Entry> entries = Arrays.asList(entry("pending", Reach.PENDING, 0), entry("friend", Reach.DIRECT, 0),
                                                  saved());

        final Snapshot snapshot = build(entries, Collections.singletonList(request("r", 1)), Filter.REQUESTS, "");

        assertEquals(Arrays.asList(SectionKind.REQUESTS, SectionKind.AWAY), kinds(snapshot));
        assertEquals(Collections.singletonList("pending"), ids(snapshot, SectionKind.AWAY));
        assertNull(snapshot.emptyState);
    }

    @Test
    public void transit_filter_keeps_rows_with_waiting_messages()
    {
        final List<Entry> entries = Arrays.asList(entry("waiting", 2), entry("clear", 0), saved());

        final Snapshot snapshot = build(entries, Collections.singletonList(request("r", 1)), Filter.TRANSIT, "");

        assertEquals(Collections.singletonList(SectionKind.AWAY), kinds(snapshot));
        assertEquals(Collections.singletonList("waiting"), ids(snapshot, SectionKind.AWAY));
        assertNull(snapshot.emptyState);
    }

    @Test
    public void undelivered_messages_wait_in_transit_only_while_the_person_is_away()
    {
        assertEquals(3, Reach.away(60).messagesInTransit(3));
        assertEquals(2, Reach.CONNECTING.messagesInTransit(2));
        assertEquals(1, Reach.PENDING.messagesInTransit(1));
        assertEquals(4, Reach.AWAY_LONG_AGO.messagesInTransit(4));
        assertEquals(0, Reach.DIRECT.messagesInTransit(3));
        assertEquals(0, Reach.RELAY.messagesInTransit(3));
    }

    @Test
    public void row_mark_of_a_last_text_follows_the_conversation()
    {
        assertEquals(Delivery.TRANSIT, Delivery.ofOutgoingText(false, 7L, false));
        assertEquals(Delivery.TRANSIT, Delivery.ofOutgoingText(false, -1L, true));
        assertEquals(Delivery.SENT, Delivery.ofOutgoingText(false, 7L, true));
        assertEquals(Delivery.DELIVERED, Delivery.ofOutgoingText(true, 7L, false));
        assertEquals(Delivery.DELIVERED, Delivery.ofOutgoingText(true, 7L, true));

        // Android also knows the watchdog gave up: the row keeps a clock, a receipt still wins.
        assertEquals(Delivery.TRANSIT, Delivery.ofDirectText(false, true, 7L, true));
        assertEquals(Delivery.DELIVERED, Delivery.ofDirectText(true, true, 7L, true));
        assertEquals(Delivery.SENT, Delivery.ofDirectText(false, false, 7L, true));
        assertEquals(Delivery.TRANSIT, Delivery.ofDirectText(false, false, 7L, false));
    }

    @Test
    public void favorites_filter_keeps_starred_rows_and_saved_messages()
    {
        final List<Entry> entries = Arrays.asList(favorite("star"), entry("plain", Reach.DIRECT, 0), saved());

        final Snapshot snapshot = build(entries, Collections.<Request>emptyList(), Filter.FAVORITES, "");

        assertEquals(Arrays.asList(SectionKind.LIVE, SectionKind.SAVED), kinds(snapshot));
        assertEquals(Collections.singletonList("star"), ids(snapshot, SectionKind.LIVE));
        assertNull(snapshot.emptyState);
    }

    @Test
    public void favorites_filter_no_longer_matches_every_row()
    {
        // The old Android list let its "Favorites" row through every filter, so no filter was ever empty.
        final List<Entry> entries = Arrays.asList(entry("a", Reach.DIRECT, 1), entry("b", Reach.away(9), 2), saved());

        final Snapshot snapshot = build(entries, Collections.<Request>emptyList(), Filter.FAVORITES, "");

        assertEquals(Collections.singletonList(SectionKind.SAVED), kinds(snapshot));
        assertEquals(EmptyState.NO_FAVORITES, snapshot.emptyState);
    }

    @Test
    public void swipe_order_steps_through_the_filters_and_stops_at_the_ends()
    {
        assertEquals(Filter.REQUESTS, AirModel.neighbourFilter(Filter.ALL, 1));
        assertEquals(Filter.FAVORITES, AirModel.neighbourFilter(Filter.TRANSIT, 1));
        assertNull(AirModel.neighbourFilter(Filter.FAVORITES, 1));
        assertNull(AirModel.neighbourFilter(Filter.ALL, -1));
        assertEquals(Filter.TRANSIT, AirModel.neighbourFilter(Filter.FAVORITES, -1));
        assertEquals(Arrays.asList(Filter.REQUESTS, Filter.TRANSIT, Filter.FAVORITES), Filter.PILLS);
        assertEquals(Filter.TRANSIT, Filter.fromName("transit"));
        assertEquals(Filter.ALL, Filter.fromName("nonsense"));
    }

    // ---- searching ----

    @Test
    public void query_matches_titles_ignoring_case_and_marks_and_hides_requests()
    {
        final List<Entry> entries = Arrays.asList(entry("a", "Zoë Adams", Reach.DIRECT), entry("b", "Bob", 0),
                                                  saved());
        final MessageHit hit = new MessageHit("m1", "b", "Bob", "zoe said hi", "");

        final Snapshot snapshot = ListBuilder.build(entries, Collections.singletonList(request("r", 1)),
                                                    Collections.singletonList(hit), Filter.ALL, "  zoe ");

        assertEquals(Arrays.asList(SectionKind.LIVE, SectionKind.MESSAGES), kinds(snapshot));
        assertEquals(Collections.singletonList("a"), ids(snapshot, SectionKind.LIVE));
        assertEquals(Collections.singletonList("m1"), ids(snapshot, SectionKind.MESSAGES));
        assertNull(snapshot.emptyState);
    }

    @Test
    public void message_hits_are_ignored_without_a_query()
    {
        final MessageHit hit = new MessageHit("m1", "b", "Bob", "", "");

        final Snapshot snapshot = ListBuilder.build(Collections.singletonList(entry("b", "Bob", 0)),
                                                    Collections.<Request>emptyList(),
                                                    Collections.singletonList(hit), Filter.ALL, " ");

        assertEquals(Collections.singletonList(SectionKind.AWAY), kinds(snapshot));
    }

    @Test
    public void highlights_cover_every_match_in_the_original_text()
    {
        assertRanges("Zoë and ZOE", "zoe", new int[]{0, 3}, new int[]{8, 11});
        // A decomposed letter keeps its mark inside the highlight.
        assertRanges("Zoë!", "zoe", new int[]{0, 4});
        assertRanges("Привет мир", "МИР",
                     new int[]{7, 10});
        assertTrue(AirModel.matchRanges("abc", "  ").isEmpty());
        assertTrue(AirModel.matchRanges("", "a").isEmpty());
        assertTrue(AirModel.matchRanges("abc", "x").isEmpty());
    }

    // ---- counts and empty states ----

    @Test
    public void counts_ignore_the_filter_and_the_query()
    {
        final List<Entry> entries = Arrays.asList(entry("a", 2), entry("b", 1), saved());
        final List<Request> requests = Arrays.asList(request("r1", 1), request("r2", 2));

        for (Filter filter : Filter.SWIPE_ORDER)
        {
            for (String query : new String[]{"", "nothing like it"})
            {
                final Snapshot snapshot = build(entries, requests, filter, query);
                assertEquals(filter + " " + query, new AirModel.Counts(2, 3), snapshot.counts);
            }
        }
    }

    @Test
    public void only_saved_messages_means_nothing_yet()
    {
        final Snapshot snapshot = build(Collections.singletonList(saved()), Collections.<Request>emptyList(),
                                        Filter.ALL, "");

        assertEquals(EmptyState.NOTHING_YET, snapshot.emptyState);
        assertEquals(Collections.singletonList(SectionKind.SAVED), kinds(snapshot));
    }

    @Test
    public void a_request_alone_is_not_nothing()
    {
        final Snapshot snapshot = build(Collections.singletonList(saved()), Collections.singletonList(request("r", 1)),
                                        Filter.ALL, "");

        assertNull(snapshot.emptyState);
    }

    @Test
    public void empty_state_for_each_filter_and_for_the_query()
    {
        final List<Entry> entries = Arrays.asList(entry("a", 0), saved());
        final List<Request> none = Collections.emptyList();

        assertEquals(EmptyState.NO_REQUESTS, build(entries, none, Filter.REQUESTS, "").emptyState);
        assertEquals(EmptyState.NO_TRANSIT, build(entries, none, Filter.TRANSIT, "").emptyState);
        assertEquals(EmptyState.NO_FAVORITES, build(entries, none, Filter.FAVORITES, "").emptyState);
        assertEquals(EmptyState.NO_MATCH, build(entries, none, Filter.ALL, "qqq").emptyState);
        assertNull(build(entries, none, Filter.ALL, "").emptyState);
    }

    // ---- command line ----

    @Test
    public void command_line_tells_a_search_from_an_id()
    {
        final String address = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5";
        final String publicKey = address.substring(0, 64);

        assertCommand(Command.Type.EMPTY, "", "");
        assertCommand(Command.Type.EMPTY, "", "  \n ");
        assertCommand(Command.Type.EMPTY, "", null);
        assertCommand(Command.Type.QUERY, "Alice", "  Alice  ");
        // Hex letters in ordinary words are still a search.
        assertCommand(Command.Type.QUERY, "cafe bead", "cafe bead");
        // A bare key has the length of a group ID: never taken for a person's ID on its own, only offered
        // as the start of one ("New contact with this ID"), and the search goes on.
        assertCommand(Command.Type.PARTIAL_ADDRESS, publicKey, publicKey);
        assertCommand(Command.Type.QUERY, address + "0", address + "0");
        // The start of an ID: 12 characters or more with a digit among them, spaces and a prefix aside.
        assertCommand(Command.Type.PARTIAL_ADDRESS, address.substring(0, 12), address.substring(0, 12).toLowerCase());
        assertCommand(Command.Type.PARTIAL_ADDRESS, address.substring(0, 16), "tox:" + address.substring(0, 4) + " "
                                                                              + address.substring(4, 16));
        assertCommand(Command.Type.QUERY, address.substring(0, 11), address.substring(0, 11));
        // Hex letters only is a name, not an ID.
        assertCommand(Command.Type.QUERY, "Abbe Cafe Bead", "Abbe Cafe Bead");

        final String pasted = address.substring(0, 30).toLowerCase() + " \n" + address.substring(30);
        assertCommand(Command.Type.CONTACT_ADDRESS, address, pasted);
        assertCommand(Command.Type.CONTACT_ADDRESS, address, "tox://" + address);
        assertCommand(Command.Type.CONTACT_ADDRESS, address, "Tox:" + address);
        assertCommand(Command.Type.CONTACT_ADDRESS, address, "​" + address + "﻿");
        assertCommand(Command.Type.CONTACT_ADDRESS, address, address);
        // Only one prefix comes off.
        assertCommand(Command.Type.QUERY, "tox:tox:" + address, "tox:tox:" + address);
    }

    @Test
    public void request_key_shows_the_first_twelve_digits_in_fours()
    {
        assertEquals("56A1 ADE4 B65B", AirModel.groupedKey("56a1ade4b65b86bcd51cc73e"));
        assertEquals("56A1 AD", AirModel.groupedKey("56A1AD"));
        assertEquals("", AirModel.groupedKey(""));
        assertEquals("", AirModel.groupedKey(null));
    }

    @Test
    public void one_card_per_person_whatever_case_the_key_arrives_in()
    {
        final Request lower = new Request("abcd", "  hello  ", "", 1);
        assertEquals("ABCD", lower.id);
        assertEquals("ABCD", lower.publicKey);
        assertEquals("hello", lower.message);
    }

    // ---- reach and network ----

    @Test
    public void friend_reach()
    {
        final long now = 1_000_000L;

        assertEquals(Reach.DIRECT, Reach.friend(AirModel.TOX_CONNECTION_UDP, 0, now, false));
        assertEquals(Reach.RELAY, Reach.friend(AirModel.TOX_CONNECTION_TCP, 0, now, false));
        assertEquals(Reach.PENDING, Reach.friend(AirModel.TOX_CONNECTION_NONE, 0, now, false));
        assertEquals(Reach.away(90), Reach.friend(AirModel.TOX_CONNECTION_NONE, now - 90, now, false));
        // A clock that runs behind never gives a negative age.
        assertEquals(Reach.away(0), Reach.friend(AirModel.TOX_CONNECTION_NONE, now + 30, now, false));
    }

    @Test
    public void friend_seen_today_is_still_linking_right_after_we_come_online()
    {
        final long now = 1_000_000L;

        assertEquals(Reach.CONNECTING, Reach.friend(AirModel.TOX_CONNECTION_NONE, now - 90, now, true));
        assertEquals(Reach.away(86400), Reach.friend(AirModel.TOX_CONNECTION_NONE, now - 86400, now, true));
        assertEquals(Reach.PENDING, Reach.friend(AirModel.TOX_CONNECTION_NONE, 0, now, true));
        assertEquals(Reach.DIRECT, Reach.friend(AirModel.TOX_CONNECTION_UDP, now - 90, now, true));
    }

    @Test
    public void only_a_working_link_counts_as_live()
    {
        assertTrue(Reach.DIRECT.isLive());
        assertTrue(Reach.RELAY.isLive());
        assertTrue(Reach.group(1).isLive());
        assertFalse(Reach.group(0).isLive());
        assertFalse(Reach.away(1).isLive());
        assertFalse(Reach.PENDING.isLive());
        assertFalse(Reach.CONNECTING.isLive());
        assertFalse(Reach.LOCAL.isLive());
        assertFalse(Reach.AWAY_LONG_AGO.isLive());
    }

    @Test
    public void network_state_from_the_tox_link_and_the_device()
    {
        assertEquals(NetworkState.DIRECT, NetworkState.from(AirModel.TOX_CONNECTION_UDP, false));
        assertEquals(NetworkState.RELAY, NetworkState.from(AirModel.TOX_CONNECTION_TCP, true));
        assertEquals(NetworkState.CONNECTING, NetworkState.from(AirModel.TOX_CONNECTION_NONE, true));
        assertEquals(NetworkState.OFFLINE, NetworkState.from(AirModel.TOX_CONNECTION_NONE, false));
    }

    @Test
    public void a_lost_default_network_says_offline_before_tox_notices()
    {
        // Tox still says UDP or TCP for up to its own timeouts after the network went away.
        assertEquals(NetworkState.OFFLINE, NetworkState.from(AirModel.TOX_CONNECTION_UDP, true, true));
        assertEquals(NetworkState.OFFLINE, NetworkState.from(AirModel.TOX_CONNECTION_TCP, false, true));
        assertEquals(NetworkState.OFFLINE, NetworkState.from(AirModel.TOX_CONNECTION_NONE, true, true));
        // Back on a network, Tox's state speaks again.
        assertEquals(NetworkState.DIRECT, NetworkState.from(AirModel.TOX_CONNECTION_UDP, true, false));
        assertEquals(NetworkState.CONNECTING, NetworkState.from(AirModel.TOX_CONNECTION_NONE, true, false));
    }

    @Test
    public void away_words_change_unit_at_the_same_seconds_as_on_ios()
    {
        assertAway(59, AirModel.AwayWords.Unit.JUST_NOW, 0);
        assertAway(60, AirModel.AwayWords.Unit.MINUTES, 1);
        assertAway(3599, AirModel.AwayWords.Unit.MINUTES, 59);
        assertAway(3600, AirModel.AwayWords.Unit.HOURS, 1);
        assertAway(86399, AirModel.AwayWords.Unit.HOURS, 23);
        assertAway(86400, AirModel.AwayWords.Unit.DAYS, 1);
        assertAway(86400 * 7, AirModel.AwayWords.Unit.LONG_AGO, 0);
        assertAway(Long.MAX_VALUE, AirModel.AwayWords.Unit.LONG_AGO, 0);
    }

    // ---- tiles ----

    @Test
    public void tone_is_the_same_on_every_launch_and_every_client()
    {
        // FNV-1a over the upper-cased UTF-8 bytes, then mod 6: the iOS vectors, so a person keeps one colour.
        assertEquals(1, AirModel.Tone.index(""));
        assertEquals(0, AirModel.Tone.index("A"));
        assertEquals(3, AirModel.Tone.index("abc"));
        assertEquals(3, AirModel.Tone.index("ABC"));
        assertEquals(0, AirModel.Tone.index("56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855"));
        assertEquals(5, AirModel.Tone.index("group-chat-1"));
    }

    @Test
    public void tones_spread_over_all_six()
    {
        final Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 120; i++)
        {
            seen.add(AirModel.Tone.index("key-" + i));
        }
        assertEquals(new HashSet<>(Arrays.asList(0, 1, 2, 3, 4, 5)), seen);
        assertEquals(5, AirModel.Tone.wrap(-1));
        assertEquals(0, AirModel.Tone.wrap(6));
    }

    @Test
    public void initials_take_the_first_letters_of_two_words()
    {
        assertEquals("A‌M", AirModel.Initials.from("anna maria lopez"));
        assertEquals("B", AirModel.Initials.from("  Bob "));
        assertEquals("", AirModel.Initials.from(""));
        assertEquals("", AirModel.Initials.from(null));
        assertEquals("ع‌م", AirModel.Initials.from("علي محمد"));
        // A letter with its combining mark stays one letter.
        assertEquals("É", AirModel.Initials.from("émile"));
    }

    // ---- strings ----

    private static final String[] LOCALES = {"values", "values-ru", "values-ar", "values-zh-rCN"};
    private static final Pattern FORMAT_ARG = Pattern.compile("%(\\d+\\$)?[a-zA-Z]");
    /**
     * Glyphs the redesign keeps out of every text: dashes, the one-character ellipsis, the multiplication
     * sign, the middle dot and bullets, arrows, box drawing, blocks, geometric shapes, ballot boxes and
     * check or cross marks.
     */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "[\\u2012\\u2013\\u2014\\u2015\\u2026\\u00D7\\u00B7\\u2022\\u2023\\u2043\\u2190-\\u21FF"
            + "\\u2500-\\u259F\\u25A0-\\u25FF\\u2610-\\u2612\\u2713-\\u2718\\u27F0-\\u27FF\\u2B00-\\u2BFF]");

    @Test
    public void every_air_string_exists_in_every_language_with_the_same_arguments()
    {
        final Map<String, String> english = airStrings("values");
        assertTrue("too few air strings: " + english.size(), english.size() > 40);

        for (String locale : LOCALES)
        {
            final Map<String, String> other = airStrings(locale);
            assertEquals(locale, new TreeSet<>(english.keySet()), new TreeSet<>(other.keySet()));
            for (Map.Entry<String, String> string : english.entrySet())
            {
                final String translated = other.get(string.getKey());
                assertFalse(locale + " " + string.getKey(), translated.trim().isEmpty());
                assertEquals(locale + " " + string.getKey(), formatArguments(string.getValue()),
                             formatArguments(translated));
                final Matcher forbidden = FORBIDDEN.matcher(translated);
                assertFalse(locale + " " + string.getKey() + " has a forbidden glyph", forbidden.find());
            }
        }
    }

    @Test
    public void every_air_plural_exists_in_every_language_with_an_other_form_and_the_same_arguments()
    {
        final Map<String, Map<String, String>> english = airPlurals("values");
        assertTrue("too few air plurals: " + english.size(), english.size() >= 6);

        for (String folder : LOCALES)
        {
            final Map<String, Map<String, String>> other = airPlurals(folder);
            assertEquals(folder, new TreeSet<>(english.keySet()), new TreeSet<>(other.keySet()));
            for (Map.Entry<String, Map<String, String>> plural : english.entrySet())
            {
                final Set<String> arguments = formatArguments(plural.getValue().get("other"));
                final Map<String, String> forms = other.get(plural.getKey());
                assertTrue(folder + " " + plural.getKey() + " has no other form", forms.containsKey("other"));
                for (Map.Entry<String, String> form : forms.entrySet())
                {
                    final String where = folder + " " + plural.getKey() + " " + form.getKey();
                    assertFalse(where, form.getValue().trim().isEmpty());
                    assertEquals(where, arguments, formatArguments(form.getValue()));
                    assertFalse(where + " has a forbidden glyph", FORBIDDEN.matcher(form.getValue()).find());
                }
            }
        }
    }

    // ---- fixtures ----

    private static Snapshot build(final List<Entry> entries, final List<Request> requests, final Filter filter,
                                  final String query)
    {
        return ListBuilder.build(entries, requests, Collections.<MessageHit>emptyList(), filter, query);
    }

    private static Entry entry(final String id, final String title, final EntryKind kind, final Reach reach,
                               final long activity, final int transit, final boolean favorite)
    {
        return new Entry(id, kind, title, reach, "", PreviewStyle.MESSAGE, null, "", activity, transit, 0, favorite,
                         null, AirModel.Tone.index(id));
    }

    private static Entry entry(final String id, final Reach reach, final long activity)
    {
        return entry(id, id, EntryKind.DIRECT, reach, activity, 0, false);
    }

    private static Entry entry(final String id, final EntryKind kind, final Reach reach, final long activity)
    {
        return entry(id, id, kind, reach, activity, 0, false);
    }

    private static Entry entry(final String id, final String title, final EntryKind kind)
    {
        return entry(id, title, kind, Reach.away(3600), 0, 0, false);
    }

    private static Entry entry(final String id, final String title, final long activity)
    {
        return entry(id, title, EntryKind.DIRECT, Reach.away(3600), activity, 0, false);
    }

    private static Entry entry(final String id, final String title, final Reach reach)
    {
        return entry(id, title, EntryKind.DIRECT, reach, 0, 0, false);
    }

    private static Entry entry(final String id, final int transit)
    {
        return entry(id, id, EntryKind.DIRECT, Reach.away(3600), 0, transit, false);
    }

    private static Entry favorite(final String id)
    {
        return entry(id, id, EntryKind.DIRECT, Reach.DIRECT, 0, 0, true);
    }

    private static Entry saved()
    {
        return entry("saved", "Saved", EntryKind.SAVED, Reach.LOCAL, 1, 0, false);
    }

    private static Request request(final String id, final long date)
    {
        return new Request(id, "", "", date);
    }

    private static void assertCommand(final Command.Type type, final String value, final String raw)
    {
        final Command command = Command.parse(raw);
        assertEquals(String.valueOf(raw), type, command.type);
        assertEquals(String.valueOf(raw), value, command.value);
    }

    private static void assertRanges(final String text, final String query, final int[]... expected)
    {
        final List<int[]> ranges = AirModel.matchRanges(text, query);
        assertEquals(text, expected.length, ranges.size());
        for (int i = 0; i < expected.length; i++)
        {
            assertArrayEquals(text + " #" + i, expected[i], ranges.get(i));
        }
    }

    private static void assertAway(final long seconds, final AirModel.AwayWords.Unit unit, final long amount)
    {
        final AirModel.AwayWords words = AirModel.AwayWords.of(seconds);
        assertEquals(seconds + " s", unit, words.unit);
        assertEquals(seconds + " s", amount, words.amount);
    }

    private static List<SectionKind> kinds(final Snapshot snapshot)
    {
        final List<SectionKind> kinds = new ArrayList<>();
        for (AirModel.Section section : snapshot.sections)
        {
            kinds.add(section.kind);
        }
        return kinds;
    }

    private static List<String> ids(final Snapshot snapshot, final SectionKind kind)
    {
        final List<String> ids = new ArrayList<>();
        final AirModel.Section section = snapshot.section(kind);
        if (section == null)
        {
            return ids;
        }
        for (AirModel.Item item : section.items)
        {
            if (item.request != null)
            {
                ids.add(item.request.id);
            }
            else if (item.entry != null)
            {
                ids.add(item.entry.id);
            }
            else
            {
                ids.add(item.message.id);
            }
        }
        return ids;
    }

    private static Set<String> formatArguments(final String text)
    {
        final Set<String> out = new TreeSet<>();
        final Matcher matcher = FORMAT_ARG.matcher(text);
        while (matcher.find())
        {
            out.add(matcher.group());
        }
        return out;
    }

    /** {@code air_*} strings of one resource folder, from strings_air.xml. */
    private static Map<String, String> airStrings(final String folder)
    {
        final Map<String, String> out = new LinkedHashMap<>();
        final File file = resourceFile(folder + "/strings_air.xml");
        try
        {
            final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            final NodeList nodes = doc.getElementsByTagName("string");
            for (int i = 0; i < nodes.getLength(); i++)
            {
                final Element string = (Element) nodes.item(i);
                final String name = string.getAttribute("name");
                if (name.startsWith("air_"))
                {
                    out.put(name, string.getTextContent());
                }
            }
        }
        catch (Exception e)
        {
            throw new AssertionError("cannot read " + file, e);
        }
        assertNotNull(out);
        return out;
    }

    /** {@code air_*} plurals of one resource folder, from strings_air.xml: name, then quantity to text. */
    private static Map<String, Map<String, String>> airPlurals(final String folder)
    {
        final Map<String, Map<String, String>> out = new LinkedHashMap<>();
        final File file = resourceFile(folder + "/strings_air.xml");
        try
        {
            final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            final NodeList plurals = doc.getElementsByTagName("plurals");
            for (int i = 0; i < plurals.getLength(); i++)
            {
                final Element plural = (Element) plurals.item(i);
                final String name = plural.getAttribute("name");
                if (!name.startsWith("air_"))
                {
                    continue;
                }
                final Map<String, String> forms = new LinkedHashMap<>();
                final NodeList items = plural.getElementsByTagName("item");
                for (int j = 0; j < items.getLength(); j++)
                {
                    final Element item = (Element) items.item(j);
                    forms.put(item.getAttribute("quantity"), item.getTextContent());
                }
                out.put(name, forms);
            }
        }
        catch (Exception e)
        {
            throw new AssertionError("cannot read " + file, e);
        }
        return out;
    }

    private static File resourceFile(final String path)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (String root : new String[]{"src/main/res", "app/src/main/res"})
        {
            final File f = new File(root, path);
            if (f.isFile())
            {
                return f;
            }
        }
        throw new AssertionError("res/" + path + " not found from " + new File("").getAbsolutePath());
    }
}

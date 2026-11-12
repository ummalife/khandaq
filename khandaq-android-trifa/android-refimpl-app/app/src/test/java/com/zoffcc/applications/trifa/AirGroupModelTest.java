package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.zoffcc.applications.trifa.AirGroupModel.MemberAction.KICK;
import static com.zoffcc.applications.trifa.AirGroupModel.MemberAction.MAKE_MODERATOR;
import static com.zoffcc.applications.trifa.AirGroupModel.MemberAction.MAKE_OBSERVER;
import static com.zoffcc.applications.trifa.AirGroupModel.MemberAction.MAKE_USER;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_FOUNDER;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_MODERATOR;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_OBSERVER;
import static com.zoffcc.applications.trifa.AirGroupModel.ROLE_USER;
import static com.zoffcc.applications.trifa.AirGroupModel.VOICE_ALL;
import static com.zoffcc.applications.trifa.AirGroupModel.VOICE_FOUNDER;
import static com.zoffcc.applications.trifa.AirGroupModel.VOICE_MODERATOR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The group rules of E13 to E15, case for case with AirGroupModelTests.swift on iOS. */
public class AirGroupModelTest
{
    private static final int[] ROLES = {ROLE_FOUNDER, ROLE_MODERATOR, ROLE_USER, ROLE_OBSERVER};

    @Test
    public void the_founder_gives_any_role_but_founder_to_anyone_else()
    {
        assertTrue(AirGroupModel.canSet(ROLE_MODERATOR, ROLE_USER, ROLE_FOUNDER));
        assertTrue(AirGroupModel.canSet(ROLE_OBSERVER, ROLE_MODERATOR, ROLE_FOUNDER));
        assertTrue(AirGroupModel.canSet(ROLE_USER, ROLE_OBSERVER, ROLE_FOUNDER));
        assertFalse(AirGroupModel.canSet(ROLE_FOUNDER, ROLE_USER, ROLE_FOUNDER));
        assertFalse(AirGroupModel.canSet(ROLE_USER, ROLE_FOUNDER, ROLE_FOUNDER));
        // Not to the role the member already has.
        assertFalse(AirGroupModel.canSet(ROLE_USER, ROLE_USER, ROLE_FOUNDER));
    }

    @Test
    public void a_moderator_moves_members_and_observers_between_those_two_roles_only()
    {
        assertTrue(AirGroupModel.canSet(ROLE_OBSERVER, ROLE_USER, ROLE_MODERATOR));
        assertTrue(AirGroupModel.canSet(ROLE_USER, ROLE_OBSERVER, ROLE_MODERATOR));
        assertFalse(AirGroupModel.canSet(ROLE_MODERATOR, ROLE_USER, ROLE_MODERATOR));
        assertFalse(AirGroupModel.canSet(ROLE_USER, ROLE_MODERATOR, ROLE_MODERATOR));
        assertFalse(AirGroupModel.canSet(ROLE_OBSERVER, ROLE_FOUNDER, ROLE_MODERATOR));
    }

    @Test
    public void members_observers_and_unknown_roles_change_nothing()
    {
        for (final int actor : new int[]{ROLE_USER, ROLE_OBSERVER, -1, 7})
        {
            for (final int target : ROLES)
            {
                for (final int role : ROLES)
                {
                    assertFalse(AirGroupModel.canSet(role, target, actor));
                }
                assertFalse(AirGroupModel.canKick(target, actor));
            }
        }
        // A member whose role toxcore did not report is not changed either.
        assertFalse(AirGroupModel.canSet(ROLE_USER, -1, ROLE_FOUNDER));
        assertFalse(AirGroupModel.canKick(-1, ROLE_FOUNDER));
    }

    @Test
    public void removing_follows_the_same_ladder()
    {
        assertTrue(AirGroupModel.canKick(ROLE_MODERATOR, ROLE_FOUNDER));
        assertTrue(AirGroupModel.canKick(ROLE_OBSERVER, ROLE_FOUNDER));
        assertFalse(AirGroupModel.canKick(ROLE_FOUNDER, ROLE_FOUNDER));
        assertTrue(AirGroupModel.canKick(ROLE_USER, ROLE_MODERATOR));
        assertTrue(AirGroupModel.canKick(ROLE_OBSERVER, ROLE_MODERATOR));
        assertFalse(AirGroupModel.canKick(ROLE_MODERATOR, ROLE_MODERATOR));
        assertFalse(AirGroupModel.canKick(ROLE_FOUNDER, ROLE_MODERATOR));
    }

    @Test
    public void the_menu_offers_only_what_the_actor_may_do_in_a_fixed_order()
    {
        assertEquals(Arrays.asList(MAKE_MODERATOR, MAKE_OBSERVER, KICK),
                     AirGroupModel.MemberAction.available(ROLE_USER, ROLE_FOUNDER));
        assertEquals(Arrays.asList(MAKE_USER, MAKE_OBSERVER, KICK),
                     AirGroupModel.MemberAction.available(ROLE_MODERATOR, ROLE_FOUNDER));
        assertEquals(Arrays.asList(MAKE_OBSERVER, KICK),
                     AirGroupModel.MemberAction.available(ROLE_USER, ROLE_MODERATOR));
        assertEquals(Arrays.asList(MAKE_USER, KICK),
                     AirGroupModel.MemberAction.available(ROLE_OBSERVER, ROLE_MODERATOR));
        assertTrue(AirGroupModel.MemberAction.available(ROLE_MODERATOR, ROLE_MODERATOR).isEmpty());
        assertTrue(AirGroupModel.MemberAction.available(ROLE_FOUNDER, ROLE_FOUNDER).isEmpty());
        assertTrue(AirGroupModel.MemberAction.available(ROLE_OBSERVER, ROLE_USER).isEmpty());
        // Undo of a change of role gives the old role back.
        assertEquals(MAKE_USER, AirGroupModel.MemberAction.giving(ROLE_USER));
        assertEquals(MAKE_MODERATOR, AirGroupModel.MemberAction.giving(ROLE_MODERATOR));
        assertEquals(MAKE_OBSERVER, AirGroupModel.MemberAction.giving(ROLE_OBSERVER));
        assertNull(AirGroupModel.MemberAction.giving(ROLE_FOUNDER));
    }

    @Test
    public void members_sort_by_role_then_online_then_last_seen_then_name()
    {
        final List<AirGroupModel.Member> members = new ArrayList<>();
        members.add(member("A1", "Zaid", ROLE_USER, false, 100));
        members.add(member("A2", "Amina", ROLE_USER, true, 0));
        members.add(member("A3", "Bilal", ROLE_USER, false, 500));
        members.add(member("A4", "Omar", ROLE_OBSERVER, true, 0));
        members.add(member("A5", "Yusuf", ROLE_FOUNDER, false, 0));
        members.add(member("A6", "Huda", ROLE_MODERATOR, false, 0));
        members.add(member("A7", "Aisha", ROLE_USER, false, 100));
        members.add(member("A8", "Nobody", -1, false, 0));
        assertEquals(Arrays.asList("Yusuf", "Huda", "Amina", "Bilal", "Aisha", "Zaid", "Nobody", "Omar"),
                     names(AirGroupModel.sorted(members)));
    }

    @Test
    public void the_search_ignores_case_and_marks()
    {
        final List<AirGroupModel.Member> members = Arrays.asList(member("A1", "Zoë", ROLE_USER, true, 0),
                                                                 member("A2", "Amina", ROLE_USER, true, 0),
                                                                 member("A3", "ZOE K", ROLE_USER, true, 0));
        assertEquals(Arrays.asList("Zoë", "ZOE K"), names(AirGroupModel.filtered(members, " zoe ")));
        assertEquals(3, AirGroupModel.filtered(members, "  ").size());
        assertTrue(AirGroupModel.filtered(members, "Yusuf").isEmpty());
    }

    @Test
    public void who_gets_it_now_is_everyone_online_but_you_and_moderators_exclude_you()
    {
        final List<AirGroupModel.Member> members = new ArrayList<>();
        members.add(selfMember("Bilal", ROLE_MODERATOR));
        members.add(member("A2", "Amina", ROLE_USER, true, 0));
        members.add(member("A3", "Huda", ROLE_MODERATOR, false, 10));
        members.add(member("A4", "Yusuf", ROLE_FOUNDER, true, 0));
        members.add(member("A5", "Omar", ROLE_OBSERVER, false, 0));
        assertEquals(Arrays.asList("Yusuf", "Amina"), names(AirGroupModel.reachableNow(members)));
        assertEquals(Arrays.asList("Yusuf", "Huda"), names(AirGroupModel.moderators(members)));
    }

    @Test
    public void nobody_is_shown_online_while_the_group_is_not_connected()
    {
        // Connected: you always, the others as last seen.
        assertTrue(AirGroupModel.shownOnline(true, true, false));
        assertTrue(AirGroupModel.shownOnline(true, false, true));
        assertFalse(AirGroupModel.shownOnline(true, false, false));
        // Not connected: neither you nor a member seen a moment ago, as the header counts nobody online.
        assertFalse(AirGroupModel.shownOnline(false, true, true));
        assertFalse(AirGroupModel.shownOnline(false, false, true));
        assertTrue(AirGroupModel.reachableNow(Arrays.asList(
                member("A2", "Amina", ROLE_USER, AirGroupModel.shownOnline(false, false, true), 0))).isEmpty());
    }

    @Test
    public void another_members_role_waits_for_the_group_to_be_connected()
    {
        for (final int role : ROLES)
        {
            // Connected: every role as toxcore or this device knows it.
            assertEquals(role, AirGroupModel.shownRole(true, false, role));
            assertEquals(role, AirGroupModel.shownRole(true, true, role));
            // Not connected: yours stays, another member's is not shown.
            assertEquals(role, AirGroupModel.shownRole(false, true, role));
            assertEquals(AirGroupModel.ROLE_UNKNOWN, AirGroupModel.shownRole(false, false, role));
        }
        // A role nobody reported is never shown, connected or not.
        assertEquals(AirGroupModel.ROLE_UNKNOWN, AirGroupModel.shownRole(true, false, 7));
        assertEquals(AirGroupModel.ROLE_UNKNOWN, AirGroupModel.shownRole(true, true, AirGroupModel.ROLE_UNKNOWN));
        assertFalse(AirGroupModel.isKnownRole(AirGroupModel.ROLE_UNKNOWN));
    }

    @Test
    public void a_member_without_a_role_shown_offers_no_action_and_asks_nobody()
    {
        final int hidden = AirGroupModel.shownRole(false, false, ROLE_USER);
        // Not even the founder may change a role or remove a member the screen shows no role for.
        assertTrue(AirGroupModel.MemberAction.available(hidden, ROLE_FOUNDER).isEmpty());
        assertTrue(AirGroupModel.MemberAction.available(hidden, ROLE_MODERATOR).isEmpty());
        // The moderators an observer can write to are not known either, so none is offered.
        final int hiddenModerator = AirGroupModel.shownRole(false, false, ROLE_MODERATOR);
        final AirGroupModel.Member zaynab = member("M1", "Zaynab", hiddenModerator, false, 0);
        assertTrue(AirGroupModel.moderators(Arrays.asList(zaynab, selfMember("Samir", ROLE_OBSERVER))).isEmpty());
        // Such a member sorts with the members by name, no longer first as a moderator; yours stays known.
        final List<AirGroupModel.Member> members = Arrays.asList(zaynab, member("U1", "Yusuf", ROLE_USER, false, 0),
                                                                 selfMember("Samir", ROLE_FOUNDER));
        assertEquals(Arrays.asList("Samir", "Yusuf", "Zaynab"), names(AirGroupModel.sorted(members)));
    }

    @Test
    public void the_reach_counts_you_in_the_members_but_not_among_those_online()
    {
        final AirGroupModel.Reach reach = new AirGroupModel.Reach(9, 7, 4, true);
        assertEquals(9, reach.total);
        assertEquals(4, reach.online);
        assertEquals(8, reach.recipients());
        assertEquals(AirGroupModel.Reach.Header.SOME, reach.header());
        assertEquals(AirGroupModel.Reach.Hint.SOME, reach.hint());
        assertEquals(AirConversationModel.ComposeHint.Dot.LIVE, reach.dot());
        // More members listed than toxcore counts: the list wins; never fewer than you alone.
        assertEquals(12, new AirGroupModel.Reach(3, 12, 1, true).total);
        assertEquals(1, new AirGroupModel.Reach(0, 0, 0, true).total);
        // Online never counts you and never exceeds the others.
        assertEquals(2, new AirGroupModel.Reach(3, 3, 9, true).online);
        assertEquals(0, new AirGroupModel.Reach(3, 3, -2, true).online);
    }

    @Test
    public void the_hint_has_a_case_for_each_situation()
    {
        final AirGroupModel.Reach offline = new AirGroupModel.Reach(5, 5, 3, false);
        assertEquals(0, offline.online);
        assertEquals(AirGroupModel.Reach.Header.OFFLINE, offline.header());
        assertEquals(AirGroupModel.Reach.Hint.OFFLINE, offline.hint());
        assertEquals(AirConversationModel.ComposeHint.Dot.TRANSIT, offline.dot());

        final AirGroupModel.Reach alone = new AirGroupModel.Reach(1, 1, 0, true);
        assertEquals(AirGroupModel.Reach.Header.NOBODY, alone.header());
        assertEquals(AirGroupModel.Reach.Hint.ALONE, alone.hint());
        assertEquals(AirConversationModel.ComposeHint.Dot.NONE, alone.dot());

        final AirGroupModel.Reach nobody = new AirGroupModel.Reach(4, 4, 0, true);
        assertEquals(AirGroupModel.Reach.Hint.NOBODY, nobody.hint());
        assertEquals(AirConversationModel.ComposeHint.Dot.TRANSIT, nobody.dot());

        assertEquals(AirGroupModel.Reach.Hint.ONE_OTHER, new AirGroupModel.Reach(2, 2, 1, true).hint());
        assertEquals(AirGroupModel.Reach.Hint.ALL, new AirGroupModel.Reach(5, 5, 4, true).hint());
        assertEquals(AirGroupModel.Reach.Hint.SOME, new AirGroupModel.Reach(5, 5, 3, true).hint());
    }

    @Test
    public void observers_never_write_and_the_voice_state_narrows_the_others()
    {
        assertEquals(AirGroupModel.ComposeRight.OBSERVER, AirGroupModel.ComposeRight.make(ROLE_OBSERVER, VOICE_ALL));
        assertEquals(AirGroupModel.ComposeRight.WRITE, AirGroupModel.ComposeRight.make(ROLE_USER, VOICE_ALL));
        assertEquals(AirGroupModel.ComposeRight.MODERATORS_ONLY,
                     AirGroupModel.ComposeRight.make(ROLE_USER, VOICE_MODERATOR));
        assertEquals(AirGroupModel.ComposeRight.WRITE, AirGroupModel.ComposeRight.make(ROLE_MODERATOR, VOICE_MODERATOR));
        assertEquals(AirGroupModel.ComposeRight.WRITE, AirGroupModel.ComposeRight.make(ROLE_FOUNDER, VOICE_MODERATOR));
        assertEquals(AirGroupModel.ComposeRight.FOUNDER_ONLY,
                     AirGroupModel.ComposeRight.make(ROLE_MODERATOR, VOICE_FOUNDER));
        assertEquals(AirGroupModel.ComposeRight.WRITE, AirGroupModel.ComposeRight.make(ROLE_FOUNDER, VOICE_FOUNDER));
        // A voice state toxcore could not read leaves the field as it was.
        assertEquals(AirGroupModel.ComposeRight.WRITE, AirGroupModel.ComposeRight.make(ROLE_USER, -1));
        assertTrue(AirGroupModel.ComposeRight.FOUNDER_ONLY.asksFounder());
        assertFalse(AirGroupModel.ComposeRight.OBSERVER.asksFounder());
    }

    @Test
    public void an_observer_neither_asks_nor_answers_privately_a_member_held_back_by_the_voice_state_does()
    {
        // toxcore refuses every private message of an observer: gc_send_private_message gives -5 while
        // gc_get_self_role(chat) >= GR_OBSERVER, whatever the voice state.
        for (final int voice : new int[]{VOICE_ALL, VOICE_MODERATOR, VOICE_FOUNDER, -1})
        {
            final AirGroupModel.ComposeRight observer = AirGroupModel.ComposeRight.make(ROLE_OBSERVER, voice);
            assertEquals(AirGroupModel.ComposeRight.OBSERVER, observer);
            assertFalse(observer.asks());
            assertFalse(observer.writesPrivately());
        }
        assertFalse(AirGroupModel.writesPrivately(ROLE_OBSERVER));

        // A member whom "moderators only" or "the founder only" keeps from writing to all asks privately,
        // and toxcore takes it: their own role is a member's.
        final AirGroupModel.ComposeRight moderatorsOnly = AirGroupModel.ComposeRight.make(ROLE_USER, VOICE_MODERATOR);
        assertEquals(AirGroupModel.ComposeRight.MODERATORS_ONLY, moderatorsOnly);
        assertTrue(moderatorsOnly.asks());
        assertTrue(moderatorsOnly.writesPrivately());
        final AirGroupModel.ComposeRight founderOnly = AirGroupModel.ComposeRight.make(ROLE_USER, VOICE_FOUNDER);
        assertEquals(AirGroupModel.ComposeRight.FOUNDER_ONLY, founderOnly);
        assertTrue(founderOnly.asks());
        assertTrue(founderOnly.writesPrivately());
        assertTrue(AirGroupModel.writesPrivately(ROLE_USER));
        // A moderator held back by "the founder only" asks the founder the same way.
        assertTrue(AirGroupModel.ComposeRight.make(ROLE_MODERATOR, VOICE_FOUNDER).asks());
        assertTrue(AirGroupModel.writesPrivately(ROLE_MODERATOR));

        // Whoever writes to all has nobody to ask, and writes privately as well; so does the founder, and
        // a role toxcore does not say yet leaves the field to toxcore.
        for (final int role : new int[]{ROLE_FOUNDER, ROLE_MODERATOR, ROLE_USER})
        {
            final AirGroupModel.ComposeRight write = AirGroupModel.ComposeRight.make(role, VOICE_ALL);
            assertEquals(AirGroupModel.ComposeRight.WRITE, write);
            assertFalse(write.asks());
            assertTrue(write.writesPrivately());
        }
        assertTrue(AirGroupModel.writesPrivately(ROLE_FOUNDER));
        assertTrue(AirGroupModel.writesPrivately(-1));
    }

    @Test
    public void invitations_list_those_who_can_be_invited_first()
    {
        final List<AirGroupModel.InviteCandidate> candidates = new ArrayList<>();
        candidates.add(new AirGroupModel.InviteCandidate(4, "K4", "Zaid", false, false));
        candidates.add(new AirGroupModel.InviteCandidate(1, "K1", "Yusuf", true, false));
        candidates.add(new AirGroupModel.InviteCandidate(2, "K2", "Amina", true, true));
        candidates.add(new AirGroupModel.InviteCandidate(3, "K3", "Bilal", true, false));
        candidates.add(new AirGroupModel.InviteCandidate(-1, "K5", "Huda", true, false));
        final List<AirGroupModel.InviteCandidate> arranged = AirGroupModel.arranged(candidates, "");
        final List<String> order = new ArrayList<>();
        for (final AirGroupModel.InviteCandidate candidate : arranged)
        {
            order.add(candidate.name);
        }
        assertEquals(Arrays.asList("Bilal", "Yusuf", "Amina", "Huda", "Zaid"), order);
        assertEquals(AirGroupModel.InviteNote.ALREADY_MEMBER, AirGroupModel.InviteNote.of(candidates.get(2)));
        assertEquals(AirGroupModel.InviteNote.WHEN_ONLINE, AirGroupModel.InviteNote.of(candidates.get(0)));
        assertEquals(AirGroupModel.InviteNote.NONE, AirGroupModel.InviteNote.of(candidates.get(1)));
        assertEquals(1, AirGroupModel.arranged(candidates, "bil").size());
    }

    @Test
    public void a_group_name_is_one_line_of_at_most_48_bytes()
    {
        assertEquals("Book club", AirGroupModel.cleanName("  Book \n\t club  "));
        assertEquals("", AirGroupModel.cleanName(" \n "));
        assertEquals(0, AirGroupModel.nameOverflow("Book club"));
        final StringBuilder exactly = new StringBuilder();
        for (int i = 0; i < 48; i++)
        {
            exactly.append('a');
        }
        assertEquals(0, AirGroupModel.nameOverflow(exactly.toString()));
        assertEquals(1, AirGroupModel.nameOverflow(exactly + "b"));
        // Cyrillic letters take two bytes each: 25 of them are 50 bytes.
        final StringBuilder cyrillic = new StringBuilder();
        for (int i = 0; i < 25; i++)
        {
            cyrillic.append('ж');
        }
        assertEquals(2, AirGroupModel.nameOverflow(cyrillic.toString()));
    }

    @Test
    public void the_group_counter_shows_the_last_tenth_of_1372_bytes()
    {
        assertNull(AirGroupModel.composeRemaining(1200));
        assertEquals(Integer.valueOf(136), AirGroupModel.composeRemaining(1236));
        assertEquals(Integer.valueOf(0), AirGroupModel.composeRemaining(1372));
        assertEquals(Integer.valueOf(-8), AirGroupModel.composeRemaining(1380));
    }

    private static AirGroupModel.Member member(final String key, final String name, final int role,
                                               final boolean online, final long lastSeenMs)
    {
        return new AirGroupModel.Member(key, name, role, online, lastSeenMs, false);
    }

    private static AirGroupModel.Member selfMember(final String name, final int role)
    {
        return new AirGroupModel.Member("SELF", name, role, true, 0, true);
    }

    private static List<String> names(final List<AirGroupModel.Member> members)
    {
        final List<String> result = new ArrayList<>();
        for (final AirGroupModel.Member member : members)
        {
            result.add(member.name);
        }
        return result;
    }
}

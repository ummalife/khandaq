package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (26.11.2026): @mentions as in Telegram, in any script (DESIGN-group-contacts-private-mentions.md).
 * A handle picked from the list after "@" was made of Cyrillic letters, and the token that finds handles in
 * the text was ASCII only, so the mention never went out: no mark and no notification for the member.
 */
public class GroupMentionsTest
{
    private static final String KEY_A = key(0xA1);
    private static final String KEY_B = key(0xB2);
    private static final String KEY_C = key(0xC3);
    private static final String KEY_D = key(0xD4);

    private static String key(final int value)
    {
        return String.format(Locale.ROOT, "%02X%062X", value, value);
    }

    private static HelperGroup.GroupMemberDisplay member(final String key, final String name, final boolean online,
                                                         final boolean self)
    {
        final HelperGroup.GroupMemberDisplay member = new HelperGroup.GroupMemberDisplay();
        member.pubkey = key;
        member.name = name;
        member.online = online;
        member.self = self;
        return member;
    }

    private static List<String> names(final List<HelperGroup.GroupMemberDisplay> members)
    {
        final List<String> names = new ArrayList<>();
        for (final HelperGroup.GroupMemberDisplay member : members)
        {
            names.add(member.name);
        }
        return names;
    }

    private static String token(final String text)
    {
        final Matcher matcher = GroupMentionHelper.MENTION_TOKEN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    @Test
    public void a_handle_keeps_the_letters_of_any_script()
    {
        assertEquals("Иса_Дагестани", GroupMentionHelper.mentionHandleFromName("Иса Дагестани"));
        assertEquals("عيسى_الداغستاني", GroupMentionHelper.mentionHandleFromName("عيسى الداغستاني"));
        assertEquals("李小龙", GroupMentionHelper.mentionHandleFromName("李小龙"));
        assertEquals("Jean_Luc_Picard", GroupMentionHelper.mentionHandleFromName("Jean-Luc Picard"));
        assertEquals("Ali", GroupMentionHelper.mentionHandleFromName("Ali (Moscow)"));
        assertEquals("Ali", GroupMentionHelper.mentionHandleFromName(" -Ali- "));
        assertEquals("", GroupMentionHelper.mentionHandleFromName("..."));
        assertEquals("", GroupMentionHelper.mentionHandleFromName(null));
    }

    @Test
    public void a_handle_keeps_the_marks_of_its_letters()
    {
        // Arabic harakat (non-spacing) and Devanagari vowel signs (spacing) belong to the letter before them.
        assertEquals("مُحَمَّد", GroupMentionHelper.mentionHandleFromName("مُحَمَّد"));
        assertEquals("मोहन", GroupMentionHelper.mentionHandleFromName("मोहन"));
        // A mark with no letter before it starts nothing.
        assertEquals("a", GroupMentionHelper.mentionHandleFromName("́a"));
    }

    @Test
    public void the_token_after_at_reads_a_handle_in_any_script()
    {
        assertEquals("Иса_Дагестани", token("Салам, @Иса_Дагестани, как дела?"));
        assertEquals("مُحَمَّد", token("@مُحَمَّد مرحبا"));
        assertEquals("मोहन", token("hi @मोहन!"));
        assertEquals("Ali_2", token("@Ali_2."));
        assertNull(token("@ alone"));
        assertNull(token("no mention here"));
        // Every handle a name makes is read back whole by the token.
        for (final String name : new String[]{"Иса Дагестани", "مُحَمَّد", "मोहन", "李小龙", "José María"})
        {
            final String handle = GroupMentionHelper.mentionHandleFromName(name);
            assertEquals(handle, token(GroupMentionHelper.insertMentionToken(handle)));
        }
    }

    @Test
    public void a_name_with_nothing_to_keep_takes_its_key_and_a_twin_the_start_of_its_own()
    {
        final Set<String> used = new HashSet<>();
        assertEquals("u" + KEY_A.substring(0, 8), GroupMentionHelper.uniqueHandle("...", KEY_A, used, true));
        assertEquals("Иса", GroupMentionHelper.uniqueHandle("Иса", KEY_B, used, true));
        assertEquals("Иса_" + KEY_C.substring(0, 4), GroupMentionHelper.uniqueHandle("Иса", KEY_C, used, true));
        // Case makes no other handle: the name in lower case is taken by the same name capitalised.
        assertEquals("иса_" + KEY_D.substring(0, 4), GroupMentionHelper.uniqueHandle("иса", KEY_D, used, true));
    }

    @Test
    public void the_list_after_at_puts_online_first_then_by_name_and_leaves_you_out()
    {
        final List<HelperGroup.GroupMemberDisplay> all = Arrays.asList(
                member(KEY_A, "Борис", false, false),
                member(KEY_B, "Анна", false, false),
                member(KEY_C, "Яна", true, false),
                member(KEY_D, "Я сам", true, true));
        assertEquals(Arrays.asList("Яна", "Анна", "Борис"),
                     names(GroupMentionInputController.matchingMembers(all, "")));
        assertEquals(Arrays.asList("Яна", "Анна", "Борис"),
                     names(GroupMentionInputController.matchingMembers(all, null)));
    }

    @Test
    public void what_is_typed_after_at_matches_without_case_or_accents_in_any_script()
    {
        final List<HelperGroup.GroupMemberDisplay> all = Arrays.asList(
                member(KEY_A, "Иса Дагестани", true, false),
                member(KEY_B, "José", false, false),
                member(KEY_C, "مُحَمَّد", false, false),
                member(KEY_D, "Ali", false, false));
        assertEquals(Arrays.asList("Иса Дагестани"), names(GroupMentionInputController.matchingMembers(all, "иса")));
        assertEquals(Arrays.asList("Иса Дагестани"), names(GroupMentionInputController.matchingMembers(all, "ДАГ")));
        assertEquals(Arrays.asList("José"), names(GroupMentionInputController.matchingMembers(all, "jose")));
        assertEquals(Arrays.asList("مُحَمَّد"), names(GroupMentionInputController.matchingMembers(all, "محمد")));
        assertTrue(GroupMentionInputController.matchingMembers(all, "zzz").isEmpty());
        assertEquals("ежик", GroupMentionInputController.fold("Ёжик"));
        assertEquals("", GroupMentionInputController.fold(null));
    }
}

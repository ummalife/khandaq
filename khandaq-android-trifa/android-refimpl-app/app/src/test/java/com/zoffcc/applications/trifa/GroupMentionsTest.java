package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void twins_all_take_the_start_of_their_key_whatever_the_order_of_the_list()
    {
        final List<HelperGroup.GroupMemberDisplay> members = Arrays.asList(
                member(KEY_A, "...", true, false),
                member(KEY_B, "Иса", true, false),
                member(KEY_C, "Иса", false, false),
                member(KEY_D, "иса", false, false));
        final Map<String, String> handles = GroupMentionHelper.mentionHandles(members);
        assertEquals("u" + KEY_A.substring(0, 8), handles.get(KEY_A));
        // As on iOS and desktop: case makes no other handle, and none of the three keeps the bare one.
        assertEquals("Иса_" + KEY_B.substring(0, 4), handles.get(KEY_B));
        assertEquals("Иса_" + KEY_C.substring(0, 4), handles.get(KEY_C));
        assertEquals("иса_" + KEY_D.substring(0, 4), handles.get(KEY_D));
        final List<HelperGroup.GroupMemberDisplay> reversed = new ArrayList<>(members);
        Collections.reverse(reversed);
        assertEquals(handles, GroupMentionHelper.mentionHandles(reversed));
    }

    @Test
    public void twins_whose_keys_start_alike_take_eight_characters()
    {
        final String first = "ABCD1111" + String.format(Locale.ROOT, "%056d", 0);
        final String second = "ABCD2222" + String.format(Locale.ROOT, "%056d", 0);
        final Map<String, String> handles = GroupMentionHelper.mentionHandles(Arrays.asList(
                member(first, "Ali", true, false), member(second, "Ali", true, false)));
        assertEquals("Ali_ABCD1111", handles.get(first));
        assertEquals("Ali_ABCD2222", handles.get(second));
    }

    @Test
    public void a_token_names_the_member_of_its_handle_and_a_twin_bare_handle_names_nobody()
    {
        final Map<String, String> handles = GroupMentionHelper.mentionHandles(Arrays.asList(
                member(KEY_A, "Ali", true, false),
                member(KEY_B, "Иса", true, false),
                member(KEY_C, "Иса", false, false)));
        assertEquals(KEY_A, GroupMentionHelper.keyOfHandle(handles, "ali"));
        assertEquals(KEY_C, GroupMentionHelper.keyOfHandle(handles,
                                                           ("Иса_" + KEY_C.substring(0, 4)).toLowerCase(Locale.ROOT)));
        assertNull(GroupMentionHelper.keyOfHandle(handles, "Иса"));
        assertNull(GroupMentionHelper.keyOfHandle(handles, null));
        // A member's key is upper case in the map whatever case it came in.
        final Map<String, String> lower = GroupMentionHelper.mentionHandles(
                Collections.singletonList(member(KEY_A.toLowerCase(Locale.ROOT), "Ali", true, false)));
        assertEquals("Ali", lower.get(KEY_A));
    }

    @Test
    public void the_list_after_at_matches_a_twin_by_the_start_of_its_key()
    {
        final List<HelperGroup.GroupMemberDisplay> all = Arrays.asList(
                member(KEY_B, "Иса", true, false),
                member(KEY_C, "Иса", false, false));
        assertEquals(Arrays.asList("Иса"), names(GroupMentionInputController.matchingMembers(
                all, ("Иса_" + KEY_C.substring(0, 3)).toLowerCase(Locale.ROOT))));
        assertEquals(2, GroupMentionInputController.matchingMembers(all, "иса").size());
    }

    @Test
    public void a_text_mentions_you_by_a_whole_token_that_no_block_gave_to_someone_else()
    {
        final GroupMentionHelper.ParsedGroupText plain = new GroupMentionHelper.ParsedGroupText();
        plain.bodyText = "@Alina, @Мурад_Тест: look";
        assertTrue(GroupMentionHelper.mentionsSelfByText(plain, "Мурад_Тест", "Мурад_Тест"));
        // "@Ali" used to be found inside "@Alina".
        assertFalse(GroupMentionHelper.mentionsSelfByText(plain, "Ali", "Ali"));

        // A twin's bare handle: ours by name when no block says whose it is...
        final GroupMentionHelper.ParsedGroupText bare = new GroupMentionHelper.ParsedGroupText();
        bare.bodyText = "@Иса hi";
        assertTrue(GroupMentionHelper.mentionsSelfByText(bare, "Иса_" + KEY_C.substring(0, 4), "Иса"));
        // ...and not ours when the block gave it to the other twin.
        final GroupMentionHelper.MentionEntry other = new GroupMentionHelper.MentionEntry();
        other.pubkey = KEY_B;
        other.handle = "Иса";
        bare.mentions.add(other);
        assertFalse(GroupMentionHelper.mentionsSelfByText(bare, "Иса_" + KEY_C.substring(0, 4), "Иса"));
        assertFalse(GroupMentionHelper.mentionsSelfByText(bare, "", "Иса"));
    }

    /**
     * KHANDAQ (26.11.2026): the same members, handles and block are in the tests of iOS (GroupContactsMentionsTests)
     * and desktop (airmention_test.cpp), so every client names a member alike and reads the block another one wrote.
     */
    @Test
    public void every_client_names_the_members_alike_and_reads_the_same_block()
    {
        final String murad = "1111111111111111111111111111111111111111111111111111111111111111";
        final String isa = "ABCD111111111111111111111111111111111111111111111111111111111111";
        final String isaDot = "1234222222222222222222222222222222222222222222222222222222222222";
        final String isaCaps = "ABCD333333333333333333333333333333333333333333333333333333333333";
        final String dots = "0123456789FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF";
        final Map<String, String> handles = GroupMentionHelper.mentionHandles(Arrays.asList(
                member(murad, "\u041c\u0443\u0440\u0430\u0434 \u0422\u0435\u0441\u0442", true, false),
                member(isa, "Isa", true, false),
                member(isaDot, "isa.", true, false),
                member(isaCaps, "ISA", true, false),
                member(dots, "...", true, false)));
        final String muradHandle = "\u041c\u0443\u0440\u0430\u0434_\u0422\u0435\u0441\u0442";
        assertEquals(muradHandle, handles.get(murad));
        assertEquals("Isa_ABCD1111", handles.get(isa));
        assertEquals("isa_1234", handles.get(isaDot));
        assertEquals("ISA_ABCD3333", handles.get(isaCaps));
        assertEquals("u01234567", handles.get(dots));
        assertEquals(isa, GroupMentionHelper.keyOfHandle(handles, "isa_abcd1111"));

        final String body = "@" + muradHandle + " and @Isa_ABCD1111, look";
        final String raw = "[KQ|m|" + murad + ":" + muradHandle + ";" + isa + ":Isa_ABCD1111][KQ/m]\n" + body;
        final GroupMentionHelper.ParsedGroupText parsed = GroupMentionHelper.parse(raw);
        assertEquals(2, parsed.mentions.size());
        assertEquals(murad, parsed.mentions.get(0).pubkey);
        assertEquals(muradHandle, parsed.mentions.get(0).handle);
        assertEquals(isa, parsed.mentions.get(1).pubkey);
        assertEquals("Isa_ABCD1111", parsed.mentions.get(1).handle);
        assertEquals(body, parsed.bodyText);
        assertEquals(raw, GroupMentionHelper.encodeMentionsBlock(body, parsed.mentions));
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

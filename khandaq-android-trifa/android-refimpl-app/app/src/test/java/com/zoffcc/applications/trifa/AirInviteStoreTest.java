package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Invitations wait as cards until they are answered (E12): what the store keeps and what it drops. */
public class AirInviteStoreTest
{
    private static final String AMINA = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855";
    private static final String YUSUF = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71";
    private static final String BOOK_CLUB = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";
    private static final String FAMILY = "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210";

    @Test
    public void an_invitation_needs_a_contact_key_and_the_group_id()
    {
        final AirInviteStore.Invite invite = group("a", AMINA.toLowerCase(Locale.ROOT), BOOK_CLUB + "00FF", 10);
        assertNotNull(invite);
        assertEquals(AMINA, invite.inviterKey);
        assertEquals(BOOK_CLUB, invite.groupId());
        assertNull(group("b", AMINA.substring(0, 60), BOOK_CLUB, 10));
        // Shorter than the group's ID, or not hex: damaged.
        assertNull(group("c", AMINA, BOOK_CLUB.substring(0, 62), 10));
        assertNull(group("d", AMINA, BOOK_CLUB.substring(0, 63) + "G", 10));
        assertNull(AirInviteStore.Invite.of("", AirInviteStore.Kind.GROUP, AMINA, "Books", BOOK_CLUB, 0, 10));
        assertNull(AirInviteStore.Invite.of("e", null, AMINA, "Books", BOOK_CLUB, 0, 10));
    }

    @Test
    public void a_conference_cookie_has_the_group_id_after_three_bytes()
    {
        final AirInviteStore.Invite invite = AirInviteStore.Invite.of("a", AirInviteStore.Kind.CONFERENCE, AMINA, "",
                                                                      "000102" + FAMILY, 0, 10);
        assertNotNull(invite);
        assertEquals(FAMILY, invite.groupId());
        assertNull(AirInviteStore.Invite.of("b", AirInviteStore.Kind.CONFERENCE, AMINA, "", FAMILY, 0, 10));
        // A group keeps no conference type.
        assertEquals(0, AirInviteStore.Invite.of("c", AirInviteStore.Kind.GROUP, AMINA, "", FAMILY, 1, 10).conferenceType);
    }

    @Test
    public void the_name_is_one_short_line()
    {
        assertEquals("", AirInviteStore.cleanName(null));
        assertEquals("", AirInviteStore.cleanName("(null)"));
        assertEquals("Book club", AirInviteStore.cleanName("  Book\nclub "));
        assertEquals(AirInviteStore.MAX_NAME_LENGTH, AirInviteStore.cleanName(repeat("x", 80)).length());
    }

    @Test
    public void a_repeated_invitation_to_the_same_group_is_one_card()
    {
        List<AirInviteStore.Invite> list = Collections.emptyList();
        list = AirInviteStore.withAdded(list, group("1", AMINA, BOOK_CLUB, 10));
        list = AirInviteStore.withAdded(list, group("2", YUSUF, BOOK_CLUB, 20));
        list = AirInviteStore.withAdded(list, group("3", AMINA, FAMILY, 30));
        list = AirInviteStore.withAdded(list, group("4", AMINA, BOOK_CLUB + "AA", 40));
        assertEquals(3, list.size());
        // Newest first; Amina's second invitation to the book club replaced her first.
        assertEquals("4", list.get(0).id);
        assertEquals("3", list.get(1).id);
        assertEquals("2", list.get(2).id);
    }

    @Test
    public void the_list_keeps_the_newest_up_to_the_cap()
    {
        List<AirInviteStore.Invite> list = Collections.emptyList();
        for (int i = 0; i < AirInviteStore.MAX_INVITES + 5; i++)
        {
            final String id = String.format(Locale.ROOT, "%064X", i);
            list = AirInviteStore.withAdded(list, group("i" + i, AMINA, id, i));
        }
        assertEquals(AirInviteStore.MAX_INVITES, list.size());
        assertEquals("i" + (AirInviteStore.MAX_INVITES + 4), list.get(0).id);
    }

    @Test
    public void a_removed_contact_takes_their_invitations_along()
    {
        final List<AirInviteStore.Invite> list = new ArrayList<>();
        list.add(group("1", AMINA, BOOK_CLUB, 10));
        list.add(group("2", YUSUF, BOOK_CLUB, 20));
        list.add(group("3", AMINA, FAMILY, 30));
        final List<AirInviteStore.Invite> kept = AirInviteStore.without(list, null, AMINA.toLowerCase(Locale.ROOT));
        assertEquals(1, kept.size());
        assertEquals("2", kept.get(0).id);
        assertEquals(2, AirInviteStore.without(list, "2", null).size());
        assertEquals(3, AirInviteStore.without(list, "missing", null).size());
    }

    @Test
    public void a_group_we_are_in_by_now_takes_its_cards_along_and_conferences_stay()
    {
        final List<AirInviteStore.Invite> list = new ArrayList<>();
        list.add(group("1", AMINA, BOOK_CLUB, 10));
        list.add(group("2", YUSUF, BOOK_CLUB, 20));
        list.add(group("3", AMINA, FAMILY, 30));
        // A conference whose cookie carries the same ID after its three bytes is another kind of chat.
        list.add(AirInviteStore.Invite.of("4", AirInviteStore.Kind.CONFERENCE, AMINA, "Old", "000000" + BOOK_CLUB, 1,
                                          40));
        final List<AirInviteStore.Invite> kept = AirInviteStore.withoutGroups(
                list, java.util.Collections.singleton(BOOK_CLUB.toLowerCase(Locale.ROOT)));
        assertEquals(2, kept.size());
        assertEquals("3", kept.get(0).id);
        assertEquals("4", kept.get(1).id);
        assertEquals(4, AirInviteStore.withoutGroups(list, java.util.Collections.<String>emptySet()).size());
    }

    @Test
    public void the_store_reads_what_it_wrote_and_skips_damage()
    {
        List<AirInviteStore.Invite> list = Collections.emptyList();
        list = AirInviteStore.withAdded(list, group("1", AMINA, BOOK_CLUB, 10));
        list = AirInviteStore.withAdded(list, AirInviteStore.Invite.of("2", AirInviteStore.Kind.CONFERENCE, YUSUF,
                                                                       "Family", "000102" + FAMILY, 1, 20));
        final List<AirInviteStore.Invite> back = AirInviteStore.decode(AirInviteStore.encode(list));
        assertEquals(list, back);
        assertEquals(1, back.get(0).conferenceType);
        assertTrue(AirInviteStore.decode(null).isEmpty());
        assertTrue(AirInviteStore.decode("{not json").isEmpty());
        assertTrue(AirInviteStore.decode("{\"id\":\"1\"}").isEmpty());
        final String oneDamaged = "[{\"id\":\"x\",\"kind\":\"group\",\"inviter_key\":\"nope\",\"data\":\"" + BOOK_CLUB
                                  + "\"}," + AirInviteStore.encode(Collections.singletonList(list.get(1))).substring(1);
        assertEquals(1, AirInviteStore.decode(oneDamaged).size());
    }

    @Test
    public void the_data_goes_back_to_bytes_as_it_came()
    {
        final byte[] data = {0, 1, (byte) 0x7F, (byte) 0x80, (byte) 0xFF};
        final String hex = AirInviteStore.hex(data);
        assertEquals("00017F80FF", hex);
        final AirInviteStore.Invite invite = group("1", AMINA, BOOK_CLUB + hex, 10);
        final byte[] back = invite.dataBytes();
        assertEquals(32 + data.length, back.length);
        assertArrayEquals(data, java.util.Arrays.copyOfRange(back, 32, back.length));
        assertNull(AirInviteStore.hex(null));
    }

    @Test
    public void a_group_joined_from_a_card_goes_by_the_invitation_name_until_it_has_its_own() throws Exception
    {
        assertEquals("Khandaq Demo Group", AirInviteStore.placeholderName("", " Khandaq Demo Group "));
        assertEquals("Khandaq Demo Group", AirInviteStore.placeholderName(null, "Khandaq Demo Group"));
        assertEquals("Khandaq Demo Group", AirInviteStore.placeholderName("  ", "Khandaq\nDemo Group"));
        assertEquals("", AirInviteStore.placeholderName("Hiking club", "Khandaq Demo Group"));
        assertEquals("", AirInviteStore.placeholderName("", "(null)"));
        assertEquals("", AirInviteStore.placeholderName("", null));
        // Join from a card names the group so, and a start before it connects keeps that name.
        final String main = source("MainActivity.java");
        final int join = main.indexOf("static String join_invite(");
        assertTrue(join > 0);
        assertTrue(main.indexOf("HelperGroup.name_group_from_invitation(group_identifier, invite.groupName)", join) > join);
        assertTrue(source("TrifaToxService.java").contains("else if (group_name.length() > 0)"));
    }

    private static String source(final String name) throws java.io.IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final java.io.File file = new java.io.File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new java.io.File("").getAbsolutePath());
    }

    private static AirInviteStore.Invite group(final String id, final String inviter, final String data, final long ms)
    {
        return AirInviteStore.Invite.of(id, AirInviteStore.Kind.GROUP, inviter, "Books", data, 0, ms);
    }

    private static String repeat(final String text, final int times)
    {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++)
        {
            out.append(text);
        }
        return out.toString();
    }
}

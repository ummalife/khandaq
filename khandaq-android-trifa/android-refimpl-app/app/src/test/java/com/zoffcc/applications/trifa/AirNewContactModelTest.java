package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** "New contact" decides before sending: the ruler, the verdict and whether the request may go. */
public class AirNewContactModelTest
{
    /** A whole ID with a correct check (the one the iOS tests use). */
    private static final String ADDRESS = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5";
    private static final String OWN = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71000000007F3A";
    private static final String KEY = ADDRESS.substring(0, 64);

    @Test
    public void the_field_takes_an_id_in_any_usual_shape()
    {
        assertEquals(ADDRESS, AirNewContactModel.clean(ADDRESS.toLowerCase(Locale.ROOT)));
        assertEquals(ADDRESS, AirNewContactModel.clean("tox:" + ADDRESS));
        assertEquals(ADDRESS, AirNewContactModel.clean("TOX://" + ADDRESS.substring(0, 40) + "\n  " + ADDRESS.substring(40)));
        assertEquals(ADDRESS, AirNewContactModel.clean("​" + AirNewContactModel.grouped(ADDRESS) + "﻿"));
        // A sentence around a whole ID: the ID, not the hex letters of the words.
        assertEquals(ADDRESS, AirNewContactModel.clean("My ID is " + ADDRESS + ", add me"));
        // Anything longer is cut at 76 characters.
        assertEquals(AirNewContactModel.LENGTH, AirNewContactModel.clean(ADDRESS + "ABCD").length());
        assertEquals("1234AB", AirNewContactModel.clean("12 34 ab"));
        assertEquals("", AirNewContactModel.clean(null));
        assertEquals("1234 5678 90", AirNewContactModel.grouped("1234567890"));
        assertEquals("", AirNewContactModel.grouped(""));
    }

    @Test
    public void the_check_is_really_checked()
    {
        assertTrue(AirNewContactModel.checkMatches(ADDRESS));
        final String lastWrong = ADDRESS.substring(0, 75) + (ADDRESS.endsWith("5") ? "6" : "5");
        assertFalse(AirNewContactModel.checkMatches(lastWrong));
        // A typo in the key is caught too: the old field recomputed the check and hid it.
        final char[] characters = ADDRESS.toCharArray();
        characters[10] = characters[10] == 'A' ? 'B' : 'A';
        assertFalse(AirNewContactModel.checkMatches(new String(characters)));
        assertFalse(AirNewContactModel.checkMatches(KEY));
        assertFalse(AirNewContactModel.checkMatches(null));
        assertTrue(AirNewContactModel.checkMatches(OWN.substring(0, 72) + checkFor(OWN.substring(0, 72))));
    }

    @Test
    public void the_verdicts_come_in_their_order()
    {
        assertEquals(AirNewContactModel.Verdict.Kind.EMPTY, verdict("").kind);
        final AirNewContactModel.Verdict partial = verdict(KEY);
        assertEquals(AirNewContactModel.Verdict.Kind.PARTIAL, partial.kind);
        assertEquals(12, partial.left);
        assertEquals(AirNewContactModel.Verdict.Kind.BAD_CHECK, verdict(ADDRESS.substring(0, 75) + "0").kind);
        final AirNewContactModel.Verdict ok = verdict(ADDRESS);
        assertEquals(AirNewContactModel.Verdict.Kind.OK, ok.kind);
        assertEquals(ADDRESS, ok.address());
        final AirNewContactModel.Verdict contact = verdict(ADDRESS, set(KEY), set(), set());
        assertEquals(AirNewContactModel.Verdict.Kind.CONTACT, contact.kind);
        assertEquals(KEY, contact.publicKey);
        assertNull(contact.address());
        // Contact wins over a request and a block: the order is the design's.
        assertEquals(AirNewContactModel.Verdict.Kind.CONTACT, verdict(ADDRESS, set(KEY), set(KEY), set(KEY)).kind);
        assertEquals(AirNewContactModel.Verdict.Kind.THEY_ASKED, verdict(ADDRESS, set(), set(KEY), set(KEY)).kind);
        assertNull(verdict(ADDRESS, set(), set(KEY), set()).address());
        final AirNewContactModel.Verdict blocked = verdict(ADDRESS, set(), set(), set(KEY));
        assertEquals(AirNewContactModel.Verdict.Kind.BLOCKED, blocked.kind);
        // A blocked key may be asked again: that lifts the block.
        assertEquals(ADDRESS, blocked.address());
        // Your own ID with another antispam is still yours.
        final String ownOtherAntispam = OWN.substring(0, 64) + "12345678";
        assertEquals(AirNewContactModel.Verdict.Kind.OWN, verdict(ownOtherAntispam + checkFor(ownOtherAntispam)).kind);
    }

    @Test
    public void the_ruler_counts_each_part()
    {
        assertEquals(Arrays.asList(0, 0, 0), filled(0));
        assertEquals(Arrays.asList(30, 0, 0), filled(30));
        assertEquals(Arrays.asList(64, 6, 0), filled(70));
        assertEquals(Arrays.asList(64, 8, 4), filled(76));
        final AirNewContactModel.RulerPart[] parts = AirNewContactModel.RulerPart.parts(76);
        assertEquals(64, parts[0].size);
        assertEquals(8, parts[1].size);
        assertEquals(4, parts[2].size);
        assertEquals(AirNewContactModel.RulerState.FILLING, verdict(KEY).checkState());
        assertEquals(AirNewContactModel.RulerState.BAD, verdict(ADDRESS.substring(0, 75) + "0").checkState());
        final String ownWhole = OWN.substring(0, 72) + checkFor(OWN.substring(0, 72));
        assertEquals(AirNewContactModel.RulerState.GOOD, verdict(ownWhole).checkState());
    }

    @Test
    public void the_command_line_reads_the_start_of_an_id()
    {
        assertEquals(ADDRESS.substring(0, 12), AirNewContactModel.commandCandidate(ADDRESS.substring(0, 12)));
        assertNull(AirNewContactModel.commandCandidate(ADDRESS.substring(0, 11)));
        assertNull(AirNewContactModel.commandCandidate("Amina Safina"));
        assertEquals(ADDRESS, AirNewContactModel.commandCandidate("tox:" + ADDRESS.toLowerCase(Locale.ROOT)));
        assertEquals(ADDRESS.substring(0, 16), AirNewContactModel.commandCandidate(AirNewContactModel.grouped(ADDRESS.substring(0, 16))));
        assertNull(AirNewContactModel.commandCandidate(ADDRESS + "0"));
        // Hex letters only, a name like "Abbe Cafe Bead", is a name: an ID has digits.
        assertNull(AirNewContactModel.commandCandidate("Abbe Cafe Bead"));
        assertNull(AirNewContactModel.commandCandidate(null));
    }

    @Test
    public void the_command_row_says_what_is_wrong_so_far()
    {
        assertEquals(AirNewContactModel.CommandNote.LEFT,
                     AirNewContactModel.commandNote(ADDRESS.substring(0, 20), OWN, key -> false));
        assertEquals(AirNewContactModel.CommandNote.BAD,
                     AirNewContactModel.commandNote(ADDRESS.substring(0, 75) + "0", OWN, key -> false));
        assertEquals(AirNewContactModel.CommandNote.CONTACT,
                     AirNewContactModel.commandNote(ADDRESS, OWN, KEY::equals));
        assertEquals(AirNewContactModel.CommandNote.FINE, AirNewContactModel.commandNote(ADDRESS, OWN, key -> false));
        final String ownWhole = OWN.substring(0, 72) + checkFor(OWN.substring(0, 72));
        assertEquals(AirNewContactModel.CommandNote.OWN, AirNewContactModel.commandNote(ownWhole, OWN, key -> false));
    }

    @Test
    public void an_empty_message_sends_the_greeting()
    {
        assertEquals("Hello", AirNewContactModel.requestMessage("", "Hello"));
        assertEquals("Hello", AirNewContactModel.requestMessage("  \n ", "Hello"));
        assertEquals("Hello", AirNewContactModel.requestMessage(null, "Hello"));
        assertEquals("Salam", AirNewContactModel.requestMessage(" Salam ", "Hello"));
        assertEquals(0, AirNewContactModel.messageBytes(null));
        assertEquals(5, AirNewContactModel.messageBytes(" Salam "));
        // Bytes, not characters: toxcore counts UTF-8.
        assertEquals(10, AirNewContactModel.messageBytes("Салам"));
        assertEquals(1016, AirNewContactModel.MAX_MESSAGE_BYTES);
    }

    private static AirNewContactModel.Verdict verdict(final String hex)
    {
        return verdict(hex, set(), set(), set());
    }

    private static AirNewContactModel.Verdict verdict(final String hex, final Set<String> contacts,
                                                      final Set<String> requests, final Set<String> blocked)
    {
        return AirNewContactModel.Verdict.of(hex, OWN, contacts::contains, requests::contains, blocked::contains);
    }

    private static Set<String> set(final String... keys)
    {
        return keys.length == 0 ? Collections.<String>emptySet() : new HashSet<>(Arrays.asList(keys));
    }

    private static java.util.List<Integer> filled(final int count)
    {
        final AirNewContactModel.RulerPart[] parts = AirNewContactModel.RulerPart.parts(count);
        return Arrays.asList(parts[0].filled, parts[1].filled, parts[2].filled);
    }

    /** The check toxcore puts at the end of an ID, for building one in a test. */
    private static String checkFor(final String keyAndAntispam)
    {
        final int[] check = {0, 0};
        for (int i = 0; i < keyAndAntispam.length() / 2; i++)
        {
            check[i % 2] ^= Integer.parseInt(keyAndAntispam.substring(2 * i, 2 * i + 2), 16);
        }
        return String.format(Locale.ROOT, "%02X%02X", check[0], check[1]);
    }
}

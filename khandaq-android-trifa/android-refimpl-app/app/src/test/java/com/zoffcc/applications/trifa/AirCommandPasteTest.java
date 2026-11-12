package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** "Paste ID" on the command line (E3): what goes on the line from the clipboard's text, and what does not. */
public class AirCommandPasteTest
{
    /** A whole ID with a correct check (the one AirNewContactModelTest and the iOS tests use). */
    private static final String ADDRESS = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5";
    private static final String GROUP_KEY = "88ACED36E3BEC19E5F0A6B7C2D9E1F3A4B5C6D7E8F90A1B2C3D4E5F6A7B8C9D0";

    /**
     * Stands in for ShareActivity.parseKhandaqGroupId, which cannot load in a JVM test: the two link
     * forms, the hex after them, exactly 64 characters, in upper case.
     */
    private static final AirNewContactModel.GroupLinkReader LINKS = link -> {
        final String lower = link.toLowerCase(Locale.ROOT);
        final String rest;
        if (lower.startsWith("khandaq://group/"))
        {
            rest = link.substring("khandaq://group/".length());
        }
        else if (lower.startsWith("khandaq:group:"))
        {
            rest = link.substring("khandaq:group:".length());
        }
        else
        {
            return null;
        }
        final String hex = rest.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F]", "");
        return hex.length() == 64 ? hex : null;
    };

    private static String paste(final String clip)
    {
        return AirNewContactModel.pastedCommand(clip, LINKS);
    }

    @Test
    public void a_whole_id_goes_on_the_line_in_any_usual_shape()
    {
        assertEquals(ADDRESS, paste(ADDRESS));
        assertEquals(ADDRESS, paste(ADDRESS.toLowerCase(Locale.ROOT)));
        assertEquals(ADDRESS, paste("tox:" + ADDRESS));
        assertEquals(ADDRESS, paste("  " + AirNewContactModel.grouped(ADDRESS) + "\n"));
        // A zero-width space between the groups, as some chat apps copy it.
        assertEquals(ADDRESS, paste(AirNewContactModel.grouped(ADDRESS).replace(" ", "\u200B")));
    }

    @Test
    public void a_whole_id_or_a_group_key_is_found_among_words()
    {
        assertEquals(ADDRESS, paste("My ID is " + ADDRESS + ", add me."));
        assertEquals(ADDRESS, paste("«" + ADDRESS + "»"));
        assertEquals(GROUP_KEY, paste("Our group key: " + GROUP_KEY));
    }

    @Test
    public void a_group_key_and_a_group_link_go_on_the_line()
    {
        assertEquals(GROUP_KEY, paste(GROUP_KEY));
        assertEquals(GROUP_KEY, paste(GROUP_KEY.toLowerCase(Locale.ROOT)));
        // A link stays a link: the line then offers only the group, never a contact with that ID.
        assertEquals("khandaq:group:" + GROUP_KEY, paste("khandaq:group:" + GROUP_KEY));
        assertEquals("khandaq://group/" + GROUP_KEY, paste(" khandaq://group/" + GROUP_KEY + " "));
        assertEquals("khandaq:group:" + GROUP_KEY, paste("Join us: khandaq:group:" + GROUP_KEY + " see you"));
    }

    @Test
    public void the_start_of_an_id_goes_on_the_line_as_typed()
    {
        assertEquals(ADDRESS.substring(0, 20), paste(ADDRESS.substring(0, 20)));
        assertEquals(ADDRESS.substring(0, 16), paste(AirNewContactModel.grouped(ADDRESS.substring(0, 16))));
    }

    @Test
    public void words_without_an_id_paste_nothing()
    {
        assertNull(paste(null));
        assertNull(paste(""));
        assertNull(paste("   \n"));
        assertNull(paste("Amina Safina"));
        // Hex letters only: a name, not an ID.
        assertNull(paste("Abbe Cafe Bead"));
        assertNull(paste("https://khandaq.org"));
        // The start of an ID inside a sentence is not taken: only a whole ID or key is.
        assertNull(paste("my ID starts with " + ADDRESS.substring(0, 20)));
        // A link with a short key is not a group link, and its words hold no ID.
        assertNull(paste("khandaq:group:ABC123"));
        assertNull(paste(ADDRESS + "0"));
    }

    @Test
    public void without_a_link_reader_ids_still_paste()
    {
        assertEquals(ADDRESS, AirNewContactModel.pastedCommand(ADDRESS, null));
        assertNull(AirNewContactModel.pastedCommand("khandaq:group:" + GROUP_KEY, null));
    }
}

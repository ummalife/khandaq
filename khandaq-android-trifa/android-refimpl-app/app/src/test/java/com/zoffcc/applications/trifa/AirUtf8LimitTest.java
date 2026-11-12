package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The name and status fields of the You screen (redesign stage 3, E17) keep to what Tox carries:
 * UTF-8 bytes as the native layer sends them, a paste cut after the last whole character, one line.
 */
public class AirUtf8LimitTest
{
    @Test
    public void bytes_are_counted_as_the_native_layer_sends_them()
    {
        for (String text : new String[]{"Bilal", "Билал", "بلال", "比拉尔", "Bilal 😀", "a\uD83Db"})
        {
            assertEquals(text, text.getBytes(StandardCharsets.UTF_8).length,
                         AirUtf8Limit.utf8Length(text, 0, text.length()));
        }
    }

    @Test
    public void typing_within_the_limit_is_taken_as_it_is()
    {
        assertNull(AirUtf8Limit.fit("l", 0, 1, "Bila", 4, 4, 128));
        assertNull(AirUtf8Limit.fit("Билал", 0, 5, "", 0, 0, 10));
    }

    @Test
    public void typing_past_the_limit_stops_at_the_end()
    {
        assertEquals("", AirUtf8Limit.fit("x", 0, 1, "abcd", 4, 4, 4).toString());
        // Two bytes left: one Cyrillic letter fits, the second does not.
        assertEquals("Б", AirUtf8Limit.fit("Би", 0, 2, "ab", 2, 2, 4).toString());
    }

    @Test
    public void a_paste_is_cut_after_the_last_whole_character()
    {
        // An emoji is four bytes and two chars: with three bytes of room it does not go in by half.
        assertEquals("", AirUtf8Limit.fit("😀", 0, 2, "a", 1, 1, 4).toString());
        assertEquals("😀", AirUtf8Limit.fit("😀😀", 0, 4, "", 0, 0, 7).toString());
        // Replacing a selection frees its bytes.
        assertNull(AirUtf8Limit.fit("Bilal", 0, 5, "Muhammad", 0, 8, 5));
    }

    @Test
    public void a_line_break_becomes_a_space()
    {
        assertEquals("Bilal Ahmad", AirUtf8Limit.fit("Bilal\nAhmad", 0, 11, "", 0, 0, 128).toString());
        assertEquals("a b", AirUtf8Limit.fit("a\r\nb", 0, 4, "", 0, 0, 128).toString());
        assertEquals("a b", AirUtf8Limit.fit("a\nb c", 0, 5, "", 0, 0, 3).toString());
    }

    @Test
    public void the_name_and_status_limits_are_toxcore_ones()
    {
        assertEquals(128, ToxVars.TOX_MAX_NAME_LENGTH);
        assertEquals(1007, ToxVars.TOX_MAX_STATUS_MESSAGE_LENGTH);
    }
}

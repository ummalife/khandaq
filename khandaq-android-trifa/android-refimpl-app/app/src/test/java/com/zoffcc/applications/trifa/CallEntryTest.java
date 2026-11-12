package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The length of a call in its transcript entry (redesign stage 3, E9): "Call, 4:12", minutes and seconds,
 * and the hours once there are any, as the iOS entry writes it. A stored entry is read back from its
 * words in any language the app ships, so it can be said again in the language of the app now.
 */
public class CallEntryTest
{
    @Test
    public void a_call_is_minutes_and_two_digit_seconds()
    {
        assertEquals("0:00", HelperCall.callDuration(0));
        assertEquals("0:07", HelperCall.callDuration(7));
        assertEquals("4:12", HelperCall.callDuration(252));
        assertEquals("59:59", HelperCall.callDuration(3599));
    }

    @Test
    public void an_hour_and_more_shows_the_hours()
    {
        assertEquals("1:00:00", HelperCall.callDuration(3600));
        assertEquals("1:02:03", HelperCall.callDuration(3723));
        assertEquals("12:00:01", HelperCall.callDuration(43201));
    }

    @Test
    public void a_clock_that_went_back_is_no_time_at_all()
    {
        assertEquals("0:00", HelperCall.callDuration(-30));
    }

    /** {missed, no answer, entry format} as the four string files have them. */
    private static final String[][] WORDS = {
            {"Missed call", "No answer", "Call, %1$s"},
            {"Пропущенный звонок", "Без ответа", "Звонок, %1$s"},
            {"مكالمة فائتة", "لم يتم الرد", "مكالمة، %1$s"},
            {"未接来电", "无人接听", "通话，%1$s"},
    };

    @Test
    public void a_stored_entry_is_read_back_in_any_language()
    {
        assertEquals(HelperCall.StoredCall.MISSED, HelperCall.readStoredCall("Пропущенный звонок", WORDS).kind);
        assertEquals(HelperCall.StoredCall.NO_ANSWER, HelperCall.readStoredCall("无人接听", WORDS).kind);
        assertEquals(HelperCall.StoredCall.MISSED, HelperCall.readStoredCall("Missed call", WORDS).kind);
        final HelperCall.StoredCall call = HelperCall.readStoredCall("Звонок, 0:47", WORDS);
        assertEquals(HelperCall.StoredCall.ANSWERED, call.kind);
        assertEquals("0:47", call.length);
        assertEquals("4:12", HelperCall.readStoredCall("通话，4:12", WORDS).length);
    }

    @Test
    public void a_length_in_arabic_digits_comes_back_in_ascii()
    {
        final HelperCall.StoredCall call = HelperCall.readStoredCall(
                "مكالمة، \u0661:\u0660\u0662:\u0660\u0663", WORDS);
        assertEquals(HelperCall.StoredCall.ANSWERED, call.kind);
        assertEquals("1:02:03", call.length);
    }

    @Test
    public void other_words_are_not_a_call_entry()
    {
        assertNull(HelperCall.readStoredCall("Outgoing voice call", WORDS));
        assertNull(HelperCall.readStoredCall("Call, soon", WORDS));
        assertNull(HelperCall.readStoredCall("Call, ", WORDS));
        assertNull(HelperCall.readStoredCall("Call, :47", WORDS));
        assertNull(HelperCall.readStoredCall("Call, 0:47 and more", WORDS));
        assertNull(HelperCall.readStoredCall(null, WORDS));
        assertNull(HelperCall.readStoredCall("Missed call", null));
    }
}

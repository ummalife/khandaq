package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (owner, 28.11): the place a person shares is where they are now (LocationFixPicker, as on the iPhone), and a
 * place is named as what it is in a quote, a list and a notification, not shown as the text it travels in.
 */
public class LocationSharingTest
{
    private static final double LAT = 41.0082;
    private static final double LON = 28.9784;

    @Test
    public void a_fix_from_before_the_share_is_not_where_the_person_is()
    {
        final LocationFixPicker picker = new LocationFixPicker();
        // The last fix the system had, a minute old, may be from another town.
        assertFalse(picker.consider(LAT, LON, true, 5f, 60_000L));
        assertFalse(picker.hasFix());
        assertFalse(picker.isSettled(ShareLocationHelper.MAX_WAIT_MS));
    }

    @Test
    public void a_fix_without_an_accuracy_is_not_taken()
    {
        final LocationFixPicker picker = new LocationFixPicker();
        assertFalse(picker.consider(LAT, LON, false, 0f, 0L));
        assertFalse(picker.consider(LAT, LON, true, -1f, 0L));
        assertFalse(picker.consider(LAT, LON, true, Float.NaN, 0L));
        assertFalse(picker.consider(Double.NaN, LON, true, 5f, 0L));
        assertFalse(picker.consider(91.0, LON, true, 5f, 0L));
        assertFalse(picker.hasFix());
    }

    @Test
    public void the_most_accurate_fresh_fix_wins()
    {
        final LocationFixPicker picker = new LocationFixPicker();
        assertTrue(picker.consider(41.1, LON, true, 1_400f, 1_000L));
        assertTrue(picker.consider(LAT, LON, true, 35f, 0L));
        assertFalse(picker.consider(41.2, LON, true, 90f, 0L));
        assertEquals(LAT, picker.latitude(), 0.0);
        assertEquals(35f, picker.accuracyM(), 0f);
    }

    @Test
    public void a_good_fix_ends_the_wait_at_once_and_a_fair_one_once_patience_runs_out()
    {
        final LocationFixPicker picker = new LocationFixPicker();
        picker.consider(LAT, LON, true, 50f, 0L);
        assertFalse("a fair fix waits a moment for a better one", picker.isSettled(500L));
        assertTrue(picker.isSettled(LocationFixPicker.PATIENCE_MS));

        picker.consider(LAT, LON, true, 12f, 0L);
        assertTrue("a good fix goes at once", picker.isSettled(500L));
    }

    @Test
    public void a_coarse_fix_waits_till_the_end_of_the_wait()
    {
        final LocationFixPicker picker = new LocationFixPicker();
        picker.consider(LAT, LON, true, 3_000f, 0L);
        assertFalse(picker.isSettled(LocationFixPicker.PATIENCE_MS + 1_000L));
        assertTrue("when the wait ends, the best fresh fix there is still goes", picker.hasFix());
    }

    @Test
    public void a_recent_fix_is_kept_for_when_no_fresh_one_comes()
    {
        final LocationFixPicker recent = new LocationFixPicker(ShareLocationHelper.FALLBACK_MAX_AGE_MS);
        assertTrue(recent.consider(LAT, LON, true, 30f, 90_000L));
        assertFalse(recent.consider(LAT, LON, true, 10f, 10 * 60_000L));
        assertEquals(30f, recent.accuracyM(), 0f);
    }

    @Test
    public void a_quoted_place_is_named_while_the_header_keeps_its_wire_text()
    {
        final String place = HelperLocationMessage.encode(LAT, LON);
        final double[] parsed = HelperLocationMessage.parse(place);
        assertEquals("Location \u00B7 41.00820, 28.97840",
                     HelperLocationMessage.caption("Location", parsed[0], parsed[1]));

        final MessageReplyHelper.ReplyMeta meta = new MessageReplyHelper.ReplyMeta();
        meta.senderName = "Amina";
        meta.previewText = place;
        assertTrue("each reader names the place in its own language",
                   MessageReplyHelper.encode(meta, "On my way").contains(place));
    }

    @Test
    public void coordinates_read_the_same_in_every_language()
    {
        assertEquals("41.00820, 28.97840", HelperLocationMessage.coordinatesText(LAT, LON));
        assertEquals("-22.90685, -43.17290", HelperLocationMessage.coordinatesText(-22.90685, -43.1729));
    }
}

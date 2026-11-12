package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AirContactMarksTest
{
    private static final String KEY = "7F3A91C05D2E7F3A91C05D2E7F3A91C05D2E7F3A91C05D2E7F3A91C05D2E7F3A";
    private static final long NOW_MS = 1_794_000_000_000L;
    private static final long NOW_HOUR = (NOW_MS / 1000L) / 3600L * 3600L;
    private static final long WEEK = AirConversationModel.KEEP_SECONDS;

    @Test
    public void hashed_key_is_sixteen_bytes_of_sha256_over_the_upper_cased_key()
    {
        // printf '%s' KEY | shasum -a 256, first 32 hex digits: the same vector as AirLocalStore.hashedKey.
        assertEquals("dd24469a8c76b2264024337d32c7f9cf", AirContactMarks.hashedKey(KEY));
        assertEquals("dd24469a8c76b2264024337d32c7f9cf", AirContactMarks.hashedKey(KEY.toLowerCase(java.util.Locale.ROOT)));
        assertEquals("dd24469a8c76b2264024337d32c7f9cf", AirContactMarks.hashedKey(" " + KEY + "\n"));
        assertEquals("99d27dd0ecd916c2cb99316110328514",
                     AirContactMarks.hashedKey("0000000000000000000000000000000000000000000000000000000000000abc"));
        assertEquals("", AirContactMarks.hashedKey(""));
        assertEquals("", AirContactMarks.hashedKey(null));
    }

    @Test
    public void an_hour_is_added_once_and_old_hours_go()
    {
        final String key = AirContactMarks.hashedKey(KEY);
        final Map<String, AirContactMarks.Contact> empty = new HashMap<>();

        final Map<String, AirContactMarks.Contact> one = AirContactMarks.withHour(empty, key, NOW_HOUR, NOW_MS);
        assertNotNull(one);
        assertEquals(Arrays.asList(NOW_HOUR), one.get(key).hours);
        // The same hour again changes nothing and asks for no write.
        assertNull(AirContactMarks.withHour(one, key, NOW_HOUR, NOW_MS));

        final Map<String, AirContactMarks.Contact> withOld = new HashMap<>();
        withOld.put(key, new AirContactMarks.Contact(Arrays.asList(NOW_HOUR - WEEK - 3600L, NOW_HOUR - 7200L), 5L));
        final Map<String, AirContactMarks.Contact> next = AirContactMarks.withHour(withOld, key, NOW_HOUR, NOW_MS);
        assertNotNull(next);
        assertEquals(Arrays.asList(NOW_HOUR - 7200L, NOW_HOUR), next.get(key).hours);
        assertEquals(5L, next.get(key).verifiedMs);
        // The input is left as it was.
        assertEquals(2, withOld.get(key).hours.size());
    }

    @Test
    public void pruning_drops_old_hours_and_contacts_left_with_nothing()
    {
        final Map<String, AirContactMarks.Contact> marks = new HashMap<>();
        marks.put("a", new AirContactMarks.Contact(Arrays.asList(NOW_HOUR - WEEK - 1L, NOW_HOUR), 0L));
        marks.put("b", new AirContactMarks.Contact(Arrays.asList(NOW_HOUR - WEEK - 1L), 0L));
        marks.put("c", new AirContactMarks.Contact(Arrays.asList(NOW_HOUR - WEEK - 1L), 1234L));

        final Map<String, AirContactMarks.Contact> kept = AirContactMarks.pruned(marks, NOW_MS);
        assertEquals(Arrays.asList(NOW_HOUR), kept.get("a").hours);
        assertFalse(kept.containsKey("b"));
        // A verified key stays even without hours.
        assertTrue(kept.get("c").hours.isEmpty());
        assertEquals(1234L, kept.get("c").verifiedMs);
    }

    @Test
    public void verifying_and_taking_it_back()
    {
        final Map<String, AirContactMarks.Contact> verified =
                AirContactMarks.withVerified(new HashMap<String, AirContactMarks.Contact>(), "k", 99L);
        assertEquals(99L, verified.get("k").verifiedMs);
        // Taken back with no hours left: the contact goes.
        assertFalse(AirContactMarks.withVerified(verified, "k", 0L).containsKey("k"));

        final Map<String, AirContactMarks.Contact> withHours = new HashMap<>();
        withHours.put("k", new AirContactMarks.Contact(Arrays.asList(NOW_HOUR), 99L));
        final Map<String, AirContactMarks.Contact> unverified = AirContactMarks.withVerified(withHours, "k", 0L);
        assertEquals(0L, unverified.get("k").verifiedMs);
        assertEquals(Arrays.asList(NOW_HOUR), unverified.get("k").hours);
    }

    @Test
    public void encode_and_decode_round_trip_and_survive_damage()
    {
        final Map<String, AirContactMarks.Contact> marks = new HashMap<>();
        marks.put("a", new AirContactMarks.Contact(Arrays.asList(NOW_HOUR, NOW_HOUR - 3600L, NOW_HOUR), 0L));
        marks.put("b", new AirContactMarks.Contact(Arrays.<Long>asList(), 77L));

        final String json = AirContactMarks.encode(marks);
        final Map<String, AirContactMarks.Contact> back = AirContactMarks.decode(json);
        assertEquals(2, back.size());
        // Hours come back sorted and without the repeat.
        assertEquals(Arrays.asList(NOW_HOUR - 3600L, NOW_HOUR), back.get("a").hours);
        assertEquals(0L, back.get("a").verifiedMs);
        assertEquals(77L, back.get("b").verifiedMs);
        assertEquals(json, AirContactMarks.encode(back));

        assertTrue(AirContactMarks.decode(null).isEmpty());
        assertTrue(AirContactMarks.decode("").isEmpty());
        assertTrue(AirContactMarks.decode("not json").isEmpty());
        assertTrue(AirContactMarks.decode("[1,2]").isEmpty());
        final Map<String, AirContactMarks.Contact> partly = AirContactMarks.decode(
                "{\"a\":{\"hours\":[1,\"x\",3]},\"b\":5,\"c\":{\"hours\":[]},\"d\":{\"verified_ms\":\"y\",\"hours\":[4]}}");
        assertEquals(Arrays.asList(1L, 3L), partly.get("a").hours);
        assertFalse(partly.containsKey("b"));
        assertFalse(partly.containsKey("c"));
        assertEquals(Arrays.asList(4L), partly.get("d").hours);
        assertEquals(0L, partly.get("d").verifiedMs);
    }
}

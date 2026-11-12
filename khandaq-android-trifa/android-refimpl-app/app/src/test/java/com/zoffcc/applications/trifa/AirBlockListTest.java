package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The rules of the local block list (design/DESIGN.md, "Block, on every client"). */
public class AirBlockListTest
{
    private static final String KEY_A = "7F3A91C05D2E7F3A91C05D2E7F3A91C05D2E7F3A91C05D2E7F3A91C05D2E7F3A";
    private static final String KEY_B = "0000000000000000000000000000000000000000000000000000000000000ABC";
    /** KEY_A as a whole Tox ID: the key, the nospam and the checksum. */
    private static final String ID_A = KEY_A + "1A2B3C4D" + "5E6F";

    @Test
    public void a_key_is_64_hex_characters_kept_in_upper_case()
    {
        assertEquals(KEY_A, AirBlockList.normalizedKey(KEY_A));
        assertEquals(KEY_A, AirBlockList.normalizedKey(KEY_A.toLowerCase(Locale.ROOT)));
        assertEquals(KEY_A, AirBlockList.normalizedKey("  " + KEY_A + "\n"));
    }

    @Test
    public void anything_else_is_not_a_key()
    {
        assertNull(AirBlockList.normalizedKey(null));
        assertNull(AirBlockList.normalizedKey(""));
        assertNull(AirBlockList.normalizedKey(KEY_A.substring(1)));
        assertNull(AirBlockList.normalizedKey(KEY_A + "0"));
        // A whole ID is not taken: the list holds keys, and the dossier hands it the key.
        assertNull(AirBlockList.normalizedKey(ID_A));
        assertNull(AirBlockList.normalizedKey(KEY_A.substring(0, 63) + "G"));
        assertNull(AirBlockList.normalizedKey(KEY_A.substring(0, 32) + " " + KEY_A.substring(32)));
    }

    @Test
    public void blocking_appends_oldest_first()
    {
        List<AirBlockList.Entry> list = Collections.emptyList();
        list = AirBlockList.withBlocked(list, KEY_A, 1000L);
        list = AirBlockList.withBlocked(list, KEY_B.toLowerCase(Locale.ROOT), 2000L);
        assertEquals(2, list.size());
        assertEquals(KEY_A, list.get(0).publicKey);
        assertEquals(1000L, list.get(0).blockedMs);
        assertEquals(KEY_B, list.get(1).publicKey);
        assertEquals(2000L, list.get(1).blockedMs);
    }

    @Test
    public void a_key_blocked_again_keeps_its_place_and_date()
    {
        List<AirBlockList.Entry> list = Collections.emptyList();
        list = AirBlockList.withBlocked(list, KEY_A, 1000L);
        list = AirBlockList.withBlocked(list, KEY_B, 2000L);
        final List<AirBlockList.Entry> again = AirBlockList.withBlocked(list, KEY_A.toLowerCase(Locale.ROOT), 3000L);
        assertSame(list, again);
        assertEquals(KEY_A, again.get(0).publicKey);
        assertEquals(1000L, again.get(0).blockedMs);
    }

    @Test
    public void an_invalid_key_changes_nothing()
    {
        final List<AirBlockList.Entry> list = AirBlockList.withBlocked(Collections.emptyList(), KEY_A, 1000L);
        assertSame(list, AirBlockList.withBlocked(list, ID_A, 2000L));
        assertSame(list, AirBlockList.withBlocked(list, "", 2000L));
        assertSame(list, AirBlockList.without(list, ID_A));
    }

    @Test
    public void unblocking_removes_only_that_key()
    {
        List<AirBlockList.Entry> list = Collections.emptyList();
        list = AirBlockList.withBlocked(list, KEY_A, 1000L);
        list = AirBlockList.withBlocked(list, KEY_B, 2000L);
        final List<AirBlockList.Entry> rest = AirBlockList.without(list, KEY_A.toLowerCase(Locale.ROOT));
        assertEquals(1, rest.size());
        assertEquals(KEY_B, rest.get(0).publicKey);
        assertSame(rest, AirBlockList.without(rest, KEY_A));
    }

    @Test
    public void past_the_cap_the_oldest_key_goes()
    {
        List<AirBlockList.Entry> list = Collections.emptyList();
        for (int i = 0; i <= AirBlockList.MAX_KEYS; i++)
        {
            list = AirBlockList.withBlocked(list, String.format(Locale.ROOT, "%064X", i), i);
        }
        assertEquals(AirBlockList.MAX_KEYS, list.size());
        assertEquals(String.format(Locale.ROOT, "%064X", 1), list.get(0).publicKey);
    }

    @Test
    public void a_request_from_a_listed_key_is_dropped_whatever_its_case()
    {
        final List<AirBlockList.Entry> list = AirBlockList.withBlocked(Collections.emptyList(), KEY_A, 1000L);
        // The request callback hands over the key in upper case; the rule holds for any case anyway.
        assertTrue(AirBlockList.contains(list, KEY_A));
        assertTrue(AirBlockList.contains(list, KEY_A.toLowerCase(Locale.ROOT)));
        assertFalse(AirBlockList.contains(list, KEY_B));
    }

    @Test
    public void nothing_is_dropped_without_a_valid_key_or_a_list()
    {
        final List<AirBlockList.Entry> list = AirBlockList.withBlocked(Collections.emptyList(), KEY_A, 1000L);
        assertFalse(AirBlockList.contains(list, null));
        assertFalse(AirBlockList.contains(list, ""));
        assertFalse(AirBlockList.contains(list, ID_A));
        assertFalse(AirBlockList.contains(null, KEY_A));
        assertFalse(AirBlockList.contains(Collections.emptyList(), KEY_A));
    }

    @Test
    public void the_stored_list_reads_back_as_written()
    {
        List<AirBlockList.Entry> list = Collections.emptyList();
        list = AirBlockList.withBlocked(list, KEY_B, 2000L);
        list = AirBlockList.withBlocked(list, KEY_A, 1000L);
        final List<AirBlockList.Entry> back = AirBlockList.decode(AirBlockList.encode(list));
        assertEquals(2, back.size());
        assertEquals(KEY_B, back.get(0).publicKey);
        assertEquals(2000L, back.get(0).blockedMs);
        assertEquals(KEY_A, back.get(1).publicKey);
        assertEquals(1000L, back.get(1).blockedMs);
    }

    @Test
    public void a_damaged_value_never_blocks_what_it_should_not()
    {
        assertTrue(AirBlockList.decode(null).isEmpty());
        assertTrue(AirBlockList.decode("").isEmpty());
        assertTrue(AirBlockList.decode("{").isEmpty());
        assertTrue(AirBlockList.decode("{}").isEmpty());
        assertTrue(AirBlockList.decode("[1, \"x\", null, []]").isEmpty());
        final String json = "[{\"public_key\":\"" + ID_A + "\",\"blocked_ms\":1},"
                            + "{\"public_key\":\"" + KEY_A.toLowerCase(Locale.ROOT) + "\",\"blocked_ms\":\"soon\"},"
                            + "{\"public_key\":\"" + KEY_A + "\",\"blocked_ms\":5},"
                            + "{\"blocked_ms\":7},"
                            + "{\"public_key\":\"" + KEY_B + "\",\"blocked_ms\":-4}]";
        final List<AirBlockList.Entry> list = AirBlockList.decode(json);
        assertEquals(2, list.size());
        assertEquals(KEY_A, list.get(0).publicKey);
        assertEquals(0L, list.get(0).blockedMs);
        assertEquals(KEY_B, list.get(1).publicKey);
        assertEquals(0L, list.get(1).blockedMs);
    }
}

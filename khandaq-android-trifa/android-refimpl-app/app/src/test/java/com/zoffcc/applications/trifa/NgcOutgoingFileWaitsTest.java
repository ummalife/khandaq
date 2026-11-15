package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.zoffcc.applications.trifa.NgcOutgoingFileWaits.PEER_SETTLE_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56, QA live): our group files that wait for a member (NgcOutgoingFileWaits).
 *
 * QA (GW5-GW10): a file sent into a group with nobody else online was said not sent after about a minute, and was
 * not sent when the member came online. It waits now, and goes once its group has had someone for a moment.
 */
public class NgcOutgoingFileWaitsTest
{
    private static final String GROUP = "926C33466257EDA8837C55D11580435A0A8B4482A451D5A427032C39D8947DC9";
    private static final String OTHER_GROUP = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
    private static final long T0 = 7_000_000L;

    private static List<String> msgs(final List<NgcOutgoingFileWaits.File> files)
    {
        final List<String> out = new ArrayList<>();
        for (final NgcOutgoingFileWaits.File f : files)
        {
            out.add(f.msgIdHash);
        }
        return out;
    }

    @Test
    public void a_file_waits_until_its_group_has_had_a_member_for_a_moment()
    {
        final NgcOutgoingFileWaits waits = new NgcOutgoingFileWaits();
        waits.add(GROUP, "AA01");
        assertTrue(waits.contains(GROUP, "aa01"));

        // Nobody for an hour: the file stays, it is not given up.
        for (long now = T0; now < T0 + 3_600_000L; now += 4_000L)
        {
            assertTrue(waits.due(GROUP, false, now).isEmpty());
        }
        assertTrue(waits.contains(GROUP, "AA01"));

        // A member: not at once, toxcore reports it before it has our peer info.
        final long came = T0 + 3_600_000L;
        assertTrue(waits.due(GROUP, true, came).isEmpty());
        assertTrue(waits.due(GROUP, true, came + PEER_SETTLE_MS - 1L).isEmpty());
        assertEquals(Arrays.asList("aa01"), msgs(waits.due(GROUP, true, came + PEER_SETTLE_MS)));
        // It no longer waits: the send that takes it brings it back if it finds nobody again.
        assertFalse(waits.contains(GROUP, "aa01"));
        assertTrue(waits.due(GROUP, true, came + 10L * PEER_SETTLE_MS).isEmpty());
    }

    @Test
    public void a_member_that_leaves_again_before_the_moment_is_over_starts_it_again()
    {
        final NgcOutgoingFileWaits waits = new NgcOutgoingFileWaits();
        waits.add(GROUP, "aa01");
        assertTrue(waits.due(GROUP, true, T0).isEmpty());
        assertTrue(waits.due(GROUP, false, T0 + 1_000L).isEmpty());
        assertTrue(waits.due(GROUP, true, T0 + PEER_SETTLE_MS).isEmpty());
        assertTrue(waits.due(GROUP, true, T0 + PEER_SETTLE_MS + 1_000L).isEmpty());
        assertEquals(1, waits.due(GROUP, true, T0 + 2L * PEER_SETTLE_MS).size());
    }

    @Test
    public void the_files_of_a_group_go_in_the_order_they_began_to_wait_and_only_they()
    {
        final NgcOutgoingFileWaits waits = new NgcOutgoingFileWaits();
        waits.add(GROUP, "aa01");
        waits.add(OTHER_GROUP, "bb01");
        waits.add(GROUP, "aa02");
        waits.add(GROUP, "aa01");
        waits.add(GROUP.toLowerCase(java.util.Locale.ROOT), "AA03");
        assertEquals(Arrays.asList(GROUP.toLowerCase(java.util.Locale.ROOT), OTHER_GROUP), waits.groups());

        assertTrue(waits.due(GROUP, true, T0).isEmpty());
        assertEquals(Arrays.asList("aa01", "aa02", "aa03"), msgs(waits.due(GROUP, true, T0 + PEER_SETTLE_MS)));
        assertTrue(waits.contains(OTHER_GROUP, "bb01"));
        assertEquals(Arrays.asList(OTHER_GROUP), waits.groups());
    }

    @Test
    public void a_file_that_finds_nobody_again_waits_again_from_the_start()
    {
        final NgcOutgoingFileWaits waits = new NgcOutgoingFileWaits();
        waits.add(GROUP, "aa01");
        waits.add(GROUP, "aa02");
        assertTrue(waits.due(GROUP, true, T0).isEmpty());
        // aa01 began to go meanwhile (a tap on "Send again") and found nobody: the group's moment starts again.
        waits.add(GROUP, "aa01");
        assertTrue(waits.due(GROUP, true, T0 + PEER_SETTLE_MS).isEmpty());
        assertEquals(Arrays.asList("aa01", "aa02"), msgs(waits.due(GROUP, true, T0 + 2L * PEER_SETTLE_MS)));
    }

    @Test
    public void a_file_that_goes_or_is_cancelled_no_longer_waits()
    {
        final NgcOutgoingFileWaits waits = new NgcOutgoingFileWaits();
        waits.add(GROUP, "aa01");
        waits.add(GROUP, "aa02");
        assertTrue(waits.remove(GROUP, "AA01"));
        assertFalse(waits.remove(GROUP, "aa01"));
        assertTrue(waits.due(GROUP, true, T0).isEmpty());
        assertEquals(Arrays.asList("aa02"), msgs(waits.due(GROUP, true, T0 + PEER_SETTLE_MS)));

        // The group's last file gone, a file that begins to wait later waits its own moment.
        waits.add(GROUP, "aa03");
        assertTrue(waits.due(GROUP, true, T0 + PEER_SETTLE_MS + 1L).isEmpty());
        assertTrue(waits.remove(GROUP, "aa03"));
        assertTrue(waits.groups().isEmpty());
        waits.add(GROUP, "aa04");
        assertTrue(waits.due(GROUP, true, T0 + 2L * PEER_SETTLE_MS).isEmpty());
    }

    @Test
    public void nothing_is_taken_for_missing_ids()
    {
        final NgcOutgoingFileWaits waits = new NgcOutgoingFileWaits();
        waits.add(null, "aa01");
        waits.add(GROUP, null);
        assertTrue(waits.groups().isEmpty());
        assertFalse(waits.contains(null, "aa01"));
        assertFalse(waits.remove(GROUP, null));
        assertTrue(waits.due(null, true, T0).isEmpty());
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57, cross-platform QA): the resends of the chunks members ask for, one per file and member
 * (NgcFileServes). A request merges into the resend of its member while that one is queued or running.
 *
 * QA: a request of more than 256 chunks comes as several packets, and an iPhone asks again every 2.5 s while its file
 * is stalled. A cooldown of 2.5 s per file dropped the packets after the first and some of the repeats, and one resend
 * per file dropped every request of a member while the resend of another member ran.
 */
public class NgcFileServesTest
{
    private static final String MEMBER_A = "groupid:msgid:1";
    private static final String MEMBER_B = "groupid:msgid:2";

    private static boolean[] chunks(final int total, final int... indices)
    {
        final boolean[] wanted = new boolean[total];
        for (final int index : indices)
        {
            wanted[index] = true;
        }
        return wanted;
    }

    private static int[] range(final int from, final int to)
    {
        final int[] indices = new int[to - from];
        for (int i = from; i < to; i++)
        {
            indices[i - from] = i;
        }
        return indices;
    }

    /** The chunks the resend sends from chunk {@code from} on, in their order, as its worker takes them. */
    private static List<Integer> sendAll(final NgcFileServes serves, final String key, final int from)
    {
        final List<Integer> sent = new ArrayList<>();
        int at = from;
        int idx;
        while ((idx = serves.takeNext(key, at)) >= 0)
        {
            sent.add(idx);
            at = idx + 1;
        }
        return sent;
    }

    private static List<Integer> list(final int... indices)
    {
        final List<Integer> out = new ArrayList<>();
        for (final int index : indices)
        {
            out.add(index);
        }
        return out;
    }

    @Test
    public void the_packets_of_one_request_go_in_one_resend()
    {
        // 600 missing chunks of a file of 1200 come as packets of 256, 256 and 88, within milliseconds.
        final NgcFileServes serves = new NgcFileServes();
        assertTrue("the first starts the resend", serves.ask(MEMBER_A, chunks(1200, range(0, 256))));
        assertFalse("merged", serves.ask(MEMBER_A, chunks(1200, range(256, 512))));
        assertFalse("merged", serves.ask(MEMBER_A, chunks(1200, range(512, 600))));
        assertEquals(list(range(0, 600)), sendAll(serves, MEMBER_A, 0));

        serves.end(MEMBER_A);
        assertTrue("the next request starts a new resend", serves.ask(MEMBER_A, chunks(1200, 5)));
    }

    @Test
    public void a_repeat_while_the_resend_runs_adds_the_chunks_ahead_and_leaves_those_behind()
    {
        final NgcFileServes serves = new NgcFileServes();
        assertTrue(serves.ask(MEMBER_A, chunks(100, 10, 20, 30)));
        assertEquals(10, serves.takeNext(MEMBER_A, 0));
        assertEquals(20, serves.takeNext(MEMBER_A, 11));
        // The member asks again (an iPhone, 2.5 s later): 10 and 20 may still be on their way; 25 and 40 are new.
        assertFalse(serves.ask(MEMBER_A, chunks(100, 10, 20, 25, 30, 40)));
        assertEquals(list(25, 30, 40), sendAll(serves, MEMBER_A, 21));
        // It ended: what it passed is dropped, and the member's next request asks for it again.
        serves.end(MEMBER_A);
        assertEquals(-1, serves.takeNext(MEMBER_A, 0));
        assertTrue(serves.ask(MEMBER_A, chunks(100, 10)));
        assertEquals(list(10), sendAll(serves, MEMBER_A, 0));
    }

    @Test
    public void every_member_has_a_resend_of_its_own()
    {
        final NgcFileServes serves = new NgcFileServes();
        assertTrue(serves.ask(MEMBER_A, chunks(10, 1, 4)));
        assertTrue("not dropped while the resend of another member runs", serves.ask(MEMBER_B, chunks(10, 2)));
        assertEquals(list(1, 4), sendAll(serves, MEMBER_A, 0));
        assertEquals(list(2), sendAll(serves, MEMBER_B, 0));
        serves.end(MEMBER_A);
        assertFalse("the resend of the other member goes on", serves.ask(MEMBER_B, chunks(10, 3)));
        assertEquals(list(3), sendAll(serves, MEMBER_B, 3));
    }

    @Test
    public void a_request_keeps_its_chunks_and_a_longer_one_grows_them()
    {
        final NgcFileServes serves = new NgcFileServes();
        final boolean[] wanted = chunks(4, 1);
        assertTrue(serves.ask(MEMBER_A, wanted));
        // The caller's array stays its own.
        wanted[2] = true;
        assertEquals(1, serves.takeNext(MEMBER_A, 0));
        assertEquals(-1, serves.takeNext(MEMBER_A, 2));
        assertFalse(serves.ask(MEMBER_A, chunks(8, 6)));
        assertEquals(6, serves.takeNext(MEMBER_A, 2));
    }

    @Test
    public void nothing_is_sent_without_a_resend()
    {
        final NgcFileServes serves = new NgcFileServes();
        assertEquals(-1, serves.takeNext(MEMBER_A, 0));
        serves.end(MEMBER_A);
        assertTrue(serves.ask(MEMBER_A, chunks(3)));
        assertEquals("a request of nothing sends nothing", -1, serves.takeNext(MEMBER_A, 0));
    }
}

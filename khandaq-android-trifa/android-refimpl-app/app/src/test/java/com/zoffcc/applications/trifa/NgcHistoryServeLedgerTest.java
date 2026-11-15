package com.zoffcc.applications.trifa;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56) — what a history serve sends to one peer ({@link NgcHistoryServeLedger}).
 *
 * <p>QA live 2026-11-15: after a 64-part group text, Android kept serving group history at 200-370
 * packets a minute for over an hour. Every serve was the whole window, and the churn of one member
 * served every member again. {@link #theQaStormStopsOnceThePeersHoldTheRows} replays that churn; the
 * other tests pin the rules one at a time, and that a member who joins still gets the history.
 */
public class NgcHistoryServeLedgerTest
{
    private static final String GROUP = "DFAE16E37E8924C6689E35CD0B6EAF6A0D15540BBE8B17A6CE65E5FAFE1C8B40";
    private static final String SELF = "A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1A1";
    private static final String PEER_B = "B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2B2";
    private static final String PEER_IOS = "C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3C3";
    /** A member that has left: its rows reached the others through us only. */
    private static final String PEER_GONE = "D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4D4";
    private static final String NEWCOMER = "E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5E5";
    private static final long T0 = 1_700_000_000_000L;
    /** SystemClock.uptimeMillis() at T0: the monotonic clock counts from the boot, two hours ago. */
    private static final long U0 = 7_200_000L;

    private static final class Row
    {
        final String id;
        final String author;
        final String[] confirmedBy;

        Row(final String id, final String author, final String... confirmedBy)
        {
            this.id = id;
            this.author = author;
            this.confirmedBy = confirmedBy;
        }
    }

    private static int nextId = 0x10000000;

    private static Row row(final String author, final String... confirmedBy)
    {
        return new Row(String.format(Locale.ROOT, "%08x", nextId++), author, confirmedBy);
    }

    /** The monotonic clock at wall time nowMs, for a phone that has not slept since T0. */
    private static long awake(final long nowMs)
    {
        return U0 + (nowMs - T0);
    }

    /** What sync_group_message_history does with the rows of one serve; returns the rows sent. */
    private static int serve(final String peer, final List<Row> rows, final long nowMs)
    {
        return serve(peer, rows, nowMs, awake(nowMs));
    }

    /** The serve the last call of {@link #serve} made. */
    private static NgcHistoryServeLedger.Serve lastServe;

    private static int serve(final String peer, final List<Row> rows, final long nowMs, final long nowUptimeMs)
    {
        final NgcHistoryServeLedger.Serve s = NgcHistoryServeLedger.beginServe(GROUP, peer, nowMs);
        lastServe = s;
        int sent = 0;
        for (final Row r : rows)
        {
            if (NgcHistoryServeLedger.shouldSend(s, r.id, r.author, r.confirmedBy))
            {
                sent++;
                NgcHistoryServeLedger.markQueued(s, r.id, nowUptimeMs);
            }
        }
        return sent;
    }

    /** The member's exit as the exit callback reports it, on a phone that has not slept since T0. */
    private static void exited(final String peer, final long nowMs)
    {
        NgcHistoryServeLedger.onPeerExited(GROUP, peer, nowMs, awake(nowMs));
    }

    /** The member's join as the join callback reports it, on a phone that has not slept since T0. */
    private static void joined(final String peer, final long nowMs)
    {
        NgcHistoryServeLedger.onPeerJoined(GROUP, peer, nowMs, awake(nowMs));
    }

    private static List<Row> rowsBy(final List<Row> rows, final String author)
    {
        final List<Row> out = new ArrayList<>();
        for (final Row r : rows)
        {
            if (r.author.equals(author))
            {
                out.add(r);
            }
        }
        return out;
    }

    @Before
    public void reset()
    {
        NgcHistoryServeLedger.resetAllForTest();
        NgcHistoryRequestPolicy.resetAllForTest();
    }

    /**
     * The QA group: us, an Android phone and an iPhone; 32 older rows (ours, theirs and four of a member
     * who left) and our 64-part text. Both members confirmed our rows and served each other's to us
     * before the churn, as in the logs (128 receipts for the 64 parts). Then the Android member times
     * out 58 s after every join and is back 5 s later, and the iPhone drops every 3 minutes for 10 s.
     * Every join and exit serves every member present, again 46 s later (the resync), each member at
     * most every 20 s (NgcHistoryRequestPolicy), as the callbacks do.
     */
    @Test
    public void theQaStormStopsOnceThePeersHoldTheRows()
    {
        final List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++)
        {
            rows.add(row(SELF));
        }
        for (int i = 0; i < 10; i++)
        {
            rows.add(row(PEER_B));
            rows.add(row(PEER_IOS));
        }
        for (int i = 0; i < 4; i++)
        {
            rows.add(row(PEER_GONE));
        }
        for (int i = 0; i < 64; i++)
        {
            rows.add(row(SELF));
        }
        assertEquals(100, rows.size());

        for (final Row r : rowsBy(rows, SELF))
        {
            NgcHistoryServeLedger.markHeld(GROUP, PEER_B, r.id);
            NgcHistoryServeLedger.markHeld(GROUP, PEER_IOS, r.id);
        }
        for (final Row r : rowsBy(rows, PEER_IOS))
        {
            NgcHistoryServeLedger.markHeld(GROUP, PEER_B, r.id);
        }
        for (final Row r : rowsBy(rows, PEER_B))
        {
            NgcHistoryServeLedger.markHeld(GROUP, PEER_IOS, r.id);
        }

        final int seconds = 25 * 60;
        final int[] sentPerMinute = new int[seconds / 60 + 2];
        final List<Long> resyncAt = new ArrayList<>();
        int servesToB = 0;
        int firstServeToB = -1;
        int firstServeToIos = -1;
        for (int t = 0; t < seconds; t++)
        {
            final long now = T0 + t * 1000L;
            final boolean bJoins = t % 63 == 0;
            final boolean bExits = t % 63 == 58;
            final boolean iosJoins = t % 180 == 0;
            final boolean iosExits = t % 180 == 170;
            if (bExits)
            {
                exited(PEER_B, now);
            }
            if (iosExits)
            {
                exited(PEER_IOS, now);
            }
            if (bJoins)
            {
                joined(PEER_B, now);
            }
            if (iosJoins)
            {
                joined(PEER_IOS, now);
            }
            boolean trigger = bJoins || bExits || iosJoins || iosExits;
            if (trigger)
            {
                resyncAt.add(now + 46_000L);
            }
            if (resyncAt.remove(Long.valueOf(now)))
            {
                trigger = true;
            }
            if (!trigger)
            {
                continue;
            }
            final boolean bPresent = t % 63 < 58;
            final boolean iosPresent = t % 180 < 170;
            if (bPresent && NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_B, now))
            {
                final int sent = serve(PEER_B, rows, now);
                servesToB++;
                if (firstServeToB < 0)
                {
                    firstServeToB = sent;
                }
                else
                {
                    sentPerMinute[t / 60] += sent;
                }
            }
            if (iosPresent && NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_IOS, now))
            {
                final int sent = serve(PEER_IOS, rows, now);
                if (firstServeToIos < 0)
                {
                    firstServeToIos = sent;
                }
                else
                {
                    sentPerMinute[t / 60] += sent;
                }
            }
        }

        int afterFirst = 0;
        int fromMinuteFive = 0;
        for (int m = 0; m < sentPerMinute.length; m++)
        {
            afterFirst += sentPerMinute[m];
            if (m >= 5)
            {
                fromMinuteFive += sentPerMinute[m];
            }
        }
        // Only the four rows of the member who left are unproven, and the Android member drops within the
        // grace after every serve: two more sends each, then they wait for the next full serve.
        assertEquals("rows sent after the first serves", 4 * (NgcHistoryServeLedger.MAX_SENDS_PER_FULL_INTERVAL - 1),
                     afterFirst);
        assertEquals("rows sent from minute 5 on", 0, fromMinuteFive);
        assertTrue("the churn still triggers serves: " + servesToB, servesToB >= 30);
        assertEquals("the first serve is the whole window but the member's own rows", 90, firstServeToB);
        assertEquals("the first serve is the whole window but the member's own rows", 90, firstServeToIos);
    }

    @Test
    public void aMemberWhoJoinsGetsTheWholeWindow()
    {
        final List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++)
        {
            rows.add(row(i % 2 == 0 ? SELF : PEER_B));
        }
        joined(NEWCOMER, T0);
        assertEquals(30, serve(NEWCOMER, rows, T0));
    }

    @Test
    public void aMemberIsNeverServedItsOwnRows()
    {
        final List<Row> rows = new ArrayList<>();
        rows.add(row(PEER_B));
        rows.add(row(SELF));
        rows.add(row(PEER_B));

        final NgcHistoryServeLedger.Serve s = NgcHistoryServeLedger.beginServe(GROUP, PEER_B, T0);
        assertTrue("the first serve is full", s.full);
        assertFalse(NgcHistoryServeLedger.shouldSend(s, rows.get(0).id, PEER_B.toLowerCase(Locale.ROOT)));
        assertTrue(NgcHistoryServeLedger.shouldSend(s, rows.get(1).id, SELF));
        assertFalse("a file has no id, and is not sent back to its author either",
                    NgcHistoryServeLedger.shouldSend(s, null, PEER_B));
        assertEquals(2, s.skipOwn);
    }

    @Test
    public void ourRowsTheMemberConfirmedAreLeftOut()
    {
        final Row receipted = row(SELF);
        final Row confirmedOnTheRow = row(SELF, null, PEER_IOS, PEER_B.toLowerCase(Locale.ROOT));
        final Row unconfirmed = row(SELF, PEER_IOS);
        final List<Row> rows = new ArrayList<>();
        rows.add(receipted);
        rows.add(confirmedOnTheRow);
        rows.add(unconfirmed);

        assertEquals("first serve: full", 3, serve(PEER_B, rows, T0));
        exited(PEER_B, T0 + 1_000L);
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B.toLowerCase(Locale.ROOT), receipted.id.toUpperCase(Locale.ROOT));

        final NgcHistoryServeLedger.Serve s = NgcHistoryServeLedger.beginServe(GROUP, PEER_B, T0 + 30_000L);
        assertFalse(s.full);
        assertFalse(NgcHistoryServeLedger.shouldSend(s, receipted.id, receipted.author, receipted.confirmedBy));
        assertFalse(NgcHistoryServeLedger.shouldSend(s, confirmedOnTheRow.id, confirmedOnTheRow.author,
                                                     confirmedOnTheRow.confirmedBy));
        assertTrue(NgcHistoryServeLedger.shouldSend(s, unconfirmed.id, unconfirmed.author, unconfirmed.confirmedBy));
        assertEquals(2, s.skipHeld);
    }

    @Test
    public void rowsTheMemberServedUsAreLeftOut()
    {
        final Row echoed = row(PEER_IOS);
        final Row notEchoed = row(PEER_IOS);
        final List<Row> rows = new ArrayList<>();
        rows.add(echoed);
        rows.add(notEchoed);

        assertEquals(2, serve(PEER_B, rows, T0));
        exited(PEER_B, T0 + 1_000L);
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B, echoed.id);
        assertEquals(1, serve(PEER_B, rows, T0 + 30_000L));
    }

    @Test
    public void aRowQueuedShortlyBeforeTheMemberDroppedIsServedAgain()
    {
        final List<Row> rows = new ArrayList<>();
        rows.add(row(PEER_IOS));

        assertEquals(1, serve(PEER_B, rows, T0));
        assertEquals("queued, and the member is still there", 0, serve(PEER_B, rows, T0 + 20_000L));

        exited(PEER_B, T0 + 58_000L);
        joined(PEER_B, T0 + 63_000L);
        assertEquals("lost with the connection", 1, serve(PEER_B, rows, T0 + 63_000L));

        // Queued at +63 s; the member stays for longer than the grace before it drops.
        exited(PEER_B, T0 + 63_000L + NgcHistoryServeLedger.DELIVERY_GRACE_MS + 1);
        joined(PEER_B, T0 + 200_000L);
        assertEquals("delivered long before the member dropped", 0, serve(PEER_B, rows, T0 + 200_000L));
    }

    @Test
    public void aJoinWithoutAReportedExitServesRecentRowsAgain()
    {
        final List<Row> rows = new ArrayList<>();
        rows.add(row(PEER_IOS));
        assertEquals(1, serve(PEER_B, rows, T0));
        joined(PEER_B, T0 + 10_000L);
        assertEquals(1, serve(PEER_B, rows, T0 + 30_000L));
    }

    /**
     * The phone sleeps for a minute right after a serve. toxcore times the member out by CLOCK_MONOTONIC, which
     * stood still meanwhile: the exit comes 58 s after the serve on that clock, two minutes after it on the wall
     * clock, past the grace. What was queued went with the connection all the same, and is served again.
     */
    @Test
    public void aRowQueuedBeforeThePhoneSleptIsServedAgainAfterTheTimeout()
    {
        final List<Row> rows = new ArrayList<>();
        rows.add(row(PEER_IOS));
        assertEquals(1, serve(PEER_B, rows, T0));

        final long sleptMs = 60_000L;
        assertTrue(sleptMs + 58_000L > NgcHistoryServeLedger.DELIVERY_GRACE_MS);
        NgcHistoryServeLedger.onPeerExited(GROUP, PEER_B, T0 + sleptMs + 58_000L, awake(T0 + 58_000L));
        NgcHistoryServeLedger.onPeerJoined(GROUP, PEER_B, T0 + sleptMs + 63_000L, awake(T0 + 63_000L));
        assertEquals("lost with the connection", 1,
                     serve(PEER_B, rows, T0 + sleptMs + 63_000L, awake(T0 + 63_000L)));
    }

    /**
     * Tox starts again in the same process (a profile switch, the service started again): what the last session
     * queued went with its toxcore instance. The new session takes nothing for queued, and serves in full first.
     */
    @Test
    public void aNewToxSessionDoesNotTrustWhatTheLastOneQueued()
    {
        final List<Row> rows = new ArrayList<>();
        rows.add(row(PEER_IOS));
        assertEquals(1, serve(PEER_B, rows, T0));
        assertEquals("queued, and the member is still there", 0, serve(PEER_B, rows, T0 + 20_000L));

        NgcHistoryServeLedger.reset();
        assertEquals(0, NgcHistoryServeLedger.trackedEntriesForTest());
        assertEquals("the new session's first serve", 1, serve(PEER_B, rows, T0 + 30_000L));
        assertTrue(lastServe.full);
    }

    @Test
    public void aRowIsSentAtMostThreeTimesBetweenFullServes()
    {
        final List<Row> rows = new ArrayList<>();
        rows.add(row(PEER_GONE));
        int sent = 0;
        long now = T0;
        for (int i = 0; i < 20; i++)
        {
            sent += serve(PEER_B, rows, now);
            exited(PEER_B, now + 58_000L);
            joined(PEER_B, now + 63_000L);
            now += 63_000L;
        }
        assertEquals(NgcHistoryServeLedger.MAX_SENDS_PER_FULL_INTERVAL, sent);

        final long nextFull = T0 + NgcHistoryServeLedger.FULL_SERVE_INTERVAL_MS;
        assertEquals("and again with the next full serve", 1, serve(PEER_B, rows, nextFull));
    }

    @Test
    public void everyThirtyMinutesAServeIsFullAgain()
    {
        final Row held = row(SELF);
        final List<Row> rows = new ArrayList<>();
        rows.add(held);
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B, held.id);

        assertTrue(NgcHistoryServeLedger.beginServe(GROUP, PEER_B, T0).full);
        assertEquals(0, serve(PEER_B, rows, T0 + 1_000L));
        assertEquals(0, serve(PEER_B, rows, T0 + NgcHistoryServeLedger.FULL_SERVE_INTERVAL_MS - 1));
        assertEquals("a database the member lost is healed this way", 1,
                     serve(PEER_B, rows, T0 + NgcHistoryServeLedger.FULL_SERVE_INTERVAL_MS));
        assertEquals(0, serve(PEER_B, rows, T0 + NgcHistoryServeLedger.FULL_SERVE_INTERVAL_MS + 20_000L));
    }

    @Test
    public void aMemberBackFromALongAbsenceGetsAFullServe()
    {
        final Row held = row(SELF);
        final List<Row> rows = new ArrayList<>();
        rows.add(held);
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B, held.id);
        assertEquals("first serve: full", 1, serve(PEER_B, rows, T0));

        exited(PEER_B, T0 + 60_000L);
        joined(PEER_B, T0 + 120_000L);
        assertEquals("away for a minute", 0, serve(PEER_B, rows, T0 + 120_000L));

        exited(PEER_B, T0 + 180_000L);
        joined(PEER_B, T0 + 180_000L + NgcHistoryServeLedger.LONG_ABSENCE_MS);
        assertEquals("away long enough to have reinstalled", 1,
                     serve(PEER_B, rows, T0 + 180_000L + NgcHistoryServeLedger.LONG_ABSENCE_MS));
    }

    /**
     * A member that kept its key and lost its rows (a restored profile, a database reset) is back within the
     * absence that would make it a stranger, and asks for history. The serve after an ask is full once the last full
     * serve is five minutes old; asks within those five minutes are served the ledger's way.
     */
    @Test
    public void aServeTheMemberAskedForIsFullFiveMinutesAfterTheLastFullOne()
    {
        final Row held = row(SELF);
        final List<Row> rows = new ArrayList<>();
        rows.add(held);
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B, held.id);
        assertEquals("first serve: full", 1, serve(PEER_B, rows, T0));

        final long interval = NgcHistoryServeLedger.REQUESTED_FULL_SERVE_INTERVAL_MS;
        exited(PEER_B, T0 + 60_000L);
        joined(PEER_B, T0 + 120_000L);
        NgcHistoryServeLedger.onHistoryRequested(GROUP, PEER_B, T0 + 124_000L);
        assertEquals("asked two minutes after a full serve", 0, serve(PEER_B, rows, T0 + 124_000L));
        NgcHistoryServeLedger.onHistoryRequested(GROUP, PEER_B, T0 + interval - 1);
        assertEquals("asked just inside five minutes", 0, serve(PEER_B, rows, T0 + interval - 1));

        NgcHistoryServeLedger.onHistoryRequested(GROUP, PEER_B, T0 + interval);
        assertEquals("asked five minutes after the last full serve", 1, serve(PEER_B, rows, T0 + interval));
        NgcHistoryServeLedger.onHistoryRequested(GROUP, PEER_B, T0 + interval + 20_000L);
        assertEquals("asked again 20 s later", 0, serve(PEER_B, rows, T0 + interval + 20_000L));

        assertEquals("not asked: full only after 30 minutes, as before", 0,
                     serve(PEER_B, rows, T0 + 3 * interval));
    }

    /**
     * The member asks a few seconds after it sees us, when its join has just made us serve it: the request cooldown
     * drops the serve of the ask. The ask still counts, and the resync 46 s after the join is full.
     */
    @Test
    public void anAskTheRequestCooldownDroppedMakesTheNextServeFull()
    {
        final Row held = row(SELF);
        final List<Row> rows = new ArrayList<>();
        rows.add(held);
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B, held.id);
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_B, T0));
        assertEquals("first serve: full", 1, serve(PEER_B, rows, T0));

        final long back = T0 + 6 * 60_000L;
        exited(PEER_B, back - 60_000L);
        joined(PEER_B, back);
        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_B, back));
        assertEquals("the serve of the join", 0, serve(PEER_B, rows, back));

        NgcHistoryServeLedger.onHistoryRequested(GROUP, PEER_B, back + 4_000L);
        assertFalse("the serve of the ask", NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_B, back + 4_000L));

        assertTrue(NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_B, back + 46_000L));
        assertEquals("the resync after the join", 1, serve(PEER_B, rows, back + 46_000L));
    }

    /**
     * The QA churn with the asks in it: the Android member is back every 63 s and asks 4 s after each join. Every
     * join, ask and resync serves it, through the request cooldown. A full serve comes at most every five minutes,
     * and between two of them only the rows the member never showed it holds go again, up to the cap.
     */
    @Test
    public void aMemberThatAsksAtEveryJoinGetsAFullServeAtMostEveryFiveMinutes()
    {
        final List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++)
        {
            rows.add(row(SELF));
        }
        for (int i = 0; i < 4; i++)
        {
            rows.add(row(PEER_GONE));
        }
        for (final Row r : rowsBy(rows, SELF))
        {
            NgcHistoryServeLedger.markHeld(GROUP, PEER_B, r.id);
        }

        final List<Long> fullAt = new ArrayList<>();
        int sent = 0;
        for (int t = 0; t < 25 * 60; t++)
        {
            final long now = T0 + t * 1000L;
            final int sinceJoin = t % 63;
            if (sinceJoin == 58)
            {
                exited(PEER_B, now);
            }
            if (sinceJoin == 0)
            {
                joined(PEER_B, now);
            }
            if (sinceJoin == 4)
            {
                NgcHistoryServeLedger.onHistoryRequested(GROUP, PEER_B, now);
            }
            final boolean serves = sinceJoin == 0 || sinceJoin == 4 || sinceJoin == 46;
            if (serves && NgcHistoryRequestPolicy.allowRequest(GROUP, PEER_B, now))
            {
                sent += serve(PEER_B, rows, now);
                if (lastServe.full)
                {
                    fullAt.add(now);
                }
            }
        }

        assertTrue("the asks bring full serves: " + fullAt, fullAt.size() >= 4);
        for (int i = 1; i < fullAt.size(); i++)
        {
            assertTrue("five minutes between full serves: " + fullAt,
                       fullAt.get(i) - fullAt.get(i - 1) >= 5L * 60_000L);
        }
        assertEquals("each full serve, then the four unproven rows up to the cap",
                     fullAt.size() * (rows.size() + 4 * (NgcHistoryServeLedger.MAX_SENDS_PER_FULL_INTERVAL - 1)), sent);
    }

    @Test
    public void rowsTheLedgerCannotTrackAreServedAsBefore()
    {
        final List<Row> rows = new ArrayList<>();
        rows.add(row(SELF));
        assertEquals(1, serve(PEER_B, rows, T0));

        final NgcHistoryServeLedger.Serve s = NgcHistoryServeLedger.beginServe(GROUP, PEER_B, T0 + 20_000L);
        assertFalse(s.full);
        assertTrue(NgcHistoryServeLedger.shouldSend(s, null, SELF));
        assertTrue(NgcHistoryServeLedger.shouldSend(s, "", SELF));
        assertTrue(NgcHistoryServeLedger.shouldSend(s, "1234567", SELF));
        assertTrue(NgcHistoryServeLedger.shouldSend(s, "1234567g", SELF));
        assertTrue(NgcHistoryServeLedger.shouldSend(s, HelperGroup.PENDING_GROUP_MESSAGE_ID_TOX, SELF));
        NgcHistoryServeLedger.markQueued(s, null, awake(T0 + 20_000L));
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B, "zz");
        NgcHistoryServeLedger.markHeld(GROUP, null, rows.get(0).id);
        NgcHistoryServeLedger.markHeld(null, PEER_B, rows.get(0).id);

        final NgcHistoryServeLedger.Serve unnamed = NgcHistoryServeLedger.beginServe(GROUP, null, T0);
        assertTrue(unnamed.full);
        assertTrue(NgcHistoryServeLedger.shouldSend(unnamed, rows.get(0).id, SELF));
        NgcHistoryServeLedger.markQueued(unnamed, rows.get(0).id, awake(T0));
        NgcHistoryServeLedger.onPeerExited(GROUP, null, T0, awake(T0));
        NgcHistoryServeLedger.onPeerJoined(null, PEER_B, T0, awake(T0));
    }

    @Test
    public void keysAreCaseInsensitive()
    {
        final Row r = row(SELF);
        final List<Row> rows = new ArrayList<>();
        rows.add(r);
        assertEquals(1, serve(PEER_B, rows, T0));
        NgcHistoryServeLedger.markHeld(GROUP.toLowerCase(Locale.ROOT), PEER_B.toLowerCase(Locale.ROOT),
                                       r.id.toUpperCase(Locale.ROOT));
        exited(PEER_B, T0 + 1_000L);
        final NgcHistoryServeLedger.Serve s = NgcHistoryServeLedger.beginServe(GROUP.toUpperCase(Locale.ROOT),
                                                                               PEER_B.toUpperCase(Locale.ROOT),
                                                                               T0 + 30_000L);
        assertFalse(s.full);
        assertFalse(NgcHistoryServeLedger.shouldSend(s, r.id, r.author));
    }

    @Test
    public void aClockSetBackDoesNotWithholdRows()
    {
        final Row held = row(SELF);
        final List<Row> rows = new ArrayList<>();
        rows.add(held);
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B, held.id);
        assertEquals(1, serve(PEER_B, rows, T0));
        // The wall clock goes back a minute; the monotonic one goes on.
        assertEquals("a serve before the last full one is full", 1,
                     serve(PEER_B, rows, T0 - 60_000L, awake(T0 + 1_000L)));
    }

    @Test
    public void pastItsBoundsTheLedgerForgetsAndServesAsBefore()
    {
        final Row held = row(SELF);
        final List<Row> rows = new ArrayList<>();
        rows.add(held);
        NgcHistoryServeLedger.markHeld(GROUP, PEER_B, held.id);
        assertEquals(1, serve(PEER_B, rows, T0));
        assertEquals(0, serve(PEER_B, rows, T0 + 20_000L));

        // One peer flooding receipts for made-up ids loses only its own ledger.
        for (int i = 0; i <= NgcHistoryServeLedger.MAX_ENTRIES_PER_PEER; i++)
        {
            NgcHistoryServeLedger.markHeld(GROUP, PEER_IOS, String.format(Locale.ROOT, "%08x", i));
        }
        assertEquals("the other peer keeps its ledger", 0, serve(PEER_B, rows, T0 + 40_000L));
        assertTrue(NgcHistoryServeLedger.beginServe(GROUP, PEER_IOS, T0 + 40_000L).full);

        // Many peers together past the global bound: everything is forgotten.
        int peer = 0;
        int before;
        do
        {
            before = NgcHistoryServeLedger.trackedEntriesForTest();
            final String pk = String.format(Locale.ROOT, "%064x", peer++);
            for (int i = 0; i < 1_000; i++)
            {
                NgcHistoryServeLedger.markHeld(GROUP, pk, String.format(Locale.ROOT, "%08x", i));
            }
            assertTrue("the bound is reached: " + peer, peer <= NgcHistoryServeLedger.MAX_TRACKED_ENTRIES / 1_000 + 2);
        }
        while (NgcHistoryServeLedger.trackedEntriesForTest() > before);
        assertTrue(NgcHistoryServeLedger.trackedEntriesForTest() <= NgcHistoryServeLedger.MAX_TRACKED_ENTRIES);
        assertEquals("served as before", 1, serve(PEER_B, rows, T0 + 60_000L));
        assertEquals(0, serve(PEER_B, rows, T0 + 80_000L));

        // And past the bound on peers.
        for (int i = 0; i < NgcHistoryServeLedger.MAX_TRACKED_PEERS; i++)
        {
            NgcHistoryServeLedger.beginServe(GROUP, String.format(Locale.ROOT, "%064x", i), T0);
        }
        assertEquals("served as before", 1, serve(PEER_B, rows, T0 + 100_000L));
    }
}

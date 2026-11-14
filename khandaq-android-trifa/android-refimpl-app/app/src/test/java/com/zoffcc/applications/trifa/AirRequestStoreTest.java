package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AirRequestStoreTest
{
    private static final List<AirRequestStore.Pending> EMPTY = Collections.emptyList();
    private static final String KEY_A = key(0xA1);
    private static final String KEY_B = key(0xB2);

    private static String key(int value)
    {
        return String.format(Locale.ROOT, "%064X", value);
    }

    private static String repeat(String text, int times)
    {
        final StringBuilder builder = new StringBuilder(text.length() * times);
        for (int i = 0; i < times; i++)
        {
            builder.append(text);
        }
        return builder.toString();
    }

    private static AirRequestStore.Pending pending(String publicKey, String message, long receivedMs)
    {
        final AirRequestStore.Pending result = AirRequestStore.Pending.of(publicKey, message, receivedMs);
        assertNotNull(result);
        return result;
    }

    @Test
    public void pendingNeedsA64HexKeyAndNormalizesTheRest()
    {
        assertNull(AirRequestStore.Pending.of(null, "hi", 1));
        assertNull(AirRequestStore.Pending.of("", "hi", 1));
        assertNull(AirRequestStore.Pending.of("ABC", "hi", 1));
        assertNull(AirRequestStore.Pending.of(KEY_A + "00", "hi", 1));
        assertNull(AirRequestStore.Pending.of(KEY_A.substring(1) + "G", "hi", 1));

        final AirRequestStore.Pending normalized = pending(" " + KEY_A.toLowerCase(Locale.ROOT) + "\n", "  hello  ", -5);
        assertEquals(KEY_A, normalized.publicKey);
        assertEquals("hello", normalized.message);
        assertEquals(0L, normalized.receivedMs);
        assertEquals("", pending(KEY_A, null, 1).message);
    }

    @Test
    public void messageIsCappedWithoutSplittingASurrogatePair()
    {
        assertEquals(AirRequestStore.MAX_MESSAGE_LENGTH, pending(KEY_A, repeat("b", 2000), 1).message.length());

        // The emoji starts at index 1015, so a plain cut at 1016 would keep half of it.
        final String text = repeat("a", 1015) + "😀" + "tail";
        final String capped = pending(KEY_A, text, 1).message;
        assertEquals(1015, capped.length());
        assertEquals(repeat("a", 1015), capped);
    }

    @Test
    public void aRepeatedRequestReplacesTheFirst()
    {
        List<AirRequestStore.Pending> list = AirRequestStore.withAdded(EMPTY, pending(KEY_A, "first", 100));
        list = AirRequestStore.withAdded(list, pending(KEY_B, "other", 200));
        list = AirRequestStore.withAdded(list, pending(KEY_A, "second", 300));
        assertEquals(2, list.size());
        assertEquals(pending(KEY_A, "second", 300), list.get(0));
        assertEquals(pending(KEY_B, "other", 200), list.get(1));

        // An empty repeat keeps the earlier words; an older time never moves a card back.
        list = AirRequestStore.withAdded(list, pending(KEY_A, "", 400));
        assertEquals(pending(KEY_A, "second", 400), list.get(0));
        list = AirRequestStore.withAdded(list, pending(KEY_A, "third", 50));
        assertEquals(pending(KEY_A, "third", 400), list.get(0));
        assertEquals(2, list.size());
    }

    @Test
    public void theListKeepsTheNewestFiftyNewestFirst()
    {
        List<AirRequestStore.Pending> list = EMPTY;
        for (int i = 0; i < 60; i++)
        {
            list = AirRequestStore.withAdded(list, pending(key(i), "m", 1000 + i));
        }
        assertEquals(AirRequestStore.MAX_REQUESTS, list.size());
        assertEquals(1059L, list.get(0).receivedMs);
        assertEquals(1010L, list.get(list.size() - 1).receivedMs);
    }

    @Test
    public void requestsAtTheSameTimeOrderByKey()
    {
        List<AirRequestStore.Pending> list = AirRequestStore.withAdded(EMPTY, pending(KEY_B, "b", 5));
        list = AirRequestStore.withAdded(list, pending(KEY_A, "a", 5));
        assertEquals(KEY_A, list.get(0).publicKey);
        assertEquals(KEY_B, list.get(1).publicKey);
    }

    @Test
    public void withoutDropsKeysInAnyCase()
    {
        List<AirRequestStore.Pending> list = AirRequestStore.withAdded(EMPTY, pending(KEY_A, "a", 1));
        list = AirRequestStore.withAdded(list, pending(KEY_B, "b", 2));
        final List<AirRequestStore.Pending> kept = AirRequestStore.without(list, Collections.singleton(KEY_A.toLowerCase(Locale.ROOT)));
        assertEquals(1, kept.size());
        assertEquals(KEY_B, kept.get(0).publicKey);
        assertEquals(2, AirRequestStore.without(list, Arrays.asList((String) null, key(7))).size());
    }

    @Test
    public void encodeAndDecodeRoundTrip()
    {
        List<AirRequestStore.Pending> list = AirRequestStore.withAdded(EMPTY, pending(KEY_A, "Привет, \"друг\"\nسلام 你好", 5));
        list = AirRequestStore.withAdded(list, pending(KEY_B, "", 7));
        assertEquals(list, AirRequestStore.decode(AirRequestStore.encode(list)));
        assertEquals("[]", AirRequestStore.encode(EMPTY));
    }

    @Test
    public void decodeSkipsDamagedValues()
    {
        assertTrue(AirRequestStore.decode(null).isEmpty());
        assertTrue(AirRequestStore.decode("").isEmpty());
        assertTrue(AirRequestStore.decode("   ").isEmpty());
        assertTrue(AirRequestStore.decode("{").isEmpty());
        assertTrue(AirRequestStore.decode("{}").isEmpty());
        assertTrue(AirRequestStore.decode("[1, \"x\", null, []]").isEmpty());

        final String json = "[{\"public_key\":\"" + KEY_A + "\",\"message\":\"ok\",\"received_ms\":9},"
                            + "{\"public_key\":\"nope\",\"message\":\"bad\",\"received_ms\":1},"
                            + "{\"public_key\":\"" + KEY_B + "\",\"message\":7,\"received_ms\":\"x\"},"
                            + "{\"public_key\":\"" + KEY_A.toLowerCase(Locale.ROOT) + "\",\"message\":\"dup\",\"received_ms\":3}]";
        final List<AirRequestStore.Pending> list = AirRequestStore.decode(json);
        assertEquals(2, list.size());
        assertEquals(pending(KEY_A, "dup", 9), list.get(0));
        assertEquals(pending(KEY_B, "", 0), list.get(1));
    }

    // ---- requests we accepted (QA live, F2) ----

    @Test
    public void theAcceptedMarkTakesTheTimeOfTheWaitingRequestButNotItsWords()
    {
        final List<AirRequestStore.Pending> waiting = AirRequestStore.withAdded(EMPTY, pending(KEY_A, "hi, it's me", 1234));
        assertEquals(pending(KEY_A, "", 1234), AirRequestStore.acceptedMark(waiting, KEY_A.toLowerCase(Locale.ROOT)));
        // Accepted on a path without a card: the time is not known.
        assertEquals(pending(KEY_B, "", 0), AirRequestStore.acceptedMark(waiting, KEY_B));
        assertNull(AirRequestStore.acceptedMark(waiting, "not a key"));
    }

    @Test
    public void onlyAnAcceptedKeyHasAnAcceptedTime()
    {
        List<AirRequestStore.Pending> accepted = AirRequestStore.withAdded(EMPTY, pending(KEY_A, "", 1234),
                                                                           AirRequestStore.MAX_ACCEPTED);
        accepted = AirRequestStore.withAdded(accepted, pending(KEY_B, "", 0), AirRequestStore.MAX_ACCEPTED);
        assertEquals(1234L, AirRequestStore.acceptedMs(accepted, KEY_A.toLowerCase(Locale.ROOT)));
        assertEquals(0L, AirRequestStore.acceptedMs(accepted, KEY_B));
        assertEquals(AirRequestStore.NOT_ACCEPTED, AirRequestStore.acceptedMs(accepted, key(7)));
        assertEquals(AirRequestStore.NOT_ACCEPTED, AirRequestStore.acceptedMs(accepted, null));
        assertEquals(AirRequestStore.NOT_ACCEPTED,
                     AirRequestStore.acceptedMs(AirRequestStore.without(accepted, Collections.singleton(KEY_A)), KEY_A));
    }

    @Test
    public void theAcceptedListOutlivesTheCapOfWaitingCardsAndSurvivesARestart()
    {
        List<AirRequestStore.Pending> accepted = EMPTY;
        for (int i = 0; i < 60; i++)
        {
            accepted = AirRequestStore.withAdded(accepted, pending(key(i), "", 1000 + i), AirRequestStore.MAX_ACCEPTED);
        }
        assertEquals(60, accepted.size());
        // What a restart reads back from the globals table.
        final List<AirRequestStore.Pending> read = AirRequestStore.decode(AirRequestStore.encode(accepted),
                                                                          AirRequestStore.MAX_ACCEPTED);
        assertEquals(accepted, read);
        assertEquals(1000L, AirRequestStore.acceptedMs(read, key(0)));
        // The cards themselves keep their own cap.
        assertEquals(AirRequestStore.MAX_REQUESTS, AirRequestStore.decode(AirRequestStore.encode(accepted)).size());
    }
}

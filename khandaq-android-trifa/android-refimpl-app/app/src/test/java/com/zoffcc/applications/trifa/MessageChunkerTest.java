package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — first JVM unit tests in this app.
 *
 * The second external security review closed with "the app currently contains zero JVM unit-test files",
 * and both reviews found their real defects in packet parsing and in the reassembly state machines. So
 * that is what these cover: the 1:1 chunk header admission rule and {@code IncomingAssembly}.
 *
 * These are deliberately plain JUnit with no Robolectric — the code under test is pure Java, and a test
 * that needs an Android runtime to check an integer bound is a test that will be skipped.
 */
public class MessageChunkerTest
{
    // ---------------------------------------------------------------- header admission (audit2 #3)

    @Test
    public void acceptsAnOrdinarySingleChunkMessage()
    {
        assertTrue(MessageChunker.isValidChunkHeader(0, 1, 100));
    }

    @Test
    public void acceptsEveryChunkOfAnOrdinaryMultiChunkMessage()
    {
        final int total = 5;
        for (int seq = 0; seq < total; seq++)
        {
            assertTrue("seq " + seq + " of " + total, MessageChunker.isValidChunkHeader(seq, total, 256));
        }
    }

    /**
     * The reported attack: a 13-byte packet declaring total=65535 (the u16 maximum) used to buy a
     * 65535-entry array. 65535 is far above MAX_LEGIT_CHUNKS, so it must be refused outright.
     */
    @Test
    public void rejectsTheReportedU16MaximumChunkCount()
    {
        assertFalse(MessageChunker.isValidChunkHeader(0, 65535, 1));
    }

    @Test
    public void acceptsTheLargestLegitimateChunkCountAndRejectsOneMore()
    {
        assertTrue(MessageChunker.isValidChunkHeader(0, MessageChunker.MAX_LEGIT_CHUNKS, 1));
        assertFalse(MessageChunker.isValidChunkHeader(0, MessageChunker.MAX_LEGIT_CHUNKS + 1, 1));
    }

    @Test
    public void rejectsAChunkCountBelowOne()
    {
        assertFalse(MessageChunker.isValidChunkHeader(0, 0, 100));
        assertFalse(MessageChunker.isValidChunkHeader(0, -1, 100));
    }

    @Test
    public void rejectsASequenceNumberOutsideTheDeclaredCount()
    {
        assertFalse("seq == total is one past the end", MessageChunker.isValidChunkHeader(4, 4, 100));
        assertFalse(MessageChunker.isValidChunkHeader(9, 4, 100));
        assertFalse(MessageChunker.isValidChunkHeader(-1, 4, 100));
    }

    @Test
    public void rejectsAnEmptyChunk()
    {
        assertFalse(MessageChunker.isValidChunkHeader(0, 1, 0));
        assertFalse(MessageChunker.isValidChunkHeader(0, 1, -1));
    }

    @Test
    public void acceptsTheLargestChunkAPacketCanCarryAndRejectsOneByteMore()
    {
        assertTrue(MessageChunker.isValidChunkHeader(0, 1, MessageChunker.CHUNK_PAYLOAD_MAX));
        assertFalse(MessageChunker.isValidChunkHeader(0, 1, MessageChunker.CHUNK_PAYLOAD_MAX + 1));
    }

    // ------------------------------------------------------------- reassembly state machine

    @Test
    public void storesAChunkAndAccountsItsBytesOnce()
    {
        final MessageChunker.IncomingAssembly asm = new MessageChunker.IncomingAssembly(7L, 2, 1_000L);

        assertTrue(asm.put(0, new byte[10], 1_000L));
        assertEquals(10, asm.bytes);
    }

    /**
     * A duplicate seq must be ignored AND must not be accounted — the caller adds to the global byte
     * counter based on this return value, so a duplicate that returned true would inflate the quota
     * accounting until the process was refusing honest transfers.
     */
    @Test
    public void ignoresADuplicateSequenceWithoutDoubleCountingItsBytes()
    {
        final MessageChunker.IncomingAssembly asm = new MessageChunker.IncomingAssembly(7L, 2, 1_000L);

        assertTrue(asm.put(0, new byte[10], 1_000L));
        assertFalse("second chunk with the same seq", asm.put(0, new byte[10], 1_001L));
        assertEquals("bytes must be counted once", 10, asm.bytes);
    }

    @Test
    public void refusesASequenceOutsideTheDeclaredCount()
    {
        final MessageChunker.IncomingAssembly asm = new MessageChunker.IncomingAssembly(7L, 2, 1_000L);

        assertFalse(asm.put(2, new byte[10], 1_000L));
        assertFalse(asm.put(-1, new byte[10], 1_000L));
        assertEquals(0, asm.bytes);
    }

    @Test
    public void isCompleteOnlyOnceEveryChunkHasArrived()
    {
        final MessageChunker.IncomingAssembly asm = new MessageChunker.IncomingAssembly(7L, 3, 1_000L);

        asm.put(0, new byte[4], 1_000L);
        assertFalse(asm.isComplete());
        asm.put(2, new byte[4], 1_001L);
        assertFalse("a gap at seq 1 is not complete", asm.isComplete());
        asm.put(1, new byte[4], 1_002L);
        assertTrue(asm.isComplete());
    }

    /**
     * Chunks are held in a sparse map with no ordering of its own, so out-of-order arrival — the normal
     * case on a lossy link — must still reassemble in sequence order.
     */
    @Test
    public void reassemblesInSequenceOrderRegardlessOfArrivalOrder()
    {
        final MessageChunker.IncomingAssembly asm = new MessageChunker.IncomingAssembly(7L, 3, 1_000L);

        asm.put(2, "cc".getBytes(StandardCharsets.UTF_8), 1_000L);
        asm.put(0, "aa".getBytes(StandardCharsets.UTF_8), 1_001L);
        asm.put(1, "bb".getBytes(StandardCharsets.UTF_8), 1_002L);

        assertTrue(asm.isComplete());
        assertArrayEquals("aabbcc".getBytes(StandardCharsets.UTF_8), asm.join());
    }

    @Test
    public void reassemblesAShortFinalChunk()
    {
        final MessageChunker.IncomingAssembly asm = new MessageChunker.IncomingAssembly(7L, 2, 1_000L);

        asm.put(0, "hello ".getBytes(StandardCharsets.UTF_8), 1_000L);
        asm.put(1, "w".getBytes(StandardCharsets.UTF_8), 1_001L);

        assertArrayEquals("hello w".getBytes(StandardCharsets.UTF_8), asm.join());
    }

    /**
     * The idle-TTL sweep keys off lastChunkTs, so it has to advance on every packet the peer sends —
     * including duplicates. If it did not, a peer that keeps resending seq 0 would have its assembly
     * swept out from under an otherwise live transfer.
     */
    @Test
    public void advancesTheIdleTimestampEvenForADuplicateChunk()
    {
        final MessageChunker.IncomingAssembly asm = new MessageChunker.IncomingAssembly(7L, 2, 1_000L);

        asm.put(0, new byte[4], 2_000L);
        assertEquals(2_000L, asm.lastChunkTs);
        assertFalse(asm.put(0, new byte[4], 5_000L));
        assertEquals(5_000L, asm.lastChunkTs);
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — bounds on an incoming NGC group-file chunk (internal audit L-1).
 *
 * chunkIndex and chunkSize are u32 fields read straight out of a group packet, so every value here is
 * attacker-chosen. Two of these bounds are the ones that were missing: a chunk larger than the payload
 * size the BEGIN declared, and a chunk whose offset+size runs past the declared end of the file.
 */
public class NgcGroupFileChunkBoundsTest
{
    /** A 100 KiB file at a 1000-byte payload: 100 chunks, the last one short. */
    private static final int PAYLOAD = 1000;
    private static final int TOTAL_CHUNKS = 100;
    private static final long TOTAL_SIZE = 100L * PAYLOAD;

    private static boolean accepts(final int index, final int size, final int packetLength)
    {
        return NgcGroupFileTransfer.isValidFileChunkHeader(index, size, packetLength, TOTAL_CHUNKS, PAYLOAD,
                                                          TOTAL_SIZE);
    }

    /** Packet exactly big enough to carry `size` payload bytes. */
    private static int packetFor(final int size)
    {
        return NgcGroupFileTransfer.FILE_CHUNK_HEADER + size;
    }

    @Test
    public void acceptsAnOrdinaryFullChunk()
    {
        assertTrue(accepts(0, PAYLOAD, packetFor(PAYLOAD)));
        assertTrue(accepts(42, PAYLOAD, packetFor(PAYLOAD)));
    }

    @Test
    public void acceptsTheLastChunkOfTheFile()
    {
        assertTrue(accepts(TOTAL_CHUNKS - 1, PAYLOAD, packetFor(PAYLOAD)));
    }

    @Test
    public void acceptsAShortFinalChunk()
    {
        // an honest 99.5 KiB file: last chunk carries 500 bytes
        assertTrue(NgcGroupFileTransfer.isValidFileChunkHeader(99, 500, packetFor(500), TOTAL_CHUNKS, PAYLOAD,
                                                              99L * PAYLOAD + 500L));
    }

    @Test
    public void rejectsAnIndexBeyondTheDeclaredChunkCount()
    {
        assertFalse(accepts(TOTAL_CHUNKS, PAYLOAD, packetFor(PAYLOAD)));
        assertFalse(accepts(TOTAL_CHUNKS + 5000, PAYLOAD, packetFor(PAYLOAD)));
    }

    /**
     * readU32Be returns an int, so any chunk index above 2^31 arrives NEGATIVE. Unchecked it would index
     * backwards out of received[] and produce a negative file offset.
     */
    @Test
    public void rejectsANegativeIndexFromAHighBitU32()
    {
        assertFalse(accepts(-1, PAYLOAD, packetFor(PAYLOAD)));
        assertFalse(accepts(Integer.MIN_VALUE, PAYLOAD, packetFor(PAYLOAD)));
    }

    @Test
    public void rejectsAnEmptyOrNegativeChunkSize()
    {
        assertFalse(accepts(0, 0, packetFor(0)));
        assertFalse(accepts(0, -1, packetFor(1)));
        assertFalse(accepts(0, Integer.MIN_VALUE, packetFor(1)));
    }

    /** The packet must actually contain the bytes it claims — otherwise the copy reads past its end. */
    @Test
    public void rejectsAChunkWhoseBytesAreNotInThePacket()
    {
        assertFalse(accepts(0, PAYLOAD, packetFor(PAYLOAD) - 1));
        assertFalse(accepts(0, PAYLOAD, NgcGroupFileTransfer.FILE_CHUNK_HEADER));
    }

    /** L-1, first half: a chunk larger than the payload size the BEGIN declared. */
    @Test
    public void rejectsAChunkLargerThanTheDeclaredPayloadSize()
    {
        assertFalse(accepts(0, PAYLOAD + 1, packetFor(PAYLOAD + 1)));
    }

    /** L-1, second half: index*payload + size must stay inside the declared file size. */
    @Test
    public void rejectsAChunkThatWouldWritePastTheDeclaredEndOfFile()
    {
        // last chunk of a file whose real length is one byte short of a full final chunk
        assertFalse(NgcGroupFileTransfer.isValidFileChunkHeader(99, PAYLOAD, packetFor(PAYLOAD), TOTAL_CHUNKS,
                                                               PAYLOAD, 99L * PAYLOAD + 1L));
    }

    /**
     * index*payload is computed in long on purpose. With int arithmetic this case overflows to a large
     * negative offset, which then compares as comfortably inside the file and the chunk is accepted.
     */
    @Test
    public void rejectsAnIndexWhosePayloadProductWouldOverflowIntArithmetic()
    {
        final int index = 1_500_000;
        final int payload = 2_000;
        final int totalChunks = 2_000_000;
        // 1_500_000 * 2_000 = 3e9, past Integer.MAX_VALUE — as an int this wraps negative.
        assertTrue("precondition: this product really does overflow an int",
                   (long) index * (long) payload > Integer.MAX_VALUE);

        assertFalse(NgcGroupFileTransfer.isValidFileChunkHeader(index, payload, packetFor(payload), totalChunks,
                                                               payload, 1_000_000L));
    }
}

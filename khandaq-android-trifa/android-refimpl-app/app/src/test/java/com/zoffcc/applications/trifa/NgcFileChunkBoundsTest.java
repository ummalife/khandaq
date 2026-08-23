package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ — the file-chunk admission rule (internal audit L-1).
 *
 * isValidFileChunkHeader was deliberately written as a pure predicate "so it can be tested without a
 * Tox stack or VFS", and then never was. Every field it takes is read as a u32 straight out of a group
 * packet, so all of them are attacker-chosen, and readU32Be returns an int — negative for anything
 * above 2^31. The bounds are what keeps a chunk from being copied out of a packet that does not hold
 * it, or written past the end of the file it claims to belong to.
 *
 * The iOS side (OCTNgcGroupFileTransfer, handleIncomingChunk) enforces the same five conditions on
 * unsigned types; these cases are written so the two can be compared line by line.
 */
public class NgcFileChunkBoundsTest
{
    private static final int HEADER = NgcGroupFileTransfer.FILE_CHUNK_HEADER;

    // A plausible honest transfer: 3 chunks of 1000 bytes, last one short.
    private static final int PAYLOAD = 1000;
    private static final int CHUNKS = 3;
    private static final long SIZE = 2500;

    private static boolean ok(int index, int size, int packetLength)
    {
        return NgcGroupFileTransfer.isValidFileChunkHeader(index, size, packetLength, CHUNKS, PAYLOAD, SIZE);
    }

    @Test
    public void honest_chunks_are_accepted()
    {
        assertTrue("first full chunk", ok(0, PAYLOAD, HEADER + PAYLOAD));
        assertTrue("middle full chunk", ok(1, PAYLOAD, HEADER + PAYLOAD));
        assertTrue("short final chunk", ok(2, 500, HEADER + 500));
        assertTrue("a packet longer than the chunk it carries is fine",
                   ok(0, PAYLOAD, HEADER + PAYLOAD + 64));
    }

    @Test
    public void an_index_outside_the_declared_count_is_refused()
    {
        assertFalse("one past the end", ok(CHUNKS, 10, HEADER + 10));
        assertFalse("far past the end", ok(9999, 10, HEADER + 10));
    }

    @Test
    public void a_negative_index_is_refused()
    {
        // What readU32Be hands back for a declared index above 2^31: it would index backwards, and
        // the product below would go negative rather than large.
        assertFalse(ok(-1, 10, HEADER + 10));
        assertFalse(ok(Integer.MIN_VALUE, 10, HEADER + 10));
    }

    @Test
    public void a_chunk_of_no_bytes_or_negative_bytes_is_refused()
    {
        assertFalse("zero", ok(0, 0, HEADER + 100));
        assertFalse("negative, i.e. a u32 above 2^31", ok(0, -1, HEADER + 100));
        assertFalse(ok(0, Integer.MIN_VALUE, HEADER + 100));
    }

    @Test
    public void a_chunk_the_packet_does_not_hold_is_refused()
    {
        assertFalse("claims more bytes than arrived", ok(0, 500, HEADER + 499));
        assertFalse("header alone, no payload", ok(0, 1, HEADER));
        assertTrue("exactly the bytes that arrived", ok(0, 500, HEADER + 500));
    }

    @Test
    public void a_chunk_larger_than_the_declared_payload_is_refused()
    {
        // The BEGIN packet fixed the payload size; a later chunk cannot enlarge it, even if the packet
        // really does carry that many bytes.
        assertFalse(ok(0, PAYLOAD + 1, HEADER + PAYLOAD + 1));
    }

    @Test
    public void a_chunk_running_past_the_declared_file_size_is_refused()
    {
        // The bound that was missing. Chunk 2 starts at 2000; the file is 2500 long.
        assertTrue("fits exactly", ok(2, 500, HEADER + 500));
        assertFalse("one byte past the end", ok(2, 501, HEADER + 501));
    }

    @Test
    public void the_offset_product_is_computed_in_long_arithmetic()
    {
        // index * payload overflows int here; in int arithmetic the product wraps negative and the
        // final comparison would pass, letting a write land at an arbitrary offset.
        final int hugeIndex = 3_000_000;
        final int hugePayload = 1000;
        final long product = (long) hugeIndex * (long) hugePayload;
        assertTrue("precondition: this really does overflow int", product > Integer.MAX_VALUE);

        assertFalse(NgcGroupFileTransfer.isValidFileChunkHeader(
                hugeIndex, 10, HEADER + 10, hugeIndex + 1, hugePayload, SIZE));
    }

    @Test
    public void a_zero_chunk_count_admits_nothing()
    {
        assertFalse(NgcGroupFileTransfer.isValidFileChunkHeader(0, 10, HEADER + 10, 0, PAYLOAD, SIZE));
    }
}

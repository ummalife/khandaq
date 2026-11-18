package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): the map of the chunks a group download has on disk (.rxmap), read again after a restart. Up to
 * 716bbf6c the first chunk after a restart emptied the file while the map kept the chunks of the run before, so a map
 * of an earlier version may mark chunks its file no longer has: it is not read, and the download starts again.
 */
public class NgcRxMapTest
{
    private static final int CHUNKS = 86;
    private static final long SIZE = 85L * NgcGroupFileTransfer.CHUNK_PAYLOAD_MAX + 1000L;

    /** Some chunks on disk, some not. */
    private static boolean[] someOnDisk()
    {
        final boolean[] onDisk = new boolean[CHUNKS];
        for (int i = 0; i < CHUNKS; i++)
        {
            onDisk[i] = (i % 3 == 0) || (i > 70);
        }
        return onDisk;
    }

    /** The map as 0.2.55 (persistReceivedBitmap) wrote it: "RXM1", the chunk count, the file size, the bitmap. */
    private static byte[] mapOf0255(final boolean[] onDisk, final long size)
    {
        final byte[] out = new byte[4 + 4 + 8 + (onDisk.length + 7) / 8];
        final ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(0x52584d31);
        bb.putInt(onDisk.length);
        bb.putLong(size);
        for (int i = 0; i < onDisk.length; i++)
        {
            if (onDisk[i])
            {
                out[16 + (i >> 3)] |= (byte) (1 << (i & 7));
            }
        }
        return out;
    }

    @Test
    public void a_map_of_an_earlier_version_is_not_read()
    {
        assertNull(NgcGroupFileTransfer.rxMapChunks(mapOf0255(someOnDisk(), SIZE), CHUNKS, SIZE));
    }

    @Test
    public void a_map_this_version_writes_is_read_back()
    {
        final byte[] map = NgcGroupFileTransfer.rxMapBytes(someOnDisk(), SIZE);
        assertArrayEquals(new byte[]{'R', 'X', 'M', '2'}, Arrays.copyOf(map, 4));
        assertArrayEquals(someOnDisk(), NgcGroupFileTransfer.rxMapChunks(map, CHUNKS, SIZE));
    }

    @Test
    public void a_map_of_another_file_or_cut_short_is_not_read()
    {
        final byte[] map = NgcGroupFileTransfer.rxMapBytes(someOnDisk(), SIZE);
        assertNull(NgcGroupFileTransfer.rxMapChunks(map, CHUNKS + 1, SIZE));
        assertNull(NgcGroupFileTransfer.rxMapChunks(map, CHUNKS, SIZE + 1L));
        assertNull(NgcGroupFileTransfer.rxMapChunks(Arrays.copyOf(map, map.length - 1), CHUNKS, SIZE));
        assertNull(NgcGroupFileTransfer.rxMapChunks(Arrays.copyOf(map, 15), CHUNKS, SIZE));
        assertNull(NgcGroupFileTransfer.rxMapChunks(null, CHUNKS, SIZE));
    }

    /**
     * KHANDAQ (0.2.59, group files): a download has its full length once its last chunk landed, missing chunks or
     * not; after a restart only its map says whether it is whole (NgcGroupFileTransfer.isReceiveUnfinished).
     */
    @Test
    public void a_map_says_whether_its_file_misses_chunks()
    {
        final boolean[] lastFirst = new boolean[CHUNKS];
        lastFirst[CHUNKS - 1] = true;
        assertTrue(NgcGroupFileTransfer.rxMapMissesChunks(NgcGroupFileTransfer.rxMapBytes(lastFirst, SIZE), SIZE));
        assertTrue(NgcGroupFileTransfer.rxMapMissesChunks(NgcGroupFileTransfer.rxMapBytes(someOnDisk(), SIZE), SIZE));

        final boolean[] whole = new boolean[CHUNKS];
        Arrays.fill(whole, true);
        assertFalse(NgcGroupFileTransfer.rxMapMissesChunks(NgcGroupFileTransfer.rxMapBytes(whole, SIZE), SIZE));
    }

    @Test
    public void a_map_that_is_not_read_says_nothing_of_its_file()
    {
        final byte[] map = NgcGroupFileTransfer.rxMapBytes(someOnDisk(), SIZE);
        assertFalse(NgcGroupFileTransfer.rxMapMissesChunks(map, SIZE + 1L));
        assertFalse(NgcGroupFileTransfer.rxMapMissesChunks(mapOf0255(someOnDisk(), SIZE), SIZE));
        assertFalse(NgcGroupFileTransfer.rxMapMissesChunks(Arrays.copyOf(map, map.length - 1), SIZE));
        assertFalse(NgcGroupFileTransfer.rxMapMissesChunks(Arrays.copyOf(map, 15), SIZE));
        assertFalse(NgcGroupFileTransfer.rxMapMissesChunks(null, SIZE));
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * KHANDAQ (0.2.56): the two layouts of a request for the missing chunks of a group file (0x14). Android sends the
 * file's chunk count and a bitmap of the missing chunks; an iPhone sends a list of them. Android read the iPhone's
 * list as a request for the whole file: dropped while one of our files went, else the whole file again at the pace,
 * which the iPhone gives up on after a minute when its gap is far into the file.
 */
public class NgcFileRequestLayoutTest
{
    private static final int CHUNK = NgcGroupFileTransfer.CHUNK_PAYLOAD_MAX;
    /** 85 whole chunks and a short last one: 86 chunks. */
    private static final long SIZE_86 = 85L * CHUNK + 1000L;
    private static final byte[] MSG_ID = new byte[32];

    static
    {
        for (int i = 0; i < MSG_ID.length; i++)
        {
            MSG_ID[i] = (byte) (0xa0 + i);
        }
    }

    /**
     * The request as an iPhone builds it (OCTNgcGroupFileTransfer sendNackForAssembly on origin/fix/056-ios):
     * appendMagicToData (66 77 88 11 34 35, version 01), the packet id 0x14, appendMsgId (32 bytes), appendU32BE of the
     * count, then appendU32BE of each missing index.
     */
    private static byte[] iphoneRequest(final int... indices)
    {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] magic = {0x66, 0x77, (byte) 0x88, 0x11, 0x34, 0x35, 0x01};
        out.write(magic, 0, magic.length);
        out.write(0x14);
        out.write(MSG_ID, 0, MSG_ID.length);
        writeU32Be(out, indices.length);
        for (final int index : indices)
        {
            writeU32Be(out, index);
        }
        return out.toByteArray();
    }

    private static void writeU32Be(final ByteArrayOutputStream out, final int value)
    {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static boolean[] parse(final byte[] packet, final long fileSize)
    {
        return NgcGroupFileTransfer.parseWantedChunks(packet, packet.length, fileSize);
    }

    private static void assertWanted(final boolean[] wanted, final int chunks, final int... indices)
    {
        assertNotNull("taken for a request of the whole file", wanted);
        assertEquals(chunks, wanted.length);
        final boolean[] expected = new boolean[chunks];
        for (final int index : indices)
        {
            expected[index] = true;
        }
        assertArrayEquals(expected, wanted);
    }

    private static int[] upTo(final int n)
    {
        final int[] indices = new int[n];
        for (int i = 0; i < n; i++)
        {
            indices[i] = i;
        }
        return indices;
    }

    @Test
    public void an_iphone_request_names_the_chunks_it_misses()
    {
        final byte[] packet = iphoneRequest(0, 41, 85);
        assertEquals(NgcGroupFileTransfer.FILE_REQUEST_HEADER + 3 * 4, packet.length);
        assertWanted(parse(packet, SIZE_86), 86, 0, 41, 85);
    }

    @Test
    public void an_iphone_request_for_every_chunk_of_a_small_file_is_a_list_not_a_bitmap()
    {
        // Its count is then the file's chunk count, and the bitmap rule alone read the index bytes as a bitmap.
        final byte[] packet = iphoneRequest(0, 1, 2);
        assertWanted(parse(packet, 2L * CHUNK + 10L), 3, 0, 1, 2);
    }

    @Test
    public void an_iphone_request_leaves_out_chunks_beyond_the_file()
    {
        assertWanted(parse(iphoneRequest(7, 86, -1), SIZE_86), 86, 7);
    }

    @Test
    public void the_longest_list_an_iphone_sends_is_read_and_a_longer_one_is_not()
    {
        final long size400 = 400L * CHUNK;
        final int most = NgcGroupFileTransfer.FILE_REQUEST_MAX_LISTED_CHUNKS;
        assertEquals(256, most);
        assertWanted(parse(iphoneRequest(upTo(most)), size400), 400, upTo(most));
        // Not an iPhone's request: read as before, here as a request of the whole file.
        assertNull(parse(iphoneRequest(upTo(most + 1)), size400));
    }

    @Test
    public void a_list_with_bytes_after_it_is_not_an_iphone_request()
    {
        final byte[] packet = iphoneRequest(3, 4);
        final byte[] longer = Arrays.copyOf(packet, packet.length + 1);
        assertNull(parse(longer, SIZE_86));
    }

    @Test
    public void an_android_request_still_reads_as_its_bitmap()
    {
        final byte[] bitmap = new byte[(86 + 7) / 8];
        for (final int index : new int[]{0, 41, 85})
        {
            bitmap[index >> 3] |= (byte) (1 << (index & 7));
        }
        assertWanted(parse(NgcGroupFileTransfer.buildFileRequestPacket(MSG_ID, 86, bitmap), SIZE_86), 86, 0, 41, 85);
        // Without a bitmap it asks for the whole file, as before.
        assertNull(parse(NgcGroupFileTransfer.buildFileRequestPacket(MSG_ID, 86, null), SIZE_86));
    }

    @Test
    public void no_android_request_has_the_length_of_an_iphone_list()
    {
        // A bitmap of n chunks is (n + 7) / 8 bytes, a list of n chunks 4 n: every Android request reads as its bitmap.
        for (int chunks = 1; chunks <= 6000; chunks++)
        {
            final byte[] bitmap = new byte[(chunks + 7) / 8];
            final boolean[] expected = new boolean[chunks];
            for (int i = 0; i < chunks; i += 3)
            {
                bitmap[i >> 3] |= (byte) (1 << (i & 7));
                expected[i] = true;
            }
            final long size = (long) (chunks - 1) * CHUNK + 1L;
            final byte[] packet = NgcGroupFileTransfer.buildFileRequestPacket(MSG_ID, chunks, bitmap);
            assertArrayEquals("chunks=" + chunks, expected, parse(packet, size));
        }
    }
}

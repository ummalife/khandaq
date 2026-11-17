package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): the two layouts of a request for the missing chunks of a group file (0x14). Android up to 0.2.56
 * sends the file's chunk count and a bitmap of the missing chunks; an iPhone sends a list of them. Android read the
 * iPhone's list as a request for the whole file: dropped while one of our files went, else the whole file again at
 * the pace, which the iPhone gives up on after a minute when its gap is far into the file. KHANDAQ (0.2.57): Android
 * asks with lists too, at most 256 chunks a packet, since an iPhone drops the bitmap; it still reads both.
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

    /**
     * Whether an iPhone serves the request (OCTNgcGroupFileTransfer handleIncomingRequestWithGroupNumber, 0.2.56): a
     * count of 1 to 256 and the bytes of that many indices.
     */
    private static boolean iphoneServes(final byte[] packet)
    {
        if (packet.length < NgcGroupFileTransfer.FILE_REQUEST_HEADER || packet[7] != NgcGroupFileTransfer.PKT_FILE_REQUEST)
        {
            return false;
        }
        final long count = ((packet[40] & 0xffL) << 24) | ((packet[41] & 0xffL) << 16) | ((packet[42] & 0xffL) << 8)
                           | (packet[43] & 0xffL);
        return (count != 0) && (count <= 256) && (packet.length >= NgcGroupFileTransfer.FILE_REQUEST_HEADER + count * 4);
    }

    private static byte[][] androidRequests(final int... missing)
    {
        return NgcGroupFileTransfer.buildListedFileRequestPackets(MSG_ID, missing);
    }

    @Test
    public void an_iphone_dropped_the_bitmap_android_asked_with()
    {
        // QA 0.2.57: 55 bytes for 86 chunks; an iPhone read the chunk count as the length of a list.
        final byte[] bitmap = new byte[(86 + 7) / 8];
        bitmap[0] = 1;
        final byte[] old = NgcGroupFileTransfer.buildFileRequestPacket(MSG_ID, 86, bitmap);
        assertEquals(55, old.length);
        assertFalse(iphoneServes(old));
    }

    @Test
    public void android_asks_in_the_layout_of_an_iphone()
    {
        final byte[][] packets = androidRequests(0, 41, 85);
        assertEquals(1, packets.length);
        assertArrayEquals(iphoneRequest(0, 41, 85), packets[0]);
        assertTrue(iphoneServes(packets[0]));
        // And Android reads it as it reads an iPhone's.
        assertWanted(parse(packets[0], SIZE_86), 86, 0, 41, 85);
    }

    @Test
    public void android_lists_at_most_256_chunks_a_packet_and_every_missing_chunk_once()
    {
        // Every other chunk of a file of 1200 is missing: 600 chunks, three packets of 256, 256 and 88.
        final int chunks = 1200;
        final int[] missing = new int[600];
        for (int i = 0; i < missing.length; i++)
        {
            missing[i] = 2 * i;
        }
        final byte[][] packets = androidRequests(missing);
        assertEquals(3, packets.length);
        final boolean[] asked = new boolean[chunks];
        int listed = 0;
        for (final byte[] packet : packets)
        {
            final int n = Math.min(NgcGroupFileTransfer.FILE_REQUEST_MAX_LISTED_CHUNKS, missing.length - listed);
            final int[] part = Arrays.copyOfRange(missing, listed, listed + n);
            assertEquals(NgcGroupFileTransfer.FILE_REQUEST_HEADER + 4 * n, packet.length);
            assertArrayEquals(iphoneRequest(part), packet);
            assertTrue(iphoneServes(packet));
            final boolean[] wanted = parse(packet, (long) chunks * CHUNK);
            assertWanted(wanted, chunks, part);
            for (int i = 0; i < chunks; i++)
            {
                if (wanted[i])
                {
                    assertFalse("listed twice: " + i, asked[i]);
                    asked[i] = true;
                }
            }
            listed += n;
        }
        assertEquals(missing.length, listed);
        assertWanted(asked, chunks, missing);
    }

    @Test
    public void a_list_of_256_is_one_packet_257_two_and_nothing_missing_none()
    {
        assertEquals(1, androidRequests(upTo(256)).length);
        final byte[][] two = androidRequests(upTo(257));
        assertEquals(2, two.length);
        assertArrayEquals(iphoneRequest(256), two[1]);
        assertEquals(0, androidRequests().length);
    }

    @Test
    public void no_bitmap_request_has_the_length_of_a_list()
    {
        // A bitmap of n chunks is (n + 7) / 8 bytes, a list of n chunks 4 n: every bitmap request (an Android up to
        // 0.2.56) reads as its bitmap.
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

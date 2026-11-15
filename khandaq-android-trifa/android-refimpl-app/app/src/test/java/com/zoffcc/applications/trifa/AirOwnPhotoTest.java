package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static com.zoffcc.applications.trifa.ProfileTabAvatarHelper.OwnPhoto;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.57): the photo on Air's "you" tile blinked when a contact came online. IOCipher moves a
 * file's mtime when the file is only read, and our photo is read to be sent to every contact who comes
 * online, so a key holding the mtime took each send for a new photo and the tile reloaded it. The key
 * follows the content now; the mtime only tells whether the file needs to be read again.
 */
public class AirOwnPhotoTest
{
    private static final String PATH = "/datadir/myavatar//avatar.png";

    @Test
    public void a_read_that_only_moves_the_mtime_is_the_same_photo() throws IOException
    {
        // The two stamps the tile saw before and after a contact came back online.
        final OwnPhoto before = OwnPhoto.of(PATH, 20526, 1794782578000L, digest(photo(20526, 7)));
        final OwnPhoto after = OwnPhoto.of(PATH, 20526, 1794782705000L, digest(photo(20526, 7)));
        assertEquals(before, after);
        assertEquals(before.hashCode(), after.hashCode());
        assertEquals(before.signature, after.signature);
        assertFalse(before.signature.contains("1794782578"));
    }

    @Test
    public void a_new_photo_of_the_same_length_is_a_new_photo() throws IOException
    {
        final byte[] first = photo(20526, 7);
        final byte[] second = Arrays.copyOf(first, first.length);
        second[second.length - 1] ^= 1;
        assertNotEquals(OwnPhoto.of(PATH, 20526, 1000L, digest(first)),
                        OwnPhoto.of(PATH, 20526, 1000L, digest(second)));
    }

    @Test
    public void a_new_length_is_a_new_photo() throws IOException
    {
        assertNotEquals(OwnPhoto.of(PATH, 20526, 1000L, digest(photo(20526, 7))),
                        OwnPhoto.of(PATH, 20527, 1000L, digest(photo(20527, 7))));
    }

    @Test
    public void the_key_is_the_path_the_length_and_the_digest() throws IOException
    {
        final OwnPhoto own = OwnPhoto.of(PATH, 20526, 1000L, digest(photo(20526, 7)));
        assertEquals(PATH, own.path);
        assertTrue(own.signature.startsWith(PATH + "_20526_"));
        // 32 bytes of SHA-256 in hex after the length.
        assertEquals(PATH.length() + "_20526_".length() + 64, own.signature.length());
    }

    @Test
    public void a_stat_that_still_matches_needs_no_read() throws IOException
    {
        final OwnPhoto own = OwnPhoto.of(PATH, 20526, 1794782705000L, digest(photo(20526, 7)));
        assertTrue(own.sameStat(PATH, 20526, 1794782705000L));
        assertFalse(own.sameStat(PATH, 20526, 1794782706000L));
        assertFalse(own.sameStat(PATH, 20527, 1794782705000L));
        assertFalse(own.sameStat("/datadir/other.png", 20526, 1794782705000L));
    }

    @Test
    public void the_digest_covers_the_whole_file_past_one_buffer() throws IOException, NoSuchAlgorithmException
    {
        final byte[] bytes = photo(100000, 3);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), digest(bytes));
        final byte[] tail = Arrays.copyOf(bytes, bytes.length);
        tail[99999] ^= 1;
        assertFalse(Arrays.equals(digest(bytes), digest(tail)));
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(new byte[0]), digest(new byte[0]));
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest("x".getBytes(StandardCharsets.UTF_8)),
                          digest("x".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] digest(final byte[] content) throws IOException
    {
        return OwnPhoto.digest(new ByteArrayInputStream(content));
    }

    private static byte[] photo(final int length, final int seed)
    {
        final byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++)
        {
            bytes[i] = (byte) (i * 31 + seed);
        }
        return bytes;
    }
}

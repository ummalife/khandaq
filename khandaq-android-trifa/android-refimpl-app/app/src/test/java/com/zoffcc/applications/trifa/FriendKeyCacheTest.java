package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): the friend number to public key cache keeps only public keys.
 *
 * The JNI tox_friend_get_public_key answers "-1" for a number toxcore has no friend for. The service asks
 * for friend 0 while it loads groups, so a profile with a group and no contact yet cached "-1" for number
 * 0; the first contact added after that got number 0, and until the next start its incoming texts and
 * files and the files sent to it were stored under "-1" (found live on Android 16: a picked file read
 * "Goes when -1 is online" and left the chat at once).
 */
public class FriendKeyCacheTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String KEY = "B5C3C8BF283B99D7D19E7AB638A675E82A9F4E27036DBCA184F3C44BF3387D21";

    @Test
    public void only_a_public_key_is_an_answer_to_keep()
    {
        assertTrue(HelperFriend.is_friend_pubkey_answer(KEY));
        assertTrue(HelperFriend.is_friend_pubkey_answer(KEY.toLowerCase(java.util.Locale.ROOT)));
        assertFalse(HelperFriend.is_friend_pubkey_answer("-1")); // no friend with this number (JNI)
        assertFalse(HelperFriend.is_friend_pubkey_answer(null)); // Tox not running (JNI)
        assertFalse(HelperFriend.is_friend_pubkey_answer(""));
        assertFalse(HelperFriend.is_friend_pubkey_answer(KEY + "98F37AFAC1")); // a whole Tox ID is not a key
    }

    @Test
    public void the_wrapper_caches_only_a_public_key() throws Exception
    {
        final String wrapper = method(read("HelperFriend.java"),
                                      "public static String tox_friend_get_public_key__wrapper(long friend_number)");
        assertTrue(wrapper.contains("if (is_friend_pubkey_answer(result))"));
        assertFalse(wrapper.contains("if (result != null)"));
        // The answer itself is unchanged: callers that test for the "-1" sentinel still get it.
        assertTrue(wrapper.contains("return result;"));
    }

    private static String read(final String name) throws IOException
    {
        return new String(Files.readAllBytes(new File(JAVA + name).toPath()), StandardCharsets.UTF_8);
    }

    /** The source of one method: from its signature to the brace that closes it. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue("missing " + signature, start >= 0);
        int depth = 0;
        for (int i = source.indexOf('{', start); i < source.length(); i++)
        {
            final char c = source.charAt(i);
            if (c == '{')
            {
                depth++;
            }
            else if (c == '}' && --depth == 0)
            {
                return source.substring(start, i + 1);
            }
        }
        throw new AssertionError("unclosed " + signature);
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.63): the name a text or a file of the group's history goes out under
 * (HelperGroup.history_sync_author_name). It was the name the author has now only, and for an author the group does
 * not know now that is the end of the key (HelperFriend.peer_pubkey_short_id), while the entry kept the name the
 * author had when it came. QA-Live of 0.2.63: the iPhone took a text of Windows again from Android's history as
 * "0F2652", next to its live copy as "QA A".
 */
public class HistorySyncAuthorNameTest
{
    /** A key whose end, as the group names an author it does not know, is 0F2652. */
    private static final String KEY = "5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A5A0F2652";

    @Test
    public void anAuthorTheGroupKnowsNowGoesUnderTheNameNow()
    {
        assertEquals("QA A", HelperGroup.pick_history_sync_author_name("QA A", "QA A", KEY));
        assertEquals("Amina", HelperGroup.pick_history_sync_author_name("Amina", "QA A", KEY));
        assertEquals("Amina", HelperGroup.pick_history_sync_author_name("Amina", null, KEY));
    }

    @Test
    public void anAuthorKnownByTheEndOfTheKeyOnlyGoesUnderTheNameTheEntryKept()
    {
        assertEquals("0F2652", HelperFriend.peer_pubkey_short_id(KEY));
        assertEquals("QA A", HelperGroup.pick_history_sync_author_name("0F2652", "QA A", KEY));
        assertEquals("QA A", HelperGroup.pick_history_sync_author_name("0f2652", "QA A", KEY));
        assertEquals("QA A", HelperGroup.pick_history_sync_author_name("", "QA A", KEY));
        assertEquals("QA A", HelperGroup.pick_history_sync_author_name(null, "QA A", KEY));
        assertEquals("the name kept is cleaned as every group name is",
                     "QA A", HelperGroup.pick_history_sync_author_name("0F2652", " QA A\n", KEY));
    }

    @Test
    public void withoutANameKeptTheEndOfTheKeyStays()
    {
        assertEquals("0F2652", HelperGroup.pick_history_sync_author_name("0F2652", null, KEY));
        assertEquals("0F2652", HelperGroup.pick_history_sync_author_name("0F2652", "", KEY));
        assertEquals("0F2652", HelperGroup.pick_history_sync_author_name("0F2652", "-1", KEY));
        assertEquals("the entry came while the author was not known either",
                     "0F2652", HelperGroup.pick_history_sync_author_name("0F2652", "0f2652", KEY));
        assertEquals("", HelperGroup.pick_history_sync_author_name(null, null, KEY));
    }

    @Test
    public void bothBuildersOfTheHistoryTakeTheNameThisWay() throws IOException
    {
        final String source = read("HelperGroup.java");
        for (final String signature : new String[]{
                "private static boolean send_ngch_syncmsg(final String group_identifier, final String peer_pubkey, final GroupMessage m)",
                "private static void send_ngch_syncfile(final String group_identifier, final String peer_pubkey, final GroupMessage m)"})
        {
            final String body = method(source, signature);
            assertTrue(signature, body.contains("final String peer_name = history_sync_author_name(m);"));
            assertFalse(signature, body.contains("tox_group_peer_get_name__wrapper("));
        }
    }

    /** The text of the method that starts with signature, up to its closing brace at class level. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + "src/main/java/com/zoffcc/applications/trifa/" + name);
            if (f.isFile())
            {
                return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

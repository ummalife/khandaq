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
 * KHANDAQ (redesign, stage 5): a tap on a link, an address or an ID in a message asks in the app's language
 * (AirLinkQuestion) and does what the old English dialogs did; a group mention is drawn in ink.
 */
public class AirLinkQuestionTest
{
    /** The rows of a conversation, of a group and of an old conference that people tap links in. */
    private static final String[] MESSAGE_ROWS = {
            "MessageListHolder_text_incoming_not_read.java",
            "MessageListHolder_text_outgoing_read.java",
            "MessageListHolder_text_outgoing_not_read.java",
            "GroupMessageListHolder_text_incoming_not_read.java",
            "GroupMessageBubbleTextHelper.java",
            "ConferenceMessageListHolder_text_incoming_not_read.java",
            "ConferenceMessageListHolder_text_outgoing_read.java",
    };

    @Test
    public void a_link_without_a_scheme_opens_as_https()
    {
        assertEquals("https://khandaq.org", AirLinkQuestion.withScheme("khandaq.org"));
        assertEquals("https://khandaq.org/a?b=c", AirLinkQuestion.withScheme(" khandaq.org/a?b=c "));
        assertEquals("http://example.com", AirLinkQuestion.withScheme("http://example.com"));
        assertEquals("ftp://example.com/x", AirLinkQuestion.withScheme("ftp://example.com/x"));
        assertEquals("https://", AirLinkQuestion.withScheme(null));
    }

    @Test
    public void an_id_is_added_in_capitals_without_spaces_or_prefix()
    {
        final String id = "872F45C1BAF8AC12C854003E6420C7459339056FF6E40E5BAD8B123AC397E117B66A6A0E90B7";
        assertEquals(id, AirLinkQuestion.bareId("tox:" + id.toLowerCase()));
        assertEquals(id, AirLinkQuestion.bareId("TOX:" + id));
        assertEquals(id, AirLinkQuestion.bareId(" " + id.substring(0, 20) + " " + id.substring(20) + " "));
        assertEquals(id, AirLinkQuestion.bareId(id));
        assertEquals("", AirLinkQuestion.bareId(null));
    }

    @Test
    public void no_message_row_asks_in_english_words() throws IOException
    {
        for (final String name : MESSAGE_ROWS)
        {
            final String text = source(name);
            assertFalse(name + " still asks \"open URL?\"", text.contains("\"open URL?\""));
            assertFalse(name + " still asks \"send Email?\"", text.contains("\"send Email?\""));
            assertFalse(name + " still has an old link dialog", text.contains("showDialog_"));
        }
        final String group = source("GroupMessageBubbleTextHelper.java");
        assertTrue(group.contains("AirLinkQuestion.openLink("));
        assertTrue(group.contains("AirLinkQuestion.writeEmail("));
        assertTrue(group.contains("AirLinkQuestion.requestContact("));
    }

    @Test
    public void a_group_mention_is_drawn_in_ink() throws IOException
    {
        final String mentions = source("GroupMentionHelper.java");
        assertFalse("the old dark green vanishes on the dark theme", mentions.contains("R.color.khandaq_primary"));
        assertEquals(2, mentions.split("R\\.color\\.kq_ink\\)", -1).length - 1);
    }

    private static String source(final String name) throws IOException
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"src/main/java", "app/src/main/java"})
        {
            final File file = new File(root, "com/zoffcc/applications/trifa/" + name);
            if (file.isFile())
            {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}

package com.zoffcc.applications.trifa;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Markup this build cannot read (redesign stage 3, item 10; design/DESIGN.md, "Unknown markup, on every
 * client") shows as the text after it, never as the markup itself; replies, mention blocks and plain
 * text read as before.
 */
public class MessageReplyHelperTest
{
    @Test
    public void unknown_markup_shows_the_text_after_it()
    {
        assertEquals("Hello", MessageReplyHelper.textAfterUnknownMarkup("[KQ|v2|poll|42]Hello"));
        assertEquals("Hello", MessageReplyHelper.parse("[KQ|v2|poll|42]\nHello").bodyText);
        // With a footer the text starts after it, as on iOS.
        assertEquals("Hello", MessageReplyHelper.textAfterUnknownMarkup("[KQ|x|1]\nsomething\n[KQ/end]\n  Hello  "));
    }

    @Test
    public void markup_with_nothing_after_it_has_no_text()
    {
        // readableBody puts the sentence of air_message_unknown_format in its place.
        assertEquals("", MessageReplyHelper.textAfterUnknownMarkup("[KQ|v2|poll|42]"));
        assertEquals("", MessageReplyHelper.textAfterUnknownMarkup("[KQ|v2|poll|42] \n "));
        assertEquals("", MessageReplyHelper.textAfterUnknownMarkup("[KQ|no closing bracket"));
    }

    @Test
    public void control_characters_go_and_line_breaks_stay()
    {
        assertEquals("a\nb", MessageReplyHelper.textAfterUnknownMarkup("[KQ|x]\u0000a\nb\u0007"));
    }

    @Test
    public void plain_text_and_mention_blocks_are_not_unknown_markup()
    {
        assertNull(MessageReplyHelper.textAfterUnknownMarkup("Hello [KQ|x] there"));
        assertNull(MessageReplyHelper.textAfterUnknownMarkup("[KQ is a word here"));
        assertNull(MessageReplyHelper.textAfterUnknownMarkup("[KQ|m|ABCD1234:bilal][KQ/m]\n@bilal hi"));
        assertNull(MessageReplyHelper.textAfterUnknownMarkup("[KQ|m]ABCD1234:bilal[|KQ|m]\nhi"));
        assertEquals("Hello [KQ|x] there", MessageReplyHelper.parse("Hello [KQ|x] there").bodyText);
        final String mention = "[KQ|m|ABCD1234:bilal][KQ/m]\n@bilal hi";
        assertEquals(mention, MessageReplyHelper.parse(mention).bodyText);
    }

    @Test
    public void a_reply_reads_as_before()
    {
        final MessageReplyHelper.ParsedMessage parsed =
                MessageReplyHelper.parse("[KQ|7|ABCDEF12|1731370000000|Khandaq Demo]\nHello\n[KQ/end]\nAnswer");
        assertNotNull(parsed.reply);
        assertEquals(7L, parsed.reply.localMessageId);
        assertEquals("Hello", parsed.reply.previewText);
        assertEquals("Answer", parsed.bodyText);
    }

    @Test
    public void a_reply_without_its_footer_is_a_reply()
    {
        // KHANDAQ (#146, as iOS): the first line after the header is the quote, the rest is the body.
        final MessageReplyHelper.ParsedMessage parsed =
                MessageReplyHelper.parse("[KQ|7|ABCDEF12|1731370000000|Demo]\nquoted\nanswer\nsecond line");
        assertNotNull(parsed.reply);
        assertEquals(7L, parsed.reply.localMessageId);
        assertEquals("ABCDEF12", parsed.reply.senderPubkeyTail);
        assertEquals(1731370000000L, parsed.reply.sortTimestampMs);
        assertEquals("Demo", parsed.reply.senderName);
        assertEquals("quoted", parsed.reply.previewText);
        assertEquals("answer\nsecond line", parsed.bodyText);
    }

    @Test
    public void a_reply_without_its_footer_or_a_body_is_only_the_quote()
    {
        final MessageReplyHelper.ParsedMessage parsed =
                MessageReplyHelper.parse("[KQ|7|ABCDEF12|1731370000000|Demo]\n\nquoted  ");
        assertNotNull(parsed.reply);
        assertEquals("quoted", parsed.reply.previewText);
        assertEquals("", parsed.bodyText);
        final MessageReplyHelper.ParsedMessage cut = MessageReplyHelper.parse("[KQ|7|ABCDEF12|1731370000000|Demo]");
        assertNotNull(cut.reply);
        assertEquals("", cut.reply.previewText);
        assertEquals("", cut.bodyText);
    }

    @Test
    public void a_header_that_is_not_a_reply_stays_unknown_markup()
    {
        // Without the message ID and the time as numbers it is not a reply, footer or not.
        final MessageReplyHelper.ParsedMessage parsed = MessageReplyHelper.parse("[KQ|seven|ABCDEF12|soon|Demo]\nquoted\nanswer");
        assertNull(parsed.reply);
        assertEquals("quoted\nanswer", parsed.bodyText);
    }

    @Test
    public void a_body_with_the_footer_words_in_it_keeps_them()
    {
        // Audit #18: the first footer ends the quote, so a body that says "[KQ/end]" keeps it. The form is
        // encode()'s, which also takes the footer words out of a quote (encode itself needs Android's
        // TextUtils, which these JVM tests do not have).
        final String body = "the footer is written [KQ/end] like this";
        final MessageReplyHelper.ParsedMessage parsed =
                MessageReplyHelper.parse("[KQ|9|ABCDEF12|1731370000000|Demo]\na quote\n[KQ/end]\n" + body);
        assertNotNull(parsed.reply);
        assertEquals(9L, parsed.reply.localMessageId);
        assertEquals("a quote", parsed.reply.previewText);
        assertEquals(body, parsed.bodyText);
    }

    @Test
    public void a_legacy_quote_reads_as_before()
    {
        final MessageReplyHelper.ParsedMessage parsed = MessageReplyHelper.parse("----\nquoted\n----\nAnswer");
        assertNotNull(parsed.reply);
        assertEquals("quoted", parsed.reply.previewText);
        assertEquals("Answer", parsed.bodyText);
    }
}

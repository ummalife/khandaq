package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.PushPreviewCryptoTest.repeat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.64): what a notification made from a push preview says, row by row of the table in
 * docs/PUSH_PREVIEWS.md ("Recipient"), with the words of the app's English resources.
 */
public class PushPreviewTextTest
{
    private static final long NOW = 1763571600L;
    private static final String THUMBS_UP = "\uD83D\uDC4D";
    private static final String ANCHOR = repeat("ab", 32);

    /** The English words of res/values/strings.xml. */
    static final PushPreviewText.Labels EN = new PushPreviewText.Labels()
    {
        @Override
        public String appTitle()
        {
            return "Khandaq";
        }

        @Override
        public String newMessage()
        {
            return "New message";
        }

        @Override
        public String newReaction()
        {
            return "New reaction";
        }

        @Override
        public String voice()
        {
            return "\uD83C\uDFA4 Voice message";
        }

        @Override
        public String video()
        {
            return "\uD83C\uDFAC Video";
        }

        @Override
        public String photo()
        {
            return "\uD83D\uDCF7 Photo";
        }

        @Override
        public String file()
        {
            return "\uD83D\uDCCE File";
        }

        @Override
        public String fileNamed(final String name)
        {
            return "\uD83D\uDCCE " + name;
        }

        @Override
        public String reactionToText(final String emoji, final String text)
        {
            return "Reacted " + emoji + " to \"" + text + "\"";
        }

        @Override
        public String reactionToVoice(final String emoji)
        {
            return "Reacted " + emoji + " to your voice message";
        }

        @Override
        public String reactionToVideo(final String emoji)
        {
            return "Reacted " + emoji + " to your video";
        }

        @Override
        public String reactionToPhoto(final String emoji)
        {
            return "Reacted " + emoji + " to your photo";
        }

        @Override
        public String reactionToFile(final String emoji)
        {
            return "Reacted " + emoji + " to your file";
        }

        @Override
        public String reactionToMessage(final String emoji)
        {
            return "Reacted " + emoji + " to your message";
        }

        @Override
        public String location()
        {
            return "\uD83D\uDCCD Location";
        }

        @Override
        public String reactionToLocation(final String emoji)
        {
            return "Reacted " + emoji + " to your location";
        }
    };

    /** KHANDAQ (owner, 28.11): a place is named, not shown as its wire text "khandaq-location:...". */
    @Test
    public void a_place_and_a_reaction_to_it_are_named()
    {
        final String place = HelperLocationMessage.encode(41.0082, 28.9784);
        final PushPreviewText.Shown shown = show(PushPreviewPayload.text(place, null, "Isa", NOW));
        assertEquals("\uD83D\uDCCD Location", shown.body);
        assertFalse(shown.messageText);
        assertEquals("Reacted " + THUMBS_UP + " to your location",
                     show(reaction(PushPreviewPayload.KIND_TEXT, place)).body);
        assertFalse(PushPreviewText.isLocation("Meet me at the khandaq-location: gate"));
        assertTrue(PushPreviewText.isLocation("  KHANDAQ-LOCATION:1.00000,2.00000"));
    }

    @Test
    public void a_text_shows_its_sender_and_its_words()
    {
        final PushPreviewText.Shown shown = show(PushPreviewPayload.text("See you at 5", null, "Isa", NOW));
        assertEquals("Isa", shown.title);
        assertEquals("See you at 5", shown.body);
        assertTrue(shown.messageText);
    }

    @Test
    public void voice_video_and_photo_show_their_labels()
    {
        assertEquals("\uD83C\uDFA4 Voice message", show(file(PushPreviewPayload.KIND_VOICE, null)).body);
        assertEquals("\uD83C\uDFAC Video", show(file(PushPreviewPayload.KIND_VIDEO, null)).body);
        assertEquals("\uD83D\uDCF7 Photo", show(file(PushPreviewPayload.KIND_PHOTO, null)).body);
        assertFalse(show(file(PushPreviewPayload.KIND_PHOTO, null)).messageText);
    }

    @Test
    public void a_file_shows_its_name_when_it_has_one()
    {
        assertEquals("\uD83D\uDCCE report.pdf", show(file(PushPreviewPayload.KIND_FILE, "report.pdf")).body);
        assertEquals("\uD83D\uDCCE File", show(file(PushPreviewPayload.KIND_FILE, null)).body);
        // A name is one line.
        assertEquals("\uD83D\uDCCE a b", show(file(PushPreviewPayload.KIND_FILE, "a\nb")).body);
    }

    @Test
    public void a_reaction_says_what_it_was_to()
    {
        assertEquals("Reacted " + THUMBS_UP + " to \"See you at 5\"",
                     show(reaction(PushPreviewPayload.KIND_TEXT, "See you at 5")).body);
        assertEquals("Reacted " + THUMBS_UP + " to \"two lines\"",
                     show(reaction(PushPreviewPayload.KIND_TEXT, "two\nlines")).body);
        assertEquals("Reacted " + THUMBS_UP + " to your voice message",
                     show(reaction(PushPreviewPayload.KIND_VOICE, null)).body);
        assertEquals("Reacted " + THUMBS_UP + " to your video", show(reaction(PushPreviewPayload.KIND_VIDEO, null)).body);
        assertEquals("Reacted " + THUMBS_UP + " to your photo", show(reaction(PushPreviewPayload.KIND_PHOTO, null)).body);
        assertEquals("Reacted " + THUMBS_UP + " to your file", show(reaction(PushPreviewPayload.KIND_FILE, null)).body);
        // Nothing known of the message, or a text without its words.
        assertEquals("Reacted " + THUMBS_UP + " to your message", show(reaction(null, null)).body);
        assertEquals("Reacted " + THUMBS_UP + " to your message",
                     show(reaction(PushPreviewPayload.KIND_TEXT, null)).body);
        assertFalse(show(reaction(PushPreviewPayload.KIND_TEXT, "x")).messageText);
    }

    @Test
    public void a_reaction_without_its_emoji_is_a_new_reaction()
    {
        assertEquals("New reaction", show(PushPreviewPayload.reaction(null, PushPreviewPayload.KIND_TEXT, "x", ANCHOR,
                                                                      "Isa", NOW)).body);
    }

    @Test
    public void a_group_event_has_the_group_for_its_title_and_the_sender_before_its_body()
    {
        final PushPreviewText.Shown reaction = show(reaction(PushPreviewPayload.KIND_PHOTO, null)
                                                            .inGroup("Team", repeat("0f", 32)));
        assertEquals("Team", reaction.title);
        assertEquals("Isa: Reacted " + THUMBS_UP + " to your photo", reaction.body);

        final PushPreviewText.Shown text = show(PushPreviewPayload.text("one\ntwo", null, "Isa", NOW)
                                                                  .inGroup("Team", repeat("0f", 32)));
        assertEquals("Team", text.title);
        assertEquals("Isa: one two", text.body);
        assertFalse(text.messageText);
    }

    @Test
    public void a_group_without_its_name_has_the_sender_for_its_title()
    {
        final PushPreviewText.Shown shown = show(reaction(PushPreviewPayload.KIND_FILE, null)
                                                         .inGroup(null, repeat("0f", 32)));
        assertEquals("Isa", shown.title);
        assertEquals("Isa: Reacted " + THUMBS_UP + " to your file", shown.body);
    }

    @Test
    public void a_missing_name_is_the_local_name_or_the_app()
    {
        final PushPreviewPayload unnamed = PushPreviewPayload.text("hi", null, null, NOW);
        assertEquals("Ali", PushPreviewText.select(unnamed, "Ali", true, NOW, EN).title);
        assertEquals("Khandaq", PushPreviewText.select(unnamed, null, true, NOW, EN).title);
        assertEquals("Khandaq", PushPreviewText.select(unnamed, "  ", true, NOW, EN).title);
        // The sender's own name comes first.
        assertEquals("Isa", PushPreviewText.select(PushPreviewPayload.text("hi", null, "Isa", NOW), "Ali", true, NOW,
                                                   EN).title);
    }

    @Test
    public void with_previews_off_only_the_sender_is_shown()
    {
        final PushPreviewText.Shown text = PushPreviewText.select(PushPreviewPayload.text("secret", null, "Isa", NOW),
                                                                  null, false, NOW, EN);
        assertEquals("Isa", text.title);
        assertEquals("New message", text.body);
        assertFalse(text.messageText);

        final PushPreviewText.Shown reaction = PushPreviewText.select(
                reaction(PushPreviewPayload.KIND_TEXT, "secret").inGroup("Team", repeat("0f", 32)), null, false, NOW,
                EN);
        assertEquals("Team", reaction.title);
        assertEquals("New reaction", reaction.body);
    }

    @Test
    public void a_preview_older_than_two_days_is_the_fallback()
    {
        final long twoDays = PushPreviewText.MAX_AGE_SECONDS;
        final PushPreviewText.Shown old = PushPreviewText.select(
                PushPreviewPayload.text("hi", null, "Isa", NOW - twoDays - 1), null, true, NOW, EN);
        assertEquals("Khandaq", old.title);
        assertEquals("New message", old.body);
        final PushPreviewText.Shown justInTime = PushPreviewText.select(
                PushPreviewPayload.text("hi", null, "Isa", NOW - twoDays), null, true, NOW, EN);
        assertEquals("hi", justInTime.body);
    }

    @Test
    public void a_text_without_words_is_a_new_message()
    {
        assertEquals("New message", show(PushPreviewPayload.text("", null, "Isa", NOW)).body);
    }

    private static PushPreviewText.Shown show(final PushPreviewPayload p)
    {
        return PushPreviewText.select(p, null, true, NOW, EN);
    }

    private static PushPreviewPayload file(final String kind, final String name)
    {
        return PushPreviewPayload.file(kind, name, repeat("cd", 32), "Isa", NOW);
    }

    private static PushPreviewPayload reaction(final String reactedKind, final String reactedText)
    {
        return PushPreviewPayload.reaction(THUMBS_UP, reactedKind, reactedText, ANCHOR, "Isa", NOW);
    }
}

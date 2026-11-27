package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.khandaq.messenger.R;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertEquals;

/**
 * KHANDAQ (QA live 0.2.61): TalkBack read a reply as a message of its own. An entry speaks as one sentence, the
 * description of its container, and the quote over it was not in that sentence. The sentence now says what the
 * entry answers, as ChatBubbleUiHelper.bind_reply_quote shows it, between the time and the entry's own words.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AirSpokenReplyTest
{
    /**
     * The app's own strings are not in Robolectric's resources here (the unit tests do not include the Android
     * resources), so the two the sentence takes are given as values/strings.xml has them.
     */
    private static Context context()
    {
        final Context base = ApplicationProvider.getApplicationContext();
        final Resources real = base.getResources();
        final Resources strings = new Resources(real.getAssets(), real.getDisplayMetrics(), real.getConfiguration())
        {
            @Override
            public String getString(final int id)
            {
                if (id == R.string.chat_reply_unknown_sender)
                {
                    return "Unknown";
                }
                if (id == R.string.chat_reply_deleted)
                {
                    return "Deleted message";
                }
                return super.getString(id);
            }

            @Override
            public String getString(final int id, final Object... args)
            {
                if (id == R.string.chat_reply_header)
                {
                    return String.format("In reply to %1$s", args);
                }
                return super.getString(id, args);
            }
        };
        return new ContextWrapper(base)
        {
            @Override
            public Resources getResources()
            {
                return strings;
            }
        };
    }

    private static MessageReplyHelper.ReplyMeta reply(final String name, final String preview)
    {
        final MessageReplyHelper.ReplyMeta meta = new MessageReplyHelper.ReplyMeta();
        meta.senderName = name;
        meta.previewText = preview;
        return meta;
    }

    @Test
    public void theQuoteIsSpokenAsItIsShown()
    {
        assertEquals("In reply to QA A, hello from Windows",
                     AirTranscript.spokenQuote(context(), reply("QA A", "hello\nfrom Windows ")));
        assertEquals("In reply to Unknown, hello", AirTranscript.spokenQuote(context(), reply("", "hello")));
        assertEquals("In reply to Unknown, hello", AirTranscript.spokenQuote(context(), reply(null, "hello")));
    }

    @Test
    public void aLongQuoteIsCutWhereTheScreenCutsIt()
    {
        final StringBuilder words = new StringBuilder();
        while (words.length() < 200)
        {
            words.append("word ");
        }
        final String spoken = AirTranscript.spokenQuote(context(), reply("QA A", words.toString()));
        assertEquals("In reply to QA A, " + MessageReplyHelper.previewForDisplay(context(), words.toString(), 160),
                     spoken);
        assertEquals('…', spoken.charAt(spoken.length() - 1));
    }

    /** KHANDAQ (inbox #10, 27.11): a quote of a message deleted here is heard as it is shown, without its words. */
    @Test
    public void theQuoteOfADeletedMessageSaysItWasDeleted()
    {
        final MessageReplyHelper.ReplyMeta deleted = reply("QA A", "Lunch at one?");
        deleted.targetDeleted = true;
        assertEquals("In reply to QA A, Deleted message", AirTranscript.spokenQuote(context(), deleted));
    }

    @Test
    public void anEntryThatShowsNoQuoteSaysNoneEither()
    {
        assertEquals("", AirTranscript.spokenQuote(context(), null));
        assertEquals("", AirTranscript.spokenQuote(context(), reply("QA A", "   ")));
        assertEquals("", AirTranscript.spokenQuote(context(), reply("QA A", null)));
    }

    @Test
    public void aReplySaysWhatItAnswersBetweenItsTimeAndItsWords()
    {
        final String quote = AirTranscript.spokenQuote(context(), reply("Yusuf", "Are we still on for tonight?"));
        assertEquals("Amina, 9:41, In reply to Yusuf, Are we still on for tonight?, See you at the gate",
                     AirTranscript.spoken(context(), "Amina", "9:41", quote, "See you at the gate", false,
                                          AirConversationModel.EntryStatus.NONE));
    }

    @Test
    public void anEntryWithoutAQuoteReadsAsBefore()
    {
        assertEquals("Amina, 9:41, See you at the gate",
                     AirTranscript.spoken(context(), "Amina", "9:41", "", "See you at the gate", false,
                                          AirConversationModel.EntryStatus.NONE));
    }
}

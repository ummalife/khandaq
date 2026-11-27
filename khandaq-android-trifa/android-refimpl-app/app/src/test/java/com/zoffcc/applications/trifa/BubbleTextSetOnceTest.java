package com.zoffcc.applications.trifa;

import android.content.Context;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;

import com.luseen.autolinklibrary.AutoLinkMode;
import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.vanniktech.emoji.EmojiManager;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #20, 27.11): each setting of a bubble's text searches all of it for emoji and lays it out anew.
 * A long bubble had its text set three times: by the holder, again by the link movement method for a text with
 * links, and again by the emoji size, which did not change the pictures the text had. It is set once now.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BubbleTextSetOnceTest
{
    /** The bubble's text, counting how often it is set. */
    private static final class CountingBubble extends EmojiTextViewLinks
    {
        int sets;

        CountingBubble(final Context context)
        {
            super(context);
            addAutoLinkMode(AutoLinkMode.MODE_URL, AutoLinkMode.MODE_HASHTAG, AutoLinkMode.MODE_MENTION);
        }

        @Override
        public void setText(final CharSequence text, final BufferType type)
        {
            sets++;
            super.setText(text, type);
        }
    }

    @BeforeClass
    public static void installTheSet()
    {
        EmojiManager.install(new GoogleEmojiProvider());
    }

    private static CountingBubble bubble()
    {
        final CountingBubble bubble = new CountingBubble(ApplicationProvider.getApplicationContext());
        bubble.sets = 0;
        return bubble;
    }

    @Test
    public void aTextWithLinksIsSetOnce()
    {
        final CountingBubble bubble = bubble();
        bubble.setAutoLinkText("@Override public void run() { } // see https://example.com #notes");
        assertEquals(1, bubble.sets);
        assertNotNull("its links are tappable", bubble.getMovementMethod());
        assertTrue(bubble.getText() instanceof Spannable);
    }

    @Test
    public void aTextWithoutLinksIsSetAsBefore()
    {
        final CountingBubble bubble = bubble();
        bubble.setAutoLinkText("plain words 😀");
        assertEquals(1, bubble.sets);
        // Not a Spannable, so the bubble lays it out as it did.
        assertFalse(bubble.getText() instanceof Spannable);
        assertNull(bubble.getMovementMethod());
    }

    @Test
    public void aTextWithoutLinksAfterOneWithLinksIsSetOnceAndTheOtherWayRound()
    {
        final CountingBubble bubble = bubble();
        bubble.setAutoLinkText("see https://example.com");
        bubble.sets = 0;
        bubble.setAutoLinkText("plain words");
        assertEquals(1, bubble.sets);
        assertNull("plain words keep the long press for the bubble", bubble.getMovementMethod());
        bubble.sets = 0;
        bubble.setAutoLinkText("again https://example.org");
        assertEquals(1, bubble.sets);
        assertNotNull(bubble.getMovementMethod());
    }

    @Test
    public void theEmojiSizeLeavesTheTextAndTakesTheNextOne()
    {
        final CountingBubble bubble = bubble();
        bubble.applyEmojiSize(48);
        bubble.setAutoLinkText("hello 😀");
        final Object picture = picture(bubble);
        bubble.sets = 0;
        bubble.applyEmojiSize(48);
        bubble.applyEmojiSize(96);
        assertEquals(0, bubble.sets);
        assertEquals(96f, bubble.getEmojiSize(), 0f);
        // The picture the text has stays, as it did when the text was set again: EmojiTextView.setText keeps it.
        assertSame(picture, picture(bubble));
        bubble.setAutoLinkText("hello 😀");
        assertEquals(1, bubble.sets);
        assertNotSame(picture, picture(bubble));
    }

    /** The one picture of the set on the bubble's text. */
    private static Object picture(final CountingBubble bubble)
    {
        final Spanned text = (Spanned) bubble.getText();
        final DynamicDrawableSpan[] pictures = text.getSpans(0, text.length(), DynamicDrawableSpan.class);
        assertEquals(1, pictures.length);
        return pictures[0];
    }
}

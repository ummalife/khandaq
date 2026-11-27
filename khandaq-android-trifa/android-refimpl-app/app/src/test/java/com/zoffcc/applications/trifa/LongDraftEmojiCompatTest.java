package com.zoffcc.applications.trifa;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #20, 27.11): EmojiCompat laid all of the input field's text out again for each typed char, so a
 * long draft made every key slow on the main thread. It is off while the field holds a long text.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LongDraftEmojiCompatTest
{
    private static String text(final int length)
    {
        final char[] chars = new char[length];
        Arrays.fill(chars, 'a');
        return new String(chars);
    }

    private static AppCompatEditText field()
    {
        return new AppCompatEditText(new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                                                             androidx.appcompat.R.style.Theme_AppCompat));
    }

    @Test
    public void emojiCompatIsOffBeforeALongTextComesInAndOnOnceItIsShort()
    {
        final AppCompatEditText field = field();
        ChatInputBarHelper.keepEmojiCompatToShortTexts(field);
        assertTrue(field.isEmojiCompatEnabled());
        // What EmojiCompat's own watcher, the field's first, finds when it looks at a change.
        final List<Boolean> onWhenChanged = new ArrayList<>();
        field.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(final CharSequence text, final int start, final int count, final int after)
            {
            }

            @Override
            public void onTextChanged(final CharSequence text, final int start, final int before, final int count)
            {
                onWhenChanged.add(field.isEmojiCompatEnabled());
            }

            @Override
            public void afterTextChanged(final Editable text)
            {
            }
        });

        field.setText(text(40_000));
        assertFalse(field.isEmojiCompatEnabled());
        field.getText().append('b');
        assertFalse(field.isEmojiCompatEnabled());
        field.setText("short");
        assertTrue(field.isEmojiCompatEnabled());
        assertEquals(Arrays.asList(false, false, false), onWhenChanged);
    }

    @Test
    public void theLineIsTheLongestTextThatKeepsIt()
    {
        final AppCompatEditText field = field();
        ChatInputBarHelper.keepEmojiCompatToShortTexts(field);
        field.setText(text(ChatInputBarHelper.EMOJI_COMPAT_UP_TO));
        assertTrue(field.isEmojiCompatEnabled());
        field.getText().append('b');
        assertFalse(field.isEmojiCompatEnabled());
        final Editable text = field.getText();
        text.delete(text.length() - 1, text.length());
        assertTrue(field.isEmojiCompatEnabled());
    }

    @Test
    public void aFieldThatHoldsALongTextAlreadyHasItOff()
    {
        final AppCompatEditText field = field();
        field.setText(text(5_000));
        ChatInputBarHelper.keepEmojiCompatToShortTexts(field);
        assertFalse(field.isEmojiCompatEnabled());
    }
}

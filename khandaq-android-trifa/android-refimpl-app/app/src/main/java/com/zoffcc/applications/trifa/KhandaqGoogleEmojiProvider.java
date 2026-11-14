package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.style.DynamicDrawableSpan;

import com.vanniktech.emoji.EmojiProvider;
import com.vanniktech.emoji.EmojiReplacer;
import com.vanniktech.emoji.emoji.Emoji;
import com.vanniktech.emoji.emoji.EmojiCategory;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Google emoji set with Khandaq-specific filtering (see {@link KhandaqEmojiFilter}).
 *
 * <p>Also the EmojiReplacer of the app: EmojiManager.install() takes a provider that is one as the
 * replacer of every EmojiTextView and EmojiEditText. It puts the set's pictures on a text as the
 * library does, and then leaves the joined sequences the set has no picture of to the system font
 * ({@link KhandaqJoinedEmoji}).
 */
public final class KhandaqGoogleEmojiProvider implements EmojiProvider, EmojiReplacer
{
    private static final GoogleEmojiProvider DELEGATE = new GoogleEmojiProvider();

    /**
     * The span of a picture of the set. The class is package-private in the library, so it is known by
     * its name; release builds keep the names (-dontobfuscate in proguard-rules.pro).
     */
    private static final String SET_PICTURE_SPAN = "com.vanniktech.emoji.EmojiSpan";

    private static final int FONT_ANSWERS_KEPT = 256;

    /** The system font's answers, by sequence: asked once per sequence, as texts are drawn again and again. */
    private static final Map<String, Boolean> FONT_HAS_IT =
            new LinkedHashMap<String, Boolean>(32, 0.75f, true)
            {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest)
                {
                    return size() > FONT_ANSWERS_KEPT;
                }
            };

    private static Paint fontPaint;

    /**
     * Categories whose contents are, essentially in their entirety, depictions of living beings.
     * Only dropped when the user asks for it — see {@link KhandaqEmojiFilter#hideLivingBeings()}.
     */
    private static boolean isLivingBeingCategory(final EmojiCategory category)
    {
        final String name = category.getClass().getSimpleName();
        return name.startsWith("SmileysAndPeopleCategory") || name.startsWith("AnimalsAndNatureCategory");
    }

    @Override
    public EmojiCategory[] getCategories()
    {
        final EmojiCategory[] original = DELEGATE.getCategories();
        final List<EmojiCategory> out = new ArrayList<>(original.length);

        final boolean hideLiving = KhandaqEmojiFilter.hideLivingBeings();

        for (final EmojiCategory category : original)
        {
            if (category == null)
            {
                continue;
            }

            // Dropping a whole category rather than emptying it: an EmojiCategory with zero emojis
            // still gets a tab in the picker, and a tab that opens onto nothing reads as a bug.
            if (hideLiving && isLivingBeingCategory(category))
            {
                continue;
            }

            out.add(new FilteredEmojiCategory(category, KhandaqEmojiFilter::isBlocked));
        }

        return out.toArray(new EmojiCategory[0]);
    }

    /**
     * The set's pictures, as the library puts them, and then the joined sequences the set has no
     * picture of left to the system font (owner, 2026-11-14; QA live F14).
     */
    @Override
    public void replaceWithImages(final Context context, final Spannable text, final float emojiSize,
                                  final EmojiReplacer defaultReplacer)
    {
        if (defaultReplacer == null || text == null)
        {
            return;
        }
        defaultReplacer.replaceWithImages(context, text, emojiSize, defaultReplacer);
        try
        {
            leaveJoinedSequencesToTheFont(text);
        }
        catch (Exception e)
        {
            // The set's pictures stay: a sequence drawn in pieces is a far smaller problem than a chat
            // screen that cannot draw its text.
        }
    }

    private static void leaveJoinedSequencesToTheFont(final Spannable text)
    {
        for (final int[] sequence : KhandaqJoinedEmoji.joinedSequences(text))
        {
            final int start = sequence[0];
            final int end = sequence[1];
            final List<Object> pictures = new ArrayList<>();
            final List<int[]> ranges = new ArrayList<>();
            for (final DynamicDrawableSpan span : text.getSpans(start, end, DynamicDrawableSpan.class))
            {
                if (!SET_PICTURE_SPAN.equals(span.getClass().getName()))
                {
                    continue;
                }
                final int spanStart = text.getSpanStart(span);
                final int spanEnd = text.getSpanEnd(span);
                if (spanStart < start || spanEnd > end)
                {
                    continue;
                }
                pictures.add(span);
                ranges.add(new int[]{spanStart, spanEnd});
            }
            if (KhandaqJoinedEmoji.takePicturesOff(text, start, end, ranges,
                                                    KhandaqGoogleEmojiProvider::fontHasGlyph))
            {
                for (final Object picture : pictures)
                {
                    text.removeSpan(picture);
                }
            }
        }
    }

    /** Whether the system font draws the sequence as one glyph (Paint.hasGlyph: a ligature of its members). */
    private static synchronized boolean fontHasGlyph(final String sequence)
    {
        Boolean known = FONT_HAS_IT.get(sequence);
        if (known == null)
        {
            if (fontPaint == null)
            {
                fontPaint = new Paint();
            }
            known = fontPaint.hasGlyph(sequence);
            FONT_HAS_IT.put(sequence, known);
        }
        return known;
    }

    /**
     * Kept as a named method because the flag category used to be the only filtered one and the
     * rainbow-flag test is worth being able to point at on its own.
     */
    static boolean isBlockedFlagEmoji(final Emoji emoji)
    {
        return KhandaqEmojiFilter.isBlocked(emoji);
    }
}

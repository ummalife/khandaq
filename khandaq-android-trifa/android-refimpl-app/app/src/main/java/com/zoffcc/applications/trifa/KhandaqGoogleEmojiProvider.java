package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.DynamicDrawableSpan;

import androidx.annotation.Nullable;

import com.vanniktech.emoji.EmojiProvider;
import com.vanniktech.emoji.EmojiReplacer;
import com.vanniktech.emoji.emoji.Emoji;
import com.vanniktech.emoji.emoji.EmojiCategory;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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

    /** KHANDAQ (inbox #20, 27.11): what the set's search found in the windows it searched (putSetPictures). */
    private final FoundInWindows foundInWindows = new FoundInWindows();

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
        putSetPictures(context, text, emojiSize, defaultReplacer, foundInWindows);
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

    /**
     * KHANDAQ (inbox #20, 27.11): the set's pictures, as the library puts them (defaultReplacer), with the same
     * result: on a long text only where emoji can be (EmojiSearchWindows), and there only in the windows where the
     * search could put a picture (FoundInWindows). The library's search of a long text took long enough on the main
     * thread for Android to say that Khandaq was not responding.
     */
    static void putSetPictures(final Context context, final Spannable text, final float emojiSize,
                               final EmojiReplacer defaultReplacer, final FoundInWindows foundInWindows)
    {
        final List<int[]> windows = text.length() > EmojiSearchWindows.WHOLE_TEXT_UP_TO
                                    ? SetSearchWindows.windows(text) : null;
        if (windows == null)
        {
            defaultReplacer.replaceWithImages(context, text, emojiSize, defaultReplacer);
            return;
        }
        if (windows.isEmpty())
        {
            return;
        }
        final BitSet pictured = setPictureStarts(text);
        final List<int[]> toSearch = new ArrayList<>();
        final List<String> charsToSearch = new ArrayList<>();
        for (final int[] window : windows)
        {
            final String chars = TextUtils.substring(text, window[0], window[1]);
            final List<Integer> found = foundInWindows.get(chars);
            if (found != null && allPictured(pictured, window[0], found))
            {
                continue;
            }
            toSearch.add(window);
            charsToSearch.add(chars);
        }
        if (toSearch.isEmpty())
        {
            return;
        }
        // The chars of the windows, one after another with EmojiSearchWindows.BETWEEN_WINDOWS between two, are
        // searched at once: a search costs more to start than the few chars of a window cost to search.
        final int[] startsThere = new int[toSearch.size()];
        final List<List<Integer>> foundThere = new ArrayList<>(toSearch.size());
        final StringBuilder chars = new StringBuilder();
        for (int k = 0; k < toSearch.size(); k++)
        {
            if (k > 0)
            {
                chars.append(EmojiSearchWindows.BETWEEN_WINDOWS);
            }
            startsThere[k] = chars.length();
            chars.append(charsToSearch.get(k));
            foundThere.add(new ArrayList<>(2));
        }
        final SpannableStringBuilder searched = new SpannableStringBuilder(chars);
        defaultReplacer.replaceWithImages(context, searched, emojiSize, defaultReplacer);
        // Its pictures go to the text, each where its window is: as the library does, a place that has a picture
        // of the set keeps it.
        for (final DynamicDrawableSpan picture : searched.getSpans(0, searched.length(), DynamicDrawableSpan.class))
        {
            if (!SET_PICTURE_SPAN.equals(picture.getClass().getName()))
            {
                continue;
            }
            final int pictureStartThere = searched.getSpanStart(picture);
            int k = Arrays.binarySearch(startsThere, pictureStartThere);
            if (k < 0)
            {
                k = -k - 2;
            }
            foundThere.get(k).add(pictureStartThere - startsThere[k]);
            final int shift = toSearch.get(k)[0] - startsThere[k];
            final int pictureStart = pictureStartThere + shift;
            if (pictured.get(pictureStart))
            {
                continue;
            }
            text.setSpan(picture, pictureStart, searched.getSpanEnd(picture) + shift, searched.getSpanFlags(picture));
        }
        for (int k = 0; k < toSearch.size(); k++)
        {
            foundInWindows.put(charsToSearch.get(k), foundThere.get(k));
        }
    }

    /** The places where a picture of the set starts. */
    private static BitSet setPictureStarts(final Spanned text)
    {
        final BitSet starts = new BitSet(text.length());
        for (final DynamicDrawableSpan span : text.getSpans(0, text.length(), DynamicDrawableSpan.class))
        {
            if (SET_PICTURE_SPAN.equals(span.getClass().getName()))
            {
                starts.set(text.getSpanStart(span));
            }
        }
        return starts;
    }

    private static boolean allPictured(final BitSet pictured, final int windowStart, final List<Integer> found)
    {
        for (final int start : found)
        {
            if (!pictured.get(windowStart + start))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * KHANDAQ (inbox #20, 27.11): where the set's search found emoji in the windows it searched, by the chars of the
     * window: its chars alone decide what the search finds in a window, wherever the window is (EmojiSearchWindows).
     * A field is searched again for each typed char; a window there with the chars of one searched before, and a
     * picture of the set in each place found, is not searched again, as the search would put no picture in it.
     * Each installed provider has its own: what the search finds depends on the set as it was installed.
     */
    static final class FoundInWindows
    {
        /** How many windows are kept, the last asked for. */
        private static final int KEPT = 512;

        /** The longest window kept: a long run of emoji is rare, and is searched each time. */
        private static final int LONGEST = 64;

        private final Map<String, List<Integer>> found =
                new LinkedHashMap<String, List<Integer>>(64, 0.75f, true)
                {
                    @Override
                    protected boolean removeEldestEntry(final Map.Entry<String, List<Integer>> eldest)
                    {
                        return size() > KEPT;
                    }
                };

        /** Where the search found emoji in a window with these chars, from the start of the window; null if unknown. */
        @Nullable
        synchronized List<Integer> get(final String chars)
        {
            return found.get(chars);
        }

        synchronized void put(final String chars, final List<Integer> starts)
        {
            if (chars.length() <= LONGEST)
            {
                found.put(chars, starts);
            }
        }
    }

    /**
     * KHANDAQ (inbox #20, 27.11): the search windows of the set, made the first time a long text is searched.
     * They take every emoji of the set and its variants, as the library's pattern has them: the filters of
     * getCategories only take emoji away, so the windows hold every place the installed pattern can match.
     */
    private static final class SetSearchWindows
    {
        @Nullable
        private static final EmojiSearchWindows WINDOWS = EmojiSearchWindows.of(setUnicodes());

        private SetSearchWindows()
        {
        }

        @Nullable
        static List<int[]> windows(final CharSequence text)
        {
            return WINDOWS == null ? null : WINDOWS.windows(text);
        }

        private static List<String> setUnicodes()
        {
            final List<String> unicodes = new ArrayList<>();
            final EmojiCategory[] categories = DELEGATE.getCategories();
            if (categories == null)
            {
                return unicodes;
            }
            for (final EmojiCategory category : categories)
            {
                final Emoji[] emojis = category == null ? null : category.getEmojis();
                if (emojis == null)
                {
                    continue;
                }
                for (final Emoji emoji : emojis)
                {
                    if (emoji == null)
                    {
                        continue;
                    }
                    unicodes.add(emoji.getUnicode());
                    for (final Emoji variant : emoji.getVariants())
                    {
                        unicodes.add(variant.getUnicode());
                    }
                }
            }
            return unicodes;
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

package com.zoffcc.applications.trifa;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.DynamicDrawableSpan;

import com.vanniktech.emoji.EmojiManager;
import com.vanniktech.emoji.EmojiReplacer;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #20, 27.11): on a long text the set's pictures are put window by window (EmojiSearchWindows),
 * and they must be the very pictures the library puts when it searches the whole text: the same places, none
 * more, none fewer, and a place with a picture keeps it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KhandaqEmojiPicturesTest
{
    private static final String SET_PICTURE = "com.vanniktech.emoji.EmojiSpan";
    private static final float SIZE = 40f;

    /** The library's own way of putting the pictures, with its search of the whole text. */
    private static EmojiReplacer library;
    private static List<String> appSet;

    /** What the search found in windows, kept across the texts of a test as the installed provider keeps it. */
    private static final KhandaqGoogleEmojiProvider.FoundInWindows FOUND =
            new KhandaqGoogleEmojiProvider.FoundInWindows();

    @BeforeClass
    public static void installTheSet() throws Exception
    {
        EmojiManager.install(new GoogleEmojiProvider());
        final Field field = EmojiManager.class.getDeclaredField("DEFAULT_EMOJI_REPLACER");
        field.setAccessible(true);
        library = (EmojiReplacer) field.get(null);
        appSet = EmojiSearchWindowsTest.appSet();
    }

    private static Context context()
    {
        return ApplicationProvider.getApplicationContext();
    }

    /** The places of the set's pictures, "start-end" each, in the order of the text. */
    private static List<String> pictures(final Spanned text)
    {
        final List<int[]> places = new ArrayList<>();
        for (final DynamicDrawableSpan span : text.getSpans(0, text.length(), DynamicDrawableSpan.class))
        {
            if (SET_PICTURE.equals(span.getClass().getName()))
            {
                places.add(new int[]{text.getSpanStart(span), text.getSpanEnd(span)});
            }
        }
        places.sort(Comparator.comparingInt((int[] place) -> place[0]).thenComparingInt(place -> place[1]));
        final List<String> named = new ArrayList<>(places.size());
        for (final int[] place : places)
        {
            named.add(place[0] + "-" + place[1]);
        }
        return named;
    }

    /** The pictures the library puts on the text, and those put window by window, which must be the same. */
    private static List<String> assertSameAsTheLibrary(final String text)
    {
        final SpannableStringBuilder whole = new SpannableStringBuilder(text);
        library.replaceWithImages(context(), whole, SIZE, library);
        final SpannableStringBuilder windowed = new SpannableStringBuilder(text);
        KhandaqGoogleEmojiProvider.putSetPictures(context(), windowed, SIZE, library, FOUND);
        final List<String> expected = pictures(whole);
        assertEquals(expected, pictures(windowed));
        return expected;
    }

    @Test
    public void longCodeWithEmojiHereAndThereGetsThePicturesTheLibraryPuts()
    {
        final String code = EmojiSearchWindowsTest.code(700);
        final String text = "😀" + code
                            // A keycap after digits, the number sign keycap and a flag.
                            + "1024" + "7️⃣" + "#️⃣" + "🇷🇺" + code
                            // A family, a thumb with a skin tone, a heart, the copyright and trade mark signs.
                            + "👨‍👩‍👧" + " 👍🏽"
                            + " ❤️ © ™️" + code
                            // Emoji side by side, among digits, a lone selector and a lone keycap mark.
                            + "😀😀😀 2023😀1 ️ 5⃣ " + code
                            + "↔️";
        assertTrue(text.length() > EmojiSearchWindows.WHOLE_TEXT_UP_TO);
        assertFalse("the text has pictures to compare", assertSameAsTheLibrary(text).isEmpty());
    }

    @Test
    public void digitsAndSignsRightByTheEmojiGetThePicturesTheLibraryPuts()
    {
        final String code = EmojiSearchWindowsTest.code(1100);
        // A keycap at the very start, after digits, three side by side and one without the selector; the keycap
        // mark after a grin; number and asterisk keycaps; flags side by side with an odd regional indicator; a
        // grin between digits and one at the very end.
        final String text = "7️⃣" + code + "12️⃣ 1️⃣2️⃣3️⃣ 9⃣ "
                            + "😀⃣ #️⃣#️⃣*️⃣" + code
                            + "🇷🇺🇷🇺🇷 2023😀2024 " + code
                            + "😀";
        assertTrue(text.length() > EmojiSearchWindows.WHOLE_TEXT_UP_TO);
        assertTrue("the text has pictures to compare", assertSameAsTheLibrary(text).size() >= 10);
    }

    @Test
    public void randomTextsGetThePicturesTheLibraryPuts()
    {
        final Random random = new Random(20261127L);
        final String code = EmojiSearchWindowsTest.code(400);
        // Pieces that make and break emoji: the joiner, the selectors, the keycap mark, a regional indicator,
        // a skin tone, a small circled m and digits.
        final String[] pieces = {"‍", "️", "︎", "⃣", "🇷", "🏽", "ⓜ",
                                 "0", "#", "*", " ", "\n"};
        int pictures = 0;
        for (int round = 0; round < 12; round++)
        {
            final StringBuilder text = new StringBuilder();
            final int length = 1100 + random.nextInt(1500);
            while (text.length() < length)
            {
                final int kind = random.nextInt(10);
                if (kind < 5)
                {
                    final int from = random.nextInt(code.length() - 60);
                    text.append(code, from, from + 1 + random.nextInt(60));
                }
                else if (kind < 8)
                {
                    text.append(appSet.get(random.nextInt(appSet.size())));
                }
                else
                {
                    text.append(pieces[random.nextInt(pieces.length)]);
                }
            }
            pictures += assertSameAsTheLibrary(text.toString()).size();
        }
        assertTrue("the texts have pictures to compare", pictures > 50);
    }

    @Test
    public void longCodeWithoutEmojiGetsNoPicture()
    {
        assertTrue(assertSameAsTheLibrary(EmojiSearchWindowsTest.code(5000)).isEmpty());
    }

    @Test
    public void aPlaceThatHasAPictureKeepsIt()
    {
        final String code = EmojiSearchWindowsTest.code(1500);
        final SpannableStringBuilder twice = new SpannableStringBuilder(code + "😀 ❤️" + code);
        KhandaqGoogleEmojiProvider.putSetPictures(context(), twice, SIZE, library, FOUND);
        final List<String> once = pictures(twice);
        final Set<Object> before = Collections.newSetFromMap(new IdentityHashMap<>());
        before.addAll(Arrays.asList(twice.getSpans(0, twice.length(), DynamicDrawableSpan.class)));
        KhandaqGoogleEmojiProvider.putSetPictures(context(), twice, SIZE, library, FOUND);
        assertEquals(once, pictures(twice));
        assertEquals(2, once.size());
        // The same pictures, not new ones in their places.
        final Set<Object> after = Collections.newSetFromMap(new IdentityHashMap<>());
        after.addAll(Arrays.asList(twice.getSpans(0, twice.length(), DynamicDrawableSpan.class)));
        assertEquals(before, after);
    }

    /** The library's way of putting the pictures, with the texts it is asked to search. */
    private static EmojiReplacer asking(final List<String> asked)
    {
        return (context, text, emojiSize, fallback) ->
        {
            asked.add(text.toString());
            library.replaceWithImages(context, text, emojiSize, fallback);
        };
    }

    @Test
    public void typingInALongFieldSearchesOnlyTheWindowsThatChanged()
    {
        final String code = EmojiSearchWindowsTest.code(1500);
        final SpannableStringBuilder field = new SpannableStringBuilder(code + "\"✅ ok" + code + " ⚠️ " + code);
        final KhandaqGoogleEmojiProvider.FoundInWindows found = new KhandaqGoogleEmojiProvider.FoundInWindows();
        final List<String> asked = new ArrayList<>();
        final EmojiReplacer replacer = asking(asked);
        KhandaqGoogleEmojiProvider.putSetPictures(context(), field, SIZE, replacer, found);
        assertEquals(1, asked.size());
        assertEquals(2, pictures(field).size());
        // A char typed far from the emoji, and one before all of them: the windows move, their chars stay.
        field.append('x');
        KhandaqGoogleEmojiProvider.putSetPictures(context(), field, SIZE, replacer, found);
        field.insert(0, "y");
        KhandaqGoogleEmojiProvider.putSetPictures(context(), field, SIZE, replacer, found);
        assertEquals(1, asked.size());
        // An emoji typed in the middle: its window alone is searched.
        field.insert(2000, "😀");
        KhandaqGoogleEmojiProvider.putSetPictures(context(), field, SIZE, replacer, found);
        assertEquals(2, asked.size());
        assertEquals(field.subSequence(1999, 2002).toString(), asked.get(1));
        assertEquals(theLibrarysPictures(field.toString()), pictures(field));
    }

    @Test
    public void aWindowWhoseCharsWereSearchedButLacksAPictureIsSearchedAgain()
    {
        final String code = EmojiSearchWindowsTest.code(1500);
        final SpannableStringBuilder field = new SpannableStringBuilder(code + "x😀😀" + code);
        final KhandaqGoogleEmojiProvider.FoundInWindows found = new KhandaqGoogleEmojiProvider.FoundInWindows();
        KhandaqGoogleEmojiProvider.putSetPictures(context(), field, SIZE, library, found);
        final List<String> both = pictures(field);
        assertEquals(2, both.size());
        // The first grin is deleted, its picture with it, and typed again: the window has the chars searched before,
        // and the new grin no picture yet.
        field.delete(1501, 1503);
        KhandaqGoogleEmojiProvider.putSetPictures(context(), field, SIZE, library, found);
        field.insert(1501, "😀");
        KhandaqGoogleEmojiProvider.putSetPictures(context(), field, SIZE, library, found);
        assertEquals(both, pictures(field));
        // A picture taken off is put back, as the library puts it back.
        for (final DynamicDrawableSpan span : field.getSpans(1501, 1501, DynamicDrawableSpan.class))
        {
            field.removeSpan(span);
        }
        assertEquals(1, pictures(field).size());
        KhandaqGoogleEmojiProvider.putSetPictures(context(), field, SIZE, library, found);
        assertEquals(both, pictures(field));
    }

    @Test
    public void editsOfALongFieldKeepThePicturesTheLibraryPuts()
    {
        final Random random = new Random(27112026L);
        final String code = EmojiSearchWindowsTest.code(400);
        final String[] pieces = {"‍", "️", "⃣", "🇷", "🏽", "0", "#", " "};
        final String start = EmojiSearchWindowsTest.code(1200) + "😀 7️⃣ ❤️ 🇷🇺" + EmojiSearchWindowsTest.code(600);
        // The same edits on two fields: the library searches one whole, the app the other one window by window.
        final SpannableStringBuilder theLibrarys = new SpannableStringBuilder(start);
        final SpannableStringBuilder ours = new SpannableStringBuilder(start);
        final KhandaqGoogleEmojiProvider.FoundInWindows found = new KhandaqGoogleEmojiProvider.FoundInWindows();
        int pictures = 0;
        for (int edit = 0; edit < 150; edit++)
        {
            final int at = random.nextInt(ours.length() + 1);
            final int kind = random.nextInt(10);
            if (kind < 3 && ours.length() > 1500)
            {
                final int end = Math.min(ours.length(), at + 1 + random.nextInt(12));
                theLibrarys.delete(at, end);
                ours.delete(at, end);
            }
            else
            {
                final String inserted;
                if (kind < 6)
                {
                    inserted = appSet.get(random.nextInt(appSet.size()));
                }
                else if (kind < 8)
                {
                    inserted = pieces[random.nextInt(pieces.length)];
                }
                else
                {
                    final int from = random.nextInt(code.length() - 30);
                    inserted = code.substring(from, from + 1 + random.nextInt(30));
                }
                theLibrarys.insert(at, inserted);
                ours.insert(at, inserted);
            }
            library.replaceWithImages(context(), theLibrarys, SIZE, library);
            KhandaqGoogleEmojiProvider.putSetPictures(context(), ours, SIZE, library, found);
            assertEquals("edit " + edit, pictures(theLibrarys), pictures(ours));
            pictures = pictures(ours).size();
        }
        assertTrue("the field has pictures to compare: " + pictures, pictures > 20);
    }

    private static List<String> theLibrarysPictures(final String text)
    {
        final SpannableStringBuilder whole = new SpannableStringBuilder(text);
        library.replaceWithImages(context(), whole, SIZE, library);
        return pictures(whole);
    }
}

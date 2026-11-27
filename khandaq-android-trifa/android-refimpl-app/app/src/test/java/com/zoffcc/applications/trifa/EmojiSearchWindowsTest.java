package com.zoffcc.applications.trifa;

import com.vanniktech.emoji.emoji.Emoji;
import com.vanniktech.emoji.emoji.EmojiCategory;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #20, 27.11): Android said that Khandaq was not responding in a chat with a long piece of code.
 * The emoji set's search of a long text ran over all of it on the main thread, three times for a bubble and
 * once for each change of the input field; it runs now only where emoji can be.
 */
public class EmojiSearchWindowsTest
{
    private static final String GRIN = "😀";
    private static final String KEYCAP_SEVEN = "7️⃣";
    private static final String CIRCLED_M = "Ⓜ️";

    /** Every emoji of the set the app installs, and its variants. */
    static List<String> appSet()
    {
        final List<String> unicodes = new ArrayList<>();
        for (final EmojiCategory category : new GoogleEmojiProvider().getCategories())
        {
            for (final Emoji emoji : category.getEmojis())
            {
                unicodes.add(emoji.getUnicode());
                for (final Emoji variant : emoji.getVariants())
                {
                    unicodes.add(variant.getUnicode());
                }
            }
        }
        return unicodes;
    }

    /**
     * Code with digits, the punctuation of writing and comments in Russian, Arabic and Chinese, and no emoji:
     * "total", "sum" and "the sum" in the three languages, a dash, guillemets, the numero sign and an arrow.
     */
    static String code(final int length)
    {
        final String line = "for (int i = 0; i < 1024; i++) { total += values[i] * 31 + 0x7F; } // "
                            + "итог — «№ 1» → المج"
                            + "موع 总和 café\n";
        final StringBuilder text = new StringBuilder(length + line.length());
        while (text.length() < length)
        {
            text.append(line);
        }
        text.setLength(length);
        return text.toString();
    }

    private static EmojiSearchWindows set(final String... emoji)
    {
        final EmojiSearchWindows windows = EmojiSearchWindows.of(Arrays.asList(emoji));
        assertNotNull(windows);
        return windows;
    }

    private static boolean inside(final List<int[]> windows, final int start, final int end)
    {
        for (final int[] window : windows)
        {
            if (window[0] <= start && end <= window[1])
            {
                return true;
            }
        }
        return false;
    }

    @Test
    public void theSetOfTheAppHasWindows()
    {
        final EmojiSearchWindows windows = EmojiSearchWindows.of(appSet());
        assertNotNull("every emoji of the set has a char outside ASCII", windows);
        // Its only chars in ASCII are the digit or sign of a keycap, before the mark.
        assertEquals(1, windows.before);
        assertEquals(0, windows.after);
        assertEquals(0, windows.between);
    }

    @Test
    public void longCodeWithoutEmojiHasNothingToSearch()
    {
        final EmojiSearchWindows windows = EmojiSearchWindows.of(appSet());
        assertNotNull(windows);
        assertEquals(Collections.emptyList(), windows.windows(code(45_000)));
    }

    @Test
    public void theCharsOfEmojiCountAndTheCharsOfWritingDoNot()
    {
        final EmojiSearchWindows windows = EmojiSearchWindows.of(appSet());
        assertNotNull(windows);
        // The copyright and registered signs, the trade mark, the double exclamation mark, the heart, the joiner,
        // the emoji presentation selector, the keycap mark and the first halves of the pictographs.
        for (final char c : "©®™‼❤‍️⃣\uD83C\uD83D\uD83E".toCharArray())
        {
            assertTrue(Integer.toHexString(c), windows.isEmojiChar(c));
        }
        // ASCII, the digit and signs of keycaps among it, Russian, Arabic and Chinese letters, the punctuation of
        // writing and a no-break space.
        for (final char c : "a7#*жم中é—«»№→ ".toCharArray())
        {
            assertFalse(Integer.toHexString(c), windows.isEmojiChar(c));
        }
    }

    @Test
    public void aShortTextIsSearchedWhole()
    {
        final EmojiSearchWindows windows = set(GRIN);
        assertNull(windows.windows(null));
        assertNull(windows.windows("abc " + GRIN));
        assertNull(windows.windows(code(EmojiSearchWindows.WHOLE_TEXT_UP_TO - GRIN.length()) + GRIN));
        assertNotNull(windows.windows(code(EmojiSearchWindows.WHOLE_TEXT_UP_TO) + GRIN));
    }

    @Test
    public void aWindowIsTheEmojiWithTheDigitOfAKeycapBeforeIt()
    {
        final EmojiSearchWindows windows = set(GRIN, KEYCAP_SEVEN);
        assertEquals(1, windows.before);
        assertEquals(0, windows.after);
        assertEquals(0, windows.between);
        // The code ends with digits: the one before the selector at 2001 and the mark at 2002 is in, no other.
        final List<int[]> found = windows.windows(code(2000) + KEYCAP_SEVEN + code(2000));
        assertNotNull(found);
        assertEquals(1, found.size());
        assertArrayEquals(new int[]{2000, 2003}, found.get(0));
    }

    @Test
    public void theRoomAroundARunIsTheMostTheEmojiOfTheSetHave()
    {
        // One char in ASCII before, two between and one after.
        final EmojiSearchWindows windows = set("a©", "©bc©", "™d");
        assertEquals(1, windows.before);
        assertEquals(2, windows.between);
        assertEquals(1, windows.after);
        final List<int[]> found = windows.windows(code(1500) + "©bc©" + code(1500) + "©abc©" + code(1500));
        assertNotNull(found);
        // Two chars between two signs keep them one run; three make two runs, with a window each.
        assertEquals(3, found.size());
        assertArrayEquals(new int[]{1499, 1505}, found.get(0));
        final int second = 1500 + 4 + 1500;
        assertArrayEquals(new int[]{second - 1, second + 2}, found.get(1));
        assertArrayEquals(new int[]{second + 3, second + 6}, found.get(2));
    }

    @Test
    public void windowsThatTouchAreOne()
    {
        final EmojiSearchWindows windows = set(GRIN, KEYCAP_SEVEN);
        // The grin's window ends where the keycap's starts, with the digit before the selector.
        final List<int[]> found = windows.windows(code(1500) + GRIN + KEYCAP_SEVEN + code(1500));
        assertNotNull(found);
        assertEquals(1, found.size());
        assertArrayEquals(new int[]{1499, 1505}, found.get(0));
    }

    @Test
    public void emojiSideBySideShareAWindowAndApartHaveTheirOwn()
    {
        final EmojiSearchWindows windows = set(GRIN);
        final String start = code(1500);
        final String text = start + GRIN + GRIN + code(20) + GRIN + code(1500) + GRIN + code(1500);
        final List<int[]> found = windows.windows(text);
        assertNotNull(found);
        final int first = start.length();
        final int second = first + 2 * GRIN.length() + 20;
        final int third = second + GRIN.length() + 1500;
        assertEquals(3, found.size());
        assertArrayEquals(new int[]{first, first + 4}, found.get(0));
        assertArrayEquals(new int[]{second, second + 2}, found.get(1));
        assertArrayEquals(new int[]{third, third + 2}, found.get(2));
    }

    @Test
    public void windowsAreInOrderAndNoneTouchesAnother()
    {
        final EmojiSearchWindows windows = EmojiSearchWindows.of(appSet());
        assertNotNull(windows);
        final List<String> set = appSet();
        final Random random = new Random(27112026L);
        final String code = code(300);
        final StringBuilder text = new StringBuilder();
        while (text.length() < 20_000)
        {
            final int kind = random.nextInt(4);
            if (kind == 0)
            {
                text.append(set.get(random.nextInt(set.size())));
            }
            else if (kind == 1)
            {
                text.append("0123456789#* ".charAt(random.nextInt(13)));
            }
            else
            {
                final int from = random.nextInt(code.length() - 40);
                text.append(code, from, from + 1 + random.nextInt(40));
            }
        }
        final List<int[]> found = windows.windows(text);
        assertNotNull(found);
        assertFalse(found.isEmpty());
        for (int k = 0; k < found.size(); k++)
        {
            assertTrue(found.get(k)[0] < found.get(k)[1]);
            if (k > 0)
            {
                assertTrue("window " + k, found.get(k)[0] > found.get(k - 1)[1]);
            }
        }
    }

    @Test
    public void emojiAtTheEdgesOfTheTextHaveWindowsThatEndThere()
    {
        final EmojiSearchWindows windows = set(GRIN, KEYCAP_SEVEN);
        final String text = GRIN + code(2000) + KEYCAP_SEVEN;
        final List<int[]> found = windows.windows(text);
        assertNotNull(found);
        assertEquals(2, found.size());
        assertEquals(0, found.get(0)[0]);
        assertEquals(text.length(), found.get(1)[1]);
        assertTrue(inside(found, text.length() - 3, text.length()));
    }

    @Test
    public void aTextThatIsMostlyEmojiIsSearchedWhole()
    {
        final EmojiSearchWindows windows = set(GRIN);
        final StringBuilder text = new StringBuilder();
        while (text.length() < 3000)
        {
            text.append(GRIN).append("a");
        }
        assertNull(windows.windows(text));
    }

    @Test
    public void theOtherCaseOfAnEmojiCharCounts()
    {
        // The library's pattern folds case: the circled capital M of the set is found as a small one too.
        final EmojiSearchWindows windows = set(CIRCLED_M);
        assertTrue(windows.isEmojiChar('ⓜ'));
        final List<int[]> found = windows.windows(code(1500) + "ⓜ" + code(1500));
        assertNotNull(found);
        assertEquals(1, found.size());
        // A small final sigma is not in a set with a small sigma, but its capital is: the search finds it.
        assertTrue(set("σ").isEmojiChar('ς'));
    }

    @Test
    public void aSetThatCouldMatchInAsciiHasNoWindows()
    {
        // Nothing outside ASCII.
        assertNull(EmojiSearchWindows.of(Arrays.asList(GRIN, "#")));
        // The Kelvin sign is a small k in the other case.
        assertNull(EmojiSearchWindows.of(Arrays.asList(GRIN, "K")));
        // The sharp s is two chars in capitals.
        assertNull(EmojiSearchWindows.of(Arrays.asList(GRIN, "ß")));
        // What stands between the windows searched together.
        assertNull(EmojiSearchWindows.of(Arrays.asList(GRIN, GRIN + EmojiSearchWindows.BETWEEN_WINDOWS + GRIN)));
        assertNull(EmojiSearchWindows.of(Collections.emptyList()));
        assertNull(EmojiSearchWindows.of(null));
    }
}

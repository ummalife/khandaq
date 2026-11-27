package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (inbox #26, 27.11): a conversation with a very long message still froze as it opened on 0.2.73, because
 * everything the list does to an entry's words costs as much as they are long. An entry now lays out at most
 * ENTRY_MAX_CHARS of its text, cut where a reader expects a cut, and the reader shows the rest in pieces.
 */
public class AirLongTextTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String FAMILY = "👨‍👩‍👧"; // a ZWJ sequence of 8 chars
    private static final String E_ACUTE = "é"; // e and a combining acute

    @Test
    public void aTextThatFitsIsShownWholeAndAsItIs()
    {
        final String text = paragraphs(29, 100);
        assertTrue(text.length() <= AirLongText.ENTRY_MAX_CHARS);
        assertFalse(AirLongText.isCut(text));
        assertSame(text, AirLongText.shown(text, null));
        assertSame(text, AirLongText.shown(text, "word"));
        assertNull(AirLongText.shown(null, null));
        assertEquals("", AirLongText.shown("", null));
        assertEquals(AirLongText.ENTRY_MAX_CHARS, repeat("a", AirLongText.ENTRY_MAX_CHARS).length());
        assertFalse(AirLongText.isCut(repeat("a", AirLongText.ENTRY_MAX_CHARS)));
        assertTrue(AirLongText.isCut(repeat("a", AirLongText.ENTRY_MAX_CHARS + 1)));
    }

    @Test
    public void aLongTextShowsItsStartCutAtTheLastLineBreakNearTheLimit()
    {
        final String text = paragraphs(450, 100); // 45,000 chars, as the note that froze
        assertTrue(AirLongText.isCut(text));
        final String shown = AirLongText.shown(text, null);
        assertTrue(shown.endsWith(AirLongText.ELLIPSIS));
        final String start = withoutEllipses(shown);
        assertTrue(start.length() <= AirLongText.ENTRY_MAX_CHARS);
        assertTrue(start.length() >= AirLongText.ENTRY_MIN_CHARS);
        assertTrue(text.startsWith(start));
        assertEquals('\n', text.charAt(start.length()));
        // The last whole paragraph that fits: the next line break is past the limit.
        assertTrue(text.indexOf('\n', start.length() + 1) > AirLongText.ENTRY_MAX_CHARS);
    }

    @Test
    public void withoutLineBreaksTheCutFallsAtTheLastSpace()
    {
        final String text = repeat("word ", 2000).trim();
        final String start = withoutEllipses(AirLongText.shown(text, null));
        assertTrue(text.startsWith(start));
        assertTrue(start.endsWith("word"));
        assertEquals(' ', text.charAt(start.length()));
        assertTrue(start.length() > AirLongText.ENTRY_MAX_CHARS - "word ".length());
    }

    @Test
    public void withoutWhiteSpaceTheCutKeepsEveryCharacterWhole()
    {
        final String families = repeat(FAMILY, 1000);
        final String familyStart = withoutEllipses(AirLongText.shown(families, null));
        assertTrue(familyStart.length() <= AirLongText.ENTRY_MAX_CHARS);
        assertEquals(0, familyStart.length() % FAMILY.length());
        assertTrue(families.startsWith(familyStart));

        final String accents = repeat(E_ACUTE, 2000);
        final String accentStart = withoutEllipses(AirLongText.shown(accents, null));
        assertEquals(0, accentStart.length() % E_ACUTE.length());
    }

    @Test
    public void aSearchWhoseMatchIsPastTheStartShowsThePartAroundIt()
    {
        final StringBuilder text = new StringBuilder(paragraphs(300, 100));
        text.insert(20_000, "the Needle we look for ");
        final String shown = AirLongText.shown(text.toString(), "needle");
        assertTrue(shown.startsWith(AirLongText.ELLIPSIS));
        assertTrue(shown.endsWith(AirLongText.ELLIPSIS));
        assertTrue(shown.contains("the Needle we look for"));
        final String part = withoutEllipses(shown);
        assertTrue(part.length() <= AirLongText.ENTRY_MAX_CHARS);
        assertTrue(text.toString().contains(part));
        // The part starts with the line that holds the match.
        assertEquals('\n', text.charAt(text.indexOf(part) - 1));
    }

    @Test
    public void aSearchWhoseMatchIsInTheStartShowsTheStart()
    {
        final String text = "the needle\n" + paragraphs(450, 100);
        assertEquals(AirLongText.shown(text, null), AirLongText.shown(text, "NEEDLE"));
        assertEquals(AirLongText.shown(text, null), AirLongText.shown(text, "absent"));
    }

    @Test
    public void aMatchAtTheEndShowsTheEndWithoutAnEllipsisAfterIt()
    {
        final String text = paragraphs(450, 100) + "\nlast words";
        final String shown = AirLongText.shown(text, "last words");
        assertTrue(shown.startsWith(AirLongText.ELLIPSIS));
        assertTrue(shown.endsWith("last words"));
    }

    @Test
    public void thePiecesKeepEveryWordInOrderAndAreShort()
    {
        final String text = paragraphs(450, 100) + "\n\n" + repeat("word ", 1000) + "\n" + repeat(FAMILY, 300);
        final List<String> pieces = AirLongText.pieces(text);
        assertTrue(pieces.size() > 1);
        final StringBuilder joined = new StringBuilder();
        for (final String piece : pieces)
        {
            assertFalse(piece.trim().isEmpty());
            assertTrue(piece.length() <= AirLongText.PIECE_MAX_CHARS + 1);
            // A piece cut at white space ends with a whole word; only the run of emoji has no white space.
            assertTrue(piece, piece.trim().endsWith("words") || piece.trim().endsWith("word")
                              || piece.endsWith(FAMILY));
            joined.append(piece);
        }
        assertEquals(nonSpace(text), nonSpace(joined.toString()));
    }

    @Test
    public void anEmptyLineBetweenTwoPiecesStays()
    {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++)
        {
            text.append(repeat("a", 500)).append(i).append("\n\n");
        }
        final List<String> pieces = AirLongText.pieces(text.toString());
        assertEquals(40, pieces.size());
        for (int i = 0; i < pieces.size() - 1; i++)
        {
            assertTrue(pieces.get(i).endsWith(i + "\n"));
            assertFalse(pieces.get(i).endsWith("\n\n"));
        }
        // One line break between two pieces is the start of the next piece's line: nothing to keep.
        final List<String> lines = AirLongText.pieces(repeat(repeat("b", 500) + "\n", 10));
        assertEquals(10, lines.size());
        for (final String piece : lines)
        {
            assertFalse(piece.endsWith("\n"));
        }
    }

    @Test
    public void theIndentOfALineAfterACutStays()
    {
        final String text = repeat("c", 700) + "\n    indented line " + repeat("d", 700);
        final List<String> pieces = AirLongText.pieces(text);
        assertTrue(pieces.get(1).startsWith("    indented line"));
    }

    @Test
    public void piecesOfNothingAreNone()
    {
        assertTrue(AirLongText.pieces(null).isEmpty());
        assertTrue(AirLongText.pieces("").isEmpty());
        assertTrue(AirLongText.pieces(repeat("\n", 5000)).isEmpty());
        assertEquals(1, AirLongText.pieces("short").size());
    }

    @Test
    public void everyCutMovesOn()
    {
        final String[] texts = {repeat(" ", 5000) + "x", repeat("\n \n", 3000), repeat(FAMILY + " ", 900),
                                repeat(E_ACUTE, 5000), repeat("\r\n", 4000) + "end"};
        for (final String text : texts)
        {
            int from = 0;
            while (from < text.length())
            {
                final int[] part = AirLongText.cut(text, from, AirLongText.PIECE_MAX_CHARS,
                                                   AirLongText.PIECE_MIN_CHARS);
                assertTrue(part[1] > from);
                assertTrue(part[0] <= part[1]);
                from = part[1];
            }
            assertTrue(AirLongText.shown(text, null).length() <= AirLongText.ENTRY_MAX_CHARS + 1);
        }
    }

    @Test
    public void everyEntryOfTheTranscriptSetsItsTextThroughTheCut() throws IOException
    {
        for (final String holder : new String[]{"MessageListHolder_text_incoming_not_read",
                                                "MessageListHolder_text_outgoing_not_read",
                                                "MessageListHolder_text_outgoing_read"})
        {
            final String source = read(JAVA + holder + ".java");
            assertFalse(holder, source.contains("setAutoLinkText(parsedMessage.bodyText)"));
            assertFalse(holder, source.contains("setAutoLinkTextHighlight(parsedMessage.bodyText"));
            assertTrue(holder, source.contains("setAutoLinkText(AirLongText.shown(parsedMessage.bodyText, null))"));
        }
        assertTrue(read(JAVA + "GroupMentionHelper.java").contains("final String body = AirLongText.shown("));
    }

    private static String read(final String path) throws IOException
    {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }

    /** count paragraphs of about length chars, each a line of words with its number. */
    private static String paragraphs(final int count, final int length)
    {
        final StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++)
        {
            if (i > 0)
            {
                text.append('\n');
            }
            final StringBuilder line = new StringBuilder("p" + i);
            while (line.length() < length - 6)
            {
                line.append(" words");
            }
            text.append(line);
        }
        return text.toString();
    }

    private static String repeat(final String s, final int times)
    {
        final StringBuilder out = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++)
        {
            out.append(s);
        }
        return out.toString();
    }

    private static String withoutEllipses(final String shown)
    {
        String part = shown;
        if (part.startsWith(AirLongText.ELLIPSIS))
        {
            part = part.substring(AirLongText.ELLIPSIS.length());
        }
        if (part.endsWith(AirLongText.ELLIPSIS))
        {
            part = part.substring(0, part.length() - AirLongText.ELLIPSIS.length());
        }
        return part;
    }

    private static String nonSpace(final String text)
    {
        return text.replaceAll("\\s+", "");
    }
}

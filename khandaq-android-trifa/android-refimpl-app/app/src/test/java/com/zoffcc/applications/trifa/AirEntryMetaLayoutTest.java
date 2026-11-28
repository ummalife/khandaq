package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.vanniktech.emoji.EmojiManager;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.khandaq.messenger.R;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (owner, 28.11.2026): the meta of an entry (the edited word, the time, our mark) at the trailing edge, on
 * the last line of the text when that line leaves room for it and the 8 before it, else on a line of its own under
 * the text. The text is laid out for real here (native graphics), and the rule reads the laid-out last line, so these
 * are the cases of the shared spec: a short line, two lines ending short, a last line that is full, a right-to-left
 * paragraph in a left-to-right screen and the mirror cases, the edited word in the meta, a body that is not a text.
 * Then the direction of a paragraph without a letter in the transcript's own text view (AirTextDirection): figures run
 * left to right in every screen, emoji and signs as the screen does.
 * The views are the framework's at 1dp to 1px; the app's own resources are not loaded in the unit tests.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AirEntryMetaLayoutTest
{
    private static final int WIDTH = 360;
    private static final int BODY_PX = 16;
    private static final int CAPTION_PX = 12;
    private static final int MARK_PX = 18;
    private static final int GAP_TO_TEXT = 8;
    private static final int GAP_IN_META = 4;

    private Context context;

    /** The views of one entry, laid out. */
    private static final class Entry
    {
        AirEntryMetaLayout frame;
        View body;
        TextView edited;
        TextView time;
        View mark;
    }

    @BeforeClass
    public static void installTheEmojiSet()
    {
        // The transcript's text view is an emoji text view, which wants a set installed.
        EmojiManager.install(new GoogleEmojiProvider());
    }

    @Before
    public void setUp()
    {
        context = ApplicationProvider.getApplicationContext();
        // The app's manifest says android:supportsRtl; without the flag a view never resolves right to left.
        context.getApplicationInfo().flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
    }

    private TextView words(final String text, final int px)
    {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, 0);
        view.setText(text);
        return view;
    }

    private TextView text(final String text)
    {
        final TextView view = words(text, BODY_PX);
        // As air_entry_text: each paragraph aligned by its own direction.
        view.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        return view;
    }

    /** As m_text: the transcript's own text view, with its direction rule, at the size of text(). */
    private TextView entryText(final String text)
    {
        final AirEntryTextView view = new AirEntryTextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, BODY_PX);
        view.setIncludeFontPadding(false);
        view.setPadding(0, 0, 0, 0);
        view.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        view.setText(text);
        return view;
    }

    /** Where the character after the start of {@code part} stands on its line, from the left. */
    private static float x(final TextView text, final String part)
    {
        return text.getLayout().getPrimaryHorizontal(text.getText().toString().indexOf(part) + 1);
    }

    /** An entry whose body is {@code body}, with the edited word and our mark when asked, laid out WIDTH wide. */
    private Entry entry(final View body, final boolean rtlScreen, final boolean edited, final boolean mark)
    {
        final Entry e = new Entry();
        e.frame = new AirEntryMetaLayout(context);
        e.frame.setLayoutDirection(rtlScreen ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        e.body = body;
        e.frame.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.WRAP_CONTENT));
        e.edited = words("edited", CAPTION_PX);
        e.edited.setId(R.id.air_entry_edited);
        e.edited.setVisibility(edited ? View.VISIBLE : View.GONE);
        e.frame.addView(e.edited, wrap());
        e.time = words("12:34", CAPTION_PX);
        e.time.setId(R.id.date_time);
        e.frame.addView(e.time, wrap());
        e.mark = new View(context);
        e.mark.setId(R.id.m_icon);
        e.mark.setVisibility(mark ? View.VISIBLE : View.GONE);
        e.frame.addView(e.mark, new LinearLayout.LayoutParams(MARK_PX, MARK_PX));
        layOut(e);
        return e;
    }

    private static LinearLayout.LayoutParams wrap()
    {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void layOut(final Entry e)
    {
        e.frame.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        e.frame.layout(0, 0, WIDTH, e.frame.getMeasuredHeight());
    }

    private static int lastBaseline(final TextView text)
    {
        final Layout layout = text.getLayout();
        return text.getTop() + text.getTotalPaddingTop() + layout.getLineBaseline(layout.getLineCount() - 1);
    }

    private static int timeBaseline(final Entry e)
    {
        return e.time.getTop() + e.time.getBaseline();
    }

    /** The meta's width: the visible pieces 4 apart. */
    private static int metaWidth(final Entry e)
    {
        int width = 0;
        int pieces = 0;
        for (final View piece : new View[]{e.edited, e.time, e.mark})
        {
            if (piece.getVisibility() != View.GONE)
            {
                width += piece.getWidth();
                pieces++;
            }
        }
        return width + Math.max(0, pieces - 1) * GAP_IN_META;
    }

    /** A line of "x" as wide as the body leaves when the meta must not fit beside it. */
    private String fullLine()
    {
        final TextView probe = text("");
        final StringBuilder line = new StringBuilder("x");
        // The longest run of x that still makes one line WIDTH wide: no room left for the meta beside it.
        while (probe.getPaint().measureText(line + "x") <= WIDTH - 2)
        {
            line.append('x');
        }
        return line.toString();
    }

    // ---- the cases of the spec ----

    @Test
    public void a_short_line_has_the_meta_on_it_at_the_trailing_edge()
    {
        final TextView text = text("Salam");
        final Entry e = entry(text, false, false, true);
        assertTrue(e.frame.isMetaOnLastLine());
        assertEquals(1, text.getLayout().getLineCount());
        // At the right edge, the mark outermost, the time 4 before it, on the baseline of the line.
        assertEquals(WIDTH, e.mark.getRight());
        assertEquals(e.mark.getLeft() - GAP_IN_META, e.time.getRight());
        assertEquals(lastBaseline(text), timeBaseline(e));
        // The mark is centred on the line of the time.
        assertEquals(e.time.getTop() + (e.time.getHeight() - MARK_PX) / 2, e.mark.getTop());
        // No line of its own: the entry is no taller than its text (and the meta in it).
        assertTrue(e.frame.getHeight() <= Math.max(text.getHeight(), e.mark.getBottom()));
        // The text keeps the whole width: no column for the meta.
        assertEquals(WIDTH, text.getWidth());
    }

    @Test
    public void two_lines_ending_short_have_the_meta_on_the_second()
    {
        final TextView text = text(fullLine() + " short");
        final Entry e = entry(text, false, false, false);
        assertEquals(2, text.getLayout().getLineCount());
        assertTrue(e.frame.isMetaOnLastLine());
        assertEquals(lastBaseline(text), timeBaseline(e));
        assertTrue(timeBaseline(e) > text.getTop() + text.getLayout().getLineBottom(0));
        // Without a mark (theirs) the time itself ends at the edge.
        assertEquals(WIDTH, e.time.getRight());
    }

    @Test
    public void a_full_last_line_puts_the_meta_on_a_line_of_its_own_under_the_text()
    {
        final TextView text = text(fullLine());
        final Entry e = entry(text, false, false, true);
        assertEquals(1, text.getLayout().getLineCount());
        assertFalse(e.frame.isMetaOnLastLine());
        assertTrue(e.frame.isMetaOnOwnLine());
        final int metaTop = Math.min(e.time.getTop(), e.mark.getTop());
        final int metaBottom = Math.max(e.time.getBottom(), e.mark.getBottom());
        // Right under the text, at the trailing edge, the line as tall as the meta.
        assertEquals(text.getBottom(), metaTop);
        assertEquals(WIDTH, e.mark.getRight());
        assertEquals(text.getHeight() + (metaBottom - metaTop), e.frame.getHeight());
    }

    @Test
    public void the_rule_reads_the_room_left_on_the_line_not_the_characters()
    {
        // The same count of characters, narrow and wide: only the room on the line decides.
        final String narrow = "iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii";
        final String wide = "WWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWWW";
        final TextView probe = text("");
        assertTrue(probe.getPaint().measureText(narrow) < WIDTH / 2f);
        final Entry thin = entry(text(narrow), false, false, true);
        assertTrue(thin.frame.isMetaOnLastLine());
        final TextView wideText = text(wide);
        final Entry thick = entry(wideText, false, false, true);
        final Layout layout = wideText.getLayout();
        final float room = WIDTH - layout.getLineRight(layout.getLineCount() - 1);
        assertEquals(room >= GAP_TO_TEXT + metaWidth(thick), thick.frame.isMetaOnLastLine());
    }

    @Test
    public void the_gap_before_the_meta_is_8()
    {
        final TextView text = text("Salam");
        final Entry e = entry(text, false, false, true);
        assertTrue(e.frame.isMetaOnLastLine());
        // A meta that leaves exactly the gap on the line fits; one pixel wider does not.
        final float lineRight = text.getLayout().getLineRight(0);
        final int exact = (int) Math.floor(WIDTH - lineRight - GAP_TO_TEXT);
        assertTrue(AirEntryMetaLayout.fitsOnLastLine(text.getLayout(), 0, WIDTH, false, exact, GAP_TO_TEXT));
        assertFalse(AirEntryMetaLayout.fitsOnLastLine(text.getLayout(), 0, WIDTH, false, exact + 1, GAP_TO_TEXT));
        // The same in a right-to-left screen, on the left of a right-to-left line.
        final TextView arabic = text("السلام");
        entry(arabic, true, false, true);
        final float lineLeft = arabic.getLayout().getLineLeft(0);
        final int mirrored = (int) Math.floor(lineLeft - GAP_TO_TEXT);
        assertTrue(AirEntryMetaLayout.fitsOnLastLine(arabic.getLayout(), 0, WIDTH, true, mirrored, GAP_TO_TEXT));
        assertFalse(AirEntryMetaLayout.fitsOnLastLine(arabic.getLayout(), 0, WIDTH, true, mirrored + 1, GAP_TO_TEXT));
        // No text laid out: never on its line.
        assertFalse(AirEntryMetaLayout.fitsOnLastLine(null, 0, WIDTH, false, 1, GAP_TO_TEXT));
    }

    @Test
    public void a_right_to_left_paragraph_in_a_left_to_right_screen_takes_a_line_for_its_meta()
    {
        final TextView text = text("السلام عليكم");
        final Entry e = entry(text, false, false, true);
        final Layout layout = text.getLayout();
        assertEquals(Layout.DIR_RIGHT_TO_LEFT, layout.getParagraphDirection(0));
        // It is aligned by its own direction, to the right edge, so the right side of its line has no room.
        assertEquals(WIDTH, Math.round(layout.getLineRight(0)));
        assertFalse(e.frame.isMetaOnLastLine());
        assertTrue(e.frame.isMetaOnOwnLine());
        assertEquals(WIDTH, e.mark.getRight());
        assertEquals(text.getBottom(), Math.min(e.time.getTop(), e.mark.getTop()));
    }

    @Test
    public void in_a_right_to_left_screen_the_meta_stands_at_the_left_mirrored()
    {
        final TextView text = text("السلام عليكم");
        final Entry e = entry(text, true, true, true);
        assertTrue(e.frame.isMetaOnLastLine());
        // The trailing edge is the left one: the mark outermost, then the time, then edited, 4 apart.
        assertEquals(0, e.mark.getLeft());
        assertEquals(e.mark.getRight() + GAP_IN_META, e.time.getLeft());
        assertEquals(e.time.getRight() + GAP_IN_META, e.edited.getLeft());
        assertEquals(lastBaseline(text), timeBaseline(e));
        // And the text ends to the right of the meta, 8 or more after it.
        assertTrue(text.getLayout().getLineLeft(0) >= e.edited.getRight() + GAP_TO_TEXT);
    }

    @Test
    public void a_left_to_right_paragraph_in_a_right_to_left_screen_takes_a_line_for_its_meta()
    {
        final TextView text = text("Salam");
        final Entry e = entry(text, true, false, true);
        final Layout layout = text.getLayout();
        assertEquals(Layout.DIR_LEFT_TO_RIGHT, layout.getParagraphDirection(0));
        assertEquals(0, Math.round(layout.getLineLeft(0)));
        assertFalse(e.frame.isMetaOnLastLine());
        assertEquals(0, e.mark.getLeft());
        assertEquals(text.getBottom(), Math.min(e.time.getTop(), e.mark.getTop()));
    }

    @Test
    public void the_edited_word_leads_the_meta_before_the_time()
    {
        final TextView text = text("Salam");
        final Entry e = entry(text, false, true, true);
        assertTrue(e.frame.isMetaOnLastLine());
        assertEquals(WIDTH, e.mark.getRight());
        assertEquals(e.mark.getLeft() - GAP_IN_META, e.time.getRight());
        assertEquals(e.time.getLeft() - GAP_IN_META, e.edited.getRight());
        // On one baseline with the time and the text.
        assertEquals(e.edited.getTop() + e.edited.getBaseline(), timeBaseline(e));
        assertEquals(lastBaseline(text), timeBaseline(e));
    }

    @Test
    public void the_meta_of_a_failed_or_edited_entry_that_does_not_fit_goes_under_it_whole()
    {
        final TextView text = text(fullLine());
        final Entry e = entry(text, false, true, true);
        assertTrue(e.frame.isMetaOnOwnLine());
        assertTrue(e.edited.getTop() >= text.getBottom() - 1);
        assertEquals(e.time.getLeft() - GAP_IN_META, e.edited.getRight());
    }

    @Test
    public void a_body_that_is_not_a_text_is_narrowed_by_the_meta_which_stands_level_with_its_bottom()
    {
        final View file = new View(context);
        file.setMinimumHeight(48);
        final Entry e = entry(file, false, false, true);
        final int meta = metaWidth(e);
        assertFalse(e.frame.isMetaOnLastLine());
        assertFalse(e.frame.isMetaOnOwnLine());
        // body trailing <= meta leading - 8, and the meta at the edge, its bottom the body's.
        assertEquals(WIDTH - meta - GAP_TO_TEXT, file.getWidth());
        assertTrue(file.getRight() <= e.time.getLeft() - GAP_TO_TEXT);
        assertEquals(WIDTH, e.mark.getRight());
        assertEquals(file.getBottom(), Math.max(e.time.getBottom(), e.mark.getBottom()));
        assertEquals(48, e.frame.getHeight());

        // Mirrored in a right-to-left screen: the body from the right, the meta at the left edge.
        final View other = new View(context);
        other.setMinimumHeight(48);
        final Entry rtl = entry(other, true, false, true);
        assertEquals(WIDTH, other.getRight());
        assertEquals(0, rtl.mark.getLeft());
        assertTrue(other.getLeft() >= rtl.time.getRight() + GAP_TO_TEXT);
    }

    @Test
    public void a_call_row_is_a_block_the_meta_stands_beside()
    {
        final TextView call = text("Outgoing call");
        final Entry e = entry(call, false, false, true);
        assertTrue(e.frame.isMetaOnLastLine());
        e.frame.setTextBody(false);
        layOut(e);
        assertFalse(e.frame.isMetaOnLastLine());
        assertFalse(e.frame.isMetaOnOwnLine());
        assertEquals(WIDTH - metaWidth(e) - GAP_TO_TEXT, call.getWidth());
        assertEquals(call.getBottom(), Math.max(e.time.getBottom(), e.mark.getBottom()));
        // A recycled row back to a text reads its last line again.
        e.frame.setTextBody(true);
        layOut(e);
        assertTrue(e.frame.isMetaOnLastLine());
        assertEquals(WIDTH, call.getWidth());
    }

    @Test
    public void a_photo_with_a_caption_has_the_meta_on_the_caption_and_keeps_the_whole_width()
    {
        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        final View photo = new View(context);
        body.addView(photo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
        final TextView caption = text("A caption");
        body.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                             ViewGroup.LayoutParams.WRAP_CONTENT));
        final Entry e = entry(body, false, false, true);
        // Without the caption known to the frame, the body is narrowed as a file is.
        assertEquals(WIDTH - metaWidth(e) - GAP_TO_TEXT, body.getWidth());

        e.frame.setTailText(caption);
        layOut(e);
        assertEquals(WIDTH, body.getWidth());
        assertTrue(e.frame.isMetaOnLastLine());
        assertEquals(body.getTop() + caption.getTop() + caption.getTotalPaddingTop()
                     + caption.getLayout().getLineBaseline(0), timeBaseline(e));

        // A caption that is gone leaves the photo a body that is not a text.
        caption.setVisibility(View.GONE);
        layOut(e);
        assertFalse(e.frame.isMetaOnLastLine());
        assertEquals(WIDTH - metaWidth(e) - GAP_TO_TEXT, body.getWidth());
        assertEquals(photo.getBottom() + body.getTop(), Math.max(e.time.getBottom(), e.mark.getBottom()));
    }

    @Test
    public void a_mark_that_is_gone_takes_no_room()
    {
        final TextView text = text("Salam");
        final Entry e = entry(text, false, false, false);
        assertEquals(WIDTH, e.time.getRight());
        assertEquals(0, e.mark.getWidth());
    }

    // ---- a paragraph without a letter (AirTextDirection) ----

    @Test
    public void figures_without_a_letter_run_left_to_right_in_a_right_to_left_screen_and_the_meta_takes_a_line()
    {
        final String place = HelperLocationMessage.coordinatesText(39.92077, 32.85411);
        assertEquals("39.92077, 32.85411", place);
        for (final String figures : new String[]{place, "+7 999 123 45 67", "15:30", "\u0661\u0665:\u0663\u0660"})
        {
            final TextView text = entryText(figures);
            final Entry e = entry(text, true, false, true);
            final Layout layout = text.getLayout();
            assertEquals(figures, Layout.DIR_LEFT_TO_RIGHT, layout.getParagraphDirection(0));
            // It starts at the left, as English does: the trailing side of this screen has no room for the meta.
            assertEquals(figures, 0, Math.round(layout.getLineLeft(0)));
            assertFalse(figures, e.frame.isMetaOnLastLine());
            assertTrue(figures, e.frame.isMetaOnOwnLine());
            assertEquals(figures, 0, e.mark.getLeft());
            assertEquals(figures, text.getBottom(), Math.min(e.time.getTop(), e.mark.getTop()));
        }
        // The groups keep their order: the latitude before the longitude, a phone number from its country code on.
        final TextView coordinates = entryText(place);
        entry(coordinates, true, false, false);
        assertTrue(x(coordinates, "39") < x(coordinates, "32"));
        final TextView phone = entryText("+7 999 123 45 67");
        entry(phone, true, false, false);
        assertTrue(x(phone, "999") < x(phone, "67"));
        // The platform's first-strong default, which this replaces, gave them the screen's direction: groups reversed.
        final TextView platform = text(place);
        entry(platform, true, false, false);
        assertEquals(Layout.DIR_RIGHT_TO_LEFT, platform.getLayout().getParagraphDirection(0));
        assertTrue(x(platform, "39") > x(platform, "32"));
    }

    @Test
    public void in_a_left_to_right_screen_figures_keep_the_meta_on_their_line()
    {
        final TextView text = entryText("15:30");
        final Entry e = entry(text, false, false, true);
        assertEquals(Layout.DIR_LEFT_TO_RIGHT, text.getLayout().getParagraphDirection(0));
        assertTrue(e.frame.isMetaOnLastLine());
        assertEquals(lastBaseline(text), timeBaseline(e));
    }

    @Test
    public void signs_without_a_letter_or_a_figure_run_as_the_screen_does()
    {
        final TextView rtl = entryText("?!");
        final Entry inRtl = entry(rtl, true, false, true);
        assertEquals(Layout.DIR_RIGHT_TO_LEFT, rtl.getLayout().getParagraphDirection(0));
        assertTrue(inRtl.frame.isMetaOnLastLine());
        final TextView ltr = entryText("?!");
        final Entry inLtr = entry(ltr, false, false, true);
        assertEquals(Layout.DIR_LEFT_TO_RIGHT, ltr.getLayout().getParagraphDirection(0));
        assertTrue(inLtr.frame.isMetaOnLastLine());
    }

    @Test
    public void a_letter_decides_before_a_figure()
    {
        // "The hour 15:30" in Arabic, and the figure first: right to left by the letters, in a left-to-right screen.
        for (final String words : new String[]{"\u0627\u0644\u0633\u0627\u0639\u0629 15:30",
                                               "15:30 \u0627\u0644\u0633\u0627\u0639\u0629"})
        {
            final TextView text = entryText(words);
            entry(text, false, false, true);
            assertEquals(words, Layout.DIR_RIGHT_TO_LEFT, text.getLayout().getParagraphDirection(0));
        }
        final TextView latin = entryText("At 15:30");
        entry(latin, true, false, true);
        assertEquals(Layout.DIR_LEFT_TO_RIGHT, latin.getLayout().getParagraphDirection(0));
    }

    @Test
    public void each_paragraph_takes_its_own_direction()
    {
        // "Call me" in Arabic over a phone number, in a right-to-left screen.
        final TextView text = entryText("\u0627\u062A\u0635\u0644 \u0628\u064A\n+7 999 123 45 67");
        final Entry e = entry(text, true, false, true);
        final Layout layout = text.getLayout();
        assertEquals(2, layout.getLineCount());
        assertEquals(Layout.DIR_RIGHT_TO_LEFT, layout.getParagraphDirection(0));
        assertEquals(Layout.DIR_LEFT_TO_RIGHT, layout.getParagraphDirection(1));
        // The meta follows the last paragraph, which starts at the left: a line of its own.
        assertTrue(e.frame.isMetaOnOwnLine());
    }

    @Test
    public void the_rule_by_itself()
    {
        final AirTextDirection rtl = AirTextDirection.forInterface(true);
        final AirTextDirection ltr = AirTextDirection.forInterface(false);
        // Figures of any script: European, Arabic-Indic, Extended Arabic-Indic; a keycap holds one too.
        for (final String figures : new String[]{"15:30", "\u0661\u0665", "\u06F1\u06F5", "1\uFE0F\u20E3", "#1"})
        {
            assertFalse(figures, rtl.isRtl(figures, 0, figures.length()));
            assertFalse(figures, ltr.isRtl(figures, 0, figures.length()));
            assertFalse(figures, rtl.isRtl(figures.toCharArray(), 0, figures.length()));
        }
        // Emoji and signs: the screen's direction.
        for (final String signs : new String[]{"\uD83D\uDC4D\uD83D\uDE42", "?!", " "})
        {
            assertTrue(signs, rtl.isRtl(signs, 0, signs.length()));
            assertFalse(signs, ltr.isRtl(signs, 0, signs.length()));
        }
        // Letters: the first one with a direction, in either screen.
        assertTrue(ltr.isRtl("\u0645\u0631\u062D\u0628\u0627 15", 0, 8));
        assertFalse(rtl.isRtl("Salam 15", 0, 8));
        // Only the range asked about: the paragraph of figures after an Arabic one.
        final String two = "\u0645\u0631\u062D\u0628\u0627\n15:30";
        assertTrue(rtl.isRtl(two, 0, 5));
        assertFalse(rtl.isRtl(two, 6, 5));
    }
}

package com.zoffcc.applications.trifa;

import android.content.Context;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.util.AttributeSet;

import com.luseen.autolinklibrary.EmojiTextViewLinks;

/**
 * KHANDAQ (owner, 28.11.2026): the text of a transcript entry (air_entry_text) and the caption of a file entry, each
 * paragraph in the direction AirTextDirection gives it in place of the platform's first-strong default. The alignment
 * (textStart) and AirEntryMetaLayout read the laid-out lines, so they follow: a paragraph of figures in a
 * right-to-left screen starts at the left and its meta takes a line of its own, as an English one does.
 */
public class AirEntryTextView extends EmojiTextViewLinks
{
    public AirEntryTextView(final Context context, final AttributeSet attrs)
    {
        super(context, attrs);
    }

    public AirEntryTextView(final Context context)
    {
        super(context);
    }

    /**
     * A direction set on purpose stays the platform's; only the default first-strong one is replaced. The method is
     * public from API 29 and protected (hidden) before, where this overrides it all the same; the framework's version
     * is not called through super, as API 28 may refuse a hidden method to the app.
     */
    @Override
    public TextDirectionHeuristic getTextDirectionHeuristic()
    {
        switch (getTextDirection())
        {
            case TEXT_DIRECTION_ANY_RTL:
                return TextDirectionHeuristics.ANYRTL_LTR;
            case TEXT_DIRECTION_LTR:
                return TextDirectionHeuristics.LTR;
            case TEXT_DIRECTION_RTL:
                return TextDirectionHeuristics.RTL;
            case TEXT_DIRECTION_LOCALE:
                return TextDirectionHeuristics.LOCALE;
            case TEXT_DIRECTION_FIRST_STRONG_LTR:
                return TextDirectionHeuristics.FIRSTSTRONG_LTR;
            case TEXT_DIRECTION_FIRST_STRONG_RTL:
                return TextDirectionHeuristics.FIRSTSTRONG_RTL;
            default:
                return AirTextDirection.forInterface(getLayoutDirection() == LAYOUT_DIRECTION_RTL);
        }
    }
}

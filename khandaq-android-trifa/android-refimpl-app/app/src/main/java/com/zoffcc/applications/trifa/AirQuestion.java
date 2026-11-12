package com.zoffcc.applications.trifa;

import android.content.Context;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * KHANDAQ (redesign, E21): a dangerous action asks from below, in reach of the thumb: the question, its
 * consequence in one sentence, the verb on the button and Cancel apart (design/DESIGN.md, "Errors and
 * questions on the phone"). Nothing happens until the verb is tapped; Cancel, Back, a tap outside or a
 * swipe down leave everything as it was. The sheet itself is {@link AirSheet}, which the forms use too.
 */
final class AirQuestion
{
    private AirQuestion()
    {
    }

    /**
     * Shows the question over {@code context}'s activity. {@code danger} draws the verb in the danger
     * colour, for what cannot be taken back; otherwise it is the ink button. Returns the sheet, or null
     * when the activity is gone and nothing was shown.
     */
    @Nullable
    static BottomSheetDialog ask(final Context context, final CharSequence question,
                                 @Nullable final CharSequence consequence, final CharSequence verb,
                                 final boolean danger, final Runnable onVerb)
    {
        final AirSheet sheet = AirSheet.create(context, question, consequence);
        if (sheet == null)
        {
            return null;
        }
        // The sheet goes first, then the action runs, so whatever it opens comes in over the screen.
        sheet.verbThen(verb, danger, onVerb);
        sheet.cancel(null);
        return sheet.show() ? sheet.dialog : null;
    }
}

package com.zoffcc.applications.trifa;

import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;

import java.nio.CharBuffer;

/**
 * KHANDAQ (owner, 28.11.2026): the direction one paragraph of a transcript entry runs in, the rule iOS and the
 * desktop share (iOS: AirTextDirection.paragraphRunsRightToLeft). The first letter with a direction decides, as the
 * platform's first-strong rule has it. A paragraph without such a letter that holds a figure, a decimal digit of any
 * script, runs left to right in every interface, as figures are read in every language: first-strong gave it the
 * interface's direction, so in an Arabic interface the coordinates of a place read "32.85411 ,39.92077" and a phone
 * number had its groups in reverse order. A paragraph of neither (emoji, signs) runs as the interface does.
 *
 * <p>The text layout asks for each paragraph on its own, so an Arabic line over a phone number keeps each in its own
 * direction. In a left-to-right interface the rule is the platform's FIRSTSTRONG_LTR exactly.
 */
final class AirTextDirection implements TextDirectionHeuristic
{
    static final AirTextDirection LTR_INTERFACE = new AirTextDirection(false);
    static final AirTextDirection RTL_INTERFACE = new AirTextDirection(true);

    private final boolean rtlInterface;

    private AirTextDirection(final boolean rtlInterface)
    {
        this.rtlInterface = rtlInterface;
    }

    static AirTextDirection forInterface(final boolean rtlInterface)
    {
        return rtlInterface ? RTL_INTERFACE : LTR_INTERFACE;
    }

    @Override
    public boolean isRtl(final char[] array, final int start, final int count)
    {
        return isRtl(CharBuffer.wrap(array), start, count);
    }

    @Override
    public boolean isRtl(final CharSequence cs, final int start, final int count)
    {
        if (!rtlInterface)
        {
            // Without a letter, figures and the interface both say left to right.
            return TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(cs, start, count);
        }
        if (!TextDirectionHeuristics.FIRSTSTRONG_RTL.isRtl(cs, start, count))
        {
            return false;
        }
        // Right to left by a letter (the left-to-right default agrees then), or for want of one.
        return TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(cs, start, count) || !holdsFigure(cs, start, count);
    }

    /** Whether the range holds a decimal digit of any script (Unicode Nd: 0-9, the Arabic-Indic ones, ...). */
    static boolean holdsFigure(final CharSequence cs, final int start, final int count)
    {
        final int end = start + count;
        int i = start;
        while (i < end)
        {
            final int codePoint = Character.codePointAt(cs, i);
            if (Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER)
            {
                return true;
            }
            i += Character.charCount(codePoint);
        }
        return false;
    }
}

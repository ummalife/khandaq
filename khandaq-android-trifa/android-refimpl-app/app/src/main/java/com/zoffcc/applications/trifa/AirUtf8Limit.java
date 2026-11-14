package com.zoffcc.applications.trifa;

import android.text.InputFilter;
import android.text.Spanned;

import androidx.annotation.Nullable;

/**
 * KHANDAQ (redesign, E17): keeps a one-line field within what Tox carries, counted in UTF-8 bytes as the
 * native layer sends them (String.getBytes in UTF-8), as AirYouField on iOS. Typing past the end stops
 * at the end, a paste is cut after the last whole character that fits, and a line break becomes a
 * space, since a name and a status are one line.
 */
final class AirUtf8Limit implements InputFilter
{
    private final int maxBytes;

    AirUtf8Limit(final int maxBytes)
    {
        this.maxBytes = maxBytes;
    }

    @Override
    public CharSequence filter(final CharSequence source, final int start, final int end, final Spanned dest,
                               final int dstart, final int dend)
    {
        return fit(source, start, end, dest, dstart, dend, maxBytes);
    }

    /**
     * What goes in place of dest[dstart, dend) instead of source[start, end): null when the source fits
     * as it is, else the part of it that fits, on one line.
     */
    @Nullable
    static CharSequence fit(final CharSequence source, final int start, final int end, final CharSequence dest,
                            final int dstart, final int dend, final int maxBytes)
    {
        CharSequence piece = source.subSequence(start, end);
        final boolean breaks = hasLineBreak(piece);
        if (breaks)
        {
            piece = piece.toString().replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        }
        final int room = maxBytes - utf8Length(dest, 0, dstart) - utf8Length(dest, dend, dest.length());
        final int fits = fittingChars(piece, room);
        if (fits == piece.length())
        {
            return breaks ? piece : null;
        }
        return piece.subSequence(0, fits);
    }

    /** The UTF-8 bytes of text[from, to); a lone surrogate is sent as "?", one byte. */
    static int utf8Length(final CharSequence text, final int from, final int to)
    {
        int bytes = 0;
        int i = from;
        while (i < to)
        {
            final int step = charsAt(text, i, to);
            bytes += step == 2 ? 4 : bytesOf(text.charAt(i));
            i += step;
        }
        return bytes;
    }

    /** How many chars from the start of text fit into room bytes, never half of a surrogate pair. */
    static int fittingChars(final CharSequence text, final int room)
    {
        return fittingChars(text, 0, room);
    }

    /** How many chars of text from index from on fit into room bytes, never half of a surrogate pair. */
    static int fittingChars(final CharSequence text, final int from, final int room)
    {
        int used = 0;
        int i = from;
        while (i < text.length())
        {
            final int step = charsAt(text, i, text.length());
            final int bytes = step == 2 ? 4 : bytesOf(text.charAt(i));
            if (used + bytes > room)
            {
                break;
            }
            used += bytes;
            i += step;
        }
        return i - from;
    }

    private static int charsAt(final CharSequence text, final int i, final int to)
    {
        return Character.isHighSurrogate(text.charAt(i)) && i + 1 < to
               && Character.isLowSurrogate(text.charAt(i + 1)) ? 2 : 1;
    }

    private static int bytesOf(final char c)
    {
        if (c < 0x80 || Character.isSurrogate(c))
        {
            return 1;
        }
        return c < 0x800 ? 2 : 3;
    }

    private static boolean hasLineBreak(final CharSequence text)
    {
        for (int i = 0; i < text.length(); i++)
        {
            final char c = text.charAt(i);
            if (c == '\n' || c == '\r')
            {
                return true;
            }
        }
        return false;
    }
}

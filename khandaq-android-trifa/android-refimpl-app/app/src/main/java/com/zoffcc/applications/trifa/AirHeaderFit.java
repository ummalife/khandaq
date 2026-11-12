package com.zoffcc.applications.trifa;

import android.text.Editable;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.widget.TextView;

import java.util.function.BooleanSupplier;

/**
 * KHANDAQ (redesign, stage 3b): a conversation header gives the name its room before its icons. When the
 * name would show fewer than {@link #NAME_MIN_CHARS} characters (large text, a narrow screen), the slots
 * the header can do without go one by one, in the order given: Search moves into the overflow menu, then
 * the chevron goes, since tapping the name opens the dossier anyway. A name that fits whole keeps every
 * icon. Used by the conversation with one person and by the group conversation.
 */
final class AirHeaderFit
{
    /** Something in the header row that can step aside for the name. */
    interface Slot
    {
        /** Its width in the row while it is shown; 0 when it is not part of this header at all. */
        int width();

        void setOut(boolean out);
    }

    static final int NAME_MIN_CHARS = 10;
    private static final String ELLIPSIS = String.valueOf((char) 0x2026);

    private final TextView name;
    private final BooleanSupplier paused;
    private final Slot[] slots;
    private final boolean[] out;
    private final Runnable fit = this::fit;

    /**
     * Watches {@code name} and fits the header whenever its width or its text changes. {@code paused} says
     * when the header is not the name's (search open, the activity finishing); call {@link #post()} when
     * that ends.
     */
    AirHeaderFit(final TextView name, final BooleanSupplier paused, final Slot... slots)
    {
        this.name = name;
        this.paused = paused;
        this.slots = slots;
        this.out = new boolean[slots.length];
        name.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((right - left) != (oldRight - oldLeft))
            {
                post();
            }
        });
        name.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after)
            {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count)
            {
            }

            @Override
            public void afterTextChanged(Editable s)
            {
                post();
            }
        });
    }

    /** True while slot {@code index} has stepped aside. */
    boolean isOut(final int index)
    {
        return (index >= 0) && (index < out.length) && out[index];
    }

    /** Fits the header after the current layout pass: the visibility changes ask for a new one. */
    void post()
    {
        name.removeCallbacks(fit);
        name.post(fit);
    }

    private void fit()
    {
        if (paused.getAsBoolean())
        {
            return;
        }
        final int nameWidth = name.getWidth();
        if (nameWidth <= 0)
        {
            return;
        }
        // The name's room with every slot in place, whatever stepped aside before: the decision does not
        // depend on its own result, so it settles after one pass.
        final int[] widths = new int[slots.length];
        int room = nameWidth;
        for (int i = 0; i < slots.length; i++)
        {
            widths[i] = Math.max(0, slots[i].width());
            if (out[i])
            {
                room -= widths[i];
            }
        }
        for (int i = 0; i < slots.length; i++)
        {
            final boolean goes = (widths[i] > 0) && !showsEnough(room);
            if (goes)
            {
                room += widths[i];
            }
            if (goes != out[i])
            {
                out[i] = goes;
                slots[i].setOut(goes);
            }
        }
    }

    /** True when the whole name fits in {@code width}, or at least NAME_MIN_CHARS characters and the ellipsis. */
    private boolean showsEnough(final int width)
    {
        final CharSequence text = name.getText();
        if ((text == null) || (text.length() == 0))
        {
            return true;
        }
        final TextPaint paint = name.getPaint();
        if (paint.measureText(text, 0, text.length()) <= width)
        {
            return true;
        }
        final int count = Character.codePointCount(text, 0, text.length());
        final int end = Character.offsetByCodePoints(text, 0, Math.min(NAME_MIN_CHARS, count));
        return paint.measureText(text, 0, end) + paint.measureText(ELLIPSIS) <= width;
    }
}

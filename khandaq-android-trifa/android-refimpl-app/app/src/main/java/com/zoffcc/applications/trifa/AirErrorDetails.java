package com.zoffcc.applications.trifa;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign stage 5, E21): Details after a failure that has a code for support (AirErrorCode),
 * as the quiet Details button of iOS under such a line (AirFieldNote, UIAlertController.showDetails).
 * The words end in "Details", underlined in the second ink, and the line, or the row it stands in,
 * opens the code with Copy. Without a code the line is plain words again and nothing on it is a button.
 */
final class AirErrorDetails
{
    private AirErrorDetails()
    {
    }

    /** The words, and "Details" after them when there is a code. */
    static CharSequence words(final Context context, final CharSequence text, @Nullable final String code)
    {
        if (code == null || code.isEmpty())
        {
            return text;
        }
        final SpannableStringBuilder words = new SpannableStringBuilder(text);
        words.append("  ");
        final int start = words.length();
        words.append(context.getString(R.string.error_details));
        words.setSpan(new ForegroundColorSpan(AirUi.color(context, R.color.kq_ink_2)), start, words.length(),
                      Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        words.setSpan(new UnderlineSpan(), start, words.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return words;
    }

    /** What a view was before Details made a button of it, put back once its words go. */
    private static final class Before
    {
        @Nullable
        final AccessibilityDelegateCompat delegate;
        final boolean clickable;

        Before(final View view)
        {
            delegate = ViewCompat.getAccessibilityDelegate(view);
            clickable = view.isClickable();
        }
    }

    /**
     * {@code view} (a line or a row with no click of its own) opens the code, announced as a button. With
     * no code, whatever an earlier code made of it is taken back: its accessibility delegate, which may
     * carry other properties (a row TalkBack stops on, below Android 9), and whether it was clickable. A
     * view it never touched is left as it is.
     */
    static void attach(final View view, @Nullable final String code)
    {
        final Object tag = view.getTag(R.id.air_error_details);
        if (code == null || code.isEmpty())
        {
            if (tag instanceof Before)
            {
                final Before before = (Before) tag;
                view.setTag(R.id.air_error_details, null);
                view.setOnClickListener(null);
                view.setClickable(before.clickable);
                ViewCompat.setAccessibilityDelegate(view, before.delegate);
            }
            return;
        }
        if (!(tag instanceof Before))
        {
            view.setTag(R.id.air_error_details, new Before(view));
        }
        view.setOnClickListener(v -> show(v, code));
        AirUi.markAsButton(view);
    }

    /** The code under its title with Copy, as iOS shows it; Copy puts the code alone on the clipboard. */
    static void show(final View anchor, final String code)
    {
        final Context context = anchor.getContext();
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.error_details)
                .setMessage(context.getString(R.string.error_details_format, code))
                .setPositiveButton(R.string.error_details_copy, (shown, which) -> copy(anchor, code))
                .setNegativeButton(R.string.error_ok_button, null)
                .create();
        AirUi.showDialog(context, dialog, false);
    }

    private static void copy(final View anchor, final String code)
    {
        final Context context = anchor.getContext();
        final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null)
        {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.error_details), code));
        final String done = context.getString(R.string.error_details_copied);
        // From Android 13 the system shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && anchor.isAttachedToWindow())
        {
            AirToast.show(anchor, done);
        }
        else
        {
            anchor.announceForAccessibility(done);
        }
    }
}

package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DimenRes;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.widget.TextViewCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, E21, stage 5): a sheet from below in the design's words and shapes, for the
 * questions and the small forms that were dialogs: a title, its words, fields with the words under them,
 * the verb on a button and Cancel apart (design/DESIGN.md, "Errors and questions on the phone").
 * {@link AirQuestion} is its one-verb case.
 */
final class AirSheet
{
    final BottomSheetDialog dialog;
    /** The sheet's own themed context, for the views put on it. */
    final Context context;
    private final LinearLayout content;
    /** A sheet with fields ({@link #withKeyboard}): it takes the keyboard down as it closes. */
    private boolean fields;

    /** The sheet over {@code context}'s activity, not shown yet; null when the activity is gone. */
    @Nullable
    static AirSheet create(final Context context, final CharSequence title, @Nullable final CharSequence words)
    {
        final Activity activity = activityOf(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed())
        {
            return null;
        }
        return new AirSheet(activity, title, words);
    }

    private AirSheet(final Activity activity, final CharSequence title, @Nullable final CharSequence words)
    {
        dialog = new BottomSheetDialog(activity, R.style.ThemeOverlay_KQ_Air_Sheet)
        {
            @Override
            public void dismiss()
            {
                // Every way out ends here: the verb, Cancel, Back, a tap outside, a swipe down.
                if (fields)
                {
                    keyboardDown();
                }
                super.dismiss();
            }
        };
        context = dialog.getContext();
        final Resources res = context.getResources();

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundResource(R.drawable.air_bg_sheet);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_16);
        content.setPaddingRelative(side, res.getDimensionPixelSize(R.dimen.kq_space_8), side,
                                   res.getDimensionPixelSize(R.dimen.kq_space_24));

        final View grip = new View(context);
        grip.setBackgroundResource(R.drawable.air_bg_grip);
        grip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        final LinearLayout.LayoutParams gripParams = new LinearLayout.LayoutParams(
                res.getDimensionPixelSize(R.dimen.kq_size_grip_width), res.getDimensionPixelSize(R.dimen.kq_size_grip_height));
        gripParams.gravity = Gravity.CENTER_HORIZONTAL;
        gripParams.bottomMargin = res.getDimensionPixelSize(R.dimen.kq_space_12);
        content.addView(grip, gripParams);

        final TextView titleView = AirScreenKit.text(context, R.style.TextAppearance_KQ_Heading, R.color.kq_ink);
        titleView.setText(title);
        ViewCompat.setAccessibilityHeading(titleView, true);
        content.addView(titleView, AirScreenKit.matchWidth());

        if (words != null && words.length() > 0)
        {
            words(words);
        }
        dialog.setContentView(content);
    }

    /**
     * What came of an operation that has no line of its own on the screen it returns to: its title and
     * words from below, with OK. False when there is no activity to show it on.
     */
    static boolean tell(final Context context, final CharSequence title, final CharSequence words)
    {
        final AirSheet sheet = create(context, title, words);
        if (sheet == null)
        {
            return false;
        }
        sheet.verb(sheet.context.getString(android.R.string.ok), false, v -> sheet.dismiss());
        return sheet.show();
    }

    /**
     * E21: the outcome of an operation stands under the row or button that started it ({@code anchor}),
     * a failure in the danger colour; where there is no such row (another screen started it), it is said
     * from below instead ({@link #tell}). Main thread only.
     */
    static void sayUnderOrTell(@Nullable final View anchor, final Context context, final CharSequence title,
                               final CharSequence words, final boolean failed)
    {
        if (anchor != null && anchor.isAttachedToWindow() && AirUi.sayUnder(anchor, words, failed))
        {
            return;
        }
        if (!tell(context, title, words))
        {
            // The screen went while the work ran: the outcome still reaches the person.
            Toast.makeText(context.getApplicationContext(), words, Toast.LENGTH_LONG).show();
        }
    }

    /** Words in the second ink, under what stands above them. */
    TextView words(final CharSequence text)
    {
        final TextView view = AirScreenKit.text(context, R.style.TextAppearance_KQ_Body, R.color.kq_ink_2);
        view.setText(text);
        return add(view, R.dimen.kq_space_8);
    }

    /** Any view in the width of the sheet, such as a field or the note under it. */
    <V extends View> V add(final V view, @DimenRes final int topMargin)
    {
        final LinearLayout.LayoutParams params = AirScreenKit.matchWidth();
        params.topMargin = context.getResources().getDimensionPixelSize(topMargin);
        content.addView(view, params);
        return view;
    }

    /**
     * The verb on its button: the ink button, or the danger colour for what cannot be taken back. The
     * listener decides when the sheet closes ({@link #dismissThen}), so a refusal can stay on it.
     */
    TextView verb(final CharSequence label, final boolean danger, final View.OnClickListener onClick)
    {
        final TextView button = AirScreenKit.button(context, label, !danger);
        if (danger)
        {
            // A verb that waits for something typed reads as waiting, not as red on grey.
            button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                                                   new int[]{AirUi.color(context, R.color.kq_ink_3),
                                                             AirUi.color(context, R.color.kq_danger)}));
        }
        button.setOnClickListener(onClick);
        return add(button, R.dimen.kq_space_24);
    }

    /** The verb that closes the sheet first and then acts, so whatever it opens comes in over the screen. */
    TextView verbThen(final CharSequence label, final boolean danger, final Runnable action)
    {
        return verb(label, danger, v -> dismissThen(action));
    }

    /** A second way on, under the verb, in the plain button. */
    TextView otherVerb(final CharSequence label, final Runnable action)
    {
        final TextView button = AirScreenKit.button(context, label, false);
        button.setOnClickListener(v -> dismissThen(action));
        return add(button, R.dimen.kq_space_12);
    }

    /** Cancel, apart from the verb so a hurried thumb does not take the one for the other. */
    TextView cancel(@Nullable final Runnable onCancel)
    {
        final TextView cancel = AirScreenKit.button(context, context.getString(R.string.cancel), false);
        TextViewCompat.setTextAppearance(cancel, R.style.TextAppearance_KQ_Label);
        cancel.setTextColor(AirUi.color(context, R.color.kq_ink_2));
        cancel.setBackgroundResource(R.drawable.air_bg_icon_button);
        cancel.setOnClickListener(v -> {
            if (onCancel == null)
            {
                dismiss();
            }
            else
            {
                dismissThen(onCancel);
            }
        });
        return add(cancel, R.dimen.kq_space_12);
    }

    /** Closes the sheet and runs {@code after} once it is gone. */
    void dismissThen(@Nullable final Runnable after)
    {
        dialog.setOnDismissListener(after == null ? null : d -> after.run());
        dismiss();
    }

    void dismiss()
    {
        try
        {
            if (dialog.isShowing())
            {
                dialog.dismiss();
            }
        }
        catch (Exception ignored)
        {
            // The activity went while the work behind the sheet ran.
        }
    }

    /** For a sheet that waits on work: Back, a tap outside and a swipe down leave it where it is. */
    AirSheet holdOpen()
    {
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.getBehavior().setDraggable(false);
        return this;
    }

    /**
     * A sheet with fields opens at its full height and stays above the keyboard, and takes the keyboard
     * down as it closes ({@link #keyboardDown}).
     */
    AirSheet withKeyboard()
    {
        fields = true;
        final BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        final Window window = dialog.getWindow();
        if (window != null)
        {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return this;
    }

    /**
     * The keyboard down while the sheet still has its window. The screen under the sheet keeps a keyboard
     * it did not ask for otherwise, as with AirChatPanel's dialogs with a field: a group's read-only bar,
     * where "Write to a moderator" opens a private message, has no field to type in.
     */
    private void keyboardDown()
    {
        final Window window = dialog.getWindow();
        final View decor = window == null ? null : window.peekDecorView();
        final InputMethodManager keyboard = context.getSystemService(InputMethodManager.class);
        if (decor != null && keyboard != null)
        {
            keyboard.hideSoftInputFromWindow(decor.getWindowToken(), 0);
        }
    }

    /** Shows the sheet; false when its activity went in the meantime. */
    boolean show()
    {
        final Window window = dialog.getWindow();
        if (window != null)
        {
            // The sheet's own window: its navigation bar continues the sheet instead of a black band.
            window.setNavigationBarColor(AirUi.color(context, R.color.kq_raised));
            WindowCompat.getInsetsController(window, window.getDecorView())
                        .setAppearanceLightNavigationBars(!HelperGeneric.is_nightmode_active(context));
        }
        try
        {
            dialog.show();
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    /** A still bar of how much of a long piece of work is done: no endless motion, it moves with the work. */
    static final class Progress extends FrameLayout
    {
        private final View fill;

        Progress(final Context context)
        {
            super(context);
            final Resources res = context.getResources();
            final float radius = res.getDimension(R.dimen.kq_size_bar) / 2f;
            final GradientDrawable track = new GradientDrawable();
            track.setColor(AirUi.color(context, R.color.kq_sunken));
            track.setCornerRadius(radius);
            setBackground(track);
            fill = new View(context);
            final GradientDrawable done = new GradientDrawable();
            done.setColor(AirUi.color(context, R.color.kq_ink));
            done.setCornerRadius(radius);
            fill.setBackground(done);
            addView(fill, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, res.getDimensionPixelSize(R.dimen.kq_size_bar)));
            setMinimumHeight(res.getDimensionPixelSize(R.dimen.kq_size_bar));
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            set(0);
        }

        /** {@code percent} of the work done, 0 to 100. */
        void set(final int percent)
        {
            final float part = Math.max(0, Math.min(100, percent)) / 100f;
            fill.setPivotX(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() : 0f);
            fill.setScaleX(part);
            if (getWidth() == 0)
            {
                // The pivot needs the width, which comes with the first layout.
                post(() -> {
                    fill.setPivotX(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() : 0f);
                    fill.setScaleX(part);
                });
            }
        }
    }

    @Nullable
    private static Activity activityOf(Context context)
    {
        while (context instanceof ContextWrapper)
        {
            if (context instanceof Activity)
            {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}

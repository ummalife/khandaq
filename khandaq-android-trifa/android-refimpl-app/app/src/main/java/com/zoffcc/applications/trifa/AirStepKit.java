package com.zoffcc.applications.trifa;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStructure;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.view.ViewCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;

import org.khandaq.messenger.R;

import java.util.List;
import java.util.Locale;

/**
 * KHANDAQ (redesign stage 4, E19 and E20): the parts of the pages of first launch and sign-in, as
 * AirStepController and its fields on iOS (AirFirstLaunch.swift): the three short bars of the steps,
 * a field with its words under it, the quiet button, the main action on the bar above the keyboard with
 * the reason it waits, the one shake of a wrong password and the slide from page to page.
 */
final class AirStepKit
{
    private AirStepKit()
    {
    }

    // ---- the steps ----

    /**
     * The three short bars in the header; the bars before the current one are filled, and the current one
     * fills when its page has come in. TalkBack reads "Step 2 of 3".
     */
    @SuppressLint("ViewConstructor")
    static final class Progress extends LinearLayout
    {
        private final View[] fills;
        private final int steps;
        private int shownStep = 0;

        Progress(final Context context, final int steps)
        {
            super(context);
            this.steps = steps;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER);
            final Resources res = context.getResources();
            final int width = res.getDimensionPixelSize(R.dimen.kq_space_24);
            final int height = res.getDimensionPixelSize(R.dimen.kq_space_4);
            final int gap = res.getDimensionPixelSize(R.dimen.kq_space_6);
            fills = new View[steps];
            for (int i = 0; i < steps; i++)
            {
                final android.widget.FrameLayout track = new android.widget.FrameLayout(context);
                track.setBackground(bar(context, R.color.kq_line_strong));
                final View fill = new View(context);
                fill.setBackground(bar(context, R.color.kq_ink));
                fill.setPivotX(0f);
                track.addView(fill, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                                ViewGroup.LayoutParams.MATCH_PARENT));
                final LayoutParams params = new LayoutParams(width, height);
                if (i > 0)
                {
                    params.setMarginStart(gap);
                }
                addView(track, params);
                fills[i] = fill;
            }
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        /**
         * Shows {@code step} (1 based): the earlier bars full, the current one filling now or at once. Only
         * a step forward fills a bar; going back leaves the current bar full.
         */
        void show(final int step, final boolean animate)
        {
            final boolean grow = animate && step > shownStep;
            shownStep = step;
            setContentDescription(getContext().getString(R.string.air_first_step_format, step, steps));
            for (int i = 0; i < steps; i++)
            {
                final View fill = fills[i];
                fill.animate().cancel();
                fill.setPivotX(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? fill.getWidth() : 0f);
                if (i < step - 1 || (i == step - 1 && (!grow || AirUi.reducedMotion())))
                {
                    fill.setScaleX(1f);
                    fill.setVisibility(VISIBLE);
                }
                else if (i == step - 1)
                {
                    fill.setVisibility(VISIBLE);
                    fill.setScaleX(0f);
                    fill.post(() -> {
                        fill.setPivotX(getLayoutDirection() == LAYOUT_DIRECTION_RTL ? fill.getWidth() : 0f);
                        fill.animate().scaleX(1f).setDuration(AirUi.duration(getContext(), R.integer.kq_dur_state))
                            .start();
                    });
                }
                else
                {
                    fill.setVisibility(INVISIBLE);
                }
            }
        }

        private static GradientDrawable bar(final Context context, final int color)
        {
            final GradientDrawable shape = new GradientDrawable();
            shape.setColor(AirUi.color(context, color));
            shape.setCornerRadius(context.getResources().getDimension(R.dimen.kq_space_2));
            return shape;
        }
    }

    // ---- fields ----

    /**
     * A one-line field in the input frame. A password field hides what is typed and carries the Show
     * password toggle at its end, a 48dp target.
     */
    @SuppressLint("ViewConstructor")
    static final class LineField extends LinearLayout
    {
        final EditText edit;
        @Nullable
        private final ImageView toggle;
        private boolean shown = false;

        LineField(final Context context, final CharSequence hint, final boolean secret)
        {
            super(context);
            final Resources res = context.getResources();
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackgroundResource(R.drawable.air_bg_input);
            // The frame turns to ink while its field has the focus.
            setAddStatesFromChildren(true);
            setMinimumHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android));
            edit = new EditText(context);
            edit.setId(View.generateViewId());
            TextViewCompat.setTextAppearance(edit, R.style.TextAppearance_KQ_Body);
            edit.setTextColor(AirUi.color(context, R.color.kq_ink));
            edit.setHintTextColor(AirUi.color(context, R.color.kq_ink_3));
            edit.setHint(hint);
            edit.setBackground(null);
            final int pad = res.getDimensionPixelSize(R.dimen.kq_space_12);
            edit.setPaddingRelative(pad, pad, secret ? 0 : pad, pad);
            edit.setSingleLine(true);
            edit.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            edit.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            addView(edit, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (secret)
            {
                // A password input type sets the monospace face; the hint and the dots keep the body face
                // (mono is only for IDs and keys).
                final android.graphics.Typeface face = edit.getTypeface();
                edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                edit.setTypeface(face);
                edit.setTransformationMethod(PasswordTransformationMethod.getInstance());
                // A password is written left to right in every language, as on iOS.
                edit.setTextDirection(View.TEXT_DIRECTION_LTR);
                final ImageView eye = new ImageView(context, null, 0, R.style.Widget_KQ_Air_IconButton);
                eye.setImageResource(R.drawable.kq_ic_eye);
                eye.setOnClickListener(v -> setShown(!shown));
                AirUi.markAsButton(eye);
                addView(eye, new LayoutParams(res.getDimensionPixelSize(R.dimen.kq_size_target_android),
                                              res.getDimensionPixelSize(R.dimen.kq_size_target_android)));
                toggle = eye;
                setShown(false);
            }
            else
            {
                toggle = null;
            }
        }

        String text()
        {
            return String.valueOf(edit.getText());
        }

        void limitBytes(final int maxBytes)
        {
            edit.setFilters(new InputFilter[]{new AirUtf8Limit(maxBytes)});
        }

        /** Hides what was typed again and empties the field, so nothing half-typed is offered for saving. */
        void clear()
        {
            setShown(false);
            edit.setText("");
        }

        private void setShown(final boolean show)
        {
            shown = show;
            if (toggle == null)
            {
                return;
            }
            final int selection = edit.getSelectionEnd();
            edit.setTransformationMethod(show ? null : PasswordTransformationMethod.getInstance());
            if (selection >= 0 && selection <= edit.length())
            {
                edit.setSelection(selection);
            }
            toggle.setActivated(show);
            ImageViewCompat.setImageTintList(toggle, android.content.res.ColorStateList.valueOf(
                    AirUi.color(getContext(), show ? R.color.kq_ink : R.color.kq_ink_3)));
            toggle.setContentDescription(getContext().getString(show ? R.string.air_field_hide_password
                                                                     : R.string.air_field_show_password));
        }
    }

    /**
     * The words under a field: the rule as a hint in the third ink until something is refused or fails.
     * The note keeps the room of two lines even while it is empty, as AirFieldNote on iOS, so a refusal
     * or a failure comes in without moving what stands below it.
     */
    static TextView fieldNote(final Context context, @Nullable final CharSequence hint)
    {
        final TextView note = AirScreenKit.text(context, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
        note.setMinLines(2);
        note.setText(hint);
        note.setVisibility(View.VISIBLE);
        ViewCompat.setAccessibilityLiveRegion(note, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        return note;
    }

    /** The note back to its hint, or empty when it has none; its room stays. */
    static void showHint(final TextView note, @Nullable final CharSequence hint)
    {
        // A failure's Details goes with its words.
        AirErrorDetails.attach(note, null);
        note.animate().cancel();
        note.setAlpha(1f);
        note.setTranslationY(0f);
        note.setTextColor(AirUi.color(note.getContext(), R.color.kq_ink_3));
        note.setText(hint);
        note.setVisibility(View.VISIBLE);
    }

    /** The quieter way of a page, such as moving a profile in: words in the second ink, a 48dp target. */
    static TextView quietButton(final Context context, final CharSequence label)
    {
        final Resources res = context.getResources();
        final TextView button = AirScreenKit.text(context, R.style.TextAppearance_KQ_Label, R.color.kq_ink_2);
        button.setText(label);
        button.setMinHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android));
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setBackgroundResource(R.drawable.air_bg_row);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_4);
        button.setPaddingRelative(side, 0, side, 0);
        button.setClickable(true);
        button.setFocusable(true);
        AirUi.markAsButton(button);
        return button;
    }

    /** The title of a page in the display face and the words under it. */
    static LinearLayout header(final Context context, final CharSequence title, @Nullable final CharSequence body)
    {
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        final TextView titleView = AirScreenKit.text(context, R.style.TextAppearance_KQ_Display, R.color.kq_ink);
        titleView.setText(title);
        ViewCompat.setAccessibilityHeading(titleView, true);
        header.addView(titleView, AirScreenKit.matchWidth());
        if (body != null && body.length() > 0)
        {
            final TextView bodyView = AirScreenKit.body(context, body);
            final LinearLayout.LayoutParams params = AirScreenKit.matchWidth();
            params.topMargin = context.getResources().getDimensionPixelSize(R.dimen.kq_space_8);
            header.addView(bodyView, params);
        }
        return header;
    }

    // ---- the main action ----

    /**
     * The one action of a page on the bar above the keyboard, and under it why it waits ("Type a name
     * first."). While the profile is made or opened the button says so and takes no taps.
     */
    static final class MainBar
    {
        final TextView button;
        final TextView why;
        private CharSequence label;
        @Nullable
        private CharSequence waiting;
        private boolean busy;

        MainBar(final Activity activity, final CharSequence label, final Runnable onTap)
        {
            final LinearLayout bottom = activity.findViewById(R.id.air_screen_bottom);
            this.label = label;
            button = AirScreenKit.button(activity, label, true);
            button.setMaxLines(2);
            button.setOnClickListener(v -> {
                if (!busy)
                {
                    onTap.run();
                }
            });
            why = AirScreenKit.note(activity, "");
            why.setGravity(Gravity.CENTER_HORIZONTAL);
            bottom.addView(button, AirScreenKit.matchWidth());
            final LinearLayout.LayoutParams whyParams = AirScreenKit.matchWidth();
            whyParams.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.kq_space_6);
            bottom.addView(why, whyParams);
            bottom.setVisibility(View.VISIBLE);
            ViewCompat.setAccessibilityDelegate(button, new androidx.core.view.AccessibilityDelegateCompat()
            {
                @Override
                public void onInitializeAccessibilityNodeInfo(final View host,
                                                              final androidx.core.view.accessibility.AccessibilityNodeInfoCompat info)
                {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(android.widget.Button.class.getName());
                    if (waiting != null)
                    {
                        info.setHintText(waiting);
                    }
                }
            });
            setWaiting(null);
        }

        void setLabel(final CharSequence text)
        {
            label = text;
            if (!busy)
            {
                button.setText(text);
            }
        }

        /** Why the action waits, under the button; the button is off while there is a reason. */
        void setWaiting(@Nullable final CharSequence reason)
        {
            waiting = reason;
            button.setEnabled(reason == null && !busy);
            why.setTextColor(AirUi.color(why.getContext(), R.color.kq_ink_3));
            why.setText(reason);
            why.setVisibility(reason == null ? View.GONE : View.VISIBLE);
        }

        /** What went wrong with the action, under it in the danger colour, until the next change. */
        void sayFailure(final CharSequence text)
        {
            AirUi.sayOnLine(why, text, true);
        }

        /** Words such as "Creating the profile" in place of the label, or null to give the label back. */
        void setBusy(@Nullable final CharSequence words)
        {
            busy = words != null;
            button.setText(busy ? words : label);
            button.setEnabled(!busy && waiting == null);
            if (busy)
            {
                button.announceForAccessibility(words);
            }
        }

        boolean isBusy()
        {
            return busy;
        }
    }

    // ---- the name and the ID ----

    /**
     * The profile's name as the username half of a password form, so a password manager saves the name
     * with the password and offers the pair at sign-in. It reads as text: it takes no focus and no
     * typing, and a saved username never replaces it. The name keeps to the page's leading edge
     * whatever its script.
     */
    @SuppressLint("ViewConstructor")
    static final class UsernameText extends AppCompatEditText
    {
        UsernameText(final Context context, final int appearance, final int colorRes)
        {
            super(context);
            TextViewCompat.setTextAppearance(this, appearance);
            setTextColor(AirUi.color(context, colorRes));
            setBackground(null);
            setPadding(0, 0, 0, 0);
            setMinHeight(0);
            setMinimumHeight(0);
            setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            setHorizontallyScrolling(false);
            setFocusable(false);
            setFocusableInTouchMode(false);
            setClickable(false);
            setLongClickable(false);
            setCursorVisible(false);
            setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            setTextDirection(View.TEXT_DIRECTION_LOCALE);
            setSaveEnabled(false);
            setAutofillHints(View.AUTOFILL_HINT_USERNAME);
            setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            ViewCompat.setAccessibilityDelegate(this, new androidx.core.view.AccessibilityDelegateCompat()
            {
                @Override
                public void onInitializeAccessibilityNodeInfo(final View host,
                                                              final androidx.core.view.accessibility.AccessibilityNodeInfoCompat info)
                {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(TextView.class.getName());
                    info.setEditable(false);
                }
            });
        }

        /** The bare name given to password managers when the view shows it inside a sentence; null: the text. */
        @Nullable
        private CharSequence username;

        /**
         * Shows {@code shown}, a line that names the profile ("For the profile Amina"), while password
         * managers still get the bare {@code name} as the username of the form.
         */
        void setUsername(final CharSequence name, final CharSequence shown)
        {
            username = name;
            setText(shown);
        }

        @Override
        public AutofillValue getAutofillValue()
        {
            return username == null ? super.getAutofillValue() : AutofillValue.forText(username);
        }

        @Override
        public void onProvideAutofillStructure(final ViewStructure structure, final int flags)
        {
            super.onProvideAutofillStructure(structure, flags);
            if (username != null)
            {
                // A service that reads the node's text instead of its value saves the bare name too.
                structure.setText(username);
            }
        }

        @Override
        public void autofill(final AutofillValue value)
        {
            // The name belongs to the profile; whatever a password manager has saved does not replace it.
        }
    }

    /**
     * An ID in its three named parts, key, antispam and check, on a surface plate: the groups of four
     * left to right in every language, four to a line or fewer when a large font leaves no room. The
     * group views are added to {@code groupsOut} so a page can bring them in one after another.
     */
    static LinearLayout idPlate(final Context context, final String toxId, @Nullable final List<View> groupsOut)
    {
        final Resources res = context.getResources();
        final LinearLayout plate = AirScreenKit.plate(context);
        final int pad = res.getDimensionPixelSize(R.dimen.kq_space_16);
        plate.setPaddingRelative(pad, pad, pad, pad);
        final String id = toxId.toUpperCase(Locale.ROOT);
        addIdPart(plate, R.string.air_you_part_key, id.substring(0, KEY_DIGITS), groupsOut);
        addIdPart(plate, R.string.air_you_part_antispam, id.substring(KEY_DIGITS, KEY_DIGITS + ANTISPAM_DIGITS), groupsOut);
        addIdPart(plate, R.string.air_you_part_check, id.substring(KEY_DIGITS + ANTISPAM_DIGITS, ToxSaveSummary.ID_HEX_LENGTH), groupsOut);
        return plate;
    }

    private static final int KEY_DIGITS = 64;
    private static final int ANTISPAM_DIGITS = 8;

    private static void addIdPart(final LinearLayout plate, final int title, final String digits,
                                  @Nullable final List<View> groupsOut)
    {
        final Context context = plate.getContext();
        final Resources res = context.getResources();
        final LinearLayout part = new LinearLayout(context);
        part.setOrientation(LinearLayout.VERTICAL);
        // TalkBack reads the name of the part and its digits as one, as in the You screen.
        ViewCompat.setScreenReaderFocusable(part, true);
        part.setContentDescription(context.getString(title) + ": " + AirConversationModel.keyLines(digits, 0));
        final TextView caption = AirScreenKit.text(context, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
        caption.setText(title);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        part.addView(caption, AirScreenKit.matchWidth());
        final IdGroups line = new IdGroups(context);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (final String group : AirConversationModel.keyGroups(digits))
        {
            final TextView view = AirScreenKit.text(context, R.style.TextAppearance_KQ_Mono, R.color.kq_ink);
            view.setText(group);
            view.setTextDirection(View.TEXT_DIRECTION_LTR);
            line.addView(view);
            if (groupsOut != null)
            {
                groupsOut.add(view);
            }
        }
        final LinearLayout.LayoutParams lineParams = AirScreenKit.matchWidth();
        lineParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_4);
        part.addView(line, lineParams);
        final LinearLayout.LayoutParams partParams = AirScreenKit.matchWidth();
        partParams.topMargin = plate.getChildCount() == 0 ? 0 : res.getDimensionPixelSize(R.dimen.kq_space_12);
        plate.addView(part, partParams);
    }

    /**
     * The groups of one part of an ID: four to a line, two or one when a large font leaves no room for
     * four, so the groups keep their columns. Always left to right.
     */
    @SuppressLint("ViewConstructor")
    static final class IdGroups extends ViewGroup
    {
        private static final int MOST = 4;
        private final int gap;
        private final int lineGap;
        private int perLine = MOST;
        private int cellWidth;
        private int cellHeight;

        IdGroups(final Context context)
        {
            super(context);
            gap = context.getResources().getDimensionPixelSize(R.dimen.kq_space_8);
            lineGap = context.getResources().getDimensionPixelSize(R.dimen.kq_space_4);
            setLayoutDirection(LAYOUT_DIRECTION_LTR);
        }

        @Override
        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
        {
            final int unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            cellWidth = 0;
            cellHeight = 0;
            final int count = getChildCount();
            for (int i = 0; i < count; i++)
            {
                final View child = getChildAt(i);
                child.measure(unspecified, unspecified);
                cellWidth = Math.max(cellWidth, child.getMeasuredWidth());
                cellHeight = Math.max(cellHeight, child.getMeasuredHeight());
            }
            final int horizontal = getPaddingLeft() + getPaddingRight();
            final int available = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                                  ? Integer.MAX_VALUE : MeasureSpec.getSize(widthMeasureSpec) - horizontal;
            perLine = MOST;
            while (perLine > 1 && perLine * cellWidth + (perLine - 1) * gap > available)
            {
                perLine /= 2;
            }
            final int lines = count == 0 ? 0 : (count + perLine - 1) / perLine;
            final int width = Math.min(count, perLine) * cellWidth + Math.max(0, Math.min(count, perLine) - 1) * gap;
            final int height = lines * cellHeight + Math.max(0, lines - 1) * lineGap;
            setMeasuredDimension(resolveSize(width + horizontal, widthMeasureSpec),
                                 resolveSize(height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
        }

        @Override
        protected void onLayout(final boolean changed, final int l, final int t, final int r, final int b)
        {
            for (int i = 0; i < getChildCount(); i++)
            {
                final View child = getChildAt(i);
                final int x = getPaddingLeft() + (i % perLine) * (cellWidth + gap);
                final int y = getPaddingTop() + (i / perLine) * (cellHeight + lineGap);
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
            }
        }
    }

    /**
     * The groups of an ID come in one after another, each rising a little as it fades in (the stagger
     * token apart), or stand at once with reduced motion and with TalkBack.
     */
    static void assemble(final List<View> groups, final boolean animate)
    {
        final Context context = groups.isEmpty() ? null : groups.get(0).getContext();
        final boolean move = animate && context != null && !AirUi.reducedMotion() && !exploringByTouch(context);
        final float rise = context == null ? 0f : context.getResources().getDimension(R.dimen.kq_space_4);
        final long duration = context == null ? 0 : AirUi.duration(context, R.integer.kq_dur_state);
        final long stagger = context == null ? 0 : AirUi.duration(context, R.integer.kq_dur_stagger);
        for (int i = 0; i < groups.size(); i++)
        {
            final View group = groups.get(i);
            group.animate().cancel();
            if (!move)
            {
                group.setAlpha(1f);
                group.setTranslationY(0f);
                continue;
            }
            group.setAlpha(0f);
            group.setTranslationY(rise);
            group.animate().alpha(1f).translationY(0f).setStartDelay(i * stagger).setDuration(duration).start();
        }
    }

    // ---- motion ----

    /** One sideways shake of a wrong password or PIN; nothing moves with reduced motion. */
    static void shakeOnce(final View view)
    {
        if (AirUi.reducedMotion())
        {
            return;
        }
        final float unit = view.getResources().getDisplayMetrics().density;
        final ObjectAnimator shake = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -6f * unit, 6f * unit,
                                                            -4f * unit, 2f * unit, 0f);
        shake.setDuration(AirUi.duration(view.getContext(), R.integer.kq_dur_reveal));
        shake.start();
    }

    /**
     * From one page to the next in the direction of travel: forward the old page leaves towards the start
     * and the new one comes in from the end (mirrored in right-to-left languages), back the other way.
     * With reduced motion the two cross-fade.
     */
    static void turn(final View from, final View to, final boolean forward, @Nullable final Runnable done)
    {
        final Context context = to.getContext();
        from.animate().cancel();
        to.animate().cancel();
        if (AirUi.reducedMotion())
        {
            final long fade = AirUi.duration(context, R.integer.kq_dur_state);
            to.setTranslationX(0f);
            to.setAlpha(0f);
            to.setVisibility(View.VISIBLE);
            from.animate().alpha(0f).setDuration(fade).setListener(new AnimatorListenerAdapter()
            {
                @Override
                public void onAnimationEnd(final Animator animation)
                {
                    from.animate().setListener(null);
                    from.setVisibility(View.GONE);
                    from.setAlpha(1f);
                }
            }).start();
            to.animate().alpha(1f).setDuration(fade).withEndAction(done).start();
            return;
        }
        final boolean rtl = to.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final float width = Math.max(from.getWidth(), context.getResources().getDisplayMetrics().widthPixels);
        final float away = (forward ^ rtl) ? -width : width;
        final long duration = AirUi.duration(context, R.integer.kq_dur_layout);
        to.setAlpha(1f);
        to.setTranslationX(-away);
        to.setVisibility(View.VISIBLE);
        from.animate().translationX(away).setDuration(duration).setListener(new AnimatorListenerAdapter()
        {
            @Override
            public void onAnimationEnd(final Animator animation)
            {
                from.animate().setListener(null);
                from.setVisibility(View.GONE);
                from.setTranslationX(0f);
            }
        }).start();
        to.animate().translationX(0f).setDuration(duration).withEndAction(done).start();
    }

    /** True when TalkBack (or another touch-exploring service) is on: things then stand at once. */
    static boolean exploringByTouch(final Context context)
    {
        final AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        return manager != null && manager.isEnabled() && manager.isTouchExplorationEnabled();
    }

    /**
     * The focus in {@code field} and the keyboard up, once the field stands in a window that has the
     * focus: a page coming back from another one gets the window focus a moment after it resumes, and
     * the keyboard asked for before that does not come up.
     */
    static void showKeyboard(final EditText field)
    {
        field.requestFocus();
        field.post(() -> {
            if (!field.isAttachedToWindow())
            {
                return;
            }
            if (field.hasWindowFocus())
            {
                raiseKeyboard(field);
                return;
            }
            final ViewTreeObserver observer = field.getViewTreeObserver();
            observer.addOnWindowFocusChangeListener(new ViewTreeObserver.OnWindowFocusChangeListener()
            {
                @Override
                public void onWindowFocusChanged(final boolean hasFocus)
                {
                    if (!hasFocus)
                    {
                        return;
                    }
                    field.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                    field.post(() -> raiseKeyboard(field));
                }
            });
        });
    }

    private static void raiseKeyboard(final EditText field)
    {
        final InputMethodManager keyboard = field.getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null && field.isAttachedToWindow() && field.hasFocus())
        {
            keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    /** The keyboard down, and the focus to {@code sink}, so no field takes it and raises the keyboard again. */
    static void hideKeyboard(final Activity activity, final View sink)
    {
        final InputMethodManager keyboard = activity.getSystemService(InputMethodManager.class);
        final View focus = activity.getCurrentFocus();
        if (keyboard != null && focus != null)
        {
            keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
        sink.requestFocus();
    }

    /**
     * True for the key-down half of Enter on a hardware keyboard: it only arms the key, and the action
     * runs on its release in the same field. Acting on the press moved the focus, and the release then
     * acted again in the next field.
     */
    static boolean enterArming(final int actionId, @Nullable final KeyEvent event)
    {
        return actionId == EditorInfo.IME_NULL && event != null && event.getAction() == KeyEvent.ACTION_DOWN;
    }

    /** Next on the keyboard (or Enter) moves to {@code next}; Done (or Enter in the last field) runs {@code done}. */
    static void chain(final EditText field, @Nullable final EditText next, final Runnable done)
    {
        field.setImeOptions(next != null ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (enterArming(actionId, event))
            {
                return true;
            }
            final boolean enter = actionId == EditorInfo.IME_NULL;
            if (next != null && (actionId == EditorInfo.IME_ACTION_NEXT || enter))
            {
                next.requestFocus();
                return true;
            }
            if (actionId == EditorInfo.IME_ACTION_DONE || enter)
            {
                done.run();
                return true;
            }
            return false;
        });
    }
}

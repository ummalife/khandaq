package com.zoffcc.applications.trifa;

import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DimenRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.ActionBar;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.widget.TextViewCompat;

import org.khandaq.messenger.R;

import java.text.NumberFormat;

/**
 * KHANDAQ PIN screen. A LOCAL UI gate: it never touches DB encryption or DbSecretKeyStorage; the database
 * still opens with its own key. Two modes via the {@link #EXTRA_MODE} extra:
 * <ul>
 * <li>MODE_SET, from Password and PIN: enter a 4-digit PIN, repeat it, then persist its salted hash.
 * Returns RESULT_OK only after a successful set, so the caller enables the lock only then.</li>
 * <li>MODE_UNLOCK, from {@link AppLockHelper} after a background timeout, or on a not yet unlocked fresh
 * process: verify the PIN to return to the app. Back does nothing here.</li>
 * </ul>
 * KHANDAQ (redesign stage 4, E20): drawn as the PIN screens on iOS (PinInputView): the cells of kq-pin,
 * then a keypad of 64dp circles low on the page, in the thumb's reach, with the digits in the app's
 * numerals and the order kept left to right in right-to-left languages, and Delete as a key. Since stage 5
 * the lock stands on the flat lock ground of the call screens (lock-top), dark in both themes as on iOS
 * (EnterPinController on AirCallBackdropView): the wordmark with white letters, white words, the cells and
 * the keys translucent white with no outline, a key turning white while pressed. Setting it is a page on
 * the background colour with the way back and a note on what a forgotten PIN means on Android, where
 * wrong tries are not limited. A wrong PIN shakes the cells once (not with reduced motion) and is said
 * under them, as a mismatch is while setting it.
 */
public class PinActivity extends AirScreenActivity
{
    static final String EXTRA_MODE = "mode";
    static final String MODE_SET = "set";
    static final String MODE_UNLOCK = "unlock";

    private static final int PIN_LENGTH = 4;
    /** The full cells stay this long after a refusal, so the shake is seen on them before they empty. */
    private static final long CLEAR_DELAY_MS = 120;

    private boolean unlockMode;
    private final StringBuilder entered = new StringBuilder();
    private String firstEntry; // set mode: the PIN from the first pass, awaiting its repeat
    private boolean refused = false;

    private TextView titleView;
    private LinearLayout cellsRow;
    private final View[] cells = new View[PIN_LENGTH];
    private final View[] dots = new View[PIN_LENGTH];
    private TextView refusal;

    private final Runnable complete = this::onComplete;
    private final Runnable emptyCells = this::updateCells;

    @Override
    protected int screenTitle()
    {
        return isUnlockIntent() ? R.string.air_pin_enter_title : R.string.pin_set_title;
    }

    private boolean isUnlockIntent()
    {
        return MODE_UNLOCK.equals(getIntent().getStringExtra(EXTRA_MODE));
    }

    /** The lock has no header, so no line under it; setting a PIN keeps both, as the other settings. */
    @Override
    protected boolean headerRule()
    {
        return !isUnlockIntent();
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        unlockMode = isUnlockIntent();
        final Resources res = getResources();

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            // The title stands over the cells; the header keeps only the way back.
            actionBar.setTitle(null);
        }
        setTitle(getString(screenTitle()));
        if (unlockMode)
        {
            // The lock has no way back: no header, and Back does nothing.
            findViewById(R.id.air_screen_toolbar).setVisibility(View.GONE);
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true)
            {
                @Override
                public void handleOnBackPressed()
                {
                    // Do NOT let the user bypass the lock with the back button.
                }
            });
            styleLockGround();
        }

        column.setGravity(Gravity.CENTER_HORIZONTAL);
        if (unlockMode)
        {
            final ImageView wordmark = new ImageView(this);
            wordmark.setImageDrawable(lockWordmark());
            wordmark.setAdjustViewBounds(true);
            wordmark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            wordmark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, res.getDimensionPixelSize(R.dimen.kq_space_40));
            markParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_24);
            column.addView(wordmark, markParams);
        }
        // The height left over goes above the title, so the keypad stands low, in the thumb's reach.
        column.addView(new Space(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        titleView = centred(AirScreenKit.text(this, R.style.TextAppearance_KQ_Heading,
                                              unlockMode ? R.color.kq_fixed_white : R.color.kq_ink));
        titleView.setText(screenTitle());
        ViewCompat.setAccessibilityHeading(titleView, true);
        column.addView(titleView, unlockMode ? spaced(R.dimen.kq_space_24) : AirScreenKit.matchWidth());
        if (!unlockMode)
        {
            // What a forgotten PIN means here; there is no lockout after wrong tries on Android to speak of.
            column.addView(centred(AirScreenKit.body(this, getString(R.string.air_pin_set_note))),
                           spaced(R.dimen.kq_space_8));
        }

        cellsRow = buildCells();
        column.addView(cellsRow, centredWrap(R.dimen.kq_space_24));
        // The refusal under the cells keeps its two lines of room, so nothing below moves when it comes.
        refusal = centred(AirStepKit.fieldNote(this, null));
        column.addView(refusal, spaced(R.dimen.kq_space_8));
        final LinearLayout keypad = buildKeypad();
        column.addView(keypad, centredWrap(R.dimen.kq_space_16));
        fitKeypad(keypad);
        updateCells();
    }

    /**
     * On a short screen (360 by 640, a large font) the keys shrink from 64dp towards the 48dp touch target
     * until the whole step fits without scrolling; past that the column scrolls, as on every Air screen.
     */
    private void fitKeypad(final LinearLayout keypad)
    {
        final View scroll = findViewById(R.id.air_screen_scroll);
        if (scroll == null)
        {
            return;
        }
        final int smallest = getResources().getDimensionPixelSize(R.dimen.kq_size_target_android);
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener()
        {
            @Override
            public void onGlobalLayout()
            {
                final int over = column.getHeight() - scroll.getHeight();
                final int rows = keypad.getChildCount();
                final int size = rows == 0 ? 0
                                 : ((ViewGroup) keypad.getChildAt(0)).getChildAt(0).getLayoutParams().height;
                if (over <= 0 || size <= smallest)
                {
                    scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    return;
                }
                final int next = Math.max(smallest, size - (over + rows - 1) / rows);
                for (int r = 0; r < rows; r++)
                {
                    final ViewGroup row = (ViewGroup) keypad.getChildAt(r);
                    for (int k = 0; k < row.getChildCount(); k++)
                    {
                        final View key = row.getChildAt(k);
                        final ViewGroup.LayoutParams params = key.getLayoutParams();
                        params.width = next;
                        params.height = next;
                        key.setLayoutParams(params);
                    }
                }
            }
        });
    }

    /**
     * The lock on the flat lock ground (lock-top) in both themes, as on iOS: the page and the bars take it,
     * and the bars keep light icons. Setting a PIN keeps the theme ground of AirScreenActivity.
     */
    @SuppressWarnings("deprecation")
    private void styleLockGround()
    {
        final int ground = AirUi.color(this, R.color.kq_lock_top);
        findViewById(R.id.air_screen_root).setBackgroundColor(ground);
        final Window window = getWindow();
        window.setStatusBarColor(ground);
        window.setNavigationBarColor(ground);
        final WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
    }

    /**
     * The wordmark with white letters (its night form) whatever the theme, since the lock ground is dark in
     * both, as iOS shows it on the lock.
     */
    private Drawable lockWordmark()
    {
        final Configuration night = new Configuration(getResources().getConfiguration());
        night.uiMode = (night.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        return ContextCompat.getDrawable(createConfigurationContext(night), R.drawable.khandaq_wordmark);
    }

    @Override
    protected void onDestroy()
    {
        if (cellsRow != null)
        {
            cellsRow.removeCallbacks(emptyCells);
        }
        if (cells[PIN_LENGTH - 1] != null)
        {
            cells[PIN_LENGTH - 1].removeCallbacks(complete);
        }
        super.onDestroy();
    }

    // ---- the cells and the keypad ----

    /**
     * The four cells of kq-pin, left to right in every language. TalkBack reads them as one: the title, and
     * how many digits are in.
     */
    private LinearLayout buildCells()
    {
        final Resources res = getResources();
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        ViewCompat.setScreenReaderFocusable(row, true);
        final int width = res.getDimensionPixelSize(R.dimen.kq_size_target_ios);
        final int height = res.getDimensionPixelSize(R.dimen.kq_size_pin_cell);
        final int gap = res.getDimensionPixelSize(R.dimen.kq_space_8);
        final int dotSize = res.getDimensionPixelSize(R.dimen.kq_size_dot);
        // On the lock the cells are translucent white with white dots, as AirPinCell on iOS.
        final ColorStateList ink = ColorStateList.valueOf(
                AirUi.color(this, unlockMode ? R.color.kq_fixed_white : R.color.kq_ink));
        for (int i = 0; i < PIN_LENGTH; i++)
        {
            final FrameLayout cell = new FrameLayout(this);
            cell.setBackgroundResource(unlockMode ? R.drawable.air_bg_pin_cell_lock : R.drawable.air_bg_pin_cell);
            cell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            final View dot = new View(this);
            dot.setBackgroundResource(R.drawable.air_bg_dot);
            dot.setBackgroundTintList(ink);
            dot.setVisibility(View.INVISIBLE);
            cell.addView(dot, new FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER));
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
            params.setMarginStart(i == 0 ? 0 : gap);
            row.addView(cell, params);
            cells[i] = cell;
            dots[i] = dot;
        }
        return row;
    }

    /** 1 to 9 in three rows, then 0 and Delete; left to right in every language, as on iOS. */
    private LinearLayout buildKeypad()
    {
        final NumberFormat numerals = NumberFormat.getIntegerInstance(AirUi.locale(this));
        final LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        keypad.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        final int down = getResources().getDimensionPixelSize(R.dimen.kq_space_12);
        for (int row = 0; row < 3; row++)
        {
            addKeyRow(keypad, row == 0 ? 0 : down, digitKey(numerals, row * 3 + 1), digitKey(numerals, row * 3 + 2),
                      digitKey(numerals, row * 3 + 3));
        }
        addKeyRow(keypad, down, new Space(this), digitKey(numerals, 0), deleteKey());
        return keypad;
    }

    private void addKeyRow(final LinearLayout keypad, final int top, final View... keys)
    {
        final Resources res = getResources();
        final int size = res.getDimensionPixelSize(R.dimen.kq_space_64);
        final int across = res.getDimensionPixelSize(R.dimen.kq_space_24);
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = 0; i < keys.length; i++)
        {
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart(i == 0 ? 0 : across);
            row.addView(keys[i], params);
        }
        final LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                                  ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = top;
        keypad.addView(row, rowParams);
    }

    /** A digit in the display face, in the numerals of the app's language (Arabic-Indic in Arabic). */
    private TextView digitKey(final NumberFormat numerals, final int digit)
    {
        final TextView key = keyView(R.style.TextAppearance_KQ_Display,
                                     unlockMode ? R.drawable.air_bg_pin_key_lock : R.drawable.air_bg_pin_key);
        if (unlockMode)
        {
            // White, and lock-top on the white circle while pressed, as AirPinKey on iOS.
            key.setTextColor(ContextCompat.getColorStateList(this, R.color.air_pin_key_lock_text));
        }
        // Digits of one width, as on the keys of iOS.
        key.setFontFeatureSettings("tnum");
        key.setText(numerals.format(digit));
        final char typed = (char) ('0' + digit);
        key.setOnClickListener(v -> onDigit(typed));
        return key;
    }

    private TextView deleteKey()
    {
        final TextView key = keyView(R.style.TextAppearance_KQ_Label,
                                     unlockMode ? R.drawable.air_bg_pin_delete_lock : R.drawable.air_bg_pin_delete);
        if (unlockMode)
        {
            key.setTextColor(AirUi.color(this, R.color.kq_fixed_white));
        }
        key.setText(R.string.pin_delete);
        key.setContentDescription(getString(R.string.air_pin_delete_digit));
        key.setOnClickListener(v -> onDelete());
        return key;
    }

    /** A key: its label centred in the 64dp circle and shrunk at a large font size instead of clipped. */
    private TextView keyView(@StyleRes final int appearance, @DrawableRes final int background)
    {
        final TextView key = AirScreenKit.text(this, appearance, R.color.kq_ink);
        key.setGravity(Gravity.CENTER);
        key.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        key.setIncludeFontPadding(false);
        key.setLineSpacing(0f, 1f);
        key.setMaxLines(1);
        key.setBackgroundResource(background);
        final int pad = getResources().getDimensionPixelSize(R.dimen.kq_space_4);
        key.setPadding(pad, pad, pad, pad);
        key.setClickable(true);
        key.setFocusable(true);
        final int full = Math.round(key.getTextSize());
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(key, Math.max(1, Math.round(full * 0.4f)),
                                                                   Math.max(2, full), 1, TypedValue.COMPLEX_UNIT_PX);
        AirUi.markAsButton(key);
        return key;
    }

    // ---- typing ----

    /** A hardware keyboard types the PIN too: its digits, the number pad and Delete. */
    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event)
    {
        if (event.getRepeatCount() == 0)
        {
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9)
            {
                onDigit((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
                return true;
            }
            if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9)
            {
                onDigit((char) ('0' + keyCode - KeyEvent.KEYCODE_NUMPAD_0));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL)
            {
                onDelete();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void onDigit(final char digit)
    {
        if (entered.length() >= PIN_LENGTH)
        {
            return;
        }
        if (refused)
        {
            // The next try begins: the words of the refusal give way.
            refused = false;
            AirStepKit.showHint(refusal, null);
        }
        entered.append(digit);
        updateCells();
        if (entered.length() == PIN_LENGTH)
        {
            // let the last cell render before acting
            cells[PIN_LENGTH - 1].post(complete);
        }
    }

    private void onDelete()
    {
        if (entered.length() > 0)
        {
            entered.deleteCharAt(entered.length() - 1);
            updateCells();
        }
    }

    private void onComplete()
    {
        if (entered.length() < PIN_LENGTH || isFinishing())
        {
            return;
        }
        final String pin = entered.toString();
        if (unlockMode)
        {
            if (AppLockHelper.verifyPin(this, pin))
            {
                // KHANDAQ (audit #8): this process is now unlocked. Must happen BEFORE finish(): the screen
                // underneath gets onStart() again and would otherwise re-raise the gate.
                AppLockHelper.markProcessUnlocked();
                finish();
            }
            else
            {
                refuse(R.string.air_pin_wrong);
            }
            return;
        }

        // ---- set mode ----
        if (firstEntry == null)
        {
            firstEntry = pin;
            entered.setLength(0);
            showTitle(R.string.air_pin_repeat_title);
            titleView.announceForAccessibility(titleView.getText());
            updateCells();
            return;
        }

        if (firstEntry.equals(pin))
        {
            AppLockHelper.savePin(this, pin);
            setResult(RESULT_OK);
            finish();
        }
        else
        {
            // mismatch: the whole set flow starts again from the first pass, and the refusal stands under
            // the cells (KHANDAQ redesign E21: a refusal stands at its place, never in a toast)
            firstEntry = null;
            showTitle(R.string.pin_set_title);
            refuse(R.string.air_pin_mismatch);
        }
    }

    /** Shakes the cells once (not with reduced motion), says why under them, then empties them. */
    private void refuse(@StringRes final int words)
    {
        entered.setLength(0);
        refused = true;
        AirStepKit.shakeOnce(cellsRow);
        cellsRow.removeCallbacks(emptyCells);
        cellsRow.postDelayed(emptyCells, CLEAR_DELAY_MS);
        AirUi.sayOnLine(refusal, getString(words), true);
        if (unlockMode)
        {
            // White on the dark lock ground, where the danger colour of the light theme does not read; iOS
            // says it on the lock in white too.
            refusal.setTextColor(AirUi.color(this, R.color.kq_fixed_white));
        }
    }

    private void showTitle(@StringRes final int title)
    {
        titleView.setText(title);
        setTitle(getString(title));
        cellsRow.setContentDescription(titleView.getText());
    }

    /** One dot per digit typed; the cell the next digit goes into has the ink frame. */
    private void updateCells()
    {
        final int count = entered.length();
        for (int i = 0; i < PIN_LENGTH; i++)
        {
            dots[i].setVisibility(i < count ? View.VISIBLE : View.INVISIBLE);
            cells[i].setActivated(i == count);
        }
        cellsRow.setContentDescription(titleView.getText());
        ViewCompat.setStateDescription(cellsRow, getString(R.string.air_pin_entered_format, count, PIN_LENGTH));
    }

    // ---- layout ----

    private static TextView centred(final TextView view)
    {
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        return view;
    }

    private LinearLayout.LayoutParams spaced(@DimenRes final int top)
    {
        final LinearLayout.LayoutParams params = AirScreenKit.matchWidth();
        params.topMargin = getResources().getDimensionPixelSize(top);
        return params;
    }

    private LinearLayout.LayoutParams centredWrap(@DimenRes final int top)
    {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                               ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = getResources().getDimensionPixelSize(top);
        return params;
    }
}

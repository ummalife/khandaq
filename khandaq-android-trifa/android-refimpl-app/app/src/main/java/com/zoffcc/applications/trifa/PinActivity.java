package com.zoffcc.applications.trifa;

import android.animation.ObjectAnimator;
import android.os.Bundle;
import android.view.View;
import android.view.animation.CycleInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.khandaq.messenger.R;

/**
 * KHANDAQ PIN screen (Figma and_pin_entry / and_pin_set). A LOCAL UI gate — it never touches DB
 * encryption / DbSecretKeyStorage; the database still opens with its own key. Two modes via the
 * {@link #EXTRA_MODE} extra:
 *   MODE_SET    — from Profile → Детали: enter a 4-digit PIN, confirm it, then persist its salted hash.
 *                 Returns RESULT_OK only after a successful set, so the caller enables the lock only then.
 *   MODE_UNLOCK — from {@link AppLockHelper} after a background timeout, or on a not-yet-unlocked
 *                 fresh process: verify the PIN to return to the app.
 */
public class PinActivity extends AppCompatActivity
{
    static final String EXTRA_MODE = "mode";
    static final String MODE_SET = "set";
    static final String MODE_UNLOCK = "unlock";

    private static final int PIN_LENGTH = 4;

    private boolean unlockMode;
    private final StringBuilder entered = new StringBuilder();
    private String firstEntry; // set-mode: the PIN from the first pass, awaiting confirmation

    private TextView titleView;
    private final ImageView[] dots = new ImageView[PIN_LENGTH];

    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin);

        unlockMode = MODE_UNLOCK.equals(getIntent().getStringExtra(EXTRA_MODE));

        titleView = findViewById(R.id.pin_title);
        dots[0] = findViewById(R.id.pin_dot_0);
        dots[1] = findViewById(R.id.pin_dot_1);
        dots[2] = findViewById(R.id.pin_dot_2);
        dots[3] = findViewById(R.id.pin_dot_3);

        final View headerBar = findViewById(R.id.pin_header_bar);
        final View wordmark = findViewById(R.id.pin_wordmark);
        final View back = findViewById(R.id.pin_back_button);

        if (unlockMode)
        {
            headerBar.setVisibility(View.GONE);
            wordmark.setVisibility(View.VISIBLE);
            titleView.setText(R.string.pin_enter_title);
        }
        else
        {
            headerBar.setVisibility(View.VISIBLE);
            wordmark.setVisibility(View.GONE);
            titleView.setText(R.string.pin_set_title);
            if (back != null)
            {
                back.setOnClickListener(v -> finish());
            }
        }

        wireKey(R.id.key_0, '0');
        wireKey(R.id.key_1, '1');
        wireKey(R.id.key_2, '2');
        wireKey(R.id.key_3, '3');
        wireKey(R.id.key_4, '4');
        wireKey(R.id.key_5, '5');
        wireKey(R.id.key_6, '6');
        wireKey(R.id.key_7, '7');
        wireKey(R.id.key_8, '8');
        wireKey(R.id.key_9, '9');

        final View del = findViewById(R.id.key_del);
        if (del != null)
        {
            del.setOnClickListener(v -> onDelete());
        }

        updateDots();
    }

    private void wireKey(final int id, final char digit)
    {
        final View v = findViewById(id);
        if (v != null)
        {
            v.setOnClickListener(view -> onDigit(digit));
        }
    }

    private void onDigit(final char digit)
    {
        if (entered.length() >= PIN_LENGTH)
        {
            return;
        }
        entered.append(digit);
        updateDots();
        if (entered.length() == PIN_LENGTH)
        {
            // let the last dot render before acting
            dots[PIN_LENGTH - 1].post(this::onComplete);
        }
    }

    private void onDelete()
    {
        if (entered.length() > 0)
        {
            entered.deleteCharAt(entered.length() - 1);
            updateDots();
        }
    }

    private void onComplete()
    {
        final String pin = entered.toString();
        if (unlockMode)
        {
            if (AppLockHelper.verifyPin(this, pin))
            {
                // KHANDAQ (audit #8): this process is now unlocked. Must happen BEFORE finish() —
                // the screen underneath gets onStart() again and would otherwise re-raise the gate.
                AppLockHelper.markProcessUnlocked();
                finish();
            }
            else
            {
                rejectAndReset(null);
            }
            return;
        }

        // ---- set mode ----
        if (firstEntry == null)
        {
            firstEntry = pin;
            entered.setLength(0);
            titleView.setText(R.string.pin_confirm_title);
            updateDots();
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
            // mismatch: shake, toast, restart the whole set flow from the first pass
            firstEntry = null;
            rejectAndReset(getString(R.string.pin_mismatch));
            titleView.setText(R.string.pin_set_title);
        }
    }

    /** Shake the dots, optionally toast, then clear the current entry. */
    private void rejectAndReset(final String toast)
    {
        if (toast != null)
        {
            Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
        }
        final View dotsRow = findViewById(R.id.pin_dots);
        if (dotsRow != null)
        {
            final ObjectAnimator shake = ObjectAnimator.ofFloat(dotsRow, "translationX", 0f, 24f);
            shake.setDuration(360);
            shake.setInterpolator(new CycleInterpolator(3f));
            shake.start();
        }
        entered.setLength(0);
        dotsRow.postDelayed(this::updateDots, 120);
    }

    private void updateDots()
    {
        final int n = entered.length();
        for (int i = 0; i < PIN_LENGTH; i++)
        {
            dots[i].setImageResource(i < n ? R.drawable.pin_dot_filled : R.drawable.pin_dot_empty);
        }
    }

    @Override
    public void onBackPressed()
    {
        if (unlockMode)
        {
            // Do NOT let the user bypass the lock with the back button.
            return;
        }
        super.onBackPressed();
    }
}

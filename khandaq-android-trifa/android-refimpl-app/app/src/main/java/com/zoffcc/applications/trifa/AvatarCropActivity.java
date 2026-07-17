package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.RelativeLayout;

import com.yalantis.ucrop.UCropActivity;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * KHANDAQ: the avatar crop screen ("Редактировать фото"). Stock uCrop, plus two fixes the stock
 * activity lacks:
 * <p>
 * 1. Edge-to-edge insets — targetSdk 35 draws behind the system bars and uCrop 2.2.8 predates
 * insets handling, so the toolbar (X / title / ✓) collided with the status-bar clock and the
 * bottom controls sat under the gesture bar. Pad them by the real system-bar insets (no-op on
 * devices where the decor already fits the system windows).
 * <p>
 * 2. A big round thumb-friendly confirm button above the bottom controls — the stock tiny ✓ in
 * the top-right corner is easy to miss and hard to reach one-handed. The top ✓ still works too.
 */
public class AvatarCropActivity extends UCropActivity
{
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        final View root = findViewById(com.yalantis.ucrop.R.id.ucrop_photobox);
        final View toolbar = findViewById(com.yalantis.ucrop.R.id.toolbar);
        final View controls = findViewById(com.yalantis.ucrop.R.id.controls_wrapper);

        if (root instanceof RelativeLayout)
        {
            final float d = getResources().getDisplayMetrics().density;

            final ImageButton confirm = new ImageButton(this);
            confirm.setImageResource(R.drawable.ic_check_white_24);
            final GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(getResources().getColor(R.color.khandaq_teal));
            confirm.setBackground(bg);
            confirm.setElevation(8 * d);
            confirm.setContentDescription(getString(android.R.string.ok));
            final int pad = (int) (16 * d);
            confirm.setPadding(pad, pad, pad, pad);

            final RelativeLayout.LayoutParams lp =
                    new RelativeLayout.LayoutParams((int) (60 * d), (int) (60 * d));
            lp.addRule(RelativeLayout.ABOVE, com.yalantis.ucrop.R.id.controls_wrapper);
            lp.addRule(RelativeLayout.ALIGN_PARENT_END);
            lp.setMargins(0, 0, (int) (20 * d), (int) (20 * d));
            ((RelativeLayout) root).addView(confirm, lp);

            confirm.setOnClickListener(v -> cropAndSaveImage());
        }

        if (root != null)
        {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, wi) ->
            {
                final Insets sys = wi.getInsets(WindowInsetsCompat.Type.systemBars());
                if (toolbar != null)
                {
                    toolbar.setPadding(toolbar.getPaddingLeft(), sys.top,
                            toolbar.getPaddingRight(), toolbar.getPaddingBottom());
                }
                if (controls != null)
                {
                    controls.setPadding(0, 0, 0, sys.bottom);
                }
                return wi;
            });
        }
    }
}
